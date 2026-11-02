package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.migrationhygiene.core.VmReport

/**
 * Plugin web routes (0.3.0). The tabs render a shell; the plugin script fills it from these routes.
 *
 * <pre>
 *   GET  /plugin/migrationHygiene/vm?serverId=        VM fragment (view: read)
 *   POST /plugin/migrationHygiene/vmProbe   serverId   guest agent probe, then the VM fragment (view: read + guest-scan: full)
 *   GET  /plugin/migrationHygiene/cluster?clusterId=  cluster (fleet view) fragment; starts or joins the background scan (view: read)
 *   POST /plugin/migrationHygiene/clusterRescan clusterId  forces a new scan, then the fragment (view: read)
 *   GET  /plugin/migrationHygiene/clusterData?clusterId=  JSON: scan status and the CSV rows so far; starts or joins the scan (view: read)
 *   GET  /plugin/migrationHygiene/clusters          JSON: the clusters the user may see, ids and names (view: read)
 *   GET  /plugin/migrationHygiene/csv?clusterId= | ?all=true  CSV of the per-VM rows from the cache only (view: read; the
 *        fallback "direct download": the buttons build the file in the browser from clusterData)
 *   GET  /plugin/migrationHygiene/status            JSON: ok and the plugin version (view: read)
 * </pre>
 *
 * The Dispatcher only checks that a role has the permission code at all (even at "none"), so every route checks
 * the access level itself, and the tenant: the user's tenant must own the VM or cluster, unless it is the master
 * tenant. The tenant check comes before any scan, cache read or probe. On cluster views, a user outside the master
 * tenant sees only the VMs of their own tenant. POST routes also need X-Requested-With and a same-origin
 * Origin/Referer (pattern from com-morpheus-plugin, same author, MIT), on top of Morpheus's own CSRF filter.
 *
 * 0.4.0: a GET starts or joins a scan only when it is the plugin script's own request ({@link #scriptRequest}:
 * X-Requested-With plus a same-origin Origin, Referer or Sec-Fetch-Site). A plain navigation (a link, a new tab,
 * a cross-site page) gets cached results only, or a note to open the Migration Hygiene tab; it never starts work
 * and never runs a guest command. The CSV route never starts a scan.
 */
@Slf4j
class MigrationHygieneController implements PluginController {

	static final String STATUS = '/migrationHygiene/status'
	static final String VM = '/migrationHygiene/vm'
	static final String VM_PROBE = '/migrationHygiene/vmProbe'
	static final String CLUSTER = '/migrationHygiene/cluster'
	static final String CLUSTER_RESCAN = '/migrationHygiene/clusterRescan'
	static final String CSV = '/migrationHygiene/csv'
	static final String CLUSTER_DATA = '/migrationHygiene/clusterData'
	static final String CLUSTERS = '/migrationHygiene/clusters'

	static final String NEED_VIEW = 'Your role needs "Migration Hygiene: View results" at read access.'
	static final String NEED_GUEST = 'Your role needs "Migration Hygiene: Run guest scans" at full access.'
	static final int MAX_CLUSTERS = 50
	static final String OPEN_TAB_VM = 'Not scanned yet. Open the Migration Hygiene tab of this VM to start a scan.'
	static final String OPEN_TAB_CLUSTER = 'Not scanned yet. Open the Migration Hygiene tab of this cluster to start a scan.'
	static final String NOT_SCANNED = 'not scanned yet: open the Migration Hygiene tab of this cluster to start a scan'
	static final String STILL_RUNNING = 'scan still running'
	static final String NO_DIRECT_CSV = 'The direct download is not available on this appliance. Use the Download CSV buttons on the cluster\'s Migration Hygiene tab.'
	static final String JSON_TYPE = 'application/json; charset=utf-8'

