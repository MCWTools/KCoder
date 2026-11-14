package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import khoa.script.client.game.HitboxLib;
import khoa.script.client.game.HitboxScale;

/**
 * hitbox_entity(): every client side reader of the bounding box (crosshair pick, F3+B, other mods) sees the scaled box.
 * Only the value returned by getBoundingBox is changed; the entity's real box is never overwritten, so removing the hitbox restores it at once.
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements HitboxScale {
	@Unique
	private volatile double khoa$hitboxScale = 1.0;

	@Override
	public double khoa$getScale() {
		return khoa$hitboxScale;
	}

	@Override
	public void khoa$setScale(double scale) {
		khoa$hitboxScale = scale;
	}

	@Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
	private void khoa$scaleBox(CallbackInfoReturnable<AABB> cir) {
		double s = khoa$hitboxScale;
		if (s != 1.0 && !HitboxLib.raw) {
			cir.setReturnValue(HitboxLib.scaled(cir.getReturnValue(), s));
		}
	}
}
