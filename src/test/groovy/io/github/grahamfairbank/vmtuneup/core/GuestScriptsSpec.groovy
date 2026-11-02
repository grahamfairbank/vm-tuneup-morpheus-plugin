package io.github.grahamfairbank.vmtuneup.core

import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** The guest scripts are fixed, read-only and bounded; any change to them must be deliberate (hash pinned here). */
class GuestScriptsSpec extends Specification {

	/** Update these only after reviewing the script change (and re-running it on a lab guest). */
	static final String WINDOWS_SHA = '461df850b39d49888bb54612ac7449efa11a4958998a4893564d9bfe19b9031f'
	static final String LINUX_SHA = '5fdeeeb521d2202dff677012590a905694a79b33b6963cd52053c0fcac3704fa'

	def "the scripts are the reviewed ones"() {
		expect:
		GuestScripts.WINDOWS_SHA256 == WINDOWS_SHA
		GuestScripts.LINUX_SHA256 == LINUX_SHA
	}

	def "commands are constants: no public method builds a command from a parameter"() {
		when:
		List<Method> builders = GuestScripts.declaredMethods.findAll { Method m ->
			Modifier.isPublic(m.modifiers) && !m.synthetic && m.parameterCount > 0 && m.returnType == String &&
				!(m.name in ['compact', 'encodeUtf16', 'encodeUtf8', 'sha256', 'commandFor'])
		}

		then:
		builders == []
		GuestScripts.commandFor('windows').is(GuestScripts.WINDOWS_COMMAND)
		GuestScripts.commandFor('linux').is(GuestScripts.LINUX_COMMAND)
		GuestScripts.commandFor('solaris') == null
		GuestScripts.commandFor(null) == null
	}

	def "the Windows command line is one PowerShell call with only safe characters, under cmd.exe's limit"() {
		given:
		String c = GuestScripts.WINDOWS_COMMAND

		expect:
		c.startsWith('powershell.exe -NoProfile -NonInteractive -EncodedCommand ')
		!c.contains('ExecutionPolicy')
		c ==~ /[A-Za-z0-9 .\-+\/=]+/
		c.length() < GuestScripts.MAX_WINDOWS_COMMAND
		c.split(' ').length == 5
		new String(Base64.decoder.decode(c.split(' ')[4]), StandardCharsets.UTF_16LE) == GuestScripts.compact(GuestScripts.WINDOWS_SCRIPT)
	}

	def "the Linux command is one quote-free pipeline that decodes to the script"() {
		given:
		String c = GuestScripts.LINUX_COMMAND
		String[] p = c.split(' ')

		expect:
		c ==~ /echo [A-Za-z0-9+\/=]+ \| base64 -d \| sh/
		new String(Base64.decoder.decode(p[1]), StandardCharsets.UTF_8) == GuestScripts.compact(GuestScripts.LINUX_SCRIPT)
	}

	def "compact drops comments, blank lines and indentation only"() {
		expect:
		GuestScripts.compact('# c\n\n  a=1\n\tb | c # tail\n  # x\n') == 'a=1\nb | c # tail'
	}

	@Unroll
	def "the Windows script never uses '#word'"() {
		expect:
		!(GuestScripts.WINDOWS_SCRIPT =~ /(?i)\b${word}\b/).find()

		where:
		word << ['Set-ItemProperty', 'New-Item', 'Remove-Item', 'Remove-ItemProperty', 'Set-Content', 'Add-Content', 'Out-File',
		         'Set-Service', 'Stop-Service', 'Start-Service', 'Restart-Service', 'Start-Process', 'Invoke-Expression', 'iex',
		         'Invoke-WebRequest', 'Invoke-RestMethod', 'Net\\.WebClient', 'DownloadString', 'Restart-Computer', 'Stop-Computer',
		         'reg add', 'reg delete', 'sc config', 'bcdedit', 'Install-', 'Uninstall-', 'Enable-', 'Disable-', 'Set-', 'msiexec',
		         'w32tm /config', 'w32tm /resync', 'pnputil', 'dism']
	}

