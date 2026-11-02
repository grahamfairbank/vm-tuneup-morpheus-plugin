package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.GuestFactsSpec.facts
import static io.github.grahamfairbank.vmtuneup.core.Tier2Catalog.*

/** The Tier 2 checks on the guest scan fixtures (real lab captures and synthetic cases). */
class Tier2RulesSpec extends Specification {

	static final Tier2Input ON_SATA = new Tier2Input(needsBusSwitch: true, targetController: 'virtio-blk', needsNicSwitch: false, hasSnapshots: false)
	static final Tier2Input ON_SATA_E1000 = new Tier2Input(needsBusSwitch: true, needsNicSwitch: true, hasSnapshots: false)
	static final Tier2Input ON_VIRTIO = new Tier2Input(needsBusSwitch: false, needsNicSwitch: false, hasSnapshots: false)

	static CheckResult run(Check c, String fixture, Tier2Input input) { Tier2Rules.evaluate(c, facts(fixture), input) }

	// ---- catalog ----

	def "the Tier 2 catalog is valid and joins the Tier 1 one without clashes"() {
		expect:
		Tier2Catalog.ALL*.id == ['T2-VIRTIO-READY', 'T2-VMWARE-TOOLS', 'T2-GA-FEATURES', 'T2-TIME-SYNC', 'T2-VIRTIO-VERSION', 'T2-DC-VMGENID']
		Tier2Catalog.ALL.every { it.tier == Tier.TWO && it.id ==~ /^T2-[A-Z0-9]+(-[A-Z0-9]+)*$/ }
		Tier2Catalog.ALL.every { it.references[0].hpe }
		Tier2Catalog.ALL.every { it.evidence.startsWith(SCAN_EVIDENCE) }
		CheckCatalog.validated(Tier1Catalog.ALL + Tier2Catalog.ALL).size() == Tier1Catalog.ALL.size() + 6
		DC_VMGENID.category == Category.CONSIDERATION
		VIRTIO_VERSION.severity == Severity.INFO && VIRTIO_VERSION.infoPenalty == 0
	}

	def "check texts use HPE naming and US spelling"() {
		given:
		String all = Tier2Catalog.ALL.collect { Check c ->
			[c.title, c.evidence, c.ifYouLeaveIt, c.ifYouFixIt.summary, c.howInVme ?: '', c.whyConsideration ?: ''] + c.ifYouFixIt.prerequisites + c.unverified
		}.flatten().join('\n')

		expect:
		!(all =~ /\bVME\b/).find()
		!(all =~ /(?i)\b(colour|behaviour|organis|recognis|licence)/).find()
	}

	def "every check returns exactly one result, UNKNOWN for a scan that failed"() {
		when:
		List<CheckResult> bad = Tier2Rules.evaluate(facts('truncated'), ON_SATA)
		List<CheckResult> none = Tier2Rules.evaluate(null, null)

		then:
		bad.size() == 6 && bad.every { it.status == Status.UNKNOWN && it.detail.contains('cut off') }
		none.every { it.status == Status.UNKNOWN }
	}

	@Unroll
	def "#fixture gives one result per check"() {
		expect:
		Tier2Rules.evaluate(facts(fixture), ON_SATA)*.checkId == Tier2Catalog.ALL*.id

		where:
		fixture << GuestFactsSpec.fixtureNames()
	}

	// ---- T2-VIRTIO-READY ----

