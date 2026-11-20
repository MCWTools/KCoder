package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;

import khoa.script.client.game.Keys;
import khoa.script.lang.Builtin;
import khoa.script.lang.Parser;
import khoa.script.lang.Registry;

/** Computes the completion list for the position of the cursor: keywords, functions, variables, events, members, modules, ids, key names. */
public final class Suggest {
	private static final Pattern GUI_SLASH = Pattern.compile("([A-Za-z_]\\w*(?:\\s*(?:[.]|//|\\\\)\\s*[A-Za-z_]\\w*)*)\\s*(?://|\\\\)$");
	private static final Pattern GUI_CHAIN = Pattern.compile("([A-Za-z_]\\w*(?:(?:[.]|//|\\\\)[A-Za-z_]\\w*)*)$");

	public enum Kind {
		KEYWORD('K', 0xFFC586C0, 5),
		SNIPPET('S', 0xFFE5C07B, 6),
		VARIABLE('V', 0xFFFF9F43, 0),
		FUNCTION('f', 0xFF61AFEF, 3),
		USER_FUNCTION('u', 0xFFDCDCAA, 1),
		EVENT('E', 0xFFE06C75, 2),
		MEMBER('p', 0xFF56B6C2, 1),
		METHOD('m', 0xFF4ECDC4, 1),
		MODULE('M', 0xFFE5C07B, 1),
		ID('#', 0xFF4EC9B0, 4),
		KEY('k', 0xFF98C379, 4),
		CONSTANT('c', 0xFFD19A66, 5),
		FILE('F', 0xFF8FA0BC, 4);

		public final char icon;
		public final int color;
		public final int rank;

		Kind(char icon, int color, int rank) {
			this.icon = icon;
			this.color = color;
			this.rank = rank;
		}
	}

	/** One entry. {@code back} = how many characters the cursor moves left after inserting (to sit inside the parentheses). */
	public record Item(Kind kind, String label, String insert, int back, String detail, String doc, String category) {
	}

	public static final class Result {
		public final List<Item> items;
		/** Column where the replaced word starts. */
		public final int start;

		Result(List<Item> items, int start) {
			this.items = items;
			this.start = start;
		}
	}

	private static final Pattern EVENT_ARG = Pattern.compile("^\\s*(?:wait_for\\s*\\([^)]*\\)\\s*:\\s*)?(?:(on_\\w+|chat_detection)\\s*\\(.*\\)|[\\w.\\\\]+[.](\\w+))\\s*:\\s*arg\\s*\\(([^)]*)$");
	private static final Pattern EVENT_CLAUSE = Pattern.compile("^\\s*(?:wait_for\\s*\\([^)]*\\)\\s*:\\s*)?(on_\\w+|chat_detection)\\s*\\(.*\\)\\s*:\\s*\\w+$");
	private static final Pattern ASSIGN = Pattern.compile("^\\s*([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*=(?!=)\\s*(.*)$");
	private static final Pattern FOR = Pattern.compile("^\\s*for\\s+([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s+in\\b");
	private static final Pattern FUNC = Pattern.compile("^\\s*(?:function|def|fn)\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)");
	private static final Pattern DECLARING = Pattern.compile("^\\s*(?:(?:function|def|fn)\\s+\\w*|(?:function|def|fn)\\s+\\w+\\s*\\([^)]*|for\\s+(?:\\w+(?:\\s*,\\s*\\w*)*\\s*)?)$");
	private static final Pattern CALL = Pattern.compile("^([A-Za-z_]\\w*)\\s*\\(");
	private static final Pattern IMPLEMENT = Pattern.compile("^implement\\s+(\\S+)");
	private static final Set<String> EXPR_KEYWORDS = Set.of("not", "and", "or", "in", "is", "lambda", "true", "false", "nil");
	private static final Set<String> STMT_KEYWORDS = new LinkedHashSet<>(List.of("if", "elif", "else", "for", "while", "repeat", "break", "continue", "exit", "return",
		"function", "try", "catch", "finally", "assert", "global", "local", "const", "implement", "pass"));

	/** Hooks filled by the game side. */
	public static Supplier<List<String>> files = List::of;
	public static Function<String, String> source = p -> null;
	/** Directory (relative to the script folder root) of the file being edited. */
	public static String currentDir = "";

	private static Map<String, List<String>> ids;

	private Suggest() {
	}

