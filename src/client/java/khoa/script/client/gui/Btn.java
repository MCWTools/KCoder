package khoa.script.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A flat toolbar button. Disabled buttons are greyed out and ignore clicks. */
public final class Btn {
	public static final int NORMAL = 0;
	public static final int PRIMARY = 1;
	public static final int DANGER = 2;
	public static final int SUCCESS = 3;

	public int x;
	public int y;
	public int w;
	public int h = 20;
	public String label;
	public boolean enabled = true;
	public int style;
	public final Runnable action;

	public Btn(String label, int style, Runnable action) {
		this.label = label;
		this.style = style;
		this.action = action;
		this.w = Ui.width(label) + 20;
	}

	public Btn at(int x, int y) {
		this.x = x;
		this.y = y;
		return this;
	}

	public Btn size(int w, int h) {
		this.w = w;
		this.h = h;
		return this;
	}

	public void autoWidth() {
		this.w = Ui.width(label) + 20;
	}

	public boolean hit(double mx, double my) {
		return Ui.in(mx, my, x, y, w, h);
	}

	private int base() {
		return switch (style) {
			case PRIMARY -> Ui.ACCENT;
			case DANGER -> Ui.RED;
			case SUCCESS -> Ui.GREEN;
			default -> 0xFF8FA0BC;
		};
	}

	public void draw(GuiGraphicsExtractor g, int mx, int my) {
		boolean hover = enabled && hit(mx, my);
		if (!enabled) {
			Ui.panel(g, x, y, w, h, Ui.PANEL, Ui.alpha(Ui.BORDER, 150));
			Ui.textCenter(g, label, x + w / 2, y + (h - 8) / 2, Ui.FAINT);
			return;
		}
		int c = base();
		boolean solid = style != NORMAL;
		int fill = solid ? Ui.alpha(c, hover ? 120 : 75) : (hover ? Ui.PANEL3 : Ui.PANEL2);
		Ui.panel(g, x, y, w, h, fill, hover ? c : Ui.alpha(c, solid ? 190 : 90));
		Ui.textCenter(g, label, x + w / 2, y + (h - 8) / 2, solid ? 0xFFFFFFFF : Ui.TEXT);
	}
}
