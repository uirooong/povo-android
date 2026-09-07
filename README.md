# povo マネージャー (Android)

複数の povo2.0 アカウントを 1 つのアプリでまとめて管理する Android アプリ。
API アクセスは Rust 製の [`uirooong/povo-core`](https://github.com/uirooong/povo-core)
を uniffi 経由で呼び出す。

> 非公式クライアントです。KDDI / povo / Circles.life とは無関係で、
> 利用者自身のアカウントを管理する用途を前提としています。

## できること

- **複数アカウントを並列更新** — アカウントごとに独立した `PovoClient` を持ち、
  同時実行数を 3 に制限しつつ一括取得（実測: 2 アカウントで 2.0 秒、逐次なら約 3.8 秒）
- **データ残量の一覧と内訳** — 5 つのバケット（基本 / 追加 / トッピング / ボーナス / プラス）
- **OTP ログインウィザード** — サーバーが要求する認証ステップを順に処理
- **請求タイムライン + PDF** — パスワード付き PDF を外部ビューアへ受け渡し
- **定期一括更新（WorkManager, 15 分。請求・購入履歴だけは約 1 時間に間引き）と
  ホーム画面ウィジェット（Glance）2 種**
  — 全アカウントの残量を並べる一覧型と、1 アカウントを円グラフで見る 1×1 型
  （置いたときにアカウントを選ぶ。タイルの実寸に合わせて円と文字が伸縮する）
- **アプリの設定** — テーマ切り替え（システム / ライト / ダーク / povo カラー）、
  バージョン表示、GitHub Releases からのアプリ内更新
- トークンは Android Keystore で暗号化して永続、期限切れ時のみ自動更新

## 状態

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | プロジェクト土台・Rust ビルド配線 | ✅ |
| Phase 1 | 実機プロトコル検証 | ✅ 完了（[結果](docs/PHASE1-FINDINGS.md)） |
| Phase 2 | パーサ・Room・リポジトリ | ✅ |
| Phase 3 | ログインウィザード | ✅ |
| Phase 4 | ダッシュボード / 詳細 | ✅ |
| Phase 5 | 請求・PDF | ✅ |
| Phase 6 | WorkManager / ウィジェット | ✅ |

実機（エミュレータ API 30）で 2 アカウント同時運用まで確認済み。

## 必要なもの

| ツール | バージョン | 備考 |
|---|---|---|
| Rust | 1.98+ | Android ターゲット 3 種を追加 |
| cargo-ndk | 4.x | `cargo install cargo-ndk` |
| Android SDK | Platform 37 / Build-Tools 36 | |
| Android NDK | **28.2.13676358** | `core-povo/build.gradle.kts` で固定 |
| JDK | 21+（Android Studio 同梱 JBR で可） | |

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
sdkmanager "ndk;28.2.13676358" "platforms;android-37" "build-tools;36.0.0"
```

`local.properties`（Windows はドライブのコロンをエスケープ）:

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

## ビルド

```bash
git clone --recurse-submodules <this repo>
./gradlew :app:installDebug          # 初回は Rust のクロスコンパイルで数分
./gradlew test                       # パーサとウィジェットのユニットテスト
```

### ABI の切り替え

デバッグの往復を速くするため既定は **arm64-v8a のみ**。

```bash
./gradlew :app:installDebug -Ppovo.abis=x86_64                      # エミュレータ
./gradlew :app:assembleRelease -Ppovo.abis=arm64-v8a,armeabi-v7a,x86_64
./gradlew :app:installDebug -Ppovo.skipRustBuild=true               # Kotlin だけ触るとき
```

### リリースビルドの署名

`keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）
を置くとそれを使う。無い場合は**デバッグ鍵で署名**する。
これは R8 で難読化したビルドをローカルで実機確認するための措置で、
配布前には必ず本物の鍵を用意すること。

## 構成

```
povo-android/
├── povo-core/          git submodule — Rust コア
├── buildSrc/           cargo-ndk と uniffi-bindgen を叩く Gradle タスク
├── core-povo/          :core-povo — uniffi バインディング / suspend ラッパ / パーサ
│   ├── json/           レスポンスパーサ（実レスポンスに対するテスト付き）
│   ├── model/          PlanUsage, Bills, Money
│   ├── SecureBlobStore Keystore AES/GCM
│   └── SessionStore    アカウントごとのトークン
├── app/                :app — Compose UI / Room / WorkManager / Glance
│   ├── data/           AccountRepository（並列更新の中核）+ Room
│   ├── ui/             accounts / detail / login
│   ├── work/           RefreshWorker
│   ├── widget/         Glance ウィジェット（一覧型 / 円グラフ型 + アカウント選択画面）
│   └── devtools/       プロトコル検証画面（デバッグビルドのみ）
└── docs/
    ├── PHASE1-FINDINGS.md   実機で判明した API の挙動
    └── samples/             マスク済みの実レスポンス（テストのフィクスチャ）
```

### マルチアカウントの設計

`PovoClient` は uniffi オブジェクトで、`device_id` / `auth_token` / `sin` を
**インスタンスごとに** Rust 側の Mutex で保持する。したがって
**アカウント 1 つ = クライアント 1 つ**とすれば、並列更新しても混線しない。
これは実機で検証済み（[結果](docs/PHASE1-FINDINGS.md)）。

povo-core の全メソッドは同期ブロッキング（`ureq`）なので、
`PovoAccountClient` が `Dispatchers.IO` 上の `suspend fun` に包んでいる。

## 開発用のプロトコル検証画面

デバッグビルドのみ、一覧画面の ⋮ から開ける。
任意のエンドポイントを叩き、生 JSON をファイルに書き出せる:

```bash
adb exec-out run-as jp.povo.manager cat files/captures/spike-log.txt > capture.txt
```

エンドポイントは予告なく変わるので、壊れたときの一次調査はここから。

## povo-core への変更

Android 対応で必要になった修正・追加は upstream (`ec1574c`) に反映済み。
経緯と全項目は [docs/POVO-CORE-CHANGES.md](docs/POVO-CORE-CHANGES.md)。

主なもの: `PovoError::Api` のフィールド名衝突（uniffi の生成コードと衝突して
Kotlin がコンパイル不能だった）、非ローカライズ経路の `get_raw_json()`、
`get_bills_info()` が常に空だった件、PDF パスワードの docstring 誤り、
`expires_at()`、エラー本文の `result` エンベロープ。

## 踏んだ落とし穴

**1. uniffi バインディングは strip されたライブラリからは生成できない**

povo-core の `[profile.release]` は `strip = true` で、uniffi が `--library`
モードで読む `UNIFFI_META_*` シンボルごと削ってしまう。strip 済みの `.so` を
渡すと **exit 0 のまま 1 ファイルも出力せず終わる**。そのため
`UniffiBindgenTask` は**ホスト向け debug ビルドの cdylib** を渡している。
（povo-core の BUILDING.md にも注意書きが入った。）

**2. Quilt ページはローカライズされていない**

`{prefix}/{version}/jp/{locale}/mobile/{path}` の形ではなく
`/api/v1/quilt/page/{page}` に直接ある。通常の経路では必ず 404 になるため
povo-core の `get_raw_json()` を使う。**契約中トッピングと購入履歴は
この経路にしか無い。**

**3. AGP 9 の新 DSL**

- `org.jetbrains.kotlin.android` は**適用してはいけない**（AGP 内蔵、KGP 2.2.10 固定）
- `android.sdkDirectory` が無いので `local.properties` を自前で読む
- `buildConfig` / `resValues` は既定 off
- KSP 2.2.10 のために `android.disallowKotlinSourceSets=false` が要る

**4. Configuration cache**

タスククラスを `build.gradle.kts` に直接書くとスクリプト参照を掴んで
シリアライズできない → `buildSrc/` へ。同じ理由で `providers...map { }` を
持つ Provider をタスク入力にできないので、SDK パスは構成時に String へ解決。

**5. R8 は JNA ブリッジを消す**

JNA はリフレクションでネイティブシンボルに束ねるため、R8 から見ると未使用。
keep ルール無しだとリリースビルドは**通ってインストールもできるが、
ネイティブに触れた瞬間に落ちる**。`app/proguard-rules.pro` 参照。
難読化ビルドを実機で起動して確認済み。

## 配布とビルド (GitHub Actions)

Play ストアには出さないので、APK は GitHub Releases 経由で配る。

| トリガー | ビルド | 成果物の置き場所 |
|---|---|---|
| push / PR | debug | Actions の run に artifact として添付 |
| リリース作成 | release（固定鍵で署名） | そのリリースの assets |

debug をそのまま配るのは意図的で、プロトコル検証画面が入っているのはこちらだけ
（release ビルドでは `BuildConfig.DEBUG` が定数 false になるため、画面ごと R8 に
落とされて APK に残らない）。

### 署名鍵の設定

**リリース鍵は固定でなければならない。** Android は署名が変わったアプリの上書き
インストールを拒否するので、鍵を変えると利用者はアンインストール（＝ログイン情報の
消失）を強いられる。鍵はリポジトリに置かず、以下の Secrets に入れる:

| Secret | 中身 |
|---|---|
| `POVO_KEYSTORE_BASE64` | キーストア (`.jks`) を base64 化した文字列 |
| `POVO_KEYSTORE_PASSWORD` | キーストアのパスワード |
| `POVO_KEY_ALIAS` | 鍵のエイリアス |
| `POVO_KEY_PASSWORD` | 鍵のパスワード |

リリースワークフローは 4 つすべてが揃っていないとビルド前に失敗する。debug 鍵への
フォールバックはローカル検証専用で、そのまま配布すると上記の問題を起こすため。

ローカルで本番署名を試すときは、リポジトリ直下に `keystore.properties`
（`.gitignore` 済み）を置く:

```properties
storeFile=/absolute/path/to/povo-release.jks
storePassword=...
keyAlias=povo-release
keyPassword=...
```

### 更新チェック先

`gradle.properties` の `povo.updateRepo`（既定 `uirooong/povo-android`）を
`BuildConfig.UPDATE_REPO` として埋め込み、アプリ内更新は
`https://api.github.com/repos/<repo>/releases/latest` を見る。fork する場合は
このプロパティだけ変えればよい。
