# 06 AIの各段と画像パイプライン

- Claudeの呼び出し方、各段の仕様(入力・出力・許可するツール)、MCP読み取りツール、画像の生成と読み取り、**参考画像の上限への対処**、カメラ、評価セット。
- 「実測」と書いた事実は、2026-09-25に実際に動かして確かめたもの(`00_index_and_principles.md` 4.1節)。「未確認」は、対応するスパイク(`07`)で確定する。

---

## 1. Claude CLIの呼び出し

### 1.1 2種類の呼び出し

| 種類 | 使う段 | セッション | 中身 |
|---|---|---|---|
| **対話** | Refiner(N-02)だけ | `--session-id`/`--resume`で再開。既存の`ChatSession`をそのまま使う | ユーザーとの会話。最後に`ConceptBrief`を構造化出力で確定させる |
| **段(状態なし)** | それ以外のAI段すべて | 毎回新しいセッション。再開しない | 資料(`Dossier`)を毎回渡して、構造化出力を1回得る |

理由(D-5): 段の入力が明示的なので、再現・記録・再生でテストでき、会話の履歴が肥大せず、前段のドリフトが混ざらない。既存の調査(`docs/investigations/spk1_claude_cli_behavior.md`)のとおり、`--setting-sources ""`で利用者のグローバル設定の混入(約$0.11の無駄)を避ける。

### 1.2 コマンドの形

既存の安全フラグは維持する: `--setting-sources "" --restricted --strict-mcp-config --tools ""`(`ClaudeCliBridge.SAFE_FLAGS`)。追加するもの:

| 目的 | フラグ | 状態 |
|---|---|---|
| 型付き出力 | `--json-schema <スキーマJSON>` | **実測**。応答JSONの`structured_output`(解析済みオブジェクト)と、`result`(JSON文字列)の両方に入る。内部でツール呼び出しを1回挟むため`num_turns`は2になる |
| 画像入力 | `--input-format stream-json`(標準入力に、`{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}},{"type":"text","text":"…"}]}}`を1行で) | **実測**。安全フラグのまま動作。**`--input-format stream-json`は、`--output-format stream-json`とセットでないと起動しない**(`--output-format json`にすると`Error: --input-format=stream-json requires output-format=stream-json.`)。`--verbose`を付け、応答の最後の`result`行(`type:"result"`)から`result`・`structured_output`・`total_cost_usd`を読む。`--json-schema`との併用も動作を実測(`structured_output`が返る) |
| 費用上限 | `--max-budget-usd <額>` | フラグの存在を`--help`で確認。挙動は未確認(S-2) |
| モデル指定 | `--model` | 段ごとの設定(強い段=Architect・Module Planner・Vision Critic、軽い段=Critique Router)。既定は利用者のCLIの既定。**既定のままでも動く**(実測時の既定は`claude-opus-5-5`) |
| 追加の指示 | `--append-system-prompt` | 各段の役割と出力の作法。既存の方針と同じ |

