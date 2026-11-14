package khoa.script.client.render3d;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Builds camera-relative quads (boxes, thick lines) into one vertex consumer. World coordinates in, camera-relative floats out. */
final class Draw {
	final VertexConsumer vc;
	final PoseStack.Pose pose;
	final double cx;
	final double cy;
	final double cz;

	Draw(VertexConsumer vc, PoseStack.Pose pose, Vec3 cam) {
		this.vc = vc;
		this.pose = pose;
		this.cx = cam.x;
		this.cy = cam.y;
		this.cz = cam.z;
	}

	private void v(double x, double y, double z, int argb) {
		vc.addVertex(pose, (float) (x - cx), (float) (y - cy), (float) (z - cz)).setColor(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
	}

	void quad(double ax, double ay, double az, double bx, double by, double bz, double cx2, double cy2, double cz2, double dx, double dy, double dz, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		v(ax, ay, az, argb);
		v(bx, by, bz, argb);
		v(cx2, cy2, cz2, argb);
		v(dx, dy, dz, argb);
	}

	/** A filled axis-aligned box. */
	void box(double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
		quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb);
		quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb);
		quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb);
		quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb);
		quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb);
		quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb);
	}

	void box(AABB b, double grow, int argb) {
		box(b.minX - grow, b.minY - grow, b.minZ - grow, b.maxX + grow, b.maxY + grow, b.maxZ + grow, argb);
	}

	/** The 12 edges of a box as thin boxes of the given thickness (world units). */
	void boxEdges(AABB b, double thickness, int argb) {
		if (thickness <= 0 || (argb >>> 24) == 0) {
			return;
		}
		double h = thickness / 2;
		double[] xs = { b.minX, b.maxX };
		double[] ys = { b.minY, b.maxY };
		double[] zs = { b.minZ, b.maxZ };
		for (double y : ys) {
			for (double z : zs) {
				box(b.minX - h, y - h, z - h, b.maxX + h, y + h, z + h, argb);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				box(x - h, b.minY - h, z - h, x + h, b.maxY + h, z + h, argb);
			}
		}
		for (double x : xs) {
			for (double y : ys) {
				box(x - h, y - h, b.minZ - h, x + h, y + h, b.maxZ + h, argb);
			}
		}
	}

	/** A straight line with a square cross-section of the given thickness (world units). */
	void line(double ax, double ay, double az, double bx, double by, double bz, double thickness, int argb) {
		if (thickness <= 0 || (argb >>> 24) == 0) {
			return;
		}
		double dx = bx - ax;
		double dy = by - ay;
		double dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-9) {
			return;
		}
		double nx = dx / len;
		double ny = dy / len;
		double nz = dz / len;
		double hx = Math.abs(ny) < 0.9 ? 0 : 1;
		double hy = Math.abs(ny) < 0.9 ? 1 : 0;
		// u = n x helper
		double ux = ny * 0 - nz * hy;
		double uy = nz * hx - nx * 0;
		double uz = nx * hy - ny * hx;
		double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
		double h = thickness / 2;
		ux = ux / ul * h;
		uy = uy / ul * h;
		uz = uz / ul * h;
		// w = n x u (unit length scaled to h)
		double wx = (ny * uz - nz * uy) ;
		double wy = (nz * ux - nx * uz);
		double wz = (nx * uy - ny * ux);
		// corners around the axis
		double[][] s = { { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 } };
		double[][] a = new double[4][3];
		double[][] b = new double[4][3];
		for (int i = 0; i < 4; i++) {
			double ox = ux * s[i][0] + wx * s[i][1];
			double oy = uy * s[i][0] + wy * s[i][1];
			double oz = uz * s[i][0] + wz * s[i][1];
			a[i] = new double[] { ax + ox, ay + oy, az + oz };
			b[i] = new double[] { bx + ox, by + oy, bz + oz };
		}
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			quad(a[i][0], a[i][1], a[i][2], b[i][0], b[i][1], b[i][2], b[j][0], b[j][1], b[j][2], a[j][0], a[j][1], a[j][2], argb);
		}
		quad(a[0][0], a[0][1], a[0][2], a[1][0], a[1][1], a[1][2], a[2][0], a[2][1], a[2][2], a[3][0], a[3][1], a[3][2], argb);
		quad(b[0][0], b[0][1], b[0][2], b[1][0], b[1][1], b[1][2], b[2][0], b[2][1], b[2][2], b[3][0], b[3][1], b[3][2], argb);
	}

	void line(Vec3 a, Vec3 b, double thickness, int argb) {
		line(a.x, a.y, a.z, b.x, b.y, b.z, thickness, argb);
	}
}
