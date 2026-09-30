# 自然言語→工場建設 Phase 4: サーバー施工ランタイム Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 承認済みで決定論的な施工リスト(`PlacementManifest`)を、サーバーが安全に、1個ずつ、ドローンの演出つきで建てる「施工ランタイム」を作る。手書きの計画JSON(同梱の小屋の見本)をデバッグコマンドで投入すると、小屋がドローン演出つきで建ち、状態まで検査され(L7)、再起動・取消・ロールバック・変更(`MODIFY`)・材料・権限・区画・整地・分割ペイロード・性能の線をすべて満たす。**確認はオーナーの手を借りない**: devkit(開発専用mod)のHTTP APIと台本(`tools/p4/`)で実際のゲームを自動で動かし、証拠(JSON・スクリーンショット)を残す。子供に見える文言は、子供ペルソナの自動プレイテストで確かめる。

**Architecture:** 判断のすべて(ジョブの状態機械、カーソルと再開、施工予算と自動減速、置換の規則、安全枠、材料台帳、区画、設置の記録、取り消し記録とロールバック、`MODIFY`、L7の差分と修復、承認とハッシュ、原子的な保存と復旧、分割ペイロードの組み立て)は、**Minecraftに依存しない純Java**(新設`construction.core`と`build.verify`、既存`build.compile`への追加)に置き、`FakeWorld`などの偽物でJUnitで検査する。Minecraftに触れるのは薄いアダプタ(`construction`・`construction.net`・`client.build`)だけで、アダプタの挙動は単体テストで検証したことにせず、**devkitと台本による実機の自動確認**で証拠を取る。ワールドを書き換えるのはサーバーのメインスレッドだけ(F-1)。重い純計算は`ServerWorkerPool`で、不変の入力に対して行う。`PacedActionQueue`は使わない(D-11)。

**Tech Stack:** Java 21(record・sealed interface・パターンマッチ)、JUnit 5、NeoForge 21.1.238 / Minecraft 1.21.1(`SavedData`、`ServerTickEvent.Pre`、`BlockEvent.EntityPlaceEvent`、`ModConfigSpec`、`CustomPacketPayload`)、既存の`chat/MiniJson`・`build.model.CanonicalJson`・`Hashing`。devkit(別リポジトリ`G:\prj2\micra_drone_devkit`。JDK内蔵の`com.sun.net.httpserver`)。台本はPython 3.12(標準ライブラリのみ。`unittest`・`urllib`・`ctypes`)。新規の外部ライブラリなし。

**Spec:** `docs/design/nl_factory_builder/`(第7版)。特に`00`のD-1・D-3・D-11・D-16・D-22〜D-27、`01`の4・4.1・5・8・10・11・12節、`04`のF-1〜F-8・F-13〜F-17・F-20〜F-23、`07`のP4(完了条件16個)・3節(S-6・S-9)・4節・7節・8節、`03`のL7・0.4節、`02`のN-26・N-27・N-29。

## Global Constraints

(設計図とオーナーの指示から逐語。すべてのタスクの要件に含まれる。)

- **世界を書き換えるのは、サーバーのメインスレッドだけ**。経路は1本: `承認された施工リスト → ConstructionJob → サーバーtickで1個ずつ設置`(D-1・F-1)。重い純計算(展開・コンパイル・整地・安全枠)は`ServerWorkerPool`で、メインスレッドで取り出した不変の入力に対して行い、結果はメインスレッドへ投げ返す。
- **純Javaの核**(`build.*`と新設の`construction.core`)は、`net.minecraft.*`・`net.neoforged.*`・`com.mojang.*`とCreate系のクラスを一切importしない(D-16。`BuildPurityTest`が機械的に検査する。`construction.core`はTask 3で検査対象に加える)。`construction.core`は`construction`(アダプタ)・`construction.net`・`client`・`drone`をimportしない(`drone.MainThreadGateway`も使わず、`java.util.concurrent.Executor`を受け取る)。
- **承認の対象は、サーバーが自分で作り直した施工リストのハッシュ**(D-3)。クライアントが送るのは`SemanticPlan`と`TemplateBundle`だけ。ハッシュにはディメンションが入り(D-27)、承認の時点でプレイヤーが同じディメンションにいることを確認する。
- **`PacedActionQueue`・`DroneApi`・`LiveDroneApi`・`DroneControllerBlockEntity`の挙動は変えない**(P-15・D-11)。`drone/ServerBlockSnapshotReader.java`と`drone/BlockRangeDescription.java`は**変更しない**(`01` 8節)。既存のテスト(着手時点の`src/test`の全ファイル)は、毎タスク全部緑のまま。
- **撤去・変更できるのは、このプロジェクトが置いたブロック(`PlacedRegistry`に載る物)だけ**(D-25)。元からあったブロックエンティティは置換も撤去もしない(空だと確かめたコンテナを、利用者が明示的に確認したときだけ例外。F-5)。このプロジェクトが置いたコンテナの中身は、撤去の前にドロップして保全する。
- **材料の複製を作らない**(F-7): 消費は設置ごとに冪等、取消では返さない、ロールバック・`MODIFY`の撤去は「消費した記録がある分だけ」返す、置換で壊した自然のブロックはドロップしない。整地で切ったブロックは所有者へ、盛るブロックは所有者から。
- **区画(`SiteClaim`)と設置の記録(`PlacedRegistry`・`journal`)は、区画が解放されるまで保持する**(D-23)。ジョブの終了では解放しない。
- **オーナーは確認の輪に入れない**(2026-09-30のオーナーの指示): 実機の確認は、devkit(`127.0.0.1:47391`=クライアント、`127.0.0.1:47392`=サーバー。Task 18で足す)と台本(`tools/p4/p4_scenarios.py`)で自動に行い、証拠(JSONとスクリーンショット)を`run-evidence/p4/<runId>/`に残す。**OSのマウス・キーボードの合成入力は使わない。ゲームの窓へ文字を送らない**(過去に、合成したキー入力でシェーダーがOFFになる事故があった)。ゲームは`gradlew runClientP4`/`runServerP4`など(Task 19で足す専用の実行設定。どれも`-Xmx3G`。Store版ランチャーを使わない)で起動する。シングルプレイの世界は、タイトル画面からdevkitの`/client/create-world`で作り(EULAが要らない。コマンドの許可つき)、2回目からは`--quickPlaySingleplayer`で開く。マルチプレイは`--quickPlayMultiplayer`。台本は起動の前に47391・47392・47393・25565が空いていることを確かめ、devkitの応答の`runId`が自分の物でなければ止まる(**林さんが別に起動しているゲームを操作しない**)。閉じるときは窓に`WM_CLOSE`を送り、窓が無ければ報告して止まる(ループしない)。起動したプロセスは、台本の終わりに必ず後始末する(ゲームのフォルダは次の実行のために残し、世界は次の実行の始めに消す)。自動確認の結果は`PASS`・`FAIL`・`NOT-RUN`で、**`NOT-RUN`は合格として数えない**。
- **子供に見える文言は、データ(`assets/micradrone/lang/ja_jp.json`・`en_us.json`)にする**(Task 14)。サーバーは`Component.translatable`で送り、文言をコードに直書きしない。
- 定数はすべて名前を付け、出どころ(設計の節)をコメントに書く。生の数値を直書きしない。同じ引数の組は引数のrecordに、同じ文字列は名前付き定数に、同じ検査は共有のヘルパーにまとめる。外部と接する処理(ファイル・ネットワーク・ワールド)は、純粋な計算と別のクラスに分ける。
- コードのコメントは英語で技術的な理由だけを書く(「林さんの要望」などとは書かない)。`Objects.requireNonNull`・record・sealed interface・JUnit 5の既存の書き方に合わせる。
- 1タスク=1コミット(日本語。`feat|fix|test|refactor|docs: <説明>(自然言語→工場建設 P4 Task N)`、空行、署名の行)。**署名の行は、コードを書いた者で決める**: Devin(SWE-2)が実装したコミットは`Implemented-by: SWE-2 via Devin CLI`(Claudeの`Co-Authored-By`を付けない。オーナーの指示: 実装のコードはDevinに書かせる)、Claudeが書いたコミットは`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`。各タスクのコミット手順の署名の行は、この規則で読み替える。**pushしない。`--amend`しない。** devkitの変更は、devkitのリポジトリに同じ形式でコミットする。
- **担当の分け方**(オーナーの指示): 各タスクの見出しの下の「担当」の行に従う。
  - **Devin(SWE-2)**: `src/main`・`src/test`のJavaと、`src/main/resources`のJSON(言語ファイル・タグ)。PowerShellで、**1回に1つの単純なコマンド**だけを打つ(`&&`・`;`・パイプでつながない)。Pythonは実行しない。`docs/`・`tools/`・別のリポジトリ(devkit)は編集しない。ファイルを消さない。コミットは、ファイルを**1つずつ名前で**`git add`し、`git status --short`で余計な物が無いことを見て(`build/libs`が変わっていれば`git checkout -- build/libs`)、メッセージを作業用のフォルダの`commit_msg.txt`に書いてから`git commit -F <そのファイル>`。署名の行は`Implemented-by: SWE-2 via Devin CLI`。
  - **コントローラ(Claude)**: Task 1・2・18・19・35(設計図・README・Issue)・36・38、devkitのリポジトリの作業すべて、`tools/p4`とPythonの実行すべて、`docs/`の編集、ゲームの起動を伴う確認(`gradlew runClient*`)。署名の行は`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`。
  - **1つのタスクに両方の仕事がある時**: Devinの分(Java)を先に1つのコミットにし、その後でコントローラの分(台本・実機の確認・devkit)を別のコミットにする(署名の行が違うため、1タスク1コミットの例外。どちらのコミットにも同じ`(自然言語→工場建設 P4 Task N)`を付ける)。devkitはさらに別のリポジトリのコミット。
  - **設計図の変更がJavaのテストと結び付くタスク**(Task 3・11・12・26。例: `IssueTest`が`05`の表を読む): コントローラが先に設計図を書き(コミットしない)、Devinがそのファイルも`git add`して自分のコミットに含める(Devinは編集しない。含めるだけ)。こうすると、どのコミットの時点でもテストが緑になる。
  - 各タスクのコミットの手順に書いた`bash`の形(`git commit -m "$(cat <<'MSG' ...`)は、コントローラの分の書き方。Devinの分は上の`-F`の形で読み替える(タスクに両方を書いた物もある)。
- **AIは使わない**(P4の範囲)。後のフェーズのCLI呼び出しのテストは、オーナーが許可したスタブで行う(P4では該当なし)。
- 設計との食い違いを見つけたら、コードより先に設計図を直す(`07` 7節の6)。この計画書で確定する設計の変更は、Task 35で設計図に反映し、`00`の変更履歴に書く(一覧は末尾の「設計図との食い違い」)。

## Review Focus

(設計が暗に求めるが、どのタスクのテストにも自然には出てこない、実害の大きい入力。起きやすい順。各行の対策テストは、担当のタスクに書いてある。)

1. **施工中に、子供やほかのプレイヤーが現場をいじる**: まだ置いていない位置にブロックを置く/置いた壁を壊す/置いた階段を回す。期待: 置いていない位置は`PAUSED(SITE_CHANGED)`で止まり、その位置だけが`Conflict`になる(上書きしない)。置いた後に壊された位置はL7が直し、回された階段(自分が置いた物)は直すが、別の種類のブロックに置き換えられた位置は触らずに報告する(Task 9 `pausesWhenAnUnbuiltPositionChanged`、Task 10 `aReplacedBlockIsAConflictNotARepair`、Task 13 `theL7RoundRepairsBrokenBlocksAndReportsReplacedOnes`)。
2. **きれいに止まらなかったサーバー**(落ちた・電源が切れた): `SavedData`とチャンクは非同期に書かれ、チャンクは読み込みから外れた時にも書かれる(Task 24の「保存の順序の事実」)ので、`SavedData`のカーソル、別ファイルの`journal`・`ledger`・`PlacedRegistry`、**世界そのもの**の新しさが、どの向きにもずれる。期待(**控えめな復旧の原則**。Codexの3回目のレビュー2026-09-30): 推測しない。施工の実行はどれも**先行書き込みログ**(Task 9)を先に書き、確定点(フラッシュつきの保存)だけを「ディスクにある」とみなす。起動時の復旧は、確定点より後の終わった回の書き込みを、世界がちょうど予定のブロックを見せる所だけ取り込み(宣言の無い位置のそっくりなブロック=なりすましは引き取らない)、失われた所は古いカーソルから置き直す(台帳のキーが同じなので二重に取らない)。持ち物は保存された取引IDで決め、**回の途中で落ちた回と補給チェストを動かした回は決めずに持ち主に聞く**(`RECOVERY_NEEDED`、`adopt`/`discard`)。ジョブは確定点の後にしか終わらない。ログで説明できない、カーソルより前の記録の欠けは`PAUSED(RECOVERY_NEEDED)`(黙って再実行しない)。残る限界: 終わった回の位置に、止まっている間にプレイヤーがちょうど同じブロックを置くと見分けられない(Task 35で`04` F-2に書く)(Task 9 `CrashRecoveryTest`(すべての書き込みの間で落とし、自動保存は一部のチャンクだけ、持ち主はリンゴを拾う・捨てる)・`WalRecoveryTest`、Task 13 `aJobEndsOnlyAfterADurablePointCoversItsRuns`、Task 24 `JobServiceWalRecoveryTest`・`aJournalBehindTheCursorIsNotSettledUntilAdopted`・`aJournalAheadOfTheCursorIsNormal`、Task 25の`crash-barriers`(規約のすべての境目での本当の強制終了)と`real-crash`)。
3. **同じtickに、2人が重なる敷地を承認する**: どちらも承認の検査は通る。期待: 区画の予約はメインスレッドで順に行い、先の1人だけが`QUEUED`、後の1人は`CANCELLED`(`E-CLAIM-OVERLAP`)(Task 13 `overlappingApprovalsInOneTickAdmitOnlyTheFirst`)。
4. **所有者が承認の途中で抜ける・ディメンションを移る**: 保留中の承認・動いているジョブ。期待: 承認は無効(`dropOwner`)、別ディメンションからの承認は`DIMENSION_MISMATCH`、動いていたジョブは`PAUSED(OWNER_OFFLINE)`で、戻れば自動で再開(Task 12 `approvalFromAnotherDimensionIsRejected`、Task 13 `theJobPausesWhileTheOwnerIsAwayAndResumesOnReturn`)。
5. **材料の数え方が、ブロックと品物で1対1にならない。撤去で品物が勝手に落ちる**: 扉(上下2ブロックで1個)、2段の半ブロック(1ブロックで2個)、壁掛け看板(品物は看板)、メカニカルベルト(品物は`belt_connector`)。サバイバルで足りない→補給→再開、ロールバックで返す個数。さらに、撤去で扉の片方や壁の看板を普通に消すと、隣の更新でもう片方・看板が**アイテムを落として**外れ、台帳の外で品物が増え、偽の`Conflict`が出る。期待: 上の半分の扉は数えない、2段は2個、返却は消費と同じ個数で1回だけ、撤去で落ちるアイテムは0(入れ物の中身を除く)(Task 6 `doorsSlabsSignsAndBeltsUseTheirItems`、Task 9 `survivalChargesTheDoorOnceAndPausesWhenShort`・`rollbackRestoresEveryBlockTopDownAndReturnsExactlyWhatWasConsumedOnce`・`aDoorIsRestoredAsOnePieceEvenAcrossTheAllowanceAndSettledOnce`、Task 28 `rollbackRestoresTheSiteReleasesTheClaimAndReturnsWhatWasConsumedOnce`・`aDoorsHalvesGoTogetherAndASignGoesBeforeItsWall`と`rollback`シナリオの落ちたアイテム0)。

## File Structure

新規(`src/main/java/io/github/khayashi4337/micradrone/`の下。テストは`src/test/java/...`の同じ相対パス):

| パッケージ | クラス | 責務 | Minecraft依存 |
|---|---|---|---|
| `construction.core`(新設) | `JobState`・`PauseReason`・`JobKind`・`MaterialPolicy`・`JobEvent`・`JobStateMachine`・`ConstructionJob` | ジョブの型と状態機械 | なし |
| `construction.core` | `CellTrait`・`WorldCell`・`WorldPort`・`PlaceResult`・`Refusal`・`Destruction`・`ReplaceDecision`・`ReplaceRules` | 抽象の世界と置換の規則 | なし |
| `construction.core` | `SafetyLimits`・`PlacementSurvey`・`ItemCatalog`・`ReplacementSummary`・`SafetyReport`・`SafetyEnvelope` | 安全枠(F-5) | なし |
| `construction.core` | `BudgetConfig`・`BudgetJob`・`QueuedJob`・`Allowance`・`BudgetTick`・`ConstructionBudget` | 施工予算・自動減速(F-2) | なし |
| `construction.core` | `JournalRecord`・`UndoEntry`・`Journal`・`PlacedEntry`・`PlacedRegistry`・`AssemblyResult`・`MaterialLedger`・`LedgerBook`・`SkippedPlacement`・`JobOutcome`・`RestoreItem`・`PutItem`・`JobProgram` | 記録と台帳 | なし |
| `construction.core` | `MaterialPort`・`ExecutionContext`・`StepReport`・`ConstructionExecutor`・`Attachments` | 1tick分の設置・撤去(撤去は部品ごと。付いている物が先) | なし |
| `construction.core` | `SiteClaim`・`ClaimBook`・`OperatingBox` | 区画(F-4・D-23) | なし |
| `construction.core` | `Confirmations`・`AcceptedRisk`・`ApprovalRequest`・`PendingApproval`・`PlanSubmission`・`CompiledPlan`・`PlanCompilation`・`SurveyCache`・`Candidate`・`Approver`・`ApprovalRejection`・`ApprovalDecision`・`ApprovalDesk`・`WorkResult`・`ServerWorkerPool` | 承認とワーカー(F-3) | なし |
| `construction.core` | `JobWorld`・`TickInput`・`JobUpdate`・`JobStatus`・`ControlResult`・`JobRecord`・`RepairQueue`・`JobService` | ジョブの進行(tick) | なし |
| `construction.core` | `ChildMessages`・`MessageKey` | 子供向けの文言のキー(Task 14) | なし |
| `construction.core` | `DroneMove`・`DroneChoreographer` | 演出の割り当て(N-27) | なし |
| `construction.core` | `FileSystemPort`・`NioFileSystem`・`SealedFile`・`PersistenceEnvelope`・`Migrations`・`JsonReads`・`JobCodec`・`JournalCodec`・`LedgerCodec`・`OutcomeCodec`・`ProgramCodec`・`ManifestCodec`・`ClaimCodec`・`WalCodec`・`SurveyCodec`・`JobFiles`・`RecoveryDecision`・`RecoveryPlanner`・`OrphanSweep` | 保存と復旧(F-2。先行書き込みログからの復旧はTask 9の`WalRecovery`) | なし |
| `construction.core` | `PlacementRules`・`PermissionLevel`・`PlaceVerdict` | 建築権限の純粋な部分(F-4) | なし |
| `construction.core` | `SupplyRegistry`・`SupplyCodec` | 補給チェストの登録(F-7。在庫の計算`Stock`・`Stocks`・`Move`はTask 9) | なし |
| `construction.core` | `SubmitOutcome`・`RemovalPreview`・`HeldContents`・`QueryArgs`・`MsptStats`・`ChunkSet`・`BlockSpecText`・`SaveTypes`・`RegistryCodec`・`JobLoad`・`RecoveryChoice`・`UnreadableFileException` | 提出の結果・撤去の予告・MSPTの計測・チャンクの集合・保存の型の表 | なし |
| `construction.core` | `RollbackPlanner`・`ModifyPlanner`・`ModifyPlan` | 撤去と変更(F-14・D-14) | なし |
| `build.compile`(追加・続き) | `SiteSurveyBuilder` | 調査をtickに分けて組み立てる | なし |
| `build.analyze`(続き) | `VoxelGridFiller` | 範囲読み取りをtickに分けて詰める | なし |
| `construction.core` | `UploadAssembler`・`UploadChunk`・`UploadResult`・`PlanChunker`・`PendingQueries`・`QueryKind` | 分割ペイロード・問い合わせ(F-3・F-8) | なし |
| `construction.core` | `JobViews`・`JobEventLog` | 状態のJSON(devkit・F-8・コマンド共通)とジョブの出来事のログ(F-17) | なし |
| `construction.core` | `PlanSource`・`PlanFileReader`・`SiteRelocation`・`ApproveArgs` | デバッグコマンドの純粋な部分 | なし |
| `build.verify`(新設) | `SparseSnapshot`・`CompareScope`・`DeviationKind`・`Deviation`・`SnapshotDiff`・`RepairPlan`・`RepairPlanner`・`SnapshotCollector`・`VolatileProps` | L7の差分と修復 | なし |
| `build.analyze`(新設) | `VoxelClassGrid` | 範囲読み取りの分類グリッド(F-21。解析器はP6) | なし |
| `build.compile`(追加) | `BlockMatch`・`ItemCount`・`BlockToItem`・`SiteSurvey`・`TerrainSummary`・`TerrainResult`・`TerrainPrep`・`PhaseRanges` | 状態の照合・品物の対応・整地 | なし |
| `construction`(新設) | `BlockStates`・`ServerWorldPort`・`ServerStateReader`・`ServerSurveyor`・`BuildTags`・`ConstructionConfig`・`ConstructionRuntime`・`RuntimeJobWorld`・`BuildCommands`・`DroneShow`・`Checkpoints`・`DevBarrier`・`PlacementGuard`・`InventoryMaterials`・`SupplyChests`・`ChunkKeeper`・`ServerMessages`・`ContainerPreview` | アダプタ | **あり(サーバー)** |
| `construction.net`(新設) | `SubmitPlanPayload`・`PlanPreviewPayload`・`ApprovePlanPayload`・`JobStatusPayload`・`QueryRequestPayload`・`QueryResponsePayload`・`BuildNetHandlers` | ペイロード | あり |
| `client.build`(新設) | `ClientBuildCommands`・`ClientUploads`・`ClientQueries` | デバッグ用のクライアント側コマンド(P5の画面の前段) | あり(クライアント) |

devkit(別リポジトリ`G:\prj2\micra_drone_devkit`。Task 18): `MicradroneDevkit`を共通のmodにし、`DevkitServerApi`(サーバー側HTTP、`127.0.0.1:47392`)・`DevkitClientBuildApi`(クライアント側の追加エンドポイント)・`DevkitProbe`(S-6の測定用ペイロード)・`DevkitProtectBox`(S-9の測定用の保護リスナー)・`DevkitRunInfo`(実行のIDの照合)・`DevkitTeeSource`(コマンドの出力を集める)・`DevkitWorldCreator`(タイトル画面から世界を作る)・`DevkitInjector`(開発専用の注入)を足す。

台本(Task 19〜): `tools/p4/p4_scenarios.py`(入口。`python -m tools.p4.p4_scenarios --all`)、`tools/p4/harness.py`(起動・窓・後始末)、`tools/p4/devkit_client.py`(HTTP)、`tools/p4/compare.py`(施工リストと読み戻しの照合)、`tools/p4/evidence.py`(証拠)、`tools/p4/persona_bundle.py`(ペルソナに渡す資料の束)、`tools/p4/plans.py`(計画のJSONを作る)、`tools/p4/scenarios_*.py`(シナリオ。タスクごとに1つ)、`tools/p4/game_config/`(起動の前に置く`options.txt`・`neoforge-client.toml`)、`tools/__init__.py`・`tools/p4/__init__.py`・`tools/p4/tests/__init__.py`(空)、テスト`tools/p4/tests/test_*.py`(`python -m unittest discover -s tools/p4/tests -t .`)。どれもリポジトリの一番上から`python -m tools.p4....`で実行する。

資源: `src/main/resources/data/micradrone/build_samples/hut.json`(小屋の見本)、`data/micradrone/tags/block/{terraformable,palette_allowed}.json`、`assets/micradrone/lang/ja_jp.json`(新規)と`en_us.json`(追記)。

変更(既存): `build/compile/{ObservedBlock,ReplacePolicy,Conflicts,BomCalculator,PlanCompiler}.java`(意味を変えない追加・共有化)、`build/model/IssueCode.java`(コードを4つ足す)、`build/model/Hashing.java`(バイト列の版)、`MicraDrone.java`(登録。チケットの登録はmodのバス)、`MicraDroneClient.java`(クライアントのコマンド・ペイロード)、`build.gradle`(自動確認の実行設定)、`src/test/.../build/BuildPurityTest.java`(`ALLOWED`を`Map.ofEntries`にし、`build.verify`・`build.analyze`を足す)、`.gitignore`(`run-p4/`・`run-evidence/`)、設計図(`00`・`01`・`04`・`05`・`07`、新規`09`)。

## 型の辞書(後のタスクが使う名前と形。ここと違う綴りを使わない)

`construction.core`をccと略す。

- cc `enum JobState {PENDING_APPROVAL, QUEUED, RUNNING, PAUSED, VERIFYING, REPAIRING, ASSEMBLING, VERIFIED, PARTIAL, FAILED, CANCELLED, ROLLED_BACK}` + `boolean terminal()`
- cc `enum PauseReason {OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, SERVER_BUSY, RECOVERY_NEEDED, USER, SITE_CHANGED}`(`SITE_CHANGED`は設計に足す。F-3の「置けなくなった位置は`PAUSED`」の理由)
- cc `enum JobKind {BUILD, MODIFY, REPAIR, ROLLBACK}`、`enum MaterialPolicy {CREATIVE_FREE, SURVIVAL_CONSUME}`
- cc `enum JobEvent {ADMITTED, CLAIM_REFUSED, START, PAUSE, RESUME, PLACED_ALL, NEED_ASSEMBLY, ASSEMBLED, CLEAN, NEED_REPAIR, REPAIRED, GIVE_UP, FAIL, CANCEL, ROLL_BACK_DONE}`
- cc `JobStateMachine.next(JobState, JobEvent) → JobState`(許されない組は`IllegalStateException`)、`allows(JobState, JobEvent) → boolean`
- cc `record ConstructionJob(int schemaVersion, String jobId, UUID ownerUuid, String dimension, String manifestHash, JobKind kind, String parentJobId, JobState state, PauseReason pauseReason, int cursor, int total, int repairRound, String claimId, MaterialPolicy materialPolicy, String journalFile, String ledgerFile, String lastError, long createdTick, List<String> acceptedRiskIds)` + `on(JobEvent)`・`paused(PauseReason)`・`withCursor(int)`・`withTotal(int)`・`withRepairRound(int)`・`withLastError(String)`・`withClaimId(String)`・`static create(...)`
- build.compile `record ObservedBlock(BlockSpec block, boolean hasBlockEntity, String blockEntityType)`(既存の1引数コンストラクタは残す)
- build.compile `BlockMatch.satisfies(BlockSpec observed, BlockSpec expected, Set<String> ignoredProps) → boolean`(`STATE_SUBSET`: 期待に書いた状態だけ)、`BlockMatch.exact(...) → boolean`(`EXACT`: 両方の状態を全部)
- cc `enum CellTrait {REPLACEABLE, FLUID, LEAVES, TERRAFORMABLE, UNBREAKABLE, EMPTY_CONTAINER}`、`record WorldCell(boolean loaded, ObservedBlock observed, Set<CellTrait> traits)`
- cc `interface WorldPort { WorldCell read(IntPos); PlaceResult place(IntPos, BlockSpec, Map<String,String> blockEntityConfig, UUID actor); PlaceResult restore(IntPos, BlockSpec, UUID actor, boolean dropContentsFirst); void settle(List<IntPos> positions); }`、`enum PlaceResult {PLACED, DENIED, INVALID}`
- cc `enum Refusal {FOREIGN_BLOCK_ENTITY, UNBREAKABLE, NOT_REPLACEABLE, NOT_TERRAFORMABLE, EXPECTED_OTHER}`、`enum Destruction {NONE, FLUID, LEAVES, EMPTY_CONTAINER, TERRAIN}`、`sealed interface ReplaceDecision {Place(Destruction), AlreadyDone(), Refused(Refusal)}`、`ReplaceRules.decide(Placement, WorldCell, boolean journaled, Optional<BlockSpec> ownPlaced)`(Task 4 修正ラウンドで`boolean placedByProject`から変える。所有=登録簿の記録があり、今のブロックが置いたブロックと同じID)
- build.compile `record SiteSurvey(String dimension, Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water, boolean[][] tree, String digest)` + `static of(...)`・`static air(String, Box)`・`int surfaceAt(int x, int z)`・`boolean hasGround(int x, int z)`(表面のブロックが空気でない。空気の列は整地しない)・`boolean covers(int x, int z)`・`SurveyRef ref(long cachedUntilTick)`
- build.compile `ReplacePolicy.Terraform`(`CODE_TERRAFORM="terraform"`)、`ReplacePolicy.fromCode(String)`
- build.compile `record TerrainSummary(int cut, int fill)`、`record TerrainResult(PlacementManifest manifest, TerrainSummary summary, List<Issue> issues)`、`TerrainPrep.apply(PlacementManifest, SiteSurvey) → TerrainResult`、`PhaseRanges.of(List<Placement>) → List<PhaseRange>`
- build.compile `record ItemCount(String itemId, int count)`、`BlockToItem.cost(BlockSpec) → Optional<ItemCount>`・`cutYield(BlockSpec) → Optional<ItemCount>`・`merge(List<ItemCount>) → List<ItemCount>`
- cc `record SafetyLimits(int maxPlacements, int maxSizeX, int maxSizeY, int maxSizeZ, int minBuildY, int maxBuildYExclusive)`、`record PlacementSurvey(Map<IntPos,WorldCell> cells)`、`interface ItemCatalog { boolean exists(String itemId); }`、`record ReplacementSummary(int fluids, int leaves, int emptyContainers, int terrainCut, int terrainFill, List<IntPos> destructiveSample)`、`record SafetyReport(List<Issue> issues, ReplacementSummary replacements)`、`SafetyEnvelope.check(PlacementManifest, TerrainSummary, PlacementSurvey, SafetyLimits, PlaceableBlockPolicy, ItemCatalog, Function<IntPos,Optional<BlockSpec>> ownPlaced) → SafetyReport`
- cc `ConstructionBudget(BudgetConfig)`: `admit(List<QueuedJob>, List<BudgetJob>) → List<String>`、`allocate(long tick, double averageMspt, List<BudgetJob>) → BudgetTick`、`static droneCount(int, BudgetConfig)`、`static etaTicks(int fast, int slow, BudgetConfig)`
- cc `record JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed, int ledgerKey, boolean terrainCut)`(6引数のコンストラクタも残す)、`Journal`、`PlacedRegistry`、`MaterialLedger`(台帳は個数で持つ)、`enum MaterialOp {CHARGE, YIELD, RECLAIM, RETURN}`、`LedgerBook`、`JobOutcome`、`record PutItem(int index, int ledgerKey, Placement placement)`、`record RestoreItem(IntPos pos, BlockSpec expectedNow, Set<String> volatileProps, BlockSpec restoreTo, String sourceJobId, int sourceLedgerKey, boolean dropContents, List<Integer> earlierSourceKeys)`+7引数の形(`earlierSourceKeys`は空)・`List<Integer> sourceKeys()`(前のキー、最後に`sourceLedgerKey`。撤去はこの全部を精算する。`RollbackPlanner`・`ModifyPlanner`は`PlacedEntry.earlierKeys()`を渡す)、`record JobProgram(List<RestoreItem> restores, List<PutItem> puts)`
- cc `Attachments.dependent(BlockSpec)`・`samePiece(RestoreItem, RestoreItem)`・`REMOVAL_ORDER`、`JobOutcome.addRestoreConflict`・`hasRestoreConflictAt`・`resolveConflictAt`、`record RecoveryDecision(ConstructionJob job, JobLoad load, boolean fromLog)`、`JobRecord.requireRecovery()`・`possiblyLostDrops()`、`WalCodec.frame(WalEntry)`・`readFrames(byte[]) → Frames(entries, validLength)`、`record HeldContents(int items, long fluidMillibuckets, boolean burningFuel)`
- cc `interface MaterialPort { List<Stock> stocks(Collection<String>); Optional<String> giveTarget(List<ItemCount>); boolean apply(List<Move>); void persist(long tx); long durableTx(); static String chest(IntPos); static Optional<IntPos> chestPos(String); }`、`record Stock`・`record Move`・`record OpKey(String jobId, int ledgerKey, MaterialOp op)`・`Stocks`、`sealed interface WalEntry {RunStart, PlaceIntent, MaterialIntent, DropIntent, RunEnd, DurablePoint}`、`WriteAheadLog`(`Sink{write, readAll, dropUpTo}`・`Barrier`・`open`・`lastRunIn`・`durablePointIn`・`newRun`・`append`・`flush`・`durable()`・`durableUpTo()`・`markDurable(long)`)、`WalRecovery.recover(String jobId, List<WalEntry>, JobProgram, Journal, PlacedRegistry, LedgerBook, WorldPort, long savedTx, Resolution) → Result(ambiguous, reasons, adopted, lostMoves, rewindTo, explained, possiblyLostDrops)`・`pendingPart`、`ConstructionExecutor.superseded(...)`、`PauseReason.NO_ROOM`(Task 9の差分)、`JobService.checkpointWanted()`・`markDurable()`・`recoverAll(JobWorld)`・`recover(..., JobWorld)`・`awaitsAnswer(String)`、`enum RecoveryChoice {ADOPT, DISCARD, REPAIR, FAIL}`、`FileWalSink(Path dir[, Barrier])`、`record ExecutionContext(..., boolean skipSiteChanges, WriteAheadLog wal)`
- cc `ConstructionExecutor.run(ExecutionContext, int cursor, int allowance) → StepReport`、`record StepReport(int cursor, PauseReason pause, List<IntPos> touched, List<ItemCount> shortage, List<Conflict> conflicts)`
- build.verify `SnapshotDiff.compare(PlacementManifest, SparseSnapshot, CompareScope, Function<String,Set<String>> volatileOfNode) → SnapshotDiff.Result(List<Deviation> deviations, List<Integer> unread)`、`RepairPlanner.plan(...) → RepairPlan(List<Integer> reapply, List<Conflict> conflicts, List<Deviation> unfixable)`、`SnapshotDiff.statesMatch(VerifyMode, BlockSpec, BlockSpec, Set<String>) → boolean`
- cc `ClaimBook(int maxPerOwner)`: `check(UUID, String dim, Box operatingBox, String exceptClaimId) → List<Issue>`、`checkOwned(String claimId, UUID owner, String dimension) → List<Issue>`、`grow(String claimId, Box worldBox, Box operatingBox) → SiteClaim`、`reserve(...) → SiteClaim`、`release(String)`
- cc `ApprovalDesk`: `offer(...) → PendingApproval`、`approve(ApprovalRequest, Approver, String registryVersion, SurveyCache, long now, Supplier<String> newJobId) → ApprovalDecision`
- cc `JobService`: `admitApproved(...)`、`add(JobRecord)`・`addBroken(...)`、`tick(TickInput, JobWorld) → List<JobUpdate>`、`cancel`・`resume`(Task 13)・`beginVerify`(Task 21)・`recover`(Task 24)・`rollback`(Task 28) → `ControlResult`、`status(String) → Optional<JobStatus>`
- cc `record PlanSubmission(SemanticPlan plan, TemplateBundle templates, JobKind kind, String parentJobId, String claimId)`、`record ApprovalRequest(String manifestHash, String dimension, UUID playerUuid, List<AcceptedRisk> acceptedRisks, Confirmations confirmations)`、`record SubmitOutcome(...)`(Task 16)
- cc `ConstructionExecutor.ownedFor(ExecutionContext, PutItem) → Optional<BlockSpec>`(修復のラウンドの手順だけが登録簿の記録を自分の物として渡す)・`assembledAway(ExecutionContext, Placement)`(組み立て済みの`ASSEMBLED_AWAY`は二度と書かない)、`SkippedPlacement.ALREADY_THERE`、`JobRecord.beforeJournal()`・`useBeforesFrom(Journal)`、`JobService.admitApproved(..., List<Conflict> knownConflicts)`、`JobWorld.mayRunWithoutOwner(ConstructionJob)`
- cc `record ModifyPlan(JobProgram program, int removals, int additions, int changes, Map<IntPos,BlockSpec> notOurs)`、`ModifyPlanner.notOursConflicts(ModifyPlan, Function<IntPos,ObservedBlock>)`、`record HeldContents(int items, long fluidMillibuckets, boolean burningFuel, Optional<IntPos> sharedWith)`、`RemovalPreview.losesSomething()`・`check(Confirmations) → Optional<ApprovalRejection>`
- cc `NioFileSystem.PREVIOUS_SUFFIX = ".prev"`(2世代)・`NioFileSystem(Path, BeforeMove, boolean atomicMove, Syncer)`、`JournalCodec.recordTree`・`recordFromTree`、`LedgerCodec`(量と`appliedRun`)、`WalCodec.frame`・`readFrames → Frames(entries, validLength)`、`FileWalSink implements WriteAheadLog.Sink`、`ProgramCodec.repairToTree`・`repairFromTree`
- cc `record MessageKey(String key, List<String> args)`、`ChildMessages.issueLine(Issue) → MessageKey`(コードを含まない)・`partial(int)`、`PlanFileOpener.check`・`read`(リンクとジャンクションを拒否)、build.compile `PalettePolicy.from(Optional<Set<String>>)`(空のタグ=空の許可、無いタグ=組み込みの表)
- cc `PendingQueries.accept(UploadChunk)`(分けて届く答えの組み立て)、`MsptStats.sample(double tickMs, boolean active, double workMs)`・`maxIncrease()`・`activeTicks()`
- 新しい`IssueCode`: `E-CLAIM-OVERLAP`・`E-CLAIM-LIMIT`・`E-CLAIM-INVALID`(Task 11)、`E-REPLACE-UNCONFIRMED`(Task 12)、`E-PERMISSION-DENIED`(Task 26)。どれも受け入れ不可(`E-`で、`acceptable()`の規則どおり)。

## 完了条件 × タスク(空欄が出たらタスクを足す。削らない)

| 完了条件(設計図07 P4) | 純Javaのテスト | 実機の自動確認(台本のシナリオ) | ペルソナ |
|---|---|---|---|
| 1. 手書きの計画から小屋がドローン演出つきで建つ | Task 3〜13、17(`SampleHutTest`)、20(`DroneChoreographerTest`) | Task 19 `hut-golden`・`hut-here`、Task 20 `drone-show` | Task 36(演出の画面の視覚判定) |
| 2. 状態まで検査(向きの違う階段・扉)。壊す・向きを変える→修復で`VERIFIED`。直せない→`PARTIAL`と理由 | Task 10(`EXACT`は状態を全部、`STATE_SUBSET`は書いた状態だけ)、13(修復の途中で止まってもラウンドを使わない)、21(`REPAIR`は自分の`Journal`・修復のラウンドだけが同じIDの自分のブロックを置き直す)、9(`aFirstRoundStepNeverTakesTheProjectsBlockInAnotherStateAsItsOwn`) | Task 21 `l7-repair`・`l7-partial` | Task 36(`PARTIAL`の理由の文言) |
| 3. `MODIFY`: 差分どおりに直り、手で置き換えた位置は`Conflict`として触らずに報告 | Task 29(`ModifyPlannerTest.aChangedPositionTheProjectDidNotPlaceIsNeitherUndoneNorBuiltOver`、`JobServiceModifyTest`の3つ: プレイヤーの物は`PARTIAL`、記録に無い位置、状態を変えられた自分のブロック)、9(`ownedFor`)、13(`knownConflicts`) | Task 29 `modify-conflict` | Task 36 |
| 4. 再起動で`PAUSED`から自動再開(10秒以内)。取消。ロールバック。記録ファイル欠落で「復旧待ち」 | Task 9(`CrashRecoveryTest`・`WalRecoveryTest`)、13、22〜24(`JobServiceWalRecoveryTest`: 世界が記録より進んだ・遅れた状態、なりすまし、終わったジョブの失われた書き込み、修復の途中の再起動)、28 | Task 25 `restart-resume`・`missing-journal`・`crash-barriers`(規約のすべての境目×`BUILD`・`REPAIR`・`ROLLBACK`で本当の強制終了)・`real-crash`、Task 28 `cancel`・`rollback`、Task 31 `offline-chunk` | Task 36(復旧待ちの文言) |
| 5. 偽の施工リストの承認を拒否。サーバーが作り直す。別ディメンションからの承認を拒否 | Task 12、17、32 | Task 19 `hut-golden`(サーバーの作り直しが金のハッシュ)、Task 32 `fake-approval`・`dimension-bound` | — |
| 6. 専用サーバー+クライアント2つで、他人が取消・承認できない。他人の区画への施工を拒否 | Task 11、13、26 | Task 34 `dedicated-two-clients`(**`NOT-RUN`は合格にしない**。各JVMは`-Xmx3G`で、空きメモリの線はそこから求める)、Task 26 `permissions-single`(1つのクライアントでの論理。代わりの根拠にはしない) | — |
| 7. 材料: クリエイティブは消費なし、サバイバルは消費・不足で停止・補給で再開、取消で返さない、ロールバックは撤去分だけ返す、再起動で二重に消費しない | Task 6、8、9、24、27、28(Task 9の`CrashRecoveryTest`: すべての書き込みの間で落としても、二重に取らず二重に返さない)、23(`LedgerCodec`の量と`appliedRun`)、25(`FileWalSinkTest`) | Task 27 `survival-materials`、Task 28 `rollback-return`、Task 27 `no-room`(入らない返却は止まって待ち、落とさない)、Task 25 `crash-barriers`(サバイバルの組) | Task 36(材料不足の文言) |
| 8. 安全枠: 上限超過・置換不可(ブロックエンティティを含む)が承認前に`Issue` | Task 4、6、30 | Task 21 `safety-limits`、Task 30 `block-entities` | Task 36(`E-SITE-BLOCKED`の文言) |
| 9. 30KB超の計画が分割して送られ、ハッシュ検証を通る | Task 32 | Task 32 `payload-30kb`・`s6-payload-limits` | — |
| 10. 性能: 20,000設置で平均MSPTの増加5ms以内(最大15ms)。重い計算はワーカー。4ジョブ同時で45ms以内、超えると自動減速と`PAUSED(SERVER_BUSY)`表示 | Task 7、12、13(`aSlowedRunningJobIsShownAsServerBusy`)、33(`MsptStatsTest`: 1tickごとの長さで、1回の重いtickが最大の増加に出る) | Task 33 `perf-20000`・`four-jobs`(施工の仕事は`ServerTickEvent.Pre`=MSPTの窓の中で行い、ランタイム自身の時間も`MsptStats`に記録。Task 16。重さの道具`/spike/load`も`Pre`) | — |
| 11. 既存の畑ドローン機能が動く(農場のスクリプトを1本)。`get_block_snapshot`のテストが緑 | Task 37(全テスト) | Task 37 `farm-regression` | — |
| 12. 整地: 傾斜地で切り・盛りが個数つきで出て、確認後に実行。サバイバルで資源が消えも増えもしない。確認なしは拒否 | Task 5、6、12、27 | Task 21 `terrain-slope`、Task 27 `terrain-survival` | Task 36 |
| 13. ブロックエンティティ: 置いた保管庫・デポを撤去でき、中身はドロップ。元からあるチェストは置換も撤去もされない | Task 15(中身はアイテムの能力で取り出す。Createのクラスをimportしない)、28、29、30(`RemovalPreviewTest`)、30(`ContainerRemovalTest`: ブロックエンティティを持つ偽の世界で、置いた樽は中身を落として撤去・プレイヤーのチェストは触らない。`RemovalPreviewTest`: つながった保管庫の中身は1回だけ数える・液体1,000mBは確認が要る) | Task 30 `block-entities`(devkitの開発専用の注入でCreateの`item_vault`・`depot`とバニラの樽を置き、中身を入れて撤去→落ちたアイテムの数) | — |
| 14. 区画の存続: ジョブの後も区画を保持し他人の施工を拒否。`journal`は解放まで残る | Task 11、24、28 | Task 26 `permissions-single`(区画)、Task 28 `rollback`(解放) | — |
| 15. L7の保守性: `volatileProps`を戻さない。別の種類のブロックは上書きせず`Conflict` | Task 10 | Task 21 `l7-repair`(開けた扉はそのまま・金のブロックは上書きしない) | — |
| 16. 調査の固定: 調査から承認までに地形が変わってもハッシュ不一致にならず、置けなくなった位置だけが`Conflict`・`PAUSED`(`E-SITE-CHANGED`) | Task 5、9、12、13(`lastError`が`E-SITE-CHANGED`のID、スキップなしの再開で報告が消える) | Task 21 `survey-pinned` | Task 36 |

**横断基盤・決定事項 × タスク**(P4の担当分。空欄なし):

| 項目 | タスク |
|---|---|
| F-1 サーバー権威・スレッド | Task 12(ワーカー)、15(メインスレッドの世界)、16(ランタイム) |
| F-2 永続化・予算 | Task 7(予算)、9(先行書き込みログ・`WalRecovery`・控えめな復旧)、13(ジョブは確定点の後に終わる)、22(強制・2世代)〜25(保存・起動時の復旧と確定点・孤児の掃除・自動再開・`FileWalSink`のセグメント・境目での本当の強制終了) |
| F-3 承認と改ざん防止 | Task 5(調査の固定)、12(承認・同梱テンプレートのIDとハッシュの照合`aBundledTemplateMustMatchTheServersOwnCopyByIdAndHash`)、32(分割ペイロード・受け入れたリスク) |
| F-4 権限・所有者 | Task 11(区画)、13(所有者/OPの操作)、26(建築権限)、35(既存の権限の穴のIssue起票) |
| F-5 安全枠 | Task 4、5、6、7(所有者ごと1ジョブ)、16(OPの緩和の設定`safety.opMax*`。検査のテストは無い)、30、31 |
| F-7 材料 | Task 6、8、9、27、28 |
| F-8 問い合わせ経路 | Task 32(クライアント側`PendingQueriesTest`(分けて届く答えの組み立て・重複・形・ハッシュ・期限)、サーバー側`QueryArgsTest`) |
| F-13 チャンクとオフライン | Task 13・31(`JobServiceOfflineTest`: 設定とアダプタの`mayRunWithoutOwner`の両方が要る)、9・24(復旧はログの位置が読み込まれるまで待つ) |
| F-14 取消とロールバック | Task 13(取消)、28(ロールバック) |
| F-15 設定 | P7の担当(設計`07`)。P4はTask 16で設定の器(`ConstructionConfig`)を作るだけで、設定そのものの検査のテストは持たない |
| F-16 マルチプレイ | Task 34 |
| F-17 ログと診断 | Task 34 |
| F-19 ドキュメント | Task 35 |
| F-20 既存機能との共存 | 全タスクの回帰ステップ、Task 37 |
| F-21 性能・メモリ | Task 7、10(`SnapshotCollector`)、15(`VoxelClassGrid`の範囲読み取り)、33 |
| F-22 セキュリティ | Task 17(パスの検査)、32(受信の検査) |
| F-23(P4の分) | Task 34(`jobId`で引ける状態の問い合わせ。`BuildProject.jobIds`がP7で使う) |
| D-1 | Task 13・16(世界を変えるのは`JobService`の経路だけ)、Task 37(コンパイルされたクラスの定数プールでの構造の検査) |
| D-3・D-6 | Task 12、32 |
| D-4 | Task 12(ゲームモードで方針)、27 |
| D-11 | Task 16・20(`PacedActionQueue`を使わない) |
| D-12 | Task 13、26 |
| D-14 | Task 29 |
| D-21 | Task 16(施工リストを正本として保存) |
| D-22 | Task 6(安全枠は常に禁止だけ)、12(許可リストはサーバーのコンパイルで)、15(タグ`micradrone:palette_allowed`・`PalettePolicyTest`: 空のタグは空の許可) |
| D-23 | Task 11、24、28 |
| D-24 | Task 11(`operatingBox`に発着場の上空を含める。作用範囲`EffectSpec`の検査はP10・P13) |
| D-25 | Task 4(所有は記録+同じ種類)、8、9、28(撤去しきれなければ区画を残す)、29(記録に無い位置・状態を変えられた位置は触らない)、30(`ContainerRemovalTest`) |
| D-26 | P8(P4の範囲外。`07` 5節の表どおり) |
| D-27 | Task 5(調査のディメンション)、12、32 |
| L7 | Task 10、13、21 |
| N-26・N-27・N-29 | Task 13・16、20、15 |
| S-6・S-9 | Task 1・32、Task 2・26 |

---
### Task 1: スパイクS-6(カスタムペイロードの実際の上限)

**担当: コントローラ(Claude)**。調査文書と設計図なので、Devinには渡さない。

**事実の確定(ソースを読んだ。2026-09-30、計画書の作成時に実施済み)** と、**実機での自動測定の手順**(測定の実行はTask 32。devkitの測定用ペイロードを使うので、ゲームを起動できる段階まで待つ)を、調査文書に書く。コードは書かない。

**Files:**
- Create: `docs/investigations/spk_s6_payload_limits.md`
- Modify: `docs/design/nl_factory_builder/04_foundations.md`(F-3の「ペイロードの大きさ」の段落)、`docs/design/nl_factory_builder/07_phases_and_verification.md`(3節のS-6の行)

**Interfaces:** なし(Task 32が`SubmitPlanPayload.CHUNK_MAX_BYTES`などの定数の根拠として参照する)。

- [ ] **Step 1: 調査文書を書く**

`docs/investigations/spk_s6_payload_limits.md`に、次をそのまま書く(出典はすべて`build/moddev/artifacts/neoforge-21.1.238-sources.jar`の中のファイル):

1. **確認済み(ソース)**:
   - `net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket.java`: `private static final int MAX_PAYLOAD_SIZE = 32767;`。ただし使われるのは`DiscardedPayload.codec(id, 32767)`(**登録されていない**ペイロードを読み捨てるとき)だけ。登録済みのペイロードの大きさは、このコーデックでは制限されない。
   - `net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java`: `MAX_PAYLOAD_SIZE = 1048576`(同じく読み捨て用)。
   - `net/neoforged/neoforge/network/filters/GenericPacketSplitter.java`: エンコード後のパケットが上限を超えると`SplitPacketPayload`に分割して送る。上限は`CompressionDecoder.MAXIMUM_COMPRESSED_LENGTH = 2097152`(**圧縮の処理が無い接続**。シングルプレイのメモリ内接続のほか、`network-compression-threshold=-1`のTCPも当たる)と`MAXIMUM_UNCOMPRESSED_LENGTH = 8388608`(圧縮器がある接続)。分割用のチャンネルは`.optional()`で登録され、相手がそのチャンネルを持つときだけ使う(`isRemoteCompatible`)。
   - `net/minecraft/network/FriendlyByteBuf.java`: `MAX_STRING_LENGTH = 32767`(文字数)。`ByteBufCodecs.STRING_UTF8`で文字列を送ると、この文字数を超えた時点で読み取りが失敗する。**だから、計画の本文は文字列ではなく`ByteBufCodecs.byteArray(上限)`で送る**。
2. **設計の理解との違い**: 設計(`04` F-3)は「サーバー宛ての通常のカスタムペイロードは約32KBが上限と理解している」と書いていた。ソース上は、32,767は**登録されていない**ペイロードの読み捨て用の値で、登録済みのペイロードはNeoForgeが分割して送る。ただし、相手が分割チャンネルを持たない場合や、1つのパケットの上限(2MiB/8MiB)の実際の振る舞いは、実機で測るまで確定しない。
3. **P4の決定(測定の前でも崩れない側)**: 計画は**30KB(`30 * 1024`バイト)以下のチャンク**に分けて送る(設計どおり)。30KBは、どの上限(32,767・2MiB・8MiB)よりも小さいので、測定の結果がどうであっても動く。全体の上限は2MB(`2 * 1024 * 1024`)で、チャンクに分けるので単一パケットの上限には触れない。**測定の結果で変わりうるのはチャンクの大きさの最適値だけで、正しさは変わらない**。
4. **自動測定の手順(Task 32のシナリオ`s6-payload-limits`で実行)**: devkitの測定用ペイロード`micradrone_devkit:probe`(Task 18。`ByteBufCodecs.byteArray(16 * 1024 * 1024)`。micradrone本体の検査を通らない)を、devkitのクライアントAPI`POST /spike/send-probe {"bytes": N}`で送り、サーバー側API`POST /spike/probe-log`で受け取った大きさを読む。Nは16,384・30,720・32,767・32,768・65,536・1,048,576・2,097,151・2,097,152・2,097,153・8,388,608。**予想**: 圧縮のあるTCPで、圧縮の効かない中身を2〜8MiB送ると、分割の上限(非圧縮の8MiB)より先に、枠の上限2,097,151バイト(`Varint21LengthFieldPrepender.java` 18〜22行・`Varint21FrameDecoder.java` 15〜42行)に当たって切断される見込み。台本は切断を「失敗」ではなく測定の結果として記録する(P4レビューD-5)。(a)シングルプレイ(メモリ内接続)と(b)専用サーバー(TCP)の両方で行い、「受信できた/切断された/ログの例外」を`run-evidence/p4/<runId>/s6-payload-limits.json`に記録し、この文書の5節に追記する。切断が起きたら、台本はクライアントを再接続させて次の大きさへ進む(ループしない。3回続けて接続できなければ打ち切りを記録)。
5. **結果**(Task 32で追記。今は「未測定」と書いておく)。

- [ ] **Step 2: 設計図を直す**

`04_foundations.md` F-3の「**ペイロードの大きさ**: サーバー宛ての通常のカスタムペイロードは、バニラの実装で約32KBが上限と理解している(**未確認、S-6で実測**)。」を、「**ペイロードの大きさ**: バニラの`ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE=32767`は登録されていないペイロードの読み捨て用の値で、登録済みのペイロードはNeoForgeの`GenericPacketSplitter`が分割する(ソースで確認。`docs/investigations/spk_s6_payload_limits.md`)。実際の上限はS-6の自動測定(P4 Task 32)で記録する。文字列の上限(`FriendlyByteBuf.MAX_STRING_LENGTH=32767`文字)を避けるため、本文はバイト列で送る。」に置き換える(`python`で、唯一一致を確かめてから置換)。`07`のS-6の行の「方法」に「ソースで確認済み(上の文書)。実機の測定はdevkitの測定用ペイロードで自動(P4 Task 32)」を足す。

- [ ] **Step 3: コミット**

```bash
git add docs/investigations/spk_s6_payload_limits.md docs/design/nl_factory_builder/04_foundations.md docs/design/nl_factory_builder/07_phases_and_verification.md
git commit -m "$(cat <<'MSG'
docs: スパイクS-6(ペイロードの上限)のソース調査と自動測定の手順を記録(自然言語→工場建設 P4 Task 1)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

**このスパイクに依存するタスクと、測定までの扱い**: Task 32(`SubmitPlanPayload.CHUNK_MAX_BYTES = 30 * 1024`、`MAX_UPLOAD_BYTES = 2 * 1024 * 1024`)。測定の前は設計どおりの30KB・2MBで作る(上の3の理由で正しさは変わらない)。測定で30KBより大きなチャンクが安全と分かっても、P4では値を変えない(変える場合は設計の変更として林さんに報告する)。

---

### Task 2: スパイクS-9(保護modの設置イベントとの互換、`FakePlayer`)

**担当: コントローラ(Claude)**。

**Files:**
- Create: `docs/investigations/spk_s9_place_event.md`
- Modify: `docs/design/nl_factory_builder/04_foundations.md`(F-4の「建築権限の確認」の(c))、`docs/design/nl_factory_builder/07_phases_and_verification.md`(S-9の行)

**Interfaces:** なし(Task 15の`PlacementGuard`とTask 26の`PlacementRules`が、この文書の手順に従う)。

- [ ] **Step 1: 調査文書を書く**

`docs/investigations/spk_s9_place_event.md`に、次をそのまま書く(出典は同じsources.jar):

1. **確認済み(ソース)**:
   - `net/neoforged/neoforge/event/EventHooks.java` `onBlockPlace(@Nullable Entity entity, BlockSnapshot blockSnapshot, Direction direction)`: 置いた面の反対側のブロック(`placedAgainst`)を読み、`new BlockEvent.EntityPlaceEvent(blockSnapshot, placedAgainst, entity)`を`NeoForge.EVENT_BUS`に投げ、`isCanceled()`を返す。
   - `net/neoforged/neoforge/event/level/BlockEvent.java` `EntityPlaceEvent(BlockSnapshot, BlockState placedAgainst, @Nullable Entity)`: 親の`BlockEvent`に渡す状態は`!(entity instanceof Player) ? blockSnapshot.getState() : blockSnapshot.getCurrentState()`。**主体がプレイヤー(`FakePlayer`を含む)のときは、世界の今の状態(=既に置いたブロック)を読む**。つまり「先に`setBlock`し、イベントで決める」順で呼ぶ前提。
   - `net/neoforged/neoforge/common/CommonHooks.java` `onPlaceItemIntoWorld`(594〜671行): バニラの`BlockItem`の設置は、`level.captureBlockSnapshots = true`の間に`setBlock`する。捕まえている間は`onPlace`も隣への通知も起きない(`Level.java` 244〜261行、`LevelChunk.java` 282行)。イベントがキャンセルなら、捕まえたスナップショットを逆順に`restoringBlockSnapshots = true`で戻し、通れば`onPlace`と`markAndNotifyBlock`を呼ぶ。**P4の設置もこの形にする**(捕まえずに`setBlock(..., 3)`→イベント→`restore()`の順にすると、`onPlace`・隣の更新・ブロックエンティティの副作用が、キャンセルの前に起きて元に戻らない。P4レビューD-3)。
   - `net/neoforged/neoforge/common/util/BlockSnapshot.java`: `create(ResourceKey<Level> dim, LevelAccessor level, BlockPos pos)`・`restore()`・`getState()`・`getCurrentState()`。
   - `net/neoforged/neoforge/common/util/FakePlayerFactory.java`: `get(ServerLevel, GameProfile)`・`getMinecraft(ServerLevel)`。
   - `BlockEvent.BreakEvent(Level, BlockPos, BlockState, Player)`はキャンセル可能(撤去・ロールバックで使う)。
   - `net/minecraft/server/MinecraftServer.java` `isUnderSpawnProtection(ServerLevel, BlockPos, Player)`: 基底クラスは常に`false`。専用サーバーの実装(`DedicatedServer.java` 397〜413行)は、オーバーワールド以外・OPの一覧が空・OPのプロフィールなら`false`、それ以外はスポーンからの距離で判定する(ソースで読めるので測らない。P4レビューD-7)。
2. **P4の決定(測定の前の既定)**: 設置は「スナップショットを捕まえながら`setBlock`→`EventHooks.onBlockPlace`(2個以上なら`onMultiBlockPlace`)(主体, `Direction.UP`)→キャンセルなら捕まえた物を逆順に戻して`DENIED`、通れば`onPlace`と隣の更新」の順(上の`CommonHooks`と同じ形。Task 15)。主体は、所有者がオンラインならその`ServerPlayer`、オフラインなら`FakePlayerFactory.get(level, new GameProfile(ownerUuid, ownerName))`(所有者のUUIDを持つ偽のプレイヤー。保護modが所有者で判定できる)。撤去(ロールバック・`MODIFY`)は`BreakEvent`を同じ主体で投げる。
3. **自動測定の手順(Task 26の`s9-protection-online`・Task 31の`s9-protection-offline`・Task 28の`s9-protection-rollback`で実行)**: devkitのサーバー側の測定用リスナー`DevkitProtectBox`(Task 18)を`POST /spike/protect-box {"box":[x1,y1,z1,x2,y2,z2], "cancelPlace":true, "cancelBreak":true}`で有効にし、そのリスナーが記録した「イベントの型・主体のクラス名・`instanceof FakePlayer`・イベントの`getPlacedBlock()`の状態・その時点の世界の状態」を`POST /spike/protect-log`で読む。(a)所有者オンラインで小屋を建て、箱の中の位置が`DENIED`で世界に残らないこと、(b)所有者がいない状態(devkitのサーバーAPIでOPの代理として再開)で`FakePlayer`が主体になり同じく`DENIED`になること、(c)ロールバックの`BreakEvent`がキャンセルされた位置が残ること、を確かめ、`run-evidence/p4/<runId>/s9-protection.json`に記録し、この文書の4節に追記する。
4. **結果**(Task 26で追記。今は「未測定」と書いておく)。

- [ ] **Step 2: 設計図を直す**

`04` F-4の(c)の後に「(S-9の確認済みの事実: プレイヤー主体の設置イベントは既に置いた状態を読むので、バニラの`BlockItem`と同じく、スナップショットを捕まえながら置いてからイベントを投げ、キャンセルなら捕まえた物を戻す。オフラインの所有者は`FakePlayerFactory.get`で所有者のUUIDを持つ偽のプレイヤーを主体にする。`docs/investigations/spk_s9_place_event.md`)」を足す。`07`のS-9の行の「方法」に「ソースで確認済み。測定はdevkitの測定用リスナーで自動(P4 Task 26)」を足す。

- [ ] **Step 3: コミット**

```bash
git add docs/investigations/spk_s9_place_event.md docs/design/nl_factory_builder/04_foundations.md docs/design/nl_factory_builder/07_phases_and_verification.md
git commit -m "$(cat <<'MSG'
docs: スパイクS-9(設置イベントとFakePlayer)のソース調査と自動測定の手順を記録(自然言語→工場建設 P4 Task 2)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

**このスパイクに依存するタスクと、測定までの扱い**: Task 15(`PlacementGuard`の順序と主体)、Task 26(`PlacementRules`と権限の確認)。測定の前は上の2の既定で作る。測定で、保護のリスナーが`FakePlayer`を見分けない等の違いが出たら、Task 26の中で`PlacementGuard`の主体の選び方を直し(テストを先に)、この文書と`04` F-4に書く。

---

### Task 3: `construction.core`の新設 — ジョブの型と状態機械、純粋さの検査、設計図の配置表

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。設計図の追記はコントローラ(Claude)が先に書き(コミットしない)、Devinが自分のコミットに`git add`で含める(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobState,PauseReason,JobKind,MaterialPolicy,JobEvent,JobStateMachine,ConstructionJob}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{JobStateMachineTest,ConstructionJobTest}.java`
- Modify: `src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java`、`docs/design/nl_factory_builder/01_data_model.md`(8節・12節)

**Interfaces:**
- Consumes: なし(`java.*`のみ)
- Produces: 型の辞書のとおり。`ConstructionJob.ID_PATTERN = Pattern.compile("[a-z0-9-]{1,64}")`(IDはファイルのパスに使うので形式を検査する。F-22)、`JOBS_DIR = "jobs/"`、`JOURNAL_FILE = "journal.bin"`、`LEDGER_FILE = "ledger.bin"`、`MAX_REPAIR_ROUNDS = 3`(`03` L7の上限)。

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/io/github/khayashi4337/micradrone/construction/core/JobStateMachineTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobStateMachineTest {
    private record T(JobState from, JobEvent event, JobState to) {
    }

    private static final int LISTED = 34;
    private static final List<JobState> PAUSABLE = List.of(JobState.QUEUED, JobState.RUNNING, JobState.ASSEMBLING,
            JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> LIVE = List.of(JobState.PENDING_APPROVAL, JobState.QUEUED, JobState.RUNNING,
            JobState.PAUSED, JobState.ASSEMBLING, JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> ROLLBACKABLE = List.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);

    private static Set<T> expected() {
        Set<T> out = new HashSet<>(List.of(
                new T(JobState.PENDING_APPROVAL, JobEvent.ADMITTED, JobState.QUEUED),
                new T(JobState.PENDING_APPROVAL, JobEvent.CLAIM_REFUSED, JobState.CANCELLED),
                new T(JobState.QUEUED, JobEvent.START, JobState.RUNNING),
                new T(JobState.RUNNING, JobEvent.PLACED_ALL, JobState.VERIFYING),
                new T(JobState.RUNNING, JobEvent.NEED_ASSEMBLY, JobState.ASSEMBLING),
                new T(JobState.ASSEMBLING, JobEvent.ASSEMBLED, JobState.VERIFYING),
                new T(JobState.VERIFYING, JobEvent.CLEAN, JobState.VERIFIED),
                new T(JobState.VERIFYING, JobEvent.NEED_REPAIR, JobState.REPAIRING),
                new T(JobState.VERIFYING, JobEvent.GIVE_UP, JobState.PARTIAL),
                new T(JobState.REPAIRING, JobEvent.REPAIRED, JobState.VERIFYING),
                new T(JobState.PAUSED, JobEvent.RESUME, JobState.QUEUED)));
        for (JobState s : PAUSABLE) {
            out.add(new T(s, JobEvent.PAUSE, JobState.PAUSED));
        }
        for (JobState s : LIVE) {
            out.add(new T(s, JobEvent.CANCEL, JobState.CANCELLED));
            out.add(new T(s, JobEvent.FAIL, JobState.FAILED));
        }
        for (JobState s : ROLLBACKABLE) {
            out.add(new T(s, JobEvent.ROLL_BACK_DONE, JobState.ROLLED_BACK));
        }
        return out;
    }

    @Test
    void everyListedTransitionIsAllowedAndLandsWhereTheDesignSays() {
        Set<T> table = expected();
        assertEquals(LISTED, table.size(), "11 named moves + 5 pauses + 7 cancels + 7 failures + 4 rollbacks");
        for (T t : table) {
            assertTrue(JobStateMachine.allows(t.from(), t.event()), t.toString());
            assertEquals(t.to(), JobStateMachine.next(t.from(), t.event()), t.toString());
        }
    }

    @Test
    void everyOtherPairIsRefusedAsABug() {
        Set<T> table = expected();
        int refused = 0;
        for (JobState s : JobState.values()) {
            for (JobEvent e : JobEvent.values()) {
                boolean listed = table.stream().anyMatch(t -> t.from() == s && t.event() == e);
                if (!listed) {
                    refused++;
                    assertFalse(JobStateMachine.allows(s, e), s + " + " + e);
                    assertThrows(IllegalStateException.class, () -> JobStateMachine.next(s, e), s + " + " + e);
                }
            }
        }
        assertEquals(JobState.values().length * JobEvent.values().length - LISTED, refused);
    }

    @Test
    void terminalStatesAreExactlyTheFiveEndings() {
        Set<JobState> terminal = new HashSet<>();
        for (JobState s : JobState.values()) {
            if (s.terminal()) {
                terminal.add(s);
            }
        }
        assertEquals(Set.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED, JobState.CANCELLED, JobState.ROLLED_BACK),
                terminal);
        assertFalse(JobStateMachine.allows(JobState.ROLLED_BACK, JobEvent.CANCEL), "rolled back is final");
    }
}
```

`ConstructionJobTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstructionJobTest {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static ConstructionJob job() {
        return ConstructionJob.create("job-1", OWNER, "minecraft:overworld", "abc", JobKind.BUILD, null, 238,
                "claim-job-1", MaterialPolicy.CREATIVE_FREE, 100L, List.of("W-UNMODELED:x"));
    }

    @Test
    void createStartsPendingWithFilesUnderTheJobFolder() {
        ConstructionJob j = job();
        assertEquals(JobState.PENDING_APPROVAL, j.state());
        assertNull(j.pauseReason());
        assertEquals(0, j.cursor());
        assertEquals(238, j.total());
        assertEquals("jobs/job-1/journal.bin", j.journalFile());
        assertEquals("jobs/job-1/ledger.bin", j.ledgerFile());
        assertEquals("", j.lastError());
        assertEquals(List.of("W-UNMODELED:x"), j.acceptedRiskIds());
        assertEquals(ConstructionJob.SCHEMA_VERSION, j.schemaVersion());
    }

    @Test
    void eventsGoThroughTheStateMachineAndPauseCarriesItsReason() {
        ConstructionJob j = job().on(JobEvent.ADMITTED).on(JobEvent.START);
        assertEquals(JobState.RUNNING, j.state());
        ConstructionJob p = j.paused(PauseReason.CHUNK_UNLOADED);
        assertEquals(JobState.PAUSED, p.state());
        assertEquals(PauseReason.CHUNK_UNLOADED, p.pauseReason());
        ConstructionJob r = p.on(JobEvent.RESUME);
        assertEquals(JobState.QUEUED, r.state());
        assertNull(r.pauseReason(), "leaving PAUSED clears the reason");
        assertThrows(IllegalArgumentException.class, () -> j.on(JobEvent.PAUSE), "use paused(reason) for PAUSE");
        assertThrows(IllegalStateException.class, () -> job().on(JobEvent.START), "pending jobs cannot start");
    }

    @Test
    void invariantsAreChecked() {
        ConstructionJob j = job();
        assertThrows(IllegalArgumentException.class, () -> j.withCursor(239));
        assertThrows(IllegalArgumentException.class, () -> j.withCursor(-1));
        assertEquals(238, j.withCursor(238).cursor());
        assertThrows(IllegalArgumentException.class, () -> j.withRepairRound(ConstructionJob.MAX_REPAIR_ROUNDS + 1));
        assertThrows(IllegalArgumentException.class, () -> ConstructionJob.create("../evil", OWNER, "minecraft:overworld",
                "abc", JobKind.BUILD, null, 1, "c", MaterialPolicy.CREATIVE_FREE, 0L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConstructionJob(1, "job-2", OWNER, "minecraft:overworld", "abc",
                JobKind.BUILD, null, JobState.PAUSED, null, 0, 1, 0, "c", MaterialPolicy.CREATIVE_FREE,
                "jobs/job-2/journal.bin", "jobs/job-2/ledger.bin", "", 0L, List.of()), "PAUSED needs a reason");
        assertThrows(IllegalArgumentException.class, () -> new ConstructionJob(1, "job-2", OWNER, "minecraft:overworld", "abc",
                JobKind.BUILD, null, JobState.RUNNING, PauseReason.USER, 0, 1, 0, "c", MaterialPolicy.CREATIVE_FREE,
                "jobs/job-2/journal.bin", "jobs/job-2/ledger.bin", "", 0L, List.of()), "only PAUSED has a reason");
    }

    @Test
    void withersKeepEverythingElse() {
        ConstructionJob j = job();
        ConstructionJob k = j.withCursor(5).withRepairRound(2).withLastError("x").withTotal(300).withClaimId("claim-9");
        assertEquals(5, k.cursor());
        assertEquals(2, k.repairRound());
        assertEquals("x", k.lastError());
        assertEquals(300, k.total());
        assertEquals("claim-9", k.claimId());
        assertEquals(j.jobId(), k.jobId());
        assertEquals(j.createdTick(), k.createdTick());
        assertEquals(3, j.withCursor(5).withTotal(3).cursor(), "shrinking the total pulls the cursor in");
    }
}
```

`BuildPurityTest.java`に、次の2つのテストを足す(既存のテストは変えない。`ROOT`・`javaFiles`・`violations`は既存のものを使い、`java.util.regex.Pattern`は既にimport済み):
```java
    @Test
    void theConstructionCoreIsPureAndDoesNotReachIntoAdapters() throws IOException {
        Path core = ROOT.resolve("construction/core");
        assertTrue(Files.isDirectory(core), "construction.core must exist");
        List<String> all = new ArrayList<>();
        Pattern adapter = Pattern.compile("^\\s*import\\s+(static\\s+)?io\\.github\\.khayashi4337\\.micradrone\\."
                + "(construction\\.(?!core\\.)|drone\\.|client\\.)");
        for (Path p : javaFiles(core)) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            all.addAll(violations(p, text));
            int n = 0;
            for (String line : text.split("\n", -1)) {
                n++;
                if (adapter.matcher(line).find()) {
                    all.add(p + ":" + n + ": " + line.trim());
                }
            }
        }
        assertEquals(List.of(), all);
    }

    @Test
    void theBuildPackagesDoNotDependOnConstruction() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : javaFiles(ROOT.resolve("build"))) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains("import io.github.khayashi4337.micradrone.construction.")) {
                all.add(p.toString());
            }
        }
        assertEquals(List.of(), all, "build.* is the lower layer; construction.core builds on it, never the reverse");
    }
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --tests "io.github.khayashi4337.micradrone.build.BuildPurityTest" --console=plain`
Expected: FAIL(`construction.core`の型が無いのでコンパイルエラー)。

- [ ] **Step 3: 実装する**

`JobState.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** Where a construction job stands (design 01, section 8). VERIFIED is the job's state only, not the factory's. */
public enum JobState {
    PENDING_APPROVAL, QUEUED, RUNNING, PAUSED, VERIFYING, REPAIRING, ASSEMBLING, VERIFIED, PARTIAL, FAILED, CANCELLED,
    ROLLED_BACK;

    public boolean terminal() {
        return this == VERIFIED || this == PARTIAL || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }
}
```
`PauseReason.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** Why a job waits. SITE_CHANGED: a position became unplaceable after the survey (F-3, E-SITE-CHANGED). */
public enum PauseReason {
    OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, SERVER_BUSY, RECOVERY_NEEDED, USER, SITE_CHANGED
}
```
`JobKind.java`: `public enum JobKind { BUILD, MODIFY, REPAIR, ROLLBACK }`。`MaterialPolicy.java`: `public enum MaterialPolicy { CREATIVE_FREE, SURVIVAL_CONSUME }`。`JobEvent.java`: 型の辞書の15個を、その順で並べた`enum`(javadoc: `/** What happens to a job; the state machine decides where it leads. */`)。

`JobStateMachine.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The job state machine of design 01, section 8, as one table. A move that is not in the table is a bug in the caller,
 * never a user error, so it throws instead of being ignored.
 */
public final class JobStateMachine {
    private static final Map<JobState, Map<JobEvent, JobState>> TABLE = new EnumMap<>(JobState.class);
    private static final List<JobState> PAUSABLE = List.of(JobState.QUEUED, JobState.RUNNING, JobState.ASSEMBLING,
            JobState.VERIFYING, JobState.REPAIRING);
    private static final List<JobState> LIVE = List.of(JobState.PENDING_APPROVAL, JobState.QUEUED, JobState.RUNNING,
            JobState.PAUSED, JobState.ASSEMBLING, JobState.VERIFYING, JobState.REPAIRING);
    /** The endings a claim's rollback closes: when its ROLLBACK job ends, the claim's other jobs become ROLLED_BACK. */
    private static final List<JobState> ROLLBACKABLE = List.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);

    static {
        for (JobState s : JobState.values()) {
            TABLE.put(s, new EnumMap<>(JobEvent.class));
        }
        allow(JobState.PENDING_APPROVAL, JobEvent.ADMITTED, JobState.QUEUED);
        allow(JobState.PENDING_APPROVAL, JobEvent.CLAIM_REFUSED, JobState.CANCELLED);
        allow(JobState.QUEUED, JobEvent.START, JobState.RUNNING);
        allow(JobState.RUNNING, JobEvent.PLACED_ALL, JobState.VERIFYING);
        allow(JobState.RUNNING, JobEvent.NEED_ASSEMBLY, JobState.ASSEMBLING);
        allow(JobState.ASSEMBLING, JobEvent.ASSEMBLED, JobState.VERIFYING);
        allow(JobState.VERIFYING, JobEvent.CLEAN, JobState.VERIFIED);
        allow(JobState.VERIFYING, JobEvent.NEED_REPAIR, JobState.REPAIRING);
        allow(JobState.VERIFYING, JobEvent.GIVE_UP, JobState.PARTIAL);
        allow(JobState.REPAIRING, JobEvent.REPAIRED, JobState.VERIFYING);
        allow(JobState.PAUSED, JobEvent.RESUME, JobState.QUEUED);
        for (JobState s : PAUSABLE) {
            allow(s, JobEvent.PAUSE, JobState.PAUSED);
        }
        for (JobState s : LIVE) {
            allow(s, JobEvent.CANCEL, JobState.CANCELLED);
            allow(s, JobEvent.FAIL, JobState.FAILED);
        }
        for (JobState s : ROLLBACKABLE) {
            allow(s, JobEvent.ROLL_BACK_DONE, JobState.ROLLED_BACK);
        }
    }

    private JobStateMachine() {
    }

    private static void allow(JobState from, JobEvent event, JobState to) {
        TABLE.get(from).put(event, to);
    }

    public static boolean allows(JobState from, JobEvent event) {
        return TABLE.get(from).containsKey(event);
    }

    public static JobState next(JobState from, JobEvent event) {
        JobState to = TABLE.get(from).get(event);
        if (to == null) {
            throw new IllegalStateException("a job in " + from + " cannot take " + event);
        }
        return to;
    }
}
```

`ConstructionJob.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The small, saved state of one construction job (design 01, section 8). The large parts (manifest, journal, ledger)
 * live in separate files named here (04 F-2). The job id is used in file paths, so its form is checked (F-22).
 */
public record ConstructionJob(int schemaVersion, String jobId, UUID ownerUuid, String dimension, String manifestHash,
                              JobKind kind, String parentJobId, JobState state, PauseReason pauseReason, int cursor, int total,
                              int repairRound, String claimId, MaterialPolicy materialPolicy, String journalFile,
                              String ledgerFile, String lastError, long createdTick, List<String> acceptedRiskIds) {
    public static final int SCHEMA_VERSION = 1;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9-]{1,64}");
    public static final String JOBS_DIR = "jobs/";
    public static final String JOURNAL_FILE = "journal.bin";
    public static final String LEDGER_FILE = "ledger.bin";
    /** L7 gives up after this many repair rounds (design 03, L7). */
    public static final int MAX_REPAIR_ROUNDS = 3;

    public ConstructionJob {
        Objects.requireNonNull(jobId, "jobId");
        if (!ID_PATTERN.matcher(jobId).matches()) {
            throw new IllegalArgumentException("job id must match " + ID_PATTERN + ": " + jobId);
        }
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(materialPolicy, "materialPolicy");
        Objects.requireNonNull(journalFile, "journalFile");
        Objects.requireNonNull(ledgerFile, "ledgerFile");
        if ((state == JobState.PAUSED) != (pauseReason != null)) {
            throw new IllegalArgumentException("a pause reason goes with PAUSED and only with it: " + state + "/" + pauseReason);
        }
        if (total < 0 || cursor < 0 || cursor > total) {
            throw new IllegalArgumentException("cursor " + cursor + " must lie in 0.." + total);
        }
        if (repairRound < 0 || repairRound > MAX_REPAIR_ROUNDS) {
            throw new IllegalArgumentException("repairRound " + repairRound + " must lie in 0.." + MAX_REPAIR_ROUNDS);
        }
        lastError = Objects.requireNonNullElse(lastError, "");
        acceptedRiskIds = List.copyOf(Objects.requireNonNullElse(acceptedRiskIds, List.of()));
    }

    public static ConstructionJob create(String jobId, UUID owner, String dimension, String manifestHash, JobKind kind,
                                         String parentJobId, int total, String claimId, MaterialPolicy policy,
                                         long createdTick, List<String> acceptedRiskIds) {
        String dir = JOBS_DIR + jobId + "/";
        return new ConstructionJob(SCHEMA_VERSION, jobId, owner, dimension, manifestHash, kind, parentJobId,
                JobState.PENDING_APPROVAL, null, 0, total, 0, claimId, policy, dir + JOURNAL_FILE, dir + LEDGER_FILE, "",
                createdTick, acceptedRiskIds);
    }

    /** Any move but PAUSE, which needs a reason ({@link #paused}). Leaving PAUSED clears the reason. */
    public ConstructionJob on(JobEvent event) {
        if (event == JobEvent.PAUSE) {
            throw new IllegalArgumentException("use paused(reason) to pause");
        }
        return copy(JobStateMachine.next(state, event), null, cursor, total, repairRound, claimId, lastError);
    }

    public ConstructionJob paused(PauseReason reason) {
        return copy(JobStateMachine.next(state, JobEvent.PAUSE), Objects.requireNonNull(reason, "reason"), cursor, total,
                repairRound, claimId, lastError);
    }

    public ConstructionJob withCursor(int c) {
        return copy(state, pauseReason, c, total, repairRound, claimId, lastError);
    }

    public ConstructionJob withTotal(int t) {
        return copy(state, pauseReason, Math.min(cursor, t), t, repairRound, claimId, lastError);
    }

    public ConstructionJob withRepairRound(int r) {
        return copy(state, pauseReason, cursor, total, r, claimId, lastError);
    }

    public ConstructionJob withLastError(String e) {
        return copy(state, pauseReason, cursor, total, repairRound, claimId, e);
    }

    public ConstructionJob withClaimId(String c) {
        return copy(state, pauseReason, cursor, total, repairRound, c, lastError);
    }

    private ConstructionJob copy(JobState s, PauseReason reason, int c, int t, int r, String claim, String error) {
        return new ConstructionJob(schemaVersion, jobId, ownerUuid, dimension, manifestHash, kind, parentJobId, s, reason, c, t,
                r, claim, materialPolicy, journalFile, ledgerFile, error, createdTick, acceptedRiskIds);
    }
}
```

- [ ] **Step 4: 設計図の配置表と型を直す(担当: コントローラ。Devinに渡す前に書き、コミットしない。Devinのコミットに含まれる)**(コードより先に設計を正本にする原則。この計画書で確定した内容)

`01_data_model.md`の12節の表の`construction`の行の直前に、次の2行を足す(唯一一致する行の前に挿入):
```
| `construction.core`(P4で新設) | 施工ランタイムの判断のすべて: `ConstructionJob`・状態機械・置換の規則(`ReplaceRules`)・安全枠(`SafetyEnvelope`)・施工予算(`ConstructionBudget`)・記録(`Journal`・`PlacedRegistry`・`MaterialLedger`)・設置の実行(`ConstructionExecutor`)・区画(`ClaimBook`)・承認(`ApprovalDesk`)・ジョブの進行(`JobService`)・保存と復旧・分割ペイロードの組み立て・`ServerWorkerPool`。`build.*`の上の層で、`construction`(アダプタ)・`drone`・`client`をimportしない | なし |
| `build.analyze`(P4で`VoxelClassGrid`だけ先に置く) | 範囲読み取りの分類グリッド。解析器はP6 | なし |
```
`construction`の行の中身を「`construction.core`の型をMinecraftにつなぐ薄いアダプタ: `ServerWorldPort`・`ServerStateReader`・`ServerSurveyor`・`ConstructionRuntime`・`ConstructionJobStore`(`SavedData`)・`PlacementGuard`・`InventoryMaterials`・`DroneShow`・`BuildCommands`・`ConstructionConfig`、サーバー側の`RecipeSource`実装(P7)・`Commissioning`(P11)・`RuntimeMonitor`(P13)」に置き換える。12節の「依存の向き」の段落の末尾に「`construction.core`は`build.*`と`chat.MiniJson`だけを見る。`build.*`は`construction`を見ない(`BuildPurityTest`が検査)。」を足す。8節の`PauseReason`に`SITE_CHANGED /*調査の後で位置が置けなくなった(F-3の E-SITE-CHANGED)*/`を足し、`ConstructionJob`の末尾に`List<String> acceptedRiskIds /*F-3: 受け入れたリスクをジョブに記録*/`を足す。

- [ ] **Step 5: テストが通ることを確かめる(回帰を含む)**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(既存のテストを含む全部)。

- [ ] **Step 6: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java
git add docs/design/nl_factory_builder/01_data_model.md
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: construction.coreを新設し、施工ジョブの型と状態機械を追加(純粋さの検査と設計図の配置表も更新)(自然言語→工場建設 P4 Task 3)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 4: 抽象の世界と置換の規則(`WorldCell`・`ReplaceRules`)、状態の照合(`BlockMatch`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/compile/ObservedBlock.java`(ブロックエンティティの有無を足す。既存の1引数コンストラクタは残す)、`src/main/java/io/github/khayashi4337/micradrone/build/compile/Conflicts.java`(`BlockMatch`を使う。挙動は同じ)
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/BlockMatch.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{CellTrait,WorldCell,WorldPort,PlaceResult,Refusal,Destruction,ReplaceDecision,ReplaceRules}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/BlockMatchTest.java`、`src/test/java/io/github/khayashi4337/micradrone/construction/core/{ReplaceRulesTest,FakeWorld,FakeWorldTest}.java`

**Interfaces:**
- Consumes: `build.compile.Placement`・`ReplacePolicy`(既存。`AirOnly`・`Replaceable`・`Expect`)、`build.model.BlockSpec`・`IntPos`
- Produces: 型の辞書の`ObservedBlock`・`BlockMatch`(`satisfies`=`STATE_SUBSET`の比べ方、`exact`=`EXACT`の比べ方。設計`05` 1.1.1のとおり2つを分ける)・`CellTrait`・`WorldCell`(`static WorldCell unloaded()`・`static WorldCell of(BlockSpec, CellTrait...)`・`static WorldCell withBlockEntity(BlockSpec, String type, CellTrait...)`・`BlockSpec block()`)・`WorldPort`・`PlaceResult`・`Refusal`・`Destruction`(`boolean needsDestructiveConfirm()`)・`ReplaceDecision`・`ReplaceRules`。テスト用の`FakeWorld`(以降のタスクのテストが使う)。`Terraform`の置換方針はTask 5で足し、`ReplaceRules`のswitchにもTask 5で足す。

- [ ] **Step 1: 失敗するテストを書く**

`BlockMatchTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockMatchTest {
    private static final BlockSpec STAIRS = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");

    @Test
    void onlyTheListedPropertiesAreCompared() {
        BlockSpec observed = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom", "shape", "outer_left",
                "waterlogged", "false");
        assertTrue(BlockMatch.satisfies(observed, STAIRS, Set.of()), "extra observed states are not compared");
        assertFalse(BlockMatch.satisfies(observed.with("facing", "east"), STAIRS, Set.of()), "a turned stair is caught");
        assertFalse(BlockMatch.satisfies(BlockSpec.of("minecraft:stone_stairs", "facing", "north", "half", "bottom"), STAIRS,
                Set.of()));
    }

    @Test
    void ignoredPropertiesNeverFail() {
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "open", "false", "facing", "south");
        assertTrue(BlockMatch.satisfies(door.with("open", "true"), door, Set.of("open")));
        assertFalse(BlockMatch.satisfies(door.with("open", "true"), door, Set.of()));
    }

    @Test
    void exactComparesEveryStateAndSubsetOnlyTheListedOnes() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "y");
        assertTrue(BlockMatch.exact(log, log, Set.of()));
        assertFalse(BlockMatch.exact(log.with("extra", "1"), log, Set.of()), "an unlisted observed state fails EXACT");
        assertTrue(BlockMatch.satisfies(log.with("extra", "1"), log, Set.of()), "but not STATE_SUBSET");
        assertTrue(BlockMatch.exact(log.with("open", "true"), log, Set.of("open")), "volatile states are ignored by both");
        assertFalse(BlockMatch.exact(BlockSpec.of("minecraft:stone"), BlockSpec.of("minecraft:stone", "x", "1"), Set.of()));
    }

    @Test
    void observedBlockCarriesTheBlockEntityFlag() {
        ObservedBlock plain = new ObservedBlock(BlockSpec.of("minecraft:stone"));
        assertFalse(plain.hasBlockEntity());
        assertEquals("", plain.blockEntityType());
        ObservedBlock chest = new ObservedBlock(BlockSpec.of("minecraft:chest"), true, "minecraft:chest");
        assertTrue(chest.hasBlockEntity());
        assertThrows(IllegalArgumentException.class, () -> new ObservedBlock(BlockSpec.of("minecraft:stone"), false, "x"));
    }
}
```

`FakeWorld.java`(テスト用の偽の世界。以降のタスクのテストが使う):
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** An in-memory world for the pure core's tests. Unset positions are air. Every write is logged in order. */
public final class FakeWorld implements WorldPort {
    /** Runs after every successful write: lets a test play the player who breaks or swaps a block right after. */
    public BiConsumer<IntPos, BlockSpec> afterWrite = (pos, block) -> {
    };
    private final Map<IntPos, WorldCell> cells = new HashMap<>();
    private final Set<IntPos> unloaded = new HashSet<>();
    private final Set<IntPos> denied = new HashSet<>();
    private final Map<IntPos, Integer> containerItems = new HashMap<>();
    public final List<String> log = new ArrayList<>();

    public FakeWorld set(IntPos pos, WorldCell cell) {
        cells.put(pos, cell);
        return this;
    }

    public FakeWorld setBlock(IntPos pos, BlockSpec block, CellTrait... traits) {
        return set(pos, WorldCell.of(block, traits));
    }

    public void unload(IntPos pos) {
        unloaded.add(pos);
    }

    public void load(IntPos pos) {
        unloaded.remove(pos);
    }

    public void deny(IntPos pos) {
        denied.add(pos);
    }

    public void putItems(IntPos pos, int count) {
        containerItems.put(pos, count);
    }

    public BlockSpec blockAt(IntPos pos) {
        return read(pos).block();
    }

    @Override
    public WorldCell read(IntPos pos) {
        if (unloaded.contains(pos)) {
            return WorldCell.unloaded();
        }
        return cells.getOrDefault(pos, WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
    }

    @Override
    public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
        return write("place", pos, block);
    }

    @Override
    public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
        if (!denied.contains(pos) && !unloaded.contains(pos) && dropContentsFirst) {
            int n = containerItems.getOrDefault(pos, 0);
            containerItems.remove(pos);
            log.add("drop " + pos.x() + "," + pos.y() + "," + pos.z() + " " + n);
        }
        return write("restore", pos, block);
    }

    public int itemsIn(IntPos pos) {
        return containerItems.getOrDefault(pos, 0);
    }

    @Override
    public void settle(List<IntPos> positions) {
        StringBuilder sb = new StringBuilder("settle");
        for (IntPos p : positions) {
            sb.append(' ').append(p.x()).append(',').append(p.y()).append(',').append(p.z());
        }
        log.add(sb.toString());
    }

    private PlaceResult write(String what, IntPos pos, BlockSpec block) {
        if (unloaded.contains(pos)) {
            throw new IllegalStateException("writing into an unloaded chunk at " + pos);
        }
        if (denied.contains(pos)) {
            log.add("denied " + pos.x() + "," + pos.y() + "," + pos.z());
            return PlaceResult.DENIED;
        }
        cells.put(pos, block.isAir() ? WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE) : WorldCell.of(block));
        log.add(what + " " + pos.x() + "," + pos.y() + "," + pos.z() + " " + block);
        afterWrite.accept(pos, block);
        return PlaceResult.PLACED;
    }
}
```

`FakeWorldTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeWorldTest {
    private static final UUID ACTOR = new UUID(0, 1);
    private static final IntPos P = new IntPos(1, 2, 3);

    @Test
    void unsetIsAirAndWritesAreLogged() {
        FakeWorld w = new FakeWorld();
        assertEquals(BlockSpec.AIR, w.blockAt(P));
        assertEquals(PlaceResult.PLACED, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
        assertEquals("minecraft:stone", w.blockAt(P).blockId());
        assertEquals(List.of("place 1,2,3 minecraft:stone"), w.log);
    }

    @Test
    void deniedAndUnloadedBehaveLikeTheRealWorld() {
        FakeWorld w = new FakeWorld();
        w.deny(P);
        assertEquals(PlaceResult.DENIED, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
        assertEquals(BlockSpec.AIR, w.blockAt(P));
        IntPos far = new IntPos(99, 0, 0);
        w.unload(far);
        assertFalse(w.read(far).loaded());
        assertThrows(IllegalStateException.class, () -> w.place(far, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
    }
}
```

`ReplaceRulesTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplaceRulesTest {
    private static final IntPos POS = new IntPos(0, 64, 0);
    private static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");

    static Placement put(BlockSpec block, ReplacePolicy policy) {
        return new Placement(0, POS, block, Map.of(), "wall-n", BuildPhase.STRUCTURE, PlacerId.SIMPLE, VerifyMode.EXACT,
                policy, null);
    }

    private static ReplaceDecision decide(ReplacePolicy policy, WorldCell cell) {
        return ReplaceRules.decide(put(PLANKS, policy), cell, false, false);
    }

    @Test
    void replaceablePlacesOverAirPlantsSnowAndAsksForFluidsAndLeaves() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID), decide(ReplacePolicy.REPLACEABLE,
                WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.REPLACEABLE, CellTrait.FLUID)));
        assertEquals(new ReplaceDecision.Place(Destruction.LEAVES),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES)));
        assertTrue(Destruction.FLUID.needsDestructiveConfirm());
        assertTrue(Destruction.LEAVES.needsDestructiveConfirm());
        assertTrue(Destruction.EMPTY_CONTAINER.needsDestructiveConfirm());
        assertFalse(Destruction.NONE.needsDestructiveConfirm());
        assertFalse(Destruction.TERRAIN.needsDestructiveConfirm(), "terrain has its own confirmation (terraform)");
    }

    @Test
    void solidTerrainAndUnbreakableBlocksAreRefused() {
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE)));
        assertEquals(new ReplaceDecision.Refused(Refusal.UNBREAKABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:bedrock"), CellTrait.UNBREAKABLE)));
    }

    @Test
    void foreignBlockEntitiesAreNeverReplacedExceptAnEmptyContainer() {
        WorldCell fullChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest");
        WorldCell emptyChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest",
                CellTrait.EMPTY_CONTAINER);
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.REPLACEABLE, fullChest));
        assertEquals(new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER), decide(ReplacePolicy.REPLACEABLE, emptyChest));
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.AIR_ONLY, emptyChest),
                "air-only never replaces a container");
    }

    @Test
    void airOnlyAndExpectAreStrict() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:dirt"))));
        assertEquals(new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:stone"))));
    }

    @Test
    void aJournaledPositionThatAlreadyHoldsTheBlockIsDoneAndOurOwnBlocksMayBeOverwritten() {
        WorldCell planks = WorldCell.of(PLANKS);
        assertEquals(new ReplaceDecision.AlreadyDone(),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, true, true));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, false, false),
                "somebody else's planks are not ours to overwrite");
        WorldCell turned = WorldCell.of(BlockSpec.of("minecraft:oak_stairs", "facing", "east"));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                ReplaceRules.decide(put(BlockSpec.of("minecraft:oak_stairs", "facing", "north"), ReplacePolicy.REPLACEABLE),
                        turned, true, true), "the project's own block may be re-placed (repair, modify)");
    }

    @Test
    void anUnloadedCellIsNotDecided() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), WorldCell.unloaded(), false, false));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --tests "io.github.khayashi4337.micradrone.build.compile.BlockMatchTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`ObservedBlock.java`(置き換え):
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/**
 * What the world actually holds at a position: the block with all its states, and whether a block entity sits there
 * (design 01, section 8). The chunk-loaded state is the runtime's concern (construction.core.WorldCell).
 */
public record ObservedBlock(BlockSpec block, boolean hasBlockEntity, String blockEntityType) {
    public ObservedBlock {
        Objects.requireNonNull(block, "block");
        blockEntityType = Objects.requireNonNullElse(blockEntityType, "");
        if (!hasBlockEntity && !blockEntityType.isEmpty()) {
            throw new IllegalArgumentException("a block-entity type without a block entity: " + blockEntityType);
        }
    }

    public ObservedBlock(BlockSpec block) {
        this(block, false, "");
    }
}
```

`BlockMatch.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether an observed block satisfies an expected one: the same block id, and every state the expectation lists (minus
 * the ignored, volatile ones) equal. States the expectation does not list are not compared: the pure core cannot tell a
 * default state from a set one, and neighbour-driven states (stair shape, pane connections) are not the plan's choice.
 */
public final class BlockMatch {
    private BlockMatch() {
    }

    public static boolean satisfies(BlockSpec observed, BlockSpec expected, Set<String> ignoredProps) {
        if (!observed.blockId().equals(expected.blockId())) {
            return false;
        }
        for (Map.Entry<String, String> e : expected.properties().entrySet()) {
            if (!ignoredProps.contains(e.getKey()) && !e.getValue().equals(observed.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * VerifyMode.EXACT (design 05, 1.1.1): the same id and exactly the same states on both sides, the ignored (volatile)
     * ones aside. A state the observation has but the plan did not list fails, unlike {@link #satisfies}.
     */
    public static boolean exact(BlockSpec observed, BlockSpec expected, Set<String> ignoredProps) {
        if (!observed.blockId().equals(expected.blockId())) {
            return false;
        }
        Set<String> keys = new TreeSet<>(observed.properties().keySet());
        keys.addAll(expected.properties().keySet());
        keys.removeAll(ignoredProps);
        for (String k : keys) {
            if (!Objects.equals(observed.get(k), expected.get(k))) {
                return false;
            }
        }
        return true;
    }
}
```
`Conflicts.detect`の、ブロックIDの比較とプロパティのループ(空気の判定の2つの`if`の後ろ全部)を、次の2文に置き換える(挙動は同じ。既存の`ConflictsTest`が緑のままで確かめる):
```java
        if (!BlockMatch.satisfies(actual, expected, volatileProps)) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
        }
        return Optional.empty();
```

`CellTrait.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/**
 * What the adapter observed about a position, beyond the block: vanilla's "can be replaced" (plants, snow layers, air),
 * a fluid, leaves, the micradrone:terraformable tag, unbreakable (destroy speed below zero), and an empty container.
 */
public enum CellTrait {
    REPLACEABLE, FLUID, LEAVES, TERRAFORMABLE, UNBREAKABLE, EMPTY_CONTAINER
}
```
`WorldCell.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** One position as the construction sees it. An unloaded position has no block: it is unknown, never "missing" (N-29). */
public record WorldCell(boolean loaded, ObservedBlock observed, Set<CellTrait> traits) {
    private static final WorldCell UNLOADED = new WorldCell(false, null, Set.of());

    public WorldCell {
        if (loaded) {
            Objects.requireNonNull(observed, "observed");
        } else if (observed != null) {
            throw new IllegalArgumentException("an unloaded position has no observed block");
        }
        traits = traits.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(traits));
    }

    public static WorldCell unloaded() {
        return UNLOADED;
    }

    public static WorldCell of(BlockSpec block, CellTrait... traits) {
        return new WorldCell(true, new ObservedBlock(block), traitSet(traits));
    }

    public static WorldCell withBlockEntity(BlockSpec block, String type, CellTrait... traits) {
        return new WorldCell(true, new ObservedBlock(block, true, type), traitSet(traits));
    }

    private static Set<CellTrait> traitSet(CellTrait... traits) {
        return traits.length == 0 ? Set.of() : EnumSet.of(traits[0], traits);
    }

    public BlockSpec block() {
        if (!loaded) {
            throw new IllegalStateException("an unloaded position has no block");
        }
        return observed.block();
    }
}
```
`WorldPort.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The world as the pure core sees it. The adapter implements it on the server's main thread only (F-1): reads, the
 * placement with the permission event of the actor (S-9), and a restore with the break event that first drops the items
 * of a container the project placed (D-25).
 */
public interface WorldPort {
    WorldCell read(IntPos pos);

    PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor);

    /**
     * Puts {@code block} back (rollback, MODIFY removals) after the actor's break event allows it. With
     * {@code dropContentsFirst}, a container's items are dropped into the world first (D-25); a refused break drops nothing.
     */
    PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst);

    /**
     * Runs the neighbour updates a restore skipped (a restore changes one position without shape updates, so a door's
     * other half or a sign's wall cannot pop off with an item drop mid-way). Called once a piece is fully restored.
     */
    void settle(List<IntPos> positions);
}
```
`PlaceResult.java`: `public enum PlaceResult { PLACED, DENIED, INVALID }`(javadoc: `DENIED`=保護・権限のイベントがキャンセル、`INVALID`=ブロック状態がこの版のゲームで作れない)。`Refusal.java`: 型の辞書の5個の`enum`。`Destruction.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/**
 * What placing a block destroys. Fluids, leaves and an empty container need the user's explicit destructive
 * confirmation (F-5); terrain has its own terraform confirmation (E-TERRAFORM-UNCONFIRMED).
 */
public enum Destruction {
    NONE, FLUID, LEAVES, EMPTY_CONTAINER, TERRAIN;

    public boolean needsDestructiveConfirm() {
        return this == FLUID || this == LEAVES || this == EMPTY_CONTAINER;
    }
}
```
`ReplaceDecision.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** The verdict for one placement against what the world holds now. */
public sealed interface ReplaceDecision {
    record Place(Destruction destruction) implements ReplaceDecision {
        public Place {
            Objects.requireNonNull(destruction, "destruction");
        }
    }

    record AlreadyDone() implements ReplaceDecision {
    }

    record Refused(Refusal refusal) implements ReplaceDecision {
        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }
}
```
`ReplaceRules.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Set;

/**
 * The one set of replacement rules (F-5): the safety envelope uses it on the pinned survey before approval and the
 * executor on the live world just before each placement, so the two can never disagree.
 */
public final class ReplaceRules {
    private ReplaceRules() {
    }

    public static ReplaceDecision decide(Placement p, WorldCell cell, boolean journaled, boolean placedByProject) {
        if (!cell.loaded()) {
            throw new IllegalArgumentException("decide only on loaded positions; an unloaded one pauses the job");
        }
        BlockSpec now = cell.block();
        if (journaled && BlockMatch.satisfies(now, p.block(), Set.of())) {
            return new ReplaceDecision.AlreadyDone();
        }
        if (placedByProject) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.UNBREAKABLE)) {
            return new ReplaceDecision.Refused(Refusal.UNBREAKABLE);
        }
        if (cell.observed().hasBlockEntity()) {
            boolean mayTakeEmptyContainer = !(p.replaces() instanceof ReplacePolicy.AirOnly)
                    && !(p.replaces() instanceof ReplacePolicy.Expect);
            return mayTakeEmptyContainer && cell.traits().contains(CellTrait.EMPTY_CONTAINER)
                    ? new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER)
                    : new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY);
        }
        return switch (p.replaces()) {
            case ReplacePolicy.AirOnly a -> now.isAir() ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE);
            case ReplacePolicy.Expect e -> now.blockId().equals(e.blockId()) ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER);
            case ReplacePolicy.Replaceable r -> natural(cell, now, false);
        };
    }

    static ReplaceDecision natural(WorldCell cell, BlockSpec now, boolean terraform) {
        if (now.isAir()) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.FLUID)) {
            return new ReplaceDecision.Place(Destruction.FLUID);
        }
        if (cell.traits().contains(CellTrait.LEAVES)) {
            return new ReplaceDecision.Place(Destruction.LEAVES);
        }
        if (cell.traits().contains(CellTrait.REPLACEABLE)) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (terraform && cell.traits().contains(CellTrait.TERRAFORMABLE)) {
            return new ReplaceDecision.Place(Destruction.TERRAIN);
        }
        return new ReplaceDecision.Refused(terraform ? Refusal.NOT_TERRAFORMABLE : Refusal.NOT_REPLACEABLE);
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(既存の`ConflictsTest`・`ManifestDifferTest`を含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/build/compile の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/build/compile/BlockMatchTest.java
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 抽象の世界(WorldCell・WorldPort)と置換の規則(ReplaceRules)、状態の照合(BlockMatch)を追加(自然言語→工場建設 P4 Task 4)

Implemented-by: SWE-2 via Devin CLI
```


### Task 4 修正ラウンド(コードは既に実装中。この節の変更を、Task 4のコミットの後に、もう1つのコミットとして積む)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**なぜ**(Codexのレビュー2026-09-30、コントローラが計画書の文で確認済み): Task 4の`ReplaceRules.decide(..., boolean placedByProject)`は、「このプロジェクトが置いた」印があれば**今そこにあるどんなブロックでも**上書きしてよいとしている。プレイヤーが、このプロジェクトの丸石を壊して金のブロックを置いた位置でも、登録簿に印が残っていれば上書きしてしまう(D-25・完了条件15に反する)。**所有は「登録簿に記録があり、かつ今そこにあるブロックが、登録した(置いた)ブロックと同じ種類(ID)」の間だけ**成り立つ(状態は違ってよい: 向きを変えられた自分の階段は直す)。成り立たなければ、その位置は「よその物」として普通の置換の規則で決める(空気・草などは置ける。固い別のブロックは断る=上書きしない)。

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRules.java`(4番目の引数を`Optional<BlockSpec> ownPlaced`に)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRulesTest.java`(呼び出しを直し、`aRegisteredPositionThatNowHoldsSomethingElseIsNotOursAnyMore`を足す)

**Interfaces:**
- Produces: `ReplaceRules.decide(Placement p, WorldCell cell, boolean journaled, Optional<BlockSpec> ownPlaced) → ReplaceDecision`(`ownPlaced`=登録簿のその位置の`PlacedEntry.placed()`。無ければ`Optional.empty()`)。後のタスク(5・6・9・10・24・29)はこの形で呼ぶ(各タスクの本文は、この形で書いてある)。

- [ ] **Step 1: テストを直す(失敗することを確かめる)**

`ReplaceRulesTest.java`を次の全文に置き換える:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReplaceRulesTest {
    private static final IntPos POS = new IntPos(0, 64, 0);
    private static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");

    static Placement put(BlockSpec block, ReplacePolicy policy) {
        return new Placement(0, POS, block, Map.of(), "wall-n", BuildPhase.STRUCTURE, PlacerId.SIMPLE, VerifyMode.EXACT,
                policy, null);
    }

    private static ReplaceDecision decide(ReplacePolicy policy, WorldCell cell) {
        return ReplaceRules.decide(put(PLANKS, policy), cell, false, Optional.empty());
    }

    @Test
    void replaceablePlacesOverAirPlantsSnowAndAsksForFluidsAndLeaves() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID), decide(ReplacePolicy.REPLACEABLE,
                WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.REPLACEABLE, CellTrait.FLUID)));
        assertEquals(new ReplaceDecision.Place(Destruction.LEAVES),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES)));
        assertTrue(Destruction.FLUID.needsDestructiveConfirm());
        assertTrue(Destruction.LEAVES.needsDestructiveConfirm());
        assertTrue(Destruction.EMPTY_CONTAINER.needsDestructiveConfirm());
        assertFalse(Destruction.NONE.needsDestructiveConfirm());
        assertFalse(Destruction.TERRAIN.needsDestructiveConfirm(), "terrain has its own confirmation (terraform)");
    }

    @Test
    void solidTerrainAndUnbreakableBlocksAreRefused() {
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE)));
        assertEquals(new ReplaceDecision.Refused(Refusal.UNBREAKABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:bedrock"), CellTrait.UNBREAKABLE)));
    }

    @Test
    void foreignBlockEntitiesAreNeverReplacedExceptAnEmptyContainer() {
        WorldCell fullChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest");
        WorldCell emptyChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest",
                CellTrait.EMPTY_CONTAINER);
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.REPLACEABLE, fullChest));
        assertEquals(new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER), decide(ReplacePolicy.REPLACEABLE, emptyChest));
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.AIR_ONLY, emptyChest),
                "air-only never replaces a container");
    }

    @Test
    void airOnlyAndExpectAreStrict() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:dirt"))));
        assertEquals(new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:stone"))));
    }

    @Test
    void aJournaledPositionThatAlreadyHoldsTheBlockIsDoneAndOurOwnBlocksMayBeOverwritten() {
        WorldCell planks = WorldCell.of(PLANKS);
        assertEquals(new ReplaceDecision.AlreadyDone(),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, true, Optional.of(PLANKS)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, false, Optional.empty()),
                "somebody else's planks are not ours to overwrite");
        BlockSpec north = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        WorldCell turned = WorldCell.of(BlockSpec.of("minecraft:oak_stairs", "facing", "east"));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                ReplaceRules.decide(put(north, ReplacePolicy.REPLACEABLE), turned, true, Optional.of(north)),
                "the project's own block may be re-placed (repair, modify), even in another state");
    }

    @Test
    void aRegisteredPositionThatNowHoldsSomethingElseIsNotOursAnyMore() {
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        WorldCell gold = WorldCell.of(BlockSpec.of("minecraft:gold_block"));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(cobble, ReplacePolicy.REPLACEABLE), gold, true, Optional.of(cobble)),
                "the registry still lists our cobblestone, but a player put gold there: never overwritten");
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                ReplaceRules.decide(put(cobble, ReplacePolicy.REPLACEABLE), WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE),
                        true, Optional.of(cobble)), "a broken block of ours is air now, which anyone may fill");
    }

    @Test
    void anUnloadedCellIsNotDecided() {
        assertThrows(IllegalArgumentException.class, () -> ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE),
                WorldCell.unloaded(), false, Optional.empty()));
    }
}
```

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.ReplaceRulesTest" --console=plain`
Expected: FAIL(コンパイルエラー。`decide`の4番目の引数の型が違う)。

- [ ] **Step 2: 実装する**

`ReplaceRules.java`を次の全文に置き換える(変わるのは、クラスのjavadoc・`import java.util.Optional`・`decide`の4番目の引数と、その判定の1か所だけ):
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Optional;
import java.util.Set;

/**
 * The one set of replacement rules (F-5): the safety envelope uses it on the pinned survey before approval and the
 * executor on the live world just before each placement, so the two can never disagree. A position is the project's own
 * (it may be overwritten, e.g. to repair a turned stair) only while the registry has an entry for it AND the block there
 * is still the registered block's kind; a registered position that now holds something else belongs to whoever put it
 * there and falls under the ordinary rules, so a player's replacement is never overwritten (D-25).
 */
public final class ReplaceRules {
    private ReplaceRules() {
    }

    public static ReplaceDecision decide(Placement p, WorldCell cell, boolean journaled, Optional<BlockSpec> ownPlaced) {
        if (!cell.loaded()) {
            throw new IllegalArgumentException("decide only on loaded positions; an unloaded one pauses the job");
        }
        BlockSpec now = cell.block();
        if (journaled && BlockMatch.satisfies(now, p.block(), Set.of())) {
            return new ReplaceDecision.AlreadyDone();
        }
        if (ownPlaced.isPresent() && ownPlaced.get().blockId().equals(now.blockId())) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.UNBREAKABLE)) {
            return new ReplaceDecision.Refused(Refusal.UNBREAKABLE);
        }
        if (cell.observed().hasBlockEntity()) {
            boolean mayTakeEmptyContainer = !(p.replaces() instanceof ReplacePolicy.AirOnly)
                    && !(p.replaces() instanceof ReplacePolicy.Expect);
            return mayTakeEmptyContainer && cell.traits().contains(CellTrait.EMPTY_CONTAINER)
                    ? new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER)
                    : new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY);
        }
        return switch (p.replaces()) {
            case ReplacePolicy.AirOnly a -> now.isAir() ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE);
            case ReplacePolicy.Expect e -> now.blockId().equals(e.blockId()) ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER);
            case ReplacePolicy.Replaceable r -> natural(cell, now, false);
        };
    }

    static ReplaceDecision natural(WorldCell cell, BlockSpec now, boolean terraform) {
        if (now.isAir()) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.FLUID)) {
            return new ReplaceDecision.Place(Destruction.FLUID);
        }
        if (cell.traits().contains(CellTrait.LEAVES)) {
            return new ReplaceDecision.Place(Destruction.LEAVES);
        }
        if (cell.traits().contains(CellTrait.REPLACEABLE)) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (terraform && cell.traits().contains(CellTrait.TERRAFORMABLE)) {
            return new ReplaceDecision.Place(Destruction.TERRAIN);
        }
        return new ReplaceDecision.Refused(terraform ? Refusal.NOT_TERRAFORMABLE : Refusal.NOT_REPLACEABLE);
    }
}
```

- [ ] **Step 3: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 4: コミット**

Devin(PowerShellで1行ずつ):
```text
git add src/main/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRules.java
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRulesTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
fix: 所有は「登録簿の記録+今のブロックが置いたブロックと同じ種類」の間だけにし、置き換えられた位置を上書きしない(自然言語→工場建設 P4 Task 4 修正ラウンド)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 5: 地形調査(`SiteSurvey`)と整地(`TerrainPrep`)、置換方針`Terraform`

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

設計`01` 4節(「P3では`SurveyRef`は記録するだけで、地形の切り盛りはP4が載せる」)と`04` F-5(整地)を実装する。整地は**固定した調査(`SiteSurvey`)だけ**から決定論で作るので、調査から承認までに世界が変わってもハッシュは変わらない(完了条件16)。

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/compile/ReplacePolicy.java`(`Terraform`と`fromCode`)、`src/main/java/io/github/khayashi4337/micradrone/build/compile/PlanCompiler.java`(工程の区切りの計算を`PhaseRanges`へ移す。挙動は同じ)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRules.java`(`Terraform`の分岐)
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/{SiteSurvey,TerrainSummary,TerrainResult,TerrainPrep,PhaseRanges}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/{TestManifests,SiteSurveyTest,TerrainPrepTest,ReplacePolicyCodeTest}.java`、`src/test/java/io/github/khayashi4337/micradrone/construction/core/ReplaceRulesTerraformTest.java`

**Interfaces:**
- Consumes: `PlacementManifest`・`Placement`・`ManifestJson.computeHash`・`BomCalculator.bom`・`AssemblyStep`(既存)、`Hashing`・`CanonicalJson`・`PlanJson.boxTree`(既存)、Task 4の`ReplaceRules.natural`
- Produces: 型の辞書の`SiteSurvey`(`static SiteSurvey flat(String dimension, Box worldBounds, int surfaceY, String surfaceBlock)`・`SiteSurvey withColumn(int x, int z, int surfaceY, String surfaceBlock)`も)、`TerrainSummary`(`boolean any()`)、`TerrainResult`(`manifest`は`ERROR`があれば`null`)、`TerrainPrep.apply`・`TerrainPrep.FILL_BLOCK = "minecraft:dirt"`・`TerrainPrep.SITE_PREP_NODE = "site-prep"`、`PhaseRanges.of`、`ReplacePolicy.TERRAFORM`・`CODE_TERRAFORM`・`fromCode`。テスト用の`TestManifests`(以降のタスクのテストが使う: `put(...)`・`of(Box, List<Placement>)`・`hut()`・`DIM`)。

- [ ] **Step 1: 失敗するテストを書く**

`TestManifests.java`(テストの共通部品):
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hand-built manifests for the runtime's tests; indexes, phases, bill of materials and hash are filled in here. */
public final class TestManifests {
    public static final String DIM = "minecraft:overworld";
    public static final String REGISTRY_VERSION = "test-registry";
    public static final BuildFrame FRAME = new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH);

    private TestManifests() {
    }

    public static Placement put(int x, int y, int z, String blockId, String... props) {
        return put(new IntPos(x, y, z), BlockSpec.of(blockId, props), "wall", BuildPhase.STRUCTURE, VerifyMode.EXACT);
    }

    public static Placement put(IntPos pos, BlockSpec block, String node, BuildPhase phase, VerifyMode verify) {
        return new Placement(0, pos, block, Map.of(), node, phase, PlacerId.SIMPLE, verify, ReplacePolicy.REPLACEABLE, null);
    }

    /** The placements in the given order, re-indexed; the hash is computed the way the compiler does. */
    public static PlacementManifest of(Box worldBounds, List<Placement> placements) {
        List<Placement> indexed = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            indexed.add(new Placement(i, p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(),
                    p.verify(), p.replaces(), p.assemblyGroup()));
        }
        Map<String, Integer> bom = BomCalculator.bom(indexed);
        String hash = ManifestJson.computeHash(DIM, REGISTRY_VERSION, worldBounds, indexed, List.of(), bom);
        return new PlacementManifest(PlacementManifest.MANIFEST_VERSION, "plan", 1, REGISTRY_VERSION, DIM, FRAME, worldBounds,
                indexed, List.of(), bom, PhaseRanges.of(indexed), hash);
    }

    /** The P3 golden hut, compiled the golden way (238 placements). */
    public static PlacementManifest hut() {
        try {
            return GoldenHutTest.compileHut(GoldenHutTest.hut()).manifest();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A 3x3 hut: cobblestone foundation at y=63, a ring of planks at y=64 and y=65, an empty interior column. */
    public static PlacementManifest smallHut() {
        List<Placement> out = new ArrayList<>();
        for (int z = 0; z <= 2; z++) {
            for (int x = 0; x <= 2; x++) {
                out.add(put(new IntPos(x, 63, z), BlockSpec.of("minecraft:cobblestone"), "found", BuildPhase.STRUCTURE,
                        VerifyMode.EXACT));
            }
        }
        for (int y = 64; y <= 65; y++) {
            for (int z = 0; z <= 2; z++) {
                for (int x = 0; x <= 2; x++) {
                    if (x != 1 || z != 1) {
                        out.add(put(new IntPos(x, y, z), BlockSpec.of("minecraft:oak_planks"), "wall", BuildPhase.STRUCTURE,
                                VerifyMode.EXACT));
                    }
                }
            }
        }
        return of(new Box(-2, 55, -2, 4, 70, 4), out);
    }
}
```

`SiteSurveyTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import org.junit.jupiter.api.Test;

class SiteSurveyTest {
    private static final Box BOX = new Box(-2, 55, -2, 4, 70, 4);

    @Test
    void flatSurveyAnswersEveryColumnAndNothingOutside() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        assertEquals(63, s.surfaceAt(0, 0));
        assertEquals(63, s.surfaceAt(4, -2));
        assertTrue(s.covers(-2, 4));
        assertFalse(s.covers(5, 0));
        assertThrows(IllegalArgumentException.class, () -> s.surfaceAt(5, 0));
    }

    @Test
    void theDigestFollowsTheContentOnly() {
        SiteSurvey a = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        SiteSurvey b = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        assertEquals(a.digest(), b.digest());
        assertNotEquals(a.digest(), a.withColumn(1, 1, 64, "minecraft:grass_block").digest());
        assertNotEquals(a.digest(), SiteSurvey.flat("minecraft:the_nether", BOX, 63, "minecraft:grass_block").digest(),
                "the dimension is part of the pinned survey (D-27)");
        assertEquals(64, a.withColumn(1, 1, 64, "minecraft:grass_block").surfaceAt(1, 1));
        assertEquals(63, a.surfaceAt(1, 1), "withColumn copies; the original is unchanged");
    }

    @Test
    void theArraysAreDefensivelyCopiedAndShapeChecked() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        s.surfaceY()[0][0] = 99;
        assertEquals(63, s.surfaceAt(-2, -2), "the accessor hands out a copy");
        int[][] wrong = new int[2][2];
        assertThrows(IllegalArgumentException.class, () -> SiteSurvey.of(TestManifests.DIM, BOX, wrong, new String[2][2],
                new boolean[2][2], new boolean[2][2]));
    }

    @Test
    void airSurveyPutsTheSurfaceBelowTheBox() {
        SiteSurvey air = SiteSurvey.air(TestManifests.DIM, BOX);
        assertEquals(BOX.minB() - 1, air.surfaceAt(0, 0));
        assertFalse(air.hasGround(0, 0), "an air column has no ground: terrain prep leaves it alone");
        assertTrue(SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block").hasGround(0, 0));
        assertThrows(IllegalArgumentException.class, () -> air.hasGround(5, 0));
        assertEquals(new SurveyRef(air.digest(), 7L), air.ref(7L));
    }
}
```

`TerrainPrepTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerrainPrepTest {
    private static final Box BOX = new Box(-2, 55, -2, 4, 70, 4);

    @Test
    void nothingTouchesTheTerrainSoTheManifestIsReturnedAsIs() {
        PlacementManifest m = TestManifests.smallHut();
        TerrainResult r = TerrainPrep.apply(m, SiteSurvey.air(TestManifests.DIM, BOX));
        assertSame(m, r.manifest());
        assertEquals(new TerrainSummary(0, 0), r.summary());
        assertTrue(r.issues().isEmpty());
    }

    @Test
    void theFoundationSunkIntoTheGroundIsTerraformed() {
        PlacementManifest m = TestManifests.smallHut();
        TerrainResult r = TerrainPrep.apply(m, SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(9, 0), r.summary());
        assertEquals(m.placements().size(), r.manifest().placements().size());
        for (Placement p : r.manifest().placements()) {
            boolean underground = p.pos().y() <= 63;
            assertEquals(underground ? ReplacePolicy.TERRAFORM : ReplacePolicy.REPLACEABLE, p.replaces(), p.toString());
        }
        assertNotEquals(m.hash(), r.manifest().hash(), "the replace policy is part of the hash");
    }

    @Test
    void aHillInsideTheHutIsCutToAirFirst() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 65, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(27, 0), r.summary(), "25 placements in the ground plus the 2 interior cells");
        List<Placement> ps = r.manifest().placements();
        assertEquals(27, ps.size());
        assertEquals(new IntPos(1, 65, 1), ps.get(0).pos(), "cuts go top-down");
        assertEquals(new IntPos(1, 64, 1), ps.get(1).pos());
        for (int i = 0; i < 2; i++) {
            assertTrue(ps.get(i).block().isAir());
            assertEquals(BuildPhase.SITE_PREP, ps.get(i).phase());
            assertEquals(ReplacePolicy.TERRAFORM, ps.get(i).replaces());
            assertEquals(TerrainPrep.SITE_PREP_NODE, ps.get(i).partNodeId());
            assertEquals(i, ps.get(i).index());
        }
        assertEquals(List.of(new PhaseRange(BuildPhase.SITE_PREP, 0, 2), new PhaseRange(BuildPhase.STRUCTURE, 2, 27)),
                r.manifest().phases());
    }

    @Test
    void aValleyUnderTheFoundationIsFilledWithDirt() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 60, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(0, 18), r.summary(), "y=61 and y=62 under each of the 9 columns");
        List<Placement> ps = r.manifest().placements();
        assertEquals(25 + 18, ps.size());
        assertEquals(TerrainPrep.FILL_BLOCK, ps.get(0).block().blockId());
        assertEquals(61, ps.get(0).pos().y());
        assertEquals(62, ps.get(17).pos().y());
        assertEquals(18, r.manifest().bom().get(TerrainPrep.FILL_BLOCK));
    }

    @Test
    void theResultIsDeterministic() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 60, "minecraft:grass_block").withColumn(1, 1, 66,
                "minecraft:stone");
        String first = TerrainPrep.apply(TestManifests.smallHut(), s).manifest().hash();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, TerrainPrep.apply(TestManifests.smallHut(), s).manifest().hash());
        }
    }

    @Test
    void fillBelowTheSiteAndUnsurveyedColumnsAreRefused() {
        PlacementManifest tight = TestManifests.of(new Box(-2, 62, -2, 4, 70, 4), TestManifests.smallHut().placements());
        TerrainResult low = TerrainPrep.apply(tight, SiteSurvey.flat(TestManifests.DIM, new Box(-2, 62, -2, 4, 70, 4), 59,
                "minecraft:grass_block"));
        assertNull(low.manifest());
        assertEquals(IssueCode.E_OUT_OF_BOUNDS, low.issues().get(0).code());
        assertTrue(low.issues().get(0).id().endsWith("#terrain"));
        TerrainResult narrow = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, new Box(0, 55, 0, 1, 70, 1), 60, "minecraft:grass_block"));
        assertNull(narrow.manifest());
        assertTrue(narrow.issues().get(0).id().endsWith("#survey"));
    }

    @Test
    void aSurveyOfAnotherDimensionIsABug() {
        assertThrows(IllegalArgumentException.class, () -> TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.air("minecraft:the_nether", BOX)));
    }

    @Test
    void theGoldenHutOverAnAirSurveyKeepsTheGoldenHash() {
        PlacementManifest hut = TestManifests.hut();
        SiteSurvey air = SiteSurvey.air(hut.dimension(), hut.worldBounds());
        assertEquals(hut.hash(), TerrainPrep.apply(hut, air).manifest().hash());
    }

    /** Applies the SITE_PREP placements in manifest order to a column world where sand and gravel fall into air below. */
    private static Map<IntPos, String> applyWithGravity(PlacementManifest m, Map<IntPos, String> world) {
        for (Placement p : m.placements()) {
            if (p.phase() != BuildPhase.SITE_PREP) {
                continue;
            }
            world.put(p.pos(), p.block().blockId());
            IntPos hole = p.pos();
            IntPos above = hole.plus(0, 1, 0);
            while (p.block().isAir() && GRAVITY.contains(world.getOrDefault(above, AIR_ID))) {
                world.put(hole, world.get(above));
                world.put(above, AIR_ID);
                hole = above;
                above = above.plus(0, 1, 0);
            }
        }
        return world;
    }

    private static final String AIR_ID = "minecraft:air";
    private static final java.util.Set<String> GRAVITY = java.util.Set.of("minecraft:sand", "minecraft:gravel");

    @Test
    void cutsGoTopDownSoSandAboveNeverFallsIntoAClearedCell() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 65, "minecraft:sand"));
        Map<IntPos, String> world = new HashMap<>();
        for (int y = 60; y <= 65; y++) {
            for (int z = 0; z <= 2; z++) {
                for (int x = 0; x <= 2; x++) {
                    world.put(new IntPos(x, y, z), "minecraft:sand");
                }
            }
        }
        applyWithGravity(r.manifest(), world);
        for (Placement p : r.manifest().placements()) {
            if (p.phase() == BuildPhase.SITE_PREP && p.block().isAir()) {
                assertEquals(AIR_ID, world.get(p.pos()), "a cleared cell stays clear: " + p.pos());
            }
        }
    }

    @Test
    void aHillTallerThanTheBuildingIsCutToItsSurface() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 68, "minecraft:stone"));
        assertEquals(new TerrainSummary(25 + 29, 0), r.summary(),
                "25 sunk placements; cut: the interior column 64..68 and 66..68 over the 8 ring columns");
        java.util.Set<IntPos> cut = new java.util.HashSet<>();
        for (Placement p : r.manifest().placements()) {
            if (p.phase() == BuildPhase.SITE_PREP) {
                cut.add(p.pos());
            }
        }
        assertTrue(cut.contains(new IntPos(0, 68, 0)), "no ground is left hanging over the roof");
        assertTrue(cut.contains(new IntPos(1, 64, 1)));
    }

    @Test
    void groundCutAboveTheSiteIsRefusedLikeFillBelowIt() {
        PlacementManifest low = TestManifests.of(new Box(-2, 55, -2, 4, 67, 4), TestManifests.smallHut().placements());
        TerrainResult r = TerrainPrep.apply(low, SiteSurvey.flat(TestManifests.DIM, BOX, 68, "minecraft:stone"));
        assertNull(r.manifest(), "the ground reaches y=68, the site only y=67");
        assertTrue(r.issues().get(0).id().endsWith("#terrain"));
    }
}
```

`ReplacePolicyCodeTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReplacePolicyCodeTest {
    @Test
    void everyPolicyRoundTripsThroughItsCode() {
        for (ReplacePolicy p : new ReplacePolicy[]{ReplacePolicy.AIR_ONLY, ReplacePolicy.REPLACEABLE, ReplacePolicy.TERRAFORM,
                new ReplacePolicy.Expect("minecraft:dirt")}) {
            assertEquals(p, ReplacePolicy.fromCode(p.code()));
        }
        assertEquals("terraform", ReplacePolicy.TERRAFORM.code());
        assertThrows(IllegalArgumentException.class, () -> ReplacePolicy.fromCode("anything"));
        assertThrows(IllegalArgumentException.class, () -> ReplacePolicy.fromCode("expect:"));
    }
}
```

`ReplaceRulesTerraformTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReplaceRulesTerraformTest {
    private static ReplaceDecision decide(WorldCell cell) {
        return ReplaceRules.decide(ReplaceRulesTest.put(BlockSpec.of("minecraft:cobblestone"), ReplacePolicy.TERRAFORM),
                cell, false, Optional.empty());
    }

    @Test
    void terraformTakesNaturalGroundButNotBuiltBlocks() {
        assertEquals(new ReplaceDecision.Place(Destruction.TERRAIN),
                decide(WorldCell.of(BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_TERRAFORMABLE),
                decide(WorldCell.of(BlockSpec.of("minecraft:stone_bricks"))), "somebody's wall is not terrain");
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID),
                decide(WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID)));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.*" --tests "io.github.khayashi4337.micradrone.construction.core.*" --console=plain`
Expected: FAIL(`SiteSurvey`・`TerrainPrep`・`PhaseRanges`・`ReplacePolicy.TERRAFORM`が無いのでコンパイルエラー)。

- [ ] **Step 3: 実装する**

`ReplacePolicy.java`に足す(既存の`code()`の`switch`にも1行):
```java
    Terraform TERRAFORM = new Terraform();
    String CODE_TERRAFORM = "terraform";

    /** May remove natural ground (the micradrone:terraformable tag) as well as what REPLACEABLE takes (P4, F-5). */
    record Terraform() implements ReplacePolicy {
    }

    static ReplacePolicy fromCode(String code) {
        if (CODE_AIR_ONLY.equals(code)) {
            return AIR_ONLY;
        }
        if (CODE_REPLACEABLE.equals(code)) {
            return REPLACEABLE;
        }
        if (CODE_TERRAFORM.equals(code)) {
            return TERRAFORM;
        }
        if (code != null && code.startsWith(CODE_EXPECT_PREFIX) && code.length() > CODE_EXPECT_PREFIX.length()) {
            return new Expect(code.substring(CODE_EXPECT_PREFIX.length()));
        }
        throw new IllegalArgumentException("unknown replace policy code: " + code);
    }
```
`code()`の`switch`に`case Terraform t -> CODE_TERRAFORM;`を足す。`ReplaceRules.decide`の`switch`に`case ReplacePolicy.Terraform t -> natural(cell, now, true);`を足す。

`PhaseRanges.java`(`PlanCompiler.build`の中の同じループを、この関数の呼び出しに置き換える。重複を1か所にまとめる):
```java
package io.github.khayashi4337.micradrone.build.compile;

import java.util.ArrayList;
import java.util.List;

/** The runs of equal construction phases in a placement list, as index ranges (design 01, section 4). */
public final class PhaseRanges {
    private PhaseRanges() {
    }

    public static List<PhaseRange> of(List<Placement> placements) {
        List<PhaseRange> phases = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= placements.size(); i++) {
            if (i == placements.size() || placements.get(i).phase() != placements.get(start).phase()) {
                phases.add(new PhaseRange(placements.get(start).phase(), start, i));
                start = i;
            }
        }
        return phases;
    }
}
```

`SiteSurvey.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The server-issued terrain survey a compile is pinned to (design 01, sections 7 and 11; 04 F-3): per column of the
 * world box, the highest natural ground block (not plants, leaves, logs or fluid), the block there, and whether water or
 * a tree stood above it. Arrays are indexed [x - minX][z - minZ]. The digest covers the whole content, so a pinned
 * survey reproduces the same terraforming, and so the same manifest hash, however the world changes afterwards.
 */
public record SiteSurvey(String dimension, Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water,
                         boolean[][] tree, String digest) {
    private static final String AIR = "minecraft:air";

    public SiteSurvey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(worldBounds, "worldBounds");
        int sx = worldBounds.maxA() - worldBounds.minA() + 1;
        int sz = worldBounds.maxC() - worldBounds.minC() + 1;
        surfaceY = copy(surfaceY, sx, sz);
        surfaceBlock = copy(surfaceBlock, sx, sz);
        water = copy(water, sx, sz);
        tree = copy(tree, sx, sz);
        Objects.requireNonNull(digest, "digest");
    }

    public static SiteSurvey of(String dimension, Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water,
                                boolean[][] tree) {
        // the shape (and the non-null cells) is checked before the digest reads the arrays
        int sx = worldBounds.maxA() - worldBounds.minA() + 1;
        int sz = worldBounds.maxC() - worldBounds.minC() + 1;
        copy(surfaceY, sx, sz);
        copy(surfaceBlock, sx, sz);
        copy(water, sx, sz);
        copy(tree, sx, sz);
        return new SiteSurvey(dimension, worldBounds, surfaceY, surfaceBlock, water, tree,
                digestOf(dimension, worldBounds, surfaceY, surfaceBlock, water, tree));
    }

    public static SiteSurvey flat(String dimension, Box worldBounds, int surface, String block) {
        int sx = worldBounds.maxA() - worldBounds.minA() + 1;
        int sz = worldBounds.maxC() - worldBounds.minC() + 1;
        int[][] ys = new int[sx][sz];
        String[][] blocks = new String[sx][sz];
        for (int i = 0; i < sx; i++) {
            Arrays.fill(ys[i], surface);
            Arrays.fill(blocks[i], block);
        }
        return of(dimension, worldBounds, ys, blocks, new boolean[sx][sz], new boolean[sx][sz]);
    }

    /** A survey with no ground inside the box: terraforming leaves any manifest unchanged. */
    public static SiteSurvey air(String dimension, Box worldBounds) {
        return flat(dimension, worldBounds, worldBounds.minB() - 1, AIR);
    }

    public SiteSurvey withColumn(int x, int z, int surface, String block) {
        int[][] ys = surfaceY();
        String[][] blocks = surfaceBlock();
        ys[x - worldBounds.minA()][z - worldBounds.minC()] = surface;
        blocks[x - worldBounds.minA()][z - worldBounds.minC()] = block;
        return of(dimension, worldBounds, ys, blocks, water(), tree());
    }

    public boolean covers(int x, int z) {
        return x >= worldBounds.minA() && x <= worldBounds.maxA() && z >= worldBounds.minC() && z <= worldBounds.maxC();
    }

    public int surfaceAt(int x, int z) {
        if (!covers(x, z)) {
            throw new IllegalArgumentException("column " + x + "," + z + " is outside the survey " + worldBounds);
        }
        return surfaceY[x - worldBounds.minA()][z - worldBounds.minC()];
    }

    /** False when the column holds no natural ground inside the box (the surveyor wrote air): nothing to cut or fill. */
    public boolean hasGround(int x, int z) {
        if (!covers(x, z)) {
            throw new IllegalArgumentException("column " + x + "," + z + " is outside the survey " + worldBounds);
        }
        return !AIR.equals(surfaceBlock[x - worldBounds.minA()][z - worldBounds.minC()]);
    }

    public SurveyRef ref(long cachedUntilTick) {
        return new SurveyRef(digest, cachedUntilTick);
    }

    @Override
    public int[][] surfaceY() {
        return copy(surfaceY, surfaceY.length, surfaceY.length == 0 ? 0 : surfaceY[0].length);
    }

    @Override
    public String[][] surfaceBlock() {
        return copy(surfaceBlock, surfaceBlock.length, surfaceBlock.length == 0 ? 0 : surfaceBlock[0].length);
    }

    @Override
    public boolean[][] water() {
        return copy(water, water.length, water.length == 0 ? 0 : water[0].length);
    }

    @Override
    public boolean[][] tree() {
        return copy(tree, tree.length, tree.length == 0 ? 0 : tree[0].length);
    }

    private static String digestOf(String dimension, Box bounds, int[][] ys, String[][] blocks, boolean[][] water,
                                   boolean[][] tree) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("dimension", dimension);
        t.put("bounds", PlanJson.boxTree(bounds));
        List<Object> cols = new ArrayList<>();
        for (int i = 0; i < ys.length; i++) {
            for (int j = 0; j < ys[i].length; j++) {
                cols.add(List.of((long) ys[i][j], blocks[i][j], water[i][j], tree[i][j]));
            }
        }
        t.put("columns", cols);
        return Hashing.sha256Hex(CanonicalJson.write(t));
    }

    private static int[][] copy(int[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        int[][] out = new int[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    private static String[][] copy(String[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        String[][] out = new String[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
            for (String s : out[i]) {
                Objects.requireNonNull(s, "surface block");
            }
        }
        return out;
    }

    private static boolean[][] copy(boolean[][] a, int sx, int sz) {
        checkShape(a, sx, sz);
        boolean[][] out = new boolean[sx][];
        for (int i = 0; i < sx; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    /** Every 2-D survey array is an Object[] of primitive or String rows; all must be sx by sz. */
    private static void checkShape(Object[] rows, int sx, int sz) {
        Objects.requireNonNull(rows, "survey array");
        if (rows.length != sx) {
            throw new IllegalArgumentException("survey arrays must have " + sx + " rows, got " + rows.length);
        }
        for (Object row : rows) {
            int n = Array.getLength(Objects.requireNonNull(row, "survey row"));
            if (n != sz) {
                throw new IllegalArgumentException("survey rows must have " + sz + " columns, got " + n);
            }
        }
    }
}
```
(`int[][]`・`String[][]`・`boolean[][]`は、どれも`Object[]`として`checkShape`に渡せる。アクセサは毎回コピーを返すので、外から配列を書き換えても調査は変わらない。)

`TerrainSummary.java`: `public record TerrainSummary(int cut, int fill) { public TerrainSummary { if (cut < 0 || fill < 0) throw new IllegalArgumentException(...); } public boolean any() { return cut + fill > 0; } }`。`TerrainResult.java`: `public record TerrainResult(PlacementManifest manifest, TerrainSummary summary, List<Issue> issues)`(`issues`は`List.copyOf`、`summary`は非null)。

`TerrainPrep.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Terraforming (SITE_PREP cut and fill, 04 F-5) from the pinned survey only: placements at or below a column's surface
 * may remove natural ground (REPLACEABLE becomes TERRAFORM), ground in the building's footprint columns is cut to air
 * from the column's lowest solid placement up to the column's surface (an interior column holds only its floor, yet the
 * hill must go; ground left above the roof would hang there, and sand or gravel would fall into the house), and the gap
 * under the lowest solid placement is filled with dirt. Cuts run top-down and fills bottom-up, so a falling block (sand,
 * gravel) never drops into a cell that was just cleared. Deterministic: the same manifest and survey always give the
 * same result, so the approval hash holds while the world changes (completion condition 16).
 */
public final class TerrainPrep {
    public static final String FILL_BLOCK = "minecraft:dirt";
    public static final String SITE_PREP_NODE = "site-prep";
    private static final String KEY_SURVEY = "survey";
    private static final String KEY_TERRAIN = "terrain";
    private static final String SUBJECT = "site";
    private static final String DATA_COUNT = "count";
    private static final int NO_BASE = Integer.MAX_VALUE;
    private static final Comparator<IntPos> BOTTOM_UP = Comparator.comparingInt(IntPos::y).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);
    /** Cutting from the top keeps every cell above a cut already empty, so nothing falls into it. */
    private static final Comparator<IntPos> TOP_DOWN = Comparator.<IntPos>comparingInt(p -> -p.y()).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);

    private TerrainPrep() {
    }

    public static TerrainResult apply(PlacementManifest m, SiteSurvey survey) {
        if (!m.dimension().equals(survey.dimension())) {
            throw new IllegalArgumentException("survey of " + survey.dimension() + " for a manifest of " + m.dimension());
        }
        List<Issue> issues = new ArrayList<>();
        Map<Long, int[]> columns = new HashMap<>();
        Set<IntPos> occupied = new HashSet<>();
        int uncovered = 0;
        for (Placement p : m.placements()) {
            occupied.add(p.pos());
            if (!survey.covers(p.pos().x(), p.pos().z())) {
                uncovered++;
                continue;
            }
            int[] base = columns.computeIfAbsent(key(p.pos().x(), p.pos().z()), k -> new int[]{NO_BASE});
            if (!p.block().isAir()) {
                base[0] = Math.min(base[0], p.pos().y());
            }
        }
        if (uncovered > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_SURVEY, List.of(SUBJECT),
                    "地形調査の範囲の外に、置く位置が" + uncovered + "個あります", Map.of(DATA_COUNT, String.valueOf(uncovered)),
                    List.of()));
            return new TerrainResult(null, new TerrainSummary(0, 0), issues);
        }
        List<Placement> rest = new ArrayList<>(m.placements().size());
        int sunk = 0;
        for (Placement p : m.placements()) {
            int px = p.pos().x();
            int pz = p.pos().z();
            boolean underground = survey.hasGround(px, pz) && p.pos().y() <= survey.surfaceAt(px, pz);
            if (underground && p.replaces() instanceof ReplacePolicy.Replaceable) {
                rest.add(withReplaces(p, ReplacePolicy.TERRAFORM));
                sunk++;
            } else {
                rest.add(p);
            }
        }
        List<IntPos> cut = new ArrayList<>();
        List<IntPos> fill = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : columns.entrySet()) {
            int base = e.getValue()[0];
            if (base == NO_BASE) {
                continue; // only air is placed here: nothing stands on this column
            }
            int x = (int) (e.getKey() >> 32);
            int z = e.getKey().intValue();
            if (!survey.hasGround(x, z)) {
                continue; // a column without ground (air survey) is neither cut nor filled
            }
            int surface = survey.surfaceAt(x, z);
            for (int y = base; y <= surface; y++) {
                IntPos q = new IntPos(x, y, z);
                if (!occupied.contains(q)) {
                    cut.add(q);
                }
            }
            for (int y = surface + 1; y < base; y++) {
                fill.add(new IntPos(x, y, z));
            }
        }
        Box bounds = m.worldBounds();
        long outside = java.util.stream.Stream.concat(cut.stream(), fill.stream())
                .filter(q -> !bounds.contains(q.x(), q.y(), q.z())).count();
        if (outside > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_TERRAIN, List.of(SUBJECT),
                    "地面を切る・土を盛る位置が、敷地の範囲をはみ出します(" + outside + "マス)。敷地を広げてください",
                    Map.of(DATA_COUNT, String.valueOf(outside)), List.of()));
            return new TerrainResult(null, new TerrainSummary(0, 0), issues);
        }
        if (sunk == 0 && cut.isEmpty() && fill.isEmpty()) {
            return new TerrainResult(m, new TerrainSummary(0, 0), issues);
        }
        List<Placement> prep = new ArrayList<>();
        cut.sort(TOP_DOWN);
        fill.sort(BOTTOM_UP);
        List<IntPos> prepCells = new ArrayList<>(cut);
        prepCells.addAll(fill);
        Set<IntPos> fillSet = new HashSet<>(fill);
        for (IntPos q : prepCells) {
            boolean isFill = fillSet.contains(q);
            prep.add(new Placement(0, q, isFill ? BlockSpec.of(FILL_BLOCK) : BlockSpec.AIR, Map.of(), SITE_PREP_NODE,
                    BuildPhase.SITE_PREP, PlacerId.SIMPLE, VerifyMode.EXACT,
                    isFill ? ReplacePolicy.REPLACEABLE : ReplacePolicy.TERRAFORM, null));
        }
        List<Placement> all = new ArrayList<>(prep.size() + rest.size());
        all.addAll(prep);
        all.addAll(rest);
        List<Placement> indexed = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            indexed.add(withIndex(all.get(i), i));
        }
        int shift = prep.size();
        List<AssemblyStep> assemblies = new ArrayList<>();
        for (AssemblyStep a : m.assemblies()) {
            assemblies.add(new AssemblyStep(a.groupId(), a.kind(), a.trigger(),
                    a.memberIndexes().stream().map(i -> i + shift).toList(), a.expect()));
        }
        Map<String, Integer> bom = BomCalculator.bom(indexed);
        String hash = ManifestJson.computeHash(m.dimension(), m.registryVersion(), bounds, indexed, assemblies, bom);
        PlacementManifest out = new PlacementManifest(m.manifestVersion(), m.planId(), m.planRevision(), m.registryVersion(),
                m.dimension(), m.frame(), bounds, indexed, assemblies, bom, PhaseRanges.of(indexed), hash);
        return new TerrainResult(out, new TerrainSummary(sunk + cut.size(), fill.size()), issues);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static Placement withReplaces(Placement p, ReplacePolicy r) {
        return new Placement(p.index(), p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(),
                p.verify(), r, p.assemblyGroup());
    }

    private static Placement withIndex(Placement p, int i) {
        return new Placement(i, p.pos(), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(), p.placer(), p.verify(),
                p.replaces(), p.assemblyGroup());
    }
}
```
(切るのは、足跡の列の一番下の置く位置から**その列の地面の高さまで全部**(Codexのレビュー2026-09-30で直した: 前の版は建物の最上段で止めていたので、建物より高い丘では屋根の上に地面が残り、砂や砂利なら中へ落ちてくる)。**切る順は上から下、盛る順は下から上**で、切るのと盛るのを1つの並べ方で混ぜない(下から切ると、上の砂・砂利が、切ったばかりのマスへ落ちる。`cutsGoTopDownSoSandAboveNeverFallsIntoAClearedCell`は、砂が落ちる小さな世界の模型で、この順を確かめる)。切る位置・盛る位置のどちらかが施工の範囲(`worldBounds`)をはみ出せば`E-OUT-OF-BOUNDS#terrain`。`key`は上位32ビットに`x`、下位32ビットに`z`を詰める。`intValue()`は下位32ビットを符号つきで戻すので、負の`z`も元に戻る。列の走査は`HashMap`の順だが、作ったセルは最後に並べ直すので、結果は走査の順に依存しない。)

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`GoldenHutTest`・`DeterminismTest`・`RotationInvarianceTest`など既存のコンパイラのテストを含む。`PhaseRanges`への移し替えで挙動が変わっていないことの確認)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/build/compile の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/build/compile の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 固定した地形調査(SiteSurvey)からの整地(TerrainPrep)と置換方針Terraformを追加(自然言語→工場建設 P4 Task 5)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 6: ブロックと品物の対応(`BlockToItem`)と安全枠(`SafetyEnvelope`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/{ItemCount,BlockToItem}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{SafetyLimits,PlacementSurvey,ItemCatalog,ReplacementSummary,SafetyReport,SafetyEnvelope}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/compile/BomCalculator.java`(`BlockToItem.cost`を使う。挙動は同じ)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/BlockToItemTest.java`、`src/test/java/io/github/khayashi4337/micradrone/construction/core/SafetyEnvelopeTest.java`

**Interfaces:**
- Consumes: Task 4の`ReplaceRules`・`WorldCell`、Task 5の`TerrainSummary`・`TestManifests`、既存の`PlaceableBlockPolicy.isAlwaysForbidden`・`BlockForms`の定数(`HALF_UPPER`・`PROP_HALF`・`DOOR_SUFFIX`・`TYPE_DOUBLE`・`PROP_TYPE`・`SLAB_SUFFIX`・`WALL_SIGN_SUFFIX`・`SIGN_SUFFIX`)
- Produces: 型の辞書のとおり。`SafetyLimits.DEFAULT_MAX_PLACEMENTS = 20_000`・`DEFAULT_MAX_SIZE_X = 128`・`DEFAULT_MAX_SIZE_Y = 96`・`DEFAULT_MAX_SIZE_Z = 128`(`04` F-5の表)、`static SafetyLimits defaults(int minBuildY, int maxBuildYExclusive)`。`ReplacementSummary.SAMPLE_LIMIT = 16`(承認画面に並べる破壊的な置換の位置の数)、`needsDestructiveConfirm()`・`needsTerraformConfirm()`。`ItemCatalog.ANY`(全部ある扱い。テスト用ではなく、クリエイティブで材料を見ない既定)。

- [ ] **Step 1: 失敗するテストを書く**

`BlockToItemTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockToItemTest {
    @Test
    void doorsSlabsSignsAndBeltsUseTheirItems() {
        assertEquals(Optional.of(new ItemCount("minecraft:oak_door", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_door", "half", "lower")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:oak_door", "half", "upper")).isEmpty(), "one item per door");
        assertEquals(Optional.of(new ItemCount("minecraft:oak_slab", 2)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_slab", "type", "double")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_slab", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_slab", "type", "bottom")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_sign", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_wall_sign", "facing", "north")));
        assertEquals(Optional.of(new ItemCount("create:belt_connector", 1)), BlockToItem.cost(BlockSpec.of("create:belt")));
        assertTrue(BlockToItem.cost(BlockSpec.AIR).isEmpty());
    }

    @Test
    void cutGroundYieldsWhatMiningWouldGive() {
        assertEquals(Optional.of(new ItemCount("minecraft:dirt", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:grass_block")));
        assertEquals(Optional.of(new ItemCount("minecraft:cobblestone", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:stone")));
        assertEquals(Optional.of(new ItemCount("minecraft:cobbled_deepslate", 1)),
                BlockToItem.cutYield(BlockSpec.of("minecraft:deepslate")));
        assertEquals(Optional.of(new ItemCount("minecraft:sand", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:sand")));
        assertTrue(BlockToItem.cutYield(BlockSpec.AIR).isEmpty());
    }

    @Test
    void mergeAddsUpTheSameItemsInIdOrder() {
        assertEquals(List.of(new ItemCount("a:x", 3), new ItemCount("b:y", 1)),
                BlockToItem.merge(List.of(new ItemCount("b:y", 1), new ItemCount("a:x", 1), new ItemCount("a:x", 2))));
        assertThrows(IllegalArgumentException.class, () -> new ItemCount("a:x", 0));
    }

    @Test
    void blocksWithoutAnItemCostWhatBreakingThemGivesBack() {
        assertEquals(Optional.of(new ItemCount("create:shaft", 1)), BlockToItem.cost(BlockSpec.of("create:powered_shaft")),
                "an IMPLICIT block: a steam engine turns a placed shaft into it, and it drops the shaft");
        assertEquals(Optional.of(new ItemCount("minecraft:torch", 1)), BlockToItem.cost(BlockSpec.of("minecraft:wall_torch")));
        assertEquals(Optional.of(new ItemCount("minecraft:redstone", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:redstone_wire")));
        assertEquals(Optional.of(new ItemCount("minecraft:white_banner", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:white_wall_banner")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_hanging_sign", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_wall_hanging_sign")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:sunflower", "half", "upper")).isEmpty());
        assertEquals(Optional.of(new ItemCount("minecraft:sunflower", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:sunflower", "half", "lower")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:red_bed", "part", "head")).isEmpty());
        assertEquals(Optional.of(new ItemCount("minecraft:red_bed", 1)), BlockToItem.cost(BlockSpec.of("minecraft:red_bed", "part", "foot")));
    }
}
```

`SafetyEnvelopeTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafetyEnvelopeTest {
    private static final SafetyLimits LIMITS = SafetyLimits.defaults(-64, 320);
    private static final TerrainSummary NO_TERRAIN = new TerrainSummary(0, 0);

    /** Every placement position reads as air unless overridden. */
    private static PlacementSurvey airAt(PlacementManifest m, Map<IntPos, WorldCell> overrides) {
        Map<IntPos, WorldCell> cells = new HashMap<>();
        for (Placement p : m.placements()) {
            cells.put(p.pos(), WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
        }
        cells.putAll(overrides);
        return new PlacementSurvey(cells);
    }

    private static SafetyReport check(PlacementManifest m, PlacementSurvey s) {
        return SafetyEnvelope.check(m, NO_TERRAIN, s, LIMITS, PlaceableBlockPolicy.builtin(), ItemCatalog.ANY, pos -> Optional.empty());
    }

    private static List<String> ids(SafetyReport r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void aCleanHutPassesWithNothingToConfirm() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyReport r = check(m, airAt(m, Map.of()));
        assertEquals(List.of(), r.issues());
        assertFalse(r.replacements().needsDestructiveConfirm());
        assertFalse(r.replacements().needsTerraformConfirm());
    }

    @Test
    void tooManyTooBigAndTooHighAreRefusedBeforeApproval() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyLimits tiny = new SafetyLimits(3, 2, 2, 2, 64, 320);
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, Map.of()), tiny, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY, pos -> Optional.empty());
        assertEquals(List.of("E-OUT-OF-BOUNDS:manifest#placements", "E-OUT-OF-BOUNDS:manifest#size",
                "E-OUT-OF-BOUNDS:manifest#height"), ids(r));
        assertEquals("9", r.issues().get(2).data().get("count"), "the 9 foundation cells lie below y=64");
    }

    @Test
    void blockedPositionsAreGroupedPerPartAndReason() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> stone = new HashMap<>();
        stone.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE));
        stone.put(new IntPos(2, 64, 0), WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE));
        stone.put(new IntPos(0, 63, 0), WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest"));
        SafetyReport r = check(m, airAt(m, stone));
        assertEquals(List.of("E-SITE-BLOCKED:found#foreign_block_entity", "E-SITE-BLOCKED:wall#not_replaceable"), ids(r));
        Issue walls = r.issues().get(1);
        assertEquals("2", walls.data().get("count"));
        assertEquals("0,64,0", walls.data().get("first"));
        assertFalse(walls.acceptable(), "E-SITE-BLOCKED can never be accepted");
    }

    @Test
    void fluidsLeavesAndEmptyContainersNeedTheDestructiveConfirmation() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> o = new HashMap<>();
        o.put(new IntPos(0, 63, 0), WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID, CellTrait.REPLACEABLE));
        o.put(new IntPos(1, 63, 0), WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES));
        o.put(new IntPos(2, 63, 0), WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest",
                CellTrait.EMPTY_CONTAINER));
        SafetyReport r = check(m, airAt(m, o));
        assertEquals(List.of(), r.issues());
        assertEquals(1, r.replacements().fluids());
        assertEquals(1, r.replacements().leaves());
        assertEquals(1, r.replacements().emptyContainers());
        assertTrue(r.replacements().needsDestructiveConfirm());
        assertEquals(List.of(new IntPos(0, 63, 0), new IntPos(1, 63, 0), new IntPos(2, 63, 0)),
                r.replacements().destructiveSample());
    }

    @Test
    void theSampleOfDestructivePositionsIsCapped() {
        List<Placement> row = new ArrayList<>();
        Map<IntPos, WorldCell> water = new HashMap<>();
        for (int x = 0; x < 40; x++) {
            row.add(TestManifests.put(x, 64, 0, "minecraft:oak_planks"));
            water.put(new IntPos(x, 64, 0), WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID));
        }
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 40, 70, 1), row);
        SafetyReport r = check(m, new PlacementSurvey(water));
        assertEquals(40, r.replacements().fluids());
        assertEquals(ReplacementSummary.SAMPLE_LIMIT, r.replacements().destructiveSample().size());
    }

    @Test
    void unloadedForbiddenAndUnknownItemsAreReported() {
        List<Placement> ps = List.of(TestManifests.put(0, 64, 0, "minecraft:bedrock"),
                TestManifests.put(1, 64, 0, "minecraft:oak_planks"));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 3, 70, 1), ps);
        Map<IntPos, WorldCell> cells = new HashMap<>();
        cells.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.AIR));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, new PlacementSurvey(cells), LIMITS, PlaceableBlockPolicy.builtin(),
                id -> !id.equals("minecraft:oak_planks"), pos -> Optional.empty());
        assertEquals(List.of("E-BLOCK-FORBIDDEN:wall#minecraft:bedrock", "E-MATERIAL-UNKNOWN:wall#minecraft:oak_planks",
                "E-SITE-BLOCKED:manifest#unloaded"), ids(r));
    }

    @Test
    void positionsTheProjectAlreadyPlacedAreOurs() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> planks = new HashMap<>();
        planks.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:oak_planks")));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, planks), LIMITS, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY,
                pos -> pos.equals(new IntPos(0, 64, 0)) ? Optional.of(BlockSpec.of("minecraft:oak_planks")) : Optional.empty());
        assertEquals(List.of(), r.issues());
    }

    @Test
    void terrainCountsAreCarriedToTheSummary() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyReport r = SafetyEnvelope.check(m, new TerrainSummary(9, 18), airAt(m, Map.of()), LIMITS,
                PlaceableBlockPolicy.builtin(), ItemCatalog.ANY, pos -> Optional.empty());
        assertEquals(9, r.replacements().terrainCut());
        assertEquals(18, r.replacements().terrainFill());
        assertTrue(r.replacements().needsTerraformConfirm());
        assertEquals(IssueCode.E_SITE_BLOCKED.label(), "E-SITE-BLOCKED");
    }

    @Test
    void aPlacementOutsideTheSiteBoxIsRefusedBeforeTheSurveyIsAsked() {
        List<Placement> ps = new ArrayList<>(TestManifests.smallHut().placements());
        ps.add(TestManifests.put(1000, 64, 1000, "minecraft:oak_planks"));
        PlacementManifest m = TestManifests.of(TestManifests.smallHut().worldBounds(), ps);
        SafetyReport r = check(m, airAt(m, Map.of()));
        assertEquals(List.of("E-OUT-OF-BOUNDS:manifest#bounds"), ids(r), "not an unread survey cell: a position off the site");
        assertEquals("1000,64,1000", r.issues().get(0).data().get("first"));
    }

    @Test
    void aRegisteredPositionNowHoldingSomeoneElsesBlockIsBlocked() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> gold = new HashMap<>();
        gold.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:gold_block")));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, gold), LIMITS, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY,
                pos -> pos.equals(new IntPos(0, 64, 0)) ? Optional.of(BlockSpec.of("minecraft:oak_planks")) : Optional.empty());
        assertEquals(List.of("E-SITE-BLOCKED:wall#not_replaceable"), ids(r), "ownership needs our block to still be there");
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.BlockToItemTest" --tests "io.github.khayashi4337.micradrone.construction.core.SafetyEnvelopeTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`ItemCount.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** A number of one item (an item id, not a block id). */
public record ItemCount(String itemId, int count) {
    public ItemCount {
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("an item count must be positive: " + count);
        }
    }

    /** The items of both lists added up, in item-id order. */
    public static List<ItemCount> plus(List<ItemCount> a, List<ItemCount> b) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        a.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        b.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        return list(sum);
    }

    /** What is left of {@code a} after taking {@code b} away, never below zero, in item-id order (what is still owed). */
    public static List<ItemCount> minus(List<ItemCount> a, List<ItemCount> b) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        a.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        b.forEach(c -> sum.merge(c.itemId(), -c.count(), Integer::sum));
        return list(sum);
    }

    private static List<ItemCount> list(TreeMap<String, Integer> sum) {
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> {
            if (n > 0) {
                out.add(new ItemCount(id, n));
            }
        });
        return out;
    }
}
```
`BlockToItem.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.compile.gen.BlockForms;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which item a placed block costs (04 F-7, the BlockToItem table) and which item cutting natural ground gives the owner.
 * The rule: a block costs exactly what breaking it gives back (its loot table without silk touch), so a rollback
 * returns what a build took. Mostly the block's own item; the exceptions are listed here: blocks with no item of their
 * own (IMPLICIT parts such as Create's powered shaft and belt, wall-mounted forms, redstone wire) cost the item they
 * drop, and the second block of a two-block piece (a door's upper half, a tall flower's top, a bed's head) costs
 * nothing. Whether an item exists in the game is the adapter's check (ItemCatalog).
 */
public final class BlockToItem {
    /** Blocks whose drop is another item (Create's loot tables: data/create/loot_table/blocks/{belt,powered_shaft}.json). */
    private static final Map<String, String> ITEM_OF_BLOCK = Map.of(
            "create:belt", "create:belt_connector",
            "create:powered_shaft", "create:shaft",
            "minecraft:redstone_wire", "minecraft:redstone",
            "minecraft:wall_torch", "minecraft:torch",
            "minecraft:soul_wall_torch", "minecraft:soul_torch",
            "minecraft:redstone_wall_torch", "minecraft:redstone_torch");
    /** Wall-mounted forms that drop their standing item: {@code *_wall_banner} gives {@code *_banner}, and so on. */
    private static final Map<String, String> WALL_FORM_SUFFIXES = Map.of(
            "_wall_hanging_sign", "_hanging_sign",
            "_wall_banner", "_banner",
            "_wall_head", "_head",
            "_wall_skull", "_skull");
    /** Two-block plants whose upper half drops nothing (the lower half drops the item). */
    private static final java.util.Set<String> TALL_PLANTS = java.util.Set.of("minecraft:sunflower", "minecraft:lilac",
            "minecraft:rose_bush", "minecraft:peony", "minecraft:tall_grass", "minecraft:large_fern");
    private static final String PROP_PART = "part";
    private static final String BED_HEAD = "head";
    private static final String BED_SUFFIX = "_bed";
    /** A double slab is two slab items in one block. */
    private static final int ITEMS_PER_DOUBLE_SLAB = 2;
    /** What mining the ground gives (vanilla loot without silk touch), so cutting neither makes nor loses resources. */
    private static final Map<String, String> CUT_YIELD = Map.of(
            "minecraft:grass_block", "minecraft:dirt",
            "minecraft:dirt_path", "minecraft:dirt",
            "minecraft:farmland", "minecraft:dirt",
            "minecraft:mycelium", "minecraft:dirt",
            "minecraft:podzol", "minecraft:dirt",
            "minecraft:stone", "minecraft:cobblestone",
            "minecraft:deepslate", "minecraft:cobbled_deepslate");

    private BlockToItem() {
    }

    public static Optional<ItemCount> cost(BlockSpec block) {
        String id = block.blockId();
        if (block.isAir()) {
            return Optional.empty();
        }
        boolean upper = BlockForms.HALF_UPPER.equals(block.get(BlockForms.PROP_HALF));
        if (upper && (id.endsWith(BlockForms.DOOR_SUFFIX) || TALL_PLANTS.contains(id))) {
            return Optional.empty();
        }
        if (id.endsWith(BED_SUFFIX) && BED_HEAD.equals(block.get(PROP_PART))) {
            return Optional.empty();
        }
        int count = BlockForms.TYPE_DOUBLE.equals(block.get(BlockForms.PROP_TYPE)) && id.endsWith(BlockForms.SLAB_SUFFIX)
                ? ITEMS_PER_DOUBLE_SLAB : 1;
        String item = ITEM_OF_BLOCK.getOrDefault(id, id);
        if (item.endsWith(BlockForms.WALL_SIGN_SUFFIX)) {
            item = item.substring(0, item.length() - BlockForms.WALL_SIGN_SUFFIX.length()) + BlockForms.SIGN_SUFFIX;
        }
        for (Map.Entry<String, String> wall : WALL_FORM_SUFFIXES.entrySet()) {
            if (item.endsWith(wall.getKey())) {
                item = item.substring(0, item.length() - wall.getKey().length()) + wall.getValue();
            }
        }
        return Optional.of(new ItemCount(item, count));
    }

    public static Optional<ItemCount> cutYield(BlockSpec block) {
        if (block.isAir()) {
            return Optional.empty();
        }
        return Optional.of(new ItemCount(CUT_YIELD.getOrDefault(block.blockId(), block.blockId()), 1));
    }

    public static List<ItemCount> merge(List<ItemCount> items) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        for (ItemCount c : items) {
            sum.merge(c.itemId(), c.count(), Integer::sum);
        }
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> out.add(new ItemCount(id, n)));
        return out;
    }
}
```
(`BlockForms`の定数は既存の`BomCalculator`が使っている物で、すでに`public static final`(`gen/BlockForms.java` 15〜33行)。変えない。)

`BomCalculator.bom`の本体を、次に置き換える(`ITEM_OF_BLOCK`・`ITEMS_PER_DOUBLE_SLAB`は`BlockToItem`へ移したので消す):
```java
    public static Map<String, Integer> bom(List<Placement> placements) {
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Placement p : placements) {
            BlockToItem.cost(p.block()).ifPresent(c -> out.merge(c.itemId(), c.count(), Integer::sum));
        }
        return Collections.unmodifiableSortedMap(out);
    }
```

`SafetyLimits.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** The safety envelope's limits (04 F-5). The server config may relax them for operators. */
public record SafetyLimits(int maxPlacements, int maxSizeX, int maxSizeY, int maxSizeZ, int minBuildY, int maxBuildYExclusive) {
    public static final int DEFAULT_MAX_PLACEMENTS = 20_000;
    public static final int DEFAULT_MAX_SIZE_X = 128;
    public static final int DEFAULT_MAX_SIZE_Y = 96;
    public static final int DEFAULT_MAX_SIZE_Z = 128;

    public SafetyLimits {
        if (maxPlacements <= 0 || maxSizeX <= 0 || maxSizeY <= 0 || maxSizeZ <= 0 || minBuildY >= maxBuildYExclusive) {
            throw new IllegalArgumentException("bad safety limits");
        }
    }

    public static SafetyLimits defaults(int minBuildY, int maxBuildYExclusive) {
        return new SafetyLimits(DEFAULT_MAX_PLACEMENTS, DEFAULT_MAX_SIZE_X, DEFAULT_MAX_SIZE_Y, DEFAULT_MAX_SIZE_Z, minBuildY,
                maxBuildYExclusive);
    }
}
```
`PlacementSurvey.java`: `public record PlacementSurvey(Map<IntPos, WorldCell> cells) { public PlacementSurvey { cells = Map.copyOf(cells); } }`(javadoc: 施工リストの位置だけを、承認の前に読んだ物。キーが無い位置は読めなかった=未読み込み)。`ItemCatalog.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** Whether an item id exists in the running game (the adapter reads the item registry). */
@FunctionalInterface
public interface ItemCatalog {
    ItemCatalog ANY = itemId -> true;

    boolean exists(String itemId);
}
```
`ReplacementSummary.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;

/** What the approval screen must show and have confirmed (04 F-5): destructive replacements and terraforming. */
public record ReplacementSummary(int fluids, int leaves, int emptyContainers, int terrainCut, int terrainFill,
                                 List<IntPos> destructiveSample) {
    /** How many destructive positions the approval screen lists; the counts above are always complete. */
    public static final int SAMPLE_LIMIT = 16;

    public ReplacementSummary {
        destructiveSample = List.copyOf(destructiveSample);
    }

    public boolean needsDestructiveConfirm() {
        return fluids + leaves + emptyContainers > 0;
    }

    public boolean needsTerraformConfirm() {
        return terrainCut + terrainFill > 0;
    }
}
```
`SafetyReport.java`: `public record SafetyReport(List<Issue> issues, ReplacementSummary replacements)`(`List.copyOf`、非null)。

`SafetyEnvelope.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockToItem;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The safety envelope (04 F-5), checked on the server before approval and before a single block is placed: size limits,
 * the build height, blocks that are always forbidden (again, server-side), items the game does not know, and every
 * position's replacement verdict on the pinned survey (the same ReplaceRules the executor uses later).
 */
public final class SafetyEnvelope {
    static final String SUBJECT_MANIFEST = "manifest";
    static final String KEY_PLACEMENTS = "placements";
    static final String KEY_SIZE = "size";
    static final String KEY_HEIGHT = "height";
    static final String KEY_BOUNDS = "bounds";
    static final String KEY_UNLOADED = "unloaded";
    static final String DATA_COUNT = "count";
    static final String DATA_LIMIT = "limit";
    static final String DATA_FIRST = "first";

    private SafetyEnvelope() {
    }

    public static SafetyReport check(PlacementManifest m, TerrainSummary terrain, PlacementSurvey survey, SafetyLimits limits,
                                     PlaceableBlockPolicy policy, ItemCatalog items,
                                     Function<IntPos, Optional<BlockSpec>> ownPlaced) {
        List<Issue> issues = new ArrayList<>();
        int n = m.placements().size();
        if (n > limits.maxPlacements()) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_PLACEMENTS, List.of(SUBJECT_MANIFEST),
                    "置くブロックが多すぎます(" + n + "個。上限は" + limits.maxPlacements() + "個)",
                    Map.of(DATA_COUNT, String.valueOf(n), DATA_LIMIT, String.valueOf(limits.maxPlacements())), List.of()));
        }
        Box b = m.worldBounds();
        long sx = (long) b.maxA() - b.minA() + 1;
        long sy = (long) b.maxB() - b.minB() + 1;
        long sz = (long) b.maxC() - b.minC() + 1;
        if (sx > limits.maxSizeX() || sy > limits.maxSizeY() || sz > limits.maxSizeZ()) {
            String size = sx + "x" + sy + "x" + sz;
            String max = limits.maxSizeX() + "x" + limits.maxSizeY() + "x" + limits.maxSizeZ();
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_SIZE, List.of(SUBJECT_MANIFEST),
                    "施工の範囲が大きすぎます(" + size + "。上限は" + max + ")", Map.of(DATA_COUNT, size, DATA_LIMIT, max),
                    List.of()));
        }
        int outsideHeight = 0;
        IntPos firstOutside = null;
        int outsideSite = 0;
        IntPos firstOutsideSite = null;
        Map<String, TreeSet<String>> forbidden = new TreeMap<>();
        Map<String, TreeSet<String>> unknownItems = new TreeMap<>();
        Map<String, int[]> blockedCount = new TreeMap<>();
        Map<String, IntPos> blockedFirst = new TreeMap<>();
        int unloaded = 0;
        IntPos firstUnloaded = null;
        int fluids = 0;
        int leaves = 0;
        int empty = 0;
        List<IntPos> sample = new ArrayList<>();
        for (Placement p : m.placements()) {
            IntPos pos = p.pos();
            if (!b.contains(pos.x(), pos.y(), pos.z())) {
                // the site box is what the claim, the survey and the size limits cover: nothing is placed outside it
                outsideSite++;
                firstOutsideSite = firstOutsideSite == null ? pos : firstOutsideSite;
                continue;
            }
            if (pos.y() < limits.minBuildY() || pos.y() >= limits.maxBuildYExclusive()) {
                outsideHeight++;
                firstOutside = firstOutside == null ? pos : firstOutside;
            }
            String id = p.block().blockId();
            if (policy.isAlwaysForbidden(id)) {
                forbidden.computeIfAbsent(id, k -> new TreeSet<>()).add(p.partNodeId());
            }
            BlockToItem.cost(p.block()).ifPresent(c -> {
                if (!items.exists(c.itemId())) {
                    unknownItems.computeIfAbsent(c.itemId(), k -> new TreeSet<>()).add(p.partNodeId());
                }
            });
            WorldCell cell = survey.cells().get(pos);
            if (cell == null || !cell.loaded()) {
                unloaded++;
                firstUnloaded = firstUnloaded == null ? pos : firstUnloaded;
                continue;
            }
            ReplaceDecision d = ReplaceRules.decide(p, cell, false, ownPlaced.apply(pos));
            if (d instanceof ReplaceDecision.Refused r) {
                String key = p.partNodeId() + "#" + r.refusal().name().toLowerCase(Locale.ROOT);
                blockedCount.computeIfAbsent(key, k -> new int[1])[0]++;
                blockedFirst.putIfAbsent(key, pos);
            } else if (d instanceof ReplaceDecision.Place place && place.destruction().needsDestructiveConfirm()) {
                switch (place.destruction()) {
                    case FLUID -> fluids++;
                    case LEAVES -> leaves++;
                    default -> empty++;
                }
                if (sample.size() < ReplacementSummary.SAMPLE_LIMIT) {
                    sample.add(pos);
                }
            }
        }
        if (outsideSite > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_BOUNDS, List.of(SUBJECT_MANIFEST),
                    "敷地の範囲の外に、置く位置が" + outsideSite + "個あります",
                    Map.of(DATA_COUNT, String.valueOf(outsideSite), DATA_FIRST, text(firstOutsideSite)), List.of()));
        }
        if (outsideHeight > 0) {
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, KEY_HEIGHT, List.of(SUBJECT_MANIFEST),
                    "ワールドの建てられる高さの外に、" + outsideHeight + "個のブロックがあります",
                    Map.of(DATA_COUNT, String.valueOf(outsideHeight), DATA_FIRST, text(firstOutside)), List.of()));
        }
        forbidden.forEach((id, nodes) -> issues.add(Issue.of(IssueCode.E_BLOCK_FORBIDDEN, id, new ArrayList<>(nodes),
                id + "は、置いてはいけないブロックです")));
        unknownItems.forEach((item, nodes) -> issues.add(Issue.of(IssueCode.E_MATERIAL_UNKNOWN, item, new ArrayList<>(nodes),
                item + "は、このゲームに無い品物です(材料の対応表に無い)")));
        blockedCount.forEach((key, count) -> {
            String node = key.substring(0, key.indexOf('#'));
            String reason = key.substring(key.indexOf('#') + 1);
            issues.add(Issue.of(IssueCode.E_SITE_BLOCKED, reason, List.of(node),
                    node + "を置く場所に、置き換えられないブロックが" + count[0] + "個あります(最初は " + text(blockedFirst.get(key))
                            + ")", Map.of(DATA_COUNT, String.valueOf(count[0]), DATA_FIRST, text(blockedFirst.get(key))),
                    List.of()));
        });
        if (unloaded > 0) {
            issues.add(Issue.of(IssueCode.E_SITE_BLOCKED, KEY_UNLOADED, List.of(SUBJECT_MANIFEST),
                    "読み込まれていない場所が" + unloaded + "個あります。近づいてから、もう一度送ってください",
                    Map.of(DATA_COUNT, String.valueOf(unloaded), DATA_FIRST, text(firstUnloaded)), List.of()));
        }
        return new SafetyReport(issues, new ReplacementSummary(fluids, leaves, empty, terrain.cut(), terrain.fill(), sample));
    }

    static String text(IntPos p) {
        return p.x() + "," + p.y() + "," + p.z();
    }
}
```
(`Issue.of(code, key, subjects, message)`の4引数版は既存。`data`が要る物は6引数版を使う。発行の順は、上限→範囲外(`#bounds`)→高さ→禁止→未知の品物→置換不可→未読み込み。同じ種類の中は`TreeMap`のキー順で、決定論。**施工の範囲(`worldBounds`)の外の置く位置は、調査を見る前に`E-OUT-OF-BOUNDS#bounds`で断る**(区画・調査・大きさの上限はどれも範囲の箱で決まるので、箱の外は何の検査も受けていない。Codexのレビュー2026-09-30)。所有の判定は`ownPlaced`(登録簿の記録のブロック)を`ReplaceRules`に渡す(Task 4 修正ラウンド)。**品物の対応の規則**: ブロックの値段は、そのブロックを壊したときに出る品物(シルクタッチなしのルートテーブル)と同じにする。だからロールバックは、建てた時に取った物をちょうど返す。自分の品物を持たないブロック(`IMPLICIT`の部品: Createの`powered_shaft`は`create:shaft`、`belt`は`create:belt_connector`。どちらもCreateのjarの`data/create/loot_table/blocks/{powered_shaft,belt}.json`で確認)、壁に付く形(看板・吊り看板・旗・頭・たいまつ)、レッドストーンの線は、出る品物の名前で数え、2ブロックで1つの物の片方(扉の上・背の高い花の上・ベッドの頭)は数えない。部品の種類を見ずに、置くブロックの状態だけで決まる(ブロックIDと状態が同じなら値段も同じなので、部品を知る必要がない)。)

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(既存の`BomCalculatorTest`・`GoldenHutTest`を含む。材料表の数が変わっていないこと)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/build/compile の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/build/compile/BlockToItemTest.java
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ブロックと品物の対応(BlockToItem)と、承認前の安全枠(SafetyEnvelope)を追加(自然言語→工場建設 P4 Task 6)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 7: 施工予算(`ConstructionBudget`)— 同時ジョブ・1tickの上限・ドローンの間隔・自動減速

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{BudgetConfig,BudgetJob,QueuedJob,Allowance,BudgetTick,ConstructionBudget}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{ConstructionBudgetTest,DroneCadenceSyncTest}.java`

**Interfaces:**
- Consumes: `java.*`のみ
- Produces: `record BudgetConfig(int maxRunningJobs, int maxJobsPerOwner, int maxPlacementsPerTick, int fastPlacementsPerTick, int droneIntervalTicks, int placementsPerDrone, int minDrones, int maxDrones, double slowdownAboveMspt, double recoverBelowMspt, int slowdownFactor)` + `static BudgetConfig defaults()`。定数(出どころ): `DEFAULT_MAX_RUNNING_JOBS = 4`(F-2(a))、`DEFAULT_MAX_JOBS_PER_OWNER = 1`(F-5の「同時ジョブ: 所有者ごとに1」)、`DEFAULT_MAX_PLACEMENTS_PER_TICK = 32`(F-2(b))、`DEFAULT_FAST_PLACEMENTS_PER_TICK = 16`(F-2の構造の高速施工)、`DRONE_INTERVAL_TICKS = 4`(F-2。既存の`LiveDroneApi.ACTION_DELAY_TICKS`と同じ値)、`PLACEMENTS_PER_DRONE = 300`・`MIN_DRONES = 1`・`MAX_DRONES = 6`(F-2の`clamp(ceil(総数/300),1,6)`)、`SLOWDOWN_ABOVE_MSPT = 45.0`(F-2(c))、`RECOVER_BELOW_MSPT = 40.0`(設計に数値なし。閾値の上下で速度が振動しないよう、5ms下で戻す。Task 35で設計に書く)、`SLOWDOWN_FACTOR = 2`(F-2(c)の「半分」)。`record BudgetJob(String jobId, UUID owner, boolean fastPhase, int droneCount, long lastDroneTick, long admittedOrder)`、`record QueuedJob(String jobId, UUID owner)`、`record Allowance(String jobId, int placements)`、`record BudgetTick(List<Allowance> allowances, boolean slowed)`、`ConstructionBudget.admit`・`allocate`・`droneCount`・`etaTicks`・`boolean slowed()`。

- [ ] **Step 1: 失敗するテストを書く**

`ConstructionBudgetTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstructionBudgetTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final UUID E = new UUID(0, 5);
    private static final double CALM = 20.0;

    private static BudgetJob fast(String id, UUID owner, long order) {
        return new BudgetJob(id, owner, true, 1, 0L, order);
    }

    private static BudgetJob drone(String id, UUID owner, int drones, long lastDroneTick, long order) {
        return new BudgetJob(id, owner, false, drones, lastDroneTick, order);
    }

    private static int given(BudgetTick t, String id) {
        return t.allowances().stream().filter(a -> a.jobId().equals(id)).mapToInt(Allowance::placements).sum();
    }

    @Test
    void droneCountFollowsTheDesignFormula() {
        BudgetConfig c = BudgetConfig.defaults();
        assertEquals(1, ConstructionBudget.droneCount(0, c));
        assertEquals(1, ConstructionBudget.droneCount(238, c));
        assertEquals(1, ConstructionBudget.droneCount(300, c));
        assertEquals(2, ConstructionBudget.droneCount(301, c));
        assertEquals(6, ConstructionBudget.droneCount(20_000, c));
    }

    @Test
    void etaCountsFastPlacementsPerTickAndDroneWorkEveryFourTicks() {
        BudgetConfig c = BudgetConfig.defaults();
        assertEquals(10L + 12L * 4L, ConstructionBudget.etaTicks(160, 12, c), "160 fast at 16/tick; 12 slow with 1 drone");
        assertEquals(0L, ConstructionBudget.etaTicks(0, 0, c));
    }

    @Test
    void atMostFourJobsRunAndEachOwnerRunsOneInArrivalOrder() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<QueuedJob> queue = List.of(new QueuedJob("a1", A), new QueuedJob("a2", A), new QueuedJob("b1", B),
                new QueuedJob("c1", C), new QueuedJob("d1", D), new QueuedJob("e1", E));
        assertEquals(List.of("a1", "b1", "c1", "d1"), b.admit(queue, List.of()),
                "a2 waits for its owner's first job; e1 waits for a free slot");
        assertEquals(List.of("e1"), b.admit(List.of(new QueuedJob("a2", A), new QueuedJob("e1", E)),
                List.of(fast("a1", A, 0), fast("b1", B, 1), fast("c1", C, 2))));
    }

    @Test
    void theServerWideCapIsSharedFairlyOverTicks() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<BudgetJob> four = List.of(fast("a", A, 0), fast("b", B, 1), fast("c", C, 2), fast("d", D, 3));
        Map<String, Integer> total = new HashMap<>();
        for (long tick = 0; tick < 4; tick++) {
            BudgetTick t = b.allocate(tick, CALM, four);
            int sum = t.allowances().stream().mapToInt(Allowance::placements).sum();
            assertTrue(sum <= BudgetConfig.DEFAULT_MAX_PLACEMENTS_PER_TICK, "never more than 32 per tick");
            for (BudgetJob j : four) {
                total.merge(j.jobId(), given(t, j.jobId()), Integer::sum);
            }
        }
        assertEquals(Map.of("a", 32, "b", 32, "c", 32, "d", 32), total, "rotation gives every job the same share");
    }

    @Test
    void droneJobsPlaceOnePerDroneEveryFourTicks() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        assertEquals(3, given(b.allocate(10, CALM, List.of(drone("a", A, 3, 6, 0))), "a"));
        assertEquals(0, given(b.allocate(9, CALM, List.of(drone("a", A, 3, 6, 0))), "a"), "only 3 ticks since the last");
    }

    @Test
    void aSlowServerHalvesEverythingUntilItRecovers() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<BudgetJob> one = List.of(fast("a", A, 0));
        assertEquals(16, given(b.allocate(0, CALM, one), "a"));
        BudgetTick slow = b.allocate(1, 46.0, one);
        assertTrue(slow.slowed());
        assertEquals(8, given(slow, "a"));
        assertTrue(b.allocate(2, 42.0, one).slowed(), "still above the recovery line: stay slow (no flapping)");
        assertEquals(0, given(b.allocate(7, 42.0, List.of(drone("d", A, 2, 0, 0))), "d"),
                "slowed drones wait 8 ticks, 7 is not enough");
        BudgetTick back = b.allocate(8, 39.0, one);
        assertFalse(back.slowed());
        assertEquals(16, given(back, "a"));
    }
}
```

`DroneCadenceSyncTest.java`(既存の畑のドローンと同じ間隔であることを、ソースの文字列で機械的に確かめる。`LiveDroneApi`は変えない):
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** F-2: construction drones place at the farm drone's pace. LiveDroneApi's constant is private, so its source is read. */
class DroneCadenceSyncTest {
    @Test
    void theDroneIntervalMatchesTheFarmDrone() throws IOException {
        String src = Files.readString(Path.of("src/main/java/io/github/khayashi4337/micradrone/drone/LiveDroneApi.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("ACTION_DELAY_TICKS = " + BudgetConfig.DRONE_INTERVAL_TICKS + ";"),
                "LiveDroneApi.ACTION_DELAY_TICKS changed; update BudgetConfig.DRONE_INTERVAL_TICKS and design 04 F-2 together");
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Budget*" --tests "io.github.khayashi4337.micradrone.construction.core.DroneCadenceSyncTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`BudgetConfig.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** The server-wide construction budget (04 F-2). Defaults are the design's; the server config may change them. */
public record BudgetConfig(int maxRunningJobs, int maxJobsPerOwner, int maxPlacementsPerTick, int fastPlacementsPerTick,
                           int droneIntervalTicks, int placementsPerDrone, int minDrones, int maxDrones,
                           double slowdownAboveMspt, double recoverBelowMspt, int slowdownFactor) {
    public static final int DEFAULT_MAX_RUNNING_JOBS = 4;
    public static final int DEFAULT_MAX_JOBS_PER_OWNER = 1;
    public static final int DEFAULT_MAX_PLACEMENTS_PER_TICK = 32;
    public static final int DEFAULT_FAST_PLACEMENTS_PER_TICK = 16;
    /** The farm drone's LiveDroneApi.ACTION_DELAY_TICKS (DroneCadenceSyncTest keeps them equal). */
    public static final int DRONE_INTERVAL_TICKS = 4;
    public static final int PLACEMENTS_PER_DRONE = 300;
    public static final int MIN_DRONES = 1;
    public static final int MAX_DRONES = 6;
    public static final double SLOWDOWN_ABOVE_MSPT = 45.0;
    /** Not in the design: 5 ms below the slowdown line, so the speed does not flap around one threshold. */
    public static final double RECOVER_BELOW_MSPT = 40.0;
    public static final int SLOWDOWN_FACTOR = 2;

    public BudgetConfig {
        if (maxRunningJobs < 1 || maxJobsPerOwner < 1 || maxPlacementsPerTick < 1 || fastPlacementsPerTick < 1
                || droneIntervalTicks < 1 || placementsPerDrone < 1 || minDrones < 1 || maxDrones < minDrones
                || recoverBelowMspt > slowdownAboveMspt || slowdownFactor < 1) {
            throw new IllegalArgumentException("bad construction budget");
        }
    }

    public static BudgetConfig defaults() {
        return new BudgetConfig(DEFAULT_MAX_RUNNING_JOBS, DEFAULT_MAX_JOBS_PER_OWNER, DEFAULT_MAX_PLACEMENTS_PER_TICK,
                DEFAULT_FAST_PLACEMENTS_PER_TICK, DRONE_INTERVAL_TICKS, PLACEMENTS_PER_DRONE, MIN_DRONES, MAX_DRONES,
                SLOWDOWN_ABOVE_MSPT, RECOVER_BELOW_MSPT, SLOWDOWN_FACTOR);
    }
}
```
`BudgetJob.java`(javadoc: `fastPhase`=今のカーソルの工程が`SITE_PREP`〜`ENVELOPE`、`lastDroneTick`=ドローンが最後に置いたtick、`admittedOrder`=実行に入った順)、`QueuedJob.java`、`Allowance.java`、`BudgetTick.java`(`allowances`は`List.copyOf`)はrecordのみ。

`ConstructionBudget.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The server-wide construction budget (04 F-2): at most N running jobs (one per owner), a per-tick cap shared by
 * rotating the starting job every tick, the drones' pace for the visible phases, and an automatic halving of every rate
 * while the average tick time is above the line, back to normal once it is below the (lower) recovery line.
 */
public final class ConstructionBudget {
    private final BudgetConfig config;
    private boolean slowed;

    public ConstructionBudget(BudgetConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static int droneCount(int totalPlacements, BudgetConfig c) {
        int wanted = (int) Math.ceil(totalPlacements / (double) c.placementsPerDrone());
        return Math.max(c.minDrones(), Math.min(c.maxDrones(), wanted));
    }

    /** Ticks to finish {@code fast} structure placements and {@code slow} drone-shown placements at the default pace. */
    public static long etaTicks(int fast, int slow, BudgetConfig c) {
        int fastRate = Math.min(c.fastPlacementsPerTick(), c.maxPlacementsPerTick());
        long fastTicks = (fast + fastRate - 1L) / fastRate;
        int drones = droneCount(fast + slow, c);
        long droneRounds = (slow + drones - 1L) / drones;
        return fastTicks + droneRounds * c.droneIntervalTicks();
    }

    public boolean slowed() {
        return slowed;
    }

    /** The queued jobs that may start now, in arrival order: free slots first, and one running job per owner. */
    public List<String> admit(List<QueuedJob> queuedInArrivalOrder, List<BudgetJob> running) {
        Map<UUID, Integer> perOwner = new HashMap<>();
        for (BudgetJob j : running) {
            perOwner.merge(j.owner(), 1, Integer::sum);
        }
        int free = config.maxRunningJobs() - running.size();
        List<String> start = new ArrayList<>();
        for (QueuedJob q : queuedInArrivalOrder) {
            if (free <= 0) {
                break;
            }
            if (perOwner.getOrDefault(q.owner(), 0) >= config.maxJobsPerOwner()) {
                continue;
            }
            perOwner.merge(q.owner(), 1, Integer::sum);
            start.add(q.jobId());
            free--;
        }
        return start;
    }

    public BudgetTick allocate(long tick, double averageMspt, List<BudgetJob> running) {
        if (!slowed && averageMspt > config.slowdownAboveMspt()) {
            slowed = true;
        } else if (slowed && averageMspt < config.recoverBelowMspt()) {
            slowed = false;
        }
        int factor = slowed ? config.slowdownFactor() : 1;
        int left = Math.max(1, config.maxPlacementsPerTick() / factor);
        int fastCap = Math.max(1, config.fastPlacementsPerTick() / factor);
        long interval = (long) config.droneIntervalTicks() * factor;
        List<BudgetJob> order = new ArrayList<>(running);
        order.sort(Comparator.comparingLong(BudgetJob::admittedOrder));
        List<Allowance> out = new ArrayList<>();
        int n = order.size();
        for (int k = 0; k < n; k++) {
            BudgetJob j = order.get((int) ((tick + k) % n));
            int want = j.fastPhase() ? fastCap : (tick - j.lastDroneTick() >= interval ? j.droneCount() : 0);
            int give = Math.min(want, left);
            if (give > 0) {
                out.add(new Allowance(j.jobId(), give));
                left -= give;
            }
        }
        return new BudgetTick(out, slowed);
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 施工予算(同時4ジョブ・所有者ごと1・1tick32個の公平な分配・ドローンの間隔・自動減速)を追加(自然言語→工場建設 P4 Task 7)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 8: 記録と台帳(`Journal`・`PlacedRegistry`・`MaterialLedger`・`JobOutcome`・`JobProgram`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{JournalRecord,UndoEntry,Journal,PlacedEntry,PlacedRegistry,AssemblyResult,MaterialOp,MaterialLedger,LedgerBook,SkippedPlacement,JobOutcome,PutItem,RestoreItem,JobProgram}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{JournalTest,PlacedRegistryTest,MaterialLedgerTest,JobOutcomeTest,JobProgramTest}.java`

**Interfaces:**
- Consumes: `build.compile.{Placement,PlacementManifest,Conflict,ItemCount}`、`build.model.{BlockSpec,IntPos}`、Task 3の`ConstructionJob.MAX_REPAIR_ROUNDS`、Task 6の`SafetyLimits.DEFAULT_MAX_PLACEMENTS`
- Produces:
  - `record JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed, int ledgerKey, boolean terrainCut)` + 6引数のコンストラクタ(`terrainCut=false`)(`terrainCut`: その設置が自然の地面を切った=所有者に地面の品物を渡す義務がある。記録だけから、まだ渡していない分を決めるため。`NO_LEDGER_KEY = -1`: 撤去の記録。`static int restoreIndex(int programIndex)`=`-1 - programIndex`: 撤去の記録の番号は負にして、同じジョブの追加の番号とぶつけない)
  - `record UndoEntry(IntPos pos, BlockSpec before)`(`01` 8節のまま)
  - `final class Journal`: `Journal(int maxEntries)`・`Journal()`(`MAX_ENTRIES = SafetyLimits.DEFAULT_MAX_PLACEMENTS`、F-2の「最大20,000件」。`JobService`は修復のラウンドの分まで`(1 + MAX_REPAIR_ROUNDS)`倍の大きさで作る)、`boolean record(JournalRecord)`(同じ番号は**最初の1件だけ**残す=施工前の状態を上書きしない。**置く記録の番号は台帳のキー**(`PutItem.ledgerKey()`。ラウンド0では施工リストの番号と同じ)なので、修復のラウンドの置き直しは、それぞれ自分の記録になる。Codexのレビュー2026-09-30: 同じ番号を共有すると「最初の記録がある=このラウンドの修復が済んだ」と誤る)、`Optional<JournalRecord> at(int)`、`int size()`、`List<JournalRecord> records()`(番号順)、`List<UndoEntry> undo()`(上から下=y降順、同じ高さはz→x昇順)
  - `record PlacedEntry(BlockSpec placed, BlockSpec before, String jobId, int placementIndex, List<Integer> earlierKeys)`+4引数の形(`earlierKeys`は空)(`01` 11.1に1つ足す。`placementIndex`には台帳のキーを入れる。`earlierKeys`は**同じジョブが同じ位置に前に書いた台帳のキー**: 整地で切った地面の上に基礎を置くと、切った地面の引き渡し(`YIELD`)は前のキー、基礎の請求は後のキーに付くので、撤去は両方を精算する。Codexの3回目のレビューの後の検証(新しい`CrashRecoveryTest`)で見つけた。別のジョブが上書きしたら空に戻す=その台帳は別)
  - `final class PlacedRegistry`: `SCHEMA_VERSION = 1`、`PlacedRegistry(String claimId)`、`void apply(String jobId, JournalRecord r)`(**撤去の記録(`NO_LEDGER_KEY`)は、記録の無い位置に新しい記録を作らない**: クラッシュの後に撤去をやり直しても、戻した地面を「このプロジェクトの物」にしない)、`Optional<PlacedEntry> at(IntPos)`、`boolean contains(IntPos)`、`Map<IntPos,PlacedEntry> placed()`(読み取り専用)、`Map<String,AssemblyResult> assemblies()`、`void putAll(Map<IntPos,PlacedEntry>)`(復元用)、`void putAssembly(AssemblyResult)`(組み立ての個体。復元とP10・P13用)、`String claimId()`、`int size()`
  - `record AssemblyResult(String groupId, String assembledId, int movedBlockCount, long atTick)`(`01` 4節。P4では記録が生じない。P10・P13の組み立てが書く)
  - `enum MaterialOp {CHARGE, YIELD, RECLAIM, RETURN}` + `boolean takesFromOwner()`
  - `final class MaterialLedger`(**個数で持つ**。クラッシュで操作の一部だけが残ることがある(持ち物はすぐ保存されるが、補給チェストはチャンクと一緒にしか保存されない)ので、「済んだか」の印ではなく「どれだけ済んだか」を持ち、実行は「まだ借りている分」だけを動かす。Codexのレビュー2026-09-30): `SCHEMA_VERSION = 1`、`NO_RUN = -1`、`consumed/isConsumed/recordConsumed(key, items)`(足し算)、`returned/owedReturn/isReturned/recordReturned(key, items)`・`recordReturned(key)`(残り全部)、`yielded/isYielded/recordYield`、`reclaimed/owedReclaim/isReclaimed/recordReclaimed(key, items)`・`recordReclaimed(key)`、`record(MaterialOp, key, items)`、`replay(MaterialOp, key, items)`・`unrecord(MaterialOp, key, items)`(復旧だけが使う)、`appliedRun()`・`markAppliedRun(long)`(このスナップショットに入っている最後のログの回)、`SortedMap<Integer,List<ItemCount>> consumedView()`・`returnedView()`・`yieldedView()`・`reclaimedView()`。返却は消費を、取り返しは渡した分を超えられない
  - `final class LedgerBook`: `MaterialLedger of(String jobId)`(無ければ作る)、`Optional<MaterialLedger> find(String jobId)`、`Map<String,MaterialLedger> all()`
  - `record SkippedPlacement(int index, IntPos pos, String reason)`(`DENIED = "denied"`・`SITE_CHANGED = "site-changed"`・`INVALID = "invalid"`・`CONFLICT = "conflict"`・`ALREADY_THERE = "already-there"`)。**手順のどの位置も、終わったら「記録(`Journal`)がある」か「飛ばした記録(`SkippedPlacement`)がある」のどちらか**になる(Task 24の復旧の検査が使う不変条件)
  - `final class JobOutcome`: `boolean addConflict(Conflict)`(同じ位置は1回)、`boolean addRestoreConflict(Conflict)`・`boolean hasRestoreConflictAt(IntPos)`(撤去で触らなかった位置。同じ位置への後の設置を止める)、`void resolveConflictAt(IntPos)`(置けなかった位置が後で置けたら、その報告を消す。撤去の`Conflict`は消さない)、`void skip(SkippedPlacement)`、`List<Conflict> conflicts()`、`List<SkippedPlacement> skipped()`、`Set<Integer> deniedIndexes()`、`boolean isSkipped(int index)`
  - `record PutItem(int index, int ledgerKey, Placement placement)`、`record RestoreItem(IntPos pos, BlockSpec expectedNow, Set<String> volatileProps, BlockSpec restoreTo, String sourceJobId, int sourceLedgerKey, boolean dropContents, List<Integer> earlierSourceKeys)`+7引数の形(`earlierSourceKeys`は空)・`List<Integer> sourceKeys()`(前のキー、最後に`sourceLedgerKey`。撤去はこの全部を精算する。`RollbackPlanner`・`ModifyPlanner`は`PlacedEntry.earlierKeys()`を渡す)
  - `record JobProgram(List<RestoreItem> restores, List<PutItem> puts)`: `int size()`、`boolean isRestore(int)`、`RestoreItem restore(int)`、`PutItem put(int)`、`static JobProgram build(PlacementManifest)`、`static JobProgram repair(PlacementManifest, List<Integer> indexes, int round)`、`LEDGER_ROUND_STRIDE = Integer.MAX_VALUE / (ConstructionJob.MAX_REPAIR_ROUNDS + 1)`(修復のラウンドごとに台帳のキーを分ける。修復で置き直すブロックは、壊された分をもう一度消費するので、最初の消費と同じキーにしない)

- [ ] **Step 1: 失敗するテストを書く**

`JournalTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalTest {
    static JournalRecord rec(int index, int x, int y, int z, String before, String placed) {
        return new JournalRecord(index, new IntPos(x, y, z), BlockSpec.of(before), false, BlockSpec.of(placed), index);
    }

    @Test
    void theFirstRecordOfAPositionIsKeptSoThePreBuildStateSurvives() {
        Journal j = new Journal();
        assertTrue(j.record(rec(0, 0, 64, 0, "minecraft:air", "minecraft:stone")));
        assertFalse(j.record(rec(0, 0, 64, 0, "minecraft:stone", "minecraft:stone")), "a repeat never overwrites 'before'");
        assertEquals("minecraft:air", j.at(0).orElseThrow().before().blockId());
        assertEquals(1, j.size());
    }

    @Test
    void undoRunsTopDown() {
        Journal j = new Journal();
        j.record(rec(0, 1, 64, 0, "minecraft:air", "minecraft:stone"));
        j.record(rec(1, 0, 65, 1, "minecraft:air", "minecraft:stone"));
        j.record(rec(2, 0, 65, 0, "minecraft:grass_block", "minecraft:stone"));
        assertEquals(List.of(new UndoEntry(new IntPos(0, 65, 0), BlockSpec.of("minecraft:grass_block")),
                new UndoEntry(new IntPos(0, 65, 1), BlockSpec.AIR), new UndoEntry(new IntPos(1, 64, 0), BlockSpec.AIR)),
                j.undo());
    }

    @Test
    void theJournalHasACap() {
        Journal j = new Journal(2);
        j.record(rec(0, 0, 64, 0, "minecraft:air", "minecraft:stone"));
        j.record(rec(1, 1, 64, 0, "minecraft:air", "minecraft:stone"));
        assertThrows(IllegalStateException.class, () -> j.record(rec(2, 2, 64, 0, "minecraft:air", "minecraft:stone")));
        assertEquals(20_000, Journal.MAX_ENTRIES);
    }
}
```

`PlacedRegistryTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class PlacedRegistryTest {
    private static final IntPos P = new IntPos(0, 64, 0);

    @Test
    void theFirstPlacementRemembersThePreBuildBlockAndLaterChangesKeepIt() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.of("minecraft:stone"), 0));
        r.apply("job-2", new JournalRecord(4, P, BlockSpec.of("minecraft:stone"), false, BlockSpec.of("minecraft:oak_planks"), 4));
        PlacedEntry e = r.at(P).orElseThrow();
        assertEquals("minecraft:oak_planks", e.placed().blockId());
        assertEquals("minecraft:grass_block", e.before().blockId(), "the original ground, not the first build's stone");
        assertEquals("job-2", e.jobId(), "the latest placer's ledger answers for the current block");
        assertEquals(4, e.placementIndex());
    }

    @Test
    void restoringThePreBuildBlockForgetsThePosition() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        r.apply("job-9", new JournalRecord(0, P, BlockSpec.of("minecraft:stone"), false, BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));
        assertFalse(r.contains(P));
        assertEquals(0, r.size());
    }

    @Test
    void aRemovalRunAgainAfterACrashDoesNotClaimTheGround() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.of("minecraft:stone"), 0));
        JournalRecord undo = new JournalRecord(JournalRecord.restoreIndex(0), P, BlockSpec.of("minecraft:stone"), false,
                BlockSpec.of("minecraft:grass_block"), JournalRecord.NO_LEDGER_KEY);
        r.apply("job-2", undo);
        r.apply("job-2", undo);
        assertFalse(r.contains(P), "the grass put back is not a project block, however often the removal runs");
    }

    @Test
    void placingWhatWasAlreadyThereRecordsNothing() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.AIR, 0));
        assertFalse(r.contains(P), "an air placement on air is not a project block");
        assertTrue(r.assemblies().isEmpty());
    }

    @Test
    void theSameJobWritingAPositionAgainKeepsItsEarlierKey() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        // the ground cut (key 1, dirt handed over) and then the foundation on it (key 10)
        r.apply("job-1", new JournalRecord(1, P, grass, false, BlockSpec.AIR, 1, true));
        r.apply("job-1", new JournalRecord(10, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:cobblestone"), 10));
        PlacedEntry e = r.at(P).orElseThrow();
        assertEquals(10, e.placementIndex());
        assertEquals(java.util.List.of(1), e.earlierKeys(), "a removal settles the cut ground as well as the foundation");
        assertEquals(grass, e.before());
        r.apply("job-2", new JournalRecord(5, P, BlockSpec.of("minecraft:cobblestone"), false, BlockSpec.of("minecraft:stone"), 5));
        assertEquals(java.util.List.of(), r.at(P).orElseThrow().earlierKeys(), "another job's keys belong to its own ledger");
    }
}
```

`MaterialLedgerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialLedgerTest {
    private static final List<ItemCount> PLANK = List.of(new ItemCount("minecraft:oak_planks", 1));

    @Test
    void aChargeIsReturnedOnceAtMost() {
        MaterialLedger l = new MaterialLedger();
        assertFalse(l.isConsumed(3));
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3), "nothing to return before it was charged");
        l.recordConsumed(3, PLANK);
        assertTrue(l.isConsumed(3));
        assertEquals(PLANK, l.consumed(3));
        assertEquals(PLANK, l.owedReturn(3));
        l.recordReturned(3);
        assertTrue(l.isReturned(3));
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3), "never return twice");
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3, PLANK), "not even piece by piece");
        assertEquals(List.of(), l.consumed(4));
    }

    @Test
    void amountsAddUpSoAPartlyDurableChargeCanBeCompleted() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> two = List.of(new ItemCount("minecraft:oak_slab", 2));
        List<ItemCount> one = List.of(new ItemCount("minecraft:oak_slab", 1));
        l.recordConsumed(5, one);
        assertEquals(one, ItemCount.minus(two, l.consumed(5)), "one slab of the double slab is still owed");
        l.recordConsumed(5, one);
        assertEquals(two, l.consumed(5));
        l.unrecord(MaterialOp.CHARGE, 5, one);
        assertEquals(one, l.consumed(5), "recovery takes back only the part that never became durable");
        l.unrecord(MaterialOp.CHARGE, 5, one);
        assertFalse(l.isConsumed(5));
    }

    @Test
    void cutGroundIsGivenAndTakenBackOnce() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> dirt = List.of(new ItemCount("minecraft:dirt", 1));
        l.recordYield(7, dirt);
        assertTrue(l.isYielded(7));
        assertEquals(dirt, l.owedReclaim(7));
        l.recordReclaimed(7);
        assertTrue(l.isReclaimed(7));
        assertThrows(IllegalStateException.class, () -> l.recordReclaimed(7));
        assertThrows(IllegalArgumentException.class, () -> l.recordConsumed(8, List.of()), "an empty charge is a bug");
    }

    @Test
    void theAppliedRunOnlyMovesForward() {
        MaterialLedger l = new MaterialLedger();
        assertEquals(MaterialLedger.NO_RUN, l.appliedRun());
        l.markAppliedRun(4);
        l.markAppliedRun(2);
        assertEquals(4, l.appliedRun());
    }

    @Test
    void theBookHandsOutOneLedgerPerJob() {
        LedgerBook book = new LedgerBook();
        assertTrue(book.find("job-1").isEmpty());
        MaterialLedger a = book.of("job-1");
        assertTrue(a == book.of("job-1"));
        assertEquals(1, book.all().size());
    }
}
```

`JobOutcomeTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobOutcomeTest {
    @Test
    void aPositionIsReportedOnceAndDenialsAreIndexed() {
        JobOutcome o = new JobOutcome();
        Conflict c = new Conflict(new IntPos(1, 2, 3), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:dirt")), ConflictKind.PLAYER_MODIFIED);
        assertTrue(o.addConflict(c));
        assertFalse(o.addConflict(c));
        o.skip(new SkippedPlacement(5, new IntPos(0, 0, 0), SkippedPlacement.DENIED));
        o.skip(new SkippedPlacement(6, new IntPos(1, 0, 0), SkippedPlacement.SITE_CHANGED));
        assertEquals(1, o.conflicts().size());
        assertEquals(Set.of(5), o.deniedIndexes());
        assertEquals(2, o.skipped().size());
    }

    @Test
    void onlyRemovalConflictsBlockLaterPlacementsAndASiteConflictCanBeResolved() {
        JobOutcome o = new JobOutcome();
        IntPos site = new IntPos(1, 2, 3);
        IntPos removed = new IntPos(4, 5, 6);
        o.addConflict(new Conflict(site, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.of("minecraft:dirt")),
                ConflictKind.PLAYER_MODIFIED));
        o.addRestoreConflict(new Conflict(removed, BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:gold_block")), ConflictKind.PLAYER_MODIFIED));
        assertFalse(o.hasRestoreConflictAt(site));
        assertTrue(o.hasRestoreConflictAt(removed));
        o.resolveConflictAt(site);
        o.resolveConflictAt(removed);
        assertEquals(1, o.conflicts().size(), "the site conflict is resolved; the removal conflict stays reported");
    }
}
```

`JobProgramTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobProgramTest {
    @Test
    void aBuildProgramIsTheManifestInOrderKeyedByIndex() {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram p = JobProgram.build(m);
        assertEquals(m.placements().size(), p.size());
        for (int i = 0; i < p.size(); i++) {
            assertFalse(p.isRestore(i));
            assertEquals(i, p.put(i).index());
            assertEquals(i, p.put(i).ledgerKey());
        }
    }

    @Test
    void repairRoundsUseTheirOwnLedgerKeys() {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram r = JobProgram.repair(m, List.of(3, 7), 2);
        assertEquals(2, r.size());
        assertEquals(3, r.put(0).index());
        assertEquals(2 * JobProgram.LEDGER_ROUND_STRIDE + 3, r.put(0).ledgerKey());
        assertTrue(JobProgram.LEDGER_ROUND_STRIDE > 20_000);
    }

    @Test
    void restoresComeBeforePuts() {
        RestoreItem undo = new RestoreItem(new IntPos(0, 64, 0), BlockSpec.of("minecraft:stone"), Set.of(), BlockSpec.AIR,
                "job-1", 0, false);
        JobProgram p = new JobProgram(List.of(undo), JobProgram.build(TestManifests.smallHut()).puts());
        assertTrue(p.isRestore(0));
        assertFalse(p.isRestore(1));
        assertEquals(undo, p.restore(0));
        assertEquals(0, p.put(1).index());
        assertEquals(1 + 25, p.size());
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`JournalRecord.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/**
 * One block the job changed: the block before (the UndoEntry of design 01, section 8), whether a block entity stood
 * there, the block now, the ledger key the material was charged under ({@link #NO_LEDGER_KEY} for a removal), and
 * whether the placement cut natural ground (so the ground owed to the owner can be settled from the record alone).
 */
public record JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed,
                            int ledgerKey, boolean terrainCut) {
    public static final int NO_LEDGER_KEY = -1;

    /**
     * The journal index of a removal: negative, so a MODIFY job's removals (program positions) never collide with its
     * additions (new-manifest indexes) in the one journal.
     */
    public static int restoreIndex(int programIndex) {
        return -1 - programIndex;
    }

    public JournalRecord {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(placed, "placed");
    }

    /** A record of a placement that cut no ground, or of a removal. */
    public JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed,
                         int ledgerKey) {
        this(placementIndex, pos, before, beforeHadBlockEntity, placed, ledgerKey, false);
    }

    public UndoEntry undo() {
        return new UndoEntry(pos, before);
    }
}
```
`UndoEntry.java`: `public record UndoEntry(IntPos pos, BlockSpec before)`(`requireNonNull`)。

`Journal.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a job changed, position by position (04 F-2: up to 20,000 undo entries). The first record of a placement index
 * wins: a repeat (resume, repair) never overwrites the pre-build block, so a rollback always restores the original.
 */
public final class Journal {
    public static final int MAX_ENTRIES = SafetyLimits.DEFAULT_MAX_PLACEMENTS;
    private static final Comparator<UndoEntry> TOP_DOWN = Comparator.<UndoEntry>comparingInt(u -> -u.pos().y())
            .thenComparingInt(u -> u.pos().z()).thenComparingInt(u -> u.pos().x());

    private final int maxEntries;
    private final TreeMap<Integer, JournalRecord> byIndex = new TreeMap<>();

    public Journal() {
        this(MAX_ENTRIES);
    }

    public Journal(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public boolean record(JournalRecord r) {
        if (byIndex.containsKey(r.placementIndex())) {
            return false;
        }
        if (byIndex.size() >= maxEntries) {
            throw new IllegalStateException("the journal is full (" + maxEntries + " entries)");
        }
        byIndex.put(r.placementIndex(), r);
        return true;
    }

    public Optional<JournalRecord> at(int index) {
        return Optional.ofNullable(byIndex.get(index));
    }

    public int size() {
        return byIndex.size();
    }

    public List<JournalRecord> records() {
        return List.copyOf(byIndex.values());
    }

    public List<UndoEntry> undo() {
        List<UndoEntry> out = new ArrayList<>();
        for (JournalRecord r : byIndex.values()) {
            out.add(r.undo());
        }
        out.sort(TOP_DOWN);
        return out;
    }
}
```

`PlacedEntry.java`: `public record PlacedEntry(BlockSpec placed, BlockSpec before, String jobId, int placementIndex, List<Integer> earlierKeys)`(`requireNonNull`で3つ、`earlierKeys`は`List.copyOf`)と、`earlierKeys`が空の4引数のコンストラクタ。`PlacedRegistry.remove(IntPos)`(起動時の復旧だけが使う: 世界がこのプロジェクトの書き込みを全部失った位置)。

`PlacedRegistry.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The blocks this project placed in one claim (D-25): only these may be removed or changed later. Each entry keeps the
 * block from before the project first touched the position, so a rollback restores the original ground; the placer's
 * job and ledger key tell which ledger answers for the current block's material. Kept until the claim is released (D-23).
 */
public final class PlacedRegistry {
    public static final int SCHEMA_VERSION = 1;

    private final String claimId;
    private final Map<IntPos, PlacedEntry> placed = new HashMap<>();
    private final Map<String, AssemblyResult> assemblies = new TreeMap<>();

    public PlacedRegistry(String claimId) {
        this.claimId = Objects.requireNonNull(claimId, "claimId");
    }

    public void apply(String jobId, JournalRecord r) {
        PlacedEntry e = placed.get(r.pos());
        if (e == null) {
            // a removal never creates an entry: re-running a removal after a crash must not claim the ground as ours
            if (r.ledgerKey() != JournalRecord.NO_LEDGER_KEY && !r.placed().equals(r.before())) {
                placed.put(r.pos(), new PlacedEntry(r.placed(), r.before(), jobId, r.ledgerKey()));
            }
            return;
        }
        if (r.placed().equals(e.before())) {
            placed.remove(r.pos());
            return;
        }
        // the same job writing the position again (a foundation on its own ground cut) keeps the earlier key: a removal
        // settles every key of it (the cut ground is taken back as well as the foundation's charge returned)
        List<Integer> earlier = new ArrayList<>();
        if (e.jobId().equals(jobId) && e.placementIndex() != r.ledgerKey()) {
            earlier.addAll(e.earlierKeys());
            earlier.add(e.placementIndex());
        }
        placed.put(r.pos(), new PlacedEntry(r.placed(), e.before(), jobId, r.ledgerKey(), earlier));
    }

    /** Crash recovery only: the entry of a position whose every write by the project the world lost. */
    public void remove(IntPos pos) {
        placed.remove(pos);
    }

    public void putAll(Map<IntPos, PlacedEntry> entries) {
        placed.putAll(entries);
    }

    /** The assembled individual of a group (P10/P13 write it; P4 stores and restores it). */
    public void putAssembly(AssemblyResult result) {
        assemblies.put(result.groupId(), result);
    }

    public Optional<PlacedEntry> at(IntPos pos) {
        return Optional.ofNullable(placed.get(pos));
    }

    public boolean contains(IntPos pos) {
        return placed.containsKey(pos);
    }

    public Map<IntPos, PlacedEntry> placed() {
        return Collections.unmodifiableMap(placed);
    }

    public Map<String, AssemblyResult> assemblies() {
        return Collections.unmodifiableMap(assemblies);
    }

    public String claimId() {
        return claimId;
    }

    public int size() {
        return placed.size();
    }
}
```

`AssemblyResult.java`: `public record AssemblyResult(String groupId, String assembledId, int movedBlockCount, long atTick)`(javadoc: 組み立て後の個体。P10・P13が記録し、解体・撤去はこの記録で個体を特定する。`01` 4節)。

`MaterialOp.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/**
 * The four material operations of a job (04 F-7). CHARGE and RECLAIM take items from the owner (a placement's cost, cut
 * ground taken back when it is put back); YIELD and RETURN give items to the owner (cut ground, a removal's refund).
 */
public enum MaterialOp {
    CHARGE(true), YIELD(false), RECLAIM(true), RETURN(false);

    private final boolean takes;

    MaterialOp(boolean takes) {
        this.takes = takes;
    }

    public boolean takesFromOwner() {
        return takes;
    }
}
```

`MaterialLedger.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The material ledger of one job (04 F-7), keyed by ledger key and counted in items, so every step settles only what is
 * still owed: a placement is charged up to its cost, a charge is returned at most once in total, and ground cut by
 * terraforming is given once and taken back at most once. Amounts, not flags, because a crash can make only part of an
 * operation durable (the owner's inventory is saved at once, a supply chest only with its chunk); crash recovery then
 * takes the lost part back out of the ledger ({@link #unrecord}) and the executor settles the rest (Task 9).
 */
public final class MaterialLedger {
    public static final int SCHEMA_VERSION = 1;
    /** No write-ahead-log run has been folded into this ledger yet. */
    public static final long NO_RUN = -1L;

    private final TreeMap<Integer, List<ItemCount>> consumed = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> returned = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> yielded = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> reclaimed = new TreeMap<>();
    private long appliedRun = NO_RUN;

    public boolean isConsumed(int key) {
        return consumed.containsKey(key);
    }

    public List<ItemCount> consumed(int key) {
        return consumed.getOrDefault(key, List.of());
    }

    public void recordConsumed(int key, List<ItemCount> items) {
        add(consumed, key, items);
    }

    public List<ItemCount> returned(int key) {
        return returned.getOrDefault(key, List.of());
    }

    /** What a removal still owes back: charged minus already returned. */
    public List<ItemCount> owedReturn(int key) {
        return ItemCount.minus(consumed(key), returned(key));
    }

    public boolean isReturned(int key) {
        return isConsumed(key) && owedReturn(key).isEmpty();
    }

    public void recordReturned(int key, List<ItemCount> items) {
        requireWithin(items, owedReturn(key), "return", key);
        add(returned, key, items);
    }

    /** Returns everything still owed for the key. */
    public void recordReturned(int key) {
        List<ItemCount> owed = owedReturn(key);
        if (owed.isEmpty()) {
            throw new IllegalStateException("ledger key " + key + " owes nothing back (not charged, or returned already)");
        }
        add(returned, key, owed);
    }

    public boolean isYielded(int key) {
        return yielded.containsKey(key);
    }

    public List<ItemCount> yielded(int key) {
        return yielded.getOrDefault(key, List.of());
    }

    public void recordYield(int key, List<ItemCount> items) {
        add(yielded, key, items);
    }

    public List<ItemCount> reclaimed(int key) {
        return reclaimed.getOrDefault(key, List.of());
    }

    /** What putting the ground back still has to take from the owner: given minus already taken back. */
    public List<ItemCount> owedReclaim(int key) {
        return ItemCount.minus(yielded(key), reclaimed(key));
    }

    public boolean isReclaimed(int key) {
        return isYielded(key) && owedReclaim(key).isEmpty();
    }

    public void recordReclaimed(int key, List<ItemCount> items) {
        requireWithin(items, owedReclaim(key), "reclaim", key);
        add(reclaimed, key, items);
    }

    public void recordReclaimed(int key) {
        List<ItemCount> owed = owedReclaim(key);
        if (owed.isEmpty()) {
            throw new IllegalStateException("ledger key " + key + " cannot be reclaimed (nothing given, or taken back already)");
        }
        add(reclaimed, key, owed);
    }

    /** Records a settled operation of the given kind (the executor's commit, and the replay of a durable log run). */
    public void record(MaterialOp op, int key, List<ItemCount> items) {
        switch (op) {
            case CHARGE -> recordConsumed(key, items);
            case YIELD -> recordYield(key, items);
            case RECLAIM -> recordReclaimed(key, items);
            case RETURN -> recordReturned(key, items);
        }
    }

    /** Crash recovery: adds a durable part of an operation without the owed check (parts may come back in any order). */
    public void replay(MaterialOp op, int key, List<ItemCount> items) {
        add(mapOf(op), key, items);
    }

    /** Crash recovery: takes back the part of an operation that never became durable. */
    public void unrecord(MaterialOp op, int key, List<ItemCount> items) {
        TreeMap<Integer, List<ItemCount>> map = mapOf(op);
        List<ItemCount> left = ItemCount.minus(map.getOrDefault(key, List.of()), items);
        if (left.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, left);
        }
    }

    private TreeMap<Integer, List<ItemCount>> mapOf(MaterialOp op) {
        return switch (op) {
            case CHARGE -> consumed;
            case YIELD -> yielded;
            case RECLAIM -> reclaimed;
            case RETURN -> returned;
        };
    }

    /** The newest write-ahead-log run already folded into this ledger (a replay skips runs up to it). */
    public long appliedRun() {
        return appliedRun;
    }

    public void markAppliedRun(long run) {
        appliedRun = Math.max(appliedRun, run);
    }

    public SortedMap<Integer, List<ItemCount>> consumedView() {
        return Collections.unmodifiableSortedMap(consumed);
    }

    public SortedMap<Integer, List<ItemCount>> returnedView() {
        return Collections.unmodifiableSortedMap(returned);
    }

    public SortedMap<Integer, List<ItemCount>> yieldedView() {
        return Collections.unmodifiableSortedMap(yielded);
    }

    public SortedMap<Integer, List<ItemCount>> reclaimedView() {
        return Collections.unmodifiableSortedMap(reclaimed);
    }

    private static void add(TreeMap<Integer, List<ItemCount>> map, int key, List<ItemCount> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("a ledger entry needs at least one item");
        }
        map.put(key, ItemCount.plus(map.getOrDefault(key, List.of()), items));
    }

    private static void requireWithin(List<ItemCount> items, List<ItemCount> owed, String what, int key) {
        if (!ItemCount.minus(items, owed).isEmpty()) {
            throw new IllegalStateException("ledger key " + key + ": cannot " + what + " " + items + ", only " + owed + " is owed");
        }
    }
}
```
`LedgerBook.java`: `HashMap<String, MaterialLedger>`を包み、`of`=`computeIfAbsent(jobId, k -> new MaterialLedger())`、`find`=`Optional.ofNullable`、`all`=読み取り専用の表示、`put(String, MaterialLedger)`(復元用)。

`SkippedPlacement.java`: `record SkippedPlacement(int index, IntPos pos, String reason)`と5つの定数(`ALREADY_THERE = "already-there"`: 修復のラウンドの手順が、このプロジェクトのブロックが既に正しくあるのを見つけた。置かず、取らない)。`JobOutcome.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** What a job could not do as planned: conflicts it left untouched and placements it skipped, reported to the owner. */
public final class JobOutcome {
    private final Map<IntPos, Conflict> conflicts = new LinkedHashMap<>();
    /** Positions a removal left alone: a later placement at the same position must not overwrite them (MODIFY). */
    private final Set<IntPos> restoreConflicts = new HashSet<>();
    private final TreeMap<Integer, SkippedPlacement> skipped = new TreeMap<>();

    /** True the first time a position is reported. */
    public boolean addConflict(Conflict c) {
        return conflicts.putIfAbsent(c.pos(), c) == null;
    }

    /** A conflict found while removing: reported like any other, and it also blocks a placement at the same position. */
    public boolean addRestoreConflict(Conflict c) {
        restoreConflicts.add(c.pos());
        return addConflict(c);
    }

    public boolean hasRestoreConflictAt(IntPos pos) {
        return restoreConflicts.contains(pos);
    }

    /** A placement succeeded where a site-change conflict was recorded (the obstacle went away): no longer a conflict. */
    public void resolveConflictAt(IntPos pos) {
        if (!restoreConflicts.contains(pos)) {
            conflicts.remove(pos);
        }
    }

    public void skip(SkippedPlacement s) {
        skipped.put(s.index(), s);
    }

    public List<Conflict> conflicts() {
        return List.copyOf(conflicts.values());
    }

    public List<SkippedPlacement> skipped() {
        return List.copyOf(skipped.values());
    }

    public boolean isSkipped(int index) {
        return skipped.containsKey(index);
    }

    public Set<Integer> deniedIndexes() {
        Set<Integer> out = new HashSet<>();
        for (SkippedPlacement s : skipped.values()) {
            if (SkippedPlacement.DENIED.equals(s.reason())) {
                out.add(s.index());
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
```
`PutItem.java`・`RestoreItem.java`(recordのみ。`requireNonNull`、`volatileProps`は`Set.copyOf`)。`JobProgram.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.util.ArrayList;
import java.util.List;

/**
 * The ordered work of one job: removals first (top-down, as planned by the rollback or MODIFY planner), then placements
 * (in manifest order). One cursor walks the whole program.
 */
public record JobProgram(List<RestoreItem> restores, List<PutItem> puts) {
    /** Separates the ledger keys of repair rounds: a block re-placed by a repair is charged again, under a new key. */
    public static final int LEDGER_ROUND_STRIDE = Integer.MAX_VALUE / (ConstructionJob.MAX_REPAIR_ROUNDS + 1);

    public JobProgram {
        restores = List.copyOf(restores);
        puts = List.copyOf(puts);
    }

    public static JobProgram build(PlacementManifest m) {
        List<PutItem> puts = new ArrayList<>(m.placements().size());
        for (Placement p : m.placements()) {
            puts.add(new PutItem(p.index(), p.index(), p));
        }
        return new JobProgram(List.of(), puts);
    }

    public static JobProgram repair(PlacementManifest m, List<Integer> indexes, int round) {
        List<PutItem> puts = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            puts.add(new PutItem(i, round * LEDGER_ROUND_STRIDE + i, m.placements().get(i)));
        }
        return new JobProgram(List.of(), puts);
    }

    public int size() {
        return restores.size() + puts.size();
    }

    public boolean isRestore(int i) {
        return i < restores.size();
    }

    public RestoreItem restore(int i) {
        return restores.get(i);
    }

    public PutItem put(int i) {
        return puts.get(i - restores.size());
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 施工の記録(Journal)・設置の記録(PlacedRegistry)・冪等な材料台帳(MaterialLedger)・ジョブの手順(JobProgram)を追加(自然言語→工場建設 P4 Task 8)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 9: 設置の実行(`ConstructionExecutor`)— カーソル、再開、材料、置けなくなった位置

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{MaterialPort,Stock,Move,OpKey,Stocks,WalEntry,WriteAheadLog,WalRecovery,ExecutionContext,StepReport,ConstructionExecutor,Attachments}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{FakeMaterials,StocksTest,WalRecoveryTest,CrashRecoveryTest,ConstructionExecutorTest,ConstructionExecutorRestoreTest,AttachmentsTest}.java`

**Interfaces:**
- Consumes: Task 4〜8の型、`build.compile.{BlockToItem,BlockMatch,Conflicts,Conflict,ConflictKind}`
- Produces:
  - `interface MaterialPort`: `INVENTORY = "inventory"`・`CHEST_PREFIX = "chest:"`・`FREE_SOURCE = "free"`・`NO_TX = 0`、`List<Stock> stocks(Collection<String> itemIds)`(持ち物が先、補給チェストが登録の順)、`Optional<String> giveTarget(List<ItemCount> items)`(**全部が入る**出どころ。オンラインなら持ち物、いなければ補給チェスト。どこにも入らなければ空=呼び出し側は`NO_ROOM`で止まる。**材料の操作の中で地面に落とさない**)、`boolean apply(List<Move>)`(足りない・入らないなら何も変えず`false`)、`void persist(long tx)`(持ち物を、**取引ID`tx`と一緒に**プレイヤーの保存データ(同じファイル、1回の書き込み)へ同期で保存する。確かめられなければ例外=ランタイムは施工を止める。Task 27)、`long durableTx()`(読み込んだ時の保存データにあった取引ID)、`static chest(IntPos)`・`chestPos(String)`。`MaterialPort.FREE`(クリエイティブ)。**Task 27の`Stock`・`StockTake`・`StockBook`は、ここの`Stock`・`Move`・`Stocks`に置き換わる**
  - `record Stock(String sourceId, String itemId, int count)`、`record Move(String sourceId, String itemId, int delta)`(負=所有者から取る)、`record OpKey(String jobId, int ledgerKey, MaterialOp op)`、`final class Stocks`(`missing`・`takeMoves`・`giveMoves`・`items`・`ids`)
  - `sealed interface WalEntry`: `RunStart(jobId, run)`・`PlaceIntent(jobId, run, JournalRecord)`・`MaterialIntent(jobId, run, OpKey, List<ItemCount>)`・`DropIntent(jobId, run, IntPos)`(入れ物の中身を落とす撤去。落ちた品物はチャンクのデータなので、確定点の前に落ちると失われ得る。復旧はその位置を`possiblyLostDrops`として知らせるだけ=ベストエフォート)・`RunEnd(jobId, run, List<Integer> written, List<OpMoves> moves)`・`DurablePoint(upToRun)`(**フラッシュつきの保存が、この回までを全部ディスクに持つ**。ランタイムだけが書く)、`static long runOf(WalEntry)`。前の版の`Persisted`・`SourceSaved`・`WorldSaved`(自動保存からの推測)は**やめた**
  - `final class WriteAheadLog`: `interface Sink { void write(List<WalEntry>); List<WalEntry> readAll(); void dropUpTo(long run); }`(`write`は書き終わった時には消えない。`dropUpTo`は確定点までの回を捨て、最新の確定点だけ残す=番号を続けるため。Task 25の`FileWalSink`はセグメントのファイルを消す)、`interface Barrier { AFTER_INTENTS, AFTER_WRITES, AFTER_RUN_END, AFTER_PLAYER_SAVE; void at(String point); NONE }`(規約の境目。純Javaのテストと、devkitの落とす台本の`/spike/barrier`が使う。本番は何もしない)、`MemorySink`(テスト用)、`WriteAheadLog(Sink, long lastRun)`・`static inMemory()`・`static open(Sink)`・`static lastRunIn(List<WalEntry>)`・`static durablePointIn(List<WalEntry>)`、`long newRun()`・`lastRun()`・`long durableUpTo()`・`append`・`flush`・`List<WalEntry> durable()`・`void markDurable(long upToRun)`(**フラッシュつきの保存が返った後だけ**、ランタイムが呼ぶ: 確定点を書き、それまでのログを捨てる)、`setBarrier`・`at`
  - `final class WalRecovery`: `enum Resolution {NONE, ADOPT, DISCARD}`、`record Result(boolean ambiguous, List<String> reasons, int adopted, int lostMoves, int rewindTo, List<IntPos> possiblyLostDrops)`、`static Result recover(String jobId, List<WalEntry> log, JobProgram, Journal, PlacedRegistry, LedgerBook, WorldPort, long savedTx, Resolution)`、`static List<WalEntry> pendingPart(String jobId, List<WalEntry> log)`、`REASON_CUT_SHORT`・`REASON_CHEST`
  - `record ExecutionContext(ConstructionJob job, JobProgram program, WorldPort world, MaterialPort materials, Journal journal, LedgerBook ledgers, PlacedRegistry registry, JobOutcome outcome, boolean skipSiteChanges, WriteAheadLog wal)`
  - `record StepReport(int cursor, PauseReason pause /*null=止まっていない*/, List<IntPos> touched, List<ItemCount> shortage, List<Conflict> conflicts)`
  - `ConstructionExecutor.run(ExecutionContext ctx, int cursor, int allowance) → StepReport`
  - `final class Attachments`: `static boolean dependent(BlockSpec)`(扉・看板・たいまつ・ランタン・はしご・ボタン・レバー・じゅうたん・感圧板・旗・植木鉢・トラップドア・レール: 隣のブロックに付いていて、その隣が消えると**アイテムを落として**外れる物)、`static boolean samePiece(RestoreItem a, RestoreItem b)`(同じ扉の上下の半分)、`static final Comparator<RestoreItem> REMOVAL_ORDER`(付いている物を先に、扉の上下は並べて上から、残りは上から下へ。Task 28・29の撤去の並びはこれを使う)
- **撤去は「1つの部品」ごとに仕上げる**(P4レビューG-2。`Level.markAndNotifyBlock`は形の更新に渡すフラグから`UPDATE_SUPPRESS_DROPS`(32)を消す(`Level.java`の`int i = p_46607_ & -34`)ので、扉の片方を戻すと、もう片方が`Block.updateOrDestroy`で**アイテムを落として**消える(`Block.java` 177〜186行、`DoorBlock.updateShape`)): アダプタの`restore`は形の更新をしない(Task 15)。実行は、扉の上下(`samePiece`が続く間)を**許可の個数を超えても1回の呼び出しで最後まで**戻し、部品が終わったら`WorldPort.settle(その部品の全位置)`で隣の更新をまとめて行う。途中で止まった場合(材料不足など)は、残りを戻したときに部品の全位置を`settle`する。
- **1回の呼び出し=ログの1回(run)。控えめな復旧の原則: 推測しない**(Codexの3回目のレビュー2026-09-30の決定。**これが正本**。世界のチャンクは非同期に保存され、読み込みから外れた時にも保存されるので、世界を見ても「いつの書き込みが残ったか」は証明できない。だから仕組みを増やさず、証拠で決まる物だけを取り込み、決まらない物は持ち主に聞き、世界から導き直せる物は導き直す):
  1. 読むだけの1回目の通し(`intents`): この回に書く位置(`PlaceIntent`)・材料の操作(`MaterialIntent`)・中身を落とす撤去(`DropIntent`)を集め、あれば`RunStart`と一緒にログに足して**`flush`**(ディスクへ書き終わるまで待つ)。→境目`AFTER_INTENTS`
  2. 2回目の通し(`walk`)で、世界と材料を実際に変える(下の順序)。→境目`AFTER_WRITES`
  3. 何か変えたら`RunEnd`(書いた位置と、出どころごとの実際の動き)を足して**`flush`**。持ち物を動かしたら→境目`AFTER_RUN_END`→**持ち物を、この回の番号を取引IDとして一緒に保存**(`persist(run)`)→境目`AFTER_PLAYER_SAVE`。
  - **確定点**(`DurablePoint`)は、ランタイムがフラッシュつきの保存(`MinecraftServer.saveAllChunks(true, true, false)`。Task 25)を終えた後にだけ書く。自動保存は印を書かない。確定点までの回は、世界・チェスト・プレイヤー・ジョブのファイルが全部ディスクにあるので、ログから捨てる。
  - 起動時の復旧(Task 24の`JobService.recoverAll`)は、確定点より後の回だけを見る: `RunEnd`のある回の書き込みは、**世界がちょうど予定のブロックを見せる所だけ**(と、同じジョブの同じ位置のそれより前の書き込み。チャンクは1枚の写しとして保存されるので、後の書き込みが残っていれば前の書き込みも起きている)を記録に取り込み、見せない所は失われた書き込みとして、カーソルを`rewindTo`(その回の最初の手順)まで戻して置き直す(台帳のキーが同じなので、払い済みは二度取らない)。持ち物の動きは、**保存された取引IDがその回以上なら残った**(個数を数えない。持ち主が拾う・捨てるで個数は変わるので)。**補給チェストの動きは証明できない**(チャンクが保存されたかどうか分からない)ので、その回は**決めない**。**`RunEnd`の無い回(回の途中で落ちた)も決めない**。決めない回があるジョブは、何も変えずに持ち主の答え(`adopt`=全部あった/`discard`=全部なかった。Task 24の`recover`)を待つ。登録簿の項目は、世界が見せる最後の書き込みに合わせて作り直す(ファイルは世界より新しいことがある)。
  - **残る限界**(隠さない。Task 35で`04` F-2に書く): 終わった回の位置に、サーバーが止まっている間にプレイヤーがちょうど同じブロックを置くと、見分けられず取り込む(起こりうるが、極めてまれ)。
  - 同じジョブがもう一度同じ位置に書く(整地で切った地面の上に基礎を置く): 登録簿の項目は後の書き込みの台帳のキーを持ち、前の書き込みのキーを`earlierKeys`に残す(Task 8)。撤去は全部のキーを精算する(切った地面の取り返しと、基礎の返却)。古いカーソルからの再歩行では、世界が後の書き込みを見せていれば、前の手順は済んだものとして扱う(`superseded`)。
- 1個の設置の順序(2回目の通し。**これが正本**): (1)世界を読む→未読み込みなら`CHUNK_UNLOADED`で止まる、(2)同じジョブの撤去で触らなかった位置(`JobOutcome.hasRestoreConflictAt`)なら、置かずに`CONFLICT`として飛ばす、(2a)`verify == ASSEMBLED_AWAY`の配置で、その組み立て(`assemblyGroup`)が`PlacedRegistry.assemblies()`に載っていれば(組み立てが済み、ブロックは動く構造物に移った)、**その位置には二度と書かない**(空気が正しい状態。サバイバルなら、まだ取っていない分の材料だけを取る。組み立ての前は普通の配置と同じで、元のブロックが無ければ置く。Codexのレビュー2026-09-30)、(2b)`ReplaceRules.decide(p, cell, 記録済みか(番号は台帳のキー), 自分の物として渡す登録簿の記録)`。**自分の物として渡すのは、修復のラウンドの手順(台帳のキーが`LEDGER_ROUND_STRIDE`以上。`ownedFor`)だけ**: 同じIDで状態の違うこのプロジェクトのブロックを置き直してよいのは修復(`REPAIR`のジョブと、ジョブの中のL7の修復のラウンド)だけで、最初のラウンドの手順(`BUILD`・`MODIFY`)は登録簿の記録を渡さない(`MODIFY`の変更の位置は、撤去の手順が`Conflicts.detect`=`BlockMatch.satisfies(今, expectedNow, 稼働で変わる状態)`で先に確かめ、合わなければ消えない`Conflict`にして、同じ位置の設置も止める)、(2c)記録が無く、登録簿の記録があり(修復のラウンドの手順だけ)、世界がもう予定どおりのブロックを見せていれば(修復のラウンドが、既に直っている位置を見つけた)、置かず取らずに`ALREADY_THERE`として飛ばす、**既に置けている(`AlreadyDone`)なら、台帳がまだ借りている分を精算する**(サバイバル: 値段−消費済みを取る。記録に`terrainCut`があれば、地面の品物−渡し済みを渡す。取る分が足りなければ`MATERIALS_MISSING`。クラッシュで精算が失われた位置のため)、(3)置けない→`Conflict`を記録して`SITE_CHANGED`で止まる(`skipSiteChanges`なら飛ばして記録)、(4)サバイバルで借りている分(値段−消費済み)があれば、不足を確かめる→足りなければ`MATERIALS_MISSING`で止まる(何も置かない)。地面を切るなら、渡す品物が入る場所があるかを確かめる→無ければ`NO_ROOM`で止まる(何も置かない。落とさない)、(5)置く(保護で拒否→`DENIED`として飛ばし、取らない)、(6)借りている分を取って台帳に記録、(7)記録(`Journal`は最初の1件だけ。整地で地面を切ったら`terrainCut`)と設置の記録を更新し、(8)地面を切ったら、切った分−渡し済みを所有者に渡して台帳に記録、(9)この位置の`SITE_CHANGED`の報告を消す(`resolveConflictAt`)。撤去の順序: (1)読む、(2)既に戻っている: **このジョブの記録があれば、借りている分を精算する**(取り返し・返却の残り。**消費が保存されなかった返却は取り戻す**=返却は、本当に払われた分までしか認めない)。記録が無ければ、ほかの人が空にした物なので、記録だけ直して何も返さない、(3)`Conflicts.detect`(稼働で変わる状態は無視)で一致しなければ触らず報告(`addRestoreConflict`)、(4)取り返す分が足りなければ`MATERIALS_MISSING`、返す品物が入らなければ`NO_ROOM`(どちらも何も変えない)、(5)戻す(中身のドロップは`restore`の中で、許可された後)、(6)取り返し・返却(借りている分だけ)、(7)記録。部品の隣の更新(`settle`)は、**この回に実際に戻した位置だけ**で行う(`Conflict`で触らなかった位置の隣を動かさない)。
- **Task 3への差分**: `PauseReason`に`NO_ROOM`(返す・渡す品物の入る場所が無い)を足す。`JobService`は`MATERIALS_MISSING`と同じく`RETRY_INTERVAL_TICKS`ごとに見直す(Task 13)。子供の文はTask 14の`pause.no_room`。
- 性能: 1回の呼び出しで`flush`は最大2回(意図・終わり)と、持ち物の保存が1回。クリエイティブは材料が無いので最大2回。Task 33の`perf-20000`・`four-jobs`で、`MsptStats`のランタイムの時間として測る。

- [ ] **Step 1: 失敗するテストを書く**

`AttachmentsTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttachmentsTest {
    private static RestoreItem undo(int x, int y, int z, BlockSpec now) {
        return new RestoreItem(new IntPos(x, y, z), now, Set.of(), BlockSpec.AIR, "job-1", 0, true);
    }

    @Test
    void attachedBlocksGoFirstAndADoorsHalvesStayTogether() {
        RestoreItem wall = undo(0, 64, 0, BlockSpec.of("minecraft:stone_bricks"));
        RestoreItem sign = undo(0, 64, 1, BlockSpec.of("minecraft:oak_wall_sign", "facing", "south"));
        RestoreItem lower = undo(2, 64, 0, BlockSpec.of("minecraft:oak_door", "half", "lower"));
        RestoreItem upper = undo(2, 65, 0, BlockSpec.of("minecraft:oak_door", "half", "upper"));
        RestoreItem roof = undo(0, 66, 0, BlockSpec.of("minecraft:oak_planks"));
        List<RestoreItem> all = new ArrayList<>(List.of(wall, sign, lower, upper, roof));
        all.sort(Attachments.REMOVAL_ORDER);
        assertEquals(List.of(upper, lower, sign, roof, wall), all);
        assertTrue(Attachments.samePiece(upper, lower));
        assertFalse(Attachments.samePiece(lower, sign));
        assertTrue(Attachments.dependent(sign.expectedNow()));
        assertTrue(Attachments.dependent(BlockSpec.of("minecraft:oak_trapdoor")), "a trapdoor hangs on its neighbour");
        assertTrue(Attachments.dependent(BlockSpec.of("minecraft:potted_poppy")));
        assertFalse(Attachments.dependent(wall.expectedNow()));
    }
}
```

`StocksTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class StocksTest {
    private static final String CHEST = MaterialPort.CHEST_PREFIX + "1,2,3";

    @Test
    void theInventoryIsDrawnFirstThenTheChestsInOrder() {
        List<Stock> stocks = List.of(new Stock(MaterialPort.INVENTORY, "a:x", 2), new Stock(CHEST, "a:x", 5));
        assertEquals(List.of(new Move(MaterialPort.INVENTORY, "a:x", -2), new Move(CHEST, "a:x", -2)),
                Stocks.takeMoves(List.of(new ItemCount("a:x", 4)), stocks));
        assertEquals(List.of(new ItemCount("a:x", 1)), Stocks.missing(List.of(new ItemCount("a:x", 8)), stocks));
        assertThrows(IllegalArgumentException.class, () -> Stocks.takeMoves(List.of(new ItemCount("a:x", 8)), stocks));
    }

    @Test
    void givesGoToOneTargetAndMovesAddUp() {
        List<Move> give = Stocks.giveMoves(List.of(new ItemCount("a:x", 1), new ItemCount("a:x", 2)), MaterialPort.INVENTORY);
        assertEquals(List.of(new Move(MaterialPort.INVENTORY, "a:x", 3)), give);
        assertEquals(List.of(new ItemCount("a:x", 3)), Stocks.items(give));
    }
}
```

`WalRecoveryTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalRecoveryTest {
    private static final String JOB = "job-1";
    private static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");
    private static final List<ItemCount> ONE_PLANK = List.of(new ItemCount("minecraft:oak_planks", 1));
    private static final IntPos A = new IntPos(0, 64, 0);
    private static final IntPos B = new IntPos(1, 64, 0);
    private static final JobProgram PROGRAM = new JobProgram(List.of(), List.of(
            new PutItem(0, 0, TestManifests.put(0, 64, 0, "minecraft:oak_planks")),
            new PutItem(1, 1, TestManifests.put(1, 64, 0, "minecraft:oak_planks"))));

    private static JournalRecord put(int index, IntPos pos) {
        return new JournalRecord(index, pos, BlockSpec.AIR, false, PLANKS, index);
    }

    private static WalEntry.OpMoves charged(int key, String source) {
        return new WalEntry.OpMoves(new OpKey(JOB, key, MaterialOp.CHARGE), List.of(new Move(source, "minecraft:oak_planks", -1)));
    }

    /** An owner whose saved data holds transaction {@code tx} (what a restart loads). */
    private static FakeMaterials savedAt(long tx) {
        FakeMaterials m = new FakeMaterials();
        m.persist(tx);
        return m.restart();
    }

    private static WalRecovery.Result recover(List<WalEntry> log, Journal journal, PlacedRegistry registry, LedgerBook ledgers,
                                              FakeWorld w, FakeMaterials m, WalRecovery.Resolution r) {
        return WalRecovery.recover(JOB, log, PROGRAM, journal, registry, ledgers, w, m.durableTx(), r);
    }

    @Test
    void aFinishedRunCountsOnlyWhereTheWorldHoldsExactlyThePlannedBlock() {
        FakeWorld w = new FakeWorld();
        w.setBlock(A, PLANKS);
        Journal journal = new Journal();
        PlacedRegistry registry = new PlacedRegistry("claim-1");
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.PlaceIntent(JOB, 1, put(0, A)),
                new WalEntry.PlaceIntent(JOB, 1, put(1, B)), new WalEntry.RunEnd(JOB, 1, List.of(0, 1), List.of()));
        WalRecovery.Result r = recover(log, journal, registry, new LedgerBook(), w, savedAt(0), WalRecovery.Resolution.NONE);
        assertFalse(r.ambiguous());
        assertEquals(1, r.adopted());
        assertTrue(registry.contains(A));
        assertFalse(registry.contains(B), "B's write was lost with the unsaved world");
        assertEquals(0, r.rewindTo(), "the re-walk starts at the run's first step and places B again");
    }

    @Test
    void aRunCutShortIsLeftToTheOwnerAndChangesNothingUntilAnswered() {
        FakeWorld w = new FakeWorld();
        w.setBlock(A, PLANKS);
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.PlaceIntent(JOB, 1, put(0, A)),
                new WalEntry.MaterialIntent(JOB, 1, new OpKey(JOB, 0, MaterialOp.CHARGE), ONE_PLANK));
        Journal journal = new Journal();
        LedgerBook ledgers = new LedgerBook();
        WalRecovery.Result r = recover(log, journal, new PlacedRegistry("claim-1"), ledgers, w, savedAt(0),
                WalRecovery.Resolution.NONE);
        assertTrue(r.ambiguous());
        assertEquals(List.of(WalRecovery.REASON_CUT_SHORT + ":1"), r.reasons());
        assertEquals(0, journal.size(), "nothing is guessed");
        PlacedRegistry adopted = new PlacedRegistry("claim-1");
        recover(log, new Journal(), adopted, ledgers, w, savedAt(0), WalRecovery.Resolution.ADOPT);
        assertTrue(adopted.contains(A));
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "adopt: the operation happened");
        PlacedRegistry discarded = new PlacedRegistry("claim-1");
        LedgerBook fresh = new LedgerBook();
        recover(log, new Journal(), discarded, fresh, w, savedAt(0), WalRecovery.Resolution.DISCARD);
        assertFalse(discarded.contains(A), "discard: the planks there are somebody else's");
        assertFalse(fresh.of(JOB).isConsumed(0));
    }

    @Test
    void aSupplyChestTakeCannotBeProvenAndWaitsForTheOwner() {
        LedgerBook ledgers = new LedgerBook();
        ledgers.of(JOB).recordConsumed(0, ONE_PLANK);
        ledgers.of(JOB).markAppliedRun(1);
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1),
                new WalEntry.RunEnd(JOB, 1, List.of(), List.of(charged(0, FakeMaterials.CHEST))));
        assertTrue(recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), savedAt(0),
                WalRecovery.Resolution.NONE).ambiguous());
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "unchanged while waiting");
        recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), savedAt(0),
                WalRecovery.Resolution.DISCARD);
        assertFalse(ledgers.of(JOB).isConsumed(0), "discard: the chest was not saved with the take; it is charged again");
    }

    @Test
    void theInventoryPartCountsByTheSavedTransactionIdNeverByCounts() {
        LedgerBook ledgers = new LedgerBook();
        List<WalEntry> log = List.of(new WalEntry.RunEnd(JOB, 1, List.of(), List.of(charged(0, MaterialPort.INVENTORY))),
                new WalEntry.RunEnd(JOB, 2, List.of(), List.of(charged(1, MaterialPort.INVENTORY))));
        // the owner picked things up and dropped others since: counts say nothing, the id says run 1 was saved
        FakeMaterials owner = savedAt(1);
        owner.ownerChanges("minecraft:oak_planks", 7);
        WalRecovery.Result r = recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), owner,
                WalRecovery.Resolution.NONE);
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "run 1's take is in the saved inventory");
        assertFalse(ledgers.of(JOB).isConsumed(1), "run 2's take never reached the disk");
        assertEquals(1, r.lostMoves());
    }

    @Test
    void runsCoveredByADurablePointAreLeftAsTheFilesHaveThem() {
        FakeWorld w = new FakeWorld();
        Journal journal = new Journal();
        // run 1 was cut short too, but a flushed save came after it: it is on the disk as the files have it
        List<WalEntry> log = List.of(new WalEntry.PlaceIntent(JOB, 1, put(0, A)), new WalEntry.DurablePoint(1),
                new WalEntry.PlaceIntent(JOB, 2, put(1, B)));
        WalRecovery.Result r = recover(log, journal, new PlacedRegistry("claim-1"), new LedgerBook(), w, savedAt(0),
                WalRecovery.Resolution.NONE);
        assertEquals(List.of(WalRecovery.REASON_CUT_SHORT + ":2"), r.reasons(), "only the run after the durable point counts");
        assertEquals(List.of(new WalEntry.PlaceIntent(JOB, 2, put(1, B))), WalRecovery.pendingPart(JOB, log));
    }

    @Test
    void aDropBeforeTheDurablePointIsReportedAsPossiblyLost() {
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.DropIntent(JOB, 1, A),
                new WalEntry.RunEnd(JOB, 1, List.of(), List.of()));
        assertEquals(List.of(A), recover(log, new Journal(), new PlacedRegistry("claim-1"), new LedgerBook(), new FakeWorld(),
                savedAt(0), WalRecovery.Resolution.NONE).possiblyLostDrops());
    }

    @Test
    void aDurablePointDropsTheLogButKeepsTheRunNumbers() {
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        WriteAheadLog wal = new WriteAheadLog(sink, 0L);
        for (int i = 0; i < 3; i++) {
            long run = wal.newRun();
            wal.append(new WalEntry.RunStart(JOB, run));
            wal.append(new WalEntry.RunEnd(JOB, run, List.of(), List.of()));
        }
        wal.flush();
        wal.markDurable(wal.lastRun());
        assertEquals(List.of(new WalEntry.DurablePoint(3)), sink.durable, "the log holds only what came after");
        WriteAheadLog reopened = WriteAheadLog.open(sink);
        assertEquals(3, reopened.durableUpTo());
        assertEquals(4, reopened.newRun(), "a reused run number would look already applied to a ledger");
    }

    @Test
    void aWriteTheSameJobBuiltOverIsTakenWithTheOneTheWorldShows() {
        // the ground cut (key 0) and then the foundation on it (key 5), both after the durable point
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        JournalRecord cut = new JournalRecord(0, A, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.AIR, 0, true);
        JournalRecord foundation = new JournalRecord(5, A, BlockSpec.AIR, false, cobble, 5);
        FakeWorld w = new FakeWorld();
        w.setBlock(A, cobble);
        List<WalEntry> log = List.of(new WalEntry.PlaceIntent(JOB, 1, cut), new WalEntry.RunEnd(JOB, 1, List.of(0), List.of()),
                new WalEntry.PlaceIntent(JOB, 2, foundation), new WalEntry.RunEnd(JOB, 2, List.of(5), List.of()));
        Journal journal = new Journal();
        PlacedRegistry registry = new PlacedRegistry("claim-1");
        WalRecovery.recover(JOB, log, PROGRAM, journal, registry, new LedgerBook(), w, 0L, WalRecovery.Resolution.NONE);
        assertTrue(journal.at(0).isPresent(), "a chunk is saved as one snapshot: the foundation there means the cut happened");
        assertEquals(List.of(0), registry.at(A).orElseThrow().earlierKeys());
    }
}
```

`CrashRecoveryTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainPrep;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A survival build over grass (cut ground is handed over as dirt) and its rollback, stopped by a crash at every single
 * step that changes something (a log flush, an item move, an owner save, a world write). The disk lags the live state
 * the way a server's does: an autosave writes only some chunks, the job files and the owner (never a durable point); a
 * checkpoint (a flushed save) writes everything and is the only durable point; the owner picks apples up and drops them
 * between runs. After a crash the jobs are recovered from the log; an ambiguous job is answered the way an owner who
 * looked would answer (the truth of the disk). Nothing may be charged, given or returned twice, and nothing may be lost.
 */
class CrashRecoveryTest {
    private static final int ALLOWANCE = 4;
    private static final Box SITE = new Box(-2, 55, -2, 4, 70, 4);
    private static final int SURFACE = 63;
    private static final String COBBLE = "minecraft:cobblestone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String DIRT = "minecraft:dirt";
    private static final String APPLE = "minecraft:apple";
    private static final BlockSpec GRASS = BlockSpec.of("minecraft:grass_block");
    private static final int AUTOSAVE_EVERY = 2;
    private static final int CHECKPOINT_EVERY = 5;

    static final class Crash extends RuntimeException {
    }

    static final class Crasher implements Runnable {
        int count;
        int at = Integer.MAX_VALUE;

        @Override
        public void run() {
            if (++count == at) {
                throw new Crash();
            }
        }
    }

    /** A world whose chunks reach the disk only when saved, some chunks at a time. */
    static final class DiskWorld implements WorldPort {
        final Map<IntPos, WorldCell> live = new HashMap<>();
        final Map<IntPos, WorldCell> disk = new HashMap<>();
        Runnable hook = () -> {
        };

        void set(IntPos p, WorldCell c) {
            live.put(p, c);
            disk.put(p, c);
        }

        @Override
        public WorldCell read(IntPos pos) {
            return live.getOrDefault(pos, WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
        }

        @Override
        public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
            live.put(pos, WorldCell.of(block));
            hook.run();
            return PlaceResult.PLACED;
        }

        @Override
        public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
            live.put(pos, block.isAir() ? WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE)
                    : WorldCell.of(block, CellTrait.TERRAFORMABLE));
            hook.run();
            return PlaceResult.PLACED;
        }

        @Override
        public void settle(List<IntPos> positions) {
        }

        /** An autosave: the chunks of one parity reach the disk (the rest may still be queued when the crash comes). */
        void saveSome(int parity) {
            for (Map.Entry<IntPos, WorldCell> e : live.entrySet()) {
                if (Math.floorMod(e.getKey().x() + e.getKey().z(), 2) == parity) {
                    disk.put(e.getKey(), e.getValue());
                }
            }
            disk.keySet().removeIf(p -> !live.containsKey(p) && Math.floorMod(p.x() + p.z(), 2) == parity);
        }

        void saveAll() {
            disk.clear();
            disk.putAll(live);
        }

        DiskWorld restart() {
            DiskWorld w = new DiskWorld();
            disk.forEach(w::set);
            return w;
        }
    }

    /** The job files as the last save wrote them. */
    record Files(Journal buildJournal, Journal rollbackJournal, LedgerBook ledgers, PlacedRegistry registry, int buildCursor,
                 int rollbackCursor, boolean rollbackKnown) {
    }

    /** Everything a server holds for the two jobs. */
    static final class Server {
        final PlacementManifest manifest;
        DiskWorld world;
        FakeMaterials mats;
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        WriteAheadLog wal = new WriteAheadLog(sink, 0L);
        Journal buildJournal = new Journal();
        Journal rollbackJournal = new Journal();
        LedgerBook ledgers = new LedgerBook();
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        JobProgram rollback;
        int buildCursor;
        int rollbackCursor;
        boolean rollbackStarted;
        long chestSavedUpTo;
        int runs;
        Files files;

        Server(PlacementManifest manifest, DiskWorld world, FakeMaterials mats) {
            this.manifest = manifest;
            this.world = world;
            this.mats = mats;
        }
    }

    private static Journal copy(Journal j) {
        Journal out = new Journal();
        j.records().forEach(out::record);
        return out;
    }

    private static LedgerBook copy(LedgerBook b) {
        LedgerBook out = new LedgerBook();
        b.all().forEach((id, l) -> {
            MaterialLedger c = out.of(id);
            l.consumedView().forEach((k, v) -> c.replay(MaterialOp.CHARGE, k, v));
            l.returnedView().forEach((k, v) -> c.replay(MaterialOp.RETURN, k, v));
            l.yieldedView().forEach((k, v) -> c.replay(MaterialOp.YIELD, k, v));
            l.reclaimedView().forEach((k, v) -> c.replay(MaterialOp.RECLAIM, k, v));
            if (l.appliedRun() != MaterialLedger.NO_RUN) {
                c.markAppliedRun(l.appliedRun());
            }
        });
        return out;
    }

    private static PlacedRegistry copy(PlacedRegistry r) {
        PlacedRegistry out = new PlacedRegistry(r.claimId());
        out.putAll(r.placed());
        return out;
    }

    /** The job files are written (at every save, the autosave's too). */
    private static void writeFiles(Server s) {
        s.files = new Files(copy(s.buildJournal), copy(s.rollbackJournal), copy(s.ledgers), copy(s.registry), s.buildCursor,
                s.rollbackCursor, s.rollbackStarted);
    }

    private static void autosave(Server s) {
        s.world.saveSome(s.runs / AUTOSAVE_EVERY % 2);
        if (s.runs / AUTOSAVE_EVERY % 2 == 0) {
            s.mats.saveChests();
            s.chestSavedUpTo = s.wal.lastRun();
        }
        s.mats.saveOwner();
        writeFiles(s);
    }

    /** A flushed save of the whole server: the only durable point. */
    private static void checkpoint(Server s) {
        s.world.saveAll();
        s.mats.saveChests();
        s.chestSavedUpTo = s.wal.lastRun();
        writeFiles(s);
        s.wal.markDurable(s.wal.lastRun());
    }

    private static PlacementManifest overGrass() {
        return TerrainPrep.apply(TestManifests.smallHut(), SiteSurvey.flat(TestManifests.DIM, SITE, SURFACE,
                GRASS.blockId())).manifest();
    }

    private static Server fresh(boolean chest) {
        PlacementManifest m = overGrass();
        DiskWorld w = new DiskWorld();
        for (Placement p : m.placements()) {
            if (p.replaces() instanceof ReplacePolicy.Terraform) {
                w.set(p.pos(), WorldCell.of(GRASS, CellTrait.TERRAFORMABLE));
            }
        }
        FakeMaterials mats = chest
                ? new FakeMaterials().with(COBBLE, 4).with(PLANKS, 6).withChest(COBBLE, 5).withChest(PLANKS, 10)
                : new FakeMaterials().with(COBBLE, 9).with(PLANKS, 16);
        Server s = new Server(m, w, mats);
        writeFiles(s);
        return s;
    }

    private static JobProgram buildProgram(Server s) {
        return JobProgram.build(s.manifest);
    }

    private static JobProgram rollbackProgram(Server s) {
        if (s.rollback == null) {
            List<RestoreItem> items = new ArrayList<>();
            s.registry.placed().forEach((pos, e) -> items.add(new RestoreItem(pos, e.placed(), Set.of(), e.before(), e.jobId(),
                    e.placementIndex(), false, e.earlierKeys())));
            items.sort(Attachments.REMOVAL_ORDER);
            s.rollback = new JobProgram(items, List.of());
        }
        return s.rollback;
    }

    private static ExecutionContext buildCtx(Server s) {
        ConstructionJob job = ConstructionExecutorTest.running(MaterialPolicy.SURVIVAL_CONSUME, s.manifest.placements().size());
        return new ExecutionContext(job, buildProgram(s), s.world, s.mats, s.buildJournal, s.ledgers, s.registry, new JobOutcome(),
                false, s.wal);
    }

    private static ExecutionContext rollbackCtx(Server s) {
        ConstructionJob job = ConstructionJob.create("job-2", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.ROLLBACK,
                "job-1", rollbackProgram(s).size(), "claim-job-1", MaterialPolicy.SURVIVAL_CONSUME, 0L, List.of())
                .on(JobEvent.ADMITTED).on(JobEvent.START);
        return new ExecutionContext(job, rollbackProgram(s), s.world, s.mats, s.rollbackJournal, s.ledgers, s.registry,
                new JobOutcome(), false, s.wal);
    }

    /** A new job's files are written the moment it is created, before its first run (so no run is ever of an unknown job). */
    private static void startRollback(Server s) {
        s.rollbackStarted = true;
        rollbackProgram(s);
        writeFiles(s);
    }

    /** Runs a job to its end in small runs, with the owner's pickups and the server's saves between the runs. */
    private static void finish(Server s, ExecutionContext ctx, boolean build) {
        int cursor = build ? s.buildCursor : s.rollbackCursor;
        while (cursor < ctx.program().size()) {
            StepReport r = ConstructionExecutor.run(ctx, cursor, ALLOWANCE);
            assertEquals(null, r.pause(), "the materials were enough: " + r);
            cursor = r.cursor();
            if (build) {
                s.buildCursor = cursor;
            } else {
                s.rollbackCursor = cursor;
            }
            s.runs++;
            s.mats.ownerChanges(APPLE, s.runs % 3 == 0 ? -1 : 1);
            if (s.runs % CHECKPOINT_EVERY == 0) {
                checkpoint(s);
            } else if (s.runs % AUTOSAVE_EVERY == 0) {
                autosave(s);
            }
        }
        // a job finishes only after a durable point covers its runs (JobService waits for the checkpoint)
        checkpoint(s);
    }

    /**
     * A restart: the disk only. The files, the world, the owner and the chest as last saved, the whole durable log.
     * Each job the files know is recovered; an ambiguous one is answered like an owner who looked: a run cut short left
     * nothing, a chest take happened only if the chest's chunk was saved after it. Then the start-up checkpoint.
     */
    private static Server restart(Server before) {
        Server s = new Server(before.manifest, before.world.restart(), before.mats.restart());
        s.sink.durable.addAll(before.sink.durable);
        s.wal = WriteAheadLog.open(s.sink);
        Files f = before.files;
        s.buildJournal = copy(f.buildJournal());
        s.rollbackJournal = copy(f.rollbackJournal());
        s.ledgers = copy(f.ledgers());
        s.registry = copy(f.registry());
        s.rollback = f.rollbackKnown() ? before.rollback : null;
        s.rollbackStarted = f.rollbackKnown();
        List<WalEntry> log = s.wal.durable();
        long chestSaved = before.chestSavedUpTo;
        java.util.function.LongPredicate truth = run -> {
            WalEntry.RunEnd end = log.stream().filter(e -> e instanceof WalEntry.RunEnd re && re.run() == run)
                    .map(e -> (WalEntry.RunEnd) e).findFirst().orElse(null);
            return end != null && chestSaved >= run;
        };
        WalRecovery.Result build = WalRecovery.recover("job-1", log, buildProgram(s), s.buildJournal, s.registry, s.ledgers,
                s.world, s.mats.durableTx(), truth);
        s.buildCursor = Math.min(f.buildCursor(), build.rewindTo());
        if (s.rollbackStarted) {
            WalRecovery.Result rb = WalRecovery.recover("job-2", log, rollbackProgram(s), s.rollbackJournal, s.registry,
                    s.ledgers, s.world, s.mats.durableTx(), truth);
            s.rollbackCursor = Math.min(f.rollbackCursor(), rb.rewindTo());
        }
        // the start-up checkpoint: the recovered files, and the log starts over
        checkpoint(s);
        return s;
    }

    private static int bom(PlacementManifest m, String item) {
        return m.bom().getOrDefault(item, 0);
    }

    /** The block each position ends with (a cut of the ground can come before the foundation at the same position). */
    private static Map<IntPos, BlockSpec> finalBlocks(PlacementManifest m) {
        Map<IntPos, BlockSpec> out = new HashMap<>();
        m.placements().forEach(p -> out.put(p.pos(), p.block()));
        return out;
    }

    /** The block each position had before the build: grass where the first placement there cut the ground. */
    private static Map<IntPos, BlockSpec> groundBlocks(PlacementManifest m) {
        Map<IntPos, BlockSpec> out = new HashMap<>();
        m.placements().forEach(p -> out.putIfAbsent(p.pos(), p.replaces() instanceof ReplacePolicy.Terraform ? GRASS : BlockSpec.AIR));
        return out;
    }

    private static void assertBuilt(Server s, String when) {
        finalBlocks(s.manifest).forEach((pos, block) -> assertEquals(block, s.world.read(pos).block(), when + ": " + pos));
        assertEquals(9 - bom(s.manifest, COBBLE), s.mats.total(COBBLE), when + ": cobblestone charged exactly once");
        assertEquals(16 - bom(s.manifest, PLANKS), s.mats.total(PLANKS), when + ": planks charged exactly once");
        assertEquals(9, s.mats.total(DIRT), when + ": the 9 cut grass blocks handed over exactly once");
    }

    private static void assertRolledBack(Server s, String when) {
        groundBlocks(s.manifest).forEach((pos, block) -> assertEquals(block, s.world.read(pos).block(), when + ": " + pos));
        assertEquals(9, s.mats.total(COBBLE), when + ": every charge returned exactly once");
        assertEquals(16, s.mats.total(PLANKS), when);
        assertEquals(0, s.mats.total(DIRT), when + ": the ground taken back exactly once");
    }

    @Test
    void aBuildAndItsRollbackSurviveACrashAtEveryStep() {
        int trials = 0;
        for (boolean chest : new boolean[]{false, true}) {
            for (int at = 1; ; at++) {
                Server s = fresh(chest);
                Crasher crasher = new Crasher();
                crasher.at = at;
                s.sink.hook = crasher;
                s.mats.hook = crasher;
                s.world.hook = crasher;
                String when = "chest=" + chest + " crash at step " + at;
                boolean crashed = false;
                boolean buildDone = false;
                try {
                    finish(s, buildCtx(s), true);
                    buildDone = true;
                    assertBuilt(s, when + " (no crash yet)");
                    startRollback(s);
                    finish(s, rollbackCtx(s), false);
                } catch (Crash c) {
                    crashed = true;
                }
                if (!crashed) {
                    assertRolledBack(s, when);
                    break;
                }
                trials++;
                Server back = restart(s);
                if (!buildDone) {
                    finish(back, buildCtx(back), true);
                    assertBuilt(back, when + " (crash in the build)");
                }
                if (!back.rollbackStarted) {
                    startRollback(back);
                }
                finish(back, rollbackCtx(back), false);
                assertRolledBack(back, when + (buildDone ? " (crash in the rollback)" : ""));
            }
        }
        assertTrue(trials > 100, "every step was a crash point: " + trials);
    }
}
```

`FakeMaterials.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A fake owner inventory (and optional supply chest) for the executor's tests. It keeps what is "on disk" apart from
 * what is live: the inventory and its transaction id reach the disk only through {@link #persist(long)}, the chest only
 * through {@link #saveChests()} (a chunk save), so {@link #restart()} shows what a crash would leave. {@code hook} runs
 * before and after every change, which the crash tests use to stop at every step. {@code room} caps how many items one
 * source holds (a full inventory), so a give that does not fit can be tried.
 */
public final class FakeMaterials implements MaterialPort {
    public static final String CHEST = CHEST_PREFIX + "5,64,5";
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final LinkedHashMap<String, TreeMap<String, Integer>> live = new LinkedHashMap<>();
    private final LinkedHashMap<String, TreeMap<String, Integer>> disk = new LinkedHashMap<>();
    private long diskTx = NO_TX;
    private long loadedTx = NO_TX;
    public Runnable hook = () -> {
    };
    public boolean ownerOnline = true;
    public int room = UNLIMITED;

    public FakeMaterials() {
        live.put(INVENTORY, new TreeMap<>());
        disk.put(INVENTORY, new TreeMap<>());
    }

    /** Test setup: items that are already saved (live and on disk). */
    public FakeMaterials with(String item, int count) {
        return add(INVENTORY, item, count);
    }

    public FakeMaterials withChest(String item, int count) {
        return add(CHEST, item, count);
    }

    private FakeMaterials add(String source, String item, int count) {
        live.computeIfAbsent(source, k -> new TreeMap<>()).merge(item, count, Integer::sum);
        disk.computeIfAbsent(source, k -> new TreeMap<>()).merge(item, count, Integer::sum);
        return this;
    }

    public int count(String item) {
        return live.get(INVENTORY).getOrDefault(item, 0);
    }

    public int chestCount(String item) {
        return live.getOrDefault(CHEST, new TreeMap<>()).getOrDefault(item, 0);
    }

    /** Everything the owner can use: the inventory and every chest. */
    public int total(String item) {
        return live.values().stream().mapToInt(m -> m.getOrDefault(item, 0)).sum();
    }

    private int held(String source) {
        return live.getOrDefault(source, new TreeMap<>()).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public List<Stock> stocks(Collection<String> itemIds) {
        List<Stock> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, Integer>> source : live.entrySet()) {
            for (String id : new java.util.TreeSet<>(itemIds)) {
                out.add(new Stock(source.getKey(), id, source.getValue().getOrDefault(id, 0)));
            }
        }
        return out;
    }

    @Override
    public Optional<String> giveTarget(List<ItemCount> items) {
        int n = items.stream().mapToInt(ItemCount::count).sum();
        List<String> order = new ArrayList<>();
        if (ownerOnline) {
            order.add(INVENTORY);
        }
        if (live.containsKey(CHEST)) {
            order.add(CHEST);
        }
        for (String source : order) {
            if (room == UNLIMITED || held(source) + n <= room) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean apply(List<Move> moves) {
        hook.run();
        for (Move m : moves) {
            int have = live.computeIfAbsent(m.sourceId(), k -> new TreeMap<>()).getOrDefault(m.itemId(), 0);
            if (have + m.delta() < 0) {
                return false;
            }
        }
        for (Move m : moves) {
            live.get(m.sourceId()).merge(m.itemId(), m.delta(), Integer::sum);
        }
        hook.run();
        return true;
    }

    @Override
    public void persist(long tx) {
        hook.run();
        // the inventory and its transaction id are one file, written in one piece
        disk.put(INVENTORY, new TreeMap<>(live.get(INVENTORY)));
        diskTx = tx;
        hook.run();
    }

    @Override
    public long durableTx() {
        return loadedTx;
    }

    /** The owner picks something up or drops it (between runs): the inventory changes outside any ledger operation. */
    public void ownerChanges(String item, int delta) {
        live.get(INVENTORY).merge(item, delta, Integer::sum);
    }

    /** A player save that is not ours (an autosave, a logout): the inventory with the last transaction id it holds. */
    public void saveOwner() {
        disk.put(INVENTORY, new TreeMap<>(live.get(INVENTORY)));
    }

    /** The chunk holding the chest was saved. */
    public void saveChests() {
        for (String source : live.keySet()) {
            if (!source.equals(INVENTORY)) {
                disk.put(source, new TreeMap<>(live.get(source)));
            }
        }
    }

    /** What a restart finds: the saved state only, and the saved transaction id as the loaded one. */
    public FakeMaterials restart() {
        FakeMaterials m = new FakeMaterials();
        m.live.clear();
        m.disk.clear();
        disk.forEach((k, v) -> {
            m.live.put(k, new TreeMap<>(v));
            m.disk.put(k, new TreeMap<>(v));
        });
        m.diskTx = diskTx;
        m.loadedTx = diskTx;
        m.room = room;
        return m;
    }
}
```

`ConstructionExecutorTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstructionExecutorTest {
    private static final int PLENTY = 10_000;

    static ConstructionJob running(MaterialPolicy policy, int total) {
        return ConstructionJob.create("job-1", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.BUILD, null, total,
                "claim-job-1", policy, 0L, List.of()).on(JobEvent.ADMITTED).on(JobEvent.START);
    }

    static ExecutionContext ctx(ConstructionJob job, JobProgram program, FakeWorld world, MaterialPort materials,
                                Journal journal, LedgerBook ledgers, PlacedRegistry registry, JobOutcome outcome, boolean skip) {
        return new ExecutionContext(job, program, world, materials, journal, ledgers, registry, outcome, skip,
                WriteAheadLog.inMemory());
    }

    private static ExecutionContext fresh(PlacementManifest m, FakeWorld world, MaterialPolicy policy, MaterialPort mats) {
        return ctx(running(policy, m.placements().size()), JobProgram.build(m), world, mats, new Journal(), new LedgerBook(),
                new PlacedRegistry("claim-job-1"), new JobOutcome(), false);
    }

    @Test
    void theHutGoesUpInOrderAndEveryBlockIsJournaled() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(25, r.cursor());
        assertEquals(25, r.touched().size());
        assertEquals(25, c.journal().size());
        assertEquals(25, c.registry().size());
        for (Placement p : m.placements()) {
            assertEquals(p.block(), w.blockAt(p.pos()));
        }
        assertEquals("place 0,63,0 minecraft:cobblestone", w.log.get(0), "construction order is the manifest order");
    }

    @Test
    void theAllowanceBoundsOneCall() {
        ExecutionContext c = fresh(TestManifests.smallHut(), new FakeWorld(), MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        assertEquals(4, ConstructionExecutor.run(c, 0, 4).cursor());
        assertEquals(9, ConstructionExecutor.run(c, 4, 5).cursor());
    }

    @Test
    void aJournalAheadOfTheCursorResumesWithoutChargingTwice() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        assertEquals(10, ConstructionExecutor.run(c, 0, 10).cursor());
        int writes = w.log.size();
        StepReport again = ConstructionExecutor.run(c, 5, PLENTY);
        assertEquals(25, again.cursor());
        assertEquals(writes + 15, w.log.size(), "indexes 5..9 were already in place: no second write");
        assertEquals(0, mats.count("minecraft:cobblestone"));
        assertEquals(0, mats.count("minecraft:oak_planks"), "25 blocks, 25 items: nothing charged twice");
    }

    @Test
    void pausesWhenAnUnbuiltPositionChanged() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        IntPos third = m.placements().get(3).pos();
        w.setBlock(third, BlockSpec.of("minecraft:stone_bricks"));
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.SITE_CHANGED, r.pause());
        assertEquals(3, r.cursor(), "stopped before the changed position, nothing overwritten");
        assertEquals("minecraft:stone_bricks", w.blockAt(third).blockId());
        assertEquals(1, r.conflicts().size());
        ExecutionContext skip = ctx(c.job(), c.program(), w, MaterialPort.FREE, c.journal(), c.ledgers(), c.registry(),
                c.outcome(), true);
        StepReport done = ConstructionExecutor.run(skip, 3, PLENTY);
        assertNull(done.pause());
        assertEquals(25, done.cursor());
        assertEquals("minecraft:stone_bricks", w.blockAt(third).blockId(), "skipping never overwrites");
        assertEquals(1, c.outcome().conflicts().size(), "the same position is reported once");
    }

    @Test
    void anUnloadedChunkPausesWithoutWriting() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        w.unload(m.placements().get(2).pos());
        StepReport r = ConstructionExecutor.run(fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE), 0, PLENTY);
        assertEquals(PauseReason.CHUNK_UNLOADED, r.pause());
        assertEquals(2, r.cursor());
    }

    @Test
    void survivalChargesTheDoorOnceAndPausesWhenShort() {
        List<Placement> ps = List.of(
                TestManifests.put(0, 64, 0, "minecraft:oak_door", "half", "lower"),
                TestManifests.put(0, 65, 0, "minecraft:oak_door", "half", "upper"),
                TestManifests.put(1, 64, 0, "minecraft:oak_planks"));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_door", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.MATERIALS_MISSING, r.pause());
        assertEquals(2, r.cursor());
        assertEquals(List.of(new ItemCount("minecraft:oak_planks", 1)), r.shortage());
        assertEquals(0, mats.count("minecraft:oak_door"), "one door item for both halves");
        assertEquals(BlockSpec.AIR, w.blockAt(new IntPos(1, 64, 0)), "nothing is placed without its material");
        mats.with("minecraft:oak_planks", 1);
        StepReport done = ConstructionExecutor.run(c, r.cursor(), PLENTY);
        assertNull(done.pause());
        assertEquals(0, mats.count("minecraft:oak_planks"));
        assertEquals(List.of(0, 2), List.copyOf(c.ledgers().of("job-1").consumedView().keySet()));
    }

    @Test
    void aDeniedPlacementIsSkippedAndNotCharged() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        w.deny(new IntPos(0, 64, 0));
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(1, r.cursor());
        assertEquals(1, mats.count("minecraft:oak_planks"));
        assertEquals(java.util.Set.of(0), c.outcome().deniedIndexes());
        assertEquals(0, c.journal().size());
    }

    @Test
    void cuttingGroundGivesItToTheOwnerOnce() {
        Placement sunk = new Placement(0, new IntPos(0, 63, 0), BlockSpec.of("minecraft:cobblestone"), Map.of(), "found",
                BuildPhase.STRUCTURE, PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(sunk));
        FakeWorld w = new FakeWorld();
        w.setBlock(new IntPos(0, 63, 0), BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(1, mats.count("minecraft:dirt"), "the cut grass block is handed over as dirt");
        assertEquals(0, mats.count("minecraft:cobblestone"));
        assertTrue(c.ledgers().of("job-1").isYielded(0));
        assertEquals("minecraft:grass_block", c.journal().at(0).orElseThrow().before().blockId());
    }

    @Test
    void aBlockAlreadyThereIsStillPaidForWhenTheLedgerLostTheCharge() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 18).with("minecraft:oak_planks", 32);
        ExecutionContext first = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(first, 0, PLENTY);
        assertEquals(9, mats.count("minecraft:cobblestone"));
        long placed = w.log.stream().filter(l -> l.startsWith("place ")).count();
        // a crash lost the charges but not the blocks: the journal and registry survived, the ledger did not
        ExecutionContext after = ctx(first.job(), first.program(), w, mats, first.journal(), new LedgerBook(), first.registry(),
                new JobOutcome(), false);
        StepReport r = ConstructionExecutor.run(after, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(placed, w.log.stream().filter(l -> l.startsWith("place ")).count(), "no block is placed twice");
        assertEquals(0, mats.count("minecraft:cobblestone"), "the blocks that are there are paid for once more, exactly");
        assertEquals(0, mats.count("minecraft:oak_planks"));
        assertEquals(25, after.ledgers().of("job-1").consumedView().size());
        StepReport again = ConstructionExecutor.run(after, 0, PLENTY);
        assertNull(again.pause(), "nothing is owed any more, so nothing is missing");
        assertEquals(0, mats.count("minecraft:oak_planks"), "and never a third time");
    }

    @Test
    void everyRunLogsItsIntentsBeforeAnyChangeAndItsEndBeforeTheInventoryIsSavedWithItsId() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        WriteAheadLog.MemorySink sink = (WriteAheadLog.MemorySink) c.wal().sink();
        List<String> order = new java.util.ArrayList<>();
        sink.hook = () -> order.add("log" + sink.durable.size());
        mats.hook = () -> order.add("items");
        w.afterWrite = (pos, block) -> order.add("world");
        c.wal().setBarrier(order::add);
        ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(List.of("log0", "log3", WriteAheadLog.Barrier.AFTER_INTENTS, "world", "items", "items",
                WriteAheadLog.Barrier.AFTER_WRITES, "log3", "log4", WriteAheadLog.Barrier.AFTER_RUN_END, "items", "items",
                WriteAheadLog.Barrier.AFTER_PLAYER_SAVE), order,
                "intents, then the world and the take, then the run's end, then the inventory saved with the run's id");
        assertTrue(sink.durable.get(0) instanceof WalEntry.RunStart);
        assertTrue(sink.durable.get(1) instanceof WalEntry.PlaceIntent);
        assertTrue(sink.durable.get(2) instanceof WalEntry.MaterialIntent);
        assertTrue(sink.durable.get(3) instanceof WalEntry.RunEnd);
        assertEquals(1L, mats.restart().durableTx(), "the saved inventory carries run 1 as its transaction id");
    }

    @Test
    void aHandOverThatDoesNotFitPausesBeforeTheWorldChangesAndNothingIsDropped() {
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        IntPos pos = new IntPos(0, 63, 0);
        Placement cut = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found", BuildPhase.SITE_PREP,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(cut));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, grass, CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the dirt from the cut would not fit");
        assertEquals(grass, w.blockAt(pos), "not cut yet");
        assertEquals(1, mats.count("minecraft:cobblestone"), "not charged yet");
        mats.room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(1, mats.count("minecraft:dirt"));
    }

    @Test
    void aRepairRoundThatFindsOurBlockAlreadyRightPlacesAndChargesNothing() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 10).with("minecraft:oak_planks", 16);
        ExecutionContext first = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(first, 0, PLENTY);
        int writes = w.log.size();
        JobProgram repair = JobProgram.repair(m, List.of(0), 1);
        ExecutionContext round = ctx(first.job(), repair, w, mats, first.journal(), first.ledgers(), first.registry(),
                first.outcome(), false);
        assertNull(ConstructionExecutor.run(round, 0, PLENTY).pause());
        assertEquals(writes, w.log.size(), "the block is there and right: no write");
        assertEquals(1, mats.count("minecraft:cobblestone"), "and no second charge under the repair round's key");
        assertTrue(first.outcome().skipped().stream().anyMatch(sk -> SkippedPlacement.ALREADY_THERE.equals(sk.reason())));
        w.setBlock(m.placements().get(0).pos(), BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertNull(ConstructionExecutor.run(round, 0, PLENTY).pause());
        assertEquals(0, mats.count("minecraft:cobblestone"), "a real repair is its own record and its own charge");
        assertTrue(first.journal().at(JobProgram.LEDGER_ROUND_STRIDE).isPresent(), "journaled under the round's key");
    }

    @Test
    void aFirstRoundStepNeverTakesTheProjectsBlockInAnotherStateAsItsOwn() {
        IntPos pos = new IntPos(1, 64, 1);
        BlockSpec north = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        BlockSpec east = BlockSpec.of("minecraft:oak_stairs", "facing", "east");
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 4, 70, 4), List.of(TestManifests.put(1, 64, 1, "minecraft:oak_stairs",
                "facing", "north")));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, east);
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        // the project placed the north-facing stair earlier (another job); a player has turned it east since
        c.registry().apply("job-0", new JournalRecord(0, pos, BlockSpec.AIR, false, north, 0));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.SITE_CHANGED, r.pause(), "a first-round step (BUILD, MODIFY) does not overwrite it");
        assertEquals(east, w.blockAt(pos));
        assertEquals(1, c.outcome().conflicts().size());
        // only a repair round re-places the project's own block of the same id in a wrong state
        ExecutionContext repair = ctx(c.job(), JobProgram.repair(m, List.of(0), 1), w, MaterialPort.FREE, c.journal(), c.ledgers(),
                c.registry(), c.outcome(), false);
        assertNull(ConstructionExecutor.run(repair, 0, PLENTY).pause());
        assertEquals(north, w.blockAt(pos));
    }

    @Test
    void anAssembledAwayBlockIsPlacedBeforeItsAssemblyAndNeverAgainAfterIt() {
        IntPos pos = new IntPos(1, 64, 1);
        Placement moved = new Placement(0, pos, BlockSpec.of("minecraft:oak_planks"), Map.of(), "wall", BuildPhase.STRUCTURE,
                PlacerId.SIMPLE, VerifyMode.ASSEMBLED_AWAY, ReplacePolicy.REPLACEABLE, "group-1");
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 4, 70, 4), List.of(moved));
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 2);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(BlockSpec.of("minecraft:oak_planks"), w.blockAt(pos), "before the assembly the block must be there");
        // the assembly (P10/P13) moves the block into its contraption: the position is air now
        c.registry().putAssembly(new AssemblyResult("group-1", "contraption-1", 1, 5L));
        w.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
        int writes = w.log.size();
        ExecutionContext again = ctx(c.job(), c.program(), w, mats, new Journal(), c.ledgers(), c.registry(), c.outcome(), false);
        assertNull(ConstructionExecutor.run(again, 0, PLENTY).pause());
        assertEquals(writes, w.log.size(), "never placed again, even by a step with no journal record of its own");
        assertEquals(BlockSpec.AIR, w.blockAt(pos));
        assertEquals(1, mats.count("minecraft:oak_planks"), "charged once");
    }
}
```

`ConstructionExecutorRestoreTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstructionExecutorRestoreTest {
    private static final int PLENTY = 10_000;

    /** Builds the small hut in survival, then returns the shared state for a rollback program. */
    private record Built(PlacementManifest m, FakeWorld w, FakeMaterials mats, LedgerBook ledgers, PlacedRegistry registry) {
    }

    private static Built build() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        LedgerBook ledgers = new LedgerBook();
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        ExecutionContext c = ConstructionExecutorTest.ctx(ConstructionExecutorTest.running(MaterialPolicy.SURVIVAL_CONSUME, 25),
                JobProgram.build(m), w, mats, new Journal(), ledgers, registry, new JobOutcome(), false);
        ConstructionExecutor.run(c, 0, PLENTY);
        return new Built(m, w, mats, ledgers, registry);
    }

    private static List<RestoreItem> undoAll(PlacedRegistry registry) {
        List<RestoreItem> out = new ArrayList<>();
        registry.placed().entrySet().stream()
                .sorted((a, b) -> a.getKey().y() != b.getKey().y() ? b.getKey().y() - a.getKey().y()
                        : a.getKey().z() != b.getKey().z() ? a.getKey().z() - b.getKey().z() : a.getKey().x() - b.getKey().x())
                .forEach(e -> out.add(new RestoreItem(e.getKey(), e.getValue().placed(), Set.of(), e.getValue().before(),
                        e.getValue().jobId(), e.getValue().placementIndex(), false)));
        return out;
    }

    private static ExecutionContext rollback(Built b, List<RestoreItem> items) {
        ConstructionJob job = ConstructionJob.create("job-2", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.ROLLBACK,
                "job-1", items.size(), "claim-job-1", MaterialPolicy.SURVIVAL_CONSUME, 0L, List.of())
                .on(JobEvent.ADMITTED).on(JobEvent.START);
        return ConstructionExecutorTest.ctx(job, new JobProgram(items, List.of()), b.w(), b.mats(), new Journal(items.size()),
                b.ledgers(), b.registry(), new JobOutcome(), false);
    }

    @Test
    void rollbackRestoresEveryBlockTopDownAndReturnsExactlyWhatWasConsumedOnce() {
        Built b = build();
        ExecutionContext c = rollback(b, undoAll(b.registry()));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        for (IntPos p : b.m().placements().stream().map(x -> x.pos()).toList()) {
            assertEquals(BlockSpec.AIR, b.w().blockAt(p));
        }
        assertEquals(9, b.mats().count("minecraft:cobblestone"));
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
        assertEquals(0, b.registry().size(), "every position is back to its pre-build block");
        assertTrue(b.w().log.get(25).startsWith("restore 0,65,0"), "top row first");
        StepReport again = ConstructionExecutor.run(rollback(b, undoAll(b.registry())), 0, PLENTY);
        assertEquals(0, again.cursor(), "nothing left to roll back");
        assertEquals(16, b.mats().count("minecraft:oak_planks"), "never returned twice");
    }

    @Test
    void aBlockThePlayerReplacedIsAConflictAndIsLeftAlone() {
        Built b = build();
        IntPos wall = new IntPos(0, 64, 0);
        b.w().setBlock(wall, BlockSpec.of("minecraft:gold_block"));
        ExecutionContext c = rollback(b, undoAll(b.registry()));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals("minecraft:gold_block", b.w().blockAt(wall).blockId());
        assertEquals(1, r.conflicts().size());
        assertEquals(ConflictKind.PLAYER_MODIFIED, r.conflicts().get(0).kind());
        assertEquals(15, b.mats().count("minecraft:oak_planks"), "no return for the plank the player took away");
    }

    @Test
    void aContainerTheProjectPlacedDropsItsItemsBeforeRemoval() {
        Built b = build();
        IntPos chest = new IntPos(1, 64, 1);
        b.w().set(chest, WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest"));
        b.w().putItems(chest, 5);
        b.registry().apply("job-1", new JournalRecord(99, chest, BlockSpec.AIR, false, BlockSpec.of("minecraft:chest"), 99));
        List<RestoreItem> items = List.of(new RestoreItem(chest, BlockSpec.of("minecraft:chest"), Set.of(), BlockSpec.AIR, "job-1",
                99, true));
        ExecutionContext c = rollback(b, items);
        ConstructionExecutor.run(c, 0, PLENTY);
        int drop = b.w().log.indexOf("drop 1,64,1 5");
        assertTrue(drop >= 0, "the items were dropped, not deleted");
        assertEquals("restore 1,64,1 minecraft:air", b.w().log.get(drop + 1));
        assertTrue(((WriteAheadLog.MemorySink) c.wal().sink()).durable.contains(new WalEntry.DropIntent("job-2", 1, chest)),
                "the drop is logged before it happens (best effort: dropped items are chunk data)");
    }

    @Test
    void aDoorIsRestoredAsOnePieceEvenAcrossTheAllowanceAndSettledOnce() {
        FakeWorld w = new FakeWorld();
        IntPos upper = new IntPos(2, 65, 0);
        IntPos lower = new IntPos(2, 64, 0);
        IntPos wall = new IntPos(0, 64, 0);
        BlockSpec up = BlockSpec.of("minecraft:oak_door", "half", "upper");
        BlockSpec low = BlockSpec.of("minecraft:oak_door", "half", "lower");
        w.setBlock(upper, up);
        w.setBlock(lower, low);
        w.setBlock(wall, BlockSpec.of("minecraft:stone"));
        List<RestoreItem> items = List.of(
                new RestoreItem(upper, up, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 0, true),
                new RestoreItem(lower, low, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 1, true),
                new RestoreItem(wall, BlockSpec.of("minecraft:stone"), Set.of(), BlockSpec.AIR, "job-1", 2, true));
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), new PlacedRegistry("claim-job-1"));
        StepReport first = ConstructionExecutor.run(rollback(b, items), 0, 1);
        assertEquals(2, first.cursor(), "the allowance of 1 does not split the door");
        assertEquals(List.of("drop 2,65,0 0", "restore 2,65,0 minecraft:air", "drop 2,64,0 0", "restore 2,64,0 minecraft:air",
                "settle 2,65,0 2,64,0"), w.log.subList(w.log.size() - 5, w.log.size()));
        assertEquals(0, first.conflicts().size(), "the lower half is still there when its turn comes");
        StepReport second = ConstructionExecutor.run(rollback(b, items), 2, 1);
        assertEquals(3, second.cursor());
        assertEquals("settle 0,64,0", w.log.get(w.log.size() - 1));
    }

    @Test
    void takingBackCutGroundWaitsForTheItems() {
        FakeWorld w = new FakeWorld();
        IntPos p = new IntPos(0, 63, 0);
        w.setBlock(p, BlockSpec.of("minecraft:cobblestone"));
        LedgerBook ledgers = new LedgerBook();
        ledgers.of("job-1").recordYield(0, List.of(new ItemCount("minecraft:dirt", 1)));
        FakeMaterials mats = new FakeMaterials();
        RestoreItem item = new RestoreItem(p, BlockSpec.of("minecraft:cobblestone"), Set.of(), BlockSpec.of("minecraft:grass_block"),
                "job-1", 0, false);
        Built b = new Built(TestManifests.smallHut(), w, mats, ledgers, new PlacedRegistry("claim-job-1"));
        StepReport r = ConstructionExecutor.run(rollback(b, List.of(item)), 0, PLENTY);
        assertEquals(PauseReason.MATERIALS_MISSING, r.pause());
        assertEquals(List.of(new ItemCount("minecraft:dirt", 1)), r.shortage());
        assertEquals("minecraft:cobblestone", w.blockAt(p).blockId(), "no ground is made from nothing");
        mats.with("minecraft:dirt", 1);
        assertNull(ConstructionExecutor.run(rollback(b, List.of(item)), 0, PLENTY).pause());
        assertEquals("minecraft:grass_block", w.blockAt(p).blockId());
        assertEquals(0, mats.count("minecraft:dirt"));
        assertTrue(ledgers.of("job-1").isReclaimed(0), "the ledger shows the reclaim, so it is never taken twice");
    }

    @Test
    void onlyThePartOfAPieceThatWasReallyRemovedGetsItsNeighboursUpdated() {
        FakeWorld w = new FakeWorld();
        IntPos upper = new IntPos(2, 65, 0);
        IntPos lower = new IntPos(2, 64, 0);
        BlockSpec up = BlockSpec.of("minecraft:oak_door", "half", "upper");
        BlockSpec low = BlockSpec.of("minecraft:oak_door", "half", "lower");
        w.setBlock(upper, BlockSpec.of("minecraft:gold_block"));
        w.setBlock(lower, low);
        List<RestoreItem> items = List.of(
                new RestoreItem(upper, up, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 0, true),
                new RestoreItem(lower, low, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 1, true));
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), new PlacedRegistry("claim-job-1"));
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertEquals(1, r.conflicts().size(), "the gold block is someone else's");
        assertEquals("settle 2,64,0", w.log.get(w.log.size() - 1), "the conflicted half is not settled");
        assertEquals("minecraft:gold_block", w.blockAt(upper).blockId());
    }

    @Test
    void aRemovalDoneBeforeACrashStillSettlesWhatItOwesButAPlayersEmptyingOwesNothing() {
        Built b = build();
        List<RestoreItem> all = undoAll(b.registry());
        ExecutionContext first = rollback(b, all);
        ConstructionExecutor.run(first, 0, PLENTY);
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
        // a crash lost the refunds but not the removals: the journal says removed, the ledger lost the returns
        MaterialLedger ledger = b.ledgers().of("job-1");
        for (Integer key : List.copyOf(ledger.returnedView().keySet())) {
            ledger.unrecord(MaterialOp.RETURN, key, ledger.returned(key));
        }
        b.mats().with("minecraft:oak_planks", -16).with("minecraft:cobblestone", -9);
        ExecutionContext again = ConstructionExecutorTest.ctx(first.job(), first.program(), b.w(), b.mats(), first.journal(),
                b.ledgers(), b.registry(), new JobOutcome(), false);
        assertNull(ConstructionExecutor.run(again, 0, PLENTY).pause());
        assertEquals(16, b.mats().count("minecraft:oak_planks"), "each refund owed is paid once");
        assertEquals(9, b.mats().count("minecraft:cobblestone"));
        Built other = build();
        IntPos broken = new IntPos(0, 65, 0);
        other.w().setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        ConstructionExecutor.run(rollback(other, undoAll(other.registry())), 0, PLENTY);
        assertEquals(15, other.mats().count("minecraft:oak_planks") + other.mats().count("minecraft:cobblestone") - 9,
                "the block a player broke was not this job's to refund");
    }

    @Test
    void aRefundThatDoesNotFitPausesBeforeTheBlockIsRemovedAndNothingIsDropped() {
        Built b = build();
        b.mats().room = 0;
        List<RestoreItem> items = undoAll(b.registry());
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the owner's inventory is full: the refund would have to be dropped");
        assertEquals(items.get(0).expectedNow(), b.w().blockAt(items.get(0).pos()), "not removed yet");
        b.mats().room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(rollback(b, undoAll(b.registry())), 0, PLENTY).pause());
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.ConstructionExecutor*" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`Attachments.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.gen.BlockForms;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Comparator;
import java.util.List;

/**
 * Which blocks hang on a neighbour and fall off, dropping their item, when it goes (a door's other half, a wall sign on
 * its wall). Removals take them first, and a door's two halves are one piece (removed back to back, settled once).
 */
public final class Attachments {
    static final String POTTED_PREFIX = "minecraft:potted_";
    /** Id endings of blocks that stand on or hang from a neighbour (vanilla survives-on / attached blocks). */
    private static final List<String> DEPENDENT_SUFFIXES = List.of(BlockForms.DOOR_SUFFIX, "_trapdoor", BlockForms.SIGN_SUFFIX,
            "_torch", "lantern", "ladder", "_button", "lever", "_carpet", "_pressure_plate", "_banner", "flower_pot", "rail");
    /** Dependents first; a door is placed by its lower half; then top-down, z, x; the upper half before the lower. */
    public static final Comparator<RestoreItem> REMOVAL_ORDER = Comparator
            .<RestoreItem>comparingInt(r -> dependent(r.expectedNow()) ? 0 : 1)
            .thenComparingInt(r -> -anchor(r).y())
            .thenComparingInt(r -> anchor(r).z())
            .thenComparingInt(r -> anchor(r).x())
            .thenComparingInt(r -> -r.pos().y());

    private Attachments() {
    }

    public static boolean dependent(BlockSpec b) {
        String id = b.blockId();
        if (id.startsWith(POTTED_PREFIX)) {
            return true;
        }
        for (String suffix : DEPENDENT_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean samePiece(RestoreItem a, RestoreItem b) {
        String id = a.expectedNow().blockId();
        return id.endsWith(BlockForms.DOOR_SUFFIX) && id.equals(b.expectedNow().blockId()) && a.pos().x() == b.pos().x()
                && a.pos().z() == b.pos().z() && Math.abs(a.pos().y() - b.pos().y()) == 1;
    }

    /** A door's upper half sorts with its lower half (one below), so the two stay next to each other. */
    private static IntPos anchor(RestoreItem r) {
        boolean upperDoor = r.expectedNow().blockId().endsWith(BlockForms.DOOR_SUFFIX)
                && BlockForms.HALF_UPPER.equals(r.expectedNow().get(BlockForms.PROP_HALF));
        return upperDoor ? r.pos().plus(0, -1, 0) : r.pos();
    }
}
```

`MaterialPort.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The owner's materials (inventory and supply chests, F-7), called on the main thread only. The pure core decides the
 * exact moves ({@link Stocks}); the port only counts, applies, and saves the owner's inventory on request. Items are
 * never dropped on the ground inside a material operation: a give goes only to a source with room for all of it.
 */
public interface MaterialPort {
    /** The owner's inventory; its changes are made durable by {@link #persist(long)}. */
    String INVENTORY = "inventory";
    /** Supply chests ({@code chest:x,y,z}); their changes are durable only at a durable point (a flushed save). */
    String CHEST_PREFIX = "chest:";
    /** The creative stand-in: holds everything, keeps nothing. */
    String FREE_SOURCE = "free";
    /** No transaction has been saved with the owner's data yet. */
    long NO_TX = 0L;

    MaterialPort FREE = new MaterialPort() {
        @Override
        public List<Stock> stocks(Collection<String> itemIds) {
            List<Stock> out = new ArrayList<>();
            itemIds.forEach(id -> out.add(new Stock(FREE_SOURCE, id, Integer.MAX_VALUE)));
            return out;
        }

        @Override
        public Optional<String> giveTarget(List<ItemCount> items) {
            return Optional.of(FREE_SOURCE);
        }

        @Override
        public boolean apply(List<Move> moves) {
            return true;
        }

        @Override
        public void persist(long tx) {
        }

        @Override
        public long durableTx() {
            return NO_TX;
        }
    };

    /** The source id of the supply chest at {@code pos}. */
    static String chest(IntPos pos) {
        return CHEST_PREFIX + pos.x() + "," + pos.y() + "," + pos.z();
    }

    /** The position of a supply chest's source id; empty for the inventory and the free source. */
    static Optional<IntPos> chestPos(String sourceId) {
        if (!sourceId.startsWith(CHEST_PREFIX)) {
            return Optional.empty();
        }
        String[] xyz = sourceId.substring(CHEST_PREFIX.length()).split(",");
        if (xyz.length != 3) {
            throw new IllegalArgumentException("not a chest source: " + sourceId);
        }
        return Optional.of(new IntPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
    }

    /** Every source's count of these items, sources in the order items are drawn from (the inventory first). */
    List<Stock> stocks(Collection<String> itemIds);

    /**
     * A source with room for all of these items (the owner's inventory while online, else a supply chest), or empty:
     * then the caller pauses (NO_ROOM) instead of dropping anything.
     */
    Optional<String> giveTarget(List<ItemCount> items);

    /** Applies exactly these moves; false, with nothing changed, if a source is short or a target has no room. */
    boolean apply(List<Move> moves);

    /**
     * Saves the owner's inventory now, synchronously, together with the transaction id {@code tx} in the owner's own
     * saved data (the same file, written in one piece), so recovery can tell from the id alone whether the inventory
     * on disk holds the moves of run {@code tx}. Throws if the save cannot be confirmed.
     */
    void persist(long tx);

    /** The transaction id the owner's saved data held when it was loaded (read once, before any new run). */
    long durableTx();
}
```
`ExecutionContext.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** Everything one executor call works on. {@code wal} is shared by all jobs of the server (runs are numbered globally). */
public record ExecutionContext(ConstructionJob job, JobProgram program, WorldPort world, MaterialPort materials, Journal journal,
                               LedgerBook ledgers, PlacedRegistry registry, JobOutcome outcome, boolean skipSiteChanges,
                               WriteAheadLog wal) {
}
```
`StepReport.java`: recordのみ(3つのリストは`List.copyOf`)。

`Stock.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** How many of one item one source holds now ({@code inventory}, or {@code chest:x,y,z} for a supply chest). */
public record Stock(String sourceId, String itemId, int count) {
    public Stock {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(itemId, "itemId");
        if (count < 0) {
            throw new IllegalArgumentException("a stock cannot be negative: " + count);
        }
    }
}
```

`Move.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** One change to one source: {@code delta} items of {@code itemId} (negative takes from the owner, positive gives). */
public record Move(String sourceId, String itemId, int delta) {
    public Move {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(itemId, "itemId");
        if (delta == 0) {
            throw new IllegalArgumentException("a move of nothing is a bug");
        }
    }
}
```

`OpKey.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** Which ledger entry a material operation settles: the job whose ledger it is, the ledger key, and the kind. */
public record OpKey(String jobId, int ledgerKey, MaterialOp op) {
    public OpKey {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(op, "op");
    }
}
```

`Stocks.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where a material operation takes its items from (04 F-7): the sources in the order the port lists them (the owner's
 * inventory first, then the supply chests in their registration order). Pure, so the moves are decided before any item
 * changes hands and can be logged exactly.
 */
public final class Stocks {
    private Stocks() {
    }

    public static TreeSet<String> ids(List<ItemCount> items) {
        TreeSet<String> out = new TreeSet<>();
        items.forEach(c -> out.add(c.itemId()));
        return out;
    }

    public static List<ItemCount> missing(List<ItemCount> need, List<Stock> stocks) {
        List<ItemCount> have = new ArrayList<>();
        stocks.forEach(s -> {
            if (s.count() > 0) {
                have.add(new ItemCount(s.itemId(), s.count()));
            }
        });
        return ItemCount.minus(need, have);
    }

    /** The takes that cover {@code need}, drawing each item from the sources in order. Throws when the stock is short. */
    public static List<Move> takeMoves(List<ItemCount> need, List<Stock> stocks) {
        List<ItemCount> short_ = missing(need, stocks);
        if (!short_.isEmpty()) {
            throw new IllegalArgumentException("not enough " + short_);
        }
        List<Move> out = new ArrayList<>();
        for (ItemCount c : ItemCount.plus(need, List.of())) {
            int left = c.count();
            for (Stock s : stocks) {
                if (left == 0) {
                    break;
                }
                if (s.itemId().equals(c.itemId()) && s.count() > 0) {
                    int n = Math.min(left, s.count());
                    out.add(new Move(s.sourceId(), s.itemId(), -n));
                    left -= n;
                }
            }
        }
        return out;
    }

    public static List<Move> giveMoves(List<ItemCount> items, String target) {
        List<Move> out = new ArrayList<>();
        for (ItemCount c : ItemCount.plus(items, List.of())) {
            out.add(new Move(target, c.itemId(), c.count()));
        }
        return out;
    }

    /** The items the moves carry, whatever the direction, added up per item. */
    public static List<ItemCount> items(List<Move> moves) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        moves.forEach(m -> sum.merge(m.itemId(), Math.abs(m.delta()), Integer::sum));
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> out.add(new ItemCount(id, n)));
        return out;
    }
}
```

`WalEntry.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Objects;

/**
 * The write-ahead log of the executor (04 F-2, F-7). One executor call is one run: its intents (the world writes, the
 * material operations and the container drops it may do) are on the disk before anything changes, its end (what it
 * really wrote and moved) right after. A durable point says a flushed save of the whole server holds every run up to
 * it; only then are the world and the supply chests known to be on the disk.
 */
public sealed interface WalEntry {
    record RunStart(String jobId, long run) implements WalEntry {
        public RunStart {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /** A world write this run may do. */
    record PlaceIntent(String jobId, long run, JournalRecord record) implements WalEntry {
        public PlaceIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(record, "record");
        }
    }

    record MaterialIntent(String jobId, long run, OpKey key, List<ItemCount> items) implements WalEntry {
        public MaterialIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(key, "key");
            items = List.copyOf(items);
        }
    }

    /**
     * A container's contents about to be dropped as item entities (a removal with dropContents). Best effort: dropped
     * items live in chunk data, so a crash before a durable point can lose them; recovery only reports the position.
     */
    record DropIntent(String jobId, long run, IntPos pos) implements WalEntry {
        public DropIntent {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(pos, "pos");
        }
    }

    /** The moves one material operation really made, per source. */
    record OpMoves(OpKey key, List<Move> moves) {
        public OpMoves {
            Objects.requireNonNull(key, "key");
            moves = List.copyOf(moves);
        }
    }

    record RunEnd(String jobId, long run, List<Integer> written, List<OpMoves> moves) implements WalEntry {
        public RunEnd {
            Objects.requireNonNull(jobId, "jobId");
            written = List.copyOf(written);
            moves = List.copyOf(moves);
        }
    }

    /** A flushed save of the whole server (chunks, entities, saved data, job files) holds every run up to this. */
    record DurablePoint(long upToRun) implements WalEntry {
    }

    /** The run an entry belongs to (a durable point: the last run it covers). */
    static long runOf(WalEntry e) {
        return switch (e) {
            case RunStart s -> s.run();
            case PlaceIntent p -> p.run();
            case MaterialIntent m -> m.run();
            case DropIntent d -> d.run();
            case RunEnd r -> r.run();
            case DurablePoint d -> d.upToRun();
        };
    }
}
```

`WriteAheadLog.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Appends log entries and makes them durable in one step per {@link #flush()} (the adapter's sink appends to segment
 * files under {@code wal/} and forces them to disk). Runs are numbered across all jobs. {@link #markDurable(long)} is
 * called only after a flushed save of the whole server; everything up to it is then dropped from the log, so the log
 * holds at most the runs since the last durable point.
 */
public final class WriteAheadLog {
    /** Where flushed entries go; {@code write} returns only when they are durable. */
    public interface Sink {
        void write(List<WalEntry> entries);

        /** Everything durable so far (read only when a job is recovered). */
        List<WalEntry> readAll();

        /** Drops every entry of the runs up to {@code run}, keeping the newest durable point (it keeps the numbering). */
        void dropUpTo(long run);
    }

    /**
     * A named place in the protocol where a test (or the devkit's crash barrier) may stop the server. The production
     * barrier does nothing.
     */
    public interface Barrier {
        String AFTER_INTENTS = "after-intents";
        String AFTER_WRITES = "after-writes";
        String AFTER_RUN_END = "after-run-end";
        String AFTER_PLAYER_SAVE = "after-player-save";
        /** Half of a log frame is on the disk (FileWalSink). */
        String MID_FRAME = "mid-frame";
        Barrier NONE = point -> {
        };

        void at(String point);
    }

    /** An in-memory sink for the pure tests; {@code durable} is what a crash would leave behind. */
    public static final class MemorySink implements Sink {
        public final List<WalEntry> durable = new ArrayList<>();
        public Runnable hook = () -> {
        };

        @Override
        public void write(List<WalEntry> entries) {
            hook.run();
            durable.addAll(entries);
            hook.run();
        }

        @Override
        public List<WalEntry> readAll() {
            return List.copyOf(durable);
        }

        @Override
        public void dropUpTo(long run) {
            WalEntry.DurablePoint newest = null;
            for (WalEntry e : durable) {
                if (e instanceof WalEntry.DurablePoint d && (newest == null || d.upToRun() > newest.upToRun())) {
                    newest = d;
                }
            }
            durable.removeIf(e -> WalEntry.runOf(e) <= run);
            if (newest != null && newest.upToRun() <= run) {
                durable.add(0, newest);
            }
        }
    }

    private final Sink sink;
    private final List<WalEntry> unflushed = new ArrayList<>();
    private long lastRun;
    private long durableUpTo;
    private Barrier barrier = Barrier.NONE;

    public WriteAheadLog(Sink sink, long lastRun) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.lastRun = lastRun;
    }

    public static WriteAheadLog inMemory() {
        return new WriteAheadLog(new MemorySink(), 0L);
    }

    /** Opens a log over what its sink holds; run numbers go on from the newest one there. */
    public static WriteAheadLog open(Sink sink) {
        List<WalEntry> all = sink.readAll();
        WriteAheadLog wal = new WriteAheadLog(sink, lastRunIn(all));
        wal.durableUpTo = durablePointIn(all);
        return wal;
    }

    /** The newest run number the entries name (0 for none); the durable point keeps it after a drop. */
    public static long lastRunIn(List<WalEntry> log) {
        long last = 0L;
        for (WalEntry e : log) {
            last = Math.max(last, WalEntry.runOf(e));
        }
        return last;
    }

    /** The newest durable point in the entries (0 for none: nothing is known to be on the disk). */
    public static long durablePointIn(List<WalEntry> log) {
        long d = 0L;
        for (WalEntry e : log) {
            if (e instanceof WalEntry.DurablePoint p) {
                d = Math.max(d, p.upToRun());
            }
        }
        return d;
    }

    public long newRun() {
        return ++lastRun;
    }

    public long lastRun() {
        return lastRun;
    }

    public long durableUpTo() {
        return durableUpTo;
    }

    public void append(WalEntry e) {
        unflushed.add(Objects.requireNonNull(e, "entry"));
    }

    public void flush() {
        if (unflushed.isEmpty()) {
            return;
        }
        List<WalEntry> batch = List.copyOf(unflushed);
        unflushed.clear();
        sink.write(batch);
    }

    /**
     * Called by the runtime only after a flushed save of the whole server returned: every run up to {@code upToRun} is
     * on the disk (world, chests, players, job files), so the log keeps only what came after.
     */
    public void markDurable(long upToRun) {
        append(new WalEntry.DurablePoint(upToRun));
        flush();
        durableUpTo = Math.max(durableUpTo, upToRun);
        sink.dropUpTo(upToRun);
    }

    public List<WalEntry> durable() {
        return sink.readAll();
    }

    public void setBarrier(Barrier b) {
        barrier = Objects.requireNonNull(b, "barrier");
    }

    public void at(String point) {
        barrier.at(point);
    }

    public Sink sink() {
        return sink;
    }
}
```

`WalRecovery.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongPredicate;

/**
 * Folds the runs a crash left after the last durable point into one job's saved journal, registry and ledgers (04 F-2,
 * F-7). The rule is to never guess: what the evidence proves is folded in, what it cannot prove is left to the owner.
 * <ul>
 *   <li>A run with its end: a written block counts only where the world holds exactly the planned block (anything else
 *   was lost with the unsaved world and is placed again by the re-walk from {@link Result#rewindTo()}); an inventory
 *   move counts only if the owner's saved data holds a transaction id at least the run's; a supply chest move cannot be
 *   proven (its chunk may or may not have been saved), so the run is ambiguous.</li>
 *   <li>A run without its end (the crash came inside it) is ambiguous.</li>
 * </ul>
 * An ambiguous job is left unchanged ({@link Result#ambiguous()}); the owner resolves it with adopt or discard, which
 * decides every ambiguous run of the job the same way. The residual limit: a player who places exactly the planned
 * block at a position of a finished run while the server is down is indistinguishable and is adopted.
 */
public final class WalRecovery {
    /** The owner's answer for runs the evidence cannot decide. */
    public enum Resolution { NONE, ADOPT, DISCARD }

    /** {@code explained}: the program positions the log's runs after the durable point account for (re-walked if lost). */
    public record Result(boolean ambiguous, List<String> reasons, int adopted, int lostMoves, int rewindTo,
                         Set<Integer> explained, List<IntPos> possiblyLostDrops) {
        public Result {
            reasons = List.copyOf(reasons);
            explained = Set.copyOf(explained);
            possiblyLostDrops = List.copyOf(possiblyLostDrops);
        }
    }

    /** Why a job waits for its owner: a run cut short inside, or a run that moved supply chest items. */
    public static final String REASON_CUT_SHORT = "cut-short";
    public static final String REASON_CHEST = "chest";

    private WalRecovery() {
    }

    public static Result recover(String jobId, List<WalEntry> log, JobProgram program, Journal journal, PlacedRegistry registry,
                                 LedgerBook ledgers, WorldPort world, long savedTx, Resolution resolution) {
        LongPredicate adopt = switch (resolution) {
            case NONE -> null;
            case ADOPT -> run -> true;
            case DISCARD -> run -> false;
        };
        return recover(jobId, log, program, journal, registry, ledgers, world, savedTx, adopt);
    }

    /**
     * {@code savedTx}: the transaction id the owner's saved data held at start-up (read before any new run).
     * {@code adoptRun}: the answer per ambiguous run, or null to report the ambiguity and change nothing.
     */
    static Result recover(String jobId, List<WalEntry> log, JobProgram program, Journal journal, PlacedRegistry registry,
                          LedgerBook ledgers, WorldPort world, long savedTx, LongPredicate adoptRun) {
        long durable = WriteAheadLog.durablePointIn(log);
        TreeMap<Long, List<WalEntry>> runs = new TreeMap<>();
        for (WalEntry e : log) {
            boolean mine = switch (e) {
                case WalEntry.RunStart s -> s.jobId().equals(jobId);
                case WalEntry.PlaceIntent p -> p.jobId().equals(jobId);
                case WalEntry.MaterialIntent m -> m.jobId().equals(jobId);
                case WalEntry.DropIntent d -> d.jobId().equals(jobId);
                case WalEntry.RunEnd r -> r.jobId().equals(jobId);
                case WalEntry.DurablePoint d -> false;
            };
            if (mine && WalEntry.runOf(e) > durable) {
                runs.computeIfAbsent(WalEntry.runOf(e), k -> new ArrayList<>()).add(e);
            }
        }
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            WalEntry.RunEnd end = endOf(r.getValue());
            if (end == null && r.getValue().stream().anyMatch(e -> !(e instanceof WalEntry.RunStart))) {
                reasons.add(REASON_CUT_SHORT + ":" + r.getKey());
            } else if (end != null && movesChest(end)) {
                reasons.add(REASON_CHEST + ":" + r.getKey());
            }
        }
        if (!reasons.isEmpty() && adoptRun == null) {
            return new Result(true, reasons, 0, 0, program.size(), Set.of(), List.of());
        }
        Map<Integer, Integer> positionOfKey = new HashMap<>();
        for (int i = 0; i < program.size(); i++) {
            if (!program.isRestore(i)) {
                positionOfKey.put(program.put(i).ledgerKey(), i);
            }
        }
        int adopted = 0;
        // the writes of this job that happened (by the evidence or the owner's answer), in the order they were made; a
        // chunk is saved as one snapshot, so where the world shows a write, every earlier write of the job at that
        // position happened too (a ground cut under the foundation that now stands there)
        List<JournalRecord> writes = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            WalEntry.RunEnd end = endOf(r.getValue());
            boolean take = (end != null && !movesChest(end)) || adoptRun.test(r.getKey());
            for (WalEntry e : r.getValue()) {
                if (e instanceof WalEntry.PlaceIntent pi && take
                        && (end == null || end.written().contains(pi.record().placementIndex()))) {
                    writes.add(pi.record());
                }
            }
        }
        Map<IntPos, Integer> lastShown = new HashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            WorldCell cell = world.read(writes.get(i).pos());
            if (cell.loaded() && BlockMatch.exact(cell.block(), writes.get(i).placed(), Set.of())) {
                lastShown.put(writes.get(i).pos(), i);
            }
        }
        for (int i = 0; i < writes.size(); i++) {
            JournalRecord rec = writes.get(i);
            if (i <= lastShown.getOrDefault(rec.pos(), -1)) {
                if (journal.record(rec)) {
                    adopted++;
                }
                registry.apply(jobId, rec);
            }
        }
        rebuildEntries(jobId, writes, journal, registry, world);
        int lost = 0;
        int rewindTo = program.size();
        java.util.Set<Integer> explained = new java.util.HashSet<>();
        List<IntPos> drops = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            long run = r.getKey();
            WalEntry.RunEnd end = endOf(r.getValue());
            boolean decided = end != null && !movesChest(end);
            boolean take = decided || adoptRun.test(run);
            // whether each ledger already holds this run is read once, before any of the run's operations is folded in
            Map<String, Boolean> held = new HashMap<>();
            java.util.function.Function<String, Boolean> holds = job -> held.computeIfAbsent(job,
                    k -> run <= ledgers.of(k).appliedRun());
            for (WalEntry e : r.getValue()) {
                if (e instanceof WalEntry.PlaceIntent pi) {
                    JournalRecord rec = pi.record();
                    int at = rec.placementIndex() < 0 ? -1 - rec.placementIndex()
                            : positionOfKey.getOrDefault(rec.placementIndex(), program.size());
                    rewindTo = Math.min(rewindTo, at);
                    explained.add(at);
                } else if (e instanceof WalEntry.DropIntent d) {
                    drops.add(d.pos());
                } else if (e instanceof WalEntry.MaterialIntent mi && end == null) {
                    // a run cut short: its moves are unknown; the owner's answer decides whether the operation happened
                    MaterialLedger ledger = ledgers.of(mi.key().jobId());
                    boolean already = holds.apply(mi.key().jobId());
                    if (take && !already) {
                        ledger.replay(mi.key().op(), mi.key().ledgerKey(), mi.items());
                    } else if (!take && already) {
                        ledger.unrecord(mi.key().op(), mi.key().ledgerKey(), mi.items());
                    }
                }
            }
            if (end == null) {
                continue;
            }
            for (WalEntry.OpMoves om : end.moves()) {
                MaterialLedger ledger = ledgers.of(om.key().jobId());
                List<Move> kept = new ArrayList<>();
                List<Move> gone = new ArrayList<>();
                for (Move m : om.moves()) {
                    boolean durableMove = m.sourceId().equals(MaterialPort.INVENTORY) ? savedTx >= run
                            : m.sourceId().startsWith(MaterialPort.CHEST_PREFIX) ? take : true;
                    (durableMove ? kept : gone).add(m);
                }
                lost += gone.size();
                if (!holds.apply(om.key().jobId())) {
                    if (!kept.isEmpty()) {
                        ledger.replay(om.key().op(), om.key().ledgerKey(), Stocks.items(kept));
                    }
                } else if (!gone.isEmpty()) {
                    ledger.unrecord(om.key().op(), om.key().ledgerKey(), Stocks.items(gone));
                }
            }
            held.keySet().forEach(job -> ledgers.of(job).markAppliedRun(run));
        }
        return new Result(false, reasons, adopted, lost, rewindTo, explained, drops);
    }

    /**
     * The registry entry of each position this job wrote after the durable point, made to agree with the world: the
     * saved files may be newer than the saved world (an autosave writes both, but a chunk can miss it), so an entry can
     * name a write the world lost. The entry becomes this job's last write the world shows, with the earlier ones as
     * earlier keys; where the world shows none of them but the pre-build block, the entry goes.
     */
    private static void rebuildEntries(String jobId, List<JournalRecord> writes, Journal journal, PlacedRegistry registry,
                                       WorldPort world) {
        Map<IntPos, List<JournalRecord>> byPos = new HashMap<>();
        for (JournalRecord r : journal.records()) {
            byPos.computeIfAbsent(r.pos(), k -> new ArrayList<>()).add(r);
        }
        java.util.Set<IntPos> touched = new java.util.LinkedHashSet<>();
        writes.forEach(w -> touched.add(w.pos()));
        for (IntPos pos : touched) {
            java.util.Optional<PlacedEntry> entry = registry.at(pos);
            if (entry.isEmpty() || !entry.get().jobId().equals(jobId)) {
                continue;
            }
            // a removal (negative index) comes before the job's placements at the same position
            List<JournalRecord> chain = new ArrayList<>(byPos.getOrDefault(pos, List.of()));
            chain.removeIf(r -> r.ledgerKey() == JournalRecord.NO_LEDGER_KEY);
            chain.sort(java.util.Comparator.comparingInt(JournalRecord::ledgerKey));
            WorldCell cell = world.read(pos);
            int last = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (cell.loaded() && BlockMatch.exact(cell.block(), chain.get(i).placed(), Set.of())) {
                    last = i;
                }
            }
            BlockSpec before = entry.get().before();
            Map<IntPos, PlacedEntry> one = new HashMap<>();
            if (last >= 0) {
                List<Integer> earlier = new ArrayList<>();
                for (int i = 0; i < last; i++) {
                    earlier.add(chain.get(i).ledgerKey());
                }
                one.put(pos, new PlacedEntry(chain.get(last).placed(), before, jobId, chain.get(last).ledgerKey(), earlier));
                registry.putAll(one);
            } else if (cell.loaded() && BlockMatch.exact(cell.block(), before, Set.of())) {
                registry.remove(pos);
            }
        }
    }

    private static WalEntry.RunEnd endOf(List<WalEntry> entries) {
        for (WalEntry e : entries) {
            if (e instanceof WalEntry.RunEnd end) {
                return end;
            }
        }
        return null;
    }

    private static boolean movesChest(WalEntry.RunEnd end) {
        return end.moves().stream().flatMap(om -> om.moves().stream())
                .anyMatch(m -> m.sourceId().startsWith(MaterialPort.CHEST_PREFIX));
    }

    /** The job's runs after the last durable point, the part of the log an ambiguous job keeps for its owner's answer. */
    public static List<WalEntry> pendingPart(String jobId, List<WalEntry> log) {
        long durable = WriteAheadLog.durablePointIn(log);
        List<WalEntry> out = new ArrayList<>();
        for (WalEntry e : log) {
            if (!(e instanceof WalEntry.DurablePoint) && WalEntry.runOf(e) > durable && jobOf(e).equals(jobId)) {
                out.add(e);
            }
        }
        return out;
    }

    private static String jobOf(WalEntry e) {
        return switch (e) {
            case WalEntry.RunStart s -> s.jobId();
            case WalEntry.PlaceIntent p -> p.jobId();
            case WalEntry.MaterialIntent m -> m.jobId();
            case WalEntry.DropIntent d -> d.jobId();
            case WalEntry.RunEnd r -> r.jobId();
            case WalEntry.DurablePoint d -> "";
        };
    }
}
```

`ConstructionExecutor.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.BlockToItem;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.Conflicts;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs up to {@code allowance} steps of a job program from {@code cursor} (04 F-2, F-5, F-7). Every step reads the
 * world first and decides with the same rules as the approval did, so a re-run from an older cursor is harmless:
 * journaled positions that already hold their block are skipped, and the ledger is settled to what is still owed, never
 * more. One call is one run of the write-ahead log: intents are durable before the first change, the run's end right
 * after the last one, then the owner's inventory is saved with the run as its transaction id. Nothing is ever dropped
 * inside a material operation: a give that does not fit pauses (NO_ROOM) before the world is touched.
 */
public final class ConstructionExecutor {
    private record Step(PauseReason pause, List<ItemCount> shortage) {
        static final Step NEXT = new Step(null, List.of());

        static Step pause(PauseReason reason) {
            return new Step(reason, List.of());
        }
    }

    /** What one run really did, for its RunEnd entry. */
    private static final class RunLog {
        final long run;
        final List<Integer> written = new ArrayList<>();
        final List<WalEntry.OpMoves> moves = new ArrayList<>();
        final Set<IntPos> restored = new HashSet<>();
        boolean movedInventory;

        RunLog(long run) {
            this.run = run;
        }

        boolean isEmpty() {
            return written.isEmpty() && moves.isEmpty();
        }
    }

    private ConstructionExecutor() {
    }

    public static StepReport run(ExecutionContext ctx, int cursor, int allowance) {
        WriteAheadLog wal = ctx.wal();
        String jobId = ctx.job().jobId();
        long run = wal.newRun();
        List<WalEntry> intents = intents(ctx, cursor, allowance, run);
        if (!intents.isEmpty()) {
            wal.append(new WalEntry.RunStart(jobId, run));
            intents.forEach(wal::append);
            wal.flush();
            wal.at(WriteAheadLog.Barrier.AFTER_INTENTS);
        }
        RunLog log = new RunLog(run);
        StepReport report = walk(ctx, cursor, allowance, log);
        if (!log.isEmpty()) {
            wal.at(WriteAheadLog.Barrier.AFTER_WRITES);
            wal.append(new WalEntry.RunEnd(jobId, run, log.written, log.moves));
            wal.flush();
            if (log.movedInventory) {
                wal.at(WriteAheadLog.Barrier.AFTER_RUN_END);
                // the run number is the transaction id saved with the inventory: recovery reads the id, never counts
                ctx.materials().persist(run);
                wal.at(WriteAheadLog.Barrier.AFTER_PLAYER_SAVE);
            }
        }
        return report;
    }

    /** Whether program position {@code j} restores the other half of the piece restored at {@code j - 1}. */
    static boolean continuesPiece(JobProgram program, int j) {
        return j > 0 && j < program.size() && program.isRestore(j - 1) && program.isRestore(j)
                && Attachments.samePiece(program.restore(j - 1), program.restore(j));
    }

    static Optional<BlockSpec> owned(ExecutionContext ctx, IntPos pos) {
        return ctx.registry().at(pos).map(PlacedEntry::placed);
    }

    /**
     * The registry entry a put may treat as its own block: only a repair round's step (ledger key of round 1 or later,
     * JobProgram.repair) re-places the project's block of the same id in a wrong state. A first-round step (BUILD,
     * MODIFY) never does: a MODIFY's changed position was checked against what the project placed (its removal, with
     * BlockMatch.satisfies and the volatile states) and a mismatch is a lasting Conflict, not something to overwrite.
     */
    static Optional<BlockSpec> ownedFor(ExecutionContext ctx, PutItem item) {
        return item.ledgerKey() >= JobProgram.LEDGER_ROUND_STRIDE ? owned(ctx, item.placement().pos()) : Optional.empty();
    }

    /**
     * A step this job did and then built over (a ground cut under the foundation): the registry's entry is this job's
     * later write and lists this step's key as an earlier one. A re-walk from an older cursor counts it as done.
     */
    static boolean superseded(ExecutionContext ctx, PutItem item, Optional<JournalRecord> journaled) {
        IntPos pos = item.placement().pos();
        return journaled.isPresent() && ctx.registry().at(pos)
                .filter(e -> e.jobId().equals(ctx.job().jobId()) && e.earlierKeys().contains(item.ledgerKey())
                        && BlockMatch.exact(ctx.world().read(pos).block(), e.placed(), Set.of())).isPresent();
    }

    /** A block the assembly (P10/P13) has already moved into its contraption: its position is never written again. */
    static boolean assembledAway(ExecutionContext ctx, Placement p) {
        return p.verify() == VerifyMode.ASSEMBLED_AWAY && p.assemblyGroup() != null
                && ctx.registry().assemblies().containsKey(p.assemblyGroup());
    }

    static List<ItemCount> cost(Placement p) {
        return BlockToItem.cost(p.block()).map(List::of).orElse(List.of());
    }

    static List<ItemCount> ground(BlockSpec cut) {
        return BlockToItem.cutYield(cut).map(List::of).orElse(List.of());
    }

    private static boolean survival(ExecutionContext ctx) {
        return ctx.job().materialPolicy() == MaterialPolicy.SURVIVAL_CONSUME;
    }

    /** The read-only first pass: what this run may write and move, logged before anything changes. */
    static List<WalEntry> intents(ExecutionContext ctx, int cursor, int allowance, long run) {
        List<WalEntry> out = new ArrayList<>();
        JobProgram program = ctx.program();
        String jobId = ctx.job().jobId();
        MaterialLedger ledger = ctx.ledgers().of(jobId);
        int done = 0;
        for (int c = cursor; c < program.size(); c++, done++) {
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            if (program.isRestore(c)) {
                RestoreItem item = program.restore(c);
                WorldCell cell = ctx.world().read(item.pos());
                if (!cell.loaded()) {
                    break;
                }
                MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
                if (BlockMatch.satisfies(cell.block(), item.restoreTo(), Set.of())) {
                    if (ctx.journal().at(JournalRecord.restoreIndex(c)).isEmpty()) {
                        continue;
                    }
                } else if (Conflicts.detect(item.pos(), item.expectedNow(), cell.observed(), item.volatileProps()).isPresent()) {
                    continue;
                } else {
                    out.add(new WalEntry.PlaceIntent(jobId, run, restoreRecord(c, item, cell)));
                    if (item.dropContents() && cell.observed().hasBlockEntity()) {
                        out.add(new WalEntry.DropIntent(jobId, run, item.pos()));
                    }
                }
                for (int key : item.sourceKeys()) {
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RECLAIM), source.owedReclaim(key));
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.CHARGE),
                            ItemCount.minus(source.returned(key), source.consumed(key)));
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RETURN), source.owedReturn(key));
                }
                continue;
            }
            PutItem item = program.put(c);
            Placement p = item.placement();
            WorldCell cell = ctx.world().read(p.pos());
            if (!cell.loaded()) {
                break;
            }
            if (ctx.outcome().hasRestoreConflictAt(p.pos())) {
                continue;
            }
            if (assembledAway(ctx, p)) {
                if (survival(ctx)) {
                    intend(out, jobId, run, new OpKey(jobId, item.ledgerKey(), MaterialOp.CHARGE),
                            ItemCount.minus(cost(p), ledger.consumed(item.ledgerKey())));
                }
                continue;
            }
            Optional<JournalRecord> journaled = ctx.journal().at(item.ledgerKey());
            ReplaceDecision d = superseded(ctx, item, journaled) ? new ReplaceDecision.AlreadyDone()
                    : ReplaceRules.decide(p, cell, journaled.isPresent(), ownedFor(ctx, item));
            if (d instanceof ReplaceDecision.Refused) {
                if (!ctx.skipSiteChanges()) {
                    break;
                }
                continue;
            }
            int key = item.ledgerKey();
            if (journaled.isEmpty() && ownedFor(ctx, item).isPresent() && BlockMatch.satisfies(cell.block(), p.block(), Set.of())) {
                continue;
            }
            if (d instanceof ReplaceDecision.Place place) {
                boolean cut = survival(ctx) && place.destruction() == Destruction.TERRAIN;
                out.add(new WalEntry.PlaceIntent(jobId, run, putRecord(item, cell, cut)));
                if (survival(ctx)) {
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE),
                            ItemCount.minus(cost(p), ledger.consumed(key)));
                    if (cut) {
                        intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD),
                                ItemCount.minus(ground(cell.block()), ledger.yielded(key)));
                    }
                }
            } else if (survival(ctx)) {
                intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE), ItemCount.minus(cost(p), ledger.consumed(key)));
                JournalRecord rec = journaled.orElseThrow();
                if (rec.terrainCut()) {
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD),
                            ItemCount.minus(ground(rec.before()), ledger.yielded(key)));
                }
            }
        }
        return out;
    }

    private static void intend(List<WalEntry> out, String jobId, long run, OpKey key, List<ItemCount> items) {
        if (!items.isEmpty()) {
            out.add(new WalEntry.MaterialIntent(jobId, run, key, items));
        }
    }

    static JournalRecord putRecord(PutItem item, WorldCell cell, boolean cut) {
        Placement p = item.placement();
        // keyed by the ledger key: a repair round's placement of the same index is its own record (JobProgram.repair)
        return new JournalRecord(item.ledgerKey(), p.pos(), cell.block(), cell.observed().hasBlockEntity(), p.block(),
                item.ledgerKey(), cut);
    }

    static JournalRecord restoreRecord(int programIndex, RestoreItem item, WorldCell cell) {
        return new JournalRecord(JournalRecord.restoreIndex(programIndex), item.pos(), cell.block(),
                cell.observed().hasBlockEntity(), item.restoreTo(), JournalRecord.NO_LEDGER_KEY);
    }

    private static StepReport walk(ExecutionContext ctx, int cursor, int allowance, RunLog log) {
        List<IntPos> touched = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        JobProgram program = ctx.program();
        int c = cursor;
        int done = 0;
        while (c < program.size()) {
            // a piece (a door's two halves) is never split at the allowance: its partner would pop off with a drop
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            boolean restore = program.isRestore(c);
            Step s = restore ? restore(ctx, c, touched, conflicts, log) : put(ctx, c, touched, conflicts, log);
            if (s.pause() != null) {
                return new StepReport(c, s.pause(), touched, s.shortage(), conflicts);
            }
            if (restore && !continuesPiece(program, c + 1)) {
                settlePieceEndingAt(ctx, c, log);
            }
            c++;
            done++;
        }
        return new StepReport(c, null, touched, List.of(), conflicts);
    }

    /** Neighbour updates for the positions of the piece this run really restored (a conflicted one stays untouched). */
    private static void settlePieceEndingAt(ExecutionContext ctx, int last, RunLog log) {
        List<IntPos> piece = new ArrayList<>();
        int i = last;
        piece.add(ctx.program().restore(i).pos());
        while (continuesPiece(ctx.program(), i)) {
            i--;
            piece.add(0, ctx.program().restore(i).pos());
        }
        piece.removeIf(p -> !log.restored.contains(p));
        if (!piece.isEmpty()) {
            ctx.world().settle(piece);
        }
    }

    /** Moves what one ledger operation still owes. A take pauses on a shortage and changes nothing. */
    private static Step settle(ExecutionContext ctx, RunLog log, OpKey key, List<ItemCount> owed) {
        if (owed.isEmpty()) {
            return Step.NEXT;
        }
        MaterialPort port = ctx.materials();
        List<Move> moves;
        if (key.op().takesFromOwner()) {
            List<Stock> stocks = port.stocks(Stocks.ids(owed));
            List<ItemCount> missing = Stocks.missing(owed, stocks);
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
            moves = Stocks.takeMoves(owed, stocks);
        } else {
            Optional<String> target = port.giveTarget(owed);
            if (target.isEmpty()) {
                return new Step(PauseReason.NO_ROOM, owed);
            }
            moves = Stocks.giveMoves(owed, target.get());
        }
        if (!port.apply(moves)) {
            throw new IllegalStateException("materials changed on the main thread between the count and the move");
        }
        MaterialLedger ledger = ctx.ledgers().of(key.jobId());
        ledger.record(key.op(), key.ledgerKey(), owed);
        // the saved ledger then says which runs it already holds, so recovery never folds a run in twice
        ledger.markAppliedRun(log.run);
        log.moves.add(new WalEntry.OpMoves(key, moves));
        log.movedInventory |= moves.stream().anyMatch(m -> m.sourceId().equals(MaterialPort.INVENTORY));
        return Step.NEXT;
    }

    private static Step put(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts, RunLog log) {
        PutItem item = ctx.program().put(i);
        Placement p = item.placement();
        WorldCell cell = ctx.world().read(p.pos());
        if (!cell.loaded()) {
            return Step.pause(PauseReason.CHUNK_UNLOADED);
        }
        if (ctx.outcome().hasRestoreConflictAt(p.pos())) {
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.CONFLICT));
            return Step.NEXT;
        }
        String jobId = ctx.job().jobId();
        MaterialLedger ledger = ctx.ledgers().of(jobId);
        int key = item.ledgerKey();
        OpKey charge = new OpKey(jobId, key, MaterialOp.CHARGE);
        OpKey yield = new OpKey(jobId, key, MaterialOp.YIELD);
        if (assembledAway(ctx, p)) {
            // the assembly took this block into its contraption: air here is right, and the block is never placed again
            return survival(ctx) ? settle(ctx, log, charge, ItemCount.minus(cost(p), ledger.consumed(key))) : Step.NEXT;
        }
        Optional<JournalRecord> journaled = ctx.journal().at(item.ledgerKey());
        ReplaceDecision d = superseded(ctx, item, journaled) ? new ReplaceDecision.AlreadyDone()
                : ReplaceRules.decide(p, cell, journaled.isPresent(), ownedFor(ctx, item));
        if (d instanceof ReplaceDecision.AlreadyDone) {
            if (!survival(ctx)) {
                return Step.NEXT;
            }
            // the block is there; settle what the ledger still owes (a crash can leave the charge or the ground unsettled)
            Step s = settle(ctx, log, charge, ItemCount.minus(cost(p), ledger.consumed(key)));
            if (s.pause() != null) {
                return s;
            }
            JournalRecord rec = journaled.orElseThrow();
            return rec.terrainCut() ? settle(ctx, log, yield, ItemCount.minus(ground(rec.before()), ledger.yielded(key)))
                    : Step.NEXT;
        }
        if (journaled.isEmpty() && ownedFor(ctx, item).isPresent() && BlockMatch.satisfies(cell.block(), p.block(), Set.of())) {
            // a repair step (its own ledger key) that finds our block already right: nothing to place, nothing to charge
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.ALREADY_THERE));
            return Step.NEXT;
        }
        if (d instanceof ReplaceDecision.Refused) {
            Conflict conflict = new Conflict(p.pos(), p.block(), cell.observed(),
                    cell.block().isAir() ? ConflictKind.MISSING : ConflictKind.PLAYER_MODIFIED);
            if (ctx.outcome().addConflict(conflict)) {
                conflicts.add(conflict);
            }
            if (!ctx.skipSiteChanges()) {
                return Step.pause(PauseReason.SITE_CHANGED);
            }
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.SITE_CHANGED));
            return Step.NEXT;
        }
        Destruction destruction = ((ReplaceDecision.Place) d).destruction();
        List<ItemCount> owed = survival(ctx) ? ItemCount.minus(cost(p), ledger.consumed(key)) : List.of();
        if (!owed.isEmpty()) {
            List<ItemCount> missing = Stocks.missing(owed, ctx.materials().stocks(Stocks.ids(owed)));
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
        }
        boolean cutting = survival(ctx) && destruction == Destruction.TERRAIN;
        List<ItemCount> handOver = cutting ? ItemCount.minus(ground(cell.block()), ledger.yielded(key)) : List.of();
        if (!handOver.isEmpty() && ctx.materials().giveTarget(handOver).isEmpty()) {
            // the cut ground has nowhere to go: wait for room before the world changes (nothing is dropped)
            return new Step(PauseReason.NO_ROOM, handOver);
        }
        PlaceResult result = ctx.world().place(p.pos(), p.block(), p.blockEntityConfig(), ctx.job().ownerUuid());
        if (result != PlaceResult.PLACED) {
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(),
                    result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
            return Step.NEXT;
        }
        settle(ctx, log, charge, owed);
        boolean cut = survival(ctx) && destruction == Destruction.TERRAIN;
        JournalRecord rec = putRecord(item, cell, cut);
        ctx.journal().record(rec);
        ctx.registry().apply(jobId, rec);
        log.written.add(rec.placementIndex());
        if (cut) {
            settle(ctx, log, yield, ItemCount.minus(ground(cell.block()), ledger.yielded(key)));
        }
        ctx.outcome().resolveConflictAt(p.pos());
        touched.add(p.pos());
        return Step.NEXT;
    }

    /**
     * Settles a removal for every ledger key of its source job at the position (earlier writes too: a foundation laid on
     * the job's own ground cut hands the cut ground back as well as the foundation's charge). {@code takeBackOverRefund}:
     * a refund whose charge never reached the disk is taken back (only on a re-walk of a done removal).
     */
    private static Step settleRemoval(ExecutionContext ctx, RunLog log, RestoreItem item, MaterialLedger source,
                                      boolean takeBackOverRefund) {
        for (int key : item.sourceKeys()) {
            Step s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.RECLAIM), source.owedReclaim(key));
            if (s.pause() == null && takeBackOverRefund) {
                s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.CHARGE),
                        ItemCount.minus(source.returned(key), source.consumed(key)));
            }
            if (s.pause() == null) {
                s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.RETURN), source.owedReturn(key));
            }
            if (s.pause() != null) {
                return s;
            }
        }
        return Step.NEXT;
    }

    private static Step restore(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts, RunLog log) {
        RestoreItem item = ctx.program().restore(i);
        WorldCell cell = ctx.world().read(item.pos());
        if (!cell.loaded()) {
            return Step.pause(PauseReason.CHUNK_UNLOADED);
        }
        String jobId = ctx.job().jobId();
        MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
        JournalRecord rec = restoreRecord(i, item, cell);
        if (BlockMatch.satisfies(cell.block(), item.restoreTo(), Set.of())) {
            if (ctx.journal().at(rec.placementIndex()).isEmpty()) {
                // someone else emptied it: nothing is owed for a block this job never took away
                ctx.journal().record(rec);
                ctx.registry().apply(jobId, rec);
                return Step.NEXT;
            }
            // this job restored it already (a crash cut the run short): settle what is still owed, and take back a refund
            // whose charge never reached the disk (a refund can only be for what was really paid)
            return settleRemoval(ctx, log, item, source, true);
        }
        Optional<Conflict> conflict = Conflicts.detect(item.pos(), item.expectedNow(), cell.observed(), item.volatileProps());
        if (conflict.isPresent()) {
            if (ctx.outcome().addRestoreConflict(conflict.get())) {
                conflicts.add(conflict.get());
            }
            ctx.outcome().skip(new SkippedPlacement(rec.placementIndex(), item.pos(), SkippedPlacement.CONFLICT));
            return Step.NEXT;
        }
        List<ItemCount> owedReclaim = List.of();
        List<ItemCount> owedReturn = List.of();
        for (int key : item.sourceKeys()) {
            owedReclaim = ItemCount.plus(owedReclaim, source.owedReclaim(key));
            owedReturn = ItemCount.plus(owedReturn, source.owedReturn(key));
        }
        if (!owedReclaim.isEmpty()) {
            List<ItemCount> missing = Stocks.missing(owedReclaim, ctx.materials().stocks(Stocks.ids(owedReclaim)));
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
        }
        if (!owedReturn.isEmpty() && ctx.materials().giveTarget(owedReturn).isEmpty()) {
            // the refund has nowhere to go: wait for room before the world changes (nothing is dropped)
            return new Step(PauseReason.NO_ROOM, owedReturn);
        }
        PlaceResult result = ctx.world().restore(item.pos(), item.restoreTo(), ctx.job().ownerUuid(), item.dropContents());
        if (result != PlaceResult.PLACED) {
            ctx.outcome().skip(new SkippedPlacement(rec.placementIndex(), item.pos(),
                    result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
            return Step.NEXT;
        }
        settleRemoval(ctx, log, item, source, false);
        ctx.journal().record(rec);
        ctx.registry().apply(jobId, rec);
        log.written.add(rec.placementIndex());
        log.restored.add(item.pos());
        touched.add(item.pos());
        return Step.NEXT;
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 設置と撤去の実行(ConstructionExecutor)を追加(冪等な再開・材料の消費と返却・置けなくなった位置での停止)(自然言語→工場建設 P4 Task 9)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 10: L7の差分と修復(`build.verify`: `SparseSnapshot`・`SnapshotDiff`・`RepairPlanner`・`SnapshotCollector`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/verify/{SparseSnapshot,CompareScope,DeviationKind,Deviation,SnapshotDiff,RepairPlan,RepairPlanner,SnapshotCollector,VolatileProps}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/verify/{SnapshotDiffTest,RepairPlannerTest,SnapshotCollectorTest,VolatilePropsTest}.java`
- Modify: `src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java`(`ALLOWED`に`build.verify`)

**Interfaces:**
- Consumes: `build.compile.{PlacementManifest,Placement,ObservedBlock,BlockMatch,Conflict,ConflictKind}`、`build.parts.{VerifyMode,BuildPhase,PartTypeRegistry,PartType}`、`build.model.{BlockSpec,IntPos}`(`build.verify`は`construction`を見ない)
- Produces:
  - `record SparseSnapshot(Map<IntPos,ObservedBlock> blocks)`、`MAX_POSITIONS = 20_000`(F-21。これを超える位置は窓に分けて読む)
  - `sealed interface CompareScope { UpToCursor(int cursor), Phases(Set<BuildPhase>), IndexRange(int from, int toExclusive), All }`、`CompareScope.ALL`、`boolean includes(Placement)`(`IndexRange`はこの計画で足す: 20,000を超える施工リストを窓ごとに比べるため)
  - `enum DeviationKind {MISSING, WRONG_BLOCK, WRONG_STATE, EXTRA, BLOCKED}`、`record Deviation(int placementIndex, BlockSpec expected, ObservedBlock observed, DeviationKind kind)`
  - `SnapshotDiff.compare(PlacementManifest, SparseSnapshot, CompareScope, Function<String,Set<String>> volatileOfNode) → SnapshotDiff.Result(List<Deviation> deviations, List<Integer> unread)`、`public static boolean statesMatch(VerifyMode, BlockSpec observed, BlockSpec expected, Set<String> volatileProps)`(同じIDのときの状態の比べ方)
  - `record RepairPlan(List<Integer> reapply, List<Conflict> conflicts, List<Deviation> unfixable)`、`RepairPlanner.plan(PlacementManifest, List<Deviation>, IntFunction<Optional<BlockSpec>> journaledBefore, Predicate<IntPos> placedByProject, Set<Integer> denied) → RepairPlan`
  - `final class SnapshotCollector`: `SnapshotCollector(List<IntPos> positions)`、`DEFAULT_READS_PER_TICK = 1_000`(F-21: 既存の`MAX_BLOCKS_PER_QUERY=1000`と同じ考え方)、`List<IntPos> nextBatch(int max)`(今の窓の中で、まだ読んでいない位置)、`void accept(IntPos, ObservedBlock)`、`void unloaded(IntPos)`、`boolean windowFull()`、`Window takeWindow()`(`record Window(int fromIndex, int toIndexExclusive, SparseSnapshot snapshot)`)、`boolean done()`、`List<IntPos> unloadedPositions()`、`void resetUnloaded()`
  - `VolatileProps.of(Map<String,String> nodeTypes, PartTypeRegistry) → Function<String,Set<String>>`(ノードID→部品の`volatileProps`。知らないノード・整地のノードは空)
- 比べ方(**これが正本**。`01` 4節・8節、`03` L7): 範囲外は比べない。`ASSEMBLED_AWAY`は比べない(組み立ての検査はP10・P13)。読めていない位置は`unread`(欠落にしない)。期待が空気→観測が空気でなければ`EXTRA`。観測が空気→`MISSING`。IDが違う→`WRONG_BLOCK`。`BLOCK_ONLY`はIDだけ。`EXACT`は`BlockMatch.exact(観測, 期待, 稼働で変わる状態)`(**観測と期待の両方の状態を全部**比べる。期待に書いていない状態が観測にあれば違い)、`STATE_SUBSET`は`BlockMatch.satisfies(観測, 期待, 稼働で変わる状態)`(期待に書いた状態だけ)で、合わなければ`WRONG_STATE`(設計`05` 1.1.1のとおり2つを分ける。施工リストの`EXACT`は、コンパイラが全部の状態を書いた位置か、状態を持たないブロックだけに付く。見本の小屋では丸石・オークの板・石レンガ)。修復の分け方: 保護で拒否された位置は`BLOCKED`として直さない。`MISSING`は置き直す。`WRONG_STATE`は、このプロジェクトが置いた位置なら置き直し、そうでなければ`Conflict`。`WRONG_BLOCK`・`EXTRA`は、**このプロジェクトがそこに置いたことがある(登録簿に記録がある)なら、いつでも`Conflict`**(施工前の地面が戻っていても、それはプレイヤーが戻した物なので上書きしない。Codexのレビュー2026-09-30)。登録簿に記録が無い位置だけ、観測が施工前の状態(記録の`before`)と同じなら「まだ置けていないだけ」で置き直し、違えば`Conflict`。

- [ ] **Step 1: 失敗するテストを書く**

`SnapshotDiffTest.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SnapshotDiffTest {
    private static final Function<String, Set<String>> NONE = node -> Set.of();

    static Map<IntPos, ObservedBlock> asBuilt(PlacementManifest m) {
        Map<IntPos, ObservedBlock> out = new HashMap<>();
        for (Placement p : m.placements()) {
            out.put(p.pos(), new ObservedBlock(p.block()));
        }
        return out;
    }

    private static List<DeviationKind> kinds(SnapshotDiff.Result r) {
        return r.deviations().stream().map(Deviation::kind).toList();
    }

    @Test
    void theGoldenHutAsBuiltHasNoDeviation() {
        PlacementManifest hut = TestManifests.hut();
        SnapshotDiff.Result r = SnapshotDiff.compare(hut, new SparseSnapshot(asBuilt(hut)), CompareScope.ALL, NONE);
        assertEquals(List.of(), r.deviations());
        assertEquals(List.of(), r.unread());
    }

    @Test
    void everyKindIsFound() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(1, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(2, 64, 0), BlockSpec.of("minecraft:oak_stairs", "facing", "north"), "w",
                        BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET),
                TestManifests.put(new IntPos(3, 64, 0), BlockSpec.AIR, "w", BuildPhase.ENVELOPE, VerifyMode.EXACT));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 4, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = asBuilt(m);
        world.put(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.AIR));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:gold_block")));
        world.put(new IntPos(2, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:oak_stairs", "facing", "east", "shape", "straight")));
        world.put(new IntPos(3, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:dirt")));
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(DeviationKind.MISSING, DeviationKind.WRONG_BLOCK, DeviationKind.WRONG_STATE, DeviationKind.EXTRA),
                kinds(r));
        assertEquals(List.of(0, 1, 2, 3), r.deviations().stream().map(Deviation::placementIndex).toList());
    }

    @Test
    void volatileStatesAndBlockOnlyAreNotCompared() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), BlockSpec.of("minecraft:oak_door", "open", "false", "facing", "south"),
                        "door-1", BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET),
                TestManifests.put(new IntPos(1, 64, 0), BlockSpec.of("minecraft:glass_pane", "north", "true"), "win-1",
                        BuildPhase.ENVELOPE, VerifyMode.BLOCK_ONLY));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = new HashMap<>();
        world.put(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:oak_door", "open", "true", "facing", "south")));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:glass_pane", "north", "false")));
        Function<String, Set<String>> doorVolatile = node -> node.equals("door-1") ? Set.of("open", "powered") : Set.of();
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, doorVolatile).deviations());
    }

    @Test
    void theScopeKeepsUnbuiltPositionsFromCountingAsMissing() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, ObservedBlock> half = new HashMap<>();
        for (Placement p : m.placements()) {
            half.put(p.pos(), new ObservedBlock(p.index() < 10 ? p.block() : BlockSpec.AIR));
        }
        SparseSnapshot s = new SparseSnapshot(half);
        assertEquals(List.of(), SnapshotDiff.compare(m, s, new CompareScope.UpToCursor(10), NONE).deviations());
        assertEquals(15, SnapshotDiff.compare(m, s, CompareScope.ALL, NONE).deviations().size());
        assertEquals(5, SnapshotDiff.compare(m, s, new CompareScope.IndexRange(10, 15), NONE).deviations().size());
        assertEquals(0, SnapshotDiff.compare(m, s, new CompareScope.Phases(Set.of(BuildPhase.DECORATION)), NONE).deviations().size());
    }

    @Test
    void unreadPositionsAreNotMissing() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, ObservedBlock> world = asBuilt(m);
        world.remove(m.placements().get(4).pos());
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(), r.deviations());
        assertEquals(List.of(4), r.unread());
    }

    @Test
    void assembledAwayPlacementsAreNotCompared() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(TestManifests.put(new IntPos(0, 64, 0),
                BlockSpec.of("minecraft:white_wool"), "sail", BuildPhase.ASSEMBLE, VerifyMode.ASSEMBLED_AWAY)));
        Map<IntPos, ObservedBlock> world = Map.of(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.AIR));
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE).deviations());
    }

    @Test
    void exactComparesEveryStateButStateSubsetOnlyTheListedOnes() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "y");
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), log, "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(1, 64, 0), log, "w", BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = new HashMap<>();
        world.put(new IntPos(0, 64, 0), new ObservedBlock(log.with("waterlogged", "true")));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(log.with("waterlogged", "true")));
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(0), r.deviations().stream().map(Deviation::placementIndex).toList(),
                "an unlisted observed state fails EXACT only");
        assertEquals(List.of(DeviationKind.WRONG_STATE), kinds(r));
    }

    @Test
    void theSnapshotIsBounded() {
        Map<IntPos, ObservedBlock> tooMany = new HashMap<>();
        for (int i = 0; i <= SparseSnapshot.MAX_POSITIONS; i++) {
            tooMany.put(new IntPos(i, 0, 0), new ObservedBlock(BlockSpec.AIR));
        }
        assertThrows(IllegalArgumentException.class, () -> new SparseSnapshot(tooMany));
    }
}
```

`RepairPlannerTest.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepairPlannerTest {
    private final PlacementManifest m = TestManifests.smallHut();

    private Deviation dev(int index, BlockSpec observed, DeviationKind kind) {
        return new Deviation(index, m.placements().get(index).block(), new ObservedBlock(observed), kind);
    }

    @Test
    void missingBlocksAndOurOwnTurnedBlocksAreReplaced() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(0, BlockSpec.AIR, DeviationKind.MISSING),
                dev(10, BlockSpec.of("minecraft:oak_planks", "x", "y"), DeviationKind.WRONG_STATE)),
                i -> Optional.of(BlockSpec.AIR), pos -> true, Set.of());
        assertEquals(List.of(0, 10), p.reapply());
        assertEquals(List.of(), p.conflicts());
    }

    @Test
    void aReplacedBlockIsAConflictNotARepair() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:gold_block"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.AIR), pos -> true, Set.of());
        assertEquals(List.of(), p.reapply(), "never overwrite what a player may have put there");
        assertEquals(1, p.conflicts().size());
        assertEquals(ConflictKind.PLAYER_MODIFIED, p.conflicts().get(0).kind());
        assertEquals(m.placements().get(12).pos(), p.conflicts().get(0).pos());
    }

    @Test
    void theStillUntouchedPreBuildBlockIsJustNotPlacedYet() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:short_grass"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.of("minecraft:short_grass")), pos -> false, Set.of());
        assertEquals(List.of(12), p.reapply());
    }

    @Test
    void aTurnedBlockWeDidNotPlaceIsAConflict() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(10, BlockSpec.of("minecraft:oak_planks"), DeviationKind.WRONG_STATE)),
                i -> Optional.empty(), pos -> false, Set.of());
        assertEquals(List.of(), p.reapply());
        assertEquals(1, p.conflicts().size());
    }

    @Test
    void protectedPositionsCannotBeFixedHere() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(3, BlockSpec.AIR, DeviationKind.MISSING)),
                i -> Optional.empty(), pos -> false, Set.of(3));
        assertEquals(List.of(), p.reapply());
        assertEquals(List.of(DeviationKind.BLOCKED), p.unfixable().stream().map(Deviation::kind).toList());
    }

    @Test
    void thePreBuildBlockPutBackWhereWePlacedIsAPlayersConflictNotARepair() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:short_grass"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.of("minecraft:short_grass")), pos -> true, Set.of());
        assertEquals(List.of(), p.reapply(), "our block was placed here, so the grass is somebody's doing");
        assertEquals(1, p.conflicts().size());
    }
}
```

`SnapshotCollectorTest.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotCollectorTest {
    @Test
    void readsInBatchesAndHandsOutBoundedWindows() {
        List<IntPos> positions = new ArrayList<>();
        for (int i = 0; i < SparseSnapshot.MAX_POSITIONS + 5; i++) {
            positions.add(new IntPos(i, 0, 0));
        }
        SnapshotCollector c = new SnapshotCollector(positions);
        int reads = 0;
        while (!c.windowFull()) {
            for (IntPos p : c.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
                c.accept(p, new ObservedBlock(BlockSpec.AIR));
                reads++;
            }
        }
        assertEquals(SparseSnapshot.MAX_POSITIONS, reads);
        SnapshotCollector.Window w = c.takeWindow();
        assertEquals(0, w.fromIndex());
        assertEquals(SparseSnapshot.MAX_POSITIONS, w.toIndexExclusive());
        assertFalse(c.done());
        for (IntPos p : c.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
            c.accept(p, new ObservedBlock(BlockSpec.AIR));
        }
        assertTrue(c.windowFull());
        assertEquals(5, c.takeWindow().snapshot().blocks().size());
        assertTrue(c.done());
    }

    @Test
    void unloadedPositionsAreRememberedForRetry() {
        SnapshotCollector c = new SnapshotCollector(List.of(new IntPos(0, 0, 0), new IntPos(1, 0, 0)));
        List<IntPos> batch = c.nextBatch(10);
        c.accept(batch.get(0), new ObservedBlock(BlockSpec.AIR));
        c.unloaded(batch.get(1));
        assertEquals(List.of(new IntPos(1, 0, 0)), c.unloadedPositions());
        assertFalse(c.windowFull(), "an unloaded position is not read yet");
        c.resetUnloaded();
        assertEquals(List.of(new IntPos(1, 0, 0)), c.nextBatch(10), "it is read again after the chunk loads");
    }
}
```

`VolatilePropsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VolatilePropsTest {
    @Test
    void doorsIgnoreOpenAndPoweredAndUnknownNodesIgnoreNothing() {
        Function<String, Set<String>> v = VolatileProps.of(Map.of("door-1", "micra:door", "wall-n", "micra:wall"),
                BuildingParts.registry());
        assertEquals(Set.of("open", "powered"), v.apply("door-1"));
        assertEquals(Set.of(), v.apply("wall-n"));
        assertEquals(Set.of(), v.apply("site-prep"));
        assertEquals(Set.of(), v.apply("no-such-node"));
    }
}
```
(`micra:door`の`volatileProps`が`open`・`powered`であることは、既存の`BuildingParts`の`volatileProps(STATE_OPEN, STATE_POWERED)`の行で確かめてある。)

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.verify.*" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`SparseSnapshot.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Map;

/** The blocks read at the manifest's own positions only (design 01, section 8; F-21: memory grows with the positions). */
public record SparseSnapshot(Map<IntPos, ObservedBlock> blocks) {
    public static final int MAX_POSITIONS = 20_000;

    public SparseSnapshot {
        if (blocks.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("a snapshot holds at most " + MAX_POSITIONS + " positions; read in windows");
        }
        blocks = Map.copyOf(blocks);
    }
}
```
`CompareScope.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import java.util.Set;

/** Which placements a comparison covers (design 01, section 8). IndexRange lets a large manifest be checked in windows. */
public sealed interface CompareScope {
    All ALL = new All();

    boolean includes(Placement p);

    record UpToCursor(int cursor) implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return p.index() < cursor;
        }
    }

    record Phases(Set<BuildPhase> phases) implements CompareScope {
        public Phases {
            phases = Set.copyOf(phases);
        }

        @Override
        public boolean includes(Placement p) {
            return phases.contains(p.phase());
        }
    }

    record IndexRange(int from, int toExclusive) implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return p.index() >= from && p.index() < toExclusive;
        }
    }

    record All() implements CompareScope {
        @Override
        public boolean includes(Placement p) {
            return true;
        }
    }
}
```
`DeviationKind.java`・`Deviation.java`(recordのみ、`requireNonNull`)。

`SnapshotDiff.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The strict comparison of L7 (design 03): each placement in scope against the block read at its position, by its
 * VerifyMode, never comparing the part's volatile states. Unread positions are listed apart: unknown is not missing.
 */
public final class SnapshotDiff {
    public record Result(List<Deviation> deviations, List<Integer> unread) {
        public Result {
            deviations = List.copyOf(deviations);
            unread = List.copyOf(unread);
        }
    }

    private SnapshotDiff() {
    }

    public static Result compare(PlacementManifest m, SparseSnapshot s, CompareScope scope,
                                 Function<String, Set<String>> volatileOfNode) {
        List<Deviation> out = new ArrayList<>();
        List<Integer> unread = new ArrayList<>();
        for (Placement p : m.placements()) {
            if (!scope.includes(p) || p.verify() == VerifyMode.ASSEMBLED_AWAY) {
                continue;
            }
            ObservedBlock obs = s.blocks().get(p.pos());
            if (obs == null) {
                unread.add(p.index());
                continue;
            }
            BlockSpec exp = p.block();
            BlockSpec o = obs.block();
            DeviationKind kind = null;
            if (exp.isAir()) {
                kind = o.isAir() ? null : DeviationKind.EXTRA;
            } else if (o.isAir()) {
                kind = DeviationKind.MISSING;
            } else if (!o.blockId().equals(exp.blockId())) {
                kind = DeviationKind.WRONG_BLOCK;
            } else if (!statesMatch(p.verify(), o, exp, volatileOfNode.apply(p.partNodeId()))) {
                kind = DeviationKind.WRONG_STATE;
            }
            if (kind != null) {
                out.add(new Deviation(p.index(), exp, obs, kind));
            }
        }
        return new Result(out, unread);
    }

    /** Design 05, 1.1.1: BLOCK_ONLY looks at the id only, EXACT at every state, STATE_SUBSET at the listed states. */
    public static boolean statesMatch(VerifyMode mode, BlockSpec observed, BlockSpec expected, Set<String> volatileProps) {
        return switch (mode) {
            case BLOCK_ONLY, ASSEMBLED_AWAY -> true;
            case EXACT -> BlockMatch.exact(observed, expected, volatileProps);
            case STATE_SUBSET -> BlockMatch.satisfies(observed, expected, volatileProps);
        };
    }
}
```
`RepairPlan.java`: recordのみ(`List.copyOf`)。

`RepairPlanner.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Splits deviations into what L7 re-places, what it reports as a conflict and leaves alone, and what it cannot fix
 * (design 03, L7): missing blocks and the project's own turned blocks are re-placed; a different block is re-placed only
 * while it is still the untouched pre-build block, otherwise a player may have put it there and it is not overwritten.
 */
public final class RepairPlanner {
    private RepairPlanner() {
    }

    public static RepairPlan plan(PlacementManifest m, List<Deviation> deviations, IntFunction<Optional<BlockSpec>> journaledBefore,
                                  Predicate<IntPos> placedByProject, Set<Integer> denied) {
        List<Integer> reapply = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<Deviation> unfixable = new ArrayList<>();
        for (Deviation d : deviations) {
            IntPos pos = m.placements().get(d.placementIndex()).pos();
            if (denied.contains(d.placementIndex()) || d.kind() == DeviationKind.BLOCKED) {
                unfixable.add(new Deviation(d.placementIndex(), d.expected(), d.observed(), DeviationKind.BLOCKED));
                continue;
            }
            switch (d.kind()) {
                case MISSING -> reapply.add(d.placementIndex());
                case WRONG_STATE -> {
                    if (placedByProject.test(pos)) {
                        reapply.add(d.placementIndex());
                    } else {
                        conflicts.add(new Conflict(pos, d.expected(), d.observed(), ConflictKind.PLAYER_MODIFIED));
                    }
                }
                case WRONG_BLOCK, EXTRA -> {
                    // once the project placed here, anything else at the position was put there by someone: even the
                    // pre-build block (a player may have put the grass back) is theirs now and is never overwritten
                    Optional<BlockSpec> before = journaledBefore.apply(d.placementIndex());
                    if (!placedByProject.test(pos) && before.isPresent()
                            && BlockMatch.satisfies(d.observed().block(), before.get(), Set.of())) {
                        reapply.add(d.placementIndex());
                    } else {
                        conflicts.add(new Conflict(pos, d.expected(), d.observed(), ConflictKind.PLAYER_MODIFIED));
                    }
                }
                default -> unfixable.add(d);
            }
        }
        return new RepairPlan(reapply, conflicts, unfixable);
    }
}
```
(`journaledBefore`が空=まだ置いていない位置の場合、`WRONG_BLOCK`は「施工前がそれだった」と確かめられないので`Conflict`にする。L7は施工の完了後に走るので、施工前の記録が無い位置は、承認の後で誰かが置いた物である。)

`SnapshotCollector.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the positions of a program over several ticks (F-21: a bounded number per tick) into windows of at most
 * {@link SparseSnapshot#MAX_POSITIONS}. An unloaded position stays unread until {@link #resetUnloaded} queues it again.
 */
public final class SnapshotCollector {
    public static final int DEFAULT_READS_PER_TICK = 1_000;

    public record Window(int fromIndex, int toIndexExclusive, SparseSnapshot snapshot) {
    }

    private final List<IntPos> positions;
    private final Map<IntPos, ObservedBlock> read = new HashMap<>();
    private final Set<IntPos> unloaded = new LinkedHashSet<>();
    private final ArrayDeque<IntPos> retry = new ArrayDeque<>();
    private int windowStart;
    private int next;

    public SnapshotCollector(List<IntPos> positions) {
        this.positions = List.copyOf(positions);
    }

    private int windowEnd() {
        return Math.min(positions.size(), windowStart + SparseSnapshot.MAX_POSITIONS);
    }

    public List<IntPos> nextBatch(int max) {
        List<IntPos> out = new ArrayList<>();
        while (out.size() < max && !retry.isEmpty()) {
            out.add(retry.poll());
        }
        while (out.size() < max && next < windowEnd()) {
            out.add(positions.get(next++));
        }
        return out;
    }

    public void accept(IntPos pos, ObservedBlock block) {
        read.put(pos, block);
    }

    public void unloaded(IntPos pos) {
        unloaded.add(pos);
    }

    public List<IntPos> unloadedPositions() {
        return List.copyOf(unloaded);
    }

    /** Queues the unloaded positions again (after the job resumes from CHUNK_UNLOADED). */
    public void resetUnloaded() {
        retry.addAll(unloaded);
        unloaded.clear();
    }

    public boolean windowFull() {
        return next >= windowEnd() && unloaded.isEmpty() && retry.isEmpty();
    }

    public Window takeWindow() {
        if (!windowFull()) {
            throw new IllegalStateException("the window is not read yet");
        }
        Window w = new Window(windowStart, windowEnd(), new SparseSnapshot(read));
        read.clear();
        windowStart = windowEnd();
        next = windowStart;
        return w;
    }

    public boolean done() {
        return windowStart >= positions.size();
    }
}
```

`VolatileProps.java`:
```java
package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Node id to the part's volatile states (design 01, section 3): what L7, MODIFY and repair never compare or reset. */
public final class VolatileProps {
    private VolatileProps() {
    }

    public static Function<String, Set<String>> of(Map<String, String> nodeTypes, PartTypeRegistry registry) {
        Map<String, Set<String>> byNode = new HashMap<>();
        nodeTypes.forEach((node, type) -> byNode.put(node, registry.find(type).map(PartType::volatileProps).orElse(Set.of())));
        return node -> byNode.getOrDefault(node, Set.of());
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

`BuildPurityTest.java`の`ALLOWED`を、10個を超えても書けるように`Map.ofEntries`に書き換え、`build.verify`の行を足す(`build.verify`は新しい純Javaの包みなので、ここに無いと`thePurePackagesKeepTheMeasuredLayering`が「新しい包み」として落ちる。`import static java.util.Map.entry;`を足す):
```java
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            entry("build.model", Set.of()),
            entry("build.parts", Set.of("build.model")),
            entry("build.plan", Set.of("build.model", "build.parts", "lang")),
            entry("build.compile.gen", Set.of("build.model", "build.parts")),
            entry("build.compile", Set.of("build.model", "build.parts", "build.plan", "build.compile.gen")),
            entry("build.script", Set.of("build.model", "build.parts", "lang", "lang.ast")),
            entry("build.verify", Set.of("build.model", "build.parts", "build.compile")),
            entry("lang", Set.of("lang.ast", "build.model", "build.parts")),
            entry("lang.ast", Set.of()));
```
(Task 15で`build.analyze`の行`entry("build.analyze", Set.of("build.model"))`を同じ形で足す。)

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`BuildPurityTest`が`build.verify`も走査し、Minecraftを見ていないこと、`build.verify`が`build.model`・`build.parts`・`build.compile`だけを見ることを含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/build/verify の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/build/verify の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: L7の厳密な差分(SnapshotDiff)と保守的な修復の分類(RepairPlanner)、窓ごとの読み取り(SnapshotCollector)を追加(自然言語→工場建設 P4 Task 10)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 11: 区画(`SiteClaim`・`ClaimBook`・`OperatingBox`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。設計図の追記はコントローラ(Claude)が先に書き(コミットしない)、Devinが自分のコミットに`git add`で含める(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{SiteClaim,ClaimBook,OperatingBox}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/model/IssueCode.java`(`E_CLAIM_OVERLAP`・`E_CLAIM_LIMIT`・`E_CLAIM_INVALID`)、`docs/design/nl_factory_builder/05_parts_and_analyzers.md`(4.1節の表に3行。`IssueTest.enumMatchesTheDesignDocumentTable`が両者の一致を検査する)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{ClaimBookTest,OperatingBoxTest}.java`

**Interfaces:**
- Consumes: `build.model.{Box,IntPos,Issue,IssueCode,SemanticPlan,LogisticsPlan,LocalPos,BuildFrame}`、`build.compile.PlacementManifest`
- Produces:
  - `record SiteClaim(int schemaVersion, String claimId, UUID ownerUuid, String dimension, Box worldBox, Box operatingBox, boolean released, long createdTick)`(`01` 8節のまま。`SCHEMA_VERSION = 1`、`SiteClaim release()`)
  - `final class ClaimBook`: `DEFAULT_MAX_CLAIMS_PER_OWNER = 8`(F-4)、`ClaimBook(int maxPerOwner)`、`List<Issue> check(UUID owner, String dimension, Box operatingBox, String exceptClaimId)`、`SiteClaim reserve(String claimId, UUID owner, String dimension, Box worldBox, Box operatingBox, long tick)`、`void release(String claimId)`、`void restore(SiteClaim)`、`Optional<SiteClaim> find(String)`、`List<SiteClaim> active()`、`List<SiteClaim> all()`、`Optional<SiteClaim> claimAt(String dimension, IntPos pos)`、`List<Issue> checkOwned(String claimId, UUID owner, String dimension)`(`MODIFY`・`REPAIR`・`ROLLBACK`は、既存の区画が生きていて、同じディメンションで、ジョブの所有者の物でなければ`E-CLAIM-INVALID`)、`SiteClaim grow(String claimId, Box worldBox, Box operatingBox)`(範囲を広げる`MODIFY`は、古い箱と新しい箱の和に区画を1度に広げる。メインスレッドの1回の呼び出しなので、途中で他人が割り込めない)、`static boolean overlaps(Box, Box)`、`static Box union(Box, Box)`
  - `OperatingBox.of(PlacementManifest, SemanticPlan) → Box`: 施工範囲と、物流の発着場(`Dock.padBox`・`clearanceBox`)の世界の箱の和(D-24のP4の分。部品の作用範囲`EffectSpec`はP10・P13で足す)
  - 新しい`IssueCode`: `E_CLAIM_OVERLAP("E-CLAIM-OVERLAP")`・`E_CLAIM_LIMIT("E-CLAIM-LIMIT")`・`E_CLAIM_INVALID("E-CLAIM-INVALID")`

- [ ] **Step 1: 失敗するテストを書く**

`ClaimBookTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimBookTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final String OW = "minecraft:overworld";
    private static final Box HERE = new Box(0, 60, 0, 10, 80, 10);

    @Test
    void boxesThatShareOneBlockOverlap() {
        assertTrue(ClaimBook.overlaps(new Box(0, 0, 0, 5, 5, 5), new Box(5, 5, 5, 9, 9, 9)));
        assertFalse(ClaimBook.overlaps(new Box(0, 0, 0, 5, 5, 5), new Box(6, 0, 0, 9, 5, 5)));
        assertEquals(new Box(0, 0, 0, 9, 9, 9), ClaimBook.union(new Box(0, 0, 0, 5, 5, 5), new Box(5, 5, 5, 9, 9, 9)));
    }

    @Test
    void anotherClaimInTheSameDimensionIsRefusedAndStaysRefusedAfterItsJobEnds() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        List<Issue> issues = book.check(B, OW, new Box(5, 60, 5, 20, 80, 20), null);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP), issues.stream().map(Issue::code).toList());
        assertEquals("claim-a", issues.get(0).data().get("claim"));
        assertTrue(book.check(B, "minecraft:the_nether", new Box(5, 60, 5, 20, 80, 20), null).isEmpty(), "other dimension");
        assertTrue(book.check(A, OW, HERE, "claim-a").isEmpty(), "a MODIFY of the claim itself");
        book.release("claim-a");
        assertTrue(book.check(B, OW, HERE, null).isEmpty(), "released claims no longer protect");
        assertTrue(book.find("claim-a").orElseThrow().released());
    }

    @Test
    void anOwnerHoldsAtMostTheLimit() {
        ClaimBook book = new ClaimBook(2);
        book.reserve("c1", A, OW, new Box(0, 0, 0, 1, 1, 1), new Box(0, 0, 0, 1, 1, 1), 0L);
        book.reserve("c2", A, OW, new Box(10, 0, 0, 11, 1, 1), new Box(10, 0, 0, 11, 1, 1), 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_LIMIT),
                book.check(A, OW, new Box(20, 0, 0, 21, 1, 1), null).stream().map(Issue::code).toList());
        assertTrue(book.check(B, OW, new Box(20, 0, 0, 21, 1, 1), null).isEmpty());
        assertThrows(IllegalStateException.class, () -> book.reserve("c1", A, OW, HERE, HERE, 0L));
    }

    @Test
    void theClaimAtAPositionIsFoundByItsOperatingBox() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, new Box(0, 60, 0, 10, 120, 10), 0L);
        assertEquals("claim-a", book.claimAt(OW, new IntPos(3, 100, 3)).orElseThrow().claimId());
        assertTrue(book.claimAt(OW, new IntPos(30, 100, 3)).isEmpty());
        assertEquals(1, book.active().size());
    }

    @Test
    void workOnAnExistingClaimNeedsItLiveHereAndOurs() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(), book.checkOwned("claim-a", A, OW));
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", B, OW).get(0).code(), "another owner");
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", A, "minecraft:the_nether").get(0).code());
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-x", A, OW).get(0).code(), "no such claim");
        book.release("claim-a");
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", A, OW).get(0).code(), "released");
    }

    @Test
    void aGrowingModifyWidensItsClaimAndOthersMayNotMoveIn() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        Box wider = new Box(0, 60, 0, 20, 80, 10);
        assertEquals(new Box(0, 60, 0, 20, 80, 10), book.grow("claim-a", wider, wider).operatingBox());
        assertEquals(IssueCode.E_CLAIM_OVERLAP,
                book.check(B, OW, new Box(15, 60, 0, 16, 70, 5), null).get(0).code(), "the new part is claimed too");
    }
}
```

`OperatingBoxTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingBoxTest {
    @Test
    void theAirAboveADockIsPartOfTheClaim() {
        PlacementManifest m = TestManifests.smallHut();
        Site site = new Site(TestManifests.DIM, new BuildFrame(new IntPos(0, 64, 0), Facing.NORTH), new Box(-2, -9, -4, 4, 6, 2),
                "", "");
        LogisticsPlan.Dock dock = new LogisticsPlan.Dock("pad", new Box(0, 0, 0, 2, 0, 2), new Box(0, 1, 0, 2, 30, 2),
                Facing.NORTH, List.of(), List.of());
        SemanticPlan plan = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "p", 1, null, site, StyleSpec.EMPTY, List.of(), List.of(),
                new LogisticsPlan(List.of(dock), List.of(), List.of()), null);
        Box op = OperatingBox.of(m, plan);
        assertEquals(64 + 30, op.maxB(), "the clearance box reaches 30 blocks above the site origin");
        assertEquals(m.worldBounds().minB(), op.minB());
        assertEquals(m.worldBounds(), OperatingBox.of(m, SemanticPlan.empty("p")), "no logistics: the building's box");
    }
}
```

(担当: コントローラ。Devinに渡す前に書き、コミットしない。Devinのコミットに含まれる) `05_parts_and_analyzers.md`の4.1節の表の`E-SCRIPT-LIMIT`の行の直後に、次の2行を足す:
```
| `E-CLAIM-OVERLAP` | 区画(作用範囲を含む)が、ほかの区画と重なる(解放されるまで、ジョブが終わっても守られる。D-23) | 承認時・区画の予約時 | 場所の変更 |
| `E-CLAIM-LIMIT` | 1人が持てる区画の数(既定8)を超える | 承認時・区画の予約時 | 使わない区画の解体(ロールバック) |
| `E-CLAIM-INVALID` | 変更・点検・ロールバックの区画が無い・解放済み・別のディメンション・ほかの人の物 | ジョブの受け付け時 | 自分の区画で、その場所にいて出し直す |
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Claim*" --tests "io.github.khayashi4337.micradrone.construction.core.OperatingBoxTest" --tests "io.github.khayashi4337.micradrone.build.model.IssueTest" --console=plain`
Expected: FAIL(コンパイルエラー。`IssueTest`は表だけ先に増えたので不一致で失敗)。

- [ ] **Step 3: 実装する**

`IssueCode.java`の`E_SCRIPT_LIMIT`の後に`E_CLAIM_OVERLAP("E-CLAIM-OVERLAP"), E_CLAIM_LIMIT("E-CLAIM-LIMIT")`を足す(`E_SCRIPT_LIMIT`の行末の`;`を`,`に)。

`SiteClaim.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.Objects;
import java.util.UUID;

/** A reserved site (design 01, section 8). Kept while the factory exists (D-23); a job's end never releases it. */
public record SiteClaim(int schemaVersion, String claimId, UUID ownerUuid, String dimension, Box worldBox, Box operatingBox,
                        boolean released, long createdTick) {
    public static final int SCHEMA_VERSION = 1;

    public SiteClaim {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(worldBox, "worldBox");
        Objects.requireNonNull(operatingBox, "operatingBox");
    }

    public SiteClaim release() {
        return new SiteClaim(schemaVersion, claimId, ownerUuid, dimension, worldBox, operatingBox, true, createdTick);
    }
}
```
`ClaimBook.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's claims (04 F-4): a new site may not overlap another live claim's operating box in the same dimension,
 * and an owner holds at most a limited number. Reservation happens on the main thread, one approval after another, so
 * two overlapping approvals in one tick can never both win.
 */
public final class ClaimBook {
    public static final int DEFAULT_MAX_CLAIMS_PER_OWNER = 8;
    static final String SUBJECT_SITE = "site";
    static final String DATA_CLAIM = "claim";
    static final String DATA_OWNER = "owner";
    static final String DATA_LIMIT = "limit";

    private final int maxPerOwner;
    private final Map<String, SiteClaim> claims = new LinkedHashMap<>();

    public ClaimBook(int maxPerOwner) {
        this.maxPerOwner = maxPerOwner;
    }

    public List<Issue> check(UUID owner, String dimension, Box operatingBox, String exceptClaimId) {
        List<Issue> out = new ArrayList<>();
        int mine = 0;
        for (SiteClaim c : claims.values()) {
            if (c.released() || c.claimId().equals(exceptClaimId)) {
                continue;
            }
            if (c.ownerUuid().equals(owner)) {
                mine++;
            }
            if (c.dimension().equals(dimension) && overlaps(c.operatingBox(), operatingBox)) {
                out.add(Issue.of(IssueCode.E_CLAIM_OVERLAP, c.claimId(), List.of(SUBJECT_SITE),
                        "ここは、ほかの区画(" + c.claimId() + ")と重なっています",
                        Map.of(DATA_CLAIM, c.claimId(), DATA_OWNER, c.ownerUuid().toString()), List.of()));
            }
        }
        if (exceptClaimId == null && mine >= maxPerOwner) {
            out.add(Issue.of(IssueCode.E_CLAIM_LIMIT, "", List.of(SUBJECT_SITE),
                    "区画は1人" + maxPerOwner + "個までです。使わない建物を片付けてください",
                    Map.of(DATA_LIMIT, String.valueOf(maxPerOwner)), List.of()));
        }
        return out;
    }

    /**
     * A job that works on an existing claim (MODIFY, REPAIR, ROLLBACK) needs that claim to be live, in the job's
     * dimension, and held by the job's owner; otherwise it could touch somebody else's site or a released one.
     */
    public List<Issue> checkOwned(String claimId, UUID owner, String dimension) {
        SiteClaim c = claimId == null ? null : claims.get(claimId);
        String why = c == null ? "no such claim" : c.released() ? "released" : !c.dimension().equals(dimension)
                ? "another dimension" : !c.ownerUuid().equals(owner) ? "another owner" : null;
        if (why == null) {
            return List.of();
        }
        return List.of(Issue.of(IssueCode.E_CLAIM_INVALID, String.valueOf(claimId), List.of(SUBJECT_SITE),
                "この区画(" + claimId + ")では作業できません: " + why, Map.of(DATA_CLAIM, String.valueOf(claimId)), List.of()));
    }

    /** A MODIFY that reaches further grows its claim to cover both the old and the new site, in one step. */
    public SiteClaim grow(String claimId, Box worldBox, Box operatingBox) {
        SiteClaim c = claims.get(claimId);
        if (c == null || c.released()) {
            throw new IllegalArgumentException("no live claim " + claimId);
        }
        SiteClaim grown = new SiteClaim(c.schemaVersion(), c.claimId(), c.ownerUuid(), c.dimension(), union(c.worldBox(), worldBox),
                union(c.operatingBox(), operatingBox), false, c.createdTick());
        claims.put(claimId, grown);
        return grown;
    }

    public SiteClaim reserve(String claimId, UUID owner, String dimension, Box worldBox, Box operatingBox, long tick) {
        if (claims.containsKey(claimId)) {
            throw new IllegalStateException("claim " + claimId + " exists already");
        }
        SiteClaim c = new SiteClaim(SiteClaim.SCHEMA_VERSION, claimId, owner, dimension, worldBox, operatingBox, false, tick);
        claims.put(claimId, c);
        return c;
    }

    public void release(String claimId) {
        SiteClaim c = claims.get(claimId);
        if (c == null) {
            throw new IllegalArgumentException("no claim " + claimId);
        }
        claims.put(claimId, c.release());
    }

    public void restore(SiteClaim c) {
        claims.put(c.claimId(), c);
    }

    public Optional<SiteClaim> find(String claimId) {
        return Optional.ofNullable(claims.get(claimId));
    }

    public List<SiteClaim> active() {
        return claims.values().stream().filter(c -> !c.released()).toList();
    }

    public List<SiteClaim> all() {
        return List.copyOf(claims.values());
    }

    public Optional<SiteClaim> claimAt(String dimension, IntPos pos) {
        return active().stream().filter(c -> c.dimension().equals(dimension)
                && c.operatingBox().contains(pos.x(), pos.y(), pos.z())).findFirst();
    }

    public static boolean overlaps(Box a, Box b) {
        return a.minA() <= b.maxA() && b.minA() <= a.maxA() && a.minB() <= b.maxB() && b.minB() <= a.maxB()
                && a.minC() <= b.maxC() && b.minC() <= a.maxC();
    }

    public static Box union(Box a, Box b) {
        return new Box(Math.min(a.minA(), b.minA()), Math.min(a.minB(), b.minB()), Math.min(a.minC(), b.minC()),
                Math.max(a.maxA(), b.maxA()), Math.max(a.maxB(), b.maxB()), Math.max(a.maxC(), b.maxC()));
    }
}
```
`OperatingBox.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;

/**
 * The world box a claim protects (04 F-4): the building's box plus every dock's pad and the air above it that an
 * airship needs (D-24's P4 part; parts' EffectSpec reach joins in P10 and P13). Rotating by quarter turns maps a box's
 * opposite corners to opposite corners, so two corners are enough.
 */
public final class OperatingBox {
    private OperatingBox() {
    }

    public static Box of(PlacementManifest m, SemanticPlan plan) {
        Box out = m.worldBounds();
        if (plan.logistics() == null || plan.site() == null) {
            return out;
        }
        BuildFrame frame = plan.site().frame();
        for (LogisticsPlan.Dock d : plan.logistics().docks()) {
            out = ClaimBook.union(out, toWorld(frame, d.padBox()));
            out = ClaimBook.union(out, toWorld(frame, d.clearanceBox()));
        }
        return out;
    }

    private static Box toWorld(BuildFrame frame, Box local) {
        IntPos a = frame.toWorld(new LocalPos(local.minA(), local.minB(), local.minC()));
        IntPos b = frame.toWorld(new LocalPos(local.maxA(), local.maxB(), local.maxC()));
        return Box.of(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`IssueTest`の表との一致を含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/main/java/io/github/khayashi4337/micradrone/build/model/IssueCode.java
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add docs/design/nl_factory_builder/05_parts_and_analyzers.md
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 区画(SiteClaim・ClaimBook)と作用範囲(OperatingBox)、E-CLAIM-OVERLAP・E-CLAIM-LIMITを追加(自然言語→工場建設 P4 Task 11)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 12: 承認とハッシュ(`PlanCompilation`・`SurveyCache`・`ApprovalDesk`)と、重い計算のワーカー(`ServerWorkerPool`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。設計図の追記はコントローラ(Claude)が先に書き(コミットしない)、Devinが自分のコミットに`git add`で含める(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{Confirmations,AcceptedRisk,ApprovalRequest,PendingApproval,PlanSubmission,CompiledPlan,PlanCompilation,SurveyCache,Candidate,Approver,ApprovalRejection,ApprovalDecision,ApprovalDesk,WorkResult,ServerWorkerPool}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/model/IssueCode.java`(`E_REPLACE_UNCONFIRMED`)、`docs/design/nl_factory_builder/05_parts_and_analyzers.md`(4.1節に1行)、`src/test/java/io/github/khayashi4337/micradrone/build/compile/TestManifests.java`(`hutPlan()`を足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{PlanCompilationTest,SurveyCacheTest,ApprovalDeskTest,ServerWorkerPoolTest}.java`

**Interfaces:**
- Consumes: 既存の`PlanPatcher.normalize`・`PlanExpander.expand`・`PlanCompiler.compile`・`TemplateBundle.verifyAgainst`・`SlotResolver.NONE`・`Router.NONE`、Task 5の`TerrainPrep`・`SiteSurvey`、Task 6の`SafetyReport`、Task 11の`OperatingBox`
- Produces:
  - `record Confirmations(boolean terraform, boolean destructive)` + `NONE`
  - `record AcceptedRisk(String issueId, String note)`(`01` 11.1のまま)
  - `record ApprovalRequest(String manifestHash, String dimension, UUID playerUuid, List<AcceptedRisk> acceptedRisks, Confirmations confirmations)`(**設計の`expiresTick`は`PendingApproval`が持つので、要求には入れない。受け入れたリスクと確認を入れる**。F-3の`ApprovePlanPayload`の中身。Task 35で`01` 8節を直す)
  - `record PendingApproval(String manifestHash, String dimension, UUID owner, long expiresTick, List<Issue> issues, String surveyDigest)`(`01` 11.1のまま)
  - `record PlanSubmission(SemanticPlan plan, TemplateBundle templates, JobKind kind, String parentJobId, String claimId)`(`claimId`は`MODIFY`の対象の区画。`BUILD`は`null`。**`ExpandedPlan`や施工リストの欄は無い**: クライアントの展開結果を受け取れない形にしてある。F-3)
  - `record CompiledPlan(PlacementManifest manifest, List<Issue> issues, TerrainSummary terrain, Map<String,String> nodeTypes, Box operatingBox)`(`ERROR`があれば`manifest`・`operatingBox`は`null`)
  - `PlanCompilation.compile(PlanSubmission, PartTypeRegistry, PlaceableBlockPolicy, SiteSurvey, Map<String,String> bundledTemplateHashes) → CompiledPlan`(純粋。ワーカーで走る)
  - `final class SurveyCache`: `SURVEY_TTL_TICKS = 12_000`(F-3の既定10分=20tick×600秒)、`void pin(SiteSurvey, long now)`、`Optional<SiteSurvey> find(String digest, long now)`、`void expire(long now)`、`int size()`
  - `record Candidate(PendingApproval approval, CompiledPlan compiled, SafetyReport safety, PlanSubmission submission)`
  - `record Approver(UUID uuid, boolean operator, String currentDimension, boolean creative, MaterialPolicy forcedPolicy)`(`forcedPolicy`は設定で強制するときだけ。無ければ`null`)
  - `enum ApprovalRejection {NO_PENDING, NOT_OWNER, EXPIRED, HASH_MISMATCH, DIMENSION_MISMATCH, REGISTRY_CHANGED, SURVEY_EXPIRED, RISK_NOT_ACCEPTABLE, BLOCKING_ISSUES, TERRAFORM_UNCONFIRMED, DESTRUCTIVE_UNCONFIRMED, ASSEMBLY_NOT_AVAILABLE}`
  - `sealed interface ApprovalDecision { record Approved(ConstructionJob job, Candidate candidate); record Rejected(ApprovalRejection reason, List<Issue> blocking); }`
  - `final class ApprovalDesk`: `APPROVAL_TTL_TICKS = 12_000`(F-3の既定10分)、`PendingApproval offer(UUID owner, String dimension, CompiledPlan compiled, SafetyReport safety, PlanSubmission submission, String surveyDigest, long now)`、`Optional<Candidate> pending(UUID owner)`、`ApprovalDecision approve(ApprovalRequest, Approver, String registryVersion, SurveyCache, long now, Supplier<String> newJobId)`、`void dropOwner(UUID)`、`void dropAll()`、`void expire(long now)`
  - `sealed interface WorkResult<T> { Done<T>(T value), Failed<T>(Throwable error), Cancelled<T>() }`
  - `final class ServerWorkerPool implements AutoCloseable`: `DEFAULT_THREADS = 2`・`DEFAULT_MAX_QUEUED = 8`(設計に数値なし。F-1の「有界・取消可能・プレイヤーごとに同時1件」を満たす小さな値。Task 35で設計に書く)、`ServerWorkerPool(int threads, int maxQueued, Executor mainThread)`、`final class CancelToken { boolean cancelled(); }`、`interface Work<T> { T run(CancelToken) throws Exception; }`、`<T> boolean submit(UUID owner, Work<T> work, Consumer<WorkResult<T>> onMain)`・`<T> boolean submit(UUID owner, Callable<T> work, Consumer<WorkResult<T>> onMain)`、`boolean busy(UUID)`(**取り消した仕事でも、その仕事がスレッドから出るまでは`true`**: 取り消したコンパイルと次のコンパイルが並んで走らない。Codexのレビュー2026-09-30)、`boolean cancel(UUID)`(印を立て、走っていればスレッドに割り込み、待ち行列にいれば外してすぐ`Cancelled`を返す)、`void close()`
  - 新しい`IssueCode`: `E_REPLACE_UNCONFIRMED("E-REPLACE-UNCONFIRMED")`

- 承認の検査の順序(**これが正本**。F-3・D-3・D-27・D-12): 保留が無い→`NO_PENDING`。承認者が保留の所有者でもOPでもない→`NOT_OWNER`。期限切れ→`EXPIRED`(保留を消す)。ハッシュが違う→`HASH_MISMATCH`(偽の施工リスト)。承認者が今いるディメンション、または要求のディメンションが保留と違う→`DIMENSION_MISMATCH`。登録簿の版が違う→`REGISTRY_CHANGED`(`E-REGISTRY-VERSION`)。固定した調査が期限切れ→`SURVEY_EXPIRED`(再調査・再コンパイルが要る)。受け入れたリスクのIDが保留の`Issue`に無い、または`acceptable=false`→`RISK_NOT_ACCEPTABLE`。受け入れられていない`ERROR`が残る→`BLOCKING_ISSUES`。組み立てがある→`ASSEMBLY_NOT_AVAILABLE`(組み立てはP10・P13)。整地があるのに確認が無い→`TERRAFORM_UNCONFIRMED`(`E-TERRAFORM-UNCONFIRMED`)。破壊を伴う置換があるのに確認が無い→`DESTRUCTIVE_UNCONFIRMED`(`E-REPLACE-UNCONFIRMED`)。通れば、材料の方針(設定の強制が無ければ、承認者がクリエイティブなら`CREATIVE_FREE`、そうでなければ`SURVIVAL_CONSUME`。D-4)で`PENDING_APPROVAL`のジョブを作り、保留を消す。ジョブの所有者は承認した人(D-12)。

- [ ] **Step 1: 失敗するテストを書く**

`TestManifests.java`に足す:
```java
    /** The P3 golden hut's plan, patched from the golden JSON. */
    public static SemanticPlan hutPlan() {
        try {
            return GoldenHutTest.hut();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```
(`import io.github.khayashi4337.micradrone.build.model.SemanticPlan;`を足す。)

`PlanCompilationTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanCompilationTest {
    private static final String GOLDEN_HASH = TestManifests.hut().hash();

    private static CompiledPlan compile(SemanticPlan plan, SiteSurvey survey) {
        return PlanCompilation.compile(new PlanSubmission(plan, TemplateBundle.EMPTY, JobKind.BUILD, null, null),
                BuildingParts.registry(), PlaceableBlockPolicy.builtin(), survey, Map.of());
    }

    @Test
    void theServerRebuildsTheGoldenHutByItself() {
        SemanticPlan hut = TestManifests.hutPlan();
        CompiledPlan c = compile(hut, SiteSurvey.air(hut.site().dimension(), TestManifests.hut().worldBounds()));
        assertEquals(GOLDEN_HASH, c.manifest().hash());
        assertEquals("micra:wall", c.nodeTypes().get("wall-n"));
        assertEquals(c.manifest().worldBounds(), c.operatingBox());
        assertEquals(0, c.terrain().cut() + c.terrain().fill());
    }

    @Test
    void thePinnedSurveyAloneDecidesTheTerrainPartOfTheHash() {
        SemanticPlan hut = TestManifests.hutPlan();
        var bounds = TestManifests.hut().worldBounds();
        SiteSurvey ground = SiteSurvey.flat(hut.site().dimension(), bounds, 63, "minecraft:grass_block");
        String a = compile(hut, ground).manifest().hash();
        assertEquals(a, compile(hut, ground).manifest().hash(), "same pinned survey, same hash");
        assertNotEquals(GOLDEN_HASH, a, "sinking the foundation into the ground changes the replace policies");
    }

    @Test
    void aPlanWithoutASiteIsAnIssueNotACrash() {
        CompiledPlan c = compile(SemanticPlan.empty("x"), SiteSurvey.air(TestManifests.DIM, TestManifests.hut().worldBounds()));
        assertNull(c.manifest());
        assertTrue(c.issues().stream().anyMatch(i -> i.code() == IssueCode.E_SITE_MISSING));
    }

    @Test
    void aBundledTemplateMustMatchTheServersOwnCopyByIdAndHash() {
        SemanticPlan hut = TestManifests.hutPlan();
        SiteSurvey air = SiteSurvey.air(hut.site().dimension(), TestManifests.hut().worldBounds());
        TemplateBundle sent = TestParts.bundle();
        String id = TestParts.lineTemplate().id();
        for (Map<String, String> known : List.of(Map.<String, String>of(), Map.of(id, "0000-tampered"))) {
            CompiledPlan c = PlanCompilation.compile(new PlanSubmission(hut, sent, JobKind.BUILD, null, null),
                    BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air, known);
            assertNull(c.manifest(), "an unknown or altered template is refused before anything is compiled");
            assertNull(c.operatingBox());
            assertTrue(c.issues().stream().anyMatch(i -> i.code() == IssueCode.E_TEMPLATE_UNVERIFIED));
        }
        CompiledPlan ok = PlanCompilation.compile(new PlanSubmission(hut, sent, JobKind.BUILD, null, null),
                BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air, Map.of(id, TestParts.lineTemplate().hash()));
        assertTrue(ok.issues().stream().noneMatch(i -> i.code() == IssueCode.E_TEMPLATE_UNVERIFIED));
    }

    @Test
    void aSurveyOfAnotherDimensionIsABug() {
        SemanticPlan hut = TestManifests.hutPlan();
        assertThrows(IllegalArgumentException.class,
                () -> compile(hut, SiteSurvey.air("minecraft:the_nether", TestManifests.hut().worldBounds())));
    }
}
```

`SurveyCacheTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Box;
import org.junit.jupiter.api.Test;

class SurveyCacheTest {
    @Test
    void aPinnedSurveyLivesTenMinutes() {
        SurveyCache cache = new SurveyCache();
        SiteSurvey s = SiteSurvey.air(TestManifests.DIM, new Box(0, 0, 0, 3, 3, 3));
        cache.pin(s, 100L);
        assertEquals(s.digest(), cache.find(s.digest(), 100L + SurveyCache.SURVEY_TTL_TICKS).orElseThrow().digest());
        assertTrue(cache.find(s.digest(), 101L + SurveyCache.SURVEY_TTL_TICKS).isEmpty());
        cache.expire(101L + SurveyCache.SURVEY_TTL_TICKS);
        assertEquals(0, cache.size());
        assertEquals(12_000L, SurveyCache.SURVEY_TTL_TICKS);
    }
}
```

`ApprovalDeskTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.AssemblyStep;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.AssemblyKind;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalDeskTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final String OW = TestManifests.DIM;
    private static final long NOW = 1_000L;

    private final PlacementManifest hut = TestManifests.smallHut();
    private final SurveyCache surveys = new SurveyCache();
    private final SiteSurvey survey = SiteSurvey.air(OW, hut.worldBounds());

    private ApprovalDesk desk(List<Issue> issues, ReplacementSummary summary, PlacementManifest m) {
        surveys.pin(survey, NOW);
        ApprovalDesk d = new ApprovalDesk();
        CompiledPlan compiled = new CompiledPlan(m, List.of(), new TerrainSummary(summary.terrainCut(), summary.terrainFill()),
                Map.of(), m.worldBounds());
        d.offer(OWNER, OW, compiled, new SafetyReport(issues, summary),
                new PlanSubmission(SemanticPlan.empty("p"), TemplateBundle.EMPTY, JobKind.BUILD, null, null), survey.digest(), NOW);
        return d;
    }

    private static ReplacementSummary nothing() {
        return new ReplacementSummary(0, 0, 0, 0, 0, List.of());
    }

    private ApprovalDecision approve(ApprovalDesk d, String hash, Approver who, Confirmations c, List<AcceptedRisk> risks, long now) {
        return d.approve(new ApprovalRequest(hash, OW, OWNER, risks, c), who, TestManifests.REGISTRY_VERSION, surveys, now,
                () -> "job-7");
    }

    private static Approver owner(boolean creative) {
        return new Approver(OWNER, false, OW, creative, null);
    }

    private static ApprovalRejection reason(ApprovalDecision d) {
        return assertInstanceOf(ApprovalDecision.Rejected.class, d).reason();
    }

    @Test
    void theOwnerApprovesTheServersOwnHashAndGetsAPendingJob() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class,
                approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(), NOW + 1));
        ConstructionJob job = ok.job();
        assertEquals("job-7", job.jobId());
        assertEquals(JobState.PENDING_APPROVAL, job.state());
        assertEquals(MaterialPolicy.CREATIVE_FREE, job.materialPolicy());
        assertEquals(OWNER, job.ownerUuid());
        assertEquals("claim-job-7", job.claimId());
        assertEquals(hut.placements().size(), job.total());
        assertTrue(d.pending(OWNER).isEmpty(), "a pending approval is used once");
    }

    @Test
    void aFakeManifestHashIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        assertEquals(ApprovalRejection.HASH_MISMATCH, reason(approve(d, "0".repeat(64), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)));
        assertTrue(d.pending(OWNER).isPresent(), "a wrong hash does not burn the real pending approval");
    }

    @Test
    void approvalFromAnotherDimensionIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        Approver inNether = new Approver(OWNER, false, "minecraft:the_nether", true, null);
        assertEquals(ApprovalRejection.DIMENSION_MISMATCH, reason(approve(d, hut.hash(), inNether, Confirmations.NONE, List.of(),
                NOW + 1)));
    }

    @Test
    void expiryRegistrySurveyAndOwnershipAreChecked() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        assertEquals(ApprovalRejection.NOT_OWNER, reason(approve(d, hut.hash(), new Approver(OTHER, false, OW, true, null),
                Confirmations.NONE, List.of(), NOW + 1)));
        assertEquals(ApprovalRejection.REGISTRY_CHANGED, reason(d.approve(new ApprovalRequest(hut.hash(), OW, OWNER, List.of(),
                Confirmations.NONE), owner(true), "another-version", surveys, NOW + 1, () -> "job-7")));
        assertEquals(ApprovalRejection.EXPIRED, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + ApprovalDesk.APPROVAL_TTL_TICKS + 1)));
        assertEquals(ApprovalRejection.NO_PENDING, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + 2)), "an expired approval is gone");
        ApprovalDesk d2 = desk(List.of(), nothing(), hut);
        surveys.expire(NOW + SurveyCache.SURVEY_TTL_TICKS + 1);
        SurveyCache empty = new SurveyCache();
        assertEquals(ApprovalRejection.SURVEY_EXPIRED, reason(d2.approve(new ApprovalRequest(hut.hash(), OW, OWNER, List.of(),
                Confirmations.NONE), owner(true), TestManifests.REGISTRY_VERSION, empty, NOW + 1, () -> "job-7")));
    }

    @Test
    void anOperatorMayApproveAndBecomesTheOwner() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class, approve(d, hut.hash(),
                new Approver(OTHER, true, OW, false, null), Confirmations.NONE, List.of(), NOW + 1));
        assertEquals(OTHER, ok.job().ownerUuid(), "the job's owner is who approved it (D-12)");
        assertEquals(MaterialPolicy.SURVIVAL_CONSUME, ok.job().materialPolicy());
    }

    @Test
    void errorsBlockAndOnlyAcceptableRisksCanBeAccepted() {
        Issue blocked = Issue.of(IssueCode.E_SITE_BLOCKED, "unloaded", List.of("manifest"), "x");
        Issue warn = Issue.of(IssueCode.W_UNMODELED, List.of("press"), "x");
        ApprovalDesk d = desk(List.of(blocked, warn), nothing(), hut);
        assertEquals(ApprovalRejection.BLOCKING_ISSUES, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk(warn.id(), "")), NOW + 1)));
        assertEquals(ApprovalRejection.RISK_NOT_ACCEPTABLE, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk(blocked.id(), "")), NOW + 1)), "an E- code cannot be accepted away");
        assertEquals(ApprovalRejection.RISK_NOT_ACCEPTABLE, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk("W-NOT-THERE:x", "")), NOW + 1)));
        ApprovalDesk onlyWarn = desk(List.of(warn), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class, approve(onlyWarn, hut.hash(), owner(true),
                Confirmations.NONE, List.of(new AcceptedRisk(warn.id(), "")), NOW + 1));
        assertEquals(List.of(warn.id()), ok.job().acceptedRiskIds());
    }

    @Test
    void terraformingAndDestructiveReplacementNeedTheirConfirmations() {
        ReplacementSummary both = new ReplacementSummary(2, 0, 0, 9, 18, List.of(new IntPos(0, 63, 0)));
        ApprovalDesk d = desk(List.of(), both, hut);
        ApprovalDecision.Rejected t = assertInstanceOf(ApprovalDecision.Rejected.class,
                approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(), NOW + 1));
        assertEquals(ApprovalRejection.TERRAFORM_UNCONFIRMED, t.reason());
        assertEquals(IssueCode.E_TERRAFORM_UNCONFIRMED, t.blocking().get(0).code());
        assertEquals("9", t.blocking().get(0).data().get("cut"));
        assertEquals(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED, reason(approve(d, hut.hash(), owner(true),
                new Confirmations(true, false), List.of(), NOW + 1)));
        assertInstanceOf(ApprovalDecision.Approved.class, approve(d, hut.hash(), owner(true), new Confirmations(true, true), List.of(),
                NOW + 1));
    }

    @Test
    void assembliesWaitForTheirPhase() {
        PlacementManifest h = TestManifests.smallHut();
        PlacementManifest withAssembly = new PlacementManifest(h.manifestVersion(), h.planId(), h.planRevision(),
                h.registryVersion(), h.dimension(), h.frame(), h.worldBounds(), h.placements(),
                List.of(new AssemblyStep("g", AssemblyKind.WINDMILL, new IntPos(0, 64, 0), List.of(0), null)), h.bom(), h.phases(),
                h.hash());
        ApprovalDesk d = desk(List.of(), nothing(), withAssembly);
        assertEquals(ApprovalRejection.ASSEMBLY_NOT_AVAILABLE, reason(approve(d, h.hash(), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)));
    }

    @Test
    void anOwnerWhoLeavesLosesThePendingApproval() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        d.dropOwner(OWNER);
        assertEquals(ApprovalRejection.NO_PENDING, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + 1)));
    }
}
```

`ServerWorkerPoolTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ServerWorkerPoolTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final long WAIT_MS = 5_000L;

    private final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();

    /** Runs the main-thread queue until {@code done} is true or the time is up; the test thread plays the server thread. */
    private void pumpUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (!done.getAsBoolean()) {
            Runnable r = main.poll();
            if (r != null) {
                r.run();
            } else if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            } else {
                Thread.sleep(1);
            }
        }
    }

    @Test
    void resultsComeBackOnTheMainThreadAndOnePerOwner() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(2, 4, main::add)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<WorkResult<Integer>> got = new ArrayList<>();
            assertTrue(pool.submit(A, () -> {
                gate.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return 42;
            }, got::add));
            assertTrue(pool.busy(A));
            assertFalse(pool.submit(A, () -> 1, r -> { }), "one heavy job per player at a time");
            assertTrue(pool.submit(B, () -> 7, got::add), "another player is not blocked");
            gate.countDown();
            pumpUntil(() -> got.size() == 2);
            assertTrue(got.contains(new WorkResult.Done<>(42)));
            assertTrue(got.contains(new WorkResult.Done<>(7)));
            assertFalse(pool.busy(A));
        }
    }

    @Test
    void failuresAndCancelsAreResultsNotCrashes() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 4, main::add)) {
            List<WorkResult<Integer>> got = new ArrayList<>();
            pool.<Integer>submit(A, () -> {
                throw new IllegalStateException("boom");
            }, got::add);
            pumpUntil(() -> got.size() == 1);
            assertEquals("boom", assertInstanceOf(WorkResult.Failed.class, got.get(0)).error().getMessage());
            CountDownLatch never = new CountDownLatch(1);
            pool.submit(B, () -> {
                never.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return 1;
            }, got::add);
            assertTrue(pool.cancel(B));
            pumpUntil(() -> got.size() == 2);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(1));
        }
    }

    @Test
    void theQueueIsBounded() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 1, main::add)) {
            assertTrue(pool.submit(new UUID(1, 1), () -> {
                started.countDown();
                return gate.await(WAIT_MS, TimeUnit.MILLISECONDS);
            }, r -> { }));
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS), "the first job holds the only thread, not the queue");
            assertTrue(pool.submit(new UUID(1, 2), () -> 1, r -> { }));
            assertFalse(pool.submit(new UUID(1, 3), () -> 1, r -> { }), "a full queue refuses instead of piling up");
            gate.countDown();
        }
    }

    @Test
    void aCancelledJobKeepsItsOwnersSlotUntilItsWorkHasLeftTheThread() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(2, 4, main::add)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<WorkResult<Integer>> got = new ArrayList<>();
            assertTrue(pool.submit(A, token -> {
                started.countDown();
                while (release.getCount() > 0) {
                    Thread.onSpinWait(); // deaf to interrupts, like a long pure computation
                }
                return 1;
            }, got::add));
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(pool.cancel(A));
            assertTrue(pool.busy(A), "the work is still running: the slot is not free yet");
            assertFalse(pool.submit(A, () -> 2, r -> { }), "no second compile beside the cancelled one");
            release.countDown();
            pumpUntil(() -> got.size() == 1);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(0));
            assertFalse(pool.busy(A));
            assertTrue(pool.submit(A, () -> 3, got::add), "now the slot is free");
            pumpUntil(() -> got.size() == 2);
        }
    }

    @Test
    void cancelledWorkSeesItsToken() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 4, main::add)) {
            CountDownLatch started = new CountDownLatch(1);
            List<WorkResult<Boolean>> got = new ArrayList<>();
            pool.submit(A, token -> {
                started.countDown();
                while (!token.cancelled()) {
                    Thread.onSpinWait();
                }
                return true;
            }, got::add);
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            pool.cancel(A);
            pumpUntil(() -> got.size() == 1);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(0), "the work stopped on its own at the token");
        }
    }
}
```

(担当: コントローラ。Devinに渡す前に書き、コミットしない。Devinのコミットに含まれる) `05_parts_and_analyzers.md`の4.1節の表の`E-CLAIM-LIMIT`の行の直後に、次の1行を足す:
```
| `E-REPLACE-UNCONFIRMED` | 破壊を伴う置換(水・溶岩・木の葉・空のコンテナ)が、承認画面で確認されていない | 承認時 | 確認 |
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --tests "io.github.khayashi4337.micradrone.build.model.IssueTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`IssueCode.java`に`E_REPLACE_UNCONFIRMED("E-REPLACE-UNCONFIRMED")`を足す。

`Confirmations.java`: `public record Confirmations(boolean terraform, boolean destructive) { public static final Confirmations NONE = new Confirmations(false, false); }`。`AcceptedRisk`・`ApprovalRequest`(リストは`List.copyOf`、非null)・`PendingApproval`(`issues`は`List.copyOf`)・`PlanSubmission`(`plan`・`templates`・`kind`は非null)・`CompiledPlan`(`issues`・`nodeTypes`はコピー、`terrain`は非null)・`Candidate`・`Approver`はrecordのみ。`ApprovalRejection`は上の12個の`enum`。`ApprovalDecision`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;

/** The server's answer to an approval. A rejection names the check that failed and the issues that block. */
public sealed interface ApprovalDecision {
    record Approved(ConstructionJob job, Candidate candidate) implements ApprovalDecision {
    }

    record Rejected(ApprovalRejection reason, List<Issue> blocking) implements ApprovalDecision {
        public Rejected {
            blocking = List.copyOf(blocking);
        }
    }
}
```

`PlanCompilation.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.PlanCompiler;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainResult;
import io.github.khayashi4337.micradrone.build.compile.TerrainPrep;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's own rebuild of a submitted plan (04 F-3, D-3): templates checked against the server's copies,
 * parameters re-typed and re-checked, expanded, compiled and terraformed from the pinned survey, all with the server's
 * code. Nothing the client computed is an input. Pure and deterministic, so it runs on the worker pool.
 */
public final class PlanCompilation {
    private static final TerrainSummary NO_TERRAIN = new TerrainSummary(0, 0);

    private PlanCompilation() {
    }

    public static CompiledPlan compile(PlanSubmission s, PartTypeRegistry registry, PlaceableBlockPolicy policy, SiteSurvey survey,
                                       Map<String, String> bundledTemplateHashes) {
        List<Issue> issues = new ArrayList<>(s.templates().verifyAgainst(bundledTemplateHashes));
        if (hasError(issues)) {
            return failed(issues);
        }
        PatchResult normalized = new PlanPatcher(registry, s.templates()).normalize(s.plan());
        issues.addAll(normalized.issues());
        if (!normalized.ok()) {
            return failed(issues);
        }
        SemanticPlan plan = normalized.plan();
        if (plan.site() != null && !plan.site().dimension().equals(survey.dimension())) {
            throw new IllegalArgumentException("the survey must be taken in the plan's own dimension");
        }
        ExpandResult expanded = new PlanExpander(registry, SlotResolver.NONE).expand(plan, s.templates(), Router.NONE);
        issues.addAll(expanded.issues());
        if (expanded.plan() == null || hasError(expanded.issues())) {
            return failed(issues);
        }
        CompileResult compiled = new PlanCompiler().compile(expanded.plan(), registry, policy, survey.ref(0L));
        issues.addAll(compiled.issues());
        if (compiled.manifest() == null) {
            return failed(issues);
        }
        TerrainResult terrain = TerrainPrep.apply(compiled.manifest(), survey);
        issues.addAll(terrain.issues());
        if (terrain.manifest() == null) {
            return failed(issues);
        }
        Map<String, String> nodeTypes = new HashMap<>();
        for (PlanNode n : expanded.plan().primitiveNodes()) {
            nodeTypes.put(n.id(), n.type());
        }
        return new CompiledPlan(terrain.manifest(), issues, terrain.summary(), nodeTypes,
                OperatingBox.of(terrain.manifest(), plan));
    }

    private static boolean hasError(List<Issue> issues) {
        return issues.stream().anyMatch(Issue::isError);
    }

    private static CompiledPlan failed(List<Issue> issues) {
        return new CompiledPlan(null, issues, NO_TERRAIN, Map.of(), null);
    }
}
```
(`SurveyRef`はコンパイラの中では記録だけ(ハッシュに入らない)なので、`ref(0L)`で渡す。期限は`SurveyCache`が持つ。`ExpandResult.plan()`が`ERROR`のときに`null`になるかは既存の`PlanExpander`のとおりで、両方を見て止める。)

`SurveyCache.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The surveys the server issued and pinned (04 F-3): kept ten minutes, so approval recompiles on the very same survey. */
public final class SurveyCache {
    /** F-3's default of ten minutes, at 20 ticks per second. */
    public static final long SURVEY_TTL_TICKS = 20L * 60L * 10L;

    private record Pinned(SiteSurvey survey, long expiresTick) {
    }

    private final Map<String, Pinned> byDigest = new HashMap<>();

    public void pin(SiteSurvey survey, long now) {
        byDigest.put(survey.digest(), new Pinned(survey, now + SURVEY_TTL_TICKS));
    }

    public Optional<SiteSurvey> find(String digest, long now) {
        Pinned p = byDigest.get(digest);
        return p == null || now > p.expiresTick() ? Optional.empty() : Optional.of(p.survey());
    }

    public void expire(long now) {
        byDigest.values().removeIf(p -> now > p.expiresTick());
    }

    public int size() {
        return byDigest.size();
    }
}
```

`ApprovalDesk.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pending approvals and the approval check (04 F-3, D-3, D-12, D-27). Only a hash the server computed itself can be
 * approved, by its owner or an operator, in the same dimension, before it expires, on the same registry version and
 * pinned survey, with every blocking issue gone and the terraforming and destructive replacements confirmed.
 */
public final class ApprovalDesk {
    /** F-3's default of ten minutes, at 20 ticks per second. */
    public static final long APPROVAL_TTL_TICKS = 20L * 60L * 10L;
    static final String CLAIM_PREFIX = "claim-";
    static final String SUBJECT_MANIFEST = "manifest";
    static final String DATA_CUT = "cut";
    static final String DATA_FILL = "fill";
    static final String DATA_FLUIDS = "fluids";
    static final String DATA_LEAVES = "leaves";
    static final String DATA_CONTAINERS = "containers";

    private final Map<UUID, Candidate> pending = new HashMap<>();

    public PendingApproval offer(UUID owner, String dimension, CompiledPlan compiled, SafetyReport safety,
                                 PlanSubmission submission, String surveyDigest, long now) {
        if (compiled.manifest() == null) {
            throw new IllegalArgumentException("only a compiled manifest can wait for approval");
        }
        List<Issue> issues = new ArrayList<>(compiled.issues());
        issues.addAll(safety.issues());
        PendingApproval p = new PendingApproval(compiled.manifest().hash(), dimension, owner, now + APPROVAL_TTL_TICKS, issues,
                surveyDigest);
        pending.put(owner, new Candidate(p, compiled, safety, submission));
        return p;
    }

    public Optional<Candidate> pending(UUID owner) {
        return Optional.ofNullable(pending.get(owner));
    }

    public void dropOwner(UUID owner) {
        pending.remove(owner);
    }

    public void dropAll() {
        pending.clear();
    }

    public void expire(long now) {
        pending.values().removeIf(c -> now > c.approval().expiresTick());
    }

    public ApprovalDecision approve(ApprovalRequest req, Approver approver, String registryVersion, SurveyCache surveys, long now,
                                    Supplier<String> newJobId) {
        Candidate cand = pending.get(req.playerUuid());
        if (cand == null) {
            return rejected(ApprovalRejection.NO_PENDING);
        }
        PendingApproval pa = cand.approval();
        if (!approver.uuid().equals(pa.owner()) && !approver.operator()) {
            return rejected(ApprovalRejection.NOT_OWNER);
        }
        if (now > pa.expiresTick()) {
            pending.remove(req.playerUuid());
            return rejected(ApprovalRejection.EXPIRED);
        }
        if (!req.manifestHash().equals(pa.manifestHash())) {
            return rejected(ApprovalRejection.HASH_MISMATCH);
        }
        if (!approver.currentDimension().equals(pa.dimension()) || !req.dimension().equals(pa.dimension())) {
            return rejected(ApprovalRejection.DIMENSION_MISMATCH);
        }
        PlacementManifest m = cand.compiled().manifest();
        if (!registryVersion.equals(m.registryVersion())) {
            return new ApprovalDecision.Rejected(ApprovalRejection.REGISTRY_CHANGED, List.of(Issue.of(IssueCode.E_REGISTRY_VERSION,
                    List.of(SUBJECT_MANIFEST), "部品の登録簿の版が変わりました。もう一度送ってください")));
        }
        if (surveys.find(pa.surveyDigest(), now).isEmpty()) {
            return rejected(ApprovalRejection.SURVEY_EXPIRED);
        }
        Map<String, Issue> byId = new HashMap<>();
        for (Issue i : pa.issues()) {
            byId.put(i.id(), i);
        }
        Set<String> accepted = new HashSet<>();
        for (AcceptedRisk r : req.acceptedRisks()) {
            Issue i = byId.get(r.issueId());
            if (i == null || !i.acceptable()) {
                return rejected(ApprovalRejection.RISK_NOT_ACCEPTABLE);
            }
            accepted.add(i.id());
        }
        List<Issue> blocking = pa.issues().stream().filter(i -> i.isError() && !accepted.contains(i.id())).toList();
        if (!blocking.isEmpty()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.BLOCKING_ISSUES, blocking);
        }
        if (!m.assemblies().isEmpty()) {
            return rejected(ApprovalRejection.ASSEMBLY_NOT_AVAILABLE);
        }
        ReplacementSummary s = cand.safety().replacements();
        if (s.needsTerraformConfirm() && !req.confirmations().terraform()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.TERRAFORM_UNCONFIRMED, List.of(Issue.of(
                    IssueCode.E_TERRAFORM_UNCONFIRMED, "", List.of(SUBJECT_MANIFEST),
                    "地形を変えます(切る" + s.terrainCut() + "個、盛る" + s.terrainFill() + "個)。確かめてから承認してください",
                    Map.of(DATA_CUT, String.valueOf(s.terrainCut()), DATA_FILL, String.valueOf(s.terrainFill())), List.of())));
        }
        if (s.needsDestructiveConfirm() && !req.confirmations().destructive()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED, List.of(Issue.of(
                    IssueCode.E_REPLACE_UNCONFIRMED, "", List.of(SUBJECT_MANIFEST),
                    "水・溶岩" + s.fluids() + "個、木の葉" + s.leaves() + "個、空の入れ物" + s.emptyContainers()
                            + "個を置き換えます。確かめてから承認してください",
                    Map.of(DATA_FLUIDS, String.valueOf(s.fluids()), DATA_LEAVES, String.valueOf(s.leaves()), DATA_CONTAINERS,
                            String.valueOf(s.emptyContainers())), List.of())));
        }
        MaterialPolicy policy = approver.forcedPolicy() != null ? approver.forcedPolicy()
                : approver.creative() ? MaterialPolicy.CREATIVE_FREE : MaterialPolicy.SURVIVAL_CONSUME;
        String jobId = newJobId.get();
        PlanSubmission sub = cand.submission();
        String claimId = sub.kind() == JobKind.BUILD ? CLAIM_PREFIX + jobId : sub.claimId();
        ConstructionJob job = ConstructionJob.create(jobId, approver.uuid(), pa.dimension(), pa.manifestHash(), sub.kind(),
                sub.parentJobId(), m.placements().size(), claimId, policy, now, List.copyOf(accepted.stream().sorted().toList()));
        pending.remove(req.playerUuid());
        return new ApprovalDecision.Approved(job, cand);
    }

    private static ApprovalDecision rejected(ApprovalRejection reason) {
        return new ApprovalDecision.Rejected(reason, List.of());
    }
}
```
(`MODIFY`の`total`は、ここでは新しい施工リストの数で仮に置き、Task 29で`JobService`が手順(`JobProgram`)の長さに直す(`withTotal`)。)

`WorkResult.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

/** How a worker job ended, delivered on the main thread. */
public sealed interface WorkResult<T> {
    record Done<T>(T value) implements WorkResult<T> {
    }

    record Failed<T>(Throwable error) implements WorkResult<T> {
    }

    record Cancelled<T>() implements WorkResult<T> {
    }
}
```
`ServerWorkerPool.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The bounded, cancellable pool for the heavy pure work (04 F-1): expand, compile, terraform, safety check. One job per
 * player at a time: the player's slot is held until the work has really left its thread, even after a cancel, so a
 * cancelled compile can never run side by side with the next one. Cancelling sets a token the work may poll and
 * interrupts its thread; a job still in the queue is removed and reported cancelled at once. The queue is bounded;
 * results are handed back on the main thread, never touching the world here.
 */
public final class ServerWorkerPool implements AutoCloseable {
    public static final int DEFAULT_THREADS = 2;
    public static final int DEFAULT_MAX_QUEUED = 8;
    private static final String THREAD_NAME = "MicraDrone-BuildWorker-";

    /** A cancellation the work can poll between its steps. */
    public static final class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public boolean cancelled() {
            return cancelled.get();
        }
    }

    /** Work that may look at its token. */
    @FunctionalInterface
    public interface Work<T> {
        T run(CancelToken token) throws Exception;
    }

    private final class Job<T> implements Runnable {
        final UUID owner;
        final Work<T> work;
        final Consumer<WorkResult<T>> onMain;
        final CancelToken token = new CancelToken();
        volatile Thread runner;

        Job(UUID owner, Work<T> work, Consumer<WorkResult<T>> onMain) {
            this.owner = owner;
            this.work = work;
            this.onMain = onMain;
        }

        @Override
        public void run() {
            WorkResult<T> result;
            runner = Thread.currentThread();
            try {
                if (token.cancelled()) {
                    result = new WorkResult.Cancelled<>();
                } else {
                    T value = work.run(token);
                    result = token.cancelled() ? new WorkResult.Cancelled<>() : new WorkResult.Done<>(value);
                }
            } catch (Throwable t) {
                result = token.cancelled() ? new WorkResult.Cancelled<>() : new WorkResult.Failed<>(t);
            } finally {
                runner = null;
                Thread.interrupted();
            }
            finish(this, result);
        }
    }

    private final ThreadPoolExecutor pool;
    private final Executor mainThread;
    private final Map<UUID, Job<?>> active = new ConcurrentHashMap<>();
    private final AtomicInteger threads = new AtomicInteger();

    public ServerWorkerPool(int threadCount, int maxQueued, Executor mainThread) {
        this.mainThread = mainThread;
        this.pool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued), r -> {
                    Thread t = new Thread(r, THREAD_NAME + threads.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> boolean submit(UUID owner, Callable<T> work, Consumer<WorkResult<T>> onMain) {
        return submit(owner, (Work<T>) token -> work.call(), onMain);
    }

    public synchronized <T> boolean submit(UUID owner, Work<T> work, Consumer<WorkResult<T>> onMain) {
        if (active.containsKey(owner)) {
            return false;
        }
        Job<T> job = new Job<>(owner, work, onMain);
        active.put(owner, job);
        try {
            pool.execute(job);
        } catch (RejectedExecutionException full) {
            active.remove(owner, job);
            return false;
        }
        return true;
    }

    /** True while the player's work has not yet left its thread (a cancelled one included). */
    public boolean busy(UUID owner) {
        return active.containsKey(owner);
    }

    public synchronized boolean cancel(UUID owner) {
        Job<?> job = active.get(owner);
        if (job == null || job.token.cancelled.getAndSet(true)) {
            return false;
        }
        if (pool.remove(job)) {
            finish(job, new WorkResult.Cancelled<>());
            return true;
        }
        Thread t = job.runner;
        if (t != null) {
            t.interrupt();
        }
        return true;
    }

    private <T> void finish(Job<T> job, WorkResult<?> result) {
        @SuppressWarnings("unchecked")
        WorkResult<T> r = (WorkResult<T>) result;
        mainThread.execute(() -> {
            active.remove(job.owner, job);
            job.onMain.accept(r);
        });
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`PlanCompilationTest.theServerRebuildsTheGoldenHutByItself`で、サーバーの作り直しが金のファイルのハッシュになることを含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/main/java/io/github/khayashi4337/micradrone/build/model/IssueCode.java
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/build/compile/TestManifests.java
git add docs/design/nl_factory_builder/05_parts_and_analyzers.md
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: サーバー自身の作り直し(PlanCompilation)・調査の固定・承認の検査(ApprovalDesk)・重い計算のワーカーを追加(自然言語→工場建設 P4 Task 12)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 13: ジョブの進行(`JobService`)— 区画の予約、所有者の在不在、予算、設置、L7の検査と修復ラウンド、取消・再開

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

世界を変える経路の本体(D-1)。純Javaで、`FakeWorld`の上で小屋が`VERIFIED`まで進むことを確かめる。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobWorld,TickInput,JobUpdate,JobStatus,ControlResult,JobRecord,RepairQueue,JobService}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{FakeJobWorld,JobServiceTest,JobServiceL7Test}.java`

**Interfaces:**
- Consumes: Task 3〜12の型、`build.verify`の`SnapshotDiff`・`RepairPlanner`・`SnapshotCollector`・`VolatileProps`・`CompareScope`・`Deviation`
- Produces:
  - `interface JobWorld { WorldPort world(String dimension); MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId); boolean ownerOnline(UUID owner); boolean mayRunWithoutOwner(ConstructionJob job); }`(最後の1つ: 所有者がいない間もこのジョブを進めてよいか。アダプタが、設定だけでなく「その区画のチャンクを保持している」「置く主体(`FakePlayer`)を作れる」を確かめて答える。`JobService`は`continueWhileOffline`(設定)**かつ**これが真のときだけ、所有者の不在で止めない。Codexのレビュー2026-09-30: 設定だけで進めると、チャンクが外れた現場や主体の無い状態で進もうとする)
  - `record TickInput(long tick, double averageMspt)`
  - `record JobUpdate(ConstructionJob job, List<IntPos> touched, List<ItemCount> shortage, List<Conflict> newConflicts, boolean stateChanged)`
  - `record JobStatus(String jobId, UUID owner, JobKind kind, JobState state, PauseReason shownPause, int cursor, int total, int repairRound, int conflicts, int skipped, String lastError, String claimId, String dimension)`(`shownPause`は表示用: `PAUSED`ならその理由、待機列で待っている`QUEUED`と、自動減速中の`RUNNING`は`SERVER_BUSY`。F-2(d))
  - `enum ControlResult {OK, NOT_FOUND, NOT_ALLOWED, WRONG_STATE}`
  - `final class JobRecord`(1ジョブの実行時の状態。保存・復元で使う): `JobRecord(ConstructionJob, PlacementManifest, Map<String,String> nodeTypes, JobProgram, Journal, JobOutcome, Box operatingBox)`、`job()`・`manifest()`・`nodeTypes()`・`program()`・`journal()`・`outcome()`・`operatingBox()`・`repair()`・`remaining()`・`skipSiteChanges()`・`lastDroneTick()`
  - `record RepairQueue(JobProgram program, int cursor)`
  - `final class JobService`: `RETRY_INTERVAL_TICKS = 20`(材料不足・未読み込みで止まったジョブが、次に見直すまでの間隔=1秒。設計の「補給されれば自動で再開」の実装の間隔。Task 35で設計に書く)、`FAST_PHASES = {SITE_PREP, STRUCTURE, ENVELOPE}`(F-2)、`JobService(BudgetConfig, ClaimBook, PartTypeRegistry, boolean fastStructure, boolean continueWhileOffline)`・`JobService(..., WriteAheadLog wal)`(ログはサーバーで1つ。5引数の形はテスト用にメモリのログを使う)、`static int samePaceSteps(JobProgram, int cursor, int max, boolean fastStructure)`(後の2つは設定。F-2の「設定で高速施工にできる」、F-13の「(設定で)チャンクを保持」)、`void admitApproved(ConstructionJob job, PlacementManifest manifest, Map<String,String> nodeTypes, JobProgram program, Box operatingBox)`・`void admitApproved(..., Box operatingBox, List<Conflict> knownConflicts)`(初めから触らない位置。`JobOutcome.addRestoreConflict`で消えない`Conflict`として記録する。Task 29の`MODIFY`が使う。5引数の形は空で呼ぶ)、`void add(JobRecord)`(復元)、`List<JobUpdate> tick(TickInput, JobWorld)`、`boolean checkpointWanted()`・`void markDurable()`(**ジョブは、その実行を覆う確定点の後にしか終われない**: 検査を読み終えても、`wal.durableUpTo()`がジョブの最後の実行(`JobRecord.lastRun`)に届くまでは`VERIFYING`のまま待ち、`checkpointWanted()`が真になる。ランタイムがフラッシュつきの保存をして`markDurable()`を呼ぶと、次のtickで終わる(Codexの3回目のレビュー2026-09-30: 終わったジョブが、世界の失った書き込みを抱えたまま`VERIFIED`にならないため。ロールバックの区画の解放も確定点の後)。テストの`JobServiceTest.Clock.step`はランタイムの代わりに、欲しがられたらすぐ`markDurable()`する)、`ControlResult cancel(String jobId, UUID requester, boolean op)`、`ControlResult resume(String jobId, UUID requester, boolean op, boolean skipSiteChanges)`、`Optional<JobStatus> status(String)`、`List<JobStatus> statuses()`、`Optional<JobRecord> record(String)`、`List<JobRecord> records()`、`List<JobRecord> jobsInClaim(String claimId)`、`PlacedRegistry registry(String claimId)`、`Map<String,PlacedRegistry> registries()`、`LedgerBook ledgers()`、`ClaimBook claims()`、`boolean slowed()`

- 1tickの順序(**これが正本**): (1)`PENDING_APPROVAL`を到着順に区画の検査→`BUILD`は区画を予約して`QUEUED`、重なれば`CANCELLED`(`lastError`に`E-CLAIM-OVERLAP:...`)。`MODIFY`・`REPAIR`・`ROLLBACK`は、まず`ClaimBook.checkOwned`(区画が生きていて、同じディメンションで、ジョブの所有者の物)、通らなければ`CANCELLED`(`E-CLAIM-INVALID:...`)。`MODIFY`は通れば`ClaimBook.grow`で区画を新しい範囲まで広げる。(2)所有者の在不在: 不在なら`QUEUED`/`RUNNING`/`VERIFYING`/`REPAIRING`を`PAUSED(OWNER_OFFLINE)`(`continueWhileOffline`なら止めない)、戻れば再開。`CHUNK_UNLOADED`・`MATERIALS_MISSING`・`NO_ROOM`(Task 9の差分)は`RETRY_INTERVAL_TICKS`ごとに再開して見直す。(3)`ConstructionBudget.admit`で`QUEUED`を`RUNNING`へ。(4)`allocate`の割り当てで、`RUNNING`は手順を、`REPAIRING`は修復の手順を実行。**割り当ては、カーソルの位置の工程の速さ(整地・構造・外装は速い、それ以外はドローンの間隔)で決まるので、1回の実行は速さが変わる所で止める**(`samePaceSteps`。余った分は次のtickに割り当て直す。速い割り当てのまま、ドローンの間隔で置くはずの装飾まで一気に置かないため。Codexのレビュー2026-09-30)(**修復の手順(`JobRecord.repair`)は一時停止をまたいで残る**。`REPAIRING`中に止まったジョブは`PAUSED→QUEUED→RUNNING`で戻り、`RUNNING`でも修復の手順の続きを実行し、終われば`PLACED_ALL`で`VERIFYING`へ行く。ラウンドは増やさない。P4レビューB-5)。手順が`SITE_CHANGED`で止まったら、`lastError`に`Issue.of(E_SITE_CHANGED, [ノードID], ...)`のIDを入れる(F-3の「`E-SITE-CHANGED`で`PAUSED`」)。手順が終われば`VERIFYING`へ(読み取りの`SnapshotCollector`を新しく作る)。(5)`VERIFYING`は1tickに`SnapshotCollector.DEFAULT_READS_PER_TICK`個まで読み、未読み込みがあれば`PAUSED(CHUNK_UNLOADED)`(再開後は読み直し)。窓ごとに`SnapshotDiff`(撤去の位置は「戻っているか」を別に確かめる)。読み終われば`RepairPlanner`で分けて、直す物が無く、**触らずに残した`Conflict`も無ければ**`VERIFIED`(`lastError`を空に戻す: 解決した`E-SITE-CHANGED`などを残さない)、直せる物があり上限(3)未満なら`REPAIRING`(ラウンド+1。ただし、修復のラウンドの途中で落ちて復旧したジョブ(`JobRecord.recoveredRound`。Task 24)は、**同じラウンドのまま**: 失われた修復の書き込みを同じ台帳のキーで置き直すので、払い済みを二度取らない)、それ以外は`PARTIAL`(残る`Deviation`と理由を`lastError`・`remaining`に。`Conflict`の数は`conflict=N`)。**設計の`VERIFIED`は「ずれが1つも無い」なので、プレイヤーの物として残した位置があるジョブは`PARTIAL`**(Codexのレビュー2026-09-30。前の版は`VERIFIED`にしていた)。`ROLLBACK`が`PARTIAL`なら、区画と`PlacedRegistry`は残る(撤去しきれていない位置がある)。`ROLLBACK`が`VERIFIED`になったら、同じ区画の終わったジョブを`ROLLED_BACK`にし、区画を解放して`PlacedRegistry`を捨てる(D-23)。

- [ ] **Step 1: 失敗するテストを書く**

`FakeJobWorld.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One fake world, fake inventories, and who is online, for JobService's tests. */
public final class FakeJobWorld implements JobWorld {
    public final FakeWorld world = new FakeWorld();
    public final Map<UUID, FakeMaterials> inventories = new HashMap<>();
    public final Set<UUID> online = new HashSet<>();
    /** Jobs the adapter would let run while their owner is away (chunks held, an actor available). */
    public final Set<String> mayRunOffline = new HashSet<>();

    @Override
    public WorldPort world(String dimension) {
        return world;
    }

    @Override
    public MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId) {
        return policy == MaterialPolicy.CREATIVE_FREE ? MaterialPort.FREE
                : inventories.computeIfAbsent(owner, k -> new FakeMaterials());
    }

    @Override
    public boolean ownerOnline(UUID owner) {
        return online.contains(owner);
    }

    @Override
    public boolean mayRunWithoutOwner(ConstructionJob job) {
        return mayRunOffline.contains(job.jobId());
    }
}
```

`JobServiceTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class JobServiceTest {
    static final UUID A = new UUID(0, 1);
    static final UUID B = new UUID(0, 2);
    static final double CALM = 20.0;
    static final int MAX_TICKS = 2_000;

    /** Hands out consecutive ticks, like the server does. */
    static final class Clock {
        long tick;

        /** One server tick; like the runtime, a checkpoint follows when a job waits for one. */
        List<JobUpdate> step(JobService s, FakeJobWorld w) {
            List<JobUpdate> out = s.tick(new TickInput(tick++, CALM), w);
            if (s.checkpointWanted()) {
                s.markDurable();
            }
            return out;
        }

        List<JobUpdate> stepAt(JobService s, FakeJobWorld w, double averageMspt) {
            List<JobUpdate> out = s.tick(new TickInput(tick++, averageMspt), w);
            if (s.checkpointWanted()) {
                s.markDurable();
            }
            return out;
        }

        List<JobUpdate> runUntil(JobService s, FakeJobWorld w, String jobId, Predicate<ConstructionJob> stop) {
            List<JobUpdate> all = new ArrayList<>();
            for (int i = 0; i < MAX_TICKS; i++) {
                all.addAll(step(s, w));
                if (stop.test(s.record(jobId).orElseThrow().job())) {
                    return all;
                }
            }
            throw new AssertionError("job " + jobId + " did not get there: " + s.status(jobId));
        }
    }

    static JobService service() {
        return new JobService(BudgetConfig.defaults(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER),
                BuildingParts.registry(), true, false);
    }

    static PlacementManifest shifted(PlacementManifest m, int dx) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : m.placements()) {
            out.add(new Placement(p.index(), p.pos().plus(dx, 0, 0), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(),
                    p.placer(), p.verify(), p.replaces(), p.assemblyGroup()));
        }
        Box b = m.worldBounds();
        return TestManifests.of(new Box(b.minA() + dx, b.minB(), b.minC(), b.maxA() + dx, b.maxB(), b.maxC()), out);
    }

    static ConstructionJob approved(String id, UUID owner, PlacementManifest m, MaterialPolicy policy) {
        return ConstructionJob.create(id, owner, TestManifests.DIM, m.hash(), JobKind.BUILD, null, m.placements().size(),
                "claim-" + id, policy, 0L, List.of());
    }

    static void submit(JobService s, ConstructionJob j, PlacementManifest m) {
        s.admitApproved(j, m, Map.of(), JobProgram.build(m), m.worldBounds());
    }

    static Predicate<ConstructionJob> in(JobState state) {
        return j -> j.state() == state;
    }

    @Test
    void aHutIsBuiltAndVerifiedEndToEnd() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        List<JobUpdate> ups = c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        for (Placement p : m.placements()) {
            assertEquals(p.block(), w.world.blockAt(p.pos()));
        }
        assertEquals(25, ups.stream().mapToInt(u -> u.touched().size()).sum(), "every placement is shown once");
        assertEquals("claim-job-1", s.claims().active().get(0).claimId(), "the claim outlives the job (D-23)");
        assertEquals(25, s.registry("claim-job-1").size());
        assertEquals(0, s.status("job-1").orElseThrow().repairRound());
    }

    @Test
    void overlappingApprovalsInOneTickAdmitOnlyTheFirst() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.online.add(B);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-a", A, m, MaterialPolicy.CREATIVE_FREE), m);
        submit(s, approved("job-b", B, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(JobState.RUNNING, s.record("job-a").orElseThrow().job().state());
        ConstructionJob b = s.record("job-b").orElseThrow().job();
        assertEquals(JobState.CANCELLED, b.state());
        assertTrue(b.lastError().startsWith("E-CLAIM-OVERLAP:"), b.lastError());
    }

    @Test
    void theJobPausesWhileTheOwnerIsAwayAndResumesOnReturn() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        w.online.remove(A);
        c.step(s, w);
        ConstructionJob paused = s.record("job-1").orElseThrow().job();
        assertEquals(PauseReason.OWNER_OFFLINE, paused.pauseReason());
        int cursor = paused.cursor();
        c.step(s, w);
        assertEquals(cursor, s.record("job-1").orElseThrow().job().cursor(), "nothing is placed while away");
        w.online.add(A);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
    }

    @Test
    void onlyTheOwnerOrAnOperatorMayCancelAndCancelKeepsWhatWasBuilt() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(ControlResult.NOT_ALLOWED, s.cancel("job-1", B, false));
        assertEquals(ControlResult.NOT_FOUND, s.cancel("job-9", A, false));
        assertEquals(ControlResult.OK, s.cancel("job-1", B, true));
        assertEquals(JobState.CANCELLED, s.record("job-1").orElseThrow().job().state());
        assertEquals(ControlResult.WRONG_STATE, s.cancel("job-1", A, false));
        assertEquals(m.placements().get(0).block(), w.world.blockAt(m.placements().get(0).pos()), "cancel removes nothing");
        assertTrue(s.claims().find("claim-job-1").isPresent());
    }

    @Test
    void missingMaterialsWaitAndRetryEverySecond() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        List<JobUpdate> ups = c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.MATERIALS_MISSING, s.record("job-1").orElseThrow().job().pauseReason());
        assertTrue(ups.stream().anyMatch(u -> !u.shortage().isEmpty()), "the owner is told what is missing");
        long pausedAt = c.tick - 1;
        w.inventories.get(A).with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        while (c.tick < pausedAt + JobService.RETRY_INTERVAL_TICKS) {
            c.step(s, w);
            assertEquals(JobState.PAUSED, s.record("job-1").orElseThrow().job().state(), "no retry before one second");
        }
        c.step(s, w);
        assertEquals(JobState.RUNNING, s.record("job-1").orElseThrow().job().state(), "retried exactly one second later");
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(0, w.inventories.get(A).count("minecraft:oak_planks"));
    }

    @Test
    void aFifthJobWaitsAndIsShownAsServerBusy() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        PlacementManifest base = TestManifests.smallHut();
        for (int i = 0; i < 5; i++) {
            UUID owner = new UUID(1, i);
            w.online.add(owner);
            PlacementManifest m = shifted(base, 100 * i);
            submit(s, approved("job-" + i, owner, m, MaterialPolicy.CREATIVE_FREE), m);
        }
        c.step(s, w);
        long running = s.statuses().stream().filter(st -> st.state() == JobState.RUNNING).count();
        assertEquals(4, running);
        JobStatus waiting = s.status("job-4").orElseThrow();
        assertEquals(JobState.QUEUED, waiting.state());
        assertEquals(PauseReason.SERVER_BUSY, waiting.shownPause());
    }

    @Test
    void aSiteChangeStopsTheJobUntilTheOwnerChoosesToSkip() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos blocked = m.placements().get(3).pos();
        w.world.setBlock(blocked, BlockSpec.of("minecraft:stone_bricks"));
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.SITE_CHANGED, s.record("job-1").orElseThrow().job().pauseReason());
        String error = s.record("job-1").orElseThrow().job().lastError();
        assertTrue(error.startsWith(IssueCode.E_SITE_CHANGED.label() + ":"), error);
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 2; i++) {
            c.step(s, w);
        }
        assertEquals(JobState.PAUSED, s.record("job-1").orElseThrow().job().state(), "SITE_CHANGED waits for the owner");
        assertEquals(ControlResult.OK, s.resume("job-1", A, false, true));
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        assertEquals("minecraft:stone_bricks", w.world.blockAt(blocked).blockId());
        assertEquals(1, s.status("job-1").orElseThrow().conflicts());
        assertTrue(s.status("job-1").orElseThrow().lastError().contains("conflict=1"),
                "a position left to its player is a deviation: PARTIAL with the reason, not VERIFIED");
    }

    @Test
    void afterTheObstacleIsRemovedAResumeWithoutSkipBuildsThereAndDropsTheConflict() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos blocked = m.placements().get(3).pos();
        w.world.setBlock(blocked, BlockSpec.of("minecraft:stone_bricks"));
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        w.world.setBlock(blocked, BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertEquals(ControlResult.OK, s.resume("job-1", A, false, false));
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(m.placements().get(3).block(), w.world.blockAt(blocked));
        assertEquals(0, s.status("job-1").orElseThrow().conflicts());
        assertEquals(0, s.status("job-1").orElseThrow().skipped());
        assertEquals("", s.status("job-1").orElseThrow().lastError(), "a resolved E-SITE-CHANGED is not shown any more");
    }

    @Test
    void aSlowedRunningJobIsShownAsServerBusy() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(null, s.status("job-1").orElseThrow().shownPause());
        c.stepAt(s, w, 50.0);
        JobStatus st = s.status("job-1").orElseThrow();
        assertEquals(JobState.RUNNING, st.state());
        assertTrue(s.slowed());
        assertEquals(PauseReason.SERVER_BUSY, st.shownPause());
    }

    @Test
    void anAllowanceStopsWhereThePaceChanges() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        List<Placement> ps = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            ps.add(TestManifests.put(new IntPos(x, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE,
                    io.github.khayashi4337.micradrone.build.parts.VerifyMode.EXACT));
        }
        for (int x = 0; x < 3; x++) {
            ps.add(TestManifests.put(new IntPos(x, 65, 0), BlockSpec.of("minecraft:lantern"), "l", BuildPhase.DECORATION,
                    io.github.khayashi4337.micradrone.build.parts.VerifyMode.EXACT));
        }
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 3, 70, 1), ps);
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(3, s.record("job-1").orElseThrow().job().cursor(),
                "the fast structure allowance does not carry the drone-paced decoration along in the same tick");
        assertEquals(3, JobService.samePaceSteps(JobProgram.build(m), 0, 16, true));
        assertEquals(16, JobService.samePaceSteps(JobProgram.build(m), 0, 16, false), "without fast structure, one pace");
    }

    @Test
    void aJobOnAClaimMustFindItLiveHereAndItsOwners() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.online.add(B);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        ConstructionJob foreign = ConstructionJob.create("job-2", B, TestManifests.DIM, m.hash(), JobKind.ROLLBACK, "job-1", 0,
                "claim-job-1", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(foreign, m, Map.of(), new JobProgram(List.of(), List.of()), m.worldBounds());
        ConstructionJob missing = ConstructionJob.create("job-3", A, TestManifests.DIM, m.hash(), JobKind.MODIFY, "job-1", 0,
                "claim-nope", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(missing, m, Map.of(), new JobProgram(List.of(), List.of()), m.worldBounds());
        c.step(s, w);
        for (String id : List.of("job-2", "job-3")) {
            ConstructionJob j = s.record(id).orElseThrow().job();
            assertEquals(JobState.CANCELLED, j.state(), id);
            assertTrue(j.lastError().startsWith(IssueCode.E_CLAIM_INVALID.label() + ":"), j.lastError());
        }
    }

    @Test
    void aModifyReachingFurtherGrowsItsClaim() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        PlacementManifest wider = shifted(m, 5);
        Box both = ClaimBook.union(m.worldBounds(), wider.worldBounds());
        ConstructionJob modify = ConstructionJob.create("job-2", A, TestManifests.DIM, wider.hash(), JobKind.MODIFY, "job-1",
                0, "claim-job-1", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(modify, wider, Map.of(), new JobProgram(List.of(), List.of()), both);
        c.step(s, w);
        assertEquals(both, s.claims().find("claim-job-1").orElseThrow().operatingBox(), "the claim now covers the new part");
    }

    @Test
    void aJobEndsOnlyAfterADurablePointCoversItsRuns() {
        JobService s = service();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        long tick = 0;
        for (int i = 0; i < MAX_TICKS && !s.checkpointWanted(); i++) {
            s.tick(new TickInput(tick++, CALM), w);
        }
        assertTrue(s.checkpointWanted(), "the build is placed and checked, and waits for the checkpoint");
        for (int i = 0; i < 20; i++) {
            s.tick(new TickInput(tick++, CALM), w);
        }
        assertEquals(JobState.VERIFYING, s.status("job-1").orElseThrow().state(), "not VERIFIED while its writes may be lost");
        s.markDurable();
        s.tick(new TickInput(tick, CALM), w);
        assertEquals(JobState.VERIFIED, s.status("job-1").orElseThrow().state());
    }

    @Test
    void aHandOverThatDoesNotFitWaitsAndGoesOnOnceThereIsRoom() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        IntPos pos = new IntPos(0, 63, 0);
        Placement cut = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found", BuildPhase.SITE_PREP,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(cut));
        w.world.setBlock(pos, BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        w.inventories.put(A, mats);
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.NO_ROOM));
        mats.room = FakeMaterials.UNLIMITED;
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(1, mats.count("minecraft:dirt"), "the cut ground handed over once there was room");
    }
}
```

`JobServiceL7Test.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.A;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.approved;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.in;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.service;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.submit;

class JobServiceL7Test {
    @Test
    void theL7RoundRepairsBrokenBlocksAndReportsReplacedOnes() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos broken = m.placements().get(0).pos();
        IntPos swapped = m.placements().get(20).pos();
        boolean[] once = {false, false};
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(broken) && !once[0]) {
                once[0] = true;
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
            if (pos.equals(swapped) && !once[1]) {
                once[1] = true;
                w.world.setBlock(pos, BlockSpec.of("minecraft:gold_block"));
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobStatus st = s.status("job-1").orElseThrow();
        assertTrue(st.lastError().contains("conflict=1"), "the gold block is left to its player, so the job is PARTIAL");
        assertEquals(1, st.repairRound(), "one L7 round re-placed the broken block");
        assertEquals(m.placements().get(0).block(), w.world.blockAt(broken));
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId(), "a swapped block is never overwritten");
        assertEquals(1, st.conflicts());
    }

    @Test
    void aPauseDuringRepairKeepsTheRepairQueueAndTheRound() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos broken = m.placements().get(0).pos();
        boolean[] once = {false};
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(broken) && !once[0]) {
                once[0] = true;
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.REPAIRING));
        w.online.remove(A);
        c.step(s, w);
        assertEquals(PauseReason.OWNER_OFFLINE, s.record("job-1").orElseThrow().job().pauseReason());
        assertEquals(1, s.record("job-1").orElseThrow().repair().program().size(), "the repair queue survives the pause");
        w.online.add(A);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(1, s.status("job-1").orElseThrow().repairRound(), "resuming does not spend another round");
        assertEquals(m.placements().get(0).block(), w.world.blockAt(broken));
    }

    @Test
    void aProtectedPositionEndsInPartialWithTheReason() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        w.world.deny(m.placements().get(5).pos());
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobRecord r = s.record("job-1").orElseThrow();
        assertEquals(1, r.remaining().size());
        assertTrue(r.job().lastError().contains("blocked=1"), r.job().lastError());
        assertEquals(0, r.job().repairRound(), "nothing to retry: protection is not L7's to fix");
    }

    @Test
    void aBlockThatKeepsBreakingGivesUpAfterThreeRounds() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos flaky = m.placements().get(0).pos();
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(flaky) && !block.isAir()) {
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobRecord r = s.record("job-1").orElseThrow();
        assertEquals(ConstructionJob.MAX_REPAIR_ROUNDS, r.job().repairRound());
        assertTrue(r.job().lastError().contains("missing=1"), r.job().lastError());
    }

    @Test
    void anUnloadedChunkDuringVerificationPausesAndIsReadAgain() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos last = m.placements().get(24).pos();
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(last)) {
                w.world.unload(pos);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.CHUNK_UNLOADED, s.record("job-1").orElseThrow().job().pauseReason());
        w.world.afterWrite = (pos, block) -> {
        };
        w.world.load(last);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.JobService*" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`JobWorld.java`・`TickInput.java`・`JobUpdate.java`(リストは`List.copyOf`)・`JobStatus.java`・`ControlResult.java`・`RepairQueue.java`: 上の形のとおり。

`JobRecord.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.verify.Deviation;
import io.github.khayashi4337.micradrone.build.verify.SnapshotCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One job's runtime state. The job record itself (small) is saved as job.bin; the manifest, journal, ledger, outcome
 * and program are its separate files (04 F-2). The collector and repair queue are rebuilt after a restart.
 */
public final class JobRecord {
    private ConstructionJob job;
    private final PlacementManifest manifest;
    private final Map<String, String> nodeTypes;
    private final JobProgram program;
    private final Journal journal;
    /** The newest write-ahead-log run this job made (its end waits for a durable point covering it). */
    long lastRun;
    boolean awaitingDurable;
    /** Recovered while a repair round was under way: the next repair plan stays in that round. */
    boolean recoveredRound;
    private Journal beforeJournal;
    private final JobOutcome outcome;
    private final Box operatingBox;
    boolean skipSiteChanges;
    RepairQueue repair;
    SnapshotCollector collector;
    final List<Deviation> placementDeviations = new ArrayList<>();
    final List<Integer> restoreFailures = new ArrayList<>();
    final List<Deviation> remaining = new ArrayList<>();
    /** Far in the past, so a fresh job's drones may place on its first tick. */
    long lastDroneTick = Long.MIN_VALUE / 2;
    long admittedOrder;
    long pausedAtTick;

    public JobRecord(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                     Journal journal, JobOutcome outcome, Box operatingBox) {
        this.job = Objects.requireNonNull(job, "job");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.nodeTypes = Map.copyOf(nodeTypes);
        this.program = Objects.requireNonNull(program, "program");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.operatingBox = Objects.requireNonNull(operatingBox, "operatingBox");
    }

    public ConstructionJob job() {
        return job;
    }

    void setJob(ConstructionJob next) {
        job = Objects.requireNonNull(next, "job");
    }

    public PlacementManifest manifest() {
        return manifest;
    }

    public Map<String, String> nodeTypes() {
        return nodeTypes;
    }

    public JobProgram program() {
        return program;
    }

    public Journal journal() {
        return journal;
    }

    /** Where the pre-build blocks are looked up: the job's own journal, or its parent's for a REPAIR job (Task 21). */
    public Journal beforeJournal() {
        return beforeJournal != null ? beforeJournal : journal;
    }

    void useBeforesFrom(Journal parent) {
        beforeJournal = parent;
    }

    public JobOutcome outcome() {
        return outcome;
    }

    public Box operatingBox() {
        return operatingBox;
    }

    public RepairQueue repair() {
        return repair;
    }

    public List<Deviation> remaining() {
        return List.copyOf(remaining);
    }

    public boolean skipSiteChanges() {
        return skipSiteChanges;
    }

    public long lastDroneTick() {
        return lastDroneTick;
    }
}
```

`JobService.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.verify.CompareScope;
import io.github.khayashi4337.micradrone.build.verify.Deviation;
import io.github.khayashi4337.micradrone.build.verify.DeviationKind;
import io.github.khayashi4337.micradrone.build.verify.RepairPlan;
import io.github.khayashi4337.micradrone.build.verify.RepairPlanner;
import io.github.khayashi4337.micradrone.build.verify.SnapshotCollector;
import io.github.khayashi4337.micradrone.build.verify.SnapshotDiff;
import io.github.khayashi4337.micradrone.build.verify.VolatileProps;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Drives every job one server tick at a time: claims for newly approved jobs (one after another, so overlapping
 * approvals cannot both win), the owner's presence, the budget's admission and allowances (04 F-2), placement, L7's
 * verification in bounded reads and its repair rounds (03 L7), and the owner's controls (D-12). This is the only path
 * that changes the world (D-1).
 */
public final class JobService {
    public static final int RETRY_INTERVAL_TICKS = 20;
    public static final Set<BuildPhase> FAST_PHASES = EnumSet.of(BuildPhase.SITE_PREP, BuildPhase.STRUCTURE, BuildPhase.ENVELOPE);
    private static final Set<JobState> ACTIVE = EnumSet.of(JobState.RUNNING, JobState.VERIFYING, JobState.REPAIRING);
    private static final Set<JobState> PLACING = EnumSet.of(JobState.RUNNING, JobState.REPAIRING);
    private static final Set<JobState> AWAY_PAUSES = EnumSet.of(JobState.QUEUED, JobState.RUNNING, JobState.VERIFYING,
            JobState.REPAIRING);
    private static final Set<PauseReason> RETRIED = EnumSet.of(PauseReason.CHUNK_UNLOADED, PauseReason.MATERIALS_MISSING,
            PauseReason.NO_ROOM);
    private static final Set<JobState> ROLLBACKABLE = EnumSet.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);

    private final BudgetConfig budgetConfig;
    private final ConstructionBudget budget;
    private final ClaimBook claims;
    private final PartTypeRegistry registry;
    private final boolean fastStructure;
    private final boolean continueWhileOffline;
    private final LedgerBook ledgers = new LedgerBook();
    private final WriteAheadLog wal;
    private final Map<String, PlacedRegistry> registries = new HashMap<>();
    private final LinkedHashMap<String, JobRecord> jobs = new LinkedHashMap<>();
    private long admissions;
    private boolean slowed;

    public JobService(BudgetConfig budgetConfig, ClaimBook claims, PartTypeRegistry registry, boolean fastStructure,
                      boolean continueWhileOffline) {
        this(budgetConfig, claims, registry, fastStructure, continueWhileOffline, WriteAheadLog.inMemory());
    }

    public JobService(BudgetConfig budgetConfig, ClaimBook claims, PartTypeRegistry registry, boolean fastStructure,
                      boolean continueWhileOffline, WriteAheadLog wal) {
        this.wal = wal;
        this.budgetConfig = budgetConfig;
        this.budget = new ConstructionBudget(budgetConfig);
        this.claims = claims;
        this.registry = registry;
        this.fastStructure = fastStructure;
        this.continueWhileOffline = continueWhileOffline;
    }

    public void admitApproved(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                              Box operatingBox) {
        admitApproved(job, manifest, nodeTypes, program, operatingBox, List.of());
    }

    /** {@code knownConflicts}: positions left alone from the start (a MODIFY's changes the project never placed, Task 29). */
    public void admitApproved(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                              Box operatingBox, List<Conflict> knownConflicts) {
        if (job.state() != JobState.PENDING_APPROVAL) {
            throw new IllegalArgumentException("only a freshly approved job can be admitted: " + job.state());
        }
        // every repair round journals its own placements (keyed by ledger key), so room for all rounds
        Journal journal = new Journal(Math.max(Journal.MAX_ENTRIES, program.size()) * (1 + ConstructionJob.MAX_REPAIR_ROUNDS));
        JobOutcome outcome = new JobOutcome();
        for (Conflict c : knownConflicts) {
            // kept for good: never resolved by a later placement, counted by status, and it makes the job PARTIAL
            outcome.addRestoreConflict(c);
        }
        add(new JobRecord(job.withTotal(program.size()), manifest, nodeTypes, program, journal, outcome, operatingBox));
    }

    public void add(JobRecord r) {
        if (jobs.putIfAbsent(r.job().jobId(), r) != null) {
            throw new IllegalStateException("job " + r.job().jobId() + " exists already");
        }
    }

    public List<JobUpdate> tick(TickInput in, JobWorld w) {
        List<JobUpdate> updates = new ArrayList<>();
        admitClaims(in, updates);
        presence(in, w, updates);
        start(updates);
        List<JobRecord> placing = inState(PLACING);
        BudgetTick bt = budget.allocate(in.tick(), in.averageMspt(), placing.stream().map(this::budgetJob).toList());
        slowed = bt.slowed();
        Map<String, Integer> allowance = new HashMap<>();
        for (Allowance a : bt.allowances()) {
            allowance.merge(a.jobId(), a.placements(), Integer::sum);
        }
        for (JobRecord r : placing) {
            place(r, allowance.getOrDefault(r.job().jobId(), 0), in, w, updates);
        }
        for (JobRecord r : inState(EnumSet.of(JobState.VERIFYING))) {
            verify(r, in, w, updates);
        }
        return updates;
    }

    private void admitClaims(TickInput in, List<JobUpdate> updates) {
        for (JobRecord r : inState(EnumSet.of(JobState.PENDING_APPROVAL))) {
            ConstructionJob j = r.job();
            boolean build = j.kind() == JobKind.BUILD;
            // MODIFY, REPAIR and ROLLBACK work on an existing claim: it must be live, here, and the job owner's
            List<Issue> issues = new ArrayList<>(build ? List.of() : claims.checkOwned(j.claimId(), j.ownerUuid(), j.dimension()));
            if (issues.isEmpty()) {
                issues.addAll(claims.check(j.ownerUuid(), j.dimension(), r.operatingBox(), build ? null : j.claimId()));
            }
            if (!issues.isEmpty()) {
                change(r, j.withLastError(issues.get(0).id()).on(JobEvent.CLAIM_REFUSED), updates);
                continue;
            }
            if (build) {
                claims.reserve(j.claimId(), j.ownerUuid(), j.dimension(), r.manifest().worldBounds(), r.operatingBox(), in.tick());
            } else if (j.kind() == JobKind.MODIFY) {
                claims.grow(j.claimId(), r.manifest().worldBounds(), r.operatingBox());
            }
            change(r, j.on(JobEvent.ADMITTED), updates);
        }
    }

    private void presence(TickInput in, JobWorld w, List<JobUpdate> updates) {
        for (JobRecord r : List.copyOf(jobs.values())) {
            ConstructionJob j = r.job();
            // offline work needs both the setting and the adapter's yes for this job (its chunks held, an actor to place)
            boolean present = w.ownerOnline(j.ownerUuid()) || (continueWhileOffline && w.mayRunWithoutOwner(j));
            if (!present && AWAY_PAUSES.contains(j.state())) {
                pause(r, PauseReason.OWNER_OFFLINE, in, updates, null);
            } else if (present && j.state() == JobState.PAUSED) {
                boolean retry = RETRIED.contains(j.pauseReason()) && in.tick() - r.pausedAtTick >= RETRY_INTERVAL_TICKS;
                if (j.pauseReason() == PauseReason.OWNER_OFFLINE || retry) {
                    change(r, j.on(JobEvent.RESUME), updates);
                }
            }
        }
    }

    private void start(List<JobUpdate> updates) {
        List<QueuedJob> queued = inState(EnumSet.of(JobState.QUEUED)).stream()
                .map(r -> new QueuedJob(r.job().jobId(), r.job().ownerUuid())).toList();
        List<BudgetJob> running = inState(ACTIVE).stream().map(this::budgetJob).toList();
        for (String id : budget.admit(queued, running)) {
            JobRecord r = jobs.get(id);
            r.admittedOrder = admissions++;
            change(r, r.job().on(JobEvent.START), updates);
        }
    }

    private BudgetJob budgetJob(JobRecord r) {
        return new BudgetJob(r.job().jobId(), r.job().ownerUuid(), fastPhase(r),
                ConstructionBudget.droneCount(r.program().size(), budgetConfig), r.lastDroneTick, r.admittedOrder);
    }

    private boolean fastPhase(JobRecord r) {
        if (!fastStructure || r.repair != null) {
            return false;
        }
        int c = r.job().cursor();
        if (c >= r.program().size() || r.program().isRestore(c)) {
            return true;
        }
        return FAST_PHASES.contains(r.program().put(c).placement().phase());
    }

    private void place(JobRecord r, int allowance, TickInput in, JobWorld w, List<JobUpdate> updates) {
        ConstructionJob j = r.job();
        // the repair queue outlives a pause: PAUSED -> QUEUED -> RUNNING goes on with it instead of the finished main program
        boolean repairing = r.repair != null;
        JobProgram program = repairing ? r.repair.program() : r.program();
        int cursor = repairing ? r.repair.cursor() : j.cursor();
        if (cursor >= program.size()) {
            finishPlacing(r, updates);
            return;
        }
        if (allowance == 0) {
            return;
        }
        // the allowance was sized for the pace at the cursor: stop where the pace changes and re-budget next tick
        int steps = repairing ? allowance : samePaceSteps(program, cursor, allowance, fastStructure);
        ExecutionContext ctx = new ExecutionContext(j, program, w.world(j.dimension()),
                w.materials(j.ownerUuid(), j.materialPolicy(), j.claimId()), r.journal(), ledgers, registry(j.claimId()),
                r.outcome(), r.skipSiteChanges, wal);
        StepReport rep = ConstructionExecutor.run(ctx, cursor, steps);
        r.lastRun = wal.lastRun();
        if (repairing) {
            r.repair = new RepairQueue(program, rep.cursor());
        } else {
            r.setJob(j.withCursor(rep.cursor()));
        }
        if (!rep.touched().isEmpty()) {
            r.lastDroneTick = in.tick();
        }
        if (rep.pause() != null) {
            if (rep.pause() == PauseReason.SITE_CHANGED && !program.isRestore(rep.cursor())) {
                r.setJob(r.job().withLastError(siteChanged(program.put(rep.cursor()).placement())));
            }
            pause(r, rep.pause(), in, updates, rep);
            return;
        }
        updates.add(new JobUpdate(r.job(), rep.touched(), List.of(), rep.conflicts(), false));
        if (rep.cursor() >= program.size()) {
            finishPlacing(r, updates);
        }
    }

    /** F-3: the pause names its cause as an Issue id (E-SITE-CHANGED:node#...), which the owner's text is built from. */
    static String siteChanged(Placement p) {
        IntPos pos = p.pos();
        return Issue.of(IssueCode.E_SITE_CHANGED, List.of(p.partNodeId()),
                "位置" + pos.x() + "," + pos.y() + "," + pos.z() + "が、調べた後で変わりました").id();
    }

    /** Program steps from {@code cursor}, at most {@code max}, before the pace changes between fast and drone-paced. */
    static int samePaceSteps(JobProgram program, int cursor, int max, boolean fastStructure) {
        if (!fastStructure) {
            return max;
        }
        boolean fast = fastAt(program, cursor);
        int n = 0;
        while (n < max && cursor + n < program.size() && fastAt(program, cursor + n) == fast) {
            n++;
        }
        return n;
    }

    private static boolean fastAt(JobProgram program, int i) {
        return program.isRestore(i) || FAST_PHASES.contains(program.put(i).placement().phase());
    }

    private void finishPlacing(JobRecord r, List<JobUpdate> updates) {
        r.repair = null;
        r.placementDeviations.clear();
        r.restoreFailures.clear();
        r.collector = new SnapshotCollector(verifyPositions(r));
        // a repair resumed after a pause runs in RUNNING; RUNNING -> VERIFYING is PLACED_ALL and keeps the round
        change(r, r.job().on(r.job().state() == JobState.REPAIRING ? JobEvent.REPAIRED : JobEvent.PLACED_ALL), updates);
    }

    private static List<IntPos> verifyPositions(JobRecord r) {
        List<IntPos> out = new ArrayList<>();
        if (r.job().kind() != JobKind.ROLLBACK) {
            for (Placement p : r.manifest().placements()) {
                out.add(p.pos());
            }
        }
        for (RestoreItem ri : r.program().restores()) {
            out.add(ri.pos());
        }
        return out;
    }

    private void verify(JobRecord r, TickInput in, JobWorld w, List<JobUpdate> updates) {
        if (r.collector == null) {
            r.collector = new SnapshotCollector(verifyPositions(r));
        }
        WorldPort world = w.world(r.job().dimension());
        for (IntPos p : r.collector.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
            WorldCell c = world.read(p);
            if (c.loaded()) {
                r.collector.accept(p, c.observed());
            } else {
                r.collector.unloaded(p);
            }
        }
        if (!r.collector.unloadedPositions().isEmpty()) {
            r.collector = null;
            pause(r, PauseReason.CHUNK_UNLOADED, in, updates, null);
            return;
        }
        while (r.collector.windowFull() && !r.collector.done()) {
            verifyWindow(r, r.collector.takeWindow());
        }
        if (r.collector.done()) {
            // a job ends (or goes on to a repair round) only when a durable point covers its runs: a crash after the end
            // must not find the job finished while the world lost its last writes (the runtime makes the checkpoint)
            if (wal.durableUpTo() < r.lastRun) {
                r.awaitingDurable = true;
                return;
            }
            r.awaitingDurable = false;
            finishVerification(r, updates);
        }
    }

    /** A job waits for a durable point before it may end: the runtime should make a checkpoint (a flushed save) soon. */
    public boolean checkpointWanted() {
        return jobs.values().stream().anyMatch(r -> r.awaitingDurable);
    }

    /** The runtime made a flushed save of the whole server; every run so far is on the disk. */
    public void markDurable() {
        wal.markDurable(wal.lastRun());
    }

    private void verifyWindow(JobRecord r, SnapshotCollector.Window win) {
        int placements = r.job().kind() == JobKind.ROLLBACK ? 0 : r.manifest().placements().size();
        if (win.fromIndex() < placements) {
            r.placementDeviations.addAll(SnapshotDiff.compare(r.manifest(), win.snapshot(),
                    new CompareScope.IndexRange(win.fromIndex(), Math.min(win.toIndexExclusive(), placements)),
                    VolatileProps.of(r.nodeTypes(), registry)).deviations());
        }
        Set<IntPos> conflicted = new HashSet<>();
        for (Conflict c : r.outcome().conflicts()) {
            conflicted.add(c.pos());
        }
        for (int i = Math.max(win.fromIndex(), placements); i < win.toIndexExclusive(); i++) {
            RestoreItem ri = r.program().restore(i - placements);
            ObservedBlock o = win.snapshot().blocks().get(ri.pos());
            if (o != null && !BlockMatch.satisfies(o.block(), ri.restoreTo(), Set.of()) && !conflicted.contains(ri.pos())) {
                r.restoreFailures.add(i - placements);
            }
        }
    }

    private void finishVerification(JobRecord r, List<JobUpdate> updates) {
        ConstructionJob j = r.job();
        PlacedRegistry reg = registry(j.claimId());
        RepairPlan plan = j.kind() == JobKind.ROLLBACK ? new RepairPlan(List.of(), List.of(), List.of())
                : RepairPlanner.plan(r.manifest(), r.placementDeviations, i -> r.beforeJournal().at(i).map(JournalRecord::before),
                        reg::contains, r.outcome().deniedIndexes());
        List<Conflict> fresh = new ArrayList<>();
        for (Conflict c : plan.conflicts()) {
            if (r.outcome().addConflict(c)) {
                fresh.add(c);
            }
        }
        List<RestoreItem> retry = new ArrayList<>();
        for (int i : r.restoreFailures) {
            retry.add(r.program().restore(i));
        }
        r.collector = null;
        r.placementDeviations.clear();
        r.restoreFailures.clear();
        // VERIFIED means no deviation at all: a conflict left alone (a player's block, a removal not done) is PARTIAL
        int conflicts = r.outcome().conflicts().size();
        boolean clean = plan.reapply().isEmpty() && plan.unfixable().isEmpty() && retry.isEmpty() && conflicts == 0;
        if (clean) {
            change(r, j.withLastError("").on(JobEvent.CLEAN), updates, fresh);
            if (j.kind() == JobKind.ROLLBACK) {
                finishRollback(r, updates);
            }
            return;
        }
        boolean fixable = !plan.reapply().isEmpty() || !retry.isEmpty();
        // a job recovered in the middle of a repair round re-derives what the round lost from the world and goes on in
        // the same round: the round's ledger keys then charge nothing twice (Task 24)
        boolean sameRound = r.recoveredRound && j.repairRound() > 0;
        r.recoveredRound = false;
        if (fixable && (sameRound || j.repairRound() < ConstructionJob.MAX_REPAIR_ROUNDS)) {
            int round = sameRound ? j.repairRound() : j.repairRound() + 1;
            r.repair = new RepairQueue(new JobProgram(retry, JobProgram.repair(r.manifest(), plan.reapply(), round).puts()), 0);
            change(r, j.withRepairRound(round).on(JobEvent.NEED_REPAIR), updates, fresh);
            return;
        }
        r.remaining.clear();
        r.remaining.addAll(plan.unfixable());
        for (int i : plan.reapply()) {
            Placement p = r.manifest().placements().get(i);
            r.remaining.add(new Deviation(i, p.block(), new ObservedBlock(BlockSpec.AIR),
                    DeviationKind.MISSING));
        }
        change(r, j.withLastError(summary(r.remaining, retry.size(), conflicts)).on(JobEvent.GIVE_UP), updates, fresh);
    }

    /** A machine-readable reason for PARTIAL, e.g. "missing=1 blocked=2 conflict=1"; the owner's text is built from it. */
    static String summary(List<Deviation> remaining, int restoreFailures, int conflicts) {
        Map<String, Integer> byKind = new TreeMap<>();
        for (Deviation d : remaining) {
            byKind.merge(d.kind().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byKind.forEach((k, n) -> sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(n));
        if (restoreFailures > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("restore=").append(restoreFailures);
        }
        if (conflicts > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("conflict=").append(conflicts);
        }
        return sb.toString();
    }

    private void finishRollback(JobRecord rollback, List<JobUpdate> updates) {
        String claimId = rollback.job().claimId();
        for (JobRecord r : jobsInClaim(claimId)) {
            if (r != rollback && ROLLBACKABLE.contains(r.job().state())) {
                change(r, r.job().on(JobEvent.ROLL_BACK_DONE), updates);
            }
        }
        claims.release(claimId);
        registries.remove(claimId);
    }

    private void pause(JobRecord r, PauseReason reason, TickInput in, List<JobUpdate> updates, StepReport rep) {
        r.pausedAtTick = in.tick();
        ConstructionJob next = r.job().paused(reason);
        r.setJob(next);
        updates.add(new JobUpdate(next, rep == null ? List.of() : rep.touched(), rep == null ? List.of() : rep.shortage(),
                rep == null ? List.of() : rep.conflicts(), true));
    }

    private void change(JobRecord r, ConstructionJob next, List<JobUpdate> updates) {
        change(r, next, updates, List.of());
    }

    private void change(JobRecord r, ConstructionJob next, List<JobUpdate> updates, List<Conflict> conflicts) {
        boolean moved = r.job().state() != next.state();
        r.setJob(next);
        updates.add(new JobUpdate(next, List.of(), List.of(), conflicts, moved));
    }

    public ControlResult cancel(String jobId, UUID requester, boolean op) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            return ControlResult.NOT_FOUND;
        }
        if (!r.job().ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        if (!JobStateMachine.allows(r.job().state(), JobEvent.CANCEL)) {
            return ControlResult.WRONG_STATE;
        }
        r.collector = null;
        r.repair = null;
        r.setJob(r.job().on(JobEvent.CANCEL));
        return ControlResult.OK;
    }

    public ControlResult resume(String jobId, UUID requester, boolean op, boolean skipSiteChanges) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            return ControlResult.NOT_FOUND;
        }
        if (!r.job().ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        if (r.job().state() != JobState.PAUSED || r.job().pauseReason() == PauseReason.RECOVERY_NEEDED) {
            return ControlResult.WRONG_STATE;
        }
        r.skipSiteChanges = skipSiteChanges;
        r.setJob(r.job().on(JobEvent.RESUME));
        return ControlResult.OK;
    }

    public Optional<JobStatus> status(String jobId) {
        JobRecord r = jobs.get(jobId);
        return r == null ? Optional.empty() : Optional.of(statusOf(r));
    }

    public List<JobStatus> statuses() {
        return jobs.values().stream().map(this::statusOf).toList();
    }

    private JobStatus statusOf(JobRecord r) {
        ConstructionJob j = r.job();
        PauseReason shown = j.pauseReason();
        if (j.state() == JobState.QUEUED || (j.state() == JobState.RUNNING && slowed)) {
            shown = j.state() == JobState.QUEUED && inState(ACTIVE).isEmpty() ? null : PauseReason.SERVER_BUSY;
        }
        return new JobStatus(j.jobId(), j.ownerUuid(), j.kind(), j.state(), shown, j.cursor(), j.total(), j.repairRound(),
                r.outcome().conflicts().size(), r.outcome().skipped().size(), j.lastError(), j.claimId(), j.dimension());
    }

    public Optional<JobRecord> record(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<JobRecord> records() {
        return List.copyOf(jobs.values());
    }

    public List<JobRecord> jobsInClaim(String claimId) {
        return jobs.values().stream().filter(r -> r.job().claimId().equals(claimId)).toList();
    }

    public PlacedRegistry registry(String claimId) {
        return registries.computeIfAbsent(claimId, PlacedRegistry::new);
    }

    public Map<String, PlacedRegistry> registries() {
        return registries;
    }

    public LedgerBook ledgers() {
        return ledgers;
    }

    public ClaimBook claims() {
        return claims;
    }

    public boolean slowed() {
        return slowed;
    }

    private List<JobRecord> inState(Set<JobState> states) {
        return jobs.values().stream().filter(r -> states.contains(r.job().state())).toList();
    }
}
```
(`summary`のキーは`DeviationKind`の小文字名で、`JobServiceL7Test`の`"blocked=1"`・`"missing=1"`はこの形を確かめている。)

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ジョブの進行(JobService)を追加(区画の予約・所有者の在不在・予算・設置・L7の検査と3ラウンドの修復・取消と再開)(自然言語→工場建設 P4 Task 13)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 14: 子供に見える文言をデータにする(`ChildMessages`・`ja_jp.json`・`en_us.json`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

P4で子供の目に入るのは、デバッグコマンドの返事、ジョブの状態と進み具合、止まった理由、`Issue`の説明、ドローンの演出だけ。これを**翻訳キーのデータ**にし、サーバーは`Component.translatable`で送る(文言をコードに直書きしない。F-18)。日本語は、小学3年生のペルソナ(ひらがな中心)でも読めるように、漢字を少なくする(機械的な検査: 漢字の割合30%以下)。ペルソナの確認はTask 36。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{ChildMessages,MessageKey}.java`、`src/main/resources/assets/micradrone/lang/ja_jp.json`
- Modify: `src/main/resources/assets/micradrone/lang/en_us.json`(`micradrone.build.`で始まるキーを足す。既存のキーは変えない)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/ChildMessagesTest.java`

**Interfaces:**
- Consumes: `JobState`・`PauseReason`・`ApprovalRejection`・`ControlResult`・`IssueCode`
- Produces:
  - `record MessageKey(String key, List<String> args)`
  - `final class ChildMessages`: `PREFIX = "micradrone.build."`、`static String state(JobState)`・`pause(PauseReason)`・`rejection(ApprovalRejection)`・`control(ControlResult)`・`issue(IssueCode)`(P4で子供に出す`IssueCode`は`P4_ISSUES`の集合。それ以外は`ISSUE_OTHER`=「もんだいが あるよ」)、定数のキー: `SUBMIT_OK`・`SUBMIT_ISSUES`・`SUBMIT_BUSY`・`SUBMIT_BAD_SOURCE`・`APPROVE_OK`・`PROGRESS`・`DONE`・`PARTIAL`・`CONFLICTS`・`SHORTAGE`・`CANCELLED`・`RESUMED`・`STATUS_LINE`・`NO_JOBS`・`TERRAIN_CONFIRM`・`DESTRUCTIVE_CONFIRM`・`ISSUE_OTHER`・`DRONE_ARRIVED`、`static Set<String> allKeys()`(コードが使うキーの全部。lang ファイルとの一致の検査に使う)、`static String withCode(String childText, String code)`は作らない(コードの併記はアダプタの`ServerMessages`が灰色で足す)
  - `static MessageKey issueLine(Issue)`(子供に見せる行は訳した文だけ。問題のコードとIDは、サーバーのログと`/micradrone build status <jobId> --debug`にだけ出す。Codexのレビュー2026-09-30の決定)、`static MessageKey partial(int unplaced)`(`PARTIAL`は「置けなかった所がN個」。`lastError`の`missing=1 conflict=1`のような機械向けの理由は子供に見せない)
  - `P4_ISSUES = {E_SITE_BLOCKED, E_SITE_CHANGED, E_OUT_OF_BOUNDS, E_BLOCK_FORBIDDEN, E_MATERIAL_UNKNOWN, E_MATERIAL_SHORT, E_TERRAFORM_UNCONFIRMED, E_REPLACE_UNCONFIRMED, E_CLAIM_OVERLAP, E_CLAIM_LIMIT, E_CLAIM_INVALID, E_REGISTRY_VERSION, E_TEMPLATE_UNVERIFIED, E_SITE_MISSING, E_PARAM_RANGE, E_UNKNOWN_PART}`(`E_PERMISSION_DENIED`はTask 26で足す)

- [ ] **Step 1: 失敗するテストを書く**

`ChildMessagesTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ChildMessagesTest {
    private static final Path LANG = Path.of("src/main/resources/assets/micradrone/lang");
    /** The 小3 persona reads mostly hiragana: at most this share of a Japanese message may be kanji. */
    private static final double MAX_KANJI_SHARE = 0.30;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lang(String file) throws IOException {
        return (Map<String, Object>) MiniJson.parse(Files.readString(LANG.resolve(file), StandardCharsets.UTF_8));
    }

    private static Set<String> buildKeys(Map<String, Object> lang) {
        Set<String> out = new TreeSet<>();
        for (String k : lang.keySet()) {
            if (k.startsWith(ChildMessages.PREFIX)) {
                out.add(k);
            }
        }
        return out;
    }

    @Test
    void everyKeyTheCodeUsesExistsInJapaneseAndEnglishAndNothingElse() throws IOException {
        Set<String> code = new TreeSet<>(ChildMessages.allKeys());
        assertEquals(code, buildKeys(lang("ja_jp.json")));
        assertEquals(code, buildKeys(lang("en_us.json")));
    }

    @Test
    void everyStatePauseRejectionAndControlHasAKey() {
        for (JobState s : JobState.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.state(s)), s.name());
        }
        for (PauseReason r : PauseReason.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.pause(r)), r.name());
        }
        for (ApprovalRejection r : ApprovalRejection.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.rejection(r)), r.name());
        }
        for (ControlResult c : ControlResult.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.control(c)), c.name());
        }
        assertEquals(ChildMessages.ISSUE_OTHER, ChildMessages.issue(IssueCode.E_ROT_CONFLICT), "not a P4 message: generic");
        assertEquals("micradrone.build.issue.e_site_blocked", ChildMessages.issue(IssueCode.E_SITE_BLOCKED));
    }

    @Test
    void theJapaneseIsEasyToRead() throws IOException {
        for (Map.Entry<String, Object> e : lang("ja_jp.json").entrySet()) {
            if (!e.getKey().startsWith(ChildMessages.PREFIX)) {
                continue;
            }
            String text = (String) e.getValue();
            long kanji = text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
            long letters = text.codePoints().filter(Character::isLetter).count();
            assertTrue(letters == 0 || kanji / (double) letters <= MAX_KANJI_SHARE, e.getKey() + ": " + text);
            assertFalse(text.matches(".*\\b[EW]-[A-Z-]+\\b.*"), "no raw issue codes in a child's text: " + e.getKey());
        }
    }

    @Test
    void theLinesBuiltForAChildNeverCarryAnIssueCode() {
        for (IssueCode c : IssueCode.values()) {
            MessageKey line = ChildMessages.issueLine(Issue.of(c, java.util.List.of("wall-n"), "x"));
            assertFalse(line.key().contains(c.label()), c.label());
            for (String arg : line.args()) {
                assertFalse(arg.matches(".*\\b[EW]-[A-Z-]+.*"), "an issue code leaked into a child's line: " + arg);
            }
        }
        assertEquals(java.util.List.of("3"), ChildMessages.partial(3).args(), "a count, not \"missing=2 conflict=1\"");
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.ChildMessagesTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`MessageKey.java`: `public record MessageKey(String key, List<String> args) { public MessageKey { Objects.requireNonNull(key); args = List.copyOf(args); } public static MessageKey of(String key, Object... args) { ... String.valueOf ... } }`。

`ChildMessages.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The translation keys of everything a child sees from the construction runtime (F-18): the texts live in the lang
 * files, never in code. ChildMessagesTest keeps this list and both lang files equal.
 */
public final class ChildMessages {
    public static final String PREFIX = "micradrone.build.";
    public static final String SUBMIT_OK = PREFIX + "submit.ok";
    public static final String SUBMIT_ISSUES = PREFIX + "submit.issues";
    public static final String SUBMIT_BUSY = PREFIX + "submit.busy";
    public static final String SUBMIT_BAD_SOURCE = PREFIX + "submit.bad_source";
    public static final String APPROVE_OK = PREFIX + "approve.ok";
    public static final String PROGRESS = PREFIX + "progress";
    public static final String DONE = PREFIX + "done";
    public static final String PARTIAL = PREFIX + "partial";
    public static final String CONFLICTS = PREFIX + "conflicts";
    public static final String SHORTAGE = PREFIX + "shortage";
    public static final String CANCELLED = PREFIX + "cancelled";
    public static final String RESUMED = PREFIX + "resumed";
    public static final String STATUS_LINE = PREFIX + "status.line";
    public static final String NO_JOBS = PREFIX + "status.none";
    public static final String TERRAIN_CONFIRM = PREFIX + "confirm.terrain";
    public static final String DESTRUCTIVE_CONFIRM = PREFIX + "confirm.destructive";
    public static final String ISSUE_OTHER = PREFIX + "issue.other";
    public static final String DRONE_ARRIVED = PREFIX + "drone.arrived";
    /** A job whose log after the last durable point waits for its owner's answer (adopt or discard). Task 24. */
    public static final String RECOVER_ASK = PREFIX + "recover.ask";
    public static final Set<IssueCode> P4_ISSUES = EnumSet.of(IssueCode.E_SITE_BLOCKED, IssueCode.E_SITE_CHANGED,
            IssueCode.E_OUT_OF_BOUNDS, IssueCode.E_BLOCK_FORBIDDEN, IssueCode.E_MATERIAL_UNKNOWN, IssueCode.E_MATERIAL_SHORT,
            IssueCode.E_TERRAFORM_UNCONFIRMED, IssueCode.E_REPLACE_UNCONFIRMED, IssueCode.E_CLAIM_OVERLAP,
            IssueCode.E_CLAIM_LIMIT, IssueCode.E_CLAIM_INVALID, IssueCode.E_REGISTRY_VERSION, IssueCode.E_TEMPLATE_UNVERIFIED,
            IssueCode.E_SITE_MISSING, IssueCode.E_PARAM_RANGE, IssueCode.E_UNKNOWN_PART);
    private static final Set<String> FIXED = Set.of(SUBMIT_OK, SUBMIT_ISSUES, SUBMIT_BUSY, SUBMIT_BAD_SOURCE, APPROVE_OK,
            PROGRESS, DONE, PARTIAL, CONFLICTS, SHORTAGE, CANCELLED, RESUMED, STATUS_LINE, NO_JOBS, TERRAIN_CONFIRM,
            DESTRUCTIVE_CONFIRM, ISSUE_OTHER, DRONE_ARRIVED, RECOVER_ASK);

    private ChildMessages() {
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String state(JobState s) {
        return PREFIX + "state." + lower(s.name());
    }

    public static String pause(PauseReason r) {
        return PREFIX + "pause." + lower(r.name());
    }

    public static String rejection(ApprovalRejection r) {
        return PREFIX + "reject." + lower(r.name());
    }

    public static String control(ControlResult c) {
        return PREFIX + "control." + lower(c.name());
    }

    public static String issue(IssueCode c) {
        return P4_ISSUES.contains(c) ? PREFIX + "issue." + lower(c.label().replace('-', '_')) : ISSUE_OTHER;
    }

    /**
     * The line a child sees for an issue: the translated text only. The issue's code and id go to the server log and to
     * {@code /micradrone build status <jobId> --debug}, never into a child's chat.
     */
    public static MessageKey issueLine(Issue issue) {
        return MessageKey.of(issue(issue.code()));
    }

    /** "Almost done, N places could not be built": a count, not the machine-readable reason in {@code lastError}. */
    public static MessageKey partial(int unplaced) {
        return MessageKey.of(PARTIAL, unplaced);
    }

    public static Set<String> allKeys() {
        Set<String> out = new TreeSet<>(FIXED);
        for (JobState s : JobState.values()) {
            out.add(state(s));
        }
        for (PauseReason r : PauseReason.values()) {
            out.add(pause(r));
        }
        for (ApprovalRejection r : ApprovalRejection.values()) {
            out.add(rejection(r));
        }
        for (ControlResult c : ControlResult.values()) {
            out.add(control(c));
        }
        for (IssueCode c : P4_ISSUES) {
            out.add(issue(c));
        }
        return out;
    }
}
```

`ja_jp.json`(新規。`micradrone.build.`のキーだけ。既存の英語のキーの日本語訳は、この計画の範囲では作らない。Minecraftは無いキーを`en_us.json`で補う)。`%1$s`などの引数は、アダプタが渡す値:
```json
{
  "micradrone.build.submit.ok": "けいかくを うけとったよ。ブロック %1$s こ、やく %2$s びょう。いいなら /micradrone build approve %3$s",
  "micradrone.build.submit.issues": "このままでは たてられないよ。もんだいが %1$s こ あるよ",
  "micradrone.build.submit.busy": "まえの けいさんが まだ おわっていないよ。すこし まってね",
  "micradrone.build.submit.bad_source": "その けいかくの ファイルが よめないよ: %1$s",
  "micradrone.build.approve.ok": "オッケー! ドローンが たてはじめるよ(しごと %1$s)",
  "micradrone.build.progress": "しごと %1$s: %2$s / %3$s こ おいたよ",
  "micradrone.build.done": "できあがり! しらべたら ぜんぶ あっていたよ",
  "micradrone.build.partial": "ほとんど できたけど、おけなかった ところが %1$s こ あるよ",
  "micradrone.build.conflicts": "だれかが かえた ところが %1$s こ あったから、さわらずに のこしたよ",
  "micradrone.build.shortage": "%1$s が %2$s こ たりないよ。もちものか ほきゅうの チェストに いれてね",
  "micradrone.build.cancelled": "しごと %1$s を とめたよ。できた ところは そのまま のこるよ",
  "micradrone.build.resumed": "しごと %1$s を また はじめるよ",
  "micradrone.build.status.line": "%1$s: %2$s(%3$s / %4$s)%5$s",
  "micradrone.build.status.none": "いま うごいている しごとは ないよ",
  "micradrone.build.confirm.terrain": "じめんを けずる %1$s こ、もる %2$s こ。いいなら approve に confirm-terraform を つけてね",
  "micradrone.build.confirm.destructive": "みず・ようがん %1$s こ、はっぱ %2$s こ、からの いれもの %3$s こを おきかえるよ。いいなら confirm-destructive を つけてね",
  "micradrone.build.issue.other": "もんだいが あるよ",
  "micradrone.build.drone.arrived": "ドローンが きたよ!",
  "micradrone.build.state.pending_approval": "しょうにん まち",
  "micradrone.build.state.queued": "じゅんばん まち",
  "micradrone.build.state.running": "たてているよ",
  "micradrone.build.state.paused": "おやすみ ちゅう",
  "micradrone.build.state.verifying": "できたか しらべているよ",
  "micradrone.build.state.repairing": "なおしているよ",
  "micradrone.build.state.assembling": "くみたてているよ",
  "micradrone.build.state.verified": "かんせい",
  "micradrone.build.state.partial": "ほとんど かんせい",
  "micradrone.build.state.failed": "しっぱい",
  "micradrone.build.state.cancelled": "とめた",
  "micradrone.build.state.rolled_back": "もとに もどした",
  "micradrone.build.pause.owner_offline": "たてる ひとが いないから まっているよ",
  "micradrone.build.pause.chunk_unloaded": "とおくて ばしょが よみこまれていないよ。ちかくに いってね",
  "micradrone.build.pause.materials_missing": "ざいりょうが たりないよ",
  "micradrone.build.pause.server_busy": "サーバーが いそがしいから ゆっくり やっているよ",
  "micradrone.build.pause.no_room": "もちものが いっぱいで、かえす ものが はいらないよ。すこし あけてね",
  "micradrone.build.pause.recovery_needed": "サーバーが とちゅうで とまったので、どこまで できたか たしかめられない ところが あるよ。おとなの ひとに /micradrone build recover を たのんでね",
  "micradrone.build.recover.ask": "しごと %1$s: とまる まえに %2$s を したか どうか わからないよ。したなら /micradrone build recover %1$s adopt 、していないなら discard を えらんでね",
  "micradrone.build.pause.user": "とめて あるよ",
  "micradrone.build.pause.site_changed": "おく ばしょに なにかが おかれたよ。どかすか、resume で とばしてね",
  "micradrone.build.reject.no_pending": "しょうにん まちの けいかくが ないよ。さきに submit してね",
  "micradrone.build.reject.not_owner": "これは ほかの ひとの けいかくだよ",
  "micradrone.build.reject.expired": "じかんが たちすぎたよ。もう いちど submit してね",
  "micradrone.build.reject.hash_mismatch": "しるし(ハッシュ)が ちがうよ。もう いちど submit してね",
  "micradrone.build.reject.dimension_mismatch": "べつの せかいに いるよ。おなじ せかいで しょうにんしてね",
  "micradrone.build.reject.registry_changed": "ぶひんの リストが かわったよ。もう いちど submit してね",
  "micradrone.build.reject.survey_expired": "じめんの しらべが ふるく なったよ。もう いちど submit してね",
  "micradrone.build.reject.risk_not_acceptable": "その もんだいは うけいれられないよ",
  "micradrone.build.reject.blocking_issues": "なおさないと たてられない もんだいが あるよ",
  "micradrone.build.reject.terraform_unconfirmed": "じめんを かえるよ。confirm-terraform を つけてね",
  "micradrone.build.reject.destructive_unconfirmed": "みずや はっぱを おきかえるよ。confirm-destructive を つけてね",
  "micradrone.build.reject.assembly_not_available": "くみたてる ぶひんは まだ たてられないよ",
  "micradrone.build.control.ok": "オッケー",
  "micradrone.build.control.not_found": "その しごとは ないよ",
  "micradrone.build.control.not_allowed": "ほかの ひとの しごとは かえられないよ",
  "micradrone.build.control.wrong_state": "いまは それが できないよ",
  "micradrone.build.issue.e_site_blocked": "おく ばしょに、どかせない ブロックが あるよ",
  "micradrone.build.issue.e_site_changed": "しらべた あとで、ばしょが かわったよ",
  "micradrone.build.issue.e_out_of_bounds": "おおきすぎるか、はみだして いるよ",
  "micradrone.build.issue.e_block_forbidden": "おいては いけない ブロックが はいっているよ",
  "micradrone.build.issue.e_material_unknown": "この ゲームに ない ざいりょうが あるよ",
  "micradrone.build.issue.e_material_short": "ざいりょうが たりないよ",
  "micradrone.build.issue.e_terraform_unconfirmed": "じめんを かえる ことを たしかめてね",
  "micradrone.build.issue.e_replace_unconfirmed": "みずや はっぱを おきかえる ことを たしかめてね",
  "micradrone.build.issue.e_claim_overlap": "ほかの ひとの たてもの ばしょと かさなっているよ",
  "micradrone.build.issue.e_claim_limit": "たてもの ばしょは もう いっぱいだよ。いらない ものを かたづけてね",
  "micradrone.build.issue.e_claim_invalid": "この たてもの ばしょでは できないよ。じぶんの ばしょで、ちかくに いってね",
  "micradrone.build.issue.e_registry_version": "ぶひんの リストの バージョンが ちがうよ",
  "micradrone.build.issue.e_template_unverified": "しらべていない くみあわせ ぶひんが あるよ",
  "micradrone.build.issue.e_site_missing": "どこに たてるか きまっていないよ",
  "micradrone.build.issue.e_param_range": "すうじが おおきすぎるか ちいさすぎるよ",
  "micradrone.build.issue.e_unknown_part": "しらない ぶひんが あるよ"
}
```
`en_us.json`には同じキーの英語を足す(例: `"micradrone.build.submit.ok": "Plan received: %1$s blocks, about %2$s s. If it looks right: /micradrone build approve %3$s"`、`"micradrone.build.done": "Finished! Every block checked out."`など、上の日本語と同じ意味・同じ引数の並びで全キー)。英語の全文は、上の日本語の各行を1対1で訳して書く(省略しない。`ChildMessagesTest`が全キーの存在を検査する)。

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。漢字の割合の検査で落ちた文は、ひらがなに開いて直す(意味は変えない)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add src/main/java/io/github/khayashi4337/micradrone/construction/core/ChildMessages.java
git add src/main/java/io/github/khayashi4337/micradrone/construction/core/MessageKey.java
git add <src/main/resources/assets/micradrone/lang の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/ChildMessagesTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 子供に見える施工の文言を翻訳キーのデータにする(ChildMessages・ja_jp.json・en_us.json。漢字の割合の検査つき)(自然言語→工場建設 P4 Task 14)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 15: アダプタ — 世界の読み書き(`ServerWorldPort`・`PlacementGuard`・`ServerStateReader`)、地形調査(`ServerSurveyor`)、タグ

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

ここから先の`construction`パッケージはMinecraftに触れる薄いアダプタ。判断は`construction.core`に任せ、ここでは「読む・置く・イベントを投げる」だけをする。**アダプタの挙動は単体テストで検証したことにしない**: 純粋に切り出せる部分(調査の組み立て・範囲読み取りの区切り・タグの中身)だけJUnitで確かめ、実機での確認はTask 19以降の台本(devkit)で自動に行う。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/{BlockStates,ServerWorldPort,PlacementGuard,ServerStateReader,ServerSurveyor,BuildTags}.java`、`src/main/java/io/github/khayashi4337/micradrone/build/compile/{SiteSurveyBuilder,PalettePolicy}.java`、`src/main/java/io/github/khayashi4337/micradrone/build/analyze/{VoxelClassGrid,VoxelGridFiller}.java`、`src/main/resources/data/micradrone/tags/block/{terraformable,palette_allowed}.json`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/{SiteSurveyBuilderTest,BuildTagFilesTest,PalettePolicyTest}.java`、`src/test/java/io/github/khayashi4337/micradrone/build/analyze/VoxelGridFillerTest.java`
- Modify: `src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java`(`ALLOWED`に`entry("build.analyze", Set.of("build.model"))`。Task 10で`Map.ofEntries`にしてある。`build.analyze`は新しい純Javaの包みなので、無いと`thePurePackagesKeepTheMeasuredLayering`が落ちる)

**Interfaces:**
- Consumes: Task 4の`WorldPort`・`WorldCell`・`CellTrait`・`PlaceResult`、Task 5の`SiteSurvey`、既存の`PlaceableBlockPolicy.builtin()`・`PlaceableBlockPolicy(Set<String>)`(`BuiltinAllowList.ids()`はpackage-privateなので、アダプタからは使わない。同じ包みの`BuildTagFilesTest`だけが使う)。Minecraft/NeoForge(Task 2で読んだ物、`build/moddev/artifacts/neoforge-21.1.238-sources.jar`): `Level.captureBlockSnapshots`・`Level.capturedBlockSnapshots`・`Level.restoringBlockSnapshots`・`Level.markAndNotifyBlock(BlockPos, LevelChunk, BlockState, BlockState, int, int)`(`Level.java` 120〜122行・233〜300行)、`BlockSnapshot.getFlags()`・`restore(int)`、`EventHooks.onMultiBlockPlace`、`Block.UPDATE_CLIENTS = 2`・`UPDATE_KNOWN_SHAPE = 16`・`UPDATE_SUPPRESS_DROPS = 32`(`Block.java` 81〜82行)、`ServerLevel.updateNeighborsAt(BlockPos, Block)`・`BlockState.updateNeighbourShapes(LevelAccessor, BlockPos, int)`、`Capabilities.ItemHandler.BLOCK`・`Capabilities.FluidHandler.BLOCK`・`Level.getCapability(BlockCapability, BlockPos, Direction)`・`IItemHandler.getSlots()`・`extractItem(int, int, boolean)`・`Containers.dropItemStack(Level, double, double, double, ItemStack)`、`BlockStateParser.parseForBlock(HolderLookup<Block>, String, boolean)`(`net/minecraft/commands/arguments/blocks/BlockStateParser.java`)、`BuiltInRegistries.BLOCK.asLookup()`、`Property.getName(T)`、`Level.isLoaded(BlockPos)`、`Level.setBlock(BlockPos, BlockState, int)`と`Block.UPDATE_ALL = 3`、`BlockBehaviour.BlockStateBase.canBeReplaced()`・`getFluidState()`・`is(TagKey<Block>)`・`getDestroySpeed(BlockGetter, BlockPos)`、`BlockTags.LEAVES`・`BlockTags.LOGS`、`Container.isEmpty()`・`Containers.dropContents(Level, BlockPos, Container)`、`BlockSnapshot.create(ResourceKey<Level>, LevelAccessor, BlockPos)`・`restore()`、`EventHooks.onBlockPlace(Entity, BlockSnapshot, Direction)`、`BlockEvent.BreakEvent(Level, BlockPos, BlockState, Player)`、`FakePlayerFactory.get(ServerLevel, GameProfile)`、`Level.getHeight(Heightmap.Types, int, int)`と`Heightmap.Types.WORLD_SURFACE`
- Produces:
  - `BlockStates.toState(BlockSpec) → BlockState`(作れなければ`IllegalArgumentException`)、`BlockStates.toSpec(BlockState) → BlockSpec`(全プロパティ)
  - `ServerStateReader.read(ServerLevel, IntPos) → WorldCell`(`ServerWorldPort.read`と`ServerSurveyor`が共有する唯一の読み取り)、`ServerStateReader.step(ServerLevel, VoxelGridFiller, int maxReads, ToIntFunction<WorldCell> classifier)`(範囲の分類読み取りを1tick分進める。分類はP6が決める)
  - `final class PlacementGuard`: `PlacementGuard(MinecraftServer server, Function<UUID,String> ownerName)`、`PlaceResult place(ServerLevel, BlockPos, BlockState, UUID actor)`、`PlaceResult restore(ServerLevel, BlockPos, BlockState, UUID actor, boolean dropContentsFirst)`、`ServerPlayer actor(ServerLevel, UUID)`(オンラインならその人、いなければ`FakePlayerFactory.get(level, new GameProfile(uuid, name))`)
  - `final class ServerWorldPort implements WorldPort`: `ServerWorldPort(ServerLevel, PlacementGuard)`
  - `build.compile.SiteSurveyBuilder`(純粋): `SiteSurveyBuilder(String dimension, Box worldBounds)`、`List<int[]> nextColumns(int max)`(`{x, z}`)、`void column(int x, int z, int surfaceY, String surfaceBlock, boolean water, boolean tree)`、`boolean done()`、`SiteSurvey build()`、`SURVEY_COLUMNS_PER_TICK = 1_024`(1tickに調べる列の数。設計に数値なし: 128×128=16,384列を16tick=0.8秒に分ける。Task 35で設計に書く)
  - `ServerSurveyor.step(ServerLevel, SiteSurveyBuilder, int maxColumns) → boolean`(読み込まれていない列があれば`false`を返して止まる。**未読み込みの列を読みにいってチャンクを生成させない**)
  - `build.analyze.VoxelClassGrid(Box worldBox, byte[] classes)`(`01` 8節。`MAX_CELLS = 1_572_864`=約157万セル・約1.5MB)、`VoxelGridFiller(Box)`(純粋: `nextBatch(int)`・`set(IntPos, byte)`・`done()`・`grid()`)
  - `BuildTags.TERRAFORMABLE`(`micradrone:terraformable`)・`BuildTags.PALETTE_ALLOWED`(`micradrone:palette_allowed`)・`BuildTags.policy() → PlaceableBlockPolicy`(タグの中身で作る。D-22の「実行時はデータパックのタグに置き換える」)。**許可リスト(このタグと、登録された部品の出すブロック)を守らせるのは、サーバーのコンパイル**(`PlanCompiler`が`PlaceableBlockPolicy.checkMaterial`で検査。Task 12の`PlanCompilation`)で、`SafetyEnvelope`は**常に禁止のブロック**(`isAlwaysForbidden`)だけを、承認の前にもう一度見る(Task 6。2つの検査の役目を混ぜない。Codexのレビュー2026-09-30)

- 設置と撤去の順序(S-9の既定。**これが正本**。P4レビューD-3・G-2で直した):
  - **設置はバニラの`BlockItem`と同じ「捕まえてから決める」形**(`CommonHooks.onPlaceItemIntoWorld`、`CommonHooks.java` 594〜671行): `level.captureBlockSnapshots = true`→`level.setBlock(pos, state, Block.UPDATE_ALL)`(捕まえている間は`LevelChunk.setBlockState`が`onPlace`を呼ばず、`Level.setBlock`は隣への通知をしない。`Level.java` 244〜261行、`LevelChunk.java` 282行)→`captureBlockSnapshots = false`→捕まえた`BlockSnapshot`を取り出す→1個なら`EventHooks.onBlockPlace`、2個以上なら`onMultiBlockPlace`(持ち主=`actor`、`Direction.UP`)→キャンセルなら逆順に`restoringBlockSnapshots = true`で`snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS)`して`DENIED`(隣への影響はまだ起きていないので、元に戻すだけで済む)→通れば各スナップショットで`newState.onPlace(...)`と`level.markAndNotifyBlock(pos, chunk, old, new, flags, 512)`を、バニラと同じ順で呼ぶ。
  - **撤去は隣の形を動かさず、アイテムを落とさない**: `BreakEvent`を投げる→キャンセルなら何もせず`DENIED`→`dropContentsFirst`なら中身を落とす(下の「中身」)→`level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)`(=2|16|32。16で隣の形の更新をしないので、扉のもう片方や壁の看板が**アイテムを落として**外れることがない。`Level.markAndNotifyBlock`は形の更新に渡すフラグから32を消すので(`Level.java` 294行`& -34`)、32だけでは防げない)。隣の更新は、1つの部品を戻し終えた後に`WorldPort.settle`でまとめて行う: 各位置で`level.updateNeighborsAt(pos, 今のブロック)`と`今の状態.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)`。撤去の順序(付いている物から先、扉の上下は続けて)は純Javaの`Attachments.REMOVAL_ORDER`(Task 9)が決めるので、`settle`の時点で、このプロジェクトが付けた物はもう無い。
  - **中身**(完了条件13。保管庫・デポ・樽を同じ道で扱う。Createのクラスはimportしない): `level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)`があれば、全スロットを`extractItem(slot, Integer.MAX_VALUE, false)`で空になるまで取り出し、`Containers.dropItemStack(level, x+0.5, y+0.5, z+0.5, stack)`で落とす(Createの`item_vault`・`depot`はこの能力を出す。保管庫は複数ブロックで1つの中身なので、最初の1ブロックで全部落ちる)。能力が無く`BlockEntity`が`Container`なら`Containers.dropContents`と`clearContent()`。液体(`Capabilities.FluidHandler.BLOCK`の中身)と、燃えている燃料(`lit=true`の状態)は取り出せないので、撤去の**確認の文言で先に知らせる**(Task 30)。
  - `setBlock`は自然のブロックのアイテムを落とさない(`destroyBlock`ではない)ので、置換で壊した草・水・葉は増えない(F-7)。`Placement.blockEntityConfig`が空でなければ`INVALID`(許可するキーはS-5で決まるのでP10。P4の部品は使わない)。

- [ ] **Step 1: 失敗するテストを書く(純粋な部分)**

`SiteSurveyBuilderTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

class SiteSurveyBuilderTest {
    @Test
    void columnsAreHandedOutInBatchesAndTheSurveyMatchesAFlatOne() {
        Box box = new Box(0, 60, 0, 2, 70, 1);
        SiteSurveyBuilder b = new SiteSurveyBuilder(TestManifests.DIM, box);
        int n = 0;
        while (!b.done()) {
            List<int[]> cols = b.nextColumns(4);
            assertTrue(cols.size() <= 4);
            for (int[] c : cols) {
                b.column(c[0], c[1], 63, "minecraft:grass_block", false, false);
                n++;
            }
        }
        assertEquals(6, n);
        assertEquals(SiteSurvey.flat(TestManifests.DIM, box, 63, "minecraft:grass_block").digest(), b.build().digest());
    }

    @Test
    void anUnfinishedSurveyCannotBeBuilt() {
        SiteSurveyBuilder b = new SiteSurveyBuilder(TestManifests.DIM, new Box(0, 0, 0, 1, 1, 1));
        b.nextColumns(1);
        assertFalse(b.done());
        assertThrows(IllegalStateException.class, b::build);
    }
}
```

`VoxelGridFillerTest.java`:
```java
package io.github.khayashi4337.micradrone.build.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class VoxelGridFillerTest {
    @Test
    void fillsEveryCellOnceInBoundedBatches() {
        VoxelGridFiller f = new VoxelGridFiller(new Box(0, 0, 0, 3, 2, 1));
        int reads = 0;
        while (!f.done()) {
            for (IntPos p : f.nextBatch(5)) {
                f.set(p, (byte) (p.x() + p.y()));
                reads++;
            }
        }
        assertEquals(4 * 3 * 2, reads);
        VoxelClassGrid g = f.grid();
        assertEquals(5, g.classAt(new IntPos(3, 2, 1)));
    }

    @Test
    void aGridTooLargeForTheDesignIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new VoxelGridFiller(new Box(0, 0, 0, 1023, 1023, 1023)));
        assertEquals(1_572_864, VoxelClassGrid.MAX_CELLS);
    }
}
```

`BuildTagFilesTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The datapack tags are the runtime's allow lists (D-22, F-5); the pure builtin list is their single source. */
class BuildTagFilesTest {
    private static final Path TAGS = Path.of("src/main/resources/data/micradrone/tags/block");

    @SuppressWarnings("unchecked")
    private static List<Object> values(String file) throws IOException {
        Map<String, Object> tree = (Map<String, Object>) MiniJson.parse(Files.readString(TAGS.resolve(file), StandardCharsets.UTF_8));
        return (List<Object>) tree.get("values");
    }

    @Test
    void thePaletteTagIsExactlyTheBuiltinAllowList() throws IOException {
        TreeSet<String> tag = new TreeSet<>();
        for (Object v : values("palette_allowed.json")) {
            tag.add((String) v);
        }
        assertEquals(new TreeSet<>(BuiltinAllowList.ids()), tag);
        for (String forbidden : PlaceableBlockPolicy.ALWAYS_FORBIDDEN) {
            assertTrue(!tag.contains(forbidden), forbidden);
        }
    }

    @Test
    void terraformableGroundIsNaturalGroundOnly() throws IOException {
        List<Object> v = values("terraformable.json");
        assertTrue(v.contains("#minecraft:dirt"));
        assertTrue(v.contains("#minecraft:base_stone_overworld"));
        assertTrue(v.contains("#minecraft:sand"));
        assertTrue(v.contains("minecraft:gravel"));
        assertTrue(!v.contains("minecraft:bedrock"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.SiteSurveyBuilderTest" --tests "io.github.khayashi4337.micradrone.build.analyze.*" --tests "io.github.khayashi4337.micradrone.build.compile.BuildTagFilesTest" --console=plain`
Expected: FAIL(コンパイルエラーと、タグのファイルが無い)。

- [ ] **Step 3: 実装する**

`SiteSurveyBuilder.java`(純粋):
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.ArrayList;
import java.util.List;

/** Collects a survey column by column over several ticks, so no single tick scans the whole site. */
public final class SiteSurveyBuilder {
    public static final int SURVEY_COLUMNS_PER_TICK = 1_024;

    private final String dimension;
    private final Box box;
    private final int sx;
    private final int sz;
    private final int[][] ys;
    private final String[][] blocks;
    private final boolean[][] water;
    private final boolean[][] tree;
    private int next;
    private int filled;

    public SiteSurveyBuilder(String dimension, Box worldBounds) {
        this.dimension = dimension;
        this.box = worldBounds;
        this.sx = worldBounds.maxA() - worldBounds.minA() + 1;
        this.sz = worldBounds.maxC() - worldBounds.minC() + 1;
        this.ys = new int[sx][sz];
        this.blocks = new String[sx][sz];
        this.water = new boolean[sx][sz];
        this.tree = new boolean[sx][sz];
    }

    public List<int[]> nextColumns(int max) {
        List<int[]> out = new ArrayList<>();
        while (out.size() < max && next < sx * sz) {
            out.add(new int[]{box.minA() + next / sz, box.minC() + next % sz});
            next++;
        }
        return out;
    }

    public void column(int x, int z, int surfaceY, String surfaceBlock, boolean hasWater, boolean hasTree) {
        int i = x - box.minA();
        int j = z - box.minC();
        if (blocks[i][j] == null) {
            filled++;
        }
        ys[i][j] = surfaceY;
        blocks[i][j] = surfaceBlock;
        water[i][j] = hasWater;
        tree[i][j] = hasTree;
    }

    public boolean done() {
        return filled == sx * sz;
    }

    public SiteSurvey build() {
        if (!done()) {
            throw new IllegalStateException("the survey is not finished: " + filled + " of " + sx * sz + " columns");
        }
        return SiteSurvey.of(dimension, box, ys, blocks, water, tree);
    }
}
```
`VoxelClassGrid.java`:
```java
package io.github.khayashi4337.micradrone.build.analyze;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/** One byte of class per cell of a world box (design 01, section 8; F-21): about 1.5 MB at the most. */
public record VoxelClassGrid(Box worldBox, byte[] classes) {
    public static final int MAX_CELLS = 1_572_864;

    public VoxelClassGrid {
        Objects.requireNonNull(worldBox, "worldBox");
        if (worldBox.volume() > MAX_CELLS || classes.length != worldBox.volume()) {
            throw new IllegalArgumentException("a grid holds exactly the box's cells, at most " + MAX_CELLS);
        }
        classes = classes.clone();
    }

    static int index(Box b, IntPos p) {
        int sy = b.maxB() - b.minB() + 1;
        int sz = b.maxC() - b.minC() + 1;
        return ((p.x() - b.minA()) * sy + (p.y() - b.minB())) * sz + (p.z() - b.minC());
    }

    public byte classAt(IntPos p) {
        return classes[index(worldBox, p)];
    }
}
```
`VoxelGridFiller.java`(`VoxelClassGrid.index`と同じ順で位置を配る。`volume() > MAX_CELLS`なら`IllegalArgumentException`。`grid()`は全部埋まっていなければ`IllegalStateException`)。

`terraformable.json`:
```json
{
  "replace": false,
  "values": ["#minecraft:dirt", "#minecraft:base_stone_overworld", "#minecraft:sand", "minecraft:gravel", "minecraft:clay",
    "minecraft:snow_block", "minecraft:sandstone", "minecraft:red_sandstone", "#minecraft:terracotta"]
}
```
`palette_allowed.json`: `{"replace": false, "values": [<BuiltinAllowList.ids()を辞書順に全部>]}`。**手で書かない**: `BuiltinAllowList.ids()`を辞書順に出す1回限りのJUnitの補助(書き出した後は消す)か、`jshell`で`build/classes`を読んで出力し、`BuildTagFilesTest`で一致を確かめる。

`BlockStates.java`:
```java
package io.github.khayashi4337.micradrone.construction;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.TreeMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** BlockSpec (plain strings) to BlockState and back. The spec's text form is exactly the command syntax id[k=v,...]. */
public final class BlockStates {
    private BlockStates() {
    }

    public static BlockState toState(BlockSpec spec) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), spec.toString(), false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("not a block state of this game: " + spec + " (" + e.getMessage() + ")", e);
        }
    }

    public static BlockSpec toSpec(BlockState state) {
        TreeMap<String, String> props = new TreeMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), valueName(state, p));
        }
        return new BlockSpec(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }
}
```

`ServerStateReader.read`(唯一の読み取り): `!level.isLoaded(bp)`なら`WorldCell.unloaded()`。そうでなければ`BlockState st = level.getBlockState(bp)`、`BlockEntity be = level.getBlockEntity(bp)`から`CellTrait`を集める: `st.canBeReplaced()`→`REPLACEABLE`、`!st.getFluidState().isEmpty()`→`FLUID`、`st.is(BlockTags.LEAVES)`→`LEAVES`、`st.is(BuildTags.TERRAFORMABLE)`→`TERRAFORMABLE`、`st.getDestroySpeed(level, bp) < 0`→`UNBREAKABLE`、中身を持つ入れ物で、中身が空→`EMPTY_CONTAINER`(`level.getCapability(Capabilities.ItemHandler.BLOCK, bp, null)`があれば全スロットの`getStackInSlot(i).isEmpty()`、無ければ`be instanceof Container c && c.isEmpty()`。液体の能力があれば、全タンクが空であることも条件)。観測は`new ObservedBlock(BlockStates.toSpec(st), be != null, be == null ? "" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString())`。(既存の`BlockRangeDescription`は`SenseNames.simplify`で名前を短くするので、完全なIDが要るここでは共有しない。`BlockRangeDescription`は変更しない。)

`PlacementGuard.java`:
```java
package io.github.khayashi4337.micradrone.construction;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import io.github.khayashi4337.micradrone.construction.core.PlaceResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Every world write of the construction goes through here, with the owner as the acting player so protection mods can
 * refuse it (04 F-4 (c), S-9). A placement is captured like vanilla's BlockItem (CommonHooks.onPlaceItemIntoWorld): the
 * block is set while snapshots are captured, the place event decides, and only an accepted placement runs onPlace and the
 * neighbour updates. A removal posts the break event first, drops the contents it holds, and changes the block without
 * neighbour shape updates or drops; {@link #settle} runs the neighbour updates once a whole piece is removed.
 */
public final class PlacementGuard {
    private final MinecraftServer server;
    private final Function<UUID, String> ownerName;

    public PlacementGuard(MinecraftServer server, Function<UUID, String> ownerName) {
        this.server = server;
        this.ownerName = ownerName;
    }

    public ServerPlayer actor(ServerLevel level, UUID owner) {
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        return online != null ? online : FakePlayerFactory.get(level, new GameProfile(owner, ownerName.apply(owner)));
    }

    /** A removal changes one position quietly: no neighbour shape updates (16), no drops (32); settle() updates later. */
    static final int QUIET_REMOVAL_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Same recursion budget vanilla passes when it replays captured placements (CommonHooks.onPlaceItemIntoWorld). */
    static final int NEIGHBOUR_UPDATE_DEPTH = 512;

    public PlaceResult place(ServerLevel level, BlockPos pos, BlockState state, UUID owner) {
        level.captureBlockSnapshots = true;
        boolean set;
        try {
            set = level.setBlock(pos, state, Block.UPDATE_ALL);
        } finally {
            level.captureBlockSnapshots = false;
        }
        List<BlockSnapshot> snapshots = new ArrayList<>(level.capturedBlockSnapshots);
        level.capturedBlockSnapshots.clear();
        if (!set || snapshots.isEmpty()) {
            return PlaceResult.INVALID;
        }
        if (level.getBlockState(pos).getBlock() != state.getBlock()) {
            undo(level, snapshots);
            return PlaceResult.INVALID;
        }
        ServerPlayer actor = actor(level, owner);
        boolean cancelled = snapshots.size() > 1 ? EventHooks.onMultiBlockPlace(actor, snapshots, Direction.UP)
                : EventHooks.onBlockPlace(actor, snapshots.get(0), Direction.UP);
        if (cancelled) {
            undo(level, snapshots);
            return PlaceResult.DENIED;
        }
        for (BlockSnapshot snap : snapshots) {
            BlockState old = snap.getState();
            BlockState now = level.getBlockState(snap.getPos());
            now.onPlace(level, snap.getPos(), old, false);
            level.markAndNotifyBlock(snap.getPos(), level.getChunkAt(snap.getPos()), old, now, snap.getFlags(),
                    NEIGHBOUR_UPDATE_DEPTH);
        }
        return PlaceResult.PLACED;
    }

    private static void undo(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (BlockSnapshot snap : Lists.reverse(snapshots)) {
            level.restoringBlockSnapshots = true;
            try {
                snap.restore(snap.getFlags() | Block.UPDATE_CLIENTS);
            } finally {
                level.restoringBlockSnapshots = false;
            }
        }
    }

    public PlaceResult restore(ServerLevel level, BlockPos pos, BlockState state, UUID owner, boolean dropContentsFirst) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), actor(level, owner));
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            return PlaceResult.DENIED;
        }
        // the items are held, not dropped, until the block is really gone: a failed write puts them back
        List<ItemStack> held = dropContentsFirst ? takeContents(level, pos) : List.of();
        boolean set = level.setBlock(pos, state, QUIET_REMOVAL_FLAGS);
        if (!set || level.getBlockState(pos) != state) {
            putBack(level, pos, held);
            return PlaceResult.INVALID;
        }
        for (ItemStack stack : held) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
        return PlaceResult.PLACED;
    }

    /** Runs the neighbour updates the quiet removals skipped, once for each position of a finished piece. */
    public void settle(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            BlockState now = level.getBlockState(pos);
            level.updateNeighborsAt(pos, now.getBlock());
            now.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    /** Takes every item out through the item-handler capability (vanilla chests, Create's vault and depot alike). */
    static List<ItemStack> takeContents(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (items != null) {
            for (int slot = 0; slot < items.getSlots(); slot++) {
                for (ItemStack s = items.extractItem(slot, Integer.MAX_VALUE, false); !s.isEmpty();
                     s = items.extractItem(slot, Integer.MAX_VALUE, false)) {
                    out.add(s);
                }
            }
        } else if (level.getBlockEntity(pos) instanceof Container c) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack s = c.removeItemNoUpdate(slot);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Puts held items back into the block that stayed; what does not fit is dropped, never deleted. */
    static void putBack(ServerLevel level, BlockPos pos, List<ItemStack> held) {
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        for (ItemStack stack : held) {
            ItemStack rest = items == null ? stack : ItemHandlerHelper.insertItem(items, stack, false);
            if (!rest.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, rest);
            }
        }
    }
}
```
(置いた直後に比べるのはブロックの種類だけ。隣の影響で形の状態(階段の角・板ガラスのつながり)が変わるのは正常で、状態の違いはL7が`BlockMatch`で判定する。)

`ServerWorldPort`: `read`は`ServerStateReader.read(level, pos)`。`place`は`blockEntityConfig`が空でなければ`INVALID`、`BlockStates.toState`が`IllegalArgumentException`なら`INVALID`、それ以外は`guard.place`。`restore`は`guard.restore`。`settle`は`guard.settle`(位置を`BlockPos`にして)。`IntPos`→`BlockPos`の変換は`new BlockPos(p.x(), p.y(), p.z())`の1か所(`ServerWorldPort.toBlockPos`)にまとめる。

`ServerSurveyor.allLoaded(ServerLevel, Box) → boolean`(範囲の全チャンクについて`level.hasChunk(cx, cz)`)を、調査を**始める前に**確かめ、偽なら調査を始めない(提出の返事で「ちかくに いってね」)。`ServerSurveyor.step(level, builder, maxColumns)`は、`builder.nextColumns(maxColumns)`の各列を調べる(途中でチャンクが外れたら`false`を返し、提出は`E-SITE-BLOCKED#unloaded`で終わる)。列の中では`y = min(level.getHeight(WORLD_SURFACE, x, z) - 1, box.maxB())`から下へ、空気・`canBeReplaced()`(草花・雪の層)は飛ばし、液体は`water=true`で飛ばし、`BlockTags.LEAVES`・`BlockTags.LOGS`は`tree=true`で飛ばし、それ以外で止まる。`box.minB()`より下に行けば`surfaceY = box.minB() - 1`・`"minecraft:air"`。

`BuildPurityTest.java`の`ALLOWED`(Task 10で`Map.ofEntries`にした物)に`entry("build.analyze", Set.of("build.model")),`を`build.compile.gen`の行の前に足す。

`BuildTags.java`: `TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "terraformable"))`などの2つと、`policy()`=`BuiltInRegistries.BLOCK.getTag(PALETTE_ALLOWED)`(`Optional<HolderSet.Named<Block>>`。`Registry.java` 159行)で決める(Codexのレビュー2026-09-30で直した): **タグがある**なら、その中身のブロックIDで`new PlaceableBlockPolicy(ids)`(**空のタグも、そのまま「何も置けない」方針**: サーバーの管理者がわざと空にした物を、黙って組み込みの表に戻さない)。**タグが無い**(データパックが読めていない)ときだけ、組み込みの表`PlaceableBlockPolicy.builtin()`を使い、`MicraDrone.LOGGER.warn`で知らせる(この表は同梱のタグの中身とまったく同じ。`BuildTagFilesTest`が一致を検査する。だから「タグが無い」ことで許される物は増えない)。純粋な判断`PalettePolicy.from(Optional<Set<String>> tagIds) → PlaceableBlockPolicy`を`build.compile`に置き、`PalettePolicyTest`で「空のタグ=空の方針」「無いタグ=組み込み」を確かめる。

`PalettePolicy.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import java.util.Optional;
import java.util.Set;

/**
 * The runtime palette (D-22): the palette_allowed tag when the datapack defines it, even empty (an administrator may
 * forbid everything on purpose); the built-in table only when the tag is absent (it equals the shipped tag, see
 * BuildTagFilesTest, so an absent datapack never allows more).
 */
public final class PalettePolicy {
    private PalettePolicy() {
    }

    public static PlaceableBlockPolicy from(Optional<Set<String>> tagIds) {
        return tagIds.map(PlaceableBlockPolicy::new).orElseGet(PlaceableBlockPolicy::builtin);
    }
}
```
`PalettePolicyTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PalettePolicyTest {
    @Test
    void anEmptyTagForbidsEverythingAndOnlyAnAbsentTagFallsBackToTheShippedTable() {
        assertTrue(PalettePolicy.from(Optional.of(Set.of())).allowed().isEmpty(), "an empty tag is a policy, not a missing one");
        assertEquals(Set.of("minecraft:stone"), PalettePolicy.from(Optional.of(Set.of("minecraft:stone"))).allowed());
        assertEquals(PlaceableBlockPolicy.builtin().allowed(), PalettePolicy.from(Optional.empty()).allowed());
    }
}
```

- [ ] **Step 4: テストとコンパイルを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`OptionalModBoundaryTest`・`BuildPurityTest`を含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/java/io/github/khayashi4337/micradrone/build の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/resources/data/micradrone/tags の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/build の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 世界の読み書きのアダプタ(ServerWorldPort・PlacementGuard・ServerStateReader・ServerSurveyor)と、整地・素材のタグを追加(自然言語→工場建設 P4 Task 15)

Implemented-by: SWE-2 via Devin CLI
```

**実機での確認**: このタスクのアダプタは、Task 19のシナリオ`hut-golden`・`hut-here`(小屋が建ち、読み戻したブロックが施工リストと全数一致する)と、Task 26・28・31の`s9-protection-online`・`-rollback`・`-offline`(保護イベント)と、Task 28・29の落ちたアイテム0の確認(撤去の書き方)で自動に確かめる。

---

### Task 16: アダプタ — 設定(`ConstructionConfig`)と施工ランタイム(`ConstructionRuntime`)、状態のJSON(`JobViews`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/{ConstructionConfig,ConstructionRuntime,RuntimeJobWorld,ServerMessages}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobViews,SubmitOutcome}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/MicraDrone.java`(設定の登録と、ランタイムのイベントの登録。既存の登録は変えない)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/JobViewsTest.java`

**Interfaces:**
- Consumes: Task 3〜15の型。NeoForge: `ModConfigSpec.Builder`(`defineInRange(String, int, int, int)`・`defineInRange(String, double, double, double)`・`define(String, boolean)`・`defineEnum(String, V)`・`build()`)、`ModContainer.registerConfig(ModConfig.Type.SERVER, IConfigSpec)`(`ModConfig.Type.SERVER`の既定の名前は`micradrone-server.toml`。F-15)、`ServerStartedEvent`・`ServerStoppingEvent`・`ServerTickEvent.Pre`・`PlayerEvent.PlayerLoggedOutEvent`、`MinecraftServer.getAverageTickTimeNanos()`・`getTickCount()`・`execute(Runnable)`
- Produces:
  - `ConstructionConfig`: `ModConfigSpec SPEC`と値(カッコ内は既定・出どころ): `safety.maxPlacements`(20,000・F-5)、`safety.maxSizeX/Y/Z`(128/96/128・F-5)、`safety.opMaxPlacements`・`safety.opMaxSizeX/Y/Z`(同じ値。F-5の「OPは設定で緩和」)、`materials.policy`(`AUTO`・F-7。`AUTO`はゲームモードで決める)、`budget.maxRunningJobs`(4)・`budget.maxPlacementsPerTick`(32)・`budget.fastPlacementsPerTick`(16)・`budget.fastStructure`(`true`)・`budget.slowdownAboveMspt`(45.0)・`budget.recoverBelowMspt`(40.0)、`permissions.level`(`ALL`・F-4(d))・`permissions.largeJobPlacements`(20,000。これ以上はOPだけ。設計に数値なし: 既定では上限と同じで、実質OFF)、`chunks.continueWhileOffline`(`false`・F-13)、`claims.maxPerOwner`(8・F-4)。`static BudgetConfig budget()`・`static SafetyLimits limits(ServerLevel, boolean op)`
  - `record SubmitOutcome(String state /*WORKING|OFFERED|FAILED*/, PendingApproval pending, List<Issue> issues, ReplacementSummary replacements, long etaTicks)`(cc。`WORKING`は調査・コンパイル・読み取りの途中)
  - `JobViews`(cc、純粋。devkit・F-8の問い合わせ・コマンドが同じJSONを使う): `static Map<String,Object> statusTree(JobStatus)`、`jobsTree(List<JobStatus>)`、`submitTree(SubmitOutcome)`、`issueTree(Issue)`
  - `ConstructionRuntime`: `static Optional<ConstructionRuntime> of(MinecraftServer)`、`void submit(ServerPlayer, PlanSubmission)`(非同期: 調査(tickに分ける)→ワーカーでコンパイル→施工リストの位置を読む(tickに分ける)→安全枠→`ApprovalDesk.offer`)、`SubmitOutcome lastSubmit(UUID)`、`ApprovalDecision approve(ServerPlayer, String hash, Confirmations, List<AcceptedRisk>)`(承認できたら`JobService.admitApproved(job, manifest, nodeTypes, JobProgram.build(manifest), operatingBox)`)、`ControlResult cancel(UUID requester, boolean op, String jobId)`・`resume(UUID, boolean, String, boolean skip)`、`Map<String,Object> statusTree(String jobId)`・`jobsTree()`・`manifestTree(String hash)`、`JobService jobs()`、`String registryVersion()`、`String nextJobId()`(`"job-" + overworld().getGameTime() + "-" + その起動での通し番号`。ゲーム内の時刻は保存され、再起動しても戻らないので、前の起動のIDとぶつからない。`ConstructionJob.ID_PATTERN`に合う。`approve`の`Supplier<String> newJobId`はこれ。P4レビューB-6: Task 21より前に要る)、`long lastTickWorkNanos()`(直前のtickで、このランタイムの処理にかかった実時間。`perfTree()`と`MsptStats`はTask 33で足す)
  - `RuntimeJobWorld implements JobWorld`: `world(dim)`=`ServerWorldPort`(ディメンションのID→`server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)))`)、`ownerOnline`=`server.getPlayerList().getPlayer(uuid) != null`、`materials`=クリエイティブは`MaterialPort.FREE`、サバイバルはTask 27までは`RuntimeJobWorld.NO_MATERIALS`(Task 9の`MaterialPort`の形で、`stocks`は空=何も持たない、`giveTarget`は`INVENTORY`、`apply`は`false`、`persist`は何もしない。サバイバルの在庫はTask 27の`InventoryMaterials`が置き換える。**Task 27までは、サバイバルの方針のジョブは材料不足で止まる**。自動確認のTask 19〜21はクリエイティブで行う)、`mayRunWithoutOwner`=Task 31までは常に`false`(所有者がいなければ止まる)
  - `ServerMessages`: `Component issueLine(Issue)`(`ChildMessages.issueLine`の訳した文だけ。**問題のコードは付けない**: コードとIDは`MicraDrone.LOGGER.info`でサーバーのログに1行ずつ書き、`/micradrone build status <jobId> --debug`(所有者かOP。Task 34)で見せる)、`Component status(JobStatus)`、`Component rejection(ApprovalRejection, List<Issue>)`など、`ChildMessages`のキーからだけ文を作る

- 1tickの順序(サーバーのメインスレッド、**`ServerTickEvent.Pre`**): (0)`long t0 = System.nanoTime()`、(1)`ApprovalDesk.expire`・`SurveyCache.expire`、(2)進行中の提出(調査・位置の読み取り)を、それぞれ`SiteSurveyBuilder.SURVEY_COLUMNS_PER_TICK`列・`SnapshotCollector.DEFAULT_READS_PER_TICK`個まで進める、(3)`JobService.tick(new TickInput(server.getTickCount(), server.getAverageTickTimeNanos() / NANOS_PER_MILLI), world)`、(4)更新を所有者に知らせる(状態が変わった時だけ。`ServerMessages`)、(5)演出(Task 20)。`NANOS_PER_MILLI = 1_000_000.0`。(6)`lastTickWorkNanos = System.nanoTime() - t0`(Task 33で`MsptStats`に入れる)。**ワールドへの書き込みは(3)の中だけ**(D-1)。
  - **なぜ`Pre`か**(P4レビューD-1): `MinecraftServer.tickServer`は、tickの開始時刻を取った後に`ServerTickEvent.Pre`を投げ(`MinecraftServer.java` 928〜930行)、ワールドを進め、その後で経過時間を`tickTimesNanos`に足し(950〜955行)、最後に`ServerTickEvent.Post`を投げる(958行)。`Post`で働くと、その時間は`getAverageTickTimeNanos()`に入らず、自動減速(45ms)も性能の線(完了条件10)も、施工の重さを見ないで判定してしまう。`Pre`なら測られる窓の中に入る。さらに、ランタイム自身の処理時間を`System.nanoTime()`で測り、Task 33の`MsptStats`に「施工の仕事の時間」として別に記録する(サーバー全体の平均と、施工の分を分けて見られる)。Task 35で`04` F-2に書く。
- 登録: `MicraDrone`のコンストラクタで`modContainer.registerConfig(ModConfig.Type.SERVER, ConstructionConfig.SPEC)`と`NeoForge.EVENT_BUS.register(ConstructionRuntime.Events.class)`(静的な`@SubscribeEvent`を集めた入れ子のクラス。**ゲームのバスの事件だけ**を入れる。`RegisterTicketControllersEvent`のようなmodのバスの事件(`IModBusEvent`)はここに入れない。ゲームのバスは`IModBusEvent`を受け付けない(`NeoForge.java` 17〜20行)。Task 31を見よ)。`ServerStartedEvent`でランタイムを作り`MicraDrone.LOGGER.info("MicraDrone: construction runtime ready")`、`ServerStoppingEvent`でワーカーを閉じる。ログアウトで`ApprovalDesk.dropOwner`。

- [ ] **Step 1: 失敗するテストを書く(`JobViews`)**

`JobViewsTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobViewsTest {
    @Test
    void aStatusIsPlainJsonWithEveryField() {
        JobStatus st = new JobStatus("job-1", new UUID(0, 1), JobKind.BUILD, JobState.PAUSED, PauseReason.MATERIALS_MISSING, 3, 25, 0,
                1, 2, "", "claim-job-1", "minecraft:overworld");
        Map<String, Object> t = JobViews.statusTree(st);
        assertEquals("PAUSED", t.get("state"));
        assertEquals("MATERIALS_MISSING", t.get("pause"));
        assertEquals(3, ((Number) t.get("cursor")).intValue());
        assertEquals(25, ((Number) t.get("total")).intValue());
        String json = MiniJson.write(t);
        assertEquals(t.get("jobId"), ((Map<?, ?>) MiniJson.parse(json)).get("jobId"), "round-trips through the JSON writer");
    }

    @Test
    void aRunningJobHasNoPauseAndIssuesKeepTheirIds() {
        JobStatus st = new JobStatus("job-1", new UUID(0, 1), JobKind.BUILD, JobState.RUNNING, null, 3, 25, 0, 0, 0, "", "c",
                "minecraft:overworld");
        assertNull(JobViews.statusTree(st).get("pause"));
        Issue i = Issue.of(IssueCode.E_SITE_BLOCKED, "unloaded", List.of("manifest"), "x");
        Map<String, Object> it = JobViews.issueTree(i);
        assertEquals("E-SITE-BLOCKED:manifest#unloaded", it.get("id"));
        assertEquals("E-SITE-BLOCKED", it.get("code"));
        assertEquals(false, it.get("acceptable"));
        assertEquals(ChildMessages.issue(IssueCode.E_SITE_BLOCKED), it.get("childKey"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.JobViewsTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`JobViews`(純粋): `statusTree`はキー`jobId`・`owner`(UUIDの文字列)・`kind`・`state`(`name()`)・`pause`(無ければ`null`)・`cursor`・`total`・`repairRound`・`conflicts`・`skipped`・`lastError`・`claimId`・`dimension`を持つ`LinkedHashMap`。`issueTree`は`id`・`code`(`label()`)・`severity`・`acceptable`・`subjects`・`message`・`data`・`childKey`(`ChildMessages.issue`)。`submitTree`は`state`・`hash`(無ければ`null`)・`dimension`・`expiresTick`・`surveyDigest`・`issues`・`replacements`(`fluids`・`leaves`・`emptyContainers`・`terrainCut`・`terrainFill`・`destructiveSample`=`[[x,y,z],...]`)・`etaTicks`。数は`Long`で入れる(`MiniJson`・`CanonicalJson`の既存の作法)。

`ConstructionConfig`・`ConstructionRuntime`・`RuntimeJobWorld`・`ServerMessages`は上の「Produces」と「1tickの順序」のとおり。`ConstructionRuntime.submit`の流れ:
1. 提出者ごとに同時1件(`ServerWorkerPool.busy`か、進行中の提出があれば`SUBMIT_BUSY`)。
2. 計画の`site`が無ければ、そのまま`PlanCompilation`に任せて`E-SITE-MISSING`を返す。`site.dimension`が提出者の今いるディメンションと違えば、`DIMENSION_MISMATCH`の文で断る(D-27)。
3. `site`の`localBounds`と`frame`から世界の箱を出し(`OperatingBox`と同じ角2つの写し方)、範囲の全チャンクが読み込まれていなければ`E-SITE-BLOCKED#unloaded`で断る。`site.terrainDigest`が空でなく`SurveyCache`にあれば、その調査を使う(F-3: サーバーが発行して固定した調査)。無ければ`SiteSurveyBuilder`で調査を始める(tickに分ける)。
4. 調査ができたら`SurveyCache.pin`し、**メインスレッドで**`PlaceableBlockPolicy policy = BuildTags.policy()`を作ってから(レジストリとタグはメインスレッドで読む。作った方針は変わらない値)、`ServerWorkerPool.submit(owner, () -> PlanCompilation.compile(sub, registry, policy, survey, BUNDLED_TEMPLATE_HASHES))`(ワーカーの中で`BuildTags`・`BuiltInRegistries`を触らない。Codexのレビュー2026-09-30)。`BUNDLED_TEMPLATE_HASHES`はP4では空の`Map`(同梱テンプレートはP11)。
5. 結果(メインスレッド)が`manifest == null`なら`FAILED`と`Issue`を返す。そうでなければ施工リストの全位置を`SnapshotCollector`でtickに分けて`ServerStateReader.read`し、`PlacementSurvey`を作る。
6. `SafetyEnvelope.check(manifest, terrain, survey, ConstructionConfig.limits(level, op), policy, itemCatalog, ownPlaced)`(メインスレッド。`policy`は4で作った物。`itemCatalog`=`id -> BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))`、`ownPlaced`=`BUILD`も`MODIFY`も`pos -> Optional.empty()`。`MODIFY`の調査は、設置の記録に載る位置を施工前のブロックとして読む(Task 29の`ServerSurveyor`)ので、登録簿の記録を自分の物として渡す必要が無く、渡すと「同じIDで状態の違うブロックを自分の物として置き直す」修復だけの規則が承認の前に入り込む。Task 9の`ownedFor`と同じ)。
7. `permissions.largeJobPlacements`以上なら、提出者がOPでなければ`E-PERMISSION-DENIED`(Task 26で足すコード。Task 26までは、この検査の行は無い)。
8. `ApprovalDesk.offer`し、`SubmitOutcome(OFFERED, pending, issues, replacements, ConstructionBudget.etaTicks(fast, slow, budget))`を所有者に知らせる(`SUBMIT_OK`と、確認が要れば`TERRAIN_CONFIRM`・`DESTRUCTIVE_CONFIRM`、問題は`issueLine`で1行ずつ)。

- [ ] **Step 4: テストとコンパイルを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: 起動の確認(ログだけ。ゲーム窓に入力しない)** — 担当: コントローラ(Claude)。Devinはこの手順をしない(プロセスの起動と後始末が複数のコマンドになるため)

P3 Task 23 Step 3と同じ手順で、`./gradlew runClient --console=plain`をバックグラウンドで起動し(起動前の`java.exe`を控える)、ログに`MicraDrone: construction runtime ready`が出る(世界に入らない限りサーバーは起動しないので、この行はTask 19の自動確認で見る)か、少なくとも`crash-reports`に新しいファイルが無く、`micradrone-server.toml`の読み込みでエラーが出ないことを確かめ、この起動で増えたプロセスだけを終了する。

- [ ] **Step 6: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/main/java/io/github/khayashi4337/micradrone/MicraDrone.java
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/JobViewsTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: サーバー設定と施工ランタイム(提出の流れ・tickの駆動・状態のJSON)を追加(自然言語→工場建設 P4 Task 16)

Implemented-by: SWE-2 via Devin CLI
```

**実機での確認**: Task 19の`hut-golden`(提出→承認→`VERIFIED`)、Task 33の`perf-20000`(ワーカーで計算し、メインスレッドを止めない)。

---

### Task 17: デバッグコマンド(`/micradrone build ...`)と、同梱の小屋の見本

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{PlanSource,PlanFileReader,PlanFileOpener,SiteRelocation,ApproveArgs}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/BuildCommands.java`、`src/main/resources/data/micradrone/build_samples/hut.json`(`src/test/resources/build/golden/hut.patch.json`のバイト単位の写し)
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(`Events`に`RegisterCommandsEvent`の登録)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{PlanSourceTest,PlanFileReaderTest,PlanFileOpenerTest,SiteRelocationTest,ApproveArgsTest,SampleHutTest}.java`

**Interfaces:**
- Consumes: 既存の`PlanJson.patchFromTree`・`planFromTree`・`PlanPatcher.apply`、`MiniJson.parse`、Task 12の`PlanCompilation`
- Produces:
  - `PlanSource`: `SAMPLE_PREFIX = "sample:"`、`PLANS_DIR = "micradrone/plans"`(ゲームのフォルダの下。F-22: 外のファイルは読まない)、`MAX_PLAN_BYTES = 2 * 1024 * 1024`(F-3の全体の上限と同じ)、`SAMPLES = Map.of("hut", "/data/micradrone/build_samples/hut.json")`、`sealed interface Resolved { Sample(String resource), FileAt(Path path), Invalid(String reason) }`、`static Resolved resolve(String source, Path gameDir)`
  - `PlanFileReader`: `record Result(PlanSubmission submission, List<Issue> issues, String error)`、`static Result read(String json, PartTypeRegistry registry)`(`"ops"`があれば`PlanPatch`として`SemanticPlan.empty(patchId)`に適用。無ければ`SemanticPlan`として読む)
  - `SiteRelocation.relocate(SemanticPlan, String dimension, IntPos origin, Facing facing) → SemanticPlan`(敷地の原点・向き・ディメンションを差し替え、`terrainDigest`は空にする)
  - `ApproveArgs`: `record Parsed(Confirmations confirmations, List<AcceptedRisk> risks, String error)`、`static Parsed parse(String flags)`(`confirm-terraform`・`confirm-destructive`・`accept=<issueId>[,<issueId>...]`。知らない語は`error`)
  - コマンド(Brigadier。`Commands.literal("micradrone").then(literal("build")...)`): `submit <source:greedy>`、`submit-here <source:greedy>`(提出者の足元のブロックを原点、向いている水平方向を`facing`に)、`approve <hash:word> [flags:greedy]`、`status [jobId]`、`list`、`cancel <jobId>`、`resume <jobId> [skip-conflicts]`、`check <jobId>`(今のカーソルまでを`CompareScope.UpToCursor`で比べ、ずれの数だけを返す。世界は変えない)。後のタスクで`verify`(Task 21)・`recover`(Task 25)・`supply`(Task 27)・`rollback`(Task 28)・`modify`(Task 29)・`perf`(Task 33)を足す。`submit`・`approve`は`permissions.level`の水準(`ALL`=`Commands.LEVEL_ALL`、`OP`=`Commands.LEVEL_GAMEMASTERS`)、`cancel`・`resume`は所有者かOP(`JobService`が判定。D-12)。

- [ ] **Step 1: 失敗するテストを書く**

`PlanSourceTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlanSourceTest {
    private static final Path GAME = Path.of("C:/game");

    @Test
    void samplesAndFilesUnderThePlansFolderResolve() {
        assertEquals(new PlanSource.Sample("/data/micradrone/build_samples/hut.json"), PlanSource.resolve("sample:hut", GAME));
        PlanSource.FileAt f = assertInstanceOf(PlanSource.FileAt.class, PlanSource.resolve("my/hut2.json", GAME));
        assertEquals(GAME.resolve("micradrone/plans/my/hut2.json").normalize(), f.path());
    }

    @Test
    void anythingOutsideThePlansFolderIsRefused() {
        for (String bad : new String[]{"../secrets.json", "C:/x.json", "/etc/passwd", "a/../../b.json", "hut.txt", "",
                "sample:castle", "a\\b.json"}) {
            assertInstanceOf(PlanSource.Invalid.class, PlanSource.resolve(bad, GAME), bad);
        }
    }
}
```

`PlanFileReaderTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import org.junit.jupiter.api.Test;

class PlanFileReaderTest {
    @Test
    void aPatchAndTheWholePlanReadToTheSamePlan() {
        String patch = SampleHutTest.resource("/build/golden/hut.patch.json");
        PlanFileReader.Result a = PlanFileReader.read(patch, BuildingParts.registry());
        assertNull(a.error());
        String whole = MiniJson.write(PlanJson.toTree(TestManifests.hutPlan()));
        PlanFileReader.Result b = PlanFileReader.read(whole, BuildingParts.registry());
        assertNull(b.error());
        assertEquals(a.submission().plan().contentHash(), b.submission().plan().contentHash());
        assertEquals(JobKind.BUILD, a.submission().kind());
    }

    @Test
    void brokenJsonIsAnErrorNotACrash() {
        PlanFileReader.Result r = PlanFileReader.read("{\"ops\": [ {\"op\": \"nope\"} ]", BuildingParts.registry());
        assertNotNull(r.error());
        assertNull(r.submission());
    }
}
```

`SiteRelocationTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import org.junit.jupiter.api.Test;

class SiteRelocationTest {
    @Test
    void onlyTheSiteMovesAndTheDesignStaysTheSame() {
        SemanticPlan hut = TestManifests.hutPlan();
        SemanticPlan moved = SiteRelocation.relocate(hut, "minecraft:overworld", new IntPos(5, -60, 7), Facing.EAST);
        assertEquals(new IntPos(5, -60, 7), moved.site().frame().origin());
        assertEquals(Facing.EAST, moved.site().frame().facing());
        assertEquals(hut.site().localBounds(), moved.site().localBounds());
        assertEquals("", moved.site().terrainDigest());
        assertEquals(hut.nodes(), moved.nodes());
    }
}
```

`ApproveArgsTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApproveArgsTest {
    @Test
    void flagsAndAcceptedRisksAreParsed() {
        ApproveArgs.Parsed p = ApproveArgs.parse("confirm-terraform accept=W-UNMODELED:press,W-STRESS-MARGIN:net");
        assertNull(p.error());
        assertEquals(new Confirmations(true, false), p.confirmations());
        assertEquals(List.of(new AcceptedRisk("W-UNMODELED:press", ""), new AcceptedRisk("W-STRESS-MARGIN:net", "")), p.risks());
        assertEquals(Confirmations.NONE, ApproveArgs.parse("").confirmations());
        assertNotNull(ApproveArgs.parse("confirm-everything").error());
    }
}
```

`SampleHutTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shipped sample is the P3 golden hut: the owner's first build is the one the golden file pins. */
class SampleHutTest {
    static String resource(String path) {
        try (InputStream in = SampleHutTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theSampleIsTheGoldenPatchByteForByte() {
        assertEquals(resource("/build/golden/hut.patch.json"), resource("/data/micradrone/build_samples/hut.json"));
    }

    @Test
    void theSampleCompilesOnTheServerPathToTheGoldenHash() {
        String golden = resource("/build/golden/hut.manifest.txt").lines().findFirst().orElseThrow().substring("hash ".length());
        PlanFileReader.Result r = PlanFileReader.read(resource(PlanSource.SAMPLES.get("hut")), BuildingParts.registry());
        SiteSurvey air = SiteSurvey.air(TestManifests.DIM, TestManifests.hut().worldBounds());
        CompiledPlan c = PlanCompilation.compile(r.submission(), BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air,
                Map.of());
        assertEquals(golden, c.manifest().hash());
        assertEquals(238, c.manifest().placements().size());
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --console=plain`
Expected: FAIL(コンパイルエラーと、見本のファイルが無い)。

- [ ] **Step 3: 実装する**

見本: `cp src/test/resources/build/golden/hut.patch.json src/main/resources/data/micradrone/build_samples/hut.json`(バイト単位の写し。`SampleHutTest.theSampleIsTheGoldenPatchByteForByte`が一致を守る)。

`PlanSource.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a debug-command plan comes from (F-22): a bundled sample, or a .json file under the game's micradrone/plans
 * folder. Absolute paths, drive letters, backslashes and any climb out of the folder are refused.
 */
public final class PlanSource {
    public static final String SAMPLE_PREFIX = "sample:";
    public static final String PLANS_DIR = "micradrone/plans";
    public static final int MAX_PLAN_BYTES = 2 * 1024 * 1024;
    public static final Map<String, String> SAMPLES = Map.of("hut", "/data/micradrone/build_samples/hut.json");
    private static final Pattern RELATIVE_JSON = Pattern.compile("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*\\.json");
    private static final int MAX_SOURCE_CHARS = 128;

    public sealed interface Resolved {
    }

    public record Sample(String resource) implements Resolved {
    }

    public record FileAt(Path path) implements Resolved {
    }

    public record Invalid(String reason) implements Resolved {
    }

    private PlanSource() {
    }

    public static Resolved resolve(String source, Path gameDir) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS) {
            return new Invalid("empty or too long");
        }
        if (source.startsWith(SAMPLE_PREFIX)) {
            String resource = SAMPLES.get(source.substring(SAMPLE_PREFIX.length()));
            return resource == null ? new Invalid("no such sample: " + source) : new Sample(resource);
        }
        if (!RELATIVE_JSON.matcher(source).matches()) {
            return new Invalid("only a relative .json path of letters, digits, - and _ is allowed");
        }
        Path dir = gameDir.resolve(PLANS_DIR).normalize();
        Path file = dir.resolve(source).normalize();
        return file.startsWith(dir) ? new FileAt(file) : new Invalid("outside " + PLANS_DIR);
    }
}
```
(`..`は正規表現の`[A-Za-z0-9_\-]+`に合わないので、`a/../../b.json`も拒否される。`normalize`と`startsWith`は二重の守り。)

`PlanFileReader.read`: `MiniJson.parse`(失敗→`error`)。木が`Map`で`PlanJson.KEY_OPS`を持てば`PlanJson.patchFromTree`→`new PlanPatcher(registry, TemplateBundle.EMPTY).apply(SemanticPlan.empty(patch.patchId()), patch)`、`ok()`でなければ`issues`を返して`submission`は`null`。持たなければ`PlanJson.planFromTree`で読んでから`new PlanPatcher(registry, TemplateBundle.EMPTY).normalize(plan)`でパラメータの型を確定する(`01` 2節: JSONからは型を推測して読むので、確定しないと同じ設計でも`contentHash`が変わる)。`ok()`でなければ`issues`。`PlanJsonException`・`IllegalArgumentException`は`error`に(メッセージつき)。成功は`new PlanSubmission(plan, TemplateBundle.EMPTY, JobKind.BUILD, null, null)`。

`SiteRelocation.relocate`: `new Site(dimension, new BuildFrame(origin, facing), plan.site().localBounds(), "", plan.site().claimId())`に差し替えた`SemanticPlan`(他の欄はそのまま)。`site`が`null`なら`IllegalArgumentException`。

`ApproveArgs.parse`: 空白で区切り、`confirm-terraform`・`confirm-destructive`・`accept=`で始まる語だけを受け付ける。`accept=`の値は`,`で区切った`Issue`のID(`AcceptedRisk(id, "")`)。

`PlanFileOpener`(Codexのレビュー2026-09-30: `normalize`と`startsWith`だけでは、計画のフォルダの中に置いたシンボリックリンクや**Windowsのジャンクション**を通って外のファイルが読める)。フォルダからファイルまでの**どの要素も**リンク・ジャンクション・そのほかの再解析ポイント(Javaは`BasicFileAttributes.isOther()`と報告する)でないこと、実際のパス(`toRealPath`)がフォルダの実際のパスの下にあること、`LinkOption.NOFOLLOW_LINKS`で開くことを、すべて確かめる:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Reads a plan file from the plans folder only (F-22). A normalized path can still leave the folder through a link, so
 * every element from the folder down to the file must be a plain directory or file (no symbolic link, no Windows junction
 * or other reparse point, which Java reports as "other"), the real path must stay under the folder's real path, and the
 * file is opened without following links. File access is kept here, apart from the pure {@link PlanSource} checks.
 */
public final class PlanFileOpener {
    /** What the checks need to know about a path; the default reads the real file system. */
    public interface PathFacts {
        /** A symbolic link, a junction or another reparse point (anything but a plain file or directory). */
        boolean isLinkLike(Path p) throws IOException;

        Path realPath(Path p) throws IOException;
    }

    public static final PathFacts NIO = new PathFacts() {
        @Override
        public boolean isLinkLike(Path p) throws IOException {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isSymbolicLink() || a.isOther();
        }

        @Override
        public Path realPath(Path p) throws IOException {
            return p.toRealPath();
        }
    };

    private PlanFileOpener() {
    }

    /** Empty when the path is safe to open; otherwise why not. */
    public static Optional<String> check(Path plansDir, Path file, PathFacts facts) throws IOException {
        Path dir = plansDir.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(dir) || target.equals(dir)) {
            return Optional.of("outside the plans folder");
        }
        for (Path p = target; p != null && p.startsWith(dir); p = p.getParent()) {
            if (facts.isLinkLike(p)) {
                return Optional.of("a link or junction on the way: " + dir.relativize(p));
            }
        }
        if (!facts.realPath(target).startsWith(facts.realPath(dir))) {
            return Optional.of("the real file is outside the plans folder");
        }
        return Optional.empty();
    }

    /** The file's bytes, at most {@code maxBytes}; refused with an IOException when unsafe or too big. */
    public static byte[] read(Path plansDir, Path file, int maxBytes) throws IOException {
        Optional<String> why = check(plansDir, file, NIO);
        if (why.isPresent()) {
            throw new IOException(why.get());
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (ch.size() > maxBytes) {
                throw new IOException("the plan is larger than " + maxBytes + " bytes");
            }
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // keep reading until the file is in
            }
            return buf.array();
        }
    }
}
```
`PlanFileOpenerTest.java`(偽のファイルシステムで規則を確かめ、この機械でリンクかジャンクションを作れる時は本物でも確かめる。作れなければ`Assumptions`で飛ばす):
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PlanFileOpenerTest {
    private static final Path DIR = Path.of("/game/micradrone/plans").toAbsolutePath();

    /** A fake file system: the listed paths are links, and one path's real location is elsewhere. */
    private static PlanFileOpener.PathFacts fake(Set<Path> links, Path redirectFrom, Path redirectTo) {
        return new PlanFileOpener.PathFacts() {
            @Override
            public boolean isLinkLike(Path p) {
                return links.contains(p);
            }

            @Override
            public Path realPath(Path p) {
                return p.equals(redirectFrom) ? redirectTo : p;
            }
        };
    }

    @Test
    void aLinkOrJunctionAnywhereOnTheWayIsRefused() throws IOException {
        Path inner = DIR.resolve("my");
        Path file = inner.resolve("hut.json");
        assertEquals(Optional.empty(), PlanFileOpener.check(DIR, file, fake(Set.of(), null, null)));
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(inner), null, null)).orElseThrow().contains("junction"),
                "a folder in the plans folder that is a junction to C:\\\\ is caught");
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(file), null, null)).isPresent(), "the file itself a link");
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(), file, Path.of("/etc/passwd").toAbsolutePath()))
                .orElseThrow().contains("real file"), "a real path that leaves the folder");
        assertTrue(PlanFileOpener.check(DIR, DIR.resolve("../x.json"), fake(Set.of(), null, null)).isPresent());
    }

    @Test
    void aRealLinkOrJunctionIsRefusedWhenOneCanBeMadeHere() throws Exception {
        Path root = Files.createTempDirectory("plans-test");
        Path plans = Files.createDirectories(root.resolve("micradrone/plans"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(plans.resolve("ok.json"), "{\"a\":1}", StandardCharsets.UTF_8);
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), PlanFileOpener.read(plans, plans.resolve("ok.json"), 100));
        Path link = plans.resolve("sneaky");
        boolean made;
        try {
            Files.createSymbolicLink(link, outside);
            made = true;
        } catch (IOException | UnsupportedOperationException noSymlink) {
            // Windows without the symlink privilege: a junction needs none
            made = System.getProperty("os.name").startsWith("Windows")
                    && new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).start()
                    .waitFor(10, TimeUnit.SECONDS) && Files.exists(link);
        }
        Assumptions.assumeTrue(made, "neither a symbolic link nor a junction could be made here; the fake test covers it");
        assertTrue(Files.exists(link.resolve("secret.json")), "the link really reaches outside");
        assertThrows(IOException.class, () -> PlanFileOpener.read(plans, link.resolve("secret.json"), 100));
        assertThrows(IOException.class, () -> PlanFileOpener.read(plans, plans.resolve("ok.json"), 3), "size limit");
    }
}
```

`BuildCommands.register(CommandDispatcher<CommandSourceStack>)`: 上の各コマンド。`submit`は`PlanSource.resolve(source, server.getServerDirectory())`→`Sample`はクラスパスから、`FileAt`は`PlanFileOpener.read(plansDir, path, MAX_PLAN_BYTES)`で読む(下)→`PlanFileReader.read`→`ConstructionRuntime.submit`。`submit-here`は`SiteRelocation.relocate(plan, player.level().dimension().location().toString(), 足元のIntPos, player.getDirection()の水平方向)`を挟む。返事はすべて`ServerMessages`(翻訳キー)。

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件(`SampleHutTest`で、見本がサーバーの経路で金のファイルのハッシュになることを含む)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/resources/data/micradrone/build_samples の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: デバッグコマンド(/micradrone build submit・approve・status・cancel・resume・check)と同梱の小屋の見本を追加(自然言語→工場建設 P4 Task 17)

Implemented-by: SWE-2 via Devin CLI
```

**実機での確認**: Task 19の`hut-golden`(見本の提出で、サーバーの保留のハッシュが金のファイルの1行目と一致する)・`hut-here`・`bad-source`(`../`を含む指定が断られ、子供向けの文が出る)。

---

### Task 18: devkitに建設のAPIを足す(サーバー側HTTP `127.0.0.1:47392`・クライアント側の追加・スパイクの測定具)

**担当: コントローラ(Claude)**。別のリポジトリなので、Devinには渡さない。

**このタスクは別リポジトリ`G:\prj2\micra_drone_devkit`で行い、そのリポジトリにコミットする**(開発専用。配布しない)。オーナーを確認の輪から外すための土台: 台本(Task 19)は、このAPIだけでゲームを動かし、読む。**OSのマウス・キーボードの合成入力は使わない。ゲームの窓へ文字を送らない。**

**このタスクで作るのは、下の表で「(Task Nで足す)」の印が無い行だけ**(P4レビューB-2)。印のある行は、そのタスクでmicradrone側の型ができてから、そのタスクの中でdevkitに足す(devkitのリポジトリへの別のコミット)。印の無い行だけで`devkit-smoke`(Task 19)が通る。

**Files(devkitのリポジトリ):**
- Modify: `src/main/java/io/github/khayashi4337/micradronedevkit/MicradroneDevkit.java`(`dist = Dist.CLIENT`を外し、共通のmodにする。クライアントの初期化は`DevkitClientSetup`へ移す)、`src/main/templates/META-INF/neoforge.mods.toml`(依存の`side`を`BOTH`に)、`src/main/java/io/github/khayashi4337/micradronedevkit/DevkitHttpServer.java`(ポートをシステムプロパティ`micradrone.devkit.port`で変えられるようにする。既定47391。2つ目のクライアント用。**ポートを取れなければ例外を握りつぶさず、ログに`DEVKIT PORT BUSY`を出して、そのAPIを立てない**。今は握りつぶしているので、林さんのゲームが同じポートを持っていても気づけない。P4レビューF-3)、`README.md`(新しいエンドポイントの一覧と、JSONの形)
- Create: `src/main/java/io/github/khayashi4337/micradronedevkit/{DevkitClientSetup,DevkitServerHooks,DevkitServerApi,DevkitClientBuildApi,DevkitChatLog,DevkitProbe,DevkitProtectBox,DevkitStates,DevkitRunInfo,DevkitTeeSource,DevkitWorldCreator,DevkitInjector}.java`

**Interfaces:**
- Consumes(micradrone): `ConstructionRuntime.of(MinecraftServer)`と、その公開メソッド(Task 16)、`JobViews`、`ManifestJson.toTree`、`JobService.admitApproved`・`ConstructionJob.create`・`JobProgram.build`(注入の行だけ。下を見よ)。Minecraft/NeoForge: `ServerStartedEvent`・`ServerStoppedEvent`、`MinecraftServer.createCommandSourceStack()`・`getCommands().performPrefixedCommand(CommandSourceStack, String)`・`saveEverything(boolean, boolean, boolean)`・`halt(boolean)`・`getAverageTickTimeNanos()`、`CommandSource`(`sendSystemMessage(Component)`・`acceptsSuccess()`・`acceptsFailure()`・`shouldInformAdmins()`)・`CommandSourceStack.withSource(CommandSource)`、`FakePlayerFactory.get(ServerLevel, GameProfile)`・`UUIDUtil.createOfflinePlayerUUID(String)`、`Screenshot.grab(File gameDirectory, String name, RenderTarget, Consumer<Component>)`(**書き込みは非同期**、`Screenshot.java` 44〜77行)・`Minecraft.getMainRenderTarget()`、`ClientChatReceivedEvent`(`getMessage()`・`isSystem()`)、`ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`・`Minecraft.disconnect()`、`Minecraft.createWorldOpenFlows().createFreshLevel(String, LevelSettings, WorldOptions, Function<RegistryAccess,WorldDimensions>, Screen)`(`WorldOpenFlows.java` 79行)・`new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT)`(5番目の`true`がコマンドの許可。`CreateWorldScreen.java` 262行と同じ形)・`new WorldOptions(seed, false, false)`・`WorldPresets.FLAT`と`WorldPreset.createWorldDimensions()`、`RegisterPayloadHandlersEvent`・`ByteBufCodecs.byteArray(int)`、`BlockEvent.EntityPlaceEvent`・`BlockEvent.BreakEvent`・`FakePlayer`、`Capabilities.ItemHandler.BLOCK`・`IItemHandler.insertItem`
- Produces(**台本が使うエンドポイントの一覧**。既存の`GET /state`はそのまま(項目を足すだけ)。新しいものは`POST`・JSON。応答に`"error"`があれば失敗。形は下の「JSONの形」):

  すべての応答に`runId`・`pid`・`gameDir`を入れる(`DevkitRunInfo`: `runId`はシステムプロパティ`micradrone.devkit.runId`。無ければ`"none"`)。**台本は毎回の呼び出しで`runId`が自分の物か確かめ、違えば止まる**(林さんが別に起動しているゲームのdevkitに触れないため。P4レビューF-3)。

  クライアント側の`GET /state`に足す項目: `screen`(今の画面のクラスの単純名。無ければ`null`)・`inWorld`(`mc.level != null && mc.player != null`)・`runId`・`pid`・`gameDir`。

  サーバー側(`DevkitServerApi`、ポートはシステムプロパティ`micradrone.devkit.serverPort`、既定47392。サーバーのメインスレッドで実行し、5秒で打ち切り):
  | エンドポイント | 要求 | 応答 |
  |---|---|---|
  | `/server/state` | なし | `ready`・`tick`・`msptAvg`(`getAverageTickTimeNanos()/1e6`)・`worldDir`・`players`(名前・UUID・ディメンション・座標・ゲームモード・`op`・`flying`)・`levels` |
  | `/server/run-command` | `command` | コンソール(権限4)で実行し、出力の行`output`(`DevkitTeeSource`で集める) |
  | `/server/run-as` | `player`・`command` | オンラインならその人の`createCommandSourceStack()`、いなければ`FakePlayerFactory.get(overworld, new GameProfile(UUIDUtil.createOfflinePlayerUUID(player), player))`で実行(権限はその人の物)。**出力は`DevkitTeeSource`で`output`に集め、同時に、その人がオンラインならそのチャットにも出す**(子供が見る文を、偽のプレイヤーでも台本が読めるように。P4レビューF-4) |
  | `/server/save-all` | なし | `saveEverything(false, true, true)` |
  | `/server/stop` | なし | `halt(false)`(きれいに止める) |
  | `/build/submit` | `player`・`source`・`here` | `ConstructionRuntime.submit`を呼ぶ(非同期)。`accepted` |
  | `/build/pending` | `player` | `JobViews.submitTree(runtime.lastSubmit(uuid))` |
  | `/build/approve` | `player`・`hash`・`confirmTerraform`・`confirmDestructive`・`accept`(IDの配列)・`owner`(Task 26で足す) | `approved`・`jobId`または`rejection`・`blocking` |
  | `/build/status` | `jobId` | `JobViews.statusTree` |
  | `/build/jobs` | なし | `JobViews.jobsTree` |
  | `/build/cancel`・`/build/resume` | `player`・`jobId`(・`skipConflicts`) | `ControlResult`の名前 |
  | `/build/verify`(Task 21で足す) | `player`・`jobId` | `ControlResult`の名前と`jobId` |
  | `/build/files`・`/build/recover`(Task 25で足す) | `jobId`(・`player`・`action`) | ファイルの名前と大きさ/`ControlResult` |
  | `/build/rollback`(Task 28で足す) | `player`・`claimId` | `ControlResult`の名前と`jobId` |
  | `/build/modify`(Task 29で足す) | `player`・`claimId`・`source` | `accepted` |
  | `/build/manifest` | `hash` | `ManifestJson.toTree`(`placements`に`index`・`pos`・`block`・`verify`・`phase`・`replaces`) |
  | `/build/read-blocks` | `dimension`と、`positions`(`[[x,y,z],...]`、最大4,096)か`box`(`[x1,y1,z1,x2,y2,z2]`、4,096個まで) | `blocks`: `[{"pos":[x,y,z],"state":"id[k=v,...]","blockEntity":"<型のID>"か null,"loaded":bool}]`(`DevkitStates.text`。**micradroneのコードを使わず独立に作る**) |
  | `/build/entities` | `dimension`と、`type`(例 `minecraft:item`)か`tag`、任意で`box` | `count`・`positions`(`[[x,y,z],...]`)・`items`(`type`がアイテムのとき、品物のID→個数の合計) |
  | `/build/inject-manifest` | `player`・`blocks`(`[{"pos":[x,y,z],"state":"id[k=v,...]"}]`、最大64)・`materialPolicy` | `jobId`。**開発専用の注入**(下の`DevkitInjector`) |
  | `/build/fill-container` | `dimension`・`pos`・`item`・`count` | `inserted`(`Capabilities.ItemHandler.BLOCK`の`insertItem`で入った数。保管庫・デポ・樽に同じ道で入れる) |
  | `/build/perf`(Task 33で足す) | なし | `runtime.perfTree()`とサーバーの`msptAvg` |
  | `/spike/protect-box` | `dimension`・`box`・`cancelPlace`・`cancelBreak`(`box`が`null`なら無効) | `enabled` |
  | `/spike/protect-log` | なし | 記録: イベントの型・主体のクラス名・`fakePlayer`・`placedState`・`worldState`・`pos` |
  | `/spike/probe-log` | なし | 受け取った測定用ペイロードの大きさの一覧 |
  | `/spike/barrier`(Task 25で足す) | `point`・`kind`・`nth` | 規約の境目(`WriteAheadLog.Barrier`)を仕掛ける。その境目を、その種類のジョブで`nth`回目に通った時、サーバーのスレッドをそこで待たせる(台本がpidを落とすため)。`micradrone.devBarriers=true`の起動だけで効く |
  | `/spike/barrier-state`(Task 25で足す) | なし | `armed`・`hit`・`point`・`run` |
  | `/spike/load`(Task 33で足す) | `msPerTick` | **`ServerTickEvent.Pre`で**、その時間だけ待って重さを作る(自動減速の確認用。`0`で止める。`Pre`はtickの時間を測る窓の中にある(`MinecraftServer.java` 928〜955行)ので、`getAverageTickTimeNanos()`に入る。`Post`では入らない。P4レビューD-1) |

  クライアント側(既存の`DevkitHttpServer`に足す`DevkitClientBuildApi`。描画スレッドで実行):
  | エンドポイント | 要求 | 応答 |
  |---|---|---|
  | `/client/create-world` | `name`・`seed`・`flat`(真なら`WorldPresets.FLAT`)・`gameMode`(`creative`) | タイトル画面のときだけ、`DevkitWorldCreator`が`createFreshLevel`で世界を作って入る(`allowCommands=true`なので、シングルプレイの`Dev`がOPになる)。`started`。タイトル画面でなければ`error`(P4レビューF-2: `--quickPlaySingleplayer`は無い世界を作れず、サーバーで作った世界はコマンドが許可されない) |
  | `/client/screenshot` | `name`(**ファイル名だけ**: `[A-Za-z0-9_-]{1,64}\.png`に合うこと。`/`・`\\`・`..`・絶対パス・ドライブ名は`error`。保存先は`<gameDir>/screenshots/`に固定し、決めたパスの`toRealPath`の親が、その`screenshots`の実際のパスと同じであることも確かめる。Codexのレビュー2026-09-30: `.png`で終わるかだけでは`../../x.png`で外に書ける) | `Screenshot.grab(gameDirectory, name, mainRenderTarget, msg -> {})`を呼び、**ファイルができて大きさが0でなくなるまで**(最大5秒)待ってから、保存先のパス(`<gameDir>/screenshots/<name>`)を返す(書き込みが非同期のため。P4レビューF-10) |
  | `/client/chat-log` | `since`(番号) | `lines`: `[{"n":番号,"text":この言語で表示された文字列(getMessage().getString()),"system":bool}]`・`next`(次の`since`) |
  | `/client/connect` | `host`・`port` | `ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(host + ":" + port), new ServerData("p4", host + ":" + port, ServerData.Type.OTHER), false, null)` |
  | `/client/disconnect` | なし | `Minecraft.disconnect()`(世界から出る) |
  | `/spike/send-probe` | `bytes` | 測定用ペイロードをその大きさで送る(S-6の測定具。ここで作り、Task 32が使う) |
  | `/client/upload`(Task 32で足す) | `path`(任意で`corruptChunk`: その番号のチャンクを1バイト変えて送る。改ざんの確認用) | Task 32の`ClientUploads.send`を呼ぶ(ファイルを分割して送る) |
  | `/client/approve-remote`(Task 32で足す) | `hash`・`confirmTerraform`・`confirmDestructive`・`accept`(IDの配列)・`owner`(省略可) | Task 32の`ApprovePlanPayload`を送る。`sent` |
  | `/client/send-command`(Task 34で足す) | `command` | `Minecraft.player.connection.sendCommand`でサーバーのコマンドを送る(キーボードの合成入力ではない) |

- `DevkitTeeSource implements CommandSource`: 受け取った`Component`を`getString()`で行のリストに足し、元の`CommandSource`(プレイヤーかコンソール)があればそれにも渡す。`acceptsSuccess`・`acceptsFailure`は`true`、`shouldInformAdmins`は`false`。`CommandSourceStack.withSource(tee)`で使う。
- `DevkitWorldCreator.create(Minecraft, name, seed, flat)`: `mc.screen instanceof TitleScreen`を確かめ、`mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, false, false), ra -> flat ? ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() : WorldPresets.createNormalWorldDimensions(ra), new TitleScreen())`。(既定の`WorldDataConfiguration.DEFAULT`でmicradroneのデータパック(タグ)が有効になるかは**未確認**。Task 19の`hut-here`の`terrainCut`=49と、`latest.log`に`BuildTags`の「タグが空」の警告が無いことで確かめる。無効なら、`PackRepository`の全部のIDを有効にした`WorldDataConfiguration`を渡す。)
- `DevkitInjector`(**開発専用の注入**。完了条件13の実機の確認で、Createの`item_vault`・`depot`とバニラの`barrel`を「このプロジェクトが置いた物」にするため。これらを置く部品はP4に無い): 要求の`blocks`から、`Placement(index, pos, block, Map.of(), "inject-" + index, BuildPhase.STRUCTURE, PlacerId.SIMPLE, VerifyMode.BLOCK_ONLY, ReplacePolicy.AIR_ONLY, null)`の並びと`PlacementManifest`(ハッシュは`ManifestJson.computeHash`)を作り、`ConstructionJob.create(runtime.nextJobId(), uuid, dim, hash, JobKind.BUILD, null, n, "claim-" + jobId, policy, tick, List.of())`を`runtime.jobs().admitApproved(job, manifest, Map.of(), JobProgram.build(manifest), manifest.worldBounds())`へ入れる。**micradroneの公開の型だけを使い、micradroneに裏口を作らない**(配布するjarには注入の道が無い)。台本は、この注入を`inject`という名前の証拠に残す。
- `DevkitProbe`: `record ProbePayload(byte[] data)`、`TYPE = micradrone_devkit:probe`、`STREAM_CODEC = ByteBufCodecs.byteArray(PROBE_MAX_BYTES)`(`PROBE_MAX_BYTES = 16 * 1024 * 1024`: 測りたい最大の8MiBより大きく)。`RegisterPayloadHandlersEvent`で`playToServer`に登録し、サーバーは受け取った長さを記録する。
- `DevkitProtectBox`: サーバー側の`@SubscribeEvent`。有効な箱の中の`EntityPlaceEvent`・`BreakEvent`をキャンセルし、上の記録を残す(S-9の「簡単な保護のテスト用リスナー」)。
- `DevkitStates.text(BlockState)`: `BuiltInRegistries.BLOCK.getKey(block)`と、`getProperties()`を名前で並べた`k=v`を`,`で結ぶ(micradroneの`BlockStates`と同じ形だが、独立に書く。読み戻しの照合が、検査される側のコードに依存しないため)。

- [ ] **Step 1: devkitの実装**

上の表の、印の無い行だけを実装する。すべてのハンドラは既存の`DevkitHttpServer.handle`と同じ作法(本文をJSONで読み、結果をJSONで返し、例外は`{"error": ...}`)。サーバー側は`server.execute`と`CompletableFuture`で5秒まで待つ(`SERVER_THREAD_TIMEOUT_MS = 5_000L`)。長い処理(提出・ジョブ)は待たずに返し、台本が`/build/pending`・`/build/status`を1秒ごとに読む。

- [ ] **Step 2: ビルドする**

Run(micradroneのリポジトリで): `./gradlew.bat jar --console=plain`
Expected: `BUILD SUCCESSFUL`、`build/libs/micradrone-<版>.jar`。(`build/libs`のjarは`.gitignore`で追跡されない版名なら残してよい。追跡される名前なら、コミットの前に`git checkout -- build/libs`で戻す。)
Run(devkitのリポジトリで): `./gradlew.bat build -Pmicradrone_jar_path=G:/prj2/micra_drone/build/libs/micradrone-<版>.jar --console=plain`
Expected: `BUILD SUCCESSFUL`、`build/libs/micradrone_devkit-<版>.jar`。

- [ ] **Step 3: 実機の煙の試験はTask 19で行う**(P4レビューE-5: 実機の起動に要る`run-p4/`・`clientP4`の実行設定・世界の作成は、Task 19の台本が作る)。このタスクはビルドとコミットまで。

- [ ] **Step 4: コミット(devkitのリポジトリ)**

```bash
cd /g/prj2/micra_drone_devkit
git add src README.md
git commit -m "$(cat <<'MSG'
feat: 建設の自動確認のため、サーバー側API(47392)とスクリーンショット・チャット記録・接続・世界の作成・注入・測定具のエンドポイントを追加(自然言語→工場建設 P4 Task 18)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 19: 自動確認の台本(`tools/p4/`)と、最初のシナリオ(`devkit-smoke`・`hut-golden`・`hut-here`・`bad-source`)

**担当: コントローラ(Claude)**。Pythonの実行と、ゲームの起動・後始末を含むため、Devinには渡さない。

オーナーを確認の輪に入れないための本体。**1つのコマンドで、ゲームの起動→世界に入る→建てる→読み戻して照合→証拠→閉じる→後始末**まで行う。

**Files:**
- Modify: `build.gradle`(自動確認の専用の実行設定)、`.gitignore`(`run-p4/`・`run-evidence/`)
- Create: `tools/__init__.py`・`tools/p4/__init__.py`・`tools/p4/tests/__init__.py`(空。`python -m`で包みとして読むため。P4レビューE-6)、`tools/p4/{p4_scenarios.py,harness.py,devkit_client.py,compare.py,evidence.py,plans.py,scenarios_basic.py}`、`tools/p4/tests/{test_compare.py,test_evidence.py,test_plans.py,test_registry.py,test_harness.py}`、`tools/p4/game_config/{options.txt,neoforge-client.toml}`(起動の前に置く設定)、`tools/p4/server.properties`(雛形)、`tools/p4/README.md`(使い方。実行の入口は`python -m tools.p4.p4_scenarios --all`。リポジトリの一番上で実行する)

**Interfaces:**
- Consumes: Task 18のdevkitのAPI、`src/test/resources/build/golden/hut.manifest.txt`(1行目のハッシュ)
- Produces:
  - `build.gradle`の`runs`に5つ(既存の`client`・`server`は変えない)。**どれも`-Xmx3G`**(P4レビューF-7: 指定が無いと、各JVMが物理メモリの1/4を取る)。`runId`はGradleのプロパティ`p4RunId`から渡す(台本が`-Pp4RunId=<runId>`を付ける):
    ```groovy
        clientP4 {
            client()
            gameDirectory = project.file('run-p4/client')
            jvmArguments.addAll '-Xmx3G'
            systemProperty 'micradrone.devkit.port', '47391'
            systemProperty 'micradrone.devkit.runId', providers.gradleProperty('p4RunId').getOrElse('none')
        }
        clientLoadP4 {
            client()
            gameDirectory = project.file('run-p4/client')
            jvmArguments.addAll '-Xmx3G'
            programArguments.addAll '--quickPlaySingleplayer', 'p4-auto'
            systemProperty 'micradrone.devkit.port', '47391'
            systemProperty 'micradrone.devkit.runId', providers.gradleProperty('p4RunId').getOrElse('none')
        }
        clientMpP4 {
            client()
            gameDirectory = project.file('run-p4/client')
            jvmArguments.addAll '-Xmx3G'
            programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25565'
            systemProperty 'micradrone.devkit.port', '47391'
            systemProperty 'micradrone.devkit.runId', providers.gradleProperty('p4RunId').getOrElse('none')
        }
        client2P4 {
            client()
            gameDirectory = project.file('run-p4/client2')
            jvmArguments.addAll '-Xmx3G'
            programArguments.addAll '--username', 'Dev2', '--quickPlayMultiplayer', '127.0.0.1:25565'
            systemProperty 'micradrone.devkit.port', '47393'
            systemProperty 'micradrone.devkit.runId', providers.gradleProperty('p4RunId').getOrElse('none')
        }
        serverP4 {
            server()
            gameDirectory = project.file('run-p4/server')
            jvmArguments.addAll '-Xmx3G'
            programArgument '--nogui'
            systemProperty 'micradrone.devkit.serverPort', '47392'
            systemProperty 'micradrone.devkit.runId', providers.gradleProperty('p4RunId').getOrElse('none')
        }
    ```
  - `harness.py`: `class Game`(起動・待機・終了)。
    - `preflight()`: **47391・47392・47393・25565のどれかが既に待ち受けていれば、起動せずに`PortBusyError`で止まる**(林さんのゲームがdevkitを載せて47391を持っていると、台本がそのゲームを操作してしまう。P4レビューF-3)。確かめ方は`socket.create_connection(("127.0.0.1", port), timeout=0.5)`が成功するか。
    - `prepare()`: **Gradleは1回だけ、ゲームを起動する前に**走らせる(Codexのレビュー2026-09-30: 前の版は、`runServerP4`のGradleが動いたままクライアントのGradleを起動し、同じチェックアウトでGradleを同時に走らせないという自分の規則に反していた)。`gradlew.bat --no-daemon -Pp4RunId=<runId> jar prepareClientP4Run prepareClientLoadP4Run prepareClientMpP4Run prepareClient2P4Run prepareServerP4Run`で、jarと、各実行設定の引数のファイルを作る(ModDevGradleの`PrepareRun`の作業。ファイルは`RunUtils$RunArgFile`の`runVmArgs.txt`・`runProgramArgs.txt`・`runClasspath.txt`。**作業の名前・ファイルの置き場所と名前の前につく語は、最初の実行で`gradlew tasks --all`の出力と`build/moddev`の中を見て`harness.py`の定数に書く**。確かめた結果は証拠の`prepare.json`に残す)。Gradleはここで終わり、残らない。
    - `start_server()`・`start_client(kind)`(`kind`は`sp`=`clientP4`・`sp-load`=`clientLoadP4`・`mp`=`clientMpP4`・`mp2`=`client2P4`): 台本が**javaを直接**起動する(`subprocess.Popen`。ModDevGradleがIDEの実行設定を作る時と同じ形=`java @<VM引数のファイル> <その主クラス> @<プログラム引数のファイル>`。主クラスと作業フォルダは`prepare`で確かめた値。起動したコマンドの全文を証拠に書く)。**起動は1つずつ**、前のAPIが応答してから次を起動する。起動した`Popen`はすべて`children`の一覧に入れる。
    - `wait_api(port, timeout)`: 応答があり、**その`runId`がこの実行の物**であるまで待つ。違う`runId`・`"none"`なら`ForeignGameError`で止まる。
    - `wait_screen(port, name, timeout)`(`/state`の`screen`)、`wait_in_world(port, timeout)`(`/state`の`inWorld`)。
    - `close_client(kind)`: **窓に`WM_CLOSE`**。`ctypes.windll.user32.EnumWindows`で、この起動のゲームのJVM(下の見分け方)の見える窓を探し、`PostMessageW(hwnd, WM_CLOSE, 0, 0)`。窓が無ければ`NoWindowError`で**止まって報告する**。終了を`CLOSE_TIMEOUT_S`秒待つ。
    - `stop_server()`(devkitの`/server/stop`)。
    - `collect_logs(folder)`: 各ゲームのフォルダの`logs/latest.log`と、この実行で増えた`crash-reports/*.txt`を証拠に写す(**後始末の前に必ず呼ぶ**。P4レビューF-10)。
    - `cleanup()`: **`p4_scenarios.py`の一番外側の`try ... finally`で必ず呼ぶ**(途中の例外・Ctrl+Cでも)。`children`の各プロセスについて、まだ生きていれば、そのPIDと子のプロセス(`taskkill /T /F /PID <pid>`)を終了して証拠に書く。**ゲームのフォルダ(`run-p4/client`・`client2`・`server`)は残す**(毎回の初回起動の画面・資源のダウンロード・Gradleの冷えたコンパイルを避ける。P4レビューG-4)。消すのは世界だけ(`run-p4/client/saves/p4-auto`・`run-p4/server/world`)で、それも**次の実行の始め**に消す(失敗した実行の世界を、調べるために残す)。
    - プロセスの見分け方: 台本が起動したプロセスは`Popen`のPIDで分かる(Gradleを間に挟まないので、差を取って推測しない)。窓を探すとき(`WM_CLOSE`)も、このPIDの窓だけを見る。林さんが別に起動しているゲームには触れない。
    - 定数: `API_UP_TIMEOUT_S = 300`(javaの起動から、devkitのAPIが応答するまで。Gradleのコンパイルは`prepare`で済んでいる)、`IN_WORLD_TIMEOUT_S = 300`、`JOB_TIMEOUT_S = 600`、`CLOSE_TIMEOUT_S = 120`、`POLL_INTERVAL_S = 1.0`、`GAME_JVM_HEAP_GIB = 3`(`build.gradle`の`-Xmx3G`と同じ数。1か所で持ち、`test_harness.py`で`build.gradle`の値と一致を確かめる)、`GAME_JVM_NATIVE_GIB = 0.5`(ヒープの外: メタスペース・コードキャッシュ・直接バッファの見積もり。実際の使用量は毎回`Get-CimInstance`の`WorkingSetSize`で証拠に書く)。
    - `required_free_gib(n_games) = n_games * (GAME_JVM_HEAP_GIB + GAME_JVM_NATIVE_GIB)`(ゲームの間はGradleが残らない。クライアント2つ+サーバー=3で10.5GiB)。`mp2`の前に空きメモリ(`Win32_OperatingSystem.FreePhysicalMemory`)がこれより少なければ、そのシナリオは**`NOT-RUN(resource)`**として、必要な数・空きの数・動いていたプロセスの上位10個のメモリを証拠に書く。**`NOT-RUN`は合格ではない**(P4レビューA1-4。Task 38は`NOT-RUN`が1つでもあれば失敗にする)。
  - 設定の置き場: 起動の前に、`tools/p4/game_config/options.txt`を`run-p4/client/options.txt`・`run-p4/client2/options.txt`へ、`tools/p4/game_config/neoforge-client.toml`を`run-p4/{client,client2}/config/neoforge-client.toml`へ写す(毎回。ゲームが書き足した行は上書きされてよい)。`options.txt`の中身: `onboardAccessibility:false`(初回の「アクセシビリティ」の画面を出さない)・`pauseOnLostFocus:false`(窓に焦点が無いとシングルプレイが止まるのを防ぐ。`Options.java` 1212行、`GameRenderer.java` 1006行)・`lang:ja_jp`(子供が見る言語で文言を集める)・`tutorialStep:none`・`joinedFirstServer:true`・`skipMultiplayerWarning:true`・`narrator:0`。`neoforge-client.toml`: `showLoadWarnings = false`(modの読み込みの警告の画面が、世界に入る前に出るのを防ぐ。`ClientModLoader.completeModLoading`、`NeoForgeConfig.java` 113行)。(P4レビューF-1)
  - 世界の用意:
    - **シングルプレイ(`sp`)はEULAが要らない**: `clientP4`を世界の指定なしで起動し、`/state`の`screen`が`TitleScreen`になるまで待ち、devkitの`/client/create-world {name: "p4-auto", seed: 4337, flat: true, gameMode: "creative"}`で世界を作って入る(`allowCommands=true`。P4レビューF-2)。入った後、`/server/run-command "op Dev"`を送り、`/server/state`の`players`で`Dev`の`op`が`true`であることを確かめる(違えば止まる)。
    - 2回目以降にその世界を開くとき(再起動の確認)は`clientLoadP4`(`--quickPlaySingleplayer p4-auto`)。
    - **マルチプレイ(`mp`・`mp2`)だけEULAが要る**: `serverP4`が`run-p4/server/world`を作る(`tools/p4/server.properties`: `level-type=minecraft\:flat`・`gamemode=creative`・`online-mode=false`・`spawn-protection=16`・`difficulty=peaceful`・`spawn-monsters=false`・`generate-structures=false`・`level-seed=4337`・**`server-ip=127.0.0.1`**(全部のアドレスで待ち受けると、初回にWindowsのファイアウォールの許可の画面が出るおそれがある。P4レビューF-8)・`enable-query=false`・`enable-rcon=false`)。起動後に`/server/run-command "op Dev"`。
    - **`run-p4/server/eula.txt`は台本が書かない**: 無ければ「EULAへの同意は林さんのアカウントの同意なので、一度だけ林さんが`tools/p4/eula_ack.txt`を作ってください(中身は`eula=true`の1行)」と表示し、`mp`・`mp2`のシナリオを**`NOT-RUN(eula)`**にする(オーナーだけができる項目。理由は同意の主体だから)。台本は`tools/p4/eula_ack.txt`があれば、その中身を`run-p4/server/eula.txt`に写す。**`sp`のシナリオはEULAに依存しない**(`--mode sp`はEULAが無くても全部走る)。
  - devkitのjar: 台本がmicradroneの`jar`とdevkitの`build`を走らせ、`run-p4/{client,client2,server}/mods/`に置く(起動のたびに作り直し、`filecmp.cmp`で同一を確かめる。古いjarの`NoSuchMethodError`の事故を防ぐ。devkitのREADMEの教訓)。
  - `devkit_client.py`: `class Devkit(port, run_id)`: `post(path, body) → dict`(`urllib.request`。`error`があれば`DevkitError`。**応答の`runId`が`run_id`と違えば`ForeignGameError`**)、`get(path) → dict`、`poll(path, body, until, timeout)`
  - `compare.py`(純粋): `parse_state("id[k=v,...]") → (id, dict)`、`compare(manifest_tree, readback) → list[Mismatch]`(`verify`が`BLOCK_ONLY`ならIDだけ、`EXACT`は**両方の状態を全部**、`STATE_SUBSET`はIDと施工リストに書いた状態だけ、`ASSEMBLED_AWAY`は比べない。`SnapshotDiff`と同じ規則を、**micradroneのコードを使わずPythonで独立に書く**)、`Mismatch(index, pos, expected, observed, reason)`
  - `evidence.py`: `RunFolder(run_id)`(`run-evidence/p4/<run_id>/`)、`write_json(name, obj)`、`add_file(src, name)`、`record(scenario, conditions, status, reason, files)`(`status`は`PASS`・`FAIL`・`NOT-RUN`。**`NOT-RUN`は理由を必ず書き、黙って飛ばさない**)、`summary.json`(シナリオごとの状態と理由・証拠のファイル名と、条件ごとの状態)
  - `plans.py`: 計画のJSONを作る(金のファイルのパッチを読み、原点・大きさ・素材を差し替える): `hut_patch(origin, facing, palette=None)`・`long_road_patch(length)`(安全枠の超過用)・`hut_with_extra_wall_patch()`(`MODIFY`用)。後のタスクが使う補助は、そのタスクで足す(Task 25`hut_patch`の大きさの引数、Task 32`many_parts_patch`、Task 33`big_block_patch`。各タスクのFilesに`tools/p4/plans.py`を書いてある)
  - `p4_scenarios.py`: 入口。`--all`・`--only a,b`・`--mode sp|mp|mp2|all`(P4レビューE-7)・`--run-id`。シナリオの登録簿`SCENARIOS`(名前→`Scenario(func, conditions, mode, owner_only_reason=None)`)と、`PENDING_CONDITIONS`(まだシナリオの無い完了条件→それを足すタスクの番号。各シナリオのタスクが、自分の条件をここから消す)。**`test_registry.py`が、完了条件1〜16のすべてにシナリオが1つ以上あるか、`PENDING_CONDITIONS`に載っていることを検査する**。

- 最初のシナリオ(`scenarios_basic.py`。どれも`mode="sp"`):
  1. `devkit-smoke`: クライアントとサーバーの、**このタスクまでに実装済みのエンドポイントだけ**を1回ずつ呼び(破壊的な`stop`は除く)、`error`が無いこと。`runId`・`pid`・`gameDir`が入っていること。
  2. `hut-golden`(条件1・5の一部・16の前提): `run-command`で`gamemode creative Dev`・`time set day`・`gamerule doDaylightCycle false`・**`setblock 100 65 186 minecraft:glass`**(見る位置の足場。調査の箱の外。クリエイティブでも`tp`の後は飛んでいないので、足場が無いと地面まで落ちて、小屋が写らない。P4レビューF-5)・`tp Dev 100 66 186 0 15`→`/server/state`の`Dev`の`y`が66であることを確かめる。`/build/submit {player: Dev, source: "sample:hut"}`→`/build/pending`が`OFFERED`になるまで待つ→**`hash`が`hut.manifest.txt`の1行目と一致**(サーバーが自分で作り直した施工リストが金のファイルと同じ)→`/build/approve`→`/build/status`を`VERIFIED`まで1秒ごとに記録(`timeline.json`)→`/build/manifest`と`/build/read-blocks`で**238個の全配置を読み戻して`compare.py`で照合し、不一致0件**(`compare.json`)→`/client/screenshot`(`hut-golden.png`)→`/client/chat-log`(`chat.json`)。
  3. `hut-here`(条件1・12の前提): `tp Dev 0 -60 0 180 20`(平らな地面の上に立つので、足場は要らない)。`/build/submit {here: true}`→保留の`terrainCut`が49(基礎が草の層に沈む)で`>0`→確認なしの承認が`TERRAFORM_UNCONFIRMED`で断られる→`confirmTerraform: true`で承認→`VERIFIED`→読み戻しの照合(整地の`SITE_PREP`の配置を含む)→スクリーンショット。`latest.log`に`BuildTags`の「タグが空」の警告が無いこと(世界の作成でmodのデータパックが有効なことの確認)。
  4. `bad-source`(F-22): `/server/run-as Dev "micradrone build submit ../../server.properties"`→**応答の`output`**と`/client/chat-log`の両方に`micradrone.build.submit.bad_source`の日本語の文(「その けいかくの ファイルが よめないよ」)が出て、`/build/pending`が変わらないこと。

- [ ] **Step 1: 失敗するテストを書く(Python。純粋な部分)**

`tools/p4/tests/test_compare.py`:
```python
import unittest

from tools.p4 import compare


class CompareTest(unittest.TestCase):
    def test_parse_state(self):
        self.assertEqual(("minecraft:oak_stairs", {"facing": "north", "half": "bottom"}),
                         compare.parse_state("minecraft:oak_stairs[facing=north,half=bottom]"))
        self.assertEqual(("minecraft:stone", {}), compare.parse_state("minecraft:stone"))

    def _manifest(self, block, verify="EXACT"):
        return {"placements": [{"index": 0, "pos": [1, 64, 2], "block": block, "verify": verify}]}

    def test_listed_states_must_match_and_extra_states_are_ignored_by_state_subset(self):
        m = self._manifest({"id": "minecraft:oak_stairs", "props": {"facing": "north"}}, "STATE_SUBSET")
        ok = {(1, 64, 2): "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"}
        self.assertEqual([], compare.compare(m, ok))
        turned = {(1, 64, 2): "minecraft:oak_stairs[facing=east,half=bottom]"}
        self.assertEqual("state:facing", compare.compare(m, turned)[0].reason)

    def test_exact_compares_every_state(self):
        m = self._manifest({"id": "minecraft:oak_log", "props": {"axis": "y"}}, "EXACT")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:oak_log[axis=y]"}))
        self.assertEqual("state:extra", compare.compare(m, {(1, 64, 2): "minecraft:oak_log[axis=y,extra=1]"})[0].reason)

    def test_block_only_compares_the_id(self):
        m = self._manifest({"id": "minecraft:glass_pane", "props": {"north": "true"}}, "BLOCK_ONLY")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:glass_pane[north=false]"}))

    def test_missing_readback_and_wrong_block_are_reported(self):
        m = self._manifest({"id": "minecraft:stone", "props": {}})
        self.assertEqual("unread", compare.compare(m, {})[0].reason)
        self.assertEqual("block", compare.compare(m, {(1, 64, 2): "minecraft:dirt"})[0].reason)

    def test_assembled_away_is_not_compared(self):
        m = self._manifest({"id": "minecraft:white_wool", "props": {}}, "ASSEMBLED_AWAY")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:air"}))


if __name__ == "__main__":
    unittest.main()
```
`tools/p4/tests/test_registry.py`:
```python
import unittest

from tools.p4 import p4_scenarios

ALL_CONDITIONS = set(range(1, 17))  # design 07, P4 completion conditions 1..16


class RegistryTest(unittest.TestCase):
    def covered(self):
        out = set()
        for s in p4_scenarios.SCENARIOS.values():
            out.update(s.conditions)
        return out

    def test_every_completion_condition_has_an_automated_check_or_is_pending_with_its_task(self):
        pending = set(p4_scenarios.PENDING_CONDITIONS)
        self.assertEqual(ALL_CONDITIONS, self.covered() | pending, "P4 completion conditions 1..16 (design 07)")
        for condition, task in p4_scenarios.PENDING_CONDITIONS.items():
            self.assertTrue(task.startswith("Task "), (condition, task))

    def test_owner_only_scenarios_always_say_why(self):
        for name, s in p4_scenarios.SCENARIOS.items():
            if s.owner_only_reason is not None:
                self.assertTrue(len(s.owner_only_reason) > 20, name)


if __name__ == "__main__":
    unittest.main()
```
Task 19の時点の`PENDING_CONDITIONS`(条件→**その条件のシナリオが全部そろうタスク**。条件1・5・12はこのタスクのシナリオが持つので入れない。条件5・12は後のタスクもシナリオを足すが、それは足すだけでよい):
```python
PENDING_CONDITIONS = {
    2: "Task 21", 3: "Task 29", 4: "Task 28", 6: "Task 34", 7: "Task 28", 8: "Task 30", 9: "Task 32", 10: "Task 33",
    11: "Task 37", 13: "Task 30", 14: "Task 28", 15: "Task 21", 16: "Task 21",
}
```
シナリオは、条件が`PENDING_CONDITIONS`に残っていても、その条件の番号を持ってよい(途中のタスクが一部を足す)。**`PENDING_CONDITIONS`の値のタスクは、自分のシナリオを`SCENARIOS`に足すのと同じコミットで、その条件を`PENDING_CONDITIONS`から消す**(そのタスクのStepに1行ずつ書いてある)。Task 38は`test_no_pending_conditions_remain`で空であることを確かめる。

`tools/p4/tests/test_evidence.py`(一時フォルダに`RunFolder`を作り、`record`で`NOT-RUN`・`FAIL`に理由が無いと`ValueError`、`summary.json`の形、条件ごとの状態が「全部のシナリオが`PASS`のときだけ`PASS`、1つでも`NOT-RUN`なら`NOT-RUN`、`FAIL`があれば`FAIL`」)、`tools/p4/tests/test_plans.py`(`hut_patch((5,-60,7), "east")`の`set_site`の原点と向きが差し替わり、`ops`の数が金のファイルと同じ)、`tools/p4/tests/test_harness.py`(`required_free_gib(3) == 10.5`、`GAME_JVM_HEAP_GIB`が`build.gradle`の`clientP4`・`client2P4`・`serverP4`の`-Xmx`と同じ数であること(`build.gradle`の文字列を読む)、`preflight`がポートを持つ偽のソケットを見つけて`PortBusyError`を出すこと(テストの中で`socket`を1つ`listen`して確かめる)、`options.txt`の雛形に`pauseOnLostFocus:false`と`onboardAccessibility:false`があること)。

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `python -m unittest discover -s tools/p4/tests -t . -v`
Expected: FAIL(モジュールが無い)。

- [ ] **Step 3: 実装する**

上の「Produces」のとおり。`p4_scenarios.py`の実行の流れ(全体を`try ... finally: cleanup()`で包む): (1)`preflight`(ポート)、(2)`prepare`(Gradleは1回だけ。jar・引数のファイル)とdevkitのビルドと配置、設定の置き場、(3)モードごとにゲームを起動して世界を用意し、そのモードのシナリオを順に実行(各シナリオは例外を捕まえて`FAIL`と記録し、次へ進む。ゲームが落ちたら再起動は1回まで、2回目は残りを`NOT-RUN(game crashed)`)、(4)`collect_logs`、(5)閉じる(`WM_CLOSE`)、(6)後始末、(7)`summary.json`を表示し、1つでも`FAIL`か`NOT-RUN`があれば終了コード1。**同じエラーで同じ手を2回繰り返さない**(深く考えるルール)。

- [ ] **Step 4: 単体テストと、実機の自動確認を走らせる**(このタスクで、Task 18のdevkitの煙の試験も兼ねる)

Run: `python -m unittest discover -s tools/p4/tests -t . -v`
Expected: `OK`。
Run: `python -m tools.p4.p4_scenarios --only devkit-smoke,hut-golden,hut-here,bad-source --mode sp`
Expected: 4つとも`PASS`(EULAが無くても走る)。`run-evidence/p4/<runId>/summary.json`・`hut-golden/compare.json`(不一致0・238件)・`hut-golden/hut-golden.png`・`hut-here/pending.json`(`terrainCut`=49)・`bad-source/chat.json`・`logs/client-latest.log`。起動したプロセスが残っていないこと(台本の最後の表示と、`Get-CimInstance`の確認)。`FAIL`なら、証拠を読み、原因をsystematic-debuggingで調べて直す(直した内容は、原因のタスクの範囲のコミットとして別に積む)。

- [ ] **Step 5: コミット**

```bash
git add build.gradle .gitignore tools/__init__.py tools/p4
git commit -m "$(cat <<'MSG'
test: 実機の自動確認の台本(tools/p4)と、小屋の建設・読み戻しの照合・整地の確認・不正なパスのシナリオを追加(自然言語→工場建設 P4 Task 19)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 20: ドローンの演出(`DroneChoreographer`・`DroneShow`)

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{DroneMove,DroneChoreographer}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/DroneShow.java`、`tools/p4/scenarios_show.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(tickの(5)で`DroneShow.onUpdates`)、`tools/p4/p4_scenarios.py`(登録)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/DroneChoreographerTest.java`

**Interfaces:**
- Consumes: Task 13の`JobUpdate`(`touched`)、Task 7の`ConstructionBudget.droneCount`、既存の`MicraDrone.DRONE_ENTITY`・`drone/DroneEntity`(**変えない**。演出用に別の個体を作る。N-27)。Minecraft: `EntityType.create(Level)`・`Entity.addTag(String)`・`getTags()`・`setNoGravity(boolean)`・`setInvulnerable(boolean)`・`moveTo(double, double, double)`・`discard()`、`ServerLevel.addFreshEntity`・`sendParticles`、`Level.playSound(Player, BlockPos, SoundEvent, SoundSource, float, float)`、`BlockState.getSoundType().getPlaceSound()`、`EntityJoinLevelEvent`(キャンセル可能・`loadedFromDisk()`)
- Produces:
  - `record DroneMove(int drone, IntPos target)`、`DroneChoreographer.assign(int droneCount, List<IntPos> touched) → List<DroneMove>`(この1tickに置いた位置を、ドローンに順番に割り当て、各ドローンは最後に割り当てられた位置の上へ行く)、`HOVER_BLOCKS = 1.5`(置いた位置からの高さ)
  - `DroneShow`: `SHOW_TAG = "micradrone_build_show"`、`void onUpdates(MinecraftServer, List<JobUpdate>)`(ジョブが`RUNNING`・`REPAIRING`で置いた位置があれば、`droneCount`機まで作り、動かし、各位置にパーティクル(`ParticleTypes.HAPPY_VILLAGER`、`SHOW_PARTICLES = 4`)と、そのブロックの設置音(1tickに`MAX_SOUNDS_PER_TICK = 4`まで。音が重なりすぎないため。設計に数値なし))。ジョブがそれ以外の状態になったら、そのジョブのドローンを`discard`。`EntityJoinLevelEvent`で`SHOW_TAG`を持ち`loadedFromDisk()`の個体は取り消す(前の起動の残り)。`ServerStoppingEvent`で全部`discard`(ワールドに保存しない)。**演出が消えても(`/kill`)ジョブは進む**(N-27)。演出を始めたとき、所有者に`DRONE_ARRIVED`(「ドローンが きたよ!」)を1回だけ送る。

- [ ] **Step 1: 失敗するテストを書く**

`DroneChoreographerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DroneChoreographerTest {
    @Test
    void eachDroneGoesToTheLastPositionItWasGiven() {
        List<IntPos> touched = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            touched.add(new IntPos(i, 64, 0));
        }
        assertEquals(List.of(new DroneMove(0, new IntPos(6, 64, 0)), new DroneMove(1, new IntPos(4, 64, 0)),
                new DroneMove(2, new IntPos(5, 64, 0))), DroneChoreographer.assign(3, touched));
    }

    @Test
    void nothingPlacedMeansNoMoveAndAtLeastOneDrone() {
        assertEquals(List.of(), DroneChoreographer.assign(3, List.of()));
        assertEquals(List.of(new DroneMove(0, new IntPos(1, 2, 3))), DroneChoreographer.assign(0, List.of(new IntPos(1, 2, 3))));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.DroneChoreographerTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`DroneChoreographer.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Which show drone flies where this tick (N-27): the placed positions dealt out in turn; each drone ends at its last. */
public final class DroneChoreographer {
    /** How high above the block it "places" a show drone hovers. */
    public static final double HOVER_BLOCKS = 1.5;

    private DroneChoreographer() {
    }

    public static List<DroneMove> assign(int droneCount, List<IntPos> touched) {
        int drones = Math.max(1, droneCount);
        Map<Integer, IntPos> last = new TreeMap<>();
        for (int i = 0; i < touched.size(); i++) {
            last.put(i % drones, touched.get(i));
        }
        List<DroneMove> out = new ArrayList<>();
        last.forEach((drone, pos) -> out.add(new DroneMove(drone, pos)));
        return out;
    }
}
```
`DroneMove`: recordのみ。`DroneShow`は上の「Produces」のとおり(ジョブID→ドローンのUUIDの一覧を持つ。`droneCount`は`ConstructionBudget.droneCount(job.total(), ConstructionConfig.budget())`)。

`tools/p4/scenarios_show.py`の`drone-show`(条件1): `hut-golden`と同じ手順で建てる間、`/build/entities {tag: micradrone_build_show}`を`POLL_INTERVAL_S`ごとに記録し(`drones.json`)、**建てている間に1機以上・`droneCount`機以下、`VERIFIED`の後は0機**。建てている途中で`/client/screenshot`(`drone-mid.png`: ドローンが小屋の上にいる画面。ペルソナの視覚判定に使う)。2つ目の小屋を建てている途中で`run-command "kill @e[tag=micradrone_build_show]"`し、それでも`VERIFIED`になること(演出が消えてもジョブは進む)。

- [ ] **Step 4: テストと自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。
Run: `python -m tools.p4.p4_scenarios --only drone-show --mode sp` → `PASS`。証拠`drone-show/drones.json`・`drone-show/drone-mid.png`。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/DroneChoreographerTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 施工のドローン演出(DroneChoreographer・DroneShow。保存しない・消えてもジョブは進む)と自動確認のシナリオを追加(自然言語→工場建設 P4 Task 20)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 20の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 20)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 21: 建てた後の点検(`verify`・`REPAIR`ジョブ)と、L7・整地・安全枠・調査の固定の自動確認

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

完了条件2(壊す・向きを変える→直る/直せなければ`PARTIAL`)・8・12(確認の部分)・15・16を、実機で自動に確かめる。L7は施工の完了時に走るので、**建てた後の点検**として、同じ施工リストを当て直す`REPAIR`ジョブ(承認は要らない。`03` 0.4節の例外)を`/micradrone build verify <jobId>`で作れるようにする。

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/core/JobService.java`(`beginVerify`)、`src/main/java/io/github/khayashi4337/micradrone/construction/BuildCommands.java`(`verify <jobId>`)、`src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(`verify`)、devkit(`/build/verify`)
- Create: `tools/p4/scenarios_l7.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/JobServiceVerifyTest.java`

**Interfaces:**
- Produces: `ControlResult JobService.beginVerify(String jobId, UUID requester, boolean op, String newJobId, long tick)`: 元のジョブが`VERIFIED`か`PARTIAL`で、同じ区画に動いているジョブが無いときだけ。`JobKind.REPAIR`・`parentJobId=元`・同じ`manifestHash`・同じ区画・同じ材料の方針・`total=0`・手順は空。`JobRecord`の`journal`は**このジョブ自身の新しい`Journal`**(Codexのレビュー2026-09-30: 元のジョブと記録を共有すると、同じ位置の元の設置と修復の設置が同じ番号になり、「最初の記録がある」ことが修復をした証拠にならない)。施工前の状態は、`JobRecord.useBeforesFrom(元の journal)`で元のジョブの記録から引く(`RepairPlanner`はこれを`beforeJournal()`で読む。Task 13)。修復の置き直しは、ラウンドごとの台帳のキー(`JobProgram.repair`)で、このジョブの台帳に記録し、`PlacedRegistry`は元の`before`を残したまま置いた者だけを新しくする(Task 8の`apply`の規則)。その後は通常のジョブと同じく、`QUEUED`→`RUNNING`(手順が空なので即`VERIFYING`)→L7のラウンド→`VERIFIED`/`PARTIAL`。

- [ ] **Step 1: 失敗するテストを書く**

`JobServiceVerifyTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.A;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.B;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.approved;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.in;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.service;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class JobServiceVerifyTest {
    @Test
    void aVerifyJobRepairsAFinishedBuildWithoutApprovalAndLeavesSwappedBlocks() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        IntPos broken = m.placements().get(10).pos();
        IntPos swapped = m.placements().get(11).pos();
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        w.world.setBlock(swapped, BlockSpec.of("minecraft:gold_block"));
        assertEquals(ControlResult.NOT_ALLOWED, s.beginVerify("job-1", B, false, "job-2", c.tick));
        assertEquals(ControlResult.OK, s.beginVerify("job-1", A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", in(JobState.PARTIAL));
        JobStatus st = s.status("job-2").orElseThrow();
        assertEquals(JobKind.REPAIR, st.kind());
        assertEquals(1, st.repairRound());
        assertEquals(1, st.conflicts());
        assertEquals(m.placements().get(10).block(), w.world.blockAt(broken));
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId());
        assertEquals(ControlResult.NOT_FOUND, s.beginVerify("job-9", A, false, "job-3", c.tick));
        assertEquals(25, s.record("job-1").orElseThrow().journal().size(), "the parent's journal is not written by the repair");
        assertEquals(1, s.record("job-2").orElseThrow().journal().size(), "the repair's own record of its one re-placement");
    }

    @Test
    void aSurvivalRepairChargesOnlyTheBlockItPlacesAgain() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.inventories.put(A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 17));
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        w.world.setBlock(m.placements().get(10).pos(), BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertEquals(ControlResult.OK, s.beginVerify("job-1", A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", in(JobState.VERIFIED));
        assertEquals(0, w.inventories.get(A).count("minecraft:oak_planks"), "one plank for the one broken wall block");
        assertEquals(0, w.inventories.get(A).count("minecraft:cobblestone"), "nothing else is charged again");
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.JobServiceVerifyTest" --console=plain`
Expected: FAIL(`beginVerify`が無い)。

- [ ] **Step 3: 実装する**

`JobService.beginVerify`:
```java
    public ControlResult beginVerify(String jobId, UUID requester, boolean op, String newJobId, long tick) {
        JobRecord parent = jobs.get(jobId);
        if (parent == null) {
            return ControlResult.NOT_FOUND;
        }
        ConstructionJob p = parent.job();
        if (!p.ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        boolean finished = p.state() == JobState.VERIFIED || p.state() == JobState.PARTIAL;
        boolean busyClaim = jobsInClaim(p.claimId()).stream().anyMatch(r -> !r.job().state().terminal());
        if (!finished || busyClaim) {
            return ControlResult.WRONG_STATE;
        }
        ConstructionJob job = ConstructionJob.create(newJobId, p.ownerUuid(), p.dimension(), p.manifestHash(), JobKind.REPAIR,
                p.jobId(), 0, p.claimId(), p.materialPolicy(), tick, List.of());
        JobRecord repair = new JobRecord(job, parent.manifest(), parent.nodeTypes(), new JobProgram(List.of(), List.of()),
                new Journal(Math.max(Journal.MAX_ENTRIES, parent.program().size()) * (1 + ConstructionJob.MAX_REPAIR_ROUNDS)),
                new JobOutcome(), parent.operatingBox());
        repair.useBeforesFrom(parent.journal());
        add(repair);
        return ControlResult.OK;
    }
```
コマンド`/micradrone build verify <jobId>`とランタイムの`verify(UUID requester, boolean op, String jobId)`、devkitの`/build/verify`。新しいジョブのIDは、Task 16で作ったランタイムの`nextJobId()`で作る(承認のジョブIDと同じ関数)。

`tools/p4/scenarios_l7.py`(実機の自動確認):
1. `l7-repair`(条件2・15): `hut-golden`の小屋(別の場所に`hut_patch`で)を建てて`VERIFIED`→`run-command`で(a)壁の1つを`setblock ... air`、(b)屋根の階段の1つを向きだけ変えて`setblock`(施工リストの状態を`/build/manifest`から読み、`facing`だけを変える)、(c)壁の1つを`gold_block`に、(d)扉を`open=true`にする(上下2つとも)。`/build/verify`→`PARTIAL`まで待つ(金のブロックはプレイヤーの物として残すので、ずれが残る=`PARTIAL`。Task 13)→読み戻して: (a)(b)は施工リストどおりに戻る、(c)は`gold_block`のまま・`status.conflicts == 1`・`lastError`に`conflict=1`、(d)は`open=true`のまま(稼働で変わる状態を戻さない)。(c)を元に戻してから、もう一度`/build/verify`→`VERIFIED`。
2. `l7-partial`(条件2): `/spike/protect-box`で壁の1ブロックを守り、その位置を`setblock air`で壊してから`/build/verify`→`PARTIAL`・`lastError`に`blocked=1`→`/client/chat-log`に`PARTIAL`の子供向けの文(「ほとんど できたけど…」)。
3. `terrain-slope`(条件12の確認の部分): `fill`で敷地に段差(石と土)を作り、`submit-here`→保留の`terrainCut > 0`・`terrainFill > 0`(`pending.json`)→確認なしの承認が`TERRAFORM_UNCONFIRMED`→確認つきで承認→`VERIFIED`→読み戻しの照合(`SITE_PREP`の土と空気を含む)→スクリーンショット。
4. `safety-limits`(条件8): `plans.long_road_patch(200)`で敷地の上限(128)を超える計画→保留に`E-OUT-OF-BOUNDS#size`。中身の入ったチェストを置いた位置に重なる小屋→`E-SITE-BLOCKED`(`foreign_block_entity`)で承認が`BLOCKING_ISSUES`。水の上の小屋→`confirmDestructive`なしで`DESTRUCTIVE_UNCONFIRMED`。
5. `survey-pinned`(条件16): 小屋を提出して保留(ハッシュH)→**承認の前に**、施工リストの位置の1つに`setblock stone_bricks`、敷地の中の関係ない位置に`setblock grass_block`→ハッシュHのまま承認できる(ハッシュ不一致にならない)→ジョブが`PAUSED(SITE_CHANGED)`→`status.conflicts == 1`→`/build/resume {skipConflicts: true}`→`PARTIAL`(飛ばした位置はプレイヤーの物として残るので、ずれが残る。`lastError`に`conflict=1`。Task 13)→その位置の`stone_bricks`を`setblock air`で除いてから`/build/verify`→`VERIFIED`。

- [ ] **Step 4: テストと自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。
このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件2・15・16を消す。

Run: `python -m tools.p4.p4_scenarios --only l7-repair,l7-partial,terrain-slope,safety-limits,survey-pinned --mode sp` → 5つとも`PASS`(証拠: 各シナリオの`compare.json`・`status.json`・`pending.json`・スクリーンショット・`chat.json`)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/JobServiceVerifyTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 建てた後の点検(verify・REPAIRジョブ)と、L7・整地・安全枠・調査の固定の実機の自動確認を追加(自然言語→工場建設 P4 Task 21)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 21の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 21)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

(devkitの`/build/verify`の追加は、devkitのリポジトリに別のコミット`feat: /build/verifyを追加(自然言語→工場建設 P4 Task 21)`として積む。)

---

### Task 22: 保存の土台 — 原子的な書き込みと検査値(`SealedFile`・`NioFileSystem`)、版の包み(`PersistenceEnvelope`・`Migrations`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

設計`04` F-2の「ファイルの書き込みは原子的(一時ファイル→リネーム)。検査値が合わない・欠けているファイルは、そのジョブを`PAUSED(RECOVERY_NEEDED)`」と、`01` 0節の「保存される型は`schemaVersion`を持ち、`PersistenceEnvelope`で包み、移行表で現在の版に変換する。新しい版・変換できない物は拒否する」を実装する。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{FileSystemPort,NioFileSystem,SealedFile,PersistenceEnvelope,Migrations,UnreadableFileException,JsonReads}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{SealedFileTest,NioFileSystemTest,PersistenceEnvelopeTest,MigrationsTest}.java`

**Interfaces:**
- Produces:
  - `interface FileSystemPort { Optional<byte[]> read(String relPath) throws IOException; void writeAtomic(String relPath, byte[] data) throws IOException; void delete(String relPath) throws IOException; List<String> list(String relDir) throws IOException; }`(パスは`/`区切りの相対。外へは出られない)
  - `final class NioFileSystem implements FileSystemPort`: `NioFileSystem(Path root)`、`TEMP_MARK = ".tmp-"`、`void cleanTemp()`(起動時に、途中で止まった一時ファイルを消す)、書き込みは`一時ファイルに書く→FileChannel.force(true)でディスクまで書き切る→Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`(Codexのレビュー2026-09-30: 強制しないと、停電でリネームだけが残り、中身の無いファイルになり得る)。`ATOMIC_MOVE`が使えないファイルシステム(`AtomicMoveNotSupportedException`)では、**2世代で置き換える**: 今のファイルを`<名前>.prev`(`PREVIOUS_SUFFIX`)にリネームし、一時ファイルを移し、強制してから`.prev`を消す。読むときに`.prev`があれば(置き換えが途中で止まった)、**検査値(`SealedFile`の末尾のSHA-256。最後に書かれるので、書き終わった印=コミットの印になる)が合う新しい方**を返し、新しい方が無いか壊れていれば`.prev`を返す。`list`は`.prev`を元の名前として数える。`writeAtomic`で書く物は、どれも`PersistenceEnvelope`(封をした物)。ディレクトリの強制はしない(Windowsではディレクトリを`FileChannel`で開けない。NTFSはリネームのメタデータをジャーナルに書く)。テスト用の包みのコンストラクタ`NioFileSystem(Path, BeforeMove, boolean atomicMove, Syncer)`
  - `final class SealedFile`: `MAGIC`(8バイト`"MDSEAL1\n"`)、`DIGEST_BYTES = 32`、`static byte[] seal(byte[] body)`(`MAGIC + body + SHA-256(body)`)、`static Optional<byte[]> unseal(byte[] file)`(形か検査値が合わなければ空)
  - `record PersistenceEnvelope(String type, int schemaVersion, Object payload)`: `byte[] toBytes()`(正規JSON→GZIP→`SealedFile.seal`)、`static PersistenceEnvelope fromBytes(byte[])`(検査値が合わなければ`UnreadableFileException`)
  - `final class Migrations`: `Migrations(Map<String,Integer> currentVersions)`、`Migrations step(String type, int fromVersion, UnaryOperator<Object> upgrade)`、`Object payloadOf(PersistenceEnvelope)`(古い版は順に変換、**新しい版・知らない型・移行の段が欠けた版は`UnreadableFileException`**。黙って捨てない)
  - `final class UnreadableFileException extends IOException`
  - `final class JsonReads`: `static int integer(Object, String what)`・`static long longValue(Object, String)`・`static String string(Object, String)`・`static String stringOrNull(Object, String)`・`static boolean bool(Object, String)`・`static Map<String,Object> map(Object, String)`・`static List<Object> list(Object, String)`(`MiniJson`は数を`Double`で読むので、整数かどうかをここで確かめる。形が違えば`IllegalArgumentException`)

- [ ] **Step 1: 失敗するテストを書く**

`SealedFileTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SealedFileTest {
    private static final byte[] BODY = "hello journal".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSealedBodyComesBackUnchanged() {
        assertArrayEquals(BODY, SealedFile.unseal(SealedFile.seal(BODY)).orElseThrow());
    }

    @Test
    void anyDamageIsDetected() {
        byte[] sealed = SealedFile.seal(BODY);
        byte[] flipped = sealed.clone();
        flipped[SealedFile.MAGIC.length + 2] ^= 1;
        assertTrue(SealedFile.unseal(flipped).isEmpty(), "one flipped bit");
        assertTrue(SealedFile.unseal(Arrays.copyOf(sealed, sealed.length - 1)).isEmpty(), "a torn tail");
        byte[] wrongMagic = sealed.clone();
        wrongMagic[0] = 'X';
        assertTrue(SealedFile.unseal(wrongMagic).isEmpty());
        assertTrue(SealedFile.unseal(new byte[3]).isEmpty(), "too short to hold anything");
    }
}
```

`NioFileSystemTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioFileSystemTest {
    @TempDir
    Path root;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesReplaceAtomicallyAndReadsBack() throws IOException {
        NioFileSystem fs = new NioFileSystem(root);
        fs.writeAtomic("jobs/job-1/journal.bin", b("one"));
        fs.writeAtomic("jobs/job-1/journal.bin", b("two"));
        assertArrayEquals(b("two"), fs.read("jobs/job-1/journal.bin").orElseThrow());
        assertTrue(fs.read("jobs/job-1/nothing.bin").isEmpty());
        assertEquals(List.of("jobs/job-1/journal.bin"), fs.list("jobs"));
        fs.delete("jobs/job-1/journal.bin");
        assertTrue(fs.read("jobs/job-1/journal.bin").isEmpty());
    }

    @Test
    void aCrashBetweenWriteAndMoveLeavesTheOldFileAndTheTempIsCleanedLater() throws IOException {
        NioFileSystem ok = new NioFileSystem(root);
        ok.writeAtomic("claims.bin", b("old"));
        NioFileSystem crashing = new NioFileSystem(root, () -> {
            throw new IOException("power cut before the rename");
        });
        assertThrows(IOException.class, () -> crashing.writeAtomic("claims.bin", b("new")));
        assertArrayEquals(b("old"), ok.read("claims.bin").orElseThrow(), "the old file is intact");
        try (var files = Files.list(root)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(NioFileSystem.TEMP_MARK)));
        }
        ok.cleanTemp();
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(NioFileSystem.TEMP_MARK)));
        }
    }

    @Test
    void pathsCannotLeaveTheRoot() {
        NioFileSystem fs = new NioFileSystem(root);
        for (String bad : new String[]{"../x.bin", "/etc/passwd", "C:/x", "a\\b", "a/../../x"}) {
            assertThrows(IllegalArgumentException.class, () -> fs.writeAtomic(bad, b("x")), bad);
        }
    }

    @Test
    void theTempFileIsForcedToTheDiskBeforeItIsRenamed() throws IOException {
        List<Path> synced = new ArrayList<>();
        List<Boolean> targetExistedAtSync = new ArrayList<>();
        Path target = root.resolve("claims.bin");
        NioFileSystem fs = new NioFileSystem(root, () -> {
        }, true, p -> {
            synced.add(p);
            targetExistedAtSync.add(Files.exists(target));
        });
        fs.writeAtomic("claims.bin", b("one"));
        assertTrue(synced.get(0).getFileName().toString().contains(NioFileSystem.TEMP_MARK), "the temp file is forced");
        assertEquals(false, targetExistedAtSync.get(0), "before the rename puts it in place");
    }

    @Test
    void withoutAnAtomicRenameTheLastCommittedGenerationIsRead() throws IOException {
        NioFileSystem ok = new NioFileSystem(root, () -> {
        }, false, p -> {
        });
        ok.writeAtomic("jobs/claims.bin", SealedFile.seal(b("old")));
        NioFileSystem crashing = new NioFileSystem(root, () -> {
            throw new IOException("power cut between the two renames");
        }, false, p -> {
        });
        assertThrows(IOException.class, () -> crashing.writeAtomic("jobs/claims.bin", SealedFile.seal(b("new"))));
        assertArrayEquals(b("old"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow(),
                "the file is missing, so the previous generation is read");
        assertEquals(List.of("jobs/claims.bin"), ok.list("jobs"));
        // a torn copy of a new generation is not committed (its seal fails): the previous generation is read, and kept
        Files.write(root.resolve("jobs/claims.bin"), Arrays.copyOf(SealedFile.seal(b("torn")), 12));
        assertArrayEquals(b("old"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow());
        ok.writeAtomic("jobs/claims.bin", SealedFile.seal(b("next")));
        assertArrayEquals(b("next"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow());
        assertTrue(Files.notExists(root.resolve("jobs/claims.bin" + NioFileSystem.PREVIOUS_SUFFIX)), "gone once in place");
    }
}
```

`PersistenceEnvelopeTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PersistenceEnvelopeTest {
    @Test
    void anEnvelopeRoundTripsAndRefusesDamage() throws Exception {
        PersistenceEnvelope e = new PersistenceEnvelope("job", 1, Map.of("jobId", "job-1", "cursor", 5L));
        byte[] bytes = e.toBytes();
        PersistenceEnvelope back = PersistenceEnvelope.fromBytes(bytes);
        assertEquals("job", back.type());
        assertEquals(1, back.schemaVersion());
        assertEquals("job-1", ((Map<?, ?>) back.payload()).get("jobId"));
        bytes[bytes.length / 2] ^= 1;
        assertThrows(UnreadableFileException.class, () -> PersistenceEnvelope.fromBytes(bytes));
    }
}
```

`MigrationsTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationsTest {
    @SuppressWarnings("unchecked")
    private static Object renameField(Object payload) {
        Map<String, Object> m = new HashMap<>((Map<String, Object>) payload);
        m.put("total", m.remove("count"));
        return m;
    }

    @Test
    void olderVersionsAreUpgradedAndNewerOrUnknownOnesAreRefused() {
        Migrations mig = new Migrations(Map.of("job", 2)).step("job", 1, MigrationsTest::renameField);
        assertEquals(7L, ((Map<?, ?>) assertDoesNotThrowPayload(mig, new PersistenceEnvelope("job", 1, Map.of("count", 7L))))
                .get("total"));
        assertEquals(Map.of("total", 3L), assertDoesNotThrowPayload(mig, new PersistenceEnvelope("job", 2, Map.of("total", 3L))));
        assertThrows(UnreadableFileException.class, () -> mig.payloadOf(new PersistenceEnvelope("job", 3, Map.of())),
                "a newer save read by older code is refused, not guessed");
        assertThrows(UnreadableFileException.class, () -> mig.payloadOf(new PersistenceEnvelope("ledger", 1, Map.of())));
        Migrations gap = new Migrations(Map.of("job", 3)).step("job", 1, MigrationsTest::renameField);
        assertThrows(UnreadableFileException.class, () -> gap.payloadOf(new PersistenceEnvelope("job", 1, Map.of("count", 1L))),
                "no step from 2 to 3");
    }

    private static Object assertDoesNotThrowPayload(Migrations m, PersistenceEnvelope e) {
        try {
            return m.payloadOf(e);
        } catch (UnreadableFileException ex) {
            throw new AssertionError(ex);
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*File*" --tests "io.github.khayashi4337.micradrone.construction.core.*Envelope*" --tests "io.github.khayashi4337.micradrone.construction.core.MigrationsTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`SealedFile.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

/**
 * A file body with a magic header and its SHA-256 at the end (04 F-2: "the hash or the trailing check value"), so a
 * torn, truncated or flipped file is detected on load instead of being trusted.
 */
public final class SealedFile {
    public static final byte[] MAGIC = "MDSEAL1\n".getBytes(StandardCharsets.US_ASCII);
    public static final int DIGEST_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private SealedFile() {
    }

    public static byte[] seal(byte[] body) {
        byte[] out = new byte[MAGIC.length + body.length + DIGEST_BYTES];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(body, 0, out, MAGIC.length, body.length);
        System.arraycopy(sha256(body), 0, out, MAGIC.length + body.length, DIGEST_BYTES);
        return out;
    }

    public static Optional<byte[]> unseal(byte[] file) {
        if (file.length < MAGIC.length + DIGEST_BYTES || !Arrays.equals(file, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            return Optional.empty();
        }
        byte[] body = Arrays.copyOfRange(file, MAGIC.length, file.length - DIGEST_BYTES);
        byte[] digest = Arrays.copyOfRange(file, file.length - DIGEST_BYTES, file.length);
        return MessageDigest.isEqual(digest, sha256(body)) ? Optional.of(body) : Optional.empty();
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
```

`NioFileSystem.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The construction's files under {@code <world>/data/micradrone/} (04 F-2). A write goes to a temp file first, is forced
 * to the disk, and is renamed over the target, so a crash leaves either the old or the new file, never half of one.
 * Where the file system cannot rename atomically, the old file is kept as the previous generation until the new one is
 * in place, and a read takes the newest generation whose seal checks out ({@link SealedFile}: the trailing check value
 * is written last, so it is the commit marker). Paths are relative, slash-separated, and may not climb out of the root.
 */
public final class NioFileSystem implements FileSystemPort {
    public static final String TEMP_MARK = ".tmp-";
    /** The previous generation, kept only while a non-atomic replace is under way. */
    public static final String PREVIOUS_SUFFIX = ".prev";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_\\-.]+(/[A-Za-z0-9_\\-.]+)*");

    /** A hook between writing the temp file and putting it in place; tests use it to play a crash. */
    interface BeforeMove {
        void run() throws IOException;
    }

    /** Forces a file's bytes to the disk; tests record the calls. */
    interface Syncer {
        void sync(Path file) throws IOException;
    }

    // Disk I/O: FileChannel.force(true) flushes the data and the metadata of the temp file before the rename.
    static final Syncer FORCE = file -> {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    };

    private final Path root;
    private final BeforeMove beforeMove;
    private final boolean atomicMove;
    private final Syncer syncer;

    public NioFileSystem(Path root) {
        this(root, () -> {
        }, true, FORCE);
    }

    NioFileSystem(Path root, BeforeMove beforeMove) {
        this(root, beforeMove, true, FORCE);
    }

    NioFileSystem(Path root, BeforeMove beforeMove, boolean atomicMove, Syncer syncer) {
        this.root = root.toAbsolutePath().normalize();
        this.beforeMove = beforeMove;
        this.atomicMove = atomicMove;
        this.syncer = syncer;
    }

    private Path resolve(String rel) {
        if (!SAFE.matcher(rel).matches() || rel.contains("..")) {
            throw new IllegalArgumentException("not a safe relative path: " + rel);
        }
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("outside the construction folder: " + rel);
        }
        return p;
    }

    private static Path previousOf(Path target) {
        return target.resolveSibling(target.getFileName() + PREVIOUS_SUFFIX);
    }

    private static Optional<byte[]> bytesOf(Path p) throws IOException {
        try {
            return Optional.of(Files.readAllBytes(p));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> read(String relPath) throws IOException {
        Path target = resolve(relPath);
        Optional<byte[]> now = bytesOf(target);
        Optional<byte[]> previous = bytesOf(previousOf(target));
        if (previous.isEmpty()) {
            return now;
        }
        // a non-atomic replace was cut short: the new generation counts only once its seal (the commit marker) checks out
        if (now.isPresent() && SealedFile.unseal(now.get()).isPresent()) {
            return now;
        }
        return SealedFile.unseal(previous.get()).isPresent() ? previous : now;
    }

    @Override
    public void writeAtomic(String relPath, byte[] data) throws IOException {
        Path target = resolve(relPath);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + TEMP_MARK + UUID.randomUUID());
        Files.write(tmp, data);
        syncer.sync(tmp);
        if (atomicMove) {
            beforeMove.run();
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // fall through to the two-generation replace
            }
        }
        Path previous = previousOf(target);
        if (Files.exists(target)) {
            // the current file becomes the previous generation, unless it is a torn copy left by a cut-short replace
            // (then the previous generation already there is the last committed one and stays)
            boolean committed = !Files.exists(previous) || SealedFile.unseal(Files.readAllBytes(target)).isPresent();
            if (committed) {
                Files.move(target, previous, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!atomicMove) {
            beforeMove.run();
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        syncer.sync(target);
        Files.deleteIfExists(previous);
    }

    @Override
    public void delete(String relPath) throws IOException {
        Path target = resolve(relPath);
        Files.deleteIfExists(target);
        Files.deleteIfExists(previousOf(target));
    }

    @Override
    public List<String> list(String relDir) throws IOException {
        Path dir = resolve(relDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().contains(TEMP_MARK)).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // a previous generation stands for its file (the new one may be missing after a cut-short replace)
                out.add(rel.endsWith(PREVIOUS_SUFFIX) ? rel.substring(0, rel.length() - PREVIOUS_SUFFIX.length()) : rel);
            }
        }
        return new ArrayList<>(out);
    }

    public void cleanTemp() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(p -> p.getFileName().toString().contains(TEMP_MARK)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
```
(一時ファイルの名前にも`.`と`-`が入るので、`SAFE`は`.`を許す。`..`は別に拒否する。)

`PersistenceEnvelope.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A saved value with its type and schema version (design 01, section 0): canonical JSON, gzip, then sealed. */
public record PersistenceEnvelope(String type, int schemaVersion, Object payload) {
    static final String KEY_TYPE = "type";
    static final String KEY_VERSION = "schemaVersion";
    static final String KEY_PAYLOAD = "payload";

    public PersistenceEnvelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }

    public byte[] toBytes() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put(KEY_TYPE, type);
        tree.put(KEY_VERSION, (long) schemaVersion);
        tree.put(KEY_PAYLOAD, payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(CanonicalJson.write(tree).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory gzip cannot fail", e);
        }
        return SealedFile.seal(out.toByteArray());
    }

    public static PersistenceEnvelope fromBytes(byte[] bytes) throws UnreadableFileException {
        byte[] body = SealedFile.unseal(bytes).orElseThrow(() -> new UnreadableFileException("check value mismatch"));
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
            Map<String, Object> tree = JsonReads.map(MiniJson.parse(new String(gz.readAllBytes(), StandardCharsets.UTF_8)), "envelope");
            return new PersistenceEnvelope(JsonReads.string(tree.get(KEY_TYPE), KEY_TYPE),
                    JsonReads.integer(tree.get(KEY_VERSION), KEY_VERSION), tree.get(KEY_PAYLOAD));
        } catch (IOException | IllegalArgumentException e) {
            throw new UnreadableFileException("unreadable envelope: " + e.getMessage());
        }
    }
}
```
`Migrations.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The migration table (design 01, section 0): each saved type has a current version and one upgrade step per older
 * version. A newer version, an unknown type or a missing step refuses the file with a reason; nothing is guessed.
 */
public final class Migrations {
    private final Map<String, Integer> current;
    private final Map<String, Map<Integer, UnaryOperator<Object>>> steps = new HashMap<>();

    public Migrations(Map<String, Integer> currentVersions) {
        this.current = Map.copyOf(currentVersions);
    }

    public Migrations step(String type, int fromVersion, UnaryOperator<Object> upgrade) {
        steps.computeIfAbsent(type, k -> new HashMap<>()).put(fromVersion, upgrade);
        return this;
    }

    public Object payloadOf(PersistenceEnvelope e) throws UnreadableFileException {
        Integer target = current.get(e.type());
        if (target == null) {
            throw new UnreadableFileException("unknown saved type " + e.type());
        }
        if (e.schemaVersion() > target) {
            throw new UnreadableFileException(e.type() + " v" + e.schemaVersion() + " is newer than this game's v" + target);
        }
        Object payload = e.payload();
        for (int v = e.schemaVersion(); v < target; v++) {
            UnaryOperator<Object> up = steps.getOrDefault(e.type(), Map.of()).get(v);
            if (up == null) {
                throw new UnreadableFileException("no migration of " + e.type() + " from v" + v);
            }
            payload = up.apply(payload);
        }
        return payload;
    }
}
```
`UnreadableFileException`: `public final class UnreadableFileException extends IOException { public UnreadableFileException(String m) { super(m); } }`。`JsonReads`: 上の形(整数は`Number`で、`doubleValue()`が整数でなければ例外)。

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 保存の土台(原子的な書き込み・検査値つきのファイル・版の包みと移行表)を追加(自然言語→工場建設 P4 Task 22)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 23: 保存の形(`JobCodec`・`JournalCodec`・`LedgerCodec`・`OutcomeCodec`・`ProgramCodec`・`ManifestCodec`・`ClaimCodec`・`RegistryCodec`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{BlockSpecText,JobCodec,JournalCodec,LedgerCodec,OutcomeCodec,ProgramCodec,ManifestCodec,ClaimCodec,RegistryCodec,WalCodec,SaveTypes}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{BlockSpecTextTest,CodecRoundTripTest,ManifestCodecTest,WalCodecTest}.java`

**Interfaces:**
- Produces:
  - `BlockSpecText.parse(String) → BlockSpec`(`BlockSpec.toString()`の逆。`id`または`id[k=v,...]`。形が違えば`IllegalArgumentException`)
  - `SaveTypes`: 型の名前と現在の版の表(`JOB = "job"`・`JOURNAL = "journal"`・`LEDGER = "ledger"`・`OUTCOME = "outcome"`・`PROGRAM = "program"`・`MANIFEST = "manifest"`・`CLAIMS = "claims"`・`REGISTRY = "registry"`、どれも版1。先行書き込みログ(Task 9の`WriteAheadLog`)の1件1件は封筒に包まず、`WalCodec`のフレームで持つ(ログは追記だけのファイルなので)。`ConstructionJob.SCHEMA_VERSION`・`MaterialLedger.SCHEMA_VERSION`・`PlacedRegistry.SCHEMA_VERSION`・`SiteClaim.SCHEMA_VERSION`と同じ値を参照する)、`static Migrations migrations()`
  - 各`XxxCodec`: `static Object toTree(X)`と`static X fromTree(Object)`(`PersistenceEnvelope`の`payload`になる木)。`ManifestCodec`は`record Stored(PlacementManifest manifest, Map<String,String> nodeTypes)`を扱い、**ブロックの一覧(パレット)で圧縮**(F-2の「パレット圧縮」: 同じ`BlockSpec`の文字列を1回だけ持ち、配置は番号で指す)。読み込みでは`ManifestJson.computeHash`でハッシュを計算し直し、保存されたハッシュと違えば`IllegalArgumentException`(→復旧待ち)。`ProgramCodec`は、置く手順を「施工リストの番号と台帳のキー」だけで持ち(施工リスト本体は重複させない)、読み込みで施工リストから`Placement`を引く(`fromTree(Object, PlacementManifest)`)
  - `JobCodec`の木は`ConstructionJob`の全欄(列挙は`name()`、UUIDは文字列、`null`はそのまま)。`jobs/<jobId>/job.bin`はこの木を`PersistenceEnvelope`で包んだ物(Task 24・25。前の版の`SavedData`は使わない)
  - `ClaimCodec`は`static Object toTree(ClaimBook)`と`static ClaimBook fromTree(Object, int maxPerOwner)`(上限は設定から渡す。保存しない)
  - `OutcomeCodec`は`restoreConflicts`(撤去で触らなかった位置と、Task 29の`MODIFY`が初めから触らない位置。Task 8の`JobOutcome.addRestoreConflict`)も保存する(無いと、再起動の後の`MODIFY`が、手で置き換えた位置に置いてしまう)
  - `LedgerCodec`は台帳の**量**(Task 8: 種類ごと・キーごとの品物の数)と`appliedRun`(ログのどの回まで台帳に入れたか)を保存する。読み込みは`MaterialLedger.replay`で量を戻す(払いすぎの検査をしない。保存された量がそのまま正)
  - `JournalCodec`の1件は`terrainCut`(整地で地面を切ったか。Task 8)を含む9つの欄。`WalCodec`の`PlaceIntent`も同じ形を使う(`JournalCodec.recordTree`・`recordFromTree`)
  - `WalCodec`: `static byte[] frame(WalEntry)`(4バイトの長さ+`SealedFile.seal(正規JSON)`)、`static Frames readFrames(byte[])`(`record Frames(List<WalEntry> entries, long validLength)`。**短い・検査値が合わない・形が違うフレームの所で止まり、そこまでの長さを返す**: 追記だけのファイルなので、クラッシュで壊れ得るのは最後のフレームだけ。書き手(Task 25の`FileWalSink`)は、追記の前にファイルを`validLength`まで切り詰める。切らないと、壊れた尻尾の後ろに書いた物が永久に読めない)、`toTree(WalEntry)`・`fromTree(Object)`(7種類の項目。`t`で種類を分ける)、`MAX_FRAME_BYTES = 16MB`

- [ ] **Step 1: 失敗するテストを書く**

`BlockSpecTextTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import org.junit.jupiter.api.Test;

class BlockSpecTextTest {
    @Test
    void theTextFormRoundTrips() {
        for (BlockSpec b : new BlockSpec[]{BlockSpec.AIR, BlockSpec.of("minecraft:stone"),
                BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom", "shape", "straight")}) {
            assertEquals(b, BlockSpecText.parse(b.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> BlockSpecText.parse("minecraft:stone[facing]"));
        assertThrows(IllegalArgumentException.class, () -> BlockSpecText.parse(""));
    }
}
```

`CodecRoundTripTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodecRoundTripTest {
    /** Every codec must survive the real path: tree, envelope bytes, envelope back, tree back. */
    private static Object throughBytes(String type, Object tree) throws Exception {
        PersistenceEnvelope e = PersistenceEnvelope.fromBytes(new PersistenceEnvelope(type, 1, tree).toBytes());
        return SaveTypes.migrations().payloadOf(e);
    }

    @Test
    void aJobKeepsEveryField() throws Exception {
        ConstructionJob j = ConstructionJob.create("job-1", new UUID(3, 4), "minecraft:overworld", "abc", JobKind.MODIFY, "job-0",
                25, "claim-job-0", MaterialPolicy.SURVIVAL_CONSUME, 77L, List.of("W-UNMODELED:x")).on(JobEvent.ADMITTED)
                .on(JobEvent.START).withCursor(12).paused(PauseReason.MATERIALS_MISSING).withLastError("e").withRepairRound(2);
        assertEquals(j, JobCodec.fromTree(throughBytes(SaveTypes.JOB, JobCodec.toTree(j))));
    }

    @Test
    void theJournalLedgerOutcomeClaimsAndRegistryRoundTrip() throws Exception {
        Journal journal = new Journal();
        journal.record(new JournalRecord(0, new IntPos(1, 2, 3), BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0));
        journal.record(new JournalRecord(JournalRecord.restoreIndex(0), new IntPos(1, 3, 3), BlockSpec.of("minecraft:chest"), true,
                BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));

        journal.record(new JournalRecord(5, new IntPos(1, 4, 3), BlockSpec.of("minecraft:dirt"), false,
                BlockSpec.AIR, 5, true));
        Journal journalBack = JournalCodec.fromTree(throughBytes(SaveTypes.JOURNAL, JournalCodec.toTree(journal)));
        assertEquals(journal.records(), journalBack.records());
        assertEquals(true, journalBack.at(5).orElseThrow().terrainCut(), "the ground owed is settled from the record alone");

        MaterialLedger ledger = new MaterialLedger();
        ledger.recordConsumed(0, List.of(new ItemCount("minecraft:cobblestone", 2)));
        ledger.recordReturned(0, List.of(new ItemCount("minecraft:cobblestone", 1)));
        ledger.recordYield(3, List.of(new ItemCount("minecraft:dirt", 1)));
        ledger.recordReclaimed(3, List.of(new ItemCount("minecraft:dirt", 1)));
        ledger.markAppliedRun(41L);
        MaterialLedger back = LedgerCodec.fromTree(throughBytes(SaveTypes.LEDGER, LedgerCodec.toTree(ledger)));
        assertEquals(ledger.consumedView(), back.consumedView());
        assertEquals(ledger.returnedView(), back.returnedView(), "a partial refund keeps its amount");
        assertEquals(ledger.yieldedView(), back.yieldedView());
        assertEquals(ledger.reclaimedView(), back.reclaimedView());
        assertEquals(41L, back.appliedRun(), "the log's runs up to here are in the ledger already");
        assertEquals(MaterialLedger.NO_RUN, LedgerCodec.fromTree(throughBytes(SaveTypes.LEDGER,
                LedgerCodec.toTree(new MaterialLedger()))).appliedRun());

        JobOutcome o = new JobOutcome();
        o.addConflict(new Conflict(new IntPos(0, 64, 0), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:dirt"), false, ""), ConflictKind.PLAYER_MODIFIED));
        o.skip(new SkippedPlacement(4, new IntPos(4, 64, 0), SkippedPlacement.DENIED));
        o.addRestoreConflict(new Conflict(new IntPos(2, 64, 0), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:gold_block"), false, ""), ConflictKind.PLAYER_MODIFIED));
        JobOutcome ob = OutcomeCodec.fromTree(throughBytes(SaveTypes.OUTCOME, OutcomeCodec.toTree(o)));
        assertEquals(o.conflicts(), ob.conflicts());
        assertEquals(o.skipped(), ob.skipped());
        assertEquals(true, ob.hasRestoreConflictAt(new IntPos(2, 64, 0)), "a removal conflict still blocks a placement");
        assertEquals(false, ob.hasRestoreConflictAt(new IntPos(0, 64, 0)));

        ClaimBook claims = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        claims.reserve("claim-a", new UUID(1, 1), "minecraft:overworld", new Box(0, 0, 0, 5, 5, 5), new Box(0, 0, 0, 5, 9, 5), 3L);
        claims.release("claim-a");
        assertEquals(claims.all(), ClaimCodec.fromTree(throughBytes(SaveTypes.CLAIMS, ClaimCodec.toTree(claims)),
                ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER).all());

        PlacedRegistry reg = new PlacedRegistry("claim-a");
        reg.apply("job-1", new JournalRecord(0, new IntPos(1, 2, 3), BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        assertEquals(reg.placed(), RegistryCodec.fromTree(throughBytes(SaveTypes.REGISTRY, RegistryCodec.toTree(reg))).placed());
    }

    @Test
    void aProgramStoresOnlyIndexesAndKeysForItsPuts() throws Exception {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram p = new JobProgram(List.of(new RestoreItem(new IntPos(9, 64, 9), BlockSpec.of("minecraft:stone"),
                Set.of("open"), BlockSpec.AIR, "job-0", 3, true)), JobProgram.repair(m, List.of(2, 5), 1).puts());
        assertEquals(p, ProgramCodec.fromTree(throughBytes(SaveTypes.PROGRAM, ProgramCodec.toTree(p)), m));
    }
}
```

`ManifestCodecTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestCodecTest {
    @Test
    void theGoldenHutComesBackWithTheSameHashAndItsPaletteIsSmall() throws Exception {
        PlacementManifest hut = TestManifests.hut();
        Map<String, String> types = Map.of("wall-n", "micra:wall");
        Object tree = ManifestCodec.toTree(new ManifestCodec.Stored(hut, types));
        byte[] bytes = new PersistenceEnvelope(SaveTypes.MANIFEST, 1, tree).toBytes();
        ManifestCodec.Stored back = ManifestCodec.fromTree(SaveTypes.migrations().payloadOf(PersistenceEnvelope.fromBytes(bytes)));
        assertEquals(hut, back.manifest());
        assertEquals(types, back.nodeTypes());
        assertTrue(((List<?>) ((Map<?, ?>) tree).get("palette")).size() < 40, "238 placements share a few block states");
    }

    @Test
    void aTamperedManifestIsRefused() {
        PlacementManifest hut = TestManifests.hut();
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = new java.util.LinkedHashMap<>((Map<String, Object>) ManifestCodec.toTree(
                new ManifestCodec.Stored(hut, Map.of())));
        tree.put("hash", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ManifestCodec.fromTree(tree));
    }
}
```

`WalCodecTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalCodecTest {
    private static final List<WalEntry> ALL = List.of(
            new WalEntry.RunStart("job-1", 7),
            new WalEntry.PlaceIntent("job-1", 7, new JournalRecord(3, new IntPos(1, 64, 2), BlockSpec.of("minecraft:grass_block"),
                    false, BlockSpec.of("minecraft:oak_stairs", "facing", "north"), 3, true)),
            new WalEntry.MaterialIntent("job-1", 7, new OpKey("job-1", 3, MaterialOp.CHARGE),
                    List.of(new ItemCount("minecraft:oak_stairs", 1))),
            new WalEntry.DropIntent("job-1", 7, new IntPos(2, 64, 2)),
            new WalEntry.RunEnd("job-1", 7, List.of(3), List.of(new WalEntry.OpMoves(new OpKey("job-1", 3, MaterialOp.CHARGE),
                    List.of(new Move("chest:5,64,5", "minecraft:oak_stairs", -1))))),
            new WalEntry.DurablePoint(7));

    private static byte[] file(List<WalEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WalEntry e : entries) {
            out.writeBytes(WalCodec.frame(e));
        }
        return out.toByteArray();
    }

    @Test
    void everyKindOfEntryRoundTripsThroughItsFrame() {
        byte[] f = file(ALL);
        WalCodec.Frames back = WalCodec.readFrames(f);
        assertEquals(ALL, back.entries());
        assertEquals(f.length, back.validLength());
    }

    @Test
    void aTornLastFrameIsDroppedAndItsStartIsReported() {
        byte[] whole = file(ALL);
        int goodPart = file(ALL.subList(0, ALL.size() - 1)).length;
        for (int cut = goodPart; cut < whole.length; cut++) {
            WalCodec.Frames back = WalCodec.readFrames(Arrays.copyOf(whole, cut));
            assertEquals(ALL.subList(0, ALL.size() - 1), back.entries(), "cut at " + cut);
            assertEquals(goodPart, back.validLength(), "the writer cuts the file back to here before appending");
        }
        // damage in the middle: nothing after it is trusted (the runs must be folded in order)
        int twoFrames = file(ALL.subList(0, 2)).length;
        byte[] flipped = whole.clone();
        flipped[twoFrames + WalCodec.LENGTH_BYTES + SealedFile.MAGIC.length + 1] ^= 1;
        WalCodec.Frames damaged = WalCodec.readFrames(flipped);
        assertEquals(ALL.subList(0, 2), damaged.entries(), "a damaged frame ends the log");
        assertEquals(twoFrames, damaged.validLength());
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Codec*" --tests "io.github.khayashi4337.micradrone.construction.core.BlockSpecTextTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`BlockSpecText.parse`: 最初の`[`で分け、`]`で終わることを確かめ、中身を`,`で分け、各要素を`=`で2つに分ける(どちらも空でない)。`id`は`[a-z0-9_.\-]+:[a-z0-9_./\-]+`。

`ManifestCodec`の木(キーは名前付き定数):
```
{ "manifestVersion": n, "planId": s, "planRevision": n, "registryVersion": s, "dimension": s,
  "frame": {"origin": [x,y,z], "facing": "north"}, "worldBounds": [..6..],
  "palette": ["minecraft:cobblestone", "minecraft:oak_stairs[facing=north,half=bottom]", ...],
  "nodes": ["found", "wall-n", ...],
  "placements": [[x, y, z, paletteIndex, nodeIndex, "PHASE", "PLACER", "VERIFY", "replacesCode", groupOrNull, {beConfig}], ...],
  "assemblies": [ManifestJson.assemblyTreeと同じ形。assemblyTreeはpackage-privateなので呼ばず、同じ形をこのクラスで書き直す], "bom": {...}, "hash": s, "nodeTypes": {...} }
```
読み込みは、パレットと節の番号から`Placement`を作り直し(番号は並び順)、`PhaseRanges.of`で工程の区切り、`BomCalculator.bom`で材料表を作り直して保存値と比べ、`ManifestJson.computeHash`でハッシュを計算して保存値と比べる(どれかが違えば`IllegalArgumentException("stored manifest does not reproduce its hash")`)。`AssemblyExpectation`の形は`ManifestJson`と同じ(`contraption`/`sublevel`)。

`JournalCodec`・`LedgerCodec`・`WalCodec`は下のとおり(`BlockSpec`は`toString()`/`BlockSpecText.parse`。`MiniJson`の`Map`のキーは文字列なので、台帳のキーは文字列の数)。`OutcomeCodec`: `{"conflicts": [[x,y,z,"expected","observed",hasBE,"beType","KIND"]], "restoreConflicts": [[x,y,z]], "skipped": [[index,x,y,z,"reason"]]}`(読み込みは、`restoreConflicts`に載る位置の衝突を`addRestoreConflict`で、他を`addConflict`で戻す)。`ProgramCodec`: `{"restores": [[x,y,z,"expectedNow",[volatile...],"restoreTo","sourceJobId",sourceLedgerKey,dropContents]], "puts": [[index, ledgerKey], ...]}`。`ClaimCodec`: 区画の一覧。`RegistryCodec`: `{"claimId": s, "placed": [[x,y,z,"placed","before","jobId",placementIndex,[earlierKeys...]]], "assemblies": [[groupId, assembledId, moved, atTick]]}`(`earlierKeys`は、同じジョブが同じ位置に前に書いた台帳のキー。Task 8)。`ProgramCodec`の撤去の1件は`earlierSourceKeys`も持つ(`[...,dropContents,[keys...]]`)。`JobCodec`: `ConstructionJob`の全欄。

`JournalCodec.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal as a tree: {@code [[index, x, y, z, "before", hadBlockEntity, "placed", ledgerKey, terrainCut], ...]}. One
 * record's form is shared with the write-ahead log's place intents ({@link WalCodec}).
 */
public final class JournalCodec {
    private static final int RECORD_FIELDS = 9;

    private JournalCodec() {
    }

    public static Object toTree(Journal journal) {
        List<Object> out = new ArrayList<>();
        for (JournalRecord r : journal.records()) {
            out.add(recordTree(r));
        }
        return out;
    }

    /** Every record is added again; the journal's size is set from its own maximum, not from the file. */
    public static Journal fromTree(Object tree, int maxEntries) {
        Journal j = new Journal(maxEntries);
        for (Object o : JsonReads.list(tree, "journal")) {
            j.record(recordFromTree(o));
        }
        return j;
    }

    public static Journal fromTree(Object tree) {
        return fromTree(tree, Journal.MAX_ENTRIES);
    }

    static List<Object> recordTree(JournalRecord r) {
        return List.of((long) r.placementIndex(), (long) r.pos().x(), (long) r.pos().y(), (long) r.pos().z(), r.before().toString(),
                r.beforeHadBlockEntity(), r.placed().toString(), (long) r.ledgerKey(), r.terrainCut());
    }

    static JournalRecord recordFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "journal record");
        if (a.size() != RECORD_FIELDS) {
            throw new IllegalArgumentException("a journal record has " + RECORD_FIELDS + " fields, not " + a.size());
        }
        return new JournalRecord(JsonReads.integer(a.get(0), "index"),
                new IntPos(JsonReads.integer(a.get(1), "x"), JsonReads.integer(a.get(2), "y"), JsonReads.integer(a.get(3), "z")),
                BlockSpecText.parse(JsonReads.string(a.get(4), "before")), JsonReads.bool(a.get(5), "hadBlockEntity"),
                BlockSpecText.parse(JsonReads.string(a.get(6), "placed")), JsonReads.integer(a.get(7), "ledgerKey"),
                JsonReads.bool(a.get(8), "terrainCut"));
    }
}
```
`LedgerCodec.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * A job's material ledger as a tree: the amounts of each kind by ledger key, and the newest write-ahead-log run folded
 * in. {@code {"consumed": {"key": [["item", n], ...]}, "returned": {...}, "yielded": {...}, "reclaimed": {...},
 * "appliedRun": n}} (JSON object keys are strings).
 */
public final class LedgerCodec {
    static final String KEY_APPLIED_RUN = "appliedRun";

    private LedgerCodec() {
    }

    private static String keyOf(MaterialOp op) {
        return switch (op) {
            case CHARGE -> "consumed";
            case RETURN -> "returned";
            case YIELD -> "yielded";
            case RECLAIM -> "reclaimed";
        };
    }

    private static SortedMap<Integer, List<ItemCount>> view(MaterialLedger l, MaterialOp op) {
        return switch (op) {
            case CHARGE -> l.consumedView();
            case RETURN -> l.returnedView();
            case YIELD -> l.yieldedView();
            case RECLAIM -> l.reclaimedView();
        };
    }

    public static Object toTree(MaterialLedger ledger) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (MaterialOp op : MaterialOp.values()) {
            Map<String, Object> byKey = new LinkedHashMap<>();
            view(ledger, op).forEach((key, items) -> byKey.put(Integer.toString(key), itemsTree(items)));
            out.put(keyOf(op), byKey);
        }
        out.put(KEY_APPLIED_RUN, ledger.appliedRun());
        return out;
    }

    public static MaterialLedger fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "ledger");
        MaterialLedger l = new MaterialLedger();
        for (MaterialOp op : MaterialOp.values()) {
            for (Map.Entry<String, Object> e : JsonReads.map(m.get(keyOf(op)), keyOf(op)).entrySet()) {
                l.replay(op, Integer.parseInt(e.getKey()), itemsFromTree(e.getValue()));
            }
        }
        long applied = JsonReads.longValue(m.get(KEY_APPLIED_RUN), KEY_APPLIED_RUN);
        if (applied != MaterialLedger.NO_RUN) {
            l.markAppliedRun(applied);
        }
        return l;
    }

    static List<Object> itemsTree(List<ItemCount> items) {
        List<Object> out = new ArrayList<>();
        for (ItemCount c : items) {
            out.add(List.of(c.itemId(), (long) c.count()));
        }
        return out;
    }

    static List<ItemCount> itemsFromTree(Object o) {
        List<ItemCount> out = new ArrayList<>();
        for (Object x : JsonReads.list(o, "items")) {
            List<Object> a = JsonReads.list(x, "item count");
            out.add(new ItemCount(JsonReads.string(a.get(0), "item"), JsonReads.integer(a.get(1), "count")));
        }
        return out;
    }
}
```
`WalCodec.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The write-ahead log's entries as trees, and its file as frames: each frame is a 4-byte big-endian length and a
 * {@link SealedFile} of the entry's canonical JSON. The log file is only ever appended to, so a crash can only tear its
 * last frame; reading stops at the first frame that is short or fails its check value, and reports where the good part
 * ends so the writer can cut the torn tail off before it appends again (Task 25).
 */
public final class WalCodec {
    public static final int LENGTH_BYTES = Integer.BYTES;
    /** No single entry comes near this; a larger length can only be a torn or foreign file. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    static final String KEY_TYPE = "t";
    static final String T_START = "start";
    static final String T_PLACE = "place";
    static final String T_MATERIAL = "material";
    static final String T_DROP = "drop";
    static final String T_END = "end";
    static final String T_DURABLE = "durable";

    /** The entries of the good part of a log file, and that part's length in bytes. */
    public record Frames(List<WalEntry> entries, long validLength) {
        public Frames {
            entries = List.copyOf(entries);
        }
    }

    private WalCodec() {
    }

    public static byte[] frame(WalEntry e) {
        byte[] sealed = SealedFile.seal(CanonicalJson.write(toTree(e)).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(LENGTH_BYTES + sealed.length).putInt(sealed.length).put(sealed).array();
    }

    public static Frames readFrames(byte[] file) {
        List<WalEntry> out = new ArrayList<>();
        int at = 0;
        while (file.length - at >= LENGTH_BYTES) {
            int len = ByteBuffer.wrap(file, at, LENGTH_BYTES).getInt();
            if (len <= 0 || len > MAX_FRAME_BYTES || file.length - at - LENGTH_BYTES < len) {
                break;
            }
            Optional<byte[]> body = SealedFile.unseal(Arrays.copyOfRange(file, at + LENGTH_BYTES, at + LENGTH_BYTES + len));
            if (body.isEmpty()) {
                break;
            }
            try {
                out.add(fromTree(MiniJson.parse(new String(body.get(), StandardCharsets.UTF_8))));
            } catch (IllegalArgumentException e) {
                break;
            }
            at += LENGTH_BYTES + len;
        }
        return new Frames(out, at);
    }

    public static Object toTree(WalEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (e) {
            case WalEntry.RunStart s -> {
                m.put(KEY_TYPE, T_START);
                m.put("job", s.jobId());
                m.put("run", s.run());
            }
            case WalEntry.PlaceIntent p -> {
                m.put(KEY_TYPE, T_PLACE);
                m.put("job", p.jobId());
                m.put("run", p.run());
                m.put("record", JournalCodec.recordTree(p.record()));
            }
            case WalEntry.MaterialIntent mi -> {
                m.put(KEY_TYPE, T_MATERIAL);
                m.put("job", mi.jobId());
                m.put("run", mi.run());
                m.put("key", keyTree(mi.key()));
                m.put("items", LedgerCodec.itemsTree(mi.items()));
            }
            case WalEntry.DropIntent d -> {
                m.put(KEY_TYPE, T_DROP);
                m.put("job", d.jobId());
                m.put("run", d.run());
                m.put("pos", List.of((long) d.pos().x(), (long) d.pos().y(), (long) d.pos().z()));
            }
            case WalEntry.RunEnd end -> {
                m.put(KEY_TYPE, T_END);
                m.put("job", end.jobId());
                m.put("run", end.run());
                List<Object> written = new ArrayList<>();
                end.written().forEach(i -> written.add((long) i));
                m.put("written", written);
                List<Object> moves = new ArrayList<>();
                for (WalEntry.OpMoves om : end.moves()) {
                    List<Object> ms = new ArrayList<>();
                    for (Move mv : om.moves()) {
                        ms.add(List.of(mv.sourceId(), mv.itemId(), (long) mv.delta()));
                    }
                    moves.add(List.of(keyTree(om.key()), ms));
                }
                m.put("moves", moves);
            }
            case WalEntry.DurablePoint d -> {
                m.put(KEY_TYPE, T_DURABLE);
                m.put("upTo", d.upToRun());
            }
        }
        return m;
    }

    public static WalEntry fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "log entry");
        String t = JsonReads.string(m.get(KEY_TYPE), KEY_TYPE);
        return switch (t) {
            case T_START -> new WalEntry.RunStart(str(m, "job"), run(m, "run"));
            case T_PLACE -> new WalEntry.PlaceIntent(str(m, "job"), run(m, "run"), JournalCodec.recordFromTree(m.get("record")));
            case T_MATERIAL -> new WalEntry.MaterialIntent(str(m, "job"), run(m, "run"), keyFromTree(m.get("key")),
                    LedgerCodec.itemsFromTree(m.get("items")));
            case T_DROP -> {
                List<Object> p = JsonReads.list(m.get("pos"), "pos");
                yield new WalEntry.DropIntent(str(m, "job"), run(m, "run"), new IntPos(JsonReads.integer(p.get(0), "x"),
                        JsonReads.integer(p.get(1), "y"), JsonReads.integer(p.get(2), "z")));
            }
            case T_END -> {
                List<Integer> written = new ArrayList<>();
                for (Object o : JsonReads.list(m.get("written"), "written")) {
                    written.add(JsonReads.integer(o, "written index"));
                }
                List<WalEntry.OpMoves> moves = new ArrayList<>();
                for (Object o : JsonReads.list(m.get("moves"), "moves")) {
                    List<Object> pair = JsonReads.list(o, "op moves");
                    List<Move> ms = new ArrayList<>();
                    for (Object x : JsonReads.list(pair.get(1), "moves")) {
                        List<Object> a = JsonReads.list(x, "move");
                        ms.add(new Move(JsonReads.string(a.get(0), "source"), JsonReads.string(a.get(1), "item"),
                                JsonReads.integer(a.get(2), "delta")));
                    }
                    moves.add(new WalEntry.OpMoves(keyFromTree(pair.get(0)), ms));
                }
                yield new WalEntry.RunEnd(str(m, "job"), run(m, "run"), written, moves);
            }
            case T_DURABLE -> new WalEntry.DurablePoint(run(m, "upTo"));
            default -> throw new IllegalArgumentException("unknown log entry " + t);
        };
    }

    private static String str(Map<String, Object> m, String k) {
        return JsonReads.string(m.get(k), k);
    }

    private static long run(Map<String, Object> m, String k) {
        return JsonReads.longValue(m.get(k), k);
    }

    private static List<Object> keyTree(OpKey k) {
        return List.of(k.jobId(), (long) k.ledgerKey(), k.op().name());
    }

    private static OpKey keyFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "op key");
        return new OpKey(JsonReads.string(a.get(0), "job"), JsonReads.integer(a.get(1), "ledger key"),
                MaterialOp.valueOf(JsonReads.string(a.get(2), "op")));
    }
}
```

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ジョブ・記録・台帳・手順・施工リスト(パレット圧縮とハッシュの再計算)・区画の保存の形を追加(自然言語→工場建設 P4 Task 23)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 24: ジョブのファイル(`JobFiles`)と復旧の判断(`RecoveryPlanner`)、孤児の掃除(`OrphanSweep`)、復旧の操作(`JobService.recover`)

**担当: Devin**(Java。コマンドはPowerShellで1回に1つ)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobFiles,JobLoad,RecoveryDecision,RecoveryPlanner,OrphanSweep,RecoveryChoice}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/core/JobService.java`(`addBroken`・`recover`・壊れたジョブの状態表示・**起動時の先行書き込みログからの復旧**`recoverAll`・持ち主の答え)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/JobRecord.java`(復旧待ちの印・答えを待つログの一部・失われたかもしれないドロップ)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{JobFilesTest,RecoveryPlannerTest,OrphanSweepTest,JobServiceRecoverTest,JobServiceWalRecoveryTest,InMemoryFileSystem}.java`

(前の版の`AdoptPass`(承認の時の調査と今の世界を比べ、予定どおりのブロックがあれば自分の物として引き取る)は**やめた**。Codexのレビュー2026-09-30: プレイヤーが同じブロックを置いた位置(なりすまし)も引き取ってしまい、ロールバックで他人の物を撤去する。代わりに、Task 9の先行書き込みログ(`WalRecovery`)が「このジョブが書くと宣言し、書いた」位置だけを記録に戻す。承認の時の調査のファイル`survey.bin`も要らなくなった。)

**Interfaces:**
- Consumes: Task 22・23の型
- Produces:
  - ファイルの置き場(`04` F-2。根は`<world>/data/micradrone/`): `manifests/<hash>.bin`(内容で名前が決まる。同じ施工リストは1つ)、`jobs/<jobId>/journal.bin`・`ledger.bin`・`outcome.bin`・`program.bin`(**手順が標準の形と違うときは必ず**: `MODIFY`・`ROLLBACK`はいつも。`BUILD`は`JobProgram.build(manifest)`、`REPAIR`は空の手順が標準で、同じなら書かない)・`job.bin`(ジョブの小さな状態。`JobCodec`。**ジョブを作った瞬間に**、最初の実行の前に書く: 知らないジョブの実行がログに残らないため。前の版の`SavedData`(`ConstructionJobStore`)は使わない)・`pending_log.bin`(**持ち主の答えを待つジョブだけ**: そのジョブのログの、最後の確定点より後の部分。`WalCodec`のフレーム。答えが出たら消す)、`claims.bin`、`claims/<claimId>/placed.bin`、`wal/`(Task 9の先行書き込みログのセグメント。Task 25の`FileWalSink`)。修復の途中の手順は保存しない(Codexの3回目のレビュー2026-09-30: 前の版の`repair.bin`はやめた。修復の途中で落ちたジョブは、新しいL7の検査で世界から失われた修復を見つけ直し、同じラウンドの台帳のキーで、既に払った分を二度取らずに置き直す)。`JobFiles.MANIFESTS_DIR`などの名前付き定数。
  - `final class JobFiles`: `JobFiles(FileSystemPort fs)`、`void saveJob(JobRecord r, LedgerBook ledgers) throws IOException`(上の規則で`job.bin`・`program.bin`も。`pending_log.bin`は`JobRecord`が答えを待つ間だけ書き、それ以外は消す)、`void saveClaims(ClaimBook)`・`void saveRegistry(PlacedRegistry)`、`JobLoad loadJob(ConstructionJob saved, ClaimBook claims)`(`operatingBox`は区画から取る。ジョブの保存には持たないため。区画が無ければ`Broken`で理由`"claim: missing"`。P4レビューB-7。`pending_log.bin`があれば、`JobRecord`の答えを待つログの一部として戻す)、`Optional<ClaimBook> loadClaims(int maxPerOwner)`、`Optional<PlacedRegistry> loadRegistry(String claimId)`、`List<String> allFiles()`
  - `sealed interface JobLoad { Loaded(JobRecord record, MaterialLedger ledger), Broken(PlacementManifest manifestOrNull, Map<String,String> nodeTypes, List<String> reasons) }`
  - `record RecoveryDecision(ConstructionJob job, JobLoad load, boolean fromLog)`、`RecoveryPlanner.decide(ConstructionJob saved, JobLoad load) → RecoveryDecision`(`fromLog`は、読めたジョブの`PENDING_APPROVAL`以外の全部。終わったジョブも。ただし`VERIFIED`・`PARTIAL`・`ROLLED_BACK`は確定点の後にしか終わらない(Task 13)ので、ログに残るのは取消・失敗のジョブの分だけ)、`static boolean settledUpTo(JobRecord, int cursor)`(置く手順は台帳のキーで記録を、施工リストの番号で飛ばした記録を引く。撤去は手順の位置で)
  - `JobRecord`に: `boolean recoveryPending()`・`void requireRecovery()`・`List<IntPos> possiblyLostDrops()`
  - `JobService`に: `RecoveryReport recoverAll(JobWorld)`(**起動時に1回、最初のtickの前に**。下の「先行書き込みログからの復旧」)、`boolean awaitsAnswer(String jobId)`、`record RecoveryReport(Map<String,List<WalEntry>> pendingLogs, List<String> reasons)`
  - `OrphanSweep.forgettableJobs(Collection<ConstructionJob>, ClaimBook) → Set<String>`(区画が解放された、終わったジョブ)、`OrphanSweep.orphanFiles(List<String> files, Collection<ConstructionJob> kept, ClaimBook) → List<String>`
  - `enum RecoveryChoice {ADOPT, DISCARD, REPAIR, FAIL}`、`JobService.addBroken(ConstructionJob job, PlacementManifest manifestOrNull, Map<String,String> nodeTypes, Box operatingBox, List<String> reasons)`、`ControlResult JobService.recover(String jobId, UUID requester, boolean op, RecoveryChoice choice, String newJobId, long tick, JobWorld w)`(`ADOPT`・`DISCARD`=答えを待つログを、全部「あった」・全部「なかった」として取り込む(Task 9の`WalRecovery.Resolution`)。`FAIL`=`FAILED`にして手動の後始末に任せる。`REPAIR`=施工リストが読めるときだけ、元を`FAILED`にし、新しい記録で`REPAIR`ジョブ(`beginVerify`と同じ形。記録が壊れたので施工前の状態は分からず、置き換えられた位置は`Conflict`に倒れる)を作る。F-2の(a)(b))

- **保存の順序の事実**(P4レビューD-2で直した。出どころは`build/moddev/artifacts/neoforge-21.1.238-sources.jar`):
  - 呼ぶ順は、プレイヤー(`PlayerList.saveAll`。`PlayerDataStorage.save`は**同期**で書く。`PlayerDataStorage.java` 33〜41行)→各ディメンションの`ServerLevel.save`(`saveLevelData`で`SavedData`→チャンク→`LevelEvent.Save`。`ServerLevel.java` 810〜833行、`LevelEvent.Save`は828行)。
  - しかし**ディスクに届く順は、呼ぶ順と同じではない**: `SavedData`の書き込みは`IOUtilities.withIOWorker`で`Util.ioPool()`に回り(`SavedData.java` 36〜55行、`IOUtilities.java` 195〜197行)、待つのは`flush`が真のときだけで、自動保存は偽を渡す(`MinecraftServer.java` 944行)。チャンクも非同期に書かれ、さらに**チャンクが読み込みから外れた時にも保存される**(`ChunkMap.java` 440〜466行・743〜765行)。
  - だから、落ちた後には次のどれも起こりうる: (a)記録(`journal`)がカーソルより進んでいる、(b)記録がカーソルより遅れている、(c)**世界(チャンク)が、記録と設置の記録(`PlacedRegistry`)より進んでいる**。(c)を放っておくと、このプロジェクトが置いたブロックが「よその物」に見え、`ReplaceRules`が断って`SITE_CHANGED`と偽の`Conflict`になり(子供に「だれかが かえたよ」と誤って伝わる)、ロールバックもそれを撤去しない(D-25に反する)。
  - 材料: プレイヤーは同期で先に書かれ、台帳は後で`LevelEvent.Save`で書くので、「台帳が持ち物より新しい」ことは起きない(台帳に消費の記録があれば、持ち物はそれより前の保存か同じ)。**台帳が遅れて持ち物が進んでいる**ことは起こりうる(持ち物から取った後、台帳を書く前に落ちた)が、その位置は再開の時に置き直して、もう1回取る。これは「複製」ではなく「1個多く取る」側の誤りで、F-7の「複製を作らない」は守られる。
- **先行書き込みログからの復旧**((a)(b)(c)への対策。Task 9の`WalRecovery`。**控えめな復旧の原則**: 推測しない。証拠で決まる物だけを取り込み、決まらない物は持ち主に聞く。世界から導き直せる物(L7の検査)は、ログを再生せずに導き直す。Codexの3回目のレビュー2026-09-30): ランタイムは、起動時(`ServerStartedEvent`)に、読み込んだ全ジョブを`JobService.recoverAll(JobWorld)`に通してから、最初のtickに入る。(1)ログを1回だけ読む(きれいに止まったサーバーのログは確定点しか無く、すぐ終わる)。アダプタの`JobWorld`は、読む位置のチャンクをその場で読み込み、持ち主の保存された取引ID(`durableTx`)をプレイヤーのデータのファイルから読む(持ち主がいなくてよい)。(2)ジョブごとに`WalRecovery.recover`: 最後の確定点より後の、終わった回の書き込みは、世界が予定どおりのブロックを見せる所だけ(と、同じ位置のそれより前の書き込み)を取り込み、見せない所は、カーソルを`rewindTo`まで戻して、ふだんの予算の中で置き直す(台帳のキーが同じなので二度取らない)。持ち物の動きは取引IDで決める。**途中で切れた回と、補給チェストを動かした回は、決めない**: そのジョブはログのその部分を`pending_log.bin`に持って`PAUSED(RECOVERY_NEEDED)`(終わったジョブは状態を保ち、区画に新しい仕事を入れない)。持ち主が`/micradrone build recover <jobId> adopt|discard|repair`で答える。(3)修復のラウンドの途中だったジョブは、修復の手順を保存しない: 再開すると新しいL7の検査が世界から失われた分を見つけ、同じラウンドのまま(`recoveredRound`)置き直す。(4)生きたジョブは、そのあと`settledUpTo(カーソル)`を確かめ、足りなければ`PAUSED(RECOVERY_NEEDED)`。(5)全部のジョブの後、ランタイムは**起動時のチェックポイント**(フラッシュつきの保存。Task 25)をして、取り込んだ状態をファイルに書き、ログを確定点だけにする。だから、次に落ちても同じ回を二度取り込まない。仕事の量は、最後の確定点から後のログの長さで決まり(確定点の間隔で上限がある)、置き直しはふだんの1tickの予算で行う。
- 復旧の判断(**これが正本**。F-2): (1)ファイルが読めない・検査値が合わない・施工リストがハッシュを再現しない・区画が無い→動いていたジョブは`PAUSED(RECOVERY_NEEDED)`、終わったジョブは状態を保ち理由だけ記録(`fromLog=false`)。(2)読めたジョブ(`PENDING_APPROVAL`以外。終わったジョブも)は`fromLog=true`。**カーソルより前のどの位置も「記録がある」か「飛ばした記録がある」**(Task 8の不変条件)かの確認は、ログからの復旧の後に`JobService`が行う(上)。記録がカーソルより先に進んでいるのは正常で、古いカーソルから冪等に進み、台帳が二重の消費を防ぐ。(3)`RUNNING`・`VERIFYING`・`REPAIRING`・`ASSEMBLING`・`QUEUED`は`PAUSED(OWNER_OFFLINE)`に(`04` F-2の「RUNNINGだったジョブはPAUSEDで復元」。所有者が戻れば次のtickで自動に再開=10秒以内)。`PENDING_APPROVAL`・`PAUSED`・終わった状態はそのまま。

- [ ] **Step 1: 失敗するテストを書く**

`InMemoryFileSystem.java`(テスト用):
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** An in-memory FileSystemPort for the persistence tests. */
public final class InMemoryFileSystem implements FileSystemPort {
    public final TreeMap<String, byte[]> files = new TreeMap<>();

    @Override
    public Optional<byte[]> read(String relPath) {
        byte[] b = files.get(relPath);
        return b == null ? Optional.empty() : Optional.of(b.clone());
    }

    @Override
    public void writeAtomic(String relPath, byte[] data) {
        files.put(relPath, data.clone());
    }

    @Override
    public void delete(String relPath) {
        files.remove(relPath);
    }

    @Override
    public List<String> list(String relDir) {
        List<String> out = new ArrayList<>();
        for (String k : files.keySet()) {
            if (k.startsWith(relDir.endsWith("/") ? relDir : relDir + "/")) {
                out.add(k);
            }
        }
        return out;
    }
}
```

`JobFilesTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobFilesTest {
    /** A service whose job-1 has run one tick (its claim reserved, some blocks placed). */
    static JobService halfway(FakeJobWorld w) {
        JobService s = JobServiceTest.service();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        s.admitApproved(JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.CREATIVE_FREE), m,
                Map.of("wall", "micra:wall"), JobProgram.build(m), m.worldBounds());
        s.tick(new TickInput(0, JobServiceTest.CALM), w);
        return s;
    }

    static JobRecord builtHalfway(FakeJobWorld w) {
        return halfway(w).record("job-1").orElseThrow();
    }

    @Test
    void aJobSavesToItsFilesAndLoadsBackTheSame() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        files.saveJob(r, new LedgerBook());
        assertTrue(fs.files.containsKey("manifests/" + r.manifest().hash() + ".bin"));
        assertTrue(fs.files.containsKey("jobs/job-1/journal.bin"));
        assertFalse(fs.files.containsKey("jobs/job-1/program.bin"), "a BUILD's program is the manifest's, rebuilt at load");
        assertTrue(fs.files.containsKey("jobs/job-1/job.bin"), "the job itself: no SavedData list any more");
        assertFalse(fs.files.containsKey("jobs/job-1/pending_log.bin"), "no answer is awaited");
        JobLoad.Loaded back = assertInstanceOf(JobLoad.Loaded.class, files.loadJob(r.job(), s.claims()));
        assertEquals(r.journal().records(), back.record().journal().records());
        assertEquals(r.manifest(), back.record().manifest());
        assertEquals(Map.of("wall", "micra:wall"), back.record().nodeTypes());
        assertEquals(r.operatingBox(), back.record().operatingBox(), "the operating box comes from the claim");
    }

    @Test
    void aJobWaitingForItsOwnersAnswerKeepsItsPartOfTheLog() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        List<WalEntry> part = List.of(new WalEntry.RunStart("job-1", 7), new WalEntry.PlaceIntent("job-1", 7,
                new JournalRecord(3, r.manifest().placements().get(3).pos(), BlockSpec.AIR, false,
                        r.manifest().placements().get(3).block(), 3)));
        r.pendingLog = part;
        files.saveJob(r, new LedgerBook());
        assertTrue(fs.files.containsKey("jobs/job-1/pending_log.bin"));
        JobLoad.Loaded back = assertInstanceOf(JobLoad.Loaded.class, files.loadJob(r.job(), s.claims()));
        assertEquals(part, back.record().pendingLog, "the answer is still possible after a restart");
        r.pendingLog = List.of();
        files.saveJob(r, new LedgerBook());
        assertFalse(fs.files.containsKey("jobs/job-1/pending_log.bin"));
    }

    @Test
    void aMissingOrDamagedFileOrAMissingClaimIsBrokenWithAReason() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        files.saveJob(r, new LedgerBook());
        fs.files.remove("jobs/job-1/journal.bin");
        JobLoad.Broken missing = assertInstanceOf(JobLoad.Broken.class, files.loadJob(r.job(), s.claims()));
        assertTrue(missing.reasons().get(0).contains("journal"), missing.reasons().toString());
        assertEquals(r.manifest(), missing.manifestOrNull(), "the manifest is still usable for a repair");
        files.saveJob(r, new LedgerBook());
        byte[] ledger = fs.files.get("jobs/job-1/ledger.bin");
        ledger[ledger.length - 1] ^= 1;
        assertInstanceOf(JobLoad.Broken.class, files.loadJob(r.job(), s.claims()));
        files.saveJob(r, new LedgerBook());
        JobLoad.Broken noClaim = assertInstanceOf(JobLoad.Broken.class,
                files.loadJob(r.job(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER)));
        assertTrue(noClaim.reasons().contains("claim: missing"), noClaim.reasons().toString());
    }
}
```

`JobServiceWalRecoveryTest.java`(落ちた後のディスクを純Javaで作る: 保存されたファイルは古く、先行書き込みログは新しく、世界はどちらより古くも新しくもあり得る。起動時の`recoverAll`と、それに続く起動時のチェックポイント(`markDurable`)):
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Start-up recovery in pure Java: the saved files (journal, registry, cursor) are older than the durable log, and the
 * world may be older or newer than both (chunks are saved asynchronously and on unload). recoverAll runs before the
 * first tick; the runtime's start-up checkpoint follows (markDurable).
 */
class JobServiceWalRecoveryTest {
    private static final PlacementManifest M = TestManifests.smallHut();
    private static final String JOB = "job-1";

    static JobService service(WriteAheadLog wal) {
        return new JobService(BudgetConfig.defaults(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER),
                BuildingParts.registry(), true, false, wal);
    }

    private static JournalRecord record(PutItem item) {
        return new JournalRecord(item.ledgerKey(), item.placement().pos(), BlockSpec.AIR, false, item.placement().block(),
                item.ledgerKey());
    }

    /**
     * The disk after a crash: the world holds placements 0..inWorld-1, the saved journal 0..journaled-1, the saved job
     * {@code saved}; the log holds one run with intents for logFrom..logTo-1, ended (with them all written) or cut short.
     */
    private static JobService recovered(FakeJobWorld w, int inWorld, int journaled, ConstructionJob saved, int logFrom, int logTo,
                                        boolean runEnded) {
        JobProgram program = JobProgram.build(M);
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        sink.durable.add(new WalEntry.RunStart(saved.jobId(), 1));
        List<Integer> written = new ArrayList<>();
        for (int i = logFrom; i < logTo; i++) {
            sink.durable.add(new WalEntry.PlaceIntent(saved.jobId(), 1, record(program.put(i))));
            written.add(program.put(i).ledgerKey());
        }
        if (runEnded) {
            sink.durable.add(new WalEntry.RunEnd(saved.jobId(), 1, written, List.of()));
        }
        JobService s = service(WriteAheadLog.open(sink));
        for (int i = 0; i < inWorld; i++) {
            Placement p = M.placements().get(i);
            w.world.setBlock(p.pos(), p.block());
        }
        Journal journal = new Journal();
        for (int i = 0; i < journaled; i++) {
            JournalRecord rec = record(program.put(i));
            journal.record(rec);
            s.registry("claim-job-1").apply(saved.jobId(), rec);
        }
        add(s, saved, program, journal);
        return s;
    }

    private static void add(JobService s, ConstructionJob saved, JobProgram program, Journal journal) {
        RecoveryDecision d = RecoveryPlanner.decide(saved, new JobLoad.Loaded(
                new JobRecord(saved, M, Map.of(), program, journal, new JobOutcome(), M.worldBounds()), new MaterialLedger()));
        JobRecord r = new JobRecord(d.job(), M, Map.of(), program, journal, new JobOutcome(), M.worldBounds());
        if (d.fromLog()) {
            r.requireRecovery();
        }
        s.add(r);
    }

    /** What the runtime does at start-up: recover every job, then the checkpoint. */
    private static JobService.RecoveryReport startUp(JobService s, FakeJobWorld w) {
        JobService.RecoveryReport report = s.recoverAll(w);
        s.markDurable();
        return report;
    }

    private static ConstructionJob running(String id, int cursor) {
        return JobServiceTest.approved(id, JobServiceTest.A, M, MaterialPolicy.CREATIVE_FREE).withClaimId("claim-job-1")
                .on(JobEvent.ADMITTED).on(JobEvent.START).withCursor(cursor);
    }

    private static long places(FakeJobWorld w) {
        return w.world.log.stream().filter(l -> l.startsWith("place ")).count();
    }

    private static FakeJobWorld world() {
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        return w;
    }

    @Test
    void theWorldAheadOfTheSavedJournalIsTakenFromTheLogNotReportedAsAConflict() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 12, true);
        assertTrue(startUp(s, w).pendingLogs().isEmpty());
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        assertEquals(0, s.status(JOB).orElseThrow().conflicts(), "our own blocks are not someone else's change");
        assertEquals(M.placements().size() - 12, places(w), "logged positions are not placed again");
        assertEquals(M.placements().size(), s.registry("claim-job-1").size(), "a later rollback removes them too");
    }

    @Test
    void aLostWriteBeforeTheSavedCursorIsPlacedAgainByTheReWalkNotByARepairRound() {
        FakeJobWorld w = world();
        // the files were saved after the run (cursor 12), but the world kept only 0..9 of its writes
        JobService s = recovered(w, 10, 5, running(JOB, 12), 5, 12, true);
        startUp(s, w);
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        assertEquals(M.placements().size() - 10, places(w), "10 and 11 again, then the rest");
        assertEquals(0, s.status(JOB).orElseThrow().repairRound(), "the re-walk from the rewound cursor, not L7, put them back");
    }

    @Test
    void aRunCutShortWaitsForTheOwnersAnswerAndNeverRunsOnItsOwn() {
        FakeJobWorld w = world();
        // the run was cut short after its intents for 5..7; 8..11 show the planned blocks too, but no intent names them
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 8, false);
        JobService.RecoveryReport report = startUp(s, w);
        assertEquals(List.of(JOB), List.copyOf(report.pendingLogs().keySet()), "its log part is kept for the answer");
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 3; i++) {
            c.step(s, w);
        }
        assertEquals(PauseReason.RECOVERY_NEEDED, s.status(JOB).orElseThrow().shownPause());
        assertEquals(0, places(w), "nothing is guessed, nothing is placed");
        assertEquals(ControlResult.OK, s.recover(JOB, JobServiceTest.A, false, RecoveryChoice.ADOPT, null, c.tick, w));
        assertEquals(8, s.record(JOB).orElseThrow().journal().size(), "adopt: the three intents the world shows");
        assertFalse(s.registry("claim-job-1").contains(M.placements().get(8).pos()), "a look-alike without an intent stays the player's");
        c.runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.SITE_CHANGED));
        assertEquals(0, places(w));
    }

    @Test
    void discardLeavesTheCutRunsBlocksToThePlayer() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 8, false);
        startUp(s, w);
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        assertEquals(ControlResult.OK, s.recover(JOB, JobServiceTest.A, false, RecoveryChoice.DISCARD, null, c.tick, w));
        assertEquals(5, s.record(JOB).orElseThrow().journal().size());
        c.runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.SITE_CHANGED));
        assertEquals(1, s.status(JOB).orElseThrow().conflicts(), "the planks at 5 are somebody else's now");
    }

    @Test
    void aClaimWithAJobWaitingForAnAnswerTakesNoNewWork() {
        FakeJobWorld w = world();
        JobProgram program = JobProgram.build(M);
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        // a cancelled job of the same claim was cut short inside a run
        sink.durable.add(new WalEntry.RunStart("job-2", 1));
        sink.durable.add(new WalEntry.PlaceIntent("job-2", 1, record(program.put(3))));
        JobService s = service(WriteAheadLog.open(sink));
        Journal journal = new Journal();
        for (Placement p : M.placements()) {
            w.world.setBlock(p.pos(), p.block());
            JournalRecord rec = record(program.put(p.index()));
            journal.record(rec);
            s.registry("claim-job-1").apply(JOB, rec);
        }
        add(s, running(JOB, 25).on(JobEvent.PLACED_ALL).on(JobEvent.CLEAN), program, journal);
        add(s, running("job-2", 3).on(JobEvent.CANCEL), program, new Journal());
        startUp(s, w);
        assertTrue(s.awaitsAnswer("job-2"));
        assertEquals(ControlResult.WRONG_STATE, s.beginVerify(JOB, JobServiceTest.A, false, "job-3", 0L),
                "the claim's registry is not settled until the owner answers");
        assertEquals(ControlResult.OK, s.recover("job-2", JobServiceTest.A, false, RecoveryChoice.DISCARD, null, 0L, w));
        assertEquals(ControlResult.OK, s.beginVerify(JOB, JobServiceTest.A, false, "job-3", 0L));
    }

    @Test
    void positionsBeforeTheCursorThatTheLogCannotExplainStillNeedRecovery() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 8, 5, running(JOB, 10), 5, 8, true);
        startUp(s, w);
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED)
                .and(j -> j.pauseReason() == PauseReason.RECOVERY_NEEDED));
        assertEquals(0, places(w), "nothing is placed silently");
        assertEquals(8, s.record(JOB).orElseThrow().journal().size(), "what the log explained was still folded in");
    }

    @Test
    void aRepairCutShortByACrashReDerivesItsLostWriteInTheSameRoundAndChargesNothingTwice() {
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        JobService before = service(new WriteAheadLog(sink, 0L));
        FakeJobWorld w = world();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 17);
        w.inventories.put(JobServiceTest.A, mats);
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        JobServiceTest.submit(before, JobServiceTest.approved(JOB, JobServiceTest.A, M, MaterialPolicy.SURVIVAL_CONSUME), M);
        c.runUntil(before, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        IntPos broken = M.placements().get(10).pos();
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        before.beginVerify(JOB, JobServiceTest.A, false, "job-2", c.tick);
        c.runUntil(before, w, "job-2", JobServiceTest.in(JobState.REPAIRING));
        before.tick(new TickInput(c.tick++, JobServiceTest.CALM), w);
        assertEquals(0, mats.count("minecraft:oak_planks"), "the repair round placed the plank and charged it (run saved with its id)");
        JobRecord repair = before.record("job-2").orElseThrow();
        // the crash: the world never saved the repair's write; the files are as at the checkpoint (repair queue not saved)
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        FakeJobWorld back = world();
        back.world.log.clear();
        M.placements().forEach(p -> back.world.setBlock(p.pos(), w.world.blockAt(p.pos())));
        back.inventories.put(JobServiceTest.A, mats.restart());
        JobService after = service(WriteAheadLog.open(sink));
        before.claims().all().forEach(after.claims()::restore);
        after.registry("claim-job-1").putAll(before.registry("claim-job-1").placed());
        JobRecord parent = before.record(JOB).orElseThrow();
        after.add(parent);
        RecoveryDecision d = RecoveryPlanner.decide(repair.job(), new JobLoad.Loaded(repair, new MaterialLedger()));
        JobRecord loaded = new JobRecord(d.job(), M, Map.of(), repair.program(), new Journal(), new JobOutcome(), repair.operatingBox());
        loaded.useBeforesFrom(parent.journal());
        loaded.requireRecovery();
        after.add(loaded);
        LedgerBook fromFiles = after.ledgers();
        fromFiles.put(JOB, before.ledgers().of(JOB));
        startUp(after, back);
        c.runUntil(after, back, "job-2", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(M.placements().get(10).block(), back.world.blockAt(broken), "the lost repair write is placed again");
        assertEquals(0, back.inventories.get(JobServiceTest.A).count("minecraft:oak_planks"), "and not charged a second time");
        assertEquals(1, after.status("job-2").orElseThrow().repairRound(), "the same round: its ledger keys are the charge's");
    }
}
```

`RecoveryPlannerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryPlannerTest {
    private static JobLoad.Loaded loaded(JobRecord r) {
        return new JobLoad.Loaded(r, new MaterialLedger());
    }

    @Test
    void aRunningJobComesBackPausedUntilItsOwnerIsHereAndAdoptsFirst() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        RecoveryDecision d = RecoveryPlanner.decide(r.job(), loaded(r));
        assertEquals(JobState.PAUSED, d.job().state());
        assertEquals(PauseReason.OWNER_OFFLINE, d.job().pauseReason());
        assertTrue(d.fromLog(), "the log may hold what the files do not (async chunk saves)");
    }

    @Test
    void aJournalBehindTheCursorIsNotSettledUntilAdopted() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        ConstructionJob ahead = r.job().withCursor(r.job().cursor() + 3);
        assertFalse(RecoveryPlanner.settledUpTo(r, ahead.cursor()));
        assertTrue(RecoveryPlanner.decide(ahead, loaded(r)).fromLog(), "JobService recovers from the log, then checks again");
    }

    @Test
    void aJournalAheadOfTheCursorIsNormal() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        ConstructionJob behind = r.job().withCursor(Math.max(0, r.job().cursor() - 3));
        assertEquals(PauseReason.OWNER_OFFLINE, RecoveryPlanner.decide(behind, loaded(r)).job().pauseReason());
        assertTrue(RecoveryPlanner.settledUpTo(r, behind.cursor()));
    }

    @Test
    void brokenFilesPauseALiveJobAndLeaveAFinishedOneAsItIs() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        JobLoad broken = new JobLoad.Broken(r.manifest(), Map.of(), List.of("journal: check value mismatch"));
        assertEquals(PauseReason.RECOVERY_NEEDED, RecoveryPlanner.decide(r.job(), broken).job().pauseReason());
        assertFalse(RecoveryPlanner.decide(r.job(), broken).fromLog());
        PlacementManifest m = TestManifests.smallHut();
        ConstructionJob done = ConstructionJob.create("job-2", JobServiceTest.A, TestManifests.DIM, m.hash(), JobKind.BUILD, null, 25,
                "claim-job-2", MaterialPolicy.CREATIVE_FREE, 0L, List.of()).on(JobEvent.ADMITTED).on(JobEvent.START)
                .withCursor(25).on(JobEvent.PLACED_ALL).on(JobEvent.CLEAN);
        assertEquals(JobState.VERIFIED, RecoveryPlanner.decide(done, broken).job().state());
        JobRecord finished = new JobRecord(done, m, Map.of(), JobProgram.build(m), new Journal(), new JobOutcome(), m.worldBounds());
        assertTrue(RecoveryPlanner.decide(done, loaded(finished)).fromLog(),
                "a finished job too: the world may have lost its last writes");
    }
}
```

`OrphanSweepTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrphanSweepTest {
    private static ConstructionJob job(String id, String claim, String hash, JobState end) {
        ConstructionJob j = ConstructionJob.create(id, new UUID(0, 1), "minecraft:overworld", hash, JobKind.BUILD, null, 0, claim,
                MaterialPolicy.CREATIVE_FREE, 0L, List.of()).on(JobEvent.ADMITTED);
        return end == JobState.CANCELLED ? j.on(JobEvent.CANCEL) : j;
    }

    @Test
    void filesAreKeptUntilTheClaimIsReleased() {
        ClaimBook claims = new ClaimBook(8);
        claims.reserve("claim-a", new UUID(0, 1), "minecraft:overworld", new Box(0, 0, 0, 1, 1, 1), new Box(0, 0, 0, 1, 1, 1), 0L);
        claims.reserve("claim-b", new UUID(0, 1), "minecraft:overworld", new Box(9, 0, 0, 9, 1, 1), new Box(9, 0, 0, 9, 1, 1), 0L);
        claims.release("claim-b");
        ConstructionJob a = job("job-a", "claim-a", "h1", JobState.CANCELLED);
        ConstructionJob b = job("job-b", "claim-b", "h2", JobState.CANCELLED);
        assertEquals(Set.of("job-b"), OrphanSweep.forgettableJobs(List.of(a, b), claims), "a released claim's ended jobs go");
        List<String> files = List.of("jobs/job-a/journal.bin", "jobs/job-b/journal.bin", "manifests/h1.bin", "manifests/h2.bin",
                "claims/claim-a/placed.bin", "claims/claim-b/placed.bin", "claims.bin");
        assertEquals(List.of("claims/claim-b/placed.bin", "jobs/job-b/journal.bin", "manifests/h2.bin"),
                OrphanSweep.orphanFiles(files, List.of(a), claims));
    }
}
```

`JobServiceRecoverTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobServiceRecoverTest {
    @Test
    void aBrokenJobWaitsUntilSomeoneChoosesAndNeverRunsSilently() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        ConstructionJob paused = JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.CREATIVE_FREE)
                .on(JobEvent.ADMITTED).on(JobEvent.START).withCursor(10).paused(PauseReason.RECOVERY_NEEDED);
        s.claims().reserve("claim-job-1", JobServiceTest.A, TestManifests.DIM, m.worldBounds(), m.worldBounds(), 0L);
        s.addBroken(paused, m, Map.of(), m.worldBounds(), List.of("journal: missing"));
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 3; i++) {
            c.step(s, w);
        }
        assertEquals(PauseReason.RECOVERY_NEEDED, s.status("job-1").orElseThrow().shownPause());
        assertEquals(0, w.world.log.size(), "nothing was placed");
        assertEquals(ControlResult.WRONG_STATE, s.resume("job-1", JobServiceTest.A, false, false), "resume is not the way out");
        assertEquals(ControlResult.WRONG_STATE, s.recover("job-1", JobServiceTest.A, false, RecoveryChoice.ADOPT, "job-2", c.tick, w),
                "adopt and discard answer an undecided log; this job's files are broken");
        assertEquals(ControlResult.OK, s.recover("job-1", JobServiceTest.A, false, RecoveryChoice.REPAIR, "job-2", c.tick, w));
        assertEquals(JobState.FAILED, s.status("job-1").orElseThrow().state());
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(JobKind.REPAIR, s.status("job-2").orElseThrow().kind());
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`RecoveryPlanner.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a saved job becomes at load (04 F-2): unreadable files mean RECOVERY_NEEDED for a live job (never a silent
 * re-run); every other loaded job, finished ones included, is recovered from the write-ahead log first (JobService), and
 * a job that was moving comes back paused until its owner is here.
 */
public final class RecoveryPlanner {
    private static final Set<JobState> MOVING = EnumSet.of(JobState.QUEUED, JobState.RUNNING, JobState.VERIFYING,
            JobState.REPAIRING, JobState.ASSEMBLING);

    private RecoveryPlanner() {
    }

    public static RecoveryDecision decide(ConstructionJob saved, JobLoad load) {
        boolean live = !saved.state().terminal();
        if (load instanceof JobLoad.Broken) {
            return new RecoveryDecision(live ? needsRecovery(saved) : saved, load, false);
        }
        // a finished job too: the log may hold a write the world lost, or a settlement its ledger still owes
        boolean fromLog = saved.state() != JobState.PENDING_APPROVAL;
        ConstructionJob job = MOVING.contains(saved.state()) ? saved.paused(PauseReason.OWNER_OFFLINE) : saved;
        return new RecoveryDecision(job, load, fromLog);
    }

    private static ConstructionJob needsRecovery(ConstructionJob j) {
        if (j.state() == JobState.PAUSED) {
            return j.on(JobEvent.RESUME).paused(PauseReason.RECOVERY_NEEDED);
        }
        if (j.state() == JobState.PENDING_APPROVAL) {
            return j.on(JobEvent.FAIL).withLastError("unreadable files before admission");
        }
        return j.paused(PauseReason.RECOVERY_NEEDED);
    }

    /**
     * Every program position before the cursor ended in a journal record (a put's under its ledger key, a removal's under
     * its position) or a skip record (the Task 8 invariant).
     */
    public static boolean settledUpTo(JobRecord r, int cursor) {
        return settledUpTo(r, cursor, Set.of());
    }

    /**
     * The same, where the positions the write-ahead log explains count as settled too (their lost writes are placed again
     * by the re-walk). A position before the saved cursor that neither the files nor the log account for means files
     * that do not belong together: never re-run silently.
     */
    public static boolean settledUpTo(JobRecord r, int cursor, Set<Integer> explained) {
        JobProgram p = r.program();
        for (int i = 0; i < Math.min(cursor, p.size()); i++) {
            if (explained.contains(i)) {
                continue;
            }
            int journalKey = p.isRestore(i) ? JournalRecord.restoreIndex(i) : p.put(i).ledgerKey();
            int skipKey = p.isRestore(i) ? JournalRecord.restoreIndex(i) : p.put(i).index();
            if (r.journal().at(journalKey).isEmpty() && !r.outcome().isSkipped(skipKey)) {
                return false;
            }
        }
        return cursor <= p.size();
    }
}
```
(`PAUSED`から`RECOVERY_NEEDED`へは、状態機械の表に`PAUSED`→`PAUSE`の組が無いので、一度`RESUME`で`QUEUED`にしてから`PAUSE`する。どちらも表にある移り方。)

`JobRecord`に足す(フィールドと公開の読み書き。コンストラクタは変えない):
```java
    boolean recoveryPending;
    /** The log part after the last durable point that waits for the owner's answer (pending_log.bin), or empty. */
    List<WalEntry> pendingLog = List.of();
    /** Containers whose dropped contents a crash may have lost (shown by status --debug). */
    List<IntPos> possiblyLostDrops = List.of();
    boolean broken;

    public boolean recoveryPending() {
        return recoveryPending;
    }

    /** Every job loaded from the files (RecoveryDecision.fromLog): the log may hold what the files do not. */
    public void requireRecovery() {
        recoveryPending = true;
    }

    public List<IntPos> possiblyLostDrops() {
        return possiblyLostDrops;
    }
```

`JobService`に足す(`RECOVERY_AMBIGUOUS = "recovery-ambiguous:"`(`lastError`の頭。子供の文はTask 14の`RECOVERY_NEEDED`の行と、その理由の行)、`Map<String,ConstructionJob> brokenWithoutManifest`。`beginVerify`(Task 21)・`rollback`(Task 28)・`MODIFY`の承認(Task 29)は、`claimAwaitsAnswer(区画)`なら`WRONG_STATE`):
```java
    /** What start-up recovery leaves for the runtime: the ambiguous jobs' log parts (saved as pending_log.bin). */
    public record RecoveryReport(Map<String, List<WalEntry>> pendingLogs, List<String> reasons) {
        public RecoveryReport {
            pendingLogs = Map.copyOf(pendingLogs);
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Start-up recovery (04 F-2), before the first tick, synchronously: every job loaded from the files is recovered from
     * the log part after the last durable point (the adapter's world loads each position it reads; the owners' saved
     * transaction ids come from their saved data, not from a live inventory). A job the evidence cannot decide keeps its
     * part of the log and waits for its owner (RECOVERY_NEEDED; a finished job keeps its state but blocks its claim). The
     * runtime then makes a checkpoint, which writes the recovered files and drops the whole log: the next crash never
     * sees these runs again, and nothing is ever folded in twice.
     */
    public RecoveryReport recoverAll(JobWorld w) {
        List<WalEntry> log = null;
        Map<String, List<WalEntry>> pending = new TreeMap<>();
        List<String> reasons = new ArrayList<>();
        for (JobRecord r : List.copyOf(jobs.values())) {
            if (!r.recoveryPending) {
                continue;
            }
            if (log == null) {
                log = wal.durable();
            }
            WalRecovery.Result x = recoverOne(r, log, w, WalRecovery.Resolution.NONE);
            if (x.ambiguous()) {
                pending.put(r.job().jobId(), r.pendingLog);
                reasons.add(r.job().jobId() + " " + x.reasons());
            }
        }
        return new RecoveryReport(pending, reasons);
    }

    private WalRecovery.Result recoverOne(JobRecord r, List<WalEntry> log, JobWorld w, WalRecovery.Resolution answer) {
        ConstructionJob j = r.job();
        long savedTx = w.materials(j.ownerUuid(), j.materialPolicy(), j.claimId()).durableTx();
        WalRecovery.Result x = WalRecovery.recover(j.jobId(), log, r.program(), r.journal(), registry(j.claimId()), ledgers,
                w.world(j.dimension()), savedTx, answer);
        r.recoveryPending = false;
        if (x.ambiguous()) {
            r.pendingLog = WalRecovery.pendingPart(j.jobId(), log);
            r.setJob(j.withLastError(RECOVERY_AMBIGUOUS + String.join(",", x.reasons())));
            if (!j.state().terminal()) {
                pauseForRecovery(r);
            }
            return x;
        }
        r.pendingLog = List.of();
        r.possiblyLostDrops = x.possiblyLostDrops();
        if (!j.state().terminal()) {
            if (!RecoveryPlanner.settledUpTo(r, j.cursor(), x.explained())) {
                pauseForRecovery(r);
                return x;
            }
            // a re-walk from the first step after the durable point places what the world lost, charging nothing twice
            if (x.rewindTo() < j.cursor()) {
                r.setJob(r.job().withCursor(x.rewindTo()));
            }
            r.recoveredRound = j.repairRound() > 0;
        }
        return x;
    }

    private static void pauseForRecovery(JobRecord r) {
        ConstructionJob j = r.job();
        if (j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED) {
            return;
        }
        r.setJob((j.state() == JobState.PAUSED ? j.on(JobEvent.RESUME) : j).paused(PauseReason.RECOVERY_NEEDED));
    }

    /** A job whose log after the last durable point waits for its owner's answer (adopt or discard). */
    public boolean awaitsAnswer(String jobId) {
        JobRecord r = jobs.get(jobId);
        return r != null && !r.pendingLog.isEmpty();
    }

    /** The owner's answer for an ambiguous job; the runtime saves the job's files after it (pending_log.bin goes). */
    private ControlResult answer(JobRecord r, RecoveryChoice choice, JobWorld w) {
        WalRecovery.Result x = recoverOne(r, r.pendingLog, w, choice == RecoveryChoice.ADOPT ? WalRecovery.Resolution.ADOPT
                : WalRecovery.Resolution.DISCARD);
        ConstructionJob j = r.job();
        r.setJob(j.withLastError(""));
        if (j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED
                && RecoveryPlanner.settledUpTo(r, j.cursor())) {
            r.setJob(r.job().on(JobEvent.RESUME));
        }
        return x.ambiguous() ? ControlResult.WRONG_STATE : ControlResult.OK;
    }

    /** A claim with a job still waiting for its owner's answer takes no new work (its registry is not settled yet). */
    boolean claimAwaitsAnswer(String claimId) {
        return jobsInClaim(claimId).stream().anyMatch(r -> !r.pendingLog.isEmpty());
    }

    /**
     * A job whose files could not be read (F-2 (a)): kept with an empty journal so its status shows and the owner can
     * choose; it never runs. A job without a readable manifest is kept for FAIL only.
     */
    public void addBroken(ConstructionJob job, PlacementManifest manifestOrNull, Map<String, String> nodeTypes, Box operatingBox,
                          List<String> reasons) {
        ConstructionJob shown = job.withLastError(String.join("; ", reasons));
        if (manifestOrNull == null) {
            brokenWithoutManifest.put(job.jobId(), shown);
            return;
        }
        JobProgram program = job.kind() == JobKind.BUILD ? JobProgram.build(manifestOrNull) : new JobProgram(List.of(), List.of());
        JobRecord r = new JobRecord(shown, manifestOrNull, nodeTypes, program, new Journal(), new JobOutcome(), operatingBox);
        r.broken = true;
        add(r);
    }

    /**
     * The owner's (or an operator's) way out of RECOVERY_NEEDED (F-2): ADOPT or DISCARD answers a log the evidence could
     * not decide; FAIL ends the job for a manual clean-up; REPAIR ends it and starts a REPAIR job that re-derives
     * everything from the world (a broken journal gives no pre-build blocks: replaced positions fall to Conflicts).
     */
    public ControlResult recover(String jobId, UUID requester, boolean op, RecoveryChoice choice, String newJobId, long tick,
                                 JobWorld w) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            ConstructionJob lone = brokenWithoutManifest.get(jobId);
            if (lone == null) {
                return ControlResult.NOT_FOUND;
            }
            if (!op && !lone.ownerUuid().equals(requester)) {
                return ControlResult.NOT_ALLOWED;
            }
            if (choice != RecoveryChoice.FAIL) {
                return ControlResult.WRONG_STATE;
            }
            brokenWithoutManifest.put(jobId, lone.on(JobEvent.FAIL));
            return ControlResult.OK;
        }
        ConstructionJob j = r.job();
        if (!op && !j.ownerUuid().equals(requester)) {
            return ControlResult.NOT_ALLOWED;
        }
        boolean waiting = !r.pendingLog.isEmpty();
        if (choice == RecoveryChoice.ADOPT || choice == RecoveryChoice.DISCARD) {
            return waiting ? answer(r, choice, w) : ControlResult.WRONG_STATE;
        }
        if (!(j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED)) {
            return ControlResult.WRONG_STATE;
        }
        if (waiting) {
            // the undecided runs are dropped; a REPAIR job then takes the world as it is
            answer(r, RecoveryChoice.DISCARD, w);
        }
        r.setJob(r.job().on(JobEvent.FAIL));
        if (choice == RecoveryChoice.FAIL) {
            return ControlResult.OK;
        }
        ConstructionJob job = ConstructionJob.create(newJobId, j.ownerUuid(), j.dimension(), j.manifestHash(), JobKind.REPAIR,
                j.jobId(), 0, j.claimId(), j.materialPolicy(), tick, List.of());
        JobRecord repair = new JobRecord(job, r.manifest(), r.nodeTypes(), new JobProgram(List.of(), List.of()),
                new Journal(Math.max(Journal.MAX_ENTRIES, r.program().size()) * (1 + ConstructionJob.MAX_REPAIR_ROUNDS)),
                new JobOutcome(), r.operatingBox());
        if (!r.broken) {
            repair.useBeforesFrom(r.journal());
        }
        add(repair);
        return ControlResult.OK;
    }
```

`JobFiles`: 上の置き場へ、各コーデックの木を`PersistenceEnvelope(SaveTypes.X, version, tree).toBytes()`で書く。施工リストのファイルが既にあれば書かない(内容で名前が決まるので同じ)。読み込みは`PersistenceEnvelope.fromBytes`→`SaveTypes.migrations().payloadOf`→`fromTree`。どれかで`UnreadableFileException`・`IllegalArgumentException`・ファイルが無いなら、理由(`"journal: missing"`のように、ファイルの種類と理由)を集めて`Broken`(施工リストが読めていれば`manifestOrNull`に入れる)。`BUILD`・`REPAIR`の手順は`JobProgram.build(manifest)`(`REPAIR`は空の手順)で作り直す。`MODIFY`・`ROLLBACK`は`program.bin`から。

`OrphanSweep`: `forgettableJobs`=終わった状態で、区画が`released`のジョブのID。`orphanFiles`=`jobs/<id>/...`で`id`が残すジョブに無い物、`manifests/<hash>.bin`でどの残すジョブも指さない物、`claims/<claimId>/...`で区画が解放済みか存在しない物(`claims.bin`は残す)。結果は辞書順。

`JobService.addBroken`・`recover`は上のとおり(Codexの3回目のレビュー2026-09-30で、文からJavaにした。`recover`には答えを確かめるために`JobWorld`を渡す)。

- [ ] **Step 4: テストが通ることを確かめる**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ジョブのファイルの保存と読み込み、復旧の判断(記録の遅れで復旧待ち・黙って再実行しない)、孤児の掃除、復旧の操作を追加(自然言語→工場建設 P4 Task 24)

Implemented-by: SWE-2 via Devin CLI
```

---

### Task 25: アダプタ — ジョブの保存(`job.bin`)・確定点(フラッシュつきの保存)と起動時の復旧、再起動・本当の強制終了の自動確認

**担当**: Step 1〜2とそのコミットはDevin(Java)。Step 3(台本・devkit・実機)とそのコミットはコントローラ(Claude)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/{Checkpoints,DevBarrier}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/core/FileWalSink.java`、`tools/p4/scenarios_restart.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/FileWalSinkTest.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(読み込み・保存・起動時の復旧・確定点・`recover`)、`src/main/java/io/github/khayashi4337/micradrone/construction/BuildCommands.java`(`recover <jobId> adopt|discard|repair|fail`)、devkit(`/build/recover`・`/build/files`・`/spike/barrier`・`/spike/barrier-state`)、`tools/p4/p4_scenarios.py`、`tools/p4/plans.py`(`hut_patch`に`width`・`depth`・`floors`の引数を足す。P4レビューB-11)

**Interfaces:**
- Consumes: Task 22〜24。Minecraft/NeoForge(出どころは`build/moddev/artifacts/neoforge-21.1.238-sources.jar`で確かめた): `MinecraftServer.saveAllChunks(boolean suppressLog, boolean flush, boolean forced)`(546行。各`ServerLevel.save(null, flush, level.noSave && !forced)`を呼ぶ)、`ServerLevel.save`(`flush`が真なら、チャンクを`save(true)`・実体を`saveAll()`・`LevelEvent.Save`を投げ、最後に`IOUtilities.waitUntilIOWorkerComplete()`でI/Oの終わりを待つ)、`ServerLevel.noSave`(`/save-off`)、`MinecraftServer.stopServer`(停止は`saveAllChunks(false, true, false)`=フラッシュつき。625行)、`LevelEvent.Save`、`ServerStartedEvent`・`ServerStoppedEvent`、`MinecraftServer.getWorldPath(LevelResource.ROOT)`
- Produces:
  - `final class FileWalSink implements WriteAheadLog.Sink`(`construction.core`。純Javaのファイル入出力): `FileWalSink(Path dir)`、`<world>/data/micradrone/wal/seg-NNNNNNNN.bin`。`write`はフレーム(`WalCodec.frame`)を一番新しいセグメントに追記して`FileChannel.force(true)`(長さもメタデータなので`true`)、`readAll`は古い順に読み、**一番新しいセグメントの壊れた尻尾を`validLength`まで切り詰めてから**返す(閉じたセグメントが壊れていれば例外)、`dropUpTo(run)`は確定点だけを持つ新しいセグメントを書いてから古いセグメントを消す(**ログは最後の確定点からの回だけに限られる**)。書けなければ`UncheckedIOException`
  - `final class Checkpoints`(アダプタ): `CHECKPOINT_MIN_SPACING_TICKS = 1200`(1分。ジョブが終わるのを待たせる最大の間)・`CHECKPOINT_INTERVAL_TICKS = 6000`(5分。確定点の後に実行のあるジョブがある間の、ふだんの間隔=自動保存と同じ間隔)。毎tickの終わりに、`JobService.checkpointWanted()`で前の確定点から`CHECKPOINT_MIN_SPACING_TICKS`経った、または確定点の後に実行があって`CHECKPOINT_INTERVAL_TICKS`経ったら、**確定点を作る**: `long upTo = wal.lastRun()`→`server.saveAllChunks(true, true, false)`(フラッシュつき。`LevelEvent.Save`でジョブのファイルも書く)→どのディメンションも`noSave`でなく、ジョブのファイルの書き込みが全部成功したときだけ`jobService.markDurable()`(=`wal.markDurable(upTo)`)。`noSave`のディメンションがあれば確定点を作らず、ログに`W`で出す(ジョブは終わらずに待つ。`/save-on`で進む)。1回の確定点は重い(Task 33で測る)。
  - `ConstructionRuntime`: **起動時**(`ServerStartedEvent`。プレイヤーが入る前)に、(1)`NioFileSystem(worldPath/data/micradrone)`を作り`cleanTemp()`、(2)`WriteAheadLog.open(new FileWalSink(root/wal))`で`JobService`を作る、(3)`claims.bin`と各区画の`placed.bin`を読む、(4)`jobs/*/job.bin`からジョブを読み(**前の版の`SavedData`=`ConstructionJobStore`はやめた**: ジョブを作った瞬間に`job.bin`を書くので、ログに知らないジョブの実行が残らない)、`JobFiles.loadJob`→`RecoveryPlanner.decide`→`fromLog`なら`requireRecovery()`→`JobService.add`/`addBroken`、(5)**`JobService.recoverAll(JobWorld)`**(Task 24。この`JobWorld`は、読む位置のチャンクをその場で読み込み、持ち主の取引IDを`playerdata/<uuid>.dat`から読む)→答えを待つジョブの`pending_log.bin`を書く、(6)**起動時の確定点**(上の手順。ログが確定点しか持たなければ要らない=きれいに止まった後)、(7)`OrphanSweep`。**ジョブを作ったら、その場で`JobFiles.saveJob`**(承認・`verify`・`rollback`・`modify`・`recover`の`REPAIR`。最初の実行の前)。`LevelEvent.Save`(そのディメンションのジョブのファイル、オーバーワールドなら`claims.bin`と各`placed.bin`)。`ServerStoppedEvent`(停止の保存はフラッシュつきなので、`LevelEvent.Save`が全部書いた後に`jobService.markDurable()`)。**ログや持ち物の保存ができなかったら(`UncheckedIOException`)、施工を止める**(`JobService.tick`を呼ぶのをやめ、ログに`E`、`status`に「きろくが かけないので とめたよ」)。`recover(UUID, boolean op, String jobId, RecoveryChoice)`(答えの後、そのジョブのファイルを書き直す)。`installBarrier(WriteAheadLog.Barrier)`(**開発専用**: システムプロパティ`micradrone.devBarriers=true`のときだけ効く。devkitの`runP4`系の実行設定だけが付ける。配布のjarで付けて起動しなければ何もしない)。
  - `DevBarrier implements WriteAheadLog.Barrier`: `arm(String point, JobKind kind, int nth)`(その境目を、その種類のジョブで`nth`回目に通った時に**止まる**=サーバーのスレッドをそこで待たせ、`hit`を立てる)、`hit()`。`FileWalSink`の`MID_FRAME`(フレームの半分を書いて強制した所)も同じ仕組み(`WriteAheadLog.Barrier.MID_FRAME`。`FileWalSink(Path, Barrier)`)。
  - コマンド`/micradrone build recover <jobId> adopt|discard|repair|fail`(所有者かOP。`adopt`・`discard`は答えを待つジョブだけ)。子供の文(Task 14): 「サーバーが とちゅうで とまったので、〜したか どうか わからないよ。/micradrone build recover 〜 adopt(した)か discard(していない)を えらんでね」。

- [ ] **Step 1: 実装する**(担当: Devin)

上のとおり。**順序について分かっていること**(Task 24の「保存の順序の事実」): 自動保存では、`SavedData`とチャンクはI/Oのスレッドで非同期に書かれ、チャンクは読み込みから外れた時にも書かれる。だから、確定点(フラッシュつきの保存が返った後)より後の回は、世界に残ったかどうか分からない=「失われたかもしれない」として、起動時に世界と照らして取り込むか置き直す(Task 9・24)。自動保存は確定点にしない。`FileWalSink`のテストは下のとおり(`@TempDir`で本当のファイルに書く)。

`FileWalSinkTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWalSinkTest {
    @TempDir
    Path dir;

    private List<Path> segments() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    @Test
    void appendedBatchesComeBackInOrderAfterAReopen() {
        FileWalSink sink = new FileWalSink(dir);
        sink.write(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunEnd("job-1", 1, List.of(), List.of())));
        sink.write(List.of(new WalEntry.RunStart("job-1", 2)));
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunEnd("job-1", 1, List.of(), List.of()),
                new WalEntry.RunStart("job-1", 2)), new FileWalSink(dir).readAll());
    }

    @Test
    void aTornTailIsCutOffSoTheNextAppendIsReadable() throws IOException {
        FileWalSink sink = new FileWalSink(dir);
        sink.write(List.of(new WalEntry.RunStart("job-1", 1)));
        Path seg = segments().get(0);
        long good = Files.size(seg);
        // a crash in the middle of the next append: half a frame is on the disk
        byte[] half = WalCodec.frame(new WalEntry.RunStart("job-1", 2));
        Files.write(seg, java.util.Arrays.copyOf(half, half.length / 2), StandardOpenOption.APPEND);
        FileWalSink reopened = new FileWalSink(dir);
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1)), reopened.readAll());
        assertEquals(good, Files.size(seg), "cut back to the good part");
        reopened.write(List.of(new WalEntry.RunStart("job-1", 2)));
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunStart("job-1", 2)), new FileWalSink(dir).readAll(),
                "without the cut, this entry would sit behind garbage for ever");
    }

    @Test
    void aDurablePointStartsANewSegmentAndDeletesTheOldOnes() throws IOException {
        FileWalSink sink = new FileWalSink(dir);
        List<WalEntry> many = new ArrayList<>();
        for (int run = 1; run <= 5; run++) {
            many.add(new WalEntry.RunStart("job-1", run));
        }
        sink.write(many);
        WriteAheadLog wal = WriteAheadLog.open(sink);
        wal.markDurable(wal.lastRun());
        assertEquals(1, segments().size(), "bounded: only the runs since the last checkpoint are kept");
        assertEquals(List.of(new WalEntry.DurablePoint(5)), new FileWalSink(dir).readAll());
        WriteAheadLog reopened = WriteAheadLog.open(new FileWalSink(dir));
        assertEquals(6, reopened.newRun());
        assertEquals(5, reopened.durableUpTo());
    }
}
```

`FileWalSink.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The write-ahead log's files ({@code <world>/data/micradrone/wal/seg-NNNNNNNN.bin}): frames ({@link WalCodec}) appended
 * to the newest segment and forced to the disk before {@link #write} returns. A durable point starts a new segment
 * holding only that point and deletes the older ones, so the log never grows past the runs since the last checkpoint. A
 * crash can only tear the last frame of the newest segment; {@link #readAll} cuts it back to its good part. Disk I/O
 * only: the rules are in WalCodec and WalRecovery.
 */
public final class FileWalSink implements WriteAheadLog.Sink {
    static final String PREFIX = "seg-";
    static final String SUFFIX = ".bin";
    static final String NUMBER_FORMAT = "%08d";

    private final Path dir;
    private final WriteAheadLog.Barrier barrier;

    public FileWalSink(Path dir) {
        this(dir, WriteAheadLog.Barrier.NONE);
    }

    /** With a barrier (development only): each batch is written in two halves with MID_FRAME in between. */
    public FileWalSink(Path dir, WriteAheadLog.Barrier barrier) {
        this.dir = dir.toAbsolutePath().normalize();
        this.barrier = barrier;
    }

    private List<Path> segments() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(PREFIX) && p.getFileName().toString().endsWith(SUFFIX))
                    .sorted().toList();
        }
    }

    private Path segment(long n) {
        return dir.resolve(PREFIX + String.format(NUMBER_FORMAT, n) + SUFFIX);
    }

    private static long numberOf(Path p) {
        String n = p.getFileName().toString();
        return Long.parseLong(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
    }

    private static byte[] frames(List<WalEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WalEntry e : entries) {
            out.writeBytes(WalCodec.frame(e));
        }
        return out.toByteArray();
    }

    private static void append(Path file, byte[] bytes) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            // the length is metadata: force(true), or a power cut can leave the frames written but not reachable
            ch.force(true);
        }
    }

    @Override
    public void write(List<WalEntry> entries) {
        try {
            Files.createDirectories(dir);
            List<Path> segs = segments();
            Path target = segs.isEmpty() ? segment(1) : segs.get(segs.size() - 1);
            byte[] bytes = frames(entries);
            if (barrier == WriteAheadLog.Barrier.NONE) {
                append(target, bytes);
            } else {
                append(target, java.util.Arrays.copyOf(bytes, bytes.length / 2));
                barrier.at(WriteAheadLog.Barrier.MID_FRAME);
                append(target, java.util.Arrays.copyOfRange(bytes, bytes.length / 2, bytes.length));
            }
        } catch (IOException e) {
            // nothing may change in the world without its intent on the disk: the caller stops construction
            throw new UncheckedIOException("the write-ahead log could not be made durable: " + dir, e);
        }
    }

    @Override
    public List<WalEntry> readAll() {
        try {
            List<Path> segs = segments();
            List<WalEntry> out = new ArrayList<>();
            for (int i = 0; i < segs.size(); i++) {
                byte[] bytes = Files.readAllBytes(segs.get(i));
                WalCodec.Frames f = WalCodec.readFrames(bytes);
                if (f.validLength() < bytes.length) {
                    if (i < segs.size() - 1) {
                        throw new UncheckedIOException(new IOException("a closed log segment is damaged: " + segs.get(i)));
                    }
                    try (FileChannel ch = FileChannel.open(segs.get(i), StandardOpenOption.WRITE)) {
                        ch.truncate(f.validLength());
                        ch.force(true);
                    }
                }
                out.addAll(f.entries());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("the write-ahead log could not be read: " + dir, e);
        }
    }

    @Override
    public void dropUpTo(long run) {
        try {
            List<Path> segs = segments();
            long next = segs.isEmpty() ? 1 : numberOf(segs.get(segs.size() - 1)) + 1;
            // the new segment first (holding the durable point, so the run numbers go on), then the old ones go
            append(segment(next), frames(List.of(new WalEntry.DurablePoint(run))));
            for (Path p : segs) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the write-ahead log could not be cut at the durable point: " + dir, e);
        }
    }
}
```

- [ ] **Step 2: 単体テストの回帰**(担当: Devin)

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`、失敗0件。

- [ ] **Step 3: 実機の自動確認(`tools/p4/scenarios_restart.py`)**(担当: コントローラ。devkitの`/build/recover`・`/build/files`もここで足し、devkitのリポジトリに別にコミットする)

1. `restart-resume`(条件4): 大きめの小屋(`plans.hut_patch`で`width=15`・`depth=15`・`floors=3`、ドローンの工程の配置が多い物)を建て始め、`status.cursor`が全体の半分を超えたら、`/server/save-all`はせずに**クライアントを`WM_CLOSE`で閉じる**(シングルプレイの終了は、統合サーバーを保存して止める=きれいな停止)→同じ世界で`clientLoadP4`を起動し直す→`/state`の`inWorld: true`になった時刻から、`/build/status`が`RUNNING`になるまでの秒数を測る(**10秒以内**、`resume.json`)→`VERIFIED`→読み戻しの照合で不一致0件→`/build/entities`で読み込み直後の演出用ドローンが0機だった(前の起動の残りが無い)ことも記録。
2. `missing-journal`(条件4): 建て始めて途中で閉じ→`run-p4/client/saves/p4-auto/data/micradrone/jobs/<jobId>/journal.bin`を消す→起動し直す→`status.pause == RECOVERY_NEEDED`→30秒の間`cursor`が変わらず、世界も変わらない(読み戻しの数が同じ)=**黙って再実行しない**→`/client/chat-log`に「きろくが こわれて いるよ…」→`/build/recover {choice: "fail"}`→`FAILED`。同じことを`repair`でもう一度(別の場所で)行い、`REPAIR`ジョブが`VERIFIED`になる。
(前の版の`crash-window`(きれいに止めた後にファイルを差し替えて、世界がファイルより進んだディスクを作る)は**やめた**。Codexの3回目のレビュー2026-09-30: きれいに止めた後のファイルの差し替えは、本当のクラッシュでは起きない状態を作る。同じ状態は、純Javaの`CrashRecoveryTest`(自動保存が一部のチャンクだけを書く世界)と`JobServiceWalRecoveryTest`で確かめる。)
3. `crash-barriers`(条件4・7・Review Focus 2。**本当の強制終了を、規約のすべての境目で**。`mode="mp"`): 台本が起動した専用サーバー(`-Dmicradrone.devBarriers=true`)で、次の全部の組を1つずつ行う: 境目`after-intents`・`after-writes`・`after-run-end`・`after-player-save`・`mid-frame` × ジョブ`BUILD`(小屋)・`REPAIR`(小屋の壁を3つ壊してから`verify`)・`ROLLBACK`(建てた小屋の`rollback`)、サバイバル(持ち物と補給チェストの両方から取る)。各組で: `/spike/barrier {point, kind, nth: 3}`で仕掛ける→施工を進める(**その間、台本は`Dev`にリンゴを拾わせ・捨てさせる**=`/server/run-as Dev give/clear`。持ち物の個数が施工と関係なく変わる)→`/spike/barrier-state`が`hit`になったら、**台本が起動したそのpidだけを**`taskkill /PID <pid> /T /F`(pidが`children`に無い・`runId`が違えば止まる)→消えたことを確かめ、`wal/`の中身の一覧を`crash.json`に→同じ世界で起動し直し→`Dev`が入る→期待: (a)`after-run-end`・`after-player-save`: 答えを待たずに再開し、終わる。(b)`after-intents`・`after-writes`・`mid-frame`・補給チェストを動かした回: **`RECOVERY_NEEDED`になり、10秒待っても何も置かない**→子供の文が出る→台本が正しい答え(回の途中で止まった回は`discard`。チェストの回は、チェストの中身を読んで決める)を`/build/recover`で送る→終わる。(c)どの組でも、終わった後の「持ち物(リンゴを除く)+チェスト+置いたブロックの品物」が最初と等しく(**二重に取らない・二重に返さない**)、読み戻しの照合で不一致0件、`timeline.json`に`SITE_CHANGED`が無い。`finally`で子のプロセスを片付ける。EULAが無ければ`NOT-RUN(eula)`(合格にしない)。
4. `real-crash`(条件4。境目を決めない強制終了。`mode="mp"`): 台本が起動した専用サーバーのJVM(Task 19の`harness`が`java`を直接起動し、pidを`children`に持つ)で、大きめの小屋(`hut_patch(width=15, depth=15, floors=3)`)を建て始め、`cursor`が全体の半分を超えたら、**台本が起動したそのpidだけを**`taskkill /PID <pid> /T /F`で落とす(pidが`children`に無い・devkitの`runId`が自分の物でなければ止まる)→プロセスが消えたことを確かめ、`wal.bin`の大きさを`crash.json`に残す→同じ世界でサーバーを起動し直し→`Dev`が入り直す→`/build/status`が`RUNNING`になるまでの秒数(10秒以内)→`VERIFIED`(回の途中で落ちたら`RECOVERY_NEEDED`→台本が`discard`で答える)→読み戻しの照合で不一致0件→`conflicts == 0`→`timeline.json`に`SITE_CHANGED`が無い。台本は`try`/`finally`で、起動した子のプロセスを全部片付ける(Task 19)。EULAが無ければ`NOT-RUN(eula)`(合格にしない)。

Run: `python -m tools.p4.p4_scenarios --only restart-resume,missing-journal --mode sp`、`python -m tools.p4.p4_scenarios --only crash-barriers,real-crash --mode mp`
Expected: 全部`PASS`。証拠: `restart-resume/resume.json`(秒数)・`compare.json`、`missing-journal/status-*.json`・`chat.json`、`crash-barriers/<point>-<kind>/crash.json`・`timeline.json`・`compare.json`・`inventory-*.json`・`chest-*.json`・`answer.json`、`real-crash/crash.json`・`timeline.json`・`compare.json`。

- [ ] **Step 4: コミット**

Devin(Step 1〜2):
```text
git add <Filesの src/main/java の各ファイルを1つずつ>
git commit -F <scratch>\commit_msg.txt
```
```text
feat: ジョブの保存(job.bin)・先行書き込みログのセグメント・確定点(フラッシュつきの保存)・起動時の復旧と確定点・開発専用の境目・孤児の掃除・recoverコマンドを追加(自然言語→工場建設 P4 Task 25)

Implemented-by: SWE-2 via Devin CLI
```
コントローラ(Step 3):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: 再起動・記録の欠落・規約のすべての境目での本当の強制終了からの復旧の実機の自動確認を追加(自然言語→工場建設 P4 Task 25)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```
(devkitの`/build/recover`・`/build/files`・`/spike/barrier`・`/spike/barrier-state`は、devkitのリポジトリに別のコミットとして積む。`/spike/barrier`はmicradroneの`ConstructionRuntime.installBarrier`に`DevBarrier`を渡すだけで、`micradrone.devBarriers`の無い起動では何もしない。)

---

### Task 26: 建築権限(`PlacementRules`・`E-PERMISSION-DENIED`)、他人の操作の拒否、S-9の自動測定

**担当**: Java(`src/`)とそのコミットはDevin。`05`の表の1行はコントローラ(Claude)が先に書き(コミットしない)、Devinが自分のコミットに`git add`で含める(`IssueTest`が表を読むため)。台本(`tools/p4`)・`docs/investigations`・実機の確認とそのコミットはコントローラ(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{PermissionLevel,PlaceVerdict,PlacementRules}.java`、`tools/p4/scenarios_permissions.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/model/IssueCode.java`(`E_PERMISSION_DENIED`)、`docs/design/nl_factory_builder/05_parts_and_analyzers.md`(4.1節に1行)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{ChildMessages,ApproveArgs}.java`(`P4_ISSUES`に足す。`owner=<名前>`で承認する保留を指せる)、`src/main/resources/assets/micradrone/lang/{ja_jp,en_us}.json`、`src/main/java/io/github/khayashi4337/micradrone/construction/{PlacementGuard,ConstructionRuntime,BuildCommands}.java`、`docs/investigations/spk_s9_place_event.md`(4節の結果)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{PlacementRulesTest,ApproveArgsOwnerTest}.java`

**Interfaces:**
- Consumes: Minecraft: `Level.getWorldBorder().isWithinBounds(BlockPos)`、`LevelHeightAccessor.isOutsideBuildHeight(BlockPos)`、`MinecraftServer.isUnderSpawnProtection(ServerLevel, BlockPos, Player)`、`Entity.hasPermissions(int)`・`Commands.LEVEL_GAMEMASTERS`
- Produces:
  - `enum PermissionLevel {ALL, OP}`
  - `PlacementRules.canSubmit(PermissionLevel level, boolean op, int placements, int largeJobPlacements) → Optional<Issue>`(F-4(d)。拒否は`E-PERMISSION-DENIED`)
  - `enum PlaceVerdict {ALLOW, OUTSIDE_BORDER, OUTSIDE_HEIGHT, SPAWN_PROTECTED}`、`PlacementRules.check(boolean insideBorder, boolean insideHeight, boolean underSpawnProtection, boolean op) → PlaceVerdict`(F-4(a)(b)。OPはスポーン保護を越えられる)
  - `PlacementGuard.place`・`restore`は、イベントを投げる前に`PlacementRules.check`を当て、`ALLOW`以外は何も変えずに`DENIED`
  - `ConstructionRuntime.approve`に最後の引数`String ownerNameOrNull`を足す: `approve(ServerPlayer, String hash, Confirmations, List<AcceptedRisk>, String ownerNameOrNull)`(P4レビューB-9。コマンド・devkitの`/build/approve`の`owner`・Task 32の`ApprovePlanPayload.ownerName`が同じ道を通る。Task 16の呼び出しは`null`を渡すように直す)
  - `ApproveArgs.Parsed`に`String ownerName`(`owner=<名前>`。無ければ`null`=自分の保留)。`ApprovalRequest.playerUuid`は、その名前のUUID(オフライン名の規則`UUIDUtil.createOfflinePlayerUUID`ではなく、サーバーの`getProfileCache`で引く。見つからなければ`NO_PENDING`)
  - 新しい`IssueCode`: `E_PERMISSION_DENIED("E-PERMISSION-DENIED")`

- [ ] **Step 1: 失敗するテストを書く**

`PlacementRulesTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
import org.junit.jupiter.api.Test;

class PlacementRulesTest {
    @Test
    void theServerMayReserveConstructionForOperatorsOrForLargeJobs() {
        assertTrue(PlacementRules.canSubmit(PermissionLevel.ALL, false, 238, 20_000).isEmpty());
        assertEquals(IssueCode.E_PERMISSION_DENIED,
                PlacementRules.canSubmit(PermissionLevel.OP, false, 238, 20_000).orElseThrow().code());
        assertTrue(PlacementRules.canSubmit(PermissionLevel.OP, true, 238, 20_000).isEmpty());
        assertEquals(IssueCode.E_PERMISSION_DENIED,
                PlacementRules.canSubmit(PermissionLevel.ALL, false, 5_000, 1_000).orElseThrow().code(), "a large job needs an OP");
    }

    @Test
    void theWorldsEdgesAndTheSpawnAreRespected() {
        assertEquals(PlaceVerdict.ALLOW, PlacementRules.check(true, true, false, false));
        assertEquals(PlaceVerdict.OUTSIDE_BORDER, PlacementRules.check(false, true, false, true));
        assertEquals(PlaceVerdict.OUTSIDE_HEIGHT, PlacementRules.check(true, false, false, true));
        assertEquals(PlaceVerdict.SPAWN_PROTECTED, PlacementRules.check(true, true, true, false));
        assertEquals(PlaceVerdict.ALLOW, PlacementRules.check(true, true, true, true), "an operator may build at spawn");
    }
}
```
`ApproveArgsOwnerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApproveArgsOwnerTest {
    @Test
    void anotherPlayersPendingApprovalCanBeNamed() {
        assertEquals("Dev", ApproveArgs.parse("owner=Dev confirm-terraform").ownerName());
        assertNull(ApproveArgs.parse("confirm-terraform").ownerName());
    }
}
```
(担当: コントローラ。Devinに渡す前に書き、コミットしない。Devinのコミットに含まれる) `05_parts_and_analyzers.md`の4.1節の表の`E-REPLACE-UNCONFIRMED`の行の直後に:
```
| `E-PERMISSION-DENIED` | 施工の権限が無い(設定の許可レベル・大規模のジョブはOPだけ) | 提出時 | OPに頼む |
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.PlacementRulesTest" --tests "io.github.khayashi4337.micradrone.construction.core.ApproveArgsOwnerTest" --tests "io.github.khayashi4337.micradrone.build.model.IssueTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

`PlacementRules`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The pure part of the build permission (04 F-4): who may submit, and which positions a placement may touch. */
public final class PlacementRules {
    static final String SUBJECT = "manifest";
    static final String DATA_LIMIT = "limit";

    private PlacementRules() {
    }

    public static Optional<Issue> canSubmit(PermissionLevel level, boolean op, int placements, int largeJobPlacements) {
        if (op) {
            return Optional.empty();
        }
        if (level == PermissionLevel.OP) {
            return Optional.of(Issue.of(IssueCode.E_PERMISSION_DENIED, "level", List.of(SUBJECT),
                    "このサーバーでは、建物を建てられるのはOPだけです"));
        }
        if (placements >= largeJobPlacements) {
            return Optional.of(Issue.of(IssueCode.E_PERMISSION_DENIED, "large", List.of(SUBJECT),
                    "大きな建物(" + largeJobPlacements + "個以上)はOPだけが建てられます",
                    Map.of(DATA_LIMIT, String.valueOf(largeJobPlacements)), List.of()));
        }
        return Optional.empty();
    }

    public static PlaceVerdict check(boolean insideBorder, boolean insideHeight, boolean underSpawnProtection, boolean op) {
        if (!insideBorder) {
            return PlaceVerdict.OUTSIDE_BORDER;
        }
        if (!insideHeight) {
            return PlaceVerdict.OUTSIDE_HEIGHT;
        }
        if (underSpawnProtection && !op) {
            return PlaceVerdict.SPAWN_PROTECTED;
        }
        return PlaceVerdict.ALLOW;
    }
}
```
`PlaceVerdict`・`PermissionLevel`: 上の`enum`。`ChildMessages.P4_ISSUES`に`E_PERMISSION_DENIED`を足し、`ja_jp.json`に`"micradrone.build.issue.e_permission_denied": "たてる けんりが ないよ。OPの ひとに たのんでね"`、`en_us.json`に同じ意味の英語を足す(`ChildMessagesTest`が両方の一致を検査する)。`PlacementGuard`は、`place`・`restore`の最初に`PlacementRules.check(level.getWorldBorder().isWithinBounds(pos), !level.isOutsideBuildHeight(pos), server.isUnderSpawnProtection(level, pos, actor), actor.hasPermissions(Commands.LEVEL_GAMEMASTERS))`を当てる。`ConstructionRuntime.submit`の手順7(Task 16)で`canSubmit`を呼ぶ。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_permissions.py`:
1. `permissions-single`(条件6・14、シングルプレイの統合サーバー): `Dev`が小屋を建てて`VERIFIED`。**他人**は`Intruder`(devkitの`/server/run-as`で、オンラインでない偽のプレイヤー。本物のUUIDとOPでない権限を持つ): (a)`micradrone build cancel <DevのjobId>`→`control.not_allowed`の文(実行の出力と`/build/status`が変わらないこと)、(b)`micradrone build approve <Devの保留のハッシュ> owner=Dev`→`reject.not_owner`(Devが別の小屋を提出して保留を作ってから)、(c)Devの小屋に重なる計画を`submit`→保留の`issues`に`E-CLAIM-OVERLAP`(**ジョブが終わっても区画が守られている**=条件14)、(d)`Dev`がOPとして`cancel`できること(OPの代理)。
2. `s9-protection-online`(S-9の測定、`mode="sp"`): `/spike/protect-box`で小屋の壁の3ブロックを囲って有効にし、`Dev`がオンラインで小屋を建てる→その3位置が`skipped`(`denied`)で世界に残らない(読み戻し)・`/spike/protect-log`の主体のクラスが`ServerPlayer`。結果を`docs/investigations/spk_s9_place_event.md`の4節に書き、主体の選び方(Task 2の既定)を変える必要が無いか判断する(変えるなら、テストを先に書いてから`PlacementGuard.actor`を直す)。同じ節に、スポーン保護の読み取った事実を書く: `DedicatedServer.isUnderSpawnProtection`は、オーバーワールド以外・OPの一覧が空・OPのプロフィールなら偽(`DedicatedServer.java` 397〜413行)。だから、OPの所有者のUUIDを持つ`FakePlayer`はスポーン保護を越え、`ops.json`が空ならスポーン保護は効かない(P4レビューD-7。測る必要は無い)。
   - S-9の残り2つは、それぞれ要る部品ができたタスクで足す(P4レビューB-10): `s9-protection-offline`(所有者がいない間の主体が`FakePlayer`で、同じく`denied`になる。`ChunkKeeper`が要るのでTask 31、`mp`)、`s9-protection-rollback`(守りの箱と重なる小屋のロールバックで、`BreakEvent`がキャンセルされた位置が残る。Task 28、`sp`)。

Run: `python -m tools.p4.p4_scenarios --only permissions-single,s9-protection-online --mode sp` → `PASS`(証拠: `permissions-single/outputs.json`(`/server/run-as`の`output`。偽のプレイヤーにはチャットが無いので、Task 18の`DevkitTeeSource`で集めた行を見る)・`status.json`・`pending.json`、`s9-protection-online/protect-log.json`・`compare.json`)。このタスクでは`PENDING_CONDITIONS`を変えない(条件6は`dedicated-two-clients`のTask 34、条件14はロールバックの解放のTask 28で消す)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/resources/assets/micradrone/lang の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git add docs/design/nl_factory_builder/05_parts_and_analyzers.md
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 建築権限(許可レベル・大規模ジョブ・世界の端・スポーン保護)とE-PERMISSION-DENIED、他人の操作の拒否とS-9の自動測定を追加(自然言語→工場建設 P4 Task 26)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add docs/investigations/spk_s9_place_event.md tools/p4
git commit -m "$(cat <<'MSG'
test: Task 26の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 26)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 27: 材料(サバイバルの消費・補給チェスト・不足で停止・補給で再開・整地の資源)

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{SupplyRegistry,SupplyCodec}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/{InventoryMaterials,SupplyChests}.java`、`tools/p4/scenarios_materials.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/{RuntimeJobWorld,BuildCommands,ConstructionRuntime}.java`(`supply add <pos>`・`supply list`、補給チェストの保存)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobFiles,SaveTypes}.java`(`claims/<claimId>/supply.bin`と、保存の型`SUPPLY`)、`tools/p4/p4_scenarios.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/SupplyRegistryTest.java`

**Interfaces:**
- Consumes: Task 9の`MaterialPort`・`Stock`・`Move`。Minecraft/NeoForge(`build/moddev/artifacts/neoforge-21.1.238-sources.jar`で確かめた): `ServerPlayer.getInventory()`(`Inventory.getContainerSize()`・`getItem(int)`・`removeItem(int, int)`・`add(ItemStack)`)、`Container`(補給チェスト)、`ItemStack.getCount()`・`getMaxStackSize()`、`Entity.getPersistentData()`(NeoForgeの、実体の保存データに一緒に書かれる`CompoundTag`。`Entity.java` 3713行)、`PlayerList.saveAll()`(685行の`public`。`save(ServerPlayer)`は352行の`protected`)、`NbtIo.readCompressed`、`LevelResource.PLAYER_DATA_DIR`
- Produces:
  - `SupplyRegistry`: 区画ごとの補給チェストの位置(`add(claimId, IntPos)`・`remove`・`positions(claimId)`)。追加の条件(区画の`worldBox`の中・コンテナであること)はアダプタが確かめる。`SupplyCodec`(保存の形)
  - `InventoryMaterials implements MaterialPort`(Task 9の形: `stocks`・`giveTarget`・`apply`・`persist(tx)`・`durableTx()`。下のStep 3)。所有者がオンラインなら持ち物+補給チェスト、いなければ補給チェストだけ。F-7の返却先。`TX_KEY = "micradrone:lastTx"`(プレイヤーの保存データの中の取引IDの名前)
  - コマンド: `/micradrone build supply add <pos>`(所有者かOP。その区画の中のコンテナだけ)、`supply list`
  - `RuntimeJobWorld.materials`: `SURVIVAL_CONSUME`なら`InventoryMaterials`(Task 16の仮の`RuntimeJobWorld.NO_MATERIALS`を置き換え、`NO_MATERIALS`は消す)

- [ ] **Step 1: 失敗するテストを書く**

(在庫の計算(`Stock`・`Stocks`・`Move`)はTask 9にあり、`StocksTest`で確かめてある。前の版の`StockBook`・`StockTake`は作らない。)

`SupplyRegistryTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyRegistryTest {
    @Test
    void chestsAreKeptPerClaimInTheOrderAddedAndRoundTrip() throws Exception {
        SupplyRegistry r = new SupplyRegistry();
        r.add("claim-a", new IntPos(1, 64, 1));
        r.add("claim-a", new IntPos(2, 64, 1));
        r.add("claim-a", new IntPos(1, 64, 1));
        assertEquals(List.of(new IntPos(1, 64, 1), new IntPos(2, 64, 1)), r.positions("claim-a"));
        assertEquals(List.of(), r.positions("claim-b"));
        SupplyRegistry back = SupplyCodec.fromTree(SaveTypes.migrations().payloadOf(PersistenceEnvelope.fromBytes(
                new PersistenceEnvelope(SaveTypes.SUPPLY, 1, SupplyCodec.toTree(r)).toBytes())));
        assertEquals(r.positions("claim-a"), back.positions("claim-a"));
    }
}
```
(`SaveTypes`に`SUPPLY = "supply"`(版1)を足す。)

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.SupplyRegistryTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`InventoryMaterials`(アダプタ。Task 9の`MaterialPort`の形):
- `stocks(itemIds)`: 所有者がオンラインなら、まず`inventory`(持ち物の全スロットの合計)、次に区画の補給チェストを登録の順に(`MaterialPort.chest(pos)`。チャンクが読み込まれていないチェストは数えない)。
- `giveTarget(items)`: **全部が入る**出どころ(オンラインなら持ち物を先に、次に補給チェスト)。どこにも入らなければ空=実行は`NO_ROOM`で止まり、所有者が場所を空ければ`RETRY_INTERVAL_TICKS`ごとの見直しで続く。**材料の操作の中で地面に落とさない**(Codexの3回目のレビュー2026-09-30: 落ちた品物はチャンクのデータで、クラッシュで消えたり増えたりし、台帳と合わなくなる)。
- `apply(moves)`: まず全部の動きが今の数で足り、入る場所があるかを確かめ(でなければ何も変えずに`false`)、取る動きは`removeItem`(同じ品物はスロットの順)、渡す動きは`Inventory.add`・チェストへの挿入。持ち物を動かしたら「保存が要る」の印を立てる。
- `persist(tx)`: 印が無ければ何もしない。あれば、所有者の`getPersistentData()`に`TX_KEY = tx`を入れてから`server.getPlayerList().saveAll()`(持ち物と取引IDが**同じファイルに1回で**書かれる)→所有者の`<world>/playerdata/<uuid>.dat`を`FileChannel.open(WRITE).force(true)`→**読み戻して取引IDが`tx`であることを確かめる**(`NbtIo.readCompressed`の`NeoForgeData`の`TX_KEY`。`PlayerDataStorage.save`は例外を握りつぶすので、確かめないと保存できたとは言えない)。合わなければ`UncheckedIOException`→ランタイムは施工を止める。
- `durableTx()`: 起動時の復旧のため、**プレイヤーのデータのファイルから**読む(持ち主がいなくてよい。`playerdata/<uuid>.dat`の`NeoForgeData`の`TX_KEY`。無ければ`NO_TX`)。起動の後の新しい実行より前に1回だけ読み、覚えておく。
- **補給チェストは「ちょうど1回」を約束しない**: チェストの中身はチャンクと一緒に非同期に保存されるので、確定点(Task 25)の後の回の取り出し・返却が残ったかは証明できない。そういう回が落ちた後に見つかれば、そのジョブは`RECOVERY_NEEDED`で持ち主の答え(`adopt`/`discard`。チェストの中を見て決める)を待つ(Task 9・24)。子供の文と`README`にもこう書く(Task 35)。前の版の`SourceSaved`(チャンクの保存の直前の個数の印)は**やめた**。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_materials.py`(条件7・12):
1. `survival-materials`: `gamemode survival Dev`・`clear Dev`→小屋の材料表(`/build/pending`の施工リストの`bom`)の半分だけ`give`→承認(クリエイティブでないので`SURVIVAL_CONSUME`になる)→`PAUSED(MATERIALS_MISSING)`・`/client/chat-log`に「〜が 〜こ たりないよ」→区画の中にチェストを置いて(`setblock`と`item replace`で残りの材料を入れる)`micradrone build supply add <pos>`→**1秒ほどで自動に再開**して`VERIFIED`→持ち物とチェストの残りが0(=材料表の個数ちょうどを消費)。途中で`cancel`した別の小屋では、`cancel`の前後で持ち物の数が変わらない(**取消で返さない**)。建てている途中でクライアントを`WM_CLOSE`で閉じて起動し直し(Task 25と同じ手順)、再開して完成した後の「持ち物+チェスト+置いたブロックの品物の数」が、最初に与えた数と等しい(**二重に消費しない**)。
2. `terrain-survival`: 地面の上で`submit-here`(整地あり)→確認つきで承認(サバイバル)→完成後、「持ち物の土・丸石など+世界のその種類のブロックの数」が、建てる前と比べて**消えも増えもしない**(切った草の地面は土として渡り、盛った土は持ち物から減る。`inventory-before.json`・`inventory-after.json`・`world-count-*.json`)。
3. `no-room`(条件7): 返却の途中で持ち物をいっぱいにして(`/server/run-as Dev give`で石を詰める)、補給チェストも無い状態でロールバック→`PAUSED(NO_ROOM)`・子供の文「もちものが いっぱいで…」→撤去が進まない(読み戻し)→`/build/entities {type: "minecraft:item"}`が0(**落とさない**)→持ち物を空ける→再開して終わる→返却の合計が消費と等しい。
(本当の強制終了での材料の確かめは、Task 25の`crash-barriers`(サバイバル・持ち物と補給チェストの両方)で行う。前の版の`real-crash-materials`はそこにまとめた。)

Run: `python -m tools.p4.p4_scenarios --only survival-materials,terrain-survival,no-room --mode sp` → `PASS`。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: サバイバルの材料(持ち物と補給チェストからの消費・不足で停止・補給で自動再開・整地の資源の受け渡し)を追加(自然言語→工場建設 P4 Task 27)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 27の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 27)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 28: ロールバック(`RollbackPlanner`・`ROLLBACK`ジョブ・区画の解放)と、取消・ロールバックの自動確認

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/RollbackPlanner.java`、`tools/p4/scenarios_rollback.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/core/JobService.java`(`rollback`)、`src/main/java/io/github/khayashi4337/micradrone/construction/{ConstructionRuntime,BuildCommands}.java`(`rollback <claimId> [confirm]`)、devkit(`/build/rollback`)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{RollbackPlannerTest,JobServiceRollbackTest}.java`

**Interfaces:**
- Produces:
  - `RollbackPlanner.plan(PlacedRegistry registry, BiFunction<String,Integer,Set<String>> volatileOfPlacer) → List<RestoreItem>`(`01` 4.1・F-14: 設置の記録のすべての位置を、`Attachments.REMOVAL_ORDER`の順に(**付いている物(扉・看板・たいまつ・ランタンなど)が先、扉の上下は続けて、残りは上から下**(y降順、同じ高さはz→x昇順))。付いている物を後に回すと、支えを戻した時に隣の更新でアイテムを落として外れる(P4レビューG-2。Task 9・15)、`expectedNow`=今置いてあるはずのブロック、`restoreTo`=施工前のブロック、材料の返却は置いた人の台帳のキー、`dropContents`は常に`true`(このプロジェクトが置いたブロックだけなので、コンテナなら中身を落とす。D-25))。`volatileOfPlacer.apply(jobId, ledgerKey)`は、置いたジョブの施工リストの番号(`ledgerKey % JobProgram.LEDGER_ROUND_STRIDE`)の節の部品の`volatileProps`
  - `ControlResult JobService.rollback(String claimId, UUID requester, boolean op, String newJobId, long tick)`: 区画が有効・所有者かOP・区画の中に終わっていないジョブが無い→`ROLLBACK`ジョブ(`parentJobId`=区画の最後のジョブ、`manifestHash`=その施工リスト、手順=`RollbackPlanner.plan`)。完了で`VERIFIED`になれば、区画の他のジョブが`ROLLED_BACK`になり、区画が解放され、`PlacedRegistry`が捨てられる(Task 13の`finishRollback`)。`Conflict`は触らずに報告し、**そのジョブは`PARTIAL`で終わり、区画と`PlacedRegistry`(撤去できなかった位置の記録)は残る**(Codexのレビュー2026-09-30: 撤去しきれていないのに区画を解放すると、残ったブロックが誰の物でもなくなる。Task 13)。プレイヤーがその位置を施工前の状態に戻せば(例: 金のブロックを壊して空気に)、もう一度`rollback`でき、その位置は「もう戻っている」(`ConstructionExecutor`の撤去の手順の、満たしているが記録が無い枝。返却はしない)として記録から外れ、`VERIFIED`→区画が解放される。区画は、終わった(`PARTIAL`を含む)ジョブしか無ければ何度でも`rollback`できる。
  - コマンド`/micradrone build rollback <claimId>`は、まず「撤去するブロックN個、中身がM個ドロップします」を見せ(Task 30で中身の数を足す)、`/micradrone build rollback <claimId> confirm`で実行する(F-5: 撤去の承認画面の代わり)。

- [ ] **Step 1: 失敗するテストを書く**

`RollbackPlannerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RollbackPlannerTest {
    @Test
    void everyProjectBlockIsUndoneTopDownToItsPreBuildBlock() {
        PlacedRegistry reg = new PlacedRegistry("claim-a");
        reg.apply("job-1", new JournalRecord(0, new IntPos(0, 64, 0), BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0));
        reg.apply("job-1", new JournalRecord(1, new IntPos(0, 66, 0), BlockSpec.AIR, false,
                BlockSpec.of("minecraft:oak_door", "open", "false"), 1));
        reg.apply("job-1", new JournalRecord(2, new IntPos(1, 66, 0), BlockSpec.AIR, false, BlockSpec.of("minecraft:barrel"), 2));
        List<RestoreItem> items = RollbackPlanner.plan(reg, (job, key) -> key == 1 ? Set.of("open", "powered") : Set.of());
        assertEquals(List.of(new IntPos(0, 66, 0), new IntPos(1, 66, 0), new IntPos(0, 64, 0)),
                items.stream().map(RestoreItem::pos).toList());
        assertEquals(BlockSpec.of("minecraft:grass_block"), items.get(2).restoreTo());
        assertEquals(Set.of("open", "powered"), items.get(0).volatileProps(), "an opened door still counts as ours");
        assertTrue(items.stream().allMatch(RestoreItem::dropContents));
        assertEquals("job-1", items.get(1).sourceJobId());
        assertEquals(2, items.get(1).sourceLedgerKey());
    }

    @Test
    void aDoorsHalvesGoTogetherAndASignGoesBeforeItsWall() {
        PlacedRegistry reg = new PlacedRegistry("claim-a");
        IntPos wall = new IntPos(0, 65, 0);
        IntPos sign = new IntPos(0, 65, 1);
        IntPos lower = new IntPos(2, 64, 0);
        IntPos upper = new IntPos(2, 65, 0);
        IntPos roof = new IntPos(0, 67, 0);
        reg.apply("job-1", new JournalRecord(0, wall, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone_bricks"), 0));
        reg.apply("job-1", new JournalRecord(1, sign, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_wall_sign", "facing", "south"), 1));
        reg.apply("job-1", new JournalRecord(2, lower, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_door", "half", "lower"), 2));
        reg.apply("job-1", new JournalRecord(3, upper, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_door", "half", "upper"), 3));
        reg.apply("job-1", new JournalRecord(4, roof, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_planks"), 4));
        List<IntPos> order = RollbackPlanner.plan(reg, (job, key) -> Set.of()).stream().map(RestoreItem::pos).toList();
        // Attachments.REMOVAL_ORDER (Task 9): a door sorts by its lower half, so the sign one higher goes first
        assertEquals(List.of(sign, upper, lower, roof, wall), order,
                "attached blocks first (the door's halves back to back), then the rest top-down");
    }
}
```
`JobServiceRollbackTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class JobServiceRollbackTest {
    @Test
    void aRollbackThatLeavesAPlayersBlockIsPartialAndKeepsTheClaimUntilTheSiteIsClean() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        w.inventories.put(JobServiceTest.A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16));
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        IntPos swapped = m.placements().get(12).pos();
        w.world.setBlock(swapped, BlockSpec.of("minecraft:gold_block"));
        assertEquals(ControlResult.NOT_ALLOWED, s.rollback("claim-job-1", JobServiceTest.B, false, "job-2", c.tick));
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.PARTIAL));
        for (Placement p : m.placements()) {
            if (!p.pos().equals(swapped)) {
                assertEquals(BlockSpec.AIR, w.world.blockAt(p.pos()));
            }
        }
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId(), "a player's change is left alone");
        assertEquals(1, s.status("job-2").orElseThrow().conflicts());
        assertEquals(JobState.VERIFIED, s.status("job-1").orElseThrow().state(), "not rolled back while a block is left");
        assertFalse(s.claims().find("claim-job-1").orElseThrow().released(), "the claim stays while the site is not clean");
        assertTrue(s.registries().get("claim-job-1").contains(swapped), "the position left alone is still on the list");
        FakeMaterials inv = w.inventories.get(JobServiceTest.A);
        assertEquals(9, inv.count("minecraft:cobblestone"));
        assertEquals(15, inv.count("minecraft:oak_planks"), "the plank under the gold block was not taken back");
        // the player clears the gold block: the ground is as before the build, so a second rollback finishes
        w.world.setBlock(swapped, BlockSpec.AIR);
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-3", c.tick));
        c.runUntil(s, w, "job-3", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(JobState.ROLLED_BACK, s.status("job-1").orElseThrow().state());
        assertTrue(s.claims().find("claim-job-1").orElseThrow().released());
        assertEquals(15, inv.count("minecraft:oak_planks"), "a block the player removed is not refunded");
        assertEquals(ControlResult.WRONG_STATE, s.rollback("claim-job-1", JobServiceTest.A, false, "job-4", c.tick),
                "a released claim cannot be rolled back twice");
    }

    @Test
    void aCleanRollbackReleasesTheClaimAndReturnsWhatWasConsumedOnce() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        w.inventories.put(JobServiceTest.A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16));
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.VERIFIED));
        for (Placement p : m.placements()) {
            assertEquals(BlockSpec.AIR, w.world.blockAt(p.pos()));
        }
        assertEquals(JobState.ROLLED_BACK, s.status("job-1").orElseThrow().state());
        assertTrue(s.claims().find("claim-job-1").orElseThrow().released());
        assertFalse(s.registries().containsKey("claim-job-1"));
        FakeMaterials inv = w.inventories.get(JobServiceTest.A);
        assertEquals(9, inv.count("minecraft:cobblestone"));
        assertEquals(16, inv.count("minecraft:oak_planks"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Rollback*" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

`RollbackPlanner`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A claim's rollback (04 F-14): every block the project placed goes back to its pre-build block, top-down, removing only
 * project blocks (D-25), comparing without the part's volatile states, dropping a container's items first, and returning
 * only the material its placer's ledger recorded.
 */
public final class RollbackPlanner {
    private static final Comparator<IntPos> TOP_DOWN = Comparator.<IntPos>comparingInt(p -> -p.y()).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);

    private RollbackPlanner() {
    }

    public static List<RestoreItem> plan(PlacedRegistry registry, BiFunction<String, Integer, Set<String>> volatileOfPlacer) {
        List<Map.Entry<IntPos, PlacedEntry>> entries = new ArrayList<>(registry.placed().entrySet());
        entries.sort(Map.Entry.comparingByKey(TOP_DOWN));
        List<RestoreItem> out = new ArrayList<>(entries.size());
        for (Map.Entry<IntPos, PlacedEntry> e : entries) {
            PlacedEntry p = e.getValue();
            out.add(new RestoreItem(e.getKey(), p.placed(), volatileOfPlacer.apply(p.jobId(), p.placementIndex()), p.before(),
                    p.jobId(), p.placementIndex(), true, p.earlierKeys()));
        }
        // stable: ties keep the top-down order above
        out.sort(Attachments.REMOVAL_ORDER);
        return out;
    }
}
```
`JobService.rollback`: 上の条件(区画が無ければ`NOT_FOUND`、所有者でもOPでもなければ`NOT_ALLOWED`、解放済みか、終わっていないジョブがあれば`WRONG_STATE`。親は区画の最後のジョブ)。`volatileOfPlacer`は`(jobId, key) -> record(jobId)`の施工リストの`placements().get(key % JobProgram.LEDGER_ROUND_STRIDE).partNodeId()`→`VolatileProps.of(nodeTypes, registry)`(ジョブが見つからなければ空)。`ROLLBACK`の`JobRecord`の`manifest`は親の物(比べない。Task 13の`verifyPositions`)。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_rollback.py`(どれも`mode="sp"`。このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件4・7・14を消す):
1. `cancel`(条件4): 大きめの小屋を建て始め、半分で`micradrone build cancel <jobId>`→`CANCELLED`・読み戻しで置いた分が残っている(撤去しない)・区画は有効のまま(`Intruder`の重なる提出が`E-CLAIM-OVERLAP`)。
2. `rollback`(条件4・14): 草の地面の上に`submit-here`で小屋(整地あり)→`VERIFIED`→壁の1つを`setblock gold_block`→`micradrone build rollback <claimId>`(撤去の数が出る)→`... confirm`→`ROLLBACK`ジョブが`PARTIAL`(金のブロックは残り`conflicts == 1`・`lastError`に`conflict=1`)→**建てる前に読んでおいた全位置の状態**(台本が承認の前に`/build/read-blocks`で`before.json`に取る)と、今の状態が、金の壁の1位置を除いて全部一致(**置換したすべてのブロックが、設置前のブロックとブロック状態に戻る**)→**`/build/entities {type: "minecraft:item", box: 区画}`の数が0**(扉の上下・付いている物を戻しても、アイテムが落ちていない。P4レビューG-2。クリエイティブなので返却の品物も落ちない)→区画は**まだ有効**(`Intruder`の重なる提出が`E-CLAIM-OVERLAP`)→金のブロックの位置を`before.json`の状態に`setblock`で戻す→もう一度`rollback <claimId>`→`confirm`→`VERIFIED`→全位置が`before.json`と一致→区画が解放され、`Intruder`が同じ場所に提出すると`E-CLAIM-OVERLAP`が出ない→ジョブのファイル(`/build/files`)が、次の起動の孤児の掃除で消える(起動し直して確かめる)。
3. `rollback-return`(条件7): サバイバルで建てた小屋をロールバック→持ち物に、材料表のとおりの個数が1回だけ戻る(2回目の`rollback`は`control.wrong_state`)。整地の地面は、切った分の土を持ち物から取り返してから草の地面に戻す(土が足りなければ`MATERIALS_MISSING`で止まり、補給で再開)。

4. (前の版の`crash-window-rollback`はやめた。ロールバックの途中の本当の強制終了は、Task 25の`crash-barriers`の`ROLLBACK`の組で確かめる。起動時の復旧で取り込んだ位置も撤去されることは、純Javaの`CrashRecoveryTest`で確かめる。)
5. `s9-protection-rollback`(S-9): `/spike/protect-box`で建てた小屋の壁の3ブロックを`cancelBreak: true`で守り、ロールバック→その3位置は`BreakEvent`がキャンセルされて残り(`skipped`が`denied`)、他は撤去。`/spike/protect-log`の記録を`docs/investigations/spk_s9_place_event.md`の4節に足す。

Run: `python -m tools.p4.p4_scenarios --only cancel,rollback,rollback-return,s9-protection-rollback --mode sp` → `PASS`(証拠: `before.json`・`after.json`・`compare.json`・`status.json`・`entities.json`・`inventory-*.json`・`protect-log.json`)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ロールバック(設置の記録から上から下へ施工前に戻す・区画の解放・消費した分だけ返す)と、取消・ロールバックの自動確認を追加(自然言語→工場建設 P4 Task 28)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 28の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 28)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 29: 建てた後の変更(`MODIFY`)— `ManifestDiff`から手順を作り、手で置き換えた位置は`Conflict`として触らない

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{ModifyPlan,ModifyPlanner}.java`、`tools/p4/scenarios_modify.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{JobService,JobRecord,RollbackPlanner,SubmitOutcome,JobViews}.java`(`RollbackPlanner.TOP_DOWN`を公開、`JobRecord`に置く位置の集合)、`src/main/java/io/github/khayashi4337/micradrone/construction/{ConstructionRuntime,BuildCommands,ServerSurveyor}.java`、devkit(`/build/modify`)、`tools/p4/p4_scenarios.py`、`tools/p4/plans.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{ModifyPlannerTest,JobServiceModifyTest}.java`

**Interfaces:**
- Consumes: P3の`ManifestDiffer.diff(PlacementManifest from, PlacementManifest to, Function<IntPos,BlockSpec> restoreLookup)`・`ManifestDiff`・`RemovalEntry`・`PlacementChange`
- Produces:
  - `record ModifyPlan(JobProgram program, int removals, int additions, int changes, Map<IntPos,BlockSpec> notOurs /*撤去・変更の対象だが、このプロジェクトが置いていない位置と、そこにあるはずのブロック。触らず、上に置きもしない*/)`
  - `ModifyPlanner.plan(PlacementManifest from, PlacementManifest to, PlacedRegistry registry, BiFunction<String,Integer,Set<String>> volatileOfPlacer) → ModifyPlan`: `ManifestDiffer.diff(from, to, pos -> registry.at(pos).map(PlacedEntry::before).orElse(BlockSpec.AIR))`。撤去と変更の位置のうち設置の記録に載る物だけを`RestoreItem`(`expectedNow`=差分の`expectedNow`、`restoreTo`=施工前、材料は置いた人の台帳へ返す、`dropContents=true`)にして**上から下**に並べ、次に、追加と変更の新しい配置を`PutItem`(`index`=新しい施工リストの番号・台帳のキー=同じ)として新しい施工リストの順に並べる。**設置の記録に載らない変更の位置(`notOurs`)の新しい配置は`PutItem`にしない**(Codexのレビュー2026-09-30: 撤去は飛ばしても、同じ位置の新しい配置が残ると、このプロジェクトの物でないブロックの上に置き直してしまう)。「このプロジェクトの物か」は`ReplaceRules`と同じ2つの条件で決まる: 記録に載ること(ここで見る)と、今のブロックが置いたブロックと同じ種類であること(実行の時に`Conflicts.detect`が`expectedNow`と比べて見る。合わなければ撤去の`Conflict`になり、`hasRestoreConflictAt`で同じ位置の設置も止まる)。
  - `static List<Conflict> ModifyPlanner.notOursConflicts(ModifyPlan, Function<IntPos,ObservedBlock> now)`: `notOurs`の各位置を`Conflict(pos, そこにあるはずのブロック, 今の観測, PLAYER_MODIFIED)`にする(位置の順)。`ConstructionRuntime.approve`は、`MODIFY`のジョブを`JobService.admitApproved(..., List<Conflict> knownConflicts)`で入れ、`JobService`はそれを`JobOutcome.addRestoreConflict`で**消えない`Conflict`**として記録する(`resolveConflictAt`で消えず、保存もされる。Task 23の`OutcomeCodec`)。だから、そのジョブは`PARTIAL`で終わり、`status.conflicts`に数えられる。
  - `int ModifyPlanner.conflictPreview(ModifyPlan, PlacementSurvey)`: 撤去・変更の位置のうち、今の状態が`expectedNow`と合わない(`Conflicts.detect`)数。承認の前に見せる(`03` 0.4: 「`Conflict`の予告」)
  - **同じIDで状態の違うこのプロジェクトのブロック**(登録簿は北向きの`oak_stairs`、今は東向き、新しい計画はそこを石にする)は、撤去の手順の`Conflicts.detect`(`BlockMatch.satisfies(今, expectedNow, 稼働で変わる状態)`)で合わないので、**消えない`Conflict`になり、撤去も設置もしない**(ジョブは`PARTIAL`)。同じIDでも状態を直して置き直してよいのは修復(`REPAIR`とL7の修復のラウンド)だけで、`MODIFY`の最初のラウンドの設置は登録簿の記録を自分の物として`ReplaceRules`に渡さない(Task 9の`ownedFor`)。テストは`JobServiceModifyTest.aProjectBlockTurnedByAPlayerIsALastingConflictThatTheModifyDoesNotChange`
  - `ConstructionExecutor`の置く手順は**変えない**: 「同じジョブの撤去で`Conflict`になった位置には置かない」はTask 9で入れてある(`JobOutcome.hasRestoreConflictAt`。撤去の`Conflict`だけを見る。調査の後で置けなくなった位置(`SITE_CHANGED`)の`Conflict`は、邪魔な物がどかされれば置き、報告も消える。P4レビューB-4: 両方を同じ集合で見ると、スキップなしで再開した位置が永久に飛ばされ、3ラウンドの後に`PARTIAL`になる)
  - `JobService`: `MODIFY`のジョブの検査では、置く位置でもある撤去の位置を「戻っているか」の確認から外す(変更は、撤去の後に新しいブロックを置くので)
  - `ServerSurveyor`: `MODIFY`の調査では、設置の記録に載る位置を、その施工前のブロックとして読む(自分の建物を地面と見なさない)
  - コマンド`/micradrone build modify <jobId> <source:greedy>`(`jobId`=変更したい建物のジョブ。新しい計画を`PlanSubmission(plan, EMPTY, MODIFY, jobId, そのジョブの区画)`で提出し、いつもの`approve`で承認)。保留の返事に「こわす N こ、たす N こ、かえる N こ、だれかが かえた ところ N こ(さわらない)」
  - `SubmitOutcome`に`ModifyPlan`の数と`conflictPreview`を足し、`JobViews.submitTree`に`modify`の欄を足す

- [ ] **Step 1: 失敗するテストを書く**

`ModifyPlannerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModifyPlannerTest {
    private static PlacedRegistry builtRegistry(PlacementManifest m) {
        PlacedRegistry reg = new PlacedRegistry("claim-job-1");
        for (Placement p : m.placements()) {
            reg.apply("job-1", new JournalRecord(p.index(), p.pos(), BlockSpec.AIR, false, p.block(), p.index()));
        }
        return reg;
    }

    @Test
    void removalsTopDownFirstThenNewPlacementsAndOnlyOurBlocks() {
        PlacementManifest from = TestManifests.smallHut();
        List<Placement> next = new ArrayList<>(from.placements().subList(0, 24));
        next.set(9, TestManifests.put(0, 64, 0, "minecraft:stone_bricks"));
        next.add(TestManifests.put(5, 64, 5, "minecraft:oak_planks"));
        PlacementManifest to = TestManifests.of(new Box(-2, 55, -2, 6, 70, 6), next);
        PlacedRegistry reg = builtRegistry(from);
        ModifyPlan plan = ModifyPlanner.plan(from, to, reg, (j, k) -> Set.of());
        assertEquals(1, plan.removals());
        assertEquals(1, plan.additions());
        assertEquals(1, plan.changes());
        List<RestoreItem> restores = plan.program().restores();
        assertEquals(2, restores.size(), "the removed top block and the changed wall block");
        assertTrue(restores.get(0).pos().y() >= restores.get(1).pos().y(), "top-down");
        assertEquals(2, plan.program().puts().size(), "the changed block's new state and the added block");
        assertEquals(Map.of(), plan.notOurs());
    }

    @Test
    void aChangedPositionTheProjectDidNotPlaceIsNeitherUndoneNorBuiltOver() {
        PlacementManifest from = TestManifests.smallHut();
        List<Placement> next = new ArrayList<>(from.placements());
        Placement old = next.get(9);
        next.set(9, TestManifests.put(old.pos().x(), old.pos().y(), old.pos().z(), "minecraft:stone_bricks"));
        PlacementManifest to = TestManifests.of(from.worldBounds(), next);
        PlacedRegistry reg = builtRegistry(from);
        // the parent job never placed index 9 (a player's block was there): the registry has no entry for it
        reg.apply("job-1", new JournalRecord(JournalRecord.restoreIndex(0), old.pos(), old.block(), false, BlockSpec.AIR,
                JournalRecord.NO_LEDGER_KEY));
        ModifyPlan plan = ModifyPlanner.plan(from, to, reg, (j, k) -> Set.of());
        assertEquals(Map.of(old.pos(), old.block()), plan.notOurs());
        assertTrue(plan.program().restores().isEmpty(), "not undone");
        assertTrue(plan.program().puts().stream().noneMatch(p -> p.placement().pos().equals(old.pos())), "not built over");
        List<Conflict> known = ModifyPlanner.notOursConflicts(plan, pos -> new ObservedBlock(BlockSpec.of("minecraft:gold_block")));
        assertEquals(List.of(new Conflict(old.pos(), old.block(), new ObservedBlock(BlockSpec.of("minecraft:gold_block")),
                ConflictKind.PLAYER_MODIFIED)), known);
    }

    @Test
    void aPositionThePlayerChangedIsPreviewedAsAConflict() {
        PlacementManifest from = TestManifests.smallHut();
        PlacementManifest to = TestManifests.of(from.worldBounds(), from.placements().subList(0, 24));
        ModifyPlan plan = ModifyPlanner.plan(from, to, builtRegistry(from), (j, k) -> Set.of());
        Map<IntPos, WorldCell> now = new HashMap<>();
        now.put(from.placements().get(24).pos(), WorldCell.of(BlockSpec.of("minecraft:gold_block")));
        assertEquals(1, ModifyPlanner.conflictPreview(plan, new PlacementSurvey(now)));
    }
}
```
`JobServiceModifyTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobServiceModifyTest {
    @Test
    void aModifyFollowsTheDiffAndLeavesThePlayersBlockAlone() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest from = TestManifests.smallHut();
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, from, MaterialPolicy.CREATIVE_FREE), from);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        List<Placement> next = new ArrayList<>(from.placements());
        next.set(10, TestManifests.put(next.get(10).pos().x(), next.get(10).pos().y(), next.get(10).pos().z(), "minecraft:stone_bricks"));
        next.set(11, TestManifests.put(next.get(11).pos().x(), next.get(11).pos().y(), next.get(11).pos().z(), "minecraft:stone_bricks"));
        PlacementManifest to = TestManifests.of(from.worldBounds(), next);
        IntPos touchedByPlayer = from.placements().get(11).pos();
        w.world.setBlock(touchedByPlayer, BlockSpec.of("minecraft:gold_block"));
        ModifyPlan plan = ModifyPlanner.plan(from, to, s.registry("claim-job-1"), (j, k) -> Set.of());
        ConstructionJob job = ConstructionJob.create("job-2", JobServiceTest.A, TestManifests.DIM, to.hash(), JobKind.MODIFY, "job-1",
                plan.program().size(), "claim-job-1", MaterialPolicy.CREATIVE_FREE, c.tick, List.of());
        s.admitApproved(job, to, Map.of(), plan.program(), to.worldBounds(), List.of());
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.PARTIAL));
        assertEquals("minecraft:stone_bricks", w.world.blockAt(from.placements().get(10).pos()).blockId());
        assertEquals("minecraft:gold_block", w.world.blockAt(touchedByPlayer).blockId(), "the player's change is reported, not undone");
        assertEquals(1, s.status("job-2").orElseThrow().conflicts());
    }

    @Test
    void aPositionTheBuildNeverPlacedStaysAPlayersBlockThroughTheModify() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest from = TestManifests.smallHut();
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, from, MaterialPolicy.CREATIVE_FREE), from);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        IntPos pos = from.placements().get(10).pos();
        PlacedRegistry reg = s.registry("claim-job-1");
        // as if the build never placed index 10: a player's plank stands there and the registry does not list it
        reg.apply("job-1", new JournalRecord(JournalRecord.restoreIndex(0), pos, from.placements().get(10).block(), false,
                BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));
        List<Placement> next = new ArrayList<>(from.placements());
        next.set(10, TestManifests.put(pos.x(), pos.y(), pos.z(), "minecraft:stone_bricks"));
        PlacementManifest to = TestManifests.of(from.worldBounds(), next);
        ModifyPlan plan = ModifyPlanner.plan(from, to, reg, (j, k) -> Set.of());
        ConstructionJob job = ConstructionJob.create("job-2", JobServiceTest.A, TestManifests.DIM, to.hash(), JobKind.MODIFY, "job-1",
                plan.program().size(), "claim-job-1", MaterialPolicy.CREATIVE_FREE, c.tick, List.of());
        s.admitApproved(job, to, Map.of(), plan.program(), to.worldBounds(),
                ModifyPlanner.notOursConflicts(plan, p -> w.world.read(p).observed()));
        assertEquals(1, s.status("job-2").orElseThrow().conflicts(), "shown from the start, before any step runs");
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.PARTIAL));
        assertEquals(from.placements().get(10).block(), w.world.blockAt(pos), "neither undone nor built over");
        assertEquals(1, s.status("job-2").orElseThrow().conflicts());
        assertEquals(0, s.status("job-2").orElseThrow().repairRound(), "a known conflict is not repaired");
    }

    @Test
    void aProjectBlockTurnedByAPlayerIsALastingConflictThatTheModifyDoesNotChange() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        IntPos pos = new IntPos(1, 64, 1);
        Box box = new Box(-2, 55, -2, 6, 70, 6);
        PlacementManifest from = TestManifests.of(box, List.of(TestManifests.put(1, 64, 1, "minecraft:oak_stairs", "facing", "north")));
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, from, MaterialPolicy.CREATIVE_FREE), from);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        BlockSpec east = BlockSpec.of("minecraft:oak_stairs", "facing", "east");
        w.world.setBlock(pos, east);
        PlacementManifest to = TestManifests.of(box, List.of(TestManifests.put(1, 64, 1, "minecraft:stone")));
        ModifyPlan plan = ModifyPlanner.plan(from, to, s.registry("claim-job-1"), (j, k) -> Set.of());
        Map<IntPos, WorldCell> now = Map.of(pos, WorldCell.of(east));
        assertEquals(1, ModifyPlanner.conflictPreview(plan, new PlacementSurvey(now)), "the same id in another state is not ours");
        ConstructionJob job = ConstructionJob.create("job-2", JobServiceTest.A, TestManifests.DIM, to.hash(), JobKind.MODIFY, "job-1",
                plan.program().size(), "claim-job-1", MaterialPolicy.CREATIVE_FREE, c.tick, List.of());
        s.admitApproved(job, to, Map.of(), plan.program(), to.worldBounds(), List.of());
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.PARTIAL));
        assertEquals(east, w.world.blockAt(pos), "neither removed nor replaced by the stone");
        assertEquals(1, s.status("job-2").orElseThrow().conflicts());
        assertEquals(0, s.status("job-2").orElseThrow().repairRound(), "a MODIFY does not re-place a turned block; only REPAIR does");
    }
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Modify*" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

`ModifyPlanner.plan`:
```java
    public static ModifyPlan plan(PlacementManifest from, PlacementManifest to, PlacedRegistry registry,
                                  BiFunction<String, Integer, Set<String>> volatileOfPlacer) {
        ManifestDiff diff = ManifestDiffer.diff(from, to, pos -> registry.at(pos).map(PlacedEntry::before).orElse(BlockSpec.AIR));
        List<RestoreItem> restores = new ArrayList<>();
        List<IntPos> undo = new ArrayList<>();
        Map<IntPos, BlockSpec> expected = new HashMap<>();
        for (RemovalEntry r : diff.removals()) {
            undo.add(r.old().pos());
            expected.put(r.old().pos(), r.expectedNow());
        }
        for (PlacementChange c : diff.changes()) {
            undo.add(c.old().pos());
            expected.put(c.old().pos(), c.expectedNow());
        }
        undo.sort(RollbackPlanner.TOP_DOWN);
        // a position the project has no entry for is someone else's block: neither undone nor built over
        Map<IntPos, BlockSpec> notOurs = new LinkedHashMap<>();
        for (IntPos pos : undo) {
            Optional<PlacedEntry> e = registry.at(pos);
            if (e.isEmpty()) {
                notOurs.put(pos, expected.get(pos));
                continue;
            }
            restores.add(new RestoreItem(pos, expected.get(pos), volatileOfPlacer.apply(e.get().jobId(), e.get().placementIndex()),
                    e.get().before(), e.get().jobId(), e.get().placementIndex(), true, e.get().earlierKeys()));
        }
        // attached blocks first, a door's halves back to back (Task 9's Attachments; the sort is stable)
        restores.sort(Attachments.REMOVAL_ORDER);
        List<Placement> news = new ArrayList<>(diff.additions());
        for (PlacementChange c : diff.changes()) {
            news.add(c.now());
        }
        news.sort(Comparator.comparingInt(Placement::index));
        List<PutItem> puts = new ArrayList<>();
        for (Placement p : news) {
            if (!notOurs.containsKey(p.pos())) {
                puts.add(new PutItem(p.index(), p.index(), p));
            }
        }
        return new ModifyPlan(new JobProgram(restores, puts), diff.removals().size(), diff.additions().size(),
                diff.changes().size(), notOurs);
    }

    /** The positions left alone as someone else's blocks, as conflicts the job keeps (JobOutcome.addRestoreConflict). */
    public static List<Conflict> notOursConflicts(ModifyPlan plan, Function<IntPos, ObservedBlock> now) {
        List<Conflict> out = new ArrayList<>();
        plan.notOurs().forEach((pos, expected) -> out.add(new Conflict(pos, expected, now.apply(pos), ConflictKind.PLAYER_MODIFIED)));
        return out;
    }
```
(`TOP_DOWN`は`RollbackPlanner`と同じ比較。**重複させず**、`RollbackPlanner.TOP_DOWN`を`public static final`にして使う。撤去の並びの規則(付いている物が先)も`Attachments.REMOVAL_ORDER`を共有する。)
`conflictPreview`: `program().restores()`の各位置について、`survey.cells()`の観測があり、`Conflicts.detect(pos, expectedNow, observed, volatileProps)`が空でなければ数える。

`ConstructionExecutor`・`JobOutcome`はこのタスクでは変えない(撤去の`Conflict`の位置を飛ばす規則は、Task 9の`hasRestoreConflictAt`がすでに行う)。`JobService.verifyWindow`の撤去の確認に「その位置が施工リストの配置の位置なら飛ばす」を足す(`Set<IntPos> putPositions`を`JobRecord`で1回だけ作る)。

`ServerSurveyor`の`MODIFY`の読み方、コマンド`modify`、保留の返事は「Produces」のとおり。`ConstructionRuntime.approve`で`MODIFY`が承認されたら、`ModifyPlanner.plan(親の施工リスト, 新しい施工リスト, registry, volatileOfPlacer)`の手順と、`ModifyPlanner.notOursConflicts(plan, 世界の読み取り)`で`JobService.admitApproved(job, manifest, nodeTypes, program, box, knownConflicts)`する(`total`は手順の長さ。Task 12の注)。(`JobService.admitApproved`の6引数の形はTask 13で入れてある。5引数の形は`knownConflicts = List.of()`。)`knownConflicts`の位置は、検査で`Deviation`になっても置き直されない(`RepairPlanner`の規則のまま: 設置の記録に無く、このジョブの`Journal`にも施工前の記録が無いので`Conflict`になる。同じ位置の`Conflict`は`JobOutcome`で1回だけ数える)。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件(Task 9の実行のテストと、Task 13の`afterTheObstacleIsRemovedAResumeWithoutSkipBuildsThereAndDropsTheConflict`を含む)。

`tools/p4/scenarios_modify.py`の`modify-conflict`(条件3・13の一部): 小屋を建てて`VERIFIED`→プレイヤーの変更として壁の1つを`setblock gold_block`→`plans.hut_with_extra_wall_patch()`(壁を1枚足し、別の壁の素材を石レンガに変え、ランタンを外した計画)を台本が`run-p4/client/micradrone/plans/modify1.json`に書き、`micradrone build modify <jobId> modify1.json`(`PlanSource`は`micradrone/plans`の下を指すので、`plans/`を付けない。P4レビューB-15)→保留の`modify`の欄(こわす・たす・かえる・`conflictPreview == 1`)→承認→`PARTIAL`(金のブロックをプレイヤーの物として残したので。`lastError`に`conflict=1`。Task 13)→読み戻しで、足した壁と変えた素材が新しい施工リストどおり、外したランタンの位置が施工前(空気)、金のブロックはそのまま・`status.conflicts == 1`→`/client/chat-log`に「だれかが かえた ところが 1 こ あったから、さわらずに のこしたよ」。

同じシナリオで、変更の撤去に扉を含め(計画から扉を外す)、承認→`VERIFIED`の後に`/build/entities {type: "minecraft:item"}`が0(撤去でアイテムが落ちない。P4レビューG-2)。このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件3を消す。

Run: `python -m tools.p4.p4_scenarios --only modify-conflict --mode sp` → `PASS`。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 建てた後の変更(MODIFY。差分から撤去→追加の手順、手で置き換えた位置はConflictとして触らない、承認前の予告)を追加(自然言語→工場建設 P4 Task 29)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 29の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 29)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 30: ブロックエンティティ(元からある物は触らない・このプロジェクトが置いた保管庫・デポ・樽は中身を落として撤去)

**担当**: Step 1〜3とそのコミットはDevin(Java・言語ファイル)。Step 4の実機の自動確認とそのコミットはコントローラ(Claude)。

完了条件13と8(ブロックエンティティを含む置換不可)。純Javaの規則はTask 4(`ReplaceRules`: 元からあるブロックエンティティは`FOREIGN_BLOCK_ENTITY`、空のコンテナだけ確認つきで置換)とTask 28・29(撤去は`dropContents=true`)で済み、中身の落とし方はTask 15の`PlacementGuard.dropContents`(**アイテムの能力`Capabilities.ItemHandler.BLOCK`で取り出す。Createの`item_vault`・`depot`もバニラの樽・チェストも同じ道。Createのクラスはimportしない**)。ここでは、**撤去の前に「中身がN個ドロップします」「液体・燃えている燃料は戻らない」を見せる**ことと、**Createの保管庫・デポを本当に置いて撤去する**実機の自動確認を足す(P4レビューA1-3: 条件13は「保管庫・デポ」と書いている。樽だけで代えない)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/ContainerPreview.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/core/{RemovalPreview,HeldContents}.java`、`tools/p4/scenarios_blockentities.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/{BuildCommands,ConstructionRuntime}.java`(`rollback`の予告・`modify`の保留に中身の数)、`src/main/resources/assets/micradrone/lang/{ja_jp,en_us}.json`(`micradrone.build.rollback.preview`・`micradrone.build.rollback.lost`・`micradrone.build.rollback.started`)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/ChildMessages.java`(3つのキー: `ROLLBACK_PREVIEW`・`ROLLBACK_LOST`・`ROLLBACK_STARTED`。P4レビューB-16)、`tools/p4/p4_scenarios.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{RemovalPreviewTest,ContainerRemovalTest}.java`

**Interfaces:**
- Produces:
  - `record HeldContents(int items, long fluidMillibuckets, boolean burningFuel, Optional<IntPos> sharedWith)` + 3引数の形(`sharedWith`が空)+ `NONE`(ある位置の入れ物が持っている物。純Java)。**複数ブロックの入れ物**(Createの`item_vault`は、つながった全ブロックが同じ1つのアイテムの能力を返す)は、同じ能力を返す位置のうち最初の位置だけが数え、残りは`sharedWith`にその位置を持つ(Codexのレビュー2026-09-30: 位置ごとに足すと、2ブロックの保管庫の17個が34個と予告される)
  - `record RemovalPreview(int blocks, int containers, int items, long lostFluidMillibuckets, int burningFuelBlocks)` + `static RemovalPreview of(List<RestoreItem> items, Map<IntPos,HeldContents> held)`(撤去するブロックの数、そのうち中身のある入れ物の数と、落ちるアイテムの総数、戻らない液体の量(ミリバケツ)、燃えている燃料のブロックの数)
  - `ContainerPreview.read(ServerLevel, List<IntPos>) → Map<IntPos,HeldContents>`(アダプタ): 位置の順に読み、**能力のオブジェクトの同一性**(`IdentityHashMap<Object, IntPos>`。アイテムと液体の能力のそれぞれ)で、前の位置と同じ能力なら`sharedWith`にその位置を入れて数を足さない。アイテムは`level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)`の全スロットの`getStackInSlot(i).getCount()`の合計(能力が無ければ`Container`のスロットの合計)、液体は`Capabilities.FluidHandler.BLOCK`の全タンクの`getFluidInTank(i).getAmount()`の合計(NeoForgeは大釜にもこの能力を付ける。`CauldronFluidContent.java` 168〜170行)、燃料は状態に`lit`があり`true`のとき(かまど・溶鉱炉・燻製器。Createのブレイズバーナーの燃料はP10の部品で扱う)。
- 文言(`ja_jp`): `"micradrone.build.rollback.preview": "こわす ブロック %1$s こ(いれものの なかみ %2$s こは じめんに おとすよ。おとした なかみは、サーバーが とちゅうで とまると なくなる ことが あるよ)。いいなら おなじ コマンドに confirm を つけてね"`、`"micradrone.build.rollback.lost": "みずなどの えきたい %1$s ミリバケツと、もえている ねんりょう %2$s こは もどらないよ"`(`RemovalPreview.losesSomething()`のときだけ、予告の次の行に出す。F-5の最後の行「液体◯mB・燃料◯が失われます」)、`"micradrone.build.rollback.started": "もとに もどしはじめるよ(しごと %1$s)"`。英語も同じ意味で。撤去で入れ物の中身を落とすときは、実行の前にログへ`DropIntent`を書く(Task 9)。落ちた品物はチャンクのデータなので、確定点の前にサーバーが落ちると消えることがある=**ベストエフォート**で、予告の文にそう書き、起動時の復旧はその位置を`status <jobId> --debug`の`possiblyLostDrops`として知らせる(Codexの3回目のレビュー2026-09-30)。

- [ ] **Step 1: 失敗するテストを書く**(担当: Devin)

`RemovalPreviewTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RemovalPreviewTest {
    private static RestoreItem undo(int x, String id) {
        return new RestoreItem(new IntPos(x, 64, 0), BlockSpec.of(id), Set.of(), BlockSpec.AIR, "j", x, true);
    }

    @Test
    void anOwnedTankOfWaterIsDisclosedAndNeedsTheDestructiveConfirmation() {
        RemovalPreview tank = RemovalPreview.of(List.of(undo(0, "create:fluid_tank")),
                Map.of(new IntPos(0, 64, 0), new HeldContents(0, 1000, false)));
        assertEquals(new RemovalPreview(1, 0, 0, 1000, 0), tank);
        assertEquals(java.util.Optional.of(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED), tank.check(Confirmations.NONE),
                "1,000 mB cannot be picked up again");
        assertEquals(java.util.Optional.empty(), tank.check(new Confirmations(false, true)));
        RemovalPreview barrel = RemovalPreview.of(List.of(undo(1, "minecraft:barrel")),
                Map.of(new IntPos(1, 64, 0), new HeldContents(5, 0, false)));
        assertEquals(java.util.Optional.empty(), barrel.check(Confirmations.NONE), "dropped items can be picked up again");
    }

    @Test
    void aVaultOfTwoBlocksSharingOneInventoryCountsItOnce() {
        IntPos first = new IntPos(0, 64, 0);
        List<RestoreItem> vault = List.of(undo(0, "create:item_vault"), undo(1, "create:item_vault"));
        Map<IntPos, HeldContents> held = Map.of(first, new HeldContents(17, 0, false),
                new IntPos(1, 64, 0), new HeldContents(17, 0, false, java.util.Optional.of(first)));
        assertEquals(new RemovalPreview(2, 1, 17, 0, 0), RemovalPreview.of(vault, held), "17 apples, not 34");
    }

    @Test
    void theOwnerIsToldHowManyItemsWillDropAndWhatIsLost() {
        List<RestoreItem> items = List.of(undo(0, "minecraft:stone"), undo(1, "create:item_vault"), undo(2, "create:depot"),
                undo(3, "minecraft:water_cauldron"), undo(4, "minecraft:furnace"));
        Map<IntPos, HeldContents> held = Map.of(
                new IntPos(1, 64, 0), new HeldContents(17, 0, false),
                new IntPos(2, 64, 0), new HeldContents(1, 0, false),
                new IntPos(3, 64, 0), new HeldContents(0, 1000, false),
                new IntPos(4, 64, 0), new HeldContents(3, 0, true));
        assertEquals(new RemovalPreview(5, 3, 21, 1000, 1), RemovalPreview.of(items, held));
        assertEquals(new RemovalPreview(1, 0, 0, 0, 0), RemovalPreview.of(List.of(undo(0, "minecraft:stone")), Map.of()),
                "a position without an entry holds nothing");
    }
}
```

`ContainerRemovalTest.java`(D-25を、ブロックエンティティを持つ偽の世界で確かめる。`FakeWorld.blockEntity(id, type)`はTask 4/5の修正ラウンド2で入った物):
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D-25 with real block entities in the fake world (FakeWorld.blockEntity): only the project's containers are removed. */
class ContainerRemovalTest {
    private static final IntPos POS = new IntPos(1, 64, 1);
    private static final Box SITE = new Box(0, 60, 0, 4, 70, 4);

    private static ExecutionContext ctx(JobProgram program, FakeWorld w, PlacedRegistry registry, LedgerBook ledgers) {
        return ConstructionExecutorTest.ctx(ConstructionExecutorTest.running(MaterialPolicy.CREATIVE_FREE, program.size()), program,
                w, MaterialPort.FREE, new Journal(), ledgers, registry, new JobOutcome(), false);
    }

    @Test
    void aBarrelThisProjectPlacedIsRemovedWithItsItemsDroppedFirst() {
        FakeWorld w = new FakeWorld().blockEntity("minecraft:barrel", "minecraft:barrel");
        PlacementManifest m = TestManifests.of(SITE, List.of(TestManifests.put(1, 64, 1, "minecraft:barrel", "facing", "up")));
        ExecutionContext build = ctx(JobProgram.build(m), w, new PlacedRegistry("claim-job-1"), new LedgerBook());
        assertNull(ConstructionExecutor.run(build, 0, 10).pause());
        assertTrue(w.read(POS).observed().hasBlockEntity(), "the fake keeps the barrel's block entity");
        w.putItems(POS, 5);
        JobProgram rollback = new JobProgram(RollbackPlanner.plan(build.registry(), (job, key) -> Set.of()), List.of());
        assertNull(ConstructionExecutor.run(ctx(rollback, w, build.registry(), build.ledgers()), 0, 10).pause());
        assertEquals(BlockSpec.AIR, w.blockAt(POS));
        assertEquals(0, w.itemsIn(POS));
        assertTrue(w.log.contains("drop 1,64,1 5"), "the five items dropped before the barrel went: " + w.log);
    }

    @Test
    void aPlayersChestUnderAPlacementIsNeverTouched() {
        FakeWorld w = new FakeWorld().blockEntity("minecraft:chest", "minecraft:chest");
        w.place(POS, BlockSpec.of("minecraft:chest"), Map.of(), new UUID(0, 9));
        w.putItems(POS, 3);
        int writes = w.log.size();
        PlacementManifest m = TestManifests.of(SITE, List.of(TestManifests.put(1, 64, 1, "minecraft:stone")));
        ExecutionContext build = ctx(JobProgram.build(m), w, new PlacedRegistry("claim-job-1"), new LedgerBook());
        assertEquals(PauseReason.SITE_CHANGED, ConstructionExecutor.run(build, 0, 10).pause());
        assertEquals("minecraft:chest", w.blockAt(POS).blockId());
        assertEquals(3, w.itemsIn(POS));
        assertEquals(writes, w.log.size(), "not a single write");
        assertEquals(1, build.outcome().conflicts().size());
    }
}
```

- [ ] **Step 2〜3: 失敗を確かめ、実装する**(担当: Devin)

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.RemovalPreviewTest" --tests "io.github.khayashi4337.micradrone.construction.core.ContainerRemovalTest" --console=plain` → FAIL(`RemovalPreview`が無いコンパイルエラー)。`RemovalPreview`・`HeldContents`は下のとおり(`sharedWith`が他の位置を指す位置は数えない)。

`HeldContents.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;
import java.util.Optional;

/**
 * What the container at one position holds. A multi-block container (Create's item vault) answers with one shared
 * inventory at every block: {@code sharedWith} names the position that reports it, so it is counted once.
 */
public record HeldContents(int items, long fluidMillibuckets, boolean burningFuel, Optional<IntPos> sharedWith) {
    public static final HeldContents NONE = new HeldContents(0, 0, false);

    public HeldContents {
        Objects.requireNonNull(sharedWith, "sharedWith");
    }

    public HeldContents(int items, long fluidMillibuckets, boolean burningFuel) {
        this(items, fluidMillibuckets, burningFuel, Optional.empty());
    }
}
```
`RemovalPreview.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a removal will do to the owner's things (04 F-5): the blocks removed, the containers among them that hold items
 * and how many items drop, the fluid that is lost and the burning fuel. A shared inventory counts once. Dropped items can
 * be picked up again; fluid and burning fuel cannot, so a removal that loses any needs the owner's confirmation (F-5).
 */
public record RemovalPreview(int blocks, int containers, int items, long lostFluidMillibuckets, int burningFuelBlocks) {
    public static RemovalPreview of(List<RestoreItem> items, Map<IntPos, HeldContents> held) {
        int containers = 0;
        int count = 0;
        long fluid = 0;
        int burning = 0;
        for (RestoreItem ri : items) {
            HeldContents h = held.getOrDefault(ri.pos(), HeldContents.NONE);
            if (h.sharedWith().isPresent() && !h.sharedWith().get().equals(ri.pos())) {
                continue;
            }
            if (h.items() > 0) {
                containers++;
                count += h.items();
            }
            fluid += h.fluidMillibuckets();
            if (h.burningFuel()) {
                burning++;
            }
        }
        return new RemovalPreview(items.size(), containers, count, fluid, burning);
    }

    /** Something that cannot be picked up again is lost (F-5: the fluid in mB and the burning fuel are named). */
    public boolean losesSomething() {
        return lostFluidMillibuckets > 0 || burningFuelBlocks > 0;
    }

    /** A MODIFY whose removals lose something is approved only with the destructive confirmation. */
    public Optional<ApprovalRejection> check(Confirmations confirmations) {
        return losesSomething() && !confirmations.destructive() ? Optional.of(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED)
                : Optional.empty();
    }
}
```
`ChildMessagesTest`が新しい3つのキーの存在を検査する(`ChildMessages.FIXED`に足す)。`ConstructionRuntime`の`rollback`の予告と`modify`の保留は、撤去の手順の位置を`ContainerPreview.read`で読んでから`RemovalPreview.of`を作り、`ROLLBACK_PREVIEW`(と、`losesSomething()`なら`ROLLBACK_LOST`)を送る。**`MODIFY`の承認は、撤去の予告が何かを失う(`RemovalPreview.check(confirmations)`)なら`confirmDestructive`なしで`DESTRUCTIVE_UNCONFIRMED`**(`ApprovalDesk`の検査の前に`ConstructionRuntime.approve`が見る。ロールバックは、いつも`confirm`の一手が要る)。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。(担当: Devin。ここまでで1つ目のコミット)

`tools/p4/scenarios_blockentities.py`の`block-entities`(条件13・8、`mode="sp"`。担当: コントローラ):
1. 中身の入ったチェスト(プレイヤーの物)を、小屋の基礎の位置に置く→提出→保留に`E-SITE-BLOCKED`(`foreign_block_entity`)で、承認が`BLOCKING_ISSUES`→チェストと中身はそのまま(読み戻しと`data get block`の出力)。
2. 同じ位置の空のチェスト→`confirmDestructive`なしで`DESTRUCTIVE_UNCONFIRMED`→確認つきで承認→建つ。ロールバックで**空のチェストとして戻る**(`01`・F-5の例外)。
3. **保管庫・デポ・樽**(条件13の本体): devkitの`/build/inject-manifest`(Task 18。**開発専用**: devkitのjarは開発の起動(`runP4`系の`runId`つき)でだけ読み込まれ、配布するmicradroneのjarには注入の道が無い。注入はサーバーのコンパイル(D-22の許可リストの検査)を**わざと通らない**ので、ここで確かめられるのは撤去と中身の扱いだけで、許可リストの確かめにはならない。P4の部品には保管庫・デポを置く物が無いため)で、**`create:item_vault[axis=x]`を隣り合わせに2つ(つながって1つの保管庫になる)**・`create:depot`・`minecraft:barrel`・`minecraft:water_cauldron[level=3]`を並べて置くジョブを入れ→`VERIFIED`(5つが「このプロジェクトが置いた物」として`PlacedRegistry`に載る)→`/build/fill-container`で保管庫の1つ目にリンゴ17個・デポに鉄のインゴット1個・樽に丸石5個を入れる(`inserted`がそれぞれ17・1・5)→2つ目の保管庫の位置の中身も17と読める(つながっている証拠。`vault.json`)→`rollback <claimId>`の予告に「なかみ 23 こ」(**40こではない**: 共有の中身は1回だけ数える)と、液体の行(「みずなどの えきたい 1000 …」)→`confirm`→5つとも撤去され(読み戻しが施工前)、`/build/entities {type: "minecraft:item"}`で、その周りにリンゴ17・鉄1・丸石5の落ちたアイテムがあり(**リンゴは34ではない**)、**それ以外の品物(保管庫・デポ自身の品物など)は増えていない**(クリエイティブで置いた物なので、撤去は品物を返さない)。`inject.json`に注入の中身を残す。
4. `MODIFY`で樽を外す計画でも、同じく中身が落ちる。

このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件8・13を消す。

Run: `python -m tools.p4.p4_scenarios --only block-entities --mode sp` → `PASS`。

- [ ] **Step 5: コミット**

Devin(Step 1〜4の単体テストまで):
```text
git add <Filesの src の各ファイルを1つずつ>
git commit -F <scratch>\commit_msg.txt
```
```text
feat: 撤去の前にコンテナの中身・戻らない液体と燃料を見せる予告を追加(自然言語→工場建設 P4 Task 30)

Implemented-by: SWE-2 via Devin CLI
```
コントローラ(実機の自動確認):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: ブロックエンティティ(元からある物は触らない・置いた保管庫・デポ・樽は中身を落とす)の実機の自動確認を追加(自然言語→工場建設 P4 Task 30)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 31: チャンクとオフライン(F-13)— 読み込まれていなければ止まり、戻れば再開、設定でチャンクを保持

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/ChunkSet.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/ChunkKeeper.java`、`tools/p4/scenarios_offline.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/MicraDrone.java`(`RegisterTicketControllersEvent`の登録。**modのバス**)、`src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(ジョブの状態に合わせたチャンクの保持と解放)、`src/main/java/io/github/khayashi4337/micradrone/construction/RuntimeJobWorld.java`(`mayRunWithoutOwner`)、`tools/p4/p4_scenarios.py`
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{ChunkSetTest,JobServiceOfflineTest}.java`

**Interfaces:**
- Consumes: NeoForge: `RegisterTicketControllersEvent`(`net/neoforged/neoforge/common/world/chunk/RegisterTicketControllersEvent.java`。**`IModBusEvent`なので、modのバスで受ける**: `MicraDrone`のコンストラクタで`modEventBus.addListener(RegisterTicketControllersEvent.class, e -> e.register(ChunkKeeper.CONTROLLER))`。ゲームのバス(`NeoForge.EVENT_BUS`、`ConstructionRuntime.Events`)に登録すると、`IModBusEvent`は受け付けられず例外になる(`RegisterTicketControllersEvent.java` 16行、`NeoForge.java` 17〜20行。P4レビューD-4))、`TicketController(ResourceLocation)`・`forceChunk(ServerLevel, UUID owner, int chunkX, int chunkZ, boolean add, boolean ticking)`(`TicketController.java`の67行)
- Produces:
  - `ChunkSet.of(Box worldBox) → SortedSet<Long>`(箱にかかるチャンクの座標。`ChunkSet.pack(int cx, int cz)`・`unpackX`・`unpackZ`。`cx = x >> 4`)
  - `ChunkKeeper`: `TicketController`の`micradrone:construction`。設定`chunks.continueWhileOffline`が`true`のときだけ、終わっていないジョブの区画の`worldBox`のチャンクを`forceChunk(level, ownerUuid, cx, cz, true, true)`で保持し、ジョブが終わる(`terminal()`)か`PAUSED(USER|RECOVERY_NEEDED|SITE_CHANGED)`になったら外す。**既定はOFF**(F-13の「強制読み込みは既定でオフ」)。
  - `RuntimeJobWorld.mayRunWithoutOwner(job)`(Task 13の`JobWorld`): 設定`continueWhileOffline`が真で、`ChunkKeeper`がそのジョブの区画のチャンクを全部保持していて、置く主体の`FakePlayer`をそのディメンションで作れるときだけ真(Task 16で入れた仮の`false`を置き換える)。`JobService`は、所有者がいなくても、設定と**この答えの両方**が真のジョブだけを進める(`JobServiceOfflineTest`)。OFFのときは、所有者が離れれば`PAUSED(OWNER_OFFLINE)`、チャンクが読み込まれていなければ`PAUSED(CHUNK_UNLOADED)`(Task 9・13)で、戻れば自動で再開(Task 13の`RETRY_INTERVAL_TICKS`)。

- [ ] **Step 1: 失敗するテストを書く**

`ChunkSetTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkSetTest {
    @Test
    void theChunksUnderABoxIncludingNegativeCoordinates() {
        var chunks = ChunkSet.of(new Box(-1, 0, 15, 16, 10, 16));
        assertEquals(6, chunks.size(), "x chunks -1,0,1 by z chunks 0,1");
        long first = chunks.first();
        assertEquals(List.of(-1, 0), List.of(ChunkSet.unpackX(first), ChunkSet.unpackZ(first)));
    }
}
```

`JobServiceOfflineTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import org.junit.jupiter.api.Test;

class JobServiceOfflineTest {
    @Test
    void workGoesOnWithoutTheOwnerOnlyWhenTheAdapterSaysThisJobMay() {
        JobService s = new JobService(BudgetConfig.defaults(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER),
                BuildingParts.registry(), true, true);
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        PlacementManifest m = TestManifests.smallHut();
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.PAUSED));
        assertEquals(PauseReason.OWNER_OFFLINE, s.status("job-1").orElseThrow().shownPause(),
                "the setting alone is not enough: the chunks are not held and there is no actor");
        w.mayRunOffline.add("job-1");
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
    }
}
```

- [ ] **Step 2〜3: 失敗を確かめ、実装する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.ChunkSetTest" --tests "io.github.khayashi4337.micradrone.construction.core.JobServiceOfflineTest" --console=plain` → FAIL。`ChunkSet.pack`は`((long) cx << 32) | (cz & 0xffffffffL)`、`unpackZ`は下位32ビットの符号つき、`of`は`box.minA() >> 4`〜`box.maxA() >> 4`と`box.minC() >> 4`〜`box.maxC() >> 4`の全組を`TreeSet`に。`ChunkKeeper`は上のとおり。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_offline.py`(専用サーバー+クライアント、`--mode mp`):
1. `offline-chunk`(F-13・条件4の再開): (a)大きめの小屋を建て始め、`tp Dev +1000 ~ ~`(チャンクが外れる距離)→`status.pause == CHUNK_UNLOADED`であること(所有者はいるので`OWNER_OFFLINE`ではない)を確かめ、30秒の間`cursor`が進まない→`tp`で戻る→2秒以内に`RUNNING`に戻り、`VERIFIED`。(b)建てている途中で`/client/disconnect`→`OWNER_OFFLINE`→`/client/connect 127.0.0.1 25565`→再開→`VERIFIED`。(c)`run-p4/server/world/serverconfig/micradrone-server.toml`の`continueWhileOffline=true`(台本がサーバーを止めて書き換え、起動し直す。専用サーバーの設定の場所。P4レビューF-9)で、途中で`/client/disconnect`しても`RUNNING`のまま進み、`VERIFIED`(主体は`FakePlayer`。`s9-protection-offline`と同じ仕組み)。すべての場合で読み戻しの照合が不一致0件。

2. `s9-protection-offline`(S-9、`mode="mp"`。Task 26から分けた物): `continueWhileOffline=true`のまま、`/spike/protect-box`で小屋の壁の3ブロックを囲って有効にし、建て始めて`/client/disconnect`→所有者がいない間に置かれる位置の主体が`FakePlayer`(`/spike/protect-log`の`fakePlayer: true`)で、囲った3位置が`denied`で残らない。結果を`docs/investigations/spk_s9_place_event.md`の4節に足す。

Run: `python -m tools.p4.p4_scenarios --only offline-chunk,s9-protection-offline --mode mp` → `PASS`(EULAが無ければ`NOT-RUN(eula)`。Task 19)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/ChunkSetTest.java
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/JobServiceOfflineTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: チャンクとオフライン(読み込まれていなければ止まる・戻れば再開・設定でチャンクを保持)と自動確認を追加(自然言語→工場建設 P4 Task 31)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 31の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 31)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 32: 分割ペイロード(30KBのチャンク・全体2MB・ハッシュ検証)、承認のペイロード、問い合わせ経路(F-8)、クライアントのデバッグコマンド、S-6の測定

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/{UploadChunk,UploadResult,UploadAssembler,PlanChunker,QueryKind,PendingQueries,SurveyCodec}.java`、`src/main/java/io/github/khayashi4337/micradrone/construction/net/{SubmitPlanPayload,PlanPreviewPayload,ApprovePlanPayload,JobStatusPayload,QueryRequestPayload,QueryResponsePayload,BuildNetHandlers}.java`、`src/main/java/io/github/khayashi4337/micradrone/client/build/{ClientBuildCommands,ClientUploads,ClientQueries}.java`、`tools/p4/scenarios_net.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/MicraDrone.java`(ペイロードの登録。既存の登録は変えない)、`src/main/java/io/github/khayashi4337/micradrone/MicraDroneClient.java`(クライアントのコマンド・受信)、`src/main/java/io/github/khayashi4337/micradrone/construction/ConstructionRuntime.java`(`lastUploadTree`)、`src/main/java/io/github/khayashi4337/micradrone/build/model/Hashing.java`(`sha256Hex(byte[])`)、devkit(`/client/upload`・`/client/approve-remote`の実装。`/spike/send-probe`はTask 18で作ってある)、`docs/investigations/spk_s6_payload_limits.md`(5節の結果)、`tools/p4/p4_scenarios.py`、`tools/p4/plans.py`(`many_parts_patch(n)`: 柱をn本並べた計画。P4レビューB-11)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/{PlanChunkerTest,UploadAssemblerTest,PendingQueriesTest,SurveyCodecTest,QueryArgsTest}.java`

**Interfaces:**
- Produces:
  - `record UploadChunk(String uploadId, int seq, int total, String sha256, byte[] bytes)`(`UPLOAD_ID = [a-z0-9-]{1,32}`)
  - `PlanChunker`: `CHUNK_MAX_BYTES = 30 * 1024`(F-3。S-6の結果に関わらず正しい値。Task 1)、`MAX_UPLOAD_BYTES = PlanSource.MAX_PLAN_BYTES`(2MB。F-3)、`MAX_CHUNKS = (MAX_UPLOAD_BYTES + CHUNK_MAX_BYTES - 1) / CHUNK_MAX_BYTES`、`static List<UploadChunk> split(String uploadId, byte[] data)`(全体のSHA-256を各チャンクに付ける)
  - `sealed interface UploadResult { Pending(int received, int total), Complete(byte[] data), Rejected(String reason) }`
  - `UploadAssembler`: `UPLOAD_START_COOLDOWN_TICKS = 100`(新しい送信を始められる間隔=5秒。F-3の「レート制限あり」の値。設計に数値なし)、`UPLOAD_IDLE_TIMEOUT_TICKS = 600`(30秒チャンクが来なければ捨てる。設計に数値なし)、`UploadResult accept(UUID player, UploadChunk chunk, long tick)`(プレイヤーごとに同時1件、`seq`の範囲・`total`と`sha256`の一致・大きさ・同じ`seq`の重複(同じ中身は無視、違う中身は拒否)・全体の上限・完成時のハッシュ検証)、`void expire(long tick)`
  - `enum QueryKind {SITE_SURVEY, JOB_STATUS, JOBS, MANIFEST, PENDING}`、`QUERY_ARGS_MAX_BYTES = 30 * 1024`(F-8)、`PendingQueries`(クライアント側): `QUERY_TIMEOUT_MS = 5_000`(F-8)、`CompletableFuture<String> register(String requestId, long nowMillis)`、`void accept(UploadChunk part)`(**サーバーの答えの組み立て**: 答えは`PlanChunker`で分けて届く。`requestId`ごとに、順不同で集め、同じ`seq`の同じ中身は無視、違う中身・`total`や`sha256`の食い違い・範囲外の`seq`・30KBを超える部分・全体で2MBを超える答え・全体のハッシュの不一致は、その問い合わせを失敗させる。知らない`requestId`は無視。Codexのレビュー2026-09-30: 前の版は、分けて送る答えをクライアントで組み立てる所が無かった)、`void complete(String requestId, String json)`(1つで届く答え。`accept`の1部分の形)、`void expire(long nowMillis)`(期限切れは`TimeoutException`で失敗。途中まで集めた部分も捨てる)
  - `SurveyCodec`(`SiteSurvey`↔木。クライアントが同じ調査でコンパイルし直すため)
  - `QueryArgs.check(String kind, String argsJson) → Optional<String>`(サーバー側の問い合わせの検査。F-8: 知らない`kind`、`QUERY_ARGS_MAX_BYTES`を超える引数、`SITE_SURVEY`の範囲が`SafetyLimits`の最大の箱を超える要求は、理由を返して答えない。答えが30KBを超えれば`PlanChunker.split`で分けて送る。P4レビューA1-10)
  - ペイロード(`construction.net`。`TYPE`は`micradrone:build_submit`などの名前付き定数): `SubmitPlanPayload(String uploadId, int seq, int total, String sha256, byte[] bytes)`(C2S。**計画の本文だけ**を運ぶ。展開結果・施工リストの欄は無い=サーバーはクライアントの計算を受け取れない。F-3)、`PlanPreviewPayload(byte[] json)`(S2C。`JobViews.submitTree`)、`ApprovePlanPayload(String manifestHash, List<String> acceptedRiskIds, boolean confirmTerraform, boolean confirmDestructive, String ownerName)`(C2S)、`JobStatusPayload(byte[] json)`(S2C)、`QueryRequestPayload(String requestId, String kind, String argsJson)`(C2S。`argsJson`は30KBまで)、`QueryResponsePayload(String requestId, int seq, int total, String sha256, byte[] bytes)`(S2C。`PlanChunker`で分けて送る。クライアントの`ClientQueries`は`UploadChunk`にして`PendingQueries.accept`へ)。本文は`ByteBufCodecs.byteArray(上限)`(Task 1: 文字列の32,767文字の上限を避ける)
  - `BuildNetHandlers`: 受信の検査(大きさ・IDの形・プレイヤーがいること。F-22)の後、`UploadAssembler`→`Complete`なら`PlanFileReader.read`→`ConstructionRuntime.submit`→保留ができたら`PlanPreviewPayload`を返す。`ApprovePlanPayload`→`ConstructionRuntime.approve`(承認者の今のディメンションで。D-27)→`JobStatusPayload`。`QueryRequestPayload`→`kind`ごとの`JobViews`・`SurveyCodec`のJSON→`QueryResponsePayload`。
  - クライアントのコマンド(`RegisterClientCommandsEvent`。サーバーの`/micradrone`と重ならないよう`/micradrone-client`): `build upload <path>`(`<gameDir>/micradrone/plans/`の中のファイルを`PlanChunker.split`で送る。`PlanSource.resolve`で同じ検査)、`build approve-remote <hash> [flags]`(`ApproveArgs`で解釈し`ApprovePlanPayload`を送る)。`PlanPreviewPayload`を受けたら、`QueryRequestPayload(SITE_SURVEY, 保留の調査のダイジェスト)`で調査を取り寄せ、**クライアントでも同じ計画を同じ調査でコンパイルし(作業スレッド)、サーバーのハッシュと比べる**(D-6)。一致しなければ`E-REGISTRY-VERSION`の子供向けの文(「ぶひんの リストの バージョンが ちがうよ」)を出す。

- [ ] **Step 1: 失敗するテストを書く**

`PlanChunkerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PlanChunkerTest {
    private static final long SEED = 4337L;

    @Test
    void aLargePlanIsSplitIntoChunksOfAtMost30KbCarryingTheWholeHash() {
        byte[] data = new byte[PlanChunker.CHUNK_MAX_BYTES * 2 + 5];
        new Random(SEED).nextBytes(data);
        List<UploadChunk> chunks = PlanChunker.split("up-1", data);
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.bytes().length <= PlanChunker.CHUNK_MAX_BYTES));
        assertTrue(chunks.stream().allMatch(c -> c.total() == 3 && c.sha256().equals(chunks.get(0).sha256())));
        assertEquals(30 * 1024, PlanChunker.CHUNK_MAX_BYTES);
        assertThrows(IllegalArgumentException.class, () -> PlanChunker.split("up-1", new byte[PlanChunker.MAX_UPLOAD_BYTES + 1]));
    }
}
```
`UploadAssemblerTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadAssemblerTest {
    private static final UUID P = new UUID(0, 1);
    private static final long SEED = 4337L;

    private static byte[] data(int n) {
        byte[] d = new byte[n];
        new Random(SEED).nextBytes(d);
        return d;
    }

    @Test
    void chunksInAnyOrderReassembleToTheExactBytes() {
        byte[] d = data(PlanChunker.CHUNK_MAX_BYTES * 3 + 1);
        List<UploadChunk> chunks = new ArrayList<>(PlanChunker.split("up-1", d));
        Collections.shuffle(chunks, new Random(SEED));
        UploadAssembler a = new UploadAssembler();
        UploadResult last = null;
        for (UploadChunk c : chunks) {
            last = a.accept(P, c, 0L);
        }
        assertArrayEquals(d, assertInstanceOf(UploadResult.Complete.class, last).data());
    }

    @Test
    void tamperingOversizeAndWrongShapesAreRefused() {
        UploadAssembler a = new UploadAssembler();
        List<UploadChunk> chunks = PlanChunker.split("up-1", data(PlanChunker.CHUNK_MAX_BYTES + 10));
        UploadChunk bad = new UploadChunk("up-1", 1, 2, chunks.get(1).sha256(), new byte[10]);
        a.accept(P, chunks.get(0), 0L);
        assertInstanceOf(UploadResult.Rejected.class, a.accept(P, bad, 1L), "the whole hash does not match");
        UploadAssembler b = new UploadAssembler();
        assertInstanceOf(UploadResult.Rejected.class, b.accept(P, new UploadChunk("up-2", 5, 2, "x", new byte[1]), 0L), "seq");
        assertInstanceOf(UploadResult.Rejected.class, b.accept(P, new UploadChunk("UP!", 0, 1, "x", new byte[1]), 0L), "id");
        assertInstanceOf(UploadResult.Rejected.class, b.accept(P, new UploadChunk("up-3", 0, PlanChunker.MAX_CHUNKS + 1, "x",
                new byte[1]), 0L), "too many chunks");
        assertInstanceOf(UploadResult.Rejected.class, b.accept(P, new UploadChunk("up-4", 0, 1, "x",
                new byte[PlanChunker.CHUNK_MAX_BYTES + 1]), 0L), "a chunk over 30 KB");
    }

    @Test
    void onePlayerOneUploadWithACooldownAndAnIdleTimeout() {
        UploadAssembler a = new UploadAssembler();
        List<UploadChunk> first = PlanChunker.split("up-1", data(PlanChunker.CHUNK_MAX_BYTES + 1));
        a.accept(P, first.get(0), 0L);
        assertInstanceOf(UploadResult.Rejected.class, a.accept(P, PlanChunker.split("up-2", data(3)).get(0), 1L),
                "one upload at a time");
        a.expire(UploadAssembler.UPLOAD_IDLE_TIMEOUT_TICKS + 1L);
        assertInstanceOf(UploadResult.Complete.class,
                a.accept(P, PlanChunker.split("up-3", data(3)).get(0), UploadAssembler.UPLOAD_IDLE_TIMEOUT_TICKS + 2L));
        assertInstanceOf(UploadResult.Rejected.class,
                a.accept(P, PlanChunker.split("up-4", data(3)).get(0), UploadAssembler.UPLOAD_IDLE_TIMEOUT_TICKS + 3L),
                "a new upload within the cooldown");
    }
}
```
`PendingQueriesTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class PendingQueriesTest {
    private static final long NOW = 1_000L;

    /** A JSON answer of about 3.5 parts. */
    private static String bigAnswer() {
        StringBuilder sb = new StringBuilder("{\"cells\":\"");
        Random r = new Random(4337L);
        while (sb.length() < PlanChunker.CHUNK_MAX_BYTES * 3 + PlanChunker.CHUNK_MAX_BYTES / 2) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.append("\"}").toString();
    }

    /** Never blocks: a query that neither answered nor failed is a failure of the test, not a hang. */
    private static Throwable cause(CompletableFuture<String> f) {
        assertTrue(f.isCompletedExceptionally(), "the query failed (it is not still waiting)");
        return assertThrows(ExecutionException.class, f::get).getCause();
    }

    private static String answer(CompletableFuture<String> f) throws Exception {
        assertTrue(f.isDone(), "the answer is complete (it is not still waiting)");
        return f.get();
    }

    @Test
    void aSmallAnswerArrivesAndAnUnknownIdIsIgnored() throws Exception {
        PendingQueries q = new PendingQueries();
        CompletableFuture<String> f = q.register("q-1", NOW);
        q.complete("q-9", "{}");
        assertFalse(f.isDone());
        q.complete("q-1", "{\"ok\":true}");
        assertEquals("{\"ok\":true}", answer(f));
    }

    @Test
    void partsInAnyOrderWithARepeatReassembleToTheAnswer() throws Exception {
        String whole = bigAnswer();
        List<UploadChunk> parts = new ArrayList<>(PlanChunker.split("q-1", whole.getBytes(StandardCharsets.UTF_8)));
        assertTrue(parts.size() > 3);
        Collections.shuffle(parts, new Random(4337L));
        parts.add(1, parts.get(0));
        PendingQueries q = new PendingQueries();
        CompletableFuture<String> f = q.register("q-1", NOW);
        for (UploadChunk p : parts) {
            q.accept(p);
        }
        assertEquals(whole, answer(f));
    }

    @Test
    void aPartWithOtherBytesAPartOfAnotherShapeOrAWrongHashFailsTheQuery() {
        byte[] data = bigAnswer().getBytes(StandardCharsets.UTF_8);
        List<UploadChunk> parts = PlanChunker.split("q-1", data);
        PendingQueries q = new PendingQueries();
        CompletableFuture<String> twice = q.register("q-1", NOW);
        q.accept(parts.get(0));
        q.accept(new UploadChunk("q-1", 0, parts.size(), parts.get(0).sha256(), new byte[]{1, 2, 3}));
        assertInstanceOf(IllegalStateException.class, cause(twice), "the same part with other bytes");

        CompletableFuture<String> shape = q.register("q-2", NOW);
        q.accept(new UploadChunk("q-2", 0, 2, "a", new byte[1]));
        q.accept(new UploadChunk("q-2", 1, 3, "a", new byte[1]));
        assertInstanceOf(IllegalStateException.class, cause(shape), "a part of another answer");

        CompletableFuture<String> hash = q.register("q-3", NOW);
        q.accept(new UploadChunk("q-3", 0, 1, parts.get(0).sha256(), "{}".getBytes(StandardCharsets.UTF_8)));
        assertInstanceOf(IllegalStateException.class, cause(hash), "the whole does not match its hash");
    }

    @Test
    void anAnswerThatDoesNotCompleteInTimeFails() {
        PendingQueries q = new PendingQueries();
        CompletableFuture<String> f = q.register("q-1", NOW);
        q.accept(PlanChunker.split("q-1", bigAnswer().getBytes(StandardCharsets.UTF_8)).get(0));
        q.expire(NOW + PendingQueries.QUERY_TIMEOUT_MS);
        assertFalse(f.isDone(), "exactly at the deadline it still waits");
        q.expire(NOW + PendingQueries.QUERY_TIMEOUT_MS + 1);
        assertInstanceOf(TimeoutException.class, cause(f));
        q.complete("q-1", "{}");
    }
}
```
`QueryArgsTest.java`(30KB+1バイトの引数・知らない`kind`・129ブロック幅の`SITE_SURVEY`の範囲がそれぞれ理由つきで断られ、小屋の範囲の`SITE_SURVEY`と`JOB_STATUS`は通る)。`SurveyCodecTest.java`(`SiteSurvey.flat(...).withColumn(...)`が木を往復して同じ`digest`)。

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.*Upload*" --tests "io.github.khayashi4337.micradrone.construction.core.PlanChunkerTest" --tests "io.github.khayashi4337.micradrone.construction.core.PendingQueriesTest" --tests "io.github.khayashi4337.micradrone.construction.core.SurveyCodecTest" --console=plain`
Expected: FAIL(コンパイルエラー)。

- [ ] **Step 3: 実装する**

`UploadAssembler`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Hashing;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reassembles a chunked plan upload on the server (04 F-3): one upload per player, a start cooldown, an idle timeout,
 * chunks of at most 30 KB, at most 2 MB in total, and the whole content checked against its announced SHA-256.
 */
public final class UploadAssembler {
    public static final long UPLOAD_START_COOLDOWN_TICKS = 100L;
    public static final long UPLOAD_IDLE_TIMEOUT_TICKS = 600L;
    static final Pattern UPLOAD_ID = Pattern.compile("[a-z0-9-]{1,32}");

    private static final class Upload {
        final String id;
        final int total;
        final String sha256;
        final byte[][] parts;
        int received;
        long bytes;
        long lastTick;

        Upload(UploadChunk first, long tick) {
            id = first.uploadId();
            total = first.total();
            sha256 = first.sha256();
            parts = new byte[total][];
            lastTick = tick;
        }
    }

    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();

    public UploadResult accept(UUID player, UploadChunk c, long tick) {
        if (!UPLOAD_ID.matcher(c.uploadId()).matches()) {
            return new UploadResult.Rejected("bad upload id");
        }
        if (c.total() < 1 || c.total() > PlanChunker.MAX_CHUNKS || c.seq() < 0 || c.seq() >= c.total()) {
            return new UploadResult.Rejected("bad chunk numbers");
        }
        if (c.bytes().length > PlanChunker.CHUNK_MAX_BYTES) {
            return new UploadResult.Rejected("chunk over " + PlanChunker.CHUNK_MAX_BYTES + " bytes");
        }
        Upload u = uploads.get(player);
        if (u == null) {
            Long last = lastStart.get(player);
            if (last != null && tick - last < UPLOAD_START_COOLDOWN_TICKS) {
                return new UploadResult.Rejected("too soon after the last upload");
            }
            u = new Upload(c, tick);
            uploads.put(player, u);
            lastStart.put(player, tick);
        } else if (!u.id.equals(c.uploadId())) {
            return new UploadResult.Rejected("another upload is in progress");
        }
        if (c.total() != u.total || !c.sha256().equals(u.sha256)) {
            uploads.remove(player);
            return new UploadResult.Rejected("chunks disagree about the upload");
        }
        byte[] prev = u.parts[c.seq()];
        if (prev != null) {
            if (Arrays.equals(prev, c.bytes())) {
                return new UploadResult.Pending(u.received, u.total);
            }
            uploads.remove(player);
            return new UploadResult.Rejected("a chunk was sent twice with different bytes");
        }
        u.bytes += c.bytes().length;
        if (u.bytes > PlanChunker.MAX_UPLOAD_BYTES) {
            uploads.remove(player);
            return new UploadResult.Rejected("over " + PlanChunker.MAX_UPLOAD_BYTES + " bytes");
        }
        u.parts[c.seq()] = c.bytes().clone();
        u.received++;
        u.lastTick = tick;
        if (u.received < u.total) {
            return new UploadResult.Pending(u.received, u.total);
        }
        uploads.remove(player);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : u.parts) {
            all.writeBytes(part);
        }
        byte[] data = all.toByteArray();
        return Hashing.sha256Hex(data).equals(u.sha256) ? new UploadResult.Complete(data)
                : new UploadResult.Rejected("the reassembled plan does not match its hash");
    }

    public void expire(long tick) {
        uploads.values().removeIf(u -> tick - u.lastTick > UPLOAD_IDLE_TIMEOUT_TICKS);
    }
}
```
(`Hashing.sha256Hex(byte[])`は既存に無い(文字列だけ)ので、`build.model.Hashing`に`public static String sha256Hex(byte[] data)`を足す(既存の文字列版はこれを呼ぶ形に。挙動は同じ。`HashingTest`が緑のままで確かめる)。)
`PendingQueries.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Hashing;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * The client's open queries (04 F-8): each request waits up to {@link #QUERY_TIMEOUT_MS} for its answer, which the server
 * may send in parts ({@link PlanChunker}: 30 KB each, the whole answer's SHA-256 on every part). Parts may come in any
 * order; a repeated part with the same bytes is ignored, one with other bytes, a part that does not fit the answer's
 * shape, an answer over the upload limit or a whole that fails its hash fails the query. Unknown request ids are ignored.
 */
public final class PendingQueries {
    public static final long QUERY_TIMEOUT_MS = 5_000L;

    private static final class Open {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final long deadline;
        byte[][] parts;
        String sha256;
        int received;
        long bytes;

        Open(long deadline) {
            this.deadline = deadline;
        }
    }

    private final Map<String, Open> open = new HashMap<>();

    public CompletableFuture<String> register(String requestId, long nowMillis) {
        Open o = new Open(nowMillis + QUERY_TIMEOUT_MS);
        if (open.putIfAbsent(requestId, o) != null) {
            throw new IllegalStateException("request id in use: " + requestId);
        }
        return o.future;
    }

    /** A whole answer in one message (the part form with one part). */
    public void complete(String requestId, String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        accept(new UploadChunk(requestId, 0, 1, Hashing.sha256Hex(data), data));
    }

    public void accept(UploadChunk part) {
        Open o = open.get(part.uploadId());
        if (o == null) {
            return;
        }
        String reason = check(o, part);
        if (reason != null) {
            fail(part.uploadId(), o, reason);
            return;
        }
        if (o.parts[part.seq()] != null) {
            return;
        }
        o.parts[part.seq()] = part.bytes().clone();
        o.received++;
        o.bytes += part.bytes().length;
        if (o.bytes > PlanChunker.MAX_UPLOAD_BYTES) {
            fail(part.uploadId(), o, "the answer is over " + PlanChunker.MAX_UPLOAD_BYTES + " bytes");
            return;
        }
        if (o.received < o.parts.length) {
            return;
        }
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (byte[] p : o.parts) {
            whole.writeBytes(p);
        }
        byte[] data = whole.toByteArray();
        open.remove(part.uploadId());
        if (!Hashing.sha256Hex(data).equals(o.sha256)) {
            o.future.completeExceptionally(new IllegalStateException("the answer does not match its hash"));
            return;
        }
        o.future.complete(new String(data, StandardCharsets.UTF_8));
    }

    private static String check(Open o, UploadChunk part) {
        if (part.total() < 1 || part.total() > PlanChunker.MAX_CHUNKS || part.seq() < 0 || part.seq() >= part.total()) {
            return "part " + part.seq() + " of " + part.total() + " is out of shape";
        }
        if (part.bytes().length > PlanChunker.CHUNK_MAX_BYTES) {
            return "a part over " + PlanChunker.CHUNK_MAX_BYTES + " bytes";
        }
        if (o.parts == null) {
            o.parts = new byte[part.total()][];
            o.sha256 = part.sha256();
            return null;
        }
        if (part.total() != o.parts.length || !part.sha256().equals(o.sha256)) {
            return "a part of another answer";
        }
        byte[] had = o.parts[part.seq()];
        if (had != null && !Arrays.equals(had, part.bytes())) {
            return "part " + part.seq() + " came twice with other bytes";
        }
        return null;
    }

    private void fail(String requestId, Open o, String reason) {
        open.remove(requestId);
        o.future.completeExceptionally(new IllegalStateException(reason));
    }

    public void expire(long nowMillis) {
        for (Iterator<Map.Entry<String, Open>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Open> e = it.next();
            if (nowMillis > e.getValue().deadline) {
                it.remove();
                e.getValue().future.completeExceptionally(new TimeoutException("no answer to " + e.getKey()));
            }
        }
    }
}
```
`PlanChunker.split`: `data.length > MAX_UPLOAD_BYTES`なら`IllegalArgumentException`、`total = max(1, ceil(len / CHUNK_MAX_BYTES))`、各チャンクに`Hashing.sha256Hex(data)`。ペイロード・ハンドラ・クライアントのコマンドは「Produces」のとおり。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_net.py`(`--mode mp`=専用サーバー+クライアント。一部は`sp`でも):
1. `payload-30kb`(条件9・D-6): `plans.many_parts_patch(200)`(柱200本など。JSONで60KB以上)を`run-p4/client/micradrone/plans/big.json`に書き、`/client/upload {path: "big.json"}`→サーバーの`lastUploadTree`で`chunks >= 3`・`shaOk: true`→保留が`OFFERED`→クライアントのチャットに「ハッシュが いっちしたよ」(D-6)→承認して`VERIFIED`。**同じ物を1チャンクだけ1バイト変えて**(`/client/upload {path, corruptChunk: 1}`)送ると`Rejected`(ハッシュ不一致)で保留が作られない。
2. `fake-approval`(条件5): 保留を作ってから`/client/approve-remote {hash: "0"*64}`→`reject.hash_mismatch`の文・`/build/status`にジョブが無い。正しいハッシュでは承認できる。
3. `dimension-bound`(条件5・D-27): オーバーワールドで提出→`execute in minecraft:the_nether run tp Dev 0 100 0`→正しいハッシュで`approve-remote`→`reject.dimension_mismatch`→オーバーワールドに戻って承認→承認できる。
4. `s6-payload-limits`(S-6の測定): Task 1の4の手順。`--mode sp`と`--mode mp`で1回ずつ。結果を`docs/investigations/spk_s6_payload_limits.md`の5節に書く(`run-evidence`の`s6-payload-limits.json`を表にする)。

このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件9を消す。

Run: `python -m tools.p4.p4_scenarios --only payload-30kb,fake-approval,dimension-bound,s6-payload-limits --mode all` → `PASS`(`mp`の分はEULAが無ければ`NOT-RUN(eula)`)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/test/java/io/github/khayashi4337/micradrone/construction/core の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 計画の分割送信(30KB・全体2MB・ハッシュ検証)と承認・状態・問い合わせのペイロード、クライアントでのハッシュの照合、S-6の測定を追加(自然言語→工場建設 P4 Task 32)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4 docs/investigations/spk_s6_payload_limits.md
git commit -m "$(cat <<'MSG'
test: Task 32の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 32)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 33: 性能の線(MSPT)と同時ジョブ・自動減速の自動確認

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/MsptStats.java`、`tools/p4/scenarios_perf.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/{ConstructionRuntime,BuildCommands}.java`(`perf`・`perfTree()`。P4レビューB-13: `MsptStats`が要るのでTask 16ではなくここ)、devkit(`/spike/load {msPerTick}`: **`ServerTickEvent.Pre`で**、指定のミリ秒だけ待って重さを作る開発専用の道具。`0`で止める。`Pre`はtickの時間を測る窓の中にある。P4レビューD-1。`/build/perf`もここで足す)、`tools/p4/p4_scenarios.py`、`tools/p4/plans.py`(`big_block_patch(n)`: ちょうどn配置の計画。P4レビューB-11)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/MsptStatsTest.java`

**Interfaces:**
- Produces: `MsptStats`: `WINDOW = 100`(バニラの`MinecraftServer.tickTimesNanos`と同じ100tickの窓)、`void sample(double tickMs, boolean constructionActive, double workMs)`(**`tickMs`は、そのtick1回の長さ**: `ServerTickEvent.Pre`の最初で`System.nanoTime()`を取り、`ServerTickEvent.Post`で引いた物。`workMs`はTask 16の`lastTickWorkNanos()`をミリ秒にした物=このランタイムの処理そのものの時間)、`double idleMean()`・`double activeMean()`・`double activeMax()`、`double increase()`(`activeMean - idleMean`)、`double maxIncrease()`(`activeMax - idleMean`=15msの線)、`double workMean()`・`double workMax()`、`List<Double> activeTicks()`(窓の中の施工中のtickの長さ。証拠に残す)。コマンド`/micradrone build perf`と`perfTree()`: 今の平均、施工が無い間の平均、施工中の平均と最大、ランタイムの処理の平均と最大、`slowed`、動いているジョブの数、待っているジョブの数。
- **測り方**(P4レビューD-1、Codexのレビュー2026-09-30): 施工の仕事は`ServerTickEvent.Pre`で行う(Task 16)。**判定は1tickごとの長さの列**(`Pre`→`Post`)で行う。`getAverageTickTimeNanos()`は100tickの平均なので、1回だけ重いtickが隠れ、最大の増加(15ms)を判定できない。平均の増加(`increase`)と最大の増加(`maxIncrease`)で完了条件10を判定し、ランタイム自身の時間(`workMean`・`workMax`)も並べて記録する(増加の原因が施工か、それ以外かを見分けるため)。自動減速(Task 7・16)は今までどおり`getAverageTickTimeNanos()`を見る(滑らかな値で速さを変えるため)。

- [ ] **Step 1: 失敗するテストを書く**

`MsptStatsTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MsptStatsTest {
    private static final double EPS = 1e-9;

    @Test
    void idleAndActiveTicksAreKeptApartTickByTickOverTheWindow() {
        MsptStats s = new MsptStats();
        for (int i = 0; i < 10; i++) {
            s.sample(10.0, false, 0.0);
        }
        for (int i = 0; i < 10; i++) {
            s.sample(i % 2 == 0 ? 13.0 : 15.0, true, i % 2 == 0 ? 1.0 : 3.0);
        }
        assertEquals(10.0, s.idleMean(), EPS);
        assertEquals(14.0, s.activeMean(), EPS);
        assertEquals(15.0, s.activeMax(), EPS);
        assertEquals(4.0, s.increase(), EPS);
        assertEquals(5.0, s.maxIncrease(), EPS);
        assertEquals(2.0, s.workMean(), EPS);
        assertEquals(3.0, s.workMax(), EPS);
        assertEquals(List.of(13.0, 15.0, 13.0, 15.0, 13.0, 15.0, 13.0, 15.0, 13.0, 15.0), s.activeTicks());
        for (int i = 0; i < MsptStats.WINDOW; i++) {
            s.sample(20.0, true, 0.5);
        }
        assertEquals(20.0, s.activeMean(), EPS, "old samples leave the window");
        assertEquals(MsptStats.WINDOW, s.activeTicks().size());
    }

    @Test
    void oneHeavyTickShowsInTheMaximumThatAnAverageWouldHide() {
        MsptStats s = new MsptStats();
        for (int i = 0; i < MsptStats.WINDOW; i++) {
            s.sample(10.0, false, 0.0);
            s.sample(i == 50 ? 40.0 : 11.0, true, 0.2);
        }
        assertEquals(11.29, s.activeMean(), EPS, "the mean barely moves");
        assertEquals(30.0, s.maxIncrease(), EPS, "the single 40 ms tick is over the 15 ms line");
    }
}
```

- [ ] **Step 2〜3: 失敗を確かめ、実装する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.MsptStatsTest" --console=plain` → FAIL。`MsptStats`は、施工中と施工の無い間の、それぞれ最後の`WINDOW`個の標本(`ArrayDeque<Double>`)。ランタイムは毎tick、`Pre`で記録した時刻から`Post`までの長さ(ミリ秒)を、ジョブが`RUNNING`・`VERIFYING`・`REPAIRING`の間か否かと、`lastTickWorkNanos() / NANOS_PER_MILLI`と一緒に入れる。`MsptStats`は下のとおり。

`MsptStats.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The per-tick cost of construction (04 F-2 performance line): each tick's own length, measured from
 * ServerTickEvent.Pre to ServerTickEvent.Post (not the server's 100-tick average, which hides a single heavy tick), kept
 * for the last {@link #WINDOW} ticks with and without construction, and the runtime's own work in the ticks with it.
 */
public final class MsptStats {
    public static final int WINDOW = 100;

    private final ArrayDeque<Double> idle = new ArrayDeque<>();
    private final ArrayDeque<Double> active = new ArrayDeque<>();
    private final ArrayDeque<Double> work = new ArrayDeque<>();

    private static void push(ArrayDeque<Double> q, double v) {
        q.addLast(v);
        if (q.size() > WINDOW) {
            q.removeFirst();
        }
    }

    private static double mean(ArrayDeque<Double> q) {
        return q.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double max(ArrayDeque<Double> q) {
        return q.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    /** One tick: its length in ms, whether a job was placing or checking, and the runtime's own work in ms. */
    public void sample(double tickMs, boolean constructionActive, double workMs) {
        if (constructionActive) {
            push(active, tickMs);
            push(work, workMs);
        } else {
            push(idle, tickMs);
        }
    }

    public double idleMean() {
        return mean(idle);
    }

    public double activeMean() {
        return mean(active);
    }

    public double activeMax() {
        return max(active);
    }

    public double increase() {
        return activeMean() - idleMean();
    }

    /** The heaviest single tick with construction, over the quiet ticks' mean (the 15 ms line). */
    public double maxIncrease() {
        return activeMax() - idleMean();
    }

    public double workMean() {
        return mean(work);
    }

    public double workMax() {
        return max(work);
    }

    /** The ticks with construction in the window, oldest first (the evidence keeps them). */
    public List<Double> activeTicks() {
        return List.copyOf(active);
    }
}
```

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_perf.py`(条件10。`--mode mp`=専用サーバー。本番の負荷に近いため):
1. `perf-20000`: `plans.big_block_patch(20_000)`(20,000配置ちょうどの計画。3階建ての大きな建物など。上限の中)を用意→施工の無い状態で60秒、`/build/perf`を1秒ごとに記録(基準)→提出(**このとき、コンパイルがワーカーで走り、提出から保留までの間のサーバーの1tickの時間の最大が50msを超えない**=メインスレッドを止めない)→承認(クリエイティブ、既定の速度)→`VERIFIED`まで記録→**1tickごとの長さの列で、施工中の平均の増加が5ms以内、最も重いtickの増加が15ms以内**(確定点を作ったtick(フラッシュつきの保存。Task 25の`Checkpoints`。ジョブの終わりと5分ごと)は、バニラの自動保存のtickと同じく別の列`checkpointTicks`に分けて記録し、最大の増加の判定からは除く。その回数と長さは`perf.json`に全部残し、林さんへの報告に書く=設計の数値の扱いを変える点。Task 35の食い違いの一覧にも書く)(`perf.json`に基準・施工中の`activeTicks`の列(1秒ごとに取り、重なりを除いてつなぐ)・判定。`/build/perf`は`increase`・`maxIncrease`・列を返す)。合格しないときは、証拠を添えて、原因(何のtickが重いか。`/build/perf`の内訳と、サーバーのログのプロファイル)を調べて直す(設計の数値を緩める場合は、理由を記録して林さんに報告する。`07`の「数値の合格線について」)。
2. `four-jobs`: `continueWhileOffline=true`で、`Dev`と`Intruder1〜3`(devkitの`/server/run-as`)が離れた4か所に小屋を提出・承認→4つが同時に`RUNNING`、5つ目(`Intruder4`)は`QUEUED`で`status.pause == SERVER_BUSY`→その間のサーバー全体の平均MSPTが45ms以内→`/spike/load {msPerTick: 60}`で重さを作る→`/build/perf`の`slowed: true`・動いているジョブの`status.pause == SERVER_BUSY`→`/spike/load {msPerTick: 0}`→40msを下回った後に`slowed: false`→全部`VERIFIED`。

このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件10を消す。

Run: `python -m tools.p4.p4_scenarios --only perf-20000,four-jobs --mode mp` → `PASS`(EULAが無ければ`NOT-RUN(eula)`)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/MsptStatsTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: 施工中のMSPTの計測(perf)と、20,000設置の性能の線・4ジョブ同時と自動減速の自動確認を追加(自然言語→工場建設 P4 Task 33)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 33の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 33)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 34: ログと診断(F-17)・マルチプレイ(F-16)・専用サーバーとクライアント2つの自動確認

**担当**: Java(`src/`)とそのコミットはDevin。台本(`tools/p4`)・devkit・`docs/`・実機の確認とそのコミットはコントローラ(Claude)(Global Constraintsの「担当の分け方」)。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/construction/core/JobEventLog.java`、`tools/p4/scenarios_multiplayer.py`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/construction/{ConstructionRuntime,BuildCommands}.java`(出来事のログの書き出し、`status`・`list`で他人のジョブも見える)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/JobFiles.java`(`jobs/<jobId>/events.log`)
- Test: `src/test/java/io/github/khayashi4337/micradrone/construction/core/JobEventLogTest.java`

**Interfaces:**
- `status <jobId> --debug`(Task 16)は、子供に見せない次のことも出す: 問題のコードとID、答えを待つジョブの理由(`recovery-ambiguous:cut-short:<run>`・`chest:<run>`)、起動時の復旧が知らせた`possiblyLostDrops`(中身を落とした撤去のうち、確定点の前に落ちた物)。
- Produces: `JobEventLog`: `MAX_LINES_PER_JOB = 1_000`(1ジョブの出来事の行の上限。古い行から捨てる。設計に数値なし)、`void add(long tick, JobUpdate u)`(状態が変わった時・止まった時・`Conflict`が出た時だけ1行: `tick jobId STATE pause cursor/total conflicts=n message`)、`List<String> lines(String jobId)`。`/micradrone build status <jobId>`は子供向けの文だけを見せ、`/micradrone build status <jobId> --debug`(所有者かOP)は最後の10行と、`lastError`・問題のコードとIDを見せる(F-17: 「何が・どのIDで・なぜ」。子供のチャットに問題のコードを出さない決定。Task 14)。`status`・`list`は**他人のジョブも見える**(状態と進み具合だけ。F-16)、操作は所有者かOP(Task 13)。F-23のP4の分: ジョブはIDで引ける(`/micradrone build status <jobId>`、F-8の`JOB_STATUS`、devkitの`/build/status`が同じ`JobViews`を返す)ので、P7の`BuildProject.jobIds`はこのIDを持てばよい。

- [ ] **Step 1: 失敗するテストを書く**

`JobEventLogTest.java`:
```java
package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobEventLogTest {
    private static ConstructionJob job() {
        return ConstructionJob.create("job-1", new UUID(0, 1), "minecraft:overworld", "h", JobKind.BUILD, null, 25, "claim-job-1",
                MaterialPolicy.CREATIVE_FREE, 0L, List.of()).on(JobEvent.ADMITTED).on(JobEvent.START);
    }

    @Test
    void onlyChangesAreLoggedAndTheLogIsBounded() {
        JobEventLog log = new JobEventLog();
        log.add(5, new JobUpdate(job(), List.of(), List.of(), List.of(), true));
        log.add(6, new JobUpdate(job().withCursor(16), List.of(), List.of(), List.of(), false));
        log.add(7, new JobUpdate(job().paused(PauseReason.MATERIALS_MISSING), List.of(), List.of(), List.of(), true));
        List<String> lines = log.lines("job-1");
        assertEquals(2, lines.size(), "plain progress is not an event");
        assertTrue(lines.get(1).contains("PAUSED") && lines.get(1).contains("MATERIALS_MISSING"), lines.get(1));
        for (int i = 0; i < JobEventLog.MAX_LINES_PER_JOB + 5; i++) {
            log.add(10 + i, new JobUpdate(job(), List.of(), List.of(), List.of(), true));
        }
        assertEquals(JobEventLog.MAX_LINES_PER_JOB, log.lines("job-1").size());
    }
}
```

- [ ] **Step 2〜3: 失敗を確かめ、実装する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.core.JobEventLogTest" --console=plain` → FAIL。`JobEventLog`は上のとおり(`stateChanged`か、`newConflicts`が空でない更新だけを行にする)。ログのファイルは`LevelEvent.Save`で`JobFiles`が書く(本文は`SealedFile`に包まない普通の文字列。調べる人が読めるように。壊れても復旧の判断には使わない)。

- [ ] **Step 4: テストと実機の自動確認**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。

`tools/p4/scenarios_multiplayer.py`の`dedicated-two-clients`(条件6・F-16。`--mode mp2`): `serverP4`+`clientMpP4`(`Dev`、OP。台本が`ops.json`に書く)+`client2P4`(`Dev2`、OPでない)。**空きメモリが`harness.required_free_gib(3)`(3つのJVMの`-Xmx3G`から求めた数。Task 19)より少なければ、`NOT-RUN(resource)`と、必要な数・空きの数・メモリの多いプロセスを証拠に書く。`NOT-RUN`は合格ではなく、代わりの根拠も立てない**(P4レビューA1-4。`permissions-single`は1つのクライアントの論理の確認で、条件6の「クライアント2つ」の代わりにならない)。Task 38は`NOT-RUN`が残る限り失敗する。手順: (1)`Dev`が小屋を建てる→`Dev2`の`/micradrone build list`(`/server/run-as`ではなく**`Dev2`のクライアントのチャット経由**: devkitの2つ目のクライアントAPI(47393)の`/client/chat-log`で見る)に`Dev`のジョブが見える。(2)`Dev2`がクライアントから`/micradrone-client build approve-remote <Devの保留のハッシュ> owner=Dev`→`reject.not_owner`。(3)`Dev2`が`/micradrone build cancel <Devのジョブ>`(サーバーのコマンドをクライアントから打つのは、devkitのクライアントAPIに`/client/send-command {command}`を足して`Minecraft.player.connection.sendCommand`で送る。**キーボードの合成入力ではない**)→`control.not_allowed`。(4)`Dev2`がDevの区画に重なる計画を提出→`E-CLAIM-OVERLAP`。(5)`Dev2`がスポーンから16ブロック以内に小屋を提出→承認→スポーン保護で置けない位置が`denied`→`PARTIAL`(理由`blocked=N`)。(6)`Dev`(OP)は`Dev2`のジョブを取消できる。(7)両方のクライアントの`/client/chat-log`とスクリーンショットを証拠に。

このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件6を消す。

Run: `python -m tools.p4.p4_scenarios --only dedicated-two-clients --mode mp2` → `PASS`。`NOT-RUN(resource)`・`NOT-RUN(eula)`なら、その理由をTask 38の報告に書く(合格として数えない)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add src/test/java/io/github/khayashi4337/micradrone/construction/core/JobEventLogTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ジョブの出来事のログと状態の診断、他人のジョブの表示と、専用サーバー+クライアント2つの自動確認を追加(自然言語→工場建設 P4 Task 34)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 34の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 34)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

(devkitの`/client/send-command`は、devkitのリポジトリに別のコミットとして積む。)

---

### Task 35: ドキュメントの同時更新(F-19)と、設計図の整合、既存の権限の穴のIssue

**担当**: コントローラ(Claude)が設計図・README・CurseForgeの説明・Issueを行う。ゲーム内のヘルプ(`BuildCommands`・`ChildMessages`・言語ファイル)のJavaとJSONはDevinが行い、別のコミットにする(Step 1の前半)。

**Files:**
- Modify: `README.md`(「建設(開発中): `/micradrone build ...`」の節。コマンドの一覧・見本の小屋の建て方・サバイバルでは材料が要ること・補給チェスト)、`docs/curseforge_description.md`(同じ内容の短い版)、`src/main/java/io/github/khayashi4337/micradrone/construction/BuildCommands.java`(`/micradrone build help`: 子供向けの翻訳キーでコマンドの一覧を出す)、`src/main/resources/assets/micradrone/lang/{ja_jp,en_us}.json`(`micradrone.build.help.*`)、`src/main/java/io/github/khayashi4337/micradrone/construction/core/ChildMessages.java`(`help`のキー)、設計図`docs/design/nl_factory_builder/{00_index_and_principles,01_data_model,04_foundations,05_parts_and_analyzers,07_phases_and_verification}.md`
- Test: 既存の`ChildMessagesTest`(新しいキーの存在)

- [ ] **Step 1: ゲーム内のヘルプ(Devin)とREADME・CurseForgeの説明(コントローラ)**

`/micradrone build help`は`micradrone.build.help.submit`・`.approve`・`.status`・`.cancel`・`.resume`・`.verify`・`.rollback`・`.modify`・`.supply`・`.recover`・`.perf`・`.check`の各行を出す(ひらがな中心。`ChildMessagesTest`の漢字の割合の検査を通る)。README・CurseForgeは、この計画書のコマンドの一覧と同じ内容を大人向けに書く(「開発中のデバッグ機能」と明記)。

- [ ] **Step 2: 設計図を、この計画書で確定した内容に合わせて直す**(コードより先に設計図を正本にする原則。末尾の「設計図との食い違い」の全項目)

1. `01` 8節: `PauseReason`の`SITE_CHANGED`、`ConstructionJob.acceptedRiskIds`(Task 3で済み。重複して直さない)、`ApprovalRequest`を`(manifestHash, dimension, playerUuid, acceptedRisks, confirmations)`に(期限は`PendingApproval`が持つ)、`ObservedBlock`(実装は`block`の名前。設計の`spec`を`block`に)、`MaterialLedger`の欄を`consumed`・`returned`・`yielded`・`reclaimed`に(設計の`reserved`は、設置ごとの消費では生じない。返却と整地の受け渡しの冪等な記録が要る)、`CompareScope`に`IndexRange`、`JournalRecord`(`UndoEntry`に置いた物と、台帳のキー)。
2. `01` 7節・11節: `SiteSurvey`に`dimension`を足す(D-27: 調査もディメンションに縛る)。置き場を`build.compile`に(12節の表)。`ReplacePolicy`に`Terraform`。
3. `01` 12節: `build.verify`・`build.analyze`の行(Task 3で済み)に、`SnapshotCollector`・`VolatileProps`・`VoxelGridFiller`を足す。`build.compile`の行に`BlockMatch`・`BlockToItem`・`ItemCount`・`SiteSurvey`・`SiteSurveyBuilder`・`TerrainPrep`・`PhaseRanges`。
4. `04` F-2: ジョブの記録と台帳は「`LevelEvent.Save`で書く」。**保存の順序の事実**(Task 24の節をそのまま写す: 呼ぶ順はプレイヤー(同期)→`SavedData`→チャンク→`LevelEvent.Save`だが、`SavedData`とチャンクはI/Oのスレッドで非同期に書かれ、チャンクは読み込みから外れた時にも書かれる。出どころの行番号つき)。落ちた後は、記録がカーソルより進む(冪等に再開)・遅れる・**世界が記録より進む**のどれも起こりうるので、復旧したジョブは最初に**先行書き込みログからの復旧**(14b)を行い、その後でカーソルより前の記録がそろっていなければ`RECOVERY_NEEDED`。**施工の仕事は`ServerTickEvent.Pre`で行い**(`Post`は`getAverageTickTimeNanos()`の窓の外なので、自動減速も性能の線も施工の重さを見られない)、ランタイム自身の処理の時間を`System.nanoTime()`で測って`MsptStats`に別に記録する。自動減速の戻りの線40ms(`RECOVER_BELOW_MSPT`)。`ServerWorkerPool`の既定(2スレッド・待ち8件)。調査の1tickの列の数(1,024)。`JobService.RETRY_INTERVAL_TICKS`(20)。L7の修復は同じジョブの中の`REPAIRING`で行い、`JobKind.REPAIR`は「建てた後の点検」(`verify`)と復旧(a)のジョブに使う。
5. `04` F-3: 分割の送信の`UPLOAD_START_COOLDOWN_TICKS`(100)・`UPLOAD_IDLE_TIMEOUT_TICKS`(600)。クライアント側のコマンドの根は`/micradrone-client`。
6. `04` F-4: 補給チェストの指定方法(`/micradrone build supply add <pos>`、区画の中のコンテナ)。`permissions.largeJobPlacements`(既定20,000)。承認で他人の保留を名指す`owner=<名前>`。
7. `04` F-5: 撤去の承認画面の代わりに、`rollback <claimId>`の予告と`confirm`。入れ物の中身は、アイテムの能力(`Capabilities.ItemHandler.BLOCK`)で取り出して落とす(Createの保管庫・デポも、Createのクラスをimportせずに同じ道)。液体(`Capabilities.FluidHandler.BLOCK`)と燃えている燃料は戻らないので、予告の文で先に知らせる。OPの緩和は設定の`safety.opMax*`。
7b. `04` F-14(撤去の書き方): 撤去は`Block.UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS`(2|16|32)で1位置ずつ静かに変え、1つの部品(扉の上下)を戻し終えたら隣の更新をまとめて行う(`Level.markAndNotifyBlock`が形の更新のフラグから32を消すので、32だけではアイテムが落ちる)。撤去の順は「付いている物(扉・看板・たいまつ・ランタンなど)が先、扉の上下は続けて、残りは上から下」(`Attachments.REMOVAL_ORDER`)。「戻す先の状態がすでにある」位置は、済んだものとして記録する。設置はバニラの`BlockItem`と同じく、スナップショットを捕まえてから設置のイベントで決め、通ったものだけ`onPlace`と隣の更新を行う(`CommonHooks.onPlaceItemIntoWorld`と同じ形)。
8. `04` F-5の整地の行: 切る範囲は「施工リストの足跡の列で、その列の一番下の置く位置から建物全体の最上段まで(地面の高さまで)」、盛るのは「一番下の置く位置の下から地面まで」。建物ごとの高さで切る規則はP6の`SemanticMap`が入るときに見直す。
8b. `04` F-7: 整地で切った地面の渡し方(`BlockToItem.cutYield`: 草の地面→土、石→丸石 など、素手でない普通の採掘と同じ)。ロールバックで地面を戻すときは、渡した分を取り返す(足りなければ`MATERIALS_MISSING`)。
9. `04` F-13: `ChunkKeeper`(`TicketController`)と設定`continueWhileOffline`。
10. `05` 1.1.1節: **直さない**(P4は設計どおり、`EXACT`は観測と期待の状態を全部比べ、`STATE_SUBSET`は期待に書いた状態だけを比べる。前の版の計画書にあった「P4では同じ」は取り消した。P4レビューA1/G-5)。
11. `05` 4.1節: `E-CLAIM-OVERLAP`・`E-CLAIM-LIMIT`・`E-REPLACE-UNCONFIRMED`・`E-PERMISSION-DENIED`(Task 11・12・26で済み)。
12. `07` P4: 完了条件の確かめ方を「実機の自動確認(devkitと台本)と子供ペルソナの確認」に(オーナーの指示 2026-09-30)。自動確認の状態は`PASS`・`FAIL`・`NOT-RUN`の3つで、**`NOT-RUN`(メモリ不足・EULAが無い など)は合格として数えない**。シングルプレイの確認はEULAに依存しない(devkitの`/client/create-world`で世界を作る)。S-6・S-9の行(Task 1・2で済み)。`07` 4節のテスト戦略の「実機確認」の行を「devkitと台本で自動。見た目の判断は視覚のパスとペルソナ。オーナーだけの項目は理由つきの最小限」に。新しい設計の文書`09_personas_and_playtest.md`(Task 36)を`00`の表に足す。
13. `07` P4の「作る物」の`F-23の一部`: P4の分は「ジョブをIDで引ける状態の問い合わせ」(Task 34)であることを書く。
14. `02` N-29: 読み取りのロジックは`BlockRangeDescription`と共有しない(あちらは名前を短くするので、完全なIDが要る`ServerStateReader`とは共有できない。`BlockRangeDescription`は変更しない)。
14b. `04` F-2・F-7(**クラッシュからの復旧は、設計として控えめ**。Codexの3回目のレビュー2026-09-30): 世界のチャンクは非同期に、読み込みから外れた時にも保存されるので、世界を見ても「どの書き込みが残ったか」は証明できない。だから推測しない: 実行の1回ごとに、意図(書く位置・材料の操作・中身を落とす撤去)を先にディスクへ強制して書き、終わりを書き、持ち物は**その回の番号を取引IDとして一緒に**保存する(個数では判断しない)。**確定点**はフラッシュつきの保存(`saveAllChunks(true, true, false)`。ジョブの終わりと、決まった間隔で。停止の保存も)の後だけ。自動保存は確定点にしない。起動時に、確定点より後の回だけを、世界がちょうど予定のブロックを見せる所は取り込み、失われた所は置き直し(台帳のキーで二重に取らない)、**回の途中で落ちた回と補給チェストを動かした回は持ち主に聞く**(`RECOVERY_NEEDED`、`/micradrone build recover <job> adopt|discard|repair`)。世界から導き直せる物(L7)は導き直す(修復の途中の手順は保存しない)。ジョブは確定点の後にしか終わらない。ジョブの状態はジョブを作った瞬間に`job.bin`に書く(`SavedData`は使わない)。材料の操作で地面に落とさない(入らなければ`NO_ROOM`で待つ)。入れ物の中身を落とす撤去はベストエフォート(予告の文に書く)。**残る限界**: 終わった回の位置に、サーバーが止まっている間にプレイヤーがちょうど同じブロックを置くと見分けられず取り込む(極めてまれ。隠さずに書く)。前の版の「承認の時の調査と比べた引き取り」「自動保存からの推測(`WorldSaved`)」「チャンクの保存の直前の個数の印(`SourceSaved`)」「持ち物の個数での判断」「`repair.bin`」は**やめた**。
14c. `01` 12節の`construction`の行(Task 3で足した物)の`ConstructionJobStore`を、`Checkpoints`・`DevBarrier`に置き換える(ジョブの状態は`job.bin`)。`04` F-2(ファイル): 先行書き込みログはセグメントに分け、確定点で古いセグメントを消す(ログは最後の確定点からの回だけ)。一時ファイルは`FileChannel.force`してからリネーム。`ATOMIC_MOVE`が無いファイルシステムでは2世代(`.prev`)で置き換え、検査値(最後に書かれる)をコミットの印にする。修復の途中の手順も保存する(`repair.bin`)。
14d. `01` 8節(`JournalRecord`): 記録の番号は台帳のキー(修復のラウンドの置き直しは、それぞれ自分の記録と自分の請求)。`REPAIR`のジョブは自分の`Journal`を持ち、施工前の状態は元のジョブの記録から引く。
14e. `01` 8節・D-25(このプロジェクトの物): 「登録簿の記録がある」**かつ**「今のブロックが置いたブロックと同じ種類」。同じIDで状態の違う自分のブロックを置き直すのは修復(`REPAIR`とL7の修復のラウンド)だけ。`MODIFY`は、記録に無い位置と、状態を変えられた自分のブロックを触らず、消えない`Conflict`にする。組み立て済み(`ASSEMBLED_AWAY`)の位置は二度と書かない。
14f. `07` P4・`03`(`VERIFIED`の意味): 触らずに残した`Conflict`があるジョブは`PARTIAL`(ずれが1つも無いときだけ`VERIFIED`)。ロールバックが撤去しきれなければ`PARTIAL`で、区画と設置の記録を残す。
14g. `04` F-7・`01`(材料の個数): 個数はブロックを壊した時に落ちる品物(ルートテーブル)で数える。`create:powered_shaft`を別の`create:shaft`として数える今の扱い(Task 6)は仮で、**P10で「品物を持たないブロック(`IMPLICIT`)は、それを作る`USER`の部品に数える」部品の知識が要る**。
14h. `05`(子供の文): 子供に見せる文に問題のコード(`E-…`・`W-…`)を出さない。コードとIDは、サーバーのログと`/micradrone build status <jobId> --debug`にだけ出す。
14i. D-22(許可リスト): 許可リスト(タグ`micradrone:palette_allowed`と登録された部品の出すブロック)を守らせるのはサーバーのコンパイル。承認前の安全枠(`SafetyEnvelope`)は常に禁止のブロックだけを見る。空のタグは「何も許さない」、タグが無いときだけ組み込みの表。devkitの施工リストの注入は開発専用で、コンパイルを通らない(配布のjarには無い)。
14j. `04` F-5(撤去の予告): 拾える品物の数と、戻らない液体(mB)・燃えている燃料を出し、戻らない物があれば確認を求める(`MODIFY`は`confirmDestructive`、ロールバックは`confirm`)。つながった保管庫の中身は1回だけ数える。
14k. `04` F-13(所有者のいない間): 設定`continueWhileOffline`に加えて、アダプタがそのジョブに「チャンクを保持していて、置く主体(`FakePlayer`)がある」と答えたときだけ進める(`JobWorld.mayRunWithoutOwner`)。
14l. `04` F-8(問い合わせの答え): 30KBを超える答えは分けて送り、クライアントが`requestId`ごとに組み立てる(順不同・重複・形・全体のハッシュ・期限を確かめる)。
14m. `07` P4(性能の判定): 1tickごとの長さ(`ServerTickEvent.Pre`→`Post`)の列で、平均の増加と最も重いtickの増加を判定する(100tickの平均では1回の重いtickが隠れる)。
14n. D-1(構造の検査): 世界を書き換える経路が1本であることは、コンパイルされたクラスの定数プールで確かめる(ソースの文字列ではメソッド参照などを見逃す)。`04` F-22: 計画のファイルは、シンボリックリンク・ジャンクションを通って読まない。
14o. `07` P4(性能の線): 確定点(フラッシュつきの保存)を作るtickは、バニラの自動保存のtickと同じく、最大の増加(15ms)の判定から除いて別に記録する(回数と長さは報告する)。確定点は、ジョブの終わり(最短1分の間隔)と、確定点の後に実行がある間の5分ごと。
15. `00`の変更履歴に「**第8版**(2026-09-30、P4の計画): 上の各項目と、なぜ直したか」を1項目で足す。

Run: `git diff --stat docs/design`で、意図した6ファイル(`00`・`01`・`02`・`04`・`05`・`07`)だけが変わっていること。`./gradlew test --console=plain`(`IssueTest`の表との一致を含む)→`BUILD SUCCESSFUL`。

- [ ] **Step 3: 既存の権限の穴をIssueに起票する(F-4)**

`gh issue list --search "saveScript 権限"`で同じ内容が無いことを確かめてから、`gh issue create --title "既存の畑のsaveScript/Runに権限の判定が無い(誰でも保存・実行できる)" --body "..."`(本文: `04` F-4の実測の記述、`DroneControllerBlockEntity.ownerUuid`が最後の実行者で権限に使われていないこと、建設とは別の課題としてP-15で挙動を変えずに残していること)。作ったIssueの番号を、このタスクのコミットの本文に書く。

- [ ] **Step 4: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add <src/main/java/io/github/khayashi4337/micradrone/construction の下の、このタスクで作った・変えたファイルを1つずつ>
git add <src/main/resources/assets/micradrone/lang の下の、このタスクで作った・変えたファイルを1つずつ>
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
feat: ゲーム内の建設のヘルプ(/micradrone build help)を追加(自然言語→工場建設 P4 Task 35)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add README.md docs/curseforge_description.md docs/design/nl_factory_builder
git commit -m "$(cat <<'MSG'
docs: P4で確定した設計を設計図に反映し、README・CurseForgeの説明を更新(自然言語→工場建設 P4 Task 35)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 36: 子供ペルソナとプレイテストの手順(`09_personas_and_playtest.md`)と、ペルソナに渡す資料の束

**担当: コントローラ(Claude)**。設計図とPythonなので、Devinには渡さない。

オーナーの代わりに、**子供の目で**見える物を確かめる仕組み。人は入らない: 新しいサブエージェントが、子供が見る物**だけ**を渡されてペルソナを演じ、分かったこと・つまずいた所・次にやりそうなこと・楽しかったかを書く。別の視覚のパスが、スクリーンショットを見て「頼んだ小屋に見えるか」を判定する。失敗は、つまずいた所の引用つきで直す課題になる。

**Files:**
- Create: `docs/design/nl_factory_builder/09_personas_and_playtest.md`、`tools/p4/persona_bundle.py`、`tools/p4/tests/test_persona_bundle.py`
- Modify: `docs/design/nl_factory_builder/00_index_and_principles.md`(文書の表に`09`を足す)

- [ ] **Step 1: `09_personas_and_playtest.md`を書く**

中身(全部書く):
1. **ペルソナ3人**(各人: 年齢・読める文字・ゲームの経験・プログラムの経験・好きなこと・すぐ飽きること・つまずきやすい所):
   - **ハナ(小3・8歳)**: ひらがなとカタカナはすらすら、漢字は小2までの物だけ。マイクラは親のそばで週末に。プログラムの経験なし。**何かがすぐ出てくるのが楽しい**(待つのが苦手・30秒で飽きる)。英語のコマンドは、書いてある通りに写すことはできる。長い文は読まない(1行が長いと飛ばす)。
   - **ソウタ(小5・10歳)**: 小4までの漢字。マイクラは毎日、サバイバルが好き。Scratchを学校で少し。材料集めは苦にならないが、**なぜ止まったのか分からないのが嫌い**。コマンドは自分で`/`から打てる。
   - **リン(小6・12歳)**: 小5までの漢字と、英単語を少し。プログラミングが好きで、スクリプトを読む(スクリプトの画面はP5だが、コマンドの一覧・`status`の出力・エラーの文は読む)。**仕組みを知りたい**・自分で直したい。ハッシュという言葉は初めて。
2. **プレイテストの手順**(毎回同じにする):
   - (a)自動確認の台本の実行(`run-evidence/p4/<runId>/`)から、`tools/p4/persona_bundle.py`が**子供に見える物だけ**の束を作る: ゲーム内のチャットの文(`chat.json`。子供の言語=日本語で表示された文字列)、`/micradrone build help`の出力、`ja_jp.json`の`micradrone.build.`の文、READMEの建設の節、スクリーンショット(`*.png`)、シナリオごとの「子供がした操作」(台本が打ったコマンドを、子供が打った物として並べる)。**ソースコード・設計図・ログ・JSONの内部の値・IDの仕組みの説明は入れない**。
   - (b)ペルソナごとに、**新しいサブエージェント**(前の会話を持たない)に、ペルソナの説明と、その束だけを渡す。指示文は固定(この文書に全文を書く): 「あなたは〇〇です。…これはあなたがマイクラで見た物です。各場面で(1)何が起きたと思うか(2)どこで分からなくなったか(分からなかった文を、そのまま引用)(3)次に何をしようと思うか(4)楽しかったか(はい/いいえと理由)を、〇〇の言葉で書いてください。知らないことを知っているふりをしないでください」。
   - (c)**視覚のパス**: 別のサブエージェントに、スクリーンショットと「頼んだ物: 石の基礎・石レンガの壁・木の屋根の、扉と窓のある小さな小屋」だけを渡し、「頼んだ物に見えるか(はい/いいえ)」「ドローンが建てているように見えるか(建てている途中の画面)」「子供が見て、何が起きているか分かるか」を判定させる。
   - (d)**合否**: 場面ごとに、ペルソナの(1)が実際に起きたことと合い、(3)が次に正しい操作(例: 材料不足→チェストに入れる、だれかが変えた→そのままでよい/戻すならロールバック)につながれば**合格**。(2)で引用された文は、全部**直す候補**。3人のうち1人でも(1)を取り違えた場面は**不合格**。視覚のパスで「はい」でなければ不合格。
   - (e)**不合格は直す課題にする**: 引用された文を、その場面・ペルソナ・理由とともに`docs/investigations/p4_persona_<date>.md`に書き、GitHub Issue(`[P4-persona]`)にする。文言の直しは`ja_jp.json`と`ChildMessagesTest`の範囲で直し、同じ束でもう一度プレイテストする(同じ指示文・新しいサブエージェント)。
3. **P4の場面**(子供に見える面は小さいので、全部を対象にする): S1 見本の小屋を建てる(提出→承認→ドローン→完成)、S2 整地の確認を求められる、S3 材料が足りなくて止まる→補給で動く、S4 だれかが変えた所を残して完成、S5 ロールバックで元に戻す、S6 建てた後の点検(壊した壁が直る)、S7 読み込まれていない・いなくなって止まる、S8 ほかの人の区画と重なって断られる、S9 権限が無くて断られる。
4. **記録の形**: ペルソナごと・場面ごとの表(分かったこと・つまずいた文の引用・次の操作・楽しいか・合否)と、視覚のパスの判定。

- [ ] **Step 2: 資料の束を作る道具**

`tools/p4/persona_bundle.py`: `build_bundle(run_dir, out_dir)`が、上の(a)の物だけを`out_dir/bundle.md`と画像に集める。**子供が見た文は、表示されたとおりに、手を加えずに入れる**(Codexのレビュー2026-09-30の決定: 子供の画面の文には問題のコードを付けない(Task 14・16)ので、取り除く物は無い。取り除く処理を置くと、子供の画面に混ざったコードを隠してしまう)。**入れてはいけない物の検査**: 束の文字列に`E-`・`W-`で始まる問題のコード・`jobId`以外の内部のID(`claim-`・ハッシュの64桁)・`.java`・`{`で始まるJSONが入っていないこと(入っていれば例外。子供が見る文にこれらが混ざっていたら、それ自体が直す課題で、Task 14・16のどこかが壊れている)。`09`にこの扱いを書く。`tools/p4/tests/test_persona_bundle.py`: 偽の実行フォルダ(チャットの文と画像1枚)から束ができ、文の中身が1文字も変わらないこと、行の終わりの` (E-SITE-BLOCKED)`・文の途中の`E-SITE-BLOCKED`・64桁のハッシュ・`{"a":1}`の混入では、どれも例外になること。

Run: `python -m unittest discover -s tools/p4/tests -t . -v` → `OK`。

- [ ] **Step 3: コミット**

```bash
git add docs/design/nl_factory_builder/09_personas_and_playtest.md docs/design/nl_factory_builder/00_index_and_principles.md tools/p4/persona_bundle.py tools/p4/tests/test_persona_bundle.py
git commit -m "$(cat <<'MSG'
docs: 子供ペルソナ3人とプレイテストの手順(子供に見える物だけを渡すサブエージェント・視覚のパス・合否と直す課題)を設計に追加(自然言語→工場建設 P4 Task 36)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 37: 回帰(既存の畑ドローン)・書き込みの経路の構造検査・配布用のjar

**担当**: Step 1(`WorldWritePathTest`)とそのコミットはDevin。Step 2〜4(全部のテスト・変えてはいけないファイルの確認・実機・jar)とシナリオのコミットはコントローラ(Claude)。

**Files:**
- Create: `src/test/java/io/github/khayashi4337/micradrone/construction/WorldWritePathTest.java`、`tools/p4/scenarios_regression.py`

- [ ] **Step 1: 書き込みの経路が1本であることの構造検査(D-1)**

**コンパイルされたクラスで**確かめる(Codexのレビュー2026-09-30: ソースの文字列の検索は、`Level::setBlock`のようなメソッド参照、別の名前の変数からの呼び出し、行をまたぐ書き方、コメントや文字列の中の名前で、見逃しも誤検知も起こす)。クラスファイルの定数プールを**JDKだけで**読む(`DataInputStream`。Java 21には`java.lang.classfile`が無い。`build.gradle`は変えない=新しい依存を足さない)。クラスの場所は、テストの実行時のクラスパスにある`PlacementGuard.class`の置き場(`build/classes/java/main`)から取る。`PlacementGuard`が見つかること自体も確かめる(何も読めずに通る、を防ぐ)。コントローラが、偽の`net.minecraft.world.level.Level`と、ラムダの中で`setBlockAndUpdate`を呼ぶクラスを足した作業用のコピーで、このテストが落ちること(と、文字列に`setBlock`と書いただけのクラスでは落ちないこと)を確かめた。

`WorldWritePathTest.java`:
```java
package io.github.khayashi4337.micradrone.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D-1: in the construction code, only PlacementGuard writes blocks. Checked on the compiled classes, not the source
 * text: a method reference ({@code Level::setBlock}), a call through a variable of another type or a name split across
 * lines is still a reference to the write method in the class file's constant pool. The class file parser is JDK only.
 */
class WorldWritePathTest {
    private static final String CONSTRUCTION = "io/github/khayashi4337/micradrone/construction";
    private static final String ONLY_WRITER = "io/github/khayashi4337/micradrone/construction/PlacementGuard";
    private static final String MINECRAFT = "net/minecraft/";
    /** The block-changing methods of Level, LevelWriter, LevelAccessor, ServerLevel and LevelChunk. */
    private static final Set<String> WRITES = Set.of("setBlock", "setBlockAndUpdate", "destroyBlock", "removeBlock",
            "setBlockState");
    private static final int MAGIC = 0xCAFEBABE;
    private static final int UTF8 = 1;
    private static final int CLASS = 7;
    private static final int METHODREF = 10;
    private static final int INTERFACE_METHODREF = 11;
    private static final int NAME_AND_TYPE = 12;

    /** The Minecraft write methods a class file refers to, as {@code owner.name}. */
    static Set<String> writeReferences(InputStream classFile) throws IOException {
        DataInputStream in = new DataInputStream(classFile);
        if (in.readInt() != MAGIC) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        Map<Integer, Integer> classNames = new HashMap<>();
        Map<Integer, int[]> refs = new HashMap<>();
        Map<Integer, Integer> nameOfNameAndType = new HashMap<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case UTF8 -> utf8.put(i, in.readUTF());
                case CLASS -> classNames.put(i, in.readUnsignedShort());
                case METHODREF, INTERFACE_METHODREF -> refs.put(i, new int[]{in.readUnsignedShort(), in.readUnsignedShort()});
                case NAME_AND_TYPE -> {
                    nameOfNameAndType.put(i, in.readUnsignedShort());
                    in.readUnsignedShort();
                }
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4, 9, 17, 18 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        Set<String> out = new TreeSet<>();
        for (int[] r : refs.values()) {
            String owner = utf8.get(classNames.get(r[0]));
            String name = utf8.get(nameOfNameAndType.get(r[1]));
            if (owner.startsWith(MINECRAFT) && WRITES.contains(name)) {
                out.add(owner + "." + name);
            }
        }
        return out;
    }

    @Test
    void onlyThePlacementGuardRefersToAWriteMethod() throws IOException, URISyntaxException {
        Path root = Path.of(PlacementGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> s = Files.walk(root.resolve(CONSTRUCTION))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    if (!writeReferences(in).isEmpty()) {
                        String name = root.relativize(p).toString().replace('\\', '/');
                        // a nested class or lambda holder of the guard is the guard
                        writers.add(name.substring(0, name.length() - ".class".length()).replaceAll("\\$.*", ""));
                    }
                }
            }
        }
        assertEquals(List.of(ONLY_WRITER), List.copyOf(writers), "the guard is found (not a vacuous pass) and is alone");
    }
}
```
Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.construction.WorldWritePathTest" --console=plain` → PASS(`PlacementGuard`だけ。入れ子のクラス・ラムダの持ち主の`PlacementGuard$...`は`PlacementGuard`として数える)。

- [ ] **Step 2: 全部のテストと、変えてはいけないファイル**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。テスト数を記録し、着手時点の`src/test`のテストファイルが全部含まれること(`git diff --name-status main...HEAD -- src/test`で、既存のテストファイルが`D`(削除)になっていないこと)。`BlockSnapshotToolServerTest`(既存の`get_block_snapshot`)が緑であること(条件11)。
Run: `git diff --stat main...HEAD -- src/main/java/io/github/khayashi4337/micradrone/drone/ServerBlockSnapshotReader.java src/main/java/io/github/khayashi4337/micradrone/drone/BlockRangeDescription.java src/main/java/io/github/khayashi4337/micradrone/drone/PacedActionQueue.java src/main/java/io/github/khayashi4337/micradrone/drone/LiveDroneApi.java src/main/java/io/github/khayashi4337/micradrone/lang/DroneApi.java src/main/java/io/github/khayashi4337/micradrone/drone/DroneControllerBlockEntity.java src/main/java/io/github/khayashi4337/micradrone/drone/DroneEntity.java` → **何も出ない**(無変更。P-15・`01` 8節)。

- [ ] **Step 3: 既存の畑ドローンの実機の自動確認(`farm-regression`、条件11)**

`tools/p4/scenarios_regression.py`: `run-command`で`drone_controller`と`corner_marker`を置き(区画から離れた場所。既存の`CornerMarkerScan`の対角の規則どおり)、耕せる土を用意→devkitの既存のエンドポイントで`/open-ide {x,y,z}`→`/set-editor-text {text: <既存のSampleScriptsの耕して植える見本の本文>}`→`/save`→`/run`→`/state`の`debugState`が`idle`に戻るまで待つ→`/build/read-blocks`で区画の土が`farmland`になり作物が植わっていること→`/close-screen`→スクリーンショット。**このシナリオは建設のジョブが動いている間にも1回行い**、畑の`PacedActionQueue`と建設の`JobService`が干渉しないことを確かめる。

このタスクのシナリオを足すコミットで、`PENDING_CONDITIONS`から条件11を消す(これで空になる)。

Run: `python -m tools.p4.p4_scenarios --only farm-regression --mode sp` → `PASS`。

- [ ] **Step 4: 配布用のjar**

Run: `./gradlew.bat jar --console=plain`
Expected: `BUILD SUCCESSFUL`、`build/libs/micradrone-<版>.jar`。大きさとSHA-256を`run-evidence/p4/<runId>/jar.json`に記録する(`certutil -hashfile <jar> SHA256`の出力)。`build/libs`の追跡されているファイルが変わっていれば、コミットの前に`git checkout -- build/libs`で戻す。**林さんの普段のStore版ランチャーの`.minecraft/mods`には入れない**(入れて起動するにはランチャーの画面の操作が要り、OSの合成入力を使わない約束のため。下の「オーナーだけができる項目」)。

- [ ] **Step 5: コミット**

Devin(PowerShellで1行ずつ。`git add`はファイルごとに1回):
```text
git add src/test/java/io/github/khayashi4337/micradrone/construction/WorldWritePathTest.java
git status --short
git commit -F <作業用フォルダ>\commit_msg.txt
```
`commit_msg.txt`の中身:
```text
test: 世界を書き換える経路が1本であることの構造検査と、既存の畑ドローンの実機の自動確認を追加(自然言語→工場建設 P4 Task 37)

Implemented-by: SWE-2 via Devin CLI
```

コントローラ(Claude。Devinのコミットの後):
```bash
git add tools/p4
git commit -m "$(cat <<'MSG'
test: Task 37の実機の自動確認のシナリオ・証拠の手順を追加(自然言語→工場建設 P4 Task 37)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 38: P4の自動確認と子供ペルソナ確認(完了条件ごとの証拠)

**担当: コントローラ(Claude)**。

最後のタスク。**林さんへの最終報告は証拠そのもの**で、クリックのお願いではない。

**Files:**
- Create: `docs/investigations/p4_auto_verification_2026-09-30.md`(日付は実行日。証拠の表)、`docs/investigations/p4_persona_<実行日>.md`(ペルソナの記録)
- Create: `tools/p4/evidence_check.py`(証拠が空でないかの検査)、`tools/p4/tests/test_evidence_check.py`
- Modify: `tools/p4/tests/test_registry.py`(`test_no_pending_conditions_remain`を足す。`PENDING_CONDITIONS`はTask 37で空になっている)

- [ ] **Step 1: すべてを1つのコマンドで走らせる**

`test_registry.py`に足す:
```python
    def test_no_pending_conditions_remain(self):
        self.assertEqual({}, p4_scenarios.PENDING_CONDITIONS, "every completion condition has its scenarios (Task 38)")
```
Run: `python -m unittest discover -s tools/p4/tests -t . -v` → `OK`(**`PENDING_CONDITIONS`が空**で、完了条件1〜16のすべてにシナリオがあること)。
Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`、失敗0件。
**空の証拠で合格にしない**(Codexのレビュー2026-09-30): `tools/p4/evidence_check.py`の`check(run_dir) → list[str]`(問題の一覧。空なら良い)が、`summary.json`の`PASS`のシナリオごとに、決めた証拠のファイルがあり、中身が「何かを確かめた」ことを示すかを見る。規則は`EVIDENCE_RULES`(シナリオ名→(ファイル, 条件)の表)に1か所で書く: 例えば`compare.json`は`compared > 0`かつ`mismatches == 0`、`perf.json`は`activeTicks`が空でなく`idleTicks`も空でない、`timeline.json`は1件以上、`entities.json`は数えた範囲の箱を持つ、`resume.json`は秒の数を持つ、`crash.json`は落としたpidと`wal.bin`の大きさを持つ。規則の無いシナリオが`PASS`なら、それも問題として出す(規則を足し忘れない)。`test_evidence_check.py`: 比べた位置が0件の`compare.json`、空の`activeTicks`、規則の無いシナリオ、ファイルの無いシナリオのそれぞれで問題が出て、正しい証拠では空であること。
```python
    def test_a_pass_with_nothing_compared_is_not_a_pass(self):
        run = self.make_run({"hut-golden": "PASS"}, {"hut-golden/compare.json": {"compared": 0, "mismatches": 0}})
        self.assertIn("hut-golden: compare.json compared 0 positions", evidence_check.check(run))
```
Run: `python -m unittest discover -s tools/p4/tests -t . -v` → `OK`。

Run: `python -m tools.p4.p4_scenarios --all --mode all`、続けて`python -m tools.p4.evidence_check run-evidence/p4/<runId>`
Expected: `summary.json`で全シナリオが`PASS`で、`evidence_check`の問題が0件。起動したプロセスが残っていない(ゲームのフォルダ`run-p4/`は次の実行のために残る。世界は次の実行の始めに消える)。**`FAIL`か`NOT-RUN`が1つでもあるか、`evidence_check`が問題を1つでも出せば、完了を宣言しない**(P4レビューA1-4): `FAIL`は証拠を読み、原因を調べて直し(テストを先に)、同じコマンドでもう一度走らせる。`NOT-RUN(eula)`は林さんのEULAの1行(末尾の「オーナーだけができる項目」)を待つ。`NOT-RUN(resource)`は、証拠のメモリの数値を添えて報告し、空きができてから走らせ直す。どちらの場合も、報告は「未完了(理由)」であり、合格とは書かない。

- [ ] **Step 2: 子供ペルソナのプレイテスト**

`python -m tools.p4.persona_bundle run-evidence/p4/<runId> run-evidence/p4/<runId>/persona`で束を作り、`09_personas_and_playtest.md`の手順どおり、ハナ・ソウタ・リンの3人をそれぞれ新しいサブエージェントで演じさせ(束だけを渡す)、視覚のパスも別のサブエージェントで行う。結果を`docs/investigations/p4_persona_<実行日>.md`に表で書く。不合格の場面は、引用された文を直して(`ja_jp.json`・`ChildMessagesTest`)、Step 1の台本を走らせ直して束を作り直し、プレイテストをやり直す(**合格するまで。同じ直し方を2回試して通らなければ、深く考えるルールに従い、原因を分析してから次の手を打つ**)。

- [ ] **Step 3: 完了条件ごとの証拠の表を作る**

`docs/investigations/p4_auto_verification_<実行日>.md`に、次の表を、**その場で実行した結果**で埋める(証拠が無い行は「未確認」と書き、完了を宣言しない):

| # | 完了条件 | 自動確認(シナリオ) | 証拠(`run-evidence/p4/<runId>/`の下) | ペルソナ・視覚 | 結果 |
|---|---|---|---|---|---|
| 1 | 小屋がドローン演出つきで建つ | `hut-golden`・`hut-here`・`drone-show` | `hut-golden/compare.json`(238件・不一致0)、`drone-show/drones.json`、`drone-show/drone-mid.png` | S1、視覚のパス(小屋に見える・建てている途中に見える) | |
| 2 | 状態まで検査・直る・直らなければPARTIALと理由 | `l7-repair`・`l7-partial` | `l7-repair/compare.json`、`l7-partial/status.json`・`chat.json` | S6 | |
| 3 | MODIFYが差分どおり・置き換えはConflictで触らない | `modify-conflict` | `modify-conflict/compare.json`・`status.json` | S4 | |
| 4 | 再起動で10秒以内に再開・取消・ロールバック・記録の欠落で復旧待ち | `restart-resume`・`cancel`・`rollback`・`missing-journal`・`crash-barriers`・`real-crash`・`offline-chunk` | `restart-resume/resume.json`(秒)、`rollback/compare.json`・`entities.json`(落ちたアイテム0)、`missing-journal/status-*.json`、`crash-barriers/<point>-<kind>/timeline.json`(`SITE_CHANGED`が無い)・`answer.json`(答えを待った組)・`inventory-*.json`、`real-crash/crash.json`・`timeline.json`・`compare.json` | S5・S7 | |
| 5 | 偽のハッシュ・サーバーの作り直し・別ディメンション | `fake-approval`・`hut-golden`(金のハッシュ)・`dimension-bound` | 各`chat.json`・`status.json`、`hut-golden/pending.json` | — | |
| 6 | 専用サーバー+クライアント2つで他人が操作できない | `dedicated-two-clients`(`NOT-RUN`は合格にしない)、`permissions-single`(1つのクライアントでの論理) | 両クライアントの`chat.json`、`permissions-single/outputs.json` | S8・S9 | |
| 7 | 材料(消費・停止・再開・取消で返さない・ロールバックで返す・二重にしない) | `survival-materials`・`rollback-return`・`no-room`・`crash-barriers`(サバイバルの組) | `inventory-*.json`・`chest-*.json`・`no-room/entities.json`(落ちた品物0)・`crash-barriers/*/answer.json` | S3 | |
| 8 | 安全枠の`Issue`(ブロックエンティティを含む) | `safety-limits`・`block-entities` | 各`pending.json` | — | |
| 9 | 30KB超の分割送信とハッシュ検証 | `payload-30kb` | `payload-30kb/upload.json`・`chat.json` | — | |
| 10 | 性能の線・4ジョブ・自動減速 | `perf-20000`・`four-jobs` | `perf.json` | — | |
| 11 | 既存の畑ドローン・`get_block_snapshot` | `farm-regression`、`./gradlew test` | `farm-regression/read.json`、テストの出力 | — | |
| 12 | 整地の個数・確認・資源が消えも増えもしない | `hut-here`・`terrain-slope`・`terrain-survival` | `pending.json`・`inventory-*.json`・`world-count-*.json` | S2 | |
| 13 | 置いた保管庫・デポ(と樽)の中身を落として撤去・元からのチェストは触らない | `block-entities`(Createの`item_vault`2つ(つながった1つの保管庫)・`depot`を注入して撤去) | `block-entities/inject.json`・`vault.json`・`entities.json`(リンゴ17(34ではない)・鉄1・丸石5)・`pending.json`(液体の予告) | — | |
| 14 | 区画の存続と`journal`の保持 | `permissions-single`・`rollback` | `pending.json`(`E-CLAIM-OVERLAP`)、`files-*.json` | S8 | |
| 15 | `volatileProps`を戻さない・別のブロックは上書きしない | `l7-repair` | `l7-repair/compare.json`(扉が開いたまま・金のブロックが残る) | — | |
| 16 | 調査の固定 | `survey-pinned` | `survey-pinned/status.json` | — | |

表の下に、(a)S-6・S-9の測定の結果の要約(調査文書への参照)、(b)ペルソナの結果の要約、(c)**オーナーだけができる項目**(この計画書の末尾の一覧。理由つき)、(d)設計の数値を緩めた物があれば、その理由(無ければ「無し」)を書く。

- [ ] **Step 4: コミット**

```bash
git add docs/investigations tools/p4/p4_scenarios.py
git commit -m "$(cat <<'MSG'
test: P4の完了条件1〜16の自動確認と子供ペルソナ確認の証拠をまとめる(自然言語→工場建設 P4 Task 38)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
MSG
)"
```

林さんへの報告は、この表(証拠のファイル名つき)と、ペルソナの記録と、下の「オーナーだけができる項目」だけにする。「確認してください」とは書かない。

---

## フェーズ末のゲート(タスクではない。全タスクの完了後、実装者とは別のエージェントで、読み取り専用で走らせる)

1. **`pr-review-toolkit:type-design-analyzer`**: `construction.core`・`build.verify`の新しい`record`と`sealed interface`。設計`01`の不変条件(`ConstructionJob`の状態と理由、`Journal`の最初の1件、台帳の冪等、`PlacedRegistry`の`before`)で見る。
2. **`pr-review-toolkit:silent-failure-hunter`**: `JobService`・`ConstructionExecutor`・`WalRecovery`・`FileWalSink`・`JobFiles`・`RecoveryPlanner`・`UploadAssembler`・`PlacementGuard`・`ConstructionRuntime`の、失敗の握りつぶし(`catch`・既定値へのフォールバック・`Optional.empty()`の黙殺)。「推測でOKにしない」「黙って再実行しない」で見る。
3. **`pr-review-toolkit:pr-test-analyzer`**: ローカルの差分(`git diff main...HEAD`)で、テストの穴。特にReview Focusの5つ(施工中の変更・きれいに止まらなかったサーバー・同じtickの重なる承認・所有者が抜ける/移る・材料の数え方と撤去で落ちるアイテム)に、テストがあるか。
4. **`/codex:adversarial-review`**: 先にコミットしてから`--base main --scope branch`。focus文に私の仮説は書かない(バグ探索型のレビューはフラットに頼む)。
5. **Opus 5.5のコードレビュー**(別のエージェント): 設計図(第8版)とこの計画書を渡し、実装との食い違いを探させる。

各ゲートの指摘は、(a)事実を裏取りし、(b)実害があれば直し(テストを先に)、(c)反映しない物は理由を書く。PRの作成・マージ・pushは、林さんの号令があった時だけ。

**範囲の判断の相談は、フェーズの末ではなく、実装に入る前に行う**(P4レビューG-5): コントローラは、Task 3に入る前に、`codex:codex-rescue`で1回、私の見解を先に書いてから相談する。論点は、この計画で「後のフェーズの担当」とした2つ(どちらも設計が後のフェーズに置いた物で、P4で削った物ではない、というのが私の見解): (a)組み立て(`ASSEMBLE`)を含む施工リストを`ASSEMBLY_NOT_AVAILABLE`で断ること(実行はP10・P13。設計`07`のL7の行)、(b)Createのブレイズバーナーの燃料の失われる量の表示(P10の部品。P4は`lit`の状態を持つバニラのかまどの類と、液体の能力を持つ入れ物を表示する)。一致すればそのまま進め、一致しなければ再検討して林さんに報告する。**この改訂で、前の版の3つの範囲の判断(`EXACT`と`STATE_SUBSET`を同じにする・`dedicated-two-clients`を省けること・保管庫とデポを樽で代えること)は取り消した**(林さんの判断 2026-09-30: 何も狭めない)。

## 自己点検(この計画書を書いた側のチェック)

- **完了条件と基盤の網羅**: 設計図07のP4の完了条件16個は、冒頭の「完了条件×タスク」の表で、純Javaのテスト・実機の自動確認・ペルソナの3列に割り当てた。F-1〜F-8・F-13〜F-17・F-19〜F-23とD-1・D-3・D-4・D-6・D-11・D-12・D-14・D-21〜D-27、L7、N-26・N-27・N-29、S-6・S-9も、2つ目の表で空欄なし。D-26はP8の担当(`07` 5節の表のとおり)なので、P4の表では「P8」と書いた。F-15(設定)の担当は設計`07`ではP7で、P4はTask 16で設定の器(`ConstructionConfig`)を作るだけ。
- **「対象外」「後回し」は無い**: 組み立て(`ASSEMBLING`)は状態機械と承認の検査まで作り、実行はP10・P13の担当(設計`07`のL7の行: P10=風車、P13=飛行船)。部品の作用範囲(`EffectSpec`)の検査はD-24のとおりP10・P13。完了条件13の保管庫・デポは、Createのブロックを本当に置いて撤去する(Task 30。開発専用の注入はdevkitの中だけで、配布するjarには無い)。液体と燃えている燃料の損失は、P4で予告の文に出す(Task 30)。ブレイズバーナーの燃料だけはP10の部品。`dedicated-two-clients`は`NOT-RUN`を合格にしない。
- **この改訂で直した物(P4レビュー 2026-09-30、BLOCKER 10・IMPORTANT 26・MINOR 16)**: コンパイルできないテスト2件(B-1・E-2)、`SiteSurvey`の空気の列の整地(E-3)、`BuildPurityTest`の`ALLOWED`(C-1)、施工の仕事を`ServerTickEvent.Pre`にし自分の時間も測る(D-1)、保存の順序の事実と引き取り(D-2・G-1)、撤去で落ちるアイテムと撤去の順(G-2)、設置の捕まえ方(D-3)、チケットのmodのバス(D-4)、自動確認の起動の詰まり(F-1〜F-10)、台本のPythonの形(E-5〜E-7)、`PENDING_CONDITIONS`(B-3)、修復の手順が止まっても残る(B-5)、撤去の`Conflict`だけで置くのを止める(B-4)、その他B-6〜B-16・C-2〜C-4・A1-5〜A1-10。**検証**: Task 3〜13(とTask 24の引き取りの部分)のJavaのコードとテストを全部、リポジトリの外に取り出して`build/classes`とJUnit 5.11.3でコンパイルし、全部のテストを走らせて全部通ることを確かめた(文だけで書かれた型は、文のとおりにスタブを書いた)。
- **2回目のレビュー(Codex 2026-09-30、A: Task 4〜13、B: Task 14〜38)の反映**: 各指摘をコードと出どころ(NeoForgeのソースのjar・Createのjar)で確かめてから直した。直した規則には、規則を外すと落ちるテストを1つずつ付け、作業用のコピーで実際に規則を外して落ちることを確かめた。計画のJavaの塊は、作業用のフォルダで取り出してコンパイルし、実行した(Minecraftに触れる型は、計画の文のとおりの仮の型で置き換えた)。Task 3〜5はコミット済みなので、書き直さず「修正ラウンド」として足した(Task 4)。Task 5〜7の本文は、実装の始まり(09:59)の後は変えていない。
- **3回目のレビュー(Codex 2026-09-30。クラッシュからの復旧と材料の設計にBLOCKER 6つ)の反映**: 控えめな復旧の原則にして、仕組みを**減らした**(承認の時の調査での引き取り・`WorldSaved`・`SourceSaved`・`Persisted`・持ち物の個数での判断・`settlementSteps`・`repair.bin`・`SavedData`のジョブの一覧・ファイルを差し替える`crash-window`)。足した物は、確定点(フラッシュつきの保存)、取引ID、持ち主の答え(`adopt`/`discard`)、`NO_ROOM`、`DropIntent`、境目(`Barrier`)と`crash-barriers`。検証で、コミット済みのTask 5の整地(同じ位置を切ってから基礎を置く)と組み合わさると、ロールバックが切った地面を取り返せない誤りと、古いカーソルからの再歩行が切った手順で止まる誤りが見つかったので、`PlacedEntry.earlierKeys`と`superseded`で直した(新しい`CrashRecoveryTest`が見つけた)。
- **未確認のまま計画に入れた物(実機で測る)**: S-6の実際の上限(Task 1・32)、S-9の保護のイベントの実際の振る舞い(Task 2・26・28・31)、MSPTの線(Task 33)、専用サーバーのスポーン保護(ソースで読めたので、Task 34で結果を見るだけ)、`BlockStateParser`で作れない状態が実際に出るか(Task 19の読み戻し)、調査の走査の重さ(Task 16・33)、`WorldDataConfiguration.DEFAULT`で作った世界でmicradroneのデータパックが有効か(Task 19の`hut-here`)、3つのJVMの実際のメモリ(Task 19・34の証拠)。どれも台本の証拠で確かめる。
- **既存コードの変更**: `ObservedBlock`(欄を足す。1引数のコンストラクタは残す)、`Conflicts`(`BlockMatch`を使う。挙動は同じ)、`BomCalculator`(`BlockToItem`を使う。挙動は同じ)、`PlanCompiler`(`PhaseRanges`を使う。挙動は同じ)、`ReplacePolicy`(`Terraform`を足す)、`IssueCode`(4つ足す)、`Hashing`(バイト列の版を足す)、`MicraDrone`・`MicraDroneClient`(登録を足す)、`build.gradle`(実行設定を足す)、`BuildPurityTest`(`ALLOWED`を`Map.ofEntries`にし、`build.verify`・`build.analyze`の行を足す)。変えないファイルはTask 37 Step 2で機械的に確かめる。

## オーナーだけができる項目(理由つき。最小限)

1. **Minecraft EULAへの同意**(マルチプレイの確認`mp`・`mp2`にだけ要る。シングルプレイの確認は要らない。本当の強制終了の`crash-barriers`・`real-crash`も、台本が起動して落とせる専用サーバーで行うので`mp`): **することは1行**: `tools/p4/eula_ack.txt`というファイルを作り、中身を`eula=true`の1行にする。台本はそれを見つけたら`run-p4/server/eula.txt`に写す。理由: 同意はMinecraftのアカウントの持ち主の意思表示なので、AIが代わりに書かない。1回だけ。これが無い間は、マルチプレイのシナリオ(条件6・10と、条件4・5・9の一部)が`NOT-RUN(eula)`になり、Task 38は完了を宣言しない。
2. **林さんの普段の環境(Store版ランチャー・シェーダー込み)での起動**(任意): ランチャーの画面は隠れて再表示できず、起動にはOSの合成入力が要るので、自動化しない約束(合成入力の禁止)に当たる。P4の確認は、同じjarを`gradlew runClientP4`で起動して自動で行うので、この項目は完了条件の判定には使わない。
3. **子供ペルソナで確かめられない「本物の子供が楽しいか」**: ペルソナのプレイテストは代わりの根拠であり、本物の子供の反応ではない。P4の完了条件には含まれないが、判断の限界として書いておく。
4. (項目ではなく前提)**自動確認の間、画面のロックされていないデスクトップのセッション**が要る(描画とスクリーンショットのため)。空きメモリが足りなければ`mp2`は`NOT-RUN(resource)`になるので、そのときは他のアプリを閉じてから走らせ直す。

## 設計図との食い違い(この計画書で見つけ、Task 35で設計図に反映する物)

(Task 35 Step 2の1〜15(14b〜14nを含む)と同じ内容。ここでは要点だけ)
- **Codexの3回目のレビュー2026-09-30で決めた物(14b・14c・14o)**: クラッシュからの復旧は設計として控えめ(推測しない。決まらない物は持ち主に聞く。世界から導き直せる物は導き直す)。確定点はフラッシュつきの保存だけ。持ち物は取引ID。補給チェストは「ちょうど1回」を約束しない。材料の操作で落とさない。残る限界(止まっている間に同じブロックを置かれると見分けられない)を`04` F-2に書く。確定点のtickは性能の最大の判定から除いて別に報告する。
- **Codexのレビュー2026-09-30で決めた物(Task 35の14b〜14n)**: 先行書き込みログと、それによる復旧・材料の二重の防止(引き取りの廃止)。ファイルの強制と2世代。記録の番号=台帳のキー、`REPAIR`は自分の記録。「このプロジェクトの物」=記録+同じ種類、同じIDの状態の直しは修復だけ、`MODIFY`は記録に無い位置・状態を変えられた位置を触らない。残した`Conflict`があれば`PARTIAL`、撤去しきれないロールバックは区画を残す。材料の個数はルートテーブル(`powered_shaft`→`shaft`は仮で、P10で部品に数える)。子供の文に問題のコードを出さない。許可リストはサーバーのコンパイル(安全枠は常に禁止だけ、注入は開発専用)。撤去の予告は戻らない液体・燃料を出して確認を求め、つながった保管庫は1回だけ数える。所有者のいない間はアダプタの許しも要る。答えの分割の組み立て。性能は1tickごとの列で判定。D-1の検査はクラスファイルで。
- S-6: 設計は「バニラで約32KBが上限」と理解していたが、ソースでは32,767は**登録されていない**ペイロードの読み捨て用で、登録済みのペイロードはNeoForgeの`GenericPacketSplitter`が分割する。文字列は`FriendlyByteBuf.MAX_STRING_LENGTH=32767`文字の上限があるので、本文はバイト列で送る。
- S-9と設置の書き方: プレイヤー主体の`EntityPlaceEvent`は「既に置いた状態」を読む。バニラの`BlockItem`は`captureBlockSnapshots`でスナップショットを捕まえてから設置のイベントで決め、通ったものだけ`onPlace`と隣の更新を行う(`CommonHooks.onPlaceItemIntoWorld`)。P4の設置も同じ形にする(Task 15)。
- **撤去の書き方(新)**: 普通の`setBlock(..., 3)`で撤去すると、隣の形の更新で扉のもう片方や壁の看板がアイテムを落として外れる(`Level.markAndNotifyBlock`は形の更新のフラグから32を消す)。撤去は2|16|32で静かに行い、部品ごとに隣の更新をまとめる。撤去の順は付いている物が先(Task 9・15・28・29)。
- **入れ物の中身(新)**: 中身はアイテムの能力で取り出して落とす(Createの保管庫・デポも同じ道)。液体・燃えている燃料は戻らないので予告で知らせる(Task 15・30)。
- **施工の仕事のtick(新)**: `ServerTickEvent.Post`は`getAverageTickTimeNanos()`の窓の外なので、施工の仕事は`Pre`で行い、自分の処理の時間も測る(Task 16・33)。
- `01` 8節の`ObservedBlock(spec, hasBlockEntity, blockEntityType)`: 実装(P3)は`ObservedBlock(block)`だけだった→欄を足し、名前は`block`。
- `01` 8節の`ApprovalRequest(... expiresTick)`: 期限は`PendingApproval`が持つ。要求には受け入れたリスク(F-3)と確認(F-5)が要る。
- `01` 8節の`MaterialLedger(consumedByPlacementIndex, reserved)`: 設置ごとの消費では予約が生じない。返却・整地の受け渡しの冪等な記録が要る→`consumed`・`returned`・`yielded`・`reclaimed`。
- `01` 8節の`PauseReason`に「位置が置けなくなった」理由が無い(F-3は`PAUSED`と`E-SITE-CHANGED`を求める)→`SITE_CHANGED`(ジョブの`lastError`に`E-SITE-CHANGED`のIDを入れる)。
- `01` 8節の`CompareScope`: 20,000を超える施工リスト(OPの緩和)を`SparseSnapshot`の上限の中で比べるには窓が要る→`IndexRange`。
- `01` 7節の`SiteSurvey`にディメンションが無い(D-27と固定した調査の組み合わせ)→足す。地面の無い(空気の)列は整地しない(`hasGround`)。
- `02` N-29「読み取りのロジックは`BlockRangeDescription`と共有」: あちらは`SenseNames.simplify`で名前を短くし、変更も禁止なので、完全なIDが要る`ServerStateReader`とは共有できない。
- `04` F-2「原子的な書き込み」: いつ書くか・落ちた後に何が起こりうるかが書かれていない。**保存は呼ぶ順(プレイヤー→`SavedData`→チャンク→`LevelEvent.Save`)と、ディスクに届く順が違う**(`SavedData`とチャンクは非同期、チャンクは外れた時にも書かれる)。だから、記録の進み・遅れに加えて「世界が記録より進む」も起こる。**施工はどれも先行書き込みログを先に書き**(Task 9)、復旧はログの意図と世界が一致する位置だけを戻す(前の版の「承認の時の調査と比べた引き取り」は、なりすましを引き取るのでやめた。`survey.bin`も無くなった)。世界の保存の印は1つ前の保存の分、ログはフラッシュつきの保存の後で縮める。
- L7の「`REPAIR`ジョブ」: 同じジョブの中の`REPAIRING`で行い、`JobKind.REPAIR`は建てた後の点検と復旧のジョブに使う(同時ジョブの上限・区画・台帳を二重に扱わないため)。修復の手順は一時停止をまたいで残る。
- `07` P4「F-23の一部」: F-23はプロジェクトとチャットの結び付け(P7)。P4の分は「ジョブをIDで引ける状態の問い合わせ」。
- 数値が設計に無かった物(名前付き定数にして設計に書く): 自動減速の戻りの線40ms、ワーカーの2スレッド・待ち8件、調査の1tickの列1,024、止まったジョブの見直しの間隔20tick、送信の開始の間隔100tick・放置の打ち切り600tick、設置音の1tickの上限4、出来事のログの1ジョブ1,000行、大規模ジョブの閾値(既定20,000)、自動確認のJVMの`-Xmx3G`。
- オーナーの指示(2026-09-30)による確認の方法の変更: 設計`07`の「実機確認=林さんに見てもらう」を、devkitと台本による自動確認と子供ペルソナの確認に置き換える。状態は`PASS`・`FAIL`・`NOT-RUN`で、`NOT-RUN`は合格にしない(Task 35・36)。
