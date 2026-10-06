package br.andrew.odbc_sap_hana.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class QueryStreamControllerTest(@Autowired val mockMvc: MockMvc) {

    private val json = JsonMapper.builder().build()

    private fun call(body: String) = mockMvc.perform(
        post("/api/v1/query/stream").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun mensagens(body: String): List<Map<*, *>> {
        val resposta = call(body)
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/x-ndjson"))
            .andReturn().response.contentAsString
        return resposta.lines().filter { it.isNotBlank() }.map { json.readValue(it, Map::class.java) }
    }

    @Test
    fun `fluxo entrega colunas, uma mensagem por linha e o fim`() {
        val msgs = mensagens("""{"sql":"SELECT CODIGO, LIMITE FROM VENDAS.CLIENTES ORDER BY CODIGO"}""")

        assertEquals(listOf("colunas", "linha", "linha", "fim"), msgs.map { it["tipo"] })
        assertEquals("CODIGO", ((msgs[0]["columns"] as List<*>)[0] as Map<*, *>)["name"])
        // mesmo formato de valor do /query: decimal como string
        assertEquals(mapOf("CODIGO" to "C0001", "LIMITE" to "1500.5"), msgs[1]["row"])
        assertEquals(2, msgs[3]["rowCount"])
        assertEquals(false, msgs[3]["truncated"])
    }

    @Test
    fun `resultado vazio ainda entrega colunas e fim`() {
        val msgs = mensagens("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES WHERE CODIGO = :c","params":{"c":"NENHUM"}}""")

        assertEquals(listOf("colunas", "fim"), msgs.map { it["tipo"] })
        assertEquals(0, msgs[1]["rowCount"])
    }

    @Test
    fun `maxRows limita e sinaliza truncamento no fim`() {
        val msgs = mensagens("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES","maxRows":1}""")

        assertEquals(listOf("colunas", "linha", "fim"), msgs.map { it["tipo"] })
        assertEquals(true, msgs[2]["truncated"])
    }

    @Test
    fun `teto do fluxo e maior que o do query comum`() {
        // 20.000 passa do maxRowsLimit (10.000) do /query, mas nao do stream-max-rows-limit
        mensagens("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES","maxRows":20000}""")
        call("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES","maxRows":2000000}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.erro").value("sql_invalido"))
    }

    @Test
    fun `instrucao de escrita e recusada com 400 json antes de abrir o fluxo`() {
        call("""{"sql":"DELETE FROM VENDAS.CLIENTES"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.erro").value("sql_invalido"))
    }

    @Test
    fun `sql recusado pelo banco volta como erro http comum, nao dentro do fluxo`() {
        // Regressao: com o Content-Type ndjson definido antes de executar, o envelope JSON
        // deste erro nao conseguia ser escrito e virava 500.
        call("""{"sql":"SELECT X FROM VENDAS.NAO_EXISTE"}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.erro").value("sql_rejeitado_pelo_banco"))
    }

    @Test
    fun `reserva do fluxo e liberada ao terminar`() {
        // teto padrao e 2: sem liberar, a terceira chamada seguida seria recusada
        repeat(5) { mensagens("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES"}""") }
        call("""{"sql":"SELECT X FROM VENDAS.NAO_EXISTE"}""").andExpect(status().isBadRequest)
        call("""{"sql":"SELECT X FROM VENDAS.NAO_EXISTE"}""").andExpect(status().isBadRequest)
        mensagens("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES"}""")
    }
}

@SpringBootTest(properties = ["query.max-concurrent-streams=0"])
@AutoConfigureMockMvc
class QueryStreamOcupadoTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `sem fluxo disponivel responde 503 com retry-after`() {
        mockMvc.perform(
            post("/api/v1/query/stream").contentType(MediaType.APPLICATION_JSON)
                .content("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(jsonPath("$.erro").value("ocupado"))
    }
}
