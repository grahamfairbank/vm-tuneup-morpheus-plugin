package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Snapshot
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.VmFacts

/**
 * The Morpheus adapter: turns a plugin-API ComputeServer into the REST-shaped map the core reads
 * ({@link VmFacts#fromMap}). Only Plugin API 1.4.1 fields are used. Every read is guarded, because lazily loaded
 * associations can throw inside Morpheus; a field that can't be read is left out, and the checks report unknown.
 */
@Slf4j
class ServerFacts {

	final MorpheusContext morpheus
	/** Host id to cluster name, shared across one scan. */
	final Map<Long, String> clusterByHost = [:]
	final Map<Long, String> hostNames = [:]
	/** Datastore id to [name, typeCode, local], shared across one scan (0.6). */
	final Map<Long, Map> datastores = [:]
	/**
	 * How each service read runs: {@code reader(part, closure)}. Default: directly. Request paths and background
	 * scans pass {@link Workers#budgetedReader}, so a slow read is cut off and reported as "time budget".
	 */
	final Closure<Object> reader

	ServerFacts(MorpheusContext morpheus, Closure<Object> reader = null) {
		this.morpheus = morpheus
		this.reader = reader ?: { String part, Closure c -> c.call() }
	}

	private Object fetch(String part, Closure c) { reader.call(part, c) }

	/** Loads the server by id (9.0 tab show() gets a shallow one), then builds its facts. */
	VmFacts load(ComputeServer server) {
		ComputeServer full = HvmScope.load(morpheus, server)
		return VmFacts.fromMap(toMap(full))
	}

	Map toMap(ComputeServer s) {
		Map m = [:]
		if (s == null) return m
		m.id = safe { s.id }
		m.name = safe { s.name } ?: safe { s.displayName }
		m.computeServerType = [code: safe { s.computeServerType?.code }, managed: safe { s.computeServerType?.managed }]
		m.powerState = safe { s.powerState?.toString() }
		m.status = safe { s.status }
		def os = safe { s.serverOs }
		if (os != null) {
			m.serverOs = [code: safe { os.code }, name: safe { os.name }, osFamily: safe { os.osFamily }, platform: safe { os.platform?.toString() }]
		}
		m.osType = safe { s.osType }
		m.platform = safe { s.platform }
		m.guestAgentStatus = safe { s.guestAgentStatus?.toString() }
		m.agentInstalled = safe { s.agentInstalled }
		m.agentVersion = safe { s.agentVersion }
		m.lastAgentUpdate = safe { s.lastAgentUpdate }
		m.uefi = safe { s.uefi }
		m.secureBoot = safe { s.secureBoot }
		m.tpm = safe { s.tpm }
		m.maxCores = safe { s.maxCores }
		m.coresPerSocket = safe { s.coresPerSocket }
		def devices = safe { s.devices }
		if (devices instanceof List) m.devices = ((List) devices).collect { [id: safe { it.id }] }
		m.instance = [id: instanceId(s)]
		m.config = safe { s.getConfigMap() } ?: [:]
		m.controllers = (safe { s.controllers } ?: []).collect { c ->
			[id: safe { c.id }, name: safe { c.name }, type: [code: safe { c.type?.code }, name: safe { c.type?.name }]]
		}
		m.volumes = (safe { s.volumes } ?: []).collect { v ->
			[id: safe { v.id }, name: safe { v.name }, controller: [id: safe { v.controller?.id }, name: safe { v.controller?.name }],
			 rootVolume: safe { v.rootVolume }, diskMode: safe { v.diskMode }, type: [code: safe { v.type?.code }],
			 maxStorage: safe { v.maxStorage }, usedStorage: safe { v.usedStorage }, sourceImage: safe { v.sourceImage },
			 imageType: safe { v.imageType }, volumePath: safe { v.volumePath }, datastore: datastore(safe { v.datastore?.id } as Long, safe { v.datastore?.name } as String)]
		}
		m.interfaces = (safe { s.interfaces } ?: []).collect { i ->
			[name: safe { i.name }, type: safe { i.type } != null ? [code: safe { i.type?.code }, name: safe { i.type?.name }] : null,
			 macAddress: safe { i.macAddress }, primaryInterface: safe { i.primaryInterface }]
		}
		List snaps = snapshots(s)
		if (snaps != null) m.snapshots = snaps
		Long hostId = safe { s.parentServer?.id } as Long
		if (hostId != null) {
			m.parentServer = [id: hostId, name: hostName(s, hostId)]
			m.clusterName = clusterName(hostId)
		}
		m.zone = [name: safe { s.cloud?.name }]
		return m
	}

