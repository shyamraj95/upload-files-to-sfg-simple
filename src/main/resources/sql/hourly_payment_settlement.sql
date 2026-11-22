-- Parameterized Oracle SQL query executed by the `hourly_payment_settlement` job.
-- Parameters (`:lookback_hours`, `:settlement_status`) are bound safely via NamedParameterJdbcTemplate.
SELECT
    p.payment_id,
    p.batch_reference,
    p.value_date,
    p.currency_code,
    p.amount,
    p.settlement_status,
    p.updated_at_utc
FROM fin_reporting.payment_settlements p
WHERE p.settlement_status = :settlement_status
  AND p.updated_at_utc >= (SYSTIMESTAMP AT TIME ZONE 'UTC') - NUMTODSINTERVAL(:lookback_hours, 'HOUR')
ORDER BY p.updated_at_utc ASC, p.payment_id ASC
