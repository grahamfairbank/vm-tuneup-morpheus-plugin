package io.github.grahamfairbank.vmtuneup.core.hostcpu

import groovy.transform.CompileStatic
import groovy.transform.ToString
import org.w3c.dom.Document
import org.w3c.dom.Element

/** What libvirt says about one named CPU model on a host. */
@CompileStatic
enum Usability {
	YES, NO, UNKNOWN

	static Usability of(String s) {
		String v = s?.trim()?.toLowerCase()
		return v == 'yes' ? YES : v == 'no' ? NO : UNKNOWN
	}
}

/**
 * One named CPU model from {@code virsh domcapabilities} (mode 'custom'):
 * {@code <model usable='no' vendor='Intel' canonical='Broadwell-v1' deprecated='yes'>Broadwell</model>}, followed by
 * {@code <blockers model='Broadwell'><feature name='hle'/>...</blockers>} when it isn't usable.
 */
@CompileStatic
@ToString(includeNames = true)
final class CpuModelInfo {
	final String name
	final Usability usable
	/** libvirt's vendor for the model: Intel, AMD, Hygon, unknown (generic), ... May be null on old libvirt. */
	final String vendor
	/** The versioned name this one is an alias for (Broadwell-noTSX-IBRS → Broadwell-v4), or null. */
	final String canonical
	final boolean deprecated
	/** Host CPU features this model needs but the host lacks (libvirt's blockers), sorted. Empty when usable. */
	final List<String> blockers

	CpuModelInfo(String name, Usability usable, String vendor, String canonical, boolean deprecated, List<String> blockers) {
		this.name = name
		this.usable = usable ?: Usability.UNKNOWN
		this.vendor = vendor
		this.canonical = canonical
		this.deprecated = deprecated
		List<String> b = new ArrayList<String>(new TreeSet<String>(blockers ?: Collections.<String>emptyList()))
		this.blockers = Collections.unmodifiableList(b)
	}

	/** The canonical name when libvirt gives one, else the name itself. */
	String getCanonicalName() { canonical ?: name }
}

/**
 * The CPU facts of one HVM host, from {@code virsh capabilities} and {@code virsh domcapabilities --virttype kvm}.
 * Built with {@link #parse} (tolerant: whatever is missing stays null or empty and is listed in {@link #problems})
 * or {@link #failed} when the commands didn't run. Plain Java types only.
 */
@CompileStatic
@ToString(includeNames = true, includes = ['label', 'vendor', 'cpuModel', 'microcodeHex', 'hostModelName'])
final class HostCpu {

	/** How the host is named in reports (the Morpheus host name). */
	final String label
	/** Why nothing could be read (command failed, host unreachable), or null. */
	final String error
	/** Pieces that were missing or unreadable, in plain words. */
	final List<String> problems

	/** True when {@code virsh capabilities} parsed and had a host CPU section. */
	final boolean capabilitiesRead
	/** True when {@code virsh domcapabilities} parsed and had a CPU section. */
	final boolean domCapabilitiesRead

	// ---- from capabilities (host/cpu)
	final String arch
	/** libvirt's name for the host CPU, e.g. Broadwell-v4. The features below come on top of this model. */
	final String cpuModel
	final String vendor
	/** Microcode revision as libvirt reports it (decimal), or null. */
	final Long microcode
	final String signature
	/** Features libvirt lists on top of {@link #cpuModel}, sorted. */
	final SortedSet<String> features

	// ---- from domcapabilities (cpu/mode)
	final boolean hostPassthroughSupported
	final boolean hostModelSupported
	/** The model host-model would give a VM, e.g. Broadwell-noTSX-IBRS. */
	final String hostModelName
	final String hostModelFallback
	final String hostModelVendor
	/** Features host-model requires on top of {@link #hostModelName}, sorted. */
	final SortedSet<String> hostModelFeatures
	/** Named models (mode 'custom'), by name, in libvirt's order. */
	final Map<String, CpuModelInfo> models

