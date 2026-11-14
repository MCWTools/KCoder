package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.List;

import khoa.script.lang.Parser;
import khoa.script.lang.Registry;

/** Colors one line of KCD source. It is a scanner, not the parser, so it also works on half-typed code. */
public final class Highlighter {
	public record Span(int start, int end, int color) {
	}

	public static final int TEXT = 0xFFD7DAE0;
	public static final int KEYWORD = 0xFFC586C0;
	public static final int STRING = 0xFFCE9178;
	public static final int NUMBER = 0xFFB5CEA8;
	public static final int COMMENT = 0xFF6A9955;
	public static final int BUILTIN = 0xFF61AFEF;
	public static final int CALL = 0xFFDCDCAA;
	public static final int EVENT = 0xFFE06C75;
	public static final int CONST = 0xFFD19A66;
	/** true is bright lime and false bright red; the editor draws both in bold. */
	public static final int TRUE = 0xFF7CFF3A;
	public static final int FALSE = 0xFFFF3B3B;
	public static final int RESID = 0xFF4EC9B0;
	public static final int MEMBER = 0xFF56B6C2;
	public static final int DEF = 0xFFE5C07B;
	public static final int OP = 0xFF9AA5B1;

	private Highlighter() {
	}

	private static boolean wordStart(char c) {
		return Character.isLetter(c) || c == '_';
	}

	private static boolean word(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	public static List<Span> line(String t) {
		List<Span> out = new ArrayList<>();
		Registry reg = Registry.get();
		int n = t.length();
		int i = 0;
		String prevWord = "";
		char prevSig = ' ';
		while (i < n) {
			char c = t.charAt(i);
			if (c == ' ') {
				i++;
				continue;
			}
			if (c == '#') {
				out.add(new Span(i, n, COMMENT));
				break;
			}
			if (c == '"' || c == '\'') {
				int j = i + 1;
				while (j < n && t.charAt(j) != c) {
					if (t.charAt(j) == '\\') {
						j++;
					}
					j++;
				}
				j = Math.min(n, j + 1);
				out.add(new Span(i, j, STRING));
				i = j;
				prevSig = '"';
				continue;
			}
			if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(t.charAt(i + 1)) && !word(prevSig))) {
				int j = i + 1;
				while (j < n && (Character.isDigit(t.charAt(j)) || t.charAt(j) == '.' || t.charAt(j) == '_')) {
					j++;
				}
				out.add(new Span(i, j, NUMBER));
				i = j;
				prevSig = '1';
				continue;
			}
			if (wordStart(c)) {
				int j = i + 1;
				while (j < n && word(t.charAt(j))) {
					j++;
				}
				String w = t.substring(i, j);
				boolean call = j < n && t.charAt(j) == '(';
				// a:b resource id (not followed by a call) or module:function() call
				if (j + 1 < n && t.charAt(j) == ':' && wordStart(t.charAt(j + 1)) && !Parser.isKeyword(w) && (i == 0 || t.charAt(i - 1) != '.')) {
					int k = j + 1;
					while (k < n && (word(t.charAt(k)) || t.charAt(k) == '.' || t.charAt(k) == '/')) {
						k++;
					}
					if (k < n && t.charAt(k) == '(') {
						out.add(new Span(i, j, DEF));
						out.add(new Span(j, j + 1, OP));
						out.add(new Span(j + 1, k, CALL));
					} else {
						out.add(new Span(i, k, RESID));
					}
					i = k;
					prevSig = 'a';
					prevWord = "";
					continue;
				}
				int color;
				if (prevSig == '.') {
					color = call ? CALL : MEMBER;
				} else if (w.equalsIgnoreCase("true")) {
					color = TRUE;
				} else if (w.equalsIgnoreCase("false")) {
					color = FALSE;
				} else if (w.equals("nil") || w.equals("True") || w.equals("False") || w.equals("None")) {
					color = CONST;
				} else if (Parser.isKeyword(w) || ((w.equals("match") || w.equals("case")) && i == t.length() - t.stripLeading().length() && t.stripTrailing().endsWith(":"))) {
					color = KEYWORD;
				} else if (prevWord.equals("function") || prevWord.equals("def") || prevWord.equals("fn")) {
					color = DEF;
				} else if (reg.events.containsKey(w)) {
					color = EVENT;
				} else if (reg.builtins.containsKey(w)) {
					color = BUILTIN;
				} else if (call) {
					color = CALL;
				} else {
					color = TEXT;
				}
				out.add(new Span(i, j, color));
				prevWord = w;
				prevSig = 'a';
				i = j;
				continue;
			}
			out.add(new Span(i, i + 1, OP));
			prevSig = c;
			prevWord = "";
			i++;
		}
		return out;
	}
}
