# 01 データモデル

すべての型の定義。0節は共通の約束、1〜9節は型の一覧。表記はJavaの`record`風の擬似コード(フィールド名と意味が設計、細かい構文は実装時に決める)。

## 0. 共通の約束

- **純Javaの核**(`io.github.khayashi4337.micradrone.build.*`)は、`net.minecraft.*`と`net.neoforged.*`を一切importしない(D-16)。座標は`int`3つ、ブロックは文字列の識別子とプロパティ表で持つ。MinecraftのBlockPos・BlockState・Levelとの変換は、サーバー側の薄いアダプタ(`construction`パッケージ)だけが行う。
- **JSON**は、既存の`chat/MiniJson.java`(外部ライブラリなし)で読み書きする。ハッシュ用の**正規形**は、キーを辞書順、数値は整数か固定小数、余計な空白なし、で書いたバイト列。ハッシュは正規形のSHA-256(小文字16進)。
- **ID**は、AIにも人にも見える安定した文字列(例: `wall-north-1`、`press-station-2`)。AIが付け、決定論コードが重複と形式(`[a-z0-9-]{1,48}`)を検査する。問題の指摘(`Issue`)は、必ずこのIDで対象を指す。
- **座標系**(`BuildFrame`): `u`=右、`v`=上、`w`=前。`BuildFrame(origin, facing)`の`facing`が向いている方向が`w`、そこから時計回りに90度が`u`。変換は`world = origin + u*right + v*up + w*forward`。計画はすべて局所座標(u,v,w)で書き、向きに依存しない(P-4)。
- **版**: 型には`schemaVersion`を持たせ、古い保存データは読み込み時に変換する(変換できなければ理由つきで拒否)。

## 1. 基本型

```
enum Facing { NORTH, EAST, SOUTH, WEST }                 // 水平の向き
enum Dir6   { UP, DOWN, NORTH, EAST, SOUTH, WEST }        // ポートの向き
record IntPos(int x, int y, int z)                        // ワールド座標(Minecraft非依存)
record LocalPos(int u, int v, int w)                      // BuildFrameの局所座標
record Box(int minA, int minB, int minC, int maxA, int maxB, int maxC)  // 両端を含む直方体
record Rot(int quarterTurns /*0..3*/, boolean mirror)     // 回転と鏡像
record BuildFrame(IntPos origin, Facing facing) {
    IntPos toWorld(LocalPos p);   LocalPos toLocal(IntPos p);   Facing toWorldFacing(Facing local);
}
record BlockSpec(String blockId, SortedMap<String,String> properties)   // 例: create:shaft {axis=x}
```

## 2. 設計データ(`SemanticPlan`)と差分(`PlanPatch`)

```
record SemanticPlan(
    int schemaVersion, String planId, int revision, Integer parentRevision,
    Site site, StyleSpec style,
    List<PlanNode> nodes, List<Connection> connections,
    Provenance provenance)

record Site(BuildFrame frame, Box localBounds, String terrainDigest /*地形調査のハッシュ*/, String claimId)
record StyleSpec(Map<String,String> palette /*役割→素材。例 "roof"→"minecraft:red_terracotta"*/, Set<String> moodTags)

record PlanNode(
    String id, String type /*PartTypeRegistryの識別子*/, String parent /*入れ子の親。無ければnull*/,
    Anchor anchor, Map<String,ParamValue> params /*PartTypeのパラメータ仕様で検証*/, Set<String> tags, String label)

sealed interface Anchor {
    record Absolute(LocalPos pos, Rot rot)                          // 局所座標で直接
    record OnSurface(String nodeId, Side side, int u, int v)        // 壁・床・屋根の面ローカル座標(窓・扉・装飾)
    record InSlot(String slotId, Rot rot)                           // 建屋内のスロット(モジュール)
}

record Connection(
    String id, PortRef from, PortRef to, ConnKind kind /*ROTATION|ITEM|FLUID|REDSTONE|AIR*/,
    Routing routing /*AUTO(Routerに任せる) | EXPLICIT(経由する部品のIDを列挙)*/, Constraints constraints)
record PortRef(String nodeId, String port)                          // 例 press-1 / "power_in"
```

