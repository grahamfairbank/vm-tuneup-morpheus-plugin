package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * The definition of one hygiene check: what it looks at, how serious a failure is, and what leaving it or fixing
 * it means. Plain Groovy with no Morpheus types, so the catalog and scoring can be tested on their own.
 * Definitions are validated when built: a check without a clear impact description can't exist.
 */
@CompileStatic
@EqualsAndHashCode
@ToString(includeNames = true, includes = ['id', 'tier', 'severity'])
final class Check {

	static final java.util.regex.Pattern ID = ~/^T[12]-[A-Z0-9]+(-[A-Z0-9]+)*$/
	/** Largest penalty an info finding may carry. */
	static final int MAX_INFO_PENALTY = 2

	/** Stable id, e.g. T1-DISK-BUS. Its prefix matches the tier. */
	final String id
	final String title
	final Tier tier
	/** Default severity. A result may raise or lower it (e.g. critical when the boot disk is on IDE). */
	final Severity severity
	final AppliesTo appliesTo
	/** What the check reads, in plain words (fields, or the guest command for Tier 2). */
	final String evidence
	/** "If you leave it": the effect today on performance, supportability or risk. */
	final String ifYouLeaveIt
	/** "If you fix it": downtime, risk, undo and prerequisites. */
	final FixImpact ifYouFixIt
	/** Whether an operator may waive a failure while a plan is in progress. */
	final boolean waivable
	/** Penalty for an info finding, 0 to 2 (default 0). Ignored for other severities. */
	final int infoPenalty
	/** Recommendation (a Morpheus UI or guest OS action) or Consideration (not a Morpheus action). */
	final Category category
	/** For a Recommendation: the exact place in the Morpheus UI (or "inside the guest OS: ..."). */
	final String howInVme
	/** For a Consideration: why it isn't a Morpheus action. */
	final String whyConsideration
	/** Public sources, HPE docs first. At least one. */
	final List<Reference> references
	/** Statements that still need confirming, shown as "Not yet verified". */
	final List<String> unverified

	Check(Map args) {
		this.id = text(args.id, 'id')
		if (!ID.matcher(id).matches()) throw new IllegalArgumentException("id '${id}' must look like T1-DISK-BUS")
		this.title = text(args.title, 'title')
		this.tier = required(args.tier, Tier, 'tier')
		if (!id.startsWith("T${tier.number}-")) throw new IllegalArgumentException("id '${id}' doesn't match tier ${tier.number}")
		this.severity = required(args.severity, Severity, 'severity')
		this.appliesTo = required(args.appliesTo ?: AppliesTo.ANY, AppliesTo, 'appliesTo')
		this.evidence = text(args.evidence, 'evidence')
		this.ifYouLeaveIt = text(args.ifYouLeaveIt, 'ifYouLeaveIt')
		Object fix = args.ifYouFixIt
		this.ifYouFixIt = fix instanceof FixImpact ? (FixImpact) fix : fix instanceof Map ? new FixImpact((Map) fix) : null
		if (ifYouFixIt == null) throw new IllegalArgumentException('ifYouFixIt is required')
		this.waivable = args.waivable == null ? true : (args.waivable as Boolean)
		int p = args.infoPenalty == null ? 0 : (args.infoPenalty as Integer)
		if (p < 0 || p > MAX_INFO_PENALTY) throw new IllegalArgumentException("infoPenalty must be 0 to ${MAX_INFO_PENALTY}")
		if (p != 0 && severity != Severity.INFO) throw new IllegalArgumentException('infoPenalty is only for info checks')
		this.infoPenalty = p
		this.category = required(args.category, Category, 'category')
		this.howInVme = args.howInVme?.toString()?.trim() ?: null
		this.whyConsideration = args.whyConsideration?.toString()?.trim() ?: null
		if (category == Category.RECOMMENDATION && !howInVme) throw new IllegalArgumentException('a Recommendation needs howInVme')
		if (category == Category.CONSIDERATION && !whyConsideration) throw new IllegalArgumentException('a Consideration needs whyConsideration')
		List<Reference> refs = new ArrayList<Reference>()
		for (Object r : ((args.references ?: []) as List)) {
			if (r instanceof Reference) refs.add((Reference) r)
			else if (r instanceof Map) refs.add(new Reference(((Map) r).label as String, ((Map) r).url as String))
			else throw new IllegalArgumentException('references[] must be Reference or [label, url]')
		}
		if (refs.isEmpty()) throw new IllegalArgumentException('at least one reference is required')
		this.references = Collections.unmodifiableList(refs)
		List<String> uv = new ArrayList<String>()
		for (Object u : ((args.unverified ?: []) as List)) uv.add(text(u, 'unverified[]'))
		this.unverified = Collections.unmodifiableList(uv)
	}

	static String text(Object v, String field) {
		String s = v?.toString()?.trim()
		if (!s) throw new IllegalArgumentException("${field} is required")
		return s
	}

	static <E extends Enum<E>> E required(Object v, Class<E> type, String field) {
		if (v == null) throw new IllegalArgumentException("${field} is required")
		if (type.isInstance(v)) return type.cast(v)
		try {
			return Enum.valueOf(type, v.toString().trim().toUpperCase())
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("${field} '${v}' is not one of ${type.enumConstants*.name()}")
		}
	}
}
