package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

import khoa.script.client.ScriptRuntime;

/** Runs the script scheduler once per rendered frame, so short waits (a few milliseconds) are not rounded up to a 50 ms tick. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void khoa$frame(CallbackInfo ci) {
		ScriptRuntime.onFrame();
	}
}
