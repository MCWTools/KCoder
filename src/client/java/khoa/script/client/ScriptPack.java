package khoa.script.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.lwjgl.system.MemoryUtil;

/**
 * One Script Folder in one small file (.kcdpack): a ZIP (deflate, level 9) holding {@code kcoder.pack} (the folder name) and every file of the folder.
 * Importing never trusts the paths inside: anything that would land outside the new folder is rejected.
 */
public final class ScriptPack {
	public static final String EXTENSION = ".kcdpack";
	private static final String MANIFEST = "kcoder.pack";
	private static final long MAX_TOTAL = 64L * 1024 * 1024;
	private static final int MAX_FILES = 5000;

	private ScriptPack() {
	}

	/** Result of an import or export: either a message to show, or an error. */
	public record Result(boolean ok, String message) {
	}

	/** Writes the folder to a file. */
	public static Result export(String folder, Path target) {
		Path root = ScriptFolders.dir(folder);
		if (!Files.isDirectory(root)) {
			return new Result(false, "No script folder named '" + folder + "'");
		}
		int count = 0;
		try (OutputStream os = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
			zip.setLevel(Deflater.BEST_COMPRESSION);
			zip.putNextEntry(new ZipEntry(MANIFEST));
			zip.write(("kcoder-pack 1\n" + folder + "\n").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			List<Path> files = new ArrayList<>();
			try (Stream<Path> s = Files.walk(root)) {
				s.filter(Files::isRegularFile).forEach(files::add);
			}
			for (Path f : files) {
				String rel = root.relativize(f).toString().replace('\\', '/');
				if (rel.equals(ScriptFolders.MARKER)) {
					continue;
				}
				zip.putNextEntry(new ZipEntry("files/" + rel));
				Files.copy(f, zip);
				zip.closeEntry();
				count++;
			}
		} catch (IOException e) {
			return new Result(false, "Export failed: " + e.getMessage());
		}
		return new Result(true, "Exported '" + folder + "' (" + count + " files) to " + target);
	}

	/** Reads a pack into a new script folder. A name that is taken gets _2, _3 ... */
	public static Result importFrom(Path source) {
		String name = null;
		List<String> paths = new ArrayList<>();
		List<byte[]> data = new ArrayList<>();
		long total = 0;
		try (InputStream in = Files.newInputStream(source); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
			ZipEntry e;
			while ((e = zip.getNextEntry()) != null) {
				if (e.isDirectory()) {
					continue;
				}
				byte[] bytes = zip.readNBytes((int) Math.min(MAX_TOTAL - total + 1, Integer.MAX_VALUE));
				total += bytes.length;
				if (total > MAX_TOTAL || paths.size() >= MAX_FILES) {
					return new Result(false, "The pack is too big (limit 64 MB, " + MAX_FILES + " files)");
				}
				if (e.getName().equals(MANIFEST)) {
					String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
					if (lines.length >= 2) {
						name = lines[1].trim();
					}
				} else if (e.getName().startsWith("files/")) {
					paths.add(e.getName().substring(6));
					data.add(bytes);
				}
			}
		} catch (IOException e) {
			return new Result(false, "Not a readable KCoder pack: " + e.getMessage());
		}
		if (name == null) {
			return new Result(false, "This is not a KCoder pack (kcoder.pack is missing)");
		}
		if (ScriptFolders.checkName(name) != null) {
			name = source.getFileName().toString().replaceFirst("\\.[^.]*$", "");
			if (ScriptFolders.checkName(name) != null) {
				name = "Imported";
			}
		}
		String unique = name;
		for (int i = 2; Files.exists(ScriptFolders.dir(unique)); i++) {
			unique = name + "_" + i;
		}
		Path root = ScriptFolders.dir(unique).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
			Files.writeString(root.resolve(ScriptFolders.MARKER), "");
			for (int i = 0; i < paths.size(); i++) {
				Path target = root.resolve(paths.get(i)).normalize();
				if (!target.startsWith(root) || paths.get(i).contains("..")) {
					return new Result(false, "Unsafe path in the pack: " + paths.get(i));
				}
				Files.createDirectories(target.getParent());
				Files.write(target, data.get(i));
			}
		} catch (IOException e) {
			return new Result(false, "Import failed: " + e.getMessage());
		}
		return new Result(true, "Imported as '" + unique + "' (" + paths.size() + " files)");
	}

	private static void dialog(boolean save, String defaultName, java.util.function.Consumer<Path> done) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		org.lwjgl.sdl.SDL_DialogFileFilter.Buffer filters = org.lwjgl.sdl.SDL_DialogFileFilter.calloc(1);
		java.nio.ByteBuffer label = MemoryUtil.memUTF8("KCoder script pack (" + EXTENSION + ")");
		java.nio.ByteBuffer pattern = MemoryUtil.memUTF8(EXTENSION.substring(1));
		filters.get(0).set(label, pattern);
		org.lwjgl.sdl.SDL_DialogFileCallback[] holder = new org.lwjgl.sdl.SDL_DialogFileCallback[1];
		holder[0] = org.lwjgl.sdl.SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
			Path chosen = null;
			if (fileList != 0) {
				long first = MemoryUtil.memGetAddress(fileList);
				if (first != 0) {
					chosen = Path.of(MemoryUtil.memUTF8(first));
				}
			}
			Path result = chosen;
			// the dialog may answer on another thread: hand the result to the game thread and release the native memory
			mc.execute(() -> {
				filters.free();
				MemoryUtil.memFree(label);
				MemoryUtil.memFree(pattern);
				holder[0].free();
				if (result != null) {
					done.accept(save && !result.toString().endsWith(EXTENSION) ? Path.of(result + EXTENSION) : result);
				}
			});
		});
		if (save) {
			org.lwjgl.sdl.SDLDialog.SDL_ShowSaveFileDialog(holder[0], 0L, mc.getWindow().handle(), filters, defaultName + EXTENSION);
		} else {
			org.lwjgl.sdl.SDLDialog.SDL_ShowOpenFileDialog(holder[0], 0L, mc.getWindow().handle(), filters, (CharSequence) null, false);
		}
	}

	/** Opens the OS save dialog; done runs on the game thread with the chosen file (never called when cancelled). */
	public static void pickSave(String folder, java.util.function.Consumer<Path> done) {
		dialog(true, folder, done);
	}

	/** Opens the OS file picker; done runs on the game thread with the chosen file (never called when cancelled). */
	public static void pickOpen(java.util.function.Consumer<Path> done) {
		dialog(false, "", done);
	}
}
