#include "crashlog.h"
#include <esp_system.h>
#include <esp_core_dump.h>
#include <esp_app_desc.h>

#include <esp_heap_caps.h>
#include <esp_attr.h>

struct HeapNote { uint32_t magic, freeB, largest, minB; char where[16]; };
static RTC_NOINIT_ATTR HeapNote note;
static const uint32_t NOTE_MAGIC = 0x4e4f5445;

void crashlog_heap(uint32_t &freeB, uint32_t &minB, uint32_t &largest) {
  freeB = heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  minB = heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  largest = heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
}

void crashlog_note(const char *where) {
  crashlog_heap(note.freeB, note.minB, note.largest);
  strlcpy(note.where, where, sizeof(note.where));
  note.magic = NOTE_MAGIC;
}
void crashlog_note_done() { note.magic = 0; }

static const char *reason = "unknown";
static bool bad = false, dump = false;
static char summary[160] = "";
static char qr[400] = "";

void crashlog_begin() {
  esp_reset_reason_t r = esp_reset_reason();
  switch (r) {
    case ESP_RST_POWERON:   reason = "power on"; break;
    case ESP_RST_SW:        reason = "restart"; break;
    case ESP_RST_PANIC:     reason = "crash"; break;
    case ESP_RST_INT_WDT:   reason = "interrupt watchdog"; break;
    case ESP_RST_TASK_WDT:  reason = "task watchdog"; break;
    case ESP_RST_WDT:       reason = "watchdog"; break;
    case ESP_RST_BROWNOUT:  reason = "brownout (power dip)"; break;
    case ESP_RST_EXT:       reason = "reset pin"; break;
    case ESP_RST_USB:       reason = "USB reset"; break;
    case ESP_RST_DEEPSLEEP: reason = "wake from sleep"; break;
    default:                reason = "unknown"; break;
  }
  bad = r == ESP_RST_PANIC || r == ESP_RST_INT_WDT || r == ESP_RST_TASK_WDT || r == ESP_RST_WDT || r == ESP_RST_BROWNOUT;

  char mine[17] = "";
  esp_app_get_elf_sha256(mine, sizeof(mine));
  int n = snprintf(qr, sizeof(qr), "dk reset=%s fw=%.8s", reason, mine);
  if (note.magic == NOTE_MAGIC && r != ESP_RST_POWERON) {        // it went down during a noted step
    note.where[sizeof(note.where) - 1] = 0;
    n += snprintf(qr + n, sizeof(qr) - n, " during=%s ram=%lu/%lu/%lu", note.where, (unsigned long)note.freeB,
                  (unsigned long)note.largest, (unsigned long)note.minB);
  }
  note.magic = 0;

  esp_core_dump_summary_t *s = (esp_core_dump_summary_t *)malloc(sizeof(esp_core_dump_summary_t));
  if (s && esp_core_dump_image_check() == ESP_OK && esp_core_dump_get_summary(s) == ESP_OK) {
    dump = true;
    char why[96] = "";
    esp_core_dump_get_panic_reason(why, sizeof(why));
    for (char *c = why; *c; c++) if (*c == '\n' || *c == '\r') *c = ' ';
    const char *dsha = (const char *)s->app_elf_sha256;
    bool same = !strncmp(dsha, mine, 8);
    snprintf(summary, sizeof(summary), "%s\nin %s%s", why[0] ? why : "crash", s->exc_task, same ? "" : " (older firmware)");
    n += snprintf(qr + n, sizeof(qr) - n, " dump=%.8s task=%s pc=%08lx cause=%lu addr=%08lx why=%s bt=", dsha, s->exc_task,
                  (unsigned long)s->exc_pc, (unsigned long)s->ex_info.exc_cause, (unsigned long)s->ex_info.exc_vaddr, why);
    for (uint32_t i = 0; i < s->exc_bt_info.depth && i < 10 && n < (int)sizeof(qr) - 10; i++)
      n += snprintf(qr + n, sizeof(qr) - n, "%s%08lx", i ? "," : "", (unsigned long)s->exc_bt_info.bt[i]);
    if (s->exc_bt_info.corrupted) snprintf(qr + n, sizeof(qr) - n, " (bt corrupted)");
  } else if (bad) {
    snprintf(summary, sizeof(summary), "%s", r == ESP_RST_BROWNOUT ? "the supply voltage dipped" : "no crash dump saved");
  }
  free(s);
  Serial.printf("[boot] last reset: %s\n", reason);
  if (dump) Serial.printf("[boot] crash report: %s\n", qr);
}

const char *crashlog_reason() { return reason; }
bool crashlog_bad() { return bad; }
bool crashlog_has_dump() { return dump; }
const char *crashlog_summary() { return summary; }
const char *crashlog_qr() { return qr; }
void crashlog_clear() {
  esp_core_dump_image_erase();
  dump = false;
  summary[0] = 0;
}
