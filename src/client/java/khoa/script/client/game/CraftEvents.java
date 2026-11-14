package khoa.script.client.game;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

import khoa.script.client.Kcd;

/** Fires on_craft and on_item_pickup. Called from the mixins, always on the game thread. */
public final class CraftEvents {
	private CraftEvents() {
	}

	private static String idOf(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	/** A click on a menu slot is about to be sent. If it takes the result of a crafting grid, that is a craft. */
	public static void onContainerInput(int containerId, int slotNum, int button, ContainerInput input, Player player) {
		Minecraft mc = Minecraft.getInstance();
		if (player != mc.player || !Kcd.ENGINE.events.hasHandlers("on_craft")) {
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.containerId != containerId || slotNum < 0 || slotNum >= menu.slots.size()) {
			return;
		}
		var slot = menu.slots.get(slotNum);
		if (!(slot instanceof ResultSlot) || !slot.hasItem()) {
			return;
		}
		ItemStack result = slot.getItem();
		ItemStack carried = menu.getCarried();
		String method;
		if (input == ContainerInput.QUICK_MOVE) {
			method = "quick_move";
		} else if (input == ContainerInput.THROW && carried.isEmpty()) {
			method = "throw";
		} else if (input == ContainerInput.PICKUP && (carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, result) && carried.getCount() + result.getCount() <= carried.getMaxStackSize())) {
			method = "pickup";
		} else {
			return;
		}
		Map<String, Object> d = new HashMap<>();
		d.put("item", new ItemRef(result.copy(), slotNum));
		d.put("id", idOf(result));
		d.put("count", (double) result.getCount());
		d.put("method", method);
		d.put("slot", new SlotRef(slotNum));
		Kcd.ENGINE.events.fire("on_craft", d);
	}

	/** The server says an item entity was picked up. Only the pass that runs on the game thread, and only when it was you. */
	public static void onTakeItem(ClientboundTakeItemEntityPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || packet.getPlayerId() != mc.player.getId() || !Kcd.ENGINE.events.hasHandlers("on_item_pickup")) {
			return;
		}
		if (!(mc.level.getEntity(packet.getItemId()) instanceof ItemEntity ie)) {
			return;
		}
		ItemStack st = ie.getItem();
		Map<String, Object> d = new HashMap<>();
		d.put("item", new ItemRef(st.copy(), -1));
		d.put("id", idOf(st));
		d.put("count", (double) packet.getAmount());
		d.put("entity", new EntityRef(ie));
		Kcd.ENGINE.events.fire("on_item_pickup", d);
	}
}
