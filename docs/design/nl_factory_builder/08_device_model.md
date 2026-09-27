# 08 機器モデル・学びの層(MHS式)

機器(ドローンの制御ブロック・発着場・機械など)を、「宣言した機能・実行前の検査・観測・学び」の層で扱う設計。P-17・D-32・F-26の詳細。

## 1. 一次情報と、この設計の位置づけ(重要)

- **実在の MHS(Model Hardware Standard)の仕様は公開されていない**。Anthropicの公開ページ(2026-08-27の研究プレビューの告知)から読めるのは、「機器は読む・書く・出来事を持つ」「機器が発見可能である」「安全限界は機器の側が強制する」「実行の前に事前条件で遮断する」といった**考え方**まで。それ以上の仕様(操作の名前・戻り値の形・限界の表し方・購読の形・認証)は公開情報からは確かめられない。
- したがってこの設計は **MHSに着想した、このプロジェクト独自の「おもちゃ規格」**。`MHS準拠`とは名乗らず「MHS式」と呼ぶ。実在の規格との互換・一致は主張しない。名前も独自(`DeviceDescriptor`・`DeviceGate`など)にする。
- **ページの考え方 → この設計での対応**(対応の中身は推測を含む。推測の個所には「推測」と書く):

  | MHSのページにあった考え方 | この設計での対応 |
  |---|---|
  | 機器は「読む・書く・出来事」を持つ | `DeviceDescriptor`の`reads`/`writes`/`events`(`01` 13節) |
  | 機器が発見可能である | `DeviceRegistry`・`ReferenceSheet`・MCPツール`get_device_reference` |
  | 安全限界は機器の側が強制する | `SafetyLimit`+`DeviceGate`の実行前検査(P-17) |
  | 実行の前に事前条件で遮断する | `Precondition`+`DeviceGate`の検査の順序(3節) |
  | (推測)緊急停止・人の確認 | `ESTOP`・`needsApproval`(4節) |

- **実仕様が公開されたら再確認する所**: 操作の名前と戻り値の形、限界の区分(`hard`/`CLAMP`の分け方)、出来事の購読の形(面名の対応づけは推測)、認証・権限、用語の対応づけ。再確認の結果は変更履歴に記録する。
- 文書・型の中で「推測」と書いた物は、公開情報からは確かめられていない、このプロジェクトでの決め事。一次情報と推測は混ぜない(`01` 13節も同じ注記)。

## 2. 機器の宣言(`DeviceDescriptor`)

機器は`DeviceDescriptor`(`01` 13節)で、次を宣言する:

- **読む(READ)**: 世界を変えない操作(`measure`・`can_harvest`・`get_*`など)。
- **書く(WRITE)**: 世界・機器の状態を変える操作(`move`・`till`・`plant`・`harvest`・`set_output`など)。
- **出来事(events)**: 機器からの通知(`fish_bite`・`dock_arrived`・`harvestable`など)。
- **安全限界(SafetyLimit)**: `hard=true`は超えた指示を拒否。丸めてよい種類だけ`CLAMP`(丸めた事実は記録に残す)。
- **事前条件(Precondition)**: 決定論の検査の名前で宣言し、実行前に満たす必要がある。
- **日本語の目印(tags)と`referenceSheetId`**: 子供が読める説明の元になる。

## 3. `DeviceGate`(実行前の必須の検査)

すべての`DeviceInstruction`は、実行の前に`DeviceGate`(純Java)を通る。検査の順序:

1. 機器が存在し、`OFFLINE`・`ESTOP`でない。
2. 引数の型と範囲(`ParamSpec`)。
3. 事前条件(`Precondition.check`)。
4. 安全限界(`hard`は拒否、丸め可能なものだけ`CLAMP`)。
5. `ESTOP`の状態。

- 結果は`InstructionVerdict`(`ALLOW`/`CLAMP`/`REFUSE`)。`REFUSE`は`E-DEVICE-RANGE`・`E-DEVICE-PRECOND`・`E-DEVICE-LIMIT`・`E-ESTOP`の`Issue`を返す(例外で潰さない。`Issue`+`FixHint`の形は他の検査と同じ)。`CLAMP`は`W-DEVICE-CLAMPED`と丸めた値を残す。
- **AI・スクリプト・画面のどの経路も、この検査を迂回できない**(P-17)。D-1(AIは世界を書けない)・`ParamValidator`・`PlaceableBlockPolicy`・`EffectSpec`(D-24)と同じ「宣言した範囲だけを許す検査器」の、機器に対する版。検査の実装は`build/device/`の純Java、実機に触れる面は`integration/device/`(該当modが入っている時だけ読み込まれる)。

## 4. 緊急停止(`ESTOP`)と状態

