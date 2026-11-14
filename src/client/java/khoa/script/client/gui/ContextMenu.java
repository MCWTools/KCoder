package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Right-click menu. */
public final class ContextMenu {
	public record Item(String label, boolean enabled, Runnable action) {
	}

	private static final int ROW = 16;

	private final List<Item> items = new ArrayList<>();
	private int x;
	private int y;
	private int w;

	public ContextMenu add(String label, boolean enabled, Runnable action) {
		items.add(new Item(label, enabled, action));
		return this;
	}

	public ContextMenu separator() {
		items.add(new Item(null, false, null));
		return this;
	}

	public ContextMenu open(int mx, int my, int screenW, int screenH) {
		int max = 60;
		for (Item i : items) {
			if (i.label() != null) {
				max = Math.max(max, Ui.width(i.label()));
			}
		}
		w = max + 24;
		int h = height();
		x = Math.min(mx, screenW - w - 2);
		y = Math.min(my, screenH - h - 2);
		return this;
	}

	private int height() {
		int h = 4;
		for (Item i : items) {
			h += i.label() == null ? 5 : ROW;
		}
		return h;
	}

	public void draw(GuiGraphicsExtractor g, int mx, int my) {
		Ui.panel(g, x + 2, y + 2, w, height(), 0x55000000, 0x00000000);
		Ui.panel(g, x, y, w, height(), Ui.PANEL2, Ui.BORDER);
		int yy = y + 2;
		for (Item i : items) {
			if (i.label() == null) {
				Ui.fill(g, x + 6, yy + 2, w - 12, 1, Ui.BORDER);
				yy += 5;
				continue;
			}
			boolean hover = i.enabled() && Ui.in(mx, my, x + 2, yy, w - 4, ROW);
			if (hover) {
				Ui.fill(g, x + 2, yy, w - 4, ROW, Ui.alpha(Ui.ACCENT, 90));
			}
			Ui.text(g, i.label(), x + 10, yy + 4, i.enabled() ? Ui.TEXT : Ui.FAINT);
			yy += ROW;
		}
	}

	public boolean contains(double mx, double my) {
		return Ui.in(mx, my, x, y, w, height());
	}

	/** Runs the clicked item. Returns true if the click was inside the menu. */
	public boolean click(double mx, double my) {
		if (!contains(mx, my)) {
			return false;
		}
		int yy = y + 2;
		for (Item i : items) {
			int rh = i.label() == null ? 5 : ROW;
			if (i.label() != null && i.enabled() && my >= yy && my < yy + rh) {
				i.action().run();
				break;
			}
			yy += rh;
		}
		return true;
	}
}
