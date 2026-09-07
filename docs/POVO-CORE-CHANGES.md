# povo-core に必要な変更（指示書）

> **ステータス: 全項目が upstream に反映されました。**
> povo-core `ec1574c` "Fix binding-breaking error field, add non-localized route access"
> で、A の 3 件と B の提案 6 件すべてが取り込まれています。
> submodule は `6a100a0` を指しており、ローカル改変はありません。
> 以下は経緯の記録として残します。

povo-android を作る過程で判明した povo-core の問題と提案。
対象: `crates/povo-core/src/`

---

## A. 必須だったもの（1 と 2 は無いとビルドすら通りません）

### 1. 【必須】`PovoError::Api` の `message` フィールドをリネーム

**ファイル:** `src/error.rs`
**症状:** 生成された Kotlin が**コンパイルできない**。

uniffi 0.28 の Kotlin バックエンドは、各エラークラスに Display 文字列を返す
`override val message` を生成します。Rust 側の variant に `message` という
フィールドがあると、コンストラクタプロパティと衝突して
*Conflicting declarations* になります。

```diff
-    #[error("api error (status {status}): {message}")]
+    #[error("api error (status {status}): {server_message}")]
     Api {
         status: u16,
-        message: String,
+        server_message: String,
```

`From<ureq::Error>` の構築側も同様に `server_message:` へ。

**影響:** Windows / iOS の呼び出し側でフィールド名の変更が要ります。
Swift でも同じ衝突が起きる可能性が高いので、どのみち直す価値があります。

### 2. 【必須】`get_raw_json()` — 非ローカライズ経路へのアクセス

**ファイル:** `src/client.rs`
**症状:** Quilt ページに**到達できない**（必ず 404）。

`build_path()` は常に `{prefix}/{version}/jp/{locale}/mobile/{path}` を組み立てます。
ところが Quilt ページはローカライズされておらず、
`/api/v1/quilt/page/{page}` に直接あります。
そのため `get_json()` では構造的に叩けません。

```rust
pub fn get_raw_json(&self, absolute_path: String) -> Result<String, PovoError>
```

パスをそのまま使い、ヘッダ・認証・`sin`/`uuid` の付与ルール（quilt では抑制）は
従来どおり適用します。

**なぜ重要か:** **契約中のトッピング一覧は Quilt ページからしか取れません。**
静的解析が示していた `account/usage/plan/get` の `addons_subscribed` は
実レスポンスに存在せず、`account/plan/details/get` と
`account/addon/topup/all/get` はどちらも HTTP 500 です。

### 3. `set_extra_header()` — 任意ヘッダの追加

**ファイル:** `src/client.rs`

povo-core は自前でパスを組むため `VLI-*` ヘッダを送らず、
デコンパイル結果を根拠に `Accept` / `Accept-Language` も送りません。
その判断自体は妥当ですが、**サーバーがヘッダで分岐していないことを検証する手段が
無い**のが問題でした。

```rust
pub fn set_extra_header(&self, name: String, value: String)
```

**検証結果:** povo-native と同じヘッダ一式（`Accept`, `Accept-Language`,
`VLI-Version`, `VLI-Localize`）を送っても `telco/dashboard` の
HTTP 500 は変わりませんでした。したがって povo-android は既定で何も追加しません
（`PovoProtocol.EXTRA_HEADERS` は空）。仕組みだけ残しています。

---

## B. 提案（すべて反映済み）

### 4. `get_bills_info()` が常に空配列を返す ✅ 修正済み

**ファイル:** `src/client.rs:434-455`

```rust
let entries = value.get("bills").or_else(|| value.get("result"))
```

`layout/bills/info` はトップレベルに `bills` も `result` 配列も返しません。
実際のキーは:

```
au_services_status, banner, billing, code, instant_charges, past_bills, upcoming_bill
```

請求は **`past_bills.list[]`** にあります。実機で確認済み（0 件が返る）。

**提案:** `past_bills.list[]` を読み、`upcoming_bill.list[]` と
`instant_charges.list[]` も拾う。あるいは、誤解を招くので
**このメソッドを削除**して生 JSON を返す方針に統一する。

→ 修正され、`section` 付きで past/upcoming/instant の 3 セクションを返すようになりました。
povo-android の UI は金額・状態・日時も要るため引き続き Kotlin の `BillsParser` を使いますが、
型付きメソッドも正しく動くようになっています。

### 5. `download_bill_pdf()` の docstring が誤り ✅ 修正済み

**ファイル:** `src/client.rs:457-461`

