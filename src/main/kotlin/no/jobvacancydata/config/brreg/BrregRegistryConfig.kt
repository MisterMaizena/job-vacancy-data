package no.jobvacancydata.config.brreg

import java.net.http.HttpClient
import no.jobvacancydata.ingestion.client.brreg.BrregRecordMapper
import no.jobvacancydata.ingestion.client.brreg.BrregRestClient
import no.jobvacancydata.ingestion.client.brreg.BrregRegistryClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(BrregRegistryProperties::class)
class BrregRegistryConfig {
	@Bean
	fun brregRegistryClient(
		mapper: BrregRecordMapper,
		objectMapper: ObjectMapper,
		properties: BrregRegistryProperties,
	): BrregRegistryClient = BrregRestClient(
		restClient = RestClient.builder()
			.requestFactory(
				JdkClientHttpRequestFactory(
					HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
				).apply { setReadTimeout(properties.readTimeout) },
			)
			.build(),
		mapper = mapper,
		objectMapper = objectMapper,
		baseUrl = properties.baseUrl,
		retryMaxAttempts = properties.retryMaxAttempts,
		retryWaitDuration = properties.retryWaitDuration,
	)
}
