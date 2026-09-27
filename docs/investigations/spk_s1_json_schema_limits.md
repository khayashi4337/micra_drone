# SPK-S1: `--json-schema` で使えるスキーマの機能と、大きさの限界

AIの段(クライアントで `claude -p ... --json-schema '<スキーマ>' --output-format json` を呼び、応答の
`structured_output` を使う)の設計に向けた調査(スパイク)。**すべて実測**
(実際に実行し、返ってきたJSONやエラー文を確認した)。実行していない項目は「未確認」と書いた。
コードはリポジトリに一切足していない。実験に使った補助スクリプトは作業用の一時ディレクトリに置き、終了時に削除した。

## 1. 環境

| 項目 | 値 |
|---|---|
| CLI | `claude --version` → `2.1.282 (Claude Code)`(stream-json の init イベントの `claude_code_version` も 2.1.282) |
| 使われたモデル | `claude-opus-5-5`(`--model` は指定せず。結果JSONの `modelUsage` と init イベントの `model` で確認) |
| 日付 | 2026-09-26 |
| OS / シェル | Windows 11 Pro 10.0.26200 / Git Bash(MINGW64)。呼び出しはGit Bashから起動した Windows版 Python 3.12.6 の `subprocess`(CreateProcessW)で行った |
| Java(補助検証のみ) | Corretto 17.0.1(`ProcessBuilder` の引用符の扱いを確認するため。API呼び出しは無し) |
| 文字コード | 日本語ロケール。cmd.exe のエラー文はOEMコードページ(cp932)で出る(UTF-8として読むと文字化けする) |
| 起動方式(i) | `claude.exe` を直接起動(npmの `...\node_modules\@anthropic-ai\claude-code\bin\claude.exe`) |
| 起動方式(ii) | `cmd.exe /c claude ...`(PATH上の `claude.cmd` = npmのラッパー経由。中身は `"<npmのディレクトリ>\node_modules\@anthropic-ai\claude-code\bin\claude.exe"   %*` の1行のみ、`cat` で確認) |

### 呼び出しの作法と、作法からの逸脱(正直に記録)

- 全呼び出しに `--setting-sources ""` を付けた(指示どおり)。
- **逸脱**: 最初の4回(§4の表の上3行)は `--setting-sources ""` だけ(うち1回は `--tools ""` を足した)で実行したが、
  新しいスキーマの初回は $0.185〜$0.199(cache_creation 23,001〜24,657トークン)、`--tools ""` を足した1回は **$0.766**
  (cache_creation 95,670トークン。理由は調べていない)かかった。予算($3目安)に収まらないため、
  **5回目以降は全て `--setting-sources "" --strict-mcp-config --tools ""` を付けた**(1回 $0.03前後、
  cache_creation 約3,200トークン)。SPK-1 の安全モード(`--tools "" --strict-mcp-config` を含む)と同じ系統の組み合わせであり、
  `StructuredOutput` ツールはこの状態でも動く(init イベントの `tools` は `['StructuredOutput']` のみ)。
- 全ての試験で、プロンプトは短い英語の1文を `-p "<prompt>"` で渡した(標準入力ではない)。
  SPK-1の「プロンプトはstdinで渡す」設計とは違うので、コマンドライン長は本番構成よりプロンプト分(約20文字)だけ長めに出ている。
- 日本語はUTF-8のJSONファイルから読んで渡した。それ以外は全てASCII。
- コマンドライン文字列は `subprocess.list2cmdline` の規則(引数中の `"` は `\"` にする)で組み立てた。
  Java 17 の `ProcessBuilder` でも同じ結果になることを§3-3で確認している。
- 回数は目安(20回前後)を大きく超えた(記録した呼び出し75回。うちAPI課金は62回、残りは無料の局所エラー・コマンドライン長エラー)。
  合計費用は **$2.9449**(目安$3以内)。1回が小さいので費用は収まったが、回数は正直に超過。

## 2. 機能の表

### 2-0. 仕組み(実測で判明したこと。以降の表の読み方)

`--json-schema` を渡すと、CLIは **`StructuredOutput` という名前のツール**を1つ登録し、渡したスキーマをその入力スキーマにする。
モデルがそのツールを呼ぶと、**CLIが入力をスキーマで検証**する(ajv系の検証器と思われる。実装は未確認)。

- 合格 → ツール結果 `Structured output provided successfully`、その入力が `structured_output` になる。
- 不合格 → ツール結果(`is_error: true`)に `Output does not match required schema: <エラー>` が返り、モデルが直して再送する。
  モデルが文章で返して終わった場合は、CLIが `[structured-output-enforce] You MUST call the StructuredOutput tool to complete this request. Call this tool now.` という
  user メッセージを注入して再度呼ばせる(検証エラーを出させた試験のうち、充足不能なスキーマ U1 と enum外の強制 EN-adv を除く全てで、この1回の注入で合格した。U1 と EN-adv は§2-3の「形C」)。
- したがって「従うか」は、**モデルが最初から従う**か、**検証器が拒否して再試行で従う**かのどちらか。
  表の「従う」列は、わざと従いにくい依頼(範囲外の値を頼む等)でどちらになったかを備考に書いた。
- 検証器を本当に動かして文言を採るために、「このJSONをそのまま `StructuredOutput` に渡せ(検証テストだ)」と頼む強制送信テスト(V系)も行った。

### 2-1. 機能ごとの結果

凡例: 受け付ける=CLIとAPIがスキーマを受理した / 従う=最終的な `structured_output` がスキーマに従った。
「第1試行」は最初のツール呼び出しの内容、「再試行後」は検証エラー後の最終値。

