package br.andrew.odbc_sap_hana.sql

import br.andrew.odbc_sap_hana.config.QueryProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlySqlValidatorTest {

    private val validator = ReadOnlySqlValidator(QueryProperties())

    private fun rejected(sql: String): String =
        assertFailsWith<SqlValidationException> { validator.validate(sql) }.message!!

    @Test
    fun `aceita select simples`() {
        assertEquals("SELECT 1 FROM DUMMY", validator.validate("  SELECT 1 FROM DUMMY  "))
    }

    @Test
    fun `aceita select com ponto e virgula final`() {
        assertEquals("SELECT 1 FROM DUMMY", validator.validate("SELECT 1 FROM DUMMY;"))
    }

    @Test
    fun `aceita cte e join`() {
        val sql = "WITH t AS (SELECT a, b FROM VENDAS.PEDIDOS) SELECT t.a FROM t JOIN VENDAS.ITENS i ON i.a = t.a"
        assertEquals(sql, validator.validate(sql))
    }

    @Test
    fun `aceita literal contendo palavra proibida`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE NOME = 'DROP TABLE'"
        assertEquals(sql, validator.validate(sql))
    }

    @Test
    fun `rejeita instrucoes de escrita`() {
        listOf(
            "DELETE FROM VENDAS.PEDIDOS",
            "UPDATE VENDAS.PEDIDOS SET total = 1",
            "INSERT INTO VENDAS.PEDIDOS VALUES (1)",
            "DROP TABLE VENDAS.PEDIDOS",
            "CALL SYS.MINHA_PROC()",
        ).forEach { assertTrue(rejected(it).isNotEmpty(), "deveria rejeitar: $it") }
    }

    @Test
    fun `rejeita instrucoes empilhadas`() {
        assertTrue(rejected("SELECT 1 FROM DUMMY; DROP TABLE VENDAS.PEDIDOS").contains("uma instrucao"))
    }

    @Test
    fun `rejeita comentarios`() {
        assertTrue(rejected("SELECT 1 FROM DUMMY -- comentario").contains("Comentarios"))
        assertTrue(rejected("SELECT /* x */ 1 FROM DUMMY").contains("Comentarios"))
    }

    @Test
    fun `rejeita select into e for update`() {
        assertTrue(rejected("SELECT a INTO OUTRA FROM VENDAS.PEDIDOS").isNotEmpty())
        assertTrue(rejected("SELECT a FROM VENDAS.PEDIDOS FOR UPDATE").contains("UPDATE"))
    }

    @Test
    fun `rejeita schema bloqueado`() {
        assertTrue(rejected("SELECT * FROM SYS.USERS").contains("SYS"))
        assertTrue(rejected("SELECT * FROM \"SYS\".USERS").contains("SYS"))
        assertTrue(rejected("SELECT * FROM _SYS_BIC.ALGO").contains("_SYS_BIC"))
    }

    @Test
    fun `rejeita sql vazio ou invalido`() {
        assertTrue(rejected("").isNotEmpty())
        assertTrue(rejected("SELECT FROM WHERE").isNotEmpty())
    }

    @Test
    fun `exige parametros declarados e recusa extras`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE CODIGO = :codigo"
        assertEquals(setOf("codigo"), validator.parameterNames(sql))
        validator.validateParameters(sql, mapOf("codigo" to "C1"))
        assertFailsWith<SqlValidationException> { validator.validateParameters(sql, emptyMap()) }
        assertFailsWith<SqlValidationException> {
            validator.validateParameters(sql, mapOf("codigo" to "C1", "extra" to 1))
        }
    }

    @Test
    fun `dois pontos dentro de literal nao vira parametro`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE OBS = 'chave:valor'"
        assertEquals(emptySet(), validator.parameterNames(sql))
        validator.validateParameters(sql, emptyMap())
    }

    @Test
    fun `cast com dois pontos nao vira parametro`() {
        val sql = "SELECT CODIGO::int FROM VENDAS.CLIENTES"
        assertEquals(emptySet(), validator.parameterNames(sql))
        validator.validateParameters(sql, emptyMap())
    }

    @Test
    fun `parametro fora de literal continua sendo detectado`() {
        val sql = "SELECT * FROM VENDAS.C WHERE OBS = 'chave:valor' AND CODIGO = :codigo"
        assertEquals(setOf("codigo"), validator.parameterNames(sql))
    }

    @Test
    fun `allowlist exige tabela qualificada com schema`() {
        val restrito = ReadOnlySqlValidator(QueryProperties(allowedSchemas = listOf("VENDAS")))
        restrito.validate("SELECT * FROM VENDAS.OINV")
        val erro = assertFailsWith<SqlValidationException> { restrito.validate("SELECT * FROM OINV") }
        assertTrue(erro.message!!.contains("qualificada"), erro.message!!)
        assertFailsWith<SqlValidationException> { restrito.validate("SELECT * FROM OUTRO.OINV") }
    }

    @Test
    fun `allowlist aceita cte sem exigir schema no nome da cte`() {
        val restrito = ReadOnlySqlValidator(QueryProperties(allowedSchemas = listOf("VENDAS")))
        restrito.validate("WITH x AS (SELECT * FROM VENDAS.A) SELECT * FROM x")
    }

    @Test
    fun `sem allowlist tabela sem schema continua aceita`() {
        validator.validate("SELECT * FROM OINV")
    }

    @Test
    fun `rejeita nome de parametro invalido`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE CODIGO = :codigo"
        assertFailsWith<SqlValidationException> {
            validator.validateParameters(sql, mapOf("codigo'; DROP" to "x"))
        }
    }
}
