# Changelog

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
- Two permissions: **VM Tune-Up: View results** and **VM Tune-Up: Run guest scans**.
- One jar for Morpheus 9.0 and 9.1 (Plugin API 1.4.1, tested on 1.5.0).
