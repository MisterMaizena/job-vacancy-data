package no.jobvacancydata.ingestion.client.nav

import no.jobvacancydata.ingestion.client.validation.MappingError
import no.jobvacancydata.ingestion.client.validation.MappingErrorReport

/**
 * NAV mapping result: either a complete mapped value or errors with no value.
 * Use Valid(null) when an optional field is absent or null.
 */
sealed interface MappingResult<out T> {
	data class Valid<T>(val value: T) : MappingResult<T>

	data class Invalid(val report: MappingErrorReport) : MappingResult<Nothing> {
		init {
			require(report.errors.isNotEmpty()) { "Invalid mapping results must contain errors" }
		}
	}
}

fun invalidMapping(path: String, expected: String, actual: String): MappingResult.Invalid =
	MappingResult.Invalid(
		MappingErrorReport(listOf(MappingError(path, expected, actual))),
	)

/** Builds the model only if every result is valid; combines all errors otherwise. */
fun <T> buildWhenValid(
	results: List<MappingResult<*>>,
	build: () -> T,
): MappingResult<T> {
	val errors = results.flatMap { result ->
		when (result) {
			is MappingResult.Valid -> emptyList()
			is MappingResult.Invalid -> result.report.errors
		}
	}
	return if (errors.isNotEmpty()) {
		MappingResult.Invalid(MappingErrorReport(errors))
	} else {
		MappingResult.Valid(build())
	}
}

/** Changes a valid value and leaves an invalid result unchanged. */
fun <T, R> MappingResult<T>.mapValidValue(transform: (T) -> R): MappingResult<R> =
	when (this) {
		is MappingResult.Valid -> MappingResult.Valid(transform(value))
		is MappingResult.Invalid -> this
	}

/** Runs the next check only when this result is valid. */
fun <T, R> MappingResult<T>.validateNext(
	transform: (T) -> MappingResult<R>,
): MappingResult<R> =
	when (this) {
		is MappingResult.Valid -> transform(value)
		is MappingResult.Invalid -> this
	}

/** Checks every list item and collects errors; returns a list only if all pass. */
fun <T, R> MappingResult<List<T>>.validateEach(
	transform: (index: Int, value: T) -> MappingResult<R>,
): MappingResult<List<R>> =
	validateNext { values ->
		val mapped = values.mapIndexed(transform)
		buildWhenValid(mapped) {
			mapped.map(MappingResult<R>::valueForValidResult)
		}
	}

/** Reads a value inside the successful build step; using it on an invalid result is a coding error. */
fun <T> MappingResult<T>.valueForValidResult(): T =
	when (this) {
		is MappingResult.Valid -> value
		is MappingResult.Invalid ->
			throw IllegalStateException("Cannot extract a value from an invalid mapping result")
	}

/** Returns the value if valid; otherwise throws the given exception with the error report. */
fun <T> MappingResult<T>.valueOrThrow(
	exception: (MappingErrorReport) -> RuntimeException,
): T =
	when (this) {
		is MappingResult.Valid -> value
		is MappingResult.Invalid -> throw exception(report)
	}
