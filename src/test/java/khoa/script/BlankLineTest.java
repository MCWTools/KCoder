package khoa.script;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlankLineTest {
	@Test
	void blankLinesNeverBreakCode() {
		String[] sources = {
			"print(1)\n\n",
			"print(1)\n   \n",
			"print(1)\n    ",
			"if true:\n    print(1)\n\n\n    print(2)\n\n",
			"if true:\n    print(1)\n    \n  \nprint(2)\n   ",
			"function f():\n    print(1)\n\nf()\n\n\n",
			"\n\n\nprint(1)",
			"if true:\n    print(1)\n    ",
		};
		for (String s : sources) {
			Lab l = new Lab().run(s);
			assertTrue(l.errors().isEmpty(), "source " + s.replace("\n", "\n") + " -> " + l.errors());
		}
	}
}
