package khoa.script.lang;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Runtime value helpers. Values are plain Java objects: Double, String, Boolean, null (nil), ArrayList, LinkedHashMap, plus the records below. */
public final class Val {
	private Val() {
	}

	public record Vec2(double x, double y) {
	}

	public record Vec3(double x, double y, double z) {
		public Vec3 add(Vec3 o) {
			return new Vec3(x + o.x, y + o.y, z + o.z);
		}

		public Vec3 sub(Vec3 o) {
			return new Vec3(x - o.x, y - o.y, z - o.z);
		}

		public Vec3 mul(double k) {
			return new Vec3(x * k, y * k, z * k);
		}

		public double dot(Vec3 o) {
			return x * o.x + y * o.y + z * o.z;
		}

		public double length() {
			return Math.sqrt(x * x + y * y + z * z);
		}
	}

	/** Yaw and pitch in degrees (Minecraft convention: yaw 0 = south, pitch -90 = up). */
	public record Rotation(double yaw, double pitch) {
	}

	public record Tuple(List<Object> items) {
	}

	/** Inclusive range: range(1, 100) yields 1 .. 100. */
	public record Range(double start, double end, double step) implements Iterable<Object> {
		public int size() {
			if (step > 0 ? start > end : start < end) {
				return 0;
			}
			return (int) Math.floor((end - start) / step + 1e-9) + 1;
		}

		@Override
		public Iterator<Object> iterator() {
			return new Iterator<>() {
				int i;
				final int n = size();

				@Override
				public boolean hasNext() {
					return i < n;
				}

				@Override
				public Object next() {
					if (i >= n) {
						throw new NoSuchElementException();
					}
					return start + (i++) * step;
				}
			};
		}
	}

	/** Anything that is a game object (entity, block, item ...) and supports members through the Members registry. */
	public interface KObject {
		String typeName();
	}

	/** An object with members that are not in the registry, found by name (a GUI folder and the elements inside it). */
	public interface KDynamic extends KObject {
		/** The member called name, or null if there is none. */
		Object dynamicMember(String name);
	}

	/** A Java object from another mod's API: any public method is callable by name (case and underscores ignored): obj:GetAimingEntity(). */
	public interface KInvokable extends KObject {
		Object invoke(String name, List<Object> args);
	}

	/** An object whose members can be assigned:  box.text = "hi". */
	public interface KSettable extends KObject {
		void setMember(String name, Object value);
	}

	/** A user defined function or lambda. */
	public static final class KFunction {
		public final String name;
		public final List<Node.Param> params;
		public final List<Node> body;
		public final Node expr;
		public final Scope closure;
		public final String file;
		public final int line;

		public KFunction(String name, List<Node.Param> params, List<Node> body, Node expr, Scope closure, String file, int line) {
			this.name = name;
			this.params = params;
			this.body = body;
			this.expr = expr;
			this.closure = closure;
			this.file = file;
			this.line = line;
		}
	}

	/** A loaded module: its own global scope. Names starting with _ are private. */
	public static final class KModule {
		public final String name;
		public final String path;
		public final Scope scope;
		public boolean loaded;

		public KModule(String name, String path, Scope scope) {
			this.name = name;
			this.path = path;
			this.scope = scope;
		}
	}

	/** obj.method or obj:method not yet called. */
	public record Bound(Object self, String name) {
	}

	public static boolean isNum(Object o) {
		return o instanceof Double;
	}

	public static String typeName(Object o) {
		if (o == null) {
			return "nil";
		}
		if (o instanceof Double) {
			return "number";
		}
		if (o instanceof String) {
			return "string";
		}
		if (o instanceof Boolean) {
			return "boolean";
		}
		if (o instanceof Vec3) {
			return "vec3";
		}
		if (o instanceof Vec2) {
			return "vec2";
		}
		if (o instanceof Rotation) {
			return "rotation";
		}
		if (o instanceof List) {
			return "list";
		}
		if (o instanceof Map) {
			return "map";
		}
		if (o instanceof Tuple) {
			return "tuple";
		}
		if (o instanceof Range) {
			return "range";
		}
		if (o instanceof KFunction || o instanceof Builtin || o instanceof Bound) {
			return "function";
		}
		if (o instanceof KModule) {
			return "module";
		}
		if (o instanceof KObject k) {
			return k.typeName();
		}
		return o.getClass().getSimpleName().toLowerCase();
	}

