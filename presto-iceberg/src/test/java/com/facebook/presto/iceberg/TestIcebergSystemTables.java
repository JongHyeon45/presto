/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.iceberg;

import com.facebook.presto.Session;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.MaterializedRow;
import com.facebook.presto.testing.QueryRunner;
import com.facebook.presto.tests.AbstractTestQueryFramework;
import com.facebook.presto.tests.DistributedQueryRunner;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.facebook.presto.iceberg.IcebergQueryRunner.ICEBERG_CATALOG;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;

public class TestIcebergSystemTables
        extends AbstractTestQueryFramework
{
    private static final int DEFAULT_PRECISION = 5;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog(ICEBERG_CATALOG)
                .build();
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).build();

        Path catalogDirectory = queryRunner.getCoordinator().getDataDirectory().resolve("iceberg_data").resolve("catalog");

        queryRunner.installPlugin(new IcebergPlugin());
        Map<String, String> icebergProperties = ImmutableMap.<String, String>builder()
                .put("hive.metastore", "file")
                .put("hive.metastore.catalog.dir", catalogDirectory.toFile().toURI().toString())
                .build();

        queryRunner.createCatalog(ICEBERG_CATALOG, "iceberg", icebergProperties);

        return queryRunner;
    }

    @BeforeClass
    @Override
    public void init()
            throws Exception
    {
        super.init();
        assertUpdate("CREATE SCHEMA test_schema");
        assertUpdate("CREATE TABLE test_schema.test_table (_bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO test_schema.test_table VALUES (0, CAST('2019-09-08' AS DATE)), (1, CAST('2019-09-09' AS DATE)), (2, CAST('2019-09-09' AS DATE))", 3);
        assertUpdate("INSERT INTO test_schema.test_table VALUES (3, CAST('2019-09-09' AS DATE)), (4, CAST('2019-09-10' AS DATE)), (5, CAST('2019-09-10' AS DATE))", 3);
        assertQuery("SELECT count(*) FROM test_schema.test_table", "VALUES 6");

        assertUpdate("CREATE TABLE test_schema.test_table_v1 (_bigint BIGINT, _date DATE) WITH (format_version = '1', partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO test_schema.test_table_v1 VALUES (0, CAST('2019-09-08' AS DATE)), (1, CAST('2019-09-09' AS DATE)), (2, CAST('2019-09-09' AS DATE))", 3);
        assertUpdate("INSERT INTO test_schema.test_table_v1 VALUES (3, CAST('2019-09-09' AS DATE)), (4, CAST('2019-09-10' AS DATE)), (5, CAST('2019-09-10' AS DATE))", 3);
        assertQuery("SELECT count(*) FROM test_schema.test_table_v1", "VALUES 6");

        assertUpdate("CREATE TABLE test_schema.test_table_multilevel_partitions (_varchar VARCHAR, _bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_bigint', '_date'])");
        assertUpdate("INSERT INTO test_schema.test_table_multilevel_partitions VALUES ('a', 0, CAST('2019-09-08' AS DATE)), ('a', 1, CAST('2019-09-08' AS DATE)), ('a', 0, CAST('2019-09-09' AS DATE))", 3);
        assertQuery("SELECT count(*) FROM test_schema.test_table_multilevel_partitions", "VALUES 3");

        assertUpdate("CREATE TABLE test_schema.test_table_drop_column (_varchar VARCHAR, _bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO test_schema.test_table_drop_column VALUES ('a', 0, CAST('2019-09-08' AS DATE)), ('a', 1, CAST('2019-09-09' AS DATE)), ('b', 2, CAST('2019-09-09' AS DATE))", 3);
        assertUpdate("INSERT INTO test_schema.test_table_drop_column VALUES ('c', 3, CAST('2019-09-09' AS DATE)), ('a', 4, CAST('2019-09-10' AS DATE)), ('b', 5, CAST('2019-09-10' AS DATE))", 3);
        assertQuery("SELECT count(*) FROM test_schema.test_table_drop_column", "VALUES 6");
        assertUpdate("ALTER TABLE test_schema.test_table_drop_column DROP COLUMN _varchar");
    }

    @Test
    public void testPartitionTable()
    {
        assertQuery("SELECT count(*) FROM test_schema.test_table", "VALUES 6");
        assertQuery("SHOW COLUMNS FROM test_schema.\"test_table$partitions\"",
                "VALUES ('_date', 'date', '', '')," +
                        "('row_count', 'bigint', '', '')," +
                        "('file_count', 'bigint', '', '')," +
                        "('total_size', 'bigint', '', '')," +
                        "('_bigint', 'row(\"min\" bigint, \"max\" bigint, \"null_count\" bigint)', '', '')");

        MaterializedResult result = computeActual("SELECT * from test_schema.\"test_table$partitions\"");
        assertEquals(result.getRowCount(), 3);

        Map<LocalDate, MaterializedRow> rowsByPartition = result.getMaterializedRows().stream()
                .collect(toImmutableMap(row -> (LocalDate) row.getField(0), Function.identity()));

        // Test if row counts are computed correctly
        assertEquals(rowsByPartition.get(LocalDate.parse("2019-09-08")).getField(1), 1L);
        assertEquals(rowsByPartition.get(LocalDate.parse("2019-09-09")).getField(1), 3L);
        assertEquals(rowsByPartition.get(LocalDate.parse("2019-09-10")).getField(1), 2L);

        // Test if min/max values and null value count are computed correctly.
        assertEquals(
                rowsByPartition.get(LocalDate.parse("2019-09-08")).getField(4),
                new MaterializedRow(DEFAULT_PRECISION, 0L, 0L, 0L).getFields());
        assertEquals(
                rowsByPartition.get(LocalDate.parse("2019-09-09")).getField(4),
                new MaterializedRow(DEFAULT_PRECISION, 1L, 3L, 0L).getFields());
        assertEquals(
                rowsByPartition.get(LocalDate.parse("2019-09-10")).getField(4),
                new MaterializedRow(DEFAULT_PRECISION, 4L, 5L, 0L).getFields());
    }

    @Test
    public void testHistoryTable()
    {
        assertQuery("SHOW COLUMNS FROM test_schema.\"test_table$history\"",
                "VALUES ('made_current_at', 'timestamp with time zone', '', '')," +
                        "('snapshot_id', 'bigint', '', '')," +
                        "('parent_id', 'bigint', '', '')," +
                        "('is_current_ancestor', 'boolean', '', '')");

        // Test the number of history entries
        assertQuery("SELECT count(*) FROM test_schema.\"test_table$history\"", "VALUES 2");
    }

    @Test
    public void testSnapshotsTable()
    {
        assertQuery("SHOW COLUMNS FROM test_schema.\"test_table$snapshots\"",
                "VALUES ('committed_at', 'timestamp with time zone', '', '')," +
                        "('snapshot_id', 'bigint', '', '')," +
                        "('parent_id', 'bigint', '', '')," +
                        "('operation', 'varchar', '', '')," +
                        "('manifest_list', 'varchar', '', '')," +
                        "('summary', 'map(varchar, varchar)', '', '')");

        assertQuery("SELECT operation FROM test_schema.\"test_table$snapshots\"", "VALUES 'append', 'append'");
        assertQuery("SELECT summary['total-records'] FROM test_schema.\"test_table$snapshots\"", "VALUES '3', '6'");
    }

    @Test
    public void testManifestsTable()
    {
        assertQuery("SHOW COLUMNS FROM test_schema.\"test_table$manifests\"",
                "VALUES ('path', 'varchar', '', '')," +
                        "('length', 'bigint', '', '')," +
                        "('partition_spec_id', 'integer', '', '')," +
                        "('added_snapshot_id', 'bigint', '', '')," +
                        "('added_data_files_count', 'integer', '', '')," +
                        "('existing_data_files_count', 'integer', '', '')," +
                        "('deleted_data_files_count', 'integer', '', '')," +
                        "('partitions', 'array(row(\"contains_null\" boolean, \"lower_bound\" varchar, \"upper_bound\" varchar))', '', '')");
        assertQuerySucceeds("SELECT * FROM test_schema.\"test_table$manifests\"");

        assertQuerySucceeds("SELECT * FROM test_schema.\"test_table_multilevel_partitions$manifests\"");
    }

    @Test
    public void testFilesTable()
    {
        assertQuery("SHOW COLUMNS FROM test_schema.\"test_table$files\"",
                "VALUES ('content', 'integer', '', '')," +
                        "('file_path', 'varchar', '', '')," +
                        "('file_format', 'varchar', '', '')," +
                        "('record_count', 'bigint', '', '')," +
                        "('file_size_in_bytes', 'bigint', '', '')," +
                        "('column_sizes', 'map(integer, bigint)', '', '')," +
                        "('value_counts', 'map(integer, bigint)', '', '')," +
                        "('null_value_counts', 'map(integer, bigint)', '', '')," +
                        "('nan_value_counts', 'map(integer, bigint)', '', '')," +
                        "('lower_bounds', 'map(integer, varchar)', '', '')," +
                        "('upper_bounds', 'map(integer, varchar)', '', '')," +
                        "('key_metadata', 'varbinary', '', '')," +
                        "('split_offsets', 'array(bigint)', '', '')," +
                        "('equality_ids', 'array(integer)', '', '')");
        assertQuerySucceeds("SELECT * FROM test_schema.\"test_table$files\"");
    }

    protected void checkTableProperties(String tableName, String deleteMode)
    {
        assertQuery(String.format("SHOW COLUMNS FROM test_schema.\"%s$properties\"", tableName),
                "VALUES ('key', 'varchar', '', '')," + "('value', 'varchar', '', '')");
        assertQuery(String.format("SELECT COUNT(*) FROM test_schema.\"%s$properties\"", tableName), "VALUES 4");
        List<MaterializedRow> materializedRows = computeActual(getSession(),
                String.format("SELECT * FROM test_schema.\"%s$properties\"", tableName)).getMaterializedRows();

        assertThat(materializedRows).hasSize(4);
        assertThat(materializedRows)
                .anySatisfy(row -> assertThat(row)
                        .isEqualTo(new MaterializedRow(MaterializedResult.DEFAULT_PRECISION, "write.delete.mode", deleteMode)))
                .anySatisfy(row -> assertThat(row)
                        .isEqualTo(new MaterializedRow(MaterializedResult.DEFAULT_PRECISION, "write.format.default", "PARQUET")))
                .anySatisfy(row -> assertThat(row)
                        .isEqualTo(new MaterializedRow(MaterializedResult.DEFAULT_PRECISION, "write.parquet.compression-codec", "zstd")))
                .anySatisfy(row -> assertThat(row)
                        .isEqualTo(new MaterializedRow(MaterializedResult.DEFAULT_PRECISION, "commit.retry.num-retries", "4")));
    }

    @Test
    public void testPropertiesTable()
    {
        // Test table properties for all supported format versions
        checkTableProperties("test_table_v1", "copy-on-write");
        checkTableProperties("test_table", "merge-on-read");
    }

    @Test
    public void testFilesTableOnDropColumn()
    {
        assertQuery("SELECT sum(record_count) FROM test_schema.\"test_table_drop_column$files\"", "VALUES 6");
    }

    @Test
    public void testAlterTableColumnNotNull()
    {
        String tableName = "test_schema.test_table_add_column";
        assertUpdate("CREATE TABLE " + tableName + " (c1 INTEGER, c2 INTEGER)");
        assertQueryFails("ALTER TABLE " + tableName + " ADD COLUMN c3 INTEGER NOT NULL",
                "This connector does not support add column with non null");
        assertUpdate("INSERT INTO " + tableName + " VALUES (1,1)", 1);
        assertQueryFails("ALTER TABLE " + tableName + " ADD COLUMN c3 INTEGER NOT NULL",
                "This connector does not support add column with non null");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        assertUpdate("DROP TABLE IF EXISTS test_schema.test_table");
        assertUpdate("DROP TABLE IF EXISTS test_schema.test_table_v1");
        assertUpdate("DROP TABLE IF EXISTS test_schema.test_table_multilevel_partitions");
        assertUpdate("DROP TABLE IF EXISTS test_schema.test_table_drop_column");
        assertUpdate("DROP TABLE IF EXISTS test_schema.test_table_add_column");
        assertUpdate("DROP SCHEMA IF EXISTS test_schema");
    }
}
