package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.admin.MorpheusSynchronousAdminService
import com.morpheusdata.core.synchronous.admin.MorpheusSynchronousUserService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import io.github.grahamfairbank.migrationhygiene.core.Status
import io.github.grahamfairbank.migrationhygiene.core.VmReport
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The guest agent probe with a stubbed MorpheusContext: no guest is ever reached. */
class GuestAgentProbeSpec extends Specification {

	MigrationHygienePlugin plugin = new MigrationHygienePlugin()

	def setup() {
		plugin.classLoader = getClass().classLoader
	}

	/** Every command the stub was asked to run, with its rpc, sudo and guestExec flags. */
	ConcurrentLinkedQueue<Map> calls = new ConcurrentLinkedQueue<Map>()

	static User user(String guestScan) {
		Map p = [(MigrationHygienePlugin.VIEW_PERMISSION): 'read']
		if (guestScan) p[MigrationHygienePlugin.GUEST_SCAN_PERMISSION] = guestScan
		return new User(username: 'vm-viewer', permissions: p)
	}

	static ComputeServer vm(Long id, Map opts = [:]) {
		ComputeServer s = new ComputeServer(name: "vm-${id}", computeServerType: new ComputeServerType(code: opts.type ?: 'morpheusVm', managed: true),
			powerState: opts.power ?: ComputeServer.PowerState.on, guestAgentStatus: opts.ga ?: ComputeServer.GuestAgentStatus.unknown)
		if (opts.windows) s.serverOs = new OsType(code: 'windows.server.2022', name: 'windows server 2022', osFamily: 'windows', platform: PlatformType.windows)
		s.id = id
		return s
	}

	MorpheusContext context(Map<Long, ComputeServer> byId, Closure<Single<TaskResult>> exec, MorpheusReportService reports = null,
	                        MorpheusSynchronousUserService users = null) {
		MorpheusSynchronousComputeServerService cs = Stub {
			get(_) >> { Long id -> byId[id] }
			list(_) >> { args -> byId.values() as List }
		}
		MorpheusSynchronousAdminService admin = Stub { getUser() >> users }
		MorpheusServices services = Stub { getComputeServer() >> cs; getAdmin() >> admin }
		return Stub(MorpheusContext) {
			getServices() >> services
			getReport() >> reports
			executeCommandOnServer(_, _, _, _, _, _, _, _, _, _, _) >> { args ->
				calls << [server: (args[0] as ComputeServer).id, command: args[1], rpc: args[2], user: args[3], password: args[4],
				          sudo: args[9], guestExec: args[10]]
				return exec.call(args[0])
			}
		}
	}

	static Single<TaskResult> ok() { Single.just(new TaskResult(success: true, exitCode: '0', output: 'secret output')) }