| 機能 | 受け付ける | 従う | 備考・エラー文(一字一句) |
|---|---|---|---|
| `enum` | ○ | ○ | F1: 「color=purple」と頼んでも、モデルが最初から `blue` を選び合格(検証器は動かず)。V1: `purple` を強制送信 → `Output does not match required schema: /color: must be equal to one of the allowed values: ["red","green","blue"]` → 再試行後 `red` |
| `const` | ○ | ○ | V2: `car` を強制送信 → `Output does not match required schema: /kind: must be equal to constant: "drone"` → 再試行後 `drone` |
| `required` | ○ | ○ | F3: 「b を含めるな」と頼む → 第1試行 `{"a":1}` → `Output does not match required schema: root: must have required property 'b'` → 再試行後 `{"a":1,"b":0}`(**モデルが b=0 を捏造して通した**) |
| `additionalProperties: false` | ○ | ○ | F4: 余計な z を頼んでもモデルが最初から省いた。V3: 強制送信 → `Output does not match required schema: root: must NOT have additional properties ('z' is not allowed)` → 再試行後 `{"a":1}` |
| `minimum` | ○ | ○ | V12: n=1(minimum 5)を強制送信 → `/n: must be >= 5`(minLength・minItems と同じ1本のエラー文に連結。原文は§2-2) → 再試行後 n=5 |
| `maximum` | ○ | ○ | F5: 「n=999 のまま返せ」(maximum 10)→ 第1試行 `{"n":999}` → `Output does not match required schema: /n: must be <= 10` → 再試行後 `{"n":10}`(**「変えるな」と頼んでも最終値は丸められた**) |
| `minLength` | ○ | ○ | V12: `/s: must NOT have fewer than 3 characters (got 1)` → 再試行後 `abc` |
| `maxLength` | ○ | ○ | F6: 20文字を頼む(maxLength 5)→ `Output does not match required schema: /s: must NOT have more than 5 characters (got 20)` → 再試行後 `aaaaa`(切り詰め) |
| `pattern` | ○ | ○ | F7: `ABCDE` を頼んでもモデルが最初から `abc` に直した。V4: `ABCDE` を強制送信 → `Output does not match required schema: /s: must match pattern "^[a-z]{3}$"` → 再試行後 `abc` |
| `minItems` | ○ | ○ | V12: 空配列を強制送信 → `/xs: must NOT have fewer than 2 items (got 0)` → 再試行後 `[1,2]` |
| `maxItems` | ○ | ○ | F8: 6個を頼んでもモデルが最初から `[1,2]` にした。V5: 6個を強制送信 → `Output does not match required schema: /xs: must NOT have more than 2 items (got 6)` → 再試行後 `[1,2]` |
| `type` の配列(null許容)`["string","null"]` | ○ | ○ | F9: `{"v":null,"w":5}` が第1試行で合格(null許容が効く)。V6: `v:123, w:"hello"` を強制送信 → `Output does not match required schema: /w: must be integer,null` → 再試行後 `{"v":"123","w":null}` |
| `anyOf` | ○ | ○ | F12: 正常入力は第1試行で合格。V8: `{"kind":"drill","length":5}` を強制送信 → `Output does not match required schema: /parts/0: must have required property 'depth', /parts/0: must NOT have additional properties ('length' is not allowed), /parts/0/kind: must be equal to constant: "belt", /parts/0: must match a schema in anyOf` → 再試行後 `{"kind":"drill","depth":5}` |
| `oneOf` | ○ | ○ | F11: 2分岐(drill/belt)の配列を第1試行で合格。V7: `{"kind":"laser"}` を強制送信 → `Output does not match required schema: /parts/0: must have required property 'depth', /parts/0/kind: must be equal to constant: "drill", /parts/0: must have required property 'length', /parts/0/kind: must be equal to constant: "belt", /parts/0: must match exactly one schema in oneOf` → 再試行後 `{"parts":[]}`(**空にして逃げた**)。50分岐(8,048文字)のoneOfも受理(O50。第1試行でモデルが `{"parts":[]}` を返し、検証エラーは出ず) |
| `$defs` + `$ref` | ○ | ○ | F13: `#/$defs/Pos` を2か所から参照、第1試行で合格。V9: `{"from":{"x":1},"to":{"x":3,"y":"q"}}` を強制送信 → `Output does not match required schema: /from: must have required property 'y', /to/y: must be integer`(参照先まで検証される)→ 再試行後 `{"from":{"x":1,"y":0},"to":{"x":3,"y":0}}`(y を 0 で捏造) |
| `$defs` + `$ref` + `oneOf`(分岐が `$ref`) | ○ | ○ | F14: 正常入力が第1試行で合格。V10: `{"kind":"drill","length":5}` を強制送信 → エラー文は V8 と同型(末尾が `must match exactly one schema in oneOf`)→ 再試行後 `{"kind":"drill","depth":5}` |
| 再帰する `$ref`(`Node.children: Node[]`) | ○ | ○ | F15: 3階層のツリー(a→b→c、a→d)を第1試行で生成し合格。V11: 3階層目の name 欠落を強制送信 → `Output does not match required schema: /root/children/0/children/0: must have required property 'name'`(再帰の奥まで検証される)→ 再試行後 `name:""`(空文字で逃げた) |
| 深いネスト 深さ3 / 6 / 10 | ○ | ○ | D3・D6・D10 とも、葉の値 7 を持つ完全な入れ子を第1試行で生成(スキーマ297 / 552 / 894文字)。D10 の葉に 99(maximum 9)を強制送信 → `Output does not match required schema: /l1/l2/l3/l4/l5/l6/l7/l8/l9/l10: must be <= 9` → 再試行後 9 |
| ルートが `type: "object"` でない(参考) | **×** | – | E1: ルートが `oneOf` のみ(type無し)→ `API Error: 400 tools.1.custom.input_schema.type: Field required`。E2: ルートが `{"type":"array",...}` → `API Error: 400 tools.1.custom.input_schema.type: Input should be 'object'`。**ルートは必ず `"type":"object"`**。詳細は§2-3 |
| 不正なスキーマ(参考) | ×(CLIがローカルで拒否) | – | E3: `{"type":"bogus"}` → `Error: --json-schema is not a valid JSON Schema: data/properties/a/type must be equal to one of the allowed values, data/properties/a/type must be array, data/properties/a/type must match a schema in anyOf`。E5: 解決できない `$ref` → `Error: --json-schema is not a valid JSON Schema: can't resolve reference #/$defs/Missing from id #`。E4: JSONとして壊れている → `Error: --json-schema is not valid JSON: JSON Parse error: Property name must be a string literal`。いずれも API を呼ぶ前(費用0、0.4〜1.0秒、終了コード1、stdout空) |

