package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

import khoa.script.client.game.ChatLog;

/** Records every line added to the chat (own and system messages too). Does not touch vanilla's history limit: KhoaTools Better Chat owns that. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "addMessageToQueue", at = @At("HEAD"))
	private void khoa$record(GuiMessage message, CallbackInfo ci) {
		ChatLog.add(message.content().getString());
	}
}
