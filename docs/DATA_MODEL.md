# Data Model

No Media Compare application data model has been designed yet. This document records only the persistence choices and schema state that are currently confirmed.

## Confirmed Persistence Choices

- SQLite is the selected persistence layer.
- Spring JDBC is selected instead of JPA.
- Flyway owns schema migrations.
- The configured JDBC URL is `jdbc:sqlite:data/media-compare.db`.
- When the backend is started from `backend/`, the local development database is `backend/data/media-compare.db`.
- Local database files under `backend/data/` are ignored by Git; `backend/data/.gitkeep` is tracked to preserve the directory.
- Flyway migrations belong in `backend/src/main/resources/db/migration/` and are loaded from `classpath:db/migration`.

## Current Schema

There are no versioned migration scripts and no application tables. The current local schema is effectively empty except for Flyway's `flyway_schema_history` bookkeeping table.

## Data Model Decisions Still Needed

The deliberate data-model design pass still needs to address these broad topics:

- How a scan is represented.
- How files and media are identified.
- How comparison results are represented and retained.
- Whether and how jobs and history are persisted.
- Which discovered or derived metadata is persisted.

No tables, columns, relationships, identifiers, or retention rules have been decided yet.
