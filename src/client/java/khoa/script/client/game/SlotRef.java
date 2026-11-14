package khoa.script.client.game;

import khoa.script.lang.Val;

/** A slot of the menu that is open (looked up again on every use, so it always means the slot at that index right now). */
public record SlotRef(int index) implements Val.KObject {
	@Override
	public String typeName() {
		return "slot";
	}

	@Override
	public String toString() {
		return "<slot " + index + ">";
	}
}