- **起動の方法(`StageCliRunner`)**: 既存の`chat/ClaudeCliBridge`は、実行中のプロセスを**1本しか持てず**(`inFlight`・`cancelRequested`が1組)、IDE画面を閉じると`close()`で止まり、タイムアウトが定数(120秒)で、引数を`cmd.exe /c`に載せる。建設のAI段は、並列の呼び出し(Approve0の後のVision ReaderとProcess Planner、L2の評価)、長い呼び出し、画面を閉じた後の続行が要るので、**別の`StageCliRunner`で動かす**。呼び出しごとに自前のプロセス・取消・タイムアウトを持ち、`BuildOrchestrator`(プロジェクト)の寿命に結びつく。既存の`ClaudeCliBridge`(チャット)は変えず、コマンドの組み立て・引用・「CLIが無い」判定の静的ヘルパだけを共有する。
- **`cmd.exe`を経由しない**: npmの`claude.cmd`は、中身が`...\node_modules\@anthropic-ai\claude-code\bin\claude.exe %*`を呼ぶだけ(林さんの環境で確認)。**S-1の実測(2026-09-26。`docs/investigations/spk_s1_json_schema_limits.md`)**: コマンドライン全体の上限は、`cmd.exe /c`経由で8,118文字、`claude.exe`の直接起動で32,766文字(約4倍)。しかも`cmd.exe`経由は、スキーマに空白が1つでもあると、文字列中の`&`・`|`・`<`・`>`が壊れ、`^`が黙って消え、`%VAR%`が展開される(コマンド注入の危険)。そこで、`claude.cmd`の隣の`node_modules\@anthropic-ai\claude-code\bin\claude.exe`を探して**直接起動**し、見つからなければ従来の`cmd.exe`経由に切り替える(切り替えたときは、下の安全上限を`cmd.exe`用の値に下げる)。
- **`--json-schema`はインラインの文字列だけ**(ファイルのパスを渡すと`JSON Parse error`で失敗することを実測)。**S-1の実測**: 上限は、スキーマ単体でなくコマンドライン全体(スキーマ中の`"`は`\"`になって1文字増える)で決まる。**安全上限は、コンパクトJSONで、直接起動が20,000文字(ハード32,766)、`cmd.exe`経由が5,000文字(ハード8,118)**。`enum`は1個あたり「IDの文字数+5」で、直接起動は9文字IDで1,000個まで実測OK(`cmd.exe`経由は564個が最大。安全には「個数×(ID長+5)が4,000文字以内」)。**機能は全部使える**(`enum`・`const`・`oneOf`・`anyOf`・`$defs`+`$ref`・再帰する`$ref`・深さ10のネスト・`additionalProperties:false`・`required`・`minimum/maximum`・`minLength/maxLength`・`pattern`・`minItems/maxItems`・`type`の配列。CLIの検証器が違反を実際に拒否した)。**ルートは必ず`"type":"object"`**(`oneOf`だけ・配列だとAPIが400)。登録簿から作る型つきの完全なスキーマは大きくなるので、**段ごとに関係する部品だけの部分集合**(建築段は建築部品、Module Plannerは動力・加工・物流の部品)にしぼる。それでも上限を超える規模では、パラメータを`params: [{key, value}]`の配列にする方式に切り替える(下の4節)。パラメータの意味の検証は、どちらの方式でも自前の検証器が行う。
- 応答から読むもの: `is_error`、`result`、`structured_output`、`session_id`、`total_cost_usd`(**実測で存在を確認**。`CostLedger`に記録)、`num_turns`、`usage`。
- **失敗の形(S-1実測)**: モデルがスキーマに従えなかったとき、**終了コード0・`is_error:false`・`subtype:"success"`のまま、`structured_output`のキー自体が無い**(`result`に「できなかった」という自然文が入る)。呼び出し側は、「`structured_output`のキーが在り、objectである」ことを成功の条件に含める(`result`をJSONとして読まない)。ルートが`type:object`でないなど、スキーマが不正なときは、終了コード1で、ローカルで拒否される(費用0)か、API 400になる。**検証を通っても意味が正しいとは限らない**: 検証エラーの後、モデルは値を捏造したり(必須の`b`を0で埋める)、空にしたり(`parts:[]`)、丸めたりして通すことがある。意味の検証は、常に決定論の側(`PlanPatcher`・`PlanCompiler`)が行う。
- 既存の`chat/ClaudeCliJson.java`は`result`と`session_id`しか拾わないので、`structured_output`と`total_cost_usd`を拾うよう拡張する。既存のテスト(`ClaudeCliJsonTest`)は維持する。**画像を渡す段は`stream-json`の出力になる**ので、その最後の`result`行を読む別のパーサ(`ClaudeCliStreamJson`)を、同じ`ClaudeCliResult`を返す形で新設する(既存の`json`の経路は変えない)。
- **タイムアウト**: 既存の定数`CLI_TIMEOUT_SECONDS=120`は対話用のまま。構造化出力の段は`StageSpec.timeoutSeconds`で個別に指定する(Architect、Module Planner、視覚の段は既定300秒)。
- **取消**: `StageCliRunner`の呼び出しごとの取消(プロセスツリーごと終了する作法は、既存の`ClaudeCliBridge.cancel()`と同じ)。取消された段は、状態を変えずに前の状態へ戻す。
- **記録と再生**: 各段の呼び出しの「入力の`Dossier`」と「生の応答」を、プロジェクトの`journal`に保存する。テストは、この応答を再生して、解析・検証・状態遷移だけを検査する(モデルの品質は検査しない)。

### 1.3 失敗の扱い(段に共通)

