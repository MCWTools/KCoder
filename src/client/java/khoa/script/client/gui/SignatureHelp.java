package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import khoa.script.lang.Builtin;
import khoa.script.lang.Registry;

/** Parameter hints: while the cursor is inside the parentheses of a call, which function it is and which argument is being typed. */
public final class SignatureHelp {
	/** {@code row}/{@code col} = where the opening parenthesis is. {@code active} = index of the argument under the cursor (-1 if the function takes none). */
	public record Sig(String name, List<String> params, int active, String returns, String doc, int row, int col) {
		/** Declared type of the argument under the cursor ("attribute", "entity" ...), or "". */
		public String activeType() {
			if (active < 0 || active >= params.size()) {
				return "";
			}
			String p = params.get(active);
			int colon = p.indexOf(": ");
			if (colon < 0) {
				return "";
			}
			String t = p.substring(colon + 2);
			int eq = t.indexOf(" =");
			return (eq >= 0 ? t.substring(0, eq) : t).trim();
		}
	}

	private static final Pattern FUNC = Pattern.compile("^\\s*(?:function|def|fn)\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)");
	private static final Set<String> NOT_CALLS = Set.of("if", "elif", "while", "for", "in", "and", "or", "not", "return", "lambda", "is", "assert", "print_nothing");
	private static final int LOOKBACK = 40;

	private SignatureHelp() {
	}

