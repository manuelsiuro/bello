"""Bello's desk stand: the tablet is the Minion's head, the stand is its body in overalls.

Run headless from the repo root (scripts/stand.sh build does it):

    Blender -b --factory-startup -P hardware/stand/make_stand.py -- [out_dir]

Units are millimetres (Cura reads the STL numbers as mm). World axes: X across the tablet,
-Y towards the person looking at Bello, Z up. The stand is modelled in its print orientation:
upright, flat bottom on the bed, no supports needed — every face of the hollow body is vertical,
leans back like the tablet (20°) or slopes at 45°.

Tablet facts (docs/tablet-stand.md has the sources): Galaxy Tab 4 10.1 SM-T530,
243.4 x 176.4 x 8.0 mm, landscape. Micro-USB and microphone in the middle of the bottom edge,
speakers on both short edges, power/volume/microSD/jack on the top edge, front camera in the
top bezel, Home button in the middle of the 20 mm bottom bezel.
"""

import math
import os
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

# ---------------------------------------------------------------- the tablet
TAB_W = 243.4          # long edge (landscape width)
TAB_H = 176.4          # short edge (landscape height)
TAB_T = 8.0            # thickness (retailers say 7.95–8.0)
BEZEL_SIDE = 13.0      # (243.4 - 217.4 active width) / 2
BEZEL_TB = 20.2        # (176.4 - 136.0 active height) / 2
KEYS_X = 36.0          # capacitive Recent/Back keys sit within ±36 mm of the centre

# ---------------------------------------------------------------- the fit
CLR = 0.75             # clearance around the tablet in the pocket, each side
TILT = 20.0            # the tablet leans back this many degrees from vertical
ZL = 32.0              # height of the tablet's bottom front edge above the desk (plug room)
LIP_H = 5.0            # front lip height, measured up the tablet face (Home button starts ~7 mm)
LIP_T = 3.0            # front lip thickness at its top
USB_OFFSET_MM = 0.0    # port offset from the centre, + to the right; measure if the plug misses
SLOT_W = 30.0          # the plug slot is this wide, so the exact offset hardly matters

# ---------------------------------------------------------------- the body
BODY_W = 110.0         # width of the overalls (the tablet overhangs both sides: speakers free)
Y_FRONT = -14.0        # front of the overalls
SQUIRCLE = 4.0         # plan shape exponent: 2 = ellipse, higher = squarer capsule
SHELL = 1.2            # wall of the hollow body: three 0.4 mm lines (walls are most of the print time)
REST_W = 64.0          # back rest width (the tablet is rigid; a narrow rest is enough)
REST_T = 2.0           # back rest thickness
REST_H = 40.0          # back rest height up the tablet's back (the tablet leans on it; camera is higher)
REST_WINDOW = False    # a window in the back rest (only worth it for a taller rest)
HEEL_Y = 80.0          # a fin reaches back here: tip-over margin when the screen is tapped
HEEL_X = -14.0         # beside the cable, which leaves through the notch in the middle
HEEL_T = 1.2           # fin thickness: walls only, nothing slow to fill
HEEL_H = 9.0           # fin height where it leaves the body
NOTCH_W = 14.0         # cable exit at the back
NOTCH_H = 8.0

# ---------------------------------------------------------------- the Minion
BIB_W = 64.0
BIB_PROUD = 1.2        # how far the overalls bib stands out of the body
EMBLEM_R = 7.0
BIB_BOTTOM = 10.0      # the bib starts above the legs
LEGS_ARCH = (6.0, 8.0) # half width and height of the gap between the legs
HAND_X = None          # gloves hold the tablet near the ends of the lip (set below from BODY_W)
SHOE_X = 24.0

HAND_X = HAND_X or BODY_W / 2 - 9    # well outside the Recent/Back keys (checked)
S20, C20 = math.sin(math.radians(TILT)), math.cos(math.radians(TILT))
R2 = math.sqrt(2.0)

# The tablet frame: origin at the bottom front edge of the tablet, local x = world x,
# local y = s (into the tablet, towards its back), local z = t (up the tablet face).
TABLET = Matrix.Translation((0, 0, ZL)) @ Matrix.Rotation(math.radians(-TILT), 4, 'X')


