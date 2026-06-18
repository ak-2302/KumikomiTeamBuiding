## QRコードの読み取りサンプルコード
## これでスマホのIPアドレスを読み取ってhttpリクエストをPOSTする想定。

import cv2

if __name__ == "__main__":
    image = cv2.imread("qrcode.png")
    decoded_objects = cv2.QRCodeDetector().detectAndDecodeMulti(image)[1]
    for obj in decoded_objects:
        print("Decoded QR Code:", obj)
