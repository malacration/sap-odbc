package br.andrew.odbc_sap_hana.web

import br.andrew.odbc_sap_hana.dto.ErrorResponse
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
// Boot 4 moveu esta interface: era org.springframework.boot.web.servlet.error
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Devolve JSON para os erros que NAO passam pelo @RestControllerAdvice.
 *
 * Sem isto, uma rota inexistente cai no handler padrao do container e responde a
 * pagina HTML do Tomcat ("HTTP Status 404 - Not Found"). Um cliente que espera
 * JSON quebra ao tentar parsear, e o usuario final ve um erro de parse em vez do
 * problema real - que era simplesmente URL errada.
 *
 * Cobre 404, 405, 415 e qualquer erro levantado fora do ciclo do controller.
 */
@RestController
class ApiErrorController : ErrorController {

    @RequestMapping("\${server.error.path:\${error.path:/error}}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun erro(request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val codigo = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
            ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
        val status = HttpStatus.resolve(codigo) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val caminho = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)?.toString()

        // Mensagens explicitas: a do container ("The origin server did not find...")
        // nao ajuda quem chama a API. Nao repassamos detalhe interno.
        val (erro, mensagem) = when (status) {
            HttpStatus.NOT_FOUND -> "rota_nao_encontrada" to
                "Rota nao encontrada${caminho?.let { ": $it" } ?: ""}. A API expoe POST /api/v1/query."
            HttpStatus.METHOD_NOT_ALLOWED -> "metodo_nao_permitido" to
                "Metodo HTTP nao permitido para esta rota. Use POST em /api/v1/query."
            HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "formato_nao_suportado" to
                "Envie Content-Type: application/json."
            HttpStatus.UNAUTHORIZED -> "nao_autorizado" to "Header X-API-Key ausente ou invalido."
            else -> "erro_interno" to "Erro inesperado ao processar a requisicao."
        }
        return ResponseEntity.status(status).body(ErrorResponse(erro, mensagem))
    }
}
