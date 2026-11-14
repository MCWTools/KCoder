package khoa.script.client.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import khoa.script.client.ConsoleRunner;
import khoa.script.client.Kcd;
import khoa.script.lang.LogConsole;
import khoa.script.lang.LogConsole.Level;

/** The log panel (everything scripts print, plus errors with file:line) and the console tab with its multi-line input. State survives between screens. */
public final class BottomPanel {
	private record Row(LogConsole.Line line, String text, boolean first, int color) {
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int ROW_H = 10;
	private static final int INPUT_H = 54;

	// state shared by every screen
	private static boolean consoleTab;
	/** 0 = log / console, 2 = debugger, 3 = profiler. */
	private static int extra;
	private static double extraScroll;
	private static final boolean[] SHOW = {true, true, true, true};
	private static final CodeArea INPUT = new CodeArea(false);
	private static double scroll;
	private static boolean follow = true;
	private static int histIndex = -1;

	static {
		INPUT.lineNumbers = false;
		INPUT.placeholder = "Type code here...  Enter = run, Ctrl+Enter = run multi-line code, Ctrl+Space = suggestions";
		INPUT.onEnter = () -> {
			String t = INPUT.getText();
			if (!t.contains("\n") && !t.stripTrailing().endsWith(":") && !t.isBlank()) {
				runInput();
				return true;
			}
			return false;
		};
		INPUT.onHistory = d -> {
			List<String> h = ConsoleRunner.HISTORY;
			if (h.isEmpty()) {
				return;
			}
			if (histIndex < 0) {
				histIndex = h.size();
			}
			histIndex = Math.max(0, Math.min(h.size(), histIndex + d));
			INPUT.setText(histIndex >= h.size() ? "" : h.get(histIndex));
			INPUT.goTo(INPUT.lines().size() - 1, Integer.MAX_VALUE);
		};
	}

	public int x;
	public int y;
	public int w;
	public int h;
	/** Called when an error line is clicked: (session id, "file.kcd", line). */
	public TriConsumer onOpen = (s, f, l) -> {
	};

	@FunctionalInterface
	public interface TriConsumer {
		void accept(String session, String file, int line);
	}

	private List<Row> rows = new ArrayList<>();
	private long cacheVersion = -1;
	private int cacheW = -1;
	private int cacheMask = -1;
	private boolean cacheTab;
	private final Btn tabLog = new Btn("Log", Btn.NORMAL, () -> {
		consoleTab = false;
		extra = 0;
	}).size(60, 18);
	private final Btn tabCon = new Btn("Console", Btn.NORMAL, () -> {
		consoleTab = true;
		extra = 0;
	}).size(70, 18);
	private final Btn tabDbg = new Btn("Debug", Btn.NORMAL, () -> {
		extra = 2;
		extraScroll = 0;
	}).size(52, 18);
	private final Btn tabProf = new Btn("Profiler", Btn.NORMAL, () -> {
		extra = 3;
		extraScroll = 0;
	}).size(64, 18);
	private final Btn dbgGo = new Btn("Continue", Btn.SUCCESS, () -> resume(khoa.script.lang.Debugger.Mode.RUN)).size(62, 18);
	private final Btn dbgOver = new Btn("Step over", Btn.NORMAL, () -> resume(khoa.script.lang.Debugger.Mode.OVER)).size(66, 18);
	private final Btn dbgInto = new Btn("Step into", Btn.NORMAL, () -> resume(khoa.script.lang.Debugger.Mode.INTO)).size(66, 18);
	private final Btn dbgOut = new Btn("Step out", Btn.NORMAL, () -> resume(khoa.script.lang.Debugger.Mode.OUT)).size(60, 18);
	private final Btn dbgClear = new Btn("Clear breakpoints", Btn.DANGER, () -> Kcd.ENGINE.debugger.clearAll()).size(104, 18);
	private final Btn profStart = new Btn("Start", Btn.SUCCESS, () -> {
		Kcd.ENGINE.profiler.reset();
		Kcd.ENGINE.profiler.enabled = true;
	}).size(46, 18);
	private final Btn profStop = new Btn("Stop", Btn.DANGER, () -> Kcd.ENGINE.profiler.enabled = false).size(46, 18);
	private final Btn profReset = new Btn("Reset", Btn.NORMAL, () -> Kcd.ENGINE.profiler.reset()).size(48, 18);

