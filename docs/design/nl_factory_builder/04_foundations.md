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
  | サーバーのメインスレッド | **ワールドへの読み書きだけ**: 承認の最終確認、ジョブの設置、状態の読み取り(`ServerStateReader`)、ドローン演出、試運転の入出力 |
  | サーバーのワーカー(`ServerWorkerPool`。有界・取消可能・プレイヤーごとに同時1件) | **重い純Javaの計算**: 計画の展開(`PlanExpander`、Routerを含む)、コンパイル、各アナライザ。メインスレッドで**不変の入力(地形調査`SiteSurvey`、登録簿、テンプレート)を取り出してから**ワーカーへ渡し、結果はメインスレッドへ投げ返す。20,000配置・3D経路探索・工場解析を1tickの中では行わない |
- 既存の`drone/MainThreadGateway.java`(`isOnMainThread()`を追加済み)を使う。既存の`drone/ServerBlockSnapshotReader.java`(Phase 2で追加済み)は、文字列でブロック名だけを返す既存の契約(クライアント実装・MCPと共有)なので**変更せず**、L7と意味解析には別契約の`construction/ServerStateReader`を新設する(`01` 8節)。スクリプト実行用スレッド(`DroneScriptRunner`)は建設の経路に使わない。

## F-2 施工ジョブの永続化

- `ConstructionJobStore`(NeoForgeの`SavedData`、ディメンションごと)には、**小さな状態だけ**を保存する: ジョブのID・状態・カーソル・修復ラウンド・各ファイルへの参照(`01` 8節の`ConstructionJob`)。大きな物は別ファイルに置く: **施工リスト本体**=`<world>/data/micradrone/manifests/<hash>.bin`(パレット圧縮)、**取り消し用の記録**(`UndoEntry`、最大20,000件)=`<world>/data/micradrone/jobs/<jobId>/journal.bin`、**材料台帳**(`MaterialLedger`)=`.../ledger.bin`。SavedDataを肥大させない。
- **ファイルの書き込みは原子的**(一時ファイルに書いてからリネーム)。読み込み時に、ハッシュまたは末尾の検査値が合わない・欠けているファイルは、**そのジョブを`PAUSED(RECOVERY_NEEDED)`のまま「復旧待ち」にして所有者に通知**する(黙って再実行しない)。復旧の選択肢: (a)世界の現状を`SparseSnapshot`で読み、施工リストとの差分から`REPAIR`ジョブを作る(施工リストが在る場合)、(b)ジョブを`FAILED`にして手動の後始末に任せる。
- **`journal`(施工前のブロックの記録)と`PlacedRegistry`は、区画が解放されるまで保持する**(D-23。後の`MODIFY`・`ROLLBACK`で、元の地形に戻すのに要る)。区画が解放された後に、どのジョブからも参照されない`journal`・`ledger`・施工リストを、起動時に削除する(孤児の掃除)。終端のジョブの`ledger`は、返却の上限の根拠(F-7)なので、区画の解放まで残す。
- サーバー再起動・ワールド再読込で、`RUNNING`だったジョブは`PAUSED`で復元し、所有者がオンラインでチャンクが読み込まれれば、自動で再開する。
- **`PacedActionQueue`は使わない**(D-11)。既存の実装はメモリ上の`ConcurrentLinkedQueue`だけで、停止すると保留中の処理が消える/後で実行されるため、永続すべきジョブに向かない。
- 1tickの上限: 既定で**ドローン1機あたり4tickに1個**(既存の`LiveDroneApi.ACTION_DELAY_TICKS=4`と同じ間隔、見せ場のため)。構造(`SITE_PREP`〜`ENVELOPE`)は、設定で**毎tick最大N個**(既定16)の高速施工にできる。ドローンの数は`clamp(ceil(総数/300), 1, 6)`。所要時間の見積もりは、承認画面に出す。
- **サーバー全体の施工予算(`ConstructionBudget`)**: マルチプレイで複数人が同時に施工しても、サーバーが重くならないようにする。(a)サーバー全体の**同時に動くジョブは最大4**(設定)。超えた分は待機列に入り、公平に(到着順に)順番が来る。(b)**サーバー全体で1tickに置く数の上限**(既定32)を、動いているジョブで分け合う。(c)**自動減速**: 直近の平均tick時間(`MSPT`)が閾値(既定45ms)を超えたら、設置の速度を半分にし、回復したら戻す。(d)減速・待機の理由は、所有者に`PAUSED(SERVER_BUSY)`として表示する。

