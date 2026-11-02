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
    ("Cluster page › Migration Hygiene", [
        "The fleet view: grades, findings by severity, origin groups, worst VMs, findings per check",
        "Rescan · Download CSV (this cluster / all clusters)",
        ("Works on every license, including VM Essentials", "note"),
    ], BLUE),
    ("VM / instance › Migration Hygiene", [
        "Grade and findings, worst first",
        "Evidence · If you leave it · If you fix it · How in VME · references",
        "Refresh · Check guest agent (optional)",
    ], BLUE),
    ("Plugin script (migration-hygiene.js)", [
        "Fills only the tab you open; pauses while it's hidden",
        "Builds the CSV in the browser, with progress",
        ("Talks only to the plugin's own routes on the same appliance.", "note"),
    ], BLUE),
]
col2_left = [
    ("Tab providers", [
        "Show the tab on HVM clusters, VMs and instances, for roles with View results",
        "Render a small shell, or a result already in memory: no scans during page loads",
    ], TEAL),
    ("Script loader", [
        "Loads migration-hygiene.js with Morpheus's CSP nonce, for view users only",
    ], TEAL),
    ("Routes  /plugin/migrationHygiene/…", [
        ("vm · cluster · clusterData · clusters · csv · status", "code"),
        ("clusterRescan · vmProbe  (POST)", "code"),
        "Each checks the permission level and the tenant itself",
    ], TEAL),
]
col2_right = [
    ("ScanService", [
        "VM scan: 15 s budget",
        "Cluster scan in the background: one worker, 250 ms pause per VM, 15 min cap",
        "Results kept in memory for 10 minutes",
    ], TEAL),
    ("Tier 1 checks", [
        "14 checks from Morpheus data only",
        "Score from 100, grade A to F, origin group",
        "Unknown is listed, never a pass",
    ], TEAL),
    ("Fleet report (where licensed)", [
        "Operations › Reports: master tenant, view users only",
    ], TEAL),
]
col3 = [
    ("Nothing runs here by default", [
        "The checks read what Morpheus already knows about each VM",
        "No agent to install, no SSH, no WinRM, no credentials",
    ], GREEN),
    ("Optional: Check guest agent", [
        ("Windows: cmd /c exit 0", "code"),
        ("Linux: true", "code"),
        "One fixed no-op command through Morpheus's guest execution",
        "Only success, exit code and a short error are kept",
        ("On Linux, Morpheus runs it through its own agent, so it tests that agent.", "note"),
    ], AMBER),
]


def measure(cards, w):
    return sum(card(0, 0, w, a, b, c, draw=False) + 12 for a, b, c in cards)


