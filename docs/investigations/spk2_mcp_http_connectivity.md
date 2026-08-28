# SPK-2: MCP接続方式検証(ループバックHTTP)

AIチャット支援機能のWave 0スパイク。「クライアントJVM内に立てたツール
サーバーに、claude -pが実際に接続してツールを呼べるか」を検証した。

## 検証方法

1. `@modelcontextprotocol/sdk`(公式SDK、Node.js)で、`get_block_snapshot`
   という1つのダミーツール(固定の偽ブロック情報を返すだけ)を持つ
   ローカルHTTP MCPサーバーを実装(`StreamableHTTPServerTransport`、
   `http://127.0.0.1:8765/mcp`)。呼ばれたら`calls.log`に記録するようにして、
   「モデルの自己申告」ではなく**サーバー側の実測ログ**で裏取りできるようにした。
2. `--mcp-config`用のJSON(`{"mcpServers":{"micradrone-spike":{"type":"http",
   "url":"http://127.0.0.1:8765/mcp"}}}`)を用意。
3. SPK-1で確定した安全モードのフラグ(`--setting-sources "" --restricted
   --strict-mcp-config --tools ""`)を付けたまま`claude -p`にこのツールを
   使うよう指示し、`calls.log`にサーバー側の呼び出しが実際に記録されるかを確認。

## 結果: 成功(サーバー側ログで実証済み)

- 初回は「`mcp__micradrone-spike__get_block_snapshot`の権限が無いので
  実行をブロックした」という応答になった。**MCPツールは`--tools`や
  `--restricted`の管轄外で、個別に許可が要る**ことが分かった。
- `--allowedTools "mcp__micradrone-spike__get_block_snapshot"` を追加したところ、
  安全モードの他のフラグ(`--restricted --strict-mcp-config --tools ""`)は
  そのままに、このツールだけが実際に呼ばれた。`calls.log`に
  `CALLED get_block_snapshot(10,64,-3)-(10,64,-3)` が記録され、応答テキストにも
  サーバーが返した固定文言(`SPIKE-PROOF: ...`)がそのまま返ってきたことを確認。

## 設計への反映

- **MCPツール名の形式は `mcp__<mcpServerNameで指定した名前>__<ツール名>`**。
  `BlockSnapshotToolServer`のmcpServers名を固定(例: `micradrone`)にし、
  ツール名`get_block_snapshot`と合わせて `--allowedTools
  "mcp__micradrone__get_block_snapshot"` を安全モード・dangerousモードの
  **両方に常時付与する**(要件の「常に有効」をこれで実現する)。
- `--mcp-config`はHTTPのURLを指すJSONファイル(または同等のインラインJSON文字列)
  で足りる。Javaプロセス起動時に一時ファイルとして書き出すか、`--mcp-config`が
  JSON文字列も受け付けるかは実装時に再確認するが、ファイル書き出しなら
  確実に動くことは確認済み。
- HTTPサーバーは公式SDKと同じ「Streamable HTTP」方式なら確実に繋がることを
  実証した。ただしT-7はJavaでの実装が必要なため、`com.sun.net.httpserver.HttpServer`
  (JDK標準)等でMCPのJSON-RPCハンドシェイク(initialize→tools/list→tools/call)
  を実装する必要がある。**プロトコルの往復自体は今回の検証で「claude -p側は
  正しく繋がる」ことが確定したので、残るリスクはJava側でMCPプロトコルを
  正しく実装できるかという実装リスクのみに縮小できた**(接続可否という
  最大の不確実性は解消)。

## 未検証(T-7/T-7bで確認すべき点)

- ダミーサーバーは固定値を返すのみ。実際のブロック読み取り(メインスレッド
  委譲、未ロード領域の扱い)はT-7bで別途検証する。
- danger時の`--dangerously-skip-permissions`との組み合わせ(この場合は
  `--allowedTools`を明示しなくても呼べるはずだが未実測、T-9で確認)。
