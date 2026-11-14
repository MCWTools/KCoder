package khoa.script.client.ui;

import java.util.Locale;
import java.util.Map;

/** Script colors: "#RRGGBB", "#AARRGGBB", a color name, or a number 0xAARRGGBB (0xRRGGBB means opaque). */
public final class Colors {
	private static final Map<String, Integer> NAMES = Map.ofEntries(
		Map.entry("white", 0xFFFFFFFF), Map.entry("black", 0xFF000000), Map.entry("red", 0xFFFF5555), Map.entry("green", 0xFF55FF55),
		Map.entry("blue", 0xFF5555FF), Map.entry("yellow", 0xFFFFFF55), Map.entry("orange", 0xFFFFAA00), Map.entry("purple", 0xFFAA55FF),
		Map.entry("pink", 0xFFFF77CC), Map.entry("cyan", 0xFF55FFFF), Map.entry("gray", 0xFF888888), Map.entry("grey", 0xFF888888),
		Map.entry("dark_gray", 0xFF444444), Map.entry("light_gray", 0xFFBBBBBB), Map.entry("gold", 0xFFFFAA00), Map.entry("lime", 0xFF88FF00),
		Map.entry("brown", 0xFF8B5A2B), Map.entry("teal", 0xFF2EC4B6), Map.entry("transparent", 0x00000000), Map.entry("none", 0x00000000));

	private Colors() {
	}

	/** Parses a script color; {@code def} when the value is nil or not understood. */
	public static int parse(Object o, int def) {
		if (o instanceof Double d) {
			long v = d.longValue();
			return (v & 0xFF000000L) == 0 && v != 0 ? (int) (0xFF000000L | v) : (int) v;
		}
		if (o instanceof String s) {
			String t = s.trim().toLowerCase(Locale.ROOT);
			Integer named = NAMES.get(t);
			if (named != null) {
				return named;
			}
			if (t.startsWith("#")) {
				t = t.substring(1);
			} else if (t.startsWith("0x")) {
				t = t.substring(2);
			}
			try {
				if (t.length() == 6) {
					return (int) (0xFF000000L | Long.parseLong(t, 16));
				}
				if (t.length() == 8) {
					return (int) Long.parseLong(t, 16);
				}
				if (t.length() == 3) {
					int r = Integer.parseInt(t.substring(0, 1), 16) * 17;
					int g = Integer.parseInt(t.substring(1, 2), 16) * 17;
					int b = Integer.parseInt(t.substring(2, 3), 16) * 17;
					return 0xFF000000 | r << 16 | g << 8 | b;
				}
			} catch (NumberFormatException ignored) {
				// falls through
			}
		}
		return def;
	}

	/** Like parse, but a value that is not a color is an error. */
	public static int strict(Object o, String what) {
		int bad = 0x12345678;
		int c = parse(o, bad);
		if (c == bad && !(o instanceof String s && s.trim().equalsIgnoreCase("#12345678"))) {
			throw new khoa.script.lang.KcdError(what + " must be a color: color.rgb(255, 0, 0), \"#RRGGBB\", or a name like \"red\" (got " + khoa.script.lang.Val.repr(o, 1) + ")", 0);
		}
		return c;
	}

	public static boolean valid(Object o) {
		return o == null || parse(o, 0x12345678) != 0x12345678 || o instanceof String s && s.trim().equals("#12345678");
	}

	public static int withAlpha(int argb, double mul) {
		int a = (int) Math.round((argb >>> 24) * Math.max(0, Math.min(1, mul)));
		return a << 24 | (argb & 0xFFFFFF);
	}

	public static int lerp(int a, int b, double t) {
		int out = 0;
		for (int sh = 24; sh >= 0; sh -= 8) {
			int x = a >>> sh & 0xFF;
			int y = b >>> sh & 0xFF;
			out |= (int) Math.round(x + (y - x) * t) << sh;
		}
		return out;
	}

	public static int rgb(double r, double g, double b, double a) {
		return c(a) << 24 | c(r) << 16 | c(g) << 8 | c(b);
	}

	private static int c(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	public static int hsv(double h, double s, double v, double a) {
		h = (h % 360 + 360) % 360;
		double c = v * s;
		double x = c * (1 - Math.abs(h / 60 % 2 - 1));
		double m = v - c;
		double r;
		double g;
		double b;
		if (h < 60) {
			r = c; g = x; b = 0;
		} else if (h < 120) {
			r = x; g = c; b = 0;
		} else if (h < 180) {
			r = 0; g = c; b = x;
		} else if (h < 240) {
			r = 0; g = x; b = c;
		} else if (h < 300) {
			r = x; g = 0; b = c;
		} else {
			r = c; g = 0; b = x;
		}
		return rgb((r + m) * 255, (g + m) * 255, (b + m) * 255, a);
	}
}
