# 07 施工順序(フェーズ)・スパイク・テスト・網羅性チェック

**フェーズは「先に造る順序」であり、範囲ではない(0.1節)。全フェーズを造る。** 順序の原則: (1)依存の下から上へ(ベース層 → 工場層)、(2)見える成果を早く出す、(3)最大の技術リスク(Createとの一致、Aeronautics)は、**スパイクを先に走らせて**事実で設計を確定する、(4)**あるフェーズの完了条件が、後のフェーズの成果物に依存してはならない**。

各フェーズの計画書は、承認後に`docs/superpowers/plans/`へ、1フェーズ1文書で作る(既存の`2026-09-25-nl-factory-phase1-…`、`phase2-…`と同じ形式)。各フェーズの完了は、下の「完了の実測条件」を**実際に動かして**確かめてから宣言する。

**数値の合格線について**: 下の数値(MSPT、FPS、適合率など)は**初期値**。P4・P7・P8でベースラインを実測して調整する。**緩める場合は、理由を記録して林さんに報告する**(私の判断だけで緩めない)。

---

## 1. フェーズ一覧

| フェーズ | 名前 | 主な成果物 | 担当するノード・ループ・基盤 |
|---|---|---|---|
| P1(済) | Create/Aeronautics依存 | 依存追加、`runClient`で13mod構成が起動 | F-11の土台 |
| P2(済) | サーバー側ブロック読み取り | `ServerBlockSnapshotReader` | F-1の土台 |
| **P3** | 意味の核(純Java) | 型、部品登録簿(建築部品)、`PlanPatcher`、`PlanExpander`(部品とExplicitの経路。Autoの経路はP11)、`PlanCompiler`、`ManifestDiff`(`Conflict`つき)、`PlaceableBlockPolicy`の判定、ハッシュ、`SchemaGenerator`、`PlanApi`+命令(許可リスト方式)、`PlanRecorder`/`PlanScriptWriter` | N-14 Carpenter、F-6、F-20、D-2/D-3/D-14/D-15/D-16/D-22 |
| **P4** | サーバー施工ランタイム | 地形調査、安全枠、置いてよいブロック、材料、ジョブ保存(状態・施工リスト・journal・ledgerの分離、原子的書き込み、復旧)、施工実行、施工予算とワーカー、ドローン演出、`ServerStateReader`・`SparseSnapshot`・差分・修復、`MODIFY`とロールバック、承認・権限・区画、分割ペイロード、デバッグコマンド | N-26 Builder、N-27 Drone、N-29 MC、**L7**、F-1〜F-5、F-7、F-8、F-13、F-14、F-16(専用サーバー確認)、F-17(ジョブのログ)、F-21 |
| **P5** | レビュー画面 | ホログラム(半透明・不透明)、**差分ホログラム**、承認画面(材料表・検証・所要時間)、スクリプト表示と編集、進捗表示、**施工リストの`.nbt`書き出し(`ManifestExporter`)** | N-01(入口・進捗)、N-24(承認・材料表・差分)、F-3の画面側、D-21 |
| **P6** | ベース層の決定論解析 | `VoxelClassGrid`、Blueprint Analyzer、`SemanticMap`とスロット、Zoning Fixer | N-11、N-15 |
| **P7** | AI基盤と「日本語→小屋」 | `BuildOrchestrator`、段の枠組み、Claude構造化出力・費用、MCP読み取りツール、**`RecipeSource`とサーバー実装(`query_recipes`)**、Refiner、Site Planner、Architect(文章入力)、費用台帳(3種の上限)、プロジェクト保存、`ChatKey`/`ProjectKey`、**`KnowledgeStore`の書き込み**、`LoopEscalationScreen`、**`RouteChooserScreen`**、**`StageCliRunner`** | N-02、N-07(一部)、N-10、N-13、N-31(記録)、F-9、F-15、F-17(journal)、F-18、F-23 |
| **P8** | 画像パイプライン(絵→小屋) | `CodexCliBridge`、`ClaudeCliStreamJson`、`ImageStore`、`PartAtlasRenderer`・`AtlasSlicer`、`ImagePromptBuilder`、`ReferenceImageBudget`、コンセプト画像(カットアウェイ)と承認画面(認識一覧・警告)、建屋画像3種、Vision Reader、Architect(画像入力) | N-03、N-04、N-05、N-12、**L1**、F-10 |
| **P9** | 見た目の照合 | `PREVIEW`撮影、`CameraPreset`、Vision Critic(意味の比較)、Decorator、並列表示、`AS_BUILT`撮影 | N-20、N-22、N-23、**L5**(建築)、**L5'**、N-24(並列表示) |
| **P10** | Createの部品と忠実度 | `create:*`部品の登録・設置戦略・ポート(P10a=確認題材に要る部品、P10b=残り)、`IMPLICIT`の扱い、`KineticModel`、`PowerSourceModel`、`StressValueSource`、Createの全レシピ種別の網羅、風車の組み立て(`ASSEMBLE`)、忠実度テスト、GameTest基盤、Create部品の見本帳 | N-07(網羅)、F-11、F-12、P-13、L7(風車の組み立て) |
| **P11** | 工場の決定論解析とモジュール | Capacity Calculator、`PlanExpander`のAutoの経路(Router)、Factory Analyzer、Module Library(同梱テンプレート。Aeronauticsを要する物はP13)、**試運転(Commissioning)**とサバイバル経済(F-24)、予測レポート | N-08、N-17、N-18、N-19、N-30(試運転)、N-24(予測レポート)、F-24 |
| **P12** | 工場のAI | Process Planner、Reconciler、Module Planner、複数建屋のSite Planner、工場用のRefiner、Vision Reader(Create部品)、`query_module_library` | N-06、N-09、N-16、**L2**、**L3**、**L4**、**L4'**、**L5**(工場) |
| **P13** | 物流・空輸・運用 | `aeronautics:`・`simulated:`・`offroad:`の部品の登録と忠実度、発着場・係留・飛行船テンプレート(組み立て)、Logistics Planner、Runtime Monitor | N-21、N-28、N-30(観測)、**L8**、L7(飛行船の組み立て) |
| **P14** | 知見とダメ出し | Knowledge Storeの昇格・検索・整理、Critique Router | N-25、N-31(昇格・検索)、**L6**、**L9** |
| **P15** | 仕上げ | マルチプレイの総合確認、性能の合格線、設定と表示言語、ドキュメント(README・CurseForge・ゲーム内ヘルプ)、評価セットの全実行、リリース点検 | F-15〜F-19、F-21、F-22の総点検 |
| **P16** | 追加modの能力パック | `CapabilityPack`・`EnabledRegistry`、`ModScan`と`ModScanReport`・`UnknownModNote`、pack無効時の検査(`E-PACK-DISABLED`・`E-UNKNOWN-CAPABILITY`・`W-UNKNOWN-MOD`)、新しい3mod(`create_submarine`・`powergrid`・`create_copper_and_zinc`)のpack | N-32、F-25、F-27、D-30、D-31 |
| **P17** | 機器の安全モデル・学びの層 | `DeviceDescriptor`・`DeviceGate`(実行前検査)、`DeviceEvent`の割り込み面、`MonitorRecord`、`ReferenceSheet`、畑ドローンの制御ブロックを包む最初の機器 | N-33、F-26、D-32、P-17 |
| **P18** | 動線と空きの予約 | `ReservedSpace`・`CirculationReq`、Space Keeper(N-35)、Site Plannerの「空きを先に予約」、Zoning Fixerの予約の扱い、Routerの予約内経路、`E-RESERVED-CONFLICT`・`E-CIRCULATION-BROKEN` | N-35、N-10・N-11・N-16・N-18の拡張、F-28、D-33 |
| **P19** | 輸送手段と空間要求 | `TransportProfile`・`TransportMode`、`LogisticsPlan`の一般化(`Dock.mode`・`Route.mode`)、`SiteSurvey.waterDepth`、手段の敷地適合の判定(`E-MODE-UNFIT`と代替の提案)、水密の検査(`E-HULL-LEAK`)、`get_transport_profiles` | N-21の一般化、F-29、D-34、S-17 |
| **P20** | 既存建築の計測 | `ExistingStructureProfile`・`PassageProfile`・`StyleObservation`、Structure Surveyor(N-36)、`StyleSpec.matchExisting`、`W-STRUCTURE-UNCERTAIN`、`get_structure_profiles`、L11 | N-36、F-30、D-35、L11、S-18 |
| **P21** | ゾーンの階層(C4風) | `Zone`・`Window`・`ZoneBudget`・`ZoneSummary`、Zone Layer(N-37)、窓の照合(`E-WINDOW-MISMATCH`)、ゾーン別の予算(`E-ZONE-BUDGET`)、ゾーンだけの再検証(L10)、`get_zone_summary` | N-37、F-31、D-36、L10、S-19 |

---

## 2. 各フェーズの詳細

### P3 意味の核(純Java)

