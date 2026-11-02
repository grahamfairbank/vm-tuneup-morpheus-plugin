package io.github.grahamfairbank.vmtuneup.core.hostcpu

import groovy.transform.CompileStatic

/**
 * Reconciles a cluster's CPU setting ({@code config.cpuModel}) with what every host can really offer. Microcode and
 * patch levels change which libvirt models a host can run (Intel TSX removed → "Broadwell" unusable,
 * "Broadwell-noTSX" usable), so the answer comes from each host's own {@code virsh domcapabilities}, not from the
 * CPU name. Pure logic: no Morpheus types, no I/O.
 *
 * Findings: VENDOR-MIX and ARCH-MIX (critical); HOSTS-UNREAD (unknown, never a pass); for host passthrough,
 * host-model and maximum: HOSTS-DIFFER (warning), HOSTS-IDENTICAL or SINGLE-HOST (pass); for a named model:
 * MODEL-UNUSABLE (critical), MODEL-USABILITY-UNKNOWN (unknown), MODEL-DEPRECATED (warning), MODEL-NEWER (info),
 * MODEL-OK (pass); NO-COMMON-MODEL (warning) when a recommendation is needed and none exists; NO-HOSTS (unknown).
 */
@CompileStatic
final class HostCpuReconciler {

	private HostCpuReconciler() {}

	static final String HOW_IN_MORPHEUS = 'In the Morpheus UI: Infrastructure > Clusters > (this cluster) > Edit > CPU Model (an editable cluster setting; that changing it on an existing cluster applies to running VMs at their next full stop and start is not yet verified).'
	static final String APPLIES_WHEN = 'A new CPU model reaches each VM at its next full stop and start, not a guest reboot (not yet verified).'
	static final String TEST_BOTH_WAYS = 'Then test a live migration both ways between two different hosts.'
	static final Set<String> TSX = Collections.unmodifiableSet(new TreeSet<String>(['hle', 'rtm']))
	static final String TSX_NOTE = 'hle and rtm are Intel TSX. Microcode updates have removed TSX from many Intel CPUs (Haswell to ' +
		'Cascade Lake), so a model with TSX that worked before an update stops being usable after it. The -noTSX variant of the ' +
		'same model avoids this.'

	/**
	 * @param clusterCpuModel the cluster's {@code config.cpuModel}: host-passthrough, host-model, maximum, a libvirt
	 *        model name, or null/blank (HVM's default, host passthrough)
	 * @param hosts every host in the cluster, including ones that couldn't be read ({@link HostCpu#failed})
	 */
	static HostCpuResult reconcile(String clusterCpuModel, List<HostCpu> hosts) {
		List<HostCpu> all = new ArrayList<HostCpu>()
		for (HostCpu h : (hosts ?: Collections.<HostCpu>emptyList())) if (h != null) all.add(h)
		String setting = clusterCpuModel?.trim() ?: null
		ClusterCpuMode mode = ClusterCpuMode.of(setting)
		Map<String, String> summary = new LinkedHashMap<String, String>()
		for (HostCpu h : all) summary[h.label] = h.describe()
		List<HostCpuFinding> f = []

		if (all.isEmpty()) {
			f << new HostCpuFinding('NO-HOSTS', HostCpuStatus.UNKNOWN, 'No hosts to check',
				'The cluster has no hosts, or none were given to the check, so the CPU setting can\'t be checked.', null, null)
			return new HostCpuResult(setting, mode, f, null, summary)
		}

		unread(all, f)
		List<HostCpu> read = all.findAll { it.readable }
		boolean vendorMix = vendorMix(read, f)
		boolean archMix = archMix(read, f)
		String vendor = commonVendor(read)
		ModelChoice best = (vendorMix || archMix) ? null : pickBest(all, vendor)

		if (mode.hostDependent) hostDependent(mode, all, vendorMix || archMix, best, f)
		else named(setting, all, vendorMix || archMix, best, f)
		return new HostCpuResult(setting, mode, f, best, summary)
	}

	// ---- unread hosts, vendor and architecture