	@Unroll
	def "T2-VIRTIO-READY on #fixture (#why): #status #severity"() {
		when:
		CheckResult r = run(VIRTIO_READY, fixture, input)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                            | why                     | input                                                        | status       | severity         | seen
		'windows-2022-migrated-real'       | 'on VirtIO'             | ON_VIRTIO                                                    | Status.PASS  | Severity.WARNING | 'already on VirtIO'
		'windows-2022-migrated-real'       | 'to VirtIO Block'       | ON_SATA                                                      | Status.PASS  | Severity.WARNING | 'viostor is installed and boot-start'
		'windows-2022-migrated-real'       | 'to VirtIO SCSI'        | new Tier2Input(needsBusSwitch: true, targetController: 'virtio-scsi') | Status.FAIL | Severity.WARNING | 'only staged in the driver store'
		'windows-2022-ready'               | 'on SATA'               | ON_SATA                                                      | Status.PASS  | Severity.WARNING | 'Ready to switch'
		'windows-2019-vmware-tools'        | 'viostor Start=3'       | ON_SATA                                                      | Status.FAIL  | Severity.WARNING | 'not boot-start (Start = 3)'
		'windows-2019-vmware-tools'        | 'bus unknown'           | new Tier2Input()                                             | Status.FAIL  | Severity.INFO    | 'not boot-start'
		'windows-10-client'                | 'no drivers, e1000'     | ON_SATA_E1000                                                | Status.FAIL  | Severity.WARNING | 'netkvm'
		'rocky-9-prepped-real'             | 'dracut prepped'        | ON_SATA                                                      | Status.PASS  | Severity.WARNING | 'virtio_blk in the initramfs'
		'rocky-9-ready'                    | 'ready'                 | ON_SATA                                                      | Status.PASS  | Severity.WARNING | 'virtio_pci built into the kernel'
		'rocky-9-hostonly-not-ready'       | 'hostonly'              | ON_SATA                                                      | Status.FAIL  | Severity.WARNING | 'virtio_blk is installed as a module but not in the initramfs'
		'ubuntu-22.04-not-ready'           | 'MODULES=dep, /dev/sda' | ON_SATA                                                      | Status.FAIL  | Severity.WARNING | 'fstab /dev/sda1 /'
		'ubuntu-22.04-not-ready'           | 'already on VirtIO'     | ON_VIRTIO                                                    | Status.PASS  | Severity.WARNING | 'already on VirtIO'
		'ubuntu-24.04-vmware-nonroot-real' | 'built in'              | ON_SATA                                                      | Status.PASS  | Severity.WARNING | 'virtio_blk built into the kernel'
	}

	def "T2-VIRTIO-READY explains what to do and what else it saw"() {
		expect:
		run(VIRTIO_READY, 'windows-2019-vmware-tools', ON_SATA).notes.contains(Tier1Catalog.WINDOWS_BUS_RULE)
		run(VIRTIO_READY, 'windows-2019-vmware-tools', ON_SATA).detail.contains('INACCESSIBLE_BOOT_DEVICE')
		run(VIRTIO_READY, 'windows-2022-migrated-real', new Tier2Input(needsBusSwitch: true, targetController: 'virtio-scsi')).notes.any { it.contains('viostor is boot-start') }
		with(run(VIRTIO_READY, 'ubuntu-22.04-not-ready', ON_SATA)) {
			detail.contains('virtio_blk is installed as a module but not in the initramfs')
			detail.contains('virtio_pci')
			detail.contains('root=/dev/sda1')
			notes.contains(Tier1Catalog.LINUX_BUS_RULE)
		}
		run(VIRTIO_READY, 'ubuntu-22.04-not-ready', ON_VIRTIO).notes.any { it.contains('/dev/sdb1 /srv/data') }
		run(VIRTIO_READY, 'ubuntu-24.04-vmware-nonroot-real', ON_SATA).notes.any { it.contains('not root') }
		run(VIRTIO_READY, 'windows-10-client', ON_SATA_E1000).detail.contains('viostor) is not installed')
	}

	def "T2-VIRTIO-READY is UNKNOWN when the initramfs couldn't be read, never PASS"() {
		given:
		GuestFacts g = GuestFacts.parse('VTU-BEGIN v1 linux\nuid=1000\nbuiltin.read=1\nbuiltin.virtio_blk=0\nbuiltin.virtio_pci=1\n' +
			'initrd.file=found\ninitrd.tool=lsinitrd\ninitrd.entries=0\ninitrd.virtio_blk=0\nfstab.read=1\nVTU-END 9\n')

		expect:
		Tier2Rules.virtioReady(g, ON_SATA).status == Status.UNKNOWN
		Tier2Rules.virtioReady(g, ON_SATA).detail.contains('could not be read')
	}

	def "Tier2Input comes from the Tier 1 facts"() {
		expect:
		Tier2Input.fromVm(Fixtures.vm('vm-sata-import-windows')).needsBusSwitch == true
		Tier2Input.fromVm(Fixtures.vm('vm-clean-linux')).needsBusSwitch == false
		Tier2Input.fromVm(Fixtures.vm('vm-clean-linux')).needsNicSwitch == false
		Tier2Input.fromVm(Fixtures.vm('vm-clean-linux')).target == 'virtio-blk'
		Tier2Input.fromVm(null).needsBusSwitch == null
	}

	// ---- T2-VMWARE-TOOLS ----

