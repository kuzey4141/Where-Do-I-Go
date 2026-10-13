package db.migration;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2__seed_countries extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Locale turkish = Locale.forLanguageTag("tr");
        String[] codes = Locale.getISOCountries();
        Arrays.sort(codes);
        try (PreparedStatement statement = context.getConnection()
                .prepareStatement("INSERT INTO countries (iso_code, name) VALUES (?, ?)")) {
            for (String code : codes) {
                String name = new Locale.Builder().setRegion(code).build().getDisplayCountry(turkish);
                statement.setString(1, code);
                statement.setString(2, name);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
