-- ============================================================
-- Usuario somente-leitura para a API  -  rode como SYSTEM
--
-- Nomes com prefixo para nao confundir os dois objetos:
--     ROLE_LEITURA_SBO  -> o PAPEL (nao tem senha, nao loga)
--     USR_API_LEITURA   -> o USUARIO (e este que vai no application-local.yaml)
--
-- Ajuste SBOGRUPOROVEMA se o schema for outro.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Diagnostico: o que ja existe?
-- ------------------------------------------------------------
SELECT 'USUARIO' AS TIPO, USER_NAME AS NOME FROM SYS.USERS
 WHERE USER_NAME IN ('USR_API_LEITURA','LEITURA_SBODEMOBR','API_LEITURA')
UNION ALL
SELECT 'PAPEL', ROLE_NAME FROM SYS.ROLES
 WHERE ROLE_NAME IN ('ROLE_LEITURA_SBO','LEITURA_SBODEMOBR');

-- ------------------------------------------------------------
-- 2. O papel e o privilegio de leitura
--    ON SCHEMA cobre tambem tabelas criadas depois deste GRANT.
-- ------------------------------------------------------------
CREATE ROLE ROLE_LEITURA_SBO;
GRANT SELECT ON SCHEMA SBOGRUPOROVEMA TO ROLE_LEITURA_SBO;

-- ------------------------------------------------------------
-- 3. O usuario
--    NO FORCE_FIRST_PASSWORD_CHANGE e obrigatorio em conta de
--    servico: sem isso o primeiro login exige troca interativa.
--    Troque a senha abaixo.
-- ------------------------------------------------------------
CREATE RESTRICTED USER USR_API_LEITURA
    PASSWORD "Api2Leitura9Sbo"
    NO FORCE_FIRST_PASSWORD_CHANGE;

-- Restricted user nao abre conexao sem isto:
GRANT RESTRICTED_USER_JDBC_ACCESS TO USR_API_LEITURA;

GRANT ROLE_LEITURA_SBO TO USR_API_LEITURA;

-- Senha de servico nao pode expirar sozinha (padrao: 182 dias).
ALTER USER USR_API_LEITURA DISABLE PASSWORD LIFETIME;

-- ------------------------------------------------------------
-- 4. Conferencia - deve listar o papel e o privilegio SELECT
-- ------------------------------------------------------------
SELECT * FROM SYS.GRANTED_ROLES WHERE GRANTEE = 'USR_API_LEITURA';

SELECT PRIVILEGE, OBJECT_TYPE, SCHEMA_NAME, OBJECT_NAME
FROM   SYS.EFFECTIVE_PRIVILEGES
WHERE  USER_NAME = 'USR_API_LEITURA'
ORDER  BY SCHEMA_NAME;

-- ============================================================
-- Se o usuario JA EXISTIR, nao recrie - conserte:
-- ============================================================
-- ALTER USER USR_API_LEITURA RESET CONNECT ATTEMPTS;   -- destrava
-- ALTER USER USR_API_LEITURA ACTIVATE;                 -- reativa
-- ALTER USER USR_API_LEITURA PASSWORD "Api2Leitura9Sbo" NO FORCE_FIRST_PASSWORD_CHANGE;
-- ALTER USER USR_API_LEITURA DISABLE PASSWORD LIFETIME;
-- GRANT RESTRICTED_USER_JDBC_ACCESS TO USR_API_LEITURA;
-- GRANT ROLE_LEITURA_SBO TO USR_API_LEITURA;
