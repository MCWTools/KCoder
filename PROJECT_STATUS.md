# KCoder 26.3 - project status

Client-side Fabric mod: an in-game scripting platform with its own Python-like language (`.kcd`).

## Environment (from the project files)
- Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3 (template version), Loom 1.18.x, Gradle 9.7.1, JDK 25, no mappings line.
- Mod id `khoascript`, package `khoa.script`, jar `build/libs/kcoder-1.0.0.jar`.

## 2026-11-14: Switched to KhoaAPILibs (1.1.0)
- KCoder now REQUIRES the KhoaAPILibs mod (modid `khoaapi` >= 1.0.0, https://github.com/MCWTools/KhoaAPILibs). The jar is compiled against from `libs/KhoaAPILibs-1.0.0.jar` (not bundled); players install `KhoaAPILibs-<version>.jar` next to KCoder.
- Moved to the library: hitbox scaling + real box (`khoa.api.hitbox.KhoaHitbox`, its own Entity mixin and the attack / interact cancel), nearest-angle-to-aim maths (`khoa.api.aim.KhoaAim`), distance-to-box (`KhoaGeometry`). KCoder's `EntityMixin` and `HitboxScale` were deleted; `MultiPlayerGameModeMixin` only feeds on_craft now. `hitbox_entity`, `on_hit` and `get_nearest_angle_to_aim` keep the same script API.
- Verified: `gradlew build` (see below). NOT verified in game: the library mixins applying next to KCoder's.

## Layout
- `src/main/java/khoa/script/lang` - lexer, parser, interpreter, coroutines, scheduler, events, sessions, debugger, log.
- `src/main/java/khoa/script/lib` - Core (math/vector/string/list/map) and Runtime (print/wait/timers/files) builtins.
- `src/main/java/khoa/script/ExampleFiles.java` - the files of the auto-created `Example` script folder.
- `src/client/java/khoa/script/client/game` - Minecraft API (entity, player, world, camera, inventory, input, action, client/output).
- `src/client/java/khoa/script/client/mixin` - key/mouse priority, frame pump, pick invoker, click-count accessor.
- `src/client/java/khoa/script/client/gui` - HomeScreen, FolderScreen, CodeArea, BottomPanel (log + console), Suggest, Highlighter.
- `<game>/kcd/<Name>/` - script folders (a directory with a `.scriptfolder` marker file).

## Usage
- `/kcoder` opens the GUI, `/kcoder run|stop <folder>`, `/kcoder multiplayer true|false`.
- Script hotkeys (`on_key_press("G"):`) are read in `KeyboardHandler.keyPress` / `MouseHandler.onButton` BEFORE Minecraft's Controls; a claimed key is swallowed. Not active while a screen is open; ESC is never claimed.
- On multiplayer servers actions/aim/chat are blocked unless `/kcoder multiplayer true`.

## Verified (2026-11-12)
- `gradlew.bat build` (compile main+client, unit tests, jar): BUILD SUCCESSFUL.
- Unit tests: language (incl. `match`, `try/except`, typed variables), coroutines, example files parse, language tour, JSON, and `ClientSignaturesTest` (every `R.def` / `R.method` signature in the client code parses, so a typo cannot crash startup).

## Added since the first version
- `game/DataLib`: entity / item attributes, entity + block entity + item NBT (full data in singleplayer through the integrated server).
- `game/InfoLib`: effects, container items, vehicle / passengers, clipboard, sign text, sidebar scoreboard, colors (`rgb`, `hsv`, `lerp_color`), `save_data` / `load_data` (kcd/saved_data.json).
- `lib/JsonLib`: `json_parse`, `json_stringify`.
- `match` / `case` statement.
- `client/ui`: script GUI (rect, oval, line, text, image, item icon, textbox, button, checkbox, slider, progress bar), HUD layer + `ui_cursor` screen, events (`on_click`, `on_submit`, `on_change`, `on_focus`, `on_unfocus`, `on_hover`, `on_unhover`, `on_drag`, `on_render`), animations, toasts, dragging, clip + scroll, `load_texture`. Everything a script creates is removed when the script stops (`Session.onStop`).
- Editor: parameter hints, type-aware suggestions, find / replace (Ctrl+F, Ctrl+H, F3).
- See `ROADMAP.md` for the task list.

## NOT verified in game yet
- All Minecraft API builtins, the mixins (key/mouse claim, frame pump, pick, click count), event firing, the GUI screens, console, suggestion popup, parameter hints, find / replace bar, the whole script GUI (HUD drawing, ui_cursor input, textbox typing, animations, load_texture), DataLib / InfoLib.
- Not implemented: code folding, debugger / profiler UI (engine parts exist), boss bars, screenshots, hover tooltips in the editor.

## 2026-11-12 update (built + unit-tested, NOT verified in game unless stated)
- Visual GUI editor (/kcoder gui): Screen Folders, frames, text, buttons, images, textboxes; explorer, properties panel, undo/redo, kcoder/gui.json. Script access: ScreenFolder.Name.prop or ScreenFolder/Name.prop; element events: ScreenFolder.Button.On_Click:
- New GUI events: on_press, on_release, on_right_click, on_double_click, on_scroll, on_enter, on_type, on_drag_end.
- Language: `local` keyword (plain assignment now writes the existing/global variable), `??`, *rest parameters, typed element declarations (lines[k]: line3d = ...), map.items()/clear()/copy().
- Library: convert (to_number, to_binary, to_hex, from_*, ord/chr ...), util (find, unique, group_by, pad_left, format_number, ...), get_container()/get_container_title(), line3d set_points.
- Errors show the source line with a caret; /kcoder docs writes kcoder/KCODER_REFERENCE.txt (verified in game: file generated, 406 functions).

## 2026-11-12 (later): chat / input / packet / pack features
- Added (build + unit tests pass, NOT yet verified in game): open_chat_ui, type_text, press_key (enum.key), press_keybind, list_keybinds, run_command, action_bar/client_chat aliases, get_chat/delete_chat/clear_chat/set_chat_limit (ChatComponentMixin records into its own buffer, ChatComponentAccess; vanilla history limit is left to KhoaTools Better Chat), get_suggestion (Brigadier dispatcher, 10k cap), move_cursor (SDL warp), get_velocity/set_client_velocity/add_client_velocity, spawn(fn, ...), count(x, v?), on_key_hold(key, seconds), look_at_smooth accepts a function target, packet.send/record/stoprec (ConnectionMixin), Script Folder export/import as .kcdpack (ZIP, SDL file dialog; HomeScreen buttons, /kcoder export|import).
- Name note: `type(x)` already means type-of, so typing into the UI is `type_text`.
- Risk to check first in game: mixins ChatComponentMixin / ConnectionMixin apply (defaultRequire=1), SDL dialogs, press_key on other mods' bindings.
- Chat history: KCoder keeps its own per-server history in <game>/kcoder/chat/<server>.txt (ChatLog: load on JOIN, save every ~5s and on DISCONNECT). If the `khoatools` mod is loaded, lines its Better Chat cache replays within 5 s of joining are matched against our history and not duplicated. Built, not verified in game.

## 2026-11-14: hitbox_entity / on_hit / get_nearest_angle_to_aim (built + unit tests pass, NOT verified in game)
- `hitbox_entity(entity, scale=1) -> hitbox`: EntityMixin (Entity.getBoundingBox RETURN) returns the scaled box on the client only; so crosshair pick, F3+B and hitbox mods see it. `HitboxLib.raw` reads the vanilla box. Hitbox members: entity, scale (settable), active, real_box, box, remove(). Removed when the script stops.
- `hitbox.On_Hit:` / `on_hit(box):` (gives hitbox, entity, real, distance). MultiPlayerGameModeMixin cancels `attack` and `interact` when the ray does not cross the REAL box (nothing is sent to the server); a hit inside the real box is sent normally and has `real = true`.
- `get_nearest_angle_to_aim(entity|hitbox) -> rotation` (perm `look`): closest-in-angle point of the real box (12-edge search), nudged 0.02 inside; returns the current rotation if already aimed at it.
- Verified with javap on 26.3 jars: Entity.getBoundingBox (public final, returns field bb), MultiPlayerGameMode.attack/interact descriptors, F3+B (EntityHitboxDebugRenderer) calls Entity.getBoundingBox. Risk to check first in game: EntityMixin + the two new injectors apply (defaultRequire=1).
- Cannot resize your own hitbox (collision). Scale range 0..32.

## 2026-11-14 (later): chat_prompt, run_client_command, features/locator.kcd (built + tests pass, NOT verified in game)
- `chat_prompt(question, timeout=60) -> string|nil` (ChatPrompt, ClientSendMessageEvents.ALLOW_CHAT): prints the question and swallows the player's next chat line (never sent to the server). Cleaned up when the script stops.
- `run_client_command(command) -> boolean`: executes a Fabric client command synchronously through ClientCommandInternals (feedback lands in chat/get_chat at once).
- `features/locator.kcd`: /locator -> asks the name, /pw cloudstown2, locatorcoord at 2.5s and +1.5s, parses "Triangulated location", locatorfinder + pwnearest, /pw <nearest>. Needs KhoaTools.
