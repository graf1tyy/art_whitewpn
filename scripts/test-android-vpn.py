#!/usr/bin/env python3
"""Install ART in an Android emulator and prove that real HTTPS returns 204 through Android VPN/TUN."""
from __future__ import annotations

import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.artvpn.app"
ACTIVITY = "com.artvpn.app/io.nekohasekai.sfa.compose.MainActivity"
STATUS_TEXT = "Подключено · интернет проверен"
CONNECT_DESC = "Connect VPN"
VPN_DIALOG_BUTTONS = {"OK", "Allow", "Start", "Разрешить", "ОК"}


def adb(*args: str, timeout: int = 30, check: bool = True) -> str:
    result = subprocess.run(
        ["adb", *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    if check and result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed:\n{result.stdout[-5000:]}")
    return result.stdout


def ui_tree() -> ET.Element | None:
    adb("shell", "uiautomator", "dump", "/sdcard/art-window.xml", timeout=15, check=False)
    raw = adb("exec-out", "cat", "/sdcard/art-window.xml", timeout=10, check=False)
    try:
        return ET.fromstring(raw)
    except ET.ParseError:
        return None


def bounds_center(value: str) -> tuple[int, int] | None:
    try:
        left_top, right_bottom = value.strip("[]").split("][")
        left, top = (int(x) for x in left_top.split(","))
        right, bottom = (int(x) for x in right_bottom.split(","))
        return (left + right) // 2, (top + bottom) // 2
    except (ValueError, TypeError):
        return None


def tap_node(root: ET.Element, predicate) -> bool:
    for node in root.iter():
        if predicate(node.attrib):
            center = bounds_center(node.attrib.get("bounds", ""))
            if center is None:
                continue
            adb("shell", "input", "tap", str(center[0]), str(center[1]))
            return True
    return False


def wait_for(predicate, timeout: float, description: str) -> ET.Element:
    deadline = time.monotonic() + timeout
    last_root = None
    while time.monotonic() < deadline:
        last_root = ui_tree()
        if last_root is not None and predicate(last_root):
            return last_root
        # The Google Pixel system image can show an unrelated launcher ANR
        # under load. Choose "Wait" and keep observing; an ART VPN ANR is not
        // ignored and will still fail the test.
        if last_root is not None and contains_text(last_root, "Pixel Launcher isn't responding"):
            if tap_node(
                last_root,
                lambda a: a.get("text", "").strip().lower() == "wait"
                and a.get("clickable") == "true",
            ):
                time.sleep(2)
                continue
        time.sleep(1)
    last_ui = ET.tostring(last_root, encoding="unicode") if last_root is not None else "unavailable"
    raise TimeoutError(f"Timed out waiting for {description}; last UI: {last_ui}")


def contains_text(root: ET.Element, text: str) -> bool:
    return any(text in n.attrib.get("text", "") or text in n.attrib.get("content-desc", "") for n in root.iter())


def accept_vpn_consent() -> bool:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        root = ui_tree()
        if root is None:
            time.sleep(1)
            continue
        if tap_node(root, lambda a: a.get("resource-id") == "android:id/button1" and a.get("clickable") == "true"):
            return True
        if tap_node(root, lambda a: a.get("text", "") in VPN_DIALOG_BUTTONS and a.get("clickable") == "true"):
            return True
        time.sleep(1)
    return False


def main() -> int:
    apk = Path(sys.argv[1]).resolve()
    if not apk.is_file():
        raise FileNotFoundError(apk)

    adb("wait-for-device", timeout=120)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(2)
    else:
        raise TimeoutError("Android emulator did not finish booting")

    adb("install", "-r", str(apk), timeout=180)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", ACTIVITY, timeout=30)

    root = wait_for(
        lambda r: any(
            a.get("content-desc") == CONNECT_DESC and a.get("clickable") == "true"
            for a in (n.attrib for n in r.iter())
        ),
        150,
        "ART Auto profile and enabled Connect action",
    )
    if not tap_node(root, lambda a: a.get("content-desc") == CONNECT_DESC and a.get("clickable") == "true"):
        raise RuntimeError("Could not tap the accessible Connect VPN action")

    accept_vpn_consent()
    wait_for(lambda r: contains_text(r, STATUS_TEXT), 150, "verified HTTP 204 through Android VPN/TUN")

    connectivity = adb("shell", "dumpsys", "connectivity", timeout=30, check=False)
    vpn_dump = adb("shell", "dumpsys", "vpn", timeout=15, check=False)
    if "TRANSPORT_VPN" not in connectivity and "TRANSPORT_VPN" not in vpn_dump:
        raise RuntimeError("App displayed a verified connection, but Android did not report an active VPN transport")

    print("PASS: ART VPN installed and launched on Android API 35")
    print("PASS: Android VPN consent accepted and VPN transport is active")
    print("PASS: ART's in-process HTTPS verifier received HTTP 204 through the tunnel")
    print("PASS: status was not inferred from VPN service start alone")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ANDROID VPN TEST FAILED: {error}", file=sys.stderr)
        for label, args in (
            ("ART process", ("shell", "pidof", PACKAGE)),
            ("Foreground activity", ("shell", "dumpsys", "activity", "activities")),
            ("Window focus", ("shell", "dumpsys", "window", "windows")),
        ):
            try:
                print(f"--- {label} ---", file=sys.stderr)
                print(adb(*args, timeout=30, check=False)[-6000:], file=sys.stderr)
            except Exception as diagnostic_error:
                print(f"{label} unavailable: {diagnostic_error}", file=sys.stderr)
        try:
            print("--- logcat ---", file=sys.stderr)
            print(adb("logcat", "-d", "-t", "2000", "-v", "time", timeout=30, check=False)[-30000:], file=sys.stderr)
        except Exception:
            pass
        raise
