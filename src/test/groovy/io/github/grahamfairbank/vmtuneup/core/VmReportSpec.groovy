package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.Fixtures.*

class VmReportSpec extends Specification {

	@Unroll
	def "#fixture scores #score (#grade)"() {
		when:
		VmReport r = VmReport.scan(vm(fixture), ctx())

		then:
		r.score.value == score
		r.score.grade == grade
		r.vmwareOrigin == vmware

		where:
		fixture                   | score | grade | vmware
		'vm-clean-linux'          | 100   | 'A'   | false
		'vm-ga-disconnected'      | 92    | 'A'   | false
		'vm-ide-import'           | 67    | 'D'   | true  // critical 25 + NIC 8 (QGA unknown: not reported)
		'vm-901-migrated-windows' | 84    | 'B'   | true  // OS typing 8 + snapshots 8
		'vm-legacy-dirty'         | 52    | 'F'   | true
	}

	def "findings are ordered by severity, then in fix-plan order"() {
		when:
		VmReport r = VmReport.scan(vm('vm-ide-import'), ctx())

		then:
		r.findings*.checkId == ['T1-DISK-BUS', 'T1-NIC-MODEL', 'T1-DISCOVERED', 'T1-MORPHEUS-AGENT', 'T1-VMWARE-MAC']
		r.findings[0].severity == Severity.CRITICAL
		r.count(Severity.CRITICAL) == 1
		r.count(Severity.WARNING) == 1
		r.count(Severity.INFO) == 3
	}

	def "unknown and not-evaluated results carry no penalty but are listed"() {
		when:
		VmReport r = VmReport.scan(vm('vm-ide-import'), ctx())

		then:
		r.undecided*.checkId as Set == ['T1-CHIPSET', 'T1-FIRMWARE', 'T1-GA-CHANNEL', 'T1-GA-STATUS', 'T1-HA-MOBILITY', 'T1-NESTED-VIRT'] as Set
		r.undecided.every { Scoring.penalty(it) == 0 }
		!r.score.complete
	}

	def "a clean VM in the fixtures lists only mobility as undecided (fixtures carry no datastore type)"() {
		expect:
		VmReport.scan(vm('vm-clean-linux'), ctx()).undecided*.checkId == ['T1-HA-MOBILITY']
	}
}
