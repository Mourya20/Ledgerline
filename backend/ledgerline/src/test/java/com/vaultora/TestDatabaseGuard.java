package com.vaultora;

import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabaseGuard {

    public static final String EXPECTED_TEST_DB = "ledgerline_test_db";

    private TestDatabaseGuard() {
    }

    public static void assertTestDatabase(JdbcTemplate jdbcTemplate) {
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        if (!EXPECTED_TEST_DB.equalsIgnoreCase(currentDb)) {
            throw new IllegalStateException(
                    "SAFETY ABORT: Test execution or cleanup attempted against non-test database: '" + currentDb
                            + "'. Aborting to protect application data.");
        }
    }
}