	private static void unread(List<HostCpu> all, List<HostCpuFinding> f) {
		Map<String, String> ev = new LinkedHashMap<String, String>()
		for (HostCpu h : all) {
			if (!h.readable) ev[h.label] = "Not read: ${h.error ?: 'no output'}".toString()
			else if (!h.capabilitiesRead) ev[h.label] = "Host CPU (virsh capabilities) not read: ${problemText(h, 'virsh capabilities')}".toString()
			else if (!h.modelsKnown) ev[h.label] = "Named CPU models (virsh domcapabilities) not read: ${problemText(h, 'virsh domcapabilities')}".toString()
		}
		if (ev.isEmpty()) return
		f << new HostCpuFinding('HOSTS-UNREAD', HostCpuStatus.UNKNOWN,
			ev.size() == 1 ? 'One host couldn\'t be read' : "${ev.size()} hosts couldn't be read".toString(),
			"${ev.size()} of ${all.size()} hosts couldn't be read fully, so this check can't confirm that every host can run the " +
				'cluster\'s CPU setting. The other findings cover only the hosts that were read.',
			'Check that each host is online and that the scan can run virsh as root on it (virsh domcapabilities needs a ' +
				'read-write connection), then run the check again.', ev)
	}

	private static String problemText(HostCpu h, String prefix) {
		List<String> p = h.problems.findAll { it.startsWith(prefix) }
		return p ? p.join('; ') : (h.error ?: 'no output')
	}

	private static boolean vendorMix(List<HostCpu> read, List<HostCpuFinding> f) {
		Map<String, List<String>> byVendor = new LinkedHashMap<String, List<String>>()
		for (HostCpu h : read) if (h.vendor) byVendor.computeIfAbsent(h.vendor) { new ArrayList<String>() } << h.label
		if (byVendor.size() < 2) return false
		Map<String, String> ev = new LinkedHashMap<String, String>()
		for (HostCpu h : read) ev[h.label] = "${h.vendor ?: 'unknown vendor'} ${h.cpuModel ?: ''}".toString().trim()
		String groups = byVendor.collect { String v, List<String> labels -> "${v}: ${labels.join(', ')}".toString() }.join('; ')
		f << new HostCpuFinding('VENDOR-MIX', HostCpuStatus.CRITICAL, 'Hosts with different CPU vendors in one cluster',
			"This cluster mixes CPU vendors (${groups}). KVM can't live-migrate a running VM between Intel and AMD CPUs with " +
				'any CPU setting, so live migration and maintenance evacuation between these hosts can\'t work. A VM that ' +
				'fails over or is moved cold starts on a different CPU vendor, which some guests notice (for example ' +
				'software licensing or Windows activation).',
			'Keep hosts of one CPU vendor per cluster: move the hosts of the other vendor to a cluster of their own.', ev)
		return true
	}

	private static boolean archMix(List<HostCpu> read, List<HostCpuFinding> f) {
		Set<String> arches = new TreeSet<String>()
		for (HostCpu h : read) if (h.arch) arches << h.arch
		if (arches.size() < 2) return false
		Map<String, String> ev = new LinkedHashMap<String, String>()
		for (HostCpu h : read) ev[h.label] = h.arch ?: 'unknown architecture'
		f << new HostCpuFinding('ARCH-MIX', HostCpuStatus.CRITICAL, 'Hosts with different CPU architectures in one cluster',
			"This cluster mixes CPU architectures (${arches.join(', ')}). VMs can't move between them at all.",
			'Keep hosts of one architecture per cluster.', ev)
		return true
	}

	private static String commonVendor(List<HostCpu> read) {
		Set<String> v = new LinkedHashSet<String>()
		for (HostCpu h : read) if (h.vendor) v << h.vendor
		return v.size() == 1 ? v.iterator().next() : null
	}

	// ---- host passthrough, host-model, maximum

