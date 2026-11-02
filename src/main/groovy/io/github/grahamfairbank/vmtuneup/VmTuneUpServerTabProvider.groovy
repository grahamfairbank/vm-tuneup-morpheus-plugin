package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/**
 * "VM Tune-Up" tab on HVM VM pages (Infrastructure > Compute). Needed because discovered VMs have no
 * instance. Never relies on the account argument, which Morpheus 9.0 leaves null for server tabs. Never blocks the
 * page: the content comes from the VM route (see {@link VmTuneUpController}).
 */
@Slf4j
class VmTuneUpServerTabProvider extends AbstractServerTabProvider {

	VmTuneUpPlugin plugin
	MorpheusContext morpheus
	final ViewerGrants grants = new ViewerGrants()

	VmTuneUpServerTabProvider(VmTuneUpPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'vm-tuneup-server-tab' }
	@Override String getName() { VmTuneUpPlugin.NAME }

	/**
	 * Trivial (0.2.0): only the server as given and the user's permission map, no service call. A server without
	 * a type (Morpheus 9.0 passes a shallow one) gets the tab; its content then says whether it's an HVM VM.
	 */
	@Override
	Boolean show(ComputeServer server, User user, Account account) {
		if (server?.id == null || !VmTuneUpPlugin.canView(user)) return false
		grants.record('server', server.id, user)
		return HvmScope.maybeHvmVm(server)
	}

	/**
	 * Never scans (0.2.0): a fresh cached result for this VM is rendered inline, otherwise a loading shell that the
	 * plugin script fills from the VM route after the page has loaded. Reads only in-memory caches.
	 */
	@Override
	HTMLResponse renderTemplate(ComputeServer server) {
		return Shell.vms(plugin, getRenderer(), morpheus, [server?.id], grants.viewer('server', server?.id))
	}
}