- **作る物**: `01_data_model.md`の1〜5節・11節の型と、`ModuleTemplate`・`TemplateBundle`(9節。`PlanExpander`が使う)、`PartTypeRegistry`(建築部品`micra:*`)、`PlanPatcher`、`PlanExpander`(テンプレート展開と`Explicit`の経路。`Auto`はRouterが入るP11で追加するので、P3では`Auto`の接続を`E-NO-ROUTE`(ルーター未搭載)で拒否する)、`PlanCompiler`(建築部品→施工リスト)、`ManifestDiff`(`RemovalEntry`・`Conflict`)、`PlaceableBlockPolicy`の判定(許可リスト。純Java部分)、正規JSONとハッシュ、`SchemaGenerator`、`PlanApi`インターフェースと建設用の命令(`CommandNames.PLAN`・`Interpreter`の`PlanApi`対応・`CommandsHelpDoc.BUILD_COMMANDS`・`SyntaxHighlighter`の多重定義。許可リスト方式の決定論プロファイル`PlanScriptProfile`と、実行の上限`PlanRunLimits`)、`PlanRecorder`、`PlanScriptWriter`。
- **依存**: なし(純Java)。**先に必要なスパイク**: S-1(スキーマの機能)。
- **完了の実測条件**:
  1. 手書きの`PlanPatch`(JSON)から施工リストができ、**同じ入力を1,000回変換して同じハッシュ**。
  2. **回転不変**: 同じ計画を`facing`4方向でコンパイルした結果が、座標の回転として一致(全部品種で)。
  3. **往復**: `SemanticPlan → スクリプト → PlanRecorder → SemanticPlan`でハッシュが変わらない(複数スクリプト分割も)。
  4. **許可リスト方式の決定論プロファイル**: 許可した命令以外(乱数・時刻・知覚・畑の命令・`create_task`・`semaphore`・`attach_isr`・`raise_interrupt`・`sleep_ticks`)を使ったスクリプトが静的検査で拒否される。農場の命令との混在も拒否される。
  5. `build.*`が`net.minecraft`をimportしていないことを検査するテストが緑(D-16)。
  6. 既存のテスト47ファイルがすべて緑(畑の`CommandNames.ALL`・`Interpreter`の既存挙動は無変更)。`CommandNames.PLAN`の全命令が、建設用の`Interpreter`で認識され(unknown functionにならない)、`CommandsHelpDoc.BUILD_COMMANDS`が全命令を載せ、構文ハイライト(命令名の一覧を渡す多重定義)が認識する。
  7. 部品ごとのパラメータ範囲・不正入力(`E-PARAM-RANGE`等)の`Issue`が出る。
  8. `E-BLOCK-FORBIDDEN`: コマンドブロック・岩盤・スポナーなどを素材や部品で指定した計画が、コンパイルで拒否される。
  9. `ManifestDiff`: 旧→新の差分から、`expectedNow`と`restoreTo`を持つ`RemovalEntry`が作られる(単体テスト)。
  10. **建築部品の生成**: 登録簿の`micra:`部品(22種)のすべてが生成器を持ち(登録簿と生成器の一致を検査するテスト)、金のファイル(小屋: 壁・床・屋根・扉・窓)と一致する。各部品の生成物が、規則から手で導いた値(小屋は238個)と一致する(占有体積は生成器が決める物で、宣言は生成器の写しになるので、宣言との一致は検査にならない)。`Conflict`の判定(`volatileProps`を無視し、別のブロックなら`PLAYER_MODIFIED`、空気なら`MISSING`)が単体テストで通る。
  11. **`SchemaGenerator`**: 登録簿から`PlanPatch`のスキーマを作り、`USER`の部品識別子が`enum`、`IMPLICIT`が出ず、`additionalProperties:false`が基本で、S-1で測った上限の文字数に収まる(超える規模では、パラメータ配列の方式に切り替わる)。
  12. **スクリプトの実行の限界**: 建設用の`Interpreter`が、総ステップ上限(既定100,000)と時間の上限(既定5秒)で止まり、`E-SCRIPT-LIMIT`になる。
- **実機確認**: なし(純Java)。ただし`runClient`が従来どおり起動することを確認。

### P4 サーバー施工ランタイム

- **作る物**: `construction/*`(`04`のF-1〜F-8、F-13、F-14、F-21、F-23の一部)、整地(`SITE_PREP`の切り・盛り)、`PlacedRegistry`、区画の存続(D-23)、地形調査の固定(`SurveyRef`)。デバッグコマンド(例: `/micradrone build submit <計画JSONのパス>`)で、手書きの計画を投入できる。**AIは使わない**。
- **先に必要なスパイク**: S-6(ペイロード上限)、S-9(保護modの設置イベント)。
- **完了の実測条件(実機)**:
  1. 手書きの計画から、**小屋(壁・床・屋根・扉・窓)が、ドローンの演出つきで建つ**。
  2. 施工後に、**ブロックの状態まで**検査する(向きの違う階段・扉を検出)。意図的に壊す・向きを変えると、L7の修復ジョブが直して`VERIFIED`。直せない状況で`PARTIAL`と理由。
  3. **`MODIFY`**: 建てた小屋の計画を変更(壁の追加・撤去・材質の変更)すると、`ManifestDiff`から`MODIFY`ジョブができ、差分どおりに直る。**プレイヤーが手で置き換えたブロックは`Conflict`として、触らずに報告される**。
  4. サーバー再起動で`PAUSED`から自動再開(再開までの時間が10秒以内)。取消。ロールバック(置換したすべてのブロックが、設置前のブロックとブロック状態に戻る)。ジョブの記録ファイルが欠けた場合に、`PAUSED`のまま「復旧待ち」になり、黙って再実行しない。
  5. 承認: クライアントが偽の施工リストを承認しようとして、サーバーが拒否する(ハッシュ不一致)。クライアントの展開結果を信用せず、サーバーが自分で作り直す。承認した後で別のディメンションへ移動して承認しても拒否される(ハッシュにディメンションを含む)。サーバーの検証パイプラインは、P4では展開・コンパイル・安全枠だけ(`ProcessGraph`は任意の欄)で、Blueprint AnalyzerはP6、Factory AnalyzerはP11で載る。
  6. 権限: 専用サーバー+クライアント2つで、他人が取消・承認できない。他人の区画への施工が拒否される。
  7. 材料: クリエイティブは消費なし。サバイバルはインベントリと補給チェストから消費し、不足で`PAUSED`、補給で自動再開。**取消で材料を返さない**(建物が残るため)。ロールバックは、撤去した分だけ返す。再起動しても二重に消費しない。
  8. 安全枠: 上限超過・置換不可ブロック(ブロックエンティティを持つブロックを含む)がある場合に、承認前に`Issue`が出る。
  9. 30KB超の計画が、分割して送られ、ハッシュ検証を通る。
  10. **性能の合格線(初期値)**: 20,000設置のジョブを既定の速度で動かした間の、平均の`MSPT`の増加が5ms以内(最大でも15ms以内)。重い計算(展開・コンパイル・解析)はワーカーで走り、メインスレッドを止めない。4つのジョブが同時に動いても、サーバー全体の平均`MSPT`が45ms以内で、超えると自動減速して`PAUSED(SERVER_BUSY)`と表示される。
  11. 既存の畑ドローン機能が従来どおり動く(農場のスクリプトを1本実行)。既存の`get_block_snapshot`のテストが緑(`ServerBlockSnapshotReader`は無変更)。
  12. **整地**: 傾斜地で、切り・盛りが承認画面に個数つきで出て、確認後に実行される。サバイバルでは、切ったブロックが所有者に集められ、盛るブロックが消費され、資源が消えも増えもしない。確認なしの整地は拒否される。
  13. **ブロックエンティティ**: このプロジェクトが置いた保管庫・デポを`MODIFY`・`ROLLBACK`で撤去でき、中身のアイテムがドロップされて保全される。元からあったブロックエンティティ(プレイヤーのチェスト)は、置換も撤去もされない。
  14. **区画の存続**: ジョブが終わっても、工場が在る間は区画が保持され、他人の施工が拒否される。`journal`は区画が解放されるまで残る。
  15. **L7の保守性**: 稼働で変わる状態(`volatileProps`)を、修復が初期状態に戻さない。別の種類のブロックが在る位置(プレイヤーが置き換えた可能性)は、自動で上書きされず`Conflict`として報告される。
  16. **調査の固定**: 調査から承認までの間に、範囲内の地形が変わっても、ハッシュ不一致にならない。置けなくなった位置だけが`Conflict`・`PAUSED`(`E-SITE-CHANGED`)になる。

### P5 レビュー画面

