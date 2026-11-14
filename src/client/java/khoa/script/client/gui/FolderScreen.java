package khoa.script.client.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import khoa.script.client.Kcd;
import khoa.script.client.ScriptFolders;
import khoa.script.lang.Builtin;
import khoa.script.lang.KcdError;
import khoa.script.lang.Parser;
import khoa.script.lang.Registry;

/** Inside one script folder: explorer tree, tabbed code editor, API browser, Run/Stop. Files are edited live; a running folder keeps its snapshot. */
public final class FolderScreen extends KScreen {
	private static final int TREE_W = 214;
	private static final int API_W = 250;
	private static final int ROW = 14;

	private record Entry(String rel, String name, boolean dir, int depth) {
	}

	private static final class Doc {
		final String rel;
		Path path;
		final CodeArea area = new CodeArea(false);
		String saved = "";
		boolean dirty;
		long checkAt;
		String error = "";

		Doc(String rel, Path path) {
			this.rel = rel;
			this.path = path;
		}

		String name() {
			return rel.substring(rel.lastIndexOf('/') + 1);
		}
	}

	private final String folder;
	private final Path root;
	private final List<Entry> entries = new ArrayList<>();
	private final List<Entry> visible = new ArrayList<>();
	private final Set<String> expanded = new HashSet<>();
	private final List<Doc> docs = new ArrayList<>();
	private final List<String> allFiles = new ArrayList<>();
	private String selRel;
	private int active = -1;
	private double treeScroll;
	private boolean apiOpen;
	private double apiScroll;
	private final CodeArea apiSearch = new CodeArea(true);
	private final List<Object> apiRows = new ArrayList<>();
	private String apiFilter = "\0";
	private final String openFile;
	private final int openLine;
	private boolean opened;

	private final Btn backBtn = new Btn("< Back", Btn.NORMAL, this::back);
	private final Btn newKcd = new Btn("+ Create KCD", Btn.PRIMARY, this::askNewFile);
	private final Btn newDir = new Btn("+ Create Folder", Btn.NORMAL, this::askNewFolder);
	private final Btn rename = new Btn("Rename", Btn.NORMAL, this::askRename);
	private final Btn delete = new Btn("Delete", Btn.DANGER, this::askDelete);
	private final Btn save = new Btn("Save", Btn.NORMAL, this::saveActive);
	private final Btn apiBtn = new Btn("API", Btn.NORMAL, () -> apiOpen = !apiOpen);
	private final Btn runStop = new Btn("Run", Btn.SUCCESS, this::toggleRun);
	private final Btn preview = new Btn("Preview UI", Btn.PRIMARY, this::previewUi);

	public FolderScreen(String folder) {
		this(folder, null, 0);
	}

	public FolderScreen(String folder, String file, int line) {
		super("KCoder");
		this.folder = folder;
		this.root = ScriptFolders.dir(folder);
		this.openFile = file;
		this.openLine = line;
		apiSearch.placeholder = "Search functions...";
		apiSearch.onChange = () -> apiFilter = "\0";
	}

	@Override
	protected String headerTitle() {
		return folder;
	}

	@Override
	protected void init() {
		super.init();
		rebuildTree();
		if (!opened) {
			opened = true;
			if (openFile != null) {
				openDoc(openFile, openLine);
			} else if (Files.exists(root.resolve("main.kcd"))) {
				openDoc("main.kcd", 0);
			}
		}
	}

