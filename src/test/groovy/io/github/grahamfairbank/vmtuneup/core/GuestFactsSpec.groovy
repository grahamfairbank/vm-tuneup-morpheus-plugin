package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification
import spock.lang.Unroll

/** Parsing guest scan output: tolerant of noise and missing keys, strict about markers, size and counts. */
class GuestFactsSpec extends Specification {

	static String text(String name) {
		InputStream s = GuestFactsSpec.getResourceAsStream("/fixtures/guest/${name}.txt")
		if (s == null) throw new IllegalArgumentException("no guest fixture ${name}")
		return s.getText('UTF-8')
	}

	static GuestFacts facts(String name, String os = null) { GuestFacts.parse(text(name), os) }

	static List<String> fixtureNames() {
		new File(GuestFactsSpec.getResource('/fixtures/guest').toURI()).listFiles().findAll { it.name.endsWith('.txt') }*.name.collect { it - '.txt' }.sort()
	}

	@Unroll
	def "#name parses as #status"() {
		expect:
		facts(name).status == status

		where:
		name                                  | status
		'windows-2022-migrated-real'          | GuestFacts.Parse.OK
		'windows-2022-ready'                  | GuestFacts.Parse.OK
		'windows-2019-vmware-tools'           | GuestFacts.Parse.OK
		'windows-10-client'                   | GuestFacts.Parse.OK
		'windows-2022-dc'                     | GuestFacts.Parse.OK
		'rocky-9-prepped-real'                | GuestFacts.Parse.OK
		'rocky-9-ready'                       | GuestFacts.Parse.OK
		'rocky-9-hostonly-not-ready'          | GuestFacts.Parse.OK
		'ubuntu-22.04-not-ready'              | GuestFacts.Parse.OK
		'ubuntu-24.04-vmware-nonroot-real'    | GuestFacts.Parse.OK
		'truncated'                           | GuestFacts.Parse.TRUNCATED
		'truncated-count'                     | GuestFacts.Parse.TRUNCATED
		'garbage'                             | GuestFacts.Parse.NO_MARKERS
		'garbage-inside'                      | GuestFacts.Parse.MALFORMED
		'wrong-version'                       | GuestFacts.Parse.UNSUPPORTED_VERSION
	}

	def "every fixture is covered above"() {
		expect:
		fixtureNames().size() == 15
	}

	def "the real Windows capture reads into typed facts"() {
		when:
		GuestFacts g = facts('windows-2022-migrated-real', 'windows')

		then:
		g.ok && g.windows && !g.linux
		g.caption == 'Microsoft Windows Server 2022 Standard'
		g.build == 20348
		g.productType == 3
		g.domainRole == 2
		g.domainController == false
		g.ranAsSystem
		g.is64Bit
		g.editionId == 'ServerStandard'
		g.installationType == 'Server'
		g.serviceExists('viostor') && g.serviceStart('viostor') == 0
		g.serviceExists('vioscsi') == false && g.serviceStart('vioscsi') == null
		g.driverStaged('vioscsi')
		g.serviceState('QEMU-GA') == 'Running'
		g.serviceState('QEMU Guest Agent VSS Provider') == 'Stopped'
		g.driverVersion('viostor') == '100.103.104.30200'
		g.virtioWinRelease == '0.1.302'
		g.all('pkg.virtiowin') == ['0.1.302', '0.1.302']
		g.vmwareToolsVersion == '12.3.0.22234872'
		g.vmwareDrivers == [pvscsi: 0, vmxnet3ndis6: 3, vm3dmp: 3, vmhgfs: 3, vmmouse: 3, vmusbmouse: 3, vmci: 0, vmmemctl: 2]
		g.vmwarePciDevices == 6
		g.vssProviders.contains('QEMU Guest Agent VSS Provider')
		g.timeSource == 'time.windows.com,0x8'
		g.timeType == 'NTP'
		g.qgaVersion == '110.2.3'
		g.qgaBlockedRpcs == []
	}

	def "the real Rocky capture reads into typed facts"() {
		when:
		GuestFacts g = facts('rocky-9-prepped-real', 'linux')

		then:
		g.ok && g.linux
		g.uid == 0
		g.osId == 'rocky'
		g.versionId == '9.6'
		g.idLike == ['rhel', 'centos', 'fedora']
		g.kernel == '5.14.0-570.17.1.el9_6.x86_64'
		g.builtin('virtio_blk') == false
		g.inInitrd('virtio_blk')
		g.builtin('virtio_pci')
		g.initrdTool == 'lsinitrd'
		g.initrdEntries == 2107
		g.initrdModules == null
		g.fstabRead
		g.fstabDeviceNames == []
		g.bootRoot == '/dev/mapper/rl_app--legacy--01-root'
		g.openVmToolsVersion == '12.5.0'
		g.vmtoolsRunning == false
		g.vmwareModules == []
		g.qgaRunning == false
		g.qgaChannel
		g.qgaFreeze == 'default'
		g.qgaVersion == null
		g.clocksource == 'kvm-clock'
		g.availableClocksources == ['kvm-clock', 'tsc', 'hpet', 'acpi_pm']
		g.ntpSynced
		g.timeServices == ['chronyd']
	}

