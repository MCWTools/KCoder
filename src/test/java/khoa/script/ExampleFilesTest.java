package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import khoa.script.lang.Parser;

class ExampleFilesTest {
	@Test
	void everyExampleFileParses() {
		for (Map.Entry<String, String> e : ExampleFiles.FILES.entrySet()) {
			if (e.getKey().endsWith(".kcd")) {
				Parser.parse(e.getValue(), e.getKey());
			}
		}
	}

	@Test
	void languageTourRunsToTheEnd() {
		Lab lab = new Lab();
		lab.file("features/basics.kcd", ExampleFiles.FILES.get("features/basics.kcd"));
		lab.run("tour = implement ./features/basics.kcd\ntour:run()\n");
		for (int i = 0; i < 40; i++) {
			lab.advanceMs(250);
			lab.tick();
			lab.tick();
			lab.pump();
		}
		assertEquals(java.util.List.of(), lab.errors());
		assertEquals("Tour finished", lab.last());
		assertTrue(lab.prints().contains("1+2+3+4+5 = 15"), lab.prints().toString());
		assertTrue(lab.prints().contains("min 4, max 42, average 18"), lab.prints().toString());
	}
}
