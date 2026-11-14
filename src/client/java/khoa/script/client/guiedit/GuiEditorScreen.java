package khoa.script.client.guiedit;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import khoa.script.client.game.Keys;
import khoa.script.client.gui.Btn;
import khoa.script.client.gui.ContextMenu;
import khoa.script.client.gui.Ui;
import khoa.script.client.ui.Dim;
import khoa.script.client.ui.UiElement;
import khoa.script.client.ui.UiManager;

/**
 * Visual editor for the GUI saved in {@link GuiStore}. The game stays visible behind it (the layout is drawn by the normal HUD layer, the
 * panels are translucent) and, because this is the open screen, nothing else can be clicked: other mods' menus and the inventory cannot open.
 */
public final class GuiEditorScreen extends Screen {
	private static final int TOOLBAR_H = 20;
	private static final int ROW = 15;
	private static final int HANDLE = 6;
	private static boolean explorerOpen = true;

	private final PropsPanel props = new PropsPanel();
	private final Btn toggleExplorer = new Btn("Explorer", Btn.NORMAL, () -> explorerOpen = !explorerOpen);
	private final Btn addFolder = new Btn("+ Screen Folder", Btn.PRIMARY, () -> select(GuiStore.create("folder", null)));
	private final Btn done = new Btn("Done", Btn.SUCCESS, this::onClose);
	private final List<UiElement> collapsed = new ArrayList<>();
	private final List<Object[]> rowsCache = new ArrayList<>();

	private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<String> redo = new java.util.ArrayDeque<>();
	private String current = GuiStore.snapshot(false);
	private boolean lastMerge;

	private UiElement selected;
	private ContextMenu menu;
	private boolean dirty;
	private double explorerScroll;
	private int exX = 6;
	private int exY = TOOLBAR_H + 8;
	private int exW = 190;
	private int exH = 300;

	// drag state
	private enum Drag {
		NONE, MOVE, RESIZE, EXPLORER_ROW
	}

	private Drag drag = Drag.NONE;
	private int handle;
	/** Panels shrink on a small window (never grow on a big one); lw / lh is the window size measured in panel units. */
	private float k = 1;
	private int lw = 480;
	private int lh = 270;
	private double mouseX;
	private double mouseY;
	private static final int MIN_SIZE = 4;
	/** Mouse position, element rectangle and parent box at the moment a move / resize drag began. */
	private double rsMx;
	private double rsMy;
	private int rsX;
	private int rsY;
	private int rsW;
	private int rsH;
	private double baseX;
	private double baseY;
	private double baseW;
	private double baseH;
	private Dim startX = new Dim(0, 0);
	private Dim startY = new Dim(0, 0);
	private UiElement dragRow;
	private double pressX;
	private double pressY;

	public GuiEditorScreen() {
		super(Component.literal("KCoder GUI editor"));
		props.changed = () -> dirty = true;
		props.openMenu = m -> menu = m.open((int) mouseX, (int) mouseY, lw, lh);
		props.x = 0;
	}

	private void updateScale() {
		k = (float) Math.max(0.5, Math.min(1.0, Math.min(this.width / 560.0, this.height / 320.0)));
		lw = Math.round(this.width / k);
		lh = Math.round(this.height / k);
	}

	@Override
	protected void init() {
		updateScale();
		props.x = lw - props.w - 8;
		props.y = TOOLBAR_H + 8;
		props.h = Math.min(380, lh - TOOLBAR_H - 16);
		exH = lh - TOOLBAR_H - 16;
	}

	private void select(UiElement e) {
		selected = e;
		props.setTarget(e);
	}

	/** Ends an edit: if the layout differs from the last checkpoint, the old state becomes an undo step and the layout is saved. */
	private void commit(boolean merge) {
		String now = GuiStore.snapshot(false);
		if (!now.equals(current)) {
			if (!(merge && lastMerge)) {
				undo.push(current);
				while (undo.size() > 100) {
					undo.removeLast();
				}
			}
			redo.clear();
			current = now;
			lastMerge = merge;
			GuiStore.save();
		}
		dirty = false;
	}

