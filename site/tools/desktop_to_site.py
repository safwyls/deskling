#!/usr/bin/env python3
"""Copy the desktop app's installers into the website and write desktop/manifest.json.

CI builds the app on Windows, macOS and Linux and gathers what they made in one folder; then:

    python3 site/tools/desktop_to_site.py <folder> site/desktop desktop/src-tauri/tauri.conf.json

The installers get fixed names, so links to them keep working from one version to the next. The desktop page
reads manifest.json for the version, sizes and checksums it shows. Missing installers are left out.
"""
import hashlib, json, shutil, sys
from datetime import date
from pathlib import Path

KINDS = [  # (key, pattern, name on the site)
    ('windows', '*.exe', 'Deskling-setup.exe'),
    ('mac', '*.dmg', 'Deskling.dmg'),
    ('appimage', '*.AppImage', 'Deskling.AppImage'),
    ('deb', '*.deb', 'deskling.deb'),
]


def main(found: Path, out: Path, conf: Path) -> None:
    files = {}
    for key, pattern, name in KINDS:
        hits = sorted(found.rglob(pattern))
        if not hits:
            print(f'desktop: no {pattern}')
            continue
        out.mkdir(parents=True, exist_ok=True)
        shutil.copy(hits[0], out / name)
        data = hits[0].read_bytes()
        files[key] = {'file': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        print(f'desktop {key}: {len(data) / 1e6:.1f} MB')
    if not files:
        return
    version = json.loads(conf.read_text())['version']
    manifest = {'version': version, 'built': date.today().isoformat(), 'files': files}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