def to_world(s, t):
    """(s, t) in the tablet frame -> (y, z) in the world, at any x."""
    return s * C20 + t * S20, ZL - s * S20 + t * C20


# Where the body's flat top sits: exactly LIP_H up the tablet face, just in front of it.
Z_TOP = ZL + CLR * S20 + LIP_H * C20
Y_LIP_FRONT = (-CLR * C20 + LIP_H * S20) - LIP_T
S_REST_BACK = TAB_T + CLR + REST_T
Y_REST_BACK = S_REST_BACK * C20 + ((Z_TOP - ZL + S_REST_BACK * S20) / C20) * S20 + 1.0
Z_FRONT_WALL = Z_TOP - (Y_LIP_FRONT - Y_FRONT)      # where the 45° front shoulder begins
Y_REAR = Y_REST_BACK + Z_TOP                        # where the 45° rear slope meets the desk
# Lowest point of the pocket floor, minus a wall: the cavity may not rise above it there.
Z_CEIL = ZL - (TAB_T + CLR) * S20 - SHELL - 0.3


# ================================================================= helpers
def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def material(name, rgb):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.diffuse_color = (*rgb, 1.0)
    return m


def mesh_object(name, verts, faces, mat=None, matrix=None):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    me.validate()
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    if mat:
        ob.data.materials.append(mat)
    if matrix is not None:
        ob.matrix_world = matrix
    return ob


def prism(name, outline, z0, z1, mat=None, matrix=None):
    """Extrude a closed 2D outline [(x, y)] from z0 to z1."""
    n = len(outline)
    verts = [(x, y, z0) for x, y in outline] + [(x, y, z1) for x, y in outline]
    faces = [list(range(n))[::-1], list(range(n, 2 * n))]
    faces += [[i, (i + 1) % n, n + (i + 1) % n, n + i] for i in range(n)]
    return mesh_object(name, verts, faces, mat, matrix)


def profile_x(name, poly_yz, x0, x1, mat=None):
    """Extrude a closed YZ outline across X from x0 to x1."""
    n = len(poly_yz)
    verts = [(x0, y, z) for y, z in poly_yz] + [(x1, y, z) for y, z in poly_yz]
    faces = [list(range(n))[::-1], list(range(n, 2 * n))]
    faces += [[i, (i + 1) % n, n + (i + 1) % n, n + i] for i in range(n)]
    return mesh_object(name, verts, faces, mat)


def box(name, x0, x1, y0, y1, z0, z1, mat=None, matrix=None):
    return prism(name, [(x0, y0), (x1, y0), (x1, y1), (x0, y1)], z0, z1, mat, matrix)


def squircle(a, b, cy, n=SQUIRCLE, cx=0.0, steps=128):
    pts = []
    for i in range(steps):
        th = 2 * math.pi * i / steps
        c, s = math.cos(th), math.sin(th)
        pts.append((cx + a * math.copysign(abs(c) ** (2 / n), c),
                    cy + b * math.copysign(abs(s) ** (2 / n), s)))
    return pts


def rounded_rect(x0, x1, y0, y1, r, steps=10):
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for i in range(steps + 1):
            a = math.radians(a0 + 90 * i / steps)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def clip(planes, big=500.0):
    """Convex YZ polygon from half-planes a*y + b*z <= c (Sutherland–Hodgman on a big square)."""
    poly = [(-big, -big), (big, -big), (big, big), (-big, big)]
    for a, b, c in planes:
        out = []
        for i, p in enumerate(poly):
            q = poly[(i + 1) % len(poly)]
            fp, fq = a * p[0] + b * p[1] - c, a * q[0] + b * q[1] - c
            if fp <= 0:
                out.append(p)
            if fp * fq < 0:
                k = fp / (fp - fq)
                out.append((p[0] + k * (q[0] - p[0]), p[1] + k * (q[1] - p[1])))
        poly = out
    return poly


