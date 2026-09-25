# 05 部品の語彙・モジュールライブラリ・アナライザ

- 「使ってよい部品の名簿」(`PartTypeRegistry`)の中身、モジュールライブラリ、決定論のアナライザ(Blueprint Analyzer、Zoning Fixer、Recipe Resolver、Capacity Calculator、Router、Factory Analyzer、忠実度テスト、試運転)の仕様。
- **日本語の表示名は、ゲーム内の言語ファイル(JEIと同じ表示名)を実物のjarから取得したもの**(`assets/create/lang/ja_jp.json`、Aeronauticsは`assets/aeronautics/lang/ja_jp.json`)。「(仮)」は、設置方法・検証方法を、スパイクS-5(Createの設置手順)で確定するまでの暫定を示す。

---

## 1. 部品カタログ

### 1.1 建築部品(接頭辞`micra:`、ゲーム標準のブロックを`StyleSpec.palette`の素材で組む)

| 部品ID | 名前 | 役割・主なパラメータ | 生成するもの |
|---|---|---|---|
| `micra:structure` | 建屋 | 足跡(幅・奥行き)、階数、階高。壁・床・屋根・モジュールの親 | (親ノード。ブロックは子が出す) |
| `micra:foundation` | 基礎・整地 | 範囲、深さ、素材。地形の切り盛り | 地面の均し、基礎ブロック |
| `micra:floor` | 床 | 階、範囲、素材、穴(階段・吹き抜け) | スラブ・ブロックの床 |
| `micra:wall` | 壁 | 面(N/E/S/W)、階、長さ、素材、厚み、部分(全面/腰壁) | 壁ブロック。開口部の位置を面ローカル座標で受ける |
| `micra:pillar` | 柱 | 位置、高さ、土台・柱頭の有無 | 柱ブロック+土台+柱頭 |
| `micra:beam` | 梁 | 始点・終点、素材 | 水平の梁 |
| `micra:roof` | 屋根 | 種類(切妻`GABLE`/寄棟`HIP`/陸屋根`FLAT`/片流れ`SHED`/鋸屋根`SAWTOOTH`/越屋根`MONITOR`)、色、勾配、軒の出 | 階段・スラブで作る屋根 |
| `micra:door` | 扉 | 壁ID、位置u、種類(`SINGLE`/`DOUBLE`/`HANGAR`(大扉))、向き | ドア、大扉は開口+ゲート |
| `micra:window` | 窓 | 壁ID、位置(u,v)、種類(`PANE`/`WIDE`/`ARCH`)、格子 | ガラス板・開口 |
| `micra:stairs` | 階段 | 始点、方向、段数、幅 | 階段ブロック |
| `micra:ladder` | はしご | 位置、高さ | はしご |
| `micra:catwalk` | キャットウォーク(歩廊) | 経路、幅、高さ、手すり | 格子床+手すり(素材は、鉄骨(`create:metal_girder`)などをパレットで選べる) |
| `micra:balcony` | バルコニー | 壁ID、位置、張り出し | スラブ+手すり |
| `micra:railing` | 手すり・柵 | 経路、高さ | 柵ブロック |
| `micra:chimney` | 煙突 | 位置、高さ、太さ、笠 | 積み上げ+笠 |
| `micra:ramp` | 斜路 | 始点、終点、幅 | 階段・スラブ |
| `micra:lamp` | 照明 | 位置、種類 | 光源ブロック |
| `micra:sign` | 看板 | 壁ID、位置、文字 | 看板 |
| `micra:planter`、`micra:trim` | 植栽・縁取り | 壁ID、位置、範囲 | 装飾ブロック |
| `micra:dock_pad` | 発着場 | 広さ、余白(上空の空き高)、荷役位置、標識 | 平らな台+標識+荷役口(`LOGISTICS`) |
| `micra:road` | 道 | 経路、幅、素材 | 道ブロック |

- 窓の`ARCH`(アーチ)のように、形の実現が難しい種類は、P3の計画書でブロックの作り方を確定し、できない形は登録しない(登録簿にあるものは必ず作れる、が原則)。
- 建築部品の**パラメータ仕様**は、`ParamSpec`(型・範囲・既定値・単位)として登録し、スキーマ生成(`06` 4節)と検証に使う。

### 1.2 Create部品(`create:`、**JEIで検索して出る名前**)

