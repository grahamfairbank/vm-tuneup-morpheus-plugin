package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * "If you fix it": what a fix costs. Every check must say how much downtime it needs, how risky it is,
 * whether it can be undone, and what has to happen first (for example VirtIO drivers before a disk bus switch).
 */
@CompileStatic
@EqualsAndHashCode
@ToString(includeNames = true)
final class FixImpact {

	final String summary
	final Downtime downtime
	final Risk risk
	final boolean reversible
	/** Steps that must be done before this fix, in order. Empty when there are none. */
	final List<String> prerequisites

	FixImpact(Map args) {
		this.summary = Check.text(args.summary, 'ifYouFixIt.summary')
		this.downtime = Check.required(args.downtime, Downtime, 'ifYouFixIt.downtime')
		this.risk = Check.required(args.risk, Risk, 'ifYouFixIt.risk')
		if (!(args.reversible instanceof Boolean)) throw new IllegalArgumentException('ifYouFixIt.reversible must be true or false')
		this.reversible = (Boolean) args.reversible
		List<String> pre = new ArrayList<String>()
		for (Object p : ((args.prerequisites ?: []) as List)) pre.add(Check.text(p, 'ifYouFixIt.prerequisites[]'))
		this.prerequisites = Collections.unmodifiableList(pre)
	}
}
