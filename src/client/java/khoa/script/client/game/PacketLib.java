package khoa.script.client.game;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.phys.Vec3;

import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/** packet.send(...), packet.record(...), packet.stoprec(): talk to the server with raw packets and see what goes over the wire. */
public final class PacketLib {
	private static final Registry R = Registry.get();
	private static final String[] PACKAGES = { "net.minecraft.network.protocol.game.", "net.minecraft.network.protocol.common.", "net.minecraft.network.protocol.ping." };

	private PacketLib() {
	}

	public record PacketNs() implements Val.KObject {
		@Override
		public String typeName() {
			return "packet_ns";
		}

		@Override
		public String toString() {
			return "<packet>";
		}
	}

	private static String camel(String s) {
		StringBuilder sb = new StringBuilder();
		for (String part : s.split("[_ .\\-]+")) {
			if (!part.isEmpty()) {
				sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
			}
		}
		return sb.toString();
	}

	private static Class<?> find(String name) {
		List<String> names = new ArrayList<>();
		String n = camel(name.contains(":") ? name.substring(name.indexOf(':') + 1) : name);
		if (name.contains(".")) {
			names.add(name);
		}
		for (String pkg : PACKAGES) {
			names.add(pkg + n);
			names.add(pkg + "Serverbound" + n + "Packet");
			names.add(pkg + "Serverbound" + n);
		}
		for (String full : names) {
			try {
				Class<?> c = Class.forName(full);
				if (Packet.class.isAssignableFrom(c)) {
					return c;
				}
			} catch (ClassNotFoundException | LinkageError ignored) {
				// try the next spelling
			}
		}
		throw new KcdError("No packet named '" + name + "'", 0).withHint("use the class name without Serverbound/Packet, e.g. packet.send(\"swing\", \"main_hand\"), or the full class name");
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object convert(Object v, Class<?> want) {
		if (want == int.class || want == Integer.class) {
			return v instanceof Double d ? (Object) d.intValue() : null;
		}
		if (want == long.class || want == Long.class) {
			return v instanceof Double d ? (Object) d.longValue() : null;
		}
		if (want == double.class || want == Double.class) {
			return v instanceof Double d ? (Object) d : null;
		}
		if (want == float.class || want == Float.class) {
			return v instanceof Double d ? (Object) d.floatValue() : null;
		}
		if (want == short.class || want == Short.class) {
			return v instanceof Double d ? (Object) d.shortValue() : null;
		}
		if (want == byte.class || want == Byte.class) {
			return v instanceof Double d ? (Object) d.byteValue() : null;
		}
		if (want == boolean.class || want == Boolean.class) {
			return v instanceof Boolean b ? b : null;
		}
		if (want == String.class) {
			return v instanceof String s ? s : null;
		}
		if (want == Vec3.class) {
			return v instanceof Val.Vec3 p ? new Vec3(p.x(), p.y(), p.z()) : null;
		}
		if (want == BlockPos.class) {
			return v instanceof Val.Vec3 p ? BlockPos.containing(p.x(), p.y(), p.z()) : null;
		}
		if (want.isEnum() && v instanceof String s) {
			for (Object e : want.getEnumConstants()) {
				if (((Enum) e).name().equalsIgnoreCase(s.replace(' ', '_'))) {
					return e;
				}
			}
			return null;
		}
		return null;
	}

	private static Packet<?> build(String name, Object[] args) {
		Class<?> type = find(name);
		List<String> tried = new ArrayList<>();
		for (Constructor<?> ctor : type.getConstructors()) {
			Parameter[] ps = ctor.getParameters();
			tried.add(describe(ctor));
			if (ps.length != args.length) {
				continue;
			}
			Object[] values = new Object[ps.length];
			boolean ok = true;
			for (int i = 0; i < ps.length; i++) {
				values[i] = convert(args[i], ps[i].getType());
				if (values[i] == null) {
					ok = false;
					break;
				}
			}
			if (ok) {
				try {
					return (Packet<?>) ctor.newInstance(values);
				} catch (ReflectiveOperationException | RuntimeException e) {
					throw new KcdError("Could not create " + type.getSimpleName() + ": " + e.getMessage(), 0);
				}
			}
		}
		throw new KcdError(type.getSimpleName() + " does not take these " + args.length + " argument(s)", 0)
			.withHint("constructors: " + (tried.isEmpty() ? "none public" : String.join(" | ", tried)));
	}

	private static String describe(Constructor<?> c) {
		StringBuilder sb = new StringBuilder("(");
		Parameter[] ps = c.getParameters();
		for (int i = 0; i < ps.length; i++) {
			sb.append(i > 0 ? ", " : "").append(ps[i].getType().getSimpleName());
		}
		return sb.append(')').toString();
	}

	private static List<Object> entries(List<PacketLog.Entry> list) {
		List<Object> out = new ArrayList<>();
		long t0 = PacketLog.startedAt();
		for (PacketLog.Entry e : list) {
			Map<Object, Object> m = new LinkedHashMap<>();
			m.put("id", e.id());
			m.put("class", e.type());
			m.put("direction", e.direction());
			m.put("time", (e.time() - t0) / 1000.0);
			out.add(m);
		}
		return out;
	}

	public static void register() {
		R.constants.put("packet", new PacketNs());
		String t = "packet_ns";
		R.typeDoc(t, "Raw network packets: packet.send(\"swing\", \"main_hand\"), packet.record(5), packet.stoprec(). Sending wrong packets can get you kicked.");
		R.method(t, "send(name:string, ...args:any)", "Sends a client-to-server packet. name is the class name without Serverbound/Packet (\"swing\", \"client_command\", \"chat_command\") or the full class name; args fill its constructor (numbers, text, true/false, vec3, enum names such as \"main_hand\").", (in, s, g) -> {
			Object[] raw = g;
			if (raw.length == 0 || !(raw[0] instanceof String name)) {
				throw new KcdError("packet.send needs the packet name first", 0);
			}
			Object[] args = new Object[raw.length - 1];
			if (args.length == 1 && raw[1] instanceof List<?> l) {
				args = l.toArray();
			} else {
				System.arraycopy(raw, 1, args, 0, args.length);
			}
			Packet<?> p = build(name, args);
			final Packet<?> packet = p;
			try {
				in.onClient(() -> {
					var conn = G.mc().getConnection();
					if (conn == null) {
						throw new KcdError("Not connected", 0);
					}
					conn.send(packet);
					return null;
				});
			} catch (KcdError e) {
				throw e;
			} catch (Exception e) {
				throw new KcdError("packet.send failed: " + e.getMessage(), 0);
			}
			return null;
		});
		R.method(t, "record(seconds?:number, direction?:string=\"both\") -> list", "Starts recording packets. With seconds it waits that long and returns the list; without it records until packet.stoprec(). direction: \"in\" (from the server), \"out\" (from you) or \"both\". Each entry is a map with id, class, direction, time.", (in, s, g) -> {
			String dir = g.length > 1 && g[1] instanceof String d ? d.toLowerCase(Locale.ROOT) : "both";
			PacketLog.start(!dir.equals("out"), !dir.equals("in"));
			if (g.length > 0 && g[0] instanceof Double secs) {
				in.waitSeconds(Math.max(0, secs));
				return entries(PacketLog.stop());
			}
			return null;
		});
		R.method(t, "stoprec() -> list", "Stops recording and returns what was recorded (an empty list if nothing was recording).", (in, s, g) -> entries(PacketLog.stop()));
		R.method(t, "is_recording() -> boolean", "True while packet.record is running.", (in, s, g) -> PacketLog.recording());
	}
}