## F-3 承認と改ざん防止(D-3)

```
[クライアント]                                   [サーバー]
 PlanPatch/SemanticPlan を確定
 ── SubmitPlanPayload(uploadId, seq, total, bytes) × n ──▶  再組み立て・サイズ検査・ハッシュ検査
                                                  **ワーカーで**、サーバー自身が展開→コンパイル→アナライザを実行
                                                    (PlanExpander → PlanCompiler → BlueprintAnalyzer/FactoryAnalyzer)
                                                  → PendingApproval(hash, owner, expiresTick)
 ◀── PlanPreviewPayload(hash, issues, bom, eta) ──
 ホログラム表示(クライアント自身のコンパイル結果のハッシュと比較)
 ── ApprovePlanPayload(hash) ──▶                       PendingApprovalと一致・所有者・期限・権限を確認 → ConstructionJob作成
 ◀── JobStatusPayload(jobId, state, progress) ──
```

- **承認・施工は、ディメンションにも拘束される**(D-27): `Site.dimension`を施工リストのハッシュに含め、承認の時点でプレイヤーが同じディメンションにいることを確認する。
- **検証パイプライン(`AnalysisPipeline`)は、フェーズごとに載る物が増える登録式**: P4=展開・コンパイル・安全枠、P6=+Blueprint Analyzer、P11=+Factory Analyzer。`ProcessGraph`は任意の欄で、無ければ工程の検査は行わない(P4の承認経路は、P11の部品に依存しない)。
- **クライアントが送るのは、`SemanticPlan`(`LogisticsPlan`を含む)・`ProcessGraph`(任意)・`TemplateBundle`だけ**(`SubmitPlanPayload`の中身)。展開結果(`ExpandedPlan`)や経路(Routerの出力)や施工リストは、**サーバーが手持ちのコードと、手持ちの同梱テンプレートで、決定論的に作り直す**。同梱テンプレートは、IDとハッシュが一致しなければ拒否。プレイヤーが昇格させたテンプレートは、`TemplateBundle`に本体を含めて送り、サーバーが**静的検証を通す**(通らなければ`E-TEMPLATE-UNVERIFIED`で拒否)。
- **サーバーが自分で作り直した施工リストのハッシュだけが承認できる**。クライアントが送った物をそのまま実行しない。クライアントとサーバーのハッシュが一致しなければ、承認ボタンを無効にして「バージョンの不一致」を表示(`E-REGISTRY-VERSION`)。
- **ペイロードの大きさ**: サーバー宛ての通常のカスタムペイロードは、バニラの実装で約32KBが上限と理解している(**未確認、S-6で実測**)。そのため、計画は**30KB以下のチャンク**に分けて送り、`uploadId`でまとめ、全体のハッシュで検証する。全体の上限は2MB、プレイヤーごとに同時1件、レート制限あり。既存の`SaveScriptPayload`の`MAX_SCRIPT_CHARS=10000`は、建設の経路には使わない。
- 承認の有効期限(既定10分)。期限切れ・所有者の退出・登録簿の版変更で無効になる。
- **地形の変化でハッシュが合わなくなる問題(調査の固定)**: クライアントが計画を作る前に、サーバーから`SiteSurvey`を受け取る(F-8)。サーバーは、その調査結果とダイジェスト(`SurveyRef`)を**一定時間(既定10分)保持**し、承認の流れでは**自分が発行した調査結果を使って再コンパイルする**(世界の現在の状態そのものではない)。したがって、調査から承認までの間に、範囲内で草が広がる・水が流れる・他人が建てるなどしても、ハッシュは変わらない。代わりに、**施工の実行時に、各位置の置換の可否を、その場の世界で確認**し、置けなくなった位置は`Conflict`として`PAUSED`にする(`E-SITE-CHANGED`が原因を示す。`E-REGISTRY-VERSION`とは別のコード)。保持の期限が切れていれば、再調査して再コンパイルする。
- **受け入れたリスク**: `ApprovePlanPayload`は`manifestHash`と、ユーザーが`ACCEPTED_RISK`にした`Issue`のID一覧(`AcceptedRisk`)を含む。サーバーは、各IDが**自分の解析結果に存在し、`acceptable=true`である**ことを確認し、満たさなければ承認を拒否する(クライアントが受け入れ不可の問題を受け入れたことにできない)。受け入れた内容は、ジョブと`journal`に記録する。

