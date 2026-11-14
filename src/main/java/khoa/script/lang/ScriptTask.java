package khoa.script.lang;

/** One running coroutine: a script file, an event handler invocation or a console submission. */
public final class ScriptTask {
	public enum Status {
		RUNNING, PAUSED, DONE, FAILED, STOPPED
	}

	public final long id;
	public final String name;
	public final Session session;
	public Coroutine co;
	public Interp interp;
	public KcdError error;
	public Throwable internal;
	public final long startedAt = System.nanoTime();
	public long finishedAt;

	public volatile boolean paused;
	public volatile String pausedFile;
	public volatile int pausedLine;
	public Debugger.Mode stepMode = Debugger.Mode.RUN;
	public int stepDepth;
	/** Set when the task was killed by Stop (so it is not reported as an error). */
	public boolean stopped;
	/** Result of a console expression, if any. */
	public volatile Object result;

	ScriptTask(long id, String name, Session session) {
		this.id = id;
		this.name = name;
		this.session = session;
	}

	public boolean finished() {
		return co.done;
	}

	public Status status() {
		if (!co.done) {
			return paused ? Status.PAUSED : Status.RUNNING;
		}
		if (stopped) {
			return Status.STOPPED;
		}
		return error != null || internal != null ? Status.FAILED : Status.DONE;
	}

	boolean ready(long now, long tick, long frame) {
		return switch (co.waitKind) {
			case NONE -> true;
			case TIME -> now >= co.waitTarget;
			case TICKS -> tick >= co.waitTarget;
			case FRAMES -> frame >= co.waitTarget;
		};
	}

	void pause(String file, int line, int depth) {
		pausedFile = file;
		pausedLine = line;
		paused = true;
	}

	/** Continue after a breakpoint, or step. */
	public void resumeDebug(Debugger.Mode mode) {
		stepMode = mode;
		stepDepth = interp.frames.size();
		paused = false;
	}
}
