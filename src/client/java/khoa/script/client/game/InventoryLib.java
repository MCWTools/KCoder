package khoa.script.client.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Inventory and item API. Slots follow Minecraft: 0-8 hotbar, 9-35 inventory, 36-39 armor (feet to head), 40 offhand. */
public final class InventoryLib {
	private static final Registry R = Registry.get();

	private InventoryLib() {
	}

	static EquipmentSlot armorSlot(String name) {
		return switch (name.toLowerCase()) {
			case "head", "helmet" -> EquipmentSlot.HEAD;
			case "chest", "chestplate" -> EquipmentSlot.CHEST;
			case "legs", "leggings" -> EquipmentSlot.LEGS;
			case "feet", "boots" -> EquipmentSlot.FEET;
			default -> throw new KcdError("armor slot must be head, chest, legs or feet", 0);
		};
	}

	private static ItemRef at(Inventory inv, int slot) {
		if (slot < 0 || slot >= inv.getContainerSize()) {
			throw new KcdError("Inventory slot " + slot + " does not exist (0-" + (inv.getContainerSize() - 1) + ")", 0);
		}
		return new ItemRef(inv.getItem(slot).copy(), slot);
	}

	private static ItemStack stack(Args a, int i) {
		if (a.obj(i) instanceof ItemRef r) {
			return r.stack();
		}
		throw a.bad(i, "an item");
	}

	private static boolean matches(ItemStack s, String id) {
		return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(G.norm(id));
	}

