package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification
import spock.lang.Unroll

class ScoringSpec extends Specification {

	static Check check(String id, Severity severity, int infoPenalty = 0) {
		new Check(id: id, title: "Test ${id}", tier: Tier.ONE, severity: severity, appliesTo: AppliesTo.ANY,
			evidence: 'test field', ifYouLeaveIt: 'Something stays slow.',
			ifYouFixIt: [summary: 'Change it.', downtime: Downtime.NONE, risk: Risk.LOW, reversible: true],
			infoPenalty: infoPenalty, category: Category.CONSIDERATION, whyConsideration: 'Test.',
			references: [new Reference('Test', 'https://example.com/')])
	}

	static CheckResult fail(Check c, Severity s = null) { new CheckResult(c, Status.FAIL, s) }

	static final Check CRIT = check('T1-CRIT', Severity.CRITICAL)
	static final Check WARN = check('T1-WARN', Severity.WARNING)
	static final Check WARN2 = check('T1-WARN-TWO', Severity.WARNING)
	static final Check INFO0 = check('T1-INFO', Severity.INFO)
	static final Check INFO2 = check('T1-INFO-TWO', Severity.INFO, 2)

	def "no results scores a clean 100 A"() {
		when:
		Scoring.Score s = Scoring.score([])

		then:
		s.value == 100
		s.grade == 'A'
		s.penalty == 0
		s.complete
		s.failed.isEmpty()
	}

	def "null input is treated as no results"() {
		expect:
		Scoring.score(null).value == 100
	}

	def "penalties: critical 25, warning 8, info 0 to 2"() {
		expect:
		Scoring.score([fail(CRIT)]).value == 75
		Scoring.score([fail(WARN)]).value == 92
		Scoring.score([fail(INFO0)]).value == 100
		Scoring.score([fail(INFO2)]).value == 98
		Scoring.score([fail(CRIT), fail(WARN), fail(WARN2), fail(INFO2)]).value == 100 - 25 - 8 - 8 - 2
	}

	def "only failures cost points"() {
		expect:
		Scoring.score([new CheckResult(CRIT, status)]).value == 100

		where:
		status << [Status.PASS, Status.UNKNOWN, Status.NOT_APPLICABLE, Status.WAIVED, Status.NOT_EVALUATED]
	}

	def "a result can raise the check's severity"() {
		expect: 'a warning check reporting critical (boot disk on IDE) costs 25'
		Scoring.score([fail(WARN, Severity.CRITICAL)]).value == 75
	}

	def "each check counts once, using its worst result"() {
		expect:
		Scoring.score([fail(WARN), fail(WARN)]).value == 92
		Scoring.score([fail(WARN), fail(WARN, Severity.CRITICAL), new CheckResult(WARN, Status.PASS)]).value == 75
		Scoring.score([new CheckResult(WARN, Status.PASS), fail(WARN)]).failed.size() == 1
	}

	def "score never goes below 0"() {
		given:
		List<CheckResult> many = (1..6).collect { fail(check("T1-C${it}", Severity.CRITICAL)) }

		expect:
		Scoring.score(many).value == 0
		Scoring.score(many).grade == 'F'
		Scoring.score(many).penalty == 150
	}

	@Unroll
	def "grade for #score is #grade"() {
		expect:
		Scoring.grade(score) == grade

		where:
		score | grade
		100   | 'A'
		90    | 'A'
		89    | 'B'
		80    | 'B'
		79    | 'C'
		70    | 'C'
		69    | 'D'
		60    | 'D'
		59    | 'F'
		0     | 'F'
	}

	def "one critical alone drops a VM to C; one warning keeps it at A"() {
		expect:
		Scoring.score([fail(CRIT)]).grade == 'C'
		Scoring.score([fail(WARN)]).grade == 'A'
		Scoring.score([fail(WARN), fail(WARN2)]).grade == 'B'
	}

	def "unknown results are counted and make the score incomplete"() {
		when:
		Scoring.Score s = Scoring.score([new CheckResult(WARN, Status.UNKNOWN), fail(INFO2), new CheckResult(CRIT, Status.WAIVED)])

		then:
		!s.complete
		s.counts[Status.UNKNOWN] == 1
		s.counts[Status.FAIL] == 1
		s.counts[Status.WAIVED] == 1
		s.counts[Status.PASS] == 0
		s.failed*.checkId == ['T1-INFO-TWO']
	}
}
