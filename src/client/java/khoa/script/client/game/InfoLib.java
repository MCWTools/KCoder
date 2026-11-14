package khoa.script.client.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;

import khoa.script.client.Kcd;
import khoa.script.client.gui.Ui;
import khoa.script.lang.KcdError;
import khoa.script.lib.JsonLib;
import khoa.script.client.ui.Colors;
import khoa.script.lang.Registry;

/** Read-only game info, clipboard and small color / text helpers. */
public final class InfoLib {
	private static final Registry R = Registry.get();

	private InfoLib() {
	}

	private static Map<Object, Object> store;

	private static Path storeFile() {
		return Kcd.ROOT.resolve("saved_data.json");
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> store() {
		if (store == null) {
			store = new LinkedHashMap<>();
			Path f = storeFile();
			try {
				if (Files.exists(f) && JsonLib.parse(Files.readString(f)) instanceof Map<?, ?> m) {
					store.putAll((Map<Object, Object>) m);
				}
			} catch (IOException | KcdError e) {
				// an unreadable file is treated as empty and replaced by the next save
			}
		}
		return store;
	}

	private static void flush() {
		Path f = storeFile();
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, JsonLib.stringify(store(), true));
		} catch (IOException e) {
			throw new KcdError("Could not save data: " + e.getMessage(), 0);
		}
	}

	public static void register() {
		registerExtras();
		String c = "info";
		R.def(c, "get_effects() -> list<map>", "Potion effects on you: id, amplifier (0 = level I), seconds left.", a -> {
			List<Object> out = new ArrayList<>();
			for (MobEffectInstance e : G.me().getActiveEffects()) {
				Map<Object, Object> m = new LinkedHashMap<>();
				m.put("id", e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"));
				m.put("amplifier", (double) e.getAmplifier());
				m.put("seconds", e.getDuration() / 20.0);
				out.add(m);
			}
			return out;
		}).client();
		R.def(c, "get_container_items() -> list<item>", "Every slot of the menu you have open (chest, furnace, inventory ...), in slot order.", a -> {
			List<Object> out = new ArrayList<>();
			int i = 0;
			for (Slot s : G.me().containerMenu.slots) {
				out.add(new ItemRef(s.getItem().copy(), i++));
			}
			return out;
		}).client();
		R.def(c, "get_container() -> map", "Describes the menu you have open: title, type (such as minecraft:generic_9x3, nil for your own inventory), size (slots of the container itself), items (only the container's slots, in order), player_items (your inventory part), empty (no container slot holds an item). nil when no menu is open.", a -> {
			var menu = G.me().containerMenu;
			if (G.mc().gui.screen() == null || menu == null) {
				return null;
			}
			Map<Object, Object> out = new LinkedHashMap<>();
			var screen = G.mc().gui.screen();
			out.put("title", screen.getTitle().getString());
			String type = null;
			try {
				var key = net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType());
				type = key == null ? null : key.toString();
			} catch (UnsupportedOperationException e) {
				// the player's own inventory menu has no type
			}
			out.put("type", type);
			List<Object> box = new ArrayList<>();
			List<Object> mine = new ArrayList<>();
			boolean empty = true;
			int i = 0;
			for (Slot sl : menu.slots) {
				ItemRef ref = new ItemRef(sl.getItem().copy(), i++);
				if (sl.container == G.me().getInventory()) {
					mine.add(ref);
				} else {
					box.add(ref);
					empty &= sl.getItem().isEmpty();
				}
			}
			out.put("size", (double) box.size());
			out.put("items", box);
			out.put("player_items", mine);
			out.put("empty", empty);
			return out;
		}).client().example("c = get_container()\nif c != nil:\n    print(c.title, c.size)\n    for it in c.items:\n        if it.count > 0:\n            print(it.id, it.count)");
		R.def(c, "get_container_title() -> string", "Title of the open menu (such as Chest or Large Chest), or nil.", a -> G.mc().gui.screen() == null ? null : G.mc().gui.screen().getTitle().getString()).client();
		R.def(c, "get_entity_vehicle(entity:entity) -> entity", "What the entity is riding, or nil.", a -> {
			if (a.obj(0) == null) {
				return null;
			}
			Entity v = G.entity(a, 0).getVehicle();
			return v == null ? null : new EntityRef(v);
		}).client();
		R.def(c, "get_entity_passengers(entity:entity) -> list<entity>", "Everything riding the entity.", a -> {
			List<Object> out = new ArrayList<>();
			if (a.obj(0) != null) {
				for (Entity p : G.entity(a, 0).getPassengers()) {
					out.add(new EntityRef(p));
				}
			}
			return out;
		}).client();
		R.def(c, "get_clipboard() -> string", "Text in the system clipboard.", a -> G.mc().keyboardHandler.getClipboard()).client();
		R.def(c, "set_clipboard(text:string)", "Copies text to the system clipboard.", a -> {
			G.mc().keyboardHandler.setClipboard(a.text(0));
			return null;
		}).client();
		R.def(c, "get_text_width(text:string) -> number", "Width of a text in GUI pixels.", a -> (double) Ui.font().width(a.text(0))).client();
		R.def(c, "get_text_height() -> number", "Height of one line of text in GUI pixels.", a -> (double) Ui.font().lineHeight).client();
		R.def(c, "save_data(key:string, value:any)", "Remembers a value on disk (kcd/saved_data.json), also after the game restarts. Numbers, strings, booleans, lists and maps work.", a -> {
			store().put(a.str(0), JsonLib.parse(JsonLib.stringify(a.obj(1), false)));
			flush();
			return null;
		}).example("save_data(\"kills\", 12)\nprint(load_data(\"kills\", 0))");
		R.def(c, "load_data(key:string, default?:any) -> any", "A value stored with save_data, or the default (nil) if there is none.", a -> store().containsKey(a.str(0)) ? store().get(a.str(0)) : a.obj(1));
		R.def(c, "delete_data(key:string)", "Forgets a saved value.", a -> {
			if (store().remove(a.str(0)) != null) {
				flush();
			}
			return null;
		});
		R.def(c, "list_data_keys() -> list<string>", "Every key stored with save_data.", a -> new ArrayList<Object>(store().keySet()));
		R.def(c, "get_sign_text(pos:vec3, back?:boolean) -> list<string>", "The four lines of a sign (front, or back with back=true); nil if there is no sign.", a -> {
			BlockEntity be = G.level().getBlockEntity(G.bp(a.vec3(0)));
			if (!(be instanceof SignBlockEntity sign)) {
				return null;
			}
			List<Object> out = new ArrayList<>();
			for (var line : sign.getText(a.bool(1, false) ? SignTextSlot.BACK : SignTextSlot.FRONT).getMessages(false)) {
				out.add(line.getString());
			}
			return out;
		}).client();
		R.def(c, "get_scoreboard_title() -> string", "Title of the sidebar scoreboard, or nil.", a -> {
			Objective o = G.level().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
			return o == null ? null : o.getDisplayName().getString();
		}).client();
		R.def(c, "get_scoreboard_lines() -> list<map>", "The sidebar scoreboard: a list of maps with name and score, highest score first.", a -> {
			Scoreboard sb = G.level().getScoreboard();
			Objective o = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
			List<Object> out = new ArrayList<>();
			if (o == null) {
				return out;
			}
			List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(o));
			entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
			for (PlayerScoreEntry e : entries) {
				if (!e.isHidden()) {
					Map<Object, Object> m = new LinkedHashMap<>();
					m.put("name", e.ownerName().getString());
					m.put("score", (double) e.value());
					out.add(m);
				}
			}
			return out;
		}).client();
		R.def("math", "rgb(r:number, g:number, b:number, a?:number=255) -> color", "A color from 0-255 channels.", a -> (double) (Colors.rgb(a.num(0), a.num(1), a.num(2), a.num(3, 255)) & 0xFFFFFFFFL));
		R.def("math", "hsv(h:number, s:number, v:number, a?:number=255) -> color", "A color from hue 0-360, saturation 0-1, value 0-1.", a -> (double) (Colors.hsv(a.num(0), a.num(1), a.num(2), a.num(3, 255)) & 0xFFFFFFFFL));
		R.def("math", "lerp_color(a:color, b:color, t:number) -> color", "Blend two colors (\"#RRGGBB\", names or numbers).", a -> (double) (Colors.lerp(Colors.parse(a.obj(0), 0xFF000000), Colors.parse(a.obj(1), 0xFF000000), Math.max(0, Math.min(1, a.num(2)))) & 0xFFFFFFFFL));
		R.def("string", "time_format(seconds:number) -> string", "Seconds as m:ss or h:mm:ss.", a -> {
			long s = Math.max(0, Math.round(a.num(0)));
			return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
		});
	}

	private static java.lang.reflect.Field bossField;

	/** Boss bars currently shown. The game keeps them in a private map, read through reflection. */
	private static List<Object> bossbars() {
		List<Object> out = new ArrayList<>();
		try {
			var overlay = G.mc().gui.hud.getBossOverlay();
			if (bossField == null) {
				bossField = overlay.getClass().getDeclaredField("events");
				bossField.setAccessible(true);
			}
			for (Object o : ((Map<?, ?>) bossField.get(overlay)).values()) {
				var b = (net.minecraft.world.BossEvent) o;
				Map<Object, Object> m = new LinkedHashMap<>();
				m.put("id", b.getId().toString());
				m.put("name", b.getName().getString());
				m.put("progress", (double) b.getProgress());
				m.put("color", b.getColor().getName());
				m.put("style", b.getOverlay().getSerializedName());
				m.put("darkens_sky", b.shouldDarkenScreen());
				out.add(m);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new KcdError("Could not read boss bars: " + e, 0);
		}
		return out;
	}

	private static void registerExtras() {
		String c = "info";
		R.def(c, "get_bossbars() -> list<map>", "Boss bars on screen. Each map: id, name, progress (0-1), color, style, darkens_sky.", a -> bossbars()).client();
		R.def(c, "screenshot(name?:string) -> string", "Takes a screenshot of the game (without GUI popups of this mod) into the screenshots folder and returns the file name. The file appears a moment later.", a -> {
			var mc = G.mc();
			String name = a.has(0) ? a.str(0) : null;
			if (name != null && !name.toLowerCase().endsWith(".png")) {
				name += ".png";
			}
			if (name != null && (name.contains("/") || name.contains("\\") || name.contains(".."))) {
				throw new KcdError("screenshot(): the name must be a plain file name", 0);
			}
			String finalName = name != null ? name : "kcoder_" + System.currentTimeMillis() + ".png";
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, finalName, mc.gameRenderer.mainRenderTarget(), 1, msg -> { });
			return finalName;
		}).client().permission = "file";
	}
}