	def "initramfs-tools defaults MODULES to most when the setting is missing"() {
		when:
		GuestFacts g = GuestFacts.parse('VTU-BEGIN v1 linux\ninitrd.tool=initramfs-tools\nVTU-END 1\n')

		then:
		g.initrdModules == 'most'
	}

	def "missing keys are unknown, never no"() {
		when:
		GuestFacts g = GuestFacts.parse('VTU-BEGIN v1 windows\nos.caption=Windows\nVTU-END 1\n')

		then:
		g.ok
		g.serviceExists('viostor') == null
		g.serviceStart('viostor') == null
		g.driverStaged('viostor') == null
		g.domainController == null
		g.ranAsSystem == null
		g.build == null
		g.vssProviders == []
	}

	def "noise outside the markers, CRLF and blank lines are ignored"() {
		when:
		GuestFacts g = GuestFacts.parse('banner\r\n#< CLIXML\r\nVTU-BEGIN v1 linux\r\n\r\nuid=0\r\nos.id=Debian\r\nVTU-END 2\r\n<Objs/>')

		then:
		g.ok
		g.uid == 0
		g.osId == 'debian'
	}

	def "a few junk lines inside the block are tolerated"() {
		expect:
		GuestFacts.parse('VTU-BEGIN v1 linux\nuid=0\nwarning: something\n= no key\nVTU-END 1\n').ok
	}

	@Unroll
	def "#why is rejected as #status without throwing"() {
		expect:
		GuestFacts.parse(output, expected).status == status
		GuestFacts.parse(output, expected).problem

		where:
		why                       | output                                                                 | expected  | status
		'null'                    | null                                                                   | null      | GuestFacts.Parse.EMPTY
		'blank'                   | '  \n '                                                                | null      | GuestFacts.Parse.EMPTY
		'too large'               | 'VTU-BEGIN v1 linux\n' + ('x' * 40000)                                 | null      | GuestFacts.Parse.TOO_LARGE
		'no end'                  | 'VTU-BEGIN v1 linux\nuid=0\n'                                          | null      | GuestFacts.Parse.TRUNCATED
		'fewer lines'             | 'VTU-BEGIN v1 linux\nuid=0\nVTU-END 3\n'                               | null      | GuestFacts.Parse.TRUNCATED
		'more lines'              | 'VTU-BEGIN v1 linux\nuid=0\nos.id=x\nVTU-END 1\n'                      | null      | GuestFacts.Parse.MALFORMED
		'second begin'            | 'VTU-BEGIN v1 linux\nuid=0\nVTU-BEGIN v1 linux\nVTU-END 1\n'           | null      | GuestFacts.Parse.MALFORMED
		'broken end'              | 'VTU-BEGIN v1 linux\nuid=0\nVTU-END x\n'                               | null      | GuestFacts.Parse.MALFORMED
		'other os'                | 'VTU-BEGIN v1 linux\nuid=0\nVTU-END 1\n'                               | 'windows' | GuestFacts.Parse.WRONG_OS
		'new version'             | 'VTU-BEGIN v2 windows\nVTU-END 0\n'                                    | null      | GuestFacts.Parse.UNSUPPORTED_VERSION
		'unknown os'              | 'VTU-BEGIN v1 solaris\nVTU-END 0\n'                                    | null      | GuestFacts.Parse.NO_MARKERS
		'binary'                  | '\u0000\u0001￿'                                                   | null      | GuestFacts.Parse.NO_MARKERS
		'over the line limit'     | 'VTU-BEGIN v1 linux\n' + (1..200).collect { "k${it}=v" }.join('\n') + '\nVTU-END 200\n' | null | GuestFacts.Parse.MALFORMED
	}

	def "long values are cut to the value limit and invalid keys skipped"() {
		when:
		GuestFacts g = GuestFacts.parse("VTU-BEGIN v1 linux\nos.pretty=${'a' * 500}\nVTU-END 1\n")

		then:
		g.ok
		g.prettyName.length() == GuestScripts.MAX_VALUE
	}

	@Unroll
	def "driver file version #v is virtio-win #release"() {
		expect:
		GuestFacts.virtioReleaseOf(v) == release

		where:
		v                    | release
		'100.103.104.30200'  | '0.1.302'
		'100.94.104.24000'   | '0.1.240'
		'62.77.104.9000'     | '0.1.90'
		'10.0.20348.1'       | null
		'100.103.104.30201'  | null
		'garbage'            | null
		null                 | null
	}

	@Unroll
	def "#device is bus-dependent: #dep"() {
		expect:
		GuestFacts.isBusDependent(device) == dep

		where:
		device                                        | dep
		'/dev/sda1'                                   | true
		'/dev/hdb'                                    | true
		'/dev/xvda2'                                  | true
		'/dev/disk/by-path/pci-0000:00:10.0-scsi-0:0' | true
		'/dev/disk/by-id/ata-VBOX_HARDDISK_1-part1'   | true
		'/dev/disk/by-id/dm-name-vg-root'             | false
		'/dev/mapper/rl-root'                         | false
		'UUID=00000000-0000-4000-8000-000000000000'   | false
		'/dev/vda1'                                   | false
		null                                          | false
	}

	def "guest fixtures hold no lab identifiers or secrets"() {
		expect:
		fixtureNames().collectMany { FixtureHygieneSpec.problems(it, text(it)) } == []
	}
}
