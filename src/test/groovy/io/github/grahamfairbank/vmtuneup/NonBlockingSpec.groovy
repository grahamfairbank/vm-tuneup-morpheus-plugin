package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousSnapshotService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import io.github.grahamfairbank.vmtuneup.core.Status
import io.reactivex.rxjava3.core.Single
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.2.0: tabs never block page loads. show() and renderTemplate() make no service calls; the routes enforce
 * permissions, tenants and POST checks, keep their time budgets, and cluster scans are single-flight.
 * Test data uses fake names only (vm-*, host-*, cluster-*).
 */
@Timeout(60)
class NonBlockingSpec extends Specification {

	VmTuneUpPlugin plugin = new VmTuneUpPlugin()

	def setup() { plugin.classLoader = getClass().classLoader }

	def cleanup() { plugin.onDestroy() }

	static <T> T withId(T model, Long id) { model.id = id; return model }

	static User user(String view = 'read', String guest = null, Long account = 1L, boolean master = false) {
		Map p = [:]
		if (view) p[VmTuneUpPlugin.VIEW_PERMISSION] = view
		if (guest) p[VmTuneUpPlugin.GUEST_SCAN_PERMISSION] = guest
		return new User(username: 'vm-viewer', permissions: p, account: withId(new Account(masterAccount: master), account))
	}

	static ComputeServer host(Long id) { withId(new ComputeServer(name: "host-${id}", computeServerType: new ComputeServerType(code: 'mvmHost'), account: withId(new Account(), 1L)), id) }

	static ComputeServer vm(Long id, Map o = [:]) {
		ComputeServer s = new ComputeServer(name: o.name ?: "vm-${id}", computeServerType: new ComputeServerType(code: o.type ?: 'morpheusVm', managed: true),
			powerState: ComputeServer.PowerState.on, guestAgentStatus: ComputeServer.GuestAgentStatus.unknown, agentInstalled: true, agentVersion: '2.0.1',
			account: withId(new Account(), (o.account ?: 1L) as Long))
		if (o.windows) s.serverOs = new OsType(code: 'windows.server.2022', name: 'windows server 2022', osFamily: 'windows', platform: PlatformType.windows)
		if (o.ga) s.guestAgentStatus = o.ga as ComputeServer.GuestAgentStatus
		s.parentServer = withId(new ComputeServerIdentityProjection(name: 'host-6', computeServerTypeCode: 'mvmHost'), 6L)
		return withId(s, id)
	}

	static ComputeServerGroup cluster(Long id = 7L, Long owner = 1L, String type = null) {
		return withId(new ComputeServerGroup(name: "cluster-${id}", owner: withId(new Account(), owner), type: type ? new ComputeServerGroupType(code: type) : null), id)
	}

	/**
	 * Services as plain proxies, not Spock stubs: Spock serializes stub calls across threads, which would make one
	 * slow call block every other thread. {@code hooks}: get / list / snapshots / exec closures to slow or count calls.
	 */
	MorpheusContext context(Map<Long, ComputeServer> byId, Map hooks = [:], List<ComputeServerGroup> groups = [cluster()]) {
		MorpheusSynchronousComputeServerService cs = [
			get : { Object... a -> Long id = a[0] as Long; if (hooks.get) hooks.get.call(id); byId[id] },
			list: { Object... a ->
				def q = a[0]
				if (hooks.list) hooks.list.call(q)
				if (q.filters.any { it.name == 'serverGroup.id' }) return byId.values().findAll { it.computeServerType?.code == 'mvmHost' } as List
				return byId.values().findAll { it.computeServerType?.code != 'mvmHost' } as List
			}] as MorpheusSynchronousComputeServerService
		MorpheusSynchronousSnapshotService ss = [
			list    : { Object... a -> if (hooks.snapshots) hooks.snapshots.call(); [] },
			listById: { Object... a -> [] }] as MorpheusSynchronousSnapshotService
		MorpheusSynchronousComputeServerGroupService gs = [
			get : { Object... a -> groups.find { it.id == (a[0] as Long) } },
			list: { Object... a -> groups }] as MorpheusSynchronousComputeServerGroupService
		MorpheusServices services = [getComputeServer: { -> cs }, getSnapshot: { -> ss }, getCluster: { -> gs }] as MorpheusServices
		return [getServices: { -> services }, getWebRequest: { -> null },
		        executeCommandOnServer: { Object... a -> hooks.exec ? hooks.exec.call(a[0]) : Single.just(new TaskResult(success: true, exitCode: '0')) }] as MorpheusContext
	}

	Map<String, String> sentHeaders = [:]

	/** Default headers: the plugin script's own fetch (X-Requested-With, same-origin). Pass [:] for a plain navigation. */
	ViewModel<Map> request(User u, Map params, String method = 'GET', Map headers = xhr()) {
		ViewModel<Map> m = new ViewModel<>()
		m.user = u
		m.request = Stub(HttpServletRequest) {
			getParameter(_) >> { String n -> params[n] as String }
			getMethod() >> method
			getHeader(_) >> { String n -> headers[n] as String }
			getServerName() >> 'manager.example.com'
		}
		m.response = Stub(HttpServletResponse) { setHeader(_, _) >> { String n, String v -> sentHeaders[n] = v } }
		return m
	}

	static Map xhr(Map extra = [:]) { [ 'X-Requested-With': 'XMLHttpRequest', 'Host': 'manager.example.com', 'Sec-Fetch-Site': 'same-origin' ] + extra }

	/** A plain top-level navigation (a link or a new tab): no X-Requested-With. */
	static Map navigation(Map extra = [:]) { [ 'Host': 'manager.example.com', 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'navigate' ] + extra }

	ScanService.ClusterJob waitFor(ScanService svc, Long id) {
		long end = System.currentTimeMillis() + 20_000L
		while (System.currentTimeMillis() < end) {
			ScanService.ClusterJob j = svc.clusters[id]
			if (j != null && !j.running) return j
			Thread.sleep(20)
		}
		throw new AssertionError('cluster scan did not finish')
	}

	// ---- show() and renderTemplate(): no service calls ------------------------------------------------

	def "show() decides from the object and the permissions only: no service call at all"() {
		given:
		MorpheusContext ctx = Mock()
		def server = new VmTuneUpServerTabProvider(plugin, ctx)
		def instance = new VmTuneUpInstanceTabProvider(plugin, ctx)
		def clusterTab = new VmTuneUpClusterTabProvider(plugin, ctx)
		def loader = new VmTuneUpUiLoaderProvider(plugin, ctx)
		Account mine = withId(new Account(), 1L)

		when:
		List<Boolean> answers = [
			server.show(vm(1L), user(), null),
			server.show(withId(new ComputeServer(), 2L), user(), null), // shallow (9.0): no type, so shown
			server.show(host(3L), user(), null),
			server.show(vm(4L, [type: 'vmwareVm']), user(), null),
			server.show(vm(1L), user('none'), null),
			instance.show(new Instance(containers: [new Workload(server: vm(1L))]), user(), null),
			instance.show(new Instance(containers: [new Workload(server: vm(4L, [type: 'vmwareVm']))]), user(), null),
			instance.show(new Instance(), user(), null), // VMs not loaded: shown
			clusterTab.show(cluster(), user(), mine),
			clusterTab.show(cluster(7L, 1L, 'kubernetes-cluster'), user(), mine),
			clusterTab.show(cluster(7L, 1L, 'mvm-cluster'), user(), mine),
			clusterTab.show(cluster(7L, 2L), user(), mine),
			loader.show(user(), mine),
			loader.show(user('none'), mine)
		]

		then:
		0 * ctx._
		answers == [true, true, false, false, false, true, false, true, true, false, true, false, true, false]
	}

