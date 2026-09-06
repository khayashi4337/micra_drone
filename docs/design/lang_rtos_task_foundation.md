# 実装設計書: RTOSタスク基盤(semaphore/sleep_ticks/create_task/attach_isr/raise_interrupt)

作成日: 2026-09-06
関連: `C:\Users\kh\.claude\plans\sorted-noodling-bachman.md` の
「言語拡張: ユーザー定義関数(def)とRTOSタスク基盤」セクション(B節=組み込み、C節=実行モデル)。
前提: `docs/design/lang_def_return_break_continue.md`(def/return/break/continue/pass、T1〜T3で実装済み・マージ済み)。

**この文書の目的**: 各タスクを別々のエージェントが実行しても、前提ファイルを
見失ったり同じ役割のファイルを重複作成したりしないよう、対象ファイルと
成果物を閉じたリストとして明示する。ここに無いファイルを新規作成したくなったら、
作業を止めてこの文書に追記してから進めること。

**実装完了後の注記(2026-09-06、Codexレビューで指摘)**: T1〜T5の実装・コードレビューを
通じてこの文書のコード片より実装が何段階か先に進んでいる(例: `budgetTicks`の切り上げ
処理、`TaskRegistry.tryReserveAndBind`への一本化、世代番号によるstopAll/reopen競合の
解消、`timeoutForTicks`の悲観的TPS仮定への修正)。**この文書は設計判断とその理由の記録
として引き続き有効だが、個別のコード片の正確な最終形は実際のソース(`Interpreter.java`/
`TaskRegistry.java`/`LiveDroneApi.java`/`PacedActionQueue.java`)を正とすること。**
実装完了後の文書全体の逐語的な同期は行わない(以後の細かい修正のたびに更新し続けると
文書自体が際限なく追従コストを生むため)。

## スコープの決定(重要、最初に読むこと)

「言語部分」として**今回実装する**もの:
- `semaphore()` + `.post()`/`.wait()`メソッド
- `sleep_ticks(n)` (既存のtickペーシング機構を一般化するだけ、新しいMinecraft APIは不要)
- `create_task(name, priority, budget_ticks, fn)` (タスク=Javaワーカースレッド1本、
  グローバルの浅いコピー、名前の一意性、OSスレッド優先度、予算超過ウォッチドッグ)
- `attach_isr(face, fn)` / `raise_interrupt(face)` (ソフトウェア割り込み。ISRコンテキストが
  ワールド変更系ビルトインを禁止する仕組み)

**今回は実装しない(明確に先送り、理由あり)**:
- `pwm_set(face, duty)` — plan原文の「疑似PWM出力」に対応する**実際のMinecraftブロック側の
  物理的な受け皿がまだ存在しない**(`set_output`にとってのredstone `POWERED`プロパティに相当する
  ものが無い)。アナログredstone(コンパレータ出力0〜15)への割り当てが有力候補だが、
  「新しいMinecraft API使用時は必ずdecompile済みソースで実際のシグネチャ・挙動を確認してから
  実装する」という本プロジェクトの標準手順を踏む必要があり、これは`set_output`/`get_output`
  実装時と同格の**別課題**である。中身のないsetterを今回のタスクの一部として作ることは、
  「言語部分の完了」の名目で実質何もしていないコードを追加するだけになるため見送る。
- redstoneのRISING edgeを実際に検出して`raise_interrupt`を自動的に呼ぶ配線(本物のハードウェア
  割り込み経路)。今回追加する`raise_interrupt(face)`はスクリプトから明示的に呼ぶ
  **ソフトウェア割り込み**(本物の組み込み系にもある概念、ARM Cortex-MのNVICのソフトウェア
  割り込みトリガーが実例)であり、ISR機構そのものはこれで言語レベルでは完結し
  JUnitで完全に検証できる。redstone配線は別途Minecraft側の設計(どのブロックのどの面をどの
  `face`名に対応させるか)が要るため別課題とする。
- メインスレッドディスパッチを優先度キューにすること(`PacedActionQueue`は今もFIFO)。
  `create_task`の`priority`は`Thread.setPriority()`という**実在し実際に効く、しかし
  OSスケジューラへの助言に過ぎず優先度に応じた確定的な実行順序を保証しない**近似値として
  使う(下記「優先度についての正直な注記」を参照)。
- ワールド空間の演出(考え中/エラー/成功/busyパーティクル等) — plan原文でも
  「実行モデル(C)→組み込み(B)→演出、の順」と最初から別扱い。
- 生の7スロットMAVLink風`send_command(...)`(上級者向けの下位互換API) — plan原文でも
  「一段下に置く」という将来拡張として触れられているだけで、7スロットの具体的な中身
  (何番目が何を意味するか)がまだ一切決まっていない。今回のACCEPTED/DENIEDという
  結果コード文字列の採用だけで、plan原文が指摘した「読みやすい名前 vs MAVLink風窓口」の
  食い違いは解消されるため、この生API自体を今回追加する必要はない。

## 優先度についての正直な注記

`Thread.setPriority(1〜10)`はJVM/OSへの**ヒント**であり、リアルタイムOSのような
確定的なプリエンプティブスケジューリングではない(JVM仕様上、実装依存)。
本プロジェクトの「嘘はつかない」原則に従い、ドキュメント・javadoc・ヘルプ巻物のいずれにも
「優先度が高いタスクは必ず先に実行される」という誤った保証は書かない。「優先度が高いほど
CPU時間を得やすくなる可能性がある、程度の目安」という正確な表現にする。

## タスク分割(1タスク=1コミット、都度ビルド・テスト実測確認)

**(Codexレビュー指摘により順序を修正: `CommandNames`の更新を最後のタスクにまとめず、
各ビルトインを追加するタスクと同じコミットで追加する。理由:
`defineFunction`/`evalCall`が`CommandNames.ALL`を「組み込み名かどうか」の判定に使っており、
中間コミットで新しいビルトイン名がまだ`CommandNames.ALL`に無いと、その名前をユーザー定義
関数として定義できてしまう等、名前解決が一時的に不整合になる。)**

1. `MicraSemaphore` + `evalMethodCall`への配線 + `CommandNames`に`semaphore`追加 + JUnit
2. `TaskRegistry` + `Interpreter`のコンストラクタ再編・`create_task` + `CommandNames`に
   `create_task`追加 + `DroneControllerBlockEntity`/`DroneScriptRunner`/`DroneControllerBlock`
   との結線(タスクの後始末) + JUnit
3. `sleep_ticks(n)` (`DroneApi`/`LiveDroneApi`/`FakeDroneApi`/`PacedActionQueue`の
   先頭ブロッキング修正を含む) + `CommandNames`に`sleep_ticks`追加 + JUnit
4. `InterruptTable` + `attach_isr`/`raise_interrupt` + ISRコンテキストのゲート +
   `CommandNames`に`attach_isr`/`raise_interrupt`追加 + JUnit
5. ヘルプ巻物/サンプルスクリプト1本(Lチカ相当の`BLINK_TASK`)/`docs/curseforge_description.md`
   (`CommandNames`は既にタスク1〜4で追加済みなのでこのタスクでの追加は無い)

## 対象ファイル一覧(クローズドリスト)

### 既存ファイル(編集のみ)

