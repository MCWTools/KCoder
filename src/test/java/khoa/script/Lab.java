package khoa.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import khoa.script.lang.Engine;
import khoa.script.lang.LogConsole;
import khoa.script.lang.Registry;
import khoa.script.lang.Session;
import khoa.script.lib.CoreLib;
import khoa.script.lib.RuntimeLib;

/** Test harness: runs KCD source with a fake clock and collects what the script printed. */
final class Lab {
	static {
		CoreLib.register();
		RuntimeLib.register();
		khoa.script.lib.JsonLib.register();
		khoa.script.lib.EnumLib.register();
		Registry.get().event("on_test", "test event", List.of("filter"), Map.of("value", "any"),
			(filters, data) -> filters.isEmpty() || filters.get(0).equals(data.get("value")));
	}

	final Engine engine = new Engine();
	final Map<String, String> files = new HashMap<>();
	Session session;
	long nowNanos = 1_000_000_000L;

	Lab() {
		engine.scheduler.clock = () -> nowNanos;
		session = engine.session("test", files::get);
	}

	Lab file(String name, String src) {
		files.put(name, src);
		return this;
	}

	/** Runs main.kcd until nothing more happens without time passing. */
	Lab run(String src) {
		files.put("main.kcd", src);
		engine.start(session, List.of("main.kcd"));
		pump();
		return this;
	}

	void pump() {
		for (int i = 0; i < 20; i++) {
			engine.scheduler.pump();
		}
	}

	void advanceMs(long ms) {
		nowNanos += ms * 1_000_000L;
		engine.scheduler.onFrame();
	}

	void tick() {
		engine.scheduler.onTick();
	}

	List<String> prints() {
		List<String> out = new ArrayList<>();
		for (LogConsole.Line l : engine.console.snapshot()) {
			if (l.level() == LogConsole.Level.PRINT) {
				out.add(l.message());
			}
		}
		return out;
	}

	List<String> errors() {
		List<String> out = new ArrayList<>();
		for (LogConsole.Line l : engine.console.snapshot()) {
			if (l.level() == LogConsole.Level.ERROR) {
				out.add(l.message());
			}
		}
		return out;
	}

	String last() {
		List<String> p = prints();
		return p.isEmpty() ? null : p.get(p.size() - 1);
	}
}
