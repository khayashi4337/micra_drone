# T-14/T-16: 実機確認チェックリスト

AIチャット支援機能のWave 5/6の最終確認。当初「この作業環境にはGUI操作手段が
無いので実施不能」としていたが、林さんとの一問一答で「devkit companion mod
としてJSON API経由でIdeScreenを直接駆動する」方式を採用し(実装は別リポジトリ
`micra_drone_devkit`)、2026-08-28に実機で下記を全て実測確認できた。

## 実施結果(2026-08-28、実機・devkit API経由で確認済み)

### T-14: get_block_snapshot 統合確認

- [x] Chatタブを開き、Sendでメッセージ送信→本物の`claude` CLIが起動し
  応答が返ることを確認(`AI: DEVKIT-E2E-PROOF`)
- [x] AIに範囲の中身を尋ねる質問を送信→AIが自分で`get_block_snapshot`
  ツールを呼び、実際のワールドのブロック名を答えに含めてくることを確認
  (`AI: (-12,65,151)=grass_block;` — 実際にその場に生えていた草ブロックと一致)
- [x] safeモード(danger OFF)のままでも`get_block_snapshot`だけは動作する
  ことを確認(要件通り、常時有効)
- [ ] Insertボタンでのコードブロック反映は、実際に```コードブロックを含む
  応答をまだ引き出せていないため未確認(スクリプト作成を頼む会話で改めて確認予定)

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
- [ ] 送信中に画面を閉じる/履歴破損からの復旧は未確認(残タスク)

## 実機検証中に見つかった実バグ2件(修正・コミット済み)

1. **Windows起動失敗**: `ProcessBuilder`が`claude`(拡張子なしPOSIXスクリプト)
   を直接起動できず`CreateProcess error=2`で失敗。npm版はWindows上
   `claude.cmd`(バッチラッパー)であり`claude.exe`が存在しないため。
   `cmd.exe /c`経由に修正(`ClaudeCliBridge`、コミット`ee07af8`)。
2. **devkit側の例外処理漏れ**: 上記1のエラーが`NoSuchMethodError`(Errorで
   RuntimeExceptionではない)だった場合に、devkitのレンダースレッド
   タスクラッパーがキャッチできずゲーム全体がクラッシュしていた。
   `catch (RuntimeException)`を`catch (Throwable)`に修正(devkit側)。

## 見た目について(既知の未完成点)

- Region Pointerアイテムのテクスチャは仮のvanilla `blaze_rod`を流用している
  (T-8のコミット参照)。自作テクスチャは未着手 — 必要なら
  `tools/drone_pipeline.py`と同じ流れで別途デザインする。
- IdeScreenのChatタブ下部、Save/Save&Run/List/Close Chatの4分割ボタンの
  文字が窓幅によっては詰まって見える(実機スクリーンショットで確認、
  機能に支障は無いが見た目の微調整余地あり)。

## 未確認・残タスク

- Insertボタンの実地確認(コードブロックを含む応答を引き出す会話が必要)
- 送信中の画面クローズ
- 履歴ファイル破損からの復旧
- Region Pointerアイテムの実際のブロッククリック(左クリック=始点/
  右クリック=終点)は、devkitの`/select-region`で代替確認したのみで、
  アイテム本体の3Dクリック動作(SPK-3で指摘したクリエイティブモードでの
  `setCanceled`無効の挙動含む)は未確認