| 分類 | 部品ID(名前) |
|---|---|
| 動力(`POWER`) | `create:water_wheel`(水車)、`create:large_water_wheel`(大きな水車)、`create:windmill_bearing`(風車ベアリング。帆の構造を伴う)、`create:steam_engine`(蒸気エンジン。ボイラー=`create:fluid_tank`(液体タンク)+`create:blaze_burner`(ブレイズバーナー)+水を伴う)、`create:hand_crank`(ハンドクランク)、`create:creative_motor`(クリエイティブモーター。**設定で許可された時だけ**登録) |
| 伝達(`TRANSMISSION`) | `create:shaft`(シャフト)、`create:cogwheel`(歯車)、`create:large_cogwheel`(大きな歯車)、`create:gearbox`(ギアボックス)、`create:clutch`(クラッチ)、`create:gearshift`(ギアシフト)、`create:encased_chain_drive`(ケース入りチェーンドライブ)、`create:adjustable_chain_gearshift`(可変チェーンギアシフト)、`create:rotation_speed_controller`(回転速度コントローラー)、`create:sequenced_gearshift`(シーケンスギアシフト)、`create:flywheel`(弾み車)、`create:andesite_encased_shaft`(安山岩ケース入りシャフト)、`create:brass_encased_shaft`(真鍮ケース入りシャフト) |
| 加工(`PROCESSING`) | `create:mechanical_press`(メカニカルプレス)、`create:mechanical_mixer`(メカニカルミキサー)+`create:basin`(鉢)、`create:millstone`(石臼)、`create:crushing_wheel`(破砕ホイール。2基1組)、`create:mechanical_saw`(メカニカルソー)、`create:mechanical_drill`(メカニカルドリル)、`create:mechanical_harvester`(メカニカルハーベスター)、`create:mechanical_plough`(メカニカルプラウ)、`create:deployer`(デプロイヤー)、`create:spout`(アイテム注液口)、`create:item_drain`(アイテム排液口)、`create:encased_fan`(ケース入りファン)、`create:blaze_burner`(ブレイズバーナー。加熱の熱源)、`create:mechanical_arm`(メカニカルアーム) |
| 物流(`LOGISTICS`) | `create:belt_connector`(**メカニカルベルト**。これがJEIに出るアイテム名。置かれるブロック`create:belt`は`IMPLICIT`)、`create:depot`(デポ)、`create:chute`(シュート)、`create:smart_chute`(スマートシュート)、`create:andesite_funnel`(安山岩ファンネル)、`create:brass_funnel`(真鍮ファンネル)、`create:andesite_tunnel`(安山岩トンネル)、`create:brass_tunnel`(真鍮トンネル) |
| 保管(`STORAGE`) | `create:item_vault`(アイテム保管庫)、ゲーム標準のチェスト・樽、`create:stockpile_switch`(閾値スイッチ) |
| 液体(`FLUID`) | `create:fluid_pipe`(液体パイプ)、`create:glass_fluid_pipe`(ガラス付き液体パイプ)、`create:smart_fluid_pipe`(スマート液体パイプ)、`create:mechanical_pump`(メカニカルポンプ)、`create:fluid_tank`(液体タンク)、`create:hose_pulley`(ホースプーリー) |
| 外装(`STRUCTURE`/`DECOR`) | `create:andesite_casing`(安山岩ケーシング)、`create:brass_casing`(真鍮ケーシング)、`create:copper_casing`(銅ケーシング)、`create:metal_girder`(鉄骨) |

