package com.wheredoigo.catalog;

import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TravelCatalogDatabaseTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Flyway flyway;

    @Test
    void migrationsSeedCatalogAndDoNotRunAgain() {
        Integer countryCount = jdbc.queryForObject("SELECT COUNT(*) FROM countries", Integer.class);
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM passport_types", Integer.class));
        assertEquals("Almanya", jdbc.queryForObject(
                "SELECT name FROM countries WHERE iso_code = 'DE'", String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(countryCount, jdbc.queryForObject("SELECT COUNT(*) FROM countries", Integer.class));
    }

    @Test
    void apiReadsPersistedRulesAndFiltersTheirStatus() throws Exception {
        // Synthetic test data; this is not an actual visa rule for Germany.
        jdbc.update("""
                INSERT INTO entry_rules
                    (country_iso_code, passport_type, visa_status, stay_limit, conditions, source_url, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, "DE", "ORDINARY", "VISA_FREE", "Test stay limit", "Test condition",
                "https://example.test/entry-rules", LocalDate.of(2026, 1, 1));

        mvc.perform(get("/api/entry-rules")
                        .param("passportType", "ORDINARY").param("visaStatus", "VISA_FREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].country.isoCode").value("DE"))
                .andExpect(jsonPath("$[0].stayLimit").value("Test stay limit"))
                .andExpect(jsonPath("$[0].verifiedAt").value("2026-01-01"))
                .andExpect(jsonPath("$[0].sourceUrl").value("https://example.test/entry-rules"));

        mvc.perform(get("/api/countries/DE/entry-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].visaStatus").value("VISA_FREE"))
                .andExpect(jsonPath("$[1].visaStatus").value("UNKNOWN"));
    }

    @Test
    void rejectsDuplicateCountryPassportRules() {
        jdbc.update("""
                INSERT INTO entry_rules (country_iso_code, passport_type, visa_status)
                VALUES ('DE', 'ORDINARY', 'UNKNOWN')
                """);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO entry_rules (country_iso_code, passport_type, visa_status)
                VALUES ('DE', 'ORDINARY', 'UNKNOWN')
                """));
    }

    @Test
    void rejectsRulesForUnknownCountries() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO entry_rules (country_iso_code, passport_type, visa_status)
                VALUES ('ZZ', 'ORDINARY', 'UNKNOWN')
                """));
    }

    @Test
    void rejectsInvalidVisaStatuses() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO entry_rules (country_iso_code, passport_type, visa_status)
                VALUES ('DE', 'ORDINARY', 'INVALID')
                """));
    }

    @Test
    void requiresSourceAndVerificationDateForKnownRules() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO entry_rules (country_iso_code, passport_type, visa_status)
                VALUES ('DE', 'ORDINARY', 'VISA_FREE')
                """));
    }
}
