package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * What the Tier 2 guest scan found inside one VM: the parsed output of a {@link GuestScripts} script.
 *
 * <p>Parsing never throws. Output that is empty, too large, without markers, cut off, of another format version
 * or another OS gives a result with {@link #isOk()} false and a plain-words {@link #problem}; every Tier 2 check
 * then reports UNKNOWN. Inside a good block, a missing key means "unknown" (the accessors return null), never
 * "no" or "absent": the script prints "absent" or 0 when it actually looked.
 *
 * <p>Text outside the markers is ignored (shell banners, PowerShell progress records, stderr), so a block can
 * be found in noisy output; lines inside the block that aren't key=value are ignored too, up to
 * {@value #MAX_JUNK_LINES}, beyond which the block counts as malformed.
 */
@CompileStatic
final class GuestFacts {

	enum Parse {
		OK,
		/** No output at all. */
		EMPTY,
		/** More than {@link GuestScripts#MAX_OUTPUT_CHARS} characters: rejected unread. */
		TOO_LARGE,
		/** No VTU-BEGIN line: the script didn't run, or something else answered. */
		NO_MARKERS,
		/** VTU-BEGIN without VTU-END, or fewer lines than VTU-END announces: the output was cut off. */
		TRUNCATED,
		/** A format version this plugin doesn't know. */
		UNSUPPORTED_VERSION,
		/** The block came from the other OS's script. */
		WRONG_OS,
		/** Markers present, but the content doesn't add up. */
		MALFORMED
	}

	static final int MAX_JUNK_LINES = 5
	static final Pattern KEY = ~/^[A-Za-z0-9_.\-]{1,64}$/
	static final Pattern BEGIN_LINE = ~/^VTU-BEGIN v(\d{1,3}) (windows|linux)$/
	static final Pattern END_LINE = ~/^VTU-END (\d{1,4})$/

	/** VirtIO and VMware service names the Windows script reads. */
	static final List<String> VMWARE_WINDOWS_DRIVERS = Collections.unmodifiableList(
		['pvscsi', 'vmxnet3ndis6', 'vm3dmp', 'vmhgfs', 'vmmouse', 'vmusbmouse', 'vmci', 'vmmemctl'])

	final Parse status
	/** Why parsing failed, in plain words; null when OK. */
	final String problem
	/** 'windows' or 'linux' (from the BEGIN line); null when there was no usable block. */
	final String os
	final Integer version
	/** Every key with its values in output order (most keys have one value; lists repeat the key). */
	final Map<String, List<String>> values

	private GuestFacts(Parse status, String problem, String os, Integer version, Map<String, List<String>> values) {
		this.status = status
		this.problem = problem
		this.os = os
		this.version = version
		this.values = Collections.unmodifiableMap(values ?: new LinkedHashMap<String, List<String>>())
	}

	static GuestFacts failed(Parse status, String problem, String os = null, Integer version = null) {
		return new GuestFacts(status, problem, os, version, null)
	}

	/**
	 * Parses raw script output.
	 * @param expectedOs 'windows' or 'linux' when the caller knows which script it ran (a block from the other
	 *        script is then WRONG_OS); null to accept either
	 */
	static GuestFacts parse(String output, String expectedOs = null) {
		try {
			return doParse(output, expectedOs)
		} catch (Throwable t) {
			return failed(Parse.MALFORMED, 'The guest scan output could not be read.')
		}
	}

	private static GuestFacts doParse(String output, String expectedOs) {
		if (output == null || output.trim().isEmpty()) return failed(Parse.EMPTY, 'The guest scan returned no output.')
		if (output.length() > GuestScripts.MAX_OUTPUT_CHARS) {
			return failed(Parse.TOO_LARGE, "The guest scan output is larger than ${GuestScripts.MAX_OUTPUT_CHARS} characters, so it was not read.".toString())
		}
		String[] lines = output.replace('\r', '').split('\n')
		int i = 0
		Matcher begin = null
		for (; i < lines.length; i++) {
			Matcher m = BEGIN_LINE.matcher(lines[i].trim())
			if (m.matches()) { begin = m; break }
		}
		if (begin == null) return failed(Parse.NO_MARKERS, 'The guest scan output has no VM Tune-Up block (the script did not run, or another program answered).')
		int version = Integer.parseInt(begin.group(1))
		String os = begin.group(2)
		if (version != GuestScripts.FORMAT_VERSION) {
			return failed(Parse.UNSUPPORTED_VERSION, "The guest scan output is format v${version}; this plugin reads v${GuestScripts.FORMAT_VERSION}.".toString(), os, version)
		}
		if (expectedOs != null && expectedOs != os) {
			return failed(Parse.WRONG_OS, "Expected the ${expectedOs} scan, got the ${os} one.".toString(), os, version)
		}
		Map<String, List<String>> values = new LinkedHashMap<String, List<String>>()
		int kv = 0
		int junk = 0
		Integer announced = null
		for (i = i + 1; i < lines.length; i++) {
			String line = lines[i].trim()
			if (line.isEmpty()) continue
			Matcher end = END_LINE.matcher(line)
			if (end.matches()) { announced = Integer.parseInt(end.group(1)); break }
			if (line.startsWith(GuestScripts.BEGIN) || line.startsWith(GuestScripts.END)) {
				return failed(Parse.MALFORMED, 'The guest scan output has a second or broken marker line.', os, version)
			}
			int eq = line.indexOf('=')
			String key = eq > 0 ? line.substring(0, eq) : null
			if (key == null || !KEY.matcher(key).matches()) {
				if (++junk > MAX_JUNK_LINES) return failed(Parse.MALFORMED, 'The guest scan block holds lines that are not key=value.', os, version)
				continue
			}
			if (++kv > GuestScripts.MAX_LINES) return failed(Parse.MALFORMED, "The guest scan block has more than ${GuestScripts.MAX_LINES} lines.".toString(), os, version)
			String value = line.substring(eq + 1).trim()
			if (value.length() > GuestScripts.MAX_VALUE) value = value.substring(0, GuestScripts.MAX_VALUE)
			List<String> list = values.get(key)
			if (list == null) { list = new ArrayList<String>(); values.put(key, list) }
			list.add(value)
		}
		if (announced == null) return failed(Parse.TRUNCATED, 'The guest scan output was cut off (no end marker).', os, version)
		if (announced > kv) return failed(Parse.TRUNCATED, "The guest scan output was cut off (${kv} of ${announced} lines).".toString(), os, version)
		if (announced < kv) return failed(Parse.MALFORMED, "The guest scan block has ${kv} lines but announces ${announced}.".toString(), os, version)
		return new GuestFacts(Parse.OK, null, os, version, values)
	}

	boolean isOk() { status == Parse.OK }

	boolean isWindows() { os == 'windows' }

	boolean isLinux() { os == 'linux' }

	// ---- generic access ----

	/** The first value of a key; null when the key is missing or its value is empty. */
	String get(String key) {
		List<String> l = values.get(key)
		if (l == null || l.isEmpty()) return null
		String v = l.get(0)
		return v == null || v.isEmpty() ? null : v
	}

	/** Every non-empty value of a key, in order; empty when missing. */
	List<String> all(String key) {
		List<String> l = values.get(key)
		if (l == null) return Collections.<String>emptyList()
		return Collections.unmodifiableList(l.findAll { String s -> s != null && !s.isEmpty() } as List<String>)
	}

	Integer integer(String key) {
		String v = get(key)
		if (v == null || !(v ==~ /-?\d{1,9}/)) return null
		return Integer.valueOf(v)
	}

	/** 1/yes/true → true, 0/no/false → false, anything else (or missing) → null. */
	Boolean flag(String key) {
		String v = get(key)?.toLowerCase()
		if (v in ['1', 'yes', 'true']) return Boolean.TRUE
		if (v in ['0', 'no', 'false']) return Boolean.FALSE
		return null
	}

	/** Comma- or space-separated value as a list; empty when missing. */
	List<String> list(String key) {
		String v = get(key)
		if (v == null) return Collections.<String>emptyList()
		return Collections.unmodifiableList(v.split(/[,\s]+/).findAll { String s -> !s.isEmpty() } as List<String>)
	}

	// ---- Windows ----

	String getCaption() { get('os.caption') }

	Integer getBuild() { integer('os.build') }

	/** 1 workstation, 2 domain controller, 3 server. */
	Integer getProductType() { integer('os.producttype') }

	/** 0-1 workstation, 2-3 member or standalone server, 4 backup DC, 5 primary DC. */
	Integer getDomainRole() { integer('os.domainrole') }

	String getEditionId() { get('os.edition') }

	/** 'Server', 'Server Core' or 'Client'. */
	String getInstallationType() { get('os.installtype') }

	String getDisplayVersion() { get('os.displayversion') }

	/** True when 64-bit (Windows OSArchitecture or Linux uname -m); null when unknown. */
	Boolean getIs64Bit() {
		String a = get('os.arch')?.toLowerCase()
		if (a == null) return null
		return a.contains('64')
	}

	/** True when ProductType says domain controller or DomainRole is 4 or 5; null when neither was read. */
	Boolean getDomainController() {
		Integer pt = productType
		Integer dr = domainRole
		if (pt == null && dr == null) return null
		return pt == 2 || dr == 4 || dr == 5
	}

	/** True when the Windows scan ran as SYSTEM, false when not, null when unknown. */
	Boolean getRanAsSystem() { flag('system') }

	private static String svcKey(String name) { 'svc.' + name.replace(' ', '_') }

	/** True when the service key exists, false when the script found it absent, null when not read. */
	Boolean serviceExists(String name) {
		String v = get(svcKey(name))
		if (v == null) return null
		return v != 'absent'
	}

	/** The service Start value (0 boot ... 4 disabled); null when absent or not read. */
	Integer serviceStart(String name) {
		String v = get(svcKey(name))
		return v != null && v ==~ /\d/ ? Integer.valueOf(v) : null
	}

	/** Running state from Get-Service ('Running', 'Stopped', ...); null when not reported. */
	String serviceState(String name) { get('run.' + name.replace(' ', '_')) }

	/** File version of a VirtIO driver (viostor, vioscsi, netkvm), e.g. 100.103.104.30200; null when absent. */
	String driverVersion(String driver) { get('drv.' + driver) }

	/** True when the driver package is in the driver store, false when not, null when not read. */
	Boolean driverStaged(String driver) {
		Integer n = integer('store.' + driver)
		return n == null ? null : n > 0
	}

	/**
	 * The virtio-win release a driver file version comes from: virtio-win builds number their drivers
	 * major.minor.target.(release*100), so 100.103.104.30200 is 0.1.302 (observed on the lab with
	 * virtio-win-guest-tools 0.1.302). Null when the version doesn't have that shape.
	 */
	static String virtioReleaseOf(String fileVersion) {
		if (fileVersion == null) return null
		Matcher m = (fileVersion =~ /^\d+\.\d+\.\d+\.(\d{3,6})$/)
		if (!m.matches()) return null
		int p = Integer.parseInt(m.group(1))
		if (p % 100 != 0 || p == 0) return null
		return '0.1.' + (p.intdiv(100))
	}

	/** The virtio-win guest tools version from the Uninstall keys (e.g. 0.1.302), else from the driver files. */
	String getVirtioWinRelease() {
		String pkg = get('pkg.virtiowin')
		if (pkg != null && pkg ==~ /0\.1\.\d+/) return pkg
		for (String d : ['viostor', 'netkvm', 'vioscsi']) {
			String r = virtioReleaseOf(driverVersion(d))
			if (r != null) return r
		}
		return null
	}

	String getVmwareToolsVersion() { get('pkg.vmwaretools') }

	String getQgaVersion() { windows ? get('pkg.qga') : get('pkg.qemu-guest-agent') }

	List<String> getVssProviders() { all('vss.provider') }

	Integer getVmwarePciDevices() { integer('vmw.pcidevs') }

	/** VMware driver services still registered (not absent, not disabled), with their Start value. */
	Map<String, Integer> getVmwareDrivers() {
		Map<String, Integer> out = new LinkedHashMap<String, Integer>()
		for (String n : VMWARE_WINDOWS_DRIVERS) {
			Integer s = serviceStart(n)
			if (s != null && s != 4) out.put(n, s)
		}
		return out
	}

	String getTimeSource() { get('time.source') }

	/** W32Time Parameters Type: NTP, NT5DS, AllSync, NoSync. */
	String getTimeType() { get('time.type') }

	List<String> getQgaBlockedRpcs() { windows ? list('qga.blocked') : Collections.<String>emptyList() }

	List<String> getQgaAllowedRpcs() { windows ? list('qga.allowed') : Collections.<String>emptyList() }

	// ---- Linux ----

	Integer getUid() { integer('uid') }

	String getOsId() { get('os.id')?.toLowerCase() }

	String getVersionId() { get('os.version_id') }

	String getOsName() { get('os.name') }

	String getPrettyName() { get('os.pretty') }

	List<String> getIdLike() { list('os.like').collect { String s -> s.toLowerCase() } as List<String> }

	String getKernel() { get('os.kernel') }

	String getArch() { get('os.arch') }

	Boolean getBuiltinRead() { flag('builtin.read') }

	Boolean builtin(String module) { flag('builtin.' + module) }

	Boolean moduleOnDisk(String module) { flag('module.' + module) }

	/** For dracut: listed in the initramfs image. For initramfs-tools: listed in /etc/initramfs-tools/modules. */
	Boolean inInitrd(String module) { flag('initrd.' + module) }

	/** 'found' or 'missing'. */
	String getInitrdFile() { get('initrd.file') }

	/** 'lsinitrd', 'initramfs-tools' or 'none'. */
	String getInitrdTool() { get('initrd.tool') }

	/** Lines lsinitrd listed (a readable image has hundreds). */
	Integer getInitrdEntries() { integer('initrd.entries') }

	/** initramfs-tools MODULES setting ('most' when not set: the documented default). */
	String getInitrdModules() {
		if (initrdTool != 'initramfs-tools') return null
		String v = get('initrd.modules')
		return v == null ? 'most' : v.toLowerCase()
	}

	Boolean getFstabRead() { flag('fstab.read') }

	/** fstab entries mounted by device name or bus path, as "device mountpoint". */
	List<String> getFstabDeviceNames() { all('fstab.devname') }

	String getBootRoot() { get('boot.root') }

	String getBootResume() { get('boot.resume') }

	String getOpenVmToolsVersion() { get('pkg.open-vm-tools') }

	Boolean getVmtoolsRunning() { flag('vmw.running') }

	Boolean getVmwareTarball() { flag('vmw.tarball') }

	List<String> getVmwareModules() { list('vmw.mods') }

	Boolean getQgaRunning() { flag('qga.running') }

	Boolean getQgaChannel() { flag('qga.channel') }

	/** 'allowed', 'blocked' or 'default' (no allow or block list). */
	String getQgaFreeze() { get('qga.fsfreeze-freeze') }

	String getQgaExec() { get('qga.exec') }

	Boolean getQgaHook() { flag('qga.hook') }

	String getClocksource() { get('time.clocksource') }

	List<String> getAvailableClocksources() { list('time.available') }

	Boolean getNtpSynced() { flag('time.synced') }

	List<String> getTimeServices() { all('time.service') }

	/** A device name that changes when the disk bus changes: /dev/sdX, /dev/hdX, /dev/xvdX or a by-path/by-id bus path. */
	static boolean isBusDependent(String device) {
		if (device == null) return false
		String d = device.trim()
		return d ==~ /\/dev\/(sd|hd|xvd)[a-z].*/ || d.startsWith('/dev/disk/by-path/') ||
			d ==~ /\/dev\/disk\/by-id\/(ata|scsi|wwn|nvme)-.*/
	}
}
