# SPK-S6: カスタムペイロードの実際の上限

P4 の分割ペイロード(計画は30KB以下のチャンクに分けて送る)の根拠。**ソースの調査は済み(2026-09-30)。実機の測定は P4 Task 32 の台本で自動に行う**(今は「未測定」)。出典はすべて `build/moddev/artifacts/neoforge-21.1.238-sources.jar` の中のファイル。コードは足していない。

1. **確認済み(ソース)**:
   - `net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket.java`: `private static final int MAX_PAYLOAD_SIZE = 32767;`。ただし使われるのは`DiscardedPayload.codec(id, 32767)`(**登録されていない**ペイロードを読み捨てるとき)だけ。登録済みのペイロードの大きさは、このコーデックでは制限されない。
   - `net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java`: `MAX_PAYLOAD_SIZE = 1048576`(同じく読み捨て用)。
   - `net/neoforged/neoforge/network/filters/GenericPacketSplitter.java`: エンコード後のパケットが上限を超えると`SplitPacketPayload`に分割して送る。上限は`CompressionDecoder.MAXIMUM_COMPRESSED_LENGTH = 2097152`(**圧縮の処理が無い接続**。シングルプレイのメモリ内接続のほか、`network-compression-threshold=-1`のTCPも当たる)と`MAXIMUM_UNCOMPRESSED_LENGTH = 8388608`(圧縮器がある接続)。分割用のチャンネルは`.optional()`で登録され、相手がそのチャンネルを持つときだけ使う(`isRemoteCompatible`)。
   - `net/minecraft/network/FriendlyByteBuf.java`: `MAX_STRING_LENGTH = 32767`(文字数)。`ByteBufCodecs.STRING_UTF8`で文字列を送ると、この文字数を超えた時点で読み取りが失敗する。**だから、計画の本文は文字列ではなく`ByteBufCodecs.byteArray(上限)`で送る**。
2. **設計の理解との違い**: 設計(`04` F-3)は「サーバー宛ての通常のカスタムペイロードは約32KBが上限と理解している」と書いていた。ソース上は、32,767は**登録されていない**ペイロードの読み捨て用の値で、登録済みのペイロードはNeoForgeが分割して送る。ただし、相手が分割チャンネルを持たない場合や、1つのパケットの上限(2MiB/8MiB)の実際の振る舞いは、実機で測るまで確定しない。
3. **P4の決定(測定の前でも崩れない側)**: 計画は**30KB(`30 * 1024`バイト)以下のチャンク**に分けて送る(設計どおり)。30KBは、どの上限(32,767・2MiB・8MiB)よりも小さいので、測定の結果がどうであっても動く。全体の上限は2MB(`2 * 1024 * 1024`)で、チャンクに分けるので単一パケットの上限には触れない。**測定の結果で変わりうるのはチャンクの大きさの最適値だけで、正しさは変わらない**。
4. **自動測定の手順(Task 32のシナリオ`s6-payload-limits`で実行)**: devkitの測定用ペイロード`micradrone_devkit:probe`(Task 18。`ByteBufCodecs.byteArray(16 * 1024 * 1024)`。micradrone本体の検査を通らない)を、devkitのクライアントAPI`POST /spike/send-probe {"bytes": N}`で送り、サーバー側API`POST /spike/probe-log`で受け取った大きさを読む。Nは16,384・30,720・32,767・32,768・65,536・1,048,576・2,097,151・2,097,152・2,097,153・8,388,608。**予想**: 圧縮のあるTCPで、圧縮の効かない中身を2〜8MiB送ると、分割の上限(非圧縮の8MiB)より先に、枠の上限2,097,151バイト(`Varint21LengthFieldPrepender.java` 18〜22行・`Varint21FrameDecoder.java` 15〜42行)に当たって切断される見込み。台本は切断を「失敗」ではなく測定の結果として記録する(P4レビューD-5)。(a)シングルプレイ(メモリ内接続)と(b)専用サーバー(TCP)の両方で行い、「受信できた/切断された/ログの例外」を`run-evidence/p4/<runId>/s6-payload-limits.json`に記録し、この文書の5節に追記する。切断が起きたら、台本はクライアントを再接続させて次の大きさへ進む(ループしない。3回続けて接続できなければ打ち切りを記録)。
5. **結果**(Task 32で追記。今は「未測定」と書いておく)。
