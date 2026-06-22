import ipaddress
import json
import os
import sys
import time
from urllib.parse import urlparse

import cv2
import requests
from api import notify_qr_scanned
from pyzbar.pyzbar import decode as decode_qr

from discord_webhook import send_message

HOME_DIRECTORY = os.path.expanduser("~")
DEFAULT_IMAGE_PATH = os.path.join(HOME_DIRECTORY, "capture.jpg")
IP_STORAGE_PATH = os.path.join(HOME_DIRECTORY, "last_ip.txt")
STATE_STORAGE_PATH = os.path.join(HOME_DIRECTORY, "face_analyzer_state.json")
ANALYSIS_INTERVAL_SECONDS = 2
ALERT_COOLDOWN_SECONDS = 60
QR_NOTIFICATION_COOLDOWN_SECONDS = 30
ALERT_STATES = {"distracted", "not_detected"}


def resolve_cascade_path(filename):
    candidate_directories = []

    cv2_data = getattr(cv2, "data", None)
    if cv2_data is not None and hasattr(cv2_data, "haarcascades"):
        candidate_directories.append(cv2_data.haarcascades)

    cv2_module_directory = os.path.dirname(cv2.__file__)
    candidate_directories.extend(
        [
            os.path.join(cv2_module_directory, "data"),
            "/usr/share/opencv4/haarcascades",
            "/usr/share/opencv/haarcascades",
            "/usr/local/share/opencv4/haarcascades",
        ]
    )

    for directory in candidate_directories:
        candidate_path = os.path.join(directory, filename)
        if os.path.exists(candidate_path):
            return candidate_path

    return filename


FRONTAL_CASCADE_PATH = resolve_cascade_path("haarcascade_frontalface_default.xml")
PROFILE_CASCADE_PATH = resolve_cascade_path("haarcascade_profileface.xml")


def send_request(
    connection_url,
    request_type,
    duration=5000,
    title="Hello",
    message="notification sample",
    value=None,
    quiet=False,
):
    if request_type in {"vibrate", "beep"}:
        data = {"durationMs": duration}
    elif request_type == "notification":
        data = {"title": title, "message": message}
    elif request_type == "data":
        data = {"type": "focus", "value": value}
    else:
        raise ValueError(f"未対応のリクエストです: {request_type}")

    url = f"{connection_url.rstrip('/')}/api/{request_type}"
    try:
        response = requests.post(url, json=data, timeout=3.0)
        response.raise_for_status()
        if not quiet:
            print("Request successful:", response.json())
        return True
    except requests.exceptions.RequestException as error:
        if not quiet:
            print("Request failed:", error)
        return False


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
    frontal_faces = frontal_cascade.detectMultiScale(
        gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50)
    )
    if len(frontal_faces) > 0:
        print("集中: 前を向いています！")
        return "focused"

    profile_faces = profile_cascade.detectMultiScale(
        gray, scaleFactor=1.1, minNeighbors=5, minSize=(50, 50)
    )
    profile_faces_flipped = profile_cascade.detectMultiScale(
        cv2.flip(gray, 1), scaleFactor=1.1, minNeighbors=5, minSize=(50, 50)
    )
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


def parse_connection_url(qr_data):
    try:
        parsed = urlparse(qr_data.strip())
        if parsed.scheme != "http" or not parsed.hostname:
            return None
        ipaddress.ip_address(parsed.hostname)
        port = parsed.port or 8080
        return f"http://{parsed.hostname}:{port}"
    except ValueError:
        return None


def save_ip_address(ip_address):
    try:
        with open(IP_STORAGE_PATH, "w", encoding="utf-8") as ip_file:
            ip_file.write(ip_address)
        print(f"IPアドレスをファイルに保存しました: {ip_address}")
    except OSError as error:
        print(f"IPアドレスの保存に失敗しました: {error}")


def load_ip_address():
    try:
        with open(IP_STORAGE_PATH, "r", encoding="utf-8") as ip_file:
            ip_address = ip_file.read().strip()
        ipaddress.ip_address(ip_address)
        return ip_address
    except (OSError, ValueError):
        return None


