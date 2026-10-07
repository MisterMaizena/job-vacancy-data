package no.jobvacancyanalysis.config.nav

import java.net.http.HttpClient
import no.jobvacancyanalysis.ingestion.client.nav.NavFeedClient
import no.jobvacancyanalysis.ingestion.client.nav.NavFeedPageMapper
import no.jobvacancyanalysis.ingestion.client.nav.NavFeedRestClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(NavFeedProperties::class)
class NavFeedConfig {

	@Bean
	fun navFeedClient(
		mapper: NavFeedPageMapper,
		properties: NavFeedProperties,
	): NavFeedClient {
		require(properties.token.isNotBlank()) { "nav.feed.token must not be blank" }

		val requestFactory = JdkClientHttpRequestFactory(
			HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout)
				.build(),
		).apply {
			setReadTimeout(properties.readTimeout)
		}

		val restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader("Authorization", "Bearer ${properties.token}")
			.build()

		return NavFeedRestClient(
			restClient = restClient,
			mapper = mapper,
			baseUrl = properties.baseUrl,
			retryMaxAttempts = properties.retryMaxAttempts,
			retryWaitDuration = properties.retryWaitDuration,
		)
	}
}