	def "the Windows script only redirects error output to null"() {
		expect:
		GuestScripts.compact(GuestScripts.WINDOWS_SCRIPT).readLines().every { String l ->
			!l.contains('>') || l.replace('2>$null', '').indexOf('>') < 0
		}
	}

	@Unroll
	def "the Linux script never runs '#word'"() {
		expect:
		!(GuestScripts.compact(GuestScripts.LINUX_SCRIPT) =~ /(?m)(^|[\s;|&(`$])${word}\b/).find()

		where:
		word << ['rm', 'mv', 'cp', 'dd', 'tee', 'ln', 'chmod', 'chown', 'mkdir', 'touch', 'curl', 'wget', 'nc', 'ssh', 'sudo', 'su',
		         'apt', 'apt-get', 'dnf', 'yum', 'zypper', 'rpm -i', 'rpm -U', 'dpkg -i', 'systemctl', 'service', 'dracut',
		         'update-initramfs', 'modprobe', 'insmod', 'rmmod', 'mount', 'eval', 'source', 'exec', 'kill', 'reboot', 'shutdown']
	}

	def "the Linux script writes nowhere: every redirect goes to /dev/null, and it sources no file"() {
		given:
		List<String> lines = GuestScripts.compact(GuestScripts.LINUX_SCRIPT).readLines()

		expect:
		lines.every { String l -> l.replaceAll(/[12]?>\s?\/dev\/null|2>&1/, '').replaceAll(/'[^']*'/, "''").indexOf('>') < 0 }
		lines.every { String l -> !(l ==~ /^\.\s.*/) && !l.contains('; . ') }
	}

	def "both scripts print the versioned markers and cap their output"() {
		expect:
		GuestScripts.WINDOWS_SCRIPT.contains("'VTU-BEGIN v${GuestScripts.FORMAT_VERSION} windows'")
		GuestScripts.LINUX_SCRIPT.contains("VTU-BEGIN v${GuestScripts.FORMAT_VERSION} linux")
		GuestScripts.WINDOWS_SCRIPT.contains("-lt ${GuestScripts.MAX_LINES}")
		GuestScripts.LINUX_SCRIPT.contains("head -n ${GuestScripts.MAX_LINES}")
		GuestScripts.WINDOWS_SCRIPT.contains("-gt ${GuestScripts.MAX_VALUE}")
		GuestScripts.LINUX_SCRIPT.contains("cut -c1-${GuestScripts.MAX_VALUE}")
	}

	def "every command the scripts run is documented"() {
		expect:
		GuestScripts.WINDOWS_COMMANDS.size() >= 10
		GuestScripts.LINUX_COMMANDS.size() >= 10
		['Win32_OperatingSystem', 'Win32_ComputerSystem', 'whoami', 'w32tm', 'Get-Service', 'DriverStore', 'Uninstall', 'VSS'].every { String w ->
			GuestScripts.WINDOWS_COMMANDS.any { it.contains(w) }
		}
		['id -u', 'os-release', 'uname', 'modules.builtin', 'lsinitrd', 'initramfs-tools', 'fstab', 'cmdline', 'ps -e', 'rpm', 'dpkg-query',
		 'clocksource', 'timedatectl', 'qemu-ga'].every { String w -> GuestScripts.LINUX_COMMANDS.any { it.contains(w) } }
	}

	@IgnoreIf({ !new File('/bin/sh').canExecute() || System.getProperty('os.name').toLowerCase().contains('windows') })
	def "the Linux script runs under sh here and its output parses"() {
		when:
		Process p = new ProcessBuilder('/bin/sh', '-c', GuestScripts.LINUX_COMMAND).redirectErrorStream(false).start()
		p.outputStream.close()
		boolean done = p.waitFor(90, TimeUnit.SECONDS)
		String out = p.inputStream.getText('UTF-8')
		GuestFacts g = GuestFacts.parse(out, 'linux')

		then:
		done
		g.ok
		g.uid != null
		g.kernel != null
		g.values.size() >= 20
		out.length() < GuestScripts.MAX_OUTPUT_CHARS
	}
}
