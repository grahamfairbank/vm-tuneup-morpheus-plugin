package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/** Every check the plugin knows: the Tier 1 checks (M1). Tier 2 guest checks come later. */
@CompileStatic
final class CheckCatalog {

	private CheckCatalog() {}

	static List<Check> all() {
		return validated(Tier1Catalog.ALL)
	}

	/** Refuses a catalog with duplicate ids, so results and waivers can be keyed by id. */
	static List<Check> validated(List<Check> checks) {
		Set<String> ids = new HashSet<String>()
		for (Check c : checks) {
			if (!ids.add(c.id)) throw new IllegalStateException("duplicate check id ${c.id}".toString())
		}
		return Collections.unmodifiableList(new ArrayList<Check>(checks))
	}
}
