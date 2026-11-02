package io.github.grahamfairbank.vmtuneup.core.hostcpu

import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.hostcpu.HostCpuFixtures.*

class HostCpuReconcilerSpec extends Specification {

	static List<HostCpu> labIdentical() {
		[host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'broadwell-notsx'), host('hvm-host-03', 'broadwell-notsx')]
	}

	static HostCpuResult run(String setting, List<HostCpu> hosts) { HostCpuReconciler.reconcile(setting, hosts) }

	// ---- identical hosts (the lab today)

	def "identical hosts on host passthrough pass, with advice for later"() {
		when:
		HostCpuResult r = run('host-passthrough', labIdentical())

		then:
		r.mode == ClusterCpuMode.HOST_PASSTHROUGH
		r.status == HostCpuStatus.PASS
		r.codes == ['HOSTS-IDENTICAL']
		with(r.finding('HOSTS-IDENTICAL')) {
			text.contains('All 3 hosts report the same CPU')
			text.contains('host-model (Broadwell-noTSX-IBRS)')
			recommendation.contains('add only hosts with the same CPU and microcode')
			recommendation.contains('Broadwell-noTSX-IBRS is the newest one every host can run')
			evidence.keySet() as List == ['hvm-host-01', 'hvm-host-02', 'hvm-host-03']
			evidence['hvm-host-02'].contains('microcode 0x700001c')
		}
		r.hosts.size() == 3
	}

	def "the best model on the lab hosts is Broadwell-noTSX-IBRS, explained"() {
		when:
		ModelChoice best = run('host-passthrough', labIdentical()).bestModel

		then:
		best.name == 'Broadwell-noTSX-IBRS'
		best.canonical == 'Broadwell-v4'
		best.family == 'Broadwell'
		best.sameModelNames == ['Broadwell-noTSX-IBRS', 'Broadwell-v4']
		best.coversAllHosts
		best.explanation.contains("libvirt's Broadwell-v4")
		best.explanation.contains('Broadwell, the newest CPU generation every host can run')
		best.explanation.contains('The next generation, Skylake-Client, is ruled out: hvm-host-01 lacks')
	}

	@Unroll
	def "identical hosts with #setting also pass"() {
		when:
		HostCpuResult r = run(setting, labIdentical())

		then:
		r.status == HostCpuStatus.PASS
		r.codes == ['HOSTS-IDENTICAL']
		r.mode == mode
		r.finding('HOSTS-IDENTICAL').text.contains(words)

		where:
		setting      | mode                    | words
		'host-model' | ClusterCpuMode.HOST_MODEL | 'uses host-model'
		null         | ClusterCpuMode.NOT_SET  | "HVM's default, host passthrough"
		'maximum'    | ClusterCpuMode.MAXIMUM  | 'maximum'
	}

	def "a single host passes and is told to switch before adding a different host"() {
		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'broadwell-notsx')])

		then:
		r.status == HostCpuStatus.PASS
		r.codes == ['SINGLE-HOST']
		r.finding('SINGLE-HOST').recommendation.contains('Broadwell-noTSX-IBRS')
	}

	// ---- microcode update: TSX removed on one host

	def "named model Broadwell after a microcode update removed TSX on one host is critical, with blockers"() {
		when:
		HostCpuResult r = run('Broadwell', [host('hvm-host-01', 'broadwell-tsx'), host('hvm-host-02', 'broadwell-notsx')])

		then:
		r.mode == ClusterCpuMode.NAMED
		r.status == HostCpuStatus.CRITICAL
		r.codes == ['MODEL-UNUSABLE']
		with(r.finding('MODEL-UNUSABLE')) {
			text.contains("1 of 2 hosts can't run it: hvm-host-02")
			text.contains('missing CPU features: hle, rtm')
			text.contains('Intel TSX')
			evidence['hvm-host-01'] == 'Broadwell: usable'
			evidence['hvm-host-02'] == 'Broadwell: not usable, host lacks hle, rtm'
			recommendation.startsWith('Change the cluster to Broadwell-noTSX-IBRS')
			recommendation.contains('Edit > CPU Model')
		}
		r.bestModel.name == 'Broadwell-noTSX-IBRS'
	}

	def "host passthrough across old and new microcode warns and recommends the shared model"() {
		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'broadwell-tsx'), host('hvm-host-02', 'broadwell-notsx')])

		then:
		r.status == HostCpuStatus.WARNING
		r.codes == ['HOSTS-DIFFER']
		with(r.finding('HOSTS-DIFFER')) {
			text.contains('microcode, CPU features, host-model, host-model features differ')
			text.contains('migratable flag is not a compatibility guarantee')
			text.contains('Intel TSX')
			evidence['hvm-host-02'].endsWith('; lacks hle, rtm')
			!evidence['hvm-host-01'].contains('lacks')
			recommendation.startsWith('Set the cluster to the named model Broadwell-noTSX-IBRS')
		}
	}

	def "host-model on hosts that differ explains host-model"() {
		when:
		HostCpuResult r = run('host-model', [host('hvm-host-01', 'broadwell-tsx'), host('hvm-host-02', 'broadwell-notsx')])

		then:
		r.status == HostCpuStatus.WARNING
		r.finding('HOSTS-DIFFER').text.contains('libvirt builds each VM\'s CPU from the host it starts on')
	}

	def "hosts that differ only in microcode still warn, and say so"() {
		given:
		String newer = caps('broadwell-notsx').replace("<microcode version='117440540'/>", "<microcode version='117440542'/>")
		List<HostCpu> hosts = [host('hvm-host-01', 'broadwell-notsx'), HostCpu.parse('hvm-host-02', newer, domcaps('broadwell-notsx'))]

		when:
		HostCpuResult r = run('host-passthrough', hosts)

		then:
		r.status == HostCpuStatus.WARNING
		with(r.finding('HOSTS-DIFFER')) {
			text.contains('its hosts\' CPUs are not the same: microcode differ')
			text.contains('Only the microcode differs today')
		}
	}

	// ---- an older host

	def "a cluster on Broadwell-noTSX-IBRS with an older Haswell host is critical and falls back to Haswell"() {
		when:
		HostCpuResult r = run('Broadwell-noTSX-IBRS', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'haswell')])

		then:
		r.status == HostCpuStatus.CRITICAL
		with(r.finding('MODEL-UNUSABLE')) {
			text.contains('hvm-host-02')
			text.contains('3dnowprefetch, adx, rdseed, smap')
			!text.contains('Intel TSX')
			evidence['hvm-host-01'] == 'Broadwell-noTSX-IBRS: usable'
		}
		with(r.bestModel) {
			name == 'Haswell-noTSX-IBRS'
			canonical == 'Haswell-v4'
			explanation.contains('The next generation, Broadwell, is ruled out: hvm-host-02 lacks 3dnowprefetch, adx, rdseed, smap')
		}
	}

	def "an older host with host passthrough warns and lists what the older host lacks"() {
		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'haswell')])

		then:
		r.status == HostCpuStatus.WARNING
		with(r.finding('HOSTS-DIFFER')) {
			text.contains('CPU model, CPU signature, microcode')
			evidence['hvm-host-02'].contains('Intel Haswell-v4')
			evidence['hvm-host-02'].contains('lacks vmx-eptp-switching, vmx-rdseed-exit')
			recommendation.contains('Haswell-noTSX-IBRS')
		}
	}

	def "a named model usable everywhere with a newer one also usable is info"() {
		when:
		HostCpuResult r = run('Haswell-noTSX', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'haswell')])

		then:
		r.status == HostCpuStatus.INFO
		r.codes == ['MODEL-NEWER']
		with(r.finding('MODEL-NEWER')) {
			text.contains('Haswell-noTSX is usable on all 2 hosts')
			text.contains('Haswell-noTSX-IBRS is also usable on every host and is newer')
			recommendation.startsWith('Optional: change the cluster to Haswell-noTSX-IBRS')
			recommendation.contains('if you plan to add older hosts')
		}
	}

	def "an older generation on newer hosts is info too"() {
		expect:
		run('IvyBridge', labIdentical()).finding('MODEL-NEWER').text.contains('Broadwell-noTSX-IBRS is also usable')
	}

	@Unroll
	def "the best named model, or its alias #setting, passes"() {
		when:
		HostCpuResult r = run(setting, [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'haswell')])

		then:
		r.status == HostCpuStatus.PASS
		r.codes == ['MODEL-OK']
		r.finding('MODEL-OK').text.contains('It is the newest model every host can run')

		where:
		setting << ['Haswell-noTSX-IBRS', 'Haswell-v4', 'haswell-notsx-ibrs']
	}

	def "a model libvirt doesn't know on a host is critical"() {
		when:
		HostCpuResult r = run('Skylake-Server-v99', labIdentical())

		then:
		r.status == HostCpuStatus.CRITICAL
		r.finding('MODEL-UNUSABLE').evidence['hvm-host-01'] == "libvirt on this host doesn't know a model named Skylake-Server-v99"
	}

	def "a generic model (kvm64) gets the newest shared model suggested"() {
		expect:
		run('kvm64', labIdentical()).finding('MODEL-NEWER').text.contains('Broadwell-noTSX-IBRS is also usable')
	}

	def "a vendor model the table can't rank passes without suggesting another"() {
		given:
		List<List> models = [['DiamondRapids', 'yes'], ['Haswell-v4', 'yes']]

		when:
		HostCpuResult r = run('DiamondRapids', [small('hvm-host-01', models), small('hvm-host-02', models)])

		then:
		r.status == HostCpuStatus.PASS
		r.codes == ['MODEL-OK']
		r.finding('MODEL-OK').text.contains("can't rank this model's generation")
	}

	// ---- vendor mix

	def "Intel and AMD hosts in one cluster are critical, with no model recommended"() {
		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'epyc-rome')])

		then:
		r.status == HostCpuStatus.CRITICAL
		r.codes == ['VENDOR-MIX']
		r.bestModel == null
		with(r.finding('VENDOR-MIX')) {
			text.contains('Intel: hvm-host-01; AMD: hvm-host-02')
			text.contains("can't live-migrate a running VM between Intel and AMD")
			evidence['hvm-host-02'] == 'AMD EPYC-Rome-v4'
			recommendation.contains('one CPU vendor per cluster')
		}
	}

	def "a vendor mix stays critical even when an old named model is usable on both"() {
		when:
		HostCpuResult r = run('IvyBridge', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'epyc-rome')])

		then:
		r.status == HostCpuStatus.CRITICAL
		r.codes == ['VENDOR-MIX']
		r.bestModel == null
	}

	def "a vendor mix on an Intel model also lists the AMD host's blockers"() {
		when:
		HostCpuResult r = run('Broadwell-noTSX-IBRS', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'epyc-rome')])

		then:
		r.codes.containsAll(['VENDOR-MIX', 'MODEL-UNUSABLE'])
		!r.codes.contains('NO-COMMON-MODEL')
		r.finding('MODEL-UNUSABLE').evidence['hvm-host-02'] == 'Broadwell-noTSX-IBRS: not usable, host lacks invpcid, pcid'
	}

	def "AMD-only clusters pick an EPYC model"() {
		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'epyc-rome'), host('hvm-host-02', 'epyc-rome')])

		then:
		r.status == HostCpuStatus.PASS
		r.bestModel.name == 'EPYC-Rome-v4'
		r.bestModel.family == 'EPYC-Rome'
		r.bestModel.explanation.contains('The next generation, EPYC-Milan, is ruled out')
	}

	def "an architecture mix is critical"() {
		given:
		HostCpu arm = HostCpu.parse('hvm-host-02', caps('broadwell-notsx').replace('<arch>x86_64</arch>', '<arch>aarch64</arch>'), null)

		when:
		HostCpuResult r = run('host-passthrough', [host('hvm-host-01', 'broadwell-notsx'), arm])

		then:
		r.codes.contains('ARCH-MIX')
		r.status == HostCpuStatus.CRITICAL
		r.bestModel == null
	}

	// ---- hosts that couldn't be read

	def "a host that couldn't be read makes the result unknown, never a pass"() {
		when:
		HostCpuResult r = run('host-passthrough', labIdentical()[0..1] + [HostCpu.failed('hvm-host-03', 'command timed out')])

		then:
		r.status == HostCpuStatus.UNKNOWN
		r.codes == ['HOSTS-UNREAD', 'HOSTS-IDENTICAL']
		r.finding('HOSTS-UNREAD').evidence == ['hvm-host-03': 'Not read: command timed out']
		r.finding('HOSTS-UNREAD').text.startsWith('1 of 3 hosts')
		r.finding('HOSTS-IDENTICAL').text.contains('The 2 hosts that could be read')
		!r.bestModel.coversAllHosts
	}

	def "a host with a broken model list is unknown for a named model, and the recommendation says so"() {
		given:
		HostCpu partial = HostCpu.parse('hvm-host-02', caps('broadwell-tsx'), text('truncated-domcaps.xml'))

		when:
		HostCpuResult r = run('Broadwell', [host('hvm-host-01', 'broadwell-notsx'), partial])

		then:
		r.status == HostCpuStatus.CRITICAL
		r.finding('HOSTS-UNREAD').evidence['hvm-host-02'].startsWith('Named CPU models (virsh domcapabilities) not read: virsh domcapabilities: not valid XML')
		r.finding('MODEL-UNUSABLE').text.contains('1 of 1 hosts')
		r.finding('MODEL-UNUSABLE').recommendation.contains('checked on the hosts that could be read')
	}

	def "a named model checked on the readable hosts only stays unknown overall"() {
		when:
		HostCpuResult r = run('Broadwell-noTSX-IBRS', [host('hvm-host-01', 'broadwell-notsx'), HostCpu.failed('hvm-host-02', 'unreachable')])

		then:
		r.status == HostCpuStatus.UNKNOWN
		r.codes == ['HOSTS-UNREAD', 'MODEL-OK']
		r.finding('MODEL-OK').text.contains('the 1 hosts that could be read')
	}

	def "no host readable at all is unknown with nothing recommended"() {
		when:
		HostCpuResult r = run('host-passthrough', [HostCpu.failed('hvm-host-01', 'x'), HostCpu.failed('hvm-host-02', 'y')])

		then:
		r.status == HostCpuStatus.UNKNOWN
		r.codes == ['HOSTS-UNREAD']
		r.bestModel == null
	}

	def "no hosts at all is unknown"() {
		expect:
		run('host-passthrough', []).codes == ['NO-HOSTS']
		run('host-passthrough', null).status == HostCpuStatus.UNKNOWN
		run('host-passthrough', [null]).codes == ['NO-HOSTS']
	}

	// ---- deprecated and unknown usability (small crafted documents)

	static HostCpu small(String label, List<List> models) {
		HostCpu.parse(label, capsWith('Intel', 'Skylake-Server-v4', 1L), domcapsWith('Intel', 'Skylake-Server-v4', models))
	}

	def "deprecated models are skipped when picking the best one"() {
		given:
		List<List> models = [['Skylake-Server-v5', 'yes', [deprecated: 'yes', canonical: 'Skylake-Server-v5']], ['Skylake-Server-v4', 'yes'],
		                     ['Skylake-Server', 'yes', [canonical: 'Skylake-Server-v1']]]

		when:
		HostCpuResult r = run('Skylake-Server-v5', [small('hvm-host-01', models), small('hvm-host-02', models)])

		then:
		r.bestModel.name == 'Skylake-Server-v4'
		r.bestModel.explanation.contains('Skipped as deprecated: Skylake-Server-v5')
		r.status == HostCpuStatus.WARNING
		r.codes == ['MODEL-DEPRECATED']
		r.finding('MODEL-DEPRECATED').text.contains('may drop it')
		r.finding('MODEL-DEPRECATED').recommendation.startsWith('Plan a change to Skylake-Server-v4')
	}

	def "a model libvirt can't judge is unknown"() {
		given:
		List<List> models = [['Skylake-Server-v4', 'unknown'], ['Haswell-v4', 'yes']]

		when:
		HostCpuResult r = run('Skylake-Server-v4', [small('hvm-host-01', models)])

		then:
		r.status == HostCpuStatus.UNKNOWN
		r.codes == ['MODEL-USABILITY-UNKNOWN']
	}

	def "an unusable model with no blockers element says libvirt didn't say why"() {
		given:
		List<List> models = [['Skylake-Server-v4', 'no'], ['Haswell-v4', 'yes']]

		when:
		HostCpuResult r = run('Skylake-Server-v4', [small('hvm-host-01', models)])

		then:
		r.finding('MODEL-UNUSABLE').evidence['hvm-host-01'].contains("libvirt didn't say")
	}

	def "different hosts with no shared named model warn that none fits"() {
		given:
		HostCpu a = HostCpu.parse('hvm-host-01', capsWith('Intel', 'A', 1L), domcapsWith('Intel', 'X', [['Haswell-v4', 'yes'], ['Broadwell-v4', 'no', [:], ['adx']]]))
		HostCpu b = HostCpu.parse('hvm-host-02', capsWith('Intel', 'B', 2L), domcapsWith('Intel', 'Y', [['Haswell-v4', 'no', [:], ['avx2']], ['Broadwell-v4', 'yes']]))

		when:
		HostCpuResult r = run('host-passthrough', [a, b])

		then:
		r.bestModel == null
		r.codes == ['HOSTS-DIFFER', 'NO-COMMON-MODEL']
		r.finding('HOSTS-DIFFER').recommendation.startsWith('No named CPU model is usable on every host')
	}

	def "findings are ordered worst first and the result is read-only"() {
		when:
		HostCpuResult r = run('Broadwell-noTSX-IBRS', [host('hvm-host-01', 'broadwell-notsx'), host('hvm-host-02', 'epyc-rome'), HostCpu.failed('hvm-host-03', 'down')])
		r.findings << null

		then:
		thrown(UnsupportedOperationException)
		r.findings*.status == r.findings*.status.sort { -it.ordinal() }
		r.findings[0].status == HostCpuStatus.CRITICAL
	}
}
