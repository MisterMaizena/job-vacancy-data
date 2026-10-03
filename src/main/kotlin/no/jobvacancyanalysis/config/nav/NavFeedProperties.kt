package no.jobvacancyanalysis.config.nav

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("nav.feed")
data class NavFeedProperties(
	val baseUrl: String,
	val token: String,
	val connectTimeout: Duration = Duration.ofSeconds(5),
	val readTimeout: Duration = Duration.ofSeconds(30),
	// Counts the initial request as attempt one.
	val retryMaxAttempts: Int = 3,
	val retryWaitDuration: Duration = Duration.ofMillis(500),
) {
	init {
		require(!connectTimeout.isZero && !connectTimeout.isNegative) {
			"nav.feed.connect-timeout must be positive"
		}
		require(!readTimeout.isZero && !readTimeout.isNegative) {
			"nav.feed.read-timeout must be positive"
		}
		require(retryMaxAttempts >= 1) {
			"nav.feed.retry-max-attempts must be at least 1"
		}
		require(!retryWaitDuration.isNegative) {
			"nav.feed.retry-wait-duration must not be negative"
		}
	}
}
