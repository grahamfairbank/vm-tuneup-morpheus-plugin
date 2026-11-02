package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import static io.github.grahamfairbank.vmtuneup.core.Tier1Catalog.*

/**
 * Evaluates the Tier 1 checks on one VM. Every check returns exactly one result. A check that can't decide
 * (field absent on 9.0.x, discovered VM without config) returns UNKNOWN with the reason, never PASS.
 */
@CompileStatic
final class Tier1Rules {

	private Tier1Rules() {}

	static final Set<String> VMWARE_OUIS = Collections.unmodifiableSet(['00:50:56', '00:0c:29', '00:05:69', '00:1c:14'] as Set<String>)
	static final Set<String> EMULATED_BUSES = Collections.unmodifiableSet(['sata', 'ide'] as Set<String>)
	static final Set<String> EMULATED_CONTROLLERS = Collections.unmodifiableSet(['kvm-sata', 'kvm-ide'] as Set<String>)
	static final int AGENT_SILENT_DAYS = 7
	/** guestAgentStatus values that mean the agent isn't working on a running VM. */
	static final Set<String> GA_NEGATIVE = Collections.unmodifiableSet(['disconnected', 'error', 'unconfigured'] as Set<String>)

	static List<CheckResult> evaluate(VmFacts vm, ScanContext ctx) {
		ScanContext c = ctx ?: new ScanContext()
		List<CheckResult> out = new ArrayList<CheckResult>()
		for (Check check : Tier1Catalog.ALL) out.add(evaluate(check, vm, c))
		return out
	}

	static CheckResult evaluate(Check check, VmFacts vm, ScanContext ctx) {
		if (check.id in Tier1Catalog.NOT_EVALUATED) {
			return new CheckResult(check, Status.NOT_EVALUATED, null, 'Not evaluated yet: pending verification.')
		}
		switch (check.id) {
			case 'T1-DISK-BUS': return diskBus(vm)
			case 'T1-NIC-MODEL': return nicModel(vm)
			case 'T1-CHIPSET': return chipset(vm)
			case 'T1-VMWARE-MAC': return vmwareMac(vm)
			case 'T1-OS-TYPE-MISMATCH': return osTypeMismatch(vm)
			case 'T1-FIRMWARE': return firmware(vm)
			case 'T1-GA-CHANNEL': return gaChannel(vm)
			case 'T1-GA-STATUS': return gaStatus(vm)
			case 'T1-MORPHEUS-AGENT': return morpheusAgent(vm, ctx)
			case 'T1-SNAPSHOTS': return snapshots(vm, ctx)
			case 'T1-DISK-ALLOCATION': return diskAllocation(vm)
			case 'T1-DISCOVERED': return discovered(vm)
			case 'T1-ISO-ATTACHED': return isoAttached(vm)
			default: return new CheckResult(check, Status.NOT_EVALUATED, null, 'No rule for this check.')
		}
	}

	// ---- disk and devices ----

	static CheckResult diskBus(VmFacts vm) {
		List<VmFacts.Volume> disks = vm.disks
		if (disks.isEmpty()) return new CheckResult(DISK_BUS, Status.UNKNOWN, null, 'Morpheus lists no disks for this VM.')
		VmFacts.Volume boot = vm.bootDisk
		List<String> bad = []
		boolean bootOnIde = false
		for (VmFacts.Volume v : disks) {
			if (!emulated(v)) continue
			bad.add("${v.root || v.is(boot) ? 'Boot disk' : 'Disk'} '${v.name ?: '?'}': diskMode ${v.diskMode ?: 'none'}, controller ${v.controllerType ?: 'unknown'}".toString())
			if (v.is(boot) && isIde(v)) bootOnIde = true
		}
		if (bad.isEmpty()) {
			return new CheckResult(DISK_BUS, Status.PASS, null, 'All disks on VirtIO: ' + disks.collect { VmFacts.Volume v -> "${v.diskMode ?: '?'}/${v.controllerType ?: '?'}" }.unique().join(', '))
		}
		return new CheckResult(DISK_BUS, Status.FAIL, bootOnIde ? Severity.CRITICAL : Severity.WARNING, bad.join('; ') + '.', osRuleNotes(vm))
	}

