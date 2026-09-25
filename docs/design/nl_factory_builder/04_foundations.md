# 04 横断基盤

ノードやループの下にある、すべてが乗る土台。番号は`F-n`。既存コードの実態(実際に読んだもの)を出発点にする。

---

## F-1 サーバー権威と実行境界

- **世界を書き換えるのは、サーバーのメインスレッドだけ**。経路は1本: `承認された施工リスト → ConstructionJob → サーバーtickで1個ずつ設置`(D-1)。
- スレッド分担:
  | スレッド | してよいこと |
  |---|---|
  | クライアントの描画スレッド | 画面・ホログラム・撮影。世界の状態を変えない |
  | クライアントの作業スレッド(`MicraDrone-ClaudeCli`、Codex用、解析用) | CLI呼び出し、純Javaの解析、ファイル入出力。**Minecraftのオブジェクトに触れない** |
  | MCPサーバーのスレッド(`MicraDrone-McpToolServer`) | クライアントの`ClientMainThreadDispatch`経由でのみ`ClientLevel`を読む。サーバーの情報は問い合わせ経路(F-8)で得る |
  | サーバーのメインスレッド | 検証(権威)、承認、ジョブ実行、スナップショット、ドローン演出、試運転 |
- 既存の`drone/MainThreadGateway.java`(`isOnMainThread()`を追加済み)と`drone/ServerBlockSnapshotReader.java`(Phase 2で追加済み)を使う。スクリプト実行用スレッド(`DroneScriptRunner`)は建設の経路に使わない。

## F-2 施工ジョブの永続化

- `ConstructionJobStore`(NeoForgeの`SavedData`、ディメンションごと)に、ジョブの状態・カーソル・修復ラウンド・材料の消費記録・取り消し用の記録(`UndoEntry`)を保存。**施工リスト本体**は、パレット圧縮した別ファイル(`<world>/data/micradrone/manifests/<hash>.bin`)に保存し、ジョブは`manifestHash`で参照する(SavedDataを肥大させない)。
- サーバー再起動・ワールド再読込で、`RUNNING`だったジョブは`PAUSED`で復元し、所有者がオンラインでチャンクが読み込まれれば、自動で再開する。
- **`PacedActionQueue`は使わない**(D-11)。既存の実装はメモリ上の`ConcurrentLinkedQueue`だけで、停止すると保留中の処理が消える/後で実行されるため、永続すべきジョブに向かない。
- 1tickの上限: 既定で**ドローン1機あたり4tickに1個**(既存の`LiveDroneApi.ACTION_DELAY_TICKS=4`と同じ間隔、見せ場のため)。構造(`SITE_PREP`〜`ENVELOPE`)は、設定で**毎tick最大N個**(既定16)の高速施工にできる。ドローンの数は`clamp(ceil(総数/300), 1, 6)`。所要時間の見積もりは、承認画面に出す。

## F-3 承認と改ざん防止(D-3)

```
[クライアント]                                   [サーバー]
 PlanPatch/SemanticPlan を確定
 ── SubmitPlanPayload(uploadId, seq, total, bytes) × n ──▶  再組み立て・サイズ検査・ハッシュ検査
                                                  PlanCompiler で施工リストを**サーバー自身で**作る
                                                  アナライザで権威検証 → PendingApproval(hash, owner, expiresTick)
 ◀── PlanPreviewPayload(hash, issues, bom, eta) ──
 ホログラム表示(クライアント自身のコンパイル結果のハッシュと比較)
 ── ApprovePlanPayload(hash) ──▶                       PendingApprovalと一致・所有者・期限・権限を確認 → ConstructionJob作成
 ◀── JobStatusPayload(jobId, state, progress) ──
```

- **サーバーが自分で作り直した施工リストのハッシュだけが承認できる**。クライアントが送った物をそのまま実行しない。クライアントとサーバーのハッシュが一致しなければ、承認ボタンを無効にして「バージョンの不一致」を表示(`E-REGISTRY-VERSION`)。
- **ペイロードの大きさ**: サーバー宛ての通常のカスタムペイロードは、バニラの実装で約32KBが上限と理解している(**未確認、S-6で実測**)。そのため、計画は**30KB以下のチャンク**に分けて送り、`uploadId`でまとめ、全体のハッシュで検証する。全体の上限は2MB、プレイヤーごとに同時1件、レート制限あり。既存の`SaveScriptPayload`の`MAX_SCRIPT_CHARS=10000`は、建設の経路には使わない。
- 承認の有効期限(既定10分)。期限切れ・所有者の退出・登録簿の版変更で無効になる。

## F-4 権限・所有者

