// Where the site's binary assets live. The artifact preview build swaps this file,
// because that host only serves a fixed set of file extensions.
export const PACK_EXT = 'bin';                 // pets/<pet>/<loop>.<ext>
export const MODEL_EXT = 'stl';                // model/<part>.<ext>, in the stand's shared frame
export const CAN_FLASH_HERE = true;            // false where the firmware images aren't hosted
export const APK_BASE = 'app/';                // where app/deskling.apk is served from
