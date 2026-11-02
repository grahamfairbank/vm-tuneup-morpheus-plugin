package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import java.text.SimpleDateFormat

/**
 * What the Tier 1 checks know about one VM, as plain values. Built from a Map shaped like the Morpheus REST
 * server record (GET /api/servers/{id}, under "server"), which is also what the Morpheus adapter produces from
 * a plugin-API ComputeServer. No Morpheus types here, so every check can be tested from JSON fixtures.
 *
 * Extra keys the REST record doesn't have, written by the adapter (and by fixtures):
 * <ul>
 *   <li>{@code snapshots}: a list of [name, snapshotCreated]; absent means "couldn't read snapshots".</li>
 *   <li>{@code clusterName}, {@code hostName}: for the report.</li>
 *   <li>{@code configPresent}: false when the server had no config at all.</li>
 *   <li>{@code observedGuestAgent}: the guest agent probe, [result: responded | no-response | not-probed, reason,
 *       success, exitCode, error]; absent when no probe was considered.</li>
 * </ul>
 */
@CompileStatic
final class VmFacts {

	/** Morpheus server types of managed HVM VMs. */
	static final Set<String> MANAGED_TYPES = Collections.unmodifiableSet(['morpheusVm', 'morpheusWindowsVm', 'mvmVm'] as Set<String>)

	Long id
	String name
	String serverType
	/** True for a managed VM, false for a discovered one. */
	boolean managed
	String powerState
	String status

	/** serverOs fields (the reliable OS signal; see verify-findings). Null when Morpheus has no OS. */
	String osCode
	String osName
	String osFamily
	String osPlatform
	/** Server-level osType and platform, as stored (native migration leaves them incomplete). */
	String osType
	String platform

	String guestAgentStatus
	/** The guest agent probe: 'responded', 'no-response', 'not-probed', or null when none was considered. */
	String gaProbe
	String gaProbeReason
	Boolean gaProbeSuccess
	String gaProbeExitCode
	String gaProbeError
	Boolean agentInstalled
	String agentVersion
	Date lastAgentUpdate

	Boolean uefi
	Boolean secureBoot
	Boolean tpm

	/** vCPUs and cores per socket (Morpheus creates VMs with 1 core per socket; discovered VMs have null). */
	Long maxCores
	Long coresPerSocket
	/** Instance id of a managed VM (for links to its instance page), null for discovered VMs. */
	Long instanceId
	/** Placement strategy: auto, failover or pinned; null when Morpheus doesn't say. */
	String placementStrategy
	/** Host devices passed through to the VM (GPUs, PCI devices); null when they couldn't be read. */
	Integer passthroughDevices

	/** The server config map; empty when there is none. */
	Map<String, Object> config = [:]

	List<Volume> volumes = []
	List<Nic> interfaces = []
	/** Null when snapshots couldn't be read. */
	List<Snap> snapshots

	String clusterName
	String hostName
	String cloudName

	static final class Volume {
		String name
		boolean cd
		boolean root
		String diskMode
		String controllerName
		String controllerType
		Long usedStorage
		Long maxStorage
		String sourceImage
		String volumePath
		String imageType
		String datastoreName
		String datastoreTypeCode
		/** True for host-local storage (a directory datastore), false for shared; null when unknown. */
		Boolean datastoreLocal
	}

	static final class Nic {
		String name
		String typeCode
		String typeName
		String mac
		Boolean primary
	}

	static final class Snap {
		String name
		Date created
	}

	/** Sockets the guest sees: vCPUs / cores per socket; null when either is unknown. */
	Long getSockets() {
		if (maxCores == null || coresPerSocket == null || coresPerSocket < 1) return null
		return (long) Math.ceil(maxCores / (double) coresPerSocket)
	}

	/** Nested virtualization as set on the VM (config.nestedVirtualization); null when not set. */
	Boolean getNestedVirtualization() { bool(config?.get('nestedVirtualization')) }

	boolean isPoweredOn() { powerState == 'on' }

	boolean isPoweredOff() { powerState == 'off' }

