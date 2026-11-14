package khoa.script.client.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;

import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;
import khoa.script.lib.JsonLib;

/**
 * Resource packs, models and textures. Models and textures are changed the way the game itself does it: the script writes a resource pack folder
 * (resourcepacks/kcoder_NAME), enables it and reloads resources, so every item, block and entity picks the change up through the normal pipeline.
 */
public final class PackLib {
	private static final Registry R = Registry.get();
	private static final String PREFIX = "kcoder_";
	private static final long MAX_READ = 8L * 1024 * 1024;

	private PackLib() {
	}

	// ------------------------------------------------------------ helpers
	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static String safeName(Args a, int i) {
		String n = a.str(i).strip();
		if (!n.matches("[A-Za-z0-9_\\-]{1,48}")) {
			throw new KcdError(a.fn.name + "(): pack name must be letters, digits, _ or - (max 48), got '" + n + "'", 0);
		}
		return n;
	}

	private static Path packDir(Args a, int i) {
		return mc().getResourcePackDirectory().resolve(PREFIX + safeName(a, i));
	}

	private static Path existingPack(Args a, int i) {
		Path d = packDir(a, i);
		if (!Files.isDirectory(d)) {
			throw new KcdError(a.fn.name + "(): no pack '" + a.str(i) + "' - create it first with create_resource_pack()", 0);
		}
		return d;
	}

	/** A file inside the pack folder; throws if the path would leave it. */
	private static Path inPack(Args a, Path pack, String rel) {
		String r = rel.replace('\\', '/');
		if (r.startsWith("/") || r.contains(":")) {
			throw new KcdError(a.fn.name + "(): path must be relative to the pack, got '" + rel + "'", 0);
		}
		Path p = pack.resolve(r).normalize();
		if (!p.startsWith(pack.normalize()) || p.equals(pack.normalize())) {
			throw new KcdError(a.fn.name + "(): path leaves the pack folder", 0);
		}
		return p;
	}

	private static String ns(String id) {
		int c = id.indexOf(':');
		return c < 0 ? "minecraft" : id.substring(0, c);
	}

	private static String path(String id) {
		int c = id.indexOf(':');
		return c < 0 ? id : id.substring(c + 1);
	}

	private static void write(Args a, Path p, byte[] data) {
		try {
			Files.createDirectories(p.getParent());
			Files.write(p, data);
		} catch (IOException e) {
			throw new KcdError(a.fn.name + "(): " + e.getMessage(), 0);
		}
	}

	private static String jsonText(Object v) {
		return v instanceof String s ? s : JsonLib.stringify(v, true) + "\n";
	}

	/** Reads a file from the script folder, falling back to the output folder. */
	private static Path source(Args a, int i) {
		String rel = a.str(i).replace('\\', '/');
		for (Path root : new Path[] { a.in.session.fileRoot, a.in.session.outputRoot }) {
			if (root == null) {
				continue;
			}
			Path p = root.resolve(rel).normalize();
			if (p.startsWith(root.normalize()) && Files.isRegularFile(p)) {
				return p;
			}
		}
		throw new KcdError(a.fn.name + "(): file '" + rel + "' not found in the script folder or its output folder", 0);
	}

	private static Map<Object, Object> describe(Pack p, PackRepository repo) {
		Map<Object, Object> m = new LinkedHashMap<>();
		m.put("id", p.getId());
		m.put("name", p.getTitle().getString());
		m.put("description", p.getDescription().getString());
		m.put("enabled", repo.getSelectedIds().contains(p.getId()));
		m.put("compatible", p.getCompatibility().isCompatible());
		m.put("required", p.isRequired());
		return m;
	}

	private static String packId(Args a, int i) {
		String n = a.str(i).strip();
		PackRepository repo = mc().getResourcePackRepository();
		if (repo.isAvailable(n)) {
			return n;
		}
		String own = "file/" + PREFIX + n;
		if (repo.isAvailable(own)) {
			return own;
		}
		throw new KcdError(a.fn.name + "(): unknown pack '" + n + "'", 0).withHint("get_resource_packs() lists every id");
	}

