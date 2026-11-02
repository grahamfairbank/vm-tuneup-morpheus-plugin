package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic

import static io.github.grahamfairbank.migrationhygiene.core.Refs.*

/**
 * The Tier 1 checks: what each one reads, why it matters, and what fixing it costs. Facts and field values come
 * from docs/verify-findings.md; wording rules from docs/best-practices.md (Recommendation only for what can be
 * done in the Morpheus UI or inside the guest OS; never hand-edited host or libvirt files).
 */
@CompileStatic
final class Tier1Catalog {

	private Tier1Catalog() {}

	static final String BACKUP_FIRST = 'Take a backup or a Morpheus snapshot of the VM first, and delete that snapshot once the VM runs well (see T1-SNAPSHOTS).'

	/** Ordering rule for Windows: the VirtIO storage driver must be installed and boot-start before any bus switch. */
	static final String WINDOWS_BUS_RULE = 'Windows, before any disk bus switch: install the VirtIO storage driver (viostor for VirtIO Block, ' +
		'vioscsi for VirtIO SCSI, from the virtio-win guest tools) AND make sure it is a boot-start driver. Installing the tools on a VM ' +
		'that has never seen VirtIO hardware is not enough: the driver stays inactive and Windows fails to boot (INACCESSIBLE_BOOT_DEVICE ' +
		'or 0xc0000225). Check in the guest that HKLM\\SYSTEM\\CurrentControlSet\\Services\\viostor (or vioscsi) has Start = 0. A widely ' +
		'used way to get there: add a small temporary disk on a VirtIO controller with Reconfigure, boot Windows once so it binds the ' +
		'driver, then switch the boot disk and remove the temporary disk (not yet verified by this project on HVM).'

	/** Ordering rule for Linux: VirtIO in the initramfs (or built into the kernel) and fstab by UUID before any bus switch. */
	static final String LINUX_BUS_RULE = 'Linux, before any disk bus switch: make sure the VirtIO drivers (virtio_blk, plus virtio_scsi for ' +
		'VirtIO SCSI) are in the initramfs or built into the kernel (RHEL family: dracut -f --add-drivers "virtio_blk virtio_scsi"; ' +
		'Debian/Ubuntu: add them to /etc/initramfs-tools/modules and run update-initramfs -u), and that /etc/fstab and the boot loader ' +
		'refer to disks by UUID or label, not by device name: /dev/sda or /dev/hda becomes /dev/vda. Without this the VM stops in the ' +
		'initramfs waiting for its root disk.'

	static final String SWITCH_LAST = 'Switch the bus last, then check that the guest boots, sees every disk and mounts them.'

	static final Check DISK_BUS = new Check(
		id: 'T1-DISK-BUS', title: 'Disk on an emulated SATA or IDE bus', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: 'Each disk\'s bus (volume diskMode) and controller type (kvm-virtio-blk, kvm-virtio-scsi, kvm-sata, kvm-ide). CD-ROM drives ' +
			'are ignored: Morpheus puts them on SATA by design. A boot disk on IDE is critical; any other disk on SATA or IDE is a warning.',
		ifYouLeaveIt: 'SATA and IDE are emulated in software, so disk I/O is slower and uses more host CPU than VirtIO, which is the HVM ' +
			'standard. IDE also limits how many disks the VM can have. A boot disk on IDE usually means the VM was imported outside Morpheus\'s ' +
			'migration tools or runs on the Legacy chipset.',
		ifYouFixIt: [summary: 'Move the disks to a VirtIO controller (VirtIO Block, or VirtIO SCSI for many or shared disks). Prepare the ' +
			'guest first: switching the bus before the guest can load the VirtIO driver at boot makes the VM fail to boot.',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.MEDIUM, reversible: true,
			prerequisites: [BACKUP_FIRST, WINDOWS_BUS_RULE, LINUX_BUS_RULE,
				'A discovered VM must be converted to managed first (see T1-DISCOVERED), so Morpheus can reconfigure it.', SWITCH_LAST]],
		category: Category.RECOMMENDATION,
		howInVme: 'Instance > Actions > Reconfigure: change the disk\'s storage controller to VirtIO. If the VM also runs on the Legacy ' +
			'chipset, set Actions > Reconfigure > Advanced Options > Hardware Compatibility to Modern instead (see T1-CHIPSET).',
		unverified: ['That Reconfigure changes the controller of an existing boot disk in place on HVM (HPE documents storage controller ' +
			'changes in Reconfigure in general).'],
		references: [H11_MANAGING_INSTANCES, H1_ADVANCED_OPTIONS, H2_GUEST_OS_NOTES, H8_WINDOWS_MIGRATION])

