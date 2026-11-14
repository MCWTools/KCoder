package khoa.script.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

import khoa.script.client.Kcd;
import khoa.script.client.ScriptFolders;
import khoa.script.lang.ScriptTask;
import khoa.script.lang.Session;

/** Main screen: the list of script folders with Create / Rename / Delete / Run-Stop in the toolbar. Double-click opens a folder. */
public final class HomeScreen extends KScreen {
	private static final int CARD_W = 236;
	private static final int CARD_H = 74;
	private static String selected;

	private List<String> folders = new ArrayList<>();
	private long refreshedAt;
	private double scroll;
	private final Btn create = new Btn("+ Create Script Folder", Btn.PRIMARY, this::askCreate);
	private final Btn guiEditor = new Btn("GUI Editor", Btn.PRIMARY, () -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(new khoa.script.client.guiedit.GuiEditorScreen()));
	private final Btn exportBtn = new Btn("Export...", Btn.NORMAL, this::exportSelected);
	private final Btn importBtn = new Btn("Import...", Btn.NORMAL, this::importPack);
	private final Btn rename = new Btn("Rename", Btn.NORMAL, this::askRename);
	private final Btn delete = new Btn("Delete", Btn.DANGER, this::askDelete);
	private final Btn runStop = new Btn("Run", Btn.SUCCESS, this::toggleRun);
	private final Btn open = new Btn("Open", Btn.NORMAL, this::openSelected);
	private final Btn stopAll = new Btn("Stop all", Btn.NORMAL, () -> {
		ScriptFolders.stopAll();
		refresh();
	});

	public HomeScreen() {
		super("KCoder");
	}

	@Override
	protected String headerTitle() {
		return "Script folders";
	}

	private void refresh() {
		folders = ScriptFolders.list();
		refreshedAt = System.currentTimeMillis();
		if (selected != null && !folders.contains(selected)) {
			selected = null;
		}
	}

	@Override
	protected void init() {
		super.init();
		refresh();
	}

	@Override
	protected void drawHeaderRight(GuiGraphicsExtractor g, int right, int y, int mx, int my) {
		int n = ScriptFolders.runningCount();
		String s = n == 0 ? "nothing running" : n + " running";
		Ui.textRight(g, s, right, y, n == 0 ? Ui.DIM : Ui.GREEN);
		if (n > 0) {
			Ui.fill(g, right - Ui.width(s) - 12, y + 2, 6, 6, Ui.GREEN);
		}
	}

	// ------------------------------------------------------------ actions
	private void askCreate() {
		modal = Modal.input("Create Script Folder", "Name of the new script folder", "MyScripts", n -> {
			String bad = ScriptFolders.checkName(n);
			if (bad != null) {
				return bad;
			}
			return java.nio.file.Files.exists(ScriptFolders.dir(n)) ? "'" + n + "' already exists" : null;
		}, n -> {
			String err = ScriptFolders.create(n);
			refresh();
			if (err != null) {
				Kcd.ENGINE.console.error(null, "Create failed: " + err);
			} else {
				selected = n;
			}
		});
	}

	private void exportSelected() {
		String name = selected;
		if (name == null) {
			return;
		}
		khoa.script.client.ScriptPack.pickSave(name, target -> report(khoa.script.client.ScriptPack.export(name, target)));
	}

	private void importPack() {
		khoa.script.client.ScriptPack.pickOpen(source -> {
			report(khoa.script.client.ScriptPack.importFrom(source));
			refresh();
		});
	}

	private static void report(khoa.script.client.ScriptPack.Result r) {
		if (r.ok()) {
			Kcd.ENGINE.console.info(null, r.message());
		} else {
			Kcd.ENGINE.console.error(null, r.message());
		}
	}

	private void askRename() {
		String from = selected;
		if (from == null) {
			return;
		}
		modal = Modal.input("Rename Script Folder", "New name for '" + from + "'", from, n -> {
			if (n.equals(from)) {
				return "Type a different name";
			}
			String bad = ScriptFolders.checkName(n);
			if (bad != null) {
				return bad;
			}
			return java.nio.file.Files.exists(ScriptFolders.dir(n)) ? "'" + n + "' already exists" : null;
		}, n -> {
			String err = ScriptFolders.rename(from, n);
			refresh();
			if (err != null) {
				Kcd.ENGINE.console.error(null, "Rename failed: " + err);
			} else {
				selected = n;
			}
		});
	}

