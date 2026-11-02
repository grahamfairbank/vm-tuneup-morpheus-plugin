package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/** The outcome of one check on one VM. */
@CompileStatic
@EqualsAndHashCode
@ToString(includeNames = true, includes = ['checkId', 'status', 'severity'])
final class CheckResult {

	final Check check
	final Status status
	/** Severity of this finding: the check's default unless the check raised or lowered it. */
	final Severity severity
	/** What was seen, with the actual field values (e.g. "Boot disk 'root': diskMode ide, controller kvm-ide"). */
	final String detail
	/** Extra notes for this VM (for example which ordering rule applies to its OS). */
	final List<String> notes

	CheckResult(Check check, Status status, Severity severity = null, String detail = null, List<String> notes = null) {
		if (check == null) throw new IllegalArgumentException('check is required')
		if (status == null) throw new IllegalArgumentException('status is required')
		this.check = check
		this.status = status
		this.severity = severity ?: check.severity
		this.detail = detail
		this.notes = Collections.unmodifiableList(new ArrayList<String>(notes ?: Collections.<String>emptyList()))
	}

	String getCheckId() { check.id }
}