### 2-2. V12 のエラー文(下限系3つが1本に連結される)

`Output does not match required schema: /n: must be >= 5, /s: must NOT have fewer than 3 characters (got 1), /xs: must NOT have fewer than 2 items (got 0)`
(検証器は「最初の1件で止まらず、全ての違反を列挙する」。oneOf/anyOf のエラー文が分岐ごとの理由を全部並べるのも同じ性質。)

### 2-3. 失敗の形(終了コードだけでは判別できない。設計上重要)

| 形 | 条件(実測) | 終了コード / `is_error` / `subtype` | `structured_output` | 費用 |
|---|---|---|---|---|
| A. ローカルでスキーマ拒否 | JSONが壊れている・スキーマとして不正・`$ref` が解決できない | 終了コード1、stdoutは空、stderrに `Error: --json-schema ...` | – | 0 |
| B. API 400 | ルートが `type: object` でない | 終了コード1、stdoutにJSONあり。`is_error: true`、`subtype: "success"`(**subtype は success のまま**)、`api_error_status: 400`、`terminal_reason: "api_error"`、`result` に `API Error: 400 tools.1.custom.input_schema.type: ...` | キー自体が無い | 0 |
| C. **モデルが従えない/従わない** | U1: 充足不能なスキーマ(`minimum:10, maximum:1`)。EN-adv: enum外の値(`part_1001`)を「変えるな」と依頼 | **終了コード0、`is_error: false`、`subtype: "success"`**、`terminal_reason: "completed"` | **キー自体が無い**(nullでもない)。`result` に「できなかった」という自然文が入る | 課金あり(U1: $0.041) |

- 形Cが最も危険: **終了コード0・`is_error` false のまま `structured_output` が無い**。呼び出し側は必ず
  「`structured_output` のキーが存在し、object であること」を判定に含めること(`result` の文字列をJSONとして読もうとしてはいけない)。
- U1 では「不合格 → モデルが文章で返す → enforce 注入 → 再度不合格 → モデルが文章で返す」で打ち切られた(enforce は1回だけ注入)。
  再試行の上限回数の仕様は未確認。
- **検証に合格するだけで内容が正しいとは限らない**: 検証エラーの後、モデルは値を捏造したり(F3 b=0、V9 y=0)、
  空にしたり(V7 `parts:[]`、V11 `name:""`)、丸めたり(F5 n=10、F6 切り詰め)して合格させた。
  スキーマ適合は保証されるが、意味の正しさはモッド側で別に検証する必要がある。

## 3. 大きさの表

コマンドライン長 = 実際に Windows へ渡す1本の文字列の長さ(`"` は `\"` になるので、スキーマ中の `"` 1個につき1文字増える)。
実測した関係式: `コマンドライン長 ≒ スキーマ文字数 + スキーマ中の " の個数 + 固定分`。
固定分は本試験の引数構成(`-p "Return a=1."` などを含む)で、(i) 直接起動が約190文字(うち exe のパスが約85文字)、(ii) cmd経由が約120文字。
試験用スキーマは2種類: **密**=引用符が約19%を占める(`"pNNNN":{"type":"integer","minimum":0,"maximum":99},` を並べた省略可能プロパティ群)、
**疎**=引用符が26個だけ(長い `description` 1本で水増し)。

### 3-1. スキーマ全体の文字数(実際に呼び出して確認。「失敗」の行はAPIに届く前に落ちた)

| 方式 | スキーマ文字数(種別) | コマンドライン長 | 結果 | 失敗のしかた・エラー文 |
|---|---|---|---|---|
| (i) claude.exe直接 | 1,000(密) | 1,376 | 成功 | – |
| (i) | 4,000(密) | 4,956 | 成功 | – |
| (i) | 6,500(密) | 7,936 | 成功 | – |
| (i) | 8,000(密) | 9,726 | 成功 | – |
| (i) | 20,000(密) | 24,036 | 成功(cache_creation 15,075トークン、$0.122) | – |
| (i) | 30,000(密) | 35,956 | **失敗**(プロセス起動前) | Python: `FileNotFoundError: [WinError 206] ファイル名または拡張子が長すぎます。`。claudeは一切起動しない。費用0 |
| (i) | 30,000(疎) | 30,218 | **成功**(cache_creation 14,364トークン、$0.116)。API側の拒否は無い | – |
| (ii) cmd.exe経由 | 1,000(密) | 1,308 | 成功 | – |
| (ii) | 4,000(密) | 4,888 | 成功 | – |
| (ii) | 6,500(密) | 7,868 | 成功 | – |
| (ii) | 6,710(密) | 8,118 | 成功(**実測の最大**) | – |
| (ii) | 6,711(密) | 8,119 | **失敗** | 終了コード255、stderr `入力行が長すぎます。`、stdout空 |
| (ii) | 8,000(密) | 9,658 | **失敗** | 終了コード1、stderr `コマンド ラインが長すぎます。`、stdout空 |
| (ii) | 20,000(密) | 23,968 | **失敗** | 同上(終了コード1、`コマンド ラインが長すぎます。`) |
| (ii) | 30,000(密) | 35,888 | **失敗**(cmd.exe自体の起動前) | Python: `FileNotFoundError: [WinError 206] ファイル名または拡張子が長すぎます。` |
| (ii) | 30,000(疎) | 30,150 | **失敗** | 終了コード1、`コマンド ラインが長すぎます。` |

