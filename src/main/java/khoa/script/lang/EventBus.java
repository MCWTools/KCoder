package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Event handlers registered with {@code on_xxx(...):} blocks, owned by the session that registered them. */
public final class EventBus {
	public static final class Handler {
		public final String event;
		public final Session session;
		public final List<Object> filters;
		public final List<Node> body;
		public final Scope closure;
		public final String file;
		public final int line;
		/** Names from the :arg(a, b) clause, or null. */
		public final List<String> argNames;
		/** Nanotime after which the handler stops firing (chat_detection with a duration); 0 = never. */
		long expires;
		ScriptTask running;
		long nextFire;

		Handler(String event, Session session, List<Object> filters, List<Node> body, Scope closure, String file, int line, List<String> argNames) {
			this.event = event;
			this.argNames = argNames;
			this.session = session;
			this.filters = filters;
			this.body = body;
			this.closure = closure;
			this.file = file;
			this.line = line;
		}
	}

	private final Engine engine;
	/** A script paused in wait_for(): filled with the event's data when it fires. */
	private static final class Waiter {
		final String event;
		final Session session;
		final List<Object> filters;
		volatile Map<String, Object> data;

		Waiter(String event, Session session, List<Object> filters) {
			this.event = event;
			this.session = session;
			this.filters = filters;
		}
	}

	private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
	private final Map<String, List<Handler>> handlers = new ConcurrentHashMap<>();

	EventBus(Engine engine) {
		this.engine = engine;
	}

	void register(Interp in, Node.EventDef def, List<Object> filters, Scope closure) {
		Registry.EventType type = Registry.get().events.get(def.event());
		if (type == null) {
			KcdError e = new KcdError("Unknown event '" + def.event() + "'", in.file, def.line(), def.col());
			String close = Hints.closest(def.event(), Registry.get().events.keySet());
			if (close != null) {
				e.withHint("did you mean '" + close + "'?");
			}
			throw e;
		}
		if (filters.size() > type.filters.size()) {
			throw new KcdError(def.event() + " takes at most " + type.filters.size() + " argument(s): " + type.filters, in.file, def.line(), def.col());
		}
		Session s = in.session;
		List<Handler> list = handlers.computeIfAbsent(def.event(), k -> new CopyOnWriteArrayList<>());
		// the same line registered again (a script ran its top level twice) replaces the old handler instead of duplicating it
		list.removeIf(h -> h.session == s && h.file.equals(in.file) && h.line == def.line());
		List<String> argNames = def.argNames();
		if (argNames != null) {
			for (String n : argNames) {
				if (!type.vars.containsKey(n) && type.vars.size() != 1) {
					throw new KcdError(def.event() + " gives " + (type.vars.isEmpty() ? "nothing" : String.join(", ", type.vars.keySet())) + ", so arg(" + n + ") has nothing to bind to", in.file, def.line(), def.col());
				}
			}
			if (argNames.size() > Math.max(1, type.vars.size())) {
				throw new KcdError(def.event() + " gives " + type.vars.size() + " value(s) but arg() names " + argNames.size(), in.file, def.line(), def.col());
			}
		}
		Handler h = new Handler(def.event(), s, filters, def.body(), closure, in.file, def.line(), argNames);
		if (def.event().equals("chat_detection") && filters.size() > 1 && filters.get(1) instanceof Double d) {
			h.expires = engine.scheduler.now() + (long) (Math.max(0, d) * 1e9);
		}
		if (def.event().equals("on_interval")) {
			double sec = filters.isEmpty() || !(filters.get(0) instanceof Double d) ? 1.0 : d;
			h.nextFire = engine.scheduler.now() + (long) (Math.max(0.001, sec) * 1e9);
		}
		list.add(h);
	}

	public void unregister(Session s) {
		for (List<Handler> l : handlers.values()) {
			l.removeIf(h -> h.session == s);
		}
	}

	private Registry.EventType typeOf(Interp in, Node.EventDef def, List<Object> filters) {
		Registry.EventType type = Registry.get().events.get(def.event());
		if (type == null) {
			KcdError e = new KcdError("Unknown event '" + def.event() + "'", in.file, def.line(), def.col());
			String close = Hints.closest(def.event(), Registry.get().events.keySet());
			if (close != null) {
				e.withHint("did you mean '" + close + "'?");
			}
			throw e;
		}
		if (filters.size() > type.filters.size()) {
			throw new KcdError(def.event() + " takes at most " + type.filters.size() + " argument(s): " + type.filters, in.file, def.line(), def.col());
		}
		return type;
	}

	/**
	 * wait_for(): pauses the calling script until the event fires (and passes its filters) or the timeout (seconds, negative = none) ends.
	 * Returns the event's data, or null on timeout / when the script was stopped.
	 */
	public Map<String, Object> await(Interp in, Node.EventDef def, List<Object> filters, double timeoutSec) {
		Registry.EventType type = typeOf(in, def, filters);
		validateArgs(in, def, type);
		Waiter w = new Waiter(def.event(), in.session, filters);
		waiters.add(w);
		long deadline = timeoutSec < 0 ? Long.MAX_VALUE : engine.scheduler.now() + (long) (timeoutSec * 1e9);
		try {
			while (w.data == null && !in.session.stopped && engine.scheduler.now() < deadline) {
				in.waitFrames(1);
			}
		} finally {
			waiters.remove(w);
		}
		return w.data;
	}

