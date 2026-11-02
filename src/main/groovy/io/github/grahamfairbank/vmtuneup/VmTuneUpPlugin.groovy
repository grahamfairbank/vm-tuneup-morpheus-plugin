package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * VM Tune-Up: a read-only, "one and done" audit of VMs on HVM clusters for leftovers from VMware
 * migrations. Install it, scan, plan the fixes, then uninstall it.
 *
 * M1: the Tier 1 checks (Morpheus data only) on the server, instance and cluster tabs and in the fleet report.
 * 0.2.0: tabs never block page loads. They render a shell that the plugin script fills from the plugin's routes;
 * scans run with time budgets, cluster scans in the background (see {@link ScanService}).
 *
 * Community project: not affiliated with, endorsed or supported by HPE.
 */
@Slf4j
class VmTuneUpPlugin extends Plugin {

	static final String CODE = 'vm-tuneup'
	static final String NAME = 'VM Tune-Up'
	static final String VIEW_PERMISSION = 'vm-tuneup-view'
	/**
	 * For guest commands: the guest agent probe (a fixed no-op command, see {@link GuestAgentProbe}) and the Tier 2
	 * read-only commands of later milestones, which run as root/SYSTEM in the guest.
	 */
	static final String GUEST_SCAN_PERMISSION = 'vm-tuneup-guest-scan'
	/** The one write (0.7): fill in a VM's empty guest OS fields from a guest scan, after the user confirms. */
	static final String UPDATE_PERMISSION = 'vm-tuneup-update-records'

	private GuestAgentProbe guestProbe
	private Workers workers
	private ScanService scans

	@Override
	String getCode() { CODE }

	/** The probe shared by the tabs and the report, so they share its 5-minute cache. */
	synchronized GuestAgentProbe guestProbe(MorpheusContext context) {
		if (guestProbe == null || !guestProbe.morpheus.is(context)) guestProbe = new GuestAgentProbe(context)
		return guestProbe
	}

	/**
	 * The scan service shared by the tabs and the routes. Creating it starts no thread (pools start threads on
	 * first use), so a tab render may call this.
	 */
	synchronized ScanService scans(MorpheusContext context) {
		if (scans == null || !scans.morpheus.is(context)) {
			if (workers == null || workers.isShutdown()) workers = new Workers()
			scans = new ScanService(context, workers, guestProbe(context))
		}
		return scans
	}

	@Override
	void initialize() {
		this.setName(NAME)
		this.setDescription('Unofficial community plugin (not an HPE product): read-only audit of HVM VMs: migration leftovers and what to tune next')
		this.setAuthor('vm-tuneup community project')
		this.setSourceCodeLocationUrl('https://github.com/grahamfairbank/vm-tuneup-morpheus-plugin')
		this.setPermissions([
			new Permission('VM Tune-Up: View results', VIEW_PERMISSION, [Permission.AccessType.none, Permission.AccessType.read]),
			new Permission('VM Tune-Up: Run guest and host checks', GUEST_SCAN_PERMISSION, [Permission.AccessType.none, Permission.AccessType.full]),
			new Permission('VM Tune-Up: Update VM records', UPDATE_PERMISSION, [Permission.AccessType.none, Permission.AccessType.full])
		])
		this.registerProvider(new VmTuneUpReportProvider(this, morpheus))
		this.registerProvider(new VmTuneUpInstanceTabProvider(this, morpheus))
		this.registerProvider(new VmTuneUpServerTabProvider(this, morpheus))
		this.registerProvider(new VmTuneUpClusterTabProvider(this, morpheus))
		this.registerProvider(new VmTuneUpUiLoaderProvider(this, morpheus))
		// A plugin with a controller must bring its own renderer, or Plugin API 1.4.1 fails to load it
		// (DynamicTemplateLoader). The providers build their own renderers either way.
		this.setRenderer(new HandlebarsRenderer('renderer', this.getClassLoader() ?: this.class.classLoader))
		// Routes are dispatched from getControllers(); registerProvider() doesn't fill it.
		this.setControllers([new VmTuneUpController(this, morpheus)])
	}

	/** Stops every vm-tuneup-* thread; running scans are interrupted and their results dropped. */
	@Override
	synchronized void onDestroy() {
		try { workers?.shutdown() } catch (Throwable ignored) {}
		workers = null
		scans = null
	}

	/** The user's level for a plugin permission: 'none', 'read', 'full' or null when the role doesn't have it. */
	static String level(User user, String code) {
		try {
			return user?.permissions?.get(code) as String
		} catch (Throwable ignored) {
			return null
		}
	}

	/** Results are shown only to roles granted "VM Tune-Up: View results". */
	static boolean canView(User user) {
		return level(user, VIEW_PERMISSION) in ['read', 'full']
	}

	static boolean canGuestScan(User user) {
		return level(user, GUEST_SCAN_PERMISSION) == 'full'
	}

	static boolean canUpdate(User user) {
		return level(user, UPDATE_PERMISSION) == 'full'
	}
}
