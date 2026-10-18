#!/usr/bin/env python3
"""Turn Material Symbols (Rounded) SVGs into Android vector drawables.

Widgets and tiles draw through RemoteViews, which can't use an icon font, so every icon the
app shows is a vector drawable. For each name in app/symbols.txt this writes two files to
app/src/main/res/drawable: sym_<name>.xml (outlined, for "off") and sym_<name>_fill.xml
(filled, for "on").

The SVGs come from google/material-design-icons (Apache-2.0) at the pinned commit below.
Run it with `python3 -I scripts/import-symbols.py` after adding a name.
"""

import pathlib
import re
import sys
import urllib.request

COMMIT = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
BASE = f"https://raw.githubusercontent.com/google/material-design-icons/{COMMIT}/symbols/web"
ROOT = pathlib.Path(__file__).resolve().parent.parent
NAMES = ROOT / "app" / "symbols.txt"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable"
LOOKUP = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "droidective" / "android" / "ui" / "Symbols.kt"
PATH_DATA = re.compile(r'<path[^>]*\sd="([^"]+)"')
VIEW_BOX = re.compile(r'<svg[^>]*\sviewBox="([-0-9.\s]+)"')
SIZE = re.compile(r'<svg(?=[^>]*\swidth="([0-9.]+)")(?=[^>]*\sheight="([0-9.]+)")')
SAFE_NAME = re.compile(r"[a-z0-9_]+")

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Material Symbols Rounded "{name}"{variant}, Apache-2.0, google/material-design-icons@{commit}. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{width}"
    android:viewportHeight="{height}">
    <group{translate}>
{paths}
    </group>
</vector>
"""


def fetch(name: str, fill: bool) -> tuple[list[float], list[str]]:
    """The SVG's viewBox and path data. Most symbols draw on a 960 grid shifted up by 960, but
    older ones (auto_awesome, for one) have no viewBox and draw on their width and height, which
    is what SVG means without one. The file decides, never a guess."""
    suffix = "_fill1_24px.svg" if fill else "_24px.svg"
    url = f"{BASE}/{name}/materialsymbolsrounded/{name}{suffix}"
    with urllib.request.urlopen(url, timeout=30) as response:
        svg = response.read().decode("utf-8")
    box = VIEW_BOX.search(svg)
    size = SIZE.search(svg)
    if box:
        view_box = [float(n) for n in box.group(1).split()]
    elif size:
        view_box = [0.0, 0.0, float(size.group(1)), float(size.group(2))]
    else:
        raise ValueError(f"neither a viewBox nor a width and height in {url}")
    if len(view_box) != 4 or view_box[2] <= 0 or view_box[3] <= 0:
        raise ValueError(f"unusable viewBox {view_box} in {url}")
    paths = PATH_DATA.findall(svg)
    if not paths:
        raise ValueError(f"no <path> in {url}")
    for d in paths:
        if not re.fullmatch(r"[0-9A-Za-z.,\s\-]+", d):
            raise ValueError(f"unexpected characters in path data of {url}")
    return view_box, paths


def number(value: float) -> str:
    return str(int(value)) if value == int(value) else str(value)


def translation(dx: float, dy: float) -> str:
    """The group's offset back into the viewport, leaving out any axis that needs none."""
    parts = [f' android:translate{axis}="{number(v)}"' for axis, v in (("X", dx), ("Y", dy)) if v]
    return "".join(parts)


def main() -> int:
    names = [line.strip() for line in NAMES.read_text().splitlines() if line.strip() and not line.startswith("#")]
    bad = [n for n in names if not SAFE_NAME.fullmatch(n)]
    if bad:
        print(f"Not a symbol name: {bad}", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    missing = []
    for name in sorted(set(names)):
        for fill in (False, True):
            try:
                (min_x, min_y, width, height), paths = fetch(name, fill)
            except Exception as error:  # noqa: BLE001 - report every missing icon, then fail
                missing.append(f"{name}{' (fill)' if fill else ''}: {error}")
                continue
            body = "\n".join(
                f'        <path\n            android:fillColor="#FFFFFFFF"\n            android:pathData="{d}" />'
                for d in paths
            )
            target = OUT / f"sym_{name}{'_fill' if fill else ''}.xml"
            target.write_text(
                TEMPLATE.format(
                    name=name,
                    variant=" filled" if fill else "",
                    commit=COMMIT,
                    width=number(width),
                    height=number(height),
                    translate=translation(-min_x, -min_y),
                    paths=body,
                )
            )
    if missing:
        print("Could not import:\n  " + "\n  ".join(missing), file=sys.stderr)
        return 1
    write_lookup(sorted(set(names)))
    print(f"Wrote {len(set(names)) * 2} drawables to {OUT.relative_to(ROOT)}")
    return 0


def write_lookup(names: list[str]) -> None:
    """Name-to-resource lookup. Direct R references keep the drawables through resource shrinking."""
    entries = "\n".join(
        f'        "{n}" to Symbol(R.drawable.sym_{n}, R.drawable.sym_{n}_fill),' for n in names
    )
    LOOKUP.write_text(
        f"""// Generated by scripts/import-symbols.py from app/symbols.txt. Don't edit by hand.
package com.droidective.android.ui

import androidx.annotation.DrawableRes
import com.droidective.android.R

/** One Material Symbol: the outlined drawable for "off" and the filled one for "on". */
data class Symbol(@param:DrawableRes val outlined: Int, @param:DrawableRes val filled: Int)

object Symbols {{
    val byName: Map<String, Symbol> =
        mapOf(
{entries}
        )
}}
"""
    )


if __name__ == "__main__":
    sys.exit(main())
