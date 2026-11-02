# Contributing to vm-tuneup-morpheus-plugin

**Pull requests are welcome.** This is a community project, and it gets better with outside help: new checks, better fix guidance, results from Morpheus versions and guest operating systems we haven't seen, documentation and translations.

> This is an unofficial project, not an HPE product (see [NOTICE.md](NOTICE.md)). Contributions are reviewed by the maintainers, not by HPE.

## Before you start

- **Bugs and ideas:** open an [issue](../../issues/new/choose) first if the change is large, so we can agree on the approach.
- **Small fixes:** open a pull request directly.
- **Security issues** (for example, a way around the permission or tenant checks, a way to change what the plugin runs in a VM or on a host, or a way to make it write something other than empty OS fields): please don't open a public issue. Use GitHub's **private vulnerability reporting** (Security → Report a vulnerability) instead. See [SECURITY.md](SECURITY.md).

## Build and test

You only need Docker:

```bash
tools/build.sh
```

This builds and tests one jar into `dist/`. It's compiled against Plugin API 1.4.1 (Morpheus 9.0), and the whole test suite is repeated against 1.5.0 (Morpheus 9.1). Every change must keep both test runs green, and must not use anything that's missing from 1.4.1.

- `tools/check-js.sh` checks the browser script (`src/assets/javascript/vm-tuneup.js`).
- Maintainers: before a release, run `tools/check-plugin-core.sh`. It reports any change in HPE's published morpheus-plugin-core or morpheus-docs that could affect compatibility.
- Opt-in: `LOAD_JARS=<jar>` with `--tests '*PluginLoadSpec*'` loads a jar through the plugin API's own PluginManager. Use a build with a renamed package, because the loader is parent-first.
- The "How it works" infographic is generated: edit `tools/draw-how-it-works.py` and run `python3 tools/draw-how-it-works.py`.

## Where things are

- `core/Tier1Catalog.groovy` and `core/Tier2Catalog.groovy`: every check's title, evidence, "If you leave it", "If you fix it" and references. Start here to improve a check's wording.
- `core/Tier1Rules.groovy` and `core/Tier2Rules.groovy`: how each check decides pass, fail or unknown, from plain data (no Morpheus types).
- `core/TuneUpPlan.groovy`: the per-VM plan (windows, prerequisites, score after the plan).
- `core/GuestScripts.groovy`: the two fixed guest scripts, and the lists of every command they run (`WINDOWS_COMMANDS`, `LINUX_COMMANDS`); `core/GuestFacts.groovy` parses their output.
- `core/hostcpu/`: parsing `virsh` output and reconciling the cluster CPU setting with each host; `HostCpuCheck.groovy` runs the commands.
- `GuestScan.groovy`, `OsFill.groovy`: the guest scan and the one write.
- `ServerFacts.groovy`: turns a Morpheus server into those maps.
- `ScanService.groovy`, `Workers.groovy`: scans, budgets, caching and thread pools.
- `VmTuneUpController.groovy`: the routes and their permission, tenant and request checks.

## Guidelines

- **Read-only, except one opt-in action.** The plugin reports and explains; it never changes a VM's hardware, a host or a cluster. Its only write fills empty guest OS fields, behind its own permission, after a confirm. Fixes are planned by people and done in Morpheus or inside the guest. Any new write, to Morpheus or a VM, needs a design discussion in an issue first.
- **No credentials.** Never add a setting or prompt for a password, key or token. Commands go through channels Morpheus already has (guest execution, its connection to the hosts).
- **Every check describes its impact.** A check needs "If you leave it" and "If you fix it" (downtime, risk, whether it can be undone, prerequisites in order). The `Check` model refuses a definition without them.
- **Recommendation or Consideration.** A Recommendation must be doable in the Morpheus UI or inside the guest OS. Anything that needs host-level work (editing libvirt or host files, for example) is a Consideration and is never recommended.
- **Cite HPE documentation first** ([docs.morpheusdata.com](https://docs.morpheusdata.com/en/9.1/), or its source on GitHub, [HewlettPackard/morpheus-docs](https://github.com/HewlettPackard/morpheus-docs), which is easier to search), paraphrased, never copied. Say what isn't verified yet.
- **Unknown is not a pass.** If the data isn't there, the check is unknown, with the reason.
- **Tabs never block a page load.** No service calls in a tab's `show()` or `renderTemplate()`; `NonBlockingSpec` checks this.
- **Guest and host commands** are fixed constants. Never put user-supplied text, or values from Morpheus data, into a command. A change to a guest script must keep it read-only, update its command list (`GuestScriptsSpec` checks it), and say what it reads in the README.
- **Tests:** add or update Spock specs for behavior changes.
- **No real data in issues, PRs, fixtures or screenshots.** Replace VM, host, cluster and tenant names, IP addresses, MAC address suffixes, UUIDs, ids and tokens with example values (`app-legacy-01`, `hvm-host-01`, `cluster-a`, `192.0.2.x`, `00:0c:29:00:00:01`, `example.com`). `FixtureHygieneSpec` fails if a real-looking address, UUID or MAC comes back. On screenshots, paint the example value over the real one; never blur.
- **Licenses:** don't copy code or text from sources whose license doesn't allow it. The Morpheus plugin API is compile-only and must never be bundled into the jar; don't decompile it. Cite new sources in [NOTICE.md](NOTICE.md).
- **US English** in docs and UI text.
- Update [CHANGELOG.md](CHANGELOG.md) and, for user-visible changes, the README or [docs/INSTALL.md](docs/INSTALL.md).

## License of contributions

By contributing, you agree that your contribution is licensed under the project's [MIT license](LICENSE).
