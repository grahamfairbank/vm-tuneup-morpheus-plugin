package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/**
 * Public sources the Tier 2 checks cite, beyond {@link Refs}. Each URL was fetched (HTTP 200) on 2026-11-02 and
 * holds the cited content; the HPE pages match HewlettPackard/morpheus-docs branch dev-9.1. Check texts paraphrase.
 */
@CompileStatic
final class Tier2Refs {

	private Tier2Refs() {}

	static final Reference M_ADDSERVICE = new Reference('Microsoft: INF AddService directive (StartType 0 = boot start)',
		'https://learn.microsoft.com/en-us/windows-hardware/drivers/install/inf-addservice-directive')
	static final Reference M_ACCURATE_TIME = new Reference('Microsoft: Accurate time for Windows Server',
		'https://learn.microsoft.com/en-us/windows-server/networking/windows-time-service/accurate-time')
	static final Reference M_KERBEROS_SKEW = new Reference('Microsoft: Maximum tolerance for computer clock synchronization',
		'https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-10/security/threat-protection/security-policy-settings/maximum-tolerance-for-computer-clock-synchronization')
	static final Reference M_VIRTUAL_DC = new Reference('Microsoft: Virtualized domain controller architecture (VM-Generation ID)',
		'https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/get-started/virtual-dc/virtualized-domain-controller-architecture')
	static final Reference M_USN_ROLLBACK = new Reference('Microsoft: Detect and recover from a USN rollback',
		'https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/detect-and-recover-from-usn-rollback')
	static final Reference M_DRIVER_SIGNING = new Reference('Microsoft: Driver signing offerings',
		'https://learn.microsoft.com/en-us/windows-hardware/drivers/dashboard/driver-signing-offerings')
	static final Reference M_PROGRAM_REMOVAL = new Reference('Microsoft: Fix problems that block programs from being installed or removed',
		'https://support.microsoft.com/en-us/topic/fix-problems-that-block-programs-from-being-installed-or-removed-cca7d1b6-65a9-3d98-426b-e9f927e1eb4d')

	static final Reference U_QEMU_GA = new Reference('QEMU: Guest agent (allowed and blocked commands, fsfreeze)',
		'https://www.qemu.org/docs/master/interop/qemu-ga.html')
	static final Reference U_QEMU_VMGENID = new Reference('QEMU: VM Generation ID device', 'https://www.qemu.org/docs/master/specs/vmgenid.html')
	static final Reference U_LIBVIRT_GENID = new Reference('libvirt: Domain XML format (genid)', 'https://libvirt.org/formatdomain.html')
	static final Reference U_VIRTIO_WIN = new Reference('virtio-win packaging README (releases and signing)',
		'https://github.com/virtio-win/virtio-win-pkg-scripts/blob/master/README.md')
	static final Reference U_DRACUT_CONF = new Reference('dracut.conf(5): hostonly and add_drivers', 'https://man7.org/linux/man-pages/man5/dracut.conf.5.html')
	static final Reference U_INITRAMFS_CONF = new Reference('initramfs.conf(5): MODULES=most or dep',
		'https://manpages.ubuntu.com/manpages/noble/man5/initramfs.conf.5.html')
	static final Reference U_FSTAB = new Reference('fstab(5): naming devices by UUID or LABEL', 'https://man7.org/linux/man-pages/man5/fstab.5.html')
	static final Reference U_KVM_TIMEKEEPING = new Reference('Linux kernel: KVM timekeeping (kvmclock)', 'https://docs.kernel.org/virt/kvm/x86/timekeeping.html')
	static final Reference U_CHRONY = new Reference('chrony documentation', 'https://chrony-project.org/documentation.html')
	static final Reference U_OPEN_VM_TOOLS = new Reference('open-vm-tools (VMware guest tools for Linux)', 'https://github.com/vmware/open-vm-tools')
}
