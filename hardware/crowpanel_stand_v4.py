"""CrowPanel 1.46" rotary display desk stand, v4 - exact B-rep (CadQuery / OpenCascade).

v4: battery power. The inline JRC-B008 pigtail is replaced by a USB-C breakout board at the rear port (22 x 13 mm,
VBUS/GND/CC1/D-/D+/CC2 pads, 5.1k CC pull-downs fitted) and an IP5306 charge + 5 V boost board (HW-775, 25 x 16 mm)
on the right of the base, opposite the 18350 cradle. Its KEY button is pressed through a hole in the right wall and
its four charge LEDs glow through the shell. The display is fed by its own MX1.25-to-USB cable (J37: VUSB, D-, D+,
GND) with the USB end cut off: 5 V from the IP5306, D-/D+ straight from the breakout, so the stand's port flashes it. To fit the 22 mm breakout the rear is a little squarer, the lip in
front of the socket is 1.0 mm, and the rear base screws move out to x = +-16.

Three parts:
  shell    : sloped glow body. The knob loads from BELOW through a tilted bore, butts against a printed stop
             shoulder, and a lip + collar hides everything below the black grip band.
  retainer : printed ring that pushes the knob's bottom plate up against the stop; front ear held by 2x M3
             screws threaded up into the shell (from beneath, base removed).
  base     : floor plate, 4x M3 countersunk screws into heat-set inserts in the shell, pins for the USB-C breakout,
             a pocket for the IP5306 board, straight wire trough, 18350 cell cradle.

World: Z up (desk z=0), -Y toward the user. Units mm.
Run:  python3 stand_cq.py        -> crowpanel_shell.step / crowpanel_base.step (+ STL)
Needs: pip install cadquery
"""
import numpy as np
import cadquery as cq
from cadquery import Solid, Vector

# ---------------- parameters ----------------
BETA        = 60.0    # screen-normal elevation; screen face is 90-BETA = 30 deg from horizontal
BASE_D      = 60.15   # CrowPanel fixed base diameter (Elecrow STEP)
POCKET_CLR  = 0.25
WALL        = 1.6
CUP_TOP     = 12.4
FLOOR_T     = 2.4
SCREW_RECESS_R, SCREW_RECESS_D = (24.5, 28.9), 1.0
FLOOR_HOLE_R = 24.5
RR          = 2.0     # rim fillet
CHAMBER_R   = 27.2
RIBS, RIB_H, RIB_W = 6, 0.35, 1.2
LIFT        = 8.5     # cup lift (clears the straight cable run under the cup front)

BASE_T      = 3.0     # base plate thickness (room for M3 countersunk heads)
# USB-C breakout board: 22 x 13 mm, receptacle mounted on top, its mouth overhanging one long edge; two mounting
# holes beside the receptacle 16.3 mm apart; six pads along the opposite edge. Seller's sizes are +-1-2 mm: measure.
BRK_W, BRK_D, BRK_T = 22.0, 13.0, 1.6   # across x, deep in y, PCB thickness
BRK_OVER    = 1.0                 # receptacle mouth stands this far past the PCB edge
BRK_HOLE_X, BRK_HOLE_IN, BRK_HOLE_D = 8.15, 2.5, 3.0   # holes at x = +-8.15, 2.5 mm in from the receptacle edge
PIN_D       = 2.6                 # printed locating pins through those holes
RECEP_W, RECEP_H = 8.94, 3.26     # USB-C receptacle metal shell (standard), sitting on the breakout PCB
PLUG_W, PLUG_H   = 8.45, 2.65     # opening in the outer lip (plug passes, receptacle can't pull out)
LIP_T       = 1.0
# IP5306 charge/boost board (HW-775): 25 x 16 mm, pads OUT-5V/GND/BAT/GND/GND/VIN on one short edge, a side-pressed
# KEY switch on a long edge near the other end, next to four charge LEDs. Lies flat, long axis along y, pads to the
# rear (short wires to the breakout, the trough and the cradle), KEY facing the right wall.
PMU_L, PMU_W, PMU_T = 25.0, 16.0, 1.0   # length (y), width (x), PCB thickness
PMU_H       = 5.0                 # overall height above the base with the inductor
PMU_X1      = 27.6                # its +x edge (the KEY side)
PMU_Y0      = 13.5                # its front end (the LED / KEY end)
KEY_IN      = 3.5                 # KEY centre, in from the front end (measure)
KEY_OUT, KEY_HALF_H = 1.5, 1.6    # how far the KEY actuator stands past the edge; its half height
KEY_HOLE_D  = 3.2                 # hole in the right wall: press the KEY with a pen tip
PMU_RELIEF  = (6.0, 5.0, 1.2)     # pocket in the base under the KEY's through-hole legs (y, x, depth)
CABLE_D     = 6.0
STRAIGHT    = 50.0    # wire run: from the breakout's pads forward to the bend
TUNNEL_W, TUNNEL_H = 16.0, 7.6   # the wire trough's room in the shell
BEND_R      = 12.0    # centreline radius for the pigtail's upward bend after the straight run
SKIRT_H     = 10.5    # vertical skirt height all round (holds the USB-C port)
SIDE_PAD    = 0.8     # extra half-width of the footprint beyond the cup radius
RIM_SPLIT   = 30.0    # rim arc (deg) mapped to each straight side in the loft
INNER_EXTRA = 1.0     # extra inset of the inner skin's lower loft section (sloped walls)
WALL_B      = 2.2     # body wall / roof thickness around the hollow under the slope

