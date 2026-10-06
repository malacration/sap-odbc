package br.andrew.odbc_sap_hana.web

import br.andrew.odbc_sap_hana.dto.QueryRequest
import br.andrew.odbc_sap_hana.dto.QueryResponse
import br.andrew.odbc_sap_hana.service.QueryService
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedOutputStream
import java.io.OutputStream
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class QueryController(private val service: QueryService, private val json: JsonMapper) {

    private val log = LoggerFactory.getLogger(QueryController::class.java)

    /**
     * Executa uma consulta somente-leitura e devolve o resultado em JSON.
     *
     * ```
     * POST /api/v1/query
     * {
     *   "sql": "SELECT DocEntry, CardCode FROM SBODEMO.OINV WHERE CardCode = :cliente",
     *   "params": { "cliente": "C0001" },
     *   "maxRows": 500
     * }
     * ```
     */
    @PostMapping("/query", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun query(@RequestBody request: QueryRequest): QueryResponse = service.execute(request)

    /**
     * Mesma consulta, entregue em FLUXO (NDJSON, uma mensagem JSON por linha), para
     * resultados grandes demais para uma resposta unica. Nada e acumulado em memoria:
     * cada linha lida do banco (fetchSize) vai para a rede. Mesmo validador, mesmos
     * parametros, teto proprio de linhas (`query.stream-max-rows-limit`).
     *
     * ```
     * {"tipo":"colunas","columns":[{"name":"CODIGO","type":"NVARCHAR","nullable":true}]}
     * {"tipo":"linha","row":{"CODIGO":"C0001"}}
     * {"tipo":"fim","rowCount":1,"truncated":false,"elapsedMs":12}
     * ```
     *
     * Erro ANTES da primeira mensagem (SQL invalido, parametro, fluxo ocupado, SQL
     * recusado pelo banco) volta como erro HTTP comum, com o envelope de sempre. Erro
     * DEPOIS (timeout ou queda no meio da leitura) so pode ir dentro do fluxo, porque o
     * 200 ja saiu: `{"tipo":"erro","erro":"timeout","mensagem":"..."}`. Sem a mensagem
     * `fim`, o resultado esta INCOMPLETO e o cliente deve descarta-lo.
     *
     * Sincrono de proposito (escreve direto na resposta, sem StreamingResponseBody): o
     * caminho assincrono herdaria o timeout de requisicao assincrona do container e
     * cortaria leituras longas no meio.
     */
    @PostMapping("/query/stream", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun stream(@RequestBody request: QueryRequest, response: HttpServletResponse) {
        service.prepararFluxo(request).use { consulta ->
            val saida = FluxoNdjson(response)
            try {
                val fim = consulta.executar(
                    aoIniciar = { colunas -> saida.escrever(mapOf("tipo" to "colunas", "columns" to colunas)) },
                    aoLer = { linha -> saida.escrever(mapOf("tipo" to "linha", "row" to linha)) },
                )
                saida.escrever(mapOf(
                    "tipo" to "fim", "rowCount" to fim.rowCount,
                    "truncated" to fim.truncated, "elapsedMs" to fim.elapsedMs,
                ))
                saida.fechar()
            } catch (ex: Exception) {
                // Nada escrito ainda: a resposta nao foi confirmada e o ApiExceptionHandler
                // devolve o status correto (400 para SQL recusado pelo banco, 504, 502).
                if (!saida.iniciou) throw ex
                if (ex is java.io.IOException) {
                    log.warn("Cliente encerrou o fluxo antes do fim: {}", ex.message)
                    return
                }
                val (_, corpo) = ErrosBanco.traduzir(ex)
                log.error("Falha no meio do fluxo ({})", corpo.erro, ex)
                runCatching {
                    saida.escrever(mapOf("tipo" to "erro", "erro" to corpo.erro,
                        "mensagem" to corpo.mensagem, "sqlState" to corpo.sqlState))
                    saida.fechar()
                }
            }
        }
    }

    /**
     * Uma mensagem por linha. Flush a cada [FLUSH_A_CADA] mensagens: empurra os dados
     * pela rede (o cliente nao espera o fim para comecar a gravar) e mantem a conexao
     * viva em leituras longas, sem pagar um flush por linha.
     */
    private inner class FluxoNdjson(private val response: HttpServletResponse) {
        var iniciou = false
            private set
        private var pendentes = 0
        private lateinit var out: OutputStream

        fun escrever(mensagem: Map<String, Any?>) {
            if (!iniciou) {
                // So aqui, na primeira mensagem: com o Content-Type ndjson ja definido, um
                // erro anterior (SQL recusado pelo banco) faria o ApiExceptionHandler tentar
                // serializar o envelope JSON como ndjson e falhar com 500.
                response.contentType = NDJSON
                response.setHeader("Cache-Control", "no-store")
                // Proxy reverso (nginx) nao deve segurar o fluxo em buffer ate o fim.
                response.setHeader("X-Accel-Buffering", "no")
                out = BufferedOutputStream(response.outputStream, 64 * 1024)
                iniciou = true
            }
            out.write(json.writeValueAsBytes(mensagem))
            out.write('\n'.code)
            if (++pendentes >= FLUSH_A_CADA) {
                out.flush()
                pendentes = 0
            }
        }

        fun fechar() { if (iniciou) out.flush() }
    }

    private companion object {
        const val NDJSON = "application/x-ndjson"
        const val FLUSH_A_CADA = 500
    }
}
