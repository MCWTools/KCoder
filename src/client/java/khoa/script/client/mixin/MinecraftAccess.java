package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Minecraft;

/** Private client actions used by script clicks. */
@Mixin(Minecraft.class)
public interface MinecraftAccess {
	/** Refreshes Minecraft.hitResult / crosshairPickEntity for the current camera. */
	@Invoker("pick")
	void khoa$pick(float partialTick);
}
