package khoa.script.client.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * A multi-line code editor: syntax colors, line numbers, selection, undo/redo, auto indent, paste of very long text,
 * and a completion popup (Ctrl+Space or just type). Also used as the console input and, in single-line mode, as a text field.
 */
public final class CodeArea {
	private record Snap(String text, int row, int col) {
	}

	public int x;
	public int y;
	public int w;
	public int h;
	public boolean singleLine;
	public boolean lineNumbers = true;
	public boolean suggestions = true;
	public boolean focused;
	/** Used for script text boxes: no syntax colors, own colors. */
	public boolean plainText;
	public int fill = 0xFF0B0E13;
	public int borderColor = Ui.BORDER;
	public int textColor = Ui.TEXT;
	public String placeholder = "";
	public Runnable onChange = () -> {
	};
	public Runnable onSave = () -> {
	};
	/** Return true to consume Enter (console: run the code). */
	public BooleanSupplier onEnter = () -> false;
	/** Called for Up/Down at the first/last line (console history). */
	public java.util.function.IntConsumer onHistory = d -> {
	};
	/** Line (0-based) to message: shown as a red number and a red underline. */
	public Map<Integer, String> errors = new HashMap<>();

	private List<String> lines = new ArrayList<>(List.of(""));
	private int cr;
	private int cc;
	private int ar;
	private int ac;
	private int wantCol = -1;
	private double scrollY;
	private double scrollX;
	private boolean dragging;
	private final Deque<Snap> undo = new ArrayDeque<>();
	private final Deque<Snap> redo = new ArrayDeque<>();
	private long lastEdit;
	private char lastKind;
	private boolean changed;

	// find / replace
	private FindBar findBar;

	private FindBar findBar() {
		if (findBar == null) {
			findBar = new FindBar(this);
		}
		return findBar;
	}

	int revision() {
		return rev;
	}

	/** {row, col} of the start of the selection (or of the caret). */
	int[] selectionStart() {
		boolean fwd = ar < cr || (ar == cr && ac < cc);
		return fwd ? new int[] { ar, ac } : new int[] { cr, cc };
	}

	void selectRange(int row, int col, int len) {
		ar = row;
		ac = col;
		cr = row;
		cc = col + len;
		wantCol = -1;
		reveal();
	}

	// completion popup
	private Suggest.Result popup;
	private int sel;
	private int popTop;
	private boolean popOpen;

	public CodeArea(boolean singleLine) {
		this.singleLine = singleLine;
		if (singleLine) {
			lineNumbers = false;
			suggestions = false;
		}
	}

	public CodeArea bounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	// ------------------------------------------------------------ text access
	public String getText() {
		return String.join("\n", lines);
	}

	public List<String> lines() {
		return lines;
	}

	public void setText(String t) {
		lines = new ArrayList<>(List.of(normalize(t, false).split("\n", -1)));
		cr = 0;
		cc = 0;
		ar = 0;
		ac = 0;
		scrollX = 0;
		scrollY = 0;
		undo.clear();
		redo.clear();
		closePopup();
	}

	public boolean isEmpty() {
		return lines.size() == 1 && lines.get(0).isEmpty();
	}

	public int row() {
		return cr;
	}

	public int col() {
		return cc;
	}

	public void goTo(int row, int col) {
		cr = Math.max(0, Math.min(lines.size() - 1, row));
		cc = Math.max(0, Math.min(lines.get(cr).length(), col));
		ar = cr;
		ac = cc;
		unfoldFor(cr);
		double target = visIndex(cr) * Ui.LH - (h - 12) / 2.0;
		scrollY = Math.max(0, target);
		reveal();
	}

	/** True once since the last call if the text was edited by the user. */
	public boolean takeChanged() {
		boolean c = changed;
		changed = false;
		return c;
	}

	private static String normalize(String t, boolean single) {
		String s = t.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
		if (single) {
			s = s.replace('\n', ' ');
		}
		return s;
	}

	// ------------------------------------------------------------ geometry
	private int gutter() {
		return lineNumbers ? Ui.width(String.valueOf(Math.max(99, lines.size()))) + (singleLine ? 14 : 28) : 4;
	}

	// ------------------------------------------------------------ folding and debugger gutter
	/** Rows whose block is folded away (the row itself stays visible). */
	private final java.util.Set<Integer> folded = new java.util.TreeSet<>();
	private int foldSize;
	private int[] visCache = new int[0];
	private String visKey = "";
	/** True when a breakpoint is set on this row (set by the screen). */
	public java.util.function.IntPredicate hasBreakpoint = r -> false;
	/** Called with the row when the breakpoint area of the gutter is clicked. */
	public java.util.function.IntConsumer onBreakpointClick;
	/** Row where a paused script waits, or -1. */
	public int pausedRow = -1;
	/** Text of the last line copied with nothing selected (pasted as a whole line). */
	private String copiedLine = "\u0001";

