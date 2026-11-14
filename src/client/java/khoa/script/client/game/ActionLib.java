package khoa.script.client.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import khoa.script.client.ScriptRuntime;
import khoa.script.client.mixin.KeyMappingAccess;
import khoa.script.lang.Args;
import khoa.script.lang.KcdError;
import khoa.script.lang.Registry;
import khoa.script.lang.Val;

/**
 * Actions. Clicks and key presses are the same input the keyboard would produce (they go through the normal game tick), so the server still
 * decides what happens. Direct calls (attack_entity, interact_block) use the same client methods as a real click.
 */
public final class ActionLib {
	private static final Registry R = Registry.get();
	private static final Set<KeyMapping> HELD = new HashSet<>();

	private record Release(KeyMapping key, long tick) {
	}

	private static final List<Release> LATER = new ArrayList<>();

	private ActionLib() {
	}

	private static void click(KeyMapping key) {
		KeyMappingAccess acc = (KeyMappingAccess) key;
		acc.khoa$setClickCount(acc.khoa$getClickCount() + 1);
	}

	private static void hold(KeyMapping key, boolean down) {
		key.setDown(down);
		if (down) {
			HELD.add(key);
		} else {
			HELD.remove(key);
		}
	}

	/** Lets go of every key a script is holding (called when a script folder stops). */
	public static void releaseAll() {
		for (KeyMapping k : new ArrayList<>(HELD)) {
			k.setDown(false);
		}
		HELD.clear();
		LATER.clear();
	}

	/** Called every client tick: releases keys pressed for a fixed number of ticks (jump). */
	public static void tick(long tick) {
		Iterator<Release> it = LATER.iterator();
		while (it.hasNext()) {
			Release r = it.next();
			if (tick >= r.tick()) {
				r.key().setDown(false);
				HELD.remove(r.key());
				it.remove();
			}
		}
	}

	private static void press(KeyMapping key, int ticks) {
		hold(key, true);
		LATER.add(new Release(key, ScriptRuntime.engine().scheduler.tick() + ticks));
	}

	private static KeyMapping direction(String d) {
		var o = G.mc().options;
		return switch (d.toLowerCase()) {
			case "forward", "front", "w" -> o.keyUp;
			case "back", "backward", "s" -> o.keyDown;
			case "left", "a" -> o.keyLeft;
			case "right", "d" -> o.keyRight;
			default -> throw new KcdError("direction must be forward, back, left or right", 0);
		};
	}

	private static void swing(LocalPlayer p, InteractionHand hand) {
		p.swing(hand, SwingAnimation.DEFAULT, true);
	}

