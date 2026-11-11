# Hardware

The desk stand for the CrowPanel 1.46" rotary display: two printed parts, a shell and a deck, with a USB-C port in
the back. Version 8 was reworked by hand in CAD from the v7 script's output.

| File | Part |
| --- | --- |
| `deskling_v8_shell.3mf` / `.step` / `.stl` | The shell (65 × 65 × 54 mm). The display presses in from below through a tilted bore and stops on a printed shoulder; a collar hides everything below its black grip band. Four heat-set inserts (two front, one each side) take the deck's screws, and two sockets inside take its posts. Translucent PETG carries the display's LED skirt out as a glow. |
| `deskling_v8_deck.3mf` / `.step` / `.stl` | The deck (65 × 64 × 42 mm): the floor and the curved back wall in one piece. The back wall has the USB-C port; the floor has pins for the USB-C breakout, clips for the cable, and three posts that stand under the rim of the display's bottom plate. Two tall posts locate it in the shell. |
| `deskling_v8_assembly.step` | Both parts in place as one STEP assembly, with stand-ins for the display, the breakout and the cable. |
| `v7/` | The v7 design and the parametric CadQuery script that generated it (`crowpanel_stand_v7.py`), including the cable version, which keeps the display's cable whole. |
| `v6/`, `v5/`, `v4/`, `v3/` | Earlier versions. |

The STEP files share one frame (desk at z = 0, the front toward -Y) and line up when imported together. The 3MF and
STL files are the print meshes in that same frame; any slicer drops them onto the bed.

## Screws and parts

- 4 × M3 heat-set inserts in the shell (4.0 mm holes, 6 mm deep).
- 4 × M3×8 countersunk screws up through the deck into the inserts.
- A 22 × 13 mm USB-C breakout (VBUS/GND/CC1/D-/D+/CC2, 5.1 kΩ CC pull-downs fitted) on the deck's two pins (16.4 mm
  apart); melt the pin tops over its holes.
- The display's MX1.25-to-USB cable with its USB end cut off: VBUS → red (pin 1), D− → white (2), D+ → green (3),
  GND → black (4) at J37, the display's USB connector, which goes straight to the ESP32-S3's USB pins. CC1/CC2 stay
  unconnected. The port powers and flashes the display.

## Measure first

The shoulder in the shell and the posts in the deck assume the display's fixed base is 7.0 mm tall above its bottom
plate, from Elecrow's 3D model, which has been wrong around the skirt before. Measure yours before printing.

The website shows the parts in 3D (`site/pages/wiki/models.html`) and covers printing, wiring and assembly
(`site/pages/wiki/print.html`).
