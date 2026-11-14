package khoa.script;

import java.util.LinkedHashMap;
import java.util.Map;

/** The files of the "Example" script folder that is created on first start. Kept in main so the unit tests can parse every one of them. */
public final class ExampleFiles {
	public static final Map<String, String> FILES = new LinkedHashMap<>();

	static {
		FILES.put("main.kcd", """
			# ============================================================
			#  KCoder example folder
			#  Select "Example" in /kcoder and press Run. Then, in game:
			#     G  smoothly look at the nearest entity (100 small steps)
			#     H  feed the nearest allay (needs an amethyst shard in the hotbar)
			#     J  show your status in chat
			#     K  run the language tour (see the Log panel)
			#  Script hotkeys are read BEFORE Minecraft's Controls, so a key used
			#  here will not trigger the normal Minecraft action while this runs.
			#  Every .kcd file in the root of the folder starts when you press Run.
			#  Files in sub folders only run when another file does `implement` on them.
			# ============================================================

			aim = implement ./features/aim.kcd
			allay = implement ./features/allay.kcd
			tour = implement ./features/basics.kcd
			status = implement ./features/status.kcd

			print("Example loaded. Keys: G aim, H feed allay, J status, K tour")

			on_key_press("G"):
			    aim:aim_nearest()

			on_key_press("H"):
			    allay:feed_nearest()

			on_key_press("J"):
			    status:show()

			on_key_press("K"):
			    tour:run()
			""");

		FILES.put("events.kcd", """
			# Events: a block that starts with on_xxx(...): runs every time it happens.
			# A handler that is still running (it has a wait inside) ignores new events.

			on_damage():
			    print("Ouch! -{amount} health, {health} left")

			on_join():
			    print("Joined a world")

			on_chat():
			    if contains(message, "khoa"):
			        print("Someone said my name: {message}")

			# on_interval(5) runs every 5 seconds. Remove the # to try it:
			# on_interval(5):
			#     print("tick tock, tick {get_tick()}")
			""");

		FILES.put("features/aim.kcd", """
			# Smooth aiming, written out by hand.
			# Each step recalculates the direction, so a moving target is followed,
			# and the last step lands exactly on the target.

			function aim_nearest():
			    target = get_nearest_entity(nil, 30)
			    if target == nil:
			        print("No entity within 30 blocks")
			        return
			    print("Aiming at {target.name} ({target.type}), {target.distance} blocks away")
			    for n in range(1, 100):
			        wait(random(1, 3) / 1000)
			        yaw, pitch = get_direction(get_player_eye_pos(), target.center)
			        turn_towards(yaw, pitch, 1 / (101 - n))
			    print("Done. Crosshair on target: {is_looking_at(target)}")

			# The same thing in one line (built in):
			function aim_nearest_fast():
			    target = get_nearest_entity(nil, 30)
			    if target != nil:
			        look_at_smooth(target, 100, 1, 3)
			""");

		FILES.put("features/allay.kcd", """
			# Feed every allay within 3.5 blocks of your eyes.

			function feed_nearest():
			    allays = get_entities_in_range(minecraft:allay, 3.5)
			    if len(allays) == 0:
			        print("No allay within 3.5 blocks")
			        return
			    slot = find_hotbar_item(minecraft:amethyst_shard)
			    if slot == 0:
			        print("Put an amethyst shard in your hotbar first")
			        return
			    hotbar_select(slot)
			    for a in allays:
			        look_at_smooth(a, 20, 1, 2)
			        wait(0.1)
			        interact_entity(a)
			        wait(0.5)
			    print("Fed {len(allays)} allay(s)")
			""");

		FILES.put("features/gui.kcd", """
			# GUI demo. Run this script, then press H to show / hide a small window.
			# Elements are drawn on the HUD. ui_cursor(true) gives you a mouse so you can click and type (Esc closes it).

			panel = create_rect(from_scale(0.5), from_scale(0.5), 220, 112, "#161A21")
			panel.anchor_x = 0.5
			panel.anchor_y = 0.5
			panel.radius = 6
			panel.border_width = 1
			panel.border_color = "#2B3240"
			panel.visible = false

			title = create_text(10, 8, "GUI demo", "white")
			title.parent = panel
			box = create_textbox(10, 24, 200, 16, "type something, press Enter")
			box.parent = panel
			check = create_checkbox(10, 48, "A checkbox")
			check.parent = panel
			bar = create_progress_bar(10, 70, 200, 10, 0)
			bar.parent = panel
			bar.show_text = true
			btn = create_button(10, 88, 90, 16, "Fill the bar")
			btn.parent = panel

			on_key_press("H"):
			    panel.toggle()
			    if panel.visible:
			        panel.opacity = 0
			        animate(panel, "opacity", 1, 0.25, "ease_out")
			        ui_cursor(true)
			    else:
			        ui_cursor(false)

			on_submit(box):
			    show_toast("You typed: {text}", 3, "green")

			on_click(btn):
			    bar.value = 0
			    animate(bar, "value", 1, 1.5, "ease_in_out")

			on_change(check):
			    print("checked = {value}")
			""");

		FILES.put("features/ui_kit.kcd", """
			# ui_kit.kcd - ready-made GUI parts: draggable windows, tabs and dropdowns.
			# Use it from another file:
			#     kit = implement ./features/ui_kit.kcd
			#     win = kit:window("Settings", 100, 80, 220, 140)
			# A window is a map: win.frame (drag it by any empty spot), win.title, win.close, win.body.
			# Put your own elements inside with element.parent = win.body

			function window(title, x, y, w, h):
			    frame = create_rect(x, y, w, h, "#161A21")
			    frame.radius = 6
			    frame.border_width = 1
			    frame.border_color = "#2B3240"
			    frame.draggable = true
			    bar = create_rect(0, 0, w, 18, "#222A36")
			    bar.parent = frame
			    bar.click_through = true
			    label = create_text(8, 5, title, "white")
			    label.parent = frame
			    label.click_through = true
			    close = create_button(w - 18, 2, 14, 14, "x")
			    close.parent = frame
			    body = create_rect(0, 18, w, h - 18, "#00000000")
			    body.parent = frame
			    body.click_through = true
			    return {"frame": frame, "bar": bar, "title": label, "close": close, "body": body}

			# Tab buttons with one page each. Show a page with kit:select_tab(t, index).
			function tabs(parent, names, x, y, w, h):
			    buttons = []
			    pages = []
			    n = len(names)
			    bw = w / n
			    for i in range(0, n - 1):
			        b = create_button(x + i * bw, y, bw - 2, 16, names[i])
			        b.parent = parent
			        buttons.append(b)
			        page = create_rect(x, y + 20, w, h - 20, "#00000000")
			        page.parent = parent
			        page.click_through = true
			        page.visible = i == 0
			        pages.append(page)
			    return {"buttons": buttons, "pages": pages}

			function select_tab(t, index):
			    for i in range(0, len(t.pages) - 1):
			        t.pages[i].visible = i == index

			# A button that opens a list. Use kit:dropdown_toggle(d) when d.head is clicked and kit:dropdown_pick(d, i) when d.entries[i] is clicked.
			function dropdown(parent, x, y, w, options):
			    head = create_button(x, y, w, 16, options[0])
			    head.parent = parent
			    entries = []
			    for i in range(0, len(options) - 1):
			        entry = create_button(x, y + 16 + i * 16, w, 16, options[i])
			        entry.parent = parent
			        entry.visible = false
			        entry.z = 50
			        entries.append(entry)
			    return {"head": head, "entries": entries, "options": options, "value": options[0], "index": 0}

			function dropdown_toggle(d):
			    for entry in d.entries:
			        entry.visible = not entry.visible

			function dropdown_pick(d, i):
			    d.value = d.options[i]
			    d.index = i
			    d.head.text = d.options[i]
			    for entry in d.entries:
			        entry.visible = false
			""");

		FILES.put("features/ui_kit_demo.kcd", """
			# Demo of ui_kit.kcd: a draggable window with tabs and a dropdown. Press U to show or hide it.
			kit = implement ./ui_kit.kcd

			win = kit:window("UI kit demo", 120, 70, 240, 150)
			win.frame.visible = false
			t = kit:tabs(win.body, ["Main", "Options"], 8, 4, 224, 120)
			hello = create_text(4, 6, "Drag the window by its edge", "white")
			hello.parent = t.pages[0]
			d = kit:dropdown(t.pages[1], 4, 6, 120, ["Easy", "Normal", "Hard"])

			on_key_press("U"):
			    win.frame.toggle()
			    ui_cursor(win.frame.visible)

			on_click(win.close):
			    win.frame.hide()
			    ui_cursor(false)

			on_click(t.buttons[0]):
			    kit:select_tab(t, 0)

			on_click(t.buttons[1]):
			    kit:select_tab(t, 1)

			on_click(d.head):
			    kit:dropdown_toggle(d)

			on_click(d.entries[0]):
			    kit:dropdown_pick(d, 0)

			on_click(d.entries[1]):
			    kit:dropdown_pick(d, 1)

			on_click(d.entries[2]):
			    kit:dropdown_pick(d, 2)
			    show_toast("Hard mode!", 2, "red")
			""");

		FILES.put("features/draw3d.kcd", """
			# 3D drawing demo. Run it, then look at blocks. Press K to clear everything.
			# color.rgb(r, g, b) packs a color; alpha values go from 0 (invisible) to 1 (solid); thickness is in blocks.

			# 1) Outline whatever block you look at, following its real model
			state = {"last": nil}
			on_render():
			    b = get_crosshair_block()
			    if state.last != nil:
			        state.last:delete()
			        state.last = nil
			    if b != nil:
			        state.last = overlay_block(b, "model", color.rgb(255, 200, 0), 0.2, color.rgb(255, 255, 255), 1, 0.02)

			# 2) A free box and a line to it
			box = box_overlay(vec3(0.5, 100, 0.5), vec3(2, 1, 0.5), color.rgb(255, 0, 0), 0.3, color.rgb(255, 255, 0), 1, 0.05)
			rope = line_3d(get_player_position(), vec3(0.5, 100, 0.5), color.rgb(0, 200, 255), 1, 0.05)

			# 3) Graphs
			wave = plot_function(vec3(0, 102, 0), lambda x: sin(x) * 2, -10, 10, 200, color.rgb(0, 255, 120), 1, 0.06)
			helix = plot_parametric(vec3(8, 100, 0), lambda t: vec3(cos(t) * 2, t * 0.3, sin(t) * 2), 0, 25, 300, color.rgb(255, 100, 255))
			surf = plot_surface(vec3(0, 105, 10), lambda x, z: sin(x) * cos(z), -5, 5, -5, 5, 24, color.rgb(255, 160, 0), 1, 0.03)

			# 4) The ray from your eyes
			on_key_press("R"):
			    r = mixed_raycast(50)
			    print("hit {r.type} at distance {r.distance}")

			on_key_press("K"):
			    clear_overlays()
			""");

		FILES.put("features/status.kcd", """
			# Reading information about the player and the world.

			function show():
			    pos = get_player_position()
			    print("Position: {pos}")
			    print("Health: {get_player_health()} / {get_player_max_health()}")
			    print("Dimension: {get_dimension()}, biome: {get_biome()}")
			    held = get_held_item()
			    print("Holding: {held.id} x{held.count}")
			    block = get_crosshair_block()
			    if block != nil:
			        print("Looking at block: {block.id} at {block.pos}")
			    chat("[KCoder] health {get_player_health()}, food {get_player_hunger()}")
			    actionbar("Health {get_player_health()}")
			""");

		FILES.put("features/basics.kcd", """
			# A tour of the language. Press K in game (or run it from the console:
			#   cd ./Example/features/
			#   ./basics.kcd:run()
			# ). Everything it prints shows in the Log panel.

			function stats(numbers):
			    # A function can return several values.
			    return min(numbers), max(numbers), sum(numbers) / len(numbers)

			function countdown(n):
			    while n > 0:
			        print("  {n}")
			        n = n - 1
			        wait_tick(1)

			function run():
			    print("--- variables, math, strings ---")
			    x = 10
			    y = 3.5
			    print("x + y = {x + y}, x * y = {x * y}, x / 4 = {x / 4}")
			    name = "Steve"
			    print("Hello " + name + ", upper: " + upper(name) + ", length {len(name)}")

			    print("--- if / elif / else ---")
			    if x > 20:
			        print("big")
			    elif x > 5:
			        print("medium")
			    else:
			        print("small")

			    print("--- lists ---")
			    items = ["apple", "stone", "diamond"]
			    append(items, "gold")
			    for item in items:
			        if item == "stone":
			            continue
			        print("  item: {item}")

			    print("--- ranges are inclusive ---")
			    total = 0
			    for i in range(1, 5):
			        total = total + i
			    print("1+2+3+4+5 = {total}")

			    print("--- several return values ---")
			    low, high, average = stats([4, 8, 15, 16, 23, 42])
			    print("min {low}, max {high}, average {average}")

			    print("--- vectors ---")
			    a = vec3(0, 0, 0)
			    b = vec3(3, 4, 0)
			    print("distance {distance(a, b)}, middle {(a + b) / 2}, length of b {magnitude(b)}")
			    yaw, pitch = get_direction(a, vec3(0, 0, 10))
			    print("direction to +Z: yaw {yaw}, pitch {pitch}")

			    print("--- break and wait ---")
			    for i in range(1, 100):
			        if i > 3:
			            break
			        print("  i = {i}")
			        wait(0.2)

			    print("--- wait_tick ---")
			    countdown(3)
			    print("Tour finished")
			""");

		FILES.put("features/screen_folder.kcd", """
			# Uses a GUI built in the visual editor (/kcoder gui). In the editor make:
			#   a Screen Folder named ScreenFolder
			#   inside it: a Text named MoneyText, a Button named Buy, a Textbox named Amount
			# Then run this file. Names are the ones in the Explorer; ScreenFolder//Buy and ScreenFolder\\Buy also work.
			ui_cursor(true)              # show the mouse so the GUI can be clicked
			money = 100
			ScreenFolder.MoneyText.text = "Money: {money}"

			ScreenFolder.Buy.On_Click:
			    n = to_number(ScreenFolder.Amount.text) ?? 1
			    money -= n * 10
			    ScreenFolder.MoneyText.text = "Money: {format_number(money)}"
			    show_toast("Bought {n}")

			ScreenFolder.Amount.On_Type:
			    ScreenFolder.Buy.text = "Buy " + (to_number(ScreenFolder.Amount.text) ?? "?")

			on_key_press("H"):
			    ScreenFolder.enabled = not ScreenFolder.enabled
			""");

		FILES.put("features/pricelist.kcd", """
			# Shop price saver (NOT run by main.kcd: it needs a Screen Folder named ScreenFolder with a Button named Button,
			# made in /kcoder gui. Copy this file into your own script folder, or implement it from main.kcd).
			#
			# Open a shop menu, then click the button. The script reads every item of the open container, saves the full dump to
			# kcoder/output/<script folder>/Container.json, and writes one line per item with its Buy Price to Pricelist.txt:
			#     Warped Nylium | $6.00
			# Items already in Pricelist.txt are not written again.
			ScreenFolder.Button.On_Click:
			    c = get_container()
			    if c == nil:
			        notify("Pricelist", "Open a shop menu first")
			        return
			    known = {}
			    for line in read_output_lines("Pricelist.txt"):
			        known[line.split(" | ")[0]] = true
			    dump = []
			    added = 0
			    for entry in c.items:
			        if entry.empty:
			            continue
			        dump.append({"slot": entry.slot, "id": entry.id, "name": entry.name, "count": entry.count, "lore": entry.lore})
			        price = nil
			        i = 0
			        while i < len(entry.lore) - 1 and price == nil:
			            if entry.lore[i].contains("Buy Price"):
			                j = entry.lore[i + 1].index_of("$")
			                if j >= 0:
			                    price = entry.lore[i + 1].substring(j).trim()
			            i = i + 1
			        if price != nil and not known.has(entry.name):
			            append_output_line("Pricelist.txt", "{entry.name} | {price}")
			            known[entry.name] = true
			            added = added + 1
			    export_json("Container.json", dump)
			    notify("Pricelist", "{len(dump)} items read, {added} new price(s) saved")
			""");

		FILES.put("features/locator.kcd", """
			# /locator <- finds a Java player in the overworld by triangulating the locator bar with KhoaTools, then teleports you to the nearest pw.
			# Needs the KhoaTools mod (Locator Coord, Locator Finder, PW Nearest). NOT run by main.kcd: implement it or copy it into your folder.
			# Flow: /pw cloudstown2 (it teleports after 3s) -> sample 1 at +2.5s (still at the old spot) -> sample 2 at +4s (new spot)
			# -> read "Triangulated location" -> Locator Finder line + PW Nearest -> /pw <nearest>.

			TELEPORT_PW = "cloudstown2"
			SAMPLE_1_AT = 2.5
			SAMPLE_2_AFTER = 1.5

			cmd = register_command("/locator")

			# Runs a KhoaTools command and returns the newest chat line it printed ("" when nothing came).
			function khoa(text):
			    run_client_command("khoatoolsclient " + text)
			    lines = get_chat(6)
			    i = len(lines) - 1
			    while i >= 0:
			        if lines[i].contains("Locator") or lines[i].contains("Triangulated") or lines[i].contains("PW g"):
			            return lines[i]
			        i = i - 1
			    return ""

			function bad_sample(line):
			    return line == "" or line.contains("không hiện trên locator bar") or line.contains("không đọc được") or line.contains("Không thấy player")

			function find_online(name):
			    for p in get_online_players():
			        if lower(p) == lower(name):
			            return p
			    return nil

			function nearest_pw():
			    lines = get_chat(12)
			    start = -1
			    i = 0
			    while i < len(lines):
			        if lines[i].contains("PW g") and lines[i].contains("nhất"):
			            start = i
			        i = i + 1
			    if start < 0:
			        return nil
			    text = ""
			    i = start
			    while i < len(lines):
			        text = text + lines[i] + " "
			        i = i + 1
			    found = regex_find(text, "/pw [A-Za-z0-9_]+")
			    if len(found) == 0:
			        return nil
			    return substring(found[0], 4)

			on_command_run("/locator"):
			    typed = chat_prompt("Name ng chơi (java, overworld):", 60)
			    if typed == nil or trim(typed) == "":
			        chat("Locator: huỷ (không nhập tên).")
			        return
			    name = find_online(trim(typed))
			    if name == nil:
			        chat("Locator: không thấy player '" + trim(typed) + "' (phải đang online, tab list).")
			        return

			    khoa("locatorcoord " + name + " clear")
			    send_command("pw " + TELEPORT_PW)
			    wait(SAMPLE_1_AT)
			    first = khoa("locatorcoord " + name)
			    if bad_sample(first):
			        chat("Locator: " + name + " đã tắt locator bar hoặc không ở overworld -> huỷ.")
			        return
			    wait(SAMPLE_2_AFTER)
			    second = khoa("locatorcoord " + name)
			    if bad_sample(second) or second.contains("dimension"):
			        chat("Locator: " + name + " đã tắt locator bar hoặc không ở overworld -> huỷ.")
			        return
			    at = second.index_of("Triangulated")
			    if at < 0:
			        chat("Locator: chưa tính được vị trí: " + second)
			        return
			    nums = regex_find(substring(second, at), "-?[0-9]+")
			    x = to_number(nums[0])
			    z = to_number(nums[1])
			    chat("Locator: " + name + " ở khoảng " + x + " " + z)

			    khoa("locatorfinder " + name)
			    khoa("pwnearest " + x + " 64 " + z)
			    pw = nearest_pw()
			    if pw == nil:
			        chat("Locator: không tìm được pw gần nhất.")
			        return
			    chat("Locator: tp tới pw " + pw)
			    send_command("pw " + pw)
			""");

		FILES.put("features/commands.kcd", """
			# Script commands (NOT run by main.kcd; implement it or copy it into your folder).
			# Type /greet or /greet world in chat. The command disappears when the script stops.
			cmd = register_command("/greet")
			if cmd != nil:
			    register_suggestion(cmd, "world")
			    register_suggestion(cmd, "everyone")

			on_command_run("/greet"):
			    print("you ran", command)

			on_command_run("/greet world"):
			    actionbar("Hello, world!")
			""");

		FILES.put("features/retexture.kcd", """
			# Change a model and a texture from a script (NOT run by main.kcd; copy it into your folder).
			# Put stone.png (16x16) next to this script first. Everything goes into resourcepacks/kcoder_demo.
			pack = create_resource_pack("demo", "Made by KCoder")
			pack_set_texture("demo", "minecraft:block/stone", "stone.png")
			# make the stick look like a diamond sword
			pack_set_item_model("demo", "minecraft:stick", "minecraft:item/diamond_sword")
			# read a vanilla model, edit it, save it back
			model = json_parse(read_resource("minecraft:models/item/diamond_sword.json"))
			print(model)
			enable_resource_pack("demo")
			print("enabled:", get_enabled_resource_packs())
			""");

		FILES.put("features/crafting.kcd", """
			# Crafting demo (NOT run by main.kcd; copy it into your folder). Open a crafting table (or your inventory), then press K.
			on_key_press("K"):
			    recipes = get_recipes("minecraft:stick")
			    if len(recipes) == 0:
			        print("No stick recipe in your recipe book")
			        return
			    r = recipes[0]
			    print("recipe", r.index, r.ingredients, "craftable:", r.craftable)
			    print("missing:", get_missing_ingredients(r))
			    if craft(r, true):
			        wait_tick(3)
			        out = get_crafting_out_slot()
			        if out != nil and not out.empty:
			            out.throw_all()

			on_craft():
			    print("crafted {count} x {id} ({method})")

			on_item_pickup():
			    print("picked up {count} x {id}")
			""");

		FILES.put("README.kcd", """
			# KCoder quick reference (this file only has comments, so running it does nothing)
			#
			# /kcoder                          open the manager
			# /kcoder run <folder>             run a script folder
			# /kcoder gui                      visual GUI editor (Screen Folders, buttons, images ...)
			# /kcoder docs                     writes kcoder/KCODER_REFERENCE.txt with every function
			# /kcoder stop [folder]            stop one folder (or all of them)
			# /kcoder multiplayer true|false   allow clicks / aiming / chat on servers
			#
			# Files: .kcd. Indentation makes blocks (like Python). # starts a comment.
			# Hotkeys: on_key_press("G"):   on_key_press("ctrl+shift+K"):   on_key_press("MOUSE_RIGHT"):
			# Waiting: wait(seconds)  wait_ms(ms)  wait_tick(n)  wait_frame(n)  wait_until(fn)
			# Modules: name = implement ./features/file.kcd   then   name:function()
			# Editor: Ctrl+Space suggestions, Ctrl+S save, Ctrl+/ comment.
			# Console tab: cd ./Example/features/   then   ./basics.kcd:run()
			# Files you write go to kcoder/output/<script folder>/: write_output, append_output_line, insert_output, read_output, export_txt, export_json.
			# Commands: register_command("/abc"), register_suggestion("/abc", "def"), on_command_run("/abc def"):
			""");
	}

	private ExampleFiles() {
	}
}
