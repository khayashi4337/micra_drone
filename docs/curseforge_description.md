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
  `get_points`, `print`, ...). Scripts are plain `.mdrone` text files you can
  edit in any external editor — no in-game code editor required.
- **Claim a plot** — place a Drone Controller and a Corner Marker on a
  diagonal to define a square farming area. Vanilla farmland inside it is
  kept watered automatically, and crops grow faster than normal whenever the
  plot is claimed, script running or not.
- **Wheat, carrot, and pumpkin** — earn points per crop by harvesting, then
  spend them in an in-game shop (right-click the Corner Marker) to unlock
  carrot and pumpkin farming.
- **Giant pumpkins** — grow a full square of pumpkins at once and they fuse
  into a giant pumpkin patch for a large bonus payout on harvest.
  Pumpkins also have a ~20% chance to grow "rotten" and yield nothing when
  harvested (matches the source game) — check `is_rotten()` before you
  harvest to farm efficiently.
- **Advancements** — a dedicated advancement tab tracks obtaining the
  controller and marker, unlocking each crop, and harvest-count milestones
  (10 / 100 / 1000) per crop.
- **Multiple scripts per controller, with a description** — pick from a
  scrollable list of saved scripts by file name; the first `#` comment line
  of the selected script shows as its description.

### Getting started

1. Craft a **Drone Controller** (4 iron ingots, 4 glass, 1 redstone) and a
   **Corner Marker** (4 gold ingots, 1 glass).
2. Place the controller, then place the marker diagonally from it to size
   your plot (or skip the marker for a default 5x5 area).
3. Right-click the controller to open its screen: set an alias, pick a
   script, hit **Run**. Click **Open Scripts Folder** to edit scripts in
   your own editor, or **Help** to write out a full command reference.
4. Right-click the Corner Marker to open the shop and spend earned points on
   new crops.

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
