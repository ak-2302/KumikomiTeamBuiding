import requests


def send_request(IP_ADDRESS, type,duration=5000, title="Hello", message="notification sample"):
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