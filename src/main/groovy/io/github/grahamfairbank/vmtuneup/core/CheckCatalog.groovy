package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/** Every check the plugin knows: Tier 1 (Morpheus data), then Tier 2 (read-only, inside the guest). */
@CompileStatic
final class CheckCatalog {

	private CheckCatalog() {}

	/** Tier 1 then Tier 2: the fix order across both tiers. */
	static final List<Check> ORDER = Collections.unmodifiableList(new ArrayList<Check>(Tier1Catalog.ALL + Tier2Catalog.ALL))

	static List<Check> all() {
		return validated(ORDER)
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