	private static boolean emulated(VmFacts.Volume v) {
		return (v.diskMode?.toLowerCase() in EMULATED_BUSES) || (v.controllerType?.toLowerCase() in EMULATED_CONTROLLERS)
	}

	private static boolean isIde(VmFacts.Volume v) {
		return v.diskMode?.toLowerCase() == 'ide' || v.controllerType?.toLowerCase() == 'kvm-ide'
	}

	/** Which ordering rule applies, from serverOs (never from serverType). */
	static List<String> osRuleNotes(VmFacts vm) {
		String fam = vm.osFamilyKey
		if (fam == 'windows') return [WINDOWS_BUS_RULE]
		if (fam == 'linux') return [LINUX_BUS_RULE]
		return ['The guest OS is unknown to Morpheus, so check which rule applies: ' + WINDOWS_BUS_RULE, LINUX_BUS_RULE]
	}

	static CheckResult nicModel(VmFacts vm) {
		if (vm.interfaces.isEmpty()) return new CheckResult(NIC_MODEL, Status.NOT_APPLICABLE, null, 'The VM has no network interfaces.')
		List<String> bad = []
		for (VmFacts.Nic n : vm.interfaces) {
			if (n.typeCode?.toLowerCase() == 'virtio') continue
			bad.add(n.typeCode == null ? "Interface '${n.name ?: '?'}': no type reported (not VirtIO, model unknown; E1000E shows like this)".toString()
				: "Interface '${n.name ?: '?'}': type ${n.typeCode}${n.typeName ? " (${n.typeName})" : ''}".toString())
		}
		if (bad.isEmpty()) return new CheckResult(NIC_MODEL, Status.PASS, null, "All ${vm.interfaces.size()} interfaces are VirtIO.")
		return new CheckResult(NIC_MODEL, Status.FAIL, null, bad.join('; ') + '.')
	}

	static CheckResult chipset(VmFacts vm) {
		Object mt = vm.config.get('machineType')
		if (mt == null || mt.toString().trim().isEmpty()) {
			String why = !vm.managed ? 'Discovered VM: Morpheus has no chipset setting for it.' :
				'config.machineType is not reported (Morpheus 9.0.x, or a VM created before the setting existed).'
			List<String> notes = vm.disks.any { VmFacts.Volume v -> isIde(v) } ? ['A disk on IDE suggests the Legacy (i440fx) chipset.'] : []
			return new CheckResult(CHIPSET, Status.UNKNOWN, null, 'Unknown on this version or VM. ' + why, notes)
		}
		String s = mt.toString().trim()
		if (s.toLowerCase().contains('q35')) return new CheckResult(CHIPSET, Status.PASS, null, "config.machineType = ${s}")
		return new CheckResult(CHIPSET, Status.FAIL, null, "config.machineType = ${s} (Legacy chipset).", osRuleNotes(vm))
	}

	static String oui(String mac) {
		if (!mac) return null
		String m = mac.trim().toLowerCase().replace('-', ':')
		return m.length() >= 8 ? m.substring(0, 8) : null
	}

	static CheckResult vmwareMac(VmFacts vm) {
		if (vm.interfaces.isEmpty()) return new CheckResult(VMWARE_MAC, Status.NOT_APPLICABLE, null, 'The VM has no network interfaces.')
		List<String> hits = vm.interfaces.findAll { VmFacts.Nic n -> oui(n.mac) in VMWARE_OUIS }.collect { VmFacts.Nic n -> "${n.name ?: '?'} ${n.mac}".toString() }
		if (hits.isEmpty()) return new CheckResult(VMWARE_MAC, Status.PASS, null, 'No VMware MAC address.')
		return new CheckResult(VMWARE_MAC, Status.FAIL, null, 'VMware MAC address on: ' + hits.join(', ') + '. VMware origin.')
	}

	// ---- OS typing and firmware ----

