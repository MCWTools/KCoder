package khoa.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Recursive-descent parser for KCD. Knows nothing about builtin names; calls are resolved at run time. */
public final class Parser {
	private static final Set<String> KEYWORDS = Set.of("if", "elif", "else", "for", "while", "repeat", "in", "not", "and", "or", "is", "break", "continue", "exit",
		"return", "function", "def", "fn", "lambda", "try", "catch", "except", "finally", "assert", "global", "local", "const", "implement", "as", "pass", "true", "false", "nil",
		"True", "False", "None");

	private final List<Token> toks;
	private final String file;
	private int p;
	/** > 0 while parsing slice bounds or dictionary keys, where a:b is not a resource id. */
	private int noResId;

	private Parser(List<Token> toks, String file) {
		this.toks = toks;
		this.file = file;
	}

	public static boolean isKeyword(String s) {
		return KEYWORDS.contains(s);
	}

	public static List<Node> parse(String src, String file) {
		Parser ps = new Parser(Lexer.lex(src, file), file);
		List<Node> out = new ArrayList<>();
		while (ps.peek().kind() != Token.Kind.EOF) {
			if (ps.peek().kind() == Token.Kind.NEWLINE) {
				ps.p++;
				continue;
			}
			out.add(ps.statement());
		}
		return out;
	}

	/** Parses a single expression (used by the debugger watch and string interpolation). */
	public static Node parseExpression(String src, String file, int line) {
		Parser ps = new Parser(Lexer.lexFragment(src, file, line), file);
		Node e = ps.test();
		if (ps.peek().kind() != Token.Kind.EOF) {
			throw ps.err("Unexpected '" + ps.peek().text() + "' in expression");
		}
		return e;
	}

	// ---------- helpers ----------
	private Token peek() {
		return toks.get(p);
	}

	private Token peekAt(int n) {
		return toks.get(Math.min(p + n, toks.size() - 1));
	}

	private Token next() {
		return toks.get(p++);
	}

	private KcdError err(String msg) {
		Token t = peek();
		return new KcdError(msg, file, t.line(), t.col());
	}

	private boolean acceptOp(String op) {
		if (peek().isOp(op)) {
			p++;
			return true;
		}
		return false;
	}

	private boolean acceptName(String name) {
		if (peek().isName(name)) {
			p++;
			return true;
		}
		return false;
	}

	private Token expectOp(String op) {
		if (!peek().isOp(op)) {
			throw err("Expected '" + op + "' but found " + describe(peek()));
		}
		return next();
	}

	private Token expectName(String what) {
		Token t = peek();
		if (t.kind() != Token.Kind.NAME || KEYWORDS.contains(t.text())) {
			throw err("Expected " + what + " but found " + describe(t));
		}
		return next();
	}

	private static String describe(Token t) {
		return switch (t.kind()) {
			case NEWLINE -> "end of line";
			case INDENT -> "unexpected indent";
			case DEDENT -> "end of block";
			case EOF -> "end of file";
			case STR -> "a string";
			default -> "'" + t.text() + "'";
		};
	}

	private void endStatement() {
		Token t = peek();
		if (t.kind() == Token.Kind.NEWLINE) {
			p++;
		} else if (t.kind() != Token.Kind.EOF && t.kind() != Token.Kind.DEDENT) {
			throw err("Unexpected " + describe(t) + " (expected end of line)");
		}
	}

	// ---------- statements ----------
	private List<Node> block() {
		expectOp(":");
		if (peek().kind() == Token.Kind.NEWLINE) {
			p++;
			if (peek().kind() != Token.Kind.INDENT) {
				throw err("Expected an indented block");
			}
			p++;
			List<Node> out = new ArrayList<>();
			while (peek().kind() != Token.Kind.DEDENT && peek().kind() != Token.Kind.EOF) {
				if (peek().kind() == Token.Kind.NEWLINE) {
					p++;
					continue;
				}
				out.add(statement());
			}
			if (peek().kind() == Token.Kind.DEDENT) {
				p++;
			}
			return out;
		}
		List<Node> one = new ArrayList<>();
		one.add(statement());
		return one;
	}

