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
| `micra:balcony` | バルコニー | 壁ID、位置、張り出し | 床+柵 |
| `micra:railing` | 手すり・柵 | 経路、高さ | 柵ブロック |
| `micra:chimney` | 煙突 | 位置、高さ、太さ、笠 | 積み上げ+笠 |
| `micra:ramp` | 斜路 | 始点、終点、幅 | 階段・スラブ |
| `micra:lamp` | 照明 | 位置、種類 | 光源ブロック |
| `micra:sign` | 看板 | 壁ID、位置、文字 | 看板 |
| `micra:planter`、`micra:trim` | 植栽・縁取り | 壁ID、位置、範囲 | 装飾ブロック |
| `micra:dock_pad` | 発着場 | 広さ、余白(上空の空き高)、荷役位置、標識 | 平らな台+標識+荷役口(`LOGISTICS`) |
| `micra:road` | 道 | 経路、幅、素材 | 道ブロック |

- 窓の`ARCH`(アーチ)などの形は、下の1.1.1節で作り方を確定した(全部作れる)。登録簿にあるものは必ず作れる、が原則。
- 建築部品の**パラメータ仕様**は、`ParamSpec`(型・範囲・既定値・単位)として登録し、スキーマ生成(`06` 4節)と検証に使う。

### 1.1.1 建築部品の生成規則(P3で確定。局所座標(u,v,w)。向きの規約は`01` 1節)

**共通**: ノードの原点=(親の原点)+`Absolute.pos`(親が無ければ計画の原点から)。階の高さ`fh`=親の`structure`の`floor_height`。階`L`の床のv=`L*fh`、その階の壁の最下段のv=`L*fh+1`(壁の標準の高さは`fh-1`)。屋根の土台のv=`floors*fh`。`structure`・壁を基準にする部品は、**親が`structure`(または壁)**でなければ`E-ANCHOR`。
**素材**: `MaterialV`は、`:`を含めばブロックID、含まなければ役割名で、`StyleSpec.palette`から引く(無ければ既定のパレット)。階段・スラブが要る部品は、役割`R`の階段を`palette[R+"_stairs"]`、スラブを`palette[R+"_slab"]`、無ければ**族表**(`MaterialFamilies`。ゲーム標準の板材・石材などの族)から引く。族に無い素材(例: 赤いテラコッタは階段が無い)は、`E-PARAM-RANGE`と、族のある候補(`red_nether_bricks`など)を`FixHint`で返す。
**既定のパレット**(すべて`minecraft:`。登録簿の版ハッシュに含める): `wall`=`stone_bricks`、`floor`=`oak_planks`、`roof`=`oak_planks`、`foundation`=`cobblestone`、`pillar`=`stone_bricks`、`beam`=`oak_log`、`trim`=`stone_bricks`、`glass`=`glass_pane`、`door`=`oak_door`、`gate`=`oak_fence_gate`、`fence`=`oak_fence`、`stairs`=`oak_stairs`、`ramp`=`stone`、`catwalk`=`iron_trapdoor`、`chimney`=`bricks`、`path`=`gravel`、`pad`=`smooth_stone`、`marker`=`yellow_concrete`、`cargo`=`barrel`、`sign`=`oak_wall_sign`、`planter`=`dirt`、`plant`=`poppy`。
**フェーズ**: `foundation`・`floor`・`pillar`・`beam`・`chimney`・`stairs`・`ramp`=`STRUCTURE`、`wall`・`roof`・`door`・`window`=`ENVELOPE`、`ladder`・`catwalk`・`balcony`・`railing`・`lamp`・`sign`・`planter`・`trim`=`DECORATION`(壁や床に支えられて初めて残る物は、あとに置く)、`dock_pad`・`road`=`LOGISTICS`。`structure`はブロックを持たない。
**重なり**: 同じ位置を2つの部品が使えば`E-OVERLAP`。**例外**: 同じ親の`wall`どうしの角で、ブロックが同じなら合流(角は1個)。開口部(`door`・`window`)は、壁のマスを**掘る**(壁のマスでなければ`E-OPENING-NO-WALL`)。開口部どうしの重なりは`E-OVERLAP`。壁の端まで届く開口部は、壁どうしが合流した角の柱も掘る。2つの開口部が同じマスを掘るときは、両方の開口部を名指しする`E-OVERLAP`を1つだけ出す(重なりの種類は`carve`)。
- **作業量の上限(P3で確定)**: 生成器が試す配置の回数は、占有セル1つあたり2回まで(`Canvas`の予算)。「検討したが空気のまま残したマス」(床の穴など)も1単位を使うので、何も置かない部品も仕事量に上限がある。発着場の上空の空きの確認は、他の部品が置かれたマスを調べた分だけ予算を使う(空きだったマスは使わない)。開口部が掘るマスは1マス1単位。前の検査で拒否された部品は、後の検査(発着場の上空の確認を含む)を受けない。
**検証の既定**: 状態を持たない全ブロック=`EXACT`。階段・スラブ・扉・門・はしご・看板・ランタン・樽など=`STATE_SUBSET`(状態のうち、記した物だけ比べる)。ガラス板・柵など、隣で状態が変わる物=`BLOCK_ONLY`。稼働で変わる`open`・`powered`は`volatileProps`。置換の方針は、すべて`REPLACEABLE`(地形の切り盛りは、P4の整地が載せる)。

