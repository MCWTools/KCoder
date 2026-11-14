package khoa.script.client.render3d;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import khoa.script.client.game.BlockRef;
import khoa.script.client.game.G;
import khoa.script.client.ui.Colors;
import khoa.script.client.ui.UiLib;
import khoa.script.client.ui.UiElement;
import khoa.script.client.ui.UiManager;
import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Script API of the 3D drawing system: overlay_block, box_overlay, line_3d, graphs, image_3d and the color namespace. */
public final class Lib3D {
	private static final Registry R = Registry.get();
	private static final int MAX_POINTS = 200_000;

	private Lib3D() {
	}

	/** The value of the global {@code color}. */
	public record ColorNs() implements Val.KObject {
		@Override
		public String typeName() {
			return "color_ns";
		}
	}

	private static double d(Object[] g, int i, double def) {
		return i < g.length && g[i] instanceof Double v ? v : def;
	}

	private static Vec3 v(Val.Vec3 p) {
		return new Vec3(p.x(), p.y(), p.z());
	}

	/** Reads (fill, fill_alpha, outline, outline_alpha, thickness) from args starting at {@code i}. */
	private static void style(Args a, int i, Shape3D.Style s) {
		set(a, i, "fill", s);
		set(a, i + 1, "fill_alpha", s);
		set(a, i + 2, "outline", s);
		set(a, i + 3, "outline_alpha", s);
		set(a, i + 4, "thickness", s);
	}

	private static void set(Args a, int i, String prop, Shape3D.Style s) {
		if (a.obj(i) != null) {
			try {
				s.set(prop, a.obj(i));
			} catch (KcdError e) {
				throw new KcdError(a.fn.name + "(): argument " + (i + 1) + " ('" + (i < a.fn.params.size() ? a.fn.params.get(i).name() : prop) + "'): " + e.getMessage(), 0);
			}
		}
	}

	private static void walls(Args a, int i, Shape3D s) {
		if (a.obj(i) != null) {
			s.throughWalls = a.bool(i, false);
		}
	}

	/** Reads (color, alpha, thickness) from args starting at {@code i}. */
	private static void lineStyle(Args a, int i, Shape3D.Style s) {
		set(a, i, "outline", s);
		set(a, i + 1, "outline_alpha", s);
		set(a, i + 2, "thickness", s);
	}

	private static void addBlock(Object o, List<BlockPos> out) {
		if (o instanceof BlockRef b) {
			out.add(b.blockPos());
		} else if (o instanceof Val.Vec3 p) {
			out.add(G.bp(p));
		} else if (o instanceof List<?> l) {
			for (Object x : l) {
				addBlock(x, out);
			}
		} else if (o != null) {
			throw new KcdError("Expected a block, a position or a list of them, got " + Val.typeName(o), 0);
		}
	}

	private static Shape3D shape(Object s) {
		return (Shape3D) s;
	}

	private static Vec3 pt(Object o) {
		if (o instanceof Val.Vec3 p) {
			return v(p);
		}
		throw new KcdError("A graph point must be a vec3, got " + Val.typeName(o), 0);
	}

	private static Shape3D.Path path(Args a, List<List<Vec3>> strips, int styleAt) {
		return path(a, strips, styleAt, styleAt + 3);
	}

	private static Shape3D.Path path(Args a, List<List<Vec3>> strips, int styleAt, int wallsAt) {
		int n = 0;
		for (List<Vec3> s : strips) {
			n += s.size();
		}
		if (n > MAX_POINTS) {
			throw new KcdError("Graph too big (" + n + " points, max " + MAX_POINTS + ")", 0).withHint("lower steps");
		}
		Shape3D.Path p = new Shape3D.Path(a.in.session);
		p.strips = strips;
		lineStyle(a, styleAt, p.style);
		walls(a, wallsAt, p);
		return World3D.add(p);
	}

	private static void moveLine(Shape3D.Line l, Object[] g) {
		l.a = v((Val.Vec3) g[0]);
		if (g.length > 1 && g[1] instanceof Val.Vec3 to) {
			l.b = v(to);
		}
	}