	private Node statement() {
		Token t = peek();
		int line = t.line();
		int col = t.col();
		if (t.kind() == Token.Kind.NAME) {
			switch (t.text()) {
				case "if" -> {
					return ifStmt();
				}
				case "while" -> {
					p++;
					Node c = test();
					return new Node.While(c, block(), line, col);
				}
				case "repeat" -> {
					p++;
					Node c = test();
					return new Node.Repeat(c, block(), line, col);
				}
				case "for" -> {
					return forStmt();
				}
				case "function", "def", "fn" -> {
					if (peekAt(1).kind() == Token.Kind.NAME) {
						return funcDef();
					}
				}
				case "try" -> {
					return tryStmt();
				}
				case "match" -> {
					int save = p;
					p++;
					try {
						Node subject = test();
						if (peek().isOp(":") && peekAt(1).kind() == Token.Kind.NEWLINE) {
							return matchStmt(subject, line, col);
						}
					} catch (RuntimeException ignored) {
						// not a match statement (for example a variable called match)
					}
					p = save;
				}
				case "return" -> {
					p++;
					List<Node> vals = new ArrayList<>();
					if (peek().kind() != Token.Kind.NEWLINE && peek().kind() != Token.Kind.EOF && peek().kind() != Token.Kind.DEDENT) {
						vals.add(test());
						while (acceptOp(",")) {
							vals.add(test());
						}
					}
					endStatement();
					return new Node.Return(vals, line, col);
				}
				case "break" -> {
					p++;
					endStatement();
					return new Node.Break(line, col);
				}
				case "continue" -> {
					p++;
					endStatement();
					return new Node.Continue(line, col);
				}
				case "exit" -> {
					p++;
					endStatement();
					return new Node.Exit(line, col);
				}
				case "pass" -> {
					p++;
					endStatement();
					return new Node.Pass(line, col);
				}
				case "assert" -> {
					p++;
					Node c = test();
					Node m = acceptOp(",") ? test() : null;
					endStatement();
					return new Node.Assert(c, m, line, col);
				}
				case "global" -> {
					return globalStmt();
				}
				case "local" -> {
					p++;
					List<Node> names = new ArrayList<>();
					Token first = expectName("a variable name");
					names.add(new Node.Name(first.text(), first.line(), first.col()));
					while (acceptOp(",")) {
						Token n = expectName("a variable name");
						names.add(new Node.Name(n.text(), n.line(), n.col()));
					}
					Node v = acceptOp("=") ? exprListAsOne() : null;
					endStatement();
					return new Node.LocalAssign(names, v, line, col);
				}
				case "const" -> {
					p++;
					Token n = expectName("a constant name");
					expectOp("=");
					Node v = exprListAsOne();
					endStatement();
					return new Node.Assign(List.of(new Node.Name(n.text(), n.line(), n.col())), v, false, true, line, col);
				}
				case "implement" -> {
					return implementStmt();
				}
				default -> {
				}
			}
		}
		if (t.kind() == Token.Kind.INDENT) {
			throw err("Unexpected indent");
		}
		return simpleStatement();
	}

	private Node implementStmt() {
		Token t = next();
		String path = implementPath();
		String alias = null;
		if (acceptName("as")) {
			alias = expectName("an alias name").text();
		}
		endStatement();
		return new Node.ImplementStmt(path, alias, t.line(), t.col());
	}

	/** implement ./x.kcd   or   implement("./x.kcd"). */
	private String implementPath() {
		if (peek().isOp("(")) {
			p++;
			String s = pathToken();
			expectOp(")");
			return s;
		}
		return pathToken();
	}

	private String pathToken() {
		Token t = peek();
		if (t.kind() == Token.Kind.PATH) {
			p++;
			return t.text();
		}
		if (t.kind() == Token.Kind.STR) {
			p++;
			@SuppressWarnings("unchecked")
			List<Object> parts = (List<Object>) t.value();
			if (parts.size() == 1 && parts.get(0) instanceof String s) {
				return s;
			}
		}
		throw err("Expected a file path after implement, e.g. implement ./lib/utils.kcd");
	}

