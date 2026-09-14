package br.andrew.odbc_sap_hana.sql

import br.andrew.odbc_sap_hana.config.QueryProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Testes de ataque: cada caso e uma tentativa concreta de escapar da validacao
 * somente-leitura. Diferente do [ReadOnlySqlValidatorTest], que cobre o contrato
 * normal da classe, aqui o objetivo e adversarial - se um destes passar a ser
 * ACEITO, a barreira de validacao foi furada.
 *
 * Os casos "legitimos" no fim do arquivo existem para que o endurecimento da
 * validacao nao seja feito as custas de recusar consulta valida: uma validacao
 * que rejeita tudo tambem esta quebrada.
 */
class SqlInjectionAttackTest {

    private val validator = ReadOnlySqlValidator(QueryProperties())
    private val comAllowlist = ReadOnlySqlValidator(
        QueryProperties(allowedSchemas = listOf("VENDAS")),
    )

    private fun bloqueado(sql: String, v: ReadOnlySqlValidator = validator) {
        assertFailsWith<SqlValidationException>("DEVERIA SER BLOQUEADO: $sql") { v.validate(sql) }
    }

    private fun aceito(sql: String, v: ReadOnlySqlValidator = validator) {
        val r = runCatching { v.validate(sql) }
        assertTrue(r.isSuccess, "deveria ser aceito: $sql -> ${r.exceptionOrNull()?.message}")
    }

    // --- Instrucoes empilhadas (o classico `'; DROP TABLE --`) ---

    @Test
    fun `stacked queries em todas as formas`() {
        bloqueado("SELECT 1 FROM DUMMY; DROP TABLE VENDAS.PEDIDOS")
        bloqueado("SELECT 1 FROM DUMMY; DELETE FROM VENDAS.PEDIDOS")
        bloqueado("SELECT 1 FROM DUMMY;DROP TABLE VENDAS.PEDIDOS")
        bloqueado("SELECT 1 FROM DUMMY;\n\tUPDATE VENDAS.PEDIDOS SET A = 1")
        bloqueado("SELECT 1 FROM DUMMY ; ; DROP TABLE VENDAS.P")
        bloqueado("SELECT 1 FROM DUMMY; CALL SYS.PROC()")
    }

    // --- Comentarios usados para esconder trecho da instrucao ---

    @Test
    fun `comentarios usados para ofuscar`() {
        bloqueado("SELECT 1 FROM DUMMY -- ; DROP TABLE X")
        bloqueado("SELECT /* DROP */ 1 FROM DUMMY")
        bloqueado("SELECT 1 FROM DUMMY /*! MYSQL HINT */")
        bloqueado("SELECT 1/**/FROM DUMMY")
        bloqueado("SELECT 1 FROM DUMMY --")
    }

    // --- Escrita disfarcada de leitura ---

    @Test
    fun `escrita disfarcada de select`() {
        bloqueado("SELECT a INTO NOVA_TABELA FROM VENDAS.PEDIDOS")
        bloqueado("SELECT a FROM VENDAS.PEDIDOS FOR UPDATE")
        bloqueado("WITH x AS (DELETE FROM VENDAS.P RETURNING *) SELECT * FROM x")
        bloqueado("SELECT * FROM VENDAS.P UNION ALL SELECT * FROM VENDAS.Q FOR UPDATE")
    }

    @Test
    fun `dml e ddl direto`() {
        listOf(
            "INSERT INTO VENDAS.P VALUES (1)",
            "UPDATE VENDAS.P SET TOTAL = 0",
            "DELETE FROM VENDAS.P",
            "DROP TABLE VENDAS.P",
            "TRUNCATE TABLE VENDAS.P",
            "ALTER TABLE VENDAS.P ADD C INT",
            "CREATE TABLE X (A INT)",
            "GRANT SELECT ON VENDAS.P TO PUBLIC",
            "MERGE INTO VENDAS.P USING VENDAS.Q ON (1=1)",
            "CALL SYS.MINHA_PROC()",
        ).forEach { bloqueado(it) }
    }

    // --- Acesso a schema do sistema por caminhos indiretos ---
    // E aqui que validacao ingenua (olhar so o inicio da query) costuma falhar.

