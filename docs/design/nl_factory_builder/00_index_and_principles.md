# 自然言語 → 工場建設 設計図(全体編)

- 状態: **実装に着手(2026-09-26)**。林さんが、第3版に基づいてフェーズごとに実装する指示(P3から)を出した。実装で見つかった食い違いは、コードより先にこの設計図を直す(第4版=P3着手前の整合)。
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
| `08_device_model.md` | 機器モデル・学びの層(読む・書く・出来事・安全限界・緊急停止・教材シート。MHSに着想した独自規格) |

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
| P-16 | **絵・AI・施工・検証は、同じ部品の語彙を使う**。語彙の正本は`PartTypeRegistry`(実在するCreate/Aeronauticsの部品)。画像の指示文、AIの出力スキーマ、絵から読み取る構造記述、施工、点検のすべてが、この登録簿から自動で作られる。登録簿は**有効な能力パックの和集合**として作る(D-30。modが無ければそのpackの部品は語彙に出ない) | 林さんの明確化(絵は実在部品で作り、その絵を参考に整合した物を作る) |
| P-17 | **機器の側の安全限界は、AI・スクリプトから超えられない**。機器への指示は、実行の前に必ず機器の側の検査(`DeviceGate`の事前条件・安全限界・緊急停止)を通る。AIは機器の安全を「設定で緩める」手段を持たない | MHSの「安全限界は機器の側が強制する」という考え方(実測の一次情報は`08` 1節)+既存のサーバー権威(P-10)と同じ構え |

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
| D-13 | 部品登録は要求するCreate/Aeronauticsの版範囲を持つ。対象のmodが無い、または範囲外なら該当部品を無効化して理由を明示する(Create/Aeronautics/Sableはoptional依存。無くてもmod自体は動き、そのpackの部品だけが無効になる) | 更新やmodの不在で静かに壊れるのを防ぐ。子供がmodの組合せを自由に選べるようにする(林さんの裁定 2026-09-27) |
| D-14 | **建築後の変更**(L4'・L5・L6・L8)のため、`ManifestDiff`(旧→新の差分)と`MODIFY`ジョブを核に含める | 一度建てたものを直す前提が無いと、収束ループが成り立たない |
| D-15 | 施工用スクリプトは「決定論プロファイル」で実行する(乱数・時刻・畑の知覚は禁止)。農場用と混在させない | スクリプト実行結果が変わると、レビューの意味がなくなる |
| D-16 | 純Javaの核(`build/`)がMinecraftをimportしていないことを、テストで機械的に検査する | P-12を人の注意に頼らない |
| D-17 | **部品見本帳(`PartAtlas`)**を作る。登録簿の`USER`の部品(JEIに出る物。`IMPLICIT`は除く)を全部、ゲーム内で描画して1枚の見本画像にし、部品名(JEIと同じ表示名)を添える。この見本を、画像生成(`codex exec -i`)とClaudeの視覚(絵から部品を読み取る)の両方に参考として渡す | 画像生成AIはCreateの機械の見た目を知らない。実物を見せて、実在部品だけで描かせる。絵から部品を読み取る側も、同じ見本と名前で照合できる |
| D-18 | 部品の表示名は、ゲームの言語ファイル(JEIと同じ翻訳キー)から取る。ユーザーが見る名前(例: メカニカルベルト)と、AIに渡す名前・スキーマの選択肢を一致させる | 林さんが「JEIで検索して出るもの」と言った通りの名前でやり取りできる。内部の識別子(例: `create:belt`)との対応は登録簿が持つ |
| D-19 | 絵から読み取る構造記述(Vision Readerの出力スキーマ)の部品名は、登録簿の識別子の**列挙(enum)に限る**。登録簿に無い機械は、出力できない | 絵の中の想像上の機械が、そのまま設計に紛れ込むのを防ぐ。登録簿に無い物が描かれていたら「該当部品なし」として扱い、Reconcilerに返す |
| D-20 | **施工順序は、依存の下から上(ベース層 → 工場層)を優先する。** 引き継ぎ文のMVP候補(「鉄インゴット→鉄板を先に、L4とL7を先に」)は、林さんの後の明確化(ベース層が先、家は通過点)で置き換わった。ループの実装順は、L7=P4(最初)、L4=P12。**優先順位が高い物を先に造るが、後の物も全部造る** | 引き継ぎ文の候補と設計図の順序が違う理由を、決定として残す。工場層はベース層の`SemanticMap`を入力にするので、下が無いと上が成り立たない(記憶`project_factory_builds_on_bananacraft_base`) |
| D-21 | 引き継ぎ文の「建屋はCreateの設計図(.nbt)として出力」は、**独自の施工リスト(`PlacementManifest`)に置き換える**。ホログラム・承認・ジョブ・差分・ロールバックが、ブロック状態と組み立てを含む1つの型で完結するため。必要なら`.nbt`への書き出しは、施工リストからの変換として後から足せる | `.nbt`はブロックの並びを持つが、承認のハッシュ・材料表・部品ID(エラーの逆引き)・組み立て手順を持てない |
| D-23 | **区画(`SiteClaim`)と設置の記録(`PlacedRegistry`、施工前のブロックの記録`journal`)は、工場が存在する間は保持する。** ジョブの終了では解放しない。所有者が工場を解体して区画を解放したときに、初めて解放する | 完成した工場の内部空間・発着場の上空・機械の作用範囲を他人の施工から守るため。撤去やロールバックで元の地形に戻すには、施工前の記録が要る |
| D-24 | **世界に作用する部品(ドリル・ソー・ハーベスター・プラウ・ホースプーリー・`offroad:`の掘削部品・ポテト砲台など)は、作用が届く範囲(`EffectSpec`)を宣言しないと登録できない。** Factory Analyzerが、作用範囲が区画の中(`operatingBox`)に収まることを検査する(`E-EFFECT-ESCAPES-CLAIM`)。区画外に作用する部品は、設定で許可された時だけ登録する | 「置く」ことの許可制(D-22)だけでは、置いた後の動作で区画外の世界を壊せてしまう |
| D-25 | **撤去・変更できるのは、このプロジェクトが置いたブロック(`PlacedRegistry`に載る物)だけ。** 元からあったブロックエンティティ(プレイヤーのチェスト等)は、置換も撤去もしない。プロジェクトが置いたコンテナ(保管庫・デポ・機械)を撤去するときは、**中身のアイテムをドロップして保全する**(消さない) | ブロックエンティティの置換禁止(F-5)と、機械の撤去(MODIFY・ROLLBACK)を両立させ、プレイヤーの品物を消さない |
| D-26 | **画像の合格ゲート(「実在部品だけ」)**: Vision Readerを**Approve0の前**に走らせ(承認後の段は結果を再利用)、承認画面に認識された部品の一覧と登録簿外の機械の警告を出す。**目立つ登録簿外の機械が有る絵は、承認画面に出す前に自動で1回だけ再生成する。** それでも残る場合は警告つきで提示し、ユーザーが明示的に「この絵で進む」を選べる(上書きとして記録)。適合率・再現率・誤認率は、パイプライン全体の品質の合格線 | 承認後にしか認識一覧が出ない順序では、架空の機械が描かれた絵を、知らずに承認してしまう。画像の最終決定権はユーザーにあるので、明示の上書きは残す |
| D-27 | **承認・施工リスト・ジョブは、ディメンションにも拘束する。** `Site.dimension`を施工リストのハッシュに含め、承認の時点でプレイヤーが同じディメンションにいることを確認する | 承認後に別ディメンションへ移動しても、同じ座標の別のディメンションへ施工されない |
| D-22 | **置けるブロックを許可制にする**(`PlaceableBlockPolicy`)。素材(`StyleSpec.palette`)と部品が生成するブロックは、許可リスト(`micradrone:palette_allowed`タグ+登録部品の出力)に載る物だけ。コマンドブロック・岩盤・スポナー・バリア等は常に禁止(`E-BLOCK-FORBIDDEN`)。AIが素材に何を書いても、サーバーの検査で弾く | 置換の制限(F-5)だけでは、「何を置けるか」が無制限になり、通常は権限が要るブロックを、権限なしで置けてしまう |
| D-28 | **建設スクリプトの命令は`CommandNames.PLAN`に分け、畑用の`CommandNames.ALL`は変えない。** `Interpreter`は`PlanApi`があるときだけ建設の命令を受け付ける。部品を置く命令の名前は登録簿から機械的に作る | `ALL`に`wall`・`place`などの一般的な名前を入れると、`Interpreter`が畑のスクリプトの同名の関数定義を拒否し、公開済みの畑のスクリプトが壊れる(P-15)。命令名を登録簿から作れば、部品を足しても命令とずれない(P-16) |
| D-29 | **座標・向き・ハッシュの規約**: 局所の向きは`NORTH`=+w(前)・`EAST`=+u(右)。計画は局所で作り、`BuildFrame`で世界へ写す(回転不変を構造的に保証)。`contentHash`は`nodes`・`connections`を`id`順に並べて計算し、施工リストのハッシュは`index`・`partNodeId`を含めない | 並びや呼び名が違うだけの同じ設計・同じ建物を、同じハッシュにし、承認の対象を建物の中身に絞るため |
| D-30 | **modの統合は「能力パック(`CapabilityPack`)」方式にする。** `vanilla`(常に有効の基底)、`create`、`aeronautics`(同梱の`simulated`・`offroad`を含む)、`sable`、`create_submarine`、`powergrid`、`create_copper_and_zinc`の各統合を、pack ID・要求するmod IDと版範囲・部品・アナライザ・モジュールテンプレート・知見・輸送プロファイル・レシピ源・未解決スパイクの一覧として持つ。packは、要求するmodが**入っていて版も範囲内のときだけ有効**。**modが無ければそのpackの部品だけが無効**になり、残りは動く(「畑だけ」「バニラの雰囲気建築だけ」でも使える)。**どんな組合せで入っていても動く**(子供がmodを自由に選べる) | 林さんの裁定 2026-09-27(能力駆動・どのmodが入っていてもAIが対応)。optional依存の宣言だけではmodの不在に対応できない(D-13の拡張) |
| D-31 | **未知のmodは、読み解いて報告するが、決して推測で部品にしない。** 起動時に導入modの一覧を走査し、(a)既知のpackが要求するmod、(b)未知だがメタデータ上は無害と見なせるmod、(c)未知かつ取り扱いが危険な可能性のあるmod、に分類して`ModScanReport`を出す。未知のmodのクラスは**純粋な核にも`integration`以外にも読み込まない**。分からない事実はスパイクにし、能力パックに昇格するまでは「部品として使えない」 | 「入っているかもしれないmod」の安全な扱いを、能力駆動(D-30)と同じ仕組みで決める(林さんの裁定 2026-09-27) |
| D-32 | **機器モデル・学びの層は、MHS(Model Hardware Standard)に着想した、このプロジェクト独自の「おもちゃ規格」で作る。** 実在のMHSの仕様は公開されていないので、`MHS準拠`とは呼ばず「MHS式」と呼ぶ。機器は「読む・書く・出来事」の3種の操作と、機器の側が強制する安全限界・事前条件・緊急停止を持つ。AIの指示は、実行の前に必ず機器の側の検査(`DeviceGate`)を通る(P-17) | Anthropicの公開ページにあった考え方(read/write・発見可能・安全限界・事前条件の遮断)を、子供向けに確実に守れる形へ写す。一次情報と推測は`08` 1節で区別する |

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

- **第3版**(2026-09-26): レビュー**2周目**(Opus 5.5とCodex)の指摘を、事実を裏取りして反映。裏取り: `--json-schema`はインライン引数だけ(ファイル指定は失敗)、npmの`claude.cmd`は`claude.exe`を呼ぶだけ、既存の`IdeCameraController`は`package-private`で真上からの視点専用、`ChatSession`・`ChatHistoryStore`が`ControllerKey`型を直接使う。
  - **世界・経済の安全**: ブロックエンティティの所有(`PlacedRegistry`、D-25。撤去できるのはプロジェクトが置いた物だけ、中身は保全)、区画と施工前の記録の存続(D-23。ジョブの終了や30日では消さない)、動作の影響範囲の宣言と区画内の検査(D-24)、整地の確認と資源の保存、ロールバックの返却は「消費した記録がある分だけ」、地形調査の固定(`SurveyRef`)と`E-SITE-CHANGED`、ディメンションへの拘束(D-27)、組み立て個体の記録(`AssemblyResult`)と解体、試運転の先行ログ(`CommissioningJournal`)、L7の比較範囲(`CompareScope`)と自動修復の限定(`WRONG_BLOCK`は上書きしない)、稼働で変わる状態(`volatileProps`)の除外。
  - **状態の整理**: ジョブの`VERIFIED`(施工が施工リストどおり)と、工場の`COMMISSIONED`(試運転に合格)を分離(`ProjectState`)。
  - **データの経路**: `LogisticsPlan`を`SemanticPlan`に含め(空輸が承認・施工の経路に入る)、`SetSite`・`SetLogistics`と`contentHash`、`MachineSetupRegistry`(Createのレシピは機械の並びを持たない)、時刻・信号で変わる部品の宣言状態(`assumedState`)、`SemanticMap`の`structureId`と扉・窓・門の区別、`LoopCounter`の対象と世代、`CostEntry`の画像枚数、保存型の`schemaVersion`と移行表、`RecipeOption`の補完、`ConceptBrief`から後段の型への依存の除去(`ProductRequest`)、L2の必要建屋の役割ID化。
  - **画像**: Vision ReaderをApprove0の前に走らせる(D-26。認識一覧と警告を承認画面に出す)、Codexの出力ファイルの決定論的な取得(ファイル名の指定と`--output-schema`)、Codexの隔離の限界の明示、`BuildCameraController`の新設、`claude.exe`の直接起動と`StageCliRunner`、段ごとの部分スキーマ。
  - **フェーズ**: P3に`ModuleTemplate`、P4の検証パイプラインを段階式に(`AnalysisPipeline`)、P7に`RouteChooserScreen`・`StageCliRunner`、P11のテンプレートからAeronauticsを要する物を外してP13へ、P12にCreate部品の合格ゲート、P13の`offroad`、`ManifestExporter`(D-21)、実測の下限推定と計測用の保管庫(L8)、L3を過大でループさせない。
  - **反映しなかった指摘**: Codex指摘20の「全Create運動系を独自モデルで再現する範囲が過大」は、範囲を削る提案なので採用しない。代わりに、静的なモデルで表せない部品は宣言した状態で評価して試運転で確認する(`W-DYNAMIC-PART`)。

- **第4版**(2026-09-26。P3の着手前): 実装の入口で、既存コードとの食い違いを見つけたので、コードより先に設計図を直した。実測した事実: `CommandNames.ALL`は`Interpreter.java:183`(同名の関数定義の拒否)・`CommandNamesTest`・`SyntaxHighlighterTest`・IDE補完が使う。`MiniJson`は`chat`パッケージ限定。`Interpreter`の暴走検出は「畑の操作なしで100万文」だけ。この言語にキーワード引数は無く、数は全て`double`。
  - **`04` F-6**: 建設の命令を確定(`site`・`style`・`mood`・`<部品名>`・`part`・`update_params`・`relocate`・`remove_part`・`connect`・`disconnect`・`logistics`)。`frame`・`place`・`power`・`dock`・`decorate`・`module`・`floor_slab`は、他の命令で表せるので置かない。`CommandNames.PLAN`を分けて`ALL`は変えない(D-28)。`PlanRunLimits`(総ステップ・時間)。
  - **`01`**: 局所の向き・`BuildFrame`の変換・回転の規約(D-29)、`Anchor`の意味(親からの相対、`OnSurface`の`u`/`v`)、パラメータの型付け、`PlanPatcher`の規則(パッチ全体の拒否、`RemoveNode`の条件)、テンプレートの展開の規則、`contentHash`の正規形(並びの無視)、施工リストの並びとハッシュの範囲、`MiniJson`の公開。
  - **`05`**: 建築部品22種の生成規則(1.1.1節。パラメータ・素材の族・既定のパレット・フェーズ・重なり・検証の既定・屋根の作り方)、新しい問題コード7件。
  - **`07`**: P3の完了条件に、建築部品の生成(10)・`SchemaGenerator`(11)・スクリプトの実行の限界(12)を追加(「作る物」にあって、完了条件に無かった物)。

- **第4版・追補**(2026-09-27。P3の実装後): 実装で確定した規則と、実装と食い違っていた記述を、コードより先に設計図へ直した。パッケージの層(`build.plan`・`build.compile.gen`の分離と依存の向きの固定、`lang`の建設の橋)、`ParamType.INT_LIST`と`maxItems`、建築部品の`VolumeSpec`は`GENERATED`、正規形の物流の並び(dock/routeは`id`・flowは品物と両端・`Set`はワイヤ名の辞書順)、存在しない親の`E-ANCHOR#parent`(拒否された親の子には重ねて出さない)、`connect`の`via`(`None`=`Routing.Auto`、空のリストも`Explicit`=直接つなぐ)、展開の上限(部品・接続とも20万個/本、作る前に拒否)、スクリプトの分割は計画の並びのまま文の切れ目で10,000字(`# 建設スクリプト i/n`)、`PlanApi`の失敗の契約、`catwalk`・`railing`・`ramp`・`road`の`dir`の既定値`north`、角の柱も掘る開口部と`carve`の`E-OVERLAP`は1つ、生成の作業量の上限(占有セルあたり2回・空きは無料・掘るマスは1単位・拒否された部品に後段なし)、`axis`を持つブロック55個の決め方(語尾+ID列挙)、P3の完了条件10を「手で導いた数との一致」に(宣言の`VolumeSpec`は生成器の写しになるため。範囲の判断はCodexの意見を求めてから林さんに報告する)、実機で見た目を確かめる項目の一覧。

- **第5版**(2026-09-27。P3の検証中): 林さんの裁定で、Create/Aeronautics/Sableを**optional依存**にした(どのmodが入っていてもAIが対応し、無いpackの部品だけが無効になる能力駆動。畑の機能はCreate無しで動く)。`neoforge.mods.toml`の3依存を`type="optional"`にし、modのクラスに触れるコードは専用の`integration`パッケージに限定(F-11、`OptionalModBoundaryTest`が機械的に検査)。D-13を「modの不在」にも拡張。あわせて、P3の台帳で漏れていた設計図のずれも直した(部品パラメータの開いた範囲の上限、`lattice`は`pane`では`E-PARAM-RANGE`、`monitor`の拒否条件と`gable_fill`の端面、`sawtooth`の歯の向き、面取り付け部品の`extent`、看板の本文は63字、植栽の草花は`v`+1、`E-UNKNOWN-PART`/`E-ANCHOR`は生成側も出す、屋根の補足)。

- **第6版**(2026-09-27。P3の検証後、Task 25): 林さんの台帳の要件A・B・Fを設計に落とした。
  - **能力パック(D-30、F-25)**: modの統合を`CapabilityPack`方式に定義。`vanilla`を常に有効の基底にして、Create・Aeronautics(同梱`simulated`/`offroad`)・Sable・`create_submarine`・`powergrid`・`create_copper_and_zinc`を任意の組合せで載せる。登録簿は有効packの和集合として作り、スキーマ・見本帳・画面・AIの語彙は有効な物だけから生成(P-16を拡張)。無効packの部品を使う計画は`E-PACK-DISABLED`。新しい3modの中身は不明なので、スパイクS-11〜S-13で確定してから設計を凍結する。
  - **機器モデル・学びの層(D-32、F-26、`08`)**: MHSに着想した独自の「おもちゃ規格」。機器は読む・書く・出来事を持ち、安全限界・事前条件・緊急停止は機器の側が強制する(P-17)。既存の`attach_isr`/`raise_interrupt`・`create_task`・`semaphore`・`sleep_ticks`と、畑の読み取り/書き込み命令に結びつける。
  - **未知のmodの読み解き(D-31、F-27、N-32・N-34)**: 起動時にmodを分類し(`ModScanReport`)、ゲームの登録簿・レシピ・言語・汎用の能力から事実の目録(`ModCatalog`)を集める。AIの通訳段(N-34)が目録の事実だけから草案(`ModDraft`。各主張は根拠の事実と信頼度つき)を作り、サンドボックスの実測と人の承認を経て初めてpackに昇格する。未検証の草案は施工に使えない。Createで校正する。
  - **新しいノードとIssueCode**: N-32(ModScan)・N-33(機器層`DeviceGate`)・N-34(Mod Interpreter)。`E-PACK-DISABLED`・`E-UNKNOWN-CAPABILITY`・`E-DEVICE-RANGE`・`E-DEVICE-PRECOND`・`E-DEVICE-LIMIT`・`E-ESTOP`・`W-DEVICE-CLAMPED`・`W-UNKNOWN-MOD`を追加。
  - **フェーズとスパイク(`07`)**: P16(追加modの能力パック)・P17(機器の安全モデル・学びの層)を追加。スパイクS-11(潜水艦mod)・S-12(バッテリーmod)・S-13(銅と亜鉛のmod)・S-14(未知modの走査の実現可能性)・S-15(レッドストーン立ち上がり→割り込みの面)を追加。網羅性チェック表とリスク表を更新。
