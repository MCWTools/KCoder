package khoa.script.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Everyday helpers for lists, maps, text, numbers and time: the things scripts end up writing by hand. */
public final class UtilLib {
	private static final Registry R = Registry.get();

	private UtilLib() {
	}

	private static double number(Object o, String fn) {
		if (o instanceof Double d) {
			return d;
		}
		throw new KcdError(fn + "(): every element must be a number, got " + Val.typeName(o), 0);
	}

	public static void register() {
		String c = "util";

		// ---------------------------------------------------------- lists
		R.def(c, "find(items:list, test:function) -> any", "The first element for which test(element) is true, or nil.", a -> {
			for (Object o : a.iter(0)) {
				if (Val.truthy(a.call(1, o))) {
					return o;
				}
			}
			return null;
		}).example("boss = find(get_entities_in_range(minecraft:wither, 80), lambda e: e.health > 100)");
		R.def(c, "find_index(items:list, test:function) -> number", "Position of the first element for which test is true, or -1.", a -> {
			int i = 0;
			for (Object o : a.iter(0)) {
				if (Val.truthy(a.call(1, o))) {
					return (double) i;
				}
				i++;
			}
			return -1.0;
		});
		R.def(c, "count(items:any, value?:any) -> number", "How many items a list, map or text has; with a value, how many times it appears (in text: how many times the part appears).", a -> {
			Object x = a.obj(0);
			if (!a.has(1)) {
				if (x instanceof String s) {
					return (double) s.length();
				}
				if (x instanceof java.util.Collection<?> col) {
					return (double) col.size();
				}
				if (x instanceof Map<?, ?> m) {
					return (double) m.size();
				}
				throw new KcdError("count(): expected a list, set, map or text, got " + Val.typeName(x), 0);
			}
			Object v = a.obj(1);
			int n = 0;
			if (x instanceof String s) {
				String part = Val.str(v);
				for (int i = part.isEmpty() ? -1 : s.indexOf(part); i >= 0; i = s.indexOf(part, i + part.length())) {
					n++;
				}
			} else if (x instanceof Map<?, ?> m) {
				for (Object val : m.values()) {
					if (Val.equal(val, v)) {
						n++;
					}
				}
			} else if (x instanceof java.util.Collection<?> col) {
				for (Object o : col) {
					if (Val.equal(o, v)) {
						n++;
					}
				}
			} else {
				throw new KcdError("count(): expected a list, set, map or text, got " + Val.typeName(x), 0);
			}
			return (double) n;
		}).example("print(count([1, 2, 2, 3], 2))   # 2\nprint(count(\"banana\", \"an\"))    # 2");
		R.def(c, "spawn(task:function, ...args:any)", "Runs a function in parallel, as its own script task, and carries on immediately. wait(), look_at_smooth() and other waiting calls only pause the task that makes them, so a spawned task can wait without holding up the rest.", a -> {
			Object fn = a.obj(0);
			List<Object> args = new ArrayList<>(a.list(1));
			a.in.session.spawn("spawn@" + a.in.file, a.in.file, in2 -> in2.callValue(fn, args, new LinkedHashMap<>(), "spawn"));
			return null;
		}).example("def watch():\n    while true:\n        look_at_smooth(get_closest_entity())\n        wait(1)\nspawn(watch)\nprint(\"this line runs at once\")");
		R.def(c, "count_if(items:list, test:function) -> number", "How many elements pass the test.", a -> {
			int n = 0;
			for (Object o : a.iter(0)) {
				if (Val.truthy(a.call(1, o))) {
					n++;
				}
			}
			return (double) n;
		});
		R.def(c, "unique(items:list) -> list", "The list without repeated values, first occurrences kept in order.", a -> new ArrayList<Object>(new LinkedHashSet<>(a.iter(0))));
		R.def(c, "flatten(items:list) -> list", "Merges nested lists one level: flatten([[1, 2], [3]]) = [1, 2, 3].", a -> {
			List<Object> out = new ArrayList<>();
			for (Object o : a.iter(0)) {
				if (o instanceof List<?> inner) {
					out.addAll(inner);
				} else {
					out.add(o);
				}
			}
			return out;
		});
		R.def(c, "chunk(items:list, size:number) -> list", "Cuts the list into pieces of at most size elements.", a -> {
			int size = (int) a.num(1);
			if (size < 1) {
				throw new KcdError("chunk(): size must be at least 1, got " + size, 0);
			}
			List<Object> src = a.iter(0);
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < src.size(); i += size) {
				out.add(new ArrayList<>(src.subList(i, Math.min(src.size(), i + size))));
			}
			return out;
		});
		R.def(c, "take(items:list, n:number) -> list", "The first n elements.", a -> {
			List<Object> src = a.iter(0);
			return new ArrayList<>(src.subList(0, Math.max(0, Math.min(src.size(), (int) a.num(1)))));
		});
		R.def(c, "drop(items:list, n:number) -> list", "Everything except the first n elements.", a -> {
			List<Object> src = a.iter(0);
			return new ArrayList<>(src.subList(Math.max(0, Math.min(src.size(), (int) a.num(1))), src.size()));
		});
		R.def(c, "shuffle(items:list) -> list", "A randomly ordered copy.", a -> {
			List<Object> out = new ArrayList<>(a.iter(0));
			java.util.Collections.shuffle(out);
			return out;
		});
		R.def(c, "min_by(items:list, key:function) -> any", "The element with the smallest key(element), or nil for an empty list.", a -> best(a, true));
		R.def(c, "max_by(items:list, key:function) -> any", "The element with the largest key(element), or nil for an empty list.", a -> best(a, false));
		R.def(c, "group_by(items:list, key:function) -> map", "Map from key(element) to the list of elements that gave it.", a -> {
			Map<Object, Object> out = new LinkedHashMap<>();
			for (Object o : a.iter(0)) {
				Object k = a.call(1, o);
				@SuppressWarnings("unchecked")
				List<Object> bucket = (List<Object>) out.computeIfAbsent(k, x -> new ArrayList<Object>());
				bucket.add(o);
			}
			return out;
		}).example("by_type = group_by(get_entities_in_range(minecraft:zombie, 50), lambda e: e.name)");
		R.def(c, "average(items:list) -> number", "Mean of a list of numbers (0 for an empty list).", a -> {
			List<Object> l = a.iter(0);
			double t = 0;
			for (Object o : l) {
				t += number(o, "average");
			}
			return l.isEmpty() ? 0.0 : t / l.size();
		});
		R.alias("mean", "average");
		R.def(c, "median(items:list) -> number", "Middle value of a list of numbers (0 for an empty list).", a -> {
			List<Double> l = new ArrayList<>();
			for (Object o : a.iter(0)) {
				l.add(number(o, "median"));
			}
			if (l.isEmpty()) {
				return 0.0;
			}
			java.util.Collections.sort(l);
			int m = l.size() / 2;
			return l.size() % 2 == 1 ? l.get(m) : (l.get(m - 1) + l.get(m)) / 2;
		});

