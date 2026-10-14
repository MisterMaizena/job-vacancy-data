package no.jobvacancydata.ingestion.client.nav

import java.io.IOException
import java.net.URI
import java.time.Duration
import no.jobvacancydata.ingestion.application.nav.FeedFetchResult
import no.jobvacancydata.ingestion.application.nav.FeedPageCursor
import no.jobvacancydata.ingestion.application.nav.VacancyDetailFetchResult
import no.jobvacancydata.ingestion.client.RetryAfter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

class NavFeedRestClient(
	private val restClient: RestClient,
	private val pageMapper: NavFeedPageMapper,
	private val detailMapper: NavVacancyDetailResponseMapper,
	baseUrl: String,
	retryMaxAttempts: Int,
	retryWaitDuration: Duration,
) : NavFeedClient {

	private val baseUri: URI = parseBaseUrl(baseUrl)
	private val retryPolicy = NavRetryPolicy(retryMaxAttempts, retryWaitDuration)

	override fun fetchPage(cursor: FeedPageCursor?): FeedFetchResult =
		retryPolicy.execute { fetchPageOnce(cursor) }

	override fun fetchVacancyDetail(detailUrl: String): VacancyDetailFetchResult =
		retryPolicy.execute { fetchVacancyDetailOnce(detailUrl) }

	private fun fetchPageOnce(cursor: FeedPageCursor?): FeedFetchResult {
		val targetUrl = cursor?.let { validateFeedUrl(it.url) } ?: initialFeedUrl()

		val spec = restClient.get().uri(targetUrl)
		cursor?.etag?.let { spec.header("If-None-Match", it) }
		cursor?.lastModified?.let { spec.header("If-Modified-Since", it) }

		val response = spec.retrieve()
			.onStatus({ it.isError }) { _, clientResponse ->
				throwForErrorStatus(clientResponse.statusCode, clientResponse.headers, "NAV feed")
			}
			.toEntity<String>()

		return when {
			response.statusCode.isSameCodeAs(HttpStatus.NOT_MODIFIED) -> FeedFetchResult.Unchanged
			response.statusCode.isSameCodeAs(HttpStatus.OK) -> {
				val body = response.body
					?: throw NavFeedMalformedResponseException(
						"NAV feed response body missing for $targetUrl",
					)
				val page = pageMapper.mapFeedPage(body)
				// These response headers belong to this page; the next page gets its own when requested.
				FeedFetchResult.Page(
					page = page,
					cursor = FeedPageCursor(
						url = targetUrl,
						etag = response.headers.getFirst("ETag"),
						lastModified = response.headers.getFirst("Last-Modified"),
					),
					nextCursor = page.nextUrl?.let { FeedPageCursor(resolveContinuationUrl(it)) },
				)
			}
			else -> throw NavFeedException("Unexpected NAV feed response status ${response.statusCode} for $targetUrl")
		}
	}

	private fun fetchVacancyDetailOnce(detailUrl: String): VacancyDetailFetchResult {
		val targetUrl = validateDetailUrl(detailUrl)

		val response = restClient.get().uri(targetUrl).retrieve()
			// 404 is an expected outcome. This empty handler stops RestClient from throwing
			// so the status can be mapped to NotFound below.
			.onStatus({ it.isSameCodeAs(HttpStatus.NOT_FOUND) }) { _, _ -> }
			.onStatus({ it.isError }) { _, clientResponse ->
				throwForErrorStatus(clientResponse.statusCode, clientResponse.headers, "NAV vacancy detail")
			}
			.toEntity<String>()

		return when {
			response.statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) -> VacancyDetailFetchResult.NotFound
			response.statusCode.isSameCodeAs(HttpStatus.OK) -> {
				val body = response.body
					?: throw NavFeedMalformedResponseException(
						"NAV vacancy detail response body missing for $targetUrl",
					)
				val detail = detailMapper.mapVacancyDetailResponse(body)
				verifyDetailIdentity(targetUrl, detail.uuid)
				VacancyDetailFetchResult.Fetched(detail)
			}
			else -> throw NavFeedException(
				"Unexpected NAV vacancy detail response status ${response.statusCode} for $targetUrl",
			)
		}
	}

	// Maps an error status to the failure type the caller must handle
	private fun throwForErrorStatus(
		statusCode: HttpStatusCode,
		headers: HttpHeaders,
		resource: String,
	): Nothing {
		when {
			statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) ||
				statusCode.isSameCodeAs(HttpStatus.FORBIDDEN) ->
				throw NavFeedUnauthorizedException()

			statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) ->
				throw NavFeedRateLimitedException(headers.getFirst("Retry-After"))

			statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) ||
				statusCode.isSameCodeAs(HttpStatus.GONE) ->
				throw NavFeedUnavailableException()

			statusCode.is5xxServerError ||
				statusCode.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT) ->
				throw NavFeedRetryableException(
					"$resource request failed with retryable status $statusCode",
				)

			else -> throw NavFeedException("$resource request failed with status $statusCode")
		}
	}

	// The returned entry identity must match the requested URL's entry ID
	private fun verifyDetailIdentity(targetUrl: String, responseUuid: String) {
		val entryId = URI.create(targetUrl).path.removePrefix(NavFeedPaths.FEED_ENTRY_PATH)
		if (!entryId.equals(responseUuid, ignoreCase = true)) {
			throw NavFeedIdentityMismatchException(
				"NAV vacancy detail identity does not match the requested feed entry $entryId",
			)
		}
	}

	private fun initialFeedUrl(): String =
		validateFeedUrl(baseUri.resolve(NavFeedPaths.FEED_PATH).toString())

	private fun validateFeedUrl(url: String): String {
		val uri = URI.create(url)
		requireSameOrigin(uri, url)
		requireValidFeedUri(uri, url, "NAV feed")
		return uri.normalize().toString()
	}

	private fun validateDetailUrl(url: String): String {
		try {
			val uri = URI.create(url)
			requireSameOrigin(uri, url)
			require(uri.fragment == null) {
				"NAV vacancy detail URL must not contain a fragment"
			}
			val path = uri.path ?: ""
			require(path.startsWith(NavFeedPaths.FEED_ENTRY_PATH)) {
				"NAV vacancy detail URL has an unexpected path"
			}
			val entryId = path.removePrefix(NavFeedPaths.FEED_ENTRY_PATH)
			require(entryId.isNotEmpty() && !entryId.contains('/')) {
				"NAV vacancy detail URL must identify a single feed entry"
			}
			return uri.normalize().toString()
		} catch (exception: IllegalArgumentException) {
			throw NavFeedInvalidDetailUrlException()
		}
	}

	private fun resolveContinuationUrl(nextUrl: String): String {
		try {
			val nextUri = URI.create(nextUrl)
			require(nextUri.scheme == null && nextUri.host == null) {
				"NAV continuation URL must be relative"
			}
			requireValidFeedUri(nextUri, nextUrl, "NAV continuation")
			val resolved = baseUri.resolve(nextUri).normalize()
			requireSameOrigin(resolved, resolved.toString())
			return resolved.toString()
		} catch (_: IllegalArgumentException) {
			throw NavFeedMalformedResponseException("NAV feed response contains an invalid continuation URL")
		}
	}

	private fun requireSameOrigin(uri: URI, url: String) {
		require(uri.scheme == baseUri.scheme && uri.host == baseUri.host && uri.port == baseUri.port) {
			"NAV feed URL must target the configured origin, got: $url"
		}
	}

	private fun requireValidFeedUri(uri: URI, url: String, context: String) {
		require(uri.fragment == null) {
			"$context URL must not contain a fragment, got: $url"
		}
		require(uri.path?.startsWith(NavFeedPaths.FEED_PATH) == true) {
			"$context URL path must start with ${NavFeedPaths.FEED_PATH}, got: $url"
		}
	}

	private fun parseBaseUrl(baseUrl: String): URI {
		require(baseUrl.isNotBlank()) { "NAV feed base URL must not be blank" }
		val uri = try {
			URI.create(baseUrl)
		} catch (exception: IllegalArgumentException) {
			throw NavFeedException("NAV feed base URL is invalid: $baseUrl", exception)
		}
		require(uri.scheme == "https" || uri.scheme == "http") {
			"NAV feed base URL must use http or https, got: $baseUrl"
		}
		require(uri.host?.isNotBlank() == true) {
			"NAV feed base URL must have a host: $baseUrl"
		}
		return uri
	}
}

