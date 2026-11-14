package khoa.script.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import khoa.script.client.game.ActionLib;
import khoa.script.client.game.CameraLib;
import khoa.script.client.game.ClientLib;
import khoa.script.client.game.DataLib;
import khoa.script.client.game.InfoLib;
import khoa.script.client.game.EntityLib;
import khoa.script.client.game.EntityRef;
import khoa.script.client.game.InputLib;
import khoa.script.client.game.InventoryLib;
import khoa.script.client.game.Keys;
import khoa.script.client.game.WorldLib;
import khoa.script.client.mixin.MinecraftAccess;
import khoa.script.lang.Engine;
import khoa.script.lang.Registry;
import khoa.script.lib.CoreLib;
import khoa.script.lib.RuntimeLib;

/** Glue between the game loop and the script engine: registers the API, pumps the scheduler and raises game events. */
public final class ScriptRuntime {
	private static boolean registered;
	private static double lastMx;
	private static double lastMy;
	private static double dx;
	private static double dy;
	private static float lastHealth = -1;
	private static String lastScreen;
	private static boolean wasInWorld;

	private ScriptRuntime() {
	}

	public static Engine engine() {
		return Kcd.ENGINE;
	}

	public static double mouseDx() {
		return dx;
	}

	public static double mouseDy() {
		return dy;
	}

	private static double scrollPending;
	private static double scrollFrame;

	/** Called by the scroll mixin with the wheel movement. */
	public static void addScroll(double amount) {
		scrollPending += amount;
	}

	/** Wheel movement during the previous frame (positive = away from the user). */
	public static double scrollDelta() {
		return scrollFrame;
	}

