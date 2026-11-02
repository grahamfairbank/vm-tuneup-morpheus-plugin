package io.github.grahamfairbank.migrationhygiene.core

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/** A public source for a check: HPE docs first, then upstream or vendor docs. */
@CompileStatic
@EqualsAndHashCode
@ToString(includeNames = true)
final class Reference {

	final String label
	final String url

	Reference(String label, String url) {
		this.label = Check.text(label, 'reference.label')
		this.url = Check.text(url, 'reference.url')
		if (!this.url.startsWith('https://')) throw new IllegalArgumentException("reference url '${url}' must be https")
	}

	/** True for HPE's public Morpheus/VME docs and API spec. */
	boolean isHpe() {
		return url.startsWith('https://docs.morpheusdata.com/') || url.startsWith('https://github.com/HewlettPackard/')
	}
}