| 部品 | パラメータ(型・範囲・既定) | 生成するもの |
|---|---|---|
| `structure` | `width`(3〜64, 7)、`depth`(3〜64, 7)、`floors`(1〜8, 1)、`floor_height`(3〜8, 4) | (ブロックなし。子の基準の箱 u∈[0,width-1]、w∈[0,depth-1]) |
| `foundation` | `margin`(0〜8, 0)、`depth`(1〜8, 1)、`material`(役割`foundation`) | u∈[-margin,width-1+margin]、w∈[-margin,depth-1+margin]、v∈[-depth,-1]の全ブロック |
| `floor` | `level`(0〜7, 0)、`kind`(`block`/`slab`, `block`)、`holes`(整数の並び`[u0,w0,u1,w1,…]`。値は0〜63、長さは4の倍数で64値(16個の穴)まで)、`material`(`floor`) | v=`level*fh`、u∈[0,width-1]、w∈[0,depth-1]から穴を除いた面。`slab`は下付きのスラブ |
| `wall` | `side`(`north`/`east`/`south`/`west`)、`level`(0〜7, 0)、`height`(0〜16, 0=`fh-1`)、`thickness`(1〜3, 1)、`from`(0〜63, 0)、`length`(0〜64, 0=端まで)、`part`(`full`/`half`, `full`。`half`は高さの半分(切り上げ))、`material`(`wall`) | `north`は`w=depth-1`、`south`は`w=0`、`east`は`u=width-1`、`west`は`u=0`の側。始点から`length`個、内側へ`thickness`層。始点が側の長さ以上、または端を越えれば`E-PARAM-RANGE` |
| `pillar` | `height`(1〜32, 4)、`base`(真偽, 真)、`capital`(真偽, 真)、`material`(`pillar`) | 高さ`height`の柱。`base`はv=0、`capital`はv=`height-1`を、役割`trim`のブロックにする(`height`が1なら柱身だけ) |
| `beam` | `axis`(`u`/`v`/`w`, `u`)、`length`(1〜64, 3)、`material`(`beam`) | 原点から`axis`の向きに`length`個。丸太・柱状のブロックは、向き(`axis`の状態)を付ける |
| `roof` | `kind`(`gable`/`hip`/`flat`/`shed`/`sawtooth`/`monitor`, `gable`)、`overhang`(0〜3, 1)、`ridge`(`auto`/`u`/`w`, `auto`=長い側に沿う。同じなら`w`)、`high_side`(`shed`の高い側, `east`)、`gable_fill`(真偽, 真)、`tooth`(2〜8, 3)、`monitor_width`(1〜5, 1)、`monitor_height`(1〜3, 1)、`material`(`roof`) | 屋根の土台v=`floors*fh`。勾配は1:1(階段)だけ。下記 |
| `door` | `kind`(`single`/`double`/`hangar`, `single`)、`width`(3〜9, 5。`hangar`のみ)、`height`(3〜6, 4。`hangar`のみ)、`hinge`(`left`/`right`, `left`)、`material`(`door`) | 壁の`OnSurface`。`single`は幅1×高さ2、`double`は幅2×高さ2(2枚の蝶番は外側)、`hangar`は幅×高さを掘って空け、下の2段に門(役割`gate`)を並べる。扉・門の向きは室内向き(`INNER`なら室外向き) |
| `window` | `kind`(`pane`/`wide`/`arch`, `pane`)、`lattice`(真偽, 偽)、`material`(`glass`) | 壁の`OnSurface`。`pane`は幅1×高さ2、`wide`は幅3×高さ2、`arch`は幅3×高さ3(上の両端は、役割`trim`の階段を上下逆にして、外側を背にする)。`lattice`は、真ん中の列を役割`trim`にする(`wide`と`arch`だけ。`pane`では`E-PARAM-RANGE`) |
| `stairs` | `steps`(1〜32, 4)、`width`(1〜8, 1)、`dir`(4方向, `north`)、`material`(`stairs`) | 原点の段から、`dir`へ1段ごとに1つ上がる階段(向きは`dir`)。幅は`dir`の右手へ |
| `ladder` | `height`(1〜32, 3)、`facing`(4方向, `north`) | 原点からv方向へ`height`個のはしご |
| `catwalk` | `length`(1〜64, 6)、`dir`(4方向, `north`)、`width`(1〜5, 2)、`rail`(真偽, 真)、`material`(`catwalk`) | v=0の格子床と、`rail`なら両端の外側の列のv=1に柵(役割`fence`) |
| `balcony` | `width`(1〜16, 3)、`depth`(1〜8, 2)、`rail`(真偽, 真)、`material`(`floor`) | 壁の`OnSurface`。壁の外に張り出す床(v=その階の床の高さ+`v`)と、外側の3辺の柵 |
| `railing` | `length`(1〜64, 3)、`dir`(4方向, `north`)、`height`(1〜3, 1)、`material`(`fence`) | 原点から`dir`へ`length`本の柵 |
| `chimney` | `height`(2〜32, 6)、`size`(1〜3, 1)、`cap`(真偽, 真)、`material`(`chimney`) | `size`×`size`の柱(3は中空)、`cap`はv=`height`に、一回り大きなスラブ |
| `ramp` | `length`(2〜32, 6)、`dir`(4方向, `north`)、`width`(1〜8, 2)、`material`(`ramp`) | スラブを交互(下付き・上付き)に並べ、1マスで半段上がる |
| `lamp` | `kind`(`lantern`/`hanging`/`post`/`torch`, `lantern`)、`height`(1〜6, 2。`post`のみ) | `lantern`(置き)、`hanging`(吊り)、`post`(柵の柱+てっぺんにランタン)、`torch` |
| `sign` | `text`(63字以内=4行×15字+縦線3個。縦線`\|`で改行、4行まで、1行15字まで)、`material`(`sign`) | 壁の外側に、壁掛けの看板(向きは外向き)。文字は`blockEntityConfig`の`line1`〜`line4` |
| `planter` | `width`(1〜8, 3) | 壁の外側に、土の列(役割`planter`、v=`v`)とその上の草花(役割`plant`、v=`v`+1)。`v`が壁の最上段なら草花は壁の高さを1段超える(拒否しない) |
| `trim` | `length`(1〜64, 3)、`axis`(`horizontal`/`vertical`, `horizontal`)、`shape`(`block`/`slab`, `block`)、`material`(`trim`) | 壁の外側に沿う縁取りの列 |
| `dock_pad` | `width`(5〜64, 9)、`depth`(5〜64, 9)、`clearance`(4〜64, 16)、`cargo_u`・`cargo_w`(0〜63, 1)、`marker`(真偽, 真)、`material`(`pad`) | v=0の平らな台。`marker`は外周を役割`marker`にする。荷役口として、(`cargo_u`,1,`cargo_w`)に樽(役割`cargo`)。台の真上(v=1〜`clearance`)に他の部品があれば`E-OVERLAP`(荷役口の樽を除く) |
| `road` | `length`(1〜128, 8)、`dir`(4方向, `north`)、`width`(1〜8, 2)、`material`(`path`) | v=0の道 |

