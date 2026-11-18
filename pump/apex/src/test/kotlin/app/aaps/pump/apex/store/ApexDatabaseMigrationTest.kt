package app.aaps.pump.apex.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The table the migration from 7 makes must be the one the schema of version 8 holds: Room checks the
 * table against the schema when the database opens, and a table that differs stops AAPS at start.
 */
class ApexDatabaseMigrationTest {

    private fun schemaOf(version: Int): String = File("schemas/app.aaps.pump.apex.store.ApexDatabase/$version.json").readText()

    /** The create statement of [table] in [schema], for a table named [name]. */
    private fun createSql(schema: String, table: String, name: String): String =
        Regex("\"tableName\": \"$table\",\\s*\"createSql\": \"(.*?)\",", RegexOption.DOT_MATCHES_ALL).find(schema)!!.groupValues[1]
            .replace("\${TABLE_NAME}", name)

    @Test
    fun `the action table the migration makes is the action table of version 8`() {
        assertEquals(createSql(schemaOf(8), "action", "action_new"), ApexDatabase.MIGRATION_7_8_SQL.first())
    }

    @Test
    fun `the migration from 7 leads to the version the database is at`() {
        assertEquals(8, ApexDatabase.VERSION)
        assertEquals(7, ApexDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, ApexDatabase.MIGRATION_7_8.endVersion)
    }
}
