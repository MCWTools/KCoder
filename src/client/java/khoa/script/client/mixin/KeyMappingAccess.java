package khoa.script.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.KeyMapping;

@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
	@Accessor("clickCount")
	int khoa$getClickCount();

	@Accessor("clickCount")
	void khoa$setClickCount(int count);

	@Accessor("key")
	com.mojang.blaze3d.platform.InputConstants.Key khoa$getKey();
}