	private Node globalStmt() {
		Token t = next();
		List<String> names = new ArrayList<>();
		names.add(expectName("a variable name").text());
		while (acceptOp(",")) {
			names.add(expectName("a variable name").text());
		}
		if (acceptOp("=")) {
			Node v = exprListAsOne();
			endStatement();
			List<Node> targets = new ArrayList<>();
			for (String n : names) {
				targets.add(new Node.Name(n, t.line(), t.col()));
			}
			return new Node.Assign(targets, v, true, false, t.line(), t.col());
		}
		endStatement();
		return new Node.GlobalDecl(names, t.line(), t.col());
	}

	private Node ifStmt() {
		Token t = next();
		List<Node> conds = new ArrayList<>();
		List<List<Node>> blocks = new ArrayList<>();
		conds.add(test());
		blocks.add(block());
		List<Node> elseBlock = null;
		while (true) {
			if (peek().isName("elif")) {
				p++;
				conds.add(test());
				blocks.add(block());
			} else if (peek().isName("else")) {
				p++;
				if (peek().isName("if")) {
					p++;
					conds.add(test());
					blocks.add(block());
				} else {
					elseBlock = block();
					break;
				}
			} else {
				break;
			}
		}
		return new Node.If(conds, blocks, elseBlock, t.line(), t.col());
	}

	private Node forStmt() {
		Token t = next();
		boolean paren = acceptOp("(");
		List<Node> targets = new ArrayList<>();
		targets.add(postfix());
		while (acceptOp(",")) {
			targets.add(postfix());
		}
		if (paren) {
			expectOp(")");
		}
		if (!acceptName("in")) {
			throw err("Expected 'in' in for loop");
		}
		Node iter = test();
		return new Node.For(targets, iter, block(), t.line(), t.col());
	}

	private Node funcDef() {
		Token t = next();
		Token name = expectName("a function name");
		List<Node.Param> params = params(")");
		return new Node.FuncDef(name.text(), params, block(), t.line(), t.col());
	}

	/** Parses "(a, b = 1, c)" when closer is ")" (consumes the opening paren), or "a, b" up to ':' for lambdas. */
	private List<Node.Param> params(String closer) {
		List<Node.Param> out = new ArrayList<>();
		if (closer.equals(")")) {
			expectOp("(");
		}
		while (!(closer.equals(")") ? peek().isOp(")") : peek().isOp(":"))) {
			boolean rest = acceptOp("*");
			Token n = expectName("a parameter name");
			if (closer.equals(")") && peek().isOp(":")) {
				p++;
				skipTypeAnnotation();
			}
			Node def = null;
			if (acceptOp("=")) {
				def = test();
			}
			out.add(new Node.Param(n.text(), def, rest, n.line(), n.col()));
			if (!acceptOp(",")) {
				break;
			}
		}
		if (closer.equals(")")) {
			expectOp(")");
		}
		return out;
	}

	private Node matchStmt(Node subject, int line, int col) {
		expectOp(":");
		p++; // newline
		if (peek().kind() != Token.Kind.INDENT) {
			throw err("Expected an indented block of cases after match");
		}
		p++;
		List<List<Node>> patterns = new ArrayList<>();
		List<List<Node>> bodies = new ArrayList<>();
		while (peek().kind() != Token.Kind.DEDENT && peek().kind() != Token.Kind.EOF) {
			if (peek().kind() == Token.Kind.NEWLINE) {
				p++;
				continue;
			}
			if (peek().isName("case")) {
				p++;
				if (peek().isName("_") && peekAt(1).isOp(":")) {
					p++;
					patterns.add(null);
				} else {
					List<Node> pats = new ArrayList<>();
					pats.add(test());
					while (acceptOp(",")) {
						pats.add(test());
					}
					patterns.add(pats);
				}
			} else if (peek().isName("else")) {
				p++;
				patterns.add(null);
			} else {
				throw err("Expected 'case' or 'else' inside match");
			}
			bodies.add(block());
		}
		if (peek().kind() == Token.Kind.DEDENT) {
			p++;
		}
		return new Node.Match(subject, patterns, bodies, line, col);
	}

	private Node tryStmt() {
		Token t = next();
		List<Node> body = block();
		String errName = null;
		List<Node> catchBody = null;
		List<Node> fin = null;
		if (peek().isName("catch") || peek().isName("except")) {
			p++;
			if (peek().kind() == Token.Kind.NAME && !peek().isOp(":")) {
				errName = expectName("an error variable name").text();
			}
			catchBody = block();
		}
		if (peek().isName("finally")) {
			p++;
			fin = block();
		}
		if (catchBody == null && fin == null) {
			throw err("try needs a catch or finally block");
		}
		return new Node.Try(body, errName, catchBody, fin, t.line(), t.col());
	}

