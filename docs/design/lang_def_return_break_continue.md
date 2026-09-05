# 実装設計書: ユーザー定義関数(def/return/break/continue/pass)

作成日: 2026-09-05(セルフレビュー3回+Codexレビュー+Fable5.1独立レビューを経て改訂)
関連: `C:\Users\kh\.claude\plans\sorted-noodling-bachman.md` の
「言語拡張: ユーザー定義関数(def)とRTOSタスク基盤」セクション(背景・動機はそちら)。

**この文書の目的**: 各タスクを別々のエージェントが実行しても、前提ファイルを
見失ったり同じ役割のファイルを重複作成したりしないよう、対象ファイルと
成果物を閉じたリストとして明示する。ここに無いファイルを新規作成したくなったら、
作業を止めてこの文書に追記してから進めること。

**改訂履歴**: 初版はセルフレビュー3回で行番号誤り3件を修正したのみだったが、
Codexと独立なFable5.1(全くの初見でこの設計書とソースを読ませた)の2レビューが
独立に、①`RETURN`が`statement()`の分岐リストから抜けている、②`callFunction`の
提示コードで`yield`(switch式専用)を誤用している、③`loopDepth`が関数の境界で
リセットされず関数内の不正なbreak/continueを受理してしまう、④`DebugController.java`/
`DebugControllerTest.java`をT2で編集すると書きながら対象ファイル一覧に無い、
という4点を**両方とも独立に**指摘した。一致した指摘は特に確度が高いと判断しそのまま
採用。加えて各レビューが単独で見つけた指摘(Fable: ネストした`def`が自己再帰できない・
`CommandNamesTest.java`が即座に壊れる・暴走検知の修正案自体に新しい脆さがある。
Codex: `env`宣言の引用矛盾・「Pythonと同じ」という言い過ぎ・`callDepth`の増減順序)も
実ソースで裏取りの上ですべて反映した。

**改訂履歴(2回目、実装コードのレビュー)**: T1のコード実装後、実装コード自体を
Codex・独立なFable5.1(コード読みのみの2回目インスタンス)にレビューしてもらった。
両者とも「T1の実装自体に確実な誤りは無い」と判定(loopDepthリセット・ネストしたdef
禁止・evalCall早期解決・callDepth増減順序を、それぞれ実ソースの行番号を挙げて
独立に検証済みと報告)。ただし両者が収束して指摘した重要な懸念が1件あった:
`evalCall`の早期関数解決が、組み込み名を**普通の変数として代入**した後にその
組み込みを呼ぶ既存スクリプト(例: `max = 0` の後で `max(a, b)`)を壊しうる
(このmodは既にCurseForgeで公開済み)。`!CommandNames.ALL.contains(call.name())`を
追加条件にして修正済み(下記Interpreterの変更点を参照)。他にCodex・Fable5.1
両方が「MAX_CALL_DEPTH=200の実測はより重い呼び出し1段あたりのJavaフレーム数でも
確認すべき」と収束して指摘し、list/dict literal・算術式を含む重い再帰形状の
テストを`DroneScriptRunnerTest.java`に追加、実測で安全性を確認した。他の単独
指摘(関数内ループを跨ぐreturn・while/for-list上でのbreak/continue・入れ子
ループでのbreakが最内だけに効くこと・関数呼び出しをまたいだスコープ分離・
第一級関数値としての受け渡し・callDepthの境界値199/200)もテストとして追加した。
`Environment.set`には`Objects.requireNonNull`を追加(将来DroneApiがnullを返す
ようになった場合の防御、Fable5.1の提案)。

## 対象ファイル一覧(クローズドリスト)

### 既存ファイル(編集のみ、絶対に新規作成しない)

