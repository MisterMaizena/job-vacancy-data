package no.jobvacancydata.ingestion.client.brreg

import java.io.IOException
import java.net.URI
import java.time.Duration
import no.jobvacancydata.ingestion.client.RetryAfter
import no.jobvacancydata.ingestion.application.brreg.BrregLookupResult
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationLookup
import no.jobvacancydata.ingestion.application.brreg.BrregOrganizationRecord
import org.springframework.http.HttpStatus
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
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
	private val retryPolicy = BrregRetryPolicy(retryMaxAttempts, retryWaitDuration)

	// Validates and batches IDs, then returns each ID's main-entity and subunit outcomes.
	override fun lookup(organizationNumbers: Collection<String>): List<BrregOrganizationLookup> {
		val ids = organizationNumbers.toSortedSet()
		if (ids.isEmpty()) return emptyList()
		return partitionBrregOrganizationNumbers(ids).flatMap(::lookupBatch)
	}

	// Searches both registers, then detail-checks each ID which a completed search did not return.
	private fun lookupBatch(ids: List<String>): List<BrregOrganizationLookup> {
		val mainSearch = searchRegister(ids, BrregApiContract.MAIN_ENTITY_PATH, ENHETER_KEY, mapper::mapMainEntity)
		val subunitSearch = searchRegister(ids, BrregApiContract.SUBUNIT_PATH, UNDERENHETER_KEY, mapper::mapSubunit)
		return ids.map { id ->
			BrregOrganizationLookup(
				organizationNumber = id,
				mainEntity = mainSearch.outcomeFor(id)
					?: fetchDetailOutcome(id, BrregApiContract.MAIN_ENTITY_PATH, mapper::mapMainEntity),
				subunit = subunitSearch.outcomeFor(id)
					?: fetchDetailOutcome(id, BrregApiContract.SUBUNIT_PATH, mapper::mapSubunit),
			)
		}
	}

	// Searches one register across all pages and resolves each requested ID it returns.
	private fun searchRegister(
		ids: Collection<String>,
		path: String,
		embeddedKey: String,
		mapRecord: (String) -> BrregOrganizationRecord,
	): RegisterSearch {
		val outcomes = linkedMapOf<String, BrregLookupResult>()
		var nextUrl: String? = "${baseUri}$path?organisasjonsnummer=${ids.joinToString(",")}"
		try {
			while (nextUrl != null) {
				val root = requestSearchPage(validatePageUrl(nextUrl))
				val embedded = root.get(EMBEDDED_FIELD)
				val items = embedded?.get(embeddedKey)
				if ((embedded != null && !embedded.isObject) || (items != null && !items.isArray)) {
					throw BrregSearchFailedException("BRREG search response is missing _embedded.$embeddedKey")
				}
				items?.forEach { item ->
					collectSearchRecord(item, ids, outcomes, mapRecord)
				}
				nextUrl = nextPageUrl(root)
			}
		} catch (exception: Exception) {
			return RegisterSearch(outcomes, failureReason = searchFailureReason(exception))
		}
		return RegisterSearch(outcomes, failureReason = null)
	}

	private fun collectSearchRecord(
		item: JsonNode,
		ids: Collection<String>,
		outcomes: MutableMap<String, BrregLookupResult>,
		mapRecord: (String) -> BrregOrganizationRecord,
	) {
		val rawOrganizationNumber = item.get(ORGANIZATION_NUMBER_FIELD)
			?.takeIf(JsonNode::isString)
			?.asString()
		val record = try {
			mapRecord(objectMapper.writeValueAsString(item))
		} catch (exception: BrregRecordMappingException) {
			if (rawOrganizationNumber != null && rawOrganizationNumber in ids) {
				outcomes[rawOrganizationNumber] =
					BrregLookupResult.Invalid(exception.report.message(SOURCE_NAME))
				return
			}
			throw BrregSearchFailedException("BRREG search returned a record that failed validation")
		}
		if (record.organizationNumber !in ids) {
			throw BrregSearchFailedException("BRREG search returned an organization number that was not requested")
		}
		if (outcomes.putIfAbsent(record.organizationNumber, BrregLookupResult.Found(record)) != null) {
			outcomes[record.organizationNumber] =
				BrregLookupResult.Invalid("BRREG search returned the organization number more than once")
		}
	}

	private fun requestSearchPage(url: String): JsonNode {
		val body = retryPolicy.execute { restClient.get().uri(url).retrieve().body<String>() }
			?: throw BrregSearchFailedException("BRREG search response body is missing")
		val root = try {
			objectMapper.readTree(body)
		} catch (_: JacksonException) {
			throw BrregSearchFailedException("BRREG search response is not valid JSON")
		}
		if (root?.isObject != true) {
			throw BrregSearchFailedException("BRREG search response is not a JSON object")
		}
		if (root.get(LINKS_FIELD)?.isObject != true || root.get(PAGE_FIELD)?.isObject != true) {
			throw BrregSearchFailedException("BRREG search response is missing required paging metadata")
		}
		return root
	}

	private fun nextPageUrl(root: JsonNode): String? {
		val nextLink = root.path(LINKS_FIELD).get(NEXT_LINK)
		val nextHref = nextLink?.get(HREF_FIELD)
		if (nextLink != null && (!nextLink.isObject || nextHref == null ||
				(!nextHref.isNull && !nextHref.isString))
		) {
			throw BrregSearchFailedException("BRREG search response has an invalid _links.next.href")
		}
		return nextHref?.takeIf(JsonNode::isString)?.asString()
	}

	// If a completed search omits the organization number, check the detail endpoint:
	// 200 returns a record, 404 means no record exists, and 410 may return a removed record.
	private fun fetchDetailOutcome(
		id: String,
		path: String,
		mapRecord: (String) -> BrregOrganizationRecord,
	): BrregLookupResult {
		val url = "${baseUri}$path/$id"
		return try {
			val body = retryPolicy.execute { restClient.get().uri(url).retrieve().body<String>() }
				?: return BrregLookupResult.Failed("BRREG detail response body is missing")
			BrregLookupResult.Found(mapRecord(body))
		} catch (exception: RestClientResponseException) {
			when {
				exception.statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) -> BrregLookupResult.NotFound
				exception.statusCode.isSameCodeAs(HttpStatus.GONE) -> goneDetailOutcome(exception, mapRecord)
				else -> BrregLookupResult.Failed(
					"BRREG detail lookup failed with HTTP ${exception.statusCode.value()}",
				)
			}
		} catch (exception: ResourceAccessException) {
			BrregLookupResult.Failed("BRREG detail lookup failed with a transport error")
		} catch (exception: BrregRecordMappingException) {
			BrregLookupResult.Invalid(exception.report.message(SOURCE_NAME))
		}
	}

	private fun goneDetailOutcome(
		exception: RestClientResponseException,
		mapRecord: (String) -> BrregOrganizationRecord,
	): BrregLookupResult {
		// BRREG's 410 body carries the removed record's organization number and deletion date.
		val body = exception.responseBodyAsString
		if (body.isBlank()) return BrregLookupResult.Failed("BRREG 410 response body is missing")
		return try {
			BrregLookupResult.Found(mapRecord(body))
		} catch (mappingException: BrregRecordMappingException) {
			BrregLookupResult.Invalid(mappingException.report.message(SOURCE_NAME))
		}
	}

	// Reject pagination links outside the configured origin or selected search endpoints.
	private fun validatePageUrl(url: String): String {
		val uri = try {
			URI.create(url)
		} catch (exception: IllegalArgumentException) {
			throw BrregSearchFailedException("BRREG pagination URL is invalid")
		}
		if (uri.scheme != baseUri.scheme || uri.host != baseUri.host || uri.port != baseUri.port) {
			throw BrregSearchFailedException("BRREG pagination URL must use the configured origin")
		}
		val expectedPaths = setOf(
			"${baseUri.path.trimEnd('/')}${BrregApiContract.MAIN_ENTITY_PATH}",
			"${baseUri.path.trimEnd('/')}${BrregApiContract.SUBUNIT_PATH}",
		)
		if (uri.path !in expectedPaths) {
			throw BrregSearchFailedException("BRREG pagination URL must target a selected search endpoint")
		}
		return uri.toString()
	}

	// Failure reasons carry the failure kind, never response content.
	private fun searchFailureReason(exception: Exception): String = when (exception) {
		is BrregSearchFailedException -> exception.message!!
		is RestClientResponseException -> "BRREG search failed with HTTP ${exception.statusCode.value()}"
		is ResourceAccessException -> "BRREG search failed with a transport error"
		else -> "BRREG search failed unexpectedly (${exception.javaClass.simpleName})"
	}

	private companion object {
		private const val ENHETER_KEY = "enheter"
		private const val UNDERENHETER_KEY = "underenheter"
		private const val EMBEDDED_FIELD = "_embedded"
		private const val LINKS_FIELD = "_links"
		private const val PAGE_FIELD = "page"
		private const val NEXT_LINK = "next"
		private const val HREF_FIELD = "href"
		private const val ORGANIZATION_NUMBER_FIELD = "organisasjonsnummer"
		private const val SOURCE_NAME = "BRREG"
	}
}

