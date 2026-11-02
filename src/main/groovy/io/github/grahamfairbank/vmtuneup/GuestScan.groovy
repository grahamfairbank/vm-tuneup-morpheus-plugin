package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.GuestFacts
import io.github.grahamfairbank.vmtuneup.core.GuestScripts

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The Tier 2 guest scan (0.7): runs ONE fixed, read-only script inside the VM ({@link GuestScripts}), the same way
 * Morpheus runs guest commands (Windows through the QEMU guest agent, Linux through the Morpheus agent), and parses
 * its output ({@link GuestFacts}). On request only, guest-scan permission, one run per VM at a time, cached one hour.
 * The command is a constant; nothing from the request or from Morpheus data goes into it. The output is never
 * logged (it can hold internal host names); only the script's hash, the server and the user are.
 */
@Slf4j
class GuestScan {

	static final long CACHE_MS = 60L * 60L * 1000L
	static final long TIMEOUT_MS = 120_000L

	static final class Entry {
		Long serverId
		volatile boolean running = true
		long startedAt
		volatile Long at
		GuestFacts facts
		String error
		String os
	}

	final MorpheusContext morpheus
	final Closure<Long> clock
	final Map<Long, Entry> entries = new ConcurrentHashMap<Long, Entry>()
	/** Cluster batches: cluster id to [total, done, running, startedAt, userId]. */
	final Map<Long, Map> batches = new ConcurrentHashMap<Long, Map>()

	GuestScan(MorpheusContext morpheus, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.morpheus = morpheus
		this.clock = clock
	}

	Entry cached(Long serverId) {
		Entry e = serverId == null ? null : entries.get(serverId)
		if (e == null) return null
		if (!e.running && e.at != null && clock.call() - e.at > CACHE_MS) {
			entries.remove(serverId, e)
			return null
		}
		if (stale(e)) finish(e, null, 'no answer from the guest in time')
		return e
	}

	/** Parsed facts of a finished, clean scan, else null. */
	GuestFacts facts(Long serverId) {
		Entry e = cached(serverId)
		return (e != null && !e.running && e.facts?.ok) ? e.facts : null
	}

	/**
	 * Starts the scan in the background (the VM tab's button) and returns at once: a guest scan can take longer than
	 * Morpheus lets a plugin request run. The tab polls until the entry is no longer running.
	 */
	synchronized Entry start(ComputeServer server, Long userId, ExecutorService pool) {
		Entry old = cached(server.id)
		if (old != null && old.running) return old
		if (old != null && old.at != null && clock.call() - old.at < 60_000L) return old
		Entry e = new Entry(serverId: server.id, startedAt: clock.call())
		entries.put(server.id, e)
		try {
			pool.submit({ -> run(e, server, userId) } as Runnable)
		} catch (RejectedExecutionException busy) {
			finish(e, null, 'too many guest scans in progress, try again shortly')
		}
		return e
	}

	/** A run that never reported back (a stuck command) stops counting as running after twice the timeout. */
	boolean stale(Entry e) {
		return e != null && e.running && clock.call() - e.startedAt > 2 * TIMEOUT_MS + 30_000L
	}

	/** At most this many VMs per cluster batch; the rest can be scanned from their own tabs. */
	static final int MAX_BATCH = 200
	/** A batch stops starting new VMs after this long. */
	static final long BATCH_DEADLINE_MS = 90L * 60L * 1000L
	/** Per-VM timeout inside a batch: shorter than a single scan, so non-answering VMs don't hold the batch. */
	static final long BATCH_VM_TIMEOUT_MS = 60_000L