	private static boolean word(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** An open bracket: type, where it is, commas seen at its own level, and where the current argument starts. */
	private static final class Frame {
		final char open;
		final int row;
		final int col;
		int commas;
		int argRow;
		int argCol;

		Frame(char open, int row, int col) {
			this.open = open;
			this.row = row;
			this.col = col;
			this.argRow = row;
			this.argCol = col + 1;
		}
	}

	public static Sig at(List<String> lines, int row, int col) {
		int first = Math.max(0, row - LOOKBACK);
		List<Frame> stack = new ArrayList<>();
		for (int r = first; r <= row; r++) {
			String ln = lines.get(r);
			int end = r == row ? Math.min(col, ln.length()) : ln.length();
			char quote = 0;
			for (int i = 0; i < end; i++) {
				char c = ln.charAt(i);
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
				} else if (c == '#') {
					break;
				} else if (c == '(' || c == '[' || c == '{') {
					stack.add(new Frame(c, r, i));
				} else if (c == ')' || c == ']' || c == '}') {
					if (!stack.isEmpty()) {
						stack.remove(stack.size() - 1);
					}
				} else if (c == ',' && !stack.isEmpty()) {
					Frame f = stack.get(stack.size() - 1);
					f.commas++;
					f.argRow = r;
					f.argCol = i + 1;
				}
			}
			if (quote != 0 && r == row) {
				return null; // typing inside a string: hints would be noise
			}
		}
		Frame call = null;
		for (int k = stack.size() - 1; k >= 0; k--) {
			if (stack.get(k).open == '(') {
				call = stack.get(k);
				break;
			}
		}
		if (call == null) {
			return null;
		}
		String line = lines.get(call.row);
		int nameEnd = call.col;
		while (nameEnd > 0 && line.charAt(nameEnd - 1) == ' ') {
			nameEnd--;
		}
		int nameStart = nameEnd;
		while (nameStart > 0 && word(line.charAt(nameStart - 1))) {
			nameStart--;
		}
		if (nameStart == nameEnd) {
			return null;
		}
		String name = line.substring(nameStart, nameEnd);
		if (NOT_CALLS.contains(name) || Character.isDigit(name.charAt(0))) {
			return null;
		}
		String beforeName = line.substring(0, nameStart).trim();
		if (beforeName.endsWith("function") || beforeName.endsWith("def") || beforeName.endsWith("fn")) {
			return null; // the parameter list of a definition
		}
		// the text of the argument being typed, to spot  name=value
		StringBuilder arg = new StringBuilder();
		for (int r = call.argRow; r <= row; r++) {
			String ln = lines.get(r);
			int from = r == call.argRow ? Math.min(call.argCol, ln.length()) : 0;
			int to = r == row ? Math.min(col, ln.length()) : ln.length();
			if (from < to) {
				arg.append(ln, from, to);
			}
		}
		Matcher named = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*=(?!=)").matcher(arg);
		String namedArg = named.find() ? named.group(1) : null;

		Registry reg = Registry.get();
		Map<String, String> vars = null;
		boolean member = nameStart > 0 && (line.charAt(nameStart - 1) == '.' || (line.charAt(nameStart - 1) == ':' && nameStart > 1 && word(line.charAt(nameStart - 2))));
		List<String> params = null;
		List<String> names = new ArrayList<>();
		String returns = "";
		String doc = "";
		boolean variadic = false;
		if (!member) {
			Builtin b = reg.builtin(name);
			Registry.EventType ev = reg.events.get(name);
			if (b != null) {
				params = new ArrayList<>();
				for (Builtin.ParamDef p : b.params) {
					params.add(format(p));
					names.add(p.name());
				}
				variadic = b.variadic();
				returns = b.returns;
				doc = b.description;
			} else if (ev != null) {
				params = new ArrayList<>(ev.filters);
				names.addAll(ev.filters);
				doc = ev.description;
			} else {
				List<String> fn = userFunction(lines, name);
				if (fn != null) {
					params = fn;
					for (String p : fn) {
						names.add(p.split("[:=\\s]")[0]);
					}
					doc = "Function defined in this file";
				}
			}
		} else {
			vars = SuggestTypes.variables(lines, call.row);
			String recvExpr = SuggestTypes.receiverBefore(line, nameStart - 1);
			String type = SuggestTypes.typeOf(recvExpr, vars);
			if (type.startsWith("module:")) {
				String path = Suggest.resolvePath(type.substring(7));
				String src = Suggest.source.apply(path.endsWith(".kcd") ? path : path + ".kcd");
				if (src != null) {
					List<String> fn = userFunction(List.of(src.split("\n", -1)), name);
					if (fn != null) {
						params = fn;
						for (String p : fn) {
							names.add(p.split("[:=\\s]")[0]);
						}
						doc = "Function of module " + recvExpr;
					}
				}
			} else {
				Registry.Member m = type.isEmpty() ? null : reg.member(SuggestTypes.baseOf(type), name);
				if (m == null && type.isEmpty()) {
					for (String t : reg.members.keySet()) {
						Registry.Member c = reg.member(t, name);
						if (c != null && c.method) {
							m = c;
							break;
						}
					}
				}
				if (m != null && m.method) {
					params = new ArrayList<>();
					for (Builtin.ParamDef p : m.params) {
						params.add(format(p));
						names.add(p.name());
					}
					variadic = !m.params.isEmpty() && m.params.get(m.params.size() - 1).variadic();
					returns = m.returns;
					doc = m.doc;
				}
			}
		}
		if (params == null) {
			return null;
		}
		int active = call.commas;
		if (namedArg != null && names.contains(namedArg)) {
			active = names.indexOf(namedArg);
		}
		if (params.isEmpty()) {
			active = -1;
		} else if (active >= params.size()) {
			active = variadic ? params.size() - 1 : params.size() - 1;
		}
		return new Sig(name, params, active, returns, doc, call.row, call.col);
	}

	private static String format(Builtin.ParamDef p) {
		StringBuilder sb = new StringBuilder();
		if (p.variadic()) {
			sb.append("...");
		}
		sb.append(p.name());
		if (p.optional() && p.def() == null) {
			sb.append('?');
		}
		sb.append(": ").append(p.type());
		if (p.def() != null) {
			sb.append(" = ").append(p.def());
		}
		return sb.toString();
	}

	/** Parameters of "function name(a, b: entity, c = 3)" written in the given lines, formatted like the builtins. */
	private static List<String> userFunction(List<String> lines, String name) {
		for (String ln : lines) {
			Matcher f = FUNC.matcher(ln);
			if (f.find() && f.group(1).equals(name)) {
				List<String> out = new ArrayList<>();
				String inner = f.group(2).trim();
				if (!inner.isEmpty()) {
					for (String part : inner.split(",")) {
						String p = part.trim();
						String def = "";
						int eq = p.indexOf('=');
						if (eq >= 0) {
							def = " = " + p.substring(eq + 1).trim();
							p = p.substring(0, eq).trim();
						}
						int colon = p.indexOf(':');
						out.add(colon >= 0 ? p.substring(0, colon).trim() + ": " + p.substring(colon + 1).trim() + def : p + def);
					}
				}
				return out;
			}
		}
		return null;
	}
}
