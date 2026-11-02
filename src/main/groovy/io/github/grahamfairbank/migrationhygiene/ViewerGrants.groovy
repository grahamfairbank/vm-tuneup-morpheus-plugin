package io.github.grahamfairbank.migrationhygiene

import com.morpheusdata.model.User

/**
 * Tab renderTemplate() gets no user, only show() does, and Morpheus calls them back to back. show() records here
 * what the render needs to know about the viewer (guest-scan grant, tenant); the render on the same thread reads
 * it a moment later. Keyed by tab, target id and thread, so one viewer's answer is never used for another
 * request. Plain field reads only: no service calls. A render that finds no answer renders the loading shell,
 * and the route then checks the user itself.
 *
 * 0.4.0: an answer is read once (the render consumes it) and lives 3 seconds, so a render that Morpheus ever runs
 * without its own show() can't reuse another viewer's answer. Never use this to authorize a route: routes get the
 * user from Morpheus and check it themselves.
 */
class ViewerGrants {

	static final long TTL_MS = 3_000L

	private final ShortMemo<String, Map> memo

	ViewerGrants(Closure<Long> clock = { System.currentTimeMillis() }) {
		this.memo = new ShortMemo<String, Map>(TTL_MS, 500, clock)
	}

	void record(String tab, Object id, User user) {
		if (id == null) return
		Map v = [guestScan: MigrationHygienePlugin.canGuestScan(user),
		         accountId: ServerFacts.safe { user?.account?.id },
		         master: ServerFacts.safe { user?.account?.masterAccount } == true]
		memo.put(key(tab, id), v)
	}

	/** The guest-scan grant as show() saw it on this thread in the last few seconds, else null (unknown). Consumes it. */
	Boolean granted(String tab, Object id) {
		return viewer(tab, id)?.guestScan as Boolean
	}

	/** [guestScan, accountId, master] as show() saw it, else null. Consumed: a second read returns null. */
	Map viewer(String tab, Object id) {
		if (id == null) return null
		String k = key(tab, id)
		Map v = memo.get(k)
		memo.remove(k)
		return v
	}

	/** True when the viewer is in the master tenant or in the tenant that owns the object. */
	static boolean mayView(Map viewer, Long ownerAccountId) {
		if (viewer == null) return false
		if (viewer.master == true) return true
		return viewer.accountId != null && ownerAccountId != null && (viewer.accountId as Long) == ownerAccountId
	}

	private static String key(String tab, Object id) {
		return "${tab}:${id}:${Thread.currentThread().id}".toString()
	}
}