	static CheckResult osTypeMismatch(VmFacts vm) {
		if (!vm.hasServerOs()) return new CheckResult(OS_TYPE_MISMATCH, Status.NOT_APPLICABLE, null, 'Morpheus has no serverOs for this VM.')
		String side = "serverOs ${vm.osCode ?: vm.osName}, osType ${vm.osType ?: 'null'}, platform ${vm.platform ?: 'null'}, server type ${vm.serverType ?: 'null'}"
		List<String> problems = []
		if (vm.osType == null) problems.add('osType is empty')
		if (vm.windows) {
			if (vm.managed && vm.serverType != 'morpheusWindowsVm') problems.add('server type is not morpheusWindowsVm')
			if (vm.platform?.toLowerCase() != 'windows') problems.add('platform is not windows')
		}
		if (problems.isEmpty()) return new CheckResult(OS_TYPE_MISMATCH, Status.PASS, null, side + '.')
		return new CheckResult(OS_TYPE_MISMATCH, Status.FAIL, vm.windows ? Severity.WARNING : Severity.INFO,
			side + ': ' + problems.join(', ') + '. Likely VMware origin (native migration).')
	}

	static boolean isWindows11(VmFacts vm) {
		String c = "${vm.osCode ?: ''} ${vm.osName ?: ''}".toLowerCase()
		return c.contains('windows.11') || c.contains('windows 11')
	}

	static boolean isServer2025(VmFacts vm) {
		String c = "${vm.osCode ?: ''} ${vm.osName ?: ''}".toLowerCase()
		return c.contains('server') && c.contains('2025')
	}

	static CheckResult firmware(VmFacts vm) {
		String flags = "UEFI ${fmt(vm.uefi)}, Secure Boot ${fmt(vm.secureBoot)}, TPM ${fmt(vm.tpm)}"
		List<String> problems = []
		if (vm.tpm == Boolean.TRUE && vm.uefi != Boolean.TRUE) problems.add('TPM without UEFI is inconsistent')
		if (vm.hasServerOs() && (isWindows11(vm) || isServer2025(vm)) && !(vm.uefi == Boolean.TRUE && vm.tpm == Boolean.TRUE)) {
			problems.add("${vm.osName ?: vm.osCode} without UEFI and TPM".toString())
		}
		if (!problems.isEmpty()) return new CheckResult(FIRMWARE, Status.FAIL, null, flags + ': ' + problems.join('; ') + '.')
		if (!vm.hasServerOs()) return new CheckResult(FIRMWARE, Status.UNKNOWN, null, flags + '. The guest OS is unknown, so the fit can\'t be judged.')
		return new CheckResult(FIRMWARE, Status.PASS, null, flags + " for ${vm.osName ?: vm.osCode}.")
	}

	private static String fmt(Boolean b) { b == null ? 'unknown' : b ? 'on' : 'off' }

	// ---- guest agents ----

	static CheckResult gaChannel(VmFacts vm) {
		if (!vm.config.containsKey('disableQgaChannel') || vm.config.get('disableQgaChannel') == null) {
			return new CheckResult(GA_CHANNEL, Status.UNKNOWN, null, 'Unknown on this version or VM: config.disableQgaChannel is not reported' +
				(!vm.managed ? ' (discovered VM).' : ' (Morpheus 9.0.x, or a VM created before the setting existed).'))
		}
		Boolean off = VmFacts.bool(vm.config.get('disableQgaChannel'))
		if (off == Boolean.TRUE) return new CheckResult(GA_CHANNEL, Status.FAIL, null, 'config.disableQgaChannel = true.')
		return new CheckResult(GA_CHANNEL, Status.PASS, null, 'config.disableQgaChannel = false.')
	}