# ---- knob loads from below: bore, stop shoulder, hiding lip + collar (local frame, a = 0 at knob bottom plate)
STOP_A      = 7.0     # top of the knob's fixed base band: stops against the printed shoulder here
SKIRT_CLR_R = 29.15   # bore above the stop: clears the turning lit skirt (r 28.83)
LIP_A       = 12.3    # underside of the lip (knob ledge / notch top is at 12.1)
LIP_R       = 27.25   # lip bore: clears the black grip band (r 27.0)
TOP_A       = 17.5    # top of the collar (grip band starts at 17.0, so the seam is hidden)
COLLAR_R    = 29.6    # collar outer radius
# ---- retainer ring
RET_RIN, RET_ROUT = 24.6, 30.0     # ring clears buttons (r<=24.1) and screw heads (r 23.5-28.5, 0.7 proud)
RET_RIM     = 28.9                 # contact rim on the bottom plate's outer edge
RET_PRELOAD = 0.25                 # rim stands this far proud of the ear seat -> spring preload on the knob
RET_TOP     = -1.2                 # ring top (clears 1 mm underside protrusions)
RET_PLATE   = 3.0
RET_FLANGE  = (28.5, -12.0)        # stiffening flange inner radius / bottom
EAR_U, EAR_V = 9.5, (-35.5, -29.5) # front ear half-width and radial extent (in -v = front)
EAR_SCREWS  = [(-5.5, -32.2), (5.5, -32.2)]
ANCHOR_D, ANCHOR_L = 2.6, 6.5      # M3 thread-forming pilot in the shell above the ear (set 4.0 for heat-set inserts)
# ---- base screws (M3 countersunk from below into heat-set inserts in shell bosses)
BASE_SCREWS = [(-17.0, -24.5), (17.0, -24.5), (-16.0, 49.0), (16.0, 49.0)]   # v4: rear pair out to +-16
INSERT_D, INSERT_L, BOSS_D2 = 4.0, 6.0, 7.5
CSK_D, CLEAR_D = 6.4, 3.4
# ---- 18350 cradle (left of the cable run)
CELL_D, CELL_L = 18.0, 35.0
CR_X, CELL_Y0 = -15.4, -0.5        # cell axis x; front end of the cell (y)
CR_SIDE, CR_END, CR_FLOOR = 1.2, 2.0, 1.0
FRONT_GAP, REAR_GAP = 2.5, 4.0     # room for the flat contact (front) and the spring contact (rear)
SADDLES = (2.5, 17.0, 31.5)        # y centres of the U-saddles that carry the cell
CONTACT_W, CONTACT_T = 12.5, 0.9   # slots for flat/spring battery contact plates (18650-holder style)

R_POCKET = BASE_D / 2 + POCKET_CLR
R_OUT    = R_POCKET + WALL
A0, A1   = -FLOOR_T, CUP_TOP
TILT     = 90 - BETA
sb, cb   = np.sin(np.radians(BETA)), np.cos(np.radians(BETA))
CZ       = LIFT - A0 * sb + R_OUT * cb
th = np.radians(TILT)
ROT = np.array([[1, 0, 0], [0, np.cos(th), -np.sin(th)], [0, np.sin(th), np.cos(th)]])
OFF = np.array([0, 0, CZ])
BIG = 300.0

def place(s):            # device-local frame (z = cup axis, 0 = device bottom plate) -> world
    return s.rotate(Vector(0, 0, 0), Vector(1, 0, 0), TILT).translate(Vector(0, 0, CZ))

def to_local(p):
    return (np.asarray(p) - OFF) @ ROT

def to_world(p):
    return np.asarray(p) @ ROT.T + OFF

def lcyl(r, a0, a1):
    return Solid.makeCylinder(r, a1 - a0, Vector(0, 0, a0))

def lcone(r0, r1, a0, a1):
    return Solid.makeCone(r0, r1, a1 - a0, Vector(0, 0, a0))

def box(x0, x1, y0, y1, z0, z1):
    return Solid.makeBox(x1 - x0, y1 - y0, z1 - z0, Vector(x0, y0, z0))

def fuse(*s):
    out = s[0]
    for t in s[1:]:
        out = out.fuse(t)
    return out.clean()

