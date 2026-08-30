# SPK-3: 左クリックblockイベントのAPI確認

AIチャット支援機能のWave 0スパイク。RegionPointerItem(範囲参照アイテム)の
左クリック=始点/右クリック=終点を、サーバー往復なしでクライアント単独で
実現できるかをdecompile済みソース(`sourcesAndCompiledWithNeoForge_*_output.jar`
展開版、6317ファイル)で確認した。

## 結論: 両方ともクライアント単独のNeoForgeイベントで実現できる

- `net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock`
  と `PlayerInteractEvent.RightClickBlock` はどちらも
  `net.minecraft.client.multiplayer.MultiPlayerGameMode`(**クライアント専用
  クラス**)から`NeoForge.EVENT_BUS.post(evt)`で発火していることを確認済み
  (`CommonHooks.onLeftClickBlock`/`onRightClickBlock`経由)。サーバーへの
  パケット送信より前にクライアント側で発火するため、
  **`NeoForge.EVENT_BUS`にリスナーを登録するだけで、サーバー往復無しに
  座標(`event.getPos()`)を取得できる**。
- よって過去2回踏んだバグクラス(`Block#useItemOn`/`useWithoutItem`の
  ディスパッチ順序、Sneak修飾のブロック相互作用バイパス)を一切踏まない
  設計になる — `Item#useOn`のオーバーライドではなく、イベントバスの
  リスナーとして実装するため。

## 設計への反映(T-8: RegionPointerItem)

- `LeftClickBlock`(`Action.START`のときのみ、`CLIENT_HOLD`は毎tick発火
  するため無視)→ メインハンドが`RegionPointerItem`なら`corner1 =
  event.getPos()`、`event.setCanceled(true)`でブロック攻撃を抑制。
- `RightClickBlock` → 同様に`corner2 = event.getPos()`。
- どちらも`event.getLevel().isClientSide`を確認してから処理する
  (このイベントは`getSide()`でCLIENT/SERVER両方あり得る抽象クラスの
  サブタイプだが、発火元がクライアント専用クラスなので実質クライアント側
  のみで発火するはず。念のため実装時にガードを入れる)。

## 既知の制約(実機確認が必要)

- `LeftClickBlock.setCanceled(true)`のjavadocに **「クリエイティブモードでは
  効果が無い」** と明記されている。クリエイティブモードでは左クリックで
  ブロックが即座に破壊される別経路があるため、RegionPointerItemを持って
  クリエイティブモードでブロックを左クリックすると、選択と同時にブロックが
  壊れてしまう可能性がある。サバイバルモードでは`setCanceled`が効くはず。
  **この差はソースの読解だけでは断定できないため、T-8完了後の実機確認で
  クリエイティブ/サバイバル両方を確認する。**