private class NavRetryPolicy(
	private val maxAttempts: Int,
	private val waitDuration: Duration,
) {
	fun <T> execute(action: () -> T): T {
		var attempt = 1
		var accumulatedRateLimitWait = Duration.ZERO
		while (true) {
			try {
				return action()
			} catch (exception: NavFeedRateLimitedException) {
				if (attempt >= maxAttempts) throw exception
				val serverDelay = RetryAfter.delay(exception.retryAfter)
				val delay = maxOf(waitDuration, serverDelay ?: waitDuration)
				// If this wait would exceed the 30-second limit, stop rather than retry before NAV's requested time.
				if (delay > MAX_RATE_LIMIT_WAIT - accumulatedRateLimitWait) throw exception
				accumulatedRateLimitWait += delay
				sleep(delay)
			} catch (exception: ResourceAccessException) {
				if (attempt >= maxAttempts) throw exception
				sleep(waitDuration)
			} catch (exception: NavFeedRetryableException) {
				if (attempt >= maxAttempts) throw exception
				sleep(waitDuration)
			}
			attempt++
		}
	}

	private fun sleep(duration: Duration) {
		try {
			Thread.sleep(duration.toMillis())
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw ResourceAccessException(
				"Interrupted while waiting to retry a NAV request",
				IOException(exception),
			)
		}
	}

	// Declared in-file since it's fixed policy for this specific retry logic
	private companion object {
		private val MAX_RATE_LIMIT_WAIT: Duration = Duration.ofSeconds(30)
	}
}
