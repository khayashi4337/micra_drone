# 02 v3フロー 全ノード対応表

v3の**全ノード**を、種別・役割・入出力・実装場所(既存の流用か新規か)・失敗時の扱い・フェーズ・完了の実測条件で書く。「対象外」の行は無い(0.1節)。

**種別の凡例**: `AI-構造化`=Claudeの構造化出力(`--json-schema`) / `AI-視覚`=Claudeの画像入力 / `AI-対話`=再開可能なClaudeセッション / `決定論`=純Java(AIを使わない) / `画像生成`=`codex exec` / `UI`=画面 / `施工`=サーバーのジョブ / `計測`=実行中の観測 / `既存`=既に有る部品を流用

**共通の入力の渡し方(D-5)**: AIの各段は「状態を持たない呼び出し」。入力は、その段の**資料(`Dossier`)**として毎回明示的に渡す。資料に入れるものは各段で決まっている(下記「入力」)。

パッケージは`io.github.khayashi4337.micradrone`の下。

---

## Stage 0: コンセプト

### N-01 User → DroneUI(ドローンチャットUI)
- 種別: UI・既存。
- 役割: 依頼の入口。日本語で依頼、画像の承認、ダメ出し。
- 実装: 既存`client/IdeScreen.java`+`client/IdeChatPanel.java`のチャット欄。**新規**: 「工場を建てる」モード切替と、施工の進捗表示(`client/build/BuildProgressHud`)。`IdeScreen`は既に1610行あるので、建設用の画面は別クラスに分ける。
- フェーズ: P5(レビュー画面と進捗)、P7(依頼入力、ループの上限画面`LoopEscalationScreen`と、戻し先を選ぶ`RouteChooserScreen`。後者は、その時点で存在する段の一覧から選ぶ。段が増えるたびに一覧に加わり、L6のCritique Routerが分類できないとき(`OTHER`)にも使う)。
- 完了条件: 日本語で依頼を入力→プロジェクトが作られ、状態が画面に出る。実機で確認。

### N-02 Refiner(コンセプト洗練)
- 種別: AI-対話(このノードだけ再開可能なセッション)。
- 役割: 生産物・生産量/分・動力源・外観を、ユーザーと詰める。`ConceptBrief`を作る。存在しない品物(例: 「ネジ」)を頼まれたら、Recipe Resolverの結果を見て「Createには無い。近い品物はこれ」と返して確認する(勝手に置き換えない)。
- 入力: ユーザーの発話、既存の`ChatContextBuilder`の文脈、`RecipeSource`への問い合わせ結果(MCP読み取りツール`query_recipes`)、`ModuleLibrary`の一覧、`KnowledgeStore`の好み・失敗例(L9)。
- 出力: `ConceptBrief`(構造化)。未確定の質問は`openQuestions`に残す。
- 実装: 既存`chat/ChatSession.java`・`chat/ChatContextBuilder.java`・`chat/ClaudeCliBridge.java`を流用。**新規**: 工場用のシステムプロンプト(`build/ai/prompts/RefinerPrompt`)。**セッションは制御ブロックではなくプロジェクトに結びつける**(既存の`ControllerKey`は制御ブロックの座標単位のため、共通の窓口`ChatKey`と新設の`ProjectKey`を導入する。`04` F-23)。ツールは、`query_recipes`(P7から使える)と`query_module_library`(ライブラリに工場用テンプレートが載るP11から。それまでは空)。
- 失敗時: 品物が解決できない→`W-NO-RECIPE`を出してユーザーへ。
- フェーズ: **P7**(建築の依頼)、**P12**(工場の依頼: 生産物・生産量・動力・外観を詰める)。
- 完了条件: P7=「屋根が赤い小屋」の依頼から、外観と規模が`ConceptBrief`に入る。「ネジを作って」では、`query_recipes`が空を返し、勝手に置き換えずに質問が返る。P12=「鉄板と真鍮を作る工場」から、鉄板(プレス)と真鍮(混合・加熱)が`ConceptBrief`に入る(`ProductRequest`。工程・搬出口は後の段が決める)。

### N-03 ConceptImg(コンセプトアート生成)
- 種別: 画像生成。
- 役割: 工場全景の鳥瞰図を作る。**部品見本帳(`PartAtlas`)と、依頼に必要な部品の名前・見た目の説明を参考として渡し、登録簿にある実在部品だけで描かせる**(D-17、P-16)。カメラ角度は`CameraPreset`で固定し、指示文に書く(In-game Rendererと同じ角度にするため)。**様式は、屋根を一部外して内部の機械の並びが見えるカットアウェイの鳥瞰図**(建屋の外側だけでは機械が写らず、L2で機械の照合が測れないため)。
- 入力: `ConceptBrief`、`PartAtlas`(依頼に関係する部品だけの絞り込み版、`06_ai_and_images.md`の参考画像の予算)、前回の承認画像(L1・L2の再生成時)、`CameraPreset`。
- 出力: `ImageArtifact`(`CONCEPT_ART`)。
- 実装: **新規** `chat/CodexCliBridge.java`(`ClaudeCliBridge`と同型)、`build/ai/image/ImagePromptBuilder`(登録簿から指示文を生成)、`build/ai/image/ImageStore`。
- 失敗時: `codex`が無い→「Codex CLIをインストールしてログイン」の案内を出し、他機能は使える。タイムアウト→再試行1回→ユーザーへ。
- フェーズ: **P8**(建築部品の絵)、**P12**(Create部品を含む工場の絵。P10で作ったCreate部品の見本帳を参考画像に渡す)。
- 完了条件: P8=実機で、建築部品(壁・屋根・扉・窓など)が描かれた画像が生成され、プロジェクトに保存される。P12=実機で、実在のCreate部品(プレス・デポ・メカニカルベルト・水車など)が描かれた画像が生成される。どちらも、生成に使った参考画像・外した参考画像と理由・指示文・費用が`ImageArtifact`に記録される。

