# povo 側 web ページを認証済みで開く — 実機調査の結果

povo 公式アプリの「プロフィール」から開ける各ページ（お支払い方法、
メールアドレスの変更、契約管理）を本アプリからも開くために、実アカウントで
各エンドポイントを叩き、`shop.povo.jp` の SPA バンドルを読んで確定した事実を
まとめる。

調査に使ったのは debug ビルドのプロトコル検証画面（`/` 始まりの絶対パスは
`get_raw_json` に回るよう拡張した）と、公開されている JS バンデル
`https://shop.povo.jp/static/js/index.js` の静的読解。

---

## 1. プロフィールページのタイル一覧 ✅ 実装済み

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
- 同じページに氏名・郵便番号付き住所・暗証番号マスクも含まれる。本アプリはそれらを
  読まない。読むのはマスク済みカード番号と、開き先の `web_view` リンクだけ。

### `web_view` タイルの全数（実測）

| 用途 | タイル種別 | `title` | link のパス | `exit_url` |
|---|---|---|---|---|
| メールアドレスの変更 | `tile-nav-right` | `メールアドレス` | `/profile/email` | `/profileUpdateEmailSuccess` |
| お支払い方法 | `tile-credit-card` | `ご利用中のお支払い方法` | `/manage/payment-details` | `/updateCardSuccess` |
| 暗証番号 (未対応) | `tile-icon-help` | — | `/manage/confirm-pin-change` | `/managePinClose` |
| **契約管理** | `tile-nav-right` | **無し** | `/manage/order` | `/manageOrderClose` |

4 本すべて `needs_xauth: true`。

**識別はリンクのパスで行う必要がある。** タイル種別では足りない
（メールアドレスと契約管理はどちらも `tile-nav-right`）し、`title` でも足りない
（**契約管理のタイルは `title` を持たない** — ラベルは公式アプリ側が持っている）。
本アプリの `PovoWebPageKind` はパス完全一致で判定する。前方一致にしないのは、
同じページに `deeplink` として `/manage/order-history` が居るため。

`tile-icon-help`（暗証番号）は `data.help.action` にもう 1 本 `web_view` を
入れ子で持つが、それは `povo.jp/support/pin-code/` の解説記事で、遷移先ではない。
パーサはコンポーネント直下の `action` しか見ないので拾わない。

- ほかは `deeplink`（購入履歴・番号管理・設定・チュートリアル）と
  `popup`（ログアウト）で、web ページではない。

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

## 2. 認証（WebView）— ✅ 解決

**答えは「`device_id` を `auth_token` と一緒に初回リクエストのクエリに載せる」。**
Cookie・初回ヘッダ・localStorage 事前注入はいずれも公式アプリも使っていない
（公式 APK 1.70.0-JP の静的解析による否定証拠。`WebViewFragment.onViewCreated`
が `needs_xauth` のときに付ける一式）。

実際に送る一式:

```
device_id, auth_token, webview=1, reset=true,
use_native_ekyc_api=1, native=1, app_version, return_url(= exit_url)
```

`device_id` が要るのは実測とも符合する: `webfront/users/session` は
アカウント本来の `X-Deviceid` でのみ認証を通し、ランダム値だと 403 を返した。
サーバーがトークンとデバイスを紐付けている。

実機で確認済み。3 ページすべて認証済みで開き、`/web/login` へ飛ばない:

| ページ | 開いた内容 |
|---|---|
| お支払い方法 | 登録カードと、クレジットカード / あと払い（ペイディ）の選択肢 |
| メールアドレスの変更 | 「メールアドレスを変更する」入力フォーム |
| 契約管理 | 契約者名・電話番号・SIM 種別入りの申込内容確認 |

パラメータ一式はページごとに変えていない。公式アプリも遷移先ではなく
**アクションの `needs_xauth`** を見て付けているので、同じ扱いでよい。

