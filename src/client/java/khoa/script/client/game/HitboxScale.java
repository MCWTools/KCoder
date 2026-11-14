package khoa.script.client.game;

/** Implemented by Entity through EntityMixin: the client-side hitbox scale (1 = vanilla). */
public interface HitboxScale {
	double khoa$getScale();

	void khoa$setScale(double scale);
}