	def "renderTemplate() returns a loading shell quickly, with no service call and no background work"() {
		given:
		MorpheusContext ctx = Mock()
		def server = new VmTuneUpServerTabProvider(plugin, ctx)
		def instance = new VmTuneUpInstanceTabProvider(plugin, ctx)
		def clusterTab = new VmTuneUpClusterTabProvider(plugin, ctx)

		when:
		long t0 = System.nanoTime()
		server.show(vm(1L), user(), null)
		String a = server.renderTemplate(vm(1L)).html
		String b = instance.renderTemplate(new Instance(containers: [new Workload(server: vm(1L)), new Workload(server: vm(2L))])).html
		String c = clusterTab.renderTemplate(cluster()).html
		long ms = (System.nanoTime() - t0) / 1_000_000L

		then: 'the providers may ask for the web request (renderer nonce helper), nothing else'
		_ * ctx.getWebRequest()
		0 * ctx._
		ms < 1000
		a.contains('class="vm-tuneup-tab"') && a.contains('data-vtu-kind="vm" data-vtu-id="1"') && a.contains('Loading VM Tune-Up results')
		a.contains('/plugin/vmTuneUp/vm?serverId=1')
		b.contains('data-vtu-id="1"') && b.contains('data-vtu-id="2"')
		c.contains('data-vtu-kind="cluster" data-vtu-id="7"')
		!(a + b + c).contains('<script')
		plugin.scans(ctx).workers.all().every { ((ThreadPoolExecutor) it).taskCount == 0L }
	}

	def "a fresh cached result renders inline, still without a service call, and only for the owning tenant"() {
		given:
		AtomicInteger calls = new AtomicInteger()
		boolean counting = false
		MorpheusContext ctx = context([1L: vm(1L, [name: 'vm-<b>x</b>'])], [get: { Long id -> if (counting) calls.incrementAndGet() },
			list: { q -> if (counting) calls.incrementAndGet() }, snapshots: { if (counting) calls.incrementAndGet() }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([linux: '2.0.1'])
		svc.scanVmNow(1L)
		def tab = new VmTuneUpServerTabProvider(plugin, ctx)
		counting = true
		long tasks = svc.workers.all().sum { ((ThreadPoolExecutor) it).taskCount } as long

		when:
		tab.show(vm(1L), user(), null)
		String inline = tab.renderTemplate(vm(1L)).html
		tab.show(vm(1L), user('read', null, 2L), null)
		String other = tab.renderTemplate(vm(1L)).html

		then:
		calls.get() == 0
		(svc.workers.all().sum { ((ThreadPoolExecutor) it).taskCount } as long) == tasks
		inline.contains('data-vtu-loaded="1"')
		inline.contains('vm-&lt;b&gt;x&lt;/b&gt;')
		!inline.contains('<b>x</b>')
		inline.contains('Scanned just now.')
		!other.contains('data-vtu-loaded')
	}

	// ---- permissions, tenants, POST checks ---------------------------------------------------------------

	def "routes enforce the permission levels themselves"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		expect:
		c.vm(request(user('none'), [serverId: '1'])).status == 403
		c.vm(request(new User(permissions: [:]), [serverId: '1'])).status == 403
		c.cluster(request(user('none'), [clusterId: '7'])).status == 403
		c.csv(request(user('none'), [clusterId: '7'])).status == 403
		c.vmProbe(request(user('read', 'none'), [serverId: '1'], 'POST', xhr())).status == 403
		c.vmProbe(request(user('none', 'full'), [serverId: '1'], 'POST', xhr())).status == 403
		c.status(new ViewModel<Map>(user: user('none'))).status == 403
		c.vm(request(user(), [serverId: 'abc'])).status == 400
		c.routes*.url as Set == ['/vmTuneUp/status', '/vmTuneUp/vm', '/vmTuneUp/vmProbe', '/vmTuneUp/cluster',
			'/vmTuneUp/clusterRescan', '/vmTuneUp/csv', '/vmTuneUp/clusterData', '/vmTuneUp/clusters', '/vmTuneUp/hostCpu', '/vmTuneUp/print',
			'/vmTuneUp/guestScan', '/vmTuneUp/guestScanCluster', '/vmTuneUp/setOs', '/vmTuneUp/setOsCluster'] as Set
		c.routes.findAll { !(it.url in ['/vmTuneUp/vmProbe', '/vmTuneUp/hostCpu', '/vmTuneUp/guestScan', '/vmTuneUp/guestScanCluster', '/vmTuneUp/setOs', '/vmTuneUp/setOsCluster']) }.every { it.permissions*.code == ['vm-tuneup-view'] }
		['/vmTuneUp/guestScan', '/vmTuneUp/guestScanCluster'].every { u -> c.routes.find { it.url == u }.permissions*.code == ['vm-tuneup-guest-scan'] }
		['/vmTuneUp/setOs', '/vmTuneUp/setOsCluster'].every { u -> c.routes.find { it.url == u }.permissions*.code == ['vm-tuneup-update-records'] }
		c.routes.find { it.url == '/vmTuneUp/vmProbe' }.permissions*.code == ['vm-tuneup-guest-scan']
		c.routes.find { it.url == '/vmTuneUp/hostCpu' }.permissions*.code == ['vm-tuneup-guest-scan']
	}

	def "routes check the tenant: another tenant's VM or cluster is not found, the master tenant sees all"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		expect:
		c.vm(request(user('read', null, 2L), [serverId: '1'])).status == 404
		c.vm(request(user('read', null, 9L, true), [serverId: '1'])).status == 200
		c.cluster(request(user('read', null, 2L), [clusterId: '7'])).status == 404
		c.cluster(request(user('read', null, 9L, true), [clusterId: '7'])).status == 200
	}

	def "POST routes need POST, X-Requested-With and the same origin"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = user('read', 'full')

