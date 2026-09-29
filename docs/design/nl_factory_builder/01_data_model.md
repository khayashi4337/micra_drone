# 01 データモデル

すべての型の定義。0節は共通の約束、1〜10節は型の一覧、11節は補助型、12節はパッケージ配置。表記はJavaの`record`風の擬似コード(フィールド名と意味が設計、細かい構文は実装時に決める)。**この文書に出てくる型は、すべてこの文書のどこかで定義する**(未定義の型を残さない)。

## 0. 共通の約束

- **純Javaの核**(`io.github.khayashi4337.micradrone.build.*`)は、`net.minecraft.*`と`net.neoforged.*`を一切importしない(D-16)。座標は`int`3つ、ブロックは文字列の識別子とプロパティ表で持つ。MinecraftのBlockPos・BlockState・Levelとの変換は、サーバー側の薄いアダプタ(`construction`パッケージ)だけが行う。
- **JSON**は、既存の`chat/MiniJson.java`(外部ライブラリなし)で読み書きする。ただし`MiniJson`は現状`chat`パッケージ限定の可視性で、`build.*`から使えないので、**公開(`public`)にする**(P3。挙動は変えない)。`MiniJson`は数をすべて`Double`で読むので、整数か小数かの区別は、`build.model`の`PlanJson`が部品のパラメータ仕様(`ParamSpec`)に照らして行う。ハッシュ用の**正規形**(`CanonicalJson`)は、キーを辞書順、整数はそのまま、小数は`BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()`(`NaN`・無限大は拒否)、余計な空白なし、で書いたUTF-8のバイト列。ハッシュは正規形のSHA-256(小文字16進)。**`SemanticPlan.contentHash`の正規形では、`nodes`と`connections`を`id`の辞書順に、`Set`を辞書順に並べる**(並びが違うだけの同じ設計を、同じハッシュにするため。スクリプトの往復で、出力の並びが変わっても一致する)。物流(`LogisticsPlan`)も同じ約束で、`docks`と`routes`は`id`の辞書順(`id`が無い物は先頭)、`flows`は(`itemId`,`fromDock`,`toDock`,`perMin`)の辞書順、`Constraints.allowedEntryDirs`のような`Set`はワイヤ名の辞書順に並べる。`Dock`・`Route`の中のリスト(経由点・結ぶポート・係留部品のID)は順序が意味を持つので、与えられた順のままにする。
- **ID**は、AIにも人にも見える安定した文字列(例: `wall-north-1`、`press-station-2`)。AIが付け、決定論コードが重複と形式(`[a-z0-9-]{1,48}`)を検査する。問題の指摘(`Issue`)は、必ずこのIDで対象を指す。**画像から読み取る構造記述(`StructureDescription`)の建屋・物体・流れも、安定IDで指す**(一覧の添字では指さない)。
- **座標系**(`BuildFrame`): `u`=右、`v`=上、`w`=前。`BuildFrame(origin, facing)`の`facing`が向いている方向が`w`、そこから時計回りに90度が`u`。変換は`world = origin + u*right + v*up + w*forward`。計画はすべて局所座標(u,v,w)で書き、向きに依存しない(P-4)。
- **版**: **保存される型**(`SemanticPlan`、`PlacementManifest`、`ConstructionJob`、`MaterialLedger`、`PlacedRegistry`、`SiteClaim`、`CommissioningJournal`、`BuildProject`、`ImageArtifact`、`KnowledgeRecord`、`ModuleTemplate`)は、先頭の欄に`int schemaVersion`を持つ。保存は`PersistenceEnvelope(type, schemaVersion, payload)`で包み、読み込み時に、版の移行表(`Migrations`: 版→変換関数)で現在の版へ変換する。**新しい版のデータを古いコードが読んだ場合や、変換できない場合は、読み込みを拒否して理由を示す**(壊さない・黙って捨てない)。
- **大きなデータの置き場**: 施工リスト本体・ジョブの記録(`journal`)・材料台帳は、`SavedData`ではなく別ファイルに保存する(04 F-2)。

## 1. 基本型

```
enum Facing { NORTH, EAST, SOUTH, WEST }                 // 水平の向き
enum Dir6   { UP, DOWN, NORTH, EAST, SOUTH, WEST }        // ポートの向き
record IntPos(int x, int y, int z)                        // ワールド座標(Minecraft非依存)
record LocalPos(int u, int v, int w)                      // BuildFrameの局所座標
record Box(int minA, int minB, int minC, int maxA, int maxB, int maxC)  // 両端を含む直方体(局所・ワールド両用)
record Rot(int quarterTurns /*0..3*/, boolean mirror)     // 回転と鏡像
record BuildFrame(IntPos origin, Facing facing) {
    IntPos toWorld(LocalPos p);   LocalPos toLocal(IntPos p);   Facing toWorldFacing(Facing local);
}
record BlockSpec(String blockId, SortedMap<String,String> properties)   // 例: create:shaft {axis=x}
```

- **局所の向き(P3で確定)**: 局所座標の`Facing`は、`NORTH`=+w(前)、`EAST`=+u(右)、`SOUTH`=-w、`WEST`=-u。`Rot(quarterTurns, mirror)`は、まず鏡像(u→-u。`EAST`と`WEST`が入れ替わる)、次に時計回り(上から見て)に`quarterTurns`回90度回す。局所の1回転は`(u,w) → (w,-u)`(`NORTH`→`EAST`→`SOUTH`→`WEST`)。
- **`BuildFrame`の変換(P3で確定)**: `facing`の番号`q`は、`NORTH`=0、`EAST`=1、`SOUTH`=2、`WEST`=3。`facing=NORTH`のとき`world = origin + (u, v, -w)`(マイクラの北は-z)。それ以外は、水平の成分`(dx, dz) = (u, -w)`を、時計回りに`q`回(1回は`(dx,dz) → (-dz, dx)`)回してから足す。局所の向きは、`Facing.rotate(q)`で世界の向きになる。**回転不変は、この変換で作る(局所でコンパイルしてから世界へ写す)ので、構造的に成り立つ**。写すときは、ブロック状態の`facing`・`axis`(`x`と`z`は奇数回で入れ替え)・`north`/`east`/`south`/`west`の各キー・`rotation`(1回で+4)も同じだけ回す。

## 2. 設計データ(`SemanticPlan`)と差分(`PlanPatch`)

```
record SemanticPlan(
    int schemaVersion, String planId, int revision, Integer parentRevision,
    Site site, StyleSpec style,
    List<PlanNode> nodes, List<Connection> connections,
    LogisticsPlan logistics /*発着場・飛行ルート。無ければnull。計画の一部としてサーバーへ送られ、承認のハッシュに含まれる*/,
    Provenance provenance)
// contentHash = 正規JSONのSHA-256(planId・revision・parentRevision・provenanceを除いた全項目)。スクリプトの往復で同一になるべき量はこれ

record Site(String dimension /*例 minecraft:overworld。ハッシュに含む(D-27)*/, BuildFrame frame, Box localBounds,
            String terrainDigest /*地形調査のハッシュ*/, String claimId)
record StyleSpec(Map<String,String> palette /*役割→素材。例 "roof"→"minecraft:red_terracotta"。許可された素材だけ(D-22)*/, Set<String> moodTags)

record PlanNode(
    String id, String type /*PartTypeRegistryの識別子*/, String parent /*入れ子の親。無ければnull*/,
    Anchor anchor, Map<String,ParamValue> params /*PartTypeのパラメータ仕様で検証*/, Set<String> tags, String label)

sealed interface Anchor {
    record Absolute(LocalPos pos, Rot rot)                          // 局所座標で直接
    record OnSurface(String nodeId, Side side, int u, int v)        // 壁・床・屋根の面ローカル座標(窓・扉・装飾)
    record InSlot(String slotId, Rot rot)                           // 建屋内のスロット(モジュール)
}

record Connection(
    String id, PortRef from, PortRef to, ConnKind kind /*ROTATION|ITEM|FLUID|REDSTONE|HEAT|DOCK*/,
    Routing routing /*Auto(Routerに任せる) | Explicit(経由する部品のIDを列挙)*/, Constraints constraints)
record PortRef(String nodeId, String port)                          // 例 press-1 / "power_in"
```

- `PlanNode`は平らな一覧。親子は`parent`で表す。建物(`structure`)の中に壁・床・屋根・モジュールが入る。
- 接続(`Connection`)は、座標ではなくポートで書く(P-4)。経路の中間部品(シャフト・ベルト・シュートなど)は、`Auto`ならRouterが作り、**AIは書かない**(P-5)。
- **`Anchor`の意味(P3で確定)**: `Absolute(pos, rot)`は、**親があれば親の原点からの相対**、無ければ計画の局所座標(`Site.frame`の原点)からの絶対。`OnSurface(nodeId, side, u, v)`は、対象の壁の面上の位置で、`u`は壁の始点から、壁が伸びる向き(北・南の壁は+u、東・西の壁は+w)に数え、`v`は壁の最下段から上へ数える。`side`は`OUTER`(外側から付く)か`INNER`(内側から付く)だけを使い、他の値は`E-ANCHOR`。`InSlot(slotId, rot)`は、スロットの解決(建屋の意味解析、P6)が要るので、P3の`SlotResolver`は`E-ANCHOR`(「スロットの解決はP6で載る」)を返す(黙って無視しない)。
- **パラメータの型付け(P3で確定)**: 計画のJSON・スクリプトから入ってくるパラメータは、まず型を推測して読む(整数の値は`IntV`、小数は`NumV`、文字列は`StrV`、真偽は`BoolV`、配列は`ListV`)。`PlanPatcher`が、部品の`ParamSpec`に照らして型を確定する(`ENUM`の文字列は`EnumV`、`MATERIAL`は`MaterialV`、`NUM`に来た整数は`NumV`)。確定後の型で保存・ハッシュを取るので、同じ設計は、どの経路(JSON・スクリプト)から入っても同じハッシュになる。

