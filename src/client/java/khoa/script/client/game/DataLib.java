package khoa.script.client.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.mojang.serialization.DataResult;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.core.component.DataComponents;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;

/** Attributes and NBT / data of entities and items. */
public final class DataLib {
	private static final Registry R = Registry.get();

	private DataLib() {
	}

	// ------------------------------------------------------------ attributes
	private static Holder<Attribute> attribute(String id) {
		String s = id.trim().toLowerCase();
		if (s.startsWith("generic.") || s.startsWith("player.") || s.startsWith("minecraft:generic.") || s.startsWith("minecraft:player.")) {
			s = s.substring(s.indexOf('.') + 1); // old names: generic.max_health
		}
		Identifier key = Identifier.tryParse(G.norm(s));
		if (key != null) {
			var h = BuiltInRegistries.ATTRIBUTE.get(key);
			if (h.isPresent()) {
				return h.get();
			}
		}
		throw new KcdError("Unknown attribute '" + id + "'", 0).withHint("list_attributes() lists them all, e.g. minecraft:max_health, minecraft:attack_damage, minecraft:movement_speed");
	}

	private static AttributeInstance instance(Entity e, Holder<Attribute> attr) {
		return e instanceof LivingEntity l ? l.getAttribute(attr) : null;
	}

	private static String attrName(Holder<Attribute> h) {
		return h.unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
	}

	// ------------------------------------------------------------ NBT -> script values
	static Object convert(Tag t) {
		if (t instanceof CompoundTag c) {
			Map<Object, Object> m = new LinkedHashMap<>();
			for (String k : c.keySet()) {
				m.put(k, convert(c.get(k)));
			}
			return m;
		}
		if (t instanceof CollectionTag l) {
			List<Object> out = new ArrayList<>();
			for (Tag e : l) {
				out.add(convert(e));
			}
			return out;
		}
		if (t instanceof NumericTag n) {
			return n.doubleValue();
		}
		if (t instanceof StringTag s) {
			return s.value();
		}
		return t == null ? null : t.toString();
	}

	/** Follows "a.b.0.c" through maps and lists; nil if anything on the way is missing. */
	private static Object path(Object root, String path) {
		Object cur = root;
		for (String part : path.split("\\.")) {
			if (part.isEmpty()) {
				continue;
			}
			if (cur instanceof Map<?, ?> m) {
				cur = m.get(part);
			} else if (cur instanceof List<?> l) {
				try {
					int i = Integer.parseInt(part);
					cur = i >= 0 && i < l.size() ? l.get(i) : null;
				} catch (NumberFormatException e) {
					return null;
				}
			} else {
				return null;
			}
			if (cur == null) {
				return null;
			}
		}
		return cur;
	}

	private static Object select(Object data, Args a, int pathIndex) {
		return a.has(pathIndex) ? path(data, a.str(pathIndex)) : data;
	}

