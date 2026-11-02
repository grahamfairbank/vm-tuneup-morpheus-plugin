package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * Maps what the guest scan says about the OS to a Morpheus OS type, for filling in empty OS fields
 * (serverOs, osType, platform, platformVersion). Uses an embedded copy of the Morpheus OS type catalog
 * (GET /api/library/operating-systems/os-types on a 9.1.0.1 lab appliance, 123 entries; only the 64-bit and
 * Windows codes this mapping can return are kept), so nothing is looked up at run time.
 *
 * <p>Returns null when there's no confident match: an unknown distribution, a 32-bit Linux, a Windows build the
 * catalog has no code for (for example Windows Server 2012 R2 or 8.1), or a scan that didn't read the OS.
 * A match with {@code exact == false} is the catalog's generic code for a known family (for example ubuntu.64
 * for an Ubuntu release the catalog doesn't list); callers may choose to fill only exact matches.
 */
@CompileStatic
final class OsIdentity {

	private OsIdentity() {}

	@CompileStatic
	@EqualsAndHashCode
	@ToString(includeNames = true)
	static final class Match {
		/** Morpheus OsType code, e.g. windows.server.2022 or rocky.9.64. Look it up by code before saving. */
		final String code
		/** The catalog name, e.g. "Rocky Linux 9 64-bit". */
		final String name
		/** ComputeServer.osType: 'windows' or 'linux'. */
		final String osType
		/** ComputeServer.platform: 'windows', or the catalog category for Linux (rocky, ubuntu, redhat, ...). */
		final String platform
		/** ComputeServer.platformVersion: the Windows catalog version (2022, 10, 11) or the Linux VERSION_ID. */
		final String platformVersion
		/** False when this is the family's generic code, not one for this exact version. */
		final boolean exact

		Match(String code, String name, String osType, String platform, String platformVersion, boolean exact) {
			this.code = code
			this.name = name
			this.osType = osType
			this.platform = platform
			this.platformVersion = platformVersion
			this.exact = exact
		}
	}

