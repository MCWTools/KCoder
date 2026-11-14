package khoa.script.lang;

import java.util.List;
import java.util.Map;

/** Arguments of one builtin call, already bound to parameter slots (missing optional ones are null). */
public final class Args {
	public final Interp in;
	public final Builtin fn;
	public final Object[] v;

	public Args(Interp in, Builtin fn, Object[] v) {
		this.in = in;
		this.fn = fn;
		this.v = v;
	}

	public boolean has(int i) {
		return i < v.length && v[i] != null;
	}

	public Object obj(int i) {
		return i < v.length ? v[i] : null;
	}

	private String pname(int i) {
		if (i < fn.params.size()) {
			return fn.params.get(i).name();
		}
		return fn.params.isEmpty() ? "?" : fn.params.get(fn.params.size() - 1).name();
	}

	public KcdError bad(int i, String expected) {
		return new KcdError(fn.name + "(): argument " + (i + 1) + " ('" + pname(i) + "') must be " + expected + ", got " + Val.typeName(obj(i)), 0);
	}

	public double num(int i) {
		if (obj(i) instanceof Double d) {
			return d;
		}
		throw bad(i, "a number");
	}

	public double num(int i, double def) {
		return has(i) ? num(i) : def;
	}

	public int intv(int i) {
		return (int) Math.floor(num(i));
	}

	public int intv(int i, int def) {
		return has(i) ? intv(i) : def;
	}

	public String str(int i) {
		if (obj(i) instanceof String s) {
			return s;
		}
		throw bad(i, "a string");
	}

	public String str(int i, String def) {
		return has(i) ? str(i) : def;
	}

	/** Any value converted to text. */
	public String text(int i) {
		return Val.str(obj(i));
	}

	public boolean bool(int i, boolean def) {
		return has(i) ? Val.truthy(obj(i)) : def;
	}

	@SuppressWarnings("unchecked")
	public List<Object> list(int i) {
		Object o = obj(i);
		if (o instanceof List<?> l) {
			return (List<Object>) l;
		}
		throw bad(i, "a list");
	}

	/** Lists, tuples, ranges, strings and map keys as a fresh list. */
	public List<Object> iter(int i) {
		try {
			return Val.toList(obj(i));
		} catch (KcdError e) {
			throw bad(i, "a list or range");
		}
	}

	@SuppressWarnings("unchecked")
	public Map<Object, Object> map(int i) {
		if (obj(i) instanceof Map<?, ?> m) {
			return (Map<Object, Object>) m;
		}
		throw bad(i, "a map");
	}

	/** A position: vec3, an entity/block with a position, or [x, y, z]. */
	public Val.Vec3 vec3(int i) {
		Val.Vec3 r = Convert.toVec3(obj(i));
		if (r == null) {
			throw bad(i, "a vec3 (or something with a position)");
		}
		return r;
	}

	public Val.Vec3 vec3(int i, Val.Vec3 def) {
		return has(i) ? vec3(i) : def;
	}

	public Object call(int i, Object... args) {
		return in.callValue(obj(i), List.of(args), null, null);
	}

	public boolean isFunction(int i) {
		return obj(i) instanceof Val.KFunction || obj(i) instanceof Builtin || obj(i) instanceof Val.Bound;
	}
}
