package br.andrew.odbc_sap_hana.web

import br.andrew.odbc_sap_hana.dto.QueryRequest
import br.andrew.odbc_sap_hana.dto.QueryResponse
import br.andrew.odbc_sap_hana.service.QueryService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class QueryController(private val service: QueryService) {

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
}
