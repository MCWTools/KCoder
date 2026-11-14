package khoa.script.lang;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * A script runs on its own thread, but strictly hand-in-hand with the game thread: only one of them runs at a time, so a script behaves like a
 * coroutine. wait() parks the script thread and returns control to the game; the scheduler resumes it later. Game calls are executed on the
 * game thread (onMain), never on the script thread.
 */
public final class Coroutine {
	public enum Wait {
		NONE, TIME, TICKS, FRAMES
	}

	/** Thrown inside the script thread to unwind it when the script is stopped. Must never be caught by script code. */
	public static final class Kill extends RuntimeException {
		public Kill() {
			super("killed", null, false, false);
		}
	}

	private final Semaphore toCo = new Semaphore(0);
	private final Semaphore toMain = new Semaphore(0);
	private final Runnable body;
	private final String name;
	private Thread thread;
	private volatile boolean started;
	public volatile boolean done;
	public volatile boolean killed;
	public Throwable failure;

	public Wait waitKind = Wait.NONE;
	public long waitTarget;
	/** End of the previous timed wait; lets a lagging script catch up instead of drifting. 0 = none. */
	public long timeline;

	private Callable<?> pending;
	private Object pendingResult;
	private Throwable pendingError;

	public Coroutine(String name, Runnable body) {
		this.name = name;
		this.body = body;
	}

	public boolean isStarted() {
		return started;
	}

	/** Game thread: run the script until its next wait (or until it finishes). */
	public void resume() {
		if (done) {
			return;
		}
		if (!started) {
			started = true;
			thread = new Thread(null, this::threadMain, "kcd-" + name, 24L << 20);
			thread.setDaemon(true);
			thread.start();
		}
		toCo.release();
		while (true) {
			toMain.acquireUninterruptibly();
			Callable<?> task = pending;
			if (task == null) {
				return;
			}
			pending = null;
			try {
				pendingResult = task.call();
				pendingError = null;
			} catch (Throwable t) {
				pendingResult = null;
				pendingError = t;
			}
			toCo.release();
		}
	}

	private void threadMain() {
		toCo.acquireUninterruptibly();
		try {
			if (!killed) {
				body.run();
			}
		} catch (Kill ignored) {
			// stopped
		} catch (Throwable t) {
			failure = t;
		} finally {
			done = true;
			toMain.release();
		}
	}

	/** Script thread: give control back to the game until resumed. */
	public void yieldNow() {
		pending = null;
		toMain.release();
		toCo.acquireUninterruptibly();
		if (killed) {
			throw new Kill();
		}
	}

	/** Script thread: run a task on the game thread and wait for its result. */
	@SuppressWarnings("unchecked")
	public <T> T onMain(Callable<T> task) {
		pending = task;
		toMain.release();
		toCo.acquireUninterruptibly();
		if (killed) {
			throw new Kill();
		}
		Throwable err = pendingError;
		if (err != null) {
			pendingError = null;
			if (err instanceof RuntimeException re) {
				throw re;
			}
			if (err instanceof Error e) {
				throw e;
			}
			throw new RuntimeException(err);
		}
		return (T) pendingResult;
	}

	/** Game thread: stop the script. Safe to call when it is parked in a wait or has not started. */
	public void kill() {
		if (done) {
			return;
		}
		killed = true;
		if (!started) {
			done = true;
			return;
		}
		resume();
	}
}