### N-04 Approve0(「この絵でいい?」)
- 種別: UI。
- 役割: 生成画像を見せ、承認する。ダメ出しはL1へ。**承認画面には、Vision Reader(N-05)が読んだ認識部品の一覧と、登録簿外の機械の警告を出す**(Vision ReaderをApprove0の前に走らせるため。D-26)。目立つ登録簿外の機械が有る絵は、この画面に出す前に自動で1回だけ再生成する。それでも残る場合は、ユーザーが明示的に「この絵で進む」を選べる(上書きとして記録)。
- 実装: **新規** `client/build/ConceptImageScreen`(`NativeImage`→`DynamicTexture`で表示)。承認/やり直し/プロンプト修正のボタン。既存の`LineDiff`Accept/Reject画面の考え方を翻案。
- フェーズ: P8。完了条件: 画像が画面に出て、承認すると`ApprovalStatus=APPROVED`が保存される。

---

## Stage 1: 絵と工程を突き合わせる

### N-05 Vision Reader(絵 → 構造記述)
- 種別: AI-視覚+AI-構造化。
- 役割: 承認された絵から`StructureDescription`を作る。建屋数、屋根、煙突、そして**絵に描かれた機械の種類(登録簿の識別子のenumに限る、D-19)と並び、ベルト等の流れ**。登録簿に無い物は`unrecognized`に入れる。各建屋・物体・流れに**安定ID**を付け(流れは物体のIDで結ぶ)、物体には**見え方**(`VISIBLE`/`PARTIAL`/`HIDDEN`。屋根や壁で見えない可能性)を付ける。**合格ゲート**は`06` 5.6節(適合率・再現率・誤認率の合格線、Approve0での認識一覧と警告)。
- 入力: 生成された画像(長辺を縮小。**Approve0の前に読む**。承認後の段(Reconciler・Process Planner)は`StageMemo`でこの結果を再利用する)、`PartAtlas`(絞り込み版。名前の照合に使う)、`ConceptBrief`。
- 出力: `StructureDescription`。
- 実装: **新規** `build/ai/stages/VisionReaderStage`(`ClaudeCliBridge`に画像入力を追加)。
- 失敗時: スキーマ違反→再試行1回→`E-SCHEMA`をユーザーへ。低信頼(`confidence`が閾値未満)→Reconcilerが扱う。
- フェーズ: **P8**(建築部品)、**P12**(Create部品)。完了条件: 実在部品だけで描いた評価画像で、部品名の適合率・必須部品の再現率・誤認率を評価セットで測り、合格線(`06` 5.6節、`07`)を満たす。**P8とP12の両方で測る**(P8の登録簿は建築部品だけなので、Create部品の読み取りはP12で改めて測る)。

### N-06 Process Planner(工程グラフ作成)
- 種別: AI-構造化。
- 役割: `ConceptBrief`の生産物から、`RecipeSource`が返す候補を選んで`ProcessGraph`を作る(P-6: レシピは記憶に頼らない)。
- 入力: `ConceptBrief`、`RecipeOption`の候補一覧(Recipe Resolver、N-07)、`CapacityReport`の問題(L3)。
- 出力: `ProcessGraph`。
- 実装: **新規** `build/ai/stages/ProcessPlannerStage`。
- 失敗時: 候補が無い→`W-NO-RECIPE`→Refinerへ戻してユーザーに確認。
- フェーズ: P12。完了条件: 「鉄板(鉄インゴット→プレス)」と「真鍮(銅+亜鉛→ミキサー+ベイスン、加熱)」が、実在レシピIDつきの`ProcessGraph`になる。

### N-07 Recipe Resolver(レシピ候補の取得)
- 種別: 決定論(サーバー側の実装、純Javaの`RecipeSource`インターフェース)。
- 役割: ゲーム内`RecipeManager`から、指定品物を作るレシピの候補を、Createのレシピ種別(`create:pressing`、`create:mixing`、`create:milling`、`create:crushing`、`create:splashing`、`create:haunting`、`create:compacting`、`create:deploying`、`create:cutting`、`create:sequenced_assembly`、`create:filling`、`create:emptying`など)を含めて、正規化した`RecipeOption`で返す。タグ(例: `c:ingots/iron`)は展開する。
- 実装: **新規** `construction/ServerRecipeSource`(`RecipeManager`を読む)、純Java側`build/analyze/RecipeSource`。MCPツール`query_recipes`が、クライアントからサーバーへの問い合わせ(F-8)経由で呼ぶ。JEIには依存しない(JEIはクライアント表示専用)。
- 失敗時: 該当なし→空リスト(`W-NO-RECIPE`の根拠)。
- フェーズ: **P7**(`RecipeSource`インターフェース、サーバー実装、MCPツール`query_recipes`。Refinerが「ネジ」を判定するために要る)、**P10**(Createの全レシピ種別の正規化の網羅テスト、GameTestでの確認)。完了条件: P7=実機のサーバーで`create:iron_sheet`と`create:brass_ingot`の候補が返り、「ネジ」は空になる。P10=各レシピ種別(液体・保持道具・手順・形状を含む)が`RecipeOption`に正規化され、網羅テストが緑。

