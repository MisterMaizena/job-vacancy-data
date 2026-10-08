package no.jobvacancydata.ingestion.client.nav

import java.time.OffsetDateTime
import no.jobvacancydata.ingestion.application.nav.FeedEntry
import no.jobvacancydata.ingestion.application.nav.FeedItem
import no.jobvacancydata.ingestion.application.nav.FeedPage
import no.jobvacancydata.ingestion.client.validation.jsonNodeType
import org.springframework.stereotype.Component
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
		parseObject(objectMapper, json).validateNext { root ->
			val version = requiredText(root, "version", "$")
			val title = requiredText(root, "title", "$")
			val homePageUrl = requiredText(root, "home_page_url", "$")
			val feedUrl = requiredText(root, "feed_url", "$")
			val description = requiredText(root, "description", "$")
			val id = requiredUuid(root, "id", "$")
			val items = requiredArray(root, "items", "$")
				.validateEach { index, item -> mapFeedPageItem(item, "$.items[$index]") }
			val nextUrl = optionalText(root, "next_url", "$")
			val nextId = optionalNextId(root)

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
		val dateModified = optionalDateModified(node, path)
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

	private fun optionalNextId(node: JsonNode): MappingResult<String?> {
		val value = node.get("next_id")
		if (value == null || value.isNull) return MappingResult.Valid(null)
		if (!value.isString) {
			return invalidMapping("$.next_id", "UUID string or null", jsonNodeType(value))
		}
		return parseUuid(value.asString(), "$.next_id")
	}

	private fun optionalDateModified(
		node: JsonNode,
		parentPath: String,
	): MappingResult<OffsetDateTime?> {
		val value = node.get("date_modified")
		if (value == null || value.isNull) return MappingResult.Valid(null)
		if (!value.isString) {
			return invalidMapping(
				"$parentPath.date_modified",
				"RFC 3339 date-time string or null",
				jsonNodeType(value),
			)
		}
		return parseDateTime(value.asString(), "$parentPath.date_modified")
	}

}