## F-4 権限・所有者

- 実測で確認した現状: 既存の`DroneControllerBlockEntity.ownerUuid`は、「最後にRunを押した人」に更新されるだけで、**権限の判定には使われていない**(`saveScript`・`startScript`は誰でも通る)。建設はこれに依存しない。
- 建設の権限:
  - ジョブの所有者=承認した人。**取消・承認・再開は所有者かOP**のみ。
  - **区画(`SiteClaim`)**: 承認時にワールドボックスと**作用範囲(`operatingBox`。機械の作用範囲・発着場の上空の空きを含む)**を予約する。他人のジョブや他の区画と重なる施工は拒否。**ジョブが終了しても解放しない**(D-23)。工場が存在する間は、他人の施工から内部空間・発着場の上空・機械の作用範囲を守る。所有者が工場を解体(`ROLLBACK`)して初めて解放される。区画の数の上限(プレイヤーごと、既定8)を設ける。
  - **建築権限の確認**(`PlacementPolicy.canPlace(owner, pos, state)`): (a)世界の境界内、(b)スポーン保護の範囲外(またはOP)、(c)NeoForgeの設置イベント(`BlockEvent.EntityPlaceEvent`)を、所有者を主体として発行し、**他modの保護がキャンセルすれば拒否**(保護modとの互換)、(d)設定の許可レベル(既定: 許可された全プレイヤー。大規模はOP)。
- 既存の農場機能の`saveScript`/`Run`に権限の穴がある件は、建設とは別の課題として**GitHub Issueに起票する**(P-15: 既存機能の挙動は、この機能の中では変えない)。

## F-5 安全枠(`SafetyEnvelope`)— 1個でも置く前に検査

| 項目 | 既定 | 超えたとき |
|---|---|---|
| 1ジョブの最大設置数 | 20,000 | 承認できない(OPは設定で緩和) |
| 施工範囲の最大の大きさ | 128×96×128 | 同上 |
| 高さの範囲 | ワールドの建築可能範囲内 | 該当部分を拒否 |
| 置換できるブロック | 空気、水・溶岩(要承認)、草・花・雪、木の葉(要承認) | 置換できないブロックは`E-SITE-BLOCKED`。**破壊を伴う置換の一覧を承認画面に表示**し、利用者が明示的に確認して初めて承認できる |
| 置換禁止 | 岩盤、バリア、コマンドブロック、**元からあった(このプロジェクトが置いたのではない)ブロックエンティティを持つブロック**(例外は、中身が空だと承認時に確認できたコンテナだけ。その場合も、ロールバックで空のコンテナとして戻す) | `E-SITE-BLOCKED`(明示承認で押し切る道は無い) |
| **このプロジェクトが置いたブロックエンティティ** | `PlacedRegistry`に載る保管庫・デポ・機械などは、`MODIFY`・`ROLLBACK`で撤去・変更できる(D-25)。**中身のアイテム(インベントリ・デポ・ファンネルのフィルタ・ベルト上の物)は、ドロップして保全する**(消さない)。ブロックそのものの材料の返却はF-7の規則。**流体・燃料など回収できない物は失われる**ので、撤去の承認画面に「液体◯mB・燃料◯が失われます」を表示して確認する | (許可。撤去の承認画面に「中身がN個ドロップします」を表示) |
| **整地(`SITE_PREP`の切り・盛り)** | 区画内の自然のブロック(タグ`micradrone:terraformable`: 土・草・砂利・砂・石・深層岩など)を切る/盛る。**承認画面に「地形の改変: 切る◯個、盛る◯個」を必ず表示し、確認して初めて承認できる**。サバイバルでは、**切ったブロックは所有者に集めて渡し**、盛るブロックは所有者から消費する(資源が消えも増えもしない)。クリエイティブでは無償 | 確認なしの整地は拒否(`E-TERRAFORM-UNCONFIRMED`) |
| **置いてよいブロック(`PlaceableBlockPolicy`、D-22)** | 登録部品が出力するブロックと、`StyleSpec.palette`の素材で、許可リスト(データパックのタグ`micradrone:palette_allowed`+登録部品の出力)に載る物だけ。**コマンドブロック、岩盤、スポナー、バリア、ストラクチャーブロック、ジグソー、光ブロックなどは、素材・部品のどちらの経路でも常に禁止**。AIやスクリプトが何を書いても、サーバーのコンパイルで弾く | `E-BLOCK-FORBIDDEN`(受け入れ不可) |
| 同時ジョブ | 所有者ごとに1(サーバー全体の予算はF-2の`ConstructionBudget`) | 待機列 |
| 読み込まれていないチャンク | 置かない | `PAUSED(CHUNK_UNLOADED)` |

