# 自然言語→工場建設 Phase 1: Create/Aeronautics依存追加 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** micra_droneのbuild.gradleにCreate本体とCreate: Aeronauticsをコンパイル/実行時依存として追加し、`./gradlew build`と`./gradlew runClient`が実際に(クラッシュせず)通ることを実測で確認する。この段階では新しいゲームロジックは一切書かない。

**Architecture:** Create公式wiki(https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1)が示す「slim + transitive=false」パターンに従い、Createを`implementation`（このmodにとってCreateはハード依存であり、既存のJEIのような`compileOnly`+`localRuntime`のソフト依存パターンとは意味が異なるため区別する）で追加する。Aeronauticsは別コミットとして追加し、Create単体での成功を先に確認してから追加することで、問題が起きた場合にどちらの依存が原因か切り分けられるようにする。

**Tech Stack:** Gradle, NeoForge ModDevGradle 2.0.141, Create 6.0.10, Create: Aeronautics 1.3.0(bundled), Flywheel, Ponder, Registrate

**Spec:** このplanは、本セッションでの引き継ぎ会話(自然言語→工場建設機能、Codexセカンドオピニオン反映済みMVP範囲)の実装順序ステップ1に対応する。上位の設計はこのファイル自体には別途保存していないが、対応表・MVP範囲・実装順序は会話履歴に記録済み。

## Global Constraints

- Minecraft 1.21.1 / NeoForge 21.1.238（gradle.properties既存値、変更しない）
- Java 21（既存toolchain設定、変更しない）
- Createは6.0.10系（プレイヤーの実機にインストール済みのバージョンと一致させる。実機jarのMANIFEST.MFで`Implementation-Version: 6.0.10`を確認済み、内部ビルド番号は実機jarからは特定不可のため、maven-metadata.xmlで確認できた同系統の最新ビルド`6.0.10-281`を使う）
- Aeronauticsは1.3.0(bundled)（実機jar`create-aeronautics-bundled-1.21.1-1.3.0.jar`と一致させる。Modrinthプロジェクト`oWaK0Q19`のバージョンID`w7zlLnea`が同一ファイルであることをModrinth側の表示ファイル名で確認済み）
- 既存のbuild.gradleの`repositories {}`ブロック、`dependencies {}`ブロックの既存コメント(JEIの例)は消さない（過剰除去禁止）

---

### Task 1: Create本体の依存追加とビルド確認

**Files:**
- Modify: `gradle.properties`（バージョン変数を追記）
- Modify: `build.gradle`（`repositories`と`dependencies`ブロックに追記）

**Interfaces:**
- Consumes: なし（このタスクはビルド設定のみ、Javaコードの変更なし）
- Produces: Gradleビルドスクリプトが`com.simibubi.create`、`net.createmod.ponder`、`dev.engine-room.flywheel`、`com.tterrag.registrate`のクラスをコンパイル時に解決できる状態。後続タスク(Recipe Resolver、Factory Analyzer等)はこれらのAPIをimportして使う。

- [x] **Step 1: gradle.propertiesにバージョン変数を追記**

`gradle.properties`の末尾（`mod_group_id=...`の後）に追記:

```properties

## Create integration (自然言語→工場建設機能, Phase 1)
create_version=6.0.10-281
ponder_version=1.0.82
flywheel_version=1.0.6
registrate_version=MC1.21-1.3.0+67
```

- [x] **Step 2: build.gradleのrepositoriesブロックにCreateのMavenリポジトリを追加**

`build.gradle`の既存の空`repositories { }`ブロックを以下に置き換える:

```gradle
repositories {
    // Add here additional repositories if required by some of the dependencies below.
    maven { url = "https://maven.createmod.net" }
    maven { url = "https://maven.ithundxr.dev/snapshots" }
}
```

- [x] **Step 3: build.gradleのdependenciesブロックにCreate関連の依存を追加**

`dependencies { }`ブロック内、既存のJEIコメント例の直後・`testImplementation`群の直前に追記:

```gradle
    // Create integration (自然言語→工場建設機能, Phase 1). Createはこの機能にとってハード依存
    // (JEIのようなソフト依存とは違いcompileOnly+localRuntimeにしない)。"slim"はCreate公式が
    // 配布しているAPI専用の軽量jarで、transitive=falseなので依存先(Ponder/Flywheel/Registrate)は
    // 個別に明示する必要がある(公式wiki: https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1)。
    implementation("com.simibubi.create:create-${minecraft_version}:${create_version}:slim") { transitive = false }
    implementation("net.createmod.ponder:ponder-neoforge:${ponder_version}+mc${minecraft_version}")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${minecraft_version}:${flywheel_version}")
    runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-${minecraft_version}:${flywheel_version}")
    implementation("com.tterrag.registrate:Registrate:${registrate_version}")
```

