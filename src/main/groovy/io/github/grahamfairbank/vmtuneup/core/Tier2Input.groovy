package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * What the Tier 2 rules need from Tier 1 (Morpheus data), besides the guest scan. Null means "not known".
 * Build it with {@link #fromVm} or set the fields directly.
 */
@CompileStatic
@ToString(includeNames = true)
final class Tier2Input {

	static final String VIRTIO_BLK = 'virtio-blk'
	static final String VIRTIO_SCSI = 'virtio-scsi'

	/** True when a disk is still on SATA or IDE (the T1-DISK-BUS failure), false when all are on VirtIO. */
	Boolean needsBusSwitch
	/** The controller the disks will move to: {@link #VIRTIO_BLK} (default: HPE maps IDE/SATA to VirtIO Block) or {@link #VIRTIO_SCSI}. */
	String targetController
	/** True when a network adapter is not VirtIO (the T1-NIC-MODEL failure). */
	Boolean needsNicSwitch
	/** True when the VM has Morpheus snapshots, false when it has none, null when they couldn't be read. */
	Boolean hasSnapshots
	/** The newest virtio-win release seen across the cluster (e.g. 0.1.302), for T2-VIRTIO-VERSION; optional. */
	String fleetNewestVirtioWin

	Tier2Input() {}

	Tier2Input(Map args) {
		if (args == null) return
		needsBusSwitch = args.needsBusSwitch as Boolean
		targetController = args.targetController as String
		needsNicSwitch = args.needsNicSwitch as Boolean
		hasSnapshots = args.hasSnapshots as Boolean
		fleetNewestVirtioWin = args.fleetNewestVirtioWin as String
	}

	String getTarget() { targetController == VIRTIO_SCSI ? VIRTIO_SCSI : VIRTIO_BLK }

	/** From the Tier 1 facts: SATA/IDE disks and non-VirtIO adapters (same rules as T1-DISK-BUS and T1-NIC-MODEL), snapshots. */
	static Tier2Input fromVm(VmFacts vm) {
		Tier2Input t = new Tier2Input()
		if (vm == null) return t
		List<VmFacts.Volume> disks = vm.disks
		if (!disks.isEmpty()) {
			t.needsBusSwitch = disks.any { VmFacts.Volume v -> (v.diskMode?.toLowerCase() in Tier1Rules.EMULATED_BUSES) || (v.controllerType?.toLowerCase() in Tier1Rules.EMULATED_CONTROLLERS) }
		}
		t.targetController = VIRTIO_BLK
		if (!vm.interfaces.isEmpty()) t.needsNicSwitch = vm.interfaces.any { VmFacts.Nic n -> n.typeCode?.toLowerCase() != 'virtio' }
		if (vm.snapshots != null) t.hasSnapshots = !vm.snapshots.isEmpty()
		return t
	}
}