	public static void register() {
		R.clientTypes.add("item");
		R.clientTypes.add("block");
		String c = "inventory";
		R.def(c, "get_inventory() -> list<item>", "Every non-empty slot of your inventory as items (hotbar, inventory, armor, offhand).", a -> {
			Inventory inv = G.me().getInventory();
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (!inv.getItem(i).isEmpty()) {
					out.add(at(inv, i));
				}
			}
			return out;
		}).client();
		R.alias("get_player_inventory", "get_inventory");
		R.def(c, "get_inventory_size() -> number", "Number of slots in your inventory (41).", a -> (double) G.me().getInventory().getContainerSize()).client();
		R.def(c, "get_inventory_item(slot:number) -> item", "The item in a slot (0-8 hotbar, 9-35 inventory, 36-39 armor, 40 offhand). Empty slots give an item with .empty == true.",
			a -> at(G.me().getInventory(), a.intv(0))).client();
		R.def(c, "get_hotbar_item(slot:number) -> item", "The item in hotbar slot 1-9.", a -> {
			int s = a.intv(0);
			if (s < 1 || s > 9) {
				throw new KcdError("get_hotbar_item(): slot must be 1-9", 0);
			}
			return at(G.me().getInventory(), s - 1);
		}).client();
		R.def(c, "get_held_item() -> item", "The item in your main hand.", a -> new ItemRef(G.me().getMainHandItem().copy(), G.me().getInventory().getSelectedSlot())).client();
		R.def(c, "get_offhand_item() -> item", "The item in your offhand.", a -> new ItemRef(G.me().getOffhandItem().copy(), 40)).client();
		R.def(c, "get_armor(slot:string) -> item", "Armor piece you wear: head, chest, legs or feet.", a -> new ItemRef(G.me().getItemBySlot(armorSlot(a.str(0))).copy(), -1)).client();
		R.def(c, "find_item(id:string) -> number", "First inventory slot (0-40) that holds this item id, or -1.", a -> {
			Inventory inv = G.me().getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (matches(inv.getItem(i), a.str(0))) {
					return (double) i;
				}
			}
			return -1.0;
		}).client().example("slot = find_item(minecraft:golden_apple)");
		R.def(c, "find_items(id:string) -> list<item>", "All inventory slots that hold this item id.", a -> {
			Inventory inv = G.me().getInventory();
			List<Object> out = new ArrayList<>();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (matches(inv.getItem(i), a.str(0))) {
					out.add((double) i);
				}
			}
			return out;
		}).client();
		R.def(c, "count_item(id:string) -> number", "How many of this item you carry.", a -> {
			Inventory inv = G.me().getInventory();
			double n = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (matches(inv.getItem(i), a.str(0))) {
					n += inv.getItem(i).getCount();
				}
			}
			return n;
		}).client();
		R.def(c, "has_item(id:string) -> boolean", "True if you carry this item.", a -> {
			Inventory inv = G.me().getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (matches(inv.getItem(i), a.str(0))) {
					return true;
				}
			}
			return false;
		}).client();
		R.def(c, "is_inventory_full() -> boolean", "True if there is no free slot.", a -> G.me().getInventory().getFreeSlot() == -1).client();
		R.def(c, "find_hotbar_item(id:string) -> number", "Hotbar slot 1-9 that holds this item id, or 0.", a -> {
			Inventory inv = G.me().getInventory();
			for (int i = 0; i < 9; i++) {
				if (matches(inv.getItem(i), a.str(0))) {
					return (double) (i + 1);
				}
			}
			return 0.0;
		}).client();

		R.def("item", "get_item_id(item:item) -> string", "Item id, e.g. minecraft:diamond_sword.", a -> new ItemRef(stack(a, 0), -1).id()).client();
		R.def("item", "get_item_name(item:item) -> string", "Display name.", a -> stack(a, 0).getHoverName().getString()).client();
		R.def("item", "get_item_count(item:item) -> number", "Stack size.", a -> (double) stack(a, 0).getCount()).client();
		R.def("item", "get_item_durability(item:item) -> number", "Durability left (nil if the item cannot break).", a -> {
			ItemStack s = stack(a, 0);
			return s.isDamageableItem() ? (Object) (double) (s.getMaxDamage() - s.getDamageValue()) : null;
		}).client();
		R.def("item", "get_item_max_durability(item:item) -> number", "Max durability (nil if the item cannot break).", a -> {
			ItemStack s = stack(a, 0);
			return s.isDamageableItem() ? (Object) (double) s.getMaxDamage() : null;
		}).client();
		R.def("item", "get_item_enchantments(item:item) -> map", "Map of enchantment id to level.", a -> enchants(stack(a, 0))).client();
		R.def("item", "get_item_lore(item:item) -> list<string>", "The lore lines of an item (the extra tooltip text servers add, such as Buy Price / Sell Price in shops). Empty list if none.", a -> lore(stack(a, 0))).client();
		R.def("item", "is_item_enchanted(item:item) -> boolean", "True if the item has enchantments.", a -> stack(a, 0).isEnchanted()).client();
		R.def("item", "is_item_empty(item:item) -> boolean", "True for an empty slot.", a -> stack(a, 0).isEmpty()).client();

		R.typeDoc("item", "An item stack (a copy). item.id, item.count, item.durability, item.enchanted, item.empty.");
		R.prop("item", "id", "string", "Item id.", (in, s, g) -> ((ItemRef) s).stack().isEmpty() ? "minecraft:air" : ((ItemRef) s).id());
		R.prop("item", "name", "string", "Display name.", (in, s, g) -> ((ItemRef) s).stack().getHoverName().getString());
		R.prop("item", "count", "number", "Stack size.", (in, s, g) -> (double) ((ItemRef) s).stack().getCount());
		R.prop("item", "empty", "boolean", "True for an empty slot.", (in, s, g) -> ((ItemRef) s).stack().isEmpty());
		R.prop("item", "slot", "number", "Inventory slot it came from (-1 if unknown).", (in, s, g) -> (double) ((ItemRef) s).slot());
		R.prop("item", "durability", "number", "Durability left (nil if it cannot break).", (in, s, g) -> {
			ItemStack st = ((ItemRef) s).stack();
			return st.isDamageableItem() ? (Object) (double) (st.getMaxDamage() - st.getDamageValue()) : null;
		});
		R.prop("item", "max_durability", "number", "Max durability (nil if it cannot break).", (in, s, g) -> {
			ItemStack st = ((ItemRef) s).stack();
			return st.isDamageableItem() ? (Object) (double) st.getMaxDamage() : null;
		});
		R.prop("item", "enchanted", "boolean", "True if enchanted.", (in, s, g) -> ((ItemRef) s).stack().isEnchanted());
		R.prop("item", "enchantments", "map", "Enchantment id to level.", (in, s, g) -> enchants(((ItemRef) s).stack()));
		R.prop("item", "lore", "list<string>", "Lore lines (server tooltip text such as shop prices). Empty list if none.", (in, s, g) -> lore(((ItemRef) s).stack()));
		R.prop("item", "max_stack", "number", "Max stack size.", (in, s, g) -> (double) ((ItemRef) s).stack().getMaxStackSize());
	}

	private static List<Object> lore(ItemStack s) {
		List<Object> out = new ArrayList<>();
		ItemLore l = s.isEmpty() ? null : s.get(DataComponents.LORE);
		if (l != null) {
			for (var line : l.lines()) {
				out.add(line.getString());
			}
		}
		return out;
	}

	private static Map<Object, Object> enchants(ItemStack s) {
		Map<Object, Object> out = new LinkedHashMap<>();
		ItemEnchantments e = s.getEnchantments();
		for (Holder<Enchantment> h : e.keySet()) {
			out.put(h.unwrapKey().map(k -> k.identifier().toString()).orElse("?"), (double) e.getLevel(h));
		}
		return out;
	}

	/** Kept so the Val import documents the value model used here. */
	static Class<?> value() {
		return Val.class;
	}
}