def ellipse_prism(cy, rx, ry, z0, z1):
    return cq.Workplane("XY").workplane(offset=z0).center(0, cy).ellipse(rx, ry).extrude(z1 - z0).val()

def halfspace_local_a_below(a):          # {local axial <= a}, as a big placed box
    return place(box(-BIG, BIG, -BIG, BIG, -BIG, a))

def disc_shadow(r, a, z0=-1.0, z1=BIG):  # vertical prism under a placed disc of radius r at axial a
    c = to_world([0, 0, a])
    return ellipse_prism(c[1], r, r * sb, z0, z1)

def stadium_y(w, h, y0, y1, x, z):       # rounded slot prism along +Y centred at (x, z)
    s = cq.Workplane("XY").slot2D(w, h).extrude(y1 - y0).val()
    return s.rotate(Vector(0, 0, 0), Vector(1, 0, 0), -90).translate(Vector(x, y0, z))

def prism_y(pts_xz, y0, y1):             # polygon in XZ extruded along +Y
    w = cq.Workplane("XY").polyline(pts_xz).close().extrude(y1 - y0).val()
    return w.rotate(Vector(0, 0, 0), Vector(1, 0, 0), -90).translate(Vector(0, y0, 0)).mirror("XZ", (0, y0 + (y1 - y0) / 2, 0)) \
        if False else w.rotate(Vector(0, 0, 0), Vector(1, 0, 0), 90).translate(Vector(0, y1, 0))

def prism_x(pts_yz, x0, x1):             # polygon in YZ extruded along +X
    w = cq.Workplane("YZ").polyline(pts_yz).close().extrude(x1 - x0).val()
    return w.translate(Vector(x0, 0, 0))

# ---------------- straight cable run placement ----------------
zcab = BASE_T + CABLE_D / 2
cot = cb / sb
# the bend rises through the floor hole; its ceiling is the device underside (protrusions 1 mm proud)
z_need = zcab + BEND_R + CABLE_D / 2 + 1.0
y_end = BEND_R + (z_need - CZ + 1.0 / sb) / cot             # straight run ends here, bend rises at y_end - BEND_R
y_face = y_end + STRAIGHT                                   # USB-C receptacle face
y_back = y_face + LIP_T                                     # rear-most point of the body

def below_line_yz(p1, p2, drop=0.0):
    """Solid region below the X-parallel plane through (y,z) points p1, p2, lowered by `drop` (perpendicular)."""
    (y1, z1), (y2, z2) = p1, p2
    t = np.array([y2 - y1, z2 - z1]); t /= np.linalg.norm(t)
    nrm = np.array([-t[1], t[0]])
    if nrm[1] < 0: nrm = -nrm                                 # upward normal
    q1 = np.array([y1, z1]) - drop * nrm
    A, B = q1 - 1000 * t, q1 + 1000 * t
    return prism_x([tuple(A), tuple(B), (B[0], -BIG), (A[0], -BIG)], -BIG, BIG)

# ---------------- outer body: skirt + loft from the footprint up to a circular frame round the cup ----------------
# plan: front half-ellipse = shadow of the cup's top rim, straight sides, rounded rear ending at y_back.
# A vertical skirt (SKIRT_H, holds the USB-C port) then a ruled loft up to the cup's circular top rim.
cB = to_world([0, 0, A1])
REAR_RY = 0.32 * R_OUT          # v4: squarer rear (was 0.55), room for the 22 mm breakout's corners
yr0 = y_back - REAR_RY
Rx = R_OUT + SIDE_PAD

def outline_wire(dw, z):
    """Footprint outline (shrunk by dw) at height z, 4 edges: front arc, right side, rear arc, left side."""
    V = Vector
    e = [cq.Edge.makeEllipse(Rx - dw, R_OUT * sb - dw, V(0, cB[1], z), V(0, 0, 1), V(1, 0, 0), 180, 360),
         cq.Edge.makeLine(V(Rx - dw, cB[1], z), V(Rx - dw, yr0, z)),
         cq.Edge.makeEllipse(Rx - dw, REAR_RY - dw, V(0, yr0, z), V(0, 0, 1), V(1, 0, 0), 0, 180),
         cq.Edge.makeLine(V(-(Rx - dw), yr0, z), V(-(Rx - dw), cB[1], z))]
    return cq.Wire.assembleEdges(e)

def rim_wire(r, a):
    """Circle of radius r on the cup's local plane at axial a, split into 4 arcs matching outline_wire."""
    arcs = [(180, 360), (0, RIM_SPLIT), (RIM_SPLIT, 180 - RIM_SPLIT), (180 - RIM_SPLIT, 180)]
    e = [place(cq.Edge.makeCircle(r, Vector(0, 0, a), Vector(0, 0, 1), a1, a2)) for a1, a2 in arcs]
    return cq.Wire.assembleEdges(e)