	/** Recomputes what the crosshair points at, after a script turned the camera (so the next click hits the right target). */
	public static void refreshPick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null) {
			((MinecraftAccess) mc).khoa$pick(1.0F);
		}
	}

	public static synchronized void registerApi() {
		if (registered) {
			return;
		}
		registered = true;
		CoreLib.register();
		RuntimeLib.register();
		khoa.script.lib.JsonLib.register();
		khoa.script.lib.EnumLib.register();
		EntityLib.register();
		InventoryLib.register();
		WorldLib.register();
		CameraLib.register();
		ActionLib.register();
		InputLib.register();
		ClientLib.register();
		DataLib.register();
		InfoLib.register();
		khoa.script.client.game.ChatLib.register();
		khoa.script.client.game.MotionLib.register();
		khoa.script.client.game.PacketLib.register();
		khoa.script.client.game.CommandLib.register();
		khoa.script.client.game.PackLib.register();
		khoa.script.client.game.CraftLib.register();
		khoa.script.client.ui.UiLib.register();
		khoa.script.client.game.RaycastLib.register();
		khoa.script.client.game.HitboxLib.register();
		khoa.script.client.render3d.Lib3D.register();
		khoa.script.client.guiedit.GuiStore.init();
		registerEvents();
	}

	private static void registerEvents() {
		Registry r = Registry.get();
		r.event("on_key_press", "A keyboard key or mouse button went down (key spec such as \"G\" or \"ctrl+G\").", List.of("key"),
			Map.of("key", "string"), (f, d) -> keyMatches(f, d));
		r.event("on_key_release", "A keyboard key or mouse button went up.", List.of("key"), Map.of("key", "string"), (f, d) -> keyMatches(f, d));
		r.event("on_mouse_press", "A mouse button went down (MOUSE_LEFT ...).", List.of("button"), Map.of("button", "string"),
			(f, d) -> f.isEmpty() || Keys.isMouseName(String.valueOf(d.get("button"))) && String.valueOf(f.get(0)).equalsIgnoreCase(String.valueOf(d.get("button"))));
		r.event("on_key_hold", "A key stayed down long enough: on_key_hold(\"G\", 1.5) runs once after 1.5 seconds of holding G; letting go resets it.", List.of("key", "seconds"),
			Map.of("key", "string", "seconds", "number"), (f, d) -> f.size() >= 2 && String.valueOf(f.get(0)).equalsIgnoreCase(String.valueOf(d.get("key"))) && f.get(1) instanceof Number n
				&& n.doubleValue() == ((Number) d.get("seconds")).doubleValue());
		r.event("on_chat", "A chat message arrived.", List.of(), Map.of("message", "string"), null);
		r.event("chat_detection", "Watches chat from now on: chat_detection(\"not safe\", 30): runs for every message that contains that text (any letter case), until 30 seconds are over (leave the time out to watch until the script stops). Gives message. Name it with chat_detection(\"text\", 30):arg(msg):", List.of("text", "seconds"),
			Map.of("message", "string"), (f, d) -> !f.isEmpty() && String.valueOf(d.get("message")).toLowerCase(java.util.Locale.ROOT).contains(String.valueOf(f.get(0)).toLowerCase(java.util.Locale.ROOT))).skipIfBusy = false;
		r.event("on_damage", "Your health went down.", List.of(), Map.of("amount", "number", "health", "number"), null);
		r.event("on_join", "You joined a world or server.", List.of(), Map.of(), null);
		r.event("on_leave", "You left the world or server.", List.of(), Map.of(), null);
		r.event("on_screen_open", "A menu was opened.", List.of(), Map.of("screen", "string"), null);
		r.event("on_screen_close", "A menu was closed.", List.of(), Map.of("screen", "string"), null);
	}

	private static boolean keyMatches(List<Object> f, Map<String, Object> d) {
		if (f.isEmpty()) {
			return true;
		}
		Keys.Spec s = Keys.parse(String.valueOf(f.get(0)));
		String k = String.valueOf(d.get("key"));
		boolean mouse = Keys.isMouseName(k);
		return s.mouse() == mouse && s.code() == (mouse ? Keys.mouseButton(k) : Keys.keyCode(k))
			&& s.ctrl() == Boolean.TRUE.equals(d.get("ctrl"))
			&& s.shift() == Boolean.TRUE.equals(d.get("shift"))
			&& s.alt() == Boolean.TRUE.equals(d.get("alt"));
	}

	/** Client tick: pumps scripts, detects keys, damage, join/leave, screens. */
	private static final Map<Object, net.minecraft.world.level.block.state.BlockState> WATCHED = new java.util.WeakHashMap<>();

	/** block.On_Change: compares each watched block with how it looked last tick and fires on_change(block) when it differs. */
	private static void watchBlocks(Minecraft mc, Engine e) {
		if (mc.level == null || !e.events.hasHandlers("on_change")) {
			return;
		}
		for (khoa.script.lang.EventBus.Handler h : e.events.list(null)) {
			if (!h.event.equals("on_change") || h.filters.isEmpty() || !(h.filters.get(0) instanceof khoa.script.client.game.BlockRef ref) || h.session.stopped) {
				continue;
			}
			var now = mc.level.getBlockState(ref.blockPos());
			var before = WATCHED.put(h, now);
			if (before != null && !before.equals(now)) {
				Map<String, Object> d = new HashMap<>();
				d.put("block", ref);
				d.put("old_id", WorldLib.idOf(before));
				d.put("id", WorldLib.idOf(now));
				d.put("old_properties", WorldLib.propertiesOf(before));
				d.put("properties", WorldLib.propertiesOf(now));
				e.events.fire("on_change", d);
			}
		}
	}

	private static final Map<String, Long> HOLD_START = new HashMap<>();
	private static final java.util.Set<String> HOLD_FIRED = new java.util.HashSet<>();

	/** on_key_hold: fires once per hold when a key has been down for the requested time. */
	private static void watchHolds(Minecraft mc, Engine e) {
		if (!e.events.hasHandlers("on_key_hold")) {
			HOLD_START.clear();
			HOLD_FIRED.clear();
			return;
		}
		boolean allowed = mc.player != null && keysReachScripts(mc);
		long now = System.currentTimeMillis();
		java.util.Set<String> live = new java.util.HashSet<>();
		for (khoa.script.lang.EventBus.Handler h : e.events.list(null)) {
			if (!h.event.equals("on_key_hold") || h.filters.size() < 2 || !(h.filters.get(1) instanceof Number secs) || h.session.stopped) {
				continue;
			}
			String name = String.valueOf(h.filters.get(0));
			String id = name.toLowerCase() + "|" + secs.doubleValue();
			live.add(id);
			boolean down;
			try {
				down = allowed && (Keys.isMouseName(name) ? khoa.script.client.game.InputLib.mouseDownByName(name) : InputConstants.isKeyDown(Keys.keyCode(name)));
			} catch (khoa.script.lang.KcdError ex) {
				continue;
			}
			if (!down) {
				HOLD_START.remove(id);
				HOLD_FIRED.remove(id);
				continue;
			}
			Long start = HOLD_START.putIfAbsent(id, now);
			if (start != null && !HOLD_FIRED.contains(id) && now - start >= secs.doubleValue() * 1000.0) {
				HOLD_FIRED.add(id);
				Map<String, Object> d = new HashMap<>();
				d.put("key", name);
				d.put("seconds", secs.doubleValue());
				e.events.fire("on_key_hold", d);
			}
		}
		HOLD_START.keySet().retainAll(live);
		HOLD_FIRED.retainAll(live);
	}

	public static void onTick(Minecraft mc) {
		Engine e = engine();
		watchHolds(mc, e);
		watchBlocks(mc, e);
		boolean inWorld = mc.player != null && mc.level != null;
		if (inWorld != wasInWorld) {
			wasInWorld = inWorld;
			lastHealth = -1;
			e.events.fire(inWorld ? "on_join" : "on_leave", Map.of());
			if (!inWorld) {
				ActionLib.releaseAll();
			}
		}
		if (inWorld && e.events.hasHandlers("on_damage")) {
			float h = mc.player.getHealth();
			if (lastHealth >= 0 && h < lastHealth) {
				Map<String, Object> d = new HashMap<>();
				d.put("amount", (double) (lastHealth - h));
				d.put("health", (double) h);
				e.events.fire("on_damage", d);
			}
			lastHealth = h;
		}
		Screen s = mc.gui.screen();
		String name = s == null ? null : s.getClass().getSimpleName();
		if (!java.util.Objects.equals(name, lastScreen)) {
			if (lastScreen != null) {
				e.events.fire("on_screen_close", Map.of("screen", lastScreen));
			}
			if (name != null) {
				e.events.fire("on_screen_open", Map.of("screen", name));
			}
			lastScreen = name;
		}
		ActionLib.tick(e.scheduler.tick());
		e.scheduler.onTick();
	}

	/** Rendered frame: mouse delta and scheduler pump (short waits resume here). */
	public static void onFrame() {
		Minecraft mc = Minecraft.getInstance();
		scrollFrame = scrollPending;
		scrollPending = 0;
		if (mc.mouseHandler != null) {
			double x = mc.mouseHandler.xpos();
			double y = mc.mouseHandler.ypos();
			dx = x - lastMx;
			dy = y - lastMy;
			lastMx = x;
			lastMy = y;
		}
		khoa.script.client.guiedit.GuiStore.tick();
		khoa.script.client.DocsExport.tick();
		khoa.script.client.ui.UiManager.onFrame();
		engine().scheduler.onFrame();
	}

	/** Keys / buttons whose press a script claimed; their release (and key repeat) is swallowed too. */
	private static final Set<Integer> CLAIMED_KEYS = new HashSet<>();
	private static final Set<Integer> CLAIMED_BUTTONS = new HashSet<>();

	/** True if a running script has a key handler whose key spec equals this key + modifiers (handlers without a key spec never claim). */
	private static boolean claims(boolean mouse, int code, boolean ctrl, boolean shift, boolean alt) {
		for (var h : engine().events.list(null)) {
			if (h.session.stopped || h.filters.isEmpty() || !(h.event.equals("on_key_press") || h.event.equals("on_key_release"))) {
				continue;
			}
			try {
				Keys.Spec s = Keys.parse(String.valueOf(h.filters.get(0)));
				if (s.mouse() == mouse && s.code() == code && s.ctrl() == ctrl && s.shift() == shift && s.alt() == alt) {
					return true;
				}
			} catch (RuntimeException ignored) {
				// a bad key spec is reported when the handler is registered
			}
		}
		return false;
	}

	private static void fireKey(String event, String key, boolean ctrl, boolean shift, boolean alt) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("key", key);
		d.put("ctrl", ctrl);
		d.put("shift", shift);
		d.put("alt", alt);
		engine().events.fire(event, d);
	}

	/** Hotkeys work while playing and also while the script's own GUI mouse (ui_cursor) is open, unless a textbox has the keyboard. */
	private static boolean keysReachScripts(Minecraft mc) {
		Screen sc = mc.gui.screen();
		return sc == null || sc instanceof khoa.script.client.ui.UiScreen && khoa.script.client.ui.UiManager.focused == null;
	}

	/**
	 * Called first thing in KeyboardHandler.keyPress (mixin). Returns true to swallow the key so Minecraft's own Controls never see it:
	 * script hotkeys have priority over the game's key bindings. Not active while a menu/chat is open, and ESC is never claimed.
	 */
	public static boolean onKey(int action, int key, boolean ctrl, boolean shift, boolean alt) {
		Minecraft mc = Minecraft.getInstance();
		if (action == 0) {
			if (CLAIMED_KEYS.remove(key)) {
				fireKey("on_key_release", Keys.keyName(key), ctrl, shift, alt);
				return true;
			}
			if (keysReachScripts(mc) && mc.player != null) {
				fireKey("on_key_release", Keys.keyName(key), ctrl, shift, alt);
			}
			return false;
		}
		if (!keysReachScripts(mc) || mc.player == null || key == InputConstants.KEY_ESCAPE) {
			return false;
		}
		if (action == 2) {
			return CLAIMED_KEYS.contains(key);
		}
		boolean claimed = claims(false, key, ctrl, shift, alt);
		if (claimed) {
			CLAIMED_KEYS.add(key);
		}
		fireKey("on_key_press", Keys.keyName(key), ctrl, shift, alt);
		return claimed;
	}

	/** Same for mouse buttons (only while playing with the mouse grabbed). */
	public static boolean onMouse(int action, int button, boolean ctrl, boolean shift, boolean alt) {
		Minecraft mc = Minecraft.getInstance();
		if (action == 0) {
			if (CLAIMED_BUTTONS.remove(button)) {
				fireKey("on_key_release", Keys.mouseName(button), ctrl, shift, alt);
				return true;
			}
			return false;
		}
		if (mc.gui.screen() != null || mc.player == null || !mc.mouseHandler.isMouseGrabbed()) {
			return false;
		}
		boolean claimed = claims(true, button, ctrl, shift, alt);
		if (claimed) {
			CLAIMED_BUTTONS.add(button);
		}
		fireKey("on_key_press", Keys.mouseName(button), ctrl, shift, alt);
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("button", Keys.mouseName(button));
		engine().events.fire("on_mouse_press", d);
		return claimed;
	}

	/** Chat hook entry. */
	public static void onChat(String message) {
		engine().events.fire("on_chat", Map.of("message", message));
		engine().events.fire("chat_detection", Map.of("message", message));
	}

	static Class<?>[] keep() {
		return new Class<?>[] {EntityRef.class, InputLib.class, ClientLib.class};
	}
}
