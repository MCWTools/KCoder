package khoa.script.lang;

/** Loads another mod's Java API as a module: {@code fakeplayer = implement khoa.fakeplayer}. Set by the client side. */
public interface ModApiHost {
	/** True when ref looks like a mod package (no slash, no .kcd) and this host knows how to load it. */
	boolean handles(String ref);

	Val.KModule load(Interp in, String ref);
}
