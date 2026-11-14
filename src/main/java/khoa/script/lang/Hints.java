package khoa.script.lang;

import java.util.Collection;

/** "Did you mean ...?" helper (edit distance). */
public final class Hints {
	private Hints() {
	}

	public static String closest(String name, Collection<String> candidates) {
		String best = null;
		int bestDist = Math.max(1, Math.min(3, name.length() / 3 + 1)) + 1;
		for (String c : candidates) {
			if (c.equals(name) || c.startsWith("_")) {
				continue;
			}
			int d = distance(name, c);
			if (d < bestDist) {
				bestDist = d;
				best = c;
			}
		}
		return best;
	}

	public static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