- [x] **Step 4: コンパイルを確認**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`。失敗する場合は依存解決エラー(404等)かバージョン不整合のログを確認し、該当バージョン文字列をmaven-metadata.xmlで再確認する。

- [x] **Step 5: runClientでの起動を確認(実機確認)**

Run: `./gradlew runClient --console=plain`（GUIが起動するので、タイトル画面まで到達しCreateがMod一覧に出ることを目視確認してからクライアントを閉じる。起動したクライアントは確認後に必ず終了する）
Expected: クラッシュせずタイトル画面に到達し、Mod一覧(またはログ)に`create`が読み込まれていること。ログに`Duplicate mod`等の致命的エラーが出ていないこと。

- [x] **Step 6: コミット**

```bash
git add gradle.properties build.gradle
git commit -m "$(cat <<'EOF'
build: Create本体をハード依存として追加(自然言語→工場建設Phase1)

公式wiki(neoforge-1.21.1)のslim+transitive=falseパターンに従い、
Ponder/Flywheel/Registrateを個別に明示。バージョンは実機インストール
済みのCreate 6.0.10系に合わせ、maven-metadata.xmlで確認できた
最新ビルド6.0.10-281を使用。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Create: Aeronauticsの依存追加とビルド確認

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle`

**Interfaces:**
- Consumes: Task 1で解決済みのCreate本体クラスパス
- Produces: `com.create_aeronautics`(または実際のパッケージ名、依存解決後にjarを展開して確認)のクラスをコンパイル時に解決できる状態

- [x] **Step 1: gradle.propertiesにAeronauticsのバージョン変数を追記**

Task 1で追加したブロックの末尾に追記:

```properties
aeronautics_version=w7zlLnea
```

- [x] **Step 2: build.gradleのrepositoriesブロックにModrinth Mavenを追加**

```gradle
    maven { url = "https://api.modrinth.com/maven" }
```

- [x] **Step 3: build.gradleのdependenciesブロックにAeronauticsを追加**

```gradle
    // Create: Aeronautics (自然言語→工場建設機能, 発着場/飛行ルート用。Phase1では依存解決の
    // みを確認し、実際のAPI使用は後続フェーズで行う)。実機インストール済みのbundled版
    // 1.3.0+mc1.21.1と同一ファイル(Modrinthプロジェクトowak0Q19)。
    implementation("maven.modrinth:oWaK0Q19:${aeronautics_version}")
```

- [x] **Step 4: コンパイルを確認**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`。Aeronauticsが「bundled」(Create本体を内包)であることに起因するクラス重複エラーが出ないか特に注意する。

- [x] **Step 5: runClientでの起動を確認(実機確認)**

Run: `./gradlew runClient --console=plain`
Expected: クラッシュせずタイトル画面に到達。**特に「Duplicate mod id」「Duplicate mod: create」のようなエラーが出ないことを確認する**(bundled版が別途Create本体を読み込もうとして衝突する可能性がCodexレビューで指摘されている既知リスク)。衝突する場合はAeronautics側を`transitive = false`にしてCreateの重複部分を除外することを検討し、その対処は別途相談する。