| パス | このタスクでの役割 |
|---|---|
| `src/main/java/io/github/khayashi4337/micradrone/lang/Interpreter.java` | 下記「Interpreterの変更点」を参照。コンストラクタ再編、`evalCall`にcreate_task/attach_isr/raise_interrupt追加、`evalMethodCall`にsemaphoreディスパッチ追加、`typeName`/`stringify`にMicraSemaphoreのcase追加、`isrContext`ゲート追加(`GENERAL_PURPOSE_BUILTINS`と別に`ISR_SAFE_BUILTINS`を新設)、`runIsolatedBody`(新しい子フレームを作る)、公開メソッド`stopAllTasks()`追加 |
| `src/main/java/io/github/khayashi4337/micradrone/lang/Environment.java` | パッケージプライベートな`snapshot()`を1つ追加するのみ(下記参照) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/DroneApi.java` | `void sleepTicks(double ticks);`を追加 |
| `src/main/java/io/github/khayashi4337/micradrone/drone/LiveDroneApi.java` | `sleepTicks`実装。`dispatch(Supplier<Attempt>)`を`dispatch(long successDelayTicks, Supplier<Attempt>)`に一般化(既存呼び出し元は無改修)。`blockOn`にタイムアウト秒数を明示的に渡す版を追加し、`successDelayTicks`に応じて`timeoutForTicks`でタイムアウトを伸ばす(下記「`sleep_ticks(n)`の設計」参照 - Codexレビューで発見: 当初案は既存の固定5秒タイムアウトのままで、長い`sleep_ticks`が必ず失敗していた) |
| `src/main/java/io/github/khayashi4337/micradrone/drone/PacedActionQueue.java` | `tick(long)`を「先頭要素だけ見るFIFO」から「キュー全体を走査し、readyAtTickが来たものだけ投入順を保って取り除く」実装に変更(Codexレビューで発見: 先頭に長い`sleep_ticks`のエントリが居座ると、それより後に積まれた別のタスクの近い将来のエントリまで一切実行されなくなる致命的な誤りだった) |
| `src/main/java/io/github/khayashi4337/micradrone/drone/DroneScriptRunner.java` | `TaskRegistry`/`InterruptTable`を注入できる新しいコンストラクタを追加(既存の2引数/3引数コンストラクタはテスト用にそのまま残す)。`stop()`で`taskRegistry.stopAll()`、`runProgram`で`new Interpreter(api, debug, taskRegistry, interruptTable)`を使う。**レジストリ自体を保持するのは`DroneControllerBlockEntity`であり、`DroneScriptRunner`はコンストラクタで受け取った参照を持ち回すだけ**(下記「`TaskRegistry`/`InterruptTable`の所有者について」を参照 - 当初案からの重要な訂正) |
| `src/main/java/io/github/khayashi4337/micradrone/drone/DroneControllerBlockEntity.java` | `TaskRegistry`/`InterruptTable`フィールドを追加(コントローラの生存期間そのもの、Runをまたいで保持)。`startFreshRun`冒頭で`stopAll()`+`clear()`、新規`stopAllTasks()`メソッド、`stopScript`から`stop()`経由で間接的に`stopAll()`。下記「`DroneControllerBlockEntity`/`DroneControllerBlock`の変更点」を参照 |
| `src/main/java/io/github/khayashi4337/micradrone/drone/DroneControllerBlock.java` | `onRemove`に`be.stopAllTasks()`を1行追加(既存の`be.discardDroneEntity()`と同じ場所) |
| `src/test/java/io/github/khayashi4337/micradrone/lang/FakeDroneApi.java` | `sleepTicks`実装追加。**クラス全体をスレッドセーフにする**(`synchronized`メソッド、または内部状態をロックで保護) - `calls`/`printed`だけでなく`x`/`y`/`tilled`/`cropAge`等の全フィールドが対象(複数タスクスレッドが同じ`FakeDroneApi`インスタンスを並行に呼ぶテストで、非スレッドセーフな`ArrayList`/配列書き込みによるテストのフレーク化を防ぐため。Fable5.1レビュー指摘) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/CommandNames.java` | `semaphore`/`sleep_ticks`/`create_task`/`attach_isr`/`raise_interrupt`を追加 |
| `src/main/java/io/github/khayashi4337/micradrone/drone/CommandsHelpDoc.java` | 新セクション「■ RTOSタスク(応用)」を追加。優先度についての正直な注記もここに反映する |
| `src/main/java/io/github/khayashi4337/micradrone/drone/SampleScripts.java` | 新サンプル `BLINK_TASK`(下記「サンプルスクリプトの内容」を参照) |
| `src/main/java/io/github/khayashi4337/micradrone/drone/SampleCatalog.java` | `BLINK_TASK`を登録(本棚15冊・ラピス3、本MOD最高難度) |
| `docs/curseforge_description.md` | 機能一覧に一言追記 |
| `src/test/java/io/github/khayashi4337/micradrone/lang/InterpreterTest.java` | semaphore/create_task/attach_isr/raise_interrupt/sleep_ticksのテストを追加 |
| `src/test/java/io/github/khayashi4337/micradrone/lang/SampleScriptsTest.java` | `everySampleParsesAndRunsWithoutError`を`Interpreter`を変数に受けて`finally`で`stopAllTasks()`する形に変更(下記「`SampleScriptsTest`への影響」参照 - Fable5.1レビューで発見: `BLINK_TASK`の無期限タスクが後始末されないまま残る) |
| `src/test/java/io/github/khayashi4337/micradrone/lang/CommandNamesTest.java` | 既存の整合性テストがあれば新規名を反映(無ければ変更不要、実装時に確認) |
| `src/test/java/io/github/khayashi4337/micradrone/drone/DroneScriptRunnerTest.java` | 「スクリプトを再実行すると前回のタスクが後始末される」ことを実際のワーカースレッド経路で確認するテストを追加 |
| `src/test/java/io/github/khayashi4337/micradrone/drone/PacedActionQueueTest.java` | 「先に長い遅延のエントリを積み、後から短い遅延のエントリを積んでも、短い方が先に実行される」ケースを追加(先頭ブロッキング修正の回帰テスト) |

### 新規ファイル

| パス | 役割 |
|---|---|
| `src/main/java/io/github/khayashi4337/micradrone/lang/MicraSemaphore.java` | `java.util.concurrent.Semaphore`を包んだ値型。`post()`(release、ブロックしない)と`await()`(acquire、interrupt時は`ScriptStoppedException`に変換)。スクリプト側のメソッド名は`.post()`/`.wait()`(`evalMethodCall`側でディスパッチ、Java の`Object.wait()`とは無関係) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/TaskRegistry.java` | `create_task`が生成したタスクの名前→`Thread`の対応をスレッドセーフに保持。`tryReserve(name)`/`bind(name, thread)`/`release(name)`/`stopAll()`(全タスクに`interrupt()`) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/InterruptTable.java` | `face`(任意の文字列)→`MicraFunction`ハンドラの対応をスレッドセーフに保持。`attach(face, fn)`/`handlerFor(face)`/`clear()` |
| `src/test/java/io/github/khayashi4337/micradrone/lang/TaskRegistryTest.java` | 名前の一意性・`stopAll`がinterruptを送ることの単体テスト |
| `src/test/java/io/github/khayashi4337/micradrone/lang/InterruptTableTest.java` | 登録・上書き・未登録face・`clear()`の単体テスト |

### 触らないファイル(参考、混同注意)

- `Lexer.java`/`Parser.java`/`ast/Expr.java`/`ast/Stmt.java` — **変更不要**。新機能は
  すべて「関数呼び出し`name(args)`」と既存の「メソッド呼び出し`target.method(args)`」
  構文(list/dict/set向けに既に実装済み)だけで表現できる。新しい予約語もASTノードも要らない。
- `MicraFunction.java`/`ReturnSignal.java`/`BreakSignal.java`/`ContinueSignal.java` — 変更不要。
- `Environment.java` — 唯一の変更点は上記「Interpreterの変更点」内で示すパッケージ
  プライベートな`snapshot()`の追加のみ(`get`/`set`/`tryGet`の意味・シグネチャは不変)。
- `DebugController.java`/`IdeScreen.java` — 変更不要。タスク/ISR用に生成する`Interpreter`は
  すべて`debug=null`で作る(デバッガはメインスクリプト1本の単一スレッド前提のまま、
  複数タスクへの対応は明確な範囲外)。

## Interpreterの変更点

### コンストラクタの再編

現在:
```java
private Environment env = new Environment();
private final Environment globalEnv = env;

public Interpreter(DroneApi api) { this(api, null); }
public Interpreter(DroneApi api, DebugController debug) {
    this.api = api;
    this.debug = debug;
}
```

変更後(`TaskRegistry`/`InterruptTable`を外部から共有できるようにし、`isrContext`フラグを
持たせる):
```java
private final Environment globalEnv;
private Environment env;
private final boolean isrContext;
private final TaskRegistry taskRegistry;
private final InterruptTable interruptTable;

public Interpreter(DroneApi api) { this(api, null); }
public Interpreter(DroneApi api, DebugController debug) {
    this(api, debug, new TaskRegistry(), new InterruptTable());
}
/** DroneControllerBlockEntityがRunをまたいで保持するレジストリを注入する経路
 *  (DroneScriptRunnerのコンストラクタ経由でここに渡ってくる)。 */
public Interpreter(DroneApi api, DebugController debug, TaskRegistry taskRegistry, InterruptTable interruptTable) {
    this(api, debug, new Environment(), false, taskRegistry, interruptTable);
}
private Interpreter(DroneApi api, DebugController debug, Environment globalEnv, boolean isrContext,
        TaskRegistry taskRegistry, InterruptTable interruptTable) {
    this.api = api;
    this.debug = debug;
    this.globalEnv = globalEnv;
    this.env = globalEnv;
    this.isrContext = isrContext;
    this.taskRegistry = taskRegistry;
    this.interruptTable = interruptTable;
}
```

既存のテスト(`new Interpreter(api)` / `new Interpreter(api, debug)`)はそのまま動く
(内部で新しい`TaskRegistry`/`InterruptTable`が自動生成される)。そうしたテストが
`create_task`を使う場合、生成した`Interpreter`インスタンスを変数に受けて
`finally`で`interpreter.stopAllTasks()`する(下記「`SampleScriptsTest`への影響」で
実例を示す)か、有限回数で終わる`fn`にすること。

### タスク本体・ISRハンドラの実行経路(`run`とは別)

**(Codexレビューで発見された確実な誤りを2件、ここで修正する。)**

