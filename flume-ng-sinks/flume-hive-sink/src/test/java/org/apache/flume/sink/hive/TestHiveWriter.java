/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.sink.hive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import junit.framework.Assert;
import org.apache.flume.Context;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.streaming.HiveEndPoint;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestHiveWriter {
  final static String dbName = "testing";
  final static String tblName = "alerts";

  public static final String PART1_NAME = "continent";
  public static final String PART2_NAME = "country";
  public static final String[] partNames = { PART1_NAME, PART2_NAME };

  private static final String COL1 = "id";
  private static final String COL2 = "msg";
  final String[] colNames = {COL1,COL2};
  private String[] colTypes = { "int", "string" };

  private static final String PART1_VALUE = "Asia";
  private static final String PART2_VALUE = "India";
  private final ArrayList<String> partVals;

  private final int port;
  private final String metaStoreURI;

  private HiveDelimitedTextSerializer serializer;

  private final HiveConf conf;
  private Driver driver;

  private ExecutorService callTimeoutPool;
  int timeout = 10000; // msec



  public TestHiveWriter() throws Exception {
    partVals = new ArrayList<String>(2);
    partVals.add(PART1_VALUE);
    partVals.add(PART2_VALUE);

    port = TestUtil.findFreePort();
    metaStoreURI = "thrift://localhost:" + port;

    int threadPoolSize = 1;
    callTimeoutPool = Executors.newFixedThreadPool(threadPoolSize,
            new ThreadFactoryBuilder().setNameFormat("hiveWriterTest").build());

    // Start metastore
    conf = new HiveConf(this.getClass());
    TestUtil.setConfValues(conf);
    conf.setVar(HiveConf.ConfVars.METASTOREURIS, metaStoreURI);
    TestUtil.startLocalMetaStore(port, conf);

    // 2) Setup Hive client
    SessionState.start(new CliSessionState(conf));
    driver = new Driver(conf);
  }

  @Before
  public void setUp() throws Exception {
    // 1) Setup tables
    TestUtil.dropDB(conf, dbName);
    TestUtil.createDbAndTable(conf,dbName, tblName, partVals, colNames, colTypes, partNames);

    // 2) Setup serializer
    Context ctx = new Context();
    ctx.put("serializer.fieldnames", COL1 + ",," + COL2 + ",");
    serializer = new HiveDelimitedTextSerializer();
    serializer.configure(ctx);
  }

  @Test
  public void testInstantiate() throws Exception {
    HiveEndPoint endPoint = new HiveEndPoint(metaStoreURI, dbName, tblName, partVals);
    SinkCounter sinkCounter = new SinkCounter(this.getClass().getName());

    HiveWriter writer = new HiveWriter(endPoint, 10, true, timeout, timeout
            , callTimeoutPool, "flumetest", serializer, sinkCounter);

    writer.close();
  }

  @Test
  public void testWriteBasic() throws Exception {
    HiveEndPoint endPoint = new HiveEndPoint(metaStoreURI, dbName, tblName, partVals);
    SinkCounter sinkCounter = new SinkCounter(this.getClass().getName());
    HiveWriter writer = new HiveWriter(endPoint, 10, true, timeout, timeout
            , callTimeoutPool, "flumetest", serializer, sinkCounter);

    SimpleEvent event = new SimpleEvent();
    event.setBody("1,xyz,Hello world,abc".getBytes());
    writer.write(event);
    event.setBody("2,xyz,Hello world,abc".getBytes());
    writer.write(event);
    event.setBody("3,xyz,Hello world,abc".getBytes());
    writer.write(event);
    writer.flush(false);
    writer.close();
  }

  @Test
  public void testWriteMultiFlush() throws Exception {
    HiveEndPoint endPoint = new HiveEndPoint(metaStoreURI, dbName, tblName, partVals);
    SinkCounter sinkCounter = new SinkCounter(this.getClass().getName());

    HiveWriter writer = new HiveWriter(endPoint, 10, true, timeout, timeout
            , callTimeoutPool, "flumetest", serializer, sinkCounter);

    checkRecordCountInTable(0);
    SimpleEvent event = new SimpleEvent();

    String REC1 = "1,xyz,Hello world,abc";
    event.setBody(REC1.getBytes());
    writer.write(event);
    checkRecordCountInTable(0);
    writer.flush(true);
    checkRecordCountInTable(1);

    String REC2 = "2,xyz,Hello world,abc";
    event.setBody(REC2.getBytes());
    writer.write(event);
    checkRecordCountInTable(1);
    writer.flush(true);
    checkRecordCountInTable(2);

    String REC3 = "3,xyz,Hello world,abc";
    event.setBody(REC3.getBytes());
    writer.write(event);
    writer.flush(true);
    checkRecordCountInTable(3);
    writer.close();

    checkRecordCountInTable(3);
  }

  private void checkRecordCountInTable(int expectedCount)
          throws CommandNeedRetryException, IOException {
    int count = TestUtil.listRecordsInTable(driver, dbName, tblName).size();
    Assert.assertEquals(expectedCount, count);
  }

  /**
   * Sets up input fields to have same order as table columns,
   * Also sets the separator on serde to be same as i/p field separator
   * @throws Exception
   */
  @Test
  public void testInOrderWrite() throws Exception {
    HiveEndPoint endPoint = new HiveEndPoint(metaStoreURI, dbName, tblName, partVals);
    SinkCounter sinkCounter = new SinkCounter(this.getClass().getName());
    int timeout = 10000; // msec

    HiveDelimitedTextSerializer serializer2 = new HiveDelimitedTextSerializer();
    Context ctx = new Context();
    ctx.put("serializer.fieldnames", COL1 + "," + COL2);
    ctx.put("serializer.serdeSeparator", ",");
    serializer2.configure(ctx);


    HiveWriter writer = new HiveWriter(endPoint, 10, true, timeout, timeout, callTimeoutPool,
            "flumetest", serializer2, sinkCounter);

    SimpleEvent event = new SimpleEvent();
    event.setBody("1,Hello world 1".getBytes());
    writer.write(event);
    event.setBody("2,Hello world 2".getBytes());
    writer.write(event);
    event.setBody("3,Hello world 3".getBytes());
    writer.write(event);
    writer.flush(false);
    writer.close();
  }

}