	/** OS family seen inside the guest by a Tier 2 scan ('windows' or 'linux'); used only when Morpheus has no OS. */
	String guestOsFamily

	/** OS family from serverOs first (never from serverType), then the stored osType, then the guest scan. */
	String getOsFamilyKey() {
		String p = (osPlatform ?: '').toLowerCase()
		String f = (osFamily ?: '').toLowerCase()
		String c = (osCode ?: '').toLowerCase()
		if (osCode || osName || osPlatform || osFamily) {
			if (p == 'windows' || f == 'windows' || c.startsWith('windows')) return 'windows'
			return 'linux'
		}
		String t = (osType ?: '').toLowerCase()
		if (t == 'windows') return 'windows'
		if (t == 'linux') return 'linux'
		return guestOsFamily
	}

	boolean isWindows() { osFamilyKey == 'windows' }

	boolean hasServerOs() { osCode != null || osName != null }

	/** A short OS label for lists: the serverOs name, else its code, else the stored osType. */
	String getOsLabel() { osName ?: osCode ?: osType ?: 'unknown' }

	List<Volume> getDisks() { volumes.findAll { Volume v -> !v.cd } }

	List<Volume> getCdDrives() { volumes.findAll { Volume v -> v.cd } }

	/** The boot disk: the root volume, else the first disk. */
	Volume getBootDisk() {
		List<Volume> d = disks
		Volume r = d.find { Volume v -> v.root }
		return r ?: (d ? d[0] : null)
	}

	// ---- building from a REST-shaped map ----

