// Builds an ESP-IDF NVS partition image (format version 2) in the browser, for the dial's settings.
// Same layout as Espressif's nvs_partition_gen.py: first page active, each entry 32 bytes with a CRC, strings
// spanning extra entries. Checked byte for byte against that tool (tools/nvs_check in the repo's site folder).

const PAGE = 4096, ENTRY = 32, ENTRIES = 126, HEADER = 32, BITMAP = 32;
const T = { U8: 0x01, I8: 0x11, U16: 0x02, I16: 0x12, U32: 0x04, I32: 0x14, STR: 0x21 };

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

/** zlib's crc32 with the seed the NVS code uses (0xFFFFFFFF), as the Python generator calls it. */
function crc32(bytes, seed = 0xffffffff) {
  let c = (seed ^ 0xffffffff) >>> 0;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

class Page {
  constructor(seq) {
    this.buf = new Uint8Array(PAGE).fill(0xff);
    this.dv = new DataView(this.buf.buffer);
    this.next = 0;
    this.dv.setUint32(0, 0xfffffffe, true);       // active
    this.dv.setUint32(4, seq, true);
    this.buf[8] = 0xfe;                             // format version 2
    this.dv.setUint32(28, crc32(this.buf.subarray(4, 28)), true);
  }
  free() { return ENTRIES - this.next; }
  /** Write one 32-byte entry (with its CRC computed here) and mark it written in the bitmap. */
  put(e) {
    const off = HEADER + BITMAP + this.next * ENTRY;
    this.buf.set(e, off);
    const crcSrc = new Uint8Array(28);
    crcSrc.set(e.subarray(0, 4), 0);
    crcSrc.set(e.subarray(8, 32), 4);
    new DataView(this.buf.buffer).setUint32(off + 4, crc32(crcSrc), true);
    this.mark(this.next++);
  }
  /** Raw data chunk of a string (no CRC of its own). */
  putRaw(chunk) {
    this.buf.set(chunk, HEADER + BITMAP + this.next * ENTRY);
    this.mark(this.next++);
  }
  mark(i) {                                         // 2 bits per entry: 11 empty, 10 written
    const byte = HEADER + (i >> 2), shift = (i & 3) * 2;
    this.buf[byte] &= ~(1 << shift) & 0xff;
  }
}

function entry(ns, type, span, key) {
  const e = new Uint8Array(ENTRY).fill(0xff);
  e[0] = ns; e[1] = type; e[2] = span; e[3] = 0xff;
  const k = new TextEncoder().encode(key);
  if (k.length > 15) throw new Error(`NVS key too long: ${key}`);
  e.fill(0, 8, 24);
  e.set(k, 8);
  return e;
}

/**
 * items: [{ ns: "deskling", key, type: "u8"|"i8"|"u16"|"u32"|"i32"|"str", value }]
 * size: partition size in bytes (a multiple of 4096). Returns a Uint8Array of that size.
 */
export function buildNvs(items, size = 0x5000) {
  const pages = [new Page(0)];
  let page = pages[0];
  const nsIndex = new Map();
  const room = (n) => {
    if (page.free() < n) { page = new Page(pages.length); pages.push(page); }
  };
  for (const it of items) {
    if (!nsIndex.has(it.ns)) {
      const idx = nsIndex.size + 1;
      nsIndex.set(it.ns, idx);
      room(1);
      const e = entry(0, T.U8, 1, it.ns);
      e.fill(0xff, 24, 32); e[24] = idx;
      page.put(e);
    }
    const ns = nsIndex.get(it.ns);
    const type = it.type.toUpperCase();
    if (type === 'STR') {
      const bytes = new Uint8Array([...new TextEncoder().encode(it.value), 0]);
      const chunks = Math.ceil(bytes.length / ENTRY);
      room(1 + chunks);
      const e = entry(ns, T.STR, 1 + chunks, it.key);
      const dv = new DataView(e.buffer);
      dv.setUint16(24, bytes.length, true);
      dv.setUint16(26, 0xffff, true);
      dv.setUint32(28, crc32(bytes), true);
      page.put(e);
      for (let i = 0; i < chunks; i++) {
        const c = new Uint8Array(ENTRY).fill(0xff);
        c.set(bytes.subarray(i * ENTRY, (i + 1) * ENTRY));
        page.putRaw(c);
      }
    } else {
      room(1);
      const e = entry(ns, T[type], 1, it.key);
      const dv = new DataView(e.buffer);
      const v = Number(it.value);
      switch (type) {
        case 'U8': dv.setUint8(24, v); break;
        case 'I8': dv.setInt8(24, v); break;
        case 'U16': dv.setUint16(24, v, true); break;
        case 'U32': dv.setUint32(24, v, true); break;
        case 'I32': dv.setInt32(24, v, true); break;
        default: throw new Error(`NVS type ${type}`);
      }
      page.put(e);
    }
  }
  if (pages.length * PAGE > size - PAGE) throw new Error('settings do not fit the NVS partition');
  const out = new Uint8Array(size).fill(0xff);
  pages.forEach((p, i) => out.set(p.buf, i * PAGE));
  return out;
}

/** The dial's settings (firmware src/settings.cpp: Arduino Preferences, namespace "deskling"). */
export function dialSettingsNvs(s) {
  const u8 = (key, v) => ({ ns: 'deskling', key, type: 'u8', value: v });
  const items = [];
  if (s.ssid) {
    items.push({ ns: 'deskling', key: 'ssid', type: 'str', value: s.ssid });
    items.push({ ns: 'deskling', key: 'pass', type: 'str', value: s.pass || '' });
  }
  items.push(
    { ns: 'deskling', key: 'ledoff', type: 'i8', value: s.ledOffset },
    u8('inv', s.knobInvert ? 1 : 0),
    u8('bright', s.bright), u8('ledbr', s.ledBright), u8('nap', s.napMin),
    u8('rem_water', s.remind[0]), u8('rem_eyes', s.remind[1]), u8('rem_stretch', s.remind[2]), u8('rem_move', s.remind[3]),
    u8('h_start', s.hourStart), u8('h_end', s.hourEnd), u8('tz', s.tz),
    u8('clock', s.clockMode), u8('clock_st', s.clockStyle),
    u8('s_date', s.showDate ? 1 : 0), u8('s_status', s.showStatus ? 1 : 0), u8('s_next', s.showNext ? 1 : 0),
    u8('post', s.postureOn ? 1 : 0), u8('sit', s.sitMin), u8('stand', s.standMin),
  );
  return buildNvs(items, 0x5000);
}
