package khoa.script.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import khoa.script.client.gui.HomeScreen;

public class KhoaScriptClient implements ClientModInitializer {
	private static boolean openGui;
	private static boolean openEditor;

	@Override
	public void onInitializeClient() {
		Kcd.init();
		khoa.script.lang.Session.modApi = khoa.script.client.game.ModApiBridge.INSTANCE;
		ScriptFolders.ensureExample();
		ScriptRuntime.registerApi();
		khoa.script.client.game.CommandLib.init();
		khoa.script.client.game.ChatPrompt.init();
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> khoa.script.client.ui.UiManager.installOverlay(screen));
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("khoascript", "ui"), (g, dt) -> khoa.script.client.ui.UiManager.renderHud(g));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ScriptRuntime.onTick(mc);
			if (openEditor) {
				openEditor = false;
				mc.gui.setScreen(new khoa.script.client.guiedit.GuiEditorScreen());
			}
			if (openGui) {
				openGui = false;
				mc.gui.setScreen(new HomeScreen());
			}
		});
		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
			if (!overlay) {
				ScriptRuntime.onChat(msg.getString());
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> khoa.script.client.game.ChatLog.onJoin(mc));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			khoa.script.client.game.ChatLog.onLeave(mc);
			khoa.script.client.guiedit.GuiStore.saveIfChanged();
		});
		ClientTickEvents.END_CLIENT_TICK.register(khoa.script.client.game.ChatLog::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			khoa.script.client.guiedit.GuiStore.saveIfChanged();
			ScriptFolders.stopAll();
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("kcoder")
			.executes(c -> {
				openGui = true;
				return 1;
			})
			.then(ClientCommands.literal("docs").executes(c -> {
				try {
					c.getSource().sendFeedback(Component.literal("Wrote " + DocsExport.write()));
				} catch (java.io.IOException e) {
					c.getSource().sendFeedback(Component.literal("Could not write the reference: " + e.getMessage()));
				}
				return 1;
			}))
			.then(ClientCommands.literal("import").executes(c -> {
				var src = c.getSource();
				ScriptPack.pickOpen(source -> src.sendFeedback(Component.literal(ScriptPack.importFrom(source).message())));
				return 1;
			}))
			.then(ClientCommands.literal("export")
				.then(ClientCommands.argument("folder", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptFolders.list(), b))
					.executes(c -> {
						String f = StringArgumentType.getString(c, "folder");
						var src = c.getSource();
						ScriptPack.pickSave(f, target -> src.sendFeedback(Component.literal(ScriptPack.export(f, target).message())));
						return 1;
					})))
			.then(ClientCommands.literal("gui").executes(c -> {
				openEditor = true;
				return 1;
			}))
			.then(ClientCommands.literal("run")
				.then(ClientCommands.argument("folder", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptFolders.list(), b))
					.executes(c -> {
						String f = StringArgumentType.getString(c, "folder");
						if (!ScriptFolders.list().contains(f)) {
							c.getSource().sendFeedback(Component.literal("No script folder named '" + f + "'"));
							return 0;
						}
						boolean ok = ScriptFolders.run(f);
						c.getSource().sendFeedback(Component.literal(ok ? "Running " + f : "Could not start " + f + " - see the log (/kcoder)"));
						return ok ? 1 : 0;
					})))
			.then(ClientCommands.literal("stop")
				.executes(c -> {
					ScriptFolders.stopAll();
					c.getSource().sendFeedback(Component.literal("Stopped all script folders"));
					return 1;
				})
				.then(ClientCommands.argument("folder", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(ScriptFolders.list(), b))
					.executes(c -> {
						ScriptFolders.stop(StringArgumentType.getString(c, "folder"));
						c.getSource().sendFeedback(Component.literal("Stopped"));
						return 1;
					})))
			.then(ClientCommands.literal("multiplayer")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal("Scripts may act on multiplayer servers: " + Kcd.getBool("allow_multiplayer", false)));
					return 1;
				})
				.then(ClientCommands.argument("allow", BoolArgumentType.bool())
					.executes(c -> {
						boolean v = BoolArgumentType.getBool(c, "allow");
						Kcd.set("allow_multiplayer", Boolean.toString(v));
						c.getSource().sendFeedback(Component.literal("Scripts may act (click, aim, chat) on multiplayer servers: " + v));
						return 1;
					})))));
	}

	static Class<?> keep() {
		return FabricClientCommandSource.class;
	}
}
