# お支払い方法の表示と変更 — 実機調査の結果

povo 公式アプリの「プロフィール → お支払い方法」に相当する機能を実装するために、
実アカウントで各エンドポイントを叩き、`shop.povo.jp` の SPA バンドルを読んで
確定した事実をまとめる。

調査に使ったのは debug ビルドのプロトコル検証画面（`/` 始まりの絶対パスは
`get_raw_json` に回るよう拡張した）と、公開されている JS バンデル
`https://shop.povo.jp/static/js/index.js` の静的読解。

---

## 1. マスク済みカード番号の在り処 ✅ 実装済み

**`GET /api/v1/quilt/page/profile`**（quilt なのでロケールセグメントなし）

```json
{ "type": "tile-credit-card",
  "data": { "title": "ご利用中のお支払い方法",
            "description": "xxxx-xxxx-xxxx-1234",
            "cardIcon": ".../Povo-WF-015-Mastercard-Icon.png" },
  "action": { "type": "web_view",
    "data": { "web_view": {
      "link": "https://shop.povo.jp/manage/payment-details?webview=1&reset=true&native=1&update_from=mobile",
      "exit_url": "/updateCardSuccess",
      "needs_xauth": true }}}}
```

- このページは**エンドポイント一覧（APK 静的解析由来）に載っていない**。
- 同じページに氏名・郵便番号付き住所・暗証番号マスクも含まれる。
  本アプリが読むのは `tile-credit-card` だけ。
- 同一ページの「メールアドレス」「ご住所」「暗証番号」も `web_view` タイルなので、
  **アクションではなくタイル種別 (`tile-credit-card`) でマッチする必要がある**。

### 当初の候補は全滅

| 候補（APK 由来の一覧より） | 実測結果 |
|---|---|
| `GET  /v4/jp/ja/mobile/layout/profile/info` | **500** `error_code_common` |
| `GET  /v4/jp/en/mobile/layout/profile/info` | **500** `Oops, operation failed.` |
| `GET  /v4/jp/{ja,en}/mobile/profile/creditcard/update` | **`api.wirecard.com.sg` へリダイレクト → NXDOMAIN**（Wirecard は 2020 年に破綻。この経路は死んでいる） |
| `GET  /v4/jp/ja/mobile/promotions/payment/url/get` | **404** `no Route matched with those values` |

一覧上の決済系 8 本はすべて `x-verified: false` かつスキーマ `GenericObject`
（215 本中、実通信で確認済みは 11 本のみ）。

---

## 2. 変更フロー（WebView）— 未完

カード入力は `shop.povo.jp` 上で行われるので、**カード番号はアプリを通らない**。
問題は「その SPA をログイン済み状態で開く」方法。

### 分かっている仕組み

**a) JS ブリッジ**（実装済み・動作確認済み）

SPA は `window.POVO_ANDROID_WEB_BRIDGE` を探し、無いと
"Android bridge has not been initialized" / "Device is not supported" と判断する。

```
SPA  → POVO_ANDROID_WEB_BRIDGE.refreshXAuthToken("setAndroidAccessToken", promiseId)
アプリ → window.setAndroidAccessToken(promiseId, '{"X-Auth":"<jwt>"}')   // 第2引数は JSON 文字列
SPA  → window.setAndroidAccessToken はグローバルに公開されている
```

`getAppVersion()` は**同期**で JSON 文字列を返す必要がある
（`JSON.parse(bridge.getAppVersion()).app_version`）。例外を投げると
"Device is not supported" になる。

ただし **`refreshXAuthToken` は au ID 連携 (`useLinkUserAccount`) 専用**で、
決済ページのブート経路では呼ばれない。ブリッジだけでは認証は通らない。

**b) `auth_token` クエリパラメータ**（実装済み・単体では不十分）

```js
nextPageWithParams = (url, params, token) => { … isWebview() && addUrlParam("auth_token", token, url) … }
// 受け側の例
const t = urlParam("auth_token"); const h = t ? {"X-AUTH": t} : {};
dispatch(UserService.V4.fetchLoggedInUserDetails(h, params))
```

`auth_token` は `storeUrlParams()` で保存されず、一部のアクションが都度読むだけ。
**ブートガードはこれを見ていない**ので、付けても `/web/login` へ飛ぶ。

**c) ページセッション ← ここが本命・未実装**

ブートガードが要求しているのはこれ。

```
POST https://app.povo.jp/v4/jp/ja/webfront/users/session
Headers: X-AUTH: <アカウントの JWT>
Body:    { "session_id": "<uuid v4>", "journey": "onboarding" }
→ レスポンスは redux の `serverPageSession` に入り、session_token（JWT）を含む
```

