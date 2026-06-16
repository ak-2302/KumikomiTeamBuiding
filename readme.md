# 組込プログラム　チーム開発androidアプリ

## テーマ
ユーザーの集中を監視するためのぬいぐるみによるアプリケーション

## 機能
- raspberry pi との通信


## 構成
- `MainActivity.java`：メイン処理
- `HttpAnalyzer.java`：Raspberry Pi からの HTTP リクエストを受信
- `myModule.java`：ライブラリ
  - function `beep`：端末から音を鳴らす関数
  - function `vibrate`：端末を振動をする関数
  - function `notification`：端末に通知を表示する関数
- `Util.java`：ユーティリティクラス

## myModule の使用例

```java
myModule.beep();
myModule.vibrate(this);
myModule.notification(this, "集中時間", "姿勢を確認してください");
```

`beep(int)` と `vibrate(Context, long)` を使用すると、動作時間をミリ秒で指定できます。
各処理の成否を確認したい場合、`vibrate` と `notification` の戻り値を使用してください。

## 必要な権限
- `android.permission.INTERNET`
- `android.permission.VIBRATE`
- `android.permission.POST_NOTIFICATIONS`

Android 13（API 33）以降では、通知を表示する前に
`POST_NOTIFICATIONS` 権限を Activity から実行時に要求してください。

## Raspberry Pi からの HTTP アクセス

アプリの起動中、Android 端末は TCP ポート `8080` で HTTP リクエストを待ち受けます。
Raspberry Pi と Android 端末を同じネットワークに接続し、Android 端末の IP アドレスへ
JSON 形式の POST リクエストを送信してください。

### データを送信

```bash
curl -X POST http://ANDROID_IP:8080/api/data \
  -H 'Content-Type: application/json' \
  -d '{"data":{"focus":80,"status":"working"}}'
```

### 音を鳴らす

```bash
curl -X POST http://ANDROID_IP:8080/api/beep \
  -H 'Content-Type: application/json' \
  -d '{"durationMs":300}'
```

### 端末を振動させる

標準の振動時間で実行する場合は、空の POST リクエストでも動作します。

```bash
curl -X POST http://ANDROID_IP:8080/api/vibrate
```

振動時間を指定する場合は `durationMs` を送信してください。

```bash
curl -X POST http://ANDROID_IP:8080/api/vibrate \
  -H 'Content-Type: application/json' \
  -d '{"durationMs":500}'
```

### 通知を送信

```bash
curl -X POST http://ANDROID_IP:8080/api/notification \
  -H 'Content-Type: application/json' \
  -d '{"title":"集中時間","message":"姿勢を確認してください"}'
```

`durationMs` は省略可能です。HTTP レスポンスは処理の受付結果を JSON で返します。