	/** Skips a type hint such as entity or list<entity>. Hints are for the editor (suggestions); they do not change how the code runs. */
	private void skipTypeAnnotation() {
		expectName("a type name");
		if (peek().isOp("<")) {
			p++;
			expectName("a type name");
			while (acceptOp(",")) {
				expectName("a type name");
			}
			if (!acceptOp(">")) {
				throw err("Expected '>' to close the type");
			}
		}
	}

	/** True when the tokens at the cursor are  :arg(  which is the clause after an event header. */
	private boolean isArgClause() {
		return peek().isOp(":") && peekAt(1).kind() == Token.Kind.NAME && peekAt(1).text().equals("arg") && peekAt(2).isOp("(");
	}

	/** Parses the optional  :arg(a, b)  clause after an event header (the block's own colon is left for block()). Null if absent. */
	private List<String> argClause() {
		if (!isArgClause()) {
			return null;
		}
		p += 3;
		List<String> names = new ArrayList<>();
		if (!peek().isOp(")")) {
			do {
				Token t = peek();
				if (t.kind() != Token.Kind.NAME || KEYWORDS.contains(t.text())) {
					throw err("arg() takes variable names, e.g. arg(msg)");
				}
				names.add(t.text());
				p++;
			} while (acceptOp(","));
		}
		expectOp(")");
		return names;
	}

