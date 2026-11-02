package io.github.grahamfairbank.vmtuneup.core.hostcpu

import io.github.grahamfairbank.vmtuneup.core.FixtureHygieneSpec
import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.hostcpu.HostCpuFixtures.*

class HostCpuSpec extends Specification {

	def "the real Broadwell capture parses into the facts the lab shows"() {
		when:
		HostCpu h = host('hvm-host-01', 'broadwell-notsx')

		then:
		h.readable
		h.capabilitiesRead
		h.domCapabilitiesRead
		h.modelsKnown
		h.problems == []
		h.error == null
		h.label == 'hvm-host-01'
		h.arch == 'x86_64'
		h.vendor == 'Intel'
		h.cpuModel == 'Broadwell-v4'
		h.microcode == 117440540L
		h.microcodeHex == '0x700001c'
		h.signature == 'family 6 model 86 stepping 3'
		h.features.contains('vmx')
		!h.features.contains('hle')
		h.hostPassthroughSupported
		h.hostModelSupported
		h.hostModelName == 'Broadwell-noTSX-IBRS'
		h.hostModelFallback == 'forbid'
		h.hostModelVendor == 'Intel'
		h.hostModelFeatures.contains('md-clear')
		h.models.size() == 161
		h.usableModels.size() == 50
		h.describe().contains('50 of 161 named models usable')
	}

	def "named models carry usability, vendor, canonical name and libvirt's blockers"() {
		given:
		HostCpu h = host('hvm-host-01', 'broadwell-notsx')

		expect:
		with(h.model('Broadwell')) {
			usable == Usability.NO
			vendor == 'Intel'
			canonical == 'Broadwell-v1'
			canonicalName == 'Broadwell-v1'
			blockers == ['hle', 'rtm']
			!deprecated
		}
		with(h.model('Broadwell-noTSX-IBRS')) {
			usable == Usability.YES
			canonical == 'Broadwell-v4'
			blockers == []
		}
		h.model('Broadwell-v4').canonical == null
		h.model('Broadwell-v4').canonicalName == 'Broadwell-v4'
		h.model('EPYC-Rome').blockers.contains('svm')
		h.model('broadwell-notsx-ibrs').name == 'Broadwell-noTSX-IBRS'
		h.model('No-Such-Model') == null
		h.model(null) == null
	}

	def "crafted variants parse as intended"() {
		when:
		HostCpu tsx = host('a', 'broadwell-tsx')
		HostCpu hsw = host('b', 'haswell')
		HostCpu amd = host('c', 'epyc-rome')

		then:
		tsx.microcodeHex == '0x7000013'
		tsx.features.containsAll(['hle', 'rtm'])
		tsx.model('Broadwell').usable == Usability.YES
		tsx.hostModelName == 'Broadwell-IBRS'

		hsw.cpuModel == 'Haswell-v4'
		hsw.hostModelName == 'Haswell-noTSX-IBRS'
		hsw.model('Broadwell-noTSX-IBRS').blockers == ['3dnowprefetch', 'adx', 'rdseed', 'smap']
		hsw.model('Haswell-noTSX-IBRS').usable == Usability.YES

		amd.vendor == 'AMD'
		amd.hostModelName == 'EPYC-Rome'
		amd.model('EPYC-Rome-v4').usable == Usability.YES
		amd.model('EPYC-Rome-v5').usable == Usability.NO
		amd.model('IvyBridge').usable == Usability.YES
		amd.model('Haswell-noTSX').blockers == ['invpcid', 'pcid']
	}

	def "a failed host is unreadable and keeps the error"() {
		when:
		HostCpu h = HostCpu.failed('hvm-host-03', 'error: failed to connect to the hypervisor\n')

		then:
		!h.readable
		!h.modelsKnown
		h.error == 'error: failed to connect to the hypervisor'
		h.describe() == 'not read: error: failed to connect to the hypervisor'
		HostCpu.failed('x', null).error == 'no output from the host'
	}

	def "no output at all leaves the host unreadable with a reason"() {
		when:
		HostCpu h = HostCpu.parse('hvm-host-02', null, '  ')

		then:
		!h.readable
		h.problems == ['virsh capabilities: no output', 'virsh domcapabilities: no output']
		h.error.contains('no output')
	}