- 各部品の**ポート**(回転の入出力、品物の入出力、液体、熱、レッドストーン)・**占有体積**・**設置戦略(`placer`)**・**検証方針(`verify`)**・**挙動モデル**は、S-5(Createの設置手順の調査)と、忠実度テストで確定する。それまでの値は「(仮)」で、確定前に設計を凍結しない。
- `create:blaze_burner`は**熱源**(`PortKind.HEAT`、接続は`ConnKind.HEAT`)。真鍮のように`heat_requirement: heated`のレシピの機械(ミキサー+鉢)は、`HEAT`ポートを持つ鉢の下にバーナーを置く。**熱が足りない構成は`E-HEAT-NONE`で検出する**。バーナーは燃料を入れ続けないと加熱にならないので、燃料の補給手段(`W-FUEL-SUPPLY`)も検査する。対応は、忠実度テストとS-5cで確かめる。
- **`IMPLICIT`の部品**(`01` 3節): `create:belt`(ブロック。アイテムは`create:belt_connector`)、`create:powered_shaft`(**アイテムを持たない**。蒸気機関などが内部で作る)のように、JEIに部品として出ない物は、登録簿には載せても、AI・画像・見本帳・スキーマの選択肢には出さない。D-18の「JEIと同じ名前」は、`USER`の部品に対する規則で、言語ファイルに翻訳があるだけでは`USER`にしない(アイテムモデルの有無で判定し、判定結果は登録の点検表に記録)。
- **実装の優先度(すべて作る。順序だけ)**: P10の中で、まず**確認題材(鉄板と真鍮)に要る部品**(プレス、ミキサー+鉢、ブレイズバーナー、デポ、ベルト、ファンネル、シュート、トンネル、シャフト、歯車、ギアボックス、クラッチ、ギアシフト、水車、風車、蒸気機関、アイテム保管庫)を`P10a`として、忠実度テストまで仕上げる。残りの部品(石臼、破砕ホイール、ソー、ドリル、ハーベスター、プラウ、デプロイヤー、注液口、排液口、ファン、アーム、液体系、シーケンスギアシフトなど)は`P10b`として、同じ完了条件で仕上げる。**P10の完了は、P10aとP10bの両方**。

### 1.3 Create: Aeronautics部品(`aeronautics:`・`simulated:`・`offroad:`。同梱jarの言語ファイルの名前。**登録・忠実度テストはP13**)

| 分類 | 部品ID(名前) |
|---|---|
| 気球(`AERO`) | `aeronautics:<色>_envelope`(気球ブロック。16色。`aeronautics:<色>_envelope_encased_shaft`=気球ブロック入りシャフトもある)、`aeronautics:adjustable_burner`(熱風バーナー) |
| 推進(`AERO`) | `aeronautics:wooden_propeller`(木製プロペラ)、`aeronautics:andesite_propeller`(安山岩プロペラ)、`aeronautics:smart_propeller`(スマートプロペラ)、`aeronautics:propeller_bearing`(プロペラベアリング)、`aeronautics:gyroscopic_propeller_bearing`(ジャイロプロペラベアリング) |
| 浮力・その他 | `aeronautics:levitite`(レビタイト)、`aeronautics:levitite_blend`(レビタイト混合物)、`aeronautics:steam_vent`(蒸気弁)、`aeronautics:mounted_potato_cannon`(ポテト砲台) |

- **飛行船の組み立て・操縦・係留は`simulated`の部品**(実物のjarで確認済み。`00` 4.1節):

  | 分類 | 部品ID(名前) |
  |---|---|
  | 組み立て(`AERO`) | `simulated:physics_assembler`(物理アセンブラ)。ブロックを組み立てて動く構造物(サブレベル)にする |
  | 係留(`AERO`) | `simulated:docking_connector`(ドッキングコネクター)、`simulated:paired_docking_connector`(ペアリングされたドッキングコネクタ)、`simulated:rope_connector`、`simulated:rope_winch` |
  | 操縦(`AERO`) | `simulated:steering_wheel`(舵輪)、`simulated:throttle_lever`(操縦桿)、`simulated:navigation_table`(羅針盤)、`simulated:swivel_bearing`(スイベルベアリング) |
  | センサー・制御(`AERO`) | `simulated:altitude_sensor`(高度センサー)、`simulated:velocity_sensor`(速度センサー)、`simulated:gimbal_sensor`(ジンバルセンサー)、`simulated:optical_sensor`(光学センサー)、`simulated:laser_sensor`、`simulated:laser_pointer`、`simulated:redstone_magnet`(レッドストーン磁石)ほか |
  | 帆・動力(`AERO`) | 色付きの`simulated:<色>_symmetric_sail`と`simulated:<色>_portable_engine` |
  | 地上(`offroad`) | `offroad:borehead_bearing`、`offroad:rockcutting_wheel`、`offroad:wheel_mount` |

- 飛行船の組み立て方(物理アセンブラでブロックがサブレベルになる際の、ブロックの消え方と検証)、操縦の自動化(レッドストーン・センサー・運動での制御)、係留と発着の仕組みは、S-8で実機調査してから、`AeroParts`とテンプレート(`mod:airship_*`、`mod:dock_pad`の係留部)の設計を確定する。組み立てで消える部品は、`VerifyMode.ASSEMBLED_AWAY`と`AssemblySpec`で扱う(`01` 4節)。

