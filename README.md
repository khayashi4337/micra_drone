# Micra Drone

Minecraft (NeoForge 21.1.238 / Minecraft 1.21.1) mod for programming
education. Players write code in a custom Python-like language to control a
farming drone, automate crop work, and unlock progression — inspired by
[The Farmer Was Replaced](https://store.steampowered.com/app/2060160/The_Farmer_Was_Replaced/).

日本語の遊び方ガイドは下の [遊び方](#遊び方) を参照してください。

## Requirements

- Java 21 (JDK)
- Minecraft 1.21.1 / NeoForge 21.1.238

## Development

```
./gradlew runClient
```

First run downloads and decompiles Minecraft; this can take a while.

```
./gradlew build
```

builds `micradrone-<version>.jar` under `build/libs/` and runs the JUnit test
suite (the interpreter, farm rules, and other Minecraft-independent logic are
covered there; GUI/world behavior needs manual verification in-game).

## License

Mod code: All Rights Reserved (see `gradle.properties` / `neoforge.mods.toml`).
Template boilerplate under `TEMPLATE_LICENSE.txt` is MIT-licensed by the
NeoForged project.

---

## 遊び方

### 1. 入手する

2つの専用ブロックをクラフトする。

**Drone Controller**（ドローンコントローラ、鉄インゴット4・ガラス4・レッドストーン1、
下図の配置でクラフト）

```
[鉄] [ガラス] [鉄]
[ガラス] [レッドストーン] [ガラス]
[鉄] [ガラス] [鉄]
```

**Corner Marker**（コーナーマーカー、金インゴット4・ガラス1）

```
      [金]
[金] [ガラス] [金]
      [金]
```

### 2. 設置してプロットを作る

Drone Controllerを地面に置き、そこから斜め方向に好きな距離だけ離れた場所に
Corner Markerを置く。この2つのブロックが対角線上（斜め45度、コーナー
マーカーの水平距離が一致していれば多少の高低差はOK）にあれば、その正方形の
範囲がドローンの耕作エリア（プロット）として認識される。マーカーが無い場合は
コントローラの南東方向5x5マスがデフォルトのプロットになる。

### 3. コントローラを右クリックしてGUIを開く

- 上部にプロットの**エイリアス**（好きな名前）を設定できる。設定すると
  スクリプトの保存フォルダ名もその名前に追従してリネームされる。
- 作物ごとの獲得ポイントが表示される。
- スクリプト一覧（ファイル名表示、選択すると下の説明欄にそのスクリプトの
  概要が出る）、ログ欄、Run/Stopボタンがある。
- 「Open Scripts Folder」でスクリプトの保存フォルダ（OSのファイル
  エクスプローラ）を開ける。外部の好きなエディタで `.mdrone` ファイルを
  編集できる。
- 「Help」で使えるコマンドの一覧（`commands.txt`）を書き出してフォルダを
  開ける。

### 4. スクリプトを書く

構文はインデント方式のPython風。例えば最初から入っている
`till_and_plant.mdrone` は次のような内容（往復しながら畑全体を耕して植える）:

```python
# Tills and plants wheat across the whole plot using a snake path (no backtracking needed).
size = get_world_size()
going_east = True
row = 0
while row < size:
    col = 0
    while col < size - 1:
        till()
        plant("wheat")
        if going_east:
            move("east")
        else:
            move("west")
        col = col + 1
    till()
    plant("wheat")
    if row < size - 1:
        move("south")
    going_east = not going_east
    row = row + 1
print("planted the whole plot")
```

`move()` / `till()` / `plant()` / `harvest()` でドローンを動かし畑を耕す。
`can_harvest()` / `is_rotten()` / `get_pos_x()` / `get_points()` などの
読み取り系コマンドで状況を判断する。詳しい一覧はゲーム内の「Help」ボタンで
確認できる。

スクリプトを選んで **Run** を押すと実行され、ログ欄に `print()` の出力が
流れる。**Stop** でいつでも安全に止められる。

### 5. ポイントを稼いでショップでアンロックする

収穫すると作物ごとにポイントが貯まる。**Corner Marker を右クリック**する
とショップ画面が開き、貯めたポイントで新しい作物（carrot、pumpkin）を
アンロックできる。未アンロックの作物は `plant()` が常に失敗する。

pumpkinは実る瞬間に約20%の確率で「腐ったかぼちゃ」になり、収穫しても
ポイントが入らない。`is_rotten()` で事前にチェックして無駄な収穫を避ける
のが効率よく稼ぐコツ（本家 *The Farmer Was Replaced* と同じ仕様）。

同じ大きさの正方形にpumpkinがすべて同時に実ると自動的に「巨大かぼちゃ」に
変わり、どこか1マスを収穫するとまとめて大きなボーナスポイントが入る。

### 6. 進捗（実績）を解除する

Drone Controller / Corner Marker の入手、作物のアンロック、収穫数の節目
（10個・100個・1000個ごと、作物別）で進捗（Advancement）が解除される。
マイクラの「進捗」画面に専用タブが表示される。

### プロットの仕様メモ

- 耕作エリア内の耕地は常に最大湿度に保たれる（水源が無くても乾かない・
  dirtに戻らない）。
- プロット内の未成熟な作物は約5秒ごとに自動で成長が早まる
  （スクリプトを実行していなくても常時作用）。
