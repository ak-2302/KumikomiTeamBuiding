import requests

IP_ADDRESS = "192.168.11.97"

def send_request(type):
    url = ""
    data = {}
    match type:
        case "vibrate":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"durationMs": 5000}
        case "beep":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"durationMs": 5000}
        case "notification":
            url = f"http://{IP_ADDRESS}:8080/api/{type}"
            data = {"title": "Hello", "message": "notification sample"}
    try:
        response = requests.post(url, json=data)
        response.raise_for_status()
        print("Request successful:", response.json())
    except requests.exceptions.RequestException as e:
        print("Request failed:", e)


if __name__ == "__main__":
    send_request("vibrate")
    send_request("beep")
    send_request("notification")