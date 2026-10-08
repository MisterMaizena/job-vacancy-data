package no.jobvacancydata.ingestion.client.brreg

import java.net.URI
import java.time.Duration
import java.util.function.Supplier
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
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

internal const val BRREG_MAX_ORGANIZATION_NUMBERS_PER_REQUEST = 2_000

internal fun partitionBrregOrganizationNumbers(
	organizationNumbers: Collection<String>,
): List<List<String>> = organizationNumbers.toSortedSet()
	.chunked(BRREG_MAX_ORGANIZATION_NUMBERS_PER_REQUEST)

class BrregRestClient(
	private val restClient: RestClient,
	private val mapper: BrregRecordMapper,
	private val objectMapper: ObjectMapper,
	baseUrl: String,
	retryMaxAttempts: Int = 3,
	retryWaitDuration: Duration = Duration.ofMillis(500),
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

	init {
		require(baseUri.scheme == "https" && !baseUri.host.isNullOrBlank()) {
			"BRREG base URL must be an HTTPS URL with a host"
		}
		require(retryMaxAttempts >= 1) { "BRREG retry max attempts must be at least 1" }
		require(!retryWaitDuration.isNegative) { "BRREG retry wait duration must not be negative" }
	}

	override fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup> {
		val ids = organizationNumbers.toSortedSet()
		if (ids.isEmpty()) return emptyList()
		require(ids.all { ORGANIZATION_NUMBER_REGEX.matches(it) }) {
			"BRREG organization numbers must contain exactly nine digits"
		}
		return partitionBrregOrganizationNumbers(ids).flatMap(::lookupBatch)
	}

	private fun lookupBatch(ids: List<String>): List<BrregOrganizationLookup> {
		val main = search(ids, MAIN_SEARCH_PATH, "enheter", mapper::mapMainEntity)
		val subunits = search(ids, SUBUNIT_SEARCH_PATH, "underenheter", mapper::mapSubunit)
		return ids.map { id ->
			BrregOrganizationLookup(
				organizationNumber = id,
				mainEntity = main[id] ?: fetchDetail(id, MAIN_DETAIL_PATH, mapper::mapMainEntity),
				subunit = subunits[id] ?: fetchDetail(id, SUBUNIT_DETAIL_PATH, mapper::mapSubunit),
			)
		}
	}

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
			val body = request { restClient.get().uri(url).retrieve().body(String::class.java) }
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

	private fun fetchDetail(
		id: String,
		path: String,
		mapRecord: (String) -> BrregOrganizationRecord,
	): BrregLookupResult {
		val url = "${baseUri.toString()}$path/$id"
		return try {
			val body = request { restClient.get().uri(url).retrieve().body(String::class.java) }
				?: throw BrregMalformedResponseException("BRREG detail response body is missing")
			BrregLookupResult.Found(mapRecord(body))
		} catch (exception: RestClientResponseException) {
			when {
				exception.statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) -> BrregLookupResult.NotFound
				exception.statusCode.isSameCodeAs(HttpStatus.GONE) -> {
					val body = exception.responseBodyAsString
					if (body.isBlank()) throw BrregMalformedResponseException("BRREG 410 response body is missing")
					BrregLookupResult.Found(mapRecord(body))
				}
				else -> throw exception
			}
		}
	}

	private fun <T> request(action: () -> T): T =
		Retry.decorateSupplier(retry, Supplier { action() }).get()

	private fun validatePageUrl(url: String): String {
		val uri = URI.create(url)
		require(uri.scheme == baseUri.scheme && uri.host == baseUri.host && uri.port == baseUri.port) {
			"BRREG pagination URL must use the configured origin"
		}
		val expectedPaths = setOf(
			"${baseUri.path.trimEnd('/')}$MAIN_SEARCH_PATH",
			"${baseUri.path.trimEnd('/')}$SUBUNIT_SEARCH_PATH",
		)
		require(uri.path in expectedPaths) {
			"BRREG pagination URL must target a selected search endpoint"
		}
		return uri.toString()
	}

	private fun isRetryable(exception: Throwable): Boolean = when (exception) {
		is ResourceAccessException -> true
		is RestClientResponseException -> exception.statusCode.is5xxServerError ||
			exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
		else -> false
	}

	private fun expectedType(embeddedKey: String) =
		if (embeddedKey == "enheter") BrregRecordType.MAIN_ENTITY else BrregRecordType.SUBUNIT

	private companion object {
		val ORGANIZATION_NUMBER_REGEX = Regex("\\d{9}")
		const val MAIN_SEARCH_PATH = "/api/enheter"
		const val SUBUNIT_SEARCH_PATH = "/api/underenheter"
		const val MAIN_DETAIL_PATH = "/api/enheter"
		const val SUBUNIT_DETAIL_PATH = "/api/underenheter"
	}
}

class BrregMalformedResponseException(message: String) : IllegalArgumentException(message)
