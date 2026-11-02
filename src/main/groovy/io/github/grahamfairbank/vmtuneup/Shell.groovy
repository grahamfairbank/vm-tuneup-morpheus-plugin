package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.Renderer
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.VmReport

/**
 * What a tab's renderTemplate() returns (0.2.0): a small shell with a "Loading..." state per VM or cluster, which the
 * plugin script (vm-tuneup.js, loaded on every page by {@link VmTuneUpUiLoaderProvider}) fills
 * from the plugin's own routes after the page has loaded. A VM with a fresh cached result is rendered inline
 * instead. Reads in-memory caches only: never a service call, never a scan.
 *
 * If the script doesn't run, a fallback line appears after 15 seconds (CSS only) with a link to the route.
 */
@Slf4j
class Shell {

	static final String API = '/plugin/vmTuneUp'

	static final String STYLE = '<style>' +
		'.vm-tuneup-tab .vtu-loading{padding:8px 0;opacity:.8;font-size:13px}' +
		'.vm-tuneup-tab .vtu-fallback{visibility:hidden;animation:vtu-reveal 0s linear 15s forwards;opacity:.75;font-size:12px}' +
		'@keyframes vtu-reveal{to{visibility:visible}}' +
		'</style>'

	static String vmUrl(Long id) { "${API}/vm?serverId=${id}".toString() }
	static String clusterUrl(Long id) { "${API}/cluster?clusterId=${id}".toString() }

	static String loading(String what, String url) {
		return '<div class="vtu-loading">Loading VM Tune-Up results for this ' + Views.esc(what) + '\u2026</div>' +
			'<div class="vtu-fallback">Still loading? Reload the page, or <a href="' + Views.esc(url) +
			'" target="_blank" rel="noopener noreferrer">open the results on their own</a>.</div>'
	}

	static String slot(String kind, Long id, boolean loaded, String inner) {
		return '<div class="vtu-slot" data-vtu-kind="' + kind + '" data-vtu-id="' + id + '"' + (loaded ? ' data-vtu-loaded="1"' : '') + '>' + inner + '</div>'
	}

	static HTMLResponse wrap(String body) {
		return HTMLResponse.success('<div class="vm-tuneup-tab">' + STYLE + body + '</div>')
	}

	/** Server and instance tabs: one slot per VM id. {@code viewer} is what show() recorded (null if nothing). */
	static HTMLResponse vms(VmTuneUpPlugin plugin, Renderer<?> renderer, MorpheusContext morpheus, List<Long> ids, Map viewer) {
		try {
			List<Long> valid = (ids ?: []).findAll { it != null } as List<Long>
			if (valid.isEmpty()) return wrap('<div class="vtu-loading">No VM to show.</div>')
			ScanService svc = plugin?.scans(morpheus)
			long now = svc ? svc.clock.call() : System.currentTimeMillis()
			StringBuilder sb = new StringBuilder()
			valid.each { Long id ->
				ScanService.VmEntry e = svc?.cachedVm(id)
				if (e != null && viewer != null && ViewerGrants.mayView(viewer, e.accountId)) {
					VmReport r = svc.report(e, viewer.guestScan as Boolean)
					String inner = Render.render(renderer, Render.VM, [vms: [Views.vmBlock(e, r, viewer.guestScan == true, now, svc.probeState(e, viewer.guestScan as Boolean))]]).html
					sb.append(slot('vm', id, true, inner))
				} else {
					sb.append(slot('vm', id, false, loading('VM', vmUrl(id))))
				}
			}
			return wrap(sb.toString())
		} catch (Throwable t) {
			log.warn("vm-tuneup: tab shell failed: ${t}")
			return wrap('<div class="vtu-loading">' + Views.esc(Render.SCAN_FAILED) + '</div>')
		}
	}

	/** Cluster tab: one slot, always filled from the cluster route. */
	static HTMLResponse cluster(Renderer<?> renderer, Long id, String name) {
		if (id == null) return wrap('<div class="vtu-loading">No cluster to show.</div>')
		return wrap(slot('cluster', id, false, loading('cluster', clusterUrl(id))))
	}
}