	private static boolean word(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static Map<String, List<String>> ids() {
		if (ids == null) {
			Map<String, List<String>> m = new LinkedHashMap<>();
			try {
				for (var key : BuiltInRegistries.ENTITY_TYPE.keySet()) {
					m.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath() + "|entity");
				}
				for (var key : BuiltInRegistries.ITEM.keySet()) {
					m.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath() + "|item");
				}
				for (var key : BuiltInRegistries.BLOCK.keySet()) {
					m.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath() + "|block");
				}
				for (var key : BuiltInRegistries.ATTRIBUTE.keySet()) {
					m.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath() + "|attribute");
				}
				for (var key : BuiltInRegistries.SOUND_EVENT.keySet()) {
					m.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key.getPath() + "|sound");
				}
			} catch (RuntimeException e) {
				// registries not ready (menu before the game started): no id suggestions
			}
			ids = m;
		}
		return ids;
	}

	// ------------------------------------------------------------ variables
	/** Names defined in the text with their type (see SuggestTypes). */
	public static Map<String, String> variables(List<String> lines, int upToRow) {
		return SuggestTypes.variables(lines, upToRow);
	}

	public static List<String> userFunctions(List<String> lines) {
		List<String> out = new ArrayList<>();
		for (String ln : lines) {
			Matcher f = FUNC.matcher(ln);
			if (f.find()) {
				out.add(f.group(1) + "(" + f.group(2).trim() + ")");
			}
		}
		return out;
	}

	// ------------------------------------------------------------ main entry
	public static Result compute(List<String> lines, int row, int col, boolean force) {
		String line = lines.get(row);
		col = Math.min(col, line.length());
		int ws = col;
		while (ws > 0 && word(line.charAt(ws - 1))) {
			ws--;
		}
		String prefix = line.substring(ws, col);
		String before = line.substring(0, ws);
		String trimmed = before.trim();
		List<Item> out = new ArrayList<>();
		Registry reg = Registry.get();

		String upToCursor = line.substring(0, col);
		// on_chat():arg(   -> offer the values this event gives; on_chat():ar  -> offer the arg(...) clause
		Matcher argM = EVENT_ARG.matcher(upToCursor);
		if (argM.find()) {
			Registry.EventType ev = argM.group(1) != null ? reg.events.get(argM.group(1)) : reg.events.get(reg.eventByLooseName(argM.group(2)));
			if (ev != null) {
				Set<String> used = new LinkedHashSet<>();
				for (String n : argM.group(3).split(",")) {
					used.add(n.trim());
				}
				for (String v : ev.vars.keySet()) {
					if (!used.contains(v)) {
						out.add(new Item(Kind.VARIABLE, v, v, 0, v + " : " + ev.vars.get(v), "Value given by " + ev.name, "variable"));
					}
				}
				return finish(out, prefix, ws, col, true);
			}
		}
		Matcher clauseM = EVENT_CLAUSE.matcher(upToCursor);
		if (clauseM.find() && reg.events.containsKey(clauseM.group(1)) && !reg.events.get(clauseM.group(1)).vars.isEmpty()) {
			Registry.EventType ev = reg.events.get(clauseM.group(1));
			out.add(new Item(Kind.KEYWORD, "arg", "arg():", 2, "arg(" + String.join(", ", ev.vars.keySet()) + "):", "Name the values this event gives: " + String.join(", ", ev.vars.keySet()), "keyword"));
			return finish(out, prefix, ws, col, true);
		}
		// a comment: nothing to suggest
		boolean inStr = false;
		char quote = 0;
		for (int i = 0; i < upToCursor.length(); i++) {
			char ch = upToCursor.charAt(i);
			if (inStr) {
				if (ch == '\\') {
					i++;
				} else if (ch == quote) {
					inStr = false;
				}
			} else if (ch == '"' || ch == '\'') {
				inStr = true;
				quote = ch;
			} else if (ch == '#') {
				return new Result(List.of(), ws);
			}
		}
		// naming something new (function name, its parameters, the loop variable): suggestions would only get in the way
		if (DECLARING.matcher(upToCursor).find()) {
			return new Result(List.of(), ws);
		}

		// inside a string: key names for hotkey events, file paths for implement
		int quotes = 0;
		for (int i = 0; i < ws; i++) {
			if (line.charAt(i) == '"') {
				quotes++;
			}
		}
		if (quotes % 2 == 1) {
			if (Pattern.compile("implement\\s*\\(\\s*\"[^\"]*$").matcher(before).find()) {
				int s = before.lastIndexOf('"') + 1;
				for (String f : files.get()) {
					String rel = relative(currentDir, f);
					out.add(new Item(Kind.FILE, rel, rel, 0, "module", "Module file " + f, "file"));
				}
				return finish(out, line.substring(s, col), s, col, force);
			}
			if (Pattern.compile("on_(?:key_press|key_release)\\s*\\(\\s*\"[^\"]*$").matcher(before).find()) {
				int s = before.lastIndexOf('"') + 1;
				String typed = line.substring(s, col);
				String mod = typed.contains("+") ? typed.substring(0, typed.lastIndexOf('+') + 1) : "";
				String p2 = typed.substring(mod.length());
				for (String k : Keys.allNames()) {
					out.add(new Item(Kind.KEY, k, mod + k, 0, "key", "Key name for the hotkey. Add modifiers with + : ctrl+shift+" + k, "key"));
				}
				for (String k : List.of("MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE", "MOUSE_4", "MOUSE_5")) {
					out.add(new Item(Kind.KEY, k, mod + k, 0, "mouse button", "Mouse button used as a hotkey.", "key"));
				}
				return finish(out, p2, s + mod.length(), col, force);
			}
			return new Result(List.of(), ws);
		}

		// implement ./path
		Matcher im = Pattern.compile("implement\\s+(\\S*)$").matcher(line.substring(0, col));
		if (im.find()) {
			int s = im.start(1);
			String typed = line.substring(s, col);
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fakeplayer")) {
				out.add(new Item(Kind.FILE, "khoa.fakeplayer", "khoa.fakeplayer", 0, "mod api", "FakePlayer mod: fakeplayer = implement khoa.fakeplayer, then fakeplayer:GetPlayer(\"Name\")", "file"));
			}
			for (String f : files.get()) {
				String rel = relative(currentDir, f);
				out.add(new Item(Kind.FILE, rel, rel, 0, "module", "Module file " + f, "file"));
			}
			return finish(out, typed, s, col, force);
		}

		// GUI path with a slash separator:  ScreenFolder//   ScreenFolder\   ScreenFolder/Panel/
		Matcher gp = GUI_SLASH.matcher(line.substring(0, ws));
		if (gp.find()) {
			String dotted = gp.group(1).replaceAll("\\s*(//|\\\\|[.])\\s*", ".");
			khoa.script.client.ui.UiElement holder = khoa.script.client.guiedit.GuiStore.byPath(dotted);
			if (holder != null) {
				for (khoa.script.client.ui.UiElement c : khoa.script.client.guiedit.GuiStore.childrenOf(holder)) {
					String cn = String.valueOf(c.get("name"));
					out.add(new Item(Kind.CONSTANT, cn, cn, 0, dotted.replace('.', '/') + "/" + cn + " : " + c.kind, "Inside " + dotted + " (GUI editor)", "gui"));
				}
				return finish(out, prefix, ws, col, force);
			}
		}

		// member access:  something.  / something:
		if (ws > 0 && (line.charAt(ws - 1) == '.' || (line.charAt(ws - 1) == ':' && ws > 1 && word(line.charAt(ws - 2))))) {
			char sep = line.charAt(ws - 1);
			int e = ws - 1;
			int s = e;
			while (s > 0 && (word(line.charAt(s - 1)))) {
				s--;
			}
			String recv = line.substring(s, e);
			String recvExpr = SuggestTypes.receiverBefore(line, e);
			Matcher chain = GUI_CHAIN.matcher(line.substring(0, e));
			if (chain.find() && chain.group(1).matches(".*(//|\\\\).*")) {
				String dotted = chain.group(1).replaceAll("\\s*(//|\\\\|[.])\\s*", ".");
				if (khoa.script.client.guiedit.GuiStore.byPath(dotted) != null) {
					recvExpr = dotted;
				}
			}
			if (sep == ':' && ids().containsKey(recv)) {
				for (String id : ids().get(recv)) {
					String[] parts = id.split("\\|");
					out.add(new Item(Kind.ID, parts[0], parts[0], 0, recv + ":" + parts[0], "Minecraft " + parts[1] + " id", parts[1]));
				}
				return finish(out, prefix, ws, col, force);
			}
			Map<String, String> vars = variables(lines, row);
			String type = SuggestTypes.typeOf(recvExpr, vars);
			if (type.equals(FakePlayerSuggest.MODULE)) {
				for (FakePlayerSuggest.Entry en : FakePlayerSuggest.moduleFunctions()) {
					out.add(new Item(Kind.METHOD, en.name(), en.name() + "()", en.signature().contains("name:") ? 1 : 0, en.signature(), en.doc(), recv));
				}
				return finish(out, prefix, ws, col, force);
			}
			if (SuggestTypes.baseOf(type).equals(FakePlayerSuggest.TYPE)) {
				for (FakePlayerSuggest.Entry en : FakePlayerSuggest.botMembers()) {
					out.add(new Item(Kind.METHOD, en.name(), en.name() + "()", en.signature().contains("()") ? 0 : 1, en.signature(), en.doc(), "fakeplayer"));
				}
				return finish(out, prefix, ws, col, force);
			}
			if (type.startsWith("module:")) {
				String path = resolve(currentDir, type.substring(7));
				String src = source.apply(path.endsWith(".kcd") ? path : path + ".kcd");
				if (src != null) {
					List<String> modLines = List.of(src.split("\n", -1));
					for (String uf : userFunctions(modLines)) {
						String name = uf.substring(0, uf.indexOf('('));
						out.add(new Item(Kind.USER_FUNCTION, name, name + "()", uf.endsWith("()") ? 0 : 1, uf, "Function of module " + recv, recv));
					}
					for (Map.Entry<String, String> v : variables(modLines, 0).entrySet()) {
						out.add(new Item(Kind.VARIABLE, v.getKey(), v.getKey(), 0, v.getKey(), "Variable of module " + recv, recv));
					}
				}
				return finish(out, prefix, ws, col, force);
			}
			khoa.script.client.ui.UiElement guiEl = khoa.script.client.guiedit.GuiStore.byPath(recvExpr);
			if (guiEl != null && !vars.containsKey(recvExpr.split("[.]")[0].trim())) {
				for (khoa.script.client.ui.UiElement c : khoa.script.client.guiedit.GuiStore.childrenOf(guiEl)) {
					String cn = String.valueOf(c.get("name"));
					out.add(new Item(Kind.CONSTANT, cn, cn, 0, recvExpr + "." + cn + " : " + c.kind, "Inside " + recvExpr + " (GUI editor)", "gui"));
				}
			}
			if (guiEl != null && sep == '.' && chain.find(0) && line.substring(0, chain.start(1)).isBlank()) {
				for (Registry.EventType ev : reg.events.values()) {
					if (ev.filters.contains("element")) {
						String shown = java.util.Arrays.stream(ev.name.substring(3).split("_")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(java.util.stream.Collectors.joining("_"));
						out.add(new Item(Kind.EVENT, "On_" + shown, "On_" + shown + ":", 0, recvExpr + ".On_" + shown + ":", ev.description, "event"));
					}
				}
			}
			String base = SuggestTypes.baseOf(type);
			if (guiEl == null && !base.isEmpty() && sep == '.' && chain.find(0) && line.substring(0, chain.start(1)).isBlank()) {
				// hitbox.On_Hit: and other events whose first argument is a value type (not a GUI element)
				for (Registry.EventType ev : reg.events.values()) {
					if (!ev.filters.isEmpty() && ev.filters.get(0).equals(base)) {
						String shown = java.util.Arrays.stream(ev.name.substring(3).split("_")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(java.util.stream.Collectors.joining("_"));
						out.add(new Item(Kind.EVENT, "On_" + shown, "On_" + shown + ":", 0, recvExpr + ".On_" + shown + ":", ev.description, "event"));
					}
				}
			}
			if (sep == ':' && !base.isEmpty() && !reg.members.containsKey(base) && !reg.parents.containsKey(base)) {
				return new Result(List.of(), ws);
			}
			List<String> types = new ArrayList<>();
			if (!base.isEmpty() && (reg.members.containsKey(base) || reg.parents.containsKey(base))) {
				types.add(base);
			} else {
				types.addAll(reg.members.keySet());
			}
			Set<String> seen = new LinkedHashSet<>();
			for (String t : types) {
				for (Registry.Member m : reg.allMembers(t)) {
					if (seen.add(m.name + "/" + m.method)) {
						String ins = m.method ? m.name + "()" : m.name;
						out.add(new Item(m.method ? Kind.METHOD : Kind.MEMBER, m.name, ins, m.method && !m.params.isEmpty() ? 1 : 0, m.signature(), m.doc, m.type));
					}
				}
			}
			return finish(out, prefix, ws, col, force);
		}

		// only pop up while a word is being typed (or on request with Ctrl+Space), never after punctuation like ) : ,
		if (!force && prefix.isEmpty()) {
			return new Result(List.of(), ws);
		}

		// the argument under the cursor wants an attribute: offer every attribute id
		SignatureHelp.Sig sig = SignatureHelp.at(lines, row, col);
		if (sig != null && sig.activeType().equals("attribute")) {
			for (String id : ids().getOrDefault("minecraft", List.of())) {
				String[] parts = id.split("\\|");
				if (parts[1].equals("attribute")) {
					out.add(new Item(Kind.ID, parts[0], "minecraft:" + parts[0], 0, "minecraft:" + parts[0], "Attribute id", "attribute"));
				}
			}
			return finish(out, prefix, ws, col, force);
		}

		// the argument under the cursor is an option list: offer every choice as enum.group.choice
		if (sig != null && reg.enums.containsKey("enum_" + sig.activeType())) {
			String group = sig.activeType();
			for (String v : reg.enums.get("enum_" + group)) {
				out.add(new Item(Kind.CONSTANT, v, "enum." + group + "." + v, 0, "enum." + group + "." + v, "Choice of " + group, "enum"));
			}
			return finish(out, prefix, ws, col, force);
		}

		boolean stmtStart = trimmed.isEmpty();
		// events: on_xxx at the start of a line
		if (stmtStart) {
			for (Registry.EventType ev : reg.events.values()) {
				String args = ev.filters.isEmpty() ? "" : "(" + String.join(", ", ev.filters) + ")";
				String ins = ev.filters.isEmpty() ? ev.name + "():" : ev.name + "():";
				int back = ev.filters.isEmpty() ? 2 : 3;
				if (!ev.filters.isEmpty() && ev.name.contains("key")) {
					ins = ev.name + "(\"\"):";
					back = 4;
				}
				String vars = ev.vars.isEmpty() ? "" : "  gives: " + String.join(", ", ev.vars.keySet());
				out.add(new Item(Kind.EVENT, ev.name, ins, back, ev.name + args, ev.description + vars, "event"));
			}
			for (String k : STMT_KEYWORDS) {
				out.add(new Item(Kind.KEYWORD, k, k + " ", 0, k, "Keyword", "keyword"));
			}
			out.add(new Item(Kind.SNIPPET, "for ... in range", "for i in range(1, 10):\n", 0, "for i in range(1, 10):", "Loop; range is inclusive on both ends", "snippet"));
			out.add(new Item(Kind.SNIPPET, "for ... in list", "for item in items:\n", 0, "for item in items:", "Loop over a list", "snippet"));
			out.add(new Item(Kind.SNIPPET, "if ... else", "if condition:\n    pass\nelse:\n    pass", 0, "if / else", "Condition with else branch", "snippet"));
			out.add(new Item(Kind.SNIPPET, "function", "function name(arg):\n", 0, "function name(arg):", "Define a function (return a, b gives several values)", "snippet"));
			out.add(new Item(Kind.SNIPPET, "wait_for", "wait_for():on_chat():arg(msg)", 0, "wait_for(timeout?):event:arg(a, b)", "Pauses the script until that event fires, e.g. wait_for():Folder.On_Enter:arg(text). timed_out tells if the timeout (seconds) ran out first.", "snippet"));
			out.add(new Item(Kind.SNIPPET, "while", "while condition:\n", 0, "while condition:", "Loop while a condition is true", "snippet"));
			for (khoa.script.client.ui.UiElement root : khoa.script.client.guiedit.GuiStore.roots()) {
				String rn = String.valueOf(root.get("name"));
				out.add(new Item(Kind.CONSTANT, rn, rn, 0, rn + " : Screen Folder", "Screen Folder made in the GUI editor", "gui"));
			}
			for (Map.Entry<String, String> v : variables(lines, row).entrySet()) {
				out.add(new Item(Kind.VARIABLE, v.getKey(), v.getKey(), 0, v.getKey() + (v.getValue().isEmpty() || v.getValue().startsWith("module:") ? "" : " : " + v.getValue()), "Variable", "variable"));
			}
			out.add(new Item(Kind.SNIPPET, "module import", "mod = implement ./features/file.kcd", 0, "name = implement ./path.kcd", "Load another file; call it with mod:function()", "snippet"));
		} else {
			out.add(new Item(Kind.KEYWORD, "implement", "implement(\"\")", 2, "implement(\"./file.kcd\")", "Load another .kcd file as a module; call its functions with name:function()", "keyword"));
			for (String k : EXPR_KEYWORDS) {
				out.add(new Item(k.equals("true") || k.equals("false") || k.equals("nil") ? Kind.CONSTANT : Kind.KEYWORD, k, k, 0, k, "Keyword", "keyword"));
			}
		}
		for (Map.Entry<String, String> v : variables(lines, row).entrySet()) {
			out.add(new Item(Kind.VARIABLE, v.getKey(), v.getKey(), 0, v.getKey() + (v.getValue().isEmpty() || v.getValue().startsWith("module:") ? "" : " : " + v.getValue()),
				v.getValue().startsWith("module:") ? "Module " + v.getValue().substring(7) : "Variable", "variable"));
		}
		for (String uf : userFunctions(lines)) {
			String name = uf.substring(0, uf.indexOf('('));
			out.add(new Item(Kind.USER_FUNCTION, name, name + "()", uf.endsWith("()") ? 0 : 1, uf, "Function defined in this file", "this file"));
		}
		for (khoa.script.client.ui.UiElement root : khoa.script.client.guiedit.GuiStore.roots()) {
			String rn = String.valueOf(root.get("name"));
			out.add(new Item(Kind.CONSTANT, rn, rn, 0, rn + " : Screen Folder", "Screen Folder made in the GUI editor", "gui"));
		}
		for (String cname : reg.constants.keySet()) {
			out.add(new Item(Kind.CONSTANT, cname, cname, 0, cname + " : " + khoa.script.lang.Val.typeName(reg.constants.get(cname)), reg.typeDocs.getOrDefault(khoa.script.lang.Val.typeName(reg.constants.get(cname)), ""), "namespace"));
		}
		for (Builtin b : reg.builtins.values()) {
			boolean none = b.params.isEmpty();
			out.add(new Item(Kind.FUNCTION, b.name, b.name + "()", none ? 0 : 1, b.signature, b.description + (b.example.isEmpty() ? "" : "\nExample: " + b.example.replace('\n', ' ')), b.category));
		}
		return finish(out, prefix, ws, col, force);
	}

	private static String relative(String dir, String file) {
		String d = dir.isEmpty() ? "" : dir + "/";
		if (!d.isEmpty() && file.startsWith(d)) {
			return "./" + file.substring(d.length());
		}
		int up = 0;
		String base = dir;
		while (!base.isEmpty()) {
			int i = base.lastIndexOf('/');
			base = i < 0 ? "" : base.substring(0, i);
			up++;
			String b = base.isEmpty() ? "" : base + "/";
			if (file.startsWith(b)) {
				return "../".repeat(up) + file.substring(b.length());
			}
		}
		return "./" + file;
	}

	/** Resolves a module reference (as written after implement) against the folder of the file being edited. */
	public static String resolvePath(String ref) {
		return resolve(currentDir, ref);
	}

	private static String resolve(String dir, String ref) {
		String r = khoa.script.lang.Paths.resolve(dir, ref);
		return r == null ? ref : r;
	}

	private static Result finish(List<Item> all, String prefix, int start, int col, boolean force) {
		String p = prefix.toLowerCase(Locale.ROOT);
		Map<Item, Integer> score = new HashMap<>();
		List<Item> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Item it : all) {
			String l = it.label().toLowerCase(Locale.ROOT);
			int s;
			if (p.isEmpty()) {
				s = 3;
			} else if (l.equals(p)) {
				continue;
			} else if (l.startsWith(p)) {
				s = 0;
			} else if (l.contains(p)) {
				s = 1;
			} else if (subsequence(p, l)) {
				s = 2;
			} else {
				continue;
			}
			if (seen.add(it.kind() + "/" + it.label())) {
				score.put(it, s);
				out.add(it);
			}
		}
		out.sort(Comparator.<Item>comparingInt(score::get).thenComparingInt(i -> i.kind().rank).thenComparing(Item::label));
		return new Result(out, start);
	}

	private static boolean subsequence(String p, String s) {
		int j = 0;
		for (int i = 0; i < s.length() && j < p.length(); i++) {
			if (s.charAt(i) == p.charAt(j)) {
				j++;
			}
		}
		return j == p.length();
	}
}