**屋根の作り方**: 幅`T`=`width+2*overhang`(奥行も同様)。勾配の向きに直角な断面で、段`k`(0から)のv=土台+`k`に、外側から`k`だけ内側の位置に階段(背を棟の側へ向ける)を置く。`gable`: 棟に直角な向きの両端の階段を、内側で出会うまで積む。`T`が奇数なら、棟の1列は、役割`roof`の全ブロック。`gable_fill`が真なら、棟の両端の面(構造の縁)の、屋根の下の三角を、役割`wall`で埋める。`hip`: 各段の外周に階段(角は東西の向きを優先)、外周が1列に潰れる段は全ブロック。`flat`: v=土台に、下付きのスラブを1面。`shed`: `high_side`へ1段ごとに1つ上がる階段を、全幅に。`gable_fill`が真なら両端の三角を埋める。`sawtooth`: `tooth`幅ごとに、棟と直角の向き(`a`の大きい側)へ`k`段上がる階段と、その最上段の1つ上に役割`glass`の全ブロック1個(採光の面。最後の歯が`tooth`幅に満たなくても、その最上段の上にも置く)。`monitor`: `gable`を、中央の幅が`monitor_width`になる段で打ち切り(`T`≦`monitor_width`、または`T`と`monitor_width`の偶奇が違えば`E-PARAM-RANGE`)、中央の両縁に、役割`glass`のブロックを`monitor_height`段、その上に下付きのスラブで蓋をする。`gable_fill`が真なら両端の三角を役割`wall`で埋め(勾配の段数で打ち止め)、その内側の越屋根の下の両端の面を役割`glass`で`monitor_height`段覆う。

**位置指定の面(`OnSurface`)の補足**: 開口部・看板・植栽・縁取り・バルコニーは、壁の面上の位置`(u, v)`を持つ。`u`は壁の始点(`from`の位置)から数える。`v`は壁の最下段から数える(`balcony`は床の高さから)。壁の面に付く部品(バルコニー・看板・植栽・縁取り)は壁の長さ・高さの内側に収まること。はみ出せば`E-ANCHOR`(キー`extent`)。開口部(`door`・`window`)のはみ出しは`E-OPENING-NO-WALL`。

**`axis`の状態を持つブロック(1.21.1で55個)**: IDの語尾(`_log`・`_wood`・`_hyphae`で終わる物。modの木材にも合う)と、語尾で掴めない物(ネザーの茎4種・干し草・骨・玄武岩・柱状態のクォーツなど)のIDの一覧で決める。`_stem`の語尾はメロン・カボチャ・キノコ・ドリップリーフの茎にも合い、それらは`axis`を持たないので、語尾では使わずIDで列挙する(一覧は`FreestandingPartsTest`が固定)。

