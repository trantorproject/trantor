# trantor-data

Access to relational databases: a JDBC `DataSource` on HikariCP, the `TransactionManager` of
[trantor-core](trantor-core.md#transactions) on top of it, jOOQ, and the translation of the errors of the database
into errors an application can tell apart.

---

## Registration

```kotlin
services.addJooq()
```

`addJooq()` pulls in `addJdbc()`, which registers the `JdbcSettings`, a pooled `DataSource` and a
`TransactionManager` that keeps the open transaction per thread. Each is registered only if missing, so an
application can put its own first.

| Function | What it registers |
|---|---|
| `addJdbc()` | `JdbcSettings`, a HikariCP `DataSource` (10 connections, adjustable with `addHikariCP { hikari, _ -> }`) and a thread-local `TransactionManager` |
| `addSimpleJdbc()` | The same without a pool: every connection opens anew, and one transaction is shared by every thread. For tools and tests, not for a server |
| `addJooq()` | A `DSLContext` on that `DataSource`, whose transactions are the ones of the `TransactionManager` |

They take a `key` to register a second database under it, with its JDBC settings in the `jdbc.<key>` section.

`TransactionalMiddleware`, registered as a middleware of the application, runs each request in a transaction of
that `TransactionManager`.

---

## Settings

```json
{
    "jdbc": { "url": "jdbc:postgresql://localhost:5432/orders", "username": "orders", "password": "${DB_PASSWORD}" },
    "jooq": { "optimisticLocking": true }
}
```

| `jooq` setting | Default | What it is |
|---|---|---|
| `dialect` | from the JDBC url | The `SQLDialect` of jOOQ, by name (`POSTGRES`, `MYSQL`) |
| `logSql` | `false` | Logs every statement, with its values inlined |
| `optimisticLocking` | `false` | Updates and deletes of a record with a version field check it did not change since it was read. See below |
| `translateErrors` | `true` | Translates the errors of the database into the ones of [Errors of the database](#errors-of-the-database) |

---

## Optimistic locking

With `optimisticLocking`, jOOQ adds `WHERE version = <the one read>` to the update and the delete of a record that
has a version field (the code generation marks it with `recordVersionFields`), and increments it. A record
without one is left alone.

When the version changed, jOOQ throws its `DataChangedException`. It does not come from the database, so it is not
translated: the repository catches it and throws `ConcurrentModificationError` of
[trantor-domain](trantor-domain.md#domain-errors), which `trantor-web` answers with a 409.

A DAO updates several records one by one, so each is checked, as long as `returnRecordToPojo` and
`returnIdentityOnUpdatableRecord` of the settings of jOOQ keep their default. A batch of jOOQ
(`dsl.batchUpdate(records)`) does not check the versions at all.

---

## Errors of the database

With `translateErrors`, what the database refuses reaches the application as a `DataError` instead of the
exception of jOOQ, naming the constraint it broke:

| Error | The database refused | Carries |
|---|---|---|
| `UniqueViolationError` | A value repeated in a unique constraint or in the primary key | `constraint`, `table` |
| `ForeignKeyViolationError` | A row pointing to one that does not exist, or a delete of a row others point to | `constraint`, `table` (the one that holds the foreign key) |
| `NotNullViolationError` | A null in a column that does not take it | `column`, `table` |
| `CheckViolationError` | A value a check constraint does not allow | `constraint`, `table` |
| `ConstraintViolationError` | Any other constraint (an exclusion constraint of Postgres). The parent of the four above | `constraint`, `table` |

Everything else stays the exception of jOOQ: a value too long for its column, a syntax error, a lost connection.
The exception of jOOQ is the cause of the `DataError`, so the SQL that failed is still in the log.

These are errors of the data, not of the domain: `trantor-web` answers any of them with a 500. The repository
decides what each one means for its callers, and throws the domain error they react to:

```kotlin
override fun add(vararg entities: Customer) {
    try {
        dao.insert(entities.map { it.toRecord() })
    } catch (e: UniqueViolationError) {
        if (e.constraint == "customers_email_uq") throw AlreadyExistsError("A customer with that email already exists", e)
        throw e
    }
}
```

A foreign key that fails a delete means the row is still in use (`ExistingDependencyError`); the operation that
failed tells it apart from a row that points to nothing.

### What each database names

An adapter per database reads the names from where that database puts them. The dialect of jOOQ picks it.

| | Postgres | MySQL | MariaDB | Any other |
|---|---|---|---|---|
| Read from | the fields of the error the server sends | the error code and the message | the error code and the message | the SQLSTATE |
| `constraint` | yes | yes (`PRIMARY` for the primary key) | yes | no |
| `table` of a unique constraint | yes | yes | no | no |
| `table` of a foreign key | yes | yes | yes | no |
| `column` of a not null | yes | yes | yes | no |
| `table` of a not null or a check | yes | no | only of a check | no |

- The fields of Postgres do not change with the language of the server; its message does. The adapter needs the
  driver of Postgres (`org.postgresql:postgresql`), which an application on Postgres already has.
- MySQL and MariaDB report every constraint with the SQLSTATE 23000, and MySQL a broken check with HY000, so their
  adapter goes by the error code. A column left out of an insert that has no default is a `NotNullViolationError`.
- A failed batch reports the error of the statement that failed, which Postgres chains to the error of the batch.

Names are the ones the database uses, as it reports them: Postgres folds unquoted names to lowercase.
