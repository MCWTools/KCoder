package khoa.script.client.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import khoa.script.client.Kcd;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/**
 * Crafting: recipe lookup (from your recipe book, the same list the game's recipe book screen shows), ingredient checks, auto-crafting with the
 * recipe-book "place recipe" request, menu slots you can click / throw, and the on_craft / on_item_pickup events.
 */
public final class CraftLib {
	private static final Registry R = Registry.get();

	private CraftLib() {
	}

	// ------------------------------------------------------------ helpers
	private static void needActions(String fn) {
		if (!Kcd.actionsAllowed()) {
			throw new KcdError(fn + "() is blocked on multiplayer servers", 0).withHint("run /kcoder multiplayer true to allow scripts to act on servers");
		}
	}

	private static AbstractContainerMenu menu() {
		Player p = G.me();
		return p.containerMenu;
	}

	private static Slot slotOf(SlotRef r, String fn) {
		AbstractContainerMenu m = menu();
		if (r.index() < 0 || r.index() >= m.slots.size()) {
			throw new KcdError(fn + "(): the menu has no slot " + r.index() + " any more (it has " + m.slots.size() + ")", 0);
		}
		return m.slots.get(r.index());
	}

	private static void click(SlotRef r, int button, ContainerInput input, String fn) {
		needActions(fn);
		slotOf(r, fn);
		var mc = G.mc();
		if (mc.gameMode == null) {
			throw new KcdError(fn + "(): not in a world", 0);
		}
		mc.gameMode.handleContainerInput(menu().containerId, r.index(), button, input, G.me());
	}

	private static List<Object> idsOf(Ingredient ing) {
		List<Object> out = new ArrayList<>();
		ing.items().forEach((Holder<Item> h) -> out.add(h.unwrapKey().map(k -> k.identifier().toString()).orElse("?")));
		return out;
	}

	private static List<Ingredient> ingredients(RecipeDisplayEntry e) {
		return e.craftingRequirements().orElse(List.of());
	}

