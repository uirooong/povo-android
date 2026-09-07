# povo-core への依頼（povo-android 側からの要望）

povo-android で実装を進める過程で、povo-core 側にあると助かるもの / 直したい
ものをまとめる。実測の根拠を添えているので、そのまま調査の出発点に使える。

---

## 1. `post_raw_json(absolute_path, body_json)` ★最優先・ブロッカー

`get_raw_json` の POST 版が欲しい。

### なぜ必要か

`build_path` は必ず `/mobile/` セグメントを挟む:

```
{prefix}/{version}/jp/{locale}/mobile/{relative_path}
```

ところが web-front 系のルートには **`mobile` が無い**。`shop.povo.jp` の SPA
バンドルから:

```js
USER_SERVICE_CREATE_PAGE_SESSION = "/v4/" + COUNTRY + "/{LOCALE}/webfront/users/session"
USER_SERVICE_UPDATE_PAGE_SESSION = "/v4/" + COUNTRY + "/{LOCALE}/webfront/users/session/update"
```

`post_json` で `webfront/users/session` を渡すと
`/v4/jp/ja/mobile/webfront/users/session` になり届かない（実測）。
GET は `get_raw_json` で回避できるが、この経路は POST しかない。

### 実測した挙動（curl、実アカウントのトークン使用）

| リクエスト | 結果 |
|---|---|
| `POST /v4/jp/ja/webfront/users/session` | **404** `no Route matched with those values` |
| `POST /api/v3/user-service/v4/jp/ja/webfront/users/session` + `X-AUTH` | **400** `code:400101` |
| 同上 + `X-Deviceid`（**ランダム値**） | **403** `code:403001` |

つまり:

- **正しいパスは user-service プレフィックス配下**
  `/api/v3/user-service/v4/jp/ja/webfront/users/session`
  （`USER_SERVICE_PREFIX` + `/v4/jp/ja/webfront/users/session`。`mobile` 無し）
- **`X-Deviceid` が検証されている。** 付けないと 400101、
  でたらめな値だと 403001 と変化した。**アカウント本来の device_id が必要**で、
  それを持っているのは povo-core / アプリだけ。curl では先に進めない
- body は `{}` / `{"journey":"onboarding"}` / `{"session_id":"<uuid>"}` の
  どれでも 400101 だったので、400101 はおそらく body ではなくヘッダ不足が原因

### 欲しいシグネチャ

```rust
/// `get_raw_json` の POST 版。ローカライズされたパス形式に当てはまらない
/// ルート（`webfront/*` は `mobile` セグメントを持たない）に届かせるため。
pub fn post_raw_json(&self, absolute_path: String, body_json: String)
    -> Result<String, PovoError>
```

`get_raw_json` と同じで、通常のヘッダ一式（`X-Deviceid` / `X-App-Version` /
`X-AUTH` / `X-USER-ID`）と `sin`/`uuid` のクエリ装飾はそのまま効かせてほしい。

### 確認してほしいこと

1. `X-Deviceid` にアカウント本来の値を入れて 200 が返るか
2. 返る `session_token`（JWT）の中身。特に **`step` クレーム**
3. `journey` の有効値。バンドルには `PAGE_SESSION_ONBOARDING_KEY="onboarding"`
   しか無いが、`/manage/payment-details` に留まるには別の値が要る可能性がある
   （`redirectToDeservedPage` が `claims.step` で遷移先を決める。詳細は
   `docs/PAYMENT-WEBVIEW-FINDINGS.md`）

---

## 2. `TelcoInfo` に `activation_date` / `initial_activation_date`

契約情報の「開通日」に使う。現在は `UserProfile` に無いため、同じ
`users?include_telco=true` を生 JSON で読み直す `ProfileParser` を
povo-android 側に置いている（`getProfileJson()`）。型付き struct に入れば不要。

`customer_name`（名義）は既にあるので、そちらは追加作業なしで使えている。

---

## 3. `get_quilt_page_json(page: String)` の型付きメソッド

quilt は `/api/v1/quilt/page/{page}` 固定で、いま povo-android 側で組み立てて
`get_raw_json` に渡している。このプロジェクトでは quilt が主要な情報源になった:

| ページ | 用途 |
|---|---|
| `user-plan-details-v2` | 有効なトッピング一覧 |
| `order-history` | 購入履歴 |
| `profile` | **お支払い方法（マスク済みカード番号 + 変更ページ URL）** |

`profile` は **APK 静的解析由来のエンドポイント一覧に載っていない**。
quilt ページ名の一覧はドキュメントに残す価値がある。

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
上記のとおり、少なくとも 4 本は実際に使えない。

---

## 5. 未踏領域: `webfront/*`

`webfront` プレフィックスは APK 由来の一覧に無く、SPA バンドルにしか出てこない。
判明しているだけで:

```
/v4/jp/{locale}/webfront/users/session
/v4/jp/{locale}/webfront/users/session/update      (ヘッダ X-SessionID)
/v4/jp/{locale}/webfront/users/pin/reset/confirm
/v4/jp/{locale}/webfront/users/pin/change/confirm
/v4/jp/{locale}/webfront/users/pin/validate
```

いずれも user-service プレフィックス配下と思われる（session で確認済み）。
ここは丸ごと未調査の領域。
