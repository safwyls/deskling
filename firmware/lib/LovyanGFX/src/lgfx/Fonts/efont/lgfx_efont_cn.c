#include "lgfx_efont_cn.h"
#if defined ( ARDUINO ) && !defined ( PROGMEM )
 #if __has_include(<pgmspace.h>)
  #include <pgmspace.h>
 #elif __has_include(<avr/pgmspace.h>) || defined(__AVR__)
  #include <avr/pgmspace.h>
 #else
  #include <Arduino.h>
 #endif
#endif
#ifndef PROGMEM
#define PROGMEM
#endif


/* Deskling: CJK font data removed to keep the project small (unused). Symbols kept as 1-byte stubs. */
PROGMEM const uint8_t lgfx_efont_cn_10[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_10_b[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_10_bi[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_10_i[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_12[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_12_b[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_12_bi[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_12_i[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_14[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_14_b[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_14_bi[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_14_i[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_16[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_16_b[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_16_bi[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_16_i[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_24[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_24_b[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_24_bi[1] = {0};
PROGMEM const uint8_t lgfx_efont_cn_24_i[1] = {0};
