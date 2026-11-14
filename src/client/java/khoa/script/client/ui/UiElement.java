package khoa.script.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import khoa.script.client.game.ItemRef;
import khoa.script.client.gui.CodeArea;
import khoa.script.client.gui.Ui;
import khoa.script.lang.KcdError;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** One thing a script put on the screen: a shape, text, image, item icon or widget. Its properties are readable and writable from scripts. */
public final class UiElement implements Val.KSettable, Val.KDynamic {
	public record Def(String name, String type, String doc, Object def) {
	}

	public static final List<String> KINDS = List.of("rect", "oval", "line", "label", "image", "icon", "textbox", "button", "progress", "checkbox", "slider");
	public static final Map<String, List<Def>> DEFS = new LinkedHashMap<>();
	public static final List<Def> COMMON = new ArrayList<>();
	/** Computed, read-only properties. */
	public static final List<Def> READONLY = List.of(
		new Def("id", "number", "Unique number of this element.", null),
		new Def("kind", "string", "rect, oval, line, label, image, icon, textbox, button, progress, checkbox or slider.", null),
		new Def("screen_x", "number", "Left edge on screen, in pixels.", null),
		new Def("screen_y", "number", "Top edge on screen, in pixels.", null),
		new Def("screen_w", "number", "Width on screen, in pixels.", null),
		new Def("screen_h", "number", "Height on screen, in pixels.", null),
		new Def("hovered", "boolean", "The mouse is over it (needs ui_cursor(true)).", null),
		new Def("pressed", "boolean", "The mouse button is held down on it.", null),
		new Def("focused", "boolean", "A textbox that has the keyboard.", null),
		new Def("removed", "boolean", "It was removed.", null));

	private static void c(String name, String type, String doc, Object def) {
		COMMON.add(new Def(name, type, doc, def));
	}

	private static void k(String kind, String name, String type, String doc, Object def) {
		DEFS.computeIfAbsent(kind, x -> new ArrayList<>()).add(new Def(name, type, doc, def));
	}

	private static Double col(long argb) {
		return (double) argb;
	}

