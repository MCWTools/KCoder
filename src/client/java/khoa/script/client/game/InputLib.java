package khoa.script.client.game;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.MouseHandler;

import khoa.script.client.ScriptRuntime;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Reading the keyboard and mouse. */
public final class InputLib {
	private static final Registry R = Registry.get();

	private InputLib() {
	}

	public static boolean mouseDownByName(String name) {
		return mouseDown(Keys.mouseButton(name));
	}

	static boolean mouseDown(int button) {
		MouseHandler m = G.mc().mouseHandler;
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			return m.isLeftPressed();
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			return m.isRightPressed();
		}
		if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
			return m.isMiddlePressed();
		}
		return false;
	}

	public static void register() {
		String c = "input";
		R.def(c, "is_key_pressed(key:string) -> boolean", "True while a keyboard key is held. Names: \"G\", \"F6\", \"SPACE\", \"LSHIFT\", \"UP\" ...", a -> InputConstants.isKeyDown(Keys.keyCode(a.str(0))))
			.client().example("if is_key_pressed(\"LSHIFT\"):");
		R.def(c, "is_mouse_pressed(button:string) -> boolean", "True while a mouse button is held: MOUSE_LEFT, MOUSE_RIGHT, MOUSE_MIDDLE.", a -> mouseDown(Keys.mouseButton(a.str(0)))).client();
		R.def(c, "get_mouse_position() -> vec2", "Mouse position in GUI-scaled pixels (matters while a menu is open).", a -> {
			var mc = G.mc();
			double sx = mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getScreenWidth();
			double sy = mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getScreenHeight();
			return new Val.Vec2(mc.mouseHandler.xpos() * sx, mc.mouseHandler.ypos() * sy);
		}).client();
		R.def(c, "get_mouse_delta() -> vec2", "How far the mouse moved since the previous frame (pixels).", a -> new Val.Vec2(ScriptRuntime.mouseDx(), ScriptRuntime.mouseDy())).client();
		R.def(c, "get_scroll_delta() -> number", "Mouse wheel movement during the last frame (positive = scrolled up, 0 = none).", a -> ScriptRuntime.scrollDelta()).client();
		R.def(c, "get_pressed_keys() -> list<string>", "Names of all keyboard keys held right now.", a -> {
			List<Object> out = new ArrayList<>();
			for (String n : Keys.allNames()) {
				if (InputConstants.isKeyDown(Keys.keyCode(n))) {
					out.add(n);
				}
			}
			return out;
		}).client();
		R.def(c, "get_pressed_buttons() -> list<string>", "Names of the mouse buttons held right now.", a -> {
			List<Object> out = new ArrayList<>();
			for (String n : new String[] {"MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE"}) {
				if (mouseDown(Keys.mouseButton(n))) {
					out.add(n);
				}
			}
			return out;
		}).client();
		R.def(c, "list_keys() -> list<string>", "Every key name scripts can use.", a -> new ArrayList<Object>(Keys.allNames()));
	}
}
