// LVGL's heap in PSRAM. With the C library's malloc, every small LVGL allocation (each object, label, style:
// some 50-60 KB in all) lands in internal RAM, which the Wi-Fi and Bluetooth drivers need and crash without.
// PSRAM has megabytes to spare and is plenty fast for LVGL's bookkeeping. Falls back to internal RAM if
// PSRAM is ever full.
#include <lvgl.h>
#if LV_USE_STDLIB_MALLOC == LV_STDLIB_CUSTOM
#include <stdlib.h>
#include <esp_heap_caps.h>

#define PSRAM_CAPS (MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT)

void lv_mem_init(void) {}
void lv_mem_deinit(void) {}
lv_mem_pool_t lv_mem_add_pool(void * mem, size_t bytes) { LV_UNUSED(mem); LV_UNUSED(bytes); return NULL; }
void lv_mem_remove_pool(lv_mem_pool_t pool) { LV_UNUSED(pool); }

void * lv_malloc_core(size_t size)
{
    void * p = heap_caps_malloc(size, PSRAM_CAPS);
    return p ? p : malloc(size);
}

void * lv_realloc_core(void * p, size_t new_size)
{
    if (new_size == 0) { free(p); return NULL; }
    void * q = heap_caps_realloc(p, new_size, PSRAM_CAPS);     // moves an internal block out to PSRAM too
    return q ? q : realloc(p, new_size);
}

void lv_free_core(void * p) { free(p); }                        // free() handles either heap

void lv_mem_monitor_core(lv_mem_monitor_t * mon_p) { LV_UNUSED(mon_p); }
lv_result_t lv_mem_test_core(void) { return LV_RESULT_OK; }
#endif