	// ------------------------------------------------------------ api
	public static void register() {
		String c = "resource pack";
		R.def(c, "get_resource_packs() -> list<map>", "Every resource pack the game can see: maps with id, name, description, enabled, compatible, required.", a -> {
			PackRepository repo = mc().getResourcePackRepository();
			repo.reload();
			List<Object> out = new ArrayList<>();
			for (Pack p : repo.getAvailablePacks()) {
				out.add(describe(p, repo));
			}
			return out;
		}).client();
		R.def(c, "get_enabled_resource_packs() -> list<string>", "Ids of the enabled packs, lowest priority first (the last one wins).", a -> new ArrayList<Object>(mc().getResourcePackRepository().getSelectedIds())).client();
		R.def(c, "enable_resource_pack(pack:string, top?:boolean=true) -> boolean", "Turns a pack on (a pack id from get_resource_packs(), or the short name of a pack made with create_resource_pack) and reloads resources. top = highest priority. Returns false if it was already on.", a -> {
			PackRepository repo = mc().getResourcePackRepository();
			repo.reload();
			String id = packId(a, 0);
			List<String> sel = new ArrayList<>(repo.getSelectedIds());
			if (sel.contains(id)) {
				return false;
			}
			if (a.bool(1, true)) {
				sel.add(id);
			} else {
				sel.add(0, id);
			}
			repo.setSelected(sel);
			mc().options.updateResourcePacks(repo);
			return true;
		}).client().perm("file");
		R.def(c, "disable_resource_pack(pack:string) -> boolean", "Turns a pack off and reloads resources. Returns false if it was not on.", a -> {
			PackRepository repo = mc().getResourcePackRepository();
			repo.reload();
			String id = packId(a, 0);
			List<String> sel = new ArrayList<>(repo.getSelectedIds());
			if (!sel.remove(id)) {
				return false;
			}
			repo.setSelected(sel);
			mc().options.updateResourcePacks(repo);
			return true;
		}).client().perm("file");
		R.def(c, "reload_resources()", "Reloads every resource pack (like F3+T). Needed after pack_* changes to a pack that is already enabled.", a -> {
			mc().reloadResourcePacks();
			return null;
		}).client();

		R.def(c, "create_resource_pack(name:string, description?:string) -> string", "Creates resourcepacks/kcoder_<name> with a pack.mcmeta for this game version (keeps its files if it exists) and returns the pack id. Fill it with pack_* functions, then enable_resource_pack(name).", a -> {
			Path d = packDir(a, 0);
			try {
				Files.createDirectories(d);
				Path meta = d.resolve("pack.mcmeta");
				if (!Files.exists(meta)) {
					PackFormat f = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
					String fmt = f.minor() == 0 ? Integer.toString(f.major()) : "[" + f.major() + ", " + f.minor() + "]";
					Map<Object, Object> pack = new LinkedHashMap<>();
					pack.put("description", a.has(1) ? a.str(1) : "Made by a KCoder script");
					String json = JsonLib.stringify(pack, false);
					json = "{\"pack\": {" + json.substring(1, json.length() - 1) + ", \"min_format\": " + fmt + ", \"max_format\": " + fmt + "}}\n";
					Files.writeString(meta, json, StandardCharsets.UTF_8);
				}
			} catch (IOException e) {
				throw new KcdError("create_resource_pack(): " + e.getMessage(), 0);
			}
			return "file/" + PREFIX + safeName(a, 0);
		}).client().perm("file");
		R.def(c, "pack_write_file(pack:string, path:string, content:any)", "Writes a file inside the pack, e.g. pack_write_file(\"mine\", \"assets/minecraft/models/item/stick.json\", {...}). A map or list becomes JSON, a string is written as is.", a -> {
			write(a, inPack(a, existingPack(a, 0), a.str(1)), jsonText(a.obj(2)).getBytes(StandardCharsets.UTF_8));
			return null;
		}).client().perm("file");
		R.def(c, "pack_copy_file(pack:string, path:string, source:string)", "Copies a file (a .png, a sound ...) from your script folder (or its output folder) into the pack at path.", a -> {
			Path to = inPack(a, existingPack(a, 0), a.str(1));
			try {
				Files.createDirectories(to.getParent());
				Files.copy(source(a, 2), to, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new KcdError("pack_copy_file(): " + e.getMessage(), 0);
			}
			return null;
		}).client().perm("file");
		R.def(c, "pack_set_texture(pack:string, texture:string, source:string)", "Replaces a texture: pack_set_texture(\"mine\", \"minecraft:block/stone\", \"stone.png\") copies stone.png (from your script or output folder) to assets/minecraft/textures/block/stone.png. Works for blocks, items, GUI and entities.", a -> {
			String id = a.str(1);
			Path to = inPack(a, existingPack(a, 0), "assets/" + ns(id) + "/textures/" + path(id) + ".png");
			try {
				Files.createDirectories(to.getParent());
				Files.copy(source(a, 2), to, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new KcdError("pack_set_texture(): " + e.getMessage(), 0);
			}
			return null;
		}).client().perm("file");
		R.def(c, "pack_set_model(pack:string, model:string, json:any)", "Replaces a model: pack_set_model(\"mine\", \"minecraft:item/stick\", {\"parent\": \"minecraft:item/handheld\", \"textures\": {\"layer0\": \"minecraft:item/diamond_sword\"}}) writes assets/minecraft/models/item/stick.json. A map becomes JSON, a string is written as is. Tip: read_resource(\"minecraft:models/item/stick.json\") gives the current one.", a -> {
			String id = a.str(1);
			write(a, inPack(a, existingPack(a, 0), "assets/" + ns(id) + "/models/" + path(id) + ".json"), jsonText(a.obj(2)).getBytes(StandardCharsets.UTF_8));
			return null;
		}).client().perm("file");
		R.def(c, "pack_set_item_model(pack:string, item:string, model:string)", "Makes an item use another model: pack_set_item_model(\"mine\", \"minecraft:stick\", \"minecraft:item/diamond_sword\") writes the item definition assets/minecraft/items/stick.json.", a -> {
			String item = a.str(1);
			Map<Object, Object> inner = new LinkedHashMap<>();
			inner.put("type", "minecraft:model");
			inner.put("model", a.str(2).contains(":") ? a.str(2) : "minecraft:" + a.str(2));
			Map<Object, Object> def = new LinkedHashMap<>();
			def.put("model", inner);
			write(a, inPack(a, existingPack(a, 0), "assets/" + ns(item) + "/items/" + path(item) + ".json"), jsonText(def).getBytes(StandardCharsets.UTF_8));
			return null;
		}).client().perm("file");
		R.def(c, "pack_delete_file(pack:string, path:string) -> boolean", "Deletes a file from the pack.", a -> {
			try {
				return Files.deleteIfExists(inPack(a, existingPack(a, 0), a.str(1)));
			} catch (IOException e) {
				throw new KcdError("pack_delete_file(): " + e.getMessage(), 0);
			}
		}).client().perm("file");
		R.def(c, "pack_list_files(pack:string) -> list<string>", "Every file in the pack, as paths relative to it.", a -> {
			Path d = existingPack(a, 0);
			List<Object> out = new ArrayList<>();
			try (Stream<Path> s = Files.walk(d)) {
				s.filter(Files::isRegularFile).sorted().forEach(p -> out.add(d.relativize(p).toString().replace('\\', '/')));
			} catch (IOException e) {
				throw new KcdError("pack_list_files(): " + e.getMessage(), 0);
			}
			return out;
		}).client().perm("file");
		R.def(c, "delete_resource_pack(pack:string) -> boolean", "Disables and deletes a pack made with create_resource_pack (only kcoder_* folders can be deleted).", a -> {
			Path d = packDir(a, 0);
			if (!Files.isDirectory(d)) {
				return false;
			}
			PackRepository repo = mc().getResourcePackRepository();
			String id = "file/" + PREFIX + safeName(a, 0);
			List<String> sel = new ArrayList<>(repo.getSelectedIds());
			boolean was = sel.remove(id);
			if (was) {
				repo.setSelected(sel);
				mc().options.updateResourcePacks(repo);
			}
			try (Stream<Path> s = Files.walk(d)) {
				for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
					Files.delete(p);
				}
			} catch (IOException e) {
				throw new KcdError("delete_resource_pack(): " + e.getMessage() + " (is the pack still loaded? disable it and reload first)", 0);
			}
			repo.reload();
			return true;
		}).client().perm("file");

		// reading what the game has loaded right now
		R.def(c, "resource_exists(id:string) -> boolean", "True if the loaded resources contain this file, e.g. resource_exists(\"minecraft:models/item/stick.json\") or \"minecraft:textures/block/stone.png\".", a -> mc().getResourceManager().getResource(G.id(a.str(0))).isPresent()).client();
		R.def(c, "read_resource(id:string) -> string", "Text of a loaded resource (a model / blockstate / item definition json, lang file ...), from the highest-priority pack that has it.", a -> {
			Resource r = mc().getResourceManager().getResource(G.id(a.str(0))).orElseThrow(() -> new KcdError("read_resource(): no resource '" + a.str(0) + "'", 0));
			try (InputStream in = r.open()) {
				byte[] b = in.readNBytes((int) MAX_READ + 1);
				if (b.length > MAX_READ) {
					throw new KcdError("read_resource(): resource is larger than 8 MB", 0);
				}
				return new String(b, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new KcdError("read_resource(): " + e.getMessage(), 0);
			}
		}).client();
		R.def(c, "list_resources(folder:string, suffix?:string) -> list<string>", "Ids of loaded resources under a folder such as \"models/item\" or \"textures/block\", optionally only those ending with suffix (e.g. \".png\").", a -> {
			String suffix = a.has(1) ? a.str(1).toLowerCase(Locale.ROOT) : "";
			List<Object> out = new ArrayList<>();
			for (var id : mc().getResourceManager().listResources(a.str(0), i -> suffix.isEmpty() || i.getPath().toLowerCase(Locale.ROOT).endsWith(suffix)).keySet()) {
				out.add(id.toString());
			}
			out.sort((x, y) -> Val.str(x).compareTo(Val.str(y)));
			return out;
		}).client();
		R.def(c, "get_texture_size(id:string) -> vec2", "Width and height in pixels of a loaded .png, e.g. get_texture_size(\"minecraft:textures/block/stone.png\").", a -> {
			Resource r = mc().getResourceManager().getResource(G.id(a.str(0))).orElseThrow(() -> new KcdError("get_texture_size(): no resource '" + a.str(0) + "'", 0));
			try (InputStream in = r.open(); NativeImage img = NativeImage.read(in)) {
				return new Val.Vec2(img.getWidth(), img.getHeight());
			} catch (IOException e) {
				throw new KcdError("get_texture_size(): " + e.getMessage(), 0);
			}
		}).client();
	}
}