	private static String modeWords(ClusterCpuMode mode) {
		switch (mode) {
			case ClusterCpuMode.HOST_MODEL: return 'host-model'
			case ClusterCpuMode.MAXIMUM: return 'maximum (every host feature, like host passthrough)'
			case ClusterCpuMode.NOT_SET: return 'no CPU setting, so HVM\'s default, host passthrough'
			default: return 'host passthrough'
		}
	}

	private static void hostDependent(ClusterCpuMode mode, List<HostCpu> all, boolean mixed, ModelChoice best, List<HostCpuFinding> f) {
		List<HostCpu> cmp = all.findAll { it.capabilitiesRead || it.hostModelName }
		String words = modeWords(mode)
		if (all.size() == 1 && cmp.size() == 1) {
			f << new HostCpuFinding('SINGLE-HOST', HostCpuStatus.PASS, 'One host: CPU setting is fine for now',
				"The cluster uses ${words} and has one host, so there is no live migration between hosts to break.".toString(),
				'Before you add a host with a different CPU or microcode, switch the cluster to a named model' +
					(best ? ": ${best.name} is the newest one this host can run. ${HOW_IN_MORPHEUS}".toString() : ". ${HOW_IN_MORPHEUS}".toString()),
				[(cmp[0].label): cmp[0].describe()])
			return
		}
		if (mixed || cmp.size() < 2) return

		Map<String, Map<String, String>> diffs = differences(cmp)
		Map<String, String> ev = new LinkedHashMap<String, String>()
		if (diffs.isEmpty()) {
			for (HostCpu h : cmp) ev[h.label] = h.describe()
			String hm = cmp[0].hostModelName ? " and host-model (${cmp[0].hostModelName})".toString() : ''
			String count = cmp.size() == all.size() ? "All ${cmp.size()} hosts".toString() : "The ${cmp.size()} hosts that could be read".toString()
			f << new HostCpuFinding('HOSTS-IDENTICAL', HostCpuStatus.PASS,
				"Identical hosts: ${mode == ClusterCpuMode.HOST_MODEL ? 'host-model' : mode == ClusterCpuMode.MAXIMUM ? 'maximum' : 'host passthrough'} is fine".toString(),
				("${count} report the same CPU: same model, features, microcode${hm}. The cluster uses ${words}. Because the hosts are " +
					'identical, that is fine: VMs get the full CPU of the hosts and can live-migrate between any of them.').toString(),
				'The CPU Model setting will need to change before you add a host with a different CPU or microcode: set it to a ' +
					'named model that every host can run' +
					(best ? " (today that is ${best.name}). ${HOW_IN_MORPHEUS}".toString() : ". ${HOW_IN_MORPHEUS}".toString()) +
					' Until then, VMs can\'t live-migrate to or from the new host. Update microcode on all hosts together, so they stay identical.',
				ev)
			return
		}

		// Evidence: each host's CPU, plus the extra features it lacks compared with the others.
		Set<String> union = new TreeSet<String>()
		for (HostCpu h : cmp) if (h.capabilitiesRead) union.addAll(h.features)
		Set<String> missingAnywhere = new TreeSet<String>()
		for (HostCpu h : cmp) {
			String line = h.describe()
			if (h.capabilitiesRead) {
				Set<String> lacks = new TreeSet<String>(union)
				lacks.removeAll(h.features)
				missingAnywhere.addAll(lacks)
				if (lacks) line += "; lacks ${lacks.join(', ')}".toString()
			}
			ev[h.label] = line
		}
		List<String> aspects = new ArrayList<String>(diffs.keySet())
		StringBuilder text = new StringBuilder("The cluster uses ${words}, but its hosts' CPUs are not the same: ${aspects.join(', ')} differ. ".toString())
		if (mode == ClusterCpuMode.HOST_MODEL) {
			text << 'With host-model, libvirt builds each VM\'s CPU from the host it starts on. A VM started on a host with more ' +
				'features can\'t live-migrate to a host with fewer, and VMs started on different hosts see different CPUs.'
		} else {
			text << 'A VM sees the exact CPU of the host it started on. Moving it live to a host with fewer features can fail, or ' +
				'the guest can see CPU features change under it and crash. HPE notes that the migratable flag is not a ' +
				'compatibility guarantee.'
		}
		if (aspects == ['microcode']) {
			text << ' Only the microcode differs today. Microcode updates can remove CPU features (Intel removed TSX this way), ' +
				'so hosts on different microcode can stop matching after an update.'
		}
		if (missingAnywhere.any { TSX.contains(it) }) text << ' ' << TSX_NOTE
		f << new HostCpuFinding('HOSTS-DIFFER', HostCpuStatus.WARNING, 'Host-dependent CPU setting on hosts that differ',
			text.toString(), recommend(best, 'Set the cluster to the named model'), ev)
		if (best == null) noCommonModel(cmp, f)
	}