	static final Check NIC_MODEL = new Check(
		id: 'T1-NIC-MODEL', title: 'Network adapter is not VirtIO', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: 'Each network interface\'s type (interfaces[].type.code). Morpheus reports E1000 as e1000-kvm, and shows E1000E (and ' +
			'other models it doesn\'t map) with no type at all, which counts as "not VirtIO, model unknown".',
		ifYouLeaveIt: 'Emulated adapters (E1000, E1000E, RTL8139) are slower and use more host CPU than VirtIO. HPE\'s network tuning ' +
			'guidance for HVM assumes the VirtIO network driver.',
		ifYouFixIt: [summary: 'Replace the adapter with a VirtIO one. The guest sees a new network adapter, so its IP settings must move to it.',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.MEDIUM, reversible: true,
			prerequisites: [
				'Record the guest\'s IP settings (static address, DNS, gateway, VLAN) and any DHCP reservation or license tied to the MAC address.',
				'Windows: install the VirtIO network driver (netkvm, part of the virtio-win guest tools) before the change. Afterward, set ' +
					'the static IP on the new adapter; the old adapter keeps its settings on a hidden device.',
				'Linux: the interface name changes (for example ens34 to enp1s0). Make sure the network configuration (netplan, ' +
					'NetworkManager, ifcfg) matches the new adapter or falls back to DHCP, or the VM loses its network. Current kernels ' +
					'include the VirtIO network driver.',
				'Keep the Morpheus console at hand in case the network doesn\'t come up.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Instance > Actions > Reconfigure > Networks: add an interface of type VirtIO, then remove the old interface once the ' +
			'guest uses the new one.',
		unverified: ['Whether Reconfigure can change the type of an existing interface in place on HVM.'],
		references: [H11_MANAGING_INSTANCES, H2_GUEST_OS_NOTES])

	static final Check CHIPSET = new Check(
		id: 'T1-CHIPSET', title: 'Legacy (i440fx) chipset', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: 'The machine type (config.machineType): q35 passes, anything else (pc, i440fx) fails. Morpheus 9.0.x doesn\'t report ' +
			'it and discovered VMs have no value, so those show as unknown.',
		ifYouLeaveIt: 'The Legacy chipset is meant for guests that can\'t load VirtIO drivers. It uses the old PCI bus with emulated IDE ' +
			'disks and E1000 adapters, so the VM misses PCIe and the VirtIO devices HVM is built around.',
		ifYouFixIt: [summary: 'Set Hardware Compatibility to Modern (q35). This also changes the disk and network device models to VirtIO, ' +
			'so it needs the same guest preparation as a disk bus switch and a network adapter change.',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.HIGH, reversible: true,
			prerequisites: [BACKUP_FIRST, WINDOWS_BUS_RULE, LINUX_BUS_RULE,
				'Plan the network change as for T1-NIC-MODEL: the guest sees a new adapter.', SWITCH_LAST]],
		category: Category.RECOMMENDATION,
		howInVme: 'Actions > Reconfigure > Advanced Options > Hardware Compatibility: Modern.',
		references: [H1_ADVANCED_OPTIONS, H2_GUEST_OS_NOTES])

	static final Check VMWARE_MAC = new Check(
		id: 'T1-VMWARE-MAC', title: 'VMware MAC address kept', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'Each interface\'s MAC address. The prefixes 00:50:56, 00:0c:29, 00:05:69 and 00:1c:14 are registered to VMware, so ' +
			'the VM almost certainly came from VMware. Marks the VM as "VMware origin".',
		ifYouLeaveIt: 'Usually harmless: the VM keeps its old MAC on HVM, so DHCP reservations and MAC-bound licenses keep working. The ' +
			'risk is a duplicate: if the VMware source VM is powered on again on the same network, two machines answer with the same MAC ' +
			'and IP address.',
		ifYouFixIt: [summary: 'Usually leave the MAC as it is, and make sure the VMware source VM stays powered off or is deleted. Change ' +
			'the MAC only if your network policy requires it.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Confirm the HVM copy is in service and backed up before you delete the VMware source VM; powering it off can be undone, deleting it can\'t.']],
		category: Category.CONSIDERATION,
		whyConsideration: 'There\'s nothing to fix on the HVM side. Morpheus has no documented action to set a new MAC on an existing ' +
			'interface: that would mean re-creating the interface (see T1-NIC-MODEL) and updating everything tied to the old address.',
		references: [H8_MIGRATIONS, IEEE_OUI])

	static final Check OS_TYPE_MISMATCH = new Check(
		id: 'T1-OS-TYPE-MISMATCH', title: 'OS type fields incomplete after migration', tier: Tier.ONE, severity: Severity.INFO,
		appliesTo: AppliesTo.ANY,
		evidence: 'serverOs, osType, platform and the server type side by side. Native VMware-to-HVM migration carries serverOs over but ' +
			'leaves osType empty; for Windows it also leaves platform empty and the server type as morpheusVm instead of morpheusWindowsVm. ' +
			'Info for Linux, warning for Windows. Also marks the VM as "VMware origin".',
		ifYouLeaveIt: 'Morpheus decides some behavior from the server type and platform, such as how the Morpheus agent is installed, ' +
			'guest customization and which actions it offers. A Windows VM typed like a Linux one may get Linux handling.',
		ifYouFixIt: [summary: 'Correct the OS type of the VM in Morpheus so its type and platform match the guest.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Confirm the guest OS name and version from inside the VM first.']],
		category: Category.CONSIDERATION,
		whyConsideration: 'The Morpheus UI path to correct these fields on a migrated VM isn\'t verified yet (a candidate is Edit on the VM). ' +
			'Until it is, raise it with HPE support rather than changing records by other means.',
		unverified: ['Which Morpheus behavior the incomplete fields change.', 'The Morpheus UI path to correct them.'],
		references: [H8_MIGRATIONS, H10_OPENAPI])

