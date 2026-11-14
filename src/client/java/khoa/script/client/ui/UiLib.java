package khoa.script.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Script API of the GUI system: create_* functions, element properties and methods, events. */
public final class UiLib {
	private static final Registry R = Registry.get();
	private static final Map<String, String> TEXTURES = new HashMap<>();

	private UiLib() {
	}

	/** Pixel size of textures loaded by {@link #loadTexture}, by texture id. */
	public static final Map<String, int[]> TEXTURE_SIZES = new HashMap<>();

	/** Loads a .png from the script folder into a dynamic texture (game thread only). */
	public static String loadTexture(Path root, String rel) {
		if (root == null) {
			throw new KcdError("File access is not available for this script", 0);
		}
		root = root.toAbsolutePath().normalize();
		Path f = root.resolve(rel).normalize();
		if (!f.startsWith(root)) {
			throw new KcdError("load_texture(): the file must be inside the script folder", 0);
		}
		try {
			String key = f + "@" + Files.getLastModifiedTime(f).toMillis();
			String known = TEXTURES.get(key);
			if (known != null) {
				return known;
			}
			NativeImage img = NativeImage.read(Files.readAllBytes(f));
			Identifier id = Identifier.fromNamespaceAndPath("khoascript", "dyn/" + Integer.toHexString(key.hashCode()) + "_" + TEXTURES.size());
			TEXTURE_SIZES.put(id.toString(), new int[] { img.getWidth(), img.getHeight() });
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
			TEXTURES.put(key, id.toString());
			return id.toString();
		} catch (IOException e) {
			throw new KcdError("Could not load '" + rel + "': " + e.getMessage(), 0);
		}
	}

	private static Object arg(Object[] a, int i) {
		return i < a.length ? a[i] : null;
	}

	private static UiElement make(Args a, String kind, boolean multiline) {
		return UiManager.create(a.in.session, kind, multiline);
	}

	private static void put(UiElement e, String name, Object v) {
		if (v != null) {
			e.set(name, v, false);
		}
	}

	private static void position(UiElement e, Args a, int xi, int yi) {
		e.p.put("x", UiManager.dim(a.obj(xi), "x"));
		e.p.put("y", UiManager.dim(a.obj(yi), "y"));
	}

	private static void size(UiElement e, Args a, int wi, int hi) {
		e.p.put("w", UiManager.dim(a.obj(wi), "width"));
		e.p.put("h", UiManager.dim(a.obj(hi), "height"));
	}

	private static String text(Args a, int i) {
		return a.obj(i) == null ? "" : Val.str(a.obj(i));
	}

	private static UiElement self(Object o) {
		return (UiElement) o;
	}

