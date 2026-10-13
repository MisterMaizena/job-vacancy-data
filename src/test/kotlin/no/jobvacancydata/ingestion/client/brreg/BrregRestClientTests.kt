package no.jobvacancydata.ingestion.client.brreg

import java.time.Duration
import no.jobvacancydata.ingestion.application.brreg.BrregLifecycleStatus
import no.jobvacancydata.ingestion.application.brreg.BrregLookupResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class BrregRestClientTests {
	private val objectMapper = JsonMapper.builder().build()
	private val recordMapper = BrregRecordMapper(objectMapper)

	@Test
	fun `partitions more than two thousand IDs into batches of two thousand and one`() {
		val ids = List(BrregApiContract.MAX_ORGANIZATION_NUMBERS_PER_REQUEST + 1) { index ->
			// Prefixed to make the index position the shape of an org.nr
			(100_000_000 + index).toString()
		}

		val batches = partitionBrregOrganizationNumbers(ids)

		assertThat(batches).hasSize(2)
		assertThat(batches[0]).hasSize(BrregApiContract.MAX_ORGANIZATION_NUMBERS_PER_REQUEST)
		assertThat(batches[1]).containsExactly(ids.last())
		assertThat(batches.flatten()).containsExactlyElementsOf(ids)
	}

	@Test
	fun `returns empty list for empty input and rejects malformed organization numbers`() {
		val client = createClient(RestClient.builder().build())

		assertThat(client.lookup(emptyList())).isEmpty()
		assertThrows<IllegalArgumentException> {
			client.lookup(listOf("not-nine-digits"))
		}
	}

	@Test
	fun `looks up one organization across both registers and resolves missing register record by detail`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", listOf(mainEntity(ORG_NUMBER))), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat(result.mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	@Test
	fun `follows search next link and maps deleted detail record`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val nextUrl = "$BASE/api/enheter?page=1&size=20&organisasjonsnummer=$ORG_NUMBER"

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", emptyList(), nextUrl), MediaType.APPLICATION_JSON))
		server.expect(requestTo(nextUrl))
			.andRespond(withSuccess(searchResponse("enheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", listOf(subunit(ORG_NUMBER))), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
			.andRespond(withSuccess(deletedMainEntity(), MediaType.APPLICATION_JSON))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat((result.mainEntity as BrregLookupResult.Found).record.lifecycleStatus)
			.isEqualTo(BrregLifecycleStatus.DELETED)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.Found::class.java)
		server.verify()
	}

	@Test
	fun `maps removed detail response as gone and represents missing records`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.GONE).body(goneRecord()).contentType(MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat((result.mainEntity as BrregLookupResult.Found).record.lifecycleStatus)
			.isEqualTo(BrregLifecycleStatus.GONE)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	@Test
	fun `keeps each organization outcome independent within a batch`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val ids = listOf(ORG_NUMBER, OTHER_ORG_NUMBER)
		val idParam = ids.joinToString(",")

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$idParam"))
			.andRespond(withSuccess(searchResponse("enheter", listOf(mainEntity(ORG_NUMBER))), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$idParam"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))
		server.expect(requestTo("$BASE/api/enheter/$OTHER_ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))
		server.expect(requestTo("$BASE/api/underenheter/$OTHER_ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val results = client.lookup(ids)

		assertThat(results.map { it.organizationNumber }).containsExactly(ORG_NUMBER, OTHER_ORG_NUMBER)
		assertThat(results[0].mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat(results[0].subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		assertThat(results[1].mainEntity).isInstanceOf(BrregLookupResult.NotFound::class.java)
		assertThat(results[1].subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	@Test
	fun `failed search leaves unresolved IDs pending and preserves valid peers`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val ids = listOf(ORG_NUMBER, OTHER_ORG_NUMBER)
		val idParam = ids.joinToString(",")
		val nextUrl = "$BASE/api/enheter?page=1&organisasjonsnummer=$idParam"

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$idParam"))
			.andRespond(
				withSuccess(
					searchResponse("enheter", listOf(mainEntity(ORG_NUMBER)), nextUrl),
					MediaType.APPLICATION_JSON,
				),
			)
		repeat(RETRY_TEST_ATTEMPTS) {
			server.expect(requestTo(nextUrl))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
		}
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$idParam"))
			.andRespond(
				withSuccess(
					searchResponse("underenheter", listOf(subunit(ORG_NUMBER), subunit(OTHER_ORG_NUMBER))),
					MediaType.APPLICATION_JSON,
				),
			)

		val results = client.lookup(ids)

		assertThat(results[0].mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		val pending = results[1].mainEntity as BrregLookupResult.Failed
		assertThat(pending.reason).contains("503")
		assertThat(results[0].subunit).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat(results[1].subunit).isInstanceOf(BrregLookupResult.Found::class.java)
		server.verify()
	}

	@Test
	fun `invalid search record is rejected alone while valid peers are kept`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val ids = listOf(ORG_NUMBER, OTHER_ORG_NUMBER)
		val idParam = ids.joinToString(",")
		val invalidOther = (objectMapper.readTree(mainEntity(OTHER_ORG_NUMBER)) as ObjectNode)
			.apply { remove("navn") }
			.let(objectMapper::writeValueAsString)

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$idParam"))
			.andRespond(
				withSuccess(
					searchResponse("enheter", listOf(mainEntity(ORG_NUMBER), invalidOther)),
					MediaType.APPLICATION_JSON,
				),
			)
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$idParam"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))
		server.expect(requestTo("$BASE/api/underenheter/$OTHER_ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val results = client.lookup(ids)

		assertThat(results[0].mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat(results[1].mainEntity).isInstanceOf(BrregLookupResult.Invalid::class.java)
		assertThat(results[0].subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		assertThat(results[1].subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	@Test
	fun `duplicate search record is an ambiguous data-integrity outcome`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(
				withSuccess(
					searchResponse("enheter", listOf(mainEntity(ORG_NUMBER), mainEntity(ORG_NUMBER))),
					MediaType.APPLICATION_JSON,
				),
			)
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", listOf(subunit(ORG_NUMBER))), MediaType.APPLICATION_JSON))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat(result.mainEntity).isInstanceOf(BrregLookupResult.Invalid::class.java)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.Found::class.java)
		server.verify()
	}

	@Test
	fun `unrequested search record fails the search without turning missing IDs into no-match`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val ids = listOf(ORG_NUMBER, OTHER_ORG_NUMBER)
		val idParam = ids.joinToString(",")
		val unrequested = "599100675"

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$idParam"))
			.andRespond(
				withSuccess(
					searchResponse("enheter", listOf(mainEntity(ORG_NUMBER), mainEntity(unrequested))),
					MediaType.APPLICATION_JSON,
				),
			)
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$idParam"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))
		server.expect(requestTo("$BASE/api/underenheter/$OTHER_ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val results = client.lookup(ids)

		assertThat(results[0].mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat(results[1].mainEntity).isInstanceOf(BrregLookupResult.Failed::class.java)
		server.verify()
	}

	@Test
	fun `failed detail lookup becomes pending and is never a no-match`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", listOf(subunit(ORG_NUMBER))), MediaType.APPLICATION_JSON))
		repeat(RETRY_TEST_ATTEMPTS) {
			server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
		}

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		val main = result.mainEntity as BrregLookupResult.Failed
		assertThat(main.reason).contains("503")
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.Found::class.java)
		server.verify()
	}

	@Test
	fun `retries rate limited detail honoring retry-after then maps the record`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"))
		server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
			.andRespond(withSuccess(deletedMainEntity(), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat((result.mainEntity as BrregLookupResult.Found).record.lifecycleStatus)
			.isEqualTo(BrregLifecycleStatus.DELETED)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	@Test
	fun `invalid detail record is rejected for review`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", emptyList()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/enheter/$ORG_NUMBER"))
			.andRespond(withSuccess("""{"organisasjonsnummer":"$ORG_NUMBER"}""", MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat(result.mainEntity).isInstanceOf(BrregLookupResult.Invalid::class.java)
		assertThat(result.subunit).isInstanceOf(BrregLookupResult.NotFound::class.java)
		server.verify()
	}

	private fun createClient(restClient: RestClient) =
		BrregRestClient(
			restClient,
			recordMapper,
			objectMapper,
			BASE,
			retryMaxAttempts = RETRY_TEST_ATTEMPTS,
			retryWaitDuration = Duration.ZERO,
		)

	private fun searchResponse(key: String, items: List<String>, next: String? = null): String {
		val entries = items.joinToString(",", "[", "]")
		val nextLink = next?.let { "\"href\":\"$it\"" } ?: "\"href\":null"
		return """{"_embedded":{"$key":$entries},"_links":{"next":{$nextLink}},"page":{"number":0,"size":20,"totalElements":0,"totalPages":1}}"""
	}

	private fun mainEntity(orgNumber: String): String =
		"""{"respons_klasse":"Enhet","organisasjonsnummer":"$orgNumber","navn":"Synthetic organization","organisasjonsform":{"kode":"AS","beskrivelse":"Synthetic"},"registrertIMvaregisteret":true,"maalform":"Bokmål","underAvvikling":false,"registrertIStiftelsesregisteret":false,"konkurs":false,"paategninger":[],"registrertIFrivillighetsregisteret":false,"registrertIForetaksregisteret":true,"registreringsdatoEnhetsregisteret":"2020-01-01","underTvangsavviklingEllerTvangsopplosning":false,"harRegistrertAntallAnsatte":true,"erIKonsern":false}"""

	private fun subunit(orgNumber: String): String =
		"""{"respons_klasse":"Underenhet","organisasjonsnummer":"$orgNumber","navn":"Synthetic subunit","organisasjonsform":{"kode":"BEDR","beskrivelse":"Synthetic"},"registrertIMvaregisteret":false,"registreringsdatoEnhetsregisteret":"2020-01-01","harRegistrertAntallAnsatte":false}"""

	private fun deletedMainEntity(): String =
		"""{"respons_klasse":"SlettetEnhet","organisasjonsnummer":"$ORG_NUMBER","navn":"Synthetic deleted","organisasjonsform":{"kode":"AS","beskrivelse":"Synthetic"},"slettedato":"2026-01-01"}"""

	private fun goneRecord(): String =
		"""{"organisasjonsnummer":"$ORG_NUMBER","slettedato":"2026-01-01"}"""

	private companion object {
		const val RETRY_TEST_ATTEMPTS = 3
		const val BASE = "https://data.brreg.no/enhetsregisteret"
		const val ORG_NUMBER = "509100675"
		const val OTHER_ORG_NUMBER = "509100676"
	}
}
