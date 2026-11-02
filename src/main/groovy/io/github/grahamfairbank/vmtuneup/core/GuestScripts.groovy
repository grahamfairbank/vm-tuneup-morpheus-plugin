package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The two fixed, read-only Tier 2 guest scripts: one for Windows (PowerShell), one for Linux (POSIX sh). Each runs
 * once per VM and prints a small, versioned key=value block between markers, which {@link GuestFacts} parses.
 *
 * <p>Security rules (each one is asserted by GuestScriptsSpec):
 * <ul>
 *   <li>The scripts are constants. Nothing about the VM, the user or the request is ever put into them: there is no
 *       method that takes a parameter and returns a command.</li>
 *   <li>Read-only: they read WMI/CIM classes, the registry, files under /etc, /proc, /sys, /lib/modules and /boot, and
 *       list processes. They write nothing, install nothing, change no setting and open no network connection.
 *       The only exception is a tool's own temporary directory: dracut's {@code lsinitrd} creates an empty directory
 *       under /tmp and removes it when it exits.</li>
 *   <li>Bounded output: each value is cut to 200 printable ASCII characters, each script prints at most
 *       {@value #MAX_LINES} lines, and {@link GuestFacts} rejects anything over {@value #MAX_OUTPUT_CHARS} characters.</li>
 *   <li>They run as SYSTEM (Windows, through the QEMU guest agent) or root (Linux, through the Morpheus agent). Both
 *       report who they ran as ({@code system=} and {@code uid=}), so a scan that ran with fewer rights is flagged.</li>
 * </ul>
 *
 * <p>How each is invoked, and why:
 * <ul>
 *   <li>Windows: {@link #WINDOWS_COMMAND} = {@code powershell.exe -NoProfile -NonInteractive -EncodedCommand <base64>},
 *       where the base64 is the UTF-16LE of the compacted script. The command line holds only letters, digits,
 *       spaces, '-', '.', '+', '/' and '=', so it reaches PowerShell unchanged whether Morpheus's guest execution
 *       splits it on spaces into a program and arguments or hands it to cmd.exe, and no quoting rule of either
 *       can break it. {@code -NoProfile} skips profile scripts; {@code -NonInteractive} makes any prompt fail
 *       instead of hanging. {@code -ExecutionPolicy Bypass} is not used: the execution policy applies to script
 *       files, not to {@code -EncodedCommand}, and leaving it out avoids a flag that security tools treat as
 *       suspicious. The command stays under cmd.exe's 8191-character limit (asserted). The same argument list was
 *       run through QEMU guest-exec on a lab Windows Server 2022 VM and returned the full block as SYSTEM.</li>
 *   <li>Linux: {@link #LINUX_COMMAND} = {@code echo <base64> | base64 -d | sh}. One line with no quotes, so it
 *       survives any shell wrapper the Morpheus agent uses (sh -c, bash -c, sudo). {@code base64} is part of
 *       coreutils and BusyBox. {@link #LINUX_SCRIPT} is the same script as plain text, for showing to users.</li>
 * </ul>
 *
 * <p>The scripts in source form carry comments; {@link #compact} drops full-line comments, blank lines and
 * indentation before encoding (no line of either script depends on its indentation).
 */
@CompileStatic
final class GuestScripts {

	private GuestScripts() {}

	/** Output format version: the "v1" in "VTU-BEGIN v1 windows". Bump it when keys change meaning. */
	static final int FORMAT_VERSION = 1
	static final String BEGIN = 'VTU-BEGIN'
	static final String END = 'VTU-END'
	/** Lines each script prints at most between the markers. */
	static final int MAX_LINES = 150
	/** Longest value the scripts print. */
	static final int MAX_VALUE = 200
	/** Largest raw output {@link GuestFacts} accepts (the scripts print well under 20 000). */
	static final int MAX_OUTPUT_CHARS = 32768
	/** cmd.exe's command-line limit; the Windows command must stay below it. */
	static final int MAX_WINDOWS_COMMAND = 8191

	/** The Windows script, as reviewed. Runs on Windows PowerShell 4.0 or later (Windows Server 2012 R2 and newer). */
	static final String WINDOWS_SCRIPT = '''# VM Tune-Up guest scan v1 (Windows PowerShell 4.0 or later). Read-only: it reads WMI/CIM classes, the registry
# and file versions, and runs "w32tm /query" and "whoami"; it changes nothing, installs nothing and opens no
# network connection. Output: VTU-BEGIN v1 windows, key=value lines, VTU-END <count>.
$ErrorActionPreference = 'SilentlyContinue'
$ProgressPreference = 'SilentlyContinue'
$L = @()
function O($k, $v) {
  if ($null -eq $v) { return }
  $s = (([string]$v) -replace '[^\\x20-\\x7E]', '?').Trim()
  if ($s.Length -gt 200) { $s = $s.Substring(0, 200) }
  if ($script:L.Count -lt 150) { $script:L += ($k + '=' + $s) }
}
$o = Get-CimInstance Win32_OperatingSystem
O 'os.caption' $o.Caption
O 'os.build' $o.BuildNumber
O 'os.producttype' $o.ProductType
O 'os.arch' $o.OSArchitecture
$cv = Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion'
O 'os.edition' $cv.EditionID
O 'os.installtype' $cv.InstallationType
O 'os.displayversion' $cv.DisplayVersion
O 'os.domainrole' (Get-CimInstance Win32_ComputerSystem).DomainRole
O 'system' ([int]([string](whoami /user /fo csv /nh) -match 'S-1-5-18'))
$S = 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\'
foreach ($n in 'viostor', 'vioscsi', 'netkvm', 'VirtioSerial', 'balloon', 'pvscsi', 'vmxnet3ndis6', 'vm3dmp', 'vmhgfs', 'vmmouse', 'vmusbmouse', 'vmci', 'vmmemctl', 'VMTools', 'VGAuthService', 'QEMU-GA', 'QEMU Guest Agent VSS Provider', 'W32Time') {
  $p = Get-ItemProperty ($S + $n)
  $k = 'svc.' + ($n -replace ' ', '_')
  if ($null -eq $p) { O $k 'absent' } else { O $k $p.Start }
}
foreach ($n in 'VMTools', 'QEMU-GA', 'QEMU Guest Agent VSS Provider', 'W32Time') {
  $g = Get-Service -Name $n
  if ($g) { O ('run.' + ($n -replace ' ', '_')) $g.Status }
}
$q = [string](Get-ItemProperty ($S + 'QEMU-GA')).ImagePath
if ($q -match '(--block-rpcs|--blacklist|\\s-b)[= ]+(\\S+)') { O 'qga.blocked' $Matches[2] }
if ($q -match '(--allow-rpcs|\\s-a)[= ]+(\\S+)') { O 'qga.allowed' $Matches[2] }
foreach ($d in 'viostor', 'vioscsi', 'netkvm') {
  $f = Get-Item ($env:SystemRoot + '\\System32\\drivers\\' + $d + '.sys')
  if ($f) { $i = $f.VersionInfo; O ('drv.' + $d) ('{0}.{1}.{2}.{3}' -f $i.FileMajorPart, $i.FileMinorPart, $i.FileBuildPart, $i.FilePrivatePart) }
  O ('store.' + $d) @(Get-ChildItem ($env:SystemRoot + '\\System32\\DriverStore\\FileRepository') -Filter ($d + '.inf_*')).Count
}
$U = 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*', 'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
foreach ($e in (Get-ItemProperty $U)) {
  $dn = [string]$e.DisplayName
  if ($dn -match '^VMware Tools') { O 'pkg.vmwaretools' $e.DisplayVersion }
  elseif ($dn -match 'virtio-win') { O 'pkg.virtiowin' $e.DisplayVersion }
  elseif ($dn -match 'QEMU guest agent') { O 'pkg.qga' $e.DisplayVersion }
}
foreach ($v in @(Get-ChildItem 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\VSS\\Providers' | Select-Object -First 10)) {
  O 'vss.provider' (Get-ItemProperty $v.PSPath).'(default)'
}
O 'vmw.pcidevs' @(Get-ChildItem 'HKLM:\\SYSTEM\\CurrentControlSet\\Enum\\PCI' | Where-Object { $_.PSChildName -like 'VEN_15AD*' }).Count
O 'time.source' ([string](w32tm /query /source 2>$null | Select-Object -First 1))
O 'time.type' (Get-ItemProperty ($S + 'W32Time\\Parameters')).Type
'VTU-BEGIN v1 windows'
$L
'VTU-END ' + $L.Count'''

	/** The Linux script, as reviewed. POSIX sh (dash, bash, BusyBox ash). */
	static final String LINUX_SCRIPT = '''# VM Tune-Up guest scan v1 (Linux, POSIX sh). Read-only: it reads files and lists processes; it changes nothing,
# installs nothing and opens no network connection. Output: VTU-BEGIN v1 linux, key=value lines, VTU-END <count>.
PATH=/usr/sbin:/usr/bin:/sbin:/bin
LC_ALL=C
export PATH LC_ALL
o() { printf '%s=%s\\n' "$1" "$(printf '%s' "$2" | tr -cd '\\040-\\176' | cut -c1-200)"; }
t() { if command -v timeout >/dev/null 2>&1; then timeout 30 "$@"; else "$@"; fi; }
r() { sed -n "s/^$1=//p" /etc/os-release 2>/dev/null | head -n 1 | tr -d "\\"'"; }
has() { printf '%s\\n' "$1" | grep -q "$2"; }
body() {
o uid "$(id -u)"
o os.id "$(r ID)"
o os.version_id "$(r VERSION_ID)"
o os.name "$(r NAME)"
o os.pretty "$(r PRETTY_NAME)"
o os.like "$(r ID_LIKE)"
k=$(uname -r)
o os.kernel "$k"
o os.arch "$(uname -m)"
d=/lib/modules/$k
if [ -f "$d/modules.builtin" ]; then o builtin.read 1; else o builtin.read 0; fi
img=
for f in "/boot/initramfs-$k.img" "/boot/initrd.img-$k" "/boot/initrd-$k"; do
  if [ -f "$f" ]; then img=$f; break; fi
done
if [ -n "$img" ]; then o initrd.file found; else o initrd.file missing; fi
lst=
if command -v lsinitrd >/dev/null 2>&1; then
  o initrd.tool lsinitrd
  if [ -n "$img" ]; then lst=$(t lsinitrd "$img" 2>/dev/null); fi
  o initrd.entries "$(printf '%s\\n' "$lst" | grep -c .)"
elif [ -d /etc/initramfs-tools ]; then
  o initrd.tool initramfs-tools
  o initrd.modules "$(cat /etc/initramfs-tools/initramfs.conf /etc/initramfs-tools/conf.d/* 2>/dev/null | sed -n 's/^[[:space:]]*MODULES=//p' | tail -n 1 | tr -d "\\"'")"
  lst=$(grep -v '^[[:space:]]*#' /etc/initramfs-tools/modules 2>/dev/null | awk 'NF {print "/" $1 ".ko"}')
else o initrd.tool none; fi
for m in virtio_blk virtio_scsi virtio_pci virtio_net; do
  b=0; grep -q "/$m\\.ko" "$d/modules.builtin" 2>/dev/null && b=1
  o "builtin.$m" "$b"
  f=0; find "$d" -name "$m.ko*" 2>/dev/null | grep -q . && f=1
  o "module.$m" "$f"
  i=0; has "$lst" "/$m\\.ko" && i=1
  o "initrd.$m" "$i"
done
if [ -r /etc/fstab ]; then
  o fstab.read 1
  awk '$1 !~ /^#/ && NF >= 2 && ($1 ~ /^\\/dev\\/(sd|hd|xvd)[a-z]/ || $1 ~ /^\\/dev\\/disk\\/by-path\\// || $1 ~ /^\\/dev\\/disk\\/by-id\\/(ata|scsi|wwn|nvme)-/) {print $1 " " $2}' /etc/fstab 2>/dev/null | head -n 20 | while read -r a b; do o fstab.devname "$a $b"; done
else o fstab.read 0; fi
set -f
for w in $(cat /proc/cmdline 2>/dev/null); do
  case $w in root=*) o boot.root "${w#root=}" ;; resume=*) o boot.resume "${w#resume=}" ;; esac
done
set +f
P=$(ps -e -o comm= 2>/dev/null)
A=$(ps -e -o args= 2>/dev/null | grep '[q]emu-ga')
for p in open-vm-tools qemu-guest-agent; do
  v=
  if command -v rpm >/dev/null 2>&1; then v=$(rpm -q --qf '%{VERSION}' "$p" 2>/dev/null) || v=; fi
  if [ -z "$v" ] && command -v dpkg-query >/dev/null 2>&1; then
    s=$(dpkg-query -W -f='${Status} ${Version}' "$p" 2>/dev/null)
    case $s in *" installed "*) v=${s##* } ;; esac
  fi
  [ -n "$v" ] && o "pkg.$p" "$v"
done
if printf '%s\\n' "$P" | grep -qx vmtoolsd; then o vmw.running 1; else o vmw.running 0; fi
if [ -e /etc/vmware-tools/locations ] || [ -e /usr/bin/vmware-uninstall-tools.pl ]; then o vmw.tarball 1; else o vmw.tarball 0; fi
o vmw.mods "$(awk '{print $1}' /proc/modules 2>/dev/null | grep -xE 'vmw_balloon|vmw_pvscsi|vmw_vmci|vmxnet3|vmhgfs|vmwgfx|vmw_vsock_vmci_transport' | tr '\\n' ',' | sed 's/,$//')"
if printf '%s\\n' "$P" | grep -qx qemu-ga; then o qga.running 1; else o qga.running 0; fi
if [ -e /dev/virtio-ports/org.qemu.guest_agent.0 ]; then o qga.channel 1; else o qga.channel 0; fi
C=$(cat /etc/sysconfig/qemu-ga /etc/default/qemu-guest-agent /etc/qemu/qemu-ga.conf 2>/dev/null | grep -v '^[[:space:]]*#')
C="$C
$A"
for x in guest-fsfreeze-freeze guest-exec; do
  al=$(printf '%s\\n' "$C" | grep -iE 'allow-rpcs|allowed-rpcs')
  bl=$(printf '%s\\n' "$C" | grep -iE 'block-rpcs|blacklist')
  if [ -n "$al" ]; then
    if has "$al" "$x"; then v=allowed; else v=blocked; fi
  elif has "$bl" "$x"; then v=blocked; else v=default; fi
  o "qga.${x#guest-}" "$v"
done
if [ -x /etc/qemu/fsfreeze-hook ] || [ -x /etc/qemu-ga/fsfreeze-hook ]; then o qga.hook 1; else o qga.hook 0; fi
o time.clocksource "$(cat /sys/devices/system/clocksource/clocksource0/current_clocksource 2>/dev/null)"
o time.available "$(cat /sys/devices/system/clocksource/clocksource0/available_clocksource 2>/dev/null)"
if command -v timedatectl >/dev/null 2>&1; then
  y=$(timedatectl show -p NTPSynchronized --value 2>/dev/null)
  [ -z "$y" ] && y=$(timedatectl status 2>/dev/null | sed -n 's/.*synchronized: *//p' | head -n 1)
  o time.synced "$y"
fi
for p in chronyd ntpd systemd-timesyn; do
  if printf '%s\\n' "$P" | grep -qx "$p"; then o time.service "$p"; fi
done
}
B=$(body | head -n 150)
printf 'VTU-BEGIN v1 linux\\n%s\\nVTU-END %s\\n' "$B" "$(printf '%s\\n' "$B" | grep -c =)"'''

	/** What each script runs, in plain words, for the UI and the docs ("What the guest scan runs"). */
	static final List<String> WINDOWS_COMMANDS = Collections.unmodifiableList([
		'Get-CimInstance Win32_OperatingSystem: Caption, BuildNumber, ProductType (1 workstation, 2 domain controller, 3 server), OSArchitecture.',
		'Get-CimInstance Win32_ComputerSystem: DomainRole (4 backup domain controller, 5 primary domain controller).',
		'Registry HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion: EditionID, InstallationType (Server, Server Core, Client), DisplayVersion.',
		'whoami /user: whether the scan runs as SYSTEM (S-1-5-18).',
		'Registry HKLM\\SYSTEM\\CurrentControlSet\\Services\\<name>: whether each service exists and its Start value (0 boot, 1 system, 2 automatic, 3 manual, 4 disabled) for viostor, vioscsi, netkvm, VirtioSerial, balloon, pvscsi, vmxnet3ndis6, vm3dmp, vmhgfs, vmmouse, vmusbmouse, vmci, vmmemctl, VMTools, VGAuthService, QEMU-GA, QEMU Guest Agent VSS Provider and W32Time; and the QEMU-GA ImagePath for blocked or allowed guest agent commands.',
		'Get-Service: running state of VMTools, QEMU-GA, QEMU Guest Agent VSS Provider and W32Time.',
		'File version of %SystemRoot%\\System32\\drivers\\viostor.sys, vioscsi.sys and netkvm.sys; whether viostor, vioscsi and netkvm are staged in the driver store (DriverStore\\FileRepository).',
		'Registry Uninstall keys (64- and 32-bit): DisplayVersion of VMware Tools, virtio-win guest tools and QEMU guest agent.',
		'Registry HKLM\\SYSTEM\\CurrentControlSet\\Services\\VSS\\Providers: names of the registered VSS providers (at most 10).',
		'Registry HKLM\\SYSTEM\\CurrentControlSet\\Enum\\PCI: how many VMware (vendor 15AD) PCI devices Windows still remembers.',
		'w32tm /query /source and the W32Time Parameters Type value: where Windows gets its time.'
	])

	static final List<String> LINUX_COMMANDS = Collections.unmodifiableList([
		'id -u: whether the scan runs as root.',
		'/etc/os-release: ID, VERSION_ID, NAME, PRETTY_NAME, ID_LIKE (read with sed; the file is never sourced or run).',
		'uname -r and uname -m: running kernel and architecture.',
		'/lib/modules/<kernel>/modules.builtin and a find for the module files: whether virtio_blk, virtio_scsi, virtio_pci and virtio_net are built into the kernel or available as modules.',
		'Initramfs of the running kernel: on dracut systems (RHEL family, SUSE) lsinitrd lists /boot/initramfs-<kernel>.img (30-second limit); on Debian and Ubuntu, the MODULES setting in /etc/initramfs-tools/initramfs.conf and conf.d, and the modules listed in /etc/initramfs-tools/modules.',
		'/etc/fstab: entries that mount by device name or bus path (/dev/sdX, /dev/hdX, /dev/xvdX, /dev/disk/by-path, /dev/disk/by-id/ata-, scsi-, wwn-, nvme-), at most 20.',
		'/proc/cmdline: the root= and resume= values.',
		'ps -e: whether vmtoolsd, qemu-ga, chronyd, ntpd or systemd-timesyncd run; the qemu-ga command line for allowed or blocked commands.',
		'rpm -q or dpkg-query -W: installed versions of open-vm-tools and qemu-guest-agent.',
		'/etc/vmware-tools/locations or /usr/bin/vmware-uninstall-tools.pl: a VMware Tools tarball install; /proc/modules: loaded VMware kernel modules.',
		'/dev/virtio-ports/org.qemu.guest_agent.0: the guest agent channel; /etc/sysconfig/qemu-ga, /etc/default/qemu-guest-agent, /etc/qemu/qemu-ga.conf: whether guest-fsfreeze-freeze and guest-exec are allowed; /etc/qemu/fsfreeze-hook.',
		'/sys/devices/system/clocksource/clocksource0: current and available clock sources; timedatectl: whether NTP has synchronized the clock.'
	])

	/** The exact command line for Windows guest execution. */
	static final String WINDOWS_COMMAND = 'powershell.exe -NoProfile -NonInteractive -EncodedCommand ' + encodeUtf16(compact(WINDOWS_SCRIPT))

	/** The exact command for Linux (Morpheus agent). */
	static final String LINUX_COMMAND = 'echo ' + encodeUtf8(compact(LINUX_SCRIPT)) + ' | base64 -d | sh'

	/** SHA-256 of the compacted scripts: log these, never the output, to show which script ran. */
	static final String WINDOWS_SHA256 = sha256(compact(WINDOWS_SCRIPT))
	static final String LINUX_SHA256 = sha256(compact(LINUX_SCRIPT))

	/** The command for one OS family ('windows' or 'linux'), or null for anything else. */
	static String commandFor(String osFamily) {
		if (osFamily == 'windows') return WINDOWS_COMMAND
		if (osFamily == 'linux') return LINUX_COMMAND
		return null
	}

	/** Drops full-line comments, blank lines and indentation; keeps every other line as is. */
	static String compact(String script) {
		List<String> out = new ArrayList<String>()
		for (String line : script.split('\n')) {
			String t = line.trim()
			if (t.isEmpty() || t.startsWith('#')) continue
			out.add(t)
		}
		return out.join('\n')
	}

	static String encodeUtf16(String s) {
		return Base64.encoder.encodeToString(s.getBytes(StandardCharsets.UTF_16LE))
	}

	static String encodeUtf8(String s) {
		return Base64.encoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))
	}

	static String sha256(String s) {
		byte[] h = MessageDigest.getInstance('SHA-256').digest(s.getBytes(StandardCharsets.UTF_8))
		StringBuilder b = new StringBuilder()
		for (byte x : h) b.append(String.format('%02x', x & 0xff))
		return b.toString()
	}
}