	/** Aspect → (host → value) for each aspect that isn't the same on every host that reports it. */
	private static Map<String, Map<String, String>> differences(List<HostCpu> hosts) {
		Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>()
		aspect(out, 'CPU model', hosts) { HostCpu h -> h.cpuModel }
		aspect(out, 'CPU signature', hosts) { HostCpu h -> h.signature }
		aspect(out, 'microcode', hosts) { HostCpu h -> h.microcodeHex }
		aspect(out, 'CPU features', hosts) { HostCpu h -> h.capabilitiesRead ? h.features.join(' ') : null }
		aspect(out, 'host-model', hosts) { HostCpu h -> h.hostModelName }
		aspect(out, 'host-model features', hosts) { HostCpu h -> h.hostModelName ? h.hostModelFeatures.join(' ') : null }
		return out
	}

	private static void aspect(Map<String, Map<String, String>> out, String name, List<HostCpu> hosts, Closure<String> value) {
		Map<String, String> values = new LinkedHashMap<String, String>()
		for (HostCpu h : hosts) {
			String v = value.call(h)
			if (v != null) values[h.label] = v
		}
		if (new HashSet<String>(values.values()).size() > 1) out[name] = values
	}

	// ---- a named model

	private static void named(String want, List<HostCpu> all, boolean mixed, ModelChoice best, List<HostCpuFinding> f) {
		List<HostCpu> mh = all.findAll { it.modelsKnown }
		if (mh.isEmpty()) return
		Map<String, String> ev = new LinkedHashMap<String, String>()
		List<String> cannot = [], unknown = [], deprecated = []
		Set<String> blockers = new TreeSet<String>()
		for (HostCpu h : mh) {
			CpuModelInfo m = h.model(want)
			if (m == null) {
				cannot << h.label
				ev[h.label] = "libvirt on this host doesn't know a model named ${want}".toString()
			} else if (m.usable == Usability.NO) {
				cannot << h.label
				blockers.addAll(m.blockers)
				ev[h.label] = m.blockers ? "${m.name}: not usable, host lacks ${m.blockers.join(', ')}".toString() :
					"${m.name}: not usable (libvirt didn't say which features are missing)".toString()
			} else if (m.usable == Usability.UNKNOWN) {
				unknown << h.label
				ev[h.label] = "${m.name}: libvirt can't tell whether it is usable".toString()
			} else {
				ev[h.label] = "${m.name}: usable${m.deprecated ? ', but deprecated' : ''}".toString()
				if (m.deprecated) deprecated << h.label
			}
		}
		if (cannot) {
			StringBuilder t = new StringBuilder("The cluster CPU model is ${want}, but ${cannot.size()} of ${mh.size()} hosts can't run it: ${cannot.join(', ')}. ".toString())
			t << 'A VM using this model won\'t start on those hosts, and live migration, maintenance evacuation or failover to them fails.'
			if (blockers) t << " libvirt reports these missing CPU features: ${blockers.join(', ')}.".toString()
			if (blockers.any { TSX.contains(it) }) t << ' ' << TSX_NOTE
			f << new HostCpuFinding('MODEL-UNUSABLE', HostCpuStatus.CRITICAL, "CPU model ${want} isn't usable on every host".toString(),
				t.toString(), recommend(best, 'Change the cluster to'), ev)
			if (best == null && !mixed) noCommonModel(mh, f)
		}
		if (unknown) {
			f << new HostCpuFinding('MODEL-USABILITY-UNKNOWN', HostCpuStatus.UNKNOWN, "Can't tell whether ${want} is usable everywhere".toString(),
				"libvirt can't tell whether ${want} is usable on ${unknown.join(', ')}.".toString(),
				'Run the check again; if it stays unknown, check the host\'s libvirt and QEMU versions.', ev)
		}
		if (deprecated) {
			f << new HostCpuFinding('MODEL-DEPRECATED', HostCpuStatus.WARNING, "CPU model ${want} is deprecated".toString(),
				"libvirt marks ${want} as deprecated on ${deprecated.join(', ')}. A future QEMU update may drop it, and VMs using it would then fail to start.".toString(),
				recommend(best, 'Plan a change to'), ev)
		}
		// With a vendor or architecture mix, "usable everywhere" would read as a pass for something that can't migrate.
		if (cannot || unknown || deprecated || mixed) return

		CpuModelInfo cur = mh[0].model(want)
		CpuModelInfo bestInfo = best ? mh[0].model(best.name) : null
		String count = mh.size() == all.size() ? "all ${mh.size()} hosts".toString() : "the ${mh.size()} hosts that could be read".toString()
		// A model of a family the table doesn't know may be newer than the table: only generic models (libvirt vendor
		// 'unknown', such as kvm64) are compared with it.
		boolean comparable = CpuGenerations.rank(cur.name) >= 0 || cur.vendor == 'unknown'
		if (bestInfo != null && comparable && bestInfo.canonicalName != cur.canonicalName && CpuGenerations.compare(bestInfo, cur) > 0) {
			f << new HostCpuFinding('MODEL-NEWER', HostCpuStatus.INFO, "A newer CPU model than ${want} is usable on every host".toString(),
				"${want} is usable on ${count}. ${best.name} is also usable on every host and is newer, so VMs would get more CPU features (and newer security fixes). ${best.explanation}".toString(),
				"Optional: change the cluster to ${best.name}. ${HOW_IN_MORPHEUS} ${APPLIES_WHEN} ${TEST_BOTH_WAYS} Keep ${want} if you plan to add older hosts.".toString(), ev)
		} else {
			String extra = CpuGenerations.rank(cur.name) < 0 ? ' VM Tune-Up can\'t rank this model\'s generation, so it doesn\'t suggest a newer one.' :
				(best ? ' It is the newest model every host can run.' : '')
			f << new HostCpuFinding('MODEL-OK', HostCpuStatus.PASS, "CPU model ${want} is usable on every host".toString(),
				"${want} is usable on ${count}, so VMs can start on and live-migrate between them.${extra}".toString(),
				null, ev)
		}
	}