	def "a powered-on VM with an unreported status is probed with a fixed no-op through the guest agent"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }))

		when:
		Map linux = probe.observe(vm(1L), true)
		Map windows = probe.observe(vm(2L, [windows: true]), true)

		then:
		linux.result == 'responded'
		linux.success == true
		linux.exitCode == '0'
		linux.error == null
		!linux.toString().contains('secret output')
		windows.result == 'responded'
		calls*.command == ['true', 'cmd /c exit 0']
		calls.every { it.rpc == false && it.user == null && it.password == null && it.sudo == false && it.guestExec == true }
	}

	def "a failed command is no-response, with a short sanitized error"() {
		given:
		String long_ = 'agent\nnot\tavailable ' + ('x' * 500)
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { Single.just(new TaskResult(success: false, exitCode: '1', error: long_)) }))

		when:
		Map r = probe.observe(vm(1L), true)

		then:
		r.result == 'no-response'
		r.success == false
		r.exitCode == '1'
		r.error.startsWith('agent not available x')
		r.error.length() == 200
		!r.error.contains('\n')
	}

	def "an exception from Morpheus is no-response"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { Single.error(new IllegalStateException('guest exec unsupported')) }))

		expect:
		probe.observe(vm(1L), true).findAll { it.key != 'at' } == [result: 'no-response', success: null, exitCode: null, error: 'guest exec unsupported', command: 'true']
	}

	@Timeout(10)
	def "a probe that never answers times out"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { Single.never() }), 300L)

		when:
		Map r = probe.observe(vm(1L), true)

		then:
		r.result == 'no-response'
		r.error == 'no answer within 0 s'
	}

	@Timeout(10)
	def "a call that hangs before returning its Single can't block the caller"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { Thread.sleep(5_000); ok() }), 300L)
		long t0 = System.currentTimeMillis()

		when:
		Map r = probe.observe(vm(1L), true)

		then:
		r.result == 'no-response'
		System.currentTimeMillis() - t0 < 3_000
	}

	def "no probe without the guest-scan permission at full, or when the user is unknown"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }))

		expect:
		probe.observe(vm(1L), MigrationHygienePlugin.canGuestScan(user(level))) == [result: 'not-probed', reason: 'guest-scan permission not granted']
		probe.observe(vm(1L), null) == [result: 'not-probed', reason: 'the user could not be determined']
		calls.isEmpty()

		where:
		level << [null, 'none', 'read']
	}

	def "never probes powered-off or non-HVM servers, or when Morpheus reports a real status"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }))

		expect:
		probe.observe(vm(1L, [power: ComputeServer.PowerState.off]), true) == [result: 'not-probed', reason: 'powered off']
		probe.observe(vm(2L, [type: 'vmwareVm']), true) == [result: 'not-probed', reason: 'not an HVM VM']
		probe.observe(vm(3L, [ga: ComputeServer.GuestAgentStatus.disconnected]), true) == null
		probe.observe(vm(4L, [ga: ComputeServer.GuestAgentStatus.connected]), true) == null
		calls.isEmpty()
	}

	def "results are cached per server for 10 minutes"() {
		given:
		long now = 1_000_000L
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }), 1_000L, { now })

		when:
		Map first = probe.observe(vm(1L), true)
		Map second = probe.observe(vm(1L), true)
		Map cachedOnly = probe.observe(vm(1L), true, false)
		Map otherCacheOnly = probe.observe(vm(2L), true, false)
		now += GuestAgentProbe.CACHE_TTL_MS
		Map third = probe.observe(vm(1L), true)

		then:
		first.result == 'responded' && !first.cached
		second.result == 'responded' && second.cached
		cachedOnly.cached
		otherCacheOnly.result == 'not-probed'
		third.result == 'responded' && !third.cached
		calls.size() == 2
	}

	def "a cached result isn't shown to a user without the permission"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }))
		probe.observe(vm(1L), true)

		expect:
		probe.observe(vm(1L), false).result == 'not-probed'
	}

	def "cached observation: from the probe cache only, never runs a probe, same rules as observe()"() {
		given:
		GuestAgentProbe probe = new GuestAgentProbe(context([:], { ok() }))
		Map on = [id: 1L, powerState: 'on', guestAgentStatus: 'unknown']

		when:
		Map before = probe.cachedObservation(on, true, true)
		probe.observe(vm(1L), true)
		calls.clear()

		then:
		before.result == 'not-probed'
		before.reason.contains('Check guest agent')
		probe.cachedObservation(on, true, true).result == 'responded'
		probe.cachedObservation(on, true, true).cached
		probe.cachedObservation(on, true, false).reason == GuestAgentProbe.NO_PERMISSION
		probe.cachedObservation(on, true, null).reason == GuestAgentProbe.NO_USER
		probe.cachedObservation(on + [powerState: 'off'], true, true).reason == 'powered off'
		probe.cachedObservation(on, false, true).reason == 'not an HVM VM'
		probe.cachedObservation(on + [guestAgentStatus: 'connected'], true, true) == null
		calls.isEmpty()
	}

	def "report: probes as the user who ran it, at most 4 at a time"() {
		given:
		AtomicInteger running = new AtomicInteger()
		AtomicInteger peak = new AtomicInteger()
		Map<Long, ComputeServer> db = (1L..10L).collectEntries { [(it): vm(it)] }
		List<ReportResultRow> stored = Collections.synchronizedList([])
		MorpheusReportService reports = Stub {
			updateReportResultStatus(_, _) >> Completable.complete()
			appendResultRows(_, _) >> { ReportResult rr, Collection c -> stored.addAll(c); Single.just(true) }
		}
		MorpheusContext ctx = context(db, { s ->
			Single.fromCallable {
				peak.accumulateAndGet(running.incrementAndGet()) { a, b -> Math.max(a, b) }
				Thread.sleep(100)
				running.decrementAndGet()
				new TaskResult(success: true, exitCode: '0')
			}
		}, reports)
		MigrationHygieneReportProvider report = new MigrationHygieneReportProvider(plugin, ctx)

		when:
		report.process(new ReportResult(createdBy: user('full')))

		then:
		calls.size() == 10
		peak.get() <= 4
		peak.get() > 1
		stored.findAll { it.section == ReportResultRow.SECTION_MAIN }.every { it.dataMap.guestAgentProbe == 'responded' }
	}

	def "report: no probes when the user who ran it lacks the permission or can't be determined"() {
		given:
		MorpheusSynchronousUserService users = Stub { get(_) >> new User(permissions: [:]) }
		MorpheusContext ctx = context([1L: vm(1L)], { ok() }, null, users)

		expect:
		MigrationHygieneReportProvider.guestScanGrant(ctx, new ReportResult(createdBy: user('none'))) == false
		MigrationHygieneReportProvider.guestScanGrant(ctx, new ReportResult(createdBy: user('full'))) == true
		MigrationHygieneReportProvider.guestScanGrant(ctx, new ReportResult()) == null
		MigrationHygieneReportProvider.guestScanGrant(ctx, new ReportResult(createdBy: withId(new User(), 5L))) == null
	}

	def "report: a user without permissions on createdBy is read by id"() {
		given:
		MorpheusSynchronousUserService users = Stub { get(5L) >> user('full') }
		MorpheusContext ctx = context([:], { ok() }, null, users)

		expect:
		MigrationHygieneReportProvider.guestScanGrant(ctx, new ReportResult(createdBy: withId(new User(), 5L))) == true
	}

	def "fleet scan: probes stop at the time budget"() {
		given:
		Map<Long, ComputeServer> db = (1L..6L).collectEntries { [(it): vm(it)] }
		MorpheusContext ctx = context(db, { s -> Single.fromCallable { Thread.sleep(300); new TaskResult(success: true) } })
		FleetScan scan = new FleetScan(ctx)
		FleetScan.ProbePolicy policy = new FleetScan.ProbePolicy(permitted: true, concurrency: 1, budgetMs: 100L)

		when:
		List<VmReport> reports = scan.scan(db.values() as List, null, new GuestAgentProbe(ctx), policy)

		then:
		reports.count { it.facts.gaProbe == 'responded' } == 1
		reports.findAll { it.facts.gaProbe == 'not-probed' }*.facts*.gaProbeReason.unique() == ['time budget']
		reports.find { it.facts.gaProbe == 'not-probed' }.undecided.find { it.checkId == 'T1-GA-STATUS' }.status == Status.UNKNOWN
	}

	static <T> T withId(T model, Long id) { model.id = id; return model }
}
