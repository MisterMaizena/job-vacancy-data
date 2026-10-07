package no.jobvacancyanalysis.ingestion.client.nav

import java.net.URI
import java.time.Duration
import java.util.function.Supplier
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import no.jobvacancyanalysis.ingestion.application.nav.FeedFetchResult
import no.jobvacancyanalysis.ingestion.application.nav.FeedPageCursor
import org.springframework.http.HttpStatus
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

class NavFeedRestClient(
	private val restClient: RestClient,
	private val mapper: NavFeedPageMapper,
	baseUrl: String,
	retryMaxAttempts: Int,
	retryWaitDuration: Duration,
) : NavFeedClient {

	private val baseUri: URI = parseBaseUrl(baseUrl)
	private val retry = Retry.of(
		"nav-feed",
		RetryConfig.custom<FeedFetchResult>()
			.maxAttempts(retryMaxAttempts)
			.waitDuration(retryWaitDuration)
			.retryOnException { exception ->
				exception is ResourceAccessException || exception is NavFeedRetryableException
			}
			.build(),
	)

	/**
	 * Fetches one page. Transport and retryable server failures are retried;
	 * mapping failures propagate to the caller without retry.
	 */
	override fun fetchPage(cursor: FeedPageCursor?): FeedFetchResult =
		Retry.decorateSupplier(retry, Supplier { fetchPageOnce(cursor) }).get()

	private fun fetchPageOnce(cursor: FeedPageCursor?): FeedFetchResult {
		val targetUrl = cursor?.let { validateFeedUrl(it.url) } ?: initialFeedUrl()

		val spec = restClient.get().uri(targetUrl)
		cursor?.etag?.let { spec.header("If-None-Match", it) }
		cursor?.lastModified?.let { spec.header("If-Modified-Since", it) }

		val response = spec.retrieve()
			.onStatus({ it.isError }) { _, clientResponse ->
				val statusCode = clientResponse.statusCode
				when {
					statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) ||
						statusCode.isSameCodeAs(HttpStatus.FORBIDDEN) ->
						throw NavFeedUnauthorizedException()

					statusCode.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) ->
						throw NavFeedRateLimitedException(clientResponse.headers.getFirst("Retry-After"))

					statusCode.isSameCodeAs(HttpStatus.NOT_FOUND) ||
						statusCode.isSameCodeAs(HttpStatus.GONE) ->
						throw NavFeedUnavailableException()

					statusCode.is5xxServerError ||
						statusCode.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT) ->
						throw NavFeedRetryableException(
							"NAV feed request failed with retryable status $statusCode",
						)

					else -> throw NavFeedException("NAV feed request failed with status $statusCode")
				}
			}
			.toEntity<String>()

		return when {
			response.statusCode.isSameCodeAs(HttpStatus.NOT_MODIFIED) -> FeedFetchResult.Unchanged
			response.statusCode.isSameCodeAs(HttpStatus.OK) -> {
				val body = response.body
					?: throw NavFeedMalformedResponseException(
						"NAV feed response body missing for $targetUrl",
					)
				val page = mapper.mapFeedPage(body)
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

	private fun initialFeedUrl(): String =
		validateFeedUrl(baseUri.resolve(NavFeedPaths.FEED_PATH).toString())

	private fun validateFeedUrl(url: String): String {
		val uri = URI.create(url)
		requireSameOrigin(uri, url)
		requireValidFeedUri(uri, url, "NAV feed")
		return uri.normalize().toString()
	}

	private fun resolveContinuationUrl(nextUrl: String): String {
		val nextUri = URI.create(nextUrl)
		require(nextUri.scheme == null && nextUri.host == null) {
			"NAV continuation URL must be relative, got: $nextUrl"
		}
		requireValidFeedUri(nextUri, nextUrl, "NAV continuation")
		val resolved = baseUri.resolve(nextUri).normalize()
		requireSameOrigin(resolved, resolved.toString())
		return resolved.toString()
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
