package khoa.script.client.gui;

import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** A centered dialog: either a one-line text prompt (with validation message) or a yes/no confirmation. */
public final class Modal {
	public boolean done;
	private final String title;
	private final String message;
	private final CodeArea input;
	private final Function<String, String> validator;
	private final Consumer<String> onOk;
	private final Btn ok;
	private final Btn cancel;
	private String error = "";
	private final int width = 380;

	private Modal(String title, String message, CodeArea input, Function<String, String> validator, Consumer<String> onOk, String okLabel, int style) {
		this.title = title;
		this.message = message;
		this.input = input;
		this.validator = validator;
		this.onOk = onOk;
		this.ok = new Btn(okLabel, style, this::accept).size(90, 20);
		this.cancel = new Btn("Cancel", Btn.NORMAL, () -> done = true).size(90, 20);
		if (input != null) {
			input.focused = true;
			input.onEnter = () -> {
				accept();
				return true;
			};
			input.onChange = () -> error = validator == null ? "" : orEmpty(validator.apply(input.getText().trim()));
		}
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s;
	}

	public static Modal input(String title, String hint, String initial, Function<String, String> validator, Consumer<String> ok) {
		CodeArea a = new CodeArea(true);
		a.setText(initial);
		a.goTo(0, initial.length());
		return new Modal(title, hint, a, validator, ok, "OK", Btn.PRIMARY);
	}

	public static Modal confirm(String title, String message, String okLabel, boolean danger, Runnable ok) {
		return new Modal(title, message, null, null, s -> ok.run(), okLabel, danger ? Btn.DANGER : Btn.PRIMARY);
	}

	private void accept() {
		if (input != null) {
			String t = input.getText().trim();
			String bad = validator == null ? null : validator.apply(t);
			if (bad != null) {
				error = bad;
				return;
			}
			done = true;
			onOk.accept(t);
		} else {
			done = true;
			onOk.accept("");
		}
	}

	private int height() {
		return input != null ? 108 : 86;
	}

	public void draw(GuiGraphicsExtractor g, int mx, int my, int sw, int sh) {
		g.fill(0, 0, sw, sh, 0xAA000000);
		int x = (sw - width) / 2;
		int y = (sh - height()) / 2 - 20;
		Ui.panel(g, x + 3, y + 3, width, height(), 0x66000000, 0);
		Ui.panel(g, x, y, width, height(), Ui.PANEL, Ui.BORDER);
		Ui.fill(g, x, y, width, 2, Ui.ACCENT);
		Ui.text(g, title, x + 12, y + 10, Ui.TEXT);
		if (input != null) {
			Ui.text(g, Ui.fit(message, width - 24), x + 12, y + 24, Ui.DIM);
			input.bounds(x + 12, y + 38, width - 24, 18);
			input.render(g, mx, my);
			Ui.text(g, Ui.fit(error, width - 24), x + 12, y + 60, Ui.RED);
		} else {
			Ui.text(g, Ui.fit(message, width - 24), x + 12, y + 28, Ui.DIM);
		}
		int by = y + height() - 30;
		ok.at(x + width - 200, by);
		cancel.at(x + width - 102, by);
		ok.draw(g, mx, my);
		cancel.draw(g, mx, my);
	}

	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_ESCAPE) {
			done = true;
			return true;
		}
		if (input != null) {
			input.keyPressed(e);
		} else if (e.key() == InputConstants.KEY_RETURN) {
			accept();
		}
		return true;
	}

	public boolean charTyped(CharacterEvent e) {
		if (input != null) {
			input.charTyped(e);
		}
		return true;
	}

	public boolean mouseClicked(double mx, double my, boolean shift, boolean dbl) {
		if (ok.hit(mx, my)) {
			accept();
		} else if (cancel.hit(mx, my)) {
			done = true;
		} else if (input != null) {
			input.mouseClicked(mx, my, shift, dbl);
			input.focused = true;
		}
		return true;
	}

	public void mouseDragged(double mx, double my) {
		if (input != null) {
			input.mouseDragged(mx, my);
		}
	}

	public void mouseReleased() {
		if (input != null) {
			input.mouseReleased();
		}
	}
}
