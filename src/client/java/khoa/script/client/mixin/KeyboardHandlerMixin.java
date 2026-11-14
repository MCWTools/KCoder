package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import khoa.script.client.ScriptRuntime;

/** Script hotkeys get the key BEFORE Minecraft's key bindings (Options > Controls): a claimed key never reaches them. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void khoa$claimKey(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (ScriptRuntime.onKey(action, event.key(), event.hasControlDown(), event.hasShiftDown(), event.hasAltDown())) {
			ci.cancel();
		}
	}
}