	private static khoa.script.lang.ScriptTask pausedTask() {
		for (khoa.script.lang.Session s : Kcd.ENGINE.sessions.values()) {
			for (khoa.script.lang.ScriptTask t : s.tasks) {
				if (t.paused && !t.finished()) {
					return t;
				}
			}
		}
		return null;
	}

	private static void resume(khoa.script.lang.Debugger.Mode m) {
		khoa.script.lang.ScriptTask t = pausedTask();
		if (t != null) {
			t.resumeDebug(m);
		}
	}

	/** Body of the Debug and Profiler tabs. */
	private void renderExtra(GuiGraphicsExtractor g, int mx, int my) {
		int by = bodyY();
		int bh = h - 24;
		g.enableScissor(x + 1, by, x + w - 1, by + bh);
		if (extra == 2) {
			int bx = x + 6;
			for (Btn b : new Btn[] { dbgGo, dbgOver, dbgInto, dbgOut }) {
				b.at(bx, by + 3).draw(g, mx, my);
				bx += b.w + 4;
			}
			dbgClear.at(x + w - dbgClear.w - 8, by + 3).draw(g, mx, my);
			khoa.script.lang.ScriptTask t = pausedTask();
			int ty = by + 28;
			if (t == null) {
				Ui.text(g, "No script is paused. Click the gutter left of a line number to set a breakpoint (red dot), then Run.", x + 8, ty, Ui.FAINT);
				Ui.text(g, "When the script reaches that line it stops here and you can look at its variables.", x + 8, ty + 12, Ui.FAINT);
			} else {
				Ui.text(g, "Paused in " + t.session.id + " at " + t.pausedFile + ":" + t.pausedLine, x + 8, ty, Ui.YELLOW);
				ty += 14;
				List<java.util.Map.Entry<String, String>> vars = khoa.script.lang.Debugger.variables(t);
				int max = Math.max(0, vars.size() * ROW_H - (bh - 46));
				extraScroll = Math.max(0, Math.min(extraScroll, max));
				for (int i = 0; i < vars.size(); i++) {
					int ry = ty + i * ROW_H - (int) extraScroll;
					if (ry < by + 24 || ry > by + bh) {
						continue;
					}
					var en = vars.get(i);
					if (en.getValue().isEmpty()) {
						Ui.text(g, en.getKey(), x + 8, ry, Ui.PURPLE);
					} else {
						Ui.text(g, en.getKey(), x + 16, ry, Ui.TEAL);
						Ui.text(g, Ui.fit(en.getValue(), w - 200), x + 170, ry, Ui.TEXT);
					}
				}
			}
		} else {
			boolean on = Kcd.ENGINE.profiler.enabled;
			profStart.at(x + 6, by + 3).draw(g, mx, my);
			profStop.at(x + 56, by + 3).draw(g, mx, my);
			profReset.at(x + 106, by + 3).draw(g, mx, my);
			Ui.text(g, on ? "recording..." : "stopped (press Start, run your script, then Stop)", x + 164, by + 8, on ? Ui.GREEN : Ui.FAINT);
			int ty = by + 28;
			Ui.fill(g, x + 1, ty - 3, w - 2, 12, Ui.PANEL2);
			Ui.text(g, "function / builtin", x + 8, ty - 1, Ui.DIM);
			Ui.textRight(g, "calls", x + w - 220, ty - 1, Ui.DIM);
			Ui.textRight(g, "total ms", x + w - 140, ty - 1, Ui.DIM);
			Ui.textRight(g, "avg us", x + w - 20, ty - 1, Ui.DIM);
			List<String[]> rows = Kcd.ENGINE.profiler.rows();
			int max = Math.max(0, rows.size() * ROW_H - (bh - 44));
			extraScroll = Math.max(0, Math.min(extraScroll, max));
			double top = 0;
			for (String[] r : rows) {
				top = Math.max(top, 0);
			}
			double total = 0;
			for (String[] r : rows) {
				total = Math.max(total, Double.parseDouble(r[2]));
			}
			for (int i = 0; i < rows.size(); i++) {
				int ry = ty + 12 + i * ROW_H - (int) extraScroll;
				if (ry < by + 36 || ry > by + bh) {
					continue;
				}
				String[] r = rows.get(i);
				double frac = total <= 0 ? 0 : Double.parseDouble(r[2]) / total;
				Ui.fill(g, x + 4, ry - 1, (int) ((w - 8) * frac), ROW_H, 0x224C8DFF);
				Ui.text(g, Ui.fit(r[0], w - 260), x + 8, ry, Ui.TEXT);
				Ui.textRight(g, r[1], x + w - 220, ry, Ui.DIM);
				Ui.textRight(g, r[2], x + w - 140, ry, Ui.TEXT);
				Ui.textRight(g, r[3], x + w - 20, ry, Ui.DIM);
			}
			if (rows.isEmpty()) {
				Ui.text(g, "Nothing recorded yet. You can also use profile_start() / profile_stop() in a script.", x + 8, ty + 14, Ui.FAINT);
			}
		}
		g.disableScissor();
	}
	private final Btn[] chips = new Btn[4];
	private final Btn clear = new Btn("Clear", Btn.NORMAL, this::clearLog).size(48, 18);
	private final Btn copy = new Btn("Copy", Btn.NORMAL, this::copyLog).size(44, 18);
	private final Btn run = new Btn("Run", Btn.SUCCESS, BottomPanel::runInput).size(50, 18);
	private final Btn reset = new Btn("Reset", Btn.DANGER, ConsoleRunner::reset).size(50, 18);