### N-08 Capacity Calculator(機械台数・RPM・応力・床面積)
- 種別: 決定論。
- 役割: `ProcessGraph`から`CapacityReport`を計算する。台数=目標毎分÷機械の毎分処理量、必要RPM、応力の合計、必要な動力源(`PowerPolicy`の範囲で`PowerPlantChoice`を選ぶ。D-9: 応力の数値は実行時に`BlockStressValues`から、動力源の出力は`PowerSourceModel`が水流・帆の数・ボイラーの状態から計算)、必要な床面積(モジュールの占有+余白)。
- 実装: **新規** `build/analyze/CapacityCalculator`+`StressValueSource`(テストでは差し替え、実行時はCreate公開APIの実装)。
- 失敗時: 動力不足・過大→`Issue`(`E-POWER-NONE`、`E-STRESS-OVER`、`W-OVERSIZED-POWER`)→L3。
- フェーズ: P11。完了条件: 実機のCreateの値で、鉄板ライン1本・真鍮ライン1本の必要動力が、実際に組んで回した結果と一致する(GameTestの忠実度テスト)。

### N-09 Reconciler(絵と工程の突き合わせ)
- 種別: 決定論(数値の突き合わせ)+AI-構造化(食い違いの解釈と提案)。
- 役割: `StructureDescription`(絵の建屋構成)と、`CapacityReport`(必要な床面積・機械)を比べる。**絵は透視投影でブロック単位の縮尺を持たないので、絶対の面積では比べない**。決定論部分が、(1)建屋の数、(2)建屋どうしの**相対的な大きさの比**と、必要な床面積の比、(3)**見えている**必須部品の検出率(`HIDDEN`は数えない)、(4)登録簿外の目立つ機械の有無、を計算する。実寸はSite Plannerが決める。AI部分が、「絵を優先して機械を減らす」か「工程を優先して建屋を大きくする(絵を再生成)」かを判断して提案する。再生成された絵は、**必ずユーザーがApprove0で承認する**(`03` L2)。
- 出力: `ReconcileResult`(OK | `Constraints`つきのL2フィードバック)。
- 実装: **新規** `build/process/Reconciler`(決定論)、`build/ai/stages/ReconcilerStage`。
- フェーズ: P12。完了条件: 収容できない絵(建屋が小さすぎる)で、L2が制約つきの再生成を要求する。収容できる絵では通る。

---

## Stage 2: 建屋ごとの設計

### N-10 Site Planner(区画割り)
- 種別: AI-構造化。
- 役割: 敷地全体に、建屋・発着場・道の配置(`ZoningPlan`)を作る。Bananacraftの城下町の区画割りに当たる。
- 入力: `ConceptBrief`、`StructureDescription`、`SiteSurvey`(サーバーの地形調査)、`CapacityReport`の床面積。
- 出力: `ZoningPlan`。
- 実装: **新規** `build/ai/stages/SitePlannerStage`。敷地は既存`chat/RegionSelectionState`(領域ポインタの2隅)で指定する。
- フェーズ: P7(小屋1棟の敷地)、P12(複数建屋・発着場を含む)。同じクラスで完成させる(段階で作り直さない)。
- 完了条件: 指定した領域内に、建屋の足跡と道が収まる`ZoningPlan`が出る。

### N-11 Zoning Fixer(衝突検出・地形適合)
- 種別: 決定論。
- 役割: `ZoningPlan`の重なり・敷地外・最小間隔違反・傾斜と水と木を、決定論で直す。動かした内容を`Adjustment`として説明する。整地(切り盛り)が要る場合は、施工リストの`SITE_PREP`の置換として出す。
- 実装: **新規** `build/analyze/ZoningFixer`。
- フェーズ: P6。完了条件: 重なる2棟の入力が、間隔を守って離れた結果になる。傾斜地の入力に整地案が付く(単体テストと、実機の地形での確認)。

### N-12 DesignGen(建屋画像生成)
- 種別: 画像生成。
- 役割: 建屋ごとに、完成予想図・構造用画像・内部断面図を作る(`BUILDING_RENDER`、`STRUCTURE_GUIDE`、`INTERIOR_SECTION`)。参考画像は、承認済みコンセプト画像と、その建屋に関係する部品の見本帳。L5'では、スクリーンショットを参考に描き直す(img2img相当)。
- 実装: N-03と同じ`CodexCliBridge`と`ImagePromptBuilder`。
- フェーズ: P8(建屋の画像)。L5'は P9。完了条件: 建屋ごとに3種の画像が保存される。構造用画像は、壁・屋根・開口部が色で塗り分けられている(目視と、Architectが読める割合)。

### N-13 Architect(2段階分析)
- 種別: AI-視覚+AI-構造化(2段階)。
- 役割: 第1段: 完成予想図と構造用画像から、建屋の構造記述を作る(壁の面・屋根の種類・開口部の位置・階数)。第2段: それを部品の`PlanPatch`(`wall`、`floor`、`roof`、`door`、`window`、`pillar`、`stairs`など)にする(P-1: 何があるか → どう作るか)。
- 入力: 建屋の画像3種、`ZoningPlan`の足跡、`PartTypeRegistry`(建築部品)、L4'・L5の場合は`Issue`/`CritiqueReport`、現在の`SemanticPlan`。
- 出力: `PlanPatch`。
- 実装: **新規** `build/ai/stages/ArchitectStage`。
- 失敗時: `PlanPatcher`が拒否(古い版など)→`E-*`つきで再試行(上限あり)。
- フェーズ: P7(画像なしで`ConceptBrief`から)、P8(画像つき)。同じクラスの入力が増えるだけで、作り直さない。
- 完了条件: 「屋根が赤い小屋」で、壁・屋根・扉・窓の部品でできた`PlanPatch`が出て、`PlanCompiler`が`Issue`なしで施工リストにする。

