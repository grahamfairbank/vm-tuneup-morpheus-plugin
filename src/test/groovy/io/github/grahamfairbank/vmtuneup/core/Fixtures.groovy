package io.github.grahamfairbank.vmtuneup.core

import groovy.json.JsonSlurper

/** Loads the sanitized server fixtures under src/test/resources/fixtures (REST shape, see VmFacts). */
class Fixtures {

	/** The scans in the tests run "now" at a fixed time, so ages don't drift. */
	static final Date NOW = VmFacts.date('2026-11-01T18:00:00.000Z')

	static Map raw(String name) {
		InputStream s = Fixtures.getResourceAsStream("/fixtures/${name}.json")
		if (s == null) throw new IllegalArgumentException("no fixture ${name}")
		return (Map) new JsonSlurper().parse(s, 'UTF-8')
	}

	static VmFacts vm(String name) { VmFacts.fromMap(raw(name)) }

	static List<String> names() {
		URL dir = Fixtures.getResource('/fixtures')
		return new File(dir.toURI()).listFiles().findAll { it.name.endsWith('.json') }*.name.collect { it - '.json' }.sort()
	}

	static ScanContext ctx(Map<String, String> baseline = [windows: '2.6.1.0', linux: '3.2.7']) { new ScanContext(NOW, baseline) }

	static CheckResult result(String fixture, Check check, ScanContext c = ctx()) {
		Tier1Rules.evaluate(check, vm(fixture), c)
	}
}
