package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;

/** A script error (syntax or runtime) with a position and a call stack. Catchable with try/catch inside scripts. */
public class KcdError extends RuntimeException {
	public final String file;
	public final int line;
	public final int col;
	public final List<String> stack = new ArrayList<>();
	/** Optional hint shown by the editor as a quick fix. */
	public String hint;

	public KcdError(String message, String file, int line, int col) {
		super(message);
		this.file = file;
		this.line = line;
		this.col = col;
	}

	public KcdError(String message, int line) {
		this(message, null, line, 0);
	}

	public KcdError withHint(String hint) {
		this.hint = hint;
		return this;
	}

	public KcdError at(String file, int line) {
		return at(file, line, col);
	}

	public KcdError at(String file, int line, int column) {
		KcdError e = new KcdError(getMessage(), file, line, column);
		e.hint = hint;
		e.stack.addAll(stack);
		return e;
	}

	/** Like describe(), plus the offending line of code (with its neighbours) and a caret under the column. */
	public String describe(String source) {
		StringBuilder sb = new StringBuilder();
		if (file != null) {
			sb.append(file).append(':');
		}
		if (line > 0) {
			sb.append(line).append(": ");
		}
		sb.append(getMessage());
		if (source != null && line > 0) {
			String[] lines = source.split("\r?\n", -1);
			if (line <= lines.length) {
				int from = Math.max(1, line - 1);
				int to = Math.min(lines.length, line + 1);
				int width = String.valueOf(to).length();
				for (int n = from; n <= to; n++) {
					String text = lines[n - 1].stripTrailing();
					if (text.length() > 110) {
						text = text.substring(0, 107) + "...";
					}
					sb.append("\n  ").append(n == line ? "> " : "  ").append(String.format("%" + width + "d", n)).append(" | ").append(text);
					if (n == line && col > 0 && col <= text.length() + 1) {
						sb.append("\n  ").append("  ").append(" ".repeat(width)).append(" | ").append(" ".repeat(col - 1)).append('^');
					}
				}
			}
		}
		if (hint != null) {
			sb.append("\n  hint: ").append(hint);
		}
		for (String s : stack) {
			sb.append("\n    at ").append(s);
		}
		return sb.toString();
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		if (file != null) {
			sb.append(file).append(':');
		}
		if (line > 0) {
			sb.append(line).append(": ");
		}
		sb.append(getMessage());
		if (hint != null) {
			sb.append("  (hint: ").append(hint).append(')');
		}
		for (String s : stack) {
			sb.append("\n    at ").append(s);
		}
		return sb.toString();
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
