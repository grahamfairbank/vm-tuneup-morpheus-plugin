package io.github.grahamfairbank.migrationhygiene.core

import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.migrationhygiene.core.Fixtures.*
import static io.github.grahamfairbank.migrationhygiene.core.Tier1Catalog.*

/** One block per Tier 1 check, driven by the sanitized lab and 9.0.1-pattern fixtures. */
class Tier1RulesSpec extends Specification {

	@Unroll
	def "T1-DISK-BUS on #fixture: #status #severity"() {
		when:
		CheckResult r = result(fixture, DISK_BUS)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                  | status      | severity          | seen
		'vm-clean-linux'         | Status.PASS | Severity.WARNING  | 'virtio/kvm-virtio-blk'
		'vm-901-migrated-windows' | Status.PASS | Severity.WARNING | 'scsi/kvm-virtio-scsi'
		'vm-ide-import'          | Status.FAIL | Severity.CRITICAL | 'diskMode ide, controller kvm-ide'
		'vm-sata-import-windows' | Status.FAIL | Severity.WARNING  | 'Boot disk'
		'vm-sata-import-linux'   | Status.FAIL | Severity.WARNING  | 'diskMode sata, controller kvm-sata'
		'vm-legacy-dirty'        | Status.FAIL | Severity.WARNING  | "Disk 'data-1'"
	}

	def "T1-DISK-BUS ignores CD-ROM drives on SATA"() {
		expect: 'every lab VM has its CD-ROM on kvm-sata, and the clean ones still pass'
		vm('vm-clean-linux').cdDrives*.controllerType == ['kvm-sata']
		result('vm-clean-linux', DISK_BUS).status == Status.PASS
	}

	def "T1-DISK-BUS names the ordering rule for the guest's OS, from serverOs"() {
		expect:
		result('vm-sata-import-linux', DISK_BUS).notes.size() == 2 // OS unknown on a discovered VM: both rules
		Tier1Rules.osRuleNotes(vm('vm-901-migrated-windows')) == [WINDOWS_BUS_RULE] // morpheusVm with osType null is still Windows
		Tier1Rules.osRuleNotes(vm('vm-clean-linux')) == [LINUX_BUS_RULE]
		WINDOWS_BUS_RULE.contains('boot-start') && WINDOWS_BUS_RULE.contains('Start = 0')
		LINUX_BUS_RULE.contains('initramfs') && LINUX_BUS_RULE.contains('UUID')
	}

	def "T1-DISK-BUS with no disks is unknown"() {
		expect:
		Tier1Rules.diskBus(VmFacts.fromMap([name: 'x', volumes: []])).status == Status.UNKNOWN
	}

	@Unroll
	def "T1-NIC-MODEL on #fixture: #status"() {
		when:
		CheckResult r = result(fixture, NIC_MODEL)

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                  | status      | seen
		'vm-clean-linux'         | Status.PASS | 'VirtIO'
		'vm-ide-import'          | Status.FAIL | 'type e1000-kvm (E1000)'
		'vm-sata-import-windows' | Status.FAIL | 'not VirtIO, model unknown'
		'vm-iso-attached'        | Status.FAIL | 'no type reported'
	}

	def "T1-NIC-MODEL is not applicable without interfaces"() {
		expect:
		Tier1Rules.nicModel(VmFacts.fromMap([name: 'x'])).status == Status.NOT_APPLICABLE
	}

	@Unroll
	def "T1-CHIPSET on #fixture: #status"() {
		when:
		CheckResult r = result(fixture, CHIPSET)

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                   | status         | seen
		'vm-clean-linux'          | Status.PASS    | 'q35'
		'vm-legacy-dirty'         | Status.FAIL    | 'config.machineType = pc'
		'vm-901-migrated-windows' | Status.UNKNOWN | 'Morpheus 9.0.x'
		'vm-ide-import'           | Status.UNKNOWN | 'Discovered VM'
	}

	def "T1-CHIPSET unknown on a discovered IDE VM hints at the Legacy chipset"() {
		expect:
		result('vm-ide-import', CHIPSET).notes.any { it.contains('i440fx') }
	}

	@Unroll
	def "T1-VMWARE-MAC on #fixture: #status"() {
		expect:
		result(fixture, VMWARE_MAC).status == status

		where:
		fixture                   | status
		'vm-clean-linux'          | Status.PASS
		'vm-ide-import'           | Status.FAIL
		'vm-sata-import-windows'  | Status.FAIL
		'vm-901-migrated-windows' | Status.FAIL
		'vm-901-migrated-linux'   | Status.FAIL
	}