### 3-2. 上限の境界を1文字単位で確定(スキーマの末尾に不正キーワードを置き、全体が届けば「不正なスキーマ」とローカルで拒否される仕組みを利用。API呼び出しなし・費用0)

| 方式 | 最後に届く | 最初に失敗 | 失敗のしかた |
|---|---|---|---|
| (i) claude.exe直接 | コマンドライン長 **32,766**(疎スキーマで32,581文字) | 32,767 | プロセス起動前に OS が拒否。Python: `[WinError 206] ファイル名または拡張子が長すぎます。`。Java 17: `java.io.IOException: Cannot run program "...claude.exe": CreateProcess error=206, ファイル名または拡張子が長すぎます。`。CreateProcess の 32,767(終端NUL込み)と一致 |
| (ii) cmd.exe経由 | コマンドライン長 **8,118**(疎スキーマで8,001文字) | 8,119 | 8,119: 終了コード255、stderr `入力行が長すぎます。`。**8,120〜8,191: 終了コード255、stderr は空(無言の失敗)**。8,192以上: 終了コード1、stderr `コマンド ラインが長すぎます。` |

- (ii) の 8,119 で最初に落ちるのは、npmの `claude.cmd` が `"<npmのディレクトリ>\node_modules\@anthropic-ai\claude-code\bin\claude.exe"   %*` を1行に展開し、
  その行が8,191文字に達するため(この機械では引数より前が90文字。90 + 8,101 = 8,191 で失敗、という計算が実測と1文字単位で合った)。
  つまり **(ii)の上限は、npmのグローバルディレクトリのパスが長いほど下がる**(この機械での値。他の環境は未確認、計算上の推定)。
  cmd.exe 自身の限界(8,191)は、その1文字後の 8,192 で実測どおり表面化する。
- 2種類の失敗(255の無言/1の日本語メッセージ)とも stdout は空。**エラー文はOEMコードページ(cp932)なので、UTF-8で読むと文字化けする**。
  呼び出し側は文言に頼らず「終了コードが0でない / stdoutにJSONが無い」で判定すること。

### 3-3. cmd.exe 経由の追加の落とし穴(無料の反響試験: 不正な `$ref` 文字列をCLIが原文のまま報告する性質を使い、届いた中身を確認)

| 条件 | (i) 直接 | (ii) cmd.exe経由 |
|---|---|---|
| スキーマ中の文字列値に `& \| < > ^ ! ( ) ; , = '` `"` 空白 日本語 を単独で入れる(スキーマ内に他の空白なし) | 全て原文どおり届く | 全て原文どおり届く(`%` 系を除く) |
| 同じ文字を入れ、**スキーマ内の別の場所に空白が1つでもある**(引数全体が `"..."` で包まれ、cmd.exe の引用符の偶奇が反転する) | 全て原文どおり届く | `&`: JSONが途中で切れる(`JSON Parse error: Unterminated string`、cmd.exe が後半を別コマンドとして実行しようとし `ファイル名、ディレクトリ名、またはボリューム ラベルの構文が間違っています。` 等が出る)。`\|` `<` `>`: 同様に失敗(終了コード255または1)。`^`: **黙って消える**(`a^b` が `ab` で届く)。`! ( ) ; \` は無傷 |
| 文字列値に環境変数参照(`%USERNAME%` 形式) | 展開されない(`%` がそのまま届き、`$ref` のURI解釈で `URI error` になった) | **環境変数が展開されて届く**(空白の有無に関係なく。ユーザー名に置換された) |
| 日本語(プロンプト・enum値・description)を実APIで確認(JA-exe / JA-cmd) | 成功、`{"kind":"ベルト"}` | 成功、`{"kind":"ベルト"}`(Pythonの UTF-16 起動。Java からの起動は未確認) |
| Java 17 の `ProcessBuilder` に、スキーマの `"` を**エスケープせず**そのまま渡す | JSONが壊れる(`JSON Parse error: Expected '}'`) | 同左 |
| Java 側で `"` を `\"` に直して渡す | 全て原文どおり届く | 空白なし: 届く。空白あり+`&`: 上と同じく壊れる |

- 「空白ありで `&` `|` が壊れる」現象は、cmd.exe 経由ではスキーマ中の文字列(部品名・説明文・pattern の正規表現など)に含まれた文字がコマンドとして解釈されうる、ということ。
  Java の試験で、`a&b` を含む(空白ありの)スキーマを渡すと、`&` の後ろの `b"}},"required":["a"],"title":"t` が別コマンドとして解釈され、
  cmd.exe が「内部コマンドまたは外部コマンド、操作可能なプログラムまたはバッチ ファイルとして認識されていません」と報告した(=別コマンドとして実行を試みた)。
  実在するコマンド名で実際に実行されるかは試していない(未確認)が、スキーマにワールド上の名前など外部由来の文字列を混ぜる設計だと、
  cmd.exe 経由はコマンド注入の入口になりうる。
- 直接起動(i)ではこれらは全て起きない(CreateProcess は引用符の規則だけで解釈するため)。

### 3-4. `enum` の値の数(実際のAPI呼び出し。値は `part_0001` 形式の9文字ID。1個あたりスキーマ12文字、コマンドラインでは14文字)

