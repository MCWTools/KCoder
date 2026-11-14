package khoa.script.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The extension point of the language: builtin functions, members of value types and event types are all registered here.
 * The lexer and parser never know any API name, so adding get_redstone_power() later is one {@code def(...)} call.
 */
public final class Registry {
	@FunctionalInterface
	public interface MemberImpl {
		Object run(Interp in, Object self, Object[] args) throws Exception;
	}

	public static final class Member {
		public final String type;
		public final String name;
		public final boolean method;
		public final List<Builtin.ParamDef> params;
		public final String returns;
		public final String doc;
		public final MemberImpl impl;

		Member(String type, String name, boolean method, List<Builtin.ParamDef> params, String returns, String doc, MemberImpl impl) {
			this.type = type;
			this.name = name;
			this.method = method;
			this.params = params;
			this.returns = returns;
			this.doc = doc;
			this.impl = impl;
		}

		public String signature() {
			if (!method) {
				return name + " : " + returns;
			}
			StringBuilder sb = new StringBuilder(name).append('(');
			for (int i = 0; i < params.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				Builtin.ParamDef p = params.get(i);
				sb.append(p.variadic() ? "..." : "").append(p.name()).append(p.optional() ? "?" : "").append(": ").append(p.type());
			}
			return sb.append(") -> ").append(returns).toString();
		}
	}

	/** An event that scripts can handle with {@code on_name(filter...):}. */
	public static final class EventType {
		public final String name;
		public final String description;
		public final List<String> filters;
		public final Map<String, String> vars;
		public final BiPredicate<List<Object>, Map<String, Object>> matcher;
		/** True for events the runtime fires by itself on a timer (tick/frame/interval). */
		public boolean skipIfBusy = true;

		public EventType(String name, String description, List<String> filters, Map<String, String> vars, BiPredicate<List<Object>, Map<String, Object>> matcher) {
			this.name = name;
			this.description = description;
			this.filters = filters;
			this.vars = vars;
			this.matcher = matcher;
		}
	}

	private static final Registry INSTANCE = new Registry();

	public static Registry get() {
		return INSTANCE;
	}

	public final Map<String, Builtin> builtins = new LinkedHashMap<>();
	/** Valid choices of enum parameters: type name such as "enum_entity_sort" to its values. */
	public final Map<String, List<String>> enums = new LinkedHashMap<>();
	/** Global values such as the {@code color} namespace: found by name after builtins. */
	public final Map<String, Object> constants = new LinkedHashMap<>();
	/** Looked up by name when nothing else matches (the GUI editor's screen folders). Return null for "not mine". */
	public final List<java.util.function.Function<String, Object>> resolvers = new java.util.concurrent.CopyOnWriteArrayList<>();
	/** True for a name that starts a slash path such as ScreenFolder/Button (set by the GUI editor); only then does a/b mean "child b of a". */
	public volatile java.util.function.Predicate<String> pathRoot = n -> false;
	public final Map<String, Map<String, Member>> members = new HashMap<>();
	public final Map<String, String> parents = new HashMap<>();
	public final Map<String, EventType> events = new LinkedHashMap<>();
	/** Value types whose members must run on the client thread. */
	public final Set<String> clientTypes = new HashSet<>();
	public final Map<String, String> typeDocs = new LinkedHashMap<>();

	private Registry() {
	}

	// ---------- builtins ----------
	public Builtin def(String category, String signature, String description, Builtin.Impl impl) {
		Builtin b = Builtin.parse(category, signature, description, impl);
		builtins.put(b.name, b);
		return b;
	}

	public Builtin builtin(String name) {
		return builtins.get(name);
	}

	/** Registers a second name for an existing builtin. */
	public void alias(String alias, String target) {
		Builtin b = builtins.get(target);
		if (b == null) {
			throw new IllegalStateException("alias target missing: " + target);
		}
		Builtin copy = new Builtin(alias, b.category, b.signature.replaceFirst("^[^(]*", alias), b.params, b.returns, b.description + " (alias of " + target + ")", b.impl);
		copy.client = b.client;
		copy.suspends = b.suspends;
		copy.permission = b.permission;
		copy.example = b.example;
		builtins.put(alias, copy);
	}

	// ---------- members ----------
	public void typeParent(String type, String parent) {
		parents.put(type, parent);
	}

	public void typeDoc(String type, String doc) {
		typeDocs.put(type, doc);
	}

	public void prop(String type, String name, String returns, String doc, MemberImpl impl) {
		members.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(name, new Member(type, name, false, List.of(), returns, doc, impl));
	}

	public void method(String type, String signature, String doc, MemberImpl impl) {
		Builtin b = Builtin.parse("member", signature, doc, null);
		members.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(b.name, new Member(type, b.name, true, b.params, b.returns, doc, impl));
	}

	public Member member(String type, String name) {
		for (String t = type; t != null; t = parents.get(t)) {
			Map<String, Member> m = members.get(t);
			if (m != null && m.containsKey(name)) {
				return m.get(name);
			}
		}
		return null;
	}

	/** All members of a type including inherited ones (child entries win). */
	public List<Member> allMembers(String type) {
		Map<String, Member> out = new LinkedHashMap<>();
		List<String> chain = new ArrayList<>();
		for (String t = type; t != null; t = parents.get(t)) {
			chain.add(t);
		}
		for (int i = chain.size() - 1; i >= 0; i--) {
			Map<String, Member> m = members.get(chain.get(i));
			if (m != null) {
				out.putAll(m);
			}
		}
		return new ArrayList<>(out.values());
	}

	public boolean isClientType(String type) {
		for (String t = type; t != null; t = parents.get(t)) {
			if (clientTypes.contains(t)) {
				return true;
			}
		}
		return false;
	}

	/** Finds an event by a loose spelling: On_Click, OnClick, on_click. Null if there is none. */
	public String eventByLooseName(String spelled) {
		String want = spelled.replace("_", "").toLowerCase();
		for (String name : events.keySet()) {
			if (name.replace("_", "").equals(want)) {
				return name;
			}
		}
		return null;
	}

	// ---------- events ----------
	public EventType event(String name, String description, List<String> filters, Map<String, String> vars, BiPredicate<List<Object>, Map<String, Object>> matcher) {
		EventType e = new EventType(name, description, filters, vars, matcher);
		events.put(name, e);
		return e;
	}
}
