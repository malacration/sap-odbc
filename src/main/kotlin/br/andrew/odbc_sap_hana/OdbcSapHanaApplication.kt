package br.andrew.odbc_sap_hana

import br.andrew.odbc_sap_hana.config.QueryProperties
import br.andrew.odbc_sap_hana.config.SecurityProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(QueryProperties::class, SecurityProperties::class)
class OdbcSapHanaApplication

fun main(args: Array<String>) {
	runApplication<OdbcSapHanaApplication>(*args)
}
