package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.EntityHitResult;

import khoa.script.client.game.CraftEvents;
import khoa.script.client.game.HitboxLib;

/** Feeds on_craft (every menu click goes through here) and keeps hits on a fake hitbox (hitbox_entity) on the client. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "handleContainerInput(IIILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
	private void khoa$containerInput(int containerId, int slotNum, int button, ContainerInput input, Player player, CallbackInfo ci) {
		CraftEvents.onContainerInput(containerId, slotNum, button, input, player);
	}

	/** An attack that only landed on the fake part of a scaled hitbox fires on_hit and sends nothing to the server. */
	@Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void khoa$attack(Player player, Entity target, CallbackInfo ci) {
		if (HitboxLib.onAttack(player, target)) {
			ci.cancel();
		}
	}

	/** Same for a right click on the fake part of a scaled hitbox. */
	@Inject(method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
	private void khoa$interact(Player player, Entity target, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (HitboxLib.onInteract(player, target)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