	static VmFacts fromMap(Map input) {
		Map m = (input?.server instanceof Map) ? (Map) input.server : input
		if (m == null) throw new IllegalArgumentException('server map is required')
		VmFacts f = new VmFacts()
		f.id = asLong(m.id)
		f.name = str(m.name)
		Map type = map(m.computeServerType)
		f.serverType = str(type.code) ?: str(m.serverType)
		Object managed = type.managed != null ? type.managed : m.managed
		f.managed = managed != null ? (managed as Boolean) : (f.serverType in MANAGED_TYPES)
		f.powerState = str(m.powerState)
		f.status = str(m.status)

		Object os = m.serverOs
		if (os instanceof Map) {
			Map o = (Map) os
			f.osCode = str(o.code)
			f.osName = str(o.name)
			f.osFamily = str(o.osFamily)
			f.osPlatform = str(o.platform)
		} else if (os != null) {
			f.osCode = str(os)
			f.osFamily = str(m.osFamily)
		}
		f.osType = str(m.osType)
		f.platform = str(m.platform)

		f.guestAgentStatus = str(m.guestAgentStatus)
		Map probe = map(m.observedGuestAgent)
		f.gaProbe = str(probe.result)
		f.gaProbeReason = str(probe.reason)
		f.gaProbeSuccess = bool(probe.success)
		f.gaProbeExitCode = str(probe.exitCode)
		f.gaProbeError = str(probe.error)
		f.agentInstalled = bool(m.agentInstalled)
		f.agentVersion = str(m.agentVersion)
		f.lastAgentUpdate = date(m.lastAgentUpdate)
		f.uefi = bool(m.uefi)
		f.secureBoot = bool(m.secureBoot)
		f.tpm = bool(m.tpm)
		f.maxCores = asLong(m.maxCores)
		f.coresPerSocket = asLong(m.coresPerSocket)
		f.instanceId = asLong(map(m.instance).id) ?: asLong(m.instanceId)
		f.placementStrategy = str(m.placementStrategy) ?: str(map(m.config).placementStrategy)
		if (m.containsKey('devices') && m.devices instanceof List) f.passthroughDevices = ((List) m.devices).size()
		else if (m.passthroughDevices != null) f.passthroughDevices = asLong(m.passthroughDevices)?.intValue()
		Map cfg = map(m.config)
		f.config = new LinkedHashMap<String, Object>()
		cfg.each { Object k, Object v -> f.config.put(String.valueOf(k), v) }

		Map<String, Map> controllers = [:]
		for (Object c : list(m.controllers)) {
			Map cm = map(c)
			if (cm.id != null) controllers.put(String.valueOf(cm.id), cm)
		}
		for (Object o : list(m.volumes)) {
			Map v = map(o)
			Volume vol = new Volume()
			vol.name = str(v.name)
			Map vt = map(v.type)
			String category = str(v.category)
			String typeCode = str(vt.code) ?: str(v.typeCode)
			vol.cd = category == 'cd' || (typeCode ?: '').toLowerCase().contains('cdrom')
			vol.root = bool(v.rootVolume) ?: bool(v.root) ?: false
			vol.diskMode = str(v.diskMode)
			Map ctl = map(v.controller)
			Map full = ctl.id != null ? controllers.get(String.valueOf(ctl.id)) : null
			vol.controllerName = str(ctl.name) ?: str(full?.name)
			vol.controllerType = str(map(full?.type).code) ?: str(v.controllerType)
			vol.usedStorage = asLong(v.usedStorage)
			vol.maxStorage = asLong(v.maxStorage)
			vol.sourceImage = str(v.sourceImage)
			vol.volumePath = str(v.volumePath)
			vol.imageType = str(v.imageType)
			Map ds = map(v.datastore)
			vol.datastoreName = str(ds.name)
			vol.datastoreTypeCode = str(map(ds.datastoreType).code) ?: str(ds.typeCode)
			Object local = map(ds.datastoreType).localStorage != null ? map(ds.datastoreType).localStorage : ds.local
			vol.datastoreLocal = local != null ? bool(local) : (vol.datastoreTypeCode ? vol.datastoreTypeCode == 'libvirt-dir' : null)
			f.volumes.add(vol)
		}
		for (Object o : list(m.interfaces)) {
			Map i = map(o)
			Nic n = new Nic()
			n.name = str(i.name)
			Map it = map(i.type)
			n.typeCode = str(it.code) ?: str(i.typeCode)
			n.typeName = str(it.name) ?: str(i.typeName)
			n.mac = str(i.macAddress) ?: str(i.mac)
			n.primary = bool(i.primaryInterface) ?: bool(i.primary)
			f.interfaces.add(n)
		}
		if (m.containsKey('snapshots') && m.snapshots instanceof List) {
			f.snapshots = []
			for (Object o : (List) m.snapshots) {
				Map s = map(o)
				Snap sn = new Snap()
				sn.name = str(s.name)
				sn.created = date(s.snapshotCreated) ?: date(s.dateCreated)
				f.snapshots.add(sn)
			}
		}
		f.clusterName = str(m.clusterName)
		f.hostName = str(m.hostName) ?: str(map(m.parentServer).name)
		f.cloudName = str(map(m.zone).name) ?: str(map(m.cloud).name)
		return f
	}

	static String str(Object o) {
		if (o == null) return null
		String s = o.toString()
		return s.isEmpty() || s == 'null' ? null : s
	}

	static Map map(Object o) { o instanceof Map ? (Map) o : [:] }

	static List list(Object o) { o instanceof List ? (List) o : [] }

	static Boolean bool(Object o) {
		if (o == null) return null
		if (o instanceof Boolean) return (Boolean) o
		String s = o.toString().trim().toLowerCase()
		if (s == 'true') return true
		if (s == 'false') return false
		return null
	}

	static Long asLong(Object o) {
		if (o == null) return null
		if (o instanceof Number) return ((Number) o).longValue()
		try { return Long.valueOf(o.toString().trim()) } catch (NumberFormatException ignored) { return null }
	}

	/** Dates come as Date (plugin API) or ISO-8601 text (REST, fixtures). */
	static Date date(Object o) {
		if (o == null) return null
		if (o instanceof Date) return (Date) o
		String s = o.toString().trim()
		if (!s) return null
		for (String p : ["yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", 'yyyy-MM-dd']) {
			try {
				SimpleDateFormat fmt = new SimpleDateFormat(p)
				fmt.setTimeZone(TimeZone.getTimeZone('UTC'))
				fmt.setLenient(false)
				return fmt.parse(s)
			} catch (Exception ignored) {}
		}
		return null
	}
}
