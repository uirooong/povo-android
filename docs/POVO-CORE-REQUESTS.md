# povo-core への依頼（povo-android 側からの要望）

povo-android を実装する過程で、povo-core 側にあると助かるもの / 直したいもの
をまとめる。実測の根拠を添えているので、そのまま調査の出発点に使える。

**優先度**

| # | 項目 | 位置づけ |
|---|---|---|
| [0](#0-b-1-お支払い方法の変更フローを通す) | B-1（お支払い方法の変更）を通すための調査 | **✅ 解決。`research/WEBVIEW-AUTH.md` の回答で実装完了** |
| [1](#1-post_raw_jsonabsolute_path-body_json) | `post_raw_json` | ✅ 実装済み。B-1 には結果的に不要だったが `webfront` 到達手段として有効 |
| [2](#2-telcoinfo-に-activation_date--initial_activation_date) | `TelcoInfo` に開通日 | ✅ 実装済み・採用済み |
| [3](#3-get_quilt_page_jsonpage-の型付きメソッド) | `get_quilt_page_json` | ✅ 実装済み・採用済み |
| [4](#4-ドキュメントに死んでいることを記録してほしいルート) | 死んでいるルートの記録 | ドキュメントのみ |
| [5](#5-未踏領域-webfront-と-oms-checkoutapp_settings) | 未踏領域の記録 | ドキュメントのみ |

---

## 0. B-1（お支払い方法の変更フローを通す）

> **✅ 解決済み。** `research/WEBVIEW-AUTH.md`（公式 APK 1.70.0-JP の静的解析）
> の回答どおり、**`device_id` を `auth_token` と一緒に初回リクエストのクエリに
> 載せる**のが答えだった。Cookie・初回ヘッダ・localStorage 注入はいずれも公式
> アプリも使っていない、という否定証拠が決定的だった。実機で認証済みページの
> 表示を確認済み。以下は経緯の記録。

### 何をしたいか

povo 公式アプリの「プロフィール → お支払い方法」と同じことをしたい。
カード番号の入力は `shop.povo.jp`（povo 自身のドメイン）で行われるので、
**PAN はこのアプリを通らない**。やることは「その SPA をログイン済み状態で
WebView に開く」の一点。

表示（`xxxx-xxxx-xxxx-下4桁`）は既に完成している。出所は
`GET /api/v1/quilt/page/profile` の `tile-credit-card` タイルで、同じタイルの
`action.data.web_view` に変更ページの URL が入っている:

```json
{ "link": "https://shop.povo.jp/manage/payment-details?webview=1&reset=true&native=1&update_from=mobile",
  "exit_url": "/updateCardSuccess",
  "needs_xauth": true }
```

### 現状

この URL を WebView で開くと `/web/login` → `/logged_out?reset=true` に飛ばされ、
ログイン画面かスピナーで終わる。`needs_xauth: true` が指す「トークンの渡し方」が
分かっていない。

### 実装済みで、単体では効かなかったもの

いずれも SPA バンドル（`https://shop.povo.jp/static/js/index.js`）の静的読解で
契約を確定させ、povo-android 側に実装した。**どちらも認証を通さない。**

**a) JS ブリッジ** — SPA は `window.POVO_ANDROID_WEB_BRIDGE` を探し、無いと
"Android bridge has not been initialized" / "Device is not supported" と判断する。

```
SPA  → POVO_ANDROID_WEB_BRIDGE.refreshXAuthToken("setAndroidAccessToken", promiseId)
アプリ → window.setAndroidAccessToken(promiseId, '{"X-Auth":"<jwt>"}')   // 第2引数は JSON 文字列
       ※ window.setAndroidAccessToken はバンドル内でグローバルに公開されている
getAppVersion() は同期で JSON 文字列を返す必要がある
       （JSON.parse(bridge.getAppVersion()).app_version、例外を投げると "Device is not supported"）
```

→ 実装してブリッジは認識された（`getAppVersion` が呼ばれるのをログで確認）。
だが **`refreshXAuthToken` は au ID 連携 (`useLinkUserAccount`) からしか呼ばれない**。
決済ページのブート経路では呼ばれない。

**b) `auth_token` クエリパラメータ**

```js
nextPageWithParams = (url, params, token) => { … isWebview() && addUrlParam("auth_token", token, url) … }
const t = urlParam("auth_token"); const h = t ? {"X-AUTH": t} : {};
```

→ `storeUrlParams()` で保存されず、一部のアクションが都度読むだけ。
**ブートガードはこれを見ていない**ので、付けても `/web/login` に飛ぶ。

### ページセッション — 入口は開いたが、扉が違う可能性が高い

`redirectToDeservedPage` が `decodeJWT(sessionToken) === null` でリダイレクト
していたので、当初これがブロッカーだと考えた:

```js
function redirectToDeservedPage({sessionToken, defaultRedirectPage, pages}) {
  const claims = decodeJWT(sessionToken);
  if (claims === null) { redirect(defaultRedirectPage); return; }
  const page = findKeyAndValueByIndex(claims.step, pages);
  …
}
// sessionToken の探索順: フォーム/注文の session_token → urlParam("token")
//                        → localStorage.getItem("sessionToken")
```

このセッションを作るエンドポイントを curl で叩き、**必要なものを段階的に特定した**
（実アカウントのトークンと device_id を使用）:

| リクエスト | 応答 |
|---|---|
| `POST /v4/jp/ja/webfront/users/session` | **404** `no Route matched with those values` |
| `POST /api/v3/user-service/v4/jp/ja/webfront/users/session` + `X-AUTH` | **400** `400101` |
| ＋ `X-Deviceid`（**ランダム値**） | **403** `403001` |
| ＋ `X-Deviceid`（**アカウント本来の値**） | **400** `40032` **"Error while trying to validate-Step"** |
| ＋ `step`（整数） | **400** `40032` **"validate-Action"** |
| `journey` を外す | **400** `40032` **"validate-Journey"** |
| `step` を文字列に | **400** `400001`（型エラー） |

**分かったこと**

- 正しいパスは **user-service プレフィックス配下**
  `/api/v3/user-service/v4/jp/ja/webfront/users/session`（`mobile` セグメント無し）
- **`X-Deviceid` が検証されている。** アカウント本来の値でのみ認証を通過し、
  そこから API が body の中身を検証する段階に進む。
  → **これを持っているのは povo-core / アプリだけで、curl では再現できない**
- 必要な body フィールドは `session_id` / `journey`（文字列）/ `step`（整数）/ `action`

**しかし** `step` と `action` はサーバー提供の設定から導かれる:

```js
step   = findMatchingPageFromInputPath(path, config.pages).next
action = extractPathAfterV2(page.key)
config = appSettings["layout.page_session"][planType]     // ← selectAppSettings
```

その設定の実体（バンドルに埋まっているデフォルト）はこうだった:

```json
"page_session": { "default_redirect_page": "/v2/plan-type",
                  "pages_to_register_session": ["/v2/email-otp-verification", …] }
```

**`/v2/*` — 新規申し込み（オンボーディング）のファネル専用**であり、
`/manage/payment-details` はこの仕組みの対象ではない。
つまり**ページセッションを作れても目的のページは開かない可能性が高い**。
`/web/login` へのリダイレクトは、もっと単純な「web セッション（Cookie）が無い」
判定から来ている疑いが濃い。

静的読解では `isLoggedIn` の供給元まで辿れなかった（`isLoggedIn` は
条件式の中でしか現れず、定義に到達できない）。ここが限界。

### 調査してほしいこと（優先順）

**★ 最重要: 公式 APK の WebView 層を読む。** SPA 側からは分からない。
grep 対象:

| 探すもの | 何が分かるか |
|---|---|
| `POVO_ANDROID_WEB_BRIDGE` / `addJavascriptInterface` | ブリッジに何を何個生やしているか。`refreshXAuthToken` 以外に認証に関わるメソッドがあるか |
| `CookieManager` / `setCookie` | **最有力候補。** `.povo.jp` に Cookie を仕込んでいるなら、その名前と値の作り方が答え |
| `evaluateJavascript` / `localStorage` | `sessionToken` などを事前投入しているか |
| `shop.povo.jp` / `webview=1` / `native=1` / `update_from` | URL をどう組み立てているか（クエリを足しているか） |
| `auth_token` | クエリに付けているのか、別の渡し方か |

**次に: token → web セッション交換の有無。** 上で Cookie が出てきたら、
その Cookie を得るエンドポイントを探す。`webfront/users/session` 以外に
それらしきものは見つかっていない。

**設定の実物も欲しい:** `layout.page_session` を含む app settings は
`GET /oms/checkout/app_settings`（バンドル内の定数は `"/checkout/app_settings"` と
`"/oms/checkout/app_settings"` の 2 つ）。実レスポンスに
`/manage/*` のページが載っているなら、ページセッション説が復活する。

### 結論として povo-core に必要なもの

1. **`post_raw_json`**（項目 1）— `webfront/*` を叩くのに必須
2. 上の調査結果。**核心は「公式アプリが WebView をどう認証しているか」**で、
   これは protocol ではなく Android 実装の話。povo-core の責務外なら、
   APK の逆コンパイル結果だけでも共有してもらえれば povo-android 側で実装できる

**代替案（B-1 が無理だった場合）**: WebView 内で一度だけ web ログインしてもらう。
Cookie は永続するのでアカウントごとに初回だけで済み、カード入力は povo の
ドメインのまま。複数アカウントで Cookie が混ざらないよう、開くアカウントが
前回と違えば消す処理が必要。

---

## 1. `post_raw_json(absolute_path, body_json)`

`get_raw_json` の POST 版。項目 0 の前提。

### なぜ必要か

`build_path` は必ず `/mobile/` セグメントを挟む:

```
{prefix}/{version}/jp/{locale}/mobile/{relative_path}
```

ところが web-front 系のルートには **`mobile` が無い**:

```js
USER_SERVICE_CREATE_PAGE_SESSION = "/v4/" + COUNTRY + "/{LOCALE}/webfront/users/session"
USER_SERVICE_UPDATE_PAGE_SESSION = "/v4/" + COUNTRY + "/{LOCALE}/webfront/users/session/update"
```

`post_json` で `webfront/users/session` を渡すと
`/v4/jp/ja/mobile/webfront/users/session` になり届かない（実測）。
GET は `get_raw_json` で回避できるが、この経路は POST しかない。

### 欲しいシグネチャ

```rust
/// `get_raw_json` の POST 版。ローカライズされたパス形式に当てはまらない
/// ルート（`webfront/*` は `mobile` セグメントを持たない）に届かせるため。
pub fn post_raw_json(&self, absolute_path: String, body_json: String)
    -> Result<String, PovoError>
```

`get_raw_json` と同様に、通常のヘッダ一式（`X-Deviceid` / `X-App-Version` /
`X-AUTH` / `X-USER-ID`）と `sin`/`uuid` のクエリ装飾はそのまま効かせてほしい。
**`X-Deviceid` は必須**（項目 0 の実測のとおり、これが無いと認証されない）。

なお povo-android 側では `PovoAccountClient.postJson` を追加した
（Rust の `post_json` は元からあり、Kotlin ラッパに出ていなかっただけ）。
`post_raw_json` が入れば同じ形で `postRawJson` を足す。

---

## 2. `TelcoInfo` に `activation_date` / `initial_activation_date`

契約情報の「開通日」に使う。現在は `UserProfile` に無いため、同じ
`users?include_telco=true` を生 JSON で読み直す `ProfileParser` を
povo-android 側に置いている（`PovoAccountClient.getProfileJson()`）。
型付き struct に入れば不要になる。

- `activation_date` が現在の回線の開通日。`initial_activation_date` は
  アカウント最初の開通日で、回線の再発行がなければ同値。フォールバックに使っている
- 値は ISO-8601（`2024-01-01T00:00:00.000Z`）。**UTC 深夜**なので、JST に
  変換すると日付が 1 日ずれる。時刻は捨てて `YYYY-MM-DD` にするのが正しい
- `customer_name`（名義）は既にあるので、そちらは追加作業なしで使えている

---

## 3. `get_quilt_page_json(page)` の型付きメソッド

quilt は `/api/v1/quilt/page/{page}` 固定で、いま povo-android 側で組み立てて
`get_raw_json` に渡している。このプロジェクトでは quilt が主要な情報源になった:

| ページ | 用途 |
|---|---|
| `user-plan-details-v2` | 有効なトッピング一覧 |
| `order-history` | 購入履歴 |
| `profile` | **お支払い方法**（マスク済みカード番号 + 変更ページ URL）。氏名・住所・暗証番号マスクも含む |

`profile` は **APK 静的解析由来のエンドポイント一覧に載っていない**。
判明している quilt ページ名の一覧はドキュメントに残す価値がある。

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
