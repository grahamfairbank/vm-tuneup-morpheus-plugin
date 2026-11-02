package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousSnapshotService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.model.projection.StorageControllerIdentityProjection
import io.github.grahamfairbank.vmtuneup.core.Fixtures
import io.github.grahamfairbank.vmtuneup.core.ScanContext
import io.github.grahamfairbank.vmtuneup.core.Tier1Rules
import io.github.grahamfairbank.vmtuneup.core.VmFacts
import io.github.grahamfairbank.vmtuneup.core.VmReport
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/** The Morpheus side: ComputeServer to facts, the tab and report rendering, all with stubbed services. */
class MorpheusAdapterSpec extends Specification {

	VmTuneUpPlugin plugin = new VmTuneUpPlugin()

	def setup() {
		// Providers build their renderer from the plugin's class loader, as on the appliance.
		plugin.classLoader = getClass().classLoader
	}

	static User viewer() { new User(username: 'alice', permissions: [(VmTuneUpPlugin.VIEW_PERMISSION): 'read']) }

	static <T> T withId(T model, Long id) { model.id = id; return model }

	/** A plugin-API copy of the vm-ide-import fixture: discovered, IDE boot disk, E1000, VMware MAC. */
	static ComputeServer ideImport(Long id = 28L, String name = 'vm-ide-import') {
		StorageController ide = withId(new StorageController(name: 'IDE 0', type: new StorageControllerType(code: 'kvm-ide', name: 'IDE')), 108L)
		StorageVolume root = withId(new StorageVolume(name: 'root', rootVolume: true, diskMode: 'ide', type: new StorageVolumeType(code: 'standard'),
			maxStorage: 26843545600L, usedStorage: 7879139328L,
			controller: withId(new StorageControllerIdentityProjection(name: 'IDE 0'), 108L)), 84L)
		ComputeServerInterface nic = new ComputeServerInterface(name: 'net0', macAddress: '00:0c:29:00:00:05',
			type: new ComputeServerInterfaceType(code: 'e1000-kvm', name: 'E1000'))
		ComputeServer s = new ComputeServer(name: name, computeServerType: new ComputeServerType(code: 'kvmUnmanaged', managed: false),
			powerState: ComputeServer.PowerState.on, guestAgentStatus: ComputeServer.GuestAgentStatus.unknown, osType: null,
			agentInstalled: false, uefi: true, secureBoot: false, tpm: false, volumes: [root], controllers: [ide], interfaces: [nic],
			account: new Account(id: 1L))
		s.config = groovy.json.JsonOutput.toJson([netBoot: false, bootToBios: false])
		s.parentServer = withId(new ComputeServerIdentityProjection(name: 'host-c', computeServerTypeCode: 'mvmHost'), 6L)
		return withId(s, id)
	}

	static ComputeServer migratedWindows(Long id = 40L) {
		StorageController scsi = withId(new StorageController(name: 'SCSI 0', type: new StorageControllerType(code: 'kvm-virtio-scsi')), 3L)
		StorageVolume root = withId(new StorageVolume(name: 'root', rootVolume: true, diskMode: 'scsi', type: new StorageVolumeType(code: 'standard'),
			maxStorage: 96636764160L, usedStorage: 0L, controller: withId(new StorageControllerIdentityProjection(name: 'SCSI 0'), 3L)), 1L)
		ComputeServer s = new ComputeServer(name: 'vm-901-migrated-windows', computeServerType: new ComputeServerType(code: 'morpheusVm', managed: true),
			powerState: ComputeServer.PowerState.off, guestAgentStatus: ComputeServer.GuestAgentStatus.configured, osType: null, platform: null,
			serverOs: new OsType(code: 'windows.server.2022.std.desktop', name: 'windows server 2022 standard desktop', osFamily: 'windows',
				platform: PlatformType.windows), uefi: true, volumes: [root], controllers: [scsi],
			interfaces: [new ComputeServerInterface(name: 'eth0', macAddress: '00:50:56:00:00:21', type: new ComputeServerInterfaceType(code: 'virtio'))])
		s.config = groovy.json.JsonOutput.toJson([noAgent: true, uefi: true])
		s.snapshots = [withId(new SnapshotIdentityProjection(), 501L)]
		return withId(s, id)
	}

	MorpheusContext context(Map<Long, ComputeServer> byId, Closure<List> lister = { [] }, List<Snapshot> snaps = []) {
		MorpheusSynchronousComputeServerService cs = Stub {
			get(_) >> { Long id -> byId[id] }
			list(_) >> { args -> lister.call(args[0]) }
		}
		MorpheusSynchronousSnapshotService ss = Stub {
			list(_) >> snaps
			listById(_) >> { args -> snaps.findAll { it.id in (args[0] as Collection) } }
		}
		MorpheusServices services = Stub { getComputeServer() >> cs; getSnapshot() >> ss }
		return Stub(MorpheusContext) { getServices() >> services }
	}

