package khoa.script.client.render3d;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Session;

/** Owns every 3D shape of the running scripts and draws them each frame. Shapes of a script are removed when it stops. */
public final class World3D {
	private static final List<Shape3D> ALL = new CopyOnWriteArrayList<>();
	private static final Set<Session> HOOKED = ConcurrentHashMap.newKeySet();
	private static boolean installed;

	private World3D() {
	}

	public static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			if (ALL.isEmpty()) {
				return;
			}
			Vec3 cam = ctx.levelState().cameraRenderState.pos;
			var ps = ctx.poseStack();
			var collector = ctx.submitNodeCollector();
			List<Shape3D> shapes = List.copyOf(ALL);
			for (boolean xray : new boolean[] { false, true }) {
				boolean any = false;
				for (Shape3D s : shapes) {
					any |= s.throughWalls == xray && s.visible && !s.deleted && !(s instanceof ImageShape);
				}
				if (!any) {
					continue;
				}
				collector.submitCustomGeometry(ps, xray ? xrayType() : RenderTypes.debugQuads(), (pose, vc) -> {
					Draw d = new Draw(vc, pose, cam);
					for (Shape3D s : shapes) {
						if (s.throughWalls == xray && s.visible && !s.deleted && !(s instanceof ImageShape)) {
							try {
								s.render(d, cam);
							} catch (RuntimeException e) {
								s.visible = false;
							}
						}
					}
				});
			}
			for (Shape3D s : shapes) {
				if (s instanceof ImageShape img && img.visible && !img.deleted && img.texture != null) {
					collector.submitCustomGeometry(ps, RenderTypes.entityTranslucent(img.texture), (pose, vc) -> img.renderTextured(vc, pose, cam));
				}
			}
		});
	}

	private static RenderType xray;
	private static boolean xrayFailed;

	/** Same as the debug quads but the depth test always passes, so it shows through walls. Falls back to the normal type if it cannot be built. */
	private static RenderType xrayType() {
		if (xray == null && !xrayFailed) {
			try {
				Field f = RenderPipelines.class.getDeclaredField("DEBUG_FILLED_SNIPPET");
				f.setAccessible(true);
				RenderPipeline.Snippet snippet = (RenderPipeline.Snippet) f.get(null);
				RenderPipeline pipeline = RenderPipeline.builder(snippet)
					.withLocation("pipeline/khoascript_xray_quads")
					.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
					.build();
				xray = RenderType.create("khoascript_xray_quads", RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup());
			} catch (ReflectiveOperationException | RuntimeException e) {
				xrayFailed = true;
			}
		}
		return xray != null ? xray : RenderTypes.debugQuads();
	}

	public static <T extends Shape3D> T add(T s) {
		if (HOOKED.add(s.session)) {
			s.session.onStop.add(() -> {
				removeAll(s.session);
				HOOKED.remove(s.session);
			});
		}
		ALL.add(s);
		return s;
	}

	static void remove(Shape3D s) {
		ALL.remove(s);
	}

	public static void removeAll(Session session) {
		ALL.removeIf(s -> {
			if (s.session == session) {
				s.deleted = true;
				return true;
			}
			return false;
		});
	}

	public static void clear(Session session) {
		removeAll(session);
	}

	public static int count() {
		return ALL.size();
	}
}
