package no.jobvacancydata.ingestion.client.brreg

import java.net.URI
import java.time.Duration
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import no.jobvacancydata.ingestion.application.brreg.BrregLookupResult
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationRecord
import no.jobvacancydata.ingestion.application.brreg.BrregRecordType
import org.springframework.http.HttpStatus
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

class BrregRestClient(
	private val restClient: RestClient,
	private val mapper: BrregRecordMapper,
	private val objectMapper: ObjectMapper,
	baseUrl: String,
	retryMaxAttempts: Int,
	retryWaitDuration: Duration,
) : BrregRegistryClient {
	private val baseUri = URI.create(baseUrl.trimEnd('/'))
	private val retry = Retry.of(
		"brreg-registry",
		RetryConfig.custom<Any>()
			.maxAttempts(retryMaxAttempts)
			.waitDuration(retryWaitDuration)
			.retryOnException(::isRetryable)
			.build(),
	)

	/** Validates and batches IDs, then returns each ID's main-entity and subunit lookup outcomes. */
	override fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup> {
		val ids = organizationNumbers.toSortedSet()
		if (ids.isEmpty()) return emptyList()
		require(ids.all { BrregApiContract.ORGANIZATION_NUMBER_REGEX.matches(it) }) {
			"BRREG organization numbers must contain exactly nine digits"
		}
		return partitionBrregOrganizationNumbers(ids).flatMap(::lookupBatch)
	}

	/** Searches both registers for a chunk; if an ID is missing from a search, fetches that record by ID. */
	private fun lookupBatch(ids: List<String>): List<BrregOrganizationLookup> {
		val main = search(ids, BrregApiContract.MAIN_ENTITY_PATH, "enheter", mapper::mapMainEntity)
		val subunits = search(ids, BrregApiContract.SUBUNIT_PATH, "underenheter", mapper::mapSubunit)
		return ids.map { id ->
			BrregOrganizationLookup(
				organizationNumber = id,
				mainEntity = main[id] ?: fetchDetail(id, BrregApiContract.MAIN_ENTITY_PATH, mapper::mapMainEntity),
				subunit = subunits[id] ?: fetchDetail(id, BrregApiContract.SUBUNIT_PATH, mapper::mapSubunit),
			)
		}
	}

	/** Searches one register across all pages and collects records so each can be found by organization number. */
	private fun search(
		ids: Collection<String>,
		path: String,
		embeddedKey: String,
		mapRecord: (String) -> BrregOrganizationRecord,
	): Map<String, BrregLookupResult.Found> {
		val firstUrl = "${baseUri}$path?organisasjonsnummer=${ids.joinToString(",")}"
		var nextUrl: String? = firstUrl
		val records = linkedMapOf<String, BrregLookupResult.Found>()
		while (nextUrl != null) {
			val url = validatePageUrl(nextUrl)
			val body = request { restClient.get().uri(url).retrieve().body<String>() }
				?: throw BrregMalformedResponseException("BRREG search response body is missing")
			val root = try {
				objectMapper.readTree(body)
			} catch (_: JacksonException) {
				throw BrregMalformedResponseException("BRREG search response is not valid JSON")
			}
			if (root?.isObject != true) {
				throw BrregMalformedResponseException("BRREG search response is not a JSON object")
			}
			if (root.get("_links")?.isObject != true || root.get("page")?.isObject != true) {
				throw BrregMalformedResponseException("BRREG search response is missing required paging metadata")
			}
			val embedded = root.get("_embedded")
			val items = embedded?.get(embeddedKey)
			if ((embedded != null && !embedded.isObject) || (items != null && !items.isArray)) {
				throw BrregMalformedResponseException("BRREG search response is missing _embedded.$embeddedKey")
			}
			items?.forEach { item ->
				val record = mapRecord(objectMapper.writeValueAsString(item))
				if (record.type != expectedType(embeddedKey) || record.organizationNumber !in ids) {
					throw BrregMalformedResponseException("BRREG search returned an unexpected record")
				}
				if (records.putIfAbsent(record.organizationNumber, BrregLookupResult.Found(record)) != null) {
					throw BrregMalformedResponseException("BRREG search returned a duplicate organization number")
				}
			}
			val nextLink = root.path("_links").get("next")
			val nextHref = nextLink?.get("href")
			if (nextLink != null && (!nextLink.isObject || nextHref == null ||
				(!nextHref.isNull && !nextHref.isString))
			) {
				throw BrregMalformedResponseException("BRREG search response has an invalid _links.next.href")
			}
			nextUrl = nextHref?.takeIf { it.isString }?.asString()
		}
		return records
	}

	/** Called only when search has no match: 200 maps a record, 410 a removed record */
	private fun fetchDetail(
		id: String,
		path: String,
		mapRecord: (String) -> BrregOrganizationRecord,
	): BrregLookupResult {
		val url = "${baseUri}$path/$id"
		return try {
			val body = request { restClient.get().uri(url).retrieve().body<String>() }
				?: throw BrregMalformedResponseException("BRREG detail response body is missing")
			BrregLookupResult.Found(mapRecord(body))
		} catch (exception: RestClientResponseException) {
			when {
				exception.statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) -> BrregLookupResult.NotFound
				exception.statusCode.isSameCodeAs(HttpStatus.GONE) -> {
					// BRREG's 410 body carries the removed record's organization number and deletion date.
					val body = exception.responseBodyAsString
					if (body.isBlank()) throw BrregMalformedResponseException("BRREG 410 response body is missing")
					BrregLookupResult.Found(mapRecord(body))
				}
				else -> throw exception
			}
		}
	}

	/** Applies the shared bounded retry policy to HTTP request. */
	private fun <T> request(action: () -> T): T =
		Retry.decorateSupplier(retry) { action() }.get()

	/** Rejects pagination links that leave the configured origin or the selected search endpoints. */
	private fun validatePageUrl(url: String): String {
		val uri = URI.create(url)
		require(uri.scheme == baseUri.scheme && uri.host == baseUri.host && uri.port == baseUri.port) {
			"BRREG pagination URL must use the configured origin"
		}
		val expectedPaths = setOf(
			"${baseUri.path.trimEnd('/')}${BrregApiContract.MAIN_ENTITY_PATH}",
			"${baseUri.path.trimEnd('/')}${BrregApiContract.SUBUNIT_PATH}",
		)
		require(uri.path in expectedPaths) {
			"BRREG pagination URL must target a selected search endpoint"
		}
		return uri.toString()
	}

	/** Retries transport failures, server errors, and rate limiting, but not 4xx failures. */
	private fun isRetryable(exception: Throwable): Boolean = when (exception) {
		is ResourceAccessException -> true
		is RestClientResponseException -> exception.statusCode.is5xxServerError ||
			exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
		else -> false
	}

	/** Expects main-entity records for `enheter` searches and subunit records for `underenheter` searches. */
	private fun expectedType(embeddedKey: String) =
		if (embeddedKey == "enheter") BrregRecordType.MAIN_ENTITY else BrregRecordType.SUBUNIT

}

class BrregMalformedResponseException(message: String) : IllegalArgumentException(message)
