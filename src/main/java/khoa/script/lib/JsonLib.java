package khoa.script.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** json_parse / json_stringify, also used to persist save_data values. */
public final class JsonLib {
	private JsonLib() {
	}

	public static void register() {
		Registry r = Registry.get();
		r.def("data", "json_parse(text:string) -> any", "Turns JSON text into maps, lists, numbers, strings, booleans and nil.", a -> parse(a.str(0)));
		r.def("data", "json_stringify(value:any, pretty?:boolean) -> string", "Turns a value into JSON text. Vectors become lists; unknown things become strings.", a -> stringify(a.obj(0), a.bool(1, false)));
	}

	public static String stringify(Object v, boolean pretty) {
		StringBuilder sb = new StringBuilder();
		write(sb, v, pretty, 0, 0);
		return sb.toString();
	}

	private static void indent(StringBuilder sb, boolean pretty, int level) {
		if (pretty) {
			sb.append('\n');
			sb.append("  ".repeat(level));
		}
	}

	private static void write(StringBuilder sb, Object v, boolean pretty, int level, int depth) {
		if (depth > 40) {
			sb.append("null");
			return;
		}
		if (v == null) {
			sb.append("null");
		} else if (v instanceof Boolean b) {
			sb.append(b);
		} else if (v instanceof Double d) {
			if (d.isNaN() || d.isInfinite()) {
				sb.append("null");
			} else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				sb.append((long) d.doubleValue());
			} else {
				sb.append(d);
			}
		} else if (v instanceof Map<?, ?> m) {
			if (m.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				indent(sb, pretty, level + 1);
				quote(sb, Val.str(e.getKey()));
				sb.append(pretty ? ": " : ":");
				write(sb, e.getValue(), pretty, level + 1, depth + 1);
			}
			indent(sb, pretty, level);
			sb.append('}');
		} else if (v instanceof List<?> l) {
			writeList(sb, l, pretty, level, depth);
		} else if (v instanceof Val.Tuple t) {
			writeList(sb, t.items(), pretty, level, depth);
		} else if (v instanceof Val.Vec3 p) {
			writeList(sb, List.of(p.x(), p.y(), p.z()), pretty, level, depth);
		} else if (v instanceof Val.Vec2 p) {
			writeList(sb, List.of(p.x(), p.y()), pretty, level, depth);
		} else {
			quote(sb, Val.str(v));
		}
	}

	private static void writeList(StringBuilder sb, List<?> l, boolean pretty, int level, int depth) {
		if (l.isEmpty()) {
			sb.append("[]");
			return;
		}
		sb.append('[');
		boolean first = true;
		for (Object o : l) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			indent(sb, pretty, level + 1);
			write(sb, o, pretty, level + 1, depth + 1);
		}
		indent(sb, pretty, level);
		sb.append(']');
	}

	private static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// ------------------------------------------------------------ parsing
	public static Object parse(String text) {
		Reader r = new Reader(text);
		r.ws();
		Object v = r.value(0);
		r.ws();
		if (r.i < text.length()) {
			throw r.fail("Unexpected text after the JSON value");
		}
		return v;
	}

	private static final class Reader {
		final String s;
		int i;

		Reader(String s) {
			this.s = s;
		}

		KcdError fail(String msg) {
			return new KcdError("Invalid JSON: " + msg + " at character " + i, 0);
		}

		void ws() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		Object value(int depth) {
			if (depth > 60) {
				throw fail("nested too deeply");
			}
			if (i >= s.length()) {
				throw fail("unexpected end");
			}
			char c = s.charAt(i);
			if (c == '{') {
				i++;
				Map<Object, Object> m = new LinkedHashMap<>();
				ws();
				if (peek('}')) {
					i++;
					return m;
				}
				while (true) {
					ws();
					if (!peek('"')) {
						throw fail("expected a string key");
					}
					String k = string();
					ws();
					expect(':');
					ws();
					m.put(k, value(depth + 1));
					ws();
					if (peek(',')) {
						i++;
					} else {
						expect('}');
						return m;
					}
				}
			}
			if (c == '[') {
				i++;
				List<Object> l = new ArrayList<>();
				ws();
				if (peek(']')) {
					i++;
					return l;
				}
				while (true) {
					ws();
					l.add(value(depth + 1));
					ws();
					if (peek(',')) {
						i++;
					} else {
						expect(']');
						return l;
					}
				}
			}
			if (c == '"') {
				return string();
			}
			if (s.startsWith("true", i)) {
				i += 4;
				return true;
			}
			if (s.startsWith("false", i)) {
				i += 5;
				return false;
			}
			if (s.startsWith("null", i)) {
				i += 4;
				return null;
			}
			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
				i++;
			}
			if (start == i) {
				throw fail("unexpected '" + c + "'");
			}
			try {
				return Double.parseDouble(s.substring(start, i));
			} catch (NumberFormatException e) {
				throw fail("bad number");
			}
		}

		boolean peek(char c) {
			return i < s.length() && s.charAt(i) == c;
		}

		void expect(char c) {
			if (!peek(c)) {
				throw fail("expected '" + c + "'");
			}
			i++;
		}

		String string() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (i < s.length()) {
				char c = s.charAt(i++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (i >= s.length()) {
						break;
					}
					char e = s.charAt(i++);
					switch (e) {
						case 'n' -> sb.append('\n');
						case 't' -> sb.append('\t');
						case 'r' -> sb.append('\r');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'u' -> {
							if (i + 4 > s.length()) {
								throw fail("bad \\u escape");
							}
							sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
							i += 4;
						}
						default -> sb.append(e);
					}
				} else {
					sb.append(c);
				}
			}
			throw fail("unterminated string");
		}
	}
}
