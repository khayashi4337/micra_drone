# Claude in Chrome 用: CurseForgeへの初回投稿ガイドお願いプロンプト

以下をそのままClaude in Chromeにコピペしてください。

---

CurseForge (https://www.curseforge.com/) に、自作のMinecraft Modを初めて投稿したいです。今までCurseForgeはModのダウンロードでしか使ったことがなく、投稿(開発者登録・プロジェクト作成・ファイルアップロード)は一度もやったことがありません。私が迷わないように、1ステップずつ画面を確認しながら案内してください。最終的な「公開(Publish/Submit for review)」ボタンを押す前には必ず一度止まって、内容を確認させてください。

## Modの情報

- **Mod名**: Micra Drone
- **対応Minecraft**: 1.21.1
- **対応Mod loader**: NeoForge 21.1.238
- **バージョン**: 0.0.3
- **カテゴリ候補**: Technology / Miscellaneous / Adventure
- **ライセンス**: All Rights Reserved

## アップロードするjarファイル

ローカルの `G:\prj2\micra_drone\build\libs\micradrone-0.0.3.jar` をアップロードしてください(ファイル選択ダイアログが出たらこのパスを開きます)。

## プロジェクト説明文(そのまま貼り付け用)

```markdown
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
```

## リンク情報

- ソースコード/README: https://github.com/khayashi4337/micra_drone
- 参考にした本家ゲーム: https://store.steampowered.com/app/2060160/The_Farmer_Was_Replaced/

## お願いしたい流れ

1. まだCurseForgeの開発者アカウント登録が済んでいなければ、登録の手順を案内してください(メール認証などが必要ならその都度教えてください)。
2. 新規Mod(プロジェクト)作成画面を開き、上記の情報で1項目ずつ埋めるのを手伝ってください。アイコン画像は `G:\prj2\micra_drone\explain\micar_drone_logo.png` を候補として使えます(正方形の要件に合うか確認してください。合わない場合はその旨教えてください)。
3. ファイルアップロード画面で、上記jarファイルを選び、対応バージョン(Minecraft 1.21.1 / NeoForge)を正しく指定するのを手伝ってください。
4. 最終確認画面まで来たら、公開ボタンを押す前に内容のサマリーを見せて、私の返事を待ってください。
