package khoa.script.client.render3d;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.KcdError;
import khoa.script.lang.Session;
import khoa.script.lang.Val;

/** A picture standing in the world (image_3d). Drawn with its own texture render type, so it is submitted separately from the colored shapes. */
public final class ImageShape extends Shape3D {
	public volatile Identifier texture;
	public volatile Vec3 pos = Vec3.ZERO;
	public volatile double width = 1;
	public volatile double height = 1;
	/** "camera" (billboard), "horizontal", or a fixed normal. */
	public volatile String facing = "camera";
	public volatile Vec3 normal = new Vec3(0, 0, 1);
	public volatile double alpha = 1;

	public ImageShape(Session s) {
		super(s);
	}

	@Override
	public String typeName() {
		return "image3d";
	}

	@Override
	void render(Draw d, Vec3 cam) {
		// colored pass draws nothing for images
	}

	/** Called by World3D with the textured vertex consumer; world coordinates are converted with the camera. */
	void renderTextured(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam) {
		Vec3 n;
		Vec3 up = new Vec3(0, 1, 0);
		switch (facing) {
			case "camera" -> {
				n = cam.subtract(pos);
				if (n.lengthSqr() < 1e-8) {
					return;
				}
				n = n.normalize();
			}
			case "horizontal" -> {
				n = new Vec3(0, 1, 0);
				up = new Vec3(0, 0, -1);
			}
			default -> {
				n = normal.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : normal.normalize();
				if (Math.abs(n.y) > 0.99) {
					up = new Vec3(0, 0, -1);
				}
			}
		}
		Vec3 right = up.cross(n).normalize();
		Vec3 realUp = n.cross(right).normalize();
		Vec3 r = right.scale(width / 2);
		Vec3 u = realUp.scale(height / 2);
		Vec3[] c = { pos.subtract(r).add(u), pos.add(r).add(u), pos.add(r).subtract(u), pos.subtract(r).subtract(u) };
		float[][] uv = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
		int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
		// draw both sides so the picture is visible from behind
		for (int side = 0; side < 2; side++) {
			for (int k = 0; k < 4; k++) {
				int i = side == 0 ? k : 3 - k;
				float sx = side == 0 ? (float) n.x : (float) -n.x;
				float sy = side == 0 ? (float) n.y : (float) -n.y;
				float sz = side == 0 ? (float) n.z : (float) -n.z;
				vc.addVertex(pose, (float) (c[i].x - cam.x), (float) (c[i].y - cam.y), (float) (c[i].z - cam.z))
					.setColor(255, 255, 255, a)
					.setUv(uv[i][0], uv[i][1])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(0xF000F0)
					.setNormal(pose, sx, sy, sz);
			}
		}
	}

	@Override
	public Object get(String name) {
		return switch (name) {
			case "pos" -> out(pos);
			case "width" -> width;
			case "height" -> height;
			case "alpha" -> alpha;
			case "facing" -> facing;
			case "visible" -> visible;
			default -> null;
		};
	}

	@Override
	public boolean set(String name, Object v) {
		switch (name) {
			case "pos" -> pos = vec(v, "pos");
			case "width" -> width = num(v, name);
			case "height" -> height = num(v, name);
			case "alpha" -> alpha = num(v, name);
			case "facing" -> setFacing(v);
			default -> {
				return false;
			}
		}
		return true;
	}

	public void setFacing(Object v) {
		if (v instanceof Val.Vec3 p) {
			normal = new Vec3(p.x(), p.y(), p.z());
			facing = "fixed";
		} else if (v == null) {
			facing = "camera";
		} else {
			String s = Val.str(v).toLowerCase();
			switch (s) {
				case "camera", "player", "billboard" -> facing = "camera";
				case "up", "down", "floor", "horizontal" -> facing = "horizontal";
				case "north" -> fixed(0, 0, -1);
				case "south" -> fixed(0, 0, 1);
				case "east" -> fixed(1, 0, 0);
				case "west" -> fixed(-1, 0, 0);
				default -> throw new KcdError("Unknown facing '" + s + "'", 0).withHint("use \"camera\", \"horizontal\", \"north\", \"south\", \"east\", \"west\" or a vec3 direction");
			}
		}
	}

	private void fixed(double x, double y, double z) {
		normal = new Vec3(x, y, z);
		facing = "fixed";
	}
}