| 失敗 | 扱い |
|---|---|
| CLIが無い | 既存の`CLI_NOT_FOUND_MESSAGE`。工場機能の入口で案内。他の機能は使える |
| スキーマ違反・検証失敗 | 同じ入力に「違反箇所」を添えて**1回だけ**再試行。それでも失敗なら`E-SCHEMA`をユーザーへ(自動でこれ以上回さない) |
| タイムアウト・異常終了 | 状態を変えずに、ユーザーへ「もう一度」「中止」を返す |
| 費用の上限超過 | `CostLedger`の警告線でユーザーに確認、上限線で停止(D-10) |

---

## 2. AIの各段の仕様

`StageSpec`(段の宣言): `stageId`、種別、入力する資料、出力スキーマ、検証器、再試行、モデル、タイムアウト、許可するMCPツール。

| 段 | 資料(入力) | 出力 | 検証(決定論) | 許可ツール |
|---|---|---|---|---|
| Refiner N-02 | 会話、`ChatContext`、`KnowledgeStore`の好み・失敗例 | `ConceptBrief` | 生産物がレシピ解決できる、必須項目が埋まる | `query_recipes`(P7から)、`query_module_library`(ライブラリに工場用のテンプレートが載るP11から。それまでは空を返す) |
| Vision Reader N-05 | 承認画像、`PartAtlas`(絞り込み版)、`ConceptBrief` | `StructureDescription` | 部品名が登録簿のenum内、`region`が0〜1の範囲 | なし |
| Process Planner N-06 | `ConceptBrief`、`RecipeOption`候補、L3の`Issue` | `ProcessGraph` | 各工程が実在レシピ、流量の保存 | `query_recipes` |
| Site Planner N-10 | `ConceptBrief`、`StructureDescription`、`SiteSurvey`、床面積 | `ZoningPlan` | 敷地内、重なり(→Zoning Fixerに渡す) | `get_site_survey` |
| Architect N-13 | 建屋の画像3種、足跡、`SemanticPlan`、`Issue`/`CritiqueReport` | `PlanPatch` | `PlanPatcher`、`PlanCompiler` | `query_part_types`、`get_block_snapshot` |
| Module Planner N-16 | `SemanticMap`、`ProcessGraph`+`CapacityReport`、`ModuleLibrary`、`StructureDescription`、`Issue` | `PlanPatch` | 同上+ポート整合 | `query_module_library`、`get_semantic_map` |
| Decorator N-20 | 完成予想図、`SemanticMap`、`SemanticPlan` | `PlanPatch`(`DECOR`部品) | 同上 | `query_part_types` |
| Logistics Planner N-21 | `SemanticPlan`、`ProcessGraph`(搬入搬出)、敷地 | `LogisticsPlan` | 発着場の空間・余白 | `get_site_survey` |
| Vision Critic N-23 | 参考画像、スクリーンショット、`CameraPreset` | `CritiqueReport` | スコアが0〜1、差分の種類がenum | なし |
| Critique Router N-25 | ユーザーのダメ出し文、現在の状態の要約 | `RouteDecision` | 分類がenum | なし |
| Reconciler(AI部分) N-09 | 決定論の突き合わせ結果 | 提案(`絵を優先`/`工程を優先`+制約) | 制約が数値として妥当 | なし |
| Module Planner(L8の増設案) N-16 | `RuntimeReport`、`SemanticPlan`(増設案を出すAIの段は、L8ではModule Plannerだけ) | 増設案(`PlanPatch`の下書き) | `PlanPatcher`、`FactoryAnalyzer` | `get_capacity_report` |

- **書き込みツールは1つも無い**(D-1)。AIの成果物は、必ず`PlanPatch`などのデータで、決定論の検証器を通ってからでないと次へ進まない。
- 「許可ツール」は、その段の`--mcp-config`と`--allowedTools`で許可するツール名を絞る(既存の`ClaudeCliBridge.MCP_ALLOWED_TOOL`を、リストに拡張)。

---

## 3. MCP読み取りツール(全て読み取り専用)

既存の`chat/BlockSnapshotToolServer.java`(JDK標準の`HttpServer`、127.0.0.1、JSON-RPC)を拡張して、ツールを追加する。**どのプロジェクト・どの版のデータを返すかを決める文脈**が要る: 既存のMCPサーバーはプロセス全体で1つ・URLが1つなので、段ごとに、その呼び出し専用の`--mcp-config`(URLの経路に呼び出し用のトークンを含め、トークンが`(projectId, planRevision)`に対応する)を作る。トークンは呼び出しの終了で無効にする。`McpProtocol`のツール登録を、1個固定から一覧方式にする。

