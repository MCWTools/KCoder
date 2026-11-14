package khoa.script.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tree-walking interpreter. One Interp per running task (script, event handler or console line). */
public final class Interp {
	/** Control flow signals; they are not KcdErrors so script try/catch never swallows them. */
	static final class Signal extends RuntimeException {
		Signal() {
			super(null, null, false, false);
		}
	}

	static final class ReturnSig extends RuntimeException {
		final Object value;

		ReturnSig(Object value) {
			super(null, null, false, false);
			this.value = value;
		}
	}

	private static final Signal BREAK = new Signal();
	private static final Signal CONTINUE = new Signal();
	private static final Signal EXIT = new Signal();

	public static final long CATCH_UP_NANOS = 150_000_000L;

	public record Frame(String name, String file, Scope scope, int callLine) {
	}

	public final Engine engine;
	public final Session session;
	public final Coroutine co;
	public final Registry reg = Registry.get();
	public ScriptTask task;
	public String file;
	public int curLine;
	public final List<Frame> frames = new ArrayList<>();
	public Scope scope;
	private long used;
	private int depth;
	private final int maxDepth;
	private final long budget;

	public Interp(Engine engine, Session session, Coroutine co, String file) {
		this.engine = engine;
		this.session = session;
		this.co = co;
		this.file = file;
		this.maxDepth = engine.maxCallDepth;
		this.budget = engine.instructionBudget;
	}

	// ---------- entry points ----------
	/** Runs a whole program in a scope. exit and top-level return end it quietly. */
	public void runProgram(List<Node> prog, Scope sc, String fileName) {
		String saved = file;
		file = fileName;
		Scope savedScope = scope;
		scope = sc;
		frames.add(new Frame("<" + fileName + ">", fileName, sc, 0));
		try {
			execBlock(prog, sc);
		} catch (Signal s) {
			if (s != EXIT && s != BREAK && s != CONTINUE) {
				throw s;
			}
		} catch (ReturnSig r) {
			// top level return: just stop this file
		} finally {
			frames.remove(frames.size() - 1);
			file = saved;
			scope = savedScope;
		}
	}

	/** Runs a module's top level; unlike a script, exit inside a module ends only the module file. */
	public void runModule(List<Node> prog, Scope sc, String fileName) {
		runProgram(prog, sc, fileName);
	}

	public Object evalExpression(Node n, Scope sc) {
		return eval(n, sc);
	}

	// ---------- waiting ----------
	private void requireCo(String what) {
		if (co == null) {
			throw new KcdError(what + " cannot be used here (no running script)", 0);
		}
	}

	public void waitSeconds(double sec) {
		requireCo("wait()");
		if (sec < 0 || Double.isNaN(sec)) {
			throw new KcdError("wait(): time must be >= 0", 0);
		}
		long now = engine.scheduler.now();
		long dur = (long) (sec * 1e9);
		if (dur == 0) {
			waitFrames(1);
			return;
		}
		long base = co.timeline != 0 && co.timeline >= now - CATCH_UP_NANOS ? co.timeline : now;
		co.timeline = base + dur;
		co.waitTarget = co.timeline;
		co.waitKind = Coroutine.Wait.TIME;
		suspend();
	}

	public void waitTicks(long n) {
		requireCo("wait_tick()");
		co.timeline = 0;
		co.waitTarget = engine.scheduler.tick() + Math.max(1, n);
		co.waitKind = Coroutine.Wait.TICKS;
		suspend();
	}

	public void waitFrames(long n) {
		requireCo("wait_frame()");
		co.timeline = 0;
		co.waitTarget = engine.scheduler.frame() + Math.max(1, n);
		co.waitKind = Coroutine.Wait.FRAMES;
		suspend();
	}

	private void suspend() {
		co.yieldNow();
		co.waitKind = Coroutine.Wait.NONE;
		used = 0;
	}

	/** Run something on the game thread (blocks the script, not the game). */
	public <T> T onClient(java.util.concurrent.Callable<T> c) throws Exception {
		return co != null ? co.onMain(c) : c.call();
	}

	// ---------- statements ----------
	void execBlock(List<Node> body, Scope sc) {
		for (int i = 0, n = body.size(); i < n; i++) {
			exec(body.get(i), sc);
		}
	}

	private void tick(Node n) {
		if (++used > budget) {
			used = 0;
			throw new KcdError("Script ran " + budget + " instructions without a wait(): add wait() or wait_tick() inside long loops", file, n.line(), n.col())
				.withHint("while true:\n    wait_tick(1)");
		}
	}

	private KcdError locate(KcdError e, Node n) {
		if (e.file != null && e.line > 0) {
			return e;
		}
		return e.at(file, n.line(), n.col());
	}