- **作る物**: `client/build/*`のうち、`HologramRenderer`(半透明・不透明の2モード)、差分ホログラム、`BuildReviewHud`(材料表・`Issue`・所要時間・承認/却下)、`BuildProgressHud`、スクリプト表示(既存の`LineDiff`Accept/Reject画面に接続)、建設モードの入口。
- **完了の実測条件(実機)**: (1)スクリプト(または計画JSON)→ホログラム表示 → 承認 → 施工、が通る。(2)ホログラムの全ブロック(位置・向き)が、実際に建った物と**全て一致**(小屋で全数比較)。(3)クライアントとサーバーのハッシュが違うと承認できない。(4)材料表・破壊を伴う置換の確認欄が出る。受け入れ不可の`ERROR`がある間は、承認ボタンが無効。(5)スクリプトを人が直して再保存すると、新しい版のホログラムに変わる。(6)`MODIFY`の承認画面に、差分ホログラム(追加=緑、撤去=赤、変更=黄)と`Conflict`の予告が出る。(7)**20,000ブロックのホログラム描画で、FPSが基準(開発機で実測した通常時)の80%以上**(初期値)。(8)**`.nbt`書き出し**: 小屋の施工リストを標準のストラクチャーNBTに書き出し、標準の`/place template`で読み込むと、同じ形が建つ(組み立てを要する部品は書き出しの対象外と明記)。

### P6 ベース層の決定論解析

- **作る物**: `VoxelClassGrid`、`BlueprintAnalyzer`、`SemanticMap`・`Slot`、`ZoningFixer`。
- **完了の実測条件**: (1)箱形の小屋→1部屋、開口部・出入口が検出される。(2)壁に穴のある小屋→`E-ENCLOSURE-LEAK`(穴の位置つき)。(3)**設計データ由来と、実際に建てた状態由来で、同じ`SemanticMap`**(P4の実機の小屋で確認)。(4)重なる足跡が間隔を守って離れる、傾斜地に整地案が付く。(5)スロットが、機械の必要寸法を満たす空き直方体として列挙される。

### P7 AI基盤と「日本語→小屋」

- **作る物**: `build/ai/*`(`BuildOrchestrator`、`StageSpec`、`Dossier`、`StageMemo`、`LoopBudget`、`CostLedger`)、`ClaudeCliBridge`/`ClaudeCliJson`の拡張(`--json-schema`、`structured_output`、`total_cost_usd`)、MCP読み取りツールの追加、**`RecipeSource`(純Javaのインターフェース)とサーバー実装(`ServerRecipeSource`)、MCPツール`query_recipes`**、Refiner、Site Planner、Architect(文章から)、プロジェクト保存、`ChatKey`/`ProjectKey`、**`KnowledgeStore`の書き込み**、`LoopEscalationScreen`、初回同意画面。
- **先に必要なスパイク**: S-2(CLIの細部)。
- **完了の実測条件(実機)**: (1)日本語「屋根が赤い小屋を建てて」→ 対話で詰め → 計画 → ホログラム → 承認 → 建つ。(2)**「ネジを作って」→ `query_recipes`が空を返し、Refinerが勝手に置き換えず、Createに無いと返して質問する**。「鉄板を作って」では`create:iron_sheet`のレシピが返る。(3)各段の入出力と費用(金額・画像回数・トークン)が`journal`/`ledger`に残る。(4)取消・再起動で状態が失われない。(5)**再生テスト**(記録した応答の再生)が緑で、段の状態遷移・検証・再試行が決定論で通る。(6)評価セットv1(建築系10件以上)の、スキーマ通過→コンパイル通過に至る率が**0.9以上**(初期値)。(7)終端イベント(承認・却下・ダメ出し)が`KnowledgeStore`に保存される。(8)費用の上限線(金額)で停止し、`LoopEscalationScreen`が出る。(9)同じ制御ブロックから2つのプロジェクトを作っても、Refinerの履歴が混ざらない。(10)**`StageCliRunner`**: 2つの段を同時に走らせても、互いの取消が干渉しない。IDE画面を閉じても、実行中の段が続く。`claude.exe`を直接起動でき(`cmd.exe`を経由しない)、約3万字のスキーマでも起動する。`claude.exe`が見つからない環境では`cmd.exe`経由に切り替わり、その上限(8191文字)を超える場合は、部品の選択肢を絞ったスキーマに自動で切り替わる。(11)**`RouteChooserScreen`**: ループの上限で「前の段に戻る」を選ぶと、その時点で存在する段の一覧から戻し先を選べる。(12)`get_block_snapshot`が、サーバー(`ServerBlockSnapshotReader`、F-8経由)のデータを返す。

### P8 画像パイプライン(絵 → 小屋)

- **作る物**: `CodexCliBridge`(`--ephemeral --ignore-user-config --ignore-rules`つき)、`ClaudeCliStreamJson`(画像入力の段の応答の解析)、`ImageStore`、`PartAtlasRenderer`・`AtlasSlicer`(建築部品分)、`ImagePromptBuilder`、`ReferenceImageBudget`、コンセプト画像(カットアウェイ)と承認の画面(認識された部品の一覧と、登録簿外の機械の警告つき)、建屋画像3種、Vision Reader、Architectの画像入力。
- **先に必要なスパイク**: S-3(参考画像・Codexの隔離フラグ)、S-4(部品見本帳の描画)、S-10(絵の読み取りの評価方法と較正)。
- **完了の実測条件(実機)**: (1)依頼 → コンセプト画像が生成され、参考画像として部品見本帳が渡された事実と、外した参考画像の理由が`ImageArtifact`に記録される。(2)ダメ出し(L1)→再生成→承認。5回で`LoopEscalationScreen`が出る。(3)**Approve0の前に**`StructureDescription`が作られ、承認画面に認識一覧と警告が出る。**部品名は登録簿のenumのみ**、未登録の物は`unrecognized`。目立つ登録簿外の機械が有る絵は、承認画面に出す前に自動で1回だけ再生成される(D-26)。(4)建屋の完成予想図・構造用画像・内部断面図の3種が保存される。(5)画像を元に、Architectが計画を作り、小屋が建つ。(6)参考画像の枠を超える場合に、優先順位で外される。(7)Codex CLIが無い環境で、画像以外の機能が使え、案内が出る。(8)**評価画像セットで、部品名の適合率が0.9以上、必須部品の再現率が0.7以上、誤認率が0.1以下**(初期値。S-10で較正して凍結)。(9)画像回数・Codexトークンの上限線で停止する。

### P9 見た目の照合

- **作る物**: `InGameRenderer`(`PREVIEW`・`AS_BUILT`)、`CameraPreset`、Vision Critic(意味の比較、クロップの正規化)、Decorator、並列表示。
- **先に必要なスパイク**: S-4b(a)(建築部品の静的描画)、S-10。
- **完了の実測条件(実機)**: (1)同じ`CameraPreset`で、コンセプト画像と同じ構図の`PREVIEW`画像が撮れる。(2)意図的に屋根の色を違えると、Vision Criticが屋根の差分を指摘し、L5の修正でスコアが上がる。(3)実現不能な要素を含む絵で、L5'が発動し、描き直した絵がユーザーの承認画面に出て、承認されて通る。(4)Decoratorの装飾が、壁の向きを変えても同じ相対位置に付く。(5)`AS_BUILT`の最終撮影が記録される。(6)頭打ち・上限で`LoopEscalationScreen`が出る。(7)**人が判定した比較ペアの評価で、Vision Criticのスコアと人の評価の順位相関が0.6以上**(初期値。閾値0.75をここで較正して凍結)。

### P10 Createの部品と忠実度

- **作る物**: `create:*`部品(`05`の1.2節)の登録・設置戦略・ポート、`IMPLICIT`の扱い、`KineticModel`、`PowerSourceModel`(水車・風車・蒸気機関)、`StressValueSource`(Createの`BlockStressValues`)、Createの全レシピ種別の網羅(液体・保持道具・手順・形状を含む`RecipeOption`)、`MachineSetupRegistry`(レシピ種別→検証済みの機械の組み立て)、時刻・信号で状態が変わる部品の宣言状態(`assumedState`)の扱い、**風車の組み立て(`ASSEMBLE`と`ASSEMBLED_AWAY`の検証)**、`FidelityLab`(GameTest)、Create部品の見本帳、`requires`による有効化。
- **優先度(すべて作る)**: **P10a**=確認題材(鉄板と真鍮)に要る部品(プレス、ミキサー+鉢、ブレイズバーナー、デポ、ベルト、ファンネル、シュート、トンネル、シャフト、歯車、ギアボックス、クラッチ、ギアシフト、水車、風車、蒸気機関、アイテム保管庫)。**P10b**=残りの部品(`05` 1.2節)。**P10の完了は、P10aとP10bの両方**。
- **先に必要なスパイク**: S-5(設置手順と組み立て)、S-5b(経路の規則)、S-5c(`BlockEntity`のアクセスと燃料の補給)、S-5d(動力源の出力の実測)、S-7(GameTest)、S-4b(b)(機械部品の静的描画)。**S-5・S-7はP4の頃から並行して始める。**
- **完了の実測条件**: (1)登録簿の`create:*`の全部品の忠実度テスト(設置・状態・動作)が緑。(2)**レシピ**: 各レシピ種別が`RecipeOption`に正規化され、網羅テストが緑。(3)**予測の一致**: 基準の配置(水車+シャフト+プレス、水車+ミキサー+鉢+ブレイズバーナー)で、`KineticModel`の回転数・応力が実測と一致(回転数は厳密、応力は±5%)。(4)**動力源のモデル**: 水車の水流の配置、風車の帆の数、蒸気機関のボイラーの状態を変えた実測値と、`PowerSourceModel`の予測が±10%以内。(5)版が範囲外のとき、該当部品が無効になり理由が出る。(6)部品見本帳に`USER`の全部品が載る(描けない部品は理由と代替が記録)。(7)風車を組み立てた後にL7の検査を走らせても、帆が「欠落」と判定されず、期待のエンティティが確認される。(8)Create更新時の手順(F-11)が文書化され、実際に一度通せる。

