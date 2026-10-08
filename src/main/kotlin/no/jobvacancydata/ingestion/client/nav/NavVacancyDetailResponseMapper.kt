package no.jobvacancydata.ingestion.client.nav

import no.jobvacancydata.ingestion.application.nav.VacancyAdContent
import no.jobvacancydata.ingestion.application.nav.VacancyCategory
import no.jobvacancydata.ingestion.application.nav.VacancyDetailResponse
import no.jobvacancydata.ingestion.application.nav.VacancyEmployer
import no.jobvacancydata.ingestion.application.nav.VacancyOccupationCategory
import no.jobvacancydata.ingestion.application.nav.VacancyWorkLocation
import no.jobvacancydata.ingestion.client.validation.jsonNodeType
import org.springframework.stereotype.Component
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
		parseObject(objectMapper, json).validateNext { root ->
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
		return mapAdContent(node).mapValidValue { it }
	}

	private fun mapAdContent(node: JsonNode): MappingResult<VacancyAdContent> {
		val path = "$.ad_content"
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

		val score = requiredCategoryScore(node, path)
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

	private fun requiredCategoryScore(
		node: JsonNode,
		parentPath: String,
	): MappingResult<Double> {
		val value = node.get("score")
		if (value?.isNumber != true) {
			return invalidMapping("$parentPath.score", "number", jsonNodeType(value))
		}
		return MappingResult.Valid(value.asDouble())
	}

}
