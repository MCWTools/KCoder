package khoa.script.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Colors and small drawing helpers shared by every KCoder screen. */
public final class Ui {
	public static final int BG = 0xFF0E1116;
	public static final int PANEL = 0xFF161A21;
	public static final int PANEL2 = 0xFF1C212A;
	public static final int PANEL3 = 0xFF242A35;
	public static final int BORDER = 0xFF2B3240;
	public static final int TEXT = 0xFFE3E7EE;
	public static final int DIM = 0xFF8590A2;
	public static final int FAINT = 0xFF525B6A;
	public static final int ACCENT = 0xFF4C8DFF;
	public static final int GREEN = 0xFF3DDC84;
	public static final int RED = 0xFFFF5F5F;
	public static final int YELLOW = 0xFFFFC857;
	public static final int ORANGE = 0xFFFF9F43;
	public static final int PURPLE = 0xFFB48EFF;
	public static final int TEAL = 0xFF4ECDC4;

	public static final int LH = 11;

	private Ui() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	public static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
		g.fill(x, y, x + w, y + h, fill);
		g.outline(x, y, w, h, border);
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		g.text(font(), s, x, y, color);
	}

	public static void textRight(GuiGraphicsExtractor g, String s, int right, int y, int color) {
		g.text(font(), s, right - font().width(s), y, color);
	}

	public static void textCenter(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
		g.text(font(), s, cx - font().width(s) / 2, y, color);
	}

	public static int width(String s) {
		return font().width(s);
	}

	/** Cuts a string to fit a pixel width, adding ".." when it was shortened. */
	public static String fit(String s, int maxW) {
		if (font().width(s) <= maxW) {
			return s;
		}
		return font().plainSubstrByWidth(s, Math.max(0, maxW - font().width(".."))) + "..";
	}

	/** Word-wraps text to a pixel width (newlines start a new line). Past maxLines the last line ends with "..". */
	public static java.util.List<String> wrap(String s, int maxW, int maxLines) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (s == null || s.isEmpty()) {
			return out;
		}
		boolean cut = false;
		outer:
		for (String para : s.split("\n")) {
			StringBuilder cur = new StringBuilder();
			for (String word : para.split(" ")) {
				String trial = cur.length() == 0 ? word : cur + " " + word;
				if (cur.length() > 0 && font().width(trial) > maxW) {
					out.add(cur.toString());
					cur = new StringBuilder();
					trial = word;
				}
				while (font().width(trial) > maxW && trial.length() > 1) {
					String head = font().plainSubstrByWidth(trial, maxW);
					if (head.isEmpty()) {
						break;
					}
					out.add(head);
					trial = trial.substring(head.length());
				}
				cur = new StringBuilder(trial);
				if (out.size() >= maxLines) {
					cut = true;
					break outer;
				}
			}
			out.add(cur.toString());
			if (out.size() >= maxLines) {
				cut = true;
				break;
			}
		}
		if (out.size() > maxLines) {
			out = new java.util.ArrayList<>(out.subList(0, maxLines));
		}
		if (cut && !out.isEmpty()) {
			out.set(out.size() - 1, fit(out.get(out.size() - 1) + " ...", maxW));
		}
		return out;
	}

	public static boolean in(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static int alpha(int color, int a) {
		return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	public static String clipboard() {
		String s = Minecraft.getInstance().keyboardHandler.getClipboard();
		return s == null ? "" : s;
	}

	public static void setClipboard(String s) {
		Minecraft.getInstance().keyboardHandler.setClipboard(s);
	}

	/** A small colored badge with one letter, used as an icon in lists. */
	public static void badge(GuiGraphicsExtractor g, int x, int y, char c, int color) {
		g.fill(x, y, x + 11, y + 11, alpha(color, 70));
		g.outline(x, y, 11, 11, alpha(color, 200));
		g.text(font(), String.valueOf(c), x + 6 - font().width(String.valueOf(c)) / 2, y + 2, color);
	}
}