	/**
	 * Snapshots of the server and its volumes, as [name, snapshotCreated]; null when they couldn't be read.
	 * Tries a query by server first, keeping only rows that really belong to this server (in case the filter is
	 * ignored), then the snapshot ids carried on the server and its volumes.
	 */
	List<Map> snapshots(ComputeServer s) {
		Long id = safe { s.id } as Long
		if (id == null) return null
		Set<Long> volumeIds = ((safe { s.volumes } ?: []).collect { safe { it.id } }.findAll { it != null } as Set<Long>)
		Map<Long, Snapshot> found = [:]
		boolean read = false
		try {
			List<Snapshot> listed = ((fetch('snapshots') { morpheus.services.snapshot.list(new DataQuery().withFilter('server.id', id)) }) as List<Snapshot>) ?: []
			List<Snapshot> mine = listed.findAll { Snapshot sn -> (safe { sn.server?.id }) == id || (safe { sn.volume?.id }) in volumeIds }
			// Rows that name no server or volume at all can't be attributed: don't trust the query then.
			boolean attributable = listed.isEmpty() || listed.any { Snapshot sn -> safe { sn.server?.id } != null || safe { sn.volume?.id } != null }
			if (attributable) {
				mine.each { Snapshot sn -> found[(Long) sn.id] = sn }
				read = true
			}
		} catch (Throwable e) {
			log.debug("vm-tuneup: snapshot query for server ${id} failed: ${e}")
		}
		try {
			Set<Long> ids = [] as Set<Long>
			(safe { s.snapshots } ?: []).each { p -> Long x = safe { p.id } as Long; if (x != null) ids << x }
			(safe { s.volumes } ?: []).each { v -> (safe { v.snapshots } ?: []).each { p -> Long x = safe { p.id } as Long; if (x != null) ids << x } }
			ids.removeAll(found.keySet())
			if (ids) {
				(((fetch('snapshots') { morpheus.services.snapshot.listById(ids) }) as List<Snapshot>) ?: []).each { Snapshot sn -> found[(Long) sn.id] = sn }
			}
			if (!read && (safe { s.snapshots }) != null && ids) read = true
		} catch (Throwable e) {
			log.debug("vm-tuneup: snapshot ids for server ${id} failed: ${e}")
		}
		if (!read) return null
		return found.values().collect { Snapshot sn -> [name: safe { sn.name }, snapshotCreated: safe { sn.snapshotCreated }] }
	}

	/** The datastore's name, type and whether it's host-local; null when the volume has none. */
	Map datastore(Long id, String name) {
		if (id == null) return null
		if (!datastores.containsKey(id)) {
			Map d = [id: id, name: name]
			try {
				def full = fetch('datastore') { morpheus.services.cloud.datastore.get(id) }
				if (full != null) {
					d.name = safe { full.name } ?: name
					def t = safe { full.datastoreType }
					d.datastoreType = [code: safe { t?.code }, localStorage: safe { t?.localStorage }]
					if (d.datastoreType.code == null) d.typeCode = safe { full.type }
				}
			} catch (Throwable e) {
				log.debug("vm-tuneup: datastore ${id} failed: ${e}")
			}
			datastores[id] = d
		}
		return datastores[id]
	}

	/** The instance of a managed VM, through its workload; null for discovered VMs or when it can't be read. */
	Long instanceId(ComputeServer s) {
		Long id = safe { s.id } as Long
		if (id == null) return null
		try {
			def w = fetch('instance') { morpheus.services.workload.find(new DataQuery().withFilter('server.id', id)) }
			return safe { w?.instance?.id } as Long
		} catch (Throwable e) {
			log.debug("vm-tuneup: instance of server ${id} failed: ${e}")
			return null
		}
	}

	String hostName(ComputeServer s, Long hostId) {
		String n = safe { s.parentServer?.name }
		if (n) return n
		if (!hostNames.containsKey(hostId)) {
			hostNames[hostId] = safe { ((fetch('host name') { morpheus.services.computeServer.get(hostId) }) as ComputeServer)?.name }
		}
		return hostNames[hostId]
	}

	/** The cluster of the VM's host: the host's server group. */
	String clusterName(Long hostId) {
		if (clusterByHost.containsKey(hostId)) return clusterByHost[hostId]
		String name = null
		try {
			ComputeServer host = (fetch('cluster name') { morpheus.services.computeServer.get(hostId) }) as ComputeServer
			ComputeServerGroup g = host?.serverGroup
			name = safe { g?.name }
			if (!name && g?.id != null) name = safe { ((fetch('cluster name') { morpheus.services.cluster.get(g.id) }) as ComputeServerGroup)?.name }
		} catch (Throwable e) {
			log.debug("vm-tuneup: cluster for host ${hostId} failed: ${e}")
		}
		clusterByHost[hostId] = name
		return name
	}

	static Object safe(Closure c) {
		try { return c.call() } catch (Throwable ignored) { return null }
	}
}
