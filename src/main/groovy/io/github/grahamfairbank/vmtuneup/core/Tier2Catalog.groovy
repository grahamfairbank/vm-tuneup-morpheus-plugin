package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import static io.github.grahamfairbank.vmtuneup.core.Refs.*
import static io.github.grahamfairbank.vmtuneup.core.Tier2Refs.*

/**
 * The Tier 2 checks: read-only facts from inside the guest ({@link GuestScripts}, parsed by {@link GuestFacts}).
 * Same rules as Tier 1 (docs/best-practices.md): Recommendation only for what can be done in the Morpheus UI or
 * inside the guest OS; HPE references first; anything not confirmed goes in "unverified".
 */
@CompileStatic
final class Tier2Catalog {

	private Tier2Catalog() {}

	static final String SCAN_EVIDENCE = 'Read by the VM Tune-Up guest scan: one fixed, read-only script run once inside the guest ' +
		'(Windows through the QEMU guest agent, Linux through the Morpheus agent). '

	static final Check VIRTIO_READY = new Check(
		id: 'T2-VIRTIO-READY', title: 'Guest not ready for the switch to VirtIO', tier: Tier.TWO, severity: Severity.WARNING,
		appliesTo: AppliesTo.ANY,
		evidence: SCAN_EVIDENCE + 'Windows: whether the VirtIO storage driver for the target controller (viostor for VirtIO Block, ' +
			'vioscsi for VirtIO SCSI) is installed as a boot-start service (Start = 0) or only staged in the driver store, and ' +
			'whether netkvm is installed when the network adapter will change. Linux: whether virtio_blk (or virtio_scsi) and ' +
			'virtio_pci are built into the running kernel or in its initramfs, and whether /etc/fstab or the kernel root= setting ' +
			'name disks by device or bus path (/dev/sda, /dev/hda, by-path). Raised when Morpheus shows a disk still on SATA or IDE.',
		ifYouLeaveIt: 'Nothing changes while the disks stay on SATA or IDE. But switching the bus before the guest is ready makes the VM ' +
			'fail to boot: Windows stops with INACCESSIBLE_BOOT_DEVICE or 0xc0000225, Linux waits forever in the initramfs for its ' +
			'root disk, or mounts fail because /dev/sda became /dev/vda.',
		ifYouFixIt: [summary: 'Prepare the guest, then switch the bus (see T1-DISK-BUS). Linux preparation needs no restart; the ' +
			'Windows preparation with a temporary VirtIO disk needs a reconfigure and a boot.',
			downtime: Downtime.COLD_RECONFIGURE, risk: Risk.MEDIUM, reversible: true,
			prerequisites: [Tier1Catalog.BACKUP_FIRST, Tier1Catalog.WINDOWS_BUS_RULE, Tier1Catalog.LINUX_BUS_RULE,
				'Run the guest scan again: this check passes once the guest is ready.', Tier1Catalog.SWITCH_LAST]],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS: Windows, install the virtio-win guest tools and make the storage driver boot-start; Linux, ' +
			'rebuild the initramfs with the VirtIO drivers and mount by UUID. Then Instance > Actions > Reconfigure to change the controller.',
		unverified: ['That initramfs-tools MODULES=most includes virtio_blk and virtio_pci on Debian kernels that build them as modules ' +
			'(Ubuntu kernels build them in, which the scan sees directly).',
			'On Debian and Ubuntu the scan reads the initramfs configuration, not the image: an image not rebuilt since the configuration changed is not detected.'],
		references: [H8_WINDOWS_MIGRATION, H2_GUEST_OS_NOTES, H11_MANAGING_INSTANCES, M_ADDSERVICE, U_DRACUT_CONF, U_INITRAMFS_CONF, U_FSTAB])

