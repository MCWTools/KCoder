package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the pricelist example's logic against a fake shop container (the real one needs a game). */
class PricelistExampleTest {
	@TempDir
	Path dir;

	private String body() {
		String src = ExampleFiles.FILES.get("features/pricelist.kcd");
		int at = src.indexOf("ScreenFolder.Button.On_Click:");
		return src.substring(at).replace("ScreenFolder.Button.On_Click:", "def run():");
	}

	private Lab run(String container) {
		Lab l = new Lab();
		l.session.outputRoot = dir;
		String stubs = "C = " + container + "\ndef get_container():\n    return C\ndef notify(a, b):\n    print(a + \": \" + b)\n";
		return l.run(stubs + body() + "\nrun()\n");
	}

	private static final String SHOP = """
		{"items": [
		  {"slot": 0, "id": "minecraft:warped_nylium", "name": "Warped Nylium", "count": 1, "empty": false, "lore": ["Buy Price", "■ $6.00", "Sell Price", "$1.50"]},
		  {"slot": 1, "id": "minecraft:glass_pane", "name": "Filler", "count": 1, "empty": false, "lore": []},
		  {"slot": 2, "id": "minecraft:air", "name": "Air", "count": 0, "empty": true, "lore": []}
		]}""";

	@Test
	void savesBuyPriceOnceAndDumpsJson() throws IOException {
		Lab l = run(SHOP);
		assertEquals(List.of(), l.errors());
		assertEquals("Warped Nylium | $6.00\n", Files.readString(dir.resolve("Pricelist.txt")));
		assertEquals(List.of("Pricelist: 2 items read, 1 new price(s) saved"), l.prints());
		Lab again = run(SHOP);
		assertEquals(List.of(), again.errors());
		assertEquals("Warped Nylium | $6.00\n", Files.readString(dir.resolve("Pricelist.txt")));
		assertEquals(List.of("Pricelist: 2 items read, 0 new price(s) saved"), again.prints());
		assertEquals(true, Files.readString(dir.resolve("Container.json")).contains("Warped Nylium"));
	}
}
