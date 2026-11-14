package khoa.script;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import khoa.script.lang.Builtin;

/** The client classes cannot run in unit tests, but a malformed builtin signature there would crash the game at startup: parse every one of them here. */
class ClientSignaturesTest {
	private static final Pattern DEF = Pattern.compile("R\\.def\\(\\s*[\\w\"]+\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern METHOD = Pattern.compile("R\\.method\\(\\s*\"\\w+\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	@Test
	void everyClientSignatureParses() throws IOException {
		Path root = Path.of("src/client/java");
		List<String> bad = new ArrayList<>();
		int count = 0;
		try (Stream<Path> files = Files.walk(root)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String src = Files.readString(f);
				for (Pattern pat : List.of(DEF, METHOD)) {
					Matcher m = pat.matcher(src);
					while (m.find()) {
						String sig = m.group(1).replace("\\\"", "\"");
						count++;
						try {
							Builtin.parse("test", sig, "doc", null);
						} catch (RuntimeException e) {
							bad.add(f.getFileName() + ": " + sig + " -> " + e.getMessage());
						}
					}
				}
			}
		}
		assertTrue(count > 100, "found only " + count + " signatures");
		assertTrue(bad.isEmpty(), bad.toString());
	}
}
