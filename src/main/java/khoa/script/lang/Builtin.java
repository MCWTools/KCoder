package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;

/** A builtin function. Everything the editor shows (signature, docs, completion) comes from this metadata. */
public final class Builtin {
	public record ParamDef(String name, String type, boolean optional, String def, boolean variadic) {
	}

	@FunctionalInterface
	public interface Impl {
		Object run(Args a) throws Exception;
	}

	public final String name;
	public final String category;
	public final String signature;
	public final List<ParamDef> params;
	public final String returns;
	public final String description;
	public final Impl impl;
	public String example = "";
	/** Runs on the Minecraft client thread (all game access). False for pure helpers. */
	public boolean client;
	/** May suspend the script (wait, wait_until ...). */
	public boolean suspends;
	/** Permission needed: "", "action" (clicks, keys), "look" (camera), "file", "chat". */
	public String permission = "";
	/** Free-form notes on errors. */
	public String errors = "Throws a catchable error on a wrong argument type.";

	public Builtin(String name, String category, String signature, List<ParamDef> params, String returns, String description, Impl impl) {
		this.name = name;
		this.category = category;
		this.signature = signature;
		this.params = params;
		this.returns = returns;
		this.description = description;
		this.impl = impl;
	}

	public Builtin example(String e) {
		this.example = e;
		return this;
	}

	public Builtin client() {
		this.client = true;
		return this;
	}

	public Builtin suspends() {
		this.suspends = true;
		return this;
	}

	public Builtin perm(String p) {
		this.permission = p;
		return this;
	}

	public Builtin errors(String e) {
		this.errors = e;
		return this;
	}

	public int minArgs() {
		int n = 0;
		for (ParamDef p : params) {
			if (!p.optional() && !p.variadic()) {
				n++;
			}
		}
		return n;
	}

	public boolean variadic() {
		return !params.isEmpty() && params.get(params.size() - 1).variadic();
	}

	/** Parses "name(a:vec3, b?:number=3, ...rest) -> number". */
	public static Builtin parse(String category, String sig, String desc, Impl impl) {
		int open = sig.indexOf('(');
		int close = sig.lastIndexOf(')');
		String name = sig.substring(0, open).trim();
		String inner = sig.substring(open + 1, close).trim();
		String returns = "nil";
		int arrow = sig.indexOf("->", close);
		if (arrow >= 0) {
			returns = sig.substring(arrow + 2).trim();
		}
		List<ParamDef> params = new ArrayList<>();
		if (!inner.isEmpty()) {
			for (String part : inner.split(",(?![^\\[]*\\])")) {
				part = part.trim();
				boolean variadic = part.startsWith("...");
				if (variadic) {
					part = part.substring(3);
				}
				String def = null;
				int eq = part.indexOf('=');
				if (eq >= 0) {
					def = part.substring(eq + 1).trim();
					part = part.substring(0, eq).trim();
				}
				String type = "any";
				int colon = part.indexOf(':');
				if (colon >= 0) {
					type = part.substring(colon + 1).trim();
					part = part.substring(0, colon).trim();
				}
				boolean optional = part.endsWith("?") || def != null;
				if (part.endsWith("?")) {
					part = part.substring(0, part.length() - 1);
				}
				params.add(new ParamDef(part, type, optional, def, variadic));
			}
		}
		return new Builtin(name, category, sig, params, returns, desc, impl);
	}
}
