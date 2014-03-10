/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.sink.hive;


import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.hive.streaming.DelimitedInputWriter;
import org.apache.hive.streaming.HiveEndPoint;
import org.apache.hive.streaming.RecordWriter;
import org.apache.hive.streaming.StreamingException;
import org.apache.hive.streaming.TransactionBatch;

import java.io.IOException;

/** Forwards the incoming event body to Hive unmodified
 * Sets up the delimiter and the field to column mapping
 */

public class HiveDelimitedTextSerializer implements HiveEventSerializer  {
  private String delimiter;
  String[] fieldToColMapping = null;
  public static String ALIAS = "DELIIMITED";
  private Character serdeSeparator = null;

  @Override
  public void write(TransactionBatch txnBatch, Event e)
          throws StreamingException, IOException {
    txnBatch.write(e.getBody());
  }

  @Override
  public RecordWriter createRecordWriter(HiveEndPoint endPoint)
          throws StreamingException, IOException, ClassNotFoundException {
    if(serdeSeparator==null) {
      return new DelimitedInputWriter(fieldToColMapping, delimiter, endPoint);
    }
    return new DelimitedInputWriter(fieldToColMapping, delimiter, endPoint
            , serdeSeparator.charValue());
  }

  @Override
  public void configure(Context context) {
    delimiter = context.getString("serializer.delimiter", ",");
    String fieldNames = context.getString("serializer.fieldnames");
    if (fieldNames==null) {
      throw new IllegalArgumentException("serializer.fieldnames is not specified " +
              "for serializer " + this.getClass().getName() );
    }
    String serdeSeparatorStr = context.getString("serializer.serdeSeparator");
    if(serdeSeparatorStr!=null) {
      if(serdeSeparatorStr.length()>1) {
        throw new IllegalArgumentException("serializer.fieldnames is not specified " +
                "for serializer " + this.getClass().getName() );
      }
      this.serdeSeparator = serdeSeparatorStr.charAt(0);
    } else {
      serdeSeparator = null;
    }
    // split, but preserve empty fields (-1)
    fieldToColMapping = fieldNames.trim().split(",",-1);
  }

}
