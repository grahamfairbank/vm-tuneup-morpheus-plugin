package io.github.grahamfairbank.vmtuneup

import groovy.util.logging.Slf4j

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The plugin's threads (0.2.0). All daemon threads named vm-tuneup-*, all shut down in onDestroy.
 *
 * <ul>
 *   <li>{@code io}: service calls made for a web request, each with a timeout ({@link #call}). Bounded: when it's
 *   full, a call fails at once instead of queueing, so a request thread never waits beyond its budget.</li>
 *   <li>{@code vm}: one-VM scans for the VM route; the request thread waits on it with the VM budget.</li>
 *   <li>{@code bgIo}: the same for background scans, kept apart so they can't starve requests.</li>
 *   <li>{@code jobs}: one coordinator per cluster scan; 2 at a time, at most {@link #JOB_QUEUE} waiting (0.4.0), so a
 *   burst of scan starts is refused instead of queueing for hours.</li>
 *   <li>{@code probe}: the guest agent probe call of the "Check guest agent" button (0.4.0), bounded and fail-fast,
 *   instead of the unbounded RxJava I/O scheduler.</li>
 *   <li>{@code scan}: the per-VM work of cluster scans (1 worker, so a scan never crowds the manager).</li>
 *   <li>{@code baseline}: the agent baseline refresh (one thread).</li>
 * </ul>
 */
@Slf4j
class Workers {

	static final int SCAN_WORKERS = 1
	/** Cluster scans waiting for one of the 2 job workers (0.4.0). */
	static final int JOB_QUEUE = 8
	static final int PROBE_THREADS = 4
	static final int PROBE_QUEUE = 4

	final ExecutorService io
	final ExecutorService vm
	final ExecutorService bgIo
	final ExecutorService jobs
	final ExecutorService scan
	final ExecutorService baseline
	final ExecutorService probe

	Workers() {
		io = bounded('io', 6, 24)
		vm = bounded('vm', 4, 8)
		bgIo = bounded('bg-io', 4, 64)
		jobs = bounded('job', 2, JOB_QUEUE)
		scan = fixed('scan', SCAN_WORKERS)
		baseline = fixed('baseline', 1)
		probe = bounded('probe', PROBE_THREADS, PROBE_QUEUE)
	}

	static ThreadFactory factory(String name) {
		AtomicInteger n = new AtomicInteger()
		return { Runnable r ->
			Thread t = new Thread(r, "vm-tuneup-${name}-${n.incrementAndGet()}".toString())
			t.daemon = true
			return t
		} as ThreadFactory
	}

	static ExecutorService fixed(String name, int threads) {
		ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), factory(name))
		p.allowCoreThreadTimeOut(true)
		return p
	}

	static ExecutorService bounded(String name, int threads, int queue) {
		ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queue), factory(name))
		p.allowCoreThreadTimeOut(true)
		return p
	}

	List<ExecutorService> all() { [io, vm, bgIo, jobs, scan, baseline, probe] }

	void shutdown() {
		all().each { ExecutorService e ->
			try { e.shutdownNow() } catch (Throwable ignored) {}
		}
	}

	boolean isShutdown() { all().every { it.isShutdown() } }

	/** Thrown by a budgeted read that ran out of time (or found the pool full). */
	static class BudgetExceeded extends RuntimeException {
		BudgetExceeded(String part) { super("time budget: ${part}".toString(), null, false, false) }
	}

	/**
	 * Runs {@code c} on {@code pool} and waits at most {@code timeoutMs}. On timeout the task is cancelled
	 * (interrupted) and BudgetExceeded is thrown; the task's own exception is rethrown as is.
	 */
	static <T> T call(ExecutorService pool, long timeoutMs, String part, Closure<T> c) {
		if (timeoutMs <= 0L) throw new BudgetExceeded(part)
		Future<T> f
		try {
			f = pool.submit({ -> c.call() } as Callable<T>)
		} catch (RejectedExecutionException e) {
			throw new BudgetExceeded(part)
		}
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS)
		} catch (TimeoutException e) {
			f.cancel(true)
			throw new BudgetExceeded(part)
		} catch (InterruptedException e) {
			f.cancel(true)
			Thread.currentThread().interrupt()
			throw new BudgetExceeded(part)
		} catch (java.util.concurrent.ExecutionException e) {
			throw (e.cause ?: e)
		}
	}

	/**
	 * A reader for {@link ServerFacts}: every service read goes through {@code pool} and must finish by
	 * {@code deadline}. Parts that don't are added to {@code skipped}.
	 */
	static Closure<Object> budgetedReader(ExecutorService pool, long deadline, Collection<String> skipped, Closure<Long> clock = { System.currentTimeMillis() }) {
		return { String part, Closure c ->
			try {
				return Workers.call(pool, deadline - clock.call(), part, c)
			} catch (BudgetExceeded e) {
				synchronized (skipped) { if (!skipped.contains(part)) skipped.add(part) }
				throw e
			}
		}
	}
}