	public static void register() {
		String c = "gui";
		R.clientTypes.add("gui");
		R.typeDoc("gui", "A thing on the screen made with create_rect, create_textbox ... Read and write its properties: box.text, bar.value = 0.5");
		for (String kind : UiElement.KINDS) {
			R.typeParent(kind, "gui");
			R.typeDoc(kind, "GUI " + kind);
		}
		for (UiElement.Def d : UiElement.COMMON) {
			R.prop("gui", d.name(), d.type(), d.doc(), (in, s, g) -> self(s).get(d.name()));
		}
		for (UiElement.Def d : UiElement.READONLY) {
			R.prop("gui", d.name(), d.type(), d.doc(), (in, s, g) -> self(s).get(d.name()));
		}
		for (Map.Entry<String, List<UiElement.Def>> en : UiElement.DEFS.entrySet()) {
			for (UiElement.Def d : en.getValue()) {
				R.prop(en.getKey(), d.name(), d.type(), d.doc(), (in, s, g) -> self(s).get(d.name()));
			}
		}
		R.method("gui", "remove()", "Deletes it (and everything inside it).", (in, s, g) -> {
			UiManager.remove(self(s));
			return null;
		});
		R.method("gui", "show()", "Makes it visible.", (in, s, g) -> {
			self(s).set("visible", true, false);
			return null;
		});
		R.method("gui", "hide()", "Hides it.", (in, s, g) -> {
			self(s).set("visible", false, false);
			return null;
		});
		R.method("gui", "toggle()", "Shows it if hidden, hides it if shown.", (in, s, g) -> {
			self(s).set("visible", !self(s).flag("visible"), false);
			return null;
		});
		R.method("gui", "to_front()", "Draws it above everything else.", (in, s, g) -> {
			double top = 0;
			for (UiElement e : UiManager.ALL) {
				top = Math.max(top, e.num("z"));
			}
			self(s).set("z", top + 1, false);
			return null;
		});
		R.method("gui", "to_back()", "Draws it below everything else.", (in, s, g) -> {
			double low = 0;
			for (UiElement e : UiManager.ALL) {
				low = Math.min(low, e.num("z"));
			}
			self(s).set("z", low - 1, false);
			return null;
		});
		R.method("gui", "add_child(child:gui)", "Puts another element inside this one (same as child.parent = this).", (in, s, g) -> {
			((UiElement) g[0]).set("parent", s, true);
			return null;
		});
		R.method("gui", "find_child(name:string) -> gui", "The child with this name, or nil.", (in, s, g) -> self(s).dynamicMember((String) g[0]));
		R.method("gui", "contains(x:number, y:number) -> boolean", "True if the screen point is inside it.", (in, s, g) -> self(s).contains((Double) g[0], (Double) g[1]));
		R.method("gui", "focus()", "Gives a textbox the keyboard (use ui_cursor(true) so you can type).", (in, s, g) -> {
			UiManager.focus(self(s));
			return null;
		});
		R.method("gui", "unfocus()", "Takes the keyboard away from a textbox.", (in, s, g) -> {
			if (UiManager.focused == self(s)) {
				UiManager.blur();
			}
			return null;
		});
		R.method("textbox", "clear()", "Empties the textbox.", (in, s, g) -> {
			self(s).set("text", "", false);
			return null;
		});
		R.method("gui", "animate(prop:string, to:any, seconds:number, easing?:easing)", "Moves a property to a value over time. Easings: linear, ease_in, ease_out, ease_in_out, back, bounce.", (in, s, g) -> {
			Object ease = arg(g, 3);
			UiManager.animate(self(s), (String) g[0], g[1], (Double) g[2], ease == null ? "ease_out" : Val.str(ease));
			return null;
		});
		R.method("gui", "stop_animations()", "Stops every running animation of it.", (in, s, g) -> {
			UiManager.stopAnimations(self(s));
			return null;
		});

		// ---------------------------------------------------------- creating
		R.def(c, "from_scale(scale:number, offset?:number) -> dim", "A position or size relative to the screen (or the parent): from_scale(0.5) is half, from_scale(1, -10) is the full width minus 10 pixels. A plain number means pixels.", a -> new Dim(a.num(0), a.num(1, 0))).client();
		R.def(c, "create_rect(x:dim, y:dim, w:dim, h:dim, color?:color) -> rect", "A filled rectangle. Properties: color, border_color, border_width, radius, gradient_color ...", a -> {
			UiElement e = make(a, "rect", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			put(e, "color", a.obj(4));
			return e;
		}).client().example("panel = create_rect(from_scale(0.5), from_scale(0.5), 200, 100, \"#202530\")\npanel.anchor_x = 0.5\npanel.anchor_y = 0.5\npanel.radius = 6");
		R.def(c, "create_oval(x:dim, y:dim, w:dim, h:dim, color?:color) -> oval", "A filled ellipse or circle.", a -> {
			UiElement e = make(a, "oval", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			put(e, "color", a.obj(4));
			return e;
		}).client();
		R.def(c, "create_line(x1:dim, y1:dim, x2:dim, y2:dim, color?:color, thickness?:number) -> line", "A straight line.", a -> {
			UiElement e = make(a, "line", false);
			position(e, a, 0, 1);
			e.p.put("x2", UiManager.dim(a.obj(2), "x2"));
			e.p.put("y2", UiManager.dim(a.obj(3), "y2"));
			put(e, "color", a.obj(4));
			put(e, "thickness", a.obj(5));
			return e;
		}).client();
		R.def(c, "create_text(x:dim, y:dim, text:string, color?:color, scale?:number) -> label", "A piece of text.", a -> {
			UiElement e = make(a, "label", false);
			position(e, a, 0, 1);
			e.set("text", text(a, 2), false);
			put(e, "color", a.obj(3));
			put(e, "scale", a.obj(4));
			return e;
		}).client().example("hp = create_text(10, 10, \"HP\", \"red\", 2)");
		R.def(c, "create_image(x:dim, y:dim, w:dim, h:dim, texture:string) -> image", "A texture, e.g. \"minecraft:textures/item/diamond.png\" or load_texture(\"./logo.png\").", a -> {
			UiElement e = make(a, "image", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			e.set("texture", text(a, 4), false);
			return e;
		}).client();
		R.def(c, "create_item(x:dim, y:dim, item:any, size?:number) -> icon", "The icon of an item: an id like \"minecraft:diamond\" or an item value.", a -> {
			UiElement e = make(a, "icon", false);
			position(e, a, 0, 1);
			e.set("item", a.obj(2), false);
			put(e, "size", a.obj(3));
			return e;
		}).client();
		R.def(c, "create_textbox(x:dim, y:dim, w:dim, h:dim, placeholder?:string, multiline?:boolean) -> textbox", "A text input. Read box.text; react with on_submit(box), on_change(box), on_unfocus(box). Needs ui_cursor(true) to type.", a -> {
			UiElement e = make(a, "textbox", a.bool(5, false));
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			if (a.has(4)) {
				e.set("placeholder", text(a, 4), false);
			}
			return e;
		}).client().example("box = create_textbox(from_scale(0.5), from_scale(0.5), from_scale(0.4), 18, \"type here\")\nbox.anchor_x = 0.5\nui_cursor(true)\non_submit(box):\n    print(text)");
		R.def(c, "create_button(x:dim, y:dim, w:dim, h:dim, text:string) -> button", "A button. React with on_click(button).", a -> {
			UiElement e = make(a, "button", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			e.set("text", text(a, 4), false);
			return e;
		}).client();
		R.def(c, "create_checkbox(x:dim, y:dim, text:string, checked?:boolean) -> checkbox", "A tick box. Read box.checked; react with on_change(box).", a -> {
			UiElement e = make(a, "checkbox", false);
			position(e, a, 0, 1);
			e.set("text", text(a, 2), false);
			e.set("checked", a.bool(3, false), false);
			return e;
		}).client();
		R.def(c, "create_slider(x:dim, y:dim, w:dim, h:dim, min?:number, max?:number, value?:number) -> slider", "A slider. Read slider.value; react with on_change(slider).", a -> {
			UiElement e = make(a, "slider", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			e.set("min", a.num(4, 0), false);
			e.set("max", a.num(5, 1), false);
			e.set("value", a.num(6, a.num(4, 0)), false);
			return e;
		}).client();
		R.def(c, "create_progress_bar(x:dim, y:dim, w:dim, h:dim, value?:number) -> progress", "A bar filled from 0 to 1 (progress.value).", a -> {
			UiElement e = make(a, "progress", false);
			position(e, a, 0, 1);
			size(e, a, 2, 3);
			e.set("value", a.num(4, 0), false);
			return e;
		}).client();

		// ---------------------------------------------------------- managing
		R.def(c, "ui_cursor(open?:boolean=true)", "Opens (or closes) a transparent screen with a mouse, so you can click and type in your GUI. Esc closes it.", a -> {
			if (a.has(0) && !Val.truthy(a.obj(0))) {
				UiManager.closeCursor();
			} else {
				UiManager.openCursor();
			}
			return null;
		}).client();
		R.def(c, "is_ui_cursor_open() -> boolean", "True while the GUI mouse is active.", a -> UiManager.cursorOpen()).client();
		R.alias("get_ui_cursor_state", "is_ui_cursor_open");
		R.def(c, "toggle_ui_cursor() -> boolean", "Shows the GUI mouse if hidden, hides it if shown; returns the new state.", a -> {
			if (UiManager.cursorOpen()) {
				UiManager.closeCursor();
			} else {
				UiManager.openCursor();
			}
			return UiManager.cursorOpen();
		}).client();
		R.def(c, "clear_ui()", "Removes every GUI element this script made (this also happens when the script stops).", a -> {
			UiManager.removeAll(a.in.session);
			return null;
		}).client();
		R.def(c, "get_ui_elements() -> list<gui>", "Every GUI element this script made.", a -> new ArrayList<Object>(UiManager.of(a.in.session))).client();
		R.def(c, "get_ui_hovered() -> gui", "The element under the mouse (while the GUI mouse is active), or nil.", a -> UiManager.hovered).client();
		R.def(c, "animate(element:gui, prop:string, to:any, seconds:number, easing?:easing)", "Moves a property of an element to a value over time: animate(box, \"x\", from_scale(0.8), 0.5, \"ease_out\"). Works on numbers, sizes and colors.", a -> {
			UiManager.animate((UiElement) a.obj(0), a.str(1), a.obj(2), a.num(3), a.has(4) ? a.str(4) : "ease_out");
			return null;
		}).client();
		R.def(c, "show_toast(text:string, seconds?:number=3, color?:color)", "A small notification at the top right that fades away.", a -> {
			UiManager.toast(a.in.session, text(a, 0), a.num(1, 3), Colors.parse(a.obj(2), 0xFF4C8DFF));
			return null;
		}).client();
		R.def(c, "load_texture(path:string) -> string", "Loads a .png from your script folder and returns a texture id for create_image() and image_3d().", a -> loadTexture(a.in.session.fileRoot, a.str(0))).client();

		// ---------------------------------------------------------- events
		BiPredicate<List<Object>, Map<String, Object>> match = (f, d) -> f.isEmpty() || f.get(0) == d.get("element");
		Map<String, String> vars = Map.of("element", "gui", "text", "string", "value", "any");
		Map<String, String> clickVars = Map.of("element", "gui", "text", "string", "value", "any", "button", "string", "x", "number", "y", "number");
		R.event("on_click", "An element was clicked (needs ui_cursor(true)). on_click(button): runs for that element only. Gives element, button, x, y.", List.of("element"), clickVars, match).skipIfBusy = false;
		R.event("on_submit", "Enter was pressed in a textbox: on_submit(box). Gives element and text.", List.of("element"), vars, match).skipIfBusy = false;
		BiPredicate<List<Object>, Map<String, Object>> changeMatch = (f, d) -> f.isEmpty() ? d.containsKey("element") : d.containsKey("element") ? f.get(0) == d.get("element") : f.get(0).equals(d.get("block"));
		Map<String, String> changeVars = Map.of("element", "gui", "text", "string", "value", "any", "block", "block", "old_id", "string", "id", "string", "old_properties", "map", "properties", "map");
		R.event("on_change", "A textbox was edited, a checkbox ticked or a slider moved by the player: on_change(x). Gives element, text and value. With a block, on_change(get_block(pos)) or block.On_Change: runs when that block's state changes; gives block, old_id, id, old_properties, properties.", List.of("element"), changeVars, changeMatch);
		R.event("on_focus", "A textbox got the keyboard.", List.of("element"), vars, match);
		R.event("on_unfocus", "A textbox lost the keyboard (clicked away, Esc, or Enter).", List.of("element"), vars, match).skipIfBusy = false;
		R.event("on_enter", "Enter was pressed in a textbox (same moment as on_submit): element.On_Enter: ...", List.of("element"), vars, match).skipIfBusy = false;
		R.event("on_type", "A character was typed into a textbox. Gives element, char and text.", List.of("element"), Map.of("element", "gui", "text", "string", "value", "any", "char", "string"), match).skipIfBusy = false;
		R.event("on_press", "A mouse button went down on an element. Gives element, button, x, y.", List.of("element"), clickVars, match).skipIfBusy = false;
		R.event("on_release", "A mouse button was released after pressing an element. Gives element, button, x, y.", List.of("element"), clickVars, match).skipIfBusy = false;
		R.event("on_right_click", "An element was right-clicked. Gives element, button, x, y.", List.of("element"), clickVars, match).skipIfBusy = false;
		R.event("on_double_click", "An element was double-clicked. Gives element, button, x, y.", List.of("element"), clickVars, match).skipIfBusy = false;
		R.event("on_scroll", "The mouse wheel turned over an element. Gives element and delta.", List.of("element"), Map.of("element", "gui", "text", "string", "value", "any", "delta", "number"), match);
		R.event("on_drag_end", "A draggable element was dropped.", List.of("element"), vars, match).skipIfBusy = false;
		R.event("on_drag", "A draggable element is being moved with the mouse.", List.of("element"), vars, match);
		R.event("on_hover", "The mouse entered an element.", List.of("element"), vars, match);
		R.event("on_unhover", "The mouse left an element.", List.of("element"), vars, match);
		R.event("on_render", "Runs every frame while the script is running. Gives dt (seconds since the last frame).", List.of(), Map.of("dt", "number"), null);
	}
}
