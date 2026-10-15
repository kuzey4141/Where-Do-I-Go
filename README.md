# Where Do I Go

A REST API built with Java 21 and Spring Boot for exploring entry requirements for Turkish passport holders.

## Requirements

- JDK 21 or later
- PostgreSQL 17, or Docker with the Compose plugin to run the included database service
- An internet connection to download dependencies on the first run

The project includes Maven Wrapper, so a separate Maven installation is not required.

## Run

If a local `.env` file does not exist, create one from the template:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

If you copied the template, replace its `DB_PASSWORD` placeholder with a local password
before starting the database. An existing generated `.env` is ready to use.
Both Docker Compose and Spring Boot read this file from the project directory.
Environment variables override the application values from `.env`.

Start PostgreSQL with Docker:

```powershell
docker compose up -d --wait postgres
```

Then start the application from the project directory.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Linux / macOS:

```sh
./mvnw spring-boot:run
```

The application runs at `http://localhost:8080` by default.
The root path `/` has no endpoint and returns `404`.

If PostgreSQL is already installed, create the database and login role manually
and set the matching credentials in `.env`. Docker is optional in this case.

## Database configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_NAME` | `where_do_i_go` | Database name |
| `DB_USER` | `where_do_i_go` | Database login |
| `DB_PASSWORD` | Required | Database password |
| `DB_PORT` | `5432` | Local PostgreSQL port |
| `DB_URL` | Built from `DB_PORT` and `DB_NAME` on localhost | Optional full JDBC URL for an existing or remote database |

The Compose service is bound to localhost and stores data in a named Docker volume.
`docker compose stop postgres` stops the service while preserving its data.
PostgreSQL's Docker initialization credentials apply when the volume is first created;
editing `.env` does not change credentials in an existing database.

Flyway creates and versions the schema automatically when the application starts:

- `V1__create_travel_catalog.sql`: Creates tables, constraints, indexes, and the four passport types.
- `V2__seed_countries.java`: Inserts the JDK ISO country and territory catalog with Turkish names once.

Add a new versioned migration for later schema changes instead of modifying migrations
that have already been applied. The application does not recreate tables on startup.

| Table | Contents |
|---|---|
| `countries` | ISO alpha-2 code and Turkish name |
| `passport_types` | Passport code, Turkish name, color, and display order |
| `entry_rules` | Country/passport rule, visa status, stay conditions, source URL, fetch and verification dates, and record timestamps |

Each country/passport pair can have only one current rule. Foreign keys prevent
orphan rules. Known visa statuses require a source URL and verification date.
The future importer must set `updated_at` when updating an existing rule; its
default only applies to inserts.

## Test and package

