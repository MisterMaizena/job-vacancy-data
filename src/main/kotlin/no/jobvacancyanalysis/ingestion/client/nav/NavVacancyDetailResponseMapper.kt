package no.jobvacancyanalysis.ingestion.client.nav

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import no.jobvacancyanalysis.ingestion.application.nav.VacancyAdContent
import no.jobvacancyanalysis.ingestion.application.nav.VacancyCategory
import no.jobvacancyanalysis.ingestion.application.nav.VacancyDetailResponse
import no.jobvacancyanalysis.ingestion.application.nav.VacancyEmployer
import no.jobvacancyanalysis.ingestion.application.nav.VacancyOccupationCategory
import no.jobvacancyanalysis.ingestion.application.nav.VacancyWorkLocation
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class NavVacancyDetailResponseMapper(
	private val objectMapper: ObjectMapper,
) {
	fun mapVacancyDetailResponse(json: String): VacancyDetailResponse {
		val root = objectMapper.readTree(json)
		require(root?.isObject == true) { "NAV detail response must be a JSON object" }

		val adContentNode = root.get("ad_content")
		require(adContentNode == null || adContentNode.isNull || adContentNode.isObject) {
			"NAV ad_content must be an object or null"
		}
		val status = requiredText(root, "status")
		if (status == "ACTIVE") {
			require(adContentNode?.isObject == true) {
				"NAV ACTIVE detail response must contain an ad_content object"
			}
		}

		return VacancyDetailResponse(
			uuid = requiredText(root, "uuid"),
			sistEndret = requiredDateTime(root, "sistEndret"),
			status = status,
			adContent = adContentNode?.takeUnless(JsonNode::isNull)?.let(::mapAdContent),
		)
	}

	private fun mapAdContent(node: JsonNode): VacancyAdContent {
		val employerNode = node.get("employer")
		require(employerNode?.isObject == true) {
			"NAV detail ad_content must contain an employer object"
		}

		return VacancyAdContent(
			uuid = requiredText(node, "uuid"),
			published = requiredDateTime(node, "published"),
			expires = requiredDateTime(node, "expires"),
			updated = requiredDateTime(node, "updated"),
			workLocations = requiredArray(node, "workLocations").map(::mapWorkLocation),
			title = requiredText(node, "title"),
			description = optionalText(node, "description"),
			sourceUrl = optionalText(node, "sourceurl"),
			source = optionalText(node, "source"),
			applicationUrl = optionalText(node, "applicationUrl"),
			applicationDue = optionalText(node, "applicationDue"),
			occupationCategories = requiredArray(node, "occupationCategories")
				.map(::mapOccupationCategory),
			categoryList = requiredArray(node, "categoryList").map(::mapCategory),
			jobTitle = optionalText(node, "jobtitle"),
			link = requiredText(node, "link"),
			employer = mapEmployer(employerNode),
			engagementType = optionalText(node, "engagementtype"),
			extent = optionalText(node, "extent"),
			startTime = optionalText(node, "starttime"),
			positionCount = optionalText(node, "positioncount"),
			sector = optionalText(node, "sector"),
		)
	}

	private fun mapWorkLocation(node: JsonNode): VacancyWorkLocation {
		require(node.isObject) { "NAV workLocations entries must be JSON objects" }
		return VacancyWorkLocation(
			country = optionalText(node, "country"),
			address = optionalText(node, "address"),
			city = optionalText(node, "city"),
			postalCode = optionalText(node, "postalCode"),
			county = optionalText(node, "county"),
			municipal = optionalText(node, "municipal"),
		)
	}

	private fun mapOccupationCategory(node: JsonNode): VacancyOccupationCategory {
		require(node.isObject) { "NAV occupationCategories entries must be JSON objects" }
		return VacancyOccupationCategory(
			level1 = requiredText(node, "level1"),
			level2 = requiredText(node, "level2"),
		)
	}

	private fun mapCategory(node: JsonNode): VacancyCategory {
		require(node.isObject) { "NAV categoryList entries must be JSON objects" }
		val score = node.get("score")
		require(score != null && score.isNumber) { "NAV category score must be a number" }

		return VacancyCategory(
			categoryType = requiredText(node, "categoryType"),
			code = requiredText(node, "code"),
			name = requiredText(node, "name"),
			description = optionalText(node, "description"),
			score = score.asDouble(),
		)
	}

	private fun mapEmployer(node: JsonNode): VacancyEmployer =
		VacancyEmployer(
			name = requiredText(node, "name"),
			orgnr = optionalText(node, "orgnr"),
			description = optionalText(node, "description"),
			homepage = optionalText(node, "homepage"),
		)

	private fun requiredArray(node: JsonNode, field: String): List<JsonNode> {
		val value = node.get(field)
		require(value != null && value.isArray) { "NAV field $field must be an array" }
		return value.toList()
	}

	private fun requiredText(node: JsonNode, field: String): String {
		val value = node.get(field)
		require(value != null && value.isString) { "NAV field $field must be a string" }
		return value.asString()
	}

	private fun optionalText(node: JsonNode, field: String): String? {
		val value = node.get(field)
		require(value == null || value.isNull || value.isString) {
			"NAV field $field must be a string or null"
		}
		return value?.takeUnless(JsonNode::isNull)?.asString()
	}

	private fun requiredDateTime(node: JsonNode, field: String): OffsetDateTime =
		try {
			OffsetDateTime.parse(requiredText(node, field))
		} catch (exception: DateTimeParseException) {
			throw IllegalArgumentException(
				"NAV date-time field $field must use RFC 3339 format " +
					"(parse error at index ${exception.errorIndex})",
			)
		}
}
