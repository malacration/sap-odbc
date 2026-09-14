# Usuário somente-leitura no SAP HANA

> **Atenção aos nomes.** Neste documento `ROLE_*` é **papel** e `USR_*` é **usuário**.
> Papel não tem senha e não faz login — se você tentar conectar com o nome do papel,
> o HANA responde `[10]: authentication failed`.
> Script pronto e idempotente: **[setup-usuario.sql](setup-usuario.sql)**.

A validação da aplicação (`ReadOnlySqlValidator`) é a **primeira** barreira.
Esta é a **última**: se o usuário do banco não tiver privilégio de escrita,
nenhuma falha na aplicação vira perda de dados.

Trate as duas como independentes. Não abra exceção aqui "porque a API já valida".

> Execute o script abaixo com um usuário administrativo (ex.: `SYSTEM`, ou um
> usuário com `USER ADMIN` e `ROLE ADMIN`).

---

## 1. Por que usuário *restricted*

O HANA tem dois tipos de usuário:

| Tipo | Papel `PUBLIC` | Conexão JDBC/ODBC | Cria objetos no próprio schema |
| --- | --- | --- | --- |
| `CREATE USER` (padrão) | recebe automaticamente | liberada | sim |
| `CREATE RESTRICTED USER` | **não recebe** | só com role explícita | não |

O papel `PUBLIC` dá leitura em uma porção grande de views de sistema (`SYS.*`).
Para uma API que só precisa ler um schema de negócio, isso é privilégio a mais.
**Use `RESTRICTED USER`.**

---

## 2. Script de criação

Ajuste os três nomes no topo e execute na ordem.

```sql
-- ============================================================
-- Ajuste aqui
--   SBODEMOBR   -> o schema que a API pode ler
--   USR_API_LEITURA -> o usuário da aplicação
--   ROLE_LEITURA_SBO -> o papel que carrega o privilégio
-- ============================================================

-- 2.1 O papel. Todo privilégio vai no papel, nunca direto no usuário:
--     isso permite trocar o usuário sem reconstruir as permissões.
CREATE ROLE ROLE_LEITURA_SBO;

-- 2.2 SELECT no schema inteiro.
--     No nível de SCHEMA, o privilégio vale também para tabelas e views
--     criadas DEPOIS deste GRANT. Concedido tabela a tabela, não valeria -
--     e toda tabela nova ficaria invisível para a API.
GRANT SELECT ON SCHEMA SBODEMOBR TO ROLE_LEITURA_SBO;

-- 2.3 O usuário. Restricted: nasce sem PUBLIC e sem acesso a views de sistema.
--     NO FORCE_FIRST_PASSWORD_CHANGE é obrigatório para conta de serviço -
--     sem isso o primeiro login exige troca interativa e a API não sobe.
CREATE RESTRICTED USER USR_API_LEITURA
    PASSWORD "TrocarEsta1Senha"
    NO FORCE_FIRST_PASSWORD_CHANGE;

-- 2.4 Um restricted user não consegue nem abrir conexão sem isto.
--     Conceda APENAS o driver que você usa de fato:
GRANT RESTRICTED_USER_JDBC_ACCESS TO USR_API_LEITURA;   -- ngdbc / JDBC
-- GRANT RESTRICTED_USER_ODBC_ACCESS TO USR_API_LEITURA; -- somente se usar ODBC

-- 2.5 Liga o usuário ao papel de leitura.
GRANT ROLE_LEITURA_SBO TO USR_API_LEITURA;

-- 2.6 Senha de conta de serviço não pode expirar sozinha,
--     ou a API cai sem aviso quando o prazo (padrão: 182 dias) vencer.
--     A rotação passa a ser responsabilidade sua - veja a seção 6.
ALTER USER USR_API_LEITURA DISABLE PASSWORD LIFETIME;
```

### O que deliberadamente NÃO é concedido

| Privilégio | Por que fica de fora |
| --- | --- |
| `INSERT`, `UPDATE`, `DELETE` | é o ponto do exercício |
| `EXECUTE` | procedure pode escrever por dentro, driblando a leitura |
| `CREATE ANY` | permitiria criar objeto no schema |
| privilégio em `SYS` / `_SYS_*` | metadados e conteúdo de sistema |
| `WITH GRANT OPTION` | impede o usuário de repassar o acesso adiante |

---

## 3. Verificação — prove que é somente-leitura

Não confie no script: teste. **Conecte como `USR_API_LEITURA`** e rode:

```sql
-- Deve FUNCIONAR:
SELECT COUNT(*) FROM SBODEMOBR.OINV;

-- Todos os seguintes devem falhar com:
--   SAP DBTech JDBC: [258]: insufficient privilege
INSERT INTO SBODEMOBR.OINV (DocEntry) VALUES (999999);
UPDATE SBODEMOBR.OINV SET DocTotal = 0;
DELETE FROM SBODEMOBR.OINV;
DROP TABLE SBODEMOBR.OINV;
CREATE TABLE SBODEMOBR.TESTE (A INT);
SELECT * FROM SYS.USERS;
```

Se qualquer um dos que deveriam falhar **funcionar**, pare e revise os grants
antes de subir a API.

Para auditar o que o usuário realmente tem, rode como administrador:

