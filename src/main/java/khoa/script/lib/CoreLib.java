package khoa.script.lib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import khoa.script.lang.Args;
import khoa.script.lang.Convert;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Pure language library: math, vectors, strings, lists, maps, conversions. No game access. */
public final class CoreLib {
	private static final Registry R = Registry.get();
	public static Random random = new Random();

	private CoreLib() {
	}

	public static void register() {
		UtilLib.register();
		StringNsLib.register();
		math();
		vectors();
		strings();
		lists();
		maps();
		conversions();
		types();
	}

	private static double d(Object o, String what) {
		if (o instanceof Double x) {
			return x;
		}
		throw new KcdError(what + " must be a number, got " + Val.typeName(o), 0);
	}

	// ---------------------------------------------------------------- math
	private static void math() {
		String c = "math";
		R.def(c, "abs(x:number) -> number", "Absolute value.", a -> Math.abs(a.num(0))).example("abs(-3)  # 3");
		R.def(c, "min(...values:any) -> any", "Smallest of the values (or of one list).", a -> extreme(a, true)).example("min(3, 1, 2)  # 1");
		R.def(c, "max(...values:any) -> any", "Largest of the values (or of one list).", a -> extreme(a, false)).example("max([3, 1, 2])  # 3");
		R.def(c, "floor(x:number) -> number", "Round down.", a -> Math.floor(a.num(0)));
		R.def(c, "ceil(x:number) -> number", "Round up.", a -> Math.ceil(a.num(0)));
		R.def(c, "round(x:number, digits?:number=0) -> number", "Round to the nearest value, optionally to some decimals.", a -> {
			double p = Math.pow(10, a.num(1, 0));
			return Math.rint(a.num(0) * p) / p;
		}).example("round(3.14159, 2)  # 3.14");
		R.def(c, "sqrt(x:number) -> number", "Square root.", a -> Math.sqrt(a.num(0)));
		R.def(c, "pow(x:number, y:number) -> number", "x to the power y (also x ** y).", a -> Math.pow(a.num(0), a.num(1)));
		R.def(c, "sign(x:number) -> number", "-1, 0 or 1.", a -> Math.signum(a.num(0)));
		R.def(c, "sin(x:number) -> number", "Sine. x is in RADIANS (use sin_deg for degrees).", a -> Math.sin(a.num(0)));
		R.def(c, "cos(x:number) -> number", "Cosine. x is in RADIANS (use cos_deg for degrees).", a -> Math.cos(a.num(0)));
		R.def(c, "tan(x:number) -> number", "Tangent. x is in RADIANS.", a -> Math.tan(a.num(0)));
		R.def(c, "asin(x:number) -> number", "Arc sine, result in RADIANS.", a -> Math.asin(a.num(0)));
		R.def(c, "acos(x:number) -> number", "Arc cosine, result in RADIANS.", a -> Math.acos(a.num(0)));
		R.def(c, "atan(x:number) -> number", "Arc tangent, result in RADIANS.", a -> Math.atan(a.num(0)));
		R.def(c, "atan2(y:number, x:number) -> number", "Angle of the point (x, y) in RADIANS.", a -> Math.atan2(a.num(0), a.num(1)));
		R.def(c, "sin_deg(x:number) -> number", "Sine of an angle in degrees.", a -> Math.sin(Math.toRadians(a.num(0))));
		R.def(c, "cos_deg(x:number) -> number", "Cosine of an angle in degrees.", a -> Math.cos(Math.toRadians(a.num(0))));
		R.def(c, "tan_deg(x:number) -> number", "Tangent of an angle in degrees.", a -> Math.tan(Math.toRadians(a.num(0))));
		R.def(c, "atan2_deg(y:number, x:number) -> number", "Angle of the point (x, y) in degrees.", a -> Math.toDegrees(Math.atan2(a.num(0), a.num(1))));
		R.def(c, "radians(deg:number) -> number", "Degrees to radians.", a -> Math.toRadians(a.num(0)));
		R.def(c, "degrees(rad:number) -> number", "Radians to degrees.", a -> Math.toDegrees(a.num(0)));
		R.def(c, "log(x:number) -> number", "Natural logarithm.", a -> Math.log(a.num(0)));
		R.def(c, "log10(x:number) -> number", "Base 10 logarithm.", a -> Math.log10(a.num(0)));
		R.def(c, "exp(x:number) -> number", "e to the power x.", a -> Math.exp(a.num(0)));
		R.def(c, "hypot(x:number, y:number) -> number", "sqrt(x*x + y*y).", a -> Math.hypot(a.num(0), a.num(1)));
		R.def(c, "random(a?:number, b?:number) -> number", "Random decimal. random() is 0..1, random(5) is 0..5, random(1, 3) is 1..3.", a -> {
			if (!a.has(0)) {
				return random.nextDouble();
			}
			double lo = a.has(1) ? a.num(0) : 0;
			double hi = a.has(1) ? a.num(1) : a.num(0);
			return lo + random.nextDouble() * (hi - lo);
		}).example("wait(random(1, 3) / 1000)  # 1 to 3 milliseconds");
		R.def(c, "random_int(a:number, b:number) -> number", "Random whole number from a to b (both included).", a -> {
			long lo = (long) Math.ceil(Math.min(a.num(0), a.num(1)));
			long hi = (long) Math.floor(Math.max(a.num(0), a.num(1)));
			return (double) (lo + (long) (random.nextDouble() * (hi - lo + 1)));
		});
		R.def(c, "random_choice(items:list) -> any", "A random element of a list.", a -> {
			List<Object> l = a.iter(0);
			if (l.isEmpty()) {
				throw new KcdError("random_choice(): the list is empty", 0);
			}
			return l.get(random.nextInt(l.size()));
		});
		R.def(c, "random_seed(seed:number)", "Makes random() repeatable.", a -> {
			random = new Random((long) a.num(0));
			return null;
		});
		R.def(c, "lerp(a:any, b:any, t:number) -> any", "Linear interpolation between two numbers or two vectors.", a -> {
			double t = a.num(2);
			if (a.obj(0) instanceof Double x && a.obj(1) instanceof Double y) {
				return x + (y - x) * t;
			}
			Val.Vec3 p = a.vec3(0);
			Val.Vec3 q = a.vec3(1);
			return p.add(q.sub(p).mul(t));
		}).example("lerp(0, 10, 0.5)  # 5");
		R.def(c, "clamp(x:number, lo:number, hi:number) -> number", "Limit x to the range lo..hi.", a -> Math.max(a.num(1), Math.min(a.num(2), a.num(0))));
		R.def(c, "map_range(value:number, in_min:number, in_max:number, out_min:number, out_max:number) -> number", "Convert a value from one range to another.",
			a -> a.num(3) + (a.num(0) - a.num(1)) * (a.num(4) - a.num(3)) / (a.num(2) - a.num(1)));
		R.def(c, "sum(items:list) -> number", "Sum of a list of numbers (or vectors).", a -> {
			List<Object> l = a.iter(0);
			if (!l.isEmpty() && l.get(0) instanceof Val.Vec3) {
				Val.Vec3 s = new Val.Vec3(0, 0, 0);
				for (Object o : l) {
					s = s.add(Convert.toVec3(o));
				}
				return s;
			}
			double s = 0;
			for (Object o : l) {
				s += d(o, "sum(): every element");
			}
			return s;
		});
	}