**置いた後の動作の影響(D-24)**: 置いてよいブロック(D-22)の検査だけでは、機械が動作して区画の外の世界を壊す(ドリル・ソー・ハーベスター・プラウ・`offroad:`の掘削部品、ホースプーリーの液体の出し入れ、ポテト砲台、水源・溶岩の流れ出し、組み立てて飛ぶ飛行船)経路を防げない。そこで、**世界に作用する部品は、作用が届く範囲(`EffectSpec`)を宣言しないと登録できず**、Factory Analyzerが、作用範囲が区画の`operatingBox`の中に収まることを検査する(`E-EFFECT-ESCAPES-CLAIM`)。液体の設置(水源・溶岩)は、周囲の流れ出しを止める囲いが区画内にあることを検査する。飛行船は運航で区画を離れるので、衝突による破壊の有無と着陸のしかたをS-8で確認し、結果に応じた規則を決める。区画外に作用する部品(例: ポテト砲台)は、設定で許可された時だけ登録する。

## F-6 独自言語との往復(決定論プロファイル)

- 人が読んで直せる形(引き継ぎ文の要件): 計画は独自言語のスクリプトとして見える。`PlanScriptWriter`が`SemanticPlan`から出力し、`PlanRecorder`(新設の`PlanApi`の実装)がスクリプトを実行して`PlanPatch`を作る。**往復してもハッシュが変わらない**ことをテストで保証する(D-2)。
- **命令(P3で確定)**: 位置引数だけ(この言語にキーワード引数は無い)。`PlanOp`の全操作が、命令に1対1で対応する。**部品を置く命令の名前は、登録簿から機械的に決まる**(P-16)。

  | 命令 | `PlanOp` | 引数 |
  |---|---|---|
  | `site(dimension, x, y, z, facing, bounds[, terrain_digest[, claim_id]])` | `SetSite` | `facing`は`"north"`等、`bounds`は`[minU,minV,minW,maxU,maxV,maxW]` |
  | `style(role, material)`、`mood(tag)` | `SetStyle` | 呼んだ分を集めて、1つの`SetStyle`にする |
  | `<部品名>(id, parent, anchor, params[, tags[, label]])` | `AddNode` | `<部品名>`は、`micra:`の部品なら接頭辞を除いた名前(`wall`、`roof`、`door`…) |
  | `part(id, type, parent, anchor, params[, tags[, label]])` | `AddNode` | `type`は部品ID全体(`"create:mechanical_press"`、`"mod:press_station"`)。`micra:`以外の部品はこちら |
  | `update_params(id, params)` | `UpdateParams` | |
  | `relocate(id, anchor)` | `MoveNode` | (`move`は畑の命令なので使わない) |
  | `remove_part(id)` | `RemoveNode` | |
  | `connect(id, from, to, kind[, via[, constraints]])` | `AddConnection` | `from`・`to`は`"ノードID.ポート名"`。`via`は経由する部品IDのリストで、省略または`None`なら`Routing.Auto`、リスト(空のリストも)なら`Explicit`(空のリスト=つなぎの部品を置かず直接つなぐ)。`constraints`は`dict`(`max_length`・`avoid`・`max_turns`・`entry_dirs`) |
  | `disconnect(id)` | `RemoveConnection` | |
  | `logistics(docks, routes, flows)` | `SetLogistics` | `list`と`dict`(`01` 10節の`Dock`・`Route`・`CargoFlow`の欄名) |

  `anchor`は、`[u, v, w]`または`[u, v, w, 回転数, 鏡像]`(`Absolute`。回転数は0〜3、鏡像は`True`/`False`)、`["surface", 壁ID, "outer"または"inner", u, v]`(`OnSurface`)、`["slot", スロットID, 回転数, 鏡像]`(`InSlot`)。`parent`は親のID(なければ`None`)。`params`は`dict`で、検証は`PlanPatcher`が部品の`ParamSpec`で行う。案にあった`frame`(枠は`site`が持つ)、`place`・`power`・`dock`・`decorate`・`module`・`floor_slab`は、すべて`<部品名>`または`part`で表せるので置かない(名前を別に持つと、登録簿とずれる)。
