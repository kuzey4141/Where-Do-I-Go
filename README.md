# Where Do I Go

A REST API built with Java 21 and Spring Boot for exploring entry requirements for Turkish passport holders.

## Requirements

- JDK 21 or later
- An internet connection to download dependencies on the first run

The project includes Maven Wrapper, so a separate Maven installation is not required.

## Run

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

## Test and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
java -jar target/where-do-i-go-0.0.1-SNAPSHOT.jar
```

## Project structure

- `pom.xml`: Project metadata, dependencies, and build configuration.
- `src/main/java/com/wheredoigo/WhereDoIGoApplication.java`: Application entry point.
- `src/main/resources/application.properties`: Application configuration.
- `src/main/java/com/wheredoigo/catalog/`: API controller, service, and repository.
- `src/main/java/com/wheredoigo/passport/`: Passport types and response model.
- `src/main/java/com/wheredoigo/country/`: Country model.
- `src/main/java/com/wheredoigo/entryrule/`: Entry rule model and visa statuses.
- `src/main/java/com/wheredoigo/common/`: API error handling.
- `src/test/java/com/wheredoigo/`: Application startup, API, and filtering tests.

## API

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

Data is currently stored in memory, with no database connection. The country
and territory list comes from the JDK ISO catalog. Country names, passport
labels, and API messages remain in Turkish for the application's intended users.

Turkey is included in the country list but has no entry rules because domestic
travel is outside the scope of foreign entry requirements for Turkish passport holders.

Actual visa information has not been loaded yet. Every foreign destination and
passport type returns `UNKNOWN`, with `null` values for `stayLimit`, `sourceUrl`,
and `verifiedAt`. As a result, the `VISA_FREE` filter currently returns an empty list.
`UNKNOWN` indicates unverified information; it does not imply a visa requirement
or permission to enter without a visa.

### Entry rule response

Each entry rule contains the following fields:

| Field | Description |
|---|---|
| `country` | Destination object containing `isoCode` and a Turkish `name` |
| `passportType` | Passport type code, such as `ORDINARY` |
| `visaStatus` | Entry requirement classification, currently `UNKNOWN` |
| `stayLimit` | Stay duration and any applicable period limit; currently `null` |
| `conditions` | Additional conditions or an unverified-data message in Turkish |
| `sourceUrl` | Source supporting the entry rule; currently `null` |
| `verifiedAt` | Verification date; currently `null` |

`GET /api/countries/DE/entry-rules` returns four records, one per passport type.

## Architecture

`TravelCatalogController` handles HTTP requests, `TravelCatalogService` filters
rules and validates country codes, and `TravelCatalogRepository` supplies data.
Verified entry rules or a database implementation can be added through the
repository in a later development step.
