# povo-core への依頼（povo-android 側からの要望）

povo-android を実装する過程で、povo-core 側にあると助かるもの / 直したいもの
をまとめる。実測の根拠を添えているので、そのまま調査の出発点に使える。

解決済みの項目は削除している。過去の依頼と回答の記録は各調査ドキュメント
（`POVO-WEBVIEW-FINDINGS.md` / `TOPPING-PURCHASE.md` / `PHASE1-FINDINGS.md`）側にある。

**優先度**

| # | 項目 | 位置づけ |
|---|---|---|
| [0](#0-エラー本文の-failure-エンベロープを剥がす) | エラー本文の `failure` を剥がす | **最優先。購入失敗時に JSON が人に見える** |
| [1](#1-place_topping_orderskuredirect_url-を型付きで) | トッピング購入を型付きで | バージョンとボディ形状を core に置きたい |
| [2](#2-get_quilt_page_json-がクエリパラメータを潰す) | quilt のクエリパラメータ | `urlencode` で `?` が壊れる |
| [3](#3-get_bills_info-が-0-件を返し続けている) | `get_bills_info()` が 0 件 | Phase 1 からの持ち越し |
| [4](#4-ドキュメントに死んでいることを記録してほしいルート) | 死んでいるルートの記録 | ドキュメントのみ |
| [5](#5-未踏領域-webfront-と-oms-checkoutapp_settings) | 未踏領域の記録 | ドキュメントのみ |

---

## 0. エラー本文の `failure` エンベロープを剥がす

`error.rs` の `error_envelope()` は `["error", "result"]` の 2 キーしか降りない。
ところがトッピング購入 API の 422 はこの形で返る:

```json
{"success": false,
 "result": null,
 "failure": {"code": 4221131,
             "title": "ERROR_SUBSCRIPTION_BUSINESS_VALIDATION_FAILED",
             "description": "[checkActivateEligibility]: No subscription to activate for the request"}}
```

- `result` は `null` でオブジェクトでないため skip される
- `failure` はキー一覧に無いので降りない
- トップレベルに `message` が無い

結果として **`server_message` に JSON 本文が丸ごと入り**、
アプリはそれをそのまま人に見せてしまう。トッピングが買えなかった利用者に
生の JSON が出るのは避けたい。

**お願い:**

1. `error_envelope()` の探索キーに `failure` を追加する
2. メッセージ候補に `description` と `title` を含める（現在は `message` のみ）
3. `failure.code`（例 `4221131`）を `PovoError::Api.code` に載せる。
   これが取れると「既に持っている」「対象外」等をアプリ側で分岐できる

> 関連: 更新に失敗するアカウントで **`403001`** が出た。これは
> `API-REFERENCE.md` で実測記録済みのコードで、**トークンに紐づいた
> `X-Deviceid` と送っている device_id が食い違っている**という意味
> （webfront の `users/session` にランダムな device_id を付けたときの値）。
> ただし観測したのは webfront ルートのみで、通常の更新経路でも同じ意味かは未確認。
>
> 同じ回線で「7227」というコードも見えている。公式 FAQ の 7xxx 帯はログイン・
> 申し込み系（7017 認証コードロック、7019、7023 など）なので認証系の可能性が
> 高いが、**未特定**。エラー本文が構造化されて上がってくればこの手の切り分けが
> 一気に楽になる。

---

## 1. `place_topping_order(sku, redirect_url)` を型付きで

現在は povo-android 側で JSON を組み、**バージョン `v2` をハードコード**している。
ここは 2 回外している（`subscription/activate` の v1 → 422、v4 も違う）。
バージョンとボディ形状はプロトコル知識なので core が持つべき。

```
POST /v2/jp/ja/mobile/shop/orders        ← v2。v4 でも v1 でもない
{"sku": "<product id>", "offer_product": null, "redirect_url": "https://povo.jp/success"}

→ {"success": true,
   "result": {"challenge_url": "https://front.secure.gmopg.jp/auth/brw/callback?...",
              "order_ref": "...", "p_ref": "..."}}
```

アカウント識別子は送らない（トークンから引かれる）。実測で `order_id` は返らず、
`order_ref` と `p_ref` のみ。

**`challenge_url` の有無が「3-D Secure 認証待ち（未課金）」と「購入完了」を分ける**
ので、型で表現されると取り違えが起きにくい。詳細は `TOPPING-PURCHASE.md`。

なお `POST /v1/.../subscription/activate` は定額パートナー連携の有効化専用で、
都度購入のトッピングには使えない（上記 422）。docstring に書き分けてほしい。

---

## 2. `get_quilt_page_json` がクエリパラメータを潰す

`client.rs` の実装が `format!("/api/v1/quilt/page/{}", urlencode(&page))` なので、
`"usage-history?type=DAILY"` を渡すと `?` が `%3F` になりパスが壊れる。

実際に `usage-history?type=DAILY|MONTHLY`（データ使用量の履歴）が叩けず、
`get_raw_json` で回避した。その機能自体は見送ったが、クエリを取る quilt ページは
今後も出るはずなので、

```rust
fn get_quilt_page_json(&self, page: String, query: Option<HashMap<String, String>>)
```

のようにクエリを渡せる形にしてほしい。`type` の値は `DAILY` / `MONTHLY` の 2 つだけで、
それ以外はサーバーが
`adapter failed - Usage History Quilt API Incorrect 'type' query param` を返す。
詳細は `USAGE-HISTORY-FINDINGS.md`。

---

## 3. `get_bills_info()` が 0 件を返し続けている

Phase 1 で判明してから未解決。`bills` または `result` を**配列として**探すが、
実際の請求は `past_bills.list[]` にある。

povo-android は Phase 1 以降ずっと
`get_json("layout/bills/info", None, "v5", "ja")` のエスケープハッチで回避し、
パースは Kotlin 側（`BillsParser`）で持っている。

**直すか、直さないなら docstring に「このメソッドは常に空を返す。
`get_json` で生 JSON を取って `past_bills.list[]` を読むこと」と明記してほしい。**
今の状態は、知らない人が呼んで「請求が無い」と誤解する。

実形状は `PHASE1-FINDINGS.md` に記録済み。

---

## 4. ドキュメントに「死んでいる」ことを記録してほしいルート

実測で使えないと確認したもの。将来また誰かが試すのを防ぐため。

| ルート | 実測 |
|---|---|
| `GET /v4/jp/ja/mobile/layout/profile/info` | **500** `error_code_common` |
| `GET /v4/jp/en/mobile/layout/profile/info` | **500** `Oops, operation failed.` |
| `GET /v4/jp/{ja,en}/mobile/profile/creditcard/update` | **`api.wirecard.com.sg` へリダイレクト → NXDOMAIN**。Wirecard は 2020 年に破綻しており、このドメインは存在しない |
| `GET /v4/jp/ja/mobile/promotions/payment/url/get` | **404** `no Route matched with those values` |
| `GET/POST /v4/jp/ja/mobile/telco/dashboard` | GET は 404、POST は 500 `error_code_common`（フル body / boost のみの両方で） |

APK 由来の一覧にある決済系 8 本はすべて `x-verified: false` かつスキーマ
`GenericObject`（215 本中、実通信確認済みは 11 本のみ）。
上記のとおり少なくとも 4 本は実際に使えない。

---

## 5. 未踏領域: `webfront/*` と `oms/checkout/app_settings`

`webfront` プレフィックスは APK 由来の一覧に無く、SPA バンドルにしか出てこない。
判明しているだけで:

```
/v4/jp/{locale}/webfront/users/session          POST  ← 項目 0 で挙動を確認済み
/v4/jp/{locale}/webfront/users/session/update   POST  (ヘッダ X-SessionID)
/v4/jp/{locale}/webfront/users/pin/reset/confirm
/v4/jp/{locale}/webfront/users/pin/change/confirm
/v4/jp/{locale}/webfront/users/pin/validate
```

いずれも user-service プレフィックス配下と思われる（session で確認済み）。

あわせて、SPA の設定はこちら:

```
GET /oms/checkout/app_settings     （バンドル内の定数は "/checkout/app_settings" と "/oms/checkout/app_settings"）
→ layout.page_session, featureToggle, whitelist などを含む
```

このレスポンスは SPA の挙動を決める設定そのものなので、取得できると
web 側の動きを読むのが一気に楽になる。