**AIが出すのは全体ではなく差分**(トークンの節約、改訂の追跡、ループでの修正のため):

```
record PlanPatch(String patchId, int baseRevision, String stageId, List<PlanOp> ops)
sealed interface PlanOp {
    AddNode(PlanNode)   UpdateParams(String id, Map<String,ParamValue>)   MoveNode(String id, Anchor)
    RemoveNode(String id)   AddConnection(Connection)   RemoveConnection(String id)   SetStyle(StyleSpec)
    SetSite(Site)   SetLogistics(LogisticsPlan)
}
```

- `PlanPatcher(registry, templates)`の`apply(plan, patch) → PatchResult(SemanticPlan plan, List<Issue> issues)`は決定論。`baseRevision`が現在の`revision`と違えば拒否(`E-PATCH-STALE`。古い前提で書かれた差分を混ぜない)。**`ERROR`が1つでもあれば、パッチ全体を拒否して`plan`は`null`**(半端に適用しない。元の計画は変わらない)。成功時は、`revision`=`baseRevision+1`、`parentRevision`=`baseRevision`。`RemoveNode`は、子または接続が残るノードには使えない(`E-ANCHOR`。残っている依存先を`FixHint`で示す)。IDは`[a-z0-9-]{1,48}`(違えば`E-ID-INVALID`)で、重複は`E-ID-DUPLICATE`。`nodes`の並びは追加順で、親は子より先に来る(親が無いノードは追加できない)。存在しない親を指すノードは`E-ANCHOR`(対象はそのノードのIDに`#parent`を付けた物)。**親自身が別の理由で拒否されている場合は、その親の`Issue`が答えなので、子には改めて`E-ANCHOR`を出さない**(同じ原因を重ねて報告しない)。
- 独自言語のスクリプトは、この`PlanPatch`の**プログラム表現**(1命令=1操作)。スクリプトを実行(記録)すると`PlanPatch`になり、`PlanScriptWriter`は`SemanticPlan`から等価なスクリプトを出す。往復しても`contentHash`が変わらないことをテストで保証する(D-2、`04_foundations.md` F-6)。`planId`・`revision`・`provenance`はスクリプトの外(メタ情報)。

### 2.1 展開済みの計画(`ExpandedPlan`)— サーバーが自分で作り直す物

`SemanticPlan`のモジュール(テンプレート)と`Auto`の接続は、次の決定論の関数で、部品だけの計画に展開する:

```
PlanExpander.expand(SemanticPlan plan, TemplateBundle templates, Router router) → ExpandedPlan
record TemplateBundle(List<ModuleTemplate> templates)   // ModuleTemplate・TemplateBundleの型は`build.plan`に置く(P3で作る。PlanExpanderが使う)   // 参照されるテンプレート。同梱の物はIDとハッシュで照合、プレイヤーが昇格した物は本体を含める
record ExpandedPlan(SemanticPlan source, List<PlanNode> primitiveNodes /*テンプレート展開後の部品*/,
                    List<RoutedConnection> routed, List<String> templateHashes)
record RoutedConnection(String connectionId, List<PlanNode> intermediateNodes, List<LocalPos> path)
```

- **サーバーは、クライアントの展開結果を信用しない**。受け取るのは`SemanticPlan`・`ProcessGraph`・`TemplateBundle`だけで、展開・コンパイル・解析は、サーバーが自分の手持ちのコードで作り直す(04 F-3)。同梱テンプレートは、IDとハッシュがサーバーの手持ちと一致しなければ拒否。
- **展開の規則(P3で確定)**: テンプレートのインスタンス(`type`が`mod:`で始まるノード)は、テンプレートの`nodes`・`internal`を、ID`<インスタンスID>/<元のID>`で展開する(`/`は利用者が書くIDの正規表現に含まれないので、衝突しない)。子の`Absolute`の位置と回転は、インスタンスの`anchor`(位置と`Rot`)を合成する。子の`OnSurface`・`parent`は、同じ接頭辞に付け替える。`Explicit`の接続は、経由するノードの存在を確かめ、経路をその位置の並びで記録する(端点や経由ノードが無ければ`E-CONN-INVALID`)。`Auto`の接続は、Routerが載るP11まで、`E-NO-ROUTE`(ルーター未搭載)で拒否する。
- **展開の上限(P3で確定)**: 展開後の部品は20万個まで(`BuildLimits.MAX_CELLS`。コンパイルのセル予算と同じ)、展開後の接続は計画自身の分を含めて20万本まで(`MAX_EXPANDED_CONNECTIONS`)。超える計画は、展開を始める前に`E-OUT-OF-BOUNDS`(キー`parts`/`connections`)で拒否する(実際に組んでから止めるのではなく、個数の見通しで先に拒否する)。

## 3. 部品(`PartType`)

```
record PartType(
    String id,                  // 例 "create:mechanical_press"、"micra:wall"
    PartCategory category,      // STRUCTURE|OPENING|ROOF|DECOR|POWER|TRANSMISSION|PROCESSING|LOGISTICS|STORAGE|FLUID|AERO|MODULE
    Visibility visibility,      // USER(JEIに出る・AIと画像に出す) | IMPLICIT(他の部品や設置処理が作る内部ブロック。名簿・見本帳・スキーマの選択肢に出さない)
    String displayNameKey,      // 翻訳キー(JEIと同じ表示名。D-18)。USERのみ必須
    String visualDescription,   // 見た目の短い説明(画像生成の指示文用)
    List<ParamSpec> params,     // 名前・型(int/enum/bool/material等)・範囲・既定値
    List<PortSpec> ports,       // 下記
    VolumeSpec volume,          // 占める体積(パラメータの関数)。建物の足跡(BuildingFootprint)とは別の型
    VersionRange requires,      // 例 create [6.0.10,6.1.0)。範囲外なら無効(D-13)
    PlacerId placer,            // どう置くか(SIMPLE / BELT / ARM / MULTIBLOCK / …)
    VerifyMode verify,          // この部品の既定の検証: EXACT / STATE_SUBSET(listed) / BLOCK_ONLY / ASSEMBLED_AWAY
    Set<String> volatileProps,  // 稼働で変わるブロック状態(例 blazeの段階、powered)。検証・撤去の比較・修復で無視し、修復で初期状態に戻さない
    EffectSpec effect,          // 世界に作用する部品の作用範囲(無ければNONE)。区画内に収まることを検査する(D-24)
    AssemblySpec assembly,      // 組み立てで世界から消える部品の場合の手順(無ければnull)。風車・飛行船など
    ModelRef kineticModel,      // 工場アナライザ用の挙動モデル(無ければ null → W-UNMODELED)
    BuildPhase phase)           // 施工順のグループ

record PortSpec(String name, PortKind kind /*ROTATION_IN|ROTATION_OUT|ITEM_IN|ITEM_OUT|FLUID_IN|FLUID_OUT|REDSTONE|HEAT|DOCK*/,
                LocalPos offset, Dir6 facing, Set<String> accepts /*相手が満たすべき条件*/)
record VolumeSpec(List<Box> boxes /*部品の局所座標での占有*/, Map<String,String> sizeFromParams /*パラメータから大きさを決める式*/)
record AssemblySpec(AssemblyKind kind /*WINDMILL|BEARING|PHYSICS_ASSEMBLER*/, String triggerPort,
                    AssemblyExpectation expect, String disassembleAction /*組み立てを解く操作。撤去・ロールバックの前に行う*/)
```

- 部品登録簿(`PartTypeRegistry`)は、`PartType`の集合と**版ハッシュ**(登録内容から計算)を持つ。施工リスト・ジョブ・承認は、この版ハッシュを記録し、版が違うものは承認・実行を拒否する。
- 登録簿から、次の3つを**自動生成**する(P-16): (a)AIの出力スキーマ(部品の選択肢はenum。`USER`のみ)、(b)画像生成の「使ってよい部品リスト」の文、(c)部品見本帳(`PartAtlas`)。
- **`IMPLICIT`の部品**: `create:belt`(ブロック。アイテムは`create:belt_connector`)、`create:powered_shaft`(アイテムを持たず、蒸気機関などが内部で作る)のように、JEIに部品として出ない物。`USER`部品の設置処理が作るので、登録簿には載るが、AI・画像・見本帳には出ない。材料の対応(F-7)は、設置する側の`USER`部品(ベルトなら`belt_connector`)で数える。
- 建築部品(`micra:*`)の`VolumeSpec`は`GENERATED`(生成器が決める。生成されたセルがそのまま占有体積)。占有体積はパラメータと親の箱(建屋の大きさ・壁の面)から決まり、生成器の外に独立して書ける式が無いので、宣言は生成器の写しになる。検査は宣言との一致ではなく、生成物が規則から手で導いた数と一致することで行う(`07` P3の完了条件10)。

## 4. 施工リスト(`PlacementManifest`)

