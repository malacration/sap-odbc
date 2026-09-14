package br.andrew.odbc_sap_hana.sql

/** Erro de validacao da instrucao SQL enviada pelo cliente (resulta em HTTP 400). */
class SqlValidationException(message: String) : RuntimeException(message)
