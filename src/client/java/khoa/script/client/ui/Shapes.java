package khoa.script.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Filled and outlined rounded rectangles, ellipses and lines made of horizontal strips. */
public final class Shapes {
	private Shapes() {
	}

	/** Left inset of the shape in the given pixel row, or -1 when the row is empty. {@code oval}: ellipse; else rounded rectangle with radius r. */
	private static double inset(boolean oval, double w, double h, double r, int row) {
		if (w <= 0 || h <= 0 || row < 0 || row >= h) {
			return -1;
		}
		double cy = row + 0.5;
		if (oval) {
			double b = h / 2;
			double a = w / 2;
			double t = (cy - b) / b;
			return a - a * Math.sqrt(Math.max(0, 1 - t * t));
		}
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			return 0;
		}
		double d = cy < r ? r - cy : cy > h - r ? cy - (h - r) : 0;
		return d <= 0 ? 0 : r - Math.sqrt(Math.max(0, r * r - d * d));
	}

	public static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, double radius, boolean oval, int color) {
		if ((color >>> 24) == 0 || w <= 0 || h <= 0) {
			return;
		}
		if (!oval && radius <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		for (int row = 0; row < h; row++) {
			int in = (int) Math.round(inset(oval, w, h, radius, row));
			if (w - in * 2 > 0) {
				g.fill(x + in, y + row, x + w - in, y + row + 1, color);
			}
		}
	}

	public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, double radius, boolean oval, int bw, int color) {
		if ((color >>> 24) == 0 || bw <= 0 || w <= 0 || h <= 0) {
			return;
		}
		if (!oval && radius <= 0) {
			g.fill(x, y, x + w, y + bw, color);
			g.fill(x, y + h - bw, x + w, y + h, color);
			g.fill(x, y + bw, x + bw, y + h - bw, color);
			g.fill(x + w - bw, y + bw, x + w, y + h - bw, color);
			return;
		}
		for (int row = 0; row < h; row++) {
			int out = (int) Math.round(inset(oval, w, h, radius, row));
			int innerRow = row - bw;
			double innerInset = inset(oval, w - 2 * bw, h - 2 * bw, Math.max(0, radius - bw), innerRow);
			if (innerInset < 0) {
				g.fill(x + out, y + row, x + w - out, y + row + 1, color);
			} else {
				int in = bw + (int) Math.round(innerInset);
				g.fill(x + out, y + row, x + Math.max(out, in), y + row + 1, color);
				g.fill(x + w - Math.max(out, in), y + row, x + w - out, y + row + 1, color);
			}
		}
	}

	public static void line(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int thickness, int color) {
		if ((color >>> 24) == 0) {
			return;
		}
		int t = Math.max(1, thickness);
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.min(6000, Math.max(Math.abs(dx), Math.abs(dy)));
		if (steps == 0) {
			g.fill((int) Math.round(x1), (int) Math.round(y1), (int) Math.round(x1) + t, (int) Math.round(y1) + t, color);
			return;
		}
		int off = t / 2;
		for (int i = 0; i <= steps; i++) {
			int px = (int) Math.round(x1 + dx * i / steps) - off;
			int py = (int) Math.round(y1 + dy * i / steps) - off;
			g.fill(px, py, px + t, py + t, color);
		}
	}
}
