package io.github.grahamfairbank.vmtuneup.core.hostcpu

import groovy.transform.CompileStatic

import java.util.regex.Matcher

/**
 * libvirt / QEMU named CPU models ranked by generation, oldest first, per vendor. Used to pick the newest model every
 * host can run. A model name is a family plus optional variant suffixes: Broadwell-noTSX-IBRS → family Broadwell;
 * EPYC-Rome-v2 → family EPYC-Rome. Within a family, the libvirt version (-vN, from the canonical name) decides:
 * QEMU adds a version to add security features (IBRS, SSBD, ...) or to drop Intel TSX, which microcode updates
 * have removed from many CPUs, so the latest usable version is the safest choice.
 *
 * Generic models (qemu64, kvm64, 486, pentium, ...) and families not in the table rank as unknown and are never
 * recommended. Low-power and many-core lines (Denverton, Snowridge, SierraForest, ...) are placed by their rough
 * feature level, an approximation.
 */
@CompileStatic
final class CpuGenerations {

	private CpuGenerations() {}

	static final String INTEL = 'Intel'
	static final String AMD = 'AMD'
	static final String HYGON = 'Hygon'

	/** family → [vendor, rank]. Ranks only compare within one vendor. */
	private static final Map<String, List<Object>> TABLE = build()

	private static Map<String, List<Object>> build() {
		Map<String, List<Object>> t = new LinkedHashMap<String, List<Object>>()
		int r = 0
		for (String f : ['Conroe', 'Penryn', 'Nehalem', 'Westmere', 'SandyBridge', 'IvyBridge', 'Denverton', 'Haswell', 'Broadwell',
		                 'Snowridge', 'KnightsMill', 'Skylake-Client', 'Skylake-Server', 'Cascadelake-Server', 'Icelake-Client',
		                 'Cooperlake', 'Icelake-Server', 'SierraForest', 'SapphireRapids', 'ClearwaterForest', 'GraniteRapids']) {
			t[f] = [(Object) INTEL, (Object) (r += 10)]
		}
		r = 0
		for (String f : ['Opteron_G1', 'Opteron_G2', 'Opteron_G3', 'Opteron_G4', 'Opteron_G5', 'EPYC', 'EPYC-Rome', 'EPYC-Milan',
		                 'EPYC-Genoa', 'EPYC-Turin']) {
			t[f] = [(Object) AMD, (Object) (r += 10)]
		}
		t['Dhyana'] = [(Object) HYGON, (Object) 10]
		return t
	}

	/** Variant suffixes that don't change the family. */
	private static final List<String> SUFFIXES = ['-noTSX-IBRS', '-noTSX', '-IBRS', '-IBPB']

	/** The family of a model name (Broadwell-noTSX-IBRS → Broadwell), or null when not in the table. */
	static String family(String model) {
		if (!model) return null
		String base = model.trim().replaceFirst(/-v\d+$/, '')
		for (String s : SUFFIXES) {
			if (base.endsWith(s)) { base = base.substring(0, base.length() - s.length()); break }
		}
		return TABLE.containsKey(base) ? base : null
	}

	/** Vendor of a family in the table (Intel, AMD, Hygon), or null. */
	static String vendor(String model) {
		String f = family(model)
		return f == null ? null : (String) TABLE[f][0]
	}

	/** Generation rank (higher is newer, within one vendor), or -1 when unknown. */
	static int rank(String model) {
		String f = family(model)
		return f == null ? -1 : (int) (Integer) TABLE[f][1]
	}

	/** libvirt version number of a model: 4 for Broadwell-v4; 1 when the name has no -vN. */
	static int version(String name) {
		if (!name) return 0
		Matcher m = (name =~ /-v(\d+)$/)
		return m.find() ? Integer.parseInt(m.group(1)) : 1
	}

	/** Version of a model, from libvirt's canonical name when known (Broadwell-noTSX-IBRS is Broadwell-v4). */
	static int version(CpuModelInfo m) { m == null ? 0 : version(m.canonicalName) }

	/** True when the name ends in -vN (a versioned name rather than a descriptive alias). */
	static boolean isVersioned(String name) { name != null && name ==~ /.*-v\d+$/ }

	/** Low-power, many-core and Xeon Phi lines: ranked, but not the "next generation" of a mainline Xeon. */
	static final Set<String> SIDE_LINES = Collections.unmodifiableSet(new TreeSet<String>(
		['Denverton', 'Snowridge', 'KnightsMill', 'SierraForest', 'ClearwaterForest', 'Icelake-Client']))

	/** Families of one vendor, oldest first. */
	static List<String> families(String vendor) {
		List<String> out = []
		TABLE.each { String f, List<Object> v -> if (v[0] == vendor) out << f }
		return out
	}

	/** Mainline families of one vendor (no side lines), oldest first. */
	static List<String> mainline(String vendor) { families(vendor).findAll { !SIDE_LINES.contains(it) } }

	/** Compares two models by generation then version; positive when {@code a} is newer. Unknown families sort lowest. */
	static int compare(CpuModelInfo a, CpuModelInfo b) {
		int c = Integer.compare(rank(a?.name), rank(b?.name))
		return c != 0 ? c : Integer.compare(version(a), version(b))
	}

	/** True when a host vendor string (from libvirt) names the same vendor as the table's. */
	static boolean sameVendor(String hostVendor, String tableVendor) {
		if (!hostVendor || !tableVendor) return false
		String h = hostVendor.trim().toLowerCase()
		String t = tableVendor.toLowerCase()
		return h == t || (t == 'intel' && h.contains('intel')) || (t == 'amd' && (h.contains('amd') || h == 'authenticamd'))
	}
}
