package no.jobvacancydata.ingestion.client.nav

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import no.jobvacancydata.ingestion.client.validation.jsonNodeType
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

internal fun parseObject(
	objectMapper: ObjectMapper,
	json: String,
): MappingResult<JsonNode> {
	val root = try {
		objectMapper.readTree(json)
	} catch (_: JacksonException) {
		return invalidMapping("$", "valid JSON object", "malformed JSON")
	}
	if (root?.isObject != true) return invalidMapping("$", "object", jsonNodeType(root))
	return MappingResult.Valid(root)
}

internal fun requiredText(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<String> {
	val value = node.get(field)
	if (value?.isString != true) {
		return invalidMapping("$parentPath.$field", "string", jsonNodeType(value))
	}
	return MappingResult.Valid(value.asString())
}

internal fun optionalText(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<String?> {
	val value = node.get(field)
	if (value == null || value.isNull) return MappingResult.Valid(null)
	if (!value.isString) {
		return invalidMapping("$parentPath.$field", "string or null", jsonNodeType(value))
	}
	return MappingResult.Valid(value.asString())
}

internal fun requiredArray(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<List<JsonNode>> {
	val value = node.get(field)
	if (value?.isArray != true) {
		return invalidMapping("$parentPath.$field", "array", jsonNodeType(value))
	}
	return MappingResult.Valid(value.toList())
}

internal fun requiredObject(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<JsonNode> {
	val value = node.get(field)
	if (value?.isObject != true) {
		return invalidMapping("$parentPath.$field", "object", jsonNodeType(value))
	}
	return MappingResult.Valid(value)
}

internal fun requiredUuid(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<String> {
	val value = node.get(field)
	if (value?.isString != true) {
		return invalidMapping("$parentPath.$field", "UUID string", jsonNodeType(value))
	}
	return parseUuid(value.asString(), "$parentPath.$field")
}

internal fun parseUuid(value: String, path: String): MappingResult<String> =
	try {
		UUID.fromString(value)
		MappingResult.Valid(value)
	} catch (_: IllegalArgumentException) {
		invalidMapping(path, "UUID string", "invalid UUID string")
	}

internal fun requiredDateTime(
	node: JsonNode,
	field: String,
	parentPath: String,
): MappingResult<OffsetDateTime> {
	val value = node.get(field)
	if (value?.isString != true) {
		return invalidMapping(
			"$parentPath.$field",
			"RFC 3339 date-time string",
			jsonNodeType(value),
		)
	}
	return parseDateTime(value.asString(), "$parentPath.$field")
}

internal fun parseDateTime(value: String, path: String): MappingResult<OffsetDateTime> =
	try {
		MappingResult.Valid(OffsetDateTime.parse(value))
	} catch (_: DateTimeParseException) {
		invalidMapping(path, "RFC 3339 date-time string", "invalid date-time string")
	}