```
record PlacementManifest(
    int manifestVersion, String planId, int planRevision, String registryVersion,
    String dimension, BuildFrame frame, Box worldBounds, List<Placement> placements /*施工順に整列済み*/,
    List<AssemblyStep> assemblies /*組み立ての手順*/,
    Map<String,Integer> bom /*材料表: 品物ID→個数*/, List<PhaseRange> phases, String hash)

record Placement(
    int index, IntPos pos, BlockSpec block, Map<String,String> blockEntityConfig /*許可済みキーのみ*/,
    String partNodeId /*どの設計ノード由来か。エラーの逆引き用*/, BuildPhase phase,
    PlacerId placer, VerifyMode verify, ReplacePolicy replaces /*AIR_ONLY|REPLACEABLE|EXPECT(blockId)*/,
    String assemblyGroup /*組み立てに含まれる場合のグループID。無ければnull*/)

enum BuildPhase { SITE_PREP, STRUCTURE, ENVELOPE, POWER, UPSTREAM, DOWNSTREAM, LOGISTICS, ASSEMBLE, DECORATION, FINISH }
enum VerifyMode { EXACT, STATE_SUBSET, BLOCK_ONLY, ASSEMBLED_AWAY }
record AssemblyStep(String groupId, AssemblyKind kind, IntPos trigger, List<Integer> memberIndexes,
                    AssemblyExpectation expect /*組み立て後に期待する結果*/)
sealed interface AssemblyExpectation {                       // 結果の型は、組み立ての種類で異なる(S-8で確定・調整)
    ContraptionExpectation(int entityCount, int movedBlockCount)      // Createのコントラプション(風車など)
    SubLevelExpectation(int subLevelCount, int movedBlockCount)       // Sableのサブレベル(飛行船など)
}
record AssemblyResult(String groupId, String assembledId /*エンティティUUIDまたはサブレベルID*/, int movedBlockCount, long atTick)
// 組み立て後の個体は、PlacedRegistry.assembliesに記録する。解体・撤去・ロールバックは、この記録で個体を特定して行う

record CompileResult(PlacementManifest manifest /*ERRORがあればnull*/, List<Issue> issues,
                     List<OpeningAdjustment> adjustments /*コンパイラが開口部を通すために行った局所補正の正本*/)
record OpeningAdjustment(String openingId /*直した開口部*/, List<String> wallIds /*マスの変わった壁*/,
                         String rule /*"corner-return"(角窓化)|"corner-dig"(角まわりの内側掘り)*/, int ruleVersion,
                         List<ChangedCell> cells /*施工順に整列*/, String reason /*機械向けの理由*/,
                         String note /*子供向けの日本語の説明*/)
record ChangedCell(LocalPos pos, String before /*変わる前の所有者とブロック("wall-e/minecraft:stone_bricks")*/,
                   String after /*置いたブロックID。空気にしただけなら"minecraft:air"*/)
// W-OPENING-ADJUSTEDはこの記録の投影(ユーザー向けの告知)で、acceptableではない — 承認するリスクではなく、既に行った補正の記録だから
```

- `PlanCompiler.compile(expandedPlan, registry, policy, SurveyRef survey) → CompileResult(manifest | issues, adjustments)`は**決定論**(`policy`は`PlaceableBlockPolicy`。入力は、サーバーが発行して固定した地形調査。世界の現在の状態そのものではない。04 F-3。**P3では`SurveyRef`は記録するだけで、地形の切り盛りはP4が載せる**): 同じ入力から、バイト単位で同じ施工リスト(=同じハッシュ)ができる。`ERROR`があれば`manifest`は`null`。
- 施工順は`BuildPhase`の昇順、その中は、局所座標で下から上(v)・手前から奥(w)・左から右(u)(v3の「動力 → 上流 → 下流」を`POWER→UPSTREAM→DOWNSTREAM`で表す)。`ASSEMBLE`は、同じグループの部品を全部置いた後に、組み立てを実行する(風車の帆、飛行船)。
- `hash`は`dimension`・`placements`・`assemblies`・`bom`・`registryVersion`・`worldBounds`から作る(`Placement`の`index`と`partNodeId`は含めない。並びは施工順で決まるので`index`は冗長で、`partNodeId`は逆引き用の情報。同じ建物は同じハッシュにする)。**承認の対象はこのハッシュ**(D-3)。
- **組み立てで消える部品の検証**(`VerifyMode.ASSEMBLED_AWAY`): 組み立て後は、その位置に元のブロックは無いのが正しい。`SnapshotDiff`はこの配置を「不在=正常」とみなし、代わりに`AssemblyStep.expect`(生成されたエンティティの数・移動したブロック数)を検査する。組み立て前の検査で、置いたブロックが有ることも確認する。L7が、組み立てで消えたブロックを「欠落」と誤判定して置き直すことは無い。

### 4.1 建てた後の変更(`ManifestDiff`)— 世界を壊さない撤去

```
record ManifestDiff(String fromHash, String toHash,
    List<RemovalEntry> removals, List<Placement> additions, List<PlacementChange> changes, int unchanged)
record RemovalEntry(Placement old, BlockSpec expectedNow /*旧施工リストが期待する現状*/,
                    BlockSpec restoreTo /*撤去後に戻すブロック。元ジョブのUndoEntry由来。無ければ空気*/)
record PlacementChange(Placement old, Placement now, BlockSpec expectedNow)
record Conflict(IntPos pos, BlockSpec expected, ObservedBlock observed, ConflictKind kind /*PLAYER_MODIFIED|MISSING*/)
```

- `MODIFY`ジョブは、**撤去も変更も、実行の直前に世界の現状を読み、`expectedNow`と一致するときだけ**行う。**比較は`VerifyMode.STATE_SUBSET`で行い、`PartType.volatileProps`(稼働で変わる状態)は無視する**(稼働中の機械が、全部`Conflict`にならないように)。**対象は`PlacedRegistry`に載る位置だけ**(D-25)。載らない位置は元からあった物なので、触らない。一致しなければ`Conflict`として記録して**その位置には触れず**、実行後にユーザーへ「あなたが変更した箇所です。そのままにしますか、施工リストの状態へ戻しますか」を返す(既定はそのまま)。旧マニフェスト由来という理由だけで、プレイヤーが置き換えたブロックを消さない。
- 撤去は上から下、追加は`BuildPhase`順。

## 5. 問題(`Issue`)— ループの共通語

```
record Issue(String id /*例 "E-STRESS-OVER:net-3"*/, IssueCode code, Severity severity /*ERROR|WARN|INFO*/,
             boolean acceptable /*ユーザーがリスクを承知で受け入れてよいか。コードごとに固定(05)*/,
             List<String> subjects /*設計ノード/接続のID*/, String message /*日本語*/,
             Map<String,String> data /*数値など*/, List<FixHint> hints)
record FixHint(String kind, Map<String,String> args)   // 例 ADD_POWER_SOURCE{need_su=512}、WIDEN_ROOM{room=r1,by=3}
```

`IssueCode`は、全ループで共通(追加は登録簿に載せる。網羅一覧は`05_parts_and_analyzers.md` 4.1節)。**受け入れ可能(`acceptable=true`)なのは、危険が世界や工場の動作の根幹に及ばないコードだけ**(例: `W-*`、`E-CLOG-RISK`)。`E-ROT-CONFLICT`、`E-STRESS-OVER`、`E-PORT-*`、`E-NO-ROUTE`、`E-BLOCK-FORBIDDEN`などは受け入れ不可で、残っていれば承認できない。

## 6. 工程・生産(工場層)

```
record RecipeOption(String recipeId, String recipeType /*create:pressing など*/,
    List<Ingredient> inputs, List<Result> outputs,
    List<FluidIngredient> fluidInputs, List<FluidResult> fluidOutputs,
    List<HeldTool> tools /*保持アイテム・触媒(デプロイヤー等)*/,
    SequencedSpec sequenced /*sequenced_assemblyの手順(反復・遷移)。無ければnull*/,
    CraftingShape crafting /*mechanical_craftingの形状。無ければnull*/,
    List<MachineSetup> setups /*この加工が成立する機械の組み立て。RecipeManagerのデータからは導出できないので、MachineSetupRegistry(05 4.4)が補う*/,
    Integer processingTicks, Heat heat /*NONE|HEATED|SUPERHEATED*/, String sourceHash)
record MachineSetup(String id, List<String> partTypes /*例 mixer+basin+blaze_burner*/, Heat provides)
record Ingredient(Set<String> itemIds /*タグは展開済み*/, String tagOrNull, int count)
record Result(String itemId, int count, double chance)

record ProcessGraph(List<ProcessStep> steps, List<Flow> flows, List<ExternalInput> inputs,
                    List<ProductTarget> products, PowerPolicy power)
record ProcessStep(String id, RecipeOption recipe, double targetPerMin, MachineSetup setup)
record Flow(FlowEnd from, FlowEnd to, String itemId, double perMin)
record FlowEnd(String stepId)
record ExternalInput(String itemId, double perMin, String viaDock /*搬入口*/, String toStepId)
record ProductTarget(String itemId, double perMin, String viaDock /*搬出口*/, String fromStepId)
record PowerPolicy(Set<String> allowedPlantTemplates /*例 mod:power_waterwheel*/, Integer maxPlants,
                   double marginRatio /*既定0.10*/, Map<String,String> siteLimits /*例 水源の有無*/)

record CapacityReport(List<StepCapacity> steps, PowerNetworkPlan power, List<ZoneDemand> zones, List<Issue> issues)
record StepCapacity(String stepId, int machineCount, double rpmRequired, double stressImpactSu, int footprintCells)
record ZoneDemand(String zoneId, ZoneKind kind, int floorCells, int minHeight, List<String> stepIds)   // 必要な区域(床面積・天井高)
enum ZoneKind { POWER_HOUSE, PRODUCTION_HALL, INPUT_DOCK, OUTPUT_DOCK, STORAGE, DOCK_PAD }
record RequiredBuilding(String roleId /*例 production-hall*/, List<String> zoneIds, double relativeShare /*全建屋の必要面積に対する比*/)
record PowerNetworkPlan(double totalImpactSu, List<PowerPlantChoice> plants, double capacitySu, double marginRatio)
record PowerPlantChoice(String templateId, Map<String,ParamValue> params /*水車の数・水流の配置、帆の数、ボイラーの大きさ・熱源の状態など*/,
                        int count, double capacitySu /*PowerSourceModelが params から計算*/)
```

