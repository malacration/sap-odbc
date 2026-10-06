package br.andrew.odbc_sap_hana.service

import br.andrew.odbc_sap_hana.config.QueryProperties
import br.andrew.odbc_sap_hana.dto.ColumnMeta
import br.andrew.odbc_sap_hana.dto.QueryRequest
import br.andrew.odbc_sap_hana.dto.QueryResponse
import br.andrew.odbc_sap_hana.sql.ReadOnlySqlValidator
import br.andrew.odbc_sap_hana.sql.SqlValidationException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.Blob
import java.sql.Clob
import java.sql.ResultSet
import java.sql.SQLFeatureNotSupportedException
import java.util.Base64
import java.util.concurrent.Semaphore
import javax.sql.DataSource

/** Todos os fluxos simultaneos permitidos ja estao em uso. */
class FluxoOcupadoException(limite: Int) : RuntimeException(
    "Limite de $limite consultas em fluxo simultaneas atingido. Tente novamente em instantes.",
)

@Service
class QueryService(
    private val dataSource: DataSource,
    private val validator: ReadOnlySqlValidator,
    private val props: QueryProperties,
) {
    private val log = LoggerFactory.getLogger(QueryService::class.java)
    private val fluxos = Semaphore(props.maxConcurrentStreams)

    fun execute(request: QueryRequest): QueryResponse {
        val sql = validator.validate(request.sql)
        validator.validateParameters(sql, request.params)

        val maxRows = clamp(request.maxRows ?: props.maxRows, props.maxRowsLimit, "maxRows")
        val timeout = clamp(request.timeoutSeconds ?: props.timeoutSeconds, props.timeoutSecondsLimit, "timeoutSeconds")
        val template = template(maxRows, timeout)

        log.info("Executando consulta somente-leitura (params={}, maxRows={}, timeout={}s)", request.params.keys, maxRows, timeout)
        val start = System.nanoTime()
        val result = template.query(sql, MapSqlParameterSource(request.params), extractor(maxRows))
        val elapsed = (System.nanoTime() - start) / 1_000_000

        return QueryResponse(
            columns = result.columns,
            rows = result.rows,
            rowCount = result.rows.size,
            truncated = result.truncated,
            elapsedMs = elapsed,
        )
    }

    /**
     * Valida e reserva um fluxo, SEM executar. Tudo o que pode ser recusado antes da
     * primeira linha (SQL invalido, parametro, limite, fluxo ocupado) sai daqui como
     * excecao comum, e o chamador responde com o status HTTP correto - depois que o
     * fluxo comeca, o 200 ja foi enviado e erro so pode ir dentro do proprio fluxo.
     *
     * A reserva e liberada no [ConsultaEmFluxo.close]: use sempre com `use {}`.
     */
    fun prepararFluxo(request: QueryRequest): ConsultaEmFluxo {
        val sql = validator.validate(request.sql)
        validator.validateParameters(sql, request.params)
        val maxRows = clamp(request.maxRows ?: props.maxRows, props.streamMaxRowsLimit, "maxRows")
        val timeout = clamp(request.timeoutSeconds ?: props.timeoutSeconds, props.timeoutSecondsLimit, "timeoutSeconds")
        if (!fluxos.tryAcquire()) throw FluxoOcupadoException(props.maxConcurrentStreams)
        return ConsultaEmFluxo(sql, request.params, maxRows, timeout)
    }

    inner class ConsultaEmFluxo internal constructor(
        private val sql: String,
        private val params: Map<String, Any?>,
        private val maxRows: Int,
        private val timeout: Int,
    ) : AutoCloseable {
        private var liberada = false

        /**
         * Le o resultado linha a linha (fetchSize do driver) e entrega cada uma ao
         * chamador, sem acumular. Excecao de [aoLer] (cliente desconectou) interrompe
         * a leitura e o JdbcTemplate fecha ResultSet e conexao.
         */
        fun executar(aoIniciar: (List<ColumnMeta>) -> Unit, aoLer: (Map<String, Any?>) -> Unit): FimFluxo {
            log.info("Executando consulta em fluxo (params={}, maxRows={}, timeout={}s)", params.keys, maxRows, timeout)
            val start = System.nanoTime()
            val (linhas, truncado) = template(maxRows, timeout).query(sql, MapSqlParameterSource(params), ResultSetExtractor { rs ->
                val columns = colunas(rs)
                aoIniciar(columns)
                var lidas = 0
                var truncado = false
                while (rs.next()) {
                    if (lidas >= maxRows) {
                        truncado = true
                        break
                    }
                    aoLer(linha(rs, columns))
                    lidas++
                }
                lidas to truncado
            })!!
            return FimFluxo(linhas, truncado, (System.nanoTime() - start) / 1_000_000)
        }

        override fun close() {
            if (!liberada) {
                liberada = true
                fluxos.release()
            }
        }
    }

    data class FimFluxo(val rowCount: Int, val truncated: Boolean, val elapsedMs: Long)

    // Uma instancia por requisicao: maxRows/queryTimeout sao estado mutavel do template.
    private fun template(maxRows: Int, timeout: Int): NamedParameterJdbcTemplate {
        val jdbc = JdbcTemplate(dataSource).apply {
            this.queryTimeout = timeout
            // +1 linha para descobrir se o resultado foi truncado.
            this.maxRows = maxRows + 1
            this.fetchSize = minOf(props.fetchSize, maxRows + 1)
            this.isResultsMapCaseInsensitive = false
        }
        return NamedParameterJdbcTemplate(jdbc)
    }

    private fun clamp(value: Int, limit: Int, field: String): Int {
        if (value <= 0) throw SqlValidationException("O campo '$field' deve ser maior que zero.")
        if (value > limit) throw SqlValidationException("O campo '$field' excede o limite de $limit.")
        return value
    }

    private class Extraction(
        val columns: List<ColumnMeta>,
        val rows: List<Map<String, Any?>>,
        val truncated: Boolean,
    )

    private fun extractor(maxRows: Int) = ResultSetExtractor { rs: ResultSet ->
        val columns = colunas(rs)
        val rows = ArrayList<Map<String, Any?>>()
        var truncated = false
        while (rs.next()) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            rows.add(linha(rs, columns))
        }
        Extraction(columns, rows, truncated)
    }

    private fun colunas(rs: ResultSet): List<ColumnMeta> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { i ->
            ColumnMeta(
                name = meta.getColumnLabel(i) ?: meta.getColumnName(i),
                type = meta.getColumnTypeName(i) ?: "UNKNOWN",
                nullable = meta.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls,
            )
        }
    }

    private fun linha(rs: ResultSet, columns: List<ColumnMeta>): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>(columns.size)
        columns.forEachIndexed { idx, column ->
            row[uniqueKey(row, column.name, idx)] = toJsonValue(rs.getObject(idx + 1))
        }
        return row
    }

    /** Consultas podem devolver rotulos repetidos; mantemos todos no JSON. */
    private fun uniqueKey(row: Map<String, Any?>, name: String, index: Int): String {
        val base = name.ifBlank { "column_${index + 1}" }
        if (!row.containsKey(base)) return base
        var n = 2
        while (row.containsKey("${base}_$n")) n++
        return "${base}_$n"
    }

    /** Converte tipos JDBC em valores que o Jackson serializa sem surpresa. */
    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> null
        is java.sql.Timestamp -> value.toLocalDateTime().toString()
        is java.sql.Date -> value.toLocalDate().toString()
        is java.sql.Time -> value.toLocalTime().toString()
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is ByteArray -> encodeBinary(value)
        is Blob -> readBlob(value)
        is Clob -> readClob(value)
        is java.sql.Array -> (value.array as? Array<*>)?.map { toJsonValue(it) } ?: value.toString()
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private fun encodeBinary(bytes: ByteArray): Any =
        if (bytes.size > props.maxBinaryBytes) {
            mapOf("truncated" to true, "bytes" to bytes.size)
        } else {
            Base64.getEncoder().encodeToString(bytes)
        }

    private fun readBlob(blob: Blob): Any? = try {
        val length = blob.length()
        if (length > props.maxBinaryBytes) {
            mapOf("truncated" to true, "bytes" to length)
        } else {
            Base64.getEncoder().encodeToString(blob.getBytes(1, length.toInt()))
        }
    } catch (ex: SQLFeatureNotSupportedException) {
        log.debug("BLOB nao suportado pelo driver", ex)
        null
    } finally {
        runCatching { blob.free() }
    }

    private fun readClob(clob: Clob): Any? = try {
        val length = clob.length()
        clob.getSubString(1, minOf(length, props.maxTextLength.toLong()).toInt())
    } catch (ex: SQLFeatureNotSupportedException) {
        log.debug("CLOB nao suportado pelo driver", ex)
        null
    } finally {
        runCatching { clob.free() }
    }
}