```sql
-- Papéis do usuário
SELECT * FROM SYS.GRANTED_ROLES WHERE GRANTEE = 'USR_API_LEITURA';

-- Privilégios efetivos (o que vale na prática, já resolvendo os papéis)
SELECT PRIVILEGE, OBJECT_TYPE, SCHEMA_NAME, OBJECT_NAME, IS_VALID
FROM   SYS.EFFECTIVE_PRIVILEGES
WHERE  USER_NAME = 'USR_API_LEITURA'
ORDER  BY SCHEMA_NAME, OBJECT_NAME;
```

O resultado esperado é curto. Se aparecer `INSERT`/`UPDATE`/`DELETE`/`EXECUTE`,
ou schema fora do previsto, há privilégio herdado de algum papel a mais.

---

## 4. Ligando na aplicação

```bash
export SAP_JDBC_URL="jdbc:sap://meu-hana:30015/?databaseName=HXE&encrypt=true"
export SAP_DB_USER="USR_API_LEITURA"
export SAP_DB_PASSWORD="TrocarEsta1Senha"
export API_KEY="chave-do-header-X-API-Key"
```

E feche o cerco também na aplicação, em `application.yaml`:

```yaml
query:
  allowed-schemas:
    - SBODEMOBR      # precisa bater com o schema do GRANT acima
```

> **Atenção:** com `allowed-schemas` preenchida, a API passa a exigir que toda
> tabela venha qualificada — `SELECT * FROM SBODEMOBR.OINV`, não `FROM OINV`.
> Isso é proposital: sem schema, a resolução dependeria do schema corrente da
> conexão, e bastaria omitir o nome para escapar da lista.

Se preferir aceitar nomes não qualificados, defina o schema corrente na URL
(`?currentSchema=SBODEMOBR`) e **deixe `allowed-schemas` vazia**, confiando o
isolamento aos privilégios do usuário.

---

## 5. Views entre schemas (caso comum em SAP Business One)

Se uma view em `SBODEMOBR` lê tabelas de outro schema, o `GRANT SELECT ON SCHEMA
SBODEMOBR` sozinho pode não bastar — o acesso depende de como a view foi criada
e de quem a possui. Sintoma: a consulta falha com *insufficient privilege*
apontando um objeto que você não citou na query.

Nesse caso, conceda o mínimo adicional **no papel**, nunca no usuário:

```sql
GRANT SELECT ON SCHEMA OUTRO_SCHEMA TO ROLE_LEITURA_SBO;
```

E acrescente o schema em `query.allowed-schemas`.

---

## 6. Manutenção

**Rotação de senha** (o `DISABLE PASSWORD LIFETIME` tirou a expiração automática,
então programe a troca):

```sql
ALTER USER USR_API_LEITURA PASSWORD "NovaSenha2Forte" NO FORCE_FIRST_PASSWORD_CHANGE;
```

Atualize `SAP_DB_PASSWORD` e reinicie a aplicação.

**Desativar sem apagar** (útil em incidente — preserva os grants):

```sql
ALTER USER USR_API_LEITURA DEACTIVATE;
-- reverter:
ALTER USER USR_API_LEITURA ACTIVATE;
```

**Remover de vez:**

```sql
DROP USER USR_API_LEITURA;
DROP ROLE ROLE_LEITURA_SBO;
```

---

## 7. Erros comuns e o que significam

| Mensagem | Causa provável |
| --- | --- |
| `[258]: insufficient privilege` | falta o `GRANT SELECT ON SCHEMA`, ou o papel não foi concedido ao usuário |
| `[10]: authentication failed` | senha errada, ou usuário desativado/bloqueado |
| `[414]: user is forced to change password` | criado sem `NO FORCE_FIRST_PASSWORD_CHANGE` |
| `[332]: password has expired` | faltou `DISABLE PASSWORD LIFETIME` |
| conexão recusada logo após criar o usuário | restricted user sem `RESTRICTED_USER_JDBC_ACCESS` |
| `[663]: user not allowed to connect from client` | senha OK, mas a interface de conexão não está liberada para o restricted user — veja [diagnostico-663.sql](diagnostico-663.sql) |
| senha recusada no `CREATE USER` | política padrão: mín. 8 caracteres, com maiúscula, minúscula e dígito, e não pode começar com dígito |

---

## Checklist

- [ ] Usuário criado como `RESTRICTED USER`
- [ ] Privilégios em um **papel**, não direto no usuário
- [ ] `GRANT SELECT ON SCHEMA` (não tabela a tabela)
- [ ] Nenhum `EXECUTE`, `INSERT`, `UPDATE`, `DELETE`, `WITH GRANT OPTION`
- [ ] Apenas `RESTRICTED_USER_JDBC_ACCESS` (ODBC só se for usado)
- [ ] `NO FORCE_FIRST_PASSWORD_CHANGE` + `DISABLE PASSWORD LIFETIME`
- [ ] Testes de escrita da seção 3 falharam com `258`
- [ ] `SYS.EFFECTIVE_PRIVILEGES` auditado
- [ ] `allowed-schemas` da aplicação bate com o schema concedido
- [ ] Senha em variável de ambiente / cofre, fora do código
