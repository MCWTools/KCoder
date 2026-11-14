package khoa.script.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import khoa.script.lang.Engine;
import khoa.script.lang.KcdError;
import khoa.script.lang.LogConsole.Level;
import khoa.script.lang.Node;
import khoa.script.lang.Parser;
import khoa.script.lang.Paths;
import khoa.script.lang.Session;

/**
 * The console: runs any amount of KCD code (several lines, pasted blocks) in a session that keeps its variables between runs,
 * plus a few shell-like commands: cd, ls, pwd, clear, run, stop, help. {@code ./file.kcd:function()} calls a function of a module file.
 */
public final class ConsoleRunner {
	public static final String ID = "console";
	private static final Pattern FILE_CALL = Pattern.compile("^(\\s*)((?:\\.{1,2}/|/)?[A-Za-z0-9_ .\\-/]*?\\.kcd):(.+)$");
	private static final Pattern ASSIGN = Pattern.compile("^[A-Za-z_]\\w*(\\s*,\\s*[A-Za-z_]\\w*)*\\s*(=|\\+=|-=|\\*=|/=)(?!=).*");

	public static final List<String> HISTORY = new ArrayList<>();
	private static String cwd = "";
	private static Session session;

	private ConsoleRunner() {
	}

	public static String cwd() {
		return cwd;
	}

	public static String prompt() {
		return "kcd/" + cwd + (cwd.isEmpty() ? "" : "/") + " >";
	}

	private static Engine engine() {
		return Kcd.ENGINE;
	}

	private static Session session() {
		if (session == null || session.stopped) {
			session = engine().session(ID, path -> {
				Path p = Kcd.ROOT.resolve(path).normalize();
				if (!p.startsWith(Kcd.ROOT) || !Files.isRegularFile(p)) {
					return null;
				}
				try {
					return Files.readString(p, StandardCharsets.UTF_8);
				} catch (IOException e) {
					return null;
				}
			});
			session.fileRoot = Kcd.ROOT;
			session.outputRoot = Kcd.OUTPUT.resolve("console");
			session.baseDir = cwd;
		}
		return session;
	}

	private static void out(Level level, String msg) {
		engine().console.add(level, ID, msg, null, 0);
	}

	/** Stops everything the console started (tasks, handlers) and forgets its variables. */
	public static void reset() {
		engine().remove(ID);
		session = null;
		out(Level.SYSTEM, "Console reset: tasks stopped, variables cleared");
	}

