package io.github.khayashi4337.micradrone.client;

/** Static content for the commands.txt reference doc written by the DroneScreen "Help" button. */
public final class CommandsHelpDoc {
    public static final String CONTENT = """
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
                常にfalseになる（DroneScreenの「Shop」タブから購入できる）。

            harvest()
                育ちきった作物を収穫する。戻り値: 成功したら true。

            ■ 状態を調べる（ワールドは変えない。すぐ結果が返る）
            can_harvest()
                今いるマスの作物が収穫できる状態かを調べる。戻り値: true/false。

            get_pos_x()
            get_pos_y()
                ドローンの現在のグリッド座標（0始まり）。

            get_world_size()
                畑の一辺の長さ（マス数）。コントローラとコーナーマーカーの置き方で変わる。

            get_points()
                このプロットが今まで稼いだ資源ポイントの合計（全作物の合計）。

            get_points("wheat")
                指定した作物1種類だけの資源ポイント。

            ■ アンロックショップ
            DroneScreen上部の「Shop」タブで、稼いだポイントを使って新しい作物を
            解放できる。今のところ carrot（wheat 20）・pumpkin（wheat 30 +
            carrot 15）が購入可能。未購入の作物はplant()が常に失敗する。

            ■ ログに出力する
            print(値)
                このGUIのログ欄に1行追記する。数値・文字列・真偽値を渡せる。
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

            ■ スクリプトファイルについて
            スクリプトはワールドごとに <world>/micradrone/scripts/<コントローラの座標>/
            フォルダの中に .mdrone ファイルとして保存される（「Open Scripts Folder」
            ボタンで開ける）。1つのコントローラにつき複数のスクリプトを置けて、DroneScreen
            のリストから選んでRunを押すと、選択中のスクリプトが実行される。リストにはファイル名
            ではなく、そのスクリプトの先頭に書いた # コメントが説明として表示される
            （例: 1行目が「# 畑を耕して植える」なら、リストにはその文がそのまま出る）。
            main.mdrone のほかに、動作見本として move_square.mdrone（移動のみ）・
            till_and_plant.mdrone（畑を耕して植える）・harvest_when_ready.mdrone
            （育った作物を収穫する）が最初から入っている。
            """;

    private CommandsHelpDoc() {
    }
}
