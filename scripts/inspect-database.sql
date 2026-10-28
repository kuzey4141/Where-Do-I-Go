-- Read-only inspection. Run with psql -X -v ON_ERROR_STOP=1 -f scripts/inspect-database.sql
\pset pager off
BEGIN TRANSACTION READ ONLY;

-- Tables and columns actually present in PostgreSQL.
SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;

SELECT version, description, type, success
FROM flyway_schema_history ORDER BY installed_rank;

SELECT 'countries' AS table_name, count(*) AS records FROM countries
UNION ALL SELECT 'passport_types', count(*) FROM passport_types
UNION ALL SELECT 'entry_rules', count(*) FROM entry_rules;

SELECT * FROM passport_types ORDER BY display_order;

-- Missing rows and explicitly imported UNKNOWN rows are different.
SELECT p.code AS passport_type,
       count(*) AS expected_destinations,
       count(r.id) AS stored,
       count(*) FILTER (WHERE r.id IS NULL) AS missing,
       count(*) FILTER (WHERE r.visa_status = 'UNKNOWN') AS imported_unknown,
       count(*) FILTER (WHERE r.visa_status <> 'UNKNOWN') AS classified
FROM countries c CROSS JOIN passport_types p
LEFT JOIN entry_rules r ON r.country_iso_code = c.iso_code AND r.passport_type = p.code
WHERE c.iso_code <> 'TR'
GROUP BY p.code, p.display_order ORDER BY p.display_order;

SELECT passport_type, visa_status, count(*) AS records
FROM entry_rules GROUP BY passport_type, visa_status ORDER BY passport_type, visa_status;

SELECT c.iso_code, c.name
FROM countries c WHERE c.iso_code <> 'TR'
AND NOT EXISTS (SELECT 1 FROM entry_rules r WHERE r.country_iso_code = c.iso_code)
ORDER BY c.iso_code;

SELECT country_iso_code, count(*) AS passport_records
FROM entry_rules GROUP BY country_iso_code HAVING count(*) <> 4;

SELECT count(*) FILTER (WHERE visa_status <> 'UNKNOWN' AND
          (NULLIF(btrim(source_url), '') IS NULL OR verified_at IS NULL)) AS classified_without_provenance,
       count(*) FILTER (WHERE visa_status = 'VISA_FREE' AND NULLIF(btrim(stay_limit), '') IS NULL) AS visa_free_without_duration,
       min(fetched_at) AS oldest_fetch, max(fetched_at) AS newest_fetch
FROM entry_rules;

SELECT r.country_iso_code, c.name, r.passport_type, r.visa_status,
       r.stay_limit, r.conditions, r.source_url, r.verified_at
FROM entry_rules r JOIN countries c ON c.iso_code = r.country_iso_code
WHERE r.country_iso_code IN ('DE', 'JP', 'GE', 'AZ', 'US', 'KR')
ORDER BY r.country_iso_code, r.passport_type;

COMMIT;