	def "a truncated domcapabilities leaves the capabilities part usable"() {
		when:
		HostCpu h = HostCpu.parse('hvm-host-02', caps('broadwell-notsx'), text('truncated-domcaps.xml'))

		then:
		h.readable
		h.capabilitiesRead
		!h.domCapabilitiesRead
		!h.modelsKnown
		h.vendor == 'Intel'
		h.problems.size() == 1
		h.problems[0].startsWith('virsh domcapabilities: not valid XML')
	}

	def "garbage, wrong documents and missing sections are tolerated"() {
		expect:
		!HostCpu.parse('h', 'error: no connection', 'not xml').readable
		HostCpu.parse('h', '<domainCapabilities/>', null).problems[0].contains('unexpected document')
		HostCpu.parse('h', '<capabilities><host/></capabilities>', null).problems[0] == 'virsh capabilities: no host CPU section'
		HostCpu.parse('h', null, '<domainCapabilities><cpu/></domainCapabilities>').problems.contains('virsh domcapabilities: no named CPU models (mode custom)')
	}

	def "a warning line before the XML is skipped"() {
		when:
		HostCpu h = HostCpu.parse('h', 'warning: something\n' + caps('broadwell-notsx'), null)

		then:
		h.capabilitiesRead
		h.vendor == 'Intel'
	}

	def "vendor falls back to the host-model vendor when capabilities is missing"() {
		expect:
		HostCpu.parse('h', null, domcaps('epyc-rome')).vendor == 'AMD'
	}

	def "capability features with policy disable are not counted as host features"() {
		when:
		HostCpu h = HostCpu.parse('h', "<capabilities><host><cpu><model>X</model><vendor>Intel</vendor>" +
			"<feature name='aes'/><feature policy='disable' name='hle'/></cpu></host></capabilities>", null)

		then:
		h.features as List == ['aes']
		h.microcode == null
		h.microcodeHex == null
	}

	def "deprecated and unknown usability are read"() {
		when:
		HostCpu h = HostCpu.parse('h', null, domcapsWith('Intel', 'Skylake-Server-v4',
			[['Skylake-Server-v5', 'yes', [deprecated: 'yes']], ['Skylake-Server-v4', 'unknown'], ['Cooperlake', 'no', [:], ['avx512-bf16']]]))

		then:
		h.model('Skylake-Server-v5').deprecated
		h.model('Skylake-Server-v4').usable == Usability.UNKNOWN
		h.model('Cooperlake').blockers == ['avx512-bf16']
	}

	@Unroll
	def "XML with a DOCTYPE or entity is refused before parsing: #name"() {
		when:
		HostCpu h = HostCpu.parse('h', xml, null)

		then:
		!h.capabilitiesRead
		h.problems[0].contains('refused')
		h.vendor == null

		where:
		name             | xml
		'external file'  | '<?xml version="1.0"?><!DOCTYPE c [<!ENTITY x SYSTEM "file:///etc/passwd">]><capabilities><host><cpu><vendor>&x;</vendor></cpu></host></capabilities>'
		'billion laughs' | '<!DOCTYPE c [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><capabilities>&b;</capabilities>'
		'external DTD'   | '<!DOCTYPE capabilities SYSTEM "http://example.invalid/x.dtd"><capabilities/>'
	}

	def "the parser factory itself also refuses a DOCTYPE (second guard)"() {
		when: 'the text guard is bypassed'
		SafeXml.factory().newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(
			'<!DOCTYPE c [<!ENTITY x SYSTEM "file:///etc/hostname">]><c>&x;</c>')))

		then:
		thrown(org.xml.sax.SAXParseException)
	}

	def "oversized input is refused"() {
		when:
		SafeXml.parse('<a>' + ('x' * (SafeXml.MAX_CHARS + 1)) + '</a>', 'test')

		then:
		SafeXml.XmlProblem e = thrown()
		e.message.contains('too large')
	}

	def "error text is cleaned and capped"() {
		when:
		HostCpu h = HostCpu.failed('h', ('bad ' * 200) + '\u0007')

		then:
		h.error.length() <= 303
		!h.error.contains('\u0007')
	}

	def "host CPU fixtures hold no lab identifiers or secrets"() {
		given:
		List<File> files = files()

		expect:
		files.size() == 9
		files.collectMany { FixtureHygieneSpec.problems(it.name, it.getText('UTF-8')) } == []
		files.every { !it.getText('UTF-8').contains('<secmodel>') && !it.getText('UTF-8').contains('hvm0') }
		files.findAll { it.name.endsWith('-caps.xml') }.every { it.getText('UTF-8').contains('<uuid>00000000-0000-4000-8000-000000000000</uuid>') }
	}
}
