package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import static io.github.grahamfairbank.vmtuneup.core.Tier2Catalog.*

/**
 * Evaluates the Tier 2 checks on one VM from its guest scan ({@link GuestFacts}) and a few Tier 1 facts
 * ({@link Tier2Input}). Every check returns exactly one result. A scan that failed, or a fact the scan couldn't
 * read, gives UNKNOWN with the reason, never PASS.
 */
@CompileStatic
final class Tier2Rules {

	private Tier2Rules() {}

	static final String QGA_VSS = 'QEMU Guest Agent VSS Provider'
	/** Fewer lines than this from lsinitrd means the image wasn't readable (a real one lists hundreds). */
	static final int MIN_INITRD_ENTRIES = 20

	static List<CheckResult> evaluate(GuestFacts g, Tier2Input input) {
		List<CheckResult> out = new ArrayList<CheckResult>()
		for (Check c : Tier2Catalog.ALL) out.add(evaluate(c, g, input))
		return out
	}

	static CheckResult evaluate(Check check, GuestFacts g, Tier2Input input) {
		Tier2Input in2 = input ?: new Tier2Input()
		if (g == null) return new CheckResult(check, Status.UNKNOWN, null, 'No guest scan has run on this VM.')
		if (!g.ok) return new CheckResult(check, Status.UNKNOWN, null, 'The guest scan gave no usable result: ' + g.problem)
		if (check.appliesTo == AppliesTo.WINDOWS && !g.windows) return new CheckResult(check, Status.NOT_APPLICABLE, null, 'Windows only.')
		if (check.appliesTo == AppliesTo.LINUX && !g.linux) return new CheckResult(check, Status.NOT_APPLICABLE, null, 'Linux only.')
		switch (check.id) {
			case 'T2-VIRTIO-READY': return virtioReady(g, in2)
			case 'T2-VMWARE-TOOLS': return vmwareTools(g)
			case 'T2-GA-FEATURES': return gaFeatures(g)
			case 'T2-TIME-SYNC': return timeSync(g)
			case 'T2-VIRTIO-VERSION': return virtioVersion(g, in2)
			case 'T2-DC-VMGENID': return dcVmgenid(g, in2)
			default: return new CheckResult(check, Status.NOT_EVALUATED, null, 'No rule for this check.')
		}
	}

	/** A note when the scan ran with fewer rights than it should (some reads may then come back empty). */
	static List<String> rightsNotes(GuestFacts g) {
		if (g.windows && g.ranAsSystem == Boolean.FALSE) return ['The guest scan did not run as SYSTEM, so some values may be missing.']
		if (g.linux && g.uid != null && g.uid != 0) return ["The guest scan ran as uid ${g.uid}, not root, so some files may have been unreadable.".toString()]
		return []
	}

	/** Builds the result from problems (FAIL), unknown parts (UNKNOWN) or neither (PASS). */
	private static CheckResult outcome(Check check, List<String> problems, List<String> unknown, String passDetail,
	                                   Severity failSeverity, List<String> notes) {
		if (!problems.isEmpty()) {
			List<String> n = new ArrayList<String>(notes)
			if (!unknown.isEmpty()) n.add('Also not known: ' + unknown.join('; ') + '.')
			return new CheckResult(check, Status.FAIL, failSeverity, problems.join(' ') , n)
		}
		if (!unknown.isEmpty()) return new CheckResult(check, Status.UNKNOWN, null, 'Not known: ' + unknown.join('; ') + '.', notes)
		return new CheckResult(check, Status.PASS, null, passDetail, notes)
	}

	private static Severity worst(Severity a, Severity b) {
		if (a == null) return b
		if (b == null) return a
		return a.ordinal() <= b.ordinal() ? a : b
	}

	// ---- T2-VIRTIO-READY ----