	@Unroll
	def "T2-VMWARE-TOOLS on #fixture: #status #severity"() {
		when:
		CheckResult r = run(VMWARE_TOOLS, fixture, ON_VIRTIO)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                            | status      | severity         | seen
		'windows-2022-migrated-real'       | Status.FAIL | Severity.WARNING | 'VMware Tools 12.3.0.22234872 is installed, its service is Running (Start = 2)'
		'windows-2019-vmware-tools'        | Status.FAIL | Severity.WARNING | 'pvscsi (Start = 0)'
		'windows-2022-ready'               | Status.PASS | Severity.WARNING | 'No VMware Tools'
		'windows-10-client'                | Status.PASS | Severity.WARNING | 'No VMware Tools'
		'rocky-9-prepped-real'             | Status.FAIL | Severity.INFO    | 'open-vm-tools 12.5.0 is installed (not running)'
		'rocky-9-ready'                    | Status.PASS | Severity.WARNING | 'No open-vm-tools'
		'rocky-9-hostonly-not-ready'       | Status.FAIL | Severity.WARNING | 'vmtoolsd (VMware Tools) is running'
		'ubuntu-24.04-vmware-nonroot-real' | Status.FAIL | Severity.WARNING | 'vmw_pvscsi'
	}

	def "T2-VMWARE-TOOLS: leftovers without VMware Tools, or a disabled service, are info"() {
		given:
		GuestFacts left = GuestFacts.parse('VTU-BEGIN v1 windows\nsvc.VMTools=absent\nsvc.vmxnet3ndis6=3\nvmw.pcidevs=2\nVTU-END 3\n')
		GuestFacts off = GuestFacts.parse('VTU-BEGIN v1 windows\npkg.vmwaretools=12.3.0\nsvc.VMTools=4\nrun.VMTools=Stopped\nVTU-END 3\n')

		expect:
		Tier2Rules.vmwareTools(left).status == Status.FAIL
		Tier2Rules.vmwareTools(left).severity == Severity.INFO
		Tier2Rules.vmwareTools(left).detail.contains('2 VMware PCI devices')
		Tier2Rules.vmwareTools(off).severity == Severity.INFO
	}

	// ---- T2-GA-FEATURES ----

	@Unroll
	def "T2-GA-FEATURES on #fixture: #status"() {
		when:
		CheckResult r = run(GA_FEATURES, fixture, ON_VIRTIO)

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                            | status      | seen
		'windows-2022-migrated-real'       | Status.PASS | 'QEMU guest agent 110.2.3 is running and its VSS provider is registered'
		'windows-10-client'                | Status.FAIL | 'not installed'
		'rocky-9-prepped-real'             | Status.FAIL | 'qemu-guest-agent) is not installed'
		'rocky-9-ready'                    | Status.PASS | 'qemu-guest-agent 8.2.0 is running and file system freeze is allowed'
		'rocky-9-hostonly-not-ready'       | Status.FAIL | 'no guest agent channel device'
	}

	def "T2-GA-FEATURES catches a blocked freeze and a missing VSS provider"() {
		given:
		GuestFacts lin = GuestFacts.parse('VTU-BEGIN v1 linux\nqga.running=1\nqga.channel=1\nqga.fsfreeze-freeze=blocked\nqga.exec=blocked\nVTU-END 4\n')
		GuestFacts win = GuestFacts.parse('VTU-BEGIN v1 windows\nsvc.QEMU-GA=2\nrun.QEMU-GA=Running\nsvc.QEMU_Guest_Agent_VSS_Provider=absent\n' +
			'qga.blocked=guest-fsfreeze-freeze,guest-exec\nVTU-END 4\n')

		expect:
		Tier2Rules.gaFeatures(lin).status == Status.FAIL
		Tier2Rules.gaFeatures(lin).detail.contains('guest-fsfreeze-freeze is blocked')
		Tier2Rules.gaFeatures(lin).notes.any { it.contains('Morpheus agent') }
		Tier2Rules.gaFeatures(win).detail.contains('VSS Provider is not installed')
		Tier2Rules.gaFeatures(win).detail.contains('freeze commands blocked')
		run(GA_FEATURES, 'rocky-9-ready', ON_VIRTIO).notes.any { it.contains('fsfreeze hook') }
	}

	// ---- T2-TIME-SYNC ----

	@Unroll
	def "T2-TIME-SYNC on #fixture: #status #severity"() {
		when:
		CheckResult r = run(TIME_SYNC, fixture, ON_VIRTIO)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                            | status      | severity         | seen
		'windows-2022-migrated-real'       | Status.PASS | Severity.WARNING | 'time.windows.com,0x8 (Type NTP)'
		'windows-10-client'                | Status.PASS | Severity.WARNING | 'time.windows.com'
		'windows-2019-vmware-tools'        | Status.FAIL | Severity.WARNING | "'Local CMOS Clock'"
		'windows-2022-dc'                  | Status.FAIL | Severity.WARNING | 'primary domain controller'
		'rocky-9-prepped-real'             | Status.PASS | Severity.WARNING | 'Clock source kvm-clock; chronyd running and synchronized'
		'rocky-9-hostonly-not-ready'       | Status.FAIL | Severity.INFO    | 'tsc, not kvm-clock'
		'ubuntu-22.04-not-ready'           | Status.FAIL | Severity.WARNING | 'not synchronized'
		'ubuntu-24.04-vmware-nonroot-real' | Status.FAIL | Severity.INFO    | 'tsc'
	}

