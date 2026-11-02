package io.github.grahamfairbank.migrationhygiene

import io.github.grahamfairbank.migrationhygiene.core.Category
import io.github.grahamfairbank.migrationhygiene.core.Check
import io.github.grahamfairbank.migrationhygiene.core.CheckResult
import io.github.grahamfairbank.migrationhygiene.core.Downtime
import io.github.grahamfairbank.migrationhygiene.core.Severity
import io.github.grahamfairbank.migrationhygiene.core.Status
import io.github.grahamfairbank.migrationhygiene.core.VmReport

/**
 * Plain maps of strings, numbers and booleans for the Handlebars templates. Templates only use {{ }} (escaped),
 * never {{{ }}}, so nothing here can inject markup; every value still comes out as text.
 */
class Views {

	static final String DISCLAIMER = 'Unofficial community plugin. Not an HPE product and not supported by HPE.'

	static String label(Severity s) { s == null ? '' : s.name().toLowerCase().capitalize() }

	static String label(Downtime d) {
		switch (d) {
			case Downtime.NONE: return 'None'
			case Downtime.GUEST_REBOOT: return 'Guest reboot'
			case Downtime.COLD_RECONFIGURE: return 'Cold reconfigure (Morpheus powers the VM off, applies the change and starts it)'
			case Downtime.HOST_CHANGE: return 'Host or source change'
			default: return String.valueOf(d)
		}
	}

	static String label(Category c) { c == Category.RECOMMENDATION ? 'Recommendation' : 'Consideration' }

	/** The check ID as shown to users: without the tier prefix (T1-DISK-BUS shows as DISK-BUS). */
	static String shortId(String id) { id ? id.replaceFirst(/^T\d-/, '') : '' }

	static final String ABOUT_CHECKS = 'About the checks: they use Morpheus data only (nothing runs inside the VM, except the optional ' +
		'guest agent check). Later versions add read-only checks inside the guest.'

	static String gradeClass(String grade) { "mh-grade-${(grade ?: 'f').toLowerCase()}" }

	static Map finding(CheckResult r) {
		Check c = r.check
		return [id: shortId(c.id), fullId: c.id, title: c.title, severity: label(r.severity), sevClass: "mh-sev-${r.severity.name().toLowerCase()}",
		        detail: r.detail ?: '', notes: r.notes, evidence: c.evidence, leave: c.ifYouLeaveIt,
		        fixSummary: c.ifYouFixIt.summary, downtime: label(c.ifYouFixIt.downtime),
		        risk: c.ifYouFixIt.risk.name().toLowerCase().capitalize(), reversible: c.ifYouFixIt.reversible ? 'Yes' : 'No',
		        prerequisites: c.ifYouFixIt.prerequisites, hasPrerequisites: !c.ifYouFixIt.prerequisites.isEmpty(),
		        category: label(c.category), catClass: c.category == Category.RECOMMENDATION ? 'mh-cat-rec' : 'mh-cat-con',
		        isRecommendation: c.category == Category.RECOMMENDATION, howInVme: c.howInVme ?: '', whyConsideration: c.whyConsideration ?: '',
		        unverified: c.unverified, hasUnverified: !c.unverified.isEmpty(),
		        references: c.references.collect { [label: it.label, url: it.url] }]
	}

	static Map vm(VmReport r) {
		def f = r.facts
		List<Map> findings = r.findings.collect { finding(it) }
		List<Map> undecided = r.undecided.collect { CheckResult u ->
			[id: shortId(u.checkId), fullId: u.checkId, title: u.check.title, status: u.status == Status.NOT_EVALUATED ? 'Not evaluated (pending verification)' : 'Unknown',
			 detail: u.detail ?: '', notes: u.notes]
		}
		List<Map> passed = r.passed.collect { CheckResult p ->
			[id: shortId(p.checkId), fullId: p.checkId, title: p.check.title, status: p.status == Status.PASS ? 'Pass' : 'Not applicable', detail: p.detail ?: '']
		}
		return [name: f.name ?: "VM ${f.id}", os: f.osLabel, cluster: f.clusterName ?: '', host: f.hostName ?: '',
		        serverType: f.serverType ?: 'unknown', managed: f.managed ? 'Managed' : 'Discovered (not managed)',
		        power: f.powerState ?: 'unknown', score: r.score.value, grade: r.score.grade, gradeClass: gradeClass(r.score.grade),
		        vmwareOrigin: r.vmwareOrigin, origin: r.origin, originLabel: originLabel(r.origin), originWhy: originWhy(r), critical: r.count(Severity.CRITICAL), warning: r.count(Severity.WARNING),
		        info: r.count(Severity.INFO), findings: findings, hasFindings: !findings.isEmpty(),
		        undecided: undecided, undecidedCount: undecided.size(), hasUndecided: !undecided.isEmpty(),
		        passed: passed, passedCount: passed.size()]
	}

