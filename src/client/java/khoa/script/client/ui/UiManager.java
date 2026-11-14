package khoa.script.client.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import khoa.script.client.ScriptRuntime;
import khoa.script.client.game.Keys;
import khoa.script.client.gui.Ui;
import khoa.script.lang.KcdError;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** All GUI elements of all running scripts: drawing, mouse / keyboard input, animations, toasts and events. */
public final class UiManager {
	private static final class Tween {
		final UiElement e;
		final String prop;
		final Object from;
		final Object to;
		final double dur;
		final String ease;
		double t;

		Tween(UiElement e, String prop, Object from, Object to, double dur, String ease) {
			this.e = e;
			this.prop = prop;
			this.from = from;
			this.to = to;
			this.dur = dur;
			this.ease = ease;
		}
	}

	private static final class Toast {
		final Session session;
		final String text;
		final int color;
		final double ttl;
		double age;

		Toast(Session session, String text, int color, double ttl) {
			this.session = session;
			this.text = text;
			this.color = color;
			this.ttl = ttl;
		}
	}

	static final List<UiElement> ALL = new CopyOnWriteArrayList<>();
	private static final List<Tween> TWEENS = new ArrayList<>();
	private static final List<Toast> TOASTS = new CopyOnWriteArrayList<>();
	private static final Set<Session> HOOKED = ConcurrentHashMap.newKeySet();
	public static UiElement hovered;
	public static UiElement focused;
	static UiElement pressedEl;
	private static UiElement dragEl;
	private static double dragMx;
	private static double dragMy;
	private static Dim dragX;
	private static Dim dragY;
	private static int nextId = 1;
	private static long hoverSince;
	private static long lastFrame = System.nanoTime();

	private UiManager() {
	}

	// ------------------------------------------------------------ lifetime
	private static void hook(Session s) {
		if (HOOKED.add(s)) {
			s.onStop.add(() -> {
				removeAll(s);
				HOOKED.remove(s);
			});
		}
	}

	public static UiElement create(Session s, String kind, boolean multiline) {
		hook(s);
		UiElement e = new UiElement(kind, s, nextId++);
		if (kind.equals("textbox")) {
			if (multiline) {
				e.makeMultiline();
			}
			e.box.onChange = () -> fire("on_change", e, Map.of());
			if (!multiline) {
				e.box.onEnter = () -> {
					fire("on_submit", e, Map.of());
					fire("on_enter", e, Map.of());
					if (e.flag("blur_on_submit") && focused == e) {
						blur();
					}
					return true;
				};
			}
		}
		ALL.add(e);
		return e;
	}

	/** Snapshot of every element, oldest first. */
	public static List<UiElement> all() {
		return List.copyOf(ALL);
	}

	/** Elements in draw order, back to front. */
	public static List<UiElement> drawOrder() {
		return sorted();
	}

	/** Computes the on-screen rectangles of every element (the editor needs them before the HUD draws). */
	public static void layout() {
		layoutAll();
	}

	public static void remove(UiElement e) {
		if (e.removed) {
			return;
		}
		if (focused == e) {
			blur();
		}
		e.removed = true;
		ALL.remove(e);
		if (hovered == e) {
			hovered = null;
		}
		if (pressedEl == e) {
			pressedEl = null;
		}
		TWEENS.removeIf(t -> t.e == e);
		// children lose their parent link and go with it
		for (UiElement c : ALL) {
			if (c.parent() == e) {
				remove(c);
			}
		}
	}

	public static void removeAll(Session s) {
		for (UiElement e : ALL) {
			if (e.session == s) {
				remove(e);
			}
		}
		TOASTS.removeIf(t -> t.session == s);
	}

	public static List<UiElement> of(Session s) {
		List<UiElement> out = new ArrayList<>();
		for (UiElement e : ALL) {
			if (e.session == s) {
				out.add(e);
			}
		}
		return out;
	}

	// ------------------------------------------------------------ events
	static Object valueOf(UiElement e) {
		return switch (e.kind) {
			case "textbox" -> e.box.getText();
			case "checkbox" -> e.flag("checked");
			case "slider", "progress" -> e.num("value");
			default -> null;
		};
	}

