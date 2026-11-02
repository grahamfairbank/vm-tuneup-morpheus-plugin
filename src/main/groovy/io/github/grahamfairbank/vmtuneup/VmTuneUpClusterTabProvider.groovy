package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/**
 * "VM Tune-Up" tab on HVM cluster pages: the fleet view (0.2.0) for licenses without Reports. Summary,
 * worst VMs, findings per check, Rescan and CSV downloads, all from the cluster route; the scan runs in the
 * background (see {@link ScanService}).
 */
@Slf4j
class VmTuneUpClusterTabProvider extends AbstractClusterTabProvider {

	VmTuneUpPlugin plugin
	MorpheusContext morpheus

	VmTuneUpClusterTabProvider(VmTuneUpPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'vm-tuneup-cluster-tab' }
	@Override String getName() { VmTuneUpPlugin.NAME }

	/** Trivial (0.2.0): the cluster's type code as given, its owner and the user's permissions. No service call. */
	@Override
	Boolean show(ComputeServerGroup cluster, User user, Account account) {
		if (cluster?.id == null || !VmTuneUpPlugin.canView(user) || !mayView(cluster, account)) return false
		return HvmScope.maybeHvmCluster(cluster)
	}

	/** The fleet view's shell: filled from the cluster route, which runs the scan in the background. */
	@Override
	HTMLResponse renderTemplate(ComputeServerGroup cluster) {
		return Shell.cluster(getRenderer(), cluster?.id, ServerFacts.safe { cluster?.name } as String)
	}

	/** Same rule as com-morpheus-plugin: the user's tenant owns the cluster, or the user is in the master tenant. */
	static boolean mayView(ComputeServerGroup cluster, Account account) {
		if ((safe { account?.masterAccount }) == true) return true
		Long mine = safe { account?.id } as Long
		Long owner = safe { cluster?.owner?.id } as Long
		return mine != null && mine == owner
	}

	private static Object safe(Closure c) {
		try { return c.call() } catch (Throwable ignored) { return null }
	}
}
