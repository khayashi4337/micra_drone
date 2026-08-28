package io.github.khayashi4337.micradrone.drone;

/**
 * Static content for the command reference, split into three scrolls (COMMANDS / ADVANCED /
 * EDITOR_AND_SCROLLS) so each stays comfortably under {@link DroneControllerBlockEntity#MAX_SCRIPT_CHARS}
 * with room to grow independently, instead of one scroll creeping toward the limit. Obtainable
 * in-game as help scrolls from the enchanting table (see {@link SampleCatalog}) - it lives in this
 * Minecraft-free package (moved from {@code client}) because the server writes it into scroll
 * items. Each constant's leading "#" line makes {@link ScriptFileStore#describeScript} produce a
 * real description wherever the scroll is listed.
 */
public final class CommandsHelpDoc {

    /** Every command a script can call, plus the language's own syntax - what you need to write any script. */
    public static final String COMMANDS = """
            # コマンド一覧(1/3): 基本コマンドと文法のリファレンス(実行するスクリプトではない)
            === MicraDrone スクリプト コマンド一覧 (1/3: 基本コマンドと文法) ===

            ■ ドローンを動かす（ワールドを変える。実行に少し時間がかかる）
            move("north" | "south" | "east" | "west")
                指定した方向へ1マス移動する。戻り値: 移動できたら true、境界の外などで失敗したら false。

            till()
                今いるマスの地面を耕して耕地にする。戻り値: 成功したら true。

            plant("wheat")
                耕地に小麦を植える。戻り値: 成功したら true。

            plant("carrot")
                耕地にニンジンを植える。ショップでcarrotをアンロックするまでは
                常にfalseになる（アンロックの方法はヘルプ(2/3)の「アンロックショップ」参照）。

            plant("pumpkin")
                耕地にカボチャの苗（つる）を植える。育ちきると本家vanillaの
                仕組みでとなりの空いたマスに実(カボチャ本体)が生える
                （どのマスに生えるかはランダムなので、苗の周りは空けておく
                といい）。harvest()は実が生えたマスで呼ぶ必要がある
                （苗のマスでは常に失敗する）。実る瞬間、約20%の確率で
                「腐ったかぼちゃ」になり、harvest()しても何も得られない
                （本家 The Farmer Was Replaced と同じ仕様）。腐っているかは
                is_rotten()で調べられる。腐ったマスにそのままplant()すると
                上書きして植え直せる（harvestは不要）。ショップでpumpkinを
                アンロックするまでは常にfalseになる。

            harvest()
                育ちきった作物を収穫する。腐ったかぼちゃの場合は成功しても
                ポイントは入らない。戻り値: 成功したら true。

            do_a_flip()
                ドローンが宙返りを1回する。畑にもポイントにも一切影響しない、
                完全にお楽しみのコマンド（本家 The Farmer Was Replaced にも
                あるもの）。スクリプトが本当に動いているかを、畑を荒らさずに
                確かめたいときにも使える。戻り値は無し（None）。

            ■ 状態を調べる（ワールドは変えない。すぐ結果が返る）
            can_harvest()
                今いるマスの作物が収穫できる状態かを調べる。戻り値: true/false。

            is_rotten()
                今いるマスが「腐ったかぼちゃ」かどうかを調べる。戻り値: true/false。
                効率よくポイントを稼ぐには、収穫前にこれでチェックして
                外れを避けるのがコツ。

            get_pos_x()
            get_pos_y()
                ドローンの現在のグリッド座標（0始まり）。

            get_world_size()
                畑の一辺の長さ（マス数）。コントローラとコーナーマーカーの置き方で変わる。

            get_points()
                このプロットが今まで稼いだ資源ポイントの合計（全作物の合計）。

            get_points("wheat")
                指定した作物1種類だけの資源ポイント。

            ■ まわりのMinecraftの世界を見る（ワールドは変えない。すぐ結果が返る）
            ブロック名・バイオーム名はvanillaのものなら "dirt" のように短い名前で
            返る。MODが追加したものだけ "micradrone:rotten_pumpkin" のように
            前に名前空間が付く。

            get_ground()
                ドローンが今いるマスの「地面」のブロック名。
                例: "farmland"（耕地）, "dirt"（土）, "grass_block"（草）,
                "sand"（砂）, "stone"（石）, "water"（水）。
                till()する前に地面が何かを調べれば、どんな場所でも通用する
                スクリプトが書ける。

            get_block_above()
                地面の上、ドローンと同じマスにあるブロック名。作物がある
                ならその名前（"wheat", "carrots", "pumpkin_stem", "pumpkin"
                など）、何も無ければ "air"。

            get_time()
                ワールドの1日の中での時刻（tick、0〜23999）。
                0=日の出、6000=正午、12000=日の入り、18000=真夜中。

            get_weather()
                今の天気。"clear"（晴れ）/ "rain"（雨）/ "thunder"（雷雨）。
                雷雨は雨も降っているが、その場合は "thunder" が返る。

            get_biome()
                今いるマスのバイオーム名。例: "plains", "desert", "jungle",
                "snowy_taiga"。

            get_light()
                今いるマスの明るさ（0〜15）。vanillaの作物は9以上ないと
                育たない。夜や天気でも下がるので、松明が要るかの判断に使える。

            get_plot_id()
                このプロットのコーナーマーカーのID。金床で別名を付けていれば
                その名前、付けていなければ自動採番された短いIDが返る。
                マーカーが見つからない場合は空文字列。複数のプロットを
                持っているときにスクリプトから見分けるのに使える。

            ■ ログに出力する
            print(値)
                コントローラのScripts画面のログ欄に1行追記する。数値・文字列・真偽値を渡せる。
                注意: 文字列と数値を + で連結することはできない
                (例: "points: " + get_points() はエラーになる。分けてprintする)。

            ■ 文法（インデント方式、Python風）
            - コメントは # から行末まで
            - 変数への代入: x = 1
            - 条件分岐: if 条件: / elif 条件: / else:
            - 繰り返し: while 条件:
            - 繰り返し（回数指定）: for i in range(5):
            - 演算子: + - * / %、比較 == != < > <= >=、論理 and or not
            - インデントは半角スペースのみ（タブは使えない）

            ■ 使用例
            till()
            plant("wheat")
            for i in range(4):
                move("east")
                till()
                plant("wheat")

            ■ 使用例（まわりを見て判断する）
            ground = get_ground()
            if ground == "farmland":
                plant("wheat")
            elif ground == "dirt":
                till()
                plant("wheat")
            else:
                print("ここには植えられない")
                print(ground)
            """;

