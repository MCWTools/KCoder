package khoa.script.lang;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The scripting runtime: scheduler, event bus, log, sessions. The game integration calls scheduler.onTick() / onFrame() and fires events. */
public final class Engine {
	@FunctionalInterface
	public interface Permissions {
		/** Throws a KcdError when the permission is not granted. */
		void check(String permission, String function);
	}

	public final Registry reg = Registry.get();
	public final LogConsole console = new LogConsole();
	public final Scheduler scheduler = new Scheduler(this);
	public final EventBus events = new EventBus(this);
	public final Profiler profiler = new Profiler();
	public final Debugger debugger = new Debugger();
	public final Map<String, Session> sessions = new ConcurrentHashMap<>();
	public Permissions permissions = (perm, fn) -> {
	};
	public Consumer<ScriptTask> onTaskFinished;
	public int maxCallDepth = 120;
	public long instructionBudget = 1_500_000;

	public Session session(String id, Session.Sources sources) {
		Session s = new Session(this, id, sources);
		sessions.put(id, s);
		return s;
	}

	public Session find(String id) {
		return sessions.get(id);
	}

	public void remove(String id) {
		Session s = sessions.remove(id);
		if (s != null) {
			s.stop();
		}
	}

	/**
	 * Starts a program: parses every file first (all syntax errors are reported together, nothing starts if any file is broken), then spawns one task per entry file.
	 * Returns false if there were syntax errors.
	 */
	public boolean start(Session s, List<String> entryFiles) {
		boolean ok = true;
		List<Map.Entry<String, List<Node>>> parsed = new java.util.ArrayList<>();
		for (String f : entryFiles) {
			String src = s.sources.read(f);
			if (src == null) {
				console.error(s, "File not found: " + f);
				ok = false;
				continue;
			}
			try {
				parsed.add(Map.entry(f, s.parse(f, src)));
			} catch (KcdError e) {
				console.add(LogConsole.Level.ERROR, s.id, "Syntax error: " + e.describe(), f, e.line);
				ok = false;
			}
		}
		if (!ok) {
			return false;
		}
		for (Map.Entry<String, List<Node>> p : parsed) {
			String f = p.getKey();
			List<Node> prog = p.getValue();
			s.spawn(f, f, in -> in.runProgram(prog, new Scope(null, Paths.stem(f)), f));
		}
		return true;
	}

	public void stopAll() {
		for (Session s : sessions.values()) {
			s.stop();
		}
		sessions.clear();
		events.unregisterAll();
	}
}