| パス | 現在の行数 | このタスクでの役割 |
|---|---|---|
| `src/main/java/io/github/khayashi4337/micradrone/lang/TokenType.java` | 19 | `DEF`/`RETURN`/`BREAK`/`CONTINUE`/`PASS`トークン種別を追加 |
| `src/main/java/io/github/khayashi4337/micradrone/lang/Lexer.java` | 271 | `KEYWORDS`マップ(15-27行目)に5語を追加。`keywords()`(55行目)は変更不要(マップから自動的に拾われる) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/ast/Stmt.java` | 24 | 下記「Stmt.javaに追加するrecordの正確な形」を参照(sealed interfaceなので追加した瞬間`Interpreter.execStmt`のswitchがコンパイルエラーになる。これは同時に直す必要があるという安全網) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/Environment.java` | 24 | 単一のフラットなHashMapから、parentを持つスコープチェーンに書き換え |
| `src/main/java/io/github/khayashi4337/micradrone/lang/Interpreter.java` | 817 | 下記「Interpreterの変更点」を参照 |
| `src/main/java/io/github/khayashi4337/micradrone/lang/Parser.java` | 350 | `statement()`(28行目)にDEF/RETURN/BREAK/CONTINUE/PASSの分岐を追加。新規`defStmt()`メソッドを追加。`loopDepth`/`funcDepth`のフィールドを追加 |
| `src/main/java/io/github/khayashi4337/micradrone/lang/DebugController.java` | 136 | T2でjavadoc更新(下記T2参照)。**コードの機能変更は無し** - `enterLoop`/`exitLoop`の深度カウンタはそのまま関数呼び出しにも使い回せる設計のため |
| `src/main/java/io/github/khayashi4337/micradrone/client/IdeScreen.java` | - | T2で70行目付近のjavadocコメント文言のみ更新(「Step Out (of the current loop)」が関数にも及ぶようになるため)。**GUIロジックの変更は無し** |
| `src/test/java/io/github/khayashi4337/micradrone/lang/LexerTest.java` | - | 新トークン化のテストを追加 |
| `src/test/java/io/github/khayashi4337/micradrone/lang/InterpreterTest.java` | - | def/return/break/continue/pass・スコープ・再帰上限・組み込み名の再定義禁止・仮引数重複・break/continue/return外文脈エラー・ネストしたdef禁止・暴走検知の抜け穴が塞がっていることのテストを追加 |
| `src/test/java/io/github/khayashi4337/micradrone/lang/DebugControllerTest.java` | - | T2: 関数呼び出し中のStep Outが正しく動く(関数を抜けて呼び出し元へ戻る)ことを確認するテストを追加 |
| `src/test/java/io/github/khayashi4337/micradrone/lang/CommandNamesTest.java` | - | **必ず修正が要る**。既存の`keywordsMatchesTheLanguagesReservedWords`(34-37行目)が`Lexer.keywords()`を12語のハードコードされた集合と`assertEquals`しているため、DEF/RETURN/BREAK/CONTINUE/PASSを追加した瞬間にこのテストが落ちる。期待値の集合に5語を追加する |
| `src/test/java/io/github/khayashi4337/micradrone/drone/DroneScriptRunnerTest.java` | - | (2026-09-05、Fable5.1レビューで対象ファイル一覧からの漏れを指摘され追記)MAX_CALL_DEPTHが実際のワーカースレッド生成方法(`DroneScriptRunner.start()`の`new Thread(...)`、カスタムスタックサイズ無し)で安全に働くことを実測するテストを追加。Codex・Fable5.1の両方が指摘した「より重い呼び出し1段あたりのJavaフレーム数」でも安全か、実測で確認するテストも含む |

### 新規ファイル(このタスクで作るのはこれだけ)

| パス | 役割 |
|---|---|
| `src/main/java/io/github/khayashi4337/micradrone/lang/MicraFunction.java` | ユーザー定義関数を表す値の型。`public record MicraFunction(String name, List<String> params, List<Stmt> body) {}`。**閉じたスコープの環境(クロージャ)は持たせない** - 下記「クロージャは意図的に非対応」を参照 |
| `src/main/java/io/github/khayashi4337/micradrone/lang/ReturnSignal.java` | returnの制御フロー用例外。`public final class ReturnSignal extends RuntimeException`、ScriptStoppedException.javaと同じパターン(`super(null, null, false, false)`)で、値を1つ持つコンストラクタ`ReturnSignal(Object value)` + `Object value()` |
| `src/main/java/io/github/khayashi4337/micradrone/lang/BreakSignal.java` | breakの制御フロー用例外。`public final class BreakSignal extends RuntimeException`。状態を持たないので、呼ぶたびに`new`せず`public static final BreakSignal INSTANCE = new BreakSignal();`を1つ持つシングルトンにする(コンストラクタは`private`) |
| `src/main/java/io/github/khayashi4337/micradrone/lang/ContinueSignal.java` | continueの制御フロー用例外。同上、`public final class ContinueSignal extends RuntimeException`+`INSTANCE`シングルトン |
| `src/test/java/io/github/khayashi4337/micradrone/lang/EnvironmentTest.java` | スコープチェーン(ローカル優先・グローバルへのフォールバック・シャドーイング)単体のテスト |

