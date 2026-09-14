package br.andrew.odbc_sap_hana

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Garante que o driver JDBC do SAP HANA esta no classpath e assume as URLs `jdbc:sap:`. */
class SapHanaDriverTest {

    @Test
    fun `driver do sap hana registrado`() {
        val driver = DriverManager.getDriver("jdbc:sap://hana.exemplo:30015/?databaseName=HXE")
        assertEquals("com.sap.db.jdbc.Driver", driver.javaClass.name)
        assertTrue(driver.acceptsURL("jdbc:sap://hana.exemplo:30015/"))
    }
}
