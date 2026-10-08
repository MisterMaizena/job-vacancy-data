package no.jobvacancydata.config.nav

import java.net.http.HttpClient
import no.jobvacancydata.config.ConditionalOnNavIngestion
import no.jobvacancydata.ingestion.client.nav.NavFeedClient
import no.jobvacancydata.ingestion.client.nav.NavFeedPageMapper
import no.jobvacancydata.ingestion.client.nav.NavFeedRestClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
@ConditionalOnNavIngestion
class NavFeedConfiguration {

	@Bean
	fun navFeedClient(
		mapper: NavFeedPageMapper,
		properties: NavFeedProperties,
		@Qualifier("navFeedToken") navFeedToken: String,
	): NavFeedClient {
		val requestFactory = JdkClientHttpRequestFactory(
			HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout)
				.build(),
		).apply {
			setReadTimeout(properties.readTimeout)
		}

		val restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader("Authorization", "Bearer $navFeedToken")
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
