# 自然言語→工場建設 Phase 2: サーバー権威のブロック読み取り経路 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 既存の`get_block_snapshot`(クライアントの`ClientLevel`しか読めない)とは別に、サーバーの本物のワールドデータ(`ServerLevel`)を読む経路を新設する。マルチプレイでも「本当は何が置かれているか」を正しく検証できるようにするための土台。今回はクラス追加のみで、実際に呼び出す場所(Factory Analyzer / L7ループ)は後続フェーズで作る。

**Architecture:** 既存の`io.github.khayashi4337.micradrone.chat.BlockSnapshotReader`インターフェース(`Optional<String> read(x1,y1,z1,x2,y2,z2)`、Minecraft型に依存しない)をそのまま再利用し、`drone`パッケージに`ServerBlockSnapshotReader`というサーバー版実装を追加する。`client.LiveBlockSnapshotReader`と対になる構成(クライアント版/サーバー版で同じ契約、別実装)。新しいインターフェースは作らない(重複回避)。

**Tech Stack:** 既存パターンの踏襲のみ、新規ライブラリ不要。

**Spec:** 実装順序ステップ2に対応。上位設計は会話履歴を参照。

## Global Constraints

- 既存の`BlockSnapshotReader`インターフェースのシグネチャ・契約(「範囲内が読み込まれていなければOptional.empty()」)を変更しない
- 出力テキスト形式(`(x,y,z)=<block_name>; `)とブロック名簡略化(`SenseNames.simplify`)は`LiveBlockSnapshotReader`と揃える(表示形式の一貫性)
- 1クエリの上限は既存のクライアント版と同じ1000ブロック(`LiveBlockSnapshotReader.MAX_BLOCKS_PER_QUERY`と同じ値、モジュール規模を考えれば十分すぎる余裕がある)
- このクラスの呼び出し元(Factory Analyzer等)はまだ存在しない。`lang/VirtualScheduler.java`の前例(javadocで「本番未接続」と明記)に倣い、その旨をjavadocに明記する
- 呼び出しはメインスレッド(サーバーtick)からのみ、という契約を`FarmBlockAccess`と同様にjavadocで明記する(ディスパッチ機構は追加しない、既存の`MainThreadGateway`経由で呼び出す側が保証する)

---

### Task 1: ServerBlockSnapshotReaderの追加

**Files:**
- Create: `src/main/java/io/github/khayashi4337/micradrone/drone/ServerBlockSnapshotReader.java`

**Interfaces:**
- Consumes: `io.github.khayashi4337.micradrone.chat.BlockSnapshotReader`(既存インターフェース、変更なし)
- Produces: `ServerBlockSnapshotReader implements BlockSnapshotReader` — 後続フェーズ(Factory Analyzer)がこれをコンストラクタで`ServerLevel`を渡して使う

- [x] **Step 1: クラスを作成**

`src/main/java/io/github/khayashi4337/micradrone/drone/ServerBlockSnapshotReader.java`:

```java
package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server-authoritative counterpart to {@code client.LiveBlockSnapshotReader}: reads the real
 * {@link ServerLevel} instead of the client's own (possibly stale, and in principle spoofable)
 * ClientLevel. Needed for verifying what construction actually produced on the server - the source
 * of truth in multiplayer - rather than what one player's screen happens to show.
 *
 * <p>Not yet wired into any production entry point: this class exists ahead of its first caller
 * (the still-to-be-built Factory Analyzer / construction-verification loop), matching the precedent
 * set by {@link io.github.khayashi4337.micradrone.lang.VirtualScheduler}. Callers must invoke
 * {@link #read} only from the main server thread (the same contract {@link FarmBlockAccess}
 * documents) - this class does not dispatch or synchronize on its own.
 */
public final class ServerBlockSnapshotReader implements BlockSnapshotReader {
    /** Matches {@code client.LiveBlockSnapshotReader.MAX_BLOCKS_PER_QUERY} for consistent behavior between the two readers. */
    static final int MAX_BLOCKS_PER_QUERY = 1000;

    private final ServerLevel level;

    public ServerBlockSnapshotReader(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<String> read(int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_BLOCKS_PER_QUERY) {
            return Optional.of("range too large (" + volume + " blocks, max " + MAX_BLOCKS_PER_QUERY
                    + ") - ask about a smaller range");
        }

        StringBuilder sb = new StringBuilder();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        return Optional.empty();
                    }
                    BlockState state = level.getBlockState(pos);
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    sb.append('(').append(x).append(',').append(y).append(',').append(z).append(")=")
                            .append(SenseNames.simplify(id.getNamespace(), id.getPath())).append("; ");
                }
            }
        }
        return Optional.of(sb.toString());
    }
}
```

- [x] **Step 2: コンパイル確認**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: 既存テストが壊れていないことを確認**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`(既存テストのみ、このクラス自体は呼び出し元が無いためテスト対象は追加しない — `LiveBlockSnapshotReader`同様、Minecraft型に直接依存するLive実装はこのプロジェクトの慣例として単体テスト対象外で、実際の検証は最初の呼び出し元ができた時点で行う)

- [x] **Step 4: コミット**

```bash
git add src/main/java/io/github/khayashi4337/micradrone/drone/ServerBlockSnapshotReader.java
git commit -m "$(cat <<'EOF'
feat: サーバー権威のブロック読み取りServerBlockSnapshotReaderを追加(自然言語→工場建設Phase2)

既存のget_block_snapshot(client.LiveBlockSnapshotReader)はクライアントの
ClientLevelしか読めず、マルチプレイでの構築結果検証には使えない
(Codexレビュー指摘)。既存のchat.BlockSnapshotReaderインターフェースを
再利用し、ServerLevelを読むサーバー版を追加。VirtualSchedulerの前例に
倣い、呼び出し元(Factory Analyzer)は後続フェーズで追加する前提で
javadocに明記。

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

- Spec coverage: 実装順序ステップ2(サーバー権威のワールド状態読み取り経路)を過不足なくカバー。
- Placeholder scan: TBD等なし、実コード記載済み。
- Type consistency: `BlockSnapshotReader.read`のシグネチャを`LiveBlockSnapshotReader`と完全一致させた。

## 訂正(Opus 5レビュー指摘、コミット4083f43で対応)

上記Task 1 Step 1で書いたjavadoc・Global Constraints(18行目, 56行目)は「`lang/VirtualScheduler.java`の前例に倣った」と主張していたが、**そのクラスはこのブランチ(mainから分岐)には存在しない**。`feature/sync-puzzle-foundations`ブランチ(別の未マージ作業)を調査した際の記憶を、ブランチを切り替えた後に再確認せずそのまま使った誤り。虚偽の前例主張だった。

また、同レビューで以下も修正した(いずれもコミット4083f43):
- `LiveBlockSnapshotReader`との丸ごとの重複ロジックを`BlockRangeDescription`に共通化
- メインスレッド呼び出し契約をjavadocに書くだけでなく`MainThreadGateway`経由のディスパッチで実際に保証するよう変更(リスコフ置換違反の是正)
- `ServerLevel`を永続保持せず`Supplier<ServerLevel>`で毎回解決するよう変更

このplanドキュメント自体(上記コードブロック)は「Step 1実行時点で実際に書いたコード」の記録として残し、書き換えない。
