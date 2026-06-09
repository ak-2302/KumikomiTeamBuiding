# 組込プログラム　チーム開発androidアプリ

## テーマ
ユーザーの集中を監視するためのぬいぐるみによるアプリケーション

## 機能
- raspberry pi との通信


## 構成
- `MainActivity.java`：メイン処理
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