- 実測で確認した現状: 既存の`DroneControllerBlockEntity.ownerUuid`は、「最後にRunを押した人」に更新されるだけで、**権限の判定には使われていない**(`saveScript`・`startScript`は誰でも通る)。建設はこれに依存しない。
- 建設の権限:
  - ジョブの所有者=承認した人。**取消・承認・再開は所有者かOP**のみ。
  - **区画(`SiteClaim`)**: 承認時にワールドボックスを予約する。他人のジョブや他の区画と重なる施工は拒否。ジョブの終了・取消で解放。
  - **建築権限の確認**(`PlacementPolicy.canPlace(owner, pos, state)`): (a)世界の境界内、(b)スポーン保護の範囲外(またはOP)、(c)NeoForgeの設置イベント(`BlockEvent.EntityPlaceEvent`)を、所有者を主体として発行し、**他modの保護がキャンセルすれば拒否**(保護modとの互換)、(d)設定の許可レベル(既定: 許可された全プレイヤー。大規模はOP)。
- 既存の農場機能の`saveScript`/`Run`に権限の穴がある件は、建設とは別の課題として**GitHub Issueに起票する**(P-15: 既存機能の挙動は、この機能の中では変えない)。

## F-5 安全枠(`SafetyEnvelope`)— 1個でも置く前に検査

| 項目 | 既定 | 超えたとき |
|---|---|---|
| 1ジョブの最大設置数 | 20,000 | 承認できない(OPは設定で緩和) |
| 施工範囲の最大の大きさ | 128×96×128 | 同上 |
| 高さの範囲 | ワールドの建築可能範囲内 | 該当部分を拒否 |
| 置換できるブロック | 空気、水・溶岩(要承認)、草・花・雪、木の葉(要承認) | 置換できないブロックは`E-SITE-BLOCKED`。**破壊を伴う置換の一覧を承認画面に表示**し、利用者が明示的に確認して初めて承認できる |
| 置換禁止 | 岩盤、バリア、コマンドブロック、ブロックエンティティを持つ(中身が入りうる)ブロック(明示承認がある場合を除く) | 拒否 |
| 同時ジョブ | 所有者ごとに1 | 待機列 |
| 読み込まれていないチャンク | 置かない | `PAUSED(CHUNK_UNLOADED)` |

## F-6 独自言語との往復(決定論プロファイル)

- 人が読んで直せる形(引き継ぎ文の要件): 計画は独自言語のスクリプトとして見える。`PlanScriptWriter`が`SemanticPlan`から出力し、`PlanRecorder`(新設の`PlanApi`の実装)がスクリプトを実行して`PlanPatch`を作る。**往復してもハッシュが変わらない**ことをテストで保証する(D-2)。
- 命令(案、P3の計画書で確定): `frame(facing)`、`style(role, material)`、`structure(id, width, depth, floors)`、`floor_slab`、`wall(id, structure, side, level, material)`、`pillar`、`roof(id, structure, kind, color)`、`door(id, wall, u, kind)`、`window(id, wall, u, v, kind)`、`stairs`、`catwalk`、`module(id, template, structure, slot, facing)`、`place(id, part_type, u, v, w, facing)`、`connect(from, to, kind)`、`power(id, part_type, …)`、`dock(id, …)`、`decorate(wall, element, u, v)`。
- 追加の作法(既存の慣習どおり): `lang/CommandNames.ALL`に登録、`lang/Interpreter.evalCall`にcaseを追加(Lexer/Parser/ASTは無改修)、`drone/CommandsHelpDoc`に説明、`lang/SyntaxHighlighter`は`CommandNames`から自動。**ただし既存の`DroneApi`には足さず、別インターフェース`PlanApi`にする**(P-15)。`Interpreter`は`PlanApi`を任意で受け取り、農場の命令と建設の命令の混在は、実行前の静的検査で拒否する。
- **決定論プロファイル**: 建設スクリプトでは、乱数・時刻・知覚(`get_time`、`get_ground`等)を禁止し、使えば静的検査で拒否する。ループ・変数・関数は使える(反復する壁や柱を書くため)。実行回数の上限(既定100,000ステップ)と時間の上限を設ける。
- **スクリプトの長さ**: 既存の`MAX_SCRIPT_CHARS=10000`は、1つのスクリプトに適用される。大きな計画は、`PlanScriptWriter`が**建物・モジュールごとの複数のスクリプト**に分割して出力する(各10,000字以内)。`PlanRecorder`は、複数のスクリプトを順番に取り込める。
- スクリプトを人が直して保存すると、それは新しい`PlanPatch`(新しい版)になる。承認は、**コンパイル後のハッシュ**に対して行う(D-3。スクリプトの文字列ではない)。

