# SPK-1: claude -p 挙動検証

AIチャット支援機能(ドローンAI相談窓口)のWave 0スパイク。検証環境:
Claude Code 2.1.250、Windows、林さんのローカルマシン(Claude Pro/Maxサブスク)。
すべて実測(`claude -p ... --output-format json` を実行し、返ってきたJSONを確認)。

## 1. セッション継続はboolean(--continue)ではなくUUID(--session-id/--resume)で行う

- `--session-id <uuid>` で自分で発行したUUIDを渡して会話を開始できる。
- 次回以降は `--resume <uuid>` で同じ会話を再開できる。実際に2ターン目で
  1ターン目の内容を正しく覚えていることを確認済み(「さっき何て返信させた?」
  に正しく "OK1" と回答)。
- `--continue` は「カレントディレクトリの最新の会話」を指すだけで、
  コントローラ別に会話を分離する用途には使えない(設計時のCodexレビュー
  指摘の通り)。
- **設計への反映**: `ChatSession.cliSessionId` はUUID文字列として自前で
  発行し、初回は `--session-id`、以降は `--resume` を使う。CLIの出力を
  パースしてIDを抽出する必要は無い(`--output-format json` の
  `session_id` フィールドで検証用に照合はできるが、正本は自前発行UUID)。

## 2. `--autocompact` は閾値設定であり、手動での即時要約コマンドではない

- ヘルプ上、compact関連のフラグは `--autocompact <auto|tokens>` のみ。
  「今すぐ要約して履歴を縮める」ための単発コマンドは無い。
- **設計への反映**: T-4bの「compact機構」は、CLI側の自動compactに頼らず、
  「これまでの会話を要約してください」という明示プロンプトを送り、
  その要約結果でローカルのChatSession.messagesを置き換える、という
  アプリ側実装が必要(CLIの機能ではなく、こちら側のロジック)。

## 3. コストの実測値と、システムプロンプトの肥大化問題(重要な追加発見)

| 呼び出し | cache_creation_input_tokens | 概算コスト(USD) |
|---|---|---|
| デフォルト設定(林さんのグローバルCLAUDE.md込み)、初回 | 50,997 | $0.204 |
| 同一セッションを--resumeで継続、2ターン目 | 73(残りはcache_read) | $0.011 |
| `--setting-sources ""` で初回 | 8,298 | $0.091 |

デフォルト設定では、`claude -p` はプロジェクトと無関係な**林さんの
グローバルCLAUDE.md一式(rules_git.md等を含む、数万トークン規模)を
毎回読み込んでしまう**。これはドローンのスクリプト相談とは無関係な内容で、
コントローラごとに新しい会話を始めるたびに約$0.20相当のコストが乗る計算になる。

`--setting-sources ""` を渡すとユーザー/プロジェクト/ローカルのCLAUDE.md
読み込みを抑制でき、初回コストを$0.204→$0.091まで下げられる(残りの
約8,300トークンはClaude Code自身のベースシステムプロンプト)。

`--bare` はさらに軽量化できるが、ヘルプ上「Anthropic auth is strictly
ANTHROPIC_API_KEY or apiKeyHelper via --settings (OAuth and keychain are
never read)」と明記されており、**--bareはOAuth/サブスク認証と非互換**
(APIキーが必須になってしまう)。よって`--bare`は不採用、`--setting-sources ""`
を採用する。

- **設計への反映**: `ClaudeCliBridge`は常に`--setting-sources ""`を付与する。
  ChatContextBuilder/T-6のプロンプトに、Minecraft/ドローンAPIの必要最小限の
  説明を`--append-system-prompt`等で明示的に注入する方針とする(林さんの
  グローバル設定に頼らない)。

## 4. 安全モードのフラグ組み合わせ(最重要、実機側で副作用まで確認済み)

**当初の設計(`--restricted`のみ)は不十分だった。** `--restricted`単独では
Bash/PowerShell等の「コード実行系」ツールは塞がれるが、Read/Write/Edit/Glob/Grep
や、**林さん個人のMCPサーバー(Figma/Slack/Gmail/Drive/Notion等)は塞がれない**
ことを自己申告ベースで確認(自己申告は信頼できないため、次に副作用で検証)。

`--tools "" --restricted --strict-mcp-config` の組み合わせで、実際に
「Writeツールでファイルを作れ」と指示したところ、モデルは「作った」という
文面(ツール呼び出し風の疑似XML込み)を返したが、**実際にはファイルは
作成されなかった**(作業ディレクトリを直接確認して実証)。つまりこの組み合わせは
本当にツール実行をブロックしている。

一方で分かった注意点: **ツールが使えない状態でツール使用を指示すると、
モデルはツール呼び出し風のテキストや「実行した想定の出力」を生成することがある
(実際には何も起きていない)**。ChatTabPanel側は応答テキストの文面を実行結果の
証拠として信用してはならない(もっとも本機能は元々「```コードブロックを
Insertボタンでエディタに反映する」設計であり、応答テキスト自体をコマンドとして
実行する経路は無いため、実害は無い。念のため記録)。

- **設計への反映(DangerModeState/T-3)**:
  - 安全(既定): `--setting-sources "" --restricted --strict-mcp-config --tools ""`
    + 自前の`--mcp-config`(get_block_snapshotのみ)
  - dangerous: 上記を外し、`--dangerously-skip-permissions`を付与
    (林さんの明示要望通り「ローカル端末でclaudeを直接叩くのと同じ」、
    個人のMCPサーバーも含めてフル権限になる)

## 5. プロンプト渡しはstdin経由にする(CLI引数のパース事故を回避)

`--tools ""` の直後に素の文字列引数としてプロンプトを渡すと、可変長オプション
`--tools <tools...>` がプロンプト文字列まで飲み込んでしまい
`Input must be provided either through stdin or as a prompt argument`
エラーになることを実測。**プロンプトは常に標準入力経由で渡す**ことで
この事故を回避できる(ProcessBuilderからの起動でも同様にstdinへ書き込む設計にする)。

## 未検証・後続タスクで確認すべき点

- `--dangerously-skip-permissions`自体の直接検証は今回省略(Claude Code本体の
  基本機能でありコスト対効果が低いため)。T-5実装時、実機で一度だけ動作確認する。
- `--mcp-config`経由でのカスタムツール呼び出し(get_block_snapshot)は
  SPK-2で別途検証する。
