package khoa.script.client.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** World API: blocks, light, time, weather, raycasts, crosshair. */
public final class WorldLib {
	private static final Registry R = Registry.get();
	private static final int MAX_SCAN = 40_000;

	private WorldLib() {
	}

	private static BlockPos pos(Args a, int i) {
		return G.bp(a.vec3(i));
	}

	private static Map<Object, Object> neighbours(BlockPos p) {
		Map<Object, Object> out = new java.util.LinkedHashMap<>();
		for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[] { net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.NORTH,
			net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST }) {
			out.put(d.getName(), new BlockRef(p.relative(d)));
		}
		return out;
	}

	private static BlockState state(BlockPos p) {
		return G.level().getBlockState(p);
	}

	private static String blockId(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	/** Id of the block in a state (used by the block change watcher). */
	public static String idOf(BlockState s) {
		return blockId(s);
	}

	public static Map<Object, Object> propertiesOf(BlockState s) {
		return properties(s);
	}

	static Map<Object, Object> properties(BlockState s) {
		Map<Object, Object> m = new LinkedHashMap<>();
		for (Property<?> p : s.getProperties()) {
			Object v = s.getValue(p);
			m.put(p.getName(), v instanceof Boolean || v instanceof String ? v : v instanceof Integer i ? (Object) (double) i : v.toString());
		}
		return m;
	}

	private static Object fluidId(BlockPos p) {
		var f = G.level().getFluidState(p);
		return f.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(f.getType()).toString();
	}

	static Map<Object, Object> hit(HitResult h) {
		Map<Object, Object> m = new LinkedHashMap<>();
		if (h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
			m.put("hit", true);
			m.put("type", "block");
			m.put("pos", new Val.Vec3(b.getBlockPos().getX(), b.getBlockPos().getY(), b.getBlockPos().getZ()));
			m.put("point", G.v(b.getLocation()));
			m.put("face", b.getDirection().getName());
			m.put("block", new BlockRef(b.getBlockPos()));
		} else if (h instanceof EntityHitResult e) {
			m.put("hit", true);
			m.put("type", "entity");
			m.put("point", G.v(e.getLocation()));
			m.put("entity", new EntityRef(e.getEntity()));
		} else {
			m.put("hit", false);
			m.put("type", "miss");
		}
		return m;
	}

	public static void register() {
		R.typeDoc("block", "A block position. block.id, block.pos, block.solid, block.air, block.properties ...");
		String c = "world";
		R.def(c, "get_world() -> string", "Dimension id you are in (alias of get_dimension).", a -> G.level().dimension().identifier().toString()).client();
		R.def(c, "get_dimension() -> string", "Dimension id, e.g. minecraft:overworld.", a -> G.level().dimension().identifier().toString()).client();
		R.def(c, "get_world_time() -> number", "Ticks the world has existed.", a -> (double) G.level().getGameTime()).client();
		R.def(c, "get_day_time() -> number", "Time of day, 0-23999 (0 = sunrise, 6000 = noon, 13000 = night).", a -> (double) (G.level().getDefaultClockTime() % 24000L)).client();
		R.def(c, "get_weather() -> string", "clear, rain or thunder.", a -> G.level().isThundering() ? "thunder" : G.level().isRaining() ? "rain" : "clear").client();
		R.def(c, "get_biome(pos?:vec3) -> string", "Biome id at a position (default: where you stand).", a -> {
			BlockPos p = a.has(0) ? pos(a, 0) : G.me().blockPosition();
			return G.level().getBiome(p).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
		}).client();
		R.def(c, "get_loaded_chunk_count() -> number", "How many chunks the client currently has loaded.", a -> (double) G.level().getChunkSource().getLoadedChunksCount()).client();
		R.def(c, "get_loaded_chunks(radius?:number) -> list<vec2>", "Chunk coordinates (vec2 of chunk x, chunk z) that are loaded within radius chunks of the player (default 8, max 32).", a -> {
			int r = a.has(0) ? Math.max(0, Math.min(a.intv(0), 32)) : 8;
			var me = G.me().chunkPosition();
			var src = G.level().getChunkSource();
			List<Object> out = new ArrayList<>();
			for (int x = me.x() - r; x <= me.x() + r; x++) {
				for (int z = me.z() - r; z <= me.z() + r; z++) {
					if (src.hasChunk(x, z)) {
						out.add(new Val.Vec2(x, z));
					}
				}
			}
			return out;
		}).client();
		R.def(c, "get_block(pos:vec3) -> block", "The block at a position.", a -> new BlockRef(pos(a, 0))).client().example("b = get_block(vec3(0, 64, 0))\nprint(b.id)");
		R.def(c, "get_neighbour(block:vec3) -> map", "The 6 blocks touching a block, as a map keyed up, down, north, south, east, west. Every value is a block (air included): block.air tells if it is empty, block.pos gives its coordinates, and id, tags, properties describe it.", a -> neighbours(pos(a, 0))).client().example("n = get_neighbour(get_block(vec3(0, 64, 0)))\nfor face, b in n.items():\n    if not b.air:\n        print(face, b.id, b.pos, b.tags)");
		R.alias("get_neighbor", "get_neighbour");
		R.alias("get_neighbours", "get_neighbour");
		R.def(c, "get_block_id(pos:vec3) -> string", "Block id at a position, e.g. minecraft:stone.", a -> blockId(state(pos(a, 0)))).client();
		R.def(c, "get_block_state(pos:vec3) -> map", "Block id and properties at a position.", a -> {
			BlockState s = state(pos(a, 0));
			Map<Object, Object> m = new LinkedHashMap<>();
			m.put("id", blockId(s));
			m.put("properties", properties(s));
			return m;
		}).client();
		R.def(c, "get_block_property(pos:vec3, property:string) -> any", "One block property (facing, powered, age ...), or nil.", a -> properties(state(pos(a, 0))).get(a.str(1))).client();
		R.def(c, "get_block_light(pos:vec3) -> number", "Block light level 0-15.", a -> (double) G.level().getBrightness(LightLayer.BLOCK, pos(a, 0))).client();
		R.def(c, "get_sky_light(pos:vec3) -> number", "Sky light level 0-15.", a -> (double) G.level().getBrightness(LightLayer.SKY, pos(a, 0))).client();
		R.def(c, "get_block_collision(pos:vec3) -> boolean", "True if the block has a collision box.", a -> !state(pos(a, 0)).getCollisionShape(G.level(), pos(a, 0)).isEmpty()).client();
		R.def(c, "get_block_outline(pos:vec3) -> map", "Map with min and max corners of the block's outline box in the world, or nil if it has none.", a -> {
			BlockPos p = pos(a, 0);
			var shape = state(p).getShape(G.level(), p);
			if (shape.isEmpty()) {
				return null;
			}
			var b = shape.bounds().move(p);
			Map<Object, Object> m = new LinkedHashMap<>();
			m.put("min", new Val.Vec3(b.minX, b.minY, b.minZ));
			m.put("max", new Val.Vec3(b.maxX, b.maxY, b.maxZ));
			return m;
		}).client();
		R.def(c, "is_chunk_loaded(pos:vec3) -> boolean", "True if the chunk at this position is loaded.", a -> G.level().isLoaded(pos(a, 0))).client();
		R.def(c, "is_block_air(pos:vec3) -> boolean", "True if the block is air.", a -> state(pos(a, 0)).isAir()).client();
		R.def(c, "is_block_solid(pos:vec3) -> boolean", "True if the block is solid.", a -> state(pos(a, 0)).isSolid()).client();
		R.def(c, "is_block_replaceable(pos:vec3) -> boolean", "True if blocks can be placed into it (air, grass, water ...).", a -> state(pos(a, 0)).canBeReplaced()).client();
		R.def(c, "is_block_fluid(pos:vec3) -> boolean", "True if the block holds a fluid.", a -> !G.level().getFluidState(pos(a, 0)).isEmpty()).client();
		R.def(c, "get_fluid(pos:vec3) -> string", "Fluid id at a position (minecraft:water, minecraft:lava) or nil.", a -> fluidId(pos(a, 0))).client();
		R.def(c, "get_blocks_in_range(center:vec3, range:number, id?:string) -> list<block>", "Blocks within a cube around a point (optionally only one block id). Limited to " + MAX_SCAN + " blocks scanned.", a -> {
			BlockPos o = pos(a, 0);
			int r = Math.min(a.intv(1), 20);
			String want = a.has(2) ? G.norm(a.str(2)) : null;
			List<Object> out = new ArrayList<>();
			int scanned = 0;
			for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -r, -r), o.offset(r, r, r))) {
				if (++scanned > MAX_SCAN) {
					break;
				}
				if (want == null ? !state(p).isAir() : blockId(state(p)).equals(want)) {
					out.add(new BlockRef(p.immutable()));
				}
			}
			return out;
		}).client();
		R.def(c, "get_nearest_block(id:string, range?:number=16) -> block", "The nearest block of an id within range (max 20) of you, or nil.", a -> {
			BlockPos o = G.me().blockPosition();
			int r = Math.min(a.intv(1, 16), 20);
			String want = G.norm(a.str(0));
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			int scanned = 0;
			for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -r, -r), o.offset(r, r, r))) {
				if (++scanned > MAX_SCAN) {
					break;
				}
				double d = p.distToCenterSqr(G.me().getEyePosition());
				if (d < bestD && blockId(state(p)).equals(want)) {
					bestD = d;
					best = p.immutable();
				}
			}
			return best == null ? null : new BlockRef(best);
		}).client();
		R.def(c, "raycast(from:vec3, to:vec3) -> map", "Casts a ray against blocks. Map: hit, type, pos, point, face, block.", a -> {
			Vec3 f = G.mv(a.vec3(0));
			Vec3 t = G.mv(a.vec3(1));
			return hit(G.level().clip(new ClipContext(f, t, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, G.me())));
		}).client();
		R.def(c, "get_raycast_result() -> map", "What your crosshair points at (block or entity): hit, type, pos, point, face, block, entity.", a -> hit(G.mc().hitResult)).client();
		R.def(c, "get_crosshair_block() -> block", "The block under your crosshair, or nil.", a -> {
			HitResult h = G.mc().hitResult;
			return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? new BlockRef(b.getBlockPos()) : null;
		}).client();
		R.def(c, "get_crosshair_entity() -> entity", "The entity under your crosshair, or nil.", a -> {
			Entity e = G.mc().crosshairPickEntity;
			return e == null ? null : new EntityRef(e);
		}).client();
		R.def(c, "is_block(x:any) -> boolean", "True if x is a block handle.", a -> a.obj(0) instanceof BlockRef);

		R.prop("block", "id", "string", "Block id.", (in, s, g) -> ((BlockRef) s).id());
		R.prop("block", "name", "string", "Display name.", (in, s, g) -> state(((BlockRef) s).blockPos()).getBlock().getName().getString());
		R.prop("block", "pos", "vec3", "Block coordinates.", (in, s, g) -> {
			BlockPos p = ((BlockRef) s).blockPos();
			return new Val.Vec3(p.getX(), p.getY(), p.getZ());
		});
		R.prop("block", "x", "number", "Block X.", (in, s, g) -> (double) ((BlockRef) s).blockPos().getX());
		R.prop("block", "y", "number", "Block Y.", (in, s, g) -> (double) ((BlockRef) s).blockPos().getY());
		R.prop("block", "z", "number", "Block Z.", (in, s, g) -> (double) ((BlockRef) s).blockPos().getZ());
		R.prop("block", "center", "vec3", "Centre of the block.", (in, s, g) -> ((BlockRef) s).pos());
		R.prop("block", "tags", "list<string>", "Every block tag the block is in, such as minecraft:logs or minecraft:mineable/axe.", (in, s, g) -> {
			List<Object> out = new ArrayList<>();
			state(((BlockRef) s).blockPos()).tags().forEach(t -> out.add(t.location().toString()));
			out.sort((x, y) -> x.toString().compareTo(y.toString()));
			return out;
		});
		R.prop("block", "neighbours", "map", "The 6 blocks touching this one (see get_neighbour).", (in, s, g) -> neighbours(((BlockRef) s).blockPos()));
		R.prop("block", "air", "boolean", "True if air.", (in, s, g) -> state(((BlockRef) s).blockPos()).isAir());
		R.prop("block", "solid", "boolean", "True if solid.", (in, s, g) -> state(((BlockRef) s).blockPos()).isSolid());
		R.prop("block", "replaceable", "boolean", "True if blocks can be placed into it.", (in, s, g) -> state(((BlockRef) s).blockPos()).canBeReplaced());
		R.prop("block", "fluid", "string", "Fluid id or nil.", (in, s, g) -> fluidId(((BlockRef) s).blockPos()));
		R.prop("block", "light", "number", "Light the block emits.", (in, s, g) -> (double) state(((BlockRef) s).blockPos()).getLightEmission());
		R.prop("block", "properties", "map", "Block state properties.", (in, s, g) -> properties(state(((BlockRef) s).blockPos())));
		R.method("block", "distance(other:vec3) -> number", "Distance from the block centre to a point.", (in, s, g) -> {
			var t = khoa.script.lang.Convert.toVec3(g[0]);
			if (t == null) {
				throw new KcdError("distance(): argument must be a vec3, entity or block", 0);
			}
			return ((BlockRef) s).pos().sub(t).length();
		});
	}
}