	static CheckResult virtioReady(GuestFacts g, Tier2Input input) {
		List<String> problems = []
		List<String> unknown = []
		List<String> notes = new ArrayList<String>(rightsNotes(g))
		List<String> seen = []
		Boolean bus = input.needsBusSwitch
		boolean checkDisk = bus != Boolean.FALSE
		if (g.windows) {
			if (checkDisk) windowsStorage(g, input.target, problems, unknown, notes, seen)
			if (input.needsNicSwitch == Boolean.TRUE) {
				Boolean nk = g.serviceExists('netkvm')
				if (nk == null) unknown.add('whether the VirtIO network driver (netkvm) is installed')
				else if (!nk) problems.add('The VirtIO network driver (netkvm) is not installed, so a VirtIO network adapter would have no driver.')
				else seen.add('netkvm installed')
			}
			if (!problems.isEmpty()) notes.add(Tier1Catalog.WINDOWS_BUS_RULE)
		} else {
			if (checkDisk) linuxDrivers(g, input.target, problems, unknown, seen)
			List<String> names = linuxDeviceNames(g, unknown)
			if (!names.isEmpty()) {
				String text = 'Disks are named by device or bus path, which changes when the bus changes: ' + names.join(', ') + '.'
				if (checkDisk) problems.add(text)
				else notes.add(text + ' A later switch between VirtIO Block and VirtIO SCSI would rename them too.')
			} else if (g.fstabRead == Boolean.TRUE) {
				seen.add('fstab and root= use UUID, LABEL or LVM names')
			}
			if (!problems.isEmpty()) notes.add(Tier1Catalog.LINUX_BUS_RULE)
		}
		if (bus == Boolean.FALSE) {
			if (problems.isEmpty()) {
				String d = 'Disks are already on VirtIO' + (seen ? '; ' + seen.join('; ') : '') + '.'
				return new CheckResult(VIRTIO_READY, Status.PASS, null, d, notes)
			}
			return new CheckResult(VIRTIO_READY, Status.FAIL, Severity.WARNING, problems.join(' '), notes)
		}
		Severity sev = bus == Boolean.TRUE ? Severity.WARNING : Severity.INFO
		if (bus == null) notes.add('Morpheus did not say whether a disk is still on SATA or IDE, so this is reported as info.')
		return outcome(VIRTIO_READY, problems, unknown, 'Ready to switch to VirtIO: ' + seen.join('; ') + '.', sev, notes)
	}

	private static void windowsStorage(GuestFacts g, String target, List<String> problems, List<String> unknown, List<String> notes, List<String> seen) {
		String drv = target == Tier2Input.VIRTIO_SCSI ? 'vioscsi' : 'viostor'
		String bus = target == Tier2Input.VIRTIO_SCSI ? 'VirtIO SCSI' : 'VirtIO Block'
		Boolean exists = g.serviceExists(drv)
		Integer start = g.serviceStart(drv)
		Boolean staged = g.driverStaged(drv)
		if (exists == null) {
			unknown.add("whether the ${drv} driver is installed".toString())
			return
		}
		if (exists && start == 0) {
			seen.add("${drv} is installed and boot-start (Start = 0)".toString())
			return
		}
		if (exists) {
			problems.add("The ${bus} storage driver (${drv}) is installed but not boot-start (Start = ${start == null ? '?' : start}): switching the boot disk to ${bus} now makes Windows fail to boot (INACCESSIBLE_BOOT_DEVICE).".toString())
		} else if (staged == Boolean.TRUE) {
			problems.add("The ${bus} storage driver (${drv}) is only staged in the driver store, not installed: Windows hasn't seen VirtIO storage yet, so switching the boot disk now makes it fail to boot.".toString())
		} else {
			problems.add("The ${bus} storage driver (${drv}) is not installed: install the virtio-win guest tools first.".toString())
		}
		String other = drv == 'viostor' ? 'vioscsi' : 'viostor'
		if (g.serviceStart(other) == 0) notes.add("${other} is boot-start, so the guest is ready for ${other == 'vioscsi' ? 'VirtIO SCSI' : 'VirtIO Block'} instead.".toString())
	}

