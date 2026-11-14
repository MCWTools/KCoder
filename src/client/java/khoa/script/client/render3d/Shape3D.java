package khoa.script.client.render3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.script.client.game.G;
import khoa.script.client.ui.Colors;
import khoa.script.lang.KcdError;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** Something drawn in the 3D world: block/box overlays, lines, graphs, images. Fields are volatile because scripts edit them off the render thread. */
public abstract class Shape3D implements Val.KSettable {
	public final Session session;
	public volatile boolean visible = true;
	public volatile boolean deleted;
	/** Drawn on top of everything, also when blocks are in the way. */
	public volatile boolean throughWalls;

	protected Shape3D(Session s) {
		this.session = s;
	}

	abstract void render(Draw d, Vec3 cam);

	public abstract Object get(String name);

	/** @return false if the name is not a property of this shape */
	public abstract boolean set(String name, Object v);

	public void delete() {
		deleted = true;
		World3D.remove(this);
	}

	@Override
	public void setMember(String name, Object value) {
		if (name.equals("visible") || name.equals("through_walls")) {
			if (!(value instanceof Boolean b)) {
				throw new KcdError(name + " must be true or false (got " + Val.repr(value, 1) + ")", 0);
			}
			if (name.equals("visible")) {
				visible = b;
			} else {
				throughWalls = b;
			}
		} else if (!set(name, value)) {
			throw new KcdError(typeName() + " has no property '" + name + "'", 0);
		}
	}

	/** Fill + outline look shared by overlays and graphs. */
	public static final class Style {
		public volatile int fill = 0xFF4C8DFF;
		public volatile int outline = 0xFF4C8DFF;
		public volatile double fillAlpha = 0.25;
		public volatile double outlineAlpha = 1;
		public volatile double thickness = 0.03;

		public int fillArgb() {
			return argb(fill, fillAlpha);
		}

		public int outlineArgb() {
			return argb(outline, outlineAlpha);
		}

		public static int argb(int color, double alpha) {
			int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * (color >>> 24));
			return a << 24 | (color & 0xFFFFFF);
		}

		boolean set(String name, Object v) {
			switch (name) {
				case "fill", "fill_color" -> fill = Colors.strict(v, name);
				case "fill_alpha" -> fillAlpha = alpha(v, name);
				case "outline", "outline_color", "color" -> outline = Colors.strict(v, name);
				case "outline_alpha", "alpha" -> outlineAlpha = alpha(v, name);
				case "thickness" -> thickness = thick(v, name);
				default -> {
					return false;
				}
			}
			return true;
		}

