package br.andrew.odbc_sap_hana.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class QueryControllerTest(@Autowired val mockMvc: MockMvc) {

    private fun call(body: String) = mockMvc.perform(
        post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `consulta com parametro nomeado devolve json`() {
        call("""{"sql":"SELECT CODIGO, NOME, LIMITE FROM VENDAS.CLIENTES WHERE CODIGO = :codigo","params":{"codigo":"C0001"}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rowCount").value(1))
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.rows[0].NOME").value("Cliente Um"))
            .andExpect(jsonPath("$.rows[0].LIMITE").value("1500.5"))
            .andExpect(jsonPath("$.columns[0].name").value("CODIGO"))
    }

    @Test
    fun `valor do parametro com aspas nao vira sql`() {
        call("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES WHERE NOME = :nome","params":{"nome":"x' OR '1'='1"}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rowCount").value(0))
    }

    @Test
    fun `instrucao de escrita e recusada com 400`() {
        call("""{"sql":"DELETE FROM VENDAS.CLIENTES"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("sql_invalido"))
    }

    @Test
    fun `instrucao empilhada e recusada com 400`() {
        call("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES; DROP TABLE VENDAS.CLIENTES"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `maxRows limita e sinaliza truncamento`() {
        call("""{"sql":"SELECT CODIGO FROM VENDAS.CLIENTES","maxRows":1}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rowCount").value(1))
            .andExpect(jsonPath("$.truncated").value(true))
    }
}