	private void askDelete() {
		String name = selected;
		if (name == null) {
			return;
		}
		modal = Modal.confirm("Delete Script Folder", "Delete '" + name + "' and ALL its files? This cannot be undone.", "Delete", true, () -> {
			String err = ScriptFolders.delete(name);
			refresh();
			if (err != null) {
				Kcd.ENGINE.console.error(null, "Delete failed: " + err);
			}
		});
	}

	private void toggleRun() {
		String name = selected;
		if (name == null) {
			return;
		}
		if (ScriptFolders.isRunning(name)) {
			ScriptFolders.stop(name);
		} else {
			ScriptFolders.run(name);
		}
	}

	private void openSelected() {
		if (selected != null) {
			this.minecraft.gui.setScreen(new FolderScreen(selected));
		}
	}

	@Override
	protected void openFromLog(String session, String file, int line) {
		if (ScriptFolders.list().contains(session)) {
			this.minecraft.gui.setScreen(new FolderScreen(session, file, line));
		}
	}

	// ------------------------------------------------------------ drawing
	private int cols() {
		return Math.max(1, (mainW - 4) / (CARD_W + 8));
	}

	private int gridTop() {
		return mainY + 54;
	}

	private int gridH() {
		return mainH - 54;
	}

	private int cardX(int i) {
		return mainX + 2 + (i % cols()) * (CARD_W + 8);
	}

	private int cardY(int i) {
		return (int) (gridTop() + 4 + (i / cols()) * (CARD_H + 8) - scroll);
	}

	@Override
	protected void drawMain(GuiGraphicsExtractor g, int mx, int my) {
		if (System.currentTimeMillis() - refreshedAt > 800) {
			refresh();
		}
		boolean has = selected != null;
		boolean running = has && ScriptFolders.isRunning(selected);
		// toolbar
		int x = mainX + 2;
		int y = mainY + 2;
		for (Btn b : new Btn[] {create, rename, delete, runStop, open, stopAll}) {
			b.autoWidth();
		}
		runStop.label = running ? "Stop" : "Run";
		runStop.style = running ? Btn.DANGER : Btn.SUCCESS;
		runStop.enabled = has;
		rename.enabled = has && !running;
		delete.enabled = has;
		open.enabled = has;
		stopAll.enabled = ScriptFolders.runningCount() > 0;
		runStop.w = 60;
		for (Btn b : new Btn[] {create, rename, delete}) {
			b.at(x, y).draw(g, mx, my);
			x += b.w + 6;
		}
		x += 10;
		for (Btn b : new Btn[] {runStop, open, stopAll, guiEditor}) {
			b.autoWidth();
			b.at(x, y).draw(g, mx, my);
			x += b.w + 6;
		}
		if (has && !running) {
			Ui.text(g, "Rename needs the folder stopped", x + 8, y + 6, Ui.FAINT);
		}
		x = mainX + 2;
		y += 24;
		exportBtn.enabled = has;
		for (Btn b : new Btn[] {exportBtn, importBtn}) {
			b.autoWidth();
			b.at(x, y).draw(g, mx, my);
			x += b.w + 6;
		}
		Ui.text(g, "one .kcdpack file holds a whole script folder", x + 8, y + 6, Ui.FAINT);
		Ui.fill(g, mainX, y + 24, mainW, 1, Ui.BORDER);
		// cards
		g.enableScissor(mainX, gridTop(), mainX + mainW, gridTop() + gridH());
		long t = System.currentTimeMillis();
		float pulse = (float) (0.5 + 0.5 * Math.sin(t / 380.0));
		for (int i = 0; i < folders.size(); i++) {
			String name = folders.get(i);
			int cx = cardX(i);
			int cy = cardY(i);
			if (cy + CARD_H < gridTop() || cy > gridTop() + gridH()) {
				continue;
			}
			boolean run = ScriptFolders.isRunning(name);
			boolean sel = name.equals(selected);
			boolean hover = Ui.in(mx, my, cx, cy, CARD_W, CARD_H) && Ui.in(mx, my, mainX, gridTop(), mainW, gridH());
			int fill = run ? Ui.mix(Ui.PANEL, Ui.GREEN, 0.10f + 0.05f * pulse) : hover ? Ui.PANEL3 : Ui.PANEL2;
			int border = sel ? Ui.ACCENT : run ? Ui.alpha(Ui.GREEN, 140 + (int) (90 * pulse)) : Ui.BORDER;
			Ui.panel(g, cx, cy, CARD_W, CARD_H, fill, border);
			if (sel) {
				g.outline(cx + 1, cy + 1, CARD_W - 2, CARD_H - 2, Ui.alpha(Ui.ACCENT, 160));
			}
			Ui.fill(g, cx + 1, cy + 1, 4, CARD_H - 2, run ? Ui.GREEN : Ui.FAINT);
			Ui.text(g, Ui.fit(name, CARD_W - 80), cx + 14, cy + 9, Ui.TEXT);
			int files = ScriptFolders.countFiles(name);
			Ui.text(g, files + (files == 1 ? " file" : " files"), cx + 14, cy + 23, Ui.DIM);
			if (run) {
				Session s = ScriptFolders.session(name);
				int tasks = 0;
				if (s != null) {
					for (ScriptTask task : s.tasks) {
						if (!task.finished()) {
							tasks++;
						}
					}
				}
				int handlers = s == null ? 0 : Kcd.ENGINE.events.count(s);
				Ui.fill(g, cx + CARD_W - 74, cy + 8, 66, 13, Ui.alpha(Ui.GREEN, 50));
				g.outline(cx + CARD_W - 74, cy + 8, 66, 13, Ui.alpha(Ui.GREEN, 190));
				Ui.fill(g, cx + CARD_W - 69, cy + 12, 5, 5, Ui.alpha(Ui.GREEN, 150 + (int) (105 * pulse)));
				Ui.text(g, "RUNNING", cx + CARD_W - 61, cy + 11, Ui.GREEN);
				Ui.text(g, ScriptFolders.uptime(name) + "  -  " + tasks + " active, " + handlers + " hotkeys/events", cx + 14, cy + 40, Ui.GREEN);
				Ui.text(g, "Edits apply after Stop + Run", cx + 14, cy + 54, Ui.FAINT);
			} else {
				Ui.text(g, "Stopped", cx + 14, cy + 40, Ui.DIM);
				Ui.text(g, "Double-click to open", cx + 14, cy + 54, Ui.FAINT);
			}
		}
		if (folders.isEmpty()) {
			Ui.textCenter(g, "No script folders yet.", mainX + mainW / 2, gridTop() + 40, Ui.DIM);
			Ui.textCenter(g, "Click  + Create Script Folder  to make one.", mainX + mainW / 2, gridTop() + 54, Ui.FAINT);
		}
		g.disableScissor();
	}