		Object get(String name) {
			return switch (name) {
				case "fill", "fill_color" -> (double) (fill & 0xFFFFFFFFL);
				case "fill_alpha" -> fillAlpha;
				case "outline", "outline_color", "color" -> (double) (outline & 0xFFFFFFFFL);
				case "outline_alpha", "alpha" -> outlineAlpha;
				case "thickness" -> thickness;
				default -> null;
			};
		}
	}

	static double num(Object v, String what) {
		if (v instanceof Double d) {
			return d;
		}
		throw new KcdError(what + " must be a number", 0);
	}

	/** An alpha value: a number from 0 (invisible) to 1 (solid). */
	static double alpha(Object v, String what) {
		double d = num(v, what);
		if (d < 0 || d > 1) {
			throw new KcdError(what + " must be between 0 (invisible) and 1 (solid), got " + khoa.script.lang.Val.fmt(d), 0);
		}
		return d;
	}

	static double thick(Object v, String what) {
		double d = num(v, what);
		if (d < 0 || d > 16) {
			throw new KcdError(what + " must be between 0 and 16 blocks, got " + khoa.script.lang.Val.fmt(d), 0);
		}
		return d;
	}

	static Vec3 vec(Object v, String what) {
		if (v instanceof Val.Vec3 p) {
			return new Vec3(p.x(), p.y(), p.z());
		}
		throw new KcdError(what + " must be a vec3", 0);
	}

	static Val.Vec3 out(Vec3 v) {
		return new Val.Vec3(v.x, v.y, v.z);
	}

	// ====================================================================== overlays
	/** A filled + outlined shape around blocks (overlay_block) or around a free box (box_overlay). */
	public static final class Overlay extends Shape3D {
		public static final double EPS = 0.002;
		public final Style style = new Style();
		/** Blocks to cover; empty for a free box. */
		public volatile List<BlockPos> blocks = List.of();
		public volatile String shape = "hitbox";
		public volatile Vec3 pos = Vec3.ZERO;
		public volatile Vec3 size = new Vec3(1, 1, 1);
		public volatile boolean free;

		public Overlay(Session s) {
			super(s);
		}

		public static String normShape(String s) {
			String t = s.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
			if (t.equals("box") || t.equals("1x1box") || t.equals("1x1") || t.equals("cube") || t.equals("block")) {
				return "box";
			}
			if (t.equals("hitbox") || t.equals("outline") || t.equals("shape")) {
				return "hitbox";
			}
			if (t.equals("model") || t.equals("mesh")) {
				return "model";
			}
			if (t.equals("collision")) {
				return "collision";
			}
			throw new KcdError("Unknown overlay shape '" + s + "'", 0).withHint("use \"box\" (1x1x1), \"hitbox\", \"model\" or \"collision\"");
		}

		@Override
		public String typeName() {
			return "overlay";
		}

		@Override
		void render(Draw d, Vec3 cam) {
			int fa = style.fillArgb();
			int oa = style.outlineArgb();
			double t = style.thickness;
			if (free) {
				AABB b = new AABB(pos, pos.add(size));
				d.box(b, 0, fa);
				d.boxEdges(b, t, oa);
				return;
			}
			ClientLevel level = G.mc().level;
			if (level == null) {
				return;
			}
			String sh = shape;
			int n = 0;
			for (BlockPos p : blocks) {
				if (++n > 20000 || p.distToCenterSqr(cam) > 256 * 256) {
					continue;
				}
				BlockState st = level.getBlockState(p);
				switch (sh) {
					case "box" -> {
						AABB b = new AABB(p);
						d.box(b, EPS, fa);
						d.boxEdges(b, t, oa);
					}
					case "model" -> {
						ModelGeo g = ModelGeo.of(st);
						g.draw(d, p, EPS, fa, oa, t);
					}
					default -> {
						if (st.isAir()) {
							continue;
						}
						var vs = sh.equals("collision") ? st.getCollisionShape(level, p) : st.getShape(level, p);
						for (AABB local : vs.toAabbs()) {
							AABB b = local.move(p);
							d.box(b, EPS, fa);
							d.boxEdges(b, t, oa);
						}
					}
				}
			}
		}

		@Override
		public Object get(String name) {
			return switch (name) {
				case "shape" -> shape;
				case "pos" -> out(pos);
				case "size" -> out(size);
				case "visible" -> visible;
				case "through_walls" -> throughWalls;
				case "count" -> (double) blocks.size();
				default -> style.get(name);
			};
		}

		@Override
		public boolean set(String name, Object v) {
			switch (name) {
				case "shape" -> shape = normShape(Val.str(v));
				case "pos" -> pos = vec(v, "pos");
				case "size" -> size = v instanceof Double d ? new Vec3(d, d, d) : vec(v, "size");
				default -> {
					return style.set(name, v);
				}
			}
			return true;
		}
	}

	// ====================================================================== line
	public static final class Line extends Shape3D {
		public final Style style = new Style();
		public volatile Vec3 a = Vec3.ZERO;
		public volatile Vec3 b = Vec3.ZERO;

		public Line(Session s) {
			super(s);
			style.outline = 0xFFFFFFFF;
			style.thickness = 0.05;
		}

		@Override
		public String typeName() {
			return "line3d";
		}

		@Override
		void render(Draw d, Vec3 cam) {
			d.line(a, b, style.thickness, style.outlineArgb());
		}

		@Override
		public Object get(String name) {
			return switch (name) {
				case "origin", "from" -> out(a);
				case "endpoint", "to" -> out(b);
				case "length" -> a.distanceTo(b);
				case "visible" -> visible;
				default -> style.get(name);
			};
		}

		@Override
		public boolean set(String name, Object v) {
			switch (name) {
				case "origin", "from" -> a = vec(v, name);
				case "endpoint", "to" -> b = vec(v, name);
				default -> {
					return style.set(name, v);
				}
			}
			return true;
		}
	}

	// ====================================================================== graph / polylines
	public static final class Path extends Shape3D {
		public final Style style = new Style();
		public volatile List<List<Vec3>> strips = List.of();
		public volatile boolean closed;

		public Path(Session s) {
			super(s);
			style.outline = 0xFFFFFFFF;
			style.thickness = 0.04;
		}

		@Override
		public String typeName() {
			return "graph3d";
		}

		@Override
		void render(Draw d, Vec3 cam) {
			double t = style.thickness;
			int oa = style.outlineArgb();
			for (List<Vec3> pts : strips) {
				for (int i = 0; i + 1 < pts.size(); i++) {
					d.line(pts.get(i), pts.get(i + 1), t, oa);
				}
				if (closed && pts.size() > 2) {
					d.line(pts.get(pts.size() - 1), pts.get(0), t, oa);
				}
			}
		}

		@Override
		public Object get(String name) {
			return switch (name) {
				case "visible" -> visible;
				case "closed" -> closed;
				case "points" -> {
					List<Object> out = new ArrayList<>();
					for (List<Vec3> s : strips) {
						for (Vec3 p : s) {
							out.add(out(p));
						}
					}
					yield out;
				}
				default -> style.get(name);
			};
		}

		@Override
		public boolean set(String name, Object v) {
			if (name.equals("closed")) {
				closed = Val.truthy(v);
				return true;
			}
			return style.set(name, v);
		}
	}
}
