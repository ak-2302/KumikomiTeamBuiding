import requests


def notify_qr_scanned(connection_url, device_name="Raspberry Pi"):
    url = f"{connection_url.rstrip('/')}/api/qr-scanned"
    response = requests.post(
        url,
        json={"deviceName": device_name},
        timeout=5,
    )
    response.raise_for_status()
    return response.json()


def send_media_command(connection_url, action):
    if action not in {"toggle", "next", "previous"}:
        raise ValueError("action must be toggle, next, or previous")

    url = f"{connection_url.rstrip('/')}/api/media/{action}"
    response = requests.post(url, timeout=5)
    response.raise_for_status()
    return response.json()


def send_request(
    IP_ADDRESS, type, duration=5000, title="Hello", message="notification sample"
):
    url = ""
    data = {}
    match type:
        case "vibrate":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"durationMs": duration}
        case "beep":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"durationMs": duration}
        case "notification":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"title": title, "message": message}
    try:
        response = requests.post(url, json=data)
        response.raise_for_status()
        print("Request successful:", response.json())
    except requests.exceptions.RequestException as e:
        print("Request failed:", e)


if __name__ == "__main__":
    IP_ADDRESS = "192.168.11.97"
    send_request(IP_ADDRESS, "vibrate")
    send_request(IP_ADDRESS, "beep")
    send_request(IP_ADDRESS, "notification")