	/** The worst findings as plain titles with their severity, e.g. "Disk on an emulated SATA or IDE bus (critical)". */
	static String topFindings(VmReport r, int n = 3) {
		return r.findings.take(n).collect { "${it.check.title} (${it.severity.name().toLowerCase()})" }.join('; ')
	}

	/** Every failed check, as short IDs (no tier prefix), worst first: "DISK-BUS; NIC-MODEL". */
	static String checkIds(VmReport r) {
		return r.findings.collect { shortId(it.checkId) }.join('; ')
	}

	// ---- origin (0.3.0) ----

	static final List<String> ORIGINS = Collections.unmodifiableList([VmReport.ORIGIN_MIGRATED, VmReport.ORIGIN_NOT_MIGRATED, VmReport.ORIGIN_UNKNOWN])

	static String originLabel(String origin) {
		switch (origin) {
			case VmReport.ORIGIN_MIGRATED: return 'Migrated from VMware'
			case VmReport.ORIGIN_NOT_MIGRATED: return 'No VMware signal'
			default: return 'Unknown origin'
		}
	}

	/** Why the VM got its origin, in a few words (shown as a tooltip and under the label). */
	static String originWhy(VmReport r) {
		List<String> sig = r.originSignals
		if (sig) return sig.collect { it == 'T1-VMWARE-MAC' ? 'VMware MAC address' : 'OS type fields incomplete after migration' }.join(', ')
		return r.facts.managed ? 'managed VM, no VMware signal' : 'discovered VM, no VMware signal'
	}

	static final String ORIGIN_NOTE = 'A VM counts as Migrated from VMware when it still has a VMware MAC address ' +
		'(prefix 00:50:56, 00:0C:29, 00:05:69 or 00:1C:14), or when its OS type fields are incomplete the way a native VMware migration ' +
		'leaves them. No VMware signal: a managed VM with neither signal; most are native HVM VMs, but a VM migrated through an image ' +
		'upload or another tool, with a new MAC address and corrected OS type, also lands here (the in-guest checks of a later version ' +
		'will spot VMware Tools and other leftovers). Unknown origin: a discovered (unmanaged) VM with neither signal; it may have been ' +
		'created outside Morpheus or moved by another tool.'

	/** Per origin group (and all VMs): VM count, grade counts and findings by severity. */
	static List<Map> originGroups(List<VmReport> reports) {
		List<VmReport> all = reports ?: []
		List<Map> out = ORIGINS.collect { String o -> group(originLabel(o), o, all.findAll { it.origin == o }) }
		out << group('All VMs', 'all', all)
		return out
	}

	private static Map group(String label, String key, List<VmReport> rs) {
		Map<String, Integer> grades = ['A': 0, 'B': 0, 'C': 0, 'D': 0, 'F': 0]
		rs.each { grades[it.score.grade] = (grades[it.score.grade] ?: 0) + 1 }
		return [label: label, origin: key, isAll: key == 'all', vmCount: rs.size(),
		        grades: grades.collect { k, v -> [grade: k, count: v, gradeClass: gradeClass(k)] },
		        critical: rs.sum(0) { it.count(Severity.CRITICAL) }, warning: rs.sum(0) { it.count(Severity.WARNING) },
		        info: rs.sum(0) { it.count(Severity.INFO) }]
	}

