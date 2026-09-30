# SPK-S9: 保護 mod の設置イベントとの互換(`FakePlayer`)

P4 の設置(`PlacementGuard`)と建築権限(`PlacementRules`)の根拠。**ソースの調査は済み(2026-09-30)。実機の測定は P4 Task 26・28・31 の台本で自動に行う**(今は「未測定」)。出典は同じ sources.jar。コードは足していない。

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