> **契約管理は申込フローの途中に着地しうる。** 実機では「申込内容の確認」と
> 「次へ進む」ボタンが出た。押すと実際の手続き（SIM 再発行・5G SA 変更・解約/MNP）が
> 進む可能性があるため、調査では押していない。公式アプリでも同じページなので
> アプリ側で止めるものではないが、承知しておくこと。

あわせて実装したもの:

- `webfront://` スキームの傍受。web 側がトークンを更新する公式チャンネルで、
  `auth_token` を取り出して新しいセッションとして保存する。傍受しないと
  WebView が解決できないスキームでロードエラーにもなる
- JS ブリッジは `loadUrl` の前に登録（`getAppVersion` が無いと
  "Device is not supported" になる）

### 古い WebView では崩れる（アプリ側の問題ではない）

エミュレータ（Chromium 83 / 2020年6月）ではアイコンが文字に重なる。
flexbox の `gap` は Chromium 84 で入ったため、`gap` で間隔を取っている行が
重なって潰れる。テストページで実測して確認した:

```
flex gap = 0px → NOT SUPPORTED | UA Chrome 83
```

実機の WebView は Play 経由で更新されるため現行版になり、この問題は出ない。
アプリ側で回避すべきものではない。

入力はすべて `shop.povo.jp` 上で行われるので、**カード番号も新しいメールアドレスも
MNP の書類もアプリを通らない**。問題は「その SPA をログイン済み状態で開く」方法だった。

### 分かっている仕組み（解決に至る過程の記録）

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

**c) ページセッション ← 当初「本命」と考えたが、扉が違う可能性が高い**

ブートガードのコード形からこれだと考えた。

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

`localStorage.sessionToken` が SPA から見える置き場所。

### ただし、その後の実測で疑わしくなった（重要）

curl で `POST /api/v3/user-service/v4/jp/ja/webfront/users/session` を叩き、
**アカウント本来の `X-Deviceid` を付けると認証を通過**して body の検証に進むことを
確認した（`400101` → `40032 "validate-Step"` → `"validate-Action"` →
`journey` を外すと `"validate-Journey"`）。必要なのは
`session_id` / `journey` / `step`(int) / `action` の 4 つ。

しかし `step` と `action` はサーバー設定から導かれる:

```js
step   = findMatchingPageFromInputPath(path, config.pages).next
action = extractPathAfterV2(page.key)
config = appSettings["layout.page_session"][planType]
```

その設定の実体は **`/v2/*` — 新規申し込みファネル専用**だった:

```json
"page_session": { "default_redirect_page": "/v2/plan-type",
                  "pages_to_register_session": ["/v2/email-otp-verification", …] }
```

`/manage/payment-details` はこの仕組みの対象ではない。したがって
**ページセッションを作れても目的のページは開かない可能性が高く、
`/web/login` へのリダイレクトはもっと単純な「web セッション（Cookie）が無い」
判定から来ていると疑っている。**

静的読解では `isLoggedIn` の供給元まで辿れなかった（条件式の中にしか現れない）。
ここが SPA バンドルを読む限界で、次は**公式 APK の WebView 層**を読む必要がある
（`CookieManager` / `addJavascriptInterface` / `evaluateJavascript` あたり）。
調査依頼は `docs/POVO-CORE-REQUESTS.md` の項目 0 にまとめた。

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
- リンクはサーバー応答から保存しているので、**開く前にもう一度ドメインを検査**する。
  改変されたペイロードでトークン付きの WebView を別ホストへ向けられないようにする。
- 保存するのはマスク済み番号とリンクのみ。氏名・住所・暗証番号は読まない。
- 対応するのはリンクのパスが `PovoWebPageKind` に一致するものだけ。
  プロフィールページに新しいタイルが増えても、勝手に開ける先が増えることはない。
- **調査中、実アカウントの JWT が logcat に出た**（`auth_token` を含む URL が
  SPA の `console.log` に出るため）。検証端末の logcat は揮発だが、
  キャプチャを保存する場合は注意。
