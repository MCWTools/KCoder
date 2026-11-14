package khoa.script.client.game;

import net.minecraft.world.entity.Entity;

import khoa.api.hitbox.KhoaHitbox;
import net.minecraft.world.phys.AABB;

import khoa.script.lang.KcdError;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** A script handle to a client-side scaled hitbox (made by hitbox_entity). */
public final class HitboxRef implements Val.KSettable {
	public final Session session;
	public final Entity entity;
	volatile boolean removed;

	HitboxRef(Session session, Entity entity) {
		this.session = session;
		this.entity = entity;
	}

	@Override
	public String typeName() {
		return "hitbox";
	}

	public double scale() {
		return KhoaHitbox.getScale(entity);
	}

	public boolean active() {
		return !removed && !entity.isRemoved();
	}

	/** The vanilla box, ignoring the scale. */
	public AABB realBox() {
		return HitboxLib.realBox(entity);
	}

	@Override
	public void setMember(String name, Object value) {
		if (!name.equals("scale")) {
			throw new KcdError("hitbox has no settable property '" + name + "' (only scale)", 0);
		}
		if (removed) {
			throw new KcdError("This hitbox was removed", 0);
		}
		KhoaHitbox.scale(entity, HitboxLib.checkScale(value));
	}

	@Override
	public String toString() {
		return "<hitbox " + G.entityId(entity) + " #" + entity.getId() + " x" + scale() + (active() ? "" : " removed") + ">";
	}
}