	public static void register() {
		String c = "action";
		R.def(c, "left_click()", "One attack / left click at what your crosshair points at (the crosshair target is refreshed first).", a -> {
			ScriptRuntime.refreshPick();
			click(G.mc().options.keyAttack);
			return null;
		}).client().perm("action").example("left_click()");
		R.def(c, "right_click()", "One use / right click.", a -> {
			ScriptRuntime.refreshPick();
			click(G.mc().options.keyUse);
			return null;
		}).client().perm("action");
		R.alias("use_item", "right_click");
		R.def(c, "hold_left(seconds?:number)", "Holds the attack button (mining). With seconds it waits and lets go; without it holds until release_left().", a -> {
			a.in.onClient(() -> {
				ScriptRuntime.refreshPick();
				hold(G.mc().options.keyAttack, true);
				return null;
			});
			if (a.has(0)) {
				a.in.waitSeconds(a.num(0));
				a.in.onClient(() -> {
					hold(G.mc().options.keyAttack, false);
					return null;
				});
			}
			return null;
		}).suspends().perm("action");
		R.def(c, "hold_right(seconds?:number)", "Holds the use button. With seconds it waits and lets go; without it holds until release_right().", a -> {
			a.in.onClient(() -> {
				hold(G.mc().options.keyUse, true);
				return null;
			});
			if (a.has(0)) {
				a.in.waitSeconds(a.num(0));
				a.in.onClient(() -> {
					hold(G.mc().options.keyUse, false);
					return null;
				});
			}
			return null;
		}).suspends().perm("action");
		R.def(c, "release_left()", "Lets go of the attack button.", a -> {
			hold(G.mc().options.keyAttack, false);
			return null;
		}).client();
		R.def(c, "release_right()", "Lets go of the use button.", a -> {
			hold(G.mc().options.keyUse, false);
			return null;
		}).client();
		R.def(c, "release_all()", "Lets go of every key and button this script holds.", a -> {
			releaseAll();
			return null;
		}).client();
		R.def(c, "jump()", "Presses the jump key for one moment.", a -> {
			press(G.mc().options.keyJump, 2);
			return null;
		}).client().perm("action");
		R.def(c, "sneak(enabled?:boolean=true)", "Starts or stops sneaking.", a -> {
			hold(G.mc().options.keyShift, a.bool(0, true));
			return null;
		}).client().perm("action");
		R.def(c, "sprint(enabled?:boolean=true)", "Starts or stops sprinting.", a -> {
			hold(G.mc().options.keySprint, a.bool(0, true));
			return null;
		}).client().perm("action");
		R.def(c, "move(direction:string, seconds?:number)", "Walks forward, back, left or right. With seconds it waits and stops; without it keeps going until stop_moving().", a -> {
			KeyMapping k = direction(a.str(0));
			a.in.onClient(() -> {
				hold(k, true);
				return null;
			});
			if (a.has(1)) {
				a.in.waitSeconds(a.num(1));
				a.in.onClient(() -> {
					hold(k, false);
					return null;
				});
			}
			return null;
		}).suspends().perm("action").example("move(\"forward\", 1.5)");
		R.def(c, "stop_moving()", "Stops walking started with move().", a -> {
			var o = G.mc().options;
			for (KeyMapping k : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight}) {
				hold(k, false);
			}
			return null;
		}).client();
		R.def(c, "drop_item()", "Drops one item from your selected slot.", a -> {
			click(G.mc().options.keyDrop);
			return null;
		}).client().perm("action");
		R.def(c, "swap_hands()", "Swaps the items in your hands.", a -> {
			click(G.mc().options.keySwapOffhand);
			return null;
		}).client().perm("action");
		R.def(c, "hotbar_select(slot:number)", "Selects hotbar slot 1-9.", a -> {
			int s = a.intv(0);
			if (s < 1 || s > 9) {
				throw new KcdError("hotbar_select(): slot must be 1-9", 0);
			}
			G.me().getInventory().setSelectedSlot(s - 1);
			return null;
		}).client().perm("action").example("hotbar_select(3)");
		R.alias("hotbar_slot", "hotbar_select");
		R.def(c, "attack_entity(entity:entity)", "Attacks an entity directly (same as clicking on it).", a -> {
			Entity e = G.entity(a, 0);
			var p = G.me();
			G.mc().gameMode.attack(p, e);
			swing(p, InteractionHand.MAIN_HAND);
			return null;
		}).client().perm("action");
		R.def(c, "interact_entity(entity:entity) -> boolean", "Right-clicks an entity (feed, trade, ride ...). Returns true if something happened.", a -> {
			Entity e = G.entity(a, 0);
			var p = G.me();
			InteractionResult r = G.mc().gameMode.interact(p, e, new EntityHitResult(e), InteractionHand.MAIN_HAND);
			if (r.consumesAction()) {
				swing(p, InteractionHand.MAIN_HAND);
			}
			return r.consumesAction();
		}).client().perm("action");
		R.def(c, "interact_block(pos:vec3, face?:block_face=\"up\") -> boolean", "Right-clicks a block face with the item in your hand. Returns true if something happened.", a -> {
			var p = G.me();
			BlockPos pos = G.bp(a.vec3(0));
			Direction dir = Direction.byName(a.str(1, "up"));
			if (dir == null) {
				throw new KcdError("face must be up, down, north, south, east or west", 0);
			}
			BlockHitResult hit = new BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), dir, pos, false);
			InteractionResult r = G.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (r.consumesAction()) {
				swing(p, InteractionHand.MAIN_HAND);
			}
			return r.consumesAction();
		}).client().perm("action");
		R.def(c, "close_screen()", "Closes the open menu (inventory, chest ...).", a -> {
			G.mc().gui.setScreen(null);
			return null;
		}).client().perm("action");
	}

	static Minecraft unused(Args a, Val.Vec3 v) {
		return G.mc();
	}
}
