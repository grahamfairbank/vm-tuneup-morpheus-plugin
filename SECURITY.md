# Security policy

migration-hygiene-plugin is a community project, not an HPE product. Please don't contact HPE support about it.

## Reporting a vulnerability

**Please report security problems privately, not as a public issue.**

Use GitHub's private vulnerability reporting: on this repository, open **Security → Report a vulnerability** ([direct link](https://github.com/grahamfairbank/migration-hygiene-plugin/security/advisories/new)). Only the maintainer sees the report.

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
| 0.4.x (latest) | Yes |
| 0.3.x and earlier | No. Before 0.4.0, a plain GET (for example a link) could start a cluster scan, and with it the guest agent check for a user with the guest-scan permission. |

## What runs where

- **On the appliance only.** The plugin runs inside the Morpheus appliance and uses the plugin API. It makes no network calls of its own and sends nothing off the appliance.
- **In the browser.** A small script, loaded with Morpheus's CSP nonce for users with "Migration Hygiene: View results", fills the plugin's tabs from the plugin's own routes on the same appliance. It runs no dynamic code and acts only inside the plugin's tabs.
- **Inside VMs, only on request.** The guest agent check runs one fixed no-op command (`true` on Linux, `cmd /c exit 0` on Windows) through Morpheus's guest execution: no user input, no credentials, no sudo, no output kept. Only users with "Migration Hygiene: Run guest scans" at full can trigger it, never automatically when a tab opens, at most once a minute per VM, and each run is logged with the Morpheus user id.

## Permissions model

- **Migration Hygiene: View results** (`migration-hygiene-view`, read): the tabs, the cluster fleet view, CSV downloads and the fleet report.
- **Migration Hygiene: Run guest scans** (`migration-hygiene-guest-scan`, full): the guest agent check.
- Every route checks the level and the tenant itself, before it reads, scans or checks anything; the Morpheus dispatcher only checks that the role has the code. Outside the master tenant, users see only their own tenant's VMs and clusters.
- A GET starts work only for the plugin's own script (`X-Requested-With` and a same-origin `Origin`, `Referer` or `Sec-Fetch-Site`); POSTs also need the same origin and Morpheus's CSRF token.

The [Security section of the README](README.md#security) has the details, including what view users can see and the [independent security review](README.md#independent-security-review) done before 0.4.0.

## What's in scope

- The plugin jar: its routes, permission and tenant checks, the guest agent check, what it shows to which Morpheus users, and what it sends to the browser.
- The scripts in `tools/`: `create-readonly-user.py` (what the role it creates can do) and `collect-fleet-sample.py` (what its output reveals).

## Out of scope

- Problems in HPE Morpheus itself. Report those to HPE.
- What a user with a permission can do by design: for example, a guest-scan user running the no-op guest agent check in a VM their tenant owns, or a view user seeing the MAC addresses and paths the checks report.
- Findings that need an appliance administrator, or someone who can already install plugins.
