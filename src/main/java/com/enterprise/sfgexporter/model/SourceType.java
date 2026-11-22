package com.enterprise.sfgexporter.model;

/**
 * Defines how an export job retrieves data from Oracle.
 */
public enum SourceType {
    /**
     * Executes an Oracle PL/SQL stored procedure returning a {@code SYS_REFCURSOR} OUT parameter.
     */
    STORED_PROCEDURE,

    /**
     * Executes a parameterized external SQL query file (e.g. {@code classpath:sql/my_query.sql}).
     */
    SQL_QUERY
}