### P11 工場の決定論解析とモジュール

- **作る物**: `CapacityCalculator`、`PlanExpander`の`Auto`の経路(`Router`)、`FactoryAnalyzer`、`ModuleLibrary`+同梱テンプレート(`05` 3節)、`Commissioning`(サバイバルの経済を含む。F-24)、予測レポート。
- **完了の実測条件**: (1)**P11で作る同梱テンプレートの全部**(動力・加工・搬入出・保管・幹線・キャットウォーク。Aeronauticsを要する`mod:cargo_loader`・`mod:dock_pad`・`mod:airship_*`はP13)が、静的検証・忠実度テスト・試運転(実機で品物を入れて製品が出る)を通り、`stats`(毎分の生産量・応力)が実測で記録される。(2)`鉄インゴット1個 → 鉄板1個`がプレス台で実際に出る。(3)`銅+亜鉛 → 真鍮×2`が、ブレイズバーナーで加熱したミキサー台で出る。熱源が無い構成は`E-HEAT-NONE`。(4)Routerが作ったシャフト・ベルト・シュートが、実機で回り、品物が流れる。(5)意図的な不整合(回転方向の衝突、動力不足、行き止まり)が、それぞれ`E-ROT-CONFLICT`/`E-STRESS-OVER`/`E-ITEM-DEADEND`として検出され、同じ入力で同じ結果になる。(6)予測の毎分生産量が、実測と許容内(±10%)で一致。(7)`W-UNMODELED`の部品を含む計画が、「未検証」と表示され、試運転が通って`COMMISSIONED`になるまで「試運転待ち(未検証)」のまま(ジョブの`VERIFIED`とは別の状態)。(8)**サバイバルの試運転で、投入品が所有者から消費され、製品が返り、品物が増えない**(投入と回収の収支が合う)。途中で落として再起動しても、残った試験品が回収される。

### P12 工場のAI

- **作る物**: Process Planner、Reconciler(決定論+AI)、Module Planner、複数建屋のSite Planner、工場用のRefiner、Vision Reader(Create部品)、`query_module_library`、L2・L3・L4・L4'、L5(機械を含む工場の照合)。
- **完了の実測条件(実機)**: (1)**「鉄板と真鍮を作る工場」(発着場なし)→ 対話 → 画像 → 工程 → 建屋 → 機械配置 → 検証 → ホログラム → 承認 → 建屋込みで建つ → 試運転で鉄板と真鍮が出る**。(2)L2: 建屋が小さい・少ない絵で再生成が要求され、新しい絵がユーザーの承認画面に出て、承認されて通る。`HIDDEN`の部品は欠落と数えない。(3)L3: 動力が足りない工程グラフ(水車のみ許可・目標が大きい)が、目標の引き下げまたは動力源の許可で通る。(4)L4: 意図的な回転衝突の配置が、Module Plannerの修正で解消する。上限に達しても受け入れ不可の`ERROR`が残る間は「進む」が出ず、承認できない。(5)L4': 狭い建屋が広がり、下流が自動で再計算される。(6)絵の機械の並びが、配置のヒントとして使われる。(7)評価セットv2(工場系10件以上)で、`ERROR`0件に到達する率が0.8以上(初期値)。(8)**Create部品の合格ゲート**: P10で作ったCreate部品の見本帳を参考画像に渡した絵で、Vision Readerの部品名の適合率0.9以上・必須部品の再現率0.7以上・誤認率0.1以下(初期値。工場系の評価画像セットで測る)。Approve0の承認画面に、認識された部品の一覧と登録簿外の機械の警告が出る。

### P13 物流・空輸・運用

- **作る物**: `aeronautics:`・`simulated:`・`offroad:`の部品(`05` 1.3節)の登録・設置戦略・忠実度、発着場(`micra:dock_pad`)・荷役・係留・飛行船テンプレート(物理アセンブラでの組み立て)、**`mod:cargo_loader`・`mod:dock_pad`・`mod:airship_*`の同梱テンプレート**(静的検証・忠実度テスト・試運転を通す)、作用範囲の検査(`E-EFFECT-ESCAPES-CLAIM`)、Logistics Planner、Runtime Monitor、L8。
- **先に必要なスパイク**: S-8(飛行船の組み立て・操縦・係留と、組み立て後の検証)。
- **完了の実測条件(実機)**: (1)**「鉄板と真鍮を作る工場。屋根は赤、飛行船の発着場つき」が、最後まで通る**: 発着場が建ち、搬出口から荷積み位置まで品物が流れ、飛行船テンプレートが組み上がり(期待のエンティティが生成され、L7が欠落と誤判定しない)、係留でき、荷積み位置から荷を運べる。**運航の自動化は、S-8で可能と分かった方式で完成させる**。S-8が「Aeronautics 1.3.0では自動化が技術的に不可能」と**実測で示した場合に限り**、その根拠(試した方法と結果)を記録して林さんに報告し、了承を得た上で、手動操縦+航路の標識までを完了とする。私の判断だけで範囲を狭めない。(2)L8: 動力を意図的に不足させると、停止・低速が検出され、増設案が出て、承認で増設され、実測が改善する。**生産量の実測は下限推定で、プレイヤーが完成品を取り出しても、専用の計測用の保管庫の差分から、真の値の±15%以内に収まる**(実機で確認)。(3)運転の観測は、ユーザーが有効にした時だけ動き、稼働中の工場を自動では変更しない。(4)`aeronautics:`・`simulated:`・`offroad:`の全部品の忠実度テスト(設置・状態、組み立てを要する物は組み立て後の状態)が緑。

### P14 知見とダメ出し

- **作る物**: `KnowledgeStore`の昇格・検索・整理、モジュール昇格、Critique Router、L6、L9の残り(件数の上限の画面)。
- **完了の実測条件**: (1)成功した施工が記録され、昇格の3条件(静的検証・そのプレイヤーの環境での試運転・ユーザーの承認)を満たしたテンプレート候補が`ModuleLibrary`に入り、次のプロジェクトで選択肢に出る。(2)過去の好み・失敗例が、Refinerの資料に入る(件数上限あり)。(3)4分類のダメ出し(評価セット)が正しい戻し先に振り分けられ、`Invalidation`が正しく波及する。(4)L6が8回で停止し、`LoopEscalationScreen`が出る。(5)記録が上限(プレイヤーごと1,000件)の90%で通知され、100%で**自動では消さず**画面が出て、選ぶまで新規の書き込みが保留される。

### P15 仕上げ

- **作る物・確認**: 専用サーバー+複数クライアントでの総合確認(権限・区画・同時ジョブ・切断と再接続)、性能の合格線、設定と表示言語(日本語・英語)、ドキュメント(README、`docs/curseforge_description.md`、ゲーム内ヘルプ`CommandsHelpDoc`)、評価セットの全実行と記録、リリース点検(mods.tomlの依存、ライセンスの表記、既存機能の回帰)。
- **完了の実測条件**: 8節の総合の確認シナリオが全て通る。**性能**: 4人が同時に大きな施工(各20,000設置)をしても、サーバー全体の平均`MSPT`が45ms以内(自動減速を含む)、4つの`PAUSED(SERVER_BUSY)`が解消して全て完了する。ホログラム20,000ブロックのFPSが基準の80%以上。**CurseForgeへの公開は、林さんの号令があった時だけ**。

### P16 追加modの能力パック(`CapabilityPack`・`ModScan`)

