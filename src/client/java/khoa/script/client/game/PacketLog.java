package khoa.script.client.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.protocol.Packet;

/** Packets seen while packet.record() is running (netty threads write, the script reads). */
public final class PacketLog {
	public record Entry(long time, String direction, String id, String type) {
	}

	public static final int MAX = 10_000;
	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static volatile boolean recording;
	private static volatile boolean wantIn = true;
	private static volatile boolean wantOut = true;
	private static long startedAt;

	private PacketLog() {
	}

	public static boolean recording() {
		return recording;
	}

	public static void start(boolean in, boolean out) {
		synchronized (ENTRIES) {
			ENTRIES.clear();
			startedAt = System.currentTimeMillis();
			wantIn = in;
			wantOut = out;
			recording = true;
		}
	}

	public static List<Entry> stop() {
		synchronized (ENTRIES) {
			recording = false;
			List<Entry> out = new ArrayList<>(ENTRIES);
			ENTRIES.clear();
			return out;
		}
	}

	public static long startedAt() {
		return startedAt;
	}

	public static void add(String direction, Packet<?> packet) {
		if (!recording || direction.equals("in") && !wantIn || direction.equals("out") && !wantOut) {
			return;
		}
		String id;
		try {
			id = packet.type().id().toString();
		} catch (RuntimeException e) {
			id = packet.getClass().getSimpleName();
		}
		synchronized (ENTRIES) {
			if (ENTRIES.size() < MAX) {
				ENTRIES.add(new Entry(System.currentTimeMillis(), direction, id, packet.getClass().getSimpleName()));
			}
		}
	}
}
