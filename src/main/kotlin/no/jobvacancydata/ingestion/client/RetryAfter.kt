package no.jobvacancydata.ingestion.client

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal object RetryAfter {
	// Accepts seconds or an HTTP date; ignores invalid values and treats past dates as zero
	fun delay(value: String?, now: Instant = Instant.now()): Duration? {
		if (value == null) return null
		value.toLongOrNull()?.let { seconds ->
			return if (seconds >= 0) Duration.ofSeconds(seconds) else null
		}
		return try {
			val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
			Duration.between(now, target).let { if (it.isNegative) Duration.ZERO else it }
		} catch (_: DateTimeParseException) {
			null
		}
	}
}
