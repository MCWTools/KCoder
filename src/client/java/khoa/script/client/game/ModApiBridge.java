package khoa.script.client.game;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.Builtin;
import khoa.script.lang.Convert;
import khoa.script.lang.Interp;
import khoa.script.lang.KcdError;
import khoa.script.lang.ModApiHost;
import khoa.script.lang.Scope;
import khoa.script.lang.Val;

/**
 * {@code fakeplayer = implement khoa.fakeplayer} then {@code player = fakeplayer:GetPlayer("Bot")}, {@code player:GetAimingEntity()}.
 * Pure reflection (KCoder has no compile dependency on the FakePlayer mod). Every call runs on the integrated server thread so the
 * values are the exact server state. Method names ignore case, underscores and a leading get/is.
 */
public final class ModApiBridge implements ModApiHost {
	public static final ModApiBridge INSTANCE = new ModApiBridge();
	private static final String PACKAGE = "khoa.fakeplayer";

	private ModApiBridge() {
	}

	@Override
	public boolean handles(String ref) {
		return ref.equals(PACKAGE) || ref.startsWith(PACKAGE + ".");
	}

	@Override
	public Val.KModule load(Interp in, String ref) {
		if (!FabricLoader.getInstance().isModLoaded("fakeplayer")) {
			throw new KcdError("The FakePlayer mod is not installed (implement " + ref + ")", 0);
		}
		Class<?> api;
		try {
			api = Class.forName("khoa.fakeplayer.api.FakePlayerApi");
		} catch (ClassNotFoundException e) {
			throw new KcdError("FakePlayer API class not found", 0);
		}
		Val.KModule mod = new Val.KModule("fakeplayer", ref, new Scope(null, "fakeplayer"));
		for (String n : new String[] {"GetPlayer", "getplayer", "get_player", "getPlayer", "Get", "get"}) {
			mod.scope.define(n, fn(n, "name:string", "The bot called name (or nil when it is not online).", a -> {
				Object r = fast(() -> {
					Optional<?> o = (Optional<?>) call(api, "get", List.<Object>of(a.v[0] == null ? "" : String.valueOf(a.v[0])));
					return o.orElse(null);
				});
				return wrap(r);
			}));
		}
		for (String n : new String[] {"All", "all", "GetAll", "getall", "get_all", "GetPlayers", "getplayers"}) {
			mod.scope.define(n, fn(n, "", "Every bot that is online.", a -> {
				Object r = fast(() -> new ArrayList<Object>((java.util.Collection<?>) call(api, "all", List.<Object>of())));
				return wrap(r);
			}));
		}
		mod.loaded = true;
		return mod;
	}

	private static Builtin fn(String name, String sig, String desc, Builtin.Impl impl) {
		List<Builtin.ParamDef> ps = sig.isEmpty() ? List.of() : List.of(new Builtin.ParamDef("name", "string", false, null, false));
		return new Builtin(name, "fakeplayer", name + "(" + sig + ")", ps, "any", desc, impl).client();
	}

	// ------------------------------------------------------------------ server thread

	private static MinecraftServer server() {
		MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
		if (s == null) {
			throw new KcdError("FakePlayer needs a singleplayer world or an integrated server", 0);
		}
		return s;
	}

	/** Reads state on the calling thread (no wait for the server tick); falls back to the server thread if that throws. */
	private static <T> T fast(java.util.function.Supplier<T> job) {
		try {
			return job.get();
		} catch (KcdError e) {
			throw e;
		} catch (RuntimeException e) {
			return onServer(job);
		}
	}

	private static <T> T onServer(java.util.function.Supplier<T> job) {
		MinecraftServer s = server();
		if (s.isSameThread()) {
			return job.get();
		}
		CompletableFuture<T> f = s.submit(job);
		try {
			return f.join();
		} catch (java.util.concurrent.CompletionException e) {
			Throwable c = e.getCause() == null ? e : e.getCause();
			throw new KcdError("fakeplayer: " + c.getClass().getSimpleName() + ": " + c.getMessage(), 0);
		}
	}

	// ------------------------------------------------------------------ the bot object