- `PlanNode`は平らな一覧。親子は`parent`で表す。建物(`structure`)の中に壁・床・屋根・モジュールが入る。
- 接続(`Connection`)は、座標ではなくポートで書く(P-4)。経路の中間部品(シャフト・ベルト・シュートなど)は、`AUTO`ならRouterが作り、**AIは書かない**(P-5)。

**AIが出すのは全体ではなく差分**(トークンの節約、改訂の追跡、ループでの修正のため):

```
record PlanPatch(String patchId, int baseRevision, String stageId, List<PlanOp> ops)
sealed interface PlanOp {
    AddNode(PlanNode)   UpdateParams(String id, Map<String,ParamValue>)   MoveNode(String id, Anchor)
    RemoveNode(String id)   AddConnection(Connection)   RemoveConnection(String id)   SetStyle(StyleSpec)
}
```

- `PlanPatcher.apply(plan, patch)`は決定論。`baseRevision`が現在と違えば拒否(古い前提で書かれた差分を混ぜない)。結果は新しい`revision`の`SemanticPlan`と`Issue`の一覧。
- 独自言語のスクリプトは、この`PlanPatch`の**プログラム表現**(1命令=1操作)。スクリプトを実行(記録)すると`PlanPatch`になり、`PlanScriptWriter`は`SemanticPlan`から等価なスクリプトを出す。往復してもハッシュが変わらないことをテストで保証する(D-2、`04_foundations.md` F-6)。

## 3. 部品(`PartType`)

```
record PartType(
    String id,                  // 例 "create:mechanical_press"、"micra:wall"
    PartCategory category,      // STRUCTURE|OPENING|ROOF|DECOR|POWER|TRANSMISSION|PROCESSING|LOGISTICS|STORAGE|FLUID|AERO|MODULE
    String displayNameKey,      // 翻訳キー(JEIと同じ表示名。D-18)
    String visualDescription,   // 見た目の短い説明(画像生成の指示文用)
    List<ParamSpec> params,     // 名前・型(int/enum/bool/material等)・範囲・既定値
    List<PortSpec> ports,       // 下記
    Footprint footprint,        // 占める体積(パラメータの関数)
    VersionRange requires,      // 例 create [6.0.10,6.1.0)。範囲外なら無効(D-13)
    PlacerId placer,            // どう置くか(SIMPLE / BELT / ARM / MULTIBLOCK …)
    VerifyPolicy verify,        // EXACT / STATE_SUBSET(listed) / BLOCK_ONLY
    ModelRef kineticModel,      // 工場アナライザ用の挙動モデル(無ければ null → W-UNMODELED)
    BuildPhase phase)           // 施工順のグループ

record PortSpec(String name, PortKind kind /*ROTATION_IN|ROTATION_OUT|ITEM_IN|ITEM_OUT|FLUID_IN|FLUID_OUT|REDSTONE|HEAT*/,
                LocalPos offset, Dir6 facing, Set<String> accepts /*相手が満たすべき条件*/)
```

- 部品登録簿(`PartTypeRegistry`)は、`PartType`の集合と**版ハッシュ**(登録内容から計算)を持つ。施工リスト・ジョブ・承認は、この版ハッシュを記録し、版が違うものは承認・実行を拒否する。
- 登録簿から、次の3つを**自動生成**する(P-16): (a) AIの出力スキーマ(部品の選択肢はenum)、(b) 画像生成の「使ってよい部品リスト」の文、(c) 部品見本帳(`PartAtlas`)。

## 4. 施工リスト(`PlacementManifest`)

```
record PlacementManifest(
    int manifestVersion, String planId, int planRevision, String registryVersion,
    BuildFrame frame, Box worldBounds, List<Placement> placements /*施工順に整列済み*/,
    Map<String,Integer> bom /*材料表: 品物ID→個数*/, List<PhaseRange> phases, String hash)

record Placement(
    int index, IntPos pos, BlockSpec block, Map<String,String> blockEntityConfig /*許可済みキーのみ*/,
    String partNodeId /*どの設計ノード由来か。エラーの逆引き用*/, BuildPhase phase,
    PlacerId placer, VerifyMode verify, ReplacePolicy replaces /*AIR_ONLY|REPLACEABLE|EXPECT(blockId)*/)

enum BuildPhase { SITE_PREP, STRUCTURE, ENVELOPE, POWER, UPSTREAM, DOWNSTREAM, LOGISTICS, DECORATION, FINISH }
```