	private Node simpleStatement() {
		Token t = peek();
		int line = t.line();
		int col = t.col();
		if (t.isName("wait_for") && peekAt(1).isOp("(")) {
			p += 2;
			Node timeout = null;
			if (!peek().isOp(")")) {
				timeout = test();
			}
			expectOp(")");
			expectOp(":");
			Node hdr = exprListAsOne();
			String ev = null;
			List<Node> evArgs = new ArrayList<>();
			List<String> evNames = new ArrayList<>();
			if (hdr instanceof Node.Call c && c.fn() instanceof Node.Name n && (n.name().startsWith("on_") || Registry.get().events.containsKey(n.name()))) {
				ev = n.name();
				evArgs.addAll(c.args());
				evNames.addAll(c.names());
			} else if (hdr instanceof Node.Name bare && (bare.name().startsWith("on_") || Registry.get().events.containsKey(bare.name()))) {
				ev = bare.name(); // wait_for():on_chat   (no parentheses)
			} else {
				Node.Member em = null;
				if (hdr instanceof Node.Call cc && cc.fn() instanceof Node.Member m1 && cc.args().isEmpty()) {
					em = m1;
				} else if (hdr instanceof Node.Member m2) {
					em = m2;
				}
				if (em != null) {
					ev = Registry.get().eventByLooseName(em.name());
				}
				if (ev == null) {
					throw err("wait_for(): expected an event such as on_chat() or Folder.On_Enter after the colon");
				}
				evArgs.add(em.obj());
				evNames.add(null);
			}
			List<String> argNames = argClause();
			acceptOp(":");
			endStatement();
			return new Node.WaitFor(new Node.EventDef(ev, evArgs, evNames, argNames, List.of(), line, col), timeout, line, col);
		}
		// typed variable:  vex: entity = get_nearest_entity(minecraft:vex)   (also  vex:entity = ...  and  items: list<entity> = ...)
		if (t.kind() == Token.Kind.NAME && !KEYWORDS.contains(t.text()) && peekAt(1).isOp(":") && peekAt(2).kind() == Token.Kind.NAME
			&& (peekAt(3).isOp("=") || peekAt(3).isOp("<"))) {
			p += 2;
			skipTypeAnnotation();
			expectOp("=");
			Node value = exprListAsOne();
			endStatement();
			return new Node.Assign(List.of(new Node.Name(t.text(), line, col)), value, false, false, line, col);
		}
		Node first = exprListAsOne();
		// button.On_Click:  followed by an indented block = on_click(button):
		if (peek().isOp(":") && first instanceof Node.Member em && !em.colon() && (peekAt(1).kind() == Token.Kind.NEWLINE || isArgClause())) {
			String ev = Registry.get().eventByLooseName(em.name());
			if (ev == null && em.name().toLowerCase().startsWith("on")) {
				// unknown here (events are registered by the game): keep the shape so the error says "Unknown event"
				ev = "on_" + em.name().toLowerCase().replaceFirst("^on_?", "").replace("_", "");
			}
			if (ev != null) {
				List<String> argNames = argClause();
				List<Node> body = block();
				List<String> names = new ArrayList<>();
				names.add(null);
				return new Node.EventDef(ev, List.of(em.obj()), names, argNames, body, line, col);
			}
		}
		// typed element:  lines[allay]: line_3d = draw_line_3d(...)   (the type is only a hint for the editor)
		if (peek().isOp(":") && (first instanceof Node.Index || first instanceof Node.Member) && peekAt(1).kind() == Token.Kind.NAME
			&& (peekAt(2).isOp("=") || peekAt(2).isOp("<"))) {
			p++;
			skipTypeAnnotation();
			expectOp("=");
			Node value = exprListAsOne();
			endStatement();
			checkTarget(first);
			return new Node.Assign(List.of(first), value, false, false, line, col);
		}
		if (peek().isOp(":") && first instanceof Node.Call c && c.fn() instanceof Node.Name n && (n.name().startsWith("on_") || Registry.get().events.containsKey(n.name()))) {
			List<String> argNames = argClause();
			List<Node> body = block();
			return new Node.EventDef(n.name(), c.args(), c.names(), argNames, body, line, col);
		}
		Token o = peek();
		if (o.isOp("=")) {
			List<Node> targets = new ArrayList<>();
			targets.add(first);
			Node value = null;
			while (acceptOp("=")) {
				value = exprListAsOne();
				if (peek().isOp("=")) {
					targets.add(value);
				}
			}
			// handle = box.On_Hit:  (an indented block follows): the handler is created and the name holds it (cancel / pause / resume)
			if (peek().isOp(":") && targets.size() == 1) {
				Node.EventDef ed = eventDefAfter(value, line, col);
				if (ed != null) {
					checkTarget(targets.get(0));
					return new Node.Assign(targets, ed, false, false, line, col);
				}
			}
			endStatement();
			for (Node target : targets) {
				checkTarget(target);
			}
			return new Node.Assign(targets, value, false, false, line, col);
		}
		if (o.kind() == Token.Kind.OP && o.text().length() >= 2 && o.text().endsWith("=") && "+=-=*=/=%=**=//=".contains(o.text())
			&& !o.text().equals("==") && !o.text().equals("!=") && !o.text().equals("<=") && !o.text().equals(">=")) {
			p++;
			Node v = test();
			endStatement();
			checkTarget(first);
			return new Node.AugAssign(o.text().substring(0, o.text().length() - 1), first, v, line, col);
		}
		endStatement();
		return new Node.ExprStmt(first, line, col);
	}

	/** The event header just parsed as an expression (box.On_Hit  or  on_hit(box)) followed by ":" and a block: the whole handler, or null if it is no event header. */
	private Node.EventDef eventDefAfter(Node value, int line, int col) {
		boolean blockNext = peekAt(1).kind() == Token.Kind.NEWLINE || isArgClause();
		if (!blockNext) {
			return null;
		}
		if (value instanceof Node.Member em && !em.colon()) {
			String ev = Registry.get().eventByLooseName(em.name());
			if (ev == null && em.name().toLowerCase().startsWith("on")) {
				ev = "on_" + em.name().toLowerCase().replaceFirst("^on_?", "").replace("_", "");
			}
			if (ev != null) {
				List<String> argNames = argClause();
				List<Node> body = block();
				List<String> names = new ArrayList<>();
				names.add(null);
				return new Node.EventDef(ev, List.of(em.obj()), names, argNames, body, line, col);
			}
		}
		if (value instanceof Node.Call c && c.fn() instanceof Node.Name n && (n.name().startsWith("on_") || Registry.get().events.containsKey(n.name()))) {
			List<String> argNames = argClause();
			List<Node> body = block();
			return new Node.EventDef(n.name(), c.args(), c.names(), argNames, body, line, col);
		}
		return null;
	}