	/** Defines the event's values (and the :arg names) as variables; with null data (timeout) the :arg names are nil. */
	public void bind(Scope sc, Node.EventDef def, Map<String, Object> data) {
		Registry.EventType type = Registry.get().events.get(def.event());
		if (data != null) {
			for (Map.Entry<String, Object> e : data.entrySet()) {
				sc.define(e.getKey(), e.getValue());
			}
		}
		if (def.argNames() != null && type != null) {
			for (String n : def.argNames()) {
				Object v = data == null ? null : data.containsKey(n) ? data.get(n) : type.vars.size() == 1 ? data.get(type.vars.keySet().iterator().next()) : null;
				sc.define(n, v);
			}
		}
	}

	private void validateArgs(Interp in, Node.EventDef def, Registry.EventType type) {
		List<String> argNames = def.argNames();
		if (argNames == null) {
			return;
		}
		for (String n : argNames) {
			if (!type.vars.containsKey(n) && type.vars.size() != 1) {
				throw new KcdError(def.event() + " gives " + (type.vars.isEmpty() ? "nothing" : String.join(", ", type.vars.keySet())) + ", so arg(" + n + ") has nothing to bind to", in.file, def.line(), def.col());
			}
		}
		if (argNames.size() > Math.max(1, type.vars.size())) {
			throw new KcdError(def.event() + " gives " + type.vars.size() + " value(s) but arg() names " + argNames.size(), in.file, def.line(), def.col());
		}
	}

	public void unregisterAll() {
		handlers.clear();
	}

	public int count(Session s) {
		int n = 0;
		for (List<Handler> l : handlers.values()) {
			for (Handler h : l) {
				if (h.session == s) {
					n++;
				}
			}
		}
		return n;
	}

	public List<Handler> list(Session s) {
		List<Handler> out = new ArrayList<>();
		for (List<Handler> l : handlers.values()) {
			for (Handler h : l) {
				if (s == null || h.session == s) {
					out.add(h);
				}
			}
		}
		return out;
	}

	public boolean hasHandlers(String event) {
		List<Handler> l = handlers.get(event);
		return l != null && !l.isEmpty() || waiters.stream().anyMatch(w -> w.event.equals(event));
	}

	/** Fires an event on the game thread: every matching handler gets its own task (skipped while its previous run is still going). */
	public void fire(String event, Map<String, Object> data) {
		Registry.EventType type = Registry.get().events.get(event);
		for (Waiter w : waiters) {
			if (w.data == null && w.event.equals(event) && !w.session.stopped && (type == null || type.matcher == null || type.matcher.test(w.filters, data))) {
				w.data = new java.util.LinkedHashMap<>(data);
			}
		}
		List<Handler> list = handlers.get(event);
		if (list == null || list.isEmpty()) {
			return;
		}
		for (Handler h : list) {
			if (h.session.stopped) {
				continue;
			}
			if (h.expires != 0 && engine.scheduler.now() > h.expires) {
				list.remove(h);
				continue;
			}
			if (type != null && type.matcher != null && !type.matcher.test(h.filters, data)) {
				continue;
			}
			run(h, type, data);
		}
	}

	private void run(Handler h, Registry.EventType type, Map<String, Object> data) {
		if (h.running != null && !h.running.finished()) {
			if (type == null || type.skipIfBusy) {
				return;
			}
		}
		h.running = h.session.spawn(h.event + "@" + h.file + ":" + h.line, h.file, in -> {
			Scope sc = new Scope(h.closure, h.event);
			for (Map.Entry<String, Object> e : data.entrySet()) {
				sc.define(e.getKey(), e.getValue());
			}
			// :arg(a, b) names the values this event gives; a name that is not one of them takes the event's only value
			if (h.argNames != null && type != null) {
				for (String n : h.argNames) {
					Object v = data.containsKey(n) ? data.get(n) : type.vars.size() == 1 ? data.get(type.vars.keySet().iterator().next()) : null;
					sc.define(n, v);
				}
			}
			in.runProgram(h.body, sc, h.file);
		});
	}

	/** on_interval(seconds) timers; called every frame. */
	void timers(long now) {
		List<Handler> list = handlers.get("on_interval");
		if (list == null) {
			return;
		}
		for (Handler h : list) {
			if (h.session.stopped || now < h.nextFire) {
				continue;
			}
			double sec = h.filters.isEmpty() || !(h.filters.get(0) instanceof Double d) ? 1.0 : d;
			long period = (long) (Math.max(0.001, sec) * 1e9);
			h.nextFire = Math.max(h.nextFire + period, now - period);
			run(h, Registry.get().events.get("on_interval"), Map.of());
		}
	}
}
