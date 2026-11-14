package khoa.script.client.game;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import khoa.script.lang.Convert;
import khoa.script.lang.Val;

/** A script handle to an entity. Two refs to the same entity are equal. */
public record EntityRef(Entity entity) implements Val.KObject, Convert.HasPos {
	@Override
	public String typeName() {
		return entity instanceof Player ? "player" : "entity";
	}

	@Override
	public Val.Vec3 pos() {
		return G.v(entity.position());
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof EntityRef r && r.entity == entity;
	}

	@Override
	public int hashCode() {
		return entity.getId();
	}

	@Override
	public String toString() {
		return "<" + typeName() + " " + G.entityId(entity) + " #" + entity.getId() + ">";
	}
}
