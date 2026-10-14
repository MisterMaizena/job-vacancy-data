package no.jobvacancydata.ingestion.client.brreg

import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup

interface BrregRegistryClient {
	/**
	 * Looks up Orgnr. in both BRReg registers (main unit and sub unit). Accepts one-item list for single-organization
	 * lookup, or a list with more than one item for bulk lookup.
	 */
	fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup>
}
