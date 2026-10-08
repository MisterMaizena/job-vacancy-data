package no.jobvacancydata.ingestion.client.validation

import tools.jackson.databind.JsonNode

data class MappingError(
	val path: String,
	val expected: String,
	val actual: String,
)

data class MappingErrorReport(
	val errors: List<MappingError>,
) {
	fun message(source: String): String = buildString {
		append("$source response failed mapping validation")
		if (errors.isNotEmpty()) {
			append(": ")
			append(errors.joinToString("; ") {
				"${it.path}: expected ${it.expected}, got ${it.actual}"
			})
		}
	}
}

fun jsonNodeType(node: JsonNode?): String = when {
	node == null -> "missing"
	node.isNull -> "null"
	node.isObject -> "object"
	node.isArray -> "array"
	node.isString -> "string"
	node.isNumber -> "number"
	node.isBoolean -> "boolean"
	else -> "unknown"
}
