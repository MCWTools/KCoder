package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

import khoa.script.client.game.CraftEvents;

/** Feeds on_craft (every menu click goes through here). Hits on a fake hitbox are handled by KhoaAPILibs (KhoaHitbox). */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "handleContainerInput(IIILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
	private void khoa$containerInput(int containerId, int slotNum, int button, ContainerInput input, Player player, CallbackInfo ci) {
		CraftEvents.onContainerInput(containerId, slotNum, button, input, player);
	}
}
