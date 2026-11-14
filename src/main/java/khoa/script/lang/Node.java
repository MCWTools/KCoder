package khoa.script.lang;

import java.util.List;

/** AST. Every node knows its source line (and column when useful for the editor). */
public sealed interface Node {
	int line();

	int col();

	record Param(String name, Node def, boolean rest, int line, int col) {
	}

	/** a ?? b : a unless it is nil. */
	record Coalesce(Node l, Node r, int line, int col) implements Node {
	}

	// ---------- expressions ----------
	record Num(double v, int line, int col) implements Node {
	}

	/** parts: String | Node. */
	record Str(List<Object> parts, int line, int col) implements Node {
	}

	record Bool(boolean v, int line, int col) implements Node {
	}

	record Nil(int line, int col) implements Node {
	}

	record Name(String name, int line, int col) implements Node {
	}

	/** A namespaced id written without quotes, e.g. minecraft:allay. */
	record ResId(String id, int line, int col) implements Node {
	}

	record ListLit(List<Node> items, int line, int col) implements Node {
	}

	record TupleLit(List<Node> items, int line, int col) implements Node {
	}

	record MapLit(List<Node> keys, List<Node> values, int line, int col) implements Node {
	}

	record Unary(String op, Node e, int line, int col) implements Node {
	}

	record Binary(String op, Node l, Node r, int line, int col) implements Node {
	}

	record Logic(boolean isAnd, Node l, Node r, int line, int col) implements Node {
	}

	/** a < b <= c ... ops can be == != < > <= >= in "not in" is "is not". */
	record Compare(Node first, List<String> ops, List<Node> rest, int line, int col) implements Node {
	}

	record Ternary(Node cond, Node a, Node b, int line, int col) implements Node {
	}

	record Lambda(List<Param> params, Node body, int line, int col) implements Node {
	}

	/** names has the same size as args; a null entry means positional. */
	record Call(Node fn, List<Node> args, List<String> names, int line, int col) implements Node {
	}

	/** obj.name (colon=false) or obj:name (colon=true). */
	record Member(Node obj, String name, boolean colon, int line, int col) implements Node {
	}

	record Index(Node obj, Node idx, int line, int col) implements Node {
	}

	record Slice(Node obj, Node lo, Node hi, Node step, int line, int col) implements Node {
	}

	record ListComp(Node elem, List<Node> targets, Node iter, Node cond, int line, int col) implements Node {
	}

	record MapComp(Node key, Node value, List<Node> targets, Node iter, Node cond, int line, int col) implements Node {
	}

	record ImplementExpr(String path, int line, int col) implements Node {
	}

	// ---------- statements ----------
	record ExprStmt(Node e, int line, int col) implements Node {
	}

	/** Simple or unpacking assignment. */
	/** local a, b = 1, 2  -- creates variables in the current function or handler only. */
	record LocalAssign(List<Node> targets, Node value, int line, int col) implements Node {
	}

	record Assign(List<Node> targets, Node value, boolean isGlobal, boolean isConst, int line, int col) implements Node {
	}

	record AugAssign(String op, Node target, Node value, int line, int col) implements Node {
	}

	record If(List<Node> conds, List<List<Node>> blocks, List<Node> elseBlock, int line, int col) implements Node {
	}

	record For(List<Node> targets, Node iter, List<Node> body, int line, int col) implements Node {
	}

	/** match subject: case a, b: ... case _: ...   A null pattern list is the default branch. */
	record Match(Node subject, List<List<Node>> patterns, List<List<Node>> bodies, int line, int col) implements Node {
	}

	record While(Node cond, List<Node> body, int line, int col) implements Node {
	}

	record Repeat(Node count, List<Node> body, int line, int col) implements Node {
	}

	record Break(int line, int col) implements Node {
	}

	record Continue(int line, int col) implements Node {
	}

	record Exit(int line, int col) implements Node {
	}

	record Pass(int line, int col) implements Node {
	}

	record Return(List<Node> values, int line, int col) implements Node {
	}

	record FuncDef(String name, List<Param> params, List<Node> body, int line, int col) implements Node {
	}

	record Try(List<Node> body, String errName, List<Node> catchBody, List<Node> finallyBody, int line, int col) implements Node {
	}

	record Assert(Node cond, Node msg, int line, int col) implements Node {
	}

	record GlobalDecl(List<String> names, int line, int col) implements Node {
	}

	record ImplementStmt(String path, String alias, int line, int col) implements Node {
	}

	/** {@code on_key_press("G"):} followed by an indented block: registers an event handler. */
	/** wait_for(timeout):on_chat():arg(msg) - blocks the script until the event fires; def has no body. */
	record WaitFor(EventDef def, Node timeout, int line, int col) implements Node {
	}

	record EventDef(String event, List<Node> args, List<String> names, List<String> argNames, List<Node> body, int line, int col) implements Node {
	}
}
