"""Check the Gradle release report against OSV and verify the bundled PDF.js files."""

import argparse
import base64
import hashlib
import io
import json
import re
import sys
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "app/src/main/assets/pdf/vendor"
REPORT = ROOT / "app/build/reports/security/release-dependencies.json"
MAX_TARBALL = 32 * 1024 * 1024


def read_json(request):
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def check_pdfjs(apk_path):
    marker = (VENDOR / "VERSION.txt").read_text(encoding="utf-8")
    version_match = re.search(r"^PDF\.js (\d+\.\d+\.\d+)\b", marker, re.MULTILINE)
    digest_match = re.search(r"^Package SHA-512 \(base64\): (\S+)$", marker, re.MULTILINE)
    if not version_match or not digest_match:
        raise ValueError("PDF.js VERSION.txt must declare a version and package digest.")
    version = version_match[1]
    expected = digest_match[1]
    registry = "https://registry.npmjs.org/pdfjs-dist"
    metadata = read_json(f"{registry}/{version}")
    source = f"{registry}/-/pdfjs-dist-{version}.tgz"
    if (metadata["version"] != version or metadata["dist"]["tarball"] != source
            or metadata["dist"]["integrity"] != f"sha512-{expected}"):
        raise ValueError("PDF.js version or published package digest differs from VERSION.txt.")

    for _ in range(3):
        try:
            with urlopen(source, timeout=30) as response:
                package = response.read(MAX_TARBALL + 1)
        except OSError:
            continue
        digest = base64.b64encode(hashlib.sha512(package).digest()).decode("ascii")
        if len(package) <= MAX_TARBALL and digest == expected:
            break
    else:
        raise ValueError("Unable to verify the complete PDF.js package SHA-512.")

    bundled = {file.relative_to(VENDOR).as_posix(): file.read_bytes()
               for file in VENDOR.rglob("*") if file.is_file()}
    with tarfile.open(fileobj=io.BytesIO(package), mode="r:gz") as published:
        entries = {entry.name: entry for entry in published if entry.isfile()}
        for name, data in bundled.items():
            if name == "VERSION.txt":
                continue
            names = ["package/" + name, "package/legacy/" + name]
            if name == "LICENSE.txt":
                names.append("package/LICENSE")
            if not any(path in entries and published.extractfile(entries[path]).read() == data for path in names):
                raise ValueError(f"Bundled PDF.js file differs from the official package: {name}")

    if apk_path is not None:
        with ZipFile(apk_path) as apk:
            for name, data in bundled.items():
                if apk.read("assets/pdf/vendor/" + name) != data:
                    raise ValueError(f"APK PDF.js file differs from the verified copy: {name}")
    print(f"Verified PDF.js {version} and {len(bundled) - 1} upstream files.")
    return version


def check_advisories(report_path, pdfjs):
    report = json.loads(report_path.read_text(encoding="utf-8"))
    packages = {}
    for scope, coordinates in report.items():
        for coordinate in coordinates:
            key = ("Maven", coordinate["name"], coordinate["version"])
            packages.setdefault(key, set()).add(scope)
    packages[("npm", "pdfjs-dist", pdfjs)] = {"bundled"}
    keys = sorted(packages)
    matches = 0
    for start in range(0, len(keys), 50):
        batch = keys[start:start + 50]
        queries = [{"package": {"ecosystem": ecosystem, "name": name}, "version": version}
                   for ecosystem, name, version in batch]
        request = Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                          headers={"Content-Type": "application/json"})
        results = read_json(request)["results"]
        if len(results) != len(batch) or any(result.get("next_page_token") for result in results):
            raise ValueError("OSV returned an incomplete advisory result.")
        for key, result in zip(batch, results):
            advisories = [entry["id"] for entry in result.get("vulns", [])]
            if advisories:
                _, name, version = key
                scopes = ", ".join(sorted(packages[key]))
                print(f"{name} {version} ({scopes}) — {', '.join(advisories)}")
                matches += len(advisories)
    print(f"Checked {len(keys)} package versions against OSV. Advisory matches: {matches}.")
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=REPORT)
    parser.add_argument("--apk", type=Path, help="Also compare PDF.js files inside this APK.")
    args = parser.parse_args()
    try:
        pdfjs = check_pdfjs(args.apk)
        return 1 if check_advisories(args.report, pdfjs) else 0
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        print(f"Release dependency check failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