### N-14 Carpenter(ツール実行エンジン)
- 種別: 決定論。
- 役割: `PlanPatch`を`PlanPatcher`で適用し、`PlanCompiler`で施工リストにする。Bananacraftの大工に当たる。**AIが直接ツールを呼ぶ形はとらない**(D-1)。
- 実装: **新規** `build/model/PlanPatcher`、`build/compile/PlanCompiler`。
- フェーズ: P3。完了条件: 手書きの`PlanPatch`(JSON)から、同じ入力で同じハッシュの施工リストができる。回転(4方向)しても結果が回転しただけになる。

### N-15 Blueprint Analyzer(構造の意味解析)
- 種別: 決定論。
- 役割: 施工リスト(予測)または実際のスナップショット(観測)から、`SemanticMap`(壁・屋根・開口部・部屋・階・スロット・出入口)を作る。
- 実装: **新規** `build/analyze/BlueprintAnalyzer`+`VoxelClassGrid`。
- フェーズ: P6。完了条件: 箱形の小屋で、内部が1部屋として検出され、穴あきの壁では`E-ENCLOSURE-LEAK`が出る。設計データ由来と、実際に建てたスナップショット由来で、同じ`SemanticMap`になる。

### N-16 Module Planner(モジュール配置・接続)
- 種別: AI-構造化。
- 役割: `SemanticMap`のスロットに、モジュールライブラリのテンプレートを置き(`place_module`)、ポートをつなぐ(`connect`)。**座標や経路は決めない**(P-5)。絵の配置(`StructureDescription`)と内部断面図の雰囲気(2階建て・キャットウォーク)を参考にする。
- 入力: `SemanticMap`、`ProcessGraph`+`CapacityReport`、`ModuleLibrary`(候補一覧と仕様)、`StructureDescription`と内部断面図、`Issue`(L4・L8)。
- 出力: `PlanPatch`(`AddNode`のモジュール、`AddConnection`)。
- 実装: **新規** `build/ai/stages/ModulePlannerStage`。
- フェーズ: P12。完了条件: 鉄板ラインと真鍮ラインが、スロットに置かれ、必要な接続(動力・材料の搬入・製品の搬出)が張られる。

### N-17 Module Library(検証済みテンプレート)
- 種別: 決定論(データ+検証)。
- 役割: 検証済みのモジュール(プレス台=プレス+デポ+ファンネル+シャフト、ミキサー台、動力室、倉庫、搬入口・搬出口、キャットウォークなど)を持つ。テンプレートは`SemanticPlan`の断片で、読み込み時に**Factory Analyzerで検証**し、通らないものは載せない。
- 実装: **新規** `build/knowledge/ModuleLibrary`+`ModuleTemplate`。保存場所は、同梱(jar内リソース)と、プレイヤーのプロジェクト(L9で追加される物)。保存の作法は既存`drone/ScriptFileStore`を踏襲。**載る条件は由来で決まる**(`05` 3節): 同梱=開発時に静的検証・忠実度テスト(GameTest)・試運転を通した物、プレイヤーが昇格した物=静的検証+そのプレイヤーの環境での試運転+ユーザーの承認(GameTestは走らせられないため)。
- フェーズ: P11(同梱テンプレート)、P14(L9で成長)。
- 完了条件: 同梱テンプレートの全部が、Factory Analyzerの静的検証と、忠実度テストと、実機の試運転(Commissioning)を通る。プレイヤーが昇格したテンプレートは、静的検証と試運転を通らないと載らない。

### N-18 Router(経路探索)
- 種別: 決定論。
- 役割: `AUTO`の接続について、シャフト・ベルト・シュート・パイプの経路を3D格子上で探索して、中間部品を作る。既存物・壁・スロットの余白を避け、長さと曲がりを最小化する。見つからなければ`E-NO-ROUTE`(邪魔している対象つき)。
- 実装: **新規** `build/analyze/Router`。
- フェーズ: P11。完了条件: 障害物のある小さな3D迷路(単体テスト)で最短経路が出る。実機で、Routerが作ったシャフト・ベルトが実際に回り、品物が流れる。

### N-19 Factory Analyzer(応力・回転・物流・詰まり)
- 種別: 決定論(静的)+計測(動的、N-30の試運転)。
- 役割: 静的: `KineticModel`で回転ネットワークの速度・方向・応力を計算し、`E-ROT-CONFLICT`、`E-STRESS-OVER`を検出。**熱**(加熱を要求する機械に熱源があるか=`E-HEAT-NONE`、燃料の補給手段=`W-FUEL-SUPPLY`)と**係留**(`E-DOCK-MISALIGN`)も検査する。物流(品物・流体)の行き止まり、詰まりの危険、ポートの不一致を検出。`PredictionReport`(毎分の生産量・応力の使用率)を出す。
- 実装: **新規** `build/analyze/FactoryAnalyzer`+`KineticModel`。
- フェーズ: P11。完了条件: 予測が、実機で建てて回した結果(回転数・応力・生産量)と、許容誤差内で一致する(忠実度テスト)。
- 失敗時: 未対応の部品→`W-UNMODELED`(推測せず、試運転に任せる)。**解析の通過は「検証済み」ではない**: `W-UNMODELED`の部品を含む計画は「未検証」と表示し、試運転が通るまで`VERIFIED`にしない(`03` L4、`05` 4.7節)。