		// ---------------------------------------------------------- maps
		R.def(c, "merge(a:map, b:map) -> map", "A new map with the keys of both; b wins when a key is in both.", a -> {
			Map<Object, Object> out = new LinkedHashMap<>(a.map(0));
			out.putAll(a.map(1));
			return out;
		});
		R.def(c, "from_pairs(pairs:list) -> map", "Builds a map from [[key, value], ...].", a -> {
			Map<Object, Object> out = new LinkedHashMap<>();
			for (Object o : a.iter(0)) {
				List<Object> pair = Val.toList(o);
				if (pair.size() != 2) {
					throw new KcdError("from_pairs(): every pair needs exactly 2 items, got " + pair.size(), 0);
				}
				out.put(pair.get(0), pair.get(1));
			}
			return out;
		});

		// ---------------------------------------------------------- text
		R.def(c, "pad_left(text:string, width:number, fill?:string=\" \") -> string", "Adds fill characters on the left until the text is width long: pad_left(\"7\", 3, \"0\") = \"007\".", a -> pad(a.str(0), (int) a.num(1), a.has(2) ? a.str(2) : " ", true));
		R.def(c, "pad_right(text:string, width:number, fill?:string=\" \") -> string", "Adds fill characters on the right until the text is width long.", a -> pad(a.str(0), (int) a.num(1), a.has(2) ? a.str(2) : " ", false));
		R.def(c, "repeat_text(text:string, times:number) -> string", "The text repeated: repeat_text(\"ab\", 3) = \"ababab\".", a -> a.str(0).repeat(Math.max(0, (int) a.num(1))));
		R.def(c, "capitalize(text:string) -> string", "First letter upper case.", a -> {
			String s = a.str(0);
			return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
		});
		R.def(c, "title_case(text:string) -> string", "Every word starts with a capital letter; underscores become spaces (zombie_villager -> Zombie Villager).", a -> {
			StringBuilder sb = new StringBuilder();
			for (String w : a.str(0).replace('_', ' ').split(" ", -1)) {
				if (!w.isEmpty()) {
					sb.append(sb.length() > 0 ? " " : "").append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
				}
			}
			return sb.toString();
		});
		R.def(c, "is_empty(x:any) -> boolean", "True for nil, empty text, an empty list or an empty map.", a -> {
			Object o = a.obj(0);
			return o == null || o instanceof String s && s.isEmpty() || o instanceof List<?> l && l.isEmpty() || o instanceof Map<?, ?> m && m.isEmpty();
		});
		R.def(c, "lines(text:string) -> list<string>", "Splits text into lines.", a -> new ArrayList<Object>(List.of(a.str(0).split("\\r?\\n", -1))));
		R.def(c, "truncate(text:string, max:number, suffix?:string=\"...\") -> string", "Shortens long text so it is at most max characters, ending in the suffix.", a -> {
			String s = a.str(0);
			int max = (int) a.num(1);
			String suf = a.has(2) ? a.str(2) : "...";
			return s.length() <= max ? s : s.substring(0, Math.max(0, max - suf.length())) + suf;
		});
		R.def(c, "format_number(n:number, decimals?:number=0) -> string", "Number with thousands separators: format_number(1234567.891, 1) = \"1,234,567.9\".", a -> String.format(Locale.ROOT, "%,." + Math.max(0, (int) a.num(1, 0)) + "f", a.num(0)));
		R.def(c, "format_duration(seconds:number) -> string", "Seconds as a short time: 3725 -> \"1h 2m 5s\".", a -> {
			long t = Math.max(0, Math.round(a.num(0)));
			long h = t / 3600;
			long m = t % 3600 / 60;
			long s = t % 60;
			StringBuilder sb = new StringBuilder();
			if (h > 0) {
				sb.append(h).append("h ");
			}
			if (h > 0 || m > 0) {
				sb.append(m).append("m ");
			}
			return sb.append(s).append('s').toString();
		});