	def "the adapter gives the same check results as the REST fixture"() {
		given:
		ServerFacts adapter = new ServerFacts(context([28L: ideImport()]))
		ScanContext ctx = Fixtures.ctx()

		when:
		VmFacts fromModel = adapter.load(ideImport())
		VmFacts fromRest = Fixtures.vm('vm-ide-import')

		then:
		fromModel.serverType == 'kvmUnmanaged'
		!fromModel.managed
		fromModel.disks[0].controllerType == 'kvm-ide'
		fromModel.interfaces[0].typeCode == 'e1000-kvm'
		fromModel.snapshots == [] // query worked and returned none
		Tier1Rules.evaluate(fromModel, ctx).collect { [it.checkId, it.status, it.severity] } ==
			Tier1Rules.evaluate(fromRest, ctx).collect { [it.checkId, it.status, it.severity] }
	}

	def "the adapter keys the OS off serverOs and reads snapshot dates through the snapshot service"() {
		given:
		Snapshot old = withId(new Snapshot(name: 'before-migration', snapshotCreated: VmFacts.date('2026-03-15T10:00:00.000Z')), 501L)
		old.server = migratedWindows()
		MorpheusContext ctx = context([40L: migratedWindows()], { [] }, [old])

		when:
		VmReport r = VmReport.scan(new ServerFacts(ctx).load(migratedWindows()), Fixtures.ctx())

		then:
		r.facts.windows
		r.facts.snapshots*.name == ['before-migration']
		r.findings*.checkId.containsAll(['T1-OS-TYPE-MISMATCH', 'T1-SNAPSHOTS', 'T1-VMWARE-MAC'])
		r.vmwareOrigin
	}

	def "snapshots are unknown when the snapshot service can't be read"() {
		given:
		MorpheusSynchronousComputeServerService cs = Stub { get(_) >> ideImport() }
		MorpheusSynchronousSnapshotService ss = Stub { list(_) >> { throw new RuntimeException('no') } }
		MorpheusServices services = Stub { getComputeServer() >> cs; getSnapshot() >> ss }
		MorpheusContext ctx = Stub(MorpheusContext) { getServices() >> services }

		expect:
		new ServerFacts(ctx).load(ideImport()).snapshots == null
	}

	def "snapshot rows that belong to another server are ignored"() {
		given:
		Snapshot other = withId(new Snapshot(name: 'not-mine', snapshotCreated: new Date()), 9L)
		other.server = withId(new ComputeServer(name: 'other'), 999L)

		expect:
		new ServerFacts(context([28L: ideImport()], { [] }, [other])).load(ideImport()).snapshots == []
	}

	def "an unmanaged type counts as HVM only on an HVM host"() {
		given:
		ComputeServer onHvm = ideImport()
		onHvm.computeServerType = new ComputeServerType(code: 'unmanagedWindows')
		ComputeServer loose = new ComputeServer(computeServerType: new ComputeServerType(code: 'unmanagedWindows'))

		expect:
		HvmScope.isHvmVm(onHvm)
		!HvmScope.isHvmVm(loose)
		!HvmScope.isHvmVm(null)
	}

	com.morpheusdata.views.ViewModel<Map> get(Map params, User u = new User(permissions: viewer().permissions, account: new Account(id: 1L))) {
		com.morpheusdata.views.ViewModel<Map> m = new com.morpheusdata.views.ViewModel<>()
		m.user = u
		// The plugin script's own fetch: only such a GET may start a scan (0.4.0).
		Map headers = ['X-Requested-With': 'XMLHttpRequest', 'Sec-Fetch-Site': 'same-origin', Host: 'manager.example.com']
		m.request = Stub(jakarta.servlet.http.HttpServletRequest) { getParameter(_) >> { String n -> params[n] as String }; getMethod() >> 'GET'; getHeader(_) >> { String n -> headers[n] } }
		return m
	}

	def cleanup() { plugin.onDestroy() }

	def "VM route renders grade, findings by severity and the collapsed unknown section, all escaped"() {
		given:
		ComputeServer evil = ideImport(28L, '<script>alert(1)</script>')
		VmTuneUpController c = new VmTuneUpController(plugin, context([28L: evil], { [evil] }))

		when:
		String html = c.vm(get([serverId: '28'])).html

		then:
		html.contains('vtu-grade-f')
		html.indexOf('Disk on an emulated SATA or IDE bus') < html.indexOf('Network adapter is not VirtIO')
		html.contains('If you leave it:')
		html.contains('If you fix it:')
		html.contains('Do first, in this order:')
		html.contains('Recommendation')
		html.contains('Consideration')
		html.contains('https://docs.morpheusdata.com/en/9.1/')
		html.contains('<details>')
		html.contains('Unknown or not evaluated (6)')
		!html.contains('Not evaluated (pending verification)')  // no check is pending verification since SOURCE-TWIN was dropped (0.6)
		html.contains('VMware origin')
		!html.contains('<script>alert(1)</script>')
		html.contains('&lt;script&gt;')
		!html.contains('<script')
	}

	def "VM route says unknown when the server can't be read"() {
		given:
		MorpheusSynchronousComputeServerService cs = Stub { get(_) >> { throw new RuntimeException('db down') }; list(_) >> { throw new RuntimeException('db down') } }
		MorpheusContext ctx = Stub(MorpheusContext) { getServices() >> Stub(MorpheusServices) { getComputeServer() >> cs } }

		when:
		String html = new VmTuneUpController(plugin, ctx).vm(get([serverId: '28'])).html

		then:
		html.contains('Unknown: the server could not be read.')
	}

