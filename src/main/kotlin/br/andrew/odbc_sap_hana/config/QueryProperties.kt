package br.andrew.odbc_sap_hana.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Limites e regras de seguranca aplicados a toda consulta recebida pela API.
 */
@ConfigurationProperties(prefix = "query")
data class QueryProperties(
    /** Tamanho maximo, em caracteres, da instrucao SQL aceita. */
    val maxSqlLength: Int = 20_000,
    /** Numero maximo de linhas devolvidas em uma unica resposta. */
    val maxRows: Int = 1_000,
    /** Teto para o parametro maxRows enviado pelo cliente. */
    val maxRowsLimit: Int = 10_000,
    /** Timeout padrao da consulta, em segundos. */
    val timeoutSeconds: Int = 30,
    /** Teto para o timeout enviado pelo cliente. */
    val timeoutSecondsLimit: Int = 120,
    /** Quantidade de linhas buscadas por viagem ao servidor. */
    val fetchSize: Int = 500,
    /** Numero maximo de parametros nomeados por consulta. */
    val maxParameters: Int = 100,
    /** Tamanho maximo, em bytes, de um valor binario devolvido em base64. */
    val maxBinaryBytes: Int = 64 * 1024,
    /** Tamanho maximo, em caracteres, de um valor textual (CLOB) devolvido. */
    val maxTextLength: Int = 1_000_000,
    /** Schemas bloqueados. Vazio desativa a checagem. Aceita sufixo `*`. */
    val deniedSchemas: List<String> = listOf("SYS", "_SYS_*", "SYSTEM"),
    /** Schemas permitidos. Vazio libera todos os que nao estao bloqueados. */
    val allowedSchemas: List<String> = emptyList(),
    /** Palavras proibidas em qualquer posicao da instrucao (defesa em profundidade). */
    val deniedKeywords: List<String> = listOf(
        "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "TRUNCATE",
        "DROP", "CREATE", "ALTER", "RENAME", "COMMENT",
        "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "PROCEDURE", "TRIGGER",
        "COMMIT", "ROLLBACK", "SAVEPOINT", "LOCK", "UNLOAD", "IMPORT", "EXPORT",
        "BACKUP", "CONNECT", "DISCONNECT", "SIGNAL", "INTO", "SHUTDOWN",
    ),
)