- `DeviceState`: `OFFLINE`・`ONLINE`・`BUSY`・`FAULT`・`ESTOP`・`RECOVERING`。
- `ESTOP`が発動した機器は、全`WRITE`を`E-ESTOP`で拒否し、動作中の操作を止める。**解除は所有者かOPの明示操作のみ**。発動・解除は`MonitorRecord`とジョブの`journal`に残る(誰が・いつ・なぜを追跡できる)。`ESTOP`の発動自体は確認を要しない(安全側は即時)。
- **危険な操作は人の確認を要する**: `DeviceOp`は「確認が要る」印(`needsApproval`)を持てる(推測: MHSにこの区分があるかは未公開)。印のついたWRITEは、実行の前に承認画面(D-3と同じ承認の作法)で人が確認する。確認なしの操作は`REFUSE`。
- 機器が応答しない・チャンクが読み込まれていない読み取りは「不明」を返し、`0`や「失敗」とは区別する(真実の欠如を偽の値で埋めない)。

## 5. 出来事 → ソフトウェア割り込み

- `DeviceEvent`は、言語の`attach_isr(face, fn)`/`raise_interrupt(face)`の、面名`"device:<deviceId>:<event>"`に対応づける(既存の`InterruptTable`を流用。ISRは呼んだ側のスレッドで動く)。
- 実物のレッドストーンの立ち上がりを面名へ配線する仕組みは、既知の未着手項目として**S-15**で確かめる(現在は`raise_interrupt`を機器層がプログラムから呼ぶ形)。
- 建設のスクリプト(`PlanScriptProfile`)では`attach_isr`等は引き続き禁止(E-SCRIPT-FORBIDDEN)。機器の出来事を使えるのは**畑・運用のスクリプト側**で、建設の決定論は割り込みに依存しない。

## 6. 監視と教材(`MonitorRecord`・`ReferenceSheet`)

- 操作・出来事・拒否を`MonitorRecord`として記録する(実測のフィードバック。L8の実測・L9の蓄積の材料)。**拒否の記録は「なぜ止まったか」の教材でもある**: `REFUSE`/`CLAMP`は`Issue`の理由(`E-DEVICE-*`と`FixHint`)と共に残り、子供が後から「なぜこの指示は止められたか」を読める(拒否ログ)。
- 各機器に`ReferenceSheet`(「できること・できないこと・絶対に止まる条件」)を宣言から自動生成し、画面のヘルプとAIの資料(`get_device_reference`)は**同じ生成物**を使う(正本は1つ。P-16と同じ構え)。**ゲーム内で読める機器の説明書**として、看板・本・ヘルプ画面のいずれかの形で出す(形はP17で実装しながら決める)。
- **練習問題**(推測、教材として): 各機器について、宣言から短い課題を自動生成する案(「この機器を動かすには」「なぜこの指示は止められたか」「`ESTOP`を解除できるのは誰か」)。問題の生成の型はP17で作る。

## 7. 学びの層(観測 ≠ 検証済み)

機器のモデルの値は、3種に分けて持つ:

| 種類 | 例 | 信用の度合い | 計画に使えるか |
|---|---|---|---|
| 宣言値 | `DeviceDescriptor`/`PartType`のパラメータ・限界 | 設計時の宣言 | 使える(デフォルト) |
| 観測値 | `MonitorRecord`・`RuntimeMonitor`の実測 | その環境・その時の1観測 | **そのままでは使わない**(参考表示のみ) |
| 学習済み値 | 昇格を通った値(回数・条件・版つき) | 検証済み | 使える |

- **昇格(観測→学習済み)のゲート**: (a)同じ条件の観測が既定回数(初期値3回)集まり、ばらつきが閾値内、(b)試運転(`Commissioning`)で実機確認、(c)ユーザーの承認。**1回の観測が、黙って信頼されるモデルにならない**。
- 学習済みの値は、版(`registryVersion`・モデルの版)と出所(`Verification`と同じ由来の記録)を付けて保存し、計画が使う値は版を明記する。版が合わなければ、古い学習値は使わず宣言値に戻る。
- 学習の適用は**決定論**: 同じ観測の集まりからは同じ学習値が出る(推論にAIを使わない。集計は平均・中央値などの固定の規則)。

## 8. 最初の機器と段階

- **最初の機器**: 畑ドローンの制御ブロック(`packId="vanilla"`)。読む=`measure`・`can_harvest`・`get_*`、書く=`move`・`till`・`plant`・`harvest`・`set_output`、出来事=`harvestable`・`fault`。
- 発着場・機械・潜水艦の機器は、対応するpackが有効な環境でのみ登録される(`DeviceDescriptor.packId`)。
- フェーズ: **P17**(S-15の後)。完了の実測条件は`07` P17節。
