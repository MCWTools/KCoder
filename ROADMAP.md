# KCoder roadmap (updated 2026-11-11)

## Done (build OK, chua test trong game tru khi ghi chu)
- Editor: go chu (fix SDL text input), suggestion theo type, parameter hint, `implement ./x.kcd` / `implement("./x.kcd")`, typed variables (`vex: entity = ...`), Enter khong chon suggestion (chi Tab).
- Ngon ngu: indent thua / dong trong cuoi file khong con bao loi, getter entity nil-safe (tra nil neu entity nil).
- `get_scroll_delta`, `get_loaded_chunks`, `get_loaded_chunk_count`.
- DataLib: `get_entity_attribute`, `get_entity_attribute_base`, `get_entity_attributes`, `list_attributes`, `get_entity_nbt` (day du khi singleplayer/LAN host, vd allay `DuplicationCooldown`), `get_item_nbt`, `get_item_attributes`, `get_item_attribute`. Suggestion day du moi attribute trong registry.
- InfoLib (moi, chua test trong game): `get_effects`, `get_container_items`, `get_entity_vehicle`, `get_entity_passengers`, `get_clipboard`, `set_clipboard`, `get_text_width`, `get_text_height`, `rgb`, `hsv`, `lerp_color`, `time_format`.
- Nen cho GUI (chua noi vao script): `Session.onStop` (dung script = don sach GUI), `Val.KSettable` (cho `box.text = "x"`), `Dim`, `Colors`, `Shapes` trong `client/ui`, `CodeArea` doi mau/plainText cho textbox.

## GUI ve tay (da viet + build OK 2026-11-12, CHUA test trong game)
Code o `client/ui/` (UiElement, UiManager, UiScreen, UiLib, Dim, Colors, Shapes).
- create_rect / oval / line / text / image / item / textbox / button / checkbox / slider / progress_bar
- from_scale(s, offset?), anchor_x/anchor_y, parent (toa do tuong doi), z, opacity, visible, enabled, tooltip, click_through
- Thuoc tinh doc/ghi: `box.text`, `bar.value = 0.5`, ghi sai ten thuoc tinh se bao loi kem danh sach ten dung
- Event: on_click, on_submit (Enter), on_change, on_focus, on_unfocus, on_hover, on_unhover, on_render (moi frame). Trong handler co san `element`, `text`, `value`.
- ui_cursor(true/false), is_ui_cursor_open, clear_ui, get_ui_elements, get_ui_hovered
- animate(el, prop, to, seconds, easing) va el:animate(...); easing linear/ease_in/ease_out/ease_in_out/back/bounce; chay duoc cho so, kich thuoc, mau
- show_toast(text, seconds, color), load_texture(path) (PNG trong thu muc script)
- Dung script = tu xoa het GUI (Session.onStop)
- Vi du: `Example/features/gui.kcd` (folder Example chi tao 1 lan nen da copy san vao `kcd/GuiDemo/main.kcd` trong profile 26.3)
- Can xem lai khi test: HUD co ve duoc khong, bam/go trong UiScreen, Enter trong textbox, hover/tooltip, load_texture (API chua kiem chung), on_render co lag khong.

## Da lam them (2026-11-12, build OK + unit test, chua test trong game)
- `json_parse`, `json_stringify`, `save_data`, `load_data`, `delete_data`, `list_data_keys` (luu o `kcd/saved_data.json`)
- `match` / `case` (`case 1, 2:`, `case _:` hoac `else:`), co test. `try / catch / except / finally` thi ngon ngu da co san tu truoc.
- `get_sign_text`, `get_scoreboard_title`, `get_scoreboard_lines`, `get_block_entity_nbt`
- GUI: `draggable` (+ `on_drag`), `clip`, `scrollable` + `scroll_y` (lan chuot trong panel)
- Editor: Find / Replace (Ctrl+F, Ctrl+H, Enter / F3 / Shift+F3, Esc dong), highlight `match` / `case`
- Don log debug tam
- Test moi: `ClientSignaturesTest` (parse moi chu ky ham trong code client), `matchStatement`, `tryExceptAndJson`
- `PROJECT_STATUS.md` da cap nhat

## Chua lam
- Code folding, debugger / profiler UI (engine co san, chua co UI)
- `get_bossbars` (can accessor vao BossHealthOverlay.events), `screenshot()`
- Thu vien `ui_kit.kcd` (cua so keo tha, dropdown, tab) va nut "Preview UI" trong editor
- Preview/hover tooltip trong editor

## Chua kiem chung trong game
Parameter hint, goi y theo type, `implement("` path, getter nil-safe, scroll/chunk, toan bo DataLib va InfoLib, doc NBT allay tu server tich hop.

## Can test dau tien khi vao game
1. Chay `GuiDemo`, bam H: HUD ve duoc khong, go chu trong textbox, Enter ra toast, nut Fill, tick checkbox.
2. Editor: Ctrl+F / Ctrl+H, tab/Enter chuyen o, Replace All.
3. `print(get_entity_nbt(allay))` tren singleplayer.
4. Dung script -> GUI bien mat.


## Cap nhat (lan lam them: 3D, debugger, folding)

Da lam:
- 3D: `overlay_block(block, shape, fill, fill_alpha, outline, outline_alpha, thickness)` (shape: box / hitbox / model / collision),
  `box_overlay` (alias `box_outline`), `line_3d` (set_endpoint, set_origin, color, appearance), `plot_function`, `plot_parametric`,
  `plot_surface`, `plot_values`, `plot_points`, `image_3d`, `delete(x)`, `clear_overlays()`, namespace `color.rgb/rgba/hsv/hex/lerp`.
- Raycast: `block_raycast`, `entity_raycast`, `mixed_raycast`; `get_blocks_around(range, id?)`.
- `get_bossbars()`, `screenshot(name?)`.
- Editor: code folding (click mui ten o le, Ctrl+Shift+[ gap khoi, Ctrl+Shift+] mo het, Ctrl+Shift+- gap het),
  breakpoint (click le trai so dong), tab Debug (Continue / Step over / into / out + bien) va tab Profiler, nut "Preview UI".
- `features/ui_kit.kcd` (window keo tha, tabs, dropdown) + `ui_kit_demo.kcd`, `features/draw3d.kcd`.

Chua kiem chung trong game: toan bo phan 3D (vi tri poseStack, depth, image_3d), screenshot, bossbar (reflection),
breakpoint/pause, folding.

- `enum.<nhom>.<gia tri>` (Lua-style): enum.entity_sort, overlay_shape, easing, block_face, plane, image_facing; editor goi y; sai gia tri bao loi.