def load_state():
    try:
        with open(STATE_STORAGE_PATH, "r", encoding="utf-8") as state_file:
            state = json.load(state_file)
        return state if isinstance(state, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as error:
        print(f"状態ファイルの読み込みに失敗しました: {error}")
        return {}


def save_state(state):
    temporary_path = f"{STATE_STORAGE_PATH}.tmp"
    try:
        with open(temporary_path, "w", encoding="utf-8") as state_file:
            json.dump(state, state_file, ensure_ascii=False)
        os.replace(temporary_path, STATE_STORAGE_PATH)
    except OSError as error:
        print(f"状態ファイルの保存に失敗しました: {error}")


def load_connection_url(state):
    saved_url = state.get("connection_url")
    if isinstance(saved_url, str):
        parsed_url = parse_connection_url(saved_url)
        if parsed_url:
            return parsed_url

    legacy_ip = load_ip_address()
    return f"http://{legacy_ip}:8080" if legacy_ip else None


def state_timestamp(state, key):
    value = state.get(key, 0)
    return value if isinstance(value, (int, float)) else 0


def should_send_alert(face_status, state, now):
    if face_status not in ALERT_STATES:
        return False
    return (
        state.get("face_status") not in ALERT_STATES
        or now - state_timestamp(state, "last_alert_at") >= ALERT_COOLDOWN_SECONDS
    )


def process_qr_codes(qr_codes, state, now):
    connection_url = None
    for qr_data in qr_codes:
        print(f"Decoded QR Code: {qr_data}")
        if connection_url is None:
            connection_url = parse_connection_url(qr_data)

    if connection_url is None:
        return None

    hostname = urlparse(connection_url).hostname
    print(f"取得した接続先: {connection_url}")
    save_ip_address(hostname)
    state["connection_url"] = connection_url

    notification_due = (
        connection_url != state.get("last_qr_url")
        or now - state_timestamp(state, "last_qr_notified_at")
        >= QR_NOTIFICATION_COOLDOWN_SECONDS
    )
    if notification_due:
        state["last_qr_url"] = connection_url
        state["last_qr_notified_at"] = now
        try:
            result = notify_qr_scanned(connection_url)
            print("スマートフォンへQR読取完了を通知しました:", result)
        except requests.exceptions.RequestException as error:
            print("スマートフォンへのQR読取通知に失敗しました:", error)

    return connection_url


def send_focus_alert(face_status, connection_url):
    if face_status == "distracted":
        alert_title = "よそ見注意！"
        alert_message = "横を向いています。前を向いて集中しましょう。"
    else:
        alert_title = "姿勢注意！"
        alert_message = "顔が見えません。下を向きすぎていませんか？"

    print(f"スマホ {connection_url} に警告（{face_status}）を送信します")
    send_request(
        connection_url,
        "notification",
        title=alert_title,
        message=alert_message,
    )
    time.sleep(0.5)
    send_request(connection_url, "vibrate", duration=500)

    send_message(
       message="",
       has_embed=True, 
       embed_title=alert_title, 
       embed_description=alert_message, 
       embed_color=0x123456
       ) 


def process_image(image_path, frontal_cascade, profile_cascade):
    image = cv2.imread(image_path)
    if image is None:
        print(f"Error: 画像が読み込めません: {image_path}")
        return

    face_status = analyze_face(image, frontal_cascade, profile_cascade)
    qr_codes = read_qr_codes(image)
    state = load_state()
    now = time.time()
    connection_url = process_qr_codes(qr_codes, state, now)

    if not qr_codes:
        print("QRコードは検出されませんでした")

    if connection_url is None:
        connection_url = load_connection_url(state)
        if connection_url:
            print(f"記憶されている接続先を使用します: {connection_url}")
        else:
            print("警告: 有効な接続先がありません（QRコードの履歴もありません）")

    if connection_url and face_status != "error":
        send_request(
            connection_url,
            "data",
            value=face_status,
            quiet=True,
        )

    if connection_url and should_send_alert(face_status, state, now):
        state["last_alert_at"] = now
        send_focus_alert(face_status, connection_url)

    state["face_status"] = face_status
    save_state(state)


def watch_image(image_path):
    try:
        frontal_cascade, profile_cascade = load_face_cascades()
    except FileNotFoundError as error:
        print(f"Error: {error}")
        return

    print(
        f"{image_path} を{ANALYSIS_INTERVAL_SECONDS}秒おきに解析します。"
        "終了するには Ctrl + C を押してください。"
    )
    while True:
        process_image(image_path, frontal_cascade, profile_cascade)
        time.sleep(ANALYSIS_INTERVAL_SECONDS)


def run_once(image_path):
    try:
        frontal_cascade, profile_cascade = load_face_cascades()
    except FileNotFoundError as error:
        print(f"Error: {error}")
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
