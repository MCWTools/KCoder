package khoa.script.client.game;

import khoa.script.lang.Registry;

/** Client-side movement: reading and changing the velocity of your own player. */
public final class MotionLib {
	private static final Registry R = Registry.get();

	private MotionLib() {
	}

	public static void register() {
		String c = "motion";
		R.def(c, "get_velocity() -> vec3", "Your current velocity in blocks per tick (what the game applies each tick; falling gives a negative y).", a -> G.v(G.me().getDeltaMovement())).client();
		R.def(c, "set_client_velocity(velocity:vec3)", "Replaces your velocity. Client side only: the server may correct you when it disagrees, so it works best where the server trusts the client.", a -> {
			G.me().setDeltaMovement(G.mv(a.vec3(0)));
			return null;
		}).client().perm("move").example("set_client_velocity(vec3(0, 0.6, 0))  # a jump-sized hop");
		R.def(c, "add_client_velocity(velocity:vec3)", "Adds to your velocity (a push). Client side only.", a -> {
			G.me().addDeltaMovement(G.mv(a.vec3(0)));
			return null;
		}).client().perm("move").example("add_client_velocity(get_look_vector() * 1.5)  # dash forward");
	}
}
