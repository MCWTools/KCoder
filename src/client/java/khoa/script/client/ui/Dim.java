package khoa.script.client.ui;

import khoa.script.lang.Val;

/** A size or position: a fraction of the screen (or of the parent element) plus pixels. A plain number in a script means pixels. */
public record Dim(double scale, double px) implements Val.KObject {
	@Override
	public String typeName() {
		return "dim";
	}

	public double resolve(double base) {
		return scale * base + px;
	}

	/** Script value (Dim or number) to Dim; null for anything else. */
	public static Dim of(Object o) {
		if (o instanceof Dim d) {
			return d;
		}
		if (o instanceof Double n) {
			return new Dim(0, n);
		}
		return null;
	}

	@Override
	public String toString() {
		return scale == 0 ? Val.str(px) + "px" : Val.str(scale * 100) + "%" + (px == 0 ? "" : (px > 0 ? "+" : "") + Val.str(px) + "px");
	}
}
