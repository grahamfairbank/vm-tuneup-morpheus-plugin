package io.github.grahamfairbank.vmtuneup.core.hostcpu

import groovy.transform.CompileStatic
import groovy.transform.ToString

/** Outcome of one host CPU finding, from best to worst. UNKNOWN ranks above INFO and PASS: missing data never passes. */
@CompileStatic
enum HostCpuStatus {
	PASS, INFO, UNKNOWN, WARNING, CRITICAL

	static HostCpuStatus worst(Collection<HostCpuStatus> all) {
		HostCpuStatus w = null
		for (HostCpuStatus s : all) if (s != null && (w == null || s.ordinal() > w.ordinal())) w = s
		return w ?: UNKNOWN
	}
}

/** How the cluster's CPU setting ({@code config.cpuModel}) asks libvirt to build the guest CPU. */
@CompileStatic
enum ClusterCpuMode {
	/** Guest sees the host CPU as is (HVM's default). */
	HOST_PASSTHROUGH,
	/** libvirt's closest named model plus the host's extra features, decided on the host the VM starts on. */
	HOST_MODEL,
	/** libvirt's "maximum": every feature the host and QEMU can offer. Treated like host passthrough. */
	MAXIMUM,
	/** One libvirt model name, the same on every host. */
	NAMED,
	/** No value. HVM's documented default is host passthrough. */
	NOT_SET

	static ClusterCpuMode of(String setting) {
		String s = setting?.trim()
		if (!s) return NOT_SET
		String n = s.toLowerCase().replaceAll(/[\s_]+/, '-')
		if (n == 'host-passthrough' || n == 'passthrough') return HOST_PASSTHROUGH
		if (n == 'host-model') return HOST_MODEL
		if (n == 'maximum') return MAXIMUM
		return NAMED
	}

	/** True for the modes whose guest CPU depends on the host the VM runs on. */
	boolean isHostDependent() { this != NAMED }
}

/** The named model recommended for a cluster: the newest one usable on every host that could be read. */
@CompileStatic
@ToString(includeNames = true, includes = ['name', 'canonical', 'family'])
final class ModelChoice {
	/** Model name to set in Morpheus (the descriptive alias when libvirt has one, e.g. Broadwell-noTSX-IBRS). */
	final String name
	/** libvirt's versioned name for the same model (Broadwell-v4), or the name itself. */
	final String canonical
	final String family
	/** Every name for this exact model usable on all hosts (aliases), sorted. */
	final List<String> sameModelNames
	/** False when some hosts couldn't be read, so "usable on all hosts" only covers the hosts that were read. */
	final boolean coversAllHosts
	/** Why this model: plain words, including what rules out the next newer generation. */
	final String explanation

	ModelChoice(String name, String canonical, String family, List<String> sameModelNames, boolean coversAllHosts, String explanation) {
		this.name = name
		this.canonical = canonical
		this.family = family
		this.sameModelNames = Collections.unmodifiableList(new ArrayList<String>(sameModelNames ?: [name]))
		this.coversAllHosts = coversAllHosts
		this.explanation = explanation
	}
}

/** One finding about the cluster's hosts, with the evidence per host. */
@CompileStatic
@ToString(includeNames = true, includes = ['code', 'status'])
final class HostCpuFinding {
	/** Stable code: VENDOR-MIX, ARCH-MIX, HOSTS-UNREAD, HOSTS-DIFFER, HOSTS-IDENTICAL, SINGLE-HOST, MODEL-UNUSABLE,
	 * MODEL-USABILITY-UNKNOWN, MODEL-DEPRECATED, MODEL-NEWER, MODEL-OK, NO-COMMON-MODEL, NO-HOSTS. */
	final String code
	final HostCpuStatus status
	final String title
	/** What was found and why it matters, in plain words. */
	final String text
	/** What to do, or null when nothing needs doing. */
	final String recommendation
	/** Host label → what that host showed, in host order. */
	final Map<String, String> evidence

	HostCpuFinding(String code, HostCpuStatus status, String title, String text, String recommendation, Map<String, String> evidence) {
		this.code = code
		this.status = status
		this.title = title
		this.text = text
		this.recommendation = recommendation
		this.evidence = Collections.unmodifiableMap(new LinkedHashMap<String, String>(evidence ?: [:]))
	}
}

/** The host CPU check for one cluster: overall status, the findings, and the recommended named model. */
@CompileStatic
@ToString(includeNames = true, includes = ['clusterCpuModel', 'mode', 'status'])
final class HostCpuResult {
	/** The cluster's setting as read ({@code config.cpuModel}), or null. */
	final String clusterCpuModel
	final ClusterCpuMode mode
	/** Worst status of the findings. */
	final HostCpuStatus status
	/** Worst first. */
	final List<HostCpuFinding> findings
	/** Best named model usable on every host read, or null (vendor mix, no common model, nothing read). */
	final ModelChoice bestModel
	/** Host label → one-line CPU summary. */
	final Map<String, String> hosts

	HostCpuResult(String clusterCpuModel, ClusterCpuMode mode, List<HostCpuFinding> findings, ModelChoice bestModel, Map<String, String> hosts) {
		this.clusterCpuModel = clusterCpuModel
		this.mode = mode
		List<HostCpuFinding> f = new ArrayList<HostCpuFinding>(findings ?: Collections.<HostCpuFinding>emptyList())
		f.sort { HostCpuFinding a, HostCpuFinding b -> b.status.ordinal() <=> a.status.ordinal() }
		this.findings = Collections.unmodifiableList(f)
		this.status = HostCpuStatus.worst(f*.status)
		this.bestModel = bestModel
		this.hosts = Collections.unmodifiableMap(new LinkedHashMap<String, String>(hosts ?: [:]))
	}

	HostCpuFinding finding(String code) { findings.find { it.code == code } }

	List<String> getCodes() { findings*.code }
}
