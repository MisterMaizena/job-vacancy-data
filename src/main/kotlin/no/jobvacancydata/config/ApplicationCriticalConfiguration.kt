package no.jobvacancydata.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(
	prefix = "app.ingestion.nav",
	name = ["enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
annotation class ConditionalOnNavIngestion

@Configuration
class ApplicationCriticalConfiguration {
	@Bean
	@ConditionalOnNavIngestion
	fun navFeedToken(properties: ApplicationCriticalProperties): String {
		val token = properties.navFeedToken?.takeIf(String::isNotBlank)
			?: throw IllegalStateException(
				"app.critical.nav-feed-token is required when NAV ingestion is enabled",
			)
		return token
	}
}
