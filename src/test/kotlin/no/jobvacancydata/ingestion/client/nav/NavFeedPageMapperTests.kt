package no.jobvacancydata.ingestion.client.nav

import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class NavFeedPageMapperTests {
	private val jsonMapper = JsonMapper.builder().build()
	private val mapper = NavFeedPageMapper(jsonMapper)

	@Test
	fun `maps required page and item fields and ignores unknown fields`() {
		val pageJson = validPageNode().apply {
			put("next_id", "550e8400-e29b-41d4-a716-446655440001")
			put("unknown_envelope_field", true)

			val item = path("items").get(0) as ObjectNode
			item.put("unknown_field", "ignored")

			val feedEntry = item.path("_feed_entry") as ObjectNode
			feedEntry.put("status", "UNRECOGNIZED_STATUS")
			feedEntry.put("sistEndret", "2026-01-02T03:04:05+01:00")
		}

		val page = mapPage(pageJson)

		assertThat(page.id).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
		assertThat(page.nextId).isEqualTo("550e8400-e29b-41d4-a716-446655440001")
		assertThat(page.nextUrl).isEqualTo("/api/v1/feed?next=synthetic")
		assertThat(page.items).hasSize(1)

		val item = page.items.single()
		assertThat(item.id).isEqualTo("synthetic-id")
		assertThat(item.url).isEqualTo("https://example.test/vacancy")
		assertThat(item.title).isEqualTo("Synthetic role")
		assertThat(item.contentText).isEqualTo("Synthetic description")
		assertThat(item.dateModified).isEqualTo(OffsetDateTime.parse("2026-01-02T03:04:05Z"))
		assertThat(item.feedEntry.uuid).isEqualTo("synthetic-entry-id")
		assertThat(item.feedEntry.status).isEqualTo("UNRECOGNIZED_STATUS")
		assertThat(item.feedEntry.title).isEqualTo("Synthetic feed title")
		assertThat(item.feedEntry.businessName).isEqualTo("Synthetic employer")
		assertThat(item.feedEntry.municipal).isEqualTo("Synthetic municipality")
		assertThat(item.feedEntry.sistEndret)
			.isEqualTo(OffsetDateTime.parse("2026-01-02T03:04:05+01:00"))
	}

	@Test
	fun `allows missing or null next fields and date modified`() {
		val missingFields = validPageNode().apply {
			remove("next_url")
			remove("next_id")
			(path("items").get(0) as ObjectNode).remove("date_modified")
		}

		val missingFieldsPage = mapPage(missingFields)
		assertThat(missingFieldsPage.nextUrl).isNull()
		assertThat(missingFieldsPage.nextId).isNull()
		assertThat(missingFieldsPage.items.single().dateModified).isNull()

		val nullFields = validPageNode().apply {
			putNull("next_url")
			putNull("next_id")
			(path("items").get(0) as ObjectNode).putNull("date_modified")
		}

		val nullFieldsPage = mapPage(nullFields)
		assertThat(nullFieldsPage.nextUrl).isNull()
		assertThat(nullFieldsPage.nextId).isNull()
		assertThat(nullFieldsPage.items.single().dateModified).isNull()
	}

	@Test
	fun `rejects missing required page and item fields`() {
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply { remove("version") })
		}
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply {
				(path("items").get(0) as ObjectNode).remove("content_text")
			})
		}
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply {
				(path("items").get(0) as ObjectNode).remove("_feed_entry")
			})
		}
	}

	@Test
	fun `rejects invalid types and formats`() {
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply { put("next_url", 42) })
		}
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply { put("id", "not-a-uuid") })
		}
		assertThrows(NavFeedMappingException::class.java) {
			mapPage(validPageNode().apply {
				(path("items").get(0) as ObjectNode).put("date_modified", "not-a-date")
			})
		}
	}

	@Test
	fun `reports malformed page input as a structured mapping exception`() {
		assertThrows(NavFeedMappingException::class.java) {
			mapper.mapFeedPage("[]")
		}
		assertThrows(NavFeedMappingException::class.java) {
			mapper.mapFeedPage("""{"items":""")
		}
	}

	@Test
	fun `aggregates page and item errors without returning a partial page`() {
		val invalidPage = validPageNode().apply {
			put("next_url", 42)
			(path("items").get(0) as ObjectNode).put("content_text", false)
		}

		val exception = assertThrows(NavFeedMappingException::class.java) {
			mapPage(invalidPage)
		}

		assertThat(exception.report.errors.map { it.path }).containsExactlyInAnyOrder(
			"$.next_url",
			"$.items[0].content_text",
		)
	}


	private fun validPageNode(): ObjectNode =
		jsonMapper.readTree(validPageJson()) as ObjectNode

	private fun mapPage(page: ObjectNode) =
		mapper.mapFeedPage(jsonMapper.writeValueAsString(page))

	private fun validPageJson() =
		"""
		{
		  "version": "1",
		  "title": "Synthetic feed",
		  "home_page_url": "https://example.test",
		  "feed_url": "https://example.test/feed",
		  "description": "Synthetic feed description",
		  "id": "550e8400-e29b-41d4-a716-446655440000",
		  "items": [
		    {
		      "id": "synthetic-id",
		      "url": "https://example.test/vacancy",
		      "title": "Synthetic role",
		      "content_text": "Synthetic description",
		      "date_modified": "2026-01-02T03:04:05Z",
		      "_feed_entry": {
		        "uuid": "synthetic-entry-id",
		        "status": "ACTIVE",
		        "title": "Synthetic feed title",
		        "businessName": "Synthetic employer",
		        "municipal": "Synthetic municipality",
		        "sistEndret": "2026-01-02T03:04:05Z"
		      }
		    }
		  ],
		  "next_url": "/api/v1/feed?next=synthetic"
		}
		""".trimIndent()
}
