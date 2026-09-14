-- ============================================================
-- [663]: user not allowed to connect from client
--
-- Ja descartado por teste empirico:
--   - nao e porta         (30015 responde; 30013 e outro banco)
--   - nao e senha         (o erro anterior era [10]; agora passa da autenticacao)
--   - nao e criptografia  (mesmo erro com e sem encrypt=true)
--   - nao e a interface   (JDBC e ODBC access ja concedidos)
--
-- Rode no TENANT (a mesma conexao onde API_LEITURA existe),
-- como SYSTEM ou B1ADMIN.
-- ============================================================

-- ------------------------------------------------------------
-- 1. COMPARACAO - o diagnostico mais util.
--    Troque 'B1ADMIN' por um usuario que conecta normalmente.
--    A diferenca entre as duas linhas e a causa.
-- ------------------------------------------------------------
SELECT USER_NAME, USER_MODE, IS_VALID, USER_DEACTIVATED,
       INVALID_CONNECT_ATTEMPTS, PASSWORD_CHANGE_NEEDED,
       IS_PASSWORD_ENABLED, IS_KERBEROS_ENABLED, IS_SAML_ENABLED,
       IS_X509_ENABLED, IS_SAP_LOGON_TICKET_ENABLED,
       IS_RESTRICTED, VALID_FROM, VALID_UNTIL
FROM   SYS.USERS
WHERE  USER_NAME IN ('API_LEITURA', 'B1ADMIN');

-- ------------------------------------------------------------
-- 2. Parametros do usuario - e aqui que costuma morar uma
--    restricao de cliente (CLIENT, PRIORITY, etc.)
-- ------------------------------------------------------------
SELECT * FROM SYS.USER_PARAMETERS
WHERE  USER_NAME IN ('API_LEITURA', 'B1ADMIN');

-- ------------------------------------------------------------
-- 3. Papeis lado a lado
-- ------------------------------------------------------------
SELECT GRANTEE, ROLE_NAME FROM SYS.GRANTED_ROLES
WHERE  GRANTEE IN ('API_LEITURA', 'B1ADMIN')
ORDER  BY GRANTEE, ROLE_NAME;

-- ------------------------------------------------------------
-- 4. Existe restricao de conexao configurada no servidor?
-- ------------------------------------------------------------
SELECT FILE_NAME, SECTION, KEY, VALUE
FROM   SYS.M_INIFILE_CONTENTS
WHERE  LOWER(KEY) LIKE '%client%'
   OR  LOWER(KEY) LIKE '%connect%'
   OR  LOWER(SECTION) LIKE '%authentic%';

-- ------------------------------------------------------------
-- 5. Qual e o nome real do tenant? (para a URL, se precisar)
-- ------------------------------------------------------------
SELECT DATABASE_NAME FROM SYS.M_DATABASE;