| ツール | 返すもの | データの出どころ |
|---|---|---|
| `get_block_snapshot`(既存) | 指定範囲のブロック名 | **サーバー**(既存の`ServerBlockSnapshotReader`。F-8経由。マルチプレイで権威)。接続が無い場合の代替として、クライアントの`ClientLevel`(既存の`LiveBlockSnapshotReader`) |
| `get_site_survey` | 地形調査(地表の高さ・水・木・ブロック名) | サーバー(F-8の問い合わせ経路) |
| `query_part_types` | 部品登録簿(名前・ポート・パラメータ・表示名) | 登録簿(クライアントにも同じ物がある) |
| `query_recipes` | `RecipeOption`の候補(品物または機械で検索) | サーバー(`RecipeManager`) |
| `query_module_library` | モジュールの一覧と仕様(ポート・毎分の生産量・応力) | `ModuleLibrary` |
| `get_semantic_map` | `SemanticMap`(スロット・部屋) | `BlueprintAnalyzer`(クライアントで計算、サーバーで検証) |
| `get_capacity_report` | `CapacityReport` | `CapacityCalculator` |
| `list_open_issues` | 未解決の`Issue` | 現在の解析結果 |

- サーバーが答えるツールは、`QueryRequestPayload(id, kind, args)`(クライアント→サーバー)と`QueryResponsePayload(id, json)`(サーバー→クライアント)で往復する。MCPの呼び出しスレッドは、応答の`Future`を待つ(既存の`ClientMainThreadDispatch`と同じ作法。タイムアウトあり)。
- **応答のサイズ制限**: 1回の応答は64KB以内(既存の`MAX_BODY_BYTES`と同じ考え方)。大きい物は要約して返し、範囲を絞って再問い合わせさせる(`get_block_snapshot`の既存上限`MAX_BLOCKS_PER_QUERY=1000`も維持)。

---

## 4. スキーマ生成と検証

- `SchemaGenerator`が、`PartTypeRegistry`から`--json-schema`用のスキーマを作る(P-16)。部品の識別子は`enum`。パラメータは部品ごとの仕様から。`additionalProperties:false`を基本にする。
- **AIの出力は信用しない**: スキーマに通っても、`PlanPatcher`・`PlanCompiler`・各アナライザが独立に検証する。スキーマは「形を整える助け」であって、検証の代わりではない。
- **方式(S-1で確定)**: `oneOf`・`$defs`・`const`・再帰は使えると実測できたので、機能の不足を理由にした切り替えは要らない。既定は**型つきの方式**(`type`が部品IDの`enum`で、部品ごとに`params`の型・範囲を`oneOf`で持つ)。スキーマが安全上限(直接起動20,000文字、`cmd.exe`経由5,000文字)を超える規模では、**パラメータ配列の方式**(`params: [{key, value}]`。型ごとの検証は自前の検証器)に自動で切り替える。**2方式の収まり方(P3で確定)**: `FLAT`(配列の方式)は、`cmd.exe`経由の5,000文字に収めるため、`logistics`・`constraints`の中身・パラメータ名・IDの`pattern`を緩める(検証は`PlanJson`・`PlanPatcher`が行う)。型つき(`TYPED`)は、`material`や4方向の`enum`などの共通部品を`$defs`へ寄せて、20,000文字に収める。収まらない規模では`SchemaTooLargeException`を投げる(黙って出さない)。1分岐は約160文字(簡素な場合)で、直接起動の上限で約120部品が目安。50分岐を超えたときの、検証エラー文の長さとモデルの選択精度は**未確認**(P7の評価で測る)。

---

## 5. 画像パイプライン

### 5.1 画像の種類と役割