	void exec(Node n, Scope sc) {
		tick(n);
		curLine = n.line();
		scope = sc;
		Debugger dbg = engine.debugger;
		if (dbg.active) {
			dbg.beforeStatement(this, n, sc);
		}
		try {
			switch (n) {
				case Node.ExprStmt s -> eval(s.e(), sc);
				case Node.Assign a -> {
					Object v = eval(a.value(), sc);
					for (Node t : a.targets()) {
						assignTo(t, v, sc, a.isGlobal(), a.isConst());
					}
				}
				case Node.LocalAssign a -> {
					Object v = a.value() == null ? null : eval(a.value(), sc);
					if (a.targets().size() == 1) {
						bindLocal(a.targets().get(0), v, sc);
					} else {
						List<Object> items = a.value() == null ? new ArrayList<>(Collections.nCopies(a.targets().size(), null)) : unpack(v, a.targets().size());
						for (int i = 0; i < a.targets().size(); i++) {
							bindLocal(a.targets().get(i), items.get(i), sc);
						}
					}
				}
				case Node.AugAssign a -> {
					Object cur = eval(a.target(), sc);
					Object v = binary(a.op(), cur, eval(a.value(), sc));
					if (a.target() instanceof Node.Name nm) {
						if (sc.isConst(nm.name())) {
							throw new KcdError("Cannot change constant '" + nm.name() + "'", 0);
						}
						sc.setExisting(nm.name(), v);
					} else {
						assignTo(a.target(), v, sc, false, false);
					}
				}
				case Node.If i -> {
					boolean done = false;
					for (int k = 0; k < i.conds().size(); k++) {
						if (Val.truthy(eval(i.conds().get(k), sc))) {
							execBlock(i.blocks().get(k), sc);
							done = true;
							break;
						}
					}
					if (!done && i.elseBlock() != null) {
						execBlock(i.elseBlock(), sc);
					}
				}
				case Node.For f -> forLoop(f, sc);
				case Node.While w -> {
					while (Val.truthy(eval(w.cond(), sc))) {
						try {
							execBlock(w.body(), sc);
						} catch (Signal s) {
							if (s == BREAK) {
								break;
							}
							if (s != CONTINUE) {
								throw s;
							}
						}
					}
				}
				case Node.Repeat r -> {
					Object c = eval(r.count(), sc);
					if (!(c instanceof Double d)) {
						throw new KcdError("repeat needs a number, got " + Val.typeName(c), 0);
					}
					for (long k = 0; k < (long) d.doubleValue(); k++) {
						try {
							execBlock(r.body(), sc);
						} catch (Signal s) {
							if (s == BREAK) {
								break;
							}
							if (s != CONTINUE) {
								throw s;
							}
						}
					}
				}
				case Node.Break b -> throw BREAK;
				case Node.Continue c -> throw CONTINUE;
				case Node.Exit e -> throw EXIT;
				case Node.Pass p -> {
				}
				case Node.Return r -> {
					Object v = null;
					if (r.values().size() == 1) {
						v = eval(r.values().get(0), sc);
					} else if (r.values().size() > 1) {
						List<Object> items = new ArrayList<>();
						for (Node x : r.values()) {
							items.add(eval(x, sc));
						}
						v = new Val.Tuple(items);
					}
					throw new ReturnSig(v);
				}
				case Node.FuncDef f -> {
					if (reg.builtin(f.name()) != null) {
						engine.console.warn(session, file + ":" + f.line() + ": function '" + f.name()
							+ "' has the same name as a builtin; calls to " + f.name() + "() use the builtin. Rename it or call it as module:" + f.name() + "().", file, f.line());
					}
					sc.define(f.name(), new Val.KFunction(f.name(), f.params(), f.body(), null, sc, file, f.line()));
				}
				case Node.Try t -> tryStmt(t, sc);
				case Node.Match m -> {
					Object subject = eval(m.subject(), sc);
					for (int i = 0; i < m.bodies().size(); i++) {
						List<Node> pats = m.patterns().get(i);
						boolean hit = pats == null;
						if (!hit) {
							for (Node pn : pats) {
								if (Val.equal(subject, eval(pn, sc))) {
									hit = true;
									break;
								}
							}
						}
						if (hit) {
							execBlock(m.bodies().get(i), sc);
							break;
						}
					}
				}
				case Node.Assert a -> {
					if (!Val.truthy(eval(a.cond(), sc))) {
						throw new KcdError(a.msg() == null ? "Assertion failed" : "Assertion failed: " + Val.str(eval(a.msg(), sc)), 0);
					}
				}
				case Node.GlobalDecl g -> {
					for (String name : g.names()) {
						sc.declareGlobal(name);
					}
				}
				case Node.ImplementStmt i -> {
					Val.KModule m = session.loadModule(this, file, i.path());
					String alias = i.alias() != null ? i.alias() : m.name;
					sc.define(alias, m);
				}
				case Node.WaitFor w -> {
					List<Object> filters = new ArrayList<>();
					for (Node a : w.def().args()) {
						filters.add(eval(a, sc));
					}
					Object to = w.timeout() == null ? null : eval(w.timeout(), sc);
					if (to != null && !(to instanceof Double)) {
						throw new KcdError("wait_for(): the timeout must be a number of seconds", w.line());
					}
					Map<String, Object> got = engine.events.await(this, w.def(), filters, to == null ? -1 : (Double) to);
					sc.define("timed_out", got == null);
					engine.events.bind(sc, w.def(), got);
				}
				case Node.EventDef e -> {
					List<Object> filters = new ArrayList<>();
					for (Node a : e.args()) {
						filters.add(eval(a, sc));
					}
					engine.events.register(this, e, filters, sc);
				}
				default -> throw new KcdError("Cannot execute " + n.getClass().getSimpleName(), 0);
			}
		} catch (KcdError e) {
			throw locate(e, n);
		}
	}

	private void tryStmt(Node.Try t, Scope sc) {
		try {
			try {
				execBlock(t.body(), sc);
			} catch (KcdError e) {
				if (t.catchBody() == null) {
					throw e;
				}
				if (t.errName() != null) {
					sc.define(t.errName(), e.getMessage());
				}
				execBlock(t.catchBody(), sc);
			} catch (StackOverflowError so) {
				if (t.catchBody() == null) {
					throw so;
				}
				if (t.errName() != null) {
					sc.define(t.errName(), "Stack overflow (recursion too deep)");
				}
				execBlock(t.catchBody(), sc);
			}
		} catch (Coroutine.Kill k) {
			throw k;
		} catch (Throwable other) {
			if (t.finallyBody() != null) {
				execBlock(t.finallyBody(), sc);
			}
			throw other;
		}
		if (t.finallyBody() != null) {
			execBlock(t.finallyBody(), sc);
		}
	}