- **L3(動力不足・過大)でProcess Plannerが変えられる物**: 目標の毎分(`targetPerMin`)、レシピの選択、機械の組み合わせ(`setup`)、`PowerPolicy`(許す動力源・台数の上限)。Capacity Calculatorは`PowerPolicy`の範囲で動力源(`PowerPlantChoice`)を選び、範囲内で足りなければ`E-POWER-NONE`と`FixHint`(例: 「目標を毎分◯以下に」「`mod:power_windmill`も許すと足りる」)を返す。
- 数値(応力の影響・容量・回転数)は、実行時にCreateの`BlockStressValues`から読む(D-9)。**動力源の出力は、部品の表の値だけでは決まらない**(水車は水流の配置、風車は帆の数、蒸気機関はボイラーの状態に依存)ので、`PowerSourceModel`が`PowerPlantChoice.params`から`capacitySu`を計算する。モデルの数値は忠実度テストで実測して確定する(`05` 4.5節)。

## 7. 建屋の意味解析・敷地

```
record ZoningPlan(List<BuildingFootprint> footprints, List<Corridor> corridors)
record BuildingFootprint(String id, Box baseLocal, int height, Rot rot, int minGapBlocks)   // 建屋の足跡(部品の体積とは別の型)
record Corridor(String id, List<LocalPos> path, int width)
record Adjustment(String footprintId, String description, LocalPos from, LocalPos to)   // Zoning Fixerが動かした内容
record SiteSurvey(Box worldBounds, int[][] surfaceY, String[][] surfaceBlock, boolean[][] water, boolean[][] tree, String digest)

record SemanticMap(String structureId /*建屋(structureノード)ごとに1つ作る*/, List<Level> levels, List<WallSegment> walls, List<RoofPlane> roofs, List<Opening> openings,
                   List<Room> rooms, List<Slot> slots, List<Entrance> entrances, List<Issue> issues)
record Room(String id, String structureId, int levelIndex, Box bbox, int volumeCells, boolean enclosed)
record Slot(String id, String structureId, String roomId, Box box, Set<Facing> allowedFacings, int clearanceAbove,
            int clearanceSides, boolean nearPower, boolean nearWindow, boolean nearEntrance)
record SpaceRequest(String structureId, int needFloorCells, int needHeight, int needLevels, List<String> slotIds)  // L4'
```

- `SemanticMap`は、**設計データから**(予測)も、**実際の状態から**(観測)も、同じアルゴリズムで作る(`VoxelClassGrid`経由)。予測と観測を比べることで、厳密な差分では気づけない意味の違い(壁に穴、部屋がつながっていない)を検出できる。

## 8. 施工ジョブ・検証(サーバー)

```
record ConstructionJob(
    int schemaVersion, String jobId, UUID ownerUuid, String dimension, String manifestHash, JobKind kind /*BUILD|MODIFY|REPAIR|ROLLBACK*/,
    String parentJobId /*REPAIR/ROLLBACK/MODIFYが対象とする元のジョブ*/,
    JobState state, PauseReason pauseReason, int cursor, int total, int repairRound,
    String claimId, MaterialPolicy materialPolicy,
    String journalFile, String ledgerFile /*別ファイル。SavedDataには置かない*/,
    String lastError, long createdTick)
enum JobState { PENDING_APPROVAL, QUEUED, RUNNING, PAUSED, VERIFYING, REPAIRING, ASSEMBLING, VERIFIED, PARTIAL, FAILED, CANCELLED, ROLLED_BACK }
enum PauseReason { OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, SERVER_BUSY, RECOVERY_NEEDED /*記録ファイルの破損・欠落*/, USER }

record UndoEntry(IntPos pos, BlockSpec before)          // ブロックエンティティを持つブロックの置換は禁止(F-5)なので、NBT本体は持たない
record MaterialLedger(int schemaVersion, Map<Integer,String> consumedByPlacementIndex /*設置ごとの消費。冪等*/, Map<String,Integer> reserved)

record SparseSnapshot(Map<IntPos,ObservedBlock> blocks)   // L7は、施工リストの配置位置だけを読む(範囲全体のマップを作らない)
record VoxelClassGrid(Box worldBox, byte[] classes)       // 意味解析用。1セル1バイトの分類(最大約157万セルで約1.5MB)
record ObservedBlock(BlockSpec spec, boolean hasBlockEntity, String blockEntityType)
record Deviation(int placementIndex, BlockSpec expected, ObservedBlock observed, DeviationKind kind /*MISSING|WRONG_BLOCK|WRONG_STATE|EXTRA|BLOCKED*/)

record SiteClaim(int schemaVersion, String claimId, UUID ownerUuid, String dimension, Box worldBox, Box operatingBox /*機械の作用範囲・発着場の上空の空きを含む*/,
                 boolean released, long createdTick)   // 工場が存在する間は保持(D-23)。ジョブの終了では解放しない
record ApprovalRequest(String manifestHash, String dimension, UUID playerUuid, long expiresTick)
```

- **既存の`drone/ServerBlockSnapshotReader.java`(Phase 2で追加済み。まだどこからも呼ばれていない)と`drone/BlockRangeDescription.java`は変更しない。** これらは、既存の`BlockSnapshotReader`インターフェース(文字列でブロック名だけを返し、1,000ブロック上限)を実装しており、クライアント実装とMCPツールと契約を共有しているため、状態つきの型に拡張すると既存のMCPを壊す。L7と意味解析用に、**別の契約**として、新設の`construction/ServerStateReader`(型付き、指定した座標の一覧を読む/範囲を複数tickに分けて分類グリッドに詰める)を作る。ブロック名の読み取りロジックだけは、`BlockRangeDescription`と共有する。
- `SparseSnapshot`と`SnapshotDiff.compare(manifest, snapshot, scope)`は決定論。**`scope`(`CompareScope`)で比較する範囲を限る**: `UpToCursor(n)`(施工の途中は、`cursor`より前の配置だけ。未施工の位置を欠落と扱わない)、`Phases(set)`、`All`(施工の完了後)。`VerifyMode`に従い、設置後に`Deviation`の一覧を返す。
- **状態の意味を分ける**: `JobState.VERIFIED`は**施工ジョブの状態**(施工が施工リストどおり、`Deviation`が0件)。**工場の検証状態ではない**。ジョブが`VERIFIED`になると、プロジェクトは`BUILT`になり、試運転(`COMMISSIONING`)へ進む。試運転に合格すると`COMMISSIONED`、失敗すると`COMMISSION_FAILED`。`W-UNMODELED`の部品を含む工場は、`COMMISSIONED`になるまで「試運転待ち(未検証)」と表示する。

## 9. AI進行・画像・知見(クライアント)

```
record BuildProject(int schemaVersion, String projectId, UUID ownerUuid, String worldId, ProjectKey chatKey, ProjectState state,
                    ConceptBrief brief, List<ImageArtifact> images, List<PlanRevisionRef> planRevisions,
                    List<ManifestRef> manifests, List<String> jobIds, List<CritiqueRecord> critiques,
                    Map<String,LoopCounter> loops, CostLedger ledger, String journalPath)

record ConceptBrief(List<ProductRequest> products, PowerPolicy power, StyleSpec style, int buildingCountHint,
                    boolean needsDock, Box siteHint, List<String> openQuestions)
record ProductRequest(String itemId, double perMin)     // 品物と毎分の量だけ。工程・搬出口(ProductTarget)は後の段の成果物なので参照しない

record ImageArtifact(int schemaVersion, String imageId, ImageKind kind, String path, String sha256, int width, int height,
                     String prompt, String promptHash, List<String> referenceImageIds,
                     List<DroppedReference> droppedReferences /*枠の都合で外した参考画像と理由(再現用)*/,
                     String generator /*codex-cli 0.144.1 等*/, String cameraPresetId,
                     ApprovalStatus approval, Integer codexTokens, Double costEstimateUsd /*不明ならnull*/, long createdAtMillis,
                     boolean stale /*上流が変わり、今の計画と食い違う可能性がある(03 0.3)*/)
record DroppedReference(String imageId, String reason)
enum ImageKind { CONCEPT_ART, BUILDING_RENDER, STRUCTURE_GUIDE, INTERIOR_SECTION, PART_ATLAS, SCREENSHOT, REVISION }

record PartAtlas(String atlasId, String registryVersion, String imageId, List<AtlasEntry> entries)
record AtlasEntry(String partTypeId, String displayName, int x, int y, int w, int h)

record StructureDescription(String imageId, List<DescribedBuilding> buildings, List<DescribedObject> objects,
                            List<DescribedFlow> flows, List<Unrecognized> unrecognized, double confidence)
record DescribedBuilding(String id, ShapeKind shape, NormBox region, int stories, RoofKind roof,
                         List<String> colors, List<String> features /*煙突など*/, double relativeFootprint /*全建屋の中での比(0〜1)*/)
record DescribedObject(String id, String partType /*登録簿の識別子のenum(D-19)*/, String buildingId, NormBox region,
                       int count, ObjectVisibility visibility /*VISIBLE|PARTIAL|HIDDEN(屋根や壁で見えない可能性)*/, String note)
record DescribedFlow(String fromObjectId, String toObjectId, ConnKind kind, boolean forward)
record Unrecognized(String id, String description, NormBox region, boolean prominent /*目立つ大きさか*/)

record CameraPreset(String id, double yawDeg, double pitchDeg, double distanceFactor, double fovDeg, String targetRule)
record CritiqueReport(double matchScore, List<Diff> diffs, String summary)
record Diff(String area, DiffKind kind /*SHAPE|ROOF|LAYOUT|DECOR|COLOR|IMPOSSIBLE*/, Severity severity, String description, List<String> targetNodeIds)
record RouteDecision(CritiqueCategory category /*MOOD|PRODUCT|BUILDING_FORM|LINE_LAYOUT|OTHER*/, String targetStage, Map<String,String> constraints)

record LoopBudget(String loopId, int maxIterations, Double threshold, Double plateauDelta, OnExceed onExceed /*ESCALATE*/)
record LoopCounter(String loopId, String scopeId /*対象: コンセプトID・建屋ID・画像ID・ジョブID・プロジェクトID(ループごとに03 0.1)*/,
                   int epoch /*上流の成果物が作り直されるたびに増える版。増えたら、下流のカウンタは新しい対象として数え直す*/,
                   int used, List<Double> scores /*ループごとの定義の量*/, boolean escalated)
// BuildProject.loops のキーは "loopId:scopeId"。複数建屋・上流変更後でも、回数が混ざらない

record CostLedger(List<CostEntry> entries, Caps caps)
record CostEntry(String stageId, Provider provider /*CLAUDE|CODEX*/, Double usd, Integer inputTokens, Integer outputTokens,
                 int imagesGenerated /*生成した画像の枚数(失敗した呼び出しは0)*/, boolean failed, long millis)
record Caps(double softUsd, double hardUsd,                 // Claude(金額で測れる物)
            int softImages, int hardImages,                 // 画像生成の回数(プロジェクトごと)
            long softCodexTokens, long hardCodexTokens)     // Codexのトークン数(金額に換算できないため)

record KnowledgeRecord(int schemaVersion, String id, KnowledgeKind kind /*SUCCESS_MODULE|FIX_HISTORY|CRITIQUE|PREFERENCE|FAILURE*/,
                       Set<String> tags, String payloadJson, String provenance, PromotionState promotion, long createdAtMillis)
record ModuleTemplate(int schemaVersion, String id, String displayNameKey, PartCategory category, VersionRange requires, Box footprint,
                      List<PortSpec> ports, List<PlanNode> nodes, List<Connection> internal,
                      TemplateStats stats /*RPM・応力・毎分の生産量*/, Verification verification /*解析版・忠実度/試運転の結果ハッシュ・検証の由来(BUNDLED_CI|PLAYER_COMMISSIONED)*/, Set<String> tags)
```