	private static String recommend(ModelChoice best, String verb) {
		if (best == null) return 'No named CPU model is usable on every host, so no single setting covers this cluster. Split the hosts into clusters of matching CPUs.'
		String scope = best.coversAllHosts ? '' : ' (checked on the hosts that could be read; check the others before changing)'
		return "${verb} ${best.name}, the newest named model every host can run${scope}. ${best.explanation} ${HOW_IN_MORPHEUS} ${APPLIES_WHEN} ${TEST_BOTH_WAYS}".toString()
	}

	private static void noCommonModel(List<HostCpu> hosts, List<HostCpuFinding> f) {
		if (f.any { it.code == 'NO-COMMON-MODEL' }) return
		Map<String, String> ev = new LinkedHashMap<String, String>()
		for (HostCpu h : hosts) ev[h.label] = h.describe()
		f << new HostCpuFinding('NO-COMMON-MODEL', HostCpuStatus.WARNING, 'No named CPU model fits every host',
			'VM Tune-Up found no named CPU model (from a known Intel or AMD generation) that every host can run.',
			'Split the hosts into clusters of matching CPUs.', ev)
	}

	// ---- best model usable on all hosts

	/**
	 * The newest named model usable on every host whose model list was read: newest generation first, then the
	 * latest libvirt version of it; deprecated models and models of another vendor skipped. Null when nothing fits.
	 */
	static ModelChoice pickBest(List<HostCpu> all, String hostVendor) {
		List<HostCpu> mh = (all ?: Collections.<HostCpu>emptyList()).findAll { it != null && it.modelsKnown }
		if (mh.isEmpty() || !hostVendor) return null
		List<CpuModelInfo> candidates = []
		List<String> skippedDeprecated = []
		for (CpuModelInfo m : mh[0].models.values()) {
			String fv = CpuGenerations.vendor(m.name)
			if (fv == null || !CpuGenerations.sameVendor(hostVendor, fv)) continue
			boolean everywhere = true
			boolean dep = false
			for (HostCpu h : mh) {
				CpuModelInfo hm = h.model(m.name)
				if (hm == null || hm.usable != Usability.YES) { everywhere = false; break }
				if (hm.deprecated) dep = true
			}
			if (!everywhere) continue
			if (dep) { skippedDeprecated << m.name; continue }
			candidates << m
		}
		if (candidates.isEmpty()) return null
		CpuModelInfo top = candidates[0]
		for (CpuModelInfo c : candidates) {
			int cmp = CpuGenerations.compare(c, top)
			if (cmp > 0 || (cmp == 0 && better(c, top))) top = c
		}
		String family = CpuGenerations.family(top.name)
		String canonical = top.canonicalName
		List<String> same = candidates.findAll { it.canonicalName == canonical && CpuGenerations.family(it.name) == family }*.name.sort()
		String explanation = explain(top, family, mh, skippedDeprecated.findAll { CpuGenerations.rank(it) >= CpuGenerations.rank(top.name) })
		return new ModelChoice(top.name, canonical, family, same, mh.size() == all.size(), explanation)
	}

