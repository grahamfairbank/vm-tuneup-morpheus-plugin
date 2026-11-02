package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The guest agent probe behind T1-GA-STATUS. On Morpheus 9.1.0.1 a ComputeServer read through the plugin API
 * always carries the model default guestAgentStatus "unknown", so when the status is unknown (or null) on a
 * powered-on HVM VM, the plugin asks the QEMU guest agent to run a fixed no-op command (Linux {@code true},
 * Windows {@code cmd /c exit 0}) through Morpheus's guest execution. An answer means the agent works.
 *
 * Rules: only for users granted "Migration Hygiene: Run guest scans" at full (the caller decides and passes
 * {@code permitted}; null means the user couldn't be determined, which never probes); never on powered-off or
 * non-HVM servers; a hard timeout per VM; results cached per server id for 10 minutes. No command output is kept:
 * only the success flag, the exit code, the first 200 characters of the error message and when it ran ({@code at}).
 */
@Slf4j
class GuestAgentProbe {

	static final String RESPONDED = 'responded'
	static final String NO_RESPONSE = 'no-response'
	static final String NOT_PROBED = 'not-probed'

	static final String LINUX_COMMAND = 'true'
	static final String WINDOWS_COMMAND = 'cmd /c exit 0'

	static final long TIMEOUT_MS = 20_000L
	/** As long as scan results are kept (0.2.0), so a cached scan and its probe result expire together. */
	static final long CACHE_TTL_MS = 10 * 60_000L
	static final int CACHE_MAX = 500
	static final int ERROR_MAX = 200

	static final String NO_PERMISSION = 'guest-scan permission not granted'
	static final String NO_USER = 'the user could not be determined'
	static final String TIME_BUDGET = 'time budget'
	static final String CACHE_ONLY = 'not run automatically: use "Check guest agent" on the VM\'s Migration Hygiene tab'
	static final String BUSY = 'the plugin is busy with other guest agent checks; try again shortly'
	/** "Check guest agent" runs at most once per VM per minute (0.4.0); sooner, the last result is shown. */
	static final long MIN_INTERVAL_MS = 60_000L

	final MorpheusContext morpheus
	final long timeoutMs
	final ShortMemo<Long, Map> cache
	private final Closure<Long> clock
	/** One "Check guest agent" in flight per server (0.4.0): a second caller waits for the same answer. */
	private final Map<Long, CompletableFuture<Map>> inFlight = new ConcurrentHashMap<Long, CompletableFuture<Map>>()

	GuestAgentProbe(MorpheusContext morpheus, long timeoutMs = TIMEOUT_MS, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.morpheus = morpheus
		this.timeoutMs = timeoutMs
		this.clock = clock
		this.cache = new ShortMemo<Long, Map>(CACHE_TTL_MS, CACHE_MAX, clock)
	}

	/** Only an unreported status needs the probe; a real status from Morpheus is used as is. */
	static boolean needsProbe(String guestAgentStatus) {
		return guestAgentStatus == null || guestAgentStatus == 'unknown'
	}

	/**
	 * The observedGuestAgent fact for this (fully loaded) server, or null when Morpheus reports a real status and
	 * no probe is needed.
	 *
	 * @param permitted true when the user has the guest-scan permission at full, false when not, null when unknown
	 * @param mayRun false to use cached results only
	 * @param deadline epoch millis after which no new probe starts (cached results are still used); null for none
	 */
	Map observe(ComputeServer server, Boolean permitted, boolean mayRun = true, Long deadline = null) {
		if (server == null) return null
		if (!needsProbe(ServerFacts.safe { server.guestAgentStatus?.toString() } as String)) return null
		if (!HvmScope.isHvmVm(server)) return notProbed('not an HVM VM')
		String power = ServerFacts.safe { server.powerState?.toString() }
		if (power != 'on') return notProbed(power == 'off' ? 'powered off' : "power state ${power ?: 'unknown'}".toString())
		Long id = ServerFacts.safe { server.id } as Long
		if (id == null) return notProbed('the server has no id')
		if (permitted == null) return notProbed(NO_USER)
		if (!permitted) return notProbed(NO_PERMISSION)
		Map hit = cache.get(id)
		if (hit != null) return hit + [cached: true]
		if (!mayRun) return notProbed(CACHE_ONLY)
		if (deadline != null && clock.call() > deadline) return notProbed(TIME_BUDGET)
		Map r = run(server)
		cache.put(id, r)
		return r
	}

	/**
	 * The observation for an already-scanned VM, from the probe cache only: never runs a probe and makes no
	 * service call. {@code facts} is the {@link ServerFacts#toMap} map. Same gating as {@link #observe}.
	 */
	Map cachedObservation(Map facts, boolean hvm, Boolean permitted) {
		if (facts == null) return null
		if (!needsProbe(facts.guestAgentStatus as String)) return null
		if (!hvm) return notProbed('not an HVM VM')
		String power = facts.powerState as String
		if (power != 'on') return notProbed(power == 'off' ? 'powered off' : "power state ${power ?: 'unknown'}".toString())
		Long id = facts.id as Long
		if (id == null) return notProbed('the server has no id')
		if (permitted == null) return notProbed(NO_USER)
		if (!permitted) return notProbed(NO_PERMISSION)
		Map hit = cache.get(id)
		return hit != null ? hit + [cached: true] : notProbed(CACHE_ONLY)
	}

	/**
	 * The "Check guest agent" button (0.4.0): one probe of this server for a user, with the usual rules of
	 * {@link #observe} (the caller has already checked the permission and the tenant). At most one probe per
	 * server runs at a time (a second caller gets the same answer), at most one per {@link #MIN_INTERVAL_MS} (sooner,
	 * the last result comes back with {@code recent: true}), and the guest command call runs on {@code pool}, a
	 * small bounded plugin pool, not on the unbounded RxJava I/O scheduler. Logs who asked, never the output.
	 */
	Map probeNow(ComputeServer server, Object userId, ExecutorService pool) {
		Long id = ServerFacts.safe { server?.id } as Long
		if (id == null) return observe(server, true, false)
		Map hit = cache.get(id)
		if (hit != null && hit.at != null && clock.call() - (hit.at as long) < MIN_INTERVAL_MS) return hit + [cached: true, recent: true]
		CompletableFuture<Map> mine = new CompletableFuture<Map>()
		CompletableFuture<Map> running = inFlight.putIfAbsent(id, mine)
		if (running != null) {
			try {
				return running.get(timeoutMs + 5_000L, TimeUnit.MILLISECONDS) + [cached: true]
			} catch (Throwable e) {
				return [result: NO_RESPONSE, success: null, exitCode: null, error: "no answer within ${Math.round(timeoutMs / 1000d)} s".toString()]
			}
		}
		try {
			log.info("migration-hygiene: guest agent check requested by user ${userId} on server ${id}")
			Map r
			if (!needsProbe(ServerFacts.safe { server.guestAgentStatus?.toString() } as String)) {
				r = null
			} else {
				cache.remove(id)
				r = observeOn(server, pool)
			}
			mine.complete(r)
			return r
		} catch (Throwable t) {
			mine.complete(null)
			throw t
		} finally {
			inFlight.remove(id, mine)
		}
	}

	/** observe() with a fresh run on {@code pool}. */
	private Map observeOn(ComputeServer server, ExecutorService pool) {
		Map rules = observe(server, true, false)
		if (rules?.result == NOT_PROBED && rules.reason != CACHE_ONLY) return rules
		Map r = run(server, pool)
		if (r.busy != true) cache.put(server.id, r)
		return r
	}

	/**
	 * Runs the no-op command through the guest agent. Never throws, never waits longer than the timeout. With a
	 * {@code pool}, the call runs there (fail-fast when it's full: a "busy" answer, not cached); without, on the
	 * RxJava I/O scheduler (cluster and report probes, which bound their own concurrency).
	 */
	Map run(ComputeServer server, ExecutorService pool = null) {
		boolean windows = isWindows(server)
		String command = windows ? WINDOWS_COMMAND : LINUX_COMMAND
		long started = clock.call()
		Map r
		try {
			TaskResult tr
			if (pool != null) {
				Future<TaskResult> f
				try {
					f = pool.submit({ ->
						morpheus.executeCommandOnServer(server, command, false, null, null, null, null, null, windows, false, true).blockingGet()
					} as java.util.concurrent.Callable<TaskResult>)
				} catch (RejectedExecutionException busy) {
					return [result: NO_RESPONSE, success: null, exitCode: null, error: BUSY, busy: true, command: command, at: clock.call()]
				}
				try {
					tr = f.get(timeoutMs, TimeUnit.MILLISECONDS)
				} catch (TimeoutException te) {
					f.cancel(true)
					throw te
				} catch (java.util.concurrent.ExecutionException ee) {
					throw (ee.cause ?: ee)
				}
			} else {
				// defer + subscribeOn: even an implementation that does its work before returning the Single runs on an
				// I/O thread, so the timeout always frees the calling (tab or report) thread.
				tr = Single.defer {
					morpheus.executeCommandOnServer(server, command, false, null, null, null, null, null, windows, false, true)
				}.subscribeOn(Schedulers.io()).timeout(timeoutMs, TimeUnit.MILLISECONDS).blockingGet()
			}
			boolean ok = tr?.success == Boolean.TRUE
			r = [result: ok ? RESPONDED : NO_RESPONSE, success: tr?.success, exitCode: tr?.exitCode,
			     error: ok ? null : sanitize(tr?.error ?: tr?.msg)]
		} catch (Throwable e) {
			if (isTimeout(e)) {
				r = [result: NO_RESPONSE, success: null, exitCode: null, error: "no answer within ${Math.round(timeoutMs / 1000d)} s".toString()]
			} else {
				r = [result: NO_RESPONSE, success: null, exitCode: null, error: sanitize(rootMessage(e))]
			}
		}
		r.command = command
		r.at = clock.call()
		log.info("migration-hygiene: guest agent probe on server ${server.id}: ${r.result} (${clock.call() - started} ms)")
		return r
	}

	static Map notProbed(String reason) {
		return [result: NOT_PROBED, reason: reason]
	}

	/** Windows when serverOs says so; without a serverOs, the stored osType or platform. */
	static boolean isWindows(ComputeServer s) {
		def os = ServerFacts.safe { s.serverOs }
		if (os != null) {
			String code = (ServerFacts.safe { os.code } ?: '').toString().toLowerCase()
			String name = (ServerFacts.safe { os.name } ?: '').toString().toLowerCase()
			String family = (ServerFacts.safe { os.osFamily } ?: '').toString().toLowerCase()
			String platform = (ServerFacts.safe { os.platform?.toString() } ?: '').toString().toLowerCase()
			return code.startsWith('windows') || name.startsWith('windows') || family == 'windows' || platform == 'windows'
		}
		String t = (ServerFacts.safe { s.osType } ?: '').toString().toLowerCase()
		String p = (ServerFacts.safe { s.platform } ?: '').toString().toLowerCase()
		return t == 'windows' || p == 'windows'
	}

	static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = (t.cause == t ? null : t.cause)) {
			if (t instanceof TimeoutException) return true
		}
		return false
	}

	static String rootMessage(Throwable e) {
		Throwable t = e
		while (t?.cause != null && t.cause != t) t = t.cause
		return t?.message ?: t?.getClass()?.simpleName
	}

	/** One line, printable characters only, at most 200 characters. */
	static String sanitize(Object o) {
		if (o == null) return null
		String s = o.toString().replaceAll(/[\p{Cntrl}\s]+/, ' ').trim()
		if (!s) return null
		return s.length() > ERROR_MAX ? s.substring(0, ERROR_MAX) : s
	}
}
