# T-14/T-16: 実機確認チェックリスト

AIチャット支援機能のWave 5/6の最終確認。当初「この作業環境にはGUI操作手段が
無いので実施不能」としていたが、林さんとの一問一答で「devkit companion mod
としてJSON API経由でIdeScreenを直接駆動する」方式を採用し(実装は別リポジトリ
`micra_drone_devkit`)、2026-08-28に実機で下記を実測確認した。

## 実施結果(2026-08-28、実機・devkit API経由で確認済み)

### T-14: get_block_snapshot 統合確認

- [x] Chatタブを開き、Sendでメッセージ送信→本物の`claude` CLIが起動し
  応答が返ることを確認(`AI: DEVKIT-E2E-PROOF`)
- [x] AIに範囲の中身を尋ねる質問を送信→AIが自分で`get_block_snapshot`
  ツールを呼び、実際のワールドのブロック名を答えに含めてくることを確認
  (`AI: (-12,65,151)=grass_block;` — 実際にその場に生えていた草ブロックと一致)
- [x] safeモード(danger OFF)のままでも`get_block_snapshot`だけは動作する
  ことを確認(要件通り、常時有効)
- [x] **Insertボタン**: 「pythonのコードブロック1つだけで`harvest()`と返して」
  という依頼→応答の`codeBlockCount`=1→`/insert-block 0`→エディタ本文が
  `harvest()`に置き換わることを確認(第2ラウンド)

### T-16: 異常系確認

- [x] **未ロード範囲**: 遠方座標(100000,64,100000)を問い合わせ→
  `AI: unavailable: part of that range is not currently loaded on the client`
  が正しく返り、クラッシュしないことを確認
- [x] **resume**: ゲーム再起動を挟んでChatを開き直しても、前回までの会話が
  正しく復元されることを確認(履歴がコントローラごとにローカル保存される
  設計通り)
- [x] **compact**: Compactを実行→会話が的確な要約1件に置き換わることを確認
  (要約内容も的確で、それまでのやり取りを正しく反映していた)
- [x] **danger トグル**: ON/OFFの切り替え自体がクラッシュ無く動作することを確認
- [x] **送信中に画面を閉じる**(第2ラウンド): 送信直後に`/close-screen`→
  応答が届くまでの間(5回サンプリング)と届いた後も`cameraOnPlayer`=true
  (視点がプレイヤーに戻ったまま)、画面は閉じたまま、応答は履歴ファイルに
  保存され、IDEを開き直すとChatログに表示されることを確認
- [x] **履歴ファイル破損からの復旧**(第2ラウンド): 履歴ファイルを
  バイナリごみで上書き→Chatを開いてもクラッシュせず空の会話から開始→
  そのまま送信して応答が返ることを確認
- [ ] CLI応答なし(ハング)は、120秒タイムアウトの経路をコードレベルで
  直した(下記バグ3)が、本物のclaude CLIを意図的にハングさせる手段が無く
  実機では未確認

## 実機検証中に見つかった実バグ(修正・コミット済み)

### 第1ラウンド

1. **Windows起動失敗**: `ProcessBuilder`が`claude`(拡張子なしPOSIXスクリプト)
   を直接起動できず`CreateProcess error=2`で失敗。npm版はWindows上
   `claude.cmd`(バッチラッパー)であり`claude.exe`が存在しないため。
   `cmd.exe /c`経由に修正(`ClaudeCliBridge`、コミット`ee07af8`)。
2. **devkit側の例外処理漏れ**: 上記1のエラーが`NoSuchMethodError`(Errorで
   RuntimeExceptionではない)だった場合に、devkitのレンダースレッド
   タスクラッパーがキャッチできずゲーム全体がクラッシュしていた。
   `catch (RuntimeException)`を`catch (Throwable)`に修正(devkit側)。

### 第2ラウンド(コードレビューで発見、修正後の挙動を実機で確認)

3. **CLIタイムアウトが効いていなかった**: `runProcess`がstdoutを最後まで
   読み切ってから`waitFor(120s)`していたため、CLIがハングすると読み取りが
   永遠にブロックし、Sendボタンが二度と有効に戻らなかった。stdout/stderrを
   別スレッドで読みながら`waitFor`で先に待つ形に修正。stderrが詰まって
   子プロセスがデッドロックする古典的な問題も同時に解消。
4. **送信中に画面を閉じるとカメラが奪われる**: 応答到着時の
   `rebuildWidgets()`→`init()`→`IdeCameraController.update()`が、閉じた
   画面でも走り、プレイヤーの視点をダミーカメラに移したまま戻す者がいなく
   なる経路があった。`removed()`で`closed`フラグを立て、閉じた後は
   再構築しない(履歴保存は行う)よう修正。devkitの`/state`に
   `cameraOnPlayer`を追加して実機で確認。
5. **会話履歴がワールドをまたいで共有されていた**: 保存先が
   `micradrone/chat/`直下で、`ControllerKey`は次元+座標のみのため、別の
   セーブデータ(やサーバー)の同座標のコントローラと履歴が混ざる。
   `micradrone/chat/<セーブフォルダ名 or サーバーアドレス>/`に分離
   (バニラの`Minecraft#archiveProfilingReport`と同じ「今いるワールド」の
   取り方、`LevelResource.ROOT`が`.`なのでnormalize必須)。
6. **ツールサーバー起動失敗でゲームごと落ちる**: ループバックのbindや
   設定ファイル書き込みに失敗すると`IllegalStateException`がボタン
   ハンドラから素通りしてクラッシュレポート行きだった。Chatログに
   `(error)`行として出す形に変更。
7. **Region Pointerが統合サーバー側スレッドからも書いていた**:
   `PlayerInteractEvent`は両サイド+右クリックは両手で発火するため、
   クライアントUI状態`RegionSelectionHolder.PENDING`をサーバースレッド
   からも書いていた。キャンセルは両サイド、書き込みはクライアント側の
   メインハンドのみに限定。

その他の仕上げ: 応答到着時の再構築で入力中の下書きが消えないようにした、
`Insert #N`ラベルの翻訳キー化、レイアウト定数化、ロール文字列の定数化、
MCPエラー応答をMiniJson経由で組み立てて常に有効なJSONにした、
`ClientMainThreadDispatch`もThrowableを捕捉するようにした(devkit側と同じ教訓)。

## 見た目について(既知の未完成点)

- Region Pointerアイテムのテクスチャは仮のvanilla `blaze_rod`を流用している
  (T-8のコミット参照)。自作テクスチャは未着手 — 必要なら
  `tools/drone_pipeline.py`と同じ流れで別途デザインする。
- IdeScreenのChatタブ下部、Save/Save&Run/List/Close Chatの4分割ボタンの
  文字が窓幅によっては詰まって見える(実機スクリーンショットで確認、
  機能に支障は無いが見た目の微調整余地あり)。

## 未確認・残タスク

- CLIハング時の120秒タイムアウト(コードは修正済み、実機再現手段なし)
- Region Pointerアイテムの実際のブロッククリック(左クリック=始点/
  右クリック=終点)は、devkitの`/select-region`で代替確認したのみで、
  アイテム本体の3Dクリック動作(SPK-3で指摘したクリエイティブモードでの
  `setCanceled`無効の挙動含む)は未確認(3D視点は合成入力で動かせないため
  人手の確認が必要)