inner = 2 * 14
h1 = measure(col1, COL1_W - inner)
h2 = max(measure(col2_left, (COL2_W - 3 * 14) // 2), measure(col2_right, (COL2_W - 3 * 14) // 2))
h3 = measure(col3, COL3_W - inner)
DATA_H = 112
ZONE_H = 66 + max(h1 + 54, h2 + DATA_H + 18, h3 + 54)

zone(COL1_X, TOP, COL1_W, ZONE_H, "① Your browser", "Morpheus web UI: nothing to install", BLUE)
zone(COL2_X, TOP, COL2_W, ZONE_H, "② Morpheus appliance", "migration-hygiene-plugin jar, running inside Morpheus", TEAL)
zone(COL3_X, TOP, COL3_W, ZONE_H, "③ Your HVM VMs", "On the HVM hosts of each cluster", GREEN)

stack(COL1_X + 14, TOP + 66, COL1_W - inner, col1)
half = (COL2_W - 3 * 14) // 2
stack(COL2_X + 14, TOP + 66, half, col2_left)
stack(COL2_X + 28 + half, TOP + 66, half, col2_right)
stack(COL3_X + 14, TOP + 66, COL3_W - inner, col3)

dy = TOP + ZONE_H - DATA_H - 14
rect(COL2_X + 14, dy, COL2_W - 28, DATA_H, "#ffffff", GRAY[1], rx=8, sw=1.2, dash="5 4")
t(COL2_X + 28, dy + 24, "Morpheus data the plugin reads (it never writes)", 15, "bold", GRAY[1])
para(COL2_X + 28, dy + 34, COL2_W - 56,
     "Each VM's disks and controllers · network interfaces and MAC addresses · machine type, UEFI, Secure Boot, TPM · "
     "guest agent and Morpheus agent state · snapshots · CD-ROM drives · server type and OS · "
     "the user's tenant and permissions", 13)

para(COL1_X + 14, TOP + ZONE_H - 46, COL1_W - 28,
     "A plain link or another site never starts a scan: only the plugin's own script can.", 12, MUTED)
para(COL3_X + 14, TOP + ZONE_H - 46, COL3_W - 28,
     "Nothing leaves the appliance: no telemetry, no external calls.", 12, MUTED)

ay = TOP + 140
arrow(COL1_X + COL1_W + 4, ay, COL2_X - 4, ay, BLUE[1], "tab\nHTML", ly=ay - 26, both=True)
arrow(COL1_X + COL1_W + 4, ay + 290, COL2_X - 4, ay + 290, BLUE[1], "route\nrequests", ly=ay + 264)
arrow(COL2_X + COL2_W + 4, ay + 230, COL3_X - 4, ay + 230, AMBER[1], "no-op\n(optional)", ly=ay + 204)

# ---------------------------------------------------------------- the four flows
FY = TOP + ZONE_H + 44
t(W / 2, FY, "What happens, step by step", 22, "bold", INK, "middle")
FY += 22
GAP = 16
PW = (W - 2 * 24 - 3 * GAP) / 4
flows = [
    ("A.  Opening a VM's tab", BLUE, [
        ("Morpheus asks the tab whether to show itself: an HVM VM, and a role with View results.", "1"),
        ("The tab renders a small shell at once. The page never waits.", "2"),
        ("The script asks the vm route. The route checks the level and that your tenant owns the VM.", "3"),
        ("The VM is scanned with a 15 s budget (or served from memory), and the findings are rendered.", "4"),
        ("Parts not done in time show as \"unknown: time budget\".", "note"),
    ]),
    ("B.  Scanning a cluster", TEAL, [
        ("Opening the cluster tab starts a background scan, or joins the one already running.", "1"),
        ("One worker scans each HVM VM in turn, pausing 250 ms after each.", "2"),
        ("The tab shows progress and refreshes every 5 s until the scan is done.", "3"),
        ("Fleet view: grades, origin groups, worst VMs. Non-master users see only their tenant's VMs.", "4"),
        ("At most 2 scans run and 8 wait; 3 running per user.", "note"),
    ]),
    ("C.  Downloading the CSV", BLUE, [
        ("The button asks clusterData for the scan status and rows, every 3 s.", "1"),
        ("It shows progress until the scan is done (15 min cap per cluster).", "2"),
        ("The browser builds the file: UTF-8 with a byte order mark, formulas neutralized.", "3"),
        ("All clusters: one cluster at a time, one file.", "4"),
        ("The direct download links use results in memory only and never start a scan.", "note"),
    ]),
    ("D.  Check guest agent", AMBER, [
        ("Every gate must pass, in this order:", "plain"),
        ("POST with X-Requested-With, same origin, Morpheus CSRF token", "✓"),
        ("Role has Run guest scans (Full)", "✓"),
        ("Your tenant owns the VM; it's a powered-on HVM VM", "✓"),
        ("Morpheus doesn't report the agent status", "✓"),
        ("Not checked in the last minute; none in flight", "✓"),
        ("The fixed no-op runs, 20 s timeout; the run is logged with your user id.", "→"),
        ("Answered or No answer, and when, is shown on the tab.", "→"),
    ]),
]


def flow_items(items):
    return [(s, b if b in ("plain", "note") else (f"{b}." if b.isdigit() else b)) for s, b in items]


fh = max(card(0, 0, PW, title, flow_items(items), col, size=13, title_size=15, draw=False)
         for title, col, items in flows)
for i, (title, col, items) in enumerate(flows):
    x = 24 + i * (PW + GAP)
    rect(x, FY, PW, fh, col[0], col[1], rx=12, sw=2)
    saved = len(out)
    card(x, FY, PW, title, flow_items(items), col, size=13, title_size=15)
    del out[saved]              # drop the white rect, keep the text

# ---------------------------------------------------------------- one and done + footer
IY = FY + fh + 24
summary = [
    ("Install, scan, plan, uninstall. The plugin has no settings and no schedules: scans run only when someone opens a tab "
     "or runs the report. It never changes a VM, host or cluster. Every finding says what happens if you leave it and what "
     "fixing it costs (downtime, risk, whether it can be undone, what to do first), and is either a Recommendation (doable "
     "in the VME UI or inside the guest OS) or a Consideration (needs host-level work; never recommended).", "plain"),
]
ih = card(24, IY, W - 48, "One and done, read-only", summary, TEAL, size=14, draw=False)
rect(24, IY, W - 48, ih, TEAL[0], TEAL[1], rx=12, sw=2)
saved = len(out)
card(24, IY, W - 48, "One and done, read-only", summary, TEAL, size=14)
del out[saved]

FOOT = IY + ih + 30
t(24, FOOT, "Unofficial community project: not an HPE or Morpheus Data product, and not endorsed or supported by HPE. "
  "HPE, Morpheus and VM Essentials are trademarks of Hewlett Packard Enterprise. See NOTICE.md.",
  12, "normal", MUTED)
H = FOOT + 22

markers = "\n".join(f'<marker id="a{c.lstrip("#")}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>' for c in sorted(MARKERS))
head = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<title>How migration-hygiene-plugin works</title>
<desc>Architecture and request flows of migration-hygiene-plugin: the browser, the plugin inside the Morpheus appliance, and the HVM VMs it audits.</desc>
<defs>
{markers}
</defs>
<rect width="{W}" height="{H}" fill="#ffffff"/>
<text x="24" y="48" font-size="30" font-weight="bold" fill="{INK}">How migration-hygiene-plugin works</text>
<text x="24" y="76" font-size="15" fill="{MUTED}">We've migrated to HVM. Now what? A read-only audit of HVM VMs, inside Morpheus: what the migration left behind, and what to tune next.</text>
'''
out_path = Path(__file__).resolve().parent.parent / "docs/images/how-it-works.svg"
out_path.write_text(head + "\n".join(out) + "\n</svg>\n")
print(out_path, W, H)
