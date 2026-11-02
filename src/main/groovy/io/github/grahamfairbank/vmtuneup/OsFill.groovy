package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.OsIdentity
import io.github.grahamfairbank.vmtuneup.core.VmReport

/**
 * The plugin's one write (0.7): fill in a VM's EMPTY guest OS fields (serverOs, osType, platform, platformVersion)
 * from what a Tier 2 guest scan found inside the VM, after the user confirms, with "VM Tune-Up: Update VM records".
 * A field Morpheus already has is never changed. Each write is logged with the user id, server id and OS code.
 */
@Slf4j
class OsFill {

	/** VMs filled per cluster request (VT-03); the button can be used again for the rest. */
	static final int MAX_PER_REQUEST = 100
	/** A version must look like 9, 22.04 or 2022 (VT-04): the guest controls this text. */
	static final java.util.regex.Pattern VERSION = ~/^\d{1,4}(\.\d{1,4}){0,2}$/

	/** What would be filled in for this VM, or null when nothing would: [code, name, fields]. */
	static Map proposal(VmReport r) {
		if (r?.guest == null) return null
		OsIdentity.Match m = OsIdentity.match(r.guest)
		if (m == null || !m.exact) return null
		def f = r.facts
		List<String> fields = []
		if (f.osCode == null && f.osName == null) fields << 'OS type'
		if (!f.osType) fields << 'OS family'
		if (!f.platform) fields << 'platform'
		if (fields.isEmpty()) return null
		return [code: m.code, name: m.name, exact: m.exact, fields: fields]
	}

	/** Fills the empty fields of a fresh copy of the server and saves it once. Returns a note for the tab. */
	static String fill(MorpheusContext morpheus, ScanService svc, ComputeServer loaded, Long userId) {
		Long id = loaded?.id
		def g = svc.guestScan.facts(id)
		if (g == null) return 'Run the guest checks first: the OS comes from inside the VM.'
		OsIdentity.Match m = OsIdentity.match(g)
		if (m == null || !m.exact) return 'The guest OS has no exact match in the Morpheus OS catalog; set it by hand.'
		// VT-04: the family must match how the scan reached the guest (Windows answers only through the guest agent path).
		String channel = svc.guestScan.cached(id)?.os
		if (channel && channel != m.osType) return 'The guest scan and the OS it reports disagree; nothing was changed.'
		ComputeServer s = svc.loadFresh(id)
		if (s == null) return 'The VM could not be read again; nothing was changed.'
		List<String> done = []
		if (ServerFacts.safe { s.serverOs } == null) {
			OsType t = ServerFacts.safe { Workers.call(svc.workers.io, 10_000L, 'os type') { morpheus.services.osType.find(new DataQuery().withFilter('code', m.code)) } } as OsType
			if (t == null) return "Morpheus has no OS type '${m.code}'; nothing was changed.".toString()
			s.serverOs = t
			done << "OS type ${t.name ?: m.name}".toString()
		}
		if (!ServerFacts.safe { s.osType }) { s.osType = m.osType; done << "OS family ${m.osType}".toString() }
		if (!ServerFacts.safe { s.platform }) { s.platform = m.platform; done << "platform ${m.platform}".toString() }
		if (!ServerFacts.safe { s.platformVersion } && m.platformVersion && VERSION.matcher(m.platformVersion).matches()) {
			s.platformVersion = m.platformVersion
			done << "version ${m.platformVersion}".toString()
		}
		if (done.isEmpty()) return 'Nothing to fill in: Morpheus already has these fields.'
		ComputeServer saved
		try {
			saved = (Workers.call(svc.workers.io, 15_000L, 'save') { morpheus.services.computeServer.save(s) }) as ComputeServer
		} catch (Throwable t) {
			log.warn("vm-tuneup: OS fill-in save on server ${id} failed: ${t}")
			return 'Morpheus did not save the change in time; check the VM record and try again.'
		}
		log.info("vm-tuneup: OS fill-in on server ${id} by user ${userId}: ${m.code} (${done.size()} fields)${saved == null ? ' - save returned nothing' : ''}")
		return saved == null ? 'Morpheus did not confirm the save; check the VM record.' : "Filled in: ${done.join(', ')}.".toString()
	}
}
