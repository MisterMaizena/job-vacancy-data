package no.jobvacancydata.ingestion.client.nav

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import no.jobvacancydata.ingestion.application.nav.FeedEntry
import no.jobvacancydata.ingestion.application.nav.FeedItem
import no.jobvacancydata.ingestion.application.nav.FeedPage
import no.jobvacancydata.ingestion.client.validation.MappingResult
import no.jobvacancydata.ingestion.client.validation.buildWhenValid
import no.jobvacancydata.ingestion.client.validation.validateNext
import no.jobvacancydata.ingestion.client.validation.invalidMapping
import no.jobvacancydata.ingestion.client.validation.validateEach
import no.jobvacancydata.ingestion.client.validation.jsonNodeType
import no.jobvacancydata.ingestion.client.validation.valueOrThrow
import no.jobvacancydata.ingestion.client.validation.valueForValidResult
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class NavFeedPageMapper(
	private val objectMapper: ObjectMapper,
) {
	/** Maps one complete NAV feed-page response; invalid input throws instead of returning a partial page. */
	fun mapFeedPage(json: String): FeedPage =
		mapFeedPageResult(json).valueOrThrow { NavFeedMappingException(it) }

	private fun mapFeedPageResult(json: String): MappingResult<FeedPage> =
		parseObject(json).validateNext { root ->
			val version = requiredText(root, "version", "$")
			val title = requiredText(root, "title", "$")
			val homePageUrl = requiredText(root, "home_page_url", "$")
			val feedUrl = requiredText(root, "feed_url", "$")
			val description = requiredText(root, "description", "$")
			val id = requiredUuid(root, "id", "$")
			val items = requiredArray(root, "items", "$")
				.validateEach { index, item -> mapFeedPageItem(item, "$.items[$index]") }
			val nextUrl = optionalText(root, "next_url", "$")
			val nextId = optionalUuid(root, "next_id", "$")

			buildWhenValid(
				listOf(version, title, homePageUrl, feedUrl, description, id, items, nextUrl, nextId),
			) {
				FeedPage(
					id = id.valueForValidResult(),
					items = items.valueForValidResult(),
					nextUrl = nextUrl.valueForValidResult(),
					nextId = nextId.valueForValidResult(),
				)
			}
		}

	private fun mapFeedPageItem(
		node: JsonNode,
		path: String,
	): MappingResult<FeedItem> {
		if (!node.isObject) return invalidMapping(path, "object", jsonNodeType(node))

		val id = requiredText(node, "id", path)
		val url = requiredText(node, "url", path)
		val title = requiredText(node, "title", path)
		val contentText = requiredText(node, "content_text", path)
		val dateModified = optionalDateTime(node, "date_modified", path)
		val feedEntry = requiredObject(node, "_feed_entry", path)
			.validateNext { mapFeedEntry(it, "$path._feed_entry") }

		return buildWhenValid(listOf(id, url, title, contentText, dateModified, feedEntry)) {
			FeedItem(
				id = id.valueForValidResult(),
				url = url.valueForValidResult(),
				title = title.valueForValidResult(),
				contentText = contentText.valueForValidResult(),
				dateModified = dateModified.valueForValidResult(),
				feedEntry = feedEntry.valueForValidResult(),
			)
		}
	}

	private fun mapFeedEntry(node: JsonNode, path: String): MappingResult<FeedEntry> {
		val uuid = requiredText(node, "uuid", path)
		val status = requiredText(node, "status", path)
		val title = requiredText(node, "title", path)
		val businessName = requiredText(node, "businessName", path)
		val municipal = requiredText(node, "municipal", path)
		val sistEndret = requiredDateTime(node, "sistEndret", path)

		return buildWhenValid(
			listOf(uuid, status, title, businessName, municipal, sistEndret),
		) {
			FeedEntry(
				uuid = uuid.valueForValidResult(),
				// Keep status as text so a new NAV status doesn't break mapping.
				status = status.valueForValidResult(),
				title = title.valueForValidResult(),
				businessName = businessName.valueForValidResult(),
				municipal = municipal.valueForValidResult(),
				sistEndret = sistEndret.valueForValidResult(),
			)
		}
	}

	private fun parseObject(json: String): MappingResult<JsonNode> {
		val root = try {
			objectMapper.readTree(json)
		} catch (_: JacksonException) {
			return invalidMapping("$", "valid JSON object", "malformed JSON")
		}
		if (root?.isObject != true) {
			return invalidMapping("$", "object", jsonNodeType(root))
		}
		return MappingResult.Valid(root)
	}

	private fun requiredText(node: JsonNode, field: String, parentPath: String): MappingResult<String> {
		val value = node.get(field)
		if (value?.isString != true) {
			return invalidMapping("$parentPath.$field", "string", jsonNodeType(value))
		}
		return MappingResult.Valid(value.asString())
	}

	private fun optionalText(
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

	private fun requiredArray(
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

	private fun requiredObject(
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

	private fun requiredUuid(node: JsonNode, field: String, parentPath: String): MappingResult<String> {
		val value = node.get(field)
		if (value?.isString != true) {
			return invalidMapping("$parentPath.$field", "UUID string", jsonNodeType(value))
		}
		return parseUuid(value.asString(), "$parentPath.$field")
	}

	private fun optionalUuid(
		node: JsonNode,
		field: String,
		parentPath: String,
	): MappingResult<String?> {
		val value = node.get(field)
		if (value == null || value.isNull) return MappingResult.Valid(null)
		if (!value.isString) {
			return invalidMapping("$parentPath.$field", "UUID string or null", jsonNodeType(value))
		}
		return parseUuid(value.asString(), "$parentPath.$field")
	}

	private fun parseUuid(value: String, path: String): MappingResult<String> =
		try {
			UUID.fromString(value)
			MappingResult.Valid(value)
		} catch (_: IllegalArgumentException) {
			invalidMapping(path, "UUID string", "invalid UUID string")
		}

	private fun requiredDateTime(
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

	private fun optionalDateTime(
		node: JsonNode,
		field: String,
		parentPath: String,
	): MappingResult<OffsetDateTime?> {
		val value = node.get(field)
		if (value == null || value.isNull) return MappingResult.Valid(null)
		if (!value.isString) {
			return invalidMapping(
				"$parentPath.$field",
				"RFC 3339 date-time string or null",
				jsonNodeType(value),
			)
		}
		return parseDateTime(value.asString(), "$parentPath.$field")
	}

	private fun parseDateTime(value: String, path: String): MappingResult<OffsetDateTime> =
		try {
			MappingResult.Valid(OffsetDateTime.parse(value))
		} catch (_: DateTimeParseException) {
			invalidMapping(path, "RFC 3339 date-time string", "invalid date-time string")
		}
}
