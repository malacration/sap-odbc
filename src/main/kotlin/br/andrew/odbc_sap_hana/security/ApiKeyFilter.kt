package br.andrew.odbc_sap_hana.security

import br.andrew.odbc_sap_hana.config.SecurityProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * Autenticacao minima por chave de API. Fica inativa quando `api.security.api-key`
 * nao esta configurada (uso local); em producao, configure a chave ou coloque a
 * aplicacao atras de um gateway com autenticacao propria.
 */
@Component
class ApiKeyFilter(private val props: SecurityProperties) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        props.apiKey.isNullOrBlank() || !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val provided = request.getHeader("X-API-Key")
        if (provided == null || !constantTimeEquals(provided, props.apiKey!!)) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"error":"nao_autorizado","message":"Header X-API-Key ausente ou invalido."}""")
            return
        }
        chain.doFilter(request, response)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.UTF_8),
        b.toByteArray(StandardCharsets.UTF_8),
    )
}
