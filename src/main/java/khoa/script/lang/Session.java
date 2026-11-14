package khoa.script.lang;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One running script folder (or the console). It owns the source snapshot taken when it was started, its module cache, its tasks and its event handlers,
 * so editing files while it runs changes nothing until it is stopped and started again.
 */
public final class Session {
	public interface Sources {
		/** Source text or null if the file does not exist. */
		String read(String path);
	}

	private static final AtomicLong IDS = new AtomicLong();

	public final Engine engine;
	public final String id;
	public final Sources sources;
	/** Directory used to resolve imports from code that has no file (the console). */
	public String baseDir = "";
	public final Map<String, Val.KModule> modules = new HashMap<>();
	private final Map<String, String> moduleSource = new HashMap<>();
	private final java.util.Set<String> loading = new java.util.HashSet<>();
	public final List<ScriptTask> tasks = new CopyOnWriteArrayList<>();
	public volatile boolean stopped;
	/** Cleanup that runs when the session stops (UI elements, ...). */
	public final List<Runnable> onStop = new CopyOnWriteArrayList<>();
	/** Root directory for the file API (read_file ...). Null = no file access. */
	public java.nio.file.Path fileRoot;
	/** Root directory for the output API (write_output ...): kcoder/output/<script folder>/. Null = not available. */
	public java.nio.file.Path outputRoot;
	public final long startedAt = System.currentTimeMillis();
	/** Persistent variables (console sessions). */
	public final Scope globals = new Scope(null, "console");

	public Session(Engine engine, String id, Sources sources) {
		this.engine = engine;
		this.id = id;
		this.sources = sources;
	}

	/** Creates a task; it starts running at the next scheduler pump. */
	public ScriptTask spawn(String name, String file, Consumer<Interp> body) {
		ScriptTask t = new ScriptTask(IDS.incrementAndGet(), name, this);
		Coroutine co = new Coroutine(name, () -> {
			try {
				body.accept(t.interp);
			} catch (KcdError e) {
				t.error = e;
			}
		});
		Interp in = new Interp(engine, this, co, file);
		in.task = t;
		t.co = co;
		t.interp = in;
		tasks.add(t);
		engine.scheduler.add(t);
		return t;
	}

	/** Parses a file's source (throws KcdError with the file name on a syntax error). */
	public List<Node> parse(String file, String src) {
		return Parser.parse(src, file);
	}

	public Val.KModule loadModule(Interp in, String fromFile, String ref) {
		String base = fromFile.startsWith("<") ? baseDir : Paths.dir(fromFile);
		String path = Paths.resolve(base, ref);
		if (path == null) {
			throw new KcdError("Module path '" + ref + "' leaves the script folder", 0);
		}
		if (!path.endsWith(".kcd")) {
			path += ".kcd";
		}
		Val.KModule cached = modules.get(path);
		String src = sources.read(path);
		if (src == null) {
			KcdError e = new KcdError("Module not found: " + path, 0);
			e.withHint("paths are relative to the importing file, e.g. implement ./features/x.kcd");
			throw e;
		}
		if (cached != null && src.equals(moduleSource.get(path))) {
			if (!cached.loaded && loading.contains(path)) {
				throw new KcdError("Circular implement of " + path, 0);
			}
			return cached;
		}
		Val.KModule mod = new Val.KModule(Paths.stem(path), path, new Scope(null, Paths.stem(path)));
		modules.put(path, mod);
		moduleSource.put(path, src);
		List<Node> prog = parse(path, src);
		loading.add(path);
		try {
			in.runModule(prog, mod.scope, path);
			mod.loaded = true;
		} catch (RuntimeException | Error e) {
			modules.remove(path);
			throw e;
		} finally {
			loading.remove(path);
		}
		return mod;
	}

	public boolean busy() {
		for (ScriptTask t : tasks) {
			if (!t.finished()) {
				return true;
			}
		}
		return false;
	}

	/** Kills every task and removes the event handlers. */
	public void stop() {
		stopped = true;
		for (Runnable r : onStop) {
			try {
				r.run();
			} catch (RuntimeException e) {
				engine.console.error(this, "cleanup failed: " + e.getMessage());
			}
		}
		onStop.clear();
		engine.events.unregister(this);
		for (ScriptTask t : tasks) {
			if (!t.finished()) {
				t.stopped = true;
				t.paused = false;
				t.co.kill();
			}
			engine.scheduler.remove(t);
		}
		tasks.clear();
	}
}
