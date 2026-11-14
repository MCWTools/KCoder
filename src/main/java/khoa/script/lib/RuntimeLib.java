package khoa.script.lib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import khoa.script.lang.Args;
import khoa.script.lang.Interp;
import khoa.script.lang.KcdError;
import khoa.script.lang.Paths;
import khoa.script.lang.Registry;
import khoa.script.lang.ScriptTask;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** Output, timing, timers, debugging and the sandboxed file API. */
public final class RuntimeLib {
	private static final Registry R = Registry.get();
	private static final long MAX_FILE = 8L << 20;

	private RuntimeLib() {
	}

	public static void register() {
		output();
		timing();
		debug();
		files();
		handlers();
	}

	/** Members of the value that  h = box.On_Hit:  (or  h = on_chat():) gives back. */
	private static void handlers() {
		R.typeDoc("handler", "An event handler made with  h = box.On_Hit:  or  h = on_chat():  . h.cancel() removes it, h.pause() / h.resume() switch it off and on, h.paused, h.active.");
		R.method("handler", "cancel()", "Removes the handler for good (a run that is already going finishes).", (in, s, g) -> {
			((khoa.script.lang.EventBus.Handler) s).cancel();
			return null;
		});
		R.method("handler", "pause()", "The handler stays registered but does not fire until resume().", (in, s, g) -> {
			((khoa.script.lang.EventBus.Handler) s).paused = true;
			return null;
		});
		R.method("handler", "resume()", "Lets a paused handler fire again.", (in, s, g) -> {
			((khoa.script.lang.EventBus.Handler) s).paused = false;
			return null;
		});
		R.prop("handler", "paused", "boolean", "True between pause() and resume().", (in, s, g) -> ((khoa.script.lang.EventBus.Handler) s).paused);
		R.prop("handler", "active", "boolean", "False after cancel() or when the script stopped.", (in, s, g) -> {
			var h = (khoa.script.lang.EventBus.Handler) s;
			return !h.cancelled && !h.session.stopped;
		});
	}

