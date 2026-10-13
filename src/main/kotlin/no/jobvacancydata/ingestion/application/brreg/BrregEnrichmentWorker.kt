package no.jobvacancydata.ingestion.application.brreg

import no.jobvacancydata.ingestion.client.brreg.BrregApiContract
import no.jobvacancydata.ingestion.client.brreg.BrregRegistryClient

/**
 * Non-persistent BRREG enrichment flow: resolves each organization number against the
 * main-entity and subunit registers and derives one explicit status per organization.
 * Lookup uses exact organization-number identity only; malformed IDs are reported as
 * rejected rather than guessed at, and failures never discard valid results.
 */
class BrregEnrichmentWorker(
	private val client: BrregRegistryClient,
) {
	fun enrich(organizationNumbers: Collection<String>): BrregEnrichmentOutcome {
		val (wellFormed, rejected) = organizationNumbers
			.partition { BrregApiContract.ORGANIZATION_NUMBER_REGEX.matches(it) }
		val lookups = client.lookup(wellFormed.distinct())
		return BrregEnrichmentOutcome(
			organizations = lookups.map(::toEnrichment),
			rejectedOrganizationNumbers = rejected.distinct(),
		)
	}

	private fun toEnrichment(lookup: BrregOrganizationLookup): BrregOrganizationEnrichment {
		val status = when {
			lookup.mainEntity is BrregLookupResult.Failed ||
				lookup.subunit is BrregLookupResult.Failed -> BrregEnrichmentStatus.FAILED

			lookup.mainEntity is BrregLookupResult.Invalid ||
				lookup.subunit is BrregLookupResult.Invalid -> BrregEnrichmentStatus.INVALID

			lookup.mainEntity is BrregLookupResult.NotFound &&
				lookup.subunit is BrregLookupResult.NotFound -> BrregEnrichmentStatus.NOT_FOUND

			else -> BrregEnrichmentStatus.ENRICHED
		}
		return BrregOrganizationEnrichment(
			organizationNumber = lookup.organizationNumber,
			status = status,
			mainEntity = lookup.mainEntity,
			subunit = lookup.subunit,
		)
	}
}
