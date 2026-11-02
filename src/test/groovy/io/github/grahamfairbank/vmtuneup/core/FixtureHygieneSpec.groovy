package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification

/**
 * Fixtures come from real lab captures, sanitized. This fails if a real address, lab name, UUID, token or MAC
 * slips back in. Allowed: documentation addresses (192.0.2.x, 198.51.100.x, 203.0.113.x), fake names (vm-*,
 * host-*), the all-zero UUID, and MACs that keep a real prefix (VMware or KVM) with a fake 00:00:xx suffix.
 *
 * 0.4.0: the check for the lab's own names is no longer here (unsalted hashes of short names are guessable). It is
 * a local-only script in the git-ignored spike/ folder; this spec keeps the generic checks: addresses, MACs,
 * UUIDs and secret-like words.
 */
class FixtureHygieneSpec extends Specification {

	/** Generic markers that never belong in a fixture. */
	static final List<String> FORBIDDEN = ['192.168.', '10.30.', 'apiKey', 'sshPassword', 'password', 'token', 'secret']


	/** Tokens: runs of letters, digits, '_', '-' and '.', plus each '/'-separated part of a path. */
	static Set<String> tokens(String text) {
		Set<String> out = [] as Set
		(text.toLowerCase() =~ /[a-z0-9_.\-]+/).each { String t ->
			out << t
			t.split(/[.\-]/).each { if (it) out << it }
			if (t.endsWith('.iso') || t.endsWith('.qcow2')) out << t.substring(0, t.lastIndexOf('.'))
		}
		return out
	}

	static List<String> problems(String name, String text) {
		List<String> problems = []
		FORBIDDEN.each { if (text.toLowerCase().contains(it.toLowerCase())) problems << "${name}: '${it}'".toString() }
		// Dotted quads outside version fields (agentVersion 2.6.1.0 is not an address).
		(text.readLines().findAll { !it.contains('ersion"') }.join('\n') =~ /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/).each { String ip ->
			if (!(ip.startsWith('192.0.2.') || ip.startsWith('198.51.100.') || ip.startsWith('203.0.113.'))) problems << "${name}: IP ${ip}".toString()
		}
		(text =~ /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/).each { String u ->
			if (u != '00000000-0000-4000-8000-000000000000') problems << "${name}: UUID ${u}".toString()
		}
		(text =~ /(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b/).each { String mac ->
			if (!(mac.toLowerCase() ==~ /[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:00:00:[0-9a-f]{2}/)) problems << "${name}: MAC ${mac}".toString()
		}
		return problems
	}

	def "fixtures hold no lab identifiers or secrets"() {
		given:
		File dir = new File(getClass().getResource('/fixtures').toURI())
		List<File> files = dir.listFiles().findAll { it.name.endsWith('.json') }

		expect:
		files.size() > 10
		files.collectMany { problems(it.name, it.getText('UTF-8')) } == []
	}

	def "the scanner itself catches what it should"() {
		expect:
		problems('t', '{"ipAddress": "192.168.1.5"}').any { it.contains('192.168.') }
		problems('t', '{"ipAddress": "172.16.0.9"}').any { it.contains('IP 172.16.0.9') }
		problems('t', '{"uuid": "f56a1602-9c60-4491-99aa-9eca78d013ed"}').any { it.contains('UUID') }
		problems('t', '{"macAddress": "00:0c:29:12:34:56"}').any { it.contains('MAC') }
		problems('t', '{"agentVersion": "2.6.1.0", "ipAddress": "192.0.2.10", "macAddress": "00:50:56:00:00:01"}') == []
		tokens('/mnt/datastore-1/vm-a/disk.qcow2').containsAll(['datastore-1', 'vm-a', 'disk.qcow2', 'disk'])
	}
}
