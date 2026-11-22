SELECT id, account_code, amount, note
FROM test_transactions
WHERE status = :status
ORDER BY id ASC
