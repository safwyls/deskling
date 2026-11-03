// CrowPanel 1.46" rotary display: pins and display driver.
// Pin map and panel setup follow Elecrow's official Core 3 + LVGL 9 example for this board.
#pragma once
#include <Arduino.h>
#include <LovyanGFX.h>

// Display (ST77961 over 3-wire SPI) and power
#define PIN_LCD_SCLK   10
#define PIN_LCD_MOSI   11
#define PIN_LCD_DC      3
#define PIN_LCD_CS      9
#define PIN_LCD_RST    14
#define PIN_LCD_PWR_A   1   // both rails must be high before the LCD accepts data
#define PIN_LCD_PWR_B   2
#define PIN_BACKLIGHT  46

// Touch (CST816T)
#define PIN_TP_SDA      6
#define PIN_TP_SCL      7
#define PIN_TP_INT      5
#define PIN_TP_RST     13

// Rotary encoder
#define PIN_ENC_A      45
#define PIN_ENC_B      42
#define PIN_ENC_SW     41   // active low

// LEDs
#define PIN_POWER_LED  40   // active low
#define PIN_RING       48   // 8 x WS2812
#define PIN_RING_PWR   17
#define RING_COUNT      8

#define SCREEN_W 360
#define SCREEN_H 360

class LGFX : public lgfx::LGFX_Device {
  lgfx::Panel_ST77961 _panel;
  lgfx::Bus_SPI _bus;
public:
  LGFX() {
    {
      auto cfg = _bus.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 0;
      cfg.freq_write = 80000000;
      cfg.freq_read = 20000000;
      cfg.spi_3wire = true;
      cfg.use_lock = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = PIN_LCD_SCLK;
      cfg.pin_mosi = PIN_LCD_MOSI;
      cfg.pin_miso = -1;
      cfg.pin_dc = PIN_LCD_DC;
      _bus.config(cfg);
      _panel.setBus(&_bus);
    }
    {
      auto cfg = _panel.config();
      cfg.pin_cs = PIN_LCD_CS;
      cfg.pin_rst = PIN_LCD_RST;
      cfg.pin_busy = -1;
      cfg.memory_width = SCREEN_W;
      cfg.memory_height = SCREEN_H;
      cfg.panel_width = SCREEN_W;
      cfg.panel_height = SCREEN_H;
      cfg.offset_x = 0;
      cfg.offset_y = 0;
      cfg.offset_rotation = 0;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits = 1;
      cfg.readable = false;
      cfg.invert = false;
      cfg.rgb_order = true;
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      _panel.config(cfg);
    }
    setPanel(&_panel);
  }
};

extern LGFX gfx;
