#!/usr/bin/env python3

import ipaddress
import json
import os
import time
from pathlib import Path
from urllib.parse import urlparse

import requests

from api import send_media_command
from vendor.DFRobot_PAJ7620U2 import DFRobot_PAJ7620U2


STATE_PATH = Path.home() / "face_analyzer_state.json"
LEGACY_IP_PATH = Path.home() / "last_ip.txt"
NORMAL_COOLDOWN_SECONDS = 0.8
FORWARD_LOCKOUT_SECONDS = 1.5


def normalize_connection_url(value):
    if not isinstance(value, str):
        return None

    try:
        parsed = urlparse(value.strip())
        if parsed.scheme != "http" or not parsed.hostname:
            return None
        ipaddress.ip_address(parsed.hostname)
        return f"http://{parsed.hostname}:{parsed.port or 8080}"
    except ValueError:
        return None


def load_connection_url():
    environment_url = normalize_connection_url(os.environ.get("ANDROID_BASE_URL"))
    if environment_url:
        return environment_url

    try:
        state = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        state_url = normalize_connection_url(state.get("connection_url"))
        if state_url:
            return state_url
    except (FileNotFoundError, OSError, json.JSONDecodeError, AttributeError):
        pass

    try:
        legacy_ip = LEGACY_IP_PATH.read_text(encoding="utf-8").strip()
        ipaddress.ip_address(legacy_ip)
        return f"http://{legacy_ip}:8080"
    except (FileNotFoundError, OSError, ValueError):
        return None


def dispatch_media_command(command):
    connection_url = load_connection_url()
    if connection_url is None:
        print("media command ignored: Android接続先がありません")
        return False

    try:
        result = send_media_command(connection_url, command)
        print(f"sent: {command} -> {result}")
        return True
    except requests.exceptions.RequestException as error:
        print(f"media command failed: {error}")
        return False


def initialize_sensor():
    while True:
        try:
            sensor = DFRobot_PAJ7620U2(bus=1)
            if sensor.begin() == sensor.ERR_OK:
                sensor.set_gesture_highrate(True)
                return sensor
        except OSError as error:
            print(f"PAJ7620U2 I2C error: {error}")

        print("PAJ7620U2 initialization failed; retrying...")
        time.sleep(0.5)


def run():
    sensor = initialize_sensor()
    print("Gesture media controller started")
    print("LEFT    -> next")
    print("RIGHT   -> previous")
    print("FORWARD -> toggle")

    last_command_time = 0.0
    ignore_until = 0.0

    while True:
        gesture = sensor.get_gesture()
        now = time.monotonic()

        if now < ignore_until:
            if gesture != sensor.GESTURE_NONE:
                remaining = ignore_until - now
                print(f"ignored gesture during Forward lockout ({remaining:.1f}s remaining)")
            continue

        if gesture == sensor.GESTURE_FORWARD:
            print("detected: FORWARD -> toggle")
            dispatch_media_command("toggle")
            ignore_until = time.monotonic() + FORWARD_LOCKOUT_SECONDS
            continue

        if gesture == sensor.GESTURE_LEFT:
            command = "next"
        elif gesture == sensor.GESTURE_RIGHT:
            command = "previous"
        else:
            continue

        if now - last_command_time < NORMAL_COOLDOWN_SECONDS:
            print(f"ignored duplicate: {command}")
            continue

        if dispatch_media_command(command):
            last_command_time = time.monotonic()


def main():
    run()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nstopped")