	private static ItemStack resultOf(RecipeDisplayEntry e) {
		var level = G.mc().level;
		if (level == null) {
			return ItemStack.EMPTY;
		}
		for (ItemStack s : e.resultItems(SlotDisplayContext.fromLevel(level))) {
			if (!s.isEmpty()) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	private static StackedItemContents available() {
		Player p = G.me();
		StackedItemContents c = new StackedItemContents();
		p.getInventory().fillStackedContents(c);
		if (p.containerMenu instanceof RecipeBookMenu m) {
			m.fillCraftSlotsStackedContents(c);
		}
		return c;
	}

	private static RecipeRef recipe(Object o, String fn) {
		if (o instanceof RecipeRef r) {
			return r;
		}
		throw new KcdError(fn + "(): needs a recipe from get_recipes()", 0);
	}

	/** Which ingredients you cannot cover with what is in your inventory (greedy per-ingredient count; the exact yes/no is can_craft). */
	private static List<Object> missing(RecipeDisplayEntry e) {
		Inventory inv = G.me().getInventory();
		int[] left = new int[inv.getContainerSize()];
		for (int i = 0; i < left.length; i++) {
			left[i] = inv.getItem(i).getCount();
		}
		List<Object> out = new ArrayList<>();
		for (Ingredient ing : ingredients(e)) {
			boolean got = false;
			for (int i = 0; i < left.length && !got; i++) {
				if (left[i] > 0 && ing.test(inv.getItem(i))) {
					left[i]--;
					got = true;
				}
			}
			if (!got) {
				List<Object> ids = idsOf(ing);
				out.add(ids.stream().map(Val::str).collect(Collectors.joining("|")));
			}
		}
		return out;
	}

	// ------------------------------------------------------------ api
	public static void register() {
		R.clientTypes.add("recipe");
		R.clientTypes.add("slot");
		String c = "crafting";

		R.def(c, "get_recipes(result?:string) -> list<recipe>", "The recipes in your recipe book, as a list (recipe.index is its position in that list). With result, only recipes that make that item: get_recipes(\"minecraft:stick\"). The recipe book only holds recipes you have unlocked.", a -> {
			var p = G.mc().player;
			if (p == null) {
				throw new KcdError("get_recipes(): not in a world", 0);
			}
			String want = a.has(0) ? G.norm(a.str(0)) : null;
			List<Object> out = new ArrayList<>();
			Set<Object> seen = new HashSet<>();
			for (RecipeCollection col : p.getRecipeBook().getCollections()) {
				for (RecipeDisplayEntry e : col.getRecipes()) {
					if (!seen.add(e.id())) {
						continue;
					}
					if (want != null) {
						ItemStack r = resultOf(e);
						if (r.isEmpty() || !BuiltInRegistries.ITEM.getKey(r.getItem()).toString().equals(want)) {
							continue;
						}
					}
					out.add(new RecipeRef(e, out.size()));
				}
			}
			return out;
		}).client().example("for r in get_recipes(\"minecraft:stick\"):\n    print(r.index, r.ingredients, r.craftable)");
		R.def(c, "can_craft(recipe:recipe) -> boolean", "True if your inventory (plus what is already in the open crafting grid) has the ingredients for one craft.", a -> available_ok(recipe(a.obj(0), "can_craft"))).client();
		R.def(c, "get_missing_ingredients(recipe:recipe) -> list<string>", "The ingredients you do not have, one entry per missing item (alternatives joined with |). Empty list = you have everything.", a -> missing(recipe(a.obj(0), "get_missing_ingredients").entry())).client();
		R.def(c, "craft(recipe:recipe, all?:boolean=false) -> boolean", "Fills the open crafting grid with the recipe (the recipe book's click; all = as many crafts as the ingredients allow). Needs a crafting table or your inventory open. The result then sits in get_crafting_out_slot(). False if you cannot craft it or no crafting menu is open.", a -> {
			needActions("craft");
			RecipeRef r = recipe(a.obj(0), "craft");
			AbstractContainerMenu m = menu();
			if (!(m instanceof RecipeBookMenu) || G.mc().gameMode == null) {
				return false;
			}
			if (!r.entry().canCraft(available())) {
				return false;
			}
			G.mc().gameMode.handlePlaceRecipe(m.containerId, r.entry().id(), a.bool(1, false));
			return true;
		}).client().perm("action");

		R.def(c, "get_crafting_out_slot() -> slot", "The result slot of the open crafting menu (crafting table or your inventory's 2x2 grid), or nil if the open menu has none. Use it like: out = get_crafting_out_slot()  then  out.throw_all().", a -> {
			for (Slot s : menu().slots) {
				if (s instanceof ResultSlot) {
					return new SlotRef(menu().slots.indexOf(s));
				}
			}
			return null;
		}).client();
		R.def(c, "get_crafting_slots() -> list<slot>", "The ingredient slots of the open crafting grid (empty list if there is none).", a -> {
			List<Object> out = new ArrayList<>();
			AbstractContainerMenu m = menu();
			for (int i = 0; i < m.slots.size(); i++) {
				if (m.slots.get(i).container instanceof CraftingContainer) {
					out.add(new SlotRef(i));
				}
			}
			return out;
		}).client();
		R.def(c, "get_slot(index:number) -> slot", "Slot number index of the open menu (see get_container_items() for the numbering).", a -> {
			int i = a.intv(0);
			if (i < 0 || i >= menu().slots.size()) {
				throw new KcdError("get_slot(): the open menu has slots 0-" + (menu().slots.size() - 1), 0);
			}
			return new SlotRef(i);
		}).client();

		R.typeDoc("recipe", "A recipe from your recipe book. recipe.index, .result, .result_id, .count, .ingredients, .craftable. Use can_craft(recipe), craft(recipe), get_missing_ingredients(recipe).");
		R.prop("recipe", "index", "number", "Position in the list get_recipes() returned.", (in, s, g) -> (double) ((RecipeRef) s).index());
		R.prop("recipe", "result", "item", "What one craft makes (an item with its count).", (in, s, g) -> new ItemRef(resultOf(((RecipeRef) s).entry()).copy(), -1));
		R.prop("recipe", "result_id", "string", "Item id of the result.", (in, s, g) -> {
			ItemStack r = resultOf(((RecipeRef) s).entry());
			return r.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(r.getItem()).toString();
		});
		R.prop("recipe", "count", "number", "How many items one craft makes.", (in, s, g) -> (double) resultOf(((RecipeRef) s).entry()).getCount());
		R.prop("recipe", "ingredients", "list<list<string>>", "One entry per ingredient, each the list of item ids that fit it (a list of 1 when only one item fits).", (in, s, g) -> {
			List<Object> out = new ArrayList<>();
			for (Ingredient ing : ingredients(((RecipeRef) s).entry())) {
				out.add(idsOf(ing));
			}
			return out;
		});
		R.prop("recipe", "craftable", "boolean", "True if you have the ingredients right now.", (in, s, g) -> available_ok((RecipeRef) s));
		R.prop("recipe", "category", "string", "Recipe book category, e.g. crafting_misc.", (in, s, g) -> String.valueOf(((RecipeRef) s).entry().category()));

		R.typeDoc("slot", "A slot of the open menu. slot.item, .index, .empty. Actions: take(), shift_click(), throw_one(), throw_all(), swap(hotbar), click(button).");
		R.prop("slot", "index", "number", "Slot number in the open menu.", (in, s, g) -> (double) ((SlotRef) s).index());
		R.prop("slot", "item", "item", "What is in the slot right now (a copy).", (in, s, g) -> new ItemRef(slotOf((SlotRef) s, "slot.item").getItem().copy(), ((SlotRef) s).index()));
		R.prop("slot", "empty", "boolean", "True if nothing is in the slot.", (in, s, g) -> !slotOf((SlotRef) s, "slot.empty").hasItem());
		R.prop("slot", "is_result", "boolean", "True for the result slot of a crafting grid.", (in, s, g) -> slotOf((SlotRef) s, "slot.is_result") instanceof ResultSlot);
		R.method("slot", "take()", "Left-click: picks the stack up onto your cursor (for the result slot this crafts once).", (in, s, g) -> {
			click((SlotRef) s, 0, ContainerInput.PICKUP, "take");
			return null;
		});
		R.method("slot", "click(button?:number=0)", "Clicks the slot with a mouse button: 0 = left, 1 = right.", (in, s, g) -> {
			click((SlotRef) s, g.length > 0 && g[0] instanceof Double d ? d.intValue() : 0, ContainerInput.PICKUP, "click");
			return null;
		});
		R.method("slot", "shift_click()", "Shift-click: moves the stack to the other part of the menu (on the result slot it crafts and puts the result in your inventory).", (in, s, g) -> {
			click((SlotRef) s, 0, ContainerInput.QUICK_MOVE, "shift_click");
			return null;
		});
		R.method("slot", "throw_one()", "Drops one item of the slot (Q).", (in, s, g) -> {
			click((SlotRef) s, 0, ContainerInput.THROW, "throw_one");
			return null;
		});
		R.method("slot", "throw_all()", "Drops the whole stack of the slot (Ctrl+Q); on the result slot it crafts once and drops everything that craft made.", (in, s, g) -> {
			click((SlotRef) s, 1, ContainerInput.THROW, "throw_all");
			return null;
		});
		R.method("slot", "swap(hotbar:number)", "Swaps the slot with hotbar slot 1-9 (the number keys).", (in, s, g) -> {
			int h = g[0] instanceof Double d ? d.intValue() : 0;
			if (h < 1 || h > 9) {
				throw new KcdError("swap(): hotbar slot must be 1-9", 0);
			}
			click((SlotRef) s, h - 1, ContainerInput.SWAP, "swap");
			return null;
		});

		R.event("on_craft", "You took the result of a crafting grid (your click or a script's). on_craft(\"minecraft:stick\"): only for that item. Gives item, id, count, method (pickup, quick_move or throw) and slot.", List.of("item"),
			java.util.Map.of("item", "item", "id", "string", "count", "number", "method", "string", "slot", "slot"),
			(f, d) -> f.isEmpty() || G.norm(String.valueOf(f.get(0))).equals(String.valueOf(d.get("id")))).skipIfBusy = false;
		R.event("on_item_pickup", "You picked up an item lying on the ground. on_item_pickup(\"minecraft:diamond\"): only for that item. Gives item, id, count (how many you took) and entity.", List.of("item"),
			java.util.Map.of("item", "item", "id", "string", "count", "number", "entity", "entity"),
			(f, d) -> f.isEmpty() || G.norm(String.valueOf(f.get(0))).equals(String.valueOf(d.get("id")))).skipIfBusy = false;
	}

	private static boolean available_ok(RecipeRef r) {
		return G.mc().player != null && r.entry().canCraft(available());
	}
}