### 触らないファイル(参考、混同注意)

- `CommandNames.java` — 変更不要。ここは「組み込み(builtin)コマンド名」の一覧であり、
  ユーザー定義関数の名前はスクリプトローカルなので載せない。新キーワード
  (def等)は`Lexer.keywords()`経由で別途エディタ補完に流れる(コード変更不要)。
- `ast/Expr.java` — 変更不要。`Expr.Call(String name, List<Expr> args, int line)`は
  ユーザー定義関数の呼び出しにもそのまま使える(呼び出し式のASTノードは
  組み込み/ユーザー定義を区別しない)。
- `MicraLangException.java` — 変更不要。新しいエラーメッセージは全て
  既存のコンストラクタ`MicraLangException(int line, String message)`で作る。
- `CommandsHelpDoc.java` / `SampleScripts.java` / `SampleCatalog.java` — このタスクの
  スコープ外(下記T3、先送り可)。
- `DroneApi.java` / `MicraNone.java` / `ScriptStoppedException.java` — 参照のみ、変更しない。
- このコードベースに`ParserTest.java`は存在しない。パーサー単体の新規テストファイルを
  作らず、構文エラー系のテストは`InterpreterTest.java`の`run(String source)`
  ヘルパー(Lexer→Parser→Interpreterを通しで実行する)経由で書く。
  (lang テストパッケージには他に`CommandNamesTest.java`/`SyntaxHighlighterTest.java`/
  `FakeDroneApi.java`もあるが、いずれも本タスクでの役割は上表の通り)

## Interpreter.javaの変更点(詳細)

- フィールド: 現在の`private final Environment env = new Environment();`(40行目)
  から`final`を外す(関数呼び出し中に一時的に差し替えるため)。直後に
  `private final Environment globalEnv = env;`を追加(関数フレームの親は常に
  ここ、呼び出し元のローカルフレームではない)。**注意**: これはPythonの
  クロージャ付きレキシカルスコープと「同じ」ではない - Pythonのネスト関数は
  定義位置の外側ローカル変数を字句的に捕捉できるが、本設計はそれを持たない
  (モジュールレベル=グローバル変数だけが常に見える簡略版)。下記「クロージャは
  意図的に非対応」を参照。
