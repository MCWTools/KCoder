package khoa.script.client.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.script.client.ScriptRuntime;
import khoa.script.lang.Convert;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/**
 * hitbox_entity(entity, scale): the client sees a bigger (or smaller) hitbox, the server never hears about it.
 * A hit on the part of the box that is NOT inside the real hitbox fires on_hit but sends no packet, so the server
 * cannot flag it. get_nearest_angle_to_aim(entity) gives the rotation that moves the crosshair to the closest point of the real hitbox.
 */
public final class HitboxLib {
	private static final Registry R = Registry.get();
	private static final double MAX_SCALE = 32;

	/** True while the vanilla box is being read (EntityMixin returns the field untouched). Game thread only. */
	public static volatile boolean raw;

	private static final Map<Entity, HitboxRef> BY_ENTITY = Collections.synchronizedMap(new IdentityHashMap<>());
	private static final Set<Session> HOOKED = Collections.synchronizedSet(new HashSet<>());

	private HitboxLib() {
	}

	// ------------------------------------------------------------ box helpers
	public static AABB scaled(AABB b, double s) {
		double cx = (b.minX + b.maxX) / 2;
		double cy = (b.minY + b.maxY) / 2;
		double cz = (b.minZ + b.maxZ) / 2;
		double hx = (b.maxX - b.minX) / 2 * s;
		double hy = (b.maxY - b.minY) / 2 * s;
		double hz = (b.maxZ - b.minZ) / 2 * s;
		return new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
	}

	/** The vanilla hitbox of an entity, whatever scale it has. */
	public static AABB realBox(Entity e) {
		raw = true;
		try {
			return e.getBoundingBox();
		} finally {
			raw = false;
		}
	}