	/** NBT of an entity. In singleplayer (or on your own LAN world) this is the real server data, e.g. Allay "DuplicationCooldown"; on a server it is only what the client knows. */
	private static CompoundTag entityTag(Entity clientEntity) {
		var mc = G.mc();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null) {
			try {
				CompoundTag tag = server.submit(() -> {
					var level = server.getLevel(clientEntity.level().dimension());
					Entity real = level == null ? null : level.getEntity(clientEntity.getId());
					if (real == null) {
						return null;
					}
					TagValueOutput out = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, real.registryAccess());
					real.saveWithoutId(out);
					return out.buildResult();
				}).get(1500, TimeUnit.MILLISECONDS);
				if (tag != null) {
					return tag;
				}
			} catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
				throw new KcdError("Could not read the entity data from the integrated server: " + e.getMessage(), 0);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new KcdError("Interrupted while reading entity data", 0);
			}
		}
		TagValueOutput out = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, clientEntity.registryAccess());
		clientEntity.saveWithoutId(out);
		return out.buildResult();
	}

	private static Object itemTag(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		RegistryOps<Tag> ops = G.mc().level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		DataResult<Tag> r = ItemStack.CODEC.encodeStart(ops, stack);
		return convert(r.result().orElse(null));
	}

	public static void register() {
		String c = "data";
		R.def(c, "list_attributes() -> list<string>", "Every attribute id: minecraft:max_health, minecraft:attack_damage, minecraft:movement_speed ...", a -> {
			List<Object> out = new ArrayList<>();
			for (Identifier k : BuiltInRegistries.ATTRIBUTE.keySet()) {
				out.add(k.toString());
			}
			return out;
		});
		R.def(c, "get_entity_attribute(entity:entity, attribute:attribute) -> number", "Current value of an attribute (with all modifiers), e.g. get_entity_attribute(e, minecraft:max_health). Gives nil if the entity is nil, is not a living entity, or does not have that attribute.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			AttributeInstance i = instance(G.entity(a, 0), attribute(a.str(1)));
			return i == null ? null : (Object) i.getValue();
		}).client().example("speed = get_entity_attribute(vex, minecraft:movement_speed)");
		R.def(c, "get_entity_attribute_base(entity:entity, attribute:attribute) -> number", "Base value of an attribute, without modifiers.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			AttributeInstance i = instance(G.entity(a, 0), attribute(a.str(1)));
			return i == null ? null : (Object) i.getBaseValue();
		}).client();
		R.def(c, "get_entity_attributes(entity:entity) -> map", "Every attribute the entity has: a map from attribute id to its current value.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			Entity e = G.entity(a, 0);
			Map<Object, Object> m = new LinkedHashMap<>();
			if (e instanceof LivingEntity l) {
				for (Identifier k : BuiltInRegistries.ATTRIBUTE.keySet()) {
					var h = BuiltInRegistries.ATTRIBUTE.get(k);
					if (h.isPresent()) {
						AttributeInstance i = l.getAttribute(h.get());
						if (i != null) {
							m.put(k.toString(), i.getValue());
						}
					}
				}
			}
			return m;
		}).client();
		R.def(c, "get_entity_nbt(entity:entity, path?:string) -> any", "The entity's NBT data as a map (or one value, with a dotted path like \"Brain.memories\" or \"DuplicationCooldown\"). In singleplayer this is the real server data (an Allay's DuplicationCooldown, ages, owners ...). On a multiplayer server the client only has what the server sent, so many values are missing.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			return select(convert(entityTag(G.entity(a, 0))), a, 1);
		}).client().example("cd = get_entity_nbt(allay, \"DuplicationCooldown\")");
		R.def(c, "get_item_nbt(item:item, path?:string) -> any", "The item's data as a map: id, count and components (enchantments, custom_name, damage, attribute_modifiers ...). Use a dotted path to read one value, e.g. \"components.minecraft:damage\".", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			if (!(a.obj(0) instanceof ItemRef r)) {
				throw a.bad(0, "an item");
			}
			return select(itemTag(r.stack()), a, 1);
		}).client();
		R.def(c, "get_block_entity_nbt(pos:vec3, path?:string) -> any", "NBT of a chest, furnace, sign, spawner ... as a map (nil if the block has none). Full data in singleplayer; on a server only what the client knows.", a -> {
			var pos = G.bp(a.vec3(0));
			MinecraftServer server = G.mc().getSingleplayerServer();
			CompoundTag tag = null;
			if (server != null) {
				try {
					tag = server.submit(() -> {
						var lvl = server.getLevel(G.level().dimension());
						var be = lvl == null ? null : lvl.getBlockEntity(pos);
						return be == null ? null : be.saveWithFullMetadata(lvl.registryAccess());
					}).get(1500, TimeUnit.MILLISECONDS);
				} catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
					throw new KcdError("Could not read the block data from the integrated server: " + e.getMessage(), 0);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new KcdError("Interrupted while reading block data", 0);
				}
			}
			if (tag == null) {
				var be = G.level().getBlockEntity(pos);
				if (be != null) {
					tag = be.saveWithFullMetadata(G.level().registryAccess());
				}
			}
			return tag == null ? null : select(convert(tag), a, 1);
		}).client();
		R.def(c, "get_item_attributes(item:item) -> list", "The attribute modifiers an item gives: a list of maps with attribute, amount, operation (add_value, add_multiplied_base, add_multiplied_total) and slot.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			if (!(a.obj(0) instanceof ItemRef r)) {
				throw a.bad(0, "an item");
			}
			List<Object> out = new ArrayList<>();
			ItemAttributeModifiers mods = r.stack().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
			for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
				Map<Object, Object> m = new LinkedHashMap<>();
				m.put("attribute", attrName(e.attribute()));
				m.put("amount", e.modifier().amount());
				m.put("operation", e.modifier().operation().getSerializedName());
				m.put("slot", e.slot().getSerializedName());
				out.add(m);
			}
			return out;
		}).client();
		R.def(c, "get_item_attribute(item:item, attribute:attribute) -> number", "Sum of what the item adds to one attribute (0 if none), e.g. get_item_attribute(get_held_item(), minecraft:attack_damage).", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			if (!(a.obj(0) instanceof ItemRef r)) {
				throw a.bad(0, "an item");
			}
			Holder<Attribute> want = attribute(a.str(1));
			double sum = 0;
			ItemAttributeModifiers mods = r.stack().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
			for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
				if (e.attribute().equals(want)) {
					sum += e.modifier().amount();
				}
			}
			return sum;
		}).client();
	}
}