	private int cardAt(double mx, double my) {
		if (!Ui.in(mx, my, mainX, gridTop(), mainW, gridH())) {
			return -1;
		}
		for (int i = 0; i < folders.size(); i++) {
			if (Ui.in(mx, my, cardX(i), cardY(i), CARD_W, CARD_H)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	protected boolean mainClicked(double mx, double my, int button, boolean shift, boolean dbl) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Btn b : new Btn[] {create, rename, delete, runStop, open, stopAll, guiEditor, exportBtn, importBtn}) {
				if (b.enabled && b.hit(mx, my)) {
					b.action.run();
					return true;
				}
			}
		}
		int i = cardAt(mx, my);
		if (i >= 0) {
			selected = folders.get(i);
			if (button == InputConstants.MOUSE_BUTTON_LEFT && dbl) {
				openSelected();
			} else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				boolean run = ScriptFolders.isRunning(selected);
				menu = new ContextMenu()
					.add("Open", true, this::openSelected)
					.add(run ? "Stop" : "Run", true, this::toggleRun)
					.separator()
					.add("Rename", !run, this::askRename)
					.add("Delete", true, this::askDelete)
					.separator()
					.add("Export...", true, this::exportSelected)
					.open((int) mx, (int) my, lw, lh);
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT && Ui.in(mx, my, mainX, gridTop(), mainW, gridH())) {
			menu = new ContextMenu().add("Create Script Folder", true, this::askCreate).add("Import...", true, this::importPack).open((int) mx, (int) my, lw, lh);
			return true;
		}
		return false;
	}

	@Override
	protected boolean mainScrolled(double mx, double my, double dy) {
		int rows = (folders.size() + cols() - 1) / cols();
		double max = Math.max(0, rows * (CARD_H + 8) + 8 - gridH());
		scroll = Math.max(0, Math.min(max, scroll - dy * 30));
		return true;
	}

	@Override
	protected boolean mainKey(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RETURN && selected != null) {
			openSelected();
			return true;
		}
		if (e.key() == InputConstants.KEY_DELETE && selected != null) {
			askDelete();
			return true;
		}
		return false;
	}
}
