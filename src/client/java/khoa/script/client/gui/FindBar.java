package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** Find / replace bar of a CodeArea (Ctrl+F, Ctrl+H): highlights every match, Enter / F3 jump to the next one. Search ignores case. */
final class FindBar {
	private static final int W = 330;
	private static final int MAX_MATCHES = 5000;

	private final CodeArea ed;
	final CodeArea find = new CodeArea(true);
	final CodeArea repl = new CodeArea(true);
	boolean open;
	boolean replaceMode;
	/** The box that has the keyboard, or null when the editor has it. */
	CodeArea active;
	private int x;
	private int y;
	private int h;
	private final List<int[]> matches = new ArrayList<>();
	private final Map<Integer, List<int[]>> byRow = new HashMap<>();
	private String lastQuery = null;
	private int lastRev = -1;
	private boolean jump;

	FindBar(CodeArea ed) {
		this.ed = ed;
		for (CodeArea b : List.of(find, repl)) {
			b.lineNumbers = false;
			b.suggestions = false;
			b.plainText = true;
		}
		find.placeholder = "Find";
		repl.placeholder = "Replace with";
		find.onChange = () -> {
			lastQuery = null;
			jump = true;
		};
		find.onEnter = () -> {
			step(1);
			return true;
		};
		repl.onEnter = () -> {
			replaceOne();
			return true;
		};
	}

	void show(boolean replace, String initial) {
		open = true;
		replaceMode = replace;
		if (initial != null && !initial.isEmpty() && !initial.contains("\n")) {
			find.setText(initial);
		}
		active = find;
		find.focused = true;
		repl.focused = false;
		lastQuery = null;
		jump = true;
	}

	void close() {
		open = false;
		active = null;
		find.focused = false;
		repl.focused = false;
	}

	private void layout() {
		x = ed.x + ed.w - W - 8;
		y = ed.y + 4;
		h = replaceMode ? 42 : 22;
		find.bounds(x + 4, y + 3, 176, 16);
		repl.bounds(x + 4, y + 21, 176, 16);
	}

	private String query() {
		return find.getText();
	}

	/** Rebuilds the match list when the query or the text changed. */
	private void refresh() {
		String q = query();
		if (q.equals(lastQuery) && lastRev == ed.revision()) {
			return;
		}
		lastQuery = q;
		lastRev = ed.revision();
		matches.clear();
		byRow.clear();
		if (!q.isEmpty()) {
			List<String> lines = ed.lines();
			outer:
			for (int r = 0; r < lines.size(); r++) {
				String s = lines.get(r);
				int from = 0;
				while (from <= s.length() - q.length()) {
					if (s.regionMatches(true, from, q, 0, q.length())) {
						int[] m = { r, from, q.length() };
						matches.add(m);
						byRow.computeIfAbsent(r, k -> new ArrayList<>()).add(m);
						if (matches.size() >= MAX_MATCHES) {
							break outer;
						}
						from += Math.max(1, q.length());
					} else {
						from++;
					}
				}
			}
		}
		if (jump) {
			jump = false;
			stepFrom(0, true);
		}
	}

	List<int[]> matchesOnRow(int row) {
		if (!open) {
			return List.of();
		}
		refresh();
		return byRow.getOrDefault(row, List.of());
	}

	private int indexOfCurrent() {
		int[] s = ed.selectionStart();
		for (int i = 0; i < matches.size(); i++) {
			int[] m = matches.get(i);
			if (m[0] == s[0] && m[1] == s[1] && ed.hasSelection()) {
				return i;
			}
		}
		return -1;
	}

	/** Selects the next (dir 1) or previous (dir -1) match relative to the caret. {@code inclusive}: a match that starts exactly at the caret counts. */
	private void stepFrom(int dir, boolean inclusive) {
		if (matches.isEmpty()) {
			return;
		}
		int[] s = ed.selectionStart();
		int pick = -1;
		if (dir >= 0) {
			for (int i = 0; i < matches.size(); i++) {
				int[] m = matches.get(i);
				boolean after = m[0] > s[0] || m[0] == s[0] && (inclusive ? m[1] >= s[1] : m[1] > s[1]);
				if (after) {
					pick = i;
					break;
				}
			}
			if (pick < 0) {
				pick = 0;
			}
		} else {
			for (int i = matches.size() - 1; i >= 0; i--) {
				int[] m = matches.get(i);
				if (m[0] < s[0] || m[0] == s[0] && m[1] < s[1]) {
					pick = i;
					break;
				}
			}
			if (pick < 0) {
				pick = matches.size() - 1;
			}
		}
		int[] m = matches.get(pick);
		ed.selectRange(m[0], m[1], m[2]);
	}

