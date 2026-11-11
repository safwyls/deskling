"""Write the stand as one STEP assembly: named, coloured parts in their places, plus stand-ins for the bought parts."""
import os, sys
import cadquery as cq
import crowpanel_stand_v7 as m   # run from this folder; DESKLING_PORT / DESKLING_POWER pick the version
from crowpanel_stand_v7 import fuse

name = sys.argv[1]
asm = cq.Assembly(name=name)
asm.add(m.shell, name="shell", color=cq.Color(0.85, 0.92, 0.97, 0.6))
if m.POWER == "18650":
    asm.add(m.base, name="deck", color=cq.Color(0.62, 0.83, 0.96))
    asm.add(m.tray, name="tray", color=cq.Color(0.55, 0.61, 0.69))
    asm.add(m.cell, name="ref_18650_cell", color=cq.Color(0.36, 0.42, 0.50))
    asm.add(m.pmu, name="ref_ip5306_board", color=cq.Color(0.17, 0.48, 0.34))
else:
    asm.add(m.base, name="base", color=cq.Color(0.62, 0.83, 0.96))
if m.PORT == "usbc":
    asm.add(m.pigtail, name="ref_usbc_breakout", color=cq.Color(0.17, 0.48, 0.34))
asm.add(m.cable, name="ref_display_cable", color=cq.Color(0.16, 0.18, 0.22))
asm.add(fuse(*m.knob.values()), name="ref_display", color=cq.Color(0.10, 0.10, 0.11))
asm.save(name + ".step")
print("wrote", name + ".step")
