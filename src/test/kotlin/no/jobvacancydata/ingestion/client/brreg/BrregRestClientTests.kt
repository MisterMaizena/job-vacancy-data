package no.jobvacancydata.ingestion.client.brreg

import java.time.Duration
import no.jobvacancydata.ingestion.application.brreg.BrregLifecycleStatus
import no.jobvacancydata.ingestion.application.brreg.BrregLookupResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

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
	fun `looks up one organization across both registers and resolves missing register record by detail`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", mainEntity()), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", null), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter/$ORG_NUMBER"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND))

		val result = client.lookup(listOf(ORG_NUMBER)).single()

		assertThat(result.mainEntity).isInstanceOf(BrregLookupResult.Found::class.java)
		assertThat((result.subunit as BrregLookupResult.NotFound)).isNotNull
		server.verify()
	}

	@Test
	fun `follows search next link and maps deleted detail record`() {
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		val client = createClient(builder.build())
		val nextUrl = "$BASE/api/enheter?page=1&size=20&organisasjonsnummer=$ORG_NUMBER"

		server.expect(requestTo("$BASE/api/enheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("enheter", null, nextUrl), MediaType.APPLICATION_JSON))
		server.expect(requestTo(nextUrl))
			.andRespond(withSuccess(searchResponse("enheter", null), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", subunit()), MediaType.APPLICATION_JSON))
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
			.andRespond(withSuccess(searchResponse("enheter", null), MediaType.APPLICATION_JSON))
		server.expect(requestTo("$BASE/api/underenheter?organisasjonsnummer=$ORG_NUMBER"))
			.andRespond(withSuccess(searchResponse("underenheter", null), MediaType.APPLICATION_JSON))
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

	private fun createClient(restClient: RestClient) =
		BrregRestClient(
			restClient,
			recordMapper,
			objectMapper,
			BASE,
			retryMaxAttempts = 3,
			retryWaitDuration = Duration.ZERO,
		)

	private fun searchResponse(key: String, item: String?, next: String? = null): String {
		val entries = item?.let { "[$it]" } ?: "[]"
		val nextLink = next?.let { "\"href\":\"$it\"" } ?: "\"href\":null"
		return """{"_embedded":{"$key":$entries},"_links":{"next":{$nextLink}},"page":{"number":0,"size":20,"totalElements":0,"totalPages":1}}"""
	}

	private fun mainEntity(): String =
		"""{"respons_klasse":"Enhet","organisasjonsnummer":"$ORG_NUMBER","navn":"Synthetic organization","organisasjonsform":{"kode":"AS","beskrivelse":"Synthetic"},"registrertIMvaregisteret":true,"maalform":"Bokmål","underAvvikling":false,"registrertIStiftelsesregisteret":false,"konkurs":false,"paategninger":[],"registrertIFrivillighetsregisteret":false,"registrertIForetaksregisteret":true,"registreringsdatoEnhetsregisteret":"2020-01-01","underTvangsavviklingEllerTvangsopplosning":false,"harRegistrertAntallAnsatte":true,"erIKonsern":false}"""

	private fun subunit(): String =
		"""{"respons_klasse":"Underenhet","organisasjonsnummer":"$ORG_NUMBER","navn":"Synthetic subunit","organisasjonsform":{"kode":"BEDR","beskrivelse":"Synthetic"},"registrertIMvaregisteret":false,"registreringsdatoEnhetsregisteret":"2020-01-01","harRegistrertAntallAnsatte":false}"""

	private fun deletedMainEntity(): String =
		"""{"respons_klasse":"SlettetEnhet","organisasjonsnummer":"$ORG_NUMBER","navn":"Synthetic deleted","organisasjonsform":{"kode":"AS","beskrivelse":"Synthetic"},"slettedato":"2026-01-01"}"""

	private fun goneRecord(): String =
		"""{"organisasjonsnummer":"$ORG_NUMBER","slettedato":"2026-01-01"}"""

	private companion object {
		const val BASE = "https://data.brreg.no/enhetsregisteret"
		const val ORG_NUMBER = "509100675"
	}
}