    @Test
    fun `schema do sistema via caminhos indiretos`() {
        bloqueado("SELECT * FROM SYS.USERS")
        bloqueado("SELECT 1 FROM VENDAS.A UNION ALL SELECT 1 FROM SYS.USERS")
        bloqueado("SELECT 1 FROM VENDAS.A UNION SELECT 1 FROM SYSTEM.ALGO")
        bloqueado("SELECT (SELECT COUNT(*) FROM SYS.USERS) FROM DUMMY")
        bloqueado("WITH x AS (SELECT * FROM SYS.USERS) SELECT * FROM x")
        bloqueado("SELECT * FROM VENDAS.A WHERE ID IN (SELECT ID FROM SYS.USERS)")
        bloqueado("SELECT * FROM VENDAS.A JOIN SYS.USERS u ON u.ID = VENDAS.A.ID")
        bloqueado("SELECT * FROM (SELECT * FROM SYS.USERS) t")
        bloqueado("SELECT * FROM _SYS_BIC.ALGUMA_VIEW")
        bloqueado("SELECT * FROM \"SYS\".\"USERS\"")
        bloqueado("SELECT * FROM sys.users")
    }

    @Test
    fun `allowlist nao pode ser contornada`() {
        bloqueado("SELECT * FROM OUTRO.OINV", comAllowlist)
        // Omitir o schema deixaria a resolucao para o schema corrente da conexao.
        bloqueado("SELECT * FROM OINV", comAllowlist)
        bloqueado("SELECT * FROM VENDAS.A UNION ALL SELECT * FROM OUTRO.B", comAllowlist)
        bloqueado("SELECT (SELECT 1 FROM OUTRO.B) FROM VENDAS.A", comAllowlist)
    }

    // --- Payloads que chegam como DADO, nao como SQL ---
    // Devem ser aceitos: o valor vai como bind variable e nunca e interpretado.

    @Test
    fun `payload classico como valor de parametro e inofensivo`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE NOME = :nome"
        aceito(sql)
        listOf(
            "'; DROP TABLE VENDAS.CLIENTES; --",
            "' OR '1'='1",
            "1' UNION SELECT * FROM SYS.USERS --",
            "admin'--",
        ).forEach { payload ->
            // O payload e dado: passa pela validacao de parametros sem virar SQL.
            validator.validateParameters(sql, mapOf("nome" to payload))
        }
    }

    @Test
    fun `nome de parametro nao pode carregar sql`() {
        val sql = "SELECT * FROM VENDAS.CLIENTES WHERE NOME = :nome"
        listOf("nome'; DROP TABLE X; --", "no me", "no-me", "1nome", "", "nome)")
            .forEach { ruim ->
                assertFailsWith<SqlValidationException>("nome invalido aceito: '$ruim'") {
                    validator.validateParameters(sql, mapOf(ruim to "x"))
                }
            }
    }

    // --- Abuso de recursos ---

    @Test
    fun `instrucao acima do limite de tamanho`() {
        val gigante = "SELECT " + "A,".repeat(20_000) + "B FROM VENDAS.C"
        bloqueado(gigante)
    }

    @Test
    fun `excesso de parametros`() {
        val sql = "SELECT * FROM VENDAS.C WHERE X = :p0"
        val muitos = (0..200).associate { "p$it" to it }
        assertFailsWith<SqlValidationException> { validator.validateParameters(sql, muitos) }
    }

    // --- Consultas legitimas que NAO podem ser recusadas ---
    // Sem estes, "bloquear tudo" passaria nos testes acima.

    @Test
    fun `consultas legitimas continuam aceitas`() {
        aceito("SELECT 1 FROM DUMMY")
        aceito("SELECT * FROM VENDAS.OINV WHERE CARDCODE = :cliente")
        aceito("WITH t AS (SELECT A FROM VENDAS.P) SELECT * FROM t JOIN VENDAS.I i ON i.A = t.A")
        aceito("SELECT ROW_NUMBER() OVER (ORDER BY A) FROM VENDAS.C")
        aceito("SELECT A FROM VENDAS.P UNION ALL SELECT A FROM VENDAS.Q")
        aceito("SELECT COUNT(*), MAX(TOTAL) FROM VENDAS.P GROUP BY TIPO HAVING COUNT(*) > 1")
        aceito("SELECT * FROM VENDAS.OINV ORDER BY DOCENTRY DESC LIMIT 10")
        aceito("SELECT * FROM VENDAS.C", comAllowlist)
    }

    @Test
    fun `palavra proibida como dado literal nao bloqueia`() {
        // Falso positivo classico: recusar a consulta porque o DADO contem 'DROP'.
        aceito("SELECT * FROM VENDAS.C WHERE NOME = 'DROP TABLE'")
        aceito("SELECT * FROM VENDAS.C WHERE OBS = 'a;b'")
        aceito("SELECT * FROM VENDAS.C WHERE OBS = 'a--b'")
        aceito("SELECT * FROM VENDAS.C WHERE OBS = 'INSERT'")
    }

    @Test
    fun `normalizacao remove ponto e virgula final`() {
        assertEquals("SELECT 1 FROM DUMMY", validator.validate("  SELECT 1 FROM DUMMY ;  "))
    }
}
