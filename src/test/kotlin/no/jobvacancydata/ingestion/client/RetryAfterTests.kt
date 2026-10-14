package no.jobvacancydata.ingestion.client

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RetryAfterTests {
	private val now = Instant.parse("2026-10-14T12:00:00Z")

	@Test
	fun `parses delay seconds`() {
		assertThat(RetryAfter.delay("12", now)).isEqualTo(Duration.ofSeconds(12))
	}

	@Test
	fun `parses HTTP date`() {
		assertThat(RetryAfter.delay("Wed, 14 Oct 2026 12:00:12 GMT", now))
			.isEqualTo(Duration.ofSeconds(12))
	}

	@Test
	fun `past dates become zero and invalid values are absent`() {
		assertThat(RetryAfter.delay("Wed, 14 Oct 2026 11:59:00 GMT", now)).isEqualTo(Duration.ZERO)
		assertThat(RetryAfter.delay("not-a-date", now)).isNull()
		assertThat(RetryAfter.delay("-1", now)).isNull()
	}
}