	/** A script handle to one bot; any BotHandle method can be called by name. */
	public record BotRef(Object handle) implements Val.KInvokable, Convert.HasPos, Convert.HasBox {
		@Override
		public double[] box() {
			net.minecraft.world.phys.AABB b = fast(() -> ((net.minecraft.server.level.ServerPlayer) call(handle, "player", List.<Object>of())).getBoundingBox());
			return new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
		}

		@Override
		public String typeName() {
			return "fakeplayer";
		}

		@Override
		public Val.Vec3 pos() {
			Vec3 p = (Vec3) fast(() -> call(handle, "position", List.<Object>of()));
			return new Val.Vec3(p.x, p.y, p.z);
		}

		@Override
		public Object invoke(String name, List<Object> args) {
			MinecraftServer s = server();
			if (returnsVoid(handle, name, args.size())) {
				// commands never block the game thread: they run on the next server tick
				s.execute(() -> {
					try {
						call(handle, name, args);
					} catch (RuntimeException e) {
						System.err.println("[fakeplayer] " + name + ": " + e.getMessage());
					}
				});
				return null;
			}
			// reads happen right here (no wait for the server tick); only if that fails do we ask the server thread
			try {
				return wrap(call(handle, name, args));
			} catch (KcdError e) {
				throw e;
			} catch (RuntimeException e) {
				return wrap(onServer(() -> call(handle, name, args)));
			}
		}

		@Override
		public String toString() {
			return "<fakeplayer " + fast(() -> String.valueOf(call(handle, "name", List.<Object>of()))) + ">";
		}
	}

	// ------------------------------------------------------------------ reflection call

	private static String norm(String n) {
		return n.toLowerCase(Locale.ROOT).replace("_", "");
	}

	private static boolean returnsVoid(Object target, String name, int argc) {
		String want = norm(name);
		for (Method m : target.getClass().getMethods()) {
			if (m.getParameterCount() == argc && norm(m.getName()).equals(want)) {
				return m.getReturnType() == void.class;
			}
		}
		return false;
	}

	private static Object call(Object target, String name, List<Object> args) {
		Class<?> cls = target instanceof Class<?> c ? c : target.getClass();
		String want = norm(name);
		List<String> wants = new ArrayList<>();
		wants.add(want);
		for (String pre : new String[] {"get", "is", "set"}) {
			if (want.startsWith(pre) && want.length() > pre.length()) {
				wants.add(want.substring(pre.length()));
			}
		}
		List<String> seen = new ArrayList<>();
		Method best = null;
		Object[] bestArgs = null;
		for (String w : wants) {
			for (Method m : cls.getMethods()) {
				if (m.getDeclaringClass() == Object.class || (target instanceof Class<?> && !Modifier.isStatic(m.getModifiers()))) {
					continue;
				}
				seen.add(m.getName());
				if (!norm(m.getName()).equals(w) || m.getParameterCount() != args.size()) {
					continue;
				}
				Object[] conv = convertArgs(m, args);
				if (conv != null) {
					best = m;
					bestArgs = conv;
					break;
				}
			}
			if (best != null) {
				break;
			}
		}
		if (best == null) {
			throw new KcdError("fakeplayer has no method '" + name + "' taking " + args.size() + " argument(s)", 0)
				.withHint("methods: " + String.join(", ", new java.util.TreeSet<>(seen)));
		}
		try {
			best.setAccessible(true);
			return best.invoke(target instanceof Class<?> ? null : target, bestArgs);
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable c = e.getCause() == null ? e : e.getCause();
			throw new KcdError(best.getName() + "(): " + c.getClass().getSimpleName() + ": " + c.getMessage(), 0);
		} catch (ReflectiveOperationException e) {
			throw new KcdError(best.getName() + "(): " + e, 0);
		}
	}