	public BottomPanel() {
		String[] names = {"Print", "Info", "Warn", "Error"};
		for (int i = 0; i < 4; i++) {
			final int k = i;
			chips[i] = new Btn(names[i], Btn.NORMAL, () -> SHOW[k] = !SHOW[k]).size(Ui.width(names[i]) + 14, 18);
		}
	}

	private static void runInput() {
		String t = INPUT.getText();
		if (t.isBlank()) {
			return;
		}
		ConsoleRunner.submit(t);
		INPUT.setText("");
		histIndex = -1;
		follow = true;
	}

	public static void focusConsole() {
		consoleTab = true;
		INPUT.focused = true;
	}

	public boolean inputFocused() {
		return consoleTab && extra == 0 && INPUT.focused;
	}

	public void unfocus() {
		INPUT.focused = false;
	}

	private void clearLog() {
		Kcd.ENGINE.console.clear();
		scroll = 0;
		follow = true;
	}

	private void copyLog() {
		StringBuilder sb = new StringBuilder();
		for (LogConsole.Line l : visibleLines()) {
			sb.append(TIME.format(LocalTime.ofInstant(java.time.Instant.ofEpochMilli(l.timeMillis()), java.time.ZoneId.systemDefault()))).append(' ').append(l.message()).append('\n');
		}
		Ui.setClipboard(sb.toString());
	}

	private int mask() {
		int m = 0;
		for (int i = 0; i < 4; i++) {
			if (SHOW[i]) {
				m |= 1 << i;
			}
		}
		return m;
	}

	private List<LogConsole.Line> visibleLines() {
		List<LogConsole.Line> out = new ArrayList<>();
		for (LogConsole.Line l : Kcd.ENGINE.console.snapshot()) {
			if (consoleTab) {
				if (!l.source().equals(ConsoleRunner.ID) && l.level() != Level.SYSTEM) {
					continue;
				}
			}
			int bit = switch (l.level()) {
				case PRINT -> 0;
				case INFO, SYSTEM -> 1;
				case WARN -> 2;
				case ERROR -> 3;
			};
			if (SHOW[bit] || l.level() == Level.SYSTEM) {
				out.add(l);
			}
		}
		return out;
	}

	private static int colorOf(Level l) {
		return switch (l) {
			case PRINT -> Ui.TEXT;
			case INFO -> Ui.TEAL;
			case WARN -> Ui.YELLOW;
			case ERROR -> Ui.RED;
			case SYSTEM -> Ui.PURPLE;
		};
	}

	private int bodyY() {
		return y + 24;
	}

	private int bodyH() {
		return h - 24 - (consoleTab ? INPUT_H + 4 : 0);
	}

