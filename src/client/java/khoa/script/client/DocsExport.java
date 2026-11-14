package khoa.script.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import khoa.script.ExampleFiles;
import khoa.script.client.guiedit.GuiStore;
import khoa.script.client.ui.UiElement;
import khoa.script.lang.Builtin;
import khoa.script.lang.Registry;

/** Writes everything the language can do, taken from the live registry, to one text file (kcoder/KCODER_REFERENCE.txt). */
public final class DocsExport {
	private static boolean marker;

	private DocsExport() {
	}

	public static Path file() {
		return GuiStore.dir().resolve("KCODER_REFERENCE.txt");
	}

	/** Called every frame: exports once if the file kcoder/export_docs exists (used to produce the file without clicking). */
	public static void tick() {
		if (marker) {
			return;
		}
		Path flag = GuiStore.dir().resolve("export_docs");
		if (Files.exists(flag)) {
			marker = true;
			try {
				write();
				Files.deleteIfExists(flag);
			} catch (IOException | RuntimeException e) {
				Kcd.ENGINE.console.error(null, "Docs export failed: " + e);
			}
		}
	}

	public static Path write() throws IOException {
		Files.createDirectories(GuiStore.dir());
		Files.writeString(file(), build(), StandardCharsets.UTF_8);
		return file();
	}

	private static void h(StringBuilder sb, String title) {
		sb.append("\n\n").append("=".repeat(78)).append('\n').append(title).append('\n').append("=".repeat(78)).append('\n');
	}

	public static String build() {
		Registry r = Registry.get();
		StringBuilder sb = new StringBuilder();
		sb.append(GUIDE);

		// builtins by category
		h(sb, "5. BUILT-IN FUNCTIONS (" + r.builtins.size() + ")");
		Map<String, List<Builtin>> byCat = new TreeMap<>();
		for (Builtin b : r.builtins.values()) {
			byCat.computeIfAbsent(b.category, k -> new ArrayList<>()).add(b);
		}
		for (Map.Entry<String, List<Builtin>> en : byCat.entrySet()) {
			sb.append("\n--- ").append(en.getKey()).append(" ---\n");
			for (Builtin b : en.getValue()) {
				sb.append("\n").append(b.signature).append('\n');
				sb.append("    ").append(b.description).append('\n');
				List<String> flags = new ArrayList<>();
				if (b.client) {
					flags.add("runs on the client thread");
				}
				if (b.suspends) {
					flags.add("may pause the script");
				}
				if (!b.permission.isEmpty()) {
					flags.add("permission: " + b.permission);
				}
				if (!flags.isEmpty()) {
					sb.append("    [").append(String.join("; ", flags)).append("]\n");
				}
				if (!b.example.isEmpty()) {
					sb.append("    example:\n");
					for (String line : b.example.split("\n")) {
						sb.append("        ").append(line).append('\n');
					}
				}
			}
		}

		// types
		h(sb, "6. VALUE TYPES AND THEIR MEMBERS");
		List<String> types = new ArrayList<>(r.members.keySet());
		for (String t : r.typeDocs.keySet()) {
			if (!types.contains(t)) {
				types.add(t);
			}
		}
		types.sort(String::compareTo);
		for (String t : types) {
			sb.append("\n### ").append(t);
			if (r.parents.containsKey(t)) {
				sb.append("  (extends ").append(r.parents.get(t)).append(')');
			}
			sb.append('\n');
			if (r.typeDocs.containsKey(t)) {
				sb.append("    ").append(r.typeDocs.get(t)).append('\n');
			}
			Map<String, Registry.Member> own = r.members.get(t);
			if (own != null) {
				for (Registry.Member m : own.values()) {
					sb.append("  ").append(m.method ? "method " : "prop   ").append(m.signature()).append("  -  ").append(m.doc).append('\n');
				}
			}
		}

		// events
		h(sb, "7. EVENTS (" + r.events.size() + ")   syntax:  on_name(filter...):  then an indented block");
		for (Registry.EventType e : r.events.values()) {
			sb.append("\n").append(e.name).append('(').append(String.join(", ", e.filters)).append(")\n    ").append(e.description).append('\n');
			if (!e.vars.isEmpty()) {
				sb.append("    variables inside the block: ");
				List<String> vs = new ArrayList<>();
				e.vars.forEach((k, v) -> vs.add(k + ": " + v));
				sb.append(String.join(", ", vs)).append('\n');
			}
		}

		// enums
		h(sb, "8. ENUMS  (enum.<group>.<value>, a value is just its text; wrong choices are errors)");
		for (Map.Entry<String, List<String>> en : r.enums.entrySet()) {
			sb.append('\n').append(en.getKey().replaceFirst("^enum_", "enum.")).append(": ").append(String.join(", ", en.getValue())).append('\n');
		}

		// gui
		h(sb, "9. GUI ELEMENTS (script API: create with the kind functions, set properties with el.prop = value)");
		sb.append("\nCommon properties (every element):\n");
		for (UiElement.Def d : UiElement.COMMON) {
			sb.append("  ").append(d.name()).append(" : ").append(d.type()).append("  -  ").append(d.doc()).append('\n');
		}
		for (Map.Entry<String, List<UiElement.Def>> en : UiElement.DEFS.entrySet()) {
			sb.append("\nKind \"").append(en.getKey()).append("\" properties:\n");
			for (UiElement.Def d : en.getValue()) {
				sb.append("  ").append(d.name()).append(" : ").append(d.type()).append("  -  ").append(d.doc()).append('\n');
			}
		}

		// examples
		h(sb, "10. EXAMPLE PROGRAMS (complete, working files shipped with the mod)");
		for (Map.Entry<String, String> en : ExampleFiles.FILES.entrySet()) {
			sb.append("\n----- ").append(en.getKey()).append(" -----\n").append(en.getValue()).append('\n');
		}
		return sb.toString();
	}

