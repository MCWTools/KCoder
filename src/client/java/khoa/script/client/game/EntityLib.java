package khoa.script.client.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Args;
import khoa.script.lang.Convert;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Entity and player API: handles, queries and the member list of "entity" / "player" values. */
public final class EntityLib {
	private static final Registry R = Registry.get();

	private EntityLib() {
	}

	// ---------------------------------------------------------------- shared lookups
	static double reachDistance(Entity e) {
		return G.distanceToBox(G.eye(), e.getBoundingBox());
	}

	static Val.Vec3 center(Entity e) {
		AABB b = e.getBoundingBox();
		return new Val.Vec3((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2);
	}

	static Val.Rotation rotationOf(Entity e) {
		return new Val.Rotation(Convert.wrapDegrees(e.getYRot()), e.getXRot());
	}

	static Object bbox(Entity e) {
		AABB b = e.getBoundingBox();
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("min", new Val.Vec3(b.minX, b.minY, b.minZ));
		m.put("max", new Val.Vec3(b.maxX, b.maxY, b.maxZ));
		return m;
	}

	private static LivingEntity living(Entity e, String what) {
		if (e instanceof LivingEntity l) {
			return l;
		}
		throw new KcdError(what + ": " + G.entityId(e) + " is not a living entity", 0);
	}

	static Object held(Entity e) {
		return e instanceof LivingEntity l ? new ItemRef(l.getMainHandItem().copy(), -1) : null;
	}

	private static boolean typeMatches(Entity e, Object types) {
		if (types == null) {
			return true;
		}
		String id = G.entityId(e);
		if (types instanceof String s) {
			return s.equals("*") || G.norm(s).equals(id);
		}
		if (types instanceof List<?> l) {
			for (Object o : l) {
				if (o instanceof String s && (s.equals("*") || G.norm(s).equals(id))) {
					return true;
				}
			}
			return false;
		}
		throw new KcdError("type must be an entity id such as minecraft:allay, or a list of them", 0);
	}

	/** Core query behind get_entities and friends. */
	static List<Entity> query(Object types, boolean aliveOnly, boolean players, boolean self, double maxDist, String sort) {
		Entity me = G.me();
		Vec3 eye = me.getEyePosition();
		List<Entity> out = new ArrayList<>();
		for (Entity e : G.level().entitiesForRendering()) {
			if (e == me && !self) {
				continue;
			}
			if (!players && e instanceof Player) {
				continue;
			}
			if (aliveOnly && !e.isAlive()) {
				continue;
			}
			if (!typeMatches(e, types)) {
				continue;
			}
			if (maxDist >= 0 && G.distanceToBox(eye, e.getBoundingBox()) > maxDist) {
				continue;
			}
			out.add(e);
		}
		Comparator<Entity> byDist = Comparator.comparingDouble(e -> G.distanceToBox(eye, e.getBoundingBox()));
		switch (sort == null ? "distance" : sort) {
			case "distance" -> out.sort(byDist);
			case "distance_desc" -> out.sort(byDist.reversed());
			case "health" -> out.sort(Comparator.comparingDouble(e -> e instanceof LivingEntity l ? l.getHealth() : 0));
			case "name" -> out.sort(Comparator.comparing(e -> e.getName().getString()));
			case "angle" -> {
				Vec3 look = G.me().getLookAngle();
				out.sort(Comparator.comparingDouble(e -> -look.dot(e.getBoundingBox().getCenter().subtract(eye).normalize())));
			}
			case "none" -> {
			}
			default -> throw new KcdError("sort must be distance, distance_desc, angle, health, name or none", 0);
		}
		return out;
	}

	private static List<Object> refs(List<Entity> l, int max) {
		List<Object> out = new ArrayList<>();
		for (Entity e : l) {
			if (max >= 0 && out.size() >= max) {
				break;
			}
			out.add(new EntityRef(e));
		}
		return out;
	}

	private static void ent(String name, String ret, String doc, Function<Entity, Object> f) {
		// nil in, nil out: get_entity_position(get_nearest_entity(...)) is nil when nothing was found, so  if not result:  works
		R.def("entity", name + "(entity:entity) -> " + ret, doc + " Gives nil if the entity is nil.", a -> a.obj(0) == null ? null : f.apply(G.entity(a, 0))).client();
	}

	private static void pl(String name, String ret, String doc, Function<Entity, Object> f) {
		R.def("player", name + "(player?:player) -> " + ret, doc + " Without an argument: you. With a player (or player name): that player, if inside your render distance.",
			a -> f.apply(G.entityOrMe(a, 0))).client();
	}

	// ---------------------------------------------------------------- registration
	public static void register() {
		entityQueries();
		entityGetters();
		playerGetters();
		members();
	}

	private static void entityQueries() {
		String c = "entity";
		R.def(c, "get_entities(type?:string, alive?:boolean=true, sort?:entity_sort=\"distance\", max_distance?:number, max_count?:number, players?:boolean=true, self?:boolean=false) -> list<entity>",
			"Entities around you. type is an id like minecraft:allay (or a list of ids, or nil for all). max_distance is measured from your eyes to the entity's box. sort: use enum.entity_sort (distance, distance_desc, angle, health, name, none).",
			a -> refs(query(a.obj(0), a.bool(1, true), a.bool(5, true), a.bool(6, false), a.has(3) ? a.num(3) : -1, a.str(2, "distance")), a.has(4) ? a.intv(4) : -1))
			.client().example("allays = get_entities(type = minecraft:allay, max_distance = 20, sort = \"distance\")");
		R.def(c, "get_entities_in_range(type:string, range:number) -> list<entity>",
			"Entities of a type whose box is within `range` blocks of your EYES (like interaction reach), nearest first. get_entity_in_range is the same.",
			a -> refs(query(a.obj(0), true, true, false, a.num(1), "distance"), -1)).client().example("allays = get_entities_in_range(minecraft:allay, 3.5)");
		R.alias("get_entity_in_range", "get_entities_in_range");
		R.def(c, "get_nearest_entity(type?:string, max_distance?:number) -> entity", "The nearest entity (of a type), or nil. Distance is measured from your eyes.", a -> {
			List<Entity> l = query(a.obj(0), true, true, false, a.has(1) ? a.num(1) : -1, "distance");
			return l.isEmpty() ? null : new EntityRef(l.get(0));
		}).client().example("allay = get_nearest_entity(minecraft:allay)");
		R.def(c, "get_nearest_entities(type?:string, count?:number=5) -> list<entity>", "The nearest few entities, nearest first.", a -> refs(query(a.obj(0), true, true, false, -1, "distance"), a.intv(1, 5))).client();
		R.def(c, "get_nearest_player(max_distance?:number) -> player", "The nearest OTHER player inside your render distance, or nil.", a -> {
			List<Entity> l = query(null, true, true, false, a.has(0) ? a.num(0) : -1, "distance");
			for (Entity e : l) {
				if (e instanceof Player) {
					return new EntityRef(e);
				}
			}
			return null;
		}).client().example("opponent = get_nearest_player()");
		R.def(c, "get_players(max_distance?:number) -> list<player>", "All other players inside your render distance, nearest first.", a -> {
			List<Entity> l = new ArrayList<>();
			for (Entity e : query(null, true, true, false, a.has(0) ? a.num(0) : -1, "distance")) {
				if (e instanceof Player) {
					l.add(e);
				}
			}
			return refs(l, -1);
		}).client();
		R.def(c, "get_entity_by_id(id:number) -> entity", "Entity with this numeric id, or nil.", a -> {
			Entity e = G.level().getEntity(a.intv(0));
			return e == null ? null : new EntityRef(e);
		}).client();
		R.def(c, "get_entity_by_uuid(uuid:string) -> entity", "Entity with this UUID, or nil.", a -> {
			UUID u;
			try {
				u = UUID.fromString(a.str(0));
			} catch (IllegalArgumentException ex) {
				throw new KcdError("get_entity_by_uuid(): not a valid UUID", 0);
			}
			for (Entity e : G.level().entitiesForRendering()) {
				if (e.getUUID().equals(u)) {
					return new EntityRef(e);
				}
			}
			return null;
		}).client();
		R.def(c, "get_entities_in_box(min:vec3, max:vec3, type?:string) -> list<entity>", "Entities whose box touches the box between two corners.", a -> {
			Vec3 p = G.mv(a.vec3(0));
			Vec3 q = G.mv(a.vec3(1));
			AABB box = new AABB(p, q);
			List<Entity> l = new ArrayList<>();
			for (Entity e : G.level().entitiesForRendering()) {
				if (e != G.me() && e.getBoundingBox().intersects(box) && typeMatches(e, a.obj(2))) {
					l.add(e);
				}
			}
			return refs(l, -1);
		}).client();
		R.def(c, "is_entity(x:any) -> boolean", "True if x is an entity or player handle.", a -> a.obj(0) instanceof EntityRef);
	}

	private static void entityGetters() {
		ent("get_entity_position", "vec3", "Position of the entity's feet.", e -> G.v(e.position()));
		ent("get_entity_velocity", "vec3", "Movement per tick.", e -> G.v(e.getDeltaMovement()));
		ent("get_entity_rotation", "rotation", "Yaw and pitch (degrees).", EntityLib::rotationOf);
		ent("get_entity_type", "string", "Entity type id, e.g. minecraft:allay.", G::entityId);
		ent("get_entity_name", "string", "Display name.", e -> e.getName().getString());
		ent("get_entity_health", "number", "Health (nil for non-living entities).", e -> e instanceof LivingEntity l ? (Object) (double) l.getHealth() : null);
		ent("get_entity_max_health", "number", "Max health (nil for non-living entities).", e -> e instanceof LivingEntity l ? (Object) (double) l.getMaxHealth() : null);
		ent("get_entity_distance", "number", "Distance from your eyes to the entity's box.", e -> reachDistance(e));
		ent("get_entity_eye_pos", "vec3", "Position of the entity's eyes.", e -> G.v(e.getEyePosition()));
		ent("get_entity_eye_height", "number", "Eye height above the feet.", e -> (double) e.getEyeHeight());
		ent("get_entity_width", "number", "Box width.", e -> (double) e.getBbWidth());
		ent("get_entity_height", "number", "Box height.", e -> (double) e.getBbHeight());
		ent("get_entity_bounding_box", "map", "Map with min and max corners (vec3).", EntityLib::bbox);
		ent("get_entity_age", "number", "Ticks the entity has existed on the client.", e -> (double) e.tickCount);
		ent("get_entity_uuid", "string", "UUID.", e -> e.getUUID().toString());
		ent("get_entity_id", "number", "Numeric id.", e -> (double) e.getId());
		ent("get_entity_vehicle", "entity", "What the entity rides, or nil.", e -> e.getVehicle() == null ? null : new EntityRef(e.getVehicle()));
		ent("get_entity_passengers", "list", "Entities riding it.", e -> refs(e.getPassengers(), -1));
		ent("get_entity_target", "entity", "The mob's attack target. Usually nil: servers do not send it to clients.",
			e -> e instanceof Mob m && m.getTarget() != null ? new EntityRef(m.getTarget()) : null);
		ent("is_entity_alive", "boolean", "True if the entity is alive.", Entity::isAlive);
		ent("is_entity_on_ground", "boolean", "True if standing on the ground.", Entity::onGround);
		ent("is_entity_in_water", "boolean", "True if in water.", Entity::isInWater);
		ent("is_entity_invisible", "boolean", "True if invisible.", Entity::isInvisible);
		ent("is_entity_glowing", "boolean", "True if glowing.", Entity::isCurrentlyGlowing);
		ent("is_entity_sneaking", "boolean", "True if sneaking.", Entity::isShiftKeyDown);
		ent("is_entity_sprinting", "boolean", "True if sprinting.", Entity::isSprinting);
		ent("can_see_entity", "boolean", "True if you have a clear line of sight to the entity.", e -> G.me().hasLineOfSight(e));
	}

	private static void playerGetters() {
		pl("get_player_position", "vec3", "Position of the feet.", e -> G.v(e.position()));
		R.alias("get_player_pos", "get_player_position");
		pl("get_player_eye_pos", "vec3", "Position of the eyes.", e -> G.v(e.getEyePosition()));
		R.alias("get_eye_pos", "get_player_eye_pos");
		pl("get_player_eye_height", "number", "Eye height above the feet.", e -> (double) e.getEyeHeight());
		pl("get_player_velocity", "vec3", "Movement per tick.", e -> G.v(e.getDeltaMovement()));
		pl("get_player_rotation", "rotation", "Yaw and pitch (degrees).", EntityLib::rotationOf);
		pl("get_player_direction", "vec3", "Unit vector the player looks along.", e -> G.v(e.getLookAngle()));
		pl("get_player_health", "number", "Health.", e -> (double) living(e, "get_player_health").getHealth());
		pl("get_player_max_health", "number", "Max health.", e -> (double) living(e, "get_player_max_health").getMaxHealth());
		pl("get_player_absorption", "number", "Absorption hearts (golden).", e -> (double) living(e, "get_player_absorption").getAbsorptionAmount());
		pl("get_player_armor", "number", "Armor points.", e -> (double) living(e, "get_player_armor").getArmorValue());
		pl("get_player_name", "string", "Player name.", e -> e.getName().getString());
		pl("get_player_uuid", "string", "Player UUID.", e -> e.getUUID().toString());
		pl("get_player_bounding_box", "map", "Map with min and max corners (vec3).", EntityLib::bbox);
		pl("get_player_fall_distance", "number", "Blocks fallen so far.", e -> (double) e.fallDistance);
		pl("get_player_ping", "number", "Latency in ms (from the tab list).", e -> {
			PlayerInfo info = G.mc().getConnection() == null ? null : G.mc().getConnection().getPlayerInfo(e.getUUID());
			return info == null ? null : (Object) (double) info.getLatency();
		});
		pl("get_player_gamemode", "string", "survival, creative, adventure or spectator.", e -> {
			PlayerInfo info = G.mc().getConnection() == null ? null : G.mc().getConnection().getPlayerInfo(e.getUUID());
			return info == null ? null : info.getGameMode().getName();
		});
		pl("is_on_ground", "boolean", "True if standing on the ground.", Entity::onGround);
		pl("is_in_water", "boolean", "True if in water.", Entity::isInWater);
		pl("is_in_lava", "boolean", "True if in lava.", Entity::isInLava);
		pl("is_sprinting", "boolean", "True if sprinting.", Entity::isSprinting);
		pl("is_sneaking", "boolean", "True if sneaking.", Entity::isShiftKeyDown);
		pl("is_swimming", "boolean", "True if swimming.", Entity::isSwimming);
		pl("is_flying", "boolean", "True if flying with creative/abilities flight.", e -> e instanceof Player p && p.getAbilities().flying);
		pl("is_fall_flying", "boolean", "True if gliding with an elytra.", e -> living(e, "is_fall_flying").isFallFlying());
		pl("is_alive", "boolean", "True if alive.", Entity::isAlive);
		pl("get_player_effects", "list", "Active effects as maps with id, amplifier and duration (ticks).", e -> {
			List<Object> out = new ArrayList<>();
			for (MobEffectInstance m : living(e, "get_player_effects").getActiveEffects()) {
				Map<Object, Object> x = new LinkedHashMap<>();
				x.put("id", m.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
				x.put("amplifier", (double) m.getAmplifier());
				x.put("duration", (double) m.getDuration());
				out.add(x);
			}
			return out;
		});

		R.def("player", "get_player() -> player", "You (the local player).", a -> new EntityRef(G.me())).client();
		R.def("player", "get_player_by_name(name:string) -> player", "A player by name if inside your render distance, else nil.", a -> {
			for (Entity e : G.level().players()) {
				if (e.getName().getString().equalsIgnoreCase(a.str(0))) {
					return new EntityRef(e);
				}
			}
			return null;
		}).client();
		R.def("player", "get_online_players() -> list<string>", "Names of everyone on the tab list.", a -> {
			List<Object> out = new ArrayList<>();
			if (G.mc().getConnection() != null) {
				for (PlayerInfo i : G.mc().getConnection().getOnlinePlayers()) {
					out.add(i.getProfile().name());
				}
			}
			return out;
		}).client();
		R.def("player", "get_player_list() -> list<map>", "Everyone on the tab list as maps with name, ping (ms), gamemode and uuid.", a -> {
			List<Object> out = new ArrayList<>();
			if (G.mc().getConnection() != null) {
				for (PlayerInfo i : G.mc().getConnection().getOnlinePlayers()) {
					Map<Object, Object> m = new LinkedHashMap<>();
					m.put("name", i.getProfile().name());
					m.put("ping", (double) i.getLatency());
					m.put("gamemode", i.getGameMode().getName());
					m.put("uuid", i.getProfile().id().toString());
					out.add(m);
				}
			}
			return out;
		}).client().example("for p in get_player_list():\n    print(p[\"name\"], p[\"ping\"])");
		R.def("player", "get_ping(name?:string) -> number", "Latency in ms from the tab list: yours, or the named player's. nil if not on the list.", a -> {
			var conn = G.mc().getConnection();
			if (conn == null) {
				return null;
			}
			PlayerInfo i = a.has(0) ? conn.getPlayerInfoIgnoreCase(a.str(0)) : conn.getPlayerInfo(G.me().getUUID());
			return i == null ? null : (Object) (double) i.getLatency();
		}).client();
		R.def("player", "get_player_hunger() -> number", "Your food level (0-20).", a -> (double) G.me().getFoodData().getFoodLevel()).client();
		R.alias("get_player_food_level", "get_player_hunger");
		R.def("player", "get_player_saturation() -> number", "Your saturation.", a -> (double) G.me().getFoodData().getSaturationLevel()).client();
		R.def("player", "get_player_level() -> number", "Your experience level.", a -> (double) G.me().experienceLevel).client();
		R.def("player", "get_player_xp() -> number", "Your total experience points.", a -> (double) G.me().totalExperience).client();
		R.def("player", "get_player_selected_slot() -> number", "Selected hotbar slot, 1-9.", a -> (double) (G.me().getInventory().getSelectedSlot() + 1)).client();
		R.def("player", "get_player_status() -> map", "Summary of you: health, food, level, position, rotation, gamemode, flags.", a -> {
			var p = G.me();
			Map<Object, Object> m = new LinkedHashMap<>();
			m.put("name", p.getName().getString());
			m.put("health", (double) p.getHealth());
			m.put("max_health", (double) p.getMaxHealth());
			m.put("food", (double) p.getFoodData().getFoodLevel());
			m.put("saturation", (double) p.getFoodData().getSaturationLevel());
			m.put("level", (double) p.experienceLevel);
			m.put("position", G.v(p.position()));
			m.put("rotation", rotationOf(p));
			m.put("on_ground", p.onGround());
			m.put("sprinting", p.isSprinting());
			m.put("sneaking", p.isShiftKeyDown());
			m.put("in_water", p.isInWater());
			m.put("flying", p.getAbilities().flying);
			m.put("gamemode", G.mc().gameMode == null ? null : G.mc().gameMode.getPlayerMode().getName());
			return m;
		}).client();
		R.def("player", "is_in_world() -> boolean", "True if you are in a world.", a -> G.mc().player != null && G.mc().level != null).client();
	}

	// ---------------------------------------------------------------- members of entity / player
	private static void prop(String name, String ret, String doc, Function<Entity, Object> f) {
		R.prop("entity", name, ret, doc, (in, s, g) -> f.apply(((EntityRef) s).entity()));
	}

	private static void members() {
		R.typeParent("player", "entity");
		R.clientTypes.add("entity");
		R.clientTypes.add("player");
		R.typeDoc("entity", "A mob, item, projectile ... in the world. Use entity.health, entity.distance, entity:direction_to(point) ...");
		R.typeDoc("player", "A player (also an entity). entity.name, entity.ping, entity.gamemode ...");
		prop("name", "string", "Display name.", e -> e.getName().getString());
		prop("type", "string", "Entity type id, e.g. minecraft:allay.", G::entityId);
		prop("id", "number", "Numeric id.", e -> (double) e.getId());
		prop("uuid", "string", "UUID.", e -> e.getUUID().toString());
		prop("position", "vec3", "Position of the feet.", e -> G.v(e.position()));
		prop("pos", "vec3", "Same as position.", e -> G.v(e.position()));
		prop("eye_pos", "vec3", "Position of the eyes.", e -> G.v(e.getEyePosition()));
		prop("center", "vec3", "Centre of the bounding box (good aim point).", EntityLib::center);
		prop("eye_height", "number", "Eye height above feet.", e -> (double) e.getEyeHeight());
		prop("velocity", "vec3", "Movement per tick.", e -> G.v(e.getDeltaMovement()));
		prop("rotation", "rotation", "Yaw and pitch.", EntityLib::rotationOf);
		prop("yaw", "number", "Horizontal angle.", e -> Convert.wrapDegrees(e.getYRot()));
		prop("pitch", "number", "Vertical angle.", e -> (double) e.getXRot());
		prop("look_vector", "vec3", "Unit vector it looks along.", e -> G.v(e.getLookAngle()));
		prop("health", "number", "Health (nil if not living).", e -> e instanceof LivingEntity l ? (Object) (double) l.getHealth() : null);
		prop("max_health", "number", "Max health (nil if not living).", e -> e instanceof LivingEntity l ? (Object) (double) l.getMaxHealth() : null);
		prop("distance", "number", "Distance from your eyes to its box.", e -> reachDistance(e));
		prop("alive", "boolean", "True if alive.", Entity::isAlive);
		prop("on_ground", "boolean", "True if standing on the ground.", Entity::onGround);
		prop("in_water", "boolean", "True if in water.", Entity::isInWater);
		prop("in_lava", "boolean", "True if in lava.", Entity::isInLava);
		prop("sneaking", "boolean", "True if sneaking.", Entity::isShiftKeyDown);
		prop("sprinting", "boolean", "True if sprinting.", Entity::isSprinting);
		prop("swimming", "boolean", "True if swimming.", Entity::isSwimming);
		prop("invisible", "boolean", "True if invisible.", Entity::isInvisible);
		prop("glowing", "boolean", "True if glowing.", Entity::isCurrentlyGlowing);
		prop("on_fire", "boolean", "True if burning.", Entity::isOnFire);
		prop("is_player", "boolean", "True if it is a player.", e -> e instanceof Player);
		prop("is_baby", "boolean", "True if a baby mob.", e -> e instanceof LivingEntity l && l.isBaby());
		prop("width", "number", "Box width.", e -> (double) e.getBbWidth());
		prop("height", "number", "Box height.", e -> (double) e.getBbHeight());
		prop("box", "map", "Map with min and max corners.", EntityLib::bbox);
		prop("age", "number", "Ticks since it appeared.", e -> (double) e.tickCount);
		prop("vehicle", "entity", "What it rides, or nil.", e -> e.getVehicle() == null ? null : new EntityRef(e.getVehicle()));
		prop("passengers", "list", "Entities riding it.", e -> refs(e.getPassengers(), -1));
		prop("can_see", "boolean", "True if you have a clear line of sight to it.", e -> G.me().hasLineOfSight(e));
		prop("held_item", "item", "Item in its main hand.", EntityLib::held);
		prop("armor_value", "number", "Armor points.", e -> e instanceof LivingEntity l ? (Object) (double) l.getArmorValue() : null);
		R.prop("player", "ping", "number", "Latency in ms.", (in, s, g) -> {
			Entity e = ((EntityRef) s).entity();
			PlayerInfo i = G.mc().getConnection() == null ? null : G.mc().getConnection().getPlayerInfo(e.getUUID());
			return i == null ? null : (Object) (double) i.getLatency();
		});
		R.prop("player", "gamemode", "string", "Game mode name.", (in, s, g) -> {
			Entity e = ((EntityRef) s).entity();
			PlayerInfo i = G.mc().getConnection() == null ? null : G.mc().getConnection().getPlayerInfo(e.getUUID());
			return i == null ? null : i.getGameMode().getName();
		});
		R.method("entity", "distance_to(target:vec3) -> number", "Distance from its position to a point (or entity/block).", (in, s, g) -> {
			Val.Vec3 t = Convert.toVec3(g[0]);
			if (t == null) {
				throw new KcdError("distance_to(): argument must be a vec3, entity or block", 0);
			}
			return G.v(((EntityRef) s).entity().position()).sub(t).length();
		});
		R.method("entity", "direction_to(target:vec3) -> rotation", "Yaw and pitch from its eyes to a point.", (in, s, g) -> {
			Val.Vec3 t = Convert.toVec3(g[0]);
			if (t == null) {
				throw new KcdError("direction_to(): argument must be a vec3, entity or block", 0);
			}
			return Convert.directionToRotation(t.sub(G.v(((EntityRef) s).entity().getEyePosition())));
		});
		R.method("entity", "armor(slot:string) -> item", "Armor piece: head, chest, legs or feet.", (in, s, g) -> {
			Entity e = ((EntityRef) s).entity();
			if (!(e instanceof LivingEntity l)) {
				return null;
			}
			ItemStack st = l.getItemBySlot(InventoryLib.armorSlot(Val.str(g[0])));
			return new ItemRef(st.copy(), -1);
		});
		// make sure the unused import check keeps the registry referenced
		if (BuiltInRegistries.ENTITY_TYPE == null) {
			throw new IllegalStateException();
		}
	}

	/** Used by other libs to wrap lists of raw entities. */
	static List<Object> wrap(List<Entity> l) {
		return refs(l, -1);
	}

	static Args unused(Args a) {
		return a;
	}
}