- **作る物**: `CapabilityPack`の宣言の仕組み(pack ID・`requiredMods`と版範囲・部品・アナライザ・モジュールテンプレート・知見・輸送プロファイル・レシピ源・未解決スパイクの一覧)、`EnabledRegistry`(有効なpackの和集合としての登録簿の合成)、`ModScan`(N-32。起動時の導入modの分類と`ModScanReport`・子供向けの`UnknownModNote`)、pack無効時の検査(`E-PACK-DISABLED`・`E-UNKNOWN-CAPABILITY`・`W-UNKNOWN-MOD`)、画面へのpackの状態と理由の表示、新しい3mod(`create_submarine`・`powergrid`・`create_copper_and_zinc`)のpack(中身はS-11〜S-13で確定してから凍結)。
- **先に必要なスパイク**: S-11(潜水艦mod)、S-12(バッテリーmod)、S-13(銅と亜鉛のmod)、S-14(未知modのメタデータ走査)。
- **完了の実測条件(実機)**: (1)Create無しで起動→`create`packが`DISABLED_ABSENT`・理由つきで表示され、畑の機能とバニラの建築部品は動く。(2)全mod入りで起動→全packが`ENABLED`。(3)同じmod構成の2回の起動で、`ModScanReport.digest`と登録簿の版ハッシュが一致する。(4)未知のmodを入れて起動→分類と`UnknownModNote`が出て、部品としては登録されない。(5)無効なpackの部品を使う計画が`E-PACK-DISABLED`で、未知mod由来らしいIDが`E-UNKNOWN-CAPABILITY`で拒否される。(6)`query_part_types`・部品見本帳・スキーマが有効なpackの物だけを返し、modを外して再起動すると語彙が変わる。(7)**草案の流れ**: 未知modに対して`ModDraft`が作られ、未検証の草案の部品が施工に使えない(`E-UNKNOWN-CAPABILITY`)こと、サンドボックスでの実測(`SANDBOX_TESTED`)→人の承認(`APPROVED`)→昇格(`PROMOTED`)の順でしかpackにならないこと。(8)**校正**: 走査+通訳をCreateに掛けた結果が手書きの`create`packの部品表をどれだけ再発見するか、新しい3modの草案を人がjarから読んだ一覧と突き合わせた一致率を、実測して記録する。

### P17 機器の安全モデル・学びの層(`08_device_model.md`)

- **作る物**: `DeviceDescriptor`(読む・書く・出来事・安全限界・事前条件・日本語の目印)、`DeviceGate`(実行前の検査: 型と範囲→事前条件→安全限界→ESTOP)、`DeviceEvent`の割り込み面(`attach_isr`の面名`device:<deviceId>:<event>`、`raise_interrupt`で発火)、`MonitorRecord`(操作・出来事・拒否の記録)、`ReferenceSheet`(子供向けの機器の説明書の自動生成)、畑ドローンの制御ブロックを包む最初の機器(読む=`measure`・`can_harvest`・`get_*`、書く=`move`・`till`・`plant`・`harvest`・`set_output`)、`E-DEVICE-*`・`E-ESTOP`・`W-DEVICE-CLAMPED`の検査。
- **先に必要なスパイク**: S-15(実物のレッドストーン立ち上がり→割り込みの面への配線)。
- **完了の実測条件(実機)**: (1)範囲外の書き込みが`E-DEVICE-RANGE`(または`W-DEVICE-CLAMPED`で丸め)で止まる。(2)`ESTOP`発動→全書き込みが`E-ESTOP`→所有者/OPの明示解除で復帰、発動と解除が記録に残る。(3)機器の出来事(例: 収穫可能)が`attach_isr`で付けたISRを起こす。(4)`ReferenceSheet`が宣言どおりに生成され、画面とAIの資料(`get_device_reference`)に同じ文が出る。(5)操作・出来事・拒否が`MonitorRecord`に残る。(6)学んだ(実測の)値が、明示の昇格のゲート(試運転+承認)を通るまで計画のモデルを書き換えない(観測≠検証済み、`08`)。

### P18 動線と空きの予約

- **作る物**: `ReservedSpace`・`SpacePurpose`・`CirculationReq`(`01` 14.1節)、Space Keeper(N-35)、Site Planner(N-10)の「動線・輸送の空きを足跡より先に予約」への変更、Zoning Fixer(N-11)の予約の扱い(侵入させない)、Router(N-18)の予約内経路の規則、`E-RESERVED-CONFLICT`・`E-CIRCULATION-BROKEN`。
- **依存**: P6(`VoxelClassGrid`・`SemanticMap`)、P7(Site Planner)。**先に必要なスパイク**: S-16(歩行・ドローン・荷物の通り道の断面と曲がりの実測)。
- **完了の実測条件**: (1)予約した空き(`ReservedSpace`)に部品を置く計画が、施工の前に`E-RESERVED-CONFLICT`で拒否される。(2)出入口から各部屋・スロットまで`CirculationReq`の断面で歩ける建屋は通り、壁で分断した建屋は`E-CIRCULATION-BROKEN`(塞いでいる位置つき)になる。(3)「子供が建物の中をまっすぐ歩ける」小屋で、実機で入口から奥の部屋まで障害物なく歩ける(林さんに見てもらう)。(4)用途の合わない予約をRouterが避け、合う`TRANSPORT_PATH`の予約の中を通す。(5)廊下の断面の数値はS-16の実測値であり、推測で書いた値は無い。

### P19 輸送手段と空間要求

- **作る物**: `TransportMode`・`TransportProfile`(`01` 14.2節。packの`transportProfileIds`として各packの知識に属する)、`LogisticsPlan`の一般化(`Dock.mode`・`minWaterDepth`、`Route.mode`・`transportProfileId`)、`SiteSurvey.waterDepth`、Site Planner/Logistics Plannerの手段の敷地適合の判定(`E-MODE-UNFIT`と代替の提案)、水密の検査(`E-HULL-LEAK`)、`get_transport_profiles`ツール。
- **依存**: P13(発着場・飛行船)、P16(`TransportProfile`をpackの知識として載せる仕組み)。**先に必要なスパイク**: S-17(各輸送手段の空間要求: チェーンドライブ・車両・滑走路・パッド・泊位・水深・水密の実測)。
- **完了の実測条件(実機)**: (1)滑走路の要求に足りない小さな敷地で、滑走路を計画せず`E-MODE-UNFIT`と代替(車両・船)が出る。(2)水深の足りない岸に泊位を計画しない。(3)水密が要る手段で、船体に穴のある計画が`E-HULL-LEAK`になる(浸水の規則はS-11・S-17の実測どおり)。(4)全手段の`TransportProfile`の数値に、出処(スパイクか計測記録)が付いている。

### P20 既存建築の計測

- **作る物**: `StructureSource`・`ExistingStructureProfile`・`PassageProfile`・`StyleObservation`(`01` 14.3節)、Structure Surveyor(N-36)、`StyleSpec.matchExisting`、`W-STRUCTURE-UNCERTAIN`、`get_structure_profiles`ツール、L11。
- **依存**: P2(`ServerBlockSnapshotReader`)、P6(`VoxelClassGrid`・`SemanticMap`)、P7(Site Planner)。**先に必要なスパイク**: S-18(手造り建物の認識が実際にどこまでできるか)。
- **完了の実測条件(実機)**: (1)このmodが建てた小屋の`OWN_PLAN`プロファイルが、保存済み計画と一致する。(2)手造りの小屋を認識し、通路の幅・高さ・材質の分布が`confidence`つきで出る。認識できなかった部分は「不明」として残る。(3)`matchExisting`が立った計画で、新しい建屋の材質・通路の断面が既存に揃い、認識由来の値には`W-STRUCTURE-UNCERTAIN`が付く。(4)`ServerBlockSnapshotReader`の既存の契約(ツール側)は無変更(F-30)。

### P21 ゾーンの階層(C4風)

- **作る物**: `ZoneLevel`・`ZoneRole`・`Zone`・`Window`・`ZoneBudget`・`ZoneSummary`(`01` 14.4節)、Zone Layer(N-37。窓の照合`E-WINDOW-MISMATCH`・ゾーン別の予算`E-ZONE-BUDGET`・`ZoneSummary`の生成・ゾーンだけの再検証)、`PlanCompiler`/`AnalysisPipeline`のゾーン別実行モード、`get_zone_summary`ツール、L10。
- **依存**: P6(`ZoningPlan`)、P11(`PlanCompiler`・アナライザ)、P18(空きの予約と窓の関係)、P19(輸送の窓)。**先に必要なスパイク**: S-19(ゾーン別の予算の大きさと、窓の照合・部分再検証の性能)。
- **完了の実測条件**: (1)64×64×64の発着場2つを含む計画(台帳T11-3の問題)で、全体の予算ではなくゾーンごとの予算で評価され、超えたゾーンだけが`E-ZONE-BUDGET`になる。(2)接続の両側で窓が違う計画が`E-WINDOW-MISMATCH`で拒否される。(3)ゾーン内の変更で、そのゾーンの窓への照合だけが走り、他のゾーン・全体のコンパイルが再実行されない(計測で確認)。(4)Module PlannerのAIの資料に、計画全体ではなく`ZoneSummary`が入っている。(5)`ZoneBudget`の数値はS-19の実測値であり、推測で書いた値は無い。

---

## 3. スパイク(先に事実を確かめる調査)

各スパイクは、**問い・方法・成果物・期限(この前に確定)・確定する設計**を持つ。結果は`docs/investigations/`に、既存の`spk*`と同じ形式で記録する。