	private void forLoop(Node.For f, Scope sc) {
		Object it = eval(f.iter(), sc);
		Iterable<?> seq;
		if (it instanceof Val.Range r) {
			seq = r;
		} else if (it instanceof List<?> l) {
			seq = new ArrayList<>(l);
		} else if (it instanceof Val.Tuple || it instanceof String || it instanceof Map) {
			seq = Val.toList(it);
		} else if (it instanceof Val.Vec3 v) {
			seq = List.of(v.x(), v.y(), v.z());
		} else {
			throw new KcdError("Cannot loop over " + Val.typeName(it), 0).withHint("use range(1, 10) or a list");
		}
		for (Object item : seq) {
			tick(f);
			if (f.targets().size() == 1) {
				bindLocal(f.targets().get(0), item, sc);
			} else {
				bindLocal(new Node.TupleLit(f.targets(), f.line(), f.col()), item, sc);
			}
			try {
				execBlock(f.body(), sc);
			} catch (Signal s) {
				if (s == BREAK) {
					break;
				}
				if (s != CONTINUE) {
					throw s;
				}
			}
		}
	}

	private void assignTo(Node target, Object v, Scope sc, boolean isGlobal, boolean isConst) {
		switch (target) {
			case Node.Name n -> {
				if (sc.isConst(n.name())) {
					throw new KcdError("Cannot change constant '" + n.name() + "'", 0);
				}
				if (isConst) {
					sc.setConst(n.name(), v);
				} else if (isGlobal) {
					sc.declareGlobal(n.name());
					sc.setGlobal(n.name(), v);
				} else {
					sc.set(n.name(), v);
				}
			}
			case Node.TupleLit t -> {
				List<Object> items = unpack(v, t.items().size());
				for (int i = 0; i < t.items().size(); i++) {
					assignTo(t.items().get(i), items.get(i), sc, isGlobal, isConst);
				}
			}
			case Node.Index ix -> {
				Object obj = eval(ix.obj(), sc);
				Object idx = eval(ix.idx(), sc);
				if (obj instanceof List<?> l) {
					@SuppressWarnings("unchecked")
					List<Object> list = (List<Object>) l;
					int i = listIndex(idx, list.size());
					list.set(i, v);
				} else if (obj instanceof Map<?, ?> m) {
					@SuppressWarnings("unchecked")
					Map<Object, Object> map = (Map<Object, Object>) m;
					map.put(idx, v);
				} else {
					throw new KcdError("Cannot assign by index to " + Val.typeName(obj), 0);
				}
			}
			case Node.Member m -> {
				Object obj = eval(m.obj(), sc);
				if (obj instanceof Map<?, ?> mp) {
					@SuppressWarnings("unchecked")
					Map<Object, Object> map = (Map<Object, Object>) mp;
					map.put(m.name(), v);
				} else if (obj instanceof Val.KSettable ks) {
					if (co != null && reg.isClientType(Val.typeName(obj))) {
						co.onMain(() -> {
							ks.setMember(m.name(), v);
							return null;
						});
					} else {
						ks.setMember(m.name(), v);
					}
				} else if (obj instanceof Val.KModule mod) {
					mod.scope.set(m.name(), v);
				} else {
					throw new KcdError("Cannot set '" + m.name() + "' on " + Val.typeName(obj), 0);
				}
			}
			default -> throw new KcdError("Cannot assign to this expression", 0);
		}
	}

	private List<Object> unpack(Object v, int n) {
		List<Object> items;
		if (v instanceof Val.Tuple t) {
			items = t.items();
		} else if (v instanceof List<?> l) {
			items = new ArrayList<>(l);
		} else if (v instanceof Val.Vec3 vec) {
			items = List.of(vec.x(), vec.y(), vec.z());
		} else if (v instanceof Val.Vec2 vec) {
			items = List.of(vec.x(), vec.y());
		} else if (v instanceof Val.Rotation r) {
			items = List.of(r.yaw(), r.pitch());
		} else {
			throw new KcdError("Cannot unpack " + Val.typeName(v) + " into " + n + " variables", 0);
		}
		if (items.size() != n) {
			throw new KcdError("Expected " + n + " values to unpack but got " + items.size(), 0);
		}
		return items;
	}

