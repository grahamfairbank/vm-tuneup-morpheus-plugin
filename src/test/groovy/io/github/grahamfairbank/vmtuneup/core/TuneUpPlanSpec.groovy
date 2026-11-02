package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification

import static io.github.grahamfairbank.vmtuneup.core.Fixtures.*

class TuneUpPlanSpec extends Specification {

	def "an IDE import gets one outage with the disk bus and NIC fixes, in fix order"() {
		when:
		TuneUpPlan p = TuneUpPlan.of(VmReport.scan(vm('vm-ide-import'), ctx()))

		then:
		p.window == TuneUpPlan.Window.COLD
		p.cold*.checkId == ['T1-DISK-BUS', 'T1-NIC-MODEL']
		p.steps.every { it.check.category == Category.RECOMMENDATION }
		p.considerations*.checkId.every { !(it in p.steps*.checkId) }
		p.windowLabel.startsWith('One outage of this VM')
		p.windowCode == 'cold'
	}

	def "prerequisites are listed once, in the order the fixes need them"() {
		when:
		TuneUpPlan p = TuneUpPlan.of(VmReport.scan(vm('vm-ide-import'), ctx()))

		then:
		p.prepare.size() == p.prepare.toSet().size()
		p.prepare[0] == Tier1Catalog.BACKUP_FIRST
	}

	def "the projected score counts every planned fix as done and leaves considerations"() {
		when:
		VmReport r = VmReport.scan(vm('vm-legacy-dirty'), ctx())
		TuneUpPlan p = TuneUpPlan.of(r)

		then:
		p.now.value == r.score.value
		p.after.value >= p.now.value
		p.gain == p.after.value - p.now.value
		p.after.failed.every { it.check.category == Category.CONSIDERATION || it.check.ifYouFixIt.downtime == Downtime.HOST_CHANGE }
	}

	def "a clean VM has nothing to do"() {
		when:
		TuneUpPlan p = TuneUpPlan.of(VmReport.scan(vm('vm-clean-linux'), ctx()))

		then:
		p.window == TuneUpPlan.Window.NONE
		p.steps.isEmpty()
		p.gain == 0
		p.windowLabel == 'Nothing to do'
	}
}
