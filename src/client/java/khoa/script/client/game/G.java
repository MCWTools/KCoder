package khoa.script.client.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Val;

/** Small helpers shared by the game libraries. All of them must be called on the game thread (Builtin.client()). */
public final class G {
	private G() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static LocalPlayer me() {
		LocalPlayer p = mc().player;
		if (p == null || mc().level == null) {
			throw new KcdError("You are not in a world", 0).withHint("join a world first, or check with is_in_world()");
		}
		return p;
	}

	public static ClientLevel level() {
		ClientLevel l = mc().level;
		if (l == null) {
			throw new KcdError("You are not in a world", 0);
		}
		return l;
	}

	public static Val.Vec3 v(Vec3 v) {
		return new Val.Vec3(v.x, v.y, v.z);
	}

	public static Vec3 mv(Val.Vec3 v) {
		return new Vec3(v.x(), v.y(), v.z());
	}

	public static BlockPos bp(Val.Vec3 v) {
		return BlockPos.containing(v.x(), v.y(), v.z());
	}

	/** "allay" and "minecraft:allay" both become "minecraft:allay". */
	public static String norm(String id) {
		String s = id.trim().toLowerCase();
		return s.indexOf(':') < 0 ? "minecraft:" + s : s;
	}

	public static String entityId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	public static Identifier id(String s) {
		Identifier i = Identifier.tryParse(norm(s));
		if (i == null) {
			throw new KcdError("'" + s + "' is not a valid id", 0);
		}
		return i;
	}

	/** Entity from an argument: an entity ref, or (when allowed) a player name; null/absent = the local player. */
	public static Entity entityOrMe(Args a, int i) {
		Object o = a.obj(i);
		if (o == null) {
			return me();
		}
		if (o instanceof EntityRef r) {
			return r.entity();
		}
		if (o instanceof String name) {
			for (Entity e : level().players()) {
				if (e.getName().getString().equalsIgnoreCase(name)) {
					return e;
				}
			}
			throw new KcdError("Player '" + name + "' is not in render distance", 0);
		}
		throw a.bad(i, "a player, an entity, a player name or nil");
	}

	public static Entity entity(Args a, int i) {
		if (a.obj(i) instanceof EntityRef r) {
			return r.entity();
		}
		throw a.bad(i, "an entity");
	}

	/** Eye position of the local player. */
	public static Vec3 eye() {
		return me().getEyePosition();
	}

	/** Distance from a point to the nearest point of a box (0 inside it). */
	public static double distanceToBox(Vec3 p, AABB b) {
		double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
		double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
		double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static List<Object> asList(Iterable<? extends Entity> it) {
		List<Object> out = new ArrayList<>();
		for (Entity e : it) {
			out.add(new EntityRef(e));
		}
		return out;
	}
}
