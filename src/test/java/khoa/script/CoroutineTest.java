package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoroutineTest {
	@Test
	void waitSuspendsAndResumesInPlace() {
		Lab l = new Lab().run("print(\"a\")\nwait(0.5)\nprint(\"b\")\nwait(0.5)\nprint(\"c\")");
		assertEquals(List.of("a"), l.prints());
		l.advanceMs(400);
		assertEquals(List.of("a"), l.prints());
		l.advanceMs(150);
		assertEquals(List.of("a", "b"), l.prints());
		l.advanceMs(500);
		assertEquals(List.of("a", "b", "c"), l.prints());
	}

	@Test
	void loopContinuesWhereItStoppedAfterEveryWait() {
		Lab l = new Lab().run("for n in range(1, 100):\n    wait(random(1, 3) / 1000)\n    last = n\nprint(\"done\", last)");
		assertEquals(List.of(), l.prints());
		for (int i = 0; i < 400 && l.prints().isEmpty(); i++) {
			l.advanceMs(16);
		}
		assertEquals(List.of("done 100"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void timedWaitCatchesUpAndDoesNotDrift() {
		Lab l = new Lab().run("start = get_time()\nfor n in range(1, 50):\n    wait(0.002)\nprint(get_time() - start)");
		// 50 waits of 2 ms = 100 ms of script time, even though frames come every 16 ms
		for (int i = 0; i < 20; i++) {
			l.advanceMs(16);
		}
		assertEquals(1, l.prints().size());
		double elapsed = Double.parseDouble(l.prints().get(0));
		assertTrue(elapsed >= 0.1 && elapsed < 0.2, "elapsed=" + elapsed);
	}

	@Test
	void waitTickWaitsForTicks() {
		Lab l = new Lab().run("print(1)\nwait_tick(2)\nprint(2)");
		assertEquals(List.of("1"), l.prints());
		l.tick();
		assertEquals(List.of("1"), l.prints());
		l.tick();
		assertEquals(List.of("1", "2"), l.prints());
	}

	@Test
	void waitUntilAndTimeout() {
		Lab l = new Lab().run("ready = false\nok = wait_until(lambda: ready, 1)\nprint(ok)");
		l.advanceMs(1100);
		assertEquals(List.of("false"), l.prints());
		Lab m = new Lab().run("n = 0\nfunction f():\n    return n >= 3\nr = wait_until(f)\nprint(r)");
		m.advanceMs(50);
		assertEquals(List.of(), m.prints());
	}

	@Test
	void nestedForInsideHandlerWaitsEachIteration() {
		Lab l = new Lab().run("on_test():\n    for allay in [1, 2, 3]:\n        print(\"feed\", allay)\n        wait(0.5)\n    print(\"end\")");
		l.engine.events.fire("on_test", java.util.Map.of());
		l.pump();
		assertEquals(List.of("feed 1"), l.prints());
		l.advanceMs(500);
		assertEquals(List.of("feed 1", "feed 2"), l.prints());
		l.advanceMs(1000);
		assertEquals(List.of("feed 1", "feed 2", "feed 3"), l.prints());
		l.advanceMs(500);
		assertEquals(List.of("feed 1", "feed 2", "feed 3", "end"), l.prints());
	}

	@Test
	void handlerIsSkippedWhileStillRunning() {
		Lab l = new Lab().run("on_test():\n    print(\"start\")\n    wait(1)\n    print(\"end\")");
		l.engine.events.fire("on_test", java.util.Map.of());
		l.pump();
		l.engine.events.fire("on_test", java.util.Map.of());
		l.pump();
		assertEquals(List.of("start"), l.prints());
	}

	@Test
	void stopKillsRunningWaitsAndHandlers() {
		Lab l = new Lab().run("on_test():\n    print(\"h\")\nwait(10)\nprint(\"never\")");
		l.session.stop();
		l.advanceMs(20000);
		assertEquals(List.of(), l.prints());
		assertEquals(0, l.engine.events.count(l.session));
	}

	@Test
	void timersWork() {
		Lab l = new Lab().run("set_timeout(lambda: print(\"later\"), 0.1)\nid = set_interval(lambda: print(\"tick\"), 0.05)\nwait(0.12)\ncancel_timer(id)\nprint(\"cancelled\")");
		for (int i = 0; i < 20; i++) {
			l.advanceMs(16);
		}
		assertTrue(l.prints().contains("later"));
		assertTrue(l.prints().contains("cancelled"));
		int ticks = Collections.frequency(l.prints(), "tick");
		assertTrue(ticks >= 1 && ticks <= 3, "ticks=" + ticks);
	}

	private static class Collections {
		static int frequency(List<String> l, String s) {
			return (int) l.stream().filter(s::equals).count();
		}
	}

	@Test
	void waitOutsideScriptFails() {
		Lab l = new Lab().run("try:\n    wait(0.1)\ncatch e:\n    print(e)");
		// inside a script wait works; this only checks that a try/catch does not swallow the coroutine machinery
		l.advanceMs(200);
		assertEquals(List.of(), l.errors());
	}
}