	/** Whether a module is available at boot: built in, or in the initramfs. Null when that couldn't be read. */
	static Boolean bootAvailable(GuestFacts g, String module) {
		if (g.builtin(module) == Boolean.TRUE) return true
		String tool = g.initrdTool
		if (tool == 'lsinitrd') {
			if (g.initrdFile == 'missing') return g.builtinRead == Boolean.TRUE ? Boolean.FALSE : null
			Integer n = g.initrdEntries
			if (n == null || n < MIN_INITRD_ENTRIES) return null
			return g.inInitrd(module)
		}
		if (tool == 'initramfs-tools') {
			if (g.initrdFile == 'missing') return g.builtinRead == Boolean.TRUE ? Boolean.FALSE : null
			if (g.initrdModules == 'most') return Boolean.TRUE
			return g.inInitrd(module) == Boolean.TRUE
		}
		return null
	}

	private static void linuxDrivers(GuestFacts g, String target, List<String> problems, List<String> unknown, List<String> seen) {
		String disk = target == Tier2Input.VIRTIO_SCSI ? 'virtio_scsi' : 'virtio_blk'
		for (String m : [disk, 'virtio_pci']) {
			Boolean ok = bootAvailable(g, m)
			if (ok == null) {
				unknown.add("whether ${m} is in the initramfs (${g.initrdTool == 'none' ? 'no initramfs tool found' : 'the initramfs could not be read'})".toString())
			} else if (!ok) {
				String where = g.moduleOnDisk(m) == Boolean.TRUE ? 'installed as a module but not in the initramfs' : 'neither built into the kernel nor in the initramfs'
				problems.add("${m} is ${where} of kernel ${g.kernel ?: '?'}: the VM would stop at boot waiting for its root disk.".toString())
			} else {
				seen.add(g.builtin(m) == Boolean.TRUE ? "${m} built into the kernel".toString() :
					g.initrdTool == 'initramfs-tools' && g.inInitrd(m) != Boolean.TRUE ? "${m} in the initramfs (MODULES=${g.initrdModules})".toString() :
						"${m} in the initramfs".toString())
			}
		}
	}

	private static List<String> linuxDeviceNames(GuestFacts g, List<String> unknown) {
		List<String> out = []
		if (g.fstabRead == null) unknown.add('how /etc/fstab names the disks')
		else if (g.fstabRead == Boolean.FALSE) unknown.add('how /etc/fstab names the disks (not readable)')
		for (String e : g.fstabDeviceNames) out.add("fstab ${e}".toString())
		if (GuestFacts.isBusDependent(g.bootRoot)) out.add("root=${g.bootRoot}".toString())
		if (GuestFacts.isBusDependent(g.bootResume)) out.add("resume=${g.bootResume}".toString())
		return out
	}

	// ---- T2-VMWARE-TOOLS ----

