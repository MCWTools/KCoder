package khoa.script.client.game;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import khoa.script.lang.KcdError;

/** Key names for scripts: "G", "F6", "SPACE", "ctrl+G", "MOUSE_LEFT" ... built from the InputConstants key list, so every key the game knows works. */
public final class Keys {
	private static final Map<String, Integer> CODES = new LinkedHashMap<>();
	private static final Map<Integer, String> NAMES = new LinkedHashMap<>();
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("CTRL", "LCONTROL"), Map.entry("CONTROL", "LCONTROL"), Map.entry("SHIFT", "LSHIFT"), Map.entry("ALT", "LALT"),
		Map.entry("ESC", "ESCAPE"), Map.entry("ENTER", "RETURN"), Map.entry("DEL", "DELETE"), Map.entry("BACKSPACE", "BACKSPACE"),
		Map.entry("UP", "UP"), Map.entry("DOWN", "DOWN"), Map.entry("LEFT", "LEFT"), Map.entry("RIGHT", "RIGHT"));

	static {
		for (Field f : InputConstants.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class && f.getName().startsWith("KEY_")) {
				try {
					String n = f.getName().substring(4);
					int code = f.getInt(null);
					CODES.putIfAbsent(n, code);
					NAMES.putIfAbsent(code, n);
				} catch (IllegalAccessException ignored) {
					// skip
				}
			}
		}
	}

	private Keys() {
	}

	/** A parsed key spec: required modifiers plus one key or mouse button. */
	public record Spec(boolean ctrl, boolean shift, boolean alt, boolean mouse, int code, String display) {
	}

	public static List<String> allNames() {
		return new ArrayList<>(CODES.keySet());
	}

	public static int keyCode(String name) {
		String n = name.trim().toUpperCase(Locale.ROOT);
		n = ALIASES.getOrDefault(n, n);
		Integer c = CODES.get(n);
		if (c == null) {
			throw new KcdError("Unknown key '" + name + "'", 0).withHint("examples: \"G\", \"F6\", \"SPACE\", \"LSHIFT\", \"ctrl+G\", \"MOUSE_LEFT\"");
		}
		return c;
	}

	public static String keyName(int code) {
		return NAMES.getOrDefault(code, "KEY_" + code);
	}

	public static int mouseButton(String name) {
		return switch (name.trim().toUpperCase(Locale.ROOT)) {
			case "MOUSE_LEFT", "LEFT", "LMB", "MOUSE_1", "1" -> InputConstants.MOUSE_BUTTON_LEFT;
			case "MOUSE_RIGHT", "RIGHT", "RMB", "MOUSE_2", "2" -> InputConstants.MOUSE_BUTTON_RIGHT;
			case "MOUSE_MIDDLE", "MIDDLE", "MMB", "MOUSE_3", "3" -> InputConstants.MOUSE_BUTTON_MIDDLE;
			case "MOUSE_4", "4" -> InputConstants.MOUSE_BUTTON_4;
			case "MOUSE_5", "5" -> InputConstants.MOUSE_BUTTON_5;
			default -> throw new KcdError("Unknown mouse button '" + name + "'", 0).withHint("MOUSE_LEFT, MOUSE_RIGHT, MOUSE_MIDDLE, MOUSE_4, MOUSE_5");
		};
	}

	public static String mouseName(int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			return "MOUSE_LEFT";
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			return "MOUSE_RIGHT";
		}
		if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
			return "MOUSE_MIDDLE";
		}
		return "MOUSE_" + button;
	}

	public static boolean isMouseName(String s) {
		String u = s.trim().toUpperCase(Locale.ROOT);
		return u.startsWith("MOUSE_") || u.equals("LMB") || u.equals("RMB") || u.equals("MMB");
	}

	/** Parses "ctrl+shift+G" or "MOUSE_RIGHT". */
	public static Spec parse(String text) {
		boolean ctrl = false;
		boolean shift = false;
		boolean alt = false;
		String key = null;
		for (String part : text.split("\\+")) {
			String p = part.trim().toUpperCase(Locale.ROOT);
			switch (p) {
				case "CTRL", "CONTROL" -> ctrl = true;
				case "SHIFT" -> shift = true;
				case "ALT" -> alt = true;
				default -> key = part.trim();
			}
		}
		if (key == null) {
			throw new KcdError("Key spec '" + text + "' has only modifiers", 0);
		}
		boolean mouse = isMouseName(key);
		return new Spec(ctrl, shift, alt, mouse, mouse ? mouseButton(key) : keyCode(key), key.toUpperCase(Locale.ROOT));
	}

	public static boolean ctrlDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
	}

	public static boolean shiftDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
	}

	public static boolean altDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT);
	}
}