- **追加の作法(P3で確定。既存の慣習に沿うが、畑の側を壊さない)**: (a)`lang/CommandNames`に**`PLAN`(建設の命令の一覧。部品を置く命令は登録簿から作る)を新設し、畑用の`ALL`は変えない**。`ALL`は`Interpreter.defineFunction`(`Interpreter.java:183`。組み込みと同名の関数の定義を拒否する)、`CommandNamesTest`、`SyntaxHighlighterTest`、IDEの補完が使っており、`wall`・`place`・`power`のような一般的な名前を入れると、既存の畑のスクリプトが「組み込みの命令なので再定義できない」で壊れる(P-15違反)。(b)`Interpreter`は`PlanApi`を任意で受け取り(`Interpreter(PlanApi, PlanRunLimits)`)、`PlanApi`があるときだけ`CommandNames.PLAN`の呼び出しを`PlanApi`へ渡す。`PlanApi`が無い(畑の)`Interpreter`では、建設の命令は従来どおり「unknown function」で、利用者が同名の関数を定義しても壊れない。(c)`lang/SyntaxHighlighter.highlight`に、命令名の一覧を渡す多重定義を足す(既存の`highlight(source)`は`ALL`のまま)。(d)`drone/CommandsHelpDoc`に、建設の命令の説明`BUILD_COMMANDS`を足す。**既存の`DroneApi`には足さず、別インターフェース`lang.PlanApi`にする**(P-15)。`Lexer`/`Parser`/ASTは無改修。農場の命令と建設の命令の混在は、実行前の静的検査(`PlanScriptProfile`)で拒否する。
- **決定論プロファイル(許可リスト方式)**: 建設スクリプトで使えるのは、**許可した命令だけ**。許可するのは、建設の命令(上の一覧)、制御構造(if・for・while・関数・変数)、純粋な補助(`len`、`abs`、`min`、`max`、`str`、`list`、`dict`、`set`、`range`、`print`)。**それ以外は静的検査で拒否する**。拒否される既存の命令(`lang/CommandNames.ALL`の実物): 畑の操作(`move`、`till`、`plant`、`harvest`、`do_a_flip`、`sleep_ticks`、釣り・金床の各命令、`set_output`、`pair_with`など)、知覚(`get_pos_x`、`get_time`、`get_weather`、`get_ground`、`can_harvest`など)、**`random`、`create_task`(本物のスレッド)、`semaphore`、`attach_isr`、`raise_interrupt`**。理由: これらは実行の順序や結果が毎回同じにならず、同じスクリプトから同じ`PlanPatch`が出る保証(往復のハッシュ一致、D-2)が崩れるため。ブラックリストではなく許可リストなので、将来`CommandNames`に命令が増えても、自動では建設で使えるようにならない。実行回数の上限(既定100,000文=ステップ)と時間の上限(既定5秒)を、`PlanRunLimits`として`Interpreter`に足す(既存の「畑の操作なしで100万文」の暴走検出とは別。`PlanApi`があるときだけ有効)。超えれば`E-SCRIPT-LIMIT`。
- **スクリプトの長さ**: 既存の`MAX_SCRIPT_CHARS=10000`は、1つのスクリプトに適用される。大きな計画は、`PlanScriptWriter`が**計画の並びのまま、文の切れ目で、文字数(10,000字)で分割して出力**する(各スクリプトの先頭に`# 建設スクリプト i/n`の行。建物ごとの分割は、計画の並びが建物ごとに固まっているとき、結果として同じになる)。`PlanRecorder`は、複数のスクリプトを順番に取り込める。
- **`PlanApi`の失敗の契約(P3で確定)**: `PlanApi`のメソッドが投げる`IllegalArgumentException`は「スクリプトの値が悪い」を意味し、命令名と行番号つきで利用者に報告される。`PlanBudgetException`は上限の超過(`E-SCRIPT-LIMIT`)。それ以外の例外は実装の失敗として扱い、利用者の入力のせいにしない。
- スクリプトを人が直して保存すると、それは新しい`PlanPatch`(新しい版)になる。承認は、**コンパイル後のハッシュ**に対して行う(D-3。スクリプトの文字列ではない)。

## F-7 材料(`MaterialPolicy`、D-4)

