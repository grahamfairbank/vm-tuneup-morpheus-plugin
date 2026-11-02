package io.github.grahamfairbank.migrationhygiene.core

import io.github.grahamfairbank.migrationhygiene.Views
import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.migrationhygiene.core.Fixtures.*

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
		Views.originLabel(r.origin) == 'No VMware signal'
		Views.originWhy(r) == 'managed VM, no VMware signal'
	}

	def "a VMware MAC alone: migrated"() {
		when:
		VmReport r = report { VmFacts f -> f.interfaces[0].mac = '00:50:56:00:00:01' }

		then:
		r.originSignals == ['T1-VMWARE-MAC']
		r.origin == 'migrated'
		Views.originLabel(r.origin) == 'Migrated from VMware'
		Views.originWhy(r) == 'VMware MAC address'
	}

	def "the native-migration OS typing gap alone: migrated"() {
		when:
		VmReport r = report { VmFacts f -> f.osType = null }

		then:
		r.results.find { it.checkId == 'T1-VMWARE-MAC' }.status == Status.PASS
		r.originSignals == ['T1-OS-TYPE-MISMATCH']
		r.origin == 'migrated'
		Views.originWhy(r) == 'OS type fields incomplete after migration'
	}

	def "both signals: migrated, both named"() {
		when:
		VmReport r = report { VmFacts f -> f.interfaces[0].mac = '00:0c:29:00:00:02'; f.osType = null }

		then:
		r.originSignals as Set == ['T1-VMWARE-MAC', 'T1-OS-TYPE-MISMATCH'] as Set
		Views.originWhy(r).split(', ') as Set == ['VMware MAC address', 'OS type fields incomplete after migration'] as Set
	}

	def "a discovered VM with no signal: unknown origin; with a signal: migrated"() {
		when:
		VmReport plain = report { VmFacts f -> f.managed = false }
		VmReport signal = report { VmFacts f -> f.managed = false; f.interfaces[0].mac = '00:50:56:00:00:03' }

		then:
		plain.origin == 'unknown'
		Views.originLabel(plain.origin) == 'Unknown origin'
		Views.originWhy(plain) == 'discovered VM, no VMware signal'
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
		g*.label == ['Migrated from VMware', 'No VMware signal', 'Unknown origin', 'All VMs']
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

	def "the VM table rows carry the origin, worst first"() {
		when:
		List<Map> rows = Views.vmRows([report(), report('vm-ide-import'), report { VmFacts f -> f.managed = false }])

		then:
		rows[0].origin == 'migrated'
		rows*.origin as Set == ['migrated', 'not-migrated', 'unknown'] as Set
		rows.every { it.originLabel && it.originWhy }
		rows*.score == rows*.score.sort(false)
	}
}