**実機で見た目を確かめる項目(P4の実機確認)**: 二枚扉の蝶番が中央で合うか(蝶番の左右の規則: 扉の向きに向かって歩く人から見て、各扉の葉の外側の縁に蝶番を付ける。壁の始点側の葉が右蝶番か左蝶番かは、壁が伸びる向きと扉の向きから決まる)、階段の角の形(隣のブロックの更新で決まる)、壁掛け看板の向き、屋根の階段の向き、はしごの向き。純Javaのテストでは、規則どおりの状態が出ることまでしか確かめられない。

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
- **世界に作用する部品**(メカニカルドリル・ソー・ハーベスター・プラウ、ホースプーリー、`offroad:borehead_bearing`・`rockcutting_wheel`、`aeronautics:mounted_potato_cannon`など)は、`EffectSpec`(作用が届く範囲)を宣言しないと登録できない(D-24)。Factory Analyzerが、作用範囲が区画の`operatingBox`に収まることを検査する(`E-EFFECT-ESCAPES-CLAIM`)。区画外に作用する部品は、設定で許可された時だけ登録する。

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
7. 稼働で変わるブロック状態(`volatileProps`)と、世界への作用範囲(`EffectSpec`)が、宣言されている。
8. 所属する能力パック(`pack`)と、そのpackの`requiredMods`・版範囲が記入されている(そのmodが無い環境ではその部品だけが無効になる。1.5節)。

### 1.5 部品と能力パックの対応(`pack`)

- 各部品の登録は、**どの能力パックの物か**(`pack`フィールド)を持つ。`PartTypeRegistry`は全packの宣言を持ち、**実際に使える名簿(`EnabledRegistry`)は、有効なpackの物だけ**から作る(F-25、D-30)。モジュールテンプレート・アナライザ・レシピ源も同じく`pack`を持つ。
- 対応(現時点): `micra:*`とバニラのブロック・レシピ・畑ドローンの機器=`vanilla`pack(常に`ENABLED`)、`create:*`=`create`pack、`aeronautics:`・`simulated:`・`offroad:`=`aeronautics`pack(3つのmodの全部が揃って初めて有効)、Sable由来=`sable`pack。`create_submarine`・`powergrid`・`create_copper_and_zinc`の部品は、S-11〜S-13で接頭辞と中身を確定してから載せる(それまで登録しない)。
- **無効なpackの物を使う計画は`E-PACK-DISABLED`**(対象ID・pack ID・理由つき)で、承認の前に拒否する。通常はスキーマ・見本帳・画面の選択肢に出ないので「書けない」が、保存済みの計画の再利用や、modを外した後の環境で起きうる。**未知のmod由来らしいIDは`E-UNKNOWN-CAPABILITY`**(推測で受けない。D-31)。

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
- **同梱する時期**: P11で同梱するのは、動力・加工・搬入出・保管・幹線・キャットウォークの各テンプレート。**Aeronauticsの部品を要する`mod:cargo_loader`・`mod:dock_pad`・`mod:airship_*`は、P13で同梱に加え**、同じ検証(静的検証・忠実度テスト・試運転)を通す。
- 検証の記録(`Verification`): 解析器のバージョン、忠実度テストの結果ハッシュ、試運転の実測(毎分の生産量・回転数・応力)。

---

## 4. アナライザ(決定論、純Java)

### 4.1 `IssueCode`(全ループで共通の語)

