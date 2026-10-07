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

// Allowlisted fields from BRReg only
data class BrregOrganizationRecord(
	val type: BrregRecordType,
	val lifecycleStatus: BrregLifecycleStatus,
	val organizationNumber: String,
	val fields: JsonNode,
)