	public static void register() {
		World3D.install();
		String c = "3d";
		R.typeDoc("shape3d", "Anything drawn in the 3D world. Methods: delete(), show(), hide(). Properties can be changed: shape.visible = false");
		R.typeDoc("overlay", "A filled + outlined shape on blocks or a box. Properties: shape, fill, fill_alpha, outline, outline_alpha, thickness, pos, size, visible.");
		R.typeDoc("line3d", "A 3D line. Methods: set_endpoint(v), set_origin(v), color(c), appearance(c, alpha, thickness), delete().");
		R.typeDoc("graph3d", "A graph or curve in the world (plot_function, plot_surface ...).");
		R.typeDoc("image3d", "A picture standing in the world (image_3d).");
		for (String t : List.of("overlay", "line3d", "graph3d", "image3d")) {
			R.typeParent(t, "shape3d");
		}

		// ---------------------------------------------------------- color namespace
		R.typeDoc("color_ns", "Color helpers: color.rgb(255, 0, 0), color.hsv(200, 1, 1), color.hex(\"#ff8800\"), color.red ...");
		R.constants.put("color", new ColorNs());
		R.method("color_ns", "rgb(r:number, g:number, b:number) -> number", "One color value from red, green, blue (0-255). Use it anywhere a color is wanted.", (in, s, g) -> col(Colors.rgb((Double) g[0], (Double) g[1], (Double) g[2], 255)));
		R.method("color_ns", "rgba(r:number, g:number, b:number, a:number) -> number", "Color with alpha (0-255).", (in, s, g) -> col(Colors.rgb((Double) g[0], (Double) g[1], (Double) g[2], (Double) g[3])));
		R.method("color_ns", "hsv(h:number, s:number, v:number) -> number", "Color from hue 0-360, saturation 0-1, value 0-1.", (in, s, g) -> col(Colors.hsv((Double) g[0], (Double) g[1], (Double) g[2], 255)));
		R.method("color_ns", "hex(text:string) -> number", "Color from \"#RRGGBB\" or a name like \"orange\".", (in, s, g) -> col(Colors.strict(g[0], "color.hex() argument")));
		R.method("color_ns", "lerp(a:any, b:any, t:number) -> number", "Blend between two colors (t from 0 to 1).", (in, s, g) -> col(Colors.lerp(Colors.strict(g[0], "color.lerp() first color"), Colors.strict(g[1], "color.lerp() second color"), (Double) g[2])));
		for (String name : List.of("white", "black", "red", "green", "blue", "yellow", "orange", "purple", "pink", "cyan", "gray", "lime", "brown", "teal")) {
			R.prop("color_ns", name, "number", "The color " + name + ".", (in, s, g) -> col(Colors.parse(name, 0xFFFFFFFF)));
		}

		// ---------------------------------------------------------- overlays
		String styleDoc = " fill and outline take color.rgb(...), \"#RRGGBB\" or a name. *_alpha is 0 (invisible) to 1 (solid). thickness is in blocks (0.1 = a tenth of a block).";
		R.def(c, "overlay_block(block:any, shape?:overlay_shape=\"hitbox\", fill?:any, fill_alpha?:number=0.25, outline?:any, outline_alpha?:number=1, thickness?:number=0.03, through_walls?:boolean=false) -> overlay",
			"Draws an overlay on a block (or a list of blocks) and finds its position by itself. shape: \"box\" (1x1x1), \"hitbox\" (the block's outline shape), \"model\" (follows the real model) or \"collision\"." + styleDoc, a -> {
				Shape3D.Overlay o = new Shape3D.Overlay(a.in.session);
				List<BlockPos> bl = new ArrayList<>();
				addBlock(a.obj(0), bl);
				o.blocks = bl;
				o.shape = a.has(1) ? Shape3D.Overlay.normShape(a.str(1)) : "hitbox";
				style(a, 2, o.style);
				walls(a, 7, o);
				return World3D.add(o);
			}).example("for b in get_blocks_around(6, \"minecraft:diamond_ore\"):\n    overlay_block(b, \"model\", color.rgb(0, 200, 255), 0.2, color.rgb(255, 255, 255), 1, 0.02)");
		R.def(c, "box_overlay(pos:vec3, size:any, fill?:any, fill_alpha?:number=0.25, outline?:any, outline_alpha?:number=1, thickness?:number=0.03, through_walls?:boolean=false) -> overlay",
			"A box at free decimal coordinates: pos is the lowest corner, size is a vec3 (or one number for a cube)." + styleDoc, a -> {
				Shape3D.Overlay o = new Shape3D.Overlay(a.in.session);
				o.free = true;
				o.pos = v(a.vec3(0));
				o.size = a.obj(1) instanceof Double n ? new Vec3(n, n, n) : v(a.vec3(1));
				style(a, 2, o.style);
				walls(a, 7, o);
				return World3D.add(o);
			}).example("box = box_overlay(vec3(10.5, 64, 3.25), vec3(2, 1, 0.5), color.rgb(255, 0, 0), 0.3, color.rgb(255, 255, 0), 1, 0.05)");
		R.alias("box_outline", "box_overlay");

		// ---------------------------------------------------------- lines
		R.def(c, "line_3d(from:vec3, to:vec3, color?:any, alpha?:number=1, thickness?:number=0.05, through_walls?:boolean=false) -> line3d", "Draws a straight line in the world. Keep it in a variable to move or recolor it later.", a -> {
			Shape3D.Line l = new Shape3D.Line(a.in.session);
			l.a = v(a.vec3(0));
			l.b = v(a.vec3(1));
			lineStyle(a, 2, l.style);
			walls(a, 5, l);
			return World3D.add(l);
		}).example("ln = line_3d(get_player_position(), vec3(0, 80, 0), color.rgb(255, 80, 0))\nln:set_endpoint(vec3(10, 70, 10))\nln:delete()");
		R.alias("draw_line_3d", "line_3d");

		R.method("line3d", "set_endpoint(to:vec3)", "Moves the far end.", (in, s, g) -> {
			((Shape3D.Line) s).b = v((Val.Vec3) g[0]);
			return null;
		});
		R.method("line3d", "setendpoint(to:vec3)", "Same as set_endpoint.", (in, s, g) -> {
			((Shape3D.Line) s).b = v((Val.Vec3) g[0]);
			return null;
		});
		R.method("line3d", "set_origin(from:vec3, to?:vec3)", "Moves the start (and the far end too when you pass a second point).", (in, s, g) -> {
			moveLine((Shape3D.Line) s, g);
			return null;
		});
		R.method("line3d", "setorigin(from:vec3, to?:vec3)", "Same as set_origin.", (in, s, g) -> {
			moveLine((Shape3D.Line) s, g);
			return null;
		});
		R.method("line3d", "set_points(from:vec3, to:vec3)", "Moves both ends at once.", (in, s, g) -> {
			moveLine((Shape3D.Line) s, g);
			return null;
		});
		R.method("line3d", "setpoints(from:vec3, to:vec3)", "Same as set_points.", (in, s, g) -> {
			moveLine((Shape3D.Line) s, g);
			return null;
		});
		for (String t : List.of("line3d", "graph3d")) {
			R.method(t, "color(c:any)", "Sets the color.", (in, s, g) -> {
				shape(s).set("color", g[0]);
				return null;
			});
			for (String nm : List.of("appearance", "appearence")) {
				R.method(t, nm + "(c:any, alpha?:number, thickness?:number)", "Sets color, alpha (0-1) and thickness (blocks) at once.", (in, s, g) -> {
					Shape3D sh = shape(s);
					sh.set("color", g[0]);
					if (g.length > 1 && g[1] != null) {
						sh.set("alpha", g[1]);
					}
					if (g.length > 2 && g[2] != null) {
						sh.set("thickness", g[2]);
					}
					return null;
				});
			}
		}

		// ---------------------------------------------------------- common shape members
		R.method("shape3d", "delete()", "Removes it from the world.", (in, s, g) -> {
			shape(s).delete();
			return null;
		});
		R.method("shape3d", "show()", "Makes it visible.", (in, s, g) -> {
			shape(s).visible = true;
			return null;
		});
		R.method("shape3d", "hide()", "Hides it (without deleting).", (in, s, g) -> {
			shape(s).visible = false;
			return null;
		});
		R.method("shape3d", "set(name:string, value:any)", "Changes a property by name.", (in, s, g) -> {
			shape(s).setMember((String) g[0], g[1]);
			return null;
		});
		R.prop("shape3d", "visible", "boolean", "False while hidden.", (in, s, g) -> shape(s).visible);
		R.prop("shape3d", "through_walls", "boolean", "True when it is drawn on top of blocks (readable and writable).", (in, s, g) -> shape(s).throughWalls);
		for (String p : List.of("fill", "fill_alpha", "outline", "outline_alpha", "thickness", "alpha")) {
			R.prop("shape3d", p, "number", "Look of the shape (readable and writable).", (in, s, g) -> shape(s).get(p));
		}
		for (String p : List.of("shape", "pos", "size", "count")) {
			R.prop("overlay", p, "any", "Overlay " + p + " (readable and writable).", (in, s, g) -> shape(s).get(p));
		}
		for (String p : List.of("origin", "endpoint", "length")) {
			R.prop("line3d", p, "any", "Line " + p + " (readable and writable).", (in, s, g) -> shape(s).get(p));
		}
		R.method("overlay", "set_fill(c:any, alpha?:number)", "Sets the fill color and alpha.", (in, s, g) -> {
			shape(s).set("fill", g[0]);
			if (g.length > 1 && g[1] != null) {
				shape(s).set("fill_alpha", g[1]);
			}
			return null;
		});
		R.method("overlay", "set_outline(c:any, alpha?:number, thickness?:number)", "Sets the outline color, alpha and thickness.", (in, s, g) -> {
			shape(s).set("outline", g[0]);
			if (g.length > 1 && g[1] != null) {
				shape(s).set("outline_alpha", g[1]);
			}
			if (g.length > 2 && g[2] != null) {
				shape(s).set("thickness", g[2]);
			}
			return null;
		});
		R.method("overlay", "set_shape(shape:overlay_shape)", "Changes between \"box\", \"hitbox\", \"model\", \"collision\".", (in, s, g) -> {
			shape(s).set("shape", g[0]);
			return null;
		});
		R.method("overlay", "set_blocks(blocks:any)", "Replaces the covered blocks.", (in, s, g) -> {
			List<BlockPos> bl = new ArrayList<>();
			addBlock(g[0], bl);
			((Shape3D.Overlay) s).blocks = bl;
			((Shape3D.Overlay) s).free = false;
			return null;
		});

		R.def(c, "delete(thing:any)", "Deletes an overlay, line, graph, image or GUI element.", a -> {
			Object o = a.obj(0);
			if (o instanceof Shape3D sh) {
				sh.delete();
			} else if (o instanceof UiElement e) {
				UiManager.remove(e);
			} else if (o != null) {
				throw a.bad(0, "an overlay, line, graph, image or GUI element");
			}
			return null;
		});
		R.def(c, "clear_overlays()", "Deletes every 3D shape your script made.", a -> {
			World3D.clear(a.in.session);
			return null;
		});

		// ---------------------------------------------------------- graphs
		R.def(c, "plot_points(points:list<vec3>, color?:any, alpha?:number=1, thickness?:number=0.04, closed?:boolean=false, through_walls?:boolean=false) -> graph3d", "Connects points with a line.", a -> {
			List<Vec3> pts = new ArrayList<>();
			for (Object o : a.list(0)) {
				pts.add(pt(o));
			}
			Shape3D.Path p = path(a, List.of(pts), 1, 5);
			p.closed = a.bool(4, false);
			return p;
		});
		R.def(c, "plot_values(origin:vec3, values:list<number>, spacing?:number=1, scale?:number=1, color?:any, alpha?:number=1, thickness?:number=0.04, through_walls?:boolean=false) -> graph3d", "A line chart: value i is drawn at origin + (i*spacing, value*scale, 0).", a -> {
			Vec3 o = v(a.vec3(0));
			double sp = a.num(2, 1);
			double sc = a.num(3, 1);
			List<Vec3> pts = new ArrayList<>();
			int i = 0;
			for (Object x : a.list(1)) {
				pts.add(o.add(i++ * sp, (x instanceof Double dv ? dv : 0) * sc, 0));
			}
			return path(a, List.of(pts), 4);
		});
		R.def(c, "plot_function(origin:vec3, fn:function, x_min:number, x_max:number, steps?:number=100, color?:any, alpha?:number=1, thickness?:number=0.04, plane?:plane=\"xy\", through_walls?:boolean=false) -> graph3d",
			"Graphs y = fn(x) standing in the world. plane \"xy\" runs along X, \"zy\" along Z. Every unit is one block.", a -> {
				Vec3 o = v(a.vec3(0));
				double x0 = a.num(2);
				double x1 = a.num(3);
				int steps = Math.max(2, Math.min(MAX_POINTS, a.intv(4, 100)));
				boolean zy = a.str(8, "xy").equalsIgnoreCase("zy");
				List<Vec3> pts = new ArrayList<>();
				for (int i = 0; i <= steps; i++) {
					double x = x0 + (x1 - x0) * i / steps;
					Object y = a.call(1, x);
					if (y instanceof Double yv && !Double.isNaN(yv) && !Double.isInfinite(yv)) {
						pts.add(zy ? o.add(0, yv, x) : o.add(x, yv, 0));
					}
				}
				return path(a, List.of(pts), 5, 9);
			}).example("g = plot_function(vec3(0, 70, 0), lambda x: sin(x) * 2, -10, 10, 200, color.rgb(0, 255, 120))");
		R.def(c, "plot_parametric(origin:vec3, fn:function, t_min:number, t_max:number, steps?:number=200, color?:any, alpha?:number=1, thickness?:number=0.04, through_walls?:boolean=false) -> graph3d",
			"A 3D curve: fn(t) returns a vec3 offset from origin.", a -> {
				Vec3 o = v(a.vec3(0));
				double t0 = a.num(2);
				double t1 = a.num(3);
				int steps = Math.max(2, Math.min(MAX_POINTS, a.intv(4, 200)));
				List<Vec3> pts = new ArrayList<>();
				for (int i = 0; i <= steps; i++) {
					Object r = a.call(1, t0 + (t1 - t0) * i / steps);
					if (r instanceof Val.Vec3 p) {
						pts.add(o.add(p.x(), p.y(), p.z()));
					}
				}
				return path(a, List.of(pts), 5, 8);
			}).example("helix = plot_parametric(get_player_position(), lambda t: vec3(cos(t) * 2, t * 0.3, sin(t) * 2), 0, 25)");
		R.def(c, "plot_surface(origin:vec3, fn:function, x_min:number, x_max:number, z_min:number, z_max:number, steps?:number=24, color?:any, alpha?:number=1, thickness?:number=0.03, through_walls?:boolean=false) -> graph3d",
			"A 3D graph y = fn(x, z) drawn as a wire grid.", a -> {
				Vec3 o = v(a.vec3(0));
				double x0 = a.num(2);
				double x1 = a.num(3);
				double z0 = a.num(4);
				double z1 = a.num(5);
				int n = Math.max(2, Math.min(150, a.intv(6, 24)));
				Vec3[][] grid = new Vec3[n + 1][n + 1];
				for (int i = 0; i <= n; i++) {
					for (int j = 0; j <= n; j++) {
						double x = x0 + (x1 - x0) * i / n;
						double z = z0 + (z1 - z0) * j / n;
						Object y = a.call(1, x, z);
						double yv = y instanceof Double dv && !Double.isNaN(dv) && !Double.isInfinite(dv) ? dv : 0;
						grid[i][j] = o.add(x, yv, z);
					}
				}
				List<List<Vec3>> strips = new ArrayList<>();
				for (int i = 0; i <= n; i++) {
					List<Vec3> row = new ArrayList<>();
					List<Vec3> col = new ArrayList<>();
					for (int j = 0; j <= n; j++) {
						row.add(grid[i][j]);
						col.add(grid[j][i]);
					}
					strips.add(row);
					strips.add(col);
				}
				return path(a, strips, 7, 10);
			}).example("surf = plot_surface(vec3(0, 70, 0), lambda x, z: sin(x) * cos(z), -5, 5, -5, 5, 30, color.rgb(255, 160, 0))");
		R.method("graph3d", "set_points(points:list<vec3>)", "Replaces the graph with a new line through these points.", (in, s, g) -> {
			List<Vec3> pts = new ArrayList<>();
			for (Object o : (List<?>) g[0]) {
				pts.add(pt(o));
			}
			((Shape3D.Path) s).strips = List.of(pts);
			return null;
		});

		// ---------------------------------------------------------- images
		R.def(c, "image_3d(texture:string, pos:vec3, width?:number=1, height?:number, facing?:any=\"camera\", alpha?:number=1) -> image3d",
			"Shows a picture in the world. texture: a .png file in your script folder (\"./logo.png\") or a game texture id. facing: \"camera\" (always toward you), \"horizontal\", \"north\"/\"south\"/\"east\"/\"west\", or a vec3 direction. Height defaults to the picture's proportions.", a -> {
				String tex = a.str(0);
				String id = tex.contains(":") && !tex.contains("/") || tex.startsWith("minecraft:") || tex.startsWith("khoascript:") ? tex : UiLib.loadTexture(a.in.session.fileRoot, tex);
				if (!id.contains(":")) {
					id = "minecraft:" + id;
				}
				Identifier ident = Identifier.tryParse(id);
				if (ident == null) {
					throw new KcdError("'" + tex + "' is not a texture", 0);
				}
				ImageShape im = new ImageShape(a.in.session);
				im.texture = ident;
				im.pos = v(a.vec3(1));
				im.width = a.num(2, 1);
				int[] size = UiLib.TEXTURE_SIZES.get(id);
				im.height = a.has(3) ? a.num(3) : size != null ? im.width * size[1] / size[0] : im.width;
				im.setFacing(a.has(4) ? a.obj(4) : "camera");
				im.alpha = a.num(5, 1);
				return World3D.add(im);
			}).client().example("pic = image_3d(\"./logo.png\", get_player_position() + vec3(0, 3, 0), 2)");
		for (String p : List.of("width", "height", "alpha", "facing")) {
			R.prop("image3d", p, "any", "Image " + p + " (readable and writable).", (in, s, g) -> shape(s).get(p));
		}
		R.prop("image3d", "pos", "vec3", "Centre of the picture (readable and writable).", (in, s, g) -> shape(s).get("pos"));

		R.def(c, "get_overlay_count() -> number", "How many 3D shapes exist right now (all scripts).", a -> (double) World3D.count());
		R.def(c, "get_blocks_around(range:number, id?:string) -> list<block>", "Blocks in a square (cube) around the player, optionally only one block id. Air is skipped. Range is at most 24.", a -> {
			BlockPos o = G.me().blockPosition();
			int r = Math.max(0, Math.min(a.intv(0), 24));
			String want = a.has(1) ? G.norm(a.str(1)) : null;
			List<Object> out = new ArrayList<>();
			for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -r, -r), o.offset(r, r, r))) {
				var st = G.level().getBlockState(p);
				if (st.isAir()) {
					continue;
				}
				if (want == null || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString().equals(want)) {
					out.add(new BlockRef(p.immutable()));
				}
			}
			return out;
		}).client();
	}

	private static Double col(int argb) {
		return (double) (argb & 0xFFFFFFFFL);
	}
}