### N-20 Decorator(外装装飾)
- 種別: AI-視覚+AI-構造化。
- 役割: 完成予想図を見ながら、壁ローカル座標(`OnSurface`)で装飾を指定する(P-4)。
- 実装: **新規** `build/ai/stages/DecoratorStage`。装飾は`DECOR`部品(ランプ、トリム、プランター、看板、煙突の笠など)。
- フェーズ: P9。完了条件: 壁の向きを変えても、同じ装飾が同じ相対位置に付く。

### N-21 Logistics Planner(発着場・飛行ルート)
- 種別: AI-構造化。
- 役割: 発着場(`Dock`)と飛行ルートの計画(`LogisticsPlan`)。搬入・搬出口と発着場を物流でつなぐ。
- 入力: `SemanticPlan`(建屋・搬出口の位置)、`ProcessGraph`の搬入出、敷地(`SiteSurvey`)。出力: `PlanPatch`(発着場・係留部・飛行船のモジュールの`AddNode`・`AddConnection`と、`SetLogistics(LogisticsPlan)`)。`LogisticsPlan`(`Dock`・`Route`・`CargoFlow`、`01` 10節)は`SemanticPlan.logistics`に入り、サーバーへ送られる計画の一部として、承認のハッシュに含まれる。失敗時: 発着場の空間(上空の余白)が確保できなければ`E-SPACE-SHORT`で建屋・区画の見直しへ。
- 実装: **新規** `build/ai/stages/LogisticsPlannerStage`。飛行船の組み立て・操縦・係留は`simulated`の部品(`05` 1.3節)。仕組みはスパイクS-8で確定してから設計を固める(`07`)。
- フェーズ: P13。完了条件: 発着場が建ち、搬出口から発着場の荷積み位置まで品物が流れ、飛行船が係留でき、荷積み位置から荷を運べる。運航の自動化はS-8で可能と分かった方式で完成させる(`07` P13の規則)。

---

## Stage 3: 見た目の照合

### N-22 In-game Renderer(ゲーム内撮影)
- 種別: UI・計測。
- 役割: コンセプト画像と同じ`CameraPreset`でスクリーンショットを撮る。撮影条件(昼、HUD非表示、エンティティ非表示など)を固定する。**2つのモード**(`03_loops.md` 0.5節): `PREVIEW`=施工前。施工リストをクライアント側だけで本物のブロックモデルの見た目で不透明に描画して撮る(サーバーの世界は書き換えない。v3の順序どおり、承認と施工より前に見た目を照合できる)。`AS_BUILT`=施工後の最終確認。
- 実装: **新規** `client/build/InGameRenderer`(+`HologramRenderer`の不透明モード)と、`client/build/BuildCameraController`(任意のyaw・pitch・距離・FOVで撮る)。既存の`client/IdeCameraController`は同一パッケージ限定(package-private)で、真上からの固定の視点だけなので**再利用しない**。公開の`client/IdeCameraMath`(カメラ姿勢の計算)の考え方を参考にする。
- フェーズ: P9。完了条件: ゲーム内カメラが、指定した`CameraPreset`のyaw・pitch・FOVに数値どおり設定され(数値で検査)、撮影した画像に被写体全体が入る(外接矩形が画像の一定割合に収まる)。**生成画像と同じ構図になること自体は要求しない**(生成AIは角度を守れない。比較は意味の比較、`03` 0.5節)。`PREVIEW`で、ブロックの向きと位置が施工リストどおりに写る。撮影後にカメラが元に戻る。

### N-23 Vision Critic(絵 vs 実物の差分)
- 種別: AI-視覚+AI-構造化。
- 役割: 参考画像(コンセプト・建屋の完成予想図)とスクリーンショットを比べ、`CritiqueReport`(一致スコアと差分リスト、差分の種類)を出す。
- 実装: **新規** `build/ai/stages/VisionCriticStage`。
- フェーズ: P9。完了条件: 意図的に屋根の色を変えた実物で、屋根の差分が指摘される。差が無ければスコアが高い。

---

## Stage 4: ユーザーレビュー

### N-24 Preview(ホログラム・並列表示・予測レポート)
- 種別: UI。
- 役割: 施工リストを、半透明のホログラムとして現地に重ねて見せる。絵と実物(またはホログラム)の並列表示。予測レポート(生産量/分・応力)、材料表(BOM)、検証結果(`Issue`)、所要時間の見積もり。承認ボタン(D-3: 承認するのはハッシュ)。
- 実装: **新規** `client/build/HologramRenderer`、`client/build/BuildReviewHud`、`client/build/SideBySideView`。承認は`ApprovePlanPayload(manifestHash)`。
- フェーズ: P5(ホログラム・承認・材料表・**差分ホログラム**(建てた後の変更用。追加=緑、撤去=赤、変更=黄)、**施工リストの`.nbt`書き出し**(`ManifestExporter`。D-21))、P9(並列表示)、P11(予測レポート)。同じ画面に表示欄を追加していく。
- 完了条件: ホログラムがブロックの位置と向きどおりに出る。クライアントとサーバーのハッシュが一致しないと承認できない。

### N-25 Critique Router(ダメ出しの分類)
- 種別: AI-構造化。
- 役割: ユーザーのダメ出しを、雰囲気・全体像=Refiner、生産量・製品=Process Planner、建屋の形・色=DesignGen、ラインの並び=Module Plannerに振り分け(`RouteDecision`)、制約を取り出す。
- 実装: **新規** `build/ai/stages/CritiqueRouterStage`。
- フェーズ: P14(戻し先の全ノードが揃った後に、1回で完成させる)。それまでは、ユーザーが`RouteChooserScreen`(P8で作る)で戻し先を選んで手動で戻す。P14の後も、分類できない(`OTHER`)ときの入口として残る。
- 完了条件: 4分類のダメ出し文が、正しい戻し先に振り分けられる(評価セット)。