	def "T1-VMWARE-MAC knows all four VMware OUIs, in any case and separator"() {
		expect:
		['00:50:56:AA:BB:CC', '00-0C-29-00-00-01', '00:05:69:00:00:01', '00:1c:14:00:00:01'].every { Tier1Rules.oui(it) in Tier1Rules.VMWARE_OUIS }
		!(Tier1Rules.oui('52:54:00:00:00:01') in Tier1Rules.VMWARE_OUIS)
		Tier1Rules.oui(null) == null
	}

	@Unroll
	def "T1-OS-TYPE-MISMATCH on #fixture: #status #severity"() {
		when:
		CheckResult r = result(fixture, OS_TYPE_MISMATCH)

		then:
		r.status == status
		r.severity == severity

		where:
		fixture                       | status                | severity
		'vm-clean-linux'              | Status.PASS           | Severity.INFO
		'vm-prepped-windows'          | Status.PASS           | Severity.INFO
		'vm-ide-import'               | Status.NOT_APPLICABLE | Severity.INFO
		'vm-901-migrated-windows'     | Status.FAIL           | Severity.WARNING
		'vm-901-migrated-linux'       | Status.FAIL           | Severity.INFO
	}

	def "T1-OS-TYPE-MISMATCH shows the fields side by side"() {
		expect:
		result('vm-901-migrated-windows', OS_TYPE_MISMATCH).detail ==
			'serverOs windows.server.2022.std.desktop, osType null, platform null, server type morpheusVm: osType is empty, ' +
			'server type is not morpheusWindowsVm, platform is not windows. Likely VMware origin (native migration).'
	}

	@Unroll
	def "T1-FIRMWARE on #fixture: #status"() {
		when:
		CheckResult r = result(fixture, FIRMWARE)

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                | status         | seen
		'vm-clean-linux'       | Status.PASS    | 'UEFI off, Secure Boot off, TPM off'
		'vm-901-windows-agent' | Status.PASS    | 'TPM on'
		'vm-legacy-dirty'      | Status.FAIL    | 'TPM without UEFI is inconsistent; windows 11 64-bit without UEFI and TPM'
		'vm-ide-import'        | Status.UNKNOWN | 'guest OS is unknown'
	}

	def "T1-FIRMWARE flags Windows Server 2025 without TPM"() {
		given:
		VmFacts f = VmFacts.fromMap([name: 'x', uefi: true, tpm: false, serverOs: [code: 'windows.server.2025', name: 'windows server 2025', platform: 'windows']])

		expect:
		Tier1Rules.firmware(f).status == Status.FAIL
	}

	@Unroll
	def "T1-GA-CHANNEL on #fixture: #status"() {
		expect:
		result(fixture, GA_CHANNEL).status == status

		where:
		fixture                   | status
		'vm-clean-linux'          | Status.PASS
		'vm-legacy-dirty'         | Status.FAIL
		'vm-901-migrated-windows' | Status.UNKNOWN
		'vm-ide-import'           | Status.UNKNOWN
	}

	@Unroll
	def "T1-GA-STATUS on #fixture (#power, #ga): #status"() {
		given:
		VmFacts f = vm(fixture)

		expect:
		f.powerState == power
		f.guestAgentStatus == ga
		result(fixture, GA_STATUS).status == status

		where:
		fixture                   | power | ga             | status
		'vm-clean-linux'          | 'on'  | 'connected'    | Status.PASS
		'vm-prepped-windows'      | 'on'  | 'connected'    | Status.PASS
		'vm-ga-disconnected'      | 'on'  | 'disconnected' | Status.FAIL
		'vm-ide-import'           | 'on'  | 'unknown'      | Status.UNKNOWN
		'vm-clean-linux-off'      | 'off' | 'configured'   | Status.NOT_APPLICABLE
		'vm-901-migrated-windows' | 'off' | 'configured'   | Status.NOT_APPLICABLE
	}

	static VmFacts running(String ga, Map probe = null) {
		Map m = [name: 'vm-x', powerState: 'on', guestAgentStatus: ga]
		if (probe != null) m.observedGuestAgent = probe
		return VmFacts.fromMap(m)
	}

	def "T1-GA-STATUS: a Windows VM whose guest agent probe gets no answer fails as a warning"() {
		when:
		CheckResult r = Tier1Rules.gaStatus(VmFacts.fromMap([name: 'w', powerState: 'on', guestAgentStatus: 'unknown',
			serverOs: [code: 'windows.server.2022', osFamily: 'windows', platform: 'windows'], platform: 'windows', osType: 'windows',
			observedGuestAgent: [result: 'no-response', error: 'no answer within 20 s']]))

		then:
		r.status == Status.FAIL
		r.severity == Severity.WARNING
		r.detail.contains('did not answer a no-op command')
	}

