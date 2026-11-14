package khoa.script.lang;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A variable scope. Functions and event handlers get their own scope; blocks (if/for) do not, like Python. */
public final class Scope {
	public final Scope parent;
	public final Map<String, Object> vars = new HashMap<>();
	private Set<String> consts;
	private Set<String> globalNames;
	/** Name shown in stack traces and the variable inspector. */
	public final String label;

	public Scope(Scope parent, String label) {
		this.parent = parent;
		this.label = label;
	}

	/** Functions written with "function name(...)": kept apart from variables, so  count = count()  does not lose the function count. */
	private Map<String, Object> fns;

	public void defineFunction(String name, Object fn) {
		if (fns == null) {
			fns = new HashMap<>();
		}
		fns.put(name, fn);
		vars.put(name, fn); // still a value too: g = count passes it around
	}

	/**
	 * What  name(...)  calls: the nearest user function of that name, or a variable holding something callable (a lambda).
	 * A variable that holds a number, text ... is skipped, since a call can never mean it. Null if there is none (then a builtin is used).
	 */
	public Object findCallable(String name) {
		for (Scope s = this; s != null; s = s.parent) {
			if (s.fns != null && s.fns.containsKey(name)) {
				return s.fns.get(name);
			}
			Object v = s.vars.get(name);
			if (v instanceof Val.KFunction || v instanceof Builtin || v instanceof Val.Bound) {
				return v;
			}
		}
		return null;
	}

	public Scope root() {
		Scope s = this;
		while (s.parent != null) {
			s = s.parent;
		}
		return s;
	}

	public Scope find(String name) {
		for (Scope s = this; s != null; s = s.parent) {
			if (s.vars.containsKey(name)) {
				return s;
			}
		}
		return null;
	}

	public boolean has(String name) {
		return find(name) != null;
	}

	public Object get(String name) {
		Scope s = find(name);
		return s == null ? null : s.vars.get(name);
	}

	public void declareGlobal(String name) {
		if (globalNames == null) {
			globalNames = new HashSet<>();
		}
		globalNames.add(name);
	}

	public boolean isConst(String name) {
		Scope s = find(name);
		return s != null && s.consts != null && s.consts.contains(name);
	}

	/** Plain assignment: updates the variable where it lives, or creates a global. Use define() (the "local" keyword) for a variable of this scope only. */
	public void set(String name, Object value) {
		if (globalNames != null && globalNames.contains(name)) {
			root().vars.put(name, value);
			return;
		}
		// no "local" in front: change the variable wherever it lives, or create a global one
		Scope s = find(name);
		(s != null ? s : root()).vars.put(name, value);
	}

	/** Augmented assignment (x += 1): updates the variable where it lives, even in an enclosing scope. */
	public void setExisting(String name, Object value) {
		if (globalNames != null && globalNames.contains(name)) {
			root().vars.put(name, value);
			return;
		}
		Scope s = find(name);
		(s == null ? this : s).vars.put(name, value);
	}

	public void setGlobal(String name, Object value) {
		root().vars.put(name, value);
	}

	public void setConst(String name, Object value) {
		vars.put(name, value);
		if (consts == null) {
			consts = new HashSet<>();
		}
		consts.add(name);
	}

	public void define(String name, Object value) {
		vars.put(name, value);
	}
}
