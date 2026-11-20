package khoa.script.client.gui;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import khoa.script.lang.Builtin;
import khoa.script.lang.Registry;

/**
 * Types for the editor. A type is a plain name (number, string, entity, vec3 ...) or a list with an element type: list<entity>.
 * Types come from a declaration (name: entity = ...), typed parameters, or are worked out from the value on the right of "=".
 * An empty string means "unknown": suggestions then fall back to everything that could fit.
 */
public final class SuggestTypes {
	private static final Pattern ASSIGN = Pattern.compile("^\\s*([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*=(?!=)\\s*(.*)$");
	private static final Pattern TYPED_ASSIGN = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*:\\s*([A-Za-z_]\\w*(?:\\s*<[^>]*>)?)\\s*=(?!=)\\s*(.*)$");
	private static final Pattern TYPED_INDEX = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*\\[[^\\]]*\\]\\s*:\\s*([A-Za-z_]\\w*(?:\\s*<[^>]*>)?)\\s*=(?!=)\\s*(.*)$");
	private static final Pattern INDEX_ASSIGN = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*\\[[^\\]]*\\]\\s*=(?!=)\\s*(.*)$");
	private static final Pattern APPEND = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*[.:]\\s*(?:append|add|push)\\s*\\((.*)\\)\\s*$");
	private static final Pattern FOR = Pattern.compile("^\\s*for\\s+([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s+in\\b");
	private static final Pattern FUNC = Pattern.compile("^\\s*(?:function|def|fn)\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)");
	private static final Pattern IMPLEMENT = Pattern.compile("^implement\\s*(?:\\(\\s*[\"']([^\"']+)[\"']\\s*\\)|\\s+(\\S+))");

	private SuggestTypes() {
	}

	static boolean word(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** "list<entity>" -> "list". */
	public static String baseOf(String type) {
		int i = type.indexOf('<');
		return (i < 0 ? type : type.substring(0, i)).trim();
	}

	/** "list<entity>" -> "entity", "map<entity,line_3d>" -> "line_3d" (the value), anything else -> "". */
	public static String elemOf(String type) {
		int i = type.indexOf('<');
		int j = type.lastIndexOf('>');
		if (i < 0 || j <= i) {
			return "";
		}
		String inner = type.substring(i + 1, j).trim();
		if (baseOf(type).equals("map")) {
			int c = topLevelComma(inner);
			String v = c < 0 ? inner : inner.substring(c + 1).trim();
			return v.equals("any") ? "" : v;
		}
		return inner;
	}

	/** "map<entity,line_3d>" -> "entity" (what a for loop over the map gives), anything else -> "". */
	public static String keyOf(String type) {
		int i = type.indexOf('<');
		int j = type.lastIndexOf('>');
		if (i < 0 || j <= i || !baseOf(type).equals("map")) {
			return "";
		}
		String inner = type.substring(i + 1, j).trim();
		int c = topLevelComma(inner);
		String k = c < 0 ? "" : inner.substring(0, c).trim();
		return k.equals("any") ? "" : k;
	}

	/** The container type with its element type filled in: list + entity -> list<entity>, map + line_3d -> map<any,line_3d>. */
	private static String withElem(String container, String elem) {
		String b = baseOf(container);
		if (elem.isEmpty()) {
			return container;
		}
		if (b.equals("list")) {
			return "list<" + elem + ">";
		}
		if (b.equals("map")) {
			String k = keyOf(container);
			return "map<" + (k.isEmpty() ? "any" : k) + "," + elem + ">";
		}
		return container;
	}

	private static String clean(String type) {
		if (type == null || type.equals("any") || type.equals("nil") || type.equals("function")) {
			return "";
		}
		return normalize(type.replace(" ", ""));
	}

	/** Lets a declaration say line_3d for the type called line3d (and so on): underscores and case do not matter when the plain name is not a type. */
	private static String normalize(String type) {
		int lt = type.indexOf('<');
		if (lt >= 0) {
			int gt = type.lastIndexOf('>');
			StringBuilder out = new StringBuilder(normalize(type.substring(0, lt))).append('<');
			String inner = gt > lt ? type.substring(lt + 1, gt) : type.substring(lt + 1);
			String[] parts = inner.split(",");
			for (int i = 0; i < parts.length; i++) {
				out.append(i > 0 ? "," : "").append(normalize(parts[i]));
			}
			return out.append('>').toString();
		}
		Map<String, String> known = Registry.get().typeDocs;
		if (known.containsKey(type) || type.equals("list") || type.equals("map")) {
			return type;
		}
		String flat = type.replace("_", "").toLowerCase();
		for (String k : known.keySet()) {
			if (k.replace("_", "").toLowerCase().equals(flat)) {
				return k;
			}
		}
		return type;
	}

	/** Index of the bracket that closes the one at {@code open}, or -1. Strings are skipped. */
	static int closing(String s, int open) {
		int depth = 0;
		char quote = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	static int topLevelComma(String s) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (c == ',' && depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** Finds the first top-level operator. kind: 0 = comparison, 1 = arithmetic. Returns its index or -1. */
	private static int topLevelOp(String s, int kind) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (depth == 0 && i > 0) {
				String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
				if (kind == 0) {
					if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
						return i;
					}
					if ((c == '<' || c == '>') && !two.endsWith("=")) {
						return i;
					}
				} else if ("+-*/%".indexOf(c) >= 0) {
					String left = s.substring(0, i).trim();
					if (!left.isEmpty() && "+-*/%(,=<>".indexOf(left.charAt(left.length() - 1)) < 0) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	private static boolean hasWordOperator(String s) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
			} else if (depth == 0 && (i == 0 || !word(s.charAt(i - 1)))) {
				for (String w : new String[] {"and ", "or ", "not ", "in ", "is "}) {
					if (s.startsWith(w, i)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean callAfterColon(String s, int colon) {
		int j = colon + 1;
		while (j < s.length() && word(s.charAt(j))) {
			j++;
		}
		return j < s.length() && s.charAt(j) == '(';
	}

	/** Best guess at the type of an expression. Empty = unknown. */
	public static String typeOf(String expr, Map<String, String> vars) {
		String s = expr.trim();
		if (s.isEmpty() || s.startsWith("#")) {
			return "";
		}
		if (s.matches("[A-Za-z_][A-Za-z0-9_]*(\\s*[.]\\s*[A-Za-z_][A-Za-z0-9_]*)*") && !vars.containsKey(s.split("[.]")[0].trim())) {
			// ScreenFolder.Panel.Title: a thing made in the GUI editor
			khoa.script.client.ui.UiElement ge = khoa.script.client.guiedit.GuiStore.byPath(s);
			if (ge != null) {
				return ge.kind;
			}
		}
		if (hasWordOperator(s) || topLevelOp(s, 0) >= 0) {
			return "boolean";
		}
		int op = topLevelOp(s, 1);
		if (op >= 0) {
			String left = typeOf(s.substring(0, op), vars);
			return left;
		}
		if (s.startsWith("-")) {
			return typeOf(s.substring(1), vars);
		}
		char c0 = s.charAt(0);
		if (c0 == '"' || c0 == '\'') {
			return "string";
		}
		if (Character.isDigit(c0) || (c0 == '.' && s.length() > 1 && Character.isDigit(s.charAt(1)))) {
			return "number";
		}
		if (c0 == '{') {
			return "map";
		}
		String type;
		int i;
		if (c0 == '[') {
			int e = closing(s, 0);
			if (e < 0) {
				return "list";
			}
			String inner = s.substring(1, e).trim();
			if (inner.isEmpty()) {
				type = "list";
			} else {
				int comma = topLevelComma(inner);
				String first = typeOf(comma < 0 ? inner : inner.substring(0, comma), vars);
				type = first.isEmpty() ? "list" : "list<" + first + ">";
			}
			i = e + 1;
		} else if (c0 == '(') {
			int e = closing(s, 0);
			if (e < 0) {
				return "";
			}
			type = typeOf(s.substring(1, e), vars);
			i = e + 1;
		} else if (Character.isLetter(c0) || c0 == '_') {
			int j = 1;
			while (j < s.length() && word(s.charAt(j))) {
				j++;
			}
			String name = s.substring(0, j);
			if (name.equals("true") || name.equals("false")) {
				return "boolean";
			}
			if (name.equals("nil")) {
				return "";
			}
			i = j;
			if (i < s.length() && s.charAt(i) == '(') {
				Builtin b = Registry.get().builtin(name);
				type = b != null ? clean(b.returns) : "";
				int e = closing(s, i);
				if (e < 0) {
					return "";
				}
				i = e + 1;
			} else if (i + 1 < s.length() && s.charAt(i) == ':' && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_') && !callAfterColon(s, i)) {
				return "string";
			} else {
				type = vars.getOrDefault(name, Registry.get().constants.containsKey(name) ? khoa.script.lang.Val.typeName(Registry.get().constants.get(name)) : "");
			}
		} else {
			return "";
		}
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '.' || c == ':') {
				int j = i + 1;
				while (j < s.length() && word(s.charAt(j))) {
					j++;
				}
				String name = s.substring(i + 1, j);
				if (type.equals(FakePlayerSuggest.MODULE)) {
					type = FakePlayerSuggest.moduleReturn(name);
				} else if (baseOf(type).equals(FakePlayerSuggest.TYPE)) {
					type = FakePlayerSuggest.botReturn(name);
				} else {
					Registry.Member m = type.isEmpty() ? null : Registry.get().member(baseOf(type), name);
					type = m == null ? "" : clean(m.returns);
				}
				i = j;
				if (i < s.length() && s.charAt(i) == '(') {
					int e = closing(s, i);
					if (e < 0) {
						return "";
					}
					i = e + 1;
				}
			} else if (c == '[') {
				int e = closing(s, i);
				if (e < 0) {
					return "";
				}
				boolean slice = s.substring(i + 1, e).contains(":");
				if (!slice) {
					type = baseOf(type).equals("string") ? "string" : elemOf(type);
				}
				i = e + 1;
			} else if (c == '(') {
				int e = closing(s, i);
				if (e < 0) {
					return "";
				}
				type = "";
				i = e + 1;
			} else {
				return "";
			}
		}
		return type;
	}

	/** Names defined in the text with their type: declared, typed parameters, or worked out from the value. */
	public static Map<String, String> variables(List<String> lines, int upToRow) {
		Map<String, String> vars = new LinkedHashMap<>();
		Set<String> declared = new LinkedHashSet<>();
		for (int r = 0; r < lines.size(); r++) {
			String ln = lines.get(r);
			Matcher f = FUNC.matcher(ln);
			if (f.find()) {
				for (String p : f.group(2).split(",")) {
					String part = p.trim();
					int eq = part.indexOf('=');
					String def = eq >= 0 ? part.substring(eq + 1).trim() : "";
					if (eq >= 0) {
						part = part.substring(0, eq).trim();
					}
					int colon = part.indexOf(':');
					String n = (colon >= 0 ? part.substring(0, colon) : part).trim();
					String t = colon >= 0 ? clean(part.substring(colon + 1).trim()) : "";
					if (!n.isEmpty() && n.matches("[A-Za-z_]\\w*")) {
						if (!t.isEmpty()) {
							vars.put(n, t);
							declared.add(n);
						} else if (!declared.contains(n)) {
							vars.putIfAbsent(n, def.isEmpty() ? "" : typeOf(def, vars));
						}
					}
				}
				continue;
			}
			Matcher fo = FOR.matcher(ln);
			if (fo.find()) {
				String[] ns = fo.group(1).split(",");
				String rhs = ln.substring(fo.end()).trim();
				if (rhs.endsWith(":")) {
					rhs = rhs.substring(0, rhs.length() - 1).trim();
				}
				String itType = typeOf(rhs, vars);
				for (int k = 0; k < ns.length; k++) {
					String n = ns[k].trim();
					String t = "";
					if (ns.length == 1) {
						t = rhs.startsWith("range(") ? "number" : baseOf(itType).equals("string") ? "string" : baseOf(itType).equals("map") ? keyOf(itType) : elemOf(itType);
					} else if (rhs.startsWith("enumerate(") && ns.length == 2) {
						int open = rhs.indexOf('(');
						int e = closing(rhs, open);
						String inner = e > 0 ? rhs.substring(open + 1, e) : "";
						int comma = topLevelComma(inner);
						t = k == 0 ? "number" : elemOf(typeOf(comma < 0 ? inner : inner.substring(0, comma), vars));
					}
					if (!declared.contains(n)) {
						vars.put(n, t);
					}
				}
				continue;
			}
			if (r > upToRow + 200) {
				continue;
			}
			Matcher ta = TYPED_ASSIGN.matcher(ln);
			if (ta.find()) {
				vars.put(ta.group(1), clean(ta.group(2)));
				declared.add(ta.group(1));
				continue;
			}
			Matcher ti = TYPED_INDEX.matcher(ln);
			if (ti.find()) {
				String name = ti.group(1);
				String old = vars.getOrDefault(name, "");
				String base = baseOf(old).equals("list") ? "list" : "map";
				if (!declared.contains(name)) {
					vars.put(name, withElem(base, clean(ti.group(2))));
				}
				continue;
			}
			Matcher ia = INDEX_ASSIGN.matcher(ln);
			if (ia.find() && !declared.contains(ia.group(1))) {
				String old = vars.getOrDefault(ia.group(1), "");
				if (!old.isEmpty() && elemOf(old).isEmpty()) {
					vars.put(ia.group(1), withElem(old, typeOf(ia.group(2).trim(), vars)));
				}
				continue;
			}
			Matcher ap = APPEND.matcher(ln);
			if (ap.find() && !declared.contains(ap.group(1))) {
				String old = vars.getOrDefault(ap.group(1), "");
				if (baseOf(old).equals("list") && elemOf(old).isEmpty()) {
					vars.put(ap.group(1), withElem(old, typeOf(ap.group(2).trim(), vars)));
				}
				continue;
			}
			Matcher a = ASSIGN.matcher(ln);
			if (a.find()) {
				String[] names = a.group(1).split(",");
				String rhs = a.group(2).trim();
				String type = "";
				Matcher imp = IMPLEMENT.matcher(rhs);
				if (imp.find()) {
					type = "module:" + (imp.group(1) != null ? imp.group(1) : imp.group(2));
					if (type.equals("module:khoa.fakeplayer")) {
						type = FakePlayerSuggest.MODULE;
					}
				} else if (names.length == 1) {
					type = typeOf(rhs, vars);
				}
				for (String n : names) {
					String key = n.trim();
					String prev = vars.getOrDefault(key, "");
					if (names.length == 1 && baseOf(prev).equals(type) && !elemOf(prev).isEmpty() && (rhs.equals("[]") || rhs.equals("{}"))) {
						// reset to an empty container: keep what we learned about its contents
						continue;
					}
					if (!declared.contains(key)) {
						vars.put(key, names.length == 1 ? type : "");
					}
				}
			}
		}
		return vars;
	}

	/** The expression that ends just before {@code end} in the line (a chain like a.b(c)[0]), used as the receiver of a member access. */
	public static String receiverBefore(String line, int end) {
		int i = end;
		while (i > 0) {
			char c = line.charAt(i - 1);
			if (c == ')' || c == ']') {
				int depth = 0;
				int k = i - 1;
				for (; k >= 0; k--) {
					char d = line.charAt(k);
					if (d == ')' || d == ']' || d == '}') {
						depth++;
					} else if (d == '(' || d == '[' || d == '{') {
						depth--;
						if (depth == 0) {
							break;
						}
					}
				}
				if (k < 0) {
					break;
				}
				i = k;
			} else if (word(c)) {
				while (i > 0 && word(line.charAt(i - 1))) {
					i--;
				}
				if (i > 0 && (line.charAt(i - 1) == '.' || (line.charAt(i - 1) == ':' && i > 1 && word(line.charAt(i - 2))))) {
					i--;
				} else {
					break;
				}
			} else {
				break;
			}
		}
		return line.substring(i, end);
	}
}