	// ---------- expressions ----------
	Object eval(Node n, Scope sc) {
		tick(n);
		switch (n) {
			case Node.Num x -> {
				return x.v();
			}
			case Node.Str s -> {
				if (s.parts().size() == 1 && s.parts().get(0) instanceof String only) {
					return only;
				}
				StringBuilder sb = new StringBuilder();
				for (Object p : s.parts()) {
					sb.append(p instanceof String str ? str : Val.str(eval((Node) p, sc)));
				}
				return sb.toString();
			}
			case Node.Bool b -> {
				return b.v();
			}
			case Node.Nil x -> {
				return null;
			}
			case Node.ResId r -> {
				return r.id();
			}
			case Node.Name nm -> {
				return lookup(nm, sc);
			}
			case Node.ListLit l -> {
				List<Object> out = new ArrayList<>(l.items().size());
				for (Node i : l.items()) {
					out.add(eval(i, sc));
				}
				return out;
			}
			case Node.TupleLit t -> {
				List<Object> out = new ArrayList<>(t.items().size());
				for (Node i : t.items()) {
					out.add(eval(i, sc));
				}
				return new Val.Tuple(out);
			}
			case Node.MapLit m -> {
				Map<Object, Object> out = new LinkedHashMap<>();
				for (int i = 0; i < m.keys().size(); i++) {
					Node kn = m.keys().get(i);
					Object k = kn instanceof Node.Name nm && !sc.has(nm.name()) && reg.builtin(nm.name()) == null && !reg.constants.containsKey(nm.name()) ? nm.name() : eval(kn, sc);
					out.put(k, eval(m.values().get(i), sc));
				}
				return out;
			}
			case Node.Unary u -> {
				Object v = eval(u.e(), sc);
				try {
					return switch (u.op()) {
						case "not" -> !Val.truthy(v);
						case "-" -> negate(v);
						default -> v;
					};
				} catch (KcdError e) {
					throw locate(e, u);
				}
			}
			case Node.Binary b -> {
				Object l = eval(b.l(), sc);
				Object r = eval(b.r(), sc);
				try {
					return binary(b.op(), l, r);
				} catch (KcdError e) {
					throw locate(e, b);
				}
			}
			case Node.Logic lg -> {
				Object l = eval(lg.l(), sc);
				if (lg.isAnd()) {
					return Val.truthy(l) ? eval(lg.r(), sc) : l;
				}
				return Val.truthy(l) ? l : eval(lg.r(), sc);
			}
			case Node.Compare c -> {
				Object left = eval(c.first(), sc);
				for (int i = 0; i < c.ops().size(); i++) {
					Object right = eval(c.rest().get(i), sc);
					boolean ok;
					try {
						ok = compare(c.ops().get(i), left, right);
					} catch (KcdError e) {
						throw locate(e, c);
					}
					if (!ok) {
						return false;
					}
					left = right;
				}
				return true;
			}
			case Node.Coalesce c -> {
				Object left = eval(c.l(), sc);
				return left != null ? left : eval(c.r(), sc);
			}
			case Node.Ternary t -> {
				return Val.truthy(eval(t.cond(), sc)) ? eval(t.a(), sc) : eval(t.b(), sc);
			}
			case Node.Lambda l -> {
				return new Val.KFunction("<lambda>", l.params(), null, l.body(), sc, file, l.line());
			}
			case Node.Call c -> {
				return evalCall(c, sc);
			}
			case Node.Member m -> {
				Object obj = eval(m.obj(), sc);
				try {
					return getMember(obj, m.name());
				} catch (KcdError e) {
					throw locate(e, m);
				}
			}
			case Node.Index ix -> {
				Object obj = eval(ix.obj(), sc);
				Object idx = eval(ix.idx(), sc);
				try {
					return index(obj, idx);
				} catch (KcdError e) {
					throw locate(e, ix);
				}
			}
			case Node.Slice s -> {
				Object obj = eval(s.obj(), sc);
				Object lo = s.lo() == null ? null : eval(s.lo(), sc);
				Object hi = s.hi() == null ? null : eval(s.hi(), sc);
				Object st = s.step() == null ? null : eval(s.step(), sc);
				try {
					return slice(obj, lo, hi, st);
				} catch (KcdError e) {
					throw locate(e, s);
				}
			}
			case Node.ListComp lc -> {
				Scope inner = new Scope(sc, "comprehension");
				List<Object> out = new ArrayList<>();
				for (Object item : Val.toList(eval(lc.iter(), sc))) {
					bindComp(lc.targets(), item, inner);
					if (lc.cond() == null || Val.truthy(eval(lc.cond(), inner))) {
						out.add(eval(lc.elem(), inner));
					}
				}
				return out;
			}
			case Node.MapComp mc -> {
				Scope inner = new Scope(sc, "comprehension");
				Map<Object, Object> out = new LinkedHashMap<>();
				for (Object item : Val.toList(eval(mc.iter(), sc))) {
					bindComp(mc.targets(), item, inner);
					if (mc.cond() == null || Val.truthy(eval(mc.cond(), inner))) {
						out.put(eval(mc.key(), inner), eval(mc.value(), inner));
					}
				}
				return out;
			}
			case Node.ImplementExpr i -> {
				try {
					return session.loadModule(this, file, i.path());
				} catch (KcdError e) {
					throw locate(e, i);
				}
			}
			default -> throw new KcdError("Cannot evaluate " + n.getClass().getSimpleName(), file, n.line(), n.col());
		}
	}

	private void bindComp(List<Node> targets, Object item, Scope inner) {
		if (targets.size() == 1) {
			bindLocal(targets.get(0), item, inner);
		} else {
			bindLocal(new Node.TupleLit(targets, 0, 0), item, inner);
		}
	}

	/** Creates (or replaces) a variable in this scope only; used by local, for loops and comprehensions. */
	private void bindLocal(Node target, Object v, Scope sc) {
		if (target instanceof Node.Name n) {
			if (sc.isConst(n.name())) {
				throw new KcdError("Cannot change constant '" + n.name() + "'", 0);
			}
			sc.define(n.name(), v);
		} else if (target instanceof Node.TupleLit t) {
			List<Object> items = unpack(v, t.items().size());
			for (int i = 0; i < t.items().size(); i++) {
				bindLocal(t.items().get(i), items.get(i), sc);
			}
		} else {
			assignTo(target, v, sc, false, false);
		}
	}

	private Object lookup(Node.Name nm, Scope sc) {
		Scope s = sc.find(nm.name());
		if (s != null) {
			return s.vars.get(nm.name());
		}
		Builtin b = reg.builtin(nm.name());
		if (b != null) {
			return b;
		}
		if (reg.constants.containsKey(nm.name())) {
			return reg.constants.get(nm.name());
		}
		for (java.util.function.Function<String, Object> r : reg.resolvers) {
			Object found = r.apply(nm.name());
			if (found != null) {
				return found;
			}
		}
		KcdError e = new KcdError("Undefined variable '" + nm.name() + "'", file, nm.line(), nm.col());
		String close = Hints.closest(nm.name(), allNames(sc));
		if (close != null) {
			e.withHint("did you mean '" + close + "'?");
		}
		throw e;
	}