		expect:
		c.clusterRescan(request(u, [clusterId: '7'], 'GET', xhr())).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', [Host: 'manager.example.com'])).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'https://evil.example.net']))).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'null']))).status == 403
		c.vmProbe(request(u, [serverId: '1'], 'GET', xhr())).status == 403
		c.vmProbe(request(u, [serverId: '1'], 'POST', xhr([Origin: 'https://evil.example.net']))).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'https://manager.example.com:443']))).status == 200
		c.vmProbe(request(u, [serverId: '1'], 'POST', xhr([Referer: 'https://manager.example.com/infrastructure/servers/1']))).status == 200
	}

	// ---- 0.7: guest checks, host CPU check, OS fill-in, print (VT-15) --------------------------------------

	static User userWith(Map perms, Long account = 1L, boolean master = false) {
		return new User(username: 'vm-user', permissions: perms, account: withId(new Account(masterAccount: master), account))
	}

	def "0.7 POST routes need POST, X-Requested-With and the same origin"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = userWith([(VmTuneUpPlugin.VIEW_PERMISSION): 'read', (VmTuneUpPlugin.GUEST_SCAN_PERMISSION): 'full', (VmTuneUpPlugin.UPDATE_PERMISSION): 'full'], 1L, true)

		expect:
		c.guestScan(request(u, [serverId: '1'], 'GET', xhr())).status == 403
		c.guestScanCluster(request(u, [clusterId: '7'], 'POST', [Host: 'manager.example.com'])).status == 403
		c.setOs(request(u, [serverId: '1'], 'POST', xhr([Origin: 'https://evil.example.net']))).status == 403
		c.setOsCluster(request(u, [clusterId: '7'], 'GET', xhr())).status == 403
		c.hostCpu(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'null']))).status == 403
	}

	def "0.7 routes check their permission level themselves"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		Map same = xhr([Origin: 'https://manager.example.com'])
		User viewOnly = userWith([(VmTuneUpPlugin.VIEW_PERMISSION): 'read'], 1L, true)
		User guestRead = userWith([(VmTuneUpPlugin.VIEW_PERMISSION): 'read', (VmTuneUpPlugin.GUEST_SCAN_PERMISSION): 'read'], 1L, true)
		User updateNone = userWith([(VmTuneUpPlugin.VIEW_PERMISSION): 'read', (VmTuneUpPlugin.GUEST_SCAN_PERMISSION): 'full', (VmTuneUpPlugin.UPDATE_PERMISSION): 'none'], 1L, true)

		expect:
		c.guestScan(request(viewOnly, [serverId: '1'], 'POST', same)).status == 403
		c.guestScan(request(guestRead, [serverId: '1'], 'POST', same)).status == 403
		c.guestScanCluster(request(viewOnly, [clusterId: '7'], 'POST', same)).status == 403
		c.hostCpu(request(viewOnly, [clusterId: '7'], 'POST', same)).status == 403
		c.setOs(request(updateNone, [serverId: '1'], 'POST', same)).status == 403
		c.setOsCluster(request(updateNone, [clusterId: '7'], 'POST', same)).status == 403
	}

	def "the host CPU check is for the master tenant only; guest and fill-in routes check the tenant first"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		Map same = xhr([Origin: 'https://manager.example.com'])
		Map all = [(VmTuneUpPlugin.VIEW_PERMISSION): 'read', (VmTuneUpPlugin.GUEST_SCAN_PERMISSION): 'full', (VmTuneUpPlugin.UPDATE_PERMISSION): 'full']

		expect:
		c.hostCpu(request(userWith(all, 1L, false), [clusterId: '7'], 'POST', same)).status == 403
		c.guestScan(request(userWith(all, 2L, false), [serverId: '1'], 'POST', same)).status == 404
		c.setOs(request(userWith(all, 2L, false), [serverId: '1'], 'POST', same)).status == 404
		c.guestScan(request(userWith(all, 1L, true), [serverId: 'x1'], 'POST', same)).status == 400
	}

	def "the print page never starts a scan and only shows results already scanned"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		def r = c.print(request(user('read', null, 9L, true), [clusterId: '7'], 'GET', xhr()))

		then:
		r.status == 200
		r.html.contains('Not ready yet')
		plugin.scans(ctx).cachedCluster(7L) == null
	}

	def "the fill-in proposal needs an exact catalog match and an empty field"() {
		expect:
		OsFill.proposal(null) == null
		OsFill.VERSION.matcher('22.04').matches()
		OsFill.VERSION.matcher('2022').matches()
		!OsFill.VERSION.matcher('9; rm -rf /').matches()
		!OsFill.VERSION.matcher('<script>').matches()
	}

	// ---- budgets -----------------------------------------------------------------------------------------

	def "the VM route keeps its time budget when Morpheus hangs: unknown, time budget"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L)], [get: { Long id -> Thread.sleep(10_000) }])
		plugin.scans(ctx).vmBudgetMs = 400L
		plugin.scans(ctx).lookupBudgetMs = 400L
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when: 'the tenant check (0.4.0: before the scan) runs out of time first'
		long t0 = System.currentTimeMillis()
		HTMLResponse r = c.vm(request(user(), [serverId: '1']))
		long ms = System.currentTimeMillis() - t0

		then:
		ms < 1500
		r.status == 503
		r.html.contains('Unknown: time budget')
		plugin.scans(ctx).vms.size() == 0
	}

	def "a slow part is cut off at the budget and reported, the rest of the VM is still scanned"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L)], [snapshots: { Thread.sleep(10_000) }])
		ScanService svc = plugin.scans(ctx)
		svc.vmBudgetMs = 800L
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		long t0 = System.currentTimeMillis()
		HTMLResponse r = c.vm(request(user(), [serverId: '1']))
		long ms = System.currentTimeMillis() - t0

		then:
		ms < 2000
		r.html.contains('Not finished in time: snapshots')
		r.html.contains('vm-1')
		svc.cachedVm(1L).scanStatus.startsWith('partial: time budget (snapshots')
		svc.report(svc.cachedVm(1L), false).results.find { it.checkId == 'T1-SNAPSHOTS' }.status == Status.UNKNOWN
	}

	def "the VM route never lists the fleet: with no baseline yet, the agent version check is unknown"() {
		given:
		AtomicInteger lists = new AtomicInteger()
		MorpheusContext ctx = context([1L: vm(1L)], [list: { q -> lists.incrementAndGet(); Thread.sleep(3000) }])
		ScanService svc = plugin.scans(ctx)
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		long t0 = System.currentTimeMillis()
		HTMLResponse r1 = c.vm(request(user(), [serverId: '1', fresh: '1']))
		HTMLResponse r2 = c.vm(request(user(), [serverId: '1', fresh: '1']))
		long ms = System.currentTimeMillis() - t0
		ScanService.VmEntry e = svc.cachedVm(1L)
		def agent = svc.report(e, false).results.find { it.checkId == 'T1-MORPHEUS-AGENT' }
		Thread.sleep(100)

		then: 'the baseline listing runs once, in the background, and the requests did not wait for it'
		ms < 2000
		r1.status == 200 && r2.status == 200
		e.baseline == null
		agent.status == Status.UNKNOWN
		agent.detail.contains("baseline isn't ready yet")
		svc.baselineRuns.get() == 1
		lists.get() == 1
	}

	def "the probe button runs the guest agent probe for that VM only, as a user with guest-scan full"() {
		given:
		ConcurrentLinkedQueue<Long> probed = new ConcurrentLinkedQueue<Long>()
		MorpheusContext ctx = context([1L: vm(1L), 2L: vm(2L)], [exec: { s -> probed << (s as ComputeServer).id; Single.just(new TaskResult(success: true, exitCode: '0')) }])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		HTMLResponse plain = c.vm(request(user('read', 'full'), [serverId: '1']))

		then: 'never automatic'
		probed.isEmpty()
		plain.html.contains('Check guest agent')
		!c.vm(request(user('read', 'none'), [serverId: '1', fresh: '1'])).html.contains('data-vtu-action="vm-probe"')

		when:
		HTMLResponse r = c.vmProbe(request(user('read', 'full'), [serverId: '1'], 'POST', xhr()))

		then:
		probed as List == [1L]
		r.html.contains('guest agent answered a no-op command')
	}

	// ---- background cluster scans --------------------------------------------------------------------------

	def "two requests for a cluster start one background scan; the fragment polls until it's done"() {
		given:
		AtomicInteger memberLists = new AtomicInteger()
		Map<Long, ComputeServer> db = [6L: host(6L)] + (1L..5L).collectEntries { [(it): vm(it)] }
		MorpheusContext ctx = context(db, [list: { q -> if (q.filters.any { it.name == 'serverGroup.id' }) { memberLists.incrementAndGet(); Thread.sleep(3000) } }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		long t0 = System.currentTimeMillis()
		HTMLResponse first = c.cluster(request(user(), [clusterId: '7']))
		HTMLResponse second = c.cluster(request(user(), [clusterId: '7']))
		long ms = System.currentTimeMillis() - t0
		ScanService.ClusterJob job = waitFor(svc, 7L)
		HTMLResponse done = c.cluster(request(user(), [clusterId: '7']))

		then:
		ms < 2500 // returned at once, the listing takes 3 seconds
		first.html.contains('data-vtu-poll="1"')
		second.html.contains('data-vtu-poll="1"')
		memberLists.get() == 1
		job.state == 'complete'
		job.done.get() == 5
		!done.html.contains('data-vtu-poll')
		done.html.contains('Scanned 5 of 5 HVM VMs')
		done.html.contains('href="/infrastructure/servers/1"')
		done.html.contains('Download CSV (this cluster)')
		done.html.contains('Rescan')
	}

	def "Rescan starts a new scan; the background scan probes only for a user with guest-scan full"() {
		given:
		ConcurrentLinkedQueue<Long> probed = new ConcurrentLinkedQueue<Long>()
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L), 2L: vm(2L)]
		MorpheusContext ctx = context(db, [exec: { s -> probed << (s as ComputeServer).id; Single.just(new TaskResult(success: true, exitCode: '0')) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		svc.rescanMinMs = 0L
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		c.cluster(request(user('read', 'none'), [clusterId: '7']))
		ScanService.ClusterJob plain = waitFor(svc, 7L)

		then:
		probed.isEmpty()
		!plain.probe

		when:
		c.clusterRescan(request(user('read', 'full'), [clusterId: '7'], 'POST', xhr()))
		ScanService.ClusterJob probing = waitFor(svc, 7L)

		then:
		!probing.is(plain)
		probing.probe
		probed.sort() == [1L, 2L]
	}

	def "a hung VM in a cluster scan is recorded as time budget and the scan still finishes"() {
		given:
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L), 2L: vm(2L)]
		MorpheusContext ctx = context(db, [get: { Long id -> if (id == 2L) Thread.sleep(10_000) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		svc.vmBudgetMs = 1500L

		when:
		long t0 = System.currentTimeMillis()
		svc.startOrJoin(7L, 'cluster-7', false, false)
		ScanService.ClusterJob job = waitFor(svc, 7L)

		then:
		System.currentTimeMillis() - t0 < 5000
		job.state == 'complete'
		job.results[1L].facts != null
		job.results[2L].error == 'time budget'
	}

	def "background threads are daemon, named vm-tuneup-*, and stop in onDestroy"() {
		given:
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L)])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		svc.startOrJoin(7L, 'cluster-7', false, false)
		waitFor(svc, 7L)

		when:
		List<Thread> ours = Thread.getAllStackTraces().keySet().findAll { it.name.startsWith('vm-tuneup-') } as List<Thread>
		plugin.onDestroy()

		then:
		!ours.isEmpty()
		ours.every { it.daemon }
		svc.workers.isShutdown()
	}

	// ---- caches ----------------------------------------------------------------------------------------------

	def "results expire after 10 minutes"() {
		given:
		long now = 1_000_000L
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L)])
		Workers w = new Workers()
		ScanService svc = new ScanService(ctx, w, new GuestAgentProbe(ctx), { now })
		svc.baselineValueForTest([:])

		when:
		svc.scanVmNow(1L)
		svc.startOrJoin(7L, 'cluster-7', false, false)
		waitFor(svc, 7L)

		then:
		svc.cachedVm(1L) != null
		svc.cachedCluster(7L) != null

		when:
		now += ScanService.RESULT_TTL_MS
		ScanService.ClusterJob stale = svc.clusters[7L]

		then:
		svc.cachedVm(1L) == null
		svc.cachedCluster(7L) == null
		!svc.startOrJoin(7L, 'cluster-7', false, false).is(stale) // an expired result starts a new scan

		cleanup:
		w.shutdown()
	}

	// ---- CSV -------------------------------------------------------------------------------------------------

	def "CSV: never starts a scan; 409, then 202 while running, then the rows with a safe filename, escaped and formula-neutralized"() {
		given:
		CountDownLatch release = new CountDownLatch(1)
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L, [name: '=HYPERLINK("x")']), 2L: vm(2L, [name: 'vm-a,"b"']), 3L: vm(3L, [name: '@sum']),
		                              4L: vm(4L, [name: '-1+2']), 5L: vm(5L, [name: '+cmd'])]
		List<ComputeServerGroup> groups = [withId(new ComputeServerGroup(name: 'cluster a/b', owner: withId(new Account(), 1L)), 7L)]
		MorpheusContext ctx = context(db, [list: { q -> if (q.filters.any { it.name == 'serverGroup.id' }) release.await(10, TimeUnit.SECONDS) }], groups)
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when: 'nothing scanned yet: the direct download (even from the script) never starts a scan'
		HTMLResponse none = c.csv(request(user(), [clusterId: '7']))

		then:
		none.status == 409
		none.html.contains('Open the VM Tune-Up tab')
		sentHeaders['Content-Type'].startsWith('text/plain')
		svc.clusters.isEmpty()

		when:
		c.cluster(request(user(), [clusterId: '7']))
		HTMLResponse pending = c.csv(request(user(), [clusterId: '7']))

		then:
		pending.status == 202
		pending.html.contains('still running')

		when:
		release.countDown()
		waitFor(svc, 7L)
		sentHeaders.clear()
		HTMLResponse r = c.csv(request(user(), [clusterId: '7']))
		List<String> lines = r.html.split('\r\n') as List

		then:
		r.status == 200
		sentHeaders['Content-Type'] == 'text/csv; charset=utf-8'
		sentHeaders['X-Content-Type-Options'] == 'nosniff'
		sentHeaders['Content-Disposition'] ==~ /attachment; filename="vm-tuneup-cluster-a-b-\d{4}-\d{2}-\d{2}\.csv"/
		lines[0] == Views.CSV_COLUMNS.join(',')
		lines.size() == 6
		lines.any { it.startsWith('"\'=HYPERLINK(""x"")",') }
		lines.any { it.startsWith('"vm-a,""b""",') }
		lines.any { it.startsWith("'@sum,") }
		lines.any { it.startsWith("'-1+2,") }
		lines.any { it.startsWith("'+cmd,") }
		lines.drop(1).every { it.contains(',cluster a/b,') }
	}

	def "CSV for all clusters never starts a scan: cached clusters' rows, the others listed as not scanned yet"() {
		given:
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L)]
		List<ComputeServerGroup> groups = [cluster(7L, 1L), cluster(8L, 2L), cluster(9L, 1L, 'kubernetes-cluster'), cluster(10L, 1L)]
		MorpheusContext ctx = context(db, [:], groups)
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		HTMLResponse none = c.csv(request(user(), [all: 'true']))

		then:
		none.status == 409
		none.html.contains('None of the 2 clusters are scanned yet')
		svc.clusters.isEmpty()

		when:
		c.cluster(request(user(), [clusterId: '7']))
		waitFor(svc, 7L)
		HTMLResponse r = c.csv(request(user(), [all: 'true']))
		List<String> lines = r.html.split('\r\n') as List

		then:
		svc.clusters.keySet() == [7L] as Set // all=true started nothing for cluster 10
		r.status == 200
		sentHeaders['Content-Disposition'].contains('vm-tuneup-all-clusters-')
		lines.any { it.startsWith('vm-1,cluster-7,') }
		lines.any { it.startsWith(',cluster-10,') && it.contains('not scanned yet') }
		!lines.any { it.contains('cluster-8') || it.contains('cluster-9') }
	}

	def "the global loader includes the script asset with the CSP nonce; the script only talks to our routes"() {
		given:
		com.morpheusdata.views.HandlebarsRenderer renderer = new com.morpheusdata.views.HandlebarsRenderer('renderer', getClass().classLoader)
		renderer.registerAssetHelper('vm-tuneup')
		renderer.registerNonceHelper(Stub(com.morpheusdata.core.web.MorpheusWebRequestService) { getNonceToken() >> 'n0nce' })
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = [version: '0.2.0']
		String js = new File('src/assets/javascript/vm-tuneup.js').text

		when:
		String html = renderer.renderTemplate('hbs/vtu-loader', vm).html.replace('&#x2F;', '/').replace('&#x3D;', '=')

		then:
		html.contains('<script nonce="n0nce" src="/assets/plugin/vm-tuneup/vm-tuneup.js?v=0.2.0"></script>')
		js.contains("var API = '/plugin/vmTuneUp';")
		js.contains("'X-Requested-With': 'XMLHttpRequest'")
		js.contains("'X-XSRF-TOKEN'")
		!js.contains('eval(')
		!(js =~ /fetch\((?!API|url)/) // every fetch goes to our API prefix or the CSRF page lookup
	}

	/** The cases the browser port is checked against too (tools/check-js.sh). Each: value (text, number or null), expected. */
	static List<Map> sharedCsvCases() {
		new groovy.json.JsonSlurper().parse(NonBlockingSpec.getResource('/csv-cell-cases.json')) as List<Map>
	}

	def "csv cells: the shared cases (formula guard incl. | and leading whitespace)"() {
		given:
		List<Map> cases = sharedCsvCases()

		expect:
		cases.size() >= 20
		cases.findAll { Map k -> Views.csvCell(k.value) != k.expected }.collect { Map k -> groovy.json.JsonOutput.toJson([value: k.value, got: Views.csvCell(k.value)]) } == []
	}

	// ---- 0.3.0: "Check guest agent" ------------------------------------------------------------------------

	def "Check guest agent, granted: button, hint, Linux note and the last result from the probe cache"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L)], [exec: { s -> Single.just(new TaskResult(success: true, exitCode: '0')) }])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = user('read', 'full')

		when:
		String before = c.vm(request(u, [serverId: '1'])).html
		c.vmProbe(request(u, [serverId: '1'], 'POST', xhr()))
		String after = c.vm(request(u, [serverId: '1'])).html

		then:
		before.contains('data-vtu-action="vm-probe"') && before.contains('>Check guest agent</button>')
		before.contains('Runs a harmless no-op command in the VM through the QEMU guest agent to confirm it answers. Changes nothing. ' +
			'Needs the VM Tune-Up guest-scan permission.')
		before.contains('On Linux, Morpheus runs commands through its own Morpheus agent, so this tests that agent, not the QEMU guest agent.')
		before.contains('Not checked yet.')
		!before.contains('You don&#x27;t have permission') && !before.contains('have permission.')
		after.contains('Answered just now')
	}

	def "Check guest agent, denied: no button, the hint greyed with the reason"() {
		given:
		VmTuneUpController c = new VmTuneUpController(plugin, context([1L: vm(1L)]))

		when:
		String html = c.vm(request(user('read', 'none'), [serverId: '1'])).html

		then:
		!html.contains('data-vtu-action="vm-probe"')
		html.contains('vtu-probe-hint vtu-probe-off')
		html.contains('Runs a harmless no-op command')
		html =~ /You don(&#x27;|&#39;|')t have permission\./
		!html.contains('Not checked yet.')
	}

	def "Check guest agent: no Linux note on Windows; no button when Morpheus reports a real status"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L, [windows: true]), 2L: vm(2L, [ga: ComputeServer.GuestAgentStatus.connected])])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		String windows = c.vm(request(user('read', 'full'), [serverId: '1'])).html
		String reported = c.vm(request(user('read', 'full'), [serverId: '2'])).html

		then:
		windows.contains('>Check guest agent</button>')
		!windows.contains('On Linux, Morpheus runs commands')
		!reported.contains('data-vtu-action="vm-probe"')
		reported.contains('Morpheus reports the guest agent status (connected), so no check is needed.')
	}

	def "the last probe result: answered, no answer with a short error, not checked"() {
		given:
		long now = 1_000_000_000L
		io.github.grahamfairbank.vmtuneup.core.VmReport r = io.github.grahamfairbank.vmtuneup.core.VmReport.scan(
			io.github.grahamfairbank.vmtuneup.core.VmFacts.fromMap([id: 1, name: 'vm-1', powerState: 'on', guestAgentStatus: 'unknown',
				computeServerType: [code: 'morpheusVm', managed: true], serverOs: [code: 'rocky.10.64', name: 'rocky 10'], osType: 'linux']),
			new io.github.grahamfairbank.vmtuneup.core.ScanContext(new Date(now), [:], true))
		String longError = 'x' * 150

		expect:
		Views.probeLast([result: 'responded', at: now - 120_000L], now) == 'Answered 2 min ago'
		Views.probeLast([result: 'no-response', at: now - 300_000L, error: 'no answer within 20 s'], now) == 'No answer 5 min ago: no answer within 20 s'
		Views.probeLast([result: 'no-response', at: now - 300_000L, error: longError], now).length() < 110
		Views.probeLast([result: 'no-response', at: now], now) == 'No answer just now'
		Views.probeLast(GuestAgentProbe.notProbed(GuestAgentProbe.CACHE_ONLY), now) == 'Not checked yet.'
		Views.probeLast(GuestAgentProbe.notProbed('powered off'), now) == 'Not checked: powered off.'
		Views.probeLast(null, now) == null
		Views.probeLine(r, true, [result: 'responded', at: now], now).probeLast == 'Answered just now'
		Views.probeLine(r, false, [result: 'responded', at: now], now).probeLast == null
		Views.probeLine(r, false, null, now).probeDenied == "You don't have permission."
		Views.probeLine(null, true, null, now) == [probeShown: false]
	}

	// ---- 0.3.0: clusterData and clusters (JSON for the browser-built CSV) ----------------------------------------

	def "clusterData: scanning, then done with the CSV rows; 400, 403 and another tenant's 404"() {
		given:
		CountDownLatch release = new CountDownLatch(1)
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L, [name: '=cmd']), 2L: vm(2L)]
		MorpheusContext ctx = context(db, [list: { q -> if (q.filters.any { it.name == 'serverGroup.id' }) release.await(10, TimeUnit.SECONDS) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		long t0 = System.currentTimeMillis()
		JsonResponse first = c.clusterData(request(user(), [clusterId: '7']))
		long ms = System.currentTimeMillis() - t0
		release.countDown()
		waitFor(svc, 7L)
		JsonResponse done = c.clusterData(request(user(), [clusterId: '7']))
		Map d = done.data as Map

		then: 'the first answer is immediate, while the listing still waits'
		ms < 2000
		first.status == 200
		(first.data as Map).status == 'scanning'
		(first.data as Map).finishedAt == null
		(first.data as Map).startedAt ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/
		done.status == 200
		d.status == 'done'
		d.scanned == 2 && d.total == 2
		d.finishedAt ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/
		d.columns == Views.CSV_COLUMNS
		(d.rows as List<Map>).size() == 2
		(d.rows as List<Map>).every { (it.keySet() as List) == Views.CSV_COLUMNS }
		(d.rows as List<Map>)*.name as Set == ['=cmd', 'vm-2'] as Set // raw: the browser neutralizes formulas, as Views.csvCell does
		(d.rows as List<Map>).every { it.origin == 'not-migrated' && it.cluster == 'cluster-7' }
		svc.clusters.size() == 1 // joined, not restarted

		expect:
		c.clusterData(request(user(), [clusterId: 'x'])).status == 400
		c.clusterData(request(user('none'), [clusterId: '7'])).status == 403
		c.clusterData(request(new User(permissions: [:]), [clusterId: '7'])).status == 403
		c.clusterData(request(user('read', null, 2L), [clusterId: '7'])).status == 404
		c.clusterData(request(user('read', null, 2L), [clusterId: '7'])).data.error == 'No such cluster.'
		c.clusterData(request(user(), [clusterId: '99'])).status == 404
	}

	def "clusterData: a scan that stops reports failed with its error"() {
		given:
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L)], [list: { q -> throw new IllegalStateException('boom') }])
		ScanService svc = plugin.scans(ctx)
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		c.clusterData(request(user(), [clusterId: '7']))
		waitFor(svc, 7L)
		Map d = c.clusterData(request(user(), [clusterId: '7'])).data as Map

		then:
		d.status == 'failed'
		d.error == 'the cluster scan failed inside the plugin'
		d.rows == []
	}

	def "clusters: ids and names the tenant may see, HVM-capable only; 403 without view"() {
		given:
		List<ComputeServerGroup> groups = [cluster(7L, 1L), cluster(8L, 2L), cluster(9L, 1L, 'kubernetes-cluster'), cluster(10L, 1L, 'mvm-cluster')]
		VmTuneUpController c = new VmTuneUpController(plugin, context([6L: host(6L)], [:], groups))

		expect:
		c.clusters(request(user(), [:])).status == 200
		(c.clusters(request(user(), [:])).data as Map).clusters == [[id: 7L, name: 'cluster-7'], [id: 10L, name: 'cluster-10']]
		((c.clusters(request(user('read', null, 9L, true), [:])).data as Map).clusters as List)*.id == [7L, 8L, 10L]
		c.clusters(request(user('none'), [:])).status == 403
		plugin.scans(c.morpheus).clusters.isEmpty() // listing clusters starts no scan
	}

	// ---- 0.3.0: renders still make no service calls --------------------------------------------------------

	def "with a probed VM and a scanned cluster in the caches, show() and renderTemplate() still make no service call"() {
		given:
		AtomicInteger calls = new AtomicInteger()
		boolean counting = false
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L)]
		MorpheusContext ctx = context(db, [get: { Long id -> if (counting) calls.incrementAndGet() }, list: { q -> if (counting) calls.incrementAndGet() },
			snapshots: { if (counting) calls.incrementAndGet() }, exec: { s -> if (counting) calls.incrementAndGet(); Single.just(new TaskResult(success: true, exitCode: '0')) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		c.vmProbe(request(user('read', 'full'), [serverId: '1'], 'POST', xhr()))
		c.cluster(request(user('read', 'full'), [clusterId: '7']))
		waitFor(svc, 7L)
		def vmTab = new VmTuneUpServerTabProvider(plugin, ctx)
		def clusterTab = new VmTuneUpClusterTabProvider(plugin, ctx)
		counting = true
		long tasks = svc.workers.all().sum { ((ThreadPoolExecutor) it).taskCount } as long

		when:
		vmTab.show(vm(1L), user('read', 'full'), null)
		String inline = vmTab.renderTemplate(vm(1L)).html
		clusterTab.show(cluster(), user('read', 'full'), withId(new Account(), 1L))
		String clusterShell = clusterTab.renderTemplate(cluster()).html

		then:
		calls.get() == 0
		(svc.workers.all().sum { ((ThreadPoolExecutor) it).taskCount } as long) == tasks
		inline.contains('data-vtu-loaded="1"')
		inline.contains('>Check guest agent</button>')
		inline.contains('Answered just now')
		inline.contains('About the checks: Tier 1 uses Morpheus data only')
		clusterShell.contains('data-vtu-kind="cluster" data-vtu-id="7"')
	}

	def "the cluster fragment: origin groups, the filter, the Origin column, plain titles and the CSV buttons"() {
		given:
		ComputeServer migrated = vm(2L, [name: 'vm-migrated'])
		migrated.interfaces = [new com.morpheusdata.model.ComputeServerInterface(name: 'eth0', macAddress: '00:50:56:00:00:05')]
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L), 2L: migrated])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		c.cluster(request(user(), [clusterId: '7']))
		waitFor(svc, 7L)
		String html = c.cluster(request(user(), [clusterId: '7'])).html.replace('&#x2F;', '/').replace('&#x3D;', '=')

		then:
		html.contains('<h4>By origin</h4>')
		html.contains('data-vtu-group="migrated"') && html.contains('data-vtu-group="not-migrated"') && html.contains('data-vtu-group="unknown"') && html.contains('data-vtu-group="all"')
		html.contains('<summary>How origin is decided</summary>')
		html.contains('<select class="vtu-origin-filter"')
		['>All<', '>Migrated<', '>No VMware signal<', '>Unknown origin<'].every { html.contains(it) }
		html.contains('<th>Origin</th>')
		html.contains('data-vtu-origin="migrated"') && html.contains('data-vtu-origin="not-migrated"')
		html.contains('>Migrated from VMware</td>')
		html.contains('data-vtu-action="csv-cluster"') && html.contains('data-vtu-action="csv-all"')
		html.contains('class="vtu-csv-status"')
		html.contains('direct download of the results already scanned')
		html.contains('href="/plugin/vmTuneUp/csv?clusterId=7"')
		html.contains('About the checks: Tier 1 uses Morpheus data only')
		!html.contains('>T1-')
	}

	def "the browser CSV builder is a straight port of Views.csvCell, with a BOM, CRLF and the same filename rules"() {
		given:
		String js = new File('src/assets/javascript/vm-tuneup.js').text

		expect: 'the same formula characters, quoting and number exemption as the server encoder'
		js.contains('var FORMULA = /' + Views.FORMULA + '/;')
		js.contains("if (typeof v !== 'number' && s && FORMULA.test(s)) s = \"'\" + s;")
		js.contains("if (s.indexOf(',') >= 0 || s.indexOf('\"') >= 0 || s.indexOf('\\n') >= 0 || s.indexOf('\\r') >= 0) s = '\"' + s.replace(/\"/g, '\"\"') + '\"';")
		js.contains("return out.join('\\r\\n') + '\\r\\n';")
		js.contains("new Blob(['\\ufeff', text], { type: 'text/csv;charset=utf-8' })")
		js.contains('URL.createObjectURL(') && js.contains('a.download = filename;')
		js.contains("'/clusterData?clusterId=' + id") && js.contains("getJson('/clusters')")
		js.contains('CSV_POLL_MS = 3000, CSV_CAP_MS = 15 * 60 * 1000')
		js.contains("'vm-tuneup-' + slug + '-' + new Date().toISOString().slice(0, 10) + '.csv'")
		js.contains("throw new Error('HTTP ' + r.status + ': '")
		Views.csvFilename('cluster a/b', new Date(0)) == 'vm-tuneup-cluster-a-b-1970-01-01.csv'
	}

	// ---- 0.4.0: security review remediations ----------------------------------------------------------------

	def "MH-02: a GET without the script's headers serves the cache only and starts no work"() {
		given:
		AtomicInteger reads = new AtomicInteger()
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L)], [snapshots: { reads.incrementAndGet() }, list: { q -> reads.incrementAndGet() }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User admin = user('read', 'full', 9L, true)

		when: 'plain navigations, and fetches that do not prove where they came from'
		List<Object> answers = [
			c.cluster(request(admin, [clusterId: '7'], 'GET', navigation())),
			c.cluster(request(admin, [clusterId: '7'], 'GET', [:])),
			c.cluster(request(admin, [clusterId: '7'], 'GET', ['X-Requested-With': 'XMLHttpRequest', Host: 'manager.example.com'])), // no Origin, Referer or Sec-Fetch-Site
			c.cluster(request(admin, [clusterId: '7'], 'GET', navigation([Referer: 'https://evil.example.net/x', 'Sec-Fetch-Site': 'cross-site']))),
			c.clusterData(request(admin, [clusterId: '7'], 'GET', navigation())),
			c.csv(request(admin, [clusterId: '7'], 'GET', navigation())),
			c.csv(request(admin, [all: 'true'], 'GET', navigation())),
			c.vm(request(admin, [serverId: '1'], 'GET', navigation())),
			c.vm(request(admin, [serverId: '1', fresh: '1'], 'GET', navigation()))
		]

		then: 'nothing started, nothing scanned, no guest command'
		svc.clusters.isEmpty()
		svc.vms.size() == 0
		reads.get() == 0
		svc.workers.jobs.taskCount == 0L && svc.workers.vm.taskCount == 0L && svc.workers.probe.taskCount == 0L
		(answers[0..3] as List<HTMLResponse>).every { it.status == 200 && it.html.contains(VmTuneUpController.OPEN_TAB_CLUSTER) }
		((answers[4] as JsonResponse).data as Map).status == 'not-scanned'
		((answers[4] as JsonResponse).data as Map).rows == []
		(answers[5] as HTMLResponse).status == 409
		(answers[6] as HTMLResponse).status == 409
		(answers[7] as HTMLResponse).html.contains(VmTuneUpController.OPEN_TAB_VM)
		(answers[8] as HTMLResponse).html.contains(VmTuneUpController.OPEN_TAB_VM)

		when: 'the script starts it; a plain GET then serves the cached result'
		c.cluster(request(admin, [clusterId: '7']))
		waitFor(svc, 7L)
		c.vm(request(admin, [serverId: '1']))
		String cached = c.cluster(request(admin, [clusterId: '7'], 'GET', navigation())).html
		String cachedVm = c.vm(request(admin, [serverId: '1', fresh: '1'], 'GET', navigation())).html

		then:
		svc.clusters.size() == 1
		cached.contains('Scanned 1 of 1 HVM VMs')
		cachedVm.contains('vm-1') && !cachedVm.contains(VmTuneUpController.OPEN_TAB_VM)
	}

	def "MH-02: a same-origin Referer or Sec-Fetch-Site same-origin is enough; cross-site and same-site are refused"() {
		given:
		def m = { Map h -> request(user(), [:], 'GET', h) }

		expect:
		VmTuneUpController.scriptRequest(m(xhr()))
		VmTuneUpController.scriptRequest(m(['X-Requested-With': 'XMLHttpRequest', Host: 'manager.example.com', Referer: 'https://manager.example.com/infrastructure/clusters/7']))
		VmTuneUpController.scriptRequest(m(['X-Requested-With': 'XMLHttpRequest', Host: 'manager.example.com', 'Sec-Fetch-Site': 'same-origin']))
		!VmTuneUpController.scriptRequest(m(xhr(['Sec-Fetch-Site': 'cross-site'])))
		!VmTuneUpController.scriptRequest(m(xhr(['Sec-Fetch-Site': 'same-site'])))
		!VmTuneUpController.scriptRequest(m(xhr([Referer: 'https://evil.example.net/'])))
		!VmTuneUpController.scriptRequest(m(xhr([Origin: 'null'])))
		!VmTuneUpController.scriptRequest(m(['X-Requested-With': 'XMLHttpRequest', Host: 'manager.example.com']))
		!VmTuneUpController.scriptRequest(m(navigation()))
		!VmTuneUpController.scriptRequest(new ViewModel<Map>(user: user()))
	}

	def "MH-02, MH-15: POSTs refuse cross-site Sec-Fetch-Site, and X-Forwarded-Host is not trusted"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L), 6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = user('read', 'full')

		expect:
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr(['Sec-Fetch-Site': 'cross-site']))).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr(['Sec-Fetch-Site': 'same-site']))).status == 403
		c.vmProbe(request(u, [serverId: '1'], 'POST', xhr(['Sec-Fetch-Site': 'cross-site']))).status == 403
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'https://evil.example.net', 'X-Forwarded-Host': 'evil.example.net']))).status == 403
		plugin.scans(ctx).clusters.isEmpty()
		c.clusterRescan(request(u, [clusterId: '7'], 'POST', xhr([Origin: 'https://manager.example.com']))).status == 200
	}

	def "MH-04: the VM route checks the tenant before any scan, cache read or probe"() {
		given:
		AtomicInteger gets = new AtomicInteger()
		AtomicInteger snaps = new AtomicInteger()
		MorpheusContext ctx = context([1L: vm(1L)], [get: { Long id -> if (id == 1L || id == 999L) gets.incrementAndGet() }, snapshots: { snaps.incrementAndGet() }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when: 'another tenant asks first, fresh'
		HTMLResponse other = c.vm(request(user('read', null, 2L), [serverId: '1', fresh: '1']))

		then: 'one server read for the owner, nothing else, nothing cached'
		other.status == 404
		gets.get() == 1
		snaps.get() == 0
		svc.vms.size() == 0

		when: 'the owner: the record already read is reused, so the scan reads the server no second time'
		gets.set(0)
		c.vmOwners.clear()
		HTMLResponse mine = c.vm(request(user(), [serverId: '1']))

		then:
		mine.status == 200
		gets.get() == 1
		svc.vms.size() == 1

		when: 'another tenant again, with the result cached: the remembered owner refuses it with no read at all'
		gets.set(0)
		snaps.set(0)
		HTMLResponse again = c.vm(request(user('read', null, 2L), [serverId: '1']))

		then:
		again.status == 404
		!again.html.contains('vm-1')
		gets.get() == 0 && snaps.get() == 0
		c.vm(request(user('read', null, 2L), [serverId: '999'])).status == 404 // missing and foreign look the same
	}

	def "MH-03: cluster views, JSON and CSV show a non-master viewer only their tenant's VMs; probes stay in the starter's tenant"() {
		given:
		ConcurrentLinkedQueue<Long> probed = new ConcurrentLinkedQueue<Long>()
		Map<Long, ComputeServer> db = [6L: host(6L), 1L: vm(1L, [name: 'vm-mine']), 2L: vm(2L, [name: 'vm-foreign', account: 2L])]
		MorpheusContext ctx = context(db, [exec: { s -> probed << (s as ComputeServer).id; Single.just(new TaskResult(success: true, exitCode: '0')) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		svc.rescanMinMs = 0L
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User tenant = user('read', 'full', 1L)
		User master = user('read', 'full', 9L, true)

		when: 'a tenant user with guest-scan starts the scan'
		c.cluster(request(tenant, [clusterId: '7']))
		ScanService.ClusterJob job = waitFor(svc, 7L)
		String html = c.cluster(request(tenant, [clusterId: '7'])).html
		Map data = c.clusterData(request(tenant, [clusterId: '7'])).data as Map
		String csv = c.csv(request(tenant, [clusterId: '7'])).html
		String masterHtml = c.cluster(request(master, [clusterId: '7'])).html

		then:
		job.results.keySet() == [1L, 2L] as Set
		job.results[2L].accountId == 2L
		probed as List == [1L] // not the other tenant's VM
		html.contains('vm-mine') && !html.contains('vm-foreign')
		(data.rows as List<Map>)*.name == ['vm-mine']
		csv.contains('vm-mine') && !csv.contains('vm-foreign')
		masterHtml.contains('vm-mine') && masterHtml.contains('vm-foreign')

		when: 'a master-tenant user rescans: every VM is probed'
		probed.clear()
		svc.probe.cache.clear()
		c.clusterRescan(request(master, [clusterId: '7'], 'POST', xhr()))
		waitFor(svc, 7L)

		then:
		probed.sort() == [1L, 2L]
	}

	def "MH-03: a scan started without a starter never probes"() {
		given:
		ConcurrentLinkedQueue<Long> probed = new ConcurrentLinkedQueue<Long>()
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L)], [exec: { s -> probed << (s as ComputeServer).id; Single.just(new TaskResult(success: true)) }])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])

		when:
		svc.startOrJoin(7L, 'cluster-7', true, false)
		waitFor(svc, 7L)

		then:
		probed.isEmpty()
	}

	def "MH-05: a viewer answer is read once and expires after 3 seconds"() {
		given:
		long now = 1_000_000L
		ViewerGrants g = new ViewerGrants({ now })

		when:
		g.record('server', 1L, user('read', 'full'))
		Map first = g.viewer('server', 1L)
		Map second = g.viewer('server', 1L)
		g.record('server', 2L, user())
		now += ViewerGrants.TTL_MS
		Map late = g.viewer('server', 2L)

		then:
		first.guestScan == true && first.accountId == 1L
		second == null
		late == null
		ViewerGrants.TTL_MS == 3_000L
	}

	def "MH-05: a second render without its own show() gets the loading shell"() {
		given:
		MorpheusContext ctx = context([1L: vm(1L)])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		svc.scanVmNow(1L)
		def tab = new VmTuneUpServerTabProvider(plugin, ctx)

		when:
		tab.show(vm(1L), user('read', 'full'), null)
		String a = tab.renderTemplate(vm(1L)).html
		String b = tab.renderTemplate(vm(1L)).html

		then:
		a.contains('data-vtu-loaded="1"')
		!b.contains('data-vtu-loaded')
	}

	def "MH-06: Check guest agent is single-flight per VM and runs at most once a minute"() {
		given:
		long now = 1_000_000L
		AtomicInteger execs = new AtomicInteger()
		CountDownLatch inside = new CountDownLatch(1)
		CountDownLatch release = new CountDownLatch(1)
		MorpheusContext ctx = context([1L: vm(1L)], [exec: { s ->
			Single.fromCallable { execs.incrementAndGet(); inside.countDown(); release.await(10, TimeUnit.SECONDS); new TaskResult(success: true, exitCode: '0') }
		}])
		GuestAgentProbe probe = new GuestAgentProbe(ctx, 5_000L, { now })
		Workers w = new Workers()

		when: 'two at once'
		Map a = null, b = null
		Thread t1 = Thread.start { a = probe.probeNow(vm(1L), 5L, w.probe) }
		inside.await(5, TimeUnit.SECONDS)
		Thread t2 = Thread.start { b = probe.probeNow(vm(1L), 6L, w.probe) }
		Thread.sleep(200)
		release.countDown()
		t1.join(5000); t2.join(5000)

		then:
		execs.get() == 1
		a.result == 'responded' && b.result == 'responded'

		when: 'again within the minute: the last result, no new command'
		now += 30_000L
		Map recent = probe.probeNow(vm(1L), 5L, w.probe)

		then:
		execs.get() == 1
		recent.recent == true && recent.result == 'responded'

		when: 'after the minute'
		now += GuestAgentProbe.MIN_INTERVAL_MS
		probe.probeNow(vm(1L), 5L, w.probe)

		then:
		execs.get() == 2

		cleanup:
		w.shutdown()
	}

	def "MH-06: the probe call runs on the bounded probe pool; a full pool answers busy and caches nothing"() {
		given:
		AtomicInteger execs = new AtomicInteger()
		MorpheusContext ctx = context([1L: vm(1L)], [exec: { s -> execs.incrementAndGet(); Single.just(new TaskResult(success: true)) }])
		GuestAgentProbe probe = new GuestAgentProbe(ctx)
		java.util.concurrent.ExecutorService full = Workers.bounded('test-probe', 1, 1)
		CountDownLatch hold = new CountDownLatch(1)
		2.times { full.submit { hold.await(10, TimeUnit.SECONDS) } }

		when:
		Map r = probe.probeNow(vm(1L), 5L, full)

		then:
		r.busy == true
		r.error == GuestAgentProbe.BUSY
		execs.get() == 0
		probe.cache.get(1L) == null
		Workers.PROBE_THREADS <= 4 && Workers.PROBE_QUEUE <= 4

		cleanup:
		hold.countDown()
		full.shutdownNow()
	}

	def "MH-06: the route answers a second check within the minute with the last result and when it ran"() {
		given:
		AtomicInteger execs = new AtomicInteger()
		MorpheusContext ctx = context([1L: vm(1L)], [exec: { s -> execs.incrementAndGet(); Single.just(new TaskResult(success: true, exitCode: '0')) }])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = withId(user('read', 'full'), 42L)

		when:
		c.vmProbe(request(u, [serverId: '1'], 'POST', xhr()))
		String second = c.vmProbe(request(u, [serverId: '1'], 'POST', xhr())).html

		then:
		execs.get() == 1
		second.contains('Answered just now')
		second =~ /Checked \d+ s ago; you can check again in \d+ s\./
	}

	def "MH-07: a user may run 3 scans at once; a full job queue refuses new scans without storing them"() {
		given:
		CountDownLatch release = new CountDownLatch(1)
		List<ComputeServerGroup> groups = (1L..20L).collect { cluster(it) }
		MorpheusContext ctx = context([6L: host(6L)], [list: { q -> if (q.filters.any { it.name == 'serverGroup.id' }) release.await(20, TimeUnit.SECONDS) }], groups)
		ScanService svc = plugin.scans(ctx)
		ScanService.Starter alice = new ScanService.Starter(5L, 1L, false)

		when: 'one user'
		List<ScanService.ClusterJob> mine = (1L..4L).collect { svc.startOrJoin(it, "cluster-${it}", false, false, alice) }

		then:
		mine[0..2].every { it.running }
		mine[3].state == 'failed' && mine[3].error == ScanService.TOO_MANY_FOR_USER
		!svc.clusters.containsKey(4L)
		svc.startOrJoin(1L, 'cluster-1', false, false, alice).is(mine[0]) // joining doesn't count

		when: 'everyone: 2 running + 8 queued, then refused'
		List<ScanService.ClusterJob> more = (11L..18L).collect { svc.startOrJoin(it, "cluster-${it}", false, false, new ScanService.Starter(100L + it, 1L, false)) }

		then:
		more[0..6].every { it.running }
		more[7].state == 'failed' && more[7].error == ScanService.TOO_MANY
		!svc.clusters.containsKey(18L)
		svc.clusters.size() == 10

		when: 'the fragment says so'
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		String html = c.cluster(request(user(), [clusterId: '19'])).html

		then:
		html.contains('The scan stopped: too many scans in progress, try again shortly.')
		!svc.clusters.containsKey(19L)

		cleanup:
		release.countDown()
	}

	def "MH-01: the CSV body is served only when the response takes the CSV headers; else 406"() {
		given:
		MorpheusContext ctx = context([6L: host(6L), 1L: vm(1L, [name: '<b>vm</b>'])])
		ScanService svc = plugin.scans(ctx)
		svc.baselineValueForTest([:])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		c.cluster(request(user(), [clusterId: '7']))
		waitFor(svc, 7L)
		ViewModel<Map> noResponse = request(user(), [clusterId: '7'])
		noResponse.response = null
		ViewModel<Map> refusing = request(user(), [clusterId: '7'])
		refusing.response = Stub(HttpServletResponse) { setHeader(_, _) >> { throw new IllegalStateException('committed') } }
		ViewModel<Map> overridden = request(user(), [clusterId: '7'])
		overridden.response = Stub(HttpServletResponse) { getContentType() >> 'text/html;charset=UTF-8' }

		expect:
		[noResponse, refusing, overridden].collect { c.csv(it) }.every { HTMLResponse r -> r.status == 406 && !r.html.contains('<b>') && r.html.contains('Download CSV buttons') }
		c.csv(request(user(), [clusterId: '7'])).status == 200
	}

	def "MH-01: a plain-text note is HTML-escaped when its type can't be set"() {
		given:
		ViewModel<Map> m = new ViewModel<>()

		expect:
		VmTuneUpController.text(m, 409, 'cluster <img src=x>').html == 'cluster &lt;img src=x&gt;'
		VmTuneUpController.text(request(user(), [:]), 409, 'cluster <img src=x>').html == 'cluster <img src=x>'
	}

	def "MH-15 and charset: JSON is served as UTF-8 with nosniff; status says only ok and the version"() {
		given:
		MorpheusContext ctx = context([6L: host(6L)])
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)

		when:
		sentHeaders.clear()
		JsonResponse data = c.clusters(request(user(), [:]))
		Map headers = new LinkedHashMap(sentHeaders)
		JsonResponse st = c.status(request(user(), [:]))

		then:
		headers['Content-Type'] == 'application/json; charset=utf-8'
		headers['X-Content-Type-Options'] == 'nosniff'
		VmTuneUpController.safe { data.contentType } in [null, 'application/json; charset=utf-8'] // the field exists on newer Plugin APIs
		st.status == 200
		(st.data as Map).keySet() == ['ok', 'version'] as Set
		c.status(request(user('none'), [:])).status == 403
	}

	def "MH-09: the script injects only the plugin's own same-origin HTML fragments"() {
		given:
		String js = new File('src/assets/javascript/vm-tuneup.js').text
		String inject = js.substring(js.indexOf('function inject(slot, res)'), js.indexOf('function fail(slot)'))

		expect:
		js.contains("var MARKER = '<div class=\"vm-tuneup';")
		js.contains('function fragmentOk(res, origin)')
		js.contains('res.redirected') && js.contains('new URL(res.url, origin).origin') && js.contains('text\\/html')
		inject.indexOf('fragmentOk(res, window.location.origin)') > 0
		inject.indexOf('fragmentOk(res, window.location.origin)') < inject.indexOf('slot.innerHTML = res.text')
		js.contains('Reload the page and sign in again.')
		// every GET carries X-Requested-With, which the routes need to start a scan
		(js =~ /fetch\(API \+ path, \{ credentials: 'same-origin', headers: \{[^}]*'X-Requested-With': 'XMLHttpRequest'/).count == 2
		// our own fragments do start with the marker
		VmTuneUpController.message(404, 'x').html.startsWith('<div class="vm-tuneup')
	}
}
