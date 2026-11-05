#!/usr/bin/env python3
"""Copy built firmware into the website's fw/ folder and write fw/manifest.json.

Run after `pio run -e wisp -e crumb -e sprig` in firmware/:

    python3 site/tools/firmware_to_site.py firmware site/fw

The flash page reads manifest.json for the version, sizes and flash addresses.
The bootloader and partition table are the same for every pet, so they come from the first one.
"""
import hashlib, json, re, shutil, sys
from datetime import date
from pathlib import Path

PETS = ['wisp', 'crumb', 'sprig']


def main(fw_dir: Path, out: Path) -> None:
    build = fw_dir / '.pio' / 'build'
    version = re.search(r'#define\s+FW_VERSION\s+"([^"]+)"', (fw_dir / 'include' / 'config.h').read_text()).group(1)
    boot_app0 = next(Path.home().glob('.platformio/packages/framework-arduinoespressif32*/tools/partitions/boot_app0.bin'))

    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(build / PETS[0] / 'bootloader.bin', out / 'bootloader.bin')
    shutil.copy(build / PETS[0] / 'partitions.bin', out / 'partitions.bin')
    shutil.copy(boot_app0, out / 'boot_app0.bin')

    apps = {}
    for pet in PETS:
        data = (build / pet / 'firmware.bin').read_bytes()
        (out / f'{pet}.bin').write_bytes(data)
        apps[pet] = {'file': f'{pet}.bin', 'address': 0x10000, 'size': len(data), 'md5': hashlib.md5(data).hexdigest()}

    manifest = {
        'version': version, 'built': date.today().isoformat(), 'chip': 'ESP32-S3', 'flash': '16MB',
        'parts': {
            'bootloader': {'file': 'bootloader.bin', 'address': 0x0},
            'partitions': {'file': 'partitions.bin', 'address': 0x8000},
            'otadata': {'file': 'boot_app0.bin', 'address': 0xE000},
            'nvs': {'address': 0x9000, 'size': 0x5000},
        },
        'apps': apps,
    }
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
    print(f'firmware {version}: ' + ', '.join(f'{p} {a["size"] / 1e6:.1f} MB' for p, a in apps.items()))


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
