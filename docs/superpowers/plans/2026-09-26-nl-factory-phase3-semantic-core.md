# 自然言語→工場建設 Phase 3: 意味の核(純Java) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 設計データ(`SemanticPlan`)・差分(`PlanPatch`)から、決定論的に施工リスト(`PlacementManifest`)を作る「意味の核」を、Minecraftに依存しない純Javaで作る。部品登録簿(建築部品`micra:*` 22種)、`PlanPatcher`、`PlanExpander`、`PlanCompiler`、`ManifestDiff`、`PlaceableBlockPolicy`(純Java部分)、正規JSONとハッシュ、`SchemaGenerator`、建設スクリプト(`PlanApi`・`PlanRecorder`・`PlanScriptWriter`・許可リスト方式の静的検査)を含む。

**Architecture:** `io.github.khayashi4337.micradrone.build.*`に純Javaの核を置く(`net.minecraft`・`net.neoforged`をimportしない。D-16。構造検査のテストで機械的に守る)。計画は局所座標(u,v,w)で作り、`BuildFrame`で世界へ写す(回転不変を構造的に保証。D-29)。建設スクリプトは、既存の独自言語(`lang/*`)に**別の`PlanApi`**として足す(畑の`DroneApi`・`CommandNames.ALL`は変えない。D-28)。

**Tech Stack:** Java 21(record・sealed interface・パターンマッチ)、JUnit 5(既存の`testImplementation`)、既存の`chat/MiniJson`(公開にする)。新規ライブラリなし。

**Spec:** `docs/design/nl_factory_builder/`の第4版(`00`〜`07`)。特に `01_data_model.md`の1〜5・11節、`04_foundations.md`のF-6・F-20、`05_parts_and_analyzers.md`の1.1・1.1.1・4.1節、`07_phases_and_verification.md`のP3、決定D-2/D-3/D-14/D-15/D-16/D-22/D-28/D-29。

## Global Constraints

(設計図から逐語。すべてのタスクの要件に含まれる。)

- 純Javaの核(`io.github.khayashi4337.micradrone.build.*`)は、`net.minecraft.*`と`net.neoforged.*`を一切importしない(D-16)。座標は`int`3つ、ブロックは文字列の識別子とプロパティ表で持つ。
- JSONは既存の`chat/MiniJson.java`(外部ライブラリなし)で読み書きする。ハッシュ用の正規形は、キーを辞書順、整数はそのまま、小数は`BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()`(`NaN`・無限大は拒否)、余計な空白なし、で書いたUTF-8のバイト列。ハッシュは正規形のSHA-256(小文字16進)。
- IDは`[a-z0-9-]{1,48}`。AIが付け、決定論コードが重複と形式を検査する。問題の指摘(`Issue`)は、必ずこのIDで対象を指す。
- 座標系: `u`=右、`v`=上、`w`=前。局所の`Facing`は`NORTH`=+w、`EAST`=+u、`SOUTH`=-w、`WEST`=-u。`facing=NORTH`のとき`world = origin + (u, v, -w)`。それ以外は、水平の成分`(dx, dz) = (u, -w)`を時計回りに`q`回(1回は`(dx,dz) → (-dz, dx)`)回してから足す。
- `PlanCompiler.compile`は決定論(同じ入力からバイト単位で同じ施工リスト=同じハッシュ)。ハッシュは`dimension`・`placements`・`assemblies`・`bom`・`registryVersion`・`worldBounds`から作る(`index`・`partNodeId`は含めない)。承認の対象はこのハッシュ(D-3)。
- `PlanPatcher.apply`は決定論。`baseRevision`が現在と違えば拒否(`E-PATCH-STALE`)。`ERROR`が1つでもあれば、パッチ全体を拒否して`plan`は`null`。
- 建設スクリプトは許可リスト方式の決定論プロファイル: 建設の命令、制御構造、純粋な補助(`len`・`abs`・`min`・`max`・`str`・`list`・`dict`・`set`・`range`・`print`)だけ。乱数・時刻・知覚・畑の命令・`create_task`・`semaphore`・`attach_isr`・`raise_interrupt`・`sleep_ticks`は静的検査で拒否。農場の命令との混在も拒否。実行の上限は既定100,000ステップ・5秒。
- 既存の`DroneApi`・`LiveDroneApi`・`PacedActionQueue`・`DroneControllerBlockEntity`の挙動は変えない(P-15)。畑用の`CommandNames.ALL`は変えない。既存のテスト47ファイルはすべて緑のまま。
- 1スクリプトは`MAX_SCRIPT_CHARS`(10,000字)以内。大きな計画は、複数のスクリプトに分割して出す。
- 置いてはいけないブロック(コマンドブロック・岩盤・スポナー・バリア・ストラクチャーブロック・ジグソー・光ブロックなど)は、素材・部品のどちらの経路でも常に`E-BLOCK-FORBIDDEN`(受け入れ不可。D-22)。
- 地形の切り盛り(整地)はP4が載せる。P3の`SurveyRef`は記録するだけ。
- 既存の慣習に合わせる: コメントは英語で技術的な理由を書く(「林さんの要望」等とは書かない)。定数は名前を付ける(生の数値の直書きをしない)。同じ処理の複製は共有ヘルパーへ。

## Review Focus

(設計が暗に求めるが、どのタスクのテストにも自然には出てこない、実害の大きい入力。最も起きやすい順。各行の対策テストは、担当のタスクに書いてある。)

1. **巨大な計画**: 64×64の建屋を8階建て、`dock_pad`64×64を複数、道128×8を大量、などでセル数が爆発する。期待: 上限(200,000セル)で`E-OUT-OF-BOUNDS`になり、メモリを食い尽くさない(Task 10)。
2. **空の計画・敷地なし・親の無い壁**: `site`が無い、`structure`が無いのに`wall`だけある、など。期待: 例外でなく`E-SITE-MISSING`・`E-ANCHOR`の`Issue`(Task 8・10)。
3. **文字列の特殊文字**: `label`・`sign.text`・パレットに、引用符・バックスラッシュ・改行・日本語・絵文字。期待: スクリプトの往復で`contentHash`が変わらない(Task 21)。
4. **同じパッチの中での矛盾**: 同じIDの追加→削除→追加、追加したノードを同じパッチで`UpdateParams`、存在しない親への追加。期待: 順に適用し、矛盾は`Issue`。半端に適用しない(Task 8)。
5. **整数のあふれ・小数の混入**: `width`に`3000000000`や`7.5`や`NaN`、`holes`に巨大な数。期待: `E-PARAM-RANGE`。例外で落ちない(Task 6・8)。

## File Structure

新規(すべて`src/main/java/io/github/khayashi4337/micradrone/`の下。テストは`src/test/java/...`の同じ相対パス。金のファイルは`src/test/resources/build/golden/`):

| パッケージ | クラス | 責務 |
|---|---|---|
| `build.model` | `Facing`・`Dir6`・`IntPos`・`LocalPos`・`Box`・`Rot`・`BuildFrame`・`BlockSpec`・`BlockRotation` | 基本型と向き・回転 |
| `build.model` | `CanonicalJson`・`Hashing`・`JsonTree`・`PlanJson`・`PlanJsonException` | 正規JSON・ハッシュ・計画のJSON変換 |
| `build.model` | `Issue`・`IssueCode`・`Severity`・`FixHint` | 問題(ループの共通語) |
| `build.model` | `ParamValue`・`Provenance`・`Side`・`StyleSpec`・`Site`・`Anchor`・`ConnKind`・`PortRef`・`Routing`・`Constraints`・`Connection`・`PlanNode`・`LogisticsPlan`(+`Dock`・`Route`・`CargoFlow`)・`SemanticPlan`・`PlanOp`・`PlanPatch` | 設計データと差分 |
| `build.plan` | `PlanPatcher`・`PatchResult`・`ModuleTemplate`・`TemplateStats`・`Verification`・`VerificationOrigin`・`TemplateBundle`・`ExpandedPlan`・`RoutedConnection`・`Router`・`SlotResolver`・`Origins`・`PlanExpander`・`ExpandResult` | 差分の適用と、テンプレート・接続の展開(`build.model`と`build.parts`の上の層。循環を避けるため設計図の案から分けた) |
| `build.parts` | `PartCategory`・`Visibility`・`ParamType`・`ParamSpec`・`PortKind`・`PortSpec`・`VolumeSpec`・`VersionRange`・`PlacerId`・`VerifyMode`・`EffectKind`・`EffectSpec`・`AssemblyKind`・`AssemblyExpectation`・`AssemblySpec`・`ModelRef`・`BuildPhase`・`PartType`・`PartTypeRegistry`・`PartTypeJson` | 部品と登録簿 |
| `build.parts` | `ParamValidator`・`ParamException`・`Params`・`MaterialFamilies`・`BuildingParts`・`SchemaGenerator`・`SchemaLimits`・`SchemaTooLargeException` | パラメータ検証・素材の族・建築部品の定義・スキーマ生成 |
| `build.compile` | `ReplacePolicy`・`Placement`・`PhaseRange`・`AssemblyStep`・`PlacementManifest`・`CompileResult`・`SurveyRef`・`ManifestJson`・`BomCalculator`・`PlaceableBlockPolicy`・`BuiltinAllowList` | 施工リストと、置いてよいブロック |
| `build.compile` | `PlanCompiler` | コンパイラ |
| `build.compile.gen` | `Canvas`・`GenContext`・`Palette`・`BlockForms`・`GenAbort`・`StructureInfo`・`WallInfo`・`PartGenerator`・`PartGenerators`・各生成器 | 建築部品の生成器 |
| `build.compile` | `ManifestDiff`・`RemovalEntry`・`PlacementChange`・`ManifestDiffer`・`Conflict`・`ConflictKind`・`ObservedBlock`・`Conflicts` | 建てた後の変更 |
| `build.script` | `PlanRecorder`・`PlanScriptWriter`・`PlanScriptProfile`・`PlanScriptRunner` | 建設スクリプトの記録・出力・静的検査・実行 |
| `lang` | `PlanApi`・`PlanAnchorArgs`・`PlanRunLimits`・`PlanLimitException`・`PlanModeDroneApi`・`PlanCommandDispatcher` | 建設命令の橋渡し(`Interpreter`に足す) |

変更: `chat/MiniJson.java`(公開)、`lang/CommandNames.java`(`PLAN`等を足す。`ALL`は変えない)、`lang/Interpreter.java`(`PlanApi`対応と上限)、`lang/SyntaxHighlighter.java`(多重定義)、`drone/CommandsHelpDoc.java`(`BUILD_COMMANDS`)、`assets/micradrone/lang/en_us.json`(部品の表示名)。

## 完了条件 × タスク(空欄が出たらタスクを足す。削らない)

| 完了条件(設計図07 P3。設計図第4版) | 担当タスク |
|---|---|
| 1. 手書きの`PlanPatch`(JSON)から施工リストができ、同じ入力を1,000回変換して同じハッシュ | Task 8(パッチ適用)、Task 10(コンパイラ)、Task 16(金のファイルと1,000回) |
| 2. 回転不変(4方向、全部品種) | Task 16(全22種を含む性質テスト) |
| 3. 往復(`SemanticPlan → スクリプト → PlanRecorder → SemanticPlan`でハッシュ不変。複数スクリプト分割も) | Task 21 |
| 4. 許可リスト方式の決定論プロファイル(拒否・混在の拒否) | Task 19(命令と`Interpreter`)、Task 20(静的検査) |
| 5. `build.*`が`net.minecraft`をimportしていない検査 | Task 23 |
| 6. 既存の47テストが緑。`CommandNames.PLAN`・`CommandsHelpDoc.BUILD_COMMANDS`・構文ハイライトが新命令を認識 | Task 19、Task 22、Task 23 |
| 7. `E-PARAM-RANGE`等の`Issue` | Task 6、Task 8 |
| 8. `E-BLOCK-FORBIDDEN` | Task 10(方針)、Task 16(素材・部品の両経路) |
| 9. `ManifestDiff`(`RemovalEntry`の`expectedNow`・`restoreTo`) | Task 17 |
| 10. 建築部品22種の生成(登録簿と生成器の一致、金のファイル、`Conflict`の判定) | Task 7、Task 10〜16、Task 17 |
| 11. `SchemaGenerator` | Task 18(Task 1のS-1の結果に従う) |
| 12. スクリプトの実行の限界(総ステップ・時間で止まる) | Task 19 |
| (実機確認)`runClient`が従来どおり起動する | Task 23 |
| (F-19)ヘルプ・README・CurseForge | Task 22(P3は利用者から見える入口が無いので、ゲーム内ヘルプの文書だけ。READMEとCurseForgeは入口ができるP5で更新すると、Task 22に明記) |

---

### Task 1: スパイクS-1(`--json-schema`の機能と大きさの限界)【完了。コミット`c75642e`】

背景の調査担当が`docs/investigations/spk_s1_json_schema_limits.md`を作り(コードは書かない。実測75回・約2.94ドル)、私が読んで、設計図(`06`・`07`)に反映した。**この計画書の実行では、追加の作業は無い**。Task 18(`SchemaGenerator`)が使う実測値は次のとおり:

- ルートは`"type":"object"`必須(`oneOf`だけ・配列はAPIが400)。`oneOf`・`anyOf`・`const`・`enum`・`$defs`+`$ref`・再帰する`$ref`・深さ10のネスト・`additionalProperties:false`・`required`・`minimum/maximum`・`minLength/maxLength`・`pattern`・`minItems/maxItems`・`type`の配列は、すべて受理され、CLIの検証器が違反を実際に拒否した。
- 上限は、スキーマ単体でなくコマンドライン全体で決まる(スキーマ中の`"`は`\"`になって1文字増える)。**安全上限はコンパクトJSONで、`claude.exe`直接起動が20,000文字(ハード32,766)、`cmd.exe`経由が5,000文字(ハード8,118)**。`enum`は「個数×(IDの文字数+5)」で、直接起動は9文字IDで1,000個まで実測OK、`cmd.exe`経由は4,000文字以内が安全。
- 失敗の形: モデルが従えないとき、終了コード0・`is_error:false`のまま`structured_output`のキー自体が無い。検証を通っても、モデルは値を捏造・空にして通すことがあるので、意味の検証は`PlanPatcher`・`PlanCompiler`が行う。
- 未確認(調査文書の6節): 50分岐を超えたときの検証エラー文の長さとモデルの選択精度。P7の評価で測る。

---

### Task 2: 基本型(向き・座標・箱・回転・`BuildFrame`・`BlockSpec`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/model/{Facing,Dir6,IntPos,LocalPos,Box,Rot,BuildFrame,BlockSpec,BlockRotation}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/model/{FacingTest,BoxTest,RotTest,BuildFrameTest,BlockSpecTest,BlockRotationTest}.java`

**Interfaces:**
- Produces(以降のタスクが使う。名前と型はこのとおり):
  - `enum Facing {NORTH,EAST,SOUTH,WEST}`: `int quarterTurns()`、`Facing rotate(int q)`、`Facing opposite()`、`int du()`、`int dw()`、`String lower()`、`static Facing parse(String)`
  - `enum Dir6 {UP,DOWN,NORTH,EAST,SOUTH,WEST}`: `String lower()`、`static Dir6 parse(String)`
  - `record IntPos(int x,int y,int z)`: `IntPos plus(int dx,int dy,int dz)`
  - `record LocalPos(int u,int v,int w)`: `LocalPos plus(int du,int dv,int dw)`
  - `record Box(int minA,int minB,int minC,int maxA,int maxB,int maxC)`: `static Box of(...)`(最小・最大を正規化)、`boolean contains(int a,int b,int c)`、`long volume()`
  - `record Rot(int quarterTurns, boolean mirror)`: `Rot.NONE`、`LocalPos apply(LocalPos)`、`Facing apply(Facing)`、`static Rot compose(Rot outer, Rot inner)`(innerを先に適用)
  - `record BuildFrame(IntPos origin, Facing facing)`: `IntPos toWorld(LocalPos)`、`LocalPos toLocal(IntPos)`、`Facing toWorldFacing(Facing local)`、`Facing toLocalFacing(Facing world)`
  - `record BlockSpec(String blockId, SortedMap<String,String> properties)`: `BlockSpec.AIR`、`static BlockSpec of(String id, String... keyValuePairs)`、`BlockSpec with(String k,String v)`、`String get(String k)`、`toString()`(`id[k=v,...]`)
  - `final class BlockRotation`: `static BlockSpec rotate(BlockSpec, int quarterTurns)`、`static BlockSpec mirrorU(BlockSpec)`、`static BlockSpec transform(BlockSpec, Rot)`(鏡像→回転の順)

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/io/github/khayashi4337/micradrone/build/model/FacingTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FacingTest {
    @Test
    void rotatesClockwiseSeenFromAbove() {
        assertEquals(Facing.EAST, Facing.NORTH.rotate(1));
        assertEquals(Facing.SOUTH, Facing.NORTH.rotate(2));
        assertEquals(Facing.WEST, Facing.NORTH.rotate(3));
        assertEquals(Facing.NORTH, Facing.WEST.rotate(1));
        assertEquals(Facing.WEST, Facing.NORTH.rotate(-1));
        assertEquals(Facing.NORTH, Facing.NORTH.rotate(4));
    }

    @Test
    void oppositeAndSteps() {
        assertEquals(Facing.SOUTH, Facing.NORTH.opposite());
        assertEquals(Facing.WEST, Facing.EAST.opposite());
        // local frame: NORTH = +w, EAST = +u
        assertEquals(0, Facing.NORTH.du());
        assertEquals(1, Facing.NORTH.dw());
        assertEquals(1, Facing.EAST.du());
        assertEquals(0, Facing.EAST.dw());
        assertEquals(-1, Facing.SOUTH.dw());
        assertEquals(-1, Facing.WEST.du());
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Facing.EAST, Facing.parse("east"));
        assertEquals(Facing.EAST, Facing.parse(" EAST "));
        assertEquals("east", Facing.EAST.lower());
        assertThrows(IllegalArgumentException.class, () -> Facing.parse("up"));
    }

    @Test
    void dir6Parse() {
        assertEquals(Dir6.UP, Dir6.parse("up"));
        assertEquals("north", Dir6.NORTH.lower());
        assertThrows(IllegalArgumentException.class, () -> Dir6.parse("sideways"));
    }
}
```

`BoxTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxTest {
    @Test
    void containsIsInclusiveOnBothEnds() {
        Box b = new Box(0, 0, 0, 2, 3, 4);
        assertTrue(b.contains(0, 0, 0));
        assertTrue(b.contains(2, 3, 4));
        assertFalse(b.contains(3, 0, 0));
        assertFalse(b.contains(0, -1, 0));
        assertEquals(3L * 4L * 5L, b.volume());
    }

    @Test
    void constructorRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Box(1, 0, 0, 0, 0, 0));
    }

    @Test
    void ofNormalizesTheCorners() {
        assertEquals(new Box(0, 1, 2, 3, 4, 5), Box.of(3, 4, 5, 0, 1, 2));
    }
}
```

`RotTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RotTest {
    private static final List<LocalPos> SAMPLES = List.of(
            new LocalPos(0, 0, 0), new LocalPos(1, 2, 3), new LocalPos(-4, 1, 5), new LocalPos(3, -2, -7));

    @Test
    void oneQuarterTurnMapsNorthToEast() {
        // rotation of (u,w) is (w,-u): NORTH (0,+1) -> EAST (+1,0)
        assertEquals(new LocalPos(1, 0, 0), new Rot(1, false).apply(new LocalPos(0, 0, 1)));
        assertEquals(new LocalPos(0, 0, -1), new Rot(1, false).apply(new LocalPos(1, 0, 0)));
        assertEquals(Facing.EAST, new Rot(1, false).apply(Facing.NORTH));
    }

    @Test
    void mirrorFlipsUAndSwapsEastWest() {
        assertEquals(new LocalPos(-3, 2, 4), new Rot(0, true).apply(new LocalPos(3, 2, 4)));
        assertEquals(Facing.WEST, new Rot(0, true).apply(Facing.EAST));
        assertEquals(Facing.NORTH, new Rot(0, true).apply(Facing.NORTH));
    }

    @Test
    void quarterTurnsAreNormalized() {
        assertEquals(new Rot(0, false), new Rot(4, false));
        assertEquals(new Rot(3, true), new Rot(-1, true));
    }

    @Test
    void composeMeansInnerFirstThenOuter() {
        for (int qo = 0; qo < 4; qo++) {
            for (int qi = 0; qi < 4; qi++) {
                for (boolean mo : new boolean[]{false, true}) {
                    for (boolean mi : new boolean[]{false, true}) {
                        Rot outer = new Rot(qo, mo);
                        Rot inner = new Rot(qi, mi);
                        Rot composed = Rot.compose(outer, inner);
                        for (LocalPos p : SAMPLES) {
                            assertEquals(outer.apply(inner.apply(p)), composed.apply(p),
                                    "compose " + outer + " after " + inner + " on " + p);
                        }
                        for (Facing f : Facing.values()) {
                            assertEquals(outer.apply(inner.apply(f)), composed.apply(f));
                        }
                    }
                }
            }
        }
    }
}
```

`BuildFrameTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildFrameTest {
    @Test
    void northFrameIsIdentityWithZFlipped() {
        BuildFrame f = new BuildFrame(new IntPos(10, 64, 20), Facing.NORTH);
        // forward (+w) is Minecraft north (-z); right (+u) is east (+x)
        assertEquals(new IntPos(12, 67, 16), f.toWorld(new LocalPos(2, 3, 4)));
    }

    @Test
    void eastFrameForwardIsEastAndRightIsSouth() {
        BuildFrame f = new BuildFrame(new IntPos(0, 0, 0), Facing.EAST);
        assertEquals(new IntPos(1, 0, 0), f.toWorld(new LocalPos(0, 0, 1)));
        assertEquals(new IntPos(0, 0, 1), f.toWorld(new LocalPos(1, 0, 0)));
    }

    @Test
    void southAndWestFrames() {
        assertEquals(new IntPos(0, 0, 1), new BuildFrame(new IntPos(0, 0, 0), Facing.SOUTH).toWorld(new LocalPos(0, 0, 1)));
        assertEquals(new IntPos(-1, 0, 0), new BuildFrame(new IntPos(0, 0, 0), Facing.WEST).toWorld(new LocalPos(0, 0, 1)));
        // right of south-facing is west
        assertEquals(new IntPos(-1, 0, 0), new BuildFrame(new IntPos(0, 0, 0), Facing.SOUTH).toWorld(new LocalPos(1, 0, 0)));
    }

    @Test
    void toLocalInvertsToWorldForEveryFacing() {
        for (Facing facing : Facing.values()) {
            BuildFrame f = new BuildFrame(new IntPos(-7, 70, 33), facing);
            for (int u = -3; u <= 3; u++) {
                for (int v = -2; v <= 2; v++) {
                    for (int w = -3; w <= 3; w++) {
                        LocalPos p = new LocalPos(u, v, w);
                        assertEquals(p, f.toLocal(f.toWorld(p)), facing + " " + p);
                    }
                }
            }
        }
    }

    @Test
    void localFacingBecomesWorldFacingByTheFrameTurns() {
        BuildFrame east = new BuildFrame(new IntPos(0, 0, 0), Facing.EAST);
        assertEquals(Facing.EAST, east.toWorldFacing(Facing.NORTH));
        assertEquals(Facing.SOUTH, east.toWorldFacing(Facing.EAST));
        assertEquals(Facing.NORTH, east.toLocalFacing(Facing.EAST));
    }
}
```

`BlockSpecTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockSpecTest {
    @Test
    void propertiesAreSortedAndEqualityIgnoresInsertionOrder() {
        BlockSpec a = BlockSpec.of("minecraft:oak_stairs", "half", "bottom", "facing", "north");
        BlockSpec b = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", a.toString());
    }

    @Test
    void withReturnsACopy() {
        BlockSpec a = BlockSpec.of("minecraft:stone");
        BlockSpec b = a.with("k", "v");
        assertEquals(0, a.properties().size());
        assertEquals("v", b.get("k"));
        assertNotEquals(a, b);
        assertEquals("minecraft:stone", a.toString());
    }

    @Test
    void oddKeyValueCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.of("minecraft:stone", "only-key"));
    }

    @Test
    void propertiesAreImmutable() {
        BlockSpec a = BlockSpec.of("minecraft:stone", "k", "v");
        assertThrows(UnsupportedOperationException.class, () -> a.properties().put("x", "y"));
        assertEquals("minecraft:air", BlockSpec.AIR.blockId());
    }
}
```

`BlockRotationTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class BlockRotationTest {
    @Test
    void facingRotatesClockwiseAndUpDownStay() {
        BlockSpec stairs = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertEquals(BlockSpec.of("minecraft:oak_stairs", "facing", "east", "half", "bottom"), BlockRotation.rotate(stairs, 1));
        assertEquals(BlockSpec.of("minecraft:oak_stairs", "facing", "south", "half", "bottom"), BlockRotation.rotate(stairs, 2));
        BlockSpec barrel = BlockSpec.of("minecraft:barrel", "facing", "up");
        assertEquals(barrel, BlockRotation.rotate(barrel, 3));
    }

    @Test
    void axisSwapsXAndZOnOddTurnsOnly() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "x");
        assertEquals("z", BlockRotation.rotate(log, 1).get("axis"));
        assertEquals("x", BlockRotation.rotate(log, 2).get("axis"));
        assertEquals("y", BlockRotation.rotate(BlockSpec.of("minecraft:oak_log", "axis", "y"), 1).get("axis"));
    }

    @Test
    void sideKeysAndRotationProperty() {
        BlockSpec fence = BlockSpec.of("minecraft:oak_fence", "north", "true", "east", "false");
        BlockSpec turned = BlockRotation.rotate(fence, 1);
        assertEquals("true", turned.get("east"));   // north -> east
        assertEquals("false", turned.get("south")); // east -> south
        BlockSpec sign = BlockSpec.of("minecraft:oak_sign", "rotation", "14");
        assertEquals("2", BlockRotation.rotate(sign, 1).get("rotation")); // +4 mod 16
    }

    @Test
    void zeroTurnsReturnsTheSameInstance() {
        BlockSpec s = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        assertSame(s, BlockRotation.rotate(s, 0));
        assertSame(s, BlockRotation.rotate(s, 4));
    }

    @Test
    void mirrorFlipsEastWestFacingHingeAndStairShape() {
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "facing", "east", "half", "lower", "hinge", "left");
        BlockSpec m = BlockRotation.mirrorU(door);
        assertEquals("west", m.get("facing"));
        assertEquals("right", m.get("hinge"));
        BlockSpec stairs = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "shape", "inner_left");
        assertEquals("inner_right", BlockRotation.mirrorU(stairs).get("shape"));
        assertEquals("north", BlockRotation.mirrorU(stairs).get("facing"));
    }

    @Test
    void transformMirrorsFirstThenRotates() {
        BlockSpec s = BlockSpec.of("minecraft:oak_stairs", "facing", "east");
        // mirror: east -> west, then one quarter turn: west -> north
        assertEquals("north", BlockRotation.transform(s, new Rot(1, true)).get("facing"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.*" --console=plain`
Expected: FAIL(`cannot find symbol`: `Facing`等。クラスがまだ無いため)。

- [ ] **Step 3: 最小の実装を書く**

`Facing.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/**
 * Horizontal direction in the local build frame: NORTH is +w (forward), EAST is +u (right), SOUTH is -w,
 * WEST is -u. Clockwise turns are counted seen from above, matching {@link BuildFrame}.
 */
public enum Facing {
    NORTH, EAST, SOUTH, WEST;

    public int quarterTurns() {
        return ordinal();
    }

    public Facing rotate(int quarterTurns) {
        return values()[Math.floorMod(ordinal() + quarterTurns, 4)];
    }

    public Facing opposite() {
        return rotate(2);
    }

    /** Unit step along u (EAST is +1, WEST is -1). */
    public int du() {
        return switch (this) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    /** Unit step along w (NORTH is +1, SOUTH is -1). */
    public int dw() {
        return switch (this) {
            case NORTH -> 1;
            case SOUTH -> -1;
            default -> 0;
        };
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Facing parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
```

`Dir6.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/** Six-way direction, used for port facings and routing entry directions. */
public enum Dir6 {
    UP, DOWN, NORTH, EAST, SOUTH, WEST;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Dir6 parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
```

`IntPos.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/** A world position without any Minecraft type (the construction adapters convert to BlockPos). */
public record IntPos(int x, int y, int z) {
    public IntPos plus(int dx, int dy, int dz) {
        return new IntPos(x + dx, y + dy, z + dz);
    }
}
```

`LocalPos.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/** A position in a {@link BuildFrame}: u is right, v is up, w is forward. */
public record LocalPos(int u, int v, int w) {
    public LocalPos plus(int du, int dv, int dw) {
        return new LocalPos(u + du, v + dv, w + dw);
    }
}
```

`Box.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/** An axis-aligned box, inclusive on both ends. Used for local (u,v,w) and world (x,y,z) alike. */
public record Box(int minA, int minB, int minC, int maxA, int maxB, int maxC) {
    public Box {
        if (minA > maxA || minB > maxB || minC > maxC) {
            throw new IllegalArgumentException("box min must not exceed max: " + minA + "," + minB + "," + minC
                    + " .. " + maxA + "," + maxB + "," + maxC);
        }
    }

    /** Builds a box from any two opposite corners. */
    public static Box of(int a0, int b0, int c0, int a1, int b1, int c1) {
        return new Box(Math.min(a0, a1), Math.min(b0, b1), Math.min(c0, c1),
                Math.max(a0, a1), Math.max(b0, b1), Math.max(c0, c1));
    }

    public boolean contains(int a, int b, int c) {
        return a >= minA && a <= maxA && b >= minB && b <= maxB && c >= minC && c <= maxC;
    }

    public long volume() {
        return (long) (maxA - minA + 1) * (maxB - minB + 1) * (maxC - minC + 1);
    }
}
```

`Rot.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/**
 * A rotation and optional mirror in the local frame. The mirror (u to -u, so EAST and WEST swap) is applied
 * first, then {@code quarterTurns} clockwise turns seen from above; one turn maps (u,w) to (w,-u).
 */
public record Rot(int quarterTurns, boolean mirror) {
    public static final Rot NONE = new Rot(0, false);

    public Rot {
        quarterTurns = Math.floorMod(quarterTurns, 4);
    }

    public LocalPos apply(LocalPos p) {
        int u = mirror ? -p.u() : p.u();
        int w = p.w();
        for (int i = 0; i < quarterTurns; i++) {
            int nextU = w;
            int nextW = -u;
            u = nextU;
            w = nextW;
        }
        return new LocalPos(u, p.v(), w);
    }

    public Facing apply(Facing facing) {
        Facing mirrored = facing;
        if (mirror) {
            mirrored = facing == Facing.EAST ? Facing.WEST : facing == Facing.WEST ? Facing.EAST : facing;
        }
        return mirrored.rotate(quarterTurns);
    }

    /**
     * The transform "apply {@code inner} first, then {@code outer}". A mirror reverses the direction of any
     * rotation that follows it, hence the sign flip.
     */
    public static Rot compose(Rot outer, Rot inner) {
        int turns = outer.quarterTurns + (outer.mirror ? -inner.quarterTurns : inner.quarterTurns);
        return new Rot(turns, outer.mirror ^ inner.mirror);
    }
}
```

`BuildFrame.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/**
 * Maps the local frame (u right, v up, w forward) to the world. With {@code facing == NORTH} the world offset
 * is (u, v, -w) (Minecraft's north is -z); the other facings turn the horizontal part clockwise by the facing's
 * quarter-turn count. Plans are written in local coordinates, so rotating a plan is only a change of facing.
 */
public record BuildFrame(IntPos origin, Facing facing) {
    public IntPos toWorld(LocalPos p) {
        int dx = p.u();
        int dz = -p.w();
        for (int i = 0; i < facing.quarterTurns(); i++) {
            int nextDx = -dz;
            int nextDz = dx;
            dx = nextDx;
            dz = nextDz;
        }
        return new IntPos(origin.x() + dx, origin.y() + p.v(), origin.z() + dz);
    }

    public LocalPos toLocal(IntPos p) {
        int dx = p.x() - origin.x();
        int dz = p.z() - origin.z();
        for (int i = 0; i < facing.quarterTurns(); i++) {
            int nextDx = dz;
            int nextDz = -dx;
            dx = nextDx;
            dz = nextDz;
        }
        return new LocalPos(dx, p.y() - origin.y(), -dz);
    }

    public Facing toWorldFacing(Facing local) {
        return local.rotate(facing.quarterTurns());
    }

    public Facing toLocalFacing(Facing world) {
        return world.rotate(-facing.quarterTurns());
    }
}
```

`BlockSpec.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** A block id plus block-state properties, as plain strings (no Minecraft types). Properties are kept sorted. */
public record BlockSpec(String blockId, SortedMap<String, String> properties) {
    public static final BlockSpec AIR = new BlockSpec("minecraft:air", new TreeMap<>());

    public BlockSpec {
        Objects.requireNonNull(blockId, "blockId");
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties == null ? Map.of() : properties));
    }

    /** {@code of("minecraft:oak_stairs", "facing", "north", "half", "bottom")}. */
    public static BlockSpec of(String blockId, String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("properties must come as key/value pairs");
        }
        TreeMap<String, String> props = new TreeMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new BlockSpec(blockId, props);
    }

    public BlockSpec with(String key, String value) {
        TreeMap<String, String> copy = new TreeMap<>(properties);
        copy.put(key, value);
        return new BlockSpec(blockId, copy);
    }

    public String get(String key) {
        return properties.get(key);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return blockId;
        }
        StringBuilder sb = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }
}
```

`BlockRotation.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rotates and mirrors block-state properties along with the positions they belong to, so a plan compiled
 * in the local frame stays consistent once mapped to the world. Local semantics: {@code axis} x runs along u
 * and z along w; {@code facing} values use {@link Facing}'s local meaning.
 */
public final class BlockRotation {
    private static final int ROTATION_STEPS_PER_QUARTER_TURN = 4; // signs use 16 rotation steps per full turn
    private static final int ROTATION_STEPS = 16;

    private BlockRotation() {
    }

    public static BlockSpec rotate(BlockSpec spec, int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0 || spec.properties().isEmpty()) {
            return spec;
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case "facing" -> out.put(key, rotateDirection(value, q));
                case "axis" -> out.put(key, q % 2 == 1 ? swapXZ(value) : value);
                case "rotation" -> out.put(key, String.valueOf(
                        Math.floorMod(Integer.parseInt(value) + ROTATION_STEPS_PER_QUARTER_TURN * q, ROTATION_STEPS)));
                case "north", "east", "south", "west" -> {
                    // handled below so that keys move together
                }
                default -> out.put(key, value);
            }
        }
        for (Facing f : Facing.values()) {
            String value = spec.properties().get(f.lower());
            if (value != null) {
                out.put(f.rotate(q).lower(), value);
            }
        }
        return new BlockSpec(spec.blockId(), out);
    }

    /** Mirrors u to -u: EAST and WEST swap; door hinges and stair corner shapes swap left and right. */
    public static BlockSpec mirrorU(BlockSpec spec) {
        if (spec.properties().isEmpty()) {
            return spec;
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case "facing" -> out.put(key, mirrorDirection(value));
                case "hinge" -> out.put(key, swapLeftRight(value));
                case "shape" -> out.put(key, swapLeftRight(value));
                case "east", "west" -> {
                    // handled below
                }
                case "rotation" -> out.put(key, String.valueOf(Math.floorMod(-Integer.parseInt(value), ROTATION_STEPS)));
                default -> out.put(key, value);
            }
        }
        String east = spec.properties().get("east");
        String west = spec.properties().get("west");
        if (east != null) {
            out.put("west", east);
        }
        if (west != null) {
            out.put("east", west);
        }
        return new BlockSpec(spec.blockId(), out);
    }

    /** Applies {@link Rot}: mirror first, then the quarter turns. */
    public static BlockSpec transform(BlockSpec spec, Rot rot) {
        BlockSpec mirrored = rot.mirror() ? mirrorU(spec) : spec;
        return rotate(mirrored, rot.quarterTurns());
    }

    private static String rotateDirection(String value, int q) {
        return switch (value) {
            case "north", "east", "south", "west" -> Facing.parse(value).rotate(q).lower();
            default -> value; // up / down
        };
    }

    private static String mirrorDirection(String value) {
        return switch (value) {
            case "east" -> "west";
            case "west" -> "east";
            default -> value;
        };
    }

    private static String swapXZ(String axis) {
        return switch (axis) {
            case "x" -> "z";
            case "z" -> "x";
            default -> axis;
        };
    }

    private static String swapLeftRight(String value) {
        if (value.endsWith("_left")) {
            return value.substring(0, value.length() - "_left".length()) + "_right";
        }
        if (value.endsWith("_right")) {
            return value.substring(0, value.length() - "_right".length()) + "_left";
        }
        return switch (value) {
            case "left" -> "right";
            case "right" -> "left";
            default -> value;
        };
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.*" --console=plain`
Expected: PASS(全テスト)。`compose`の全組み合わせの検査も緑。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build/model src/test/java/io/github/khayashi4337/micradrone/build/model
git commit -m "$(cat <<'EOF'
feat: 建設の核の基本型(向き・座標・箱・回転・BuildFrame・BlockSpec)を追加(自然言語→工場建設 P3 Task 2)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 正規JSONとハッシュ(`MiniJson`の公開、`CanonicalJson`、`Hashing`)

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/chat/MiniJson.java`(クラス・`write`・`parse`を`public`にする。挙動は変えない)
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/model/{CanonicalJson,Hashing}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/model/{CanonicalJsonTest,HashingTest}.java`

**Interfaces:**
- Consumes: `chat.MiniJson.parse(String)`(公開にする。数は`Double`、`{}`は`LinkedHashMap<String,Object>`、`[]`は`ArrayList<Object>`)
- Produces:
  - `CanonicalJson.write(Object tree) → String`: `Map`(キーは`String`。辞書順)、`List`(順序を保つ)、`Set`(要素の正規形の辞書順)、`String`、`Boolean`、整数型(`Integer`/`Long`/`Short`/`Byte`)、`Double`/`Float`(有限のみ)、`BigDecimal`、`null`。それ以外・`String`以外のキー・非有限の小数は`IllegalArgumentException`
  - `Hashing.sha256Hex(String utf8Text) → String`(小文字16進64文字)

- [ ] **Step 1: 失敗するテストを書く**

`CanonicalJsonTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void keysAreSortedAndNoWhitespace() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1);
        m.put("a", List.of(2L, "x"));
        assertEquals("{\"a\":[2,\"x\"],\"b\":1}", CanonicalJson.write(m));
    }

    @Test
    void numbersAreIntegersOrPlainDecimals() {
        assertEquals("2", CanonicalJson.write(2.0));
        assertEquals("2.5", CanonicalJson.write(2.5));
        assertEquals("0.1", CanonicalJson.write(0.1));
        assertEquals("0", CanonicalJson.write(-0.0));
        assertEquals("1000", CanonicalJson.write(1000.0));
        assertEquals("-7", CanonicalJson.write(-7));
        assertEquals("12.5", CanonicalJson.write(new BigDecimal("12.500")));
        assertEquals("3", CanonicalJson.write(3L));
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void stringsAreEscapedAndUnicodeIsKept() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\"", CanonicalJson.write("a\"b\\c\n\t"));
        assertEquals("\"\\u0001\"", CanonicalJson.write("\u0001"));
        assertEquals("\"屋根🏠\"", CanonicalJson.write("屋根🏠"));
    }

    @Test
    void setsAreOrderedByTheirCanonicalForm() {
        Set<String> s = new TreeSet<>(List.of("b", "a"));
        assertEquals("[\"a\",\"b\"]", CanonicalJson.write(s));
        assertEquals("[\"a\",\"b\"]", CanonicalJson.write(Set.of("b", "a")));
    }

    @Test
    void nullBooleanAndNesting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", null);
        m.put("t", true);
        m.put("o", Map.of("z", 1, "y", 2));
        assertEquals("{\"n\":null,\"o\":{\"y\":2,\"z\":1},\"t\":true}", CanonicalJson.write(m));
    }

    @Test
    void unsupportedTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new Object()));
        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(badKey));
    }

    @Test
    void miniJsonParsedTreesCanBeWrittenAndAreOrderIndependent() {
        Object a = MiniJson.parse("{\"b\":1,\"a\":{\"d\":2.5,\"c\":[1,2]}}");
        Object b = MiniJson.parse("{ \"a\" : {\"c\":[1,2], \"d\":2.5}, \"b\":1 }");
        assertEquals(CanonicalJson.write(a), CanonicalJson.write(b));
        assertEquals("{\"a\":{\"c\":[1,2],\"d\":2.5},\"b\":1}", CanonicalJson.write(a));
    }
}
```

`HashingTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void knownSha256Vectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""));
    }

    @Test
    void utf8IsUsedForNonAscii() {
        assertEquals(64, Hashing.sha256Hex("屋根").length());
        assertEquals(Hashing.sha256Hex("屋根"), Hashing.sha256Hex("屋根"));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.CanonicalJsonTest" --tests "io.github.khayashi4337.micradrone.build.model.HashingTest" --console=plain`
Expected: FAIL(`MiniJson`が`chat`パッケージ限定で`build.model`から見えない、`CanonicalJson`が無い)。

- [ ] **Step 3: 実装する**

`chat/MiniJson.java`の宣言3か所を公開にする(15行目付近・24行目付近・93行目付近。他は変えない):
```java
public final class MiniJson {           // was: final class MiniJson
    ...
    public static String write(Object value) {     // was: static String write(Object value)
    ...
    public static Object parse(String json) {      // was: static Object parse(String json)
```
クラスのjavadocの末尾に1文を足す: `Public so the Minecraft-free build.* core can read and write JSON without a second parser.`

`CanonicalJson.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The canonical JSON form used for hashing: keys in dictionary order, integers as integers, other numbers as
 * plain decimals without trailing zeros, no whitespace, sets in the dictionary order of their elements' own
 * canonical form. Two structurally equal trees always produce the same bytes.
 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    public static String write(Object tree) {
        StringBuilder sb = new StringBuilder();
        writeValue(tree, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof BigDecimal bd) {
            sb.append(plain(bd));
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("non-finite number cannot be written canonically: " + d);
            }
            sb.append(plain(BigDecimal.valueOf(d)));
        } else if (value instanceof Map<?, ?> map) {
            writeMap(map, sb);
        } else if (value instanceof Set<?> set) {
            List<String> parts = new ArrayList<>();
            for (Object item : set) {
                StringBuilder one = new StringBuilder();
                writeValue(item, one);
                parts.add(one.toString());
            }
            parts.sort(null);
            sb.append('[').append(String.join(",", parts)).append(']');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize value of type " + value.getClass().getName());
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings but was " + e.getKey());
            }
            sorted.put(key, e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(e.getKey(), sb);
            sb.append(':');
            writeValue(e.getValue(), sb);
        }
        sb.append('}');
    }

    /** toPlainString never uses an exponent, and stripTrailingZeros makes 2.0 and 2 the same text. */
    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return stripped.toPlainString();
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
```

`Hashing.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 over the UTF-8 bytes of a text, as lowercase hex. */
public final class Hashing {
    private Hashing() {
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
```

- [ ] **Step 4: テストが通り、既存の`chat`のテストが壊れていないことを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.*" --tests "io.github.khayashi4337.micradrone.chat.*" --console=plain`
Expected: PASS(`MiniJsonTest`等の既存テストも緑)。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/chat/MiniJson.java src/main/java/io/github/khayashi4337/micradrone/build/model src/test/java/io/github/khayashi4337/micradrone/build/model
git commit -m "$(cat <<'EOF'
feat: 正規JSON(CanonicalJson)とSHA-256(Hashing)を追加し、MiniJsonを公開にする(自然言語→工場建設 P3 Task 3)

MiniJsonはchatパッケージ限定でbuild.*から使えなかった。挙動は変えず、可視性だけ公開にする。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 問題(`Issue`・`IssueCode`・`Severity`・`FixHint`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/model/{Severity,IssueCode,FixHint,Issue}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/model/IssueTest.java`

**Interfaces:**
- Produces:
  - `enum Severity {ERROR,WARN,INFO}`
  - `enum IssueCode`: 設計図05の4.1節の全47コード(`E_SCHEMA("E-SCHEMA")`のように、列挙名は`-`を`_`にした物)。`String label()`、`Severity severity()`(ラベルの先頭が`E`→ERROR、`W`→WARN)、`boolean acceptable()`(`W-*`と`E-CLOG-RISK`だけtrue)、`static Optional<IssueCode> fromLabel(String)`
  - `record FixHint(String kind, Map<String,String> args)`(引数は辞書順に複製)
  - `record Issue(String id, IssueCode code, Severity severity, boolean acceptable, List<String> subjects, String message, Map<String,String> data, List<FixHint> hints)`: `static Issue of(IssueCode code, String key, List<String> subjects, String message, Map<String,String> data, List<FixHint> hints)`、`static Issue of(IssueCode code, List<String> subjects, String message)`。`id`=`<ラベル>:<対象をカンマでつないだ物>`に、`key`が空でなければ`#<key>`を付ける。`boolean isError()`

- [ ] **Step 1: 失敗するテストを書く**

`IssueTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IssueTest {
    @Test
    void severityComesFromTheLabelPrefix() {
        assertEquals(Severity.ERROR, IssueCode.E_PARAM_RANGE.severity());
        assertEquals(Severity.WARN, IssueCode.W_UNMODELED.severity());
        assertEquals("E-PARAM-RANGE", IssueCode.E_PARAM_RANGE.label());
    }

    @Test
    void onlyWarningsAndClogRiskAreAcceptable() {
        for (IssueCode code : IssueCode.values()) {
            boolean expected = code.label().startsWith("W-") || code == IssueCode.E_CLOG_RISK;
            assertEquals(expected, code.acceptable(), code.label());
        }
        assertFalse(IssueCode.E_BLOCK_FORBIDDEN.acceptable());
        assertTrue(IssueCode.E_CLOG_RISK.acceptable());
    }

    @Test
    void fromLabelRoundTripsEveryCode() {
        for (IssueCode code : IssueCode.values()) {
            assertEquals(code, IssueCode.fromLabel(code.label()).orElseThrow());
        }
        assertTrue(IssueCode.fromLabel("E-NOPE").isEmpty());
    }

    @Test
    void issueIdCombinesCodeSubjectsAndKey() {
        Issue a = Issue.of(IssueCode.E_STRESS_OVER, List.of("net-3"), "over");
        assertEquals("E-STRESS-OVER:net-3", a.id());
        Issue b = Issue.of(IssueCode.E_PARAM_RANGE, "height", List.of("wall-1"), "too high", Map.of("max", "16"), List.of());
        assertEquals("E-PARAM-RANGE:wall-1#height", b.id());
        assertEquals("16", b.data().get("max"));
        assertTrue(b.isError());
        assertFalse(Issue.of(IssueCode.W_UNMODELED, List.of("x"), "m").isError());
        assertEquals("E-SCHEMA:", Issue.of(IssueCode.E_SCHEMA, List.of(), "m").id());
    }

    @Test
    void collectionsAreCopiedAndImmutable() {
        Issue i = Issue.of(IssueCode.E_ANCHOR, "", List.of("a"), "m", Map.of("k", "v"),
                List.of(new FixHint("USE", Map.of("x", "1"))));
        assertThrows(UnsupportedOperationException.class, () -> i.subjects().add("b"));
        assertThrows(UnsupportedOperationException.class, () -> i.data().put("z", "z"));
        assertThrows(UnsupportedOperationException.class, () -> i.hints().clear());
        assertEquals("1", i.hints().get(0).args().get("x"));
    }

    /** The enum and the design document's table (05, section 4.1) must list exactly the same codes. */
    @Test
    void enumMatchesTheDesignDocumentTable() throws IOException {
        Path doc = Path.of("docs/design/nl_factory_builder/05_parts_and_analyzers.md");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        int start = text.indexOf("### 4.1 `IssueCode`");
        int end = text.indexOf("### 4.2", start);
        String section = text.substring(start, end);
        Set<String> documented = new HashSet<>();
        Pattern row = Pattern.compile("^\\| ((?:`[EW]-[A-Z-]+`/?)+) \\|", Pattern.MULTILINE);
        Matcher m = row.matcher(section);
        while (m.find()) {
            Matcher token = Pattern.compile("`([EW]-[A-Z-]+)`").matcher(m.group(1));
            while (token.find()) {
                documented.add(token.group(1));
            }
        }
        Set<String> coded = new HashSet<>();
        for (IssueCode code : IssueCode.values()) {
            coded.add(code.label());
        }
        assertEquals(documented, coded);
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.IssueTest" --console=plain`
Expected: FAIL(`cannot find symbol: IssueCode`)。

- [ ] **Step 3: 実装する**

`Severity.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

public enum Severity { ERROR, WARN, INFO }
```

`IssueCode.java`(設計図05 4.1節の全コード。列挙名は`-`を`_`にする):
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Optional;

/**
 * The common vocabulary of problems across every loop (design doc 05, section 4.1). Adding a code means
 * adding it to that table too; a test keeps the two in step. Only warnings and E-CLOG-RISK may be accepted
 * by the user (01, section 5).
 */
public enum IssueCode {
    E_SCHEMA("E-SCHEMA"),
    E_UNKNOWN_PART("E-UNKNOWN-PART"),
    E_PARAM_RANGE("E-PARAM-RANGE"),
    E_ANCHOR("E-ANCHOR"),
    E_OVERLAP("E-OVERLAP"),
    E_OUT_OF_BOUNDS("E-OUT-OF-BOUNDS"),
    E_NOT_SUPPORTED("E-NOT-SUPPORTED"),
    E_OPENING_NO_WALL("E-OPENING-NO-WALL"),
    E_ENCLOSURE_LEAK("E-ENCLOSURE-LEAK"),
    E_PORT_UNCONNECTED("E-PORT-UNCONNECTED"),
    E_PORT_MISMATCH("E-PORT-MISMATCH"),
    E_NO_ROUTE("E-NO-ROUTE"),
    E_ROT_CONFLICT("E-ROT-CONFLICT"),
    E_STRESS_OVER("E-STRESS-OVER"),
    E_POWER_NONE("E-POWER-NONE"),
    E_ITEM_DEADEND("E-ITEM-DEADEND"),
    E_CLOG_RISK("E-CLOG-RISK"),
    E_FLUID_LEAK("E-FLUID-LEAK"),
    E_SPACE_SHORT("E-SPACE-SHORT"),
    E_REGISTRY_VERSION("E-REGISTRY-VERSION"),
    E_SITE_BLOCKED("E-SITE-BLOCKED"),
    E_MATERIAL_SHORT("E-MATERIAL-SHORT"),
    E_MATERIAL_UNKNOWN("E-MATERIAL-UNKNOWN"),
    W_UNMODELED("W-UNMODELED"),
    W_STRESS_MARGIN("W-STRESS-MARGIN"),
    W_OVERSIZED_POWER("W-OVERSIZED-POWER"),
    W_NO_RECIPE("W-NO-RECIPE"),
    W_DECOR_COLLIDE("W-DECOR-COLLIDE"),
    E_BLOCK_FORBIDDEN("E-BLOCK-FORBIDDEN"),
    E_HEAT_NONE("E-HEAT-NONE"),
    W_FUEL_SUPPLY("W-FUEL-SUPPLY"),
    E_DOCK_MISALIGN("E-DOCK-MISALIGN"),
    E_ASSEMBLY_FAILED("E-ASSEMBLY-FAILED"),
    E_TEMPLATE_UNVERIFIED("E-TEMPLATE-UNVERIFIED"),
    E_SITE_CHANGED("E-SITE-CHANGED"),
    E_EFFECT_ESCAPES_CLAIM("E-EFFECT-ESCAPES-CLAIM"),
    E_TERRAFORM_UNCONFIRMED("E-TERRAFORM-UNCONFIRMED"),
    W_ASSEMBLY_AWAY("W-ASSEMBLY-AWAY"),
    W_NO_SETUP("W-NO-SETUP"),
    W_DYNAMIC_PART("W-DYNAMIC-PART"),
    E_PATCH_STALE("E-PATCH-STALE"),
    E_ID_INVALID("E-ID-INVALID"),
    E_ID_DUPLICATE("E-ID-DUPLICATE"),
    E_CONN_INVALID("E-CONN-INVALID"),
    E_SITE_MISSING("E-SITE-MISSING"),
    E_SCRIPT_FORBIDDEN("E-SCRIPT-FORBIDDEN"),
    E_SCRIPT_LIMIT("E-SCRIPT-LIMIT");

    private final String label;

    IssueCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Severity severity() {
        return label.startsWith("W-") ? Severity.WARN : Severity.ERROR;
    }

    /** Whether the user may accept the risk and go on: warnings and E-CLOG-RISK only. */
    public boolean acceptable() {
        return label.startsWith("W-") || this == E_CLOG_RISK;
    }

    public static Optional<IssueCode> fromLabel(String label) {
        for (IssueCode code : values()) {
            if (code.label.equals(label)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}
```

`FixHint.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A machine-readable suggestion attached to an {@link Issue}, e.g. {@code ADD_POWER_SOURCE{need_su=512}}. */
public record FixHint(String kind, Map<String, String> args) {
    public FixHint {
        args = Collections.unmodifiableMap(new TreeMap<>(args == null ? Map.of() : args));
    }
}
```

`Issue.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A problem found by a deterministic check. It always points at design nodes or connections by their stable
 * ids. {@code acceptable} is fixed per code: whether the user may accept the risk and go on.
 */
public record Issue(String id, IssueCode code, Severity severity, boolean acceptable, List<String> subjects,
                    String message, Map<String, String> data, List<FixHint> hints) {
    public Issue {
        subjects = List.copyOf(subjects);
        data = Collections.unmodifiableMap(new TreeMap<>(data == null ? Map.of() : data));
        hints = List.copyOf(hints);
    }

    /** {@code key} tells apart several issues of one code on one subject (e.g. the parameter name); may be empty. */
    public static Issue of(IssueCode code, String key, List<String> subjects, String message,
                           Map<String, String> data, List<FixHint> hints) {
        String id = code.label() + ":" + String.join(",", subjects) + (key == null || key.isEmpty() ? "" : "#" + key);
        return new Issue(id, code, code.severity(), code.acceptable(), subjects, message, data, hints);
    }

    public static Issue of(IssueCode code, List<String> subjects, String message) {
        return of(code, "", subjects, message, Map.of(), List.of());
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.IssueTest" --console=plain`
Expected: PASS(設計図の表とのコード一覧の一致も緑)。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build/model src/test/java/io/github/khayashi4337/micradrone/build/model
git commit -m "$(cat <<'EOF'
feat: 問題の共通語(Issue・IssueCode)を追加し、設計図の表との一致をテストで守る(自然言語→工場建設 P3 Task 4)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 5: 設計データ(`SemanticPlan`・`PlanNode`・`Anchor`・`Connection`・`PlanPatch`)と、そのJSON変換(`PlanJson`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/model/{ParamValue,Provenance,Side,StyleSpec,Site,Anchor,ConnKind,PortRef,Routing,Constraints,Connection,PlanNode,LogisticsPlan,SemanticPlan,PlanOp,PlanPatch,PlanJsonException,JsonTree,PlanJson}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/model/PlanJsonTest.java`

**Interfaces:**
- Consumes: Task 2の`LocalPos`・`Box`・`Rot`・`BuildFrame`・`IntPos`・`Facing`・`Dir6`、Task 3の`CanonicalJson`・`Hashing`・`MiniJson`
- Produces(以降のタスクが使う):
  - `sealed interface ParamValue`: `IntV(int)`・`NumV(double)`・`BoolV(boolean)`・`StrV(String)`・`EnumV(String)`・`MaterialV(String)`・`ListV(List<ParamValue>)`、`Object toTree()`、`static ParamValue fromTree(Object)`(型を推測して読む。整数値の`double`は`IntV`、`int`の範囲外の整数は`NumV`。非有限・`Map`・`null`は`IllegalArgumentException`)
  - `record Provenance(String stageId, String modelId, String promptHash, List<String> imageIds, long createdAtMillis)`、`Provenance.NONE`
  - `enum Side {NORTH,EAST,SOUTH,WEST,TOP,BOTTOM,INNER,OUTER}`(`lower()`・`parse`)
  - `record StyleSpec(Map<String,String> palette, Set<String> moodTags)`(`StyleSpec.EMPTY`。辞書順に複製)
  - `record Site(String dimension, BuildFrame frame, Box localBounds, String terrainDigest, String claimId)`
  - `sealed interface Anchor`: `Absolute(LocalPos pos, Rot rot)`・`OnSurface(String nodeId, Side side, int u, int v)`・`InSlot(String slotId, Rot rot)`
  - `enum ConnKind {ROTATION,ITEM,FLUID,REDSTONE,HEAT,DOCK}`(`lower()`・`parse`)、`record PortRef(String nodeId, String port)`
  - `sealed interface Routing`: `Auto`(`Routing.AUTO`)・`Explicit(List<String> viaNodeIds)`
  - `record Constraints(Integer maxLength, Set<String> avoidNodeIds, Integer maxTurns, Set<Dir6> allowedEntryDirs)`(`Constraints.NONE`)
  - `record Connection(String id, PortRef from, PortRef to, ConnKind kind, Routing routing, Constraints constraints)`
  - `record PlanNode(String id, String type, String parent, Anchor anchor, Map<String,ParamValue> params, Set<String> tags, String label)`(`parent`は`null`可。`params`は辞書順、`tags`は辞書順、`label`は`null`なら空文字)
  - `record LogisticsPlan(List<Dock> docks, List<Route> routes, List<CargoFlow> flows)`、`Dock(String id, Box padBox, Box clearanceBox, Facing approach, List<PortRef> linkedPorts, List<String> dockingConnectorNodeIds)`、`Route(String id, String fromDock, String toDock, List<LocalPos> waypoints, String airshipTemplateId)`、`CargoFlow(String itemId, double perMin, String fromDock, String toDock)`
  - `record SemanticPlan(int schemaVersion, String planId, int revision, Integer parentRevision, Site site, StyleSpec style, List<PlanNode> nodes, List<Connection> connections, LogisticsPlan logistics, Provenance provenance)`: `SCHEMA_VERSION=1`、`static SemanticPlan empty(String planId)`、`Optional<PlanNode> node(String id)`、`String contentHash()`
  - `sealed interface PlanOp`: `AddNode(PlanNode node)`・`UpdateParams(String id, Map<String,ParamValue> params)`・`MoveNode(String id, Anchor anchor)`・`RemoveNode(String id)`・`AddConnection(Connection connection)`・`RemoveConnection(String id)`・`SetStyle(StyleSpec style)`・`SetSite(Site site)`・`SetLogistics(LogisticsPlan logistics)`(`null`可)
  - `record PlanPatch(String patchId, int baseRevision, String stageId, List<PlanOp> ops)`
  - `PlanJson`: `Map<String,Object> toTree(SemanticPlan)`、`Map<String,Object> contentTree(SemanticPlan)`、`String contentHash(SemanticPlan)`、`SemanticPlan planFromTree(Object)`、`Map<String,Object> toTree(PlanPatch)`、`PlanPatch patchFromTree(Object)`、`Map<String,Object> nodeToTree(PlanNode)`、`PlanNode nodeFromTree(Object, String path)`、`Map<String,Object> connectionToTree(Connection)`、`Connection connectionFromTree(Object, String path)`。読み込みの失敗は`PlanJsonException`(メッセージに`$.nodes[2].anchor`のようなパス)
  - `contentHash`の対象: `planId`・`revision`・`parentRevision`・`provenance`を除く全項目。`nodes`と`connections`は`id`の辞書順に並べる(並びが違うだけの同じ設計は同じハッシュ)

- [ ] **Step 1: 失敗するテストを書く**

`PlanJsonTest.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PlanJsonTest {
    static Site site() {
        return new Site("minecraft:overworld", new BuildFrame(new IntPos(100, 64, 200), Facing.EAST),
                new Box(-2, -3, -2, 12, 8, 12), "", "");
    }

    /** Loosely typed (Int/Str/Bool/List only), so a JSON round trip gives back equal records. */
    static SemanticPlan loosePlan() {
        PlanNode structure = new PlanNode("hut", "micra:structure", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("width", new IntV(7), "depth", new IntV(7)), Set.of("main", "a"), "小屋 \"one\"\n");
        PlanNode wall = new PlanNode("wall-n", "micra:wall", "hut",
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new StrV("north"), "holes", new ListV(List.of(new IntV(1), new IntV(2)))), Set.of(), "");
        PlanNode door = new PlanNode("door-1", "micra:door", "hut",
                new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of("kind", new StrV("single"), "lattice", new BoolV(true)),
                Set.of(), null);
        PlanNode press = new PlanNode("press-1", "create:mechanical_press", null,
                new Anchor.Absolute(new LocalPos(3, 1, 2), new Rot(1, true)), Map.of(), Set.of(), "");
        PlanNode module = new PlanNode("line-1", "mod:press_station", null,
                new Anchor.InSlot("slot-a", new Rot(2, false)), Map.of(), Set.of(), "");
        Connection explicit = new Connection("c-1", new PortRef("press-1", "power_in"), new PortRef("line-1", "out"),
                ConnKind.ROTATION, new Routing.Explicit(List.of("shaft-1", "shaft-2")),
                new Constraints(20, Set.of("wall-n"), 3, Set.of(Dir6.UP, Dir6.NORTH)));
        Connection auto = new Connection("c-2", new PortRef("press-1", "item_out"), new PortRef("line-1", "in"),
                ConnKind.ITEM, Routing.AUTO, Constraints.NONE);
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.NORTH,
                        List.of(new PortRef("press-1", "item_out")), List.of("conn-1"))),
                List.of(new LogisticsPlan.Route("route-1", "dock-1", "dock-1", List.of(new LocalPos(0, 5, 0), new LocalPos(9, 5, 9)), "mod:airship_a")),
                List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", 12.5, "dock-1", "dock-1")));
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "plan-1", 3, 2, site(),
                new StyleSpec(Map.of("roof", "minecraft:red_nether_bricks", "wall", "minecraft:stone_bricks"), Set.of("cozy")),
                List.of(structure, wall, door, press, module), List.of(explicit, auto), logistics,
                new Provenance("architect", "claude-x", "abc", List.of("img-1"), 1234L));
    }

    @Test
    void jsonRoundTripGivesEqualRecords() {
        SemanticPlan plan = loosePlan();
        String json = MiniJson.write(PlanJson.toTree(plan));
        SemanticPlan back = PlanJson.planFromTree(MiniJson.parse(json));
        assertEquals(plan, back);
        assertEquals(plan.contentHash(), back.contentHash());
    }

    @Test
    void contentHashIgnoresMetaAndOrderButNotContent() {
        SemanticPlan plan = loosePlan();
        String base = plan.contentHash();
        SemanticPlan meta = new SemanticPlan(1, "other-id", 99, null, plan.site(), plan.style(), plan.nodes(),
                plan.connections(), plan.logistics(), Provenance.NONE);
        assertEquals(base, meta.contentHash(), "planId/revision/provenance are not part of the content");

        List<PlanNode> reversed = new ArrayList<>(plan.nodes());
        Collections.reverse(reversed);
        List<Connection> reversedConns = new ArrayList<>(plan.connections());
        Collections.reverse(reversedConns);
        SemanticPlan reordered = new SemanticPlan(1, "plan-1", 3, 2, plan.site(), plan.style(), reversed, reversedConns,
                plan.logistics(), plan.provenance());
        assertEquals(base, reordered.contentHash(), "node/connection order must not matter");

        TreeMap<String, ParamValue> changed = new TreeMap<>(plan.nodes().get(0).params());
        changed.put("width", new IntV(8));
        List<PlanNode> edited = new ArrayList<>(plan.nodes());
        PlanNode first = edited.get(0);
        edited.set(0, new PlanNode(first.id(), first.type(), first.parent(), first.anchor(), changed, first.tags(), first.label()));
        SemanticPlan different = new SemanticPlan(1, "plan-1", 3, 2, plan.site(), plan.style(), edited, plan.connections(),
                plan.logistics(), plan.provenance());
        assertNotEquals(base, different.contentHash());
    }

    @Test
    void typedAndLooseParametersHashTheSameWhenTheTextIsTheSame() {
        PlanNode loose = new PlanNode("w", "micra:wall", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new StrV("north"), "material", new StrV("wall")), Set.of(), "");
        PlanNode typed = new PlanNode("w", "micra:wall", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new EnumV("north"), "material", new MaterialV("wall")), Set.of(), "");
        assertEquals(CanonicalJsonHolder.of(PlanJson.nodeToTree(loose)), CanonicalJsonHolder.of(PlanJson.nodeToTree(typed)));
    }

    /** Local shim so the test reads clearly. */
    static final class CanonicalJsonHolder {
        static String of(Object tree) {
            return CanonicalJson.write(tree);
        }
    }

    @Test
    void emptyPlanHasNoSiteAndAStableHash() {
        SemanticPlan empty = SemanticPlan.empty("p");
        assertEquals(0, empty.revision());
        assertEquals(null, empty.site());
        assertEquals(empty.contentHash(), SemanticPlan.empty("q").contentHash());
        SemanticPlan back = PlanJson.planFromTree(MiniJson.parse(MiniJson.write(PlanJson.toTree(empty))));
        assertEquals(empty, back);
    }

    @Test
    void paramValueFromTreeReadsLooselyAndRejectsBadInput() {
        assertEquals(new IntV(3), ParamValue.fromTree(3.0));
        assertEquals(new NumV(3.5), ParamValue.fromTree(3.5));
        assertEquals(new NumV(3_000_000_000.0), ParamValue.fromTree(3_000_000_000.0), "outside int range stays a number");
        assertEquals(new StrV("x"), ParamValue.fromTree("x"));
        assertEquals(new BoolV(true), ParamValue.fromTree(true));
        assertEquals(new ListV(List.of(new IntV(1), new StrV("a"))), ParamValue.fromTree(List.of(1.0, "a")));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(null));
        assertThrows(IllegalArgumentException.class, () -> new NumV(Double.POSITIVE_INFINITY));
    }

    @Test
    void decodingErrorsCarryAJsonPath() {
        PlanJsonException missing = assertThrows(PlanJsonException.class,
                () -> PlanJson.planFromTree(MiniJson.parse("{\"schemaVersion\":1}")));
        assertTrue(missing.getMessage().contains("$"), missing.getMessage());

        String badPos = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"pos\":[0,0,0]", "\"pos\":[0,0]");
        PlanJsonException e = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(badPos)));
        assertTrue(e.getMessage().contains("$.nodes["), e.getMessage());
        assertTrue(e.getMessage().contains("pos"), e.getMessage());

        String wrongVersion = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        PlanJsonException v = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(wrongVersion)));
        assertTrue(v.getMessage().contains("schemaVersion"), v.getMessage());
    }

    @Test
    void patchRoundTripCoversEveryOperation() {
        PlanNode node = new PlanNode("hut", "micra:structure", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("width", new IntV(7)), Set.of(), "");
        Connection conn = new Connection("c-1", new PortRef("a", "out"), new PortRef("b", "in"), ConnKind.ITEM,
                Routing.AUTO, Constraints.NONE);
        PlanPatch patch = new PlanPatch("patch-1", 4, "architect", List.of(
                new PlanOp.SetSite(site()),
                new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:bricks"), Set.of("m"))),
                new PlanOp.AddNode(node),
                new PlanOp.UpdateParams("hut", Map.of("width", new IntV(9))),
                new PlanOp.MoveNode("hut", new Anchor.Absolute(new LocalPos(1, 0, 1), new Rot(1, false))),
                new PlanOp.AddConnection(conn),
                new PlanOp.RemoveConnection("c-1"),
                new PlanOp.RemoveNode("hut"),
                new PlanOp.SetLogistics(null)));
        PlanPatch back = PlanJson.patchFromTree(MiniJson.parse(MiniJson.write(PlanJson.toTree(patch))));
        assertEquals(patch, back);
    }

    @Test
    void handWrittenPatchJsonIsReadable() {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"hand\",\"ops\":["
                + "{\"op\":\"add_node\",\"node\":{\"id\":\"hut\",\"type\":\"micra:structure\",\"parent\":null,"
                + "\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0],\"rot\":{\"turns\":0,\"mirror\":false}},"
                + "\"params\":{\"width\":7},\"tags\":[],\"label\":\"\"}}]}";
        PlanPatch patch = PlanJson.patchFromTree(MiniJson.parse(json));
        assertEquals(1, patch.ops().size());
        PlanOp.AddNode add = (PlanOp.AddNode) patch.ops().get(0);
        assertEquals(new IntV(7), add.node().params().get("width"));
    }

    @Test
    void unknownOperationIsRejectedWithItsPath() {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"s\",\"ops\":[{\"op\":\"explode\"}]}";
        PlanJsonException e = assertThrows(PlanJsonException.class, () -> PlanJson.patchFromTree(MiniJson.parse(json)));
        assertTrue(e.getMessage().contains("$.ops[0]"), e.getMessage());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.PlanJsonTest" --console=plain`
Expected: FAIL(`cannot find symbol`)。

- [ ] **Step 3: 実装する**

`ParamValue.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A part parameter value. Values arriving from JSON or scripts are first read "loosely" ({@link #fromTree});
 * the part registry then fixes the exact type from the parameter's spec, so the stored form is canonical.
 */
public sealed interface ParamValue {
    record IntV(int value) implements ParamValue {
    }

    record NumV(double value) implements ParamValue {
        public NumV {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("number must be finite: " + value);
            }
        }
    }

    record BoolV(boolean value) implements ParamValue {
    }

    record StrV(String value) implements ParamValue {
        public StrV {
            Objects.requireNonNull(value, "value");
        }
    }

    record EnumV(String value) implements ParamValue {
        public EnumV {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Either a palette role name or a block id (see StyleSpec). */
    record MaterialV(String value) implements ParamValue {
        public MaterialV {
            Objects.requireNonNull(value, "value");
        }
    }

    record ListV(List<ParamValue> value) implements ParamValue {
        public ListV {
            value = List.copyOf(value);
        }
    }

    /** The JSON-tree form: Long, Double, Boolean, String or List. Enum and material values are plain strings. */
    default Object toTree() {
        return switch (this) {
            case IntV v -> (long) v.value();
            case NumV v -> v.value();
            case BoolV v -> v.value();
            case StrV v -> v.value();
            case EnumV v -> v.value();
            case MaterialV v -> v.value();
            case ListV v -> {
                List<Object> out = new ArrayList<>();
                for (ParamValue item : v.value()) {
                    out.add(item.toTree());
                }
                yield out;
            }
        };
    }

    /**
     * Reads a JSON/script value without knowing the spec: an integral number within int range is an
     * {@link IntV}, any other finite number a {@link NumV}, a string a {@link StrV}.
     */
    static ParamValue fromTree(Object tree) {
        if (tree instanceof Boolean b) {
            return new BoolV(b);
        }
        if (tree instanceof String s) {
            return new StrV(s);
        }
        if (tree instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("number must be finite: " + d);
            }
            if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                return new IntV((int) d);
            }
            return new NumV(d);
        }
        if (tree instanceof List<?> list) {
            List<ParamValue> items = new ArrayList<>();
            for (Object item : list) {
                items.add(fromTree(item));
            }
            return new ListV(items);
        }
        throw new IllegalArgumentException("unsupported parameter value: "
                + (tree == null ? "null" : tree.getClass().getSimpleName()));
    }
}
```

`Provenance.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/** Where a plan revision came from (which stage and model). Not part of the plan's content hash. */
public record Provenance(String stageId, String modelId, String promptHash, List<String> imageIds, long createdAtMillis) {
    public static final Provenance NONE = new Provenance("", "", "", List.of(), 0L);

    public Provenance {
        stageId = stageId == null ? "" : stageId;
        modelId = modelId == null ? "" : modelId;
        promptHash = promptHash == null ? "" : promptHash;
        imageIds = List.copyOf(imageIds == null ? List.of() : imageIds);
    }
}
```

`Side.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/** Which face of a part something attaches to. Openings and decorations use OUTER or INNER only. */
public enum Side {
    NORTH, EAST, SOUTH, WEST, TOP, BOTTOM, INNER, OUTER;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Side parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
```

`StyleSpec.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Role name to material (a block id), plus mood tags. Materials must pass the block policy (D-22) at compile time. */
public record StyleSpec(Map<String, String> palette, Set<String> moodTags) {
    public static final StyleSpec EMPTY = new StyleSpec(Map.of(), Set.of());

    public StyleSpec {
        palette = Collections.unmodifiableSortedMap(new TreeMap<>(palette == null ? Map.of() : palette));
        moodTags = Collections.unmodifiableSortedSet(new TreeSet<>(moodTags == null ? Set.of() : moodTags));
    }
}
```

`Site.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/**
 * Where the plan is built. The dimension is part of the manifest hash (D-27). {@code localBounds} is the
 * allowed region in local coordinates; every generated cell must lie inside it.
 */
public record Site(String dimension, BuildFrame frame, Box localBounds, String terrainDigest, String claimId) {
    public Site {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(localBounds, "localBounds");
        terrainDigest = terrainDigest == null ? "" : terrainDigest;
        claimId = claimId == null ? "" : claimId;
    }
}
```

`Anchor.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/**
 * Where a node sits. {@code Absolute} is relative to the parent's origin (or to the plan origin when there is
 * no parent); {@code OnSurface} is a position on a wall face (u along the wall's direction of growth, v up from
 * the wall's lowest row); {@code InSlot} needs the slot resolution that arrives with the building analysis.
 */
public sealed interface Anchor {
    record Absolute(LocalPos pos, Rot rot) implements Anchor {
        public Absolute {
            Objects.requireNonNull(pos, "pos");
            rot = rot == null ? Rot.NONE : rot;
        }
    }

    record OnSurface(String nodeId, Side side, int u, int v) implements Anchor {
        public OnSurface {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(side, "side");
        }
    }

    record InSlot(String slotId, Rot rot) implements Anchor {
        public InSlot {
            Objects.requireNonNull(slotId, "slotId");
            rot = rot == null ? Rot.NONE : rot;
        }
    }
}
```

`ConnKind.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

public enum ConnKind {
    ROTATION, ITEM, FLUID, REDSTONE, HEAT, DOCK;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConnKind parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
```

`PortRef.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/** A port on a node, e.g. {@code press-1 / power_in}. */
public record PortRef(String nodeId, String port) {
}
```

`Routing.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/** How a connection is routed: left to the Router (Auto) or through listed parts the author placed (Explicit). */
public sealed interface Routing {
    Auto AUTO = new Auto();

    record Auto() implements Routing {
    }

    record Explicit(List<String> viaNodeIds) implements Routing {
        public Explicit {
            viaNodeIds = List.copyOf(viaNodeIds);
        }
    }
}
```

`Constraints.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Limits on an automatically routed connection. Null numbers mean "no limit". */
public record Constraints(Integer maxLength, Set<String> avoidNodeIds, Integer maxTurns, Set<Dir6> allowedEntryDirs) {
    public static final Constraints NONE = new Constraints(null, Set.of(), null, Set.of());

    public Constraints {
        avoidNodeIds = Collections.unmodifiableSortedSet(new TreeSet<>(avoidNodeIds == null ? Set.of() : avoidNodeIds));
        allowedEntryDirs = Collections.unmodifiableSortedSet(new TreeSet<>(allowedEntryDirs == null ? Set.of() : allowedEntryDirs));
    }
}
```

`Connection.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/** A link between two ports. Written by ports, never by coordinates; intermediate parts are the Router's job. */
public record Connection(String id, PortRef from, PortRef to, ConnKind kind, Routing routing, Constraints constraints) {
    public Connection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        routing = routing == null ? Routing.AUTO : routing;
        constraints = constraints == null ? Constraints.NONE : constraints;
    }
}
```

`PlanNode.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** One part instance. {@code parent} nests it (a wall in a structure); {@code type} is a registry id or a template id. */
public record PlanNode(String id, String type, String parent, Anchor anchor, Map<String, ParamValue> params,
                       Set<String> tags, String label) {
    public PlanNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(anchor, "anchor");
        params = Collections.unmodifiableSortedMap(new TreeMap<>(params == null ? Map.of() : params));
        tags = Collections.unmodifiableSortedSet(new TreeSet<>(tags == null ? Set.of() : tags));
        label = label == null ? "" : label;
    }
}
```

`LogisticsPlan.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/** Docks, flight routes and cargo flows. Part of the plan, so it travels with approval and hashing. */
public record LogisticsPlan(List<Dock> docks, List<Route> routes, List<CargoFlow> flows) {
    public LogisticsPlan {
        docks = List.copyOf(docks == null ? List.of() : docks);
        routes = List.copyOf(routes == null ? List.of() : routes);
        flows = List.copyOf(flows == null ? List.of() : flows);
    }

    public record Dock(String id, Box padBox, Box clearanceBox, Facing approach, List<PortRef> linkedPorts,
                       List<String> dockingConnectorNodeIds) {
        public Dock {
            linkedPorts = List.copyOf(linkedPorts == null ? List.of() : linkedPorts);
            dockingConnectorNodeIds = List.copyOf(dockingConnectorNodeIds == null ? List.of() : dockingConnectorNodeIds);
        }
    }

    public record Route(String id, String fromDock, String toDock, List<LocalPos> waypoints, String airshipTemplateId) {
        public Route {
            waypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
        }
    }

    public record CargoFlow(String itemId, double perMin, String fromDock, String toDock) {
    }
}
```
(`Dock`・`Route`・`CargoFlow`は`LogisticsPlan`の入れ子の型。)

`SemanticPlan.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Optional;

/**
 * The design data: the source of truth (D-2). Scripts are a round-trippable view of it. {@code nodes} is in
 * insertion order and a parent always comes before its children; {@code contentHash} ignores the order.
 */
public record SemanticPlan(int schemaVersion, String planId, int revision, Integer parentRevision, Site site,
                           StyleSpec style, List<PlanNode> nodes, List<Connection> connections,
                           LogisticsPlan logistics, Provenance provenance) {
    public static final int SCHEMA_VERSION = 1;

    public SemanticPlan {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        connections = List.copyOf(connections == null ? List.of() : connections);
        style = style == null ? StyleSpec.EMPTY : style;
        provenance = provenance == null ? Provenance.NONE : provenance;
    }

    public static SemanticPlan empty(String planId) {
        return new SemanticPlan(SCHEMA_VERSION, planId, 0, null, null, StyleSpec.EMPTY, List.of(), List.of(), null,
                Provenance.NONE);
    }

    public Optional<PlanNode> node(String id) {
        for (PlanNode n : nodes) {
            if (n.id().equals(id)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** SHA-256 of the canonical content (everything except planId, revision, parentRevision, provenance). */
    public String contentHash() {
        return PlanJson.contentHash(this);
    }
}
```
(`SemanticPlan`のテストは`LogisticsPlan`を`SemanticPlan`のコンストラクタの第9引数に渡す。)

`PlanOp.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** One edit of a plan. A script is the program form of a list of these (one command = one operation). */
public sealed interface PlanOp {
    record AddNode(PlanNode node) implements PlanOp {
    }

    record UpdateParams(String id, Map<String, ParamValue> params) implements PlanOp {
        public UpdateParams {
            params = Collections.unmodifiableSortedMap(new TreeMap<>(params));
        }
    }

    record MoveNode(String id, Anchor anchor) implements PlanOp {
    }

    record RemoveNode(String id) implements PlanOp {
    }

    record AddConnection(Connection connection) implements PlanOp {
    }

    record RemoveConnection(String id) implements PlanOp {
    }

    record SetStyle(StyleSpec style) implements PlanOp {
    }

    record SetSite(Site site) implements PlanOp {
    }

    /** {@code logistics} may be null to clear the logistics of the plan. */
    record SetLogistics(LogisticsPlan logistics) implements PlanOp {
    }
}
```

`PlanPatch.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/** What the AI (or a script) produces: a list of operations against a specific base revision. */
public record PlanPatch(String patchId, int baseRevision, String stageId, List<PlanOp> ops) {
    public PlanPatch {
        ops = List.copyOf(ops);
    }
}
```

`PlanJsonException.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

/** A plan or patch could not be read from JSON; the message starts with the path of the bad value. */
public class PlanJsonException extends RuntimeException {
    public PlanJsonException(String message) {
        super(message);
    }
}
```

`JsonTree.java`(パッケージ内部の読み取り補助):
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Map;

/** Typed access to trees produced by {@code MiniJson.parse}, with a path in every error message. */
final class JsonTree {
    private JsonTree() {
    }

    static PlanJsonException bad(String path, String message) {
        return new PlanJsonException(path + ": " + message);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value, String path) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw bad(path, "expected an object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object value, String path) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw bad(path, "expected an array");
    }

    static String str(Object value, String path) {
        if (value instanceof String s) {
            return s;
        }
        throw bad(path, "expected a string");
    }

    static boolean bool(Object value, String path) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw bad(path, "expected true or false");
    }

    static int integer(Object value, String path) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                return (int) d;
            }
        }
        throw bad(path, "expected an integer");
    }

    static double number(Object value, String path) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw bad(path, "expected a finite number");
    }

    static Object req(Map<String, Object> map, String key, String path) {
        if (!map.containsKey(key)) {
            throw bad(path, "missing \"" + key + "\"");
        }
        return map.get(key);
    }

    /** Null when the key is absent or JSON null. */
    static String optStr(Map<String, Object> map, String key, String path) {
        Object v = map.get(key);
        return v == null ? null : str(v, path + "." + key);
    }
}
```

`PlanJson.java`:
```java
package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * JSON tree conversion of plans and patches, and the content hash. Trees are plain Map/List/String/Long/Double/
 * Boolean/null values, so they work with {@code MiniJson} and {@code CanonicalJson}. Reading is strict about
 * structure and reports the path of the bad value.
 */
public final class PlanJson {
    private PlanJson() {
    }

    // ------------------------------------------------------------------ plan -> tree

    public static Map<String, Object> toTree(SemanticPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", plan.schemaVersion());
        m.put("planId", plan.planId());
        m.put("revision", plan.revision());
        m.put("parentRevision", plan.parentRevision());
        m.putAll(body(plan, false));
        m.put("provenance", provenanceTree(plan.provenance()));
        return m;
    }

    /** The tree the content hash is computed from: no meta fields, nodes and connections in id order. */
    public static Map<String, Object> contentTree(SemanticPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", plan.schemaVersion());
        m.putAll(body(plan, true));
        return m;
    }

    public static String contentHash(SemanticPlan plan) {
        return Hashing.sha256Hex(CanonicalJson.write(contentTree(plan)));
    }

    private static Map<String, Object> body(SemanticPlan plan, boolean canonicalOrder) {
        List<PlanNode> nodes = new ArrayList<>(plan.nodes());
        List<Connection> connections = new ArrayList<>(plan.connections());
        if (canonicalOrder) {
            nodes.sort(Comparator.comparing(PlanNode::id));
            connections.sort(Comparator.comparing(Connection::id));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("site", plan.site() == null ? null : siteTree(plan.site()));
        m.put("style", styleTree(plan.style()));
        List<Object> nodeTrees = new ArrayList<>();
        for (PlanNode n : nodes) {
            nodeTrees.add(nodeToTree(n));
        }
        m.put("nodes", nodeTrees);
        List<Object> connTrees = new ArrayList<>();
        for (Connection c : connections) {
            connTrees.add(connectionToTree(c));
        }
        m.put("connections", connTrees);
        m.put("logistics", plan.logistics() == null ? null : logisticsTree(plan.logistics()));
        return m;
    }

    public static List<Object> posTree(LocalPos p) {
        return new ArrayList<>(List.of((long) p.u(), (long) p.v(), (long) p.w()));
    }

    public static List<Object> boxTree(Box b) {
        return new ArrayList<>(List.of((long) b.minA(), (long) b.minB(), (long) b.minC(),
                (long) b.maxA(), (long) b.maxB(), (long) b.maxC()));
    }

    static Map<String, Object> rotTree(Rot r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turns", r.quarterTurns());
        m.put("mirror", r.mirror());
        return m;
    }

    static Map<String, Object> anchorTree(Anchor anchor) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (anchor) {
            case Anchor.Absolute a -> {
                m.put("kind", "absolute");
                m.put("pos", posTree(a.pos()));
                m.put("rot", rotTree(a.rot()));
            }
            case Anchor.OnSurface s -> {
                m.put("kind", "surface");
                m.put("node", s.nodeId());
                m.put("side", s.side().lower());
                m.put("u", s.u());
                m.put("v", s.v());
            }
            case Anchor.InSlot s -> {
                m.put("kind", "slot");
                m.put("slot", s.slotId());
                m.put("rot", rotTree(s.rot()));
            }
        }
        return m;
    }

    static Map<String, Object> siteTree(Site s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", s.dimension());
        m.put("origin", new ArrayList<>(List.of((long) s.frame().origin().x(), (long) s.frame().origin().y(),
                (long) s.frame().origin().z())));
        m.put("facing", s.frame().facing().lower());
        m.put("bounds", boxTree(s.localBounds()));
        m.put("terrainDigest", s.terrainDigest());
        m.put("claimId", s.claimId());
        return m;
    }

    static Map<String, Object> styleTree(StyleSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("palette", new TreeMap<>(s.palette()));
        m.put("moodTags", new ArrayList<>(s.moodTags()));
        return m;
    }

    public static Map<String, Object> nodeToTree(PlanNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        m.put("parent", n.parent());
        m.put("anchor", anchorTree(n.anchor()));
        m.put("params", paramsTree(n.params()));
        m.put("tags", new ArrayList<>(n.tags()));
        m.put("label", n.label());
        return m;
    }

    static Map<String, Object> paramsTree(Map<String, ParamValue> params) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, ParamValue> e : params.entrySet()) {
            m.put(e.getKey(), e.getValue().toTree());
        }
        return m;
    }

    static Map<String, Object> portTree(PortRef p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", p.nodeId());
        m.put("port", p.port());
        return m;
    }

    public static Map<String, Object> connectionToTree(Connection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("from", portTree(c.from()));
        m.put("to", portTree(c.to()));
        m.put("kind", c.kind().lower());
        Map<String, Object> routing = new LinkedHashMap<>();
        if (c.routing() instanceof Routing.Explicit e) {
            routing.put("mode", "explicit");
            routing.put("via", new ArrayList<>(e.viaNodeIds()));
        } else {
            routing.put("mode", "auto");
        }
        m.put("routing", routing);
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("maxLength", c.constraints().maxLength());
        constraints.put("avoid", new ArrayList<>(c.constraints().avoidNodeIds()));
        constraints.put("maxTurns", c.constraints().maxTurns());
        List<Object> dirs = new ArrayList<>();
        for (Dir6 d : c.constraints().allowedEntryDirs()) {
            dirs.add(d.lower());
        }
        constraints.put("entryDirs", dirs);
        m.put("constraints", constraints);
        return m;
    }

    static Map<String, Object> logisticsTree(LogisticsPlan l) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> docks = new ArrayList<>();
        for (LogisticsPlan.Dock d : l.docks()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id());
            dm.put("pad", boxTree(d.padBox()));
            dm.put("clearance", boxTree(d.clearanceBox()));
            dm.put("approach", d.approach().lower());
            List<Object> ports = new ArrayList<>();
            for (PortRef p : d.linkedPorts()) {
                ports.add(portTree(p));
            }
            dm.put("ports", ports);
            dm.put("connectors", new ArrayList<>(d.dockingConnectorNodeIds()));
            docks.add(dm);
        }
        m.put("docks", docks);
        List<Object> routes = new ArrayList<>();
        for (LogisticsPlan.Route r : l.routes()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id());
            rm.put("from", r.fromDock());
            rm.put("to", r.toDock());
            List<Object> pts = new ArrayList<>();
            for (LocalPos p : r.waypoints()) {
                pts.add(posTree(p));
            }
            rm.put("waypoints", pts);
            rm.put("airship", r.airshipTemplateId());
            routes.add(rm);
        }
        m.put("routes", routes);
        List<Object> flows = new ArrayList<>();
        for (LogisticsPlan.CargoFlow f : l.flows()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("item", f.itemId());
            fm.put("perMin", f.perMin());
            fm.put("from", f.fromDock());
            fm.put("to", f.toDock());
            flows.add(fm);
        }
        m.put("flows", flows);
        return m;
    }

    static Map<String, Object> provenanceTree(Provenance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stageId", p.stageId());
        m.put("modelId", p.modelId());
        m.put("promptHash", p.promptHash());
        m.put("imageIds", new ArrayList<>(p.imageIds()));
        m.put("createdAtMillis", p.createdAtMillis());
        return m;
    }

    // ------------------------------------------------------------------ tree -> plan

    public static SemanticPlan planFromTree(Object tree) {
        String path = "$";
        Map<String, Object> m = JsonTree.obj(tree, path);
        int version = JsonTree.integer(JsonTree.req(m, "schemaVersion", path), path + ".schemaVersion");
        if (version != SemanticPlan.SCHEMA_VERSION) {
            throw JsonTree.bad(path + ".schemaVersion", "unsupported schemaVersion " + version
                    + " (this build reads " + SemanticPlan.SCHEMA_VERSION + "); the data is refused, not converted");
        }
        String planId = JsonTree.str(JsonTree.req(m, "planId", path), path + ".planId");
        int revision = JsonTree.integer(JsonTree.req(m, "revision", path), path + ".revision");
        Integer parentRevision = m.get("parentRevision") == null ? null
                : JsonTree.integer(m.get("parentRevision"), path + ".parentRevision");
        Site site = m.get("site") == null ? null : siteFromTree(m.get("site"), path + ".site");
        StyleSpec style = m.get("style") == null ? StyleSpec.EMPTY : styleFromTree(m.get("style"), path + ".style");
        List<PlanNode> nodes = new ArrayList<>();
        List<Object> nodeTrees = JsonTree.arr(JsonTree.req(m, "nodes", path), path + ".nodes");
        for (int i = 0; i < nodeTrees.size(); i++) {
            nodes.add(nodeFromTree(nodeTrees.get(i), path + ".nodes[" + i + "]"));
        }
        List<Connection> connections = new ArrayList<>();
        List<Object> connTrees = JsonTree.arr(JsonTree.req(m, "connections", path), path + ".connections");
        for (int i = 0; i < connTrees.size(); i++) {
            connections.add(connectionFromTree(connTrees.get(i), path + ".connections[" + i + "]"));
        }
        LogisticsPlan logistics = m.get("logistics") == null ? null
                : logisticsFromTree(m.get("logistics"), path + ".logistics");
        Provenance provenance = m.get("provenance") == null ? Provenance.NONE
                : provenanceFromTree(m.get("provenance"), path + ".provenance");
        return new SemanticPlan(version, planId, revision, parentRevision, site, style, nodes, connections, logistics,
                provenance);
    }

    static LocalPos posFromTree(Object v, String path) {
        List<Object> a = JsonTree.arr(v, path);
        if (a.size() != 3) {
            throw JsonTree.bad(path, "expected [u, v, w]");
        }
        return new LocalPos(JsonTree.integer(a.get(0), path + "[0]"), JsonTree.integer(a.get(1), path + "[1]"),
                JsonTree.integer(a.get(2), path + "[2]"));
    }

    static Box boxFromTree(Object v, String path) {
        List<Object> a = JsonTree.arr(v, path);
        if (a.size() != 6) {
            throw JsonTree.bad(path, "expected six integers [minA, minB, minC, maxA, maxB, maxC]");
        }
        int[] n = new int[6];
        for (int i = 0; i < 6; i++) {
            n[i] = JsonTree.integer(a.get(i), path + "[" + i + "]");
        }
        try {
            return new Box(n[0], n[1], n[2], n[3], n[4], n[5]);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, e.getMessage());
        }
    }

    static Rot rotFromTree(Object v, String path) {
        if (v == null) {
            return Rot.NONE;
        }
        Map<String, Object> m = JsonTree.obj(v, path);
        return new Rot(JsonTree.integer(JsonTree.req(m, "turns", path), path + ".turns"),
                JsonTree.bool(JsonTree.req(m, "mirror", path), path + ".mirror"));
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, java.util.function.Function<String, E> parse, Object v, String path) {
        String text = JsonTree.str(v, path);
        try {
            return parse.apply(text);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, "unknown " + type.getSimpleName() + " \"" + text + "\"");
        }
    }

    static Anchor anchorFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        String kind = JsonTree.str(JsonTree.req(m, "kind", path), path + ".kind");
        return switch (kind) {
            case "absolute" -> new Anchor.Absolute(posFromTree(JsonTree.req(m, "pos", path), path + ".pos"),
                    rotFromTree(m.get("rot"), path + ".rot"));
            case "surface" -> new Anchor.OnSurface(JsonTree.str(JsonTree.req(m, "node", path), path + ".node"),
                    enumOf(Side.class, Side::parse, JsonTree.req(m, "side", path), path + ".side"),
                    JsonTree.integer(JsonTree.req(m, "u", path), path + ".u"),
                    JsonTree.integer(JsonTree.req(m, "v", path), path + ".v"));
            case "slot" -> new Anchor.InSlot(JsonTree.str(JsonTree.req(m, "slot", path), path + ".slot"),
                    rotFromTree(m.get("rot"), path + ".rot"));
            default -> throw JsonTree.bad(path + ".kind", "unknown anchor kind \"" + kind + "\"");
        };
    }

    static Site siteFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        List<Object> o = JsonTree.arr(JsonTree.req(m, "origin", path), path + ".origin");
        if (o.size() != 3) {
            throw JsonTree.bad(path + ".origin", "expected [x, y, z]");
        }
        IntPos origin = new IntPos(JsonTree.integer(o.get(0), path + ".origin[0]"),
                JsonTree.integer(o.get(1), path + ".origin[1]"), JsonTree.integer(o.get(2), path + ".origin[2]"));
        Facing facing = enumOf(Facing.class, Facing::parse, JsonTree.req(m, "facing", path), path + ".facing");
        String terrain = JsonTree.optStr(m, "terrainDigest", path);
        String claim = JsonTree.optStr(m, "claimId", path);
        return new Site(JsonTree.str(JsonTree.req(m, "dimension", path), path + ".dimension"),
                new BuildFrame(origin, facing), boxFromTree(JsonTree.req(m, "bounds", path), path + ".bounds"),
                terrain, claim);
    }

    static StyleSpec styleFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Map<String, String> palette = new TreeMap<>();
        if (m.get("palette") != null) {
            for (Map.Entry<String, Object> e : JsonTree.obj(m.get("palette"), path + ".palette").entrySet()) {
                palette.put(e.getKey(), JsonTree.str(e.getValue(), path + ".palette." + e.getKey()));
            }
        }
        java.util.TreeSet<String> mood = new java.util.TreeSet<>();
        if (m.get("moodTags") != null) {
            List<Object> tags = JsonTree.arr(m.get("moodTags"), path + ".moodTags");
            for (int i = 0; i < tags.size(); i++) {
                mood.add(JsonTree.str(tags.get(i), path + ".moodTags[" + i + "]"));
            }
        }
        return new StyleSpec(palette, mood);
    }

    public static PlanNode nodeFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Map<String, ParamValue> params = new TreeMap<>();
        if (m.get("params") != null) {
            for (Map.Entry<String, Object> e : JsonTree.obj(m.get("params"), path + ".params").entrySet()) {
                params.put(e.getKey(), paramFromTree(e.getValue(), path + ".params." + e.getKey()));
            }
        }
        java.util.TreeSet<String> tags = new java.util.TreeSet<>();
        if (m.get("tags") != null) {
            List<Object> list = JsonTree.arr(m.get("tags"), path + ".tags");
            for (int i = 0; i < list.size(); i++) {
                tags.add(JsonTree.str(list.get(i), path + ".tags[" + i + "]"));
            }
        }
        return new PlanNode(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"),
                JsonTree.str(JsonTree.req(m, "type", path), path + ".type"), JsonTree.optStr(m, "parent", path),
                anchorFromTree(JsonTree.req(m, "anchor", path), path + ".anchor"), params, tags,
                JsonTree.optStr(m, "label", path));
    }

    static ParamValue paramFromTree(Object v, String path) {
        try {
            return ParamValue.fromTree(v);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, e.getMessage());
        }
    }

    static PortRef portFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        return new PortRef(JsonTree.str(JsonTree.req(m, "node", path), path + ".node"),
                JsonTree.str(JsonTree.req(m, "port", path), path + ".port"));
    }

    public static Connection connectionFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Routing routing = Routing.AUTO;
        if (m.get("routing") != null) {
            Map<String, Object> r = JsonTree.obj(m.get("routing"), path + ".routing");
            String mode = JsonTree.str(JsonTree.req(r, "mode", path + ".routing"), path + ".routing.mode");
            if (mode.equals("explicit")) {
                List<String> via = new ArrayList<>();
                List<Object> list = JsonTree.arr(JsonTree.req(r, "via", path + ".routing"), path + ".routing.via");
                for (int i = 0; i < list.size(); i++) {
                    via.add(JsonTree.str(list.get(i), path + ".routing.via[" + i + "]"));
                }
                routing = new Routing.Explicit(via);
            } else if (!mode.equals("auto")) {
                throw JsonTree.bad(path + ".routing.mode", "unknown routing mode \"" + mode + "\"");
            }
        }
        Constraints constraints = Constraints.NONE;
        if (m.get("constraints") != null) {
            Map<String, Object> c = JsonTree.obj(m.get("constraints"), path + ".constraints");
            Integer maxLength = c.get("maxLength") == null ? null
                    : JsonTree.integer(c.get("maxLength"), path + ".constraints.maxLength");
            Integer maxTurns = c.get("maxTurns") == null ? null
                    : JsonTree.integer(c.get("maxTurns"), path + ".constraints.maxTurns");
            java.util.TreeSet<String> avoid = new java.util.TreeSet<>();
            if (c.get("avoid") != null) {
                List<Object> list = JsonTree.arr(c.get("avoid"), path + ".constraints.avoid");
                for (int i = 0; i < list.size(); i++) {
                    avoid.add(JsonTree.str(list.get(i), path + ".constraints.avoid[" + i + "]"));
                }
            }
            java.util.TreeSet<Dir6> dirs = new java.util.TreeSet<>();
            if (c.get("entryDirs") != null) {
                List<Object> list = JsonTree.arr(c.get("entryDirs"), path + ".constraints.entryDirs");
                for (int i = 0; i < list.size(); i++) {
                    dirs.add(enumOf(Dir6.class, Dir6::parse, list.get(i), path + ".constraints.entryDirs[" + i + "]"));
                }
            }
            constraints = new Constraints(maxLength, avoid, maxTurns, dirs);
        }
        return new Connection(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"),
                portFromTree(JsonTree.req(m, "from", path), path + ".from"),
                portFromTree(JsonTree.req(m, "to", path), path + ".to"),
                enumOf(ConnKind.class, ConnKind::parse, JsonTree.req(m, "kind", path), path + ".kind"), routing, constraints);
    }

    static LogisticsPlan logisticsFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        List<LogisticsPlan.Dock> docks = new ArrayList<>();
        List<Object> dockTrees = JsonTree.arr(JsonTree.req(m, "docks", path), path + ".docks");
        for (int i = 0; i < dockTrees.size(); i++) {
            String p = path + ".docks[" + i + "]";
            Map<String, Object> d = JsonTree.obj(dockTrees.get(i), p);
            List<PortRef> ports = new ArrayList<>();
            List<Object> portTrees = JsonTree.arr(JsonTree.req(d, "ports", p), p + ".ports");
            for (int j = 0; j < portTrees.size(); j++) {
                ports.add(portFromTree(portTrees.get(j), p + ".ports[" + j + "]"));
            }
            List<String> connectors = new ArrayList<>();
            List<Object> connTrees = JsonTree.arr(JsonTree.req(d, "connectors", p), p + ".connectors");
            for (int j = 0; j < connTrees.size(); j++) {
                connectors.add(JsonTree.str(connTrees.get(j), p + ".connectors[" + j + "]"));
            }
            docks.add(new LogisticsPlan.Dock(JsonTree.str(JsonTree.req(d, "id", p), p + ".id"),
                    boxFromTree(JsonTree.req(d, "pad", p), p + ".pad"),
                    boxFromTree(JsonTree.req(d, "clearance", p), p + ".clearance"),
                    enumOf(Facing.class, Facing::parse, JsonTree.req(d, "approach", p), p + ".approach"), ports, connectors));
        }
        List<LogisticsPlan.Route> routes = new ArrayList<>();
        List<Object> routeTrees = JsonTree.arr(JsonTree.req(m, "routes", path), path + ".routes");
        for (int i = 0; i < routeTrees.size(); i++) {
            String p = path + ".routes[" + i + "]";
            Map<String, Object> r = JsonTree.obj(routeTrees.get(i), p);
            List<LocalPos> pts = new ArrayList<>();
            List<Object> ptTrees = JsonTree.arr(JsonTree.req(r, "waypoints", p), p + ".waypoints");
            for (int j = 0; j < ptTrees.size(); j++) {
                pts.add(posFromTree(ptTrees.get(j), p + ".waypoints[" + j + "]"));
            }
            routes.add(new LogisticsPlan.Route(JsonTree.str(JsonTree.req(r, "id", p), p + ".id"),
                    JsonTree.str(JsonTree.req(r, "from", p), p + ".from"), JsonTree.str(JsonTree.req(r, "to", p), p + ".to"),
                    pts, JsonTree.optStr(r, "airship", p)));
        }
        List<LogisticsPlan.CargoFlow> flows = new ArrayList<>();
        List<Object> flowTrees = JsonTree.arr(JsonTree.req(m, "flows", path), path + ".flows");
        for (int i = 0; i < flowTrees.size(); i++) {
            String p = path + ".flows[" + i + "]";
            Map<String, Object> f = JsonTree.obj(flowTrees.get(i), p);
            flows.add(new LogisticsPlan.CargoFlow(JsonTree.str(JsonTree.req(f, "item", p), p + ".item"),
                    JsonTree.number(JsonTree.req(f, "perMin", p), p + ".perMin"),
                    JsonTree.str(JsonTree.req(f, "from", p), p + ".from"), JsonTree.str(JsonTree.req(f, "to", p), p + ".to")));
        }
        return new LogisticsPlan(docks, routes, flows);
    }

    static Provenance provenanceFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        List<String> images = new ArrayList<>();
        if (m.get("imageIds") != null) {
            List<Object> list = JsonTree.arr(m.get("imageIds"), path + ".imageIds");
            for (int i = 0; i < list.size(); i++) {
                images.add(JsonTree.str(list.get(i), path + ".imageIds[" + i + "]"));
            }
        }
        long created = m.get("createdAtMillis") == null ? 0L
                : (long) JsonTree.number(m.get("createdAtMillis"), path + ".createdAtMillis");
        return new Provenance(JsonTree.optStr(m, "stageId", path), JsonTree.optStr(m, "modelId", path),
                JsonTree.optStr(m, "promptHash", path), images, created);
    }

    // ------------------------------------------------------------------ patch

    public static Map<String, Object> toTree(PlanPatch patch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patchId", patch.patchId());
        m.put("baseRevision", patch.baseRevision());
        m.put("stageId", patch.stageId());
        List<Object> ops = new ArrayList<>();
        for (PlanOp op : patch.ops()) {
            ops.add(opTree(op));
        }
        m.put("ops", ops);
        return m;
    }

    private static Map<String, Object> opTree(PlanOp op) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (op) {
            case PlanOp.AddNode o -> {
                m.put("op", "add_node");
                m.put("node", nodeToTree(o.node()));
            }
            case PlanOp.UpdateParams o -> {
                m.put("op", "update_params");
                m.put("id", o.id());
                m.put("params", paramsTree(o.params()));
            }
            case PlanOp.MoveNode o -> {
                m.put("op", "move_node");
                m.put("id", o.id());
                m.put("anchor", anchorTree(o.anchor()));
            }
            case PlanOp.RemoveNode o -> {
                m.put("op", "remove_node");
                m.put("id", o.id());
            }
            case PlanOp.AddConnection o -> {
                m.put("op", "add_connection");
                m.put("connection", connectionToTree(o.connection()));
            }
            case PlanOp.RemoveConnection o -> {
                m.put("op", "remove_connection");
                m.put("id", o.id());
            }
            case PlanOp.SetStyle o -> {
                m.put("op", "set_style");
                m.put("style", styleTree(o.style()));
            }
            case PlanOp.SetSite o -> {
                m.put("op", "set_site");
                m.put("site", siteTree(o.site()));
            }
            case PlanOp.SetLogistics o -> {
                m.put("op", "set_logistics");
                m.put("logistics", o.logistics() == null ? null : logisticsTree(o.logistics()));
            }
        }
        return m;
    }

    public static PlanPatch patchFromTree(Object tree) {
        String path = "$";
        Map<String, Object> m = JsonTree.obj(tree, path);
        List<PlanOp> ops = new ArrayList<>();
        List<Object> opTrees = JsonTree.arr(JsonTree.req(m, "ops", path), path + ".ops");
        for (int i = 0; i < opTrees.size(); i++) {
            ops.add(opFromTree(opTrees.get(i), path + ".ops[" + i + "]"));
        }
        String stage = JsonTree.optStr(m, "stageId", path);
        return new PlanPatch(JsonTree.str(JsonTree.req(m, "patchId", path), path + ".patchId"),
                JsonTree.integer(JsonTree.req(m, "baseRevision", path), path + ".baseRevision"), stage == null ? "" : stage, ops);
    }

    private static PlanOp opFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        String op = JsonTree.str(JsonTree.req(m, "op", path), path + ".op");
        return switch (op) {
            case "add_node" -> new PlanOp.AddNode(nodeFromTree(JsonTree.req(m, "node", path), path + ".node"));
            case "update_params" -> {
                Map<String, ParamValue> params = new TreeMap<>();
                for (Map.Entry<String, Object> e : JsonTree.obj(JsonTree.req(m, "params", path), path + ".params").entrySet()) {
                    params.put(e.getKey(), paramFromTree(e.getValue(), path + ".params." + e.getKey()));
                }
                yield new PlanOp.UpdateParams(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"), params);
            }
            case "move_node" -> new PlanOp.MoveNode(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"),
                    anchorFromTree(JsonTree.req(m, "anchor", path), path + ".anchor"));
            case "remove_node" -> new PlanOp.RemoveNode(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"));
            case "add_connection" -> new PlanOp.AddConnection(
                    connectionFromTree(JsonTree.req(m, "connection", path), path + ".connection"));
            case "remove_connection" -> new PlanOp.RemoveConnection(JsonTree.str(JsonTree.req(m, "id", path), path + ".id"));
            case "set_style" -> new PlanOp.SetStyle(styleFromTree(JsonTree.req(m, "style", path), path + ".style"));
            case "set_site" -> new PlanOp.SetSite(siteFromTree(JsonTree.req(m, "site", path), path + ".site"));
            case "set_logistics" -> new PlanOp.SetLogistics(m.get("logistics") == null ? null
                    : logisticsFromTree(m.get("logistics"), path + ".logistics"));
            default -> throw JsonTree.bad(path + ".op", "unknown operation \"" + op + "\"");
        };
    }
}
```
(`Set`のimportは使わなければ消す。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.model.PlanJsonTest" --console=plain`
Expected: PASS。`LogisticsPlan`の入れ子の型のimportを、テストに足していなければ足す。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build/model src/test/java/io/github/khayashi4337/micradrone/build/model
git commit -m "$(cat <<'EOF'
feat: 設計データ(SemanticPlan・PlanNode・Anchor・Connection・PlanPatch)とJSON変換・内容ハッシュを追加(自然言語→工場建設 P3 Task 5)

contentHashは、planId・revision・provenanceを除き、nodes/connectionsをid順に並べて計算する。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 部品の型・登録簿・パラメータ検証・素材の族

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/parts/{PartCategory,Visibility,ParamType,ParamSpec,PortKind,PortSpec,VolumeSpec,VersionRange,PlacerId,VerifyMode,EffectKind,EffectSpec,AssemblyKind,AssemblyExpectation,AssemblySpec,ModelRef,BuildPhase,PartType,PartTypeJson,PartTypeRegistry,ParamException,ParamValidator,Params,MaterialFamilies}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/parts/{ParamValidatorTest,PartTypeRegistryTest,MaterialFamiliesTest}.java`

**Interfaces:**
- Consumes: Task 2〜5(`LocalPos`・`Dir6`・`Box`・`ParamValue`・`Issue`・`IssueCode`・`FixHint`・`CanonicalJson`・`Hashing`)
- Produces:
  - 列挙: `PartCategory {STRUCTURE,OPENING,ROOF,DECOR,POWER,TRANSMISSION,PROCESSING,LOGISTICS,STORAGE,FLUID,AERO,MODULE}`、`Visibility {USER,IMPLICIT}`、`ParamType {INT,NUM,BOOL,STR,ENUM,MATERIAL,INT_LIST}`、`PortKind {ROTATION_IN,ROTATION_OUT,ITEM_IN,ITEM_OUT,FLUID_IN,FLUID_OUT,REDSTONE,HEAT,DOCK}`、`PlacerId {SIMPLE,BELT,ARM,MULTIBLOCK,ASSEMBLY}`、`VerifyMode {EXACT,STATE_SUBSET,BLOCK_ONLY,ASSEMBLED_AWAY}`、`EffectKind {NONE,BREAK,FLUID,PROJECTILE,MOVE_STRUCTURE}`、`AssemblyKind {WINDMILL,BEARING,PHYSICS_ASSEMBLER}`、`BuildPhase {SITE_PREP,STRUCTURE,ENVELOPE,POWER,UPSTREAM,DOWNSTREAM,LOGISTICS,ASSEMBLE,DECORATION,FINISH}`
  - `record ParamSpec(String name, ParamType type, String unit, ParamValue min, ParamValue max, ParamValue defaultValue, List<String> enumValues, int maxItems)`: `boolean required()`(`defaultValue==null`)、ファクトリ `integer(name,min,max,Integer defaultOrNull)`・`bool(name,boolean)`・`enumOf(name,String defaultOrNull,String... values)`・`material(name,defaultRole)`・`text(name,maxLength,String defaultOrNull)`・`intList(name,min,max,maxItems)`(既定は空の並び)
  - `record PortSpec(String name, PortKind kind, LocalPos offset, Dir6 facing, Set<String> accepts)`、`record VolumeSpec(List<Box> boxes, Map<String,String> sizeFromParams)`(`VolumeSpec.GENERATED`=生成器が決める)、`record VersionRange(String modId, String mavenRange)`(`VersionRange.ALWAYS`)、`record EffectSpec(EffectKind kind, Box reachLocal)`(`EffectSpec.NONE`)、`sealed interface AssemblyExpectation`(`ContraptionExpectation(int entityCount,int movedBlockCount)`・`SubLevelExpectation(int subLevelCount,int movedBlockCount)`)、`record AssemblySpec(AssemblyKind kind, String triggerPort, AssemblyExpectation expect, String disassembleAction)`、`record ModelRef(String modelId)`
  - `record PartType(...)`(設計図01 3節の全欄)と`PartType.builder(String id, PartCategory category)`(既定: `USER`、`ALWAYS`、`SIMPLE`、`EXACT`、`GENERATED`、`EffectSpec.NONE`、`STRUCTURE`)。不変条件: IDは`[a-z0-9_]+:[a-z0-9_]+`、`USER`は`displayNameKey`が必須、パラメータ名とポート名は重複しない。`Optional<ParamSpec> param(String)`、`Optional<PortSpec> port(String)`
  - `PartTypeRegistry`: `builder()`(`register(PartType)`・`defaultPalette(Map<String,String>)`・`build()`。ID重複は`IllegalArgumentException`)、`Optional<PartType> find(String)`、`PartType get(String)`(無ければ`IllegalArgumentException`)、`boolean contains(String)`、`Collection<PartType> all()`(ID順)、`List<PartType> userParts()`、`Map<String,String> defaultPalette()`、`String version()`(登録内容と既定パレットの正規JSONのSHA-256)、`List<String> suggest(String unknownId, int limit)`(編集距離の近いID)
  - `ParamValidator`: `static ParamValue coerce(ParamSpec, ParamValue loose) throws ParamException`、`static Result validate(String nodeId, PartType type, Map<String,ParamValue> given)`(`Result(Map<String,ParamValue> typed, List<Issue> issues)`。未知のパラメータ名・型違い・範囲外・必須の欠落は`E-PARAM-RANGE`。`key`はパラメータ名)
  - `Params`: `static Params resolve(PartType type, Map<String,ParamValue> typed)`(既定値を補う)、`int i(String)`・`boolean b(String)`・`String s(String)`・`List<Integer> ints(String)`・`double d(String)`
  - `MaterialFamilies`: `record Family(String full, String stairs, String slab)`、`static Optional<Family> family(String fullBlockId)`

- [ ] **Step 1: 失敗するテストを書く**

`ParamValidatorTest.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamValidatorTest {
    private static final ParamSpec WIDTH = ParamSpec.integer("width", 3, 64, 7);
    private static final ParamSpec KIND = ParamSpec.enumOf("kind", "gable", "gable", "hip", "flat");
    private static final ParamSpec SIDE = ParamSpec.enumOf("side", null, "north", "south");
    private static final ParamSpec MATERIAL = ParamSpec.material("material", "wall");
    private static final ParamSpec TEXT = ParamSpec.text("text", 5, null);
    private static final ParamSpec HOLES = ParamSpec.intList("holes", 0, 63, 8);
    private static final ParamSpec FLAG = ParamSpec.bool("flag", false);

    @Test
    void integersAcceptIntegralNumbersAndRejectFractionsAndOverflow() throws Exception {
        assertEquals(new IntV(7), ParamValidator.coerce(WIDTH, new IntV(7)));
        assertEquals(new IntV(7), ParamValidator.coerce(WIDTH, new NumV(7.0)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new NumV(7.5)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new NumV(3_000_000_000.0)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new IntV(2)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new IntV(65)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(WIDTH, new StrV("7")));
    }

    @Test
    void enumsBecomeEnumValuesAndMustBeListed() throws Exception {
        assertEquals(new EnumV("hip"), ParamValidator.coerce(KIND, new StrV("hip")));
        assertEquals(new EnumV("hip"), ParamValidator.coerce(KIND, new EnumV("hip")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(KIND, new StrV("dome")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(KIND, new IntV(1)));
    }

    @Test
    void materialsAreRolesOrBlockIds() throws Exception {
        assertEquals(new MaterialV("roof"), ParamValidator.coerce(MATERIAL, new StrV("roof")));
        assertEquals(new MaterialV("minecraft:stone"), ParamValidator.coerce(MATERIAL, new StrV("minecraft:stone")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(MATERIAL, new StrV("Not Valid!")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(MATERIAL, new StrV("")));
    }

    @Test
    void numbersBoolsTextsAndIntLists() throws Exception {
        ParamSpec speed = new ParamSpec("speed", ParamType.NUM, "rpm", new NumV(0), new NumV(256), new NumV(16), List.of(), 0);
        assertEquals(new NumV(4.0), ParamValidator.coerce(speed, new IntV(4)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(speed, new NumV(300)));
        assertEquals(new BoolV(true), ParamValidator.coerce(FLAG, new BoolV(true)));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(FLAG, new IntV(1)));
        assertEquals(new StrV("abc"), ParamValidator.coerce(TEXT, new StrV("abc")));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(TEXT, new StrV("abcdef")));
        assertEquals(new ListV(List.of(new IntV(1), new IntV(2))),
                ParamValidator.coerce(HOLES, new ListV(List.of(new IntV(1), new NumV(2.0)))));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(HOLES, new ListV(List.of(new IntV(64)))));
        assertThrows(ParamException.class, () -> ParamValidator.coerce(HOLES,
                new ListV(List.of(new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0), new IntV(0),
                        new IntV(0), new IntV(0)))));
    }

    private static PartType sample() {
        return PartType.builder("test:thing", PartCategory.STRUCTURE).displayNameKey("k")
                .params(WIDTH, KIND, SIDE, MATERIAL).build();
    }

    @Test
    void validateReportsEveryProblemAsParamRangeWithTheParameterAsKey() {
        Map<String, ParamValue> given = Map.of("width", new IntV(2), "kind", new StrV("dome"), "nope", new IntV(1));
        ParamValidator.Result r = ParamValidator.validate("thing-1", sample(), given);
        assertTrue(r.issues().stream().allMatch(i -> i.code() == IssueCode.E_PARAM_RANGE));
        List<String> ids = r.issues().stream().map(Issue::id).toList();
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#width"), ids.toString());
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#kind"), ids.toString());
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#nope"), "unknown name: " + ids);
        assertTrue(ids.contains("E-PARAM-RANGE:thing-1#side"), "missing required parameter: " + ids);
        Issue width = r.issues().stream().filter(i -> i.id().endsWith("#width")).findFirst().orElseThrow();
        assertEquals("3", width.data().get("min"));
        assertEquals("64", width.data().get("max"));
    }

    @Test
    void validateReturnsTypedParametersWithoutFillingDefaults() {
        Map<String, ParamValue> given = Map.of("width", new NumV(9.0), "kind", new StrV("hip"), "side", new StrV("north"));
        ParamValidator.Result r = ParamValidator.validate("thing-1", sample(), given);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(new IntV(9), r.typed().get("width"));
        assertEquals(new EnumV("hip"), r.typed().get("kind"));
        assertEquals(3, r.typed().size(), "defaults are resolved at compile time, not stored");
    }

    @Test
    void paramsResolveDefaultsAndReadTypedValues() {
        Params p = Params.resolve(sample(), Map.of("side", new EnumV("south"), "width", new IntV(9)));
        assertEquals(9, p.i("width"));
        assertEquals("gable", p.s("kind"));
        assertEquals("south", p.s("side"));
        assertEquals("wall", p.s("material"));
        assertThrows(IllegalArgumentException.class, () -> p.i("nope"));
    }
}
```

`PartTypeRegistryTest.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartTypeRegistryTest {
    private static PartType part(String id, Visibility visibility, String key) {
        return PartType.builder(id, PartCategory.STRUCTURE).visibility(visibility).displayNameKey(key)
                .params(ParamSpec.integer("n", 1, 5, 2)).build();
    }

    private static PartTypeRegistry registry(int defaultN) {
        return PartTypeRegistry.builder()
                .register(PartType.builder("test:b", PartCategory.STRUCTURE).displayNameKey("b")
                        .params(ParamSpec.integer("n", 1, 5, defaultN)).build())
                .register(part("test:a", Visibility.USER, "a"))
                .register(part("test:hidden", Visibility.IMPLICIT, null))
                .defaultPalette(Map.of("wall", "minecraft:stone"))
                .build();
    }

    @Test
    void partsAreSortedByIdAndUserPartsExcludeImplicit() {
        PartTypeRegistry r = registry(2);
        assertEquals(List.of("test:a", "test:b", "test:hidden"), r.all().stream().map(PartType::id).toList());
        assertEquals(List.of("test:a", "test:b"), r.userParts().stream().map(PartType::id).toList());
        assertTrue(r.contains("test:hidden"));
        assertTrue(r.find("test:nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> r.get("test:nope"));
    }

    @Test
    void versionIsStableAndSensitiveToPartsAndPalette() {
        assertEquals(registry(2).version(), registry(2).version());
        assertNotEquals(registry(2).version(), registry(3).version(), "a changed default changes the version");
        PartTypeRegistry otherPalette = PartTypeRegistry.builder()
                .register(part("test:a", Visibility.USER, "a")).defaultPalette(Map.of("wall", "minecraft:bricks")).build();
        PartTypeRegistry samePalette = PartTypeRegistry.builder()
                .register(part("test:a", Visibility.USER, "a")).defaultPalette(Map.of("wall", "minecraft:stone")).build();
        assertNotEquals(otherPalette.version(), samePalette.version());
        assertEquals(64, registry(2).version().length());
    }

    @Test
    void registrationOrderDoesNotChangeTheVersion() {
        PartTypeRegistry ab = PartTypeRegistry.builder().register(part("test:a", Visibility.USER, "a"))
                .register(part("test:b", Visibility.USER, "b")).build();
        PartTypeRegistry ba = PartTypeRegistry.builder().register(part("test:b", Visibility.USER, "b"))
                .register(part("test:a", Visibility.USER, "a")).build();
        assertEquals(ab.version(), ba.version());
    }

    @Test
    void duplicateIdsAreRejected() {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder().register(part("test:a", Visibility.USER, "a"));
        assertThrows(IllegalArgumentException.class, () -> b.register(part("test:a", Visibility.USER, "a")));
    }

    @Test
    void partTypeInvariants() {
        assertThrows(IllegalArgumentException.class, () -> part("Bad Id", Visibility.USER, "k"));
        assertThrows(IllegalArgumentException.class, () -> part("test:user-without-name", Visibility.USER, null));
        assertThrows(IllegalArgumentException.class, () -> PartType.builder("test:dup", PartCategory.STRUCTURE)
                .displayNameKey("k").params(ParamSpec.integer("n", 1, 2, 1), ParamSpec.integer("n", 1, 2, 1)).build());
        PartType t = part("test:a", Visibility.USER, "a");
        assertTrue(t.param("n").isPresent());
        assertFalse(t.param("x").isPresent());
        assertEquals(EffectKind.NONE, t.effect().kind());
    }

    @Test
    void suggestReturnsNearbyIds() {
        PartTypeRegistry r = registry(2);
        assertEquals(List.of("test:a"), r.suggest("test:aa", 1));
        assertTrue(r.suggest("zzz:qqqqqq", 3).size() <= 3);
    }
}
```

`MaterialFamiliesTest.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaterialFamiliesTest {
    @Test
    void woodStoneAndBrickFamilies() {
        MaterialFamilies.Family oak = MaterialFamilies.family("minecraft:oak_planks").orElseThrow();
        assertEquals("minecraft:oak_stairs", oak.stairs());
        assertEquals("minecraft:oak_slab", oak.slab());
        MaterialFamilies.Family red = MaterialFamilies.family("minecraft:red_nether_bricks").orElseThrow();
        assertEquals("minecraft:red_nether_brick_stairs", red.stairs());
        assertEquals("minecraft:stone_brick_slab", MaterialFamilies.family("minecraft:stone_bricks").orElseThrow().slab());
    }

    @Test
    void smoothStoneHasNoStairsButHasSlab() {
        MaterialFamilies.Family f = MaterialFamilies.family("minecraft:smooth_stone").orElseThrow();
        assertNull(f.stairs());
        assertEquals("minecraft:smooth_stone_slab", f.slab());
    }

    @Test
    void terracottaAndUnknownBlocksHaveNoFamily() {
        assertTrue(MaterialFamilies.family("minecraft:red_terracotta").isEmpty(), "terracotta has no stairs in vanilla");
        assertTrue(MaterialFamilies.family("create:brass_casing").isEmpty());
    }

    @Test
    void everyEntryFollowsTheNamingShape() {
        for (String id : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(id).orElseThrow();
            assertEquals(id, f.full());
            if (f.stairs() != null) {
                assertTrue(f.stairs().endsWith("_stairs"), f.stairs());
            }
            assertTrue(f.slab().endsWith("_slab"), f.slab());
            assertTrue(id.startsWith("minecraft:"));
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.parts.*" --console=plain`
Expected: FAIL(`cannot find symbol`)。

- [ ] **Step 3: 実装する**

列挙(すべて`package io.github.khayashi4337.micradrone.build.parts;`):
```java
// PartCategory.java
public enum PartCategory { STRUCTURE, OPENING, ROOF, DECOR, POWER, TRANSMISSION, PROCESSING, LOGISTICS, STORAGE, FLUID, AERO, MODULE }
// Visibility.java  (USER: shown to the AI and in images; IMPLICIT: created by another part's placement, never offered)
public enum Visibility { USER, IMPLICIT }
// ParamType.java
public enum ParamType { INT, NUM, BOOL, STR, ENUM, MATERIAL, INT_LIST }
// PortKind.java
public enum PortKind { ROTATION_IN, ROTATION_OUT, ITEM_IN, ITEM_OUT, FLUID_IN, FLUID_OUT, REDSTONE, HEAT, DOCK }
// PlacerId.java
public enum PlacerId { SIMPLE, BELT, ARM, MULTIBLOCK, ASSEMBLY }
// VerifyMode.java
public enum VerifyMode { EXACT, STATE_SUBSET, BLOCK_ONLY, ASSEMBLED_AWAY }
// EffectKind.java
public enum EffectKind { NONE, BREAK, FLUID, PROJECTILE, MOVE_STRUCTURE }
// AssemblyKind.java
public enum AssemblyKind { WINDMILL, BEARING, PHYSICS_ASSEMBLER }
// BuildPhase.java  (ascending order is the construction order)
public enum BuildPhase { SITE_PREP, STRUCTURE, ENVELOPE, POWER, UPSTREAM, DOWNSTREAM, LOGISTICS, ASSEMBLE, DECORATION, FINISH }
```
各ファイルにjavadocを1行付ける(上の括弧内)。

`ParamSpec.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.List;
import java.util.Objects;

/**
 * One parameter of a part: name, type, range, default. A null default means the parameter is required.
 * For STR the {@code max} is the maximum length; for INT_LIST {@code min}/{@code max} bound each element and
 * {@code maxItems} bounds the length.
 */
public record ParamSpec(String name, ParamType type, String unit, ParamValue min, ParamValue max,
                        ParamValue defaultValue, List<String> enumValues, int maxItems) {
    public ParamSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        unit = unit == null ? "" : unit;
        enumValues = List.copyOf(enumValues == null ? List.of() : enumValues);
        if (type == ParamType.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("enum parameter " + name + " needs values");
        }
    }

    public boolean required() {
        return defaultValue == null;
    }

    public static ParamSpec integer(String name, int min, int max, Integer defaultValue) {
        return new ParamSpec(name, ParamType.INT, "", new IntV(min), new IntV(max),
                defaultValue == null ? null : new IntV(defaultValue), List.of(), 0);
    }

    public static ParamSpec bool(String name, boolean defaultValue) {
        return new ParamSpec(name, ParamType.BOOL, "", null, null, new BoolV(defaultValue), List.of(), 0);
    }

    public static ParamSpec enumOf(String name, String defaultValue, String... values) {
        return new ParamSpec(name, ParamType.ENUM, "", null, null,
                defaultValue == null ? null : new EnumV(defaultValue), List.of(values), 0);
    }

    public static ParamSpec material(String name, String defaultRole) {
        return new ParamSpec(name, ParamType.MATERIAL, "", null, null, new MaterialV(defaultRole), List.of(), 0);
    }

    public static ParamSpec text(String name, int maxLength, String defaultValue) {
        return new ParamSpec(name, ParamType.STR, "", null, new IntV(maxLength),
                defaultValue == null ? null : new StrV(defaultValue), List.of(), 0);
    }

    public static ParamSpec intList(String name, int min, int max, int maxItems) {
        return new ParamSpec(name, ParamType.INT_LIST, "", new IntV(min), new IntV(max), new ListV(List.of()),
                List.of(), maxItems);
    }
}
```

`PortSpec.java`・`VolumeSpec.java`・`VersionRange.java`・`EffectSpec.java`・`AssemblyExpectation.java`・`AssemblySpec.java`・`ModelRef.java`:
```java
// PortSpec.java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** A connection point of a part, in the part's local frame. {@code accepts} lists conditions the other end must meet. */
public record PortSpec(String name, PortKind kind, LocalPos offset, Dir6 facing, Set<String> accepts) {
    public PortSpec {
        accepts = Collections.unmodifiableSortedSet(new TreeSet<>(accepts == null ? Set.of() : accepts));
    }
}

// VolumeSpec.java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The space a part occupies: fixed boxes plus optional parameter-dependent sizes. Building parts (micra:*)
 * are {@link #GENERATED}: the generator decides, so the generated cells are the occupied volume.
 */
public record VolumeSpec(List<Box> boxes, Map<String, String> sizeFromParams) {
    public static final VolumeSpec GENERATED = new VolumeSpec(List.of(), Map.of("mode", "generated"));

    public VolumeSpec {
        boxes = List.copyOf(boxes == null ? List.of() : boxes);
        sizeFromParams = Collections.unmodifiableSortedMap(new TreeMap<>(sizeFromParams == null ? Map.of() : sizeFromParams));
    }
}

// VersionRange.java
package io.github.khayashi4337.micradrone.build.parts;

/** The mod version range a part needs (e.g. create [6.0.10,6.1.0)); outside it the part is disabled (D-13). */
public record VersionRange(String modId, String mavenRange) {
    public static final VersionRange ALWAYS = new VersionRange("", "");
}

// EffectSpec.java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;

/** How far a part acts on the world (D-24). Parts that break blocks, move liquids or fire must declare it. */
public record EffectSpec(EffectKind kind, Box reachLocal) {
    public static final EffectSpec NONE = new EffectSpec(EffectKind.NONE, null);
}

// AssemblyExpectation.java
package io.github.khayashi4337.micradrone.build.parts;

/** What must exist after an assembly step. The kind depends on the assembly (settled by spike S-8). */
public sealed interface AssemblyExpectation {
    record ContraptionExpectation(int entityCount, int movedBlockCount) implements AssemblyExpectation {
    }

    record SubLevelExpectation(int subLevelCount, int movedBlockCount) implements AssemblyExpectation {
    }
}

// AssemblySpec.java
package io.github.khayashi4337.micradrone.build.parts;

/** How a part that vanishes into a moving structure (windmill sails, airship) is assembled and undone. */
public record AssemblySpec(AssemblyKind kind, String triggerPort, AssemblyExpectation expect, String disassembleAction) {
}

// ModelRef.java
package io.github.khayashi4337.micradrone.build.parts;

/** Name of a behaviour model (kinetic or power source) used by the factory analysis. */
public record ModelRef(String modelId) {
}
```

`PartType.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** One entry of the part registry (design doc 01, section 3). */
public record PartType(String id, PartCategory category, Visibility visibility, String displayNameKey,
                       String visualDescription, List<ParamSpec> params, List<PortSpec> ports, VolumeSpec volume,
                       VersionRange requires, PlacerId placer, VerifyMode verify, Set<String> volatileProps,
                       EffectSpec effect, AssemblySpec assembly, ModelRef kineticModel, BuildPhase phase) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+:[a-z0-9_]+");

    public PartType {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("part id must look like namespace:name but was " + id);
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(visibility, "visibility");
        if (visibility == Visibility.USER && (displayNameKey == null || displayNameKey.isBlank())) {
            throw new IllegalArgumentException("USER part " + id + " needs a displayNameKey");
        }
        visualDescription = visualDescription == null ? "" : visualDescription;
        params = List.copyOf(params == null ? List.of() : params);
        ports = List.copyOf(ports == null ? List.of() : ports);
        Set<String> seen = new HashSet<>();
        for (ParamSpec p : params) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException("duplicate parameter " + p.name() + " in " + id);
            }
        }
        seen.clear();
        for (PortSpec p : ports) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException("duplicate port " + p.name() + " in " + id);
            }
        }
        volume = volume == null ? VolumeSpec.GENERATED : volume;
        requires = requires == null ? VersionRange.ALWAYS : requires;
        placer = placer == null ? PlacerId.SIMPLE : placer;
        verify = verify == null ? VerifyMode.EXACT : verify;
        volatileProps = Collections.unmodifiableSortedSet(new TreeSet<>(volatileProps == null ? Set.of() : volatileProps));
        effect = effect == null ? EffectSpec.NONE : effect;
        phase = phase == null ? BuildPhase.STRUCTURE : phase;
    }

    public Optional<ParamSpec> param(String name) {
        for (ParamSpec p : params) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public Optional<PortSpec> port(String name) {
        for (PortSpec p : ports) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static Builder builder(String id, PartCategory category) {
        return new Builder(id, category);
    }

    public static final class Builder {
        private final String id;
        private final PartCategory category;
        private Visibility visibility = Visibility.USER;
        private String displayNameKey;
        private String visualDescription = "";
        private final List<ParamSpec> params = new ArrayList<>();
        private final List<PortSpec> ports = new ArrayList<>();
        private VolumeSpec volume = VolumeSpec.GENERATED;
        private VersionRange requires = VersionRange.ALWAYS;
        private PlacerId placer = PlacerId.SIMPLE;
        private VerifyMode verify = VerifyMode.EXACT;
        private Set<String> volatileProps = Set.of();
        private EffectSpec effect = EffectSpec.NONE;
        private AssemblySpec assembly;
        private ModelRef kineticModel;
        private BuildPhase phase = BuildPhase.STRUCTURE;

        private Builder(String id, PartCategory category) {
            this.id = id;
            this.category = category;
        }

        public Builder visibility(Visibility v) {
            this.visibility = v;
            return this;
        }

        public Builder displayNameKey(String key) {
            this.displayNameKey = key;
            return this;
        }

        public Builder visualDescription(String text) {
            this.visualDescription = text;
            return this;
        }

        public Builder params(ParamSpec... specs) {
            params.addAll(List.of(specs));
            return this;
        }

        public Builder ports(PortSpec... specs) {
            ports.addAll(List.of(specs));
            return this;
        }

        public Builder volume(VolumeSpec v) {
            this.volume = v;
            return this;
        }

        public Builder requires(VersionRange r) {
            this.requires = r;
            return this;
        }

        public Builder placer(PlacerId p) {
            this.placer = p;
            return this;
        }

        public Builder verify(VerifyMode v) {
            this.verify = v;
            return this;
        }

        public Builder volatileProps(String... names) {
            this.volatileProps = Set.of(names);
            return this;
        }

        public Builder effect(EffectSpec e) {
            this.effect = e;
            return this;
        }

        public Builder assembly(AssemblySpec a) {
            this.assembly = a;
            return this;
        }

        public Builder kineticModel(ModelRef m) {
            this.kineticModel = m;
            return this;
        }

        public Builder phase(BuildPhase p) {
            this.phase = p;
            return this;
        }

        public PartType build() {
            return new PartType(id, category, visibility, displayNameKey, visualDescription, params, ports, volume,
                    requires, placer, verify, volatileProps, effect, assembly, kineticModel, phase);
        }
    }
}
```

`PartTypeJson.java`(登録簿の版ハッシュ用):
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The tree form of a part type, used to compute the registry's version hash. Every field participates. */
public final class PartTypeJson {
    private PartTypeJson() {
    }

    public static Map<String, Object> toTree(PartType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("category", t.category().name());
        m.put("visibility", t.visibility().name());
        m.put("displayNameKey", t.displayNameKey());
        m.put("visualDescription", t.visualDescription());
        List<Object> params = new ArrayList<>();
        for (ParamSpec p : t.params()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type().name());
            pm.put("unit", p.unit());
            pm.put("min", p.min() == null ? null : p.min().toTree());
            pm.put("max", p.max() == null ? null : p.max().toTree());
            pm.put("default", p.defaultValue() == null ? null : p.defaultValue().toTree());
            pm.put("enumValues", new ArrayList<>(p.enumValues()));
            pm.put("maxItems", p.maxItems());
            params.add(pm);
        }
        m.put("params", params);
        List<Object> ports = new ArrayList<>();
        for (PortSpec p : t.ports()) {
            ports.add(portTree(p));
        }
        m.put("ports", ports);
        Map<String, Object> volume = new LinkedHashMap<>();
        List<Object> boxes = new ArrayList<>();
        for (Box b : t.volume().boxes()) {
            boxes.add(PlanJson.boxTree(b));
        }
        volume.put("boxes", boxes);
        volume.put("sizeFromParams", new TreeMap<>(t.volume().sizeFromParams()));
        m.put("volume", volume);
        m.put("requires", Map.of("modId", t.requires().modId(), "range", t.requires().mavenRange()));
        m.put("placer", t.placer().name());
        m.put("verify", t.verify().name());
        m.put("volatileProps", new ArrayList<>(t.volatileProps()));
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("kind", t.effect().kind().name());
        effect.put("reach", t.effect().reachLocal() == null ? null : PlanJson.boxTree(t.effect().reachLocal()));
        m.put("effect", effect);
        m.put("assembly", t.assembly() == null ? null : assemblyTree(t.assembly()));
        m.put("kineticModel", t.kineticModel() == null ? null : t.kineticModel().modelId());
        m.put("phase", t.phase().name());
        return m;
    }

    public static Map<String, Object> portTree(PortSpec p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("name", p.name());
        pm.put("kind", p.kind().name());
        pm.put("offset", PlanJson.posTree(p.offset()));
        pm.put("facing", p.facing().name());
        pm.put("accepts", new ArrayList<>(p.accepts()));
        return pm;
    }

    private static Map<String, Object> assemblyTree(AssemblySpec a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", a.kind().name());
        m.put("triggerPort", a.triggerPort());
        m.put("disassembleAction", a.disassembleAction());
        if (a.expect() instanceof AssemblyExpectation.ContraptionExpectation c) {
            m.put("expect", Map.of("type", "contraption", "count", c.entityCount(), "blocks", c.movedBlockCount()));
        } else if (a.expect() instanceof AssemblyExpectation.SubLevelExpectation s) {
            m.put("expect", Map.of("type", "sublevel", "count", s.subLevelCount(), "blocks", s.movedBlockCount()));
        } else {
            m.put("expect", null);
        }
        return m;
    }
}
```
(`PlanJson.posTree`・`boxTree`は、Task 5で公開にしてある。)

`PartTypeRegistry.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The list of parts that may be used. Everything (AI schemas, image prompts, construction, checks) speaks in
 * these ids only. {@link #version()} is a hash of the registered content and the default palette; manifests,
 * jobs and approvals record it, and anything built against another version is refused.
 */
public final class PartTypeRegistry {
    private final TreeMap<String, PartType> parts;
    private final TreeMap<String, String> defaultPalette;
    private final String version;

    private PartTypeRegistry(TreeMap<String, PartType> parts, TreeMap<String, String> defaultPalette) {
        this.parts = parts;
        this.defaultPalette = defaultPalette;
        List<Object> trees = new ArrayList<>();
        for (PartType t : parts.values()) {
            trees.add(PartTypeJson.toTree(t));
        }
        this.version = Hashing.sha256Hex(CanonicalJson.write(Map.of("parts", trees, "palette", defaultPalette)));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<PartType> find(String id) {
        return Optional.ofNullable(parts.get(id));
    }

    public PartType get(String id) {
        PartType t = parts.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown part " + id);
        }
        return t;
    }

    public boolean contains(String id) {
        return parts.containsKey(id);
    }

    public Collection<PartType> all() {
        return Collections.unmodifiableCollection(parts.values());
    }

    public List<PartType> userParts() {
        List<PartType> out = new ArrayList<>();
        for (PartType t : parts.values()) {
            if (t.visibility() == Visibility.USER) {
                out.add(t);
            }
        }
        return out;
    }

    public Map<String, String> defaultPalette() {
        return Collections.unmodifiableMap(defaultPalette);
    }

    public String version() {
        return version;
    }

    /** Ids close to {@code unknownId} (edit distance), nearest first, for "did you mean" hints. */
    public List<String> suggest(String unknownId, int limit) {
        List<Map.Entry<Integer, String>> scored = new ArrayList<>();
        for (String id : parts.keySet()) {
            scored.add(Map.entry(editDistance(unknownId, id), id));
        }
        scored.sort(Map.Entry.<Integer, String>comparingByKey().thenComparing(Map.Entry.comparingByValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            out.add(scored.get(i).getValue());
        }
        return out;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    public static final class Builder {
        private final TreeMap<String, PartType> parts = new TreeMap<>();
        private final TreeMap<String, String> palette = new TreeMap<>();

        public Builder register(PartType type) {
            if (parts.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("part already registered: " + type.id());
            }
            return this;
        }

        public Builder defaultPalette(Map<String, String> roles) {
            palette.putAll(roles);
            return this;
        }

        public PartTypeRegistry build() {
            return new PartTypeRegistry(new TreeMap<>(parts), new TreeMap<>(palette));
        }
    }
}
```

`ParamException.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

/** A parameter value does not fit its spec; the message says how, in Japanese for the user. */
public class ParamException extends Exception {
    public ParamException(String message) {
        super(message);
    }
}
```

`ParamValidator.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Fixes the exact type of loosely read values against a {@link ParamSpec} and checks ranges. */
public final class ParamValidator {
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");

    private ParamValidator() {
    }

    public record Result(Map<String, ParamValue> typed, List<Issue> issues) {
    }

    public static ParamValue coerce(ParamSpec spec, ParamValue loose) throws ParamException {
        return switch (spec.type()) {
            case INT -> coerceInt(spec, loose);
            case NUM -> coerceNum(spec, loose);
            case BOOL -> {
                if (loose instanceof BoolV b) {
                    yield b;
                }
                throw new ParamException("真偽(True/False)が必要です");
            }
            case STR -> {
                if (!(loose instanceof StrV s)) {
                    throw new ParamException("文字列が必要です");
                }
                if (spec.max() instanceof IntV max && s.value().length() > max.value()) {
                    throw new ParamException(max.value() + "字以内にしてください");
                }
                yield s;
            }
            case ENUM -> {
                String text = loose instanceof StrV s ? s.value() : loose instanceof EnumV e ? e.value() : null;
                if (text == null) {
                    throw new ParamException("次のどれかの文字列が必要です: " + spec.enumValues());
                }
                if (!spec.enumValues().contains(text)) {
                    throw new ParamException("\"" + text + "\"は使えません。使えるのは: " + spec.enumValues());
                }
                yield new EnumV(text);
            }
            case MATERIAL -> {
                String text = loose instanceof StrV s ? s.value() : loose instanceof MaterialV m ? m.value() : null;
                if (text == null || !(ROLE.matcher(text).matches() || BLOCK_ID.matcher(text).matches())) {
                    throw new ParamException("素材は、役割の名前(例: roof)かブロックID(例: minecraft:stone)で指定してください");
                }
                yield new MaterialV(text);
            }
            case INT_LIST -> coerceIntList(spec, loose);
        };
    }

    private static ParamValue coerceInt(ParamSpec spec, ParamValue loose) throws ParamException {
        int value;
        if (loose instanceof IntV i) {
            value = i.value();
        } else if (loose instanceof NumV n && n.value() == Math.rint(n.value()) && Math.abs(n.value()) <= Integer.MAX_VALUE) {
            value = (int) n.value();
        } else {
            throw new ParamException("整数が必要です");
        }
        checkRange(spec, value);
        return new IntV(value);
    }

    private static ParamValue coerceNum(ParamSpec spec, ParamValue loose) throws ParamException {
        double value;
        if (loose instanceof IntV i) {
            value = i.value();
        } else if (loose instanceof NumV n) {
            value = n.value();
        } else {
            throw new ParamException("数が必要です");
        }
        boolean tooLow = spec.min() != null && value < asDouble(spec.min());
        boolean tooHigh = spec.max() != null && value > asDouble(spec.max());
        if (tooLow || tooHigh) {
            throw new ParamException(rangeText(spec));
        }
        return new NumV(value);
    }

    private static ParamValue coerceIntList(ParamSpec spec, ParamValue loose) throws ParamException {
        if (!(loose instanceof ListV list)) {
            throw new ParamException("整数の並び(例: [1, 2])が必要です");
        }
        if (spec.maxItems() > 0 && list.value().size() > spec.maxItems()) {
            throw new ParamException("要素は" + spec.maxItems() + "個までです");
        }
        List<ParamValue> out = new ArrayList<>();
        for (ParamValue item : list.value()) {
            out.add(coerceInt(spec, item));
        }
        return new ListV(out);
    }

    private static void checkRange(ParamSpec spec, int value) throws ParamException {
        boolean tooLow = spec.min() instanceof IntV min && value < min.value();
        boolean tooHigh = spec.max() instanceof IntV max && value > max.value();
        if (tooLow || tooHigh) {
            throw new ParamException(rangeText(spec));
        }
    }

    private static String rangeText(ParamSpec spec) {
        String lo = spec.min() == null ? "" : String.valueOf(spec.min().toTree());
        String hi = spec.max() == null ? "" : String.valueOf(spec.max().toTree());
        return lo + "〜" + hi + "の範囲にしてください";
    }

    private static double asDouble(ParamValue v) {
        return v instanceof IntV i ? i.value() : v instanceof NumV n ? n.value() : 0;
    }

    /** Types every given value; unknown names, wrong types, out-of-range values and missing required ones are E-PARAM-RANGE. */
    public static Result validate(String nodeId, PartType type, Map<String, ParamValue> given) {
        Map<String, ParamValue> typed = new TreeMap<>();
        List<Issue> issues = new ArrayList<>();
        for (Map.Entry<String, ParamValue> e : given.entrySet()) {
            String name = e.getKey();
            ParamSpec spec = type.param(name).orElse(null);
            if (spec == null) {
                List<String> known = type.params().stream().map(ParamSpec::name).toList();
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, name, List.of(nodeId),
                        type.id() + "に「" + name + "」というパラメータはありません(使えるのは " + known + ")",
                        Map.of("param", name), List.of(new FixHint("USE_PARAM", Map.of("names", String.join(",", known))))));
                continue;
            }
            try {
                typed.put(name, coerce(spec, e.getValue()));
            } catch (ParamException ex) {
                Map<String, String> data = new TreeMap<>();
                data.put("param", name);
                if (spec.min() != null) {
                    data.put("min", String.valueOf(spec.min().toTree()));
                }
                if (spec.max() != null) {
                    data.put("max", String.valueOf(spec.max().toTree()));
                }
                if (!spec.enumValues().isEmpty()) {
                    data.put("allowed", String.join(",", spec.enumValues()));
                }
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, name, List.of(nodeId),
                        type.id() + "の「" + name + "」: " + ex.getMessage(), data, List.of()));
            }
        }
        for (ParamSpec spec : type.params()) {
            if (spec.required() && !given.containsKey(spec.name())) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, spec.name(), List.of(nodeId),
                        type.id() + "の「" + spec.name() + "」は必須です", Map.of("param", spec.name()), List.of()));
            }
        }
        return new Result(typed, issues);
    }
}
```
(範囲外メッセージの`spec.min()`が`null`の`NUM`のとき`asDouble(null)`が0を返すので、`coerceNum`のメッセージは`min`/`max`が両方あるときだけ範囲を出す実装に整える。)

`Params.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Typed parameters with defaults filled in; what a generator reads. */
public final class Params {
    private final Map<String, ParamValue> values;

    private Params(Map<String, ParamValue> values) {
        this.values = values;
    }

    public static Params resolve(PartType type, Map<String, ParamValue> typed) {
        Map<String, ParamValue> all = new TreeMap<>();
        for (ParamSpec spec : type.params()) {
            ParamValue v = typed.get(spec.name());
            if (v != null) {
                all.put(spec.name(), v);
            } else if (spec.defaultValue() != null) {
                all.put(spec.name(), spec.defaultValue());
            }
        }
        return new Params(all);
    }

    private ParamValue get(String name) {
        ParamValue v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("parameter not available: " + name);
        }
        return v;
    }

    public int i(String name) {
        if (get(name) instanceof IntV v) {
            return v.value();
        }
        throw new IllegalArgumentException(name + " is not an integer");
    }

    public double d(String name) {
        ParamValue v = get(name);
        if (v instanceof NumV n) {
            return n.value();
        }
        if (v instanceof IntV i) {
            return i.value();
        }
        throw new IllegalArgumentException(name + " is not a number");
    }

    public boolean b(String name) {
        if (get(name) instanceof BoolV v) {
            return v.value();
        }
        throw new IllegalArgumentException(name + " is not a boolean");
    }

    /** The text of a string, enum or material value. */
    public String s(String name) {
        ParamValue v = get(name);
        if (v instanceof StrV s) {
            return s.value();
        }
        if (v instanceof EnumV e) {
            return e.value();
        }
        if (v instanceof MaterialV m) {
            return m.value();
        }
        throw new IllegalArgumentException(name + " is not text");
    }

    public List<Integer> ints(String name) {
        if (get(name) instanceof ListV list) {
            List<Integer> out = new ArrayList<>();
            for (ParamValue item : list.value()) {
                out.add(((IntV) item).value());
            }
            return out;
        }
        throw new IllegalArgumentException(name + " is not a list");
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }
}
```

`MaterialFamilies.java`(ブロック名は、1.21.1のclient jarのblockstatesで実在を確認済み。`smooth_stone`のみ階段なし):
```java
package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Vanilla block families that have both stairs and slabs, keyed by the full block. Roofs, ramps and trims need the
 * stairs or slab form of a material; a material outside this table (e.g. terracotta, which has no stairs in
 * vanilla) can only be used as a full block. The names were checked against the 1.21.1 client jar's blockstates.
 */
public final class MaterialFamilies {
    public record Family(String full, String stairs, String slab) {
    }

    private static final TreeMap<String, Family> TABLE = new TreeMap<>();

    static {
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
                "bamboo", "crimson", "warped")) {
            add(wood + "_planks", wood + "_stairs", wood + "_slab");
        }
        for (String name : List.of("stone", "cobblestone", "mossy_cobblestone", "sandstone", "smooth_sandstone",
                "red_sandstone", "smooth_red_sandstone", "smooth_quartz", "prismarine", "dark_prismarine", "andesite",
                "polished_andesite", "diorite", "polished_diorite", "granite", "polished_granite", "cobbled_deepslate",
                "polished_deepslate", "blackstone", "polished_blackstone", "cut_copper", "exposed_cut_copper",
                "weathered_cut_copper", "oxidized_cut_copper", "tuff", "polished_tuff")) {
            add(name, name + "_stairs", name + "_slab");
        }
        // the block name is plural, the stairs/slab name is singular
        for (String name : List.of("stone_brick", "mossy_stone_brick", "brick", "nether_brick", "red_nether_brick",
                "prismarine_brick", "deepslate_brick", "deepslate_tile", "polished_blackstone_brick", "end_stone_brick",
                "mud_brick", "tuff_brick")) {
            add(name + "s", name + "_stairs", name + "_slab");
        }
        add("quartz_block", "quartz_stairs", "quartz_slab");
        add("purpur_block", "purpur_stairs", "purpur_slab");
        add("smooth_stone", null, "smooth_stone_slab");
    }

    private MaterialFamilies() {
    }

    private static void add(String full, String stairs, String slab) {
        String id = "minecraft:" + full;
        TABLE.put(id, new Family(id, stairs == null ? null : "minecraft:" + stairs, "minecraft:" + slab));
    }

    public static Optional<Family> family(String fullBlockId) {
        return Optional.ofNullable(TABLE.get(fullBlockId));
    }

    public static List<String> fullBlockIds() {
        return Collections.unmodifiableList(new ArrayList<>(TABLE.keySet()));
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.parts.*" --tests "io.github.khayashi4337.micradrone.build.model.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 部品の型・登録簿(版ハッシュ)・パラメータ検証・素材の族を追加(自然言語→工場建設 P3 Task 6)

素材の族のブロック名は、1.21.1のclient jarのblockstatesで実在を確認した。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 建築部品22種の定義(`BuildingParts`)と表示名

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/parts/BuildingParts.java`
- Modify: `src/main/resources/assets/micradrone/lang/en_us.json`(部品の表示名22個。ファイルはCRLF)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/parts/BuildingPartsTest.java`

**Interfaces:**
- Consumes: Task 6の`PartType`・`ParamSpec`・`PartTypeRegistry`・`PartCategory`・`BuildPhase`・`VerifyMode`・`ParamValidator`
- Produces: `BuildingParts.registry()`(建築部品`micra:*` 22種と既定のパレットを持つ、共有の`PartTypeRegistry`)、`BuildingParts.NAMES`(22個の部品名。接頭辞なし。辞書順)、`BuildingParts.ID_PREFIX = "micra:"`、`BuildingParts.DEFAULT_PALETTE`(役割→ブロックID)。パラメータ・既定値・範囲・フェーズは、設計図05 1.1.1節の表のとおり。

- [ ] **Step 1: 失敗するテストを書く**

`BuildingPartsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildingPartsTest {
    private static final List<String> EXPECTED = List.of("balcony", "beam", "catwalk", "chimney", "dock_pad", "door",
            "floor", "foundation", "ladder", "lamp", "planter", "pillar", "railing", "ramp", "road", "roof", "sign",
            "stairs", "structure", "trim", "wall", "window").stream().sorted().toList();

    @Test
    void registersExactlyTheTwentyTwoBuildingParts() {
        PartTypeRegistry r = BuildingParts.registry();
        assertEquals(22, BuildingParts.NAMES.size());
        assertEquals(EXPECTED, BuildingParts.NAMES);
        for (String name : BuildingParts.NAMES) {
            PartType t = r.get(BuildingParts.ID_PREFIX + name);
            assertEquals(Visibility.USER, t.visibility(), name);
            assertEquals("micradrone.part." + name, t.displayNameKey());
            assertFalse(t.visualDescription().isBlank(), name + " needs a visual description");
        }
        assertEquals(22, r.userParts().size());
    }

    @Test
    void everyDefaultPassesItsOwnSpec() throws Exception {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                if (p.defaultValue() != null) {
                    ParamValue coerced = ParamValidator.coerce(p, p.defaultValue());
                    assertEquals(p.defaultValue(), coerced, t.id() + "." + p.name());
                }
            }
        }
    }

    @Test
    void everyMaterialParameterDefaultsToARoleThatExistsInTheDefaultPalette() {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                if (p.type() == ParamType.MATERIAL) {
                    String role = ((ParamValue.MaterialV) p.defaultValue()).value();
                    assertTrue(BuildingParts.DEFAULT_PALETTE.containsKey(role), t.id() + "." + p.name() + " -> " + role);
                }
            }
        }
    }

    @Test
    void requiredParametersAreDeclaredWhereAGuessWouldBeWrong() {
        assertTrue(BuildingParts.registry().get("micra:wall").param("side").orElseThrow().required());
        assertTrue(BuildingParts.registry().get("micra:sign").param("text").orElseThrow().required());
        assertFalse(BuildingParts.registry().get("micra:structure").param("width").orElseThrow().required());
    }

    @Test
    void phasesFollowTheDesignTable() {
        PartTypeRegistry r = BuildingParts.registry();
        for (String n : List.of("foundation", "floor", "pillar", "beam", "chimney", "stairs", "ramp", "structure")) {
            assertEquals(BuildPhase.STRUCTURE, r.get("micra:" + n).phase(), n);
        }
        for (String n : List.of("wall", "roof", "door", "window")) {
            assertEquals(BuildPhase.ENVELOPE, r.get("micra:" + n).phase(), n);
        }
        for (String n : List.of("ladder", "catwalk", "balcony", "railing", "lamp", "sign", "planter", "trim")) {
            assertEquals(BuildPhase.DECORATION, r.get("micra:" + n).phase(), n);
        }
        for (String n : List.of("dock_pad", "road")) {
            assertEquals(BuildPhase.LOGISTICS, r.get("micra:" + n).phase(), n);
        }
    }

    @Test
    void doorsDeclareTheirVolatileState() {
        assertEquals(java.util.Set.of("open", "powered"), BuildingParts.registry().get("micra:door").volatileProps());
    }

    @Test
    void defaultPaletteIsTheDocumentedOne() {
        assertEquals("minecraft:stone_bricks", BuildingParts.DEFAULT_PALETTE.get("wall"));
        assertEquals("minecraft:oak_planks", BuildingParts.DEFAULT_PALETTE.get("roof"));
        assertEquals("minecraft:glass_pane", BuildingParts.DEFAULT_PALETTE.get("glass"));
        assertEquals(22, BuildingParts.DEFAULT_PALETTE.size());
        assertNotNull(BuildingParts.registry().version());
    }

    @Test
    void everyDisplayNameKeyExistsInTheEnglishLanguageFile() throws IOException {
        String lang = Files.readString(Path.of("src/main/resources/assets/micradrone/lang/en_us.json"), StandardCharsets.UTF_8);
        for (String name : BuildingParts.NAMES) {
            assertTrue(lang.contains("\"micradrone.part." + name + "\""), name);
        }
    }
}
```
(`EXPECTED`の並びは`sorted()`した物。`pillar`と`planter`の順に注意し、実装の`NAMES`も`stream().sorted()`で作る。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.parts.BuildingPartsTest" --console=plain`
Expected: FAIL(`BuildingParts`が無い)。

- [ ] **Step 3: 実装する**

`BuildingParts.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The building parts (micra:*): what a plan may build from vanilla blocks. Parameters, ranges, defaults and phases
 * follow design doc 05, section 1.1.1. A test keeps this list, the generators and the language commands in step.
 */
public final class BuildingParts {
    public static final String ID_PREFIX = "micra:";

    private static final String[] DIRS = {"north", "east", "south", "west"};

    /** Role to block id. Part of the registry version hash. */
    public static final Map<String, String> DEFAULT_PALETTE;

    public static final List<String> NAMES;

    private static final PartTypeRegistry REGISTRY;

    static {
        TreeMap<String, String> palette = new TreeMap<>();
        String[][] roles = {
                {"wall", "stone_bricks"}, {"floor", "oak_planks"}, {"roof", "oak_planks"}, {"foundation", "cobblestone"},
                {"pillar", "stone_bricks"}, {"beam", "oak_log"}, {"trim", "stone_bricks"}, {"glass", "glass_pane"},
                {"door", "oak_door"}, {"gate", "oak_fence_gate"}, {"fence", "oak_fence"}, {"stairs", "oak_stairs"},
                {"ramp", "stone"}, {"catwalk", "iron_trapdoor"}, {"chimney", "bricks"}, {"path", "gravel"},
                {"pad", "smooth_stone"}, {"marker", "yellow_concrete"}, {"cargo", "barrel"}, {"sign", "oak_wall_sign"},
                {"planter", "dirt"}, {"plant", "poppy"}};
        for (String[] role : roles) {
            palette.put(role[0], "minecraft:" + role[1]);
        }
        DEFAULT_PALETTE = Collections.unmodifiableMap(palette);

        PartTypeRegistry.Builder b = PartTypeRegistry.builder().defaultPalette(DEFAULT_PALETTE);
        List<PartType> parts = List.of(
                part("structure", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A building: a footprint with floors, the parent of its walls, floors and roof.",
                        ParamSpec.integer("width", 3, 64, 7), ParamSpec.integer("depth", 3, 64, 7),
                        ParamSpec.integer("floors", 1, 8, 1), ParamSpec.integer("floor_height", 3, 8, 4)),
                part("foundation", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A solid base under the building.",
                        ParamSpec.integer("margin", 0, 8, 0), ParamSpec.integer("depth", 1, 8, 1),
                        ParamSpec.material("material", "foundation")),
                part("floor", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A floor of blocks or slabs with optional holes for stairs.",
                        ParamSpec.integer("level", 0, 7, 0), ParamSpec.enumOf("kind", "block", "block", "slab"),
                        ParamSpec.intList("holes", 0, 63, 64), ParamSpec.material("material", "floor")),
                part("wall", PartCategory.STRUCTURE, BuildPhase.ENVELOPE, VerifyMode.EXACT,
                        "A wall along one side of a building.",
                        ParamSpec.enumOf("side", null, DIRS), ParamSpec.integer("level", 0, 7, 0),
                        ParamSpec.integer("height", 0, 16, 0), ParamSpec.integer("thickness", 1, 3, 1),
                        ParamSpec.integer("from", 0, 63, 0), ParamSpec.integer("length", 0, 64, 0),
                        ParamSpec.enumOf("part", "full", "full", "half"), ParamSpec.material("material", "wall")),
                part("pillar", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A column with an optional base and capital.",
                        ParamSpec.integer("height", 1, 32, 4), ParamSpec.bool("base", true), ParamSpec.bool("capital", true),
                        ParamSpec.material("material", "pillar")),
                part("beam", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A straight horizontal or vertical beam.",
                        ParamSpec.enumOf("axis", "u", "u", "v", "w"), ParamSpec.integer("length", 1, 64, 3),
                        ParamSpec.material("material", "beam")),
                part("roof", PartCategory.ROOF, BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET,
                        "A roof of stairs and slabs: gable, hip, flat, shed, sawtooth or monitor.",
                        ParamSpec.enumOf("kind", "gable", "gable", "hip", "flat", "shed", "sawtooth", "monitor"),
                        ParamSpec.integer("overhang", 0, 3, 1), ParamSpec.enumOf("ridge", "auto", "auto", "u", "w"),
                        ParamSpec.enumOf("high_side", "east", DIRS), ParamSpec.bool("gable_fill", true),
                        ParamSpec.integer("tooth", 2, 8, 3), ParamSpec.integer("monitor_width", 1, 5, 1),
                        ParamSpec.integer("monitor_height", 1, 3, 1), ParamSpec.material("material", "roof")),
                doorPart(),
                part("window", PartCategory.OPENING, BuildPhase.ENVELOPE, VerifyMode.BLOCK_ONLY,
                        "A window opening in a wall: pane, wide or arch.",
                        ParamSpec.enumOf("kind", "pane", "pane", "wide", "arch"), ParamSpec.bool("lattice", false),
                        ParamSpec.material("material", "glass")),
                part("stairs", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A staircase rising in one direction.",
                        ParamSpec.integer("steps", 1, 32, 4), ParamSpec.integer("width", 1, 8, 1),
                        ParamSpec.enumOf("dir", "north", DIRS), ParamSpec.material("material", "stairs")),
                part("ladder", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A ladder against a wall.",
                        ParamSpec.integer("height", 1, 32, 3), ParamSpec.enumOf("facing", "north", DIRS)),
                part("catwalk", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A grated walkway with optional railings.",
                        ParamSpec.integer("length", 1, 64, 6), ParamSpec.enumOf("dir", "north", DIRS),
                        ParamSpec.integer("width", 1, 5, 2), ParamSpec.bool("rail", true),
                        ParamSpec.material("material", "catwalk")),
                part("balcony", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A balcony projecting from a wall, with railings.",
                        ParamSpec.integer("width", 1, 16, 3), ParamSpec.integer("depth", 1, 8, 2),
                        ParamSpec.bool("rail", true), ParamSpec.material("material", "floor")),
                part("railing", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A straight run of fence.",
                        ParamSpec.integer("length", 1, 64, 3), ParamSpec.enumOf("dir", "north", DIRS),
                        ParamSpec.integer("height", 1, 3, 1), ParamSpec.material("material", "fence")),
                part("chimney", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A brick chimney with an optional cap.",
                        ParamSpec.integer("height", 2, 32, 6), ParamSpec.integer("size", 1, 3, 1),
                        ParamSpec.bool("cap", true), ParamSpec.material("material", "chimney")),
                part("ramp", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A gentle ramp of alternating slabs.",
                        ParamSpec.integer("length", 2, 32, 6), ParamSpec.enumOf("dir", "north", DIRS),
                        ParamSpec.integer("width", 1, 8, 2), ParamSpec.material("material", "ramp")),
                part("lamp", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A light: standing lantern, hanging lantern, lamp post or torch.",
                        ParamSpec.enumOf("kind", "lantern", "lantern", "hanging", "post", "torch"),
                        ParamSpec.integer("height", 1, 6, 2)),
                part("sign", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A wall sign with up to four short lines of text.",
                        ParamSpec.text("text", 60, null), ParamSpec.material("material", "sign")),
                part("planter", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.EXACT,
                        "A flower bed along a wall.", ParamSpec.integer("width", 1, 8, 3)),
                part("trim", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A decorative course along a wall face.",
                        ParamSpec.integer("length", 1, 64, 3), ParamSpec.enumOf("axis", "horizontal", "horizontal", "vertical"),
                        ParamSpec.enumOf("shape", "block", "block", "slab"), ParamSpec.material("material", "trim")),
                part("dock_pad", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.STATE_SUBSET,
                        "A flat landing pad for airships with a cargo barrel and clear air above.",
                        ParamSpec.integer("width", 5, 64, 9), ParamSpec.integer("depth", 5, 64, 9),
                        ParamSpec.integer("clearance", 4, 64, 16), ParamSpec.integer("cargo_u", 0, 63, 1),
                        ParamSpec.integer("cargo_w", 0, 63, 1), ParamSpec.bool("marker", true),
                        ParamSpec.material("material", "pad")),
                part("road", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.EXACT,
                        "A straight path of gravel.",
                        ParamSpec.integer("length", 1, 128, 8), ParamSpec.enumOf("dir", "north", DIRS),
                        ParamSpec.integer("width", 1, 8, 2), ParamSpec.material("material", "path")));
        List<String> names = new java.util.ArrayList<>();
        for (PartType p : parts) {
            b.register(p);
            names.add(p.id().substring(ID_PREFIX.length()));
        }
        names.sort(null);
        NAMES = Collections.unmodifiableList(names);
        REGISTRY = b.build();
    }

    private BuildingParts() {
    }

    public static PartTypeRegistry registry() {
        return REGISTRY;
    }

    private static PartType doorPart() {
        return PartType.builder(ID_PREFIX + "door", PartCategory.OPENING).displayNameKey("micradrone.part.door")
                .visualDescription("A door opening in a wall: single, double or a wide hangar door.")
                .params(ParamSpec.enumOf("kind", "single", "single", "double", "hangar"), ParamSpec.integer("width", 3, 9, 5),
                        ParamSpec.integer("height", 3, 6, 4), ParamSpec.enumOf("hinge", "left", "left", "right"),
                        ParamSpec.material("material", "door"))
                .volatileProps("open", "powered").verify(VerifyMode.STATE_SUBSET).phase(BuildPhase.ENVELOPE).build();
    }

    private static PartType part(String name, PartCategory category, BuildPhase phase, VerifyMode verify, String visual,
                                 ParamSpec... params) {
        return PartType.builder(ID_PREFIX + name, category).displayNameKey("micradrone.part." + name)
                .visualDescription(visual).params(params).verify(verify).phase(phase).build();
    }
}
```
(`DIRS`は`String[]`で、`enumOf`の可変長引数にそのまま渡せる。)

`en_us.json`に、22個の表示名を足す(CRLFのまま。最後の`}`の前に、カンマ区切りで追記):
```python
# run once from the repository root; keeps the file's CRLF line endings
p = "src/main/resources/assets/micradrone/lang/en_us.json"
s = open(p, encoding="utf-8", newline="").read()
head = s.rstrip()
assert head.endswith("}")
head = head[:-1].rstrip()
names = {
    "structure": "Building", "foundation": "Foundation", "floor": "Floor", "wall": "Wall", "pillar": "Pillar",
    "beam": "Beam", "roof": "Roof", "door": "Door", "window": "Window", "stairs": "Staircase", "ladder": "Ladder",
    "catwalk": "Catwalk", "balcony": "Balcony", "railing": "Railing", "chimney": "Chimney", "ramp": "Ramp",
    "lamp": "Lamp", "sign": "Sign", "planter": "Planter", "trim": "Trim", "dock_pad": "Dock Pad", "road": "Road",
}
add = "".join(',\r\n  "micradrone.part.%s": "%s"' % (k, v) for k, v in names.items())
open(p, "w", encoding="utf-8", newline="").write(head + add + "\r\n}\r\n")
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.parts.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build/parts/BuildingParts.java src/main/resources/assets/micradrone/lang/en_us.json src/test/java/io/github/khayashi4337/micradrone/build/parts/BuildingPartsTest.java
git commit -m "$(cat <<'EOF'
feat: 建築部品22種(micra:*)の定義と既定のパレット、表示名を追加(自然言語→工場建設 P3 Task 7)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 8: `PlanPatcher`(差分の適用)と、テンプレートの型

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/plan/{PatchResult,VerificationOrigin,Verification,TemplateStats,ModuleTemplate,TemplateBundle,PlanPatcher}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/TestParts.java`(テスト用の共有部品)、`src/test/java/io/github/khayashi4337/micradrone/build/plan/PlanPatcherTest.java`
- (`build.plan`は、`build.model`と`build.parts`の上に立つ層。`build.model`が`build.parts`に依存する循環を避けるため、設計図の12節の案から、この層を分けた。Task 23で設計図の12節を直す。)

**Interfaces:**
- Consumes: Task 5の`SemanticPlan`・`PlanPatch`・`PlanOp`・`PlanNode`・`Anchor`・`Connection`・`StyleSpec`・`Site`・`LogisticsPlan`、Task 6・7の`PartTypeRegistry`・`ParamValidator`・`PartType`・`BuildingParts`、Task 4の`Issue`・`IssueCode`・`FixHint`
- Produces:
  - `record PatchResult(SemanticPlan plan, List<Issue> issues)`: `boolean ok()`(`plan != null`)。`ERROR`が1つでもあれば`plan`は`null`
  - `enum VerificationOrigin {BUNDLED_CI, PLAYER_COMMISSIONED}`、`record Verification(VerificationOrigin origin, String analyzerVersion, String resultHash, long atMillis)`、`record TemplateStats(double rpm, double stressSu, Map<String,Double> perMinByProduct)`
  - `record ModuleTemplate(int schemaVersion, String id, String displayNameKey, PartCategory category, VersionRange requires, Box footprint, List<PortSpec> ports, List<PlanNode> nodes, List<Connection> internal, TemplateStats stats, Verification verification, Set<String> tags)`: `String hash()`(構造だけのSHA-256。`stats`・`verification`は含めない。`nodes`・`internal`・`ports`・`tags`は辞書順)、`Optional<PortSpec> port(String)`
  - `record TemplateBundle(List<ModuleTemplate> templates)`: `TemplateBundle.EMPTY`、`Optional<ModuleTemplate> find(String id)`、`List<Issue> verifyAgainst(Map<String,String> knownHashById)`(手持ちに無いID・ハッシュ不一致は`E-TEMPLATE-UNVERIFIED`)
  - `PlanPatcher(PartTypeRegistry registry, TemplateBundle templates)`: `PatchResult apply(SemanticPlan plan, PlanPatch patch)`、`PatchResult normalize(SemanticPlan loose)`(JSONなどから読んだ、型が推測のままの計画を、登録簿に照らして型付けし直す。空の計画に、全ノード・接続・敷地・スタイルを足す差分を適用して作る)

**振る舞い(設計図01 2節。テストで固定する):**
1. `baseRevision != plan.revision()` → `E-PATCH-STALE`、`plan=null`。
2. 操作は先頭から順に適用する。`ERROR`が1つでもあれば全体を拒否(`plan=null`)。元の`SemanticPlan`は変えない。成功時の`revision`=`baseRevision+1`、`parentRevision`=`baseRevision`。
3. `AddNode`: IDが`[a-z0-9-]{1,48}`でなければ`E-ID-INVALID`、既存なら`E-ID-DUPLICATE`。`type`が登録簿にもテンプレートにも無ければ`E-UNKNOWN-PART`(近いIDを`FixHint(USE_PART)`で)。登録簿の部品はパラメータを`ParamValidator`で型付けし(既定値は補わない)、テンプレートのインスタンスはパラメータを持てない(`E-PARAM-RANGE`)。`parent`が無ければ`E-ANCHOR`。`OnSurface`の対象が無い・`micra:wall`でない・自分自身、`side`が`OUTER`/`INNER`でない、`u`・`v`が負、は`E-ANCHOR`。`InSlot`は構造だけ確かめる(解決は展開時)。
4. `UpdateParams`: 対象が無ければ`E-ANCHOR`。既存の型付き値と重ねた全体を検証する。
5. `MoveNode`: 対象が無ければ`E-ANCHOR`。新しい`anchor`を3と同じに検査。
6. `RemoveNode`: 対象が無ければ`E-ANCHOR`。子・`OnSurface`で参照するノード・接続(端点・`via`・`avoid`)・物流(`linkedPorts`・`dockingConnectorNodeIds`)が残っていれば`E-ANCHOR`(依存先を`data.dependents`と`FixHint(REMOVE_FIRST)`で)。
7. `AddConnection`: ID検査・重複(`E-ID-INVALID`/`E-ID-DUPLICATE`)。端点のノードが無い、ポートが無い(登録簿の部品の`ports`、またはテンプレートの`ports`に無い)、`via`のノードが無い、は`E-CONN-INVALID`。`RemoveConnection`: 無ければ`E-CONN-INVALID`。
8. `SetStyle`: 役割名が`[a-z][a-z0-9_]*`でない、素材が`namespace:name`でない、は`E-PARAM-RANGE`(`key`=`style`)。`SetSite`: `dimension`が`namespace:name`でなければ`E-PARAM-RANGE`。`SetLogistics`: ドック・経路のIDの重複、経路・流れが存在しないドックを指す、は`E-CONN-INVALID`。`null`は消去。

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/io/github/khayashi4337/micradrone/build/TestParts.java`(以降のテストが共有する):
```java
package io.github.khayashi4337.micradrone.build;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.parts.PortKind;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import io.github.khayashi4337.micradrone.build.plan.ModuleTemplate;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared fixtures: the building parts plus a few made-up machine parts that have ports. */
public final class TestParts {
    private TestParts() {
    }

    public static PartTypeRegistry registry() {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder().defaultPalette(BuildingParts.DEFAULT_PALETTE);
        for (PartType t : BuildingParts.registry().all()) {
            b.register(t);
        }
        b.register(PartType.builder("test:motor", PartCategory.POWER).displayNameKey("t.motor")
                .ports(new PortSpec("out", PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of())).build());
        b.register(PartType.builder("test:press", PartCategory.PROCESSING).displayNameKey("t.press")
                .ports(new PortSpec("power_in", PortKind.ROTATION_IN, new LocalPos(-1, 0, 0), Dir6.WEST, Set.of()),
                        new PortSpec("item_in", PortKind.ITEM_IN, new LocalPos(0, 1, 0), Dir6.UP, Set.of()),
                        new PortSpec("item_out", PortKind.ITEM_OUT, new LocalPos(0, -1, 0), Dir6.DOWN, Set.of())).build());
        b.register(PartType.builder("test:shaft", PartCategory.TRANSMISSION).displayNameKey("t.shaft")
                .ports(new PortSpec("in", PortKind.ROTATION_IN, new LocalPos(-1, 0, 0), Dir6.WEST, Set.of()),
                        new PortSpec("out", PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of())).build());
        return b.build();
    }

    public static PlanNode at(String id, String type, String parent, int u, int v, int w) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE), Map.of(), Set.of(), "");
    }

    /** A module made of a motor and a shaft child of it; exposes the shaft's output. */
    public static ModuleTemplate lineTemplate() {
        return new ModuleTemplate(1, "mod:test_line", "t.line", PartCategory.MODULE, VersionRange.ALWAYS,
                new Box(0, 0, 0, 3, 0, 1),
                List.of(new PortSpec("out", PortKind.ROTATION_OUT, new LocalPos(3, 0, 0), Dir6.EAST, Set.of())),
                List.of(at("motor", "test:motor", null, 2, 0, 1), at("shaft", "test:shaft", "motor", 1, 0, 0)),
                List.of(new Connection("link", new PortRef("motor", "out"), new PortRef("shaft", "in"), ConnKind.ROTATION,
                        new Routing.Explicit(List.of()), Constraints.NONE)),
                null, null, Set.of("test"));
    }

    public static TemplateBundle bundle() {
        return new TemplateBundle(List.of(lineTemplate()));
    }
}
```

`PlanPatcherTest.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanPatcherTest {
    private final PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TestParts.bundle());

    private static PlanNode node(String id, String type, String parent, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), params, Set.of(), "");
    }

    private static PlanPatch patch(int base, PlanOp... ops) {
        return new PlanPatch("p-" + base, base, "test", List.of(ops));
    }

    private static SemanticPlan hut(PlanPatcher patcher) {
        PatchResult r = patcher.apply(SemanticPlan.empty("plan"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(7)))),
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north"))))));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static List<String> codes(PatchResult r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
    }

    @Test
    void addingNodesTypesParametersAndBumpsTheRevision() {
        SemanticPlan plan = hut(patcher);
        assertEquals(1, plan.revision());
        assertEquals(0, plan.parentRevision());
        assertEquals(new IntV(7), plan.node("hut").orElseThrow().params().get("width"));
        assertEquals(new EnumV("north"), plan.node("wall-n").orElseThrow().params().get("side"));
        assertEquals(1, plan.node("hut").orElseThrow().params().size(), "defaults are not stored");
    }

    @Test
    void aStalePatchIsRefusedWhole() {
        SemanticPlan plan = hut(patcher);
        PatchResult r = patcher.apply(plan, patch(0, new PlanOp.RemoveNode("wall-n")));
        assertFalse(r.ok());
        assertEquals(List.of("E-PATCH-STALE"), codes(r));
    }

    @Test
    void anErrorRejectsThePatchAndLeavesTheOriginalUntouched() {
        SemanticPlan plan = hut(patcher);
        PatchResult r = patcher.apply(plan, patch(1,
                new PlanOp.AddNode(node("wall-s", "micra:wall", "hut", Map.of("side", new StrV("south")))),
                new PlanOp.AddNode(node("bad", "micra:wall", "hut", Map.of("side", new StrV("up"))))));
        assertNull(r.plan());
        assertEquals(2, plan.nodes().size());
        assertEquals(1, plan.revision());
    }

    @Test
    void idsAreValidatedAndMustBeUnique() {
        SemanticPlan plan = hut(patcher);
        PatchResult bad = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("Bad_Id", "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-INVALID"), codes(bad));
        PatchResult tooLong = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("a".repeat(49), "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-INVALID"), codes(tooLong));
        PatchResult dup = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("hut", "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-DUPLICATE"), codes(dup));
    }

    @Test
    void unknownPartsGetNearbySuggestions() {
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(node("x", "micra:walll", null, Map.of()))));
        Issue issue = r.issues().get(0);
        assertEquals(IssueCode.E_UNKNOWN_PART, issue.code());
        assertTrue(issue.hints().stream().anyMatch(h -> h.kind().equals("USE_PART") && h.args().get("ids").contains("micra:wall")),
                issue.hints().toString());
    }

    @Test
    void parameterProblemsAreParamRangeIssues() {
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(2), "nope", new IntV(1)))),
                new PlanOp.AddNode(node("hut-2", "micra:structure", null, Map.of("width", new NumV(3_000_000_000.0))))));
        assertEquals(3, r.issues().size(), r.issues().toString());
        assertTrue(r.issues().stream().allMatch(i -> i.code() == IssueCode.E_PARAM_RANGE));
    }

    @Test
    void parentAndAnchorReferencesAreChecked() {
        PatchResult noParent = patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("wall-n", "micra:wall", "ghost", Map.of("side", new StrV("north"))))));
        assertEquals(List.of("E-ANCHOR"), codes(noParent));

        SemanticPlan plan = hut(patcher);
        PlanNode onMissing = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("ghost", Side.OUTER, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(onMissing)))));
        PlanNode onNonWall = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("hut", Side.OUTER, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(onNonWall)))));
        PlanNode badSide = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.TOP, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(badSide)))));
        PlanNode negative = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, -1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(negative)))));
        PlanNode good = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of(), Set.of(), "");
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.AddNode(good))).ok());
    }

    @Test
    void updateParamsMergesAndValidatesTheWholeThing() {
        SemanticPlan plan = hut(patcher);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.UpdateParams("hut", Map.of("depth", new NumV(9.0)))));
        assertTrue(ok.ok(), ok.issues().toString());
        PlanNode updated = ok.plan().node("hut").orElseThrow();
        assertEquals(new IntV(7), updated.params().get("width"));
        assertEquals(new IntV(9), updated.params().get("depth"));

        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(plan, patch(1, new PlanOp.UpdateParams("hut", Map.of("width", new IntV(99)))))));
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.UpdateParams("ghost", Map.of())))));
    }

    @Test
    void moveNodeChecksTheNewAnchor() {
        SemanticPlan plan = hut(patcher);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", new Anchor.Absolute(new LocalPos(1, 0, 1), Rot.NONE))));
        assertTrue(ok.ok());
        assertEquals(new Anchor.Absolute(new LocalPos(1, 0, 1), Rot.NONE), ok.plan().node("wall-n").orElseThrow().anchor());
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.MoveNode("ghost", new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE))))));
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", new Anchor.OnSurface("wall-n", Side.OUTER, 0, 0))))));
    }

    @Test
    void removeNodeRefusesWhileDependentsRemain() {
        SemanticPlan plan = hut(patcher);
        PatchResult withChild = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("hut")));
        assertEquals(List.of("E-ANCHOR"), codes(withChild));
        assertTrue(withChild.issues().get(0).data().get("dependents").contains("wall-n"));
        assertTrue(withChild.issues().get(0).hints().stream().anyMatch(h -> h.kind().equals("REMOVE_FIRST")));

        PatchResult leaf = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("wall-n")));
        assertTrue(leaf.ok());
        assertEquals(1, leaf.plan().nodes().size());

        // removing the child and then the parent in one patch is fine
        PatchResult both = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("wall-n"), new PlanOp.RemoveNode("hut")));
        assertTrue(both.ok(), both.issues().toString());
        assertEquals(0, both.plan().nodes().size());
    }

    @Test
    void connectionsNeedRealNodesAndPorts() {
        PatchResult base = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("m", "test:motor", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of()))));
        assertTrue(base.ok(), base.issues().toString());
        SemanticPlan plan = base.plan();
        Connection good = new Connection("c-1", new PortRef("m", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.AddConnection(good)));
        assertTrue(ok.ok(), ok.issues().toString());

        Connection noPort = new Connection("c-2", new PortRef("m", "nope"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        Connection noNode = new Connection("c-3", new PortRef("ghost", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        Connection noVia = new Connection("c-4", new PortRef("m", "out"), new PortRef("s", "in"), ConnKind.ROTATION, new Routing.Explicit(List.of("ghost")), Constraints.NONE);
        Connection buildingPart = new Connection("c-5", new PortRef("hut", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        for (Connection bad : List.of(noPort, noNode, noVia, buildingPart)) {
            assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(plan, patch(1, new PlanOp.AddConnection(bad)))), bad.id());
        }
        assertEquals(List.of("E-ID-DUPLICATE"), codes(patcher.apply(ok.plan(), patch(2, new PlanOp.AddConnection(good)))));
        assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(ok.plan(), patch(2, new PlanOp.RemoveConnection("ghost")))));
        assertTrue(patcher.apply(ok.plan(), patch(2, new PlanOp.RemoveConnection("c-1"))).ok());
    }

    @Test
    void removingANodeThatAConnectionUsesIsRefused() {
        PatchResult base = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("m", "test:motor", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        assertTrue(base.ok(), base.issues().toString());
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(base.plan(), patch(1, new PlanOp.RemoveNode("m")))));
    }

    @Test
    void moduleInstancesNeedAKnownTemplateAndTakeNoParameters() {
        assertTrue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(node("line-1", "mod:test_line", null, Map.of())))).ok());
        assertEquals(List.of("E-UNKNOWN-PART"), codes(patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("x", "mod:ghost", null, Map.of()))))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("x", "mod:test_line", null, Map.of("n", new IntV(1))))))));
        // the module's own port can be connected
        PatchResult withModule = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("line-1", "mod:test_line", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("line-1", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        assertTrue(withModule.ok(), withModule.issues().toString());
    }

    @Test
    void styleSiteAndLogisticsAreValidated() {
        StyleSpec badRole = new StyleSpec(Map.of("Roof!", "minecraft:stone"), Set.of());
        StyleSpec badBlock = new StyleSpec(Map.of("roof", "stone"), Set.of());
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(badRole)))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(badBlock)))));
        assertTrue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:bricks"), Set.of("cozy"))))).ok());

        Site badDim = new Site("overworld", new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH), new Box(0, 0, 0, 5, 5, 5), "", "");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetSite(badDim)))));
        Site good = new Site("minecraft:overworld", new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH), new Box(0, 0, 0, 5, 5, 5), "", "");
        PatchResult withSite = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetSite(good)));
        assertTrue(withSite.ok());
        assertSame(good, withSite.plan().site());

        LogisticsPlan dangling = new LogisticsPlan(List.of(),
                List.of(new LogisticsPlan.Route("r-1", "ghost", "ghost", List.of(), null)), List.of());
        assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(dangling)))));
        LogisticsPlan dupDocks = new LogisticsPlan(List.of(
                new LogisticsPlan.Dock("d", new Box(0, 0, 0, 1, 0, 1), new Box(0, 1, 0, 1, 4, 1), Facing.NORTH, List.of(), List.of()),
                new LogisticsPlan.Dock("d", new Box(0, 0, 0, 1, 0, 1), new Box(0, 1, 0, 1, 4, 1), Facing.NORTH, List.of(), List.of())),
                List.of(), List.of());
        assertEquals(List.of("E-ID-DUPLICATE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(dupDocks)))));
        SemanticPlan withLogistics = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(
                new LogisticsPlan(List.of(), List.of(), List.of())))).plan();
        assertNotNull(withLogistics.logistics());
        assertNull(patcher.apply(withLogistics, patch(1, new PlanOp.SetLogistics(null))).plan().logistics());
    }

    @Test
    void opsInOnePatchApplyInOrder() {
        // add -> update -> remove -> add again with the same id, all in one patch
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("a", "micra:pillar", null, Map.of("height", new IntV(3)))),
                new PlanOp.UpdateParams("a", Map.of("height", new IntV(5))),
                new PlanOp.RemoveNode("a"),
                new PlanOp.AddNode(node("a", "micra:pillar", null, Map.of("height", new IntV(9))))));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(new IntV(9), r.plan().node("a").orElseThrow().params().get("height"));
        // a child added before its parent is a reference to something that does not exist yet
        PatchResult early = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north")))),
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of()))));
        assertEquals(List.of("E-ANCHOR"), codes(early));
    }

    @Test
    void normalizeFixesTheTypesOfALooselyReadPlan() {
        PlanNode loose = node("hut", "micra:structure", null, Map.of("width", new NumV(9.0)));
        PlanNode wall = node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north"), "material", new StrV("roof")));
        SemanticPlan plan = new SemanticPlan(1, "p", 4, 3, null, StyleSpec.EMPTY, List.of(loose, wall), List.of(), null,
                io.github.khayashi4337.micradrone.build.model.Provenance.NONE);
        PatchResult r = patcher.normalize(plan);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(new IntV(9), r.plan().node("hut").orElseThrow().params().get("width"));
        assertEquals(new EnumV("north"), r.plan().node("wall-n").orElseThrow().params().get("side"));
        assertEquals(new MaterialV("roof"), r.plan().node("wall-n").orElseThrow().params().get("material"));
        assertEquals(4, r.plan().revision(), "normalize keeps the revision and the meta fields");
        assertEquals(plan.planId(), r.plan().planId());
    }

    @Test
    void theSameDesignEnteredTwoWaysHasTheSameContentHash() {
        PatchResult a = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new NumV(9.0))))));
        PatchResult b = patcher.apply(SemanticPlan.empty("q"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(9))))));
        assertEquals(a.plan().contentHash(), b.plan().contentHash());
    }
}
```

`ModuleTemplateTest`は`PlanExpanderTest`(Task 9)で扱う。

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.plan.*" --console=plain`
Expected: FAIL(`cannot find symbol`)。

- [ ] **Step 3: 実装する**

`PatchResult.java`・`VerificationOrigin.java`・`Verification.java`・`TemplateStats.java`:
```java
// PatchResult.java  (package io.github.khayashi4337.micradrone.build.plan)
public record PatchResult(SemanticPlan plan, List<Issue> issues) {
    public PatchResult {
        issues = List.copyOf(issues);
    }

    /** True when the patch was applied; an ERROR anywhere makes the whole patch refused (plan is null). */
    public boolean ok() {
        return plan != null;
    }
}

// VerificationOrigin.java
public enum VerificationOrigin { BUNDLED_CI, PLAYER_COMMISSIONED }

// Verification.java
public record Verification(VerificationOrigin origin, String analyzerVersion, String resultHash, long atMillis) {
}

// TemplateStats.java
public record TemplateStats(double rpm, double stressSu, Map<String, Double> perMinByProduct) {
    public TemplateStats {
        perMinByProduct = Collections.unmodifiableSortedMap(new TreeMap<>(perMinByProduct == null ? Map.of() : perMinByProduct));
    }
}
```

`ModuleTemplate.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PartTypeJson;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A verified fragment of a plan (a press stand, a power room, ...). {@code hash()} covers the structure only, so
 * a bundled template is recognised by id and hash while stats and verification records may be refreshed.
 */
public record ModuleTemplate(int schemaVersion, String id, String displayNameKey, PartCategory category,
                             VersionRange requires, Box footprint, List<PortSpec> ports, List<PlanNode> nodes,
                             List<Connection> internal, TemplateStats stats, Verification verification, Set<String> tags) {
    public ModuleTemplate {
        ports = List.copyOf(ports == null ? List.of() : ports);
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        internal = List.copyOf(internal == null ? List.of() : internal);
        tags = Collections.unmodifiableSortedSet(new TreeSet<>(tags == null ? Set.of() : tags));
    }

    public Optional<PortSpec> port(String name) {
        for (PortSpec p : ports) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public String hash() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", schemaVersion);
        m.put("id", id);
        m.put("displayNameKey", displayNameKey);
        m.put("category", category.name());
        m.put("requires", Map.of("modId", requires.modId(), "range", requires.mavenRange()));
        m.put("footprint", footprint == null ? null : PlanJson.boxTree(footprint));
        List<PortSpec> sortedPorts = new ArrayList<>(ports);
        sortedPorts.sort(Comparator.comparing(PortSpec::name));
        List<Object> portTrees = new ArrayList<>();
        for (PortSpec p : sortedPorts) {
            portTrees.add(PartTypeJson.portTree(p));
        }
        m.put("ports", portTrees);
        List<PlanNode> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator.comparing(PlanNode::id));
        List<Object> nodeTrees = new ArrayList<>();
        for (PlanNode n : sortedNodes) {
            nodeTrees.add(PlanJson.nodeToTree(n));
        }
        m.put("nodes", nodeTrees);
        List<Connection> sortedConns = new ArrayList<>(internal);
        sortedConns.sort(Comparator.comparing(Connection::id));
        List<Object> connTrees = new ArrayList<>();
        for (Connection c : sortedConns) {
            connTrees.add(PlanJson.connectionToTree(c));
        }
        m.put("internal", connTrees);
        m.put("tags", new ArrayList<>(tags));
        return Hashing.sha256Hex(CanonicalJson.write(m));
    }
}
```
(`PartTypeJson`に`public static Map<String,Object> portTree(PortSpec)`を足し、`toTree`の中でも使う。`PlanJson.boxTree`は公開にしておく。)

`TemplateBundle.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The templates a plan refers to. Bundled ones are matched by id and hash; player-promoted ones carry their body. */
public record TemplateBundle(List<ModuleTemplate> templates) {
    public static final TemplateBundle EMPTY = new TemplateBundle(List.of());

    public TemplateBundle {
        templates = List.copyOf(templates);
    }

    public Optional<ModuleTemplate> find(String id) {
        for (ModuleTemplate t : templates) {
            if (t.id().equals(id)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Refuses templates whose id is unknown to the server or whose hash differs from the server's own copy. */
    public List<Issue> verifyAgainst(Map<String, String> knownHashById) {
        List<Issue> issues = new ArrayList<>();
        for (ModuleTemplate t : templates) {
            String known = knownHashById.get(t.id());
            if (known == null) {
                issues.add(Issue.of(IssueCode.E_TEMPLATE_UNVERIFIED, List.of(t.id()),
                        "テンプレート" + t.id() + "は、サーバーの手持ちにありません"));
            } else if (!known.equals(t.hash())) {
                issues.add(Issue.of(IssueCode.E_TEMPLATE_UNVERIFIED, List.of(t.id()),
                        "テンプレート" + t.id() + "の内容が、サーバーの手持ちと違います"));
            }
        }
        return issues;
    }
}
```

`PlanPatcher.java`(振る舞いの1〜8のとおり):
```java
package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.ParamValidator;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Applies a {@link PlanPatch} to a {@link SemanticPlan}, deterministically. Operations apply in order; any ERROR
 * refuses the whole patch (no half-applied plans). Everything that arrives from outside (AI output, hand-written
 * JSON, scripts) passes through here, so the checks here are the first line of defence.
 */
public final class PlanPatcher {
    public static final String WALL_TYPE = "micra:wall";
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,48}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int SUGGESTION_LIMIT = 3;

    private final PartTypeRegistry registry;
    private final TemplateBundle templates;

    public PlanPatcher(PartTypeRegistry registry, TemplateBundle templates) {
        this.registry = registry;
        this.templates = templates;
    }

    private static final class State {
        final LinkedHashMap<String, PlanNode> nodes = new LinkedHashMap<>();
        final LinkedHashMap<String, Connection> connections = new LinkedHashMap<>();
        Site site;
        StyleSpec style;
        LogisticsPlan logistics;

        State(SemanticPlan plan) {
            for (PlanNode n : plan.nodes()) {
                nodes.put(n.id(), n);
            }
            for (Connection c : plan.connections()) {
                connections.put(c.id(), c);
            }
            site = plan.site();
            style = plan.style();
            logistics = plan.logistics();
        }
    }

    public PatchResult apply(SemanticPlan plan, PlanPatch patch) {
        List<Issue> issues = new ArrayList<>();
        if (patch.baseRevision() != plan.revision()) {
            issues.add(Issue.of(IssueCode.E_PATCH_STALE, "", List.of(patch.patchId()),
                    "差分は版" + patch.baseRevision() + "向けですが、計画は版" + plan.revision() + "です",
                    Map.of("baseRevision", String.valueOf(patch.baseRevision()), "revision", String.valueOf(plan.revision())),
                    List.of(new FixHint("REBASE", Map.of("revision", String.valueOf(plan.revision()))))));
            return new PatchResult(null, issues);
        }
        State st = new State(plan);
        for (PlanOp op : patch.ops()) {
            applyOp(st, op, issues);
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new PatchResult(null, issues);
        }
        return new PatchResult(new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision() + 1, plan.revision(),
                st.site, st.style, new ArrayList<>(st.nodes.values()), new ArrayList<>(st.connections.values()),
                st.logistics, plan.provenance()), issues);
    }

    /** Types a loosely read plan against the registry; keeps planId, revision and provenance. */
    public PatchResult normalize(SemanticPlan loose) {
        SemanticPlan empty = new SemanticPlan(loose.schemaVersion(), loose.planId(), 0, null, null, StyleSpec.EMPTY,
                List.of(), List.of(), null, loose.provenance());
        List<PlanOp> ops = new ArrayList<>();
        if (loose.site() != null) {
            ops.add(new PlanOp.SetSite(loose.site()));
        }
        ops.add(new PlanOp.SetStyle(loose.style()));
        for (PlanNode n : loose.nodes()) {
            ops.add(new PlanOp.AddNode(n));
        }
        for (Connection c : loose.connections()) {
            ops.add(new PlanOp.AddConnection(c));
        }
        if (loose.logistics() != null) {
            ops.add(new PlanOp.SetLogistics(loose.logistics()));
        }
        PatchResult r = apply(empty, new PlanPatch("normalize", 0, "normalize", ops));
        if (!r.ok()) {
            return r;
        }
        SemanticPlan p = r.plan();
        return new PatchResult(new SemanticPlan(loose.schemaVersion(), loose.planId(), loose.revision(),
                loose.parentRevision(), p.site(), p.style(), p.nodes(), p.connections(), p.logistics(), loose.provenance()),
                r.issues());
    }

    private void applyOp(State st, PlanOp op, List<Issue> issues) {
        switch (op) {
            case PlanOp.AddNode o -> addNode(st, o.node(), issues);
            case PlanOp.UpdateParams o -> updateParams(st, o, issues);
            case PlanOp.MoveNode o -> moveNode(st, o, issues);
            case PlanOp.RemoveNode o -> removeNode(st, o.id(), issues);
            case PlanOp.AddConnection o -> addConnection(st, o.connection(), issues);
            case PlanOp.RemoveConnection o -> {
                if (st.connections.remove(o.id()) == null) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, List.of(o.id()), "接続" + o.id() + "はありません"));
                }
            }
            case PlanOp.SetStyle o -> setStyle(st, o.style(), issues);
            case PlanOp.SetSite o -> setSite(st, o.site(), issues);
            case PlanOp.SetLogistics o -> setLogistics(st, o.logistics(), issues);
        }
    }

    private boolean checkId(String id, List<Issue> issues) {
        if (!ID.matcher(id).matches()) {
            issues.add(Issue.of(IssueCode.E_ID_INVALID, List.of(id), "IDは半角の小文字・数字・ハイフンで48字以内にしてください: " + id));
            return false;
        }
        return true;
    }

    private void addNode(State st, PlanNode node, List<Issue> issues) {
        if (!checkId(node.id(), issues)) {
            return;
        }
        if (st.nodes.containsKey(node.id())) {
            issues.add(Issue.of(IssueCode.E_ID_DUPLICATE, List.of(node.id()), "IDが重複しています: " + node.id()));
            return;
        }
        boolean ok = true;
        Map<String, ParamValue> typed = node.params();
        PartType type = registry.find(node.type()).orElse(null);
        if (type != null) {
            ParamValidator.Result r = ParamValidator.validate(node.id(), type, node.params());
            issues.addAll(r.issues());
            if (r.issues().isEmpty()) {
                typed = r.typed();
            } else {
                ok = false;
            }
        } else if (templates.find(node.type()).isPresent()) {
            if (!node.params().isEmpty()) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, "params", List.of(node.id()),
                        "モジュール" + node.type() + "はパラメータを持ちません", Map.of(), List.of()));
                ok = false;
            }
        } else {
            issues.add(unknownPart(node));
            ok = false;
        }
        if (node.parent() != null && !st.nodes.containsKey(node.parent())) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "parent", List.of(node.id()), "親のノードがありません: " + node.parent()));
            ok = false;
        }
        ok &= checkAnchor(st, node.id(), node.anchor(), issues);
        if (ok) {
            st.nodes.put(node.id(), new PlanNode(node.id(), node.type(), node.parent(), node.anchor(), typed, node.tags(), node.label()));
        }
    }

    private Issue unknownPart(PlanNode node) {
        List<String> near = new ArrayList<>(registry.suggest(node.type(), SUGGESTION_LIMIT));
        return Issue.of(IssueCode.E_UNKNOWN_PART, "", List.of(node.id()),
                "登録簿に無い部品です: " + node.type(), Map.of("type", node.type()),
                List.of(new FixHint("USE_PART", Map.of("ids", String.join(",", near)))));
    }

    private boolean checkAnchor(State st, String ownerId, Anchor anchor, List<Issue> issues) {
        if (!(anchor instanceof Anchor.OnSurface s)) {
            return true;
        }
        PlanNode target = st.nodes.get(s.nodeId());
        String problem = null;
        if (s.nodeId().equals(ownerId)) {
            problem = "自分自身の面には付けられません";
        } else if (target == null) {
            problem = "面の対象のノードがありません: " + s.nodeId();
        } else if (!target.type().equals(WALL_TYPE)) {
            problem = "面の対象は壁(" + WALL_TYPE + ")でなければなりません: " + s.nodeId();
        } else if (s.side() != Side.OUTER && s.side() != Side.INNER) {
            problem = "面の側は outer か inner です";
        } else if (s.u() < 0 || s.v() < 0) {
            problem = "面の上の位置(u, v)は0以上にしてください";
        }
        if (problem != null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "anchor", List.of(ownerId), problem));
            return false;
        }
        return true;
    }

    private void updateParams(State st, PlanOp.UpdateParams op, List<Issue> issues) {
        PlanNode existing = st.nodes.get(op.id());
        if (existing == null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "node", List.of(op.id()), "対象のノードがありません: " + op.id()));
            return;
        }
        PartType type = registry.find(existing.type()).orElse(null);
        if (type == null) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, "params", List.of(op.id()), "モジュールはパラメータを持ちません"));
            return;
        }
        Map<String, ParamValue> merged = new TreeMap<>(existing.params());
        merged.putAll(op.params());
        ParamValidator.Result r = ParamValidator.validate(op.id(), type, merged);
        issues.addAll(r.issues());
        if (r.issues().isEmpty()) {
            st.nodes.put(op.id(), new PlanNode(existing.id(), existing.type(), existing.parent(), existing.anchor(),
                    r.typed(), existing.tags(), existing.label()));
        }
    }

    private void moveNode(State st, PlanOp.MoveNode op, List<Issue> issues) {
        PlanNode existing = st.nodes.get(op.id());
        if (existing == null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "node", List.of(op.id()), "対象のノードがありません: " + op.id()));
            return;
        }
        if (checkAnchor(st, op.id(), op.anchor(), issues)) {
            st.nodes.put(op.id(), new PlanNode(existing.id(), existing.type(), existing.parent(), op.anchor(),
                    existing.params(), existing.tags(), existing.label()));
        }
    }

    private void removeNode(State st, String id, List<Issue> issues) {
        if (!st.nodes.containsKey(id)) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "node", List.of(id), "対象のノードがありません: " + id));
            return;
        }
        Set<String> dependents = new java.util.TreeSet<>();
        for (PlanNode n : st.nodes.values()) {
            if (id.equals(n.parent()) || (n.anchor() instanceof Anchor.OnSurface s && s.nodeId().equals(id))) {
                dependents.add(n.id());
            }
        }
        for (Connection c : st.connections.values()) {
            boolean uses = c.from().nodeId().equals(id) || c.to().nodeId().equals(id)
                    || c.constraints().avoidNodeIds().contains(id)
                    || (c.routing() instanceof Routing.Explicit e && e.viaNodeIds().contains(id));
            if (uses) {
                dependents.add(c.id());
            }
        }
        if (st.logistics != null) {
            for (LogisticsPlan.Dock d : st.logistics.docks()) {
                boolean uses = d.dockingConnectorNodeIds().contains(id)
                        || d.linkedPorts().stream().anyMatch(p -> p.nodeId().equals(id));
                if (uses) {
                    dependents.add(d.id());
                }
            }
        }
        if (!dependents.isEmpty()) {
            String list = String.join(",", dependents);
            issues.add(Issue.of(IssueCode.E_ANCHOR, "remove", List.of(id),
                    id + "を消す前に、これに依存する物を消してください: " + list, Map.of("dependents", list),
                    List.of(new FixHint("REMOVE_FIRST", Map.of("ids", list)))));
            return;
        }
        st.nodes.remove(id);
    }

    private boolean portExists(State st, PortRef ref) {
        PlanNode n = st.nodes.get(ref.nodeId());
        if (n == null) {
            return false;
        }
        PartType type = registry.find(n.type()).orElse(null);
        if (type != null) {
            return type.port(ref.port()).isPresent();
        }
        return templates.find(n.type()).map(t -> t.port(ref.port()).isPresent()).orElse(false);
    }

    private void addConnection(State st, Connection c, List<Issue> issues) {
        if (!checkId(c.id(), issues)) {
            return;
        }
        if (st.connections.containsKey(c.id())) {
            issues.add(Issue.of(IssueCode.E_ID_DUPLICATE, List.of(c.id()), "接続のIDが重複しています: " + c.id()));
            return;
        }
        boolean ok = true;
        for (PortRef ref : List.of(c.from(), c.to())) {
            if (!portExists(st, ref)) {
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, ref.nodeId() + "." + ref.port(), List.of(c.id()),
                        "つなぎ口がありません: " + ref.nodeId() + "." + ref.port()));
                ok = false;
            }
        }
        if (c.routing() instanceof Routing.Explicit e) {
            for (String via : e.viaNodeIds()) {
                if (!st.nodes.containsKey(via)) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, "via:" + via, List.of(c.id()), "経由する部品がありません: " + via));
                    ok = false;
                }
            }
        }
        if (ok) {
            st.connections.put(c.id(), c);
        }
    }

    private void setStyle(State st, StyleSpec style, List<Issue> issues) {
        boolean ok = true;
        for (Map.Entry<String, String> e : style.palette().entrySet()) {
            if (!ROLE.matcher(e.getKey()).matches() || !BLOCK_ID.matcher(e.getValue()).matches()) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, "style", List.of(e.getKey()),
                        "パレットは 役割名(小文字)→ブロックID(namespace:name)で書いてください: " + e.getKey() + " = " + e.getValue(),
                        Map.of(), List.of()));
                ok = false;
            }
        }
        if (ok) {
            st.style = style;
        }
    }

    private void setSite(State st, Site site, List<Issue> issues) {
        if (!DIMENSION.matcher(site.dimension()).matches()) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, "site", List.of("site"),
                    "ディメンションは namespace:name の形で書いてください: " + site.dimension()));
            return;
        }
        st.site = site;
    }

    private void setLogistics(State st, LogisticsPlan logistics, List<Issue> issues) {
        if (logistics == null) {
            st.logistics = null;
            return;
        }
        boolean ok = true;
        Set<String> dockIds = new HashSet<>();
        for (LogisticsPlan.Dock d : logistics.docks()) {
            if (!dockIds.add(d.id())) {
                issues.add(Issue.of(IssueCode.E_ID_DUPLICATE, List.of(d.id()), "発着場のIDが重複しています: " + d.id()));
                ok = false;
            }
        }
        Set<String> routeIds = new HashSet<>();
        for (LogisticsPlan.Route r : logistics.routes()) {
            if (!routeIds.add(r.id())) {
                issues.add(Issue.of(IssueCode.E_ID_DUPLICATE, List.of(r.id()), "航路のIDが重複しています: " + r.id()));
                ok = false;
            }
            for (String dock : List.of(r.fromDock(), r.toDock())) {
                if (!dockIds.contains(dock)) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, "dock:" + dock, List.of(r.id()), "存在しない発着場を指しています: " + dock));
                    ok = false;
                }
            }
        }
        for (LogisticsPlan.CargoFlow f : logistics.flows()) {
            for (String dock : List.of(f.fromDock(), f.toDock())) {
                if (!dockIds.contains(dock)) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, "dock:" + dock, List.of(f.itemId()), "存在しない発着場を指しています: " + dock));
                    ok = false;
                }
            }
        }
        if (ok) {
            st.logistics = logistics;
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: PlanPatcher(差分の適用。ERRORがあれば全体を拒否)とテンプレートの型を追加(自然言語→工場建設 P3 Task 8)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `PlanExpander`(テンプレートの展開と接続の解決)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/plan/{SlotResolver,Router,RoutedConnection,ExpandedPlan,ExpandResult,Origins,PlanExpander}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/parts/BuildingParts.java`(`ROTATION_UNSUPPORTED`を足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/plan/PlanExpanderTest.java`

**Interfaces:**
- Consumes: Task 8の`PlanPatcher`・`ModuleTemplate`・`TemplateBundle`、Task 5・6・7の型
- Produces:
  - `BuildingParts.ROTATION_UNSUPPORTED`: 回転・鏡像を付けられない部品のID(親や壁に依存する`micra:structure`・`foundation`・`floor`・`wall`・`roof`・`door`・`window`・`sign`・`planter`・`trim`・`balcony`)
  - `interface SlotResolver { Optional<LocalPos> resolve(String slotId); SlotResolver NONE = id -> Optional.empty(); }`
  - `interface Router { Optional<RoutedConnection> route(SemanticPlan plan, Connection connection); Router NONE = (plan, c) -> Optional.empty(); }`
  - `record RoutedConnection(String connectionId, List<PlanNode> intermediateNodes, List<LocalPos> path)`
  - `record ExpandedPlan(SemanticPlan source, List<PlanNode> primitiveNodes, List<RoutedConnection> routed, List<String> templateHashes)`
  - `record ExpandResult(ExpandedPlan plan, List<Issue> issues)`(ERRORがあれば`plan`は`null`)
  - `final class Origins`: `static Map<String,LocalPos> resolve(List<PlanNode> nodes, SlotResolver slots, List<Issue> issues)`(親をたどって、各ノードの局所の原点を出す。`Absolute`のみ。`OnSurface`は壁の面に依存するので含めない。`InSlot`は`slots`で解決し、できなければ`E-ANCHOR`)
  - `PlanExpander(PartTypeRegistry registry, SlotResolver slots)`: `ExpandResult expand(SemanticPlan plan, TemplateBundle templates, Router router)`

**展開の規則(設計図01 2.1節。テストで固定):**
1. `type`が`mod:`で始まるノードは、テンプレートの`nodes`・`internal`で置き換える(インスタンスのノード自身は`primitiveNodes`に残らない)。ID=`<インスタンスID>/<元のID>`。テンプレートが無ければ`E-UNKNOWN-PART`。
2. インスタンスの`anchor`は`Absolute`(または`InSlot`→`slots`で位置を得て、`Rot`はそのまま)のみ。`OnSurface`は`E-ANCHOR`。
3. テンプレートの根(`parent==null`)のノード: `parent`=インスタンスの`parent`、`Absolute(p, r)`→`Absolute(instRot.apply(p) + instPos, compose(instRot, r))`。テンプレート内の子(`parent!=null`): `parent`=`<インスタンスID>/<元のparent>`、`Absolute(p, r)`→`Absolute(instRot.apply(p), compose(instRot, r))`。`OnSurface(n, ...)`→`OnSurface(<インスタンスID>/n, ...)`。テンプレート内の`InSlot`は`E-ANCHOR`。
4. `instRot`が`Rot.NONE`でなく、テンプレートに`ROTATION_UNSUPPORTED`の部品がある場合は`E-ANCHOR`(建屋は回転できない)。
5. 接続(プランのものと、テンプレートの`internal`。後者はIDを`<インスタンスID>/<元のID>`に、端点・`via`のノードIDを同じ接頭辞に付け替える): 端点のノード(展開後の部品、またはモジュールのインスタンス)とポートの存在を確かめ、無ければ`E-CONN-INVALID`。`Explicit`は、`via`のノードの存在を確かめ、`RoutedConnection(id, [], viaノードの原点の並び)`を作る(`Origins`で解決できない`via`は原点を飛ばす)。`Auto`は`router.route(...)`が空なら`E-NO-ROUTE`。
6. `templateHashes`=使ったテンプレートの`hash()`(辞書順・重複なし)。

- [ ] **Step 1: 失敗するテストを書く**

`PlanExpanderTest.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanExpanderTest {
    private final PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TestParts.bundle());
    private final PlanExpander expander = new PlanExpander(TestParts.registry(), SlotResolver.NONE);

    private SemanticPlan plan(List<PlanOp> ops) {
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static PlanNode module(String id, String parent, Anchor anchor) {
        return new PlanNode(id, "mod:test_line", parent, anchor, Map.of(), Set.of(), "");
    }

    private static Anchor.Absolute abs(int u, int v, int w, int turns) {
        return new Anchor.Absolute(new LocalPos(u, v, w), new Rot(turns, false));
    }

    private static PlanNode find(ExpandedPlan plan, String id) {
        return plan.primitiveNodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aPlanWithoutModulesKeepsItsNodes() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 1, 0, 2))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(plan.nodes(), r.plan().primitiveNodes());
        assertEquals(List.of(), r.plan().templateHashes());
    }

    @Test
    void aModuleBecomesItsPartsWithPrefixedIdsAndAComposedTransform() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null, abs(10, 0, 20, 1)))));
        ExpandResult r = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        ExpandedPlan e = r.plan();
        assertEquals(2, e.primitiveNodes().size());
        // motor: template root at (2,0,1); one clockwise turn maps (u,w)=(2,1) to (1,-2); plus the instance position
        PlanNode motor = find(e, "line-1/motor");
        assertEquals(null, motor.parent());
        assertEquals(new Anchor.Absolute(new LocalPos(11, 0, 18), new Rot(1, false)), motor.anchor());
        // shaft: child of motor at (1,0,0); rotated by the instance rotation only
        PlanNode shaft = find(e, "line-1/shaft");
        assertEquals("line-1/motor", shaft.parent());
        assertEquals(new Anchor.Absolute(new LocalPos(0, 0, -1), new Rot(1, false)), shaft.anchor());
        assertEquals(List.of(TestParts.lineTemplate().hash()), e.templateHashes());
        assertEquals(1, e.routed().size(), "the template's internal explicit connection is kept");
        assertEquals("line-1/link", e.routed().get(0).connectionId());
    }

    @Test
    void theInstanceKeepsItsParentForTheTemplateRoots() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(module("line-1", "room", abs(1, 0, 1, 0)))));
        ExpandedPlan e = expander.expand(plan, TestParts.bundle(), Router.NONE).plan();
        assertEquals("room", find(e, "line-1/motor").parent());
        assertEquals(new Anchor.Absolute(new LocalPos(3, 0, 2), Rot.NONE), find(e, "line-1/motor").anchor());
    }

    @Test
    void missingTemplateAndBadInstanceAnchorsAreErrors() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null, abs(0, 0, 0, 0)))));
        ExpandResult missing = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertNull(missing.plan());
        assertEquals(IssueCode.E_UNKNOWN_PART, missing.issues().get(0).code());

        SemanticPlan slotPlan = plan(List.of(new PlanOp.AddNode(module("line-2", null, new Anchor.InSlot("slot-a", Rot.NONE)))));
        ExpandResult noSlots = expander.expand(slotPlan, TestParts.bundle(), Router.NONE);
        assertEquals(IssueCode.E_ANCHOR, noSlots.issues().get(0).code(), "slots need the building analysis (P6)");
        PlanExpander withSlots = new PlanExpander(TestParts.registry(), id -> Optional.of(new LocalPos(5, 0, 5)));
        ExpandResult resolved = withSlots.expand(slotPlan, TestParts.bundle(), Router.NONE);
        assertTrue(resolved.issues().isEmpty(), resolved.issues().toString());
        assertEquals(new Anchor.Absolute(new LocalPos(7, 0, 6), Rot.NONE), find(resolved.plan(), "line-2/motor").anchor());
    }

    @Test
    void aRotatedModuleCannotContainABuilding() {
        ModuleTemplate withBuilding = new ModuleTemplate(1, "mod:hut", "k", io.github.khayashi4337.micradrone.build.parts.PartCategory.MODULE,
                io.github.khayashi4337.micradrone.build.parts.VersionRange.ALWAYS, null, List.of(),
                List.of(TestParts.at("shell", "micra:structure", null, 0, 0, 0)), List.of(), null, null, Set.of());
        TemplateBundle bundle = new TemplateBundle(List.of(withBuilding));
        PlanPatcher p = new PlanPatcher(TestParts.registry(), bundle);
        SemanticPlan rotated = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("h", "mod:hut", null, abs(0, 0, 0, 1), Map.of(), Set.of(), ""))))).plan();
        ExpandResult r = expander.expand(rotated, bundle, Router.NONE);
        assertEquals(IssueCode.E_ANCHOR, r.issues().get(0).code());
        SemanticPlan straight = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("h", "mod:hut", null, abs(3, 0, 3, 0), Map.of(), Set.of(), ""))))).plan();
        assertTrue(expander.expand(straight, bundle, Router.NONE).issues().isEmpty());
        assertTrue(BuildingParts.ROTATION_UNSUPPORTED.contains("micra:structure"));
        assertFalse(BuildingParts.ROTATION_UNSUPPORTED.contains("micra:pillar"));
    }

    @Test
    void explicitConnectionsRecordThePathOfTheirViaNodes() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(TestParts.at("s1", "test:shaft", null, 1, 0, 0)),
                new PlanOp.AddNode(TestParts.at("s2", "test:shaft", null, 2, 0, 0)),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("p", "power_in"),
                        ConnKind.ROTATION, new Routing.Explicit(List.of("s1", "s2")), Constraints.NONE))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        RoutedConnection routed = r.plan().routed().get(0);
        assertEquals("c-1", routed.connectionId());
        assertEquals(List.of(new LocalPos(1, 0, 0), new LocalPos(2, 0, 0)), routed.path());
        assertEquals(List.of(), routed.intermediateNodes());
    }

    @Test
    void autoConnectionsAreRefusedUntilARouterExists() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("p", "power_in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of(IssueCode.E_NO_ROUTE), r.issues().stream().map(i -> i.code()).toList());
        assertEquals(List.of("c-1"), r.issues().get(0).subjects());

        Router fake = (pl, c) -> Optional.of(new RoutedConnection(c.id(), List.of(TestParts.at("auto-1", "test:shaft", null, 1, 0, 0)),
                List.of(new LocalPos(1, 0, 0))));
        ExpandResult routed = expander.expand(plan, TemplateBundle.EMPTY, fake);
        assertTrue(routed.issues().isEmpty(), routed.issues().toString());
        assertEquals("auto-1", routed.plan().routed().get(0).intermediateNodes().get(0).id());
    }

    @Test
    void connectionsToAModulePortNeedThatPort() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(module("line-1", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(TestParts.at("s", "test:shaft", null, 5, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("line-1", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, new Routing.Explicit(List.of()), Constraints.NONE))));
        ExpandResult ok = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertTrue(ok.issues().isEmpty(), ok.issues().toString());
        assertEquals(2, ok.plan().routed().size());
    }

    @Test
    void templateHashCoversStructureAndVerifyAgainstChecksIt() {
        ModuleTemplate a = TestParts.lineTemplate();
        ModuleTemplate moved = new ModuleTemplate(a.schemaVersion(), a.id(), a.displayNameKey(), a.category(), a.requires(),
                a.footprint(), a.ports(), List.of(TestParts.at("motor", "test:motor", null, 9, 0, 1), a.nodes().get(1)),
                a.internal(), a.stats(), a.verification(), a.tags());
        assertNotEquals(a.hash(), moved.hash());
        assertEquals(a.hash(), a.hash());
        // stats and verification are metadata, not part of the hash
        ModuleTemplate restamped = new ModuleTemplate(a.schemaVersion(), a.id(), a.displayNameKey(), a.category(), a.requires(),
                a.footprint(), a.ports(), a.nodes(), a.internal(),
                new TemplateStats(16, 512, Map.of("x", 1.0)), new Verification(VerificationOrigin.BUNDLED_CI, "v1", "h", 1L), a.tags());
        assertEquals(a.hash(), restamped.hash());

        TemplateBundle bundle = new TemplateBundle(List.of(a));
        assertTrue(bundle.verifyAgainst(Map.of(a.id(), a.hash())).isEmpty());
        assertEquals(IssueCode.E_TEMPLATE_UNVERIFIED, bundle.verifyAgainst(Map.of(a.id(), "0".repeat(64))).get(0).code());
        assertEquals(IssueCode.E_TEMPLATE_UNVERIFIED, bundle.verifyAgainst(Map.of()).get(0).code());
    }

    @Test
    void originsResolveThroughParents() {
        List<PlanNode> nodes = new ArrayList<>(List.of(
                TestParts.at("a", "micra:structure", null, 2, 0, 3),
                TestParts.at("b", "micra:pillar", "a", 1, 0, 1)));
        List<io.github.khayashi4337.micradrone.build.model.Issue> issues = new ArrayList<>();
        Map<String, LocalPos> o = Origins.resolve(nodes, SlotResolver.NONE, issues);
        assertEquals(new LocalPos(2, 0, 3), o.get("a"));
        assertEquals(new LocalPos(3, 0, 4), o.get("b"));
        assertTrue(issues.isEmpty());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.plan.PlanExpanderTest" --console=plain`
Expected: FAIL(`cannot find symbol`)。

- [ ] **Step 3: 実装する**

`BuildingParts.java`に、`NAMES`の下へ足す:
```java
    /**
     * Parts that cannot take a rotation or mirror: they are laid out from their parent's footprint or a wall's
     * face, so turning them alone would detach them. A building's direction is set by the site's facing.
     */
    public static final java.util.Set<String> ROTATION_UNSUPPORTED = java.util.Set.of(
            "micra:structure", "micra:foundation", "micra:floor", "micra:wall", "micra:roof", "micra:door",
            "micra:window", "micra:sign", "micra:planter", "micra:trim", "micra:balcony");
```
`BuildingPartsTest`に、`ROTATION_UNSUPPORTED`の全IDが登録簿にあるテストを1つ足す。

`SlotResolver.java`・`Router.java`・`RoutedConnection.java`・`ExpandedPlan.java`・`ExpandResult.java`:
```java
// SlotResolver.java (package io.github.khayashi4337.micradrone.build.plan)
/** Turns a slot id into a local position. Slots come from the building analysis (phase P6); until then none resolve. */
@FunctionalInterface
public interface SlotResolver {
    SlotResolver NONE = slotId -> Optional.empty();

    Optional<LocalPos> resolve(String slotId);
}

// Router.java
/** Finds the intermediate parts (shafts, belts, chutes) for an automatically routed connection. The real one arrives in P11. */
@FunctionalInterface
public interface Router {
    Router NONE = (plan, connection) -> Optional.empty();

    Optional<RoutedConnection> route(SemanticPlan plan, Connection connection);
}

// RoutedConnection.java
public record RoutedConnection(String connectionId, List<PlanNode> intermediateNodes, List<LocalPos> path) {
    public RoutedConnection {
        intermediateNodes = List.copyOf(intermediateNodes);
        path = List.copyOf(path);
    }
}

// ExpandedPlan.java
/** A plan with modules replaced by their parts and connections resolved. Rebuilt by the server, never trusted from the client. */
public record ExpandedPlan(SemanticPlan source, List<PlanNode> primitiveNodes, List<RoutedConnection> routed,
                           List<String> templateHashes) {
    public ExpandedPlan {
        primitiveNodes = List.copyOf(primitiveNodes);
        routed = List.copyOf(routed);
        templateHashes = List.copyOf(templateHashes);
    }
}

// ExpandResult.java
public record ExpandResult(ExpandedPlan plan, List<Issue> issues) {
    public ExpandResult {
        issues = List.copyOf(issues);
    }
}
```

`Origins.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The local origin of every node, found by walking parents: an Absolute position is relative to the parent's
 * origin (or the plan origin). OnSurface nodes depend on the wall's geometry, so they are resolved by the compiler.
 */
public final class Origins {
    private Origins() {
    }

    public static Map<String, LocalPos> resolve(List<PlanNode> nodes, SlotResolver slots, List<Issue> issues) {
        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode n : nodes) {
            byId.put(n.id(), n);
        }
        Map<String, LocalPos> origins = new HashMap<>();
        for (PlanNode n : nodes) {
            resolveOne(n, byId, slots, origins, issues, 0);
        }
        return origins;
    }

    private static LocalPos resolveOne(PlanNode n, Map<String, PlanNode> byId, SlotResolver slots,
                                       Map<String, LocalPos> origins, List<Issue> issues, int depth) {
        LocalPos known = origins.get(n.id());
        if (known != null) {
            return known;
        }
        if (depth > byId.size()) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, "cycle", List.of(n.id()), "親子の関係が輪になっています"));
            return null;
        }
        LocalPos base = new LocalPos(0, 0, 0);
        if (n.parent() != null) {
            PlanNode parent = byId.get(n.parent());
            base = parent == null ? null : resolveOne(parent, byId, slots, origins, issues, depth + 1);
            if (base == null) {
                return null;
            }
        }
        LocalPos pos;
        switch (n.anchor()) {
            case Anchor.Absolute a -> pos = base.plus(a.pos().u(), a.pos().v(), a.pos().w());
            case Anchor.InSlot s -> {
                LocalPos slot = slots.resolve(s.slotId()).orElse(null);
                if (slot == null) {
                    issues.add(Issue.of(IssueCode.E_ANCHOR, "slot", List.of(n.id()),
                            "スロット" + s.slotId() + "を解決できません(スロットは建屋の意味解析が載るP6以降で使えます)"));
                    return null;
                }
                pos = slot;
            }
            case Anchor.OnSurface s -> {
                return null;
            }
        }
        origins.put(n.id(), pos);
        return pos;
    }
}
```

`PlanExpander.java`:
```java
package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Expands a plan into parts only: module instances become their template's parts, connections are resolved.
 * The server runs this itself on what the client sent; the client's own expansion is never trusted (D-3).
 */
public final class PlanExpander {
    public static final String MODULE_PREFIX = "mod:";
    /** Separates an instance id from a template-internal id; not allowed in user ids, so it cannot collide. */
    public static final String ID_SEPARATOR = "/";

    private final PartTypeRegistry registry;
    private final SlotResolver slots;

    public PlanExpander(PartTypeRegistry registry, SlotResolver slots) {
        this.registry = registry;
        this.slots = slots;
    }

    public ExpandResult expand(SemanticPlan plan, TemplateBundle templates, Router router) {
        List<Issue> issues = new ArrayList<>();
        List<PlanNode> primitive = new ArrayList<>();
        Map<String, ModuleTemplate> moduleInstances = new HashMap<>();
        List<Connection> connections = new ArrayList<>(plan.connections());
        TreeSet<String> hashes = new TreeSet<>();

        for (PlanNode node : plan.nodes()) {
            if (!node.type().startsWith(MODULE_PREFIX)) {
                primitive.add(node);
                continue;
            }
            ModuleTemplate template = templates.find(node.type()).orElse(null);
            if (template == null) {
                issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, List.of(node.id()), "テンプレートがありません: " + node.type()));
                continue;
            }
            if (instantiate(node, template, primitive, connections, issues)) {
                moduleInstances.put(node.id(), template);
                hashes.add(template.hash());
            }
        }

        Map<String, LocalPos> origins = Origins.resolve(primitive, slots, issues);
        Map<String, PlanNode> primitiveById = new HashMap<>();
        for (PlanNode n : primitive) {
            primitiveById.put(n.id(), n);
        }
        List<RoutedConnection> routed = new ArrayList<>();
        for (Connection c : connections) {
            resolveConnection(plan, c, primitiveById, moduleInstances, origins, router, routed, issues);
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new ExpandResult(null, issues);
        }
        return new ExpandResult(new ExpandedPlan(plan, primitive, routed, new ArrayList<>(hashes)), issues);
    }

    private boolean instantiate(PlanNode instance, ModuleTemplate template, List<PlanNode> out, List<Connection> connections,
                                List<Issue> issues) {
        LocalPos instPos;
        Rot instRot;
        switch (instance.anchor()) {
            case Anchor.Absolute a -> {
                instPos = a.pos();
                instRot = a.rot();
            }
            case Anchor.InSlot s -> {
                instPos = slots.resolve(s.slotId()).orElse(null);
                instRot = s.rot();
                if (instPos == null) {
                    issues.add(Issue.of(IssueCode.E_ANCHOR, "slot", List.of(instance.id()),
                            "スロット" + s.slotId() + "を解決できません(スロットは建屋の意味解析が載るP6以降で使えます)"));
                    return false;
                }
            }
            case Anchor.OnSurface s -> {
                issues.add(Issue.of(IssueCode.E_ANCHOR, "anchor", List.of(instance.id()), "モジュールは壁の面には付けられません"));
                return false;
            }
        }
        boolean turned = !instRot.equals(Rot.NONE);
        String prefix = instance.id() + ID_SEPARATOR;
        List<PlanNode> produced = new ArrayList<>();
        for (PlanNode t : template.nodes()) {
            if (turned && BuildingParts.ROTATION_UNSUPPORTED.contains(t.type())) {
                issues.add(Issue.of(IssueCode.E_ANCHOR, "rot", List.of(instance.id()),
                        "回転・鏡像を付けたモジュールに、回転できない部品(" + t.type() + ")は入れられません"));
                return false;
            }
            Anchor anchor;
            switch (t.anchor()) {
                case Anchor.Absolute a -> {
                    LocalPos rotated = instRot.apply(a.pos());
                    LocalPos placed = t.parent() == null ? rotated.plus(instPos.u(), instPos.v(), instPos.w()) : rotated;
                    anchor = new Anchor.Absolute(placed, Rot.compose(instRot, a.rot()));
                }
                case Anchor.OnSurface s -> anchor = new Anchor.OnSurface(prefix + s.nodeId(), s.side(), s.u(), s.v());
                case Anchor.InSlot s -> {
                    issues.add(Issue.of(IssueCode.E_ANCHOR, "anchor", List.of(prefix + t.id()), "テンプレートの中でスロットは使えません"));
                    return false;
                }
            }
            String parent = t.parent() == null ? instance.parent() : prefix + t.parent();
            produced.add(new PlanNode(prefix + t.id(), t.type(), parent, anchor, t.params(), t.tags(), t.label()));
        }
        out.addAll(produced);
        for (Connection c : template.internal()) {
            connections.add(new Connection(prefix + c.id(), prefixed(c.from(), prefix), prefixed(c.to(), prefix), c.kind(),
                    c.routing() instanceof Routing.Explicit e ? new Routing.Explicit(e.viaNodeIds().stream().map(v -> prefix + v).toList()) : c.routing(),
                    c.constraints()));
        }
        return true;
    }

    private static PortRef prefixed(PortRef ref, String prefix) {
        return new PortRef(prefix + ref.nodeId(), ref.port());
    }

    private boolean portExists(PortRef ref, Map<String, PlanNode> primitiveById, Map<String, ModuleTemplate> modules) {
        ModuleTemplate module = modules.get(ref.nodeId());
        if (module != null) {
            return module.port(ref.port()).isPresent();
        }
        PlanNode node = primitiveById.get(ref.nodeId());
        if (node == null) {
            return false;
        }
        PartType type = registry.find(node.type()).orElse(null);
        return type != null && type.port(ref.port()).isPresent();
    }

    private void resolveConnection(SemanticPlan plan, Connection c, Map<String, PlanNode> primitiveById,
                                   Map<String, ModuleTemplate> modules, Map<String, LocalPos> origins, Router router,
                                   List<RoutedConnection> routed, List<Issue> issues) {
        boolean ok = true;
        for (PortRef ref : List.of(c.from(), c.to())) {
            if (!portExists(ref, primitiveById, modules)) {
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, ref.nodeId() + "." + ref.port(), List.of(c.id()),
                        "つなぎ口がありません: " + ref.nodeId() + "." + ref.port()));
                ok = false;
            }
        }
        if (!ok) {
            return;
        }
        if (c.routing() instanceof Routing.Explicit e) {
            List<LocalPos> path = new ArrayList<>();
            for (String via : e.viaNodeIds()) {
                if (!primitiveById.containsKey(via)) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, "via:" + via, List.of(c.id()), "経由する部品がありません: " + via));
                    return;
                }
                LocalPos origin = origins.get(via);
                if (origin != null) {
                    path.add(origin);
                }
            }
            routed.add(new RoutedConnection(c.id(), List.of(), path));
            return;
        }
        router.route(plan, c).ifPresentOrElse(routed::add, () -> issues.add(Issue.of(IssueCode.E_NO_ROUTE, List.of(c.id()),
                "経路を自動で作るRouterは、P11で載るまで使えません。経由する部品を書くか(connectのviaに部品IDを並べる)、載るまで待ってください")));
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: PlanExpander(テンプレートの展開と、明示接続の解決。Autoは載るP11まで拒否)を追加(自然言語→工場建設 P3 Task 9)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 10: 施工リスト・置いてよいブロック・コンパイラの土台と、`structure`・`foundation`・`floor`・`wall`

**Files:**
- Create(`build.compile`): `ReplacePolicy.java`、`Placement.java`、`PhaseRange.java`、`AssemblyStep.java`、`PlacementManifest.java`、`CompileResult.java`、`SurveyRef.java`、`ManifestJson.java`、`BomCalculator.java`、`PlaceableBlockPolicy.java`、`BuiltinAllowList.java`、`PlanCompiler.java`
- Create(`build.compile.gen`): `GenAbort.java`、`Canvas.java`、`Palette.java`、`BlockForms.java`、`StructureInfo.java`、`WallInfo.java`、`GenContext.java`、`PartGenerator.java`、`PartGenerators.java`、`StructureGen.java`、`FoundationGen.java`、`FloorGen.java`、`WallGen.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/{CompileFixtures,PlaceableBlockPolicyTest,BomCalculatorTest,PlanCompilerTest}.java`

**Interfaces:**
- Consumes: Task 2〜9の型(`ExpandedPlan`・`PlanExpander`・`Origins`・`Placement`用の`BuildPhase`・`PlacerId`・`VerifyMode`・`PartTypeRegistry`・`Params`・`MaterialFamilies`・`BlockRotation`・`Issue`)
- Produces:
  - `sealed interface ReplacePolicy`: `AirOnly`(`AIR_ONLY`)・`Replaceable`(`REPLACEABLE`)・`Expect(String blockId)`、`String code()`(`"air_only"`・`"replaceable"`・`"expect:<id>"`)
  - `record Placement(int index, IntPos pos, BlockSpec block, Map<String,String> blockEntityConfig, String partNodeId, BuildPhase phase, PlacerId placer, VerifyMode verify, ReplacePolicy replaces, String assemblyGroup)`
  - `record PhaseRange(BuildPhase phase, int fromIndex, int toIndexExclusive)`、`record AssemblyStep(String groupId, AssemblyKind kind, IntPos trigger, List<Integer> memberIndexes, AssemblyExpectation expect)`
  - `record PlacementManifest(int manifestVersion, String planId, int planRevision, String registryVersion, String dimension, BuildFrame frame, Box worldBounds, List<Placement> placements, List<AssemblyStep> assemblies, Map<String,Integer> bom, List<PhaseRange> phases, String hash)`、`MANIFEST_VERSION=1`
  - `record CompileResult(PlacementManifest manifest, List<Issue> issues)`(ERRORがあれば`manifest`は`null`)、`record SurveyRef(String digest, long cachedUntilTick)`
  - `ManifestJson.toTree(PlacementManifest)`(全項目とハッシュ)、`ManifestJson.computeHash(dimension, registryVersion, worldBounds, placements, assemblies, bom)`
  - `BomCalculator.bom(List<Placement>) → Map<String,Integer>`(品物ID→個数。ドアの上半分は0、`type=double`のスラブは2、壁掛け看板は`<木>_sign`、`create:belt`は`create:belt_connector`、空気は0)
  - `PlaceableBlockPolicy`: `ALWAYS_FORBIDDEN`、`PlaceableBlockPolicy(Set<String> paletteAllowed)`、`static PlaceableBlockPolicy builtin()`、`boolean isAlwaysForbidden(String blockId)`、`Optional<String> checkMaterial(String blockId)`(禁止または許可リスト外なら理由)、`Set<String> allowed()`
  - `PlanCompiler(int maxCells)`(既定200,000)・`compile(ExpandedPlan, PartTypeRegistry, PlaceableBlockPolicy, SurveyRef) → CompileResult`
  - `gen`パッケージ: `PartGenerator { void generate(GenContext ctx, PlanNode node, Params p); default void afterAll(GenContext ctx, PlanNode node, Params p) {} }`、`PartGenerators.find(String partId) → Optional<Entry(PartGenerator, Stage)>`(`Stage {BASE, CARVE}`)、`PartGenerators.ids()`、`GenContext`(下記)

**`GenContext`の契約(生成器が使う。以降のタスクも同じ):**
- `Palette palette()`: `String full(String material, PlanNode node)`・`String stairs(...)`・`String slab(...)`。役割が無い・族が無い場合は、`E-PARAM-RANGE`(`key`=`material`)の`GenAbort`を投げる(族が無い場合の`FixHint(USE_MATERIAL)`に、族のある候補を入れる)
- `void emit(PlanNode node, int du, int dv, int dw, BlockSpec block)`・`emit(..., Map<String,String> blockEntity)`: ノードの原点からのずれで置く。ノードの`Rot`(鏡像→回転)を、位置とブロック状態にかける
- `void emitAbs(PlanNode node, LocalPos pos, BlockSpec block)`・`emitAbs(..., String mergeGroup, ...)`: 局所の絶対位置で置く(親の箱や壁の面に依存する部品。`Rot`は使わない)。同じ`mergeGroup`で同じブロックの重なりは合流する
- `StructureInfo structureOf(PlanNode node)`: 親が`micra:structure`でなければ`E-ANCHOR`の`GenAbort`
- `Optional<WallInfo> wallInfo(String wallId)`・`WallInfo wallOfAnchor(PlanNode node)`(`OnSurface`でなければ`E-ANCHOR`)
- `void carve(PlanNode opener, WallInfo wall, List<LocalPos> cells)`: 壁のマスを掘る。壁のマスでなければ`E-OPENING-NO-WALL`、掘った位置の再掘は`E-OVERLAP`(いずれも`GenAbort`)
- `GenAbort fail(PlanNode node, IssueCode code, String key, String message)`
- `StructureInfo(String id, LocalPos origin, int width, int depth, int floors, int floorHeight)`、`WallInfo(String id, StructureInfo structure, Facing side, int level, int baseV, int height, int thickness, int from, int length)`: `LocalPos cell(int i, int layer, int row)`(壁に沿って`i`個目、外側から`layer`層目(負は壁の外)、下から`row`段目)、`Facing outward()`(=`side`)、`Facing along()`(北・南の壁は`EAST`、東・西の壁は`NORTH`)
- `BlockForms`: `plain(id)`・`stairs(id, Facing back, boolean top)`・`slab(id, boolean top)`・`door(id, Facing facing, boolean upper, boolean hingeRight)`・`gate(id, Facing facing)`・`ladder(Facing)`・`wallSign(id, Facing)`・`lantern(boolean hanging)`・`axisBlock(id, String axis)`・`isAxisBlock(id)`・`flat(id)`・`verifyFor(BlockSpec)`

**コンパイルの流れ(テストで固定):**
1. `site`が無ければ`E-SITE-MISSING`。
2. 部品を、深さ(親の連鎖の長さ)→IDの順に並べ、`BASE`段の生成器を全部走らせ、そのあと`CARVE`段(`door`・`window`)を走らせ、最後に各部品の`afterAll`を走らせる。生成器を持たない部品(`micra:`以外や、まだ施工できない部品)は`E-UNKNOWN-PART`(「この版では施工できません」)。`Rot`が付いた`ROTATION_UNSUPPORTED`の部品は`E-ANCHOR`。
3. 重なり(`E-OVERLAP`。部品の組ごとに1件、`data.count`と`data.firstPos`)、敷地の外(`E-OUT-OF-BOUNDS`。部品ごとに1件)、置けないブロック(`E-BLOCK-FORBIDDEN`。`ALWAYS_FORBIDDEN`に載る最終ブロック、または、パレットから引いた素材が許可リスト外。使った部品を`subjects`に)、セル数が`maxCells`を超える(`E-OUT-OF-BOUNDS`)。
4. ERRORが1つでもあれば`manifest=null`。無ければ、セルを`BuildPhase`→v→w→uの順に並べ、`BuildFrame`で世界へ写し(ブロックの向きも`facing`の回転数だけ回す)、`Placement`(`replaces=REPLACEABLE`、`placer`は部品の物、`verify`は`BlockForms.verifyFor`)にして`index`を振り、`bom`・`phases`・`worldBounds`(`Site.localBounds`の8隅を世界へ写した外接箱)・`hash`を作る。

- [ ] **Step 1: 失敗するテストを書く**

`CompileFixtures.java`(以降の生成器のテストが共有):
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds plans and compiles them with the building parts. Tests use a NORTH frame so world offsets equal local ones. */
public final class CompileFixtures {
    public static final IntPos ORIGIN = new IntPos(100, 64, 200);
    public static final Box BOUNDS = new Box(-30, -30, -30, 90, 90, 90);
    public static final PartTypeRegistry REGISTRY = TestParts.registry();

    private CompileFixtures() {
    }

    public static Site site(Facing facing) {
        return new Site("minecraft:overworld", new BuildFrame(ORIGIN, facing), BOUNDS, "", "");
    }

    public static IntV i(int v) {
        return new IntV(v);
    }

    public static StrV s(String v) {
        return new StrV(v);
    }

    public static BoolV b(boolean v) {
        return new BoolV(v);
    }

    public static PlanNode node(String id, String type, String parent, int u, int v, int w, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE), params, Set.of(), "");
    }

    public static PlanNode ruled(String id, String type, String parent, Rot rot, int u, int v, int w, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), rot), params, Set.of(), "");
    }

    public static PlanNode onWall(String id, String type, String parent, String wall, Side side, int u, int v,
                                  Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.OnSurface(wall, side, u, v), params, Set.of(), "");
    }

    /** A 7x7, one-floor, four-metre building named "s" plus its four walls named wall-n/e/s/w. */
    public static List<PlanNode> shell(int width, int depth, int floors, int floorHeight) {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node("s", "micra:structure", null, 0, 0, 0,
                Map.of("width", i(width), "depth", i(depth), "floors", i(floors), "floor_height", i(floorHeight))));
        for (String side : List.of("north", "east", "south", "west")) {
            nodes.add(node("wall-" + side.charAt(0), "micra:wall", "s", 0, 0, 0, Map.of("side", s(side))));
        }
        return nodes;
    }

    public static SemanticPlan plan(Site site, StyleSpec style, List<PlanNode> nodes) {
        PlanPatcher patcher = new PlanPatcher(REGISTRY, TemplateBundle.EMPTY);
        List<PlanOp> ops = new ArrayList<>();
        if (site != null) {
            ops.add(new PlanOp.SetSite(site));
        }
        ops.add(new PlanOp.SetStyle(style));
        for (PlanNode n : nodes) {
            ops.add(new PlanOp.AddNode(n));
        }
        PatchResult r = patcher.apply(SemanticPlan.empty("plan"), new PlanPatch("p", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    public static CompileResult compile(SemanticPlan plan) {
        return compile(plan, new PlanCompiler());
    }

    public static CompileResult compile(SemanticPlan plan, PlanCompiler compiler) {
        ExpandResult expanded = new PlanExpander(REGISTRY, SlotResolver.NONE).expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(expanded.issues().isEmpty(), expanded.issues().toString());
        return compiler.compile(expanded.plan(), REGISTRY, PlaceableBlockPolicy.builtin(), new SurveyRef("digest", 0L));
    }

    public static CompileResult compile(List<PlanNode> nodes) {
        return compile(plan(site(Facing.NORTH), StyleSpec.EMPTY, nodes));
    }

    public static CompileResult compile(StyleSpec style, List<PlanNode> nodes) {
        return compile(plan(site(Facing.NORTH), style, nodes));
    }

    /** Local position to block, read back from a NORTH-frame manifest (properties are unrotated in that frame). */
    public static Map<LocalPos, BlockSpec> cells(PlacementManifest m) {
        BuildFrame frame = m.frame();
        Map<LocalPos, BlockSpec> out = new TreeMap<>(java.util.Comparator
                .comparingInt(LocalPos::v).thenComparingInt(LocalPos::w).thenComparingInt(LocalPos::u));
        for (Placement p : m.placements()) {
            out.put(frame.toLocal(p.pos()), p.block());
        }
        return out;
    }

    public static List<String> codes(CompileResult r) {
        return r.issues().stream().map(x -> x.code().label()).toList();
    }

    public static Map<String, ParamValue> params(Object... keyValues) {
        Map<String, ParamValue> m = new TreeMap<>();
        for (int k = 0; k < keyValues.length; k += 2) {
            Object v = keyValues[k + 1];
            ParamValue pv = v instanceof ParamValue p ? p : v instanceof Integer n ? new IntV(n)
                    : v instanceof Boolean flag ? new BoolV(flag) : new StrV(String.valueOf(v));
            m.put((String) keyValues[k], pv);
        }
        return m;
    }

    public static long countOf(Map<LocalPos, BlockSpec> cells, String blockId) {
        return cells.values().stream().filter(b -> b.blockId().equals(blockId)).count();
    }
}
```

`PlaceableBlockPolicyTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceableBlockPolicyTest {
    private final PlaceableBlockPolicy policy = PlaceableBlockPolicy.builtin();

    @Test
    void theAlwaysForbiddenBlocksAreRefusedEvenIfListed() {
        for (String id : PlaceableBlockPolicy.ALWAYS_FORBIDDEN) {
            assertTrue(policy.isAlwaysForbidden(id), id);
            assertTrue(policy.checkMaterial(id).isPresent(), id);
            assertTrue(new PlaceableBlockPolicy(Set.of(id)).checkMaterial(id).isPresent(), "listing must not override: " + id);
        }
        for (String id : List.of("minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
                "minecraft:bedrock", "minecraft:spawner", "minecraft:barrier", "minecraft:structure_block", "minecraft:jigsaw",
                "minecraft:light")) {
            assertTrue(PlaceableBlockPolicy.ALWAYS_FORBIDDEN.contains(id), id);
        }
    }

    @Test
    void theBuiltinListCoversTheDefaultPaletteAndTheFamilies() {
        for (String id : BuildingParts.DEFAULT_PALETTE.values()) {
            assertTrue(policy.checkMaterial(id).isEmpty(), id);
        }
        for (String full : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(full).orElseThrow();
            assertTrue(policy.checkMaterial(f.full()).isEmpty(), f.full());
            if (f.stairs() != null) {
                assertTrue(policy.checkMaterial(f.stairs()).isEmpty(), f.stairs());
            }
            assertTrue(policy.checkMaterial(f.slab()).isEmpty(), f.slab());
        }
        assertTrue(policy.checkMaterial("minecraft:red_terracotta").isEmpty());
        assertTrue(policy.checkMaterial("minecraft:red_concrete").isEmpty());
        assertTrue(policy.checkMaterial("minecraft:oak_wall_sign").isEmpty());
    }

    @Test
    void unlistedBlocksAreRefusedWithAReason() {
        assertTrue(policy.checkMaterial("minecraft:tnt").orElseThrow().contains("許可"));
        assertFalse(policy.checkMaterial("minecraft:stone").isPresent());
    }
}
```
(`java.util.List`のimportを足す。)

`BomCalculatorTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BomCalculatorTest {
    private static Placement at(int index, BlockSpec block) {
        return new Placement(index, new IntPos(index, 0, 0), block, Map.of(), "n", BuildPhase.STRUCTURE, PlacerId.SIMPLE,
                VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
    }

    @Test
    void countsItemsByBlockWithTheSpecialCases() {
        List<Placement> ps = new ArrayList<>();
        ps.add(at(0, BlockSpec.of("minecraft:stone")));
        ps.add(at(1, BlockSpec.of("minecraft:stone")));
        ps.add(at(2, BlockSpec.of("minecraft:oak_door", "half", "lower")));
        ps.add(at(3, BlockSpec.of("minecraft:oak_door", "half", "upper")));
        ps.add(at(4, BlockSpec.of("minecraft:oak_slab", "type", "double")));
        ps.add(at(5, BlockSpec.of("minecraft:oak_slab", "type", "bottom")));
        ps.add(at(6, BlockSpec.of("minecraft:oak_wall_sign", "facing", "north")));
        ps.add(at(7, BlockSpec.of("create:belt")));
        ps.add(at(8, BlockSpec.AIR));
        Map<String, Integer> bom = BomCalculator.bom(ps);
        assertEquals(2, bom.get("minecraft:stone"));
        assertEquals(1, bom.get("minecraft:oak_door"));
        assertEquals(3, bom.get("minecraft:oak_slab"), "double slab counts twice");
        assertEquals(1, bom.get("minecraft:oak_sign"));
        assertEquals(1, bom.get("create:belt_connector"));
        assertEquals(false, bom.containsKey("minecraft:air"));
        assertEquals(List.of("create:belt_connector", "minecraft:oak_door", "minecraft:oak_sign", "minecraft:oak_slab", "minecraft:stone"),
                List.copyOf(bom.keySet()), "sorted by item id");
    }
}
```

`PlanCompilerTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.b;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.i;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.s;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanCompilerTest {
    /** 5x5 building, one floor of height 4: floor slab at v=0, walls at v=1..3. */
    private static List<PlanNode> box5(PlanNode... extra) {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(node("f", "micra:floor", "s", 0, 0, 0, Map.of()));
        nodes.addAll(List.of(extra));
        return nodes;
    }

    @Test
    void aFloorAndFourWallsGiveTheDerivedCellCounts() {
        CompileResult r = compile(box5());
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        PlacementManifest m = r.manifest();
        // floor 5x5 = 25; wall ring 16 cells x 3 rows = 48 (corners are shared)
        assertEquals(25 + 48, m.placements().size());
        assertEquals(List.of(new PhaseRange(BuildPhase.STRUCTURE, 0, 25), new PhaseRange(BuildPhase.ENVELOPE, 25, 73)), m.phases());
        Map<LocalPos, BlockSpec> c = cells(m);
        assertEquals(25, countOf(c, "minecraft:oak_planks"));
        assertEquals(48, countOf(c, "minecraft:stone_bricks"));
        assertEquals(Map.of("minecraft:oak_planks", 25, "minecraft:stone_bricks", 48), m.bom());
        assertEquals(BlockSpec.of("minecraft:oak_planks"), c.get(new LocalPos(2, 0, 2)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(4, 3, 4)));
        assertNull(c.get(new LocalPos(2, 1, 2)), "the inside is empty");
        assertNull(c.get(new LocalPos(0, 4, 0)), "walls stop one row under the roof base");
    }

    @Test
    void placementsAreOrderedByPhaseThenVWU_andIndexedAndMappedToTheWorld() {
        PlacementManifest m = compile(box5()).manifest();
        for (int k = 0; k < m.placements().size(); k++) {
            assertEquals(k, m.placements().get(k).index());
        }
        Placement first = m.placements().get(0);
        assertEquals(new IntPos(100, 64, 200), first.pos(), "local (0,0,0) is the frame origin");
        assertEquals(new IntPos(101, 64, 200), m.placements().get(1).pos(), "u grows first");
        assertEquals(BuildPhase.STRUCTURE, first.phase());
        assertEquals(new IntPos(100, 65, 200), m.placements().get(25).pos(), "first wall cell: v=1, w=0, u=0");
        assertEquals(io.github.khayashi4337.micradrone.build.parts.VerifyMode.EXACT, first.verify());
        assertEquals(ReplacePolicy.REPLACEABLE, first.replaces());
        assertEquals("f", first.partNodeId());
        assertEquals("minecraft:overworld", m.dimension());
    }

    @Test
    void theHashIsDeterministicAndRecordsTheRegistryVersion() {
        PlacementManifest a = compile(box5()).manifest();
        PlacementManifest b = compile(box5()).manifest();
        assertEquals(a.hash(), b.hash());
        assertEquals(64, a.hash().length());
        assertEquals(CompileFixtures.REGISTRY.version(), a.registryVersion());
        assertEquals(1, a.manifestVersion());
        List<PlanNode> bigger = new ArrayList<>(box5());
        bigger.set(0, node("s", "micra:structure", null, 0, 0, 0, params("width", 5, "depth", 5, "floors", 1, "floor_height", 5)));
        assertTrue(!compile(bigger).manifest().hash().equals(a.hash()));
    }

    @Test
    void theWorldBoundsAreTheSiteBoundsMappedToTheWorld() {
        PlacementManifest m = compile(box5()).manifest();
        // local bounds (-30..90) on a NORTH frame at (100,64,200): x = 100+u, y = 64+v, z = 200-w
        assertEquals(new io.github.khayashi4337.micradrone.build.model.Box(70, 34, 110, 190, 154, 230), m.worldBounds());
    }

    @Test
    void aMissingSiteIsAnIssueNotACrash() {
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(null, StyleSpec.EMPTY, List.of()));
        assertNull(r.manifest());
        assertEquals(List.of("E-SITE-MISSING"), codes(r));
    }

    @Test
    void foundationFillsBelowTheFloorWithAMargin() {
        List<PlanNode> nodes = box5(node("fd", "micra:foundation", "s", 0, 0, 0, params("margin", 1, "depth", 2)));
        Map<LocalPos, BlockSpec> c = cells(compile(nodes).manifest());
        assertEquals(7 * 7 * 2, countOf(c, "minecraft:cobblestone"));
        assertEquals(BlockSpec.of("minecraft:cobblestone"), c.get(new LocalPos(-1, -2, -1)));
        assertEquals(BlockSpec.of("minecraft:cobblestone"), c.get(new LocalPos(5, -1, 5)));
        assertNull(c.get(new LocalPos(6, -1, 0)));
    }

    @Test
    void floorHolesAndSlabKind() {
        List<PlanNode> withHole = new ArrayList<>(shell(5, 5, 1, 4));
        withHole.add(node("f", "micra:floor", "s", 0, 0, 0, params("holes", new io.github.khayashi4337.micradrone.build.model.ParamValue.ListV(
                List.of(i(1), i(1), i(2), i(2))))));
        assertEquals(21, countOf(cells(compile(withHole).manifest()), "minecraft:oak_planks"));

        List<PlanNode> slab = new ArrayList<>(shell(5, 5, 1, 4));
        slab.add(node("f", "micra:floor", "s", 0, 0, 0, params("kind", "slab")));
        assertEquals(BlockSpec.of("minecraft:oak_slab", "type", "bottom"), cells(compile(slab).manifest()).get(new LocalPos(1, 0, 1)));

        List<PlanNode> badHoles = new ArrayList<>(shell(5, 5, 1, 4));
        badHoles.add(node("f", "micra:floor", "s", 0, 0, 0, params("holes", new io.github.khayashi4337.micradrone.build.model.ParamValue.ListV(
                List.of(i(1), i(1), i(2))))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badHoles)));
    }

    @Test
    void wallOptionsThicknessHalfFromLengthAndLevel() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 2, 4));
        nodes.removeIf(n -> !n.id().equals("s") && !n.id().equals("wall-n"));
        nodes.set(1, node("wall-n", "micra:wall", "s", 0, 0, 0, params("side", "north", "thickness", 2, "part", "half", "from", 1, "length", 3, "level", 1)));
        Map<LocalPos, BlockSpec> c = cells(compile(nodes).manifest());
        // north wall: w=4 and w=3, u=1..3, half of height 3 = 2 rows, level 1 base v = 1*4+1 = 5
        assertEquals(3 * 2 * 2, c.size());
        assertTrue(c.containsKey(new LocalPos(1, 5, 4)));
        assertTrue(c.containsKey(new LocalPos(3, 6, 3)));
        assertTrue(!c.containsKey(new LocalPos(0, 5, 4)));
        assertTrue(!c.containsKey(new LocalPos(1, 7, 4)));
    }

    @Test
    void wallParameterAndAnchorProblemsAreIssues() {
        List<PlanNode> badFrom = new ArrayList<>(shell(5, 5, 1, 4));
        badFrom.set(1, node("wall-n", "micra:wall", "s", 0, 0, 0, params("side", "north", "from", 5)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badFrom)));
        List<PlanNode> tooLong = new ArrayList<>(shell(5, 5, 1, 4));
        tooLong.set(1, node("wall-n", "micra:wall", "s", 0, 0, 0, params("side", "north", "from", 2, "length", 4)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(tooLong)));
        List<PlanNode> badLevel = new ArrayList<>(shell(5, 5, 1, 4));
        badLevel.set(1, node("wall-n", "micra:wall", "s", 0, 0, 0, params("side", "north", "level", 1)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badLevel)));
        List<PlanNode> noParent = List.of(node("wall-n", "micra:wall", null, 0, 0, 0, params("side", "north")));
        assertEquals(List.of("E-ANCHOR"), codes(compile(noParent)));
        List<PlanNode> wrongParent = List.of(node("p", "micra:pillar", null, 0, 0, 0, Map.of()),
                node("wall-n", "micra:wall", "p", 0, 0, 0, params("side", "north")));
        assertEquals(List.of("E-ANCHOR"), codes(compile(wrongParent)));
    }

    @Test
    void structuresCannotBeRotatedAlone() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.set(0, CompileFixtures.ruled("s", "micra:structure", null, new Rot(1, false), 0, 0, 0, params("width", 5, "depth", 5)));
        assertTrue(codes(compile(nodes)).contains("E-ANCHOR"));
    }

    @Test
    void overlappingWallsWithDifferentBlocksAreReportedOncePerPair_identicalCornersMerge() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.set(1, node("wall-n", "micra:wall", "s", 0, 0, 0, params("side", "north", "material", "minecraft:stone")));
        nodes.set(2, node("wall-e", "micra:wall", "s", 0, 0, 0, params("side", "east", "material", "minecraft:bricks")));
        CompileResult r = compile(nodes);
        assertNull(r.manifest());
        List<Issue> overlaps = r.issues().stream().filter(x -> x.code() == IssueCode.E_OVERLAP).toList();
        assertEquals(1, overlaps.size(), r.issues().toString());
        assertEquals(List.of("wall-e", "wall-n"), overlaps.get(0).subjects());
        assertEquals("3", overlaps.get(0).data().get("count"));
    }

    @Test
    void cellsOutsideTheSiteBoundsAreReportedPerPart() {
        io.github.khayashi4337.micradrone.build.model.Site tight = new io.github.khayashi4337.micradrone.build.model.Site(
                "minecraft:overworld", new io.github.khayashi4337.micradrone.build.model.BuildFrame(CompileFixtures.ORIGIN, Facing.NORTH),
                new io.github.khayashi4337.micradrone.build.model.Box(0, 0, 0, 4, 2, 4), "", "");
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(tight, StyleSpec.EMPTY, box5()));
        assertNull(r.manifest());
        List<Issue> out = r.issues().stream().filter(x -> x.code() == IssueCode.E_OUT_OF_BOUNDS).toList();
        assertEquals(4, out.size(), "the four walls reach v=3, above the bounds: " + r.issues());
        assertTrue(out.stream().allMatch(x -> x.subjects().size() == 1));
    }

    @Test
    void theCellBudgetStopsHugePlans() {
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, box5()),
                new PlanCompiler(50));
        assertNull(r.manifest());
        assertTrue(r.issues().stream().anyMatch(x -> x.code() == IssueCode.E_OUT_OF_BOUNDS && x.data().containsKey("cells")), r.issues().toString());
    }

    @Test
    void forbiddenAndUnlistedMaterialsAreRefusedOnEveryRoute() {
        // 1) a block id given as the material
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(compile(box5node("minecraft:command_block"))));
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(compile(box5node("minecraft:tnt"))));
        // 2) through the style palette (a role)
        CompileResult viaPalette = compile(new StyleSpec(Map.of("floor", "minecraft:bedrock"), Set.of()), box5());
        assertNull(viaPalette.manifest());
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(viaPalette));
        assertEquals(List.of("f"), viaPalette.issues().get(0).subjects());
        // 3) an allowed material passes
        assertNotNull(compile(new StyleSpec(Map.of("wall", "minecraft:red_terracotta"), Set.of()), box5()).manifest());
    }

    private static List<PlanNode> box5node(String material) {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(node("f", "micra:floor", "s", 0, 0, 0, params("material", material)));
        return nodes;
    }

    @Test
    void anUnknownPaletteRoleIsAParamRangeIssueWithAHint() {
        CompileResult r = compile(box5node("no_such_role"));
        assertEquals(List.of("E-PARAM-RANGE"), codes(r));
        assertEquals("material", r.issues().get(0).id().substring(r.issues().get(0).id().indexOf('#') + 1));
    }

    @Test
    void partsWithoutAGeneratorAreRefused() {
        CompileResult r = compile(List.of(node("m", "test:motor", null, 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-UNKNOWN-PART"), codes(r));
    }

    @Test
    void rotatingTheSiteRotatesTheWorldPositionsAndKeepsTheLocalCells() {
        PlacementManifest north = compile(CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, box5())).manifest();
        PlacementManifest east = compile(CompileFixtures.plan(CompileFixtures.site(Facing.EAST), StyleSpec.EMPTY, box5())).manifest();
        assertEquals(north.placements().size(), east.placements().size());
        // local (u,w) = (1,0) is world (+1,0) on NORTH but world (0,+1) on EAST (right of east is south)
        assertEquals(new IntPos(101, 64, 200), north.placements().get(1).pos());
        assertEquals(new IntPos(100, 64, 201), east.placements().get(1).pos());
        assertTrue(!north.hash().equals(east.hash()));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.*" --console=plain`
Expected: FAIL(`cannot find symbol`)。

- [ ] **Step 3: 実装する**

`ReplacePolicy.java`・`Placement.java`・`PhaseRange.java`・`AssemblyStep.java`・`PlacementManifest.java`・`CompileResult.java`・`SurveyRef.java`(`package io.github.khayashi4337.micradrone.build.compile`):
```java
// ReplacePolicy.java
public sealed interface ReplacePolicy {
    AirOnly AIR_ONLY = new AirOnly();
    Replaceable REPLACEABLE = new Replaceable();

    record AirOnly() implements ReplacePolicy {
    }

    /** May replace the naturally replaceable blocks (air, water, grass, snow, leaves ...); the runtime decides. */
    record Replaceable() implements ReplacePolicy {
    }

    record Expect(String blockId) implements ReplacePolicy {
    }

    default String code() {
        return switch (this) {
            case AirOnly a -> "air_only";
            case Replaceable r -> "replaceable";
            case Expect e -> "expect:" + e.blockId();
        };
    }
}

// Placement.java
public record Placement(int index, IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, String partNodeId,
                        BuildPhase phase, PlacerId placer, VerifyMode verify, ReplacePolicy replaces, String assemblyGroup) {
    public Placement {
        blockEntityConfig = Collections.unmodifiableSortedMap(new TreeMap<>(blockEntityConfig == null ? Map.of() : blockEntityConfig));
    }
}

// PhaseRange.java
public record PhaseRange(BuildPhase phase, int fromIndex, int toIndexExclusive) {
}

// AssemblyStep.java
public record AssemblyStep(String groupId, AssemblyKind kind, IntPos trigger, List<Integer> memberIndexes,
                           AssemblyExpectation expect) {
    public AssemblyStep {
        memberIndexes = List.copyOf(memberIndexes);
    }
}

// PlacementManifest.java
public record PlacementManifest(int manifestVersion, String planId, int planRevision, String registryVersion, String dimension,
                                BuildFrame frame, Box worldBounds, List<Placement> placements, List<AssemblyStep> assemblies,
                                Map<String, Integer> bom, List<PhaseRange> phases, String hash) {
    public static final int MANIFEST_VERSION = 1;

    public PlacementManifest {
        placements = List.copyOf(placements);
        assemblies = List.copyOf(assemblies);
        bom = Collections.unmodifiableSortedMap(new TreeMap<>(bom));
        phases = List.copyOf(phases);
    }
}

// CompileResult.java
public record CompileResult(PlacementManifest manifest, List<Issue> issues) {
    public CompileResult {
        issues = List.copyOf(issues);
    }
}

// SurveyRef.java
/** The server-issued, pinned terrain survey the compile was based on. P3 records it only; P4 uses it for terraforming. */
public record SurveyRef(String digest, long cachedUntilTick) {
}
```
(各ファイルの`import`は使う型に合わせる。)

`ManifestJson.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation;
import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tree form of a manifest and its hash. The hash covers what is built, not how it is labelled (no index, no node ids). */
public final class ManifestJson {
    private ManifestJson() {
    }

    static Map<String, Object> placementTree(Placement p, boolean forHash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pos", new ArrayList<>(List.of((long) p.pos().x(), (long) p.pos().y(), (long) p.pos().z())));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", p.block().blockId());
        block.put("props", new TreeMap<>(p.block().properties()));
        m.put("block", block);
        m.put("be", new TreeMap<>(p.blockEntityConfig()));
        m.put("phase", p.phase().name());
        m.put("placer", p.placer().name());
        m.put("verify", p.verify().name());
        m.put("replaces", p.replaces().code());
        m.put("group", p.assemblyGroup());
        if (!forHash) {
            m.put("index", p.index());
            m.put("node", p.partNodeId());
        }
        return m;
    }

    static Map<String, Object> assemblyTree(AssemblyStep a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", a.groupId());
        m.put("kind", a.kind().name());
        m.put("trigger", new ArrayList<>(List.of((long) a.trigger().x(), (long) a.trigger().y(), (long) a.trigger().z())));
        m.put("members", new ArrayList<>(a.memberIndexes()));
        if (a.expect() instanceof AssemblyExpectation.ContraptionExpectation c) {
            m.put("expect", Map.of("type", "contraption", "count", c.entityCount(), "blocks", c.movedBlockCount()));
        } else if (a.expect() instanceof AssemblyExpectation.SubLevelExpectation s) {
            m.put("expect", Map.of("type", "sublevel", "count", s.subLevelCount(), "blocks", s.movedBlockCount()));
        } else {
            m.put("expect", null);
        }
        return m;
    }

    public static Map<String, Object> hashTree(String dimension, String registryVersion, Box worldBounds, List<Placement> placements,
                                               List<AssemblyStep> assemblies, Map<String, Integer> bom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dimension);
        m.put("registryVersion", registryVersion);
        m.put("worldBounds", PlanJson.boxTree(worldBounds));
        List<Object> ps = new ArrayList<>();
        for (Placement p : placements) {
            ps.add(placementTree(p, true));
        }
        m.put("placements", ps);
        List<Object> as = new ArrayList<>();
        for (AssemblyStep a : assemblies) {
            as.add(assemblyTree(a));
        }
        m.put("assemblies", as);
        m.put("bom", new TreeMap<>(bom));
        return m;
    }

    public static String computeHash(String dimension, String registryVersion, Box worldBounds, List<Placement> placements,
                                     List<AssemblyStep> assemblies, Map<String, Integer> bom) {
        return Hashing.sha256Hex(CanonicalJson.write(hashTree(dimension, registryVersion, worldBounds, placements, assemblies, bom)));
    }

    public static Map<String, Object> toTree(PlacementManifest m) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("manifestVersion", m.manifestVersion());
        t.put("planId", m.planId());
        t.put("planRevision", m.planRevision());
        t.put("registryVersion", m.registryVersion());
        t.put("dimension", m.dimension());
        t.put("frame", Map.of("origin", new ArrayList<>(List.of((long) m.frame().origin().x(), (long) m.frame().origin().y(),
                (long) m.frame().origin().z())), "facing", m.frame().facing().lower()));
        t.put("worldBounds", PlanJson.boxTree(m.worldBounds()));
        List<Object> ps = new ArrayList<>();
        for (Placement p : m.placements()) {
            ps.add(placementTree(p, false));
        }
        t.put("placements", ps);
        List<Object> as = new ArrayList<>();
        for (AssemblyStep a : m.assemblies()) {
            as.add(assemblyTree(a));
        }
        t.put("assemblies", as);
        t.put("bom", new TreeMap<>(m.bom()));
        List<Object> phases = new ArrayList<>();
        for (PhaseRange r : m.phases()) {
            phases.add(Map.of("phase", r.phase().name(), "from", r.fromIndex(), "to", r.toIndexExclusive()));
        }
        t.put("phases", phases);
        t.put("hash", m.hash());
        return t;
    }
}
```
(`PlanJson.boxTree`・`posTree`は、Task 5で公開にしてある。)

`BomCalculator.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts the items needed for a manifest (item id to count). Mostly one item per block; the exceptions are
 * listed here. Blocks with no item of their own are counted under the part that creates them (design 04, F-7).
 */
public final class BomCalculator {
    private static final Map<String, String> ITEM_OF_BLOCK = Map.of("create:belt", "create:belt_connector");

    private BomCalculator() {
    }

    public static Map<String, Integer> bom(List<Placement> placements) {
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Placement p : placements) {
            BlockSpec b = p.block();
            String id = b.blockId();
            int count = 1;
            if (id.equals("minecraft:air")) {
                continue;
            }
            if ("upper".equals(b.get("half")) && id.endsWith("_door")) {
                continue; // a door is one item for both halves
            }
            if ("double".equals(b.get("type")) && id.endsWith("_slab")) {
                count = 2;
            }
            String item = ITEM_OF_BLOCK.getOrDefault(id, id);
            if (item.endsWith("_wall_sign")) {
                item = item.substring(0, item.length() - "_wall_sign".length()) + "_sign";
            }
            out.merge(item, count, Integer::sum);
        }
        return Collections.unmodifiableSortedMap(out);
    }
}
```

`PlaceableBlockPolicy.java`・`BuiltinAllowList.java`:
```java
// PlaceableBlockPolicy.java
package io.github.khayashi4337.micradrone.build.compile;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which blocks a plan may place (D-22). The materials of the palette must be on the allow list; some blocks
 * are refused whatever the list says. The runtime replaces the built-in list with the datapack tag
 * micradrone:palette_allowed (P4); this class is the pure part of that check.
 */
public final class PlaceableBlockPolicy {
    public static final Set<String> ALWAYS_FORBIDDEN = Collections.unmodifiableSet(new TreeSet<>(Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:barrier",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:light",
            "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:end_gateway", "minecraft:nether_portal",
            "minecraft:reinforced_deepslate")));

    private final Set<String> paletteAllowed;

    public PlaceableBlockPolicy(Set<String> paletteAllowed) {
        this.paletteAllowed = Collections.unmodifiableSet(new TreeSet<>(paletteAllowed));
    }

    public static PlaceableBlockPolicy builtin() {
        return new PlaceableBlockPolicy(BuiltinAllowList.ids());
    }

    public boolean isAlwaysForbidden(String blockId) {
        return ALWAYS_FORBIDDEN.contains(blockId);
    }

    public Set<String> allowed() {
        return paletteAllowed;
    }

    /** Empty when the block may be used as a material; otherwise why not (in Japanese, for the user). */
    public Optional<String> checkMaterial(String blockId) {
        if (isAlwaysForbidden(blockId)) {
            return Optional.of(blockId + "は、置いてはいけないブロックです");
        }
        if (!paletteAllowed.contains(blockId)) {
            return Optional.of(blockId + "は、素材として許可されていません(許可リストにありません)");
        }
        return Optional.empty();
    }
}

// BuiltinAllowList.java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The fallback allow list of building materials, built from the default palette, the families and common vanilla groups. */
final class BuiltinAllowList {
    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "bamboo", "crimson", "warped");
    private static final List<String> LOG_WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");
    private static final List<String> FLOWERS = List.of("poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "fern", "azalea", "flowering_azalea");

    private BuiltinAllowList() {
    }

    static Set<String> ids() {
        Set<String> out = new TreeSet<>(BuildingParts.DEFAULT_PALETTE.values());
        for (String full : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(full).orElseThrow();
            out.add(f.full());
            if (f.stairs() != null) {
                out.add(f.stairs());
            }
            out.add(f.slab());
        }
        for (String color : COLORS) {
            for (String kind : List.of("concrete", "terracotta", "wool", "stained_glass", "stained_glass_pane", "glazed_terracotta")) {
                out.add("minecraft:" + color + "_" + kind);
            }
        }
        for (String wood : WOODS) {
            for (String kind : List.of("planks", "fence", "fence_gate", "door", "trapdoor", "wall_sign", "stairs", "slab")) {
                out.add("minecraft:" + wood + "_" + kind);
            }
        }
        for (String wood : LOG_WOODS) {
            out.addAll(List.of("minecraft:" + wood + "_log", "minecraft:" + wood + "_wood",
                    "minecraft:stripped_" + wood + "_log", "minecraft:stripped_" + wood + "_wood"));
        }
        out.addAll(List.of("minecraft:bamboo_block", "minecraft:stripped_bamboo_block", "minecraft:crimson_stem",
                "minecraft:crimson_hyphae", "minecraft:warped_stem", "minecraft:warped_hyphae"));
        for (String flower : FLOWERS) {
            out.add("minecraft:" + flower);
        }
        out.addAll(List.of("minecraft:glass", "minecraft:glass_pane", "minecraft:iron_bars", "minecraft:iron_block",
                "minecraft:iron_door", "minecraft:iron_trapdoor", "minecraft:terracotta", "minecraft:hay_block",
                "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:gravel", "minecraft:sand", "minecraft:dirt_path",
                "minecraft:barrel", "minecraft:lantern", "minecraft:torch", "minecraft:ladder", "minecraft:smooth_stone",
                "minecraft:bricks", "minecraft:cobblestone", "minecraft:stone", "minecraft:sandstone"));
        return out;
    }
}
```

`gen`パッケージ。`GenAbort.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Issue;

/** Stops the generation of one part (or, when fatal, the whole compile) with the Issue that explains why. */
public final class GenAbort extends RuntimeException {
    private final transient Issue issue;
    private final boolean fatal;

    public GenAbort(Issue issue, boolean fatal) {
        super(issue.message(), null, false, false);
        this.issue = issue;
        this.fatal = fatal;
    }

    public Issue issue() {
        return issue;
    }

    public boolean fatal() {
        return fatal;
    }
}
```

`Canvas.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The cells generated so far, keyed by local position; a position has at most one owner. */
public final class Canvas {
    public record Cell(LocalPos pos, BlockSpec block, VerifyMode verify, BuildPhase phase, Map<String, String> blockEntity,
                       String ownerId, String mergeGroup) {
    }

    private record Overlap(String a, String b, int count, LocalPos first, String reason) {
    }

    private final Map<LocalPos, Cell> cells = new HashMap<>();
    private final TreeMap<String, Overlap> overlaps = new TreeMap<>();
    private final int maxCells;

    public Canvas(int maxCells) {
        this.maxCells = maxCells;
    }

    public Cell get(LocalPos pos) {
        return cells.get(pos);
    }

    public Cell remove(LocalPos pos) {
        return cells.remove(pos);
    }

    public int size() {
        return cells.size();
    }

    public Collection<Cell> all() {
        return cells.values();
    }

    /** Places a cell. A cell already there means an overlap, unless both belong to the same merge group with the same block. */
    public boolean put(Cell cell) {
        Cell existing = cells.get(cell.pos());
        if (existing != null) {
            boolean merge = existing.mergeGroup() != null && existing.mergeGroup().equals(cell.mergeGroup())
                    && existing.block().equals(cell.block());
            if (!merge) {
                recordOverlap(existing.ownerId(), cell.ownerId(), cell.pos());
            }
            return false;
        }
        if (cells.size() >= maxCells) {
            throw new GenAbort(Issue.of(IssueCode.E_OUT_OF_BOUNDS, "cells", List.of(),
                    "施工の大きさの上限(" + maxCells + "マス)を超えました", Map.of("cells", String.valueOf(maxCells)), List.of()), true);
        }
        cells.put(cell.pos(), cell);
        return true;
    }

    public void recordOverlap(String ownerA, String ownerB, LocalPos pos) {
        recordOverlap(ownerA, ownerB, pos, "");
    }

    /** {@code reason} says why the two claim the same cell when it is not plain overlap (e.g. "clearance"). */
    public void recordOverlap(String ownerA, String ownerB, LocalPos pos, String reason) {
        String a = ownerA.compareTo(ownerB) <= 0 ? ownerA : ownerB;
        String b = ownerA.compareTo(ownerB) <= 0 ? ownerB : ownerA;
        String key = a + "|" + b;
        Overlap prev = overlaps.get(key);
        overlaps.put(key, prev == null ? new Overlap(a, b, 1, pos, reason) : new Overlap(a, b, prev.count() + 1, prev.first(), prev.reason()));
    }

    private static Map<String, String> overlapData(Overlap o) {
        Map<String, String> data = new TreeMap<>();
        data.put("count", String.valueOf(o.count()));
        data.put("firstPos", o.first().u() + "," + o.first().v() + "," + o.first().w());
        if (!o.reason().isEmpty()) {
            data.put("reason", o.reason());
        }
        return data;
    }

    public List<Issue> overlapIssues() {
        List<Issue> out = new ArrayList<>();
        for (Overlap o : overlaps.values()) {
            out.add(Issue.of(IssueCode.E_OVERLAP, "", List.of(o.a(), o.b()),
                    o.a() + "と" + o.b() + "が同じ場所に重なっています(" + o.count() + "マス。最初は " + o.first().u() + "," + o.first().v() + "," + o.first().w() + ")",
                    overlapData(o), List.of()));
        }
        return out;
    }
}
```

`Palette.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves a material (a role name or a block id) to blocks. The plan's style overrides the registry's default
 * palette. Every id handed out is remembered with the parts that asked for it, for the block policy check.
 */
public final class Palette {
    private static final List<String> STAIRS_CANDIDATES = List.of("minecraft:red_nether_bricks", "minecraft:bricks",
            "minecraft:stone_bricks", "minecraft:oak_planks");

    private final Map<String, String> roles = new TreeMap<>();
    private final Map<String, Set<String>> usedBy = new TreeMap<>();

    public Palette(Map<String, String> defaults, Map<String, String> planPalette) {
        roles.putAll(defaults);
        roles.putAll(planPalette);
    }

    public Map<String, Set<String>> usedBy() {
        return usedBy;
    }

    private String note(String id, PlanNode node) {
        usedBy.computeIfAbsent(id, k -> new TreeSet<>()).add(node.id());
        return id;
    }

    private String role(String material) {
        return material.contains(":") ? null : material;
    }

    public String full(String material, PlanNode node) {
        if (material.contains(":")) {
            return note(material, node);
        }
        String id = roles.get(material);
        if (id == null) {
            throw unknownRole(material, node);
        }
        return note(id, node);
    }

    public String stairs(String material, PlanNode node) {
        String role = role(material);
        if (role != null && roles.containsKey(role + "_stairs")) {
            return note(roles.get(role + "_stairs"), node);
        }
        String full = full(material, node);
        MaterialFamilies.Family f = MaterialFamilies.family(full).orElse(null);
        if (f == null || f.stairs() == null) {
            throw noFamily(material, full, "階段", node);
        }
        return note(f.stairs(), node);
    }

    public String slab(String material, PlanNode node) {
        String role = role(material);
        if (role != null && roles.containsKey(role + "_slab")) {
            return note(roles.get(role + "_slab"), node);
        }
        String full = full(material, node);
        MaterialFamilies.Family f = MaterialFamilies.family(full).orElse(null);
        if (f == null) {
            throw noFamily(material, full, "スラブ", node);
        }
        return note(f.slab(), node);
    }

    private GenAbort unknownRole(String role, PlanNode node) {
        return new GenAbort(Issue.of(IssueCode.E_PARAM_RANGE, "material", List.of(node.id()),
                "パレットに役割「" + role + "」がありません(style(\"" + role + "\", \"minecraft:…\")で決めてください)",
                Map.of("role", role, "roles", String.join(",", roles.keySet())),
                List.of(new FixHint("USE_ROLE", Map.of("roles", String.join(",", roles.keySet()))))), false);
    }

    private GenAbort noFamily(String material, String full, String form, PlanNode node) {
        return new GenAbort(Issue.of(IssueCode.E_PARAM_RANGE, "material", List.of(node.id()),
                full + "には" + form + "の形がありません(ゲーム標準には無い素材です)。" + form + "の素材を、"
                        + (role(material) == null ? "" : role(material) + "_stairs / " + role(material) + "_slab か、")
                        + "族のある素材(例: minecraft:red_nether_bricks)で指定してください",
                Map.of("material", full), List.of(new FixHint("USE_MATERIAL", Map.of("ids", String.join(",", STAIRS_CANDIDATES))))), false);
    }
}
```

`BlockForms.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.List;

/**
 * Block states for the shapes generators need. Only the states that matter are listed (the rest are left to the game:
 * stair corner shapes, fence connections, waterlogging), and verification compares only what is listed.
 */
public final class BlockForms {
    private static final List<String> AXIS_SUFFIXES = List.of("_log", "_wood", "_stem", "_hyphae", "_block_axis");
    private static final List<String> AXIS_IDS = List.of("minecraft:bamboo_block", "minecraft:stripped_bamboo_block",
            "minecraft:hay_block", "minecraft:bone_block", "minecraft:basalt", "minecraft:polished_basalt");

    private BlockForms() {
    }

    public static BlockSpec plain(String id) {
        return BlockSpec.of(id);
    }

    /** {@code back} is the side of the tall back of the stair (the vanilla "facing"). */
    public static BlockSpec stairs(String id, Facing back, boolean top) {
        return BlockSpec.of(id, "facing", back.lower(), "half", top ? "top" : "bottom");
    }

    public static BlockSpec slab(String id, boolean top) {
        return BlockSpec.of(id, "type", top ? "top" : "bottom");
    }

    public static BlockSpec door(String id, Facing facing, boolean upper, boolean hingeRight) {
        return BlockSpec.of(id, "facing", facing.lower(), "half", upper ? "upper" : "lower", "hinge", hingeRight ? "right" : "left");
    }

    public static BlockSpec gate(String id, Facing facing) {
        return BlockSpec.of(id, "facing", facing.lower());
    }

    public static BlockSpec ladder(Facing facing) {
        return BlockSpec.of("minecraft:ladder", "facing", facing.lower());
    }

    public static BlockSpec wallSign(String id, Facing facing) {
        return BlockSpec.of(id, "facing", facing.lower());
    }

    public static BlockSpec lantern(boolean hanging) {
        return BlockSpec.of("minecraft:lantern", "hanging", String.valueOf(hanging));
    }

    public static boolean isAxisBlock(String id) {
        if (AXIS_IDS.contains(id)) {
            return true;
        }
        for (String suffix : AXIS_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code axis} is x (along u), y (up) or z (along w) in the local frame. */
    public static BlockSpec axisBlock(String id, String axis) {
        return BlockSpec.of(id, "axis", axis);
    }

    /** A floor tile: a trapdoor lies closed on the bottom half, a slab is a bottom slab, anything else is a plain block. */
    public static BlockSpec flat(String id) {
        if (id.endsWith("_trapdoor")) {
            return BlockSpec.of(id, "half", "bottom");
        }
        if (id.endsWith("_slab")) {
            return slab(id, false);
        }
        return plain(id);
    }

    /** Blocks whose state depends on their neighbours are checked by id only; stateless blocks exactly. */
    public static VerifyMode verifyFor(BlockSpec spec) {
        String id = spec.blockId();
        if (id.endsWith("_pane") || id.endsWith("_fence") || id.equals("minecraft:iron_bars")) {
            return VerifyMode.BLOCK_ONLY;
        }
        return spec.properties().isEmpty() ? VerifyMode.EXACT : VerifyMode.STATE_SUBSET;
    }
}
```

`StructureInfo.java`・`WallInfo.java`:
```java
// StructureInfo.java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.LocalPos;

/** A building's frame: its origin and box (u in [0,width-1], w in [0,depth-1]) and its floors. */
public record StructureInfo(String id, LocalPos origin, int width, int depth, int floors, int floorHeight) {
    public int totalHeight() {
        return floors * floorHeight;
    }
}

// WallInfo.java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;

/**
 * Geometry of one wall. {@code side} is the direction the wall faces outward: NORTH is the wall at w=depth-1,
 * SOUTH at w=0, EAST at u=width-1, WEST at u=0. Cells are addressed by (i along the wall, layer from the outside,
 * row from the bottom).
 */
public record WallInfo(String id, StructureInfo structure, Facing side, int level, int baseV, int height, int thickness,
                       int from, int length) {
    public Facing outward() {
        return side;
    }

    /** The direction in which the face coordinate u grows: east for north/south walls, north for east/west walls. */
    public Facing along() {
        return side == Facing.NORTH || side == Facing.SOUTH ? Facing.EAST : Facing.NORTH;
    }

    public int sideLength() {
        return side == Facing.NORTH || side == Facing.SOUTH ? structure.width() : structure.depth();
    }

    /** {@code layer} 0 is the outermost layer, thickness-1 the innermost; -1 is the first cell outside the wall. */
    public LocalPos cell(int i, int layer, int row) {
        LocalPos o = structure.origin();
        int v = baseV + row;
        return switch (side) {
            case NORTH -> new LocalPos(o.u() + from + i, v, o.w() + structure.depth() - 1 - layer);
            case SOUTH -> new LocalPos(o.u() + from + i, v, o.w() + layer);
            case EAST -> new LocalPos(o.u() + structure.width() - 1 - layer, v, o.w() + from + i);
            case WEST -> new LocalPos(o.u() + layer, v, o.w() + from + i);
        };
    }
}
```

`PartGenerator.java`・`PartGenerators.java`:
```java
// PartGenerator.java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** Generates the cells of one building part. Deterministic: the same node and parameters give the same cells. */
public interface PartGenerator {
    void generate(GenContext ctx, PlanNode node, Params p);

    /** Runs after every part has been generated (for checks that need the whole canvas). */
    default void afterAll(GenContext ctx, PlanNode node, Params p) {
    }
}

// PartGenerators.java
package io.github.khayashi4337.micradrone.build.compile.gen;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The generator of each building part. A test keeps this table equal to the registry's micra:* parts. */
public final class PartGenerators {
    public enum Stage { BASE, CARVE }

    public record Entry(PartGenerator generator, Stage stage) {
    }

    private static final Map<String, Entry> ENTRIES = Map.ofEntries(
            Map.entry("micra:structure", new Entry(new StructureGen(), Stage.BASE)),
            Map.entry("micra:foundation", new Entry(new FoundationGen(), Stage.BASE)),
            Map.entry("micra:floor", new Entry(new FloorGen(), Stage.BASE)),
            Map.entry("micra:wall", new Entry(new WallGen(), Stage.BASE)));

    private PartGenerators() {
    }

    public static Optional<Entry> find(String partId) {
        return Optional.ofNullable(ENTRIES.get(partId));
    }

    public static Set<String> ids() {
        return ENTRIES.keySet();
    }
}
```
(以降のタスクで、生成器を追加するたびに`ENTRIES`へ足す。)

`GenContext.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What a generator may use: the palette, the canvas, node geometry, and the ways to report a problem. */
public final class GenContext {
    /** A node with its resolved parameters and its local origin (null for nodes placed on a wall face). */
    public record NodeInfo(PlanNode node, PartType type, Params params, LocalPos origin) {
    }

    private final PartTypeRegistry registry;
    private final Palette palette;
    private final Canvas canvas;
    private final List<Issue> issues;
    private final Map<String, PlanNode> nodes;
    private final Map<String, LocalPos> origins;
    private final Map<String, Optional<WallInfo>> walls = new HashMap<>();
    private final Map<LocalPos, String> carvedBy = new HashMap<>();
    private final Set<String> reportedWalls = new HashSet<>();

    public GenContext(PartTypeRegistry registry, Palette palette, Canvas canvas, List<Issue> issues,
                      Map<String, PlanNode> nodes, Map<String, LocalPos> origins) {
        this.registry = registry;
        this.palette = palette;
        this.canvas = canvas;
        this.issues = issues;
        this.nodes = nodes;
        this.origins = origins;
    }

    public Palette palette() {
        return palette;
    }

    public Canvas canvas() {
        return canvas;
    }

    public PlanNode node(String id) {
        return nodes.get(id);
    }

    public GenAbort fail(PlanNode node, IssueCode code, String key, String message) {
        return new GenAbort(Issue.of(code, key, List.of(node.id()), message, Map.of(), List.of()), false);
    }

    public NodeInfo info(String id) {
        PlanNode n = nodes.get(id);
        PartType type = registry.get(n.type());
        return new NodeInfo(n, type, Params.resolve(type, n.params()), origins.get(id));
    }

    public StructureInfo structureOf(PlanNode node) {
        PlanNode parent = node.parent() == null ? null : nodes.get(node.parent());
        if (parent == null || !parent.type().equals("micra:structure")) {
            throw fail(node, IssueCode.E_ANCHOR, "parent", node.type() + "は、建屋(micra:structure)の中に置いてください(親: "
                    + (node.parent() == null ? "なし" : node.parent()) + ")");
        }
        return structureInfo(parent);
    }

    private StructureInfo structureInfo(PlanNode structureNode) {
        NodeInfo info = info(structureNode.id());
        LocalPos origin = info.origin();
        if (origin == null) {
            throw fail(structureNode, IssueCode.E_ANCHOR, "anchor", "建屋の位置を決められません");
        }
        Params p = info.params();
        return new StructureInfo(structureNode.id(), origin, p.i("width"), p.i("depth"), p.i("floors"), p.i("floor_height"));
    }

    /** The wall's geometry, or empty if the node is not a valid wall (the wall's own problem is reported once). */
    public Optional<WallInfo> wallInfo(String wallId) {
        return walls.computeIfAbsent(wallId, id -> {
            PlanNode wall = nodes.get(id);
            if (wall == null || !wall.type().equals("micra:wall")) {
                return Optional.empty();
            }
            try {
                return Optional.of(buildWallInfo(wall));
            } catch (GenAbort abort) {
                if (reportedWalls.add(id)) {
                    issues.add(abort.issue());
                }
                return Optional.empty();
            }
        });
    }

    private WallInfo buildWallInfo(PlanNode wall) {
        StructureInfo st = structureOf(wall);
        Params p = info(wall.id()).params();
        Facing side = Facing.parse(p.s("side"));
        int level = p.i("level");
        if (level >= st.floors()) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, "level", "階(level=" + level + ")が、建屋の階数(" + st.floors() + ")を超えています");
        }
        int sideLen = side == Facing.NORTH || side == Facing.SOUTH ? st.width() : st.depth();
        int from = p.i("from");
        int length = p.i("length") == 0 ? sideLen - from : p.i("length");
        if (from >= sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, "from", "始点(from=" + from + ")が、壁の側の長さ(" + sideLen + ")以上です");
        }
        if (from + length > sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, "length", "壁の端を越えます(from=" + from + " + length=" + length + " > " + sideLen + ")");
        }
        int height = p.i("height") == 0 ? st.floorHeight() - 1 : p.i("height");
        if (p.s("part").equals("half")) {
            height = (height + 1) / 2;
        }
        int baseV = st.origin().v() + level * st.floorHeight() + 1;
        return new WallInfo(wall.id(), st, side, level, baseV, height, p.i("thickness"), from, length);
    }

    /** The wall a node is attached to by an OnSurface anchor. */
    public WallInfo wallOfAnchor(PlanNode node) {
        if (!(node.anchor() instanceof Anchor.OnSurface s)) {
            throw fail(node, IssueCode.E_ANCHOR, "anchor", node.type() + "は、壁の面(OnSurface)に付けてください");
        }
        return wallInfo(s.nodeId()).orElseThrow(() -> fail(node, IssueCode.E_OPENING_NO_WALL, "anchor",
                "付ける壁(" + s.nodeId() + ")が、施工できる壁ではありません"));
    }

    private Rot rotOf(PlanNode node) {
        return node.anchor() instanceof Anchor.Absolute a ? a.rot() : Rot.NONE;
    }

    public void emit(PlanNode node, int du, int dv, int dw, BlockSpec block) {
        emit(node, du, dv, dw, block, Map.of());
    }

    /** Places a block at an offset from the node's origin; the node's rotation and mirror apply. */
    public void emit(PlanNode node, int du, int dv, int dw, BlockSpec block, Map<String, String> blockEntity) {
        NodeInfo info = info(node.id());
        if (info.origin() == null) {
            throw fail(node, IssueCode.E_ANCHOR, "anchor", "位置を決められません");
        }
        Rot rot = rotOf(node);
        LocalPos off = rot.apply(new LocalPos(du, dv, dw));
        put(node, info.origin().plus(off.u(), off.v(), off.w()), BlockRotation.transform(block, rot), blockEntity, null);
    }

    /** Where an offset from the node's origin ends up after the node's rotation and mirror. */
    public LocalPos placed(PlanNode node, int du, int dv, int dw) {
        NodeInfo info = info(node.id());
        LocalPos off = rotOf(node).apply(new LocalPos(du, dv, dw));
        return info.origin().plus(off.u(), off.v(), off.w());
    }

    public void emitAbs(PlanNode node, LocalPos pos, BlockSpec block) {
        emitAbs(node, pos, block, null, Map.of());
    }

    public void emitAbs(PlanNode node, LocalPos pos, BlockSpec block, String mergeGroup, Map<String, String> blockEntity) {
        put(node, pos, block, blockEntity, mergeGroup);
    }

    private void put(PlanNode node, LocalPos pos, BlockSpec block, Map<String, String> blockEntity, String mergeGroup) {
        PartType type = registry.get(node.type());
        canvas.put(new Canvas.Cell(pos, block, BlockForms.verifyFor(block),
                type.phase(), blockEntity, node.id(), mergeGroup));
    }

    /** Removes the wall cells an opening replaces. Refuses (and changes nothing) if any is not the wall's or already carved. */
    public void carve(PlanNode opener, WallInfo wall, List<LocalPos> cells) {
        int notWall = 0;
        LocalPos firstBad = null;
        boolean overlapped = false;
        for (LocalPos pos : cells) {
            String earlier = carvedBy.get(pos);
            if (earlier != null) {
                canvas.recordOverlap(earlier, opener.id(), pos);
                overlapped = true;
                continue;
            }
            Canvas.Cell c = canvas.get(pos);
            if (c == null || !c.ownerId().equals(wall.id())) {
                notWall++;
                if (firstBad == null) {
                    firstBad = pos;
                }
            }
        }
        if (overlapped) {
            throw new GenAbort(Issue.of(IssueCode.E_OVERLAP, "carve", List.of(opener.id()),
                    opener.id() + "の開口部が、別の開口部と重なっています", Map.of(), List.of()), false);
        }
        if (notWall > 0) {
            throw new GenAbort(Issue.of(IssueCode.E_OPENING_NO_WALL, "", List.of(opener.id()),
                    opener.id() + "の開口部が、壁(" + wall.id() + ")の外にはみ出しています(壁でないマスが" + notWall + "個。最初は "
                            + firstBad.u() + "," + firstBad.v() + "," + firstBad.w() + ")",
                    Map.of("count", String.valueOf(notWall)), List.of()), false);
        }
        for (LocalPos pos : cells) {
            canvas.remove(pos);
            carvedBy.put(pos, opener.id());
        }
    }
}
```

`StructureGen.java`・`FoundationGen.java`・`FloorGen.java`・`WallGen.java`:
```java
// StructureGen.java
final class StructureGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        // A building has no blocks of its own; it only gives its children a frame. Validate that it can be placed.
        if (ctx.info(node.id()).origin() == null) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, "anchor", "建屋の位置を決められません");
        }
    }
}

// FoundationGen.java
final class FoundationGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int margin = p.i("margin");
        int depth = p.i("depth");
        BlockSpec block = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        for (int u = -margin; u <= st.width() - 1 + margin; u++) {
            for (int w = -margin; w <= st.depth() - 1 + margin; w++) {
                for (int v = -depth; v <= -1; v++) {
                    ctx.emitAbs(node, st.origin().plus(u, v, w), block);
                }
            }
        }
    }
}

// FloorGen.java
final class FloorGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int level = p.i("level");
        if (level >= st.floors()) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "level", "階(level=" + level + ")が、建屋の階数(" + st.floors() + ")を超えています");
        }
        List<Integer> holes = p.ints("holes");
        if (holes.size() % 4 != 0) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "holes", "holesは[u0,w0,u1,w1,…]の形で、4つずつの組にしてください(" + holes.size() + "個あります)");
        }
        String id = p.s("kind").equals("slab") ? ctx.palette().slab(p.s("material"), node) : ctx.palette().full(p.s("material"), node);
        BlockSpec block = p.s("kind").equals("slab") ? BlockForms.slab(id, false) : BlockForms.plain(id);
        int v = st.origin().v() + level * st.floorHeight();
        for (int u = 0; u < st.width(); u++) {
            for (int w = 0; w < st.depth(); w++) {
                if (!inHole(holes, u, w)) {
                    ctx.emitAbs(node, new LocalPos(st.origin().u() + u, v, st.origin().w() + w), block);
                }
            }
        }
    }

    private static boolean inHole(List<Integer> holes, int u, int w) {
        for (int k = 0; k < holes.size(); k += 4) {
            int u0 = Math.min(holes.get(k), holes.get(k + 2));
            int u1 = Math.max(holes.get(k), holes.get(k + 2));
            int w0 = Math.min(holes.get(k + 1), holes.get(k + 3));
            int w1 = Math.max(holes.get(k + 1), holes.get(k + 3));
            if (u >= u0 && u <= u1 && w >= w0 && w <= w1) {
                return true;
            }
        }
        return false;
    }
}

// WallGen.java
final class WallGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallInfo(node.id()).orElse(null);
        if (wall == null) {
            return; // the reason was reported when the wall's geometry was built
        }
        BlockSpec block = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        String mergeGroup = "wall:" + node.parent();
        for (int i = 0; i < wall.length(); i++) {
            for (int layer = 0; layer < wall.thickness(); layer++) {
                for (int row = 0; row < wall.height(); row++) {
                    ctx.emitAbs(node, wall.cell(i, layer, row), block, mergeGroup, Map.of());
                }
            }
        }
    }
}
```
(各ファイルに`package`・`import`を付ける。)

`PlanCompiler.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.compile.gen.Canvas;
import io.github.khayashi4337.micradrone.build.compile.gen.GenAbort;
import io.github.khayashi4337.micradrone.build.compile.gen.GenContext;
import io.github.khayashi4337.micradrone.build.compile.gen.Palette;
import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandedPlan;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns an expanded plan into a placement manifest, deterministically. The plan is built in the local frame and
 * only mapped to the world at the end, so rotating the site rotates the result and nothing else (D-29).
 */
public final class PlanCompiler {
    public static final int DEFAULT_MAX_CELLS = 200_000;

    private final int maxCells;

    public PlanCompiler() {
        this(DEFAULT_MAX_CELLS);
    }

    public PlanCompiler(int maxCells) {
        this.maxCells = maxCells;
    }

    public CompileResult compile(ExpandedPlan expanded, PartTypeRegistry registry, PlaceableBlockPolicy policy, SurveyRef survey) {
        SemanticPlan plan = expanded.source();
        List<Issue> issues = new ArrayList<>();
        Site site = plan.site();
        if (site == null) {
            issues.add(Issue.of(IssueCode.E_SITE_MISSING, List.of(), "敷地(site)が決まっていません。site(...)で、場所と向きと範囲を決めてください"));
            return new CompileResult(null, issues);
        }
        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode n : expanded.primitiveNodes()) {
            byId.put(n.id(), n);
        }
        Map<String, LocalPos> origins = Origins.resolve(expanded.primitiveNodes(), SlotResolver.NONE, issues);
        Palette palette = new Palette(registry.defaultPalette(), plan.style().palette());
        Canvas canvas = new Canvas(maxCells);
        GenContext ctx = new GenContext(registry, palette, canvas, issues, byId, origins);

        List<PlanNode> ordered = order(expanded.primitiveNodes(), byId);
        try {
            for (PartGenerators.Stage stage : PartGenerators.Stage.values()) {
                for (PlanNode node : ordered) {
                    runNode(ctx, registry, node, stage, issues, false);
                }
            }
            for (PlanNode node : ordered) {
                runNode(ctx, registry, node, null, issues, true);
            }
        } catch (GenAbort fatal) {
            issues.add(fatal.issue());
            return new CompileResult(null, issues);
        }
        issues.addAll(canvas.overlapIssues());
        checkBounds(canvas, site, issues);
        checkBlocks(canvas, palette, policy, issues);
        if (issues.stream().anyMatch(Issue::isError)) {
            return new CompileResult(null, issues);
        }
        return new CompileResult(build(plan, site, registry, canvas, byId), issues);
    }

    /** Parents before children; the same depth by id. Deterministic whatever order the nodes arrived in. */
    private static List<PlanNode> order(List<PlanNode> nodes, Map<String, PlanNode> byId) {
        Map<String, Integer> depth = new HashMap<>();
        for (PlanNode n : nodes) {
            int d = 0;
            PlanNode cur = n;
            while (cur.parent() != null && d <= nodes.size()) {
                cur = byId.get(cur.parent());
                d++;
                if (cur == null) {
                    break;
                }
            }
            depth.put(n.id(), d);
        }
        List<PlanNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt((PlanNode n) -> depth.get(n.id())).thenComparing(PlanNode::id));
        return sorted;
    }

    private void runNode(GenContext ctx, PartTypeRegistry registry, PlanNode node, PartGenerators.Stage stage, List<Issue> issues,
                         boolean afterAll) {
        PartGenerators.Entry entry = PartGenerators.find(node.type()).orElse(null);
        if (entry == null) {
            if (stage == PartGenerators.Stage.BASE && !afterAll) {
                issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, List.of(node.id()),
                        node.type() + "は、この版では施工できません(置き方が登録されていません)"));
            }
            return;
        }
        if (!afterAll && entry.stage() != stage) {
            return;
        }
        PartType type = registry.get(node.type());
        try {
            if (!afterAll && node.anchor() instanceof Anchor.Absolute a && !a.rot().equals(Rot.NONE)
                    && BuildingParts.ROTATION_UNSUPPORTED.contains(node.type())) {
                throw ctx.fail(node, IssueCode.E_ANCHOR, "rot", node.type() + "には回転・鏡像を付けられません(向きは敷地のfacingで決めます)");
            }
            Params params = Params.resolve(type, node.params());
            if (afterAll) {
                entry.generator().afterAll(ctx, node, params);
            } else {
                entry.generator().generate(ctx, node, params);
            }
        } catch (GenAbort abort) {
            if (abort.fatal()) {
                throw abort;
            }
            issues.add(abort.issue());
        }
    }

    private static void checkBounds(Canvas canvas, Site site, List<Issue> issues) {
        Box b = site.localBounds();
        Map<String, int[]> outside = new TreeMap<>();
        Map<String, LocalPos> first = new TreeMap<>();
        for (Canvas.Cell c : canvas.all()) {
            LocalPos p = c.pos();
            if (!b.contains(p.u(), p.v(), p.w())) {
                outside.computeIfAbsent(c.ownerId(), k -> new int[1])[0]++;
                first.merge(c.ownerId(), p, (x, y) -> compare(x, y) <= 0 ? x : y);
            }
        }
        for (Map.Entry<String, int[]> e : outside.entrySet()) {
            LocalPos f = first.get(e.getKey());
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, "", List.of(e.getKey()),
                    e.getKey() + "が、敷地の範囲の外に" + e.getValue()[0] + "マスはみ出しています(最初は " + f.u() + "," + f.v() + "," + f.w() + ")",
                    Map.of("count", String.valueOf(e.getValue()[0]), "firstPos", f.u() + "," + f.v() + "," + f.w()), List.of()));
        }
    }

    private static int compare(LocalPos a, LocalPos b) {
        return Comparator.comparingInt(LocalPos::v).thenComparingInt(LocalPos::w).thenComparingInt(LocalPos::u).compare(a, b);
    }

    private static void checkBlocks(Canvas canvas, Palette palette, PlaceableBlockPolicy policy, List<Issue> issues) {
        Map<String, java.util.Set<String>> refused = new TreeMap<>();
        for (Map.Entry<String, java.util.Set<String>> e : palette.usedBy().entrySet()) {
            if (policy.checkMaterial(e.getKey()).isPresent()) {
                refused.computeIfAbsent(e.getKey(), k -> new java.util.TreeSet<>()).addAll(e.getValue());
            }
        }
        for (Canvas.Cell c : canvas.all()) {
            if (policy.isAlwaysForbidden(c.block().blockId())) {
                refused.computeIfAbsent(c.block().blockId(), k -> new java.util.TreeSet<>()).add(c.ownerId());
            }
        }
        for (Map.Entry<String, java.util.Set<String>> e : refused.entrySet()) {
            String reason = policy.checkMaterial(e.getKey()).orElse(e.getKey() + "は置けません");
            issues.add(Issue.of(IssueCode.E_BLOCK_FORBIDDEN, e.getKey(), new ArrayList<>(e.getValue()), reason,
                    Map.of("block", e.getKey()), List.of()));
        }
    }

    private PlacementManifest build(SemanticPlan plan, Site site, PartTypeRegistry registry, Canvas canvas, Map<String, PlanNode> byId) {
        List<Canvas.Cell> cells = new ArrayList<>(canvas.all());
        cells.sort(Comparator.<Canvas.Cell>comparingInt(c -> c.phase().ordinal())
                .thenComparingInt(c -> c.pos().v()).thenComparingInt(c -> c.pos().w()).thenComparingInt(c -> c.pos().u()));
        BuildFrame frame = site.frame();
        int turns = frame.facing().quarterTurns();
        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Canvas.Cell c = cells.get(i);
            PartType owner = registry.get(byId.get(c.ownerId()).type());
            placements.add(new Placement(i, frame.toWorld(c.pos()), BlockRotation.rotate(c.block(), turns), c.blockEntity(),
                    c.ownerId(), c.phase(), owner.placer(), c.verify(), ReplacePolicy.REPLACEABLE, null));
        }
        List<PhaseRange> phases = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= placements.size(); i++) {
            if (i == placements.size() || placements.get(i).phase() != placements.get(start).phase()) {
                phases.add(new PhaseRange(placements.get(start).phase(), start, i));
                start = i;
            }
        }
        if (placements.isEmpty()) {
            phases.clear();
        }
        Map<String, Integer> bom = BomCalculator.bom(placements);
        Box worldBounds = worldBounds(frame, site.localBounds());
        String hash = ManifestJson.computeHash(site.dimension(), registry.version(), worldBounds, placements, List.of(), bom);
        return new PlacementManifest(PlacementManifest.MANIFEST_VERSION, plan.planId(), plan.revision(), registry.version(),
                site.dimension(), frame, worldBounds, placements, List.of(), bom, phases, hash);
    }

    private static Box worldBounds(BuildFrame frame, Box local) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int u : new int[]{local.minA(), local.maxA()}) {
            for (int v : new int[]{local.minB(), local.maxB()}) {
                for (int w : new int[]{local.minC(), local.maxC()}) {
                    IntPos p = frame.toWorld(new LocalPos(u, v, w));
                    minX = Math.min(minX, p.x());
                    minY = Math.min(minY, p.y());
                    minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x());
                    maxY = Math.max(maxY, p.y());
                    maxZ = Math.max(maxZ, p.z());
                }
            }
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
```
(`BuildPhase`のimportは使わなければ消す。`PlanCompiler.compile`の`survey`は記録するだけで使わない: javadocに「P4が使う」と書いた。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。導出した数(73個、48個、98個、21個、12個…)がすべて一致する。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: PlanCompiler(施工リストの生成・ハッシュ・重なり/敷地/許可ブロックの検査)と、structure・foundation・floor・wallの生成器を追加(自然言語→工場建設 P3 Task 10)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: 単独で立つ部品の生成器(`pillar`・`beam`・`chimney`・`road`・`dock_pad`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/{PillarGen,BeamGen,ChimneyGen,RoadGen,DockPadGen}.java`
- Modify: `PartGenerators.java`(5件を`ENTRIES`に足す)、`GenContext.java`(4方向の補助`Facing dir(Params, String)`は不要。下の`Dirs`を使う)
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/Dirs.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/gen/FreestandingPartsTest.java`

**Interfaces:**
- Consumes: Task 10の`GenContext`・`BlockForms`・`PartGenerator`
- Produces: `Dirs.of(String name) → Facing`(`north`/`east`/`south`/`west`)、`Dirs.right(Facing) → Facing`(時計回りに1つ)

- [ ] **Step 1: 失敗するテストを書く**

`FreestandingPartsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FreestandingPartsTest {
    private static final StyleSpec STYLE = new StyleSpec(Map.of("pillar", "minecraft:quartz_block", "trim", "minecraft:smooth_stone"), Set.of());

    private static Map<LocalPos, BlockSpec> build(io.github.khayashi4337.micradrone.build.model.PlanNode... nodes) {
        CompileResult r = compile(STYLE, List.of(nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    @Test
    void pillarHasABaseAndACapitalOfTrim() {
        Map<LocalPos, BlockSpec> c = build(node("p", "micra:pillar", null, 0, 0, 0, Map.of()));
        assertEquals(4, c.size());
        assertEquals(BlockSpec.of("minecraft:smooth_stone"), c.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of("minecraft:quartz_block"), c.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of("minecraft:quartz_block"), c.get(new LocalPos(0, 2, 0)));
        assertEquals(BlockSpec.of("minecraft:smooth_stone"), c.get(new LocalPos(0, 3, 0)));
    }

    @Test
    void pillarWithoutBaseOrCapitalAndTheShortCases() {
        Map<LocalPos, BlockSpec> plain = build(node("p", "micra:pillar", null, 0, 0, 0, params("base", false, "capital", false)));
        assertEquals(4, countOf(plain, "minecraft:quartz_block"));
        Map<LocalPos, BlockSpec> one = build(node("p", "micra:pillar", null, 0, 0, 0, params("height", 1)));
        assertEquals(BlockSpec.of("minecraft:quartz_block"), one.get(new LocalPos(0, 0, 0)), "a one-block pillar is only shaft");
        Map<LocalPos, BlockSpec> two = build(node("p", "micra:pillar", null, 0, 0, 0, params("height", 2)));
        assertEquals(2, countOf(two, "minecraft:smooth_stone"));
    }

    @Test
    void beamGetsItsAxisFromTheDirection() {
        Map<LocalPos, BlockSpec> c = build(node("b", "micra:beam", null, 2, 3, 4, params("axis", "w", "length", 3)));
        assertEquals(3, c.size());
        assertEquals(BlockSpec.of("minecraft:oak_log", "axis", "z"), c.get(new LocalPos(2, 3, 4)));
        assertEquals(BlockSpec.of("minecraft:oak_log", "axis", "z"), c.get(new LocalPos(2, 3, 6)));
        Map<LocalPos, BlockSpec> up = build(node("b", "micra:beam", null, 0, 0, 0, params("axis", "v", "length", 2)));
        assertEquals("y", up.get(new LocalPos(0, 1, 0)).get("axis"));
        Map<LocalPos, BlockSpec> plainBeam = build(node("b", "micra:beam", null, 0, 0, 0, params("length", 2, "material", "minecraft:stone")));
        assertEquals(BlockSpec.of("minecraft:stone"), plainBeam.get(new LocalPos(1, 0, 0)));
    }

    @Test
    void chimneyShaftAndCap() {
        Map<LocalPos, BlockSpec> c = build(node("c", "micra:chimney", null, 0, 0, 0, params("height", 3)));
        assertEquals(3, countOf(c, "minecraft:bricks"));
        assertEquals(9, countOf(c, "minecraft:brick_slab"));
        assertEquals(BlockSpec.of("minecraft:brick_slab", "type", "bottom"), c.get(new LocalPos(-1, 3, -1)));
        assertEquals(BlockSpec.of("minecraft:brick_slab", "type", "bottom"), c.get(new LocalPos(1, 3, 1)));
        assertEquals(12, c.size());
        Map<LocalPos, BlockSpec> ring = build(node("c", "micra:chimney", null, 0, 0, 0, params("height", 2, "size", 3, "cap", false)));
        assertEquals(16, ring.size(), "a size-3 chimney is a hollow ring");
        assertNull(ring.get(new LocalPos(1, 0, 1)));
    }

    @Test
    void roadFollowsTheDirectionAndGrowsToTheRight() {
        Map<LocalPos, BlockSpec> c = build(node("r", "micra:road", null, 0, 0, 0, params("length", 3, "dir", "east")));
        assertEquals(6, c.size());
        assertEquals(BlockSpec.of("minecraft:gravel"), c.get(new LocalPos(2, 0, 0)));
        assertEquals(BlockSpec.of("minecraft:gravel"), c.get(new LocalPos(2, 0, -1)), "right of east is south (-w)");
    }

    @Test
    void dockPadHasAMarkerRingAndACargoBarrel() {
        Map<LocalPos, BlockSpec> c = build(node("d", "micra:dock_pad", null, 0, 0, 0,
                params("width", 5, "depth", 5, "clearance", 4)));
        assertEquals(26, c.size());
        assertEquals(16, countOf(c, "minecraft:yellow_concrete"));
        assertEquals(9, countOf(c, "minecraft:smooth_stone"));
        assertEquals(BlockSpec.of("minecraft:barrel", "facing", "up"), c.get(new LocalPos(1, 1, 1)));
        Map<LocalPos, BlockSpec> plainPad = build(node("d", "micra:dock_pad", null, 0, 0, 0,
                params("width", 5, "depth", 5, "marker", false)));
        assertEquals(25, countOf(plainPad, "minecraft:smooth_stone"));
    }

    @Test
    void dockPadKeepsItsAirSpaceClear() {
        CompileResult r = compile(STYLE, List.of(node("d", "micra:dock_pad", null, 0, 0, 0, params("width", 5, "depth", 5, "clearance", 4)),
                node("l", "micra:lamp", null, 3, 2, 3, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-OVERLAP"), codes(r));
        assertEquals(List.of("d", "l"), r.issues().get(0).subjects());
        assertEquals("clearance", r.issues().get(0).data().get("reason"));
        // above the clearance it is fine
        assertTrue(compile(STYLE, List.of(node("d", "micra:dock_pad", null, 0, 0, 0, params("width", 5, "depth", 5, "clearance", 4)),
                node("l", "micra:lamp", null, 3, 5, 3, Map.of()))).issues().isEmpty());
    }

    @Test
    void dockPadCargoMustLieOnThePad() {
        CompileResult r = compile(STYLE, List.of(node("d", "micra:dock_pad", null, 0, 0, 0, params("width", 5, "depth", 5, "cargo_u", 9))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(r));
    }

    @Test
    void aRotatedFreestandingPartTurnsItsCellsAndItsBlockStates() {
        io.github.khayashi4337.micradrone.build.model.PlanNode turned = io.github.khayashi4337.micradrone.build.compile.CompileFixtures
                .ruled("b", "micra:beam", null, new io.github.khayashi4337.micradrone.build.model.Rot(1, false), 0, 0, 0,
                        params("axis", "u", "length", 3));
        Map<LocalPos, BlockSpec> c = build(turned);
        // one clockwise turn: +u becomes -w, and the log's axis x becomes z
        assertEquals(BlockSpec.of("minecraft:oak_log", "axis", "z"), c.get(new LocalPos(0, 0, -2)));
        assertEquals(3, c.size());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.gen.FreestandingPartsTest" --console=plain`
Expected: FAIL(`E-UNKNOWN-PART`で、生成器がまだ無い)。

- [ ] **Step 3: 実装する**

`Dirs.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;

/** The four-direction parameters (north/east/south/west) as {@link Facing}, and the "right hand" of a direction. */
final class Dirs {
    private Dirs() {
    }

    static Facing of(String name) {
        return Facing.parse(name);
    }

    /** The next direction clockwise seen from above: the right-hand side of a walker heading in {@code heading}. */
    static Facing right(Facing heading) {
        return heading.rotate(1);
    }
}
```

`PillarGen.java`・`BeamGen.java`・`ChimneyGen.java`・`RoadGen.java`・`DockPadGen.java`(すべて`package io.github.khayashi4337.micradrone.build.compile.gen;`):
```java
// PillarGen.java
final class PillarGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int height = p.i("height");
        BlockSpec shaft = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        BlockSpec trim = BlockForms.plain(ctx.palette().full("trim", node));
        for (int dv = 0; dv < height; dv++) {
            boolean base = height > 1 && dv == 0 && p.b("base");
            boolean capital = height > 1 && dv == height - 1 && p.b("capital");
            ctx.emit(node, 0, dv, 0, base || capital ? trim : shaft);
        }
    }
}

// BeamGen.java
final class BeamGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String axis = p.s("axis");
        String id = ctx.palette().full(p.s("material"), node);
        String stateAxis = axis.equals("u") ? "x" : axis.equals("v") ? "y" : "z";
        BlockSpec block = BlockForms.isAxisBlock(id) ? BlockForms.axisBlock(id, stateAxis) : BlockForms.plain(id);
        for (int i = 0; i < p.i("length"); i++) {
            ctx.emit(node, axis.equals("u") ? i : 0, axis.equals("v") ? i : 0, axis.equals("w") ? i : 0, block);
        }
    }
}

// ChimneyGen.java
final class ChimneyGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int height = p.i("height");
        int size = p.i("size");
        BlockSpec shaft = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        for (int dv = 0; dv < height; dv++) {
            for (int u = 0; u < size; u++) {
                for (int w = 0; w < size; w++) {
                    boolean hollow = size == 3 && u == 1 && w == 1;
                    if (!hollow) {
                        ctx.emit(node, u, dv, w, shaft);
                    }
                }
            }
        }
        if (p.b("cap")) {
            BlockSpec cap = BlockForms.slab(ctx.palette().slab(p.s("material"), node), false);
            for (int u = -1; u <= size; u++) {
                for (int w = -1; w <= size; w++) {
                    ctx.emit(node, u, height, w, cap);
                }
            }
        }
    }
}

// RoadGen.java
final class RoadGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s("dir"));
        Facing right = Dirs.right(dir);
        BlockSpec block = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        for (int i = 0; i < p.i("length"); i++) {
            for (int j = 0; j < p.i("width"); j++) {
                ctx.emit(node, i * dir.du() + j * right.du(), 0, i * dir.dw() + j * right.dw(), block);
            }
        }
    }
}

// DockPadGen.java
final class DockPadGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int width = p.i("width");
        int depth = p.i("depth");
        int cargoU = p.i("cargo_u");
        int cargoW = p.i("cargo_w");
        if (cargoU >= width) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "cargo_u", "荷役口(cargo_u=" + cargoU + ")が、台の幅(" + width + ")の外です");
        }
        if (cargoW >= depth) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "cargo_w", "荷役口(cargo_w=" + cargoW + ")が、台の奥行(" + depth + ")の外です");
        }
        BlockSpec pad = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        BlockSpec marker = BlockForms.plain(ctx.palette().full("marker", node));
        BlockSpec cargo = BlockSpec.of(ctx.palette().full("cargo", node), "facing", "up");
        for (int u = 0; u < width; u++) {
            for (int w = 0; w < depth; w++) {
                boolean edge = u == 0 || w == 0 || u == width - 1 || w == depth - 1;
                ctx.emit(node, u, 0, w, p.b("marker") && edge ? marker : pad);
            }
        }
        ctx.emit(node, cargoU, 1, cargoW, cargo);
    }

    /** The air above the pad (v=1 to clearance) belongs to the pad: nothing else may stand in it, except the cargo barrel. */
    @Override
    public void afterAll(GenContext ctx, PlanNode node, Params p) {
        int clearance = p.i("clearance");
        for (int u = 0; u < p.i("width"); u++) {
            for (int w = 0; w < p.i("depth"); w++) {
                for (int dv = 1; dv <= clearance; dv++) {
                    LocalPos pos = ctx.placed(node, u, dv, w);
                    Canvas.Cell c = ctx.canvas().get(pos);
                    if (c != null && !c.ownerId().equals(node.id())) {
                        ctx.canvas().recordOverlap(node.id(), c.ownerId(), pos, "clearance");
                    }
                }
            }
        }
    }
}
```
`E-OVERLAP`の`data.reason=clearance`は、`Canvas.recordOverlap(a, b, pos, "clearance")`で理由を渡す(Task 10の`Canvas`)。台の回転(`Rot`)は、`GenContext.placed`が位置にかける。

`PartGenerators.ENTRIES`に、次の5件を足す:
```java
            Map.entry("micra:pillar", new Entry(new PillarGen(), Stage.BASE)),
            Map.entry("micra:beam", new Entry(new BeamGen(), Stage.BASE)),
            Map.entry("micra:chimney", new Entry(new ChimneyGen(), Stage.BASE)),
            Map.entry("micra:road", new Entry(new RoadGen(), Stage.BASE)),
            Map.entry("micra:dock_pad", new Entry(new DockPadGen(), Stage.BASE)),
```
(`Map.ofEntries`の項目が増えるので、`Map.ofEntries(`のままで良い。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 単独で立つ部品(pillar・beam・chimney・road・dock_pad)の生成器を追加(自然言語→工場建設 P3 Task 11)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: 開口部の生成器(`door`・`window`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/{OpeningSpot,DoorGen,WindowGen}.java`
- Modify: `PartGenerators.java`(`micra:door`・`micra:window`を`Stage.CARVE`で足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/gen/OpeningsTest.java`

**Interfaces:**
- Consumes: Task 10の`GenContext.wallOfAnchor`・`carve`・`WallInfo.cell`・`BlockForms`
- Produces: `OpeningSpot(WallInfo wall, int i, int row, boolean outer, int layer)`: `OpeningSpot.of(GenContext ctx, PlanNode node, int width, int height)`(アンカーの`OnSurface(壁, side, u, v)`から。`side`が`OUTER`なら`layer=0`、`INNER`なら`layer=thickness-1`)。`List<LocalPos> cells()`(掘るセル。全ての層)、`LocalPos at(int di, int dRow)`(扉・ガラスを置く層の位置)

**開口部の規則(設計図05 1.1.1):** 開口部は壁のマスを、全ての層(厚み)で掘り、置く物は`layer`の1層だけ。掘る範囲が壁の外(壁のマスでない位置)なら`E-OPENING-NO-WALL`。`door`の向きは室内向き(壁の`outward`の反対)、`INNER`なら室外向き。

- [ ] **Step 1: 失敗するテストを書く**

`OpeningsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpeningsTest {
    /** 7x7 building, floor height 4 (walls are 3 rows: v=1..3), walls only. South wall is at w=0, north at w=6. */
    private static Map<LocalPos, BlockSpec> build(PlanNode... openings) {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.addAll(List.of(openings));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    private static PlanNode on(String id, String type, String wall, Side side, int u, int v, Map<String, io.github.khayashi4337.micradrone.build.model.ParamValue> p) {
        return new PlanNode(id, type, "s", new io.github.khayashi4337.micradrone.build.model.Anchor.OnSurface(wall, side, u, v), p,
                java.util.Set.of(), "");
    }

    @Test
    void aSingleDoorReplacesTwoWallCellsAndFacesIndoors() {
        Map<LocalPos, BlockSpec> c = build(on("d", "micra:door", "wall-s", Side.OUTER, 3, 0, Map.of()));
        // south wall is at w=0 facing south; indoors is north
        assertEquals(BlockSpec.of("minecraft:oak_door", "facing", "north", "half", "lower", "hinge", "left"), c.get(new LocalPos(3, 1, 0)));
        assertEquals(BlockSpec.of("minecraft:oak_door", "facing", "north", "half", "upper", "hinge", "left"), c.get(new LocalPos(3, 2, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(3, 3, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(2, 1, 0)));
        assertEquals(72, c.size(), "the door takes the place of wall cells: the total does not change");
    }

    @Test
    void anInnerDoorFacesOutdoorsAndTheHingeCanBeRight() {
        Map<LocalPos, BlockSpec> c = build(on("d", "micra:door", "wall-s", Side.INNER, 3, 0, params("hinge", "right")));
        assertEquals(BlockSpec.of("minecraft:oak_door", "facing", "south", "half", "lower", "hinge", "right"), c.get(new LocalPos(3, 1, 0)));
    }

    @Test
    void aDoubleDoorHasTwoLeavesHingedOnTheOutsideEdges() {
        Map<LocalPos, BlockSpec> c = build(on("d", "micra:door", "wall-s", Side.OUTER, 2, 0, params("kind", "double")));
        assertEquals("left", c.get(new LocalPos(2, 1, 0)).get("hinge"));
        assertEquals("right", c.get(new LocalPos(3, 1, 0)).get("hinge"));
        assertEquals(4, countOf(c, "minecraft:oak_door"));
    }

    @Test
    void aHangarDoorIsAnOpeningWithTwoRowsOfGates() {
        List<PlanNode> nodes = new ArrayList<>(shell(9, 9, 1, 7)); // walls are 6 rows high
        nodes.add(on("d", "micra:door", "wall-s", Side.OUTER, 3, 0, params("kind", "hangar", "width", 3, "height", 4)));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals(6, countOf(c, "minecraft:oak_fence_gate"));
        assertEquals(BlockSpec.of("minecraft:oak_fence_gate", "facing", "north"), c.get(new LocalPos(4, 1, 0)));
        assertNull(c.get(new LocalPos(4, 3, 0)), "above the gates the opening is empty");
        assertNull(c.get(new LocalPos(4, 4, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(4, 5, 0)));
    }

    @Test
    void windowsPaneWideAndArch() {
        Map<LocalPos, BlockSpec> pane = build(on("w", "micra:window", "wall-e", Side.OUTER, 3, 1, Map.of()));
        // east wall is at u=6 and grows along +w; window at w=3, rows 1..2 = v 2..3
        assertEquals(BlockSpec.of("minecraft:glass_pane"), pane.get(new LocalPos(6, 2, 3)));
        assertEquals(BlockSpec.of("minecraft:glass_pane"), pane.get(new LocalPos(6, 3, 3)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), pane.get(new LocalPos(6, 1, 3)));
        assertEquals(2, countOf(pane, "minecraft:glass_pane"));

        Map<LocalPos, BlockSpec> wide = build(on("w", "micra:window", "wall-n", Side.OUTER, 2, 1, params("kind", "wide")));
        assertEquals(6, countOf(wide, "minecraft:glass_pane"));

        Map<LocalPos, BlockSpec> arch = build(on("w", "micra:window", "wall-n", Side.OUTER, 1, 0, params("kind", "arch")));
        assertEquals(7, countOf(arch, "minecraft:glass_pane"), "two full rows of three, and the middle of the top row");
        assertEquals(BlockSpec.of("minecraft:stone_brick_stairs", "facing", "west", "half", "top"), arch.get(new LocalPos(1, 3, 6)));
        assertEquals(BlockSpec.of("minecraft:stone_brick_stairs", "facing", "east", "half", "top"), arch.get(new LocalPos(3, 3, 6)));
        assertEquals(BlockSpec.of("minecraft:glass_pane"), arch.get(new LocalPos(2, 3, 6)));
    }

    @Test
    void archCornersOnAnEastWallFaceSouthAndNorth() {
        Map<LocalPos, BlockSpec> arch = build(on("w", "micra:window", "wall-e", Side.OUTER, 1, 0, params("kind", "arch")));
        // east wall grows along +w: the left corner (smaller w) faces south (-w), the right corner north (+w)
        assertEquals("south", arch.get(new LocalPos(6, 3, 1)).get("facing"));
        assertEquals("north", arch.get(new LocalPos(6, 3, 3)).get("facing"));
    }

    @Test
    void aLatticeReplacesTheMiddleColumnWithTrim() {
        Map<LocalPos, BlockSpec> c = build(on("w", "micra:window", "wall-n", Side.OUTER, 2, 1, params("kind", "wide", "lattice", true)));
        assertEquals(4, countOf(c, "minecraft:glass_pane"));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(3, 2, 6)), "the mullion is the trim material");
        CompileResult onPane = compile(withOpening(on("w", "micra:window", "wall-n", Side.OUTER, 2, 1, params("lattice", true))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(onPane));
    }

    private static List<PlanNode> withOpening(PlanNode opening) {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.add(opening);
        return nodes;
    }

    @Test
    void openingsThatReachOutsideTheWallAreRefused() {
        assertEquals(List.of("E-OPENING-NO-WALL"), codes(compile(withOpening(
                on("d", "micra:door", "wall-s", Side.OUTER, 6, 0, params("kind", "double"))))), "the second leaf would be past the end");
        assertEquals(List.of("E-OPENING-NO-WALL"), codes(compile(withOpening(
                on("w", "micra:window", "wall-s", Side.OUTER, 2, 2, params("kind", "arch"))))), "the top row is above the wall");
        assertEquals(List.of("E-OPENING-NO-WALL"), codes(compile(withOpening(
                on("d", "micra:door", "wall-s", Side.OUTER, 9, 0, Map.of())))));
    }

    @Test
    void twoOpeningsCannotShareACell() {
        CompileResult r = compile(withOpening(on("d1", "micra:door", "wall-s", Side.OUTER, 3, 0, Map.of())));
        assertTrue(r.issues().isEmpty());
        List<PlanNode> twice = withOpening(on("d1", "micra:door", "wall-s", Side.OUTER, 3, 0, Map.of()));
        twice.add(on("d2", "micra:window", "wall-s", Side.OUTER, 3, 1, Map.of()));
        CompileResult clash = compile(twice);
        assertNull(clash.manifest());
        assertTrue(codes(clash).contains("E-OVERLAP"), clash.issues().toString());
    }

    @Test
    void aThickWallIsCarvedThroughAndTheDoorSitsOnTheChosenSide() {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.set(3, node("wall-s", "micra:wall", "s", 0, 0, 0, params("side", "south", "thickness", 2)));
        nodes.add(on("d", "micra:door", "wall-s", Side.OUTER, 3, 0, Map.of()));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals("lower", c.get(new LocalPos(3, 1, 0)).get("half"), "outer layer is w=0");
        assertNull(c.get(new LocalPos(3, 1, 1)), "the inner layer is carved to air");
        assertNull(c.get(new LocalPos(3, 2, 1)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(2, 1, 1)));
    }

    @Test
    void anOpeningNeedsAWallAnchor() {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.add(node("d", "micra:door", "s", 0, 0, 0, Map.of()));
        assertEquals(List.of("E-ANCHOR"), codes(compile(nodes)));
        assertTrue(CompileFixtures.REGISTRY.contains("micra:door"));
    }
}
```
(`openingsThatReachOutsideTheWallAreRefused`の`wall-s`の`u=9`は壁の外なので、`PlanPatcher`ではなくコンパイルで`E-OPENING-NO-WALL`。`u=2, v=2`のarchは、行2〜4で、壁の行(0〜2)を越える。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.gen.OpeningsTest" --console=plain`
Expected: FAIL(`E-UNKNOWN-PART`)。

- [ ] **Step 3: 実装する**

`OpeningSpot.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.util.ArrayList;
import java.util.List;

/** Where an opening sits on a wall: which wall, the first cell along it, the first row, and the layer things are placed in. */
record OpeningSpot(WallInfo wall, int i, int row, boolean outer, int layer, int width, int height) {
    static OpeningSpot of(GenContext ctx, PlanNode node, int width, int height) {
        WallInfo wall = ctx.wallOfAnchor(node);
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        boolean outer = a.side() == Side.OUTER;
        return new OpeningSpot(wall, a.u(), a.v(), outer, outer ? 0 : wall.thickness() - 1, width, height);
    }

    /** Every cell the opening replaces: all layers of the wall over the opening's width and height. */
    List<LocalPos> cells() {
        List<LocalPos> out = new ArrayList<>();
        for (int di = 0; di < width; di++) {
            for (int dr = 0; dr < height; dr++) {
                for (int layer = 0; layer < wall.thickness(); layer++) {
                    out.add(wall.cell(i + di, layer, row + dr));
                }
            }
        }
        return out;
    }

    LocalPos at(int di, int dRow) {
        return wall.cell(i + di, layer, row + dRow);
    }
}
```
(`wall.cell(i+di, ...)`が壁の長さを越える位置は、`wall.cell`が幾何のまま範囲外の座標を返し、`carve`が「壁のマスでない」を検出する。)

`DoorGen.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

final class DoorGen implements PartGenerator {
    private static final int GATE_ROWS = 2;
    private static final int LEAF_HEIGHT = 2;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String kind = p.s("kind");
        int width = kind.equals("single") ? 1 : kind.equals("double") ? 2 : p.i("width");
        int height = kind.equals("hangar") ? p.i("height") : LEAF_HEIGHT;
        OpeningSpot spot = OpeningSpot.of(ctx, node, width, height);
        ctx.carve(node, spot.wall(), spot.cells());
        // doors and gates face indoors; an INNER opening faces outdoors
        Facing facing = spot.outer() ? spot.wall().outward().opposite() : spot.wall().outward();
        WallInfo wall = spot.wall();
        if (kind.equals("hangar")) {
            String gate = ctx.palette().full("gate", node);
            for (int di = 0; di < width; di++) {
                for (int dr = 0; dr < Math.min(GATE_ROWS, height); dr++) {
                    ctx.emitAbs(node, spot.at(di, dr), BlockForms.gate(gate, facing));
                }
            }
            return;
        }
        String door = ctx.palette().full(p.s("material"), node);
        for (int di = 0; di < width; di++) {
            boolean hingeRight = kind.equals("double") ? di == 1 : p.s("hinge").equals("right");
            ctx.emitAbs(node, spot.at(di, 0), BlockForms.door(door, facing, false, hingeRight));
            ctx.emitAbs(node, spot.at(di, 1), BlockForms.door(door, facing, true, hingeRight));
        }
    }
}
```
`WindowGen.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

final class WindowGen implements PartGenerator {
    private static final int PANE_HEIGHT = 2;
    private static final int WIDE_WIDTH = 3;
    private static final int ARCH_HEIGHT = 3;
    /** The arch window is glazed across its full width in the two lower rows; the top row is the arch. */
    private static final int ARCH_FULL_ROWS = 2;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String kind = p.s("kind");
        int width = kind.equals("pane") ? 1 : WIDE_WIDTH;
        int height = kind.equals("arch") ? ARCH_HEIGHT : PANE_HEIGHT;
        if (p.b("lattice") && width == 1) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "lattice", "格子(lattice)は、幅のある窓(wide・arch)だけで使えます");
        }
        OpeningSpot spot = OpeningSpot.of(ctx, node, width, height);
        ctx.carve(node, spot.wall(), spot.cells());
        BlockSpec glass = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        BlockSpec mullion = BlockForms.plain(ctx.palette().full("trim", node));
        int middle = width / 2;
        int fullRows = kind.equals("arch") ? ARCH_FULL_ROWS : height;
        for (int di = 0; di < width; di++) {
            for (int dr = 0; dr < fullRows; dr++) {
                ctx.emitAbs(node, spot.at(di, dr), p.b("lattice") && di == middle ? mullion : glass);
            }
        }
        if (kind.equals("arch")) {
            int top = ARCH_FULL_ROWS;
            ctx.emitAbs(node, spot.at(middle, top), glass);
            String stairs = ctx.palette().stairs("trim", node);
            ctx.emitAbs(node, spot.at(0, top), BlockForms.stairs(stairs, spot.wall().along().opposite(), true));
            ctx.emitAbs(node, spot.at(width - 1, top), BlockForms.stairs(stairs, spot.wall().along(), true));
        }
    }
}
```
(`import`を付ける。)

`PartGenerators.ENTRIES`に足す:
```java
            Map.entry("micra:door", new Entry(new DoorGen(), Stage.CARVE)),
            Map.entry("micra:window", new Entry(new WindowGen(), Stage.CARVE)),
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 開口部(door・window)の生成器を追加。壁を掘り、壁の外にはみ出せば拒否(自然言語→工場建設 P3 Task 12)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 13: 屋根の生成器(`roof`。6種)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/{RoofFrame,RoofGen}.java`
- Modify: `PartGenerators.java`(`micra:roof`を`Stage.BASE`で足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/gen/RoofTest.java`

**Interfaces:**
- Consumes: Task 10の`GenContext.structureOf`・`Palette.stairs`/`slab`/`full`・`BlockForms`
- Produces: `RoofFrame(boolean ridgeAlongW, int a0, int a1, int b0, int b1)`: 勾配を横切る軸`a`と、棟に沿う軸`b`を、局所の(u,w)へ写す。`LocalPos pos(int a, int v, int b)`、`Facing towardRidgeFromLow()`(`a`が小さい側の階段の背の向き。棟がwに沿えば`EAST`、uに沿えば`NORTH`)、`Facing towardRidgeFromHigh()`、`Facing risingDirection()`(`shed`・`sawtooth`用。`a`が増える向き)

**屋根の作り方(設計図05 1.1.1。テストの数はこの規則から手で導いた):**
- 土台の`v`=構造の`origin.v + floors*floorHeight`。範囲は、u∈[o.u−overhang, o.u+width−1+overhang]、w も同様。`ridge`が`auto`なら、長い側に沿う(同じなら`w`に沿う)。棟が`w`に沿うとき、勾配は`u`を横切る(`a=u`、`b=w`)。棟が`u`に沿うときは`a=w`、`b=u`。
- 階段は`BlockForms.stairs(id, 背の向き, false)`(`half=bottom`)。素材は`palette.stairs(material)`、スラブは`palette.slab(material)`、全ブロックは`palette.full(material)`。族の無い素材は`E-PARAM-RANGE`(Task 10の`Palette`が返す)。
- `gable`: 段`k`=0,1,…で`aL=a0+k`、`aR=a1−k`。`aL<aR`なら、`(aL, base+k, b)`に低い側の階段、`(aR, base+k, b)`に高い側の階段を、全`b`について置く。`aL==aR`なら、全`b`に全ブロックを置いて終わり。`gable_fill`が真なら、棟の両端の面(構造の縁`b`=構造のb最小・最大)で、構造の中の列`a`ごとに、高さ`base`〜`base+k(a)−1`(`k(a)=min(a−a0, a1−a)`)を、役割`wall`の全ブロックで埋める。
- `hip`: 段`k`で、`uL=u0+k`、`uR=u1−k`、`wS=w0+k`、`wN=w1−k`。範囲が空なら終わり。外周のマスだけを置く。`uL==uR`または`wS==wN`なら全ブロック。それ以外は階段(`u==uL`なら`EAST`、`u==uR`なら`WEST`、`w==wS`なら`NORTH`、`w==wN`なら`SOUTH`。角は東西を優先)。
- `flat`: `v=base`に、下付きのスラブを、範囲の全マスに。
- `shed`: `high_side`が`EAST`/`WEST`なら`a=u`、`NORTH`/`SOUTH`なら`a=w`。高い側へ向かって1段ごとに1つ上がる: 高い側が`a`の大きい側なら`i=a−a0`、小さい側なら`i=a1−a`。`(a, base+i, b)`に、背を高い側へ向けた階段。`gable_fill`が真なら、両端の面で、構造の中の列に、高さ`base`〜`base+i(a)−1`を役割`wall`で埋める。
- `sawtooth`: 棟に沿う軸`b`と直角に、幅`tooth`ごとの歯。歯の中の番号`idx`(0から)の階段を`(a, base+idx, b)`、背は`a`が増える向き。各歯の最後のマス(または端の欠けた歯の最後のマス)の1つ上`(a, base+idx+1, b)`に、役割`glass`の全ブロック。
- `monitor`: `T=a1−a0+1`。`(T−monitor_width)`が偶数でなく、または`T<=monitor_width`なら`E-PARAM-RANGE`。`K=(T−monitor_width)/2`。段`0..K−1`を`gable`と同じに積む。すきま(`aM0=a0+K`〜`aM1=a1−K`)の両縁の列に、`(a, base+K+dr, b)`(`dr=0..monitor_height−1`)へ役割`glass`の全ブロック。その上`(a, base+K+monitor_height, b)`に、すきまの全列で、下付きのスラブ(蓋)。`gable_fill`が真なら、両端の面で構造の中の列に、高さ`base`〜`base+min(a−a0,a1−a,K)−1`を役割`wall`で埋め、すきまの内側の列(両縁を除く)の`base+K`〜`base+K+monitor_height−1`を役割`glass`で埋める。

- [ ] **Step 1: 失敗するテストを書く**

`RoofTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoofTest {
    private static final String STAIRS = "minecraft:oak_stairs";

    /** A bare structure (no walls) plus a roof: the roof base is at v = floors * floorHeight = 4. */
    private static Map<LocalPos, BlockSpec> roof(int width, int depth, Map<String, io.github.khayashi4337.micradrone.build.model.ParamValue> roofParams) {
        return roof(StyleSpec.EMPTY, width, depth, roofParams);
    }

    private static Map<LocalPos, BlockSpec> roof(StyleSpec style, int width, int depth,
                                                  Map<String, io.github.khayashi4337.micradrone.build.model.ParamValue> roofParams) {
        CompileResult r = compile(style, List.of(
                node("s", "micra:structure", null, 0, 0, 0, params("width", width, "depth", depth)),
                node("r", "micra:roof", "s", 0, 0, 0, roofParams)));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    @Test
    void aGableRoofOverASevenBySevenBuilding() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params("overhang", 0));
        assertEquals(42, countOf(c, STAIRS), "three layers of two stair columns, seven cells long");
        assertEquals(7, countOf(c, "minecraft:oak_planks"), "the ridge row is full blocks of the roof material");
        assertEquals(18, countOf(c, "minecraft:stone_bricks"), "gable ends: 0+1+2+3+2+1+0 = 9 per end");
        assertEquals(67, c.size());
        // stairs lean toward the ridge: the low side (u=0) has its back to the east
        assertEquals(BlockSpec.of(STAIRS, "facing", "east", "half", "bottom"), c.get(new LocalPos(0, 4, 3)));
        assertEquals(BlockSpec.of(STAIRS, "facing", "west", "half", "bottom"), c.get(new LocalPos(6, 4, 3)));
        assertEquals(BlockSpec.of("minecraft:oak_planks"), c.get(new LocalPos(3, 7, 3)), "the ridge");
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), c.get(new LocalPos(3, 6, 0)), "gable end fill under the ridge");
        assertNull(c.get(new LocalPos(3, 6, 3)), "the attic is open");
    }

    @Test
    void theRidgeFollowsTheLongerSide() {
        Map<LocalPos, BlockSpec> c = roof(9, 5, params("overhang", 0));
        // ridge along u, slopes across w (T=5): two layers of two stair rows, nine cells long, ridge row of nine blocks
        assertEquals(36, countOf(c, STAIRS));
        assertEquals(9, countOf(c, "minecraft:oak_planks"));
        assertEquals("north", c.get(new LocalPos(4, 4, 0)).get("facing"), "the w=0 side leans toward +w");
        assertEquals("south", c.get(new LocalPos(4, 4, 4)).get("facing"));
        Map<LocalPos, BlockSpec> forced = roof(9, 5, params("overhang", 0, "ridge", "w"));
        assertTrue(countOf(forced, STAIRS) != 36, "an explicit ridge overrides the automatic one");
    }

    @Test
    void anEvenWidthGableHasTwoStairsAtTheRidgeAndNoBlockRow() {
        Map<LocalPos, BlockSpec> c = roof(6, 7, params("overhang", 0, "gable_fill", false));
        // T=6 across u (ridge along w since depth 7 > width 6): layers k=0,1,2, two columns each, 7 long
        assertEquals(42, countOf(c, STAIRS));
        assertEquals(42, c.size());
        assertEquals("east", c.get(new LocalPos(2, 6, 0)).get("facing"));
        assertEquals("west", c.get(new LocalPos(3, 6, 0)).get("facing"));
    }

    @Test
    void overhangGrowsTheRoofPastTheWalls() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params("overhang", 1, "gable_fill", false));
        assertTrue(c.containsKey(new LocalPos(-1, 4, -1)));
        assertTrue(c.containsKey(new LocalPos(7, 4, 7)));
        assertNull(c.get(new LocalPos(-2, 4, 0)));
    }

    @Test
    void aHipRoofShrinksRingByRing() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params("kind", "hip", "overhang", 0));
        assertEquals(48, countOf(c, STAIRS), "rings of 24, 16 and 8 cells");
        assertEquals(1, countOf(c, "minecraft:oak_planks"), "the last ring is one block");
        assertEquals(49, c.size());
        assertEquals("east", c.get(new LocalPos(0, 4, 0)).get("facing"), "corners prefer the east/west lean");
        assertEquals("north", c.get(new LocalPos(3, 4, 0)).get("facing"));
        assertEquals("south", c.get(new LocalPos(3, 4, 6)).get("facing"));
        assertEquals(BlockSpec.of("minecraft:oak_planks"), c.get(new LocalPos(3, 7, 3)));
    }

    @Test
    void aFlatRoofIsOneLayerOfBottomSlabs() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params("kind", "flat"));
        assertEquals(81, c.size());
        assertEquals(BlockSpec.of("minecraft:oak_slab", "type", "bottom"), c.get(new LocalPos(-1, 4, -1)));
    }

    @Test
    void aShedRoofRisesTowardItsHighSide() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params("kind", "shed", "overhang", 0, "high_side", "east"));
        assertEquals(49, countOf(c, STAIRS));
        assertEquals(42, countOf(c, "minecraft:stone_bricks"), "end triangles: 0+1+...+6 = 21 per end");
        assertEquals(BlockSpec.of(STAIRS, "facing", "east", "half", "bottom"), c.get(new LocalPos(0, 4, 3)));
        assertEquals(BlockSpec.of(STAIRS, "facing", "east", "half", "bottom"), c.get(new LocalPos(6, 10, 3)));
        Map<LocalPos, BlockSpec> west = roof(7, 7, params("kind", "shed", "overhang", 0, "high_side", "west", "gable_fill", false));
        assertEquals("west", west.get(new LocalPos(0, 10, 3)).get("facing"));
        Map<LocalPos, BlockSpec> north = roof(7, 7, params("kind", "shed", "overhang", 0, "high_side", "north", "gable_fill", false));
        assertEquals("north", north.get(new LocalPos(3, 10, 6)).get("facing"));
    }

    @Test
    void aSawtoothRoofHasStairTeethAndGlassSteps() {
        StyleSpec glass = new StyleSpec(Map.of("glass", "minecraft:glass"), Set.of());
        Map<LocalPos, BlockSpec> c = roof(glass, 7, 7, params("kind", "sawtooth", "overhang", 0, "tooth", 3));
        assertEquals(49, countOf(c, STAIRS));
        assertEquals(21, countOf(c, "minecraft:glass"), "one glass row above the last stair of each of the three teeth");
        assertEquals(BlockSpec.of("minecraft:glass"), c.get(new LocalPos(2, 7, 0)));
        assertEquals(BlockSpec.of("minecraft:glass"), c.get(new LocalPos(6, 5, 0)), "the last, partial tooth is one column wide");
        assertEquals(70, c.size());
    }

    @Test
    void aMonitorRoofHasAClerestoryOnTop() {
        StyleSpec glass = new StyleSpec(Map.of("glass", "minecraft:glass"), Set.of());
        Map<LocalPos, BlockSpec> c = roof(glass, 7, 7, params("kind", "monitor", "overhang", 0));
        assertEquals(42, countOf(c, STAIRS));
        assertEquals(7, countOf(c, "minecraft:glass"));
        assertEquals(7, countOf(c, "minecraft:oak_slab"));
        assertEquals(18, countOf(c, "minecraft:stone_bricks"));
        assertEquals(74, c.size());
        assertEquals(BlockSpec.of("minecraft:oak_slab", "type", "bottom"), c.get(new LocalPos(3, 8, 3)));
        assertEquals(BlockSpec.of("minecraft:glass"), c.get(new LocalPos(3, 7, 3)));
        CompileResult parity = compile(StyleSpec.EMPTY, List.of(node("s", "micra:structure", null, 0, 0, 0, params("width", 7, "depth", 7)),
                node("r", "micra:roof", "s", 0, 0, 0, params("kind", "monitor", "monitor_width", 2, "overhang", 0))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(parity), "a 7-wide roof cannot have a 2-wide clerestory");
    }

    @Test
    void materialsWithoutStairsAreRefusedWithAHint() {
        StyleSpec terracotta = new StyleSpec(Map.of("roof", "minecraft:red_terracotta"), Set.of());
        CompileResult r = compile(terracotta, List.of(node("s", "micra:structure", null, 0, 0, 0, Map.of()),
                node("r", "micra:roof", "s", 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-PARAM-RANGE"), codes(r));
        assertTrue(r.issues().get(0).hints().get(0).args().get("ids").contains("red_nether_bricks"));
        // an explicit stairs role makes a terracotta ridge with brick stairs possible
        StyleSpec explicit = new StyleSpec(Map.of("roof", "minecraft:red_terracotta", "roof_stairs", "minecraft:red_nether_brick_stairs"), Set.of());
        Map<LocalPos, BlockSpec> c = roof(explicit, 7, 7, params("overhang", 0, "gable_fill", false));
        assertEquals(42, countOf(c, "minecraft:red_nether_brick_stairs"));
        assertEquals(7, countOf(c, "minecraft:red_terracotta"));
    }

    @Test
    void aRoofNeedsABuildingParent() {
        CompileResult r = compile(List.of(node("r", "micra:roof", null, 0, 0, 0, Map.of())));
        assertEquals(List.of("E-ANCHOR"), codes(r));
    }

    @Test
    void theRoofSitsOnTopOfTheLastFloor() {
        CompileResult r = compile(StyleSpec.EMPTY, List.of(
                node("s", "micra:structure", null, 0, 0, 0, params("width", 5, "depth", 5, "floors", 2, "floor_height", 3)),
                node("r", "micra:roof", "s", 0, 0, 0, params("overhang", 0, "kind", "flat"))));
        assertEquals(6, cells(r.manifest()).keySet().iterator().next().v(), "2 floors x 3 = base at v=6");
    }
}
```
- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.gen.RoofTest" --console=plain`
Expected: FAIL(`E-UNKNOWN-PART`)。

- [ ] **Step 3: 実装する**

`RoofFrame.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;

/**
 * Maps a roof's own axes to the local frame: {@code a} runs across the slope, {@code b} along the ridge. With the
 * ridge along w the slope runs across u (a=u, b=w); with the ridge along u it runs across w (a=w, b=u).
 */
record RoofFrame(boolean ridgeAlongW, int a0, int a1, int b0, int b1) {
    LocalPos pos(int a, int v, int b) {
        return ridgeAlongW ? new LocalPos(a, v, b) : new LocalPos(b, v, a);
    }

    /** Back direction of a stair on the low-a side: toward the ridge, i.e. toward larger a. */
    Facing towardRidgeFromLow() {
        return ridgeAlongW ? Facing.EAST : Facing.NORTH;
    }

    Facing towardRidgeFromHigh() {
        return towardRidgeFromLow().opposite();
    }
}
```
`RoofGen.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

final class RoofGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int o = p.i("overhang");
        LocalPos org = st.origin();
        int u0 = org.u() - o;
        int u1 = org.u() + st.width() - 1 + o;
        int w0 = org.w() - o;
        int w1 = org.w() + st.depth() - 1 + o;
        int base = org.v() + st.totalHeight();
        String ridge = p.s("ridge");
        boolean ridgeAlongW = ridge.equals("w") || ridge.equals("auto") && st.depth() >= st.width();
        RoofFrame f = ridgeAlongW ? new RoofFrame(true, u0, u1, w0, w1) : new RoofFrame(false, w0, w1, u0, u1);
        String material = p.s("material");
        switch (p.s("kind")) {
            case "gable" -> gable(ctx, node, p, st, f, base, material, Integer.MAX_VALUE, false);
            case "hip" -> hip(ctx, node, u0, u1, w0, w1, base, material);
            case "flat" -> flat(ctx, node, u0, u1, w0, w1, base, material);
            case "shed" -> shed(ctx, node, p, st, u0, u1, w0, w1, base, material);
            case "sawtooth" -> sawtooth(ctx, node, p, f, base, material);
            case "monitor" -> monitor(ctx, node, p, st, f, base, material);
            default -> throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "kind", "屋根の種類が不明です");
        }
    }

    /** Stair columns from both edges meeting at the ridge; stops after {@code maxLayers} layers when truncated (monitor). */
    private void gable(GenContext ctx, PlanNode node, Params p, StructureInfo st, RoofFrame f, int base, String material,
                       int maxLayers, boolean truncated) {
        String stairsId = ctx.palette().stairs(material, node);
        BlockSpec low = BlockForms.stairs(stairsId, f.towardRidgeFromLow(), false);
        BlockSpec high = BlockForms.stairs(stairsId, f.towardRidgeFromHigh(), false);
        for (int k = 0; k < maxLayers; k++) {
            int aL = f.a0() + k;
            int aR = f.a1() - k;
            if (aL > aR) {
                break;
            }
            if (aL == aR) {
                BlockSpec ridge = BlockForms.plain(ctx.palette().full(material, node));
                for (int b = f.b0(); b <= f.b1(); b++) {
                    ctx.emitAbs(node, f.pos(aL, base + k, b), ridge);
                }
                break;
            }
            for (int b = f.b0(); b <= f.b1(); b++) {
                ctx.emitAbs(node, f.pos(aL, base + k, b), low);
                ctx.emitAbs(node, f.pos(aR, base + k, b), high);
            }
        }
        if (p.b("gable_fill") && !truncated) {
            fillEnds(ctx, node, st, f, base, a -> Math.min(a - f.a0(), f.a1() - a));
        }
    }

    /** Fills the triangle under the slope at both ends of the ridge, inside the building's own footprint. */
    private void fillEnds(GenContext ctx, PlanNode node, StructureInfo st, RoofFrame f, int base, java.util.function.IntUnaryOperator height) {
        BlockSpec wall = BlockForms.plain(ctx.palette().full("wall", node));
        int[] ends = f.ridgeAlongW() ? new int[]{st.origin().w(), st.origin().w() + st.depth() - 1}
                : new int[]{st.origin().u(), st.origin().u() + st.width() - 1};
        int aFrom = f.ridgeAlongW() ? st.origin().u() : st.origin().w();
        int aTo = f.ridgeAlongW() ? st.origin().u() + st.width() - 1 : st.origin().w() + st.depth() - 1;
        for (int b : ends) {
            for (int a = aFrom; a <= aTo; a++) {
                for (int dv = 0; dv < height.applyAsInt(a); dv++) {
                    ctx.emitAbs(node, f.pos(a, base + dv, b), wall);
                }
            }
        }
    }

    private void hip(GenContext ctx, PlanNode node, int u0, int u1, int w0, int w1, int base, String material) {
        String stairsId = ctx.palette().stairs(material, node);
        BlockSpec full = BlockForms.plain(ctx.palette().full(material, node));
        for (int k = 0; ; k++) {
            int uL = u0 + k;
            int uR = u1 - k;
            int wS = w0 + k;
            int wN = w1 - k;
            if (uL > uR || wS > wN) {
                break;
            }
            boolean degenerate = uL == uR || wS == wN;
            for (int u = uL; u <= uR; u++) {
                for (int w = wS; w <= wN; w++) {
                    if (!(u == uL || u == uR || w == wS || w == wN)) {
                        continue;
                    }
                    Facing back = u == uL ? Facing.EAST : u == uR ? Facing.WEST : w == wS ? Facing.NORTH : Facing.SOUTH;
                    ctx.emitAbs(node, new LocalPos(u, base + k, w), degenerate ? full : BlockForms.stairs(stairsId, back, false));
                }
            }
        }
    }

    private void flat(GenContext ctx, PlanNode node, int u0, int u1, int w0, int w1, int base, String material) {
        BlockSpec slab = BlockForms.slab(ctx.palette().slab(material, node), false);
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                ctx.emitAbs(node, new LocalPos(u, base, w), slab);
            }
        }
    }

    private void shed(GenContext ctx, PlanNode node, Params p, StructureInfo st, int u0, int u1, int w0, int w1, int base,
                      String material) {
        Facing high = Facing.parse(p.s("high_side"));
        boolean acrossU = high == Facing.EAST || high == Facing.WEST;
        RoofFrame f = acrossU ? new RoofFrame(true, u0, u1, w0, w1) : new RoofFrame(false, w0, w1, u0, u1);
        boolean risesWithA = high == Facing.EAST || high == Facing.NORTH;
        BlockSpec stairs = BlockForms.stairs(ctx.palette().stairs(material, node), high, false);
        java.util.function.IntUnaryOperator rise = a -> risesWithA ? a - f.a0() : f.a1() - a;
        for (int a = f.a0(); a <= f.a1(); a++) {
            for (int b = f.b0(); b <= f.b1(); b++) {
                ctx.emitAbs(node, f.pos(a, base + rise.applyAsInt(a), b), stairs);
            }
        }
        if (p.b("gable_fill")) {
            fillEnds(ctx, node, st, f, base, rise);
        }
    }

    private void sawtooth(GenContext ctx, PlanNode node, Params p, RoofFrame f, int base, String material) {
        int tooth = p.i("tooth");
        Facing rising = f.towardRidgeFromLow();
        BlockSpec stairs = BlockForms.stairs(ctx.palette().stairs(material, node), rising, false);
        BlockSpec glass = BlockForms.plain(ctx.palette().full("glass", node));
        for (int a = f.a0(); a <= f.a1(); a++) {
            int idx = (a - f.a0()) % tooth;
            for (int b = f.b0(); b <= f.b1(); b++) {
                ctx.emitAbs(node, f.pos(a, base + idx, b), stairs);
            }
            boolean last = idx == tooth - 1 || a == f.a1();
            if (last) {
                for (int b = f.b0(); b <= f.b1(); b++) {
                    ctx.emitAbs(node, f.pos(a, base + idx + 1, b), glass);
                }
            }
        }
    }

    private void monitor(GenContext ctx, PlanNode node, Params p, StructureInfo st, RoofFrame f, int base, String material) {
        int total = f.a1() - f.a0() + 1;
        int mw = p.i("monitor_width");
        int mh = p.i("monitor_height");
        if (total <= mw || (total - mw) % 2 != 0) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "monitor_width",
                    "屋根の幅(" + total + ")と越屋根の幅(" + mw + ")の差が偶数になるようにしてください");
        }
        int layers = (total - mw) / 2;
        gable(ctx, node, p, st, f, base, material, layers, true);
        int aM0 = f.a0() + layers;
        int aM1 = f.a1() - layers;
        BlockSpec glass = BlockForms.plain(ctx.palette().full("glass", node));
        BlockSpec cap = BlockForms.slab(ctx.palette().slab(material, node), false);
        for (int b = f.b0(); b <= f.b1(); b++) {
            for (int dr = 0; dr < mh; dr++) {
                ctx.emitAbs(node, f.pos(aM0, base + layers + dr, b), glass);
                if (aM1 != aM0) {
                    ctx.emitAbs(node, f.pos(aM1, base + layers + dr, b), glass);
                }
            }
            for (int a = aM0; a <= aM1; a++) {
                ctx.emitAbs(node, f.pos(a, base + layers + mh, b), cap);
            }
        }
        if (p.b("gable_fill")) {
            fillEnds(ctx, node, st, f, base, a -> Math.min(Math.min(a - f.a0(), f.a1() - a), layers));
            int[] ends = f.ridgeAlongW() ? new int[]{st.origin().w(), st.origin().w() + st.depth() - 1}
                    : new int[]{st.origin().u(), st.origin().u() + st.width() - 1};
            for (int b : ends) {
                for (int a = aM0 + 1; a < aM1; a++) {
                    for (int dr = 0; dr < mh; dr++) {
                        ctx.emitAbs(node, f.pos(a, base + layers + dr, b), glass);
                    }
                }
            }
        }
    }
}
```
(`RoofFrame.ridgeAlongW()`は、`record`の構成要素のアクセサ。)
`GenContext`に、`LocalPos`の`emitAbs`のためのものは追加不要。`PartGenerators.ENTRIES`に足す:
```java
            Map.entry("micra:roof", new Entry(new RoofGen(), Stage.BASE)),
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 屋根(gable・hip・flat・shed・sawtooth・monitor)の生成器を追加(自然言語→工場建設 P3 Task 13)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: 階段・はしご・斜路・歩廊・手すり・バルコニーの生成器

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/{StairsGen,LadderGen,RampGen,CatwalkGen,RailingGen,BalconyGen}.java`
- Modify: `PartGenerators.java`(6件を`Stage.BASE`で足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/gen/StairwayPartsTest.java`

**Interfaces:**
- Consumes: Task 10・11の`GenContext`・`BlockForms`・`Dirs`・`WallInfo`
- Produces: なし(生成器のみ)

**規則(設計図05 1.1.1):** 歩く向き`dir`の右手=`Dirs.right(dir)`。`stairs`: 段`s`(0〜steps−1)・幅`j`のマスを`(s*dir.du + j*right.du, s, s*dir.dw + j*right.dw)`に、背を`dir`へ向けた階段。`ladder`: `(0, 0..height−1, 0)`に`ladder(facing)`。`ramp`: マス`i`(0〜length−1)・幅`j`で、`i`が偶数なら下付きスラブを`dv=i/2`に、奇数なら上付きスラブを`dv=(i−1)/2`に。`catwalk`: 床`BlockForms.flat(素材)`を`dv=0`に、`rail`が真なら`dv=1`の`j=0`と`j=width−1`の列に、役割`fence`の柵。`railing`: `(i*dir.du, dv, i*dir.dw)`、`dv=0..height−1`に柵。`balcony`(`OnSurface(壁, OUTER, u, v)`。`INNER`は`E-ANCHOR`): 床の高さ`row=v−1`(壁の最下段の1つ下=その階の床)、床は`i=u..u+width−1`・外側へ`k=1..depth`(`wall.cell(i, −k, row)`)に平らなブロック(役割`floor`)。`rail`が真なら、`row+1`に、外の縁(`k=depth`)の`i=u..u+width−1`と、両脇(`i=u`・`i=u+width−1`)の`k=1..depth−1`に、役割`fence`の柵(重なるマスは1個)。

- [ ] **Step 1: 失敗するテストを書く**

`StairwayPartsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StairwayPartsTest {
    private static Map<LocalPos, BlockSpec> build(PlanNode... nodes) {
        CompileResult r = compile(List.of(nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    @Test
    void aStaircaseRisesOneStepPerCellAndGrowsToTheRight() {
        Map<LocalPos, BlockSpec> c = build(node("st", "micra:stairs", null, 0, 0, 0, params("steps", 3, "width", 2, "dir", "north")));
        assertEquals(6, c.size());
        BlockSpec step = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertEquals(step, c.get(new LocalPos(0, 0, 0)));
        assertEquals(step, c.get(new LocalPos(1, 1, 1)), "right of north is east (+u)");
        assertEquals(step, c.get(new LocalPos(1, 2, 2)));
    }

    @Test
    void aLadderClimbsAndFacesTheGivenDirection() {
        Map<LocalPos, BlockSpec> c = build(node("l", "micra:ladder", null, 0, 0, 0, params("height", 3, "facing", "east")));
        assertEquals(3, c.size());
        assertEquals(BlockSpec.of("minecraft:ladder", "facing", "east"), c.get(new LocalPos(0, 2, 0)));
    }

    @Test
    void aRampAlternatesBottomAndTopSlabs() {
        Map<LocalPos, BlockSpec> c = build(node("r", "micra:ramp", null, 0, 0, 0, params("length", 4, "dir", "east", "width", 1)));
        assertEquals(BlockSpec.of("minecraft:stone_slab", "type", "bottom"), c.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_slab", "type", "top"), c.get(new LocalPos(1, 0, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_slab", "type", "bottom"), c.get(new LocalPos(2, 1, 0)));
        assertEquals(BlockSpec.of("minecraft:stone_slab", "type", "top"), c.get(new LocalPos(3, 1, 0)));
        assertEquals(4, c.size());
    }

    @Test
    void aCatwalkHasAGratedFloorAndRailingsOnBothEdges() {
        Map<LocalPos, BlockSpec> c = build(node("c", "micra:catwalk", null, 0, 0, 0, params("length", 3, "dir", "north", "width", 2)));
        assertEquals(6, countOf(c, "minecraft:iron_trapdoor"));
        assertEquals(BlockSpec.of("minecraft:iron_trapdoor", "half", "bottom"), c.get(new LocalPos(1, 0, 2)));
        assertEquals(6, countOf(c, "minecraft:oak_fence"));
        assertEquals(BlockSpec.of("minecraft:oak_fence"), c.get(new LocalPos(0, 1, 0)));
        assertEquals(12, c.size());
        Map<LocalPos, BlockSpec> bare = build(node("c", "micra:catwalk", null, 0, 0, 0, params("length", 3, "rail", false)));
        assertEquals(6, bare.size());
    }

    @Test
    void aRailingIsARunOfFence() {
        Map<LocalPos, BlockSpec> c = build(node("r", "micra:railing", null, 0, 0, 0, params("length", 3, "dir", "west", "height", 2)));
        assertEquals(6, c.size());
        assertTrue(c.containsKey(new LocalPos(-2, 1, 0)));
    }

    @Test
    void aBalconyProjectsFromAWallWithRailings() {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.add(new PlanNode("b", "micra:balcony", "s", new Anchor.OnSurface("wall-n", Side.OUTER, 2, 0),
                params("width", 3, "depth", 2), Set.of(), ""));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        // the north wall is at w=6; the balcony floor is at the storey's floor level (v=0), w=7..8
        assertEquals(BlockSpec.of("minecraft:oak_planks"), c.get(new LocalPos(2, 0, 7)));
        assertEquals(BlockSpec.of("minecraft:oak_planks"), c.get(new LocalPos(4, 0, 8)));
        assertEquals(6, countOf(c, "minecraft:oak_planks"), "the balcony floor only (there is no floor part in this plan)");
        assertEquals(5, countOf(c, "minecraft:oak_fence"), "far edge of 3 plus one more on each side");
        assertEquals(BlockSpec.of("minecraft:oak_fence"), c.get(new LocalPos(2, 1, 7)));
        assertEquals(BlockSpec.of("minecraft:oak_fence"), c.get(new LocalPos(3, 1, 8)));
    }

    @Test
    void aBalconyOnTheInnerSideIsRefused() {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.add(new PlanNode("b", "micra:balcony", "s", new Anchor.OnSurface("wall-n", Side.INNER, 2, 0), Map.<String, ParamValue>of(), Set.of(), ""));
        assertEquals(List.of("E-ANCHOR"), codes(compile(nodes)));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.gen.StairwayPartsTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

```java
// StairsGen.java
final class StairsGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s("dir"));
        Facing right = Dirs.right(dir);
        BlockSpec step = BlockForms.stairs(ctx.palette().stairs(p.s("material"), node), dir, false);
        for (int s = 0; s < p.i("steps"); s++) {
            for (int j = 0; j < p.i("width"); j++) {
                ctx.emit(node, s * dir.du() + j * right.du(), s, s * dir.dw() + j * right.dw(), step);
            }
        }
    }
}

// LadderGen.java
final class LadderGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        BlockSpec ladder = BlockForms.ladder(Dirs.of(p.s("facing")));
        for (int dv = 0; dv < p.i("height"); dv++) {
            ctx.emit(node, 0, dv, 0, ladder);
        }
    }
}

// RampGen.java
final class RampGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s("dir"));
        Facing right = Dirs.right(dir);
        String slab = ctx.palette().slab(p.s("material"), node);
        for (int i = 0; i < p.i("length"); i++) {
            BlockSpec block = BlockForms.slab(slab, i % 2 == 1);
            int dv = i / 2;
            for (int j = 0; j < p.i("width"); j++) {
                ctx.emit(node, i * dir.du() + j * right.du(), dv, i * dir.dw() + j * right.dw(), block);
            }
        }
    }
}

// CatwalkGen.java
final class CatwalkGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s("dir"));
        Facing right = Dirs.right(dir);
        BlockSpec floor = BlockForms.flat(ctx.palette().full(p.s("material"), node));
        BlockSpec fence = BlockForms.plain(ctx.palette().full("fence", node));
        int width = p.i("width");
        for (int i = 0; i < p.i("length"); i++) {
            for (int j = 0; j < width; j++) {
                int du = i * dir.du() + j * right.du();
                int dw = i * dir.dw() + j * right.dw();
                ctx.emit(node, du, 0, dw, floor);
                if (p.b("rail") && (j == 0 || j == width - 1)) {
                    ctx.emit(node, du, 1, dw, fence);
                }
            }
        }
    }
}

// RailingGen.java
final class RailingGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s("dir"));
        BlockSpec fence = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        for (int i = 0; i < p.i("length"); i++) {
            for (int dv = 0; dv < p.i("height"); dv++) {
                ctx.emit(node, i * dir.du(), dv, i * dir.dw(), fence);
            }
        }
    }
}

// BalconyGen.java
final class BalconyGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node);
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        if (a.side() != Side.OUTER) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, "anchor", "バルコニーは、壁の外側(outer)にだけ付けられます");
        }
        int width = p.i("width");
        int depth = p.i("depth");
        int floorRow = a.v() - 1;
        BlockSpec floor = BlockForms.plain(ctx.palette().full(p.s("material"), node));
        BlockSpec fence = BlockForms.plain(ctx.palette().full("fence", node));
        for (int i = a.u(); i < a.u() + width; i++) {
            for (int k = 1; k <= depth; k++) {
                ctx.emitAbs(node, wall.cell(i, -k, floorRow), floor);
            }
        }
        if (p.b("rail")) {
            for (int i = a.u(); i < a.u() + width; i++) {
                for (int k = 1; k <= depth; k++) {
                    boolean edge = k == depth || i == a.u() || i == a.u() + width - 1;
                    if (edge) {
                        ctx.emitAbs(node, wall.cell(i, -k, floorRow + 1), fence);
                    }
                }
            }
        }
    }
}
```
(各ファイルに`package`・`import`を付ける。)`PartGenerators.ENTRIES`に足す:
```java
            Map.entry("micra:stairs", new Entry(new StairsGen(), Stage.BASE)),
            Map.entry("micra:ladder", new Entry(new LadderGen(), Stage.BASE)),
            Map.entry("micra:ramp", new Entry(new RampGen(), Stage.BASE)),
            Map.entry("micra:catwalk", new Entry(new CatwalkGen(), Stage.BASE)),
            Map.entry("micra:railing", new Entry(new RailingGen(), Stage.BASE)),
            Map.entry("micra:balcony", new Entry(new BalconyGen(), Stage.BASE)),
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 階段・はしご・斜路・歩廊・手すり・バルコニーの生成器を追加(自然言語→工場建設 P3 Task 14)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: 装飾の生成器(`lamp`・`sign`・`planter`・`trim`)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/gen/{LampGen,SignGen,PlanterGen,TrimGen}.java`
- Modify: `PartGenerators.java`(4件を`Stage.BASE`で足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/gen/DecorPartsTest.java`

**規則(設計図05 1.1.1):** `lamp`: `lantern`=`lantern(false)`、`hanging`=`lantern(true)`、`torch`=`minecraft:torch`(状態なし)、`post`=役割`fence`の柱を`dv=0..height−1`に置き、その上(`dv=height`)に`lantern(false)`。`sign`(`OnSurface`): `text`を`|`で分け、5行以上・16字以上の行は`E-PARAM-RANGE`(`key=text`)。`OUTER`は`wall.cell(u, −1, v)`、向きは`outward`。`INNER`は`wall.cell(u, thickness, v)`、向きは`outward.opposite()`。ブロックは`wallSign(palette.full(material), 向き)`、`blockEntityConfig`は`line1`〜`line4`(空でない行だけ)。`planter`(`OUTER`のみ): `i=u..u+width−1`に、`wall.cell(i, −1, v)`へ役割`planter`、その1つ上(`row v+1`)へ役割`plant`。`trim`: `horizontal`は`i=u..u+length−1`を`wall.cell(i, layer, v)`に、`vertical`は`row=v..v+length−1`を`wall.cell(u, layer, row)`に(`OUTER`なら`layer=−1`、`INNER`なら`layer=thickness`)。`shape=slab`なら下付きのスラブ(`palette.slab`)、`block`なら`palette.full`。

- [ ] **Step 1: 失敗するテストを書く**

`DecorPartsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecorPartsTest {
    private static PlanNode onNorthWall(String id, String type, Side side, int u, int v, Map<String, ParamValue> p) {
        return new PlanNode(id, type, "s", new Anchor.OnSurface("wall-n", side, u, v), p, Set.of(), "");
    }

    private static CompileResult withWalls(PlanNode decor) {
        List<PlanNode> nodes = new ArrayList<>(shell(7, 7, 1, 4));
        nodes.add(decor);
        return compile(nodes);
    }

    @Test
    void lampsInTheFourKinds() {
        Map<LocalPos, BlockSpec> lantern = cells(compile(List.of(node("l", "micra:lamp", null, 0, 0, 0, Map.of()))).manifest());
        assertEquals(BlockSpec.of("minecraft:lantern", "hanging", "false"), lantern.get(new LocalPos(0, 0, 0)));
        Map<LocalPos, BlockSpec> hanging = cells(compile(List.of(node("l", "micra:lamp", null, 0, 0, 0, params("kind", "hanging")))).manifest());
        assertEquals("true", hanging.get(new LocalPos(0, 0, 0)).get("hanging"));
        Map<LocalPos, BlockSpec> torch = cells(compile(List.of(node("l", "micra:lamp", null, 0, 0, 0, params("kind", "torch")))).manifest());
        assertEquals(BlockSpec.of("minecraft:torch"), torch.get(new LocalPos(0, 0, 0)));
        Map<LocalPos, BlockSpec> post = cells(compile(List.of(node("l", "micra:lamp", null, 0, 0, 0, params("kind", "post", "height", 2)))).manifest());
        assertEquals(3, post.size());
        assertEquals(BlockSpec.of("minecraft:oak_fence"), post.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of("minecraft:lantern", "hanging", "false"), post.get(new LocalPos(0, 2, 0)));
    }

    @Test
    void aSignCarriesItsTextLinesAsBlockEntityConfig() {
        CompileResult r = withWalls(onNorthWall("sg", "micra:sign", Side.OUTER, 3, 1, params("text", "SHOP|OPEN 9-5")));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Placement sign = r.manifest().placements().stream().filter(p -> p.partNodeId().equals("sg")).findFirst().orElseThrow();
        // north wall is at w=6 facing north; the sign is one cell out (w=7) at row 1 (v=2)
        assertEquals(BlockSpec.of("minecraft:oak_wall_sign", "facing", "north"), sign.block());
        assertEquals(Map.of("line1", "SHOP", "line2", "OPEN 9-5"), sign.blockEntityConfig());
        assertEquals(new io.github.khayashi4337.micradrone.build.model.IntPos(103, 66, 193),
                sign.pos(), "local (3,2,7) on a NORTH frame at (100,64,200)");
    }

    @Test
    void anInnerSignFacesIndoors() {
        CompileResult r = withWalls(onNorthWall("sg", "micra:sign", Side.INNER, 3, 1, params("text", "EXIT")));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals(BlockSpec.of("minecraft:oak_wall_sign", "facing", "south"), c.get(new LocalPos(3, 2, 5)));
    }

    @Test
    void signTextLimitsAreParamRangeIssues() {
        assertEquals(List.of("E-PARAM-RANGE"), codes(withWalls(onNorthWall("sg", "micra:sign", Side.OUTER, 3, 1, params("text", "a|b|c|d|e")))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(withWalls(onNorthWall("sg", "micra:sign", Side.OUTER, 3, 1, params("text", "0123456789abcdef")))));
        assertTrue(withWalls(onNorthWall("sg", "micra:sign", Side.OUTER, 3, 1, params("text", "日本語の看板"))).issues().isEmpty());
    }

    @Test
    void aPlanterIsSoilWithFlowersOnTop() {
        CompileResult r = withWalls(onNorthWall("pl", "micra:planter", Side.OUTER, 1, 0, params("width", 3)));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals(3, countOf(c, "minecraft:dirt"));
        assertEquals(3, countOf(c, "minecraft:poppy"));
        assertEquals(BlockSpec.of("minecraft:dirt"), c.get(new LocalPos(1, 1, 7)));
        assertEquals(BlockSpec.of("minecraft:poppy"), c.get(new LocalPos(3, 2, 7)));
        assertEquals(List.of("E-ANCHOR"), codes(withWalls(onNorthWall("pl", "micra:planter", Side.INNER, 1, 0, Map.of()))));
    }

    @Test
    void aTrimCourseRunsAlongOrUpTheWall() {
        CompileResult h = withWalls(onNorthWall("tr", "micra:trim", Side.OUTER, 0, 2, params("length", 4, "shape", "slab")));
        assertTrue(h.issues().isEmpty(), h.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(h.manifest());
        assertEquals(BlockSpec.of("minecraft:stone_brick_slab", "type", "bottom"), c.get(new LocalPos(3, 3, 7)));
        assertEquals(4, countOf(c, "minecraft:stone_brick_slab"));
        CompileResult v = withWalls(onNorthWall("tr", "micra:trim", Side.OUTER, 5, 0, params("length", 2, "axis", "vertical")));
        Map<LocalPos, BlockSpec> vc = cells(v.manifest());
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), vc.get(new LocalPos(5, 2, 7)));
        assertEquals(BlockSpec.of("minecraft:stone_bricks"), vc.get(new LocalPos(5, 1, 7)));
    }
}
```
(`countOf(c, "minecraft:stone_brick_slab")`は、壁のブロック`minecraft:stone_bricks`と名前が違うので数えられる。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.gen.DecorPartsTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

```java
// LampGen.java
final class LampGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        switch (p.s("kind")) {
            case "lantern" -> ctx.emit(node, 0, 0, 0, BlockForms.lantern(false));
            case "hanging" -> ctx.emit(node, 0, 0, 0, BlockForms.lantern(true));
            case "torch" -> ctx.emit(node, 0, 0, 0, BlockForms.plain("minecraft:torch"));
            case "post" -> {
                BlockSpec fence = BlockForms.plain(ctx.palette().full("fence", node));
                for (int dv = 0; dv < p.i("height"); dv++) {
                    ctx.emit(node, 0, dv, 0, fence);
                }
                ctx.emit(node, 0, p.i("height"), 0, BlockForms.lantern(false));
            }
            default -> throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "kind", "照明の種類が不明です");
        }
    }
}

// SignGen.java
final class SignGen implements PartGenerator {
    private static final int MAX_LINES = 4;
    private static final int MAX_LINE_CHARS = 15;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node);
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        String[] lines = p.s("text").split("\\|", -1);
        if (lines.length > MAX_LINES) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "text", "看板は" + MAX_LINES + "行までです(" + lines.length + "行あります)");
        }
        Map<String, String> config = new TreeMap<>();
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].codePointCount(0, lines[k].length()) > MAX_LINE_CHARS) {
                throw ctx.fail(node, IssueCode.E_PARAM_RANGE, "text", (k + 1) + "行目が" + MAX_LINE_CHARS + "字を超えています");
            }
            if (!lines[k].isEmpty()) {
                config.put("line" + (k + 1), lines[k]);
            }
        }
        boolean outer = a.side() == Side.OUTER;
        Facing facing = outer ? wall.outward() : wall.outward().opposite();
        int layer = outer ? -1 : wall.thickness();
        String id = ctx.palette().full(p.s("material"), node);
        ctx.emitAbs(node, wall.cell(a.u(), layer, a.v()), BlockForms.wallSign(id, facing), null, config);
    }
}

// PlanterGen.java
final class PlanterGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node);
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        if (a.side() != Side.OUTER) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, "anchor", "植栽は、壁の外側(outer)にだけ付けられます");
        }
        BlockSpec soil = BlockForms.plain(ctx.palette().full("planter", node));
        BlockSpec plant = BlockForms.plain(ctx.palette().full("plant", node));
        for (int i = a.u(); i < a.u() + p.i("width"); i++) {
            ctx.emitAbs(node, wall.cell(i, -1, a.v()), soil);
            ctx.emitAbs(node, wall.cell(i, -1, a.v() + 1), plant);
        }
    }
}

// TrimGen.java
final class TrimGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node);
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        int layer = a.side() == Side.OUTER ? -1 : wall.thickness();
        BlockSpec block = p.s("shape").equals("slab") ? BlockForms.slab(ctx.palette().slab(p.s("material"), node), false)
                : BlockForms.plain(ctx.palette().full(p.s("material"), node));
        for (int k = 0; k < p.i("length"); k++) {
            boolean horizontal = p.s("axis").equals("horizontal");
            ctx.emitAbs(node, wall.cell(horizontal ? a.u() + k : a.u(), layer, horizontal ? a.v() : a.v() + k), block);
        }
    }
}
```
(各ファイルに`package`・`import`を付ける。)`PartGenerators.ENTRIES`に足す:
```java
            Map.entry("micra:lamp", new Entry(new LampGen(), Stage.BASE)),
            Map.entry("micra:sign", new Entry(new SignGen(), Stage.BASE)),
            Map.entry("micra:planter", new Entry(new PlanterGen(), Stage.BASE)),
            Map.entry("micra:trim", new Entry(new TrimGen(), Stage.BASE)),
```
これで22部品すべてに生成器が揃う(`structure`・`foundation`・`floor`・`wall`=Task 10、`pillar`・`beam`・`chimney`・`road`・`dock_pad`=Task 11、`door`・`window`=Task 12、`roof`=Task 13、`stairs`・`ladder`・`ramp`・`catwalk`・`railing`・`balcony`=Task 14、`lamp`・`sign`・`planter`・`trim`=Task 15。合計4+5+2+1+6+4=22)。

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: 装飾(lamp・sign・planter・trim)の生成器を追加。建築部品22種すべてに生成器が揃う(自然言語→工場建設 P3 Task 15)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: 小屋の金のファイル、1,000回の決定性、回転不変(全22種)、登録簿と生成器の一致

**Files:**
- Create: `src/test/resources/build/golden/hut.patch.json`(手書きの`PlanPatch`)、`src/test/resources/build/golden/hut.manifest.txt`(期待する施工リスト。下の手順で作る)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/{GoldenHutTest,DeterminismTest,RotationInvarianceTest,GeneratorRegistryTest}.java`、`src/test/java/io/github/khayashi4337/micradrone/build/compile/RandomParts.java`(性質テスト用の部品の生成)

**Interfaces:**
- Consumes: Task 5〜15のすべて
- Produces: テストだけ(本番コードは無い。ここまでの実装の総合検査)

**小屋(手で導いた数):** 7×7、1階、階高4。基礎(余白0・深さ1)49、床49、壁4面(unique 72)から、扉(単)2・窓(pane)2枚×2=4を掘って66、扉2、ガラス4、屋根(gable、`overhang=0`)67、ランタン1。**合計238**。BOM: `cobblestone`49、`glass_pane`4、`lantern`1、`oak_door`1、`oak_planks`56、`oak_stairs`42、`stone_bricks`84。

- [ ] **Step 1: 手書きの`PlanPatch`(小屋)を置く**

`src/test/resources/build/golden/hut.patch.json`:
```json
{
  "patchId": "hut-1",
  "baseRevision": 0,
  "stageId": "hand",
  "ops": [
    {"op": "set_site", "site": {"dimension": "minecraft:overworld", "origin": [100, 64, 200], "facing": "north",
      "bounds": [-5, -5, -5, 15, 12, 15], "terrainDigest": "", "claimId": ""}},
    {"op": "set_style", "style": {"palette": {"roof": "minecraft:oak_planks"}, "moodTags": ["hut"]}},
    {"op": "add_node", "node": {"id": "hut", "type": "micra:structure", "parent": null,
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}},
      "params": {"width": 7, "depth": 7, "floors": 1, "floor_height": 4}, "tags": [], "label": "小屋"}},
    {"op": "add_node", "node": {"id": "found", "type": "micra:foundation", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "floor-0", "type": "micra:floor", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "wall-n", "type": "micra:wall", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {"side": "north"}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "wall-e", "type": "micra:wall", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {"side": "east"}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "wall-s", "type": "micra:wall", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {"side": "south"}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "wall-w", "type": "micra:wall", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {"side": "west"}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "door-1", "type": "micra:door", "parent": "hut",
      "anchor": {"kind": "surface", "node": "wall-s", "side": "outer", "u": 3, "v": 0}, "params": {}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "win-e", "type": "micra:window", "parent": "hut",
      "anchor": {"kind": "surface", "node": "wall-e", "side": "outer", "u": 3, "v": 1}, "params": {}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "win-w", "type": "micra:window", "parent": "hut",
      "anchor": {"kind": "surface", "node": "wall-w", "side": "outer", "u": 3, "v": 1}, "params": {}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "roof-1", "type": "micra:roof", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [0, 0, 0], "rot": {"turns": 0, "mirror": false}}, "params": {"overhang": 0}, "tags": [], "label": ""}},
    {"op": "add_node", "node": {"id": "lamp-1", "type": "micra:lamp", "parent": "hut",
      "anchor": {"kind": "absolute", "pos": [3, 1, 3], "rot": {"turns": 0, "mirror": false}}, "params": {}, "tags": [], "label": ""}}
  ]
}
```

- [ ] **Step 2: 失敗するテストを書く**

`GoldenHutTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenHutTest {
    static String resource(String name) throws IOException {
        try (InputStream in = GoldenHutTest.class.getResourceAsStream("/build/golden/" + name)) {
            assertNotNull(in, "missing golden resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static SemanticPlan hut() throws IOException {
        PatchResult r = new PlanPatcher(CompileFixtures.REGISTRY, TemplateBundle.EMPTY)
                .apply(SemanticPlan.empty("hut"), PlanJson.patchFromTree(MiniJson.parse(resource("hut.patch.json"))));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    /** One line per placement: index x y z block verify phase (properties are part of the block text). */
    static List<String> lines(PlacementManifest m) {
        List<String> out = new ArrayList<>();
        for (Placement p : m.placements()) {
            out.add(p.index() + " " + p.pos().x() + " " + p.pos().y() + " " + p.pos().z() + " " + p.block() + " "
                    + p.verify() + " " + p.phase() + (p.blockEntityConfig().isEmpty() ? "" : " " + p.blockEntityConfig()));
        }
        return out;
    }

    @Test
    void theHandWrittenPatchBuildsTheHutWithTheDerivedNumbers() throws IOException {
        CompileResult r = CompileFixtures.compile(hut());
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        PlacementManifest m = r.manifest();
        assertEquals(238, m.placements().size());
        assertEquals(Map.of("minecraft:cobblestone", 49, "minecraft:glass_pane", 4, "minecraft:lantern", 1, "minecraft:oak_door", 1,
                "minecraft:oak_planks", 56, "minecraft:oak_stairs", 42, "minecraft:stone_bricks", 84), m.bom());
        assertEquals(List.of("STRUCTURE", "ENVELOPE", "DECORATION"),
                m.phases().stream().map(x -> x.phase().name()).toList());
    }

    @Test
    void theManifestMatchesTheGoldenFile() throws IOException {
        PlacementManifest m = CompileFixtures.compile(hut()).manifest();
        String expected = resource("hut.manifest.txt").replace("\r\n", "\n").stripTrailing();
        assertEquals(expected, "hash " + m.hash() + "\n" + String.join("\n", lines(m)));
    }
}
```
`DeterminismTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterminismTest {
    @Test
    void theSameInputGivesTheSameHashOneThousandTimes() throws IOException {
        var plan = GoldenHutTest.hut();
        String first = CompileFixtures.compile(plan).manifest().hash();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(CompileFixtures.compile(plan).manifest().hash());
        }
        assertEquals(Set.of(first), seen);
    }
}
```
`RandomParts.java`(テスト用。乱数の種を固定して、単独で立つ部品を、互いに離して並べる):
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Random valid instances of the freestanding building parts, spread far apart so they never overlap. */
public final class RandomParts {
    public static final List<String> FREESTANDING = List.of("micra:pillar", "micra:beam", "micra:chimney", "micra:stairs", "micra:ladder",
            "micra:catwalk", "micra:railing", "micra:ramp", "micra:lamp", "micra:road", "micra:dock_pad");
    public static final int SPACING = 400;

    private RandomParts() {
    }

    static Map<String, ParamValue> randomParams(PartType type, Random rnd) {
        Map<String, ParamValue> out = new TreeMap<>();
        for (ParamSpec p : type.params()) {
            switch (p.type()) {
                case INT -> {
                    int min = ((ParamValue.IntV) p.min()).value();
                    int max = Math.min(((ParamValue.IntV) p.max()).value(), min + 12);
                    out.put(p.name(), new ParamValue.IntV(min + rnd.nextInt(max - min + 1)));
                }
                case BOOL -> out.put(p.name(), new ParamValue.BoolV(rnd.nextBoolean()));
                case ENUM -> out.put(p.name(), new ParamValue.StrV(p.enumValues().get(rnd.nextInt(p.enumValues().size()))));
                default -> {
                    // materials keep their default role; texts and lists are not used by the freestanding parts
                }
            }
        }
        // a dock pad's cargo position must lie on the pad
        if (type.id().equals("micra:dock_pad")) {
            out.put("cargo_u", new ParamValue.IntV(0));
            out.put("cargo_w", new ParamValue.IntV(0));
        }
        return out;
    }

    public static List<PlanNode> nodes(long seed) {
        Random rnd = new Random(seed);
        List<PlanNode> nodes = new ArrayList<>();
        int k = 0;
        for (String id : FREESTANDING) {
            PartType type = CompileFixtures.REGISTRY.get(id);
            Rot rot = new Rot(rnd.nextInt(4), rnd.nextBoolean());
            nodes.add(new PlanNode("p-" + k, id, null, new Anchor.Absolute(new LocalPos(k * SPACING, 0, 0), rot),
                    randomParams(type, rnd), Set.of(), ""));
            k++;
        }
        return nodes;
    }
}
```
`RotationInvarianceTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RotationInvarianceTest {
    private static final IntPos ORIGIN = new IntPos(1000, 64, -2000);
    private static final Box WIDE = new Box(-50, -50, -50, 6000, 200, 50);

    private static PlacementManifest compileFacing(List<PlanNode> nodes, Facing facing, Box bounds) {
        Site site = new Site("minecraft:overworld", new BuildFrame(ORIGIN, facing), bounds, "", "");
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(site, StyleSpec.EMPTY, nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return r.manifest();
    }

    /** Clockwise turn of a world offset seen from above: (dx, dz) becomes (-dz, dx). */
    private static IntPos turn(IntPos p, int q) {
        int dx = p.x() - ORIGIN.x();
        int dz = p.z() - ORIGIN.z();
        for (int i = 0; i < q; i++) {
            int nx = -dz;
            int nz = dx;
            dx = nx;
            dz = nz;
        }
        return new IntPos(ORIGIN.x() + dx, p.y(), ORIGIN.z() + dz);
    }

    private static void assertRotationOf(PlacementManifest north, PlacementManifest turned, int q, String label) {
        assertEquals(north.placements().size(), turned.placements().size(), label);
        Map<IntPos, Placement> byPos = new HashMap<>();
        for (Placement p : turned.placements()) {
            byPos.put(p.pos(), p);
        }
        for (Placement p : north.placements()) {
            Placement other = byPos.get(turn(p.pos(), q));
            assertTrue(other != null, label + ": no cell at the turned position of " + p.pos());
            assertEquals(BlockRotation.rotate(p.block(), q), other.block(), label + " at " + p.pos());
            assertEquals(p.blockEntityConfig(), other.blockEntityConfig(), label);
            assertEquals(p.verify(), other.verify(), label);
            assertEquals(p.phase(), other.phase(), label);
        }
    }

    @Test
    void theHutTurnedInFourWaysIsTheSameHutTurned() throws IOException {
        SemanticPlan hut = GoldenHutTest.hut();
        PlacementManifest north = CompileFixtures.compile(planAt(hut, Facing.NORTH)).manifest();
        for (int q = 1; q < 4; q++) {
            Facing f = Facing.values()[q];
            assertRotationOf(north, CompileFixtures.compile(planAt(hut, f)).manifest(), q, "hut " + f);
        }
    }

    private static SemanticPlan planAt(SemanticPlan hut, Facing facing) {
        Site s = hut.site();
        return new SemanticPlan(hut.schemaVersion(), hut.planId(), hut.revision(), hut.parentRevision(),
                new Site(s.dimension(), new BuildFrame(s.frame().origin(), facing), s.localBounds(), "", ""), hut.style(),
                hut.nodes(), hut.connections(), hut.logistics(), hut.provenance());
    }

    @Test
    void everyFreestandingPartWithRandomParametersAndRotationsTurnsCleanly() {
        for (long seed = 1; seed <= 20; seed++) {
            List<PlanNode> nodes = RandomParts.nodes(seed);
            PlacementManifest north = compileFacing(nodes, Facing.NORTH, WIDE);
            assertTrue(north.placements().size() > 0);
            for (int q = 1; q < 4; q++) {
                assertRotationOf(north, compileFacing(nodes, Facing.values()[q], WIDE), q, "seed " + seed + " facing " + Facing.values()[q]);
            }
        }
    }
}
```
(`WIDE`の`Box`は、u∈[−50, 6000]、v、w∈[−50, 50]。回転後の範囲は世界へ写して検査するのではなく、局所の範囲なので回転しても同じ。)
`GeneratorRegistryTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class GeneratorRegistryTest {
    @Test
    void everyBuildingPartHasAGeneratorAndNothingElseDoes() {
        Set<String> parts = new TreeSet<>();
        for (PartType t : BuildingParts.registry().userParts()) {
            parts.add(t.id());
        }
        assertEquals(parts, new TreeSet<>(PartGenerators.ids()));
        assertEquals(22, parts.size());
    }

    @Test
    void rotationUnsupportedPartsAreRealParts() {
        for (String id : BuildingParts.ROTATION_UNSUPPORTED) {
            assertTrue(BuildingParts.registry().contains(id), id);
        }
    }
}
```

- [ ] **Step 3: 金のファイルを作り、導出した数と食い違わないことを確かめる**

まず、`GoldenHutTest.theHandWrittenPatchBuildsTheHutWithTheDerivedNumbers`(238個・BOM)と、`DeterminismTest`・`RotationInvarianceTest`・`GeneratorRegistryTest`を走らせる。**238個とBOMが手で導いた値と一致しなければ、実装を直す(金のファイルを先に作らない)。** 一致したら、一度だけ次の手順で`hut.manifest.txt`を作る:

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.GoldenHutTest" --console=plain -i`
`theManifestMatchesTheGoldenFile`が、資源が無くて失敗する。失敗の出力の「actual」(`hash …`の行と、238行)を、そのまま`src/test/resources/build/golden/hut.manifest.txt`に保存する。保存した内容を、次の点検で読んで確かめてから、コミットする:
- 先頭の数行が`… minecraft:cobblestone EXACT STRUCTURE`(v=63、基礎)である。
- 扉の2行が`minecraft:oak_door[facing=north,half=lower,hinge=left]`と`[…half=upper…]`である(`facing`は室内向き)。
- 屋根の階段が`minecraft:oak_stairs[facing=east,half=bottom]`など。
- ランタンが`minecraft:lantern[hanging=false] STATE_SUBSET DECORATION`。

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS(238個、BOM、金のファイル、1,000回のハッシュ、全22種を含む回転不変)。

- [ ] **Step 5: コミット**

```bash
git add src/test/resources/build/golden src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
test: 小屋の金のファイル・1,000回の決定性・全部品の回転不変・登録簿と生成器の一致を追加(自然言語→工場建設 P3 Task 16)

小屋の総数(238)とBOMは、規則から手で導いた値と一致することを確かめてから、金のファイルを作った。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: `ManifestDiff`(撤去・追加・変更)と`Conflict`の判定

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/compile/{RemovalEntry,PlacementChange,ManifestDiff,ManifestDiffer,ConflictKind,ObservedBlock,Conflict,Conflicts}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/compile/{ManifestDifferTest,ConflictsTest}.java`

**Interfaces:**
- Consumes: Task 10の`PlacementManifest`・`Placement`、Task 2の`BlockSpec`・`IntPos`
- Produces:
  - `record RemovalEntry(Placement old, BlockSpec expectedNow, BlockSpec restoreTo)`、`record PlacementChange(Placement old, Placement now, BlockSpec expectedNow)`
  - `record ManifestDiff(String fromHash, String toHash, List<RemovalEntry> removals, List<Placement> additions, List<PlacementChange> changes, int unchanged)`
  - `ManifestDiffer.diff(PlacementManifest from, PlacementManifest to, Function<IntPos,BlockSpec> restoreLookup)`(`restoreLookup`は、撤去した位置に戻すブロック。元のジョブの記録に由来し、無ければ`BlockSpec.AIR`。ディメンションが違えば`IllegalArgumentException`)。撤去は上(y)から下、同じ高さはz→xの順。追加と変更は、新しい施工リストの`index`順。同じ位置で、ブロックとブロックエンティティの設定が同じなら`unchanged`
  - `enum ConflictKind {PLAYER_MODIFIED, MISSING}`、`record ObservedBlock(BlockSpec block)`、`record Conflict(IntPos pos, BlockSpec expected, ObservedBlock observed, ConflictKind kind)`
  - `Conflicts.detect(IntPos pos, BlockSpec expected, ObservedBlock observed, Set<String> volatileProps) → Optional<Conflict>`: 期待が空気でなく観測が空気なら`MISSING`。ブロックIDが違えば`PLAYER_MODIFIED`。期待のプロパティのうち、`volatileProps`を除いた物が、観測と違えば`PLAYER_MODIFIED`。それ以外は空

- [ ] **Step 1: 失敗するテストを書く**

`ManifestDifferTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDifferTest {
    private static Placement at(int index, int x, int y, int z, BlockSpec block) {
        return new Placement(index, new IntPos(x, y, z), block, Map.of(), "n", BuildPhase.STRUCTURE, PlacerId.SIMPLE,
                VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
    }

    private static PlacementManifest manifest(String dimension, String hash, Placement... ps) {
        List<Placement> list = new ArrayList<>(List.of(ps));
        return new PlacementManifest(1, "p", 1, "v", dimension, new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH),
                new Box(0, 0, 0, 10, 10, 10), list, List.of(), Map.of(), List.of(), hash);
    }

    private static final BlockSpec STONE = BlockSpec.of("minecraft:stone");
    private static final BlockSpec BRICKS = BlockSpec.of("minecraft:bricks");

    @Test
    void removalsAdditionsChangesAndUnchangedAreSeparated() {
        PlacementManifest from = manifest("minecraft:overworld", "h1",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, STONE), at(3, 0, 5, 0, STONE));
        PlacementManifest to = manifest("minecraft:overworld", "h2",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, BRICKS), at(2, 9, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, pos -> BlockSpec.AIR);
        assertEquals("h1", d.fromHash());
        assertEquals("h2", d.toHash());
        assertEquals(1, d.unchanged());
        assertEquals(List.of(new IntPos(0, 5, 0), new IntPos(2, 0, 0)), d.removals().stream().map(r -> r.old().pos()).toList(),
                "removals go from the top down");
        assertEquals(List.of(new IntPos(9, 0, 0)), d.additions().stream().map(Placement::pos).toList());
        assertEquals(1, d.changes().size());
        assertEquals(BRICKS, d.changes().get(0).now().block());
        assertEquals(STONE, d.changes().get(0).expectedNow(), "what the old manifest expects to find now");
    }

    @Test
    void aRemovalCarriesWhatIsExpectedNowAndWhatToRestore() {
        PlacementManifest from = manifest("minecraft:overworld", "h1", at(0, 4, 4, 4, STONE));
        PlacementManifest to = manifest("minecraft:overworld", "h2");
        BlockSpec dirt = BlockSpec.of("minecraft:dirt");
        ManifestDiff withRecord = ManifestDiffer.diff(from, to, pos -> pos.equals(new IntPos(4, 4, 4)) ? dirt : BlockSpec.AIR);
        assertEquals(STONE, withRecord.removals().get(0).expectedNow());
        assertEquals(dirt, withRecord.removals().get(0).restoreTo());
        ManifestDiff noRecord = ManifestDiffer.diff(from, to, pos -> null);
        assertEquals(BlockSpec.AIR, noRecord.removals().get(0).restoreTo(), "no record means air");
    }

    @Test
    void removalOrderIsTopDownThenZThenX() {
        PlacementManifest from = manifest("minecraft:overworld", "h1", at(0, 5, 1, 1, STONE), at(1, 1, 1, 2, STONE),
                at(2, 2, 1, 1, STONE), at(3, 0, 9, 0, STONE));
        List<IntPos> order = ManifestDiffer.diff(from, manifest("minecraft:overworld", "h2"), p -> BlockSpec.AIR).removals().stream()
                .map(r -> r.old().pos()).toList();
        assertEquals(List.of(new IntPos(0, 9, 0), new IntPos(2, 1, 1), new IntPos(5, 1, 1), new IntPos(1, 1, 2)), order);
    }

    @Test
    void blockEntityConfigCountsAsAChange() {
        Placement plain = at(0, 0, 0, 0, STONE);
        Placement configured = new Placement(0, new IntPos(0, 0, 0), STONE, Map.of("line1", "x"), "n", BuildPhase.STRUCTURE,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
        ManifestDiff d = ManifestDiffer.diff(manifest("minecraft:overworld", "h1", plain), manifest("minecraft:overworld", "h2", configured), p -> BlockSpec.AIR);
        assertEquals(1, d.changes().size());
    }

    @Test
    void differentDimensionsCannotBeDiffed() {
        assertThrows(IllegalArgumentException.class, () -> ManifestDiffer.diff(manifest("minecraft:overworld", "h1"),
                manifest("minecraft:the_nether", "h2"), p -> BlockSpec.AIR));
    }

    @Test
    void identicalManifestsHaveNothingToDo() {
        PlacementManifest m = manifest("minecraft:overworld", "h", at(0, 0, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(m, m, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty() && d.changes().isEmpty());
        assertEquals(1, d.unchanged());
    }
}
```
`ConflictsTest.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConflictsTest {
    private static final IntPos POS = new IntPos(1, 2, 3);

    @Test
    void whatWeExpectIsNotAConflict() {
        BlockSpec expected = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected), Set.of()).isEmpty());
        // the world has extra states we never listed (shape): a subset comparison passes
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected.with("shape", "straight")), Set.of()).isEmpty());
    }

    @Test
    void aDifferentBlockMeansThePlayerChangedIt() {
        Optional<Conflict> c = Conflicts.detect(POS, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.of("minecraft:dirt")), Set.of());
        assertEquals(ConflictKind.PLAYER_MODIFIED, c.orElseThrow().kind());
        assertEquals(POS, c.get().pos());
    }

    @Test
    void airWhereABlockShouldBeIsMissing() {
        Optional<Conflict> c = Conflicts.detect(POS, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.AIR), Set.of());
        assertEquals(ConflictKind.MISSING, c.orElseThrow().kind());
    }

    @Test
    void aChangedListedStateIsAModification() {
        BlockSpec expected = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected,
                new ObservedBlock(BlockSpec.of("minecraft:oak_stairs", "facing", "south")), Set.of()).orElseThrow().kind());
    }

    @Test
    void volatileStatesAreIgnoredSoRunningMachinesAreNotConflicts() {
        BlockSpec expected = BlockSpec.of("create:blaze_burner", "blaze", "smouldering");
        ObservedBlock running = new ObservedBlock(BlockSpec.of("create:blaze_burner", "blaze", "kindled"));
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected, running, Set.of()).orElseThrow().kind());
        assertTrue(Conflicts.detect(POS, expected, running, Set.of("blaze")).isEmpty());
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "facing", "north", "open", "false");
        assertTrue(Conflicts.detect(POS, door, new ObservedBlock(door.with("open", "true")), Set.of("open", "powered")).isEmpty());
    }

    @Test
    void anExpectedAirIsNeverAConflictOfMissing() {
        assertTrue(Conflicts.detect(POS, BlockSpec.AIR, new ObservedBlock(BlockSpec.AIR), Set.of()).isEmpty());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.compile.ManifestDifferTest" --tests "io.github.khayashi4337.micradrone.build.compile.ConflictsTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

```java
// RemovalEntry.java, PlacementChange.java, ManifestDiff.java, ConflictKind.java, ObservedBlock.java, Conflict.java
public record RemovalEntry(Placement old, BlockSpec expectedNow, BlockSpec restoreTo) {
}

public record PlacementChange(Placement old, Placement now, BlockSpec expectedNow) {
}

public record ManifestDiff(String fromHash, String toHash, List<RemovalEntry> removals, List<Placement> additions,
                           List<PlacementChange> changes, int unchanged) {
    public ManifestDiff {
        removals = List.copyOf(removals);
        additions = List.copyOf(additions);
        changes = List.copyOf(changes);
    }
}

public enum ConflictKind { PLAYER_MODIFIED, MISSING }

/** What the world actually holds at a position. (The chunk-loaded state is the runtime's concern.) */
public record ObservedBlock(BlockSpec block) {
}

public record Conflict(IntPos pos, BlockSpec expected, ObservedBlock observed, ConflictKind kind) {
}
```
`ManifestDiffer.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Compares an old and a new manifest for a MODIFY job. Nothing here reads the world: the runtime checks each
 * position against {@code expectedNow} right before it touches it (and only for positions it placed).
 */
public final class ManifestDiffer {
    private ManifestDiffer() {
    }

    public static ManifestDiff diff(PlacementManifest from, PlacementManifest to, Function<IntPos, BlockSpec> restoreLookup) {
        if (!from.dimension().equals(to.dimension())) {
            throw new IllegalArgumentException("cannot diff manifests of different dimensions: " + from.dimension() + " and " + to.dimension());
        }
        Map<IntPos, Placement> oldByPos = new HashMap<>();
        for (Placement p : from.placements()) {
            oldByPos.put(p.pos(), p);
        }
        Map<IntPos, Placement> newByPos = new HashMap<>();
        for (Placement p : to.placements()) {
            newByPos.put(p.pos(), p);
        }
        List<RemovalEntry> removals = new ArrayList<>();
        for (Placement old : from.placements()) {
            if (!newByPos.containsKey(old.pos())) {
                BlockSpec restore = restoreLookup.apply(old.pos());
                removals.add(new RemovalEntry(old, old.block(), restore == null ? BlockSpec.AIR : restore));
            }
        }
        removals.sort(Comparator.<RemovalEntry>comparingInt(r -> -r.old().pos().y())
                .thenComparingInt(r -> r.old().pos().z()).thenComparingInt(r -> r.old().pos().x()));
        List<Placement> additions = new ArrayList<>();
        List<PlacementChange> changes = new ArrayList<>();
        int unchanged = 0;
        for (Placement now : to.placements()) {
            Placement old = oldByPos.get(now.pos());
            if (old == null) {
                additions.add(now);
            } else if (old.block().equals(now.block()) && old.blockEntityConfig().equals(now.blockEntityConfig())) {
                unchanged++;
            } else {
                changes.add(new PlacementChange(old, now, old.block()));
            }
        }
        return new ManifestDiff(from.hash(), to.hash(), removals, additions, changes, unchanged);
    }
}
```
`Conflicts.java`:
```java
package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Decides whether the world still matches what a manifest expects. Only listed states count, volatile ones never do. */
public final class Conflicts {
    private Conflicts() {
    }

    public static Optional<Conflict> detect(IntPos pos, BlockSpec expected, ObservedBlock observed, Set<String> volatileProps) {
        BlockSpec actual = observed.block();
        if (expected.blockId().equals("minecraft:air")) {
            return Optional.empty();
        }
        if (actual.blockId().equals("minecraft:air")) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.MISSING));
        }
        if (!actual.blockId().equals(expected.blockId())) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
        }
        for (Map.Entry<String, String> e : expected.properties().entrySet()) {
            if (volatileProps.contains(e.getKey())) {
                continue;
            }
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: ManifestDiff(撤去・追加・変更。expectedNowとrestoreToつき)とConflictの判定(稼働で変わる状態は無視)を追加(自然言語→工場建設 P3 Task 17)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 18: `SchemaGenerator`(登録簿から`--json-schema`用のスキーマを作る)

**S-1の実測(2026-09-26。`docs/investigations/spk_s1_json_schema_limits.md`)に従う:** ルートは`"type":"object"`必須。`oneOf`・`const`・`enum`・`$defs`+`$ref`は使える。安全上限は、コンパクトJSONで、`claude.exe`直接起動が20,000文字、`cmd.exe`経由が5,000文字。`enum`は「個数×(IDの文字数+5)」で数え、`cmd.exe`経由は4,000文字以内。

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/parts/{SchemaLimits,SchemaGenerator,SchemaTooLargeException}.java`
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/model/PlanJson.java`(`params`を「オブジェクト」または「`[{key, value}]`の配列」のどちらでも読む。`rot`の`turns`・`mirror`は省略可(0・偽))
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/parts/{MiniSchemaValidator,SchemaGeneratorTest}.java`

**Interfaces:**
- Consumes: Task 6・7の`PartTypeRegistry`・`ParamSpec`・`BuildingParts`、Task 3の`CanonicalJson`
- Produces:
  - `SchemaLimits`: `CLAUDE_EXE_MAX_SCHEMA_CHARS=20_000`、`CMD_EXE_MAX_SCHEMA_CHARS=5_000`、`CMD_EXE_ENUM_BUDGET_CHARS=4_000`、`ENUM_ENTRY_OVERHEAD=5`、`static boolean enumFitsCmdExe(int count, int idLength)`
  - `SchemaGenerator`: `enum Mode {TYPED, FLAT}`、`record Generated(Mode mode, Map<String,Object> schema, String json)`(`int chars()`)、`static Map<String,Object> patchSchema(PartTypeRegistry, Mode, Set<String> partIdsOrNull)`、`static Generated forLimit(PartTypeRegistry, Set<String> partIdsOrNull, int maxChars)`(型つき(`TYPED`)が入ればそれ、入らなければ`FLAT`、それも入らなければ`SchemaTooLargeException`)
  - スキーマの形: ルートは`{"type":"object","additionalProperties":false,"required":["ops"],"properties":{"ops":{"type":"array","items":{"oneOf":[9種の操作]}}}}`。操作は`op`が`const`。`AddNode`の`type`は、`USER`の部品識別子の`enum`(`IMPLICIT`は出さない)。`TYPED`は、部品ごとの`oneOf`で`params`の型・範囲・`enum`・必須を持つ。`FLAT`は`params`が`[{key,value}]`の配列で、`key`は全部品のパラメータ名の`enum`。共通部分(`anchor`・`pos`・`rot`・`connection`・`site`・`style`・`logistics`)は`$defs`から`$ref`で引く。

- [ ] **Step 1: 失敗するテストを書く**

`MiniSchemaValidator.java`(テスト用の、必要な範囲だけの検証器):
```java
package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A tiny JSON Schema checker for the keywords the generated schemas use; enough to test them without a library. */
final class MiniSchemaValidator {
    private final Map<String, Object> root;

    MiniSchemaValidator(Map<String, Object> root) {
        this.root = root;
    }

    List<String> validate(Object value) {
        List<String> errors = new ArrayList<>();
        check(root, value, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void check(Object schemaObj, Object v, String path, List<String> errs) {
        Map<String, Object> schema = (Map<String, Object>) schemaObj;
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            Map<String, Object> defs = (Map<String, Object>) root.get("$defs");
            check(defs.get(ref.substring("#/$defs/".length())), v, path, errs);
            return;
        }
        if (schema.containsKey("oneOf")) {
            int matches = 0;
            for (Object branch : (List<Object>) schema.get("oneOf")) {
                List<String> e = new ArrayList<>();
                check(branch, v, path, e);
                if (e.isEmpty()) {
                    matches++;
                }
            }
            if (matches != 1) {
                errs.add(path + ": must match exactly one schema in oneOf (matched " + matches + ")");
            }
            return;
        }
        if (schema.containsKey("const") && !same(schema.get("const"), v)) {
            errs.add(path + ": must equal constant " + schema.get("const"));
            return;
        }
        if (schema.containsKey("enum")) {
            boolean found = false;
            for (Object o : (List<Object>) schema.get("enum")) {
                found |= same(o, v);
            }
            if (!found) {
                errs.add(path + ": not one of the allowed values: " + v);
            }
        }
        if (schema.containsKey("type") && !typeOk(schema.get("type"), v)) {
            errs.add(path + ": wrong type for " + v);
            return;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (schema.get("minimum") instanceof Number min && d < min.doubleValue()) {
                errs.add(path + ": must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && d > max.doubleValue()) {
                errs.add(path + ": must be <= " + max);
            }
        }
        if (v instanceof String s) {
            if (schema.get("maxLength") instanceof Number max && s.length() > max.intValue()) {
                errs.add(path + ": too long");
            }
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).find()) {
                errs.add(path + ": does not match pattern " + p);
            }
        }
        if (v instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()) {
                errs.add(path + ": too few items");
            }
            if (schema.get("maxItems") instanceof Number max && list.size() > max.intValue()) {
                errs.add(path + ": too many items");
            }
            if (schema.get("items") != null) {
                for (int i = 0; i < list.size(); i++) {
                    check(schema.get("items"), list.get(i), path + "[" + i + "]", errs);
                }
            }
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            if (schema.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!map.containsKey(r)) {
                        errs.add(path + ": missing required " + r);
                    }
                }
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = (String) e.getKey();
                if (props.containsKey(key)) {
                    check(props.get(key), e.getValue(), path + "." + key, errs);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errs.add(path + ": additional property " + key + " is not allowed");
                } else if (schema.get("additionalProperties") instanceof Map<?, ?>) {
                    check(schema.get("additionalProperties"), e.getValue(), path + "." + key, errs);
                }
            }
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static boolean typeOk(Object type, Object v) {
        if (type instanceof List<?> types) {
            for (Object t : types) {
                if (typeOk(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch ((String) type) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            case "integer" -> v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
            case "number" -> v instanceof Number;
            default -> false;
        };
    }
}
```
`SchemaGeneratorTest.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {
    private static final PartTypeRegistry REGISTRY = BuildingParts.registry();

    private static final String PATCH = "{\"ops\":["
            + "{\"op\":\"set_site\",\"site\":{\"dimension\":\"minecraft:overworld\",\"origin\":[0,64,0],\"facing\":\"north\",\"bounds\":[0,0,0,9,9,9]}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"hut\",\"type\":\"micra:structure\",\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0]},\"params\":{\"width\":7,\"depth\":7}}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"wall-n\",\"type\":\"micra:wall\",\"parent\":\"hut\",\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0],\"rot\":{\"turns\":0,\"mirror\":false}},\"params\":{\"side\":\"north\",\"material\":\"wall\"}}},"
            + "{\"op\":\"add_node\",\"node\":{\"id\":\"door-1\",\"type\":\"micra:door\",\"parent\":\"hut\",\"anchor\":{\"kind\":\"surface\",\"node\":\"wall-n\",\"side\":\"outer\",\"u\":3,\"v\":0},\"params\":{}}},"
            + "{\"op\":\"remove_node\",\"id\":\"door-1\"}]}";

    private static MiniSchemaValidator validator(Map<String, Object> schema) {
        return new MiniSchemaValidator(schema);
    }

    @Test
    void theRootIsAnObjectWithClosedProperties() {
        Map<String, Object> s = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null);
        assertEquals("object", s.get("type"));
        assertEquals(false, s.get("additionalProperties"));
        assertEquals(List.of("ops"), s.get("required"));
        assertTrue(s.containsKey("$defs"));
    }

    @Test
    void aValidPatchPassesBothModes() {
        Object patch = MiniJson.parse(PATCH);
        assertEquals(List.of(), validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null)).validate(patch));
    }

    @Test
    void typedModeRejectsWhatTheSpecForbids() {
        MiniSchemaValidator v = validator(SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.TYPED, null));
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"width\":7", "\"width\":99"))).isEmpty(), "out of range");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("micra:structure", "micra:castle"))).isEmpty(), "unknown part");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"side\":\"north\"", "\"side\":\"up\""))).isEmpty(), "bad enum");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"depth\":7", "\"depth\":7,\"bogus\":1"))).isEmpty(), "extra parameter");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"side\":\"north\",", ""))).isEmpty(), "missing required side");
        assertFalse(v.validate(MiniJson.parse(PATCH.replace("\"op\":\"remove_node\"", "\"op\":\"explode\""))).isEmpty(), "unknown op");
    }

    @Test
    void onlyUserPartsAreOffered() {
        PartTypeRegistry withImplicit = PartTypeRegistry.builder()
                .register(PartType.builder("test:shown", PartCategory.STRUCTURE).displayNameKey("k").build())
                .register(PartType.builder("test:hidden", PartCategory.STRUCTURE).visibility(Visibility.IMPLICIT).build())
                .build();
        String json = SchemaGenerator.forLimit(withImplicit, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS).json();
        assertTrue(json.contains("test:shown"));
        assertFalse(json.contains("test:hidden"));
    }

    @Test
    void aSubsetOfPartsGivesASmallerSchema() {
        SchemaGenerator.Generated all = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        SchemaGenerator.Generated some = SchemaGenerator.forLimit(REGISTRY, Set.of("micra:wall", "micra:roof"), SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertTrue(some.chars() < all.chars());
        assertFalse(some.json().contains("micra:pillar"));
        assertTrue(some.json().contains("micra:wall"));
    }

    @Test
    void theFullBuildingRegistryFitsTheDirectLaunchLimitAndTheFlatFormFitsTheCmdLimit() {
        SchemaGenerator.Generated direct = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertTrue(direct.chars() <= SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS, "typed size " + direct.chars());
        SchemaGenerator.Generated cmd = SchemaGenerator.forLimit(REGISTRY, null, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS);
        assertTrue(cmd.chars() <= SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS, "flat size " + cmd.chars());
        assertEquals(SchemaGenerator.Mode.FLAT, cmd.mode(), "typed is too big for the cmd.exe limit, so it falls back");
        assertThrows(SchemaTooLargeException.class, () -> SchemaGenerator.forLimit(REGISTRY, null, 200));
    }

    @Test
    void theFlatFormTakesParametersAsKeyValuePairs() {
        Map<String, Object> flat = SchemaGenerator.patchSchema(REGISTRY, SchemaGenerator.Mode.FLAT, null);
        MiniSchemaValidator v = validator(flat);
        String flatPatch = PATCH.replace("\"params\":{\"width\":7,\"depth\":7}", "\"params\":[{\"key\":\"width\",\"value\":7},{\"key\":\"depth\",\"value\":7}]")
                .replace("\"params\":{\"side\":\"north\",\"material\":\"wall\"}", "\"params\":[{\"key\":\"side\",\"value\":\"north\"},{\"key\":\"material\",\"value\":\"wall\"}]")
                .replace("\"params\":{}", "\"params\":[]");
        assertEquals(List.of(), v.validate(MiniJson.parse(flatPatch)));
        assertFalse(v.validate(MiniJson.parse(flatPatch.replace("\"key\":\"width\"", "\"key\":\"unknown_param\""))).isEmpty());
    }

    @Test
    void bothFormsReadBackToTheSamePlan() {
        String flatPatch = PATCH.replace("\"params\":{\"width\":7,\"depth\":7}", "\"params\":[{\"key\":\"width\",\"value\":7},{\"key\":\"depth\",\"value\":7}]")
                .replace("\"params\":{\"side\":\"north\",\"material\":\"wall\"}", "\"params\":[{\"key\":\"side\",\"value\":\"north\"},{\"key\":\"material\",\"value\":\"wall\"}]")
                .replace("\"params\":{}", "\"params\":[]");
        PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TemplateBundle.EMPTY);
        PatchResult typed = patcher.apply(SemanticPlan.empty("p"), withMeta(PATCH));
        PatchResult flat = patcher.apply(SemanticPlan.empty("p"), withMeta(flatPatch));
        assertTrue(typed.ok(), typed.issues().toString());
        assertTrue(flat.ok(), flat.issues().toString());
        assertEquals(typed.plan().contentHash(), flat.plan().contentHash());
    }

    private static PlanPatch withMeta(String opsOnly) {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"ai\"," + opsOnly.substring(1);
        return PlanJson.patchFromTree(MiniJson.parse(json));
    }

    @Test
    void enumBudgetForTheCmdExeRoute() {
        assertTrue(SchemaLimits.enumFitsCmdExe(280, 9), "the measured comfortable size: 9-char ids, about 280 of them");
        assertFalse(SchemaLimits.enumFitsCmdExe(300, 9));
        assertTrue(SchemaLimits.enumFitsCmdExe(140, 23));
        assertEquals(20_000, SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS);
        assertEquals(5_000, SchemaLimits.CMD_EXE_MAX_SCHEMA_CHARS);
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.parts.SchemaGeneratorTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

`SchemaLimits.java`・`SchemaTooLargeException.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

/** Size limits measured by spike S-1 (docs/investigations/spk_s1_json_schema_limits.md). */
public final class SchemaLimits {
    /** Compact-JSON schema size that is safe when claude.exe is launched directly (the hard limit is a 32,766-char command line). */
    public static final int CLAUDE_EXE_MAX_SCHEMA_CHARS = 20_000;
    /** The same when going through cmd.exe (the hard limit is an 8,118-char command line). */
    public static final int CMD_EXE_MAX_SCHEMA_CHARS = 5_000;
    /** Room for an enum of ids on the cmd.exe route: count * (id length + overhead) must stay within this. */
    public static final int CMD_EXE_ENUM_BUDGET_CHARS = 4_000;
    /** Each enum entry costs its id plus two quotes (escaped on the command line) and a comma. */
    public static final int ENUM_ENTRY_OVERHEAD = 5;

    private SchemaLimits() {
    }

    public static boolean enumFitsCmdExe(int count, int idLength) {
        return (long) count * (idLength + ENUM_ENTRY_OVERHEAD) <= CMD_EXE_ENUM_BUDGET_CHARS;
    }
}

// SchemaTooLargeException.java
public class SchemaTooLargeException extends RuntimeException {
    public SchemaTooLargeException(String message) {
        super(message);
    }
}
```
`SchemaGenerator.java`:
```java
package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the {@code --json-schema} for a PlanPatch from the part registry (P-16), so the AI, the checker and the
 * compiler share one vocabulary. Two forms: TYPED (per-part parameter types, ranges and enums, via oneOf) and FLAT
 * (parameters as key/value pairs; smaller, and the checker validates the types). The schema shapes the output; it
 * never replaces the checks in PlanPatcher and PlanCompiler.
 */
public final class SchemaGenerator {
    public enum Mode { TYPED, FLAT }

    public record Generated(Mode mode, Map<String, Object> schema, String json) {
        public int chars() {
            return json.length();
        }
    }

    public static final String ID_PATTERN = "^[a-z0-9-]{1,48}$";
    public static final String MATERIAL_PATTERN = "^([a-z][a-z0-9_]*|[a-z0-9_.-]+:[a-z0-9_/.-]+)$";
    private static final String REF_PREFIX = "#/$defs/";
    private static final List<String> PARAM_VALUE_TYPES = List.of("string", "number", "boolean", "array");

    private SchemaGenerator() {
    }

    public static Generated forLimit(PartTypeRegistry registry, Set<String> partIdsOrNull, int maxChars) {
        String typedJson = null;
        for (Mode mode : Mode.values()) {
            Map<String, Object> schema = patchSchema(registry, mode, partIdsOrNull);
            String json = CanonicalJson.write(schema);
            if (mode == Mode.TYPED) {
                typedJson = json;
            }
            if (json.length() <= maxChars) {
                return new Generated(mode, schema, json);
            }
        }
        throw new SchemaTooLargeException("the schema does not fit " + maxChars + " characters even in the flat form"
                + (typedJson == null ? "" : " (typed form: " + typedJson.length() + ")"));
    }

    public static Map<String, Object> patchSchema(PartTypeRegistry registry, Mode mode, Set<String> partIdsOrNull) {
        List<PartType> parts = new ArrayList<>();
        for (PartType t : registry.userParts()) {
            if (partIdsOrNull == null || partIdsOrNull.contains(t.id())) {
                parts.add(t);
            }
        }
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("pos", array(map("type", "integer"), 3, 3));
        defs.put("rot", obj(List.of(), map("turns", map("type", "integer", "minimum", 0, "maximum", 3), "mirror", map("type", "boolean"))));
        defs.put("anchor", anchorSchema());
        defs.put("connection", connectionSchema());
        defs.put("site", siteSchema());
        defs.put("style", obj(List.of("palette"), map("palette", map("type", "object", "additionalProperties", map("type", "string")),
                "moodTags", map("type", "array", "items", map("type", "string")))));
        defs.put("logistics", logisticsSchema());
        defs.put("anyParams", mode == Mode.FLAT ? flatParams(parts)
                : map("type", "object", "additionalProperties", map("type", PARAM_VALUE_TYPES)));

        List<Object> ops = new ArrayList<>();
        ops.add(op("add_node", List.of("node"), map("node", nodeSchema(parts, mode))));
        ops.add(op("update_params", List.of("id", "params"), map("id", map("type", "string"), "params", ref("anyParams"))));
        ops.add(op("move_node", List.of("id", "anchor"), map("id", map("type", "string"), "anchor", ref("anchor"))));
        ops.add(op("remove_node", List.of("id"), map("id", map("type", "string"))));
        ops.add(op("add_connection", List.of("connection"), map("connection", ref("connection"))));
        ops.add(op("remove_connection", List.of("id"), map("id", map("type", "string"))));
        ops.add(op("set_style", List.of("style"), map("style", ref("style"))));
        ops.add(op("set_site", List.of("site"), map("site", ref("site"))));
        ops.add(op("set_logistics", List.of("logistics"), map("logistics", map("oneOf", List.of(ref("logistics"), map("type", "null"))))));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("required", List.of("ops"));
        root.put("properties", map("ops", map("type", "array", "items", map("oneOf", ops))));
        root.put("$defs", defs);
        return root;
    }

    private static Map<String, Object> nodeSchema(List<PartType> parts, Mode mode) {
        List<String> ids = new ArrayList<>();
        for (PartType t : parts) {
            ids.add(t.id());
        }
        if (mode == Mode.FLAT) {
            return nodeObject(map("type", "string", "enum", ids), ref("anyParams"));
        }
        List<Object> perPart = new ArrayList<>();
        for (PartType t : parts) {
            perPart.add(nodeObject(map("const", t.id()), typedParams(t)));
        }
        return map("oneOf", perPart);
    }

    private static Map<String, Object> nodeObject(Object typeSchema, Object paramsSchema) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", map("type", "string", "pattern", ID_PATTERN));
        props.put("type", typeSchema);
        props.put("parent", map("type", List.of("string", "null")));
        props.put("anchor", ref("anchor"));
        props.put("params", paramsSchema);
        props.put("tags", map("type", "array", "items", map("type", "string")));
        props.put("label", map("type", "string"));
        return obj(List.of("id", "type", "anchor", "params"), props);
    }

    private static Map<String, Object> typedParams(PartType t) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParamSpec p : t.params()) {
            props.put(p.name(), paramSchema(p));
            if (p.required()) {
                required.add(p.name());
            }
        }
        return obj(required, props);
    }

    private static Map<String, Object> paramSchema(ParamSpec p) {
        return switch (p.type()) {
            case INT -> map("type", "integer", "minimum", p.min().toTree(), "maximum", p.max().toTree());
            case NUM -> map("type", "number", "minimum", p.min().toTree(), "maximum", p.max().toTree());
            case BOOL -> map("type", "boolean");
            case STR -> map("type", "string", "maxLength", p.max().toTree());
            case ENUM -> map("type", "string", "enum", p.enumValues());
            case MATERIAL -> map("type", "string", "pattern", MATERIAL_PATTERN);
            case INT_LIST -> map("type", "array", "items", map("type", "integer", "minimum", p.min().toTree(), "maximum", p.max().toTree()),
                    "maxItems", p.maxItems());
        };
    }

    private static Map<String, Object> flatParams(List<PartType> parts) {
        TreeSet<String> names = new TreeSet<>();
        for (PartType t : parts) {
            for (ParamSpec p : t.params()) {
                names.add(p.name());
            }
        }
        Map<String, Object> pair = obj(List.of("key", "value"), map("key", map("type", "string", "enum", new ArrayList<>(names)),
                "value", map("type", PARAM_VALUE_TYPES, "items", map("type", List.of("string", "number")))));
        return map("type", "array", "items", pair);
    }

    private static Map<String, Object> anchorSchema() {
        Map<String, Object> absolute = obj(List.of("kind", "pos"), map("kind", map("const", "absolute"), "pos", ref("pos"), "rot", ref("rot")));
        Map<String, Object> surface = obj(List.of("kind", "node", "side", "u", "v"), map("kind", map("const", "surface"),
                "node", map("type", "string"), "side", map("type", "string", "enum", List.of("outer", "inner")),
                "u", map("type", "integer", "minimum", 0), "v", map("type", "integer", "minimum", 0)));
        Map<String, Object> slot = obj(List.of("kind", "slot"), map("kind", map("const", "slot"), "slot", map("type", "string"), "rot", ref("rot")));
        return map("oneOf", List.of(absolute, surface, slot));
    }

    private static Map<String, Object> connectionSchema() {
        Map<String, Object> port = obj(List.of("node", "port"), map("node", map("type", "string"), "port", map("type", "string")));
        Map<String, Object> routing = obj(List.of("mode"), map("mode", map("type", "string", "enum", List.of("auto", "explicit")),
                "via", map("type", "array", "items", map("type", "string"))));
        Map<String, Object> constraints = obj(List.of(), map("maxLength", map("type", List.of("integer", "null")),
                "avoid", map("type", "array", "items", map("type", "string")), "maxTurns", map("type", List.of("integer", "null")),
                "entryDirs", map("type", "array", "items", map("type", "string", "enum", List.of("up", "down", "north", "east", "south", "west")))));
        return obj(List.of("id", "from", "to", "kind"), map("id", map("type", "string", "pattern", ID_PATTERN), "from", port, "to", port,
                "kind", map("type", "string", "enum", List.of("rotation", "item", "fluid", "redstone", "heat", "dock")),
                "routing", routing, "constraints", constraints));
    }

    private static Map<String, Object> siteSchema() {
        return obj(List.of("dimension", "origin", "facing", "bounds"), map("dimension", map("type", "string"),
                "origin", array(map("type", "integer"), 3, 3), "facing", map("type", "string", "enum", List.of("north", "east", "south", "west")),
                "bounds", array(map("type", "integer"), 6, 6), "terrainDigest", map("type", "string"), "claimId", map("type", "string")));
    }

    private static Map<String, Object> logisticsSchema() {
        Map<String, Object> box = array(map("type", "integer"), 6, 6);
        Map<String, Object> port = obj(List.of("node", "port"), map("node", map("type", "string"), "port", map("type", "string")));
        Map<String, Object> dock = obj(List.of("id", "pad", "clearance", "approach"), map("id", map("type", "string"), "pad", box, "clearance", box,
                "approach", map("type", "string", "enum", List.of("north", "east", "south", "west")),
                "ports", map("type", "array", "items", port), "connectors", map("type", "array", "items", map("type", "string"))));
        Map<String, Object> route = obj(List.of("id", "from", "to"), map("id", map("type", "string"), "from", map("type", "string"),
                "to", map("type", "string"), "waypoints", map("type", "array", "items", ref("pos")), "airship", map("type", List.of("string", "null"))));
        Map<String, Object> flow = obj(List.of("item", "perMin", "from", "to"), map("item", map("type", "string"), "perMin", map("type", "number"),
                "from", map("type", "string"), "to", map("type", "string")));
        return obj(List.of("docks", "routes", "flows"), map("docks", map("type", "array", "items", dock),
                "routes", map("type", "array", "items", route), "flows", map("type", "array", "items", flow)));
    }

    private static Map<String, Object> op(String name, List<String> required, Map<String, Object> fields) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("op", map("const", name));
        props.putAll(fields);
        List<String> req = new ArrayList<>(List.of("op"));
        req.addAll(required);
        return obj(req, props);
    }

    private static Map<String, Object> obj(List<String> required, Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("additionalProperties", false);
        if (!required.isEmpty()) {
            m.put("required", required);
        }
        m.put("properties", properties);
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return map("type", "array", "items", items, "minItems", min, "maxItems", max);
    }

    private static Map<String, Object> ref(String name) {
        return map("$ref", REF_PREFIX + name);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
```
`PlanJson.java`の変更2点:
```java
    // in nodeFromTree: params may be an object or an array of {key, value} pairs (the FLAT schema form)
    static Map<String, ParamValue> paramsFromTree(Object v, String path) {
        Map<String, ParamValue> params = new TreeMap<>();
        if (v == null) {
            return params;
        }
        if (v instanceof List<?> pairs) {
            for (int i = 0; i < pairs.size(); i++) {
                Map<String, Object> pair = JsonTree.obj(pairs.get(i), path + "[" + i + "]");
                String key = JsonTree.str(JsonTree.req(pair, "key", path + "[" + i + "]"), path + "[" + i + "].key");
                params.put(key, paramFromTree(JsonTree.req(pair, "value", path + "[" + i + "]"), path + "[" + i + "].value"));
            }
            return params;
        }
        for (Map.Entry<String, Object> e : JsonTree.obj(v, path).entrySet()) {
            params.put(e.getKey(), paramFromTree(e.getValue(), path + "." + e.getKey()));
        }
        return params;
    }
```
`nodeFromTree`と`op:update_params`の`params`の読みを、`paramsFromTree(m.get("params"), path + ".params")`に置き換える。`rotFromTree`は、`turns`・`mirror`が無ければ0・偽:
```java
        return new Rot(m.get("turns") == null ? 0 : JsonTree.integer(m.get("turns"), path + ".turns"),
                m.get("mirror") != null && JsonTree.bool(m.get("mirror"), path + ".mirror"));
```
(Task 5の`decodingErrorsCarryAJsonPath`は`rot`の欠落を検査していないので、そのまま緑。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS。**型つきの完全なスキーマが20,000文字に収まらない**、または**FLATが5,000文字に収まらない**ときは、実測の文字数をテストの失敗メッセージから読み、(a)説明を削る、(b)`$defs`へ寄せる、で縮める。それでも収まらなければ、私(計画の作成者)に報告して設計図の数字を見直す(テストの上限を勝手に緩めない)。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: SchemaGenerator(登録簿からPlanPatchのスキーマを生成。型つき/パラメータ配列の2方式、S-1の上限に従う)を追加(自然言語→工場建設 P3 Task 18)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: 建設命令の橋渡し(`PlanApi`・`Interpreter`・`CommandNames.PLAN`・ハイライト)

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/lang/{PlanApi,PlanAnchorArgs,PlanRunLimits,PlanLimitException,PlanModeDroneApi,PlanCommandDispatcher}.java`
- Modify: `lang/CommandNames.java`(`PLAN_GENERAL`・`PLAN_PART_COMMANDS`・`PLAN`・`PLAN_HELPERS`・`PLAN_VISIBLE`を足す。`ALL`は変えない)、`lang/Interpreter.java`(下記の変更)、`lang/SyntaxHighlighter.java`(多重定義)
- Test: `src/test/java/io/github/khayashi4337/micradrone/lang/{RecordingPlanApi,PlanInterpreterTest,PlanCommandNamesTest}.java`、`SyntaxHighlighterTest.java`(1件足す)

**Interfaces:**
- Produces:
  - `interface PlanApi`: `void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId)`・`void style(String role, String material)`・`void mood(String tag)`・`void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String,Object> params, List<String> tags, String label)`・`void updateParams(String id, Map<String,Object> params)`・`void relocate(String id, PlanAnchorArgs anchor)`・`void removePart(String id)`・`void connect(String id, String from, String to, String kind, List<String> via /*nullなら Auto*/, Map<String,Object> constraints /*nullなら無し*/)`・`void disconnect(String id)`・`void logistics(List<Object> docks, List<Object> routes, List<Object> flows)`・`void print(String text)`
  - `record PlanAnchorArgs(Kind kind, int u, int v, int w, int turns, boolean mirror, String target, String side, String slot)`: `enum Kind {ABSOLUTE, SURFACE, SLOT}`、`static absolute(u,v,w,turns,mirror)`・`surface(target, side, u, v)`・`slot(slot, turns, mirror)`
  - `record PlanRunLimits(long maxSteps, long maxMillis)`: `DEFAULT = new PlanRunLimits(100_000, 5_000)`
  - `class PlanLimitException extends MicraLangException`
  - `Interpreter(PlanApi planApi, PlanRunLimits limits)`(建設用。`PlanApi`があるときだけ`CommandNames.PLAN`を受け付け、畑の命令(`ALL`から`PLAN_HELPERS`を除く物)は実行時にも拒否する)
  - `CommandNames.PLAN_GENERAL`=`site,style,mood,part,update_params,relocate,remove_part,connect,disconnect,logistics`、`PLAN_PART_COMMANDS`=`balcony,beam,catwalk,chimney,dock_pad,door,floor,foundation,ladder,lamp,pillar,planter,railing,ramp,road,roof,sign,stairs,structure,trim,wall,window`、`PLAN`(この2つ)、`PLAN_HELPERS`=`print,len,abs,min,max,str,list,dict,set,range`、`PLAN_VISIBLE`=`PLAN`+`PLAN_HELPERS`
  - `SyntaxHighlighter.highlight(String source, Collection<String> commandNames)`(既存の`highlight(String)`は`CommandNames.ALL`のまま)

**命令の引数(設計図04 F-6。`connect`の`via`は、`None`または省略=`Auto`、リスト(空も)=`Explicit`):** `site(dimension, x, y, z, facing, bounds[, terrain_digest[, claim_id]])`(6〜8個)、`style(role, material)`、`mood(tag)`、`part(id, type, parent, anchor, params[, tags[, label]])`(5〜7個)、`<部品名>(id, parent, anchor, params[, tags[, label]])`(4〜6個。`id`の文字列を返す)、`update_params(id, params)`、`relocate(id, anchor)`、`remove_part(id)`、`connect(id, from, to, kind[, via[, constraints]])`(4〜6個。`from`・`to`は`"ノードID.ポート名"`)、`disconnect(id)`、`logistics(docks, routes, flows)`。`anchor`は`[u,v,w]`・`[u,v,w,回転数,鏡像]`・`["surface", 壁ID, "outer"|"inner", u, v]`・`["slot", スロットID, 回転数, 鏡像]`。

**`Interpreter`の変更(既存の挙動は変えない):**
1. フィールド`planApi`・`planLimits`・`planSteps`・`planStartNanos`を足す。既存の私的コンストラクタは、`planApi=null`・`planLimits=null`で新しい私的コンストラクタへ委譲する。
2. 公開コンストラクタ`Interpreter(PlanApi, PlanRunLimits)`: `PlanModeDroneApi.create(planApi)`を`DroneApi`として渡す。
3. `run(List<Stmt>)`の先頭で、`planStartNanos = System.nanoTime(); planSteps = 0;`。
4. `checkCancellation`の末尾に、`planLimits != null`のときの総ステップ・時間の検査(1024ステップごとに時間を測る)を足す。超えたら`PlanLimitException`。
5. `isBuiltinName(name)`=`ALL`に含む、または`planApi != null`かつ`PLAN`に含む。`defineFunction`と、`evalCall`の冒頭の「関数でない値」の判定の、`CommandNames.ALL.contains(...)`を、これに置き換える。
6. `evalCall`の、利用者の関数の解決のあと・ISRの門の前に、次を足す: `planApi != null`のとき、(a)`ALL`にあり`PLAN_HELPERS`に無い名前は`MicraLangException`(建設のスクリプトでは使えない)、(b)`PLAN`にある名前は、引数を評価して`PlanCommandDispatcher.invoke(planApi, name, values, line)`を返す。

- [ ] **Step 1: 失敗するテストを書く**

`RecordingPlanApi.java`(テスト用):
```java
package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records every call as one readable line, so tests can compare the sequence. */
final class RecordingPlanApi implements PlanApi {
    final List<String> calls = new ArrayList<>();
    final List<String> printed = new ArrayList<>();

    @Override
    public void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId) {
        calls.add("site " + dimension + " " + x + "," + y + "," + z + " " + facing + " " + java.util.Arrays.toString(bounds) + " [" + terrainDigest + "|" + claimId + "]");
    }

    @Override
    public void style(String role, String material) {
        calls.add("style " + role + "=" + material);
    }

    @Override
    public void mood(String tag) {
        calls.add("mood " + tag);
    }

    @Override
    public void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label) {
        calls.add("part " + id + " " + type + " parent=" + parent + " " + anchor + " " + new java.util.TreeMap<>(params) + " " + tags + " '" + label + "'");
    }

    @Override
    public void updateParams(String id, Map<String, Object> params) {
        calls.add("update " + id + " " + new java.util.TreeMap<>(params));
    }

    @Override
    public void relocate(String id, PlanAnchorArgs anchor) {
        calls.add("relocate " + id + " " + anchor);
    }

    @Override
    public void removePart(String id) {
        calls.add("remove " + id);
    }

    @Override
    public void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints) {
        calls.add("connect " + id + " " + from + " " + to + " " + kind + " via=" + via + " " + (constraints == null ? null : new java.util.TreeMap<>(constraints)));
    }

    @Override
    public void disconnect(String id) {
        calls.add("disconnect " + id);
    }

    @Override
    public void logistics(List<Object> docks, List<Object> routes, List<Object> flows) {
        calls.add("logistics " + docks.size() + "/" + routes.size() + "/" + flows.size());
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }
}
```
`PlanInterpreterTest.java`:
```java
package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanInterpreterTest {
    private static void run(String source, RecordingPlanApi api, PlanRunLimits limits) {
        new Interpreter(api, limits).run(new Parser(new Lexer(source).scan()).parseProgram());
    }

    private static void run(String source, RecordingPlanApi api) {
        run(source, api, PlanRunLimits.DEFAULT);
    }

    @Test
    void generalCommandsReachThePlanApi() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("site(\"minecraft:overworld\", 100, 64, -20, \"east\", [-2, 0, -2, 9, 9, 9])\n"
                + "style(\"roof\", \"minecraft:bricks\")\n"
                + "mood(\"cozy\")\n"
                + "update_params(\"hut\", {\"width\": 9})\n"
                + "relocate(\"hut\", [1, 0, 2, 1, True])\n"
                + "remove_part(\"hut\")\n"
                + "disconnect(\"c-1\")\n", api);
        assertEquals(List.of(
                "site minecraft:overworld 100,64,-20 east [-2, 0, -2, 9, 9, 9] [|]",
                "style roof=minecraft:bricks",
                "mood cozy",
                "update hut {width=9.0}",
                "relocate hut PlanAnchorArgs[kind=ABSOLUTE, u=1, v=0, w=2, turns=1, mirror=true, target=null, side=null, slot=null]",
                "remove hut",
                "disconnect c-1"), api.calls);
    }

    @Test
    void siteAcceptsTheOptionalDigestAndClaim() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("site(\"minecraft:overworld\", 0, 0, 0, \"north\", [0,0,0,1,1,1], \"abc\", \"claim-1\")", api);
        assertEquals("site minecraft:overworld 0,0,0 north [0, 0, 0, 1, 1, 1] [abc|claim-1]", api.calls.get(0));
    }

    @Test
    void partCommandsComeFromTheRegistryNamesAndReturnTheirId() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("w = wall(\"wall-n\", \"hut\", [0, 0, 0], {\"side\": \"north\"})\n"
                + "print(w)\n"
                + "part(\"press-1\", \"create:mechanical_press\", None, [3, 1, 2], {}, [\"a\", \"b\"], \"the press\")\n"
                + "door(\"door-1\", \"hut\", [\"surface\", \"wall-n\", \"outer\", 3, 0], {\"kind\": \"double\"})\n"
                + "module = part(\"m\", \"mod:line\", None, [\"slot\", \"slot-a\", 2, False], {})\n", api);
        assertEquals(List.of("wall-n"), api.printed);
        assertEquals("part wall-n micra:wall parent=hut PlanAnchorArgs[kind=ABSOLUTE, u=0, v=0, w=0, turns=0, mirror=false, target=null, side=null, slot=null] {side=north} [] ''",
                api.calls.get(0));
        assertEquals("part press-1 create:mechanical_press parent=null PlanAnchorArgs[kind=ABSOLUTE, u=3, v=1, w=2, turns=0, mirror=false, target=null, side=null, slot=null] {} [a, b] 'the press'",
                api.calls.get(1));
        assertEquals("part door-1 micra:door parent=hut PlanAnchorArgs[kind=SURFACE, u=3, v=0, w=0, turns=0, mirror=false, target=wall-n, side=outer, slot=null] {kind=double} [] ''",
                api.calls.get(2));
        assertTrue(api.calls.get(3).contains("kind=SLOT") && api.calls.get(3).contains("slot=slot-a") && api.calls.get(3).contains("turns=2"));
    }

    @Test
    void connectMeansAutoWhenViaIsMissingOrNoneAndExplicitWhenAListIsGiven() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("connect(\"c1\", \"a.out\", \"b.in\", \"rotation\")\n"
                + "connect(\"c2\", \"a.out\", \"b.in\", \"item\", None)\n"
                + "connect(\"c3\", \"a.out\", \"b.in\", \"item\", [])\n"
                + "connect(\"c4\", \"a.out\", \"b.in\", \"rotation\", [\"s1\", \"s2\"], {\"max_length\": 12, \"avoid\": [\"x\"]})\n", api);
        assertEquals("connect c1 a.out b.in rotation via=null null", api.calls.get(0));
        assertEquals("connect c2 a.out b.in item via=null null", api.calls.get(1));
        assertEquals("connect c3 a.out b.in item via=[] null", api.calls.get(2));
        assertEquals("connect c4 a.out b.in rotation via=[s1, s2] {avoid=[x], max_length=12.0}", api.calls.get(3));
    }

    @Test
    void variablesLoopsAndFunctionsBuildPlans() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("def post(n, x):\n"
                + "    pillar(\"post-\" + str(n), None, [x, 0, 0], {})\n"
                + "for i in range(3):\n"
                + "    post(i, i * 4)\n", api);
        assertEquals(3, api.calls.size());
        assertTrue(api.calls.get(2).startsWith("part post-2.0 micra:pillar"), api.calls.get(2));
    }

    @Test
    void badArgumentsAreClearLanguageErrors() {
        RecordingPlanApi api = new RecordingPlanApi();
        MicraLangException arity = assertThrows(MicraLangException.class, () -> run("wall(\"w\")", api));
        assertTrue(arity.getMessage().contains("wall()"), arity.getMessage());
        assertTrue(assertThrows(MicraLangException.class, () -> run("site(\"d\", 1.5, 0, 0, \"north\", [0,0,0,1,1,1])", api)).getMessage().contains("整数"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("site(\"d\", 1, 0, 0, \"up\", [0,0,0,1,1,1])", api)).getMessage().contains("site()"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("wall(\"w\", None, [0, 0], {})", api)).getMessage().contains("wall()"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("connect(\"c\", \"nodot\", \"b.in\", \"item\")", api)).getMessage().contains("connect()"));
    }

    @Test
    void farmCommandsAndNondeterministicBuiltinsAreRefusedEvenWithoutTheStaticCheck() {
        for (String call : List.of("move(\"north\")", "harvest()", "sleep_ticks(1)", "random()", "get_time()", "semaphore()")) {
            MicraLangException e = assertThrows(MicraLangException.class, () -> run(call, new RecordingPlanApi()), call);
            assertTrue(e.getMessage().contains("construction script"), call + ": " + e.getMessage());
        }
    }

    @Test
    void aFunctionMayNotShadowAConstructionCommand() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("def wall():\n    pass\n", new RecordingPlanApi()));
        assertTrue(e.getMessage().contains("built-in"), e.getMessage());
    }

    @Test
    void theStepLimitStopsRunawayScripts() {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("while True:\n    pass\n", new RecordingPlanApi(), new PlanRunLimits(500, 60_000)));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void theTimeLimitStopsSlowScripts() {
        long start = System.nanoTime();
        assertThrows(PlanLimitException.class, () -> run("while True:\n    pass\n", new RecordingPlanApi(), new PlanRunLimits(Long.MAX_VALUE, 60)));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "it stopped soon after the limit");
    }

    @Test
    void theDefaultLimitsAreTheDocumentedOnes() {
        assertEquals(100_000, PlanRunLimits.DEFAULT.maxSteps());
        assertEquals(5_000, PlanRunLimits.DEFAULT.maxMillis());
        assertFalse(new PlanLimitException(3, "x") instanceof IllegalStateException);
    }

    @Test
    void thePlanCommandsDoNotExistForAFarmInterpreter() {
        FakeDroneApi farm = new FakeDroneApi(5);
        MicraLangException e = assertThrows(MicraLangException.class,
                () -> new Interpreter(farm).run(new Parser(new Lexer("wall(\"w\", None, [0,0,0], {})").scan()).parseProgram()));
        assertTrue(e.getMessage().contains("unknown function"), e.getMessage());
        // and a farm script may define functions with those names, as before
        new Interpreter(new FakeDroneApi(5)).run(new Parser(new Lexer("def wall():\n    pass\nwall()\n").scan()).parseProgram());
    }
}
```
`PlanCommandNamesTest.java`:
```java
package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanCommandNamesTest {
    @Test
    void theFarmListIsUntouched() {
        assertEquals(49, CommandNames.ALL.size(), "the farm command list must not change; update this only if the farm gains a command");
        for (String name : CommandNames.PLAN) {
            assertFalse(CommandNames.ALL.contains(name), name + " must not be a farm command");
        }
    }

    @Test
    void partCommandsAreExactlyTheBuildingPartNames() {
        assertEquals(BuildingParts.NAMES, CommandNames.PLAN_PART_COMMANDS.stream().sorted().toList());
        assertEquals(22, CommandNames.PLAN_PART_COMMANDS.size());
    }

    @Test
    void everyPlanCommandIsRecognisedByAConstructionInterpreter() {
        for (String name : CommandNames.PLAN) {
            try {
                new Interpreter(new RecordingPlanApi(), PlanRunLimits.DEFAULT)
                        .run(new Parser(new Lexer(name + "()").scan()).parseProgram());
            } catch (MicraLangException e) {
                assertFalse(e.getMessage().contains("unknown function"), name + "() is in CommandNames.PLAN but is not recognised: " + e.getMessage());
            }
        }
    }

    @Test
    void helpersAreFarmBuiltinsThatAreSafeInAConstructionScript() {
        Set<String> nondeterministic = new HashSet<>(List.of("random", "create_task", "semaphore", "attach_isr", "raise_interrupt", "sleep_ticks"));
        for (String helper : CommandNames.PLAN_HELPERS) {
            assertTrue(CommandNames.ALL.contains(helper), helper);
            assertFalse(nondeterministic.contains(helper), helper);
        }
        assertEquals(CommandNames.PLAN.size() + CommandNames.PLAN_HELPERS.size(), CommandNames.PLAN_VISIBLE.size());
    }
}
```
(`ALL`の要素数49は、実装前に`CommandNames.java`の一覧から数えた値。数え直して違えば、テストの数を直すのではなく、まず`ALL`が変わっていないかを疑う。)

`SyntaxHighlighterTest`に足す:
```java
    @Test
    void aCommandNameListCanBeGivenSoConstructionCommandsHighlightAsBuiltins() {
        assertEquals(Kind.CALL, kindAt("wall()", 0), "the farm highlighter does not know construction commands");
        var spans = SyntaxHighlighter.highlight("wall()", CommandNames.PLAN_VISIBLE);
        assertEquals(Kind.BUILTIN, spans.get(0).kind());
        assertEquals(Kind.BUILTIN, SyntaxHighlighter.highlight("harvest()").get(0).kind(), "the one-argument form still uses the farm list");
        for (String command : CommandNames.PLAN_VISIBLE) {
            assertEquals(Kind.BUILTIN, SyntaxHighlighter.highlight(command + "()", CommandNames.PLAN_VISIBLE).get(0).kind(), command);
        }
    }
```
(`kindAt`は既存のテストの補助。`aCommandNameList…`で使う`Kind`のimportは既存。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.lang.*" --console=plain`
Expected: FAIL(`PlanApi`が無い)。

- [ ] **Step 3: 実装する**

`PlanApi.java`・`PlanAnchorArgs.java`・`PlanRunLimits.java`・`PlanLimitException.java`(`package io.github.khayashi4337.micradrone.lang;`):
```java
// PlanApi.java
/**
 * The bridge between a construction script and the plan being built. Separate from {@link DroneApi} on purpose: the
 * farm commands and their pacing stay untouched, and a script is either a farm script or a construction script.
 * Values arrive as the interpreter holds them (numbers as Double, dicts as Map, lists as List).
 */
public interface PlanApi {
    void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId);

    void style(String role, String material);

    void mood(String tag);

    void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label);

    void updateParams(String id, Map<String, Object> params);

    void relocate(String id, PlanAnchorArgs anchor);

    void removePart(String id);

    /** {@code via} is null for automatic routing and a (possibly empty) list of part ids for explicit routing. */
    void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints);

    void disconnect(String id);

    void logistics(List<Object> docks, List<Object> routes, List<Object> flows);

    void print(String text);
}

// PlanAnchorArgs.java
public record PlanAnchorArgs(Kind kind, int u, int v, int w, int turns, boolean mirror, String target, String side, String slot) {
    public enum Kind { ABSOLUTE, SURFACE, SLOT }

    public static PlanAnchorArgs absolute(int u, int v, int w, int turns, boolean mirror) {
        return new PlanAnchorArgs(Kind.ABSOLUTE, u, v, w, turns, mirror, null, null, null);
    }

    public static PlanAnchorArgs surface(String target, String side, int u, int v) {
        return new PlanAnchorArgs(Kind.SURFACE, u, v, 0, 0, false, target, side, null);
    }

    public static PlanAnchorArgs slot(String slot, int turns, boolean mirror) {
        return new PlanAnchorArgs(Kind.SLOT, 0, 0, 0, turns, mirror, null, null, slot);
    }
}

// PlanRunLimits.java
/** Upper bounds for one construction script run: statements executed and wall-clock time. */
public record PlanRunLimits(long maxSteps, long maxMillis) {
    public static final PlanRunLimits DEFAULT = new PlanRunLimits(100_000, 5_000);
}

// PlanLimitException.java
/** A construction script ran past its step or time limit. Its own type so callers can tell it from a script error. */
public class PlanLimitException extends MicraLangException {
    public PlanLimitException(int line, String message) {
        super(line, message);
    }
}
```
`PlanModeDroneApi.java`:
```java
package io.github.khayashi4337.micradrone.lang;

import java.lang.reflect.Proxy;

/**
 * The {@link DroneApi} a construction interpreter is given: it has no drone, so every method refuses, except
 * {@code print}, which goes to the plan's own log. The static profile check stops farm commands earlier; this is
 * the second line of defence.
 */
final class PlanModeDroneApi {
    private PlanModeDroneApi() {
    }

    static DroneApi create(PlanApi planApi) {
        return (DroneApi) Proxy.newProxyInstance(DroneApi.class.getClassLoader(), new Class<?>[]{DroneApi.class}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "PlanModeDroneApi";
                };
            }
            if (method.getName().equals("print") && args != null && args.length == 1) {
                planApi.print(String.valueOf(args[0]));
                return null;
            }
            throw new MicraLangException(0, "'" + method.getName() + "' is a drone command and cannot be used in a construction script");
        });
    }
}
```
`PlanCommandDispatcher.java`:
```java
package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an evaluated construction command call into a typed {@link PlanApi} call. Argument problems become
 * {@link MicraLangException}s that name the command and the line.
 */
final class PlanCommandDispatcher {
    static final String MICRA_PREFIX = "micra:";

    private PlanCommandDispatcher() {
    }

    static Object invoke(PlanApi api, String name, List<Object> args, int line) {
        try {
            return dispatch(api, name, args, line);
        } catch (IllegalArgumentException e) {
            throw new MicraLangException(line, name + "(): " + e.getMessage());
        }
    }

    private static Object dispatch(PlanApi api, String name, List<Object> args, int line) {
        switch (name) {
            case "site" -> {
                arity(name, args, 6, 8, line);
                api.site(str(args, 0), integer(args.get(1)), integer(args.get(2)), integer(args.get(3)), str(args, 4),
                        ints(args.get(5), 6), args.size() > 6 ? str(args, 6) : "", args.size() > 7 ? str(args, 7) : "");
            }
            case "style" -> {
                arity(name, args, 2, 2, line);
                api.style(str(args, 0), str(args, 1));
            }
            case "mood" -> {
                arity(name, args, 1, 1, line);
                api.mood(str(args, 0));
            }
            case "part" -> {
                arity(name, args, 5, 7, line);
                api.part(str(args, 0), str(args, 1), optStr(args, 2), anchor(args.get(3)), map(args.get(4)),
                        args.size() > 5 ? strings(args.get(5)) : List.of(), args.size() > 6 ? str(args, 6) : "");
                return str(args, 0);
            }
            case "update_params" -> {
                arity(name, args, 2, 2, line);
                api.updateParams(str(args, 0), map(args.get(1)));
            }
            case "relocate" -> {
                arity(name, args, 2, 2, line);
                api.relocate(str(args, 0), anchor(args.get(1)));
            }
            case "remove_part" -> {
                arity(name, args, 1, 1, line);
                api.removePart(str(args, 0));
            }
            case "connect" -> {
                arity(name, args, 4, 6, line);
                Object via = args.size() > 4 ? args.get(4) : MicraNone.INSTANCE;
                Object constraints = args.size() > 5 ? args.get(5) : MicraNone.INSTANCE;
                api.connect(str(args, 0), str(args, 1), str(args, 2), str(args, 3), via instanceof MicraNone ? null : strings(via),
                        constraints instanceof MicraNone ? null : map(constraints));
            }
            case "disconnect" -> {
                arity(name, args, 1, 1, line);
                api.disconnect(str(args, 0));
            }
            case "logistics" -> {
                arity(name, args, 3, 3, line);
                api.logistics(list(args.get(0)), list(args.get(1)), list(args.get(2)));
            }
            default -> {
                if (!CommandNames.PLAN_PART_COMMANDS.contains(name)) {
                    throw new MicraLangException(line, "unknown function '" + name + "'");
                }
                arity(name, args, 4, 6, line);
                api.part(str(args, 0), MICRA_PREFIX + name, optStr(args, 1), anchor(args.get(2)), map(args.get(3)),
                        args.size() > 4 ? strings(args.get(4)) : List.of(), args.size() > 5 ? str(args, 5) : "");
                return str(args, 0);
            }
        }
        return MicraNone.INSTANCE;
    }

    private static void arity(String name, List<Object> args, int min, int max, int line) {
        if (args.size() < min || args.size() > max) {
            throw new MicraLangException(line, name + "() takes " + (min == max ? String.valueOf(min) : min + " to " + max)
                    + " arguments but got " + args.size());
        }
    }

    private static String str(List<Object> args, int i) {
        if (args.get(i) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException((i + 1) + "番目の引数は文字列が必要です");
    }

    private static String optStr(List<Object> args, int i) {
        return args.get(i) instanceof MicraNone ? null : str(args, i);
    }

    static int integer(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        throw new IllegalArgumentException("整数が必要です(" + v + ")");
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("True か False が必要です(" + v + ")");
    }

    private static int[] ints(Object v, int size) {
        List<Object> list = list(v);
        if (list.size() != size) {
            throw new IllegalArgumentException("整数が" + size + "個並んだリストが必要です");
        }
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = integer(list.get(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException("リストが必要です");
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            if (!(o instanceof String s)) {
                throw new IllegalArgumentException("文字列のリストが必要です");
            }
            out.add(s);
        }
        return out;
    }

    private static Map<String, Object> map(Object v) {
        if (v instanceof MicraNone) {
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("辞書({\"名前\": 値})が必要です");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("辞書のキーは文字列にしてください");
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    private static PlanAnchorArgs anchor(Object v) {
        List<Object> a = list(v);
        if (!a.isEmpty() && a.get(0) instanceof String kind) {
            switch (kind) {
                case "surface" -> {
                    if (a.size() != 5 || !(a.get(1) instanceof String target) || !(a.get(2) instanceof String side)) {
                        throw new IllegalArgumentException("[\"surface\", 壁ID, \"outer\"か\"inner\", u, v] の形にしてください");
                    }
                    return PlanAnchorArgs.surface(target, side, integer(a.get(3)), integer(a.get(4)));
                }
                case "slot" -> {
                    if (a.size() < 2 || a.size() > 4 || !(a.get(1) instanceof String slot)) {
                        throw new IllegalArgumentException("[\"slot\", スロットID, 回転数, 鏡像] の形にしてください");
                    }
                    return PlanAnchorArgs.slot(slot, a.size() > 2 ? integer(a.get(2)) : 0, a.size() > 3 && bool(a.get(3)));
                }
                default -> throw new IllegalArgumentException("位置指定の種類が不明です: " + kind);
            }
        }
        if (a.size() < 3 || a.size() > 5) {
            throw new IllegalArgumentException("[u, v, w] か [u, v, w, 回転数, 鏡像] の形にしてください");
        }
        return PlanAnchorArgs.absolute(integer(a.get(0)), integer(a.get(1)), integer(a.get(2)),
                a.size() > 3 ? integer(a.get(3)) : 0, a.size() > 4 && bool(a.get(4)));
    }
}
```
(`MicraNone`は`final class MicraNone`のシングルトン。`instanceof MicraNone`で判定できる。)

`CommandNames.java`に足す(`ALL`はそのまま):
```java
    /** General construction commands (see docs/design/nl_factory_builder/04_foundations.md, F-6). */
    public static final List<String> PLAN_GENERAL = List.of("site", "style", "mood", "part", "update_params", "relocate",
            "remove_part", "connect", "disconnect", "logistics");

    /** One command per building part (micra:*), named by the part id without the prefix; a test keeps it equal to the registry. */
    public static final List<String> PLAN_PART_COMMANDS = List.of("balcony", "beam", "catwalk", "chimney", "dock_pad", "door",
            "floor", "foundation", "ladder", "lamp", "pillar", "planter", "railing", "ramp", "road", "roof", "sign", "stairs",
            "structure", "trim", "wall", "window");

    /**
     * Every construction command. Deliberately NOT part of {@link #ALL}: {@code ALL} makes the interpreter refuse a farm
     * script's own function with the same name (and feeds the farm editor), so generic names like wall or place would break
     * scripts players already wrote. The interpreter only accepts these when it was built with a PlanApi.
     */
    public static final List<String> PLAN = java.util.stream.Stream.concat(PLAN_GENERAL.stream(), PLAN_PART_COMMANDS.stream()).toList();

    /** Farm builtins that are pure and deterministic, so they stay usable in a construction script. */
    public static final List<String> PLAN_HELPERS = List.of("print", "len", "abs", "min", "max", "str", "list", "dict", "set", "range");

    /** What the construction editor highlights and completes. */
    public static final List<String> PLAN_VISIBLE = java.util.stream.Stream.concat(PLAN.stream(), PLAN_HELPERS.stream()).toList();
```
`SyntaxHighlighter.java`: `highlight(String)`を`highlight(source, CommandNames.ALL)`に委譲し、`highlight(String source, Collection<String> commandNames)`を足す。`classifyWord(source, word, wordEnd)`に`Collection<String> commandNames`を引数で足し、`CommandNames.ALL.contains(word)`を`commandNames.contains(word)`に。(呼び出し箇所の`classifyWord(...)`に引数を通す。既存の挙動は`ALL`のままなので変わらない。)

`Interpreter.java`の変更は上の「変更」1〜6のとおり。具体的には:
```java
    // fields (after `private final long generation;`)
    private final PlanApi planApi;
    private final PlanRunLimits planLimits;
    private long planSteps = 0;
    private long planStartNanos = 0;
    /** Checking the clock on every statement would cost more than the statements; every 1024th is enough. */
    private static final long PLAN_TIME_CHECK_MASK = 0x3FF;

    // the existing private constructor delegates to a new one with two more parameters
    private Interpreter(DroneApi api, DebugController debug, Environment globalEnv, boolean isrContext,
            TaskRegistry taskRegistry, InterruptTable interruptTable, long generation) {
        this(api, debug, globalEnv, isrContext, taskRegistry, interruptTable, generation, null, null);
    }

    private Interpreter(DroneApi api, DebugController debug, Environment globalEnv, boolean isrContext,
            TaskRegistry taskRegistry, InterruptTable interruptTable, long generation, PlanApi planApi, PlanRunLimits planLimits) {
        /* the existing assignments, plus: */
        this.planApi = planApi;
        this.planLimits = planLimits;
    }

    /** A construction-script interpreter: only PlanApi commands and pure helpers, under {@code limits}. */
    public Interpreter(PlanApi planApi, PlanRunLimits limits) {
        this(planApi, limits, new TaskRegistry());
    }

    private Interpreter(PlanApi planApi, PlanRunLimits limits, TaskRegistry registry) {
        this(PlanModeDroneApi.create(planApi), null, new Environment(), false, registry, new InterruptTable(),
                registry.currentGeneration(), planApi, limits);
    }
```
`run`:
```java
    public void run(List<Stmt> program) {
        planStartNanos = System.nanoTime();
        planSteps = 0;
        execBlock(program);
    }
```
`checkCancellation`の末尾:
```java
        if (planLimits != null) {
            planSteps++;
            if (planSteps > planLimits.maxSteps()) {
                throw new PlanLimitException(line, "construction script exceeded " + planLimits.maxSteps() + " steps");
            }
            if ((planSteps & PLAN_TIME_CHECK_MASK) == 0 && (System.nanoTime() - planStartNanos) / 1_000_000 > planLimits.maxMillis()) {
                throw new PlanLimitException(line, "construction script exceeded " + planLimits.maxMillis() + " ms");
            }
        }
```
`defineFunction`と`evalCall`:
```java
    private boolean isBuiltinName(String name) {
        return CommandNames.ALL.contains(name) || planApi != null && CommandNames.PLAN.contains(name);
    }
    // defineFunction: if (isBuiltinName(s.name())) throw ... (same message)
    // evalCall: `!CommandNames.ALL.contains(call.name())` -> `!isBuiltinName(call.name())`
    // evalCall, after the user-function resolution and before the ISR gate:
        if (planApi != null) {
            if (CommandNames.ALL.contains(call.name()) && !CommandNames.PLAN_HELPERS.contains(call.name())) {
                throw new MicraLangException(call.line(), "'" + call.name() + "' cannot be used in a construction script (only construction commands and pure helpers)");
            }
            if (CommandNames.PLAN.contains(call.name())) {
                List<Object> values = new ArrayList<>(call.args().size());
                for (Expr arg : call.args()) {
                    values.add(eval(arg));
                }
                return PlanCommandDispatcher.invoke(planApi, call.name(), values, call.line());
            }
        }
```
(`print`は`PLAN_HELPERS`に含まれるので拒否されず、従来の`api.print`へ進み、`PlanModeDroneApi`が`planApi.print`へ渡す。)

- [ ] **Step 4: テストが通り、畑の既存のテストが壊れていないことを確認する**

Run: `./gradlew test --console=plain`
Expected: PASS(既存の47ファイルすべて。特に`CommandNamesTest`・`SyntaxHighlighterTest`・`InterpreterTest`・`SampleScriptsTest`)。`CommandNames.ALL`の要素数が変わっていないことを、`PlanCommandNamesTest.theFarmListIsUntouched`が確かめる。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/lang src/test/java/io/github/khayashi4337/micradrone/lang
git commit -m "$(cat <<'EOF'
feat: 建設命令の橋渡し(PlanApi・PlanRunLimits・CommandNames.PLAN・Interpreterの建設用の入口)を追加。畑のCommandNames.ALLは変えない(自然言語→工場建設 P3 Task 19)

ALLに一般的な名前を足すと、畑のスクリプトの同名の関数定義が拒否されて壊れるので、PLANを別にした。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 20: `PlanRecorder`・`PlanScriptProfile`(静的検査)・`PlanScriptRunner`

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/build/script/{PlanRecorder,PlanScriptProfile,PlanScriptRunner}.java`
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/script/{PlanRecorderTest,PlanScriptProfileTest,PlanScriptRunnerTest}.java`

**Interfaces:**
- Consumes: Task 19の`PlanApi`・`PlanAnchorArgs`・`PlanRunLimits`・`PlanLimitException`・`CommandNames`、Task 5の型、Task 4の`Issue`
- Produces:
  - `PlanRecorder implements PlanApi`: `PlanPatch toPatch(String patchId, int baseRevision, String stageId)`、`List<String> printed()`。`site`→`SetSite`、`style`・`mood`→最初に呼ばれた位置に、集めた全体を持つ1つの`SetStyle`、`part`→`AddNode`(パラメータは`ParamValue.fromTree`で読む。辞書が入っていれば`IllegalArgumentException`)、`updateParams`→`UpdateParams`、`relocate`→`MoveNode`、`removePart`→`RemoveNode`、`connect`→`AddConnection`(`from`・`to`は最初の`.`で分ける。`via`がnullなら`Routing.AUTO`、リスト(空も)なら`Explicit`。`constraints`のキーは`max_length`・`avoid`・`max_turns`・`entry_dirs`だけ)、`disconnect`→`RemoveConnection`、`logistics`→`SetLogistics`(辞書の欄: ドック`id,pad,clearance,approach,ports,connectors`、航路`id,from,to,waypoints,airship`、流れ`item,per_min,from,to`)
  - `PlanScriptProfile`: `record Violation(int line, String name, Reason reason)`、`enum Reason {NONDETERMINISTIC, FARM_COMMAND, UNKNOWN}`、`static List<Violation> check(List<Stmt> program)`(許可: `PLAN`・`PLAN_HELPERS`・利用者が`def`した関数。乱数・`create_task`・`semaphore`・`attach_isr`・`raise_interrupt`・`sleep_ticks`は`NONDETERMINISTIC`、それ以外の`ALL`の命令は`FARM_COMMAND`、他は`UNKNOWN`。メソッド呼び出しは、`post`・`wait`だけ拒否(`NONDETERMINISTIC`))、`enum Kind {PLAN, FARM, MIXED, NEUTRAL}`、`static Kind classify(List<Stmt> program)`
  - `PlanScriptRunner`: `record Result(PlanPatch patch, List<Issue> issues, List<String> printed)`(`boolean ok()`=`patch != null`)、`static Result run(List<String> scripts, String patchId, int baseRevision, String stageId, PlanRunLimits limits)`。1本ずつ、(1)長さが`PlanScriptWriter.MAX_SCRIPT_CHARS`を超えれば`E-SCRIPT-LIMIT`、(2)構文エラーは`E-SCHEMA`(スクリプトの番号と行つき)、(3)静的検査の違反は`E-SCRIPT-FORBIDDEN`(命令ごとに1件。`data.name`・`data.reason`)、(4)実行(上限は1本ごと)。`PlanLimitException`は`E-SCRIPT-LIMIT`、他の`MicraLangException`は`E-SCHEMA`。ERRORがあれば`patch=null`。複数のスクリプトは、同じ`PlanRecorder`に順に取り込む

- [ ] **Step 1: 失敗するテストを書く**

`PlanScriptProfileTest.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanScriptProfileTest {
    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    private static List<String> names(String source) {
        return PlanScriptProfile.check(parse(source)).stream().map(v -> v.name() + ":" + v.reason()).toList();
    }

    @Test
    void constructionCommandsControlFlowAndPureHelpersAreAllowed() {
        assertEquals(List.of(), names("style(\"roof\", \"minecraft:bricks\")\n"
                + "def post(n):\n    pillar(\"p-\" + str(n), None, [n, 0, 0], {})\n"
                + "for i in range(3):\n    post(i)\n"
                + "x = len([1, 2]) + abs(-3) + min(1, 2) + max(1, 2)\n"
                + "items = list()\nitems.append(1)\nd = dict()\ns = set()\nprint(\"ok\")\n"));
    }

    @Test
    void nondeterministicBuiltinsAreRefused() {
        for (String call : List.of("random()", "create_task(\"t\", 1, 0, f)", "semaphore()", "attach_isr(\"north\", f)",
                "raise_interrupt(\"north\")", "sleep_ticks(5)")) {
            List<String> v = names("def f():\n    pass\n" + call);
            assertEquals(1, v.size(), call);
            assertTrue(v.get(0).endsWith(":NONDETERMINISTIC"), call + " -> " + v);
        }
    }

    @Test
    void farmCommandsAndPerceptionAreRefused() {
        for (String call : List.of("move(\"north\")", "till()", "plant(\"wheat\")", "harvest()", "do_a_flip()", "can_harvest()",
                "get_pos_x()", "get_time()", "get_weather()", "get_ground()", "set_output(True)", "pair_with(\"x\")",
                "cast_line()", "repair_rod()", "measure()", "is_rotten()")) {
            List<String> v = names(call);
            assertEquals(1, v.size(), call);
            assertTrue(v.get(0).endsWith(":FARM_COMMAND"), call + " -> " + v);
        }
    }

    @Test
    void unknownCallsAreRefusedAndUserFunctionsAreNot() {
        assertEquals(List.of("frobnicate:UNKNOWN"), names("frobnicate(1)"));
        assertEquals(List.of(), names("def helper(x):\n    return x + 1\nhelper(2)\n"));
    }

    @Test
    void violationsInsideNestedBlocksAndExpressionsAreFound() {
        String source = "def f():\n    while True:\n        if random() > 0.5:\n            pass\nx = [1, harvest()]\nd = {\"a\": get_time()}\nfor i in range(2):\n    y = d[move(\"north\")]\n";
        List<String> v = names(source);
        assertEquals(List.of("random:NONDETERMINISTIC", "harvest:FARM_COMMAND", "get_time:FARM_COMMAND", "move:FARM_COMMAND"), v);
        assertEquals(3, PlanScriptProfile.check(parse("\n\nharvest()\n")).get(0).line());
    }

    @Test
    void semaphoreMethodsAreRefused() {
        List<String> v = names("s = list()\ns.post()\ns.wait()\ns.append(1)\n");
        assertEquals(List.of("post:NONDETERMINISTIC", "wait:NONDETERMINISTIC"), v);
    }

    @Test
    void classifyTellsFarmFromConstructionAndFlagsMixtures() {
        assertEquals(PlanScriptProfile.Kind.PLAN, PlanScriptProfile.classify(parse("wall(\"w\", None, [0,0,0], {\"side\": \"north\"})")));
        assertEquals(PlanScriptProfile.Kind.FARM, PlanScriptProfile.classify(parse("harvest()")));
        assertEquals(PlanScriptProfile.Kind.MIXED, PlanScriptProfile.classify(parse("harvest()\nwall(\"w\", None, [0,0,0], {})")));
        assertEquals(PlanScriptProfile.Kind.NEUTRAL, PlanScriptProfile.classify(parse("x = 1 + 2\nprint(x)")));
    }
}
```
`PlanRecorderTest.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanRecorderTest {
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void operationsAreRecordedInCallOrder() {
        PlanRecorder r = new PlanRecorder();
        r.site("minecraft:overworld", 1, 2, 3, "east", new int[]{0, 0, 0, 5, 5, 5}, "", "");
        r.part("hut", "micra:structure", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params("width", 7.0), List.of("a"), "label");
        r.updateParams("hut", params("depth", 9.0));
        r.relocate("hut", PlanAnchorArgs.absolute(1, 0, 1, 1, true));
        r.part("d", "micra:door", "hut", PlanAnchorArgs.surface("wall-n", "outer", 3, 0), params(), List.of(), "");
        r.removePart("d");
        PlanPatch patch = r.toPatch("p", 4, "script");
        assertEquals("p", patch.patchId());
        assertEquals(4, patch.baseRevision());
        assertEquals(6, patch.ops().size());
        assertTrue(patch.ops().get(0) instanceof PlanOp.SetSite);
        PlanOp.SetSite site = (PlanOp.SetSite) patch.ops().get(0);
        assertEquals(Facing.EAST, site.site().frame().facing());
        assertEquals(new Box(0, 0, 0, 5, 5, 5), site.site().localBounds());
        PlanOp.AddNode add = (PlanOp.AddNode) patch.ops().get(1);
        assertEquals(new ParamValue.IntV(7), add.node().params().get("width"));
        assertEquals(Set.of("a"), add.node().tags());
        assertEquals("label", add.node().label());
        PlanOp.MoveNode move = (PlanOp.MoveNode) patch.ops().get(3);
        assertEquals(new Anchor.Absolute(new LocalPos(1, 0, 1), new Rot(1, true)), move.anchor());
        PlanOp.AddNode door = (PlanOp.AddNode) patch.ops().get(4);
        assertEquals(new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), door.node().anchor());
    }

    @Test
    void styleAndMoodCallsMergeIntoOneSetStyleAtTheFirstCall() {
        PlanRecorder r = new PlanRecorder();
        r.style("roof", "minecraft:bricks");
        r.part("a", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params(), List.of(), "");
        r.mood("cozy");
        r.style("wall", "minecraft:stone");
        PlanPatch patch = r.toPatch("p", 0, "s");
        assertEquals(2, patch.ops().size());
        PlanOp.SetStyle style = (PlanOp.SetStyle) patch.ops().get(0);
        assertEquals(Map.of("roof", "minecraft:bricks", "wall", "minecraft:stone"), style.style().palette());
        assertEquals(Set.of("cozy"), style.style().moodTags());
    }

    @Test
    void connectMapsViaAndConstraints() {
        PlanRecorder r = new PlanRecorder();
        r.connect("c1", "press-1.power_in", "shaft-2.out", "rotation", null, null);
        r.connect("c2", "a.out", "b.in", "item", List.of(), null);
        r.connect("c3", "a.out", "b.in", "item", List.of("s1"), params("max_length", 12.0, "avoid", List.of("x"), "max_turns", 2.0, "entry_dirs", List.of("up", "north")));
        PlanPatch patch = r.toPatch("p", 0, "s");
        Connection c1 = ((PlanOp.AddConnection) patch.ops().get(0)).connection();
        assertEquals("press-1", c1.from().nodeId());
        assertEquals("power_in", c1.from().port());
        assertEquals(ConnKind.ROTATION, c1.kind());
        assertEquals(Routing.AUTO, c1.routing());
        assertEquals(new Routing.Explicit(List.of()), ((PlanOp.AddConnection) patch.ops().get(1)).connection().routing());
        Connection c3 = ((PlanOp.AddConnection) patch.ops().get(2)).connection();
        assertEquals(new Routing.Explicit(List.of("s1")), c3.routing());
        assertEquals(12, c3.constraints().maxLength());
        assertEquals(2, c3.constraints().maxTurns());
        assertEquals(Set.of("x"), c3.constraints().avoidNodeIds());
        assertEquals(Set.of(Dir6.UP, Dir6.NORTH), c3.constraints().allowedEntryDirs());
    }

    @Test
    void logisticsIsReadFromDicts() {
        PlanRecorder r = new PlanRecorder();
        r.logistics(
                List.of(params("id", "dock-1", "pad", List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0), "clearance", List.of(0.0, 1.0, 0.0, 8.0, 16.0, 8.0),
                        "approach", "north", "ports", List.of("press-1.item_out"), "connectors", List.of("conn-1"))),
                List.of(params("id", "route-1", "from", "dock-1", "to", "dock-1", "waypoints", List.of(List.of(0.0, 5.0, 0.0)), "airship", "mod:airship_a")),
                List.of(params("item", "create:iron_sheet", "per_min", 12.5, "from", "dock-1", "to", "dock-1")));
        PlanOp.SetLogistics l = (PlanOp.SetLogistics) r.toPatch("p", 0, "s").ops().get(0);
        assertEquals(1, l.logistics().docks().size());
        assertEquals(Facing.NORTH, l.logistics().docks().get(0).approach());
        assertEquals("press-1", l.logistics().docks().get(0).linkedPorts().get(0).nodeId());
        assertEquals(12.5, l.logistics().flows().get(0).perMin());
        assertEquals(new LocalPos(0, 5, 0), l.logistics().routes().get(0).waypoints().get(0));
        assertEquals("mod:airship_a", l.logistics().routes().get(0).airshipTemplateId());
    }

    @Test
    void badValuesAreIllegalArgumentsWithAMessage() {
        PlanRecorder r = new PlanRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.site("d", 0, 0, 0, "up", new int[]{0, 0, 0, 1, 1, 1}, "", ""));
        assertThrows(IllegalArgumentException.class, () -> r.site("d", 0, 0, 0, "north", new int[]{5, 0, 0, 1, 1, 1}, "", ""));
        assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false),
                params("k", params("nested", 1.0)), List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "nodot", "b.in", "item", null, null));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "a.o", "b.i", "steam", null, null));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "a.o", "b.i", "item", null, params("bogus", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null, PlanAnchorArgs.surface("w", "top", 0, 0), params(), List.of(), ""));
    }
}
```
`PlanScriptRunnerTest.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanScriptRunnerTest {
    private static PlanScriptRunner.Result run(String... scripts) {
        return PlanScriptRunner.run(List.of(scripts), "patch", 0, "test", PlanRunLimits.DEFAULT);
    }

    private static List<String> codes(PlanScriptRunner.Result r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
    }

    @Test
    void aValidScriptBecomesAPatch() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0, 0, 0], {\"width\": 7})\nprint(\"built\")\n");
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(1, r.patch().ops().size());
        assertEquals(List.of("built"), r.printed());
    }

    @Test
    void severalScriptsAreAppendedInOrder() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0, 0, 0], {})", "wall(\"w\", \"hut\", [0, 0, 0], {\"side\": \"north\"})");
        assertEquals(2, r.patch().ops().size());
        assertEquals("hut", ((PlanOp.AddNode) r.patch().ops().get(0)).node().id());
        assertEquals("w", ((PlanOp.AddNode) r.patch().ops().get(1)).node().id());
    }

    @Test
    void forbiddenCommandsAreReportedBeforeAnythingRuns() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0,0,0], {})\nharvest()\nx = random()\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-FORBIDDEN", "E-SCRIPT-FORBIDDEN"), codes(r));
        assertEquals("harvest", r.issues().get(0).data().get("name"));
        assertEquals("FARM_COMMAND", r.issues().get(0).data().get("reason"));
        assertEquals("NONDETERMINISTIC", r.issues().get(1).data().get("reason"));
    }

    @Test
    void aMixedScriptIsRefused() {
        PlanScriptRunner.Result r = run("wall(\"w\", None, [0,0,0], {\"side\": \"north\"})\nmove(\"north\")");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-FORBIDDEN"), codes(r));
    }

    @Test
    void syntaxErrorsAreSchemaIssuesWithTheScriptNumberAndLine() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0,0,0], {})", "wall(\"w\"\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("2"), r.issues().get(0).message());
    }

    @Test
    void runtimeArgumentErrorsAreSchemaIssues() {
        PlanScriptRunner.Result r = run("wall(\"w\")");
        assertEquals(List.of("E-SCHEMA"), codes(r));
    }

    @Test
    void runawayScriptsHitTheLimit() {
        PlanScriptRunner.Result r = PlanScriptRunner.run(List.of("while True:\n    pass\n"), "p", 0, "t", new PlanRunLimits(1000, 60_000));
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertNull(r.patch());
    }

    @Test
    void anOversizedScriptIsRefused() {
        String big = "# " + "x".repeat(PlanScriptWriter.MAX_SCRIPT_CHARS) + "\n";
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(run(big)));
    }

    @Test
    void theRunnerNeverLeaksAPatchWhenAnyScriptFails() {
        PlanScriptRunner.Result r = run("structure(\"a\", None, [0,0,0], {})", "harvest()");
        assertNull(r.patch());
        assertEquals(IssueCode.E_SCRIPT_FORBIDDEN, r.issues().get(0).code());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.script.*" --console=plain`
Expected: FAIL(`PlanRecorder`・`PlanScriptWriter.MAX_SCRIPT_CHARS`が無い)。`PlanScriptWriter`のこの定数は、Task 21で本体を作る。**先にTask 21のクラスの骨(定数だけを持つ`PlanScriptWriter`)を作って、この課題のテストを通す**(`public static final int MAX_SCRIPT_CHARS = 10_000;`とjavadoc。`DroneControllerBlockEntity.MAX_SCRIPT_CHARS`と同じ値をMinecraft非依存で持つ理由)。

- [ ] **Step 3: 実装する**

`PlanRecorder.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import io.github.khayashi4337.micradrone.lang.PlanApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Records what a construction script says as a {@link PlanPatch}: one command, one operation. Style and mood calls
 * are gathered into a single SetStyle placed where the first of them was called. Malformed values are
 * IllegalArgumentExceptions; the interpreter turns them into script errors with the line.
 */
public final class PlanRecorder implements PlanApi {
    private final List<PlanOp> ops = new ArrayList<>();
    private final TreeMap<String, String> palette = new TreeMap<>();
    private final TreeSet<String> mood = new TreeSet<>();
    private int styleAt = -1;
    private final List<String> printed = new ArrayList<>();

    public PlanPatch toPatch(String patchId, int baseRevision, String stageId) {
        List<PlanOp> out = new ArrayList<>(ops);
        if (styleAt >= 0) {
            out.set(styleAt, new PlanOp.SetStyle(new StyleSpec(palette, mood)));
        }
        return new PlanPatch(patchId, baseRevision, stageId, out);
    }

    public List<String> printed() {
        return List.copyOf(printed);
    }

    private void touchStyle() {
        if (styleAt < 0) {
            styleAt = ops.size();
            ops.add(null); // replaced by the gathered SetStyle in toPatch
        }
    }

    @Override
    public void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId) {
        if (bounds.length != 6) {
            throw new IllegalArgumentException("範囲は整数6個 [minU,minV,minW,maxU,maxV,maxW] です");
        }
        Box box = new Box(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        ops.add(new PlanOp.SetSite(new Site(dimension, new BuildFrame(new IntPos(x, y, z), Facing.parse(facing)), box, terrainDigest, claimId)));
    }

    @Override
    public void style(String role, String material) {
        touchStyle();
        palette.put(role, material);
    }

    @Override
    public void mood(String tag) {
        touchStyle();
        mood.add(tag);
    }

    @Override
    public void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label) {
        ops.add(new PlanOp.AddNode(new PlanNode(id, type, parent, toAnchor(anchor), toParams(params), Set.copyOf(tags), label)));
    }

    @Override
    public void updateParams(String id, Map<String, Object> params) {
        ops.add(new PlanOp.UpdateParams(id, toParams(params)));
    }

    @Override
    public void relocate(String id, PlanAnchorArgs anchor) {
        ops.add(new PlanOp.MoveNode(id, toAnchor(anchor)));
    }

    @Override
    public void removePart(String id) {
        ops.add(new PlanOp.RemoveNode(id));
    }

    @Override
    public void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints) {
        ops.add(new PlanOp.AddConnection(new Connection(id, port(from), port(to), ConnKind.parse(kind),
                via == null ? Routing.AUTO : new Routing.Explicit(via), toConstraints(constraints))));
    }

    @Override
    public void disconnect(String id) {
        ops.add(new PlanOp.RemoveConnection(id));
    }

    @Override
    public void logistics(List<Object> docks, List<Object> routes, List<Object> flows) {
        List<LogisticsPlan.Dock> dockList = new ArrayList<>();
        for (Object o : docks) {
            Map<String, Object> d = dict(o, "ドック");
            List<PortRef> ports = new ArrayList<>();
            for (Object p : list(d.getOrDefault("ports", List.of()), "ports")) {
                ports.add(port(text(p, "ports")));
            }
            dockList.add(new LogisticsPlan.Dock(text(d.get("id"), "id"), box(d.get("pad"), "pad"), box(d.get("clearance"), "clearance"),
                    Facing.parse(text(d.get("approach"), "approach")), ports, strings(d.getOrDefault("connectors", List.of()), "connectors")));
        }
        List<LogisticsPlan.Route> routeList = new ArrayList<>();
        for (Object o : routes) {
            Map<String, Object> r = dict(o, "航路");
            List<LocalPos> pts = new ArrayList<>();
            for (Object p : list(r.getOrDefault("waypoints", List.of()), "waypoints")) {
                List<Object> c = list(p, "waypoints");
                if (c.size() != 3) {
                    throw new IllegalArgumentException("経由点は [u, v, w] です");
                }
                pts.add(new LocalPos(integer(c.get(0)), integer(c.get(1)), integer(c.get(2))));
            }
            routeList.add(new LogisticsPlan.Route(text(r.get("id"), "id"), text(r.get("from"), "from"), text(r.get("to"), "to"), pts,
                    r.get("airship") == null || r.get("airship") instanceof io.github.khayashi4337.micradrone.lang.MicraNone ? null : text(r.get("airship"), "airship")));
        }
        List<LogisticsPlan.CargoFlow> flowList = new ArrayList<>();
        for (Object o : flows) {
            Map<String, Object> f = dict(o, "流れ");
            if (!(f.get("per_min") instanceof Double perMin) || !Double.isFinite(perMin)) {
                throw new IllegalArgumentException("流れの per_min は数が必要です");
            }
            flowList.add(new LogisticsPlan.CargoFlow(text(f.get("item"), "item"), perMin, text(f.get("from"), "from"), text(f.get("to"), "to")));
        }
        ops.add(new PlanOp.SetLogistics(new LogisticsPlan(dockList, routeList, flowList)));
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }

    // ------------------------------------------------------------------ conversions

    private static Anchor toAnchor(PlanAnchorArgs a) {
        return switch (a.kind()) {
            case ABSOLUTE -> new Anchor.Absolute(new LocalPos(a.u(), a.v(), a.w()), new Rot(a.turns(), a.mirror()));
            case SURFACE -> new Anchor.OnSurface(a.target(), Side.parse(a.side()), a.u(), a.v());
            case SLOT -> new Anchor.InSlot(a.slot(), new Rot(a.turns(), a.mirror()));
        };
    }

    private static Map<String, ParamValue> toParams(Map<String, Object> params) {
        Map<String, ParamValue> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out.put(e.getKey(), ParamValue.fromTree(e.getValue()));
        }
        return out;
    }

    private static PortRef port(String text) {
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1) {
            throw new IllegalArgumentException("\"ノードID.ポート名\" の形にしてください: " + text);
        }
        return new PortRef(text.substring(0, dot), text.substring(dot + 1));
    }

    private static Constraints toConstraints(Map<String, Object> c) {
        if (c == null) {
            return Constraints.NONE;
        }
        Integer maxLength = null;
        Integer maxTurns = null;
        Set<String> avoid = new TreeSet<>();
        Set<Dir6> dirs = new TreeSet<>();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            switch (e.getKey()) {
                case "max_length" -> maxLength = integer(e.getValue());
                case "max_turns" -> maxTurns = integer(e.getValue());
                case "avoid" -> avoid.addAll(strings(e.getValue(), "avoid"));
                case "entry_dirs" -> {
                    for (String d : strings(e.getValue(), "entry_dirs")) {
                        dirs.add(Dir6.parse(d));
                    }
                }
                default -> throw new IllegalArgumentException("constraints に「" + e.getKey()
                        + "」はありません(max_length・avoid・max_turns・entry_dirs)");
            }
        }
        return new Constraints(maxLength, avoid, maxTurns, dirs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dict(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(what + "は辞書({…})で書いてください");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException(what + "はリストで書いてください");
    }

    private static String text(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(what + "は文字列が必要です");
    }

    private static List<String> strings(Object o, String what) {
        List<String> out = new ArrayList<>();
        for (Object item : list(o, what)) {
            out.add(text(item, what));
        }
        return out;
    }

    private static int integer(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        throw new IllegalArgumentException("整数が必要です(" + o + ")");
    }

    private static Box box(Object o, String what) {
        List<Object> l = list(o, what);
        if (l.size() != 6) {
            throw new IllegalArgumentException(what + "は整数6個です");
        }
        return new Box(integer(l.get(0)), integer(l.get(1)), integer(l.get(2)), integer(l.get(3)), integer(l.get(4)), integer(l.get(5)));
    }
}
```
`PlanScriptProfile.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic profile of construction scripts (D-15, allow-list): only construction commands, control flow and
 * pure helpers. A denied name is refused before anything runs, so the same script always gives the same PlanPatch.
 * Being an allow list, a command added to CommandNames.ALL later is NOT usable here unless it is added on purpose.
 */
public final class PlanScriptProfile {
    public enum Reason { NONDETERMINISTIC, FARM_COMMAND, UNKNOWN }

    public record Violation(int line, String name, Reason reason) {
    }

    public enum Kind { PLAN, FARM, MIXED, NEUTRAL }

    private static final Set<String> NONDETERMINISTIC = Set.of("random", "create_task", "semaphore", "attach_isr", "raise_interrupt",
            "sleep_ticks", "post", "wait");

    private PlanScriptProfile() {
    }

    public static List<Violation> check(List<Stmt> program) {
        Set<String> own = userFunctions(program);
        List<Violation> out = new ArrayList<>();
        walk(program, own, out, null);
        return out;
    }

    public static Kind classify(List<Stmt> program) {
        Set<String> own = userFunctions(program);
        boolean plan = false;
        boolean farm = false;
        List<String> called = new ArrayList<>();
        walk(program, own, null, called);
        for (String name : called) {
            if (CommandNames.PLAN.contains(name)) {
                plan = true;
            } else if (CommandNames.ALL.contains(name) && !CommandNames.PLAN_HELPERS.contains(name)) {
                farm = true;
            }
        }
        return plan && farm ? Kind.MIXED : plan ? Kind.PLAN : farm ? Kind.FARM : Kind.NEUTRAL;
    }

    private static Set<String> userFunctions(List<Stmt> program) {
        Set<String> names = new HashSet<>();
        for (Stmt s : program) {
            if (s instanceof Stmt.FunctionDef f) {
                names.add(f.name());
            }
        }
        return names;
    }

    private static void walk(List<Stmt> stmts, Set<String> own, List<Violation> out, List<String> called) {
        for (Stmt s : stmts) {
            switch (s) {
                case Stmt.AssignStmt a -> expr(a.value(), own, out, called);
                case Stmt.IndexAssignStmt a -> {
                    expr(a.target(), own, out, called);
                    expr(a.index(), own, out, called);
                    expr(a.value(), own, out, called);
                }
                case Stmt.ExprStmt e -> expr(e.expr(), own, out, called);
                case Stmt.IfStmt i -> {
                    for (Stmt.IfStmt.Branch b : i.branches()) {
                        expr(b.condition(), own, out, called);
                        walk(b.block(), own, out, called);
                    }
                    if (i.elseBlock() != null) {
                        walk(i.elseBlock(), own, out, called);
                    }
                }
                case Stmt.WhileStmt w -> {
                    expr(w.condition(), own, out, called);
                    walk(w.block(), own, out, called);
                }
                case Stmt.ForStmt f -> {
                    expr(f.rangeExpr(), own, out, called);
                    walk(f.block(), own, out, called);
                }
                case Stmt.FunctionDef f -> walk(f.body(), own, out, called);
                case Stmt.ReturnStmt r -> {
                    if (r.value() != null) {
                        expr(r.value(), own, out, called);
                    }
                }
                case Stmt.BreakStmt b -> { }
                case Stmt.ContinueStmt c -> { }
                case Stmt.PassStmt p -> { }
            }
        }
    }

    private static void expr(Expr e, Set<String> own, List<Violation> out, List<String> called) {
        switch (e) {
            case Expr.Call c -> {
                if (called != null) {
                    called.add(c.name());
                }
                if (out != null) {
                    classify(c.name(), c.line(), own, out);
                }
                for (Expr a : c.args()) {
                    expr(a, own, out, called);
                }
            }
            case Expr.MethodCall m -> {
                if (out != null && (m.name().equals("post") || m.name().equals("wait"))) {
                    out.add(new Violation(m.line(), m.name(), Reason.NONDETERMINISTIC));
                }
                expr(m.target(), own, out, called);
                for (Expr a : m.args()) {
                    expr(a, own, out, called);
                }
            }
            case Expr.Unary u -> expr(u.operand(), own, out, called);
            case Expr.Binary b -> {
                expr(b.left(), own, out, called);
                expr(b.right(), own, out, called);
            }
            case Expr.ListLit l -> l.elements().forEach(x -> expr(x, own, out, called));
            case Expr.SetLit s -> s.elements().forEach(x -> expr(x, own, out, called));
            case Expr.DictLit d -> {
                d.keys().forEach(x -> expr(x, own, out, called));
                d.values().forEach(x -> expr(x, own, out, called));
            }
            case Expr.Index i -> {
                expr(i.target(), own, out, called);
                expr(i.index(), own, out, called);
            }
            case Expr.NumberLit n -> { }
            case Expr.StringLit s -> { }
            case Expr.BoolLit b -> { }
            case Expr.NoneLit n -> { }
            case Expr.VarRef v -> { }
        }
    }

    private static void classify(String name, int line, Set<String> own, List<Violation> out) {
        if (own.contains(name) || CommandNames.PLAN.contains(name) || CommandNames.PLAN_HELPERS.contains(name)) {
            return;
        }
        if (NONDETERMINISTIC.contains(name)) {
            out.add(new Violation(line, name, Reason.NONDETERMINISTIC));
        } else if (CommandNames.ALL.contains(name)) {
            out.add(new Violation(line, name, Reason.FARM_COMMAND));
        } else {
            out.add(new Violation(line, name, Reason.UNKNOWN));
        }
    }
}
```
`PlanScriptRunner.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.PlanLimitException;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks and runs construction scripts, in order, into one {@link PlanPatch}. Any problem means no patch at all. */
public final class PlanScriptRunner {
    public record Result(PlanPatch patch, List<Issue> issues, List<String> printed) {
        public Result {
            issues = List.copyOf(issues);
            printed = List.copyOf(printed);
        }

        public boolean ok() {
            return patch != null;
        }
    }

    private PlanScriptRunner() {
    }

    public static Result run(List<String> scripts, String patchId, int baseRevision, String stageId, PlanRunLimits limits) {
        List<Issue> issues = new ArrayList<>();
        PlanRecorder recorder = new PlanRecorder();
        for (int n = 0; n < scripts.size(); n++) {
            String label = "スクリプト" + (n + 1);
            String source = scripts.get(n);
            if (source.length() > PlanScriptWriter.MAX_SCRIPT_CHARS) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "length:" + n, List.of(), label + "が長すぎます(" + source.length()
                        + "字 > " + PlanScriptWriter.MAX_SCRIPT_CHARS + "字)。複数のスクリプトに分けてください"));
                continue;
            }
            List<Stmt> program;
            try {
                program = new Parser(new Lexer(source).scan()).parseProgram();
            } catch (MicraLangException e) {
                issues.add(Issue.of(IssueCode.E_SCHEMA, "syntax:" + n, List.of(), label + "の構文エラー: " + e.getMessage()));
                continue;
            }
            List<PlanScriptProfile.Violation> violations = PlanScriptProfile.check(program);
            for (PlanScriptProfile.Violation v : violations) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_FORBIDDEN, v.name() + ":" + v.line(), List.of(),
                        label + " " + v.line() + "行目: 建設のスクリプトでは" + v.name() + "は使えません(" + describe(v.reason()) + ")",
                        Map.of("name", v.name(), "reason", v.reason().name(), "line", String.valueOf(v.line())), List.of()));
            }
            if (!violations.isEmpty()) {
                continue;
            }
            try {
                new Interpreter(recorder, limits).run(program);
            } catch (PlanLimitException e) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "run:" + n, List.of(), label + "が実行の上限を超えました: " + e.getMessage()));
            } catch (MicraLangException e) {
                issues.add(Issue.of(IssueCode.E_SCHEMA, "run:" + n, List.of(), label + "の実行エラー: " + e.getMessage()));
            }
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new Result(null, issues, recorder.printed());
        }
        return new Result(recorder.toPatch(patchId, baseRevision, stageId), issues, recorder.printed());
    }

    private static String describe(PlanScriptProfile.Reason reason) {
        return switch (reason) {
            case NONDETERMINISTIC -> "実行のたびに結果が変わるため";
            case FARM_COMMAND -> "畑の命令のため。畑と建設は同じスクリプトに混ぜられません";
            case UNKNOWN -> "知らない命令です";
        };
    }
}
```
`PlanScriptWriter.java`(骨だけ。Task 21で本体):
```java
package io.github.khayashi4337.micradrone.build.script;

public final class PlanScriptWriter {
    /**
     * Same value as DroneControllerBlockEntity.MAX_SCRIPT_CHARS, repeated here so this Minecraft-free core does not depend
     * on that class; a test compares the two source texts.
     */
    public static final int MAX_SCRIPT_CHARS = 10_000;

    private PlanScriptWriter() {
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --console=plain`
Expected: PASS(既存の47ファイル+新規)。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: PlanRecorder(スクリプト→PlanPatch)・許可リスト方式の静的検査(PlanScriptProfile)・PlanScriptRunnerを追加(自然言語→工場建設 P3 Task 20)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```


---

### Task 21: `PlanScriptWriter`(`SemanticPlan`→スクリプト)と往復の保証

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/build/script/PlanScriptWriter.java`(Task 20の骨に本体を足す)
- Modify: `src/test/java/io/github/khayashi4337/micradrone/build/TestParts.java`(`test:dial`を足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/build/script/PlanScriptRoundTripTest.java`

**Interfaces:**
- Consumes: Task 5の型、Task 19・20の`PlanScriptRunner`・`CommandNames`
- Produces: `PlanScriptWriter.write(SemanticPlan) → List<String>`・`write(SemanticPlan, int maxChars)`(1本ごとに`maxChars`字以内。1つの文が入らなければ`IllegalStateException`)

**出力の形(テストで固定):** 各スクリプトは、先頭に`# 建設スクリプト <i>/<n>(計画 <planId>)`の1行(planIdは40字までに切り、改行は空白に)。文は1行ずつ、順番は`site`→`style`(パレットの辞書順)→`mood`(辞書順)→ノード(計画の並び。親は子より先)→接続→`logistics`。部品は、`micra:`の部品なら`<部品名>(id, parent, anchor, params[, tags[, label]])`、他は`part(id, type, parent, anchor, params[, tags[, label]])`。`parent`は`None`または`"id"`。`anchor`は`Rot`が無ければ`[u, v, w]`、有れば`[u, v, w, 回転数, True/False]`、`OnSurface`は`["surface", "壁", "outer", u, v]`、`InSlot`は`["slot", "id", 回転数, True/False]`。`params`は辞書順の`{"名前": 値}`(整数は`7`、小数は10進の`12.5`、真偽は`True`/`False`、文字列は`"…"`(`\`・`"`・改行・タブをエスケープ)、リストは`[…]`)。`tags`・`label`は、どちらかが空でなければ`tags`(リスト)を出し、`label`が空でなければ続ける。`connect(id, "ノード.ポート", "ノード.ポート", "種類"`に、`Explicit`または`constraints`が有れば、`, via`(`Auto`なら`None`、`Explicit`ならリスト。空のリストも)、`constraints`が有れば`, {…}`(有る欄だけ: `avoid`・`entry_dirs`・`max_length`・`max_turns`)。1本の長さの上限を超える前に、次のスクリプトへ分ける(`HEADER_RESERVE`=120字を見込む)。

- [ ] **Step 1: 失敗するテストを書く**

`TestParts.java`に、`registry()`の`test:motor`の後へ足す:
```java
        b.register(PartType.builder("test:dial", PartCategory.POWER).displayNameKey("t.dial")
                .params(new io.github.khayashi4337.micradrone.build.parts.ParamSpec("speed",
                        io.github.khayashi4337.micradrone.build.parts.ParamType.NUM, "rpm",
                        new io.github.khayashi4337.micradrone.build.model.ParamValue.NumV(0),
                        new io.github.khayashi4337.micradrone.build.model.ParamValue.NumV(256),
                        new io.github.khayashi4337.micradrone.build.model.ParamValue.NumV(16), java.util.List.of(), 0)).build());
```
`PlanScriptRoundTripTest.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.RandomParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanScriptRoundTripTest {
    private final PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TestParts.bundle());

    private SemanticPlan build(List<PlanOp> ops) {
        PatchResult r = patcher.apply(SemanticPlan.empty("rt-plan"), new PlanPatch("p", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    /** plan -> scripts -> recorder -> plan again: the content hash must not move. Returns the scripts. */
    private List<String> assertRoundTrip(SemanticPlan plan) {
        List<String> scripts = PlanScriptWriter.write(plan);
        for (String s : scripts) {
            assertTrue(s.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS, "script length " + s.length());
        }
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "test", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString() + "\n" + String.join("\n---\n", scripts));
        PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
        assertTrue(applied.ok(), applied.issues().toString());
        assertEquals(plan.contentHash(), applied.plan().contentHash(), String.join("\n---\n", scripts));
        assertEquals(plan.nodes().size(), applied.plan().nodes().size());
        assertEquals(plan.connections().size(), applied.plan().connections().size());
        return scripts;
    }

    private static PlanNode node(String id, String type, String parent, Anchor anchor, Map<String, ParamValue> params, Set<String> tags, String label) {
        return new PlanNode(id, type, parent, anchor, params, tags, label);
    }

    private static Anchor abs(int u, int v, int w) {
        return new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE);
    }

    private List<PlanOp> richOps() {
        Site site = new Site("minecraft:overworld", new BuildFrame(new IntPos(-100, 64, 200), Facing.SOUTH), new Box(-5, -5, -5, 30, 30, 30), "digest-1", "claim-1");
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.WEST,
                        List.of(new PortRef("press-1", "item_out")), List.of("conn-1", "conn-2"))),
                List.of(new LogisticsPlan.Route("route-1", "dock-1", "dock-1", List.of(new LocalPos(0, 5, 0), new LocalPos(-9, 5, 9)), "mod:airship_a"),
                        new LogisticsPlan.Route("route-2", "dock-1", "dock-1", List.of(), null)),
                List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", 12.5, "dock-1", "dock-1")));
        Connection autoConn = new Connection("c-auto", new PortRef("m", "out"), new PortRef("press-1", "power_in"), ConnKind.ROTATION,
                Routing.AUTO, Constraints.NONE);
        Connection directConn = new Connection("c-direct", new PortRef("m", "out"), new PortRef("s1", "in"), ConnKind.ROTATION,
                new Routing.Explicit(List.of()), Constraints.NONE);
        Connection viaConn = new Connection("c-via", new PortRef("s1", "out"), new PortRef("press-1", "power_in"), ConnKind.ROTATION,
                new Routing.Explicit(List.of("s1", "s2")), new Constraints(20, Set.of("wall-n", "hut"), 3, Set.of(Dir6.UP, Dir6.NORTH)));
        return List.of(
                new PlanOp.SetSite(site),
                new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:red_nether_bricks", "wall", "minecraft:stone_bricks"), Set.of("cozy", "warm"))),
                new PlanOp.AddNode(node("hut", "micra:structure", null, abs(0, 0, 0), Map.of("width", new IntV(7), "depth", new NumV(9.0)),
                        Set.of("main", "b"), "小屋 \"one\"\n\ttab \\ back 🏠")),
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", abs(0, 0, 0), Map.of("side", new StrV("north"), "material", new StrV("wall")), Set.of(), "")),
                new PlanOp.AddNode(node("door-1", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.INNER, 3, 0),
                        Map.of("kind", new StrV("double"), "hinge", new StrV("right")), Set.of(), "")),
                new PlanOp.AddNode(node("win-1", "micra:window", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 1, 1),
                        Map.of("kind", new StrV("arch"), "lattice", new BoolV(true)), Set.of(), "")),
                new PlanOp.AddNode(node("sign-1", "micra:sign", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 5, 1),
                        Map.of("text", new StrV("say \"hi\"|back\\slash|日本語")), Set.of(), "")),
                new PlanOp.AddNode(node("floor-1", "micra:floor", "hut", abs(0, 0, 0),
                        Map.of("holes", new ListV(List.of(new IntV(1), new IntV(1), new IntV(2), new IntV(2)))), Set.of(), "")),
                new PlanOp.AddNode(node("m", "test:motor", null, abs(-3, 1, 2), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("s1", "test:shaft", null, new Anchor.Absolute(new LocalPos(-2, 1, 2), new Rot(3, true)), Map.of(), Set.of("a", "z"), "")),
                new PlanOp.AddNode(node("s2", "test:shaft", null, abs(-1, 1, 2), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("press-1", "test:press", null, abs(0, 1, 2), Map.of(), Set.of(), "the press")),
                new PlanOp.AddNode(node("dial", "test:dial", null, abs(4, 0, 0), Map.of("speed", new NumV(12.5)), Set.of(), "")),
                new PlanOp.AddNode(node("dial-2", "test:dial", null, abs(5, 0, 0), Map.of("speed", new NumV(2.0)), Set.of(), "")),
                new PlanOp.AddNode(node("line-1", "mod:test_line", null, new Anchor.InSlot("slot-a", new Rot(2, false)), Map.of(), Set.of(), "")),
                new PlanOp.AddConnection(autoConn),
                new PlanOp.AddConnection(directConn),
                new PlanOp.AddConnection(viaConn),
                new PlanOp.SetLogistics(logistics));
    }

    @Test
    void aRichPlanRoundTripsWithTheSameContentHash() {
        SemanticPlan plan = build(richOps());
        List<String> scripts = assertRoundTrip(plan);
        assertEquals(1, scripts.size());
        String text = scripts.get(0);
        assertTrue(text.startsWith("# 建設スクリプト 1/1(計画 rt-plan)\n"), text);
        assertTrue(text.contains("structure(\"hut\", None, [0, 0, 0], {"), "micra parts are written as their own command");
        assertTrue(text.contains("part(\"m\", \"test:motor\", None, [-3, 1, 2], {})"), "other parts use part()");
        assertTrue(text.contains("connect(\"c-direct\", \"m.out\", \"s1.in\", \"rotation\", [])"), "an empty via list means explicit, not auto");
        assertTrue(text.contains("connect(\"c-auto\", \"m.out\", \"press-1.power_in\", \"rotation\")"), "auto has no via");
        assertTrue(text.contains("[\"surface\", \"wall-n\", \"inner\", 3, 0]"));
        assertTrue(text.contains("[\"slot\", \"slot-a\", 2, False]"));
        assertTrue(text.contains("\\\"one\\\"\\n\\ttab"), "special characters are escaped");
    }

    @Test
    void writingTwiceGivesTheSameText() {
        SemanticPlan plan = build(richOps());
        assertEquals(PlanScriptWriter.write(plan), PlanScriptWriter.write(plan));
    }

    @Test
    void anEmptyPlanAndASiteOnlyPlanRoundTrip() {
        assertRoundTrip(SemanticPlan.empty("rt-plan"));
        assertRoundTrip(build(List.of(richOps().get(0))));
        assertEquals(1, PlanScriptWriter.write(SemanticPlan.empty("rt-plan")).size());
    }

    @Test
    void bigPlansSplitIntoSeveralScriptsThatEachFit() {
        List<PlanOp> ops = new ArrayList<>();
        ops.add(richOps().get(0));
        for (int i = 0; i < 400; i++) {
            ops.add(new PlanOp.AddNode(node("post-" + i, "micra:pillar", null, abs(i * 3, 0, 0), Map.of("height", new IntV(3 + i % 5)),
                    Set.of("row-" + i % 7), "a fairly long label to make each statement heavy #" + i)));
        }
        SemanticPlan plan = build(ops);
        List<String> scripts = assertRoundTrip(plan);
        assertTrue(scripts.size() >= 3, "expected several scripts but got " + scripts.size());
        assertTrue(scripts.get(0).startsWith("# 建設スクリプト 1/" + scripts.size()));
        assertTrue(scripts.get(0).contains("site("), "the site comes first");
    }

    @Test
    void aSmallLimitSplitsEverythingButKeepsTheOrder() {
        SemanticPlan plan = build(richOps());
        List<String> scripts = PlanScriptWriter.write(plan, 700);
        assertTrue(scripts.size() > 3);
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(plan.contentHash(), patcher.apply(SemanticPlan.empty("rt-plan"), r.patch()).plan().contentHash());
    }

    @Test
    void aStatementLongerThanAScriptIsRefusedNotTruncated() {
        SemanticPlan plan = build(List.of(new PlanOp.AddNode(node("a", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of(),
                "x".repeat(PlanScriptWriter.MAX_SCRIPT_CHARS)))));
        assertThrows(IllegalStateException.class, () -> PlanScriptWriter.write(plan));
    }

    @Test
    void editingTheScriptChangesThePlan() {
        SemanticPlan plan = build(richOps());
        String edited = PlanScriptWriter.write(plan).get(0).replace("\"width\": 7", "\"width\": 9");
        PlanScriptRunner.Result r = PlanScriptRunner.run(List.of(edited), "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        SemanticPlan changed = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch()).plan();
        assertNotEquals(plan.contentHash(), changed.contentHash());
        assertEquals(new IntV(9), changed.node("hut").orElseThrow().params().get("width"));
    }

    private static String trickyText(Random rnd) {
        String alphabet = "abc \"\\\n\t日本🏠'#|_-.0";
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(12);
        for (int i = 0; i < n; ) {
            int cp = alphabet.codePointAt(rnd.nextInt(alphabet.length()));
            sb.appendCodePoint(cp);
            i++;
        }
        return sb.toString();
    }

    @Test
    void randomPlansWithTrickyLabelsAndTagsRoundTrip() {
        for (long seed = 1; seed <= 100; seed++) {
            Random rnd = new Random(seed);
            List<PlanOp> ops = new ArrayList<>();
            ops.add(richOps().get(0));
            int k = 0;
            for (PlanNode n : RandomParts.nodes(seed)) {
                Set<String> tags = new java.util.TreeSet<>();
                for (int t = rnd.nextInt(3); t > 0; t--) {
                    tags.add(trickyText(rnd));
                }
                ops.add(new PlanOp.AddNode(node(n.id(), n.type(), null, n.anchor(), n.params(), tags, trickyText(rnd))));
                k++;
            }
            assertRoundTrip(build(ops));
        }
    }

    @Test
    void theScriptLengthConstantMatchesTheOneTheGameEnforces() throws IOException {
        Path source = Path.of("src/main/java/io/github/khayashi4337/micradrone/drone/DroneControllerBlockEntity.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("MAX_SCRIPT_CHARS = " + PlanScriptWriter.MAX_SCRIPT_CHARS + ";"),
                "keep PlanScriptWriter.MAX_SCRIPT_CHARS equal to DroneControllerBlockEntity.MAX_SCRIPT_CHARS");
        assertFalse(PlanScriptWriter.write(SemanticPlan.empty("p")).isEmpty());
    }
}
```
(`RandomParts`は`build.compile`の`public`クラスにしておく: Task 16の`RandomParts`を`public final class`にし、`nodes`・`FREESTANDING`・`SPACING`を`public`にする。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.script.PlanScriptRoundTripTest" --console=plain`
Expected: FAIL(`PlanScriptWriter.write`が無い)。

- [ ] **Step 3: 実装する**

`PlanScriptWriter.java`:
```java
package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a plan as construction scripts a person can read and edit: one statement per line, one command per plan
 * operation. Running the scripts and applying the result to an empty plan gives back the same content hash (D-2).
 * Big plans are cut into several scripts (each within {@link #MAX_SCRIPT_CHARS}); they must be run in order.
 */
public final class PlanScriptWriter {
    /**
     * Same value as DroneControllerBlockEntity.MAX_SCRIPT_CHARS, repeated here so this Minecraft-free core does not depend
     * on that class; a test compares the two source texts.
     */
    public static final int MAX_SCRIPT_CHARS = 10_000;

    private static final int HEADER_RESERVE = 120;
    private static final int PLAN_ID_COMMENT_CHARS = 40;
    private static final String MICRA_PREFIX = "micra:";

    private PlanScriptWriter() {
    }

    public static List<String> write(SemanticPlan plan) {
        return write(plan, MAX_SCRIPT_CHARS);
    }

    public static List<String> write(SemanticPlan plan, int maxChars) {
        return pack(plan.planId(), statements(plan), maxChars);
    }

    // ------------------------------------------------------------------ statements

    private static List<String> statements(SemanticPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan.site() != null) {
            out.add(siteLine(plan.site()));
        }
        for (Map.Entry<String, String> e : plan.style().palette().entrySet()) {
            out.add("style(" + q(e.getKey()) + ", " + q(e.getValue()) + ")");
        }
        for (String tag : plan.style().moodTags()) {
            out.add("mood(" + q(tag) + ")");
        }
        for (PlanNode n : plan.nodes()) {
            out.add(nodeLine(n));
        }
        for (Connection c : plan.connections()) {
            out.add(connectLine(c));
        }
        if (plan.logistics() != null) {
            out.add(logisticsLine(plan.logistics()));
        }
        return out;
    }

    private static String siteLine(Site s) {
        StringBuilder sb = new StringBuilder("site(").append(q(s.dimension())).append(", ")
                .append(s.frame().origin().x()).append(", ").append(s.frame().origin().y()).append(", ").append(s.frame().origin().z())
                .append(", ").append(q(s.frame().facing().lower())).append(", ").append(box(s.localBounds()));
        if (!s.terrainDigest().isEmpty() || !s.claimId().isEmpty()) {
            sb.append(", ").append(q(s.terrainDigest())).append(", ").append(q(s.claimId()));
        }
        return sb.append(")").toString();
    }

    private static String nodeLine(PlanNode n) {
        boolean sugar = n.type().startsWith(MICRA_PREFIX) && CommandNames.PLAN_PART_COMMANDS.contains(n.type().substring(MICRA_PREFIX.length()));
        StringBuilder sb = new StringBuilder();
        if (sugar) {
            sb.append(n.type().substring(MICRA_PREFIX.length())).append("(").append(q(n.id()));
        } else {
            sb.append("part(").append(q(n.id())).append(", ").append(q(n.type()));
        }
        sb.append(", ").append(n.parent() == null ? "None" : q(n.parent())).append(", ").append(anchor(n.anchor())).append(", ")
                .append(params(n.params()));
        if (!n.tags().isEmpty() || !n.label().isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (String t : n.tags()) {
                tags.add(q(t));
            }
            sb.append(", [").append(String.join(", ", tags)).append("]");
            if (!n.label().isEmpty()) {
                sb.append(", ").append(q(n.label()));
            }
        }
        return sb.append(")").toString();
    }

    private static String connectLine(Connection c) {
        StringBuilder sb = new StringBuilder("connect(").append(q(c.id())).append(", ").append(q(port(c.from()))).append(", ")
                .append(q(port(c.to()))).append(", ").append(q(c.kind().lower()));
        boolean explicit = c.routing() instanceof Routing.Explicit;
        boolean constrained = !c.constraints().equals(Constraints.NONE);
        if (explicit || constrained) {
            sb.append(", ");
            if (c.routing() instanceof Routing.Explicit e) {
                List<String> via = new ArrayList<>();
                for (String v : e.viaNodeIds()) {
                    via.add(q(v));
                }
                sb.append("[").append(String.join(", ", via)).append("]");
            } else {
                sb.append("None");
            }
            if (constrained) {
                sb.append(", ").append(constraints(c.constraints()));
            }
        }
        return sb.append(")").toString();
    }

    private static String constraints(Constraints c) {
        TreeMap<String, String> parts = new TreeMap<>();
        if (!c.avoidNodeIds().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (String id : c.avoidNodeIds()) {
                ids.add(q(id));
            }
            parts.put("avoid", "[" + String.join(", ", ids) + "]");
        }
        if (!c.allowedEntryDirs().isEmpty()) {
            List<String> dirs = new ArrayList<>();
            for (Dir6 d : c.allowedEntryDirs()) {
                dirs.add(q(d.lower()));
            }
            parts.put("entry_dirs", "[" + String.join(", ", dirs) + "]");
        }
        if (c.maxLength() != null) {
            parts.put("max_length", String.valueOf(c.maxLength()));
        }
        if (c.maxTurns() != null) {
            parts.put("max_turns", String.valueOf(c.maxTurns()));
        }
        return dict(parts);
    }

    private static String logisticsLine(LogisticsPlan l) {
        List<String> docks = new ArrayList<>();
        for (LogisticsPlan.Dock d : l.docks()) {
            TreeMap<String, String> m = new TreeMap<>();
            m.put("id", q(d.id()));
            m.put("pad", box(d.padBox()));
            m.put("clearance", box(d.clearanceBox()));
            m.put("approach", q(d.approach().lower()));
            List<String> ports = new ArrayList<>();
            for (PortRef p : d.linkedPorts()) {
                ports.add(q(port(p)));
            }
            m.put("ports", "[" + String.join(", ", ports) + "]");
            List<String> connectors = new ArrayList<>();
            for (String c : d.dockingConnectorNodeIds()) {
                connectors.add(q(c));
            }
            m.put("connectors", "[" + String.join(", ", connectors) + "]");
            docks.add(dict(m));
        }
        List<String> routes = new ArrayList<>();
        for (LogisticsPlan.Route r : l.routes()) {
            TreeMap<String, String> m = new TreeMap<>();
            m.put("id", q(r.id()));
            m.put("from", q(r.fromDock()));
            m.put("to", q(r.toDock()));
            List<String> pts = new ArrayList<>();
            for (LocalPos p : r.waypoints()) {
                pts.add("[" + p.u() + ", " + p.v() + ", " + p.w() + "]");
            }
            m.put("waypoints", "[" + String.join(", ", pts) + "]");
            m.put("airship", r.airshipTemplateId() == null ? "None" : q(r.airshipTemplateId()));
            routes.add(dict(m));
        }
        List<String> flows = new ArrayList<>();
        for (LogisticsPlan.CargoFlow f : l.flows()) {
            TreeMap<String, String> m = new TreeMap<>();
            m.put("item", q(f.itemId()));
            m.put("per_min", number(f.perMin()));
            m.put("from", q(f.fromDock()));
            m.put("to", q(f.toDock()));
            flows.add(dict(m));
        }
        return "logistics([" + String.join(", ", docks) + "], [" + String.join(", ", routes) + "], [" + String.join(", ", flows) + "])";
    }

    // ------------------------------------------------------------------ pieces

    private static String port(PortRef p) {
        return p.nodeId() + "." + p.port();
    }

    private static String box(Box b) {
        return "[" + b.minA() + ", " + b.minB() + ", " + b.minC() + ", " + b.maxA() + ", " + b.maxB() + ", " + b.maxC() + "]";
    }

    private static String anchor(Anchor a) {
        return switch (a) {
            case Anchor.Absolute abs -> {
                String pos = abs.pos().u() + ", " + abs.pos().v() + ", " + abs.pos().w();
                yield abs.rot().equals(Rot.NONE) ? "[" + pos + "]" : "[" + pos + ", " + abs.rot().quarterTurns() + ", " + bool(abs.rot().mirror()) + "]";
            }
            case Anchor.OnSurface s -> "[\"surface\", " + q(s.nodeId()) + ", " + q(s.side().lower()) + ", " + s.u() + ", " + s.v() + "]";
            case Anchor.InSlot s -> "[\"slot\", " + q(s.slotId()) + ", " + s.rot().quarterTurns() + ", " + bool(s.rot().mirror()) + "]";
        };
    }

    private static String params(Map<String, ParamValue> params) {
        TreeMap<String, String> m = new TreeMap<>();
        for (Map.Entry<String, ParamValue> e : params.entrySet()) {
            m.put(e.getKey(), value(e.getValue()));
        }
        return dict(m);
    }

    private static String dict(TreeMap<String, String> entries) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            parts.add(q(e.getKey()) + ": " + e.getValue());
        }
        return "{" + String.join(", ", parts) + "}";
    }

    private static String value(ParamValue v) {
        return switch (v) {
            case ParamValue.IntV i -> String.valueOf(i.value());
            case ParamValue.NumV n -> number(n.value());
            case ParamValue.BoolV b -> bool(b.value());
            case ParamValue.StrV s -> q(s.value());
            case ParamValue.EnumV e -> q(e.value());
            case ParamValue.MaterialV m -> q(m.value());
            case ParamValue.ListV l -> {
                List<String> items = new ArrayList<>();
                for (ParamValue item : l.value()) {
                    items.add(value(item));
                }
                yield "[" + String.join(", ", items) + "]";
            }
        };
    }

    /** A plain decimal the script language can read back (it has no exponent form). */
    private static String number(double d) {
        return d == 0 ? "0" : BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static String bool(boolean b) {
        return b ? "True" : "False";
    }

    /** A string literal the script lexer reads back unchanged: it understands \\ \" \n \t, everything else is literal. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ packing

    private static List<String> pack(String planId, List<String> statements, int maxChars) {
        int budget = maxChars - HEADER_RESERVE;
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int size = 0;
        for (String line : statements) {
            int add = line.length() + 1;
            if (add > budget) {
                throw new IllegalStateException("one statement is longer than a script may be (" + add + " > " + budget + "): "
                        + line.substring(0, Math.min(60, line.length())) + "...");
            }
            if (size + add > budget && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(line);
            size += add;
        }
        if (!current.isEmpty() || chunks.isEmpty()) {
            chunks.add(current);
        }
        String id = planId.replace('\n', ' ').replace('\r', ' ');
        if (id.length() > PLAN_ID_COMMENT_CHARS) {
            id = id.substring(0, PLAN_ID_COMMENT_CHARS);
        }
        List<String> scripts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String header = "# 建設スクリプト " + (i + 1) + "/" + chunks.size() + "(計画 " + id + ")\n";
            scripts.add(header + String.join("\n", chunks.get(i)) + (chunks.get(i).isEmpty() ? "" : "\n"));
        }
        return scripts;
    }
}
```
(`HEADER_RESERVE`=120で、ヘッダは`# 建設スクリプト 99/99(計画 ` + 40字 + `)` + 改行=約70字なので足りる。空の計画のスクリプトは、ヘッダの1行だけで、`Lexer`はコメント行だけを空のプログラムとして読む。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.build.*" --console=plain`
Expected: PASS(往復・複数分割・100個の乱数の計画・特殊文字)。往復が一致しない場合は、`assertRoundTrip`の失敗メッセージに出るスクリプトの全文を読み、(a)エスケープ、(b)数の書式(`NumV(2.0)`は`2`になり、`NUM`の仕様で`NumV`に戻る)、(c)`Explicit`の空リスト、の3つを先に疑う。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/build src/test/java/io/github/khayashi4337/micradrone/build
git commit -m "$(cat <<'EOF'
feat: PlanScriptWriter(SemanticPlan→建設スクリプト。複数分割)を追加し、往復でcontentHashが変わらないことを保証(自然言語→工場建設 P3 Task 21)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 22: ゲーム内ヘルプ(`CommandsHelpDoc.BUILD_COMMANDS`)

**Files:**
- Modify: `src/main/java/io/github/khayashi4337/micradrone/drone/CommandsHelpDoc.java`(`BUILD_COMMANDS`を足す)
- Test: `src/test/java/io/github/khayashi4337/micradrone/drone/BuildCommandsHelpTest.java`

**Interfaces:**
- Produces: `CommandsHelpDoc.BUILD_COMMANDS`(String)。**P3では、ゲーム内の巻物(`SampleCatalog`)には載せない**(建設のスクリプトを書く・実行する画面が、P5で入口を作るまで無く、読めても試せない巻物になるため。P5で`SampleCatalog`に足し、READMEとCurseForgeの説明文も同時に直す。F-19)。

**内容の要件(テストで固定):** 先頭行は`# `で始まる説明(既存の巻物と同じ。`ScriptFileStore.describeScript`が説明に使う)。`CommandNames.PLAN`の全命令が`名前(`の形で載る。`BuildingParts`の全部品の全パラメータ名が載る。全体が`DroneControllerBlockEntity.MAX_SCRIPT_CHARS`(10,000字)以内。畑との違い・座標と向き・素材の役割・位置指定の形・例・上限を含む。

- [ ] **Step 1: 失敗するテストを書く**

`BuildCommandsHelpTest.java`:
```java
package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.script.PlanScriptWriter;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import org.junit.jupiter.api.Test;

class BuildCommandsHelpTest {
    private static final String HELP = CommandsHelpDoc.BUILD_COMMANDS;

    @Test
    void itFitsAScrollAndStartsWithADescriptionLine() {
        assertTrue(HELP.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS, "length " + HELP.length());
        assertTrue(HELP.startsWith("# "), HELP.substring(0, 20));
        assertTrue(HELP.indexOf('\n') > 5);
    }

    @Test
    void everyConstructionCommandIsDocumented() {
        for (String name : CommandNames.PLAN) {
            assertTrue(HELP.contains(name + "("), name + "( is not documented");
        }
    }

    @Test
    void everyBuildingPartParameterIsNamed() {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                assertTrue(HELP.contains(p.name()), t.id() + "." + p.name() + " is not in the help text");
            }
        }
    }

    @Test
    void itExplainsTheDifferencesFromFarmScripts() {
        for (String needle : List.of("畑", "random", "u=右", "w=前", "surface", "outer", "100,000", "5秒", "roof", "style(")) {
            assertTrue(HELP.contains(needle), "missing: " + needle);
        }
    }
}
```
(`java.util.List`のimportを足す。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests "io.github.khayashi4337.micradrone.drone.BuildCommandsHelpTest" --console=plain`
Expected: FAIL。

- [ ] **Step 3: 実装する**

`CommandsHelpDoc.java`の`FISHING_AND_ANVIL`の後ろへ足す(既存の4つの定数と同じ書き方。テキストブロック):
```java
    /**
     * The construction-script reference: the commands that design a building or factory, and the building parts they
     * place. Not yet handed out as a scroll: the construction editor that can run these scripts arrives with the review
     * screens (phase P5), which will add it to {@link SampleCatalog} together with the README and CurseForge text.
     */
    public static final String BUILD_COMMANDS = """
            # コマンド一覧(建設): 建物や工場の設計を書く命令のリファレンス(実行するスクリプトではない)
            === MicraDrone 建設スクリプト コマンド一覧 ===

            建設のスクリプトは、畑のスクリプトとは別物です。畑の命令(move・harvest・till など)や、
            乱数・時刻・天気を調べる命令(random・get_time など)は使えません。同じスクリプトから
            いつも同じ設計ができるようにするためです。使えるのは、このページの命令と、
            if / for / while / def / 変数と、len・abs・min・max・str・list・dict・set・range・print だけです。
            1回の実行は100,000文・5秒までです。1本のスクリプトは10,000字までで、大きな設計は複数に分けます。

            ■ 座標と向き
            u=右、v=上、w=前。位置は [u, v, w] で書きます(建物の中では、建物の左下の手前が [0, 0, 0])。
            向きは north=前(+w)、east=右(+u)、south=後ろ(-w)、west=左(-u)。
            家全体の向きは、site の facing で決めます(建物や壁は、単独では回転できません)。

            ■ 場所と素材
            site(dimension, x, y, z, facing, bounds)
                建てる場所。例: site("minecraft:overworld", 100, 64, 200, "north", [-2, -3, -2, 12, 8, 12])
                bounds は [minU, minV, minW, maxU, maxV, maxW]。この範囲の外へ出る部品は、エラーになります。
            style(role, material)
                役割に素材を決める。例: style("roof", "minecraft:red_nether_bricks")
                役割: wall floor roof foundation pillar beam trim glass door gate fence stairs ramp catwalk
                chimney path pad marker cargo sign planter plant。階段やスラブが要る素材(roof など)は、
                ゲーム標準に階段のある素材(木材・石レンガ・レンガ・赤いネザーレンガなど)を選びます。
            mood(tag)
                雰囲気の目印(例: mood("cozy"))。

            ■ 部品を置く
            <部品名>(id, parent, anchor, params[, tags[, label]])
                id は小文字・数字・ハイフン(48字まで)。parent は親の id か None。params は {"名前": 値}。
                例: wall("wall-n", "hut", [0, 0, 0], {"side": "north"})
            part(id, type, parent, anchor, params[, tags[, label]])
                部品名で書けない部品(create: や mod: のもの)を、部品ID全体で置く。
            anchor(位置の指定)
                [u, v, w]  /  [u, v, w, 回転数, 鏡像](回転数は0〜3、鏡像は True か False)
                ["surface", 壁のid, "outer" か "inner", u, v]  壁の面の上(扉・窓・看板・植栽・縁取り・バルコニー)
                ["slot", スロットのid, 回転数, 鏡像]  建屋の中の置き場所(建屋の解析が載るまで使えません)
            update_params(id, params)   パラメータを変える
            relocate(id, anchor)        位置を変える
            remove_part(id)             消す(子や接続が残っていると消せません)

            ■ 建築の部品(パラメータ。カッコ内は候補。material は素材の役割かブロックID)
            structure: width depth floors floor_height   建物の箱。壁・床・屋根の親にする
            foundation: margin depth material   基礎
            floor: level kind(block/slab) holes material   床(holes は [u0,w0,u1,w1,…] の穴)
            wall: side(north/east/south/west。必須) level height thickness from length part(full/half) material
            pillar: height base capital material   柱
            beam: axis(u/v/w) length material   梁
            roof: kind(gable/hip/flat/shed/sawtooth/monitor) overhang ridge(auto/u/w) high_side gable_fill tooth
                  monitor_width monitor_height material   屋根
            door: kind(single/double/hangar) width height hinge(left/right) material   扉(壁の面に付ける)
            window: kind(pane/wide/arch) lattice material   窓(壁の面に付ける)
            stairs: steps width dir material   階段        ladder: height facing   はしご
            catwalk: length dir width rail material   歩廊    railing: length dir height material   手すり
            balcony: width depth rail material   バルコニー(壁の外側)    ramp: length dir width material   斜路
            chimney: height size cap material   煙突    lamp: kind(lantern/hanging/post/torch) height   照明
            sign: text(| で改行、4行・1行15字まで。必須) material   看板    planter: width   植栽
            trim: length axis(horizontal/vertical) shape(block/slab) material   縁取り
            dock_pad: width depth clearance cargo_u cargo_w marker material   飛行船の発着場
            road: length dir width material   道

            ■ つなぐ・物流
            connect(id, from, to, kind[, via[, constraints]])
                from と to は "部品のid.ポート名"。kind は rotation / item / fluid / redstone / heat / dock。
                via を書かない(または None)と、経路を自動で作る(この版では、自動の経路はまだ使えません)。
                via にリスト(["s1", "s2"])を書くと、その部品を通る。空のリスト [] は、間に部品を置かず直接つなぐ。
                constraints は {"max_length": 20, "avoid": ["id"], "max_turns": 3, "entry_dirs": ["up"]}。
            disconnect(id)   接続を消す
            logistics(docks, routes, flows)
                発着場・航路・荷の流れ。それぞれ辞書のリスト
                (docks: id pad clearance approach ports connectors / routes: id from to waypoints airship /
                 flows: item per_min from to)。

            ■ 例: 赤い屋根の小屋
            site("minecraft:overworld", 100, 64, 200, "north", [-2, -3, -2, 12, 8, 12])
            style("roof", "minecraft:red_nether_bricks")
            structure("hut", None, [0, 0, 0], {"width": 7, "depth": 7})
            floor("floor-0", "hut", [0, 0, 0], {})
            wall("wall-n", "hut", [0, 0, 0], {"side": "north"})
            wall("wall-s", "hut", [0, 0, 0], {"side": "south"})
            wall("wall-e", "hut", [0, 0, 0], {"side": "east"})
            wall("wall-w", "hut", [0, 0, 0], {"side": "west"})
            door("door-1", "hut", ["surface", "wall-s", "outer", 3, 0], {})
            window("win-1", "hut", ["surface", "wall-e", "outer", 3, 1], {})
            roof("roof-1", "hut", [0, 0, 0], {"overhang": 0})

            ■ エラーになる主な場合
            使えない命令を書いた(E-SCRIPT-FORBIDDEN)/ 部品が登録簿に無い(E-UNKNOWN-PART)/
            パラメータが範囲外(E-PARAM-RANGE)/ 位置指定が不正(E-ANCHOR)/ 部品どうしが重なる(E-OVERLAP)/
            敷地の外へ出る(E-OUT-OF-BOUNDS)/ 置けないブロック(E-BLOCK-FORBIDDEN)/ 実行の上限(E-SCRIPT-LIMIT)。
            """;
```
(実装時に、上の本文が10,000字以内であることと、`u=右`・`w=前`・`100,000`・`5秒`を含むことをテストで確かめ、超えていれば「エラーになる主な場合」の節を縮める。)

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --console=plain`
Expected: PASS(既存の47ファイル+新規)。`SampleCatalogTest`が緑(`SampleCatalog`は変えていない)。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/drone/CommandsHelpDoc.java src/test/java/io/github/khayashi4337/micradrone/drone/BuildCommandsHelpTest.java
git commit -m "$(cat <<'EOF'
docs: 建設スクリプトの命令リファレンス(CommandsHelpDoc.BUILD_COMMANDS)を追加(自然言語→工場建設 P3 Task 22)

入口(建設の画面)が無いP3では巻物として配らない。P5で入口ができるとき、SampleCatalog・README・CurseForgeの説明も同時に直す。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 23: 純Javaの検査(D-16)・全体の緑・`runClient`・設計図の整合・フェーズの完了確認

**Files:**
- Create: `src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java`
- Modify(設計図。実装で見つかった食い違いを、理由つきで直す): `docs/design/nl_factory_builder/{00_index_and_principles,01_data_model,04_foundations,05_parts_and_analyzers,07_phases_and_verification}.md`

- [ ] **Step 1: 純Javaの検査のテストを書く**

`BuildPurityTest.java`:
```java
package io.github.khayashi4337.micradrone.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** D-16: the pure core (build.*) must not touch Minecraft; checked mechanically, not by care. */
class BuildPurityTest {
    private static final Path ROOT = Path.of("src/main/java/io/github/khayashi4337/micradrone");
    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?(net\\.minecraft|net\\.neoforged|com\\.mojang|com\\.simibubi|net\\.createmod|dev\\.engine_room)\\.");
    private static final Pattern FORBIDDEN_QUALIFIED = Pattern.compile("\\b(net\\.minecraft|net\\.neoforged|com\\.mojang)\\.[a-z]");

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static List<String> violations(Path file, String text) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String line : text.split("\n", -1)) {
            n++;
            if (FORBIDDEN_IMPORT.matcher(line).find() || (!line.trim().startsWith("*") && !line.trim().startsWith("//")
                    && FORBIDDEN_QUALIFIED.matcher(line).find())) {
                out.add(file + ":" + n + ": " + line.trim());
            }
        }
        return out;
    }

    @Test
    void theScannerFindsTheSources() throws IOException {
        assertTrue(javaFiles(ROOT.resolve("build")).size() > 60, "the build package should hold many classes");
    }

    @Test
    void theBuildPackagesDoNotImportMinecraftOrNeoForgeOrCreate() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : javaFiles(ROOT.resolve("build"))) {
            all.addAll(violations(p, Files.readString(p, StandardCharsets.UTF_8)));
        }
        assertEquals(List.of(), all);
    }

    @Test
    void theConstructionBridgeInLangIsAlsoPureAndDoesNotDependOnBuild() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : javaFiles(ROOT.resolve("lang"))) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            all.addAll(violations(p, text));
            assertFalse(text.contains("import io.github.khayashi4337.micradrone.build."), p + " must not depend on build.*");
        }
        assertEquals(List.of(), all);
    }

    @Test
    void theScannerCatchesARealViolation() {
        assertEquals(1, violations(Path.of("X.java"), "import net.minecraft.core.BlockPos;\n").size());
        assertEquals(1, violations(Path.of("X.java"), "import static net.neoforged.fml.Foo.bar;\n").size());
        assertEquals(1, violations(Path.of("X.java"), "var p = new net.minecraft.core.BlockPos(1, 2, 3);\n").size());
        assertEquals(0, violations(Path.of("X.java"), "// mentions net.minecraft in a comment\n import java.util.List;\n").size());
    }
}
```

- [ ] **Step 2: 全部のテストと、コンパイルを確認する**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`。
Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`。**失敗0件**。出力の末尾のテスト数を記録し、`src/test/java`のテストファイルの数(`find src/test -name '*Test.java' | wc -l`)と、開始前の47ファイルが全部含まれることを確かめる。

- [ ] **Step 3: 実機確認(`runClient`が従来どおり起動する)**

**ゲーム窓に文字列や操作を送らない(合成入力の禁止)。ログだけで判断する。**
1. 起動前に、既存のゲームのプロセスを控える(林さんが別に起動している物を、閉じないため):
   `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*fml.modFolders*' } | Select-Object ProcessId`
2. `./gradlew runClient --console=plain`を、出力をファイルに落として、バックグラウンドで起動する。
3. ログに、資源の読み込みの完了を示す行(`Sound engine started`、または`Loading … mods`の後の`micradrone`)が出るまで待つ(最大6分。until-loopで)。`crash-reports`に新しいファイルが無いことを確かめる。
4. 起動前に控えた物**以外**の(この起動で増えた)プロセスだけを終了する。終了後、同じ確認コマンドで、増えた物が0件であることを確かめる。
5. 結果(ログの該当行、クラッシュの有無、終了の確認)を、証拠として記録する。**起動できなければ、直前の変更を疑い(`build.gradle`は変えていない。`MiniJson`の可視性・`Interpreter`・`CommandNames`・`SyntaxHighlighter`・`CommandsHelpDoc`・`en_us.json`が変更点)、systematic-debuggingで原因を調べる。**

- [ ] **Step 4: 設計図を実装の実態に合わせて直す(コードより先に設計図を正本にする原則)**

次の食い違いを、`python`の置換(唯一一致を確かめる)で直す。実装中に、これ以外の食い違いを見つけたら、同じコミットに足し、`00`の変更履歴に1行で書く。
1. `01` 12節の表: `build.model`の行を「1・2・5節の型、正規JSON・ハッシュ(`CanonicalJson`・`Hashing`・`PlanJson`)、`BlockRotation`」に。**新しい行`build.plan`**(`PlanPatcher`・`PlanExpander`・`ModuleTemplate`・`TemplateBundle`・`Origins`・`SlotResolver`・`Router`(IF)。`build.model`と`build.parts`の上の層。循環を避けるため)を足す。`build.parts`の行に`BuildingParts`・`MaterialFamilies`・`SchemaGenerator`を、`build.compile`の行に「4節の型、`PlanCompiler`、`ManifestDiff`、`Conflicts`、`PlaceableBlockPolicy`」を、**新しい行`build.compile.gen`**(建築部品の生成器)を足す。`build.script`の行に`PlanRecorder`・`PlanScriptWriter`・`PlanScriptProfile`・`PlanScriptRunner`を。`lang`の行(既存)に、`PlanApi`・`PlanRunLimits`を。
2. `01` 11.1節: `ParamType`に`INT_LIST`を足し、`ParamSpec`に`int maxItems`(`INT_LIST`の長さの上限)を足す。
3. `01` 3節: 「建築部品(`micra:*`)の`VolumeSpec`は`GENERATED`(生成器が決める。生成されたセルがそのまま占有体積)」の1文を、登録簿の箇条書きに足す。
4. `04` F-6の`connect`の行: 「`via`は…空・省略なら`Routing.Auto`、指定すれば`Explicit`」を「`via`は経由する部品IDのリストで、省略または`None`なら`Routing.Auto`、リスト(空のリストも)なら`Explicit`(空のリスト=つなぎの部品を置かず直接つなぐ)」に。
5. `07` P3の完了条件10: 「各部品の生成物が、宣言した占有体積(`VolumeSpec`)に収まる。」を「各部品の生成物が、規則から手で導いた値(小屋は238個)と一致する。」に。
6. `05` 1.1.1節の末尾に、「**実機で見た目を確かめる項目(P4の実機確認)**: 二枚扉の蝶番が中央で合うか、階段の角の形(隣のブロックの更新で決まる)、壁掛け看板の向き、屋根の階段の向き、はしごの向き。純Javaのテストでは、規則どおりの状態が出ることまでしか確かめられない」を足す。
7. `00`の変更履歴に、「**第4版・追補**(P3の実装後): ○○を直した」を1項目で足す(食い違いの一覧と、なぜ直したか)。

Run: `git diff --stat docs/design`で、意図した5ファイルだけが変わっていることを確かめる。

- [ ] **Step 5: 完了条件の表(証拠つき)を作る**

設計図07のP3の完了条件12個と、実機確認1個について、次の形の表を作り、各行に**その場で実行した証拠**(コマンド・出力の要点)を書く。証拠が無い行は「未確認」と書く:

| # | 条件 | 証拠(テスト名・コマンド・数) |
|---|---|---|
| 1 | 1,000回同じハッシュ | `DeterminismTest`・`GoldenHutTest`(238個)の出力 |
| 2 | 回転不変 | `RotationInvarianceTest`(小屋と、20種の乱数の並び) |
| 3 | 往復 | `PlanScriptRoundTripTest`(豊富な計画・400部品の分割・100個の乱数) |
| 4 | 許可リスト | `PlanScriptProfileTest`・`PlanScriptRunnerTest`・`PlanInterpreterTest` |
| 5 | `build.*`の純粋さ | `BuildPurityTest` |
| 6 | 既存47緑+新命令の認識 | `./gradlew test`の全体の結果・`PlanCommandNamesTest`・`BuildCommandsHelpTest`・`SyntaxHighlighterTest` |
| 7 | `E-PARAM-RANGE`等 | `ParamValidatorTest`・`PlanPatcherTest`・`PlanCompilerTest` |
| 8 | `E-BLOCK-FORBIDDEN` | `PlanCompilerTest.forbiddenAndUnlistedMaterialsAreRefusedOnEveryRoute`・`PlaceableBlockPolicyTest` |
| 9 | `ManifestDiff` | `ManifestDifferTest`・`ConflictsTest` |
| 10 | 建築部品22種 | `GeneratorRegistryTest`・`GoldenHutTest`・各生成器のテスト |
| 11 | `SchemaGenerator` | `SchemaGeneratorTest`(実測した文字数を記録) |
| 12 | 実行の限界 | `PlanInterpreterTest.theStepLimitStopsRunawayScripts`・`theTimeLimitStopsSlowScripts` |
| 実機 | `runClient`が従来どおり起動 | Step 3のログの行 |

- [ ] **Step 6: コミット**

```bash
git add src/test/java/io/github/khayashi4337/micradrone/build/BuildPurityTest.java docs/design/nl_factory_builder
git commit -m "$(cat <<'EOF'
test: build.*がMinecraftをimportしない検査(D-16)を追加し、設計図をP3の実装の実態に合わせて直す(自然言語→工場建設 P3 Task 23)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## フェーズ末のゲート(タスクではない。全タスクの完了後、実装者とは別のエージェントで、読み取り専用で走らせる)

1. **`pr-review-toolkit:type-design-analyzer`**: 新しい型(`build.model`・`build.parts`・`build.compile`の`record`と`sealed interface`)。設計図01の不変条件(`PlanNode`の並び、`ParamValue`の型付け、`PlacementManifest`のハッシュの範囲)で見る。
2. **`pr-review-toolkit:silent-failure-hunter`**: `PlanPatcher`・`PlanCompiler`・`PlanScriptRunner`・`PlanCommandDispatcher`・`GenContext`の、失敗の握りつぶし(`catch`・既定値へのフォールバック・`Optional.empty()`の黙殺)。「推測でOKにしない」で見る。
3. **`pr-review-toolkit:comment-analyzer`**: 新しいjavadocとコメントが、コードの実態と食い違っていないか(前例の捏造、実測していない数字)。
4. **`pr-review-toolkit:pr-test-analyzer`**: `git diff main...HEAD`(ローカルの差分)で、テストの穴。特に、Review Focusの5つの入力(巨大な計画・空の計画・特殊文字・同じパッチ内の矛盾・整数のあふれ)に、テストがあるか。
5. **`/codex:adversarial-review`**: 先にコミットしてから`--base main --scope branch`。focus文に、私の仮説は書かない。
6. **Opus 5.5のコードレビュー**(別のエージェント): 設計図の第4版と、この計画書を渡し、実装との食い違いを探させる。

各ゲートの指摘は、(a)事実を裏取りし、(b)実害があれば直し(テストを先に)、(c)反映しない物は理由を書く。範囲を狭める判断が出たら、自分の見解を先に作り、Codexに意見を求め、林さんに報告する。

## 自己点検(この計画書を書いた側のチェック)

- **Spec coverage**: 設計図07のP3の完了条件12個と実機確認は、冒頭の「完了条件×タスク」の表で、すべてタスクに割り当てた。「作る物」の各項目(型・部品登録簿・`PlanPatcher`・`PlanExpander`・`PlanCompiler`・`ManifestDiff`・`PlaceableBlockPolicy`・正規JSONとハッシュ・`SchemaGenerator`・`PlanApi`+命令・`PlanRecorder`・`PlanScriptWriter`)も、Task 2〜21のどれかが担当する。
- **未確認のまま計画に入れた物(実機・実測が要る)**: (1)二枚扉の蝶番・階段の角・看板の向きなどの見た目(Task 23の設計図の追記に「P4の実機で確認」と明記)。(2)型つきの完全なスキーマが20,000文字に収まるか(Task 18のStep 4で実測して縮める)。(3)`runClient`の起動(Task 23 Step 3)。
- **既存コードの変更**: `MiniJson`(可視性のみ)、`CommandNames`(足すだけ)、`Interpreter`(`planApi`があるときだけ働く)、`SyntaxHighlighter`(多重定義)、`CommandsHelpDoc`(定数を足すだけ)、`en_us.json`(キーを足すだけ)。畑の`CommandNames.ALL`・`DroneApi`は無変更で、既存の47テストが緑のままであることを、Task 19・22・23で確かめる。
