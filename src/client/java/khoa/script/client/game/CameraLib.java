package khoa.script.client.game;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.script.client.ScriptRuntime;
import khoa.script.lang.Args;
import khoa.script.lang.Convert;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** Camera and rotation API: reading where you look and turning (instantly, step by step, or smoothly over time). */
public final class CameraLib {
	private static final Registry R = Registry.get();

	private CameraLib() {
	}

	/** Where to aim at a target: the centre of an entity or block, or the point itself. */
	static Val.Vec3 aimPoint(Object target) {
		if (target instanceof EntityRef r) {
			return EntityLib.center(r.entity());
		}
		Val.Vec3 v = Convert.toVec3(target);
		if (v == null) {
			throw new KcdError("Expected a vec3, entity or block but got " + Val.typeName(target), 0);
		}
		return v;
	}

	/** Sets the player's rotation like moving the mouse would (sent to the server with the next movement packet). */
	public static void apply(LocalPlayer p, double yaw, double pitch) {
		float y = (float) yaw;
		float x = (float) Math.max(-90, Math.min(90, pitch));
		p.setYRot(y);
		p.setXRot(x);
		p.yRotO = y;
		p.xRotO = x;
		ScriptRuntime.refreshPick();
	}

	/** Moves the rotation a fraction of the remaining way to a target (1 = all the way). Returns the remaining angle in degrees. */
	static double turn(LocalPlayer p, double yaw, double pitch, double fraction) {
		double dy = Convert.wrapDegrees(yaw - p.getYRot());
		double dp = pitch - p.getXRot();
		double f = Math.max(0, Math.min(1, fraction));
		apply(p, f >= 1 ? p.getYRot() + dy : p.getYRot() + dy * f, f >= 1 ? pitch : p.getXRot() + dp * f);
		return Math.hypot(dy * (1 - f), dp * (1 - f));
	}

	private static Val.Rotation toward(LocalPlayer p, Object target) {
		return toward(p, target, false);
	}

	/** With nearest, an entity (or hitbox) target is aimed at the point of its REAL hitbox closest to where you aim now, not at its centre. */
	private static Val.Rotation toward(LocalPlayer p, Object target, boolean nearest) {
		if (nearest) {
			Entity e = target instanceof HitboxRef h ? h.entity : target instanceof EntityRef r ? r.entity() : null;
			if (e != null) {
				khoa.api.aim.Rotation near = khoa.api.aim.KhoaAim.getNearestAngleToAim(p, HitboxLib.realBox(e));
				return new Val.Rotation(near.yaw(), near.pitch());
			}
		}
		return Convert.directionToRotation(aimPoint(target).sub(G.v(p.getEyePosition())));
	}

