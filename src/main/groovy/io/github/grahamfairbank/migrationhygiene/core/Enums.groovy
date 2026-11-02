package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic

/** How serious a failed check is. The score penalty comes from this (see {@link Scoring}). */
@CompileStatic
enum Severity {
	CRITICAL, WARNING, INFO
}

/** Which guest operating systems a check applies to. */
@CompileStatic
enum AppliesTo {
	WINDOWS, LINUX, ANY
}

/** Tier 1 reads only what Morpheus already knows; Tier 2 runs read-only commands inside the guest. */
@CompileStatic
enum Tier {
	ONE(1), TWO(2)

	final int number

	Tier(int number) { this.number = number }
}

/** Downtime a fix needs, from least to most disruptive. */
@CompileStatic
enum Downtime {
	/** Can be done while the VM runs. */
	NONE,
	/** The guest OS restarts; the VM stays powered on in Morpheus. */
	GUEST_REBOOT,
	/** Morpheus powers the VM off, changes its hardware, then powers it on again. */
	COLD_RECONFIGURE,
	/** The VM moves to another host or datastore, or its source system changes (for example the VMware side). */
	HOST_CHANGE
}

/**
 * Recommendation: something the operator can do in the Morpheus UI or inside the guest OS.
 * Consideration: worth knowing, but not a Morpheus action (needs host-side work, re-provisioning or HPE guidance).
 */
@CompileStatic
enum Category {
	RECOMMENDATION, CONSIDERATION
}

/** How likely a fix is to cause trouble when done as described. */
@CompileStatic
enum Risk {
	LOW, MEDIUM, HIGH
}

/** Outcome of one check on one VM. Only FAIL costs points; UNKNOWN and NOT_EVALUATED are listed, never scored. */
@CompileStatic
enum Status {
	PASS,
	FAIL,
	/** The data needed isn't available (for example a discovered VM with no config). Never counts as a pass. */
	UNKNOWN,
	/** The check doesn't apply to this VM (for example a Windows check on Linux). */
	NOT_APPLICABLE,
	/** The check is defined but not run yet, because the data it needs is still being verified. */
	NOT_EVALUATED,
	/** Failed, but an operator accepted it while a plan is in progress. */
	WAIVED
}
