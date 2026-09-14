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

data class ErrorResponse(
    val error: String,
    val message: String,
    val sqlState: String? = null,
)