	private void checkTarget(Node n) {
		if (n instanceof Node.Name || n instanceof Node.Index || (n instanceof Node.Member m && !m.colon())) {
			return;
		}
		if (n instanceof Node.TupleLit tl) {
			for (Node i : tl.items()) {
				checkTarget(i);
			}
			return;
		}
		throw new KcdError("Cannot assign to this expression", file, n.line(), n.col());
	}

	/** Expression, or a comma separated list which becomes a TupleLit. */
	private Node exprListAsOne() {
		Node first = test();
		if (!peek().isOp(",")) {
			return first;
		}
		List<Node> items = new ArrayList<>();
		items.add(first);
		while (acceptOp(",")) {
			if (peek().kind() == Token.Kind.NEWLINE || peek().isOp("=") || peek().isOp(")")) {
				break;
			}
			items.add(test());
		}
		return new Node.TupleLit(items, first.line(), first.col());
	}

	// ---------- expressions ----------
	Node test() {
		Token t = peek();
		if (t.isName("lambda")) {
			p++;
			List<Node.Param> ps = params(":");
			expectOp(":");
			Node body = test();
			return new Node.Lambda(ps, body, t.line(), t.col());
		}
		Node e = coalesce();
		if (peek().isName("if")) {
			// ternary: a if cond else b
			int save = p;
			p++;
			Node c = or();
			if (acceptName("else")) {
				Node b = test();
				return new Node.Ternary(c, e, b, t.line(), t.col());
			}
			p = save;
		}
		return e;
	}

	private Node coalesce() {
		Node l = or();
		while (peek().isOp("??")) {
			Token t = next();
			l = new Node.Coalesce(l, or(), t.line(), t.col());
		}
		return l;
	}

	private Node or() {
		Node l = and();
		while (peek().isName("or")) {
			Token t = next();
			l = new Node.Logic(false, l, and(), t.line(), t.col());
		}
		return l;
	}

	private Node and() {
		Node l = not();
		while (peek().isName("and")) {
			Token t = next();
			l = new Node.Logic(true, l, not(), t.line(), t.col());
		}
		return l;
	}

	private Node not() {
		if (peek().isName("not")) {
			Token t = next();
			return new Node.Unary("not", not(), t.line(), t.col());
		}
		return compare();
	}

	private Node compare() {
		Node first = concat();
		List<String> ops = new ArrayList<>();
		List<Node> rest = new ArrayList<>();
		while (true) {
			Token t = peek();
			String op = null;
			if (t.kind() == Token.Kind.OP && (t.text().equals("==") || t.text().equals("!=") || t.text().equals("~=") || t.text().equals("<") || t.text().equals(">")
				|| t.text().equals("<=") || t.text().equals(">="))) {
				op = t.text().equals("~=") ? "!=" : t.text(); // ~= is the same as !=
				p++;
			} else if (t.isName("in")) {
				op = "in";
				p++;
			} else if (t.isName("not") && peekAt(1).isName("in")) {
				op = "not in";
				p += 2;
			} else if (t.isName("is")) {
				p++;
				op = acceptName("not") ? "is not" : "is";
			}
			if (op == null) {
				break;
			}
			ops.add(op);
			rest.add(concat());
		}
		return ops.isEmpty() ? first : new Node.Compare(first, ops, rest, first.line(), first.col());
	}

	/** "a" .. b joins text (numbers and other values become text); binds looser than + and tighter than comparisons, right to left. */
	private Node concat() {
		Node l = arith();
		if (peek().isOp("..")) {
			Token t = next();
			return new Node.Binary("..", l, concat(), t.line(), t.col());
		}
		return l;
	}

	private Node arith() {
		Node l = term();
		while (peek().isOp("+") || peek().isOp("-")) {
			Token t = next();
			l = new Node.Binary(t.text(), l, term(), t.line(), t.col());
		}
		return l;
	}

	private Node term() {
		Node l = unary();
		while (peek().isOp("*") || peek().isOp("/") || peek().isOp("//") || peek().isOp("%")) {
			Token t = next();
			l = new Node.Binary(t.text(), l, unary(), t.line(), t.col());
		}
		return l;
	}

	private Node unary() {
		Token t = peek();
		if (t.isOp("-") || t.isOp("+")) {
			p++;
			return new Node.Unary(t.text(), unary(), t.line(), t.col());
		}
		return power();
	}

