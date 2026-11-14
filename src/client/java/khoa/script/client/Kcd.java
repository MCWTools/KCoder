package khoa.script.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import khoa.script.KhoaScript;
import khoa.script.lang.Engine;
import khoa.script.lang.KcdError;

/** Client-side singleton: the engine, the workspace folder and the settings. */
public final class Kcd {
	public static final Engine ENGINE = new Engine();
	public static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("kcd");
	/** kcoder/output/<script folder>/ is where write_output / export_txt / export_json put their files. */
	public static final Path OUTPUT = FabricLoader.getInstance().getGameDir().resolve("kcoder").resolve("output");
	private static final Path CONFIG = ROOT.resolve("config.properties");
	private static final Properties PROPS = new Properties();
	private static boolean loaded;

	private Kcd() {
	}

	public static synchronized void init() {
		try {
			Files.createDirectories(ROOT);
		} catch (IOException e) {
			KhoaScript.LOGGER.error("Cannot create {}", ROOT, e);
		}
		if (Files.exists(CONFIG)) {
			try (InputStream in = Files.newInputStream(CONFIG)) {
				PROPS.load(in);
			} catch (IOException e) {
				KhoaScript.LOGGER.warn("Cannot read {}", CONFIG, e);
			}
		}
		loaded = true;
		ENGINE.permissions = Kcd::checkPermission;
	}

	public static synchronized String get(String key, String def) {
		return PROPS.getProperty(key, def);
	}

	public static synchronized boolean getBool(String key, boolean def) {
		return Boolean.parseBoolean(PROPS.getProperty(key, Boolean.toString(def)));
	}

	public static synchronized void set(String key, String value) {
		PROPS.setProperty(key, value);
		if (!loaded) {
			return;
		}
		try (OutputStream out = Files.newOutputStream(CONFIG)) {
			PROPS.store(out, "KCoder settings");
		} catch (IOException e) {
			KhoaScript.LOGGER.warn("Cannot save {}", CONFIG, e);
		}
	}

	/** True when scripts may act (click, aim, chat) in the current world. Always true in singleplayer / on your own LAN host. */
	public static boolean actionsAllowed() {
		Minecraft mc = Minecraft.getInstance();
		return mc.hasSingleplayerServer() || getBool("allow_multiplayer", false);
	}

	private static void checkPermission(String permission, String function) {
		switch (permission) {
			case "action", "look", "chat" -> {
				if (!actionsAllowed()) {
					throw new KcdError(function + "() is blocked on multiplayer servers", 0)
						.withHint("run /kcoder multiplayer true to allow scripts to act on servers");
				}
			}
			default -> {
			}
		}
	}
}
