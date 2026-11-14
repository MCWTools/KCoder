package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

import khoa.script.client.ScriptRuntime;

/** Same priority rule for mouse buttons used as script hotkeys (on_key_press("MOUSE_RIGHT")). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"))
	private void khoa$scroll(long window, double xo, double yo, CallbackInfo ci) {
		ScriptRuntime.addScroll(yo);
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void khoa$claimButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (ScriptRuntime.onMouse(action, info.button(), info.hasControlDown(), info.hasShiftDown(), info.hasAltDown())) {
			ci.cancel();
		}
	}
}
