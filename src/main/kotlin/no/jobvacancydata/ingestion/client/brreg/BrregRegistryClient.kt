package no.jobvacancydata.ingestion.client.brreg

import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup

interface BrregRegistryClient {
	/**
	 * Looks up IDs in both registers; a one-item list is the single-organization lookup.
	 * Outcomes are per organization number: a failed ID keeps its peers' valid results
	 * and is reported as failed or invalid, never as a confirmed no-match.
	 */
	fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup>
}
