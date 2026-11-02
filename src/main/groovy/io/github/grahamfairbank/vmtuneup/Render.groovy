package io.github.grahamfairbank.vmtuneup

import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel

/** Renders one of the plugin's templates. Templates escape every value ({{ }} only). */
class Render {

	static final String VM = 'hbs/vtu-vm'
	static final String CLUSTER = 'hbs/vtu-cluster'
	static final String REPORT = 'hbs/vtu-report'
	static final String PRINT = 'hbs/vtu-print'

	static HTMLResponse render(Renderer<?> renderer, String template, Map model) {
		ViewModel<Map> vm = new ViewModel<>()
		vm.object = [pluginName: VmTuneUpPlugin.NAME, disclaimer: Views.DISCLAIMER, aboutChecks: Views.ABOUT_CHECKS] + (model ?: [:])
		return renderer.renderTemplate(template, vm)
	}

	static final String SCAN_FAILED = 'The scan could not complete. Check the Morpheus log for "vm-tuneup" messages.'
}
