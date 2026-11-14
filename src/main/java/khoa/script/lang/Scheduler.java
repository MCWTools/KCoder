package khoa.script.lang;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/** Resumes script coroutines when their wait is over. Called by the game on every client tick and every rendered frame. */
public final class Scheduler {
	/** A timed wait may resume the same script several times per pump to catch up with a lagging frame. */
	private static final int MAX_RESUMES_PER_PUMP = 48;

	private final Engine engine;
	private final List<ScriptTask> tasks = new CopyOnWriteArrayList<>();
	public LongSupplier clock = System::nanoTime;
	private volatile long tick;
	private volatile long frame;

	Scheduler(Engine engine) {
		this.engine = engine;
	}

	private volatile double delta = 0.016;
	private long lastFrameNanos;

	public double deltaSeconds() {
		return delta;
	}

	public long now() {
		return clock.getAsLong();
	}

	public long tick() {
		return tick;
	}

	public long frame() {
		return frame;
	}

	void add(ScriptTask t) {
		tasks.add(t);
	}

	void remove(ScriptTask t) {
		tasks.remove(t);
	}

	public List<ScriptTask> tasks() {
		return tasks;
	}

	public void onTick() {
		tick++;
		engine.events.fire("on_tick", java.util.Map.of());
		pump();
	}

	public void onFrame() {
		frame++;
		long n = now();
		if (lastFrameNanos != 0) {
			delta = Math.min(1.0, (n - lastFrameNanos) / 1e9);
		}
		lastFrameNanos = n;
		engine.events.timers(now());
		engine.events.fire("on_frame", java.util.Map.of());
		pump();
	}

	/** Runs every task that is ready. Also usable directly in tests with a fake clock. */
	public void pump() {
		long now = now();
		long t = tick;
		long f = frame;
		for (ScriptTask task : tasks) {
			if (task.co.killed && !task.co.done) {
				task.co.kill();
			}
			if (task.co.done || task.paused) {
				if (task.co.done) {
					finish(task);
				}
				continue;
			}
			for (int n = 0; n < MAX_RESUMES_PER_PUMP && !task.co.done && !task.paused && task.ready(now, t, f); n++) {
				task.co.resume();
				if (task.co.waitKind != Coroutine.Wait.TIME) {
					break;
				}
				now = now();
			}
			if (task.co.done) {
				finish(task);
			}
		}
	}

	private void finish(ScriptTask task) {
		if (!tasks.remove(task)) {
			return;
		}
		task.finishedAt = System.nanoTime();
		Session s = task.session;
		if (task.stopped) {
			return;
		}
		if (task.error != null) {
			engine.console.error(s, task.error);
		} else if (task.co.failure != null) {
			Throwable f = task.co.failure;
			engine.console.error(s, "Internal error in " + task.name + ": " + f);
			if (f instanceof StackOverflowError) {
				engine.console.error(s, "(stack overflow: recursion too deep)");
			}
		}
		s.tasks.remove(task);
		if (engine.onTaskFinished != null) {
			engine.onTaskFinished.accept(task);
		}
	}
}