### 1.4 部品を新しく足す手順(登録の点検表)

新しい部品を登録簿に足す作業は、次を全部満たして初めて完了とする(これにより、AIの出力・画像・施工・検証が同じ語彙で動く。P-16):

1. `PartType`の全項目(ID、分類、`displayNameKey`、`visualDescription`、パラメータ、ポート、占有体積、`requires`、`placer`、`verify`、`kineticModel`または`W-UNMODELED`の宣言、`phase`)。
2. 設置の忠実度テストが通る(設置・状態・動作)。
3. 部品見本帳に描画できる(または描画できない理由と代替が記録されている)。
4. スキーマ生成の結果に反映され、`query_part_types`に出る。
5. 材料の対応(`BlockToItem`)に載っている。
6. 該当するモジュールテンプレートがあれば、検証を通っている。

---

## 2. ポートと接続

`PortKind`: `ROTATION_IN`、`ROTATION_OUT`、`ITEM_IN`、`ITEM_OUT`、`FLUID_IN`、`FLUID_OUT`、`REDSTONE`、`HEAT`、`DOCK`。`ConnKind`は`ROTATION`、`ITEM`、`FLUID`、`REDSTONE`、`HEAT`、`DOCK`(`01` 11節)。

- 接続の種類(`ConnKind`)と、つながる条件(`accepts`):
  | 種類 | つなぐ物 | 中間部品(Routerが作る) | 主な検査 |
  |---|---|---|---|
  | `ROTATION` | `ROTATION_OUT` ↔ `ROTATION_IN` | シャフト、歯車、ギアボックス、クラッチ、ベルト(回転の伝達) | 軸の向きの一致、回転方向、速度、応力 |
  | `ITEM` | `ITEM_OUT` ↔ `ITEM_IN` | ベルト、シュート、ファンネル、トンネル | 向き、行き止まり、詰まり、流量 |
  | `FLUID` | `FLUID_OUT` ↔ `FLUID_IN` | パイプ、ポンプ | 連結、ポンプの向き、漏れ |
  | `REDSTONE` | 信号の出入り | (レッドストーン配線) | 信号の向き |
  | `HEAT` | `HEAT`ポートを持つ熱源(ブレイズバーナー)↔ 加熱を要求する機械(鉢) | (隣接して置くだけ。中間部品なし) | 熱源が隣接しているか、燃料の補給手段があるか(`E-HEAT-NONE`、`W-FUEL-SUPPLY`) |
  | `DOCK` | 発着場(と搬出口の荷役)↔ 飛行船の係留部 | ドッキングコネクター | 係留部の位置・向きの一致、荷積み位置への到達(`E-DOCK-MISALIGN`) |
- **ポートは向きを持つ**(`Dir6`)。モジュールを回転・鏡像にすると、ポートも一緒に変わる(`Rot`)。`connect(A.out, B.in)`は、その向きの変化に依存しない(P-4)。

---

## 3. モジュールライブラリ(検証済みテンプレート)

各テンプレートは`SemanticPlan`の断片(`ModuleTemplate`)。**ライブラリに載る条件**は、由来で決まる(`Verification`の由来):
- **同梱のテンプレート**(`BUNDLED_CI`): 開発の段階で、Factory Analyzerの静的検証・忠実度テスト(GameTest)・試運転のすべてに通った物だけを、jarに同梱する。
- **プレイヤーが昇格させたテンプレート**(`PLAYER_COMMISSIONED`、L9): GameTestは走らせられないので、Factory Analyzerの静的検証と、**そのプレイヤーの環境での試運転**に通り、ユーザーが承認した物だけ。サーバーへ送る際は、サーバーが静的検証を再実行する(`E-TEMPLATE-UNVERIFIED`)。

毎分の生産量・応力などの`stats`は、実機で測定した値を記録する(推測で書かない)。

