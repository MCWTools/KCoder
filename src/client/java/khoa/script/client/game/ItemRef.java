package khoa.script.client.game;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import khoa.script.lang.Val;

/** A copy of an item stack (taken when the script asked) plus the slot it came from (-1 if unknown). */
public record ItemRef(ItemStack stack, int slot) implements Val.KObject {
	@Override
	public String typeName() {
		return "item";
	}

	public String id() {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	@Override
	public String toString() {
		return stack.isEmpty() ? "<item empty>" : "<item " + id() + " x" + stack.getCount() + ">";
	}
}