- **常に**、コンパイラが材料表(`bom`)を出す(承認画面に表示)。方針は**ゲームモードで決める**: 承認時に、所有者が**クリエイティブ**なら`CREATIVE_FREE`(消費しない)、**サバイバル**なら`SURVIVAL_CONSUME`。設定で強制も可能。
- `SURVIVAL_CONSUME`:
  - 材料の出どころ: 所有者のインベントリ、および区画内にある**補給チェスト**(所有者が指定したチェスト。指定方法はP4の計画書で決める。既存の`ScriptChestLibrary`のチェスト探索の作法を参考にする)。
  - 消費は**設置ごと**に、`placement.index`をキーにした冪等な記録で行う(再起動しても二重に消費しない)。材料が無ければ`PAUSED(MATERIALS_MISSING)`で、不足の一覧を所有者に通知。補給されれば自動で再開。
  - **返却のルール(材料の複製を防ぐ)**: 消費は設置ごとなので、**取消(`CANCELLED`)では未設置分は元々消費されておらず、設置済みの物は建物として残るので、返却しない**。**ロールバック(`ROLLBACK`)と`MODIFY`の撤去は、`MaterialLedger`に「消費した」と記録された配置について、実際に撤去した分の材料だけ**を返却する(クリエイティブで建てた物は記録が無いので、返却しない。サバイバルへの持ち込みによる複製を防ぐ)(返却先: 所有者のインベントリ、入らなければ足元にドロップ)。返却の記録も`MaterialLedger`に残し、二重に返却しない。
  - ブロックとアイテムの対応(`BlockToItem`表): 通常は1対1。特別な物(メカニカルベルトは`create:belt_connector`アイテム、ドアや半ブロックなど)は明示表で持つ。**アイテムを持たない内部ブロック**(`create:powered_shaft`など、`IMPLICIT`の部品)は、それを作る側の`USER`部品で数える(例: 蒸気機関)。表に無いブロックは`E-MATERIAL-UNKNOWN`で承認前に検出する。
  - **自然のブロック**(草・水・木の葉など、置換で壊す物)は、アイテムをドロップせずに消す(材料の増殖を防ぐ)。整地で切る土・石は、上の整地の規則で所有者に集める。**このプロジェクトが置いたコンテナを撤去するときの中身は、消さずにドロップして保全する**(D-25。ブロック自体の材料の返却とは別)。
- `CREATIVE_FREE`でも、材料表は出す(「買うとこれだけ」という情報として)。

## F-8 サーバー問い合わせ経路

- クライアント(MCPツールや解析)がサーバーの情報(地形、レシピ、権威の検証結果)を得る往復: `QueryRequestPayload(requestId, kind, argsJson)`(クライアント→サーバー、30KB以内)、`QueryResponsePayload(requestId, json)`(サーバー→クライアント、大きければ分割)。サーバーは、メインスレッドで処理し、1回あたりの範囲・件数を制限する(F-5と同様の上限)。
- 呼び出し側は`CompletableFuture`で待つ。タイムアウトあり(既定5秒)。

## F-9〜F-10 CLI(Claude・Codex)

`06_ai_and_images.md`の1・5節。要点: 段は状態なし、構造化出力、画像入力、費用の記録、取消、Codexの作業フォルダの隔離。

## F-11 Create/Aeronauticsの依存と版固定(D-13)

- 現状(Phase 1で実装・実機確認済み): Create 6.0.10、Aeronautics 1.3.0、Sable 2.0.x。`neoforge.mods.toml`の依存範囲は、Create `[6.0.10,6.1.0)`、Aeronautics `[1.3.0,1.4.0)`、Sable `[2.0.0,3.0.0)`。Aeronauticsの同梱jarは、中に`aeronautics`・`simulated`・`offroad`の3つのmodを持つ(jarJar)。**飛行船の組み立て・操縦・係留の部品は`simulated`のもの**なので、`PartType.requires`は、`simulated`(`[1.3.0,1.4.0)`)と`offroad`のmod IDも指定でき、部品の表示名は`assets/simulated/lang/`から取る。`mods.toml`の依存に`simulated`を足すかは、P13の計画書で、実際の同梱の挙動を確かめて決める。
- `PartType.requires`(版の範囲)を、登録時に実行中のModListと照らし、範囲外なら**その部品だけ無効**にして、理由(必要な版・実際の版)を利用者に見せる。無効な部品を含む計画は`E-REGISTRY-VERSION`。
- Createの更新時の手順: (1)忠実度テスト(F-12)を全部流す、(2)部品見本帳を作り直す、(3)モジュールライブラリのテンプレートを再検証、(4)通れば`requires`の範囲を広げる。
- 参考: Create本体のソースに、実機と同じ版のタグ`mc1.21.1-6.0.10`が実在する(`Creators-of-Create/Create`、調査担当が確認)。部品の設置手順のスパイク(S-5)は、この版のソースを読んで行う。

