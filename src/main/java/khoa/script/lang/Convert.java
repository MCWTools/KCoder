package khoa.script.lang;

import java.util.List;

/** Conversions between script values. */
public final class Convert {
	private Convert() {
	}

	/** Things that have a world position (entities, blocks ...). */
	/** Has a box (entity hitbox, block cell): {minX, minY, minZ, maxX, maxY, maxZ}. */
	public interface HasBox {
		double[] box();
	}

	public interface HasPos {
		Val.Vec3 pos();
	}

	public static Val.Vec3 toVec3(Object o) {
		if (o instanceof Val.Vec3 v) {
			return v;
		}
		if (o instanceof HasPos h) {
			return h.pos();
		}
		if (o instanceof List<?> l && l.size() == 3 && l.get(0) instanceof Double x && l.get(1) instanceof Double y && l.get(2) instanceof Double z) {
			return new Val.Vec3(x, y, z);
		}
		if (o instanceof Val.Tuple t && t.items().size() == 3) {
			return toVec3(t.items());
		}
		if (o instanceof java.util.Map<?, ?> m) {
			// get_camera() and similar info maps: use their position
			Object p = m.get("position") != null ? m.get("position") : m.get("pos");
			if (p != null && !(p instanceof java.util.Map)) {
				return toVec3(p);
			}
		}
		return null;
	}

	public static double wrapDegrees(double d) {
		d = d % 360.0;
		if (d >= 180.0) {
			d -= 360.0;
		}
		if (d < -180.0) {
			d += 360.0;
		}
		return d;
	}

	/** Minecraft yaw/pitch (degrees) from a direction: yaw 0 = +Z (south), yaw -90 = +X (east), pitch -90 = up. */
	public static Val.Rotation directionToRotation(Val.Vec3 d) {
		double horiz = Math.sqrt(d.x() * d.x() + d.z() * d.z());
		double yaw = Math.toDegrees(Math.atan2(-d.x(), d.z()));
		double pitch = -Math.toDegrees(Math.atan2(d.y(), horiz));
		return new Val.Rotation(wrapDegrees(yaw), pitch);
	}

	public static Val.Vec3 rotationToDirection(double yaw, double pitch) {
		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		double cp = Math.cos(pr);
		return new Val.Vec3(-Math.sin(yr) * cp, -Math.sin(pr), Math.cos(yr) * cp);
	}
}
