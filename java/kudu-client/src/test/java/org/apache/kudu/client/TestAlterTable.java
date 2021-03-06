// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.kudu.client;

import com.google.common.collect.ImmutableList;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.util.Pair;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TestAlterTable extends BaseKuduTest {

  private static final Logger LOG = LoggerFactory.getLogger(TestKuduClient.class);
  private String tableName;

  @Before
  public void setTableName() {
    tableName = TestKuduClient.class.getName() + "-" + System.currentTimeMillis();
  }

  /**
   * Creates a new table with two int columns, c0 and c1. c0 is the primary key.
   * The table is hash partitioned on c0 into two buckets, and range partitioned
   * with the provided bounds.
   */
  private KuduTable createTable(List<Pair<Integer, Integer>> bounds) throws KuduException {
    // Create initial table with single range partition covering the entire key
    // space, and two hash buckets.
    ArrayList<ColumnSchema> columns = new ArrayList<>(1);
    columns.add(new ColumnSchema.ColumnSchemaBuilder("c0", Type.INT32)
                                .nullable(false)
                                .key(true)
                                .build());
    columns.add(new ColumnSchema.ColumnSchemaBuilder("c1", Type.INT32)
                                .nullable(false)
                                .build());
    Schema schema = new Schema(columns);

    CreateTableOptions createOptions =
        new CreateTableOptions().setRangePartitionColumns(ImmutableList.of("c0"))
                                .setNumReplicas(1)
                                .addHashPartitions(ImmutableList.of("c0"), 2);

    for (Pair<Integer, Integer> bound : bounds) {
      PartialRow lower = schema.newPartialRow();
      PartialRow upper = schema.newPartialRow();
      lower.addInt("c0", bound.getFirst());
      upper.addInt("c0", bound.getSecond());
      createOptions.addRangeBound(lower, upper);
    }

    return BaseKuduTest.createTable(tableName, schema, createOptions);
  }

  /**
   * Insert rows into the provided table. The table's columns must be ints, and
   * must have a primary key in the first column.
   * @param table the table
   * @param start the inclusive start key
   * @param end the exclusive end key
   */
  private void insertRows(KuduTable table, int start, int end) throws KuduException {
    KuduSession session = syncClient.newSession();
    session.setFlushMode(SessionConfiguration.FlushMode.AUTO_FLUSH_BACKGROUND);
    for (int i = start; i < end; i++) {
      Insert insert = table.newInsert();
      for (int idx = 0; idx < table.getSchema().getColumnCount(); idx++) {
        insert.getRow().addInt(idx, i);
      }
      session.apply(insert);
    }
    session.flush();
    RowError[] rowErrors = session.getPendingErrors().getRowErrors();
    assertEquals(String.format("row errors: %s", Arrays.toString(rowErrors)), 0, rowErrors.length);
  }

  @Test
  public void testAlterRangePartitioning() throws Exception {
    KuduTable table = createTable(ImmutableList.<Pair<Integer,Integer>>of());
    Schema schema = table.getSchema();

    // Insert some rows, and then drop the partition and ensure that the table is empty.
    insertRows(table, 0, 100);
    assertEquals(100, countRowsInTable(table));
    PartialRow lower = schema.newPartialRow();
    PartialRow upper = schema.newPartialRow();
    syncClient.alterTable(tableName, new AlterTableOptions().dropRangePartition(lower, upper));
    assertEquals(0, countRowsInTable(table));

    // Add new range partition and insert rows.
    lower.addInt("c0", 0);
    upper.addInt("c0", 100);
    syncClient.alterTable(tableName, new AlterTableOptions().addRangePartition(lower, upper));
    insertRows(table, 0, 100);
    assertEquals(100, countRowsInTable(table));

    // Replace the range partition with a different one.
    AlterTableOptions options = new AlterTableOptions();
    options.dropRangePartition(lower, upper);
    lower.addInt("c0", 50);
    upper.addInt("c0", 150);
    options.addRangePartition(lower, upper);
    syncClient.alterTable(tableName, options);
    assertEquals(0, countRowsInTable(table));
    insertRows(table, 50, 125);
    assertEquals(75, countRowsInTable(table));

    // Replace the range partition with the same one.
    syncClient.alterTable(tableName, new AlterTableOptions().dropRangePartition(lower, upper)
                                                            .addRangePartition(lower, upper));
    assertEquals(0, countRowsInTable(table));
    insertRows(table, 50, 125);
    assertEquals(75, countRowsInTable(table));

    // Alter table partitioning + alter table schema
    lower.addInt("c0", 200);
    upper.addInt("c0", 300);
    syncClient.alterTable(tableName, new AlterTableOptions().addRangePartition(lower, upper)
                                                            .renameTable(tableName + "-renamed")
                                                            .addNullableColumn("c2", Type.INT32));
    tableName = tableName + "-renamed";
    insertRows(table, 200, 300);
    assertEquals(175, countRowsInTable(table));
    assertEquals(3, openTable(tableName).getSchema().getColumnCount());

    // Drop all range partitions + alter table schema. This also serves to test
    // specifying range bounds with a subset schema (since a column was
    // previously added).
    options = new AlterTableOptions();
    options.dropRangePartition(lower, upper);
    lower.addInt("c0", 50);
    upper.addInt("c0", 150);
    options.dropRangePartition(lower, upper);
    options.dropColumn("c2");
    syncClient.alterTable(tableName, options);
    assertEquals(0, countRowsInTable(table));
    assertEquals(2, openTable(tableName).getSchema().getColumnCount());
  }

  @Test
  public void testAlterRangeParitioningInvalid() throws KuduException {
    // Create initial table with single range partition covering [0, 100).
    KuduTable table = createTable(ImmutableList.of(new Pair<>(0, 100)));
    Schema schema = table.getSchema();
    insertRows(table, 0, 100);
    assertEquals(100, countRowsInTable(table));

    // ADD [0, 100) <- illegal (duplicate)
    PartialRow lower = schema.newPartialRow();
    PartialRow upper = schema.newPartialRow();
    lower.addInt("c0", 0);
    upper.addInt("c0", 100);
    try {
      syncClient.alterTable(tableName, new AlterTableOptions().addRangePartition(lower, upper));
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "New partition conflicts with existing partition"));
    }
    assertEquals(100, countRowsInTable(table));

    // ADD [50, 150) <- illegal (overlap)
    lower.addInt("c0", 50);
    upper.addInt("c0", 150);
    try {
      syncClient.alterTable(tableName, new AlterTableOptions().addRangePartition(lower, upper));
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "New partition conflicts with existing partition"));
    }
    assertEquals(100, countRowsInTable(table));

    // ADD [-50, 50) <- illegal (overlap)
    lower.addInt("c0", -50);
    upper.addInt("c0", 50);
    try {
      syncClient.alterTable(tableName, new AlterTableOptions().addRangePartition(lower, upper));
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "New partition conflicts with existing partition"));
    }
    assertEquals(100, countRowsInTable(table));

    // ADD [200, 300)
    // ADD [-50, 150) <- illegal (overlap)
    lower.addInt("c0", 200);
    upper.addInt("c0", 300);
    AlterTableOptions options = new AlterTableOptions();
    options.addRangePartition(lower, upper);
    lower.addInt("c0", -50);
    upper.addInt("c0", 150);
    options.addRangePartition(lower, upper);
    try {
      syncClient.alterTable(tableName, options);
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "New partition conflicts with existing partition"));
    }
    assertEquals(100, countRowsInTable(table));

    // DROP [<start>, <end>)
    try {
      syncClient.alterTable(tableName,
                            new AlterTableOptions().dropRangePartition(schema.newPartialRow(),
                                                                       schema.newPartialRow()));
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage(), e.getStatus().getMessage().contains(
          "No tablet found for drop partition step"));
    }
    assertEquals(100, countRowsInTable(table));

    // DROP [50, 150)
    // RENAME foo
    lower.addInt("c0", 50);
    upper.addInt("c0", 150);
    try {
      syncClient.alterTable(tableName, new AlterTableOptions().dropRangePartition(lower, upper)
                                                              .renameTable("foo"));
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "No tablet found for drop partition step"));
    }
    assertEquals(100, countRowsInTable(table));
    assertFalse(syncClient.tableExists("foo"));

    // DROP [0, 100)
    // ADD  [100, 200)
    // DROP [100, 200)
    // ADD  [150, 250)
    // DROP [0, 10)    <- illegal
    options = new AlterTableOptions();

    lower.addInt("c0", 0);
    upper.addInt("c0", 100);
    options.dropRangePartition(lower, upper);

    lower.addInt("c0", 100);
    upper.addInt("c0", 200);
    options.addRangePartition(lower, upper);
    options.dropRangePartition(lower, upper);

    lower.addInt("c0", 150);
    upper.addInt("c0", 250);
    options.addRangePartition(lower, upper);

    lower.addInt("c0", 0);
    upper.addInt("c0", 10);
    options.dropRangePartition(lower, upper);
    try {
      syncClient.alterTable(tableName, options);
    } catch (KuduException e) {
      assertTrue(e.getStatus().isInvalidArgument());
      assertTrue(e.getStatus().getMessage().contains(
          "No tablet found for drop partition step"));
    }
    assertEquals(100, countRowsInTable(table));
  }
}
