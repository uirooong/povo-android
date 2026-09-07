# Phase 1 プロトコル検証 — 実機結果

エミュレータ (API 30) + 実アカウントで確認済み。
マスク済みの実レスポンスは `docs/samples/` に保存。

## 結論: povo-core のログイン経路はそのまま使える

| # | 論点 | 結果 |
|---|---|---|
| **B** | `login()` が `/user-service/v5/public/.../users/auth` で通るか | ✅ **通る**（povo-native の `v4` に合わせる必要なし） |
| **C** | `users/login/action` に `device_id` 無しで通るか | ✅ **通る** |
| **D** | `device` に `token_type:"FCM"` 無しで通るか | ✅ **通る** |
| **E** | User-Agent | `okhttp/4.12.0` に合わせて実行（`PovoProtocol.USER_AGENT`） |
| **F** | SMS 経路の top-level `isd_code`/`phone_no` | ⬜ 未検証（サーバーが `EMAIL_OTP` のみ要求したため） |
| — | captcha | ✅ **不要**（`captcha_token = null` で通過） |
| — | PIN | ✅ **来ない**（`pin_token = none`） |
| **A** | `getBillsInfo()` は空か | ❌ **0 件 → povo-core のバグ確定** |

→ **Rust 側の修正は不要**。ただしビルドを通すために
`PovoError::Api` の `message` → `server_message` リネームだけは必須（README 参照）。

## 各エンドポイントの実結果

| エンドポイント | 結果 |
|---|---|
| `POST users/login/action` | ✅ |
| `POST otp` (LOGIN_EMAIL_OTP) | ✅ |
| `POST users/auth` | ✅ |
| `GET users?include_telco=true` | ✅ SIN・生年月日・プラン名が取れる |
| `GET account/usage/plan/get` | ✅ `samples/account-usage-plan-get.json` |
| `GET layout/bills/info` | ✅ 生JSONは取れる／型付きは 0 件 `samples/layout-bills-info.json` |
| `GET account/referral/code/get` | ✅ `samples/account-referral-code-get.json` |
| `GET account/plan/details/get` | ❌ **HTTP 500** `error_db_transaction` |
| `GET account/usage/data/details/get` | ❌ **HTTP 491** `error_not_found_message` |

後者2つは APK 静的解析由来 (`x-verified: false`) で、実通信では動かない。
**使わない。** プラン名はプロフィールの
`telco_info.circles_info.service_instance_base_plan_name` から取る。

> HTTP **491** は標準外のステータス。`PovoError::Api` はそのまま通すので
> Kotlin 側で「不明なエラー」として扱えばよい。

## A: `getBillsInfo()` が 0 件になる理由（確定）

`layout/bills/info` のトップレベルキーは:

```
au_services_status, banner, billing, code, instant_charges, past_bills, upcoming_bill
```

povo-core は `bills` または `result` を**配列として**探すが、そのどちらも存在しない。
請求は `past_bills.list[]` にある。

→ **対処**: `PovoAccountClient.getBillsInfoJson()`（`get_json` エスケープハッチ）を使う。実装済み。

### `past_bills.list[]` の実形状

```json
{
  "billId": "REG0000000000000",
  "title": "2026年7月 - 2026年8月",
  "subtitle": "180円 - 支払い済み",
  "subtitleSuffix": "支払い済み",
  "subtitle_suffix": "支払い済み",
  "time": 1785509999000,
  "type": "PAID",
  "total": { "prefix": "円", "postfix": "JPY", "value": 180 },
  "actions": [
    { "type": "pdf",          "data": { "link": "" } },
    { "type": "service_call", "data": { "link": "" } }
  ]
}
```

パーサを書くときの注意:

- `billId` は **camelCase**。
- `subtitleSuffix` と `subtitle_suffix` が**両方入っている**（同値）。どちらか一方を読めばよい。
- `time` は **epoch ミリ秒**。
- **`type` に状態がある**（`"PAID"`）。事前調査では「ステータス欄は無いのでセクションで判断」としていたが、実際には型が入っている。セクション推定は不要。
- `total` は PriceModel（`prefix` が `"円"`、`postfix` が `"JPY"` で**両方入る**）。
- `actions[].type == "pdf"` が PDF ダウンロードの手がかり。ただし `link` は空文字なので、
  実際の取得は `download_bill_pdf(billId)` を使う。