	private Collection<String> allNames(Scope sc) {
		List<String> names = new ArrayList<>(reg.builtins.keySet());
		names.addAll(reg.constants.keySet());
		for (Scope s = sc; s != null; s = s.parent) {
			names.addAll(s.vars.keySet());
		}
		return names;
	}

	// ---------- calls ----------
	private Object evalCall(Node.Call c, Scope sc) {
		try {
			Node f = c.fn();
			if (f instanceof Node.Name nm) {
				Builtin b = reg.builtin(nm.name());
				if (b != null) {
					List<Object> pos = new ArrayList<>(c.args().size());
					Map<String, Object> named = evalArgs(c, sc, pos);
					return callBuiltin(b, bind(b, pos, named));
				}
				Object fv = lookup(nm, sc);
				List<Object> pos = new ArrayList<>(c.args().size());
				Map<String, Object> named = evalArgs(c, sc, pos);
				return callValue(fv, pos, named, nm.name());
			}
			if (f instanceof Node.Member m) {
				Object obj = eval(m.obj(), sc);
				List<Object> pos = new ArrayList<>(c.args().size());
				Map<String, Object> named = evalArgs(c, sc, pos);
				return callMethod(obj, m.name(), pos, named);
			}
			Object fv = eval(f, sc);
			List<Object> pos = new ArrayList<>(c.args().size());
			Map<String, Object> named = evalArgs(c, sc, pos);
			return callValue(fv, pos, named, "value");
		} catch (KcdError e) {
			throw locate(e, c);
		}
	}

	private Map<String, Object> evalArgs(Node.Call c, Scope sc, List<Object> pos) {
		Map<String, Object> named = null;
		for (int i = 0; i < c.args().size(); i++) {
			Object v = eval(c.args().get(i), sc);
			String nm = c.names().get(i);
			if (nm == null) {
				if (named != null) {
					throw new KcdError("Positional argument after a named argument", 0);
				}
				pos.add(v);
			} else {
				if (named == null) {
					named = new LinkedHashMap<>();
				}
				if (named.put(nm, v) != null) {
					throw new KcdError("Argument '" + nm + "' given twice", 0);
				}
			}
		}
		return named;
	}

	public Object callValue(Object f, List<Object> pos, Map<String, Object> named, String label) {
		if (f instanceof Val.KFunction kf) {
			return callUser(kf, pos, named);
		}
		if (f instanceof Builtin b) {
			return callBuiltin(b, bind(b, pos, named));
		}
		if (f instanceof Val.Bound bd) {
			return callMethod(bd.self(), bd.name(), pos, named);
		}
		throw new KcdError("'" + (label == null ? Val.typeName(f) : label) + "' is not callable (it is a " + Val.typeName(f) + ")", 0);
	}

	private Object[] bind(Builtin b, List<Object> pos, Map<String, Object> named) {
		List<Builtin.ParamDef> ps = b.params;
		int n = ps.size();
		Object[] slots = new Object[n];
		boolean variadic = b.variadic();
		int fixed = variadic ? n - 1 : n;
		if (!variadic && pos.size() > n) {
			throw new KcdError(b.name + "() takes at most " + n + " arguments but " + pos.size() + " were given  [" + b.signature + "]", 0);
		}
		for (int i = 0; i < Math.min(fixed, pos.size()); i++) {
			slots[i] = pos.get(i);
		}
		if (variadic) {
			List<Object> rest = new ArrayList<>();
			for (int i = fixed; i < pos.size(); i++) {
				rest.add(pos.get(i));
			}
			slots[n - 1] = rest;
		}
		boolean[] given = new boolean[n];
		for (int i = 0; i < Math.min(fixed, pos.size()); i++) {
			given[i] = true;
		}
		if (named != null) {
			for (Map.Entry<String, Object> e : named.entrySet()) {
				int idx = -1;
				for (int i = 0; i < fixed; i++) {
					if (ps.get(i).name().equals(e.getKey())) {
						idx = i;
					}
				}
				if (idx < 0) {
					throw new KcdError(b.name + "() has no parameter named '" + e.getKey() + "'  [" + b.signature + "]", 0);
				}
				if (given[idx]) {
					throw new KcdError(b.name + "(): argument '" + e.getKey() + "' given twice", 0);
				}
				slots[idx] = e.getValue();
				given[idx] = true;
			}
		}
		for (int i = 0; i < fixed; i++) {
			if (!given[i] && !ps.get(i).optional()) {
				throw new KcdError(b.name + "(): missing argument '" + ps.get(i).name() + "'  [" + b.signature + "]", 0);
			}
			slots[i] = checkArg(reg, b.name + "()", b.signature, i, ps.get(i), slots[i]);
		}
		return slots;
	}

	/** Rejects a value whose type cannot be what the signature declares (number, string, boolean, vec3). nil is left to the function. */
	static Object checkArg(Registry reg, String fn, String signature, int index, Builtin.ParamDef p, Object v) {
		if (v == null || p.variadic()) {
			return v;
		}
		List<String> choices = reg.enums.get("enum_" + p.type());
		if (choices != null) {
			String pick = v instanceof String s ? s.trim() : null;
			if (pick != null) {
				for (String c : choices) {
					if (c.equalsIgnoreCase(pick)) {
						return c;
					}
				}
			}
			String group = p.type();
			throw new KcdError(fn + ": argument " + (index + 1) + " ('" + p.name() + "') must be one of " + String.join(", ", choices)
				+ " (use enum." + group + ".<choice>), got " + Val.repr(v, 1) + "  [" + signature + "]", 0);
		}
		String want = switch (p.type()) {
			case "number" -> v instanceof Double ? null : "a number";
			case "string" -> v instanceof String ? null : "a string";
			case "boolean" -> v instanceof Boolean ? null : "true or false";
			case "vec3" -> Convert.toVec3(v) != null ? null : "a vec3 (or something with a position)";
			default -> null;
		};
		if (want != null) {
			throw new KcdError(fn + ": argument " + (index + 1) + " ('" + p.name() + "') must be " + want + ", got " + Val.typeName(v) + "  [" + signature + "]", 0);
		}
		return v;
	}

