package khoa.script.client.game;

import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import khoa.script.lang.Registry;
import khoa.script.lang.Session;

/**
 * {@code chat_prompt(question)}: prints a question in chat and swallows the next thing the player types (it is never sent to the server),
 * returning it to the script. Typing a command ("/...") cancels the prompt instead and the command runs as usual.
 */
public final class ChatPrompt {
	private static final Registry R = Registry.get();
	private static final AtomicReference<Waiting> CURRENT = new AtomicReference<>();

	private static final class Waiting {
		final Session owner;
		volatile String answer;

		Waiting(Session owner) {
			this.owner = owner;
		}
	}

	private ChatPrompt() {
	}

	public static void init() {
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			Waiting w = CURRENT.get();
			if (w == null || w.answer != null) {
				return true;
			}
			w.answer = message;
			return false;
		});
	}

	public static void register() {
		R.def("chat", "chat_prompt(question:string, timeout?:number=60) -> string",
			"Shows a question in chat and waits for the player's next chat line, which is NOT sent to the server. Returns the typed text, or nil if the time ran out. Good for \"Enter the amount:\" style input.", a -> {
				String question = a.str(0);
				double timeout = a.has(1) ? a.num(1) : 60;
				Waiting w = new Waiting(a.in.session);
				if (!CURRENT.compareAndSet(null, w)) {
					throw new khoa.script.lang.KcdError("chat_prompt(): another prompt is already waiting for an answer", 0);
				}
				Runnable cleanup = () -> CURRENT.compareAndSet(w, null);
				a.in.session.onStop.add(cleanup);
				try {
					a.in.onClient(() -> {
						G.me().sendSystemMessage(net.minecraft.network.chat.Component.literal(question));
						return null;
					});
					long deadline = a.in.engine.scheduler.now() + (long) (timeout * 1e9);
					while (w.answer == null && a.in.engine.scheduler.now() < deadline) {
						a.in.waitFrames(1);
					}
					return w.answer;
				} finally {
					cleanup.run();
					a.in.session.onStop.remove(cleanup);
				}
			}).suspends().client().example("name = chat_prompt(\"Enter the amount:\")");

		R.def("chat", "run_client_command(command:string) -> boolean",
			"Runs a client-side mod command (for example a Fabric client command such as \"khoatoolsclient locatorcoord Steve\"), with or without the leading slash. Returns false if no client command matched; in that case nothing is sent. Its chat feedback shows in chat and in get_chat().", a -> {
				var conn = G.mc().getConnection();
				if (conn == null) {
					throw new khoa.script.lang.KcdError("Not connected", 0);
				}
				String cmd = a.str(0).strip();
				if (cmd.startsWith("/")) {
					cmd = cmd.substring(1);
				}
				var source = (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource) conn.getSuggestionsProvider();
				return net.fabricmc.fabric.impl.command.client.ClientCommandInternals.executeCommand(cmd, source, source);
			}).client().perm("chat").example("run_client_command(\"khoatoolsclient locatorcoord Steve\")");
	}
}
