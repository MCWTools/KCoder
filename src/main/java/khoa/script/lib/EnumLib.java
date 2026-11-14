package khoa.script.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/**
 * Named option lists in the style of Lua enums: {@code enum.entity_sort.distance}. A value is just its text ("distance"), so it works
 * everywhere a plain string does; the point is that the editor lists every valid choice after {@code enum.entity_sort.}, and a wrong
 * choice is an error that names the valid ones.
 */
public final class EnumLib {
	private static final Registry R = Registry.get();

	private EnumLib() {
	}

	/** {@code enum} itself. */
	public record EnumNs() implements Val.KObject {
		@Override
		public String typeName() {
			return "enum_ns";
		}

		@Override
		public String toString() {
			return "<enum>";
		}
	}

	/** One group such as {@code enum.entity_sort}. */
	public record Group(String name) implements Val.KObject {
		@Override
		public String typeName() {
			return "enum_" + name;
		}

		@Override
		public String toString() {
			return "<enum " + name + ": " + String.join(", ", R.enums.get(typeName())) + ">";
		}
	}

	/** Registers a group. {@code values} alternates value, description. */
	private static void group(String name, String doc, String... values) {
		String type = "enum_" + name;
		List<String> names = new ArrayList<>();
		R.typeDoc(type, doc);
		for (int i = 0; i < values.length; i += 2) {
			String v = values[i];
			names.add(v);
			R.prop(type, v, "string", values[i + 1], (in, s, g) -> v);
		}
		R.enums.put(type, names);
		Group g = new Group(name);
		R.prop("enum_ns", name, type, doc, (in, s, a) -> g);
		R.method(type, "values() -> list<string>", "Every choice of this group as a list.", (in, s, a) -> new ArrayList<Object>(names));
	}

	/** Adds a group whose choices come from another module (the client registers the key names this way). */
	public static void extraGroup(String name, String doc, List<String> choices) {
		String[] flat = new String[choices.size() * 2];
		for (int i = 0; i < choices.size(); i++) {
			flat[i * 2] = choices.get(i);
			flat[i * 2 + 1] = "Key " + choices.get(i) + ".";
		}
		group(name, doc, flat);
	}

	private static void alias(String alias, String target) {
		String type = "enum_" + target;
		Group g = new Group(target);
		R.prop("enum_ns", alias, type, "Same as enum." + target + ".", (in, s, a) -> g);
	}

	public static void register() {
		R.constants.put("enum", new EnumNs());
		R.typeDoc("enum_ns", "Lists of valid choices for options. Type enum. to see the groups, e.g. get_entities(sort = enum.entity_sort.health).");

		group("entity_sort", "How get_entities orders its result.",
			"distance", "Nearest first (default).",
			"distance_desc", "Farthest first.",
			"angle", "Closest to where you are looking first.",
			"health", "Lowest health first.",
			"name", "By display name, A to Z.",
			"none", "Unsorted.");
		alias("distance_sort", "entity_sort");
		group("overlay_shape", "What overlay_block outlines.",
			"box", "A full 1x1x1 cube.",
			"hitbox", "The outline shape the game draws around the block.",
			"model", "Follows the real block model.",
			"collision", "The collision shape (what you bump into).");
		group("easing", "Speed curve of animate().",
			"linear", "Constant speed.",
			"ease_in", "Starts slow.",
			"ease_out", "Ends slow.",
			"ease_in_out", "Slow at both ends.",
			"back", "Overshoots a little and comes back.",
			"bounce", "Bounces at the end.");
		group("block_face", "A side of a block.",
			"up", "Top.",
			"down", "Bottom.",
			"north", "North side (negative Z).",
			"south", "South side (positive Z).",
			"east", "East side (positive X).",
			"west", "West side (negative X).");
		group("plane", "Which way plot_function runs.",
			"xy", "Along X, height is Y.",
			"zy", "Along Z, height is Y.");
		group("image_facing", "Which way image_3d faces.",
			"camera", "Always toward you.",
			"horizontal", "Lying flat.",
			"north", "Fixed, facing north.",
			"south", "Fixed, facing south.",
			"east", "Fixed, facing east.",
			"west", "Fixed, facing west.");
	}

	/** Used by tests: all group types with their values. */
	public static Map<String, List<String>> groups() {
		return R.enums;
	}
}