	@Override
	protected void drawHeaderRight(GuiGraphicsExtractor g, int right, int y, int mx, int my) {
		if (ScriptFolders.isRunning(folder)) {
			float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 380.0));
			String s = "RUNNING  " + ScriptFolders.uptime(folder);
			Ui.textRight(g, s, right, y, Ui.GREEN);
			Ui.fill(g, right - Ui.width(s) - 12, y + 2, 6, 6, Ui.alpha(Ui.GREEN, 140 + (int) (115 * pulse)));
		} else {
			Ui.textRight(g, "stopped", right, y, Ui.DIM);
		}
	}

	// ------------------------------------------------------------ tree
	private void rebuildTree() {
		entries.clear();
		allFiles.clear();
		collect(root, "", 0);
		allFiles.sort(String.CASE_INSENSITIVE_ORDER);
		refreshVisible();
	}

	private void collect(Path dir, String prefix, int depth) {
		List<Path> kids = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.forEach(kids::add);
		} catch (IOException e) {
			return;
		}
		kids.sort(Comparator.<Path>comparingInt(p -> Files.isDirectory(p) ? 0 : 1).thenComparing(p -> p.getFileName().toString().toLowerCase()));
		for (Path p : kids) {
			String n = p.getFileName().toString();
			if (n.startsWith(".")) {
				continue;
			}
			boolean d = Files.isDirectory(p);
			if (!d && !n.endsWith(".kcd")) {
				continue;
			}
			String rel = prefix + n;
			entries.add(new Entry(rel, n, d, depth));
			if (d) {
				expanded.add(rel);
				collect(p, rel + "/", depth + 1);
			} else {
				allFiles.add(rel);
			}
		}
	}

	private void refreshVisible() {
		visible.clear();
		for (Entry e : entries) {
			boolean show = true;
			int slash = e.rel().lastIndexOf('/');
			String parent = slash < 0 ? null : e.rel().substring(0, slash);
			while (parent != null) {
				if (!expanded.contains(parent)) {
					show = false;
					break;
				}
				int s2 = parent.lastIndexOf('/');
				parent = s2 < 0 ? null : parent.substring(0, s2);
			}
			if (show) {
				visible.add(e);
			}
		}
	}

	private Entry entry(String rel) {
		for (Entry e : entries) {
			if (e.rel().equals(rel)) {
				return e;
			}
		}
		return null;
	}

	/** Directory new files go into: the selected folder, the folder of the selected file, or the root. */
	private String targetDir() {
		Entry e = selRel == null ? null : entry(selRel);
		if (e == null) {
			return "";
		}
		if (e.dir()) {
			return e.rel();
		}
		int i = e.rel().lastIndexOf('/');
		return i < 0 ? "" : e.rel().substring(0, i);
	}

	// ------------------------------------------------------------ documents
	private Doc active() {
		return active >= 0 && active < docs.size() ? docs.get(active) : null;
	}

	private Doc openDoc(String rel, int line) {
		for (int i = 0; i < docs.size(); i++) {
			if (docs.get(i).rel.equals(rel)) {
				active = i;
				if (line > 0) {
					docs.get(i).area.goTo(line - 1, 0);
				}
				selRel = rel;
				return docs.get(i);
			}
		}
		Path p = root.resolve(rel).normalize();
		if (!p.startsWith(root) || !Files.isRegularFile(p)) {
			Kcd.ENGINE.console.error(null, "Cannot open " + rel + " (file not found)");
			return null;
		}
		Doc d = new Doc(rel, p);
		try {
			d.saved = Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Kcd.ENGINE.console.error(null, "Cannot read " + rel + ": " + e.getMessage());
			return null;
		}
		d.area.setText(d.saved);
		d.area.onSave = this::saveActive;
		d.area.hasBreakpoint = r -> Kcd.ENGINE.debugger.has(folder, d.rel, r + 1);
		d.area.onBreakpointClick = r -> Kcd.ENGINE.debugger.toggle(folder, d.rel, r + 1);
		d.area.onChange = () -> d.checkAt = System.currentTimeMillis() + 350;
		d.checkAt = System.currentTimeMillis();
		d.area.focused = true;
		docs.add(d);
		active = docs.size() - 1;
		selRel = rel;
		if (line > 0) {
			d.area.goTo(line - 1, 0);
		}
		return d;
	}

	private void saveDoc(Doc d) {
		if (!d.dirty) {
			return;
		}
		try {
			Files.writeString(d.path, d.area.getText(), StandardCharsets.UTF_8);
			d.saved = d.area.getText();
			d.dirty = false;
		} catch (IOException e) {
			Kcd.ENGINE.console.error(null, "Cannot save " + d.rel + ": " + e.getMessage());
		}
	}

	private void saveActive() {
		Doc d = active();
		if (d != null) {
			saveDoc(d);
		}
	}

	private void saveAll() {
		for (Doc d : docs) {
			saveDoc(d);
		}
	}

	private void closeDoc(int i) {
		saveDoc(docs.get(i));
		docs.remove(i);
		active = Math.min(active, docs.size() - 1);
		if (i < active) {
			active--;
		}
	}

	private void checkSyntax(Doc d) {
		d.checkAt = 0;
		d.area.errors = new HashMap<>();
		d.error = "";
		try {
			Parser.parse(d.area.getText(), d.rel);
		} catch (KcdError e) {
			int line = Math.max(1, e.line);
			d.area.errors.put(line - 1, e.getMessage());
			d.error = "Syntax error, line " + line + ": " + e.getMessage();
		} catch (RuntimeException e) {
			d.error = "Syntax error: " + e.getMessage();
		}
	}

	// ------------------------------------------------------------ actions
	@Override
	protected void back() {
		saveAll();
		this.minecraft.gui.setScreen(new HomeScreen());
	}

	/** Runs the folder (if it is not running) and closes the editor so the GUI it builds can be seen over the game. */
	private void previewUi() {
		if (!ScriptFolders.isRunning(folder)) {
			saveAll();
			ScriptFolders.run(folder);
		}
		this.minecraft.gui.setScreen(null);
	}

	private void toggleRun() {
		if (ScriptFolders.isRunning(folder)) {
			ScriptFolders.stop(folder);
		} else {
			saveAll();
			ScriptFolders.run(folder);
		}
	}

	private void askNewFile() {
		String dir = targetDir();
		modal = Modal.input("Create KCD file", "File name (.kcd is added)" + (dir.isEmpty() ? "" : " in " + dir + "/"), "new_script", n -> {
			String bad = ScriptFolders.checkName(n.endsWith(".kcd") ? n.substring(0, n.length() - 4) : n);
			if (bad != null) {
				return bad;
			}
			String file = n.endsWith(".kcd") ? n : n + ".kcd";
			return Files.exists(root.resolve(dir).resolve(file)) ? "'" + file + "' already exists" : null;
		}, n -> {
			String file = n.endsWith(".kcd") ? n : n + ".kcd";
			Path p = root.resolve(dir).resolve(file);
			try {
				Files.writeString(p, "# " + file + "\n", StandardCharsets.UTF_8);
				if (!dir.isEmpty()) {
					expanded.add(dir);
				}
				rebuildTree();
				openDoc((dir.isEmpty() ? "" : dir + "/") + file, 0);
			} catch (IOException e) {
				Kcd.ENGINE.console.error(null, "Create failed: " + e.getMessage());
			}
		});
	}

	private void askNewFolder() {
		String dir = targetDir();
		modal = Modal.input("Create Folder", "Folder name" + (dir.isEmpty() ? "" : " in " + dir + "/"), "features", n -> {
			String bad = ScriptFolders.checkName(n);
			if (bad != null) {
				return bad;
			}
			return Files.exists(root.resolve(dir).resolve(n)) ? "'" + n + "' already exists" : null;
		}, n -> {
			try {
				Files.createDirectories(root.resolve(dir).resolve(n));
				String rel = (dir.isEmpty() ? "" : dir + "/") + n;
				rebuildTree();
				expanded.add(rel);
				refreshVisible();
				selRel = rel;
			} catch (IOException e) {
				Kcd.ENGINE.console.error(null, "Create failed: " + e.getMessage());
			}
		});
	}

	private void askRename() {
		Entry e = selRel == null ? null : entry(selRel);
		if (e == null) {
			return;
		}
		String oldName = e.dir() ? e.name() : e.name().substring(0, e.name().length() - 4);
		int slash = e.rel().lastIndexOf('/');
		String parent = slash < 0 ? "" : e.rel().substring(0, slash + 1);
		modal = Modal.input("Rename " + (e.dir() ? "folder" : "file"), "New name" + (e.dir() ? "" : " (.kcd is kept)"), oldName, n -> {
			if (n.equals(oldName)) {
				return "Type a different name";
			}
			String bad = ScriptFolders.checkName(n);
			if (bad != null) {
				return bad;
			}
			return Files.exists(root.resolve(parent + n + (e.dir() ? "" : ".kcd"))) ? "'" + n + "' already exists" : null;
		}, n -> {
			saveAll();
			String newRel = parent + n + (e.dir() ? "" : ".kcd");
			try {
				Files.move(root.resolve(e.rel()), root.resolve(newRel));
				List<Doc> old = new ArrayList<>(docs);
				docs.clear();
				active = -1;
				for (Doc d : old) {
					if (d.rel.equals(e.rel()) || (e.dir() && d.rel.startsWith(e.rel() + "/"))) {
						String moved = newRel + d.rel.substring(e.rel().length());
						Doc nd = openDoc(moved, 0);
						if (nd != null) {
							nd.area.goTo(d.area.row(), d.area.col());
						}
					} else {
						docs.add(d);
					}
				}
				active = docs.isEmpty() ? -1 : 0;
				rebuildTree();
				selRel = newRel;
			} catch (IOException ex) {
				Kcd.ENGINE.console.error(null, "Rename failed: " + ex.getMessage());
			}
		});
	}

	private void askDelete() {
		Entry e = selRel == null ? null : entry(selRel);
		if (e == null) {
			return;
		}
		modal = Modal.confirm("Delete " + (e.dir() ? "folder" : "file"), "Delete '" + e.rel() + "'" + (e.dir() ? " and everything in it" : "") + "? This cannot be undone.", "Delete", true, () -> {
			for (int i = docs.size() - 1; i >= 0; i--) {
				if (docs.get(i).rel.equals(e.rel()) || (e.dir() && docs.get(i).rel.startsWith(e.rel() + "/"))) {
					docs.remove(i);
				}
			}
			active = docs.isEmpty() ? -1 : Math.min(Math.max(active, 0), docs.size() - 1);
			String err = e.dir() ? ScriptFolders.deleteTree(root.resolve(e.rel())) : tryDelete(root.resolve(e.rel()));
			if (err != null) {
				Kcd.ENGINE.console.error(null, "Delete failed: " + err);
			}
			selRel = null;
			rebuildTree();
		});
	}

	private static String tryDelete(Path p) {
		try {
			Files.delete(p);
			return null;
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	@Override
	protected void openFromLog(String session, String file, int line) {
		if (session.equals(folder)) {
			openDoc(file, line);
		} else if (ScriptFolders.list().contains(session)) {
			saveAll();
			this.minecraft.gui.setScreen(new FolderScreen(session, file, line));
		}
	}

	// ------------------------------------------------------------ drawing
	private int topY() {
		return mainY + 44;
	}

	private int contentH() {
		return mainH - 44;
	}

	private int editorX() {
		return mainX + TREE_W + 6;
	}

	private int editorW() {
		return mainW - TREE_W - 6 - (apiOpen ? API_W + 6 : 0);
	}

	@Override
	protected void drawMain(GuiGraphicsExtractor g, int mx, int my) {
		boolean running = ScriptFolders.isRunning(folder);
		Entry sel = selRel == null ? null : entry(selRel);
		Doc doc = active();
		// toolbar
		backBtn.enabled = true;
		newKcd.enabled = true;
		newDir.enabled = true;
		rename.enabled = sel != null;
		delete.enabled = sel != null;
		save.enabled = doc != null && doc.dirty;
		apiBtn.style = apiOpen ? Btn.PRIMARY : Btn.NORMAL;
		runStop.label = running ? "Stop" : "Run";
		runStop.style = running ? Btn.DANGER : Btn.SUCCESS;
		runStop.w = 64;
		int x = mainX + 2;
		int y = mainY + 2;
		for (Btn b : new Btn[] {backBtn, newKcd, newDir, rename, delete, save, apiBtn}) {
			if (b != backBtn && b != save && b != apiBtn) {
				b.autoWidth();
			}
			if (b == save || b == apiBtn || b == backBtn) {
				b.autoWidth();
			}
			if (b == save) {
				x += 10;
			}
			b.at(x, y).draw(g, mx, my);
			x += b.w + 6;
			if (b == backBtn) {
				x += 10;
			}
		}
		runStop.at(mainX + mainW - runStop.w - 2, y).draw(g, mx, my);
		preview.autoWidth();
		preview.at(mainX + mainW - runStop.w - preview.w - 8, y).draw(g, mx, my);
		Ui.fill(g, mainX, y + 24, mainW, 1, Ui.BORDER);
		// banner
		String banner;
		int bcol;
		if (running) {
			banner = "Running a snapshot taken at Run. Edits are saved to disk but only apply after Stop + Run.";
			bcol = Ui.GREEN;
		} else {
			banner = "Stopped. Run starts every .kcd file in the root of this folder; Ctrl+S saves, Ctrl+Space suggests, Ctrl+/ comments. Files auto-save when you leave.";
			bcol = Ui.DIM;
		}
		Ui.text(g, Ui.fit(banner, mainW - 8), mainX + 4, mainY + 31, bcol);

		drawTree(g, mx, my, running);
		drawEditor(g, mx, my, doc);
		if (apiOpen) {
			drawApi(g, mx, my);
		}
	}

	private void drawTree(GuiGraphicsExtractor g, int mx, int my, boolean running) {
		int tx = mainX;
		int ty = topY();
		int th = contentH();
		Ui.panel(g, tx, ty, TREE_W, th, Ui.PANEL, Ui.BORDER);
		Ui.fill(g, tx + 1, ty + 1, TREE_W - 2, 16, Ui.PANEL2);
		Ui.text(g, "EXPLORER", tx + 8, ty + 5, Ui.DIM);
		g.enableScissor(tx + 1, ty + 18, tx + TREE_W - 1, ty + th - 1);
		int yy = ty + 20 - (int) treeScroll;
		// root row
		boolean rootSel = selRel == null;
		if (rootSel) {
			Ui.fill(g, tx + 1, yy, TREE_W - 2, ROW, Ui.alpha(Ui.ACCENT, 70));
		}
		float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 380.0));
		Ui.fill(g, tx + 8, yy + 4, 6, 6, running ? Ui.alpha(Ui.GREEN, 140 + (int) (115 * pulse)) : Ui.FAINT);
		Ui.text(g, Ui.fit(folder, TREE_W - 30), tx + 20, yy + 3, running ? Ui.GREEN : Ui.TEXT);
		yy += ROW;
		for (Entry e : visible) {
			boolean sel = e.rel().equals(selRel);
			boolean hover = Ui.in(mx, my, tx + 1, yy, TREE_W - 2, ROW) && Ui.in(mx, my, tx, ty + 18, TREE_W, th - 18);
			if (sel) {
				Ui.fill(g, tx + 1, yy, TREE_W - 2, ROW, Ui.alpha(Ui.ACCENT, 70));
			} else if (hover) {
				Ui.fill(g, tx + 1, yy, TREE_W - 2, ROW, 0x18FFFFFF);
			}
			int ix = tx + 8 + (e.depth() + 1) * 12;
			if (e.dir()) {
				Ui.text(g, expanded.contains(e.rel()) ? "v" : ">", ix - 10, yy + 3, Ui.DIM);
				Ui.badge(g, ix, yy + 1, 'D', Ui.YELLOW);
			} else {
				Ui.badge(g, ix, yy + 1, 'K', Ui.ACCENT);
			}
			boolean dirty = false;
			for (Doc d : docs) {
				if (d.rel.equals(e.rel()) && d.dirty) {
					dirty = true;
				}
			}
			String label = e.name() + (dirty ? " *" : "");
			Ui.text(g, Ui.fit(label, TREE_W - (ix - tx) - 20), ix + 15, yy + 3, dirty ? Ui.YELLOW : Ui.TEXT);
			yy += ROW;
		}
		if (entries.isEmpty()) {
			Ui.text(g, "Empty. Use + Create KCD", tx + 10, yy + 6, Ui.FAINT);
		}
		g.disableScissor();
	}

	/** Row (0-based) where a script of this folder is paused in the given file, or -1. */
	private int pausedLine(String rel) {
		var session = Kcd.ENGINE.find(folder);
		if (session != null) {
			for (var t : session.tasks) {
				if (t.paused && !t.finished() && rel.equals(t.pausedFile)) {
					return t.pausedLine - 1;
				}
			}
		}
		return -1;
	}

	private void drawEditor(GuiGraphicsExtractor g, int mx, int my, Doc doc) {
		int ex = editorX();
		int ey = topY();
		int ew = editorW();
		// tabs
		int tx = ex;
		for (int i = 0; i < docs.size(); i++) {
			Doc d = docs.get(i);
			String label = d.name() + (d.dirty ? " *" : "");
			int tw = Math.min(150, Ui.width(label) + 26);
			if (tx + tw > ex + ew) {
				break;
			}
			boolean act = i == active;
			Ui.panel(g, tx, ey, tw, 18, act ? Ui.PANEL3 : Ui.PANEL2, act ? Ui.ACCENT : Ui.BORDER);
			Ui.text(g, Ui.fit(label, tw - 24), tx + 6, ey + 5, act ? Ui.TEXT : Ui.DIM);
			boolean xh = Ui.in(mx, my, tx + tw - 14, ey + 2, 12, 14);
			Ui.text(g, "x", tx + tw - 11, ey + 5, xh ? Ui.RED : Ui.FAINT);
			tx += tw + 2;
		}
		int bodyY = ey + 20;
		int bodyH = contentH() - 20 - 16 - 14;
		if (doc == null) {
			Ui.panel(g, ex, bodyY, ew, bodyH + 30, Ui.PANEL, Ui.BORDER);
			Ui.textCenter(g, "Select a .kcd file in the explorer,", ex + ew / 2, bodyY + bodyH / 2 - 8, Ui.DIM);
			Ui.textCenter(g, "or use + Create KCD to make one.", ex + ew / 2, bodyY + bodyH / 2 + 4, Ui.FAINT);
			return;
		}
		Suggest.files = () -> allFiles;
		Suggest.currentDir = doc.rel.contains("/") ? doc.rel.substring(0, doc.rel.lastIndexOf('/')) : "";
		Suggest.source = p -> {
			for (Doc d : docs) {
				if (d.rel.equals(p)) {
					return d.area.getText();
				}
			}
			try {
				Path f = root.resolve(p).normalize();
				return f.startsWith(root) && Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
			} catch (IOException e) {
				return null;
			}
		};
		if (doc.area.takeChanged()) {
			doc.dirty = !doc.area.getText().equals(doc.saved);
		}
		if (doc.checkAt > 0 && System.currentTimeMillis() >= doc.checkAt) {
			checkSyntax(doc);
		}
		doc.area.bounds(ex, bodyY, ew, bodyH);
		doc.area.pausedRow = pausedLine(doc.rel);
		doc.area.render(g, mx, my);
		// docs strip
		int sy = bodyY + bodyH + 2;
		Ui.fill(g, ex, sy, ew, 14, Ui.PANEL2);
		String word = doc.area.wordAtCursor();
		Builtin b = word.isEmpty() ? null : Registry.get().builtin(word);
		Registry.EventType ev = word.isEmpty() ? null : Registry.get().events.get(word);
		if (b != null) {
			Ui.text(g, Ui.fit(b.signature, ew / 2), ex + 4, sy + 3, Ui.ACCENT);
			int sw = Math.min(ew / 2, Ui.width(b.signature)) + 14;
			Ui.text(g, Ui.fit(b.description, ew - sw - 6), ex + sw, sy + 3, Ui.DIM);
		} else if (ev != null) {
			Ui.text(g, Ui.fit(ev.name + "(" + String.join(", ", ev.filters) + "):  " + ev.description, ew - 8), ex + 4, sy + 3, Ui.RED);
		} else {
			Ui.text(g, "Ctrl+Space suggestions  |  Ctrl+S save  |  Ctrl+F find  |  Ctrl+H replace  |  Ctrl+/ comment  |  Tab indent  |  Ctrl+Z undo", ex + 4, sy + 3, Ui.FAINT);
		}
		// status
		int st = sy + 14;
		Ui.fill(g, ex, st, ew, 14, Ui.PANEL);
		Ui.text(g, "Ln " + (doc.area.row() + 1) + ", Col " + (doc.area.col() + 1) + "   " + doc.area.lines().size() + " lines", ex + 4, st + 3, Ui.DIM);
		if (doc.error.isEmpty()) {
			Ui.textRight(g, "syntax OK", ex + ew - 6, st + 3, Ui.GREEN);
		} else {
			Ui.textRight(g, Ui.fit(doc.error, ew - 140), ex + ew - 6, st + 3, Ui.RED);
		}
	}

	private void rebuildApi() {
		String f = apiSearch.getText().trim().toLowerCase();
		if (f.equals(apiFilter)) {
			return;
		}
		apiFilter = f;
		apiRows.clear();
		Map<String, List<Builtin>> byCat = new java.util.TreeMap<>();
		for (Builtin b : Registry.get().builtins.values()) {
			if (f.isEmpty() || b.name.contains(f) || b.category.contains(f) || b.description.toLowerCase().contains(f)) {
				byCat.computeIfAbsent(b.category, k -> new ArrayList<>()).add(b);
			}
		}
		for (Map.Entry<String, List<Builtin>> c : byCat.entrySet()) {
			apiRows.add(c.getKey());
			c.getValue().sort(Comparator.comparing(b -> b.name));
			apiRows.addAll(c.getValue());
		}
	}

	private void drawApi(GuiGraphicsExtractor g, int mx, int my) {
		int ax = mainX + mainW - API_W;
		int ay = topY();
		int ah = contentH();
		Ui.panel(g, ax, ay, API_W, ah, Ui.PANEL, Ui.BORDER);
		Ui.fill(g, ax + 1, ay + 1, API_W - 2, 16, Ui.PANEL2);
		Ui.text(g, "API  (click inserts)", ax + 8, ay + 5, Ui.DIM);
		apiSearch.bounds(ax + 4, ay + 20, API_W - 8, 18);
		apiSearch.render(g, mx, my);
		rebuildApi();
		int listY = ay + 42;
		int listH = ah - 42 - 34;
		g.enableScissor(ax + 1, listY, ax + API_W - 1, listY + listH);
		int yy = listY - (int) apiScroll;
		Builtin hover = null;
		for (Object o : apiRows) {
			if (yy + ROW >= listY && yy <= listY + listH) {
				if (o instanceof String cat) {
					Ui.fill(g, ax + 1, yy, API_W - 2, ROW, Ui.PANEL2);
					Ui.text(g, cat.toUpperCase(), ax + 6, yy + 3, Ui.ACCENT);
				} else {
					Builtin b = (Builtin) o;
					boolean h = Ui.in(mx, my, ax + 1, yy, API_W - 2, ROW) && Ui.in(mx, my, ax, listY, API_W, listH);
					if (h) {
						Ui.fill(g, ax + 1, yy, API_W - 2, ROW, 0x22FFFFFF);
						hover = b;
					}
					Ui.badge(g, ax + 4, yy + 1, 'f', Suggest.Kind.FUNCTION.color);
					Ui.text(g, Ui.fit(b.name, API_W - 30), ax + 19, yy + 3, Ui.TEXT);
				}
			}
			yy += ROW;
		}
		g.disableScissor();
		Ui.fill(g, ax + 1, ay + ah - 34, API_W - 2, 33, Ui.PANEL2);
		if (hover != null) {
			Ui.text(g, Ui.fit(hover.signature, API_W - 10), ax + 5, ay + ah - 31, Ui.ACCENT);
			Ui.text(g, Ui.fit(hover.description, API_W - 10), ax + 5, ay + ah - 19, Ui.DIM);
		} else {
			Ui.text(g, apiRows.size() + " entries", ax + 5, ay + ah - 31, Ui.FAINT);
		}
	}

	// ------------------------------------------------------------ input
	@Override
	protected boolean mainClicked(double mx, double my, int button, boolean shift, boolean dbl) {
		Doc doc = active();
		if (mx < -500) {
			if (doc != null) {
				doc.area.focused = false;
			}
			apiSearch.focused = false;
			return false;
		}
		boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
		if (left) {
			for (Btn b : new Btn[] {backBtn, newKcd, newDir, rename, delete, save, apiBtn, runStop, preview}) {
				if (b.enabled && b.hit(mx, my)) {
					b.action.run();
					return true;
				}
			}
		}
		// tree
		int tx = mainX;
		int ty = topY();
		if (Ui.in(mx, my, tx, ty + 18, TREE_W, contentH() - 18)) {
			int idx = (int) Math.floor((my - ty - 20 + treeScroll) / ROW) - 1;
			if (idx < 0) {
				selRel = null;
			} else if (idx < visible.size()) {
				Entry e = visible.get(idx);
				selRel = e.rel();
				if (left) {
					if (e.dir()) {
						if (!expanded.remove(e.rel())) {
							expanded.add(e.rel());
						}
						refreshVisible();
					} else {
						openDoc(e.rel(), 0);
					}
				}
			} else {
				selRel = null;
			}
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				Entry e = selRel == null ? null : entry(selRel);
				ContextMenu m = new ContextMenu();
				if (e != null && !e.dir()) {
					m.add("Open", true, () -> openDoc(e.rel(), 0)).separator();
				}
				m.add("Create KCD", true, this::askNewFile)
					.add("Create Folder", true, this::askNewFolder);
				if (e != null) {
					m.separator().add("Rename", true, this::askRename).add("Delete", true, this::askDelete);
					m.add("Copy path", true, () -> Ui.setClipboard("./" + e.rel()));
				}
				menu = m.open((int) mx, (int) my, lw, lh);
			}
			return true;
		}
		// tabs
		int ex = editorX();
		if (Ui.in(mx, my, ex, topY(), editorW(), 18)) {
			int x = ex;
			for (int i = 0; i < docs.size(); i++) {
				Doc d = docs.get(i);
				int tw = Math.min(150, Ui.width(d.name() + (d.dirty ? " *" : "")) + 26);
				if (mx >= x && mx < x + tw) {
					if (mx >= x + tw - 14 || button == InputConstants.MOUSE_BUTTON_MIDDLE) {
						closeDoc(i);
					} else {
						active = i;
						selRel = d.rel;
						d.area.focused = true;
					}
					return true;
				}
				x += tw + 2;
			}
			return true;
		}
		// api panel
		if (apiOpen) {
			int ax = mainX + mainW - API_W;
			int listY = topY() + 42;
			int listH = contentH() - 42 - 34;
			if (Ui.in(mx, my, ax, topY(), API_W, contentH())) {
				apiSearch.mouseClicked(mx, my, shift, dbl);
				if (doc != null && Ui.in(mx, my, ax, listY, API_W, listH)) {
					doc.area.focused = false;
					int idx = (int) ((my - listY + apiScroll) / ROW);
					if (idx >= 0 && idx < apiRows.size() && apiRows.get(idx) instanceof Builtin b) {
						doc.area.focused = true;
						doc.area.insert(b.name + "()");
						doc.area.moveCursor(-1);
						doc.dirty = true;
					}
				}
				return true;
			}
		}
		if (doc != null) {
			apiSearch.focused = false;
			if (doc.area.mouseClicked(mx, my, shift, dbl)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean mainDragged(double mx, double my) {
		Doc d = active();
		if (d != null) {
			d.area.mouseDragged(mx, my);
		}
		apiSearch.mouseDragged(mx, my);
		return true;
	}

	@Override
	protected void mainReleased() {
		Doc d = active();
		if (d != null) {
			d.area.mouseReleased();
		}
		apiSearch.mouseReleased();
	}

	@Override
	protected boolean mainScrolled(double mx, double my, double dy) {
		Doc d = active();
		if (d != null && d.area.mouseScrolled(mx, my, dy)) {
			return true;
		}
		if (Ui.in(mx, my, mainX, topY(), TREE_W, contentH())) {
			double max = Math.max(0, (visible.size() + 2) * ROW - (contentH() - 22));
			treeScroll = Math.max(0, Math.min(max, treeScroll - dy * ROW * 2));
			return true;
		}
		if (apiOpen && Ui.in(mx, my, mainX + mainW - API_W, topY(), API_W, contentH())) {
			double max = Math.max(0, apiRows.size() * ROW - (contentH() - 42 - 34));
			apiScroll = Math.max(0, Math.min(max, apiScroll - dy * ROW * 2));
			return true;
		}
		return false;
	}

	@Override
	protected boolean mainKey(KeyEvent e) {
		if (apiSearch.focused) {
			if (e.key() == InputConstants.KEY_ESCAPE) {
				apiSearch.focused = false;
				return true;
			}
			return apiSearch.keyPressed(e);
		}
		Doc d = active();
		if (d != null && d.area.focused && d.area.keyPressed(e)) {
			return true;
		}
		if (e.key() == InputConstants.KEY_DELETE && selRel != null && d != null && !d.area.focused) {
			askDelete();
			return true;
		}
		return false;
	}

	@Override
	protected boolean mainChar(CharacterEvent e) {
		if (apiSearch.focused) {
			return apiSearch.charTyped(e);
		}
		Doc d = active();
		return d != null && d.area.charTyped(e);
	}

	@Override
	public void removed() {
		saveAll();
	}

	static Set<String> unusedSet() {
		return Set.of();
	}
}