---

## Stage 5: 施工と運用

### N-26 Builder(建屋の一括施工)と 施工ドローン
- 種別: 施工。
- 役割: サーバーの`ConstructionJob`が、承認済み施工リストを`BuildPhase`順に実行する。構造(`SITE_PREP`〜`ENVELOPE`)は高速に、機械・装飾(`POWER`以降)はドローンが1個ずつ設置して見せ場にする(P-7)。ドローン(演出用`DroneEntity`)は各置き場所に飛び、パーティクルと音を出す。設置自体はジョブが行う(D-11)。
- 実装: **新規** `construction/ConstructionJob`・`ConstructionExecutor`・`PlacementApplier`・`DroneShow`。既存`drone/DroneEntity.java`を演出用に流用。
- フェーズ: P4。完了条件: 実機で、手書きの計画から小屋が建つ。停止・再起動しても続きから再開する。
- 失敗時: 置けない→`Deviation`→L7。

### N-27 Drone(micra drone)
- 種別: 施工の演出。
- 役割: 各置き場所に飛び、パーティクルと音で「置いている」ことを見せる。**実際の設置はN-26のジョブが行う**(D-11)。ドローンの数は総設置数に応じて増える(04 F-2)。
- 入力: `ConstructionJob`の現在の設置位置(サーバーtickごと)。出力: 演出(`DroneEntity`の移動、パーティクル、音)。失敗時: ドローンが消えても(チャンクの読み込み解除など)、ジョブは進行する(演出だけが止まる)。**演出用のドローンは保存しない**(ワールドに永続化しない設定にし、専用のタグを付ける)。落ちた後や再起動の後に持ち主のいない演出用ドローンが残らないよう、起動時に、このタグを持つドローンを掃除する。
- 実装: **新規** `construction/DroneShow`。既存の`drone/DroneEntity.java`を演出用に流用(施工専用の`DroneEntity`をジョブごとに生成し、終了で消す。畑の制御ブロックのドローンとは別)。既存の畑の`DroneControllerBlockEntity`・`LiveDroneApi`・`PacedActionQueue`には手を入れない(P-15)。
- フェーズ: P4。完了条件: 実機で、ドローンが設置位置に飛んで見せ、ジョブが完了するとドローンが消える。ドローンを強制的に消しても、ジョブは完了する。

### N-28 Aero(Create: Aeronautics)
- 種別: 施工・部品。
- 役割: 発着場、飛行船(気球・バーナー・プロペラ・物理アセンブラ・操縦部・係留部)の部品を登録簿に載せ、テンプレート化する。**組み立てでブロックが世界から消えて動く構造物になる**ので、`AssemblySpec`・`ASSEMBLED_AWAY`で扱う(`01` 4節)。
- 入力: `SemanticPlan`(`logistics`と、発着場・飛行船のモジュールのノード)。出力: 展開(`PlanExpander`)とコンパイルで、発着場と飛行船の施工リストの部分と組み立ての手順(`AssemblyStep`)が作られる(サーバーが自分で再構築するので、承認のハッシュに含まれる)。失敗時: 組み立ての結果が期待と違えば`E-ASSEMBLY-FAILED`、係留部が合わなければ`E-DOCK-MISALIGN`。
- 実装: **新規** `build/parts/AeroParts`(`aeronautics:`・`simulated:`・`offroad:`の部品、`05` 1.3節)、`build/knowledge`のドック・飛行船テンプレート。依存はPhase 1で追加済み。
- フェーズ: P13(S-8の後)。完了条件: 実機で、発着場が建ち、飛行船が組み上がり(期待のエンティティが生成され、L7が欠落と誤判定しない)、係留でき、荷積み位置から荷を運べる。

### N-29 MC(ワールド)
- 種別: 環境(v3ではワールドを表すノード)。
- 役割: 設置の結果を、**検査に使う唯一の真実**として提供する。サーバーの本物のワールド。
- 入力: サーバー側の読み取り要求。出力: 状態つきの観測(`SparseSnapshot`、`VoxelClassGrid`)。**既存の`drone/ServerBlockSnapshotReader.java`(文字列でブロック名だけを返す既存の契約)は変更せず**、L7と意味解析用に別契約の`construction/ServerStateReader`を新設して読む(`01` 8節)。失敗時: チャンクが読み込まれていない位置は「不明」として返し、欠落とは扱わない(`PAUSED(CHUNK_UNLOADED)`)。
- フェーズ: P4。完了条件: 実機で、施工リストの位置のブロックとブロック状態を、既存のMCPツールを壊さずに読める(既存の`get_block_snapshot`のテストが緑)。

### L7の検査: ワールド → ドローン
- N-26の一部。詳細は`03_loops.md`のL7。

### N-30 Runtime Monitor(試運転・実測)
- 種別: 計測。
- 役割: (1)試運転(Commissioning): 建て終わった直後に、入力口へ品物を入れ、出力口に製品が出るか、回転・応力の状態を確認する。(2)運転中の観測: 毎分の生産量、停止、詰まり、過負荷を計測し、計画値との差(`RuntimeReport`)を出す。
- 実装: **新規** `construction/Commissioning`、`construction/RuntimeMonitor`。
- フェーズ: **試運転(Commissioning)=P11**(同梱テンプレートの検証に使うため)、**運転中の観測(Runtime Monitor)とL8=P13**。完了条件: 実機で、鉄インゴット1個を入れて鉄板が出る(P11)。ミキサーラインで真鍮が出る(P11)。運転中の計画値と実測値の差が記録され、ボトルネックが指摘される(P13)。

