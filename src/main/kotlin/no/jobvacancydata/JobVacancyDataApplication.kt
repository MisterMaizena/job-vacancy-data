package no.jobvacancydata

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class JobVacancyDataApplication

fun main(args: Array<String>) {
	runApplication<JobVacancyDataApplication>(*args)
}
