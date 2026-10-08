package no.jobvacancydata.ingestion.client.brreg

import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup

interface BrregRegistryClient {
	/** Looks up IDs in both registers; a one-item list is the single-organization lookup. */
	fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup>
}