**実測結果(追記、Opus 5レビュー指摘#9への対応)**: 1回目の`runClient`は上記の懸念(Create重複)ではなく、**別の失敗**で落ちた。実際のクラッシュ画面:

```
-- Mod loading issue for: aeronautics --
Failure message: Mod aeronautics requires sable 2.0.0 or above, and below 3.0.0
    Currently, sable is not installed
-- Mod loading issue for: simulated --
Failure message: Mod simulated requires sable 2.0.0 or above, and below 3.0.0
-- Mod loading issue for: offroad --
Failure message: Mod offroad requires sable 2.0.0 or above, and below 3.0.0
```

Aeronauticsのbundled版はjarJarで`aeronautics`/`simulated`/`offroad`の3modを内包しており、全てが`sable`(公式ドキュメント未記載の共有ライブラリmod)を要求していた。実機の`mods`フォルダにあった`sable-neoforge-1.21.1-2.0.3.jar`と同一ファイルをModrinth API(`https://api.modrinth.com/v2/project/sable/version`)で特定し(project T9PomCSv, version 1L6XJqnY)、追加した上で2回目の`runClient`で成功(13mod構成、クラッシュなし)。この追加分はTask 2側のコミット(`4756410`)に含まれる。

**追記2(Opus 5レビュー2回目、本コミットで対応): Phase1所有ファイルへの後続変更の記録**

上記の一連の作業の後、以下の追加修正をPhase1が所有するファイル(`build.gradle`, `src/main/templates/META-INF/neoforge.mods.toml`, `gradle.properties`)に対して行った。1回目のOpus 5レビュー対応時と2回目のOpus 5レビュー対応時、いずれもこの計画書への追記が漏れていたため、まとめてここに記録する(指摘#9・#I-4と同種の記録漏れの是正)。

1. `build.gradle`の3つの新規Mavenリポジトリ(`maven.createmod.net`, `maven.ithundxr.dev/snapshots`, `api.modrinth.com/maven`)に`content { includeGroup(...) }`/`includeGroupByRegex(...)`を追加(JUnit等無関係な依存解決まで毎回問い合わせる非効率を解消)
2. `gradle.properties`の`aeronautics_version`/`sable_version`(Modrinthの不透明なバージョンID)に、何のファイルに対応するかの説明コメントを追加
3. `neoforge.mods.toml`に`create`/`aeronautics`/`sable`の`required`依存宣言を追加。**この作業中、テンプレートに書いた日本語コメントが`generateModMetadata`タスクの文字化け(Windows上のプラットフォームデフォルトエンコーディング問題)でTOML構文を破壊し、実機`runClient`で`ParsingException: Failed to parse data from Reader`のクラッシュが実際に発生することを発見した。** 根本原因は`build.gradle`の`generateModMetadata`タスクに`filteringCharset = 'UTF-8'`が指定されていなかったこと。1回目の対応ではコメントを英語に置き換える対症療法のみを行ったが、2回目のレビューで「非ASCII文字が入るたびに再発する」と指摘され、`filteringCharset = 'UTF-8'`を追加して根本原因を修正した(日本語コメントを含む一時テスト文字列で文字化けが解消することを実機で再確認済み)
4. `neoforge.mods.toml`の`create`/`aeronautics`/`sable`の`ordering`を、当初`"AFTER"`にしていたが、これは参照元として明記していた公式wiki(`ordering="NONE"`)と矛盾しており、かつ既存の`neoforge`/`minecraft`依存宣言(`ordering="NONE"`)とも不整合だった。2回目のレビューで、この`ordering="AFTER"`が**Registrateの"Found unused register callbacks"という非決定的なクラッシュ(順序依存の失敗)の最有力候補**と指摘され、`ordering="NONE"`に戻した。当初この非決定的クラッシュを「二重起動による汚染」と誤って結論づけていたが、これは実測に基づかない誤った推測だった(ログのタイムスタンプ・JVMメモリ空間の独立性から二重起動説は機序が説明できないとレビューで指摘された)

**追記3: `ordering="NONE"`に戻した後の複数回実機確認**

レビューで「1回通っただけでは失敗率1/2の非決定的バグの検証にならない」と指摘されたため、単一プロセスでの`runClient`を**5回連続**実行した。各回、前回のプロセスが完全に終了していること(`Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*fml.modFolders*' }`で0件)を確認してから次を起動した。

結果: **5/5回とも成功**(`Complete loading of 13 mods`→`Sound engine started`まで到達、`FATAL`/`Crash Report`/`unused register`のログ一致ゼロ)。`ordering="NONE"`への差し戻しがこの非決定的クラッシュを解消したことを実測で確認した。

- [x] **Step 6: コミット**

```bash
git add gradle.properties build.gradle
git commit -m "$(cat <<'EOF'
build: Create: Aeronauticsを依存として追加(自然言語→工場建設Phase1)

Modrinth mavenプロキシ経由、実機インストール済みのbundled版
1.3.0+mc1.21.1と同一ファイル(project oWaK0Q19, version w7zlLnea)。
本フェーズでは依存解決とruntClientでのロード確認のみ、実際のAPI
使用は後続フェーズで行う。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

- Spec coverage: 実装順序ステップ1(Create/Aeronautics依存追加＋runClientロード確認)を過不足なくカバー。
- Placeholder scan: 全ステップに具体的なコマンド・コード・バージョン文字列を明記済み、TBD等なし。
- Type consistency: 該当なし(Gradle設定変更のみ、新規クラス無し)。
