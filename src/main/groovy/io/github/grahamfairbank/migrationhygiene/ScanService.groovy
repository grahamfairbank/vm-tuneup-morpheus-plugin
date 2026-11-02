package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.migrationhygiene.core.ScanContext
import io.github.grahamfairbank.migrationhygiene.core.VmFacts
import io.github.grahamfairbank.migrationhygiene.core.VmReport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans and their caches (0.2.0). Nothing here runs inside a page render: tabs only read the caches
 * ({@link #cachedVm}, {@link #cachedCluster}), and the web routes start the work.
 *
 * <ul>
 *   <li>One VM: {@link #scanVmNow}, for the VM route, with a hard time budget on the request thread.</li>
 *   <li>A cluster: {@link #startOrJoin}, a background single-flight scan per cluster on the 2 scan workers.</li>
 *   <li>The agent baseline: refreshed in the background at most every 15 minutes, single-flight.</li>
 * </ul>
 * Results are kept 10 minutes. Cached facts never include the guest agent probe result: that is added per viewer
 * from the probe's own cache, so a viewer without the guest-scan permission never sees one.
 */
@Slf4j
class ScanService {

	static final long RESULT_TTL_MS = 10 * 60_000L
	static final long BASELINE_TTL_MS = 15 * 60_000L
	static final long BASELINE_RETRY_MS = 60_000L
	static final String TIME_BUDGET = 'time budget'

	final MorpheusContext morpheus
	final Workers workers
	final GuestAgentProbe probe
	final Closure<Long> clock

	/** Budgets, settable for tests. */
	long vmBudgetMs = 15_000L
	long lookupBudgetMs = 5_000L
	long listBudgetMs = 60_000L
	long baselineListBudgetMs = 120_000L
	long baselineWaitMs = 60_000L
	long clusterCapMs = 15 * 60_000L
	long rescanMinMs = 30_000L
	/** Cluster scans one user may have running at once (0.4.0). Joining a running scan doesn't count. */
	int maxScansPerUser = 3

	static final String TOO_MANY = 'too many scans in progress, try again shortly'
	static final String TOO_MANY_FOR_USER = 'you already have the most scans running at once, try again when one finishes'
	static final String SHUTTING_DOWN = 'the plugin is shutting down'

	final ShortMemo<Long, VmEntry> vms
	final Map<Long, ClusterJob> clusters = new ConcurrentHashMap<Long, ClusterJob>()

	private volatile Map<String, String> baselineValue
	private volatile long baselineAt = 0L
	private volatile long baselineTriedAt = Long.MIN_VALUE / 2
	private final AtomicBoolean baselineRunning = new AtomicBoolean()
	private volatile CountDownLatch baselineLatch = new CountDownLatch(0)
	final AtomicInteger baselineRuns = new AtomicInteger()

	ScanService(MorpheusContext morpheus, Workers workers, GuestAgentProbe probe, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.morpheus = morpheus
		this.workers = workers
		this.probe = probe
		this.clock = clock
		this.vms = new ShortMemo<Long, VmEntry>(RESULT_TTL_MS, 5000, clock)
	}

	// ---- one VM -------------------------------------------------------------------------------------

	/** One VM's scan result. {@code facts} is null when the VM couldn't be read. */
	static class VmEntry {
		Long serverId
		String name
		Long accountId
		boolean found = true
		boolean hvm
		/** ServerFacts.toMap, without observedGuestAgent. */
		Map facts
		/** Parts not finished within the time budget. */
		List<String> skipped = []
		/** Set when the VM couldn't be scanned at all: "time budget" or a short reason. */
		String error
		/** The agent baseline used; null when it wasn't ready. */
		Map<String, String> baseline
		long at
		/** The loaded server, only between a scan and a probe; never cached. */
		transient ComputeServer server

		String getScanStatus() {
			if (error) return "unknown: ${error}".toString()
			if (skipped) return "partial: ${TIME_BUDGET} (${skipped.join(', ')})".toString()
			return 'ok'
		}
	}

	VmEntry cachedVm(Long id) { vms.get(id) }

	/**
	 * Scans one VM on the {@code vm} pool and waits at most {@code vmBudgetMs}: anything not done by then is
	 * "unknown: time budget". Service reads go through the {@code io} pool, each cut off at the deadline. The
	 * agent baseline comes from the cache; no fleet listing happens here.
	 */
	VmEntry scanVmNow(Long serverId, ComputeServer preloaded = null) {
		long start = clock.call()
		try {
			VmEntry e = Workers.call(workers.vm, vmBudgetMs, 'scan') {
				scanOne(serverId, null, workers.io, start + vmBudgetMs - Math.min(1_000L, (long) (vmBudgetMs / 5)), baselineIfReady(), null, null, preloaded)
			}
			return e
		} catch (Workers.BudgetExceeded ignored) {
			return timedOut(serverId, null)
		} catch (Throwable t) {
			log.warn("migration-hygiene: scan of server ${serverId} failed: ${t}")
			return new VmEntry(serverId: serverId, error: 'the scan failed inside the plugin', at: clock.call())
		}
	}

	VmEntry timedOut(Long id, String name, Long accountId = null) {
		return new VmEntry(serverId: id, name: name, accountId: accountId, error: TIME_BUDGET, at: clock.call())
	}

	/**
	 * Reads one server and its facts on the calling thread; every service read runs on {@code pool} and must
	 * finish by {@code deadline}. Caches the result (without the server object, which stays on the entry
	 * returned so a probe can follow). {@code preloaded}: the server record the caller already read (the VM route
	 * reads it first for the tenant check), so it isn't read twice.
	 */
	VmEntry scanOne(Long serverId, String knownName, java.util.concurrent.ExecutorService pool, long deadline, Map<String, String> baseline,
	                Map<Long, String> hostNames, Map<Long, String> clusterByHost, ComputeServer preloaded = null) {
		List<String> skipped = Collections.synchronizedList(new ArrayList<String>())
		Closure<Object> reader = Workers.budgetedReader(pool, deadline, skipped, clock)
		ComputeServer full
		try {
			full = preloaded != null ? preloaded : (reader.call('server record') { morpheus.services.computeServer.get(serverId) }) as ComputeServer
		} catch (Workers.BudgetExceeded ignored) {
			return timedOut(serverId, knownName)
		} catch (Throwable t) {
			log.debug("migration-hygiene: couldn't read server ${serverId}: ${t}")
			return new VmEntry(serverId: serverId, name: knownName, error: 'the server could not be read', at: clock.call())
		}
		if (full == null) return new VmEntry(serverId: serverId, name: knownName, found: false, error: 'no such server', at: clock.call())
		VmEntry e = new VmEntry(serverId: serverId, name: (ServerFacts.safe { full.name } ?: knownName) as String,
			accountId: ServerFacts.safe { full.account?.id } as Long, hvm: HvmScope.isHvmVm(full), baseline: baseline, server: full)
		if (e.hvm) {
			ServerFacts adapter = new ServerFacts(morpheus, reader)
			if (hostNames) adapter.hostNames.putAll(hostNames)
			if (clusterByHost) adapter.clusterByHost.putAll(clusterByHost)
			Map m = adapter.toMap(full)
			m.remove('observedGuestAgent')
			e.facts = m
		}
		e.skipped = new ArrayList<String>(skipped)
		e.at = clock.call()
		cache(e)
		return e
	}

	private void cache(VmEntry e) {
		if (e?.serverId == null || e.error) return
		ComputeServer s = e.server
		e.server = null
		VmEntry copy = new VmEntry(serverId: e.serverId, name: e.name, accountId: e.accountId, found: e.found, hvm: e.hvm, facts: e.facts,
			skipped: e.skipped, error: e.error, baseline: e.baseline, at: e.at)
		vms.put(e.serverId, copy)
		e.server = s
	}

	/**
	 * The VmReport for an entry as {@code permitted} (the viewer's guest-scan grant, null when unknown) may see
	 * it. Pure: no service calls. Null when the entry has no facts.
	 */
	VmReport report(VmEntry e, Boolean permitted) {
		if (e?.facts == null) return null
		Map m = new LinkedHashMap(e.facts)
		Map obs = probe?.cachedObservation(m, e.hvm, permitted)
		if (obs != null) m.observedGuestAgent = obs
		VmFacts f = VmFacts.fromMap(m)
		return VmReport.scan(f, new ScanContext(new Date(e.at), e.baseline ?: [:], e.baseline != null))
	}

	/**
	 * The guest agent probe state for the VM tab's "Check guest agent" line, from the probe cache only (no service
	 * call, never runs a probe): the cached observation, or null when Morpheus reports a real status.
	 */
	Map probeState(VmEntry e, Boolean permitted) {
		if (e?.facts == null) return null
		return probe?.cachedObservation(e.facts, e.hvm, permitted)
	}

	// ---- agent baseline -------------------------------------------------------------------------------

	/** The cached baseline, or null when none is ready; starts a background refresh when it's due. */
	Map<String, String> baselineIfReady() {
		kickBaseline()
		return baselineValue
	}

	/** Tests: a ready baseline, as if just computed. */
	void baselineValueForTest(Map<String, String> b) {
		baselineValue = b
		baselineAt = clock.call()
	}

	/** Background use only: waits up to {@code ms} for a first baseline. */
	Map<String, String> awaitBaseline(long ms) {
		kickBaseline()
		if (baselineValue == null) {
			try { baselineLatch.await(ms, TimeUnit.MILLISECONDS) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
		}
		return baselineValue
	}

	void kickBaseline() {
		long now = clock.call()
		boolean due = baselineValue == null ? now - baselineTriedAt >= BASELINE_RETRY_MS : now - baselineAt >= BASELINE_TTL_MS
		if (!due || !baselineRunning.compareAndSet(false, true)) return
		baselineTriedAt = now
		CountDownLatch latch = new CountDownLatch(1)
		baselineLatch = latch
		try {
			workers.baseline.submit { runBaseline(latch) }
		} catch (RejectedExecutionException e) {
			baselineRunning.set(false)
			latch.countDown()
		}
	}

	private void runBaseline(CountDownLatch latch) {
		try {
			baselineRuns.incrementAndGet()
			Map<String, String> b = (Workers.call(workers.bgIo, baselineListBudgetMs, 'agent baseline') { new FleetScan(morpheus).agentBaseline() }) as Map<String, String>
			baselineValue = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b ?: [:]))
			baselineAt = clock.call()
		} catch (Throwable t) {
			log.warn("migration-hygiene: agent baseline refresh failed: ${t}")
		} finally {
			baselineRunning.set(false)
			latch.countDown()
		}
	}

	// ---- clusters -------------------------------------------------------------------------------------

	/** Who started a cluster scan (0.4.0): probes stay inside this tenant unless it's the master tenant. */
	static class Starter {
		final Long userId
		final Long accountId
		final boolean master

		Starter(Long userId, Long accountId, boolean master) {
			this.userId = userId
			this.accountId = accountId
			this.master = master
		}

		static Starter of(User u) {
			return new Starter(ServerFacts.safe { u?.id } as Long, ServerFacts.safe { u?.account?.id } as Long,
				ServerFacts.safe { u?.account?.masterAccount } == true)
		}

		/** The starter's tenant owns the VM, or the starter is in the master tenant. */
		boolean mayProbe(Long vmAccountId) {
			return master || (accountId != null && vmAccountId != null && accountId == vmAccountId)
		}
	}

	static class ClusterJob {
		static final String LISTING = 'listing'
		static final String SCANNING = 'scanning'
		static final String COMPLETE = 'complete'
		static final String FAILED = 'failed'

		final Long clusterId
		final String clusterName
		final boolean probe
		/** Null for scans started outside a request (tests); such a scan never probes. */
		final Starter starter
		final long startedAt
		volatile String state = LISTING
		volatile Integer total
		volatile int hvmHosts
		final AtomicInteger done = new AtomicInteger()
		volatile Long finishedAt
		volatile String error
		final Map<Long, VmEntry> results = new ConcurrentHashMap<Long, VmEntry>()
		volatile Future<?> future

		ClusterJob(Long clusterId, String clusterName, boolean probe, long startedAt, Starter starter = null) {
			this.clusterId = clusterId
			this.clusterName = clusterName
			this.probe = probe
			this.startedAt = startedAt
			this.starter = starter
		}

		/** A scan that was refused before it started (queue full, per-user limit); never stored. */
		static ClusterJob refused(Long clusterId, String clusterName, String why, long now) {
			ClusterJob j = new ClusterJob(clusterId, clusterName, false, now)
			j.state = FAILED
			j.error = why
			j.finishedAt = now
			return j
		}

		boolean isRunning() { state == LISTING || state == SCANNING }
	}

	boolean fresh(ClusterJob j) {
		return j != null && (j.running || (j.finishedAt != null && clock.call() - j.finishedAt < RESULT_TTL_MS))
	}

	/** A running scan, or a finished one less than 10 minutes old; else null. Pure read. */
	ClusterJob cachedCluster(Long id) {
		ClusterJob j = id == null ? null : clusters.get(id)
		return fresh(j) ? j : null
	}

	/**
	 * Starts a background scan of the cluster, or joins the one running. Without {@code force}, a fresh result
	 * is returned as is. With it (Rescan), a new scan starts unless one is running or started less than 30 seconds
	 * ago. Never blocks.
	 *
	 * 0.4.0: a new scan is refused (a failed job that is not stored, so the next try starts afresh) when the
	 * starter already has {@link #maxScansPerUser} scans running, or when the job queue is full. Only the routes
	 * call this, and only for the plugin script's own requests (see MigrationHygieneController.scriptRequest).
	 */
	ClusterJob startOrJoin(Long clusterId, String clusterName, boolean probe, boolean force, Starter starter = null) {
		synchronized (clusters) {
			ClusterJob cur = clusters.get(clusterId)
			if (cur != null && cur.running) return cur
			if (cur != null && !force && fresh(cur)) return cur
			if (cur != null && force && clock.call() - cur.startedAt < rescanMinMs) return cur
			if (starter?.userId != null && runningFor(starter.userId) >= maxScansPerUser) {
				return ClusterJob.refused(clusterId, clusterName, TOO_MANY_FOR_USER, clock.call())
			}
			ClusterJob job = new ClusterJob(clusterId, clusterName, probe, clock.call(), starter)
			if (clusters.size() > 500) clusters.values().removeIf { ClusterJob j -> !fresh(j) }
			clusters.put(clusterId, job)
			try {
				job.future = workers.jobs.submit { runCluster(job) }
			} catch (RejectedExecutionException e) {
				if (cur != null) clusters.put(clusterId, cur) else clusters.remove(clusterId)
				return ClusterJob.refused(clusterId, clusterName, workers.jobs.isShutdown() ? SHUTTING_DOWN : TOO_MANY, clock.call())
			}
			return job
		}
	}

	/** Running cluster scans started by this user. */
	int runningFor(Long userId) {
		return clusters.values().count { ClusterJob j -> j.running && j.starter?.userId == userId } as int
	}

	/** The coordinator: lists the cluster's HVM VMs, scans them on the scan workers, stops at the overall cap. */
	void runCluster(ClusterJob job) {
		long capEnd = job.startedAt + clusterCapMs
		try {
			List<ComputeServer> members = ((Workers.call(workers.bgIo, Math.min(listBudgetMs, capEnd - clock.call()), 'cluster members') {
				morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', job.clusterId))
			}) as List<ComputeServer>) ?: []
			List<ComputeServer> hosts = members.findAll { HvmScope.isHvmHostType(HvmScope.typeCode(it)) }
			List<Long> hostIds = hosts*.id.findAll { it != null } as List<Long>
			Map<Long, String> hostNames = [:]
			Map<Long, String> clusterByHost = [:]
			hosts.each { ComputeServer h ->
				if (h.id == null) return
				String n = ServerFacts.safe { h.name } as String
				if (n) hostNames[h.id] = n
				if (job.clusterName) clusterByHost[h.id] = job.clusterName
			}
			Map<Long, ComputeServer> vmsById = new LinkedHashMap<Long, ComputeServer>()
			Map<Long, Long> accountByVm = [:]
			members.findAll { HvmScope.isHvmVm(it) && it.id != null }.each { vmsById[it.id] = it }
			if (hostIds) {
				List<ComputeServer> onHosts = ((Workers.call(workers.bgIo, Math.min(listBudgetMs, capEnd - clock.call()), 'VMs on the hosts') {
					morpheus.services.computeServer.list(new DataQuery().withFilter('parentServer.id', 'in', hostIds))
				}) as List<ComputeServer>) ?: []
				onHosts.findAll { HvmScope.isHvmVm(it) && it.id != null }.each { vmsById[it.id] = it }
			}
			vmsById.each { Long id, ComputeServer s -> accountByVm[id] = ServerFacts.safe { s.account?.id } as Long }
			job.hvmHosts = hosts.size()
			job.total = vmsById.size()
			job.state = ClusterJob.SCANNING
			Map<String, String> baseline = vmsById ? awaitBaseline(Math.min(baselineWaitMs, Math.max(0L, capEnd - clock.call()))) : null
			Map<Long, Future<?>> futures = new LinkedHashMap<Long, Future<?>>()
			vmsById.values().each { ComputeServer s ->
				String name = ServerFacts.safe { s.name } as String
				Long account = accountByVm[s.id]
				futures[s.id] = workers.scan.submit { scanInBackground(job, s.id, name, account, baseline, hostNames, clusterByHost, capEnd) }
			}
			futures.each { Long id, Future<?> f ->
				try {
					f.get(Math.max(0L, capEnd - clock.call()), TimeUnit.MILLISECONDS)
				} catch (TimeoutException e) {
					f.cancel(true)
				} catch (Throwable e) {
					log.debug("migration-hygiene: scan of server ${id} failed: ${e}")
				}
				if (!job.results.containsKey(id)) {
					job.results[id] = timedOut(id, ServerFacts.safe { vmsById[id].name } as String, accountByVm[id])
					job.done.incrementAndGet()
				}
			}
			job.state = ClusterJob.COMPLETE
		} catch (Workers.BudgetExceeded e) {
			job.error = e.message
			job.state = ClusterJob.FAILED
		} catch (Throwable e) {
			log.warn("migration-hygiene: cluster scan ${job.clusterId} failed: ${e}")
			job.error = 'the cluster scan failed inside the plugin'
			job.state = ClusterJob.FAILED
		} finally {
			job.finishedAt = clock.call()
			log.info("migration-hygiene: cluster ${job.clusterId} scan ${job.state}: ${job.done.get()} of ${job.total} VMs in ${job.finishedAt - job.startedAt} ms")
		}
	}

	/**
	 * One VM of a cluster scan. {@code listedAccountId}: the VM's tenant from the cluster listing, kept on entries
	 * that couldn't be read so the tenant filter still applies to them. A probe-enabled scan probes only VMs in the
	 * starter's tenant, unless the starter is in the master tenant (0.4.0).
	 */
	private void scanInBackground(ClusterJob job, Long id, String name, Long listedAccountId, Map<String, String> baseline, Map<Long, String> hostNames,
	                              Map<Long, String> clusterByHost, long capEnd) {
		VmEntry e
		try {
			long now = clock.call()
			if (now >= capEnd) {
				e = timedOut(id, name, listedAccountId)
			} else {
				e = scanOne(id, name, workers.bgIo, Math.min(now + vmBudgetMs, capEnd), baseline, hostNames, clusterByHost)
				if (e.accountId == null) e.accountId = listedAccountId
				if (job.probe && e.hvm && e.server != null && probe != null && job.starter?.mayProbe(e.accountId)) {
					try {
						probe.observe(e.server, true, true, capEnd)
					} catch (Throwable t) {
						log.debug("migration-hygiene: probe of server ${id} failed: ${t}")
					}
				}
				e.server = null
			}
		} catch (Throwable t) {
			e = new VmEntry(serverId: id, name: name, accountId: listedAccountId, error: 'the scan failed inside the plugin', at: clock.call())
		}
		if (job.results.putIfAbsent(id, e) == null) job.done.incrementAndGet()
		// Pace background scans so they never crowd the manager's CPU or database.
		if (pauseMs > 0) {
			try { Thread.sleep(pauseMs) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
		}
	}

	/** Pause after each VM in a background cluster scan (ms). Tests set 0. */
	long pauseMs = 250

	/** Rows of a finished or running job, worst first; entries without facts last. */
	List<VmEntry> entries(ClusterJob job) {
		return new ArrayList<VmEntry>(job?.results?.values() ?: [])
	}
}
