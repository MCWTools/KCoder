package khoa.script.client.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import khoa.script.KhoaScript;

/**
 * KCoder's own chat history: plain text, kept per server (or singleplayer world) in {@code <game>/kcoder/chat/<server>.txt} and loaded again on join,
 * so get_chat() sees old lines even when no chat mod is installed. When KhoaTools Better Chat is installed it replays ITS cache into the chat right after
 * joining; those replayed lines are matched against our own history and not stored twice.
 */
public final class ChatLog {
	public static final int DEFAULT_LIMIT = 2000;
	private static final long REPLAY_WINDOW_MS = 5000;
	private static final Deque<String> LINES = new ArrayDeque<>();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "kcoder-chat-io");
		t.setDaemon(true);
		return t;
	});
	private static volatile int limit = DEFAULT_LIMIT;
	private static volatile boolean dirty;
	private static String key;
	/** Lines loaded from disk that a chat mod may replay right now, and how far the replay has got. */
	private static List<String> expected = List.of();
	private static int expectedPos;
	private static long replayUntil;
	private static int ticks;

	private ChatLog() {
	}

	/** How many lines get_chat() can look back. */
	public static int limit() {
		return limit;
	}

	public static void setLimit(int n) {
		limit = Math.max(100, Math.min(100_000, n));
		synchronized (LINES) {
			while (LINES.size() > limit) {
				LINES.removeFirst();
			}
		}
	}

	private static boolean replayedByChatMod(String line) {
		if (System.currentTimeMillis() > replayUntil || expected.isEmpty()) {
			return false;
		}
		for (int i = expectedPos; i < expected.size(); i++) {
			if (expected.get(i).equals(line)) {
				expectedPos = i + 1;
				return true;
			}
		}
		return false;
	}

	public static void add(String line) {
		synchronized (LINES) {
			if (replayedByChatMod(line)) {
				return;
			}
			LINES.addLast(line);
			while (LINES.size() > limit) {
				LINES.removeFirst();
			}
			dirty = true;
		}
	}

	public static List<String> snapshot() {
		synchronized (LINES) {
			return new ArrayList<>(LINES);
		}
	}

	public static void clear() {
		synchronized (LINES) {
			LINES.clear();
			dirty = true;
		}
	}

	public static int remove(String text, boolean ignoreCase) {
		int n = 0;
		String needle = ignoreCase ? text.toLowerCase() : text;
		synchronized (LINES) {
			var it = LINES.iterator();
			while (it.hasNext()) {
				String l = it.next();
				if ((ignoreCase ? l.toLowerCase() : l).contains(needle)) {
					it.remove();
					n++;
				}
			}
			if (n > 0) {
				dirty = true;
			}
		}
		return n;
	}

	// ------------------------------------------------------------ persistence
	private static String serverKey(Minecraft mc) {
		String raw;
		var server = mc.getCurrentServer();
		if (server != null) {
			raw = server.ip;
		} else if (mc.getSingleplayerServer() != null) {
			raw = "singleplayer_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else {
			raw = "unknown";
		}
		return raw.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static Path file(Minecraft mc, String k) {
		return mc.gameDirectory.toPath().resolve("kcoder").resolve("chat").resolve(k + ".txt");
	}

	private static String encode(String s) {
		return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
	}

	private static String decode(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				sb.append(n == 'n' ? '\n' : n);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Joined a server or world: load the saved history. Lines a chat mod already replayed before this point are matched against it, not duplicated. */
	public static void onJoin(Minecraft mc) {
		key = serverKey(mc);
		List<String> saved = new ArrayList<>();
		Path f = file(mc, key);
		if (Files.exists(f)) {
			try {
				for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
					saved.add(decode(l));
				}
			} catch (IOException e) {
				KhoaScript.LOGGER.warn("Cannot read chat history {}", f, e);
			}
		}
		synchronized (LINES) {
			List<String> already = new ArrayList<>(LINES);
			LINES.clear();
			LINES.addAll(saved);
			// lines replayed by a chat mod before we loaded: keep only the ones we did not know
			int pos = 0;
			for (String l : already) {
				int q = saved.subList(pos, saved.size()).indexOf(l);
				if (q >= 0) {
					pos += q + 1;
				} else {
					LINES.addLast(l);
				}
			}
			while (LINES.size() > limit) {
				LINES.removeFirst();
			}
			// a chat mod (KhoaTools Better Chat) replays its own cache for a few seconds after joining
			expected = FabricLoader.getInstance().isModLoaded("khoatools") ? saved.subList(Math.min(pos, saved.size()), saved.size()) : List.of();
			expectedPos = 0;
			replayUntil = System.currentTimeMillis() + REPLAY_WINDOW_MS;
			dirty = !already.isEmpty();
		}
	}

	/** Left the server: write the history and start the next session empty. */
	public static void onLeave(Minecraft mc) {
		save(mc);
		synchronized (LINES) {
			LINES.clear();
			expected = List.of();
			dirty = false;
		}
		key = null;
	}

	/** Call every tick: writes the file about every 5 seconds while something changed. */
	public static void tick(Minecraft mc) {
		if (dirty && key != null && ++ticks >= 100) {
			ticks = 0;
			save(mc);
		}
	}

	private static void save(Minecraft mc) {
		String k = key;
		if (k == null || !dirty) {
			return;
		}
		dirty = false;
		List<String> lines = snapshot();
		Path f = file(mc, k);
		IO.execute(() -> {
			try {
				Files.createDirectories(f.getParent());
				List<String> out = new ArrayList<>(lines.size());
				for (String l : lines) {
					out.add(encode(l));
				}
				Files.write(f, out, StandardCharsets.UTF_8);
			} catch (IOException e) {
				KhoaScript.LOGGER.warn("Cannot write chat history {}", f, e);
			}
		});
	}
}
