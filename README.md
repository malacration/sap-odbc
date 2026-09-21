# odbc-sap-hana

API REST que conecta em uma base SAP (HANA), executa **somente consultas de leitura**
e devolve o resultado em JSON.

## Como a segurança é feita

A proteção é em camadas — cada uma sozinha é insuficiente, juntas cobrem os casos práticos:

| Camada | Onde | O que impede |
| --- | --- | --- |
| Parâmetros como *bind variables* | `QueryService` (PreparedStatement) | SQL injection por dado: o valor nunca vira SQL |
| Validação sintática real (JSqlParser) | `ReadOnlySqlValidator` | Qualquer instrução que não seja `SELECT`/`WITH` |
| Instrução única | `ReadOnlySqlValidator` | *Stacked queries* (`...; DROP TABLE ...`) |
| Proibição de comentários | `ReadOnlySqlValidator` | Ofuscação de trecho da query (`--`, `/* */`) |
| Lista de palavras proibidas | `query.denied-keywords` | `INSERT`, `UPDATE`, `CALL`, `GRANT`, `INTO`, … (fora de literais) |
| Lista de schemas | `query.denied-schemas` / `allowed-schemas` | Acesso a `SYS`, `_SYS_*`, `SYSTEM` ou fora do domínio permitido |
| Schema obrigatório sob allowlist | `ReadOnlySqlValidator` | Contornar a allowlist omitindo o schema (`FROM OINV`) |
| Limites de execução | `query.max-rows`, `timeout-seconds` | Consulta que derruba o banco ou a aplicação |
| Conexão *read-only* | `spring.datasource.hikari.read-only` | Escrita acidental no nível do driver |
| **Usuário de banco somente-leitura** | SAP HANA | A garantia final — configure e não abra exceção |

> A validação da aplicação é a primeira barreira. A última é o privilégio do usuário
> no HANA: crie um usuário com `SELECT` apenas nos schemas necessários.
> Passo a passo em **[docs/usuario-somente-leitura.md](docs/usuario-somente-leitura.md)**.

## Configuração

Variáveis de ambiente (ver `src/main/resources/application.yaml`):

```bash
export SAP_JDBC_URL="jdbc:sap://meu-hana:30015/?databaseName=HXE&encrypt=true"
export SAP_DB_USER="LEITURA"
export SAP_DB_PASSWORD="..."
export API_KEY="chave-para-o-header-X-API-Key"   # opcional; vazio desativa a checagem
```

Ajustes de política ficam no bloco `query:` do `application.yaml`
(`max-rows`, `timeout-seconds`, `denied-schemas`, `allowed-schemas`, `denied-keywords`).

## Executar

```bash
./gradlew bootRun
./gradlew test
```

## Uso

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: minha-chave' \
  -d '{
        "sql": "SELECT DocEntry, CardCode, DocTotal FROM SBODEMOBR.OINV WHERE CardCode = :cliente",
        "params": { "cliente": "C0001" },
        "maxRows": 500,
        "timeoutSeconds": 30
      }'
```

Resposta:

```json
{
  "columns": [
    { "name": "DocEntry", "type": "INTEGER", "nullable": false },
    { "name": "CardCode", "type": "NVARCHAR", "nullable": true }
  ],
  "rows": [ { "DocEntry": 1, "CardCode": "C0001" } ],
  "rowCount": 1,
  "truncated": false,
  "elapsedMs": 42
}
```

### Regras para o campo `sql`

- Deve começar com `SELECT` ou `WITH`; `JOIN`, `UNION`, subquery e CTE são aceitos.
- Uma instrução por requisição (`;` só é aceito no final).
- Sem comentários.
- **Todo valor variável vai em `params`**, referenciado como `:nome` no SQL.
  Nomes de parâmetros precisam casar exatamente com os usados na consulta —
  parâmetro faltando ou sobrando é erro 400. `:nome` dentro de literal de texto
  (`'chave:valor'`) e cast `a::int` não contam como parâmetro.
- Com `query.allowed-schemas` preenchida, **toda tabela precisa vir qualificada**
  (`VENDAS.OINV`, não `OINV`): sem schema a resolução depende do schema corrente
  da conexão, o que permitiria escapar da lista. Nomes de CTE seguem sem schema.
- Nunca concatene valor do usuário no texto do SQL: isso reintroduz injection
  na sua aplicação, antes mesmo de chegar nesta API.

### Erros

| HTTP | `erro` | Quando |
| --- | --- | --- |
| 400 | `sql_invalido` | Reprovado pela validação read-only |
| 400 | `sql_rejeitado_pelo_banco` | Sintaxe/objeto inválido para o HANA |
| 401 | `nao_autorizado` | `X-API-Key` ausente ou incorreta |
| 504 | `timeout` | Consulta excedeu `timeoutSeconds` |
| 502 | `erro_banco` | Falha de conexão/execução no SAP |
| 404 | `rota_nao_encontrada` | URL inexistente |
| 405 | `metodo_nao_permitido` | Método HTTP errado |
| 415 | `formato_nao_suportado` | Falta `Content-Type: application/json` |

> **Todo** erro responde JSON no envelope `{erro, mensagem, sqlState?}` — inclusive
> 404 e 405, que antes caíam na página HTML do Tomcat e quebravam o cliente no parse.
> O mesmo envelope é usado pelo `sap-reports` e pelo painel de vendas do `sap-rovema`.

## Estrutura

```
sql/ReadOnlySqlValidator.kt   validação em camadas da instrução
service/QueryService.kt       execução com bind params, limites e mapeamento para JSON
web/QueryController.kt        POST /api/v1/query
web/ApiExceptionHandler.kt    tradução de exceções para JSON (sem vazar stacktrace)
security/ApiKeyFilter.kt      autenticação opcional por X-API-Key
config/QueryProperties.kt     limites e listas configuráveis
```

## ODBC em vez de JDBC

No JVM o driver nativo do HANA (`ngdbc`) é o equivalente direto do driver ODBC
`HDBODBC` — mesma base, mesma autenticação. Para uma base SAP exposta apenas via
DSN ODBC, troque `spring.datasource.url`/`driver-class-name` por uma ponte
JDBC-ODBC; nenhuma outra parte do projeto muda.
