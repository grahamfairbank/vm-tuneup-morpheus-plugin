package io.github.grahamfairbank.vmtuneup.core

import spock.lang.Specification

class CheckSpec extends Specification {

	static Map valid() {
		[id: 'T1-DISK-BUS', title: 'Disks on an emulated bus', tier: Tier.ONE, severity: Severity.WARNING, appliesTo: AppliesTo.WINDOWS,
		 evidence: 'volume diskMode and controller type',
		 ifYouLeaveIt: 'Disk I/O goes through emulated SATA or IDE, which is slower than VirtIO.',
		 ifYouFixIt: [summary: 'Switch the disk bus to VirtIO.', downtime: Downtime.COLD_RECONFIGURE, risk: Risk.MEDIUM,
		              reversible: true, prerequisites: ['Install the VirtIO drivers in the guest.']],
		 category: Category.RECOMMENDATION, howInVme: 'Instance > Actions > Reconfigure',
		 references: [new Reference('HPE: Managing Instances', 'https://docs.morpheusdata.com/en/9.1/provisioning/instances/managing_instances.html')]]
	}

	def "a complete definition builds, with defaults"() {
		when:
		Check c = new Check(valid())

		then:
		c.id == 'T1-DISK-BUS'
		c.tier == Tier.ONE
		c.waivable
		c.infoPenalty == 0
		c.ifYouFixIt.downtime == Downtime.COLD_RECONFIGURE
		c.ifYouFixIt.reversible
		c.ifYouFixIt.prerequisites == ['Install the VirtIO drivers in the guest.']
	}

	def "enum values may be given as text"() {
		expect:
		new Check(valid() + [severity: 'critical', tier: 'ONE', appliesTo: 'linux']).severity == Severity.CRITICAL
	}

	def "prerequisites can't be changed after building"() {
		when:
		new Check(valid()).ifYouFixIt.prerequisites << 'more'

		then:
		thrown(UnsupportedOperationException)
	}

	def "incomplete or inconsistent definitions are refused: #why"() {
		when:
		new Check(valid() + change)

		then:
		thrown(IllegalArgumentException)

		where:
		why                              | change
		'no id'                          | [id: null]
		'bad id'                         | [id: 'disk-bus']
		'id prefix vs tier'              | [tier: Tier.TWO]
		'no title'                       | [title: ' ']
		'no severity'                    | [severity: null]
		'unknown severity'               | [severity: 'fatal']
		'no evidence'                    | [evidence: '']
		'no leave-it text'               | [ifYouLeaveIt: null]
		'no fix impact'                  | [ifYouFixIt: null]
		'no downtime'                    | [ifYouFixIt: valid().ifYouFixIt + [downtime: null]]
		'no risk'                        | [ifYouFixIt: valid().ifYouFixIt + [risk: null]]
		'reversible not stated'          | [ifYouFixIt: valid().ifYouFixIt + [reversible: null]]
		'no fix summary'                 | [ifYouFixIt: valid().ifYouFixIt + [summary: null]]
		'info penalty on a warning'      | [infoPenalty: 1]
		'info penalty above 2'           | [severity: Severity.INFO, infoPenalty: 3]
		'negative info penalty'          | [severity: Severity.INFO, infoPenalty: -1]
		'no category'                    | [category: null]
		'recommendation without UI path' | [howInVme: ' ']
		'consideration without reason'   | [category: Category.CONSIDERATION]
		'no references'                  | [references: []]
		'non-https reference'            | [references: [[label: 'x', url: 'http://example.com']]]
	}

	def "catalog refuses duplicate ids"() {
		when:
		CheckCatalog.validated([new Check(valid()), new Check(valid())])

		then:
		thrown(IllegalStateException)
	}

	def "the catalog holds the Tier 1 and Tier 2 checks, each fully described"() {
		when:
		List<Check> all = CheckCatalog.all()

		then:
		all*.id as Set == ['T1-DISK-BUS', 'T1-NIC-MODEL', 'T1-CHIPSET', 'T1-VMWARE-MAC', 'T1-OS-TYPE-MISMATCH', 'T1-FIRMWARE',
			'T1-GA-CHANNEL', 'T1-GA-STATUS', 'T1-MORPHEUS-AGENT', 'T1-SNAPSHOTS', 'T1-DISK-ALLOCATION', 'T1-DISCOVERED',
			'T1-ISO-ATTACHED', 'T1-HA-MOBILITY', 'T1-NESTED-VIRT', 'T1-CPU-TOPOLOGY',
			'T2-VIRTIO-READY', 'T2-VMWARE-TOOLS', 'T2-GA-FEATURES', 'T2-TIME-SYNC', 'T2-VIRTIO-VERSION', 'T2-DC-VMGENID'] as Set
		all.every { (it.tier == Tier.ONE) == it.id.startsWith('T1-') && it.references && it.ifYouFixIt.summary && it.ifYouLeaveIt }
		all.every { it.category == Category.RECOMMENDATION ? it.howInVme : it.whyConsideration }
		and: 'HPE public docs come first'
		all.every { Check c -> c.references.findIndexOf { !it.hpe } < 0 || c.references.findLastIndexOf { it.hpe } < c.references.findIndexOf { !it.hpe } }
		all.every { it.references.any { it.hpe } }
		and: 'never recommend hand-editing host or libvirt files'
		all.every { Check c -> !(c.howInVme ?: '').toLowerCase().contains('virsh') && !(c.howInVme ?: '').contains('/etc/libvirt') }
	}

	def "bus switches carry the ordering rule for both OS families"() {
		expect:
		[Tier1Catalog.DISK_BUS, Tier1Catalog.CHIPSET].every { Check c ->
			c.ifYouFixIt.prerequisites.contains(Tier1Catalog.WINDOWS_BUS_RULE) && c.ifYouFixIt.prerequisites.contains(Tier1Catalog.LINUX_BUS_RULE) &&
				c.ifYouFixIt.prerequisites.indexOf(Tier1Catalog.SWITCH_LAST) == c.ifYouFixIt.prerequisites.size() - 1
		}
	}

	def "check text is US English"() {
		given:
		String all = CheckCatalog.all().collect { [it.title, it.evidence, it.ifYouLeaveIt, it.ifYouFixIt.summary, it.howInVme,
			it.whyConsideration] + it.ifYouFixIt.prerequisites + it.unverified }.flatten().findAll().join(' ')

		expect:
		!(all =~ /(?i)\b(colour|behaviour|licence|organis|optimis|centre|favour|analys(e|ing))/)
	}
}