def ellipsoid(name, center, radii, mat=None, matrix=None, segments=48, rings=24):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=segments, v_segments=rings, radius=1.0)
    bmesh.ops.scale(bm, vec=Vector(radii), verts=bm.verts)
    bmesh.ops.translate(bm, vec=Vector(center), verts=bm.verts)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    if mat:
        ob.data.materials.append(mat)
    if matrix is not None:
        ob.matrix_world = matrix
    return ob


def cylinder_y(name, x, z, r, y0, y1, mat=None, steps=64):
    """A disc/cylinder whose axis runs along Y (faces towards the viewer)."""
    pts = [(x + r * math.cos(2 * math.pi * i / steps), z + r * math.sin(2 * math.pi * i / steps))
           for i in range(steps)]
    poly = [(px, pz) for px, pz in pts]
    n = len(poly)
    verts = [(px, y0, pz) for px, pz in poly] + [(px, y1, pz) for px, pz in poly]
    faces = [list(range(n)), list(range(n, 2 * n))[::-1]]
    faces += [[i, (i + 1) % n, n + (i + 1) % n, n + i] for i in range(n)]
    return mesh_object(name, verts, faces, mat)


SOLVER = 'MANIFOLD' if 'MANIFOLD' in bpy.types.BooleanModifier.bl_rna.properties['solver'].enum_items.keys() else 'EXACT'


def boolean(target, other, op, solver=None, keep=False):
    """Apply target (op) other in place; `other` is deleted unless keep."""
    mod = target.modifiers.new('bool', 'BOOLEAN')
    mod.operation = op
    mod.object = other
    mod.solver = solver or SOLVER
    if hasattr(mod, 'material_mode'):
        try:
            mod.material_mode = 'TRANSFER'
        except TypeError:
            pass
    dg = bpy.context.evaluated_depsgraph_get()
    new = bpy.data.meshes.new_from_object(target.evaluated_get(dg))
    target.modifiers.remove(mod)
    old = target.data
    target.data = new
    bpy.data.meshes.remove(old)
    if not keep:
        bpy.data.objects.remove(other)
    return target


def swap_yz(ob):
    """Stand a flat outline up: its (x, y) become (x, z) and its thickness runs along y."""
    ob.data.transform(Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, 1, 0, 0), (0, 0, 0, 1))))
    bm = bmesh.new()            # a mirror turns the mesh inside out: turn it back
    bm.from_mesh(ob.data)
    bmesh.ops.reverse_faces(bm, faces=bm.faces)
    bm.to_mesh(ob.data)
    bm.free()


def bake_transform(ob):
    ob.data.transform(ob.matrix_world)
    ob.matrix_world = Matrix.Identity(4)


def mesh_stats(ob):
    bm = bmesh.new()
    bm.from_mesh(ob.data)
    bm.transform(ob.matrix_world)
    bad = sum(1 for e in bm.edges if not e.is_manifold)
    vol = bm.calc_volume(signed=False)
    xs = [v.co.x for v in bm.verts]
    ys = [v.co.y for v in bm.verts]
    zs = [v.co.z for v in bm.verts]
    bm.free()
    return bad, vol, (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs))


# ================================================================= the parts
def body_planes(inset=0.0, top=Z_TOP, bottom=0.0):
    """The body's side profile as half-planes; `inset` moves every face inwards."""
    return [
        (-1, 0, -Y_FRONT - inset),                                  # front wall
        (-1 / R2, 1 / R2, (Z_TOP - Y_LIP_FRONT) / R2 - inset),      # 45° front shoulder
        (0, 1, top),                                                # flat top / cavity ceiling
        (1 / R2, 1 / R2, (Z_TOP + Y_REST_BACK) / R2 - inset),       # 45° rear slope
        (0, -1, -bottom),                                           # desk
    ]


def plan(inset=0.0):
    a = BODY_W / 2 - inset
    b = (Y_REAR - Y_FRONT) / 2 - inset
    return squircle(a, b, (Y_REAR + Y_FRONT) / 2)