	private Node power() {
		Node base = postfix();
		if (peek().isOp("**")) {
			Token t = next();
			return new Node.Binary("**", base, unary(), t.line(), t.col());
		}
		return base;
	}

	private Node postfix() {
		Node e = atom();
		// ScreenFolder/Button/Label: a slash path, only when the first name is a Screen Folder made in the GUI editor
		boolean path = e instanceof Node.Name root && Registry.get().pathRoot.test(root.name());
		while (true) {
			Token t = peek();
			boolean nameFollows = peekAt(1).kind() == Token.Kind.NAME && !peekAt(1).spaceBefore() && !t.spaceBefore() && !KEYWORDS.contains(peekAt(1).text());
			if (path && (t.isOp("\\") || t.isOp("//")) && nameFollows) {
				p++;
				Token n = next();
				e = new Node.Member(e, n.text(), false, n.line(), n.col());
			} else if (path && t.isOp("/") && nameFollows) {
				throw err("'/' is division, not a GUI path. Write ScreenFolder.Button, ScreenFolder\\Button or ScreenFolder//Button");
			} else if (t.isOp("(")) {
				path = false;
				e = call(e);
			} else if (t.isOp("[")) {
				path = false;
				p++;
				noResId++;
				e = indexOrSlice(e, t);
				noResId--;
				expectOp("]");
			} else if (t.isOp(".")) {
				p++;
				Token n = peek();
				if (n.kind() != Token.Kind.NAME) {
					throw err("Expected a name after '.'");
				}
				p++;
				e = new Node.Member(e, n.text(), false, n.line(), n.col());
			} else if (t.isOp(":") && peekAt(1).isName("arg") && peekAt(2).isOp("(") && (e instanceof Node.Call || (e instanceof Node.Member m0 && !m0.colon()))) {
				break; // on_chat():arg(msg):  the arg clause of an event header, handled by simpleStatement
			} else if (t.isOp(":") && !t.spaceBefore() && peekAt(1).kind() == Token.Kind.NAME && !peekAt(1).spaceBefore() && !KEYWORDS.contains(peekAt(1).text())) {
				Token n = peekAt(1);
				boolean isCall = peekAt(2).isOp("(") && !peekAt(2).spaceBefore();
				if (!isCall && noResId == 0 && e instanceof Node.Name nm) {
					p += 2;
					e = new Node.ResId(nm.name() + ":" + n.text(), nm.line(), nm.col());
				} else if (isCall || noResId == 0) {
					p += 2;
					e = new Node.Member(e, n.text(), true, n.line(), n.col());
				} else {
					break;
				}
			} else {
				break;
			}
		}
		return e;
	}

	private Node indexOrSlice(Node obj, Token open) {
		Node lo = null;
		if (!peek().isOp(":")) {
			lo = test();
			if (!peek().isOp(":")) {
				return new Node.Index(obj, lo, open.line(), open.col());
			}
		}
		expectOp(":");
		Node hi = null;
		Node step = null;
		if (!peek().isOp("]") && !peek().isOp(":")) {
			hi = test();
		}
		if (acceptOp(":") && !peek().isOp("]")) {
			step = test();
		}
		return new Node.Slice(obj, lo, hi, step, open.line(), open.col());
	}

	private Node call(Node fn) {
		Token open = next();
		List<Node> args = new ArrayList<>();
		List<String> names = new ArrayList<>();
		int saved = noResId;
		noResId = 0;
		while (!peek().isOp(")")) {
			if (peek().kind() == Token.Kind.NAME && !KEYWORDS.contains(peek().text()) && peekAt(1).isOp("=")) {
				names.add(next().text());
				p++;
			} else {
				names.add(null);
			}
			args.add(test());
			if (!acceptOp(",")) {
				break;
			}
		}
		noResId = saved;
		expectOp(")");
		return new Node.Call(fn, args, names, open.line(), open.col());
	}

