package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.migrationhygiene.core.ScanContext
import io.github.grahamfairbank.migrationhygiene.core.VmFacts
import io.github.grahamfairbank.migrationhygiene.core.VmReport

/**
 * Finds HVM VMs and scans them. Read-only: it only lists and reads servers and snapshots.
 * A VM counts when its type is an HVM VM type, or when it runs on an HVM host (see {@link HvmScope#isHvmVm}).
 */
@Slf4j
class FleetScan {

	final MorpheusContext morpheus

	FleetScan(MorpheusContext morpheus) {
		this.morpheus = morpheus
	}

	/** Every HVM VM the manager knows. */
	List<ComputeServer> hvmVms() {
		Map<Long, ComputeServer> byId = new LinkedHashMap<>()
		list(new DataQuery().withFilter('computeServerType.code', 'in', HvmScope.VM_TYPES as List)).each { if (it?.id != null) byId[it.id] = it }
		List<Long> hosts = list(new DataQuery().withFilter('computeServerType.code', 'in', HvmScope.HOST_TYPES as List))*.id.findAll { it != null }
		vmsOnHosts(hosts).each { if (it?.id != null) byId[it.id] = it }
		return byId.values().findAll { HvmScope.isHvmVm(it) } as List<ComputeServer>
	}

	/** HVM VMs on the cluster's hosts. */
	List<ComputeServer> clusterVms(ComputeServerGroup cluster) {
		List<ComputeServer> members = list(new DataQuery().withFilter('serverGroup.id', cluster.id))
		List<Long> hosts = members.findAll { HvmScope.isHvmHostType(HvmScope.typeCode(it)) }*.id.findAll { it != null }
		Map<Long, ComputeServer> byId = new LinkedHashMap<>()
		members.findAll { HvmScope.isHvmVm(it) }.each { byId[it.id] = it }
		vmsOnHosts(hosts).findAll { HvmScope.isHvmVm(it) }.each { byId[it.id] = it }
		return byId.values() as List<ComputeServer>
	}

	List<ComputeServer> vmsOnHosts(Collection<Long> hostIds) {
		if (!hostIds) return []
		return list(new DataQuery().withFilter('parentServer.id', 'in', hostIds as List))
	}

	private List<ComputeServer> list(DataQuery q) {
		try {
			return morpheus.services.computeServer.list(q) ?: []
		} catch (Throwable e) {
			log.warn("migration-hygiene: server list failed: ${e}")
			return []
		}
	}

	/**
	 * Newest Morpheus agent per OS family across the HVM fleet, from the listed servers only (no extra reads).
	 */
	Map<String, String> agentBaseline(List<ComputeServer> fleet = null) {
		List<VmFacts> light = (fleet ?: hvmVms()).collect { ComputeServer s ->
			def os = ServerFacts.safe { s.serverOs }
			VmFacts.fromMap([name: ServerFacts.safe { s.name }, agentInstalled: ServerFacts.safe { s.agentInstalled },
				agentVersion: ServerFacts.safe { s.agentVersion }, osType: ServerFacts.safe { s.osType },
				serverOs: os == null ? null : [code: ServerFacts.safe { os.code }, name: ServerFacts.safe { os.name },
					osFamily: ServerFacts.safe { os.osFamily }, platform: ServerFacts.safe { os.platform?.toString() }]])
		}
		return ScanContext.baselineOf(light)
	}

	/**
	 * How a scan may use the guest agent probe. {@code permitted}: the user's guest-scan grant (null when the user
	 * couldn't be determined). {@code mayRun}: false to use cached probe results only. At most {@code concurrency}
	 * probes run at once, and no new probe starts after {@code budgetMs}.
	 */
	static class ProbePolicy {
		Boolean permitted
		boolean mayRun = true
		int concurrency = 1
		long budgetMs = 60_000L
	}

	/** The fleet report: up to 4 probes at a time, none started after 3 minutes. */
	static ProbePolicy reportPolicy(Boolean permitted) {
		return new ProbePolicy(permitted: permitted, mayRun: true, concurrency: 4, budgetMs: 180_000L)
	}

	/**
	 * Reads each server in full and scans it. The agent baseline comes from the servers scanned. With a probe and
	 * a policy, VMs whose guest agent status isn't reported get an observedGuestAgent fact.
	 */
	List<VmReport> scan(List<ComputeServer> servers, Map<String, String> baseline = null, GuestAgentProbe probe = null, ProbePolicy policy = null) {
		ServerFacts adapter = new ServerFacts(morpheus)
		List<ComputeServer> loaded = []
		List<Map> maps = []
		for (ComputeServer s : servers ?: []) {
			try {
				ComputeServer full = HvmScope.load(morpheus, s)
				maps << adapter.toMap(full)
				loaded << full
			} catch (Throwable e) {
				log.warn("migration-hygiene: couldn't read server ${s?.id}: ${e}")
			}
		}
		List<Map> observed = probeAll(loaded, probe, policy)
		List<VmFacts> facts = []
		maps.eachWithIndex { Map m, int i ->
			if (observed[i] != null) m.observedGuestAgent = observed[i]
			try {
				facts << VmFacts.fromMap(m)
			} catch (Throwable e) {
				log.warn("migration-hygiene: couldn't read server ${m?.id}: ${e}")
			}
		}
		ScanContext ctx = new ScanContext(new Date(), baseline ?: ScanContext.baselineOf(facts))
		return facts.collect { VmReport.scan(it, ctx) }
	}

	/** One observation per server (null where none applies), in order. Bounded in concurrency and total time. */
	static List<Map> probeAll(List<ComputeServer> servers, GuestAgentProbe probe, ProbePolicy policy) {
		List<Map> out = new ArrayList<Map>(Collections.nCopies(servers.size(), (Map) null))
		if (probe == null || policy == null || servers.isEmpty()) return out
		long deadline = System.currentTimeMillis() + policy.budgetMs
		int threads = Math.max(1, Math.min(policy.concurrency, servers.size()))
		if (threads == 1 || !policy.mayRun) {
			servers.eachWithIndex { ComputeServer s, int i -> out[i] = safeObserve(probe, s, policy, deadline) }
			return out
		}
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, { Runnable r ->
			Thread t = new Thread(r, 'migration-hygiene-probe')
			t.daemon = true
			return t
		} as java.util.concurrent.ThreadFactory)
		try {
			List<java.util.concurrent.Future<Map>> futures = servers.collect { ComputeServer s ->
				pool.submit({ -> safeObserve(probe, s, policy, deadline) } as java.util.concurrent.Callable<Map>)
			}
			// Hard stop: the budget, plus one probe timeout for probes started just before it, plus a margin.
			long hardEnd = deadline + probe.timeoutMs + 5_000L
			futures.eachWithIndex { java.util.concurrent.Future<Map> f, int i ->
				try {
					out[i] = f.get(Math.max(0L, hardEnd - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
				} catch (Throwable e) {
					f.cancel(true)
					out[i] = GuestAgentProbe.notProbed(GuestAgentProbe.TIME_BUDGET)
				}
			}
		} finally {
			pool.shutdownNow()
		}
		return out
	}

	private static Map safeObserve(GuestAgentProbe probe, ComputeServer s, ProbePolicy policy, long deadline) {
		try {
			return probe.observe(s, policy.permitted, policy.mayRun, deadline)
		} catch (Throwable e) {
			log.warn("migration-hygiene: guest agent probe for server ${s?.id} failed: ${e}")
			return GuestAgentProbe.notProbed('the probe failed inside the plugin')
		}
	}
}
