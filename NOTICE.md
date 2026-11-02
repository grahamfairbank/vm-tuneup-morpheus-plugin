# NOTICE: sources, attributions, disclaimer and trademarks

## Unofficial project

vm-tuneup-morpheus-plugin is an **independent, unofficial community project**. It is **not** a product of Hewlett Packard Enterprise (HPE) or Morpheus Data, LLC. It is not developed, reviewed, endorsed, sponsored, certified or supported by HPE or Morpheus Data.

The software is provided "as is", without warranty of any kind (see [LICENSE](LICENSE)). Use it at your own risk. Do not contact HPE support about it.

The plugin only reads. It never changes a VM, host, cluster or anything else in Morpheus. The one thing it can run inside a VM is an optional, fixed no-op command to test the guest agent, for users granted a separate permission. What it reports (for example "If you fix it" steps) is information, not advice: plan and test every change yourself, take a backup first, and follow HPE's documentation and support guidance for your environment.

## Sources and how they were used

This plugin was written from scratch. **No source code, documentation text or images from the third-party sources below are copied into this project.** It interoperates with the products they describe, and they were used only as references, within their published terms:

| Source | Owner / license | How it was used |
|---|---|---|
| [grahamfairbank/com-morpheus-plugin](https://github.com/grahamfairbank/com-morpheus-plugin) | Same author; MIT license | The sibling project. Its build and test tooling (`tools/build.sh`, `tools/check-plugin-core.sh`, the jar load test), plugin layout, renderer and route patterns, request checks, the role script and the "How it works" infographic generator were reused and adapted. |
| [HewlettPackard/morpheus-plugin-core](https://github.com/HewlettPackard/morpheus-plugin-core) (`morpheus-plugin-api` 1.4.1 and 1.5.0 from Maven Central; tags `rel-1.4.1`, `rel-1.5.0`) | Morpheus Data, LLC, under the Morpheus Data Plugin Core Source License (PSL) | Used, as the PSL permits, **solely to develop and compile this plugin**. It is a compile-time (`provided`) dependency and is **not bundled in, or redistributed with,** the plugin jar. Its published documentation and published source were read to learn the plugin interfaces (tab and report providers, global UI components, controllers and routes, the renderer, compute servers, snapshots and guest execution). |
| [HPE Morpheus documentation](https://docs.morpheusdata.com/en/9.1/) and its source, [HewlettPackard/morpheus-docs](https://github.com/HewlettPackard/morpheus-docs) (branch `dev-9.1`) | HPE; no license file published | Read for product facts about HVM virtual machines (advanced options, guest OS notes, snapshots, migrations, Reconfigure, Eject Disk, Convert To Managed, the Morpheus agent) and plugin API versions. The checks cite the public pages by link and paraphrase them; no text copied. |
| [HewlettPackard/morpheus-openapi](https://github.com/HewlettPackard/morpheus-openapi) | HPE | Read for the field names of servers, instances and volumes. Cited by link. |
| [libvirt domain XML format](https://libvirt.org/formatdomain.html), [IEEE OUI registry](https://standards-oui.ieee.org/oui/oui.txt), Microsoft documentation ([Windows 11 requirements](https://learn.microsoft.com/en-us/windows/whats-new/windows-11-requirements), [Windows Server hardware requirements](https://learn.microsoft.com/en-us/windows-server/get-started/hardware-requirements), [MBR2GPT](https://learn.microsoft.com/en-us/windows/deployment/mbr-to-gpt)) | Their respective owners | Facts behind individual checks (disk discard, VMware MAC prefixes, firmware and TPM requirements). Cited by link from the checks; no text copied. |
| [builtbyfood/gfs2-rescue](https://github.com/builtbyfood/gfs2-rescue) and [builtbyfood/morpheus-vm-folders](https://github.com/builtbyfood/morpheus-vm-folders) | Community authors; no license published | Design ideas only, by way of com-morpheus-plugin: a plugin renderer of its own, and loading the plugin's script through a global UI component. No code copied. |

### Test data

The test fixtures in `src/test/resources/fixtures` follow the shape of Morpheus 9.0.1 and 9.1 API responses measured on the author's own lab, but every name, address, MAC address suffix, UUID and id in them is made up or replaced. A test (`FixtureHygieneSpec`) fails if real-looking addresses, UUIDs or MAC addresses come back.

### Screenshots

The screenshots in `docs/images` show the plugin on the author's own Morpheus appliance. Every cluster, host, VM and appliance name, address and MAC address is replaced with a consistent example value (for example `cluster-a`, `hvm-host-01`, `app-legacy-01`, `192.0.2.x`); see [docs/images/SCREENSHOT-SPEC.md](docs/images/SCREENSHOT-SPEC.md). The Morpheus user interface they show belongs to HPE.

### What the plugin jar contains

Only this project's own compiled classes, four page templates, one script and one icon. It contains no third-party code. The Morpheus plugin API, Groovy and other runtime libraries are supplied by the Morpheus appliance at run time.

## Trademarks

This project refers to the following names only to describe what it works with (nominative use). No affiliation or endorsement is implied.

- **HPE** and **Hewlett Packard Enterprise**, and the HPE logo
- **HPE Morpheus**, **Morpheus**, **HPE Morpheus VM Essentials**, **HPE Morpheus Advanced**, **HPE Morpheus Enterprise** and **HPE Virtual Machine** (HVM)

These are trademarks or registered trademarks of Hewlett Packard Enterprise Company, or of Morpheus Data, LLC (an HPE company), in the United States and other countries.

**VMware**, **VMware Tools**, **vSphere** and **vCenter** are trademarks of Broadcom Inc. and/or its subsidiaries. **Windows**, **Windows Server** and **Excel** are trademarks of the Microsoft group of companies. **Linux** is the registered trademark of Linus Torvalds in the U.S. and other countries. **Python** is a trademark of the Python Software Foundation. **macOS** is a trademark of Apple Inc. **GitHub** is a trademark of GitHub, Inc. Other product and company names are trademarks of their respective owners.

The plugin's icon is an original drawing and does not use any HPE or VMware logo.
