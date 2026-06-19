# 組込プログラム　チーム開発androidアプリ

## テーマ
ユーザーの集中を監視するためのぬいぐるみによるアプリケーション

## 機能
- raspberry pi との通信
- Android 端末の接続先をテキストと QR コードで表示
- Raspberry Pi による QR コード読取完了の通知と画面遷移


## 構成
- `MainActivity.java`：メイン処理
- `ScanCompleteActivity.java`：QR コード読取完了画面
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

## Raspberry Pi の環境構築

Raspberry Pi OS 64-bit（Bookworm以降）と、`/dev/video0` として認識される
USBカメラを想定しています。Bookworm以降では `pip` をシステムPythonへ直接実行せず、
仮想環境を使用します。

### OSパッケージ

```bash
sudo apt update
sudo apt install -y \
  build-essential \
  libv4l-dev \
  libzbar0 \
  python3-opencv \
  python3-venv \
  v4l-utils
```

`libv4l-dev` は `camera.c` のビルド、`libzbar0` はQRコード読取、
`python3-opencv` は画像解析に使用します。

### Python仮想環境

リポジトリを取得し、`raspberrypi` ディレクトリで環境を作成します。
APTでインストールしたOpenCVを参照するため、`--system-site-packages` が必要です。

```bash
git clone https://github.com/ak-2302/KumikomiTeamBuiding.git
cd KumikomiTeamBuiding/raspberrypi

python3 -m venv --system-site-packages .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
```

依存関係を確認します。

```bash
.venv/bin/python -c "import cv2, requests; from pyzbar.pyzbar import decode; print('OK')"
```

### カメラプログラムのビルドと実行

```bash
v4l2-ctl --list-devices
gcc -O2 -Wall -Wextra -o camera camera.c
./camera
```

カメラを開く権限がない場合は、実行ユーザーを `video` グループへ追加してから
ログアウト・ログインしてください。

```bash
sudo usermod -aG video "$USER"
```

`camera` は `$HOME` に `capture.jpg`、`last_ip.txt`、
`face_analyzer_state.json` を作成します。

## Raspberry Pi からの HTTP アクセス

アプリの起動中、Android 端末は TCP ポート `8080` で HTTP リクエストを待ち受けます。
Raspberry Pi と Android 端末を同じネットワークに接続し、Android 端末の IP アドレスへ
JSON 形式の POST リクエストを送信してください。

### データを送信

`type` にデータの種類、`value` に値を指定します。
`value` は数値、文字列、真偽値、JSON オブジェクトなどを送信できます。

```bash
curl -X POST http://ANDROID_IP:8080/api/data \
  -H 'Content-Type: application/json' \
  -d '{"type":"time","value":10}'
```

```bash
curl -X POST http://ANDROID_IP:8080/api/data \
  -H 'Content-Type: application/json' \
  -d '{"type":"focus","value":{"score":80,"status":"working"}}'
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

### 音楽を操作

現在アクティブな MediaSession にメディアキーを送り、音楽アプリを操作します。
再生側のアプリが MediaSession に対応している必要があります。
初回利用時はAndroidアプリの「通知アクセス設定」を開き、
`KumikomiTeamBuiding` の通知へのアクセスを許可してください。

権限がない場合は `403 notification access is required`、Spotifyなどの再生セッションが
見つからない場合は `409 no active media session found` を返します。

再生と一時停止を切り替えます。

```bash
curl -X POST http://ANDROID_IP:8080/api/media/toggle
```

次の曲へ進みます。

```bash
curl -X POST http://ANDROID_IP:8080/api/media/next
```

前の曲へ戻ります。

```bash
curl -X POST http://ANDROID_IP:8080/api/media/previous
```

Raspberry Pi の Python コードからは `send_media_command` を使用できます。

```python
from api import send_media_command

connection_url = "http://ANDROID_IP:8080"
send_media_command(connection_url, "toggle")
send_media_command(connection_url, "next")
send_media_command(connection_url, "previous")
```

### QR コードの読取完了を通知

Android アプリのメイン画面には、接続先 URL がテキストと QR コードで表示されます。
Raspberry Pi が QR コードを読み取ると、QR コード内の URL に対して
`POST /api/qr-scanned` を送信します。

```bash
curl -X POST http://ANDROID_IP:8080/api/qr-scanned \
  -H 'Content-Type: application/json' \
  -d '{"deviceName":"Raspberry Pi"}'
```

`deviceName` は省略可能で、省略時は `Raspberry Pi` になります。
リクエストを受信すると、Android 端末へ読取完了通知を表示し、
アプリを「読み取りました」画面へ遷移させます。通知をタップした場合も同じ画面を開きます。

通常のカメラ監視では `face_analyzer.py` が撮影画像からQRコードを読み取り、
接続先の保存と読取完了 API の呼び出しを行います。`qrcode.py` は画像単体での確認用です。
同じQRコードの読取完了通知は30秒間抑制されます。

集中警告は、集中状態からよそ見・顔未検出へ変化した時に送信されます。
注意状態が継続している場合の再通知間隔は60秒です。状態は
`$HOME/face_analyzer_state.json` に保存されるため、2秒ごとに解析プロセスが
起動し直されても連続通知されません。