	private Object callBuiltin(Builtin b, Object[] slots) {
		if (!b.permission.isEmpty()) {
			engine.permissions.check(b.permission, b.name);
		}
		Args a = new Args(this, b, slots);
		long t0 = engine.profiler.enabled ? System.nanoTime() : 0;
		try {
			if (b.client && co != null) {
				return co.onMain(() -> b.impl.run(a));
			}
			return b.impl.run(a);
		} catch (KcdError | Coroutine.Kill | Signal | ReturnSig e) {
			throw e;
		} catch (Exception e) {
			throw new KcdError(b.name + "(): " + e, 0);
		} finally {
			if (t0 != 0) {
				engine.profiler.record("builtin " + b.name, System.nanoTime() - t0);
			}
		}
	}

	public Object callUser(Val.KFunction f, List<Object> pos, Map<String, Object> named) {
		if (depth >= maxDepth) {
			throw new KcdError("Recursion too deep (limit " + maxDepth + ") in " + f.name + "()", 0);
		}
		Scope local = new Scope(f.closure, f.name);
		List<Node.Param> ps = f.params;
		if (!ps.isEmpty() && ps.get(ps.size() - 1).rest()) {
			// *items collects every extra positional argument into a list
			int fixed = ps.size() - 1;
			List<Object> extra = new ArrayList<>(pos.size() > fixed ? pos.subList(fixed, pos.size()) : List.of());
			pos = new ArrayList<>(pos.subList(0, Math.min(fixed, pos.size())));
			local.define(ps.get(fixed).name(), extra);
			ps = ps.subList(0, fixed);
		}
		if (pos.size() > ps.size()) {
			throw new KcdError(f.name + "() takes at most " + ps.size() + " arguments but " + pos.size() + " were given", 0);
		}
		boolean[] given = new boolean[ps.size()];
		for (int i = 0; i < pos.size(); i++) {
			local.define(ps.get(i).name(), pos.get(i));
			given[i] = true;
		}
		if (named != null) {
			for (Map.Entry<String, Object> e : named.entrySet()) {
				int idx = -1;
				for (int i = 0; i < ps.size(); i++) {
					if (ps.get(i).name().equals(e.getKey())) {
						idx = i;
					}
				}
				if (idx < 0) {
					throw new KcdError(f.name + "() has no parameter named '" + e.getKey() + "'", 0);
				}
				if (given[idx]) {
					throw new KcdError(f.name + "(): argument '" + e.getKey() + "' given twice", 0);
				}
				local.define(e.getKey(), e.getValue());
				given[idx] = true;
			}
		}
		for (int i = 0; i < ps.size(); i++) {
			if (!given[i]) {
				Node.Param p = ps.get(i);
				if (p.def() == null) {
					throw new KcdError(f.name + "(): missing argument '" + p.name() + "'", 0);
				}
				local.define(p.name(), eval(p.def(), f.closure));
			}
		}
		String savedFile = file;
		int callLine = curLine;
		Scope savedScope = scope;
		file = f.file;
		depth++;
		frames.add(new Frame(f.name, f.file, local, callLine));
		long t0 = engine.profiler.enabled ? System.nanoTime() : 0;
		try {
			if (f.expr != null) {
				return eval(f.expr, local);
			}
			try {
				execBlock(f.body, local);
			} catch (ReturnSig r) {
				return r.value;
			}
			return null;
		} catch (KcdError e) {
			if (e.stack.size() < 12) {
				e.stack.add(f.name + "() " + savedFile + ":" + callLine);
			}
			throw e;
		} finally {
			if (t0 != 0) {
				engine.profiler.record("function " + f.name, System.nanoTime() - t0);
			}
			frames.remove(frames.size() - 1);
			depth--;
			file = savedFile;
			curLine = callLine;
			scope = savedScope;
		}
	}

	public Object callMethod(Object obj, String name, List<Object> pos, Map<String, Object> named) {
		if (obj instanceof Val.KModule mod) {
			Object v = moduleMember(mod, name);
			return callValue(v, pos, named, mod.name + ":" + name);
		}
		if (obj instanceof Map<?, ?> m && m.get(name) != null && isCallable(m.get(name))) {
			return callValue(m.get(name), pos, named, name);
		}
		String type = Val.typeName(obj);
		Registry.Member mem = reg.member(type, name);
		if (mem == null) {
			throw noMember(obj, name);
		}
		if (!mem.method) {
			return callValue(runMember(mem, obj, new Object[0]), pos, named, name);
		}
		if (named != null && !named.isEmpty()) {
			throw new KcdError("Methods do not take named arguments (" + type + ":" + name + ")", 0);
		}
		int n = mem.params.size();
		boolean variadic = !mem.params.isEmpty() && mem.params.get(n - 1).variadic();
		int required = 0;
		for (Builtin.ParamDef p : mem.params) {
			if (!p.optional() && !p.variadic()) {
				required++;
			}
		}
		if (pos.size() < required || (!variadic && pos.size() > n)) {
			String plural = n == 1 ? " argument" : " arguments";
			throw new KcdError(mem.name + "() " + (pos.size() > n ? "takes at most " + n + plural : "needs at least " + required + (required == 1 ? " argument" : " arguments"))
				+ " but " + pos.size() + (pos.size() == 1 ? " was" : " were") + " given  [" + type + ":" + mem.signature() + "]", 0);
		}
		Object[] arr = new Object[Math.max(n, pos.size())];
		for (int i = 0; i < pos.size(); i++) {
			arr[i] = pos.get(i);
			if (i < n) {
				arr[i] = checkArg(reg, type + ":" + name + "()", mem.signature(), i, mem.params.get(i), arr[i]);
			}
		}
		return runMember(mem, obj, arr);
	}

