package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;

/** Virtual path helpers; paths use '/' and are relative to a script root. */
public final class Paths {
	private Paths() {
	}

	public static String dir(String file) {
		int i = file.lastIndexOf('/');
		return i < 0 ? "" : file.substring(0, i);
	}

	public static String name(String path) {
		int i = path.lastIndexOf('/');
		return i < 0 ? path : path.substring(i + 1);
	}

	public static String stem(String path) {
		String n = name(path);
		int i = n.lastIndexOf('.');
		return i <= 0 ? n : n.substring(0, i);
	}

	/** Joins and normalizes (handles . and ..); returns null if the path escapes the root. */
	public static String resolve(String baseDir, String ref) {
		String joined = ref.startsWith("/") ? ref.substring(1) : (baseDir.isEmpty() ? ref : baseDir + "/" + ref);
		List<String> parts = new ArrayList<>();
		for (String p : joined.replace('\\', '/').split("/")) {
			if (p.isEmpty() || p.equals(".")) {
				continue;
			}
			if (p.equals("..")) {
				if (parts.isEmpty()) {
					return null;
				}
				parts.remove(parts.size() - 1);
			} else {
				parts.add(p);
			}
		}
		return String.join("/", parts);
	}
}
