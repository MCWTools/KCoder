package khoa.script.client.guiedit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;

import khoa.script.client.Kcd;
import khoa.script.client.ui.Dim;
import khoa.script.client.ui.UiElement;
import khoa.script.client.ui.UiLib;
import khoa.script.client.ui.UiManager;
import khoa.script.lang.Registry;
import khoa.script.lang.Session;
import khoa.script.lib.JsonLib;

/**
 * The GUI made in the editor: a tree of Screen Folders and the things inside them. They are ordinary GUI elements (so scripts, events and
 * animations work on them), saved to {@code kcoder/gui.json} next to the game, outside every script folder.
 */
public final class GuiStore {
	/** The kinds the editor can create. */
	public static final List<String> TYPES = List.of("folder", "frame", "text", "button", "image", "textbox");

	private static Session session;
	private static boolean loaded;
	private static final Map<String, Object[]> TEXTURES = new HashMap<>();

	private GuiStore() {
	}

	public static Path dir() {
		return Kcd.ROOT.getParent().resolve("kcoder");
	}

	public static Path texturesDir() {
		return dir().resolve("localstorage").resolve("textures");
	}

	private static Path file() {
		return dir().resolve("gui.json");
	}

	/** Owner of the saved GUI. It is not in the engine's session list, so "Stop all" leaves it alone. */
	public static synchronized Session session() {
		if (session == null) {
			session = new Session(Kcd.ENGINE, "gui", path -> null);
		}
		return session;
	}