その他のセクション:

- `upcoming_bill.list[]` — `{ label, amount: PriceModel }`。加えて `total` / `title` / `disclaimer`。
- `instant_charges` — `{ header, list[], paid: PriceModel }`。
- `billing` / `banner` / `au_services_status` — UI 用の案内。ダッシュボードには不要。

## `account/usage/plan/get` の実形状

トップレベル: `calls`, `callsIdd`, `data`, `other`, `roamingIdd`, `sms`, `type`

**`result` / `data` の多重ラップは無かった** — 使用量は素直に `data` 直下。
（他のエンドポイントでは `result` ラップがあり得るので、アンラップ処理自体は残す。）

```json
"data": {
  "basic": { "left":0, "plan_kb":0, "prorated":0, "prorated_kb":0,
             "section":"DATA", "unit":"kilobytes", "used":0 },
  "bonus": { "left":0, "section":"BONUS", "unit":"kilobytes", "used":0 },
  "boost": { "left":0, "section":"BOOST", "section_min_value":1048576,
             "unit":"kilobytes", "used":0 },
  "extra": { ...basic と同じ形... },
  "plus":  { ...basic と同じ形, "section":"" },
  "promotion_text": { "line1":"Bonus", "line2":"0 MB" }
}
```

- 事前調査どおり `used` / `left` / `plan_kb` / `unit` は実在。**単位は `"kilobytes"`**。
- 追加で **`prorated` / `prorated_kb` / `section`** があり、`boost` だけ **`section_min_value`**（1048576 KB = 1 GiB）を持つ。
- `bonus` と `boost` には `plan_kb` が**無い**。バケットごとにキーが違うので、必須扱いにしないこと。
- 通話・SMS は `other.basic.{calls,sms}`、従量課金は `other.pay_as_you_go.*` に分かれている。
  価格は `{prefix:"¥", value:N}`（※ここは `¥`、請求側は `円`。**表記が混在している**）。

⚠️ **`addons_subscribed` は今回のレスポンスに存在しなかった。**
事前調査ではここにトッピングが入るとされていたが、このアカウントは
トッピング未加入・データ残量ゼロのため。**トッピング加入中のアカウントで再取得が必要。**

## トークンの寿命が短い

```
発行 05:01:38 → exp 05:21:38   (20 分)
```

`refresh_token()` で延長できることも確認済み。
「exp が切れてから refresh」方針は維持しつつ、実質ほぼ毎回 refresh が走る前提で組む。
→ トークンの暗号化永続（`SecureBlobStore` / `SessionStore`）を実装済み。
再インストールしてもセッションが復元されることを実機で確認した。

## 未検証で残っているもの

1. **`addons_subscribed`** — トッピング加入中のアカウントが要る。
2. **F (SMS ログイン経路)** — サーバーが `EMAIL_OTP` しか要求しなかった。
   電話番号だけでログインするアカウントで確認する。
3. **請求 PDF** (`download_bill_pdf`) — 未実行。パスワードが
   `YYYY-MM-DD` か `YYYYMMDD` かもまだ確定していない。
4. **非ゼロの使用量** — 全バケットが 0 だったため、桁や書式の確認ができていない。

---

# 追記: 2アカウント目（プラン契約あり）での再取得

## ✅ マルチアカウント並列更新を実測（このアプリの中核前提）

保存済み 2 アカウントを `coroutineScope { async(Dispatchers.IO) … }` で同時更新:

```
[account-a]    OK  (+1ms → +2011ms)  sin=LW1********  phone=090********
[account-b]    OK  (+4ms → +1787ms)  sin=LW1********  phone=090********
合計 2018 ms
```

- 両方が **4ms 以内に開始し重なって走った**。逐次なら約 3,800ms のところ 2,018ms。
- **SIN が取り違えられていない** — 各アカウントの `sin` が自分の `phone_no` と正しく対応。
- 「アカウント 1 つにつき `PovoClient` 1 インスタンス」で混線しないことが実証できた。

