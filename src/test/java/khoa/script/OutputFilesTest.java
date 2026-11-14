package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputFilesTest {
	@TempDir
	Path dir;

	private Lab lab(String src) {
		Lab l = new Lab();
		l.session.outputRoot = dir;
		return l.run(src);
	}

	@Test
	void writeAppendInsertRead() throws IOException {
		Lab l = lab("""
			write_output("a/list.txt", "one\\nthree")
			insert_output("a/list.txt", 2, "two")
			append_output_line("a/list.txt", "four")
			print(read_output_lines("a/list.txt").join(","))
			print(output_exists("a/list.txt"), output_exists("nope.txt"))
			print(len(read_output_lines("nope.txt")))
			""");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("one,two,three,four", "true false", "0"), l.prints());
		assertEquals("one\ntwo\nthree\nfour\n", Files.readString(dir.resolve("a/list.txt")));
	}

	@Test
	void exportTxtAndJson() throws IOException {
		Lab l = lab("""
			export_txt("names", ["x", "y"])
			export_json("data", {"n": 1, "tags": ["a", "b"]}, false)
			print(import_json("data")["n"])
			""");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("1"), l.prints());
		assertEquals("x\ny\n", Files.readString(dir.resolve("names.txt")));
		assertEquals("{\"n\":1,\"tags\":[\"a\",\"b\"]}\n", Files.readString(dir.resolve("data.json")));
	}

	@Test
	void cannotLeaveOutputFolder() {
		Lab l = lab("write_output(\"../evil.txt\", \"x\")");
		assertFalse(l.errors().isEmpty());
		assertFalse(Files.exists(dir.resolveSibling("evil.txt")));
		assertTrue(l.errors().get(0).contains("leaves"));
	}
}