	/** Rows of the cluster view's VM table, worst first, each with its origin; the browser filters them. */
	static List<Map> vmRows(List<VmReport> reports, int max = 500) {
		List<VmReport> sorted = (reports ?: []).sort(false) { VmReport a, VmReport b -> a.score.value <=> b.score.value ?: (a.facts.name ?: '') <=> (b.facts.name ?: '') }
		return sorted.take(max).collect { VmReport r ->
			reportRow(r) + [gradeClass: gradeClass(r.score.grade), serverId: r.facts.id, originLabel: originLabel(r.origin), originWhy: originWhy(r)]
		}
	}

	/** The guest agent probe for the report row: "responded", "no-response", "not-probed: <reason>" or "". */
	static String probeLabel(def f) {
		if (!f.gaProbe) return ''
		if (f.gaProbe == 'not-probed' && f.gaProbeReason) return "not-probed: ${f.gaProbeReason}".toString()
		if (f.gaProbe == 'no-response' && f.gaProbeError) {
			String e = f.gaProbeError.toString()
			return "no-response: ${e.size() > 120 ? e.substring(0, 120) : e}".toString()
		}
		return f.gaProbe
	}

	/** One flat report row (CSV-friendly: Morpheus exports only the main section). */
	static Map reportRow(VmReport r) {
		def f = r.facts
		return [name: f.name ?: "VM ${f.id}", cluster: f.clusterName ?: '', host: f.hostName ?: '', os: f.osLabel,
		        serverType: f.serverType ?: '', managed: f.managed ? 'yes' : 'no', power: f.powerState ?: '',
		        grade: r.score.grade, score: r.score.value, critical: r.count(Severity.CRITICAL), warning: r.count(Severity.WARNING),
		        info: r.count(Severity.INFO), unknown: r.undecided.size(), vmwareOrigin: r.vmwareOrigin ? 'yes' : 'no', origin: r.origin,
		        guestAgentProbe: probeLabel(f), topFindings: topFindings(r), checkIds: checkIds(r)]
	}

	/** Fleet or cluster rollup: grade and severity counts, findings per check, and the worst VMs. */
	static Map summary(List<VmReport> reports, int worst = 10) {
		List<VmReport> all = reports ?: []
		Map<String, Integer> grades = ['A': 0, 'B': 0, 'C': 0, 'D': 0, 'F': 0]
		all.each { grades[it.score.grade] = (grades[it.score.grade] ?: 0) + 1 }
		Map<String, Map> byCheck = new LinkedHashMap<>()
		io.github.grahamfairbank.migrationhygiene.core.Tier1Catalog.ALL.each { Check c ->
			byCheck[c.id] = [id: shortId(c.id), fullId: c.id, title: c.title, failed: 0, unknown: 0, category: label(c.category)]
		}
		all.each { VmReport r ->
			r.results.each { CheckResult x ->
				Map row = byCheck[x.checkId]
				if (row == null) return
				if (x.status == Status.FAIL) row.failed = (row.failed as int) + 1
				if (x.status == Status.UNKNOWN || x.status == Status.NOT_EVALUATED) row.unknown = (row.unknown as int) + 1
			}
		}
		List<VmReport> sorted = all.sort(false) { VmReport a, VmReport b -> a.score.value <=> b.score.value ?: (a.facts.name ?: '') <=> (b.facts.name ?: '') }
		return [vmCount: all.size(),
		        grades: grades.collect { k, v -> [grade: k, count: v, gradeClass: gradeClass(k)] },
		        critical: all.sum(0) { it.count(Severity.CRITICAL) }, warning: all.sum(0) { it.count(Severity.WARNING) },
		        info: all.sum(0) { it.count(Severity.INFO) }, vmwareOrigin: all.count { it.vmwareOrigin },
		        byCheck: byCheck.values().toList(),
		        worst: sorted.take(worst).collect { reportRow(it) + [gradeClass: gradeClass(it.score.grade), serverId: it.facts.id] }]
	}

	// ---- 0.2.0: fragments served by the routes, and the CSV export ----

	static String ago(long at, long now) {
		long s = Math.max(0L, (long) ((now - at) / 1000L))
		if (s < 60) return 'just now'
		long m = (long) (s / 60)
		return m == 1 ? '1 minute ago' : "${m} minutes ago".toString()
	}