**誤り1**: 当初案は`execBlock(body)`をこの`Interpreter`インスタンスの`env`
(=`forkedGlobal`自身)に対して直接実行していた。しかし既存の`callFunction`
(730行目)は必ず`env = new Environment(globalEnv)`という**子フレーム**を
新設してから本体を実行しており、`MicraFunction`のjavadocも「すべての関数呼び出しは
グローバルを親とする新しいフレームを得る」と明記している。子フレームを作らずに
直接`forkedGlobal`上で実行すると、タスク/ISRハンドラの本体内の代入がローカル
ではなくフォーク先グローバルに直接書き込まれてしまい、既存の関数呼び出しと
異なるスコープ規則になる(例えば`def blink(): x = 1`のような、通常の関数なら
呼び出しの度に独立するはずのローカル変数が、フォーク先グローバルの永続的な
書き換えになってしまう)。

**誤り2**: `create_task`/`attach_isr`の提示コードは`argAt(call, i)`を
複数回呼んでいたが、既存の`argAt`(822行目)は内部で毎回
`requireArgCount(call, index + 1)`を呼び直す実装になっている:
```java
private Object argAt(Expr.Call call, int index) {
    requireArgCount(call, index + 1);
    return eval(call.args().get(index));
}
```
これは「引数がちょうど`index+1`個であること」を**呼ぶたびに**再検証する
仕様であり、1引数のビルトイン(`move`等)でしか正しく使えない。4引数の
`create_task`で`argAt(call, 0)`を呼ぶと、そこで`requireArgCount(call, 1)`が
実行され、実際には4引数あるため必ず失敗する。**`requireArgCount`を先に1回
呼んだ後は、`argAt`を使わず`eval(call.args().get(i))`を直接呼ぶ**必要がある。

以上2件を踏まえた正しいコード:
```java
/** タスク/ISRハンドラの本体を実行する。callFunctionと同じく必ず新しい子フレームを作ってから
 *  本体を実行する(MicraFunctionの「全呼び出しは新しいフレームを得る」という不変条件を
 *  タスク/ISRでも守るため)。関数本体の先頭にある素のreturnで早期終了できるように
 *  ReturnSignalを吸収する(戻り値を誰も見ないので捨てる)。 */
private void runIsolatedBody(List<Stmt> body) {
    Environment previous = env;
    env = new Environment(globalEnv); // globalEnv=forkedGlobal自身ではなく、その子フレーム
    try {
        execBlock(body);
    } catch (ReturnSignal ignored) {
        // 早期returnはタスク/ISRハンドラの終了を意味するだけ
    } finally {
        env = previous;
    }
}
```

### `create_task`

```java
case "create_task" -> {
    requireArgCount(call, 4); // 1回だけ検証してから、以降はargAtを使わずevalで直接読む
    String name = asString(eval(call.args().get(0)), call.line());
    double priorityRaw = asDouble(eval(call.args().get(1)), call.line());
    double budgetRaw = asDouble(eval(call.args().get(2)), call.line());
    Object fnValue = eval(call.args().get(3));
    yield createTask(name, priorityRaw, budgetRaw, fnValue);
}
```

```java
private static final long TICK_MILLIS = 50; // Minecraftの1tick=50ms(20tick/秒)、予算ウォッチドッグ専用
// 1,000,000 tick ≈ 13.9時間。予算の絶対上限(常識外れに大きいbudget_ticksでの
// `budgetTicks * TICK_MILLIS`オーバーフローを防ぐ)。
private static final long MAX_BUDGET_TICKS = 1_000_000;

private String createTask(String name, double priorityRaw, double budgetTicksRaw, Object fnValue) {
    if (!(fnValue instanceof MicraFunction fn) || !fn.params().isEmpty()) {
        return "DENIED";
    }
    // Codexレビュー指摘: 不正な数値(NaN/負数/非有限)を「0=無期限」に静かに丸めるのは危険
    // (script側の計算ミスが気付かれないまま無期限タスクを生む)。0はユーザーが明示的に選べる
    // 正当な値として区別し、それ以外の不正値はDENIEDにする。
    if (!Double.isFinite(budgetTicksRaw) || budgetTicksRaw < 0) {
        return "DENIED";
    }
    long budgetTicks = (long) Math.min(MAX_BUDGET_TICKS, budgetTicksRaw);
    // Codexレビュー指摘: Math.round(priorityRaw)がlongを返すため、極端に大きい/小さいpriorityRaw
    // ((int)キャストが折り返す)を先にintへ狭めてからクランプすると符号が反転しうる。
    // 先にdoubleの範囲でクランプしてから丸めて狭める。NaNはMath.min/maxがNaNを伝播するので
    // 通常の優先度(5)にフォールバックする。
    double clampedPriorityRaw = Double.isNaN(priorityRaw) ? 5.0
            : Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priorityRaw));
    int priority = (int) Math.round(clampedPriorityRaw);
    if (!taskRegistry.tryReserve(name)) {
        return "DENIED";
    }
    Environment forkedGlobal = new Environment();
    for (Map.Entry<String, Object> e : globalEnv.snapshot().entrySet()) {
        forkedGlobal.set(e.getKey(), e.getValue());
    }
    Interpreter taskInterpreter =
            new Interpreter(api, null, forkedGlobal, false, taskRegistry, interruptTable);
    // Codexレビュー指摘: 生の配列(Thread[])への書き込みはメモリ可視性(happens-before)が
    // 保証されず、taskThread.start()後にwatchdogHolder[0]へ書き込んでもタスクスレッド側の
    // finallyがその値を永遠に観測できない可能性がある。AtomicReferenceを使う。
    java.util.concurrent.atomic.AtomicReference<Thread> watchdogHolder = new java.util.concurrent.atomic.AtomicReference<>();
    Thread taskThread = new Thread(() -> {
        try {
            taskInterpreter.runIsolatedBody(fn.body());
        } catch (ScriptStoppedException ignored) {
            // 停止 or 予算超過(interrupt()は協調的な停止シグナルであり「強制終了」ではない -
            // タスクの通常の終了経路の一つ)
        } catch (MicraLangException e) {
            api.print("task '" + name + "' error: " + e.getMessage());
        } catch (Throwable e) {
            // DroneScriptRunner.runProgramの catch (Throwable) と同じ形(Fable5.1レビュー指摘:
            // ScriptStoppedException/MicraLangExceptionだけでは、IllegalArgumentException
            // (move()に不正なdirection)やRuntimeException(blockOnのタイムアウト)、
            // 共有list/dict/setへの並行アクセスによるConcurrentModificationExceptionなどが
            // タスクスレッドの未捕捉例外ハンドラ(標準エラー出力のみ)に落ちてプレイヤーの
            // ログには何も出ないまま終わる)。
            try {
                api.print("task '" + name + "' error: " + e);
            } finally {
                if (e instanceof Error error) {
                    throw error; // StackOverflowError等はログしてから素通しする(runProgramと同じ)
                }
            }
        } finally {
            taskRegistry.release(name);
            Thread watchdog = watchdogHolder.get();
            if (watchdog != null) {
                watchdog.interrupt(); // 先に終わったので予算ウォッチドッグは不要
            }
        }
    }, "MicraDrone-Task-" + name);
    taskThread.setDaemon(true);
    taskThread.setPriority(priority);
    taskRegistry.bind(name, taskThread);
    taskThread.start();
    if (budgetTicks > 0) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(budgetTicks * TICK_MILLIS);
                taskThread.interrupt();
            } catch (InterruptedException ignored) {
                // タスクが先に終わったので何もしない
            }
        }, "MicraDrone-Task-" + name + "-Watchdog");
        watchdog.setDaemon(true);
        watchdogHolder.set(watchdog);
        watchdog.start();
    }
    return "ACCEPTED";
}
```

**残る既知の軽微なタイミング差**: `taskThread.start()`の直後、`budgetTicks > 0`の
分岐で初めてウォッチドッグを作るため、タスクがそのごく短い間に(ウォッチドッグが
作られる前に)自然終了した場合、ウォッチドッグは少し遅れて作られてから
`budgetTicks`の満期まで走り、その後もう終わっているタスクスレッドへ無意味な
`interrupt()`を送るだけで消える(実害はない、スレッドが1本、最大`MAX_BUDGET_TICKS`
分だけ余分に生存するだけ)。`AtomicReference`化で直したのはメモリ可視性の
バグ(値が書かれたのに永遠に見えない可能性)であり、この順序自体のわずかな
ズレは実害が無いため許容する。

`Environment`に、自分の(親を辿らない)フレームの中身だけを浅くコピーして返す
パッケージプライベートな最小メソッドを1つだけ追加する:
```java
/** このフレーム自身が持つ束縛だけを浅くコピーして返す(親は辿らない)。create_task/
 *  raise_interruptがフォーク先の新しいグローバルフレームを作るための専用メソッド。 */
Map<String, Object> snapshot() {
    return new HashMap<>(values);
}
```
「触らないファイル」の記述はこのメソッド追加を妨げない(既存の`get`/`set`/`tryGet`の
シグネチャ・意味は一切変えない、純粋な追加)。呼び出し側は必ず`globalEnv.snapshot()`
(グローバルフレーム自身、親を持たないので"snapshot()=フレーム全体"と一致する)を使う。

**ACCEPTED/DENIEDという文字列を返す理由**: `design/class.pu`のMAVLink風コマンド窓口
(結果コードで返す設計)との整合を取るための決定(2026-09-05のRTOS基盤設計時に決定済み)。
読みやすい関数名はそのまま維持しつつ、戻り値だけを結果コード文字列にする。