Tests use an isolated H2 database in PostgreSQL compatibility mode. They run the
same Flyway migrations and exercise API reads, filtering, and database constraints.
No local PostgreSQL installation or `.env` file is required for these tests.
H2 tests do not replace integration testing against a running PostgreSQL instance.

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
java -jar target/where-do-i-go-0.0.1-SNAPSHOT.jar
```

## Project structure

- `pom.xml`: Project metadata, dependencies, and build configuration.
- `src/main/java/com/wheredoigo/WhereDoIGoApplication.java`: Application entry point.
- `src/main/resources/application.properties`: Application configuration.
- `src/main/resources/db/migration/`: Versioned SQL migrations.
- `src/main/java/db/migration/`: Versioned Java migrations.
- `compose.yaml`: Local PostgreSQL service.
- `.env.example`: Local configuration template; `.env` is ignored by Git.
- `src/main/java/com/wheredoigo/catalog/`: API controller, service, and repository.
- `src/main/java/com/wheredoigo/passport/`: Passport types and response model.
- `src/main/java/com/wheredoigo/country/`: Country model.
- `src/main/java/com/wheredoigo/entryrule/`: Entry rule model and visa statuses.
- `src/main/java/com/wheredoigo/common/`: API error handling.
- `src/test/java/com/wheredoigo/`: Application startup, API, and filtering tests.

## Fetch the source page

To download the Ministry of Foreign Affairs visa page once, without starting the web server:

```powershell
java -jar target/where-do-i-go-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --sources.mfa.fetch-on-startup=true
```

Build the JAR first with `./mvnw package` (or `.\mvnw.cmd package` on Windows).
PostgreSQL must be available because this command starts the application context.
`MfaVisaPageClient` sends an HTTP GET request and returns the response as an HTML string.
The optional `MfaFetchRunner` logs the received character count. HTML stays in memory
and is not saved to a file.
Normal application startup does not fetch the source page.

The client uses a 10-second connection timeout, a 30-second request timeout,
and follows redirects. It rejects non-200 statuses, non-HTML responses, and empty content.
`sources.mfa.visa-url` can override the source URL for testing.
This step does not parse visa rules or write them to the database.

## Import entry rules

Fetch the live page, parse it, and save the results to PostgreSQL:

```powershell
java -jar target/where-do-i-go-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --sources.mfa.import-on-startup=true
```

Normal startup does not run the importer. HTML stays in memory throughout the import.
No LLM or external AI service is used.

`MfaVisaParser` uses Jsoup to isolate the page's content and split it at country
headings. Names are matched to the database catalog using normalized names and
explicit aliases. Each country explanation is then evaluated sentence by sentence
for passport groups, visa predicates, and duration expressions. Period limits such
as a stay within a longer time window are kept together.

The parser supports straightforward visa-required, visa-free, eVisa, and visa-on-arrival
wording. Ambiguous subjects, conflicting rules, age or official-delegation conditions,
extra electronic authorizations, and unsupported wording remain `UNKNOWN`. This is a
conservative rule-based parser, not a complete interpretation of travel regulations.
The original country explanation is retained in `conditions`, including exceptions.
The application does not infer visa rules from geography or neighboring countries.

For every matched country, the importer stores four passport records. It records the
actual configured source URL and `fetched_at`. For a recognized rule, `verified_at`
records the UTC date of automatic source parsing and rule validation; this does not
mean a person independently reviewed the rule. Unknown rules have no verification date.

`MfaEntryRuleWriter` updates existing country/passport pairs and inserts missing ones
in one transaction. A failed write rolls back the entire batch. A newly ambiguous
rule replaces the older classification with `UNKNOWN`. Unmatched destinations are
logged and are not silently mapped to another country. Existing rows for countries
absent from a later source response are left unchanged; this importer is not a full
deletion or historical tracking mechanism.

The import refuses to write when fewer than 100 countries are matched, to avoid
treating an error page or a major layout change as valid source data. The threshold
can be configured with `sources.mfa.minimum-countries` for controlled tests.

## API endpoints

| Request | Response |
|---|---|
| `GET /api/passport-types` | Four Turkish passport types with their names and colors |
| `GET /api/countries` | Countries and territories with ISO 3166-1 alpha-2 codes |
| `GET /api/entry-rules?passportType=ORDINARY` | Entry rules for the selected passport type |
| `GET /api/entry-rules?passportType=ORDINARY&visaStatus=VISA_FREE` | Entry rules filtered by passport type and visa status |
| `GET /api/countries/DE/entry-rules` | Entry rules for all four passport types for a destination |

`passportType` is required for `/api/entry-rules`. Accepted values:
`ORDINARY`, `SPECIAL`, `SERVICE`, `DIPLOMATIC`.

`visaStatus` is optional. Accepted values:
`VISA_FREE`, `VISA_REQUIRED`, `E_VISA`, `VISA_ON_ARRIVAL`, `CONDITIONAL`, `UNKNOWN`.

Enum values are case-sensitive and must be uppercase. Country codes accept
lowercase input; three-letter codes are not supported.

Missing or invalid parameters return `400`; unknown country codes return `404`.
Errors use `application/problem+json`. Filters with no matches return `200` and `[]`.

### Data status

Data is stored in PostgreSQL and queried through Spring JDBC. The country
and territory list is seeded from the JDK ISO catalog. Country names, passport
labels, and API messages remain in Turkish for the application's intended users.

Turkey is included in the country list but has no entry rules because domestic
travel is outside the scope of foreign entry requirements for Turkish passport holders.

Visa information can be loaded with the explicit import command above. Missing or
ambiguous country/passport rules return `UNKNOWN`. Missing rules have no stay limit,
source URL, or verification date. Imported ambiguous rules retain their source URL
and explanation but have no verification date. The `VISA_FREE` filter returns
recognized imported exemptions.
`UNKNOWN` indicates unverified information; it does not imply a visa requirement
or permission to enter without a visa.

### Entry rule response

Each entry rule contains the following fields:

| Field | Description |
|---|---|
| `country` | Destination object containing `isoCode` and a Turkish `name` |
| `passportType` | Passport type code, such as `ORDINARY` |
| `visaStatus` | Parsed entry requirement classification, or `UNKNOWN` |
| `stayLimit` | Extracted stay duration and any applicable period limit, if understood |
| `conditions` | Source explanation or a message indicating that a rule needs review, in Turkish |
| `sourceUrl` | Source supporting an imported entry rule |
| `verifiedAt` | Automatic rule validation date; absent for unknown rules |

`GET /api/countries/DE/entry-rules` returns four records, one per passport type.

## Architecture

`TravelCatalogController` handles HTTP requests, `TravelCatalogService` filters
rules and validates country codes, and `JdbcTravelCatalogRepository` implements
`TravelCatalogRepository` using PostgreSQL queries. Passport labels in the API
come from the `PassportType` enum; matching passport codes and labels are also
seeded in the database. Verified entry rules can be imported into `entry_rules`
in a later development step without changing the API endpoints.