	static String problem(ScanService.VmEntry e) {
		if (e == null) return 'No result for this VM.'
		if (!e.found) return 'No such server.'
		if (e.error == ScanService.TIME_BUDGET) return 'Unknown: time budget. The scan of this VM did not finish in time; use Refresh to try again.'
		if (e.error) return "Unknown: ${e.error}.".toString()
		if (!e.hvm) return 'Not an HVM VM: Migration Hygiene checks only VMs on HVM clusters.'
		return null
	}

	static String agoShort(long at, long now) {
		long s = Math.max(0L, (long) ((now - at) / 1000L))
		if (s < 60) return 'just now'
		long m = (long) (s / 60)
		if (m < 60) return "${m} min ago".toString()
		return "${(long) (m / 60)} h ago".toString()
	}

	static final String PROBE_HINT = 'Runs a harmless no-op command in the VM through the QEMU guest agent to confirm it answers. ' +
		'Changes nothing. Needs the Migration Hygiene guest-scan permission.'
	static final String PROBE_LINUX_NOTE = 'On Linux, Morpheus runs commands through its own Morpheus agent, so this tests that agent, ' +
		'not the QEMU guest agent.'
	static final String PROBE_DENIED = 'You don\'t have permission.'
	static final int PROBE_ERROR_SHORT = 80

	/**
	 * The "Check guest agent" line of a VM block (0.3.0). {@code obs} is the probe cache's observation for this VM
	 * ({@link ScanService#probeState}), null when Morpheus reports a real guest agent status.
	 */
	static Map probeLine(VmReport r, boolean canProbe, Map obs, long now) {
		if (r == null) return [probeShown: false]
		String reported = r.facts.guestAgentStatus
		if (!GuestAgentProbe.needsProbe(reported)) {
			return [probeShown: true, probeReported: "Morpheus reports the guest agent status (${reported}), so no check is needed.".toString()]
		}
		Map m = [probeShown: true, probeHint: PROBE_HINT, probeLinuxNote: r.facts.windows ? null : PROBE_LINUX_NOTE]
		if (!canProbe) return m + [probeDenied: PROBE_DENIED]
		return m + [canProbe: true, probeLast: probeLast(obs, now)]
	}

	/** "Answered 2 min ago", "No answer 5 min ago: <short error>", "Not checked yet." or "Not checked: <reason>." */
	static String probeLast(Map obs, long now) {
		if (obs == null) return null
		String result = obs.result as String
		Long at = obs.at as Long
		String when = at != null ? ' ' + agoShort(at, now) : ''
		if (result == GuestAgentProbe.RESPONDED) return "Answered${when}".toString()
		if (result == GuestAgentProbe.NO_RESPONSE) {
			String err = obs.error ? obs.error.toString() : ''
			if (err.length() > PROBE_ERROR_SHORT) err = err.substring(0, PROBE_ERROR_SHORT - 1) + '\u2026'
			return "No answer${when}${err ? ': ' + err : ''}".toString()
		}
		if (result == GuestAgentProbe.NOT_PROBED) {
			String reason = obs.reason as String
			return reason == GuestAgentProbe.CACHE_ONLY || !reason ? 'Not checked yet.' : "Not checked: ${reason}.".toString()
		}
		return null
	}

	/** One VM block for mh-vm: the report view plus the tab's own state. */
	static Map vmBlock(ScanService.VmEntry e, VmReport r, boolean canProbe, long now, Map probeObs = null, String probeNote = null) {
		Map base = r != null ? vm(r) : [name: e?.name ?: "VM ${e?.serverId}".toString()]
		String p = problem(e)
		return base + probeLine(r, canProbe, probeObs, now) + [probeNote: canProbe ? probeNote : null, serverId: e?.serverId, problem: r != null ? null : (p ?: 'No result for this VM.'),
		               scannedAgo: e ? ago(e.at, now) : '', skippedText: e?.skipped ? e.skipped.join(', ') : null]
	}

