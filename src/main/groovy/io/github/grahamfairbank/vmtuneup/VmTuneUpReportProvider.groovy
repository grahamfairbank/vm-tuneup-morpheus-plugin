package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.VmReport

/**
 * The fleet report (Operations > Reports): one run is one scan of every HVM VM, stored by Morpheus with its rows:
 * a summary (header section) and one row per VM (main section, which Morpheus can export as CSV).
 */
@Slf4j
class VmTuneUpReportProvider extends AbstractReportProvider {

	VmTuneUpPlugin plugin
	MorpheusContext morpheus

	VmTuneUpReportProvider(VmTuneUpPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'vm-tuneup-report' }
	@Override String getName() { VmTuneUpPlugin.NAME }

	@Override
	String getDescription() { 'Read-only audit of HVM VMs: migration leftovers and HVM best-practice gaps, with a grade per VM (unofficial community plugin).' }

	@Override String getCategory() { 'inventory' }
	@Override Boolean getOwnerOnly() { false }
	/** Fleet-wide results cross tenants, so only the master tenant may run it until tenant scoping is designed. */
	@Override Boolean getMasterOnly() { true }
	@Override Boolean getSupportsAllZoneTypes() { true }
	@Override List<OptionType> getOptionTypes() { [] }

	@Override
	ServiceResponse validateOptions(Map opts) { ServiceResponse.success() }

	static final String NEED_VIEW_ROW = 'You need the VM Tune-Up view permission ("VM Tune-Up: View results" at read) to run this report.'

	/**
	 * 0.4.0: only a creator with "VM Tune-Up: View results" gets a scan; anyone else (or a creator whose
	 * permissions can't be read) gets one row saying so, and nothing is scanned or stored.
	 */
	@Override
	void process(ReportResult reportResult) {
		try {
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()
			Map perms = creatorPermissions(morpheus, reportResult)
			if (perms == null || !VmTuneUpPlugin.canView(new User(permissions: perms as Map<String, String>))) {
				morpheus.report.appendResultRows(reportResult, [new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: 0L,
					dataMap: [notice: NEED_VIEW_ROW])]).blockingGet()
				morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
				return
			}
			FleetScan scan = new FleetScan(morpheus)
			Boolean permitted = VmTuneUpPlugin.canGuestScan(new User(permissions: perms as Map<String, String>))
			List<VmReport> reports = scan.scan(scan.hvmVms(), null, plugin.guestProbe(morpheus), FleetScan.reportPolicy(permitted))
			List<ReportResultRow> rows = buildRows(reports, new Date())
			for (List<ReportResultRow> batch : rows.collate(50)) {
				morpheus.report.appendResultRows(reportResult, batch).blockingGet()
			}
			morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
		} catch (Throwable e) {
			log.warn("vm-tuneup: report run failed: ${e}")
			try { morpheus.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait() } catch (Throwable ignored) {}
		}
	}

	/**
	 * Whether the user who ran the report has "VM Tune-Up: Run guest scans" at full: from the report's
	 * createdBy, reading the user by id when createdBy carries no permissions. Null when that can't be determined
	 * (then nothing is probed).
	 */
	static Boolean guestScanGrant(MorpheusContext morpheus, ReportResult reportResult) {
		Map p = creatorPermissions(morpheus, reportResult)
		if (p == null) return null
		return VmTuneUpPlugin.canGuestScan(new User(permissions: p as Map<String, String>))
	}

	/** The permissions of the user who ran the report (read by id when createdBy carries none), or null. */
	static Map creatorPermissions(MorpheusContext morpheus, ReportResult reportResult) {
		User u = (User) safe { reportResult?.createdBy }
		if (u == null) return null
		Map p = (Map) safe { u.permissions }
		if (!p && safe { u.id } != null) {
			User full = (User) safe { morpheus.services.admin.user.get(u.id) }
			p = (Map) safe { full?.permissions }
		}
		return p ?: null
	}

	private static Object safe(Closure c) {
		try { return c.call() } catch (Throwable ignored) { return null }
	}

	/**
	 * One header row with the fleet summary, then one main row per VM, worst first. Morpheus's CSV export uses the
	 * main section only, so main rows hold flat values.
	 */
	static List<ReportResultRow> buildRows(List<VmReport> reports, Date scannedAt) {
		List<ReportResultRow> rows = []
		Map summary = Views.summary(reports, 0)
		summary.remove('worst')
		summary.scannedAt = iso(scannedAt)
		rows << new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: 0L, dataMap: summary)
		long order = 0
		reports.sort(false) { VmReport a, VmReport b -> a.score.value <=> b.score.value ?: (a.facts.name ?: '') <=> (b.facts.name ?: '') }.each { VmReport r ->
			rows << new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: order++, dataMap: Views.reportRow(r))
		}
		return rows
	}

	static String iso(Date d) {
		java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'")
		f.setTimeZone(TimeZone.getTimeZone('UTC'))
		return f.format(d)
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		Map header = reportRowsBySection?.get(ReportResultRow.SECTION_HEADER)?.find { it?.dataMap }?.dataMap
		if (header?.notice) return Render.render(getRenderer(), Render.REPORT, [error: header.notice, rows: []])
		Map summary = header ?: Views.summary([], 0)
		List<Map> rows = (reportRowsBySection?.get(ReportResultRow.SECTION_MAIN) ?: [])
			.sort(false) { (it?.displayOrder ?: 0L) as Long }
			.collect { ReportResultRow r -> (r?.dataMap ?: [:]) + [gradeClass: Views.gradeClass(r?.dataMap?.grade as String)] }
		return Render.render(getRenderer(), Render.REPORT, [summary: summary, rows: rows])
	}
}