	static CheckResult vmwareTools(GuestFacts g) {
		List<String> notes = new ArrayList<String>(rightsNotes(g))
		if (g.windows) {
			String pkg = g.vmwareToolsVersion
			Boolean svc = g.serviceExists('VMTools')
			Integer start = g.serviceStart('VMTools')
			String state = g.serviceState('VMTools')
			Map<String, Integer> drivers = g.vmwareDrivers
			Integer pci = g.vmwarePciDevices
			List<String> parts = []
			if (drivers) parts.add('VMware drivers still registered: ' + drivers.collect { String k, Integer v -> "${k} (Start = ${v})".toString() }.join(', '))
			if (pci != null && pci > 0) parts.add("Windows still remembers ${pci} VMware PCI device${pci == 1 ? '' : 's'} (hidden adapters may keep old network settings)".toString())
			if (pkg != null || (svc == Boolean.TRUE && start != 4)) {
				boolean active = state == 'Running' || start == 2
				String head = "VMware Tools ${pkg ?: ''} is installed".replace('  ', ' ') + (svc == Boolean.TRUE ?
					", its service is ${state ?: 'in an unknown state'} (Start = ${start == null ? '?' : start})" : '') + '.'
				return new CheckResult(VMWARE_TOOLS, Status.FAIL, active ? Severity.WARNING : Severity.INFO,
					head + (parts ? ' ' + parts.join('. ') + '.' : ''), notes)
			}
			if (!parts.isEmpty()) {
				return new CheckResult(VMWARE_TOOLS, Status.FAIL, Severity.INFO, 'VMware Tools is not installed, but leftovers remain. ' + parts.join('; ') + '.', notes)
			}
			if (svc == null) return new CheckResult(VMWARE_TOOLS, Status.UNKNOWN, null, 'The scan did not report the VMware Tools service.', notes)
			return new CheckResult(VMWARE_TOOLS, Status.PASS, null, 'No VMware Tools and no VMware drivers found.', notes)
		}
		List<String> warn = []
		List<String> info = []
		if (g.vmtoolsRunning == Boolean.TRUE) warn.add('vmtoolsd (VMware Tools) is running.')
		if (g.vmwareTarball == Boolean.TRUE) info.add('A VMware Tools tarball install is present (/etc/vmware-tools).')
		if (g.openVmToolsVersion != null) info.add("open-vm-tools ${g.openVmToolsVersion} is installed${g.vmtoolsRunning == Boolean.TRUE ? '' : ' (not running)'}.".toString())
		if (g.vmwareModules) info.add('VMware kernel modules are loaded: ' + g.vmwareModules.join(', ') + '.')
		if (warn.isEmpty() && info.isEmpty()) {
			if (g.vmtoolsRunning == null) return new CheckResult(VMWARE_TOOLS, Status.UNKNOWN, null, 'The scan did not report VMware Tools.', notes)
			return new CheckResult(VMWARE_TOOLS, Status.PASS, null, 'No open-vm-tools or VMware Tools found.', notes)
		}
		return new CheckResult(VMWARE_TOOLS, Status.FAIL, warn ? Severity.WARNING : Severity.INFO, (warn + info).join(' '), notes)
	}

	// ---- T2-GA-FEATURES ----

	static CheckResult gaFeatures(GuestFacts g) {
		List<String> problems = []
		List<String> unknown = []
		List<String> notes = new ArrayList<String>(rightsNotes(g))
		if (g.windows) {
			Boolean qga = g.serviceExists('QEMU-GA')
			String state = g.serviceState('QEMU-GA')
			if (qga == null) unknown.add('whether the QEMU guest agent is installed')
			else if (!qga) problems.add('The QEMU guest agent is not installed.')
			else if (g.serviceStart('QEMU-GA') == 4) problems.add('The QEMU guest agent service is disabled.')
			else if (state != null && state != 'Running') problems.add("The QEMU guest agent service is ${state}.".toString())
			Boolean vssSvc = g.serviceExists(QGA_VSS)
			boolean registered = g.vssProviders.any { String p -> p.toLowerCase().contains('qemu') }
			if (qga) {
				if (registered && g.serviceStart(QGA_VSS) != 4) {
					// fine: the provider service starts on demand, so "Stopped" is normal
				} else if (g.serviceStart(QGA_VSS) == 4) {
					problems.add('The QEMU Guest Agent VSS Provider service is disabled, so a freeze does not reach VSS.')
				} else if (vssSvc == Boolean.TRUE) {
					problems.add('The QEMU Guest Agent VSS Provider service exists but is not registered with VSS.')
				} else if (vssSvc == Boolean.FALSE) {
					problems.add('The QEMU Guest Agent VSS Provider is not installed, so snapshots don\'t notify VSS-aware applications.')
				} else {
					unknown.add('whether the QEMU Guest Agent VSS Provider is installed')
				}
			}
			List<String> blocked = g.qgaBlockedRpcs
			List<String> allowed = g.qgaAllowedRpcs
			if (blocked.any { String r -> r.contains('guest-fsfreeze') }) problems.add('The guest agent is started with the freeze commands blocked: ' + blocked.join(',') + '.')
			if (allowed && !allowed.contains('guest-fsfreeze-freeze')) problems.add('The guest agent is started with an allow list that leaves out guest-fsfreeze-freeze.')
			String ver = g.qgaVersion
			return outcome(GA_FEATURES, problems, unknown, "QEMU guest agent${ver ? ' ' + ver : ''} is running and its VSS provider is registered.".toString(), Severity.WARNING, notes)
		}
		Boolean running = g.qgaRunning
		String pkg = g.qgaVersion
		if (running == null) unknown.add('whether qemu-ga runs')
		else if (!running) problems.add(pkg == null ? 'The QEMU guest agent (qemu-guest-agent) is not installed.' : "qemu-guest-agent ${pkg} is installed but not running.".toString())
		if (g.qgaChannel == Boolean.FALSE) problems.add('The guest has no guest agent channel device (/dev/virtio-ports/org.qemu.guest_agent.0), see T1-GA-CHANNEL.')
		if (g.qgaFreeze == 'blocked') problems.add('guest-fsfreeze-freeze is blocked in the guest agent configuration.')
		if (g.qgaExec == 'blocked') notes.add('guest-exec is blocked in the guest agent configuration (the RHEL default). That is fine: on Linux the plugin uses the Morpheus agent.')
		if (g.qgaHook == Boolean.TRUE) notes.add('An fsfreeze hook is present (/etc/qemu/fsfreeze-hook), so applications can be quiesced before the freeze.')
		return outcome(GA_FEATURES, problems, unknown, "qemu-guest-agent${pkg ? ' ' + pkg : ''} is running and file system freeze is ${g.qgaFreeze == 'allowed' ? 'allowed' : 'not blocked'}.".toString(), Severity.WARNING, notes)
	}