def plan_prism(dw, z0, z1):
    return Solid.extrudeLinear(cq.Face.makeFromWires(outline_wire(dw, z0)), Vector(0, 0, z1 - z0))

skirt = plan_prism(0.0, 0, SKIRT_H)
loft = Solid.makeLoft([outline_wire(0.0, SKIRT_H), rim_wire(R_OUT, A1)], True)
body_raw = fuse(skirt, loft)
cyl_out = place(lcyl(R_OUT, A0, A1))
print("cup wall poking outside the loft: %.4f mm3" % cyl_out.cut(body_raw).Volume())
rim_edges = [e for e in body_raw.Edges() if abs(to_local(e.Center().toTuple())[2] - A1) < 1e-3]
crease = [e for e in body_raw.Edges() if abs(e.BoundingBox().zmin - SKIRT_H) < 1e-3 and abs(e.BoundingBox().zmax - SKIRT_H) < 1e-3]
body = body_raw.fillet(RR, rim_edges)
# (the skirt/loft crease is left sharp: the loft is vertical at the front, so OCC can't fillet that edge)

# inner skin for the hollow: same construction shrunk by WALL_B (rim circle lowered/shrunk so the sloped
# walls stay ~WALL_B thick; checked by ray-cast thickness test)
inner = fuse(plan_prism(WALL_B + INNER_EXTRA, BASE_T - 0.02, SKIRT_H),
             Solid.makeLoft([outline_wire(WALL_B + INNER_EXTRA, SKIRT_H), rim_wire(R_OUT - WALL_B, A1 - WALL_B)], True))

# ---------------- knob bore, stop shoulder, lip + collar ----------------
def below_45(yz):                     # region under a 45 deg plane rising toward the back through point (y, z)
    k = yz[1] - yz[0] - 0.01
    return prism_x([(-BIG, k - BIG), (BIG, k + BIG), (BIG, -BIG), (-BIG, -BIG)], -BIG, BIG)

collar_prof = (cq.Workplane("XZ").moveTo(LIP_R - 0.5, LIP_A).lineTo(R_OUT - 1.2, LIP_A)
               .lineTo(COLLAR_R, LIP_A + (R_OUT - 1.2 - COLLAR_R)).lineTo(COLLAR_R, TOP_A - 0.8)
               .lineTo(COLLAR_R - 0.8, TOP_A).lineTo(LIP_R - 0.5, TOP_A).close())
collar = place(collar_prof.revolve(360, (0, 0, 0), (0, 1, 0)).val())
body = fuse(body, collar)

knob_bore = place(fuse(lcyl(R_POCKET, -BIG, STOP_A),            # knob path from below + base band seat
                       lcyl(SKIRT_CLR_R, STOP_A - 0.01, LIP_A),  # turning lit skirt
                       lcyl(LIP_R, LIP_A - 0.01, TOP_A + 5)))     # grip band through the lip
ear_lobe = place(box(-(EAR_U + 0.5), EAR_U + 0.5, EAR_V[0] - 0.5, -26.0, -BIG, 0.0))   # also the screw-head path   # retainer ear path + seat

chamber = disc_shadow(CHAMBER_R, A0, BASE_T - 0.01).intersect(halfspace_local_a_below(A0))
rear = to_world([0, CHAMBER_R, A0])
chamber = chamber.intersect(below_45(rear[1:]))
shaft = disc_shadow(FLOOR_HOLE_R, A0 + 0.01, BASE_T - 0.01).intersect(halfspace_local_a_below(A0 + 0.01))
cup_rear_bottom = to_world([0, R_OUT, A0])
rear_cav = (inner.intersect(box(-BIG, BIG, -BIG, BIG, BASE_T - 0.01, BIG))
            .intersect(below_45(cup_rear_bottom[1:])).cut(place(lcyl(R_OUT + 0.4, A0, A1 + 40))))
tunnel = box(-TUNNEL_W / 2, TUNNEL_W / 2, y_end - 6.0, y_back - 2.0, BASE_T - 0.01, BASE_T + TUNNEL_H) \
    .intersect(fuse(inner, plan_prism(2.0, BASE_T - 0.02, SKIRT_H)))
base_bosses = fuse(*[Solid.makeCylinder(BOSS_D2 / 2, INSERT_L + 1.5, Vector(px, py, BASE_T - 0.02)) for px, py in BASE_SCREWS])
y_brk1 = y_face - BRK_OVER                                  # breakout PCB, receptacle edge
y_brk0 = y_brk1 - BRK_D                                     # breakout PCB, pad edge
brk_bay = box(-(BRK_W / 2 + 0.3), BRK_W / 2 + 0.3, y_brk0 - 0.5, y_brk1 + 0.3, BASE_T - 0.01, BASE_T + BRK_T + RECEP_H + 0.8)
pmu_bay = box(PMU_X1 - PMU_W - 0.4, PMU_X1 + KEY_OUT + 0.4, PMU_Y0 - 0.4, PMU_Y0 + PMU_L + 0.4, BASE_T - 0.01, BASE_T + PMU_H + 0.8)
cavity = fuse(chamber, shaft, tunnel, rear_cav, brk_bay, pmu_bay).cut(base_bosses)

