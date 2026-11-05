#!/usr/bin/env python3
"""Copy the built Android app into the website and write app/manifest.json.

Run after `./gradlew :app:assembleRelease` in app/:

    python3 site/tools/app_to_site.py app/app/build/outputs/apk/release site/app

The app page reads manifest.json for the version, size and checksum it shows next to the download.
"""
import hashlib, json, shutil, sys
from datetime import date
from pathlib import Path


def main(build: Path, out: Path) -> None:
    meta = json.loads((build / 'output-metadata.json').read_text())['elements'][0]
    apk = build / meta['outputFile']
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(apk, out / 'deskling.apk')
    data = apk.read_bytes()
    manifest = {'version': meta['versionName'], 'code': meta['versionCode'], 'file': 'deskling.apk',
                'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'built': date.today().isoformat(),
                'minAndroid': 8}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
    print(f'app {manifest["version"]}: {len(data) / 1e6:.1f} MB')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