	private static final String GUIDE = """
		KCODER REFERENCE
		================
		KCoder is a client-side Fabric mod for Minecraft 26.3: an in-game scripting platform. You write programs in a Python-like language
		(files end in .kcd), edit them in an in-game IDE (command /kcoder), and they control the client: read the world, entities and
		inventory, press keys, aim the camera, draw in 3D, build GUIs, call events. This file is generated from the mod's live registry,
		so every function, type, event and enum listed here exists.

		------------------------------------------------------------------------------
		1. WHAT THE MOD CONTAINS
		------------------------------------------------------------------------------
		- Language: Python-like syntax, coroutines (wait/wait_until never block the game), events, modules (implement), try/except,
		  lambdas, comprehensions, f-style string interpolation, vec2/vec3 maths, Lua-style enums, typed declarations for the editor.
		- In-game IDE (/kcoder): script folders, multi-file projects, syntax highlighting, autocomplete and signature help that know
		  every function and type, type inference, code folding, find/replace, error underlines, undo, copy/cut whole line, a log and
		  console panel, a debugger (breakpoints, step over/into/out, variable view) and a profiler.
		- Game access: entities, blocks, raycasts, inventory, containers, NBT/data components, attributes, boss bars, chat, keys,
		  mouse, camera, screenshots, files and JSON.
		- 3D drawing: block overlays, boxes, lines, graphs (functions, parametric, surfaces), images in the world, optional
		  see-through-walls rendering, colors (rgb/rgba/hsv/hex/lerp).
		- Script GUI: rect, oval, line, label, image, icon, textbox, button, progress, checkbox, slider with parents, anchors,
		  scale+pixel sizes, clipping, scrolling, dragging, tweens/animations, toasts, and a ui_kit module with ready-made windows.
		- Visual GUI editor (/kcoder gui): place Screen Folders, Color Frames, Text, Buttons, Images (PNG, tint, transparency) and
		  Textboxes; explorer tree, properties panel, drag to move, handles to resize, undo/redo, saved outside script folders in
		  kcoder/gui.json. Scripts reach them by name.

		------------------------------------------------------------------------------
		2. LANGUAGE SYNTAX
		------------------------------------------------------------------------------
		Comments:        # to end of line
		Numbers:         all numbers are doubles: 3, 2.5, -1
		Strings:         "text" or 'text'; interpolation with braces:  "hp is {get_health()}"
		Booleans/nil:    true false nil   (True/False/TRUE/None also accepted, any case)
		Lists:           xs = [1, 2, 3]   xs[0]   xs[-1]   xs[1:3]   len(xs)   0-based
		Maps:            m = {"a": 1, "b": 2}   m["a"]   m.a   keys can be any value, even entities: m[entity] = line
		                 missing key reads as nil, so  if not m[k]:  works
		Tuples/unpack:   a, b = 1, 2        for i, x in enumerate(xs):
		Vectors:         vec3(1, 2, 3), vec2(1, 2); + - * / and .x .y .z, distance(a, b)
		Resource ids:    minecraft:allay   minecraft:diamond_sword   (no quotes)

		Operators:       + - * / // % **   == != < > <= >=   and or not   in   is
		                 x += 1  x -= 1  x *= 2 ...

		Control flow:
		    if a:            elif b:            else:
		    while cond:      repeat 5:          for x in xs:      for i in range(1, 10):   (range is inclusive)
		    break  continue  pass  return  exit
		    try:  ... except err:  ... finally: ...        assert cond, "message"

		Functions:       def add(a, b=1):  return a + b          (also function / fn)
		                 f = lambda x: x * 2
		                 call a method with  value:method(args)  or  value.method(args)

		VARIABLES AND SCOPE
		    x = 5            changes variable x wherever it lives (also inside functions and event handlers);
		                     if x does not exist yet it becomes a GLOBAL
		    local x = 5      a variable of this function/handler only
		    local a, b = 1, 2
		    const MAX = 3    constant; assigning again is an error
		    global total = 5 explicit global (still allowed)
		    for-loop variables, comprehension variables and the except variable are always local.

		TYPED DECLARATIONS (hints for the editor's suggestions; not checked at run time)
		    vex: entity = get_nearest_entity(minecraft:vex)
		    items: list<entity> = []
		    lines: map<entity,line3d> = {}
		    lines[allay]: line3d = draw_line_3d(...)       type of one element
		    The editor also infers element types from  lines[k] = value  and  xs.append(value).

		EVENTS
		    on_key_press("G"):
		        showing = not showing
		    The block runs every time the event fires; the filter arguments narrow it down. See section 7.

		MODULES
		    kit = implement ./features/ui_kit.kcd
		    kit:window("Title")                 functions and variables of the file are members of kit

		STRICT ARGUMENTS
		    Every builtin checks its argument types and gives a readable error (with the valid enum choices) when one is wrong.

		ENUMS
		    get_entities(sort = enum.entity_sort.health)      enum.<group>. lists every valid choice in the editor.

		------------------------------------------------------------------------------
		3. TYPICAL PROGRAMS (patterns)
		------------------------------------------------------------------------------
		Toggle a line to every allay when G is pressed:
		    showing = false
		    lines = {}
		    on_key_press("G"):
		        showing = not showing
		        if not showing:
		            for allay in lines.keys():
		                delete(lines[allay])
		            lines = {}
		    while true:
		        if showing:
		            for allay in get_entities_in_range(minecraft:allay, 100):
		                if not lines[allay]:
		                    lines[allay]: line3d = draw_line_3d(get_eye_pos(), get_entity_position(allay), color.rgb(0,50,255), 1, 0.05, true)
		                else:
		                    lines[allay]:setorigin(get_eye_pos(), get_entity_position(allay))
		        wait(0.05)

		Control a GUI made in the visual editor (names are the ones shown in the explorer):
		    ScreenFolder.enabled = true
		    ScreenFolder.MoneyText.text = "000"
		    entered = ScreenFolder.Box.text
		    ScreenFolder.Icon.texture = "localstorage/textures/icon.png"
		    my_label.parent = ScreenFolder         # elements made by script can live inside a Screen Folder too

		------------------------------------------------------------------------------
		4. EDITOR SHORTCUTS
		------------------------------------------------------------------------------
		Ctrl+Space suggestions, Tab accept, Ctrl+F / Ctrl+H find/replace, Ctrl+S save, Ctrl+Z / Ctrl+Y undo/redo, Ctrl+C / Ctrl+X on no
		selection copy/cut the current line, Ctrl+Shift+[ / ] / - fold, click the gutter for breakpoints.
		GUI editor: right-click for menus, drag to move, handles to resize, Del delete, Ctrl+D duplicate, Ctrl+Z / Ctrl+Y undo/redo.
		""";
}