    /** Shop economics, giant-pumpkin fusion, and the collection types (list/dict/set) - past the basics. */
    public static final String ADVANCED = """
            # コマンド一覧(2/3): アンロックショップ・かぼちゃ融合・コレクション型のリファレンス(実行するスクリプトではない)
            === MicraDrone スクリプト コマンド一覧 (2/3: ショップ・かぼちゃ融合・コレクション型) ===

            ■ アンロックショップ
            コーナーマーカーのブロックを右クリックするか、IDE画面右上の
            Shopボタンから専用のショップ画面が開き、稼いだポイントを
            使って新しい作物を解放できる。今のところcarrot（wheat 20）・
            pumpkin（wheat 30 + carrot 15）が購入可能。未購入の作物は
            plant()が常に失敗する。

            ■ かぼちゃの巨大化融合
            耕作エリア内で同じ大きさの正方形(2x2以上)がすべて同時に実った状態に
            なると、自動的に見た目の違う「巨大かぼちゃ」ブロックに変わる。
            その中のどこか1マスでharvest()を呼ぶと、正方形全体をまとめて収穫
            したことになり、一辺nマスなら n×n×n ポイント(n=6以上は n×n×6)を
            まとめてpumpkinに加算する。普通に1マスずつ収穫するより効率がいい。

            ■ まとめて入れ物に入れる（リスト・辞書・集合）
            値を1つずつ変数に入れる代わりに、まとめて持てる入れ物が3種類ある。

            リスト [ ]  ... 順番に並べて持つ。同じ値が何度入ってもよい。
                items = [1, 2, 3]
                print(items[0])        # 0番目から数える → 1
                items[1] = 99          # 書き換え
                items.append(4)        # 末尾に足す
                items.pop()            # 末尾を取り出して返す
                items.remove(99)       # 値を指定して1つ消す
                items.clear()          # 全部消す

            辞書 { }  ... 「キー」で引く。キーは重複しない。
                costs = {"wheat": 20, "carrot": 15}
                print(costs["wheat"])  # → 20
                costs["pumpkin"] = 30  # 追加・上書き
                costs.keys()           # キーの一覧（リスト）
                costs.values()         # 値の一覧（リスト）
                costs.get("nope")      # 無ければNone（[ ]と違って止まらない）
                costs.remove("wheat")  # 消して、その値を返す
                { } だけ書くと空の辞書になる。

            集合 { , }  ... 重複しない値の集まり。順番は入れた順。
                seen = set()           # 空の集合はset()で作る
                seen.add("a")
                seen.add("a")          # 2回目は増えない
                print(len(seen))       # → 1
                seen.remove("a")
                seen.clear()

            ■ 入れ物を扱う関数
            len(入れ物)
                要素の数。文字列なら文字数。

            入れ物の中身を1つずつ見る:
                for x in items:        # リストなら要素、辞書ならキー、
                    print(x)           # 文字列なら1文字ずつ

            値が入っているかを調べる:
                if "wheat" in costs:   # リスト・集合・辞書(キー)・文字列に使える
                    print("ある")
                if not 5 in items:
                    print("ない")

            min(...) / max(...)
                最小・最大。`max(1, 2, 3)` のように並べても、
                `max(items)` のように入れ物を渡してもよい。

            abs(数)
                絶対値。abs(-3) は 3。

            random()
                0以上1未満の乱数。

            str(値)
                値を文字列にする。数値と文字列は + でつなげないが、
                str()を通せばつなげられる。
                print(str(5) + "個")

            list(入れ物) / set(入れ物) / dict()
                入れ物の種類を変える。list({1,1,2}) は [1, 2]。
                引数なしで呼ぶと空のものができる。

            ■ 入れ物は「参照」で入る（注意点）
            リストを別のリストに入れると、中身がコピーされるのではなく
            同じものを指す。片方を書き換えるともう片方も変わる。
                inner = [0, 0]
                pair = [inner, inner]
                pair[0][1] = 7
                print(pair[1][1])      # → 7（別物のつもりでも同じもの）
            別々にしたいなら、[[0,0], [0,0]] のように書くか、
            ループの中で毎回新しく作ること。
            """;