def tablet_half_plane(s0, keep_front):
    """Half-plane of the tablet frame s <= s0 (keep_front) or s >= s0, in world YZ."""
    # s = y*cos + (z - ZL)*(-sin)  ->  cos*y - sin*z <= s0 - sin*ZL
    a, b, c = C20, -S20, s0 - S20 * ZL
    return (a, b, c) if keep_front else (-a, -b, -c)


def build_body(blue):
    body = profile_x('body', clip(body_planes()), -BODY_W, BODY_W, blue)
    boolean(body, prism('plan', plan(), -1, 200), 'INTERSECT')
    return body


def build_cavity():
    """Hollow the body, open at the bottom. Three convex rooms, all roofs self-supporting:
    under the pocket (a short flat bridge), in front of it under the 45° shoulder, behind it."""
    lo = -5.0
    rooms = [
        clip(body_planes(SHELL, Z_CEIL, lo)),
        clip(body_planes(SHELL, Z_TOP - SHELL, lo) + [tablet_half_plane(-CLR - SHELL, True)]),
        clip(body_planes(SHELL, Z_TOP - SHELL, lo) + [tablet_half_plane(S_REST_BACK + SHELL, False)]),
    ]
    cav = profile_x('cavity', rooms[0], -BODY_W, BODY_W)
    for i, r in enumerate(rooms[1:]):
        boolean(cav, profile_x(f'room{i}', r, -BODY_W, BODY_W), 'UNION')
    boolean(cav, prism('plan_in', plan(SHELL), lo - 1, 200), 'INTERSECT')
    return cav


def build_back_rest(blue):
    top = rounded_rect(-REST_W / 2, REST_W / 2, -12.0, REST_H, 20.0)
    rest = prism('rest', [(x, t) for x, t in top], 0, 1, blue)
    # prism() extrudes along its local z; lay that axis along s (towards the tablet's back)
    swap_yz(rest)
    rest.data.transform(Matrix.Translation((0, TAB_T + CLR - 0.5, 0)) @
                        Matrix.Diagonal((1, REST_T + 0.5, 1, 1)))
    rest.matrix_world = TABLET
    bake_transform(rest)
    if REST_WINDOW:
        w0, w1 = 12.0, REST_H - 12
        win = prism('window', rounded_rect(-REST_W / 2 + 14, REST_W / 2 - 14, w0, w1,
                                           min(12.0, (w1 - w0) / 2 - 0.5)), 0, 1)
        swap_yz(win)
        win.data.transform(Matrix.Translation((0, TAB_T - 5, 0)) @ Matrix.Diagonal((1, REST_T + 10, 1, 1)))
        win.matrix_world = TABLET
        bake_transform(win)
        boolean(rest, win, 'DIFFERENCE')
    return rest


def build_overalls(blue, black):
    """Bib, straps and buttons on the front, the round emblem with a B."""
    parts = []
    grown = clip(body_planes(-BIB_PROUD))
    bib = profile_x('bib', grown, -BIB_W / 2, BIB_W / 2, blue)
    boolean(bib, prism('bib_plan', plan(-BIB_PROUD), -1, 200), 'INTERSECT')
    boolean(bib, box('bib_zone', -BIB_W, BIB_W, Y_FRONT - 10, Y_FRONT + 12, BIB_BOTTOM, Z_FRONT_WALL + 3.0), 'INTERSECT')
    parts.append(bib)

    strap_x = BIB_W / 2 - 7
    for side in (-1, 1):
        sx = side * strap_x
        strap = profile_x('strap', clip(body_planes(-1.0)), sx - 4.5, sx + 4.5, blue)
        boolean(strap, box('strap_zone', -BODY_W, BODY_W, Y_FRONT - 10, Y_LIP_FRONT - 0.2,
                           Z_FRONT_WALL - 2, Z_TOP - 0.8), 'INTERSECT')
        parts.append(strap)
        button = cylinder_y('button', sx, Z_FRONT_WALL - 3.0, 3.6,
                            Y_FRONT - BIB_PROUD - 1.6, Y_FRONT + 2, black)
        parts.append(button)

    zc = (BIB_BOTTOM + Z_FRONT_WALL) / 2
    emblem = cylinder_y('emblem', 0, zc, EMBLEM_R, Y_FRONT - BIB_PROUD - 1.0, Y_FRONT + 2, black)
    parts.append(emblem)

    bpy.ops.object.text_add()
    txt = bpy.context.object
    txt.data.body = 'B'
    txt.data.size = 9.5
    txt.data.extrude = 0.5
    txt.data.align_x = 'CENTER'
    txt.data.align_y = 'CENTER'
    txt.rotation_euler = (math.radians(90), 0, 0)
    txt.location = (0, Y_FRONT - BIB_PROUD - 1.2, zc)
    bpy.ops.object.convert(target='MESH')
    txt = bpy.context.object
    txt.data.materials.clear()
    txt.data.materials.append(blue)
    bake_transform(txt)
    boolean(emblem, txt, 'UNION', solver='EXACT')
    return parts


