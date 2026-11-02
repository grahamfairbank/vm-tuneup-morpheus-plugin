package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/** One VM's scan: its facts, every check result, the score, and the derived flags. */
@CompileStatic
final class VmReport {

	static final Set<String> VMWARE_ORIGIN_CHECKS = Collections.unmodifiableSet(['T1-VMWARE-MAC', 'T1-OS-TYPE-MISMATCH'] as Set<String>)

	final VmFacts facts
	final List<CheckResult> results
	final Scoring.Score score
	/** The guest scan behind the Tier 2 results; null when the guest wasn't scanned. */
	final GuestFacts guest

	VmReport(VmFacts facts, List<CheckResult> results, GuestFacts guest = null) {
		this.facts = facts
		this.guest = guest
		this.results = Collections.unmodifiableList(new ArrayList<CheckResult>(results ?: Collections.<CheckResult>emptyList()))
		this.score = Scoring.score(this.results)
	}

	static VmReport scan(VmFacts facts, ScanContext ctx) {
		return new VmReport(facts, Tier1Rules.evaluate(facts, ctx))
	}

	/** Tier 1, plus the Tier 2 checks when a guest scan parsed cleanly (0.7). */
	static VmReport scan(VmFacts facts, ScanContext ctx, GuestFacts guest) {
		List<CheckResult> r = new ArrayList<CheckResult>(Tier1Rules.evaluate(facts, ctx))
		if (guest != null && guest.ok) r.addAll(Tier2Rules.evaluate(guest, Tier2Input.fromVm(facts)))
		return new VmReport(facts, r, guest != null && guest.ok ? guest : null)
	}

	/** Failed results, worst first (critical, warning, info), then in catalog (fix-plan) order. */
	List<CheckResult> getFindings() {
		List<CheckResult> f = results.findAll { CheckResult r -> r.status == Status.FAIL }
		return f.sort(false) { CheckResult a, CheckResult b -> a.severity.ordinal() <=> b.severity.ordinal() ?: order(a) <=> order(b) }
	}

	/** Results the plugin couldn't decide or hasn't run yet: listed, never scored. */
	List<CheckResult> getUndecided() {
		return results.findAll { CheckResult r -> r.status == Status.UNKNOWN || r.status == Status.NOT_EVALUATED }
	}

	List<CheckResult> getPassed() {
		return results.findAll { CheckResult r -> r.status == Status.PASS || r.status == Status.NOT_APPLICABLE }
	}

	int count(Severity s) {
		return results.count { CheckResult r -> r.status == Status.FAIL && r.severity == s } as int
	}

	/** True when a VMware MAC or the native-migration OS typing gap was found. */
	boolean isVmwareOrigin() {
		return results.any { CheckResult r -> r.status == Status.FAIL && r.checkId in VMWARE_ORIGIN_CHECKS }
	}

	static final String ORIGIN_MIGRATED = 'migrated'
	static final String ORIGIN_NOT_MIGRATED = 'not-migrated'
	static final String ORIGIN_UNKNOWN = 'unknown'

	/** The checks that marked this VM as VMware origin (failed T1-VMWARE-MAC, T1-OS-TYPE-MISMATCH), in catalog order. */
	List<String> getOriginSignals() {
		return results.findAll { CheckResult r -> r.status == Status.FAIL && r.checkId in VMWARE_ORIGIN_CHECKS }
			.sort(false) { CheckResult r -> order(r) }.collect { CheckResult r -> r.checkId }
	}

	/**
	 * Where the VM came from, for grouping: "migrated" (a VMware-origin signal), "not-migrated" (a managed VM with
	 * no signal), or "unknown" (a discovered VM with no signal: created outside Morpheus or moved by another tool).
	 */
	String getOrigin() {
		if (vmwareOrigin) return ORIGIN_MIGRATED
		return facts.managed ? ORIGIN_NOT_MIGRATED : ORIGIN_UNKNOWN
	}

	/** Position in the catalog, which is also the fix order. */
	static int order(CheckResult r) {
		int i = CheckCatalog.ORDER.indexOf(r.check)
		return i < 0 ? Integer.MAX_VALUE : i
	}
}
