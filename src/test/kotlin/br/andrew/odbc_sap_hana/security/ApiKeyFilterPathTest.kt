package br.andrew.odbc_sap_hana.security

import br.andrew.odbc_sap_hana.config.SecurityProperties
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiKeyFilterPathTest {
    @Test
    fun `context path e codificacao nao dispensam chave`() {
        listOf("/gateway/api/v1/query", "/api;x=1/v1/query", "/%61pi/v1/query").forEach { uri ->
            val request = MockHttpServletRequest("POST", uri).apply {
                if (uri.startsWith("/gateway/")) contextPath = "/gateway"
            }
            val response = MockHttpServletResponse()
            ApiKeyFilter(SecurityProperties(apiKey = "segredo-teste"))
                .doFilter(request, response, MockFilterChain())
            assertEquals(401, response.status, uri)
        }
    }
}
