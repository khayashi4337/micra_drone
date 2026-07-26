package io.github.khayashi4337.micradrone.drone;

/**
 * Static content for the command reference. Obtainable in-game as a help scroll from the
 * enchanting table (see {@link SampleCatalog}) - it lives in this Minecraft-free package (moved
 * from {@code client}) because the server writes it into scroll items. The leading "#" line makes
 * {@link ScriptFileStore#describeScript} produce a real description wherever the scroll is listed.
 */
public final class CommandsHelpDoc {
    public static final String CONTENT = """
            # コマンド一覧と文法のリファレンス(実行するスクリプトではない)
            === MicraDrone スクリプト コマンド一覧 ===

            ■ ドローンを動かす（ワールドを変える。実行に少し時間がかかる）
            move("north" | "south" | "east" | "west")
                指定した方向へ1マス移動する。戻り値: 移動できたら true、境界の外などで失敗したら false。

            till()
                今いるマスの地面を耕して耕地にする。戻り値: 成功したら true。

            plant("wheat")
                耕地に小麦を植える。戻り値: 成功したら true。

            plant("carrot")
                耕地にニンジンを植える。ショップでcarrotをアンロックするまでは
                常にfalseになる（アンロックの方法は下の「アンロックショップ」参照）。

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

            ■ スクリプトの巻物について
            スクリプトは「スクリプトの巻物」アイテムに書く。空の巻物は
            紙3枚+羽根+インク袋でクラフトでき、何も狙わずに右クリックすると
            本と羽ペンと同じ画面で直接書ける。
            - コントローラを右クリックするとIDE画面が開く（最後に選んだ
              スクリプトが編集対象になる）。IDE右側のListボタンで、
              周囲を映すカメラ表示とスクリプト一覧を切り替えられる。
              一覧から1本選ぶとそれが実行対象になり、自動で一覧が閉じる。
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
            """;

    private CommandsHelpDoc() {
    }
}
