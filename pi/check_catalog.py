#!/usr/bin/env python3
"""
RandyFlix channel guide check, meant to run weekly on the Pi (see install.sh).

Tests every channel in catalog.m3u the way the app starts one: the stream's playlist, then its
media playlist, then the first bytes of a video segment. A channel is removed only after it
fails two checks in a row, so a short outage doesn't cost you a channel. If most channels fail at
once (the Pi's internet is probably down), nothing is changed.

Writes catalog-report.txt next to the catalog, and keeps a backup as catalog.bak.m3u whenever
channels are removed. The catalog is rewritten in place, so its owner and permissions stay the
same (Samba keeps working).

Usage:
    python3 check_catalog.py                 # check and update
    python3 check_catalog.py --dry-run       # check and report only
    python3 check_catalog.py --catalog PATH  # another catalog file
"""
import argparse
import datetime
import json
import os
import re
import shutil
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CATALOG = os.path.join(os.path.dirname(HERE), "catalog.m3u")   # _randyflix/catalog.m3u
USER_AGENT = ("Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
TIMEOUT = 15
FAILS_TO_REMOVE = 2          # consecutive failed checks before a channel is removed
OUTAGE_FRACTION = 0.5        # this many failing at once = probably no internet: change nothing


def fetch(url, agent, referer, max_bytes=None):
    req = urllib.request.Request(url, headers={"User-Agent": agent or USER_AGENT})
    if referer:
        req.add_header("Referer", referer)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = r.read(max_bytes) if max_bytes else r.read()
        return r.geturl(), data


def media_lines(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.strip().startswith("#")]


def test_stream(ch):
    """None if the channel plays, otherwise why not."""
    try:
        url, data = fetch(ch["url"], ch["agent"], ch["referer"])
        text = data.decode("utf-8", "replace")
        if "#EXTM3U" not in text:
            return "not a stream playlist"
        if re.search(r'CODECS="[^"]*(hvc1|hev1)', text):
            return "H.265 video (the Firestick may not play it)"
        lines = media_lines(text)
        if "#EXT-X-STREAM-INF" in text and lines:
            url, data = fetch(urllib.parse.urljoin(url, lines[0]), ch["agent"], ch["referer"])
            lines = media_lines(data.decode("utf-8", "replace"))
        if not lines:
            return "empty playlist"
        fetch(urllib.parse.urljoin(url, lines[-1]), ch["agent"], ch["referer"], 4096)
        return None
    except Exception as e:   # noqa: BLE001 - any failure means it doesn't play
        return str(getattr(e, "reason", None) or e)[:120]


def parse(text):
    """Header lines, then entries of (#EXTINF line, any option lines, stream URL)."""
    header, entries, pending = [], [], None
    for raw in text.lstrip("﻿").splitlines():
        line = raw.rstrip("\r")
        if line.startswith("#EXTINF"):
            pending = {"lines": [line]}
        elif pending is not None:
            pending["lines"].append(line)
            if line.strip() and not line.startswith("#"):
                info = pending["lines"][0]
                attr = lambda k: (re.search(k + r'="([^"]*)"', info) or [None, ""])[1]
                entries.append({
                    "lines": pending["lines"],
                    "url": line.strip(),
                    "name": info[info.rfind(",") + 1:].strip(),
                    "group": attr("group-title"),
                    "agent": attr("http-user-agent"),
                    "referer": attr("http-referrer"),
                })
                pending = None
        elif not entries and line.strip():
            header.append(line)
    return header, entries


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--catalog", default=DEFAULT_CATALOG)
    ap.add_argument("--dry-run", action="store_true", help="report only, change nothing")
    args = ap.parse_args()

    catalog = os.path.abspath(args.catalog)
    folder = os.path.dirname(catalog)
    state_path = os.path.join(HERE, "catalog-state.json")
    report_path = os.path.join(folder, "catalog-report.txt")
    with open(catalog, encoding="utf-8") as f:
        header, entries = parse(f.read())
    if not entries:
        print("No channels found in", catalog)
        return 1

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(test_stream, entries))
    # Busy servers sometimes time out when the Pi asks several at once: anything that failed
    # gets one more try on its own before it counts.
    results = [test_stream(e) if why else None for e, why in zip(entries, results)]

    try:
        with open(state_path, encoding="utf-8") as f:
            fails = json.load(f)
    except (OSError, ValueError):
        fails = {}

    failing = [(e, why) for e, why in zip(entries, results) if why]
    outage = len(failing) >= len(entries) * OUTAGE_FRACTION
    if outage:
        new_fails = fails   # don't count an outage against anyone
    else:
        # A channel that played this time starts counting from zero again.
        new_fails = {e["url"]: fails.get(e["url"], 0) + 1 for e, _ in failing}
    remove = set() if outage else {u for u, n in new_fails.items() if n >= FAILS_TO_REMOVE}

    now = datetime.datetime.now().strftime("%a %b %d %Y, %I:%M %p")
    out = [f"Channel guide check: {now}",
           f"{len(entries) - len(failing)} of {len(entries)} channels play."]
    if outage:
        out.append("Most channels failed at once, so the internet was probably down. Nothing was changed.")
    removed = [e for e in entries if e["url"] in remove]
    warned = [(e, why) for e, why in failing if e["url"] not in remove and not outage]
    if removed:
        out.append("")
        out.append(f"Removed (failed {FAILS_TO_REMOVE} checks in a row):")
        out += [f"  {e['name']}  ({e['group']})" for e in removed]
    if warned:
        out.append("")
        out.append("Not playing this time (removed if it fails again next check):")
        out += [f"  {e['name']}  ({e['group']}): {why}" for e, why in warned]
    report = "\n".join(out) + "\n"
    print(report, end="")

    if args.dry_run:
        print("--dry-run: nothing was changed.")
        return 0

    if removed:
        shutil.copy2(catalog, os.path.join(folder, "catalog.bak.m3u"))
        keep = [e for e in entries if e["url"] not in remove]
        text = "\n".join(header + [l for e in keep for l in e["lines"]]) + "\n"
        # Rewrite in place (not replace) so the file keeps its owner and permissions.
        with open(catalog, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump({u: n for u, n in new_fails.items() if u not in remove}, f, indent=1)
    with open(report_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
