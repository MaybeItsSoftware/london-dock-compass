#!/usr/bin/env python3
"""Re-seed app/src/main/res/raw/docklocations.json from the live TfL BikePoint API.

Fetches every Santander Cycles docking station, converts each to the
`Station` shape the app expects, buckets them by geohash (precision 7 --
matching com.github.davidmoten.geo.GeoHash.encodeHash(lat, lon, 7) used at
runtime in MainActivity.kt), and writes the result back to the raw resource
file.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request

TFL_BIKEPOINT_URL = "https://api.tfl.gov.uk/BikePoint/"
OUTPUT_PATH = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "docklocations.json"
)
GEOHASH_PRECISION = 7
GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

# Guard against writing a near-empty/corrupt file if the API has a partial outage.
MIN_EXPECTED_STATIONS = 700


def geohash_encode(lat: float, lon: float, precision: int = GEOHASH_PRECISION) -> str:
    lat_range = (-90.0, 90.0)
    lon_range = (-180.0, 180.0)
    geohash = []
    bit = 0
    ch = 0
    even = True
    bits = [16, 8, 4, 2, 1]
    while len(geohash) < precision:
        if even:
            mid = (lon_range[0] + lon_range[1]) / 2
            if lon > mid:
                ch |= bits[bit]
                lon_range = (mid, lon_range[1])
            else:
                lon_range = (lon_range[0], mid)
        else:
            mid = (lat_range[0] + lat_range[1]) / 2
            if lat > mid:
                ch |= bits[bit]
                lat_range = (mid, lat_range[1])
            else:
                lat_range = (lat_range[0], mid)
        even = not even
        if bit < 4:
            bit += 1
        else:
            geohash.append(GEOHASH_BASE32[ch])
            bit = 0
            ch = 0
    return "".join(geohash)


def fetch_bike_points() -> list[dict]:
    url = TFL_BIKEPOINT_URL
    app_key = os.environ.get("TFL_APP_KEY")
    if app_key:
        url = f"{url}?app_key={app_key}"

    request = urllib.request.Request(url, headers={"User-Agent": "london-dock-compass-reseed"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def additional_properties(entry: dict) -> dict:
    return {prop["key"]: prop["value"] for prop in entry.get("additionalProperties", [])}


def to_station(entry: dict) -> dict:
    props = additional_properties(entry)

    raw_id = entry["id"]
    station_id = int(raw_id.removeprefix("BikePoints_"))

    install_date = props.get("InstallDate") or ""
    removal_date = props.get("RemovalDate") or ""

    return {
        "id": station_id,
        "name": entry["commonName"],
        "terminalName": int(props["TerminalName"]),
        "lat": entry["lat"],
        "long": entry["lon"],
        "installed": props.get("Installed", "").lower() == "true",
        "locked": props.get("Locked", "").lower() == "true",
        "installDate": int(install_date) if install_date else None,
        "removalDate": int(removal_date) if removal_date else None,
        "temporary": props.get("Temporary", "").lower() == "true",
    }


def build_dock_locations(stations: list[dict]) -> dict:
    buckets: dict[str, list[dict]] = {}
    for station in sorted(stations, key=lambda s: s["id"]):
        key = geohash_encode(station["lat"], station["long"])
        buckets.setdefault(key, []).append(station)
    return buckets


def main() -> int:
    try:
        entries = fetch_bike_points()
    except Exception as exc:  # noqa: BLE001 - top-level CLI guard
        print(f"Failed to fetch BikePoint data from TfL: {exc}", file=sys.stderr)
        return 1

    stations = [to_station(entry) for entry in entries]

    if len(stations) < MIN_EXPECTED_STATIONS:
        print(
            f"Refusing to write: only got {len(stations)} stations, "
            f"expected at least {MIN_EXPECTED_STATIONS}. TfL API may be degraded.",
            file=sys.stderr,
        )
        return 1

    dock_locations = build_dock_locations(stations)

    output_path = os.path.normpath(OUTPUT_PATH)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(dock_locations, f, indent=2)
        f.write("\n")

    print(f"Wrote {len(stations)} stations across {len(dock_locations)} geohash buckets to {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