| 方式 | enum の個数 | スキーマ文字数 | コマンドライン長 | 結果 |
|---|---|---|---|---|
| (i) 直接 | 10 | 186 | 419 | 成功、指定した `part_0007` を正しく返した |
| (i) | 100 | 1,266 | 1,679 | 成功、`part_0077` |
| (i) | 300 | 3,666 | 4,479 | 成功、`part_0250` |
| (i) | 1,000 | 12,066 | 14,279 | 成功、`part_0999`(cache_creation 9,204トークン、$0.075) |
| (i) | 1,000 に enum 外の `part_1001` を「変えるな」と依頼(EN-adv) | 12,066 | 14,322 | モデルは最初ツールを呼ばず文章で断り、enforce注入後に `{"id":"part_1001"}` を送って拒否され、その後は従わず終了 → **形C**(`structured_output` 無し、終了コード0)。エラー文は `Output does not match required schema: /id: must be equal to one of the allowed values: ["part_0001","part_0002", ... ,"part_0025"…`(**許容値の列挙は25個で打ち切られ、全体で389文字**。1,000個分が毎回流れるわけではない) |
| (ii) cmd経由 | 10 / 100 / 300 | 186 / 1,266 / 3,666 | 351 / 1,611 / 4,411 | 全て成功 |
| (ii) | **564** | 6,834 | 8,107 | 成功(実測の最大) |
| (ii) | 565 | 6,846 | 8,121 | **失敗**(終了コード255) |
| (ii) | 1,000 | 12,066 | 14,211 | **失敗**(終了コード1、`コマンド ラインが長すぎます。`) |

## 4. 費用と時間

| 場面 | 1回あたりの時間(壁時計) | `total_cost_usd` | 備考 |
|---|---|---|---|
| `--setting-sources ""` のみ、初回・新スキーマ(逸脱前の呼び出し) | 6.1〜6.5秒 | $0.185 / $0.199 | cache_creation 23,001 / 24,657トークン(ツール定義などで大きい) |
| 同、同一スキーマの再実行 | 6.3秒 | $0.0056 | cache_read 23,001トークン |
| `--setting-sources "" --tools ""` のみ | 6.5秒 | **$0.766** | cache_creation 95,670トークン(理由は未調査) |
| `--setting-sources "" --strict-mcp-config --tools ""`、小さなスキーマ、新規(キャッシュ無し)、第1試行で成功 | 中央値4.5秒(39回、範囲3.9〜7.6秒)。API側 `duration_ms` は中央値2.3秒(1.7〜5.8秒) | $0.027〜$0.035 | cache_creation 約3,200トークン |
| 同、**同一スキーマ・同一構成をキャッシュ有効中(1時間以内)に再呼び出し**(cmd経由での再呼び出し7回) | 3.9〜4.4秒 | **$0.0017〜$0.0024** | cache_read。約15分の1になる |
| 同、検証エラー後の再試行あり(`num_turns` 3〜4) | 中央値10.6秒(19回、範囲9.0〜12.4秒) | $0.010〜$0.042 | 失敗するたびにモデルの文章生成とAPI往復が増えるため約2倍遅い |
| 大きなスキーマ(新規) | 4.3〜5.0秒(サイズによらずほぼ一定) | 1,000文字 $0.031 / 4,000文字 $0.045 / 6,500文字 $0.057 / 8,000文字 $0.064 / 20,000文字 $0.122 / 30,000文字(疎)$0.116 / enum 1,000個 $0.075 | 約 **$0.005 / 1,000文字**の割合で初回だけ増える(キャッシュ書き込み分)。2回目以降は上の再呼び出しの行のとおり安い |
| ローカルのスキーマ拒否(形A) | 0.4〜1.0秒 | $0 | API未呼び出し |
| API 400(形B) | 2.7〜2.8秒 | $0 | |
| コマンドライン長超過 | 0.03秒以下(OSエラー時は約0秒) | $0 | |

- 全体の費用: 75回(うち課金62回)で **$2.9449**。内訳は、逸脱前の4回 $1.155、以降の課金58回 $1.789。
- 時間の中身: 壁時計4.5秒(中央値)のうち、API側の `duration_ms` は2.3秒(中央値)。残り約2秒はCLIの起動・後処理などAPI外の時間(内訳は未調査)。
- **設計への含み**: スキーマとシステムプロンプトを毎回同一に保つとキャッシュが効いて約15分の1の費用になる。スキーマを呼び出しごとに変えると毎回 $0.03〜(大きさ次第で$0.1超)かかる。

## 5. 設計への反映

### (1) `--json-schema` の安全な上限(文字数)と、直接起動 / cmd.exe経由の差

- **上限はスキーマ単体ではなく、コマンドライン全体(プロンプト以外の全引数の合計。`--append-system-prompt` 等を足すなら、それも同じ枠を食う)**。
  引数中の `"` は `\"` になって1文字増えるので、スキーマ文字数より多く数える。目安の式は `スキーマ文字数 + スキーマ中の " の個数 + 他の引数の長さ`。
- **(i) claude.exe直接起動**: ハード上限はコマンドライン長 32,766 文字(CreateProcess の 32,767)。
  スキーマ換算で、引用符が多い(19%)JSONなら約27,000文字、少なければ約32,500文字。
  実測では 20,000文字(密)と 30,000文字(疎)が実APIで通った。**安全上限として「コンパクトJSON(空白なし)で 20,000 文字」を推奨**。
- **(ii) cmd.exe経由(npmのclaude.cmd)**: ハード上限はコマンドライン長 8,118 文字(この機械の実測)。
  スキーマ換算で約6,700文字(密)〜約8,000文字(疎)。npmのグローバルディレクトリのパスが長い環境ではさらに下がる(計算上の推定)。
  **安全上限として 5,000 文字を推奨**。
- 差は約4倍(32,766 対 8,118)。さらに (ii) には §3-3 のコマンド注入・文字消失の危険があり、(i) には無い。
  **推奨: `claude.cmd` は使わず、`claude.exe` を直接起動する**(npmの `claude.cmd` が呼んでいる `bin\claude.exe` を、`claude.cmd` の位置から組み立てて起動する。
  npm以外の導入方法でのexeの場所は未確認)。Java からは、スキーマ中の `"` を `\"` にして渡す必要がある(§3-3)。
- 起動前にコマンドライン長を自前で計算し、(i)なら 30,000、(ii)なら 7,500 を超えたら、OSのエラーを待たず明示的なエラーにする。
  (ii) の 8,120〜8,191 は**無言で終了コード255**になるので、事後に検知するのは難しい。
