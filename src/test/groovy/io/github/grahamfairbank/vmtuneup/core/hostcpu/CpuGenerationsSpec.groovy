package io.github.grahamfairbank.vmtuneup.core.hostcpu

import spock.lang.Specification
import spock.lang.Unroll

class CpuGenerationsSpec extends Specification {

	@Unroll
	def "#model is family #family"() {
		expect:
		CpuGenerations.family(model) == family

		where:
		model                       | family
		'Broadwell'                 | 'Broadwell'
		'Broadwell-noTSX-IBRS'      | 'Broadwell'
		'Broadwell-v4'              | 'Broadwell'
		'Skylake-Client-noTSX-IBRS' | 'Skylake-Client'
		'Skylake-Server-v5'         | 'Skylake-Server'
		'Cascadelake-Server-noTSX'  | 'Cascadelake-Server'
		'EPYC-IBPB'                 | 'EPYC'
		'EPYC-Rome-v2'              | 'EPYC-Rome'
		'Opteron_G3'                | 'Opteron_G3'
		'Dhyana-v2'                 | 'Dhyana'
		'qemu64'                    | null
		'kvm64-v1'                  | null
		'486'                       | null
		null                        | null
	}

	def "generations rank oldest to newest within a vendor"() {
		expect:
		['Conroe', 'Penryn', 'Nehalem', 'Westmere', 'SandyBridge', 'IvyBridge', 'Haswell', 'Broadwell', 'Skylake-Client',
		 'Skylake-Server', 'Cascadelake-Server', 'Cooperlake', 'Icelake-Server', 'SapphireRapids', 'GraniteRapids'].collect { CpuGenerations.rank(it) }
			.with { it == it.sort(false) && it.toSet().size() == it.size() }
		['Opteron_G1', 'Opteron_G5', 'EPYC', 'EPYC-Rome', 'EPYC-Milan', 'EPYC-Genoa', 'EPYC-Turin'].collect { CpuGenerations.rank(it) }
			.with { it == it.sort(false) && it.toSet().size() == it.size() }
		CpuGenerations.rank('qemu64') == -1
		CpuGenerations.vendor('EPYC-Milan-v2') == 'AMD'
		CpuGenerations.vendor('Haswell') == 'Intel'
		!CpuGenerations.mainline('Intel').contains('Snowridge')
		CpuGenerations.families('Intel').contains('Snowridge')
	}

	def "versions come from the canonical name when libvirt gives one"() {
		expect:
		CpuGenerations.version('Broadwell-v4') == 4
		CpuGenerations.version('Broadwell-noTSX') == 1
		CpuGenerations.version(new CpuModelInfo('Broadwell-noTSX', Usability.YES, 'Intel', 'Broadwell-v2', false, null)) == 2
		CpuGenerations.isVersioned('EPYC-v3')
		!CpuGenerations.isVersioned('EPYC-IBPB')
	}

	def "compare orders by generation, then version"() {
		given:
		def m = { String n, String c = null -> new CpuModelInfo(n, Usability.YES, 'Intel', c, false, null) }

		expect:
		CpuGenerations.compare(m('Skylake-Client'), m('Broadwell-v4')) > 0
		CpuGenerations.compare(m('Broadwell-noTSX-IBRS', 'Broadwell-v4'), m('Broadwell-noTSX', 'Broadwell-v2')) > 0
		CpuGenerations.compare(m('Broadwell-noTSX-IBRS', 'Broadwell-v4'), m('Broadwell-v4')) == 0
		CpuGenerations.compare(m('qemu64'), m('Conroe')) < 0
	}

	def "vendor names match libvirt's spelling"() {
		expect:
		CpuGenerations.sameVendor('Intel', 'Intel')
		CpuGenerations.sameVendor('AMD', 'AMD')
		!CpuGenerations.sameVendor('AMD', 'Intel')
		!CpuGenerations.sameVendor(null, 'Intel')
	}

	@Unroll
	def "cluster setting '#setting' is mode #mode"() {
		expect:
		ClusterCpuMode.of(setting) == mode

		where:
		setting              | mode
		'host-passthrough'   | ClusterCpuMode.HOST_PASSTHROUGH
		'Host Passthrough'   | ClusterCpuMode.HOST_PASSTHROUGH
		'host_model'         | ClusterCpuMode.HOST_MODEL
		'host-model'         | ClusterCpuMode.HOST_MODEL
		'maximum'            | ClusterCpuMode.MAXIMUM
		'Broadwell-noTSX'    | ClusterCpuMode.NAMED
		'  '                 | ClusterCpuMode.NOT_SET
		null                 | ClusterCpuMode.NOT_SET
	}

	def "status order puts unknown above pass and info"() {
		expect:
		HostCpuStatus.worst([HostCpuStatus.PASS, HostCpuStatus.UNKNOWN, HostCpuStatus.INFO]) == HostCpuStatus.UNKNOWN
		HostCpuStatus.worst([HostCpuStatus.UNKNOWN, HostCpuStatus.WARNING]) == HostCpuStatus.WARNING
		HostCpuStatus.worst([]) == HostCpuStatus.UNKNOWN
	}
}
