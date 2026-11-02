# Changelog

## 0.7.1 (2026-11-02): first public release

The first public release. It brings together 0.6.0, 0.7.0 and the 0.7.1 changes below. In short:

- **A tune-up plan for every VM:** fixes grouped into one outage of the VM, one guest restart or no downtime, prerequisites once and in order, and the grade **after the plan**; a plan summary for the cluster; plan columns in the CSV.
- **Three new Tier 1 checks:** HA and mobility, nested virtualization, and CPU topology (critical for Windows 10 or 11 spread over more than 2 sockets). The VMware source VM check (`SOURCE-TWIN`) is gone.
- **Host CPU check** on the cluster tab: reconciles the cluster's CPU model setting with every HVM host's CPU and microcode, and recommends the best named model all hosts can run.
- **Tier 2 guest checks:** one fixed, read-only script per OS, through Morpheus's guest execution, with six new checks.
- **Fill in OS fields:** the plugin's one write, opt-in, behind a new permission, empty fields only, confirmed and logged.
- **Printable report** (print to PDF), links to Morpheus pages, an activity indicator, and grade tables in columns.
- **No credentials anywhere:** every command goes through channels Morpheus already has.

Upgrading from 0.5.0: give the new **VM Tune-Up: Update VM records** permission only to people who may edit VM records; it starts at **None**.

**Also new in 0.7.1: clearer wording and layout.**

