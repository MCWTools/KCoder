package khoa.script.client.gui;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Common frame of the KCoder screens: a large panel with a header, a main area, the log/console panel below it (its height can be dragged),
 * a right-click menu and a modal dialog layer. Subclasses fill the main area.
 */
public abstract class KScreen extends Screen {
	private static final int MARGIN = 8;
	private static int bottomH = 200;

	protected final BottomPanel bottom = new BottomPanel();
	protected Modal modal;
	protected ContextMenu menu;
	/** Main area (below the header, above the bottom panel). */
	protected int mainX;
	protected int mainY;
	protected int mainW;
	protected int mainH;
	/** Window size in panel units: the panel shrinks on a small window (down to half) and keeps its size on a big one. */
	protected int lw = 480;
	protected int lh = 270;
	protected float k = 1;
	private boolean dragDivider;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	protected KScreen(String title) {
		super(Component.literal(title));
	}

	protected abstract String headerTitle();

	/** Right side of the header (running badges etc.). */
	protected void drawHeaderRight(GuiGraphicsExtractor g, int right, int y, int mx, int my) {
	}

	protected abstract void drawMain(GuiGraphicsExtractor g, int mx, int my);

	protected abstract boolean mainClicked(double mx, double my, int button, boolean shift, boolean dbl);

	protected boolean mainDragged(double mx, double my) {
		return false;
	}

	protected void mainReleased() {
	}

	protected boolean mainScrolled(double mx, double my, double dy) {
		return false;
	}

	protected boolean mainKey(KeyEvent e) {
		return false;
	}

	protected boolean mainChar(CharacterEvent e) {
		return false;
	}

	/** Esc with nothing open. */
	protected void back() {
		this.minecraft.gui.setScreen(null);
	}

	protected void layoutChanged() {
	}

	@Override
	protected void init() {
		bottom.onOpen = this::openFromLog;
	}

	protected void openFromLog(String session, String file, int line) {
	}

	private void layout() {
		k = (float) Math.max(0.5, Math.min(1.0, Math.min(this.width / 560.0, this.height / 320.0)));
		lw = Math.round(this.width / k);
		lh = Math.round(this.height / k);
		panelX = MARGIN;
		panelY = MARGIN;
		panelW = lw - MARGIN * 2;
		panelH = lh - MARGIN * 2;
		bottomH = Math.max(90, Math.min(bottomH, panelH - 220));
		mainX = panelX + 8;
		mainY = panelY + 30;
		mainW = panelW - 16;
		mainH = panelH - 30 - bottomH - 12;
		bottom.x = panelX + 8;
		bottom.y = panelY + panelH - bottomH - 6;
		bottom.w = panelW - 16;
		bottom.h = bottomH;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		layout();
		// 26.x: character events only arrive while the TextInputManager is on; vanilla text boxes switch it on when they get focus, ours are custom.
		this.minecraft.onTextInputFocusChange(this, true);
		g.fill(0, 0, this.width, this.height, 0xB0050709);
		mx = (int) (mx / k);
		my = (int) (my / k);
		g.pose().pushMatrix();
		g.pose().scale(k, k);
		Ui.panel(g, panelX, panelY, panelW, panelH, Ui.BG, Ui.BORDER);
		Ui.fill(g, panelX, panelY, panelW, 2, Ui.ACCENT);
		Ui.fill(g, panelX + 1, panelY + 2, panelW - 2, 24, Ui.PANEL);
		Ui.text(g, "KCoder", panelX + 10, panelY + 9, Ui.ACCENT);
		Ui.text(g, "|  " + headerTitle(), panelX + 10 + Ui.width("KCoder") + 4, panelY + 9, Ui.TEXT);
		drawHeaderRight(g, panelX + panelW - 10, panelY + 9, mx, my);
		layoutChanged();
		int effMx = blocked() ? -1000 : mx;
		int effMy = blocked() ? -1000 : my;
		drawMain(g, effMx, effMy);
		// divider
		int dy = bottom.y - 5;
		boolean hot = dragDivider || (!blocked() && Ui.in(mx, my, bottom.x, dy, bottom.w, 5));
		Ui.fill(g, bottom.x + bottom.w / 2 - 20, dy + 2, 40, 2, hot ? Ui.ACCENT : Ui.FAINT);
		bottom.render(g, effMx, effMy);
		if (menu != null) {
			menu.draw(g, mx, my);
		}
		if (modal != null) {
			if (modal.done) {
				modal = null;
			} else {
				modal.draw(g, mx, my, lw, lh);
			}
		}
		g.pose().popMatrix();
	}

	private boolean blocked() {
		return modal != null || menu != null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		double mx = e.x() / k;
		double my = e.y() / k;
		int b = e.button();
		boolean shift = e.hasShiftDown();
		if (modal != null) {
			modal.mouseClicked(mx, my, shift, dbl);
			if (modal.done) {
				modal = null;
			}
			return true;
		}
		if (menu != null) {
			ContextMenu m = menu;
			menu = null;
			if (m.contains(mx, my)) {
				m.click(mx, my);
			}
			return true;
		}
		if (Ui.in(mx, my, bottom.x, bottom.y - 6, bottom.w, 7)) {
			dragDivider = true;
			return true;
		}
		if (bottom.contains(mx, my)) {
			mainClicked(-1000, -1000, b, shift, dbl);
			return bottom.mouseClicked(mx, my, shift, dbl);
		}
		bottom.unfocus();
		return mainClicked(mx, my, b, shift, dbl) || super.mouseClicked(e, dbl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragDivider = false;
		if (modal != null) {
			modal.mouseReleased();
			return true;
		}
		bottom.mouseReleased();
		mainReleased();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (modal != null) {
			modal.mouseDragged(e.x() / k, e.y() / k);
			return true;
		}
		if (dragDivider) {
			bottomH = (int) Math.max(90, Math.min(panelH - 220, panelY + panelH - 6 - e.y() / k));
			return true;
		}
		bottom.mouseDragged(e.x() / k, e.y() / k);
		mainDragged(e.x() / k, e.y() / k);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		mx /= k;
		my /= k;
		if (blocked()) {
			return true;
		}
		if (bottom.mouseScrolled(mx, my, sy)) {
			return true;
		}
		return mainScrolled(mx, my, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (modal != null) {
			modal.keyPressed(e);
			if (modal.done) {
				modal = null;
			}
			return true;
		}
		if (menu != null) {
			if (e.key() == InputConstants.KEY_ESCAPE) {
				menu = null;
			}
			return true;
		}
		if (bottom.keyPressed(e)) {
			return true;
		}
		if (bottom.inputFocused()) {
			if (e.key() == InputConstants.KEY_ESCAPE) {
				bottom.unfocus();
			}
			return true;
		}
		if (mainKey(e)) {
			return true;
		}
		if (e.key() == InputConstants.KEY_ESCAPE) {
			back();
			return true;
		}
		return true;
	}

	/** Minecraft only turns on text input (character events, IME) for a screen that says it captures input; our editors are not vanilla widgets, so say so. */
	@Override
	public boolean isInputCaptured() {
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (modal != null) {
			return modal.charTyped(e);
		}
		if (bottom.charTyped(e)) {
			return true;
		}
		return mainChar(e);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