| # | 問い | 方法 | 期限 | 確定する設計 |
|---|---|---|---|---|
| S-1 | **実測済み(2026-09-26。結果: `docs/investigations/spk_s1_json_schema_limits.md`)**。`--json-schema`で使えるスキーマの機能(`enum`、`oneOf`/`anyOf`、`$defs`、再帰、深いネスト、`additionalProperties:false`、大きさの上限)。**`--json-schema`はインライン引数だけ(ファイル指定は失敗すると実測済み)で、npmの`claude.cmd`は`claude.exe`を呼ぶだけ**。結果: 機能は全部使える(ルートは`type:object`必須)。コマンドライン全体の上限は、`cmd.exe`経由8,118文字・直接起動32,766文字。安全上限は直接起動20,000文字・`cmd.exe`経由5,000文字。`enum`は直接起動で1,000個まで実測OK。失敗の形(終了コード0で`structured_output`が無い)がある | 段階的に複雑なスキーマで`claude -p`を実行して通過を記録(実施済み) | P3の前(済) | `SchemaGenerator`の方式(**型つき(`oneOf`)を既定に、上限を超える規模でパラメータ配列へ切替**) |
| S-2 | `--max-budget-usd`の挙動、`--max-turns`の有無、`--model`指定、複数画像(6枚)、並列呼び出し、`stream-json`の最後の`result`行の解析(`--input-format stream-json`は`--output-format stream-json`が必須と判明済み) | CLIで実測 | P7の前 | `ClaudeCliBridge`の拡張、費用の上限の実装 |
| S-3 | `codex exec`の参考画像: 枚数(1・2・4・8・16)ごとの再現度・時間・失敗、金額への換算の可否、スクリーンショットからの描き直し(L5')、**指示した`CameraPreset`への従い具合**、`--ephemeral --ignore-user-config --ignore-rules`を付けても画像生成と認証が動くか、`.git`/`.agents`の副作用、並列 | 部品見本(絞り込みシート)を使った実測 | P8の前 | `ReferenceImageBudget`の既定値、L5'の方式、Codexの隔離フラグ |
| S-4 | 部品見本帳の描画: オフスクリーン描画の方法、複数ブロック部品の描き方、日本語ラベルの描画 | 実機でPNGを出力して目視 | P8の前 | `PartAtlasRenderer`の方式 |
| S-4b | 施工前の不透明プレビューで見える部品(静的モデル)と見えない部品(Createの回転部品の多くはブロックエンティティの描画で見た目を出す)。**(a)建築部品はP9の前、(b)機械部品はP10の中**。見えない部品の代替の選択肢: (i)基本形の静的描画で近似、(ii)クライアント側だけの疑似ブロックエンティティを作って描画機構を直接呼ぶ、(iii)専用の撮影用の区画に一時的に実際に置いて撮る | 実機で部品を描画して一覧化 | (a)P9の前、(b)P10の中 | `PREVIEW`撮影の限界と代替 |
| S-5 | Create部品の設置手順(ベルトの連結、ファンネルの向き、アームの設定、水車・風車の多ブロック、**風車の組み立てでブロックが消えること**、動力網の更新のタイミング、`BlockEntity`の初期化) | Create本体のソース(実機と同じ版のタグ`mc1.21.1-6.0.10`)を読み、開発ワールドで試す | P10の前(P4の頃から並行) | `PlacerId`の戦略、`AssemblySpec`、設置の順序、`Placement.blockEntityConfig`の許可キー |
| S-5b | Router用の、接続の種類ごとの動かし方の規則(ベルトの斜め・曲がり、シュート、パイプ) | 実機で実験して表にする | P11の前 | `Router`の規則表 |
| S-5c | 試運転用の`BlockEntity`アクセス(品物の投入・観測、回転数・過負荷の読み取り)と、**ブレイズバーナーへの燃料の自動補給の可否** | ソースと実機で確認 | P11の前 | `Commissioning`の実装方法、`mod:mixer_station`の燃料補給部 |
| S-5d | 動力源の出力の実測: 水車の水流の配置、風車の帆の数、蒸気機関のボイラーの状態と、回転数・容量の関係 | 実機で条件を変えて計測 | P10の前 | `PowerSourceModel` |
| S-6 | サーバー宛て・クライアント宛てのカスタムペイロードの実際の上限 | 大きさを変えて送って実測 | P4の前 | 分割の大きさ、上限の値 |
| S-7 | ModDevGradle 2.0.141でGameTestサーバーが動くか(Create入りで)。**既に分かっていること**: `build.gradle`には`gameTestServer`の実行設定が既にある(`type = "gameTestServer"`。テストが1つも無いとサーバーが落ちる、とコメントにある)。動く実例: `DurdeuVlad/dwurdys-storynpcs`のPR #96(NeoForge 21.1.248系。`./gradlew runGameTestServer`が窓なしで起動し、終了コードで成否を返す。moddev-gradleに`gameTestServer()`という近道の書き方は無く、`type`で指定する)。**未確認**: このリポジトリで、Create入りのまま動くこと | `runGameTestServer`を、実際に`@GameTest`を1つ書いて試す | P10の前(早めに) | `FidelityLab`の実行方式(GameTestか、自己診断コマンドか) |
| S-8 | Aeronauticsの飛行船の組み立て(物理アセンブラでブロックがサブレベルになる手順、消え方、**組み立て後の検証方法**、組み立ての結果の型(Createのコントラプションのエンティティか、Sableのサブレベルか。`AssemblyExpectation`の形を確定))、操縦・自動化(舵輪・操縦桿・センサー・レッドストーン)、係留・発着の仕組み(ドッキングコネクター)、**組み立て後の飛行が世界に与える影響(衝突による破壊、区画外への着陸)**、公開API | 実機での組み立てとjar/ソースの調査 | P13の前 | 飛行船テンプレート、`AssemblySpec`、Logistics Planner・`cargo_loader`の設計、運用の自動化の範囲 |
| S-9 | 他modの保護(設置イベント)との互換: 実プレイヤーでなく`FakePlayer`/所有者を主体として`BlockEvent.EntityPlaceEvent`を出したときの挙動 | 開発環境で簡単な保護のテスト用リスナーを作って確認 | P4の前 | `PlacementPolicy`の実装 |
| S-10 | 絵の読み取りの評価方法: 評価画像セット(実在部品の絵)と人手ラベルの作り方、適合率・再現率・誤認率の定義、Vision Criticのスコアの較正(人が判定した比較ペア) | 画像とラベルを作り、実測 | P8の前 | 評価セット、合格線の初期値の較正 |
| S-11 | `create_submarine`(Createの潜水艦mod)の中身: mod ID・版範囲・部品一覧、潜水艦・船の組み立てと防水・浸水の扱い(「沈没の心配を無くす」の実際の意味)、Aeronauticsの飛行船との住み分け | jarの言語ファイル・ブロック登録の一覧・実機での組み立て | P16の前 | `create_submarine`packの部品・テンプレート・`AssemblySpec`の水中への拡張 |
| S-12 | `powergrid`(バッテリーmod)の中身: mod ID・版範囲・部品一覧、蓄電とCreateの回転動力との関係(変換の有無) | 同上 | P16の前 | `powergrid`packの部品・蓄電のモデル(`CapacityReport`への載せ方) |
| S-13 | `create_copper_and_zinc`(銅と亜鉛のmod)の中身: mod ID・版範囲、追加されるレシピ(銅・亜鉛の再生産)、真鍮の経済への影響 | jarのレシピjson・言語ファイル・実機 | P16の前 | `create_copper_and_zinc`packのレシピ源 |
| S-14 | 未知のmodの走査の実現可能性: `ModList`/`mods.toml`に加えて、ゲームのレジストリ(ブロック・品物・ブロック状態のプロパティ・ブロックエンティティ・タグ)・`RecipeManager`・言語ファイル・汎用の能力(品物/液体/エネルギーのハンドラ、レッドストーンの振る舞い)を、modのコードを実行せずに読めるか。`UNKNOWN_COMPATIBLE`/`UNKNOWN_UNSAFE`の分類の規則、子供向け説明文に使える情報 | NeoForgeのAPIと実機で試す | P16の前 | `ModScan`の分類規則・`ModCatalog`の項目・`UnknownModNote`の項目 |
| S-15 | 実物のレッドストーンの立ち上がりを、機器の出来事→`attach_isr`の面名に配線する方法(既知の未着手項目: 現在は`raise_interrupt`をプログラムから呼ぶだけ) | 実機で回路を組んで確認 | P17の前 | `DeviceEvent`の面への配線の方式 |
| S-16 | 動線(空き)の断面の実測: プレイヤー(子供)の歩行に要る幅・高さ・曲がり、畑ドローンの通り道、ベルト・シュートの荷物の通り道、ドッキングする飛行船の進入余白。各対象が実際に通れる最小の空気の断面 | 実機で、断面を変えた通路を通して確認(歩行は実際に歩く・ドローンは実際に走らせる) | P18の前 | `TransportProfile`の`minWidth`/`minHeight`/`minTurn`のうち歩行・ドローン・荷物の分、`CirculationReq`の検査の断面 |
| S-17 | 輸送手段ごとの空間要求: チェーンドライブ・シャフトの通り道、`offroad:wheel_mount`の車両の幅・高さ・旋回、飛行機の滑走路の長さと幅、気球・飛行船のパッド(`dock_pad`)の大きさと上空の余白、船・潜水艇の泊位の大きさと水深、**水密(水が浸入する条件。`create_submarine`無しの実際の挙動を含む)** | 各modの実機で組み立て・計測(車両は組み立てて走らせる、潜水艇は沈めて浸水を確認) | P19の前 | 各`TransportMode`の`TransportProfile`の数値、`SiteSurvey.waterDepth`の取り方、`E-HULL-LEAK`の検査の規則 |
| S-18 | 手造り建物の認識の実現可能性: `VoxelClassGrid`から、通路の幅・高さ、壁の厚さ・高さ、材質の分布、扉・窓・開口部、つながった空間を実際にどこまで検出できるか。認識できない構造の例 | 手造りの建物を数種建てて計測し、正解(実測)と突き合わせる | P20の前 | `ExistingStructureProfile`の項目の精度、`confidence`の付け方、「分からない」に倒す条件 |
| S-19 | ゾーン別の予算と部分再検証の大きさ: ゾーンごとの`ZoneBudget`(試行・部品・セル)を全体の上限に対してどう分けるか、窓の照合だけの再検証が全体再コンパイルより実際に軽いか | 台帳T11-3のケース(64×64×64の発着場×2)を含む計画で計測 | P21の前 | `ZoneBudget`の既定値、L10の再検証の境界 |

---

## 4. テスト戦略

| 層 | 対象 | 方法 |
|---|---|---|
| 単体(JUnit+Fakes) | `build.*`の純Java全部(型、コンパイラ、パッチ、アナライザ、Router、ハッシュ、スクリプトの往復) | 既存の慣習どおり。**金のファイル**(`src/test/resources/build/golden/`)に、入力と期待する施工リスト・`Issue`を置く |
| 性質テスト | 回転不変、ハッシュの決定性、往復、パッチの適用順の独立性 | ランダムな計画(乱数の種を固定)で多数回 |
| 段のテスト(記録・再生) | 各AI段の解析・検証・状態遷移・再試行 | 実際の応答を記録した固定データを再生。モデルの品質は検査しない |
| 構造検査 | `build.*`が`net.minecraft`をimportしない(D-16)。MCPの登録一覧に書き込みツールが無い(P-9) | ソース走査のテスト |
| GameTest(新規、Create連携だけ) | 部品の設置・動作・予測の一致、組み立て、試運転 | `runGameTestServer`(S-7)。動かなければ自己診断コマンド |
| 実機確認 | 見た目、体感、ドローン演出、ホログラム、画像生成 | 各フェーズの「実機確認」の項目を、林さんに見てもらう(既存の小刻みな実機確認の方式)。チェックリストは`docs/investigations/`に置く |
| マルチプレイ | 専用サーバー+クライアント2つ | P4とP15で実施 |
| 評価セット | AIの段の品質(スキーマ・コンパイル通過率、ループ回数、費用、部品名の適合率・再現率・誤認率、スコアの相関) | 手動実行(費用がかかる)。結果を日付つきで保存し、変更の前後を比べる。合格線は`06` 6節 |
| 性能 | `MSPT`、FPS、メモリ、再開時間 | 各フェーズの完了条件の数値(初期値)で測る。基準は実測で調整 |
| 回帰 | 既存の畑ドローン機能 | 既存テスト47ファイルを常に緑。P4・P15で農場のスクリプトを実機で実行 |

---

## 5. 網羅性チェック表

**ノード × フェーズ**(N-番号は`02_node_map.md`)。空欄が無いことを、文書の作成時に確認する。

| ノード | フェーズ | | ノード | フェーズ |
|---|---|---|---|---|
| N-01 User/DroneUI | P5・P7 | | N-17 Module Library | P11・P14 |
| N-02 Refiner | P7(工場用はP12) | | N-18 Router | P11・P18 |
| N-03 ConceptImg | P8(建築部品)・P12(Create部品) | | N-19 Factory Analyzer | P11 |
| N-04 Approve0 | P8 | | N-20 Decorator | P9 |
| N-05 Vision Reader | P8(Create部品はP12) | | N-21 Logistics Planner | P13・P19 |
| N-06 Process Planner | P12 | | N-22 In-game Renderer | P9 |
| N-07 Recipe Resolver | P7・P10 | | N-23 Vision Critic | P9 |
| N-08 Capacity Calculator | P11 | | N-24 Preview | P5・P9・P11 |
| N-09 Reconciler | P12 | | N-25 Critique Router | P14 |
| N-10 Site Planner | P7・P12・P18・P19・P21 | | N-26 Builder | P4 |
| N-11 Zoning Fixer | P6・P18 | | N-27 Drone | P4 |
| N-12 DesignGen | P8(L5'はP9) | | N-28 Aero | P13 |
| N-13 Architect | P7・P8 | | N-29 MC(ワールド) | P4 |
| N-14 Carpenter | P3 | | N-30 Runtime Monitor | 試運転=P11、観測=P13 |
| N-15 Blueprint Analyzer | P6 | | N-31 Knowledge Store | 記録=P7、昇格・検索=P14 |
| N-16 Module Planner | P12・P18 | | N-32 ModScan | P16 |
| | | | N-33 機器層 | P17 |
| | | | N-34 Mod Interpreter | P16 |
| | | | N-35 Space Keeper | P18 |
| | | | N-36 Structure Surveyor | P20 |
| | | | N-37 Zone Layer | P21 |

**ループ × フェーズ**(`03_loops.md`): L1=P8、L2=P12、L3=P12、L4=P12(空き・動線の新トリガはP18・P19・P21で有効化)、L4'=P12(同上)、L5=P9(建築)・P12(工場)、L5'=P9、L6=P14、L7=P4(単純なブロック)・P10(風車の組み立て)・P13(飛行船の組み立て)、L8=P13、L9=P7(書き込み)・P14(昇格・検索・整理)、L10=P21、L11=P20。機器層(N-33、P17)の`MonitorRecord`はL8の実測とL9の終端イベントの材料になる。

**横断基盤 × フェーズ**(`04_foundations.md`): F-1・F-2・F-3・F-4・F-5・F-7・F-8・F-13・F-14・F-21=P4(F-3の画面側=P5)、F-6=P3、F-9・F-15・F-18=P7、F-10=P8、F-11・F-12=P10(組み立ての空輸側=P13)、F-16=P4(専用サーバーでの確認)とP15(総合確認)、F-17=P4(ジョブのログ・コマンド)とP7(段の`journal`)、F-19・F-22=各フェーズの完了条件+P15、F-20=全フェーズ(既存テストの緑)、F-23=P7、F-24=P11、F-25・F-27=P16、F-26=P17、F-28=P18、F-29=P19、F-30=P20、F-31=P21。

**決定事項 × 確かめる場所**: D-1=P4(書き込み経路が1本)、D-2=P3(往復)、D-3=P4(偽のハッシュの拒否、サーバーの再展開)、D-4=P4、D-5=P7(再生テスト)、D-6=P4・P5(ハッシュ一致)、D-7=P10、D-8=P8(実測済みを実機で再確認)、D-9=P10、D-10=P7・P8・P9・P12(3種の上限線)、D-11=P4、D-12=P4、D-13=P10・P13、D-14=P4(`MODIFY`・`Conflict`)・P9・P13、D-15=P3、D-16=P3、D-17=P8・P10、D-18=P8、D-19=P8(合格ゲート)、D-20=この文書のフェーズ順(引き継ぎ文のMVP候補との違いを林さんに確認してもらう)、D-21=P4(`.nbt`ではなく施工リスト)、D-22=P3(判定)・P4(承認時の検査)、D-23=P4(区画の存続)、D-24=P10・P13(作用範囲の検査)、D-25=P4(`PlacedRegistry`)、D-26=P8(画像の合格ゲート)、D-27=P4(ディメンションの拘束)、D-30・D-31=P16、D-32=P17、D-33=P18、D-34=P19、D-35=P20、D-36=P21。

**原則 × 強制する仕組み**: P-9=書き込みツールが存在しないことの構造検査(MCPの登録一覧のテスト)+P4、P-12=D-16のテスト、P-13=P10の忠実度テスト、P-15=既存テストの緑+P4・P15の農場の実機確認、P-16=登録簿からの自動生成のテスト(スキーマ・指示文・見本帳が登録簿と一致。`IMPLICIT`は含まない)+P16(有効なpackの和集合との一致)、P-17=P17の実機確認(範囲外の書き込みの拒否、`ESTOP`の強制)。

---

## 6. リスクと対処

| リスク | 影響 | 対処(この設計での位置) |
|---|---|---|
| Createの部品の設置が単純な`setBlock`で済まない | 工場が動かない | S-5で先に確定。`PlacerId`の戦略化(F-12)。忠実度テストで全部品を確認 |
| 予測モデルと実物のずれ(動力源の出力は状況依存) | 検証の信頼が落ちる | `PowerSourceModel`+S-5dの実測。未対応は`W-UNMODELED`で推測せず、試運転で実測し、通るまで`VERIFIED`にしない。忠実度テストの基準表で差を追う |
| 画像生成AIが実在部品を正しく描けない | 絵と部品がずれる | 部品見本帳を参考画像に(D-17)。絵から読む側はenumに限り(D-19)、**適合率・再現率・誤認率の合格線**と、承認画面の警告。実現不能はL5'で描き直す |
| 絵の縮尺・見えない機械 | L2が測れない | カットアウェイの鳥瞰図、比率と数と種類だけで判定、`HIDDEN`は数えない(L2) |
| 参考画像の上限・品質 | 見本が効かない | 1枚に詰める・絞る・優先順位(`06` 5.3節)。S-3で枚数を実測 |
| AIのコスト・時間 | 使いにくい | 金額・画像回数・Codexトークンの3種の上限線、`StageMemo`、状態なし呼び出しの小さな資料、ループの上限 |
| CLIのバージョン変更 | 動かなくなる | CLIの出力の解析を集約(`ClaudeCliJson`/`ClaudeCliStreamJson`)、記録・再生テスト、`--version`の記録 |
| マルチプレイでの権限・混雑 | 荒らし・重複・サーバーの遅延 | F-3〜F-5、区画、`ConstructionBudget`(全体の予算・自動減速)、ワーカーでの重い計算、専用サーバーの実測(P4・P15) |
| Aeronauticsの仕様が読めない | 空輸が作れない | S-8を先に。**自動運航が技術的に不可能と実測で示された場合に限り、根拠を報告して林さんの了承を得てから**範囲を調整する(P13の完了条件) |
| 組み立てで消えるブロックの誤判定 | 風車・飛行船が壊れる/置き直される | `AssemblySpec`・`ASSEMBLED_AWAY`・`AssemblyExpectation`(P10・P13) |
| 施工の負荷(tick) | ラグ | F-2の`ConstructionBudget`・F-21、ドローンの台数、速度の設定、ワーカー |
| **サバイバルの経済の複製経路** | 品物が無料で増える | 取消で返却しない・ロールバックは撤去分だけ(F-7)、試運転は投入品を消費し製品を返す(F-24)、置換で壊したブロックはドロップしない |
| **建てた後の変更で、プレイヤーの変更を消す** | 世界の破壊 | `MODIFY`は`expectedNow`と一致する位置だけ、`Conflict`は触らず報告(`01` 4.1) |
| ジョブの記録の破損・肥大 | 復旧不能・重いセーブ | 別ファイル・原子的書き込み・復旧待ち・孤児の掃除(F-2) |
| 置いてはいけないブロックの混入 | 権限の迂回 | `PlaceableBlockPolicy`(D-22) |
| 設計の肥大 | 作りきれない | フェーズ分割、各フェーズが実際に動く成果物、スパイクで先に不確定を潰す、P10のa/b |
| ワールドの内容によるAIの誘導 | 計画の乱れ | F-22。AIは世界を書けない。承認はハッシュ |
| 機械の動作が区画の外の世界を壊す(ドリル・ホースプーリー・飛行船など) | マルチプレイの世界の破壊 | D-24: 作用範囲の宣言と区画内の検査(`E-EFFECT-ESCAPES-CLAIM`)、区画の存続(D-23)、S-8で飛行船の影響を確認 |
| 調査から承認までに地形が変わる | 承認が通らない | 調査の固定(`SurveyRef`、F-3)と、施工時の`Conflict`(`E-SITE-CHANGED`) |
| 稼働中の工場を変更・修復して止める | 生産停止・プレイヤーの変更を消す | `volatileProps`の無視、`PlacedRegistry`だけを対象、`WRONG_BLOCK`は自動で上書きしない(L7) |
| 既存のCLI橋渡しの制約(1本だけ、画面と共に閉じる、`cmd.exe`の長さ制限) | AI段が並列・長時間・大きなスキーマで動かない | `StageCliRunner`、`claude.exe`の直接起動、段ごとの部分スキーマ(`06` 1.2節、S-1) |
| 新しい3mod(潜水艦・バッテリー・銅と亜鉛)の中身が読めない・要求が変わる | そのpackが作れない | S-11〜S-13を先に。確定するまでpackは「未導入」のまま残りが動く(D-30)。確定しないmodは能力にしない |
| 未知のmodの誤検出・敵対的なメタデータ | 誤った案内・プロンプトへの混入 | メタデータは信用しないデータ(F-27)。`Dossier`にはIDと版と分類だけを入れる。分からなければ`UNKNOWN_UNSAFE`側へ倒す。部品としては登録しない(D-31) |
| 機器の安全限界の迂回(AI・スクリプトからの書き込み) | 機器・世界の破壊 | `DeviceGate`の実行前検査は全経路で必須(P-17)。`hard`の限界は拒否、`ESTOP`の解除は所有者/OPのみ |
| 実測(学んだ)値の誤りが計画に混ざる | 予測の信頼が落ちる | 観測と検証済みを分け、昇格には明示のゲート(試運転+承認)を置く(`08`)。計画に使う値は版つきで保存 |
| 予約した空きが多すぎて建屋が入らない | 敷地が無駄になる | `ReservedSpace`はSite Plannerが最小限だけ宣言し、残りは使える空き。予約の衝突は`E-RESERVED-CONFLICT`で見える(L4') |
| 動線の断面を大きく取りすぎる | 建屋が過剰に広くなる | 断面はS-16の実測値だけを使う。`CirculationReq`は必要な経路だけ |
| 手造り建物の認識の誤りが新しい建屋の様式を歪める | ちぐはぐな建築(要件Eの逆) | `confidence`と`basis`を必ず付け、認識由来の値には`W-STRUCTURE-UNCERTAIN`。確かさはS-18の実測まで約束しない |
| 窓を粗い層で固定しすぎて、ゾーン内の正当な変更が全部上位へ戻る | ループが進まない | 窓の種類を必要な通路だけに絞る(D-36)。窓が要る変更だけ`E-WINDOW-MISMATCH`で上へ |
| ゾーン別の予算の分け方が実態に合わない | 小さなゾーンでも`E-ZONE-BUDGET`が出る・全体を再コンパイルしてしまう | S-19で実測してから既定値を凍結。全体の上限も残す(使い切りの防止) |

---

## 7. 進め方

1. **この設計図に林さんの承認をもらう**(承認前は実装に入らない)。
2. 承認後、フェーズごとに、`docs/superpowers/plans/`へ**フェーズの計画書**(タスクごとにテスト先行・完全なコード・コミット)を作る。最初はP3。スパイクS-1を先に。
3. GitHub Issueを、フェーズごとに起票する(`[WIP]`→完了で`[AI-CLOSED]`)。
4. 実装は1作業1コミット。フェーズの完了は、上の「完了の実測条件」を**実際に動かして**確かめてから宣言する。
5. フェーズごとに、レビュー(Opus 5.5)を行う。PRの作成・マージ・pushは、**林さんの号令があった時だけ**。PR作成後は、規定どおりセバスによる3回のレビュー。
6. 設計に誤りが見つかったら、この文書を直し(変更履歴を残し)、なぜ変えたかを書く。フェーズの計画書だけを直して設計図と食い違わせない。

---

## 8. 総合の確認シナリオ(全体の完了条件)

| # | シナリオ | 確かめること | フェーズ |
|---|---|---|---|
| A1 | 「屋根が赤い小屋を建てて」 | 日本語→対話→計画→承認→建つ→検証 | P7 |
| A2 | 絵から小屋(コンセプト画像→建屋画像→建築)、絵と実物の一致 | 画像パイプライン、L1、L5、L5' | P8・P9 |
| A3 | 「ネジを作って」 | Createに無いと返し、確認する | P7・P12 |
| A4 | 「鉄板を作る工場」 | 建屋込みで建ち、試運転で鉄板が出る | P12 |
| A5 | 「鉄板と真鍮を作る工場。屋根は赤、飛行船の発着場つき」 | 全ノード・全ループを通る最後まで | P13 |
| A6 | 故障の注入 | L7(壊した壁が直る、風車・飛行船の組み立て後も誤判定しない)、L4(回転衝突が直る)、L5(屋根の色が直る)、L8(動力不足が検出され増設案)、`MODIFY`の`Conflict` | P4・P9・P10・P12・P13 |
| A7 | 専用サーバー+クライアント2つ(複数人の同時施工を含む) | 権限・区画・同時ジョブ・再接続・自動減速 | P15 |
| A8 | 既存の畑ドローン | 従来どおり動く | 全フェーズ |
| A9 | サバイバルでの収支 | 材料・試運転・取消・ロールバックで品物が増えない | P4・P11 |
