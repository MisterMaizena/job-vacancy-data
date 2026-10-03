package no.jobvacancyanalysis.ingestion.client.nav

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import no.jobvacancyanalysis.ingestion.application.nav.FeedEntry
import no.jobvacancyanalysis.ingestion.application.nav.FeedItem
import no.jobvacancyanalysis.ingestion.application.nav.FeedPage
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class NavFeedPageMapper(
	private val objectMapper: ObjectMapper,
) {
	fun mapFeedPage(json: String): FeedPage {
		val root = objectMapper.readTree(json)
		require(root?.isObject == true) { "NAV feed page must be a JSON object" }

		requiredText(root, "version")
		requiredText(root, "title")
		requiredText(root, "home_page_url")
		requiredText(root, "feed_url")
		requiredText(root, "description")

		val itemsNode = root.get("items")
		require(itemsNode != null && itemsNode.isArray) {
			"NAV feed page must contain an items array"
		}

		val nextUrlNode = root.get("next_url")
		require(nextUrlNode == null || nextUrlNode.isNull || nextUrlNode.isString) {
			"NAV feed next_url must be a string or null"
		}

		val nextId = optionalText(root, "next_id")?.also(::requireUuid)

		return FeedPage(
			id = requiredText(root, "id").also(::requireUuid),
			items = itemsNode.toList().map(::mapFeedPageItem),
			nextUrl = nextUrlNode?.takeUnless(JsonNode::isNull)?.asString(),
			nextId = nextId,
		)
	}

	private fun mapFeedPageItem(node: JsonNode): FeedItem {
		require(node.isObject) { "NAV feed items must be JSON objects" }

		val entryNode = node.get("_feed_entry")
		require(entryNode?.isObject == true) {
			"NAV feed item must contain a _feed_entry object"
		}

		return FeedItem(
			id = requiredText(node, "id"),
			url = requiredText(node, "url"),
			title = requiredText(node, "title"),
			contentText = requiredText(node, "content_text"),
			dateModified = optionalDateTime(node),
			feedEntry = FeedEntry(
				uuid = requiredText(entryNode, "uuid"),
				// Keep status as text so a new NAV status doesn't make the whole page fail to map.
				status = requiredText(entryNode, "status"),
				title = requiredText(entryNode, "title"),
				businessName = requiredText(entryNode, "businessName"),
				municipal = requiredText(entryNode, "municipal"),
				sistEndret = requiredSistEndret(entryNode),
			),
		)
	}

	private fun requiredText(node: JsonNode, field: String): String {
		val value = node.get(field)
		require(value != null && value.isString) {
			"NAV field $field must be a string"
		}
		return value.asString()
	}

	private fun optionalText(node: JsonNode, field: String): String? {
		val value = node.get(field)
		require(value == null || value.isNull || value.isString) {
			"NAV field $field must be a string or null"
		}
		return value?.takeUnless(JsonNode::isNull)?.asString()
	}

	private fun optionalDateTime(node: JsonNode): OffsetDateTime? =
		optionalText(node, "date_modified")?.let(::parseDateTime)

	private fun requiredSistEndret(node: JsonNode): OffsetDateTime =
		parseDateTime(requiredText(node, "sistEndret"))

	private fun parseDateTime(value: String): OffsetDateTime =
		try {
			OffsetDateTime.parse(value)
		} catch (exception: DateTimeParseException) {
			throw IllegalArgumentException(
				"NAV date-time must use RFC 3339 format (parse error at index ${exception.errorIndex})",
			)
		}

	@Suppress("UNUSED_PARAMETER")
	private fun requireUuid(value: String) {
		try {
			UUID.fromString(value)
		} catch (exception: IllegalArgumentException) {
			// Deliberately not propagating this parser exception to logs (defensive)
			throw IllegalArgumentException("NAV feed page ID must be a UUID")
		}
	}
}
