package no.jobvacancyanalysis.ingestion.application.brreg

import tools.jackson.databind.JsonNode

enum class BrregRecordType {
	MAIN_ENTITY,
	SUBUNIT,
}

enum class BrregLifecycleStatus {
	CURRENT,
	DELETED,
	GONE,
}

/** Carries only the BRREG fields allowed by the mapper's allowlist. */
data class BrregOrganizationRecord(
	val type: BrregRecordType,
	val lifecycleStatus: BrregLifecycleStatus,
	val organizationNumber: String,
	val fields: JsonNode,
)