## ⚠️ 使用量の値は**小数**（Long でパースすると壊れる）

プラン契約アカウントの `account/usage/plan/get`:

```json
"boost": { "used": 62914560.62890625,
           "left": 62914560.37109375,
           "section": "BOOST", "section_min_value": 1048576, "unit": "kilobytes" }
"basic": { "used":0, "left":0, "plan_kb":0, "prorated":0, "prorated_kb":0, … }
```

| | KB | GiB |
|---|---:|---:|
| used | 62,914,560.63 | 60.00 |
| left | 62,914,560.37 | 60.00 |
| 合計 | 125,829,121.00 | **120.00** |

- **`Double` で受けること。** `Long`/`Int` にすると `JsonDecodingException` で落ちる。
- 1024 進で割ると 120.00 GiB ちょうどになる → **バイナリ換算が正しい**ことも裏付けられた。
- `section_min_value: 1048576` = 1 GiB。

## ⚠️ 契約データは `basic` ではなく `boost` に入る

このアカウントは `basic` / `extra` / `plus` / `bonus` がすべて 0 で、
残量はすべて **`boost`** バケットにある。

→ ダッシュボードの「データ残量」は **`basic` だけを見てはいけない**。
5 バケットを合算するか、非ゼロのものを拾う必要がある。

## ❌ `addons_subscribed` は取得できない

事前調査では `account/usage/plan/get` の `data.addons_subscribed` に
トッピング一覧が入るとされていたが、**プラン契約アカウントでも存在しなかった**。
関連しそうなエンドポイントを実際に叩いた結果:

| エンドポイント | 結果 |
|---|---|
| `/v4/…/account/plan/details/get` | ❌ HTTP 500 `error_db_transaction`（トークン更新後も同じ） |
| `/v4/…/account/addon/topup/all/get` | ❌ HTTP 500 `error_system_issue` |
| `/api/v1/…/quilt/page/user-plan-details-v2` | ❌ HTTP 404 `no Route matched with those values` |

→ **トッピングの一覧表示は現時点では実装できない。**
ただし、トッピングで購入したデータ量自体は `boost` バケットに反映されるので、
**残量ダッシュボードには影響しない**。一覧が必要になったら別途エンドポイント探索が要る。

## トークン更新の挙動（確定）

```
期限切れ後に GET users/token → OK
exp = 1788759831  有効期間 = 1200 秒
```

- **期限切れ後でも refresh できる**。再ログインは不要。
- 有効期間は毎回きっかり **1200 秒 (20 分)**。

## 401 のレスポンス形状に注意

```json
{"success":false,"result":{"message":"Token Expired"}}
```

povo-core のエラーパーサは `{message}` と `{error:{message}}` は剥がすが、
**`{result:{message}}` は剥がさない**ので、`PovoException.Api.serverMessage` に
上記の生 JSON がそのまま入る。
→ Kotlin 側でユーザー向けメッセージに変換するときに、この形も剥がすこと。

## ✅ 請求 PDF — パスワードは `YYYY-MM-DD` で確定

```
billId = REG…
bytes  = 66,692
magic  = %PDF-1.6
/Encrypt 検出 = true      → 暗号化されている
```

実際のバイト列を pypdf で検証した結果:

| パスワード | 結果 |
|---|---|
| （空） | ❌ 失敗 |
| **`YYYY-MM-DD`**（例 `1981-01-14`） | ✅ **成功**（3 ページ） |
| `YYYYMMDD`（例 `19810114`） | ❌ 失敗 |

→ **ハイフン付きが正しい。** povo-core の docstring にある `YYYYMMDD` は誤り。
生年月日はプロフィールの `dob.{year,month,day}` からゼロ埋めで組み立てる。

なお `decrypt()` が 2（オーナーパスワード一致）を返したので、
**閲覧にパスワードが要る通常の保護 PDF** である。
`android.graphics.pdf.PdfRenderer` はパスワード付き PDF を開けないため、
アプリ内でレンダリングせず FileProvider で外部ビューアに渡し、
パスワードを画面に表示してコピーできるようにする方針は変更なし。
