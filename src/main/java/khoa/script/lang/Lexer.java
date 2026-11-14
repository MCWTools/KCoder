package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;

/** Indentation-aware tokenizer. Newlines and indentation are ignored inside (), [] and {}. */
public final class Lexer {
	private static final String[] OPS3 = {"**=", "//="};
	private static final String[] OPS2 = {"**", "//", "..", "==", "!=", "~=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "->", "??"};
	private static final String SINGLE = "+-*/%<>=()[]{},:.;\\";

	private final String src;
	private final String file;
	private int pos;
	private int line;
	private int lineStart;
	private int depth;
	private final List<Token> out = new ArrayList<>();
	private final List<Integer> indents = new ArrayList<>(List.of(0));
	private boolean atLineStart = true;

	private Lexer(String src, String file, int firstLine) {
		this.src = src.replace("\r\n", "\n").replace('\r', '\n');
		this.file = file;
		this.line = firstLine;
	}

	public static List<Token> lex(String src, String file) {
		return new Lexer(src, file, 1).run();
	}

	/** Lexes an expression fragment (for string interpolation); newlines inside are plain whitespace. */
	static List<Token> lexFragment(String src, String file, int line) {
		Lexer lx = new Lexer(src, file, line);
		lx.depth = 1;
		return lx.run();
	}

	private KcdError err(String msg) {
		return new KcdError(msg, file, line, pos - lineStart + 1);
	}

	private void add(Token.Kind k, String text, int col, boolean space, Object value) {
		out.add(new Token(k, text, line, col, space, value));
	}

	private List<Token> run() {
		int n = src.length();
		while (pos < n) {
			if (atLineStart && depth == 0) {
				if (!handleIndent()) {
					continue;
				}
				if (pos >= n) {
					break; // only spaces were left at the end of the file
				}
			}
			atLineStart = false;
			char c = src.charAt(pos);
			boolean space = pos == 0 || src.charAt(pos - 1) == ' ' || src.charAt(pos - 1) == '\t' || src.charAt(pos - 1) == '\n';
			int col = pos - lineStart + 1;
			if (c == ' ' || c == '\t') {
				pos++;
			} else if (c == '#') {
				while (pos < n && src.charAt(pos) != '\n') {
					pos++;
				}
			} else if (c == '\\' && pos + 1 < n && src.charAt(pos + 1) == '\n') {
				pos += 2;
				line++;
				lineStart = pos;
			} else if (c == '\n') {
				pos++;
				if (depth == 0 && !out.isEmpty() && out.get(out.size() - 1).kind() != Token.Kind.NEWLINE) {
					add(Token.Kind.NEWLINE, "\n", col, false, null);
				}
				line++;
				lineStart = pos;
				atLineStart = true;
			} else if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(src.charAt(pos + 1)) && !prevIsValue())) {
				number(col, space);
			} else if (Character.isLetter(c) || c == '_') {
				int s = pos;
				while (pos < n && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
					pos++;
				}
				String word = src.substring(s, pos);
				add(Token.Kind.NAME, word, col, space, null);
				if (word.equals("implement")) {
					path();
				}
			} else if (c == '"' || c == '\'') {
				string(c, col, space);
			} else {
				operator(col, space);
			}
		}
		if (!out.isEmpty() && out.get(out.size() - 1).kind() != Token.Kind.NEWLINE && depth == 0) {
			add(Token.Kind.NEWLINE, "\n", pos - lineStart + 1, false, null);
		}
		while (indents.size() > 1) {
			indents.remove(indents.size() - 1);
			add(Token.Kind.DEDENT, "", pos - lineStart + 1, false, null);
		}
		add(Token.Kind.EOF, "", pos - lineStart + 1, false, null);
		return out;
	}

	private boolean prevIsValue() {
		if (out.isEmpty()) {
			return false;
		}
		Token t = out.get(out.size() - 1);
		return t.kind() == Token.Kind.NAME || t.kind() == Token.Kind.NUM || t.kind() == Token.Kind.STR || t.isOp(")") || t.isOp("]") || t.isOp("}");
	}

	/** Returns false when the line was blank or comment-only (position already advanced). */
	private boolean handleIndent() {
		int n = src.length();
		int p = pos;
		int width = 0;
		while (p < n && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
			width += src.charAt(p) == '\t' ? 4 : 1;
			p++;
		}
		if (p >= n) {
			pos = p;
			return true;
		}
		char c = src.charAt(p);
		if (c == '\n' || c == '#') {
			pos = p;
			while (pos < n && src.charAt(pos) != '\n') {
				pos++;
			}
			if (pos < n) {
				pos++;
				line++;
				lineStart = pos;
			}
			return false;
		}
		pos = p;
		int cur = indents.get(indents.size() - 1);
		if (width > cur) {
			// Only a line after "...:" opens a block. Extra indentation anywhere else is forgiven (the line stays at the current level).
			int k = out.size() - 1;
			while (k >= 0 && out.get(k).kind() == Token.Kind.NEWLINE) {
				k--;
			}
			if (k >= 0 && out.get(k).isOp(":")) {
				indents.add(width);
				add(Token.Kind.INDENT, "", 1, false, null);
			}
		} else if (width < cur) {
			// Dedenting to a width that matches no open block is also forgiven: the line joins the nearest outer block.
			while (indents.get(indents.size() - 1) > width) {
				indents.remove(indents.size() - 1);
				add(Token.Kind.DEDENT, "", 1, false, null);
			}
		}
		return true;
	}

	private void number(int col, boolean space) {
		int s = pos;
		int n = src.length();
		if (src.charAt(pos) == '0' && pos + 1 < n && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')) {
			pos += 2;
			while (pos < n && Character.digit(src.charAt(pos), 16) >= 0) {
				pos++;
			}
			add(Token.Kind.NUM, src.substring(s, pos), col, space, (double) Long.parseLong(src.substring(s + 2, pos), 16));
			return;
		}
		while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
			pos++;
		}
		if (pos < n && src.charAt(pos) == '.' && pos + 1 < n && Character.isDigit(src.charAt(pos + 1))) {
			pos++;
			while (pos < n && Character.isDigit(src.charAt(pos))) {
				pos++;
			}
		}
		if (pos < n && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
			int q = pos + 1;
			if (q < n && (src.charAt(q) == '+' || src.charAt(q) == '-')) {
				q++;
			}
			if (q < n && Character.isDigit(src.charAt(q))) {
				pos = q;
				while (pos < n && Character.isDigit(src.charAt(pos))) {
					pos++;
				}
			}
		}
		String text = src.substring(s, pos).replace("_", "");
		add(Token.Kind.NUM, text, col, space, Double.parseDouble(text));
	}

	/** After the keyword "implement": reads a bare path like ./features/x.kcd (a quoted path is lexed as a string). */
	private void path() {
		int n = src.length();
		int p = pos;
		while (p < n && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
			p++;
		}
		if (p >= n || src.charAt(p) == '"' || src.charAt(p) == '\'' || src.charAt(p) == '\n' || src.charAt(p) == '=' || src.charAt(p) == '(') {
			return;
		}
		int s = p;
		while (p < n && !Character.isWhitespace(src.charAt(p)) && src.charAt(p) != ')' && src.charAt(p) != ',' && src.charAt(p) != '#') {
			p++;
		}
		String text = src.substring(s, p);
		if (text.equals("as")) {
			return;
		}
		out.add(new Token(Token.Kind.PATH, text, line, s - lineStart + 1, true, null));
		pos = p;
	}

	private void string(char q, int col, boolean space) {
		int n = src.length();
		boolean triple = pos + 2 < n && src.charAt(pos + 1) == q && src.charAt(pos + 2) == q;
		String closer = triple ? "" + q + q + q : "" + q;
		int startLine = line;
		pos += triple ? 3 : 1;
		List<Object> parts = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= n) {
				throw err("Unterminated string");
			}
			char c = src.charAt(pos);
			if (src.startsWith(closer, pos)) {
				pos += closer.length();
				break;
			}
			if (c == '\n') {
				if (!triple) {
					throw err("Unterminated string");
				}
				sb.append('\n');
				pos++;
				line++;
				lineStart = pos;
			} else if (c == '\\') {
				if (pos + 1 >= n) {
					throw err("Bad escape");
				}
				char e = src.charAt(pos + 1);
				pos += 2;
				switch (e) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'r' -> sb.append('\r');
					case '0' -> sb.append('\0');
					case '\\', '"', '\'', '{', '}' -> sb.append(e);
					case '\n' -> {
						line++;
						lineStart = pos;
					}
					default -> sb.append('\\').append(e);
				}
			} else if (c == '{') {
				int s = pos + 1;
				int d = 1;
				int p = s;
				char inQ = 0;
				while (p < n && d > 0) {
					char ch = src.charAt(p);
					if (inQ != 0) {
						if (ch == '\\') {
							p++;
						} else if (ch == inQ) {
							inQ = 0;
						}
					} else if (ch == '"' || ch == '\'') {
						inQ = ch;
					} else if (ch == '{') {
						d++;
					} else if (ch == '}') {
						d--;
					} else if (ch == '\n') {
						break;
					}
					p++;
				}
				if (d != 0) {
					throw err("Unclosed { in string (use \\{ for a literal brace)");
				}
				String inner = src.substring(s, p - 1).strip();
				if (inner.isEmpty() || inner.chars().allMatch(Character::isDigit)) {
					// "{}" and "{0}" are format() placeholders, not interpolation
					sb.append(src, s - 1, p);
					pos = p;
					continue;
				}
				if (sb.length() > 0) {
					parts.add(sb.toString());
					sb.setLength(0);
				}
				parts.add(new Token.ExprSrc(src.substring(s, p - 1), line, s - lineStart + 1));
				pos = p;
			} else {
				sb.append(c);
				pos++;
			}
		}
		if (sb.length() > 0 || parts.isEmpty()) {
			parts.add(sb.toString());
		}
		out.add(new Token(Token.Kind.STR, "<string>", startLine, col, space, parts));
	}

	private void operator(int col, boolean space) {
		for (String op : OPS3) {
			if (src.startsWith(op, pos)) {
				pos += 3;
				add(Token.Kind.OP, op, col, space, null);
				return;
			}
		}
		for (String op : OPS2) {
			if (src.startsWith(op, pos)) {
				pos += 2;
				add(Token.Kind.OP, op, col, space, null);
				return;
			}
		}
		char c = src.charAt(pos);
		if (SINGLE.indexOf(c) < 0) {
			throw err("Unexpected character '" + c + "'");
		}
		pos++;
		if (c == '(' || c == '[' || c == '{') {
			depth++;
		} else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
			depth--;
		}
		add(Token.Kind.OP, String.valueOf(c), col, space, null);
	}
}
