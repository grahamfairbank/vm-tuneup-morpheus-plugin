package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification
import spock.lang.Unroll

import static io.github.grahamfairbank.vmtuneup.core.GuestFactsSpec.facts

class OsIdentitySpec extends Specification {

	static GuestFacts win(Map<String, Object> kv) { scan('windows', kv) }

	static GuestFacts lin(Map<String, Object> kv) { scan('linux', kv) }

	static GuestFacts scan(String os, Map<String, Object> kv) {
		List<String> lines = kv.collect { k, v -> "${k}=${v}".toString() }
		GuestFacts.parse("VTU-BEGIN v1 ${os}\n${lines.join('\n')}\nVTU-END ${lines.size()}\n")
	}

	@Unroll
	def "fixture #name maps to #code"() {
		when:
		OsIdentity.Match m = OsIdentity.match(facts(name))

		then:
		m?.code == code
		m?.osType == osType
		m?.platform == platform
		m?.platformVersion == version

		where:
		name                               | code                             | osType    | platform  | version
		'windows-2022-migrated-real'       | 'windows.server.2022.std.desktop' | 'windows' | 'windows' | '2022'
		'windows-2022-ready'               | 'windows.server.2022.dc.core'    | 'windows' | 'windows' | '2022'
		'windows-2019-vmware-tools'        | 'windows.server.2019'            | 'windows' | 'windows' | '2019'
		'windows-10-client'                | 'windows.10.64'                  | 'windows' | 'windows' | '10'
		'windows-2022-dc'                  | 'windows.server.2022.std.desktop' | 'windows' | 'windows' | '2022'
		'rocky-9-prepped-real'             | 'rocky.9.64'                     | 'linux'   | 'rocky'   | '9.6'
		'ubuntu-22.04-not-ready'           | 'ubuntu.22.04.64'                | 'linux'   | 'ubuntu'  | '22.04'
		'ubuntu-24.04-vmware-nonroot-real' | 'ubuntu.24.04.64'                | 'linux'   | 'ubuntu'  | '24.04'
		'garbage'                          | null                             | null      | null      | null
	}

	@Unroll
	def "Windows build #build, product type #pt, #edition #install → #code"() {
		expect:
		OsIdentity.match(win(['os.build': build, 'os.producttype': pt, 'os.arch': arch, 'os.edition': edition, 'os.installtype': install]))?.code == code

		where:
		build | pt | arch     | edition            | install       | code
		26100 | 3  | '64-bit' | 'ServerDatacenter' | 'Server'      | 'windows.server.2025'
		20348 | 3  | '64-bit' | 'ServerTurbine'    | 'Server'      | 'windows.server.2022'
		20348 | 3  | '64-bit' | 'ServerStandard'   | 'Server Core' | 'windows.server.2022.std.core'
		20348 | 3  | '64-bit' | 'ServerDatacenter' | 'Server'      | 'windows.server.2022.dc.desktop'
		17763 | 2  | '64-bit' | 'ServerStandard'   | 'Server'      | 'windows.server.2019'
		14393 | 3  | '64-bit' | 'ServerStandard'   | 'Server'      | 'windows.server.2016'
		9600  | 3  | '64-bit' | 'ServerStandard'   | 'Server'      | null
		9200  | 3  | '64-bit' | 'ServerStandard'   | 'Server'      | 'windows.server.2012'
		7601  | 3  | '64-bit' | 'ServerStandard'   | 'Server'      | 'windows.server.2008.r2'
		25398 | 3  | '64-bit' | 'ServerStandard'   | 'Server Core' | null
		22631 | 1  | '64-bit' | 'Professional'     | 'Client'      | 'windows.11.64'
		19045 | 1  | '32-bit' | 'Professional'     | 'Client'      | 'windows.10'
		9600  | 1  | '64-bit' | 'Professional'     | 'Client'      | null
		7601  | 1  | '64-bit' | 'Professional'     | 'Client'      | 'windows.7.64'
	}

	@Unroll
	def "Linux #id #version (#arch) → #code exact #exact"() {
		when:
		OsIdentity.Match m = OsIdentity.match(lin(['os.id': id, 'os.version_id': version, 'os.arch': arch, 'os.name': name]))

		then:
		m?.code == code
		m == null || m.exact == exact

		where:
		id              | version   | arch      | name                   | code               | exact
		'ubuntu'        | '20.04'   | 'x86_64'  | 'Ubuntu'               | 'ubuntu.20.04.64'  | true
		'ubuntu'        | '23.10'   | 'x86_64'  | 'Ubuntu'               | 'ubuntu.64'        | false
		'debian'        | '12'      | 'x86_64'  | 'Debian GNU/Linux'     | 'debian.12.64'     | true
		'debian'        | '9'       | 'x86_64'  | 'Debian GNU/Linux'     | 'debian.64'        | false
		'rhel'          | '9.4'     | 'x86_64'  | 'Red Hat Enterprise Linux' | 'redhat.9.64'  | true
		'rhel'          | '5.11'    | 'x86_64'  | 'Red Hat Enterprise Linux' | 'redhat.64'    | false
		'rocky'         | '10.0'    | 'x86_64'  | 'Rocky Linux'          | 'rocky.10.64'      | true
		'rocky'         | '7'       | 'x86_64'  | 'Rocky Linux'          | null               | false
		'almalinux'     | '9.5'     | 'aarch64' | 'AlmaLinux'            | 'almalinux.9.64'   | true
		'centos'        | '8'       | 'x86_64'  | 'CentOS Stream'        | 'cent.8.stream.64' | true
		'centos'        | '7'       | 'x86_64'  | 'CentOS Linux'         | 'cent.7.64'        | true
		'ol'            | '9.3'     | 'x86_64'  | 'Oracle Linux Server'  | 'oracle.9.64'      | true
		'sles'          | '15.5'    | 'x86_64'  | 'SLES'                 | 'suse.64'          | false
		'sles'          | '12.5'    | 'x86_64'  | 'SLES'                 | 'suse.12.64'       | true
		'opensuse-leap' | '15.5'    | 'x86_64'  | 'openSUSE Leap'        | 'suse.15.64'       | true
		'amzn'          | '2'       | 'x86_64'  | 'Amazon Linux'         | 'amazonlinux.2.64' | true
		'fedora'        | '40'      | 'x86_64'  | 'Fedora Linux'         | 'fedora.64'        | false
		'arch'          | null      | 'x86_64'  | 'Arch Linux'           | null               | false
		'ubuntu'        | '22.04'   | 'i686'    | 'Ubuntu'               | null               | false
	}

	def "every code the mapping can return is in the embedded catalog"() {
		expect:
		OsIdentity.SERVER_BUILDS.values().every { OsIdentity.CATALOG.containsKey(it) }
		OsIdentity.LINUX_FAMILIES.values().every { it[1] == null || OsIdentity.CATALOG.containsKey(it[1]) }
		OsIdentity.CATALOG.values().every { it.size() == 4 && it[1] in ['windows', 'linux'] }
	}

	def "no match without a usable scan or OS facts"() {
		expect:
		OsIdentity.match(null) == null
		OsIdentity.match(GuestFacts.parse('nothing')) == null
		OsIdentity.match(win([:])) == null
		OsIdentity.match(lin([uid: 0])) == null
	}
}