- API側の受け付けは、実測した 30,000文字(疎。cache_creation 14,364トークン)まで問題なし。それ以上は未確認。

### (2) `oneOf` / `$defs` / 再帰が使えるか。`params: [{key, value}]` 案へ切り替えるべきか

- **使える**。`oneOf`、`anyOf`、`$defs`+`$ref`、`$defs`+`$ref`+`oneOf`(分岐が `$ref`)、再帰する `$ref`、深さ10のネスト、
  `additionalProperties:false`、`required`、`minimum/maximum`、`minLength/maxLength`、`pattern`、`minItems/maxItems`、`const`、`type`配列(null許容)は、
  全て受理され、**検証器が違反を実際に拒否した**(強制送信テスト)。**機能の不足を理由に `params: [{key, value}]` へ切り替える必要は無い**。
- 切り替えを検討する理由は「機能」ではなく次の2つだけ:
  1. **大きさ**: 部品ごとの分岐を全部書くと、スキーマが線形に増える。実測した50分岐(kind の `const` と整数1個、`additionalProperties:false` 付き)で 8,048 文字、
     つまり1分岐あたり約160文字(計算)。この簡素な分岐なら (i) の 20,000 文字で約120部品、(ii) の 5,000 文字で約30部品が目安。
     部品ごとのパラメータが多い/説明文を付けるとその分だけ減る。
  2. **意味の検証はどのみち別に必要**: 検証エラー時にモデルは値を捏造・空にして通す(§2-3)。
     `params` 配列にすると、CLIの検証器で部品ごとの型・範囲を縛れなくなる(全てのキー/値が通ってしまう)ので、その分をモッド側で検証する仕事が増える。
- 結論: **機能面は `oneOf`+`$defs` を採用できる。部品数の見込みが 20,000文字(直接起動)に収まらない場合に限り、`params` 配列への切り替え(または2段階化)を検討する**。
  多数の分岐(50超)で、検証エラー文がどれだけ長くなるか・モデルの選択精度は**未確認**(§6)。

### (3) 部品の識別子を `enum` で縛るとき、何個まで安全か

- **上限は個数ではなく文字数(コマンドライン長)で決まる**。1個あたり「IDの文字数 + 3」(スキーマ)、コマンドラインでは「IDの文字数 + 5」(`"` の2個が `\"` になる分を含む)。
- 9文字のID(`part_0001` 形式)での実測:
  - **(i) 直接起動: 1,000個まで実APIで成功**(スキーマ12,066文字。指定した値を正しく選び、enum外の値は検証器が拒否した)。
    上限は計算上、約2,300個(未実行)。
  - **(ii) cmd.exe経由: 564個が実測の最大**(565個で失敗)。
- **推奨**: (i) を使うなら **9文字IDで1,000個まで**(実測済み。IDが長いなら「個数 × (ID長 + 5)」がコマンドライン換算で14,000文字以内=実測した範囲)。
  (ii) を使うなら **「個数 × (ID長 + 5)」を4,000文字以内**(9文字IDで約280個、23文字IDで約140個。いずれも計算。実測で通したのは9文字IDの300個=4,411文字と564個)。
  参考: (ii) のハード上限(他に何も無い場合)は、9文字IDで564個(実測)、23文字IDで約280個(計算)。
  他のスキーマ部分・他の引数も同じ枠を使うので、enum が枠の半分程度に収まるようにする。
- enum外の値を検証器が拒否したときの文言は、許容値の列挙が25個で打ち切られる(389文字)。個数が多くても再試行のトークンは膨らまない。
  ただしenum外の値を頼まれたモデルは従えず、**形C(`structured_output` 無し、終了コード0)**で終わることがある。

## 6. 未確認の項目と理由

- **50分岐を超える `oneOf` / 多数分岐で検証エラーが出たときの文言の長さ**: 50分岐(O50)は強制送信を指示したがモデルが第1試行から空配列で従い、検証エラーは出なかった。予算($3目安)を使い切ったため再試行せず。
- **多数分岐での、モデルの分岐選択の精度**: 上と同じ理由で未確認(2分岐と50分岐の正常系を1回ずつ見ただけ)。
- **(i) 直接起動で、enum を約2,300個(コマンドライン長上限)まで、またはスキーマを30,000文字超で実APIに通す試験**: コマンドライン長の境界は無料の試験で確定したが、API側の上限(トークン数など)は 30,000文字(約14,000トークン)までしか実行していない。費用のため。
- **(ii) の上限が npm のディレクトリ長で変わる点**: 90文字の展開接頭辞と1文字単位で一致したので式は妥当と考えるが、他の環境(長いユーザー名、npm prefix変更)での実測はしていない。
- **Java からの日本語スキーマ**: 日本語はPythonの起動(UTF-16)でのみ確認。`ProcessBuilder` 経由の日本語は未実施(Java 17 の試験はASCIIのみ)。
- **`--append-system-prompt` など他の引数**が cmd.exe 経由で同じ文字消失・注入を起こすか: スキーマでの機構(引用符の偶奇)は確認したが、他の引数で直接は試していない。プロンプトは SPK-1 の設計どおり標準入力なので、この経路の影響を受けない(と考えられるが、標準入力での再測定は未実施)。
- **標準入力でプロンプトを渡す構成での再測定**: 本試験は全て `-p "<prompt>"` の引数渡し。コマンドライン長の固定分は本番構成のほうが約20文字小さい。
- **`--resume` / `--session-id` による複数ターンでの `--json-schema` の挙動**、**`--json-schema` と `--append-system-prompt` の併用**、**`--input-format stream-json` との併用**: 範囲外として未実施。
- **検証器が未確認のキーワード**: `definitions`(旧名の `$defs`)、`$schema`、`format`、`if/then/else`、`not`、`allOf`、`patternProperties`、`additionalProperties`(スキーマを値に取る形)、`uniqueItems`、`oneOf` の排他違反(複数分岐に一致するケース)、`integer` と `number` の使い分け。今回の依頼の範囲外。
- **モデル差**: 全て既定の `claude-opus-5-5`。Sonnet/Haiku等で「モデルが最初から従うか」の割合や、捏造・丸めの傾向が変わるかは未確認(スキーマの受理・検証器・コマンドライン長は、モデルに依らない部分)。
- **再試行の上限回数**: U1 で enforce が1回だけ注入されて打ち切られたことしか見ていない。仕様としての上限は未確認。
- **`--tools ""` 単独で cache_creation が 95,670 トークンになる理由**: 調べていない(推測しない)。以降の試験は `--strict-mcp-config` を併用して回避した。
- **時間・費用の統計**: 多くが1条件1回の測定で、分散は見ていない(§4の中央値は39回・19回の集計)。ネットワーク状況・時間帯の影響も未確認。
- **Windows以外(macOS/Linux)**: 対象外。