zc = BASE_T + BRK_T + RECEP_H / 2  # receptacle sits on the breakout PCB, which sits on the base plate

def arch_y(w, h, y0, y1):            # stadium (w x h) centred at zc, extended straight down to below the base top
    return fuse(stadium_y(w, h, y0, y1, 0, zc), box(-w / 2, w / 2, y0, y1, BASE_T - 1.0, zc))

chamfer = (cq.Workplane("XY").slot2D(PLUG_W, PLUG_H).workplane(offset=3.0).slot2D(PLUG_W + 1.5, PLUG_H + 1.5)
           .loft().val().rotate(Vector(0, 0, 0), Vector(1, 0, 0), -90).translate(Vector(0, y_back, zc)))
port = fuse(arch_y(RECEP_W + 0.4, RECEP_H + 0.4, y_back - 8, y_face),
            arch_y(PLUG_W, PLUG_H, y_back - 8, y_back + 5), chamfer)

key_y, key_z = PMU_Y0 + KEY_IN, BASE_T + PMU_T + KEY_HALF_H
key_hole = Solid.makeCylinder(KEY_HOLE_D / 2, 20, Vector(PMU_X1, key_y, key_z), Vector(1, 0, 0))
full = body.cut(cavity).cut(knob_bore).cut(ear_lobe).cut(port).cut(key_hole)
split = box(-BIG, BIG, -BIG, BIG, -10, BASE_T)
shell = full.cut(split)

# retainer anchors: M3 pilots straight up (along the knob axis) from the ear seat
anchors = fuse(*[place(Solid.makeCylinder(ANCHOR_D / 2, ANCHOR_L, Vector(u, v, -0.01))) for u, v in EAR_SCREWS])
# base screw inserts (vertical, from the shell's bottom face)
inserts = fuse(*[Solid.makeCylinder(INSERT_D / 2, INSERT_L, Vector(px, py, BASE_T - 0.01)) for px, py in BASE_SCREWS])
shell = shell.cut(anchors).cut(inserts).clean()

# ---------------- retainer ring (local frame, then placed) ----------------
rf_r, rf_a = RET_FLANGE
ret_prof = (cq.Workplane("XZ").moveTo(RET_RIN, RET_TOP).lineTo(RET_RIM, RET_TOP).lineTo(RET_RIM, RET_PRELOAD)
            .lineTo(RET_ROUT, RET_PRELOAD).lineTo(RET_ROUT, rf_a).lineTo(rf_r, rf_a)
            .lineTo(rf_r, RET_TOP - RET_PLATE - (rf_r - RET_RIN)).lineTo(RET_RIN, RET_TOP - RET_PLATE).close())
ret_local = ret_prof.revolve(360, (0, 0, 0), (0, 1, 0)).val()
ear = box(-EAR_U, EAR_U, EAR_V[0], EAR_V[1], rf_a, 0.0).cut(lcyl(RET_RIN + 0.01, -BIG, BIG))
ret_local = ret_local.fuse(ear)
for u, v in EAR_SCREWS:      # clearance hole + deep counterbore for the socket head (screw from beneath)
    ret_local = ret_local.cut(Solid.makeCylinder(CLEAR_D / 2, 20, Vector(u, v, rf_a - 1))) \
                         .cut(Solid.makeCylinder(3.2, (-3.0) - (rf_a - 1), Vector(u, v, rf_a - 1)))
ret_local = ret_local.clean()
# trim everything below the base plate's top so the retainer sits flush on the base
RET_BASE_GAP = 0.0
retainer = place(ret_local).intersect(box(-BIG, BIG, -BIG, BIG, BASE_T + RET_BASE_GAP, BIG)).clean()

# ---------------- base plate ----------------
base = plan_prism(0.0, 0, BASE_T)
for px, py in BASE_SCREWS:   # countersunk M3 from below
    base = base.cut(Solid.makeCylinder(CLEAR_D / 2, BASE_T + 1, Vector(px, py, -0.5))) \
               .cut(Solid.makeCone(CSK_D / 2 + 0.5, 0.0, CSK_D / 2 + 0.5, Vector(px, py, -0.5)))

yb0 = y_brk0                         # the wires leave from the breakout's pad edge
feats = []
for sgn in (-1, 1):                  # locating pins through the breakout's two holes (a drop of glue or a hot iron to set)
    feats.append(Solid.makeCylinder(PIN_D / 2, BRK_T + 0.8, Vector(sgn * BRK_HOLE_X, y_brk1 - BRK_HOLE_IN, BASE_T - 0.01)))
