package dev.botta.trantor.data.testing

import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException

/**
 * Errors recorded from real databases, on the tables
 * `authors (id INT PRIMARY KEY, name VARCHAR(10) NOT NULL, CONSTRAINT authors_name_uq UNIQUE (name),
 * CONSTRAINT authors_name_ck CHECK (name <> 'x'))` and
 * `books (id INT PRIMARY KEY, author_id INT NOT NULL, CONSTRAINT books_author_fk FOREIGN KEY (author_id)
 * REFERENCES authors (id))`, with PostgreSQL 18 and pgjdbc 42.7.13, MySQL 8.4 and Connector/J 26.7.0, and
 * MariaDB 11 and MariaDB Connector/J 3.5.10. Each keeps the SQLSTATE, error code and message the driver threw,
 * and for Postgres the fields the server sent. The class is the one of `java.sql` the driver threw, or its
 * parent when the driver threw one of its own (`MysqlDataTruncation`), which the adapters do not look at.
 */
object RecordedErrors {
    object Postgres {
        val unique = error(
            'C' to "23505",
            'M' to "duplicate key value violates unique constraint \"authors_name_uq\"",
            'D' to "Key (name)=(ursula) already exists.",
            's' to "public",
            't' to "authors",
            'n' to "authors_name_uq",
        )
        val primaryKey = error(
            'C' to "23505",
            'M' to "duplicate key value violates unique constraint \"authors_pkey\"",
            'D' to "Key (id)=(1) already exists.",
            's' to "public",
            't' to "authors",
            'n' to "authors_pkey",
        )
        val foreignKeyOnDelete = error(
            'C' to "23503",
            'M' to "update or delete on table \"authors\" violates foreign key constraint \"books_author_fk\" on table \"books\"",
            'D' to "Key (id)=(1) is still referenced from table \"books\".",
            's' to "public",
            't' to "books",
            'n' to "books_author_fk",
        )
        val foreignKeyOnInsert = error(
            'C' to "23503",
            'M' to "insert or update on table \"books\" violates foreign key constraint \"books_author_fk\"",
            'D' to "Key (author_id)=(99) is not present in table \"authors\".",
            's' to "public",
            't' to "books",
            'n' to "books_author_fk",
        )
        val notNull = error(
            'C' to "23502",
            'M' to "null value in column \"name\" of relation \"authors\" violates not-null constraint",
            'D' to "Failing row contains (3, null).",
            's' to "public",
            't' to "authors",
            'c' to "name",
        )
        val check = error(
            'C' to "23514",
            'M' to "new row for relation \"authors\" violates check constraint \"authors_name_ck\"",
            'D' to "Failing row contains (5, x).",
            's' to "public",
            't' to "authors",
            'n' to "authors_name_ck",
        )
        val tooLong = error(
            'C' to "22001",
            'M' to "value too long for type character varying(10)",
        )
        val batchUnique = BatchUpdateException(
            "Batch entry 1 INSERT INTO authors (id, name) VALUES (('8'::int4), ('ursula')) was aborted: " +
                "ERROR: duplicate key value violates unique constraint \"authors_name_uq\"\n" +
                "  Detail: Key (name)=(ursula) already exists.  Call getNextException to see other errors in the batch.",
            "23505",
            0,
            IntArray(0),
        ).apply { nextException = unique }

        private fun error(vararg fields: Pair<Char, String>) = PSQLException(
            ServerErrorMessage((listOf('S' to "ERROR") + fields).joinToString("") { "${it.first}${it.second}\u0000" })
        )
    }

    object MySql {
        val unique = violation("Duplicate entry 'ursula' for key 'authors.authors_name_uq'", 1062)
        val primaryKey = violation("Duplicate entry '1' for key 'authors.PRIMARY'", 1062)
        val foreignKeyOnDelete = violation(
            "Cannot delete or update a parent row: a foreign key constraint fails (`rec`.`books`, " +
                "CONSTRAINT `books_author_fk` FOREIGN KEY (`author_id`) REFERENCES `authors` (`id`))",
            1451,
        )
        val foreignKeyOnInsert = violation(
            "Cannot add or update a child row: a foreign key constraint fails (`rec`.`books`, " +
                "CONSTRAINT `books_author_fk` FOREIGN KEY (`author_id`) REFERENCES `authors` (`id`))",
            1452,
        )
        val notNull = violation("Column 'name' cannot be null", 1048)
        val noDefault = SQLException("Field 'name' doesn't have a default value", "HY000", 1364)
        val check = SQLException("Check constraint 'authors_name_ck' is violated.", "HY000", 3819)
        val tooLong = SQLException("Data truncation: Data too long for column 'name' at row 1", "22001", 1406)
        val batchUnique = BatchUpdateException(
            "Duplicate entry 'ursula' for key 'authors.authors_name_uq'",
            "23000",
            1062,
            IntArray(0),
            unique,
        )
    }

    object MariaDb {
        val unique = violation("(conn=3) Duplicate entry 'ursula' for key 'authors_name_uq'", 1062)
        val foreignKeyOnDelete = violation(
            "(conn=3) Cannot delete or update a parent row: a foreign key constraint fails (`rec`.`books`, " +
                "CONSTRAINT `books_author_fk` FOREIGN KEY (`author_id`) REFERENCES `authors` (`id`))",
            1451,
        )
        val notNull = violation("(conn=3) Column 'name' cannot be null", 1048)
        val check = violation("(conn=3) CONSTRAINT `authors_name_ck` failed for `rec`.`authors`", 4025)
    }

    private fun violation(message: String, errorCode: Int) =
        SQLIntegrityConstraintViolationException(message, "23000", errorCode)
}