	public static void register() {
		String c = "camera";
		R.def(c, "get_camera() -> map", "Camera info: position, rotation, direction, detached (third person).", a -> {
			Camera cam = G.mc().gameRenderer.mainCamera();
			Map<Object, Object> m = new LinkedHashMap<>();
			m.put("position", G.v(cam.position()));
			m.put("rotation", new Val.Rotation(Convert.wrapDegrees(cam.yRot()), cam.xRot()));
			m.put("direction", Convert.rotationToDirection(cam.yRot(), cam.xRot()));
			m.put("detached", cam.isDetached());
			return m;
		}).client();
		R.def(c, "get_camera_position() -> vec3", "Where the camera is.", a -> G.v(G.mc().gameRenderer.mainCamera().position())).client();
		R.def(c, "get_camera_rotation() -> rotation", "Yaw and pitch of the camera.", a -> {
			Camera cam = G.mc().gameRenderer.mainCamera();
			return new Val.Rotation(Convert.wrapDegrees(cam.yRot()), cam.xRot());
		}).client();
		R.def(c, "get_camera_direction() -> vec3", "Unit vector the camera looks along.", a -> {
			Camera cam = G.mc().gameRenderer.mainCamera();
			return Convert.rotationToDirection(cam.yRot(), cam.xRot());
		}).client();
		R.def(c, "get_yaw() -> number", "Your yaw in degrees (0 = south, -90 = east, 90 = west, 180 = north).", a -> Convert.wrapDegrees(G.me().getYRot())).client();
		R.def(c, "get_pitch() -> number", "Your pitch in degrees (-90 = up, 90 = down).", a -> (double) G.me().getXRot()).client();
		R.def(c, "get_look_vector() -> vec3", "Unit vector you look along.", a -> G.v(G.me().getLookAngle())).client();
		R.def(c, "is_looking_at(target:vec3, tolerance?:number=3) -> boolean",
			"True if your crosshair is on the target: for an entity or block, the look ray hits its box; for a point, within `tolerance` degrees.", a -> {
				var p = G.me();
				Vec3 eye = p.getEyePosition();
				Vec3 look = p.getLookAngle();
				Object t = a.obj(0);
				AABB box = null;
				if (t instanceof EntityRef r) {
					box = r.entity().getBoundingBox().inflate(0.05);
				} else if (t instanceof BlockRef b) {
					box = new AABB(b.blockPos());
				}
				if (box != null) {
					return box.clip(eye, eye.add(look.scale(128))).isPresent() || box.contains(eye);
				}
				Val.Vec3 d = a.vec3(0).sub(G.v(eye));
				double l = d.length();
				if (l == 0) {
					return true;
				}
				double cos = d.dot(G.v(look)) / l;
				return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) <= a.num(1, 3);
			}).client();
		R.def(c, "set_player_direction(yaw:number, pitch:number)", "Turns you to this yaw/pitch (degrees) at once.", a -> {
			apply(G.me(), a.num(0), a.num(1));
			return null;
		}).client().perm("look").example("set_player_direction(yaw, pitch)");
		R.alias("set_camera_rotation", "set_player_direction");
		R.def(c, "look_at(target:vec3, nearest?:boolean=false)", "Turns you at once to look at a point, entity (its centre) or block (its centre). With nearest=true an entity is aimed at the point of its real hitbox closest to your current aim instead of its centre.", a -> {
			var p = G.me();
			Val.Rotation r = toward(p, a.obj(0), a.bool(1, false));
			apply(p, r.yaw(), r.pitch());
			return null;
		}).client().perm("look").example("look_at(allay)");
		R.def(c, "turn_towards(yaw:number, pitch:number, fraction?:number=1) -> number",
			"Turns you the given FRACTION of the remaining way towards yaw/pitch. fraction 1 lands exactly on it. Returns the angle still left (degrees).",
			a -> turn(G.me(), a.num(0), a.num(1), a.num(2, 1))).client().perm("look");
		R.def(c, "rotate_towards(target:vec3, max_degrees?:number=5) -> number",
			"Turns you towards a target by at most max_degrees (one step). Returns the angle still left; 0 = looking right at it. Call it in a loop with wait().", a -> {
				var p = G.me();
				Val.Rotation r = toward(p, a.obj(0));
				double dy = Convert.wrapDegrees(r.yaw() - p.getYRot());
				double dp = r.pitch() - p.getXRot();
				double dist = Math.hypot(dy, dp);
				double max = Math.max(0.01, a.num(1, 5));
				if (dist <= max) {
					apply(p, p.getYRot() + dy, r.pitch());
					return 0.0;
				}
				double k = max / dist;
				apply(p, p.getYRot() + dy * k, p.getXRot() + dp * k);
				return dist - max;
			}).client().perm("look").example("while rotate_towards(opponent, 8) > 0:\n    wait_tick(1)");
		R.def(c, "look_at_smooth(target:any, steps?:number=100, min_ms?:number=1, max_ms?:number=3, nearest?:boolean=false)",
			"Smoothly turns you to the target in `steps` small moves, waiting a random min_ms..max_ms between them. Pass the entity itself (look_at_smooth(allay)) or a function that returns a position (lambda: allay.pos) and the target is read again every step, so a moving target is followed. A plain vec3 such as allay.pos is a fixed point read once. With nearest=true an entity target is aimed at the closest point of its real hitbox (re-evaluated every step) instead of its centre. Only the script that calls it waits; other scripts and event handlers keep running.",
			a -> {
				Object target = a.obj(0);
				int steps = Math.max(1, a.intv(1, 100));
				double lo = a.num(2, 1);
				double hi = Math.max(lo, a.num(3, 3));
				boolean nearest = a.bool(4, false);
				for (int n = 1; n <= steps; n++) {
					final double fraction = 1.0 / (steps - n + 1);
					// a function target is asked again every step, so it can follow something that moves
					final Object aim = a.isFunction(0) ? a.call(0) : target;
					a.in.onClient(() -> {
						var p = G.me();
						Val.Rotation r = toward(p, aim, nearest);
						turn(p, r.yaw(), r.pitch(), fraction);
						return null;
					});
					if (n < steps) {
						a.in.waitSeconds((lo + khoa.script.lib.CoreLib.random.nextDouble() * (hi - lo)) / 1000.0);
					}
				}
				return null;
			}).suspends().perm("look").example("look_at_smooth(allay)  # same as the 100-step loop below");
	}

	/** Used by docs. */
	static Args none() {
		return null;
	}

	static Entity unused() {
		return null;
	}
}
