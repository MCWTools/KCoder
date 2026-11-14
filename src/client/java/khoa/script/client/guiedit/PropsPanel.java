package khoa.script.client.guiedit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;

import khoa.script.client.gui.CodeArea;
import khoa.script.client.gui.ContextMenu;
import khoa.script.client.gui.Ui;
import khoa.script.client.ui.Colors;
import khoa.script.client.ui.Dim;
import khoa.script.client.ui.UiElement;

/** The properties window of the GUI editor: every property of the selected thing as a field, checkbox, color swatch or button. Draggable, scrollable. */
final class PropsPanel {
	private static final int TITLE_H = 16;
	private static final int ROW_H = 22;
	private static final int LABEL_W = 84;
	private static final List<String> HIDDEN = List.of("parent", "folder", "name", "scroll_y", "scrollable", "clip", "multiline", "enabled");
	private static final int[] PALETTE = {
		0xFFFFFFFF, 0xFFC9D1E0, 0xFF8590A2, 0xFF3A4150, 0xFF161A21, 0xFF000000, 0x00000000, 0xFFFF5F5F,
		0xFFFF9F43, 0xFFFFC857, 0xFFA3E635, 0xFF3DDC84, 0xFF4ECDC4, 0xFF4C8DFF, 0xFF7C5CFF, 0xFFB48EFF,
		0xFFFF77CC, 0xFF8B5A2B, 0xFF7A1F1F, 0xFF1F5F3A, 0xFF1F3A7A, 0xFF3A1F7A, 0xFF7A5F1F, 0xFF2B3240 };

	private enum Kind {
		NAME, DIM, NUM, BOOL, COLOR, STR, ALIGN_X, ALIGN_Y, TEXTURE
	}

	private static final class Row {
		final String prop;
		final String label;
		final Kind kind;
		final String doc;
		CodeArea a;
		CodeArea b;

		Row(String prop, String label, Kind kind, String doc) {
			this.prop = prop;
			this.label = label;
			this.kind = kind;
			this.doc = doc;
		}
	}

	int x;
	int y;
	int w = 250;
	int h = 360;
	private boolean draggingPanel;
	private int dragDx;
	private int dragDy;
	private double scroll;
	private UiElement el;
	private final List<Row> rows = new ArrayList<>();
	private Row focusedRow;
	private CodeArea focusedArea;
	private boolean syncing;
	private String paletteProp;
	private int palX;
	private int palY;
	/** Opens a context menu (used for the texture list). */
	Consumer<ContextMenu> openMenu = m -> {
	};
	Runnable changed = () -> {
	};
	Consumer<String> rename = n -> {
	};

	boolean visible() {
		return el != null;
	}

	void setTarget(UiElement e) {
		if (e == el) {
			return;
		}
		el = e;
		rows.clear();
		focusedRow = null;
		focusedArea = null;
		paletteProp = null;
		scroll = 0;
		if (e == null) {
			return;
		}
		Row name = new Row("name", "Name", Kind.NAME, "How scripts find it: Folder.Name");
		rows.add(name);
		if (e.isFolder()) {
			rows.add(new Row("enabled", "Enabled", Kind.BOOL, "Shows or hides everything inside."));
			rows.add(new Row("z", "Layer order", Kind.NUM, "Higher folders are drawn in front of lower ones."));
		} else {
			for (UiElement.Def d : UiElement.COMMON) {
				if (!HIDDEN.contains(d.name())) {
					rows.add(row(d));
				}
			}
			for (UiElement.Def d : UiElement.DEFS.getOrDefault(e.kind, List.of())) {
				if (!HIDDEN.contains(d.name())) {
					rows.add(row(d));
				}
			}
		}
		for (Row r : rows) {
			make(r);
		}
	}

