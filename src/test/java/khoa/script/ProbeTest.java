package khoa.script;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Developer tool: runs every snippet of the file named by -Dprobe (snippets separated by a line of ----) and prints what happened. Does nothing without it. */
class ProbeTest {
	@Test
	void probe() throws Exception {
		String file = System.getenv("KCD_PROBE");
		if (file == null) {
			return;
		}
		StringBuilder report = new StringBuilder();
		for (String src : Files.readString(Path.of(file)).split("(?m)^----\\s*$")) {
			if (src.isBlank()) {
				continue;
			}
			Lab l = new Lab().run(src.strip());
			report.append(src.strip().replace("\n", " | ")).append("\n   => ").append(l.errors().isEmpty() ? l.prints() : "ERROR " + l.errors()).append("\n");
		}
		Files.writeString(Path.of(file + ".out"), report.toString());
	}
}
