package no.jobvacancydata.ingestion.application

import java.time.OffsetDateTime
import no.jobvacancydata.ingestion.application.brreg.BrregEnrichmentStatus
import no.jobvacancydata.ingestion.application.brreg.BrregEnrichmentWorker
import no.jobvacancydata.ingestion.application.brreg.BrregLifecycleStatus
import no.jobvacancydata.ingestion.application.brreg.BrregLookupResult
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationRecord
import no.jobvacancydata.ingestion.application.brreg.BrregRecordType
import no.jobvacancydata.ingestion.application.nav.FeedEntry
import no.jobvacancydata.ingestion.application.nav.FeedFetchResult
import no.jobvacancydata.ingestion.application.nav.FeedItem
import no.jobvacancydata.ingestion.application.nav.FeedPage
import no.jobvacancydata.ingestion.application.nav.FeedPageCursor
import no.jobvacancydata.ingestion.application.nav.NavCollectionCompletion
import no.jobvacancydata.ingestion.application.nav.NavCollectionWorker
import no.jobvacancydata.ingestion.application.nav.VacancyAdContent
import no.jobvacancydata.ingestion.application.nav.VacancyDetailFetchResult
import no.jobvacancydata.ingestion.application.nav.VacancyDetailResponse
import no.jobvacancydata.ingestion.application.nav.VacancyEmployer
import no.jobvacancydata.ingestion.client.brreg.BrregRegistryClient
import no.jobvacancydata.ingestion.client.nav.NavFeedClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class VacancyIngestionWorkerTests {

	@Test
	fun `collects vacancies and enriches their distinct employer organization numbers`() {
		val matched = feedItem("synthetic-id-1")
		val malformed = feedItem("synthetic-id-2")
		val noEmployer = feedItem("synthetic-id-3")
		val navClient = StubNavFeedClient(
			pages = listOf(
				{ pageResult(listOf(matched, malformed, noEmployer)) },
			),
			details = mapOf(
				matched.url to { detail(matched.id, ORG_NUMBER) },
				malformed.url to { detail(malformed.id, "not-nine-digits") },
				noEmployer.url to { detail(noEmployer.id, null) },
			),
		)
		val brregClient = StubBrregRegistryClient()
		val worker = VacancyIngestionWorker(
			NavCollectionWorker(navClient),
			BrregEnrichmentWorker(brregClient),
		)

		val outcome = worker.collectAndEnrich()

		assertThat(outcome.nav.completion).isEqualTo(NavCollectionCompletion.ReachedTail)
		assertThat(outcome.nav.items).hasSize(3)
		assertThat(brregClient.requested).containsExactly(ORG_NUMBER, "not-nine-digits")
		assertThat(outcome.enrichment.organizations).hasSize(2)
		val enrichment = outcome.enrichment.organizations.first()
		assertThat(enrichment.organizationNumber).isEqualTo(ORG_NUMBER)
		assertThat(enrichment.status).isEqualTo(BrregEnrichmentStatus.ENRICHED)
	}

	private fun detail(entryId: String, orgNumber: String?): VacancyDetailFetchResult =
		VacancyDetailFetchResult.Fetched(
			VacancyDetailResponse(
				uuid = entryId,
				sistEndret = OffsetDateTime.parse("2026-01-02T03:04:05Z"),
				status = "ACTIVE",
				adContent = VacancyAdContent(
					uuid = "synthetic-ad-id-$entryId",
					published = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
					expires = OffsetDateTime.parse("2026-02-01T00:00:00Z"),
					updated = OffsetDateTime.parse("2026-01-02T00:00:00Z"),
					workLocations = emptyList(),
					title = "Synthetic role",
					description = null,
					sourceUrl = null,
					source = null,
					applicationUrl = null,
					applicationDue = null,
					occupationCategories = emptyList(),
					categoryList = emptyList(),
					jobTitle = null,
					link = "https://example.test/vacancy",
					employer = VacancyEmployer(
						name = "Synthetic employer",
						orgnr = orgNumber,
						description = null,
						homepage = null,
					),
					engagementType = null,
					extent = null,
					startTime = null,
					positionCount = null,
					sector = null,
				),
			),
		)

	private fun pageResult(items: List<FeedItem>): FeedFetchResult.Page =
		FeedFetchResult.Page(
			page = FeedPage(
				id = "synthetic-page-id",
				items = items,
				nextUrl = null,
				nextId = null,
			),
			cursor = FeedPageCursor(PAGE_URL),
			nextCursor = null,
		)

	private fun feedItem(id: String): FeedItem =
		FeedItem(
			id = id,
			url = "https://pam-stilling-feed.test/api/v1/feedentry/$id",
			title = "Synthetic role",
			contentText = "Synthetic description",
			dateModified = null,
			feedEntry = FeedEntry(
				uuid = id,
				status = "ACTIVE",
				title = "Synthetic feed title",
				businessName = "Synthetic employer",
				municipal = "Synthetic municipality",
				sistEndret = OffsetDateTime.parse("2026-01-02T03:04:05Z"),
			),
		)

	private class StubNavFeedClient(
		private val pages: List<() -> FeedFetchResult>,
		private val details: Map<String, () -> VacancyDetailFetchResult>,
	) : NavFeedClient {
		private var nextPageIndex = 0

		override fun fetchPage(cursor: FeedPageCursor?): FeedFetchResult = pages[nextPageIndex++]()

		override fun fetchVacancyDetail(detailUrl: String): VacancyDetailFetchResult =
			details.getValue(detailUrl)()
	}

	private class StubBrregRegistryClient : BrregRegistryClient {
		var requested: List<String> = emptyList()
			private set

		override fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup> {
			requested = organizationNumbers.toList()
			return requested.map { orgNumber ->
				BrregOrganizationLookup(
					organizationNumber = orgNumber,
					mainEntity = BrregLookupResult.Found(
						BrregOrganizationRecord(
							type = BrregRecordType.MAIN_ENTITY,
							lifecycleStatus = BrregLifecycleStatus.CURRENT,
							organizationNumber = orgNumber,
							fields = JsonMapper.builder().build().readTree("{}"),
						),
					),
					subunit = BrregLookupResult.NotFound,
				)
			}
		}
	}

	private companion object {
		const val PAGE_URL = "https://pam-stilling-feed.test/api/v1/feed"
		const val ORG_NUMBER = "509100675"
	}
}