	static final List<String> CSV_COLUMNS = Collections.unmodifiableList(['name', 'cluster', 'host', 'os', 'serverType', 'managed', 'power',
		'grade', 'score', 'critical', 'warning', 'info', 'unknown', 'vmwareOrigin', 'origin', 'guestAgentProbe', 'topFindings', 'checkIds', 'scanStatus', 'scannedAt'])

	/** One CSV row: the report's main-section columns, plus the scan status and time. */
	static Map csvRow(ScanService.VmEntry e, VmReport r, String clusterName) {
		Map row = r != null ? reportRow(r) : [name: e?.name ?: "VM ${e?.serverId}".toString(), cluster: clusterName ?: '', origin: VmReport.ORIGIN_UNKNOWN]
		if (!row.cluster && clusterName) row.cluster = clusterName
		row.scanStatus = r != null ? e.scanStatus : (e?.error ? "unknown: ${e.error}".toString() : (problem(e) ?: 'unknown'))
		row.scannedAt = e ? MigrationHygieneReportProvider.iso(new Date(e.at)) : ''
		return row
	}

	/** A CSV row as JSON for the clusterData route: exactly the CSV columns, in order (null cells as ""). */
	static Map dataRow(Map row) {
		Map out = new LinkedHashMap()
		CSV_COLUMNS.each { String c -> Object v = row[c]; out[c] = v == null ? '' : (v instanceof Number ? v : v.toString()) }
		return out
	}

	/** Worst first (by score), rows without a result last, then by name. */
	static List<Map> sortRows(List<Map> rows) {
		return rows.sort(false) { Map a, Map b ->
			int x = a.score == null ? 1 : 0
			int y = b.score == null ? 1 : 0
			x <=> y ?: ((a.score ?: 0) as int) <=> ((b.score ?: 0) as int) ?: (a.name ?: '').toString() <=> (b.name ?: '').toString()
		}
	}

	static String csv(List<Map> rows) {
		StringBuilder sb = new StringBuilder()
		sb.append(CSV_COLUMNS.collect { csvCell(it) }.join(',')).append('\r\n')
		rows.each { Map r -> sb.append(CSV_COLUMNS.collect { csvCell(r[it]) }.join(',')).append('\r\n') }
		return sb.toString()
	}

	/**
	 * Text cells a spreadsheet may read as a formula (0.4.0): starting with a tab or CR, or whose first
	 * non-whitespace character is = + - @ or | (DDE). The browser port uses the same pattern (FORMULA in
	 * migration-hygiene.js); the shared cases are in src/test/resources/csv-cell-cases.json.
	 */
	static final String FORMULA = '^(?:[\\t\\r]|[ \\t\\n\\x0B\\f\\r\\u00A0]*[=+\\-@|])'
	private static final java.util.regex.Pattern FORMULA_RE = java.util.regex.Pattern.compile(FORMULA)

	/**
	 * One CSV cell. The browser builds the same file from the clusterData JSON with a straight port of this
	 * function (csvCell in src/assets/javascript/migration-hygiene.js): keep the two identical. A text cell that
	 * matches {@link #FORMULA} gets a leading apostrophe; cells with commas, quotes or line breaks are quoted,
	 * quotes doubled. Numbers are left as they are.
	 */
	static String csvCell(Object v) {
		if (v == null) return ''
		String s = v.toString()
		if (!(v instanceof Number) && s && FORMULA_RE.matcher(s).find()) s = "'" + s
		if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) s = '"' + s.replace('"', '""') + '"'
		return s
	}

	/** migration-hygiene-<cluster>-<yyyy-MM-dd>.csv, with only safe characters. */
	static String csvFilename(String cluster, Date day) {
		String slug = (cluster ?: '').replaceAll(/[^A-Za-z0-9._-]+/, '-').replaceAll(/^[-.]+|-+$/, '')
		if (slug.length() > 60) slug = slug.substring(0, 60)
		if (!slug) slug = 'cluster'
		java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
		f.setTimeZone(TimeZone.getTimeZone('UTC'))
		return "migration-hygiene-${slug}-${f.format(day)}.csv".toString()
	}

	/** HTML-escapes text for the few messages built outside the templates. */
	static String esc(Object o) {
		String s = o == null ? '' : o.toString()
		return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;')
	}
}