| テンプレート | 内容(構成) | 主なポート |
|---|---|---|
| `mod:power_waterwheel` | 水源+水車(`create:water_wheel`/`large_water_wheel`)+シャフト | `ROTATION_OUT` |
| `mod:power_windmill` | 風車ベアリング+帆+シャフト。**帆は組み立てで世界から消えて動く構造物になる**(`AssemblySpec`、`ASSEMBLED_AWAY`)。出力は帆の数に依存(`PowerSourceModel`) | `ROTATION_OUT` |
| `mod:power_steam` | 液体タンク+ブレイズバーナー+水+蒸気エンジン | `ROTATION_OUT`、`FLUID_IN`(水) |
| `mod:press_station` | メカニカルプレス+デポ+搬入ファンネル+搬出ファンネル/ベルト+シャフト | `ROTATION_IN`、`ITEM_IN`、`ITEM_OUT` |
| `mod:mixer_station` | メカニカルミキサー+鉢+ブレイズバーナー(加熱用、`HEAT`接続)+搬入・搬出+シャフト+**燃料の補給手段**(S-5cで自動補給の可否を確認。自動が可能なら供給部を含め、不可能なら手動補給として`W-FUEL-SUPPLY`と手順を出す) | `ROTATION_IN`、`ITEM_IN`、`ITEM_OUT`、`HEAT` |
| `mod:millstone_line` | 石臼+搬入・搬出 | `ROTATION_IN`、`ITEM_IN`、`ITEM_OUT` |
| `mod:crusher_line` | 破砕ホイール(2基)+搬入・搬出 | 同上 |
| `mod:washing_line` | ケース入りファン+ベルト(洗浄・燻蒸など) | 同上 |
| `mod:saw_line` | メカニカルソー+搬入・搬出 | 同上 |
| `mod:deploy_line` | デプロイヤー+ベルト+搬入・搬出 | 同上 |
| `mod:input_dock` | 搬入口(保管庫+ファンネル+ベルト始点) | `ITEM_OUT` |
| `mod:output_dock` | 搬出口(ベルト終点+保管庫) | `ITEM_IN` |
| `mod:storage_bay` | アイテム保管庫+出入り口 | `ITEM_IN`、`ITEM_OUT` |
| `mod:conveyor_bus` | 建屋内の幹線(ベルトの直線+分岐) | `ITEM_IN/OUT` |
| `mod:catwalk_gallery` | キャットウォーク+階段(2階建ての見せ場) | (通行) |
| `mod:cargo_loader` | 搬出口から発着場の荷積み位置まで運ぶ荷役 | `ITEM_IN`、`DOCK` |
| `mod:airship_*` | 飛行船(気球・バーナー・プロペラ・物理アセンブラ・操縦部・係留部。組み立てで1つのサブレベルになる。S-8の結果で確定) | `DOCK`(係留)、(S-8で確定) |

- 各テンプレートに、向き・鏡像の変種(`allowedFacings`、`mirror`可否)。
- 検証の記録(`Verification`): 解析器のバージョン、忠実度テストの結果ハッシュ、試運転の実測(毎分の生産量・回転数・応力)。

---

## 4. アナライザ(決定論、純Java)

### 4.1 `IssueCode`(全ループで共通の語)

