package br.andrew.odbc_sap_hana.web

import br.andrew.odbc_sap_hana.dto.ErrorResponse
import org.springframework.dao.DataAccessException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.UncategorizedSQLException
import java.sql.SQLException

/**
 * Traducao de falha do banco para o envelope padrao, em UM lugar.
 *
 * Usada pelo [ApiExceptionHandler] (erro antes da resposta comecar) e pelo fluxo
 * `/query/stream` (erro no meio da leitura, quando o status 200 ja foi enviado e o erro
 * vai numa linha do proprio fluxo). Com a traducao duplicada, os dois caminhos acabariam
 * divergindo nos codigos - e o cliente decide o tratamento pelo codigo.
 *
 * Mensagem SEMPRE fixa, nunca a do driver: o HANA inclui a instrucao SQL no texto da
 * excecao, entao repassa-la devolveria a consulta (e nomes de tabela) ao chamador.
 */
object ErrosBanco {

    fun traduzir(ex: Throwable): Pair<HttpStatus, ErrorResponse> = when (ex) {
        is BadSqlGrammarException -> HttpStatus.BAD_REQUEST to ErrorResponse(
            "sql_rejeitado_pelo_banco",
            "A consulta foi recusada pelo banco. Verifique nomes de tabela, colunas e sintaxe.",
            sqlState(ex),
        )
        is QueryTimeoutException -> HttpStatus.GATEWAY_TIMEOUT to
            ErrorResponse("timeout", "A consulta excedeu o tempo limite.", sqlState(ex))
        is UncategorizedSQLException, is SQLException -> HttpStatus.BAD_GATEWAY to
            ErrorResponse("erro_banco", "Falha ao executar a consulta no banco.", sqlState(ex))
        is DataAccessException -> HttpStatus.BAD_GATEWAY to
            ErrorResponse("erro_banco", "Nao foi possivel acessar o banco de dados.", sqlState(ex))
        else -> HttpStatus.INTERNAL_SERVER_ERROR to
            ErrorResponse("erro_interno", "Erro inesperado ao processar a requisicao.")
    }

    fun sqlState(ex: Throwable): String? = generateSequence(ex) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
        ?.sqlState
}