	@Unroll
	def "T1-GA-STATUS on a running VM with guestAgentStatus #ga and probe #probe: #status #severity"() {
		when:
		CheckResult r = Tier1Rules.gaStatus(running(ga, probe))

		then:
		r.status == status
		status != Status.FAIL || r.severity == severity
		r.detail.contains(text)

		where:
		ga             | probe                                                                    | status         | severity         | text
		'connected'    | null                                                                     | Status.PASS    | null             | 'connected'
		'connected'    | [result: 'no-response']                                                  | Status.PASS    | null             | 'connected'
		'disconnected' | null                                                                     | Status.FAIL    | Severity.WARNING | 'disconnected'
		'error'        | null                                                                     | Status.FAIL    | Severity.WARNING | 'error'
		'unconfigured' | null                                                                     | Status.FAIL    | Severity.WARNING | 'unconfigured'
		'disconnected' | [result: 'responded']                                                    | Status.FAIL    | Severity.WARNING | 'disconnected'
		'unknown'      | [result: 'responded', success: true, exitCode: '0']                      | Status.PASS    | null             | 'guest agent answered a no-op command (success true, exit code 0)'
		null           | [result: 'responded', success: true]                                     | Status.PASS    | null             | 'answered'
		'unknown'      | [result: 'no-response', success: false, exitCode: '1', error: 'refused'] | Status.UNKNOWN | null             | "guest agent's state (success false, exit code 1; refused)"
		'unknown'      | [result: 'no-response', error: 'no answer within 20 s']                  | Status.UNKNOWN | null             | '(no answer within 20 s)'
		'unknown'      | [result: 'not-probed', reason: 'guest-scan permission not granted']      | Status.UNKNOWN | null             | 'Not probed: guest-scan permission not granted.'
		'unknown'      | [result: 'not-probed', reason: 'time budget']                            | Status.UNKNOWN | null             | 'Not probed: time budget.'
		'unknown'      | null                                                                     | Status.UNKNOWN | null             | 'Not probed.'
	}

	def "T1-GA-STATUS ignores the probe on a powered-off VM"() {
		expect:
		Tier1Rules.gaStatus(VmFacts.fromMap([name: 'x', powerState: 'off', guestAgentStatus: 'unknown',
			observedGuestAgent: [result: 'no-response']])).status == Status.NOT_APPLICABLE
	}

	def "T1-GA-STATUS with an unknown power state is unknown"() {
		expect:
		Tier1Rules.gaStatus(VmFacts.fromMap([name: 'x', powerState: 'unknown', guestAgentStatus: 'unknown'])).status == Status.UNKNOWN
	}

	@Unroll
	def "T1-MORPHEUS-AGENT on #fixture: #status #severity"() {
		when:
		CheckResult r = result(fixture, MORPHEUS_AGENT)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                      | status      | severity         | seen
		'vm-clean-linux'             | Status.PASS | Severity.INFO    | 'agentVersion 4.0.1'
		'vm-901-linux-agent-current' | Status.PASS | Severity.INFO    | '3.2.7'
		'vm-901-windows-agent'       | Status.PASS | Severity.INFO    | '2.6.1.0'
		'vm-901-migrated-linux'      | Status.FAIL | Severity.INFO    | 'older than the newest linux agent in this fleet (3.2.7)'
		'vm-virtio-skipped-prep'     | Status.FAIL | Severity.INFO    | 'config.noAgent = true'
		'vm-ide-import'              | Status.FAIL | Severity.INFO    | 'agentInstalled false'
		'vm-legacy-dirty'            | Status.FAIL | Severity.WARNING | 'no agent update for 61 days'
	}

	def "T1-MORPHEUS-AGENT compares Windows and Linux agents separately, from the fleet"() {
		given:
		List<VmFacts> fleet = ['vm-901-migrated-linux', 'vm-901-linux-agent-current', 'vm-901-windows-agent', 'vm-ide-import'].collect { vm(it) }

		expect:
		ScanContext.baselineOf(fleet) == [linux: '3.2.7', windows: '2.6.1.0']
		ScanContext.compareVersions('3.10.0', '3.9.9') > 0
		ScanContext.compareVersions('2.6.1.0', '2.6.1') == 0
		ScanContext.compareVersions('3.1.3', '3.2.7') < 0
	}

