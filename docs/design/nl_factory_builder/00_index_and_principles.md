# 自然言語 → 工場建設 設計図(全体編)

- 状態: **ドラフト(林さんの承認待ち)**。承認までは実装に入らない。
- 対象ブランチ: `feature/nl-factory-builder`
- 起点: 林さんの引き継ぎ文(v3フロー、L1〜L9)と、その後の明確化(下記 0.2)。
- 読み方: この文書は入口。詳細は同じフォルダの01〜07。

| ファイル | 内容 |
|---|---|
| `00_index_and_principles.md`(この文書) | 位置づけ、原則、全体構造、決定事項、確認済みの事実、用語 |
| `01_data_model.md` | 全データ型(設計データ・施工リスト・問題・ジョブ・画像ほか) |
| `02_node_map.md` | v3の**全ノード**を、担当部品・入出力・既存コードとの対応・完了条件つきで |
| `03_loops.md` | **L1〜L9(+L4'・L5')全ループ**の仕様(上限・終了条件・ユーザーへの返し方) |
| `04_foundations.md` | 横断基盤(サーバー権威・保存・権限・安全・材料・AI呼び出し・通信ほか) |
| `05_parts_and_analyzers.md` | 部品語彙、モジュールライブラリ、全アナライザ(決定論部品) |
| `06_ai_and_images.md` | AIの各段(プロンプト・スキーマ・ツール)、画像パイプライン、視覚照合 |
| `07_phases_and_verification.md` | 施工順序(フェーズ)、各完了条件、スパイク、テスト、網羅性チェック表 |

---

## 0. 位置づけ

### 0.1 この設計図が守ること(最重要)

**MVPは「優先順位が高い」という意味であり、「MVPでないものは作らなくてよい」という意味ではない。**
(林さんの指示、2026-09-25。記憶: `feedback_design_full_before_implement_no_mvp_shrink`)

- v3フローの**全ノードと全ループ**が、この設計図のどこかに担当・入出力・完了条件つきで載っている。「対象外」「後回し」の欄は無い。
- 07に、ノード×フェーズ、ループ×フェーズの**網羅性チェック表**を置く。1つでも空欄があれば設計図の不備として扱う。
- フェーズは「先に造るもの」の順序であり、範囲ではない。全フェーズを造る。
- 未確定のこと(Createの部品の細部、Aeronauticsの操縦方法など)は「対象外」にせず、**スパイク(先に行う調査)**を置き、結果で設計を確定する。
- 各フェーズの完了は、空のスタブやモックでは判定しない。**実際に動く成果物**を実測して判定する。

### 0.2 林さんの意図(そのまま反映)

