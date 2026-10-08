package no.jobvacancydata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

// TODO: Add a PostgreSQL/Testcontainers integration test for the normal
// DataSource/Flyway setup.
@SpringBootTest(
	properties = [
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		"spring.flyway.enabled=false",
		"app.ingestion.nav.enabled=false",
	],
)
class JobVacancyDataApplicationTests(
	@Autowired private val context: ApplicationContext,
) {

	@Test
	fun contextLoads() {
		assertThat(context.getBean<JobVacancyDataApplication>()).isNotNull()
	}

}