	static final Check VMWARE_TOOLS = new Check(
		id: 'T2-VMWARE-TOOLS', title: 'VMware Tools still installed', tier: Tier.TWO, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: SCAN_EVIDENCE + 'Windows: the VMware Tools entry in Programs and Features, the VMTools service (start type and ' +
			'state), VMware drivers still registered (pvscsi, vmxnet3ndis6, vm3dmp, vmhgfs, vmmouse, vmusbmouse, vmci, vmmemctl) and ' +
			'VMware PCI devices Windows still remembers. Linux: the open-vm-tools package, a running vmtoolsd, a VMware Tools tarball ' +
			'install and loaded VMware kernel modules. Warning when the VMware Tools service still starts; info for leftovers that stay idle.',
		ifYouLeaveIt: 'HPE notes that VMware Tools stays installed after migration but doesn\'t work on HVM, and that its core service ' +
			'causes startup errors. Leftover VMware drivers and hidden adapters clutter the device list and can keep old network ' +
			'settings on a hidden VMware adapter. On Linux, open-vm-tools normally stays idle off VMware.',
		ifYouFixIt: [summary: 'Disable the VMware Tools core service now; uninstall VMware Tools once the VM is staying on HVM.',
			downtime: Downtime.GUEST_REBOOT, risk: Risk.LOW, reversible: true,
			prerequisites: ['Make sure you won\'t move the VM back to VMware: it would need VMware Tools again.',
				'Windows: in the guest, Settings > Apps > Startup, turn off VMware Tools Core Service. To remove VMware Tools, use ' +
					'Programs and Features; if its installer refuses to run off VMware, use Microsoft\'s program removal troubleshooter, as HPE suggests.',
				'Linux: remove the package (dnf remove open-vm-tools, or apt remove open-vm-tools).']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS: disable or uninstall VMware Tools (Windows), remove open-vm-tools (Linux).',
		unverified: ['That open-vm-tools never starts on HVM: its service is limited to VMware (observed on a lab Rocky Linux 9 VM, ' +
			'where vmtoolsd was skipped with "ConditionVirtualization=vmware was not met").'],
		references: [H8_WINDOWS_MIGRATION, H8_MIGRATIONS, M_PROGRAM_REMOVAL, U_OPEN_VM_TOOLS])

	static final Check GA_FEATURES = new Check(
		id: 'T2-GA-FEATURES', title: 'Guest agent can\'t freeze the file systems', tier: Tier.TWO, severity: Severity.WARNING,
		appliesTo: AppliesTo.ANY,
		evidence: SCAN_EVIDENCE + 'Windows: the QEMU-GA service and its state, the QEMU Guest Agent VSS Provider (service and ' +
			'registered VSS provider) and commands blocked in the service\'s command line. Linux: the qemu-guest-agent package, ' +
			'whether qemu-ga runs, the guest agent channel device, and whether guest-fsfreeze-freeze is blocked by an allow or block ' +
			'list in its configuration or command line.',
		ifYouLeaveIt: 'HVM snapshots are application-consistent only when the guest agent freezes the file systems first; on Windows ' +
			'that freeze goes through VSS so applications such as SQL Server and Active Directory flush their data. Without it, ' +
			'snapshots (and backups taken from them) are crash-consistent, like a power loss. Guest customization and live IP ' +
			'reporting also need the agent.',
		ifYouFixIt: [summary: 'Install or repair the QEMU guest agent inside the guest and make sure freeze isn\'t blocked.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Windows: run the virtio-win guest tools installer (it installs the QEMU guest agent and its VSS provider).',
				'Linux: install qemu-guest-agent (dnf or apt) and run systemctl enable --now qemu-guest-agent. If the configuration has an ' +
					'allow list (--allow-rpcs, FILTER_RPC_ARGS) make sure it includes guest-fsfreeze-freeze, guest-fsfreeze-thaw and ' +
					'guest-fsfreeze-status; remove them from any block list.',
				'The VM needs its guest agent channel (see T1-GA-CHANNEL).']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS: install the guest agent (virtio-win guest tools on Windows, qemu-guest-agent on Linux).',
		unverified: ['That the RHEL default allow list keeps the fsfreeze commands (the scan reads the actual list, so a changed list is caught).'],
		references: [H3_SNAPSHOTS, H2_GUEST_OS_NOTES, U_QEMU_GA])

	static final Check TIME_SYNC = new Check(
		id: 'T2-TIME-SYNC', title: 'Guest time not synchronized', tier: Tier.TWO, severity: Severity.WARNING, appliesTo: AppliesTo.ANY,
		evidence: SCAN_EVIDENCE + 'Windows: the Windows Time service state, its source (w32tm /query /source) and its Type setting; ' +
			'"Local CMOS Clock", "Free-running System Clock" or Type NoSync fail. Linux: the clock source (kvm-clock expected on ' +
			'HVM), a running NTP client (chronyd, ntpd, systemd-timesyncd) and whether timedatectl reports the clock synchronized.',
		ifYouLeaveIt: 'Guest clocks drift, most after live migration, pauses and snapshot restores. Kerberos rejects tickets once a ' +
			'clock is more than 5 minutes off by default, so domain sign-ins and Windows authentication start to fail; logs and ' +
			'certificates are affected too. HPE still recommends guest NTP or the Windows Time service for long-running workloads.',
		ifYouFixIt: [summary: 'Turn on time synchronization inside the guest. After the 9.1 upgrade, restart migrated VMs once from ' +
			'Morpheus so they pick up HVM\'s clock settings.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Windows domain members: leave the time type at NT5DS (domain hierarchy); configure an external NTP source ' +
				'only on the domain controller holding the PDC emulator role.',
				'Windows standalone: set an NTP source with w32tm /config /manualpeerlist:<server> /syncfromflags:manual /update.',
				'Linux: install and enable chrony (or systemd-timesyncd) with a reachable NTP server.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS: Windows Time service or chrony; then a restart from Morpheus (Instance > Actions > Restart) for migrated VMs.',
		unverified: ['Whether a Linux guest that picked "tsc" over kvm-clock on a host with a stable TSC keeps good time across live ' +
			'migration (reported as info, not as a warning).'],
		references: [H2_GUEST_OS_NOTES, H9_RELEASE_9_1, M_ACCURATE_TIME, M_KERBEROS_SKEW, U_KVM_TIMEKEEPING, U_CHRONY])

	static final Check VIRTIO_VERSION = new Check(
		id: 'T2-VIRTIO-VERSION', title: 'VirtIO driver version', tier: Tier.TWO, severity: Severity.INFO, appliesTo: AppliesTo.WINDOWS,
		evidence: SCAN_EVIDENCE + 'The virtio-win guest tools version (Programs and Features) and the file versions of viostor.sys, ' +
			'vioscsi.sys and netkvm.sys; virtio-win numbers its drivers with the release in the last part (100.103.104.30200 is ' +
			'0.1.302). Reported for every Windows VM; flagged only when it is older than the newest release seen in the cluster.',
		ifYouLeaveIt: 'Older drivers miss fixes from later virtio-win releases, and VMs on different releases behave differently. HPE ' +
			'asks to confirm the VirtIO network driver is current before network tuning.',
		ifYouFixIt: [summary: 'Update the drivers with the virtio-win guest tools installer from a current VirtIO ISO.',
			downtime: Downtime.GUEST_REBOOT, risk: Risk.MEDIUM, reversible: true,
			prerequisites: [Tier1Catalog.BACKUP_FIRST,
				'On Windows Server with Secure Boot, use drivers Windows accepts: the community virtio-win builds are not WHQL-signed.']],
		category: Category.RECOMMENDATION,
		howInVme: 'Inside the guest OS: run the virtio-win guest tools installer (attach the VirtIO ISO with Actions > Reconfigure if needed).',
		unverified: ['Which virtio-win release HVM 9.0 and 9.1 ship, so there is no fixed "too old" version; only the cluster\'s newest is used.'],
		references: [H2_GUEST_OS_NOTES, H8_WINDOWS_MIGRATION, U_VIRTIO_WIN, M_DRIVER_SIGNING])

	static final Check DC_VMGENID = new Check(
		id: 'T2-DC-VMGENID', title: 'Domain controller without VM-Generation ID', tier: Tier.TWO, severity: Severity.WARNING,
		appliesTo: AppliesTo.WINDOWS,
		evidence: SCAN_EVIDENCE + 'Win32_OperatingSystem ProductType (2 = domain controller) and Win32_ComputerSystem DomainRole ' +
			'(4 or 5). HVM VMs carry no VM-Generation ID device (observed on a managed 9.1.0.1 VM: its domain XML has no genid). ' +
			'Critical when the domain controller also has Morpheus snapshots.',
		ifYouLeaveIt: 'Active Directory uses the hypervisor\'s VM-Generation ID to notice that it was reverted to a snapshot or cloned, ' +
			'and then protects itself. Without it, reverting or cloning a domain controller from a snapshot or image backup can ' +
			'cause a USN rollback: replication silently stops for the changes in between, which is hard to repair.',
		ifYouFixIt: [summary: 'There\'s no HVM setting for a VM-Generation ID. Until there is: never revert a domain controller to a ' +
			'snapshot or clone it, delete its snapshots once you no longer need them, and back it up and restore it only with ' +
			'Active Directory-aware tools (system state backup). Add domain controllers by promoting new servers.',
			downtime: Downtime.NONE, risk: Risk.LOW, reversible: true,
			prerequisites: ['Tell everyone who can take or revert snapshots in Morpheus which VMs are domain controllers.']],
		category: Category.CONSIDERATION,
		whyConsideration: 'Adding a VM-Generation ID needs HPE to support it in HVM. QEMU has a vmgenid device, but setting it through ' +
			'QEMU Arguments would give either a new ID at every start (seen by AD as a restore) or a fixed one that never changes on ' +
			'revert, so it isn\'t recommended (inference, not tested).',
		unverified: ['That no HVM release adds a genid element to the domain XML (checked on 9.1.0.1 only).'],
		references: [H3_SNAPSHOTS, H1_ADVANCED_OPTIONS, M_VIRTUAL_DC, M_USN_ROLLBACK, U_QEMU_VMGENID, U_LIBVIRT_GENID])

	static final List<Check> ALL = Collections.unmodifiableList([VIRTIO_READY, VMWARE_TOOLS, GA_FEATURES, TIME_SYNC, VIRTIO_VERSION, DC_VMGENID])
}
