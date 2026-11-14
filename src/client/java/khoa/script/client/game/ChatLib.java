package khoa.script.client.game;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

import khoa.script.client.mixin.ChatComponentAccess;
import khoa.script.client.mixin.KeyMappingAccess;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;

/** Chat window, typing, synthetic key presses, command suggestions and the GUI mouse position. */
public final class ChatLib {
	private static final Registry R = Registry.get();
	private static final int SUGGESTION_CAP = 10_000;

	private ChatLib() {
	}

	private static void character(int codePoint) {
		var mc = G.mc();
		mc.keyboardHandler.charTyped(mc.getWindow().handle(), new CharacterEvent(codePoint));
	}

	private static void key(int code, int action, int mods) {
		var mc = G.mc();
		mc.keyboardHandler.keyPress(mc.getWindow().handle(), action, new KeyEvent(code, 0, mods));
	}

	private static KeyMapping keybind(String name) {
		KeyMapping km = KeyMapping.get(name);
		if (km != null) {
			return km;
		}
		for (KeyMapping k : G.mc().options.keyMappings) {
			if (k.getName().equalsIgnoreCase(name)) {
				return k;
			}
		}
		throw new KcdError("No key binding named '" + name + "'", 0).withHint("list_keybinds() shows every name, mods included");
	}