	static CheckResult gaStatus(VmFacts vm) {
		String st = vm.guestAgentStatus ?: 'null'
		if (vm.poweredOff) return new CheckResult(GA_STATUS, Status.NOT_APPLICABLE, null, "Powered off (guestAgentStatus ${st}): judged only on running VMs.")
		if (!vm.poweredOn) return new CheckResult(GA_STATUS, Status.UNKNOWN, null, "Power state ${vm.powerState ?: 'unknown'}, guestAgentStatus ${st}.")
		if (st == 'connected') return new CheckResult(GA_STATUS, Status.PASS, null, 'Powered on, guestAgentStatus connected.')
		// Real states from Morpheus decide on their own. disconnected, error and unconfigured are the negative ones;
		// any other state but connected on a running VM (such as configured) also means the agent isn't connected.
		if (!(st in ['unknown', 'null'])) return new CheckResult(GA_STATUS, Status.FAIL, null, "Powered on, guestAgentStatus ${st}.")
		// "unknown" is also the plugin model's default value (Morpheus 9.1.0.1 never fills it in through the plugin
		// API), so it means "not reported". The guest agent probe decides then.
		String seen = "Powered on, guestAgentStatus ${st} (not reported)"
		switch (vm.gaProbe) {
			case 'responded':
				return new CheckResult(GA_STATUS, Status.PASS, null, "${seen}: guest agent answered a no-op command${probeEvidence(vm)}.".toString())
			case 'no-response':
				// Lab finding (9.1.0.1): Morpheus runs Windows guest-exec through the QEMU guest agent, but on Linux it
				// routes the command through its own Morpheus agent, so a Linux non-response says nothing about the
				// guest agent. Only Windows non-responses count as a guest agent failure.
				if (vm.isWindows()) {
					return new CheckResult(GA_STATUS, Status.FAIL, Severity.WARNING, "${seen}: guest agent did not answer a no-op command${probeEvidence(vm)}.".toString())
				}
				return new CheckResult(GA_STATUS, Status.UNKNOWN, null, ("${seen}. The probe did not get an answer, but on Linux Morpheus sends " +
					"commands through its own agent rather than the guest agent, so this doesn't show the guest agent's state${probeEvidence(vm)}.").toString())
			case 'not-probed':
				return new CheckResult(GA_STATUS, Status.UNKNOWN, null, "${seen}. Not probed: ${vm.gaProbeReason ?: 'no reason given'}.".toString())
			default:
				return new CheckResult(GA_STATUS, Status.UNKNOWN, null, "${seen}. Not probed.".toString())
		}
	}

	/** " (success true, exit code 0; error ...)" from the probe's evidence, or "" when it has none. */
	static String probeEvidence(VmFacts vm) {
		List<String> parts = []
		if (vm.gaProbeSuccess != null) parts.add("success ${vm.gaProbeSuccess}".toString())
		if (vm.gaProbeExitCode != null) parts.add("exit code ${vm.gaProbeExitCode}".toString())
		String s = parts.join(', ')
		if (vm.gaProbeError) s = s ? "${s}; ${vm.gaProbeError}".toString() : vm.gaProbeError
		return s ? " (${s})".toString() : ''
	}

	static CheckResult morpheusAgent(VmFacts vm, ScanContext ctx) {
		if (vm.agentInstalled != Boolean.TRUE) {
			String why = VmFacts.bool(vm.config.get('noAgent')) == Boolean.TRUE ? ' (provisioned with the agent install skipped: config.noAgent = true)' : ''
			return new CheckResult(MORPHEUS_AGENT, Status.FAIL, Severity.INFO, "agentInstalled ${vm.agentInstalled == null ? 'unknown' : 'false'}${why}.")
		}
		List<String> problems = []
		Severity sev = Severity.INFO
		String fam = vm.osFamilyKey
		String base = fam ? ctx.agentBaseline.get(fam) : null
		String have = "agentVersion ${vm.agentVersion ?: 'unknown'}"
		if (base && vm.agentVersion && ScanContext.compareVersions(vm.agentVersion, base) < 0) {
			problems.add("older than the newest ${fam} agent in this fleet (${base})".toString())
		}
		long silent = ctx.daysSince(vm.lastAgentUpdate)
		if (vm.poweredOn && silent > AGENT_SILENT_DAYS) {
			problems.add("no agent update for ${silent} days on a running VM".toString())
			sev = Severity.WARNING
		}
		if (!ctx.baselineReady && problems.isEmpty()) {
			return new CheckResult(MORPHEUS_AGENT, Status.UNKNOWN, null, "Agent installed, ${have}; the fleet agent baseline isn't ready yet, so the version wasn't compared.".toString(),
				['The plugin computes the newest agent per OS family in the background; open the tab again in a minute.'])
		}
		List<String> notes = base ? [] : ['No fleet baseline for this OS family, so the version was not compared.']
		if (problems.isEmpty()) return new CheckResult(MORPHEUS_AGENT, Status.PASS, null, "Agent installed, ${have}.", notes)
		return new CheckResult(MORPHEUS_AGENT, Status.FAIL, sev, "Agent installed, ${have}: " + problems.join('; ') + '.', notes)
	}

	// ---- storage ----

