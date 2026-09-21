package br.andrew.odbc_sap_hana.web

import br.andrew.odbc_sap_hana.dto.ErrorResponse
import br.andrew.odbc_sap_hana.sql.SqlValidationException
import org.slf4j.LoggerFactory
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(SqlValidationException::class)
    fun onValidation(ex: SqlValidationException) =
        ResponseEntity.badRequest().body(ErrorResponse("sql_invalido", ex.message ?: "Instrucao invalida."))

    /**
     * Mensagem FIXA, nunca a do driver: o HANA inclui a instrucao SQL no texto da
     * excecao, entao repassa-la devolveria a consulta (e nomes de tabela) ao
     * chamador. O detalhe fica so no log.
     */
    @ExceptionHandler(BadSqlGrammarException::class)
    fun onGrammar(ex: BadSqlGrammarException): ResponseEntity<ErrorResponse> {
        log.warn("Consulta recusada pelo banco: {}", rootMessage(ex))
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                "sql_rejeitado_pelo_banco",
                "A consulta foi recusada pelo banco. Verifique nomes de tabela, colunas e sintaxe.",
                sqlState(ex),
            ),
        )
    }

    @ExceptionHandler(QueryTimeoutException::class)
    fun onTimeout(ex: QueryTimeoutException) = ResponseEntity
        .status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ErrorResponse("timeout", "A consulta excedeu o tempo limite.", sqlState(ex)))

    @ExceptionHandler(UncategorizedSQLException::class, SQLException::class)
    fun onSql(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Falha ao executar consulta", ex)
        // Mesmo motivo do handler acima: a mensagem do driver carrega o SQL.
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("erro_banco", "Falha ao executar a consulta no banco.", sqlState(ex)))
    }

    /**
     * ACHADO 2: corpo JSON malformado ou com campo de tipo errado caia no handler
     * generico e virava 500 - erro do cliente reportado como falha do servidor,
     * o que manda o suporte investigar o lugar errado.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun onCorpoInvalido(ex: org.springframework.http.converter.HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("Corpo da requisicao invalido: {}", ex.message?.lineSequence()?.firstOrNull())
        return ResponseEntity.badRequest().body(
            ErrorResponse("corpo_invalido", "Corpo da requisicao invalido. Envie JSON com 'sql' e 'params'."),
        )
    }

    /**
     * ACHADO 3: falha de CONEXAO (`CannotGetJdbcConnectionException`) nao e
     * SQLException e escapava para o generico, virando 500 em vez do 502
     * documentado. Vem depois dos handlers especificos, que sao mais precisos.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun onAcessoDados(ex: org.springframework.dao.DataAccessException): ResponseEntity<ErrorResponse> {
        log.error("Falha de acesso ao banco", ex)
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("erro_banco", "Nao foi possivel acessar o banco de dados.", sqlState(ex)))
    }

    /**
     * Rota inexistente. Precisa vir ANTES do handler generico: sem isto a
     * excecao cai no `Exception` la embaixo e um 404 legitimo vira 500 - o
     * cliente recebe "erro interno" quando na verdade errou a URL.
     */
    @ExceptionHandler(
        org.springframework.web.servlet.resource.NoResourceFoundException::class,
        org.springframework.web.servlet.NoHandlerFoundException::class,
    )
    fun onRotaInexistente(ex: Exception) = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse("rota_nao_encontrada", "Rota nao encontrada. A API expoe POST /api/v1/query."))

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun onMetodo(ex: org.springframework.web.HttpRequestMethodNotSupportedException) = ResponseEntity
        .status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(ErrorResponse("metodo_nao_permitido", "Metodo ${ex.method} nao permitido. Use POST em /api/v1/query."))

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException::class)
    fun onMediaType(ex: org.springframework.web.HttpMediaTypeNotSupportedException) = ResponseEntity
        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(ErrorResponse("formato_nao_suportado", "Envie Content-Type: application/json."))

    @ExceptionHandler(Exception::class)
    fun onUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Erro inesperado", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("erro_interno", "Erro inesperado ao processar a requisicao."))
    }

    private fun sqlState(ex: Throwable): String? = generateSequence(ex) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
        ?.sqlState

    private fun rootMessage(ex: Throwable): String = generateSequence(ex) { it.cause }
        .last()
        .message
        ?.lineSequence()
        ?.firstOrNull()
        ?: "Erro ao executar a consulta."
}
