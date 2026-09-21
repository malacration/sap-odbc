package br.andrew.odbc_sap_hana.web

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import kotlin.test.Test

/**
 * Toda resposta de erro precisa ser JSON no envelope padrao.
 *
 * Sem o ApiErrorController, uma rota inexistente caia no handler do container e
 * devolvia a pagina HTML do Tomcat ("HTTP Status 404 - Not Found"). O cliente
 * quebrava ao parsear e o usuario final via erro de parse em vez do problema
 * real, que era URL errada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErroJsonTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `rota inexistente devolve JSON, nao HTML`() {
        mvc.perform(get("/rota/que/nao/existe"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.erro").value("rota_nao_encontrada"))
            .andExpect(jsonPath("$.mensagem").exists())
    }

    @Test
    fun `metodo errado devolve JSON`() {
        mvc.perform(get("/api/v1/query"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.erro").exists())
    }

    @Test
    fun `erro de validacao usa o mesmo envelope`() {
        mvc.perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sql":"DELETE FROM VENDAS.CLIENTES","params":{}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.erro").value("sql_invalido"))
            .andExpect(jsonPath("$.mensagem").exists())
    }

    @Test
    fun `o filtro de api key usa o mesmo envelope`() {
        // O ApiKeyFilter escreve o JSON a mao, fora do @RestControllerAdvice -
        // por isso ficou com o formato antigo quando o envelope mudou. Este
        // teste trava os dois formatos juntos.
        val fonte = java.io.File("src/main/kotlin/br/andrew/odbc_sap_hana/security/ApiKeyFilter.kt").readText()
        kotlin.test.assertTrue("\"erro\"" in fonte, "o filtro precisa usar o campo 'erro'")
        kotlin.test.assertTrue("\"mensagem\"" in fonte, "o filtro precisa usar o campo 'mensagem'")
        kotlin.test.assertTrue("\"error\"" !in fonte, "sobrou o campo antigo 'error' no filtro")
    }

    @Test
    fun `corpo JSON invalido devolve 400, nao 500`() {
        // Antes caia no handler generico: erro do cliente reportado como falha
        // do servidor manda o suporte investigar o lugar errado.
        mvc.perform(
            post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{ isso nao e json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.erro").value("corpo_invalido"))
    }

    @Test
    fun `campo com tipo errado devolve 400`() {
        mvc.perform(
            post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                .content("""{"sql":"SELECT 1 FROM DUMMY","maxRows":"muitas"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.erro").value("corpo_invalido"))
    }

    @Test
    fun `erro do banco nao devolve a instrucao SQL`() {
        // O driver do HANA inclui o SQL no texto da excecao; repassa-lo
        // entregaria a consulta e os nomes de tabela ao chamador.
        mvc.perform(
            post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                .content("""{"sql":"SELECT SENHA FROM VENDAS.TABELA_QUE_NAO_EXISTE","params":{}}"""),
        )
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SENHA")),
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TABELA_QUE_NAO_EXISTE")),
            )))
    }

    @Test
    fun `a mensagem de erro nunca devolve o SQL enviado`() {
        mvc.perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sql":"SELECT SENHA FROM SEGREDO -- oculto","params":{}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("SEGREDO"),
            )))
    }
}
