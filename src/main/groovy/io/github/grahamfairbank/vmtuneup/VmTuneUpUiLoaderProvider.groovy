package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Loads the plugin's browser script (vm-tuneup.js) on every page for users who may view results. The
 * tabs' content comes from the plugin routes through this script. Inline scripts in tab HTML can be dropped by
 * the Morpheus UI, so, as in com-morpheus-plugin, the script is a plugin asset included through a global UI
 * component with the page's CSP nonce. show() and renderTemplate() make no service calls.
 */
@Slf4j
class VmTuneUpUiLoaderProvider extends AbstractGlobalUIComponentProvider {

	VmTuneUpPlugin plugin
	MorpheusContext morpheus

	VmTuneUpUiLoaderProvider(VmTuneUpPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override String getCode() { 'vm-tuneup-ui-loader' }
	@Override String getName() { 'VM Tune-Up script loader' }
	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }

	@Override
	Boolean show(User user, Account account) {
		return VmTuneUpPlugin.canView(user)
	}

	@Override
	HTMLResponse renderTemplate(User user, Account account) {
		try {
			ViewModel<Map> vm = new ViewModel<>()
			vm.object = [version: plugin?.version ?: 'dev']
			return getRenderer().renderTemplate('hbs/vtu-loader', vm)
		} catch (Throwable e) {
			log.warn("vm-tuneup: script loader failed: ${e}")
			return HTMLResponse.success('')
		}
	}
}
