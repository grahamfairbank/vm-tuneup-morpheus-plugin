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
		'.vm-tuneup-tab .vtu-loading{padding:8px 0;font-size:13px}' +
		// Activity indicator (0.6): a spinning ring before any status that is still working, and a progress bar.
		'.vm-tuneup-tab .vtu-working::before{content:"";display:inline-block;width:12px;height:12px;margin-right:7px;' +
		'vertical-align:-2px;border:2px solid rgba(1,169,130,.25);border-top-color:#01a982;border-radius:50%;' +
		'animation:vtu-rot .8s linear infinite}' +
		'.vm-tuneup-tab .vtu-busy{display:inline-block;padding:3px 10px;border-radius:3px;font-weight:600;' +
		'background:rgba(1,169,130,.12);border-left:3px solid #01a982;animation:vtu-glow 1.6s ease-in-out infinite}' +
		'.vm-tuneup-tab .vtu-progress{height:4px;max-width:420px;margin:2px 0 8px;border-radius:2px;overflow:hidden;' +
		'background:rgba(128,128,128,.2)}' +
		'.vm-tuneup-tab .vtu-progress>i{display:block;height:100%;background:#01a982;transition:width .5s}' +
		'.vm-tuneup-tab .vtu-progress.vtu-indeterminate>i{width:30%;animation:vtu-slide 1.2s ease-in-out infinite}' +
		'@keyframes vtu-rot{to{transform:rotate(360deg)}}' +
		'@keyframes vtu-glow{50%{background:rgba(1,169,130,.28)}}' +
		'@keyframes vtu-slide{0%{margin-left:-30%}100%{margin-left:100%}}' +
		'@media (prefers-reduced-motion:reduce){.vm-tuneup-tab .vtu-working::before{animation-duration:3s}' +
		'.vm-tuneup-tab .vtu-busy,.vm-tuneup-tab .vtu-progress>i{animation:none}}' +
		'.vm-tuneup-tab .vtu-fallback{visibility:hidden;animation:vtu-reveal 0s linear 15s forwards;opacity:.75;font-size:12px}' +
		'@keyframes vtu-reveal{to{visibility:visible}}' +
		'</style>'

	static String vmUrl(Long id) { "${API}/vm?serverId=${id}".toString() }
	static String clusterUrl(Long id) { "${API}/cluster?clusterId=${id}".toString() }

	static String loading(String what, String url) {
		return '<div class="vtu-loading vtu-working" role="status">Loading VM Tune-Up results for this ' + Views.esc(what) + '\u2026</div>' +
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
