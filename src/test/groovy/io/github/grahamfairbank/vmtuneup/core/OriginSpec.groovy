package io.github.grahamfairbank.vmtuneup.core

import io.github.grahamfairbank.vmtuneup.Views
import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.Fixtures.*

/**
 * 0.3.0: each VM's origin bucket (migrated, not-migrated, unknown) and the signals behind it.
 * Fake MACs only: a real prefix with a 00:00:xx suffix (fixture hygiene).
 */
class OriginSpec extends Specification {

	/** A clean managed Linux VM with one KVM NIC, then the changes in {@code edit}. */
	static VmReport report(String fixture = 'vm-clean-linux', Closure edit = {}) {
		VmFacts f = vm(fixture)
		edit.call(f)
		return VmReport.scan(f, ctx())
	}

	static VmReport report(Closure edit) { report('vm-clean-linux', edit) }

	def "no signal on a managed VM: not migrated"() {
		when:
		VmReport r = report()

		then:
		r.originSignals == []
		r.origin == 'not-migrated'
		Views.originLabel(r.origin) == 'Not migrated'
		Views.originWhy(r) == 'managed VM: no VMware MAC, OS fields complete, guest checks not run'
	}

	def "a VMware MAC alone: migrated"() {
		when:
		VmReport r = report { VmFacts f -> f.interfaces[0].mac = '00:50:56:00:00:01' }

		then:
		r.originSignals == ['T1-VMWARE-MAC']
		r.origin == 'migrated'
		Views.originLabel(r.origin) == 'Migrated'
		Views.originWhy(r) == 'VMware MAC address'
	}

	def "the native-migration OS typing gap alone: migrated"() {
		when:
		VmReport r = report { VmFacts f -> f.osType = null }

		then:
		r.results.find { it.checkId == 'T1-VMWARE-MAC' }.status == Status.PASS
		r.originSignals == ['T1-OS-TYPE-MISMATCH']
		r.origin == 'migrated'
		Views.originWhy(r) == 'OS fields left incomplete by a VMware migration'
	}

	def "both signals: migrated, both named"() {
		when:
		VmReport r = report { VmFacts f -> f.interfaces[0].mac = '00:0c:29:00:00:02'; f.osType = null }

		then:
		r.originSignals as Set == ['T1-VMWARE-MAC', 'T1-OS-TYPE-MISMATCH'] as Set
		Views.originWhy(r).split(', ') as Set == ['VMware MAC address', 'OS fields left incomplete by a VMware migration'] as Set
	}

	def "a discovered VM with no signal: unknown origin; with a signal: migrated"() {
		when:
		VmReport plain = report { VmFacts f -> f.managed = false }
		VmReport signal = report { VmFacts f -> f.managed = false; f.interfaces[0].mac = '00:50:56:00:00:03' }

		then:
		plain.origin == 'unknown'
		Views.originLabel(plain.origin) == 'Origin unknown'
		Views.originWhy(plain) == 'discovered VM: no VMware MAC, OS fields complete, guest checks not run'
		signal.origin == 'migrated'
	}

	@Unroll
	def "fixture #fixture is #origin"() {
		expect:
		VmReport.scan(vm(fixture), ctx()).origin == origin

		where:
		fixture                   | origin
		'vm-clean-linux'          | 'not-migrated'
		'vm-901-migrated-linux'   | 'migrated'
		'vm-901-migrated-windows' | 'migrated'
		'vm-ide-import'           | 'migrated'
	}

	def "origin groups: counts, grades and findings by severity per group, plus all VMs"() {
		given:
		List<VmReport> rs = [report(), report { VmFacts f -> f.interfaces[0].mac = '00:50:56:00:00:04' }, report('vm-ide-import'),
		                     report { VmFacts f -> f.managed = false }]

		when:
		List<Map> g = Views.originGroups(rs)
		Map byKey = g.collectEntries { [(it.origin): it] }

		then:
		g*.origin == ['migrated', 'not-migrated', 'unknown', 'all']
		g*.label == ['Migrated', 'Not migrated', 'Origin unknown', 'All VMs']
		byKey.migrated.vmCount == 2
		byKey['not-migrated'].vmCount == 1
		byKey.unknown.vmCount == 1
		byKey.all.vmCount == 4
		byKey.all.isAll
		byKey.migrated.critical == rs.findAll { it.origin == 'migrated' }.sum { it.count(Severity.CRITICAL) }
		byKey.all.info == rs.sum { it.count(Severity.INFO) }
		(byKey.migrated.grades as List<Map>).sum { it.count } == 2
		(byKey.all.grades as List<Map>)*.grade == ['A', 'B', 'C', 'D', 'F']
	}

	def "the VM table rows carry the origin, biggest tune-up benefit first"() {
		when:
		List<VmReport> rs = [report(), report('vm-ide-import'), report { VmFacts f -> f.managed = false }]
		List<Map> rows = Views.vmRows(rs)
		List<Integer> gains = Views.byBenefit(rs).collect { TuneUpPlan.of(it).gain }

		then:
		rows[0].origin == 'migrated'
		rows*.origin as Set == ['migrated', 'not-migrated', 'unknown'] as Set
		rows.every { it.originLabel && it.originWhy }
		gains == gains.sort(false).reverse()
	}

	def "VMware Tools in a Windows guest marks the VM migrated; open-vm-tools in a Linux guest does not"() {
		given:
		GuestFacts win = GuestFacts.parse('VTU-BEGIN v1 windows\npkg.vmwaretools=12.3.0\nsvc.VMTools=2\nrun.VMTools=Running\nVTU-END 3\n')
		GuestFacts lin = GuestFacts.parse('VTU-BEGIN v1 linux\npkg.open-vm-tools=12.3.0\nrun.vmtoolsd=1\nVTU-END 2\n')

		when:
		VmReport w = VmReport.scan(vm('vm-clean-linux'), ctx(), win)
		VmReport l = VmReport.scan(vm('vm-clean-linux'), ctx(), lin)

		then:
		w.results.find { it.checkId == 'T2-VMWARE-TOOLS' }?.status == Status.FAIL
		w.origin == 'migrated'
		Views.originWhy(w).contains('VMware Tools in the guest')
		l.origin == 'not-migrated'
		Views.originWhy(l).contains('no VMware MAC, OS fields complete, no VMware Tools')
	}

	def "scan time estimate: 2 to 4 seconds per VM"() {
		expect:
		Views.scanEstimate(0) == 'A scan takes about 2 to 4 seconds per VM.'
		Views.scanEstimate(10) == "A scan takes about 2 to 4 seconds per VM: about a minute for this cluster's 10 VMs."
		Views.scanEstimate(90) == "A scan takes about 2 to 4 seconds per VM: about 3 to 6 minutes for this cluster's 90 VMs."
	}
}
