package no.jobvacancydata.ingestion.application.brreg

import no.jobvacancydata.ingestion.client.brreg.BrregRegistryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class BrregEnrichmentWorkerTests {

	@Test
	fun `derives one explicit status per organization and preserves partial results`() {
		val enriched = BrregOrganizationLookup(
			organizationNumber = ORG_ENRICHED,
			mainEntity = BrregLookupResult.Found(record(ORG_ENRICHED)),
			subunit = BrregLookupResult.NotFound,
		)
		val notFound = BrregOrganizationLookup(
			organizationNumber = ORG_NOT_FOUND,
			mainEntity = BrregLookupResult.NotFound,
			subunit = BrregLookupResult.NotFound,
		)
		val failed = BrregOrganizationLookup(
			organizationNumber = ORG_FAILED,
			mainEntity = BrregLookupResult.Failed("BRREG detail lookup failed with HTTP 503"),
			subunit = BrregLookupResult.Found(record(ORG_FAILED)),
		)
		val invalid = BrregOrganizationLookup(
			organizationNumber = ORG_INVALID,
			mainEntity = BrregLookupResult.Invalid("BRREG response failed mapping validation"),
			subunit = BrregLookupResult.NotFound,
		)
		val client = StubBrregRegistryClient(listOf(enriched, notFound, failed, invalid))

		val outcome = BrregEnrichmentWorker(client)
			.enrich(listOf(ORG_ENRICHED, ORG_NOT_FOUND, ORG_FAILED, ORG_INVALID))

		assertThat(outcome.rejectedOrganizationNumbers).isEmpty()
		val byNumber = outcome.organizations.associateBy { it.organizationNumber }
		assertThat(byNumber.getValue(ORG_ENRICHED).status).isEqualTo(BrregEnrichmentStatus.ENRICHED)
		assertThat(byNumber.getValue(ORG_NOT_FOUND).status).isEqualTo(BrregEnrichmentStatus.NOT_FOUND)
		assertThat(byNumber.getValue(ORG_FAILED).status).isEqualTo(BrregEnrichmentStatus.FAILED)
		assertThat(byNumber.getValue(ORG_INVALID).status).isEqualTo(BrregEnrichmentStatus.INVALID)
		// A failed register never discards the other register's valid result.
		assertThat(byNumber.getValue(ORG_FAILED).subunit).isInstanceOf(BrregLookupResult.Found::class.java)
	}

	@Test
	fun `rejects malformed organization numbers without sending them to BRREG`() {
		val client = StubBrregRegistryClient(emptyList())

		val outcome = BrregEnrichmentWorker(client)
			.enrich(listOf("not-nine-digits", ORG_ENRICHED, ORG_ENRICHED))

		assertThat(client.requested).containsExactly(ORG_ENRICHED)
		assertThat(outcome.rejectedOrganizationNumbers).containsExactly("not-nine-digits")
	}

	private fun record(organizationNumber: String): BrregOrganizationRecord =
		BrregOrganizationRecord(
			type = BrregRecordType.MAIN_ENTITY,
			lifecycleStatus = BrregLifecycleStatus.CURRENT,
			organizationNumber = organizationNumber,
			fields = JsonMapper.builder().build().readTree("{}"),
		)

	private class StubBrregRegistryClient(
		private val lookups: List<BrregOrganizationLookup>,
	) : BrregRegistryClient {
		var requested: List<String> = emptyList()
			private set

		override fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup> {
			requested = organizationNumbers.toList()
			return lookups
		}
	}

	private companion object {
		const val ORG_ENRICHED = "509100675"
		const val ORG_NOT_FOUND = "509100676"
		const val ORG_FAILED = "509100677"
		const val ORG_INVALID = "509100678"
	}
}
