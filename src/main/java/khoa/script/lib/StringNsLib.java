package khoa.script.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** The {@code string} namespace: string.find(text, "part", false), string.replace(...), ... grouped like color.* and enum.*. */
public final class StringNsLib {
	private static final Registry R = Registry.get();

	private StringNsLib() {
	}

	public record StringNs() implements Val.KObject {
		@Override
		public String typeName() {
			return "string_ns";
		}

		@Override
		public String toString() {
			return "<string>";
		}
	}

	private static String fold(String s, boolean caseSensitive) {
		return caseSensitive ? s : s.toLowerCase(Locale.ROOT);
	}

	public static void register() {
		R.constants.put("string", new StringNs());
		String t = "string_ns";
		R.typeDoc(t, "Text helpers grouped in one place: string.find(text, part), string.replace(text, a, b) ... Every function takes the text first.");
		R.method(t, "find(text:string, part:string, case_sensitive?:boolean=true) -> boolean", "True if part appears in text. string.find(\"Hello\", \"hello\", false) = true.",
			(in, s, g) -> fold(Val.str(g[0]), flag(g, 2)).contains(fold(Val.str(g[1]), flag(g, 2))));
		R.method(t, "index(text:string, part:string, case_sensitive?:boolean=true) -> number", "Position (0-based) of the first match, or -1.",
			(in, s, g) -> (double) fold(Val.str(g[0]), flag(g, 2)).indexOf(fold(Val.str(g[1]), flag(g, 2))));
		R.method(t, "count(text:string, part:string, case_sensitive?:boolean=true) -> number", "How many times part appears (not overlapping).", (in, s, g) -> {
			String x = fold(Val.str(g[0]), flag(g, 2));
			String p = fold(Val.str(g[1]), flag(g, 2));
			if (p.isEmpty()) {
				return 0.0;
			}
			int n = 0;
			for (int i = x.indexOf(p); i >= 0; i = x.indexOf(p, i + p.length())) {
				n++;
			}
			return (double) n;
		});
		R.method(t, "starts_with(text:string, part:string, case_sensitive?:boolean=true) -> boolean", "True if text begins with part.",
			(in, s, g) -> fold(Val.str(g[0]), flag(g, 2)).startsWith(fold(Val.str(g[1]), flag(g, 2))));
		R.method(t, "ends_with(text:string, part:string, case_sensitive?:boolean=true) -> boolean", "True if text ends with part.",
			(in, s, g) -> fold(Val.str(g[0]), flag(g, 2)).endsWith(fold(Val.str(g[1]), flag(g, 2))));
		R.method(t, "replace(text:string, old:string, new:string, case_sensitive?:boolean=true) -> string", "Replaces every old with new.", (in, s, g) -> {
			String x = Val.str(g[0]);
			String o = Val.str(g[1]);
			String n = Val.str(g[2]);
			if (flag(g, 3)) {
				return x.replace(o, n);
			}
			return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(o), java.util.regex.Pattern.CASE_INSENSITIVE).matcher(x).replaceAll(java.util.regex.Matcher.quoteReplacement(n));
		});
		R.method(t, "split(text:string, separator:string) -> list<string>", "Cuts text at every separator.", (in, s, g) -> {
			List<Object> out = new ArrayList<>();
			String sep = Val.str(g[1]);
			String x = Val.str(g[0]);
			if (sep.isEmpty()) {
				for (char c : x.toCharArray()) {
					out.add(String.valueOf(c));
				}
				return out;
			}
			int from = 0;
			for (int i = x.indexOf(sep); i >= 0; i = x.indexOf(sep, from)) {
				out.add(x.substring(from, i));
				from = i + sep.length();
			}
			out.add(x.substring(from));
			return out;
		});
		R.method(t, "trim(text:string) -> string", "Removes spaces at both ends.", (in, s, g) -> Val.str(g[0]).trim());
		R.method(t, "upper(text:string) -> string", "UPPER CASE.", (in, s, g) -> Val.str(g[0]).toUpperCase(Locale.ROOT));
		R.method(t, "lower(text:string) -> string", "lower case.", (in, s, g) -> Val.str(g[0]).toLowerCase(Locale.ROOT));
		R.method(t, "reverse(text:string) -> string", "Backwards.", (in, s, g) -> new StringBuilder(Val.str(g[0])).reverse().toString());
		R.method(t, "length(text:string) -> number", "Number of characters.", (in, s, g) -> (double) Val.str(g[0]).length());
	}

	private static boolean flag(Object[] g, int i) {
		return g.length <= i || g[i] == null || Val.truthy(g[i]);
	}
}
