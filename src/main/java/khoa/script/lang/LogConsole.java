package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The log shown in the IDE console panel: prints, info, warnings, syntax errors and runtime errors. Thread-safe. */
public final class LogConsole {
	public enum Level {
		PRINT, INFO, WARN, ERROR, SYSTEM
	}

	public record Line(long id, long timeMillis, Level level, String source, String message, String file, int line) {
	}

	private static final int MAX = 5000;
	private final List<Line> lines = new ArrayList<>();
	private final List<Consumer<Line>> listeners = new CopyOnWriteArrayList<>();
	private long nextId;
	private volatile long version;

	public synchronized void add(Level level, String source, String message, String file, int line) {
		Line l = new Line(nextId++, System.currentTimeMillis(), level, source == null ? "" : source, message, file, line);
		lines.add(l);
		if (lines.size() > MAX) {
			lines.subList(0, lines.size() - MAX).clear();
		}
		version++;
		for (Consumer<Line> c : listeners) {
			c.accept(l);
		}
	}

	public long version() {
		return version;
	}

	public synchronized List<Line> snapshot() {
		return new ArrayList<>(lines);
	}

	public synchronized void clear() {
		lines.clear();
		version++;
	}

	public void listen(Consumer<Line> c) {
		listeners.add(c);
	}

	private static String src(Session s) {
		return s == null ? "" : s.id;
	}

	public void print(Session s, String msg) {
		add(Level.PRINT, src(s), msg, null, 0);
	}

	public void info(Session s, String msg) {
		add(Level.INFO, src(s), msg, null, 0);
	}

	public void system(String msg) {
		add(Level.SYSTEM, "", msg, null, 0);
	}

	public void warn(Session s, String msg, String file, int line) {
		add(Level.WARN, src(s), msg, file, line);
	}

	public void error(Session s, String msg) {
		add(Level.ERROR, src(s), msg, null, 0);
	}

	/** A script error with file, line and call stack; the file/line make the entry clickable in the IDE. */
	public void error(Session s, KcdError e) {
		String source = null;
		if (s != null && e.file != null && s.sources != null) {
			try {
				source = s.sources.read(e.file);
			} catch (RuntimeException ignored) {
				// no excerpt then
			}
		}
		add(Level.ERROR, src(s), e.describe(source), e.file, e.line);
	}
}
