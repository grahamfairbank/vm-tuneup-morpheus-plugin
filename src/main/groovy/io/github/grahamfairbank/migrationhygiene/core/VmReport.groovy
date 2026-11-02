package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic

/** One VM's scan: its facts, every check result, the score, and the derived flags. */
@CompileStatic
final class VmReport {

	static final Set<String> VMWARE_ORIGIN_CHECKS = Collections.unmodifiableSet(['T1-VMWARE-MAC', 'T1-OS-TYPE-MISMATCH'] as Set<String>)

	final VmFacts facts
	final List<CheckResult> results
	final Scoring.Score score

	VmReport(VmFacts facts, List<CheckResult> results) {
		this.facts = facts
		this.results = Collections.unmodifiableList(new ArrayList<CheckResult>(results ?: Collections.<CheckResult>emptyList()))
		this.score = Scoring.score(this.results)
	}

	static VmReport scan(VmFacts facts, ScanContext ctx) {
		return new VmReport(facts, Tier1Rules.evaluate(facts, ctx))
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

	private static int order(CheckResult r) {
		int i = Tier1Catalog.ALL.indexOf(r.check)
		return i < 0 ? Integer.MAX_VALUE : i
	}
}