## F-7 材料(`MaterialPolicy`、D-4)

- **常に**、コンパイラが材料表(`bom`)を出す(承認画面に表示)。方針は**ゲームモードで決める**: 承認時に、所有者が**クリエイティブ**なら`CREATIVE_FREE`(消費しない)、**サバイバル**なら`SURVIVAL_CONSUME`。設定で強制も可能。
- `SURVIVAL_CONSUME`:
  - 材料の出どころ: 所有者のインベントリ、および区画内にある**補給チェスト**(所有者が指定したチェスト。指定方法はP4の計画書で決める。既存の`ScriptChestLibrary`のチェスト探索の作法を参考にする)。
  - 消費は**設置ごと**に、`placement.index`をキーにした冪等な記録で行う(再起動しても二重に消費しない)。材料が無ければ`PAUSED(MATERIALS_MISSING)`で、不足の一覧を所有者に通知。補給されれば自動で再開。
  - 取消・ロールバックで、消費済みの材料を返却する(返却先: 所有者のインベントリ、入らなければ足元にドロップ)。
  - ブロックとアイテムの対応(`BlockToItem`表): 通常は1対1。特別な物(メカニカルベルトは`create:belt_connector`アイテム、ドアや半ブロックなど)は明示表で持つ。表に無いブロックは`E-MATERIAL-UNKNOWN`で承認前に検出する。
  - 置換で壊すブロックは、アイテムをドロップせずに消す(材料の増殖を防ぐ)。
- `CREATIVE_FREE`でも、材料表は出す(「買うとこれだけ」という情報として)。

## F-8 サーバー問い合わせ経路

- クライアント(MCPツールや解析)がサーバーの情報(地形、レシピ、権威の検証結果)を得る往復: `QueryRequestPayload(requestId, kind, argsJson)`(クライアント→サーバー、30KB以内)、`QueryResponsePayload(requestId, json)`(サーバー→クライアント、大きければ分割)。サーバーは、メインスレッドで処理し、1回あたりの範囲・件数を制限する(F-5と同様の上限)。
- 呼び出し側は`CompletableFuture`で待つ。タイムアウトあり(既定5秒)。

## F-9〜F-10 CLI(Claude・Codex)

`06_ai_and_images.md`の1・5節。要点: 段は状態なし、構造化出力、画像入力、費用の記録、取消、Codexの作業フォルダの隔離。

## F-11 Create/Aeronauticsの依存と版固定(D-13)

- 現状(Phase 1で実装・実機確認済み): Create 6.0.10、Aeronautics 1.3.0、Sable 2.0.x。`neoforge.mods.toml`の依存範囲は、Create `[6.0.10,6.1.0)`、Aeronautics `[1.3.0,1.4.0)`、Sable `[2.0.0,3.0.0)`。
- `PartType.requires`(版の範囲)を、登録時に実行中のModListと照らし、範囲外なら**その部品だけ無効**にして、理由(必要な版・実際の版)を利用者に見せる。無効な部品を含む計画は`E-REGISTRY-VERSION`。
- Createの更新時の手順: (1)忠実度テスト(F-12)を全部流す、(2)部品見本帳を作り直す、(3)モジュールライブラリのテンプレートを再検証、(4)通れば`requires`の範囲を広げる。
- 参考: Create本体のソースに、実機と同じ版のタグ`mc1.21.1-6.0.10`が実在する(`Creators-of-Create/Create`、調査担当が確認)。部品の設置手順のスパイク(S-5)は、この版のソースを読んで行う。

## F-12 Createブロックの設置と、忠実度テスト(P-13)

- 設置は`PartType.placer`の戦略で行う: `SIMPLE`(`setBlock`+ブロック状態)、`BELT`(メカニカルベルトはCreateの専用の連結処理が要る可能性が高い)、`ARM`(メカニカルアームは対象の設定が要る)、`MULTIBLOCK`(水車・風車・ピストンなど)ほか。**どの部品がどの戦略かは、S-5で実際に試して確定**する(推測で決めない)。
- **忠実度テスト(`FidelityLab`)**: 各部品・各テンプレートについて、(a)実際にサーバーで設置してブロック状態が施工リストどおりか、(b)Createが期待どおりに動くか(回転数・応力・品物の流れ)、(c)`KineticModel`の予測と一致するか、を検査する。方法は、NeoForgeのGameTest(`runGameTestServer`。S-7で動作を確認)。動かない場合は、開発者用の自己診断コマンドで、実機(`runClient`/`runServer`)で同じ検査を走らせる。
- 既存の慣習(JUnit+Fakes、`@GameTest`未使用)からの逸脱は、**Create連携の検査だけ**(レジストリが要るため)に限る。純Javaの核は従来どおりJUnit。