	private HostCpu(Map a) {
		this.label = (a.label ?: 'unnamed host').toString()
		this.error = a.error as String
		this.problems = Collections.unmodifiableList(new ArrayList<String>((a.problems ?: []) as List<String>))
		this.capabilitiesRead = a.capabilitiesRead as boolean
		this.domCapabilitiesRead = a.domCapabilitiesRead as boolean
		this.arch = a.arch as String
		this.cpuModel = a.cpuModel as String
		this.vendor = a.vendor as String
		this.microcode = a.microcode as Long
		this.signature = a.signature as String
		this.features = Collections.unmodifiableSortedSet(new TreeSet<String>((a.features ?: []) as Collection<String>))
		this.hostPassthroughSupported = a.hostPassthroughSupported as boolean
		this.hostModelSupported = a.hostModelSupported as boolean
		this.hostModelName = a.hostModelName as String
		this.hostModelFallback = a.hostModelFallback as String
		this.hostModelVendor = a.hostModelVendor as String
		this.hostModelFeatures = Collections.unmodifiableSortedSet(new TreeSet<String>((a.hostModelFeatures ?: []) as Collection<String>))
		Map<String, CpuModelInfo> m = new LinkedHashMap<String, CpuModelInfo>((a.models ?: [:]) as Map<String, CpuModelInfo>)
		this.models = Collections.unmodifiableMap(m)
	}

	/** A host whose commands didn't run or returned nothing. */
	static HostCpu failed(String label, String error) {
		return new HostCpu(label: label, error: clean(error) ?: 'no output from the host', problems: [])
	}

	/**
	 * Parses one host's output. Never throws for bad input: a missing or broken document leaves its part unread.
	 * @param error the collector's error text, if a command failed (kept even when some XML was read)
	 */
	static HostCpu parse(String label, String capabilitiesXml, String domCapabilitiesXml, String error = null) {
		Map a = [label: label, error: clean(error)]
		List<String> problems = []
		a.problems = problems

		if (capabilitiesXml?.trim()) {
			try {
				readCapabilities(SafeXml.parse(capabilitiesXml, 'virsh capabilities'), a, problems)
			} catch (SafeXml.XmlProblem e) {
				problems << e.message
			}
		} else {
			problems << 'virsh capabilities: no output'
		}
		if (domCapabilitiesXml?.trim()) {
			try {
				readDomCapabilities(SafeXml.parse(domCapabilitiesXml, 'virsh domcapabilities'), a, problems)
			} catch (SafeXml.XmlProblem e) {
				problems << e.message
			}
		} else {
			problems << 'virsh domcapabilities: no output'
		}
		if (!a.vendor && a.hostModelVendor) a.vendor = a.hostModelVendor
		if (!a.capabilitiesRead && !a.domCapabilitiesRead && !a.error) a.error = problems ? problems.join('; ') : 'nothing could be read'
		return new HostCpu(a)
	}

	private static void readCapabilities(Document doc, Map a, List<String> problems) {
		Element root = doc.documentElement
		if (root?.tagName != 'capabilities') { problems << 'virsh capabilities: unexpected document (no <capabilities>)'; return }
		Element cpu = SafeXml.child(SafeXml.child(root, 'host'), 'cpu')
		if (cpu == null) { problems << 'virsh capabilities: no host CPU section'; return }
		a.capabilitiesRead = true
		a.arch = SafeXml.text(SafeXml.child(cpu, 'arch'))
		a.cpuModel = SafeXml.text(SafeXml.child(cpu, 'model'))
		a.vendor = SafeXml.text(SafeXml.child(cpu, 'vendor'))
		String mc = SafeXml.attr(SafeXml.child(cpu, 'microcode'), 'version')
		if (mc) {
			try { a.microcode = Long.parseLong(mc) } catch (NumberFormatException ignored) { problems << "virsh capabilities: microcode '${mc}' is not a number".toString() }
		}
		Element sig = SafeXml.child(cpu, 'signature')
		if (sig != null) {
			a.signature = "family ${SafeXml.attr(sig, 'family') ?: '?'} model ${SafeXml.attr(sig, 'model') ?: '?'} stepping ${SafeXml.attr(sig, 'stepping') ?: '?'}".toString()
		}
		List<String> f = []
		for (Element e : SafeXml.children(cpu, 'feature')) {
			String n = SafeXml.attr(e, 'name')
			// A feature with policy='disable' is one the host model has but this CPU lacks: not a host feature.
			if (n && SafeXml.attr(e, 'policy') != 'disable') f << n
		}
		a.features = f
		if (!a.vendor) problems << 'virsh capabilities: no CPU vendor'
		if (!a.cpuModel) problems << 'virsh capabilities: no CPU model'
	}