### `attach_isr` / `raise_interrupt`

```java
case "attach_isr" -> {
    requireArgCount(call, 2); // 1回だけ検証してから、argAtを使わずevalで直接読む(上記と同じ理由)
    String face = asString(eval(call.args().get(0)), call.line());
    Object fnValue = eval(call.args().get(1));
    if (!(fnValue instanceof MicraFunction fn) || !fn.params().isEmpty()) {
        throw new MicraLangException(call.line(), "attach_isr()'s handler must be a function that takes no arguments");
    }
    interruptTable.attach(face, fn);
    yield MicraNone.INSTANCE;
}
case "raise_interrupt" -> {
    requireArgCount(call, 1); // 単一引数なのでargAt(call, 0)のままで問題ない
    raiseInterrupt(asString(argAt(call, 0), call.line()));
    yield MicraNone.INSTANCE;
}
```

```java
/**
 * ソフトウェア割り込み: {@code face}に登録済みのハンドラを、呼び出したスレッド上で
 * "同期的に"(ISRらしく即座に)実行する。本物のredstoneエッジ検出からの自動発火(未実装、
 * 上記スコープ節を参照)は将来はメインスレッド上で発火する想定だが、ここではどのスレッドから
 * 呼ばれても良い(呼び出したスレッドで動く)。未登録のfaceは何もしない
 * (set_outputの「マーカーが無ければ何もしない」と同じ、静かな無視)。
 */
private void raiseInterrupt(String face) {
    MicraFunction handler = interruptTable.handlerFor(face);
    if (handler == null) {
        return;
    }
    Environment forkedGlobal = new Environment();
    for (Map.Entry<String, Object> e : globalEnv.snapshot().entrySet()) {
        forkedGlobal.set(e.getKey(), e.getValue());
    }
    Interpreter isrInterpreter =
            new Interpreter(api, null, forkedGlobal, true, taskRegistry, interruptTable);
    isrInterpreter.runIsolatedBody(handler.body());
}
```

### ISRコンテキストのゲート(最重要: メインスレッド長時間停止の防止)

**(Codex/Fable5.1レビューで修正: 当初「確実なデッドロック」と書いていたが、デコンパイル済み
Minecraft 1.21.1ソース(`BlockableEventLoop.execute`/`MinecraftServer`)を実際に照合した
Fable5.1のレビューにより、正確には以下の通りだと判明したので訂正する。)**

`BlockableEventLoop.execute`は、既にメインスレッド自身から呼ばれていて
`scheduleExecutables()`が偽の場合(tick処理の最中はまさにこれに該当)、渡された
Runnableを**キューに積まず、その場でインライン実行**する。つまり`queryMainThread`
経由の呼び出し(`can_harvest()`等の読み取り専用ビルトイン)を将来メインスレッド上の
ISRから呼んでも、これ自体は即座に完了し、確実なデッドロックにはならない。

しかし`dispatch`経由の呼び出し(`move()`/`till()`/`sleep_ticks()`等)は事情が違う:
`attempt.get()`によるインライン実行そのものはすぐ終わっても、その後
`pacedQueue.submit(readyAt, ...)`に積んだ完了処理は**次以降のtickで
`pacedActionQueue.tick(...)`が呼ばれて初めて実行される**(`BlockEntity#serverTick`)。
ところが今この呼び出し元はメインスレッドの「今の」tick処理の中で
`future.get(MAIN_THREAD_TIMEOUT_SECONDS=5, SECONDS)`をブロック待ちしている。
「次のtickに進む」ためには「今のtickの処理(=このブロック待ち自体)が終わる」ことが
必要なので、進行できないまま**5秒間メインスレッドが完全に固まった後、
`TimeoutException`→`RuntimeException`で失敗する**。これは技術的には「永遠に
戻らない」デッドロックではないが、プレイヤーの体感としてはサーバー全体が5秒間
無反応になるという、デッドロックと同等以上に許容できない事態である。

したがって結論(ゲートを設けること自体)は変わらないが、理由は「確実なデッドロック」
ではなく「メインスレッド上で`dispatch`系の呼び出しをすると最大5秒間サーバー全体が
固まった末に失敗する、放置できない不具合」が正確な表現である。

**なお、今回追加する`raise_interrupt(face)`はスクリプト/タスクのスレッドからのみ
呼ばれ、メインスレッドから呼ばれることは無い(本物のredstoneエッジ検出からの
自動発火は上記スコープ節の通り今回のスコープ外)。したがって今日出荷する実装では
この問題は実際には発生しない。** それでもゲートを今から入れておくのは、
(a) 将来メインスレッド上のISR自動発火を配線したときにこの問題が再発しないための
先回りの安全策、(b) 「ISRハンドラはワールドを変更する重い呼び出しをしてはいけない」
という本物の組み込みRTOSの作法そのものを最初から教えるため、の2つの理由による。

このため、`isrContext == true`のとき、`evalCall`の**ユーザー定義関数の早期解決より後・
switch文に入る前**に以下を追加する(**プレースメントが重要**: `env.tryGet`によるユーザー
定義関数の解決より前に置くと、`def add(a,b): return a+b`のような純粋計算しかしないヘルパー
関数の呼び出しまでISR内で一律禁止してしまう誤りになる。ユーザー定義関数の呼び出し自体は
許可し、その関数の**本体が実際にビルトインを呼んだ時点**でこの判定に引っかかるようにする
のが正しい):
```java
private Object evalCall(Expr.Call call) {
    Object maybeFn = env.tryGet(call.name());
    if (maybeFn instanceof MicraFunction fn) {
        return callFunction(fn, call); // isrContextは同じInterpreterインスタンスに乗って
                                        // 関数本体の中のevalCallにもそのまま伝播する
    }
    if (maybeFn != null && !CommandNames.ALL.contains(call.name())) {
        throw new MicraLangException(call.line(),
                "'" + call.name() + "' is not a function (it is a " + typeName(maybeFn) + ")");
    }
    if (isrContext && !ISR_SAFE_BUILTINS.contains(call.name())) {
        throw new MicraLangException(call.line(),
                "'" + call.name() + "' cannot be called from an interrupt handler - it would block "
                        + "the caller (main-thread freeze risk if raised from there). "
                        + "Post a semaphore instead and let a task do the real work.");
    }
    List<Expr> args = call.args();
    Object result = switch (call.name()) { /* 既存のまま */ };
    ...
}
```

**2つの別の集合が必要(重要な訂正)**: 「暴走検知カウンタをリセットしない」という
既存の目的で使われている`GENERAL_PURPOSE_BUILTINS`と、「ISR内で呼んでよい」という
このゲート専用の目的は、**メンバーシップが一致しない**。両方に同じ集合を使うと
矛盾する(下記参照)ので、新しい専用の集合`ISR_SAFE_BUILTINS`を追加する。

`GENERAL_PURPOSE_BUILTINS`(既存: `len,abs,min,max,random,str,list,set,dict`)に
`semaphore`/`create_task`/`attach_isr`/`raise_interrupt`の**4つ**を追加する
(暴走検知カウンタの観点。理由は次の通り):

- `semaphore`: 純粋な確保のみでドローン/ワールドに一切触れないため、既存の分類に
  そのまま合致する。
- `create_task`/`attach_isr`/`raise_interrupt`: **Codexレビューで指摘された暴走検知
  すり抜けの修正**。これらを`GENERAL_PURPOSE_BUILTINS`に入れず「呼ぶたびに
  カウンタをリセットする」既存ルールのままにすると、以下の抜け穴が生まれる:
  - `while True: create_task(str(random()), 5, 0, noop)`のように、同時生存数の
    上限(`MAX_CONCURRENT_TASKS`)を超えて常に`DENIED`になるループでも、
    `create_task`という名前で呼ばれる限り毎回カウンタが0に戻り、暴走ループ検知
    (`RUNAWAY_STATEMENT_THRESHOLD`)を永久にすり抜けてCPUを専有し続けられる。
  - `while True: raise_interrupt("no_such_face")`のように、実際には何もしない
    (未登録faceは即return)呼び出しでも同様に検知をすり抜けられる。

  一方、`create_task`が実際に成功する(=実在のOSスレッドを1本生成する)ケースは
  `MAX_CONCURRENT_TASKS=16`という上限があるため、たとえこの分類のせいでその
  16回分がカウンタをリセットしなくても実害は無い(1,000,000という閾値に対して
  無視できる回数)。つまり「ドローンの世界に触れない・カウンタをリセットしない」
  という既存の分類基準に、成功可否を問わずそのまま合致させるのが正しい。

