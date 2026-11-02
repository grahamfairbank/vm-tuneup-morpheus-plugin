package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/** "Migration Hygiene" tab on instance pages (Provisioning > Instances) whose VMs run on HVM: one block per VM, loaded after the page. */
@Slf4j
class MigrationHygieneInstanceTabProvider extends AbstractInstanceTabProvider {

	MigrationHygienePlugin plugin
	MorpheusContext morpheus
	final ViewerGrants grants = new ViewerGrants()

	MigrationHygieneInstanceTabProvider(MigrationHygienePlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }
	@Override String getCode() { 'migration-hygiene-instance-tab' }
	@Override String getName() { MigrationHygienePlugin.NAME }

	/**
	 * Trivial (0.2.0): from the instance's VMs as given (their type codes, if loaded) and the user's permissions.
	 * Shown when one may be an HVM VM, or when that can't be told from the instance.
	 */
	@Override
	Boolean show(Instance instance, User user, Account account) {
		if (instance == null || !MigrationHygienePlugin.canView(user)) return false
		grants.record('instance', instance.id, user)
		List<ComputeServer> servers = givenServers(instance)
		if (servers.isEmpty()) return true
		return servers.any { HvmScope.maybeHvmVm(it) }
	}

	/** A shell per VM (at most 10), each filled from the VM route, or rendered inline from the cache. */
	@Override
	HTMLResponse renderTemplate(Instance instance) {
		return Shell.vms(plugin, getRenderer(), morpheus, serverIds(instance).take(10), grants.viewer('instance', instance?.id))
	}

	static List<ComputeServer> givenServers(Instance instance) {
		try {
			return (instance?.containers ?: []).collect { it?.server }.findAll { it != null } as List<ComputeServer>
		} catch (Throwable ignored) {
			return []
		}
	}

	/** Ids of the VMs behind this instance (one per node for multi-node layouts). */
	static List<Long> serverIds(Instance instance) {
		return givenServers(instance).collect { ComputeServer s -> ServerFacts.safe { s.id } as Long }.findAll { it != null }.unique() as List<Long>
	}
}