	def "instance tab: one slot per VM, each filled from the VM route"() {
		given:
		MorpheusContext ctx = context([28L: ideImport(), 40L: migratedWindows()])
		VmTuneUpInstanceTabProvider tab = new VmTuneUpInstanceTabProvider(plugin, ctx)
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		Instance inst = new Instance(name: 'app', containers: [new Workload(server: withId(new ComputeServer(), 28L)), new Workload(server: withId(new ComputeServer(), 40L))])
		ComputeServer w = migratedWindows()

		when:
		String shell = tab.renderTemplate(inst).html

		then:
		tab.show(inst, viewer(), null)
		shell.contains('data-vtu-id="28"') && shell.contains('data-vtu-id="40"')
		c.vm(get([serverId: '28'])).html.contains('vm-ide-import')
		// migratedWindows has no account: only the master tenant sees it
		c.vm(get([serverId: '40'], new User(permissions: viewer().permissions, account: new Account(id: 9L, masterAccount: true)))).html.contains('vm-901-migrated-windows')
	}

	def "cluster route: rollup counts and the worst VMs from the background scan"() {
		given:
		ComputeServer host = withId(new ComputeServer(name: 'host-c', computeServerType: new ComputeServerType(code: 'mvmHost')), 6L)
		ComputeServer clean = withId(new ComputeServer(name: 'vm-clean', computeServerType: new ComputeServerType(code: 'morpheusVm', managed: true),
			powerState: ComputeServer.PowerState.on, guestAgentStatus: ComputeServer.GuestAgentStatus.connected, account: new Account(id: 1L)), 50L) // 0.4.0: tenant users see their own tenant's VMs only
		Map<Long, ComputeServer> db = [6L: host, 28L: ideImport(), 50L: clean]
		MorpheusContext base = context(db, { q -> q.filters.any { it.name == 'serverGroup.id' } ? [host] : [ideImport(), clean] })
		com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService groups = Stub {
			get(7L) >> withId(new ComputeServerGroup(name: 'cluster-a', owner: new Account(id: 1L)), 7L)
		}
		MorpheusServices services = Stub { getComputeServer() >> base.services.computeServer; getSnapshot() >> base.services.snapshot; getCluster() >> groups }
		MorpheusContext ctx = Stub(MorpheusContext) { getServices() >> services }
		VmTuneUpController c = new VmTuneUpController(plugin, ctx)
		User u = new User(permissions: viewer().permissions, account: new Account(id: 1L))

		when:
		c.cluster(get([clusterId: '7'], u))
		long end = System.currentTimeMillis() + 20_000L
		while (plugin.scans(ctx).clusters[7L].running && System.currentTimeMillis() < end) Thread.sleep(20)
		String html = c.cluster(get([clusterId: '7'], u)).html

		then:
		html.contains('2 HVM VMs scanned')
		html.indexOf('vm-ide-import') < html.indexOf('vm-clean')
		html.contains('Disk on an emulated SATA or IDE bus (critical)')
		!html.contains('>T1-')
		html.contains('title="Check ID T1-DISK-BUS">DISK-BUS<')
		html.contains('Findings per check')
	}

	def "report: header summary plus one flat main row per VM, worst first, rendered escaped"() {
		given:
		List<ReportResultRow> stored = []
		List<ReportResult.Status> statuses = []
		MorpheusReportService reports = Stub {
			updateReportResultStatus(_, _) >> { ReportResult rr, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { ReportResult rr, Collection c -> stored.addAll(c); Single.just(true) }
		}
		ComputeServer bad = ideImport(28L, 'vm-<b>bad</b>')
		MorpheusContext base = context([28L: bad, 40L: migratedWindows()], { q -> [bad, migratedWindows()] })
		MorpheusContext ctx = Stub(MorpheusContext) { getReport() >> reports; getServices() >> base.services }
		VmTuneUpReportProvider report = new VmTuneUpReportProvider(plugin, ctx)

		when:
		report.process(new ReportResult(createdBy: viewer()))
		Map<String, List<ReportResultRow>> bySection = stored.groupBy { it.section }
		String html = report.renderTemplate(new ReportResult(), bySection).html

		then:
		statuses == [ReportResult.Status.generating, ReportResult.Status.ready]
		bySection.header.size() == 1
		bySection.header[0].dataMap.vmCount == 2
		bySection.main*.dataMap*.name == ['vm-<b>bad</b>', 'vm-901-migrated-windows']
		bySection.main.every { r -> r.dataMap.values().every { it instanceof String || it instanceof Number } }
		bySection.main[0].dataMap.topFindings.startsWith('Disk on an emulated SATA or IDE bus (critical)')
		bySection.main[0].dataMap.checkIds.startsWith('DISK-BUS; ')
		html.contains('vm-&lt;b&gt;bad&lt;/b&gt;')
		!html.contains('<b>bad</b>')
		html.contains('VMs (worst first)')
	}
}
