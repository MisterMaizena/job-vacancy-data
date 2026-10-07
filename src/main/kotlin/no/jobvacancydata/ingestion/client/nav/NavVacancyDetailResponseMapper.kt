package no.jobvacancydata.ingestion.client.nav

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import no.jobvacancydata.ingestion.application.nav.VacancyAdContent
import no.jobvacancydata.ingestion.application.nav.VacancyCategory
import no.jobvacancydata.ingestion.application.nav.VacancyDetailResponse
import no.jobvacancydata.ingestion.application.nav.VacancyEmployer
import no.jobvacancydata.ingestion.application.nav.VacancyOccupationCategory
import no.jobvacancydata.ingestion.application.nav.VacancyWorkLocation
import no.jobvacancydata.ingestion.client.validation.MappingResult
import no.jobvacancydata.ingestion.client.validation.buildWhenValid
import no.jobvacancydata.ingestion.client.validation.validateNext
import no.jobvacancydata.ingestion.client.validation.invalidMapping
import no.jobvacancydata.ingestion.client.validation.jsonNodeType
import no.jobvacancydata.ingestion.client.validation.validateEach
import no.jobvacancydata.ingestion.client.validation.mapValidValue
import no.jobvacancydata.ingestion.client.validation.valueOrThrow
import no.jobvacancydata.ingestion.client.validation.valueForValidResult
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class NavVacancyDetailResponseMapper(
	private val objectMapper: ObjectMapper,
) {
	/** Maps one vacancy detail response; invalid input throws instead of returning partial detail. */
	fun mapVacancyDetailResponse(json: String): VacancyDetailResponse =
		mapResponse(json).valueOrThrow { NavFeedMappingException(it) }

	private fun mapResponse(json: String): MappingResult<VacancyDetailResponse> =
		parseObject(json).validateNext { root ->
			val uuid = requiredUuid(root, "uuid", "$")
			val sistEndret = requiredDateTime(root, "sistEndret", "$")
			val status = requiredText(root, "status", "$")
			val adContent = mapAdContentField(root, status)

			buildWhenValid(listOf(uuid, sistEndret, status, adContent)) {
				VacancyDetailResponse(
					uuid = uuid.valueForValidResult(),
					sistEndret = sistEndret.valueForValidResult(),
					status = status.valueForValidResult(),
					adContent = adContent.valueForValidResult(),
				)
			}
		}

	private fun mapAdContentField(
		root: JsonNode,
		status: MappingResult<String>,
	): MappingResult<VacancyAdContent?> {
		val node = root.get("ad_content")
		if (node == null || node.isNull) {
			return if (status is MappingResult.Valid && status.value == "ACTIVE") {
				invalidMapping(
					"$.ad_content",
					"object for ACTIVE status",
					jsonNodeType(node),
				)
			} else {
				MappingResult.Valid(null)
			}
		}
		if (!node.isObject) {
			return invalidMapping("$.ad_content", "object or null", jsonNodeType(node))
		}
		return mapAdContent(node, "$.ad_content").mapValidValue { it }
	}

	private fun mapAdContent(
		node: JsonNode,
		path: String,
	): MappingResult<VacancyAdContent> {
		val uuid = requiredText(node, "uuid", path)
		val published = requiredDateTime(node, "published", path)
		val expires = requiredDateTime(node, "expires", path)
		val updated = requiredDateTime(node, "updated", path)
		val workLocations = requiredArray(node, "workLocations", path)
			.validateEach { index, item -> mapWorkLocation(item, "$path.workLocations[$index]") }
		val title = requiredText(node, "title", path)
		val description = optionalText(node, "description", path)
		val sourceUrl = optionalText(node, "sourceurl", path)
		val source = optionalText(node, "source", path)
		val applicationUrl = optionalText(node, "applicationUrl", path)
		val applicationDue = optionalText(node, "applicationDue", path)
		val occupationCategories = requiredArray(node, "occupationCategories", path)
			.validateEach { index, item ->
				mapOccupationCategory(item, "$path.occupationCategories[$index]")
			}
		val categoryList = requiredArray(node, "categoryList", path)
			.validateEach { index, item -> mapCategory(item, "$path.categoryList[$index]") }
		val jobTitle = optionalText(node, "jobtitle", path)
		val link = requiredText(node, "link", path)
		val employer = requiredObject(node, "employer", path)
			.validateNext { mapEmployer(it, "$path.employer") }
		val engagementType = optionalText(node, "engagementtype", path)
		val extent = optionalText(node, "extent", path)
		val startTime = optionalText(node, "starttime", path)
		val positionCount = optionalText(node, "positioncount", path)
		val sector = optionalText(node, "sector", path)

		return buildWhenValid(
			listOf(
				uuid, published, expires, updated, workLocations, title, description,
				sourceUrl, source, applicationUrl, applicationDue, occupationCategories,
				categoryList, jobTitle, link, employer, engagementType, extent,
				startTime, positionCount, sector,
			),
		) {
			VacancyAdContent(
				uuid = uuid.valueForValidResult(),
				published = published.valueForValidResult(),
				expires = expires.valueForValidResult(),
				updated = updated.valueForValidResult(),
				workLocations = workLocations.valueForValidResult(),
				title = title.valueForValidResult(),
				description = description.valueForValidResult(),
				sourceUrl = sourceUrl.valueForValidResult(),
				source = source.valueForValidResult(),
				applicationUrl = applicationUrl.valueForValidResult(),
				applicationDue = applicationDue.valueForValidResult(),
				occupationCategories = occupationCategories.valueForValidResult(),
				categoryList = categoryList.valueForValidResult(),
				jobTitle = jobTitle.valueForValidResult(),
				link = link.valueForValidResult(),
				employer = employer.valueForValidResult(),
				engagementType = engagementType.valueForValidResult(),
				extent = extent.valueForValidResult(),
				startTime = startTime.valueForValidResult(),
				positionCount = positionCount.valueForValidResult(),
				sector = sector.valueForValidResult(),
			)
		}
	}

	private fun mapWorkLocation(
		node: JsonNode,
		path: String,
	): MappingResult<VacancyWorkLocation> {
		if (!node.isObject) return invalidMapping(path, "object", jsonNodeType(node))

		val country = optionalText(node, "country", path)
		val address = optionalText(node, "address", path)
		val city = optionalText(node, "city", path)
		val postalCode = optionalText(node, "postalCode", path)
		val county = optionalText(node, "county", path)
		val municipal = optionalText(node, "municipal", path)
		return buildWhenValid(
			listOf(country, address, city, postalCode, county, municipal),
		) {
			VacancyWorkLocation(
				country.valueForValidResult(),
				address.valueForValidResult(),
				city.valueForValidResult(),
				postalCode.valueForValidResult(),
				county.valueForValidResult(),
				municipal.valueForValidResult(),
			)
		}
	}

	private fun mapOccupationCategory(
		node: JsonNode,
		path: String,
	): MappingResult<VacancyOccupationCategory> {
		if (!node.isObject) return invalidMapping(path, "object", jsonNodeType(node))

		val level1 = requiredText(node, "level1", path)
		val level2 = requiredText(node, "level2", path)
		return buildWhenValid(listOf(level1, level2)) {
			VacancyOccupationCategory(level1.valueForValidResult(), level2.valueForValidResult())
		}
	}

	private fun mapCategory(
		node: JsonNode,
		path: String,
	): MappingResult<VacancyCategory> {
		if (!node.isObject) return invalidMapping(path, "object", jsonNodeType(node))

		val score = requiredNumber(node, "score", path)
		val categoryType = requiredText(node, "categoryType", path)
		val code = requiredText(node, "code", path)
		val name = requiredText(node, "name", path)
		val description = optionalText(node, "description", path)
		return buildWhenValid(listOf(score, categoryType, code, name, description)) {
			VacancyCategory(
				categoryType = categoryType.valueForValidResult(),
				code = code.valueForValidResult(),
				name = name.valueForValidResult(),
				description = description.valueForValidResult(),
				score = score.valueForValidResult(),
			)
		}
	}

	private fun mapEmployer(
		node: JsonNode,
		path: String,
	): MappingResult<VacancyEmployer> {
		val name = requiredText(node, "name", path)
		val orgnr = optionalText(node, "orgnr", path)
		val description = optionalText(node, "description", path)
		val homepage = optionalText(node, "homepage", path)
		return buildWhenValid(listOf(name, orgnr, description, homepage)) {
			VacancyEmployer(
				name = name.valueForValidResult(),
				orgnr = orgnr.valueForValidResult(),
				description = description.valueForValidResult(),
				homepage = homepage.valueForValidResult(),
			)
		}
	}

	private fun parseObject(json: String): MappingResult<JsonNode> {
		val root = try {
			objectMapper.readTree(json)
		} catch (_: JacksonException) {
			return invalidMapping("$", "valid JSON object", "malformed JSON")
		}
		if (root?.isObject != true) return invalidMapping("$", "object", jsonNodeType(root))
		return MappingResult.Valid(root)
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

	private fun requiredText(
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

	private fun requiredNumber(
		node: JsonNode,
		field: String,
		parentPath: String,
	): MappingResult<Double> {
		val value = node.get(field)
		if (value?.isNumber != true) {
			return invalidMapping("$parentPath.$field", "number", jsonNodeType(value))
		}
		return MappingResult.Valid(value.asDouble())
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

	private fun requiredUuid(
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

	private fun parseUuid(value: String, path: String): MappingResult<String> =
		try {
			UUID.fromString(value)
			MappingResult.Valid(value)
		} catch (_: IllegalArgumentException) {
			invalidMapping(path, "UUID string", "invalid UUID string")
		}

	private fun parseDateTime(value: String, path: String): MappingResult<OffsetDateTime> =
		try {
			MappingResult.Valid(OffsetDateTime.parse(value))
		} catch (_: DateTimeParseException) {
			invalidMapping(path, "RFC 3339 date-time string", "invalid date-time string")
		}
}