	private Row row(UiElement.Def d) {
		String n = d.name();
		Kind k = switch (d.type()) {
			case "dim" -> Kind.DIM;
			case "number" -> n.equals("align_x") ? Kind.ALIGN_X : n.equals("align_y") ? Kind.ALIGN_Y : Kind.NUM;
			case "boolean" -> Kind.BOOL;
			case "color" -> Kind.COLOR;
			default -> n.equals("texture") ? Kind.TEXTURE : Kind.STR;
		};
		String label = switch (n) {
			case "z" -> "layer";
			case "w" -> "width";
			case "h" -> "height";
			case "x" -> "x";
			case "y" -> "y";
			default -> n.replace('_', ' ');
		};
		return new Row(n, label, k, d.doc());
	}

	private CodeArea field(Row r, boolean second) {
		CodeArea a = new CodeArea(true);
		a.lineNumbers = false;
		a.suggestions = false;
		a.plainText = true;
		a.fill = 0xCC0B0E13;
		a.borderColor = Ui.BORDER;
		a.placeholder = r.kind == Kind.DIM ? (second ? "offset px" : "scale") : "";
		a.onChange = () -> {
			if (!syncing) {
				apply(r);
			}
		};
		a.onEnter = () -> {
			a.focused = false;
			focusedArea = null;
			return true;
		};
		return a;
	}

	private void make(Row r) {
		switch (r.kind) {
			case NAME, NUM, COLOR, STR, TEXTURE -> r.a = field(r, false);
			case DIM -> {
				r.a = field(r, false);
				r.b = field(r, true);
			}
			default -> {
			}
		}
		sync(r, true);
	}

