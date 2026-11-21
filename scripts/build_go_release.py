"""Build one Linux release target and record its toolchain, hashes and vulnerability scan."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--arch", choices=["amd64", "arm64"], required=True)
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--govulncheck", default="govulncheck")
args = parser.parse_args()
out = args.out.resolve() / ("linux-" + args.arch)
out.mkdir(parents=True, exist_ok=True)
manifest = out / "release.json"
manifest.unlink(missing_ok=True)
version = re.search(r"^toolchain (go\d+\.\d+\.\d+)$", (root / "go.mod").read_text(), re.M)[1]
image = (root / "Dockerfile").read_text().splitlines()[0].split()[1]
if not re.fullmatch(r"golang:" + re.escape(version[2:]) + r"-alpine@sha256:[a-f0-9]{64}", image):
    raise SystemExit("Docker builder and go.mod must pin the same patch version")
env = dict(os.environ, GOTOOLCHAIN=version, CGO_ENABLED="0", GOOS="linux", GOARCH=args.arch)

def run(*args):
    return subprocess.check_output(args, cwd=root, env=env, text=True, encoding="utf-8").strip()

if run("go", "env", "GOVERSION") != version:
    raise SystemExit("Unexpected Go toolchain")
commands = ["./cmd/timepin", "./cmd/timepinctl"]
run("go", "build", "-buildvcs=false", "-trimpath", "-ldflags=-s -w", "-o", str(out) + os.sep, *commands)
artifacts = {}
for command in commands:
    executable = out / Path(command).name
    info = run("go", "version", "-m", str(executable))
    if info.splitlines()[0].split()[-1] != version or not all(
        value in info for value in ["GOOS=linux", "GOARCH=" + args.arch, "CGO_ENABLED=0"]
    ):
        raise SystemExit("Incorrect release build metadata")
    info = executable.name + ": " + version + "\n" + info.split("\n", 1)[1]
    artifacts[executable.name] = dict(sha256=hashlib.sha256(executable.read_bytes()).hexdigest(), buildInfo=info)

scan = out / "govulncheck.json"
with scan.open("wb") as stream:
    subprocess.run([args.govulncheck, "-json", *commands], cwd=root, env=env, stdout=stream, check=True)
decoder = json.JSONDecoder()
data = scan.read_text(encoding="utf-8").lstrip()
called = set()
config = None
while data:
    item, end = decoder.raw_decode(data)
    data = data[end:].lstrip()
    if "config" in item:
        config = item["config"]
    finding = item.get("finding")
    if finding and finding["trace"][0].get("function"):
        called.add(finding["osv"])
if not config or config.get("go_version") != version or called:
    raise SystemExit(f"Release scan failed; inspect {scan}. Called findings: {sorted(called)}")
for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
    shutil.copyfile(root / name, out / name)
shutil.copytree(root / "licenses" / "go", out / "licenses" / "go", dirs_exist_ok=True)
holidays = out / "internal/server/web/holidays"
holidays.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root / "internal/server/web/holidays/LICENSE.txt", holidays / "LICENSE.txt")
manifest.write_text(json.dumps(dict(toolchain=version, builder=image, artifacts=artifacts,
    scan=scan.name, scanConfig=config, calledFindings=[]), indent=2) + "\n", encoding="utf-8")
print(manifest)
