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

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.api.aim.KhoaAim;
import khoa.api.hitbox.HitEvent;
import khoa.api.hitbox.KhoaHitbox;

import khoa.script.client.ScriptRuntime;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/**
 * hitbox_entity(entity, scale): the client sees a bigger (or smaller) hitbox, the server never hears about it.
 * A hit on the part of the box that is NOT inside the real hitbox fires on_hit but sends no packet, so the server
 * cannot flag it. get_nearest_angle_to_aim(entity) gives the rotation that moves the crosshair to the closest point of the real hitbox.
 * The scaling, the real box and the aim maths live in KhoaAPILibs (khoa.api.hitbox.KhoaHitbox, khoa.api.aim.KhoaAim); this class only owns the script handles.
 */
public final class HitboxLib {
	private static final Registry R = Registry.get();

	private static final Map<Entity, HitboxRef> BY_ENTITY = Collections.synchronizedMap(new IdentityHashMap<>());
	private static final Set<Session> HOOKED = Collections.synchronizedSet(new HashSet<>());

	private HitboxLib() {
	}

	// ------------------------------------------------------------ box helpers
	/** The vanilla hitbox of an entity, whatever scale it has (KhoaAPILibs). */
	public static AABB realBox(Entity e) {
		return KhoaHitbox.realBox(e);
	}

	static double checkScale(Object v) {
		if (!(v instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() <= 0 || n.doubleValue() > KhoaHitbox.MAX_SCALE) {
			throw new KcdError("hitbox scale must be a number above 0 and at most " + (int) KhoaHitbox.MAX_SCALE + " (1 = normal), got " + Val.repr(v, 1), 0);
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
		KhoaHitbox.reset(h.entity);
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

	// ------------------------------------------------------------ hits (KhoaHitbox fires them, KhoaAPILibs cancels the fake ones)
	private static void onHit(HitEvent ev) {
		HitboxRef h = BY_ENTITY.isEmpty() ? null : find(ev.entity());
		if (h == null) {
			return;
		}
		Map<String, Object> d = new HashMap<>();
		d.put("hitbox", h);
		d.put("entity", new EntityRef(ev.entity()));
		d.put("real", ev.real());
		d.put("distance", ev.distance());
		ScriptRuntime.engine().events.fire("on_hit", d);
	}

	// ------------------------------------------------------------ script API
	private static Val.Rotation toVal(khoa.api.aim.Rotation r) {
		return new Val.Rotation(r.yaw(), r.pitch());
	}

	private static Object box(AABB b) {
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("min", new Val.Vec3(b.minX, b.minY, b.minZ));
		m.put("max", new Val.Vec3(b.maxX, b.maxY, b.maxZ));
		return m;
	}

	public static void register() {
		KhoaHitbox.addHitListener(HitboxLib::onHit);
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
				KhoaHitbox.scale(e, s);
				return h;
			}).client().example("zombie = get_nearest_entity(\"zombie\")\nbox = hitbox_entity(zombie, 2.5)\nbox.On_Hit:\n    print(\"hit! real=\" + str(real))");
		R.def("camera", "get_nearest_angle_to_aim(target:entity) -> rotation",
			"Yaw/pitch that moves your crosshair onto the point of the target's REAL (vanilla) hitbox that is closest to where you aim now. If you already aim at it, gives your current rotation. Use it with set_player_direction(r.yaw, r.pitch) or turn_towards.", a -> {
				Entity e = a.obj(0) instanceof HitboxRef h ? h.entity : G.entity(a, 0);
				return toVal(KhoaAim.getNearestAngleToAim(G.me(), realBox(e)));
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