	static {
		c("name", "string", "A name to find it by: Folder.Name (a child of a Screen Folder made in the GUI editor, or of any element).", "");
		c("folder", "boolean", "True for a Screen Folder (made in the GUI editor). Folder.enabled shows or hides everything inside.", false);
		c("x", "dim", "Left position: pixels, or from_scale(0.5) for half the screen (or the parent).", new Dim(0, 0));
		c("y", "dim", "Top position.", new Dim(0, 0));
		c("w", "dim", "Width. nil = natural size.", null);
		c("h", "dim", "Height. nil = natural size.", null);
		c("anchor_x", "number", "0 = x is the left edge, 0.5 = the center, 1 = the right edge.", 0.0);
		c("anchor_y", "number", "0 = y is the top edge, 0.5 = the center, 1 = the bottom edge.", 0.0);
		c("visible", "boolean", "Hidden elements are not drawn and cannot be clicked.", true);
		c("opacity", "number", "0 to 1.", 1.0);
		c("z", "number", "Draw order: higher is in front.", 0.0);
		c("parent", "gui", "Another element: x/y/w/h are then relative to it.", null);
		c("enabled", "boolean", "Disabled widgets ignore input.", true);
		c("click_through", "boolean", "Clicks go to what is behind it.", false);
		c("always_on_top", "boolean", "Stays on top of chests, inventories and chat while they are open, and can be clicked there. Off = hidden behind them. A child follows its top-level parent.", true);
		c("show_on_top_of_menu", "boolean", "Also shown on top of the pause menu, options and other menus. Off by default. A child follows its top-level parent.", false);
		c("tooltip", "string", "Text shown when the mouse rests on it.", "");
		c("draggable", "boolean", "The player can drag it with the mouse (needs ui_cursor(true)). Fires on_drag.", false);
		c("clip", "boolean", "Children are cut off at its edges.", false);
		c("scrollable", "boolean", "The mouse wheel scrolls its children (use with clip = true).", false);
		c("scroll_y", "number", "How far the children are scrolled up, in pixels.", 0.0);
		for (String s : List.of("rect", "oval")) {
			k(s, "color", "color", "Fill color: \"#RRGGBB\", a name like \"red\", or rgb(r,g,b,a).", col(0xFF4C8DFFL));
			k(s, "border_color", "color", "Outline color.", col(0xFFFFFFFFL));
			k(s, "border_width", "number", "Outline thickness in pixels.", 0.0);
		}
		k("rect", "radius", "number", "Corner radius in pixels.", 0.0);
		k("rect", "gradient_color", "color", "If set, the fill fades from color (top) to this (bottom).", null);
		k("line", "x2", "dim", "End point x.", new Dim(0, 0));
		k("line", "y2", "dim", "End point y.", new Dim(0, 0));
		k("line", "color", "color", "Line color.", col(0xFFFFFFFFL));
		k("line", "thickness", "number", "Pixels.", 1.0);
		k("label", "text", "string", "The text (use a newline for a second line).", "");
		k("label", "color", "color", "Text color.", col(0xFFFFFFFFL));
		k("label", "scale", "number", "Text size multiplier.", 1.0);
		k("label", "shadow", "boolean", "Draw a drop shadow.", true);
		k("label", "align_x", "number", "Where the text sits inside its box: 0 = left, 0.5 = center, 1 = right.", 0.0);
		k("label", "align_y", "number", "0 = top, 0.5 = middle, 1 = bottom.", 0.0);
		k("image", "texture", "string", "Texture id, e.g. \"minecraft:textures/item/diamond.png\" or the result of load_texture().", "");
		k("image", "tint", "color", "Multiplies the picture's colors (white = unchanged).", col(0xFFFFFFFFL));
		k("image", "u0", "number", "Left edge of the part of the texture to show (0-1).", 0.0);
		k("image", "v0", "number", "Top edge (0-1).", 0.0);
		k("image", "u1", "number", "Right edge (0-1).", 1.0);
		k("image", "v1", "number", "Bottom edge (0-1).", 1.0);
		k("icon", "item", "any", "An item id (\"minecraft:diamond\") or an item value.", "");
		k("icon", "size", "number", "Pixels (16 is normal).", 16.0);
		k("icon", "count", "number", "Stack count shown on the icon (1 = hidden).", 1.0);
		k("textbox", "text", "string", "What is typed in it.", "");
		k("textbox", "placeholder", "string", "Gray hint shown while empty.", "");
		k("textbox", "multiline", "boolean", "More than one line (chosen at creation).", false);
		k("textbox", "color", "color", "Text color.", col(0xFFE3E7EEL));
		k("textbox", "background", "color", "Background color.", col(0xFF0B0E13L));
		k("textbox", "border_color", "color", "Border color.", col(0xFF2B3240L));
		k("textbox", "blur_on_submit", "boolean", "Enter also leaves the box (on_unfocus fires).", true);
		k("button", "text", "string", "Label.", "");
		k("button", "color", "color", "Background.", col(0xFF242A35L));
		k("button", "hover_color", "color", "Background under the mouse.", col(0xFF2F3745L));
		k("button", "text_color", "color", "Label color.", col(0xFFE3E7EEL));
		k("button", "text_scale", "number", "Label size multiplier (does not change the button's size).", 1.0);
		k("button", "border_color", "color", "Border.", col(0xFF2B3240L));
		k("progress", "value", "number", "0 to 1.", 0.0);
		k("progress", "color", "color", "Bar color.", col(0xFF3DDC84L));
		k("progress", "background", "color", "Empty part color.", col(0xFF161A21L));
		k("progress", "border_color", "color", "Border.", col(0xFF2B3240L));
		k("progress", "show_text", "boolean", "Print the percentage on the bar.", false);
		k("checkbox", "text", "string", "Label.", "");
		k("checkbox", "checked", "boolean", "Ticked or not.", false);
		k("checkbox", "color", "color", "Tick color.", col(0xFF4C8DFFL));
		k("checkbox", "text_color", "color", "Label color.", col(0xFFE3E7EEL));
		k("slider", "min", "number", "Smallest value.", 0.0);
		k("slider", "max", "number", "Largest value.", 1.0);
		k("slider", "value", "number", "Current value.", 0.0);
		k("slider", "step", "number", "Snap to multiples of this (0 = smooth).", 0.0);
		k("slider", "color", "color", "Filled part.", col(0xFF4C8DFFL));
	}

