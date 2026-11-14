package khoa.script.client.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import khoa.script.lang.Convert;
import khoa.script.lang.Val;

/** A block position in the world; the state is read when asked. HasPos gives the block centre. */
public record BlockRef(BlockPos blockPos) implements Val.KObject, Convert.HasPos {
	@Override
	public String typeName() {
		return "block";
	}

	@Override
	public Val.Vec3 pos() {
		return new Val.Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
	}

	public String id() {
		return BuiltInRegistries.BLOCK.getKey(G.level().getBlockState(blockPos).getBlock()).toString();
	}

	@Override
	public String toString() {
		return "<block " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + ">";
	}
}