| コード | 意味 | 検出する所 | `FixHint`の例 |
|---|---|---|---|
| `E-SCHEMA` | AIの出力がスキーマに合わない | スキーマ検証 | (再試行) |
| `E-UNKNOWN-PART` | 登録簿に無い部品 | `PlanPatcher` | 近い部品の候補 |
| `E-PARAM-RANGE` | パラメータが範囲外・型違い | `PlanPatcher` | 許容範囲 |
| `E-ANCHOR` | 位置指定が不正(存在しない壁・スロット) | `PlanPatcher` | 有効な候補 |
| `E-OVERLAP` | 部品どうしの重なり | `PlanCompiler` | 移動量 |
| `E-OUT-OF-BOUNDS` | 敷地・区画の外 | `PlanCompiler` | 範囲 |
| `E-NOT-SUPPORTED` | 支えの無い部品(宙に浮く構造) | `BlueprintAnalyzer` | 柱・壁の追加 |
| `E-OPENING-NO-WALL` | 壁の無い所の窓・扉 | `PlanCompiler` | 壁ID |
| `E-ENCLOSURE-LEAK` | 屋内が閉じていない(壁・屋根の穴) | `BlueprintAnalyzer` | 穴の位置 |
| `E-PORT-UNCONNECTED` | 必要なポートがつながっていない | `FactoryAnalyzer` | 接続の候補 |
| `E-PORT-MISMATCH` | 種類・向きが合わない接続 | `FactoryAnalyzer` | 変換部品 |
| `E-NO-ROUTE` | 経路が見つからない | `Router` | 邪魔している対象ID |
| `E-ROT-CONFLICT` | 回転方向・軸の衝突(噛み合わない) | `FactoryAnalyzer` | 歯車の追加・向きの変更 |
| `E-STRESS-OVER` | 応力の超過(消費 > 供給) | `FactoryAnalyzer`/`CapacityCalculator` | 動力源の追加量(SU) |
| `E-POWER-NONE` | 動力源が無い・つながっていない | `CapacityCalculator` | 動力源の種類 |
| `E-ITEM-DEADEND` | 品物の行き止まり(出口が無い) | `FactoryAnalyzer` | 搬出の追加 |
| `E-CLOG-RISK` | 詰まりの危険(出力先が満杯になる構造など) | `FactoryAnalyzer` | 保管・分岐 |
| `E-FLUID-LEAK` | 液体の漏れ・行き場なし | `FactoryAnalyzer` | 接続 |
| `E-SPACE-SHORT` | 空間不足(スロット・通路・天井高) | `FactoryAnalyzer` | 建屋の拡張量 |
| `E-REGISTRY-VERSION` | 登録簿の版が合わない | 承認時 | 更新 |
| `E-SITE-BLOCKED` | 置換できないブロックが邪魔 | 承認時の検査 | 場所の変更 |
| `E-MATERIAL-SHORT`/`E-MATERIAL-UNKNOWN` | 材料の不足/対応表に無い | 承認時・施工時 | 補給 |
| `W-UNMODELED` | 予測モデルが無い部品(試運転で確認) | `FactoryAnalyzer` | (試運転) |
| `W-STRESS-MARGIN` | 応力の余裕が少ない | `FactoryAnalyzer` | 動力の追加 |
| `W-OVERSIZED-POWER` | 動力が過大 | `CapacityCalculator` | 削減 |
| `W-NO-RECIPE` | 該当レシピが無い(例: ネジ) | `RecipeSource` | 近い品物の候補 |
| `W-DECOR-COLLIDE` | 装飾が機能部品と衝突 | `PlanCompiler` | 移動 |
| `E-BLOCK-FORBIDDEN` | 置いてはいけないブロック(許可リスト外の素材、コマンドブロック等。D-22) | `PlanCompiler`/承認時 | 許可された素材 |
| `E-HEAT-NONE` | 加熱を要求する機械に熱源が無い | `FactoryAnalyzer` | 熱源の追加 |
| `W-FUEL-SUPPLY` | 熱源の燃料の補給手段が無い | `FactoryAnalyzer` | 補給部の追加・手順 |
| `E-DOCK-MISALIGN` | 係留部と発着場の位置・向きが合わない | `FactoryAnalyzer` | 位置の調整 |
| `E-ASSEMBLY-FAILED` | 組み立ての結果が期待(`AssemblyExpectation`)と違う | 施工後の検査 | 再組み立て・部品の確認 |
| `E-TEMPLATE-UNVERIFIED` | 検証を通っていないテンプレートが送られた | 承認時 | (拒否) |

**受け入れ可能(`acceptable=true`)なのは、`W-*`と`E-CLOG-RISK`だけ**。それ以外の`E-*`は受け入れ不可で、残っていれば承認できない(`01` 5節、`03` 0.2節)。

### 4.2 Blueprint Analyzer(N-15)

- 入力を`VoxelClassGrid`(ブロックID→意味の分類: `SOLID`/`AIR`/`OPENING(扉・窓)`/`FLUID`/`MACHINE`)にする。設計データ由来(施工リストから)と、実際のスナップショット由来の両方から作れる。
- 手順: (1)階の検出(水平な床の層)、(2)壁の面の検出(垂直な連続面、向きと法線)、(3)屋根の面、(4)開口部(扉・窓)、(5)**部屋の検出**: 建屋の外接箱の内側の空気を6近傍のフラッドフィルで分け、外から入れるかを判定(開口部は閉じた物として扱い、扉は出入口として別に記録)、(6)**スロット**: 各部屋の床面で、機械を置ける空き直方体(必要な高さ・側方の余白を満たす)を、決定論的な順序(左下から)で列挙し、動力や窓、出入口への近さを付ける。
- 出力の`SemanticMap`は、Module Plannerへの資料になる(スロットID・広さ・許す向き・近さ)。