	public final String kind;
	public final Session session;
	public final int id;
	final Map<String, Object> p = new LinkedHashMap<>();
	CodeArea box;
	boolean removed;
	boolean pressed;
	int rx;
	int ry;
	int rw;
	int rh;
	int rx2;
	int ry2;
	private ItemStack cachedStack = ItemStack.EMPTY;
	private Object cachedItemKey;

	UiElement(String kind, Session session, int id) {
		this.kind = kind;
		this.session = session;
		this.id = id;
		for (Def d : COMMON) {
			p.put(d.name(), d.def());
		}
		for (Def d : DEFS.getOrDefault(kind, List.of())) {
			p.put(d.name(), d.def());
		}
		if (kind.equals("line")) {
			p.put("click_through", true);
		}
		if (kind.equals("textbox")) {
			box = newBox(true);
		}
	}

	private static CodeArea newBox(boolean singleLine) {
		CodeArea b = new CodeArea(singleLine);
		b.lineNumbers = false;
		b.suggestions = false;
		b.plainText = true;
		return b;
	}

	/** Textbox that may span several lines. */
	void makeMultiline() {
		p.put("multiline", true);
		box = newBox(false);
	}

	@Override
	public String typeName() {
		return kind;
	}

	/** Writable view of every property (used to save and load GUI layouts). */
	public Map<String, Object> props() {
		return p;
	}

	public int screenX() {
		return rx;
	}

	public int screenY() {
		return ry;
	}

	public int screenW() {
		return rw;
	}

	public int screenH() {
		return rh;
	}

	public UiElement parentElement() {
		return parent();
	}

	public boolean isRemoved() {
		return removed;
	}

	public boolean isVisibleEff() {
		return visibleEff();
	}

	public boolean isFolder() {
		return flag("folder");
	}

