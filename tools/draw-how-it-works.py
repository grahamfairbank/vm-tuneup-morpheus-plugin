#!/usr/bin/env python3
"""Draws docs/images/how-it-works.svg, the "How it works" infographic in the README.

    python3 tools/draw-how-it-works.py

Edit the text below and run it again; the layout (wrapping, card heights) is computed.
Adapted from tools/draw-how-it-works.py in com-morpheus-plugin (same author, MIT).
"""
from html import escape
from pathlib import Path

W = 1400
FONT = "-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
INK, MUTED, LINE = "#1b1f24", "#57606a", "#d0d7de"
BLUE = ("#eaf2fc", "#2a6bb8")     # browser
TEAL = ("#ecf6f2", "#1c7a5c")     # Morpheus appliance / plugin
GREEN = ("#eef7e6", "#3f7a1d")    # HVM VMs
AMBER = ("#fff5e1", "#a35f00")    # guest agent check / gates
GRAY = ("#f6f8fa", "#57606a")

out: list[str] = []
MARKERS: set[str] = set()


def t(x, y, s, size=14, weight="normal", fill=INK, anchor="start", italic=False):
    st = ' font-style="italic"' if italic else ""
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
               f'text-anchor="{anchor}"{st}>{escape(s)}</text>')


def rect(x, y, w, h, fill, stroke, rx=10, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')


def wrap(s, width, size):
    per = max(8, int(width / (size * 0.53)))
    words, lines, cur = s.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > per:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    if cur:
        lines.append(cur)
    return lines


def para(x, y, w, s, size=13, fill=INK, gap=4, bullet=None, weight="normal"):
    """Writes wrapped text; returns the y below it."""
    indent = 14 if bullet else 0
    lines = wrap(s, w - indent, size)
    for i, ln in enumerate(lines):
        if i == 0 and bullet:
            t(x, y + size, bullet, size, "bold", fill)
        t(x + indent, y + size, ln, size, weight, fill)
        y += size + gap
    return y


def card(x, y, w, title, items, colors, size=13, title_size=15, bullet="•", draw=True):
    """A rounded card with a title and wrapped bullet items; returns its height."""
    global out
    saved = out
    out = []
    cy = y + 14
    t(x + 14, cy + title_size, title, title_size, "bold", colors[1])
    cy += title_size + 10
    for it in items:
        if isinstance(it, tuple):           # (text, style)
            text, style = it
            if style == "note":
                cy = para(x + 14, cy, w - 28, text, size - 1, MUTED) + 4
            elif style == "plain":
                cy = para(x + 14, cy, w - 28, text, size) + 4
            elif style == "code":
                cy = para(x + 14, cy, w - 28, text, size - 1, colors[1], weight="bold") + 4
            else:                           # custom bullet
                cy = para(x + 14, cy, w - 28, text, size, bullet=style) + 4
        else:
            cy = para(x + 14, cy, w - 28, it, size, bullet=bullet) + 4
    h = cy - y + 8
    body = out
    out = saved
    if draw:
        rect(x, y, w, h, "#ffffff", colors[1], rx=8, sw=1.2)
        out.extend(body)
    return h


def arrow(x1, y1, x2, y2, color=INK, label=None, lx=None, ly=None, both=False):
    mid = "a" + color.lstrip("#")
    MARKERS.add(color)
    m = f' marker-start="url(#{mid})"' if both else ""
    out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="2" marker-end="url(#{mid})"{m}/>')
    if label:
        for i, ln in enumerate(label.split("\n")):
            t(lx if lx is not None else (x1 + x2) / 2, (ly if ly is not None else (y1 + y2) / 2 - 8) + i * 15,
              ln, 12, "bold", color, "middle")


def zone(x, y, w, h, title, sub, colors):
    rect(x, y, w, h, colors[0], colors[1], rx=14, sw=2)
    t(x + 18, y + 30, title, 19, "bold", colors[1])
    t(x + 18, y + 50, sub, 13, "normal", MUTED)




def stack(x, y, w, cards, gap=12):
    for title, items, colors in cards:
        y += card(x, y, w, title, items, colors) + gap
    return y


# ---------------------------------------------------------------- architecture
TOP = 108
COL1_X, COL1_W = 24, 306
COL2_X, COL2_W = 400, 600
COL3_X, COL3_W = 1070, 306

col1 = [
    ("Cluster page › VM Tune-Up", [
        "Fleet view: grades now and after the plan, origin groups, VMs by tune-up benefit, findings per check",
        "Tune-up plan summary · Live migration readiness · Guest checks",
        "Rescan · Download CSV · Print report",
        ("Works on every edition, including Morpheus VM Essentials", "note"),
    ], BLUE),
    ("VM / instance › VM Tune-Up", [
        "Grade, tune-up plan and findings, with links to the VM in Morpheus",
        "Evidence · If you leave it · If you fix it · How in Morpheus · references",
        "Refresh · Check guest agent · Run guest checks · Fill in OS fields",
    ], BLUE),
    ("Plugin script (vm-tuneup.js)", [
        "Fills only the tab you open; shows what's running",
        "Builds the CSV in the browser; asks before any write",
        ("Talks only to the plugin's own routes on the same appliance.", "note"),
    ], BLUE),
]
col2_left = [
    ("Tab providers and script loader", [
        "Tab on HVM clusters, VMs and instances, for roles with View results",
        "A small shell or a result in memory: no scans during page loads",
    ], TEAL),
    ("Routes  /plugin/vmTuneUp/…", [
        ("vm · cluster · clusterData · clusters · csv · print · status", "code"),
        ("POST: clusterRescan · vmProbe · guestScan · guestScanCluster · hostCpu · setOs · setOsCluster", "code"),
        "Each checks the permission level and the tenant itself",
    ], TEAL),
    ("Fleet report (where licensed)", [
        "Operations › Reports: master tenant, view users only",
    ], TEAL),
]
col2_right = [
    ("ScanService", [
        "VM scan: 15 s budget; cluster scan in the background, paced, 15 min cap",
        "Scan results kept 10 min; guest and host CPU results 1 hour",
    ], TEAL),
    ("Checks and tune-up plan", [
        "16 Tier 1 checks from Morpheus data; 6 Tier 2 checks after a guest scan",
        "Plan per VM: one outage of the VM, a guest restart, or no downtime; grade after the plan",
        "Host CPU result per cluster (not part of VM grades)",
        "Unknown is listed, never a pass",
    ], TEAL),
]
col3 = [
    ("By default: nothing runs here", [
        "The checks read what Morpheus already knows",
        "No credentials anywhere: only channels Morpheus already has",
    ], GREEN),
    ("Optional: Check guest agent", [
        ("cmd /c exit 0  ·  true", "code"),
        "One no-op command; only success and a short error are kept",
    ], AMBER),
    ("Optional: Guest checks", [
        "One fixed, read-only script per OS",
        "Windows: PowerShell through the QEMU guest agent",
        "Linux: sh through the Morpheus agent",
    ], AMBER),
    ("Optional: Host CPU check", [
        ("virsh capabilities  ·  virsh domcapabilities --virttype kvm", "code"),
        "On each HVM host of the cluster, through Morpheus",
    ], AMBER),
]


def measure(cards, w):
    return sum(card(0, 0, w, a, b, c, draw=False) + 12 for a, b, c in cards)


def tops(x0, y0, cards, w, gap=12):
    """The y of each card's top, and its height."""
    res, y = [], y0
    for a, b, c in cards:
        h = card(0, 0, w, a, b, c, draw=False)
        res.append((y, h))
        y += h + gap
    return res


inner = 2 * 14
half = (COL2_W - 3 * 14) // 2
h1 = measure(col1, COL1_W - inner)
h2 = max(measure(col2_left, half), measure(col2_right, half))
h3 = measure(col3, COL3_W - inner)
DATA_H = 178
ZONE_H = 66 + max(h1 + 72, h2 + DATA_H + 18, h3 + 54)

zone(COL1_X, TOP, COL1_W, ZONE_H, "① Your browser", "Morpheus web UI: nothing to install", BLUE)
zone(COL2_X, TOP, COL2_W, ZONE_H, "② Morpheus appliance", "vm-tuneup-morpheus-plugin jar, running inside Morpheus", TEAL)
zone(COL3_X, TOP, COL3_W, ZONE_H, "③ Your HVM hosts and VMs", "Reached only through Morpheus", GREEN)

stack(COL1_X + 14, TOP + 66, COL1_W - inner, col1)
stack(COL2_X + 14, TOP + 66, half, col2_left)
stack(COL2_X + 28 + half, TOP + 66, half, col2_right)
stack(COL3_X + 14, TOP + 66, COL3_W - inner, col3)
c1 = tops(0, TOP + 66, col1, COL1_W - inner)
c3 = tops(0, TOP + 66, col3, COL3_W - inner)

dy = TOP + ZONE_H - DATA_H - 14
rect(COL2_X + 14, dy, COL2_W - 28, DATA_H, "#ffffff", GRAY[1], rx=8, sw=1.2, dash="5 4")
t(COL2_X + 28, dy + 24, "Morpheus data the plugin reads", 15, "bold", GRAY[1])
yy = para(COL2_X + 28, dy + 34, COL2_W - 56,
          "Each VM's disks, controllers and datastores · network interfaces and MAC addresses · machine type, UEFI, "
          "Secure Boot, TPM · vCPUs and cores per socket · nested virtualization · placement · guest agent and Morpheus "
          "agent state · snapshots · CD-ROM drives · server type and OS · the cluster CPU model · your tenant and permissions", 13)
para(COL2_X + 28, yy + 6, COL2_W - 56,
     "Its one write (opt-in): copy the OS the guest reported into the EMPTY OS fields of the VM's Morpheus record, after you confirm, with Update VM records.", 13,
     AMBER[1], weight="bold")

para(COL1_X + 14, TOP + ZONE_H - 62, COL1_W - 28,
     "A plain link or another site never starts a scan or a command: only the plugin's own script can.", 12, MUTED)
para(COL3_X + 14, TOP + ZONE_H - 46, COL3_W - 28,
     "Nothing leaves the appliance: no telemetry, no external calls.", 12, MUTED)


def mid(c):
    return c[0] + c[1] / 2


ay = mid(c1[0])
arrow(COL1_X + COL1_W + 4, ay, COL2_X - 4, ay, BLUE[1], "tab\nHTML", ly=ay - 26, both=True)
ay2 = mid(c1[2])
arrow(COL1_X + COL1_W + 4, ay2, COL2_X - 4, ay2, BLUE[1], "route\nrequests", ly=ay2 - 26)
for i, label in ((1, "no-op"), (2, "guest\nscript"), (3, "virsh")):
    y = mid(c3[i])
    arrow(COL2_X + COL2_W + 4, y, COL3_X - 4, y, AMBER[1], label, ly=y - (26 if "\n" in label else 10))

# ---------------------------------------------------------------- the flows
FY = TOP + ZONE_H + 44
t(W / 2, FY, "What happens, step by step", 22, "bold", INK, "middle")
FY += 22
GAP = 16
PW = (W - 2 * 24 - 2 * GAP) / 3
flows = [
    ("A.  Opening a VM's tab", BLUE, [
        ("Morpheus asks the tab whether to show itself: an HVM VM, and a role with View results.", "1"),
        ("The tab renders a small shell at once. The page never waits.", "2"),
        ("The script asks the vm route. The route checks the level and that your tenant owns the VM.", "3"),
        ("The VM is scanned with a 15 s budget (or served from memory). Guest check results from the last hour are added.", "4"),
        ("The tab shows the grade, the tune-up plan (grade after it) and the findings.", "5"),
    ]),
    ("B.  Scanning a cluster", TEAL, [
        ("Opening the cluster tab starts a background scan, or joins the one already running.", "1"),
        ("One worker scans each HVM VM in turn (about 2 to 4 s each), pausing 250 ms after each. Stay on the page; don't refresh.", "2"),
        ("The tab shows progress and refreshes every 5 s until the scan is done.", "3"),
        ("Fleet view and plan summary. Non-master users see only their tenant's VMs.", "4"),
        ("At most 2 scans run and 8 wait; 3 running per user.", "note"),
    ]),
    ("C.  CSV and printable report", BLUE, [
        ("Download CSV waits for the scan with progress, then the browser builds the file (formulas neutralized).", "1"),
        ("The CSV adds gradeAfter, scoreAfter, planWindow and planSteps.", "2"),
        ("Print report opens a page for your browser's Save as PDF: summary, VMs by tune-up benefit, plan per VM, live migration readiness.", "3"),
        ("Both use results already scanned; neither runs a guest or host command.", "note"),
    ]),
    ("D.  Guest checks (and Check guest agent)", AMBER, [
        ("Every gate must pass, in this order:", "plain"),
        ("POST with X-Requested-With, same origin, Morpheus CSRF token", "✓"),
        ("Role has Run guest and host checks (Full)", "✓"),
        ("Your tenant owns the VM; it's a powered-on HVM VM", "✓"),
        ("One run per VM at a time; not run in the last minute", "✓"),
        ("Morpheus runs the fixed script (2 min limit); the run is logged with your user id and the script's hash, never the output.", "→"),
        ("Results kept 1 hour, added to grades and plans. Cluster tab: all powered-on VMs, one at a time.", "→"),
    ]),
    ("E.  Host CPU check", AMBER, [
        ("Gates: POST checks as in D; Run guest and host checks (Full); master tenant only.", "✓"),
        ("On each HVM host, through Morpheus: virsh capabilities and virsh domcapabilities --virttype kvm (as root, or sudo -n).", "1"),
        ("Each host's CPU, microcode and usable named CPU models are compared with the cluster's CPU model setting.", "2"),
        ("Live migration readiness names the best model every host can run, and what to change in Morpheus.", "3"),
        ("Cached 1 hour; one run per cluster at a time.", "note"),
    ]),
    ("F.  Fill in OS fields: the one write", AMBER, [
        ("Shown when the VM's Morpheus record has empty OS fields and the guest checks read the OS inside the guest.", "1"),
        ("Needs Update VM records (Full), plus the POST checks and the tenant check.", "✓"),
        ("You confirm in a dialog first.", "✓"),
        ("Copies that OS into only the EMPTY fields (OS type, family, platform, version); never changes a value Morpheus has.", "2"),
        ("Each write is logged with your user id, the VM and the OS.", "3"),
    ]),
]


def flow_items(items):
    return [(s, b if b in ("plain", "note") else (f"{b}." if b.isdigit() else b)) for s, b in items]


for row in range(2):
    row_flows = flows[row * 3:row * 3 + 3]
    fh = max(card(0, 0, PW, title, flow_items(items), col, size=13, title_size=15, draw=False)
             for title, col, items in row_flows)
    for i, (title, col, items) in enumerate(row_flows):
        x = 24 + i * (PW + GAP)
        rect(x, FY, PW, fh, col[0], col[1], rx=12, sw=2)
        saved = len(out)
        card(x, FY, PW, title, flow_items(items), col, size=13, title_size=15)
        del out[saved]              # drop the white rect, keep the text
    FY += fh + GAP

# ---------------------------------------------------------------- one and done + footer
IY = FY + 8
summary = [
    ("Install, scan, plan, uninstall. The plugin has no settings and no schedules: scans run only when someone opens a tab "
     "or runs the report, and guest and host commands only for people with the permission (the guest and host checks only on a click). It never "
     "changes a VM's hardware, a host or a cluster; its one write fills in empty OS fields, opt-in and confirmed. Every finding says what happens if you leave it and what "
     "fixing it costs (downtime, risk, whether it can be undone, what to do first), and is either a Recommendation (doable "
     "in the Morpheus UI or inside the guest OS) or a Consideration (needs host-level work; never recommended).", "plain"),
]
ih = card(24, IY, W - 48, "One and done, read-only except one opt-in write", summary, TEAL, size=14, draw=False)
rect(24, IY, W - 48, ih, TEAL[0], TEAL[1], rx=12, sw=2)
saved = len(out)
card(24, IY, W - 48, "One and done, read-only except one opt-in write", summary, TEAL, size=14)
del out[saved]

FOOT = IY + ih + 30
t(24, FOOT, "Unofficial community project: not an HPE or Morpheus Data product, and not endorsed or supported by HPE. "
  "HPE and Morpheus are trademarks of Hewlett Packard Enterprise. See NOTICE.md.",
  12, "normal", MUTED)
H = FOOT + 22

markers = "\n".join(f'<marker id="a{c.lstrip("#")}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>' for c in sorted(MARKERS))
head = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<title>How vm-tuneup-morpheus-plugin works</title>
<desc>Architecture and request flows of vm-tuneup-morpheus-plugin: the browser, the plugin inside the Morpheus appliance, and the HVM VMs it audits.</desc>
<defs>
{markers}
</defs>
<rect width="{W}" height="{H}" fill="#ffffff"/>
<text x="24" y="48" font-size="30" font-weight="bold" fill="{INK}">How vm-tuneup-morpheus-plugin works</text>
<text x="24" y="76" font-size="15" fill="{MUTED}">We've migrated to HVM. Now what? An audit of HVM VMs inside Morpheus: what the migration left behind, what to tune next, and a plan to do it.</text>
'''
out_path = Path(__file__).resolve().parent.parent / "docs/images/how-it-works.svg"
out_path.write_text(head + "\n".join(out) + "\n</svg>\n")
print(out_path, W, H)