	/** Loads the layout once and lets scripts reach folders by name (ScreenFolder.enabled = false). */
	public static synchronized void init() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Files.createDirectories(texturesDir());
		} catch (IOException ignored) {
			// the editor reports problems when saving
		}
		Registry.get().pathRoot = name -> {
			for (UiElement e : UiManager.all()) {
				if (e.isFolder() && e.parentElement() == null && !e.isRemoved() && e.get("name") instanceof String n && n.equalsIgnoreCase(name)) {
					return true;
				}
			}
			return false;
		};
		Registry.get().resolvers.add(name -> {
			for (UiElement e : UiManager.all()) {
				if (e.isFolder() && e.parentElement() == null && !e.isRemoved() && e.get("name") instanceof String n && n.equalsIgnoreCase(name)) {
					return e;
				}
			}
			return null;
		});
	}

	private static boolean dataLoaded;

	/** Called every frame: reads the layout as soon as the game is far enough along to create widgets. */
	public static void tick() {
		if (!dataLoaded && net.minecraft.client.Minecraft.getInstance().font != null) {
			dataLoaded = true;
			load();
		}
	}

	// ------------------------------------------------------------ queries
	public static List<UiElement> roots() {
		List<UiElement> out = new ArrayList<>();
		for (UiElement e : UiManager.all()) {
			if (e.isFolder() && e.parentElement() == null && e.session == session()) {
				out.add(e);
			}
		}
		return out;
	}

	public static List<UiElement> childrenOf(UiElement parent) {
		List<UiElement> out = new ArrayList<>();
		for (UiElement e : UiManager.all()) {
			if (e.parentElement() == parent && e.session == session()) {
				out.add(e);
			}
		}
		return out;
	}

	/** Finds a thing by a dotted path of names such as ScreenFolder.Panel.Title (case does not matter); null if any name is missing. */
	public static UiElement byPath(String dotted) {
		UiElement cur = null;
		String[] parts = dotted.trim().split("[.]");
		for (String part : parts) {
			UiElement next = null;
			for (UiElement c : cur == null ? roots() : childrenOf(cur)) {
				if (String.valueOf(c.get("name")).equalsIgnoreCase(part.trim())) {
					next = c;
					break;
				}
			}
			if (next == null) {
				return null;
			}
			cur = next;
		}
		return cur;
	}

	public static boolean childrenOfSession(UiElement e) {
		return e.session == session();
	}

	/** True if e is ancestor itself or lies somewhere inside it. */
	public static boolean isInside(UiElement e, UiElement ancestor) {
		int guard = 0;
		for (UiElement q = e; q != null && guard++ < 64; q = q.parentElement()) {
			if (q == ancestor) {
				return true;
			}
		}
		return false;
	}

	public static String typeOf(UiElement e) {
		if (e.isFolder()) {
			return "folder";
		}
		return switch (e.kind) {
			case "rect" -> "frame";
			case "label" -> "text";
			default -> e.kind;
		};
	}

	public static String typeLabel(String type) {
		return switch (type) {
			case "folder" -> "Screen Folder";
			case "frame" -> "Color Frame";
			case "text" -> "Text";
			case "button" -> "Button";
			case "image" -> "Image";
			case "textbox" -> "Textbox";
			default -> type;
		};
	}

	// ------------------------------------------------------------ creating
	private static String uniqueName(UiElement parent, String base) {
		List<String> used = new ArrayList<>();
		for (UiElement e : parent == null ? roots() : childrenOf(parent)) {
			used.add(String.valueOf(e.get("name")).toLowerCase());
		}
		if (!used.contains(base.toLowerCase())) {
			return base;
		}
		for (int i = 2; ; i++) {
			if (!used.contains((base + i).toLowerCase())) {
				return base + i;
			}
		}
	}

	private static void put(UiElement e, String prop, Object v) {
		e.set(prop, v, false);
	}

	/** A new thing with sensible defaults. Screen Folders have no parent; everything else needs a folder (or another thing) as its parent. */
	public static UiElement create(String type, UiElement parent) {
		String kind = switch (type) {
			case "folder", "frame" -> "rect";
			case "text" -> "label";
			default -> type;
		};
		UiElement e = UiManager.create(session(), kind, false);
		put(e, "name", uniqueName(parent, switch (type) {
			case "folder" -> "ScreenFolder";
			case "frame" -> "Frame";
			case "text" -> "Text";
			case "button" -> "Button";
			case "image" -> "Image";
			default -> "Textbox";
		}));
		switch (type) {
			case "folder" -> {
				put(e, "folder", true);
				put(e, "color", 0.0);
				put(e, "w", new Dim(1, 0));
				put(e, "h", new Dim(1, 0));
				put(e, "click_through", true);
			}
			case "frame" -> {
				put(e, "w", new Dim(0, 120));
				put(e, "h", new Dim(0, 70));
			}
			case "text" -> {
				put(e, "text", "Text");
				put(e, "w", new Dim(0, 120));
				put(e, "h", new Dim(0, 20));
			}
			case "button" -> {
				put(e, "text", "Button");
				put(e, "w", new Dim(0, 100));
				put(e, "h", new Dim(0, 24));
			}
			case "image" -> {
				put(e, "w", new Dim(0, 64));
				put(e, "h", new Dim(0, 64));
			}
			default -> {
				put(e, "placeholder", "Type here");
				put(e, "w", new Dim(0, 140));
				put(e, "h", new Dim(0, 20));
			}
		}
		if (parent != null) {
			put(e, "parent", parent);
			put(e, "x", new Dim(0, 10));
			put(e, "y", new Dim(0, 10));
		}
		return e;
	}

	/** Copies a thing and everything inside it. */
	public static UiElement duplicate(UiElement src, UiElement intoParent) {
		UiElement copy = UiManager.create(session(), src.kind, false);
		for (Map.Entry<String, Object> en : src.props().entrySet()) {
			if (!en.getKey().equals("parent") && !en.getKey().equals("name")) {
				copy.props().put(en.getKey(), en.getValue());
			}
		}
		copy.set("name", uniqueName(intoParent, String.valueOf(src.get("name")) + "Copy"), false);
		if (src.get("text") instanceof String t && copy.kind.equals("textbox")) {
			copy.set("text", t, false);
		}
		copy.props().put("parent", intoParent);
		for (UiElement child : childrenOf(src)) {
			duplicate(child, copy);
		}
		return copy;
	}

	public static boolean nameTaken(UiElement e, String name) {
		for (UiElement o : e.parentElement() == null ? roots() : childrenOf(e.parentElement())) {
			if (o != e && String.valueOf(o.get("name")).equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ textures
	/** Texture ids, plus files of the localstorage folder: "localstorage/textures/icon.png". */
	public static Identifier texture(String raw) {
		if (raw.isEmpty()) {
			return null;
		}
		if (!raw.startsWith("localstorage/")) {
			return Identifier.tryParse(raw);
		}
		Object[] hit = TEXTURES.get(raw);
		long now = System.currentTimeMillis();
		if (hit != null && now - (Long) hit[1] < 1500) {
			return (Identifier) hit[0];
		}
		Identifier id = null;
		try {
			id = Identifier.tryParse(UiLib.loadTexture(dir(), raw));
		} catch (RuntimeException e) {
			// missing file: draw nothing
		}
		TEXTURES.put(raw, new Object[] { id, now });
		return id;
	}

	/** Files of kcoder/localstorage/textures as "localstorage/textures/name.png". */
	public static List<String> textureFiles() {
		List<String> out = new ArrayList<>();
		try (var s = Files.list(texturesDir())) {
			s.filter(p -> p.toString().toLowerCase().endsWith(".png")).forEach(p -> out.add("localstorage/textures/" + p.getFileName()));
		} catch (IOException ignored) {
			// no folder yet
		}
		out.sort(String::compareToIgnoreCase);
		return out;
	}

	// ------------------------------------------------------------ saving and loading
	private static Map<Object, Object> toMap(UiElement e) {
		Map<Object, Object> node = new LinkedHashMap<>();
		Map<Object, Object> props = new LinkedHashMap<>();
		for (Map.Entry<String, Object> en : e.props().entrySet()) {
			Object v = en.getValue();
			if (v == null || en.getKey().equals("parent") || en.getKey().equals("scroll_y")) {
				continue;
			}
			if (v instanceof Dim d) {
				Map<Object, Object> dm = new LinkedHashMap<>();
				dm.put("scale", d.scale());
				dm.put("px", d.px());
				props.put(en.getKey(), dm);
			} else if (v instanceof Double || v instanceof Boolean || v instanceof String) {
				props.put(en.getKey(), v);
			}
		}
		if (e.kind.equals("textbox")) {
			props.put("text", e.get("text"));
		}
		node.put("kind", e.kind);
		node.put("props", props);
		List<Object> kids = new ArrayList<>();
		for (UiElement c : childrenOf(e)) {
			kids.add(toMap(c));
		}
		node.put("children", kids);
		return node;
	}

	/** The whole layout as JSON text (used for saving and for undo). */
	public static String snapshot(boolean pretty) {
		List<Object> roots = new ArrayList<>();
		for (UiElement r : roots()) {
			roots.add(toMap(r));
		}
		Map<Object, Object> doc = new LinkedHashMap<>();
		doc.put("version", 1.0);
		doc.put("folders", roots);
		return JsonLib.stringify(doc, pretty);
	}

	/** Replaces the layout with a snapshot. */
	public static void restore(String json) {
		clearAll();
		readDoc(json);
	}

	public static void save() {
		try {
			Files.createDirectories(dir());
			Files.writeString(file(), snapshot(true), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			Kcd.ENGINE.console.error(null, "Could not save the GUI layout: " + ex.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private static UiElement fromMap(Map<Object, Object> node, UiElement parent) {
		String kind = String.valueOf(node.get("kind"));
		if (!UiElement.KINDS.contains(kind)) {
			return null;
		}
		UiElement e = UiManager.create(session(), kind, false);
		Object props = node.get("props");
		if (props instanceof Map<?, ?> pm) {
			for (Map.Entry<?, ?> en : ((Map<Object, Object>) pm).entrySet()) {
				Object v = en.getValue();
				if (v instanceof Map<?, ?> dm) {
					v = new Dim(dm.get("scale") instanceof Double s ? s : 0, dm.get("px") instanceof Double p ? p : 0);
				}
				try {
					e.set(String.valueOf(en.getKey()), v, false);
				} catch (RuntimeException ignored) {
					// a property that no longer exists
				}
			}
		}
		if (parent != null) {
			e.set("parent", parent, false);
		}
		if (node.get("children") instanceof List<?> kids) {
			for (Object k : kids) {
				if (k instanceof Map<?, ?> km) {
					fromMap((Map<Object, Object>) km, e);
				}
			}
		}
		return e;
	}

	@SuppressWarnings("unchecked")
	private static void clearAll() {
		for (UiElement e : UiManager.all()) {
			if (e.session == session()) {
				UiManager.remove(e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void readDoc(String json) {
		Object doc = JsonLib.parse(json);
		if (doc instanceof Map<?, ?> m && m.get("folders") instanceof List<?> folders) {
			for (Object f : folders) {
				if (f instanceof Map<?, ?> fm) {
					fromMap((Map<Object, Object>) fm, null);
				}
			}
		}
	}

	public static void load() {
		clearAll();
		if (!Files.exists(file())) {
			return;
		}
		try {
			readDoc(Files.readString(file(), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException ex) {
			Kcd.ENGINE.console.error(null, "Could not read the GUI layout: " + ex.getMessage());
		}
	}
}