def build_hands(black):
    """Two cartoon gloves holding the tablet's bottom corners, in front of its face."""
    hands = []
    s_mid = -CLR - 3.2     # every part stays >= 0.2 mm in front of the pocket: slivers break booleans
    for side in (-1, 1):
        hx = side * HAND_X
        palm = ellipsoid('palm', (hx, s_mid, 3.0), (8.0, 3.0, 8.0), black, TABLET)
        bake_transform(palm)
        # three chubby fingers curled over the bezel, a thumb pointing outwards, a rolled cuff
        for dx, dt, rx, rt in ((-5.2, 12.0, 2.8, 6.0), (0.0, 13.5, 2.9, 6.4), (5.2, 12.0, 2.8, 6.0)):
            f = ellipsoid('finger', (hx + dx, s_mid, dt), (rx, 2.7, rt), black, TABLET)
            bake_transform(f)
            boolean(palm, f, 'UNION')
        thumb = ellipsoid('thumb', (hx + side * 8.0, s_mid, 7.0), (4.2, 2.6, 2.8), black, TABLET)
        bake_transform(thumb)
        boolean(palm, thumb, 'UNION')
        cuff = ellipsoid('cuff', (hx, s_mid - 0.3, -3.5), (8.6, 3.3, 2.6), black, TABLET)
        bake_transform(cuff)
        boolean(palm, cuff, 'UNION')
        hands.append(palm)
    return hands


def build_shoes(black):
    shoes = []
    for side in (-1, 1):
        sh = ellipsoid('shoe', (side * SHOE_X, Y_FRONT - 1.0, 0.0), (11.0, 14.0, 7.5), black)
        shoes.append(sh)
    return shoes


def build_heels(blue):
    """A thin fin sticking out at the back, like a tail: the tablet leans back, so that side needs reach."""
    heels = []
    y0, end_r = Y_REAR - 25, 2.5
    fin = [(y0, 0), (HEEL_Y - end_r, 0)]
    fin += [(HEEL_Y - end_r + end_r * math.sin(math.radians(a)), end_r - end_r * math.cos(math.radians(a)))
            for a in range(15, 180, 15)]
    fin += [(HEEL_Y - end_r, 2 * end_r), (Y_REAR, HEEL_H), (y0, HEEL_H + (Y_REAR - y0))]
    heels.append(profile_x('fin', fin, HEEL_X - HEEL_T / 2, HEEL_X + HEEL_T / 2, blue))
    return heels


def tablet_pocket():
    """What the stand must leave empty: the tablet plus clearance, everything above it,
    and the plug slot through the pocket floor into the hollow body."""
    pocket = box('pocket', -300, 300, -CLR, TAB_T + CLR, 0, 400, matrix=TABLET)
    bake_transform(pocket)
    slot = box('slot', USB_OFFSET_MM - SLOT_W / 2, USB_OFFSET_MM + SLOT_W / 2,
               -CLR, TAB_T + CLR, -40, 1, matrix=TABLET)
    bake_transform(slot)
    boolean(pocket, slot, 'UNION')
    return pocket


