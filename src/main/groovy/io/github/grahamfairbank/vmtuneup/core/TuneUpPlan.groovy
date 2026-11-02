package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/**
 * The tune-up plan for one VM: its Recommendations grouped by the downtime they need, so every fix that needs the
 * VM powered off happens in one maintenance window, in catalog (fix) order, after the steps that must come first.
 * Considerations are listed apart: they're never part of the plan.
 *
 * "After" is the score the VM would have with every Recommendation in the plan done; Considerations and checks the
 * plugin couldn't decide keep their current state.
 */
@CompileStatic
final class TuneUpPlan {

	/** The biggest interruption the plan needs, from none to a cold window. */
	enum Window {
		/** Nothing to do. */
		NONE,
		/** Everything can be done while the VM runs. */
		LIVE,
		/** At least one fix restarts the guest OS; the VM stays powered on. */
		GUEST_REBOOT,
		/** At least one fix needs Morpheus to power the VM off and change its hardware. */
		COLD
	}

	final VmReport report
	/** Prerequisites of the restart and cold-window fixes, de-duplicated, in fix order, filtered to the VM's OS. */
	final List<String> prepare
	final List<CheckResult> live
	final List<CheckResult> guestReboot
	final List<CheckResult> cold
	/** Failed Considerations: worth knowing, not in the plan. */
	final List<CheckResult> considerations
	final Scoring.Score now
	final Scoring.Score after

	private TuneUpPlan(VmReport report) {
		this.report = report
		List<CheckResult> l = [], g = [], c = [], con = []
		List<CheckResult> failed = report.results.findAll { CheckResult r -> r.status == Status.FAIL }
			.sort(false) { CheckResult r -> VmReport.order(r) }
		for (CheckResult r : failed) {
			if (r.check.category != Category.RECOMMENDATION) { con.add(r); continue }
			switch (r.check.ifYouFixIt.downtime) {
				case Downtime.NONE: l.add(r); break
				case Downtime.GUEST_REBOOT: g.add(r); break
				case Downtime.COLD_RECONFIGURE: c.add(r); break
				default: con.add(r) // a host change is never a VM tune-up step
			}
		}
		// What must happen before the restart or cold window: prerequisites of those fixes only (a no-downtime fix
		// lists its own), and only the OS rules for this VM's OS when Morpheus knows it.
		String os = report.facts?.osFamilyKey
		LinkedHashSet<String> pre = new LinkedHashSet<String>()
		for (CheckResult r : (c + g)) {
			for (String p : r.check.ifYouFixIt.prerequisites) {
				if (os == 'linux' && p.startsWith('Windows')) continue
				if (os == 'windows' && p.startsWith('Linux')) continue
				pre.add(p)
			}
		}
		this.prepare = Collections.unmodifiableList(new ArrayList<String>(pre))
		this.live = Collections.unmodifiableList(l)
		this.guestReboot = Collections.unmodifiableList(g)
		this.cold = Collections.unmodifiableList(c)
		this.considerations = Collections.unmodifiableList(con)
		this.now = report.score
		List<CheckResult> projected = report.results.collect { CheckResult r ->
			(r.status == Status.FAIL && r.check.category == Category.RECOMMENDATION && r.check.ifYouFixIt.downtime != Downtime.HOST_CHANGE)
				? new CheckResult(r.check, Status.PASS, null, 'Fixed in the tune-up plan.') : r
		}
		this.after = Scoring.score(projected)
	}

	static TuneUpPlan of(VmReport report) {
		return new TuneUpPlan(report)
	}

	Window getWindow() {
		if (!cold.isEmpty()) return Window.COLD
		if (!guestReboot.isEmpty()) return Window.GUEST_REBOOT
		if (!live.isEmpty()) return Window.LIVE
		return Window.NONE
	}

	/** All planned fixes in the order to do them: live ones first (they're free), then the reboot, then the cold window. */
	List<CheckResult> getSteps() {
		return Collections.unmodifiableList(live + guestReboot + cold)
	}

	int getGain() {
		return after.value - now.value
	}

	/** Plain-words label of the window, for the tab and the CSV. */
	String getWindowLabel() {
		switch (window) {
			case Window.COLD: return "One cold window (VM powered off once) for ${cold.size()} fix${cold.size() == 1 ? '' : 'es'}"
			case Window.GUEST_REBOOT: return 'One guest restart'
			case Window.LIVE: return 'No downtime'
			default: return 'Nothing to do'
		}
	}

	/** The same as a short code for the CSV and filters. */
	String getWindowCode() {
		return window.name().toLowerCase().replace('_', '-')
	}
}