| コード | 意味 | 検出する所 | `FixHint`の例 |
|---|---|---|---|
| `E-SCHEMA` | AIの出力がスキーマに合わない | スキーマ検証 | (再試行) |
| `E-UNKNOWN-PART` | 登録簿に無い部品 / 置き方(生成器)が未登録の部品 / 生成器の内部エラー(キー`generator`) | `PlanPatcher` / `PlanCompiler` | 近い部品の候補 |
| `E-PARAM-RANGE` | パラメータが範囲外・型違い | `PlanPatcher` | 許容範囲 |
| `E-ANCHOR` | 位置指定が不正(存在しない壁・スロット・親。存在しない親は対象IDに`#parent`。親自身が別の理由で拒否されている場合は親の`Issue`が答えなので、子には重ねて出さない。生成側でも、面に付く部品が壁からはみ出すときはキー`extent`、回転・鏡像を付けられない部品につけたときはキー`rot`) | `PlanPatcher` / `PlanCompiler` | 有効な候補 |
| `E-OVERLAP` | 部品どうしの重なり | `PlanCompiler` | 移動量 |
| `E-OUT-OF-BOUNDS` | 敷地・区画の外(展開後の接続の端点が区画の外でも同じ。対象は越えた側のノード) | `PlanCompiler` | 範囲 |
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
| `E-SITE-CHANGED` | 調査の後で、置く位置の地形が変わって置けなくなった | 施工時(`PAUSED`の原因) | 再調査・位置の見直し |
| `E-EFFECT-ESCAPES-CLAIM` | 部品の作用範囲が区画(`operatingBox`)の外に出る | `FactoryAnalyzer` | 位置の移動・区画の拡大 |
| `E-TERRAFORM-UNCONFIRMED` | 整地(切り・盛り)が承認画面で確認されていない | 承認時 | 確認 |
| `W-ASSEMBLY-AWAY` | 組み立てた物(飛行船など)が定位置に無く、検査できない | L7 | 定位置へ戻す |
| `W-NO-SETUP` | そのレシピ種別を成立させる機械の組み立てを、私たちがまだ検証していない | `RecipeSource` | 別のレシピ・後で検証 |
| `W-DYNAMIC-PART` | 時刻・信号で状態が変わる部品を、宣言した状態でだけ評価した | `FactoryAnalyzer` | 他の状態の検証(試運転) |
| `E-PATCH-STALE` | 差分の`baseRevision`が現在の版と違う | `PlanPatcher` | 最新の版で作り直す |
| `E-ID-INVALID` | IDが`[a-z0-9-]{1,48}`に合わない | `PlanPatcher` | 直したID |
| `E-ID-DUPLICATE` | 同じIDのノード・接続が既にある | `PlanPatcher` | 別のID |
| `E-CONN-INVALID` | 接続の端点のノード・ポートが無い、経由するノードが無い | `PlanPatcher`/`PlanExpander` | 有効なノード・ポートの候補 |
| `E-SITE-MISSING` | 敷地(`site`)が未設定 | `PlanCompiler` | `site(...)`を足す |
| `E-SCRIPT-FORBIDDEN` | 建設のスクリプトで許可されていない命令(乱数・時刻・畑の命令・`create_task`等)を使った | 静的検査(`PlanScriptProfile`) | 許可された命令 |
| `E-SCRIPT-LIMIT` | 建設のスクリプトが、実行の回数・時間の上限を超えた | 実行時 | 処理を減らす |
**受け入れ可能(`acceptable=true`)なのは、`W-*`と`E-CLOG-RISK`だけ**。それ以外の`E-*`は受け入れ不可で、残っていれば承認できない(`01` 5節、`03` 0.2節)。

将来のフェーズ(P16〜P21)向けに意味だけ先に確定したコード。enumへの追加と上の表への移動は、それぞれのフェーズで実装するときに行う：

- `E-PACK-DISABLED`：無効な能力パック(要求するmodが無い・版が範囲外)の部品・テンプレート・能力を計画が使っている — `PlanPatcher`/`PlanCompiler`/承認時 — 有効なpackの部品に替える・そのmodを入れる
- `E-UNKNOWN-CAPABILITY`：未知のmod由来らしい部品ID・能力を計画が使っている(推測で受けない) — `ModScan`/承認時 — 有効なpackの物を使う
- `E-DEVICE-RANGE`：機器への書き込みが、宣言した型・範囲に合わない — `DeviceGate` — 許容範囲
- `E-DEVICE-PRECOND`：機器の事前条件(`requires`)が満たされていない — `DeviceGate` — 先に満たす操作
- `E-DEVICE-LIMIT`：機器の安全限界(`hard=true`)を超える指示 — `DeviceGate` — 範囲内の値
- `E-ESTOP`：緊急停止の発動中の機器への書き込み — `DeviceGate` — 所有者/OPによる明示の解除
- `W-DEVICE-CLAMPED`：機器への指示を、宣言の範囲に丸めた(`CLAMP`) — `DeviceGate` — (確認)
- `W-UNKNOWN-MOD`：未知のmodが導入されている(情報の提供だけ。部品にはならない) — `ModScan` — (説明を読む)
- `E-RESERVED-CONFLICT`：部品・足跡が`ReservedSpace`(予約した空き)に侵入している — `SpaceKeeper`/`ZoningFixer` — 侵入した対象の移動・予約の引き直し
- `E-CIRCULATION-BROKEN`：`CirculationReq`(建屋内の動線)が、要求の断面で通れない(塞がれている) — `SpaceKeeper` — 塞いでいる部品の移動・建屋の拡張
- `E-MODE-UNFIT`：敷地が、その輸送手段の`TransportProfile`の空間要求(滑走路の長さ・泊位の水深など)に足りない — `SitePlanner`/`LogisticsPlanner` — 別の輸送手段の提案(候補つき)
- `E-HULL-LEAK`：水密が要る輸送手段(船・潜水艇)の船体・収容空間に水が浸入しうる — `FactoryAnalyzer`/`SpaceKeeper` — 漏れている位置・壁の追加
- `W-STRUCTURE-UNCERTAIN`：手造り建物の認識由来の値(幅・材質)を計画が使っている(確かさはS-18の実測どおり) — `StructureSurveyor`/承認時 — 実測で確認・上書きの記録
- `E-WINDOW-MISMATCH`：接続の両側のゾーンが宣言する`Window`が合わない(種類・位置・輸送手段が違う) — `ZoneLayer` — 窓の宣言の整合
- `E-ZONE-BUDGET`：ゾーンが`ZoneBudget`(そのゾーンの試行・部品・セルの上限)を超えた — `PlanCompiler`/`AnalysisPipeline`(ゾーン別実行) — そのゾーンの簡素化・ゾーンの分割

### 4.2 Blueprint Analyzer(N-15)

