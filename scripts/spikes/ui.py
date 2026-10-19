"""A small uiautomator driver for the spike scripts, on the device in ANDROID_SERIAL.

`ui.py tap <text>`, `ui.py has <text>`, `ui.py bounds <text>`, `ui.py texts`, `ui.py scroll`.
"""

import re
import subprocess
import sys
import time

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=False).stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def nodes(xml):
    for match in re.finditer(r"<node [^>]*>", xml):
        node = match.group(0)
        text = re.search(r' text="([^"]*)"', node)
        desc = re.search(r' content-desc="([^"]*)"', node)
        bounds = BOUNDS.search(re.search(r' bounds="([^"]*)"', node).group(1))
        yield (text.group(1) if text else ""), (desc.group(1) if desc else ""), bounds


def find(label, xml):
    for text, desc, bounds in nodes(xml):
        if label in (text, desc):
            x1, y1, x2, y2 = map(int, bounds.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def main():
    command, *rest = sys.argv[1:]
    label = " ".join(rest)
    if command == "texts":
        print("\n".join(sorted({t or d for t, d, _ in nodes(dump()) if t or d})))
    elif command == "has":
        sys.exit(0 if find(label, dump()) else 1)
    elif command == "tap":
        for _ in range(3):
            point = find(label, dump())
            if point:
                adb("shell", "input", "tap", str(point[0]), str(point[1]))
                time.sleep(1.2)
                return
            time.sleep(1)
        sys.exit(f"no '{label}' on screen")
    elif command == "bounds":
        point = find(label, dump())
        if not point:
            sys.exit(1)
        print(point[0], point[1])
    elif command == "scroll":
        adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
        time.sleep(0.8)


main()