- **費用の上限**(D-10): Claudeは金額(`total_cost_usd`)、画像生成は**回数**、Codexは**トークン数**で、それぞれ警告線・上限線を持つ。Codexの金額は不明なので`null`のまま記録し、金額に換算した値を作って上限と比べることはしない。

## 10. 施工後の観測・物流・照合(補助の出力型)

```
record ReconcileConstraints(List<RequiredBuilding> requiredBuildings /*役割IDで持つ(絵の建屋IDは再生成で変わるため)*/,
                            Map<String,Set<String>> mustShowByRole /*役割ごとに、絵に描かれるべき部品ID(見える物だけ)*/,
                            Set<String> mustDrop /*絵から消すべき登録簿外の機械*/, String rationale)
// 絵の建屋と必要な建屋(RequiredBuilding)の対応づけは、決定論の順位づけで行う: 絵の建屋を relativeFootprint の降順、
// 必要な建屋を relativeShare の降順に並べ、同じ順位どうしを対応づける(同点は建屋IDの辞書順)。
record ReconcileResult(ReconcileStatus status /*OK|REGENERATE|ESCALATE*/, ReconcileConstraints constraints,
                       List<Issue> issues, double coverageScore /*0〜1*/)

record PredictionReport(Map<String,Double> perMinByProduct, Map<String,Double> stressUsageByNetwork,
                        Map<String,Double> rpmByNetwork, List<Issue> issues, List<String> unmodeledParts)
record CommissioningReport(String jobId, List<CommissioningCheck> checks, boolean passed,
                           List<String> leftoverItemsRecovered /*回収した品物の要約。正本はCommissioningJournal*/)
record CommissioningJournal(int schemaVersion, String jobId, List<TestInjection> injections)   // 投入の前に書き込む(先行ログ)。再起動後の回収に使う
record TestInjection(String opId, IntPos pos, String itemId, int count, InjectionState state, String expectedProduct, int collectedCount)
enum InjectionState { PLANNED, INJECTED, COLLECTED, RETURNED }                                 // 状態で冪等(二重に返さない)
record CommissioningCheck(String id, String description, boolean passed, String measured, String expected)

record RuntimeReport(long periodTicks, Map<String,RateEstimate> actualPerMin, Map<String,Double> plannedPerMin,
                     List<Bottleneck> bottlenecks, List<Issue> issues)
record RateEstimate(double lowerBound /*下限推定。L8の判定に使う*/, double estimate, double confidence)   // 標本の間の出し入れで過小になるため
record Bottleneck(String subjectId, BottleneckKind kind /*STALLED|OVERSTRESSED|BELT_BACKLOG|OUTPUT_FULL|LOW_SPEED|NO_FUEL*/, double severity)

record LogisticsPlan(List<Dock> docks, List<Route> routes, List<CargoFlow> flows)
record Dock(String id, Box padBox, Box clearanceBox /*上空の空き*/, Facing approach, List<PortRef> linkedPorts,
            List<String> dockingConnectorNodeIds /*係留部品*/)
record Route(String id, String fromDock, String toDock, List<LocalPos> waypoints, String airshipTemplateId)
record CargoFlow(String itemId, double perMin, String fromDock, String toDock)
```

## 11. 補助型(上の各節で使った型の定義)

```
sealed interface ParamValue { IntV(int) | NumV(double) | BoolV(boolean) | StrV(String) | EnumV(String) | MaterialV(String /*役割名か許可された素材ID*/) | ListV(List<ParamValue>) }
record Provenance(String stageId, String modelId, String promptHash, List<String> imageIds, long createdAtMillis)
enum Side { NORTH, EAST, SOUTH, WEST, TOP, BOTTOM, INNER, OUTER }                  // 面(部品の局所)
sealed interface Routing { Auto | Explicit(List<String> viaNodeIds) }
record Constraints(Integer maxLength, Set<String> avoidNodeIds, Integer maxTurns, Set<Dir6> allowedEntryDirs)
record NormBox(double x0, double y0, double x1, double y1)                           // 画像内の割合(0〜1)。実寸は持たない
enum ConnKind { ROTATION, ITEM, FLUID, REDSTONE, HEAT, DOCK }
enum PortKind { ROTATION_IN, ROTATION_OUT, ITEM_IN, ITEM_OUT, FLUID_IN, FLUID_OUT, REDSTONE, HEAT, DOCK }
enum Visibility { USER, IMPLICIT }                                                   // 部品の見え方(3節)
enum ObjectVisibility { VISIBLE, PARTIAL, HIDDEN }                                        // 絵の中での物体の見え方
enum AssemblyKind { WINDMILL, BEARING, PHYSICS_ASSEMBLER }
enum JobKind { BUILD, MODIFY, REPAIR, ROLLBACK }
interface ChatKey { String storageFileName(); }                                       // 既存のControllerKeyと新設のProjectKeyが実装
record ProjectKey(String worldId, String projectId) implements ChatKey
```

- `ChatKey`は、既存の`chat/ControllerKey`(制御ブロックの座標)と、新設の`ProjectKey`(プロジェクトID)を、同じ保存・セッションの仕組み(`ChatSession`、`ChatHistoryStore`)で扱うための共通の窓口。`ChatSession`の`ControllerKey`型のフィールド・コンストラクタ・戻り値と、`ChatHistoryStore.load/parse`の引数を`ChatKey`に一般化し、`ControllerKey`は`ChatKey`を実装する。**既存の挙動とテストは維持するが、公開の契約が複数変わる**ので、P7の計画書で影響範囲を洗い出す。**Refinerのセッションは、制御ブロックではなくプロジェクトに結びつく**ので、同じ制御ブロックから複数の工場プロジェクトを作っても履歴が混ざらない。
### 11.1 二次的な型と列挙(すべての値を定義)