	static double checkScale(Object v) {
		if (!(v instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() <= 0 || n.doubleValue() > MAX_SCALE) {
			throw new KcdError("hitbox scale must be a number above 0 and at most " + (int) MAX_SCALE + " (1 = normal), got " + Val.repr(v, 1), 0);
		}
		return n.doubleValue();
	}

	// ------------------------------------------------------------ registry of hitboxes
	private static HitboxRef find(Entity e) {
		HitboxRef h = BY_ENTITY.get(e);
		if (h == null) {
			return null;
		}
		if (h.removed) {
			BY_ENTITY.remove(e);
			return null;
		}
		return h;
	}

	private static void purge() {
		synchronized (BY_ENTITY) {
			BY_ENTITY.entrySet().removeIf(en -> en.getValue().removed || en.getKey().isRemoved());
		}
	}

	private static void remove(HitboxRef h) {
		h.removed = true;
		((HitboxScale) h.entity).khoa$setScale(1.0);
		BY_ENTITY.remove(h.entity, h);
	}

	private static void hook(Session s) {
		if (HOOKED.add(s)) {
			s.onStop.add(() -> {
				HOOKED.remove(s);
				List<HitboxRef> mine = new ArrayList<>();
				synchronized (BY_ENTITY) {
					for (HitboxRef h : BY_ENTITY.values()) {
						if (h.session == s) {
							mine.add(h);
						}
					}
				}
				mine.forEach(HitboxLib::remove);
			});
		}
	}

	// ------------------------------------------------------------ hits (called from MultiPlayerGameModeMixin)
	/** True when the pick ray of the local player really crosses the vanilla hitbox (the server would accept it). */
	private static boolean isRealHit(Player player, Entity target) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(player.entityInteractionRange()));
		AABB real = realBox(target).inflate(target.getPickRadius());
		return real.contains(eye) || real.clip(eye, end).isPresent();
	}

	/** @return true to cancel the attack packet: the hit only landed on the fake part of the box. */
	public static boolean onAttack(Player player, Entity target) {
		HitboxRef h = BY_ENTITY.isEmpty() ? null : find(target);
		if (h == null || !(player instanceof LocalPlayer)) {
			return false;
		}
		boolean real = isRealHit(player, target);
		Map<String, Object> d = new HashMap<>();
		d.put("hitbox", h);
		d.put("entity", new EntityRef(target));
		d.put("real", real);
		d.put("distance", G.distanceToBox(player.getEyePosition(), realBox(target)));
		ScriptRuntime.engine().events.fire("on_hit", d);
		return !real;
	}

	/** @return true to cancel the interact packet (right click on the fake part of a box). */
	public static boolean onInteract(Player player, Entity target) {
		HitboxRef h = BY_ENTITY.isEmpty() ? null : find(target);
		return h != null && player instanceof LocalPlayer && !isRealHit(player, target);
	}

	// ------------------------------------------------------------ nearest aim
	private static double cos(Vec3 eye, Vec3 look, Vec3 p) {
		Vec3 d = p.subtract(eye);
		double len = d.length();
		return len < 1e-9 ? 1 : d.dot(look) / len;
	}

	/** Point of the segment a-b with the smallest angle to the look direction: the angle is unimodal along a segment that does not pass the eye. */
	private static Vec3 bestOnSegment(Vec3 eye, Vec3 look, Vec3 a, Vec3 b) {
		double lo = 0;
		double hi = 1;
		for (int i = 0; i < 60; i++) {
			double m1 = lo + (hi - lo) / 3;
			double m2 = hi - (hi - lo) / 3;
			if (cos(eye, look, a.lerp(b, m1)) < cos(eye, look, a.lerp(b, m2))) {
				lo = m1;
			} else {
				hi = m2;
			}
		}
		return a.lerp(b, (lo + hi) / 2);
	}

	private static double inside(double v, double min, double max) {
		double e = Math.min(0.02, (max - min) / 4);
		if (v <= min + 1e-9) {
			return min + e;
		}
		if (v >= max - 1e-9) {
			return max - e;
		}
		return v;
	}

	/** Moves a point on the surface a little inside the box, so the aim lands in the box and not exactly on its edge. */
	private static Vec3 nudgeInside(Vec3 p, AABB b) {
		return new Vec3(inside(p.x, b.minX, b.maxX), inside(p.y, b.minY, b.maxY), inside(p.z, b.minZ, b.maxZ));
	}

	/**
	 * The rotation that puts the crosshair on the point of the box closest (in angle) to where you aim now.
	 * When the aim misses a box, the closest direction always touches the box on one of its 12 edges, so only the edges are searched.
	 */
	static Val.Rotation nearestAim(LocalPlayer p, AABB box) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		if (box.contains(eye) || box.clip(eye, eye.add(look.scale(512))).isPresent()) {
			return new Val.Rotation(Convert.wrapDegrees(p.getYRot()), p.getXRot()); // already on it
		}
		double[] xs = { box.minX, box.maxX };
		double[] ys = { box.minY, box.maxY };
		double[] zs = { box.minZ, box.maxZ };
		Vec3 best = null;
		double bestCos = -2;
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				Vec3[] ends = {
					new Vec3(xs[0], ys[i], zs[j]), new Vec3(xs[1], ys[i], zs[j]), // edge along x
					new Vec3(xs[i], ys[0], zs[j]), new Vec3(xs[i], ys[1], zs[j]), // edge along y
					new Vec3(xs[i], ys[j], zs[0]), new Vec3(xs[i], ys[j], zs[1]), // edge along z
				};
				for (int k = 0; k < 6; k += 2) {
					Vec3 q = bestOnSegment(eye, look, ends[k], ends[k + 1]);
					double c = cos(eye, look, q);
					if (c > bestCos) {
						bestCos = c;
						best = q;
					}
				}
			}
		}
		return Convert.directionToRotation(G.v(nudgeInside(best, box).subtract(eye)));
	}

	// ------------------------------------------------------------ script API
	private static Object box(AABB b) {
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("min", new Val.Vec3(b.minX, b.minY, b.minZ));
		m.put("max", new Val.Vec3(b.maxX, b.maxY, b.maxZ));
		return m;
	}

	public static void register() {
		R.def("entity", "hitbox_entity(entity:entity, scale?:number=1) -> hitbox",
			"Makes the CLIENT see a hitbox of this entity scaled by `scale` (1 = normal, 2 = twice as big around its centre). Crosshair, F3+B and hitbox mods all see the big box. "
				+ "The server is never told: hitting the part outside the real hitbox fires hitbox.On_Hit but sends NO attack packet (so no anticheat flag); a hit inside the real hitbox is sent as usual. "
				+ "Calling it again for the same entity changes its scale. It lasts until hitbox.remove() or the script stops.", a -> {
				Entity e = G.entity(a, 0);
				if (e == G.mc().player) {
					throw new KcdError("hitbox_entity(): you cannot change your own hitbox", 0);
				}
				double s = checkScale(a.has(1) ? a.obj(1) : (Object) 1.0);
				purge();
				HitboxRef h = find(e);
				if (h == null) {
					h = new HitboxRef(a.in.session, e);
					BY_ENTITY.put(e, h);
					hook(a.in.session);
				}
				((HitboxScale) e).khoa$setScale(s);
				return h;
			}).client().example("zombie = get_nearest_entity(\"zombie\")\nbox = hitbox_entity(zombie, 2.5)\nbox.On_Hit:\n    print(\"hit! real=\" + str(real))");
		R.def("camera", "get_nearest_angle_to_aim(target:entity) -> rotation",
			"Yaw/pitch that moves your crosshair onto the point of the target's REAL (vanilla) hitbox that is closest to where you aim now. If you already aim at it, gives your current rotation. Use it with set_player_direction(r.yaw, r.pitch) or turn_towards.", a -> {
				Entity e = a.obj(0) instanceof HitboxRef h ? h.entity : G.entity(a, 0);
				return nearestAim(G.me(), realBox(e));
			}).client().perm("look").example("r = get_nearest_angle_to_aim(zombie)\nturn_towards(r.yaw, r.pitch, 0.3)");

		R.typeDoc("hitbox", "A client-side scaled hitbox of an entity (from hitbox_entity). hitbox.scale (settable), hitbox.entity, hitbox.active, hitbox.remove(), hitbox.On_Hit: ...");
		R.clientTypes.add("hitbox");
		R.prop("hitbox", "entity", "entity", "The entity it belongs to.", (in, s, g) -> new EntityRef(((HitboxRef) s).entity));
		R.prop("hitbox", "scale", "number", "Current scale (set it: box.scale = 3).", (in, s, g) -> ((HitboxRef) s).scale());
		R.prop("hitbox", "active", "boolean", "False after remove(), after the script stopped, or when the entity is gone.", (in, s, g) -> ((HitboxRef) s).active());
		R.prop("hitbox", "real_box", "map", "The vanilla hitbox: map with min and max (vec3).", (in, s, g) -> box(((HitboxRef) s).realBox()));
		R.prop("hitbox", "box", "map", "The box the client sees now (scaled): map with min and max (vec3).", (in, s, g) -> box(((HitboxRef) s).entity.getBoundingBox()));
		R.method("hitbox", "remove()", "Gives the entity its normal hitbox back. Its On_Hit handlers stop firing.", (in, s, g) -> {
			remove((HitboxRef) s);
			return null;
		});

		R.event("on_hit", "You hit (left clicked) an entity that has a hitbox_entity() box: box.On_Hit: or on_hit(box):. Gives hitbox, entity, real (true if the hit was inside the real hitbox and was sent to the server, false if it only landed on the fake part and stayed on the client), distance (from your eyes to the real hitbox).",
			List.of("hitbox"), Map.of("hitbox", "hitbox", "entity", "entity", "real", "boolean", "distance", "number"),
			(f, d) -> f.isEmpty() || f.get(0) == d.get("hitbox") || f.get(0) instanceof EntityRef r && r.equals(d.get("entity"))).skipIfBusy = false;
	}
}