	MigrationHygienePlugin plugin
	MorpheusContext morpheus
	/** Cluster id to [name, ownerId, typeCode]: polling reads the cluster once per 10 minutes, not every 5 seconds. */
	final ShortMemo<Long, Map> clusterInfo = new ShortMemo<Long, Map>(ScanService.RESULT_TTL_MS, 500)
	/** Server id to [found, accountId] (0.4.0): the VM route's tenant check, so polling doesn't read the server every 5 seconds. */
	final ShortMemo<Long, Map> vmOwners = new ShortMemo<Long, Map>(60_000L, 5000)

	MigrationHygieneController(MigrationHygienePlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override String getCode() { 'migration-hygiene-controller' }
	@Override String getName() { 'Migration Hygiene routes' }
	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }

	// Statically compiled: dynamic Permission/Route resolution has thrown MissingPropertyException inside
	// Morpheus for other plugins. Every route needs a permission: Morpheus 9.0.1 never matches one without (404).
	@groovy.transform.CompileStatic
	@Override
	List<Route> getRoutes() {
		List<Permission.AccessType> read = Collections.singletonList(Permission.AccessType.read)
		List<Permission.AccessType> full = Collections.singletonList(Permission.AccessType.full)
		List<Route> routes = new ArrayList<Route>()
		routes.add(Route.build(STATUS, 'status', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(VM, 'vm', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(VM_PROBE, 'vmProbe', Permission.build(MigrationHygienePlugin.GUEST_SCAN_PERMISSION, full)))
		routes.add(Route.build(CLUSTER, 'cluster', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(CLUSTER_RESCAN, 'clusterRescan', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(CSV, 'csv', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(CLUSTER_DATA, 'clusterData', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		routes.add(Route.build(CLUSTERS, 'clusters', Permission.build(MigrationHygienePlugin.VIEW_PERMISSION, read)))
		return routes
	}

	ScanService scans() { plugin.scans(morpheus) }

	// ---- status -------------------------------------------------------------------------------------

	/** ok and the plugin version, for users with the view permission; nothing else about the plugin's internals. */
	JsonResponse status(ViewModel<Map> model) {
		if (!MigrationHygienePlugin.canView(model?.user)) {
			return json(model, 403, [ok: false, error: NEED_VIEW])
		}
		return json(model, 200, [ok: true, version: plugin?.version])
	}

	// ---- one VM -------------------------------------------------------------------------------------

	/**
	 * GET serverId[, fresh=1]: the VM fragment. The tenant check runs first (one cheap server read, remembered a
	 * minute); then the cached result, or, for the plugin script's own request, a scan with the VM budget.
	 */
	HTMLResponse vm(ViewModel<Map> model) {
		if (!MigrationHygienePlugin.canView(model?.user)) return message(403, NEED_VIEW)
		Long id = idParam(model, 'serverId')
		if (id == null) return message(400, 'Missing or invalid serverId.')
		Map owner = vmOwner(model, id)
		if (owner.status) return message(owner.status as int, owner.error as String)
		boolean mayStart = scriptRequest(model)
		ScanService svc = scans()
		ScanService.VmEntry e = (param(model, 'fresh') == '1' && mayStart) ? null : svc.cachedVm(id)
		if (e == null) {
			if (!mayStart) return message(200, OPEN_TAB_VM)
			e = svc.scanVmNow(id, owner.server as ComputeServer)
		}
		return vmFragment(model, e)
	}

	/**
	 * [accountId, server] when the user may see this server (server: the record when it was just read, else null),
	 * or [status, error]. Reads the server once, on the io pool with the lookup budget, and remembers its tenant for
	 * a minute. A missing server and another tenant's server answer the same 404.
	 */
	Map vmOwner(ViewModel<Map> model, Long id) {
		ComputeServer loaded = null
		Map o = vmOwners.get(id)
		if (o == null) {
			ScanService svc = scans()
			try {
				loaded = (Workers.call(svc.workers.io, svc.lookupBudgetMs, 'server record') { morpheus.services.computeServer.get(id) }) as ComputeServer
			} catch (Workers.BudgetExceeded ignored) {
				return [status: 503, error: 'Unknown: time budget. Morpheus did not return the VM in time; try again.']
			} catch (Throwable t) {
				log.debug("migration-hygiene: couldn't read server ${id}: ${t}")
				return [status: 503, error: 'Unknown: the server could not be read.']
			}
			o = loaded == null ? [found: false] : [found: true, accountId: ServerFacts.safe { loaded.account?.id }]
			vmOwners.put(id, o)
		}
		if (o.found != true || !mayViewAccount(model, o.accountId as Long)) return [status: 404, error: 'No such VM.']
		return [accountId: o.accountId, server: loaded]
	}

	/**
	 * POST serverId: runs the guest agent probe on this VM (20-second timeout), then returns the VM fragment.
	 * One probe per VM at a time, at most one a minute (sooner: the last result, with when it ran), on the plugin's
	 * small probe pool; each request is logged with the Morpheus user id.
	 */
	HTMLResponse vmProbe(ViewModel<Map> model) {
		Map bad = requestCheck(model)
		if (bad) return message(403, bad.error as String)
		if (!MigrationHygienePlugin.canView(model?.user)) return message(403, NEED_VIEW)
		if (!MigrationHygienePlugin.canGuestScan(model?.user)) return message(403, NEED_GUEST)
		Long id = idParam(model, 'serverId')
		if (id == null) return message(400, 'Missing or invalid serverId.')
		ScanService svc = scans()
		ComputeServer server
		try {
			server = (Workers.call(svc.workers.io, svc.lookupBudgetMs, 'server record') { morpheus.services.computeServer.get(id) }) as ComputeServer
		} catch (Workers.BudgetExceeded ignored) {
			return message(503, 'Unknown: time budget. Morpheus did not return the VM in time; try again.')
		} catch (Throwable t) {
			server = null
		}
		if (server == null || !mayViewAccount(model, ServerFacts.safe { server.account?.id } as Long)) return message(404, 'No such VM.')
		if (!HvmScope.isHvmVm(server)) return message(200, 'Not an HVM VM: the guest agent probe runs only on HVM VMs.')
		Map r = svc.probe.probeNow(server, safe { model.user.id }, svc.workers.probe)
		String note = null
		if (r?.recent == true && r.at != null) {
			long s = Math.max(0L, (long) ((svc.clock.call() - (r.at as long)) / 1000L))
			long wait = Math.max(1L, (long) ((GuestAgentProbe.MIN_INTERVAL_MS / 1000L) - s))
			note = "Checked ${s} s ago; you can check again in ${wait} s.".toString()
		} else if (r?.busy == true) {
			note = "Not checked: ${GuestAgentProbe.BUSY}.".toString()
		}
		ScanService.VmEntry e = svc.cachedVm(id) ?: svc.scanVmNow(id, server)
		return vmFragment(model, e, note)
	}

	private HTMLResponse vmFragment(ViewModel<Map> model, ScanService.VmEntry e, String probeNote = null) {
		if (e == null || (e.error == null && e.found && !mayViewAccount(model, e.accountId))) return message(404, 'No such VM.')
		if (!e.found) return message(404, 'No such VM.')
		ScanService svc = scans()
		boolean canProbe = MigrationHygienePlugin.canGuestScan(model?.user)
		VmReport r = svc.report(e, canProbe)
		return html(200, Render.render(renderer(), Render.VM, [vms: [Views.vmBlock(e, r, canProbe, svc.clock.call(), svc.probeState(e, canProbe), probeNote)]]).html)
	}

	// ---- clusters -----------------------------------------------------------------------------------

	/**
	 * GET clusterId: for the plugin script's own request, starts (or joins) the background scan and returns its
	 * progress and results so far; for any other GET, the cached scan only, or a note to open the tab.
	 */
	HTMLResponse cluster(ViewModel<Map> model) {
		return clusterResponse(model, false, scriptRequest(model))
	}

	/** POST clusterId: forces a new scan (unless one is running or started in the last 30 seconds). */
	HTMLResponse clusterRescan(ViewModel<Map> model) {
		Map bad = requestCheck(model)
		if (bad) return message(403, bad.error as String)
		return clusterResponse(model, true, true)
	}

	private HTMLResponse clusterResponse(ViewModel<Map> model, boolean force, boolean mayStart) {
		if (!MigrationHygienePlugin.canView(model?.user)) return message(403, NEED_VIEW)
		Long id = idParam(model, 'clusterId')
		if (id == null) return message(400, 'Missing or invalid clusterId.')
		Map info = clusterInfo(model, id)
		if (info.status) return message(info.status as int, info.error as String)
		ScanService svc = scans()
		boolean permitted = MigrationHygienePlugin.canGuestScan(model?.user)
		ScanService.ClusterJob job = mayStart ? startOrJoin(svc, model, id, info.name as String, permitted, force) : svc.cachedCluster(id)
		if (job == null) return message(200, OPEN_TAB_CLUSTER)
		return html(200, Render.render(renderer(), Render.CLUSTER, clusterModel(svc, job, permitted, model)).html)
	}

	private static ScanService.ClusterJob startOrJoin(ScanService svc, ViewModel<Map> model, Long id, String name, boolean permitted, boolean force) {
		return svc.startOrJoin(id, name, permitted, force, ScanService.Starter.of(model?.user))
	}

	/**
	 * The job's entries this viewer may see: all of them for the master tenant, else only the VMs of the viewer's
	 * own tenant (a cluster's hosts may carry other tenants' VMs). Entries without a known tenant are left out.
	 */
	static List<ScanService.VmEntry> visibleEntries(ScanService svc, ScanService.ClusterJob job, ViewModel<Map> model) {
		return svc.entries(job).findAll { ScanService.VmEntry e -> mayViewAccount(model, e.accountId) }
	}

	Map clusterModel(ScanService svc, ScanService.ClusterJob job, boolean permitted, ViewModel<Map> model) {
		long now = svc.clock.call()
		List<VmReport> reports = []
		List<Map> notScanned = []
		List<ScanService.VmEntry> entries = visibleEntries(svc, job, model)
		entries.each { ScanService.VmEntry e ->
			VmReport r = svc.report(e, permitted)
			if (r != null) reports << r
			else if (e.hvm || e.error) notScanned << [name: e.name ?: "VM ${e.serverId}".toString(), serverId: e.serverId, status: Views.problem(e) ?: 'unknown']
		}
		int partial = entries.count { it.facts != null && it.skipped } as int
		String progress
		if (job.state == ScanService.ClusterJob.LISTING) progress = 'Listing the cluster\'s VMs…'
		else if (job.running) progress = "Scanned ${job.done.get()} of ${job.total} HVM VMs…".toString()
		else if (job.state == ScanService.ClusterJob.FAILED) progress = "The scan stopped: ${job.error ?: 'unknown error'}.".toString()
		else progress = "Scanned ${job.done.get()} of ${job.total} HVM VMs ${Views.ago(job.finishedAt ?: now, now)}.".toString()
		return [clusterId: job.clusterId, clusterName: job.clusterName ?: '', running: job.running, progress: progress,
		        noHvm: !job.running && job.state == ScanService.ClusterJob.COMPLETE && job.hvmHosts == 0 && (job.total ?: 0) == 0,
		        summary: Views.summary(reports, 0), hasResults: !reports.isEmpty(),
		        groups: Views.originGroups(reports), vmRows: Views.vmRows(reports), originNote: Views.ORIGIN_NOTE,
		        notScanned: notScanned, hasNotScanned: !notScanned.isEmpty(), notScannedCount: notScanned.size(), partialCount: partial,
		        probed: job.probe, csvClusterUrl: "${Shell.API}/csv?clusterId=${job.clusterId}".toString(), csvAllUrl: "${Shell.API}/csv?all=true".toString(),
		        csvClusterName: Views.csvFilename(job.clusterName ?: "cluster-${job.clusterId}", new Date(now)),
		        csvAllName: Views.csvFilename('all-clusters', new Date(now))]
	}

	// ---- JSON for the browser-built CSV (0.3.0) ----------------------------------------------------------

	/**
	 * GET clusterId: the cluster's scan status and its CSV rows so far, from the cache. For the plugin script's own
	 * request it starts or joins the background scan like the cluster route; never waits for it. Any other GET
	 * gets the cached scan only, or status "not-scanned". The browser polls this until the status is "done" (or
	 * "failed"), then builds the CSV itself, so the download doesn't depend on route response headers.
	 */
	JsonResponse clusterData(ViewModel<Map> model) {
		if (!MigrationHygienePlugin.canView(model?.user)) return json(model, 403, [ok: false, error: NEED_VIEW])
		Long id = idParam(model, 'clusterId')
		if (id == null) return json(model, 400, [ok: false, error: 'Missing or invalid clusterId.'])
		Map info = clusterInfo(model, id)
		if (info.status) return json(model, info.status as int, [ok: false, error: info.error])
		ScanService svc = scans()
		boolean permitted = MigrationHygienePlugin.canGuestScan(model?.user)
		ScanService.ClusterJob job = scriptRequest(model) ? startOrJoin(svc, model, id, info.name as String, permitted, false) : svc.cachedCluster(id)
		if (job == null) {
			return json(model, 200, [ok: true, clusterId: id, clusterName: info.name ?: '', status: 'not-scanned', error: OPEN_TAB_CLUSTER,
			                         scanned: 0, total: 0, startedAt: null, finishedAt: null, columns: Views.CSV_COLUMNS, rows: []])
		}
		return json(model, 200, clusterDataMap(svc, job, permitted, model))
	}

	static String isoUtc(long ms) {
		java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
		f.setTimeZone(TimeZone.getTimeZone('UTC'))
		return f.format(new Date(ms))
	}

	static String dataStatus(ScanService.ClusterJob job) {
		if (job.running) return 'scanning'
		return job.state == ScanService.ClusterJob.FAILED ? 'failed' : 'done'
	}

	Map clusterDataMap(ScanService svc, ScanService.ClusterJob job, boolean permitted, ViewModel<Map> model) {
		List<Map> rows = []
		visibleEntries(svc, job, model).each { ScanService.VmEntry e ->
			if (e.hvm || e.error) rows << Views.csvRow(e, svc.report(e, permitted), job.clusterName)
		}
		return [ok: true, clusterId: job.clusterId, clusterName: job.clusterName ?: '', status: dataStatus(job), error: job.error,
		        scanned: job.done.get(), total: job.total ?: 0, startedAt: isoUtc(job.startedAt),
		        finishedAt: job.running || job.finishedAt == null ? null : isoUtc(job.finishedAt),
		        columns: Views.CSV_COLUMNS, rows: Views.sortRows(rows).collect { Views.dataRow(it) }]
	}

	/** GET: the clusters the user's tenant may see that may be HVM (ids and names, at most 50). One guarded list call. */
	JsonResponse clusters(ViewModel<Map> model) {
		if (!MigrationHygienePlugin.canView(model?.user)) return json(model, 403, [ok: false, error: NEED_VIEW])
		Map listed = visibleClusters(model)
		if (listed.status) return json(model, listed.status as int, [ok: false, error: listed.error])
		return json(model, 200, [ok: true, clusters: (listed.clusters as List<Map>).collect { [id: it.id, name: it.name ?: "cluster-${it.id}".toString()] }])
	}

	// ---- CSV ----------------------------------------------------------------------------------------

	/**
	 * GET clusterId or all=true: the per-VM rows from the cache. Never starts a scan (0.4.0): a cluster that is
	 * still scanning or not scanned yet is a 202 or 409 note for one cluster, and one row with its status in the
	 * all-clusters file. The CSV body is served only when the response really carries text/csv, attachment and
	 * nosniff; when those headers can't be set, a 406 note instead (MH-01).
	 */
	HTMLResponse csv(ViewModel<Map> model) {
		if (!MigrationHygienePlugin.canView(model?.user)) return text(model, 403, NEED_VIEW)
		ScanService svc = scans()
		boolean permitted = MigrationHygienePlugin.canGuestScan(model?.user)
		boolean all = param(model, 'all') == 'true'
		List<Map> targets = []
		String label
		if (all) {
			Map listed = visibleClusters(model)
			if (listed.status) return text(model, listed.status as int, listed.error as String)
			targets = listed.clusters as List<Map>
			label = 'all-clusters'
		} else {
			Long id = idParam(model, 'clusterId')
			if (id == null) return text(model, 400, 'Missing or invalid clusterId.')
			Map info = clusterInfo(model, id)
			if (info.status) return text(model, info.status as int, info.error as String)
			targets = [[id: id, name: info.name]]
			label = (info.name ?: "cluster-${id}") as String
		}
		List<Map> rows = []
		List<Map> waiting = []
		int pending = 0
		int missing = 0
		targets.each { Map c ->
			ScanService.ClusterJob job = svc.cachedCluster(c.id as Long)
			String name = (c.name ?: "cluster-${c.id}") as String
			if (job == null || job.running) {
				if (job == null) missing++ else pending++
				waiting << [name: '', cluster: name, origin: '', scanStatus: job == null ? NOT_SCANNED : STILL_RUNNING]
				return
			}
			visibleEntries(svc, job, model).each { ScanService.VmEntry e ->
				if (e.hvm || e.error) rows << Views.csvRow(e, svc.report(e, permitted), job.clusterName)
			}
		}
		if (!all && pending > 0) return text(model, 202, 'The scan of this cluster is still running. Try the download again in a minute.')
		if (!all && missing > 0) return text(model, 409, OPEN_TAB_CLUSTER)
		if (all && targets && waiting.size() == targets.size()) {
			return text(model, 409, "None of the ${targets.size()} cluster${targets.size() == 1 ? ' is' : 's are'} scanned yet (${pending} still running). Open each cluster's Migration Hygiene tab, or use the Download CSV (all clusters) button.".toString())
		}
		String body = Views.csv(Views.sortRows(rows) + waiting)
		String filename = Views.csvFilename(label, new Date(svc.clock.call()))
		if (!csvHeaders(model, filename)) return text(model, 406, NO_DIRECT_CSV)
		return html(200, body)
	}

	/**
	 * Sets the CSV headers; true only when every one was accepted and the response reports text/csv (when it
	 * reports a type at all). False with no response object, or when a header is refused.
	 */
	static boolean csvHeaders(ViewModel<Map> model, String filename) {
		def response = safe { model?.response }
		if (response == null) return false
		try {
			response.setHeader('Content-Type', 'text/csv; charset=utf-8')
			response.setHeader('Content-Disposition', "attachment; filename=\"${filename}\"".toString())
			response.setHeader('X-Content-Type-Options', 'nosniff')
			response.setHeader('Cache-Control', 'no-store')
		} catch (Throwable t) {
			return false
		}
		String type = safe { response.getContentType() } as String
		return !type || type.toLowerCase().startsWith('text/csv')
	}

	/** Every cluster the user's tenant may see that may be HVM (at most 50). One guarded list call. */
	Map visibleClusters(ViewModel<Map> model) {
		ScanService svc = scans()
		List<ComputeServerGroup> all
		try {
			all = ((Workers.call(svc.workers.io, 10_000L, 'clusters') { morpheus.services.cluster.list(new DataQuery()) }) as List<ComputeServerGroup>) ?: []
		} catch (Workers.BudgetExceeded ignored) {
			return [status: 503, error: 'Unknown: time budget. Morpheus did not list the clusters in time; try again.']
		} catch (Throwable t) {
			log.warn("migration-hygiene: cluster list failed: ${t}")
			return [status: 503, error: 'The clusters could not be listed.']
		}
		List<Map> out = all.findAll { ComputeServerGroup g -> g?.id != null && HvmScope.maybeHvmCluster(g) && mayViewAccount(model, ServerFacts.safe { g.owner?.id } as Long) }
			.take(MAX_CLUSTERS).collect { ComputeServerGroup g -> [id: g.id, name: ServerFacts.safe { g.name }] }
		return [clusters: out]
	}

	/** [name, ownerId] for a cluster the user may see, or [status, error]. Cached 10 minutes; guarded read. */
	Map clusterInfo(ViewModel<Map> model, Long id) {
		Map info = clusterInfo.get(id)
		if (info == null) {
			ScanService svc = scans()
			ComputeServerGroup g
			try {
				g = (Workers.call(svc.workers.io, svc.lookupBudgetMs, 'cluster') { morpheus.services.cluster.get(id) }) as ComputeServerGroup
			} catch (Workers.BudgetExceeded ignored) {
				return [status: 503, error: 'Unknown: time budget. Morpheus did not return the cluster in time; try again.']
			} catch (Throwable t) {
				g = null
			}
			if (g == null) return [status: 404, error: 'No such cluster.']
			info = [name: ServerFacts.safe { g.name }, ownerId: ServerFacts.safe { g.owner?.id }]
			clusterInfo.put(id, info)
		}
		if (!mayViewAccount(model, info.ownerId as Long)) return [status: 404, error: 'No such cluster.']
		return info
	}

	// ---- request checks (from com-morpheus-plugin, MIT, same author) ----------------------------------

	/**
	 * POST only, the X-Requested-With header that a cross-site form or image can't send, a Sec-Fetch-Site (when
	 * the browser sends one) of same-origin, and, when the browser says where the request came from, the same
	 * origin as the appliance. Null when the request passes.
	 */
	static Map requestCheck(ViewModel<Map> model) {
		def req = safe { model?.request }
		if (req == null) return [ok: false, error: 'Request refused.']
		if ((safe { req.method } as String) != 'POST') return [ok: false, error: 'POST only.']
		if ((safe { req.getHeader('X-Requested-With') } as String) != 'XMLHttpRequest') return [ok: false, error: 'Request refused: missing X-Requested-With header.']
		String site = safe { req.getHeader('Sec-Fetch-Site') } as String
		if (site && site != 'same-origin') return [ok: false, error: 'Request refused: cross-origin.']
		if (!sameOrigin(req)) return [ok: false, error: 'Request refused: cross-origin.']
		return null
	}

	/**
	 * A GET that may start work (0.4.0): the plugin script's own fetch. It carries X-Requested-With (a link, an
	 * image or a cross-site page can't send it without a CORS preflight), its Sec-Fetch-Site, when sent, is
	 * same-origin (cross-site and same-site are refused), and it proves where it came from: a same-origin Origin
	 * or Referer, or Sec-Fetch-Site same-origin. Anything else is served from the cache only.
	 */
	static boolean scriptRequest(ViewModel<Map> model) {
		def req = safe { model?.request }
		if (req == null) return false
		if ((safe { req.getHeader('X-Requested-With') } as String) != 'XMLHttpRequest') return false
		String site = safe { req.getHeader('Sec-Fetch-Site') } as String
		if (site && site != 'same-origin') return false
		String from = originOf(req)
		if (from) return sameOrigin(req)
		return site == 'same-origin'
	}

	/** The Origin header, or failing that the Referer; null when neither is sent. */
	static String originOf(req) {
		return (safe { req.getHeader('Origin') } ?: safe { req.getHeader('Referer') }) as String
	}

	/**
	 * Origin (or, failing that, Referer) must name this appliance: its host name must equal the Host header or
	 * the server name (ports ignored). X-Forwarded-Host is not trusted (0.4.0): a client can set it, and a proxy
	 * that rewrites Host also sets the server name. No header at all is allowed here (X-Requested-With already
	 * rules out cross-site forms; {@link #scriptRequest} asks for more); the literal "null" origin is refused.
	 */
	static boolean sameOrigin(req) {
		String from = originOf(req)
		if (!from) return true
		if (from == 'null') return false
		String fromHost
		try { fromHost = new URI(from).host?.toLowerCase() } catch (Exception e) { return false }
		if (!fromHost) return false
		Set<String> ours = [safe { req.getHeader('Host') }, safe { req.serverName }]
			.findAll().collect { hostOnly(it as String) }.findAll() as Set
		return ours.contains(fromHost)
	}

	static String hostOnly(String h) {
		String v = h?.split(',')?.getAt(0)?.trim()?.toLowerCase()
		if (!v) return null
		if (v.startsWith('[')) return v.substring(0, v.indexOf(']') + 1)
		return v.replaceFirst(/:\d+$/, '')
	}

	/** The user's tenant owns it, or the user is in the master tenant. */
	static boolean mayViewAccount(ViewModel<Map> model, Long ownerId) {
		def account = safe { model?.user?.account }
		if (safe { account?.masterAccount } == true) return true
		Long mine = safe { account?.id } as Long
		return mine != null && ownerId != null && mine == ownerId
	}

	// ---- helpers ------------------------------------------------------------------------------------

	Renderer<?> renderer() {
		Renderer<?> r = null
		try { r = plugin?.getRenderer() } catch (Throwable ignored) {} // null plugin manager outside Morpheus (tests)
		return r ?: new HandlebarsRenderer('renderer', plugin?.classLoader ?: getClass().classLoader)
	}

	static Long idParam(ViewModel<Map> model, String name) {
		String v = param(model, name)
		return (v ==~ /\d{1,18}/) ? (v as Long) : null
	}

	static String param(ViewModel<Map> model, String name) {
		String v = safe { model?.request?.getParameter(name) } as String
		return v?.trim()
	}

	/** True when the response took the header. */
	static boolean setHeader(ViewModel<Map> model, String name, String value) {
		try {
			def response = model?.response
			if (response == null) return false
			response.setHeader(name, value)
			return true
		} catch (Throwable ignored) {
			return false
		}
	}

	static HTMLResponse html(int status, String body) {
		HTMLResponse r = HTMLResponse.success(body)
		r.status = status
		return r
	}

	/** A short message fragment (escaped). */
	static HTMLResponse message(int status, String text) {
		return html(status, '<div class="migration-hygiene"><div class="mh-note" style="padding:8px 10px;border-left:3px solid #8a8f98;margin:8px 0">' +
			Views.esc(text) + '</div></div>')
	}

	/**
	 * Plain text (CSV route errors and notes). When the text/plain and nosniff headers can't be set, the text is
	 * HTML-escaped, so a cluster name in it can never become markup.
	 */
	static HTMLResponse text(ViewModel<Map> model, int status, String text) {
		boolean typed = setHeader(model, 'Content-Type', 'text/plain; charset=utf-8')
		typed = setHeader(model, 'X-Content-Type-Options', 'nosniff') && typed
		return html(status, typed ? text : Views.esc(text))
	}

	/** JSON as application/json; charset=utf-8 (0.4.0: Morpheus served it as ISO-8859-1), with nosniff. */
	static JsonResponse json(ViewModel<Map> model, int status, Map data) {
		JsonResponse r = json(status, data)
		setHeader(model, 'Content-Type', JSON_TYPE)
		setHeader(model, 'X-Content-Type-Options', 'nosniff')
		try { model?.response?.setCharacterEncoding('UTF-8') } catch (Throwable ignored) {}
		return r
	}

	static JsonResponse json(int status, Map data) {
		JsonResponse r = JsonResponse.of(data)
		r.status = status
		try { r.contentType = JSON_TYPE } catch (Throwable ignored) {} // a field since some Plugin API; set it where it exists
		return r
	}

	static Object safe(Closure<Object> c) {
		try { return c() } catch (Throwable ignored) { return null }
	}
}