def build_stand():
    blue = material('overalls', (0.12, 0.33, 0.72))
    black = material('gloves', (0.05, 0.05, 0.06))

    stand = build_body(blue)
    for part in [build_back_rest(blue)] + build_heels(blue) + build_overalls(blue, black) \
            + build_hands(black) + build_shoes(black):
        boolean(stand, part, 'UNION')
    boolean(stand, build_cavity(), 'DIFFERENCE')
    boolean(stand, tablet_pocket(), 'DIFFERENCE')
    notch = prism('notch', rounded_rect(USB_OFFSET_MM - NOTCH_W / 2, USB_OFFSET_MM + NOTCH_W / 2,
                                        -NOTCH_H * 2, NOTCH_H, NOTCH_W / 2 - 0.5), 0, 1)
    swap_yz(notch)
    notch.data.transform(Matrix.Translation((0, 10, 0)) @ Matrix.Diagonal((1, 200, 1, 1)))
    boolean(stand, notch, 'DIFFERENCE')
    aw, ah = LEGS_ARCH
    arch = prism('legs', rounded_rect(-aw, aw, -ah, ah, aw - 0.5), 0, 1)
    swap_yz(arch)
    arch.data.transform(Matrix.Translation((0, Y_FRONT - 12, 0)) @ Matrix.Diagonal((1, 12 + SHELL + 2, 1, 1)))
    boolean(stand, arch, 'DIFFERENCE')
    boolean(stand, box('under', -500, 500, -500, 500, -50, 0), 'DIFFERENCE')   # flat on the bed
    stand.name = 'bello-stand'
    return stand


def build_tablet_dummy():
    """The tablet with a Minion face, for the preview and the interference check (not exported)."""
    bezel = material('bezel', (0.08, 0.08, 0.09))
    yellow = material('face', (1.0, 0.84, 0.0))
    grey = material('goggle', (0.55, 0.58, 0.62))
    white = material('eye', (0.97, 0.97, 0.97))
    brown = material('iris', (0.45, 0.28, 0.12))
    ink = material('pupil', (0.02, 0.02, 0.02))

    tab = box('tablet', -TAB_W / 2, TAB_W / 2, 0, TAB_T, 0, TAB_H, bezel, TABLET)
    extras = []
    screen = box('screen', -TAB_W / 2 + BEZEL_SIDE, TAB_W / 2 - BEZEL_SIDE, -0.6, 0.5,
                 BEZEL_TB, TAB_H - BEZEL_TB, yellow, TABLET)
    extras.append(screen)
    eye_t = TAB_H * 0.56
    extras.append(box('strap', -TAB_W / 2 + BEZEL_SIDE, TAB_W / 2 - BEZEL_SIDE, -1.2, -0.3,
                      eye_t - 7, eye_t + 7, ink, TABLET))
    for r, mat, d in ((40, grey, -1.2), (30, white, -1.8), (13, brown, -2.4), (6, ink, -3.0)):
        disc = cylinder_y('disc', 0, 0, r, d - 0.6, d + 0.3, mat)
        disc.data.transform(Matrix.Translation((0, 0, eye_t)))
        disc.matrix_world = TABLET
        extras.append(disc)
    cam = cylinder_y('camera', 0, 0, 1.6, -0.5, 0.3, grey)
    cam.data.transform(Matrix.Translation((0, 0, TAB_H - BEZEL_TB / 2)))
    cam.matrix_world = TABLET
    extras.append(cam)
    return tab, extras


# ================================================================= checks
def footprint_span_at_x(points, x):
    """(min y, max y) where the vertical line at x crosses the convex hull of the points."""
    pts = sorted(set(points))

    def half(seq):
        h = []
        for p in seq:
            while len(h) >= 2 and ((h[-1][0] - h[-2][0]) * (p[1] - h[-2][1])
                                   - (h[-1][1] - h[-2][1]) * (p[0] - h[-2][0])) <= 0:
                h.pop()
            h.append(p)
        return h

    hull = half(pts)[:-1] + half(pts[::-1])[:-1]
    ys = []
    for i, a in enumerate(hull):
        b = hull[(i + 1) % len(hull)]
        if (a[0] - x) * (b[0] - x) <= 0 and a[0] != b[0]:
            ys.append(a[1] + (x - a[0]) / (b[0] - a[0]) * (b[1] - a[1]))
    return min(ys), max(ys)