	public static boolean truthy(Object o) {
		if (o == null) {
			return false;
		}
		if (o instanceof Boolean b) {
			return b;
		}
		if (o instanceof Double d) {
			return d != 0;
		}
		if (o instanceof String s) {
			return !s.isEmpty();
		}
		if (o instanceof List<?> l) {
			return !l.isEmpty();
		}
		if (o instanceof Map<?, ?> m) {
			return !m.isEmpty();
		}
		if (o instanceof Tuple t) {
			return !t.items().isEmpty();
		}
		return true;
	}

	public static String fmt(double d) {
		if (Double.isNaN(d)) {
			return "nan";
		}
		if (Double.isInfinite(d)) {
			return d > 0 ? "inf" : "-inf";
		}
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		String s = String.format(java.util.Locale.ROOT, "%.6f", d);
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 0 && s.charAt(end - 1) == '.') {
			end--;
		}
		return s.substring(0, end);
	}

	/** Text used by print() and string interpolation. */
	public static String str(Object o) {
		if (o instanceof String s) {
			return s;
		}
		return repr(o, 0);
	}

	/** Text used inside collections: strings are quoted. */
	public static String repr(Object o, int depth) {
		if (depth > 6) {
			return "...";
		}
		if (o == null) {
			return "nil";
		}
		if (o instanceof String s) {
			return depth == 0 ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
		}
		if (o instanceof Double d) {
			return fmt(d);
		}
		if (o instanceof Boolean b) {
			return b.toString();
		}
		if (o instanceof Vec3 v) {
			return "vec3(" + fmt(v.x()) + ", " + fmt(v.y()) + ", " + fmt(v.z()) + ")";
		}
		if (o instanceof Vec2 v) {
			return "vec2(" + fmt(v.x()) + ", " + fmt(v.y()) + ")";
		}
		if (o instanceof Rotation r) {
			return "rotation(" + fmt(r.yaw()) + ", " + fmt(r.pitch()) + ")";
		}
		if (o instanceof List<?> l) {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < l.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(repr(l.get(i), depth + 1));
			}
			return sb.append(']').toString();
		}
		if (o instanceof Tuple t) {
			StringBuilder sb = new StringBuilder("(");
			for (int i = 0; i < t.items().size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(repr(t.items().get(i), depth + 1));
			}
			return sb.append(')').toString();
		}
		if (o instanceof Map<?, ?> m) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				sb.append(repr(e.getKey(), depth + 1)).append(": ").append(repr(e.getValue(), depth + 1));
			}
			return sb.append('}').toString();
		}
		if (o instanceof Range r) {
			return "range(" + fmt(r.start()) + ", " + fmt(r.end()) + (r.step() != 1 ? ", " + fmt(r.step()) : "") + ")";
		}
		if (o instanceof KFunction f) {
			return "<function " + f.name + ">";
		}
		if (o instanceof Builtin b) {
			return "<builtin " + b.name + ">";
		}
		if (o instanceof KModule m) {
			return "<module " + m.name + ">";
		}
		if (o instanceof Bound b) {
			return "<method " + b.name() + ">";
		}
		return o.toString();
	}

	public static boolean equal(Object a, Object b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		if (a instanceof Double x && b instanceof Double y) {
			return x.doubleValue() == y.doubleValue();
		}
		if (a instanceof List<?> x && b instanceof List<?> y) {
			if (x.size() != y.size()) {
				return false;
			}
			for (int i = 0; i < x.size(); i++) {
				if (!equal(x.get(i), y.get(i))) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof Tuple x && b instanceof Tuple y) {
			return equal(x.items(), y.items());
		}
		if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
			if (x.size() != y.size()) {
				return false;
			}
			for (Map.Entry<?, ?> e : x.entrySet()) {
				if (!y.containsKey(e.getKey()) || !equal(e.getValue(), y.get(e.getKey()))) {
					return false;
				}
			}
			return true;
		}
		return a.equals(b);
	}

	/** Materializes any iterable value into a list (list, tuple, range, string, map keys). */
	public static List<Object> toList(Object o) {
		if (o instanceof List<?> l) {
			return new ArrayList<>(l);
		}
		if (o instanceof Tuple t) {
			return new ArrayList<>(t.items());
		}
		if (o instanceof Range r) {
			List<Object> out = new ArrayList<>(r.size());
			for (Object x : r) {
				out.add(x);
			}
			return out;
		}
		if (o instanceof String s) {
			List<Object> out = new ArrayList<>();
			s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
			return out;
		}
		if (o instanceof Map<?, ?> m) {
			return new ArrayList<>(m.keySet());
		}
		throw new KcdError("Cannot iterate over " + typeName(o), 0);
	}
}