新設する`ISR_SAFE_BUILTINS`(ISR内で呼んでよいものだけの、より狭い集合。
`GENERAL_PURPOSE_BUILTINS`から`create_task`/`attach_isr`/`raise_interrupt`を
**除いた**もの):
```java
private static final Set<String> ISR_SAFE_BUILTINS =
        Set.of("len", "abs", "min", "max", "random", "str", "list", "set", "dict", "semaphore");
```
`create_task`/`attach_isr`/`raise_interrupt`はこの集合に**入れない**
(=ISR内では引き続き禁止される - タスクの再入生成・ハンドラの再登録・
別の割り込みの連鎖発火を防ぐ)。同様に既存の全DroneApi系ビルトイン(`print`含む)
も`ISR_SAFE_BUILTINS`に入れない。`print`は`LiveDroneApi`の現在の実装では実は
メインスレッド経由のディスパッチをせず直接呼んでいるので今日の実装では技術的には
サーバーフリーズを起こさないが、この判定は「個別のメソッドの実装を知っている」
前提を置かない単純な規則(ビルトインは原則禁止・純粋計算のみ許可)にするため、あえて
`print`も例外にしない - 将来`print`の実装が変わっても安全側に倒れる。

`MicraSemaphore.wait()`(`evalMethodCall`経由)も同様にブロックするため、
`semaphoreMethod`内で個別にガードする(下記参照)。`post()`はブロックしないので許可する。

### `evalMethodCall`への追加

```java
private Object evalMethodCall(Expr.MethodCall call) {
    Object target = eval(call.target());
    List<Object> args = new ArrayList<>(call.args().size());
    for (Expr arg : call.args()) {
        args.add(eval(arg));
    }
    return switch (target) {
        case List<?> list -> listMethod(uncheckedList(list), call, args);
        case Set<?> set -> setMethod(uncheckedSet(set), call, args);
        case Map<?, ?> map -> dictMethod(uncheckedMap(map), call, args);
        case MicraSemaphore sem -> semaphoreMethod(sem, call, args);
        default -> throw new MicraLangException(call.line(),
                typeName(target) + " has no methods (tried ." + call.name() + "())");
    };
}

private Object semaphoreMethod(MicraSemaphore sem, Expr.MethodCall call, List<Object> args) {
    return switch (call.name()) {
        case "post" -> {
            requireMethodArgCount(call, args, 0);
            sem.post();
            yield MicraNone.INSTANCE;
        }
        case "wait" -> {
            requireMethodArgCount(call, args, 0);
            if (isrContext) {
                throw new MicraLangException(call.line(),
                        "wait() cannot be called from an interrupt handler (would block) - use post() instead");
            }
            sem.await();
            yield MicraNone.INSTANCE;
        }
        default -> throw unknownMethod(call, "semaphore", "post, wait");
    };
}
```

### `semaphore()`ビルトイン

```java
case "semaphore" -> {
    requireArgCount(call, 0);
    yield new MicraSemaphore(0);
}
```
`GENERAL_PURPOSE_BUILTINS`と`ISR_SAFE_BUILTINS`の両方に`"semaphore"`を追加する
(上記参照)。常に許可数0個で始まる(ISR/タスクがpost()してから他のタスクがwait()する、
という標準的な生産者/消費者パターン専用)。

### `typeName`/`stringify`への追加

```java
// typeName
if (v instanceof MicraSemaphore) return "semaphore";

// stringify (depth引数を取るほうのメソッド)
if (v instanceof MicraSemaphore) return "<semaphore>";
```

## `sleep_ticks(n)`の設計

**この節はCodexレビューで2件の確実な誤りが見つかったため全面的に書き直した:**
(1) 既存の`blockOn`が`MAIN_THREAD_TIMEOUT_SECONDS=5`秒の固定タイムアウトを持つため、
`sleep_ticks(200)`(20TPSで約10秒)のような長めの待機は**必ず5秒でタイムアウトし
`RuntimeException`になる**(そもそも「動くsleep_ticks」になっていなかった)。
(2) 既存の`PacedActionQueue.tick()`は**先頭要素だけを見るFIFO**であり、長い
`sleep_ticks`のエントリが先頭に居座ると、それより後に積まれた**別のタスク/
メインスクリプトの**readyAtTickがとっくに来ているmove/till等のエントリまで
一切実行されなくなる(＝1つのタスクの長い睡眠が他の全てのペースドアクションを
道連れに止めてしまう、「タスクが独立してペースする」という設計の前提そのものが
壊れる致命的な誤り)。両方とも実ソース(`LiveDroneApi.java`/`PacedActionQueue.java`)
を照合して確認済み。

### (1) `blockOn`のタイムアウトをticks数に応じて伸ばす

```java
private static final long MAIN_THREAD_TIMEOUT_SECONDS = 5; // 既存の値、意味も変えない

// 既存の1引数版はこのまま使う(呼び出し側の意味を変えない)
private <T> T blockOn(CompletableFuture<T> future) {
    return blockOn(future, MAIN_THREAD_TIMEOUT_SECONDS);
}

// 新設: 明示的なタイムアウト秒数を取る版
private <T> T blockOn(CompletableFuture<T> future, long timeoutSeconds) {
    try {
        return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScriptStoppedException();
    } catch (TimeoutException e) {
        throw new RuntimeException("drone action timed out waiting for the main thread", e);
    } catch (ExecutionException e) {
        throw new RuntimeException(e.getCause());
    }
}
```

`DroneApi`:
```java
/**
 * ワールドに触れずに{@code ticks}ゲームtickだけ待つ - タスクが自分を一時停止/ペースさせる
 * ための基本コマンド。move/till/plant/harvestと全く同じtick駆動のペーシング機構
 * (サーバーの実tickカウンタ基準)を使うので、サーバーが重くて実tickが遅れても正しく動く。
 * ticks &lt;= 0 は即座に成功する(それでもメインスレッドとの1往復分のコストはかかる、
 * 他のコマンドと同じ)。非有限(NaN/Infinity)は0として扱う(この言語で NaN/Infinity を
 * 作る手段はほぼ無い - {@code /} と {@code %} は0除算をMicraLangExceptionで止める - ので
 * 実質到達しない防御的な扱い)。
 */
void sleepTicks(double ticks);
```

`LiveDroneApi`: `dispatch(Supplier<Attempt>)`を一般化し、`successDelayTicks`に応じて
`blockOn`のタイムアウトも一緒に伸ばす(「ANOMALY検知用の5秒」に「この待機が本来
かかるはずの時間」を上乗せする、という考え方 - 既存のmove/till等は
`successDelayTicks=ACTION_DELAY_TICKS=4`なので`timeoutForTicks(4)`は
`5 + 4/20 = 5`秒とほぼ変わらず、**既存の挙動を壊さない**):
```java
private static final int ACTION_DELAY_TICKS = 4;
private static final long MAX_SLEEP_TICKS = 24_000; // 1昼夜分(20分)、常識的な上限
...
private boolean dispatch(Supplier<Attempt> attempt) {
    return dispatch(ACTION_DELAY_TICKS, attempt);
}

private boolean dispatch(long successDelayTicks, Supplier<Attempt> attempt) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    gateway.runOnMainThread(() -> {
        Attempt result = attempt.get();
        long delay = result.succeeded() ? successDelayTicks : 0;
        long readyAt = gateway.currentTick() + delay;
        pacedQueue.submit(readyAt, () -> {
            if (result.succeeded()) {
                result.apply().run();
            }
            future.complete(result.succeeded());
        });
    });
    return blockOn(future, timeoutForTicks(successDelayTicks));
}

/** 5秒の異常検知マージン + 「この遅延が20TPSで本来かかるはずの時間」。 */
private long timeoutForTicks(long ticks) {
    return MAIN_THREAD_TIMEOUT_SECONDS + (ticks / 20L);
}

@Override
public void sleepTicks(double ticks) {
    long n = Double.isFinite(ticks) ? (long) Math.max(0, Math.min(MAX_SLEEP_TICKS, ticks)) : 0;
    dispatch(n, () -> new Attempt(true, () -> { }));
}
```
既存の`move`/`till`/`plant`/`harvest`/`doAFlip`/`setOutput`/`pairWith`の呼び出し箇所は、
1引数版の`dispatch(Supplier<Attempt>)`(内部で`ACTION_DELAY_TICKS`を渡す)をそのまま使うので
**呼び出し側のコードは変更不要、タイムアウト秒数も実質変わらない**(上記の通り)。

### (2) `PacedActionQueue`の先頭ブロッキングを解消する

現行の`tick(currentTick)`は`peek()`/`poll()`で**先頭要素だけ**を見る実装で、
「エントリは`readyAtTick`の昇順で積まれる」という暗黙の前提に依存している。
これは今まで全ての遅延が`ACTION_DELAY_TICKS=4`固定だったから偶然成立していた
だけで、`sleep_ticks`が任意の(場合によっては数百〜数千tick先の)`readyAtTick`を
持つエントリを積めるようになると、後から積まれた「もっと近い将来」のエントリが
先頭の「まだ遠い将来」のエントリに塞がれて実行されなくなる。