## F-12 Createブロックの設置と、忠実度テスト(P-13)

- 設置は`PartType.placer`の戦略で行う: `SIMPLE`(`setBlock`+ブロック状態)、`BELT`(メカニカルベルトはCreateの専用の連結処理が要る可能性が高い)、`ARM`(メカニカルアームは対象の設定が要る)、`MULTIBLOCK`(水車・風車・ピストンなど)ほか。**どの部品がどの戦略かは、S-5で実際に試して確定**する(推測で決めない)。**組み立て(`ASSEMBLE`)**: 風車(帆を付けて組み立てる)、飛行船(物理アセンブラ)のように、組み立てで元のブロックが世界から消えて、動くエンティティ(コントラプション・サブレベル)になる部品は、`AssemblySpec`に従い、同じグループの部品を全部置いた後に、組み立てを実行する。検証は、消えた位置を欠落と扱わず、`AssemblyExpectation`(生成されたエンティティ数・移動したブロック数)を確認する(`01` 4節、`03` L7)。
- **忠実度テスト(`FidelityLab`)**: 各部品・各テンプレートについて、(a)実際にサーバーで設置してブロック状態が施工リストどおりか、(b)Createが期待どおりに動くか(回転数・応力・品物の流れ)、(c)`KineticModel`の予測と一致するか、を検査する。方法は、NeoForgeのGameTest(`runGameTestServer`。S-7で動作を確認)。動かない場合は、開発者用の自己診断コマンドで、実機(`runClient`/`runServer`)で同じ検査を走らせる。
- 既存の慣習(JUnit+Fakes、`@GameTest`未使用)からの逸脱は、**Create連携の検査だけ**(レジストリが要るため)に限る。純Javaの核は従来どおりJUnit。

## F-13 チャンクとオフライン

- 施工は読み込み済みチャンクだけ。範囲内に未読み込みがあれば`PAUSED(CHUNK_UNLOADED)`。所有者が離れたら`PAUSED(OWNER_OFFLINE)`または(設定で)チャンクを保持(`TicketController`)。所有者が戻れば再開。強制読み込みは既定でオフ。

## F-14 取消とロールバック

- 取消: 現在の設置位置で止め、`CANCELLED`。設置済みの物はそのまま残る(「元に戻す」は別操作)。材料は返却しない(F-7)。
- ロールバック(`ROLLBACK`ジョブ): `UndoEntry`(設置前のブロックとブロック状態)で復元する。**元からあったブロックエンティティの置換はF-5で禁止**(中身が空だと確認できたコンテナだけ例外)なので、施工前の状態が復元できないものは無い。**このプロジェクトが置いたブロックエンティティ**は撤去でき、中身はドロップして保全する(D-25)。**組み立てた物(風車の帆、飛行船)は、先に組み立てを解く**(`AssemblySpec.disassembleAction`)。組み立て後の個体(エンティティのUUID・サブレベルID)は、`AssemblyResult`として`PlacedRegistry`に記録してあり、解体・撤去は**その記録で個体を特定して**行う(記録が無い個体は触らない)。飛行船が定位置にない場合は、戻るまで`PAUSED`にする。復元の完了とは、**置換したすべてのブロックが、設置前のブロックとブロック状態に戻ったこと**(`SparseSnapshot`で確認)。ロールバック後に、プレイヤーが位置を変更していた場合(`Conflict`)は、その位置には触れず、報告する。材料はF-7の規則で、撤去した分だけ返却。

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

- 1tickあたりの設置数(F-2の`ConstructionBudget`)と、状態の読み取り量に上限(既存の`MAX_BLOCKS_PER_QUERY=1000`と同じ考え方)。**メモリは、読む座標の数に比例させる**: L7の検査は、施工リストの配置位置だけを読む(`SparseSnapshot`、最大20,000件)。意味解析用の範囲読み取りは、複数tickに分けて、**1セル1バイトの分類グリッド**(`VoxelClassGrid`、最大約157万セルで約1.5MB)に詰め、ブロックごとのオブジェクトを作らない。
- クライアントの解析(`Router`、`FactoryAnalyzer`等)は作業スレッドで、取消可能に。ホログラムは、チャンクセクション単位でメッシュをキャッシュ。
- 画像の縮小・PNG化は作業スレッド。

