package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusReportService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.User
import com.morpheusdata.model.Workload
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.Renderer
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class PluginSpec extends Specification {

	HandlebarsRenderer renderer = new HandlebarsRenderer('renderer', getClass().classLoader)
	VmTuneUpPlugin plugin = new VmTuneUpPlugin()

	static User viewer() { new User(username: 'alice', permissions: [(VmTuneUpPlugin.VIEW_PERMISSION): 'read']) }
	static User nobody() { new User(username: 'bob', permissions: [(VmTuneUpPlugin.VIEW_PERMISSION): 'none']) }

	static ComputeServer server(Long id, String type, Long account = 1L) {
		ComputeServer s = new ComputeServer(name: "vm-${id}", computeServerType: type ? new ComputeServerType(code: type) : null,
			account: new Account(id: account))
		s.id = id
		return s
	}

	MorpheusContext context(Map<Long, ComputeServer> byId, List<ComputeServer> listed = []) {
		MorpheusSynchronousComputeServerService cs = Stub {
			get(_) >> { Long id -> byId[id] }
			list(_) >> listed
		}
		return Stub(MorpheusContext) { getServices() >> Stub(MorpheusServices) { getComputeServer() >> cs } }
	}

	def "initialize registers five providers, two permissions, a controller and its own renderer"() {
		when:
		plugin.initialize()

		then:
		plugin.code == 'vm-tuneup'
		plugin.name == 'VM Tune-Up'
		plugin.providers*.code as Set == ['vm-tuneup-report', 'vm-tuneup-instance-tab',
			'vm-tuneup-server-tab', 'vm-tuneup-cluster-tab', 'vm-tuneup-ui-loader'] as Set
		plugin.permissions*.code == ['vm-tuneup-view', 'vm-tuneup-guest-scan']
		plugin.hasCustomRender()
		plugin.controllers.size() == 1
		plugin.controllers[0] instanceof VmTuneUpController
	}

	def "permission levels"() {
		expect:
		VmTuneUpPlugin.canView(new User(permissions: perms)) == view
		VmTuneUpPlugin.canGuestScan(new User(permissions: perms)) == scan

		where:
		perms                                                                     | view  | scan
		[:]                                                                       | false | false
		['vm-tuneup-view': 'none']                                        | false | false
		['vm-tuneup-view': 'read']                                        | true  | false
		['vm-tuneup-view': 'full']                                        | true  | false
		['vm-tuneup-guest-scan': 'full']                                  | false | true
		['vm-tuneup-guest-scan': 'none', 'vm-tuneup-view': 'read'] | true  | false
	}

	def "null user can't view"() {
		expect:
		!VmTuneUpPlugin.canView(null)
	}

	def "status route requires the view permission and checks the level itself"() {
		given:
		VmTuneUpController c = new VmTuneUpController(plugin, Stub(MorpheusContext))

		expect:
		c.routes[0].url == '/vmTuneUp/status'
		c.routes[0].permissions*.code == ['vm-tuneup-view']
		c.status(new com.morpheusdata.views.ViewModel<Map>(user: viewer())).status == 200
		(c.status(new com.morpheusdata.views.ViewModel<Map>(user: viewer())).data as Map).keySet() == ['ok', 'version'] as Set // nothing else about internals
		c.status(new com.morpheusdata.views.ViewModel<Map>(user: nobody())).status == 403
		c.status(new com.morpheusdata.views.ViewModel<Map>()).status == 403
	}

	def "server tab: HVM VM types and servers without a type (9.0 shallow) are shown, from the object alone"() {
		given:
		VmTuneUpServerTabProvider tab = new VmTuneUpServerTabProvider(plugin, Mock(MorpheusContext))

		expect:
		tab.show(server(1L, 'morpheusVm'), viewer(), null)
		tab.show(server(2L, 'kvmUnmanaged'), viewer(), null)
		tab.show(server(5L, 'morpheusWindowsVm'), viewer(), null)
		tab.show(server(6L, 'mvmVm'), viewer(), null)
		tab.show(server(7L, null), viewer(), null)
		!tab.show(server(3L, 'mvmHost'), viewer(), null)
		!tab.show(server(4L, 'vmwareVm'), viewer(), null)
		!tab.show(server(1L, 'morpheusVm'), nobody(), null)
		!tab.show(server(null, 'mvmVm'), viewer(), null)
		!tab.show(null, viewer(), null)
	}

	def "instance tab: shown when one of its VMs may be an HVM VM, or when that can't be told"() {
		given:
		VmTuneUpInstanceTabProvider tab = new VmTuneUpInstanceTabProvider(plugin, Mock(MorpheusContext))
		Instance hvm = new Instance(name: 'app', containers: [new Workload(server: server(1L, 'morpheusVm'))])
		Instance other = new Instance(name: 'other', containers: [new Workload(server: server(5L, 'vmwareVm'))])

		expect:
		tab.show(hvm, viewer(), null)
		!tab.show(other, viewer(), null)
		tab.show(new Instance(name: 'empty'), viewer(), null)
		!tab.show(hvm, nobody(), null)
	}

	def "cluster tab: clusters the user's tenant owns, or any for the master tenant; never container platforms"() {
		given:
		ComputeServerGroup cluster = new ComputeServerGroup(name: 'cluster-a', owner: new Account(id: 1L))
		cluster.id = 7L
		ComputeServerGroup kube = new ComputeServerGroup(name: 'cluster-k', owner: new Account(id: 1L), type: new com.morpheusdata.model.ComputeServerGroupType(code: 'kubernetes-cluster'))
		kube.id = 8L
		VmTuneUpClusterTabProvider tab = new VmTuneUpClusterTabProvider(plugin, Mock(MorpheusContext))

		expect:
		tab.show(cluster, viewer(), new Account(id: 1L))
		tab.show(cluster, viewer(), new Account(id: 9L, masterAccount: true))
		!tab.show(cluster, viewer(), new Account(id: 2L))
		!tab.show(cluster, nobody(), new Account(id: 1L))
		!tab.show(kube, viewer(), new Account(id: 1L))
	}

	def "report run on an empty fleet stores the summary row and ends ready; report is master-tenant only"() {
		given:
		List<ReportResult.Status> statuses = []
		int rows = 0
		MorpheusReportService reports = Stub {
			updateReportResultStatus(_, _) >> { ReportResult rr, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { ReportResult rr, Collection c -> rows += c.size(); Single.just(true) }
		}
		MorpheusContext base = context([:], [])
		MorpheusContext ctx = Stub(MorpheusContext) { getReport() >> reports; getServices() >> base.services }
		VmTuneUpReportProvider report = new VmTuneUpReportProvider(plugin, ctx)

		when:
		report.process(new ReportResult(createdBy: viewer()))

		then:
		statuses == [ReportResult.Status.generating, ReportResult.Status.ready]
		rows == 1
		report.masterOnly
		report.code == 'vm-tuneup-report'
	}

	def "MH-13: a report run by a user without the view permission stores only a note and scans nothing"() {
		given:
		List<ReportResult.Status> statuses = []
		List<com.morpheusdata.model.ReportResultRow> stored = []
		MorpheusReportService reports = Stub {
			updateReportResultStatus(_, _) >> { ReportResult rr, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { ReportResult rr, Collection c -> stored.addAll(c); Single.just(true) }
		}
		MorpheusServices services = Mock()
		MorpheusContext ctx = Stub(MorpheusContext) { getReport() >> reports; getServices() >> services }
		plugin.classLoader = getClass().classLoader
		VmTuneUpReportProvider report = new VmTuneUpReportProvider(plugin, ctx)

		when:
		report.process(new ReportResult(createdBy: nobody()))
		report.process(new ReportResult())

		then: 'no inventory read at all (the only service touched is the user lookup, and there is no user id)'
		0 * services.getComputeServer()
		0 * services.getCluster()
		statuses == [ReportResult.Status.generating, ReportResult.Status.ready] * 2
		stored.size() == 2
		stored.every { it.section == com.morpheusdata.model.ReportResultRow.SECTION_HEADER && it.dataMap == [notice: VmTuneUpReportProvider.NEED_VIEW_ROW] }

		when:
		String html = report.renderTemplate(new ReportResult(), [(com.morpheusdata.model.ReportResultRow.SECTION_HEADER): [stored[0]]]).html

		then:
		html.contains('You need the VM Tune-Up view permission')
		!html.contains('<h4>Summary</h4>')
	}

	def "a failed report run is marked failed"() {
		given:
		List<ReportResult.Status> statuses = []
		MorpheusReportService reports = Stub {
			updateReportResultStatus(_, _) >> { ReportResult rr, ReportResult.Status s -> statuses << s; Completable.complete() }
			appendResultRows(_, _) >> { throw new RuntimeException('db down') }
		}
		MorpheusContext ctx = Stub(MorpheusContext) { getReport() >> reports }

		when:
		new VmTuneUpReportProvider(plugin, ctx).process(new ReportResult())

		then:
		statuses == [ReportResult.Status.generating, ReportResult.Status.failed]
	}
}
