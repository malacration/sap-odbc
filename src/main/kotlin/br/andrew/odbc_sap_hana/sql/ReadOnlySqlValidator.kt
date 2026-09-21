package br.andrew.odbc_sap_hana.sql

import br.andrew.odbc_sap_hana.config.QueryProperties
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.util.TablesNamesFinder
import org.springframework.stereotype.Component

/**
 * Garante que a instrucao recebida e uma unica consulta de leitura.
 *
 * A validacao e feita em camadas, de modo que uma falha em qualquer uma delas
 * ja rejeita a instrucao:
 *  1. limites de tamanho e ausencia de comentarios (usados para ofuscar payloads);
 *  2. instrucao unica - nada depois do primeiro `;`;
 *  3. inicio obrigatorio em SELECT ou WITH;
 *  4. analise sintatica real (JSqlParser): o no raiz precisa ser um SELECT;
 *  5. lista de palavras proibidas e checagem de schema.
 *
 * A validacao **nao** substitui um usuario de banco somente-leitura: ela e a
 * primeira barreira, e o privilegio do usuario no SAP HANA e a ultima.
 */
@Component
class ReadOnlySqlValidator(private val props: QueryProperties) {

    private val startsWithSelect = Regex("^(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)
    private val forUpdate = Regex("\\bFOR\\s+UPDATE\\b", RegexOption.IGNORE_CASE)
    private val parameterName = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
    // (?<!:) evita casar o segundo `:` de um cast `a::int` como parametro.
    private val namedParameter = Regex("(?<!:):([A-Za-z][A-Za-z0-9_]*)")

    /**
     * @return a instrucao normalizada (sem espacos nas pontas e sem `;` final).
     * @throws SqlValidationException se a instrucao nao for uma consulta de leitura.
     */
    fun validate(rawSql: String?): String {
        val sql = rawSql?.trim().orEmpty()
        if (sql.isEmpty()) throw SqlValidationException("A instrucao SQL e obrigatoria.")
        if (sql.length > props.maxSqlLength) {
            throw SqlValidationException("Instrucao SQL maior que o limite de ${props.maxSqlLength} caracteres.")
        }

        rejectComments(sql)
        val single = requireSingleStatement(sql)

        if (!startsWithSelect.containsMatchIn(single)) {
            throw SqlValidationException("Somente consultas de leitura sao aceitas: a instrucao deve comecar com SELECT ou WITH.")
        }

        val statement = parse(single)
        if (statement !is Select) {
            throw SqlValidationException("Somente instrucoes SELECT sao aceitas.")
        }
        rejectWriteConstructs(statement)

        rejectDeniedKeywords(single)
        if (forUpdate.containsMatchIn(single)) {
            throw SqlValidationException("Clausula proibida: FOR UPDATE.")
        }
        checkSchemas(statement)

        return single
    }

    /**
     * Nomes de parametros nomeados (`:nome`) presentes na instrucao.
     *
     * A busca ignora o conteudo de literais de texto - `WHERE OBS = \'chave:valor\'`
     * nao declara um parametro `valor` - e casts no estilo `a::int`, do mesmo modo
     * que o NamedParameterJdbcTemplate faz na hora de executar. Sem isso, a
     * validacao exigiria parametros que o banco nunca vai pedir.
     */
    fun parameterNames(sql: String): Set<String> =
        namedParameter.findAll(mascararParaParametros(sql)).map { it.groupValues[1] }.toSet()

    /**
     * Mascara literais de texto E identificadores entre aspas duplas.
     *
     * Difere de [maskLiterals], que deixa o conteudo das aspas duplas VISIVEL de
     * proposito - la o objetivo e impedir que palavra proibida se esconda num
     * identificador. Aqui o objetivo e outro: `SELECT 1 AS "saldo:filial"` nao
     * declara um parametro `filial`, e o binder do NamedParameterJdbcTemplate
     * tambem nao o enxerga. Sem esta mascara o validador exigiria um parametro
     * que o executor ignora, e a consulta legitima seria recusada.
     */
    private fun mascararParaParametros(sql: String): String {
        val saida = StringBuilder(sql.length)
        var aspas: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                aspas != null -> {
                    if (c == aspas) {
                        if (i + 1 < sql.length && sql[i + 1] == aspas) { saida.append("  "); i += 2; continue }
                        aspas = null; saida.append(c)
                    } else saida.append(' ')
                }
                c == '\'' || c == '"' -> { aspas = c; saida.append(c) }
                else -> saida.append(c)
            }
            i++
        }
        return saida.toString()
    }

    fun validateParameters(sql: String, params: Map<String, Any?>) {
        if (params.size > props.maxParameters) {
            throw SqlValidationException("Numero de parametros acima do limite de ${props.maxParameters}.")
        }
        params.keys.forEach { name ->
            if (!parameterName.matches(name)) {
                throw SqlValidationException("Nome de parametro invalido: '$name'.")
            }
        }
        val required = parameterNames(sql)
        val missing = required - params.keys
        if (missing.isNotEmpty()) {
            throw SqlValidationException("Parametros ausentes: ${missing.sorted().joinToString(", ")}.")
        }
        val unused = params.keys - required
        if (unused.isNotEmpty()) {
            throw SqlValidationException("Parametros nao referenciados na consulta: ${unused.sorted().joinToString(", ")}.")
        }
    }

    /**
     * Comentarios sao recusados porque servem para esconder trechos da instrucao
     * de quem le a query (inclusive de auditoria) sem alterar o que o banco executa.
     */
    private fun rejectComments(sql: String) {
        var i = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        if (i + 1 < sql.length && sql[i + 1] == quote) i++ else quote = null
                    }
                }
                c == '\'' || c == '"' -> quote = c
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' ->
                    throw SqlValidationException("Comentarios nao sao permitidos na instrucao SQL.")
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' ->
                    throw SqlValidationException("Comentarios nao sao permitidos na instrucao SQL.")
            }
            i++
        }
        if (quote != null) throw SqlValidationException("Literal de texto nao fechado na instrucao SQL.")
    }

    /** Recusa qualquer conteudo apos o primeiro `;` (SQL injection empilhada). */
    private fun requireSingleStatement(sql: String): String {
        var quote: Char? = null
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        if (i + 1 < sql.length && sql[i + 1] == quote) i++ else quote = null
                    }
                }
                c == '\'' || c == '"' -> quote = c
                c == ';' -> {
                    val rest = sql.substring(i + 1).trim()
                    if (rest.isNotEmpty()) {
                        throw SqlValidationException("Apenas uma instrucao por requisicao e permitida.")
                    }
                    return sql.substring(0, i).trim()
                }
            }
            i++
        }
        return sql
    }

    private fun parse(sql: String): Statement = try {
        CCJSqlParserUtil.parse(sql) { parser -> parser.withTimeOut(PARSE_TIMEOUT_MS).withAllowComplexParsing(true) }
    } catch (ex: Exception) {
        throw SqlValidationException("Nao foi possivel interpretar a instrucao SQL: ${ex.message?.lineSequence()?.firstOrNull()}")
    }

    /** `SELECT ... INTO tabela` e `FOR UPDATE` escrevem/bloqueiam dados mesmo sendo SELECT. */
    private fun rejectWriteConstructs(select: Select) {
        when (select) {
            is PlainSelect -> {
                if (!select.intoTables.isNullOrEmpty()) {
                    throw SqlValidationException("Clausula proibida: SELECT ... INTO.")
                }
                if (select.intoTempTable != null) {
                    throw SqlValidationException("Clausula proibida: SELECT ... INTO.")
                }
            }
            is SetOperationList -> select.selects.forEach { rejectWriteConstructs(it) }
            else -> Unit
        }
        select.withItemsList?.forEach { item ->
            // Uma CTE cujo corpo nao e SELECT (`WITH x AS (DELETE ... RETURNING *)`)
            // escreve dados. Nesse caso o getter do JSqlParser lanca ClassCastException,
            // entao tratamos qualquer corpo que nao seja Select como rejeicao - do
            // contrario o erro escaparia como 500 em vez de 400.
            val inner = runCatching { item.select as? Select }.getOrNull()
                ?: throw SqlValidationException("Somente subconsultas de leitura sao aceitas na clausula WITH.")
            rejectWriteConstructs(inner)
        }
    }

    private fun rejectDeniedKeywords(sql: String) {
        val masked = maskLiterals(sql)
        props.deniedKeywords.forEach { keyword ->
            val pattern = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(masked)) {
                throw SqlValidationException("Palavra-chave proibida em consulta somente-leitura: $keyword.")
            }
        }
    }

    /** Aplica as listas de schemas sobre as tabelas realmente referenciadas. */
    private fun checkSchemas(statement: Statement) {
        if (props.deniedSchemas.isEmpty() && props.allowedSchemas.isEmpty()) return
        val tables = try {
            TablesNamesFinder<Void>().getTables(statement)
        } catch (ex: Exception) {
            throw SqlValidationException("Nao foi possivel identificar as tabelas da consulta: ${ex.message}")
        }
        tables.forEach { fullName ->
            val parts = fullName.split('.').map { it.trim('"').uppercase() }
            val schema = if (parts.size >= 2) {
                parts[parts.size - 2]
            } else {
                // Tabela sem schema resolve pelo schema corrente da conexao, que a
                // aplicacao nao controla. Com allowlist ativa isso seria um desvio:
                // bastaria omitir o schema para escapar da lista. Entao exigimos
                // o nome qualificado. (Nomes de CTE nao chegam aqui - o parser os
                // resolve antes.)
                if (props.allowedSchemas.isNotEmpty()) {
                    throw SqlValidationException(
                        "A tabela '$fullName' precisa ser qualificada com o schema " +
                            "(ex.: SCHEMA.$fullName) quando existe lista de schemas permitidos.",
                    )
                }
                return@forEach
            }
            if (props.deniedSchemas.any { matches(it, schema) }) {
                throw SqlValidationException("Acesso negado ao schema '$schema'.")
            }
            if (props.allowedSchemas.isNotEmpty() && props.allowedSchemas.none { matches(it, schema) }) {
                throw SqlValidationException("Schema '$schema' nao esta na lista de schemas permitidos.")
            }
        }
    }

    private fun matches(pattern: String, schema: String): Boolean {
        val p = pattern.uppercase()
        return if (p.endsWith("*")) schema.startsWith(p.dropLast(1)) else schema == p
    }

    /**
     * Substitui o conteudo dos literais de texto por espacos, para que a busca por
     * palavras proibidas nao dispare com dados legitimos (ex.: `WHERE nome = 'DROP'`).
     * Identificadores entre aspas duplas continuam visiveis - de proposito - para que
     * nao sirvam de esconderijo para palavras-chave proibidas.
     */
    private fun maskLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var inLiteral = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                inLiteral -> {
                    if (c == '\'') {
                        if (i + 1 < sql.length && sql[i + 1] == '\'') {
                            out.append("  ")
                            i += 2
                            continue
                        }
                        inLiteral = false
                        out.append(c)
                    } else {
                        out.append(' ')
                    }
                }
                c == '\'' -> {
                    inLiteral = true
                    out.append(c)
                }
                c == '"' -> out.append(' ')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        const val PARSE_TIMEOUT_MS = 2_000L
    }
}
