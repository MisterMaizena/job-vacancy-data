package no.jobvacancyanalysis.ingestion.client.nav

/** Base for NAV request/response failures; subclasses identify how the caller should handle them. */
open class NavFeedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A temporary failure that the client may retry. */
class NavFeedRetryableException(message: String, cause: Throwable? = null) :
	NavFeedException(message, cause)

/** NAV rejected the credentials; retrying unchanged credentials will not help. */
class NavFeedUnauthorizedException :
	NavFeedException("NAV feed authorization failed")

/** NAV throttled the request; retryAfter holds its suggested delay, if supplied. */
class NavFeedRateLimitedException(
	val retryAfter: String?,
) : NavFeedException("NAV feed rate limited")

/** The requested feed resource is unavailable. */
class NavFeedUnavailableException :
	NavFeedException("NAV feed page unavailable")

/** Base for responses with a missing body or content that cannot be mapped to a NAV model. */
open class NavFeedMalformedResponseException(
	message: String,
	cause: Throwable? = null,
) : NavFeedException(message, cause)