	private static String join(Args a, int i) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Object o : a.list(i)) {
			if (!first) {
				sb.append(' ');
			}
			first = false;
			sb.append(Val.str(o));
		}
		return sb.toString();
	}

	private static void output() {
		String c = "output";
		R.def(c, "print(...values:any)", "Writes the values (separated by spaces) to the IDE log.", a -> {
			a.in.engine.console.print(a.in.session, join(a, 0));
			return null;
		}).example("print(\"yaw:\", yaw, \"pitch:\", pitch)");
		R.def(c, "log(...values:any)", "Writes an info line to the IDE log.", a -> {
			a.in.engine.console.info(a.in.session, join(a, 0));
			return null;
		});
		R.def(c, "warn(...values:any)", "Writes a warning to the IDE log.", a -> {
			a.in.engine.console.warn(a.in.session, join(a, 0), a.in.file, a.in.curLine);
			return null;
		});
		R.def(c, "error(...values:any)", "Writes an error line to the IDE log (does not stop the script; use raise() for that).", a -> {
			a.in.engine.console.add(khoa.script.lang.LogConsole.Level.ERROR, a.in.session.id, join(a, 0), a.in.file, a.in.curLine);
			return null;
		});
		R.def(c, "raise(message:any)", "Throws an error that stops the script unless a try/catch handles it.", a -> {
			throw new KcdError(Val.str(a.obj(0)), 0);
		});
		R.def(c, "clear_console()", "Clears the IDE log.", a -> {
			a.in.engine.console.clear();
			return null;
		});
	}

	private static void timing() {
		String c = "timing";
		R.def(c, "wait(seconds:number)", "Pauses this script (not the game) for some seconds. Real time, measured with System.nanoTime.", a -> {
			a.in.waitSeconds(a.num(0));
			return null;
		}).suspends().example("wait(0.5)");
		R.def(c, "wait_ms(milliseconds:number)", "Pauses this script for some milliseconds.", a -> {
			a.in.waitSeconds(a.num(0) / 1000.0);
			return null;
		}).suspends();
		R.def(c, "wait_tick(ticks?:number=1)", "Pauses this script for some game ticks (20 per second).", a -> {
			a.in.waitTicks((long) a.num(0, 1));
			return null;
		}).suspends().example("wait_tick(1)");
		R.def(c, "wait_frame(frames?:number=1)", "Pauses this script for some rendered frames.", a -> {
			a.in.waitFrames((long) a.num(0, 1));
			return null;
		}).suspends();
		R.def(c, "wait_until(condition:function, timeout?:number) -> boolean", "Waits until condition() is true, checking every frame. Returns false if the timeout (seconds) ran out first.", a -> {
			long deadline = a.has(1) ? a.in.engine.scheduler.now() + (long) (a.num(1) * 1e9) : Long.MAX_VALUE;
			while (true) {
				if (Val.truthy(a.call(0))) {
					return true;
				}
				if (a.in.engine.scheduler.now() >= deadline) {
					return false;
				}
				a.in.waitFrames(1);
			}
		}).suspends().example("wait_until(lambda: is_on_ground(), 5)");
		R.def(c, "get_time() -> number", "Seconds from a steady clock (use differences between two calls).", a -> a.in.engine.scheduler.now() / 1e9);
		R.def(c, "get_delta_time() -> number", "Seconds the last frame took.", a -> a.in.engine.scheduler.deltaSeconds());
		R.def(c, "get_tick() -> number", "Client ticks since the game started.", a -> (double) a.in.engine.scheduler.tick());
		R.def(c, "get_frame() -> number", "Rendered frames since the game started.", a -> (double) a.in.engine.scheduler.frame());
		R.def(c, "get_execution_time() -> number", "Seconds since this script (task) started.", a -> (a.in.engine.scheduler.now() - a.in.task.startedAt) / 1e9);
		R.def(c, "set_timeout(fn:function, seconds:number) -> number", "Calls fn once after some seconds. Returns a timer id for cancel_timer.", a -> {
			Object fn = a.obj(0);
			double sec = a.num(1);
			ScriptTask t = a.in.session.spawn("timeout", a.in.file, in -> {
				in.waitSeconds(sec);
				in.callValue(fn, List.of(), null, null);
			});
			return (double) t.id;
		});
		R.def(c, "set_interval(fn:function, seconds:number) -> number", "Calls fn every few seconds until cancelled. Returns a timer id.", a -> {
			Object fn = a.obj(0);
			double sec = a.num(1);
			if (sec <= 0) {
				throw new KcdError("set_interval(): interval must be > 0", 0);
			}
			ScriptTask t = a.in.session.spawn("interval", a.in.file, in -> {
				while (true) {
					in.waitSeconds(sec);
					in.callValue(fn, List.of(), null, null);
				}
			});
			return (double) t.id;
		});
		R.def(c, "cancel_timer(id:number) -> boolean", "Stops a timer from set_timeout / set_interval.", a -> {
			long id = (long) a.num(0);
			for (ScriptTask t : a.in.session.tasks) {
				if (t.id == id && !t.finished()) {
					t.stopped = true;
					t.co.killed = true;
					return true;
				}
			}
			return false;
		});
	}

	private static void debug() {
		String c = "debug";
		R.def(c, "breakpoint()", "Pauses the script here so the IDE can inspect variables and step.", a -> {
			a.in.engine.debugger.pauseHere(a.in);
			return null;
		}).suspends();
		R.def(c, "profile_start()", "Starts counting calls and time of functions and builtins.", a -> {
			a.in.engine.profiler.reset();
			a.in.engine.profiler.enabled = true;
			return null;
		});
		R.def(c, "profile_stop()", "Stops profiling and prints the report to the log.", a -> {
			a.in.engine.profiler.enabled = false;
			for (String line : a.in.engine.profiler.report()) {
				a.in.engine.console.info(a.in.session, line);
			}
			return null;
		});
		R.def(c, "run_script(path:string) -> boolean", "Starts another .kcd file of this script folder as its own script.", a -> {
			Session s = a.in.session;
			String base = a.in.file.startsWith("<") ? s.baseDir : Paths.dir(a.in.file);
			String path = Paths.resolve(base, a.str(0));
			if (path == null) {
				throw new KcdError("run_script(): path leaves the script folder", 0);
			}
			if (!path.endsWith(".kcd")) {
				path += ".kcd";
			}
			return a.in.engine.start(s, List.of(path));
		});
	}

	// ---------------------------------------------------------------- files
	private static Path resolve(Args a, int i) {
		return resolveIn(a, i, a.in.session.fileRoot, "the script folder");
	}

	private static Path resolveOut(Args a, int i) {
		return resolveIn(a, i, a.in.session.outputRoot, "the output folder");
	}

	private static Path resolveIn(Args a, int i, Path root, String what) {
		if (root == null) {
			throw new KcdError(a.fn.name + "(): file access is not available here", 0);
		}
		String rel = Paths.resolve("", a.str(i));
		if (rel == null) {
			throw new KcdError(a.fn.name + "(): path leaves " + what, 0);
		}
		Path p = root.resolve(rel).normalize();
		if (!p.startsWith(root.normalize())) {
			throw new KcdError(a.fn.name + "(): path leaves " + what, 0);
		}
		return p;
	}

	private static String readText(Args a, Path p) {
		try {
			if (Files.size(p) > MAX_FILE) {
				throw new KcdError(a.fn.name + "(): file is larger than 8 MB", 0);
			}
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new KcdError(a.fn.name + "(): " + e.getMessage(), 0);
		}
	}

	private static void writeText(Args a, Path p, String text, boolean append) {
		try {
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			if (append) {
				Files.writeString(p, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} else {
				Files.writeString(p, text, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new KcdError(a.fn.name + "(): " + e.getMessage(), 0);
		}
	}

	/** Adds the extension when the name has none (export_txt("prices") writes prices.txt). */
	private static Path withExt(Path p, String ext) {
		return p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ext) ? p : p.resolveSibling(p.getFileName() + ext);
	}

	/** Plain-text form of a value: a list gives one line per element, a map gives "key: value" lines, anything else its text. */
	private static String asText(Object v) {
		StringBuilder sb = new StringBuilder();
		if (v instanceof List<?> l) {
			for (Object o : l) {
				sb.append(Val.str(o)).append('\n');
			}
		} else if (v instanceof java.util.Map<?, ?> m) {
			for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
				sb.append(Val.str(e.getKey())).append(": ").append(Val.str(e.getValue())).append('\n');
			}
		} else {
			sb.append(Val.str(v));
		}
		return sb.toString();
	}

	private static void files() {
		String c = "file";
		R.def(c, "read_file(path:string) -> string", "Reads a text file inside this script folder.", a -> {
			Path p = resolve(a, 0);
			try {
				if (Files.size(p) > MAX_FILE) {
					throw new KcdError("read_file(): file is larger than 8 MB", 0);
				}
				return Files.readString(p, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new KcdError("read_file(): " + e.getMessage(), 0);
			}
		}).perm("file");
		R.def(c, "write_file(path:string, content:string)", "Writes (replaces) a text file inside this script folder.", a -> {
			Path p = resolve(a, 0);
			try {
				if (p.getParent() != null) {
					Files.createDirectories(p.getParent());
				}
				Files.writeString(p, a.text(1), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new KcdError("write_file(): " + e.getMessage(), 0);
			}
			return null;
		}).perm("file");
		R.def(c, "append_file(path:string, content:string)", "Appends text to a file inside this script folder.", a -> {
			Path p = resolve(a, 0);
			try {
				if (p.getParent() != null) {
					Files.createDirectories(p.getParent());
				}
				Files.writeString(p, a.text(1), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new KcdError("append_file(): " + e.getMessage(), 0);
			}
			return null;
		}).perm("file");
		R.def(c, "file_exists(path:string) -> boolean", "True if the file or folder exists.", a -> Files.exists(resolve(a, 0))).perm("file");
		R.def(c, "create_folder(path:string)", "Creates a folder (and parents).", a -> {
			try {
				Files.createDirectories(resolve(a, 0));
			} catch (IOException e) {
				throw new KcdError("create_folder(): " + e.getMessage(), 0);
			}
			return null;
		}).perm("file");
		R.def(c, "list_files(path?:string) -> list", "Names in a folder (folders end with /).", a -> {
			Path p = a.has(0) ? resolve(a, 0) : a.in.session.fileRoot;
			if (p == null) {
				throw new KcdError("list_files(): file access is not available here", 0);
			}
			List<Object> out = new ArrayList<>();
			try (Stream<Path> s = Files.list(p)) {
				s.sorted().forEach(x -> out.add(x.getFileName() + (Files.isDirectory(x) ? "/" : "")));
			} catch (IOException e) {
				throw new KcdError("list_files(): " + e.getMessage(), 0);
			}
			return out;
		}).perm("file");
		R.def(c, "delete_file(path:string) -> boolean", "Deletes a file or an empty folder.", a -> {
			try {
				return Files.deleteIfExists(resolve(a, 0));
			} catch (IOException e) {
				throw new KcdError("delete_file(): " + e.getMessage(), 0);
			}
		}).perm("file");
		R.def(c, "rename_file(old:string, new:string)", "Renames or moves a file inside this script folder.", a -> {
			try {
				Files.move(resolve(a, 0), resolve(a, 1), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new KcdError("rename_file(): " + e.getMessage(), 0);
			}
			return null;
		}).perm("file");
		// ---------------------------------------------------------------- output files (kcoder/output/<script folder>/)
		String o = "output file";
		R.def(o, "get_output_path() -> string", "Full path of this script folder's output folder (kcoder/output/<script folder>/).", a -> {
			if (a.in.session.outputRoot == null) {
				throw new KcdError("get_output_path(): output files are not available here", 0);
			}
			return a.in.session.outputRoot.toString();
		}).perm("file");
		R.def(o, "write_output(path:string, content:any)", "Writes (replaces) a text file in kcoder/output/<script folder>/. Parent folders are created.", a -> {
			writeText(a, resolveOut(a, 0), a.text(1), false);
			return null;
		}).perm("file");
		R.def(o, "append_output(path:string, content:any)", "Appends text to an output file (created if missing). Add a line break yourself, or use append_output_line.", a -> {
			writeText(a, resolveOut(a, 0), a.text(1), true);
			return null;
		}).perm("file");
		R.def(o, "append_output_line(path:string, line:any)", "Appends one line to an output file; a line break is added first when the file does not end with one.", a -> {
			Path p = resolveOut(a, 0);
			String old = Files.exists(p) ? readText(a, p) : "";
			writeText(a, p, (old.isEmpty() || old.endsWith("\n") ? "" : "\n") + a.text(1) + "\n", true);
			return null;
		}).perm("file");
		R.def(o, "insert_output(path:string, line:number, content:any)", "Inserts text as new line(s) BEFORE line number `line` (1 = top). A line past the end appends. Creates the file if missing.", a -> {
			Path p = resolveOut(a, 0);
			int at = a.intv(1);
			if (at < 1) {
				throw new KcdError("insert_output(): line must be 1 or more", 0);
			}
			List<String> lines = new ArrayList<>(Files.exists(p) ? java.util.Arrays.asList(readText(a, p).split("\\R", -1)) : List.of());
			if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
				lines.remove(lines.size() - 1); // the trailing line break is not an empty line
			}
			String[] add = a.text(2).split("\\R", -1);
			int idx = Math.min(at - 1, lines.size());
			for (int k = 0; k < add.length; k++) {
				lines.add(idx + k, add[k]);
			}
			writeText(a, p, String.join("\n", lines) + "\n", false);
			return null;
		}).perm("file");
		R.def(o, "read_output(path:string) -> string", "Reads a text file from the output folder.", a -> readText(a, resolveOut(a, 0))).perm("file");
		R.def(o, "read_output_lines(path:string) -> list<string>", "Reads an output file as a list of lines (an empty list if the file does not exist).", a -> {
			Path p = resolveOut(a, 0);
			List<Object> out = new ArrayList<>();
			if (Files.exists(p)) {
				for (String l : readText(a, p).split("\\R")) {
					out.add(l);
				}
			}
			return out;
		}).perm("file");
		R.def(o, "output_exists(path:string) -> boolean", "True if the file or folder exists in the output folder.", a -> Files.exists(resolveOut(a, 0))).perm("file");
		R.def(o, "list_output(path?:string) -> list", "Names in the output folder (folders end with /).", a -> {
			Path p = a.has(0) ? resolveOut(a, 0) : a.in.session.outputRoot;
			if (p == null) {
				throw new KcdError("list_output(): output files are not available here", 0);
			}
			List<Object> out = new ArrayList<>();
			if (Files.isDirectory(p)) {
				try (Stream<Path> s = Files.list(p)) {
					s.sorted().forEach(x -> out.add(x.getFileName() + (Files.isDirectory(x) ? "/" : "")));
				} catch (IOException e) {
					throw new KcdError("list_output(): " + e.getMessage(), 0);
				}
			}
			return out;
		}).perm("file");
		R.def(o, "delete_output(path:string) -> boolean", "Deletes a file or an empty folder from the output folder.", a -> {
			try {
				return Files.deleteIfExists(resolveOut(a, 0));
			} catch (IOException e) {
				throw new KcdError("delete_output(): " + e.getMessage(), 0);
			}
		}).perm("file");
		R.def(o, "export_txt(path:string, value:any)", "Saves a value as a .txt file in the output folder (.txt is added if missing). A list becomes one line per item, a map becomes key: value lines.", a -> {
			writeText(a, withExt(resolveOut(a, 0), ".txt"), asText(a.obj(1)), false);
			return null;
		}).perm("file");
		R.def(o, "export_json(path:string, value:any, pretty?:boolean=true)", "Saves a value as a .json file in the output folder (.json is added if missing).", a -> {
			writeText(a, withExt(resolveOut(a, 0), ".json"), JsonLib.stringify(a.obj(1), a.bool(2, true)) + "\n", false);
			return null;
		}).perm("file");
		R.def(o, "import_json(path:string) -> any", "Reads a .json file from the output folder and returns the value (nil if the file does not exist).", a -> {
			Path p = withExt(resolveOut(a, 0), ".json");
			return Files.exists(p) ? JsonLib.parse(readText(a, p)) : null;
		}).perm("file");
	}

	/** Used by tests / the IDE to avoid an unused import warning for Interp in docs. */
	static Class<?> interpClass() {
		return Interp.class;
	}
}
