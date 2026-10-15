package com.wheredoigo.source;

import com.wheredoigo.entryrule.EntryRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MfaEntryRuleWriter {
    private final JdbcTemplate jdbc;

    public MfaEntryRuleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(List<EntryRule> rules, Instant fetchedAt) {
        for (EntryRule rule : rules) {
            Object[] values = {rule.visaStatus().name(), rule.stayLimit(), rule.conditions(),
                    rule.sourceUrl(), Timestamp.from(fetchedAt), rule.verifiedAt(),
                    rule.country().isoCode(), rule.passportType().name()};
            int updated = jdbc.update("""
                    UPDATE entry_rules SET visa_status = ?, stay_limit = ?, conditions = ?,
                        source_url = ?, fetched_at = ?, verified_at = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE country_iso_code = ? AND passport_type = ?
                    """, values);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO entry_rules (visa_status, stay_limit, conditions, source_url,
                            fetched_at, verified_at, country_iso_code, passport_type)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, values);
            }
        }
    }
}