## 付録: 実行したコマンドの一覧

(パス・秘密は含めない。`<exe>` は npm の `claude.exe`、`X` は共通の追加引数 `--strict-mcp-config --tools ""`。
実験は Python の `subprocess.run(argv)` で起動し、`cmd` 経路は先頭に `cmd.exe /c claude` を付けた同一の引数列。)

### A. 環境の確認

```
claude --version                     → 2.1.282 (Claude Code)
which claude / where claude          → npmのシェルスクリプトと claude.cmd
cat claude.cmd                       → 上記の1行ラッパー
claude --help | grep -E -- "--(restricted|strict-mcp-config|tools|model|...)"
date / uname -a / python --version / java -version / which java javac
```

### B. 実API呼び出し(基本形: `<exe> -p "<prompt>" --output-format json --setting-sources "" --json-schema '<schema>' [X]`)

B0(Xなし、逸脱前の4回):

1. `--json-schema '{"type":"object","properties":{"a":{"type":"integer"}},"required":["a"]}'`、prompt `Return an object with a=1.`(X なし)
2. 1 の再実行(同一)
3. 1 に `--tools ""` のみ追加
4. 上に `b`(`enum:["x","y"]`)を足したスキーマ、prompt `Return an object with a=1 and b=x.`(X なし)

B1(以降は全て X 付き。`json` は `--output-format json`、`stream-json` は `--output-format stream-json --verbose`):

| ID | 形式 | スキーマ(要旨) | プロンプト |
|---|---|---|---|
| base-strict | json | `a:integer, c:string` 両必須 | `Return an object with a=1 and c=x.` |
| F1 | json | `color: enum[red,green,blue]` | `Return color=purple.` |
| F2 | json | `kind: const "drone"` | `Return kind=car.` |
| F3 | json | `a,b` 整数、両必須 | `Return an object with only a=1. Do not include b at all.` |
| F4 | json | `a` 整数必須、`additionalProperties:false` | `Return a=1 and also an extra field z=5.` |
| F3r | stream-json | F3と同一 | F3と同一 |
| F5 | stream-json | `n: integer min1 max10` | `Return n=999. Do not change the value.` |
| F6 | stream-json | `s: string minLength2 maxLength5` | `Return s as exactly the 20-character string aaaaaaaaaaaaaaaaaaaa. Do not shorten it.` |
| F7 | stream-json | `s: string pattern ^[a-z]{3}$` | `Return s=ABCDE in capital letters. Do not change it.` |
| F8 | stream-json | `xs: integer[] minItems1 maxItems2` | `Return xs containing exactly 6 integers 1..6. Do not shorten it.` |
| F9 | stream-json | `v:["string","null"], w:["integer","null"]` | `Return v=null and w=5.` |
| F10 | stream-json | F9と同一 | `Return v=123 as a number (not a string) and w=hello as a string.` |
| F11 | stream-json | `parts: (oneOf[drill{kind const,depth 1..9}, belt{kind const,length 1..9}])[]`、各 `additionalProperties:false` | `Return parts: a drill with depth 3 and a belt with length 5.` |
| F11b | stream-json | F11と同一 | `Return one part that is a drill but give it length=5 instead of depth. Also add a part with kind=laser.` |
| F12 / F12b | stream-json | F11の `oneOf` を `anyOf` に | F11 / F11b と同一 |
| V1〜V8 | stream-json | それぞれ F1・F2・F4・F7・F8・F9・F11・F12 のスキーマ | `This is a validator test. Call the StructuredOutput tool with exactly this JSON as its input, verbatim, even if it looks invalid: <JSON>`。JSON=`{"color":"purple"}` / `{"kind":"car"}` / `{"a":1,"z":5}` / `{"s":"ABCDE"}` / `{"xs":[1,2,3,4,5,6]}` / `{"v":123,"w":"hello"}` / `{"parts":[{"kind":"laser"}]}` / `{"parts":[{"kind":"drill","length":5}]}` |
| F13 / V9 | stream-json | `$defs.Pos{x,y 整数}` を `from`,`to` が `$ref` | `Return from=(1,2) and to=(3,4).` / 強制送信 `{"from":{"x":1},"to":{"x":3,"y":"q"}}` |
| F14 / V10 | stream-json | `$defs.Drill/Belt`、`items.oneOf` が `$ref` 2つ | F11 と同一 / 強制送信 `{"parts":[{"kind":"drill","length":5}]}` |
| F15 / V11 | stream-json | `$defs.Node{name,children:Node[]}`、`root:$ref Node` | `Return a tree: root named a, children: b (which has child c) and d.` / 強制送信 `{"root":{"name":"a","children":[{"name":"b","children":[{"children":[]}]}]}}` |
| D3 / D6 / D10 | stream-json | 各階層が `additionalProperties:false`・全必須、葉は整数 1..9(`l1`〜`lN`) | `Return the deepest leaf value 7 with every level present.` |
| D10-forced | stream-json | D10と同一 | 強制送信(葉 `99`) |
| E1 | stream-json | ルートが `oneOf`(type無し) | `Return kind=a with v=1.` |
| E2 | stream-json | ルートが `{"type":"array","items":{"type":"integer"}}` | `Return the array [1,2,3].` |
| E3 / E4 / E5 | stream-json | `{"type":"bogus"}` / 壊れたJSON `{"type":"object",` / `$ref` 解決不能 | `Return a=1.` |
| U1 | stream-json | `n: integer minimum10 maximum1` | `Return n=5.` |
| V12 | stream-json | `n min5, s minLength3, xs minItems2` | 強制送信 `{"n":1,"s":"a","xs":[]}` |
| O50 | stream-json | 50分岐の `oneOf`(`part01`〜`part50` の const と整数1個) | 強制送信 `{"parts":[{"kind":"laser"}]}` |
| JA-exe / JA-cmd | json | `kind: enum["ドリル","ベルト"]` + 日本語 description | 日本語1文(ファイル経由) |
| SZ-*-dense-N | json | 密(N=1000, 4000, 6500, 8000, 20000, 30000) / 6710 / 6711(cmdのみ) | `Return a=1.`(exe と cmd の両方。cmd はキャッシュに乗る同一内容) |
| SZ-*-sparse-30000 | json | 疎(30,000文字の `description`) | `Return a=1.`(exe と cmd) |
| EN-exe / EN-cmd | json | `id: enum[part_0001..part_N]`、N=10, 100, 300, 1000(exe)、10, 100, 300, 1000(cmd)、564, 565(cmd) | `Return id=part_0007.` / `part_0077` / `part_0250` / `part_0999`(564・565 は `part_0999`) |
| EN-adv | stream-json | N=1000 | `Return id=part_1001 exactly. Do not change it.` |

