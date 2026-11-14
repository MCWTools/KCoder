package khoa.script.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import khoa.script.ExampleFiles;
import khoa.script.KhoaScript;
import khoa.script.client.game.ActionLib;
import khoa.script.lang.Session;

/**
 * Script folders live in {@code <game>/kcd/<name>/}. A directory is a Script Folder only if it holds the {@code .scriptfolder} marker file;
 * other directories in kcd/ are ignored. Running takes a snapshot of every .kcd file, so editing files while it runs changes nothing.
 */
public final class ScriptFolders {
	public static final String MARKER = ".scriptfolder";
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_ .\\-]{0,39}");

	private ScriptFolders() {
	}

	public static Path dir(String name) {
		return Kcd.ROOT.resolve(name);
	}

	/** Returns an error message or null when the name can be used for a script folder, folder or file. */
	public static String checkName(String name) {
		if (name == null || !NAME.matcher(name).matches()) {
			return "Use letters, digits, space, _ - . (1-40 characters, not starting with a symbol)";
		}
		if (name.endsWith(".") || name.endsWith(" ")) {
			return "The name cannot end with a dot or space";
		}
		return null;
	}

	public static List<String> list() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(Kcd.ROOT)) {
			s.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve(MARKER))).forEach(p -> out.add(p.getFileName().toString()));
		} catch (IOException e) {
			KhoaScript.LOGGER.warn("Cannot list {}", Kcd.ROOT, e);
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	public static int countFiles(String name) {
		try (Stream<Path> s = Files.walk(dir(name))) {
			return (int) s.filter(p -> p.toString().endsWith(".kcd")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- running
	public static Session session(String name) {
		Session s = Kcd.ENGINE.find(name);
		return s != null && !s.stopped ? s : null;
	}

	public static boolean isRunning(String name) {
		return session(name) != null;
	}

	public static int runningCount() {
		int n = 0;
		for (String f : list()) {
			if (isRunning(f)) {
				n++;
			}
		}
		return n;
	}

	/** Snapshot + start. Returns false if a syntax error stopped it (the errors are in the log). */
	public static boolean run(String name) {
		if (isRunning(name)) {
			return true;
		}
		Path root = dir(name);
		Map<String, String> files = new HashMap<>();
		List<String> entries = new ArrayList<>();
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".kcd") && Files.isRegularFile(x))::iterator) {
				String rel = root.relativize(p).toString().replace('\\', '/');
				files.put(rel, Files.readString(p, StandardCharsets.UTF_8));
				if (!rel.contains("/")) {
					entries.add(rel);
				}
			}
		} catch (IOException e) {
			Kcd.ENGINE.console.error(null, "Cannot read script folder " + name + ": " + e.getMessage());
			return false;
		}
		entries.sort(String.CASE_INSENSITIVE_ORDER);
		Kcd.ENGINE.remove(name);
		Session s = Kcd.ENGINE.session(name, files::get);
		s.fileRoot = root;
		s.outputRoot = Kcd.OUTPUT.resolve(name);
		Kcd.ENGINE.console.system("Running " + name + " (" + entries.size() + " entry file(s), " + files.size() + " file(s) in snapshot)");
		if (entries.isEmpty()) {
			Kcd.ENGINE.console.warn(s, "No .kcd file in the root of " + name + " - nothing to run (files inside sub folders only run when implemented)", null, 0);
		}
		if (!Kcd.ENGINE.start(s, entries)) {
			Kcd.ENGINE.remove(name);
			Kcd.ENGINE.console.system("Not started: fix the syntax errors above");
			return false;
		}
		return true;
	}

	public static void stop(String name) {
		if (Kcd.ENGINE.find(name) != null) {
			Kcd.ENGINE.remove(name);
			Kcd.ENGINE.console.system("Stopped " + name);
		}
		if (runningCount() == 0) {
			ActionLib.releaseAll();
		}
	}

	public static void stopAll() {
		for (String f : list()) {
			stop(f);
		}
		Kcd.ENGINE.stopAll();
		ActionLib.releaseAll();
	}

	public static String uptime(String name) {
		Session s = session(name);
		if (s == null) {
			return "";
		}
		long sec = (System.currentTimeMillis() - s.startedAt) / 1000;
		return sec >= 3600 ? "%dh %02dm".formatted(sec / 3600, sec / 60 % 60) : sec >= 60 ? "%dm %02ds".formatted(sec / 60, sec % 60) : sec + "s";
	}

	// ---------------------------------------------------------------- file management
	/** Creates a script folder with a starter main.kcd. Returns an error message or null. */
	public static String create(String name) {
		String bad = checkName(name);
		if (bad != null) {
			return bad;
		}
		Path d = dir(name);
		if (Files.exists(d)) {
			return "'" + name + "' already exists";
		}
		try {
			Files.createDirectories(d);
			Files.writeString(d.resolve(MARKER), "KCoder script folder\n");
			Files.writeString(d.resolve("main.kcd"), "# " + name + "\n# Every .kcd file in the root of a script folder runs when you press Run.\n\nprint(\"Hello from " + name + "!\")\n");
			return null;
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	public static String rename(String from, String to) {
		if (isRunning(from)) {
			return "Stop it before renaming";
		}
		String bad = checkName(to);
		if (bad != null) {
			return bad;
		}
		if (Files.exists(dir(to))) {
			return "'" + to + "' already exists";
		}
		try {
			Files.move(dir(from), dir(to));
			return null;
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	public static String delete(String name) {
		stop(name);
		return deleteTree(dir(name));
	}

	public static String deleteTree(Path p) {
		try (Stream<Path> s = Files.walk(p)) {
			for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
				Files.delete(x);
			}
			return null;
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	/** Creates the "Example" script folder (once) so a fresh install has something to run. */
	public static void ensureExample() {
		Path flag = Kcd.ROOT.resolve(".example_created");
		if (Files.exists(flag)) {
			return;
		}
		try {
			if (!Files.exists(dir("Example"))) {
				Files.createDirectories(dir("Example/features"));
				Files.writeString(dir("Example").resolve(MARKER), "KCoder script folder\n");
				for (Map.Entry<String, String> e : ExampleFiles.FILES.entrySet()) {
					Files.createDirectories(dir("Example").resolve(e.getKey()).getParent());
					Files.writeString(dir("Example").resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
				}
			}
			Files.writeString(flag, "The Example script folder is created once. Delete it if you do not need it.\n");
		} catch (IOException e) {
			KhoaScript.LOGGER.warn("Cannot create the example script folder", e);
		}
	}
}
