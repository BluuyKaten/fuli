package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantRankingSchemaTest {

    @Test
    void shouldExposeQuantRankingTablesAndUniqueTradingDate() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:schema_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        jdbc.execute("RUNSCRIPT FROM 'classpath:schema-h2.sql'");

        for (String table : new String[]{"quant_rank_snapshot", "quant_rank_item",
                "quant_notification_config", "quant_notification_log"}) {
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME = ?", Integer.class, table.toUpperCase()) > 0, table);
        }
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME='QUANT_RANK_SNAPSHOT' AND CONSTRAINT_TYPE='UNIQUE' "
                + "AND CONSTRAINT_NAME LIKE '%USER%DATE%'", Integer.class) > 0);
    }
}