- ベース層 = 元ネタBananacraft方式(画像 → 構造記述 → 意味を持った部品単位の関数呼び出し)で**建物を建てる**。
- 工場層 = 同じ方式をもっと細かい粒度(機械・シャフト・ベルト・ポート、`connect(portA, portB)`)まで落とし込み、**物流の整合性**を決定論のアナライザで検証する。ベース層の上に載る。
- 家・小屋は工場に至る通過点。ただし通過点でも、ベース層として完成させる(手抜きの殻にしない)。
- 画像生成は必ず残す。画像を全体のゴールにして、構造側がそこへ収束する(L5・L5'は絵と実物を双方向に収束させる)。
- **画像は、JEIで検索して出てくるCreateの実在部品(メカニカルプレス、デポ、メカニカルベルトなど)を使って作る。** その絵を参考に、整合性のある工場を作っていく。絵に描かれる機械は、実際に置ける部品だけにする(林さんの明確化、2026-09-25)。
- 各ループには反復上限と終了条件を持たせ、超えたら自動で回さずユーザーに返す。トークンコスト(特に画像を含むL2・L5)に注意する。
- AIが出した手順を、人が読んで直せるようにする(独自言語のスクリプトとして見える)。

### 0.3 前提環境(実測)

- NeoForge 1.21.1 (21.1.238)、Java 21、Create 6.0.10、Create Aeronautics 1.3.0(bundled。同梱jarの中に`aeronautics`・`simulated`・`offroad`の3つのmodが入っている)、Sable 2.0.3。ブランチは`feature/nl-factory-builder`、Phase 1・2は実装済み(コミット`75b94a0`まで)。
- Claude Code CLI 2.1.282、Codex CLI 0.144.1(2026-09-25時点)。

---

## 1. 設計原則(v3の知見 + 林さんの方針 + 既存コードの実態)

| # | 原則 | 出どころ |
|---|---|---|
| P-1 | 「何があるか」(構造記述)と「どう作るか」(座標化)を分ける。絵→座標を一発でやらせない | Bananacraft |
| P-2 | AIの出力はスキーマ付き。自由なJSON文字列は受け取らない(`--json-schema`) | Bananacraft |
| P-3 | ボクセルではなく、意味を持った部品(壁・屋根・窓、機械・ポート)で指示する | Bananacraft |
| P-4 | 位置は向きに依存しない局所座標(壁ローカル、ポート相対)で指定する。`connect(A.out, B.in)` | Bananacraft |
| P-5 | AIは関係(トポロジー)、決定論コードは幾何・数値・経路・検証を担当する | Bananacraft |
| P-6 | レシピはAIの記憶に頼らず、ゲーム内`RecipeManager`から候補を取得して選ばせる | 林さん方針 |
| P-7 | 構造は一括、細部(機械・装飾)はドローンが1個ずつ設置して見せ場にする | 林さん方針 |
| P-8 | 画像を全体のゴールとし、構造側が収束する。フィードバックループを全段に入れる | 林さん方針 |
| P-9 | **ワールドを書き換える経路は1本だけ**: サーバー側の施工ジョブ。AIは書き込みツールを持たない | Codexの指摘+既存の差分レビュー設計 |
| P-10 | **サーバー権威**: 検証・承認・施工はサーバーが決める。クライアントの検証は事前チェックにすぎない | 既存の`RunScriptPayload`経路、マルチプレイ |
| P-11 | 承認するのは「手順書(スクリプト)」ではなく、サーバーが決定論で作った**施工リストのハッシュ** | Codexの指摘(実行結果が変わりうるため) |
| P-12 | 決定論の部品は純Java(Minecraftのクラスを一切importしない)。テストが速く、実物と切り離せる | 既存の`PlotGeometry`の教訓 |
| P-13 | 実物のCreateとの一致は、実際にゲームを動かして確かめる(予測モデルだけを信じない) | 林さんの「実測でしか言わない」 |
| P-14 | 各ループは、上限・終了条件・上限超過時のユーザーへの返し方を持つ。費用は台帳に記録する | 林さん方針 |
| P-15 | 既存の畑ドローン機能を壊さない(既存の`DroneApi`の意味は変えない) | 既存機能の保護 |
| P-16 | **絵・AI・施工・検証は、同じ部品の語彙を使う**。語彙の正本は`PartTypeRegistry`(実在するCreate/Aeronauticsの部品)。画像の指示文、AIの出力スキーマ、絵から読み取る構造記述、施工、点検のすべてが、この登録簿から自動で作られる | 林さんの明確化(絵は実在部品で作り、その絵を参考に整合した物を作る) |

---

## 2. 全体構造

### 2.1 層

```
              ┌──────────────────────────── ユーザー(日本語・承認・ダメ出し) ────────────────────────────┐
              │                                                                                        │
  [J] 知見     │  [H] レビュー画面      [F] AI進行管理(クライアント側)          [G] 画像                │
  Knowledge    │  ホログラム/予測/並列   BuildOrchestrator(状態機械)           codex exec                │
  Library成長  │  承認・ダメ出し        Claude(構造化出力・視覚)・MCP読取ツール   Claude Vision            │
              │                                                                                        │
              └───────────┬────────────────────────┬──────────────────────────────┬────────────────────┘
                          │ SemanticPlan(JSON)      │ 質問/回答(読み取り専用)      │ 画像ファイル
                          ▼                        ▼                              ▼
  [B] 意味の核(純Java、Minecraft非依存)                                  [I] 視覚照合
  SemanticPlan → PlanCompiler → PlacementManifest(+hash)                  In-game Renderer → Vision Critic
  PartTypeRegistry / 検証 / 差分(ManifestDiff)                               (L5 / L5')
       │                     ▲
       │  [D] ベース層解析    │  [E] 工場層解析
       │  BlueprintAnalyzer   │  Capacity / Router / FactoryAnalyzer / RecipeResolver
       │  ZoningFixer         │  ModuleLibrary
       ▼                     │
  [C] サーバー施工ランタイム(Minecraftのサーバー側)
  承認トークン → ConstructionJob(保存される) → 施工(1個ずつ) → 状態つきスナップショット → 厳密な差分 → 修復(L7)
  安全枠 / 材料 / 権限 / ドローン演出 / 試運転 / Runtime Monitor
```

- **[B]が中心**。ここの型(`SemanticPlan`、`PlacementManifest`)に、ベース層も工場層も、AIも人間のスクリプトも、全部が集まる。
- 人間が読む形は、`SemanticPlan`から機械的に作る独自言語スクリプト(往復変換できる)。
- 画像は目標であり、構造の正本ではない。Claudeが絵から`StructureDescription`を作り、それを`SemanticPlan`に落とす。

### 2.2 1回の依頼の流れ(要約)

1. ユーザーが日本語で依頼 → **Refiner**(Claudeとの対話)が生産物・生産量・動力源・外観を詰める。
2. **ConceptImg**(`codex exec`)が工場全景の絵を作り、ユーザーが承認(L1)。
3. **Vision Reader**が絵を構造記述にし、**Process Planner**が工程グラフを作り、**Capacity Calculator**が台数・動力・床面積を出す(L3)。**Reconciler**が絵と工程を突き合わせる(L2)。
4. **Site Planner** → **Zoning Fixer** → 建屋ごとに**DesignGen**(完成予想図・構造用画像・内部断面図) → **Architect**(2段階) → **Carpenter**(施工リスト化)。
5. **Blueprint Analyzer**が建屋の意味地図と内部空間(スロット)を出す → **Module Planner**が機械を置き、`connect`で接続 → **Router**が経路を作り → **Factory Analyzer**が検証(L4・L4')。
6. OKなら**Decorator**(装飾)と**Logistics Planner**(発着場・飛行ルート)。
7. **In-game Renderer**が、施工前の**PREVIEW撮影**(クライアント側だけで描く不透明プレビュー。世界は書き換えない)を、コンセプト画像と同じカメラ角度で撮影 → **Vision Critic**が絵と比較(L5・L5')。
8. **Preview**(ホログラム、絵と並べた表示、予測レポート、材料表)→ ユーザー承認(L6)。
9. サーバーが**施工ジョブ**を実行。ドローンが動力→上流→下流の順に設置(L7で検査・再施工)。施工後に**AS_BUILT撮影**で最終確認。
10. **試運転**と**Runtime Monitor**が実測(L8)。成功も失敗も**Knowledge Store**へ(L9)。

### 2.3 題材(全体を通す確認シナリオ)

この設計図の全フェーズの最終確認に使う依頼文:

> 「**鉄板と真鍮を作る工場**。屋根は赤、飛行船の発着場つき」

Createに**実在する**品物だけで組んである(4.1節で実物のjarから確認済み)。

| 生産物 | Createでの作り方(確認済み) | 必要になる部品(この依頼が確かめること) |
|---|---|---|
| 鉄板(`create:iron_sheet`) | プレス(`create:pressing`)。材料は鉄インゴット | 動力、プレス、デポ、搬入・搬出 |
| 真鍮インゴット(`create:brass_ingot`) | 混合(`create:mixing`)、**加熱が必要**。銅インゴット+亜鉛インゴット → 2個 | 動力、ミキサー、ベイスン、ブレイズバーナー(熱源)、材料の搬入 |

- 動力は水車・風車・蒸気機関などから、建屋の位置と生産量に合うものをCapacity Calculatorが選ぶ。
- 発着場は、Aeronauticsの飛行船を止める場所。搬入と搬出の口に物流でつなぐ。
- **注意(引き継ぎ文の例文との違い)**: 引き継ぎ文の例は「鉄板とネジ」だったが、「ネジ」はCreateに無い。題材はCreateの実在品に直した。

**存在しない品物を頼まれたとき**(例: 「ネジを作って」)の振る舞いも、仕様として決めておく。Recipe Resolverが該当レシピ無しを返し、Refinerがユーザーに「Createには無い。近い品物はこれ」と返して確認する。勝手に別の品物に置き換えない(`02_node_map.md`のProcess Planner)。

---

## 3. 主要な決定事項(D-番号)

「これでいく」と決めたもの。異論があれば林さんが差し替える。

| # | 決定 | 理由 |
|---|---|---|
| D-1 | ワールドを変える経路は、サーバーの`ConstructionJob`のみ。AIに書き込みツールを渡さない | 二重経路(AI直接実行とスクリプトレビュー)は、人間のレビューをすり抜ける |
| D-2 | 設計データ(`SemanticPlan`)が正本。スクリプトは往復変換できる「見え方」 | 決定論・ハッシュ・検証がデータ上で完結する。人も読んで直せる |
| D-3 | **承認の対象は`PlacementManifest`のハッシュ**。サーバーが自分で作り直したものだけ承認できる | クライアントが偽の施工リストを承認させられない。スクリプトの乱数などで結果が変わっても関係ない |
| D-4 | 材料はゲームモードで決める: **クリエイティブ=無償(材料表は表示)、サバイバル=オーナーの持ち物と現場の補給チェストから消費**。両方実装する | 主目的は「面白さ」。サバイバルで無償にすると価値が消える。両方あれば設定で切り替えられる |
| D-5 | AIの各段は**状態を持たない呼び出し**(必要な材料を毎回明示的に渡す)。会話するのはRefinerだけが再開可能なセッション | 再現性、テスト(記録・再生)、キャッシュが効く。ドリフトを避ける |
| D-6 | 決定論の解析は**クライアントで事前チェック、サーバーで権威チェック**。同じ純Javaコードを両方で使い、ハッシュが一致することを確認する | 往復を減らしつつ、権威はサーバー |
| D-7 | Createとの一致確認は、実際のサーバーを動かすテスト(NeoForgeのGameTest。無理なら実機の自己診断コマンド)で行う | レジストリが要るためJUnitでは不可能。P-13 |
| D-8 | 画像生成は`codex exec`(実測済み)、Claudeへの画像渡しは`--input-format stream-json`(実測済み) | 4節の確認済み事実 |
| D-9 | 応力の数値は、実行時にCreateの`BlockStressValues`(公開API)から読む。コードに埋め込まない | 設定で変わる。Createの更新に追従する |
| D-10 | 全ループは`LoopBudget`(上限・閾値・超過時の動作)を持ち、超えたら止まってユーザーに状態と選択肢を返す。費用は`CostLedger`に記録する | 林さんの方針 |
| D-11 | ドローンは「演出役」。実際の設置は、保存されるサーバーtickのジョブが行う。`PacedActionQueue`は施工ジョブに使わない(メモリ上のみで、停止時に消えるため) | Codexの指摘。既存の`PacedActionQueue`の実態(ConcurrentLinkedQueue、非永続) |
| D-12 | ジョブの所有者は承認した人。取消・承認は所有者かOPのみ。重複する区画は`SiteClaim`で予約する | 既存の所有者は「最後に実行した人」で権限判定に使われていない(実測) |
| D-13 | 部品登録は要求するCreate/Aeronauticsの版範囲を持つ。範囲外なら該当部品を無効化して理由を明示する | 更新で静かに壊れるのを防ぐ |
| D-14 | **建築後の変更**(L4'・L5・L6・L8)のため、`ManifestDiff`(旧→新の差分)と`MODIFY`ジョブを核に含める | 一度建てたものを直す前提が無いと、収束ループが成り立たない |
| D-15 | 施工用スクリプトは「決定論プロファイル」で実行する(乱数・時刻・畑の知覚は禁止)。農場用と混在させない | スクリプト実行結果が変わると、レビューの意味がなくなる |
| D-16 | 純Javaの核(`build/`)がMinecraftをimportしていないことを、テストで機械的に検査する | P-12を人の注意に頼らない |
| D-17 | **部品見本帳(`PartAtlas`)**を作る。登録簿の全部品を、ゲーム内で描画して1枚の見本画像にし、部品名(JEIと同じ表示名)を添える。この見本を、画像生成(`codex exec -i`)とClaudeの視覚(絵から部品を読み取る)の両方に参考として渡す | 画像生成AIはCreateの機械の見た目を知らない。実物を見せて、実在部品だけで描かせる。絵から部品を読み取る側も、同じ見本と名前で照合できる |
| D-18 | 部品の表示名は、ゲームの言語ファイル(JEIと同じ翻訳キー)から取る。ユーザーが見る名前(例: メカニカルベルト)と、AIに渡す名前・スキーマの選択肢を一致させる | 林さんが「JEIで検索して出るもの」と言った通りの名前でやり取りできる。内部の識別子(例: `create:belt`)との対応は登録簿が持つ |
| D-19 | 絵から読み取る構造記述(Vision Readerの出力スキーマ)の部品名は、登録簿の識別子の**列挙(enum)に限る**。登録簿に無い機械は、出力できない | 絵の中の想像上の機械が、そのまま設計に紛れ込むのを防ぐ。登録簿に無い物が描かれていたら「該当部品なし」として扱い、Reconcilerに返す |
| D-20 | **施工順序は、依存の下から上(ベース層 → 工場層)を優先する。** 引き継ぎ文のMVP候補(「鉄インゴット→鉄板を先に、L4とL7を先に」)は、林さんの後の明確化(ベース層が先、家は通過点)で置き換わった。ループの実装順は、L7=P4(最初)、L4=P12。**優先順位が高い物を先に造るが、後の物も全部造る** | 引き継ぎ文の候補と設計図の順序が違う理由を、決定として残す。工場層はベース層の`SemanticMap`を入力にするので、下が無いと上が成り立たない(記憶`project_factory_builds_on_bananacraft_base`) |
| D-21 | 引き継ぎ文の「建屋はCreateの設計図(.nbt)として出力」は、**独自の施工リスト(`PlacementManifest`)に置き換える**。ホログラム・承認・ジョブ・差分・ロールバックが、ブロック状態と組み立てを含む1つの型で完結するため。必要なら`.nbt`への書き出しは、施工リストからの変換として後から足せる | `.nbt`はブロックの並びを持つが、承認のハッシュ・材料表・部品ID(エラーの逆引き)・組み立て手順を持てない |
| D-22 | **置けるブロックを許可制にする**(`PlaceableBlockPolicy`)。素材(`StyleSpec.palette`)と部品が生成するブロックは、許可リスト(`micradrone:palette_allowed`タグ+登録部品の出力)に載る物だけ。コマンドブロック・岩盤・スポナー・バリア等は常に禁止(`E-BLOCK-FORBIDDEN`)。AIが素材に何を書いても、サーバーの検査で弾く | 置換の制限(F-5)だけでは、「何を置けるか」が無制限になり、通常は権限が要るブロックを、権限なしで置けてしまう |

---

## 4. 確認済みの事実と、未確認の事実

### 4.1 実測で確認済み(2026-09-25)

| 事実 | 確かめ方 |
|---|---|
| `claude -p --input-format stream-json`にbase64のPNGを渡すと画像を認識する。既存の安全フラグ(`--setting-sources "" --restricted --strict-mcp-config --tools ""`)のままで動く | 赤い64×64のPNGを送り、応答`"赤"`、所要4.1秒、費用$0.0129 |
| `--json-schema`を付けると、応答JSONに`structured_output`(解析済みオブジェクト)と`result`(JSON文字列)の両方が入る。`total_cost_usd`と`num_turns`も入る | 型付きの小さなスキーマで実行し、`{"color":"blue","n":1}`を確認。`num_turns`は2、費用$0.0214 |
| `codex exec --enable image_generation -s workspace-write`は、非対話で画像(PNG)を生成できる。追加のAPIキーは不要 | 本セッションの前半で実際に生成し、1254×1254のPNGを確認 |
| `claude -p --input-format stream-json`は、**`--output-format stream-json`とセットでないと起動しない**(`--output-format json`にすると`Error: --input-format=stream-json requires output-format=stream-json.`)。`--json-schema`との併用は動作し、最後の`result`行に`structured_output`と`result`が入る(`num_turns`は2) | Opus 5.5のレビュー担当と、私が同じ結果を実測(2026-09-26) |
| Aeronautics同梱jarの中の`simulated`(95ブロック)に、飛行船の組み立て・操縦・係留の部品がある: `physics_assembler`(物理アセンブラ)、`docking_connector`(ドッキングコネクター)、`paired_docking_connector`(ペアリングされたドッキングコネクタ)、`steering_wheel`(舵輪)、`throttle_lever`(操縦桿)、`navigation_table`(羅針盤)、`rope_connector`、`rope_winch`、`swivel_bearing`、各種センサー(`altitude_sensor`、`velocity_sensor`、`gimbal_sensor`、`optical_sensor`、`laser_sensor`)、`redstone_magnet`、色付きの`symmetric_sail`と`portable_engine`など。`offroad`(3ブロック)は`borehead_bearing`、`rockcutting_wheel`、`wheel_mount` | 同梱jarの中のjarのblockstatesと`assets/simulated/lang/ja_jp.json` |
| `create:powered_shaft`(パワードシャフト)は**アイテムを持たない**(item modelが無い)。`create:belt`はブロックだけで、アイテムは`create:belt_connector` | jar内`assets/create/models/item/`の有無 |
| `codex exec`には`-i/--image`(画像入力)、`--output-schema`、`--json`、`-C/--cd`、`--ephemeral`がある | `codex exec --help` |
| **`codex exec -i`で渡した参考画像2枚が、画像生成に反映される**。橙背景の緑の三角形と、濃紫背景の黄色い円環の2枚を渡し、両方のモチーフと配色を1枚に入れた画像が生成された。指示文は標準入力(`-`)で渡し、`-C`で作業フォルダ、`-s workspace-write`、`--skip-git-repo-check`を付けた。所要は約2分、Codex報告は25,224トークン。**作業フォルダに`.agents/`と`.git/`が作られた**(副作用) | 本セッションで実際に実行(モデル`gpt-5.6-sol`)、生成画像を目視で確認。参考画像の**枚数上限**の実測はまだ(スパイクS-3) |
| 画像生成の参考画像の上限: GPT Image系のAPIは**最大16枚**。Codexの組み込みツール経由の上限は、資料に記載が無い。出力画像の制約(`gpt-image-2`): 最大辺3840px以下、両辺16の倍数、縦横比3:1以下、総画素数655,360〜8,294,400 | Codex同梱の`~/.codex/skills/.system/imagegen/references/image-api.md`と`SKILL.md` |
| **Claudeに画像を渡す上限**: 1リクエストあたり最大600枚(200kコンテキストのモデルは100枚)、1枚10MB(base64)まで、1枚8000×8000pxまで。**20枚を超えると各辺2000px以下**が必須。長辺は高解像度モデル(Claude 4.7以降)で2576px、標準で1568pxに自動縮小。トークン費用は`⌈幅/28⌉×⌈高さ/28⌉`。リクエスト全体は32MBまで。複数画像には`Image 1:`のようなラベルを付け、画像を文章の前に置くと良い。claude.aiは1メッセージ20枚 | Anthropic公式「Vision」資料(2026-09-25に取得) |
| Create 6.0.10には公開API`com.simibubi.create.api.stress.BlockStressValues`があり、`getImpact(Block)`・`getCapacity(Block)`(静的、`double`)と、`IMPACTS`・`CAPACITIES`・`RPM`のレジストリがある | jarのクラス一覧と`javap`(2026-09-26) |
| 鉄インゴットのプレス加工は、`c:ingots/iron`(タグ)→`create:iron_sheet`のレシピ(`create:pressing`) | jar内`data/create/recipe/pressing/iron_ingot.json` |
| Createのレシピ種別(数): pressing 52、mixing 18、milling 261、crushing 226、splashing 65、haunting 26、compacting 8、deploying 168、cutting 43、sequenced_assembly 4、mechanical_crafting 5、filling 29、emptying 10、item_application 9、sandpaper_polishing 2、blasting 28、smelting 38、smoking 2、campfire_cooking 2、crafting 378 | jarのフォルダ集計 |
| Create Aeronautics 1.3.0の部品: 気球エンベロープ(16色+シャフト付き)、`adjustable_burner`、`andesite_propeller`/`wooden_propeller`/`smart_propeller`、`propeller_bearing`、`gyroscopic_propeller_bearing`、`levitite`系、`steam_vent`、`mounted_potato_cannon` | 同梱jarのblockstates |
| 「ネジ」「ボルト」「釘」に当たる品物は、導入済みの**全12個のmod**(内包jarを含む)に存在しない(blockstates・item model・レシピ名を検索) | 全jarの走査。引き継ぎ文の例文「鉄板とネジを作る工場」は、Createの品物ではなかった。→ 題材は下の2行の実在レシピに差し替える(2.3節) |
| 真鍮インゴット: `create:mixing`、`heat_requirement: heated`、銅インゴット(タグ`c:ingots/copper`)+亜鉛インゴット(タグ`c:ingots/zinc`) → `create:brass_ingot`×2 | jar内`data/create/recipe/mixing/brass_ingot.json` |
| 加熱の熱源となる部品`blaze_burner`(点灯状態`lit_blaze_burner`)と、混合用の`basin`、`mechanical_mixer`がある。安山岩合金(`andesite_alloy`)・小麦粉(`milling`)も実在 | jarのblockstatesとレシピ名 |
| JEIに出る表示名(日本語): `mechanical_press`=メカニカルプレス、`depot`=デポ、`belt_connector`(アイテム)と`belt`(ブロック)=**メカニカルベルト**、`mechanical_mixer`=メカニカルミキサー、`basin`=鉢、`blaze_burner`=ブレイズバーナー、`water_wheel`=水車、`shaft`=シャフト、`cogwheel`=歯車、`andesite_funnel`=安山岩ファンネル、`brass_funnel`=真鍮ファンネル、`chute`=シュート、`encased_fan`=ケース入りファン、`millstone`=石臼、`mechanical_arm`=メカニカルアーム、`item_vault`=アイテム保管庫 | jar内`assets/create/lang/ja_jp.json`(日本語は3447キー、英語は3639キー) |
| 既存コード: `MAX_SCRIPT_CHARS=10000`、`RunScriptPayload`は`(BlockPos, scriptName)`のみ、`saveScript`に権限判定なし、`ownerUuid`は最後の実行者、`PacedActionQueue`は非永続 | 該当ファイルを読んだ |

### 4.2 未確認(スパイクで確定してから設計を凍結する。一覧は07)

- Createの各部品の**正しい設置手順**(ベルトの連結、ファンネルの向き、メカニカルアームの対象設定など)。
- Createの回転・応力ネットワークの**実際の挙動が予測モデルと一致するか**。
- Aeronauticsの**飛行船の組み立て方と操縦の自動化方法**。
- サーバー向けカスタムペイロードの**上限サイズ**(バニラは約32KBと理解しているが実測が必要)。
- `codex exec`に渡せる参考画像の**枚数の上限**と、枚数を増やしたときの品質低下(スパイクS-3で1・2・4・8・16枚を測る)。参考画像を1枚渡す基本動作は、4.1節のとおり実測済み。スクリーンショットからの描き直し(L5')が、同じ仕組みで成り立つかは、S-3で確認する。
- ModDevGradle 2.0.141で**GameTestサーバー**が動くか。
- 画像認識の**精度**(絵→構造記述、絵と実物の比較)。実測は方針の妥当性を確かめる評価セットで行う。

---

## 5. 用語(平易な言い換え)

| 用語 | ひらたく言うと |
|---|---|
| 設計データ(`SemanticPlan`) | 「壁がここ、窓がここ、機械がここで、これとこれをつなぐ」を、部品の名前で書いた設計図のデータ |
| 施工リスト(`PlacementManifest`) | 設計データを、「この座標にこのブロックを置く」の一覧に直したもの。ハッシュ(指紋)つき |
| ハッシュ | 中身が1文字でも違えば変わる「指紋」。承認したものと実行するものが同じだと証明する |
| サーバー権威 | 「先生(サーバー)が決める。生徒(クライアント)の申告は参考」というルール。マルチプレイで大事 |
| 決定論 | 同じ入力なら必ず同じ結果になる計算。AIの気まぐれが入らない |
| アナライザ | 建物や工場を点検する係(計算だけで、AIは使わない) |
| ポート | 機械の「つなぎ口」。「このシャフトの出口」と「あのプレスの入口」をつなぐ、というふうに使う |
| 部品登録簿(`PartTypeRegistry`) | 「この工場で使ってよい部品」の名簿。実在するCreate等の部品だけが載る。絵も、AIも、施工も、この名簿の名前だけを使う |
| 部品見本帳(`PartAtlas`) | 名簿の全部品を、ゲームの中で描いて1枚に並べた見本画像。「メカニカルプレスとはこういう見た目」と画像生成AIに見せるための資料 |
| スロット | 建物の中の「機械を置ける場所」(広さや高さの条件つき) |
| ループ(L1〜L9) | 「やり直しの矢印」。ダメなら前に戻って直す。何回まで、どうなったら終わりか、を決めてある |
| スパイク | 本格的に作る前に、小さく試して事実を確かめる調査 |
| ハンドオフの`connect(A.out, B.in)` | 座標ではなく「AのoutとBのinをつなぐ」と書くやり方。向きが変わっても書き直さなくてよい |


---

## 6. 変更履歴

- **第1版**(コミット`44617b1`・`0bfd447`、2026-09-25): 初版。v3の全31ノード・全ループ・P3〜P15・スパイク13件。
- **第2版**(2026-09-26): **Opus 5.5のレビュー**(重要13件・軽微多数)と**Codexのレビュー**(致命8件・重要26件・軽微3件)を、事実を裏取りしたうえで反映。裏取りした事実は4.1節に追記した(`stream-json`の制約、`simulated`の部品、`powered_shaft`にアイテムが無いこと、既存言語の禁止対象)。
  - **型(`01`)**: 全面改訂。`ConnKind`/`PortKind`に`HEAT`・`DOCK`(`AIR`を廃止)、`ExpandedPlan`・`PlanExpander`・`TemplateBundle`(サーバーの再展開)、`AssemblyStep`・`ASSEMBLED_AWAY`・`BuildPhase.ASSEMBLE`(組み立てで消えるブロック)、`ManifestDiff`の`RemovalEntry`・`Conflict`(世界を壊さない撤去)、`PowerPlantChoice`・`PowerPolicy`(L3の変更対象)、`RecipeOption`の拡張(液体・道具・手順・形状・機械の組み合わせ)、`Flow`の端点、`Visibility`(部品`USER`/`IMPLICIT`)、安定ID(画像の構造記述)、`Caps`(金額・画像回数・Codexトークンの3種)、`SparseSnapshot`・`VoxelClassGrid`(メモリ)、ジョブの記録の別ファイル化、`ChatKey`/`ProjectKey`、未定義だった型の全定義、名前の衝突の解消(`VolumeSpec`・`BuildingFootprint`・`VerifyMode`)。
  - **ループ(`03`)**: 全面改訂。頭打ちのスコアの定義、L2(比率・数・種類で判定、カットアウェイの絵、**再生成でもユーザーが承認**、`HIDDEN`は数えない)、L3の変更対象、L4(受け入れ不可の`ERROR`が残るときは「進む」を出さない、未検証部品の扱い)、L6・L9の実際の停止、画像の無効化(`Invalidation`)、`MODIFY`の`Conflict`、L7の組み立ての扱い、カメラの一致の限界と意味の比較。
  - **基盤(`04`)**: ワーカーと`ConstructionBudget`(重い計算・全体の予算・自動減速)、ジョブの記録の原子的書き込みと復旧、**取消では材料を返さない(複製防止)**、ブロックエンティティの置換を禁止(ロールバックの完了の定義)、`PlaceableBlockPolicy`(D-22)、許可リスト方式の決定論プロファイル、サーバーの再展開(F-3)、`simulated`の扱い(F-11)、F-23(プロジェクトとチャット)、F-24(試運転とサバイバルの経済)。
  - **部品・アナライザ(`05`)**: `HEAT`・`DOCK`の接続、`simulated:`・`offroad:`の部品、`IMPLICIT`の部品、P10の優先度(a/b)、`PowerSourceModel`、テンプレートの検証の由来(同梱/プレイヤー)、新しい問題コード、試運転の経済と燃料。
  - **AIと画像(`06`)**: `stream-json`の制約、画像の合格ゲート(適合率・再現率・誤認率)、Codexの隔離フラグ、費用の3種の上限、カットアウェイ、L8の担当を`Module Planner`に統一。
  - **フェーズ(`07`)**: 全面改訂。P7に`RecipeSource`・`KnowledgeStore`の書き込み・`LoopEscalationScreen`を前倒し、P8に`RouteChooserScreen`、P10をP10a/P10bに区分(全部作る)、Aeronautics部品をP13へ、S-4bの分割、S-5d追加、性能・品質の**数値の合格線**、網羅性表の更新、リスクの追加。
  - **決定事項(`00`)**: D-20(施工順序と、引き継ぎ文のMVP候補との違い)、D-21(`.nbt`を独自の施工リストに置き換える)、D-22(置いてよいブロックの許可制)。
  - **反映しなかった指摘**: Codexの指摘34「部品範囲が必要以上に広い」は、範囲を削る提案なので採用しない(林さんの方針)。代わりに、P10を確認題材に要る部品(P10a)と残り(P10b)の順序に区分し、**両方を作る**こととした。