- 更新は `POST /v4/jp/ja/webfront/users/session/update`、ヘッダ `X-SessionID: <session_token>`
- SPA は `localStorage.getItem("sessionToken")` からも読む
- ベース URL は `VITE_USER_SERVICE_URL = "https://app.povo.jp"`
  （povo-core が既に話している host と同じ）
- パス形式は povo-core の `build_path(None, "v4", "ja", "webfront/users/session")` と一致するので、
  **`post_json` のエスケープハッチでそのまま叩ける**（core 変更不要の見込み）

### 残っている疑問

1. `journey` に `onboarding` 以外の値（管理系）があるか。定数は
   `PAGE_SESSION_ONBOARDING_KEY="onboarding"` しか見つかっていない。
2. セッション作成が**アプリ側の device_id と紐づく**か。SPA は
   `Session.getDeviceId()` で自前の値を localStorage に持ち、URL の
   `device_id` パラメータで上書きできる（`IS_PATHS_SECURED` が真のときのみ）。
   アカウントの device_id を渡す必要があるかは未確認。
3. 作った session_token を SPA に渡す方法。専用の URL パラメータは無いので
   （`urlParam("token")` は請求書のディープリンク用だが `sessionTokenSelector`
   の探索順には入っている）、次のどちらか:
   - `localStorage.sessionToken` に事前投入する。同一オリジンを一度読み込んで
     `evaluateJavascript` で書き、その後に目的の URL へ遷移する手順になる
   - `?token=<session_token>` を付けて試す（探索順に入っているので効く可能性がある。
     こちらが通るなら WebView 側の作りは大幅に簡単になる）
4. **セッション JWT の `step` クレーム**。`findKeyAndValueByIndex(claims.step, pages)`
   でリダイレクト先が決まるため、`journey: "onboarding"` で作ったセッションだと
   オンボーディング途中のページへ飛ばされる可能性がある。
   `/manage/payment-details` に留まる `journey` / `step` の組み合わせが要る。

### ガードの正体（追調査で確定）

リダイレクトを出しているのは `usePageSessionHandler` → `redirectToDeservedPage`:

```js
function redirectToDeservedPage({sessionToken, defaultRedirectPage, pages}) {
  if (location.pathname === LOGGED_OUT || MAINTENANCE || LOG_IN) return;
  const claims = decodeJWT(sessionToken);
  if (claims === null) { redirect(defaultRedirectPage); return; }   // ← ここに落ちている
  const page = findKeyAndValueByIndex(claims.step, pages);
  if (isEmpty(page)) { redirect(defaultRedirectPage); return; }
  redirect(page.key);
}
```

- `sessionToken` は `sessionTokenSelector` = 各フォーム/注文の `session_token`
  → `urlParam("token")` → **`getSessionToken()` = `localStorage.getItem("sessionToken")`** の順に探す
- `defaultRedirectPage` は設定値
  `layout.page_session.default_redirect_page_when_session_expired`
- **セッション JWT には `step` クレームがあり、それでどのページへ行くかが決まる**

つまりブロッカーはページセッションで確定。`localStorage.sessionToken` が
SPA から見える置き場所。

### 観測されたリダイレクト連鎖

```
/manage/payment-details?webview=1&reset=true&native=1&update_from=mobile&auth_token=…
  → "Going to Url =>  /web/login?redirect=…"      ← セッションが無いと判断
  → "Going to Url =>  /logged_out?reset=true"     ← ここで停止（スピナー）
```

`reset=true` を落としても連鎖は変わらなかったので、サーバーが返した URL は
そのまま使っている。

---

## 3. セキュリティ上の扱い

- WebView に入れている javascript interface は
  `getAppVersion` / `refreshXAuthToken` の 2 メソッドのみ。
  eKYC・ファイルダウンロード・eSIM 系は実装しない。
- トークンを渡す前に**表示中のページが povo ドメインか**を確認する
  （カード変更は 3-D Secure で発行銀行のドメインへ正当に遷移しうるため、
  遷移先からブリッジを叩かれてもトークンを渡さない）。
- `auth_token` クエリは povo ドメインの URL にのみ付与する。
- 保存するのはマスク済み番号と URL のみ。氏名・住所・暗証番号は読まない。
- **調査中、実アカウントの JWT が logcat に出た**（`auth_token` を含む URL が
  SPA の `console.log` に出るため）。検証端末の logcat は揮発だが、
  キャプチャを保存する場合は注意。