	private static boolean isCallable(Object o) {
		return o instanceof Val.KFunction || o instanceof Builtin || o instanceof Val.Bound;
	}

	private Object runMember(Registry.Member mem, Object self, Object[] args) {
		try {
			if (co != null && reg.isClientType(mem.type)) {
				return co.onMain(() -> mem.impl.run(this, self, args));
			}
			return mem.impl.run(this, self, args);
		} catch (KcdError | Coroutine.Kill | Signal | ReturnSig e) {
			throw e;
		} catch (Exception e) {
			throw new KcdError(mem.type + ":" + mem.name + "(): " + e, 0);
		}
	}

	private Object moduleMember(Val.KModule mod, String name) {
		if (name.startsWith("_")) {
			throw new KcdError("'" + name + "' is private in module " + mod.name, 0);
		}
		Scope s = mod.scope;
		if (!s.vars.containsKey(name)) {
			KcdError e = new KcdError("Module " + mod.name + " has no member '" + name + "'", 0);
			String close = Hints.closest(name, s.vars.keySet());
			if (close != null) {
				e.withHint("did you mean '" + close + "'?");
			}
			throw e;
		}
		return s.vars.get(name);
	}

	private KcdError noMember(Object obj, String name) {
		String type = Val.typeName(obj);
		List<String> names = new ArrayList<>();
		for (Registry.Member m : reg.allMembers(type)) {
			names.add(m.name);
		}
		KcdError e = new KcdError(type + " has no member '" + name + "'", 0);
		String close = Hints.closest(name, names);
		if (close != null) {
			e.withHint("did you mean '" + close + "'?");
		}
		return e;
	}

	public Object getMember(Object obj, String name) {
		if (obj instanceof Val.KModule mod) {
			return moduleMember(mod, name);
		}
		if (obj instanceof Map<?, ?> m && m.containsKey(name)) {
			return m.get(name);
		}
		if (obj instanceof Val.KDynamic dyn) {
			Object found = dyn.dynamicMember(name);
			if (found != null) {
				return found;
			}
		}
		Registry.Member mem = reg.member(Val.typeName(obj), name);
		if (mem == null) {
			if (obj instanceof Map) {
				return null;
			}
			throw noMember(obj, name);
		}
		return mem.method ? new Val.Bound(obj, name) : runMember(mem, obj, new Object[0]);
	}

	// ---------- operators ----------
	static Object negate(Object v) {
		if (v instanceof Double d) {
			return -d;
		}
		if (v instanceof Val.Vec3 a) {
			return new Val.Vec3(-a.x(), -a.y(), -a.z());
		}
		if (v instanceof Val.Vec2 a) {
			return new Val.Vec2(-a.x(), -a.y());
		}
		throw new KcdError("Cannot negate " + Val.typeName(v), 0);
	}

	@SuppressWarnings("unchecked")
	public static Object binary(String op, Object a, Object b) {
		if (a instanceof Double x && b instanceof Double y) {
			switch (op) {
				case "+":
					return x + y;
				case "-":
					return x - y;
				case "*":
					return x * y;
				case "/":
					if (y == 0) {
						throw new KcdError("Division by zero", 0);
					}
					return x / y;
				case "//":
					if (y == 0) {
						throw new KcdError("Division by zero", 0);
					}
					return Math.floor(x / y);
				case "%":
					if (y == 0) {
						throw new KcdError("Modulo by zero", 0);
					}
					return x - Math.floor(x / y) * y;
				case "**":
					return Math.pow(x, y);
				default:
					break;
			}
		}
		switch (op) {
			case ".." -> {
				return Val.str(a) + Val.str(b);
			}
			case "+" -> {
				if (a instanceof String || b instanceof String) {
					return Val.str(a) + Val.str(b);
				}
				if (a instanceof List<?> l1 && b instanceof List<?> l2) {
					List<Object> out = new ArrayList<>(l1);
					out.addAll(l2);
					return out;
				}
				if (a instanceof Val.Vec3 p && b instanceof Val.Vec3 q) {
					return p.add(q);
				}
				if (a instanceof Val.Vec2 p && b instanceof Val.Vec2 q) {
					return new Val.Vec2(p.x() + q.x(), p.y() + q.y());
				}
				if (a instanceof Val.Vec3 && b instanceof Double || a instanceof Double && b instanceof Val.Vec3) {
					throw new KcdError("Cannot add a number to a vec3 (which axis?)", 0).withHint("use vec3(0, 1.5, 0) to add to Y, or get_eye_pos()");
				}
			}
			case "-" -> {
				if (a instanceof Val.Vec3 p && b instanceof Val.Vec3 q) {
					return p.sub(q);
				}
				if (a instanceof Val.Vec2 p && b instanceof Val.Vec2 q) {
					return new Val.Vec2(p.x() - q.x(), p.y() - q.y());
				}
				if (a instanceof Val.Vec3 && b instanceof Double) {
					throw new KcdError("Cannot subtract a number from a vec3 (which axis?)", 0).withHint("use vec3(0, 1, 0)");
				}
			}
			case "*" -> {
				if (a instanceof Val.Vec3 p && b instanceof Double k) {
					return p.mul(k);
				}
				if (a instanceof Double k && b instanceof Val.Vec3 p) {
					return p.mul(k);
				}
				if (a instanceof Val.Vec2 p && b instanceof Double k) {
					return new Val.Vec2(p.x() * k, p.y() * k);
				}
				if (a instanceof String s && b instanceof Double k) {
					return s.repeat((int) Math.max(0, Math.min(10000, k)));
				}
				if (a instanceof List<?> l && b instanceof Double k) {
					List<Object> out = new ArrayList<>();
					for (int i = 0; i < Math.min(10000, (int) k.doubleValue()); i++) {
						out.addAll(l);
					}
					return out;
				}
			}
			case "/" -> {
				if (a instanceof Val.Vec3 p && b instanceof Double k) {
					if (k == 0) {
						throw new KcdError("Division by zero", 0);
					}
					return p.mul(1 / k);
				}
			}
			default -> {
			}
		}
		throw new KcdError("Cannot apply '" + op + "' to " + Val.typeName(a) + " and " + Val.typeName(b), 0);
	}

