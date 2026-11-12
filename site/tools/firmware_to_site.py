#!/usr/bin/env python3
"""Copy built firmware into the website's fw/ folder and write fw/manifest.json.

Run after `pio run` in firmware/:

    python3 site/tools/firmware_to_site.py firmware site/fw

The flash page reads manifest.json for the version, size and flash addresses. There is one firmware with every
companion in it; a fresh install writes the chosen one into the settings (NVS key "pet").
"""
import hashlib, json, re, shutil, sys
from datetime import date
from pathlib import Path

def main(fw_dir: Path, out: Path) -> None:
    build = fw_dir / '.pio' / 'build'
    version = re.search(r'#define\s+FW_VERSION\s+"([^"]+)"', (fw_dir / 'include' / 'config.h').read_text()).group(1)
    boot_app0 = next(Path.home().glob('.platformio/packages/framework-arduinoespressif32*/tools/partitions/boot_app0.bin'))

    out.mkdir(parents=True, exist_ok=True)
    for old in ('wisp.bin', 'crumb.bin', 'sprig.bin'):          # the one-firmware-per-pet days
        (out / old).unlink(missing_ok=True)
    env = build / 'deskling'
    shutil.copy(env / 'bootloader.bin', out / 'bootloader.bin')
    shutil.copy(env / 'partitions.bin', out / 'partitions.bin')
    shutil.copy(boot_app0, out / 'boot_app0.bin')
    data = (env / 'firmware.bin').read_bytes()
    (out / 'deskling.bin').write_bytes(data)
    app = {'file': 'deskling.bin', 'address': 0x10000, 'size': len(data), 'md5': hashlib.md5(data).hexdigest()}

    manifest = {
        'version': version, 'built': date.today().isoformat(), 'chip': 'ESP32-S3', 'flash': '16MB',
        'parts': {
            'bootloader': {'file': 'bootloader.bin', 'address': 0x0},
            'partitions': {'file': 'partitions.bin', 'address': 0x8000},
            'otadata': {'file': 'boot_app0.bin', 'address': 0xE000},
            'nvs': {'address': 0x9000, 'size': 0x5000},
        },
        'app': app,
        'pets': ['wisp', 'crumb', 'sprig'],
    }
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
    print(f'firmware {version}: {len(data) / 1e6:.1f} MB')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