	public static void register() {
		String c = "chat";
		khoa.script.lib.EnumLib.extraGroup("key", "A keyboard key for press_key and on_key_press.", Keys.allNames());

		R.def(c, "open_chat_ui(text?:string=\"\")", "Opens the chat box, optionally with text already typed in. Use \"/\" to start a command.", a -> {
			G.mc().gui.setScreen(new ChatScreen(a.str(0, ""), false));
			return null;
		}).client();
		R.def(c, "type_text(text:string)", "Types text as if you pressed the keys: into the chat box, a sign, an anvil, a textbox of another mod ... whatever has the keyboard. With nothing open it still tries (a mod may listen for keys).", a -> {
			var screen = G.mc().gui.screen();
			String text = a.text(0);
			if (screen instanceof ChatScreen chat) {
				chat.insertText(text, false);
				return null;
			}
			text.codePoints().forEach(ChatLib::character);
			return null;
		}).client().example("open_chat_ui()\ntype_text(\"/time set day\")\npress_key(enum.key.ENTER)");
		R.def(c, "press_key(key:key, seconds?:number=0.05, ctrl?:boolean=false, shift?:boolean=false, alt?:boolean=false)",
			"Presses a key like a real keyboard, holds it for `seconds` and lets go. Minecraft's own controls and other mods' key bindings react to it. Waits while the key is held.", a -> {
				int code = Keys.keyCode(a.str(0));
				if (Keys.isMouseName(a.str(0))) {
					throw new KcdError("press_key is for keyboard keys; use left_click() / right_click() / hold_left() for the mouse", 0);
				}
				int mods = (a.bool(2, false) ? 2 : 0) | (a.bool(3, false) ? 1 : 0) | (a.bool(4, false) ? 4 : 0);
				a.in.onClient(() -> {
					key(code, 1, mods);
					return null;
				});
				a.in.waitSeconds(Math.max(0.0, a.num(1, 0.05)));
				a.in.onClient(() -> {
					key(code, 0, mods);
					return null;
				});
				return null;
			}).suspends().perm("input").example("press_key(\"E\")                  # opens the inventory\npress_key(enum.key.ENTER)\npress_key(\"G\", 1.5)  # holds G for 1.5 seconds");
		R.def(c, "list_keybinds() -> list<string>", "Names of every key binding of the game and of other mods, such as key.inventory.", a -> {
			List<Object> out = new ArrayList<>();
			for (KeyMapping k : G.mc().options.keyMappings) {
				out.add(k.getName());
			}
			return out;
		}).client();
		R.def(c, "press_keybind(name:string, seconds?:number=0.05)", "Triggers a key binding by name, whatever key it is bound to: press_keybind(\"key.inventory\"). Works for other mods' bindings too, which is how you open their screens.", a -> {
			String name = a.str(0);
			InputConstants.Key bound = a.in.onClient(() -> {
				KeyMapping km = keybind(name);
				if (km.isUnbound()) {
					throw new KcdError("'" + name + "' has no key assigned", 0).withHint("bind it in Options > Controls first");
				}
				InputConstants.Key k = ((KeyMappingAccess) km).khoa$getKey();
				KeyMapping.set(k, true);
				KeyMapping.click(k);
				return k;
			});
			a.in.waitSeconds(Math.max(0.0, a.num(1, 0.05)));
			a.in.onClient(() -> {
				KeyMapping.set(bound, false);
				return null;
			});
			return null;
		}).suspends().perm("input");
		R.def(c, "run_command(command:string)", "Runs a command, with or without the leading slash. Client commands of other mods work as well as server commands.", a -> {
			if (G.mc().getConnection() == null) {
				throw new KcdError("Not connected", 0);
			}
			String cmd = a.str(0).trim();
			G.mc().getConnection().sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
			return null;
		}).client().perm("chat");
		R.alias("action_bar", "actionbar");
		R.alias("client_chat", "chat");
		R.def(c, "get_chat(limit?:number=100) -> list<string>", "The most recent chat lines as text, oldest first. Includes your own messages, system lines and what client_chat() printed. Works with chat mods; use set_chat_limit to keep more.", a -> {
			List<String> all = ChatLog.snapshot();
			int n = Math.max(0, Math.min(all.size(), a.intv(0, 100)));
			return new ArrayList<Object>(all.subList(all.size() - n, all.size()));
		});
		R.def(c, "delete_chat(text:string, case_sensitive?:boolean=false) -> number", "Removes every chat line containing the text. Returns how many were removed.", a -> {
			String text = a.str(0);
			boolean ci = !a.bool(1, false);
			int removed = ChatLog.remove(text, ci);
			a.in.onClient(() -> {
				ChatComponent chat = G.mc().gui.hud.getChat();
				List<GuiMessage> all = ((ChatComponentAccess) chat).khoa$allMessages();
				all.removeIf(m -> ci ? m.content().getString().toLowerCase().contains(text.toLowerCase()) : m.content().getString().contains(text));
				chat.rescaleChat();
				return null;
			});
			return (double) removed;
		});
		R.def(c, "clear_chat()", "Clears the chat window (what you typed before stays in the up-arrow history).", a -> {
			ChatLog.clear();
			G.mc().gui.hud.getChat().clearMessages(false);
			return null;
		}).client();
		R.def(c, "set_chat_limit(lines:number) -> number", "How many lines get_chat() can look back (2000 by default, up to 100000). This is KCoder's own copy; how many lines the chat window keeps and shows again after rejoin is the KhoaTools Better Chat setting.", a -> {
			ChatLog.setLimit(a.intv(0));
			return (double) ChatLog.limit();
		});
		R.def(c, "get_suggestion(text:string, limit?:number=10000) -> list<string>",
			"What the game would suggest for a command: get_suggestion(\"/gamemode \") lists the modes, get_suggestion(\"/give @s diamond_\") lists the matching items. Works for every depth of arguments. At most `limit` (max 10000) results.", a -> {
				var conn = G.mc().getConnection();
				if (conn == null) {
					throw new KcdError("Not connected", 0);
				}
				String raw = a.str(0);
				String text = raw.startsWith("/") ? raw.substring(1) : raw;
				int limit = Math.max(1, Math.min(SUGGESTION_CAP, a.intv(1, SUGGESTION_CAP)));
				CompletableFuture<Suggestions> future = a.in.onClient(() -> {
					var dispatcher = conn.getCommands();
					return dispatcher.getCompletionSuggestions(dispatcher.parse(text, conn.getSuggestionsProvider()), text.length());
				});
				long end = System.currentTimeMillis() + 3000;
				while (!future.isDone() && System.currentTimeMillis() < end) {
					a.in.waitSeconds(0.02);
				}
				if (!future.isDone()) {
					throw new KcdError("The server did not answer the suggestion request in time", 0);
				}
				Set<String> out = new LinkedHashSet<>();
				for (Suggestion s : future.join().getList()) {
					out.add(s.getText());
					if (out.size() >= limit) {
						break;
					}
				}
				return new ArrayList<Object>(out);
			}).suspends().example("for mode in get_suggestion(\"/gamemode \"):\n    print(mode)");
		R.def(c, "move_cursor(x:number, y:number, scaled?:boolean=true)", "Moves the mouse pointer inside the game window. x and y are GUI pixels (like get_mouse_position) or real window pixels when scaled is false. Works while a menu is open or after ui_cursor(true).", a -> {
			double x = a.num(0);
			double y = a.num(1);
			var w = G.mc().getWindow();
			double k = a.bool(2, true) ? w.getGuiScale() : 1.0;
			org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(w.handle(), (float) (x * k), (float) (y * k));
			return null;
		}).client().perm("input");
	}
}