```
// 部品・パラメータ
enum PartCategory { STRUCTURE, OPENING, ROOF, DECOR, POWER, TRANSMISSION, PROCESSING, LOGISTICS, STORAGE, FLUID, AERO, MODULE }
enum PlacerId { SIMPLE, BELT, ARM, MULTIBLOCK, ASSEMBLY }                 // 設置戦略(05 1.2。部品ごとの割り当てはS-5で確定)
record ModelRef(String modelId)                                            // KineticModel・PowerSourceModel内の挙動モデルの名前
enum ParamType { INT, NUM, BOOL, STR, ENUM, MATERIAL, INT_LIST }
record ParamSpec(String name, ParamType type, String unit, ParamValue min, ParamValue max,
                 ParamValue defaultValue, List<String> enumValues,
                 int maxItems /*INT_LISTの並びの長さの上限。リストでないパラメータは0*/)
record VersionRange(String modId, String mavenRange /*例 [6.0.10,6.1.0)*/)
sealed interface ReplacePolicy { AirOnly | Replaceable | Expect(String blockId) }
record PhaseRange(BuildPhase phase, int fromIndex, int toIndexExclusive)

// 建屋の意味解析
record Level(int index, int floorY, int height)
record WallSegment(String id, Side side, int level, Box box)
record RoofPlane(String id, Box box, RoofKind kind)
enum OpeningKind { DOOR, WINDOW, GATE }
record Opening(String id, String wallId, int u, int v, OpeningKind kind)
record Entrance(String id, String roomId, Side outsideSide)
enum ShapeKind { BOX, L_SHAPE, HALL, TOWER, SHED, OTHER }
enum RoofKind { GABLE, HIP, FLAT, SHED, SAWTOOTH, MONITOR }

// レシピ
enum Heat { NONE, HEATED, SUPERHEATED }
record HeldTool(Set<String> itemIds, boolean consumed)                      // デプロイヤーが持つ物など
record FluidIngredient(String fluidId, int amountMb)
record FluidResult(String fluidId, int amountMb)
record SequenceStep(String action /*deploy|press|fill|cut など*/, Set<String> itemIds, String fluidId)
record SequencedSpec(List<SequenceStep> steps, int loops)
record CraftingShape(List<String> rows, Map<Character,Set<String>> key)     // mechanical_crafting の形状

// プロジェクト・ループ・記録
enum ProjectState { BRIEFING, CONCEPT_ART, RECONCILING, DESIGNING, PREVIEWING, AWAITING_APPROVAL, BUILDING,
                    BUILT /*ジョブがVERIFIED*/, COMMISSIONING, COMMISSIONED /*試運転に合格*/, COMMISSION_FAILED,
                    OPERATING, ESCALATED, CANCELLED }
enum ApprovalStatus { PENDING, APPROVED, REJECTED }
enum OnExceed { ESCALATE }
enum Severity { ERROR, WARN, INFO }
enum Provider { CLAUDE, CODEX }
enum ReconcileStatus { OK, REGENERATE, ESCALATE }
enum DiffKind { SHAPE, ROOF, LAYOUT, DECOR, COLOR, IMPOSSIBLE }
enum CritiqueCategory { MOOD, PRODUCT, BUILDING_FORM, LINE_LAYOUT, OTHER }
enum CritiqueSource { USER, VISION_CRITIC }
record CritiqueRecord(String id, long atMillis, CritiqueSource source, String text, RouteDecision decision)
record PlanRevisionRef(String planId, int revision, String path, String hash)
record ManifestRef(String hash, String path, String planId, int revision)
enum KnowledgeKind { SUCCESS_MODULE, FIX_HISTORY, CRITIQUE, PREFERENCE, FAILURE }
enum PromotionState { CANDIDATE, PROMOTED, REJECTED, PINNED }                // PINNED=整理で消さない
enum VerificationOrigin { BUNDLED_CI, PLAYER_COMMISSIONED }
record Verification(VerificationOrigin origin, String analyzerVersion, String resultHash, long atMillis)
record TemplateStats(double rpm, double stressSu, Map<String,Double> perMinByProduct)

// 観測・ジョブ
enum BottleneckKind { STALLED, OVERSTRESSED, BELT_BACKLOG, OUTPUT_FULL, LOW_SPEED, NO_FUEL }
enum ConflictKind { PLAYER_MODIFIED, MISSING }
enum DeviationKind { MISSING, WRONG_BLOCK, WRONG_STATE, EXTRA, BLOCKED }
enum MaterialPolicy { CREATIVE_FREE, SURVIVAL_CONSUME }

// AI進行・承認・設置の記録
record Dossier(String stageId, Map<String,String> inputHashes /*入力の名前→内容のハッシュ*/, List<String> files, String hash)
record StageMemo(String stageId, String dossierHash, String outputJson, long atMillis)   // 入力が変わらなければ出力を再利用
record StaleMark(String artifactId, String becauseOf /*変わった上流の成果物ID*/)         // 03 0.3の「古い」印
record AcceptedRisk(String issueId, String note)                                        // ユーザーが受け入れた、acceptable=trueの問題
record PendingApproval(String manifestHash, String dimension, UUID owner, long expiresTick, List<Issue> issues, String surveyDigest)
record SurveyRef(String digest, long cachedUntilTick)                                   // サーバーが発行した地形調査の固定(F-3)
record PlacedRegistry(int schemaVersion, String claimId, Map<IntPos,PlacedEntry> placed, Map<String,AssemblyResult> assemblies)                   // このプロジェクトが置いたブロックの記録
record PlacedEntry(BlockSpec placed, BlockSpec before /*施工前*/, String jobId, int placementIndex)
record EffectSpec(EffectKind kind /*BREAK|FLUID|PROJECTILE|MOVE_STRUCTURE|NONE*/, Box reachLocal)   // 世界に作用する部品の作用範囲
enum EffectKind { NONE, BREAK, FLUID, PROJECTILE, MOVE_STRUCTURE }
```

## 12. パッケージ配置(案)

| パッケージ | 中身 | Minecraft依存 |
|---|---|---|
| `build.model` | 1・2・4・5節の型、正規JSON・ハッシュ(`CanonicalJson`・`Hashing`・`PlanJson`)、`BlockRotation` | なし |
| `build.parts` | `PartType`、`PartTypeRegistry`、`VolumeSpec`、パラメータ検証(`ParamValidator`)、`BuildingParts`、`MaterialFamilies`、スキーマ生成(`SchemaGenerator`) | なし |
| `build.plan` | `PlanPatcher`、`PlanExpander`、`ModuleTemplate`、`TemplateBundle`、`Origins`、`SlotResolver`、`Router`(IF)。`build.model`と`build.parts`の上の層(循環を避けるため) | なし |
| `build.compile` | 4節の型、`PlanCompiler`、`ManifestDiffer`・`Conflicts`(`ManifestDiff`)、`PlaceableBlockPolicy`、ハッシュ | なし |
| `build.compile.gen` | 建築部品(`micra:*`)の生成器と`Canvas` | なし |
| `build.analyze` | `VoxelClassGrid`、`BlueprintAnalyzer`、`ZoningFixer`、`CapacityCalculator`、`PowerSourceModel`、`Router`、`KineticModel`、`FactoryAnalyzer`、`SpaceKeeper`(N-35)、`StructureSurveyor`(N-36)、`ZoneLayer`(N-37)、`RecipeSource`(IF) | なし |
| `build.process` | `ProcessGraph`、`Reconciler`の決定論部分 | なし |
| `build.verify` | `SparseSnapshot`、`SnapshotDiff`、`RepairPlanner` | なし |
| `build.script` | `PlanRecorder`、`PlanScriptWriter`、`PlanScriptProfile`、`PlanScriptRunner` | なし |
| `lang`・`lang.ast`(既存) | 言語の字句・構文・`Interpreter`に、建設の橋(`PlanApi`・`PlanRunLimits`・`PlanCommandDispatcher`・`PlanModeDroneApi`)を足す | なし |
| `build.knowledge` | `ModuleLibrary`、`KnowledgeStore`の型 | なし |
| `build.ai` | `BuildOrchestrator`、`StageSpec`、`LoopBudget`、各段のプロンプトと検証 | なし(CLI呼び出しは`chat`側の橋渡し) |
| `construction` | `ConstructionJob`、`ConstructionExecutor`、`PlacementApplier`、`SafetyEnvelope`、`PlaceableBlockPolicy`、`MaterialPolicy`、`SiteClaimStore`、`ServerStateReader`、`ServerWorkerPool`、`ConstructionBudget`、`DroneShow`、`Commissioning`、`RuntimeMonitor`、サーバー側の調査と`RecipeSource`実装 | **あり(サーバー)** |
| `construction.net` | ペイロード群 | あり |
| `client.build` | レビュー画面、ホログラム、`PartAtlasRenderer`、`InGameRenderer`、画像表示、`LoopEscalationScreen`、`RouteChooserScreen` | **あり(クライアント)** |
| `chat`(既存) | `ClaudeCliBridge`に構造化出力・画像入力・予算指定を追加、`CodexCliBridge`を新設、`ChatKey`の導入 | なし |
| `build.pack` | `CapabilityPack`・`PackStatus`・`ModScanReport`・`EnabledRegistry`の型(13節)。登録簿の合成 | なし |
| `build.device` | 機器層の純Java部分(13節): `DeviceDescriptor`・`DeviceRegistry`・指示の検査(`DeviceGate`)・監視記録の型 | なし |
| `integration`(新設) | modのクラス(Create・Ponder・Flywheel・Registrate・Aeronautics・Sable・将来の能力パックのmod)に触れる橋渡しだけ。**modが入っているとModListで確かめてから読み込む**。他のパッケージがこれらのクラスをimport・参照することは`OptionalModBoundaryTest`が禁止する(F-11・F-25) | **あり(該当mod)** |

- **依存の向きは固定されている**: `lang.ast`と`build.model`は内部に何も見ない。`build.parts`は`build.model`、`build.compile.gen`は`build.model`と`build.parts`、`lang`は`lang.ast`・`build.model`・`build.parts`、`build.plan`は`build.model`・`build.parts`・`lang`、`build.compile`は`build.model`・`build.parts`・`build.plan`・`build.compile.gen`、`build.script`は`build.model`・`build.parts`・`lang`・`lang.ast`を見る。`BuildPurityTest`が、この向きと「Minecraft・NeoForge・Createをimportしない」を機械的に検査する(新しい向きを足すときは設計の変更として扱う)。P3の時点では、`build.*`を呼ぶMinecraft側のコードはまだ無い(P4の`construction`が載せる)。`lang`を呼ぶ既存の呼び出し側は、`drone`(畑の言語)と`client`(構文ハイライト)。`integration`パッケージは、build.*の型(13節)を実装してmodの実物に結びつける層で、**他のパッケージから`integration`をimportすることは禁止しないが、`integration`以外がmodのクラスに触れることを`OptionalModBoundaryTest`が禁止する**(向きの不変条件は「`integration`だけがmodのクラス名を知る」)。

## 13. 能力パック・機器モデル・未知のmodの読み解き(D-30〜D-32)

