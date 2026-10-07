package no.jobvacancyanalysis.ingestion.client.brreg

import no.jobvacancyanalysis.ingestion.application.brreg.BrregLifecycleStatus
import no.jobvacancyanalysis.ingestion.application.brreg.BrregRecordType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.json.JsonMapper

class BrregRecordMapperTests {
	private val jsonMapper = JsonMapper.builder().build()
	private val mapper = BrregRecordMapper(jsonMapper)

	@Test
	fun `projects allowlisted fields and preserves source JSON types and nullable values`() {
		val record = mapper.mapMainEntity(validMainEntityJson())

		assertThat(record.type).isEqualTo(BrregRecordType.MAIN_ENTITY)
		assertThat(record.lifecycleStatus).isEqualTo(BrregLifecycleStatus.CURRENT)
		assertThat(record.organizationNumber).isEqualTo("509100675")
		assertThat(record.fields.path("organisasjonsnummer").isString).isTrue()
		assertThat(record.fields.path("maalform").isString).isTrue()
		assertThat(record.fields.path("antallAnsatte").isNumber).isTrue()
		assertThat(record.fields.path("konkurs").isBoolean).isTrue()
		assertThat(record.fields.has("hjemmeside")).isTrue()
		assertThat(record.fields.path("hjemmeside").isNull).isTrue()
		assertThat(record.fields.has("stiftelsesdato")).isFalse()
		assertThat(record.fields.path("postadresse").path("poststed").asString())
			.isEqualTo("Synthetic town")
		assertThat(record.fields.path("postadresse").has("postnummer")).isTrue()
		assertThat(record.fields.path("postadresse").path("postnummer").isNull).isTrue()
		assertThat(record.fields.path("postadresse").has("unknown_address_field")).isFalse()
		assertThat(record.fields.path("naeringskode1").isNull).isTrue()
		assertThat(record.fields.has("epostadresse")).isFalse()
		assertThat(record.fields.has("_links")).isFalse()
		assertThat(record.fields.has("unknown_field")).isFalse()
	}

	@Test
	fun `maps deleted and gone subunit detail variants`() {
		val deleted = mapper.mapSubunit(validDeletedSubunitJson())
		val gone = mapper.mapSubunit(
			"""
			{
			  "organisasjonsnummer": "509100675",
			  "slettedato": "2026-01-03",
			  "_links": {"self": {"href": "https://example.test/omit"}}
			}
			""".trimIndent(),
		)

		assertThat(deleted.type).isEqualTo(BrregRecordType.SUBUNIT)
		assertThat(deleted.lifecycleStatus).isEqualTo(BrregLifecycleStatus.DELETED)
		assertThat(deleted.fields.has("navn")).isTrue()
		assertThat(deleted.fields.has("overordnetEnhet")).isFalse()
		assertThat(gone.lifecycleStatus).isEqualTo(BrregLifecycleStatus.GONE)
		assertThat(gone.fields.size()).isEqualTo(2)
		assertThat(gone.fields.has("organisasjonsnummer")).isTrue()
		assertThat(gone.fields.has("slettedato")).isTrue()
	}

	@Test
	fun `aggregates field type shape date and required field errors`() {
		val invalid = jsonMapper.readTree(validMainEntityJson()) as ObjectNode
		invalid.put("antallAnsatte", "42")
		invalid.put("underAvvikling", "false")
		invalid.put("registreringsdatoEnhetsregisteret", "2026-02-30")
		invalid.put("aktivitet", "not-an-array")
		invalid.remove("konkurs")
		(invalid.get("postadresse") as ObjectNode).put("adresse", "not-an-array")
		invalid.put("forretningsadresse", "not-an-object")

		val exception = assertThrows(BrregRecordValidationException::class.java) {
			mapper.mapMainEntity(jsonMapper.writeValueAsString(invalid))
		}

		assertThat(exception.errors.map { it.path }).containsExactlyInAnyOrder(
			"$.antallAnsatte",
			"$.underAvvikling",
			"$.registreringsdatoEnhetsregisteret",
			"$.aktivitet",
			"$.konkurs",
			"$.postadresse.adresse",
			"$.forretningsadresse",
		)
	}

	@Test
	fun `rejects mismatched response classes malformed roots and missing required deletion fields`() {
		val mismatch = assertThrows(BrregRecordValidationException::class.java) {
			mapper.mapMainEntity(
				validMainEntityJson().replace("\"Enhet\"", "\"Underenhet\""),
			)
		}
		assertThat(mismatch.errors.map { it.path }).contains("$.respons_klasse")

		assertThrows(BrregRecordValidationException::class.java) {
			mapper.mapMainEntity("[]")
		}

		val incompleteDeleted = jsonMapper.readTree(validDeletedSubunitJson()) as ObjectNode
		incompleteDeleted.remove("slettedato")
		val deletionException = assertThrows(BrregRecordValidationException::class.java) {
			mapper.mapSubunit(jsonMapper.writeValueAsString(incompleteDeleted))
		}
		assertThat(deletionException.errors.map { it.path }).contains("$.slettedato")
	}

	private fun validMainEntityJson(): String =
		"""
		{
		  "respons_klasse": "Enhet",
		  "organisasjonsnummer": "509100675",
		  "navn": "Synthetic organization",
		  "organisasjonsform": {"kode":"AS","beskrivelse":"Synthetic form"},
		  "registrertIMvaregisteret": true,
		  "maalform": "Bokmål",
		  "underAvvikling": false,
		  "registrertIStiftelsesregisteret": true,
		  "konkurs": false,
		  "paategninger": [],
		  "registrertIFrivillighetsregisteret": true,
		  "registrertIForetaksregisteret": true,
		  "registreringsdatoEnhetsregisteret": "2026-01-02",
		  "underTvangsavviklingEllerTvangsopplosning": false,
		  "harRegistrertAntallAnsatte": true,
		  "erIKonsern": false,
		  "antallAnsatte": 42,
		  "hjemmeside": null,
		  "naeringskode1": null,
		  "postadresse": {
		    "adresse": ["Synthetic street"],
		    "postnummer": null,
		    "poststed": "Synthetic town",
		    "unknown_address_field": "omit"
		  },
		  "epostadresse": "omit@example.test",
		  "_links": {"self": {"href": "https://example.test/omit"}},
		  "unknown_field": "omit"
		}
		""".trimIndent()

	private fun validDeletedSubunitJson(): String =
		"""
		{
		  "respons_klasse": "SlettetUnderEnhet",
		  "organisasjonsnummer": "509100675",
		  "navn": "Synthetic deleted subunit",
		  "organisasjonsform": {"kode":"AS","beskrivelse":"Synthetic form"},
		  "slettedato": "2026-01-02"
		}
		""".trimIndent()
}
