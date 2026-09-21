package br.andrew.odbc_sap_hana.sql

import br.andrew.odbc_sap_hana.dto.QueryRequest
import br.andrew.odbc_sap_hana.service.QueryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * O painel de vendas precisa filtrar por MULTIPLAS filiais: `IN (:filiais)`.
 * Bind de colecao nao e universal em JDBC - este teste responde se o contrato
 * do sap-odbc aceita uma lista, ou se quem chama tera de gerar um bind por valor.
 */
@SpringBootTest
class BindListaTest {

    @Autowired lateinit var service: QueryService

    @Test
    fun `IN com bind de lista funciona`() {
        val r = service.execute(QueryRequest(
            sql = "SELECT CODIGO FROM VENDAS.CLIENTES WHERE CODIGO IN (:codigos) ORDER BY CODIGO",
            params = mapOf("codigos" to listOf("C0001", "C0002")),
        ))
        assertEquals(2, r.rowCount, "lista deveria expandir para dois valores")
    }

    @Test
    fun `validador aceita lista contando como um parametro`() {
        val v = ReadOnlySqlValidator(br.andrew.odbc_sap_hana.config.QueryProperties())
        v.validateParameters("SELECT A FROM T WHERE X IN (:ids)", mapOf("ids" to listOf(1, 2, 3)))
    }
}
