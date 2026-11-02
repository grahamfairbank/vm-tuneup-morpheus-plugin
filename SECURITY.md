# Security policy

vm-tuneup-morpheus-plugin is a community project, not an HPE product. Please don't contact HPE support about it.

## Reporting a vulnerability

**Please report security problems privately, not as a public issue.**

Use GitHub's private vulnerability reporting: on this repository, open **Security → Report a vulnerability** ([direct link](https://github.com/grahamfairbank/vm-tuneup-morpheus-plugin/security/advisories/new)). Only the maintainer sees the report.

Please include:

- the plugin version and your Morpheus version;
- what a user (or someone without an account) can do that they shouldn't, and which Morpheus permissions they need;
- the steps to reproduce it.

Use example values in anything you send: `app-legacy-01`, `hvm-host-01`, `cluster-a`, `192.0.2.21`, `morpheus.example.com` and so on. Never send a real token, password, host name, MAC address or IP address. [CONTRIBUTING.md](CONTRIBUTING.md) explains how to replace them.

This is a community project maintained in spare time. You should get a reply within a week. Confirmed issues are fixed in a new release, with a note in the [changelog](CHANGELOG.md); you'll be credited there unless you'd rather not be.

## Supported versions

Only the latest release gets security fixes. Upgrade to it before reporting, if you can.

| Version | Supported |
|---|---|
| 1.0.x (latest) | Yes |
| 0.x | No. These were pre-release builds. Before 0.4.0, a plain GET (for example a link) could start a cluster scan, and with it the guest agent check for a user with the guest-scan permission. |

## What runs where

- **On the appliance only.** The plugin runs inside the Morpheus appliance and uses the plugin API. It makes no network calls of its own and sends nothing off the appliance.
- **No credentials.** The plugin has no settings and never asks for, receives or stores a password, key or token. Every command goes through a channel Morpheus already has.
- **In the browser.** A small script, loaded with Morpheus's CSP nonce for users with "VM Tune-Up: View results", fills the plugin's tabs from the plugin's own routes on the same appliance. It runs no dynamic code and acts only inside the plugin's tabs. It asks the user to confirm before the one write.
- **Inside VMs**, through Morpheus's guest execution (the QEMU guest agent on Windows, the Morpheus agent on Linux), only for users with "VM Tune-Up: Run guest and host checks" at full, and logged with the Morpheus user id:
  - the guest agent check: one fixed no-op command (`true` on Linux, `cmd /c exit 0` on Windows), when the user clicks Check guest agent, and for each powered-on VM of their tenant during a cluster scan they start; no output kept;
  - the guest checks, only when the user clicks: one fixed, read-only script per OS (PowerShell as SYSTEM on Windows, `sh` as root on Linux). Its output is size-capped, parsed, kept in memory for an hour and never logged; the script's SHA-256 is logged.
- **On HVM hosts, only on request**, through Morpheus's own connection to the host, for master-tenant users with "VM Tune-Up: Run guest and host checks" at full: `virsh -c qemu:///system capabilities` and `virsh -c qemu:///system domcapabilities --virttype kvm`, as root or with `sudo -n` (never prompts). The XML output is size-capped and parsed with DTDs and external entities off.
- **One write, only on request.** "Fill in OS fields" saves a VM record through the plugin API, filling only its empty guest OS fields (OS type, OS family, platform, version) from the guest check result, for users with "VM Tune-Up: Update VM records" at full, after a confirm. Each write is logged with the user id, server id and OS code.

Every command is a constant in the plugin's code. Nothing from the request, the page, the VM or Morpheus data is ever put into a command.

## Permissions model

- **VM Tune-Up: View results** (`vm-tuneup-view`, read): the tabs, the cluster fleet view, plans, CSV downloads, the printable report and the fleet report.
- **VM Tune-Up: Run guest and host checks** (`vm-tuneup-guest-scan`, full): the guest agent check, the guest checks, and (master tenant only) the host CPU check.
- **VM Tune-Up: Update VM records** (`vm-tuneup-update-records`, full): Fill in OS fields.
- Every route checks the level and the tenant itself, before it reads, scans or runs anything; the Morpheus dispatcher only checks that the role has the code. Outside the master tenant, users see only their own tenant's VMs and clusters, and cluster-wide guest checks and fill-ins cover only their tenant's VMs.
- A GET starts work only for the plugin's own script (`X-Requested-With` and a same-origin `Origin`, `Referer` or `Sec-Fetch-Site`). The only command a GET can lead to is the no-op guest agent check, inside a cluster scan started by a guest-scan user. The guest checks, the host CPU check and the write are POSTs that also need the same origin and Morpheus's CSRF token, and so are Rescan and Check guest agent.

The [Security section of the README](README.md#security) has the details, including what view users can see and the [independent security review](README.md#independent-security-review) done before 0.4.0.

## What's in scope

- The plugin jar: its routes, permission and tenant checks, what it shows to which Morpheus users, and what it sends to the browser.
- **Commands on hosts and in guests:** the guest agent check, the guest scripts and the host CPU commands. For example: a way to change what runs, to run it without the permission or in another tenant's VM, to run it from a link or another site, or to make a script write or reach the network.
- **The one write:** a way to make Fill in OS fields change a field that isn't empty, change another VM or tenant, or write without the permission or the confirm.
- Parsing of guest and host output: for example, output that makes the plugin misbehave, use too much memory, or show markup in a tab.
- The scripts in `tools/`: `create-readonly-user.py` (what the role it creates can do) and `collect-fleet-sample.py` (what its output reveals).

## Out of scope

- Problems in HPE Morpheus itself, including its guest execution and host connections. Report those to HPE.
- What a user with a permission can do by design: for example, a guest-scan user running the guest checks in a VM their tenant owns, a master-tenant guest-scan user running the host CPU check, an update-records user filling in empty OS fields, or a view user seeing the MAC addresses, paths and guest check results the checks report.
- Findings that need an appliance administrator, root on a host or in a guest, or someone who can already install plugins.
