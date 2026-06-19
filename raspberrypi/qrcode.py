import cv2
import requests

from api import notify_qr_scanned

if __name__ == "__main__":
    image = cv2.imread("qrcode.png")
    if image is None:
        raise SystemExit("qrcode.png を読み込めませんでした")

    detected, decoded_objects, _, _ = cv2.QRCodeDetector().detectAndDecodeMulti(image)
    if not detected or not decoded_objects:
        raise SystemExit("QRコードを検出できませんでした")

    for connection_url in dict.fromkeys(decoded_objects):
        if not connection_url:
            continue

        print("Decoded QR Code:", connection_url)
        try:
            result = notify_qr_scanned(connection_url)
            print("スマートフォンへ読取完了を通知しました:", result)
        except requests.RequestException as error:
            print("スマートフォンへの通知に失敗しました:", error)