修正: キュー全体を走査し、`readyAtTick`が来ている物だけを取り除いて実行する
(**取り除く順序=元の投入順序を保つ** - `ConcurrentLinkedQueue`のイテレータは
挿入順を守るため、既存のjavadocが約束する「submission order」の意味はそのまま
保たれる):
```java
public final class PacedActionQueue {
    private record Entry(long readyAtTick, Runnable apply) {}

    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();

    public void submit(long readyAtTick, Runnable apply) {
        pending.add(new Entry(readyAtTick, apply));
    }

    /** Runs every entry whose readyAtTick has arrived, in submission order - regardless of where
     *  in the queue it sits (a still-pending far-future entry, e.g. from a long sleep_ticks() call,
     *  must not block a later-submitted but nearer-future entry from running on time). */
    public void tick(long currentTick) {
        List<Entry> ready = new ArrayList<>();
        Iterator<Entry> it = pending.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.readyAtTick() <= currentTick) {
                ready.add(entry);
                it.remove();
            }
        }
        for (Entry entry : ready) {
            entry.apply().run();
        }
    }
}
```
既存の3件のテスト(`PacedActionQueueTest`)はいずれもreadyAtTick昇順で投入している
ケースなので無改修で通る。新規テストとして「先に長い遅延のエントリを積み、後から
短い遅延のエントリを積んでも、短い方が先に実行される」ケースを追加する
(下記「検証方法」参照)。

`FakeDroneApi`:
```java
@Override
public synchronized void sleepTicks(double ticks) {
    calls.add("sleep_ticks:" + ticks);
}
```
実時間は待たない(テストは呼び出しの記録だけで十分)。`synchronized`にする理由は
下記「共有可変状態についての注記」を参照。

`Interpreter.evalCall`:
```java
case "sleep_ticks" -> {
    api.sleepTicks(asDouble(argAt(call, 0), call.line()));
    yield MicraNone.INSTANCE;
}
```
`GENERAL_PURPOSE_BUILTINS`には**入れない**(ドローン/ワールドに触れる区分のまま、
`statementsSinceApiCall`が正しくリセットされ、`sleep_ticks`だけをループさせる
タスクが暴走ループ扱いされないようにするため)。

## `TaskRegistry`

```java
package io.github.khayashi4337.micradrone.lang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * create_taskが生成したタスクの名前と{@link Thread}の対応。コントローラの生存期間
 * (Runをまたいで)そのものと一致するよう{@link
 * io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity}が保持し、
 * 次のRun開始時・Stop時・ブロック破壊時に{@link #stopAll()}される - タスクが
 * 「生みの親」のスクリプトの生存期間を超えて追跡不能なまま生き残ることを防ぐ。
 */
public final class TaskRegistry {
    /**
     * tryReserveからbindまでの間だけ名前に紐づく、無害なプレースホルダ。一度もstart()しない
     * Threadなので、この間に{@link #stopAll()}が走ってinterrupt()されても何の意味も持たず、
     * 実際のタスクスレッドや呼び出し元スレッドには一切影響しない(呼び出し元スレッド自身を
     * プレースホルダに使うと、その短い窓でstopAll()が走った場合に無関係な呼び出し元スレッドを
     * 誤って中断させてしまう)。
     */
    private static final Thread RESERVING_PLACEHOLDER = new Thread();
    /**
     * 同時生存タスク数の上限。`while True: create_task(str(random()), 5, 0, noop)`のような
     * スクリプトがOSスレッドを無制限に生成してサーバーのメモリ/スレッド資源を食い潰す
     * (自傷的だが実害のある)DoSを防ぐための安全弁。子供向け学習用途としては十分に余裕のある
     * 数だが、無制限ではない。
     */
    private static final int MAX_CONCURRENT_TASKS = 16;

    // Codexレビュー指摘: `tasks.size() >= MAX_CONCURRENT_TASKS`という素朴なチェックは
    // 複数スレッドが同時に`tryReserve`すると全員が「上限未満」を観測してから登録できてしまい
    // (チェックしてから行動するまでの間に他のスレッドが割り込める、典型的なTOCTOUレース)、
    // DoS対策としては弱すぎる。`Semaphore`のpermit数で同時生存数を数えることで、
    // 「今何個埋まっているか」の判定自体をアトミックにする。
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_TASKS);
    private final Map<String, Thread> tasks = new ConcurrentHashMap<>();
    // Codexレビュー指摘: stopAll()の後、そのタスクがinterrupt()を処理しきる前にまだ
    // create_taskが呼べてしまうと、「Stopしたのに新しいタスクが生き残る」というレースになる。
    // stopAll()が呼ばれた後は次にreopen()されるまで新規予約を拒否することでこれを防ぐ。
    private volatile boolean accepting = true;

    /** 名前を予約する。既に使われている・同時生存数の上限に達している・stopAll()後で
     *  まだreopen()されていない、のいずれかなら false。 */
    public boolean tryReserve(String name) {
        if (!accepting) {
            return false;
        }
        if (!slots.tryAcquire()) {
            return false; // 上限到達(Semaphoreによりアトミックに判定)
        }
        if (tasks.putIfAbsent(name, RESERVING_PLACEHOLDER) != null) {
            slots.release(); // 名前が既に使用中 - 確保したスロットを返す
            return false;
        }
        return true;
    }

    /** 予約済みの名前に実際のタスクスレッドを結び付ける(tryReserveの後、start()の前に呼ぶ)。 */
    public void bind(String name, Thread thread) {
        tasks.put(name, thread);
    }

    public void release(String name) {
        if (tasks.remove(name) != null) {
            slots.release();
        }
    }

    /** 生存中の全タスクにinterrupt()する。以後は{@link #reopen()}するまで新規予約を拒否する。
     *  スクリプトの再実行前・Stop時に呼ぶ。 */
    public void stopAll() {
        accepting = false;
        for (Thread t : tasks.values()) {
            t.interrupt();
        }
    }

    /** 新しいRunの開始時、stopAll()の直後に呼ぶ - 新規タスクの受け付けを再開する。 */
    public void reopen() {
        accepting = true;
    }
}
```

## `InterruptTable`

```java
package io.github.khayashi4337.micradrone.lang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** faceの名前(任意の文字列)→ISRハンドラの対応。attach_isrで登録、raise_interruptで発火。 */
public final class InterruptTable {
    private final Map<String, MicraFunction> handlers = new ConcurrentHashMap<>();

    public void attach(String face, MicraFunction fn) {
        handlers.put(face, fn);
    }

    public MicraFunction handlerFor(String face) {
        return handlers.get(face);
    }

    /** スクリプトの再実行前に呼ぶ - 前の版のスクリプトが登録したハンドラを引き継がない。 */
    public void clear() {
        handlers.clear();
    }
}
```

## `TaskRegistry`/`InterruptTable`の所有者について(重要な訂正)

**(Fable5.1レビューで発見された確実な誤りの修正)** 当初案は`TaskRegistry`/
`InterruptTable`を`DroneScriptRunner`のフィールドとして持たせ、「スクリプトの
再実行をまたいで保持される」ことを前提にしていたが、これは**実際のプロダクション
経路と矛盾する**: `DroneControllerBlockEntity.startFreshRun`(775行目)は
Runのたびに`scriptRunner = new DroneScriptRunner(api, this::appendLog, debug);`と
**新しいDroneScriptRunnerインスタンスを毎回作り直している**。`DroneScriptRunner`
自身にレジストリを持たせても、次のRunで丸ごと新しいインスタンスに置き換わって
しまうため、前回のタスクは誰からも参照されないまま孤児化する(テストで使う
「1つの`DroneScriptRunner`を使い回すコード」でしか正しく動かない、実際のゲーム内
では機能しない設計だった)。

**正しい所有者は`DroneControllerBlockEntity`**(1つの設置済みコントローラにつき
1つ、Runをまたいで生存する)。`DroneScriptRunner`自体は変更不要
(コンストラクタも既存のまま) - `DroneControllerBlockEntity`が`TaskRegistry`/
`InterruptTable`を保持し、`Interpreter`に渡す経路は`DroneScriptRunner`を
経由せず`DroneControllerBlockEntity`が直接組み立てる必要がある。このため
**`DroneScriptRunner`に新しいコンストラクタを追加する**(既存の2引数/3引数
コンストラクタはテスト用にそのまま残す):
```java
// DroneScriptRunner.java
private final TaskRegistry taskRegistry;
private final InterruptTable interruptTable;

public DroneScriptRunner(DroneApi api, Consumer<String> logSink) {
    this(api, logSink, null);
}
public DroneScriptRunner(DroneApi api, Consumer<String> logSink, DebugController debug) {
    this(api, logSink, debug, new TaskRegistry(), new InterruptTable());
}
/** DroneControllerBlockEntityがコントローラ単位で保持するレジストリを注入する経路。 */
public DroneScriptRunner(DroneApi api, Consumer<String> logSink, DebugController debug,
        TaskRegistry taskRegistry, InterruptTable interruptTable) {
    this.api = api;
    this.logSink = logSink;
    this.debug = debug;
    this.taskRegistry = taskRegistry;
    this.interruptTable = interruptTable;
}

public synchronized void stop() {
    Thread t = worker;
    if (t != null) {
        t.interrupt();
    }
    taskRegistry.stopAll(); // Stopは「このスクリプトが始めた全部」を止める、という意味にする
}

void runProgram(List<Stmt> program) {
    try {
        new Interpreter(api, debug, taskRegistry, interruptTable).run(program);
        ...
```
`start()`自体は変更不要(`taskRegistry.stopAll()`/`interruptTable.clear()`は
**呼び出し元の`DroneControllerBlockEntity.startFreshRun`が新しい`DroneScriptRunner`を
作る直前に呼ぶ** - 下記「`DroneControllerBlockEntity`/`DroneControllerBlock`の変更点」
を参照)。

