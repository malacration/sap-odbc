package br.andrew.odbc_sap_hana.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "api.security")
data class SecurityProperties(
    /** Se preenchida, toda requisicao precisa enviar o header `X-API-Key` com este valor. */
    val apiKey: String? = null,
)