# IP5306: low rails along both sides and the front (it has no holes; a dab of glue holds it). The rear, where the
# pads are, stays open for the wires, and the right rail stops short of the KEY.
px0, px1, py0, py1 = PMU_X1 - PMU_W, PMU_X1, PMU_Y0, PMU_Y0 + PMU_L
feats.append(box(px0 - 1.45, px0 - 0.25, py0 + 2.0, py1 - 3.0, BASE_T - 0.01, BASE_T + 2.0))           # left
feats.append(box(px1 + 0.25, px1 + 1.45, key_y + 4.0, py1 - 3.0, BASE_T - 0.01, BASE_T + 2.0))         # right, behind the KEY
feats.append(box(px0 + 2.0, px1 - 6.0, py0 - 1.45, py0 - 0.25, BASE_T - 0.01, BASE_T + 2.0))           # front
for d in (24.0, 37.0, 49.0):
    yc = y_face - d
    blk = box(-(CABLE_D / 2 + 1.6), CABLE_D / 2 + 1.6, yc - 1.5, yc + 1.5, BASE_T - 0.01, zcab + CABLE_D / 2 - 0.6)
    bore = Solid.makeCylinder(CABLE_D / 2 + 0.15, 6, Vector(0, yc - 3, zcab), Vector(0, 1, 0))
    mouth = box(-2.6, 2.6, yc - 3, yc + 3, zcab, zcab + 10)
    lead = prism_y([(-2.6, zcab + 1.2), (2.6, zcab + 1.2), (4.2, zcab + 4), (-4.2, zcab + 4)], yc - 3, yc + 3)
    feats.append(blk.cut(bore).cut(mouth).cut(lead))

# 18350 cradle: three snap-in U-saddles (horns rise 3 mm past the cell centre) + narrow end walls holding
# contact plates; shaped to the free space under the sloped back, the retainer flange and the cable clips
rc = CELL_D / 2 + 0.3
ax_z = BASE_T + CR_FLOOR + rc
horn_top = ax_z + 3.0
end_top = ax_z + 4.5
cy0 = CELL_Y0 - FRONT_GAP                  # inner face of the front end wall
cy1 = CELL_Y0 + CELL_L + REAR_GAP          # inner face of the rear end wall
cr_parts = []
for ys in SADDLES:
    sd = box(CR_X - rc - CR_SIDE, CR_X + rc + CR_SIDE, ys - 2.5, ys + 2.5, BASE_T - 0.01, horn_top)
    sd = sd.cut(Solid.makeCylinder(rc, 6, Vector(CR_X, ys - 3, ax_z), Vector(0, 1, 0)))
    sd = sd.cut(box(CR_X - rc + 0.6, CR_X + rc - 0.6, ys - 3, ys + 3, ax_z, horn_top + 1))
    cr_parts.append(sd)
ew = CONTACT_W / 2 + 1.0
for y_in, sgn in ((cy0, -1), (cy1, 1)):
    y_a, y_b = sorted([y_in, y_in + sgn * CR_END])
    w = box(CR_X - ew, CR_X + ew, y_a, y_b, BASE_T - 0.01, end_top)
    ys = y_in + sgn * (0.6 if sgn > 0 else 0.6) - (CONTACT_T if sgn < 0 else 0)
    ys = min(y_a, y_b) + (CR_END - CONTACT_T) / 2
    w = w.cut(box(CR_X - CONTACT_W / 2, CR_X + CONTACT_W / 2, ys, ys + CONTACT_T, BASE_T + 1.5, end_top + 1))
    w = w.cut(box(CR_X - 1.2, CR_X + 1.2, y_a - 1, y_b + 1, BASE_T - 0.01, BASE_T + 2.2))      # wire notch
    cr_parts.append(w)
cr = fuse(*cr_parts)
feats.append(cr)
base = fuse(base, *feats)
rx, ry_, rd = PMU_RELIEF[1], PMU_RELIEF[0], PMU_RELIEF[2]   # under the KEY's legs
base = base.cut(box(PMU_X1 - rx, PMU_X1 + 0.01, key_y - ry_ / 2, key_y + ry_ / 2, BASE_T - rd, BASE_T + 0.1))
cell = Solid.makeCylinder(CELL_D / 2, CELL_L, Vector(CR_X, CELL_Y0, ax_z), Vector(0, 1, 0))

# ---------------- checks + export ----------------
def ov(a, b):
    try: return a.intersect(b).Volume()
    except ValueError: return 0.0
from OCP.BRepExtrema import BRepExtrema_DistShapeShape
def gap(a, b):
    d = BRepExtrema_DistShapeShape(a.wrapped, b.wrapped); d.Perform(); return d.Value()

pigtail = fuse(box(-BRK_W / 2, BRK_W / 2, y_brk0, y_brk1, BASE_T, BASE_T + BRK_T)          # breakout PCB
                   .cut(fuse(*[Solid.makeCylinder(BRK_HOLE_D / 2, 5, Vector(s_ * BRK_HOLE_X, y_brk1 - BRK_HOLE_IN, BASE_T - 1)) for s_ in (-1, 1)])),
               stadium_y(RECEP_W, RECEP_H, y_face - 7.35, y_face, 0, zc))                    # its receptacle
