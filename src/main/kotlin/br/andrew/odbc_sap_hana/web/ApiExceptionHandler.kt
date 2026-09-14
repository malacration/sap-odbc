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

    @ExceptionHandler(BadSqlGrammarException::class)
    fun onGrammar(ex: BadSqlGrammarException) = ResponseEntity
        .badRequest()
        .body(ErrorResponse("sql_rejeitado_pelo_banco", rootMessage(ex), sqlState(ex)))

    @ExceptionHandler(QueryTimeoutException::class)
    fun onTimeout(ex: QueryTimeoutException) = ResponseEntity
        .status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ErrorResponse("timeout", "A consulta excedeu o tempo limite.", sqlState(ex)))

    @ExceptionHandler(UncategorizedSQLException::class, SQLException::class)
    fun onSql(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Falha ao executar consulta", ex)
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("erro_banco", rootMessage(ex), sqlState(ex)))
    }

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