	private String pathOf(UiElement e) {
		StringBuilder sb = new StringBuilder();
		for (UiElement q = e; q != null; q = q.parentElement()) {
			sb.insert(0, "/" + q.get("name"));
		}
		return sb.toString();
	}

	private UiElement find(String path) {
		UiElement cur = null;
		for (String part : path.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			UiElement next = null;
			for (UiElement c : cur == null ? GuiStore.roots() : GuiStore.childrenOf(cur)) {
				if (String.valueOf(c.get("name")).equals(part)) {
					next = c;
				}
			}
			if (next == null) {
				return cur;
			}
			cur = next;
		}
		return cur;
	}

	private void step(java.util.ArrayDeque<String> from, java.util.ArrayDeque<String> to) {
		if (from.isEmpty()) {
			return;
		}
		String keep = selected == null ? null : pathOf(selected);
		to.push(current);
		current = from.pop();
		GuiStore.restore(current);
		GuiStore.save();
		lastMerge = false;
		select(keep == null ? null : find(keep));
		dirty = false;
	}

	// ------------------------------------------------------------ tree
	private void collect(UiElement parent, int depth, List<Object[]> out) {
		for (UiElement c : parent == null ? GuiStore.roots() : GuiStore.childrenOf(parent)) {
			out.add(new Object[] { c, depth });
			if (!collapsed.contains(c)) {
				collect(c, depth + 1, out);
			}
		}
	}

	private List<Object[]> rows() {
		rowsCache.clear();
		collect(null, 0, rowsCache);
		return rowsCache;
	}

	private static int badgeColor(String type) {
		return switch (type) {
			case "folder" -> Ui.YELLOW;
			case "frame" -> Ui.TEAL;
			case "text" -> Ui.ACCENT;
			case "button" -> Ui.GREEN;
			case "image" -> Ui.PURPLE;
			default -> Ui.ORANGE;
		};
	}