- `PlanCompiler.compile(expandedPlan, registry, siteSurvey) → CompileResult(manifest | issues)`は**決定論**: 同じ入力から、バイト単位で同じ施工リスト(=同じハッシュ)ができる。
- 施工順は`BuildPhase`の昇順、その中は下から上・手前から奥(v3の「動力 → 上流 → 下流」を`POWER→UPSTREAM→DOWNSTREAM`で表す)。
- `hash`は`placements`・`bom`・`registryVersion`・`worldBounds`から作る。**承認の対象はこのハッシュ**(D-3)。

```
record ManifestDiff(String fromHash, String toHash,
    List<Placement> removed, List<Placement> added, List<PlacementChange> changed, int unchanged)
```

- 建てた後の変更(L4'・L5・L6・L8、D-14)は、旧施工リストと新施工リストの差分から`MODIFY`ジョブを作る。撤去は上から下、追加は`BuildPhase`順。

## 5. 問題(`Issue`)— ループの共通語

```
record Issue(String id /*例 "E-STRESS-OVER:net-3"*/, IssueCode code, Severity severity /*ERROR|WARN|INFO*/,
             List<String> subjects /*設計ノード/接続のID*/, String message /*日本語*/,
             Map<String,String> data /*数値など*/, List<FixHint> hints)
record FixHint(String kind, Map<String,String> args)   // 例 ADD_POWER_SOURCE{need_su=512}、WIDEN_ROOM{room=r1,by=3}
```

`IssueCode`(全ループで共通、追加は登録簿に載せる。網羅一覧は`05_parts_and_analyzers.md`):
`E-SCHEMA`、`E-UNKNOWN-PART`、`E-PARAM-RANGE`、`E-ANCHOR`、`E-OVERLAP`、`E-OUT-OF-BOUNDS`、`E-NOT-SUPPORTED`(支持なし)、`E-OPENING-NO-WALL`、`E-ENCLOSURE-LEAK`、`E-PORT-UNCONNECTED`、`E-PORT-MISMATCH`、`E-NO-ROUTE`、`E-ROT-CONFLICT`、`E-STRESS-OVER`、`E-POWER-NONE`、`E-ITEM-DEADEND`、`E-CLOG-RISK`、`E-FLUID-LEAK`、`E-SPACE-SHORT`、`E-REGISTRY-VERSION`、`E-SITE-BLOCKED`、`E-MATERIAL-SHORT`、`W-UNMODELED`、`W-STRESS-MARGIN`、`W-OVERSIZED-POWER`、`W-NO-RECIPE`、`W-DECOR-COLLIDE`、`I-*`(情報)。

## 6. 工程・生産(工場層)

```
record RecipeOption(String recipeId, String recipeType /*create:pressing など*/, List<Ingredient> inputs,
                    List<Result> outputs, List<String> machinePartTypes, Integer processingTicks,
                    Heat heat /*NONE|HEATED|SUPERHEATED*/, String sourceHash)
record Ingredient(Set<String> itemIds /*タグは展開済み*/, String tagOrNull, int count)
record Result(String itemId, int count, double chance)

record ProcessGraph(List<ProcessStep> steps, List<Flow> flows, List<ExternalInput> inputs, List<ProductTarget> products)
record ProcessStep(String id, RecipeOption recipe, double targetPerMin, String machinePartType)
record Flow(String fromStep, String toStep, String itemId, double perMin)
record ExternalInput(String itemId, double perMin, String viaDock /*搬入口*/)
record ProductTarget(String itemId, double perMin, String viaDock /*搬出口*/)

record CapacityReport(List<StepCapacity> steps, PowerNetworkPlan power, Map<String,Double> floorAreaByZone, List<Issue> issues)
record StepCapacity(String stepId, int machineCount, double rpmRequired, double stressImpactSu, int footprintCells)
record PowerNetworkPlan(double totalImpactSu, List<PowerChoice> sources /*部品ID×台数*/, double capacitySu, double marginRatio)
```

- 数値(応力の影響・容量)は、実行時にCreateの`BlockStressValues`から読む(D-9)。テストでは、同じ形の`StressValueSource`を差し替える。

## 7. 建屋の意味解析・敷地

```
record ZoningPlan(List<Footprint> footprints, List<Corridor> corridors)
record Footprint(String id, Box baseLocal, int height, Rot rot, int minGapBlocks)
record SiteSurvey(Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water, boolean[][] tree, String digest)

record SemanticMap(List<Level> levels, List<WallSegment> walls, List<RoofPlane> roofs, List<Opening> openings,
                   List<Room> rooms, List<Slot> slots, List<Entrance> entrances, List<Issue> issues)
record Room(String id, int levelIndex, Box bbox, int volumeCells, boolean enclosed)
record Slot(String id, String roomId, Box box, Set<Facing> allowedFacings, int clearanceAbove,
            int clearanceSides, boolean nearPower, boolean nearWindow, boolean nearEntrance)
```

- `SemanticMap`は、**設計データから**(予測)も、**実際のスナップショットから**(観測)も、同じアルゴリズムで作る(`VoxelGrid`経由)。予測と観測を比べることで、厳密な差分では気づけない意味の違い(壁に穴、部屋がつながっていない)を検出できる。

## 8. 施工ジョブ・検証(サーバー)

```
record ConstructionJob(
    String jobId, UUID ownerUuid, String dimension, String manifestHash, JobKind kind /*BUILD|MODIFY|REPAIR|ROLLBACK*/,
    JobState state, PauseReason pauseReason, int cursor, int total, int repairRound,
    String claimId, MaterialPolicy materialPolicy, List<UndoEntry> journal, String lastError, long createdTick)
enum JobState { PENDING_APPROVAL, QUEUED, RUNNING, PAUSED, VERIFYING, REPAIRING, VERIFIED, PARTIAL, FAILED, CANCELLED, ROLLED_BACK }
enum PauseReason { OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, USER }
record UndoEntry(IntPos pos, BlockSpec before, String beDataDigest)

record Snapshot(Box worldBounds, Map<IntPos,ObservedBlock> blocks)
record ObservedBlock(BlockSpec spec, boolean hasBlockEntity, String blockEntityType)
record Deviation(int placementIndex, BlockSpec expected, ObservedBlock observed, DeviationKind kind /*MISSING|WRONG_BLOCK|WRONG_STATE|EXTRA|BLOCKED*/)
record SiteClaim(String claimId, UUID ownerUuid, String dimension, Box worldBox, String jobId, long createdTick)
record ApprovalRequest(String manifestHash, UUID playerUuid, long expiresTick)
```

- `Snapshot`は、既存の`drone/BlockRangeDescription.java`(IDのみ)を、**ブロックの状態(向き・軸・段など)まで**読む形に拡張したもの。既存の`drone/ServerBlockSnapshotReader.java`(Phase 2で追加済み、まだどこからも呼ばれていない)の出力を拡張して使う。
- `SnapshotDiff.compare(manifest, snapshot)`は決定論。`VerifyMode`に従い、設置後に`Deviation`の一覧を返す。

## 9. AI進行・画像・知見(クライアント)

```
record BuildProject(String projectId, UUID ownerUuid, String worldId, ProjectState state,
                    ConceptBrief brief, List<ImageArtifact> images, List<PlanRevisionRef> planRevisions,
                    List<ManifestRef> manifests, List<String> jobIds, List<CritiqueRecord> critiques,
                    Map<String,LoopCounter> loops, CostLedger ledger, String journalPath)

record ConceptBrief(List<ProductTarget> products, String powerPreference, StyleSpec style, int buildingCountHint,
                    boolean needsDock, Box siteHint, List<String> openQuestions)

record ImageArtifact(String imageId, ImageKind kind, String path, String sha256, int width, int height,
                     String prompt, String promptHash, List<String> referenceImageIds,
                     String generator /*codex-cli 0.144.1 等*/, String cameraPresetId,
                     ApprovalStatus approval, Double costEstimateUsd, long createdAtMillis)
enum ImageKind { CONCEPT_ART, BUILDING_RENDER, STRUCTURE_GUIDE, INTERIOR_SECTION, PART_ATLAS, SCREENSHOT, REVISION }

record PartAtlas(String atlasId, String registryVersion, String imageId, List<AtlasEntry> entries)
record AtlasEntry(String partTypeId, String displayName, int x, int y, int w, int h)

record StructureDescription(String imageId, List<DescribedBuilding> buildings, List<DescribedObject> objects,
                            List<DescribedFlow> flows, List<Unrecognized> unrecognized, double confidence)
record DescribedObject(String partType /*登録簿の識別子のenum(D-19)*/, String buildingId, NormBox region, int count, String note)
record DescribedFlow(int fromObject, int toObject, ConnKind kind, boolean forward)
record Unrecognized(String description, NormBox region)    // 登録簿に無い物

record CameraPreset(String id, double yawDeg, double pitchDeg, double distanceFactor, double fovDeg, String targetRule)
record CritiqueReport(double matchScore, List<Diff> diffs, String summary)
record Diff(String area, DiffKind kind /*SHAPE|ROOF|LAYOUT|DECOR|COLOR|IMPOSSIBLE*/, Severity severity, String description, List<String> targetNodeIds)
record RouteDecision(CritiqueCategory category /*MOOD|PRODUCT|BUILDING_FORM|LINE_LAYOUT|OTHER*/, String targetStage, Map<String,String> constraints)

record LoopBudget(String loopId, int maxIterations, Double threshold, Double plateauDelta, OnExceed onExceed /*ESCALATE*/)
record LoopCounter(String loopId, int used, List<Double> scores, boolean escalated)
record CostLedger(List<CostEntry> entries, double softCapUsd, double hardCapUsd)
record CostEntry(String stageId, Provider provider /*CLAUDE|CODEX*/, Double usd, Integer inputTokens, Integer outputTokens, long millis)

record KnowledgeRecord(String id, KnowledgeKind kind /*SUCCESS_MODULE|FIX_HISTORY|CRITIQUE|PREFERENCE|FAILURE*/,
                       Set<String> tags, String payloadJson, String provenance, PromotionState promotion, long createdAtMillis)
record ModuleTemplate(String id, String displayNameKey, PartCategory category, VersionRange requires, Box footprint,
                      List<PortSpec> ports, List<PlanNode> nodes, List<Connection> internal,
                      TemplateStats stats /*RPM・応力・毎分の生産量*/, Verification verification /*解析版・試運転の結果ハッシュ*/, Set<String> tags)
```

## 10. パッケージ配置(案)

| パッケージ | 中身 | Minecraft依存 |
|---|---|---|
| `build.model` | 1・2・4・5節の型、`PlanPatcher` | なし |
| `build.parts` | `PartType`、`PartTypeRegistry`、パラメータ検証、スキーマ生成 | なし |
| `build.compile` | `PlanCompiler`、`ManifestDiff`、ハッシュ | なし |
| `build.analyze` | `VoxelGrid`、`BlueprintAnalyzer`、`ZoningFixer`、`CapacityCalculator`、`Router`、`KineticModel`、`FactoryAnalyzer`、`RecipeSource`(IF) | なし |
| `build.process` | `ProcessGraph`、`Reconciler`の決定論部分 | なし |
| `build.verify` | `Snapshot`、`SnapshotDiff`、`RepairPlanner` | なし |
| `build.script` | `PlanRecorder`、`PlanScriptWriter` | なし |
| `build.knowledge` | `ModuleLibrary`、`KnowledgeStore`の型 | なし |
| `build.ai` | `BuildOrchestrator`、`StageSpec`、`LoopBudget`、各段のプロンプトと検証 | なし(CLI呼び出しは`chat`側の橋渡し) |
| `construction` | `ConstructionJob`、`ConstructionExecutor`、`PlacementApplier`、`SafetyEnvelope`、`MaterialPolicy`、`SiteClaimStore`、`DroneShow`、`Commissioning`、`RuntimeMonitor`、サーバー側の調査と`RecipeSource`実装 | **あり(サーバー)** |
| `construction.net` | ペイロード群 | あり |
| `client.build` | レビュー画面、ホログラム、`PartAtlasRenderer`、`InGameRenderer`、画像表示 | **あり(クライアント)** |
| `chat`(既存) | `ClaudeCliBridge`に構造化出力・画像入力・予算指定を追加、`CodexCliBridge`を新設 | なし |
