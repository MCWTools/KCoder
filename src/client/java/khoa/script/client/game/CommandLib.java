package khoa.script.client.game;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;

import khoa.script.client.Kcd;
import khoa.script.lang.Args;
import khoa.script.lang.Registry;
import khoa.script.lang.Session;

/**
 * Chat commands registered by scripts: {@code register_command("/abc")}, {@code register_suggestion("/abc", "def")} and the {@code on_command_run("/abc def"):} event.
 *
 * <p>Brigadier has no way to remove a node, so a node stays in the client dispatcher after its script stops; its {@code requires} gate answers false while no
 * script owns the name, which hides it from suggestions and makes it "unknown". A later script that registers the same name simply owns it again.
 */
public final class CommandLib {
	private static final Registry R = Registry.get();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]+");
	private static final Map<String, Cmd> COMMANDS = new ConcurrentHashMap<>();

	private static final class Cmd {
		final Session owner;
		final List<String> suggestions = new CopyOnWriteArrayList<>();

		Cmd(Session owner) {
			this.owner = owner;
		}
	}

	/** The requirement of every node made here; lets us recognise our own nodes (so a stale one is reusable and a foreign one is a conflict). */
	private record Gate(String name) implements Predicate<FabricClientCommandSource> {
		@Override
		public boolean test(FabricClientCommandSource source) {
			return COMMANDS.containsKey(name);
		}
	}

	private CommandLib() {
	}

	/** Re-adds the nodes of running scripts whenever Fabric builds a fresh client dispatcher (on joining a world). */
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
			for (String name : COMMANDS.keySet()) {
				if (dispatcher.getRoot().getChild(name) == null) {
					dispatcher.getRoot().addChild(build(name));
				}
			}
		});
	}

	private static LiteralCommandNode<FabricClientCommandSource> build(String name) {
		return ClientCommands.literal(name)
			.requires(new Gate(name))
			.executes(c -> run(name, ""))
			.then(ClientCommands.argument("args", StringArgumentType.greedyString())
				.suggests((c, b) -> {
					Cmd cmd = COMMANDS.get(name);
					return SharedSuggestionProvider.suggest(cmd == null ? List.<String>of() : cmd.suggestions, b);
				})
				.executes(c -> run(name, StringArgumentType.getString(c, "args"))))
			.build();
	}

	private static int run(String name, String args) {
		Cmd cmd = COMMANDS.get(name);
		if (cmd == null || cmd.owner.stopped) {
			return 0;
		}
		String text = args.isBlank() ? "/" + name : "/" + name + " " + args.strip();
		Map<String, Object> d = new HashMap<>();
		d.put("command", text);
		d.put("name", name);
		d.put("args", args.strip());
		Kcd.ENGINE.events.fire("on_command_run", d);
		return 1;
	}

	/** "/abc", "abc " and "ABC" all mean the command abc; null when it is not a valid single-word command name. */
	private static String nameOf(String spelled) {
		String n = spelled.strip();
		if (n.startsWith("/")) {
			n = n.substring(1);
		}
		return NAME.matcher(n).matches() ? n : null;
	}

	/** True when something other than a script command already uses this name (a Minecraft / server / other mod command). */
	private static boolean takenByGame(String name) {
		var active = ClientCommands.getActiveDispatcher();
		if (active != null) {
			CommandNode<FabricClientCommandSource> n = active.getRoot().getChild(name);
			if (n != null && !(n.getRequirement() instanceof Gate)) {
				return true;
			}
		}
		var conn = Minecraft.getInstance().getConnection();
		if (conn != null) {
			var server = conn.getCommands();
			CommandNode<ClientSuggestionProvider> n = server == null ? null : server.getRoot().getChild(name);
			return n != null;
		}
		return false;
	}

	private static void fail(Args a, String message) {
		a.in.engine.console.error(a.in.session, message);
	}

	public static void register() {
		String c = "command";
		R.def(c, "register_command(name:string) -> string", "Creates a chat command, e.g. cmd = register_command(\"/abc\"), and returns its name (\"/abc\"). Handle it with on_command_run(\"/abc\"):. The command is removed when the script stops. If the name is already used (by the game, a server, another mod or another script) it logs \"A command with that name already exist\" and returns nil - the script keeps running.", a -> {
			String name = nameOf(a.str(0));
			if (name == null) {
				fail(a, "register_command(): '" + a.str(0) + "' is not a valid command name (letters, digits, _ and - only, no spaces)");
				return null;
			}
			Cmd mine = COMMANDS.get(name);
			if (mine != null && !mine.owner.stopped || takenByGame(name)) {
				fail(a, "A command with that name already exist: /" + name);
				return null;
			}
			Cmd cmd = new Cmd(a.in.session);
			COMMANDS.put(name, cmd);
			var active = ClientCommands.getActiveDispatcher();
			if (active != null && active.getRoot().getChild(name) == null) {
				active.getRoot().addChild(build(name));
			}
			a.in.session.onStop.add(() -> {
				COMMANDS.remove(name, cmd);
				refresh();
			});
			refresh();
			return "/" + name;
		}).client().example("cmd = register_command(\"/greet\")\nregister_suggestion(cmd, \"world\")\non_command_run(\"/greet\"):\n    print(\"you typed\", command)");
		R.def(c, "register_suggestion(command:string, text:string) -> boolean", "Adds a word (or several words) that chat offers after your command: register_suggestion(\"/abc\", \"def\") suggests def after /abc . Call it again for more suggestions. Returns false (and logs an error) if the command was not registered by this script.", a -> {
			String name = nameOf(a.str(0));
			Cmd cmd = name == null ? null : COMMANDS.get(name);
			if (cmd == null || cmd.owner != a.in.session) {
				fail(a, "register_suggestion(): '" + a.str(0) + "' is not a command registered by this script");
				return false;
			}
			String text = a.str(1).strip();
			if (!text.isEmpty() && !cmd.suggestions.contains(text)) {
				cmd.suggestions.add(text);
				refresh();
			}
			return true;
		}).client();
		R.def(c, "unregister_command(command:string) -> boolean", "Removes a command this script registered (it is removed automatically when the script stops). False if it was not registered by this script.", a -> {
			String name = nameOf(a.str(0));
			Cmd cmd = name == null ? null : COMMANDS.get(name);
			if (cmd == null || cmd.owner != a.in.session) {
				return false;
			}
			COMMANDS.remove(name, cmd);
			refresh();
			return true;
		}).client();

		R.event("on_command_run", "A script command was run in chat. on_command_run(\"/abc\"): runs for /abc with any arguments; on_command_run(\"/abc def\"): runs when the text starts with /abc def. Gives command (the full text), name and args.", List.of("command"),
			Map.of("command", "string", "name", "string", "args", "string"), (f, d) -> {
				if (f.isEmpty()) {
					return true;
				}
				String want = normalize(String.valueOf(f.get(0)));
				String got = normalize(String.valueOf(d.get("command")));
				return got.equals(want) || got.startsWith(want + " ");
			}).skipIfBusy = false;
	}

	private static String normalize(String text) {
		String t = text.strip().replaceAll("\\s+", " ");
		return (t.startsWith("/") ? t.substring(1) : t).toLowerCase(Locale.ROOT);
	}

	private static void refresh() {
		var mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			ClientCommands.refreshCommandCompletions();
		}
	}
}
