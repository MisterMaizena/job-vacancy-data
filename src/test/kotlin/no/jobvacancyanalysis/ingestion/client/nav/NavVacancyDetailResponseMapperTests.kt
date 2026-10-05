package no.jobvacancyanalysis.ingestion.client.nav

import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.json.JsonMapper

class NavVacancyDetailResponseMapperTests {
	private val jsonMapper = JsonMapper.builder().build()
	private val mapper = NavVacancyDetailResponseMapper(jsonMapper)

	@Test
	fun `maps allowlisted response and nested ad fields while omitting contacts and unknown fields`() {
		val response = mapper.mapVacancyDetailResponse(validResponseJson())

		assertThat(response.uuid).isEqualTo("synthetic-entry-id")
		assertThat(response.sistEndret).isEqualTo(OffsetDateTime.parse("2026-01-02T03:04:05Z"))
		assertThat(response.status).isEqualTo("ACTIVE")

		val ad = response.adContent!!
		assertThat(ad.uuid).isEqualTo("synthetic-ad-id")
		assertThat(ad.published).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
		assertThat(ad.title).isEqualTo("Synthetic role")
		assertThat(ad.description).isEqualTo("Synthetic description")
		assertThat(ad.workLocations.single().address).isEqualTo("Synthetic address")
		assertThat(ad.occupationCategories.single().level1).isEqualTo("Synthetic occupation")
		assertThat(ad.categoryList.single().score).isEqualTo(0.75)
		assertThat(ad.employer.name).isEqualTo("Synthetic employer")
		assertThat(ad.employer.description).isEqualTo("Synthetic employer description")
		assertThat(ad.positionCount).isEqualTo("2")

		assertThat(ad.toString()).doesNotContain("contactList", "synthetic@example.test")
		assertThat(ad.toString()).doesNotContain("unknown")
	}

	@Test
	fun `allows missing or null ad content for inactive entries`() {
		val missing = (jsonMapper.readTree(validResponseJson()) as ObjectNode).apply {
			put("status", "INACTIVE")
			remove("ad_content")
		}
		val nullContent = (jsonMapper.readTree(validResponseJson()) as ObjectNode).apply {
			put("status", "INACTIVE")
			putNull("ad_content")
		}

		val missingContent = mapper.mapVacancyDetailResponse(jsonMapper.writeValueAsString(missing))
		val nullContentResponse = mapper.mapVacancyDetailResponse(jsonMapper.writeValueAsString(nullContent))
		assertThat(missingContent.status).isEqualTo("INACTIVE")
		assertThat(missingContent.adContent).isNull()
		assertThat(nullContentResponse.status).isEqualTo("INACTIVE")
		assertThat(nullContentResponse.adContent).isNull()
	}

	@Test
	fun `rejects active entries with missing or null ad content`() {
		val missing = (jsonMapper.readTree(validResponseJson()) as ObjectNode).apply {
			remove("ad_content")
		}
		val nullContent = (jsonMapper.readTree(validResponseJson()) as ObjectNode).apply {
			putNull("ad_content")
		}

		assertThrows(IllegalArgumentException::class.java) {
			mapper.mapVacancyDetailResponse(jsonMapper.writeValueAsString(missing))
		}
		assertThrows(IllegalArgumentException::class.java) {
			mapper.mapVacancyDetailResponse(jsonMapper.writeValueAsString(nullContent))
		}
	}

	@Test
	fun `rejects malformed response shape and invalid required fields`() {
		assertThrows(IllegalArgumentException::class.java) {
			mapper.mapVacancyDetailResponse("[]")
		}
		assertThrows(IllegalArgumentException::class.java) {
			mapper.mapVacancyDetailResponse("""{"uuid":"synthetic"}""")
		}
		assertThrows(IllegalArgumentException::class.java) {
			mapper.mapVacancyDetailResponse(
				validResponseJson().replace("\"score\": 0.75", "\"score\": null"),
			)
		}
	}

	private fun validResponseJson(): String =
		"""
		{
		  "uuid": "synthetic-entry-id",
		  "sistEndret": "2026-01-02T03:04:05Z",
		  "status": "ACTIVE",
		  "unknown_response_field": "unknown",
		  "ad_content": ${adContentJson()}
		}
		""".trimIndent()

	private fun adContentJson(): String =
		"""
		{
		  "uuid": "synthetic-ad-id",
		  "published": "2026-01-01T00:00:00Z",
		  "expires": "2026-02-01T00:00:00Z",
		  "updated": "2026-01-02T00:00:00Z",
		  "workLocations": [
		    {
		      "country": "Norway",
		      "address": "Synthetic address",
		      "city": "Synthetic city",
		      "postalCode": "0001",
		      "county": "Synthetic county",
		      "municipal": "Synthetic municipality",
		      "unknown_location_field": "unknown"
		    }
		  ],
		  "contactList": [
		    {
		      "name": "Synthetic contact",
		      "email": "synthetic@example.test",
		      "phone": "00000000"
		    }
		  ],
		  "title": "Synthetic role",
		  "description": "Synthetic description",
		  "sourceurl": "https://example.test/source",
		  "source": "Synthetic source",
		  "applicationUrl": "https://example.test/apply",
		  "applicationDue": "2026-01-31",
		  "occupationCategories": [
		    {"level1": "Synthetic occupation", "level2": "Synthetic subcategory"}
		  ],
		  "categoryList": [
		    {
		      "categoryType": "Synthetic type",
		      "code": "synthetic-code",
		      "name": "Synthetic category",
		      "description": "Synthetic category description",
		      "score": 0.75
		    }
		  ],
		  "jobtitle": "Synthetic job title",
		  "link": "https://example.test/vacancy",
		  "employer": {
		    "name": "Synthetic employer",
		    "orgnr": "synthetic-org-id",
		    "description": "Synthetic employer description",
		    "homepage": "https://example.test",
		    "unknown_employer_field": "unknown"
		  },
		  "engagementtype": "Synthetic engagement",
		  "extent": "Full-time",
		  "starttime": "By agreement",
		  "positioncount": "2",
		  "sector": "Synthetic sector",
		  "unknown_ad_field": "unknown"
		}
		""".trimIndent()
}