	// ---- T2-TIME-SYNC ----

	static final List<String> FREE_RUNNING = Collections.unmodifiableList(['local cmos clock', 'free-running system clock'])

	static CheckResult timeSync(GuestFacts g) {
		List<String> problems = []
		List<String> unknown = []
		List<String> notes = new ArrayList<String>(rightsNotes(g))
		Severity sev = null
		if (g.windows) {
			String state = g.serviceState('W32Time')
			String source = g.timeSource
			String type = g.timeType
			boolean client = g.productType == 1
			if (state == null) unknown.add('the Windows Time service state')
			else if (state != 'Running') {
				if (client) notes.add("The Windows Time service is ${state}; on Windows clients it starts when needed.".toString())
				else { problems.add("The Windows Time service is ${state}.".toString()); sev = Severity.WARNING }
			}
			if (type?.equalsIgnoreCase('NoSync')) { problems.add('Windows Time is set not to synchronize (Type = NoSync).'); sev = Severity.WARNING }
			if (source == null) unknown.add('the Windows time source')
			else if (FREE_RUNNING.any { String f -> source.toLowerCase().startsWith(f) }) {
				String why = g.domainRole == 5 ? ' This is the primary domain controller: it should take its time from an external NTP source, because the whole domain follows it.' : ''
				problems.add("Windows takes its time from '${source}', not from a time server.${why}".toString()); sev = Severity.WARNING
			}
			if (problems.isEmpty() && unknown.isEmpty()) {
				return new CheckResult(TIME_SYNC, Status.PASS, null, "Windows Time synchronizes from ${source}${type ? " (Type ${type})" : ''}.".toString(), notes)
			}
			return outcome(TIME_SYNC, problems, unknown, '', sev ?: Severity.WARNING, notes)
		}
		String cs = g.clocksource
		if (cs == null) unknown.add('the clock source')
		else if (cs != 'kvm-clock') {
			if (cs == 'tsc') {
				problems.add('The clock source is tsc, not kvm-clock.')
				sev = worst(sev, Severity.INFO)
				if (!g.availableClocksources.contains('kvm-clock')) notes.add('kvm-clock is not offered to this guest.')
			} else {
				problems.add("The clock source is ${cs}, not kvm-clock${g.availableClocksources.contains('kvm-clock') ? ' (kvm-clock is available)' : ''}.".toString())
				sev = worst(sev, Severity.WARNING)
			}
		}
		List<String> services = g.timeServices
		Boolean synced = g.ntpSynced
		if (services.isEmpty() && synced != Boolean.TRUE) {
			problems.add('No NTP client (chronyd, ntpd, systemd-timesyncd) is running.')
			sev = worst(sev, Severity.WARNING)
		} else if (synced == Boolean.FALSE) {
			problems.add("The clock is not synchronized (${services ? services.join(', ') + ' running' : 'timedatectl'}).".toString())
			sev = worst(sev, Severity.WARNING)
		} else if (synced == null) {
			notes.add('timedatectl did not report whether the clock is synchronized.')
		}
		String pass = "Clock source ${cs}; ${services ? services.join(', ') : 'NTP'} running${synced == Boolean.TRUE ? ' and synchronized' : ''}.".toString()
		return outcome(TIME_SYNC, problems, unknown, pass, sev ?: Severity.WARNING, notes)
	}