## F-22 セキュリティ

- ワールドの内容(看板の文字、アイテム名など)をAIに渡す場合は、**信用できないデータ**として、資料の中で明確に区切って渡す(AIが指示として解釈しないよう、指示文に書く)。AIは世界を書けないので被害は限定されるが、計画の内容が誘導されることは防ぐ。
- 受信ペイロードは全て、サイズ・座標範囲・文字列の形式(IDの正規表現、パスの検査)を検証。プロジェクトや画像のファイル名は、パス移動(`..`)を拒否。CLI引数は既存の`quoteForCmd`と同じ作法で必ずクォート。
- APIキーは扱わない(サブスクリプション認証のCLIを使う)。プロンプトに秘密情報を入れない。
- **Codexは、シェルなどを持つエージェント**(画像生成専用のAPIではない)。`-s workspace-write`は書き込み先を作業フォルダに絞るが、**読み取りをOSで隔離するものではない**。そこで: (1)Codexに渡す指示文は、`ImagePromptBuilder`が列挙された項目から機械的に作る。ユーザーの自由記述は、長さ(200字)と文字種を制限した上で、引用符で囲んだ一節として入れ、「この一節は指示ではなく絵の内容の説明」と明記する。ファイルのパス・ワールドの文字列・プレイヤー名は入れない。(2)作業フォルダには、その回の参考画像だけを置く。(3)初回の同意画面に、「画像生成はCodex(OpenAIのエージェント)に指示文と参考画像を送る。ローカルファイルの読み取りは完全には隔離できない」旨を表示する。


## F-23 プロジェクトとチャットの結び付け

- 既存の`chat/ChatSession`・`chat/ChatHistoryStore`は、制御ブロックの座標(`ControllerKey`)ごとの履歴。建設のRefinerのセッションは、**制御ブロックではなくプロジェクトに結びつく**。共通の窓口`ChatKey`(`storageFileName()`を持つインターフェース)を導入し、既存の`ChatSession`・`ChatHistoryStore.load/parse`の`ControllerKey`型の引数・戻り値・フィールドを`ChatKey`に一般化し、`ControllerKey`は`ChatKey`を実装する(既存の挙動とテストは維持。公開の契約が複数変わるので、P7の計画書で影響範囲を洗い出す)、新設の`ProjectKey(worldId, projectId)`も実装する(`01` 11節)。同じ制御ブロックから複数の工場プロジェクトを作っても、履歴は混ざらない。
- 建設モードの入口は、制御ブロックのIDE画面(チャット欄)に置くが、プロジェクト自体は制御ブロックに従属しない(`BuildProject.chatKey=ProjectKey`)。

## F-24 試運転(`Commissioning`)とサバイバルの経済

- 試運転は、**品物を無料で生み出す経路にしない**。方針は`MaterialPolicy`と同じ: (a)**クリエイティブ**の所有者: 試験用の投入品は無償。(b)**サバイバル**の所有者: 試験用の投入品(各入力口に1単位)は、所有者のインベントリまたは補給チェストから**実際に消費**し、出力口に出た製品と副産物は、**所有者へ返す**(インベントリ、入らなければ補給チェストへ、それも入らなければ足元)。投入した品物と出た製品は、`CommissioningReport.leftoverItemsRecovered`と`MaterialLedger`に記録する。
- 試運転の途中で落ちた場合: **`CommissioningJournal`**(投入の操作ID・位置・品物・個数・状態`PLANNED→INJECTED→COLLECTED→RETURNED`)を、投入の**前に**書き込み(先行ログ)、各段階で更新する。再起動時は、`INJECTED`のまま残る投入について、その位置の入力口・出力口・機械の内部から回収して所有者へ返し、`RETURNED`にする。状態で冪等になり、二重に返さない。
- 燃料(ブレイズバーナーなど)の補給は、テンプレートが自動の補給手段を持つ場合はそれを使い、持たない場合は所有者が補給する。試運転の前に、燃料が有るかを確認し、無ければ`W-FUEL-SUPPLY`と手順を出す(S-5cで自動補給の可否を確認)。