pmu = fuse(box(PMU_X1 - PMU_W, PMU_X1, PMU_Y0, PMU_Y0 + PMU_L, BASE_T, BASE_T + PMU_H),
           box(PMU_X1, PMU_X1 + KEY_OUT, key_y - 1.8, key_y + 1.8, BASE_T + PMU_T, BASE_T + PMU_T + 2 * KEY_HALF_H))
path = (cq.Workplane("YZ").moveTo(yb0, zcab).lineTo(y_end, zcab)
        .threePointArc((y_end - BEND_R * 0.70711, zcab + BEND_R * (1 - 0.70711)), (y_end - BEND_R, zcab + BEND_R))
        .lineTo(y_end - BEND_R, zcab + BEND_R + 2.0))
cable = cq.Workplane("XZ", origin=(0, yb0, zcab)).circle(CABLE_D / 2).sweep(path).val()
# knob proxies (Elecrow STEP): fixed base band, turning lit skirt, ledge/groove core, grip band, bottom plate
knob = {"base band r30.075 a0-7": place(lcyl(30.075, 0.0, 7.0)),
        "lit skirt r28.83 a7-12.1": place(lcyl(28.83, 7.0, 12.1)),
        "groove core r23.6 a12.1-17": place(lcyl(23.6, 12.1, 17.0)),
        "grip band r27.0 a17-28.3": place(lcyl(27.0, 17.0, 28.3)),
        "underside protrusions r24.1": place(lcyl(24.1, -1.0, 0.0)),
        "screw heads ring": place(lcyl(28.55, -0.7, 0.0).cut(lcyl(23.4, -0.7, 0.0)))}
knob_all = fuse(*knob.values())

