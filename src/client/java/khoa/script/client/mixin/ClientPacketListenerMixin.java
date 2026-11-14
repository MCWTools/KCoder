package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

import khoa.script.client.game.CraftEvents;

/** Feeds on_item_pickup. The handler runs twice (network thread, then game thread); only the game thread pass counts, before the item entity is removed. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleTakeItemEntity(Lnet/minecraft/network/protocol/game/ClientboundTakeItemEntityPacket;)V", at = @At("HEAD"))
	private void khoa$takeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) {
			CraftEvents.onTakeItem(packet);
		}
	}
}