能力パック(F-25)、機器モデル・学びの層(F-26・`08`)、未知のmodの読み解き(F-27)の型。機器モデルの型のうち、`MicraLang`の世界観にだけ存在する約束事(操作名・状態名の語彙など)は、このプロジェクトのおもちゃ規格であって、**実在する規格(MHS)の実装ではない**(`08` 1節。推測の節にはその旨を明記する)。

```
record CapabilityPack(String packId /*例 "vanilla"、"create"、"aeronautics"、"sable"、
                                      "create_submarine"、"powergrid"、"create_copper_and_zinc"*/,
                      String displayNameKey,
                      List<ModRequirement> requiredMods /*全部が入り、版も範囲内のときだけ有効*/,
                      List<String> partIds /*このpackが登録簿に足す部品*/,
                      List<String> analyzerIds, List<String> moduleTemplateIds,
                      List<String> knowledgeTags, List<String> transportProfileIds,
                      List<String> recipeSourceIds,
                      List<String> openSpikes /*未確定の事実を示すスパイクID(S-11等)*/,
                      String docRef)
record ModRequirement(String modId, String mavenRange /*VersionRangeと同じ形*/)
enum PackState { ENABLED, DISABLED_ABSENT /*必要なmodが無い*/, DISABLED_VERSION /*版が範囲外*/, DISABLED_ERROR }
record PackStatus(String packId, PackState state, String reason /*なぜ無効か。日本語の説明文*/,
                  Map<String,String> actualVersions /*要求したmodの実際の版(入っていれば)*/)
record EnabledRegistry(PartTypeRegistry parts /*有効なpackの和集合*/,
                       Map<String,PackStatus> packs, String registryVersion /*版ハッシュ*/,
                       String enabledDigest /*有効packの組合せを決定的に要約した値*/)
record ModScanReport(int schemaVersion, String digest /*走査結果の決定的なハッシュ*/,
                     List<ScannedMod> mods, List<PackStatus> packs, long scannedAtMillis)
record ScannedMod(String modId, String version, String displayName, List<String> declaredDeps,
                  ModDisposition disposition, String reason)
enum ModDisposition { KNOWN_PACK /*要求するpackが有る*/, BUNDLED_IN_PACK /*他modのjarの内側(aeronautics同梱のsimulated等)*/,
                      UNKNOWN_COMPATIBLE /*未知だが、読み解きの結果は無害*/, UNKNOWN_UNSAFE /*未知かつ取り扱いが危険な可能性*/ }
```

- **`vanilla`は常に`ENABLED`の基底pack**(`requiredMods`は`minecraft`のみ)。`micra:*`の建築部品・畑ドローン・MCP読み取りツールを全部含み、他のmodが一切無くても動く。工場の雰囲気の建築(バニラブロック+`micra:*`)もこのpackだけでできる(D-30)。
- `EnabledRegistry`は**有効なpackの和集合だけ**を持つ。無効なpackの部品・テンプレート・レシピ源は、登録簿にも、スキーマ・見本帳・`query_part_types`にも出ない(P-16)。無効なpackの部品を使う保存済みの計画は、`E-PACK-DISABLED`(どのpackが無効かと理由つき)で拒否される。版の不一致は従来どおり`E-REGISTRY-VERSION`。
- **`unknown`なmodは登録簿に一切足さない**(部品を推測で登録しない。F-27)。

```
// 機器モデル(推測(本物のMHSではない): このプロジェクトの独自のおもちゃ規格。実在の規格との一致は主張しない)
record DeviceDescriptor(String deviceId /*例 "drone:ctrl-1"、"dock:east"*/, String kind,
                        String displayNameKey, String packId /*どのpack由来か。vanillaなら"vanilla"*/,
                        List<DeviceOp> reads, List<DeviceOp> writes, List<DeviceEvent> events,
                        List<SafetyLimit> limits, List<Precondition> preconditions,
                        List<String> tags /*自然言語の目印。日本語*/, String referenceSheetId)
record DeviceOp(String name /*例 "measure"、"move"*/, OpKind kind /*READ|WRITE*/,
                List<ParamSpec> params, String returns /*戻り値の型名*/, String description /*日本語*/,
                boolean needsApproval /*人の確認が要るWRITE(08 4節)*/)
enum OpKind { READ /*世界を変えない*/, WRITE /*世界・機器の状態を変える*/ }
record DeviceEvent(String name /*例 "fish_bite"、"dock_arrived"*/, List<ParamSpec> payload,
                   String description)
record SafetyLimit(String name /*例 "max_speed"、"reach"*/, ParamValue limit, boolean hard,
                   String reason /*この限界の日本語の説明*/)
record Precondition(String id, String description /*日本語*/, String check /*決定論の検査の名前*/)
record DeviceInstruction(String deviceId, String op, Map<String,ParamValue> args,
                         String issuer /*"player"|"script"|"ai-plan"*/, long issuedAtMillis)
record InstructionVerdict(Verdict verdict /*ALLOW|CLAMP|REFUSE*/, DeviceInstruction normalized,
                          List<Issue> issues, String reason)
enum Verdict { ALLOW, CLAMP /*安全限界に丸める。丸めた事実はinstructionと一緒に記録*/, REFUSE }
record MonitorRecord(String deviceId, long atTick, String opOrEvent, String valueJson,
                     String note /*異常時の説明*/)
record ReferenceSheet(String sheetId, String deviceId, int schemaVersion,
                      String bodyMarkdown /*日本語。子供が読める表現で、「できること・できないこと・絶対に止まる条件」*/,
                      String registryVersion)
enum DeviceState { OFFLINE, ONLINE, BUSY, FAULT, ESTOP /*緊急停止中。全WRITEを拒否*/, RECOVERING }
```

- `DeviceGate`(純Java)が、すべての`DeviceInstruction`を、**実行の前に**検査する: (1)デバイスが存在し`OFFLINE`/`ESTOP`でない、(2)事前条件(`Precondition.check`の決定論の検査)を満たす、(3)`hard=true`の`SafetyLimit`を超える引数は`REFUSE`(`CLAMP`は限界内へ丸めるだけの種類の限界に限る)。**AI・スクリプトは`DeviceGate`を迂回できない**(P-17)。検査結果は`MonitorRecord`に残る。
- 緊急停止(`ESTOP`): 発動した機器は全`WRITE`を`E-ESTOP`で拒否し、動作中の操作は停止する。解除は所有者またはOPの明示操作でのみ可能で、発動・解除ともに`MonitorRecord`とジョブの`journal`に残る。
- 機器の出来事(`DeviceEvent`)は、言語の`attach_isr(face, fn)`/`raise_interrupt(face)`の面名`"device:<deviceId>:<event>"`に対応づける(既存のソフトウェア割り込み機構を流用。実物のレッドストーン立ち上がり→面名の配線は別途、S-15)。

```
// 未知のmodの読み解き(F-27。型は走査結果の表現だけで、modのクラスは一切importしない)
record UnknownModNote(String modId, String version, List<String> observedFacts /*メタデータから確かめられた事実だけ*/,
                      List<String> missing /*分からなかったこと*/, String childExplanation /*子供向けの説明文*/)

// 事実の目録(ModScanが、ゲームが既に読み込んだレジストリ・レシピ・タグ・言語・汎用の能力から集める)
record ModCatalog(int schemaVersion, String modId, String version,
                  List<CatalogEntry> entries, List<String> unknowns /*読めなかった項目*/,
                  String digest)
record CatalogEntry(String factId, CatalogKind kind, String key /*ブロックID・タグ名など*/,
                    Map<String,String> data /*決定的な形に正規化した事実*/)
enum CatalogKind { BLOCK, ITEM, BLOCK_STATE_PROP, BLOCK_ENTITY, TAG, RECIPE,
                   LANG_ENTRY, CAPABILITY /*品物・液体・エネルギーのハンドラ*/, REDSTONE_BEHAVIOR }

// AIの通訳(N-34)が作る草案。すべての主張は根拠の事実と信頼度を持つ。未検証の草案は施工に使えない
record ModDraft(String draftId, String forModId, int schemaVersion,
                List<DraftPartType> parts, List<DraftDevice> devices,
                List<String> moduleTemplateIdeas, List<DraftClaim> claims,
                List<String> unknowns)
record DraftPartType(String proposedPartId, String roleGuess, Map<String,String> paramsGuess,
                     String evidenceFactId)
record DraftDevice(String proposedDeviceId, List<String> reads, List<String> writes,
                   List<String> events, List<String> limitsGuess, String evidenceFactId)
record DraftClaim(String text /*日本語*/, String evidenceFactId, Confidence confidence)
enum Confidence { HIGH, MEDIUM, LOW }
enum DraftStatus { DRAFT /*作られただけ*/, SANDBOX_TESTED /*試験区画で実測済み*/,
                   APPROVED /*人が承認*/, PROMOTED /*能力パックへ昇格*/, REJECTED }
```

- `ModScanReport`は起動のたびに作られ、`digest`が版ハッシュ(`registryVersion`)に含まれる。**同じmod構成からは必ず同じレポートが出る**(決定論)。
- `childExplanation`は、「このmodは私たちが知らないので、工場の部品としては使いません。自分でブロックとして置くことはできます」といった、**子供に向けた説明文**を生成する(F-27)。
- **AIに渡すのは`ModCatalog`の事実だけ**(jar・modの説明文などの自由文は渡さない)。`DraftClaim`は全部`evidenceFactId`を持ち、根拠の無い主張は検証で落とす。**施工に使えるのは`PROMOTED`になったpackだけ**(草案→サンドボックス→承認→昇格の流れはF-27)。

## 14. 動線と空き・輸送手段・既存建築・ゾーン階層(D-33〜D-36)

