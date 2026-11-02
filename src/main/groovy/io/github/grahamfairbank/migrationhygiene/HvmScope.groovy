package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import groovy.util.logging.Slf4j

/**
 * Decides which servers the plugin looks at: VMs on HVM clusters, managed (an instance's VM) or discovered.
 * It goes by the compute server type code, as com-morpheus-plugin does for HVM hosts.
 */
@Slf4j
class HvmScope {

	/** HVM hypervisor hosts. */
	static final Set<String> HOST_TYPES = Collections.unmodifiableSet(['mvmHost', 'kvmHost'] as Set<String>)
	/**
	 * HVM VMs. Managed: 'morpheusVm' and 'morpheusWindowsVm' (seen on 9.0.1 and 9.1 appliances; 'mvmVm' kept as a
	 * fallback). Discovered but unmanaged: 'kvmUnmanaged', which has no instance.
	 */
	static final Set<String> VM_TYPES = Collections.unmodifiableSet(['morpheusVm', 'morpheusWindowsVm', 'mvmVm', 'kvmUnmanaged'] as Set<String>)

	static boolean isHvmVmType(String code) { code in VM_TYPES }

	/** An HVM VM: one of the HVM VM types, or another unmanaged type running on an HVM host. */
	static boolean isHvmVm(ComputeServer server) {
		String code = typeCode(server)
		if (isHvmVmType(code)) return true
		if (code?.startsWith('unmanaged')) {
			try {
				return isHvmHostType(server?.parentServer?.computeServerTypeCode)
			} catch (Throwable ignored) {
				return false
			}
		}
		return false
	}

	/**
	 * For tab show() (0.2.0): decides from the object as given, with no service call. True for an HVM VM type,
	 * for no type at all (Morpheus 9.0 passes a shallow server; the tab then says "not an HVM VM" if so), and for
	 * an unmanaged type unless its host is known not to be HVM.
	 */
	static boolean maybeHvmVm(ComputeServer server) {
		if (server == null) return false
		String code = typeCode(server)
		if (code == null) return true
		if (isHvmVmType(code)) return true
		if (code.startsWith('unmanaged')) {
			String host = null
			try { host = server.parentServer?.computeServerTypeCode } catch (Throwable ignored) {}
			return host == null || isHvmHostType(host)
		}
		return false
	}

	/** Cluster types that are never HVM (container platforms). Anything else, or no type, may be HVM. */
	static final java.util.regex.Pattern NON_HVM_CLUSTER = ~/(?i)kube|docker|eks|aks|gke|openshift|swarm|rancher/

	/** For the cluster tab's show() and the all-clusters CSV: from the cluster's type code as given. */
	static boolean maybeHvmCluster(com.morpheusdata.model.ComputeServerGroup cluster) {
		if (cluster == null) return false
		String code = null
		try { code = cluster.type?.code } catch (Throwable ignored) {}
		return code == null || !NON_HVM_CLUSTER.matcher(code).find()
	}

	static boolean isHvmHostType(String code) {
		return code in HOST_TYPES
	}

	static String typeCode(ComputeServer server) {
		try {
			ComputeServerType t = server?.computeServerType
			return t?.code
		} catch (Throwable ignored) {
			return null
		}
	}

	/**
	 * The full server record. Morpheus 9.0 can pass tab providers a shallow server (no type), so the plugin
	 * always reads it by id. Falls back to what it was given when the read fails.
	 */
	static ComputeServer load(MorpheusContext morpheus, ComputeServer server) {
		if (server?.id == null) return server
		try {
			ComputeServer full = morpheus?.services?.computeServer?.get(server.id)
			return full ?: server
		} catch (Throwable e) {
			log.debug("migration-hygiene: couldn't load server ${server.id}: ${e}")
			return server
		}
	}

	static ComputeServer loadById(MorpheusContext morpheus, Long id) {
		if (id == null) return null
		try {
			return morpheus?.services?.computeServer?.get(id)
		} catch (Throwable e) {
			log.debug("migration-hygiene: couldn't load server ${id}: ${e}")
			return null
		}
	}
}
