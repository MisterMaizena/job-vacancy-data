package no.jobvacancydata.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.critical")
data class ApplicationCriticalProperties(
	/** Required when `app.ingestion.nav.enabled=true` (default). */
	val navFeedToken: String? = null,
)