if __name__ == "__main__":
    print("run: socket face y=%.1f, straight run ends y=%.1f, CZ %.2f" % (y_face, y_end, CZ))
    print("valid shell/base/retainer:", shell.isValid(), base.isValid(), retainer.isValid())
    print("faces shell/base/retainer:", len(shell.Faces()), len(base.Faces()), len(retainer.Faces()))
    print("mass g PETG shell/base/retainer: %.1f / %.1f / %.1f" % tuple(s.Volume() / 1000 * 1.27 for s in (shell, base, retainer)))
    bb = fuse(shell, base).BoundingBox(); print("overall %.1f x %.1f x %.1f" % (bb.xlen, bb.ylen, bb.zlen))
    print("-- clashes (mm3, want 0 unless noted)")
    for k, p in knob.items():
        print("  knob %-30s vs shell %.3f  vs retainer %.3f   min gap to shell %.2f" % (k, ov(p, shell), ov(p, retainer), gap(p, shell)))
    plate = place(lcyl(30.075, 0.0, 2.0))
    print("  retainer rim vs knob bottom plate (preload, expect ~%.2f mm deep ring): %.2f mm3" % (RET_PRELOAD, ov(retainer, plate)))
    print("  retainer vs shell %.3f   retainer->shell min gap %.3f" % (ov(retainer, shell), gap(retainer, shell)))
    print("  retainer vs base %.3f   retainer->base min gap %.3f   retainer lowest z %.3f (base top %.1f)"
          % (ov(retainer, base), gap(retainer, base), retainer.BoundingBox().zmin, BASE_T))
    for u, v in EAR_SCREWS:   # socket head (5.5 x 3) sitting at the counterbore floor must stay above the base
        head = place(Solid.makeCylinder(2.75, 3.0, Vector(u, v, -6.0)))
        print("  retainer screw head (%.1f,%.1f): lowest z %.2f, overlap with base %.3f" % (u, v, head.BoundingBox().zmin, ov(head, base)))
    print("  shell vs base %.3f   breakout vs shell %.3f / base %.3f (pins fill its holes: 0 expected)" % (ov(shell, base), ov(pigtail, shell), ov(pigtail, base)))
    print("  breakout bay: wall to outside %.2f" % gap(brk_bay, box(-BIG, BIG, -BIG, BIG, BASE_T, BIG).cut(body)))
    print("  IP5306 vs shell %.3f / base %.3f / retainer %.3f / knob %.3f / cell %.3f / cable %.3f   gap to shell %.2f, cradle %.2f, cable %.2f"
          % (ov(pmu, shell), ov(pmu, base), ov(pmu, retainer), ov(pmu, knob_all), ov(pmu, cell), ov(pmu, cable), gap(pmu, shell), gap(pmu, cr), gap(pmu, cable)))
    kh = Solid.makeCylinder(KEY_HOLE_D / 2 - 0.1, 20, Vector(PMU_X1 + KEY_OUT + 0.2, key_y, key_z), Vector(1, 0, 0))
    print("  KEY: actuator tip x %.1f, gap to the shell %.2f; hole runs clear to the outside: %s"
          % (PMU_X1 + KEY_OUT, gap(box(PMU_X1, PMU_X1 + KEY_OUT, key_y - 1, key_y + 1, key_z - 1, key_z + 1), shell), ov(kh, shell) < 1e-6))
    print("  cable+bend vs shell %.3f / base %.3f / retainer %.3f / knob %.3f" % (ov(cable, shell), ov(cable, base), ov(cable, retainer), ov(cable, knob_all)))
    print("  18350 cell vs shell %.3f / retainer %.3f / knob %.3f / base %.3f" % (ov(cell, shell), ov(cell, retainer), ov(cell, knob_all), ov(cell, base)))
    print("  cradle min gap to shell %.2f, to retainer %.2f, to knob %.2f" % (gap(cr, shell), gap(cr, retainer), gap(cr, knob_all)))
    # anchor + insert wall checks: distance from each hole to the shell's outer/inner surfaces
    for u, v in EAR_SCREWS:
        h = place(Solid.makeCylinder(ANCHOR_D / 2, ANCHOR_L, Vector(u, v, 0)))
        print("  ear anchor (%.1f,%.1f): pilot breaks out %.3f mm3; wall to outside %.2f" % (u, v, h.cut(body).Volume(), gap(h, box(-BIG, BIG, -BIG, BIG, -BIG, BIG).cut(body))))
    for px, py in BASE_SCREWS:
        h = Solid.makeCylinder(INSERT_D / 2, INSERT_L, Vector(px, py, BASE_T))
        print("  base insert (%.1f,%.1f): into cavity/bore %.3f mm3, wall to outside %.2f, to knob bore %.2f"
              % (px, py, ov(h, fuse(cavity, knob_bore)), gap(h, box(-BIG, BIG, -BIG, BIG, -BIG, BIG).cut(body)), gap(h, knob_bore)))
    # retainer screw path: from the ear straight down the axis must exit the shell without hitting it
    for u, v in EAR_SCREWS:
        sp = place(Solid.makeCylinder(3.0, 60, Vector(u, v, -60)))
        print("  screwdriver path below ear (%.1f,%.1f) vs shell %.3f" % (u, v, ov(sp, shell)))
    # how the knob gets in: its swept volume from below must miss the shell (except the stop contact)
    sweep = place(fuse(lcyl(30.075, -80, 7.0), lcyl(28.83, -80, 12.1), lcyl(27.0, -80, 28.3)))
    print("  knob insertion sweep vs shell %.3f" % ov(sweep, shell))
    ret_sweep = place(fuse(lcyl(RET_ROUT, -80, RET_PRELOAD), box(-EAR_U, EAR_U, EAR_V[0], EAR_V[1], -80, 0)))
    print("  retainer insertion sweep vs shell %.3f" % ov(ret_sweep, shell))

    cq.exporters.export(cq.Workplane().add(shell), "v4_shell.step")
    cq.exporters.export(cq.Workplane().add(base), "v4_base.step")
    cq.exporters.export(cq.Workplane().add(retainer), "v4_retainer_assembled.step")
    # print in the ring's own frame, flange down (trimmed front needs <=5.3 mm build-plate supports)
    ret_print = retainer.translate(Vector(0, 0, -CZ)).rotate(Vector(0, 0, 0), Vector(1, 0, 0), -TILT)
    ret_print = ret_print.translate(Vector(0, 0, -ret_print.BoundingBox().zmin))
    cq.exporters.export(cq.Workplane().add(ret_print), "v4_retainer.step")
    def stl(shape, fn):
        import trimesh
        from scipy.spatial import cKDTree
        from OCP.BRepMesh import BRepMesh_IncrementalMesh
        from OCP.StlAPI import StlAPI_Writer
        BRepMesh_IncrementalMesh(shape.wrapped, 0.02, False, 0.1, True)
        StlAPI_Writer().Write(shape.wrapped, fn)
        t = trimesh.load(fn, process=False)
        v, f = t.vertices.copy(), t.faces.copy()
        parent = np.arange(len(v))
        def root(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]; i = parent[i]
            return i
        for i, j in cKDTree(v).query_pairs(1e-5):
            ri, rj = root(i), root(j)
            if ri != rj: parent[max(ri, rj)] = min(ri, rj)
        f = np.array([[root(k) for k in tri] for tri in f])
        f = f[(f[:, 0] != f[:, 1]) & (f[:, 1] != f[:, 2]) & (f[:, 0] != f[:, 2])]
        t = trimesh.Trimesh(v, f, process=True); t.export(fn)
        print(fn, "watertight", t.is_watertight)
    stl(shell.translate(Vector(0, 0, -BASE_T)), "v4_shell.stl")
    stl(base, "v4_base.stl")
    stl(ret_print, "v4_retainer.stl")
