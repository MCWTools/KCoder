package khoa.script.client.render3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** The quads and unique quad edges of a block's baked model, in block-local coordinates (0..1). Cached per block state. */
final class ModelGeo {
	private static final Map<BlockState, ModelGeo> CACHE = new HashMap<>();
	private static final ModelGeo EMPTY = new ModelGeo(List.of(), List.of());

	/** 12 floats per quad: 4 corners x (x,y,z). */
	final List<float[]> quads;
	/** 6 floats per edge. */
	final List<float[]> edges;

	private ModelGeo(List<float[]> quads, List<float[]> edges) {
		this.quads = quads;
		this.edges = edges;
	}

	static ModelGeo of(BlockState st) {
		if (st.isAir()) {
			return EMPTY;
		}
		ModelGeo g = CACHE.get(st);
		if (g == null) {
			if (CACHE.size() > 600) {
				CACHE.clear();
			}
			g = build(st);
			CACHE.put(st, g);
		}
		return g;
	}

	static void clearCache() {
		CACHE.clear();
	}

	private static ModelGeo build(BlockState st) {
		List<float[]> quads = new ArrayList<>();
		List<float[]> edges = new ArrayList<>();
		try {
			var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st);
			List<BlockStateModelPart> parts = new ArrayList<>();
			model.collectParts(RandomSource.create(42), parts);
			Set<String> seen = new HashSet<>();
			List<Direction> dirs = new ArrayList<>();
			dirs.add(null);
			dirs.addAll(List.of(Direction.values()));
			for (BlockStateModelPart part : parts) {
				for (Direction dir : dirs) {
					for (BakedQuad q : part.getQuads(dir)) {
						float[] c = new float[12];
						for (int i = 0; i < 4; i++) {
							var p = q.position(i);
							c[i * 3] = p.x();
							c[i * 3 + 1] = p.y();
							c[i * 3 + 2] = p.z();
						}
						quads.add(c);
						for (int i = 0; i < 4; i++) {
							int j = (i + 1) % 4;
							float[] e = { c[i * 3], c[i * 3 + 1], c[i * 3 + 2], c[j * 3], c[j * 3 + 1], c[j * 3 + 2] };
							if (key(e, seen)) {
								edges.add(e);
							}
						}
					}
				}
			}
		} catch (RuntimeException e) {
			return EMPTY;
		}
		return new ModelGeo(quads, edges);
	}

	/** True the first time an edge (in either direction) is seen. */
	private static boolean key(float[] e, Set<String> seen) {
		String a = Math.round(e[0] * 512) + "," + Math.round(e[1] * 512) + "," + Math.round(e[2] * 512);
		String b = Math.round(e[3] * 512) + "," + Math.round(e[4] * 512) + "," + Math.round(e[5] * 512);
		if (a.equals(b)) {
			return false;
		}
		return seen.add(a.compareTo(b) < 0 ? a + ">" + b : b + ">" + a);
	}

	void draw(Draw d, BlockPos p, double grow, int fillArgb, int outlineArgb, double thickness) {
		int px = p.getX();
		int py = p.getY();
		int pz = p.getZ();
		if ((fillArgb >>> 24) != 0) {
			for (float[] q : quads) {
				// push each corner slightly away from the block centre so the fill does not fight the real model
				double[] v = new double[12];
				for (int i = 0; i < 4; i++) {
					for (int k = 0; k < 3; k++) {
						double c = q[i * 3 + k];
						v[i * 3 + k] = c + (c > 0.5 ? grow : -grow);
					}
				}
				d.quad(px + v[0], py + v[1], pz + v[2], px + v[3], py + v[4], pz + v[5], px + v[6], py + v[7], pz + v[8], px + v[9], py + v[10], pz + v[11], fillArgb);
			}
		}
		if ((outlineArgb >>> 24) != 0 && thickness > 0) {
			for (float[] e : edges) {
				d.line(px + e[0], py + e[1], pz + e[2], px + e[3], py + e[4], pz + e[5], thickness, outlineArgb);
			}
		}
	}
}
