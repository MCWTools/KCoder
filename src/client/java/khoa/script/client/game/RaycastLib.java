package khoa.script.client.game;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Args;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** block_raycast, entity_raycast and mixed_raycast: rays from your eyes (or any point) in any direction. */
public final class RaycastLib {
	private static final Registry R = Registry.get();

	private RaycastLib() {
	}

	private static Vec3 from(Args a) {
		return a.has(1) ? G.mv(a.vec3(1)) : G.eye();
	}

	private static Vec3 dir(Args a) {
		Vec3 d = a.has(2) ? G.mv(a.vec3(2)) : G.me().getLookAngle();
		return d.lengthSqr() < 1e-9 ? G.me().getLookAngle() : d.normalize();
	}

	private static BlockHitResult block(Vec3 f, Vec3 to, boolean fluids) {
		return G.level().clip(new ClipContext(f, to, ClipContext.Block.OUTLINE, fluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, G.me()));
	}

	/** Nearest entity along the segment, or null. */
	private static Entity[] entity(Vec3 f, Vec3 to, double margin, double[] distOut, Vec3[] pointOut) {
		AABB search = new AABB(f, to).inflate(1 + margin);
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : G.level().getEntities(G.me(), search, x -> !x.isSpectator())) {
			Optional<Vec3> hit = e.getBoundingBox().inflate(margin).clip(f, to);
			Vec3 p = hit.orElse(e.getBoundingBox().inflate(margin).contains(f) ? f : null);
			if (p == null) {
				continue;
			}
			double d = f.distanceToSqr(p);
			if (d < bestD) {
				bestD = d;
				best = e;
				pointOut[0] = p;
			}
		}
		distOut[0] = Math.sqrt(bestD);
		return best == null ? null : new Entity[] { best };
	}

	private static Map<Object, Object> withDistance(Map<Object, Object> m, Vec3 from) {
		Object p = m.get("point");
		if (p instanceof Val.Vec3 v) {
			m.put("distance", from.distanceTo(new Vec3(v.x(), v.y(), v.z())));
		}
		return m;
	}

	private static Map<Object, Object> entityMap(Entity e, Vec3 point, double dist) {
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("hit", true);
		m.put("type", "entity");
		m.put("point", G.v(point));
		m.put("entity", new EntityRef(e));
		m.put("distance", dist);
		return m;
	}

	private static Map<Object, Object> miss() {
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("hit", false);
		m.put("type", "miss");
		return m;
	}

	public static void register() {
		String c = "world";
		R.def(c, "block_raycast(range?:number=64, from?:vec3, direction?:vec3, fluids?:boolean=false) -> map", "Ray from your eyes (or from) along direction (default: where you look) against blocks. Map: hit, type, pos, point, face, block, distance.", a -> {
			Vec3 f = from(a);
			BlockHitResult h = block(f, f.add(dir(a).scale(a.num(0, 64))), a.bool(3, false));
			return withDistance(WorldLib.hit(h.getType() == HitResult.Type.MISS ? null : h), f);
		}).client().example("r = block_raycast(20)\nif r.hit:\n    overlay_block(r.block, \"hitbox\")");
		R.def(c, "entity_raycast(range?:number=64, from?:vec3, direction?:vec3, margin?:number=0.1) -> map", "Ray against entities only (ignores walls). Map: hit, type, point, entity, distance.", a -> {
			Vec3 f = from(a);
			Vec3 to = f.add(dir(a).scale(a.num(0, 64)));
			double[] dist = new double[1];
			Vec3[] pt = new Vec3[1];
			Entity[] e = entity(f, to, a.num(3, 0.1), dist, pt);
			return e == null ? miss() : entityMap(e[0], pt[0], dist[0]);
		}).client();
		R.def(c, "mixed_raycast(range?:number=64, from?:vec3, direction?:vec3, margin?:number=0.1, fluids?:boolean=false) -> map", "Ray against blocks and entities: gives whichever is hit first (entities behind a wall do not count). Map: hit, type (\"block\", \"entity\" or \"miss\"), point, distance, plus pos/face/block or entity.", a -> {
			Vec3 f = from(a);
			Vec3 to = f.add(dir(a).scale(a.num(0, 64)));
			BlockHitResult bh = block(f, to, a.bool(4, false));
			boolean blockHit = bh.getType() != HitResult.Type.MISS;
			Vec3 limit = blockHit ? bh.getLocation() : to;
			double[] dist = new double[1];
			Vec3[] pt = new Vec3[1];
			Entity[] e = entity(f, limit, a.num(3, 0.1), dist, pt);
			if (e != null) {
				return entityMap(e[0], pt[0], dist[0]);
			}
			return blockHit ? withDistance(WorldLib.hit(bh), f) : miss();
		}).client();
	}
}