		// ---------------------------------------------------------- numbers
		R.def(c, "smoothstep(edge0:number, edge1:number, x:number) -> number", "0 below edge0, 1 above edge1, a smooth S-curve in between.", a -> {
			double t = Math.max(0, Math.min(1, (a.num(2) - a.num(0)) / (a.num(1) - a.num(0))));
			return t * t * (3 - 2 * t);
		});
		R.def(c, "inverse_lerp(a:number, b:number, value:number) -> number", "Where value sits between a and b (0 at a, 1 at b).", a -> {
			if (a.num(0) == a.num(1)) {
				throw new KcdError("inverse_lerp(): a and b must differ", 0);
			}
			return (a.num(2) - a.num(0)) / (a.num(1) - a.num(0));
		});
		R.def(c, "wrap(x:number, min:number, max:number) -> number", "Wraps x around into [min, max): wrap(370, 0, 360) = 10.", a -> {
			double span = a.num(2) - a.num(1);
			if (span <= 0) {
				throw new KcdError("wrap(): max must be larger than min", 0);
			}
			double r = (a.num(0) - a.num(1)) % span;
			return (r < 0 ? r + span : r) + a.num(1);
		});
		R.def(c, "approx(a:number, b:number, epsilon?:number=0.0001) -> boolean", "True if the two numbers differ by less than epsilon.", a -> Math.abs(a.num(0) - a.num(1)) < a.num(2, 0.0001));
		R.def(c, "percent(part:number, whole:number) -> number", "part as a percentage of whole (0 when whole is 0).", a -> a.num(1) == 0 ? 0.0 : a.num(0) / a.num(1) * 100);
	}

	private static Object best(khoa.script.lang.Args a, boolean min) {
		Object bestItem = null;
		double bestKey = 0;
		boolean first = true;
		for (Object o : a.iter(0)) {
			double k = number(a.call(1, o), min ? "min_by" : "max_by");
			if (first || (min ? k < bestKey : k > bestKey)) {
				bestItem = o;
				bestKey = k;
				first = false;
			}
		}
		return bestItem;
	}

	private static String pad(String s, int width, String fill, boolean left) {
		if (fill.isEmpty() || s.length() >= width) {
			return s;
		}
		StringBuilder pad = new StringBuilder();
		while (pad.length() < width - s.length()) {
			pad.append(fill);
		}
		pad.setLength(width - s.length());
		return left ? pad + s : s + pad;
	}
}