def check(stand):
    bad, vol, bbox = mesh_stats(stand)
    x0, x1, y0, y1, z0, z1 = bbox
    # Optional filament change: from here up the gloves come out black (with the lip rim and
    # the back rest, which the tablet hides).
    colour_z = min(v.co.z for v in stand.data.vertices
                   if abs(v.co.x) > HAND_X - 9 and v.co.z > Z_FRONT_WALL
                   and v.co.y < v.co.z - (Z_TOP - Y_LIP_FRONT) - 0.3)     # in front of the shoulder
    print(f'STAND black-glove filament change at z = {colour_z:.1f} mm (layer {round(colour_z / 0.2)})')
    print(f'STAND volume {vol / 1000:.1f} cm3, non-manifold edges {bad}')
    print(f'STAND size {x1 - x0:.1f} x {y1 - y0:.1f} x {z1 - z0:.1f} mm (x y z)')
    assert bad == 0, 'the stand mesh is not watertight'
    assert x1 - x0 <= 220 and y1 - y0 <= 220 and z1 - z0 <= 250, 'does not fit an Ender-3 bed'
    assert abs(z0) < 1e-3, 'the stand must sit flat at z=0'

    # The tablet sits on the pocket floor: faces that touch make a boolean guess, so test against
    # a tablet 0.05 mm smaller on every side — any real interference is still caught.
    e = 0.05
    solid = box('tablet_probe', -TAB_W / 2 + e, TAB_W / 2 - e, e, TAB_T - e, e, TAB_H - e, matrix=TABLET)
    probe = stand.copy()
    probe.data = stand.data.copy()
    bpy.context.scene.collection.objects.link(probe)
    boolean(probe, solid, 'INTERSECT', solver='EXACT')
    clash = mesh_stats(probe)[1] if probe.data.vertices else 0.0
    where = f' within {tuple(round(c, 1) for c in mesh_stats(probe)[2])}' if clash else ''
    bpy.data.objects.remove(probe)
    print(f'STAND clash with the tablet {clash:.3f} mm3{where}')
    assert clash < 1.0, 'the stand runs into the tablet'

    # Probe points: what must be plastic and what must stay open (x, then s/t in the tablet frame
    # or y/z in the world).
    tree = BVHTree.FromObject(stand, bpy.context.evaluated_depsgraph_get())

    def inside(p):
        hits, o = 0, Vector(p)
        while True:
            loc, _n, _i, _d = tree.ray_cast(o, Vector((0, 0, 1)))
            if loc is None:
                return hits % 2 == 1
            hits, o = hits + 1, loc + Vector((0, 0, 1e-4))

    def tab(x, s, t):
        return (x, *to_world(s, t))

    solid = {'pocket floor beside the slot': tab(USB_OFFSET_MM + SLOT_W / 2 + 8, TAB_T / 2, -0.6),
             'front lip': tab(20, -CLR - 1.0, LIP_H - 1.5),
             'back rest': tab(0, TAB_T + CLR + REST_T / 2, REST_H / 2)}
    empty = {'plug slot': tab(USB_OFFSET_MM, TAB_T / 2, -3),
             'cable room under the slot': (USB_OFFSET_MM, 8.0, 12.0),
             'cable notch': (USB_OFFSET_MM, Y_REAR - 3, 3.0),
             'pocket': tab(40, TAB_T / 2, 2)}
    for name, p in solid.items():
        assert inside(p), f'{name} should be plastic'
    for name, p in empty.items():
        assert not inside(p), f'{name} should be open'
    print(f'STAND probes ok: {", ".join(solid)} solid; {", ".join(empty)} open')

    # Things that must stay free, checked against the stand's bounding box and geometry.
    assert x1 < TAB_W / 2 - 20 and x0 > -TAB_W / 2 + 20, 'the speakers on the short edges'
    hand_inner = HAND_X - 8.2
    assert hand_inner > KEYS_X, 'the gloves would hide the Recent/Back keys'
    assert LIP_H + 2 < BEZEL_TB / 2, 'the lip would reach the Home button'
    rest_top_t = REST_H
    assert rest_top_t < TAB_H - 60, 'the back rest would cover the rear camera'

    # Tipping: the tablet's centre of mass must sit well inside the footprint (the convex hull
    # of everything touching the desk), measured straight back and forward from it.
    cy, cz = to_world(TAB_T / 2, TAB_H / 2)
    feet = [(v.co.x, v.co.y) for v in stand.data.vertices if v.co.z < 0.01]
    ys = footprint_span_at_x(feet, 0.0)
    margin_back, margin_front = ys[1] - cy, cy - ys[0]
    tap = 0.485 * 9.81 * margin_back / cz        # N, pushed level at the centre of the screen
    print(f'STAND tablet centre of mass y={cy:.1f} z={cz:.1f}; margin back {margin_back:.0f} mm, '
          f'front {margin_front:.0f} mm; tips back from a push of ~{tap:.1f} N at the screen centre')
    assert margin_back > 35 and margin_front > 20, 'the stand would tip over'
    return vol