### 4.3 Zoning Fixer(N-11)

- 規則: 足跡どうしの最小間隔、敷地内、傾斜(隣接する地表の高さの差)の許容、水・木の扱い。
- 直し方(決定論、同点は左下優先): 重なり→距離の小さい方向へ押し出す、傾斜→整地案(`SITE_PREP`)か位置の移動、敷地外→内側へ。動かした内容を`Adjustment`として説明する(AIにも人にも見える)。

### 4.4 Recipe Resolver(N-07)

- 純Javaの`RecipeSource`: `resolve(itemId|tag) → List<RecipeOption>`、`usedBy(itemId)`。実装は`construction/ServerRecipeSource`(`RecipeManager`)。
- Createのレシピ種別(実物のjarで確認済み): `pressing`(52件)、`mixing`、`milling`(261)、`crushing`(226)、`splashing`(65)、`haunting`(26)、`compacting`、`deploying`(168)、`cutting`(43)、`sequenced_assembly`、`mechanical_crafting`、`filling`、`emptying`、`item_application`、`sandpaper_polishing`ほか、標準の`crafting`・`smelting`・`blasting`・`smoking`・`campfire_cooking`。各種別の入出力の形を`RecipeOption`に正規化する。アイテムの入出力に加えて、**液体の入出力**(`filling`・`emptying`・液体を使う`mixing`)、**保持する道具・触媒**(`deploying`)、**手順の反復と遷移**(`sequenced_assembly`)、**形状**(`mechanical_crafting`)を持ち、その加工が成立する**機械の組み合わせ**(`MachineSetup`。例: ミキサー+鉢+ブレイズバーナー)を返す(`01` 6節)。`heat_requirement`(`heated`/`superheated`)は`Heat`へ、`processing_time`は`processingTicks`へ。
- タグ(例: `c:ingots/iron`)は、ゲームのタグ機構で具体的な品物に展開する。

### 4.5 Capacity Calculator(N-08)

- 入力: `ProcessGraph`。出力: `CapacityReport`。
- 各工程の台数 = ⌈目標毎分 ÷ 1台の毎分処理量⌉。1台の毎分処理量は、レシピの処理時間と機械の動作モデル(回転数への依存を含む)から決める。**モデルの数値は実機の測定(忠実度テストの基準表)から取る**(推測で書かない)。
- 動力: 各機械の応力の影響(単位回転数あたり)と回転数から、ネットワークの消費の合計を出し、動力源の容量の合計が、消費に余裕(`PowerPolicy.marginRatio`、既定10%)を足した値以上になるよう、**`PowerPolicy`が許す範囲で**動力源(`PowerPlantChoice`)を選ぶ。範囲内で足りなければ`E-POWER-NONE`と`FixHint`(目標を下げる、別の動力源を許す)を返す(L3)。
- **動力源の出力は、部品の表の値だけでは決まらない**: 応力の影響・容量(単位回転数あたり)は`StressValueSource`(実行時はCreateの`BlockStressValues`の`getImpact`/`getCapacity`、テストは固定表)から読むが、動力源の**発生する回転数と容量は状況に依存する**(実物のjarで確認: 水車は水流の当たり方=`WaterWheelBlockEntity.flowScore`、風車は組み立てた帆の数に応じた`getGeneratedSpeed()`、蒸気機関は`PoweredShaftBlockEntity.engineEfficiency`と`getCombinedCapacity()`=ボイラーの状態)。そこで**`PowerSourceModel`**が、`PowerPlantChoice.params`(水車の数と水流の配置、帆の数、ボイラーの大きさと熱源の状態など)から、回転数と`capacitySu`を計算する。モデルの数値は、忠実度テストで実機の値を測って確定する(推測で書かない)(D-9)。
- 床面積: モジュールの占有体積の合計に、通路と余白を足して、建屋の必要面積・階数を出す。

### 4.6 Router(N-18)

- 3D格子上のA*。状態=(位置, 進行方向)。費用=長さ+曲がりの罰則+既存物への近接の罰則。同費用は座標の辞書順で決定的に選ぶ。
- **接続の種類ごとの動かし方の規則**(S-5bで実機に合わせて確定): シャフトは直線(曲がるには歯車かギアボックス)、ベルトは直線と斜め(段差)で、曲がりは別のベルトへの受け渡し、シュートは縦、パイプは3方向。
- 障害物: 壁・床・既存部品・スロットの余白。見つからなければ`NoRoute(blockers=[…])`で、邪魔している部品のIDを返す。
- 結果: 中間部品(`PlanNode`)の一覧と、接続ごとの経路。AIは書かない(P-5)。