	/** Same rank and version: prefer the descriptive alias (Broadwell-noTSX-IBRS) over the versioned name, then by name. */
	private static boolean better(CpuModelInfo a, CpuModelInfo b) {
		boolean av = CpuGenerations.isVersioned(a.name), bv = CpuGenerations.isVersioned(b.name)
		if (av != bv) return !av
		return a.name < b.name
	}

	private static String explain(CpuModelInfo top, String family, List<HostCpu> mh, List<String> skippedDeprecated) {
		StringBuilder s = new StringBuilder()
		String alias = top.canonical && top.canonical != top.name ? " (libvirt's ${top.canonical})".toString() : ''
		s << "${top.name}${alias} is from ${family}, the newest CPU generation every host can run, and is the latest ${family} version usable on all of them. ".toString()
		s << 'Later versions add security features and drop Intel TSX, which microcode updates have removed on many CPUs.'
		// What rules out the next newer generation libvirt knows on these hosts.
		String vendor = CpuGenerations.vendor(top.name)
		List<String> fams = CpuGenerations.mainline(vendor)
		String next = null
		int at = fams.indexOf(family)
		for (int i = at + 1; at >= 0 && i < fams.size() && next == null; i++) {
			String fam = fams[i]
			if (mh[0].models.keySet().any { CpuGenerations.family(it) == fam }) next = fam
		}
		if (next != null) {
			List<String> why = []
			for (HostCpu h : mh) {
				Collection<CpuModelInfo> variants = h.models.values().findAll { CpuGenerations.family(it.name) == next }
				if (variants.any { it.usable == Usability.YES }) continue
				CpuModelInfo closest = variants.min { CpuModelInfo v -> v.blockers ? v.blockers.size() : Integer.MAX_VALUE }
				why << (closest?.blockers ? "${h.label} lacks ${closest.blockers.join(', ')}".toString() : "${h.label} can't run it".toString())
			}
			if (why) s << " The next generation, ${next}, is ruled out: ${why.join('; ')}.".toString()
		}
		if (skippedDeprecated) s << " Skipped as deprecated: ${skippedDeprecated.join(', ')}.".toString()
		return s.toString()
	}
}