**重要**: トップレベルのスクリプトが正常終了しても`taskRegistry.stopAll()`は呼ばない
(`Interpreter.run()`の中からも呼ばない)。`create_task`で作ったタスクは、本物のRTOSの
「main()がタスクを作って抜けた後もタスクは動き続ける」という感覚に合わせて、
スクリプト本体の終了後もそのまま動き続ける。停止できるのは次のRun開始時か、
明示的なStopのときだけ。

## `DroneControllerBlockEntity`/`DroneControllerBlock`の変更点(Minecraft側の結線)

**この節は当初「Minecraft側の新しい観測可能な挙動は無いので実機確認不要」としていたが、
上記の訂正により誤りだったので削除する。以下の変更は実際にコントローラの挙動に
影響するため、下記「検証方法」に実機確認を追加する。**

`DroneControllerBlockEntity`にフィールドを追加(コントローラの生存期間そのもの、
Runをまたいで保持):
```java
private final TaskRegistry taskRegistry = new TaskRegistry();
private final InterruptTable interruptTable = new InterruptTable();

/** ブロック破壊時(DroneControllerBlock#onRemove)に呼ぶ。孤児タスクを残さない。 */
public void stopAllTasks() {
    taskRegistry.stopAll();
}
```
`startFreshRun`(721行目)の、`scriptRunner = new DroneScriptRunner(...)`より前に
追加:
```java
taskRegistry.stopAll();   // 前回実行が残したタスクを後始末してから新しく始める
taskRegistry.reopen();    // stopAll()が閉じた新規予約の受け付けを再開する(重要:
                           // stopAll()単体では次のRunでcreate_taskが使えないままになる)
interruptTable.clear();   // 前の版のスクリプトのISRハンドラを引き継がない
...
scriptRunner = new DroneScriptRunner(api, this::appendLog, debug, taskRegistry, interruptTable);
```
`stopScript`(798行目)に1行追加:
```java
public void stopScript(ServerPlayer requester) {
    addViewer(requester);
    if (scriptRunner == null) {
        return;
    }
    scriptRunner.stop(); // stop()は自分の持つtaskRegistryも既にstopAllする(上記参照)
    appendLog("[stop] stop requested");
}
```
(`scriptRunner.stop()`が内部で`taskRegistry.stopAll()`するので実質重複するが、
`DroneScriptRunner`が保持するレジストリと`DroneControllerBlockEntity`が保持する
レジストリは**同一インスタンス**(`startFreshRun`で注入している)なので、
`stopAllTasks()`とあわせて二重に呼んでも安全 - `Thread.interrupt()`は
何度呼んでも安全な操作。)

`DroneControllerBlock.onRemove`(123行目)に1行追加(既存の`discardDroneEntity()`
と同じ場所・同じ条件):
```java
protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
    if (state.hasBlockEntity() && !state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
        be.discardDroneEntity();
        be.stopAllTasks(); // 追加: ブロックが壊れたらタスクも道連れに止める
    }
    super.onRemove(state, level, pos, newState, movedByPiston);
}
```

**既知の残課題(今回は対応しない、正直に明記する)**: チャンクアンロード時・
サーバー停止時にはこの`stopAllTasks()`は呼ばれない。特にサーバー停止後は
`MinecraftServer.isStopped()`が真になり`BlockableEventLoop.execute`が
呼び出し元スレッド上でインライン実行されるため、生き残ったタスクスレッドが
直接`grid`をいじる経路が理論上あり得る(Fable5.1レビュー指摘)。シングルプレイで
ワールドをすぐ再度開かない限り実害は小さいと判断し、今回はこの2つのフックの
追加は見送るが、将来の課題として明記しておく(本物の組み込み機器における
「電源を物理的に切った瞬間に何が起きるか」を厳密に定義しないのと同種の、
意図的な簡略化)。

## 再実行時の名前衝突についての既知の限界(意図的に複雑な対策をしない)

`stopAll()`は`interrupt()`を送るだけの非同期操作で、タスクスレッド自身が
自分の`finally`で`taskRegistry.release(name)`するまでは名前がまだ「使用中」の
ままになる。このため、Stop直後や再Runの直後に**全く同じ名前**で`create_task`を
すぐ呼ぶと、ごく短い時間(通常は数マイクロ秒〜数ミリ秒)だけ`DENIED`が返る
ことがある(古いタスクが完全に死に切る前に新しい予約が来るレース)。

これを`Thread.isAlive()`/`isInterrupted()`を見て即座に上書きするような仕組みで
解消することも検討したが、チェックと登録の間に新たな競合を生むだけで
根本的な解決にならない上、複雑さに見合わない(このレースは自己修復的 - 古い
タスクは放っておいてもすぐ死ぬ、再試行すれば通る)。**今回は複雑な対策をせず、
既知の限界として明記するに留める**(ヘルプ巻物にも「同じ名前のタスクを間を
置かず連続で作り直すとDENIEDになることがある、少し待つか一度Stopしてから
再試行してください」という一言を入れる)。

## 共有可変状態・並行アクセスについての注記(Codexレビュー指摘、意図的に「直さない」)

以下3点は、複数タスクを許すという設計そのものから必然的に生じる特性であり、
「バグを直す」のではなく「本物のRTOS/並行プログラミングと同じ注意が要る」と
**正直にドキュメント化する**方針で対応する(エンジン側で自動的に安全にしようと
すると、より大掛かりなアーキテクチャ変更が要り、この機能追加の範囲を超える)。

1. **共有list/dict/setはスレッドセーフではない**: `create_task`/`raise_interrupt`が
   使う「グローバルの浅いコピー」は、同じ`ArrayList`/`LinkedHashMap`/`LinkedHashSet`
   オブジェクトを複数のタスクスレッドから見えるようにする。複数のタスクが同じ
   リストに同時に`.append()`する等をすると、破損・`ConcurrentModificationException`・
   可視性不良が起こり得る。**これは本物の組み込みRTOSで複数タスクが共有メモリを
   扱う時にmutex/semaphoreで保護しなければならないのと全く同じ理由**であり、
   `semaphore()`が存在するのはまさにこの問題を解決するため。ヘルプ巻物に
   「複数のタスクから同じlist/dict/setを読み書きするなら、semaphoreで挟んで
   排他制御すること」という注記を入れる。
2. **複数タスクが同時にmove()等を呼ぶと、判定と反映の間のタイムラグにより
   意図通りに直列合成されないことがある**: `LiveDroneApi.dispatch`は
   「今すぐ成否を判定するが、実際の変更は数tick後に反映する」という構造
   (`attempt.get()`は現在の状態を読んで判定するが、書き込みは`pacedQueue`に
   積まれて後で実行される)。2つのタスクがほぼ同時に`move("east")`すると、
   両方とも変更前の同じ座標を読んで判定するため、後から効いた方が先の効果を
   上書きしてしまう可能性がある(2回の移動が合成されず1回分になる等)。
   これも**単一の物理的なドローンという共有ハードウェアリソースを複数タスクが
   保護なしに操作している**という、本物のRTOSと同じ状況であり、正しく直列に
   動かしたいスクリプトは`semaphore()`でドローン操作を挟んで排他制御すべき、と
   ヘルプ巻物に明記する。エンジン側でコマンドキュー全体を直列化する対応は、
   既存の`LiveDroneApi`/`PacedActionQueue`の設計をより大きく変える話になるため
   今回は行わない。
3. **Stopしても既にペースドキューに積まれた変更まではキャンセルされない**
   (Codexレビュー指摘): `blockOn`がinterruptされて`ScriptStoppedException`に
   なっても、`pacedQueue.submit(...)`で既に積んだ`result.apply()`はキャンセル
   されず、後のtickでそのまま実行される。**これはcreate_taskが導入する新しい
   問題ではなく、move/till/plant/harvestが最初から持っていた既存の特性**
   (Stop前から同じ`dispatch`/`PacedActionQueue`の仕組みを使っているため)。
   タスクにも同じ特性がそのまま引き継がれる、というだけであり、今回のRTOS
   基盤固有の新しい欠陥ではない。`PacedActionQueue`にキャンセルトークン/世代
   番号を持たせる対応は、既存のmove等も含めた広い範囲の設計変更になるため
   今回のスコープ外とする。

## サンプルスクリプトの内容(`BLINK_TASK`)

```
# RTOS-style: a background task blinks the plot's marker output forever while
# the main script is free to do something else. Uses semaphore()/create_task()/
# sleep_ticks() - see the help scroll's "RTOS tasks" section for what each one does.
def blink():
    while True:
        set_output(True)
        sleep_ticks(10)
        set_output(False)
        sleep_ticks(10)

create_task("blinker", 5, 0, blink)
print("blinker task started - it keeps running after this script ends")
```
`SampleCatalog`には本棚15冊・ラピス3(本MOD最高難度、`def`必須知識+新概念のため)で登録。