	void step(int dir) {
		refresh();
		stepFrom(dir, false);
	}

	private void replaceOne() {
		refresh();
		int cur = indexOfCurrent();
		if (cur >= 0) {
			ed.insert(repl.getText());
			lastQuery = null;
			refresh();
			stepFrom(1, true);
		} else {
			stepFrom(1, true);
		}
	}

	private void replaceAll() {
		refresh();
		String with = repl.getText();
		List<int[]> all = new ArrayList<>(matches);
		for (int i = all.size() - 1; i >= 0; i--) {
			int[] m = all.get(i);
			ed.selectRange(m[0], m[1], m[2]);
			ed.insert(with);
		}
		lastQuery = null;
	}

	// ------------------------------------------------------------ drawing
	private int[] btn(int i) {
		// prev, next, close, replace, all
		return switch (i) {
			case 0 -> new int[] { x + 230, y + 3, 18, 16 };
			case 1 -> new int[] { x + 250, y + 3, 18, 16 };
			case 2 -> new int[] { x + W - 22, y + 3, 18, 16 };
			case 3 -> new int[] { x + 184, y + 21, 62, 16 };
			default -> new int[] { x + 250, y + 21, 34, 16 };
		};
	}

	private void drawBtn(GuiGraphicsExtractor g, int i, String label, int mx, int my) {
		int[] b = btn(i);
		boolean hot = Ui.in(mx, my, b[0], b[1], b[2], b[3]);
		Ui.panel(g, b[0], b[1], b[2], b[3], hot ? Ui.PANEL3 : Ui.PANEL2, Ui.BORDER);
		Ui.text(g, label, b[0] + (b[2] - Ui.width(label)) / 2, b[1] + 4, Ui.TEXT);
	}

	void render(GuiGraphicsExtractor g, int mx, int my) {
		if (!open) {
			return;
		}
		layout();
		refresh();
		Ui.panel(g, x, y, W, h, 0xF2161A21, Ui.BORDER);
		find.focused = active == find;
		repl.focused = active == repl;
		find.render(g, mx, my);
		String count = query().isEmpty() ? "" : matches.isEmpty() ? "none" : (indexOfCurrent() + 1) + "/" + matches.size();
		Ui.text(g, count, x + 184, y + 7, matches.isEmpty() && !query().isEmpty() ? Ui.RED : Ui.DIM);
		drawBtn(g, 0, "<", mx, my);
		drawBtn(g, 1, ">", mx, my);
		drawBtn(g, 2, "x", mx, my);
		if (replaceMode) {
			repl.render(g, mx, my);
			drawBtn(g, 3, "Replace", mx, my);
			drawBtn(g, 4, "All", mx, my);
		}
	}

	boolean contains(double mx, double my) {
		if (!open) {
			return false;
		}
		layout();
		return Ui.in(mx, my, x, y, W, h);
	}

	boolean click(double mx, double my, boolean shift, boolean dbl) {
		layout();
		for (int i = 0; i < 5; i++) {
			if (i >= 3 && !replaceMode) {
				break;
			}
			int[] b = btn(i);
			if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) {
				switch (i) {
					case 0 -> step(-1);
					case 1 -> step(1);
					case 2 -> {
						close();
						return true;
					}
					case 3 -> replaceOne();
					default -> replaceAll();
				}
				return true;
			}
		}
		if (find.contains(mx, my)) {
			active = find;
			find.mouseClicked(mx, my, shift, dbl);
		} else if (replaceMode && repl.contains(mx, my)) {
			active = repl;
			repl.mouseClicked(mx, my, shift, dbl);
		}
		return true;
	}

	void drag(double mx, double my) {
		if (active != null) {
			active.mouseDragged(mx, my);
		}
	}

	void release() {
		find.mouseReleased();
		repl.mouseReleased();
	}

	boolean key(KeyEvent e) {
		if (active == null) {
			return false;
		}
		int k = e.key();
		if (k == InputConstants.KEY_ESCAPE) {
			close();
			return true;
		}
		if (k == InputConstants.KEY_TAB && replaceMode) {
			active = active == find ? repl : find;
			return true;
		}
		if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) && active == find && e.hasShiftDown()) {
			step(-1);
			return true;
		}
		if (k == InputConstants.KEY_F3) {
			step(e.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (e.hasControlDown() && (k == InputConstants.KEY_F || k == InputConstants.KEY_H)) {
			replaceMode = k == InputConstants.KEY_H || replaceMode;
			return true;
		}
		active.focused = true;
		active.keyPressed(e);
		return true;
	}

	boolean chr(CharacterEvent e) {
		if (active == null) {
			return false;
		}
		active.focused = true;
		return active.charTyped(e);
	}
}
