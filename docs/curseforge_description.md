# Micra Drone — CurseForge project description (draft)

Paste the section below into the CurseForge project description editor (Markdown supported).
Suggested categories/tags: Technology, Miscellaneous, Adventure. MC version: 1.21.1. Mod loader: NeoForge 21.1.238.

---

## Micra Drone

**Write code, farm faster.** Micra Drone adds a programmable farming drone to
Minecraft: craft a Drone Controller, write a script in a small Python-like
language, and watch the drone till, plant, and harvest your fields on its
own. Inspired by the Steam game
[*The Farmer Was Replaced*](https://store.steampowered.com/app/2060160/The_Farmer_Was_Replaced/).

### Features

- **A real (tiny) programming language** — variables, `if`/`elif`/`else`,
  `while`, `for i in range(...)`, `break`/`continue`, your own functions with
  `def`/`return`, comments, and a handful of drone commands
  (`move`, `till`, `plant`, `harvest`, `can_harvest`, `is_rotten`, `measure`,
  `get_points`, `print`, `do_a_flip`, ...), plus perception commands that read
  the world around the drone (`get_ground`, `get_block_above`, `get_time`,
  `get_weather`, `get_biome`, `get_light`, `get_plot_id`). Scripts are written on a
  rewritable **Script Scroll** item, or in the in-game IDE below. Indentation
  works the way it does in Python, except that a script shifted uniformly to
  the right still runs — so pasting from a chat window or a wiki doesn't
  strand you on an indentation error before the first line.
- **Lists, dicts and sets** — `items = [1, 2, 3]`, `costs = {"wheat": 20}`,
  `seen = set()`, indexing (`items[0]`, nested as deep as you like), looping
  straight over a collection, `x in items`, and the methods to build one up
  (`append`, `pop`, `remove`, `add`, `keys`, `values`, `get`, `clear`) — plus
  `len`, `abs`, `min`, `max`, `random` and `str`. Enough to count things,
  remember what you've seen, and write the kind of script that doesn't need to
  know its answer in advance.
- **An in-game IDE that behaves like one** — syntax highlighting in a Monokai
  palette (keywords, strings, numbers and comments each get their own color,
  and a mistyped command name is visibly not the color a real one would be),
  an autocomplete popup as you type a command name, undo/redo (Ctrl+Z /
  Ctrl+Y), Tab to indent by four spaces, run and step buttons on the
  editor's title bar, rename-in-place by double-clicking the script name,
  a debugger whose breakpoints follow their line as you insert or delete
  lines above them, a script list, and a live top-down camera view of your
  plot filling the other half of the screen — with whatever your script
  `print`s scrolling in a strip along the bottom of that view, so you can
  watch the drone and read its output at the same time. Close the IDE
  mid-edit and your unsaved changes are still there when you reopen it.
- **AI chat, powered by your own claude CLI** — a Chat tab in the IDE talks
  directly to the copy of Claude Code already logged into your own machine
  (no API key, no extra cost beyond your existing subscription).
  **Requirement:** Claude Code (the `claude` command) must be installed and
  logged in on the PC running the game client (`npm install -g
  @anthropic-ai/claude-code`, then `claude login` with a Claude Pro/Max
  account). Nothing ships with the mod and nothing runs on the server.
  Without it, the Chat tab tells you so on open and every other feature of
  the mod works exactly as before. Every
  question carries your current script, the command and shop reference,
  your last runtime error, the plot's world-to-grid mapping, and what this
  controller has unlocked and earned — so it knows carrots need the Shop
  first. While it thinks you see "AI: thinking..." and can press Esc to
  cancel (your text stays in the box to fix and resend). When the reply
  contains code, the editor turns into a diff on the spot, Cursor-style:
  removed lines red, added lines green, an "x Reject" beside each change
  block, and "Accept rest" / "Reject all" in the Chat tab. Always runs in a
  locked-down safe mode (no file/shell access at all). History is saved per controller and per world and
  resumes next time you open the tab, with a Compact button to summarize
  and start fresh. A craftable Region Pointer wand (left-click a block for
  the start corner, right-click for the end; the selection is drawn in the
  world as a glowing box) hands the AI a coordinate range to talk about —
  and it can inspect the blocks in that range itself, on demand, without
  you having to describe them.
- **Claim a plot** — place a Drone Controller and a Corner Marker on a
  diagonal to define a square farming area. The controller can be embedded
  flush with the farmland, or stood on the surface (the plot is
  automatically detected one block below). Vanilla farmland inside the plot
  is kept watered automatically, and crops grow faster than normal whenever
  the plot is claimed, script running or not. Each placed Corner Marker gets
  a unique number automatically (1, 2, 3, ...); rename one in an anvil
  before placing it to give it a friendly name instead (world-wide unique -
  a duplicate name is rejected with a chat message). Right-click the marker
  (the Shop screen) to see its current id before using it in a script with
  `get_plot_id()`.
- **Learn from the enchanting table** — right-click an enchanting table to
  open its normal vanilla screen, then drag a blank Script Scroll into its
  item slot: the screen switches to a picker offering starter scripts and a
  full command reference, unlocked as you surround the table with more
  bookshelves (exactly like vanilla enchanting), for a lapis lazuli cost.
  You can also copy an already-written scroll sitting in a chest, shulker
  box, or chiseled bookshelf around the table for a flat 1 lapis.
- **Write and run right away** — every controller carries its own built-in
  script ("Controller script", first in the list), so a freshly placed
  controller can be edited and run with nothing else in hand. Scrolls are
  for carrying and sharing scripts.
- **A script library you can carry** — store scrolls in a chest, shulker
  box, or chiseled bookshelf anywhere along the two axis lines from the
  controller toward the corner marker (no need to relocate it if you resize
  the plot later) and they show up in the controller's script list
  automatically. A blank scroll in the library shows up too, ready to write
  into straight from the list. Scrolls in your own inventory show up in the
  list as well, private to you. Run the selected script with a redstone
  signal (a lever, for example) — no GUI required during normal play. (If
  the selected script is a scroll in your inventory, the lever reads it
  from whoever last ran the controller, so that player must be online and
  still carrying it; pick the Controller script or a library scroll for
  unattended setups.)
- **The controller shows what it's doing** — a docked look while idle, an
  active look while a script is running, and it faces whoever placed it.
- **Wheat, carrot, and pumpkin** — earn points per crop by harvesting (shown
  live at the top of the IDE screen), then spend them in an in-game shop
  (right-click the Corner Marker, or the IDE's Shop button) to unlock carrot
  and pumpkin farming - unlocked crops plant for free, forever. Not unlocked
  yet? `plant()` will still work if the player who last ran the controller
  is carrying the real thing (carrots, pumpkin seeds), spending one per
  planting.
- **Giant pumpkins** — pumpkins ripen on the tile they were planted on
  (like the source game, not like vanilla stems), and a full square of
  ripe ones fuses into one giant pumpkin that keeps growing as its
  neighbours ripen (2x2 → 3x3 → … → the whole plot): harvest any tile and
  the whole square pays out n³ points (6n² from 6x6 up) instead of 1 per
  tile.
  About 1 in 5 pumpkins grows "rotten" instead (matches the source game):
  it yields nothing and blocks the fusion, so the game is to spot it with
  `is_rotten()` and `plant()` straight over it, then wait for the whole
  square before harvesting.
  Every pumpkin event has its own sight and sound - fusion sparkles and
  chimes, a harvested giant bursts into crumbs with a level-up jingle, a
  rotting one puffs smoke, and a giant broken by hand cracks open and
  crumbles outward ring by ring (the bigger the pumpkin, the deeper the
  sound).
- **Advancements** — a dedicated advancement tab tracks obtaining the
  controller and marker, unlocking each crop, and harvest-count milestones
  (10 / 100 / 1000) per crop.
- **Multiplayer-friendly** — several players can watch the same
  controller's log/debugger output at once, and harvest achievements are
  credited to whoever started the run.

### Getting started

1. Craft a **Drone Controller** (4 iron ingots, 4 glass, 1 redstone) and a
   **Corner Marker** (4 gold ingots, 1 glass).
2. Place the controller (on the surface or embedded flush with the ground —
   your choice), then place the marker diagonally from it to size your plot
   (or skip the marker for a default 5x5 area).
3. Craft a blank **Script Scroll** (3 paper, 1 feather, 1 ink sac). Open a
   nearby **enchanting table** and drag the scroll into its item slot to
   learn a starter script or the full command reference.
4. Right-click the controller to open the in-game **IDE** and start
   editing/running the script. A redstone signal (a lever, for example)
   next to the controller runs whatever script is currently selected.
5. Right-click the Corner Marker, or use the IDE's Shop button, to open the
   shop and spend earned points on new crops.

Full command reference and playthrough notes: see the
[GitHub repository](https://github.com/khayashi4337/micra_drone) README.

### Requirements

- Minecraft 1.21.1
- NeoForge 21.1.238
- Optional, for the AI chat tab only: Claude Code (the `claude` command)
  installed and logged in on the client PC, with a Claude Pro/Max account.
  Everything else works without it.

### Notes

This is a personal/educational project under active development - expect
rough edges and occasional balance changes between versions. Feedback and
bug reports are welcome via the GitHub repository's issue tracker.

Source code: All Rights Reserved (see the repository's `README.md` /
`gradle.properties` for details).