    /** How to use the IDE and the script-scroll item itself - tooling, not the language. */
    public static final String EDITOR_AND_SCROLLS = """
            # コマンド一覧(3/3): IDEと巻物の使い方のリファレンス(実行するスクリプトではない)
            === MicraDrone スクリプト コマンド一覧 (3/3: IDEと巻物の使い方) ===

            ■ エディタ（IDE）の使い方
            コントローラを右クリックすると開く画面。左半分がスクリプトを書く
            エディタ、右半分は畑を真上から見たカメラ映像になっている。

            - 色分け: キーワード（if / while など）と記号はピンク、文字列は
              黄色、数値と True / False / None は紫、コメントは灰色、
              ドローンのコマンド（harvest など）は水色、変数名は白で表示される。
              コマンド名を打ち間違えると水色ではなく緑になるので、タイプミスに
              その場で気づける。
            - 入力補完: コマンド名を途中まで打つと候補の一覧が出る。上下キーで
              選び、Tab か Enter で確定、Esc で閉じる。マウスでクリックしても
              選べる。カーソルがスクリプトのどこにあっても使える。
            - 実行: エディタ上の帯にある緑の塗りつぶし三角が実行、その右にある
              枠だけの三角が1文ずつのステップ実行。実行するのは「保存済み」の
              内容なので、書き換えた直後に動かすなら下段の Save & Run を使う。
              何も動いていないときにステップ実行を押すと、最初の1文の手前で
              止まった状態で走り始める。止まっている間に実行（緑の三角）を
              押すと、そこから続きを動かせる。
            - デバッグ: 行番号の左側をクリックするとブレークポイント（赤い印）を
              置ける。実行がその行に来ると止まり、今から実行する行が黄色く光る。
              下段の Pause / Resume・Step Out（今のループを抜けるまで進む）・
              Stop で操作する。行番号は保存済みの内容に対応しているので、
              デバッグするときは先に保存しておくこと。
            - 名前の変更: 帯に出ているスクリプト名をダブルクリックすると、その場で
              書き換えられる。Enter で確定、Esc で取り消し。金床で巻物の名前を
              変えるのと結果は同じ。

            ■ スクリプトの巻物について
            スクリプトは「スクリプトの巻物」アイテムに書く。空の巻物は
            紙3枚+羽根+インク袋でクラフトでき、何も狙わずに右クリックすると
            本と羽ペンと同じ画面で直接書ける。
            - コントローラを右クリックするとIDE画面が開く（最後に選んだ
              スクリプトが編集対象になる）。IDE右側のListボタンで、
              周囲を映すカメラ表示とスクリプト一覧を切り替えられる。
              一覧から1本選ぶとそれが実行対象になり、自動で一覧が閉じる。
            - コントローラ自身も1本のスクリプト（一覧の先頭「Controller
              script」）を持っていて、置いたばかりのコントローラでは最初から
              それが編集対象。巻物が無くてもそのまま書いてSave & Runできる。
            - コントローラの隣にレバーを置くと、ON=選択中のスクリプトを実行、
              OFF=停止できる。
            - コントローラからコーナーマーカーの方向（東西・南北それぞれの軸上、
              コーナーマーカーと同じ距離である必要はない）にチェスト・
              シュルカーボックス・彫刻入りの本棚などの容器を置くと、その中の
              巻物がスクリプト一覧に並ぶ（スクリプトライブラリ）。自分の
              インベントリに持っている巻物も一覧に並ぶ（自分だけに見える）。
            - 空の巻物がライブラリやインベントリにあると、一覧に新規スクリプト
              として現れ、選ぶとそのまま書き込みを始められる。
            - 巻物は金床で名前を変えられ、一覧にはその名前で表示される。
              一覧の下には、巻物の先頭に書いた # コメントが説明として出る。
            - 空（未記入）の巻物をエンチャントテーブルに向かって使うと、
              ラピスラズリと引き換えにサンプルスクリプトやこのヘルプを
              書き込んでもらえる。周りに本棚を置くほど選べる候補が増える。
              テーブル周りの本棚位置にチェスト等を置いて書き込み済み巻物を
              入れておくと、それも複製候補としてラピス1個で選べる。

            ■ AIチャット（Chatタブ）
            IDE右側のChatタブで、プレイヤー自身のマシンにログイン済みの
            claude CLIに直接質問できる（APIキー不要、追加課金なし）。
            現在のスクリプト・コマンドリファレンス・直前の実行エラーを
            自動で添えて送る。回答に```コードブロックが含まれていれば、
            Insertボタンでそのままエディタに反映できる。
            - 既定では安全モード: ファイル編集・コマンド実行等のツールを
              一切使えない。Dangerトグルで、プレイヤー自身の端末でclaudeを
              直接叩くのと同じフル権限に切り替えられる（Chat画面を開いている
              間だけ有効）。
            - 会話履歴はコントローラごとにローカル保存され、次にChatを
              開くと自動で再開する（他プレイヤーとは共有されない）。
              Compactボタンで会話を要約し、新しい会話として仕切り直せる。
            - コンパス+棒でクラフトできる「Region Pointer」を持ってブロックを
              左クリック（始点）→右クリック（終点）すると、Chatを開いた
              瞬間にその範囲の座標が入力欄へ自動で入る。AIが範囲内の
              ブロックの中身を知りたいときは、こちらから教えなくても
              AI自身がツール経由で確認できる。
            """;

    private CommandsHelpDoc() {
    }
}