# ================================================================= preview
def render(path, objects_visible, cam_loc, target, lens=50):
    scene = bpy.context.scene
    for ob in scene.objects:
        ob.hide_render = ob not in objects_visible and ob.type == 'MESH'
    cam = bpy.data.objects.get('cam')
    if cam is None:
        cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
        scene.collection.objects.link(cam)
        scene.camera = cam
    cam.data.lens = lens
    cam.data.clip_end = 5000
    cam.location = cam_loc
    direction = Vector(target) - Vector(cam_loc)
    cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.display.shading.light = 'STUDIO'
    scene.display.shading.color_type = 'MATERIAL'
    scene.display.shading.show_shadows = True
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = 'BOTH'
    scene.render.resolution_x = 960
    scene.render.resolution_y = 800
    scene.render.film_transparent = False
    scene.world = scene.world or bpy.data.worlds.new('w')
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


# ================================================================= main
def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    out = os.path.abspath(argv[0] if argv else os.path.dirname(os.path.abspath(__file__)))
    os.makedirs(out, exist_ok=True)

    clear_scene()
    stand = build_stand()
    tablet, extras = build_tablet_dummy()
    check(stand)

    for ob in bpy.context.scene.objects:
        ob.select_set(ob == stand)
    bpy.context.view_layer.objects.active = stand
    bpy.ops.wm.stl_export(filepath=os.path.join(out, 'bello-stand.stl'),
                          export_selected_objects=True, apply_modifiers=True, ascii_format=False)

    fit = stand.copy()
    fit.data = stand.data.copy()
    fit.name = 'bello-stand-fit-test'
    bpy.context.scene.collection.objects.link(fit)
    # A 44 mm slice of the pocket and the plug slot, cut flat below and laid on the bed.
    fit_z0 = ZL - 8
    boolean(fit, box('fit_zone', USB_OFFSET_MM - 20, USB_OFFSET_MM + 20, -500, Y_REST_BACK + 2,
                     fit_z0, Z_TOP + 8), 'INTERSECT')
    fit.data.transform(Matrix.Translation((0, 0, -fit_z0)))
    print(f'FIT volume {mesh_stats(fit)[1] / 1000:.1f} cm3, non-manifold edges {mesh_stats(fit)[0]}')
    for ob in bpy.context.scene.objects:
        ob.select_set(ob == fit)
    bpy.ops.wm.stl_export(filepath=os.path.join(out, 'bello-stand-fit-test.stl'),
                          export_selected_objects=True, apply_modifiers=True, ascii_format=False)
    fit.hide_render = True

    with_tablet = [stand, tablet] + extras
    render(os.path.join(out, 'bello-stand.png'), with_tablet, (-230, -420, 260), (0, 20, 95))
    render(os.path.join(out, 'bello-stand-back.png'), [stand], (170, 260, 170), (0, 25, 20))
    render(os.path.join(out, 'bello-stand-front.png'), [stand], (-90, -260, 90), (0, 10, 22))
    print('STAND done ->', out)


main()
