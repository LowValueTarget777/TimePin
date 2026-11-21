"""Check widget resource IDs in actual APKs against the published baseline."""

import argparse
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("apk", nargs="+", type=Path)
    args = parser.parse_args()
    baseline = Path(__file__).resolve().parents[1] / "android/app/widget-resource-ids.txt"
    expected = dict(line.split(" = ") for line in baseline.read_text().splitlines())
    for apk in args.apk:
        dump = subprocess.check_output([args.aapt, "dump", "resources", str(apk)]).decode(
            "utf-8", errors="replace"
        )
        found = {
            name: value
            for value, name in re.findall(r"spec resource (0x[0-9a-f]+) [^\s:]+:([^\s:]+):", dump)
        }
        for name, value in expected.items():
            if found.get(name) != value:
                raise SystemExit(f"{apk}: {name}: expected {value}, got {found.get(name, 'missing')}")
        print(f"{apk.name}: {len(expected)} widget resource IDs match")


if __name__ == "__main__":
    main()