	@SuppressWarnings("unchecked")
	static boolean compare(String op, Object a, Object b) {
		switch (op) {
			case "==":
				return Val.equal(a, b);
			case "!=":
				return !Val.equal(a, b);
			case "is":
				return a == b || Val.equal(a, b);
			case "is not":
				return !(a == b || Val.equal(a, b));
			case "in":
				return contains(b, a);
			case "not in":
				return !contains(b, a);
			default:
				break;
		}
		if (a instanceof Double x && b instanceof Double y) {
			return switch (op) {
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				default -> x >= y;
			};
		}
		if (a instanceof String x && b instanceof String y) {
			int c = x.compareTo(y);
			return switch (op) {
				case "<" -> c < 0;
				case ">" -> c > 0;
				case "<=" -> c <= 0;
				default -> c >= 0;
			};
		}
		throw new KcdError("Cannot compare " + Val.typeName(a) + " " + op + " " + Val.typeName(b), 0);
	}

	static boolean contains(Object container, Object item) {
		if (container instanceof String s) {
			if (item instanceof String t) {
				return s.contains(t);
			}
			throw new KcdError("'in <string>' needs a string on the left", 0);
		}
		if (container instanceof Map<?, ?> m) {
			return m.containsKey(item);
		}
		if (container instanceof List<?> || container instanceof Val.Tuple || container instanceof Val.Range) {
			for (Object o : Val.toList(container)) {
				if (Val.equal(o, item)) {
					return true;
				}
			}
			return false;
		}
		throw new KcdError("Cannot use 'in' on " + Val.typeName(container), 0);
	}

	static int listIndex(Object idx, int size) {
		if (!(idx instanceof Double d)) {
			throw new KcdError("List index must be a number, got " + Val.typeName(idx), 0)
				.withHint("to look things up by " + Val.typeName(idx) + " use a map: lines = {}  then  lines[key] = value");
		}
		int i = (int) Math.floor(d);
		if (i < 0) {
			i += size;
		}
		if (i < 0 || i >= size) {
			throw new KcdError("Index " + Val.fmt(d) + " out of range (size " + size + ")", 0);
		}
		return i;
	}

	static Object index(Object obj, Object idx) {
		if (obj instanceof List<?> l) {
			return l.get(listIndex(idx, l.size()));
		}
		if (obj instanceof Val.Tuple t) {
			return t.items().get(listIndex(idx, t.items().size()));
		}
		if (obj instanceof String s) {
			int i = listIndex(idx, s.length());
			return String.valueOf(s.charAt(i));
		}
		if (obj instanceof Map<?, ?> m) {
			return m.get(idx);
		}
		if (obj instanceof Val.Range r) {
			List<Object> l = Val.toList(r);
			return l.get(listIndex(idx, l.size()));
		}
		if (obj instanceof Val.Vec3 v) {
			return new double[] {v.x(), v.y(), v.z()}[listIndex(idx, 3)];
		}
		if (obj instanceof Val.Vec2 v) {
			return new double[] {v.x(), v.y()}[listIndex(idx, 2)];
		}
		throw new KcdError("Cannot index " + Val.typeName(obj), 0);
	}

	static Object slice(Object obj, Object lo, Object hi, Object step) {
		List<Object> src;
		boolean isString = obj instanceof String;
		if (isString) {
			src = Val.toList(obj);
		} else if (obj instanceof List<?> || obj instanceof Val.Tuple || obj instanceof Val.Range) {
			src = Val.toList(obj);
		} else {
			throw new KcdError("Cannot slice " + Val.typeName(obj), 0);
		}
		int n = src.size();
		int st = step == null ? 1 : (int) Math.floor(num(step));
		if (st == 0) {
			throw new KcdError("Slice step cannot be 0", 0);
		}
		int a = lo == null ? (st > 0 ? 0 : n - 1) : clampIdx((int) Math.floor(num(lo)), n, st);
		int b = hi == null ? (st > 0 ? n : -1) : clampIdx((int) Math.floor(num(hi)), n, st);
		List<Object> out = new ArrayList<>();
		if (st > 0) {
			for (int i = a; i < b; i += st) {
				out.add(src.get(i));
			}
		} else {
			for (int i = a; i > b; i += st) {
				out.add(src.get(i));
			}
		}
		if (isString) {
			StringBuilder sb = new StringBuilder();
			for (Object o : out) {
				sb.append(o);
			}
			return sb.toString();
		}
		return obj instanceof Val.Tuple ? new Val.Tuple(out) : out;
	}

	private static double num(Object o) {
		if (o instanceof Double d) {
			return d;
		}
		throw new KcdError("Slice bounds must be numbers", 0);
	}

	private static int clampIdx(int i, int n, int step) {
		if (i < 0) {
			i += n;
		}
		if (step > 0) {
			return Math.max(0, Math.min(n, i));
		}
		return Math.max(-1, Math.min(n - 1, i));
	}
}
