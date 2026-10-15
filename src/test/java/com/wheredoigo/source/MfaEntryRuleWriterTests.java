package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class MfaEntryRuleWriterTests {
    @Autowired
    private MfaEntryRuleWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void updatesExistingRulesWithoutDuplicatesAndCanDowngradeToUnknown() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            EntryRule first = new EntryRule(new Country("DE", "Almanya"), PassportType.ORDINARY,
                    VisaStatus.VISA_REQUIRED, null, "Synthetic fixture", "https://example.test/visa",
                    LocalDate.of(2026, 1, 1));
            writer.save(List.of(first), Instant.parse("2026-01-01T12:00:00Z"));
            writer.save(List.of(first), Instant.parse("2026-01-02T12:00:00Z"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM entry_rules", Integer.class));
            EntryRule uncertain = new EntryRule(first.country(), first.passportType(), VisaStatus.UNKNOWN,
                    null, "Ambiguous source", first.sourceUrl(), null);
            writer.save(List.of(uncertain), Instant.parse("2026-01-03T12:00:00Z"));
            assertEquals("UNKNOWN", jdbc.queryForObject("SELECT visa_status FROM entry_rules", String.class));
            assertNull(jdbc.queryForObject("SELECT verified_at FROM entry_rules", LocalDate.class));
            transaction.setRollbackOnly();
        });
    }

    @Test
    void rollsBackTheEntireImportWhenAnyRecordIsInvalid() {
        EntryRule valid = EntryRule.unverified(new Country("DE", "Almanya"), PassportType.ORDINARY);
        EntryRule invalid = EntryRule.unverified(new Country("ZZ", "Invalid"), PassportType.ORDINARY);
        assertThrows(DataIntegrityViolationException.class,
                () -> writer.save(List.of(valid, invalid), Instant.now()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM entry_rules", Integer.class));
    }
}