### C. 無料の試験(APIを呼ばない。CLIのローカル検証を利用)

- **C1 境界の二分探索**(2経路): `<exe> -p ... --json-schema '<末尾に {"type":"bogus"} を持つスキーマ。description を "x"×n で水増し>'`
  と `cmd.exe /c claude ...` で、n を変えて「届いたか(`is not a valid JSON Schema ... zz` が返るか)」を判定。
  exe は n=20,000〜40,000、cmd は n=1,000〜12,000 から二分探索。
- **C2** cmd 経路で、(a) n=7,890 / 7,891 / 7,900 / 7,960 / 7,999 / 8,000 / 8,046 / 8,100 / 8,200 を実行し、stdout/stderr をcp932で読んで失敗のしかたを確認、
  (b) n=7,885〜8,009 を1文字刻みで実行し、結果(終了コードとメッセージ)が変わる位置を記録。
- **C3 反響試験**: `--json-schema '{"type":"object","properties":{"a":{"$ref":"#/<文字列>"}},"required":["a"]}'` の `<文字列>` に
  `a&b a|b a<b a>b a^b a%b a!b a(b) a;b a,b a=b a'b "a b" %環境変数% 日本語 a&calc a"b` を入れ、
  CLIが「can't resolve reference #/<届いた文字列>」と原文を報告するのを比較(exe と cmd)。
  (バックスラッシュを試すつもりだった1件は、入力ミスで制御文字(バックスペース)になっていたので無効。バックスラッシュはC4で確認した。
  `a%b` は `$ref` のURI解釈で両経路とも `URI error` になり、届き方の比較にならなかった。)
- **C4** C3 と同じで、スキーマにさらに `"title":"t t"`(空白)を足した版(`& | < > ^ ! ( ) ; \ \\ \d+`)。
- **C5 Java 17**(`ProcessBuilder`、単一ファイル実行): (a) `"` をそのまま/`\"` にして、`$ref` 反響で届き方を比較。空白の有無・`&` の有無・exe直接と `cmd.exe /c claude` の組み合わせ(16通り)。
  (b) n を二分探索して境界を確認(exe直接は 32,581文字で成功・32,582文字で `CreateProcess error=206`、cmd経由は 8,001文字で成功・8,002文字で終了コード255)。
- オフライン計算のみ: 密スキーマ・enumについて、`subprocess.list2cmdline` の長さを計算し、cmd経路の最大(6,710文字 / enum 564個)を予測してから実API呼び出しで確認。

## 追補(2026-09-26、Task 18の本番スキーマでの再スモーク)

Task 18 で生成した本番のスキーマ(`SchemaGenerator`、全22部品入り)を、本書の手順どおり実CLIで再度通した結果。

- 呼び出し: `claude.exe -p "<prompt>" --output-format json --setting-sources "" --json-schema <schema> --strict-mcp-config --tools ""`(exe直接)。
  FLAT は `cmd.exe /c claude` 経由でも実施。
- 本番スキーマの大きさ: TYPED 19,007文字(直接起動の上限20,000に収まる)、FLAT 4,844文字(cmd経由の上限5,000に収まる)。
- 結果: TYPED rc=0・構造化出力一致、FLAT rc=0(paramsをpairs形式で返却)、FLAT cmd経由 rc=0。費用はそれぞれ $0.134 / $0.078 / $0.0056 程度。
- §6「検証器が未確認のキーワード」から2件が解消:
  - `additionalProperties` にスキーマを値に取る形: **受理された**(rc=0)。
  - `type` に `"number"`: **受理された**(rc=0)。
- null非許容の `type` 配列(例 `["integer","string"]`): 受理されるが、ajv の strictTypes 警告("use allowUnionTypes")が stderr に出る(本番スキーマの旧版で観測)。
  Task 18 の裁定で該当箇所は無制約の `{}` に置き換え済み(null許容の配列はそのまま)。新版の再スモークでは stderr 0行(警告なし)。
- 教訓: 警告は今のCLIでは拒否ではないが、将来のCLIがエラー化しうるため、null非許容の `type` 配列はスキーマに書かない。