- 入力を`VoxelClassGrid`(ブロックID→意味の分類: `SOLID`/`AIR`/`DOOR`/`WINDOW`/`GATE`/`FLUID`/`MACHINE`。**扉・窓・門を区別する**)にする。**建屋(`structure`ノード)ごとに`SemanticMap`を作り**、`structureId`で結びつける(複数建屋・L4'の`SpaceRequest`のため)。設計データ由来(施工リストから)と、実際のスナップショット由来の両方から作れる。
- 手順: (1)階の検出(水平な床の層)、(2)壁の面の検出(垂直な連続面、向きと法線)、(3)屋根の面、(4)開口部(扉・窓)、(5)**部屋の検出**: 建屋の外接箱の内側の空気を6近傍のフラッドフィルで分け、外から入れるかを判定(開口部は閉じた物として扱い、扉は出入口として別に記録)、(6)**スロット**: 各部屋の床面で、機械を置ける空き直方体(必要な高さ・側方の余白を満たす)を、決定論的な順序(左下から)で列挙し、動力や窓、出入口への近さを付ける。
- 出力の`SemanticMap`は、Module Plannerへの資料になる(スロットID・広さ・許す向き・近さ)。

### 4.3 Zoning Fixer(N-11)

- 規則: 足跡どうしの最小間隔、敷地内、傾斜(隣接する地表の高さの差)の許容、水・木の扱い。
- 直し方(決定論、同点は左下優先): 重なり→距離の小さい方向へ押し出す、傾斜→整地案(`SITE_PREP`)か位置の移動、敷地外→内側へ。動かした内容を`Adjustment`として説明する(AIにも人にも見える)。

### 4.4 Recipe Resolver(N-07)

- 純Javaの`RecipeSource`: `resolve(itemId|tag) → List<RecipeOption>`、`usedBy(itemId)`。実装は`construction/ServerRecipeSource`(`RecipeManager`)。
- Createのレシピ種別(実物のjarで確認済み): `pressing`(52件)、`mixing`、`milling`(261)、`crushing`(226)、`splashing`(65)、`haunting`(26)、`compacting`、`deploying`(168)、`cutting`(43)、`sequenced_assembly`、`mechanical_crafting`、`filling`、`emptying`、`item_application`、`sandpaper_polishing`ほか、標準の`crafting`・`smelting`・`blasting`・`smoking`・`campfire_cooking`。各種別の入出力の形を`RecipeOption`に正規化する。アイテムの入出力に加えて、**液体の入出力**(`filling`・`emptying`・液体を使う`mixing`)、**保持する道具・触媒**(`deploying`)、**手順の反復と遷移**(`sequenced_assembly`)、**形状**(`mechanical_crafting`)を持ち、その加工が成立する**機械の組み立て**(`MachineSetup`。例: ミキサー+鉢+ブレイズバーナー)を、`MachineSetupRegistry`から補って返す(`01` 6節)。`heat_requirement`(`heated`/`superheated`)は`Heat`へ、`processing_time`は`processingTicks`へ。
- タグ(例: `c:ingots/iron`)は、ゲームのタグ機構で具体的な品物に展開する。
- **`MachineSetupRegistry`**: Createの`RecipeManager`のレシピデータは、入出力・レシピ種別・加熱条件などしか持たず、**機械の物理的な並び(プレス+デポ、ミキサー+鉢+ブレイズバーナー)を持たない**。そこで、レシピ種別(と加熱条件)から、検証済みの機械の組み立てへの対応表を、**私たちの正本のデータ**として持つ(`build.knowledge`、同梱)。例: `create:pressing` → プレス+デポ、`create:mixing`(heated) → ミキサー+鉢+ブレイズバーナー。各エントリは、静的検証と試運転を通した物だけ(作成はP10、テンプレートと同じ検証はP11)。未登録のレシピ種別は`W-NO-SETUP`(その機械構成は、私たちがまだ検証していない)として、Process Plannerに返す。

### 4.5 Capacity Calculator(N-08)

- 入力: `ProcessGraph`。出力: `CapacityReport`。
- 各工程の台数 = ⌈目標毎分 ÷ 1台の毎分処理量⌉。1台の毎分処理量は、レシピの処理時間と機械の動作モデル(回転数への依存を含む)から決める。**モデルの数値は実機の測定(忠実度テストの基準表)から取る**(推測で書かない)。
- 動力: 各機械の応力の影響(単位回転数あたり)と回転数から、ネットワークの消費の合計を出し、動力源の容量の合計が、消費に余裕(`PowerPolicy.marginRatio`、既定10%)を足した値以上になるよう、**`PowerPolicy`が許す範囲で**動力源(`PowerPlantChoice`)を選ぶ。範囲内で足りなければ`E-POWER-NONE`と`FixHint`(目標を下げる、別の動力源を許す)を返す(L3)。
- **動力源の出力は、部品の表の値だけでは決まらない**: 応力の影響・容量(単位回転数あたり)は`StressValueSource`(実行時はCreateの`BlockStressValues`の`getImpact`/`getCapacity`、テストは固定表)から読むが、動力源の**発生する回転数と容量は状況に依存する**(実物のjarを`javap`で確認済み(2026-09-26): 水車は水流の当たり方=`WaterWheelBlockEntity`の公開フィールド`flowScore`と`getGeneratedSpeed()`、風車は組み立てた帆に応じた`WindmillBearingBlockEntity.getGeneratedSpeed()`、蒸気機関は`PoweredShaftBlockEntity`の公開フィールド`engineEfficiency`=ボイラーの状態。**`PoweredShaftBlockEntity.getCombinedCapacity()`は非公開(`private`)なので外から呼べない**ため、モデルは公開の`engineEfficiency`とブロックの容量(`BlockStressValues.getCapacity`)から計算し、忠実度テストの実測で合わせる)。そこで**`PowerSourceModel`**が、`PowerPlantChoice.params`(水車の数と水流の配置、帆の数、ボイラーの大きさと熱源の状態など)から、回転数と`capacitySu`を計算する。モデルの数値は、忠実度テストで実機の値を測って確定する(推測で書かない)(D-9)。
- 床面積: モジュールの占有体積の合計に、通路と余白を足して、建屋の必要面積・階数を出す。

### 4.6 Router(N-18)

- 3D格子上のA*。状態=(位置, 進行方向)。費用=長さ+曲がりの罰則+既存物への近接の罰則。同費用は座標の辞書順で決定的に選ぶ。
- **接続の種類ごとの動かし方の規則**(S-5bで実機に合わせて確定): シャフトは直線(曲がるには歯車かギアボックス)、ベルトは直線と斜め(段差)で、曲がりは別のベルトへの受け渡し、シュートは縦、パイプは3方向。
- 障害物: 壁・床・既存部品・スロットの余白。見つからなければ`NoRoute(blockers=[…])`で、邪魔している部品のIDを返す。
- 結果: 中間部品(`PlanNode`)の一覧と、接続ごとの経路。AIは書かない(P-5)。
- **`ReservedSpace`との関係(F-28)**: 経路探索の格子では、用途の合う`ReservedSpace`(`TRANSPORT_PATH`で同じ`transportProfileId`)の中は既存物と見なさず(ペナルティ無し)、用途の合わない予約・`CIRCULATION`の予約・建屋は障害物として避ける。「先に空きを確保し、経路はその中を探す」順序で、`E-NO-ROUTE`を減らす。

### 4.7 Factory Analyzer(N-19)と挙動モデル

- **回転ネットワーク(`KineticModel`)**: 部品を頂点、伝達可能な隣接を辺とする無向グラフを作り、動力源から幅優先で、各部品の軸・回転方向・回転数を伝える。歯車どうしの噛み合いは逆回転、大小の歯車は速度比、ギアボックス・クラッチ・ギアシフトなどは部品ごとの規則。**矛盾(同じ部品に逆の回転が伝わる等)は`E-ROT-CONFLICT`**。ネットワークごとに、消費と供給を集計して`E-STRESS-OVER`を判定。
- **物流グラフ**: 機械・保管庫・搬入出口を頂点、ベルト・シュート・ファンネル・トンネルを辺として、(a)`ProcessGraph`の各流れ(入力→機械→出力)に経路があるか、(b)行き止まり、(c)詰まりの危険(出力先が満杯になりうる、合流の順序、ファンネルの向き)、(d)辺ごとの流量が要求を満たすか、を検査。液体も同様。
- 出力: `Issue`の一覧と、`PredictionReport`(製品ごとの毎分の生産量、応力の使用率、各ネットワークの回転数)。
- **時刻や信号で状態が変わる部品**(クラッチ、ギアシフト、可変チェーンギアシフト、回転速度コントローラー、シーケンスギアシフトなど)は、静的なグラフだけでは表せない。`KineticModel`は、これらを**宣言した状態**(`PlanNode.params`の`assumedState`。例: クラッチ=接続、シーケンス=初期段階)で評価し、`W-DYNAMIC-PART`(他の状態は未検証)を出す。宣言した各状態は、試運転の検査項目になる。運用でレッドストーンなどで状態を切り替える場合は、切り替え後の状態ごとに、別の`assumedState`として検証する。
- **未対応の部品**は`W-UNMODELED`を出し、推測しない。**解析の通過を「検証済み」と表示しない**: `W-UNMODELED`の部品を含む計画は、承認画面に「予測モデルが無い部品を含む(試運転で確認)」と表示し、**その部品を試運転の検査項目に必ず入れ**、施工後の試運転が通って`COMMISSIONED`になるまで、工場は「試運転待ち(未検証)」と表示する(`03` L4の終了条件。ジョブの`VERIFIED`=施工が施工リストどおり、とは別の状態、`01` 8節)。

### 4.8 忠実度テスト(`FidelityLab`)

- 対象: 登録簿の全部品、モジュールテンプレートの全部。
- 内容: (a)**設置**: 施工リストどおりにブロックとブロック状態が入るか。(b)**動作**: 回転数・応力・品物の流れ・加工の結果が期待どおりか。(c)**予測の一致**: `KineticModel`/`PredictionReport`の予測と、実測の差が許容(回転数は厳密、生産量は±10%を初期値とし、実測で調整)以内か。
- 実行: NeoForgeのGameTest(`runGameTestServer`)。テスト用の空間に、部品を設置し、指定した数のtickを進めて観測する。S-7で、ModDevGradle 2.0.141でGameTestサーバーが動くことを確認する。動かなければ、開発者用コマンドで同じことを実機で行う。
- 結果は、部品ごとの基準表(`fidelity-baseline.json`)に記録し、Createの更新時に差分を見る。

### 4.9 試運転(`Commissioning`)と Runtime Monitor(N-30)

- **試運転**(建て終わった直後、承認済みの施工が`VERIFIED`になったあと): (1)回転ネットワークの状態を読む(回転数、過負荷の有無)。(2)`ProcessGraph`の各入力口へ、テスト用の品物を入れる(入力口のアイテム容量に挿入。`IItemHandler`のブロック機能を使う。**Createの`BlockEntity`のアクセス方法はS-5cで確認**(`KineticBlockEntity`の公開メソッド`getSpeed()`・`isOverStressed()`・`calculateStressApplied()`の存在は`javap`で確認済み))。**投入品はサバイバルでは所有者から実際に消費し、製品は所有者へ返す**(無料で品物を生み出さない。`04` F-24)。(3)出力口に、期待する製品が期待の時間内に出るかを観測する。(4)結果を`CommissioningReport`(通過/失敗、実測値)にし、失敗は`Issue`にする。(5)テスト品物の残りを回収して所有者へ返す(途中で落ちた場合も、記録から回収する。冪等)。**熱源の燃料の有無を試運転の前に確認**し、無ければ`W-FUEL-SUPPLY`を出す。組み立てで作られた構造物(風車・飛行船)は、`AssemblyExpectation`を満たすかも検査する。
- **運転中の観測**(`RuntimeMonitor`、ユーザーが有効にした時だけ): **出力口の在庫を1秒ごとに標本抽出し、増えた分(正の差分)だけを生産として数える**(取り出しで減った分は数えない。標本の間に出し入れが重なる場合は過小になるので、`RateEstimate`の**下限推定**として扱う)。プレイヤーが外から入れた物が過大に数えられないよう、`mod:output_dock`は**工場の側からしか入らない専用の計測用の保管庫**(プレイヤーは入れられず、取り出し口は別)を持つ設計とし、その保管庫の差分だけを数える。機械の停止(回転数0、過負荷)、ベルトの滞留、出力の満杯を検出。計画値との差から、ボトルネックの候補を順位づけして`RuntimeReport`を出す。観測はチャンクが読み込まれている間だけ。

### 4.10 Space Keeper(N-35)・既存建築の計測(N-36)・Zone Layer(N-37)(Task 26で追加)

- **Space Keeper(N-35)**: 2つの検査。(a)**予約の侵入**: `ReservedSpace`の体積に部品・足跡が重なれば`E-RESERVED-CONFLICT`(侵入した対象のID・重なりの体積つき)。比較は`E-OVERLAP`と同じく体積の交差で、`ZoningPlan`(敷地レベル)と`SemanticPlan`(建屋内レベル)の両方の予約を対象にする。(b)**動線の実在**: `CirculationReq`ごとに、`VoxelClassGrid`上で、出入口から各端点へ`TransportProfile`の断面(`minWidth`×`minHeight`)の**空気の連なり**が実在するかを洪水塗りで確かめる(4.2節の部屋の検出と同じ手法)。通れなければ`E-CIRCULATION-BROKEN`(塞いでいる位置つき)。**部品が置かれる前の段(Site Planner・Module Plannerの出力)で先に検査する**ので、「置いてから失敗」にならない。
- **既存建築の計測(N-36)**: 区画内の既存の建物を`ExistingStructureProfile`にする。(a)`OWN_PLAN`は`PlacedRegistry`・保存済み計画から正確に。(b)`RECOGNIZED`は`ServerStateReader`→`VoxelClassGrid`から: 通路の幅・高さは空気の連なりの断面を測り(4.2節と同じ)、材質はブロックIDの分布(`materialHistogram`)、様式の手がかり(柱のリズム・屋根の形)は決定論の規則で拾う。**不確かな所は推測で埋めず「不明」とし、`confidence`と`basis`を必ず付ける**。認識がどこまで可能かはS-18で実測してから約束する。
- **Zone Layer(N-37)**: (a)**窓の照合**: 接続・物流・動力がゾーンをまたぐとき、両側の`Window`の種類・位置(`span`)・`transportProfileId`が一致することを検査し、合わなければ`E-WINDOW-MISMATCH`。(b)**ゾーン別の予算**: `PlanCompiler`・`AnalysisPipeline`をゾーン単位で呼び、各ゾーンの`ZoneBudget`を超えたら`E-ZONE-BUDGET`でそのゾーンだけ戻す(L10)。全体の上限(台帳T11-3の400,000回)は残すが、1つの巨大ゾーンが全体を使い切る形にはしない。(c)**`ZoneSummary`の生成**: AIの文脈用に、役割・範囲・窓・指標・日本語の要約を出す(`01` 14.4節)。