> The password is the account holder's birth date as `YYYYMMDD`

実際の暗号化 PDF で検証した結果、**`YYYY-MM-DD`（ハイフン付き）が正解**で、
`YYYYMMDD` では開きません。

| パスワード | 結果 |
|---|---|
| `YYYY-MM-DD` | ✅ 復号成功 |
| `YYYYMMDD` | ❌ 失敗 |

docstring の修正だけで済みます。

### 6. `jwt::claim()` が数値クレームを読めない ✅ `expires_at()` 追加

**ファイル:** `src/jwt.rs:11-18`

```rust
value.get(name)?.as_str().map(str::to_string)
```

`as_str()` なので `exp`（数値）が常に `None` になります。
`exp` はトークン更新の判断に必須（発行から 20 分で失効、期限前に更新すると
同じ JWT が返る）なので、ホスト側で JWT を再デコードする羽目になります。

**提案:** `pub fn expires_at(&self) -> Option<i64>` を `PovoClient` に生やすか、
`claim()` を数値も文字列化して返すようにする。

→ `PovoClient::expires_at()` が追加されました。
povo-android は保存済みトークン文字列（クライアント未生成）に対しても判定が要るため
`core-povo/Jwt.kt` を残しています。

### 7. `panic = "abort"` を入れないこと ✅ 現状維持で正しい

`BUILDING.md` に既に書かれていますが、リリースプロファイルに
`panic = "abort"` を足すと FFI 境界での panic 変換が壊れます。現状のままで正しいです。

### 8. `strip = true` と uniffi バインディング生成 ✅ BUILDING.md に追記済み

`[profile.release]` の `strip = true` は、uniffi が `--library` モードで読む
`UNIFFI_META_*` シンボルごと削ります。strip 済みの `.so` を bindgen に渡すと
**exit 0 のまま 1 ファイルも出力せずに終わります**（エラーになりません）。

povo-android はホスト向け debug ビルドの cdylib を bindgen に渡して回避しています。
**BUILDING.md にこの落とし穴を書き足すことを推奨します** —
記載どおり Android の `.so` を渡すと無言で失敗します。

### 9. Android 向け uniffi 設定を同梱 ✅ `crates/povo-core/uniffi.toml` 追加済み

生成される Kotlin は既定で `java.lang.ref.Cleaner`（API 33+）を参照するため、
minSdk 26 のプロジェクトで lint の `NewApi` エラーになります。
実行時にはフォールバックするので動作は正しいのですが、参照自体が残ります。

`uniffi.toml` を置いておくと利用側が楽です:

```toml
[bindings.kotlin]
android = true
android_cleaner = true
```

→ upstream に入ったため、povo-android 側のローカル `uniffi.toml` と `--config` は削除しました。
bindgen が `--library` モードでもクレート同梱の toml を自動で読みます。

---

## 実機で確認したエンドポイントの状況（参考）

| エンドポイント | 結果 |
|---|---|
| `POST users/login/action` / `otp` / `users/auth` | ✅ captcha 不要・PIN 無し |
| `GET users/token` | ✅ 期限切れ後も更新可・有効期間ちょうど 1200 秒 |
| `GET users?include_telco=true` | ✅ |
| `GET account/usage/plan/get` | ✅ 値は**小数**、単位 KB・1024 進 |
| `GET layout/bills/info` | ✅（型付きメソッドは空、上記 4 参照） |
| `GET billing/bills/download/{billId}` | ✅ パスワードは `YYYY-MM-DD` |
| `GET account/referral/code/get` | ✅ |
| **`GET /api/v1/quilt/page/user-plan-details-v2`** | ✅ **契約中トッピングはここだけ** |
| **`GET /api/v1/quilt/page/order-history`** | ✅ **購入履歴はここだけ** |
| `GET account/plan/details/get` | ❌ HTTP 500 `error_db_transaction` |
| `GET account/usage/data/details/get` | ❌ HTTP 491 `error_not_found_message` |
| `GET account/addon/topup/all/get` | ❌ HTTP 500 `error_system_issue` |
| `POST telco/dashboard` | ❌ HTTP 500 `error_code_common`（全カード / boost のみ両方） |
| `GET telco/dashboard` | ❌ HTTP 404（POST 専用） |

エラー本文の形が 1 種類ではない点にも注意。
401 は `{"success":false,"result":{"message":"Token Expired"}}` で返ります。

→ `error_envelope()` が `error` と `result` の両方を剥がすようになったので、
povo-android 側の暫定アンラップ処理は削除しました。
