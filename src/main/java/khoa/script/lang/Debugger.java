package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Breakpoints and stepping. Costs nothing while no breakpoint is set (active == false). */
public final class Debugger {
	public enum Mode {
		RUN, INTO, OVER, OUT
	}

	/** Keys look like "SessionId|path/to/file.kcd:12". */
	private final Set<String> breakpoints = ConcurrentHashMap.newKeySet();
	public volatile boolean active;

	public static String key(String session, String file, int line) {
		return session + "|" + file + ":" + line;
	}

	public void toggle(String session, String file, int line) {
		String k = key(session, file, line);
		if (!breakpoints.remove(k)) {
			breakpoints.add(k);
		}
		active = !breakpoints.isEmpty();
	}

	public boolean has(String session, String file, int line) {
		return breakpoints.contains(key(session, file, line));
	}

	public void clearAll() {
		breakpoints.clear();
		active = false;
	}

	void beforeStatement(Interp in, Node n, Scope sc) {
		ScriptTask t = in.task;
		if (t == null || in.co == null) {
			return;
		}
		boolean stop = breakpoints.contains(key(in.session.id, in.file, n.line()));
		if (!stop && t.stepMode != Mode.RUN) {
			int depth = in.frames.size();
			stop = switch (t.stepMode) {
				case INTO -> true;
				case OVER -> depth <= t.stepDepth;
				case OUT -> depth < t.stepDepth;
				default -> false;
			};
		}
		if (stop) {
			pauseHere(in);
		}
	}

	/** Pauses the task at the current statement until the IDE resumes it. */
	public void pauseHere(Interp in) {
		ScriptTask t = in.task;
		if (t == null || in.co == null) {
			return;
		}
		t.pause(in.file, in.curLine, in.frames.size());
		in.co.waitKind = Coroutine.Wait.FRAMES;
		in.co.waitTarget = 0;
		in.co.yieldNow();
		in.co.waitKind = Coroutine.Wait.NONE;
	}

	/** Variables visible at the pause point, innermost frame first. */
	public static List<Map.Entry<String, String>> variables(ScriptTask t) {
		List<Map.Entry<String, String>> out = new ArrayList<>();
		Interp in = t.interp;
		if (in == null) {
			return out;
		}
		List<Interp.Frame> fr = new ArrayList<>(in.frames);
		for (int i = fr.size() - 1; i >= 0; i--) {
			Interp.Frame f = fr.get(i);
			Map<String, String> sorted = new TreeMap<>();
			for (Scope s = f.scope(); s != null; s = s.parent) {
				for (Map.Entry<String, Object> e : s.vars.entrySet()) {
					if (!(e.getValue() instanceof Val.KFunction) && !(e.getValue() instanceof Val.KModule)) {
						sorted.putIfAbsent(e.getKey(), Val.repr(e.getValue(), 1));
					}
				}
				if (s == f.scope().root() || s.parent == null) {
					break;
				}
			}
			out.add(Map.entry("-- " + f.name() + " (" + f.file() + ")", ""));
			out.addAll(sorted.entrySet());
		}
		return out;
	}
}
