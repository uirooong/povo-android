# トッピングの一覧と購入 — 実機調査の結果

実アカウントで叩いて確定した仕様。**金銭が動く経路**なので、確認できたことと
確認していないことを分けて書く。金額・商品 ID は実データだが、これらは全ユーザー
共通のカタログなので個人情報ではない。

---

## 1. カタログの在り処

**`GET /api/v1/quilt/page/dashboard-v2`** の **`addon-section`** タイル。

`user-plan-details-v2` ではない。名前に反してあちらは**現在契約中のものしか返さず**、
何も無いアカウントでは「現在利用中のトッピングはありません」の空状態タイルだけになる。

実測で 8 セクション・33 商品。商品はペイロード内に**2 回**現れる:

| 場所 | 内容 |
|---|---|
| `items[].data` | 一覧行用の短い表示（`id` / `name.title` / `validity.title` / `price.title`） |
| `items[].action.data.product_popup` | 確認シート用（`id` / `title` フル名称 / `price` / **`is_3ds_topping`**） |

本アプリは popup の表記を優先し、無ければタイル側にフォールバックする。
**空文字を「値なし」として扱う**必要がある — ペイロードはキー自体を省くのではなく
空文字を入れてくることがあり、null だけを見る elvis 連鎖だと名前なしの行が出る。

価格は `21,600円` や `27,500円 -> 25,100円` のように**割引込みで整形済み**。
再計算しない。povo が提示していない金額を出さないため。

### 死んでいる / 別物だった経路

| 経路 | 実測 |
|---|---|
| `GET account/addon/topup/all/get` | **HTTP 500** |
| `GET account/addon/general/all/get` | 200 だが**中身は海外ローミング設定**（`id: "roamingGroup"`）。トッピングではない |
| `GET account/addon/extra/all/get` | 200。`{calls, data, sms}` 構造だが実測では全て空配列 |

---

## 2. 購入 — `POST /v2/jp/ja/mobile/shop/orders`

**バージョンは v2。** 他の大半が使う v4 でも、`subscription/activate` の v1 でもない。

```json
{ "sku": "<product_popup.id>",
  "offer_product": null,
  "redirect_url": "https://povo.jp/success" }
```

**アカウント識別子は送らない。** トークンから引かれる。

### 応答

```json
{ "success": true,
  "result": { "challenge_url": "https://front.secure.gmopg.jp/auth/brw/callback?...",
              "order_ref": "...",
              "p_ref": "..." } }
```

- **`challenge_url` があれば 3-D Secure が必要**で、**まだ何も課金されていない**
- 無ければ（3DS 不要、または frictionless 承認）その時点で購入完了
- 実測では `order_id` は返ってこなかった（`order_ref` と `p_ref` のみ）

### `subscription/activate` は購入ではない

先に `POST /v1/jp/ja/mobile/subscription/activate` を
`{customerId, accountId, subscriptions:[{productId}]}` で叩いたところ:

```
HTTP 422  ERROR_SUBSCRIPTION_BUSINESS_VALIDATION_FAILED
[checkActivateEligibility]: No subscription to activate for the request
```

あちらは**定額パートナー連携の有効化**専用で、都度購入のトッピングは対象外。
この 422 が `shop/orders` を見つける手がかりになった。

---

## 3. 3-D Secure の扱い

`is_3ds_topping` は 33 商品中 16 件が true。ただし**これは目安でしかない**:
チャレンジを出すかは最終的に発行会社が決め、frictionless 承認なら true でも
チャレンジは出ない。**判断に使うのは応答の `challenge_url` の有無**。

チャレンジは専用画面（`ThreeDsScreen`）で開く。`PovoWebScreen` を使い回さない:

- あちらは povo ドメイン以外の描画を拒否する。3DS は決済代行から**発行銀行**へ
  正当に遷移するので、ホストを事前に決められない
- あちらは JS ブリッジ経由でアカウントのトークンを渡しうる。他社ドメインの
  ページにその経路を与えてはいけない

`ThreeDsScreen` は **javascript interface を一切入れず、トークンも識別子も渡さない**。
サーバーが返した URL を描画し、`redirect_url` への到達だけを監視する。
判定はホストとパスの一致で行う（代行会社が結果パラメータを付けるため完全一致は不可）。

---

## 4. 実機で確認したこと

| | 結果 |
|---|---|
| カタログ取得・表示 | ✅ 8 セクション 33 商品が価格つきで出る |
| 購入 POST | ✅ 200、`challenge_url` が返る |
| 3DS 画面 | ✅ 発行銀行の認証ページ（Mastercard Identity Check、認証方法の選択）が描画される |
| **中止した場合** | ✅ **課金なし・トッピング付与なし・購入履歴にも残らない** |

## 5. 確認していないこと

- **購入の完走**。3DS 認証を完了させると実際に課金されるため、通していない。
  したがって「認証成功 → `redirect_url` 到達 → 完了扱い」の経路は**未検証**
- `challenge_url` が返らない場合（3DS 不要商品 / frictionless）の完了扱いも未検証
- 自動更新トッピング（`auto_recharge` を取る系）は未対応・未検証
- 解約 `PUT /v1/.../subscription/cancel` は触っていない
