package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTravelCatalogRepository implements TravelCatalogRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTravelCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Country> countries() {
        return jdbcTemplate.query("SELECT iso_code, name FROM countries ORDER BY iso_code",
                (row, index) -> new Country(row.getString("iso_code"), row.getString("name")));
    }

    @Override
    public List<EntryRule> entryRules() {
        return jdbcTemplate.query("""
                SELECT c.iso_code, c.name, p.code AS passport_type,
                       r.visa_status, r.stay_limit, r.conditions, r.source_url, r.verified_at
                FROM countries c
                CROSS JOIN passport_types p
                LEFT JOIN entry_rules r
                    ON r.country_iso_code = c.iso_code AND r.passport_type = p.code
                WHERE c.iso_code <> 'TR'
                ORDER BY c.iso_code, p.display_order
                """, (row, index) -> {
                    Country country = new Country(row.getString("iso_code"), row.getString("name"));
                    PassportType passportType = PassportType.valueOf(row.getString("passport_type"));
                    String visaStatus = row.getString("visa_status");
                    if (visaStatus == null) {
                        return EntryRule.unverified(country, passportType);
                    }
                    return new EntryRule(country, passportType, VisaStatus.valueOf(visaStatus),
                            row.getString("stay_limit"), row.getString("conditions"),
                            row.getString("source_url"), row.getObject("verified_at", LocalDate.class));
                });
    }
}
