package no.jobvacancydata.ingestion.client.brreg

/** Fixed contract values for the selected BRREG endpoints. */
internal object BrregApiContract {
	const val MAX_ORGANIZATION_NUMBERS_PER_REQUEST = 2_000
	const val MAIN_ENTITY_PATH = "/api/enheter"
	const val SUBUNIT_PATH = "/api/underenheter"

	val ORGANIZATION_NUMBER_REGEX = Regex("\\d{9}")
}

internal fun partitionBrregOrganizationNumbers(
	organizationNumbers: Collection<String>,
): List<List<String>> = organizationNumbers.toSortedSet()
	.chunked(BrregApiContract.MAX_ORGANIZATION_NUMBERS_PER_REQUEST)
