package khoa.script.client.game;

import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.registries.BuiltInRegistries;

import khoa.script.client.ScriptRuntime;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;

/** Client information and messages shown to the player. */
public final class ClientLib {
	private static final Registry R = Registry.get();

	private ClientLib() {
	}

	public static void register() {
		String c = "client";
		R.def(c, "get_game_version() -> string", "Minecraft version, e.g. 26.3.", a -> SharedConstants.getCurrentVersion().name());
		R.def(c, "get_client_brand() -> string", "Client brand: fabric.", a -> "fabric");
		R.def(c, "get_screen() -> string", "Class name of the open menu, or nil when playing.", a -> {
			var s = G.mc().gui.screen();
			return s == null ? null : s.getClass().getSimpleName();
		}).client();
		R.def(c, "is_screen_open() -> boolean", "True if a menu is open.", a -> G.mc().gui.screen() != null).client();
		R.def(c, "get_scaled_width() -> number", "Window width in GUI pixels.", a -> (double) G.mc().getWindow().getGuiScaledWidth()).client();
		R.def(c, "get_scaled_height() -> number", "Window height in GUI pixels.", a -> (double) G.mc().getWindow().getGuiScaledHeight()).client();
		R.def(c, "get_window_width() -> number", "Window width in real pixels.", a -> (double) G.mc().getWindow().getWidth()).client();
		R.def(c, "get_window_height() -> number", "Window height in real pixels.", a -> (double) G.mc().getWindow().getHeight()).client();
		R.def(c, "get_fps() -> number", "Frames per second.", a -> (double) G.mc().getFps()).client();
		R.def(c, "get_server_address() -> string", "Address of the server, \"singleplayer\" or nil when not connected.", a -> {
			var mc = G.mc();
			if (mc.getCurrentServer() != null) {
				return mc.getCurrentServer().ip;
			}
			return mc.hasSingleplayerServer() ? "singleplayer" : null;
		}).client();
		R.def(c, "get_connection_status() -> string", "singleplayer, multiplayer or disconnected.", a -> {
			var mc = G.mc();
			if (mc.level == null) {
				return "disconnected";
			}
			return mc.hasSingleplayerServer() ? "singleplayer" : "multiplayer";
		}).client();
		R.def(c, "get_mouse_sensitivity() -> number", "Mouse sensitivity setting (0-1).", a -> G.mc().options.sensitivity().get()).client();

		String o = "output";
		R.def(o, "chat(...message:any)", "Shows a message in YOUR chat window only (nobody else sees it).", a -> {
			StringBuilder sb = new StringBuilder();
			for (Object x : a.list(0)) {
				sb.append(sb.length() > 0 ? " " : "").append(khoa.script.lang.Val.str(x));
			}
			G.me().sendSystemMessage(Component.literal(sb.toString()));
			return null;
		}).client();
		R.def(o, "actionbar(message:any)", "Shows text above your hotbar.", a -> {
			G.mc().gui.hud.setOverlayMessage(Component.literal(a.text(0)), false);
			return null;
		}).client();
		R.def(o, "title(title:any, subtitle?:any)", "Shows a big title on screen.", a -> {
			G.mc().gui.hud.setTitle(Component.literal(a.text(0)));
			if (a.has(1)) {
				G.mc().gui.hud.setSubtitle(Component.literal(a.text(1)));
			}
			return null;
		}).client();
		R.def(o, "notify(title:any, message?:any)", "Shows a toast popup in the corner.", a -> {
			SystemToast.add(G.mc().gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(a.text(0)), Component.literal(a.has(1) ? a.text(1) : ""));
			return null;
		}).client();
		R.def(o, "sound(id:string, volume?:number=1, pitch?:number=1)", "Plays a sound, e.g. sound(minecraft:entity.experience_orb.pickup).", a -> {
			Optional<SoundEvent> ev = BuiltInRegistries.SOUND_EVENT.getOptional(G.id(a.str(0)));
			if (ev.isEmpty()) {
				throw new KcdError("Unknown sound '" + a.str(0) + "'", 0);
			}
			G.mc().getSoundManager().play(SimpleSoundInstance.forUI(ev.get(), (float) a.num(2, 1), (float) a.num(1, 1)));
			return null;
		}).client();
		R.def(o, "send_chat(message:string)", "Sends a chat message to the server (everyone sees it).", a -> {
			if (G.mc().getConnection() == null) {
				throw new KcdError("Not connected", 0);
			}
			G.mc().getConnection().sendChat(a.str(0));
			return null;
		}).client().perm("chat");
		R.def(o, "send_command(command:string)", "Runs a command as you, without the leading slash: send_command(\"time set day\").", a -> {
			if (G.mc().getConnection() == null) {
				throw new KcdError("Not connected", 0);
			}
			String cmd = a.str(0);
			G.mc().getConnection().sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
			return null;
		}).client().perm("chat");
	}

	static Class<?> keep() {
		return ScriptRuntime.class;
	}
}