### `SampleScriptsTest`への影響(Fable5.1レビューで発見、対応必須)

既存の`SampleScriptsTest.everySampleParsesAndRunsWithoutError`(24-30行目)は
`SampleScripts.ALL`の**全件**を`new Interpreter(api).run(parse(source))`で
実行するだけで、生成されたタスクを誰も後始末しない。`BLINK_TASK`は
`budget_ticks=0`(無期限)+`FakeDroneApi.sleepTicks`が実時間を待たない実装
なので、何も対策しないと生成された"blinker"タスクスレッドがテストJVMの中で
CPUを使い切る勢いで回り続ける(デーモンスレッドなのでJVM終了は妨げないが、
テストスイート全体の実行中ずっとCPU/メモリを食い続ける実質的なリーク)。

対策として`Interpreter`に新規publicメソッドを追加する:
```java
/** このInterpreterがcreate_taskで生成した全タスクにinterrupt()する。テストや、
 *  トップレベルスクリプトを外側から管理する側(DroneScriptRunner等)が使う。 */
public void stopAllTasks() {
    taskRegistry.stopAll();
}
```
`SampleScriptsTest.everySampleParsesAndRunsWithoutError`を次のように直す
(全サンプル共通、`BLINK_TASK`以外は何もタスクを作らないので`stopAllTasks()`は
無害なno-op):
```java
@Test
void everySampleParsesAndRunsWithoutError() {
    for (String source : SampleScripts.ALL.values()) {
        FakeDroneApi api = new FakeDroneApi(3);
        Interpreter interpreter = new Interpreter(api);
        try {
            interpreter.run(parse(source));
        } finally {
            interpreter.stopAllTasks();
        }
    }
}
```
`run()`(トップレベルスクリプト本体、`create_task`を1回呼んで即座にprintして
終わるだけ)が返った直後に`stopAllTasks()`するので、生成された"blinker"タスクは
(既にスレッドが開始済みでも)ごく僅かな統計回数の反復しかできないうちに
`interrupt()`され、`checkCancellation`が次の文の実行前に必ずチェックする
ため、実質的な被害(CPU専有・メモリ増大)は起きない。

## 検証方法

- 言語コア(`Interpreter`/`MicraSemaphore`/`TaskRegistry`/`InterruptTable`)と
  `LiveDroneApi`/`PacedActionQueue`はMinecraft非依存(`FakeDroneApi`/`FakeGridState`/
  `FakeMainThreadGateway`/直接インスタンス化)なのでJUnitで検証できる。
  `gradlew build`成功+全テストパスを実測確認してからコミット。
- 確認すべき境界(言語コア): 名前衝突でDENIED、非0引数関数でDENIED、
  **不正なbudget_ticks(NaN/負数/Infinity)でDENIED、budget_ticks=0(有効な明示値)は
  無期限として受理**、同時生存数の上限(MAX_CONCURRENT_TASKS)に達するとDENIED、
  そのうち1つが終わると再びACCEPTEDになる(Semaphoreベースの実装で検証)、
  `stopAll()`後は`reopen()`するまで新規`create_task`がDENIEDになる、
  複数タスクが並行して同じsemaphoreをpost/waitできる、budget_ticks超過で
  interrupt()が送られる(「強制終了」ではなく協調的停止)、budget_ticks=0で
  無期限に動くタスクを`stopAll()`で止められる、タスク/ISRハンドラの本体内の
  ローカル変数代入がフォーク先グローバルを汚染しない(通常の関数呼び出しと同じ
  スコープ規則)、ISR内でmove()等を呼ぶとMicraLangException、ISR内で
  semaphore.post()は成功しwait()はMicraLangException、ISR内でも純粋計算しか
  しないユーザー定義関数の呼び出しは成功する(その関数が内部でビルトインを
  呼んで初めて失敗する)、未登録faceのraise_interruptは何もしない、attach_isrの
  二重登録は上書き、`while True: create_task(...)`(常にDENIED)と
  `while True: raise_interrupt("no_such_face")`(常に空振り)のどちらも
  `RUNAWAY_STATEMENT_THRESHOLD`で正しく止まる(暴走検知すり抜けの回帰テスト)、
  `create_task(4引数)`/`attach_isr(2引数)`が実際に成功する(argAtの誤用による
  引数個数エラーの回帰テスト)。
- 確認すべき境界(`PacedActionQueue`): 先に長い遅延のエントリを積み、後から短い
  遅延のエントリを積んでも、短い方が先に(投入順を保ったまま)実行される
  (先頭ブロッキング修正の回帰テスト)。
- 確認すべき境界(`LiveDroneApi.sleepTicks`、`FakeMainThreadGateway`経由の
  end-to-endテスト、`DroneScriptRunnerTest`と同型): `sleep_ticks(200)`(約10秒
  相当)がタイムアウトせずに完了する(5秒固定タイムアウトの回帰テスト)。
- 確認すべき境界(Minecraft側の結線、`DroneScriptRunnerTest`/`DroneControllerBlockEntity`
  相当のテストダブル経由): スクリプトを2回連続でRunすると1回目のタスクが後始末
  される、Stop後は新規タスクを作れない状態のまま次のRunまで維持される。
- **実機確認が必要(当初「不要」としていたが誤りだったので訂正 - Fable5.1レビュー
  指摘)**: `DroneControllerBlockEntity`/`DroneControllerBlock`への結線は
  実際にコントローラの挙動に影響する。以下を林さんに依頼する:
  1. `BLINK_TASK`をRun→スクリプト自体はすぐ終わるが、マーカーの出力が点滅し
     続けること(タスクがスクリプト終了後も生きている)。
  2. 点滅中にもう一度Run(同じ"blinker"という名前で`create_task`)→前回のタスクが
     後始末され、新しいタスクだけが点滅を続けること(2つのタスクが二重に
     動いて点滅が乱れないこと)。
  3. 点滅中にStopを押す→点滅が止まること。Stop直後にもう一度Runし直せば
     再び点滅が始まること(既知の限界: ごく短い間`create_task`がDENIEDに
     なることがあるが、少し待てば通る)。
  4. 点滅中にコントローラを破壊する→タスクスレッドが残り続けない(ログ・
     サーバーの様子で明らかな異常が無いこと)。
  - **既知の未対応(実機確認の対象外、正直に明記)**: チャンクアンロード・
    サーバー停止時の後始末は今回のスコープ外(上記「`DroneControllerBlockEntity`/
    `DroneControllerBlock`の変更点」の既知の残課題を参照)。

## 禁止事項

- `pwm_set`を「とりあえず値を保存するだけ」の形で追加しない(上記スコープ節参照)。
- ISRコンテキストのゲートを`ISR_SAFE_BUILTINS`以外の個別列挙で実装しない
  (将来ビルトインが増えるたびに同期が必要になり、抜け漏れで実際にメインスレッドを
  長時間止めるリスクを埋め込む)。`GENERAL_PURPOSE_BUILTINS`(暴走検知カウンタの
  リセット可否)と`ISR_SAFE_BUILTINS`(ISR内で呼んでよいか)は**目的も
  メンバーシップも別の集合**であることを混同しない(`create_task`/`attach_isr`/
  `raise_interrupt`は前者には入るが後者には入らない)。
- `create_task`/`raise_interrupt`が使う「グローバルの浅いコピー」を深いコピーにしない
  (semaphoreの共有ができなくなり、ISR→タスクの通知パターンそのものが壊れる)。
- タスクの優先度について「必ずその順で実行される」等、`Thread.setPriority`が保証しない
  確定的な順序を暗示する文言をドキュメント・javadocに書かない。同様に
  budget_ticks超過を「強制終了」と表現しない(`Thread.interrupt()`は協調的な
  停止シグナルであり、正確には「停止シグナルを送る」)。
- `MAX_CONCURRENT_TASKS`のような安全弁を「面倒だから」省略しない、またはTOCTOUの
  余地がある素朴な`size()`比較で済ませない(`Semaphore`ベースのアトミックな
  実装にする)。
- `argAt(call, i)`を、既に`requireArgCount`で総数を確定させた後の2引数目以降の
  読み出しに使わない(内部で`requireArgCount`を再実行するため、単一引数の
  ビルトイン以外では確実に失敗する)。
- `PacedActionQueue.tick()`を「先頭要素だけ見る」実装に戻さない(sleep_ticksが
  任意の長さの遅延を持てるようになった時点で、この実装は他の全ての保留中の
  ペースドアクションを道連れに止めてしまう)。
- `sleep_ticks`の待機を、既存の`blockOn`の固定5秒タイムアウトのまま実装しない
  (ticks数に応じてタイムアウトも伸ばす、上記「`sleep_ticks(n)`の設計」参照)。
- タスク本体・ISRハンドラの実行を、新しい子フレームを作らず`forkedGlobal`
  そのものに対して直接`execBlock`しない(通常の関数呼び出しと異なるスコープ
  規則になってしまう、上記「タスク本体・ISRハンドラの実行経路」参照)。