// Per-register search result: resolved outcomes plus why unresolved IDs must stay pending.
private class RegisterSearch(
	private val outcomes: Map<String, BrregLookupResult>,
	private val failureReason: String?,
) {
	// Null means the search completed without this ID; fall back to the detail endpoint.
	fun outcomeFor(id: String): BrregLookupResult? =
		outcomes[id] ?: failureReason?.let(BrregLookupResult::Failed)
}

// Internal marker for a search page that cannot be trusted or completed.
private class BrregSearchFailedException(message: String) : RuntimeException(message)

// Retries BRREG requests a limited number of times and caps total rate-limit waiting at "waitDuration"
private class BrregRetryPolicy(
	private val maxAttempts: Int,
	private val waitDuration: Duration,
) {
	fun <T> execute(action: () -> T): T {
		var attempt = 1
		var accumulatedRateLimitWait = Duration.ZERO
		while (true) {
			try {
				return action()
			} catch (exception: ResourceAccessException) {
				if (attempt >= maxAttempts) throw exception
				sleep(waitDuration)
			} catch (exception: RestClientResponseException) {
				val retryable = exception.statusCode.is5xxServerError ||
					exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
				if (!retryable || attempt >= maxAttempts) throw exception
				val serverDelay = retryAfterHint(exception)
				val delay = maxOf(waitDuration, serverDelay ?: waitDuration)
				// If this wait would exceed the 30-second limit, stop rather than retry before BRREG's requested time.
				if (exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) &&
					delay > MAX_RETRY_AFTER_WAIT - accumulatedRateLimitWait
				) throw exception
				if (exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
					accumulatedRateLimitWait += delay
				}
				sleep(delay)
			}
			attempt++
		}
	}

	private fun retryAfterHint(exception: RestClientResponseException): Duration? =
		if (exception.statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
			RetryAfter.delay(exception.responseHeaders?.getFirst("Retry-After"))
		} else {
			null
		}

	private fun sleep(duration: Duration) {
		try {
			Thread.sleep(duration.toMillis())
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw ResourceAccessException(
				"Interrupted while waiting to retry a BRREG request",
				IOException(exception),
			)
		}
	}

	// Declared in-file since it's fixed policy for this specific retry logic
	private companion object {
		private val MAX_RETRY_AFTER_WAIT: Duration = Duration.ofSeconds(30)
	}
}