	def "T2-TIME-SYNC: no NTP client is a warning; a stopped service on a client is only a note"() {
		given:
		GuestFacts none = GuestFacts.parse('VTU-BEGIN v1 linux\ntime.clocksource=kvm-clock\nVTU-END 1\n')

		expect:
		Tier2Rules.timeSync(none).status == Status.FAIL
		Tier2Rules.timeSync(none).severity == Severity.WARNING
		run(TIME_SYNC, 'windows-10-client', ON_VIRTIO).notes.any { it.contains('starts when needed') }
	}

	// ---- T2-VIRTIO-VERSION ----

	@Unroll
	def "T2-VIRTIO-VERSION on #fixture (cluster newest #fleet): #status"() {
		when:
		CheckResult r = run(VIRTIO_VERSION, fixture, new Tier2Input(fleetNewestVirtioWin: fleet))

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                      | fleet     | status                | seen
		'windows-2022-migrated-real' | null      | Status.PASS           | 'virtio-win 0.1.302; viostor 100.103.104.30200 (0.1.302)'
		'windows-2022-migrated-real' | '0.1.302' | Status.PASS           | '0.1.302'
		'windows-2019-vmware-tools'  | '0.1.302' | Status.FAIL           | 'The newest in this cluster is 0.1.302'
		'windows-10-client'          | null      | Status.NOT_APPLICABLE | 'No VirtIO drivers'
		'rocky-9-ready'              | null      | Status.NOT_APPLICABLE | 'Windows only'
	}

	def "T2-VIRTIO-VERSION notes drivers from different releases"() {
		given:
		GuestFacts g = GuestFacts.parse('VTU-BEGIN v1 windows\ndrv.viostor=100.94.104.24000\ndrv.netkvm=100.103.104.30200\nVTU-END 2\n')

		expect:
		Tier2Rules.virtioVersion(g, new Tier2Input()).notes == ['The drivers come from different virtio-win releases: 0.1.240, 0.1.302.']
		Tier2Rules.compareVersions('0.1.240', '0.1.302') < 0
		Tier2Rules.compareVersions('0.1.302', '0.1.302') == 0
		Tier2Rules.compareVersions('0.1.1000', '0.1.302') > 0
	}

	// ---- T2-DC-VMGENID ----

	@Unroll
	def "T2-DC-VMGENID on #fixture (snapshots #snaps): #status #severity"() {
		when:
		CheckResult r = run(DC_VMGENID, fixture, new Tier2Input(hasSnapshots: snaps))

		then:
		r.status == status
		r.severity == severity

		where:
		fixture                      | snaps | status                | severity
		'windows-2022-dc'            | false | Status.FAIL           | Severity.WARNING
		'windows-2022-dc'            | true  | Status.FAIL           | Severity.CRITICAL
		'windows-2022-dc'            | null  | Status.FAIL           | Severity.WARNING
		'windows-2022-migrated-real' | true  | Status.NOT_APPLICABLE | Severity.WARNING
		'windows-10-client'          | false | Status.NOT_APPLICABLE | Severity.WARNING
		'rocky-9-ready'              | true  | Status.NOT_APPLICABLE | Severity.WARNING
	}

	def "T2-DC-VMGENID names the role and the snapshot risk"() {
		expect:
		run(DC_VMGENID, 'windows-2022-dc', new Tier2Input(hasSnapshots: true)).detail.contains('primary domain controller and has Morpheus snapshots')
		run(DC_VMGENID, 'windows-2022-dc', new Tier2Input()).notes == ['Snapshots could not be read for this VM.']
		Tier2Rules.dcVmgenid(GuestFacts.parse('VTU-BEGIN v1 windows\nVTU-END 0\n'), new Tier2Input()).status == Status.UNKNOWN
	}

	def "scores: a clean guest costs nothing, the not-ready one costs points"() {
		expect:
		Scoring.score(Tier2Rules.evaluate(facts('rocky-9-ready'), ON_SATA)).value == 100
		Scoring.score(Tier2Rules.evaluate(facts('ubuntu-22.04-not-ready'), ON_SATA)).value < 100
	}
}
