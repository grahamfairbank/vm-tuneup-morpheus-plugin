package io.github.grahamfairbank.vmtuneup.core.hostcpu

/**
 * Loads the host CPU fixtures under src/test/resources/fixtures/hostcpu. broadwell-notsx is a real capture of a
 * Xeon D-1541 HVM host (scrubbed: UUID zeroed, secmodel removed); the others are crafted from it (see the comment at
 * the top of each file): broadwell-tsx (same CPU on older microcode, TSX still present), haswell (an older host),
 * epyc-rome (AMD), truncated-domcaps (a capture cut off half way).
 */
class HostCpuFixtures {

	static final List<String> KINDS = ['broadwell-notsx', 'broadwell-tsx', 'haswell', 'epyc-rome']

	static String text(String file) {
		InputStream s = HostCpuFixtures.getResourceAsStream("/fixtures/hostcpu/${file}")
		if (s == null) throw new IllegalArgumentException("no fixture ${file}")
		return s.getText('UTF-8')
	}

	static String caps(String kind) { text("${kind}-caps.xml") }

	static String domcaps(String kind) { text("${kind}-domcaps.xml") }

	static HostCpu host(String label, String kind) { HostCpu.parse(label, caps(kind), domcaps(kind)) }

	static List<File> files() {
		new File(HostCpuFixtures.getResource('/fixtures/hostcpu').toURI()).listFiles().findAll { it.name.endsWith('.xml') }.sort { it.name }
	}

	/** A small domcapabilities document with the given custom models: [name, usable, attrs map, blockers]. */
	static String domcapsWith(String vendor, String hostModel, List<List> models) {
		StringBuilder s = new StringBuilder("<domainCapabilities><domain>kvm</domain><arch>x86_64</arch><cpu>")
		s << "<mode name='host-passthrough' supported='yes'/>"
		s << "<mode name='host-model' supported='yes'><model fallback='forbid'>${hostModel}</model><vendor>${vendor}</vendor></mode>"
		s << "<mode name='custom' supported='yes'>"
		models.each { List m ->
			Map attrs = m.size() > 2 ? (Map) m[2] : [:]
			String extra = attrs.collect { k, v -> " ${k}='${v}'" }.join('')
			s << "<model usable='${m[1]}' vendor='${attrs.vendor ?: vendor}'${extra.replaceAll(/ vendor='[^']*'/, '')}>${m[0]}</model>"
			if (m.size() > 3 && m[3]) {
				s << "<blockers model='${m[0]}'>"
				((List) m[3]).each { s << "<feature name='${it}'/>" }
				s << '</blockers>'
			}
		}
		s << '</mode></cpu></domainCapabilities>'
		return s.toString()
	}

	static String capsWith(String vendor, String model, long microcode, List<String> features = []) {
		"<capabilities><host><uuid>00000000-0000-4000-8000-000000000000</uuid><cpu><arch>x86_64</arch><model>${model}</model>" +
			"<vendor>${vendor}</vendor><microcode version='${microcode}'/>" + features.collect { "<feature name='${it}'/>" }.join('') +
			'</cpu></host></capabilities>'
	}
}
