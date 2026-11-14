package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Counts calls and time of builtins and user functions while enabled. */
public final class Profiler {
	public static final class Stat {
		public long calls;
		public long nanos;
	}

	public volatile boolean enabled;
	private final Map<String, Stat> stats = new ConcurrentHashMap<>();

	public void record(String name, long nanos) {
		Stat s = stats.computeIfAbsent(name, k -> new Stat());
		synchronized (s) {
			s.calls++;
			s.nanos += nanos;
		}
	}

	public void reset() {
		stats.clear();
	}

	/** Entries sorted by total time: {name, calls, total ms, avg us}. */
	public List<String[]> rows() {
		List<Map.Entry<String, Stat>> list = new ArrayList<>(stats.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue().nanos, a.getValue().nanos));
		List<String[]> out = new ArrayList<>();
		for (Map.Entry<String, Stat> e : list) {
			Stat s = e.getValue();
			out.add(new String[] { e.getKey(), String.valueOf(s.calls), String.format(java.util.Locale.ROOT, "%.3f", s.nanos / 1e6), String.format(java.util.Locale.ROOT, "%.2f", s.nanos / 1e3 / Math.max(1, s.calls)) });
		}
		return out;
	}

	/** Entries sorted by total time, as "name  calls  total ms  avg us". */
	public List<String> report() {
		List<Map.Entry<String, Stat>> list = new ArrayList<>(stats.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue().nanos, a.getValue().nanos));
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Stat> e : list) {
			Stat s = e.getValue();
			out.add(String.format(java.util.Locale.ROOT, "%-34s %7d calls  %9.3f ms  %9.2f us avg", e.getKey(), s.calls, s.nanos / 1e6, s.nanos / 1e3 / Math.max(1, s.calls)));
		}
		return out;
	}
}
