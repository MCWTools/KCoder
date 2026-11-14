package khoa.script.client.ui;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Transparent screen opened by ui_cursor(true): gives the player a mouse and a keyboard for the script GUI.
 * The elements themselves are drawn by the HUD layer (see UiManager.renderHud); this screen only routes input.
 */
public final class UiScreen extends Screen {
	public UiScreen() {
		super(Component.literal("KCoder UI"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
		// no dimmed background: the game stays visible
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		// 26.x: character events only arrive while SDL text input is on
		this.minecraft.onTextInputFocusChange(this, true);
		UiManager.updateHover(mx, my);
		UiManager.drawTooltip(g, mx, my);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		return UiManager.mouseDown(e.x(), e.y(), e.button(), e.hasShiftDown(), dbl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		UiManager.mouseUp(e.x(), e.y(), e.button());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		UiManager.mouseDrag(e.x(), e.y());
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		return UiManager.scroll(mx, my, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_ESCAPE) {
			if (UiManager.focused != null) {
				UiManager.blur();
			} else {
				this.minecraft.gui.setScreen(null);
			}
			return true;
		}
		if (UiManager.key(e)) {
			return true;
		}
		return UiManager.focused != null || super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return UiManager.chr(e);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void removed() {
		UiManager.blur();
		UiManager.hovered = null;
		super.removed();
	}
}
