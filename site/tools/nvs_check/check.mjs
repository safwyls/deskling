// Compare js/nvs.js with Espressif's nvs_partition_gen for the same settings.
import { buildNvs } from '../../js/nvs.js';
import { writeFileSync } from 'fs';
const items = [
  { ns: 'deskling', key: 'ssid', type: 'str', value: 'Home Wi-Fi' },
  { ns: 'deskling', key: 'pass', type: 'str', value: 'a long passphrase that spans more than one entry, 64 chars max!' },
  { ns: 'deskling', key: 'ledoff', type: 'i8', value: -2 },
  { ns: 'deskling', key: 'bright', type: 'u8', value: 70 },
  { ns: 'deskling', key: 'rem_stretch', type: 'u8', value: 50 },
  { ns: 'deskling', key: 'post', type: 'u8', value: 1 },
];
writeFileSync('js.bin', buildNvs(items, 0x5000));
writeFileSync('in.csv', 'key,type,encoding,value\ndeskling,namespace,,\n' + items.map(i => `${i.key},data,${i.type === 'str' ? 'string' : i.type},"${i.value}"`).join('\n') + '\n');