	/**
	 * Scans the listed VMs one after another on the batch pool (the cluster tab's button): at most {@link #MAX_BATCH},
	 * skipping VMs with a fresh result, stopping new scans after {@link #BATCH_DEADLINE_MS}. Single VM scans use their
	 * own pool, so a batch never blocks them.
	 */
	synchronized Map startBatch(Long clusterId, List<ComputeServer> servers, Long userId, ExecutorService pool) {
		Map b = batch(clusterId)
		if (b != null && b.running) return b
		List<ComputeServer> todo = servers.findAll { ComputeServer s -> Entry old = cached(s.id); old == null || (!old.running && !old.facts?.ok) }
		boolean capped = todo.size() > MAX_BATCH
		if (capped) todo = todo.take(MAX_BATCH)
		b = new ConcurrentHashMap([total: todo.size(), done: 0, running: true, startedAt: clock.call(), userId: userId ?: 0L])
		if (capped) b.note = "only the first ${MAX_BATCH} VMs; open a VM's tab for the others".toString()
		batches.put(clusterId, b)
		Map batch = b
		List<ComputeServer> list = todo
		try {
			pool.submit({ ->
				try {
					for (ComputeServer s : list) {
						if (clock.call() - (batch.startedAt as long) > BATCH_DEADLINE_MS) { batch.note = 'stopped after 90 minutes'; break }
						Entry e
						synchronized (this) {
							Entry old = cached(s.id)
							if (old != null && old.running) { e = null } else { e = new Entry(serverId: s.id, startedAt: clock.call()); entries.put(s.id, e) }
						}
						if (e != null) run(e, s, userId, BATCH_VM_TIMEOUT_MS)
						batch.done = ((batch.done ?: 0) as int) + 1
						Thread.sleep(250L)
					}
				} finally {
					batch.running = false
					batch.at = clock.call()
				}
			} as Runnable)
		} catch (RejectedExecutionException busy) {
			b.running = false
			b.at = clock.call()
			b.error = 'too many guest scans in progress, try again shortly'
		}
		return b
	}

	Map batch(Long clusterId) {
		Map b = batches.get(clusterId)
		if (b == null) return null
		// A batch thread that died without its finally block (it can't, but be safe) stops counting after the deadline.
		if (b.running && clock.call() - (b.startedAt as long) > BATCH_DEADLINE_MS + 10L * 60L * 1000L) { b.running = false; b.at = clock.call(); b.error = 'stopped (no progress)' }
		if (!b.running && b.at != null && clock.call() - (b.at as long) > CACHE_MS) {
			batches.remove(clusterId, b)
			return null
		}
		return b
	}

	void run(Entry e, ComputeServer server, Long userId, long timeoutMs = TIMEOUT_MS) {
		String os = GuestAgentProbe.isWindows(server) ? 'windows' : osOf(server)
		e.os = os
		try {
			if (os == null) {
				// Morpheus doesn't know the OS: try Windows through the guest agent first, then Linux.
				GuestFacts w = exec(server, 'windows', timeoutMs)
				if (w.ok) { finish(e, w, null); e.os = 'windows'; return }
				GuestFacts l = exec(server, 'linux', timeoutMs)
				e.os = 'linux'
				finish(e, l, l.ok ? null : "the guest didn't answer the scan (${w.problem ?: w.status}; ${l.problem ?: l.status})".toString())
				return
			}
			GuestFacts g = exec(server, os, timeoutMs)
			finish(e, g, g.ok ? null : (g.problem ?: g.status.toString()))
		} catch (Throwable t) {
			finish(e, null, GuestAgentProbe.sanitize(GuestAgentProbe.rootMessage(t)))
		} finally {
			log.info("vm-tuneup: guest scan on server ${server.id} by user ${userId} (script ${os == 'linux' ? GuestScripts.LINUX_SHA256 : GuestScripts.WINDOWS_SHA256}): ${e.facts?.ok ? 'ok' : 'failed'}")
		}
	}

	static String osOf(ComputeServer s) {
		String t = (ServerFacts.safe { s.serverOs?.platform?.toString() } ?: ServerFacts.safe { s.osType } ?: ServerFacts.safe { s.platform } ?: '').toString().toLowerCase()
		if (!t) return null
		return t == 'windows' ? 'windows' : 'linux'
	}

	GuestFacts exec(ComputeServer server, String os, long timeoutMs = TIMEOUT_MS) {
		String command = GuestScripts.commandFor(os)
		boolean windows = os == 'windows'
		TaskResult tr
		try {
			tr = io.reactivex.rxjava3.core.Single.defer {
				// 11-argument form: the Windows flag sits in the noProfile slot, as in GuestAgentProbe; guestExec = true.
				morpheus.executeCommandOnServer(server, command, false, null, null, null, null, null, windows, false, true)
			}.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io()).timeout(timeoutMs, TimeUnit.MILLISECONDS).blockingGet()
		} catch (Throwable t) {
			if (GuestAgentProbe.isTimeout(t)) return GuestFacts.parse('', os)
			throw t
		}
		String out = (tr?.data ?: tr?.output ?: '') as String
		return GuestFacts.parse(out, os)
	}

	void finish(Entry e, GuestFacts g, String error) {
		e.facts = g
		e.error = error
		e.at = clock.call()
		e.running = false
	}
}
