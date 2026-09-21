package br.andrew.odbc_sap_hana.dto

/**
 * Requisicao de consulta.
 *
 * Valores variaveis devem ir em [params] e ser referenciados na instrucao como
 * `:nome` - nunca concatenados no texto do SQL. Os parametros viajam ate o banco
 * como bind variables de PreparedStatement, o que elimina SQL injection por dado.
 */
data class QueryRequest(
    val sql: String? = null,
    val params: Map<String, Any?> = emptyMap(),
    val maxRows: Int? = null,
    val timeoutSeconds: Int? = null,
)

data class ColumnMeta(
    val name: String,
    val type: String,
    val nullable: Boolean,
)

data class QueryResponse(
    val columns: List<ColumnMeta>,
    val rows: List<Map<String, Any?>>,
    val rowCount: Int,
    /** true quando existiam mais linhas do que o limite pedido. */
    val truncated: Boolean,
    val elapsedMs: Long,
)

/**
 * Envelope de erro padrao das APIs do workspace.
 *
 * `erro` e um codigo ESTAVEL, para o chamador decidir o tratamento sem depender
 * do texto. `mensagem` e pronta para exibir ao usuario final - nunca carrega SQL,
 * stacktrace ou nome de tabela.
 *
 * O mesmo formato e usado pelo sap-reports e pelo painel de vendas do sap-rovema.
 * Ele ja foi `error`/`message` aqui, o que gerou um bug real: o cliente lia o
 * campo errado e todo erro virava generico, perdendo `timeout` e `sql_invalido`.
 */
data class ErrorResponse(
    val erro: String,
    val mensagem: String,
    val sqlState: String? = null,
)
