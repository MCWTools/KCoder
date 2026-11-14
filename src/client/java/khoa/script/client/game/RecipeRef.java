package khoa.script.client.game;

import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import khoa.script.lang.Val;

/** A recipe from your recipe book and its position in the list get_recipes() returned. */
public record RecipeRef(RecipeDisplayEntry entry, int index) implements Val.KObject {
	@Override
	public String typeName() {
		return "recipe";
	}

	@Override
	public String toString() {
		return "<recipe #" + index + ">";
	}
}
