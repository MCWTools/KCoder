package khoa.script.lang;

/** One lexical token. {@code value} is a Double for NUM and a List of parts (String | ExprSrc) for STR. */
public record Token(Kind kind, String text, int line, int col, boolean spaceBefore, Object value) {
	public enum Kind {
		NUM, STR, NAME, OP, PATH, NEWLINE, INDENT, DEDENT, EOF
	}

	/** Source text of a {@code {expr}} inside a string literal. */
	public record ExprSrc(String text, int line, int col) {
	}

	public boolean isOp(String op) {
		return kind == Kind.OP && text.equals(op);
	}

	public boolean isName(String name) {
		return kind == Kind.NAME && text.equals(name);
	}

	@Override
	public String toString() {
		return kind + "(" + text + ")@" + line + ":" + col;
	}
}
