package no.jobvacancyanalysis.ingestion.client.brreg

import java.time.LocalDate
import java.time.format.DateTimeParseException
import no.jobvacancyanalysis.ingestion.application.brreg.BrregLifecycleStatus
import no.jobvacancyanalysis.ingestion.application.brreg.BrregOrganizationRecord
import no.jobvacancyanalysis.ingestion.application.brreg.BrregRecordType
import no.jobvacancyanalysis.ingestion.client.validation.MappingError
import no.jobvacancyanalysis.ingestion.client.validation.MappingErrorReport
import no.jobvacancyanalysis.ingestion.client.validation.jsonNodeType
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class BrregRecordMappingException(
	val report: MappingErrorReport,
) : IllegalArgumentException(report.message("BRREG"))

@Component
class BrregRecordMapper(
	private val objectMapper: ObjectMapper,
) {
	/** Maps a main-entity response; invalid responses throw instead of producing a partial record. */
	fun mapMainEntity(json: String): BrregOrganizationRecord =
		mapRecord(
			json = json,
			type = BrregRecordType.MAIN_ENTITY,
			currentResponseClass = MAIN_ENTITY_RESPONSE_CLASS,
			deletedResponseClass = DELETED_MAIN_ENTITY_RESPONSE_CLASS,
			currentShape = MAIN_ENTITY_SHAPE,
		)

	/** Maps a subunit response; invalid responses throw instead of producing a partial record. */
	fun mapSubunit(json: String): BrregOrganizationRecord =
		mapRecord(
			json = json,
			type = BrregRecordType.SUBUNIT,
			currentResponseClass = SUBUNIT_RESPONSE_CLASS,
			deletedResponseClass = DELETED_SUBUNIT_RESPONSE_CLASS,
			currentShape = SUBUNIT_SHAPE,
		)

	/**
	 * Checks response class and lifecycle status, validates allowed fields, and creates a record  if validation succeeds
	 * Field errors are collected before throwing, so no record is returned when any field is invalid.
	 */
	private fun mapRecord(
		json: String,
		type: BrregRecordType,
		currentResponseClass: String,
		deletedResponseClass: String,
		currentShape: JsonShape,
	): BrregOrganizationRecord {
		val root = try {
			objectMapper.readTree(json)
		} catch (_: JacksonException) {
			throw BrregRecordMappingException(
				MappingErrorReport(
					listOf(MappingError(ROOT_JSON_PATH, "valid JSON object", "malformed JSON")),
				),
			)
		}
		if (root?.isObject != true) {
			throw BrregRecordMappingException(
				MappingErrorReport(listOf(MappingError(ROOT_JSON_PATH, "object", jsonNodeType(root)))),
			)
		}

		val errors = mutableListOf<MappingError>()
		val responseClassNode = root.get(RESPONSE_CLASS_FIELD)
		val responseClass = responseClassNode
			?.takeIf(JsonNode::isString)
			?.asString()

		if (responseClassNode != null) {
			if (!responseClassNode.isString) {
				errors += MappingError(
					RESPONSE_CLASS_JSON_PATH,
					"string",
					jsonNodeType(responseClassNode),
				)
			} else if (responseClass != currentResponseClass &&
				responseClass != deletedResponseClass
			) {
				errors += MappingError(
					RESPONSE_CLASS_JSON_PATH,
					"\"$currentResponseClass\" or \"$deletedResponseClass\"",
					"unrecognized string",
				)
			}
		}

		// Some BRREG responses omit the class: a deletion date then identifies a gone record.
		val lifecycleStatus = when (responseClass) {
			null -> if (root.has(DELETION_DATE_FIELD)) BrregLifecycleStatus.GONE
				else BrregLifecycleStatus.CURRENT
			currentResponseClass -> BrregLifecycleStatus.CURRENT
			deletedResponseClass -> BrregLifecycleStatus.DELETED
			else -> BrregLifecycleStatus.CURRENT
		}

		// Each lifecycle variant has a different field contract, so validate against its own shape.
		val shape = when (lifecycleStatus) {
			BrregLifecycleStatus.CURRENT -> currentShape
			BrregLifecycleStatus.DELETED -> DELETED_SHAPE
			BrregLifecycleStatus.GONE -> GONE_SHAPE
		}
		// Copy only allowed fields into a temporary object; discard it if validation fails.
		val fields = validateAndCopyAllowedFields(root, "$", shape, errors) as ObjectNode
		if (lifecycleStatus != BrregLifecycleStatus.GONE &&
			responseClassNode?.isString == true
		) {
			fields.set(RESPONSE_CLASS_FIELD, responseClassNode.deepCopy())
		}

		// Build the domain record only after the full response has been checked.
		if (errors.isNotEmpty()) {
			throw BrregRecordMappingException(MappingErrorReport(errors.distinct()))
		}

		return BrregOrganizationRecord(
			type = type,
			lifecycleStatus = lifecycleStatus,
			organizationNumber = fields.get(ORGANIZATION_NUMBER_FIELD).asString(),
			fields = fields,
		)
	}

	/**
	 * Checks a node against its shape and copies only allowed fields.
	 * A bad node records an error; other fields and array items in the parent are still checked.
	 */
	private fun validateAndCopyAllowedFields(
		node: JsonNode,
		path: String,
		shape: JsonShape,
		errors: MutableList<MappingError>,
	): JsonNode {
		if (node.isNull) {
			if (!shape.nullable) {
				errors += MappingError(path, shape.expected(), "null")
			}
			return node.deepCopy()
		}

		if (!shape.matches(node)) {
			errors += MappingError(path, shape.expected(), jsonNodeType(node))
			return node.deepCopy()
		}

		return when (shape.kind) {
			JsonKind.OBJECT -> {
				val result = objectMapper.createObjectNode()
				shape.properties.forEach { (field, childShape) ->
					val childPath = "$path.$field"
					val child = node.get(field)
					if (child == null) {
						if (field in shape.required) {
							errors += MappingError(
								childPath,
								childShape.expected(),
								"missing",
							)
						}
					} else {
						result.set(
							field,
							validateAndCopyAllowedFields(child, childPath, childShape, errors),
						)
					}
				}
				result
			}
			JsonKind.ARRAY -> {
				val result = objectMapper.createArrayNode()
				val itemShape = requireNotNull(shape.items)
				node.forEachIndexed { index, item ->
					result.add(
						validateAndCopyAllowedFields(item, "$path[$index]", itemShape, errors),
					)
				}
				result
			}
			JsonKind.STRING -> {
				if (shape.date) {
					try {
						LocalDate.parse(node.asString())
					} catch (_: DateTimeParseException) {
						errors += MappingError(
							path,
							"string with format date ($DATE_FORMAT_LABEL)",
							"string with invalid date format",
						)
					}
				}
				node.deepCopy()
			}
			JsonKind.NUMBER, JsonKind.BOOLEAN -> node.deepCopy()
		}
	}

	private enum class JsonKind {
		STRING,
		NUMBER,
		BOOLEAN,
		OBJECT,
		ARRAY,
	}

	/** Describes an allowed JSON value, including nested fields and required/nullable rules. */
	private data class JsonShape(
		val kind: JsonKind,
		val nullable: Boolean = false,
		val date: Boolean = false,
		val properties: Map<String, JsonShape> = emptyMap(),
		val required: Set<String> = emptySet(),
		val items: JsonShape? = null,
	) {
		fun matches(node: JsonNode): Boolean = when (kind) {
			JsonKind.STRING -> node.isString
			JsonKind.NUMBER -> node.isNumber
			JsonKind.BOOLEAN -> node.isBoolean
			JsonKind.OBJECT -> node.isObject
			JsonKind.ARRAY -> node.isArray
		}

		fun expected(): String = when (kind) {
			JsonKind.STRING -> if (date) {
				"string with format date ($DATE_FORMAT_LABEL)"
			} else {
				"string"
			}
			JsonKind.NUMBER -> "number"
			JsonKind.BOOLEAN -> "boolean"
			JsonKind.OBJECT -> "object"
			JsonKind.ARRAY -> "array"
		}
	}

	private companion object {
		private const val ROOT_JSON_PATH = "$"
		private const val RESPONSE_CLASS_FIELD = "respons_klasse"
		private const val ORGANIZATION_NUMBER_FIELD = "organisasjonsnummer"
		private const val DELETION_DATE_FIELD = "slettedato"
		private const val RESPONSE_CLASS_JSON_PATH = "$ROOT_JSON_PATH.$RESPONSE_CLASS_FIELD"
		private const val DATE_FORMAT_LABEL = "YYYY-MM-DD"
		private const val MAIN_ENTITY_RESPONSE_CLASS = "Enhet"
		private const val DELETED_MAIN_ENTITY_RESPONSE_CLASS = "SlettetEnhet"
		private const val SUBUNIT_RESPONSE_CLASS = "Underenhet"
		private const val DELETED_SUBUNIT_RESPONSE_CLASS = "SlettetUnderEnhet"

		private fun string(nullable: Boolean = false, date: Boolean = false) =
			JsonShape(JsonKind.STRING, nullable = nullable, date = date)

		private fun number(nullable: Boolean = false) =
			JsonShape(JsonKind.NUMBER, nullable = nullable)

		private fun boolean(nullable: Boolean = false) =
			JsonShape(JsonKind.BOOLEAN, nullable = nullable)

		private fun arrayOf(item: JsonShape, nullable: Boolean = false) =
			JsonShape(JsonKind.ARRAY, nullable = nullable, items = item)

		private fun objectOf(
			properties: Map<String, JsonShape>,
			required: Set<String> = emptySet(),
			nullable: Boolean = false,
		) = JsonShape(
			kind = JsonKind.OBJECT,
			nullable = nullable,
			properties = properties,
			required = required,
		)

		/** Builds a record shape from field-type groups plus any nested field shapes. */
		private fun recordShape(
			required: Set<String>,
			strings: Set<String> = emptySet(),
			nullableStrings: Set<String> = emptySet(),
			dates: Set<String> = emptySet(),
			nullableDates: Set<String> = emptySet(),
			numbers: Set<String> = emptySet(),
			booleans: Set<String> = emptySet(),
			stringArrays: Set<String> = emptySet(),
			nullableStringArrays: Set<String> = emptySet(),
			nested: Map<String, JsonShape> = emptyMap(),
		): JsonShape {
			val properties = linkedMapOf<String, JsonShape>()
			fun add(fields: Set<String>, shape: JsonShape) {
				fields.forEach { field -> properties[field] = shape }
			}

			add(strings, string())
			add(nullableStrings, string(nullable = true))
			add(dates, string(date = true))
			add(nullableDates, string(nullable = true, date = true))
			add(numbers, number())
			add(booleans, boolean())
			add(stringArrays, arrayOf(string()))
			add(nullableStringArrays, arrayOf(string(), nullable = true))
			properties.putAll(nested)
			return objectOf(
				properties = properties,
				required = required,
			)
		}

		private val ADDRESS_SHAPE = objectOf(
			properties = mapOf(
				"kommune" to string(nullable = true),
				"landkode" to string(nullable = true),
				"postnummer" to string(nullable = true),
				"adresse" to arrayOf(string(), nullable = true),
				"land" to string(nullable = true),
				"kommunenummer" to string(nullable = true),
				"poststed" to string(nullable = true),
			),
		)

		private val CODE_SHAPE = objectOf(
			properties = mapOf(
				"kode" to string(nullable = true),
				"beskrivelse" to string(nullable = true),
			),
		)
		private val NULLABLE_CODE_SHAPE = objectOf(
			properties = CODE_SHAPE.properties,
			nullable = true,
		)

		private val ORGANISASJONSFORM_SHAPE = objectOf(
			properties = mapOf(
				"kode" to string(),
				"beskrivelse" to string(),
				"utgaatt" to string(nullable = true),
			),
			required = setOf("kode", "beskrivelse"),
		)

		private val HISTORICAL_NAME_SHAPE = objectOf(
			properties = mapOf(
				"navn" to string(),
				"fraDato" to string(),
				"tilDato" to string(),
			),
			required = setOf("navn", "fraDato", "tilDato"),
		)
		private val HISTORICAL_NAMES_SHAPE =
			arrayOf(HISTORICAL_NAME_SHAPE, nullable = true)

		private val PAATEGNING_SHAPE = objectOf(
			properties = mapOf(
				"infotype" to string(),
				"tekst" to string(),
				"innfoertDato" to string(date = true),
			),
		)

		private val CAPITAL_SHAPE = objectOf(
			properties = mapOf(
				"belop" to number(),
				"antallAksjer" to number(nullable = true),
				"type" to string(),
				"bundet" to number(nullable = true),
				"valuta" to string(),
				"innbetalt" to number(nullable = true),
				"fulltInnbetalt" to boolean(nullable = true),
				"innfortDato" to string(date = true),
			),
			required = setOf("belop", "type", "valuta", "innfortDato"),
		)

		private val FORETAKSFORM_SHAPE = objectOf(
			properties = mapOf(
				"kode" to string(),
				"beskrivelse" to string(),
				"beskrivelseBokmaal" to string(),
			),
			required = setOf("kode", "beskrivelse", "beskrivelseBokmaal"),
		)

		private val FOREIGN_ADDRESS_SHAPE = objectOf(
			properties = mapOf(
				"adresse" to arrayOf(string(), nullable = true),
				"land" to string(nullable = true),
				"poststed" to string(nullable = true),
			),
		)

		private val COMMON_NESTED_SHAPES = mapOf(
			"organisasjonsform" to ORGANISASJONSFORM_SHAPE,
			"historiskeNavn" to HISTORICAL_NAMES_SHAPE,
			"naeringskode1" to NULLABLE_CODE_SHAPE,
			"naeringskode2" to NULLABLE_CODE_SHAPE,
			"naeringskode3" to NULLABLE_CODE_SHAPE,
			"hjelpeenhetskode" to CODE_SHAPE,
		)

		private val MAIN_ENTITY_SHAPE = recordShape(
			required = setOf(
				"organisasjonsnummer", "navn", "organisasjonsform",
				"registrertIMvaregisteret", "maalform", "underAvvikling",
				"registrertIStiftelsesregisteret", "konkurs", "paategninger",
				"registrertIFrivillighetsregisteret", "registrertIForetaksregisteret",
				"registreringsdatoEnhetsregisteret",
				"underTvangsavviklingEllerTvangsopplosning",
				"harRegistrertAntallAnsatte", "erIKonsern",
			),
			strings = setOf(
				"organisasjonsnummer", "navn", "maalform", "utenlandskRegisterNavn",
				"underlagtLovgivningLand", "underlagtLovgivningLandKode",
			),
			nullableStrings = setOf(
				"hjemmeside", "sisteInnsendteAarsregnskap", "overordnetEnhet",
				"registreringsnummerIHjemlandet",
			),
			dates = setOf(
				"underAvviklingDato", "konkursdato",
				"tvangsavvikletPgaManglendeSlettingDato",
				"tvangsopplostPgaManglendeDagligLederDato",
				"tvangsopplostPgaManglendeRevisorDato",
				"tvangsopplostPgaManglendeRegnskapDato",
				"tvangsopplostPgaMangelfulltStyreDato", "vedtektsdato",
				"registreringsdatoEnhetsregisteret",
				"registreringsdatoAntallAnsatteNAVAaregisteret",
				"registreringsdatoAntallAnsatteEnhetsregisteret",
				"registreringsdatoMerverdiavgiftsregisteret",
				"registreringsdatoMerverdiavgiftsregisteretEnhetsregisteret",
				"registreringsdatoFrivilligMerverdiavgiftsregisteret",
				"registreringsdatoForetaksregisteret",
				"registreringsdatoFrivillighetsregisteret",
				"registreringsdatoPartiregisteret", "fravalgRevisjonDato",
				"fravalgRevisjonBeslutningsDato",
				"underRekonstruksjonsforhandlingDato",
				"underUtenlandskInsolvensbehandlingDato",
			),
			nullableDates = setOf("stiftelsesdato"),
			numbers = setOf("antallAnsatte"),
			booleans = setOf(
				"registrertIMvaregisteret", "underAvvikling",
				"registrertIStiftelsesregisteret", "konkurs",
				"registrertIFrivillighetsregisteret",
				"registrertIForetaksregisteret",
				"underTvangsavviklingEllerTvangsopplosning",
				"harRegistrertAntallAnsatte", "registrertIPartiregisteret",
				"erIKonsern",
			),
			stringArrays = setOf("vedtektsfestetFormaal", "aktivitet"),
			nullableStringArrays = setOf("frivilligMvaRegistrertBeskrivelser"),
			nested = COMMON_NESTED_SHAPES + mapOf(
				"postadresse" to ADDRESS_SHAPE,
				"forretningsadresse" to ADDRESS_SHAPE,
				"paategninger" to arrayOf(PAATEGNING_SHAPE),
				"institusjonellSektorkode" to CODE_SHAPE,
				"kapital" to CAPITAL_SHAPE,
				"foretaksformIHjemlandet" to FORETAKSFORM_SHAPE,
				"utenlandskRegisterAdresse" to FOREIGN_ADDRESS_SHAPE,
			),
		)

		private val SUBUNIT_SHAPE = recordShape(
			required = setOf(
				"organisasjonsnummer", "navn", "organisasjonsform",
				"registrertIMvaregisteret", "registreringsdatoEnhetsregisteret",
				"harRegistrertAntallAnsatte",
			),
			strings = setOf("organisasjonsnummer", "navn"),
			nullableStrings = setOf("hjemmeside", "overordnetEnhet"),
			dates = setOf(
				"registreringsdatoEnhetsregisteret",
				"registreringsdatoAntallAnsatteNAVAaregisteret",
				"registreringsdatoAntallAnsatteEnhetsregisteret",
				"registreringsdatoMerverdiavgiftsregisteret",
				"registreringsdatoMerverdiavgiftsregisteretEnhetsregisteret",
				"registreringsdatoFrivilligMerverdiavgiftsregisteret",
			),
			nullableDates = setOf("oppstartsdato", "datoEierskifte", "nedleggelsesdato"),
			numbers = setOf("antallAnsatte"),
			booleans = setOf("registrertIMvaregisteret", "harRegistrertAntallAnsatte"),
			nullableStringArrays = setOf("frivilligMvaRegistrertBeskrivelser"),
			nested = COMMON_NESTED_SHAPES + mapOf(
				"postadresse" to ADDRESS_SHAPE,
				"beliggenhetsadresse" to ADDRESS_SHAPE,
			),
		)

		private val DELETED_SHAPE = recordShape(
			required = setOf("organisasjonsnummer", "navn", "organisasjonsform", "slettedato"),
			strings = setOf("organisasjonsnummer", "navn", "slettedato"),
			nested = mapOf(
				"organisasjonsform" to ORGANISASJONSFORM_SHAPE,
				"historiskeNavn" to HISTORICAL_NAMES_SHAPE,
			),
		)

		private val GONE_SHAPE = recordShape(
			required = setOf("organisasjonsnummer", "slettedato"),
			strings = setOf("organisasjonsnummer", "slettedato"),
		)
	}
}