	// ------------------------------------------------------------ values
	private static String num(double d) {
		if (Math.abs(d - Math.rint(d)) < 1e-9) {
			return String.valueOf((long) Math.rint(d));
		}
		String s = String.format(java.util.Locale.ROOT, "%.3f", d);
		while (s.endsWith("0")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	static String hex(int argb) {
		return (argb >>> 24) == 0xFF ? String.format("#%06X", argb & 0xFFFFFF) : String.format("#%08X", argb);
	}

	private double numOf(String prop) {
		return el.get(prop) instanceof Double d ? d : 0;
	}

	private String text(Row r, boolean second) {
		Object v = el.get(r.prop);
		return switch (r.kind) {
			case NAME, STR, TEXTURE -> v == null ? "" : String.valueOf(v);
			case NUM -> num(v instanceof Double d ? d : 0);
			case COLOR -> hex(Colors.parse(v, 0xFFFFFFFF));
			case DIM -> {
				Dim d = v instanceof Dim dd ? dd : new Dim(0, 0);
				yield num(second ? d.px() : d.scale());
			}
			default -> "";
		};
	}

	/** Copies the element's values into the fields that are not being edited. */
	private void sync(Row r, boolean force) {
		syncing = true;
		try {
			for (int i = 0; i < 2; i++) {
				CodeArea a = i == 0 ? r.a : r.b;
				if (a != null && (force || !a.focused) && !a.getText().equals(text(r, i == 1))) {
					a.setText(text(r, i == 1));
				}
			}
		} finally {
			syncing = false;
		}
	}

	private void apply(Row r) {
		if (el == null) {
			return;
		}
		try {
			switch (r.kind) {
				case NAME -> {
					String n = r.a.getText().trim();
					if (!n.isEmpty() && n.matches("[A-Za-z_][A-Za-z0-9_]*") && !GuiStore.nameTaken(el, n)) {
						el.set("name", n, false);
						rename.accept(n);
					}
				}
				case NUM -> el.set(r.prop, Double.parseDouble(r.a.getText().trim()), false);
				case STR, TEXTURE -> el.set(r.prop, r.a.getText(), false);
				case COLOR -> {
					String t = r.a.getText().trim();
					el.set(r.prop, (double) (Colors.strict(t, r.prop) & 0xFFFFFFFFL), false);
				}
				case DIM -> {
					Dim old = el.get(r.prop) instanceof Dim d ? d : new Dim(0, 0);
					double s = parse(r.a.getText(), old.scale());
					double p = parse(r.b.getText(), old.px());
					el.set(r.prop, new Dim(s, p), false);
				}
				default -> {
				}
			}
			changed.run();
		} catch (RuntimeException ignored) {
			// half-typed value: wait for a valid one
		}
	}

	private static double parse(String s, double def) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private void setProp(String prop, Object v) {
		try {
			el.set(prop, v, false);
			changed.run();
		} catch (RuntimeException ignored) {
			// rejected value
		}
	}

	// ------------------------------------------------------------ drawing
	private int bodyY() {
		return y + TITLE_H;
	}

	private int bodyH() {
		return h - TITLE_H;
	}

	void clampToScreen(int sw, int sh) {
		h = Math.min(h, sh - 16);
		x = Math.max(0, Math.min(x, sw - w));
		y = Math.max(0, Math.min(y, sh - 30));
	}

	private double maxScroll() {
		return Math.max(0, rows.size() * ROW_H + 8 - bodyH());
	}

	void render(GuiGraphicsExtractor g, int mx, int my) {
		if (el == null) {
			return;
		}
		Ui.panel(g, x, y, w, h, 0xC8161A21, Ui.BORDER);
		Ui.fill(g, x + 1, y + 1, w - 2, TITLE_H - 1, 0xD91C212A);
		Ui.text(g, "Properties  |  " + GuiStore.typeLabel(GuiStore.typeOf(el)), x + 6, y + 4, Ui.TEXT);
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
		g.enableScissor(x + 1, bodyY(), x + w - 1, y + h - 1);
		int ry = bodyY() + 4 - (int) scroll;
		for (Row r : rows) {
			if (ry + ROW_H > bodyY() && ry < y + h) {
				drawRow(g, r, ry, mx, my);
			}
			ry += ROW_H;
		}
		g.disableScissor();
		if (maxScroll() > 0) {
			int th = Math.max(14, (int) (bodyH() * (bodyH() / (double) (rows.size() * ROW_H + 8))));
			int ty = bodyY() + (int) ((bodyH() - th) * (scroll / maxScroll()));
			Ui.fill(g, x + w - 4, ty, 3, th, 0x66FFFFFF);
		}
		if (paletteProp != null) {
			drawPalette(g, mx, my);
		}
	}

	private void drawRow(GuiGraphicsExtractor g, Row r, int ry, int mx, int my) {
		sync(r, false);
		Ui.text(g, Ui.fit(r.label, LABEL_W - 4), x + 8, ry + 6, Ui.DIM);
		int fx = x + 8 + LABEL_W;
		int fw = w - LABEL_W - 20;
		switch (r.kind) {
			case NAME, NUM, STR -> {
				r.a.bounds(fx, ry + 2, fw, 17);
				r.a.render(g, mx, my);
			}
			case TEXTURE -> {
				r.a.bounds(fx, ry + 2, fw - 22, 17);
				r.a.render(g, mx, my);
				button(g, fx + fw - 20, ry + 2, 20, 17, "...", mx, my);
			}
			case DIM -> {
				int half = (fw - 4) / 2;
				r.a.bounds(fx, ry + 2, half, 17);
				r.b.bounds(fx + half + 4, ry + 2, half, 17);
				r.a.render(g, mx, my);
				r.b.render(g, mx, my);
			}
			case COLOR -> {
				int c = Colors.parse(el.get(r.prop), 0xFFFFFFFF);
				swatch(g, fx, ry + 2, 22, 17, c);
				r.a.bounds(fx + 26, ry + 2, fw - 26, 17);
				r.a.render(g, mx, my);
			}
			case BOOL -> {
				boolean on = Boolean.TRUE.equals(el.get(r.prop));
				Ui.panel(g, fx, ry + 4, 13, 13, 0xCC0B0E13, on ? Ui.GREEN : Ui.BORDER);
				if (on) {
					Ui.fill(g, fx + 3, ry + 7, 7, 7, Ui.GREEN);
				}
				Ui.text(g, on ? "true" : "false", fx + 20, ry + 7, on ? 0xFF7CFF3A : 0xFFFF3B3B);
			}
			case ALIGN_X, ALIGN_Y -> {
				String[] names = r.kind == Kind.ALIGN_X ? new String[] { "Left", "Center", "Right" } : new String[] { "Top", "Middle", "Bottom" };
				double cur = numOf(r.prop);
				int bw = (fw - 4) / 3;
				for (int i = 0; i < 3; i++) {
					boolean on = Math.abs(cur - i * 0.5) < 0.01;
					int bx = fx + i * (bw + 2);
					Ui.panel(g, bx, ry + 2, bw, 17, on ? Ui.alpha(Ui.ACCENT, 140) : 0xCC1C212A, on ? Ui.ACCENT : Ui.BORDER);
					Ui.textCenter(g, names[i], bx + bw / 2, ry + 7, Ui.TEXT);
				}
			}
			default -> {
			}
		}
	}

	private void button(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, String label, int mx, int my) {
		boolean hot = Ui.in(mx, my, bx, by, bw, bh);
		Ui.panel(g, bx, by, bw, bh, hot ? 0xE0242A35 : 0xCC1C212A, Ui.BORDER);
		Ui.textCenter(g, label, bx + bw / 2, by + 5, Ui.TEXT);
	}

	private void swatch(GuiGraphicsExtractor g, int sx, int sy, int sw, int sh, int argb) {
		// checkerboard shows transparency
		for (int cy = 0; cy < sh; cy += 4) {
			for (int cx = 0; cx < sw; cx += 4) {
				g.fill(sx + cx, sy + cy, Math.min(sx + sw, sx + cx + 4), Math.min(sy + sh, sy + cy + 4), ((cx + cy) / 4) % 2 == 0 ? 0xFF8A8F99 : 0xFF5A5F69);
			}
		}
		g.fill(sx, sy, sx + sw, sy + sh, argb);
		g.outline(sx, sy, sw, sh, Ui.BORDER);
	}

	private void drawPalette(GuiGraphicsExtractor g, int mx, int my) {
		int cols = 8;
		int cell = 18;
		int rowsN = (PALETTE.length + cols - 1) / cols;
		Ui.panel(g, palX, palY, cols * cell + 8, rowsN * cell + 8, 0xF0161A21, Ui.BORDER);
		for (int i = 0; i < PALETTE.length; i++) {
			int cx = palX + 4 + (i % cols) * cell;
			int cy = palY + 4 + (i / cols) * cell;
			swatch(g, cx, cy, cell - 2, cell - 2, PALETTE[i]);
		}
	}

	// ------------------------------------------------------------ input
	boolean contains(double mx, double my) {
		return el != null && (Ui.in(mx, my, x, y, w, h) || paletteProp != null && Ui.in(mx, my, palX, palY, 8 * 18 + 8, 3 * 18 + 8));
	}

	boolean typing() {
		return focusedArea != null && focusedArea.focused;
	}

	void unfocusIfOutside(double mx, double my) {
		if (focusedArea != null && !Ui.in(mx, my, x, y, w, h)) {
			unfocus();
		}
	}

	void unfocus() {
		if (focusedArea != null) {
			focusedArea.focused = false;
		}
		focusedArea = null;
	}

	boolean mouseClicked(double mx, double my, boolean shift, boolean dbl) {
		if (el == null) {
			return false;
		}
		if (paletteProp != null) {
			int cols = 8;
			int cell = 18;
			for (int i = 0; i < PALETTE.length; i++) {
				if (Ui.in(mx, my, palX + 4 + (i % cols) * cell, palY + 4 + (i / cols) * cell, cell - 2, cell - 2)) {
					setProp(paletteProp, (double) (PALETTE[i] & 0xFFFFFFFFL));
					break;
				}
			}
			paletteProp = null;
			return true;
		}
		if (!Ui.in(mx, my, x, y, w, h)) {
			return false;
		}
		unfocus();
		if (my < y + TITLE_H) {
			draggingPanel = true;
			dragDx = (int) mx - x;
			dragDy = (int) my - y;
			return true;
		}
		int ry = bodyY() + 4 - (int) scroll;
		for (Row r : rows) {
			if (my >= ry && my < ry + ROW_H) {
				clickRow(r, ry, mx, my, shift, dbl);
				break;
			}
			ry += ROW_H;
		}
		return true;
	}

	private void clickRow(Row r, int ry, double mx, double my, boolean shift, boolean dbl) {
		int fx = x + 8 + LABEL_W;
		int fw = w - LABEL_W - 20;
		for (CodeArea a : new CodeArea[] { r.a, r.b }) {
			if (a != null && a.contains(mx, my)) {
				a.focused = true;
				a.mouseClicked(mx, my, shift, dbl);
				focusedArea = a;
				focusedRow = r;
				return;
			}
		}
		switch (r.kind) {
			case BOOL -> setProp(r.prop, !Boolean.TRUE.equals(el.get(r.prop)));
			case COLOR -> {
				if (mx < fx + 26) {
					paletteProp = r.prop;
					palX = Math.max(0, Math.min(fx, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 8 * 18 - 8));
					palY = (int) Math.min(ry + ROW_H, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 3 * 18 - 8);
				}
			}
			case ALIGN_X, ALIGN_Y -> {
				int bw = (fw - 4) / 3;
				for (int i = 0; i < 3; i++) {
					if (Ui.in(mx, my, fx + i * (bw + 2), ry + 2, bw, 17)) {
						setProp(r.prop, i * 0.5);
					}
				}
			}
			case TEXTURE -> {
				if (mx >= fx + fw - 20) {
					ContextMenu m = new ContextMenu();
					m.add("(none)", true, () -> setTexture(r, ""));
					for (String f : GuiStore.textureFiles()) {
						m.add(f, true, () -> setTexture(r, f));
					}
					m.separator();
					m.add("minecraft:textures/item/diamond.png", true, () -> setTexture(r, "minecraft:textures/item/diamond.png"));
					m.add("Put .png files in kcoder/localstorage/textures", false, null);
					openMenu.accept(m);
				}
			}
			default -> {
			}
		}
	}

	private void setTexture(Row r, String id) {
		setProp("texture", id);
		sync(r, true);
	}

	void mouseDragged(double mx, double my, int sw, int sh) {
		if (draggingPanel) {
			x = (int) mx - dragDx;
			y = (int) my - dragDy;
			clampToScreen(sw, sh);
		} else if (focusedArea != null) {
			focusedArea.mouseDragged(mx, my);
		}
	}

	void mouseReleased() {
		draggingPanel = false;
		if (focusedArea != null) {
			focusedArea.mouseReleased();
		}
	}

	boolean mouseScrolled(double mx, double my, double dy) {
		if (el == null || !Ui.in(mx, my, x, y, w, h)) {
			return false;
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll - dy * ROW_H));
		return true;
	}

	boolean keyPressed(KeyEvent e) {
		if (focusedArea == null) {
			return false;
		}
		if (e.key() == InputConstants.KEY_ESCAPE) {
			unfocus();
			return true;
		}
		if (e.key() == InputConstants.KEY_TAB && focusedRow != null) {
			int i = rows.indexOf(focusedRow);
			CodeArea next = null;
			if (focusedRow.b != null && focusedArea == focusedRow.a) {
				next = focusedRow.b;
			} else {
				for (int k = 1; k <= rows.size() && next == null; k++) {
					Row cand = rows.get((i + k) % rows.size());
					if (cand.a != null) {
						next = cand.a;
						focusedRow = cand;
					}
				}
			}
			focusedArea.focused = false;
			focusedArea = next;
			if (next != null) {
				next.focused = true;
			}
			return true;
		}
		return focusedArea.keyPressed(e);
	}

	boolean charTyped(CharacterEvent e) {
		return focusedArea != null && focusedArea.charTyped(e);
	}
}