	// ---- T2-VIRTIO-VERSION ----

	/** Compares dotted numeric versions (0.1.240 < 0.1.302); non-numeric parts count as 0. */
	static int compareVersions(String a, String b) {
		String[] x = (a ?: '').split(/\./)
		String[] y = (b ?: '').split(/\./)
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			long p = i < x.length && x[i] ==~ /\d{1,9}/ ? Long.parseLong(x[i]) : 0L
			long q = i < y.length && y[i] ==~ /\d{1,9}/ ? Long.parseLong(y[i]) : 0L
			if (p != q) return p < q ? -1 : 1
		}
		return 0
	}

	static CheckResult virtioVersion(GuestFacts g, Tier2Input input) {
		List<String> files = []
		Set<String> releases = new LinkedHashSet<String>()
		for (String d : ['viostor', 'vioscsi', 'netkvm']) {
			String v = g.driverVersion(d)
			if (v == null) continue
			String r = GuestFacts.virtioReleaseOf(v)
			files.add("${d} ${v}${r ? " (${r})" : ''}".toString())
			if (r) releases.add(r)
		}
		String rel = g.virtioWinRelease
		if (rel == null && files.isEmpty()) {
			return new CheckResult(VIRTIO_VERSION, Status.NOT_APPLICABLE, null, 'No VirtIO drivers are installed (see T2-VIRTIO-READY).')
		}
		List<String> notes = []
		if (releases.size() > 1) notes.add('The drivers come from different virtio-win releases: ' + releases.join(', ') + '.')
		String detail = "virtio-win ${rel ?: 'release unknown'}${files ? '; ' + files.join(', ') : ''}.".toString()
		String fleet = input.fleetNewestVirtioWin
		if (rel != null && fleet != null && compareVersions(rel, fleet) < 0) {
			return new CheckResult(VIRTIO_VERSION, Status.FAIL, Severity.INFO, "${detail} The newest in this cluster is ${fleet}.".toString(), notes)
		}
		return new CheckResult(VIRTIO_VERSION, Status.PASS, null, detail, notes)
	}

	// ---- T2-DC-VMGENID ----

	static CheckResult dcVmgenid(GuestFacts g, Tier2Input input) {
		Boolean dc = g.domainController
		if (dc == null) return new CheckResult(DC_VMGENID, Status.UNKNOWN, null, 'The scan did not report whether this is a domain controller.')
		if (!dc) return new CheckResult(DC_VMGENID, Status.NOT_APPLICABLE, null, 'Not a domain controller.')
		String role = g.domainRole == 5 ? 'primary domain controller' : g.domainRole == 4 ? 'backup domain controller' : 'domain controller'
		if (input.hasSnapshots == Boolean.TRUE) {
			return new CheckResult(DC_VMGENID, Status.FAIL, Severity.CRITICAL,
				"This VM is a ${role} and has Morpheus snapshots. HVM gives it no VM-Generation ID, so reverting to one of them can cause a USN rollback.".toString(),
				['Delete the snapshots once you no longer need them, and never revert this VM to one.'])
		}
		List<String> notes = input.hasSnapshots == null ? ['Snapshots could not be read for this VM.'] : Collections.<String>emptyList()
		return new CheckResult(DC_VMGENID, Status.FAIL, Severity.WARNING,
			"This VM is a ${role}. HVM gives it no VM-Generation ID, so never revert it to a snapshot or clone it.".toString(), notes)
	}
}
