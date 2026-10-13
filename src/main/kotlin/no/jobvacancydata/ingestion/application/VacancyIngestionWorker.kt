package no.jobvacancydata.ingestion.application

import no.jobvacancydata.ingestion.application.brreg.BrregEnrichmentOutcome
import no.jobvacancydata.ingestion.application.brreg.BrregEnrichmentWorker
import no.jobvacancydata.ingestion.application.nav.FeedPageCursor
import no.jobvacancydata.ingestion.application.nav.NavCollectionOutcome
import no.jobvacancydata.ingestion.application.nav.NavCollectionWorker
import no.jobvacancydata.ingestion.application.nav.NavItemOutcome

/**
 * Convenience flow connecting NAV collection and BRREG enrichment for one run: every
 * distinct employer organization number in the collected vacancies is enriched. Both
 * stages remain independently callable through their own workers, and nothing is
 * persisted — the outcome is for a later persistence layer to consume.
 */
class VacancyIngestionWorker(
	private val navCollectionWorker: NavCollectionWorker,
	private val brregEnrichmentWorker: BrregEnrichmentWorker,
) {
	fun collectAndEnrich(initialCursor: FeedPageCursor? = null): VacancyIngestionOutcome {
		val nav = navCollectionWorker.collect(initialCursor)
		val organizationNumbers = nav.items
			.filterIsInstance<NavItemOutcome.Collected>()
			.mapNotNull { it.detail.adContent?.employer?.orgnr }
		val enrichment = brregEnrichmentWorker.enrich(organizationNumbers)
		return VacancyIngestionOutcome(nav, enrichment)
	}
}

data class VacancyIngestionOutcome(
	val nav: NavCollectionOutcome,
	val enrichment: BrregEnrichmentOutcome,
)
