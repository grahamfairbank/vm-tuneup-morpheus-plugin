package io.github.grahamfairbank.vmtuneup.core

import groovy.transform.CompileStatic

/**
 * Public sources the checks cite. HPE pages are the public build of HewlettPackard/morpheus-docs (branch dev-9.1);
 * each was checked to hold the cited content (docs/best-practices.md, "Sources"). The docs have no license, so
 * check texts paraphrase them and never copy.
 */
@CompileStatic
final class Refs {

	private Refs() {}

	private static final String DOCS = 'https://docs.morpheusdata.com/en/9.1/'

	static final Reference H1_ADVANCED_OPTIONS = new Reference('HPE: HVM VM Advanced Options', DOCS + 'infrastructure/clusters/hvm/vm_advanced_options.html')
	static final Reference H2_GUEST_OS_NOTES = new Reference('HPE: HVM Guest OS Notes', DOCS + 'infrastructure/clusters/hvm/guest_os_notes.html')
	static final Reference H3_SNAPSHOTS = new Reference('HPE: HVM Snapshots', DOCS + 'infrastructure/clusters/hvm/snapshots.html')
	static final Reference H4_VM_MIGRATION = new Reference('HPE: HVM VM Migration & Failover', DOCS + 'infrastructure/clusters/hvm/vm_migration.html')
	static final Reference H8_MIGRATIONS = new Reference('HPE: Migrations overview', DOCS + 'tools/migrations/overview.html')
	static final Reference H8_WINDOWS_MIGRATION = new Reference('HPE: Windows migration', DOCS + 'tools/migrations/windows_2022.html')
	static final Reference H9_RELEASE_9_1 = new Reference('HPE: Release notes 9.1.0', DOCS + 'release_notes/9_1_0.html')
	static final Reference H10_OPENAPI = new Reference('HPE Morpheus OpenAPI spec', 'https://github.com/HewlettPackard/morpheus-openapi')
	static final Reference H11_MANAGING_INSTANCES = new Reference('HPE: Managing Instances (Reconfigure, Eject Disk)', DOCS + 'provisioning/instances/managing_instances.html')
	static final Reference H13_COMPUTE = new Reference('HPE: Compute (converting discovered VMs to managed)', DOCS + 'infrastructure/compute/compute.html')
	static final Reference H13_UNMANAGE = new Reference('HPE: How to Unmanage (Convert To Managed)', DOCS + 'troubleshooting/How_To_Unmanage.html')
	static final Reference AGENT_OVERVIEW = new Reference('HPE: Morpheus Agent overview (Upgrade Agent)', DOCS + 'getting_started/functionality/agent/overview.html')
	static final Reference AGENT_INSTALL = new Reference('HPE: Agent install methods', DOCS + 'getting_started/functionality/agent/installMethods.html')

	static final Reference U1_LIBVIRT_DOMAIN = new Reference('libvirt: Domain XML format (disk discard)', 'https://libvirt.org/formatdomain.html')
	static final Reference IEEE_OUI = new Reference('IEEE: OUI registry (VMware OUIs)', 'https://standards-oui.ieee.org/oui/oui.txt')
	static final Reference M1_SERVER_REQUIREMENTS = new Reference('Microsoft: Windows Server hardware requirements', 'https://learn.microsoft.com/en-us/windows-server/get-started/hardware-requirements')
	static final Reference M_WIN11_REQUIREMENTS = new Reference('Microsoft: Windows 11 requirements', 'https://learn.microsoft.com/en-us/windows/whats-new/windows-11-requirements')
	static final Reference M_MBR2GPT = new Reference('Microsoft: MBR2GPT', 'https://learn.microsoft.com/en-us/windows/deployment/mbr-to-gpt')
}