---

## 知見の蓄積

### N-31 Knowledge Store
- 種別: 決定論(保存と検索)。
- 役割: 成功したモジュール、修正履歴、ダメ出し履歴、好み、失敗例を、セッションをまたいで蓄積する(`KnowledgeRecord`)。
- 実装: **新規** `build/knowledge/KnowledgeStore`(プレイヤーごとのファイル。既存`chat/ChatHistoryStore`の保存の作法を踏襲)。
- フェーズ: **記録(書き込み)=P7**(L1・L4・L5・L7・L8などが終端イベントを書き込む先が、最初から在るように)、**昇格・検索・整理・L9=P14**。完了条件: P7=終端イベント(承認・却下・ダメ出し・修正)が保存される。P14=成功した施工(試運転まで成功、ユーザーが承認)が記録され、次回のRefinerの資料に入る。上限で自動では消さず、画面が出る。

---

## 起動時の走査と機器層(Task 25で追加)

### N-32 ModScan(導入modの走査・能力パックの有効化・事実の目録)
- 種別: 決定論(起動時。AIは使わない)。
- 役割: `ModList`のmod一覧を読み、**既知の能力パックが要求するmod**(`KNOWN_PACK`)、**他modのjarの内側にあるmod**(`BUNDLED_IN_PACK`。Aeronautics同梱の`simulated`/`offroad`)、**未知のmod**(`UNKNOWN_COMPATIBLE`/`UNKNOWN_UNSAFE`)に分類する。各`CapabilityPack`の`requiredMods`が全部入っていて版も範囲内なら`ENABLED`、そうでなければ理由つきで無効。有効なpackの部品・アナライザ・テンプレート・レシピ源を足し合わせて`EnabledRegistry`を合成し、**以降の全段(Refinerの資料、スキーマ、見本帳、コンパイラの検査)がこの有効な登録簿を使う**(P-16)。未知のmodごとに`UnknownModNote`を付けた`ModScanReport`を出し、さらに**ゲームが既に読み込んだ登録簿**(ブロック・品物・ブロック状態のプロパティ・ブロックエンティティ・タグ・レシピ・言語・品物/液体/エネルギーのハンドラ等の汎用の能力)を集めた`ModCatalog`を作る。**jarやmodのクラスを自分で読み込み・実行はしない**(D-31、F-27)。
- 入力: `ModList`のmod一覧(サーバー側)、ゲームのレジストリ・`RecipeManager`・言語ファイル・能力の一覧、`CapabilityPack`の宣言の一覧。
- 出力: `ModScanReport`(全modの分類・packの状態・理由・決定的な`digest`)、`EnabledRegistry`、`ModCatalog`(modごとの正規化した事実の目録)。**`digest`は登録簿の版ハッシュに含まれる**ので、同じmod構成からは同じレポート・同じ登録簿が出る。
- 実装: **新規** `integration/ModScan.java`(modの存在確認と登録簿の読み取り。modのクラスには触れない) + `build/registry/CapabilityPack.java`・`build/registry/EnabledRegistry.java`・`build/registry/ModCatalog.java`(一覧と合成ルールの純粋な核)。`OptionalModBoundaryTest`が境界を機械的に検査する。
- 失敗時: 読み取れない・壊れているmodは`UNKNOWN_UNSAFE`扱い(安全側に倒す)。packの要求modが欠けていても、そのpackだけを無効にして残りで起動を続ける(**modの不在はエラーではない**)。走査自体が例外で止まった場合は、`vanilla`だけで起動し、画面に「modの走査に失敗したため基本の部品だけ有効」と出す。
- フェーズ: **P16**。完了条件: (a)Create無しで起動→`create`packが`DISABLED_ABSENT`・理由つき・畑は動く。(b)全部入りで起動→全packが`ENABLED`。(c)同じ構成で2回起動→`digest`が一致。(d)未知modを入れて起動→分類と`UnknownModNote`が出て、部品としては登録されない。(e)Createを走査した`ModCatalog`が、手書きの部品表(`05` 1.2節)の対象ブロックを事実として含む(校正、F-27)。

### N-33 機器層(`DeviceGate`・出来事・教材)
- 種別: 決定論(検査は純Java)+サーバー権威(実機に触れる面はサーバー)。
- 役割: 機器(`DeviceDescriptor`が宣言した「読む・書く・出来事・安全限界・事前条件・緊急停止」)への指示を、**実行の前に必ず検査**する。検査は(1)型と範囲、(2)事前条件(`requires`)、(3)安全限界(`hard`は拒否、丸めてよい種類だけ`CLAMP`)、(4)`ESTOP`の状態、の順。AIもスクリプトも迂回できない(P-17)。出来事は`attach_isr`/`raise_interrupt`の面名`device:<deviceId>:<event>`に載せる。操作・出来事・拒否を`MonitorRecord`として記録し、L9(蓄積)とL8(実測→増設)の材料にする。各機器に子供向けの`ReferenceSheet`(「できること・できないこと・絶対に止まる条件」)を自動生成し、**AIの資料にも同じ物を使う**(正本は1つ)。詳細は`08_device_model.md`。
- 入力: `DeviceDescriptor`(packまたは同梱データが宣言)、機器への指示(スクリプト・AI段・画面から)、`ESTOP`の状態、サーバーの機器の実測。
- 出力: 検査を通った機器操作、`DeviceEvent`(ソフトウェア割り込み)、`MonitorRecord`、`ReferenceSheet`、検査失敗の`Issue`(`E-DEVICE-*`、`E-ESTOP`、`W-DEVICE-CLAMPED`)。
- 実装: **新規** `build/device/DeviceModel.java`・`build/device/DeviceGate.java`(宣言と検査の純粋な核) + `integration/device/`(実機に触れる面。畑ドローンの制御ブロックを最初の機器として包む)。
- 失敗時: 検査に落ちた指示は`E-DEVICE-*`で拒否(例外で潰さず`Issue`として返す)。`ESTOP`発動中は全書き込みを`E-ESTOP`で拒否し、解除は所有者かOPの明示操作のみ(発動・解除は記録に残る)。機器が応答しない・チャンク未読込みの読み取りは「不明」を返し、欠落や0とは区別する。
- フェーズ: **P17**。完了条件: 実機で、(a)範囲外の書き込みが`E-DEVICE-RANGE`/`W-DEVICE-CLAMPED`で止まる、(b)`ESTOP`発動→全書き込みが`E-ESTOP`→明示解除で復帰、(c)機器の出来事(例: 収穫可能)が`attach_isr`のISRを起こす、(d)`ReferenceSheet`が宣言どおり生成され、AIの資料と画面に同じ文が出る。

