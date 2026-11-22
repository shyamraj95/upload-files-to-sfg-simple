-- Reference Oracle PL/SQL Package Specification & Body for the `daily_gl_journal_export` job.
-- Apply this in Oracle (schema: FIN_REPORTING) and grant EXECUTE to the read-only export user.

CREATE OR REPLACE PACKAGE FIN_REPORTING.PKG_SFG_EXPORTS AS
    PROCEDURE SP_EXPORT_DAILY_GL_JOURNALS (
        P_LOOKBACK_DAYS IN  NUMBER,
        P_LEDGER_CODE   IN  VARCHAR2,
        P_RESULT_CURSOR OUT SYS_REFCURSOR
    );
END PKG_SFG_EXPORTS;
/

CREATE OR REPLACE PACKAGE BODY FIN_REPORTING.PKG_SFG_EXPORTS AS
    PROCEDURE SP_EXPORT_DAILY_GL_JOURNALS (
        P_LOOKBACK_DAYS IN  NUMBER,
        P_LEDGER_CODE   IN  VARCHAR2,
        P_RESULT_CURSOR OUT SYS_REFCURSOR
    ) IS
    BEGIN
        OPEN P_RESULT_CURSOR FOR
            SELECT
                j.journal_id,
                j.ledger_code,
                j.account_number,
                j.cost_center,
                j.currency_code,
                j.debit_amount,
                j.credit_amount,
                TO_CHAR(j.posting_date, 'YYYY-MM-DD') AS posting_date
            FROM fin_reporting.gl_journals j
            WHERE j.ledger_code = P_LEDGER_CODE
              AND j.posting_date >= TRUNC(SYSDATE) - P_LOOKBACK_DAYS
              AND j.posting_date <  TRUNC(SYSDATE)
            ORDER BY j.posting_date, j.journal_id;
    END SP_EXPORT_DAILY_GL_JOURNALS;
END PKG_SFG_EXPORTS;
/
