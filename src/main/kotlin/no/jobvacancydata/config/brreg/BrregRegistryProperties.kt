package no.jobvacancydata.config.brreg

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("brreg.registry")
data class BrregRegistryProperties(
	val baseUrl: String = "https://data.brreg.no/enhetsregisteret",
	val connectTimeout: Duration = Duration.ofSeconds(5),
	val readTimeout: Duration = Duration.ofSeconds(30),
	val retryMaxAttempts: Int = 3,
	val retryWaitDuration: Duration = Duration.ofMillis(500),
) {
	init {
		val uri = try {
			URI.create(baseUrl)
		} catch (exception: IllegalArgumentException) {
			throw IllegalArgumentException("brreg.registry.base-url must be a valid HTTPS URL", exception)
		}
		require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
			"brreg.registry.base-url must be an HTTPS URL with a host"
		}
		require(!connectTimeout.isZero && !connectTimeout.isNegative) {
			"brreg.registry.connect-timeout must be positive"
		}
		require(!readTimeout.isZero && !readTimeout.isNegative) {
			"brreg.registry.read-timeout must be positive"
		}
		require(retryMaxAttempts >= 1) {
			"brreg.registry.retry-max-attempts must be at least 1"
		}
		require(!retryWaitDuration.isNegative) {
			"brreg.registry.retry-wait-duration must not be negative"
		}
	}
}