- **"Host CPUs" is now "Live migration readiness"**, with a **Check the hosts** button. When the hosts are identical, the result says host passthrough is fine, and that the CPU Model setting will need to change to a named model (it names the best one) before a host with a different CPU or microcode joins.
- **"Cold window" is now "outage", per VM:** "One outage of this VM (powered off once)", "Outage (2 fixes)", "Before the outage", and "Outages are per VM: the rest of the cluster keeps running." The CSV code stays `cold`.
- **VMs ordered by tune-up benefit** (the score the plan gains, biggest first) replace "Worst VMs" on the cluster tab and "All VMs, worst first" in the printable report. The CSV keeps lowest score first.
- **Origin labels: Migrated, Not migrated, Origin unknown** (were Migrated from VMware, No VMware signal, Unknown origin). The filter is a **Migrated VMs only** checkbox. VMware Tools found inside a Windows guest by the guest checks now also marks a VM Migrated (not on Linux: many Linux images ship open-vm-tools). Hover over a VM's origin to see why.
- **Scan time and "don't refresh":** under the CSV buttons, a line gives the expected scan time (about 2 to 4 seconds per VM, measured on a 9.0.1 cluster) and asks you to stay on the page while the scan runs.
- **Fill in explained:** the cluster and VM tabs say that Fill in copies the OS the guest checks read from inside the VM, through its agent, into the empty OS fields of the Morpheus record. The cluster button is **Fill in N VMs in Morpheus**.
- **VM tab layout:** the action buttons are the same width, on the left, with their status to the right and the detail underneath; **Fill in OS fields** follows the same layout.
- **Fixes:** **Show all** no longer appears when every VM is already listed (Morpheus's button style overrode the hidden attribute); "1 VM" instead of "1 VMs" in the guest checks and fill-in texts; the scan-time line wraps instead of running off the tab; the plan column in the printable report no longer wraps.
- **Screenshots retaken** on 0.7.1 for every doc image, plus a short tour GIF at the top of the README.

## 0.7.0 (2026-11-02)

**Tier 2: checks inside the guest.**

- **Run guest checks** on the VM tab, and **Run guest checks on all powered-on VMs** on the cluster tab (one VM at a time, with progress), for users with **VM Tune-Up: Run guest and host checks** at **Full**. Only when someone clicks.
- **One fixed, read-only script per OS:** PowerShell through the QEMU guest agent on Windows (as SYSTEM), `sh` through the Morpheus agent on Linux (as root). Nothing from the request or the VM goes into it; it writes nothing, installs nothing and opens no network connection. Its output is capped and parsed into a versioned `key=value` list. The README lists every command each script runs.
- **Six Tier 2 checks:** guest not ready for the switch to VirtIO (`T2-VIRTIO-READY`), VMware Tools still installed (`T2-VMWARE-TOOLS`), guest agent can't freeze the file systems (`T2-GA-FEATURES`), guest time not synchronized (`T2-TIME-SYNC`), VirtIO driver version (`T2-VIRTIO-VERSION`, Windows), and domain controller without VM-Generation ID (`T2-DC-VMGENID`, a Consideration). They cite HPE first, then Microsoft, QEMU, libvirt, virtio-win and Linux documentation.
- **Results are kept 1 hour** and added to the VM's grade and plan while they last. A failed scan or a value the script couldn't read is unknown, never a pass.
- **The guest scan runs in the background** and the VM tab polls until it's done: a long scan took longer than Morpheus lets a plugin request run. Each scan has a 2-minute limit, one per VM at a time, at most once a minute.
- **Logged:** each scan with the user id, the server id and the script's SHA-256. The output is never logged.
- **Fill in OS fields** (the plugin's one write): when Morpheus has empty guest OS fields and a guest scan identified the OS, **Fill in OS fields** on the VM tab, or **Fill in all N** with a preview list on the cluster tab, fills **only the empty fields** (OS type, OS family, platform, version) after a confirm. A field Morpheus already has is never changed. Each write is logged with the user id, server id and OS code.
- **New permission: VM Tune-Up: Update VM records** (`vm-tuneup-update-records`, None or Full), for Fill in OS fields only. `tools/create-readonly-user.py` never grants it.
- Lab-verified on Morpheus 9.1.0.1: the Windows guest scan through the QEMU guest agent, and Fill in OS fields. The Linux script was verified over SSH on Rocky Linux 9 and Ubuntu; the Linux path through the Morpheus agent isn't verified end to end yet.

## 0.6.0 (2026-11-02)

**The tune-up plan, three new checks and the host CPU check.**

- **Tune-up plan per VM:** the failed Recommendations grouped by the downtime they need, so every fix that powers the VM off happens in **one cold window**. The plan lists the prerequisites once, in order and for the VM's OS (**Before the window**), then **Any time, no downtime**, **With one guest restart** and **In one cold window**. Considerations are left out and counted. The plan shows the grade now and **after the plan** (every Recommendation done).
- **Cluster plan summary:** grade counts and average score **Now** and **After the plan**, and how many VMs need a cold window, a guest restart, no downtime or nothing. **Worst VMs** shows each VM's grade after the plan and its window.
- **New CSV columns:** `gradeAfter`, `scoreAfter`, `planWindow` (`cold`, `guest-reboot`, `live`, `none`) and `planSteps` (short IDs in order).
- **New Tier 1 checks:**
  - **VM can't fail over or live-migrate** (`T1-HA-MOBILITY`): disks on host-local storage (warning), pinned placement or host devices passed through (info).
  - **Nested virtualization turned on** (`T1-NESTED-VIRT`, warning): HVM marks such a VM as not migratable.
  - **vCPUs spread over too many sockets** (`T1-CPU-TOPOLOGY`, Windows): **critical** for Windows 10 or 11 with more than 2 sockets (the extra vCPUs sit idle); info for other Windows VMs with more than one socket.
- **Removed: `T1-SOURCE-TWIN`** (VMware source VM still running). It was never evaluated, and what happens on the VMware side is out of scope.
- **Host CPU check** on the cluster tab (**Check host CPUs**): runs `virsh capabilities` and `virsh domcapabilities --virttype kvm` on each HVM host of the cluster, through Morpheus, as root or with `sudo -n`. It reconciles the cluster's CPU model setting with every host's CPU, features and microcode (vendor or architecture mix, host passthrough on hosts that differ, a named model some host can't run, for example after microcode removed TSX), and recommends the newest named model every host can run. For master-tenant users with **Run guest scans** at **Full**; one run per cluster at a time; kept 1 hour; logged. Host XML is size-capped and parsed with DTDs and external entities off. Not part of the VM grades.
- **Printable report:** **Print report** on the cluster tab opens a page for the browser's Save as PDF: summary, all VMs worst first with their grade now and after, the plan per VM, and the host CPU result. It uses results already scanned only.
- **Links to Morpheus:** VM names on the cluster tab, and **Open the instance in Morpheus** on the plan, go to the instance or VM page where the actions are.
- **Activity indicator** while a scan, a guest check or a host check runs, and **grade tables in columns** (grades A to F side by side).

## 0.5.0 (2026-11-02)

**New name: VM Tune-Up** (was Migration Hygiene, never released under that name). The plugin checks every VM on an HVM cluster, not only migrated ones, so the name now says what it does. The tagline stays: "We've migrated to HVM. Now what?"

- New plugin code `vm-tuneup`, permissions `vm-tuneup-view` and `vm-tuneup-guest-scan`, routes under `/plugin/vmTuneUp/`, log prefix `vm-tuneup:` and jar `vm-tuneup-morpheus-plugin-<version>.jar`.
- If you tried a Migration Hygiene build: delete it on the Plugins page, install VM Tune-Up, and give your roles the two new **VM Tune-Up** permissions.
- Checks, grades and behavior are the same as in 0.4.0.

## 0.4.0 (2026-11-02)

Tested on Morpheus 9.1.0.1. Security hardening after an independent review of the plugin, its tools, the built jar and the git history (no Critical or High findings; see [Independent security review](README.md#independent-security-review)). The tabs look and work the same as in 0.3.2.

- **Only the plugin's own script starts work.** A GET to the plugin's routes starts or joins a scan only when it's the script's own request (`X-Requested-With` plus a same-origin `Origin`, `Referer` or `Sec-Fetch-Site`). A plain link, a new tab or another site gets results already in memory, or "Not scanned yet. Open the VM Tune-Up tab…"; it never starts a scan and never runs the guest agent check. Before, a link an administrator clicked could start cluster scans, and with them the guest agent check.
- **The CSV direct download never starts a scan.** For all clusters, clusters not scanned yet are listed as such. The file is served only as a `text/csv` attachment with `nosniff`; if the appliance can't serve it that way, the link says so (HTTP 406) and points you to the **Download CSV** buttons. The direct links are labeled as a fallback.
- **Tenant checks first.** The VM route checks that the user's tenant owns the VM before it reads or scans anything. Cluster views show non-master users only their own tenant's VMs, and a guest-scan user's cluster scan checks only their own tenant's VMs.
- **Check guest agent** runs at most once a minute per VM (sooner, it shows the last result and when it ran), one at a time per VM, on a small dedicated pool. Each run is logged with the Morpheus user id and server id.
- **Bounded scan queue:** 2 cluster scans run at once and 8 wait, and each user can have 3 running; beyond that, "too many scans in progress, try again shortly".
- **CSV formula guard** also covers `|` and cells whose first non-space character is a formula character, in both the browser and server CSV.
- **The browser script** inserts only same-origin answers that start with the plugin's own markup. An expired session shows "Reload the page and sign in again" instead of a sign-in page inside the tab.
- **The fleet report** scans only for a user with the view permission; for anyone else it holds one row saying the permission is needed.
- JSON answers are UTF-8 with `nosniff`. `X-Forwarded-Host` is no longer trusted for the origin check. `status` returns only `ok` and the plugin version.
- **`tools/create-readonly-user.py` grants only what the tabs need:** Read on Operations: Dashboard and Infrastructure: Compute, Clusters, Groups and Clouds, default access to groups and clouds, and the plugin's view permission (guest scans only with `--guest-scan`). It reads the role back and stops before creating a user if anything else is above None. **If you made a role with an earlier copy, delete it and run the new script.**
- **`tools/collect-fleet-sample.py`** replaces record ids with keyed tokens, keeps dates to the month or day, writes a file only you can read, never overwrites one, and prints only the HTTP status on errors (format `vm-tuneup-fleet-sample/2`).
- **Build:** the JDK image is pinned by digest; a new check (`tools/check-js.sh`) lints the browser script. The tracked test fixtures no longer carry hashes of lab names.
- **Docs:** new README, step-by-step [install guide](docs/INSTALL.md), [SECURITY.md](SECURITY.md), contributing guide, issue templates and the "How it works" infographic.

## 0.3.2 (2026-11-02)

- **Scans start only for tabs you open.** Before, opening a cluster page filled every plugin slot on it, including the hidden VM Tune-Up tab, so just visiting a cluster started a background scan and slowed later page loads. Tabs now load only when visible, and polling pauses while a tab is hidden.
- **Paced background scans:** one worker per cluster scan, with a 250 ms pause after each VM.

## 0.3.1 (2026-11-02)

- **"Not migrated" is now "No VMware signal".** A VM migrated through an image upload gets a new MAC address and correct OS type, so Morpheus data can't tell it from a native HVM VM. The group name and the origin note now say only what the plugin can see; the in-guest checks of a later version will catch the rest.

## 0.3.0 (2026-11-02)

- **Check guest agent** on the VM tab: a one-line explanation, the last result and when it ran, a note on Linux (Morpheus runs the command through its own agent), and a greyed-out "You don't have permission" for users without the guest-scan permission.
- **Origin groups** on the cluster tab: Migrated from VMware, Not migrated and Unknown origin, with a summary per group, an origin filter, an Origin column and "How origin is decided".
- **The browser builds the CSV.** The **Download CSV** buttons wait for the scan with visible progress, then save the file (UTF-8 with a byte order mark, formulas neutralized). New CSV columns `origin` and `checkIds`; `topFindings` uses plain titles. The server-side CSV stays as a fallback.
- **Plain check titles** in the UI, with short IDs (`DISK-BUS`) in small type, and an "About the checks" note.

## 0.2.0 (2026-11-02)

- **Tabs never block page loads.** 0.1.x scanned while Morpheus built the page, so Infrastructure pages could hang. Tabs now render a small shell, and the plugin's script fills them from the plugin's routes.
- **The cluster tab is the fleet view:** grade counts, findings by severity, the worst VMs and failures per check, with **Rescan** and CSV downloads. The Morpheus VM Essentials license has no Reports page, so the fleet report can't be the main view there.
- VM scans have a 15-second budget; parts not done in time show as "unknown: time budget". Cluster scans run in the background with progress, one per cluster at a time.
- The agent baseline is computed in the background at most every 15 minutes. Results are cached for 10 minutes. All plugin threads stop when the plugin is disabled or removed.
- The guest agent check never runs automatically when a tab opens.

## 0.1.3 (2026-11-01)

- **On Linux, no answer to the guest agent check is unknown, not a failure.** Morpheus runs Windows guest commands through the QEMU guest agent, but Linux ones through its own Morpheus agent, so a Linux non-response says nothing about the QEMU guest agent.
- Report rows show the guest agent check's short error.

## 0.1.2 (2026-11-01)

- **The guest agent check is back.** Through the plugin API, Morpheus 9.1.0.1 reports every VM's guest agent status as "unknown". For a powered-on HVM VM with an unknown status, the plugin can now run a fixed no-op command in the guest (`true` on Linux, `cmd /c exit 0` on Windows), for users with the guest-scan permission only, with a 20-second timeout. Only the success flag, exit code and a short error are kept.

## 0.1.1 (2026-11-01)

- **No more false guest agent warnings.** An unreported guest agent status is treated as unknown, not as "not connected".

## 0.1.0 (2026-11-01): first build, not released

- Fourteen Tier 1 checks from Morpheus data: disk bus, network adapter model, chipset, VMware MAC address, OS type after migration, firmware and TPM, guest agent channel and status, Morpheus agent, snapshots, disk allocation, discovered VMs, attached ISOs, and the VMware source VM (defined, not evaluated yet).
- Every check explains **If you leave it** and **If you fix it** (downtime, risk, whether it can be undone, prerequisites in order), is a **Recommendation** or a **Consideration**, and cites HPE documentation first. Unknown results are listed, never scored.
- VM Tune-Up tabs on HVM VM, instance and cluster pages, and a fleet report. Scores from 100 (critical 25, warning 8, info 0 to 2), grades A to F.
- Two permissions: **VM Tune-Up: View results** and **VM Tune-Up: Run guest and host checks**.
- One jar for Morpheus 9.0 and 9.1 (Plugin API 1.4.1, tested on 1.5.0).