	private Node atom() {
		Token t = peek();
		int line = t.line();
		int col = t.col();
		switch (t.kind()) {
			case NUM -> {
				p++;
				return new Node.Num((Double) t.value(), line, col);
			}
			case STR -> {
				p++;
				return string(t);
			}
			case PATH -> {
				throw err("Unexpected path");
			}
			case NAME -> {
				String word = t.text().equalsIgnoreCase("true") ? "true" : t.text().equalsIgnoreCase("false") ? "false" : t.text();
				switch (word) {
					case "true", "True" -> {
						p++;
						return new Node.Bool(true, line, col);
					}
					case "false", "False" -> {
						p++;
						return new Node.Bool(false, line, col);
					}
					case "nil", "None" -> {
						p++;
						return new Node.Nil(line, col);
					}
					case "implement" -> {
						p++;
						return new Node.ImplementExpr(implementPath(), line, col);
					}
					default -> {
					}
				}
				if (KEYWORDS.contains(t.text())) {
					throw err("Unexpected keyword '" + t.text() + "'");
				}
				p++;
				return new Node.Name(t.text(), line, col);
			}
			case OP -> {
				if (t.isOp("(")) {
					p++;
					int saved = noResId;
					noResId = 0;
					Node r;
					if (peek().isOp(")")) {
						r = new Node.TupleLit(List.of(), line, col);
					} else {
						Node first = test();
						if (peek().isOp(",")) {
							List<Node> items = new ArrayList<>();
							items.add(first);
							while (acceptOp(",")) {
								if (peek().isOp(")")) {
									break;
								}
								items.add(test());
							}
							r = new Node.TupleLit(items, line, col);
						} else {
							r = first;
						}
					}
					noResId = saved;
					expectOp(")");
					return r;
				}
				if (t.isOp("[")) {
					return listLiteral();
				}
				if (t.isOp("{")) {
					return mapLiteral();
				}
			}
			default -> {
			}
		}
		throw err("Unexpected " + describe(t));
	}

	private Node string(Token t) {
		@SuppressWarnings("unchecked")
		List<Object> raw = (List<Object>) t.value();
		List<Object> parts = new ArrayList<>();
		for (Object o : raw) {
			if (o instanceof Token.ExprSrc src) {
				try {
					parts.add(parseExpression(src.text(), file, src.line()));
				} catch (KcdError e) {
					throw new KcdError("In string interpolation {" + src.text() + "}: " + e.getMessage(), file, src.line(), src.col());
				}
			} else {
				parts.add(o);
			}
		}
		return new Node.Str(parts, t.line(), t.col());
	}

	private Node listLiteral() {
		Token open = next();
		int saved = noResId;
		noResId = 0;
		List<Node> items = new ArrayList<>();
		if (!peek().isOp("]")) {
			Node first = test();
			if (peek().isName("for")) {
				ListCompParts cp = compParts();
				noResId = saved;
				expectOp("]");
				return new Node.ListComp(first, cp.targets, cp.iter, cp.cond, open.line(), open.col());
			}
			items.add(first);
			while (acceptOp(",")) {
				if (peek().isOp("]")) {
					break;
				}
				items.add(test());
			}
		}
		noResId = saved;
		expectOp("]");
		return new Node.ListLit(items, open.line(), open.col());
	}

	private record ListCompParts(List<Node> targets, Node iter, Node cond) {
	}

	private ListCompParts compParts() {
		p++; // for
		List<Node> targets = new ArrayList<>();
		targets.add(postfix());
		while (acceptOp(",")) {
			targets.add(postfix());
		}
		if (!acceptName("in")) {
			throw err("Expected 'in' in comprehension");
		}
		Node iter = or();
		Node cond = null;
		if (acceptName("if")) {
			cond = or();
		}
		return new ListCompParts(targets, iter, cond);
	}

	private Node mapLiteral() {
		Token open = next();
		int saved = noResId;
		noResId = 0;
		List<Node> keys = new ArrayList<>();
		List<Node> vals = new ArrayList<>();
		while (!peek().isOp("}")) {
			noResId++;
			Node k = test();
			noResId--;
			expectOp(":");
			Node v = test();
			if (keys.isEmpty() && peek().isName("for")) {
				ListCompParts cp = compParts();
				noResId = saved;
				expectOp("}");
				return new Node.MapComp(k, v, cp.targets, cp.iter, cp.cond, open.line(), open.col());
			}
			keys.add(k);
			vals.add(v);
			if (!acceptOp(",")) {
				break;
			}
		}
		noResId = saved;
		expectOp("}");
		return new Node.MapLit(keys, vals, open.line(), open.col());
	}
}