### 4.7 Factory Analyzer(N-19)と挙動モデル

- **回転ネットワーク(`KineticModel`)**: 部品を頂点、伝達可能な隣接を辺とする無向グラフを作り、動力源から幅優先で、各部品の軸・回転方向・回転数を伝える。歯車どうしの噛み合いは逆回転、大小の歯車は速度比、ギアボックス・クラッチ・ギアシフトなどは部品ごとの規則。**矛盾(同じ部品に逆の回転が伝わる等)は`E-ROT-CONFLICT`**。ネットワークごとに、消費と供給を集計して`E-STRESS-OVER`を判定。
- **物流グラフ**: 機械・保管庫・搬入出口を頂点、ベルト・シュート・ファンネル・トンネルを辺として、(a)`ProcessGraph`の各流れ(入力→機械→出力)に経路があるか、(b)行き止まり、(c)詰まりの危険(出力先が満杯になりうる、合流の順序、ファンネルの向き)、(d)辺ごとの流量が要求を満たすか、を検査。液体も同様。
- 出力: `Issue`の一覧と、`PredictionReport`(製品ごとの毎分の生産量、応力の使用率、各ネットワークの回転数)。
- **未対応の部品**は`W-UNMODELED`を出し、推測しない。**解析の通過を「検証済み」と表示しない**: `W-UNMODELED`の部品を含む計画は、承認画面に「予測モデルが無い部品を含む(試運転で確認)」と表示し、**その部品を試運転の検査項目に必ず入れ**、施工後の試運転が通るまで工場を`VERIFIED`にしない(`03` L4の終了条件)。

### 4.8 忠実度テスト(`FidelityLab`)

- 対象: 登録簿の全部品、モジュールテンプレートの全部。
- 内容: (a)**設置**: 施工リストどおりにブロックとブロック状態が入るか。(b)**動作**: 回転数・応力・品物の流れ・加工の結果が期待どおりか。(c)**予測の一致**: `KineticModel`/`PredictionReport`の予測と、実測の差が許容(回転数は厳密、生産量は±10%を初期値とし、実測で調整)以内か。
- 実行: NeoForgeのGameTest(`runGameTestServer`)。テスト用の空間に、部品を設置し、指定した数のtickを進めて観測する。S-7で、ModDevGradle 2.0.141でGameTestサーバーが動くことを確認する。動かなければ、開発者用コマンドで同じことを実機で行う。
- 結果は、部品ごとの基準表(`fidelity-baseline.json`)に記録し、Createの更新時に差分を見る。

### 4.9 試運転(`Commissioning`)と Runtime Monitor(N-30)

- **試運転**(建て終わった直後、承認済みの施工が`VERIFIED`になったあと): (1)回転ネットワークの状態を読む(回転数、過負荷の有無)。(2)`ProcessGraph`の各入力口へ、テスト用の品物を入れる(入力口のアイテム容量に挿入。`IItemHandler`のブロック機能を使う。**Createの`BlockEntity`のアクセス方法はS-5cで確認**)。**投入品はサバイバルでは所有者から実際に消費し、製品は所有者へ返す**(無料で品物を生み出さない。`04` F-24)。(3)出力口に、期待する製品が期待の時間内に出るかを観測する。(4)結果を`CommissioningReport`(通過/失敗、実測値)にし、失敗は`Issue`にする。(5)テスト品物の残りを回収して所有者へ返す(途中で落ちた場合も、記録から回収する。冪等)。**熱源の燃料の有無を試運転の前に確認**し、無ければ`W-FUEL-SUPPLY`を出す。組み立てで作られた構造物(風車・飛行船)は、`AssemblyExpectation`を満たすかも検査する。
- **運転中の観測**(`RuntimeMonitor`、ユーザーが有効にした時だけ): 出力口の在庫の増分から毎分の生産量を算出。機械の停止(回転数0、過負荷)、ベルトの滞留、出力の満杯を検出。計画値との差から、ボトルネックの候補を順位づけして`RuntimeReport`を出す。観測はチャンクが読み込まれている間だけ。