	private static Object[] convertArgs(Method m, List<Object> args) {
		Class<?>[] types = m.getParameterTypes();
		Object[] out = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			Object v = args.get(i);
			Object c = convert(types[i], v);
			if (c == NO) {
				return null;
			}
			out[i] = c;
		}
		return out;
	}

	private static final Object NO = new Object();

	private static Object convert(Class<?> t, Object v) {
		if (t == int.class || t == Integer.class) {
			return v instanceof Double d ? (Object) (int) Math.round(d) : NO;
		}
		if (t == long.class || t == Long.class) {
			return v instanceof Double d ? (Object) Math.round(d) : NO;
		}
		if (t == float.class || t == Float.class) {
			return v instanceof Double d ? (Object) (float) (double) d : NO;
		}
		if (t == double.class || t == Double.class) {
			return v instanceof Double d ? (Object) d : NO;
		}
		if (t == boolean.class || t == Boolean.class) {
			return v instanceof Boolean b ? (Object) b : (v == null ? (Object) false : NO);
		}
		if (t == String.class) {
			if (v instanceof Double d && d == Math.rint(d)) {
				return String.valueOf((long) (double) d);
			}
			return v instanceof String s ? s : (v == null ? NO : String.valueOf(v));
		}
		if (t == Vec3.class) {
			if (v instanceof Val.Vec3 p) {
				return new Vec3(p.x(), p.y(), p.z());
			}
			if (v instanceof Convert.HasPos h) {
				Val.Vec3 p = h.pos();
				return new Vec3(p.x(), p.y(), p.z());
			}
			return NO;
		}
		if (t == BlockPos.class) {
			if (v instanceof BlockRef b) {
				return b.blockPos();
			}
			if (v instanceof Val.Vec3 p) {
				return BlockPos.containing(p.x(), p.y(), p.z());
			}
			return NO;
		}
		if (Entity.class.isAssignableFrom(t)) {
			if (v instanceof EntityRef e) {
				Entity s = serverEntity(e.entity());
				return s != null && t.isInstance(s) ? s : NO;
			}
			return NO;
		}
		if (t.isInstance(v)) {
			return v;
		}
		if (t.isInterface() && v instanceof Val.KFunction) {
			return NO;
		}
		return NO;
	}

	private static Entity serverEntity(Entity clientEntity) {
		UUID id = clientEntity.getUUID();
		for (ServerLevel l : server().getAllLevels()) {
			Entity e = l.getEntity(id);
			if (e != null) {
				return e;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ server -> script values

	private static Object wrap(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Optional<?> opt) {
			return wrap(opt.orElse(null));
		}
		if (o instanceof Number n) {
			return n.doubleValue();
		}
		if (o instanceof Boolean || o instanceof String) {
			return o;
		}
		if (o instanceof UUID u) {
			return u.toString();
		}
		if (o instanceof Vec3 v) {
			return new Val.Vec3(v.x, v.y, v.z);
		}
		if (o instanceof BlockPos p) {
			return new BlockRef(p.immutable());
		}
		if (o instanceof ItemStack s) {
			return new ItemRef(s.copy(), -1);
		}
		if (o instanceof Entity e) {
			var level = Minecraft.getInstance().level;
			Entity ce = level == null ? null : level.getEntity(e.getId());
			return ce == null ? null : new EntityRef(ce);
		}
		if (o instanceof HitResult h) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", h.getType().name().toLowerCase(Locale.ROOT));
			m.put("pos", wrap(h.getLocation()));
			if (h instanceof EntityHitResult eh) {
				m.put("entity", wrap(eh.getEntity()));
			}
			if (h instanceof BlockHitResult bh) {
				m.put("block", wrap(bh.getBlockPos()));
				m.put("face", bh.getDirection().getName());
			}
			return m;
		}
		if (o instanceof java.util.Collection<?> c) {
			List<Object> out = new ArrayList<>();
			for (Object x : c) {
				out.add(wrap(x));
			}
			return out;
		}
		if (o.getClass().isRecord()) {
			Map<String, Object> m = new LinkedHashMap<>();
			for (RecordComponent rc : o.getClass().getRecordComponents()) {
				try {
					rc.getAccessor().setAccessible(true);
					m.put(rc.getName(), wrap(rc.getAccessor().invoke(o)));
				} catch (ReflectiveOperationException e) {
					m.put(rc.getName(), null);
				}
			}
			return m;
		}
		if (o.getClass().getName().startsWith("khoa.fakeplayer.")) {
			return new BotRef(o);
		}
		return String.valueOf(o);
	}
}