	/** Children of this element by name: Folder.Child.text = "hi". */
	@Override
	public Object dynamicMember(String childName) {
		for (UiElement e : UiManager.all()) {
			if (e.parent() == this && !e.removed && e.str("name").equalsIgnoreCase(childName)) {
				return e;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return "<" + kind + " #" + id + ">";
	}

	// ------------------------------------------------------------ properties
	private Def def(String name) {
		for (Def d : COMMON) {
			if (d.name().equals(name)) {
				return d;
			}
		}
		for (Def d : DEFS.getOrDefault(kind, List.of())) {
			if (d.name().equals(name)) {
				return d;
			}
		}
		return null;
	}

	public Object get(String name) {
		switch (name) {
			case "id":
				return (double) id;
			case "kind":
				return kind;
			case "screen_x":
				return (double) rx;
			case "screen_y":
				return (double) ry;
			case "screen_w":
				return (double) rw;
			case "screen_h":
				return (double) rh;
			case "hovered":
				return UiManager.hovered == this;
			case "pressed":
				return pressed;
			case "focused":
				return UiManager.focused == this;
			case "removed":
				return removed;
			case "text":
				if (box != null) {
					return box.getText();
				}
				break;
			case "enabled":
				if (flag("folder")) {
					return flag("visible");
				}
				break;
			default:
				break;
		}
		return p.get(name);
	}

	@Override
	public void setMember(String name, Object value) {
		set(name, value, true);
	}

	/** Script-side assignment: validated and converted. */
	public void set(String name, Object value, boolean fromScript) {
		Def d = def(name);
		if (d == null) {
			boolean ro = READONLY.stream().anyMatch(r -> r.name().equals(name));
			List<String> names = new ArrayList<>();
			COMMON.forEach(x -> names.add(x.name()));
			DEFS.getOrDefault(kind, List.of()).forEach(x -> names.add(x.name()));
			throw new KcdError(ro ? "'" + name + "' is read-only" : kind + " has no property '" + name + "'", 0)
				.withHint("properties: " + String.join(", ", names));
		}
		if (name.equals("multiline") && fromScript) {
			throw new KcdError("'multiline' can only be chosen when the textbox is created", 0);
		}
		Object v = convert(d, value);
		switch (name) {
			case "parent":
				for (UiElement q = (UiElement) v; q != null; q = (UiElement) q.p.get("parent")) {
					if (q == this) {
						throw new KcdError("An element cannot be its own ancestor", 0);
					}
				}
				break;
			case "text":
				if (box != null) {
					box.setText((String) v);
					return;
				}
				break;
			case "visible":
				if (Boolean.FALSE.equals(v) && UiManager.focused == this) {
					UiManager.blur();
				}
				break;
			case "enabled":
				if (flag("folder")) {
					p.put("visible", v);
					if (Boolean.FALSE.equals(v) && UiManager.focused != null) {
						UiManager.blur();
					}
					return;
				}
				break;
			case "value":
				if (kind.equals("progress")) {
					v = Math.max(0, Math.min(1, (Double) v));
				} else if (kind.equals("slider")) {
					v = clampSlider((Double) v);
				}
				break;
			default:
				break;
		}
		p.put(name, v);
		if (kind.equals("slider") && (name.equals("min") || name.equals("max") || name.equals("step"))) {
			p.put("value", clampSlider(num("value")));
		}
	}

	private Object convert(Def d, Object value) {
		switch (d.type()) {
			case "dim": {
				if (value == null) {
					if (d.def() == null) {
						return null;
					}
					throw new KcdError("'" + d.name() + "' needs a number or from_scale(...)", 0);
				}
				Dim dim = Dim.of(value);
				if (dim == null) {
					throw new KcdError("'" + d.name() + "' needs a number or from_scale(...), got " + Val.typeName(value), 0);
				}
				return dim;
			}
			case "number":
				if (!(value instanceof Double)) {
					throw new KcdError("'" + d.name() + "' needs a number, got " + Val.typeName(value), 0);
				}
				return value;
			case "boolean":
				return Val.truthy(value);
			case "string":
				return value == null ? "" : Val.str(value);
			case "gui":
				if (value != null && !(value instanceof UiElement)) {
					throw new KcdError("'" + d.name() + "' needs a GUI element or nil, got " + Val.typeName(value), 0);
				}
				return value;
			default:
				return value;
		}
	}

	double num(String name) {
		return p.get(name) instanceof Double d ? d : 0;
	}

	boolean flag(String name) {
		return Boolean.TRUE.equals(p.get(name));
	}

	String str(String name) {
		return p.get(name) instanceof String s ? s : "";
	}

	int color(String name, int def) {
		return Colors.parse(p.get(name), def);
	}

	private double clampSlider(double v) {
		double min = num("min");
		double max = Math.max(min, num("max"));
		double step = num("step");
		if (step > 0) {
			v = min + Math.round((v - min) / step) * step;
		}
		return Math.max(min, Math.min(max, v));
	}

	UiElement parent() {
		return (UiElement) p.get("parent");
	}

	boolean visibleEff() {
		int guard = 0;
		for (UiElement e = this; e != null && guard++ < 32; e = e.parent()) {
			if (e.removed || !e.flag("visible")) {
				return false;
			}
		}
		return true;
	}

	boolean enabledEff() {
		int guard = 0;
		for (UiElement e = this; e != null && guard++ < 32; e = e.parent()) {
			if (!e.flag("enabled")) {
				return false;
			}
		}
		return true;
	}

	double opacityEff() {
		double o = 1;
		int guard = 0;
		for (UiElement e = this; e != null && guard++ < 32; e = e.parent()) {
			o *= Math.max(0, Math.min(1, e.num("opacity")));
		}
		return o;
	}

	// ------------------------------------------------------------ layout
	private double defaultW(Font f) {
		return switch (kind) {
			case "label" -> textSize(f, str("text"))[0] * num("scale");
			case "icon" -> num("size");
			case "button" -> 80;
			case "textbox" -> 120;
			case "checkbox" -> 16 + f.width(str("text"));
			case "slider", "progress" -> 100;
			case "line" -> 0;
			default -> 50;
		};
	}

	private double defaultH(Font f) {
		return switch (kind) {
			case "label" -> textSize(f, str("text"))[1] * num("scale");
			case "icon" -> num("size");
			case "button" -> 20;
			case "textbox" -> flag("multiline") ? 48 : 16;
			case "checkbox" -> 12;
			case "slider" -> 10;
			case "progress" -> 8;
			case "line" -> 0;
			default -> 50;
		};
	}

	private static int[] textSize(Font f, String s) {
		int w = 0;
		String[] lines = s.split("\n", -1);
		for (String l : lines) {
			w = Math.max(w, f.width(l));
		}
		return new int[] { w, lines.length * f.lineHeight };
	}

	void layout(int sw, int sh, int depth) {
		Font f = Ui.font();
		double bx = 0;
		double by = 0;
		double bw = sw;
		double bh = sh;
		UiElement par = parent();
		if (par != null && depth < 32) {
			par.layout(sw, sh, depth + 1);
			bx = par.rx;
			by = par.ry - par.num("scroll_y");
			bw = par.rw;
			bh = par.rh;
		}
		Dim dw = (Dim) p.get("w");
		Dim dh = (Dim) p.get("h");
		double w = dw != null ? dw.resolve(bw) : defaultW(f);
		double h = dh != null ? dh.resolve(bh) : defaultH(f);
		Dim dx = (Dim) p.get("x");
		Dim dy = (Dim) p.get("y");
		rw = (int) Math.round(Math.max(0, w));
		rh = (int) Math.round(Math.max(0, h));
		rx = (int) Math.round(bx + dx.resolve(bw) - num("anchor_x") * w);
		ry = (int) Math.round(by + dy.resolve(bh) - num("anchor_y") * h);
		if (kind.equals("line")) {
			rx = (int) Math.round(bx + dx.resolve(bw));
			ry = (int) Math.round(by + dy.resolve(bh));
			rx2 = (int) Math.round(bx + ((Dim) p.get("x2")).resolve(bw));
			ry2 = (int) Math.round(by + ((Dim) p.get("y2")).resolve(bh));
		}
	}

	/** Nearest ancestor that cuts its children off at its edges. */
	UiElement clipAncestor() {
		int guard = 0;
		for (UiElement e = parent(); e != null && guard++ < 32; e = e.parent()) {
			if (e.flag("clip")) {
				return e;
			}
		}
		return null;
	}

	boolean insideRect(double mx, double my) {
		return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
	}

	/** How far the children reach below the top of this element. */
	double contentHeight() {
		double bottom = 0;
		for (UiElement c : UiManager.ALL) {
			if (c.parent() == this) {
				bottom = Math.max(bottom, c.ry + c.rh - (ry - num("scroll_y")));
			}
		}
		return bottom;
	}

	boolean contains(double mx, double my) {
		if (kind.equals("oval")) {
			double a = rw / 2.0;
			double b = rh / 2.0;
			if (a <= 0 || b <= 0) {
				return false;
			}
			double nx = (mx - rx - a) / a;
			double ny = (my - ry - b) / b;
			return nx * nx + ny * ny <= 1;
		}
		return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
	}

	// ------------------------------------------------------------ drawing
	private ItemStack stack() {
		Object v = p.get("item");
		if (v == cachedItemKey || v != null && v.equals(cachedItemKey)) {
			return cachedStack;
		}
		cachedItemKey = v;
		cachedStack = ItemStack.EMPTY;
		if (v instanceof ItemRef r) {
			cachedStack = r.stack().copy();
		} else if (v instanceof String s && !s.isBlank()) {
			Identifier id = Identifier.tryParse(s.contains(":") ? s.trim().toLowerCase() : "minecraft:" + s.trim().toLowerCase());
			if (id != null) {
				var h = BuiltInRegistries.ITEM.get(id);
				if (h.isPresent()) {
					cachedStack = new ItemStack(h.get().value());
				}
			}
		}
		return cachedStack;
	}

	private static int a(int argb, double op) {
		return Colors.withAlpha(argb, op);
	}

	void draw(GuiGraphicsExtractor g, int mx, int my) {
		double op = opacityEff();
		if (op <= 0.001) {
			return;
		}
		if (!enabledEff()) {
			op *= 0.55;
		}
		Font f = Ui.font();
		boolean hover = UiManager.hovered == this;
		switch (kind) {
			case "rect", "oval" -> {
				boolean oval = kind.equals("oval");
				double radius = oval ? 0 : num("radius");
				int fillCol = a(color("color", 0xFF4C8DFF), op);
				Object grad = p.get("gradient_color");
				if (!oval && grad != null && radius <= 0) {
					g.fillGradient(rx, ry, rx + rw, ry + rh, fillCol, a(Colors.parse(grad, fillCol), op));
				} else {
					Shapes.fill(g, rx, ry, rw, rh, radius, oval, fillCol);
				}
				Shapes.outline(g, rx, ry, rw, rh, radius, oval, (int) num("border_width"), a(color("border_color", 0xFFFFFFFF), op));
			}
			case "line" -> Shapes.line(g, rx, ry, rx2, ry2, (int) num("thickness"), a(color("color", 0xFFFFFFFF), op));
			case "label" -> {
				float s = (float) num("scale");
				int textCol = a(color("color", 0xFFFFFFFF), op);
				int[] natural = textSize(f, str("text"));
				float offX = (float) ((rw - natural[0] * s) * num("align_x"));
				float offY = (float) ((rh - natural[1] * s) * num("align_y"));
				g.pose().pushMatrix();
				g.pose().translate(rx + offX, ry + offY);
				g.pose().scale(s, s);
				int row = 0;
				for (String line : str("text").split("\n", -1)) {
					g.text(f, line, 0, row++ * f.lineHeight, textCol, flag("shadow"));
				}
				g.pose().popMatrix();
			}
			case "image" -> {
				Identifier tex = khoa.script.client.guiedit.GuiStore.texture(str("texture").trim());
				if (tex != null) {
					int tint = a(color("tint", 0xFFFFFFFF), op);
					if (tint == 0xFFFFFFFF) {
						g.blit(tex, rx, ry, rx + rw, ry + rh, (float) num("u0"), (float) num("u1"), (float) num("v0"), (float) num("v1"));
					} else {
						// u / v are given in a pretend 1000 x 1000 texture, only the fractions matter
						g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, rx, ry, (float) (num("u0") * 1000), (float) (num("v0") * 1000), rw, rh,
							(int) ((num("u1") - num("u0")) * 1000), (int) ((num("v1") - num("v0")) * 1000), 1000, 1000, tint);
					}
				}
			}
			case "icon" -> {
				ItemStack st = stack();
				if (!st.isEmpty()) {
					float s = (float) (num("size") / 16.0);
					g.pose().pushMatrix();
					g.pose().translate(rx, ry);
					g.pose().scale(s, s);
					g.item(st, 0, 0);
					if (num("count") > 1) {
						String n = String.valueOf((int) num("count"));
						g.text(f, n, 17 - f.width(n), 9, 0xFFFFFFFF, true);
					}
					g.pose().popMatrix();
				}
			}
			case "textbox" -> {
				box.fill = a(color("background", 0xFF0B0E13), op);
				box.borderColor = a(color("border_color", 0xFF2B3240), op);
				box.textColor = color("color", 0xFFE3E7EE);
				box.focused = UiManager.focused == this;
				box.placeholder = str("placeholder");
				box.bounds(rx, ry, rw, rh);
				box.render(g, mx, my);
			}
			case "button" -> {
				int bg = pressed && hover ? Colors.lerp(color("color", 0xFF242A35), 0xFF000000, 0.25)
					: hover ? color("hover_color", 0xFF2F3745) : color("color", 0xFF242A35);
				Shapes.fill(g, rx, ry, rw, rh, 0, false, a(bg, op));
				Shapes.outline(g, rx, ry, rw, rh, 0, false, 1, a(color("border_color", 0xFF2B3240), op));
				String label = str("text");
				float ts = (float) num("text_scale");
				g.pose().pushMatrix();
				g.pose().translate(rx + rw / 2f, ry + rh / 2f);
				g.pose().scale(ts, ts);
				g.text(f, label, -f.width(label) / 2, -f.lineHeight / 2 + 1, a(color("text_color", 0xFFE3E7EE), op), false);
				g.pose().popMatrix();
			}
			case "progress" -> {
				Shapes.fill(g, rx, ry, rw, rh, 0, false, a(color("background", 0xFF161A21), op));
				int fillW = (int) Math.round((rw - 2) * num("value"));
				if (fillW > 0) {
					g.fill(rx + 1, ry + 1, rx + 1 + fillW, ry + rh - 1, a(color("color", 0xFF3DDC84), op));
				}
				Shapes.outline(g, rx, ry, rw, rh, 0, false, 1, a(color("border_color", 0xFF2B3240), op));
				if (flag("show_text")) {
					String t = Math.round(num("value") * 100) + "%";
					g.text(f, t, rx + (rw - f.width(t)) / 2, ry + (rh - f.lineHeight) / 2 + 1, a(0xFFFFFFFF, op), true);
				}
			}
			case "checkbox" -> {
				Shapes.fill(g, rx, ry, 12, 12, 0, false, a(0xFF0B0E13, op));
				Shapes.outline(g, rx, ry, 12, 12, 0, false, 1, a(hover ? 0xFF4C8DFF : 0xFF2B3240, op));
				if (flag("checked")) {
					int tick = a(color("color", 0xFF4C8DFF), op);
					Shapes.line(g, rx + 3, ry + 6, rx + 5, ry + 8, 2, tick);
					Shapes.line(g, rx + 5, ry + 8, rx + 9, ry + 3, 2, tick);
				}
				g.text(f, str("text"), rx + 16, ry + 2, a(color("text_color", 0xFFE3E7EE), op), false);
			}
			case "slider" -> {
				int ty = ry + rh / 2 - 2;
				Shapes.fill(g, rx, ty, rw, 4, 0, false, a(0xFF161A21, op));
				double span = Math.max(1e-9, num("max") - num("min"));
				double t = (num("value") - num("min")) / span;
				int kx = rx + (int) Math.round((rw - 6) * t);
				g.fill(rx, ty, kx + 3, ty + 4, a(color("color", 0xFF4C8DFF), op));
				Shapes.fill(g, kx, ry, 6, rh, 0, false, a(hover || pressed ? 0xFFFFFFFF : 0xFFC9D1E0, op));
			}
			default -> {
			}
		}
	}

	/** Slider position under the mouse. Returns true if the value changed. */
	boolean dragSlider(double mx) {
		double t = Math.max(0, Math.min(1, (mx - rx - 3) / Math.max(1, rw - 6)));
		double v = clampSlider(num("min") + t * (num("max") - num("min")));
		if (v == num("value")) {
			return false;
		}
		p.put("value", v);
		return true;
	}
}
