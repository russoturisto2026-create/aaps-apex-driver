package app.aaps.pump.apex.store

import app.aaps.pump.apex.basal.BasalPeriodKeeper
import app.aaps.pump.apex.bolus.BolusCells
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The tables the migrations make must be the ones the schemas hold: Room checks the table against the
 * schema when the database opens, and a table that differs stops AAPS at start.
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
    fun `the migrations lead from 7 through 8 to the version the database is at`() {
        assertEquals(13, ApexDatabase.VERSION)
        assertEquals(7, ApexDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, ApexDatabase.MIGRATION_7_8.endVersion)
        assertEquals(8, ApexDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, ApexDatabase.MIGRATION_8_9.endVersion)
        assertEquals(9, ApexDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, ApexDatabase.MIGRATION_9_10.endVersion)
        assertEquals(10, ApexDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, ApexDatabase.MIGRATION_10_11.endVersion)
        assertEquals(11, ApexDatabase.MIGRATION_11_12.startVersion)
        assertEquals(12, ApexDatabase.MIGRATION_11_12.endVersion)
        assertEquals(12, ApexDatabase.MIGRATION_12_13.startVersion)
        assertEquals(13, ApexDatabase.MIGRATION_12_13.endVersion)
    }

    @Test
    fun `the migration from 10 removes no note the driver reads`() {
        val read = listOf(BolusCells.COUNTER_KEY, BolusCells.READ_AT_KEY, BolusCells.FROM_KEY, BasalPeriodKeeper.STATE_PERIOD)
        assertEquals(emptyList<String>(), ApexDatabase.STALE_KEYS.filter { it in read || it.startsWith("bench.") })
        assertEquals(
            "DELETE FROM `state` WHERE `key` IN ('bolusCountMark', 'bolusFaultyCells', 'tbrGateMark', 'ourTbrs', 'foreignTbrs', 'lastTbr', " +
                "'openTbr', 'tbrKnownMs', 'recordsSeen.TBR')",
            ApexDatabase.MIGRATION_10_11_SQL
        )
    }

    @Test
    fun `the migration from 11 removes the base of the old row numbers and no note the driver reads`() {
        val read = listOf(BolusCells.COUNTER_KEY, BolusCells.READ_AT_KEY, BolusCells.FROM_KEY, BasalPeriodKeeper.STATE_PERIOD)
        assertEquals(emptyList<String>(), ApexDatabase.STALE_KEYS_12.filter { it in read || it.startsWith("bench.") })
        assertEquals("DELETE FROM `state` WHERE `key` IN ('pumpIdBase')", ApexDatabase.MIGRATION_11_12_SQL)
    }

    @Test
    fun `versions 11 and 12 hold the tables of version 10`() {
        fun tables(version: Int) = Regex("\"createSql\": \"([^\"]*)\"").findAll(schemaOf(version)).map { it.groupValues[1] }.toList()
        assertEquals(tables(10), tables(11))
        assertEquals(tables(10), tables(12))
    }

    @Test
    fun `the cells the migration from 9 makes are the cells of version 10, with their index`() {
        val schema = schemaOf(10)
        assertEquals(createSql(schema, "bolus_cell", "bolus_cell"), ApexDatabase.BOLUS_CELL_SQL)
        assertEquals(
            Regex("\"createSql\": \"(CREATE UNIQUE INDEX[^\"]*bolus_cell[^\"]*)\"").find(schema)!!.groupValues[1].replace("\${TABLE_NAME}", "bolus_cell"),
            ApexDatabase.MIGRATION_9_10_SQL[1]
        )
        // Version 10 has no action table: the migration drops it.
        assertEquals(false, schema.contains("\"tableName\": \"action\""))
        assertEquals("DROP TABLE `action`", ApexDatabase.MIGRATION_9_10_SQL.last())
    }

    @Test
    fun `the columns the migration from 8 adds are the columns of the action table of version 9`() {
        val table = createSql(schemaOf(9), "action", "action")
        val added = ApexDatabase.MIGRATION_8_9_SQL.map { it.removePrefix("ALTER TABLE `action` ADD COLUMN ") }
        assertEquals(createSql(schemaOf(8), "action", "action").removeSuffix(")") + ", " + added.joinToString(", ") + ")", table)
    }

    @Test
    fun `the cells of version 13 are the cells of version 12 with the time of the reconciliation and the bolus field of the day`() {
        val twelve = createSql(schemaOf(12), "bolus_cell", "bolus_cell")
        val thirteen = createSql(schemaOf(13), "bolus_cell", "bolus_cell")
        assertEquals(twelve.removeSuffix(")") + ", `settledAtMs` INTEGER, `dayBolusUnits` REAL)", thirteen)
        assertEquals(
            listOf("ALTER TABLE `bolus_cell` ADD COLUMN `settledAtMs` INTEGER", "ALTER TABLE `bolus_cell` ADD COLUMN `dayBolusUnits` REAL"),
            ApexDatabase.MIGRATION_12_13_SQL
        )
    }
}