	static final Check FIRMWARE = new Check(
		id: 'T1-FIRMWARE', title: 'Firmware or TPM doesn\'t fit the OS', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'UEFI, Secure Boot and TPM on the VM, compared with the guest OS from serverOs: Windows 11 and Windows Server 2025 ' +
			'without UEFI and TPM, or a TPM without UEFI.',
		ifYouLeaveIt: 'Windows 11 requires UEFI with Secure Boot capability and TPM 2.0, so updates and upgrades can fail without them. ' +
			'Windows Server 2025 needs them for some security features. A TPM without UEFI is inconsistent: TPM-based features such as ' +
			'BitLocker with measured boot expect UEFI.',
		ifYouFixIt: [summary: 'Enable UEFI, plus Secure Boot and TPM where the OS needs them. Switching an installed guest from BIOS to ' +
			'UEFI needs its boot disk converted first; adding a TPM to a VM that already has UEFI needs no guest change.',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.HIGH, reversible: false,
			prerequisites: [BACKUP_FIRST,
				'Windows on BIOS: convert the boot disk from MBR to GPT inside the guest with Microsoft\'s MBR2GPT tool and confirm it succeeded.',
				'Linux on BIOS: install a UEFI boot loader (EFI system partition and grub-efi or systemd-boot) before switching.',
				'Enable UEFI and Secure Boot in the same reconfigure when you need both; Secure Boot without UEFI is rejected.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Actions > Reconfigure > Advanced Options > UEFI, Secure Boot and TPM.',
		references: [H1_ADVANCED_OPTIONS, H2_GUEST_OS_NOTES, M_WIN11_REQUIREMENTS, M1_SERVER_REQUIREMENTS, M_MBR2GPT])

	static final Check GA_CHANNEL = new Check(
		id: 'T1-GA-CHANNEL', title: 'QEMU guest agent channel disabled', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: 'config.disableQgaChannel. Morpheus 9.0.x doesn\'t report it and discovered VMs have no value, so those show as unknown.',
		ifYouLeaveIt: 'Without the guest agent channel, snapshots can\'t freeze the file systems (they are crash-consistent only), ' +
			'Windows guest customization (Sysprep) is skipped, and Morpheus loses live IP reporting and the path to the guest that works ' +
			'without network.',
		ifYouFixIt: [summary: 'Re-enable the QEMU guest agent channel on the VM, then make sure the agent runs in the guest (see T1-GA-STATUS).',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.LOW, reversible: true, prerequisites: []],
		category: Category.CONSIDERATION,
		whyConsideration: 'HPE\'s Advanced Options documentation lists no control for the guest agent channel on an existing VM, so ' +
			'there\'s no verified Morpheus action yet. Ask HPE support how to re-enable it.',
		unverified: ['Whether the setting can be changed with Reconfigure, and whether that needs a cold reconfigure.'],
		references: [H2_GUEST_OS_NOTES, H3_SNAPSHOTS, H10_OPENAPI])

	static final Check GA_STATUS = new Check(
		id: 'T1-GA-STATUS', title: 'QEMU guest agent not connected', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: 'guestAgentStatus on a powered-on VM: "connected" passes; disconnected, error, unconfigured (or any other state) ' +
			'fail. Powered-off VMs are not judged, because "configured" only means the channel exists while the VM is off. ' +
			'When Morpheus doesn\'t report the status (unknown; Morpheus 9.1.0.1 never fills it in for plugins), the plugin ' +
			'probes the agent: it runs a harmless no-op command through the QEMU guest agent (Linux "true", Windows ' +
			'"cmd /c exit 0") with a 20-second timeout. An answer passes; no answer fails. The probe runs only for users with ' +
			'the "Migration Hygiene: Run guest scans" permission; otherwise the check is unknown.',
		ifYouLeaveIt: 'Snapshots are crash-consistent only (no file system freeze, no VSS on Windows), Windows guest customization ' +
			'(Sysprep) is skipped, Morpheus may not learn the VM\'s IP address, and it has no path to the guest when the network is down.',
		ifYouFixIt: [summary: 'Install and start the QEMU guest agent inside the guest. (To check it, the plugin only runs a ' +
			'harmless no-op command through the agent, and only for users with the guest-scan permission.)',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: [
				'Windows: run virtio-win-guest-tools from the VirtIO driver ISO; it installs the QEMU Guest Agent service and the VirtIO serial driver it needs.',
				'Linux: install the qemu-guest-agent package (apt or dnf), then run systemctl enable --now qemu-guest-agent.',
				'If the agent runs but Morpheus still shows it as not connected, check the guest agent channel (T1-GA-CHANNEL).']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS; no Morpheus setting is needed. Morpheus updates the status on its next poll; the plugin keeps ' +
			'a probe result for 10 minutes.',
		references: [H2_GUEST_OS_NOTES, H3_SNAPSHOTS])

	static final Check MORPHEUS_AGENT = new Check(
		id: 'T1-MORPHEUS-AGENT', title: 'Morpheus agent missing or out of date', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'agentInstalled, agentVersion and lastAgentUpdate. The version is compared with the newest agent seen in this fleet ' +
			'for the same OS family (Windows and Linux agents have separate version lines). An installed agent that hasn\'t reported for ' +
			'more than 7 days on a running VM is a warning (plugin heuristic).',
		ifYouLeaveIt: 'Without the Morpheus agent, Morpheus collects fewer guest statistics and logs, and guest actions need another path ' +
			'(SSH, WinRM or the QEMU guest agent). The agent is optional on HVM. An older agent may miss fixes.',
		ifYouFixIt: [summary: 'Install the Morpheus agent, or upgrade it to the version the rest of the fleet runs.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: [
				'Make sure security software allows the agent package and its connection to the Morpheus manager.',
				'If the VM was provisioned without the agent on purpose, you can leave it.']],
		category: Category.RECOMMENDATION,
		howInVme: 'VM or Instance > Actions > Install Agent; for an old agent, Actions > Upgrade Agent. Actions > Download Agent Script ' +
			'is the manual alternative.',
		unverified: ['The exact name of the Install Agent action on HVM VMs.'],
		references: [AGENT_OVERVIEW, AGENT_INSTALL])

	static final int SNAPSHOT_INFO_DAYS = 30
	static final int SNAPSHOT_WARN_DAYS = 90
	static final int SNAPSHOT_INFO_COUNT = 3

	static final Check SNAPSHOTS = new Check(
		id: 'T1-SNAPSHOTS', title: 'Old or many snapshots', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: "Number of snapshots and the age of the oldest. The thresholds are plugin heuristics, not HPE guidance: more than " +
			"${SNAPSHOT_INFO_COUNT} snapshots or older than ${SNAPSHOT_INFO_DAYS} days is info; older than ${SNAPSHOT_WARN_DAYS} days is a warning.",
		ifYouLeaveIt: 'HVM file-based snapshots are qcow2 overlays: the chain grows, and deleting an old snapshot later is a heavy merge. ' +
			'A VM with snapshots can\'t be storage-migrated. Snapshots taken before a migration are rarely still useful.',
		ifYouFixIt: [summary: 'Delete the snapshots nobody needs.',
			downtime: Downtime.NONE, risk: Risk.MEDIUM, reversible: false,
			prerequisites: ['Confirm nobody needs the restore point.',
				'Check that no linked clones depend on it (Morpheus blocks the delete if they do).',
				'Delete during a quiet period: the merge is I/O heavy.']],
		category: Category.RECOMMENDATION,
		howInVme: 'VM or Instance > Snapshots tab > Delete.',
		references: [H3_SNAPSHOTS, H4_VM_MIGRATION, H9_RELEASE_9_1])

	static final double ALLOCATION_RATIO = 0.95d

	static final Check DISK_ALLOCATION = new Check(
		id: 'T1-DISK-ALLOCATION', title: 'Disk possibly fully allocated (thick)', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'Each disk\'s used size against its provisioned size (usedStorage / maxStorage); 95% or more is flagged (plugin ' +
			'heuristic). A thin disk the guest has really filled looks the same, so this is only a hint until the guest\'s own usage is known.',
		ifYouLeaveIt: 'A disk that came over fully allocated (thick VMware disks, or a conversion with preallocation) takes its full size ' +
			'on the datastore even when the guest uses little of it. That wastes capacity and makes clones, snapshots, backups and moves slower.',
		ifYouFixIt: [summary: 'Reclaim unused space from inside the guest with TRIM, which hands free blocks back to the datastore while the VM runs.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Check the guest\'s own file system usage first: if the disk is really full, there\'s nothing to reclaim.',
				'Linux: run fstrim -av and enable fstrim.timer. Windows: run Optimize-Volume -DriveLetter C -ReTrim and keep the scheduled optimization task on.',
				'Discard must reach the datastore: managed HVM VMs have it on; a discovered VM may not, so convert it to managed first (T1-DISCOVERED).']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS.',
		unverified: ['Whether the datastore (GFS2, NFS or the array behind it) returns the space.',
			'Offline re-sparsifying of the disk file is host-side work and out of scope here.'],
		references: [H8_MIGRATIONS, U1_LIBVIRT_DOMAIN])

	/** HVM defaults a managed VM gets and a discovered one lacks (lab domain XML on 9.1.0.1, verify-findings). */
	static final List<String> MANAGED_DEFAULTS = Collections.unmodifiableList([
		'Q35 machine type at the current version', 'disk cache none with io_uring', 'discard (unmap)', 'a dedicated IOThread',
		'Hyper-V enlightenments', 'kvmclock with RTC catch-up', 'memory balloon', 'VirtIO RNG', 'serial console',
		'QEMU guest agent channel'])

	static final Check DISCOVERED = new Check(
		id: 'T1-DISCOVERED', title: 'VM not managed by Morpheus', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'The server type: kvmUnmanaged (or another unmanaged type) means Morpheus found the VM on a host but doesn\'t manage it.',
		ifYouLeaveIt: 'A discovered VM has no instance, fewer Morpheus actions, and none of the defaults Morpheus gives the VMs it manages: ' +
			MANAGED_DEFAULTS.join(', ') + '. Its chipset and guest agent channel show as unknown.',
		ifYouFixIt: [summary: 'Convert the VM to managed.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Have a backup of the VM before converting.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Infrastructure > Compute > Virtual Machines > the VM > Actions > Convert To Managed.',
		unverified: ['Whether Convert To Managed applies the HVM defaults to an existing VM, or only creates the Morpheus records.',
			'Whether the conversion itself needs downtime.'],
		references: [H13_COMPUTE, H13_UNMANAGE])

	static final Check ISO_ATTACHED = new Check(
		id: 'T1-ISO-ATTACHED', title: 'ISO still attached', tier: Tier.ONE, severity: Severity.INFO, appliesTo: AppliesTo.ANY,
		evidence: 'Each CD-ROM drive: an ISO is attached when the drive has a source image, or a volume path with a size above 0. An ' +
			'empty or ejected drive has neither.',
		ifYouLeaveIt: 'The VM keeps referencing the ISO: it can block datastore cleanup or moves, may hold installer or VMware Tools ' +
			'media, and the boot order may still include it.',
		ifYouFixIt: [summary: 'Eject the ISO.', downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Check that nothing in the guest is still installing from the ISO.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Instance > Actions > Eject Disk (API: PUT /api/instances/{id}/eject). A discovered VM has no instance: convert it to ' +
			'managed first (T1-DISCOVERED).',
		references: [H11_MANAGING_INSTANCES, H10_OPENAPI])

	static final Check SOURCE_TWIN = new Check(
		id: 'T1-SOURCE-TWIN', title: 'VMware source VM still running', tier: Tier.ONE, severity: Severity.CRITICAL, appliesTo: AppliesTo.ANY,
		evidence: 'VMs in a VMware cloud registered in Morpheus with the same name or MAC address (IP address as a weak signal), and ' +
			'their power state.',
		ifYouLeaveIt: 'If both the VMware source and the HVM copy are powered on, the network has two machines with one identity: ' +
			'address conflicts, writes split between two copies of an application or database, and duplicate domain members.',
		ifYouFixIt: [summary: 'Power off the VMware source VM, and delete it once the HVM copy is confirmed.',
			downtime: Downtime.HOST_CHANGE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Confirm which copy is in service before you power anything off.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Infrastructure > Compute > Virtual Machines > the VMware VM > Actions > Stop (or stop it in vCenter).',
		references: [H8_MIGRATIONS])

	/** All Tier 1 checks, in fix-plan order (what to do first comes first). */
	static final List<Check> ALL = Collections.unmodifiableList([
		SOURCE_TWIN, DISCOVERED, SNAPSHOTS, ISO_ATTACHED, GA_STATUS, GA_CHANNEL, MORPHEUS_AGENT, OS_TYPE_MISMATCH,
		DISK_BUS, NIC_MODEL, CHIPSET, FIRMWARE, VMWARE_MAC, DISK_ALLOCATION])

	/** Checks defined but not run yet, pending verification. */
	static final Set<String> NOT_EVALUATED = Collections.unmodifiableSet(['T1-SOURCE-TWIN'] as Set<String>)
}
