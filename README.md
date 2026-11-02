# migration-hygiene-plugin

**We've migrated to HVM. Now what?** Whatever tool you used to migrate, and whenever you did it, see what it left behind on your HPE Morpheus virtual machines on HVM (HPE Virtual Machine) hosts, and what to tune next: disks on emulated SATA or IDE, e1000 network adapters, the Legacy chipset, a QEMU guest agent that doesn't answer, VMware MAC addresses, old snapshots, attached ISOs and more. Every VM gets a grade, and every finding says what happens **if you leave it** and what it costs **if you fix it**.

[![Latest release](https://img.shields.io/github/v/release/grahamfairbank/migration-hygiene-plugin?label=release&include_prereleases)](../../releases)
[![Morpheus 9.0 | 9.1](https://img.shields.io/badge/Morpheus-9.0%20%7C%209.1-0d7b6a)](#requirements)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)](CONTRIBUTING.md)

> **Unofficial community project.** Not an HPE or Morpheus Data product. Not developed, endorsed or supported by Hewlett Packard Enterprise. Provided as is, without warranty. HPE and Morpheus are trademarks of Hewlett Packard Enterprise; VMware is a trademark of Broadcom. See [NOTICE.md](NOTICE.md) for trademarks and for every source this project was built from.

![The Migration Hygiene tab on cluster-a: grade counts, findings by severity, a summary per origin group and the worst VMs first](docs/images/tab-cluster.png)

<sub>The cluster tab is the fleet view: every HVM VM in the cluster, graded, grouped by origin and sorted worst first. Names and addresses in every screenshot are example values (`cluster-a`, `hvm-host-01`, `app-legacy-01`), the same ones the [install guide](docs/INSTALL.md) uses.</sub>

## Why you'll want it

- **Migration is the start, not the finish.** A VM that boots on HVM can still run on emulated disks and network adapters, the Legacy chipset and no guest agent, and carry snapshots and ISOs from the move. Migration Hygiene finds all of it in minutes, across the whole cluster.
- **Works whatever tool you used.** Morpheus's own migration tool, an image upload, a third-party converter or a VM that was simply discovered on the hosts: the checks look at the VM as it is today, not at how it got there. Each VM is also put in an origin group (**Migrated from VMware**, **No VMware signal**, **Unknown origin**), with honest wording about what Morpheus data can and can't show.
- **Every finding is a plan, not a scare.** "If you leave it" says what the problem costs you. "If you fix it" gives the downtime, the risk, whether it can be undone, and what must happen first, in order (for example: the VirtIO storage driver must be installed and boot-start on Windows before a disk bus switch). HPE documentation is cited first.
- **Recommendations you can actually do.** A **Recommendation** is something you can do in the Morpheus UI or inside the guest OS. Anything that would need host-level work is a **Consideration**: worth knowing, never recommended.
- **Read-only, and nothing leaves the appliance.** The checks read Morpheus data. The plugin changes nothing, makes no external calls and sends no telemetry. The one optional exception is a harmless no-op command that tests the guest agent, for users you give a separate permission.
- **Works on every license.** The cluster tab and the CSV export are the fleet view, so it works on the Morpheus VM Essentials license, which has no Reports page. Where your license includes Reports, there's a fleet report too.
- **One and done.** Install it, scan, plan your fixes, uninstall it. No schedules, no agents, nothing to configure.
- **Doesn't slow Morpheus down.** Tabs never block a page load. Scans run in the background, only when someone opens a tab, and pace themselves.

## Get started

1. Download `migration-hygiene-plugin-<version>.jar` and `SHA256SUMS` from the [releases](../../releases), and check the jar.
2. In Morpheus, upload the jar: **Administration → Integrations → Plugins → Add**.
3. Give the people who'll use it the **Migration Hygiene: View results** permission (a ready-made role script is included).
4. Open an HVM cluster: **Infrastructure → Clusters → (cluster) → Migration Hygiene**. Wait for the scan, then open any VM's tab or download the CSV.

Step-by-step instructions, written for someone doing this for the first time: **[docs/INSTALL.md](docs/INSTALL.md)**. How the pieces fit together: **[How it works](#how-it-works)**.

## Tour

### The cluster tab: your fleet view

Open an HVM cluster and choose the **Migration Hygiene** tab (Morpheus may put it under the tab row's overflow menu). The first time, the tab scans the cluster in the background and shows its progress ("Scanned 4 of 9 HVM VMs"). Then it shows:

- **Grade counts** (A to F) and **findings by severity** (critical, warning, info) for the whole cluster;
- **By origin:** the same counts per origin group, so you can see at once how the migrated VMs compare with the rest;
- **Worst VMs**, worst first, each with its grade, score, origin and top findings, and a link to the VM. Filter by origin, see the 15 worst or **Show all**;
- **Findings per check:** how many VMs fail each check, and how many couldn't be decided;
- buttons: **Rescan**, **Download CSV (this cluster)** and **Download CSV (all clusters)**.

![The By origin table on cluster-a, with the origin filter set to Migrated](docs/images/tab-cluster-origin.png)

### The VM tab: what to fix, and how

Every HVM VM (and every instance on one) gets a **Migration Hygiene** tab with its grade and its findings, worst first. Discovered VMs, which have no instance, get it on the VM page. Each finding shows:

- the **evidence** (what the plugin read, for example each disk's bus and controller);
- **If you leave it:** what the problem costs you;
- **If you fix it:** the downtime, the risk, whether it can be undone, and **Do first, in this order:** the prerequisites;
- **How in Morpheus:** where to do it (for example *Actions → Reconfigure → Advanced Options*);
- whether it's a **Recommendation** or a **Consideration**, anything **not yet verified**, and references.

![The Migration Hygiene tab of app-legacy-01: grade F, a boot disk on IDE, an e1000 network adapter, a VMware MAC address and a discovered VM](docs/images/tab-vm.png)

Checks the plugin couldn't decide for this VM (for example on Morpheus 9.0.x, which doesn't report the chipset) are listed separately as **Unknown or not evaluated**. They cost no points and never count as a pass.

### Check guest agent

When Morpheus doesn't report a VM's QEMU guest agent status, users with the **Run guest scans** permission get a **Check guest agent** button. It runs one harmless no-op command in the VM and shows the result and when it ran. Everyone else sees the explanation greyed out, with "You don't have permission". See [The guest agent check](#the-guest-agent-check).

![The Check guest agent area on db-win-01: the button, its one-line explanation and "Answered 2 min ago"](docs/images/tab-vm-guest-agent.png)

### CSV export

**Download CSV (this cluster)** and **Download CSV (all clusters)** save one row per VM, worst first, ready for a spreadsheet and a change plan. See [CSV columns](#csv-columns).


## The checks (0.4.0)

The checks below use Morpheus data only. Nothing runs inside the VM, except the optional [guest agent check](#the-guest-agent-check). They're listed in fix-plan order: what to do first comes first.

The UI shows each check by its title, with a short ID in small type (for example `DISK-BUS`). In the plugin's code and logs, the IDs carry a tier prefix: `T1-` for checks that use Morpheus data only, `T2-` for the planned checks inside the guest.

| Check | Severity | Type | If you fix it: downtime, risk, can be undone | Unknown when |
|---|---|---|---|---|
| VMware source VM still running (`SOURCE-TWIN`) | critical | Recommendation | on the VMware side; low; yes | not evaluated yet (pending verification) |
| VM not managed by Morpheus (`DISCOVERED`) | info | Recommendation | none; low; yes | |
| Old or many snapshots (`SNAPSHOTS`) | info; warning when older than 90 days | Recommendation | none; medium; **no** | snapshots can't be read |
| ISO still attached (`ISO-ATTACHED`) | info | Recommendation | none; low; yes | |
| QEMU guest agent not connected (`GA-STATUS`) | warning | Recommendation | none; low; yes | VM powered off or power state unknown; status not reported and not checked |
| QEMU guest agent channel disabled (`GA-CHANNEL`) | warning | Consideration | cold reconfigure; low; yes | Morpheus 9.0.x; discovered VMs |
| Morpheus agent missing or out of date (`MORPHEUS-AGENT`) | info; warning when silent for over 7 days | Recommendation | none; low; yes | until the fleet's newest agent version is known |
| OS type fields incomplete after migration (`OS-TYPE-MISMATCH`) | info; warning for Windows | Consideration | none; low; yes | |
| Disk on an emulated SATA or IDE bus (`DISK-BUS`) | warning; critical for a boot disk on IDE | Recommendation | cold reconfigure; medium; yes | Morpheus lists no disks |
| Network adapter is not VirtIO (`NIC-MODEL`) | warning | Recommendation | cold reconfigure; medium; yes | |
| Legacy (i440fx) chipset (`CHIPSET`) | warning | Recommendation | cold reconfigure; high; yes | Morpheus 9.0.x; discovered VMs |
| Firmware or TPM doesn't fit the OS (`FIRMWARE`) | info | Recommendation | cold reconfigure; high; **no** | the guest OS is unknown |
| VMware MAC address kept (`VMWARE-MAC`) | info | Consideration | none; low; yes | |
| Disk possibly fully allocated (thick) (`DISK-ALLOCATION`) | info | Recommendation | none; low; yes | no used size reported |

"Cold reconfigure" means Morpheus powers the VM off, applies the change and starts it again. The snapshot and allocation thresholds are the plugin's own heuristics, not HPE guidance, and each finding says so.

**A few things worth knowing:**

- **Disk bus and chipset fixes list their prerequisites in order.** On Windows, the VirtIO storage driver must be installed **and** a boot-start driver before the switch, or Windows fails to boot. On Linux, VirtIO must be in the initramfs (or built into the kernel), and `/etc/fstab` and the boot loader must refer to disks by UUID or label. The VM tab spells out each step.
- **Network adapter changes** give the guest a new adapter: record its IP settings first, and keep the Morpheus console at hand.
- **Network adapters Morpheus doesn't name** (it reports E1000E and some other models with no type) count as "not VirtIO, model unknown".
- **The VMware MAC check is a Consideration.** Keeping the MAC is usually harmless; the real risk is the VMware source VM being powered on again on the same network.

### What "unknown" means

A check is **unknown** when the data it needs isn't there, and the VM tab says why. The common cases:

- **Morpheus 9.0.x** doesn't report the chipset (machine type) or the guest agent channel, so `CHIPSET` and `GA-CHANNEL` are unknown on 9.0.x.
- **Discovered (unmanaged) VMs** have no Morpheus configuration, so the same two checks are unknown for them.
- **Powered-off VMs:** the guest agent status is judged only while the VM runs.
- **Time budget:** a part of a scan that didn't finish in time (snapshots, host or cluster name) shows as "unknown: time budget", and the rest of the result is still shown.

Unknown checks cost no points and are listed separately. They never count as a pass.

### Origin groups

Each VM is put in one of three groups, on the cluster tab and in the CSV (`origin`):

| Group | When | What it can't tell you |
|---|---|---|
| **Migrated from VMware** | The VM still has a VMware MAC address (prefix 00:50:56, 00:0C:29, 00:05:69 or 00:1C:14), or its OS type fields are incomplete the way a native VMware migration leaves them. | |
| **No VMware signal** | A managed VM with neither signal. | Most are native HVM VMs, but a VM migrated through an image upload or another tool, with a new MAC address and corrected OS type, lands here too. The in-guest checks of a later version will spot VMware Tools and other leftovers. |
| **Unknown origin** | A discovered (unmanaged) VM with neither signal. | It may have been created outside Morpheus or moved by another tool. |

The cluster tab explains this under **How origin is decided**.

### Scoring

Each VM starts at 100. Each failed check costs points: critical 25, warning 8, info 0 to 2. The score never goes below 0. Grades: **A** 90+, **B** 80+, **C** 70+, **D** 60+, **F** below 60. Unknown and not-evaluated checks cost nothing.

## The guest agent check

Through the plugin API, Morpheus 9.1.0.1 reports every VM's QEMU guest agent status as "unknown". So for the guest agent check (`GA-STATUS`), when a powered-on HVM VM's status is unknown, the plugin can ask the guest to run one fixed, harmless no-op command: `true` on Linux, `cmd /c exit 0` on Windows. It goes through Morpheus's own guest execution, not SSH or WinRM, with no credentials and no sudo. Only the success flag, the exit code and the first 200 characters of an error message are kept; never any output.

- **Who, and when:** only users whose role has **Migration Hygiene: Run guest scans** at **Full**, and never automatically when a tab opens. On the VM tab, they get **Check guest agent**, with a one-line explanation and the last result and its time ("Answered 2 min ago", "No answer 5 min ago: <short error>"). A cluster scan started by such a user (opening the cluster tab, or **Rescan**) also checks each powered-on VM of their own tenant in the background, and so does the fleet report they run.
- **Windows:** Morpheus runs the command through the QEMU guest agent. An answer passes the check; no answer fails it.
- **Linux:** Morpheus runs guest commands through its own Morpheus agent, so the check tests that agent, not the QEMU guest agent. The VM tab says so next to the button. An answer passes; no answer leaves the check **unknown**, not failed.
- **Never checked:** powered-off VMs, servers that aren't HVM VMs, and VMs whose status Morpheus does report (then no button appears).
- **Limits:** 20 seconds per VM; at most once a minute per VM (sooner, the last result is shown); one at a time per VM. The fleet report checks at most 4 VMs at a time and starts none after 3 minutes ("not probed: time budget").
- **Logged:** each run is logged on the appliance with the Morpheus user id and server id. Results are kept in memory for 10 minutes and shown only to users with the guest-scan permission.

## Where users see it

| Where | What | License |
|---|---|---|
| **Cluster tab** (Infrastructure → Clusters → an HVM cluster → Migration Hygiene) | The fleet view: every HVM VM in the cluster, grade counts, findings by severity, origin groups, worst VMs, findings per check, Rescan and CSV downloads. | Every license |
| **VM tab** (Infrastructure → Compute → Virtual Machines → a VM) and **instance tab** (Provisioning → Instances → an instance) | One VM's grade and findings, with Refresh and Check guest agent. | Every license |
| **CSV export** (from the cluster tab) | One row per VM, for this cluster or every HVM cluster you can see. | Every license |
| **Fleet report** (Operations → Reports → Migration Hygiene) | A summary, then one row per HVM VM; Morpheus can export the rows as CSV. Runs from the master tenant only, for users with the view permission. | Licenses that include Reports |

The Reports page isn't part of the Morpheus VM Essentials license, so on that edition the cluster tab and the CSV are the fleet view.

### CSV columns

`name`, `cluster`, `host`, `os`, `serverType`, `managed`, `power`, `grade`, `score`, `critical`, `warning`, `info`, `unknown`, `vmwareOrigin` (yes/no), `origin` (migrated, not-migrated, unknown), `guestAgentProbe`, `topFindings` (plain titles with severity), `checkIds` (short IDs), `scanStatus`, `scannedAt`.

The browser builds the file from the plugin's own data: it shows progress ("Preparing CSV: scanned 12 of 63…"), waits for the scan to finish (at most 15 minutes per cluster; after that the file has the VMs scanned so far, with a note), and saves `migration-hygiene-<cluster>-<date>.csv` in UTF-8 with a byte order mark, so Excel reads it correctly. **All clusters** goes through your clusters one at a time and saves one file. Cells a spreadsheet could read as a formula get a leading apostrophe.

Under the buttons, **direct download** links are a fallback for browsers where the buttons don't work. They give the same columns from results already in memory and never start a scan: a cluster that's still scanning, or wasn't scanned in the last 10 minutes, is listed as such.

## Performance

Tabs never block a page load. Morpheus renders plugin tabs while it builds the page, so the tabs only render a small "Loading…" shell, or a result already in memory. The plugin's script then fills the tab from the plugin's own routes, and only for a tab you actually open.

- **VM tab:** one VM is scanned with a 15-second budget.
- **Cluster tab:** the scan runs in the background, one per cluster at a time (a second viewer joins the running scan), on one worker that pauses 250 ms after each VM, with a 15-second budget per VM and 15 minutes overall. The tab shows progress and refreshes every 5 seconds until the scan is done; polling pauses while the tab is hidden.
- **Agent baseline** (the newest Morpheus agent per OS family, for `MORPHEUS-AGENT`): computed in the background at most every 15 minutes.
- **Caching:** results are kept in memory for 10 minutes, so reopening a tab within that time is instant.
- **Limits:** at most 2 cluster scans run at once and 8 wait; beyond that, or beyond 3 running scans per user, a new scan is refused with "too many scans in progress, try again shortly".

All plugin threads are named `migration-hygiene-*` and stop when the plugin is disabled or removed.

## Permissions

The plugin adds two permissions to every Morpheus role:

| Permission | Levels | Grants |
|---|---|---|
| **Migration Hygiene: View results** (`migration-hygiene-view`) | None, Read | The tabs and results, Rescan, the CSV downloads and the fleet report. Users see only the VMs and clusters their tenant owns (the master tenant sees all). |
| **Migration Hygiene: Run guest scans** (`migration-hygiene-guest-scan`) | None, Full | Optional. The [guest agent check](#the-guest-agent-check), a no-op command. Later versions will use it for the read-only in-guest checks. |

Set them in **Administration → Roles → (role)**: search the permissions for "Migration Hygiene". Or run [`tools/create-readonly-user.py`](tools/create-readonly-user.py), which makes a least-privilege role and user for you (see the [install guide](docs/INSTALL.md#4-give-people-access)).

Viewing a stored fleet report is governed by Morpheus's own Reports permission, like any other report.

## Security

To report a vulnerability, see [SECURITY.md](SECURITY.md).

- **What runs where.** Everything runs inside the Morpheus appliance, in the plugin's own threads, through Morpheus's plugin API. The browser script only talks to the plugin's own routes on the same appliance. **Nothing is sent off the appliance**: no telemetry, no external calls.
- **Read-only.** The checks read Morpheus data and never change a VM, host or cluster. The only thing that ever runs inside a VM is the optional [guest agent check](#the-guest-agent-check): a fixed no-op command, no user input, no credentials, no sudo, no output kept, behind its own permission, at most once a minute per VM, and logged with the Morpheus user id.
- **Every route checks the user itself.** Morpheus only checks that a role has the permission code, so each route checks the level (view: Read; guest agent check: Full) and the tenant before it reads, scans or checks anything. Outside the master tenant, a user sees only their own tenant's VMs and clusters, and a guest-scan user's cluster scan checks only their own tenant's VMs.
- **Only the plugin's own script starts work.** A GET starts or joins a scan only when it carries `X-Requested-With: XMLHttpRequest` and a same-origin `Origin`, `Referer` or `Sec-Fetch-Site`. A plain link, a new tab or another site gets results already in memory, or a note to open the tab; it never starts a scan or a guest command. POSTs (Rescan, Check guest agent) also need the same origin and Morpheus's CSRF token. `X-Forwarded-Host` isn't trusted for the origin check.
- **Output.** Every value is HTML-escaped in the tabs. The script inserts only same-origin HTML that starts with the plugin's own markup (an expired session shows "Reload the page and sign in again" instead). CSV cells that could run as spreadsheet formulas are neutralized. The server CSV is served only as a `text/csv` attachment with `nosniff`, and JSON as UTF-8.
- **What view users see (by design).** For the VMs their tenant owns, view users see what the checks are about: full MAC addresses, attached ISO and volume paths, host and cluster names, OS and agent versions. A role without Infrastructure read still learns these through the plugin, so grant the view permission to people you'd show the VMs' settings to.
- **Status route.** `status` answers `ok` and the plugin version to view users, nothing else.
- **Tools.** `tools/create-readonly-user.py` creates a least-privilege role (the pages the tabs live on, plus the plugin's view permission; guest scans only with `--guest-scan`), reads it back and stops if anything else is above None. `tools/collect-fleet-sample.py` (for contributors) collects an anonymized sample, with names, addresses, UUIDs and ids replaced by keyed tokens and dates coarsened, into a file only you can read.

### Independent security review

Before 0.4.0, the routes, permission model, browser script, tools, built jar and git history were reviewed independently by a separate AI reviewer (Anthropic's Claude Fable model), with read-only access to the source. It found **no Critical or High issue**, and nothing reachable without a Morpheus account. It found:

- **two Medium:** the server-side CSV fallback relied on a response header the appliance might drop; and a plain GET (for example a link) could start cluster scans, and with them the guest agent check for a user with the guest-scan permission;
- **several Low and Informational findings:** the VM route scanned before it checked the tenant; cluster views were scoped to the cluster's tenant rather than each VM's; the guest agent check had no rate limit; the scan queue had no bound; the CSV formula guard missed two cases; the role script granted more than the plugin needs; and documentation gaps about what view users can see.

All of them are fixed in 0.4.0, with tests. Every finding needed a signed-in Morpheus user with the plugin's permissions, and the worst outcome was a bounded load spike or the no-op command. **If you run 0.3.x or earlier, upgrade to 0.4.0**, and if you made a role with an earlier copy of the role script, delete it and run the new one.

## How it works

![How migration-hygiene-plugin works: the browser, the plugin inside the Morpheus appliance, and the HVM VMs, with the VM tab, cluster scan, CSV and guest agent check flows](docs/images/how-it-works.svg)

In short:

- **Everything happens on the appliance.** The plugin reads what Morpheus already knows about each VM: disks and controllers, network interfaces and MAC addresses, machine type, firmware, guest agent and Morpheus agent state, snapshots, CD-ROM drives and the server type.
- **Tabs are shells; the script fills them.** Page loads never wait for a scan. The plugin's script, loaded with Morpheus's CSP nonce, asks the plugin's routes for results only for the tab you open.
- **Cluster scans run in the background**, paced, cached for 10 minutes, and shared by everyone viewing the same cluster.
- **The checks are plain code** with no Morpheus types (`core/Tier1Rules.groovy`), and every check's text lives in one place (`core/Tier1Catalog.groovy`): the evidence, "If you leave it", "If you fix it" and references. The `Check` model refuses a check without them.

The infographic is generated by [`tools/draw-how-it-works.py`](tools/draw-how-it-works.py); edit the text there and run it again to update it.

## Requirements

- HPE Morpheus **9.0.x or 9.1.x** (Plugin API 1.4.1 or later), any edition with HVM clusters: Morpheus VM Essentials, Morpheus Advanced or Morpheus Enterprise. Tested on 9.1.0.1; an earlier version was also tried on 9.0.1.
- HVM clusters. Other cloud types are ignored.
- No network access, credentials or settings: the plugin has no settings form.

## Download

Use the prebuilt jar from the [Releases](../../releases) page. There's no need to build anything.

**One jar, `migration-hygiene-plugin-<version>.jar`, works on HPE Morpheus 9.0.x and 9.1.x.** It's compiled against the oldest supported Plugin API (1.4.1, Morpheus 9.0), and its whole test suite also runs against Plugin API 1.5.0 (Morpheus 9.1). Before each release, `tools/check-plugin-core.sh` checks HPE's published plugin sources and docs for any compatibility change.

Each release includes `SHA256SUMS`. Check the download with `sha256sum -c SHA256SUMS`.

Then follow **[docs/INSTALL.md](docs/INSTALL.md)**.

## Build from source (optional)

You need only Docker; the build runs in a pinned JDK 17 container.

```bash
git clone https://github.com/grahamfairbank/migration-hygiene-plugin.git
cd migration-hygiene-plugin
tools/build.sh
```

This runs the tests, builds `dist/migration-hygiene-plugin-<version>.jar` against Plugin API 1.4.1, and repeats the tests against Plugin API 1.5.0.

## Limits

- **`SOURCE-TWIN` isn't evaluated yet.** It's defined (and shown as "not evaluated") until matching a VMware VM to its HVM copy is verified.
- **No in-guest checks yet.** VMware Tools leftovers, VirtIO driver readiness and the guest's own disk usage can't be seen from Morpheus data. A VM migrated with a new MAC address and corrected OS type shows as **No VMware signal**.
- **On Linux, the guest agent check tests the Morpheus agent**, not the QEMU guest agent (see above).
- **Morpheus 9.0.x reports less:** the chipset and guest agent channel checks are unknown there.
- **Some fix paths aren't verified yet on HVM** (for example, changing an existing boot disk's controller in place with Reconfigure). Each finding lists what isn't verified under **Not yet verified**.
- **The fleet report runs from the master tenant only.**
- **Results live in memory** for 10 minutes; there's no history. Save the CSV if you want to compare later.

## Roadmap

- **Tier 2: read-only checks inside the guest**, from a fixed, built-in list of commands behind the guest-scan permission: VMware Tools leftovers, VirtIO driver readiness (Windows boot-start drivers, Linux initramfs), fstab by UUID, and the guest's own disk usage.
- **`SOURCE-TWIN`**: find a VMware source VM that's still running next to its HVM copy.
- **Later, optional:** guided Linux remediation, kept separate from the read-only audit and behind its own permission.

Ideas and pull requests are welcome; see [Contributing](#contributing).

## Contributing

**Pull requests are welcome.** See **[CONTRIBUTING.md](CONTRIBUTING.md)** for how to build, test and submit changes. Use the [issue templates](../../issues/new/choose) for bugs and ideas, and report security problems privately.

## Sources, attribution and trademarks

This plugin was written from scratch, using HPE's public documentation ([docs.morpheusdata.com](https://docs.morpheusdata.com/en/9.1/) and its source on GitHub, [HewlettPackard/morpheus-docs](https://github.com/HewlettPackard/morpheus-docs)) and the Morpheus plugin API as references, and follows the architecture of its sibling project [com-morpheus-plugin](https://github.com/grahamfairbank/com-morpheus-plugin). It is an independent community project, not an HPE product, and isn't supported by HPE. **[NOTICE.md](NOTICE.md)** lists every source, its license and exactly how it was used, how the test data and screenshots were made, and the full trademark notices.

## License

[MIT](LICENSE) for this project's own code. The Morpheus plugin API it compiles against is licensed separately by Morpheus Data, LLC and isn't included.
