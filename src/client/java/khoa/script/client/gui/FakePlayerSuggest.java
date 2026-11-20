package khoa.script.client.gui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Editor suggestions for the FakePlayer mod API (found by reflection, so nothing is hard coded twice). */
public final class FakePlayerSuggest {
	public static final String MODULE = "module:khoa.fakeplayer";
	public static final String TYPE = "fakeplayer";

	private FakePlayerSuggest() {
	}

	/** One suggestion row: name, signature, returned script type. */
	public record Entry(String name, String signature, String returns, String doc) {
	}

	private static Class<?> handle() {
		try {
			return Class.forName("khoa.fakeplayer.api.BotHandle");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	/** Functions of the module itself (fakeplayer:GetPlayer(...)). */
	public static List<Entry> moduleFunctions() {
		return List.of(
			new Entry("GetPlayer", "GetPlayer(name:string) -> fakeplayer", TYPE, "The bot called name (nil when it is not online)."),
			new Entry("All", "All() -> list<fakeplayer>", "list<" + TYPE + ">", "Every bot that is online."));
	}

	public static String moduleReturn(String name) {
		String n = name.toLowerCase(Locale.ROOT).replace("_", "");
		if (n.equals("getplayer") || n.equals("get")) {
			return TYPE;
		}
		if (n.equals("all") || n.equals("getall") || n.equals("getplayers")) {
			return "list<" + TYPE + ">";
		}
		return "";
	}

	private static String scriptType(Class<?> t) {
		String n = t.getSimpleName();
		if (t == void.class) {
			return "";
		}
		if (t == boolean.class) {
			return "boolean";
		}
		if (t.isPrimitive() || Number.class.isAssignableFrom(t)) {
			return "number";
		}
		if (t == String.class) {
			return "string";
		}
		if (n.equals("Vec3")) {
			return "vec3";
		}
		if (n.equals("BlockPos")) {
			return "block";
		}
		if (n.equals("ItemStack")) {
			return "item";
		}
		if (n.equals("HitResult")) {
			return "map";
		}
		if (n.equals("PathPlan")) {
			return "map";
		}
		if (n.equals("List")) {
			return "list";
		}
		if (n.equals("BotHandle")) {
			return TYPE;
		}
		if (n.equals("ServerPlayer") || n.equals("Entity")) {
			return "entity";
		}
		return "";
	}

	private static String paramName(Class<?> t) {
		String s = scriptType(t);
		return s.isEmpty() ? t.getSimpleName().toLowerCase(Locale.ROOT) : s;
	}

	/** Every BotHandle method, one row per name (the longest overload shown), plus GetXxx aliases of the readers. */
	public static List<Entry> botMembers() {
		Class<?> h = handle();
		List<Entry> out = new ArrayList<>();
		if (h == null) {
			return out;
		}
		Map<String, Method> best = new LinkedHashMap<>();
		for (Method m : h.getMethods()) {
			if (m.getDeclaringClass() == Object.class) {
				continue;
			}
			Method old = best.get(m.getName());
			if (old == null || m.getParameterCount() > old.getParameterCount()) {
				best.put(m.getName(), m);
			}
		}
		for (Method m : best.values()) {
			StringBuilder sig = new StringBuilder(m.getName()).append('(');
			Class<?>[] ps = m.getParameterTypes();
			for (int i = 0; i < ps.length; i++) {
				sig.append(i > 0 ? ", " : "").append(paramName(ps[i]));
			}
			String ret = scriptType(m.getReturnType());
			sig.append(')');
			if (!ret.isEmpty()) {
				sig.append(" -> ").append(ret);
			}
			boolean overloaded = false;
			for (Method o : h.getMethods()) {
				if (o != m && o.getName().equals(m.getName()) && o.getDeclaringClass() != Object.class) {
					overloaded = true;
				}
			}
			String doc = "FakePlayer bot method" + (overloaded ? " (optional arguments allowed, e.g. leftMouse() = one click)" : "");
			out.add(new Entry(m.getName(), sig.toString(), ret, doc));
			String n = m.getName();
			if (ps.length == 0 && !ret.isEmpty() && !n.startsWith("get") && !n.startsWith("is")) {
				String alias = "Get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
				out.add(new Entry(alias, alias + "() -> " + ret, ret, "Same as " + n + "()"));
			}
		}
		return out;
	}

	/** The script type a bot method returns (for chains like player:GetAimingEntity():). */
	public static String botReturn(String name) {
		String want = name.toLowerCase(Locale.ROOT).replace("_", "");
		for (Entry e : botMembers()) {
			if (e.name().toLowerCase(Locale.ROOT).replace("_", "").equals(want)) {
				return e.returns();
			}
		}
		String stripped = want.startsWith("get") || want.startsWith("is") ? want.replaceFirst("^(get|is)", "") : "";
		if (!stripped.isEmpty()) {
			for (Entry e : botMembers()) {
				if (e.name().toLowerCase(Locale.ROOT).replace("_", "").equals(stripped)) {
					return e.returns();
				}
			}
		}
		return "";
	}
}
