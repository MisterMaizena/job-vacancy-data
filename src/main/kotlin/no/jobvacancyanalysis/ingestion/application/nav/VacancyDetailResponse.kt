package no.jobvacancyanalysis.ingestion.application.nav

import java.time.OffsetDateTime

data class VacancyDetailResponse(
	val uuid: String,
	val sistEndret: OffsetDateTime,
	val status: String,
	val adContent: VacancyAdContent?,
)

data class VacancyAdContent(
	val uuid: String,
	val published: OffsetDateTime,
	val expires: OffsetDateTime,
	val updated: OffsetDateTime,
	val workLocations: List<VacancyWorkLocation>,
	val title: String,
	val description: String?,
	val sourceUrl: String?,
	val source: String?,
	val applicationUrl: String?,
	val applicationDue: String?,
	val occupationCategories: List<VacancyOccupationCategory>,
	val categoryList: List<VacancyCategory>,
	val jobTitle: String?,
	val link: String,
	val employer: VacancyEmployer,
	val engagementType: String?,
	val extent: String?,
	val startTime: String?,
	val positionCount: String?,
	val sector: String?,
)

data class VacancyWorkLocation(
	val country: String?,
	val address: String?,
	val city: String?,
	val postalCode: String?,
	val county: String?,
	val municipal: String?,
)

data class VacancyOccupationCategory(
	val level1: String,
	val level2: String,
)

data class VacancyCategory(
	val categoryType: String,
	val code: String,
	val name: String,
	val description: String?,
	val score: Double,
)

data class VacancyEmployer(
	val name: String,
	val orgnr: String?,
	val description: String?,
	val homepage: String?,
)
