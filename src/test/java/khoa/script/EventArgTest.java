package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EventArgTest {
	@Test
	void argNamesTheEventValueAndShadowsOuterVariables() {
		Lab l = new Lab().run("msg = \"outer\"\non_test():arg(msg):\n    print(msg)\nprint(msg)");
		assertEquals(List.of(), l.errors());
		l.engine.events.fire("on_test", Map.of("value", "inner"));
		l.pump();
		assertEquals(List.of("outer", "inner"), l.prints());
	}

	@Test
	void argByRealNameAndOriginalNameStillWorks() {
		Lab l = new Lab().run("on_test():arg(value):\n    print(value)\non_test():\n    print(value, \"plain\")");
		assertEquals(List.of(), l.errors());
		l.engine.events.fire("on_test", Map.of("value", "v"));
		l.pump();
		assertEquals(2, l.prints().size());
		assertTrue(l.prints().contains("v"));
		assertTrue(l.prints().contains("v plain"));
	}

	@Test
	void tooManyArgNamesIsAnError() {
		Lab l = new Lab().run("on_test():arg(a, b):\n    print(a)");
		assertFalse(l.errors().isEmpty());
		assertTrue(l.errors().get(0).contains("arg()"));
	}

	@Test
	void tildeEqualsIsNotEqual() {
		Lab l = new Lab().run("t = \"a\"\nif t ~= \"\":\n    print(\"yes\")\nif t ~= \"a\":\n    print(\"no\")");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("yes"), l.prints());
	}

	@Test
	void dotDotJoinsText() {
		Lab l = new Lab().run("t = \"x\"\nprint(\"lcoord \" .. t .. \" calc\")\nprint(\"n=\" .. 1 + 2 .. \"!\")\nprint(\"a\" + \"b\")\nprint(1 .. 2)\nprint(\"a\" .. \"b\" == \"ab\")");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("lcoord x calc", "n=3!", "ab", "12", "true"), l.prints());
	}

	@Test
	void waitForBlocksUntilTheEventFires() {
		Lab l = new Lab().run("print(\"before\")\nwait_for():on_test():arg(v)\nprint(\"after\", v, timed_out)");
		l.advanceMs(50);
		assertEquals(List.of("before"), l.prints());
		l.engine.events.fire("on_test", Map.of("value", "hello"));
		l.advanceMs(50);
		l.advanceMs(50);
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("before", "after hello false"), l.prints());
	}

	@Test
	void waitForWithTrailingColonAndLooseEventName() {
		Lab l = new Lab().run("on_test():\n    print(\"x\")\nif true:\n    wait_for():on_test:\n    text = value\n    print(\"after\", text)");
		l.advanceMs(50);
		assertEquals(List.of(), l.errors());
		l.engine.events.fire("on_test", Map.of("value", "hi"));
		l.advanceMs(50);
		l.advanceMs(50);
		assertEquals(List.of(), l.errors());
		assertTrue(l.prints().contains("after hi"));
	}

	@Test
	void waitForOnAGuiPathWithTrailingColon() {
		khoa.script.lang.Registry.get().pathRoot = n -> n.equals("Gui");
		try {
			Lab l = new Lab().run("Gui = {\"Btn\": {\"text\": \"hi\"}}\nprint(\"before\")\nwait_for():Gui//Btn.on_test:\nGui//Btn.text = \"x\"\nprint(\"after\")");
			l.advanceMs(50);
			assertEquals(List.of(), l.errors());
			assertEquals(List.of("before"), l.prints());
		} finally {
			khoa.script.lang.Registry.get().pathRoot = n -> false;
		}
	}

	@Test
	void argMustBeNames() {
		Lab l = new Lab().run("on_test():arg(1):\n    print(1)");
		assertFalse(l.errors().isEmpty());
	}
}