	/** code → [name, osType, platform (catalog category), osVersion], from the lab catalog. */
	static final Map<String, List<String>> CATALOG
	static {
		Map<String, List<String>> m = new LinkedHashMap<String, List<String>>()
		List<List<String>> rows = [
			['windows.server.2008', 'windows server 2008', 'windows', 'windows', '2008'],
			['windows.server.2008.r2', 'windows server 2008 R2', 'windows', 'windows', '2008'],
			['windows.server.2012', 'windows server 2012', 'windows', 'windows', '2012'],
			['windows.server.2016', 'windows server 2016', 'windows', 'windows', '2016'],
			['windows.server.2019', 'windows server 2019', 'windows', 'windows', '2019'],
			['windows.server.2022', 'windows server 2022', 'windows', 'windows', '2022'],
			['windows.server.2022.dc.core', 'windows server 2022 datacenter core', 'windows', 'windows', '2022'],
			['windows.server.2022.dc.desktop', 'windows server 2022 datacenter desktop', 'windows', 'windows', '2022'],
			['windows.server.2022.std.core', 'windows server 2022 standard core', 'windows', 'windows', '2022'],
			['windows.server.2022.std.desktop', 'windows server 2022 standard desktop', 'windows', 'windows', '2022'],
			['windows.server.2025', 'windows server 2025', 'windows', 'windows', '2025'],
			['windows.7', 'windows 7', 'windows', 'windows', '7'],
			['windows.7.64', 'windows 7 64-bit', 'windows', 'windows', '7'],
			['windows.8', 'windows 8', 'windows', 'windows', '8'],
			['windows.8.64', 'windows 8 64-bit', 'windows', 'windows', '8'],
			['windows.10', 'windows 10', 'windows', 'windows', '10'],
			['windows.10.64', 'windows 10 64-bit', 'windows', 'windows', '10'],
			['windows.11', 'windows 11', 'windows', 'windows', '11'],
			['windows.11.64', 'windows 11 64-bit', 'windows', 'windows', '11'],
			['almalinux.8.64', 'Almalinux Linux 8 64-bit', 'linux', 'almalinux', '8'],
			['almalinux.9.64', 'Almalinux Linux 9 64-bit', 'linux', 'almalinux', '9'],
			['almalinux.10.64', 'Almalinux Linux 10 64-bit', 'linux', 'almalinux', '10'],
			['amazonlinux.2.64', 'amazon linux 2 64-bit', 'linux', 'amazonlinux', '2'],
			['cent.64', 'centOS 64-bit', 'linux', 'centos', 'all'],
			['cent.6.64', 'centOS 6 64-bit', 'linux', 'centos', '6'],
			['cent.7.64', 'centOS 7 64-bit', 'linux', 'centos', '7'],
			['cent.8.64', 'centOS 8 64-bit', 'linux', 'centos', '8'],
			['cent.8.stream.64', 'centOS 8 Stream 64-bit', 'linux', 'centos', '8'],
			['cent.9.64', 'centOS 9 64-bit', 'linux', 'centos', '9'],
			['cent.10.64', 'centOS 10 64-bit', 'linux', 'centos', '10'],
			['debian.64', 'debian 64-bit', 'linux', 'debian', 'all'],
			['debian.6.64', 'debian 6 64-bit', 'linux', 'debian', '6'],
			['debian.7.64', 'debian 7 64-bit', 'linux', 'debian', '7'],
			['debian.8.64', 'debian 8 64-bit', 'linux', 'debian', '8'],
			['debian.10.64', 'debian 10 64-bit', 'linux', 'debian', '10'],
			['debian.11.64', 'debian 11 64-bit', 'linux', 'debian', '11'],
			['debian.12.64', 'debian 12 64-bit', 'linux', 'debian', '12'],
			['debian.13.64', 'debian 13 64-bit', 'linux', 'debian', '13'],
			['fedora.64', 'fedora 64-bit', 'linux', 'fedora', 'all'],
			['opensuse.64', 'opensuse 64-bit', 'linux', 'opensuse', 'all'],
			['oracle.linux.64', 'oracle linux 64-bit', 'linux', 'oracle', 'all'],
			['oracle.8.64', 'oracle linux 8 64-bit', 'linux', 'oracle', '8'],
			['oracle.9.64', 'oracle linux 9 64-bit', 'linux', 'oracle', '9'],
			['redhat.64', 'redhat 64-bit', 'linux', 'redhat', 'all'],
			['redhat.6.64', 'redhat 6 64-bit', 'linux', 'redhat', '6'],
			['redhat.7.64', 'redhat 7 64-bit', 'linux', 'redhat', '7'],
			['redhat.8.64', 'redhat 8 64-bit', 'linux', 'redhat', '8'],
			['redhat.9.64', 'redhat 9 64-bit', 'linux', 'redhat', '9'],
			['redhat.10.64', 'redhat 10 64-bit', 'linux', 'redhat', '10'],
			['rocky.8.64', 'Rocky Linux 8 64-bit', 'linux', 'rocky', '8'],
			['rocky.9.64', 'Rocky Linux 9 64-bit', 'linux', 'rocky', '9'],
			['rocky.10.64', 'Rocky Linux 10 64-bit', 'linux', 'rocky', '10'],
			['suse.64', 'suse enterprise 64-bit', 'linux', 'suse', 'all'],
			['suse.11.64', 'suse enterprise 11 64-bit', 'linux', 'suse', '11'],
			['suse.12.64', 'suse enterprise 12 64-bit', 'linux', 'suse', '12'],
			['suse.15.64', 'opensuse 15 64-bit', 'linux', 'opensuse', '15'],
			['ubuntu.64', 'ubuntu 64-bit', 'linux', 'ubuntu', 'all'],
			['ubuntu.12.04.64', 'ubuntu 12 64-bit', 'linux', 'ubuntu', '12.04'],
			['ubuntu.13.10.64', 'ubuntu 13 64-bit', 'linux', 'ubuntu', '13.10'],
			['ubuntu.14.04.64', 'ubuntu 14 64-bit', 'linux', 'ubuntu', '14.04'],
			['ubuntu.15.10.64', 'ubuntu 15 64-bit', 'linux', 'ubuntu', '15.10'],
			['ubuntu.16.04.64', 'ubuntu 16 64-bit', 'linux', 'ubuntu', '16.04'],
			['ubuntu.18.04.64', 'ubuntu 18.04 64-bit', 'linux', 'ubuntu', '18.04'],
			['ubuntu.20.04.64', 'ubuntu 20.04 64-bit', 'linux', 'ubuntu', '20.04'],
			['ubuntu.22.04.64', 'ubuntu 22.04 64-bit', 'linux', 'ubuntu', '22.04'],
			['ubuntu.24.04.64', 'ubuntu 24.04 64-bit', 'linux', 'ubuntu', '24.04'],
			['ubuntu.26.04.64', 'ubuntu 26.04 64-bit', 'linux', 'ubuntu', '26.04']
		]
		for (List<String> r : rows) m.put(r[0], Collections.unmodifiableList(r.subList(1, 5)))
		CATALOG = Collections.unmodifiableMap(m)
	}