林さんの要件C(動線と空き)・D(輸送手段)・E(既存建築の計測)・G(ゾーンの階層)の型。**寸法・数値はこの節に書かない**(ゲームやmodの事実はスパイクS-16〜S-19で実測してから書く)。

### 14.1 空きの予約と動線(F-28)

```
// 「空き」を部品と同じく計画の中の物にする。部品を置く前に先に予約し、侵入は重なりと同じく拒否する
record ReservedSpace(String id, Box box, SpacePurpose purpose,
                     String transportProfileId /*TRANSPORT_PATHのとき、どの輸送手段のための空きか*/,
                     String ownerId /*この空きを要る対象(ゾーン・建屋・発着場のID)*/,
                     String reason /*日本語の説明。子供に読める文*/)
enum SpacePurpose { CIRCULATION /*人・荷物・ドローンの通り道(「空いていること」自体が要件)*/,
                    TRANSPORT_PATH /*輸送手段の通り道(歩道・車道・航路・動力の通り道)*/,
                    APPROACH /*発着場・港・滑走路への進入余白*/,
                    CLEARANCE /*操作・整備のための余白*/ }

// 建屋の内部の動線(廊下)。「在る物」ではなく「通れること」への要求として宣言する
record CirculationReq(String id, String structureId,
                      List<String> endpoints /*つなぐ出入口・部屋・スロットのID*/,
                      SpacePurpose purpose, String transportProfileId)
```

- `ReservedSpace`は`ZoningPlan`(敷地レベル)と`SemanticPlan`/`SemanticMap`(建屋の内部レベル)の両方に出る。Site Planner(N-10)が敷地の動線・輸送の通り道を**足跡より先に**予約し、Architect(N-13)・Module Planner(N-16)が建屋内の廊下を`CirculationReq`として宣言する。
- 検査は決定論: (a)部品・足跡が`ReservedSpace`に侵入→`E-RESERVED-CONFLICT`。(b)`CirculationReq`が、出入口から各端点まで、`transportProfileId`の指す`TransportProfile`の`minWidth`×`minHeight`(14.2節)以上の断面の空気の連なりとして実在するかを、`SemanticMap`の`VoxelClassGrid`上で洪水塗り(決定論)で確かめる。通れなければ`E-CIRCULATION-BROKEN`(塞いでいる位置つき)→L4(配置の見直し)かL4'(建屋の拡張)の戻し先。
- **「歩けること」は数値で決めるが、数値はスパイクS-16で実測してから書く**(子供の歩行・畑ドローン・荷物の通り道の最小断面)。`CirculationReq`は`SpacePurpose.CIRCULATION`が既定で、歩く人を最優先・荷物とドローンも全部対象(林さんの要件)。

### 14.2 輸送手段のモデル(F-29)

```
enum TransportMode { ON_FOOT /*歩行*/, BELT /*ベルト等の品物の通り道*/, SHAFT /*チェーンドライブ等、動力の通り道*/,
                     PIPE /*液体*/, GROUND_VEHICLE /*地上車両(offroad:wheel_mountなど)*/,
                     AIRCRAFT /*飛行機(滑走路が要る)*/, AIRSHIP /*気球・飛行船(既存)*/,
                     SHIP /*船(港・水深が要る)*/, SUBMARINE /*潜水艇(港・水深・水密が要る)*/ }
record TransportProfile(String id, TransportMode mode, String packId /*どのpackの知識か*/,
                        int minWidth, int minHeight, int minTurn /*通り道の最小断面と最小旋回(ブロック)*/,
                        int minRunLength /*滑走路など*/, int minWaterDepth /*港・泊位の水深*/,
                        boolean needsWatertight /*船体の水密が要るか*/,
                        Map<String,String> extra, String source /*数値の出処(スパイクIDまたは計測記録)*/)
```

- `LogisticsPlan`を飛行船専用から輸送手段一般へ拡張する(フィールドの追加のみ、既存の読み方を壊さない): `Dock`に`TransportMode mode`(既定`AIRSHIP`で現行と同じ意味)と水上用の条件(`minWaterDepth`)を、`Route`に`mode`と`transportProfileId`を足す。`CargoFlow`は不変。
- `SiteSurvey`は列ごとの`water`(boolean)に加えて**水深**(`int[][] waterDepth`)を持つ。港・泊位の可否は`minWaterDepth`との比較で決まる。
- **敷地のサイズで手段を選ぶ**: `E-MODE-UNFIT`(敷地がその手段の空間要求に足りない。例: 滑走路が置けない)のときは、別の手段を提案してSite Plannerへ戻す(空輸が無理なら車両や船)。提案は`TransportProfile`の比較で決定論側が候補を出し、AIは選ぶだけ。
- **船・潜水艇の水密**: `needsWatertight=true`の輸送手段の船体・収容空間は、水が浸入しないかを検査する(浸水の規則はS-11/S-17で実測)。検査に漏れがあれば`E-HULL-LEAK`。
- **バッテリー(`powergrid`)**: 回転ネットワーク(`KineticModel`)とは別の電気の蓄えとして扱う。設計での位置づけ(発電・蓄電・消費のモデルに入るか)はS-12の確定まで保留し、`TransportProfile`/`PowerSourceModel`のどちらにも仮の数値は書かない。

### 14.3 既存建築の計測(F-30)

```
enum StructureSource { OWN_PLAN /*このmodが建てた: 保存済み計画から正確に*/,
                       RECOGNIZED /*プレイヤーが手で建てた物: 観測からの認識。不確かさつき*/ }
record ExistingStructureProfile(String id, StructureSource source, Box extent,
                                List<PassageProfile> passages /*通路・出入口の幅・高さ・材質*/,
                                StyleObservation style, Confidence confidence,
                                String basis /*根拠(どの保存計画/どの観測範囲か)*/)
record PassageProfile(Box span, int width, int height, String materialHint)
record StyleObservation(Map<String,Integer> materialHistogram /*材質→セル数の分布*/,
                        List<String> motifs /*認識した様式の手がかり(柱のリズム・破風など)。日本語*/)
```

- 2つの経路を決定として分ける(D-35): (a)このmodが建てた建屋は`PlacedRegistry`・保存済み計画から**正確に**、(b)手造りの建物は`ServerStateReader`→`VoxelClassGrid`から**認識**し、`confidence`と「分からなかった所」を明示する。認識がどこまで可能かはスパイクS-18で先に測る(正確さの約束は測ってから)。
- `StyleSpec`に「既存に合わせる」モードを足す(`matchExisting=true`のとき、Refiner・Site Planner・Architectの資料に`ExistingStructureProfile`が入り、配色・材質・通路の寸法を既存に揃える)。認識由来の値を使った計画は`W-STRUCTURE-UNCERTAIN`を出す。
- 範囲は区画(`SiteClaim`)の内側だけ、サーバー側だけで読み、量は`VoxelClassGrid`の作法(F-21。範囲を複数tickに分ける)に従う。

### 14.4 ゾーンの階層(C4風。F-31)

```
enum ZoneLevel { SITE /*文脈: 敷地*/, ZONE /*コンテナ: 建屋・庭・港・滑走路*/,
                 COMPONENT /*部屋・モジュール・スロット*/, PART /*部品とセル*/ }
enum ZoneRole { BUILDING, YARD, PORT, RUNWAY, ROAD, UTILITY }
enum WindowKind { DOOR /*出入口*/, LOGISTICS_PATH /*物流の通路*/, POWER_PATH /*動力の通路*/,
                  DOCK_END /*発着場の端*/, WATER_PASSAGE /*水の通路*/ }
record Zone(String id, ZoneRole role, Box extent, String parentZoneId,
            List<Window> windows /*ゾーンの外と接する「窓」=インターフェースだけを上に見せる*/,
            ZoneBudget budget)
record Window(String id, String zoneA, String zoneB, WindowKind kind, Box span,
              String transportProfileId /*通路・動力の窓のとき*/)
record ZoneBudget(int maxAttempts /*ゾーン内のコンパイルの試行上限*/, int maxParts, int maxCells)
record ZoneSummary(String zoneId, ZoneRole role, Box extent, List<Window> windows,
                   Map<String,String> metrics /*床面積・部品数など*/, String textJa /*日本語の要約*/)
```

- **上位の層が知るのは、役割・範囲・窓だけ**(中身は見ない)。窓は粗い層(Site Plannerが出すゾーン案)で先に固定し、ゾーン内部の細かい作業は窓を動かせない。接続の両側のゾーンの窓は一致が要る→合わなければ`E-WINDOW-MISMATCH`。
- **ゾーンごとの予算**: コンパイル・アナライザの作業量はゾーン単位の`ZoneBudget`に分ける(1つの巨大なゾーンが全体の上限を使い切る事故=台帳T11-3の対策)。上限の数値はS-19で測ってから書く。ゾーンが予算を超えれば`E-ZONE-BUDGET`でそのゾーンだけ戻る。
- **AIの文脈は`ZoneSummary`**(役割・範囲・窓・指標・日本語の要約)。AIは計画全体ではなく要約を読む。ゾーン内の変更は、そのゾーンの窓への照合だけで再検証できる(全体を再コンパイルしない)。
- 既存の型との関係: `ZoningPlan`は「ゾーン層の計画」に相当し、`Zone`はその上位の構造。`BuildingFootprint`/`Corridor`は`ZoneRole.BUILDING`/`ROAD`の中身になる。`SemanticMap`/`Slot`はCOMPONENT層、部品とセルはPART層。`Dock`/`LogisticsPlan`の発着場は`PORT`・`RUNWAY`ゾーンの窓(`DOCK_END`)とつなぐ。要件Cの`ReservedSpace`・要件Dの`TransportProfile`は、窓とゾーン内の空きの両方に効く。