	private void rebuild() {
		long v = Kcd.ENGINE.console.version();
		if (v == cacheVersion && w == cacheW && mask() == cacheMask && consoleTab == cacheTab) {
			return;
		}
		cacheVersion = v;
		cacheW = w;
		cacheMask = mask();
		cacheTab = consoleTab;
		rows = new ArrayList<>();
		int textW = w - 16 - 52;
		for (LogConsole.Line l : visibleLines()) {
			String tag = l.source().isEmpty() || l.source().equals(ConsoleRunner.ID) ? "" : "[" + l.source() + "] ";
			boolean first = true;
			for (String part : l.message().split("\n", -1)) {
				String s = (first ? tag : "") + part;
				do {
					String cut = Ui.font().plainSubstrByWidth(s, textW);
					if (cut.isEmpty() && !s.isEmpty()) {
						cut = s.substring(0, 1);
					}
					rows.add(new Row(l, cut, first, colorOf(l.level())));
					s = s.substring(cut.length());
					first = false;
				} while (!s.isEmpty());
			}
		}
	}

	private double maxScroll() {
		return Math.max(0, rows.size() * ROW_H + 4 - bodyH());
	}

	public void render(GuiGraphicsExtractor g, int mx, int my) {
		Ui.panel(g, x, y, w, h, Ui.PANEL, Ui.BORDER);
		// header
		Ui.fill(g, x + 1, y + 1, w - 2, 22, Ui.PANEL2);
		tabLog.at(x + 4, y + 3);
		tabCon.at(x + 68, y + 3);
		tabDbg.at(x + 142, y + 3);
		tabProf.at(x + 198, y + 3);
		tabLog.style = consoleTab || extra != 0 ? Btn.NORMAL : Btn.PRIMARY;
		tabCon.style = consoleTab && extra == 0 ? Btn.PRIMARY : Btn.NORMAL;
		tabDbg.style = extra == 2 ? Btn.PRIMARY : pausedTask() != null ? Btn.DANGER : Btn.NORMAL;
		tabProf.style = extra == 3 ? Btn.PRIMARY : Btn.NORMAL;
		tabLog.draw(g, mx, my);
		tabCon.draw(g, mx, my);
		tabDbg.draw(g, mx, my);
		tabProf.draw(g, mx, my);
		if (extra != 0) {
			renderExtra(g, mx, my);
			return;
		}
		int rx = x + w - 4;
		rx -= copy.w;
		copy.at(rx, y + 3).draw(g, mx, my);
		rx -= clear.w + 4;
		clear.at(rx, y + 3).draw(g, mx, my);
		rx -= 10;
		for (int i = 3; i >= 0; i--) {
			rx -= chips[i].w + 3;
			chips[i].style = SHOW[i] ? Btn.PRIMARY : Btn.NORMAL;
			chips[i].at(rx, y + 3).draw(g, mx, my);
		}
		// body
		rebuild();
		int by = bodyY();
		int bh = bodyH();
		if (follow) {
			scroll = maxScroll();
		}
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
		g.enableScissor(x + 1, by, x + w - 1, by + bh);
		int first = Math.max(0, (int) (scroll / ROW_H));
		int last = Math.min(rows.size() - 1, (int) ((scroll + bh) / ROW_H));
		if (rows.isEmpty()) {
			Ui.text(g, consoleTab ? "Console ready. Type  help  for commands." : "Nothing logged yet. Run a script folder, or open the Console tab.", x + 8, by + 6, Ui.FAINT);
		}
		for (int i = first; i <= last; i++) {
			Row r = rows.get(i);
			int ry = (int) (by + 3 + i * ROW_H - scroll);
			boolean clickable = r.line().file() != null && r.line().line() > 0;
			if (clickable && Ui.in(mx, my, x + 1, ry - 1, w - 10, ROW_H)) {
				Ui.fill(g, x + 1, ry - 1, w - 10, ROW_H, 0x22FFFFFF);
			}
			if (r.line().level() == Level.ERROR) {
				Ui.fill(g, x + 1, ry - 1, 2, ROW_H, Ui.RED);
			}
			if (r.first()) {
				Ui.text(g, TIME.format(LocalTime.ofInstant(java.time.Instant.ofEpochMilli(r.line().timeMillis()), java.time.ZoneId.systemDefault())), x + 6, ry, Ui.FAINT);
			}
			Ui.text(g, r.text(), x + 52, ry, r.color());
			if (r.first() && clickable) {
				Ui.textRight(g, "open >", x + w - 10, ry, Ui.alpha(Ui.ACCENT, 200));
			}
		}
		g.disableScissor();
		if (maxScroll() > 0) {
			int th = Math.max(14, (int) (bh * (bh / (double) (rows.size() * ROW_H + 4))));
			int ty = by + (int) ((bh - th) * (scroll / maxScroll()));
			Ui.fill(g, x + w - 5, ty, 3, th, 0x66FFFFFF);
		}
		if (!follow) {
			Ui.textRight(g, "scrolled - click to follow", x + w - 12, by + bh - 11, Ui.alpha(Ui.YELLOW, 220));
		}
		if (consoleTab) {
			int iy = y + h - INPUT_H - 2;
			Ui.fill(g, x + 1, iy - 3, w - 2, 1, Ui.BORDER);
			String prompt = ConsoleRunner.prompt();
			int pw = Ui.width(prompt) + 8;
			Ui.text(g, prompt, x + 6, iy + 4, Ui.GREEN);
			INPUT.bounds(x + 6 + pw, iy, w - pw - 12 - 110, INPUT_H);
			Suggest.files = ConsoleRunner::filesBelowCwd;
			Suggest.source = p -> null;
			Suggest.currentDir = ConsoleRunner.cwd();
			INPUT.render(g, mx, my);
			run.at(x + w - 106, iy).draw(g, mx, my);
			reset.at(x + w - 52, iy).draw(g, mx, my);
			Ui.text(g, "Ctrl+Enter", x + w - 106, iy + 24, Ui.FAINT);
			Ui.text(g, "runs block", x + w - 106, iy + 34, Ui.FAINT);
		}
	}

