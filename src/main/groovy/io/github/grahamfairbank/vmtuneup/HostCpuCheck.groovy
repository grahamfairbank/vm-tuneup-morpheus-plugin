package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j
import io.github.grahamfairbank.vmtuneup.core.hostcpu.HostCpu
import io.github.grahamfairbank.vmtuneup.core.hostcpu.HostCpuReconciler
import io.github.grahamfairbank.vmtuneup.core.hostcpu.HostCpuResult
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The host CPU check (0.6): runs two fixed, read-only libvirt commands on every HVM host of a cluster, through
 * Morpheus (the documented way plugins run commands on a hypervisor host), and reconciles the cluster's CPU setting
 * ({@code config.cpuModel}) with what each host can offer ({@link HostCpuReconciler}).
 *
 * Only on request (a button, guest-scan permission, master tenant), one run per cluster at a time, cached one hour.
 * The commands are constants; nothing from the request or from Morpheus data is put into them. Output is capped.
 */
@Slf4j
class HostCpuCheck {

	static final long CACHE_MS = 60L * 60L * 1000L
	static final long HOST_TIMEOUT_MS = 90_000L
	static final int MAX_OUTPUT = 4 * 1024 * 1024

	/** libvirt refuses domain capabilities on a read-only connection: run as root, or through sudo -n (never prompts). */
	static final String CAPABILITIES = 'if [ "$(id -u)" = 0 ]; then virsh -c qemu:///system capabilities; ' +
		'else sudo -n virsh -c qemu:///system capabilities; fi'
	static final String DOM_CAPABILITIES = 'if [ "$(id -u)" = 0 ]; then virsh -c qemu:///system domcapabilities --virttype kvm; ' +
		'else sudo -n virsh -c qemu:///system domcapabilities --virttype kvm; fi'

	static final class Entry {
		Long clusterId
		volatile boolean running = true
		long startedAt
		volatile Long at
		String cpuModel
		HostCpuResult result
		String error
		Long userId
	}

	final MorpheusContext morpheus
	final Closure<Long> clock
	final Map<Long, Entry> entries = new ConcurrentHashMap<Long, Entry>()

	HostCpuCheck(MorpheusContext morpheus, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.morpheus = morpheus
		this.clock = clock
	}

	/** The last result for the cluster, or the run in progress; null when there is none or it's older than an hour. */
	Entry cached(Long clusterId) {
		Entry e = entries.get(clusterId)
		if (e == null) return null
		if (!e.running && e.at != null && clock.call() - e.at > CACHE_MS) {
			entries.remove(clusterId, e)
			return null
		}
		// VT-09: a run that never finished stops counting as running after 15 minutes.
		if (e.running && clock.call() - e.startedAt > 15L * 60L * 1000L) { e.running = false; e.at = clock.call(); e.error = e.error ?: 'no result in 15 minutes' }
		return e
	}

	/** Starts a run in the background unless one is running or fresh (under a minute old). Returns the entry. */
	synchronized Entry start(Long clusterId, Long userId, ExecutorService pool) {
		Entry old = cached(clusterId)
		if (old != null && (old.running || (old.at != null && clock.call() - old.at < 60_000L))) return old
		Entry e = new Entry(clusterId: clusterId, startedAt: clock.call(), userId: userId)
		entries.put(clusterId, e)
		try {
			pool.submit({ -> run(e) } as Runnable)
		} catch (RejectedExecutionException busy) {
			e.error = 'too many checks in progress, try again shortly'
			e.running = false
			e.at = clock.call()
		}
		return e
	}

	void run(Entry e) {
		try {
			ComputeServerGroup g = morpheus.services.cluster.get(e.clusterId)
			e.cpuModel = ServerFacts.safe { g?.getConfigMap()?.get('cpuModel') } as String
			List<ComputeServer> members = morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', e.clusterId)) ?: []
			List<ComputeServer> hosts = members.findAll { HvmScope.isHvmHostType(HvmScope.typeCode(it)) }
				.sort { (ServerFacts.safe { it.name } ?: '') as String }
			List<HostCpu> read = hosts.collect { ComputeServer h -> readHost(h) }
			e.result = HostCpuReconciler.reconcile(e.cpuModel, read)
			log.info("vm-tuneup: host CPU check on cluster ${e.clusterId} by user ${e.userId}: ${hosts.size()} hosts, ${e.result.status}")
		} catch (Throwable t) {
			e.error = GuestAgentProbe.sanitize(GuestAgentProbe.rootMessage(t))
			log.warn("vm-tuneup: host CPU check on cluster ${e.clusterId} failed: ${t}")
		} finally {
			e.at = clock.call()
			e.running = false
		}
	}

	HostCpu readHost(ComputeServer host) {
		String label = (ServerFacts.safe { host.name } ?: "host ${host.id}") as String
		try {
			String caps = exec(host, CAPABILITIES)
			String dom = exec(host, DOM_CAPABILITIES)
			return HostCpu.parse(label, caps, dom)
		} catch (Throwable t) {
			return HostCpu.failed(label, GuestAgentProbe.sanitize(GuestAgentProbe.rootMessage(t)))
		}
	}

	/** One command on the host through Morpheus, with our own timeout; stdout comes back in data (else output). */
	String exec(ComputeServer host, String command) {
		TaskResult tr = Single.defer { morpheus.executeCommandOnServer(host, command) }
			.subscribeOn(Schedulers.io()).timeout(HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS).blockingGet()
		String out = (tr?.data ?: tr?.output ?: '') as String
		if (tr?.success != Boolean.TRUE && !out.contains('<')) {
			throw new IllegalStateException((tr?.error ?: tr?.msg ?: "exit code ${tr?.exitCode ?: '?'}") as String)
		}
		return out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) : out
	}
}