	// ------------------------------------------------------------ rendering
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		this.minecraft.onTextInputFocusChange(this, true);
		UiManager.layout();
		updateScale();
		int lmx = (int) (mx / k);
		int lmy = (int) (my / k);
		mouseX = lmx;
		mouseY = lmy;
		if (selected != null && selected.isRemoved()) {
			select(null);
		}
		drawSelection(g);
		g.pose().pushMatrix();
		g.pose().scale(k, k);
		exH = lh - TOOLBAR_H - 16;
		drawToolbar(g, lmx, lmy);
		if (explorerOpen) {
			drawExplorer(g, lmx, lmy);
		}
		props.clampToScreen(lw, lh);
		props.render(g, lmx, lmy);
		if (menu != null) {
			menu.draw(g, lmx, lmy);
		}
		g.pose().popMatrix();
	}

	private void drawToolbar(GuiGraphicsExtractor g, int mx, int my) {
		g.fill(0, 0, lw, TOOLBAR_H, 0xB0101419);
		g.fill(0, TOOLBAR_H, lw, TOOLBAR_H + 1, 0x802B3240);
		toggleExplorer.label = explorerOpen ? "< Explorer" : "> Explorer";
		toggleExplorer.autoWidth();
		toggleExplorer.at(4, 1).size(toggleExplorer.w, 18).draw(g, mx, my);
		addFolder.autoWidth();
		addFolder.at(toggleExplorer.x + toggleExplorer.w + 6, 1).size(addFolder.w, 18).draw(g, mx, my);
		done.autoWidth();
		done.at(lw - done.w - 4, 1).size(done.w, 18).draw(g, mx, my);
		String hint = "Right-click: add / delete   Drag: move   Handles: resize   Del: delete   Ctrl+D: duplicate   Ctrl+Z / Ctrl+Y: undo / redo";
		Ui.text(g, Ui.fit(hint, lw - done.w - addFolder.x - addFolder.w - 24), addFolder.x + addFolder.w + 12, 6, Ui.DIM);
	}

	private void drawExplorer(GuiGraphicsExtractor g, int mx, int my) {
		Ui.panel(g, exX, exY, exW, exH, 0xC8161A21, Ui.BORDER);
		Ui.fill(g, exX + 1, exY + 1, exW - 2, 15, 0xD91C212A);
		Ui.text(g, "Explorer", exX + 6, exY + 5, Ui.TEXT);
		List<Object[]> rows = rows();
		int bodyY = exY + 18;
		int bodyH = exH - 20;
		double max = Math.max(0, rows.size() * ROW - bodyH + 4);
		explorerScroll = Math.max(0, Math.min(explorerScroll, max));
		g.enableScissor(exX + 1, bodyY, exX + exW - 1, exY + exH - 1);
		int y = bodyY + 2 - (int) explorerScroll;
		if (rows.isEmpty()) {
			Ui.text(g, "Empty. Right-click here or press", exX + 8, bodyY + 8, Ui.DIM);
			Ui.text(g, "'+ Screen Folder' to start.", exX + 8, bodyY + 20, Ui.DIM);
		}
		for (Object[] r : rows) {
			UiElement e = (UiElement) r[0];
			int depth = (Integer) r[1];
			if (y + ROW > bodyY && y < exY + exH) {
				boolean sel = e == selected;
				boolean hot = Ui.in(mx, my, exX + 2, y, exW - 4, ROW) && drag != Drag.EXPLORER_ROW;
				if (sel) {
					Ui.fill(g, exX + 2, y, exW - 4, ROW, Ui.alpha(Ui.ACCENT, 110));
				} else if (hot) {
					Ui.fill(g, exX + 2, y, exW - 4, ROW, 0x30FFFFFF);
				}
				if (drag == Drag.EXPLORER_ROW && dragRow != e && Ui.in(mx, my, exX + 2, y, exW - 4, ROW)) {
					g.outline(exX + 2, y, exW - 4, ROW, Ui.GREEN);
				}
				int ix = exX + 6 + depth * 12;
				boolean hasKids = !GuiStore.childrenOf(e).isEmpty();
				if (hasKids) {
					Ui.text(g, collapsed.contains(e) ? ">" : "v", ix, y + 4, Ui.DIM);
				}
				String type = GuiStore.typeOf(e);
				Ui.badge(g, ix + 10, y + 2, GuiStore.typeLabel(type).charAt(0), badgeColor(type));
				boolean off = !Boolean.TRUE.equals(e.get("visible"));
				Ui.text(g, Ui.fit(String.valueOf(e.get("name")), exW - (ix - exX) - 34), ix + 25, y + 4, off ? Ui.FAINT : Ui.TEXT);
			}
			y += ROW;
		}
		g.disableScissor();
		if (max > 0) {
			int th = Math.max(14, (int) (bodyH * (bodyH / (double) (rows.size() * ROW))));
			Ui.fill(g, exX + exW - 4, bodyY + (int) ((bodyH - th) * (explorerScroll / max)), 3, th, 0x66FFFFFF);
		}
	}

	private void drawSelection(GuiGraphicsExtractor g) {
		if (selected == null) {
			return;
		}
		int x = selected.screenX();
		int y = selected.screenY();
		int w = selected.screenW();
		int h = selected.screenH();
		g.outline(x - 1, y - 1, w + 2, h + 2, Ui.ACCENT);
		if (selected.isFolder()) {
			return;
		}
		int[][] hp = handlePoints(x, y, w, h);
		for (int[] p : hp) {
			g.fill(p[0] - HANDLE / 2, p[1] - HANDLE / 2, p[0] + HANDLE / 2 + 1, p[1] + HANDLE / 2 + 1, 0xFFFFFFFF);
			g.outline(p[0] - HANDLE / 2, p[1] - HANDLE / 2, HANDLE + 1, HANDLE + 1, Ui.ACCENT);
		}
	}

	/** Corners and edge middles, in order: left-top, top, right-top, right, right-bottom, bottom, left-bottom, left. */
	private static int[][] handlePoints(int x, int y, int w, int h) {
		return new int[][] { { x, y }, { x + w / 2, y }, { x + w, y }, { x + w, y + h / 2 }, { x + w, y + h }, { x + w / 2, y + h }, { x, y + h }, { x, y + h / 2 } };
	}

	private int handleAt(double mx, double my) {
		if (selected == null || selected.isFolder()) {
			return -1;
		}
		int[][] hp = handlePoints(selected.screenX(), selected.screenY(), selected.screenW(), selected.screenH());
		for (int i = 0; i < hp.length; i++) {
			if (Math.abs(mx - hp[i][0]) <= HANDLE && Math.abs(my - hp[i][1]) <= HANDLE) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------ menus
	private ContextMenu createMenu(UiElement target) {
		ContextMenu m = new ContextMenu();
		if (target != null) {
			String name = String.valueOf(target.get("name"));
			for (String t : GuiStore.TYPES) {
				if (!t.equals("folder")) {
					m.add("Add " + GuiStore.typeLabel(t) + " inside " + name, true, () -> select(GuiStore.create(t, target)));
				}
			}
			m.separator();
			m.add("Duplicate", true, () -> select(GuiStore.duplicate(target, target.parentElement())));
			m.add(Boolean.TRUE.equals(target.get("visible")) ? "Hide" : "Show", true, () -> {
				target.set(target.isFolder() ? "enabled" : "visible", !Boolean.TRUE.equals(target.get("visible")), false);
				dirty = true;
			});
			m.add("Copy script path", true, () -> khoa.script.client.gui.Ui.setClipboard(pathOf(target).substring(1).replace('/', '.')));
			m.add("Copy On_Click stub", true, () -> khoa.script.client.gui.Ui.setClipboard(pathOf(target).substring(1).replace('/', '.') + ".On_Click:\n    print(\"clicked\")\n"));
			m.add("Delete", true, () -> delete(target));
			m.separator();
		}
		m.add("New Screen Folder", true, () -> select(GuiStore.create("folder", null)));
		return m;
	}

	private void delete(UiElement e) {
		for (UiElement c : GuiStore.childrenOf(e)) {
			delete(c);
		}
		if (e == selected) {
			select(null);
		}
		collapsed.remove(e);
		UiManager.remove(e);
		dirty = true;
	}

	/** Topmost editable thing under the mouse (Screen Folders are selected from the explorer). */
	private UiElement viewportHit(double mx, double my) {
		List<UiElement> order = UiManager.drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			UiElement e = order.get(i);
			if (e.isFolder() || !GuiStore.childrenOfSession(e) || !e.isVisibleEff()) {
				continue;
			}
			if (mx >= e.screenX() && mx < e.screenX() + e.screenW() && my >= e.screenY() && my < e.screenY() + e.screenH()) {
				return e;
			}
		}
		return null;
	}

	private boolean overUi(double mx, double my) {
		return mx >= 0 && my >= 0 && my < TOOLBAR_H
			|| explorerOpen && Ui.in(mx, my, exX, exY, exW, exH)
			|| props.contains(mx, my);
	}

	// ------------------------------------------------------------ input
	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		double rx = e.x();
		double ry = e.y();
		double mx = rx / k;
		double my = ry / k;
		String btn = Keys.mouseName(e.button());
		boolean left = btn.equals("MOUSE_LEFT");
		boolean right = btn.equals("MOUSE_RIGHT");
		if (menu != null) {
			ContextMenu m = menu;
			menu = null;
			if (m.contains(mx, my)) {
				m.click(mx, my);
				commit(false);
				return true;
			}
		}
		props.unfocusIfOutside(mx, my);
		if (left && my < TOOLBAR_H) {
			for (Btn b : new Btn[] { toggleExplorer, addFolder, done }) {
				if (b.hit(mx, my)) {
					b.action.run();
					return true;
				}
			}
			return true;
		}
		if (left && props.contains(mx, my)) {
			return props.mouseClicked(mx, my, e.hasShiftDown(), dbl);
		}
		if (explorerOpen && Ui.in(mx, my, exX, exY, exW, exH)) {
			return explorerClick(mx, my, left, right);
		}
		if (right) {
			UiElement hit = viewportHit(rx, ry);
			if (hit != null) {
				select(hit);
			}
			menu = createMenu(hit != null ? hit : selected).open((int) mx, (int) my, lw, lh);
			return true;
		}
		if (!left) {
			return true;
		}
		int hd = handleAt(rx, ry);
		if (hd >= 0) {
			drag = Drag.RESIZE;
			handle = hd;
			beginDrag(rx, ry);
			return true;
		}
		UiElement hit = viewportHit(rx, ry);
		select(hit);
		if (hit != null) {
			drag = Drag.MOVE;
			beginDrag(rx, ry);
		}
		return true;
	}

	private boolean explorerClick(double mx, double my, boolean left, boolean right) {
		int bodyY = exY + 18;
		if (my < bodyY) {
			return true;
		}
		int y = bodyY + 2 - (int) explorerScroll;
		for (Object[] r : rows()) {
			UiElement el = (UiElement) r[0];
			int depth = (Integer) r[1];
			if (my >= y && my < y + ROW) {
				int ix = exX + 6 + depth * 12;
				if (left && mx < ix + 10 && !GuiStore.childrenOf(el).isEmpty()) {
					if (!collapsed.remove(el)) {
						collapsed.add(el);
					}
					return true;
				}
				select(el);
				dirty = true;
				if (right) {
					menu = createMenu(el).open((int) mx, (int) my, lw, lh);
				} else if (left) {
					drag = Drag.EXPLORER_ROW;
					dragRow = el;
					pressX = mx;
					pressY = my;
				}
				return true;
			}
			y += ROW;
		}
		if (right) {
			select(null);
			menu = new ContextMenu().add("New Screen Folder", true, () -> select(GuiStore.create("folder", null))).open((int) mx, (int) my, lw, lh);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		double mx = e.x();
		double my = e.y();
		props.mouseDragged(mx / k, my / k, lw, lh);
		if (selected == null) {
			return true;
		}
		if (drag == Drag.MOVE) {
			double mdx = mx - rsMx;
			double mdy = my - rsMy;
			setDim("x", startX.resolve(baseW) + mdx, baseW);
			setDim("y", startY.resolve(baseH) + mdy, baseH);
			dirty = true;
		} else if (drag == Drag.RESIZE) {
			resizeTo(mx - rsMx, my - rsMy);
		}
		return true;
	}

	/** Remembers where the element and its parent were when the mouse went down; every drag step is computed from that, so nothing accumulates or drifts. */
	private void beginDrag(double mx, double my) {
		rsMx = mx;
		rsMy = my;
		rsX = selected.screenX();
		rsY = selected.screenY();
		rsW = selected.screenW();
		rsH = selected.screenH();
		UiElement par = selected.parentElement();
		if (par != null) {
			baseX = par.screenX();
			baseY = par.screenY() - (par.get("scroll_y") instanceof Double d ? d : 0);
			baseW = par.screenW();
			baseH = par.screenH();
		} else {
			var win = net.minecraft.client.Minecraft.getInstance().getWindow();
			baseX = 0;
			baseY = 0;
			baseW = win.getGuiScaledWidth();
			baseH = win.getGuiScaledHeight();
		}
		startX = selected.get("x") instanceof Dim dm ? dm : new Dim(0, 0);
		startY = selected.get("y") instanceof Dim dm ? dm : new Dim(0, 0);
	}

	/**
	 * Stores a position or size. A value the player gave as a fraction (scale) stays a fraction and only the scale changes;
	 * a pixel value stays pixels.
	 */
	private void setDim(String prop, double value, double base) {
		Dim old = selected.get(prop) instanceof Dim d ? d : new Dim(0, 0);
		Dim now;
		if (old.scale() != 0 && base > 0) {
			now = new Dim(Math.round((value - old.px()) / base * 10000.0) / 10000.0, old.px());
		} else {
			now = new Dim(0, Math.round(value));
		}
		selected.set(prop, now, false);
	}

	/** Handle order: 0 left-top, 1 top, 2 right-top, 3 right, 4 right-bottom, 5 bottom, 6 left-bottom, 7 left. */
	private void resizeTo(double dx, double dy) {
		boolean left = handle == 0 || handle == 6 || handle == 7;
		boolean right = handle == 2 || handle == 3 || handle == 4;
		boolean top = handle == 0 || handle == 1 || handle == 2;
		boolean bottom = handle == 4 || handle == 5 || handle == 6;
		double ax = selected.get("anchor_x") instanceof Double d ? d : 0;
		double ay = selected.get("anchor_y") instanceof Double d ? d : 0;
		double l0 = rsX;
		double t0 = rsY;
		double r0 = rsX + rsW;
		double b0 = rsY + rsH;
		double l = left ? Math.min(Math.round(l0 + dx), r0 - MIN_SIZE) : l0;
		double r = right ? Math.max(Math.round(r0 + dx), l0 + MIN_SIZE) : r0;
		double t = top ? Math.min(Math.round(t0 + dy), b0 - MIN_SIZE) : t0;
		double b = bottom ? Math.max(Math.round(b0 + dy), t0 + MIN_SIZE) : b0;
		if (left || right) {
			setDim("w", r - l, baseW);
			setDim("x", l - baseX + ax * (r - l), baseW);
		}
		if (top || bottom) {
			setDim("h", b - t, baseH);
			setDim("y", t - baseY + ay * (b - t), baseH);
		}
		dirty = true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		props.mouseReleased();
		if (drag == Drag.EXPLORER_ROW && dragRow != null) {
			double mx = e.x() / k;
			double my = e.y() / k;
			if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 6) {
				dropRow(mx, my);
			}
		}
		commit(false);
		drag = Drag.NONE;
		dragRow = null;
		return true;
	}

	private void dropRow(double mx, double my) {
		int y = exY + 20 - (int) explorerScroll;
		for (Object[] r : rows()) {
			UiElement target = (UiElement) r[0];
			if (my >= y && my < y + ROW && explorerOpen && mx >= exX && mx < exX + exW) {
				if (target != dragRow && !GuiStore.isInside(target, dragRow) && !dragRow.isFolder()) {
					dragRow.set("parent", target, false);
					collapsed.remove(target);
				}
				return;
			}
			y += ROW;
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		mx /= k;
		my /= k;
		if (props.mouseScrolled(mx, my, sy)) {
			return true;
		}
		if (explorerOpen && Ui.in(mx, my, exX, exY, exW, exH)) {
			explorerScroll -= sy * ROW * 2;
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (menu != null) {
			if (e.key() == InputConstants.KEY_ESCAPE) {
				menu = null;
			}
			return true;
		}
		if (props.typing() && !(e.hasControlDown() && e.key() == InputConstants.KEY_Z)) {
			props.keyPressed(e);
			commit(true);
			return true;
		}
		if (e.hasControlDown() && e.key() == InputConstants.KEY_Z) {
			props.unfocus();
			if (e.hasShiftDown()) {
				step(redo, undo);
			} else {
				step(undo, redo);
			}
			return true;
		}
		if (e.hasControlDown() && e.key() == InputConstants.KEY_Y) {
			props.unfocus();
			step(redo, undo);
			return true;
		}
		if (e.key() == InputConstants.KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (selected != null && e.key() == InputConstants.KEY_DELETE) {
			delete(selected);
			commit(false);
			return true;
		}
		if (selected != null && e.key() == InputConstants.KEY_D && e.hasControlDown()) {
			select(GuiStore.duplicate(selected, selected.parentElement()));
			commit(false);
			return true;
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		boolean used = props.charTyped(e);
		commit(true);
		return used;
	}

	@Override
	public boolean isInputCaptured() {
		return true;
	}

	@Override
	public void onClose() {
		props.unfocus();
		commit(false);
		GuiStore.save();
		this.minecraft.gui.setScreen(new khoa.script.client.gui.HomeScreen());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
	}

	@Override
	public void extractTransparentBackground(GuiGraphicsExtractor g) {
	}

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor g) {
	}

	@Override
	protected void extractMenuBackground(GuiGraphicsExtractor g) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