	static CheckResult snapshots(VmFacts vm, ScanContext ctx) {
		if (vm.snapshots == null) return new CheckResult(SNAPSHOTS, Status.UNKNOWN, null, 'Snapshots could not be read for this VM.')
		int n = vm.snapshots.size()
		if (n == 0) return new CheckResult(SNAPSHOTS, Status.PASS, null, 'No snapshots.')
		List<Date> dates = vm.snapshots.collect { VmFacts.Snap s -> s.created }.findAll { it != null } as List<Date>
		Date oldest = dates ? dates.min() : null
		long age = ctx.daysSince(oldest)
		String seen = "${n} snapshot${n == 1 ? '' : 's'}" + (oldest ? ", oldest ${age} days old (${iso(oldest)})" : ', dates unknown')
		if (age > SNAPSHOT_WARN_DAYS) return new CheckResult(SNAPSHOTS, Status.FAIL, Severity.WARNING, seen + '.')
		if (age > SNAPSHOT_INFO_DAYS || n > SNAPSHOT_INFO_COUNT) return new CheckResult(SNAPSHOTS, Status.FAIL, Severity.INFO, seen + '.')
		if (oldest == null) return new CheckResult(SNAPSHOTS, Status.UNKNOWN, null, seen + ': age could not be judged.')
		return new CheckResult(SNAPSHOTS, Status.PASS, null, seen + ' (within the plugin\'s thresholds).')
	}

	static String iso(Date d) {
		java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
		f.setTimeZone(TimeZone.getTimeZone('UTC'))
		return f.format(d)
	}

	static CheckResult diskAllocation(VmFacts vm) {
		List<VmFacts.Volume> measured = vm.disks.findAll { VmFacts.Volume v -> (v.maxStorage ?: 0L) > 0L && (v.usedStorage ?: 0L) > 0L }
		if (measured.isEmpty()) return new CheckResult(DISK_ALLOCATION, Status.UNKNOWN, null, 'Morpheus reports no used size for this VM\'s disks.')
		List<String> full = []
		for (VmFacts.Volume v : measured) {
			double r = (double) v.usedStorage / (double) v.maxStorage
			if (r >= ALLOCATION_RATIO) full.add("'${v.name ?: '?'}' ${gb(v.usedStorage)} of ${gb(v.maxStorage)} (${Math.round(r * 100)}%)".toString())
		}
		if (full.isEmpty()) return new CheckResult(DISK_ALLOCATION, Status.PASS, null, 'Used size is well below provisioned size on every measured disk.')
		return new CheckResult(DISK_ALLOCATION, Status.FAIL, null, 'Possibly thick: ' + full.join(', ') + '.')
	}

	static String gb(Long bytes) {
		return String.format(Locale.US, '%.1f GB', (bytes ?: 0L) / 1073741824d)
	}

	static CheckResult discovered(VmFacts vm) {
		String t = vm.serverType ?: 'unknown'
		if (vm.managed) return new CheckResult(DISCOVERED, Status.PASS, null, "Managed (server type ${t}).")
		return new CheckResult(DISCOVERED, Status.FAIL, null, "Server type ${t}: VM not managed by Morpheus, so it lacks the Morpheus defaults.")
	}

	static CheckResult isoAttached(VmFacts vm) {
		List<VmFacts.Volume> cds = vm.cdDrives
		if (cds.isEmpty()) return new CheckResult(ISO_ATTACHED, Status.PASS, null, 'No CD-ROM drive.')
		List<String> hits = []
		for (VmFacts.Volume v : cds) {
			if (v.sourceImage) hits.add("'${v.name ?: 'CD-ROM'}': sourceImage ${v.sourceImage}".toString())
			else if (v.volumePath && (v.maxStorage ?: 0L) > 0L) hits.add("'${v.name ?: 'CD-ROM'}': volumePath set, ${gb(v.maxStorage)}".toString())
		}
		if (hits.isEmpty()) return new CheckResult(ISO_ATTACHED, Status.PASS, null, "${cds.size()} CD-ROM drive${cds.size() == 1 ? '' : 's'}, all empty.")
		return new CheckResult(ISO_ATTACHED, Status.FAIL, null, 'ISO attached: ' + hits.join('; ') + '.')
	}
}
