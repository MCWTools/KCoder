package khoa.script.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

/** Read/edit access to the chat history for delete_chat. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccess {
	@Accessor("allMessages")
	List<GuiMessage> khoa$allMessages();
}