	private static void readDomCapabilities(Document doc, Map a, List<String> problems) {
		Element root = doc.documentElement
		if (root?.tagName != 'domainCapabilities') { problems << 'virsh domcapabilities: unexpected document (no <domainCapabilities>)'; return }
		Element cpu = SafeXml.child(root, 'cpu')
		if (cpu == null) { problems << 'virsh domcapabilities: no CPU section'; return }
		a.domCapabilitiesRead = true
		String domain = SafeXml.text(SafeXml.child(root, 'domain'))
		if (domain && domain != 'kvm') problems << "virsh domcapabilities: reported for '${domain}', not kvm".toString()
		if (!a.arch) a.arch = SafeXml.text(SafeXml.child(root, 'arch'))
		boolean sawCustom = false
		for (Element mode : SafeXml.children(cpu, 'mode')) {
			String name = SafeXml.attr(mode, 'name')
			boolean supported = SafeXml.attr(mode, 'supported') == 'yes'
			if (name == 'host-passthrough') {
				a.hostPassthroughSupported = supported
			} else if (name == 'host-model') {
				a.hostModelSupported = supported
				Element model = SafeXml.child(mode, 'model')
				a.hostModelName = SafeXml.text(model)
				a.hostModelFallback = SafeXml.attr(model, 'fallback')
				a.hostModelVendor = SafeXml.text(SafeXml.child(mode, 'vendor'))
				List<String> f = []
				for (Element e : SafeXml.children(mode, 'feature')) {
					String n = SafeXml.attr(e, 'name')
					String policy = SafeXml.attr(e, 'policy') ?: 'require'
					if (n && policy == 'require') f << n
				}
				a.hostModelFeatures = f
			} else if (name == 'custom') {
				sawCustom = true
				a.models = readModels(mode)
			}
		}
		if (!sawCustom) problems << 'virsh domcapabilities: no named CPU models (mode custom)'
	}

	private static Map<String, CpuModelInfo> readModels(Element mode) {
		// Blockers are siblings of the models (libvirt writes each right after its model); collect them first.
		Map<String, List<String>> blockers = [:]
		for (Element b : SafeXml.children(mode, 'blockers')) {
			String m = SafeXml.attr(b, 'model')
			if (!m) continue
			List<String> list = blockers.computeIfAbsent(m) { new ArrayList<String>() }
			for (Element f : SafeXml.children(b, 'feature')) {
				String n = SafeXml.attr(f, 'name')
				if (n) list << n
			}
		}
		Map<String, CpuModelInfo> out = new LinkedHashMap<String, CpuModelInfo>()
		for (Element m : SafeXml.children(mode, 'model')) {
			String name = SafeXml.text(m)
			if (!name || out.containsKey(name)) continue
			Usability u = Usability.of(SafeXml.attr(m, 'usable'))
			out[name] = new CpuModelInfo(name, u, SafeXml.attr(m, 'vendor'), SafeXml.attr(m, 'canonical'),
				SafeXml.attr(m, 'deprecated') == 'yes', u == Usability.NO ? blockers[name] : null)
		}
		return out
	}

	private static String clean(String s) {
		String t = s?.trim()
		if (!t) return null
		t = t.replaceAll(/[\p{Cntrl}&&[^\n\t]]/, '').replaceAll(/\s+/, ' ')
		return t.length() > 300 ? t.substring(0, 300) + '...' : t
	}

	// ---- derived facts

	/** True when anything about the host was read. */
	boolean isReadable() { capabilitiesRead || domCapabilitiesRead }

	/** True when the named-model list could be read, so model usability can be checked. */
	boolean isModelsKnown() { domCapabilitiesRead && !models.isEmpty() }

	/** Microcode as libvirt's hex form (0x700001c), or null. */
	String getMicrocodeHex() { microcode == null ? null : '0x' + Long.toHexString(microcode) }

	/** Looks a model up by exact name, then ignoring case. */
	CpuModelInfo model(String name) {
		if (!name) return null
		CpuModelInfo m = models[name]
		if (m != null) return m
		for (Map.Entry<String, CpuModelInfo> e : models.entrySet()) if (e.key.equalsIgnoreCase(name)) return e.value
		return null
	}

	List<String> getUsableModels() { models.values().findAll { it.usable == Usability.YES }*.name }

	/** One line for evidence: vendor, CPU, microcode, host-model. */
	String describe() {
		if (!readable) return "not read: ${error ?: 'no output'}".toString()
		List<String> parts = []
		parts << ((vendor ?: 'unknown vendor') + ' ' + (cpuModel ?: 'unknown CPU model'))
		if (signature) parts << "(${signature})".toString()
		parts << "microcode ${microcodeHex ?: 'unknown'}".toString()
		if (hostModelName) parts << "host-model ${hostModelName}".toString()
		if (capabilitiesRead) parts << "${features.size()} extra features".toString()
		if (modelsKnown) parts << "${usableModels.size()} of ${models.size()} named models usable".toString()
		return parts.join(', ')
	}
}
