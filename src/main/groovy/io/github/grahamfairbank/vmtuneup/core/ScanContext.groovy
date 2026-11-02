package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/** Fleet-wide inputs to a scan: the clock, and the newest Morpheus agent version seen per OS family. */
@CompileStatic
final class ScanContext {

	final Date now
	/** 'windows' / 'linux' to the highest agent version seen in the fleet. Empty when unknown. */
	final Map<String, String> agentBaseline
	/** False when the fleet baseline wasn't computed yet (0.2.0): the agent version comparison is then unknown. */
	final boolean baselineReady

	ScanContext(Date now = null, Map<String, String> agentBaseline = null, boolean baselineReady = true) {
		this.now = now ?: new Date()
		this.baselineReady = baselineReady
		this.agentBaseline = Collections.unmodifiableMap(new LinkedHashMap<String, String>(agentBaseline ?: Collections.<String, String>emptyMap()))
	}

	/** Builds the agent baseline from a fleet: the highest version per OS family among VMs with the agent. */
	static Map<String, String> baselineOf(Collection<VmFacts> fleet) {
		Map<String, String> max = new LinkedHashMap<String, String>()
		for (VmFacts f : (fleet ?: Collections.<VmFacts>emptyList())) {
			if (f == null || f.agentInstalled != Boolean.TRUE || !f.agentVersion) continue
			String fam = f.osFamilyKey
			if (fam == null) continue
			String seen = max.get(fam)
			if (seen == null || compareVersions(f.agentVersion, seen) > 0) max.put(fam, f.agentVersion)
		}
		return max
	}

	/** Compares dotted version strings numerically (2.10 > 2.9); non-numeric parts compare as 0. */
	static int compareVersions(String a, String b) {
		List<Integer> x = parts(a)
		List<Integer> y = parts(b)
		int n = Math.max(x.size(), y.size())
		for (int i = 0; i < n; i++) {
			int p = i < x.size() ? x[i] : 0
			int q = i < y.size() ? y[i] : 0
			if (p != q) return p <=> q
		}
		return 0
	}

	private static List<Integer> parts(String v) {
		List<Integer> out = new ArrayList<Integer>()
		for (String s : (v ?: '').split('[^0-9]+')) {
			if (!s) continue
			try { out.add(Integer.valueOf(s)) } catch (NumberFormatException ignored) { out.add(0) }
		}
		return out
	}

	long daysSince(Date d) {
		if (d == null) return -1L
		return (long) Math.floor((now.time - d.time) / 86400000d)
	}
}