	public static void submit(String code) {
		String text = code.strip();
		if (text.isEmpty()) {
			return;
		}
		if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(text)) {
			HISTORY.add(text);
		}
		String[] shown = text.split("\n", -1);
		out(Level.INFO, "> " + shown[0] + (shown.length > 1 ? "   (+" + (shown.length - 1) + " more lines)" : ""));
		if (shown.length == 1 && command(text)) {
			return;
		}
		String src = rewrite(text);
		Session s = session();
		s.baseDir = cwd;
		List<Node> prog;
		try {
			prog = parse(s, wrapResult(src));
		} catch (KcdError first) {
			try {
				prog = parse(s, src);
			} catch (KcdError e) {
				out(Level.ERROR, "Syntax error: " + e.describe());
				return;
			}
		}
		List<Node> program = prog;
		s.spawn("console", "<console>", in -> in.runProgram(program, s.globals, "<console>"));
	}

	private static List<Node> parse(Session s, String src) {
		return Parser.parse(src, "<console>");
	}

	/** {@code ./macenearest.kcd:run()} becomes an implement + call. */
	static String rewrite(String code) {
		StringBuilder sb = new StringBuilder();
		for (String line : code.split("\n", -1)) {
			Matcher m = FILE_CALL.matcher(line);
			if (m.matches()) {
				String ind = m.group(1);
				String path = m.group(2).trim();
				if (!path.startsWith("./") && !path.startsWith("../") && !path.startsWith("/")) {
					path = "./" + path;
				}
				sb.append(ind).append("__mod = implement ").append(path).append('\n');
				sb.append(ind).append("__mod:").append(m.group(3).trim()).append('\n');
			} else {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	/** If the last line is a plain expression, print its value (like a REPL). */
	private static String wrapResult(String src) {
		String[] lines = src.stripTrailing().split("\n", -1);
		String last = lines[lines.length - 1];
		if (last.isBlank() || Character.isWhitespace(last.charAt(0)) || last.startsWith("#") || ASSIGN.matcher(last).matches()) {
			throw new KcdError("no result", 0);
		}
		String head = last.split("[\\s(:]", 2)[0];
		if (Parser.isKeyword(head) || head.startsWith("on_") || last.endsWith(":")) {
			throw new KcdError("no result", 0);
		}
		lines[lines.length - 1] = "__last = " + last;
		return String.join("\n", lines) + "\nif __last != nil:\n    print(__last)\n";
	}

	// ------------------------------------------------------------ shell commands
	private static boolean command(String text) {
		String[] p = text.split("\\s+", 2);
		String arg = p.length > 1 ? p[1].trim() : "";
		switch (p[0]) {
			case "clear", "cls" -> {
				engine().console.clear();
				return true;
			}
			case "pwd" -> {
				out(Level.INFO, "kcd/" + cwd);
				return true;
			}
			case "cd" -> {
				cd(arg);
				return true;
			}
			case "ls", "dir" -> {
				ls(arg);
				return true;
			}
			case "help", "?" -> {
				help();
				return true;
			}
			case "run" -> {
				if (arg.isEmpty() && !cwd.isEmpty()) {
					arg = cwd.split("/")[0];
				}
				if (!ScriptFolders.list().contains(arg)) {
					out(Level.ERROR, "No script folder named '" + arg + "'");
				} else {
					ScriptFolders.run(arg);
				}
				return true;
			}
			case "stop" -> {
				if (arg.isEmpty()) {
					ScriptFolders.stopAll();
				} else if (ScriptFolders.list().contains(arg)) {
					ScriptFolders.stop(arg);
				} else {
					out(Level.ERROR, "No script folder named '" + arg + "'");
				}
				return true;
			}
			case "reset" -> {
				reset();
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private static void cd(String arg) {
		if (arg.isEmpty() || arg.equals("/") || arg.equals("~")) {
			cwd = "";
			return;
		}
		String target = arg.startsWith("/") ? Paths.resolve("", arg.substring(1)) : Paths.resolve(cwd, arg);
		if (target == null) {
			out(Level.ERROR, "cd: that path leaves the kcd folder");
			return;
		}
		while (target.endsWith("/")) {
			target = target.substring(0, target.length() - 1);
		}
		if (target.equals(".")) {
			target = "";
		}
		if (!Files.isDirectory(Kcd.ROOT.resolve(target))) {
			out(Level.ERROR, "cd: no such folder: " + arg);
			return;
		}
		cwd = target;
		if (session != null) {
			session.baseDir = cwd;
		}
	}

	private static void ls(String arg) {
		String target = arg.isEmpty() ? cwd : Paths.resolve(cwd, arg);
		if (target == null || !Files.isDirectory(Kcd.ROOT.resolve(target))) {
			out(Level.ERROR, "ls: no such folder");
			return;
		}
		List<String> names = new ArrayList<>();
		try (Stream<Path> s = Files.list(Kcd.ROOT.resolve(target))) {
			s.forEach(p -> {
				String n = p.getFileName().toString();
				if (n.startsWith(".")) {
					return;
				}
				boolean dir = Files.isDirectory(p);
				boolean sf = dir && Files.exists(p.resolve(ScriptFolders.MARKER));
				names.add((sf ? "[script] " : dir ? "[dir]    " : "         ") + n + (dir ? "/" : ""));
			});
		} catch (IOException e) {
			out(Level.ERROR, "ls: " + e.getMessage());
			return;
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		out(Level.INFO, names.isEmpty() ? "(empty)" : String.join("\n", names));
	}

	private static void help() {
		out(Level.INFO, """
			Console commands:
			  cd ./Folder/sub/   change folder (relative to the current one, / = kcd root)
			  ls  pwd  clear     list, show path, clear the log
			  run [folder]  stop [folder]   start / stop a script folder
			  reset              stop console tasks and forget variables
			Code:
			  Type KCD code. Enter runs a one-line command; a line ending with ':' or pasted multi-line code keeps editing - Ctrl+Enter runs it.
			  ./macenearest.kcd:run()    calls function run() of that file (relative to the current folder)
			  Variables stay between runs:  x = 5   then   print(x * 2)
			  Ctrl+Space = suggestions, Up/Down on one-line input = history""".stripIndent());
	}

	/** Names of all .kcd files below the current folder (suggestions for implement). */
	public static List<String> filesBelowCwd() {
		List<String> out = new ArrayList<>();
		Path base = Kcd.ROOT.resolve(cwd);
		try (Stream<Path> s = Files.walk(base, 6)) {
			s.filter(p -> p.toString().endsWith(".kcd")).forEach(p -> out.add(Kcd.ROOT.relativize(p).toString().replace('\\', '/')));
		} catch (IOException e) {
			// ignore
		}
		return out;
	}
}
