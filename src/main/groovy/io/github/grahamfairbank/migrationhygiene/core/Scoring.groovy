package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * A VM's hygiene score: 100 minus a penalty for each failed check (critical 25, warning 8, info 0 to 2),
 * never below 0, with a letter grade. Waived, passed, unknown and not-applicable results cost nothing, but
 * unknowns are counted so a VM the plugin couldn't fully read never looks clean by accident.
 */
@CompileStatic
final class Scoring {

	static final int CRITICAL_PENALTY = 25
	static final int WARNING_PENALTY = 8

	/** Lowest score for each grade, best first. Anything below the last one is F. */
	static final List<Map.Entry<String, Integer>> GRADES = Collections.unmodifiableList([
		(Map.Entry<String, Integer>) new AbstractMap.SimpleImmutableEntry<String, Integer>('A', 90),
		(Map.Entry<String, Integer>) new AbstractMap.SimpleImmutableEntry<String, Integer>('B', 80),
		(Map.Entry<String, Integer>) new AbstractMap.SimpleImmutableEntry<String, Integer>('C', 70),
		(Map.Entry<String, Integer>) new AbstractMap.SimpleImmutableEntry<String, Integer>('D', 60)
	])

	private Scoring() {}

	static int penalty(CheckResult r) {
		if (r.status != Status.FAIL) return 0
		switch (r.severity) {
			case Severity.CRITICAL: return CRITICAL_PENALTY
			case Severity.WARNING: return WARNING_PENALTY
			default: return r.check.infoPenalty
		}
	}

	static String grade(int score) {
		for (Map.Entry<String, Integer> g : GRADES) {
			if (score >= g.value) return g.key
		}
		return 'F'
	}

	/**
	 * Scores one VM. Each check counts once: if a check reports more than one result, its worst one is used.
	 */
	static Score score(Collection<CheckResult> results) {
		Map<String, CheckResult> worst = new LinkedHashMap<String, CheckResult>()
		Collection<CheckResult> all = results == null ? Collections.<CheckResult>emptyList() : results
		for (CheckResult r : all) {
			if (r == null) continue
			CheckResult seen = worst.get(r.checkId)
			if (seen == null || rank(r) > rank(seen)) worst.put(r.checkId, r)
		}
		int total = 0
		Map<Status, Integer> counts = new EnumMap<Status, Integer>(Status)
		for (Status s : Status.values()) counts.put(s, 0)
		List<CheckResult> failed = new ArrayList<CheckResult>()
		for (CheckResult r : worst.values()) {
			counts.put(r.status, counts.get(r.status) + 1)
			total += penalty(r)
			if (r.status == Status.FAIL) failed.add(r)
		}
		int value = Math.max(0, 100 - total)
		return new Score(value, grade(value), total, Collections.unmodifiableMap(counts), Collections.unmodifiableList(failed))
	}

	/** Orders results of the same check: a bigger penalty is worse; a failure beats anything that isn't one. */
	private static int rank(CheckResult r) {
		return penalty(r) * 10 + (r.status == Status.FAIL ? 5 : r.status == Status.UNKNOWN ? 2 : r.status == Status.WAIVED ? 1 : 0)
	}

	@ToString(includeNames = true, includes = ['value', 'grade', 'penalty'])
	static final class Score {
		final int value
		final String grade
		final int penalty
		final Map<Status, Integer> counts
		/** Failed results, in the order the checks ran. */
		final List<CheckResult> failed

		Score(int value, String grade, int penalty, Map<Status, Integer> counts, List<CheckResult> failed) {
			this.value = value
			this.grade = grade
			this.penalty = penalty
			this.counts = counts
			this.failed = failed
		}

		/** True when nothing came back unknown, so the score reflects every check that applies. */
		boolean isComplete() { counts.get(Status.UNKNOWN) == 0 }
	}
}
