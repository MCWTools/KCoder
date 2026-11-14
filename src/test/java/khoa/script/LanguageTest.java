package khoa.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import khoa.script.lang.KcdError;
import khoa.script.lang.Parser;

class LanguageTest {
	@Test
	void basicMath() {
		Lab l = new Lab().run("a = 10\nb = 20\nresult = (a + b) * 2\nprint(result)");
		assertEquals(List.of("60"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void operatorsAndPrecedence() {
		Lab l = new Lab().run("print(2 + 3 * 4)\nprint(2 ** 3 ** 2)\nprint(7 // 2, 7 % 3, -7 // 2, -7 % 3)\nprint(1 < 2 < 3, 3 > 2 > 2)\nprint(not true or true)\nprint(5 / 2)");
		assertEquals(List.of("14", "512", "3 1 -4 2", "true false", "true", "2.5"), l.prints());
	}

	@Test
	void stringsAndInterpolation() {
		Lab l = new Lab().run("name = \"Khoa\"\nhp = 12.5\nprint(\"Hi {name}, hp={hp + 1}\")\nprint(name.upper(), len(name))\nprint(\"a\\{b\")\nprint(\"x\" * 3)");
		assertEquals(List.of("Hi Khoa, hp=13.5", "KHOA 4", "a{b", "xxx"), l.prints());
	}

	@Test
	void conditionals() {
		Lab l = new Lab().run("""
			for h in [3, 8, 15]:
			    if h <= 5:
			        print("Critical")
			    elif h <= 10:
			        print("Warning")
			    else:
			        print("Normal")
			x = 1 if 2 > 1 else 0
			print(x)
			""");
		assertEquals(List.of("Critical", "Warning", "Normal", "1"), l.prints());
	}

	@Test
	void loopVariableBelongsToItsRound() {
		Lab l = new Lab().run("""
			fns = []
			for i in range(1, 3):
			    insert(fns, len(fns), lambda: i)
			for f in fns:
			    print(f())
			print(i)
			""");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("1", "2", "3"), l.prints().subList(0, 3));
		assertEquals("3", l.prints().get(3)); // like Python, the variable is still there after the loop
	}

	@Test
	void handlerHandleCancelPauseResume() {
		Lab l = new Lab().run("""
			h = on_test(1):
			    print("chat")
			print(type(h), h.paused, h.active)
			h.pause()
			print(h.paused)
			h.resume()
			h.cancel()
			print(h.active)
			""");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("handler false true", "true", "false"), l.prints());
	}

	@Test
	void callAndNameAreSeparate() {
		// count = count(...): the name is a variable, name(...) is a call
		Lab l = new Lab().run("""
			count = count([1, 2, 3])
			print(count)
			count = count([1])
			print(count)
			function count():
			    return 5
			count = count()
			print(count)
			count = count()
			print(count)
			""");
		assertEquals(List.of(), l.errors());
		assertEquals(List.of("3", "1", "5", "5"), l.prints());
	}

	@Test
	void nestedLoopsBreakContinue() {
		Lab l = new Lab().run("""
			total = 0
			for x in range(1, 3):
			    for y in range(1, 3):
			        if y == 2:
			            continue
			        total += x * 10 + y
			print(total)
			n = 0
			while true:
			    n += 1
			    if n >= 5:
			        break
			print(n)
			c = 0
			repeat 4:
			    c += 1
			print(c)
			""");
		assertEquals(List.of("132", "5", "4"), l.prints());
	}

	@Test
	void rangeIsInclusive() {
		Lab l = new Lab().run("print(len(range(1, 100)))\nprint(range(3))\nprint(len(range(10, 1)))\nx = 0\nfor n in range(1, 100):\n    x += 1\nprint(x)");
		assertEquals(List.of("100", "range(1, 3)", "10", "100"), l.prints());
	}

	@Test
	void functionsDefaultsNamedAndMultipleReturns() {
		Lab l = new Lab().run("""
			function add(a, b):
			    return a + b
			function greet(name, message = "Hello"):
			    print(message, name)
			function calc():
			    return 10, 20, 30
			print(add(10, 20))
			greet("Khoa")
			greet("Khoa", message = "Yo")
			a, b, c = calc()
			print(a, b, c)
			double = lambda x: x * 2
			print(double(21))
			function main():
			    function helper():
			        return "ok"
			    return helper()
			print(main())
			""");
		assertEquals(List.of("30", "Hello Khoa", "Yo Khoa", "10 20 30", "42", "ok"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void recursionAndLimit() {
		Lab l = new Lab().run("function fact(n):\n    if n <= 1:\n        return 1\n    return n * fact(n - 1)\nprint(fact(10))\nfunction inf(n):\n    return inf(n + 1)\ninf(0)");
		assertEquals("3628800", l.prints().get(0));
		assertEquals(1, l.errors().size());
		assertTrue(l.errors().get(0).contains("Recursion too deep"));
	}

	@Test
	void instructionBudgetStopsRunawayLoops() {
		Lab l = new Lab();
		l.engine.instructionBudget = 20_000;
		l.run("while true:\n    x = 1");
		assertEquals(1, l.errors().size());
		assertTrue(l.errors().get(0).contains("without a wait"));
	}

	@Test
	void listsMapsAndComprehensions() {
		Lab l = new Lab().run("""
			items = [1, 2, 3]
			append(items, 4)
			remove(items, 2)
			items[0] = 10
			print(items, items[-1], items[1:3], len(items))
			data = {"name": "Khoa", "level": 7}
			data["level"] += 1
			print(data["name"], data.level, keys(data))
			sq = [x * x for x in range(1, 5) if x % 2 == 0]
			print(sq)
			m = {k: len(k) for k in ["a", "bb"]}
			print(m)
			nested = [[1, 2], [3]]
			print(nested[0][1], copy(nested) == nested)
			print(sorted([3, 1, 2], reverse = true), 3 in items, "z" not in "abc")
			""");
		assertEquals(List.of("[10, 3, 4] 4 [3, 4] 3", "Khoa 8 [\"name\", \"level\"]", "[4, 16]", "{\"a\": 1, \"bb\": 2}", "2 true", "[3, 2, 1] true true"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void tupleUnpackAndEnumerate() {
		Lab l = new Lab().run("a, b = 1, 2\na, b = b, a\nprint(a, b)\nfor i, v in enumerate([\"x\", \"y\"]):\n    print(i, v)\nfor k, v in items({\"p\": 1}):\n    print(k, v)");
		assertEquals(List.of("2 1", "0 x", "1 y", "p 1"), l.prints());
	}

	@Test
	void tryCatchFinally() {
		Lab l = new Lab().run("""
			try:
			    result = 10 / 0
			catch error:
			    print("Error: {error}")
			finally:
			    print("Finished")
			try:
			    raise("boom")
			catch e:
			    print(e)
			try:
			    assert 1 > 2, "bad"
			catch e:
			    print(e)
			""");
		assertEquals(List.of("Error: Division by zero", "Finished", "boom", "Assertion failed: bad"), l.prints());
	}

	@Test
	void exitStopsScript() {
		Lab l = new Lab().run("print(1)\nexit\nprint(2)");
		assertEquals(List.of("1"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void scopes() {
		Lab l = new Lab().run("""
			counter = 0
			function bump():
			    counter += 1
			function shadow():
			    counter = 100
			bump()
			shadow()
			print(counter)
			function setg():
			    global total = 5
			setg()
			print(total)
			const MAX = 3
			try:
			    MAX = 4
			catch e:
			    print(e)
			""");
		assertEquals(List.of("100", "5", "Cannot change constant 'MAX'"), l.prints());
	}

	@Test
	void vectors() {
		Lab l = new Lab().run("""
			a = vec3(0, 0, 0)
			b = vec3(10, 10, 10)
			print(distance(a, b) > 17, (a + b) / 2)
			print(vec3(1, 2, 3) * 2, b.x, b.length > 17)
			yaw, pitch = get_direction(vec3(0, 0, 0), vec3(0, 0, 5))
			print(yaw, pitch)
			print(get_direction(vec3(0, 0, 0), vec3(5, 0, 0)).yaw)
			print(dot(vec3(1, 0, 0), vec3(0, 1, 0)), cross(vec3(1, 0, 0), vec3(0, 1, 0)))
			try:
			    vec3(1, 1, 1) + 1.5
			catch e:
			    print(e)
			""");
		assertEquals("true vec3(5, 5, 5)", l.prints().get(0));
		assertEquals("vec3(2, 4, 6) 10 true", l.prints().get(1));
		assertEquals("0 0", l.prints().get(2));
		assertEquals("-90", l.prints().get(3));
		assertEquals("0 vec3(0, 0, 1)", l.prints().get(4));
		assertTrue(l.prints().get(5).startsWith("Cannot add a number to a vec3"));
	}

	@Test
	void resourceIdsAreStrings() {
		Lab l = new Lab().run("a = minecraft:allay\nprint(a, type(a))\nx = [1, 2, 3]\nprint(x[0:2])\nm = {\"k\": 1}\nprint(m[\"k\"])");
		assertEquals(List.of("minecraft:allay string", "[1, 2]", "1"), l.prints());
	}

	@Test
	void builtinsWinOverUserFunctionsWithTheSameName() {
		Lab l = new Lab().file("lib.kcd", "function distance(a, b):\n    return 999\n").run("u = implement ./lib.kcd\nprint(distance(vec3(0,0,0), vec3(3,4,0)))\nprint(u:distance(1, 2))");
		assertEquals(List.of("5", "999"), l.prints());
		assertTrue(l.engine.console.snapshot().stream().anyMatch(x -> x.level() == khoa.script.lang.LogConsole.Level.WARN && x.message().contains("same name as a builtin")));
	}

	@Test
	void modulesAreCachedAndRelative() {
		Lab l = new Lab()
			.file("lib/util.kcd", "function twice(x):\n    return x * 2\nprint(\"loading util\")\n_secret = 1\n")
			.file("features/mace.kcd", "u = implement ../lib/util.kcd\nfunction run():\n    return u:twice(21)\n")
			.run("mace = implement ./features/mace.kcd\nagain = implement ./lib/util.kcd\nprint(mace:run())\nprint(again:twice(4))\ntry:\n    print(again._secret)\ncatch e:\n    print(e)\nimplement ./features/mace.kcd as m2\nprint(m2:run())");
		assertEquals(List.of("loading util", "42", "8", "'_secret' is private in module util", "42"), l.prints());
		assertEquals(List.of(), l.errors());
	}

	@Test
	void circularImportIsDetected() {
		Lab l = new Lab().file("a.kcd", "b = implement ./b.kcd\n").file("b.kcd", "a = implement ./a.kcd\n").run("x = implement ./a.kcd");
		assertEquals(1, l.errors().size());
		assertTrue(l.errors().get(0).contains("Circular"), l.errors().get(0));
	}

	@Test
	void syntaxErrorsAreReportedWithLineAndNothingStarts() {
		Lab l = new Lab().run("print(1)\nif true\n    print(2)");
		assertEquals(List.of(), l.prints());
		assertEquals(1, l.errors().size());
		assertTrue(l.errors().get(0).contains("Syntax error"));
		assertTrue(l.errors().get(0).contains("main.kcd:2"), l.errors().get(0));
	}

	@Test
	void runtimeErrorsHaveFileAndLine() {
		Lab l = new Lab().run("x = 1\nfunction f():\n    return undefined_name + 1\nf()");
		assertEquals(1, l.errors().size());
		String e = l.errors().get(0);
		assertTrue(e.contains("main.kcd:3"), e);
		assertTrue(e.contains("Undefined variable"), e);
		assertTrue(e.contains("at f()"), e);
	}

	@Test
	void didYouMean() {
		Lab l = new Lab().run("hotbar_slot_value = 1\nprint(hotbar_slot_valu)");
		assertTrue(l.errors().get(0).contains("did you mean 'hotbar_slot_value'"), l.errors().get(0));
	}

	@Test
	void parserRejectsBadInput() {
		assertThrows(KcdError.class, () -> Parser.parse("x = = 2", "t"));
		assertThrows(KcdError.class, () -> Parser.parse("if x:\nprint(1)", "t"));
		assertThrows(KcdError.class, () -> Parser.parse("print(\"abc", "t"));
		assertFalse(Parser.parse("# only a comment\n", "t").size() > 0);
	}

	@Test
	void implementAcceptsParenthesesAndTypedVariables() {
		assertEquals(3, Parser.parse("m = implement(\"./a.kcd\")\nn = implement ./b.kcd\nimplement(\"./c.kcd\")", "t").size());
		Lab l = new Lab().run("v: list<number> = [1, 2]\nw:number = 3\nfunction f(a: number, b = 2):\n    return a + b\nprint(len(v) + w + f(1))");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("8"), l.prints());
	}

	@Test
	void matchStatement() {
		Lab l = new Lab().run("function name(n):\n    match n:\n        case 1:\n            return \"one\"\n        case 2, 3:\n            return \"few\"\n        case _:\n            return \"many\"\nprint(name(1), name(3), name(9))\nmatch = 5\nprint(match)");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("one few many", "5"), l.prints());
	}

	@Test
	void tryExceptAndJson() {
		Lab l = new Lab().run("try:\n    x = 1 / 0\nexcept err:\n    print(\"caught\")\nd = json_parse(\"\\{\\\"a\\\": [1, 2, \\{\\\"b\\\": true}], \\\"c\\\": null}\")\nprint(d.a[2].b, len(d.a))\nprint(json_stringify([1, \"x\", nil, 2.5]))");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("caught", "true 3", "[1,\"x\",null,2.5]"), l.prints());
	}

	@Test
	void mapsKeyedByObjectsAndTypedElements() {
		Lab l = new Lab().run("""
			showing = false
			lines = {}
			key = [1, 2]
			lines[key]: list<number> = 5
			if not lines[key]:
			    print("missing")
			else:
			    print("has", lines[key])
			lines[key] = 7
			print(lines[key], len(lines.keys()))
			try:
			    z = [1][key]
			except err:
			    print("bad index")
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("has 5", "7 1", "bad index"), l.prints());
	}

	@Test
	void userLineScript() {
		Lab l = new Lab().run("""
			showing = true
			lines = {}
			n = 0
			while n < 2:
			    for a in [1, 2]:
			        if not lines[a]:
			            lines[a]: line3d = [a]
			            print("create")
			        else:
			            lines[a]:append(5)
			            print("update")
			    n = n + 1
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("create", "create", "update", "update"), l.prints());
	}

	@Test
	void plainAssignmentIsGlobalLocalIsPrivate() {
		Lab l = new Lab().run("""
			count = 1
			def bump():
			    count = count + 1
			    fresh = 10
			def keep():
			    local count = 99
			    local a, b = 1, 2
			    return count + a + b
			bump()
			print(count, fresh, keep(), count)
			for i in range(1, 3):
			    pass
			print(i)
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("2 10 102 2", "3"), l.prints());
	}

	@Test
	void slashPathsAndElementEvents() {
		khoa.script.lang.Registry.get().pathRoot = n -> n.equals("Gui");
		try {
			Lab l = new Lab().run("""
				Gui = {"Btn": {"text": "hi"}}
				print(Gui//Btn.text)
				Gui\\Btn.text = "yo"
				print(Gui//Btn.text)
				a = 8
				b = 2
				print(a/b)
				Gui//Btn.On_Test:
				    print("fired")
				""");
			assertTrue(l.errors().isEmpty(), l.errors().toString());
			assertEquals(List.of("hi", "yo", "4"), l.prints());
		} finally {
			khoa.script.lang.Registry.get().pathRoot = n -> false;
		}
	}

	@Test
	void conversions() {
		Lab l = new Lab().run("""
			print(to_binary(10), to_binary(5, 8), to_hex(255), to_hex(10, 4), to_octal(8), to_base(35, 36))
			print(from_binary("0b1010"), from_hex("#ff"), from_hex("zz"), from_octal("17"), from_base("z", 36))
			print(to_number("12.5"), to_number("abc"), to_number("ff", 16), is_number("3"), is_number("x"))
			print(to_string(255, 16), to_string(true), ord("A"), chr(66), to_binary(-5))
			try:
			    to_float("nope")
			except err:
			    print("bad float")
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("1010 00000101 ff 000a 10 z", "10 255 nil 15 35", "12.5 nil 255 true false", "ff true 65 B -101", "bad float"), l.prints());
	}

	@Test
	void errorShowsSourceLine() {
		khoa.script.lang.KcdError e = new khoa.script.lang.KcdError("boom", "main.kcd", 2, 5).withHint("try again");
		String text = e.describe("a = 1\n    b = oops\nc = 3");
		assertTrue(text.contains("> 2 |     b = oops"), text);
		assertTrue(text.contains("^"), text);
		assertTrue(text.contains("hint: try again"), text);
	}

	@Test
	void utilityHelpers() {
		Lab l = new Lab().run("""
			print(find([1, 5, 9], lambda x: x > 3), find_index([1, 5, 9], lambda x: x > 6), count_if([1, 2, 3, 4], lambda x: x % 2 == 0))
			print(unique([1, 2, 1, 3]), flatten([[1, 2], [3]]), chunk([1, 2, 3, 4, 5], 2), take([1, 2, 3], 2), drop([1, 2, 3], 2))
			print(min_by(["aaa", "b", "cc"], lambda s: len(s)), max_by(["aaa", "b", "cc"], lambda s: len(s)), average([1, 2, 6]), median([5, 1, 3, 2]))
			print(merge({"a": 1}, {"b": 2}), from_pairs([["x", 1]]), group_by([1, 2, 3, 4], lambda n: n % 2))
			print(pad_left("7", 3, "0"), pad_right("ab", 4, "."), repeat_text("ab", 3), capitalize("hello"), title_case("zombie_villager"))
			print(is_empty([]), is_empty("x"), truncate("abcdefgh", 6), format_number(1234567.891, 1), format_duration(3725))
			print(wrap(370, 0, 360), approx(1, 1.00001), percent(1, 4), smoothstep(0, 10, 5), inverse_lerp(0, 10, 5))
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of(
			"5 2 2",
			"[1, 2, 3] [1, 2, 3] [[1, 2], [3, 4], [5]] [1, 2] [3]",
			"b aaa 3 2.5",
			"{\"a\": 1, \"b\": 2} {\"x\": 1} {1: [1, 3], 0: [2, 4]}",
			"007 ab.. ababab Hello Zombie Villager",
			"true false abc... 1,234,567.9 1h 2m 5s",
			"10 true 25 0.5 0.5"), l.prints());
	}

	@Test
	void coalesceVarargsAndMapItems() {
		Lab l = new Lab().run("""
			x = nil
			print(x ?? 4, 0 ?? 9)
			def total(first, *rest):
			    return first + len(rest)
			print(total(10), total(10, 1, 2, 3))
			m = {"a": 1}
			for k, v in m.items():
			    print(k, v)
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("4 0", "10 13", "a 1"), l.prints());
	}

	@Test
	void enumsAndStrictTypes() {
		Lab l = new Lab().run("""
			print(enum.entity_sort.health, enum.distance_sort.angle, TRUE, False)
			print(len(enum.easing:values()))
			try:
			    x = enum.easing.nope
			except err:
			    print("bad member")
			try:
			    y = len(5)
			except err:
			    print("bad type")
			""");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("health angle true false", "6", "bad member", "bad type"), l.prints());
	}

	@Test
	void extraIndentationIsForgiven() {
		Lab l = new Lab().run("  x = 1\n y = 2\nif x == 1:\n    print(\"a\")\n        print(\"b\")\n  print(\"c\")\nprint(x + y)");
		assertTrue(l.errors().isEmpty(), l.errors().toString());
		assertEquals(List.of("a", "b", "c", "3"), l.prints());
	}

	@Test
	void multilineCallsAndLists() {
		Lab l = new Lab().run("yaw, pitch = get_direction(\n    vec3(0, 0, 0),\n    vec3(0, 0, 5)\n)\nprint(yaw, pitch)\nxs = [\n    1,\n    2,\n]\nprint(len(xs))");
		assertEquals(List.of("0 0", "2"), l.prints());
	}

	@Test
	void formatAndRegex() {
		Lab l = new Lab().run("print(format(\"{} / {}\", 3, 4))\nprint(regex_match(\"abc123\", \"[a-z]+\\\\d+\"))\nprint(split(\"a,b\", \",\"))\nprint(join([1, 2], \"-\"))\nprint(substring(\"hello\", 1, 3))");
		assertEquals(List.of("3 / 4", "true", "[\"a\", \"b\"]", "1-2", "el"), l.prints());
	}

	@Test
	void eventHandlersAreRegisteredOnceAndFire() {
		Lab l = new Lab().run("count = 0\non_test(\"go\"):\n    global count = count + 1\n    print(\"fired\", value, count)\nprint(\"registered\")");
		assertEquals(List.of("registered"), l.prints());
		l.engine.events.fire("on_test", Map.of("value", "no"));
		l.pump();
		assertEquals(1, l.prints().size());
		l.engine.events.fire("on_test", Map.of("value", "go"));
		l.pump();
		l.engine.events.fire("on_test", Map.of("value", "go"));
		l.pump();
		assertEquals(List.of("registered", "fired go 1", "fired go 2"), l.prints());
		assertEquals(1, l.engine.events.count(l.session));
		l.session.stop();
		l.engine.events.fire("on_test", Map.of("value", "go"));
		l.pump();
		assertEquals(3, l.prints().size());
	}

	@Test
	void spawnRunsInParallelAndCountWorks() {
		Lab l = new Lab().run("def w():\n    wait_ms(50)\n    print(\"late\")\nspawn(w)\nprint(\"now\")\nwait_ms(10)\nprint(\"main\")\nprint(count([1, 2, 2], 2))\n");
		l.advanceMs(20);
		assertEquals(List.of("now", "main", "2"), l.prints());
		l.advanceMs(100);
		assertEquals(List.of("now", "main", "2", "late"), l.prints());
	}
}