	/** Windows Server builds (RTM build numbers) → catalog code. 9600 (2012 R2) has no code. */
	static final Map<Integer, String> SERVER_BUILDS = Collections.unmodifiableMap([
		(6001): 'windows.server.2008', (6002): 'windows.server.2008', (6003): 'windows.server.2008',
		(7600): 'windows.server.2008.r2', (7601): 'windows.server.2008.r2',
		(9200): 'windows.server.2012',
		(14393): 'windows.server.2016', (17763): 'windows.server.2019', (20348): 'windows.server.2022',
		(26100): 'windows.server.2025'
	] as Map<Integer, String>)

	/** Linux os-release ID → [code prefix, generic code or null]. */
	static final Map<String, List<String>> LINUX_FAMILIES = Collections.unmodifiableMap([
		ubuntu   : ['ubuntu.', 'ubuntu.64'],
		debian   : ['debian.', 'debian.64'],
		rhel     : ['redhat.', 'redhat.64'],
		rocky    : ['rocky.', null],
		almalinux: ['almalinux.', null],
		centos   : ['cent.', 'cent.64'],
		ol       : ['oracle.', 'oracle.linux.64'],
		sles     : ['suse.', 'suse.64'],
		amzn     : ['amazonlinux.', null],
		fedora   : [null, 'fedora.64']
	] as Map<String, List<String>>)

	/** The Morpheus OS type for a guest scan, or null when there's no confident match. */
	static Match match(GuestFacts g) {
		if (g == null || !g.ok) return null
		if (g.windows) return windows(g)
		if (g.linux) return linux(g)
		return null
	}

	static Match windows(GuestFacts g) {
		Integer build = g.build
		Integer pt = g.productType
		if (build == null || pt == null) return null
		boolean is64 = g.is64Bit != Boolean.FALSE
		String code = null
		if (pt == 1) {
			String base = build >= 22000 ? 'windows.11' : build >= 10240 ? 'windows.10' : build == 9200 ? 'windows.8' :
				(build == 7600 || build == 7601) ? 'windows.7' : null
			if (base == null) return null
			code = is64 ? base + '.64' : base
		} else if (pt == 2 || pt == 3) {
			code = SERVER_BUILDS.get(build)
			if (code == 'windows.server.2022') code = server2022(g)
		}
		return code == null ? null : of(code, null, true)
	}

	/** The edition-specific 2022 code when edition and installation type are known; the generic one otherwise. */
	private static String server2022(GuestFacts g) {
		String ed = (g.editionId ?: '').toLowerCase()
		String it = (g.installationType ?: '').toLowerCase()
		String e = ed.startsWith('serverstandard') ? 'std' : ed.startsWith('serverdatacenter') ? 'dc' : null
		String i = it == 'server core' ? 'core' : it == 'server' ? 'desktop' : null
		return e != null && i != null ? "windows.server.2022.${e}.${i}".toString() : 'windows.server.2022'
	}

	static Match linux(GuestFacts g) {
		String id = g.osId
		if (id == null) return null
		if (id == 'opensuse-leap' || id == 'opensuse-tumbleweed' || id == 'opensuse') {
			String major = major(g.versionId)
			return id == 'opensuse-leap' && major == '15' ? of('suse.15.64', g.versionId, true) : of('opensuse.64', g.versionId, false)
		}
		List<String> fam = LINUX_FAMILIES.get(id)
		if (fam == null) return null
		String arch = (g.arch ?: '').toLowerCase()
		if (!arch.isEmpty() && !arch.contains('64')) return null
		String version = g.versionId
		String prefix = fam[0]
		String generic = fam[1]
		String code = null
		if (prefix != null && version != null) {
			if (id == 'ubuntu') code = prefix + version + '.64'
			else if (id == 'centos' && major(version) == '8' && (g.osName ?: '').toLowerCase().contains('stream')) code = 'cent.8.stream.64'
			else code = prefix + major(version) + '.64'
		}
		// The catalog's suse.15.64 is "opensuse 15"; SUSE Linux Enterprise 15 gets the generic SUSE code.
		if (id == 'sles' && code == 'suse.15.64') code = null
		if (code != null && CATALOG.containsKey(code)) return of(code, version, true)
		return generic != null ? of(generic, version, false) : null
	}

	private static String major(String version) {
		if (version == null) return null
		int dot = version.indexOf('.')
		return dot < 0 ? version : version.substring(0, dot)
	}

	private static Match of(String code, String linuxVersion, boolean exact) {
		List<String> row = CATALOG.get(code)
		if (row == null) return null
		String platformVersion = row[1] == 'windows' ? row[3] : linuxVersion
		return new Match(code, row[0], row[1], row[2], platformVersion, exact)
	}
}