	private static Object extreme(Args a, boolean min) {
		List<Object> vals = a.list(0);
		if (vals.size() == 1 && !(vals.get(0) instanceof Double)) {
			vals = Val.toList(vals.get(0));
		}
		if (vals.isEmpty()) {
			throw new KcdError((min ? "min" : "max") + "(): nothing to compare", 0);
		}
		Object best = vals.get(0);
		for (Object o : vals) {
			if (compare(o, best) < 0 == min && compare(o, best) != 0) {
				best = o;
			}
		}
		return best;
	}

	public static int compare(Object a, Object b) {
		if (a instanceof Double x && b instanceof Double y) {
			return Double.compare(x, y);
		}
		if (a instanceof String x && b instanceof String y) {
			return x.compareTo(y);
		}
		if (a instanceof Boolean x && b instanceof Boolean y) {
			return Boolean.compare(x, y);
		}
		throw new KcdError("Cannot compare " + Val.typeName(a) + " with " + Val.typeName(b), 0);
	}

	private static double nearestDistance(Val.Vec3 p, double[] b) {
		double dx = Math.max(Math.max(b[0] - p.x(), 0), p.x() - b[3]);
		double dy = Math.max(Math.max(b[1] - p.y(), 0), p.y() - b[4]);
		double dz = Math.max(Math.max(b[2] - p.z(), 0), p.z() - b[5]);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---------------------------------------------------------------- vectors
	private static void vectors() {
		String c = "vector";
		R.def(c, "vec2(x:number, y:number) -> vec2", "A 2D vector.", a -> new Val.Vec2(a.num(0), a.num(1)));
		R.def(c, "vec3(x:number, y:number, z:number) -> vec3", "A 3D vector (position or direction).", a -> new Val.Vec3(a.num(0), a.num(1), a.num(2)))
			.example("vec3(0, 1.5, 0)");
		R.def(c, "rotation(yaw:number, pitch:number) -> rotation", "A yaw/pitch pair in degrees. yaw, pitch = rotation(90, 0) unpacks it.", a -> new Val.Rotation(a.num(0), a.num(1)));
		R.def(c, "distance(a:vec3, b:vec3, nearest?:bool=false) -> number", "Distance between two points (entities and blocks count as their position). With nearest = true an entity or block is measured to the closest point of its box instead of its centre (this is what attack reach uses).", a -> {
			if (a.bool(2, false)) {
				Object x = a.obj(0);
				Object y = a.obj(1);
				if (y instanceof Convert.HasBox hb) {
					return nearestDistance(a.vec3(0), hb.box());
				}
				if (x instanceof Convert.HasBox hb) {
					return nearestDistance(a.vec3(1), hb.box());
				}
			}
			return a.vec3(0).sub(a.vec3(1)).length();
		})
			.example("dist = distance(player:getcamerapos(), entity, true)");
		R.def(c, "distance_squared(a:vec3, b:vec3) -> number", "Squared distance (faster, good for comparing).", a -> {
			Val.Vec3 v = a.vec3(0).sub(a.vec3(1));
			return v.dot(v);
		});
		R.def(c, "distance_xz(a:vec3, b:vec3) -> number", "Horizontal distance (ignores height).", a -> {
			Val.Vec3 v = a.vec3(0).sub(a.vec3(1));
			return Math.hypot(v.x(), v.z());
		});
		R.def(c, "magnitude(v:vec3) -> number", "Length of a vector.", a -> a.vec3(0).length());
		R.def(c, "normalize(v:vec3) -> vec3", "Same direction, length 1.", a -> {
			Val.Vec3 v = a.vec3(0);
			double l = v.length();
			return l == 0 ? v : v.mul(1 / l);
		});
		R.def(c, "dot(a:vec3, b:vec3) -> number", "Dot product.", a -> a.vec3(0).dot(a.vec3(1)));
		R.def(c, "cross(a:vec3, b:vec3) -> vec3", "Cross product.", a -> {
			Val.Vec3 p = a.vec3(0);
			Val.Vec3 q = a.vec3(1);
			return new Val.Vec3(p.y() * q.z() - p.z() * q.y(), p.z() * q.x() - p.x() * q.z(), p.x() * q.y() - p.y() * q.x());
		});
		R.def(c, "reflect(v:vec3, normal:vec3) -> vec3", "Reflect a vector off a surface with the given normal.", a -> {
			Val.Vec3 v = a.vec3(0);
			Val.Vec3 n = a.vec3(1);
			return v.sub(n.mul(2 * v.dot(n)));
		});
		R.def(c, "project(a:vec3, b:vec3) -> vec3", "Projection of a onto b.", a -> {
			Val.Vec3 b = a.vec3(1);
			double l = b.dot(b);
			return l == 0 ? b : b.mul(a.vec3(0).dot(b) / l);
		});
		R.def(c, "angle_between(a:vec3, b:vec3) -> number", "Angle between two vectors in degrees.", a -> {
			Val.Vec3 p = a.vec3(0);
			Val.Vec3 q = a.vec3(1);
			double l = p.length() * q.length();
			return l == 0 ? 0.0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, p.dot(q) / l))));
		});
		R.def(c, "rotate_vector(v:vec3, yaw:number, pitch?:number=0) -> vec3", "Rotates a vector like a look direction: pitch around X, then yaw around Y (degrees).", a -> {
			Val.Vec3 v = a.vec3(0);
			double yr = Math.toRadians(a.num(1));
			double pr = Math.toRadians(a.num(2, 0));
			double y1 = v.y() * Math.cos(pr) - v.z() * Math.sin(pr);
			double z1 = v.y() * Math.sin(pr) + v.z() * Math.cos(pr);
			return new Val.Vec3(v.x() * Math.cos(yr) + z1 * Math.sin(yr), y1, -v.x() * Math.sin(yr) + z1 * Math.cos(yr));
		});
		R.def(c, "vec_floor(v:vec3) -> vec3", "Floors every component (block coordinates).", a -> {
			Val.Vec3 v = a.vec3(0);
			return new Val.Vec3(Math.floor(v.x()), Math.floor(v.y()), Math.floor(v.z()));
		});
		R.def(c, "get_direction(from:vec3, to:vec3) -> rotation", "Yaw and pitch (degrees) that look from one point at another. Returns two values: yaw, pitch = get_direction(a, b).", a -> {
			return Convert.directionToRotation(a.vec3(1).sub(a.vec3(0)));
		}).example("yaw, pitch = get_direction(get_eye_pos(), target)");
		R.def(c, "direction_from(yaw:number, pitch:number) -> vec3", "Unit vector pointing where yaw/pitch (degrees) look.", a -> Convert.rotationToDirection(a.num(0), a.num(1)));
		R.def(c, "normalize_angle(angle:number) -> number", "Wraps an angle in degrees into -180..180.", a -> Convert.wrapDegrees(a.num(0)));
		R.def(c, "angle_diff(from:number, to:number) -> number", "Shortest signed turn in degrees from one angle to another (-180..180).", a -> Convert.wrapDegrees(a.num(1) - a.num(0)));
		R.def(c, "angle_lerp(from:number, to:number, t:number) -> number", "Interpolates between two angles along the shortest way.",
			a -> a.num(0) + Convert.wrapDegrees(a.num(1) - a.num(0)) * a.num(2));

		R.typeDoc("vec3", "A 3D position or direction. Fields: x, y, z. Methods: distance, dot, cross, normalize, lerp.");
		R.prop("vec3", "x", "number", "X component.", (in, s, g) -> ((Val.Vec3) s).x());
		R.prop("vec3", "y", "number", "Y component.", (in, s, g) -> ((Val.Vec3) s).y());
		R.prop("vec3", "z", "number", "Z component.", (in, s, g) -> ((Val.Vec3) s).z());
		R.prop("vec3", "length", "number", "Vector length.", (in, s, g) -> ((Val.Vec3) s).length());
		R.prop("vec3", "normalized", "vec3", "Same direction, length 1.", (in, s, g) -> {
			Val.Vec3 v = (Val.Vec3) s;
			return v.length() == 0 ? v : v.mul(1 / v.length());
		});
		R.prop("vec3", "floor", "vec3", "Components rounded down.", (in, s, g) -> {
			Val.Vec3 v = (Val.Vec3) s;
			return new Val.Vec3(Math.floor(v.x()), Math.floor(v.y()), Math.floor(v.z()));
		});
		R.prop("vec3", "ceil", "vec3", "Components rounded up.", (in, s, g) -> {
			Val.Vec3 v = (Val.Vec3) s;
			return new Val.Vec3(Math.ceil(v.x()), Math.ceil(v.y()), Math.ceil(v.z()));
		});
		R.method("vec3", "distance(other:vec3) -> number", "Distance to another point.", (in, s, g) -> ((Val.Vec3) s).sub(Convert.toVec3(g[0])).length());
		R.method("vec3", "dot(other:vec3) -> number", "Dot product.", (in, s, g) -> ((Val.Vec3) s).dot(Convert.toVec3(g[0])));
		R.method("vec3", "cross(other:vec3) -> vec3", "Cross product.", (in, s, g) -> {
			Val.Vec3 p = (Val.Vec3) s;
			Val.Vec3 q = Convert.toVec3(g[0]);
			return new Val.Vec3(p.y() * q.z() - p.z() * q.y(), p.z() * q.x() - p.x() * q.z(), p.x() * q.y() - p.y() * q.x());
		});
		R.method("vec3", "normalize() -> vec3", "Same direction, length 1.", (in, s, g) -> {
			Val.Vec3 v = (Val.Vec3) s;
			return v.length() == 0 ? v : v.mul(1 / v.length());
		});
		R.method("vec3", "lerp(other:vec3, t:number) -> vec3", "Interpolate towards another point.", (in, s, g) -> {
			Val.Vec3 p = (Val.Vec3) s;
			return p.add(Convert.toVec3(g[0]).sub(p).mul(d(g[1], "t")));
		});
		R.typeDoc("vec2", "A 2D vector. Fields: x, y.");
		R.prop("vec2", "x", "number", "X component.", (in, s, g) -> ((Val.Vec2) s).x());
		R.prop("vec2", "y", "number", "Y component.", (in, s, g) -> ((Val.Vec2) s).y());
		R.prop("vec2", "length", "number", "Vector length.", (in, s, g) -> Math.hypot(((Val.Vec2) s).x(), ((Val.Vec2) s).y()));
		R.typeDoc("rotation", "Yaw and pitch in degrees. yaw, pitch = rotation(...) unpacks it.");
		R.prop("rotation", "yaw", "number", "Horizontal angle (0 = south, -90 = east).", (in, s, g) -> ((Val.Rotation) s).yaw());
		R.prop("rotation", "pitch", "number", "Vertical angle (-90 = up, 90 = down).", (in, s, g) -> ((Val.Rotation) s).pitch());
		R.prop("rotation", "direction", "vec3", "Unit vector this rotation looks along.", (in, s, g) -> Convert.rotationToDirection(((Val.Rotation) s).yaw(), ((Val.Rotation) s).pitch()));
	}

	// ---------------------------------------------------------------- strings
	private static void strings() {
		String c = "string";
		R.def(c, "len(x:any) -> number", "Length of a string, list, map, tuple or range.", a -> {
			Object o = a.obj(0);
			if (o instanceof String s) {
				return (double) s.length();
			}
			if (o instanceof List<?> l) {
				return (double) l.size();
			}
			if (o instanceof Map<?, ?> m) {
				return (double) m.size();
			}
			if (o instanceof Val.Tuple t) {
				return (double) t.items().size();
			}
			if (o instanceof Val.Range r) {
				return (double) r.size();
			}
			throw a.bad(0, "a string, list, map or range");
		});
		R.def(c, "str(x:any) -> string", "Converts any value to text.", a -> Val.str(a.obj(0)));
		R.def(c, "format(template:string, ...values:any) -> string", "Replaces each {} (or {0}, {1}) in the template with a value.", a -> {
			String t = a.str(0);
			List<Object> vals = a.list(1);
			StringBuilder sb = new StringBuilder();
			int next = 0;
			for (int i = 0; i < t.length(); i++) {
				char ch = t.charAt(i);
				if (ch == '{') {
					int j = t.indexOf('}', i);
					if (j > 0) {
						String key = t.substring(i + 1, j);
						int idx = key.isEmpty() ? next++ : Integer.parseInt(key.trim());
						sb.append(idx < vals.size() ? Val.str(vals.get(idx)) : "");
						i = j;
						continue;
					}
				}
				sb.append(ch);
			}
			return sb.toString();
		}).example("format(\"{} / {}\", health, max_health)");
		R.def(c, "split(text:string, sep?:string=\" \") -> list<string>", "Splits a string.", a -> {
			List<Object> out = new ArrayList<>();
			String sep = a.str(1, " ");
			for (String s : a.str(0).split(Pattern.quote(sep), -1)) {
				out.add(s);
			}
			return out;
		});
		R.def(c, "join(items:list, sep?:string=\"\") -> string", "Joins list elements into one string.", a -> {
			StringBuilder sb = new StringBuilder();
			boolean first = true;
			for (Object o : a.iter(0)) {
				if (!first) {
					sb.append(a.str(1, ""));
				}
				first = false;
				sb.append(Val.str(o));
			}
			return sb.toString();
		});
		R.def(c, "replace(text:string, old:string, new:string) -> string", "Replaces every occurrence.", a -> a.str(0).replace(a.str(1), a.str(2)));
		R.def(c, "contains(container:any, item:any) -> boolean", "True if a string contains a substring, or a list/map contains an item (same as `item in container`).", a -> {
			Object cont = a.obj(0);
			if (cont instanceof String s) {
				return s.contains(a.text(1));
			}
			if (cont instanceof Map<?, ?> m) {
				return m.containsKey(a.obj(1));
			}
			for (Object o : Val.toList(cont)) {
				if (Val.equal(o, a.obj(1))) {
					return true;
				}
			}
			return false;
		});
		R.def(c, "starts_with(text:string, prefix:string) -> boolean", "True if text starts with prefix.", a -> a.str(0).startsWith(a.str(1)));
		R.def(c, "ends_with(text:string, suffix:string) -> boolean", "True if text ends with suffix.", a -> a.str(0).endsWith(a.str(1)));
		R.def(c, "substring(text:string, start:number, end?:number) -> string", "Part of a string (end is excluded; negative counts from the end).", a -> {
			String s = a.str(0);
			int st = idx(a.intv(1), s.length());
			int en = a.has(2) ? idx(a.intv(2), s.length()) : s.length();
			return st >= en ? "" : s.substring(st, en);
		});
		R.def(c, "lower(text:string) -> string", "Lower case.", a -> a.str(0).toLowerCase());
		R.def(c, "upper(text:string) -> string", "Upper case.", a -> a.str(0).toUpperCase());
		R.def(c, "trim(text:string) -> string", "Removes spaces at both ends.", a -> a.str(0).strip());
		R.def(c, "regex_match(text:string, pattern:string) -> boolean", "True if the whole text matches the regular expression.", a -> regex(a.str(1)).matcher(a.str(0)).matches());
		R.def(c, "regex_find(text:string, pattern:string) -> list", "All matches of a regular expression (a list of strings).", a -> {
			List<Object> out = new ArrayList<>();
			Matcher m = regex(a.str(1)).matcher(a.str(0));
			while (m.find()) {
				out.add(m.group());
			}
			return out;
		});
		R.def(c, "regex_replace(text:string, pattern:string, replacement:string) -> string", "Replaces regex matches.", a -> regex(a.str(1)).matcher(a.str(0)).replaceAll(a.str(2)));

		R.typeDoc("string", "Text. Methods: upper, lower, replace, contains, split, trim, starts_with, ends_with, substring, index_of.");
		R.prop("string", "length", "number", "Number of characters.", (in, s, g) -> (double) ((String) s).length());
		R.method("string", "upper() -> string", "Upper case.", (in, s, g) -> ((String) s).toUpperCase());
		R.method("string", "lower() -> string", "Lower case.", (in, s, g) -> ((String) s).toLowerCase());
		R.method("string", "trim() -> string", "Removes spaces at both ends.", (in, s, g) -> ((String) s).strip());
		R.method("string", "replace(old:string, new:string) -> string", "Replaces every occurrence.", (in, s, g) -> ((String) s).replace(Val.str(g[0]), Val.str(g[1])));
		R.method("string", "contains(part:string) -> boolean", "True if it contains part.", (in, s, g) -> ((String) s).contains(Val.str(g[0])));
		R.method("string", "starts_with(prefix:string) -> boolean", "True if it starts with prefix.", (in, s, g) -> ((String) s).startsWith(Val.str(g[0])));
		R.method("string", "ends_with(suffix:string) -> boolean", "True if it ends with suffix.", (in, s, g) -> ((String) s).endsWith(Val.str(g[0])));
		R.method("string", "split(sep?:string) -> list", "Splits the string.", (in, s, g) -> {
			List<Object> out = new ArrayList<>();
			for (String p : ((String) s).split(Pattern.quote(g[0] == null ? " " : Val.str(g[0])), -1)) {
				out.add(p);
			}
			return out;
		});
		R.method("string", "index_of(part:string) -> number", "Position of part, or -1.", (in, s, g) -> (double) ((String) s).indexOf(Val.str(g[0])));
		R.method("string", "substring(start:number, end?:number) -> string", "Part of the string.", (in, s, g) -> {
			String str = (String) s;
			int st = idx((int) d(g[0], "start"), str.length());
			int en = g[1] == null ? str.length() : idx((int) d(g[1], "end"), str.length());
			return st >= en ? "" : str.substring(st, en);
		});
	}

	private static int idx(int i, int n) {
		if (i < 0) {
			i += n;
		}
		return Math.max(0, Math.min(n, i));
	}

	private static Pattern regex(String p) {
		try {
			return Pattern.compile(p);
		} catch (Exception e) {
			throw new KcdError("Bad regular expression: " + e.getMessage(), 0);
		}
	}

	// ---------------------------------------------------------------- lists
	@SuppressWarnings("unchecked")
	private static List<Object> mut(Object o) {
		if (o instanceof List<?> l) {
			return (List<Object>) l;
		}
		throw new KcdError("Expected a list but got " + Val.typeName(o), 0);
	}

	private static void lists() {
		String c = "list";
		R.def(c, "range(start:number, end?:number, step?:number=1) -> range", "Numbers from start to end, BOTH included. range(5) is 1..5, range(1, 100) is 100 numbers.", a -> {
			double st = a.has(1) ? a.num(0) : 1;
			double en = a.has(1) ? a.num(1) : a.num(0);
			double step = a.num(2, st <= en ? 1 : -1);
			if (step == 0) {
				throw new KcdError("range(): step cannot be 0", 0);
			}
			return new Val.Range(st, en, step);
		}).example("for n in range(1, 100):  # runs 100 times");
		R.def(c, "append(items:list, value:any) -> list", "Adds a value at the end of the list (changes the list).", a -> {
			a.list(0).add(a.obj(1));
			return a.obj(0);
		});
		R.def(c, "insert(items:list, index:number, value:any) -> list", "Inserts a value at a position.", a -> {
			List<Object> l = a.list(0);
			l.add(idx(a.intv(1), l.size()), a.obj(2));
			return l;
		});
		R.def(c, "remove(items:list, value:any) -> boolean", "Removes the first element equal to value. Returns true if one was removed.", a -> {
			List<Object> l = a.list(0);
			for (int i = 0; i < l.size(); i++) {
				if (Val.equal(l.get(i), a.obj(1))) {
					l.remove(i);
					return true;
				}
			}
			return false;
		});
		R.def(c, "pop(items:list, index?:number=-1) -> any", "Removes and returns an element (the last one by default).", a -> {
			List<Object> l = a.list(0);
			if (l.isEmpty()) {
				throw new KcdError("pop(): the list is empty", 0);
			}
			int i = a.intv(1, -1);
			if (i < 0) {
				i += l.size();
			}
			if (i < 0 || i >= l.size()) {
				throw new KcdError("pop(): index out of range", 0);
			}
			return l.remove(i);
		});
		R.def(c, "clear(items:any)", "Empties a list or map.", a -> {
			if (a.obj(0) instanceof Map<?, ?> m) {
				m.clear();
			} else {
				a.list(0).clear();
			}
			return null;
		});
		R.def(c, "sort(items:list, key?:function, reverse?:boolean=false) -> list", "Sorts the list in place. key is a function that gives the value to sort by.", a -> {
			List<Object> l = a.list(0);
			sortList(a, l);
			return l;
		}).example("sort(allays, lambda e: e.distance)");
		R.def(c, "sorted(items:list, key?:function, reverse?:boolean=false) -> list", "A sorted copy of the list.", a -> {
			List<Object> l = new ArrayList<>(a.iter(0));
			sortList(a, l);
			return l;
		});
		R.def(c, "reverse(items:list) -> list", "Reverses the list in place.", a -> {
			Collections.reverse(a.list(0));
			return a.obj(0);
		});
		R.def(c, "index_of(items:list, value:any) -> number", "Position of the first equal element, or -1.", a -> {
			List<Object> l = a.iter(0);
			for (int i = 0; i < l.size(); i++) {
				if (Val.equal(l.get(i), a.obj(1))) {
					return (double) i;
				}
			}
			return -1.0;
		});
		R.def(c, "enumerate(items:list, start?:number=0) -> list", "List of (index, value) pairs: for i, v in enumerate(list).", a -> {
			List<Object> out = new ArrayList<>();
			double i = a.num(1, 0);
			for (Object o : a.iter(0)) {
				List<Object> pair = new ArrayList<>();
				pair.add(i++);
				pair.add(o);
				out.add(new Val.Tuple(pair));
			}
			return out;
		});
		R.def(c, "zip(a:list, b:list) -> list", "Pairs elements of two lists.", a -> {
			List<Object> x = a.iter(0);
			List<Object> y = a.iter(1);
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
				List<Object> pair = new ArrayList<>();
				pair.add(x.get(i));
				pair.add(y.get(i));
				out.add(new Val.Tuple(pair));
			}
			return out;
		});
		R.def(c, "filter(items:list, test:function) -> list", "Elements for which test(element) is true.", a -> {
			List<Object> out = new ArrayList<>();
			for (Object o : a.iter(0)) {
				if (Val.truthy(a.call(1, o))) {
					out.add(o);
				}
			}
			return out;
		}).example("near = filter(entities, lambda e: e.distance < 10)");
		R.def(c, "map(items:list, fn:function) -> list", "Applies fn to every element.", a -> {
			List<Object> out = new ArrayList<>();
			for (Object o : a.iter(0)) {
				out.add(a.call(1, o));
			}
			return out;
		});
		R.def(c, "reduce(items:list, fn:function, initial?:any) -> any", "Folds the list: fn(accumulator, element).", a -> {
			List<Object> l = a.iter(0);
			int i = 0;
			Object acc;
			if (a.has(2)) {
				acc = a.obj(2);
			} else {
				if (l.isEmpty()) {
					throw new KcdError("reduce(): empty list and no initial value", 0);
				}
				acc = l.get(i++);
			}
			for (; i < l.size(); i++) {
				acc = a.call(1, acc, l.get(i));
			}
			return acc;
		});
		R.def(c, "any(items:list) -> boolean", "True if any element is truthy.", a -> {
			for (Object o : a.iter(0)) {
				if (Val.truthy(o)) {
					return true;
				}
			}
			return false;
		});
		R.def(c, "all(items:list) -> boolean", "True if every element is truthy.", a -> {
			for (Object o : a.iter(0)) {
				if (!Val.truthy(o)) {
					return false;
				}
			}
			return true;
		});
		R.def(c, "first(items:list) -> any", "First element or nil.", a -> a.iter(0).isEmpty() ? null : a.iter(0).get(0));
		R.def(c, "last(items:list) -> any", "Last element or nil.", a -> a.iter(0).isEmpty() ? null : a.iter(0).get(a.iter(0).size() - 1));
		R.def(c, "copy(x:any) -> any", "Deep copy of a list or map.", a -> deepCopy(a.obj(0)));

		R.typeDoc("list", "An ordered, changeable collection: [1, 2, 3].");
		R.prop("list", "length", "number", "Number of elements.", (in, s, g) -> (double) ((List<?>) s).size());
		R.method("list", "append(value:any) -> list", "Adds a value at the end.", (in, s, g) -> {
			mut(s).add(g[0]);
			return s;
		});
		R.method("list", "pop(index?:number) -> any", "Removes and returns the last (or given) element.", (in, s, g) -> {
			List<Object> l = mut(s);
			int i = g[0] == null ? l.size() - 1 : (int) d(g[0], "index");
			if (i < 0) {
				i += l.size();
			}
			if (i < 0 || i >= l.size()) {
				throw new KcdError("pop(): index out of range", 0);
			}
			return l.remove(i);
		});
		R.method("list", "contains(value:any) -> boolean", "True if the list has an equal element.", (in, s, g) -> {
			for (Object o : (List<?>) s) {
				if (Val.equal(o, g[0])) {
					return true;
				}
			}
			return false;
		});
		R.method("list", "index_of(value:any) -> number", "Position or -1.", (in, s, g) -> {
			List<?> l = (List<?>) s;
			for (int i = 0; i < l.size(); i++) {
				if (Val.equal(l.get(i), g[0])) {
					return (double) i;
				}
			}
			return -1.0;
		});
		R.method("list", "clear()", "Removes everything.", (in, s, g) -> {
			mut(s).clear();
			return null;
		});
		R.method("list", "reverse() -> list", "Reverses in place.", (in, s, g) -> {
			Collections.reverse(mut(s));
			return s;
		});
		R.method("list", "join(sep?:string) -> string", "Joins the elements into text.", (in, s, g) -> {
			StringBuilder sb = new StringBuilder();
			boolean first = true;
			for (Object o : (List<?>) s) {
				if (!first) {
					sb.append(g[0] == null ? "" : Val.str(g[0]));
				}
				first = false;
				sb.append(Val.str(o));
			}
			return sb.toString();
		});
		R.typeDoc("tuple", "Several values returned together (return a, b).");
		R.prop("tuple", "length", "number", "Number of values.", (in, s, g) -> (double) ((Val.Tuple) s).items().size());
		R.typeDoc("range", "range(a, b): the numbers a .. b, both included.");
		R.prop("range", "length", "number", "How many numbers.", (in, s, g) -> (double) ((Val.Range) s).size());
	}

	private static void sortList(Args a, List<Object> l) {
		boolean rev = a.bool(2, false);
		Object key = a.obj(1);
		List<Object[]> keyed = new ArrayList<>();
		for (Object o : l) {
			keyed.add(new Object[] {key == null ? o : a.call(1, o), o});
		}
		keyed.sort((x, y) -> compare(x[0], y[0]));
		if (rev) {
			Collections.reverse(keyed);
		}
		for (int i = 0; i < l.size(); i++) {
			l.set(i, keyed.get(i)[1]);
		}
	}

	public static Object deepCopy(Object o) {
		if (o instanceof List<?> l) {
			List<Object> out = new ArrayList<>(l.size());
			for (Object x : l) {
				out.add(deepCopy(x));
			}
			return out;
		}
		if (o instanceof Map<?, ?> m) {
			Map<Object, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				out.put(e.getKey(), deepCopy(e.getValue()));
			}
			return out;
		}
		if (o instanceof Val.Tuple t) {
			return new Val.Tuple((List<Object>) deepCopy(t.items()));
		}
		return o;
	}

	// ---------------------------------------------------------------- maps
	private static void maps() {
		String c = "map";
		R.def(c, "keys(m:map) -> list", "The keys of a map.", a -> new ArrayList<>(a.map(0).keySet()));
		R.def(c, "values(m:map) -> list", "The values of a map.", a -> new ArrayList<>(a.map(0).values()));
		R.def(c, "items(m:map) -> list", "List of (key, value) pairs: for k, v in items(m).", a -> {
			List<Object> out = new ArrayList<>();
			for (Map.Entry<Object, Object> e : a.map(0).entrySet()) {
				List<Object> pair = new ArrayList<>();
				pair.add(e.getKey());
				pair.add(e.getValue());
				out.add(new Val.Tuple(pair));
			}
			return out;
		});
		R.def(c, "map_get(m:map, key:any, default?:any) -> any", "Value for a key, or the default when missing.", a -> a.map(0).containsKey(a.obj(1)) ? a.map(0).get(a.obj(1)) : a.obj(2));
		R.def(c, "map_remove(m:map, key:any) -> any", "Removes a key and returns its value.", a -> a.map(0).remove(a.obj(1)));
		R.typeDoc("map", "Key/value pairs: {\"name\": \"Khoa\"}. Read with m[\"name\"] or m.name.");
		R.prop("map", "length", "number", "Number of keys.", (in, s, g) -> (double) ((Map<?, ?>) s).size());
		R.method("map", "keys() -> list", "The keys.", (in, s, g) -> new ArrayList<>(((Map<?, ?>) s).keySet()));
		R.method("map", "values() -> list", "The values.", (in, s, g) -> new ArrayList<>(((Map<?, ?>) s).values()));
		R.method("map", "items() -> list", "List of (key, value) pairs: for k, v in m.items().", (in, s, g) -> {
			List<Object> out = new ArrayList<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) s).entrySet()) {
				List<Object> pair = new ArrayList<>();
				pair.add(e.getKey());
				pair.add(e.getValue());
				out.add(new Val.Tuple(pair));
			}
			return out;
		});
		R.method("map", "clear()", "Removes every key.", (in, s, g) -> {
			((Map<?, ?>) s).clear();
			return null;
		});
		R.method("map", "copy() -> map", "A shallow copy.", (in, s, g) -> new LinkedHashMap<>((Map<?, ?>) s));
		R.method("map", "has(key:any) -> boolean", "True if the key exists.", (in, s, g) -> ((Map<?, ?>) s).containsKey(g[0]));
		R.method("map", "get(key:any, default?:any) -> any", "Value or default.", (in, s, g) -> ((Map<?, ?>) s).containsKey(g[0]) ? ((Map<?, ?>) s).get(g[0]) : g[1]);
		R.method("map", "remove(key:any) -> any", "Removes a key.", (in, s, g) -> ((Map<?, ?>) s).remove(g[0]));
	}

	// ---------------------------------------------------------------- conversions
	private static void conversions() {
		String c = "type";
		R.def(c, "num(x:any) -> number", "Converts text to a number (error if it is not one).", a -> {
			Object o = a.obj(0);
			if (o instanceof Double) {
				return o;
			}
			if (o instanceof Boolean b) {
				return b ? 1.0 : 0.0;
			}
			try {
				return Double.parseDouble(Val.str(o).trim());
			} catch (NumberFormatException e) {
				throw new KcdError("num(): '" + Val.str(o) + "' is not a number", 0);
			}
		});
		R.def(c, "int(x:any) -> number", "Whole number (rounds towards zero).", a -> {
			Object o = a.obj(0);
			double v = o instanceof Double x ? x : Double.parseDouble(Val.str(o).trim());
			return v < 0 ? Math.ceil(v) : Math.floor(v);
		});
		String cv = "convert";
		R.def(cv, "to_number(x:any, base?:number=10) -> number", "Text to number, or nil when it is not a number (never an error). With a base: to_number(\"ff\", 16) = 255.", a -> {
			Object o = a.obj(0);
			if (o instanceof Double) {
				return o;
			}
			if (o instanceof Boolean b) {
				return b ? 1.0 : 0.0;
			}
			return parseNumber(Val.str(o), (int) a.num(1, 10));
		}).example("n = to_number(input_text)\nif n == nil:\n    print(\"not a number\")");
		R.alias("tonumber", "to_number");
		R.def(cv, "is_number(x:any) -> boolean", "True if x is a number or text that reads as one.", a -> a.obj(0) instanceof Double || parseNumber(Val.str(a.obj(0)), 10) != null);
		R.def(cv, "to_string(x:any, base?:number=10) -> string", "Anything to text. With a base, a whole number is written in it: to_string(255, 16) = \"ff\".", a -> {
			if (a.has(1) && a.obj(0) instanceof Double d) {
				return writeBase(d, (int) a.num(1, 10));
			}
			return Val.str(a.obj(0));
		});
		R.alias("tostring", "to_string");
		R.def(cv, "to_base(n:number, base:number, width?:number=0) -> string", "Writes a whole number in base 2 to 36, padded with zeros to a width.", a -> pad(writeBase(a.num(0), (int) a.num(1)), (int) a.num(2, 0)));
		R.def(cv, "from_base(text:string, base:number) -> number", "Reads text written in base 2 to 36; nil if it is not valid.", a -> parseNumber(a.str(0), (int) a.num(1)));
		R.def(cv, "to_binary(n:number, width?:number=0) -> string", "Whole number in binary: to_binary(10) = \"1010\", to_binary(5, 8) = \"00000101\".", a -> pad(writeBase(a.num(0), 2), (int) a.num(1, 0)));
		R.def(cv, "to_hex(n:number, width?:number=0) -> string", "Whole number in hexadecimal (lowercase): to_hex(255) = \"ff\".", a -> pad(writeBase(a.num(0), 16), (int) a.num(1, 0)));
		R.def(cv, "to_octal(n:number, width?:number=0) -> string", "Whole number in base 8.", a -> pad(writeBase(a.num(0), 8), (int) a.num(1, 0)));
		R.def(cv, "from_binary(text:string) -> number", "Reads binary text (\"1010\" or \"0b1010\"); nil if invalid.", a -> parseNumber(a.str(0), 2));
		R.def(cv, "from_hex(text:string) -> number", "Reads hexadecimal text (\"ff\", \"0xff\" or \"#ff\"); nil if invalid.", a -> parseNumber(a.str(0), 16));
		R.def(cv, "from_octal(text:string) -> number", "Reads base 8 text; nil if invalid.", a -> parseNumber(a.str(0), 8));
		R.def(cv, "ord(char:string) -> number", "Character code of the first character: ord(\"A\") = 65.", a -> {
			String s = a.str(0);
			if (s.isEmpty()) {
				throw new KcdError("ord(): the text is empty", 0);
			}
			return (double) s.codePointAt(0);
		});
		R.def(cv, "chr(code:number) -> string", "Character with that code: chr(65) = \"A\".", a -> new String(Character.toChars((int) a.num(0))));
		R.def(cv, "to_float(x:any) -> number", "Like to_number but raises a readable error when the text is not a number.", a -> {
			Object o = a.obj(0);
			if (o instanceof Double) {
				return o;
			}
			Object v = parseNumber(Val.str(o), 10);
			if (v == null) {
				throw new KcdError("to_float(): '" + Val.str(o) + "' is not a number", 0).withHint("use to_number() to get nil instead of an error");
			}
			return v;
		});
		R.def(c, "bool(x:any) -> boolean", "Truthiness of a value.", a -> Val.truthy(a.obj(0)));
		R.def(c, "type(x:any) -> string", "Name of the type: number, string, boolean, nil, list, map, vec3, entity ...", a -> Val.typeName(a.obj(0)));
		R.def(c, "is_nil(x:any) -> boolean", "True if x is nil.", a -> a.obj(0) == null);
	}

	private static void types() {
		R.typeDoc("number", "Decimal numbers. 10, 3.14, -5.");
		R.typeDoc("boolean", "true or false.");
		R.typeDoc("nil", "No value.");
		R.typeDoc("function", "A function or lambda.");
		R.typeDoc("module", "A file loaded with implement.");
	}

	/** Reads a number in a base (2-36). Accepts 0x / 0b / 0o / # prefixes, underscores, a sign and (base 10) decimals. Null if invalid. */
	static Double parseNumber(String raw, int base) {
		String t = raw.trim().replace("_", "");
		if (t.isEmpty() || base < 2 || base > 36) {
			return null;
		}
		boolean neg = false;
		if (t.startsWith("-") || t.startsWith("+")) {
			neg = t.startsWith("-");
			t = t.substring(1);
		}
		String low = t.toLowerCase();
		if (base == 16 && (low.startsWith("0x") || low.startsWith("#"))) {
			t = t.substring(low.startsWith("#") ? 1 : 2);
		} else if (base == 2 && low.startsWith("0b")) {
			t = t.substring(2);
		} else if (base == 8 && low.startsWith("0o")) {
			t = t.substring(2);
		}
		if (t.isEmpty()) {
			return null;
		}
		try {
			double v;
			if (base == 10) {
				char last = Character.toLowerCase(t.charAt(t.length() - 1));
				if (last == 'd' || last == 'f') {
					return null;
				}
				v = Double.parseDouble(t);
				if (Double.isNaN(v) || Double.isInfinite(v)) {
					return null;
				}
			} else {
				v = Long.parseLong(t, base);
			}
			return neg ? -v : v;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String writeBase(double d, int base) {
		if (base < 2 || base > 36) {
			throw new KcdError("base must be between 2 and 36, got " + base, 0);
		}
		if (d != Math.rint(d)) {
			throw new KcdError("only whole numbers can be written in base " + base + ", got " + Val.fmt(d), 0).withHint("use round() or int() first");
		}
		return Long.toString((long) d, base);
	}

	static String pad(String s, int width) {
		boolean neg = s.startsWith("-");
		String digits = neg ? s.substring(1) : s;
		if (digits.length() < width) {
			digits = "0".repeat(width - digits.length()) + digits;
		}
		return neg ? "-" + digits : digits;
	}
}