## F-13 チャンクとオフライン

- 施工は読み込み済みチャンクだけ。範囲内に未読み込みがあれば`PAUSED(CHUNK_UNLOADED)`。所有者が離れたら`PAUSED(OWNER_OFFLINE)`または(設定で)チャンクを保持(`TicketController`)。所有者が戻れば再開。強制読み込みは既定でオフ。

## F-14 取消とロールバック

- 取消: 現在の設置位置で止め、`CANCELLED`。設置済みの物はそのまま残る(「元に戻す」は別操作)。
- ロールバック(`ROLLBACK`ジョブ): `UndoEntry`(設置前のブロックとブロック状態)で復元。**戻せないもの**: ブロックエンティティの中身(チェストの中身など)。破壊を伴う置換の承認時に、中身を持つブロックの置換は原則拒否(F-5)する。材料はF-7で返却。

## F-15 設定

- サーバー設定(`micradrone-server.toml`): 安全枠の値、材料の方針、設置の速度、権限の水準、チャンク保持。
- クライアント設定(`micradrone-client.toml`): `claude`と`codex`の実行パス、費用の警告線・上限線、画像の参考枚数の予算、`CameraPreset`、ループの上限、初回のプライバシー同意の記録。
- 既存の設定の作法を確認して踏襲する(P3の計画書で)。

## F-16 マルチプレイ

- 専用サーバーにも入る設計: サーバーはCLIを必要としない(AIはクライアントで動く)。計画は、CLIを持つクライアントが作ってサーバーへ送る。
- 同じサーバーに複数人: プロジェクト・画像・費用は、プレイヤーごとにクライアントに保存。ジョブと区画はサーバーに保存し、所有者で区別する。
- 他人のジョブは見える(進行表示のみ)、操作は所有者かOPのみ。

## F-17 ログと診断

- プロジェクトの`journal`(各段の入力・応答・判断)、サーバーのジョブのイベントログ、`/micradrone build status`のようなコマンド(所有者・OPのみ)。失敗時は、「何が・どのIDで・なぜ」を利用者向けの日本語で出す。

## F-18 表示言語

- UI・エラー文は翻訳キー(日本語・英語)。AIの指示文は日本語を基本とし、部品名は登録簿の識別子と表示名を併記する。既存の`ChatContextBuilder`(日本語の文脈)と同じ方針。

## F-19 ドキュメントの同時更新

- 機能変更と同じタスクで、`drone/CommandsHelpDoc`(ゲーム内ヘルプ)、README、CurseForgeの説明文(`docs/curseforge_description.md`)を確認して直す(既存の記憶: 機能変更時にドキュメントも同時に更新)。各フェーズの完了条件に含める。

## F-20 既存機能との共存(P-15)

- 既存の`DroneApi`・`LiveDroneApi`・`PacedActionQueue`・`DroneControllerBlockEntity`(1,527行)の**挙動は変えない**。建設の新規コードは`build/`・`construction/`・`client/build/`に置く。`IdeScreen`(1,610行)に足すのは、入口(建設モードへの切替、チャットのカード)に限り、建設の画面は別クラス。
- 既存のテスト(47ファイル)は、全て緑のまま維持する。

## F-21 性能

- 1tickあたりの設置数と、スナップショットの読み取り量に上限(既存の`MAX_BLOCKS_PER_QUERY=1000`と同じ考え方)。大きな範囲の検査は、複数tickに分けて進める(`SnapshotJob`)。
- クライアントの解析(`Router`、`FactoryAnalyzer`等)は作業スレッドで、取消可能に。ホログラムは、チャンクセクション単位でメッシュをキャッシュ。
- 画像の縮小・PNG化は作業スレッド。

## F-22 セキュリティ

- ワールドの内容(看板の文字、アイテム名など)をAIに渡す場合は、**信用できないデータ**として、資料の中で明確に区切って渡す(AIが指示として解釈しないよう、指示文に書く)。AIは世界を書けないので被害は限定されるが、計画の内容が誘導されることは防ぐ。
- 受信ペイロードは全て、サイズ・座標範囲・文字列の形式(IDの正規表現、パスの検査)を検証。プロジェクトや画像のファイル名は、パス移動(`..`)を拒否。CLI引数は既存の`quoteForCmd`と同じ作法で必ずクォート。
- APIキーは扱わない(サブスクリプション認証のCLIを使う)。プロンプトに秘密情報を入れない。
