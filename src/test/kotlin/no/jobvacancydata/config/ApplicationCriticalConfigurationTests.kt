package no.jobvacancydata.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ApplicationCriticalConfigurationTests {
	@Test
	fun `requires NAV token when NAV ingestion is enabled`() {
		assertThatThrownBy {
			ApplicationCriticalConfiguration().navFeedToken(ApplicationCriticalProperties())
		}
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("required when NAV ingestion is enabled")
	}

	@Test
	fun `provides NAV token from critical properties`() {
		val token = ApplicationCriticalConfiguration()
			.navFeedToken(ApplicationCriticalProperties("synthetic-token"))

		assertThat(token).isEqualTo("synthetic-token")
	}
}