- 新フィールド: `private static final int MAX_CALL_DEPTH = 200;` /
  `private int callDepth = 0;`(200は暫定値。ワーカースレッドはデフォルトの
  スタックサイズで生成されており(`DroneScriptRunner.java`42行目の`new Thread(...)`に
  カスタムサイズ指定なし、確認済み)、素朴な計算では十分安全な値だが、
  **T1完了時に実際のワーカースレッド相当の生成方法で200段の再帰が
  `StackOverflowError`より先に`MicraLangException`で止まることを実測確認する**
  (`DroneScriptRunner.runProgram`のcatchはError系も`instanceof Error`なら
  再スローする実装(73-81行目)なので、もし先にStackOverflowErrorが起きると
  綺麗なエラーメッセージではなく生のJavaスタックトレースが再スローされる)。
- `execStmt`のswitch(72-77行目、各case)に追加:
  - `case Stmt.FunctionDef s -> defineFunction(s);`
  - `case Stmt.ReturnStmt s -> throw new ReturnSignal(s.value() == null ? MicraNone.INSTANCE : eval(s.value()));`
  - `case Stmt.BreakStmt s -> throw BreakSignal.INSTANCE;`
  - `case Stmt.ContinueStmt s -> throw ContinueSignal.INSTANCE;`
  - `case Stmt.PassStmt s -> {}`
- 新規`private void defineFunction(Stmt.FunctionDef s)`: `CommandNames.ALL.contains(s.name())`
  なら`MicraLangException(s.line(), "'"+s.name()+"' is a built-in command and cannot be redefined")`。
  そうでなければ`env.set(s.name(), new MicraFunction(s.name(), s.params(), s.body()))`。
- **`evalCall`の先頭(506行目付近、`List<Expr> args = call.args();`の前)に、
  switch本体そのものより前に割り込ませる**(Fable5.1の指摘を採用: 当初案は
  switchの`default`ケース+`CommandNames.ALL`を使った条件分岐だったが、これは
  手動保守されている`CommandNames.ALL`と`evalCall`のswitchが将来ズレた時に
  正当なスクリプトが誤って「暴走」判定される新しい脆弱性を生む。先頭で
  解決してしまえば、既存の暴走検知リセット行(642-644行目)は**一切変更不要**):
  ```java
  private Object evalCall(Expr.Call call) {
      Object maybeFn = env.tryGet(call.name());
      if (maybeFn instanceof MicraFunction fn) {
          return callFunction(fn, call);
      }
      if (maybeFn != null && !CommandNames.ALL.contains(call.name())) {
          throw new MicraLangException(call.line(),
                  "'" + call.name() + "' is not a function (it is a " + typeName(maybeFn) + ")");
      }
      List<Expr> args = call.args();
      Object result = switch (call.name()) {
          // ... 既存のまま、無変更 ...
      };
      // ... 既存の642-644行目、無変更 ...
  }
  ```
  (`defineFunction`が組み込み名との衝突を既に禁止しているので、ユーザー関数
  として定義された名前と組み込み名の名前空間は重複しない。**訂正
  (2026-09-05、独立なFable5.1レビューの指摘)**: 「ゆえに早期returnを追加しても
  既存のswitchに到達する経路は一切変わらない」という当初の記述は不正確
  だった。組み込み名を**普通の変数として代入**した後にその組み込みを呼ぶ
  スクリプト(例: `max = 0` の後で `max(a, b)` を呼ぶ)は、この分岐が
  `maybeFn != null`だけで判定していると「'max' is not a function」で
  止まってしまう ― このmodは既にCurseForgeで公開済みで、旧実装は
  `evalCall`が`env`を一切見ずに常に組み込みへ直行していたため、こうした
  既存スクリプトを壊しかねない後方互換性の懸念だった。`!CommandNames.ALL.
  contains(call.name())`を追加条件にすることで、**組み込み名と同じ名前の
  ローカル変数がある場合は黙って既存のswitchへフォールスルーする**(旧実装と
  完全に同じ挙動)。組み込みでない名前を関数でない値で呼んだ場合
  (`x = 5` の後 `x()`)は、この段で分かりやすいエラーを出せる ― これは
  以前から「unknown function」で必ず失敗していたので後方互換性の懸念は無い。)
- 新規`private Object callFunction(MicraFunction fn, Expr.Call call)`(**通常メソッド
  なので`return`を使う、`yield`はswitch式専用でここでは使えない**):
  ```java
  private Object callFunction(MicraFunction fn, Expr.Call call) {
      requireArgCount(call, fn.params().size());
      List<Object> argVals = new ArrayList<>();
      for (Expr arg : call.args()) {
          argVals.add(eval(arg)); // 呼び出し元の環境で評価してから切り替える
      }
      if (callDepth >= MAX_CALL_DEPTH) {
          throw new MicraLangException(call.line(), "too much recursion in '" + fn.name() + "'");
      }
      callDepth++;
      Environment previous = env;
      env = new Environment(globalEnv);
      for (int i = 0; i < fn.params().size(); i++) {
          env.set(fn.params().get(i), argVals.get(i));
      }
      enterLoopForDebug(); // 既存のループ用フックをそのまま流用(下記T2参照)
      try {
          execBlock(fn.body());
          return MicraNone.INSTANCE;
      } catch (ReturnSignal r) {
          return r.value();
      } finally {
          env = previous;
          callDepth--;
          exitLoopForDebug();
      }
  }
  ```
  **重要**: 上限チェック→`callDepth++`→`try`の順を守ること。もし`callDepth++`を
  先にして超過時にthrowすると、そのフレームは`try`に入らないまま
  `callDepth`が1残った状態になり、以後の呼び出しの上限判定がずれる
  (Codexの指摘、実測で確認可能)。
- `execWhile`(126-136行目)/`execFor`(143-159行目)/`execForRange`(161-179行目):
  ループ本体の`execBlock(s.block())`を`try { ... } catch (ContinueSignal ignored) {}`で
  包み、ループ全体(既存のenterLoopForDebug〜finally exitLoopForDebugの外側)を
  さらに`try { ... } catch (BreakSignal ignored) {}`で包む。
- `typeName`(765行目)に`if (v instanceof MicraFunction) return "function";`を追加。
- `stringify`(787行目)の最終`return "None";`(807行目)の手前に
  `if (v instanceof MicraFunction fn) return "<function " + fn.name() + ">";`を追加。
- `isTruthy`(726行目)は変更不要(既存の最終行`v != MicraNone.INSTANCE`が
  関数値にも正しく効く)。

### 参考: 既に確認済みの、今回は直さない別の抜け穴(スコープ外)

レビュー中にFable5.1が発見: `print`は`GENERAL_PURPOSE_BUILTINS`(Set.of("len",
"abs","min","max","random","str","list","set","dict"))に含まれておらず、
`LiveDroneApi.print`はペーシング無しでログに流すだけなので、
`while True: print("")`は現状でも暴走検知(`RUNAWAY_STATEMENT_THRESHOLD`)を
回避できる。これは今回のdef追加とは無関係の既存の挙動であり、本タスクの
範囲外。直す場合は別課題としてGitHub Issueを立てること。

## Stmt.javaに追加するrecordの正確な形

```java
record FunctionDef(String name, List<String> params, List<Stmt> body, int line) implements Stmt {}
/** {@code return [expr]} - valueはnull可(引数無しのreturn)。 */
record ReturnStmt(Expr value, int line) implements Stmt {}
record BreakStmt(int line) implements Stmt {}
record ContinueStmt(int line) implements Stmt {}
record PassStmt(int line) implements Stmt {}
```

## クロージャは意図的に非対応、ネストしたdefは禁止(実装者への注意)

`MicraFunction`は定義された時点の環境を一切保持しない。呼び出し時に必ず
`globalEnv`を親として新しいフレームを作る。

**重要な帰結(Fable5.1の指摘で判明)**: これは「外側の関数のローカル変数を
見られない」だけでなく、**関数自身の名前もそのローカル変数の1つ**である
ため、`def outer(): def inner(n): inner(n-1)`のように関数の内側でさらに
`def`すると、`inner`はouterのローカルフレームに束縛される。しかし`inner`が
実際に呼ばれて新しいフレームを作る時、その親は`globalEnv`であって
outerのフレームではないので、`inner`本体から`inner`自身を呼ぼうとすると
「unknown function 'inner'」になり、自己再帰はおろか兄弟のネスト関数も
互いを呼べない。

この壊れた挙動を黙って残すより、**ネストした`def`自体をパース時エラーに
する**(`Parser`に`private int funcDepth = 0;`を追加、`defStmt()`本体の
パース中は`funcDepth`をインクリメントし、`funcDepth > 0`の状態で新たに
`def`が来たら`MicraLangException(line, "nested def is not supported - define functions at the top level")`)。
関数は常にトップレベルで定義する、という制約にすることで、混乱の種を
実装時点で断つ。クロージャ対応が将来必要になったら、それは独立した設計
判断であり、この実装のついでに追加しないこと。

## breakとcontinueはループの外、returnは関数の外では構文エラーにする

現状のままだと`BreakSignal`/`ContinueSignal`/`ReturnSignal`はどこにも
catchされずに`DroneScriptRunner`の`catch (Throwable e)`まで抜け、
プレイヤーには分かりにくいエラーとして見える。Python自身もこれを
コンパイル時(パース時)のSyntaxErrorとして扱っているので、それに倣う:

- `Parser`に`private int loopDepth = 0;`(上記の`funcDepth`と別に)を追加し、
  `whileStmt()`/`forStmt()`の本体パース(`block()`呼び出し)の前後で
  `loopDepth`を増減する。
- **重要(Codex・Fable5.1の両方が独立に指摘した実質的なバグ)**: `defStmt()`が
  本体をパースする直前に、その時点の`loopDepth`を退避して**0にリセット**し、
  本体のパースが終わったら退避した値に**復元**すること:
  ```java
  int savedLoopDepth = loopDepth;
  loopDepth = 0;
  try {
      body = block();
  } finally {
      loopDepth = savedLoopDepth;
  }
  ```
  これをしないと、`while True:\n    def f():\n        break`のように
  「ループの中で定義された関数の中のbreak」が、外側のwhileのloopDepthが
  残っているせいでパース時には合法だと誤判定される。実行時には
  `callFunction`が`ReturnSignal`しか`catch`しないので、この`BreakSignal`は
  関数呼び出しを素通りして、呼び出し元がループの中ならそのループを
  意図せず止め、トップレベルなら`DroneScriptRunner`の`catch (Throwable)`まで
  抜けてしまう。
- `break`/`continue`をパースする箇所で`loopDepth == 0`なら
  `MicraLangException(line, "'break' outside loop")`
  (continueも同様のメッセージ)をその場で投げる。
- `return`をパースする箇所で`funcDepth == 0`なら
  `MicraLangException(line, "'return' outside function")`をその場で投げる
  (`funcDepth`は上記「ネストしたdefは禁止」で導入したものと同じフィールド)。

## 仮引数名の重複はdef時にエラーにする

`def f(a, a):`のように同名の仮引数が重複した場合、`defStmt()`内で
(パースしたパラメータ名のリストを`Set`に入れて重複を検出し)
`MicraLangException(line, "duplicate parameter name 'a'")`にする。
黙って後者が前者を上書きする(束縛時に同じキーに2回`set`されるだけ)と、
書いた本人にも気づきにくいバグになる。

## Environment.javaの変更点(詳細)

```java
public final class Environment {
    private final Environment parent; // ルート(グローバル)ならnull
    private final Map<String, Object> values = new HashMap<>();

    public Environment() { this.parent = null; }
    public Environment(Environment parent) { this.parent = parent; }

    public void set(String name, Object value) { values.put(name, value); } // 常にこのフレームに束縛(Python代入と同じ)

    public Object get(String name, int line) { ... } // parentを辿って見つからなければ既存の例外
    public Object tryGet(String name) { ... } // 見つからなければnullを返す(evalCallのユーザー関数解決用、新規)
}
```

Interpreter側の`new Environment()`呼び出し箇所(フィールド初期化子1箇所のみ、
40行目)は無変更で動く(引数無しコンストラクタがルート/グローバルを作る)。

**既知の制限(意図的、テストで挙動を固定することを推奨)**: `global`文が無いため、
関数内で`x = x + 1`のように書くと、右辺の読み取りはグローバルの`x`まで
辿って読めるが、左辺の代入は必ずローカルフレームに新しい`x`を作る
(グローバルの`x`は変わらない)。Pythonなら`UnboundLocalError`でこの矛盾に
気づけるが、本設計では黙って動く。初学者が最も踏みやすい罠なので、
`CommandsHelpDoc.java`(T3)で明示し、`InterpreterTest.java`にこの挙動を
固定するテストを1本足すことを推奨する。

## Parser.javaの変更点(詳細)

- `statement()`(28行目)に追加: `DEF`/`RETURN`/`BREAK`/`CONTINUE`/`PASS`の
  それぞれについて`check(TokenType.XXX)`で分岐する(現在は`IF`/`WHILE`/`FOR`の
  3つだけを見て残りは`simpleStmt()`に落ちる作りなので、**5つ全部**を
  追加しないと、特に`RETURN`を追加し忘れると`return`が式として解釈されようと
  して構文エラーになる)。
- 新規`private Stmt defStmt()`: `def` IDENT `(` パラメータ名をカンマ区切りで
  IDENTずつ RPAREN まで `:` `block()`。仮引数リストのパースは既存の
  `argList()`(253行目、式のリスト)とは別物(パラメータは式ではなく単なる
  識別子の列)なので専用ループを書く。
- returnは式が省略できる(return単独で改行)ことに注意 -
  `check(TokenType.NEWLINE)`ならvalue=null。
- `loopDepth`/`funcDepth`のカウントと、break/continue/return外文脈エラー、
  ネストしたdef禁止、仮引数重複エラーは上記の専用セクションを参照。

## 実行順序・依存関係

1. **T1(1コミット)**: 上記のLexer/TokenType/Stmt/Environment/Interpreter/Parser
   +新規5ファイル+LexerTest/InterpreterTest/EnvironmentTest/CommandNamesTestへの
   テスト追加(CommandNamesTestは既存テストの期待値修正、他は新規テスト追加)。
   sealed interfaceの網羅性チェックにより、これらは分割できず1つの変更として
   コンパイルが通る形にする(過去の「コレクション型」機能追加時と同じ制約)。
2. **T2(1コミット、T1完了後)**: 以下を1つの変更として扱う。
   - `callFunction`が`enterLoopForDebug()`/`exitLoopForDebug()`を呼ぶことで、
     関数呼び出し中のStep Outが正しく動く(関数を抜けて呼び出し元に戻る)ことを
     確認するテストを`DebugControllerTest.java`(または`InterpreterTest.java`)に
     追加する。
   - **`DebugController.java`自体のコード変更は不要**(depthカウンタは
     既に呼び出しフレーム一般に対応済み、これは実装済みのjavadoc
     (「generalizes to real call frames if functions are added later」)通り)が、
     **UI上の意味の変化は明示すること**: 現在`step()`は「次の文へ進む」動作
     しか無く、関数呼び出しに対する「ステップオーバー」(関数の中に入らず
     次の行まで実行する)は提供されない - `step`は常に関数の中へ入る
     (ステップイン相当)。この点をDebugController.javaのjavadoc(13-16行目
     「no user-defined functions yet」、62行目付近「brackets each while/for
     loop」、109-113行目のstepOut説明)から、関数が実装された旨に更新する。
   - `client/IdeScreen.java`70行目付近の「Step Out (of the current loop)」という
     javadocコメントも、ループだけでなく関数呼び出しにも及ぶようになった旨に
     更新する(GUIロジック自体は変更不要)。
3. **T3(先送り可、林さんに続行の号令を仰ぐ)**: `CommandsHelpDoc.java`にdef/
   return/break/continue、および「globalが無いので関数内の再代入は
   ローカルになる」旨を追記、`SampleScripts.java`に関数を使うサンプル
   1本追加+`SampleCatalog.java`へ登録。`SampleScriptsTest`が登録された
   全サンプルを自動実行するので、T3用に新規テストファイルは不要。

## 検証方法

- T1・T2は`gradlew build`成功+全テストパスを実測確認してからコミット
  (Minecraft非依存のlangパッケージのみの変更なので実機確認は不要)。
- T1の完了条件に、MAX_CALL_DEPTHの実測確認(上記Interpreterの変更点を参照)を
  含める。
- T3はビルド+既存テスト+SampleScriptsTestが新サンプルを自動実行することを確認。

## その他、今回は直さないが記録しておく既知の非整合(スコープ外)

- `defineFunction`はユーザー定義関数が組み込み名と衝突するのを拒否するが、
  既存の`AssignStmt`(例: `move = 5`)は今も組み込み名を代入で上書きできて
  しまう。この非整合は本タスク以前から存在し、今回の範囲外。
- 将来のRTOSタスク設計(`create_task(name, priority, budget, fn)`のように
  関数値を渡してタスクとして動かす案)は、`Interpreter`が`this.env`を
  書き換える現在の実装だと**再入不可・スレッド安全でない**ため、
  タスクごとに別々の`Interpreter`インスタンスが必要になる。今回のスコープ
  外だが、次にRTOSタスクを設計する時のために記録しておく。
- `MicraFunction`を`record`にすると`equals`が構造比較になり、たまたま同じ
  パラメータ・本文を持つ別々の`def`が`==`比較でtrueになりうる
  (Pythonは常に同一性比較で別オブジェクト同士はfalse)。このmodのスクリプトが
  関数同士を`==`で比較する実用的な場面は無いと考えられるため、今回は
  対応しない(将来問題になったらidentityベースの`equals`に切り替える)。

## 禁止事項

- 上記「新規ファイル」以外のファイルを新規作成しない。似た役割のファイルが
  欲しくなったら、既存の同種ファイル(ScriptStoppedException.java等)を
  探して真似る、または本文書に追記してから進める。
- 既存クラス名・パッケージ名のリネームはしない。
- CommandNames.javaにユーザー定義関数名を追加しない(スコープ外、上記参照)。
- クロージャ対応・`global`文・ステップオーバーの独自実装など、この文書が
  明示的に「今回は非対応/先送り」とした機能を、ついでに実装しない。