	private int indentOf(int r) {
		String t = lines.get(r);
		int i = 0;
		while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) {
			i++;
		}
		return i;
	}

	/** Last row that folding {@code r} would hide, or r itself when the row has no indented block under it. */
	private int foldEnd(int r) {
		if (lines.get(r).isBlank()) {
			return r;
		}
		int ind = indentOf(r);
		int last = r;
		for (int i = r + 1; i < lines.size(); i++) {
			if (lines.get(i).isBlank()) {
				continue;
			}
			if (indentOf(i) <= ind) {
				break;
			}
			last = i;
		}
		return last;
	}

	private boolean foldable(int r) {
		return !singleLine && lineNumbers && foldEnd(r) > r;
	}

	/** Row numbers that are drawn, top to bottom (folded blocks are skipped). */
	private int[] vis() {
		if (!folded.isEmpty() && lines.size() != foldSize) {
			folded.clear();
		}
		String key = System.identityHashCode(lines) + ":" + rev + ":" + lines.size() + ":" + folded.hashCode();
		if (key.equals(visKey)) {
			return visCache;
		}
		visKey = key;
		if (singleLine || folded.isEmpty()) {
			int[] all = new int[lines.size()];
			for (int i = 0; i < all.length; i++) {
				all[i] = i;
			}
			return visCache = all;
		}
		java.util.List<Integer> out = new ArrayList<>();
		for (int r = 0; r < lines.size(); r++) {
			out.add(r);
			if (folded.contains(r)) {
				r = foldEnd(r);
			}
		}
		int[] arr = new int[out.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = out.get(i);
		}
		return visCache = arr;
	}

	/** Index in {@link #vis()} of a row (a hidden row maps to the row that folds it). */
	private int visIndex(int row) {
		int[] v = vis();
		int lo = 0;
		int hi = v.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (v[mid] <= row) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	private int rowAtY(double my) {
		int[] v = vis();
		return v[Math.max(0, Math.min(v.length - 1, (int) ((my - y - 2 + scrollY) / Ui.LH)))];
	}

	/** Opens any fold that hides the given row. */
	private void unfoldFor(int row) {
		folded.removeIf(f -> f < row && row <= foldEnd(f));
	}

	public void toggleFold(int row) {
		if (folded.contains(row)) {
			folded.remove(row);
		} else if (foldable(row)) {
			folded.add(row);
			foldSize = lines.size();
			if (cr > row && cr <= foldEnd(row)) {
				cr = row;
				cc = Math.min(cc, lines.get(row).length());
				ar = cr;
				ac = cc;
			}
		}
	}

	/** Folds every top-level block, or opens all folds. */
	public void foldAll(boolean fold) {
		folded.clear();
		foldSize = lines.size();
		if (fold) {
			for (int r = 0; r < lines.size(); r++) {
				if (indentOf(r) == 0 && foldable(r)) {
					folded.add(r);
				}
			}
			if (cr > 0) {
				cr = vis()[visIndex(cr)];
				cc = Math.min(cc, lines.get(cr).length());
				ar = cr;
				ac = cc;
			}
		}
	}

	private int textX() {
		return x + gutter();
	}

	private int viewH() {
		return h;
	}

	private double maxScrollY() {
		return Math.max(0, vis().length * Ui.LH + 4 - viewH());
	}

	private int xOf(int row, int col) {
		String s = lines.get(row);
		return Ui.width(s.substring(0, Math.min(col, s.length())));
	}

	private int colAt(int row, double px) {
		String s = lines.get(row);
		if (px <= 0) {
			return 0;
		}
		int lo = 0;
		int hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (Ui.width(s.substring(0, mid)) <= px) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		if (lo < s.length() && px - Ui.width(s.substring(0, lo)) > Ui.width(s.substring(lo, lo + 1)) / 2.0) {
			lo++;
		}
		return lo;
	}

	/** Top pixel of a text row. A one-line field centres its text vertically and never scrolls vertically. */
	private int rowY(int row) {
		if (singleLine) {
			// capital letters are 7 pixels tall inside the 9 pixel line, so +1 puts their middle on the box's middle
			return y + Math.max(1, (h - 9) / 2 + 1);
		}
		return (int) (y + 2 + visIndex(row) * Ui.LH - scrollY);
	}

	private void reveal() {
		if (w <= gutter() + 24 || h <= 0) {
			// not laid out yet (bounds are set while rendering): scrolling now would push the text out of the box
			return;
		}
		unfoldFor(cr);
		double top = visIndex(cr) * Ui.LH;
		if (top < scrollY) {
			scrollY = top;
		} else if (top + Ui.LH + 4 > scrollY + viewH()) {
			scrollY = top + Ui.LH + 4 - viewH();
		}
		scrollY = Math.max(0, Math.min(scrollY, maxScrollY()));
		int cx = xOf(cr, cc);
		int vw = w - gutter() - 10;
		if (cx < scrollX) {
			scrollX = Math.max(0, cx - 20);
		} else if (cx > scrollX + vw) {
			scrollX = cx - vw + 20;
		}
	}

	// ------------------------------------------------------------ rendering
	public void render(GuiGraphicsExtractor g, int mx, int my) {
		if (singleLine) {
			scrollY = 0;
		}
		if (scrollX > 0 && xOf(cr, cc) <= w - gutter() - 10) {
			scrollX = 0;
		}
		Ui.panel(g, x, y, w, h, fill, focused ? Ui.alpha(Ui.ACCENT, 200) : borderColor);
		if (lineNumbers) {
			Ui.fill(g, x + 1, y + 1, gutter() - 1, h - 2, 0xFF10141B);
		}
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int[] vv = vis();
		int first = Math.max(0, (int) (scrollY / Ui.LH));
		int last = Math.min(vv.length - 1, (int) ((scrollY + h) / Ui.LH));
		int tx = textX();
		boolean fwd = ar < cr || (ar == cr && ac < cc);
		int sr1 = fwd ? ar : cr;
		int sc1 = fwd ? ac : cc;
		int sr2 = fwd ? cr : ar;
		int sc2 = fwd ? cc : ac;
		boolean hasSel = ar != cr || ac != cc;
		if (isEmpty() && !focused && !placeholder.isEmpty()) {
			Ui.text(g, placeholder, tx + 2, singleLine ? rowY(0) : y + 3, Ui.FAINT);
		}
		for (int vi = first; vi <= last; vi++) {
			int r = vv[vi];
			int ly = singleLine ? rowY(r) : (int) (y + 2 + vi * Ui.LH - scrollY);
			String s = lines.get(r);
			if (r == pausedRow) {
				Ui.fill(g, x + 1, ly - 1, w - 2, Ui.LH, 0x55FFC857);
			}
			if (r == cr && focused && !singleLine) {
				Ui.fill(g, tx, ly - 1, w - gutter() - 1, Ui.LH, 0x14FFFFFF);
			}
			if (errors.containsKey(r)) {
				Ui.fill(g, x + 1, ly - 1, w - 2, Ui.LH, 0x26FF5F5F);
			}
			if (findBar != null) {
				for (int[] m : findBar.matchesOnRow(r)) {
					int mx1 = tx + xOf(r, m[1]) - (int) scrollX;
					int mx2 = tx + xOf(r, m[1] + m[2]) - (int) scrollX;
					Ui.fill(g, mx1, ly - 1, Math.max(2, mx2 - mx1), Ui.LH, 0x66FFC857);
				}
			}
			if (hasSel && r >= sr1 && r <= sr2) {
				int a = r == sr1 ? sc1 : 0;
				int b = r == sr2 ? sc2 : s.length();
				int sx1 = tx + xOf(r, a) - (int) scrollX;
				int sx2 = tx + xOf(r, b) - (int) scrollX + (r < sr2 ? 4 : 0);
				Ui.fill(g, sx1, ly - 1, Math.max(2, sx2 - sx1), Ui.LH, 0x884C8DFF);
			}
			if (lineNumbers) {
				String num = String.valueOf(r + 1);
				boolean err = errors.containsKey(r);
				Ui.textRight(g, num, x + gutter() - (singleLine ? 7 : 17), ly, err ? Ui.RED : r == cr ? Ui.TEXT : Ui.FAINT);
				if (!singleLine) {
					if (hasBreakpoint.test(r)) {
						Ui.fill(g, x + 4, ly + 1, 7, 7, Ui.RED);
					} else if (r == pausedRow) {
						Ui.text(g, ">", x + 4, ly, Ui.YELLOW);
					}
					if (folded.contains(r)) {
						Ui.text(g, ">", x + gutter() - 11, ly, Ui.YELLOW);
					} else if (foldable(r)) {
						Ui.text(g, "v", x + gutter() - 11, ly, Ui.FAINT);
					}
				}
			}
			int cx = tx - (int) scrollX;
			int done = 0;
			for (Highlighter.Span sp : plainText ? List.of(new Highlighter.Span(0, s.length(), textColor)) : Highlighter.line(s)) {
				if (sp.start() > done) {
					String gap = s.substring(done, sp.start());
					cx += Ui.width(gap);
				}
				String part = s.substring(sp.start(), sp.end());
				if (cx < x + w && cx + Ui.width(part) > x) {
					g.text(Ui.font(), part, cx, ly, sp.color());
					if (sp.color() == Highlighter.TRUE || sp.color() == Highlighter.FALSE) {
						g.text(Ui.font(), part, cx + 1, ly, sp.color());
					}
				}
				cx += Ui.width(part);
				done = sp.end();
			}
			if (errors.containsKey(r)) {
				Ui.fill(g, tx - (int) scrollX, ly + Ui.LH - 3, Math.max(8, Ui.width(s)), 1, Ui.RED);
			}
			if (folded.contains(r)) {
				int hidden = foldEnd(r) - r;
				String tag = " ... " + hidden + (hidden == 1 ? " line" : " lines");
				Ui.fill(g, cx + 2, ly - 1, Ui.width(tag) + 4, Ui.LH, 0x33FFFFFF);
				Ui.text(g, tag, cx + 4, ly, Ui.DIM);
			}
		}
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = tx + xOf(cr, cc) - (int) scrollX;
			int cy = rowY(cr);
			Ui.fill(g, cx, cy - 1, 1, Ui.LH, 0xFFFFFFFF);
		}
		g.disableScissor();
		if (!singleLine && maxScrollY() > 0) {
			int th = Math.max(16, (int) (h * (h / (double) (vv.length * Ui.LH + 4))));
			int ty = y + 1 + (int) ((h - 2 - th) * (scrollY / maxScrollY()));
			Ui.fill(g, x + w - 4, ty, 3, th, 0x66FFFFFF);
		}
		if (suggestions && !singleLine && focused) {
			renderSignature(g);
		}
		if (popOpen) {
			renderPopup(g, mx, my);
		}
		if (findBar != null) {
			findBar.render(g, mx, my);
		}
	}

	// ------------------------------------------------------------ parameter hints
	private int rev;
	private String sigKey = "";
	private SignatureHelp.Sig sig;

	private SignatureHelp.Sig currentSignature() {
		String key = rev + ":" + cr + ":" + cc + ":" + lines.size() + ":" + lines.get(cr).hashCode();
		if (!key.equals(sigKey)) {
			sigKey = key;
			sig = SignatureHelp.at(lines, cr, cc);
		}
		return sig;
	}

	/** The bar above the open parenthesis: name(arg: type, arg: type) with the argument under the cursor highlighted. It scrolls so that argument stays visible. */
	private void renderSignature(GuiGraphicsExtractor g) {
		SignatureHelp.Sig s = currentSignature();
		if (s == null) {
			return;
		}
		String head = s.name() + "(";
		List<String> parts = new ArrayList<>();
		int total = Ui.width(head);
		int activeStart = 0;
		int activeEnd = 0;
		for (int i = 0; i < s.params().size(); i++) {
			String p = s.params().get(i);
			if (i == s.active()) {
				activeStart = total;
				activeEnd = total + Ui.width(p);
			}
			total += Ui.width(p) + (i < s.params().size() - 1 ? Ui.width(", ") : 0);
			parts.add(p);
		}
		String tail = ")" + (s.returns().isEmpty() || s.returns().equals("nil") ? "" : "  ->  " + s.returns());
		total += Ui.width(tail);
		int maxW = Math.max(120, Math.min(w - 16, 620));
		int boxW = Math.min(maxW, total + 16);
		int shift = activeEnd > boxW - 16 ? Math.min(activeEnd - (boxW - 16), Math.max(0, total - (boxW - 16))) : 0;
		if (activeStart - shift < 0) {
			shift = activeStart;
		}
		boolean hasDoc = !s.doc().isEmpty();
		// only the first sentence, at most 2 lines: the whole text is in the completion popup and the reference, and a tall box hides the code
		int dot = s.doc().indexOf(". ");
		String shortDoc = dot > 0 ? s.doc().substring(0, dot + 1) : s.doc();
		java.util.List<String> docLines = hasDoc ? Ui.wrap(shortDoc, boxW - 16, 2) : java.util.List.of();
		int boxH = Ui.LH + 8 + docLines.size() * (Ui.LH + 1);
		int ax = textX() + xOf(s.row(), s.col()) - (int) scrollX;
		int bx = Math.max(x + 4, Math.min(ax - 8, x + w - boxW - 4));
		int by = rowY(s.row()) - boxH - 3;
		if (by < y + 2) {
			by = rowY(cr) + Ui.LH + 3;
		}
		if (by + boxH > y + h) {
			by = Math.max(y + 2, y + h - boxH - 2);
		}
		Ui.panel(g, bx, by, boxW, boxH, 0xF2141A24, Ui.ACCENT);
		g.enableScissor(bx + 1, by + 1, bx + boxW - 1, by + boxH - 1);
		int cx = bx + 8 - shift;
		int ty = by + 4;
		Ui.text(g, s.name(), cx, ty, Highlighter.CALL);
		cx += Ui.width(s.name());
		Ui.text(g, "(", cx, ty, Ui.DIM);
		cx += Ui.width("(");
		for (int i = 0; i < parts.size(); i++) {
			String p = parts.get(i);
			int pw = Ui.width(p);
			if (i == s.active()) {
				Ui.fill(g, cx - 2, ty - 2, pw + 4, Ui.LH + 1, 0x554C8DFF);
				Ui.text(g, p, cx, ty, Ui.TEXT);
			} else {
				Ui.text(g, p, cx, ty, Ui.DIM);
			}
			cx += pw;
			if (i < parts.size() - 1) {
				Ui.text(g, ", ", cx, ty, Ui.FAINT);
				cx += Ui.width(", ");
			}
		}
		Ui.text(g, tail, cx, ty, Ui.FAINT);
		if (hasDoc) {
			for (int i = 0; i < docLines.size(); i++) {
				Ui.text(g, docLines.get(i), bx + 8, ty + (Ui.LH + 1) * (i + 1), Ui.FAINT);
			}
		}
		g.disableScissor();
	}

	// ------------------------------------------------------------ completion popup
	private static final int POP_ROWS = 8;
	private static final int POP_ROW = 14;
	private static final int POP_W = 380;

	private int popX() {
		return Math.max(x, Math.min(textX() + xOf(cr, popup.start) - (int) scrollX, x + w - POP_W));
	}

	/** The selected item's description, word-wrapped so none of it is cut off. */
	private java.util.List<String> popDoc() {
		Suggest.Item cur = popup.items.get(Math.min(sel, popup.items.size() - 1));
		return Ui.wrap(cur.doc() == null ? "" : cur.doc(), POP_W - 12, 6);
	}

	private int popH() {
		return Math.min(POP_ROWS, popup.items.size()) * POP_ROW + 4 + 18 + Math.max(1, popDoc().size()) * 11;
	}

	private int popY() {
		int cy = rowY(cr);
		int below = cy + Ui.LH + 2;
		int screenH = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
		return below + popH() > screenH - 4 ? Math.max(2, cy - popH() - 2) : below;
	}

	private void renderPopup(GuiGraphicsExtractor g, int mx, int my) {
		int px = popX();
		int py = popY();
		int rows = Math.min(POP_ROWS, popup.items.size());
		Ui.panel(g, px + 2, py + 2, POP_W, popH(), 0x66000000, 0);
		Ui.panel(g, px, py, POP_W, popH(), Ui.PANEL2, Ui.BORDER);
		for (int i = 0; i < rows; i++) {
			int idx = popTop + i;
			Suggest.Item it = popup.items.get(idx);
			int ry = py + 2 + i * POP_ROW;
			boolean hover = Ui.in(mx, my, px + 1, ry, POP_W - 2, POP_ROW);
			if (idx == sel) {
				Ui.fill(g, px + 1, ry, POP_W - 2, POP_ROW, Ui.alpha(Ui.ACCENT, 110));
			} else if (hover) {
				Ui.fill(g, px + 1, ry, POP_W - 2, POP_ROW, 0x22FFFFFF);
			}
			Ui.badge(g, px + 3, ry + 1, it.kind().icon, it.kind().color);
			Ui.text(g, Ui.fit(it.label(), 170), px + 19, ry + 3, Ui.TEXT);
			Ui.textRight(g, Ui.fit(it.category(), 80), px + POP_W - 6, ry + 3, Ui.DIM);
			String det = it.detail() == null ? "" : it.detail();
			int lx = px + 19 + Math.min(170, Ui.width(it.label())) + 8;
			int room = px + POP_W - 90 - lx;
			if (room > 30 && !det.equals(it.label())) {
				Ui.text(g, Ui.fit(det, room), lx, ry + 3, Ui.FAINT);
			}
		}
		if (popup.items.size() > rows) {
			int th = Math.max(8, rows * POP_ROW * rows / popup.items.size());
			int ty = py + 2 + (int) ((rows * POP_ROW - th) * (popTop / (double) (popup.items.size() - rows)));
			Ui.fill(g, px + POP_W - 3, ty, 2, th, 0x66FFFFFF);
		}
		int dy = py + 2 + rows * POP_ROW + 2;
		Ui.fill(g, px + 1, dy, POP_W - 2, 1, Ui.BORDER);
		Suggest.Item cur = popup.items.get(sel);
		Ui.text(g, Ui.fit(cur.detail() == null ? "" : cur.detail(), POP_W - 12), px + 6, dy + 3, Ui.ACCENT);
		java.util.List<String> docLines = popDoc();
		for (int i = 0; i < docLines.size(); i++) {
			Ui.text(g, docLines.get(i), px + 6, dy + 14 + i * 11, Ui.DIM);
		}
	}

	private void closePopup() {
		popOpen = false;
		popup = null;
	}

	private void updatePopup(boolean force) {
		if (!suggestions || singleLine) {
			return;
		}
		Suggest.Result r = Suggest.compute(lines, cr, cc, force);
		if (r.items.isEmpty()) {
			closePopup();
			return;
		}
		popup = r;
		popOpen = true;
		sel = 0;
		popTop = 0;
	}

	private void movePopup(int d) {
		sel = Math.max(0, Math.min(popup.items.size() - 1, sel + d));
		if (sel < popTop) {
			popTop = sel;
		} else if (sel >= popTop + POP_ROWS) {
			popTop = sel - POP_ROWS + 1;
		}
	}

	private void accept(int index) {
		if (popup == null || index < 0 || index >= popup.items.size()) {
			return;
		}
		Suggest.Item it = popup.items.get(index);
		int start = popup.start;
		closePopup();
		pushUndo('x');
		String line = lines.get(cr);
		String indent = line.substring(0, line.length() - line.stripLeading().length());
		String ins = it.insert();
		// do not duplicate "(" when it is already there
		String rest = line.substring(Math.min(cc, line.length()));
		int back = it.back();
		if (ins.endsWith("()") && rest.startsWith("(")) {
			ins = ins.substring(0, ins.length() - 2);
			back = 0;
		}
		String[] parts = ins.split("\n", -1);
		String head = line.substring(0, start);
		String tail = rest;
		if (parts.length == 1) {
			lines.set(cr, head + ins + tail);
			cc = start + ins.length() - back;
		} else {
			boolean opens = parts[0].stripTrailing().endsWith(":");
			lines.set(cr, head + parts[0]);
			int r = cr;
			for (int i = 1; i < parts.length; i++) {
				String add = indent + (opens || i > 1 ? "    " : "") + parts[i].stripLeading();
				if (parts[i].startsWith("    ")) {
					add = indent + parts[i];
				}
				lines.add(++r, add);
			}
			lines.set(r, lines.get(r) + tail);
			cr = r;
			cc = lines.get(r).length() - tail.length();
		}
		ar = cr;
		ac = cc;
		touch();
	}

	// ------------------------------------------------------------ editing primitives
	private void pushUndo(char kind) {
		long now = System.currentTimeMillis();
		if (kind == 'c' && lastKind == 'c' && now - lastEdit < 700) {
			lastEdit = now;
			redo.clear();
			return;
		}
		undo.push(new Snap(getText(), cr, cc));
		if (undo.size() > 300) {
			undo.removeLast();
		}
		redo.clear();
		lastKind = kind;
		lastEdit = now;
	}

	private void touch() {
		changed = true;
		rev++;
		reveal();
		onChange.run();
	}

	boolean hasSelection() {
		return ar != cr || ac != cc;
	}

	private String selectedText() {
		if (!hasSelection()) {
			return "";
		}
		boolean fwd = ar < cr || (ar == cr && ac < cc);
		int r1 = fwd ? ar : cr;
		int c1 = fwd ? ac : cc;
		int r2 = fwd ? cr : ar;
		int c2 = fwd ? cc : ac;
		if (r1 == r2) {
			return lines.get(r1).substring(c1, c2);
		}
		StringBuilder sb = new StringBuilder(lines.get(r1).substring(c1));
		for (int r = r1 + 1; r < r2; r++) {
			sb.append('\n').append(lines.get(r));
		}
		return sb.append('\n').append(lines.get(r2), 0, c2).toString();
	}

	private void deleteSelection() {
		if (!hasSelection()) {
			return;
		}
		boolean fwd = ar < cr || (ar == cr && ac < cc);
		int r1 = fwd ? ar : cr;
		int c1 = fwd ? ac : cc;
		int r2 = fwd ? cr : ar;
		int c2 = fwd ? cc : ac;
		String head = lines.get(r1).substring(0, c1);
		String tail = lines.get(r2).substring(c2);
		for (int r = r2; r > r1; r--) {
			lines.remove(r);
		}
		lines.set(r1, head + tail);
		cr = r1;
		cc = c1;
		ar = cr;
		ac = cc;
	}

	/** Inserts text at the cursor (replacing the selection). Long pastes keep working: only the lines touched are rebuilt. */
	public void insert(String raw) {
		String t = normalize(raw, singleLine);
		if (t.isEmpty()) {
			return;
		}
		deleteSelection();
		String line = lines.get(cr);
		String head = line.substring(0, cc);
		String tail = line.substring(cc);
		String[] parts = t.split("\n", -1);
		if (parts.length == 1) {
			lines.set(cr, head + t + tail);
			cc += t.length();
		} else {
			lines.set(cr, head + parts[0]);
			List<String> add = new ArrayList<>(parts.length);
			for (int i = 1; i < parts.length; i++) {
				add.add(parts[i]);
			}
			add.set(add.size() - 1, add.get(add.size() - 1) + tail);
			lines.addAll(cr + 1, add);
			cr += parts.length - 1;
			cc = parts[parts.length - 1].length();
		}
		ar = cr;
		ac = cc;
	}

	private void newline() {
		deleteSelection();
		String line = lines.get(cr);
		String head = line.substring(0, cc);
		String tail = line.substring(cc);
		String indent = head.substring(0, head.length() - head.stripLeading().length());
		if (head.stripTrailing().endsWith(":")) {
			indent += "    ";
		}
		lines.set(cr, head);
		lines.add(cr + 1, indent + tail.stripLeading());
		cr++;
		cc = indent.length();
		ar = cr;
		ac = cc;
	}

	private void moveTo(int r, int c, boolean extend) {
		cr = Math.max(0, Math.min(lines.size() - 1, r));
		cc = Math.max(0, Math.min(lines.get(cr).length(), c));
		if (!extend) {
			ar = cr;
			ac = cc;
		}
		reveal();
	}

	// ------------------------------------------------------------ input
	public boolean charTyped(CharacterEvent e) {
		if (findBar != null && findBar.open && findBar.active != null) {
			return findBar.chr(e);
		}
		if (!focused) {
			return false;
		}
		int cp = e.codepoint();
		if (cp < 32 || cp == 127) {
			return true;
		}
		pushUndo('c');
		String s = new String(Character.toChars(cp));
		insert(s);
		touch();
		if (suggestions) {
			if (Character.isLetterOrDigit(cp) || cp == '_' || cp == '.' || cp == '"' || cp == '+' || cp == '/' || cp == '\\' || cp == ':'
				|| (cp == ' ' && lines.get(cr).substring(0, cc).matches(".*\\bimplement\\s+$"))) {
				updatePopup(false);
			} else {
				closePopup();
			}
		}
		return true;
	}

	public boolean keyPressed(KeyEvent e) {
		if (findBar != null && findBar.open && findBar.active != null && findBar.key(e)) {
			return true;
		}
		if (!focused) {
			return false;
		}
		int k = e.key();
		boolean ctrl = e.hasControlDown();
		boolean shift = e.hasShiftDown();
		if (!singleLine && suggestions) {
			if (ctrl && (k == InputConstants.KEY_F || k == InputConstants.KEY_H)) {
				closePopup();
				findBar().show(k == InputConstants.KEY_H, selectedText());
				return true;
			}
			if (ctrl && shift && k == InputConstants.KEY_LBRACKET) {
				int head = cr;
				while (head > 0 && !foldable(head)) {
					head--;
				}
				toggleFold(head);
				return true;
			}
			if (ctrl && shift && k == InputConstants.KEY_RBRACKET) {
				foldAll(false);
				return true;
			}
			if (ctrl && shift && k == InputConstants.KEY_MINUS) {
				foldAll(true);
				return true;
			}
			if (k == InputConstants.KEY_F3 && findBar != null && findBar.open) {
				findBar.step(shift ? -1 : 1);
				return true;
			}
			if (k == InputConstants.KEY_ESCAPE && findBar != null && findBar.open && !popOpen) {
				findBar.close();
				return true;
			}
		}
		if (popOpen) {
			if (k == InputConstants.KEY_DOWN) {
				movePopup(1);
				return true;
			}
			if (k == InputConstants.KEY_UP) {
				movePopup(-1);
				return true;
			}
			if (k == InputConstants.KEY_PAGEDOWN) {
				movePopup(POP_ROWS);
				return true;
			}
			if (k == InputConstants.KEY_PAGEUP) {
				movePopup(-POP_ROWS);
				return true;
			}
			if (k == InputConstants.KEY_TAB) {
				accept(sel);
				return true;
			}
			if (k == InputConstants.KEY_ESCAPE) {
				closePopup();
				return true;
			}
		}
		if (ctrl && k == InputConstants.KEY_SPACE && suggestions) {
			updatePopup(true);
			return true;
		}
		if (e.isSelectAll()) {
			ar = 0;
			ac = 0;
			cr = lines.size() - 1;
			cc = lines.get(cr).length();
			return true;
		}
		if (e.isCopy()) {
			if (hasSelection()) {
				Ui.setClipboard(selectedText());
			} else if (!singleLine) {
				copiedLine = lines.get(cr) + "\n";
				Ui.setClipboard(copiedLine);
			}
			return true;
		}
		if (e.isCut()) {
			if (hasSelection()) {
				Ui.setClipboard(selectedText());
				pushUndo('x');
				deleteSelection();
				touch();
			} else if (!singleLine) {
				// nothing selected: cut the whole line the cursor is on
				copiedLine = lines.get(cr) + "\n";
				Ui.setClipboard(copiedLine);
				pushUndo('x');
				lines.remove(cr);
				if (lines.isEmpty()) {
					lines.add("");
				}
				cr = Math.min(cr, lines.size() - 1);
				cc = Math.min(cc, lines.get(cr).length());
				ar = cr;
				ac = cc;
				touch();
			}
			return true;
		}
		if (e.isPaste()) {
			pushUndo('x');
			if (!singleLine && !hasSelection() && Ui.clipboard().equals(copiedLine)) {
				// a line copied without a selection goes in front of the current line
				cc = 0;
				ac = 0;
			}
			insert(Ui.clipboard());
			touch();
			closePopup();
			return true;
		}
		if (ctrl && k == InputConstants.KEY_S) {
			onSave.run();
			return true;
		}
		if (ctrl && k == InputConstants.KEY_Z) {
			if (!undo.isEmpty()) {
				redo.push(new Snap(getText(), cr, cc));
				restore(undo.pop());
			}
			return true;
		}
		if (ctrl && k == InputConstants.KEY_Y) {
			if (!redo.isEmpty()) {
				undo.push(new Snap(getText(), cr, cc));
				restore(redo.pop());
			}
			return true;
		}
		if (ctrl && k == InputConstants.KEY_SLASH && !singleLine) {
			toggleComment();
			return true;
		}
		if (ctrl && k == InputConstants.KEY_D && !singleLine) {
			pushUndo('x');
			lines.add(cr + 1, lines.get(cr));
			cr++;
			ar = cr;
			touch();
			return true;
		}
		switch (k) {
			case InputConstants.KEY_LEFT -> {
				closePopup();
				if (cc > 0) {
					moveTo(cr, ctrl ? wordLeft() : cc - 1, shift);
				} else if (cr > 0) {
					moveTo(cr - 1, lines.get(cr - 1).length(), shift);
				}
				wantCol = -1;
				return true;
			}
			case InputConstants.KEY_RIGHT -> {
				closePopup();
				if (cc < lines.get(cr).length()) {
					moveTo(cr, ctrl ? wordRight() : cc + 1, shift);
				} else if (cr < lines.size() - 1) {
					moveTo(cr + 1, 0, shift);
				}
				wantCol = -1;
				return true;
			}
			case InputConstants.KEY_UP, InputConstants.KEY_DOWN -> {
				int d = k == InputConstants.KEY_UP ? -1 : 1;
				if (singleLine || (cr + d < 0 || cr + d >= lines.size())) {
					onHistory.accept(d);
					return true;
				}
				if (wantCol < 0) {
					wantCol = xOf(cr, cc);
				}
				int nr = cr + d;
				moveTo(nr, colAt(nr, wantCol), shift);
				return true;
			}
			case InputConstants.KEY_HOME -> {
				String s = lines.get(cr);
				int first = s.length() - s.stripLeading().length();
				moveTo(ctrl ? 0 : cr, ctrl ? 0 : (cc == first ? 0 : first), shift);
				return true;
			}
			case InputConstants.KEY_END -> {
				moveTo(ctrl ? lines.size() - 1 : cr, Integer.MAX_VALUE, shift);
				return true;
			}
			case InputConstants.KEY_PAGEUP -> {
				moveTo(cr - Math.max(1, h / Ui.LH - 1), cc, shift);
				return true;
			}
			case InputConstants.KEY_PAGEDOWN -> {
				moveTo(cr + Math.max(1, h / Ui.LH - 1), cc, shift);
				return true;
			}
			case InputConstants.KEY_BACKSPACE -> {
				pushUndo('b');
				if (hasSelection()) {
					deleteSelection();
				} else if (cc > 0) {
					String s = lines.get(cr);
					int n = 1;
					// delete a whole indent step when only spaces are before the cursor
					if (s.substring(0, cc).isBlank() && cc >= 4 && !ctrl) {
						n = cc % 4 == 0 ? 4 : cc % 4;
					} else if (ctrl) {
						n = cc - wordLeft();
					}
					lines.set(cr, s.substring(0, cc - n) + s.substring(cc));
					cc -= n;
				} else if (cr > 0) {
					int len = lines.get(cr - 1).length();
					lines.set(cr - 1, lines.get(cr - 1) + lines.get(cr));
					lines.remove(cr);
					cr--;
					cc = len;
				}
				ar = cr;
				ac = cc;
				touch();
				if (popOpen) {
					updatePopup(false);
				}
				return true;
			}
			case InputConstants.KEY_DELETE -> {
				pushUndo('b');
				if (hasSelection()) {
					deleteSelection();
				} else if (cc < lines.get(cr).length()) {
					String s = lines.get(cr);
					lines.set(cr, s.substring(0, cc) + s.substring(cc + 1));
				} else if (cr < lines.size() - 1) {
					lines.set(cr, lines.get(cr) + lines.get(cr + 1));
					lines.remove(cr + 1);
				}
				touch();
				return true;
			}
			case InputConstants.KEY_RETURN -> {
				if (onEnter.getAsBoolean()) {
					return true;
				}
				if (singleLine) {
					return false;
				}
				pushUndo('x');
				newline();
				touch();
				closePopup();
				return true;
			}
			case InputConstants.KEY_TAB -> {
				if (singleLine) {
					return false;
				}
				pushUndo('x');
				if (hasSelection() && ar != cr) {
					indentSelection(shift);
				} else if (shift) {
					dedentLine(cr);
				} else {
					insert("    ".substring(cc % 4));
				}
				touch();
				return true;
			}
			case InputConstants.KEY_ESCAPE -> {
				if (popOpen) {
					closePopup();
					return true;
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	private void restore(Snap s) {
		rev++;
		lines = new ArrayList<>(List.of(s.text().split("\n", -1)));
		cr = Math.min(s.row(), lines.size() - 1);
		cc = Math.min(s.col(), lines.get(cr).length());
		ar = cr;
		ac = cc;
		lastKind = 0;
		closePopup();
		touch();
	}

	private int wordLeft() {
		String s = lines.get(cr);
		int i = cc;
		while (i > 0 && !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_') {
			i--;
		}
		while (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '_')) {
			i--;
		}
		return i;
	}

	private int wordRight() {
		String s = lines.get(cr);
		int i = cc;
		while (i < s.length() && !Character.isLetterOrDigit(s.charAt(i)) && s.charAt(i) != '_') {
			i++;
		}
		while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
			i++;
		}
		return i;
	}

	private void toggleComment() {
		pushUndo('x');
		int r1 = Math.min(ar, cr);
		int r2 = Math.max(ar, cr);
		boolean allCommented = true;
		for (int r = r1; r <= r2; r++) {
			if (!lines.get(r).stripLeading().startsWith("#") && !lines.get(r).isBlank()) {
				allCommented = false;
			}
		}
		for (int r = r1; r <= r2; r++) {
			String s = lines.get(r);
			int ind = s.length() - s.stripLeading().length();
			if (allCommented) {
				String body = s.substring(ind);
				body = body.startsWith("# ") ? body.substring(2) : body.startsWith("#") ? body.substring(1) : body;
				lines.set(r, s.substring(0, ind) + body);
			} else if (!s.isBlank()) {
				lines.set(r, s.substring(0, ind) + "# " + s.substring(ind));
			}
		}
		cc = Math.min(cc, lines.get(cr).length());
		ac = Math.min(ac, lines.get(ar).length());
		touch();
	}

	private void indentSelection(boolean back) {
		int r1 = Math.min(ar, cr);
		int r2 = Math.max(ar, cr);
		for (int r = r1; r <= r2; r++) {
			if (back) {
				dedentLine(r);
			} else {
				lines.set(r, "    " + lines.get(r));
			}
		}
		cc = Math.min(lines.get(cr).length(), Math.max(0, cc + (back ? -4 : 4)));
		ac = Math.min(lines.get(ar).length(), Math.max(0, ac + (back ? -4 : 4)));
	}

	private void dedentLine(int r) {
		String s = lines.get(r);
		int n = 0;
		while (n < 4 && n < s.length() && s.charAt(n) == ' ') {
			n++;
		}
		lines.set(r, s.substring(n));
		if (r == cr) {
			cc = Math.max(0, cc - n);
			ac = cc;
		}
	}

	// ------------------------------------------------------------ mouse
	public boolean contains(double mx, double my) {
		return Ui.in(mx, my, x, y, w, h);
	}

	public boolean mouseClicked(double mx, double my, boolean shift, boolean doubleClick) {
		if (findBar != null && findBar.open) {
			if (findBar.contains(mx, my)) {
				return findBar.click(mx, my, shift, doubleClick);
			}
			findBar.active = null;
		}
		if (popOpen) {
			int px = popX();
			int py = popY();
			if (Ui.in(mx, my, px, py, POP_W, popH())) {
				int row = (int) ((my - py - 2) / POP_ROW);
				if (row >= 0 && row < Math.min(POP_ROWS, popup.items.size())) {
					sel = popTop + row;
					if (doubleClick) {
						accept(sel);
					}
				}
				return true;
			}
			closePopup();
		}
		if (!contains(mx, my)) {
			focused = false;
			return false;
		}
		focused = true;
		if (lineNumbers && !singleLine && mx < textX() && mx >= x) {
			int gr = rowAtY(my);
			if (mx >= x + gutter() - 14) {
				if (folded.contains(gr) || foldable(gr)) {
					toggleFold(gr);
				}
			} else if (onBreakpointClick != null) {
				onBreakpointClick.accept(gr);
			}
			return true;
		}
		int r = rowAtY(my);
		int c = colAt(r, mx - textX() + scrollX);
		if (doubleClick) {
			String s = lines.get(r);
			int a = c;
			int b = c;
			while (a > 0 && (Character.isLetterOrDigit(s.charAt(a - 1)) || s.charAt(a - 1) == '_')) {
				a--;
			}
			while (b < s.length() && (Character.isLetterOrDigit(s.charAt(b)) || s.charAt(b) == '_')) {
				b++;
			}
			ar = r;
			ac = a;
			cr = r;
			cc = b;
			return true;
		}
		moveTo(r, c, shift);
		dragging = true;
		wantCol = -1;
		return true;
	}

	public void mouseDragged(double mx, double my) {
		if (findBar != null && findBar.open && !dragging) {
			findBar.drag(mx, my);
		}
		if (!dragging) {
			return;
		}
		int r = rowAtY(my);
		moveTo(r, colAt(r, mx - textX() + scrollX), true);
		if (my < y) {
			scrollY = Math.max(0, scrollY - 6);
		} else if (my > y + h) {
			scrollY = Math.min(maxScrollY(), scrollY + 6);
		}
	}

	public void mouseReleased() {
		if (findBar != null) {
			findBar.release();
		}
		dragging = false;
	}

	public boolean mouseScrolled(double mx, double my, double dy) {
		if (popOpen) {
			int px = popX();
			int py = popY();
			if (Ui.in(mx, my, px, py, POP_W, popH())) {
				popTop = (int) Math.max(0, Math.min(Math.max(0, popup.items.size() - POP_ROWS), popTop - Math.signum(dy) * 2));
				return true;
			}
		}
		if (!contains(mx, my)) {
			return false;
		}
		scrollY = Math.max(0, Math.min(maxScrollY(), scrollY - dy * Ui.LH * 3));
		return true;
	}

	/** Moves the cursor sideways on the current line without recentering the view. */
	public void moveCursor(int dcol) {
		moveTo(cr, cc + dcol, false);
	}

	public boolean popupOpen() {
		return popOpen;
	}

	/** The identifier under the cursor (for the documentation strip). */
	public String wordAtCursor() {
		String s = lines.get(cr);
		int a = Math.min(cc, s.length());
		int b = a;
		while (a > 0 && (Character.isLetterOrDigit(s.charAt(a - 1)) || s.charAt(a - 1) == '_')) {
			a--;
		}
		while (b < s.length() && (Character.isLetterOrDigit(s.charAt(b)) || s.charAt(b) == '_')) {
			b++;
		}
		return s.substring(a, b);
	}
}