	static void fire(String event, UiElement e, Map<String, Object> extra) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("element", e);
		d.put("text", e.box != null ? e.box.getText() : e.kind.equals("button") || e.kind.equals("label") || e.kind.equals("checkbox") ? e.str("text") : "");
		d.put("value", valueOf(e));
		d.putAll(extra);
		ScriptRuntime.engine().events.fire(event, d);
	}

	// ------------------------------------------------------------ focus
	public static void blur() {
		UiElement old = focused;
		if (old == null) {
			return;
		}
		focused = null;
		if (old.box != null) {
			old.box.focused = false;
		}
		fire("on_unfocus", old, Map.of());
	}

	public static void focus(UiElement e) {
		if (e == null || e.box == null || focused == e) {
			return;
		}
		blur();
		focused = e;
		e.box.focused = true;
		fire("on_focus", e, Map.of());
	}

	// ------------------------------------------------------------ cursor
	public static boolean cursorOpen() {
		return Minecraft.getInstance().gui.screen() instanceof UiScreen;
	}

	public static void openCursor() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!(mc.gui.screen() instanceof UiScreen)) {
				mc.gui.setScreen(new UiScreen());
			}
		});
	}

	public static void closeCursor() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui.screen() instanceof UiScreen) {
				mc.gui.setScreen(null);
			}
		});
	}

	// ------------------------------------------------------------ animation
	private static final List<String> EASINGS = List.of("linear", "ease_in", "ease_out", "ease_in_out", "back", "bounce");

	static double ease(String name, double t) {
		t = Math.max(0, Math.min(1, t));
		switch (name) {
			case "ease_in":
				return t * t;
			case "ease_out":
				return 1 - (1 - t) * (1 - t);
			case "ease_in_out":
				return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
			case "back": {
				double c1 = 1.70158;
				double c3 = c1 + 1;
				return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
			}
			case "bounce": {
				double n = 7.5625;
				double d = 2.75;
				if (t < 1 / d) {
					return n * t * t;
				} else if (t < 2 / d) {
					t -= 1.5 / d;
					return n * t * t + 0.75;
				} else if (t < 2.5 / d) {
					t -= 2.25 / d;
					return n * t * t + 0.9375;
				}
				t -= 2.625 / d;
				return n * t * t + 0.984375;
			}
			default:
				return t;
		}
	}

	private static boolean isColorProp(String prop) {
		return prop.endsWith("color") || prop.equals("background");
	}

	private static Object blend(String prop, Object from, Object to, double t) {
		if (isColorProp(prop)) {
			int a = Colors.parse(from, 0xFFFFFFFF);
			int b = Colors.parse(to, a);
			return (double) (Colors.lerp(a, b, t) & 0xFFFFFFFFL);
		}
		Dim a = Dim.of(from);
		Dim b = Dim.of(to);
		if (to instanceof Dim || from instanceof Dim) {
			a = a == null ? new Dim(0, 0) : a;
			b = b == null ? a : b;
			return new Dim(a.scale() + (b.scale() - a.scale()) * t, a.px() + (b.px() - a.px()) * t);
		}
		if (from instanceof Double x && to instanceof Double y) {
			return x + (y - x) * t;
		}
		return t >= 1 ? to : from;
	}

	public static void animate(UiElement e, String prop, Object to, double seconds, String easing) {
		if (!EASINGS.contains(easing)) {
			throw new KcdError("Unknown easing '" + easing + "'", 0).withHint("easings: " + String.join(", ", EASINGS));
		}
		e.set(prop, e.get(prop), false); // validates the property name
		Object from = e.get(prop);
		UiElement.Def def = null;
		for (UiElement.Def d : UiElement.COMMON) {
			if (d.name().equals(prop)) {
				def = d;
			}
		}
		for (UiElement.Def d : UiElement.DEFS.getOrDefault(e.kind, List.of())) {
			if (d.name().equals(prop)) {
				def = d;
			}
		}
		boolean dimProp = def != null && def.type().equals("dim");
		if (dimProp) {
			if (!(to instanceof Dim) && !(to instanceof Double)) {
				throw new KcdError("'" + prop + "' needs a number or from_scale(...)", 0);
			}
			if (from == null) {
				from = new Dim(0, prop.equals("w") ? e.rw : e.rh);
			}
		} else if (!isColorProp(prop) && !(to instanceof Double && from instanceof Double)) {
			throw new KcdError("Only numbers, sizes and colors can be animated ('" + prop + "' is not)", 0);
		}
		TWEENS.removeIf(t -> t.e == e && t.prop.equals(prop));
		if (seconds <= 0) {
			e.set(prop, to, false);
			return;
		}
		TWEENS.add(new Tween(e, prop, from, to, seconds, easing));
	}

	public static void stopAnimations(UiElement e) {
		TWEENS.removeIf(t -> t.e == e);
	}

	// ------------------------------------------------------------ toasts
	public static void toast(Session s, String text, double seconds, int color) {
		hook(s);
		TOASTS.add(new Toast(s, text, color, Math.max(0.3, seconds)));
	}

	// ------------------------------------------------------------ per frame
	public static void onFrame() {
		long now = System.nanoTime();
		double dt = Math.min(0.25, (now - lastFrame) / 1e9);
		lastFrame = now;
		for (Tween tw : new ArrayList<>(TWEENS)) {
			tw.t += dt;
			double k = Math.min(1, tw.t / tw.dur);
			try {
				tw.e.set(tw.prop, blend(tw.prop, tw.from, tw.to, ease(tw.ease, k)), false);
			} catch (RuntimeException ex) {
				TWEENS.remove(tw);
				continue;
			}
			if (k >= 1) {
				TWEENS.remove(tw);
			}
		}
		for (Toast t : TOASTS) {
			t.age += dt;
			if (t.age > t.ttl) {
				TOASTS.remove(t);
			}
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("dt", dt);
		ScriptRuntime.engine().events.fire("on_render", d);
	}

	private static List<UiElement> sorted() {
		List<UiElement> l = new ArrayList<>(ALL);
		l.sort(Comparator.<UiElement>comparingDouble(e -> e.num("z")).thenComparingInt(e -> e.id));
		return l;
	}

	private static void layoutAll() {
		Minecraft mc = Minecraft.getInstance();
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();
		for (UiElement e : ALL) {
			e.layout(sw, sh, 0);
		}
	}

	/** Drawn as a HUD layer while playing. Over another mod's / the game's menu (chest, inventory, chat ...) it is drawn by the overlay below instead, so it stays on top. */
	public static void renderHud(GuiGraphicsExtractor g) {
		if (overlayScreen(Minecraft.getInstance().gui.screen())) {
			return;
		}
		drawAll(g);
	}

	/** True for a menu that is not ours: the script GUI is drawn over it and gets its mouse / keyboard first. */
	public static boolean overlayScreen(Screen s) {
		return s != null && !(s instanceof UiScreen) && !s.getClass().getName().startsWith("khoa.script.client.");
	}

	/**
	 * Whether this element appears over the menu that is open now. Chests, inventories and chat follow always_on_top, every other menu
	 * (pause, options ...) follows show_on_top_of_menu; our own screens and normal play show everything. The top-level parent decides for its children.
	 */
	static boolean shownOnScreen(UiElement e) {
		Screen s = Minecraft.getInstance().gui.screen();
		if (!overlayScreen(s)) {
			return true;
		}
		UiElement root = e;
		for (int guard = 0; root.parent() != null && guard < 32; guard++) {
			root = root.parent();
		}
		boolean content = s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> || s instanceof net.minecraft.client.gui.screens.ChatScreen;
		return root.flag(content ? "always_on_top" : "show_on_top_of_menu");
	}

	private static final Set<Screen> OVERLAID = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/** Called for every screen that opens: puts the script GUI on top of it and routes clicks, scrolling and typing to it. */
	public static void installOverlay(Screen screen) {
		if (!overlayScreen(screen) || !OVERLAID.add(screen)) {
			return;
		}
		ScreenEvents.afterExtract(screen).register((sc, g, mx, my, pt) -> {
			if (ALL.isEmpty() && TOASTS.isEmpty()) {
				return;
			}
			drawAll(g);
			updateHover(mx, my);
			drawTooltip(g, mx, my);
			if (focused != null) {
				Minecraft.getInstance().onTextInputFocusChange(sc, true);
			}
		});
		ScreenMouseEvents.allowMouseClick(screen).register((sc, e) -> !mouseDown(e.x(), e.y(), e.button(), e.hasShiftDown(), false));
		ScreenMouseEvents.allowMouseRelease(screen).register((sc, e) -> {
			boolean ours = pressedEl != null;
			if (ours) {
				mouseUp(e.x(), e.y(), e.button());
			}
			return !ours;
		});
		ScreenMouseEvents.allowMouseDrag(screen).register((sc, e, dx, dy) -> {
			if (pressedEl == null && dragEl == null) {
				return true;
			}
			mouseDrag(e.x(), e.y());
			return false;
		});
		ScreenMouseEvents.allowMouseScroll(screen).register((sc, mx, my, sx, sy) -> !scroll(mx, my, sy));
		ScreenKeyboardEvents.allowKeyPress(screen).register((sc, e) -> {
			if (focused == null) {
				return true;
			}
			if (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
				blur();
			} else {
				key(e);
			}
			return false; // a focused textbox owns the keyboard: E must not close the chest while you type
		});
		ScreenKeyboardEvents.allowCharType(screen).register((sc, e) -> !chr(e));
	}

	private static void drawAll(GuiGraphicsExtractor g) {
		if (ALL.isEmpty() && TOASTS.isEmpty()) {
			return;
		}
		layoutAll();
		for (UiElement e : sorted()) {
			if (e.visibleEff() && shownOnScreen(e)) {
				UiElement clip = e.clipAncestor();
				if (clip != null) {
					g.enableScissor(clip.rx, clip.ry, clip.rx + clip.rw, clip.ry + clip.rh);
				}
				e.draw(g, -1000, -1000);
				if (clip != null) {
					g.disableScissor();
				}
			}
		}
		int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int y = 6;
		for (Toast t : TOASTS) {
			double fade = Math.min(1, Math.min(t.age / 0.2, (t.ttl - t.age) / 0.4));
			int w = Ui.width(t.text) + 16;
			int x = sw - w - 6;
			int bg = Colors.withAlpha(0xFF161A21, fade * 0.92);
			Ui.panel(g, x, y, w, 18, bg, Colors.withAlpha(t.color, fade));
			g.fill(x, y, x + 2, y + 18, Colors.withAlpha(t.color, fade));
			g.text(Ui.font(), t.text, x + 8, y + 5, Colors.withAlpha(0xFFE3E7EE, fade), false);
			y += 22;
		}
	}

	// ------------------------------------------------------------ input (called by UiScreen)
	static UiElement hit(double mx, double my) {
		List<UiElement> l = sorted();
		for (int i = l.size() - 1; i >= 0; i--) {
			UiElement e = l.get(i);
			if (e.visibleEff() && shownOnScreen(e) && !e.flag("click_through") && e.contains(mx, my) && insideClips(e, mx, my)) {
				return e;
			}
		}
		return null;
	}

	private static boolean insideClips(UiElement e, double mx, double my) {
		int guard = 0;
		for (UiElement q = e.clipAncestor(); q != null && guard++ < 32; q = q.clipAncestor()) {
			if (!q.insideRect(mx, my)) {
				return false;
			}
		}
		return true;
	}

	static void updateHover(double mx, double my) {
		layoutAll();
		UiElement h = hit(mx, my);
		if (h != hovered) {
			UiElement old = hovered;
			hovered = h;
			hoverSince = System.currentTimeMillis();
			if (old != null) {
				fire("on_unhover", old, Map.of());
			}
			if (h != null) {
				fire("on_hover", h, Map.of());
			}
		}
	}

	static void drawTooltip(GuiGraphicsExtractor g, int mx, int my) {
		if (hovered == null || System.currentTimeMillis() - hoverSince < 350) {
			return;
		}
		String tip = hovered.str("tooltip");
		if (tip.isEmpty()) {
			return;
		}
		int w = Ui.width(tip) + 10;
		Ui.panel(g, mx + 10, my + 10, w, 16, 0xF0161A21, Ui.BORDER);
		g.text(Ui.font(), tip, mx + 15, my + 14, Ui.TEXT, false);
	}

	static boolean mouseDown(double mx, double my, int button, boolean shift, boolean dbl) {
		UiElement hit = hit(mx, my);
		if (focused != null && hit != focused) {
			blur();
		}
		if (hit == null) {
			return false;
		}
		if (!hit.enabledEff()) {
			return true;
		}
		hit.pressed = true;
		pressedEl = hit;
		boolean left = Keys.mouseName(button).equals("MOUSE_LEFT");
		Map<String, Object> pressInfo = new LinkedHashMap<>();
		pressInfo.put("button", Keys.mouseName(button));
		pressInfo.put("x", mx);
		pressInfo.put("y", my);
		fire("on_press", hit, pressInfo);
		if (dbl) {
			fire("on_double_click", hit, pressInfo);
		}
		if (hit.box != null) {
			focus(hit);
			hit.box.mouseClicked(mx, my, shift, dbl);
		} else if (hit.kind.equals("slider") && left && hit.dragSlider(mx)) {
			fire("on_change", hit, Map.of());
		}
		if (left && hit.flag("draggable") && hit.box == null && !hit.kind.equals("slider")) {
			dragEl = hit;
			dragMx = mx;
			dragMy = my;
			dragX = (Dim) hit.p.get("x");
			dragY = (Dim) hit.p.get("y");
		}
		return true;
	}

	static void mouseDrag(double mx, double my) {
		if (dragEl != null && !dragEl.removed) {
			dragEl.p.put("x", new Dim(dragX.scale(), dragX.px() + mx - dragMx));
			dragEl.p.put("y", new Dim(dragY.scale(), dragY.px() + my - dragMy));
			fire("on_drag", dragEl, Map.of());
		}
		UiElement e = pressedEl;
		if (e == null) {
			return;
		}
		if (e.box != null) {
			e.box.mouseDragged(mx, my);
		} else if (e.kind.equals("slider") && e.dragSlider(mx)) {
			fire("on_change", e, Map.of());
		}
	}

	static void mouseUp(double mx, double my, int button) {
		boolean wasDragging = dragEl != null && (Math.abs(mx - dragMx) > 3 || Math.abs(my - dragMy) > 3);
		dragEl = null;
		UiElement e = pressedEl;
		pressedEl = null;
		if (wasDragging && e != null) {
			e.pressed = false;
			fire("on_drag_end", e, Map.of());
			return;
		}
		if (e != null && !e.removed) {
			Map<String, Object> rel = new LinkedHashMap<>();
			rel.put("button", Keys.mouseName(button));
			rel.put("x", mx);
			rel.put("y", my);
			fire("on_release", e, rel);
		}
		if (e == null) {
			return;
		}
		e.pressed = false;
		if (e.box != null) {
			e.box.mouseReleased();
			return;
		}
		if (e.removed || hit(mx, my) != e) {
			return;
		}
		String name = Keys.mouseName(button);
		if (e.kind.equals("checkbox") && name.equals("MOUSE_LEFT")) {
			e.p.put("checked", !e.flag("checked"));
			fire("on_change", e, Map.of());
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("button", name);
		extra.put("x", mx);
		extra.put("y", my);
		fire("on_click", e, extra);
		if (name.equals("MOUSE_RIGHT")) {
			fire("on_right_click", e, extra);
		}
	}

	static boolean key(net.minecraft.client.input.KeyEvent ev) {
		return focused != null && focused.box.keyPressed(ev);
	}

	static boolean chr(net.minecraft.client.input.CharacterEvent ev) {
		if (focused == null) {
			return false;
		}
		boolean used = focused.box.charTyped(ev);
		if (used) {
			fire("on_type", focused, Map.of("char", new String(Character.toChars(ev.codepoint()))));
		}
		return used;
	}

	static boolean scroll(double mx, double my, double dy) {
		UiElement h = hit(mx, my);
		if (h != null) {
			fire("on_scroll", h, Map.of("delta", dy));
		}
		if (h != null && h.box != null) {
			return h.box.mouseScrolled(mx, my, dy);
		}
		int guard = 0;
		for (UiElement q = h; q != null && guard++ < 32; q = q.parent()) {
			if (q.flag("scrollable")) {
				double max = Math.max(0, q.contentHeight() - q.rh);
				q.p.put("scroll_y", Math.max(0, Math.min(max, q.num("scroll_y") - dy * 12)));
				return true;
			}
		}
		return false;
	}

	/** Used by scripts for plain-number reads of Dim-or-number arguments. */
	public static Dim dim(Object o, String what) {
		Dim d = Dim.of(o);
		if (d == null) {
			throw new KcdError(what + " needs a number or from_scale(...), got " + Val.typeName(o), 0);
		}
		return d;
	}
}
