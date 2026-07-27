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
  `while`, `for i in range(...)`, comments, and a handful of drone commands
  (`move`, `till`, `plant`, `harvest`, `can_harvest`, `is_rotten`,
  `get_points`, `print`, ...), plus perception commands that read the world
  around the drone (`get_ground`, `get_block_above`, `get_time`,
  `get_weather`, `get_biome`, `get_light`, `get_plot_id`). Scripts are written on a
  rewritable **Script Scroll** item, or in a full **in-game IDE** with a
  live top-down camera view of your plot, a debugger (breakpoints,
  step/step-out), and a script list.
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
- **A script library you can carry** — store scrolls in a chest, shulker
  box, or chiseled bookshelf anywhere along the two axis lines from the
  controller toward the corner marker (no need to relocate it if you resize
  the plot later) and they show up in the controller's script list
  automatically. A blank scroll in the library shows up too, ready to write
  into straight from the list. Scrolls in your own inventory show up in the
  list as well, private to you. Run the selected script with a redstone
  signal (a lever, for example) — no GUI required during normal play.
- **The controller shows what it's doing** — a docked look while idle, an
  active look while a script is running, and it faces whoever placed it.
- **Wheat, carrot, and pumpkin** — earn points per crop by harvesting (shown
  live at the top of the IDE screen), then spend them in an in-game shop
  (right-click the Corner Marker, or the IDE's Shop button) to unlock carrot
  and pumpkin farming.
- **Giant pumpkins** — grow a full square of pumpkins at once and they fuse
  into a giant pumpkin patch for a large bonus payout on harvest.
  Pumpkins also have a ~20% chance to grow "rotten" and yield nothing when
  harvested (matches the source game) — check `is_rotten()` before you
  harvest to farm efficiently.
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

### Notes

This is a personal/educational project under active development - expect
rough edges and occasional balance changes between versions. Feedback and
bug reports are welcome via the GitHub repository's issue tracker.

Source code: All Rights Reserved (see the repository's `README.md` /
`gradle.properties` for details).
