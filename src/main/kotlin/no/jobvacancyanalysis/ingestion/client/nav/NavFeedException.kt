package no.jobvacancyanalysis.ingestion.client.nav

open class NavFeedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NavFeedRetryableException(message: String, cause: Throwable? = null) :
	NavFeedException(message, cause)

class NavFeedUnauthorizedException :
	NavFeedException("NAV feed authorization failed")

class NavFeedRateLimitedException(
	val retryAfter: String?,
) : NavFeedException("NAV feed rate limited")

class NavFeedUnavailableException :
	NavFeedException("NAV feed page unavailable")

class NavFeedMalformedResponseException(message: String) :
	NavFeedException(message)