### N-34 Mod Interpreter(未知modの草案を作るAIの段)
- 種別: AI-構造化(状態なし呼び出し。資料は`Dossier`)。
- 役割: `ModCatalog`の**事実だけ**を資料にして、`ModDraft`(部品の草案・機器プロファイルの草案・モジュールのテンプレートのアイデア・制御コードの提案)を構造化出力で出す。**すべての主張(`DraftClaim`)は、根拠の目録の事実(`evidenceFactId`)と信頼度を持ち、分からない物は`unknowns`に入れる**(推測で埋めない)。作った草案はそのままでは使えず、サンドボックスの実測(`SANDBOX_TESTED`)と人の承認(`APPROVED`)を経て初めてpackに昇格する(`PROMOTED`)。詳しい流れはF-27。
- 入力: `ModCatalog`(正規化した事実のみ。jar・modの説明文などの自由文は渡さない)、既存packの例(形式の参考)。
- 出力: `ModDraft`。
- 実装: **新規** `build/ai/stages/ModInterpreterStage`。検証は決定論(各claimの`evidenceFactId`が目録に実在すること、`unknowns`が明示されていること)。
- 失敗時: スキーマ違反→再試行1回→`E-SCHEMA`。根拠の無い主張は草案から除いて警告。mod構成が変わったら(`digest`が違えば)作り直す。
- フェーズ: **P16**。完了条件: Createを通訳させた草案が、手書きの`create`packの部品表(`05` 1.2節)を高い割合で再発見する(一致率を実測して記録。校正)。新しい3modの草案が、人がjarから読んだ一覧と突き合わせられる。未承認の草案の部品は計画に書けない(`E-UNKNOWN-CAPABILITY`)。

---

## 横断部品(v3のノード外だが必須)

| 部品 | 役割 | 実装 | フェーズ |
|---|---|---|---|
| `PartTypeRegistry` | 使ってよい部品の名簿(D-17〜D-19の土台) | `build/parts` | P3(建築部品)、P10(Create部品)、P13(Aeronautics) |
| `PartAtlas`+`PartAtlasRenderer` | 部品見本帳を作る | `client/build/PartAtlasRenderer` | P8 |
| `BuildOrchestrator` | 状態機械。各ノードとループを進行、保存 | `build/ai` | P7 |
| `LoopEscalationScreen`・`RouteChooserScreen` | ループの上限超過の画面、戻し先を選ぶ画面 | `client/build` | P7・P8 |
| `ServerStateReader`・`ServerWorkerPool`・`ConstructionBudget` | 状態つき読み取り、重い計算のワーカー、サーバー全体の施工予算 | `construction` | P4 |
| `PlaceableBlockPolicy` | 置いてよいブロックの許可リスト(D-22) | `construction`・`build/compile` | P3(判定の純Java部分)・P4 |
| MCP読み取りツール群 | AIが調べるための読み取り専用ツール(書き込み無し) | `chat`の`BlockSnapshotToolServer`を拡張 | P7 |
| `ConstructionJob`保存・`SiteClaim` | ジョブ・区画予約の永続化 | `construction` | P4 |
| 承認・権限 | ハッシュ承認、所有者/OP確認 | `construction` | P4 |
| `CostLedger` | 費用台帳 | `build/ai` | P7 |
| `StageCliRunner` | 建設のAI段を動かす呼び出し器。呼び出しごとに自前のプロセスと取消の手段を持ち、`BuildOrchestrator`の寿命に結びつく(IDE画面を閉じても続く)。`claude.exe`を直接起動する(`06` 1.2節) | `chat`(既存の起動ヘルパを流用) | P7 |
| `ManifestExporter` | 施工リストを標準のストラクチャーNBT(`.nbt`)に書き出す(D-21) | `client/build`・`construction` | P5 |
| `PlacedRegistry` | このプロジェクトが置いたブロックと、組み立てた個体(`AssemblyResult`)の記録(D-25)。区画が解放されるまで保持 | `construction` | P4 |
| `MachineSetupRegistry` | レシピ種別(と加熱条件)→検証済みの機械の組み立て(`MachineSetup`)の対応表。Createのレシピは機械の並びを持たないので、私たちの正本 | `build.knowledge`(同梱) | P10(作成)・P11(テンプレートと同じ検証) |
| `AnalysisPipeline` | 展開・コンパイル・アナライザを順に走らせる登録式の検証列。P4=展開・コンパイル・安全枠、P6=+Blueprint Analyzer、P11=+Factory Analyzer | `build.analyze` | P4〜P11 |