	public boolean contains(double mx, double my) {
		return Ui.in(mx, my, x, y, w, h);
	}

	public boolean mouseClicked(double mx, double my, boolean shift, boolean dbl) {
		if (!contains(mx, my)) {
			INPUT.focused = false;
			return false;
		}
		for (Btn b : new Btn[] {tabLog, tabCon, tabDbg, tabProf}) {
			if (b.hit(mx, my)) {
				b.action.run();
				return true;
			}
		}
		if (extra != 0) {
			for (Btn b : extra == 2 ? new Btn[] {dbgGo, dbgOver, dbgInto, dbgOut, dbgClear} : new Btn[] {profStart, profStop, profReset}) {
				if (b.hit(mx, my)) {
					b.action.run();
					return true;
				}
			}
			INPUT.focused = false;
			return true;
		}
		for (Btn b : new Btn[] {clear, copy}) {
			if (b.hit(mx, my)) {
				b.action.run();
				return true;
			}
		}
		for (Btn b : chips) {
			if (b.hit(mx, my)) {
				b.action.run();
				return true;
			}
		}
		if (consoleTab) {
			if (run.hit(mx, my)) {
				run.action.run();
				return true;
			}
			if (reset.hit(mx, my)) {
				reset.action.run();
				return true;
			}
			if (INPUT.mouseClicked(mx, my, shift, dbl)) {
				return true;
			}
		}
		if (my >= bodyY() && my < bodyY() + bodyH()) {
			if (!follow) {
				follow = Ui.in(mx, my, x + w - 200, bodyY() + bodyH() - 14, 200, 14);
			}
			int i = (int) ((my - bodyY() - 3 + scroll) / ROW_H);
			if (i >= 0 && i < rows.size()) {
				LogConsole.Line l = rows.get(i).line();
				if (l.file() != null && l.line() > 0) {
					onOpen.accept(l.source(), l.file(), l.line());
				}
			}
		}
		INPUT.focused = false;
		return true;
	}

	public void mouseDragged(double mx, double my) {
		INPUT.mouseDragged(mx, my);
	}

	public void mouseReleased() {
		INPUT.mouseReleased();
	}

	public boolean mouseScrolled(double mx, double my, double dy) {
		if (extra == 0 && consoleTab && INPUT.mouseScrolled(mx, my, dy)) {
			return true;
		}
		if (!contains(mx, my)) {
			return false;
		}
		if (extra != 0) {
			extraScroll = Math.max(0, extraScroll - dy * ROW_H * 3);
			return true;
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll - dy * ROW_H * 3));
		follow = scroll >= maxScroll() - 1;
		return true;
	}

	public boolean keyPressed(KeyEvent e) {
		if (!inputFocused()) {
			return false;
		}
		if (e.key() == InputConstants.KEY_RETURN && e.hasControlDown()) {
			runInput();
			return true;
		}
		return INPUT.keyPressed(e);
	}

	public boolean charTyped(CharacterEvent e) {
		return inputFocused() && INPUT.charTyped(e);
	}
}