	def "T1-MORPHEUS-AGENT without a baseline doesn't judge the version"() {
		when:
		CheckResult r = Tier1Rules.evaluate(MORPHEUS_AGENT, vm('vm-901-migrated-linux'), new ScanContext(NOW, [:]))

		then:
		r.status == Status.PASS
		r.notes.any { it.contains('No fleet baseline') }
	}

	@Unroll
	def "T1-SNAPSHOTS on #fixture: #status #severity"() {
		when:
		CheckResult r = result(fixture, SNAPSHOTS)

		then:
		r.status == status
		r.severity == severity
		r.detail.contains(seen)

		where:
		fixture                   | status         | severity         | seen
		'vm-clean-linux'          | Status.PASS    | Severity.INFO    | 'No snapshots'
		'vm-prepped-windows'      | Status.PASS    | Severity.INFO    | '1 snapshot, oldest 1 days old'
		'vm-901-migrated-windows' | Status.FAIL    | Severity.WARNING | '10 snapshots, oldest 231 days old (2026-03-15)'
		'vm-sata-import-windows'  | Status.UNKNOWN | Severity.INFO    | 'could not be read'
	}

	def "T1-SNAPSHOTS thresholds: more than 3, or older than 30 days, is info"() {
		given:
		Closure<VmFacts> withSnaps = { List dates -> VmFacts.fromMap([name: 'x', snapshots: dates.collect { [name: 's', snapshotCreated: it] }]) }

		expect:
		Tier1Rules.snapshots(withSnaps(['2026-10-30']), ctx()).status == Status.PASS
		Tier1Rules.snapshots(withSnaps(['2026-09-15']), ctx()).severity == Severity.INFO
		Tier1Rules.snapshots(withSnaps(['2026-10-30'] * 4), ctx()).status == Status.FAIL
		Tier1Rules.snapshots(withSnaps(['2026-10-30'] * 4), ctx()).severity == Severity.INFO
		Tier1Rules.snapshots(withSnaps(['2026-07-01']), ctx()).severity == Severity.WARNING
		Tier1Rules.snapshots(withSnaps([null]), ctx()).status == Status.UNKNOWN
	}

	@Unroll
	def "T1-DISK-ALLOCATION on #fixture: #status"() {
		expect:
		result(fixture, DISK_ALLOCATION).status == status

		where:
		fixture                   | status
		'vm-clean-linux'          | Status.PASS
		'vm-ide-import'           | Status.PASS
		'vm-legacy-dirty'         | Status.FAIL
		'vm-901-migrated-windows' | Status.UNKNOWN // the 9.0.1 sample reports usedStorage 0
	}

	@Unroll
	def "T1-DISCOVERED on #fixture: #status"() {
		expect:
		result(fixture, DISCOVERED).status == status

		where:
		fixture                   | status
		'vm-clean-linux'          | Status.PASS
		'vm-prepped-windows'      | Status.PASS
		'vm-901-migrated-windows' | Status.PASS
		'vm-ide-import'           | Status.FAIL
		'vm-discovered-virtio'    | Status.FAIL
		'vm-sata-import-linux'    | Status.FAIL
	}

	def "T1-DISCOVERED lists the VME defaults the VM lacks"() {
		expect:
		DISCOVERED.ifYouLeaveIt.contains('QEMU guest agent channel')
		DISCOVERED.ifYouLeaveIt.contains('io_uring')
		DISCOVERED.unverified.any { it.contains('Convert To Managed') }
	}

	@Unroll
	def "T1-ISO-ATTACHED on #fixture: #status"() {
		when:
		CheckResult r = result(fixture, ISO_ATTACHED)

		then:
		r.status == status
		r.detail.contains(seen)

		where:
		fixture                | status      | seen
		'vm-iso-attached'      | Status.FAIL | 'sourceImage installer.iso'
		'vm-iso-ejected'       | Status.PASS | 'all empty'
		'vm-clean-linux'       | Status.PASS | 'all empty'
		'vm-ide-import'        | Status.PASS | 'No CD-ROM drive'
		'vm-901-windows-agent' | Status.FAIL | 'virtio-win.iso'
		'vm-legacy-dirty'      | Status.FAIL | 'volumePath set, 0.3 GB'
	}

	def "T1-SOURCE-TWIN is defined but not evaluated yet"() {
		expect:
		result('vm-901-migrated-windows', SOURCE_TWIN).status == Status.NOT_EVALUATED
		SOURCE_TWIN.severity == Severity.CRITICAL
	}

	def "every check returns exactly one result for every fixture"() {
		expect:
		names().every { String n -> Tier1Rules.evaluate(vm(n), ctx())*.checkId == Tier1Catalog.ALL*.id }
	}
}
