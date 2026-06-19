import cv2
import ipaddress
import sys
import os
import time
import requests
from pyzbar.pyzbar import decode as decode_qr
import re


DEFAULT_IMAGE_PATH = "/home/pi/capture.jpg"
ANALYSIS_INTERVAL_SECONDS = 2
IP_STORAGE_PATH = "/home/pi/last_ip.txt"


def resolve_cascade_path(filename):
    candidate_directories = []

    cv2_data = getattr(cv2, "data", None)
    if cv2_data is not None and hasattr(cv2_data, "haarcascades"):
        candidate_directories.append(cv2_data.haarcascades)

    cv2_module_directory = os.path.dirname(cv2.__file__)
    candidate_directories.extend([
        os.path.join(cv2_module_directory, "data"),
        "/usr/share/opencv4/haarcascades",
        "/usr/share/opencv/haarcascades",
        "/usr/local/share/opencv4/haarcascades",
    ])

    for directory in candidate_directories:
        candidate_path = os.path.join(directory, filename)
        if os.path.exists(candidate_path):
            return candidate_path

    return filename


FRONTAL_CASCADE_PATH = resolve_cascade_path("haarcascade_frontalface_default.xml")
PROFILE_CASCADE_PATH = resolve_cascade_path("haarcascade_profileface.xml")


def send_request(IP_ADDRESS, type, duration=5000, title="Hello", message="notification sample"):
    url = ""
    data = {}
    if type == "vibrate":
        url = f"http://{IP_ADDRESS}:8080/api/{type}"
        data = {"durationMs": duration}
    elif type == "beep":
        url = f"http://{IP_ADDRESS}:8080/api/{type}"
        data = {"durationMs": duration}
    elif type == "notification":
        url = f"http://{IP_ADDRESS}:8080/api/{type}"
        data = {"title": title, "message": message}
    try:
        # ターミナルがフリーズしないようにタイムアウト(3秒)を設定しています
        response = requests.post(url, json=data, timeout=3.0)
        response.raise_for_status()
        print("Request successful:", response.json())
    except requests.exceptions.RequestException as e:
        print("Request failed:", e)


def load_face_cascades():
    frontal_cascade = cv2.CascadeClassifier(FRONTAL_CASCADE_PATH)
    profile_cascade = cv2.CascadeClassifier(PROFILE_CASCADE_PATH)

    if frontal_cascade.empty() or profile_cascade.empty():
        raise FileNotFoundError("顔検知用のXMLファイルが見つかりません。")

    return frontal_cascade, profile_cascade


def analyze_face(image, frontal_cascade, profile_cascade):
    if image is None:
        print("Error: 画像が読み込めません")
        return "error"

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    frontal_faces = frontal_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50))

    if len(frontal_faces) > 0:
        print("集中: 前を向いています！")
        return "focused"

    profile_faces = profile_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50))

    flipped_gray = cv2.flip(gray, 1)
    profile_faces_flipped = profile_cascade.detectMultiScale(flipped_gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50))

    if len(profile_faces) > 0 or len(profile_faces_flipped) > 0:
        print("よそ見: 横を向いています！")
        return "distracted"

    print("警告: 顔が見えません（または下を向きすぎています）")
    return "not_detected"


def read_qr_codes(image):
    qr_codes = []

    for obj in decode_qr(image):
        qr_data = obj.data.decode("utf-8", errors="ignore")
        if qr_data:
            qr_codes.append(qr_data)

    return qr_codes


def save_ip_address(ip):
    """IPアドレスをファイルに保存する"""
    try:
        with open(IP_STORAGE_PATH, "w") as f:
            f.write(ip)
        print(f"IPアドレスをファイルに保存しました: {ip}")
    except Exception as e:
        print(f"IPアドレスの保存に失敗しました: {e}")


def load_ip_address():
    """ファイルからIPアドレスを読み込む"""
    if os.path.exists(IP_STORAGE_PATH):
        try:
            with open(IP_STORAGE_PATH, "r") as f:
                ip = f.read().strip()
                if ip:
                    return ip
        except Exception as e:
            print(f"IPアドレスの読み込みに失敗しました: {e}")
    return None


def process_image(image_path, frontal_cascade, profile_cascade):
    image = cv2.imread(image_path)

    if image is None:
        print(f"Error: 画像が読み込めません: {image_path}")
        return

    face_status = analyze_face(image, frontal_cascade, profile_cascade)
    qr_codes = read_qr_codes(image)
    ip_address = None

    for qr_data in qr_codes:
        print(f"Decoded QR Code: {qr_data}")
        if ip_address is None:
            # 正規表現で「xxx.xxx.xxx.xxx」のIPアドレス部分だけを抽出
            match = re.search(r'\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b', qr_data)
            if match:
                extracted_ip = match.group(0) # 抽出した純粋なIPアドレス
                try:
                    ipaddress.ip_address(extracted_ip) # 正しいIPかチェック
                    ip_address = extracted_ip
                    print(f"取得したIPアドレス: {ip_address}")
                    # 新しいIPアドレスが見つかったらファイルに保存する
                    save_ip_address(ip_address)
                except ValueError:
                    pass

    if not qr_codes:
        print("QRコードは検出されませんでした")
        
    # 今回QRコードが写っていなかった場合、過去に保存したIPアドレスを読み込む
    if ip_address is None:
        ip_address = load_ip_address()
        if ip_address:
            print(f"記憶されているIPアドレスを使用します: {ip_address}")
        else:
            print("警告: 有効なIPアドレスがありません（QRコードの履歴もありません）")

    # --- 通知とバイブレーションの送信処理 ---
    # よそ見(distracted) または 顔が見えない(not_detected) の場合に発動
    if (face_status == "distracted" or face_status == "not_detected") and ip_address:
        
        # 状態に合わせてメッセージを変える
        if face_status == "distracted":
            alert_title = "よそ見注意！"
            alert_message = "横を向いています。前を向いて集中しましょう。"
        else:
            alert_title = "姿勢注意！"
            alert_message = "顔が見えません。下を向きすぎていませんか？"

        print(f"スマホ {ip_address} に警告（{face_status}）を送信します")
        
        # 1. 画面への通知メッセージを送信
        send_request(
            ip_address,
            "notification",
            title=alert_title,
            message=alert_message,
        )
        
        # 通信がぶつからないように0.5秒待機
        time.sleep(0.5) 
        
        # 2. バイブレーション（振動）を送信（1000ミリ秒 = 1秒間）
        send_request(
            ip_address,
            "vibrate",
            duration=500
        )


def watch_image(image_path):
    try:
        frontal_cascade, profile_cascade = load_face_cascades()
    except FileNotFoundError as e:
        print(f"Error: {e}")
        return

    print(f"{image_path} を{ANALYSIS_INTERVAL_SECONDS}秒おきに解析します。終了するには Ctrl + C を押してください。")

    while True:
        process_image(image_path, frontal_cascade, profile_cascade)
        time.sleep(ANALYSIS_INTERVAL_SECONDS)


def run_once(image_path):
    try:
        frontal_cascade, profile_cascade = load_face_cascades()
    except FileNotFoundError as e:
        print(f"Error: {e}")
        return

    process_image(image_path, frontal_cascade, profile_cascade)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--once":
        image_file = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_IMAGE_PATH
        run_once(image_file)
    elif len(sys.argv) > 1 and not sys.argv[1].startswith("-"):
        run_once(sys.argv[1])
    else:
        watch_image(DEFAULT_IMAGE_PATH)