| `ImageKind` | 何の絵 | 誰が作る | 誰が読む | 参考として渡すもの |
|---|---|---|---|---|
| `PART_ATLAS` | 部品見本帳(実在部品を並べた見本) | ゲーム内描画(`PartAtlasRenderer`) | 画像生成、Claude視覚 | (なし) |
| `CONCEPT_ART` | 工場全景の**カットアウェイ(屋根を一部外して、内部の機械が見える)鳥瞰図** | 画像生成 | Vision Reader、Vision Critic、人 | 部品見本帳(絞り込み)、L1・L2では前回の承認画像 |
| `BUILDING_RENDER` | 建屋の完成予想図 | 画像生成 | Architect、Decorator、Vision Critic | コンセプト画像、部品見本帳 |
| `STRUCTURE_GUIDE` | 構造用画像(壁・屋根・開口部・機械を色で塗り分け) | 画像生成 | Architect | 完成予想図(同じ構図) |
| `INTERIOR_SECTION` | 内部断面図(2階建て・キャットウォーク等の雰囲気) | 画像生成 | Module Planner | コンセプト画像、部品見本帳 |
| `SCREENSHOT` | ゲーム内撮影 | In-game Renderer | Vision Critic、L5'の参考 | (なし) |
| `REVISION` | スクリーンショットを元に描き直した絵(L5') | 画像生成 | Architect、Vision Critic | スクリーンショット、元の完成予想図 |

### 5.2 画像生成: `codex exec`

- 呼び出し(**実測済みの形**): `codex exec --enable image_generation -s workspace-write --skip-git-repo-check -C <作業フォルダ> -i <参考1> -i <参考2> … -`(指示文は標準入力)。**利用者のグローバル設定・規則・セッション保存の混入を避けるため、`--ephemeral --ignore-user-config --ignore-rules`を付ける**(3つとも`codex exec --help`にあることを確認。付けても画像生成が動くこと、認証が維持されることは、S-3で確認してから確定する。Claude側の`--setting-sources ""`と同じ目的)。実行するのは新設の`chat/CodexCliBridge.java`(`ClaudeCliBridge`と同型: 別スレッド、タイムアウト、取消でプロセスツリーごと終了、Windowsの`cmd.exe /c`経由の起動)。
- **作業フォルダ**は、プロジェクトの`images/_codex_work/<回>/`(使い捨て)。実測で、`.agents/`と`.git/`が作られたので、そこに閉じ込める。生成物は作業フォルダに保存させ、**出力のファイル名は指示文で指定し**(`out_<回のID>.png`)、最終メッセージは`--output-schema`(`{path, width, height}`)で構造化して受け取る。`ImageStore`は**そのパスのファイルだけ**を検査(PNGであること・大きさ・0バイトでないこと)してプロジェクトの`images/`へ移す。指定のファイルが無い・スキーマに合わない・複数の候補があるときは失敗として扱い(1回だけ再試行)、**フォルダを走査して推測しない**。移した後、`ImageArtifact`(ハッシュ・指示文・参考画像・費用)を記録する。作業フォルダは終了時に削除する。
- **所要時間**: 参考2枚で約2分(実測)。UIには進行表示と、取消ボタンを出す。同時に走らせるのは1つだけ。
- **費用**: Codexは報告のトークン数(実測: 25,224)を出すが、金額への換算は未確認(S-3)。`CostLedger`にはトークン数と**画像生成の回数**を記録し、金額は`null`にしておく(推測で埋めない)。**上限は、金額ではなく、回数(`softImages`/`hardImages`)とトークン数(`softCodexTokens`/`hardCodexTokens`)で効かせる**(`01` 9節の`Caps`)。
- Codex CLIが無い、ログインしていない場合は、案内を出す。工場機能の画像以外の部分(手書きの計画・スクリプト・施工)は、Codexなしで使える。

### 5.3 参考画像の上限と、その対処(重要)

**上限(確認済み)**

| 相手 | 上限 |
|---|---|
| 画像生成 GPT Image系API | 最大16枚(Codex同梱資料) |
| Codexの組み込み`image_gen`ツール | 資料に記載なし。2枚は実測で成功。上限はS-3で測る |
| Claude(API) | 1リクエスト最大600枚(200kコンテキストのモデルは100枚)、1枚10MB、8000×8000px。**20枚超で各辺2000px以下** |
| 出力画像(`gpt-image-2`) | 最大辺3840px、両辺16の倍数、縦横比3:1以下、総画素数655,360〜8,294,400 |

**対処: 「枚数を増やさず、1枚に詰める」+「必要なものに絞る」+「優先順位で切る」**

1. **部品見本帳は、1枚に部品を並べた画像(見本シート)にする。** 1部品1枚で渡さない。1枚のシートが、多くの部品を運ぶ。
2. **依頼に必要な部品だけの絞り込みシートを、毎回作る。** `AtlasSlicer`が、`ConceptBrief`・`ProcessGraph`が使う部品(例: 「鉄板と真鍮」ならメカニカルプレス、デポ、メカニカルベルト、メカニカルミキサー、鉢、ブレイズバーナー、水車、シャフト、歯車、ファンネル)と、建屋の基本部品(壁・屋根・窓・扉など)を選び、**1シートあたり最大12部品(4×3、1セル256px、シート1024×768px)**に収める。12を超える場合は、種類で最大2シートに分ける(機械・加工用、動力・搬送用)。全部品の見本は、登録簿の版ごとに1回だけ描画してキャッシュし、シートはそこから切り出して組み立てる(毎回ゲームで描画し直さない)。
3. **1回の画像生成に渡す参考画像は、既定で最大4枚**(設定で変更可、上限は資料の16枚)。優先順位(高い順): ① 部品見本シート(絞り込み) ② 承認済みコンセプト画像(構図・雰囲気の一貫性) ③ スクリーンショットまたは前回の絵(L5'・L1・L2) ④ 敷地の雰囲気(任意)。**枠を超える場合は、優先順位の低いものから外し、外した事実を、理由つきで`ImageArtifact.droppedReferences`に記録する(参考画像の判断を後から再現できるように)。** 外した物の内容は、文章(部品名と`visualDescription`)で補う。
4. **文章で補う**: 参考画像に入らない部品でも、指示文には、使ってよい部品の名前(JEIと同じ表示名、D-18)と見た目の短い説明を全部入れる。画像は「特に見た目が難しい部品」に優先して使う。
5. **サイズの管理**: 参考画像は、PNGで1枚8MB以下、長辺2048px以下に縮小して渡す(生成側の出力制約とは別に、転送と処理を軽くするため)。
6. **役割の明示**: 指示文で、`画像1: 部品見本(この機械だけを使って描く)`、`画像2: 承認済みコンセプト(構図と雰囲気を維持)`のように、番号と役割を書く(Codex同梱の指針: 複数画像は番号で参照し、使い方を書く)。
7. **Claude(視覚)側も同じ考え方**: 1回の呼び出しに渡す画像は多くても6枚程度に収め、常に**20枚以下**にする(20枚を超えると、各辺2000pxの厳しい制限が全画像にかかるため)。長辺は、標準で1568px、高解像度で2576px以下に縮小してから渡す(超えても自動縮小されるが、転送が無駄になる)。各画像に`Image N:`のラベルを付け、**画像を文章の前に置く**。トークンは`⌈幅/28⌉×⌈高さ/28⌉`(例: 1024×768のシートは1,036トークン)。
8. **上限の確定**: 枚数を増やしたときに、部品の再現度・所要時間・失敗がどう変わるかを、1・2・4・8・16枚で測る(スパイクS-3)。結果で既定値を確定する(仮に4枚としているのはこの測定までの暫定)。

`ReferenceImageBudget`(型): `maxImages`、`maxBytesEach`、`maxEdgePx`、`priority: List<RefRole>`。`ImagePromptBuilder`は、この予算に収まるように参考画像を選んで、指示文と一緒に返す。

### 5.4 部品見本帳(`PartAtlas`)

- **作る**: `client/build/PartAtlasRenderer`が、登録簿の各部品を、ゲームの描画機能で**画面外(オフスクリーン)**に描く(等角投影、背景は無地、セル256px)。各セルの下に、その部品の表示名(JEIと同じ翻訳キーの、ユーザーの言語の文字列)を入れる。全セルを敷き詰めた全体シートと、`AtlasEntry`の表(部品ID・表示名・座標)を保存する。
- **描画の方法(案、S-4で確定)**: オフスクリーンの`RenderTarget`にブロックの状態(または`ItemStack`のアイコン)を描き、`NativeImage`に読み出してPNG化する。複数ブロックでできる部品(ベルト、水車、風車など)は、代表的な向きの小さな設置例を、一時的に描画用の空間へ置いて撮る、という代替も検討する(S-4で、どの部品がどの方法で描けるかを一覧にする)。
- **版**: 登録簿の版ハッシュごとにキャッシュ。Createのバージョンや登録が変わると作り直す。
- **絞り込み**: `AtlasSlicer.slice(atlas, partIds, maxCells=12)`。セルをそのまま並べ直し、行列に詰める。
- **見た目の説明**: 各`PartType.visualDescription`(例: 「上から円盤状の押し板が下りてくる、縦長の機械」)は、見本を見せられない場合の文章による代替であり、画像の指示文にも入れる。
- **絵から読む側の照合**: Vision Readerは、同じ絞り込みシートを見ながら、絵に描かれた機械の名前を、登録簿のenumから選ぶ(D-19)。絞り込みシートは「選択肢の見本」になる。

### 5.5 `ImagePromptBuilder`(指示文の生成)

登録簿と`ConceptBrief`・`CameraPreset`から、指示文を機械的に作る。構成(Codex同梱の指針: 場面 → 主題 → 詳細 → 制約):

1. 用途: 「Minecraftのmod『Create』を使った工場の、コンセプトアート」
2. 場面と構図: `CameraPreset`の文(例: 「南東の斜め45度上からの鳥瞰図」)、時間帯・天気
3. 主題: 建屋の数と外観(`StyleSpec`の色・素材)、発着場、生産する物。**コンセプトアートは「屋根を一部外して(カットアウェイ)、内部の機械の並びが見える鳥瞰図」と指示する**(建屋の外側だけでは機械が写らず、L2の照合が測れないため。`03` L2)
4. **使ってよい機械(実在部品)**: 部品名(JEIの表示名)+見た目の説明の一覧、「画像1の部品見本の見た目に合わせる」
5. 制約: 「一覧にない機械は描かない」「文字を入れない」「Minecraftのブロックの見た目(立方体の格子)にする」
6. 画像の役割: `画像1: …、画像2: …`

- 指示文と参考画像の一覧は`ImageArtifact`に保存する(再現・比較・費用の追跡)。
- 建屋の画像(`BUILDING_RENDER`、`STRUCTURE_GUIDE`、`INTERIOR_SECTION`)の指示文は、同じ構造で、主題を建屋の詳細に変える。構造用画像は、「壁=X色、屋根=Y色、扉=Z色、窓=W色、機械=V色」の凡例を指示文に入れ、凡例を画像の隅にも描かせる。

### 5.6 絵の読み取り(Vision Reader・Architect・Vision Critic)

- Claudeに渡す画像は、5.3節の7のとおり。渡す前に、`ImageResizer`(クライアント側、AWTまたは`NativeImage`)で縮小する。
- 出力スキーマの部品名は登録簿のenum(D-19)。絵の中の登録簿に無い機械は`unrecognized`に入る。Reconcilerがその扱い(絵を直す/部品を追加する候補にする/無視)を決める。
- **enumは形を整えるだけで、実在性は保証しない**(絵の架空の機械を、似た実在部品に誤って当てはめる恐れがある)。そこで、次の**合格ゲート**を置く: (a)**Vision Readerを、Approve0の前に走らせ**(承認後の段は`StageMemo`で結果を再利用する)、承認画面に**認識された部品の一覧と、`unrecognized`の警告**(「この絵には登録簿にない機械が描かれています」)を必ず出す。**目立つ登録簿外の機械(`prominent=true`)が有る絵は、承認画面に出す前に、自動で1回だけ再生成する**(回数と費用に数える)。それでも残る場合は、警告つきで提示し、ユーザーが明示的に「この絵で進む」を選べる(上書きとして`ImageArtifact`に記録。D-26)。(b)これは**パイプライン全体の品質の合格線**で、個々の絵の合否は(a)のゲートが決める。評価画像セット(`6`節)で、**部品名の適合率(認識した部品のうち正しい割合)**、**必須部品の再現率**、**登録簿外の機械を実在部品と誤認した率**を測り、初期の合格線(適合率0.9以上、必須部品の再現率0.7以上、誤認率0.1以下)を満たさなければ、P8を完了としない。合格線は、S-10で人手ラベルの実測に基づいて較正してから凍結する。(c)`DescribedObject.visibility`が`HIDDEN`(屋根や壁で見えない)の部品は、再現率の分母に入れない。

### 5.7 In-game Renderer(ゲーム内撮影)と`CameraPreset`

- `CameraPreset`は、指示文の文章とゲーム内カメラの両方の**唯一の定義**(ずれを防ぐ)。既定のプリセット: `BIRD_SE45`(南東斜め45度の鳥瞰、距離は建屋全体の外接箱の大きさから決める)、`FRONT`(正面)、`INTERIOR_SECTION`(断面に近い側面、壁の一部を非表示にして撮る)。
- 撮影の条件: 昼間(夜なら待つか、撮影中だけ明るさを固定)、HUD非表示、エンティティ(ドローン等)非表示、チャンクの読み込みと描画の完了待ち、撮影後にカメラと設定を元に戻す。既存の`IdeCameraController`は同一パッケージ限定(package-private)で真上からの固定の視点だけなので再利用せず、新設の`client/build/BuildCameraController`(任意のyaw・pitch・距離・FOV)で撮る。公開の`IdeCameraMath`(カメラ姿勢の計算)の考え方を参考にする。
- 撮影: `Screenshot`の機構でフレームバッファを`NativeImage`に取り、PNGにする。マルチプレイでも、撮影は自分のクライアントだけで完結する。
- **生成画像とゲーム画像は、同じカメラにはならない**: ゲーム側のyaw・pitch・FOVは固定できるが、画像生成AIは指示した角度・距離・遠近を保証しない。そこで、Vision Criticは**画素の類似ではなく意味の比較**をし、比較の前に両画像を被写体の外接矩形でクロップして大きさを揃える(`03` 0.5節)。生成画像が指示した`CameraPreset`にどの程度従うかは、S-3(生成)とS-10(較正)で実測する。

### 5.8 費用と時間の台帳

- 実測の目安(2026-09-25): Claudeの視覚の呼び出し(小さな画像1枚)約4秒・$0.0129。構造化出力の小さな呼び出し約$0.021(内部で2ターン)。Codexの画像生成(参考2枚)約2分・25,224トークン。
- `CostLedger`は、段ごとに、Claudeは`total_cost_usd`(金額)を、Codexはトークン数と画像生成の回数を記録する。**警告線と上限線は3種類の単位で持つ**(金額=Claude、回数=画像生成、トークン数=Codex。`01` 9節の`Caps`。既定は設定、初回に利用者へ提示して同意を得る)。Codexの金額は不明なので、金額の上限とは比べない。L1・L2・L5'は画像生成を含むので、回るたびに費用(金額・回数・トークン)を表示して、**回数またはトークン数の上限線で止める**(`03_loops.md`)。

### 5.9 プライバシー

- 依頼文・画像・計画は、Anthropic(Claude)とOpenAI(Codex)のサービスに送られる。**初回に、その旨と、送られる内容の種類を表示して同意を得る**。Codexは、シェルなどを持つエージェントで、`-s workspace-write`は書き込み先を作業フォルダに絞るがOSの読み取り隔離ではない(`04` F-22)ので、その旨も同意画面に表示する。ワールドの座標・プレイヤー名など、不要な情報は、資料(`Dossier`)から除く(必要最小限)。

### 5.10 プロジェクトの保存場所

`<gameDir>/micradrone/projects/<projectId>/`: `project.json`(状態)、`images/`、`plans/`(版ごとの`SemanticPlan`と`PlanPatch`)、`manifests/`、`journal/`(各段の入力と応答の記録)、`ledger.json`。既存の`chat/ChatHistoryStore`の保存の作法(コントローラごとのファイル、破損時の扱い)を踏襲する。

---

## 6. 評価セット(AIの段の品質を測る)

- `docs/design/nl_factory_builder/evals/`に、日本語の依頼20件以上と、評価の観点を置く(小屋、赤い屋根の家、鉄板の工場、真鍮の工場、鉄板と真鍮の工場+発着場、存在しない品物(ネジ)の依頼、曖昧な依頼、矛盾する依頼など)。
- 指標: スキーマ通過率、`PlanPatcher`・`PlanCompiler`通過率、アナライザ通過までのループ回数、`Issue`の種類、費用、時間、絵から読んだ部品名の正答率(部品見本と人手ラベル)、Vision Criticのスコアと人の評価の相関。
- 実行は手動(費用がかかる)。結果は`docs/design/nl_factory_builder/evals/results/`に日付つきで記録し、プロンプト・スキーマ・モデルを変えるたびに比べる。
- **合格線(初期値。P4・P7・P8でベースラインを実測して調整し、緩める場合は理由を記録して林さんに報告する)**:
  | 指標 | 初期の合格線 | 使うフェーズ |
  |---|---|---|
  | 建築系の依頼で、スキーマ通過→コンパイル通過まで到達する率 | 0.9以上 | P7 |
  | 絵から読んだ部品名の適合率 / 必須部品の再現率 / 誤認率 | 0.9以上 / 0.7以上 / 0.1以下 | P8 |
  | Vision Criticのスコアと人の評価の相関(較正用の比較ペア) | 順位相関0.6以上 | P9 |
  | 工場系の依頼で、`ERROR`0件に到達する率(L3・L4の上限内) | 0.8以上 | P12 |
  | 1つの依頼あたりの費用(金額・画像回数・トークン)の中央値 | 記録し、上限線の既定値を決める根拠にする | P7〜P12 |
