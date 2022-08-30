/*
 * // Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * // SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.aws.cqlreplicator.storage;

import com.amazon.aws.cqlreplicator.models.*;
import com.amazon.aws.cqlreplicator.util.Utils;

import com.datastax.oss.driver.shaded.guava.common.collect.AbstractIterator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SerializationUtils;

import org.iq80.leveldb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class LedgerStorageOnLevelDB extends LedgerStorage<Object, List<Object>, Object> {

    private static final int PAGE_SIZE = 10000;
    private static final Logger LOGGER = LoggerFactory.getLogger(LedgerStorageOnLevelDB.class);
    private static final org.iq80.leveldb.Logger logger = LOGGER::info;

    private static <T> byte[] addToCbor(byte[] cbor, T object) throws IOException {

        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var set = Utils.cborDecoderSet(cbor);
        set.add(object);

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return Utils.cborEncoderSet(set);
    }

    private static <T> byte[] removeToCbor(byte[] cbor, T object) throws IOException {

        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var set = Utils.cborDecoderSet(cbor);
        set.remove(object);

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return Utils.cborEncoderSet(set);
    }



    private DB levelDBStore;
    private final Properties properties;

    public LedgerStorageOnLevelDB(Properties properties) throws IOException {
        Options options = new Options();
        this.properties = properties;
        levelDBStore = factory.open(new File(String.format("%s/ledger_v4_%s_%s.ldb",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE"),
                properties.getProperty("PROCESS_NAME"))),
                options.logger(logger).verifyChecksums(true).createIfMissing(true));
    }

    @Override
    public void tearDown() throws IOException {
        levelDBStore.close();
    }

    @Override
    public void writePartitionMetadata(Object o) {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var valueOnClient = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
        var partitionMetaData = (PartitionMetaData) o;
        var primaryKey = new PrimaryKey(partitionMetaData.getPk(), String.valueOf(partitionMetaData.getTile()));
        var value = SerializationUtils.serialize(valueOnClient);

        levelDBStore.put(SerializationUtils.serialize(primaryKey), value);

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    @Override
    public List<Object> readPartitionMetadata(Object o) {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var wrappedResult = new ArrayList<>();
        var partitionMetaData = (PartitionMetaData) o;
        var primaryKey = new PrimaryKey(partitionMetaData.getPk(), String.valueOf(partitionMetaData.getTile()));
        wrappedResult.add(SerializationUtils.deserialize(levelDBStore.get(SerializationUtils.serialize(primaryKey))));

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return wrappedResult;
    }

    @Override
    public void writeRowMetadata(Object o) throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var rowMetadata = (LedgerMetaData) o;
        var partitionKey = new PartitionKey(rowMetadata.getPartitionKeys());
        // Write clusteringKey with a value ( lastRun and writeTime)
        var clusteringKey = rowMetadata.getClusteringColumns();
        var value = new TimestampMetrics(rowMetadata.getLastRun(), rowMetadata.getLastWriteTime());
        // Upsert related partitionKey with a set of clustering keys
        var readBeforeWriteValue = levelDBStore.get(SerializationUtils.serialize(partitionKey));
        if (readBeforeWriteValue != null) {
            var batch = levelDBStore.createWriteBatch().put(SerializationUtils.serialize(partitionKey), addToCbor(readBeforeWriteValue, rowMetadata.getClusteringColumns()))
                    .put(SerializationUtils.serialize(String.format("%s|%s",partitionKey.getPartitionKey(),clusteringKey)), SerializationUtils.serialize(value));
            levelDBStore.write(batch);
        }
        else {
            Set<String> firstValueList = new HashSet<>();
            firstValueList.add(rowMetadata.getClusteringColumns());
            var batch = levelDBStore.createWriteBatch().
                    put(SerializationUtils.serialize(String.format("%s|%s",partitionKey.getPartitionKey(),clusteringKey)), SerializationUtils.serialize(value)).
                    put(SerializationUtils.serialize(partitionKey), Utils.cborEncoderSet(firstValueList));
            levelDBStore.write(batch);
        }

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    @Override
    public List<Object> readRowMetaData(Object o) throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var rowMetadata = (QueryLedgerItemByPk) o;
        var partitionKey = new PartitionKey(rowMetadata.getPartitionKey());
        var finalList = new ArrayList<>();
        var serializedPK = levelDBStore.get(SerializationUtils.serialize(partitionKey));
        if (serializedPK!=null) {
            var setOfClusteringKeys = Utils.cborDecoderSet(serializedPK);
            for (var ck:setOfClusteringKeys) {
                var timestampMetrics = (TimestampMetrics) SerializationUtils.deserialize(
                        levelDBStore.get(SerializationUtils.serialize(String.format("%s|%s",partitionKey.getPartitionKey(),ck))));
                var value = new Value(timestampMetrics.getLastRun(), timestampMetrics.getWriteTime(), ck.toString());
                finalList.add(value);
            }
        }

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return finalList;
    }

    @Override
    public void deletePartitionMetadata(Object o) {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var partitionMetaData = (PartitionMetaData) o;
        var primaryKey = new PrimaryKey(partitionMetaData.getPk(), String.valueOf(partitionMetaData.getTile()));
        levelDBStore.delete(SerializationUtils.serialize(primaryKey));

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    @Override
    public void deleteRowMetadata(Object o) throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        var rowMetadata = (LedgerMetaData) o;
        var partitionKey = new PartitionKey(rowMetadata.getPartitionKeys());
        var readBeforeWriteValue = levelDBStore.get(SerializationUtils.serialize(partitionKey));
        var clusteringKey = rowMetadata.getClusteringColumns();
        if (readBeforeWriteValue!=null) {
            if (!Utils.cborDecoderSet(readBeforeWriteValue).isEmpty())
            {
                var preparedSet = removeToCbor(readBeforeWriteValue, clusteringKey);
                // Remove a clusteringKey from the ledger
                levelDBStore.put(SerializationUtils.serialize(partitionKey),preparedSet);
                levelDBStore.delete(SerializationUtils.serialize(String.format("%s|%s",partitionKey.getPartitionKey(),clusteringKey)));
                if (Utils.cborDecoderSet(preparedSet).isEmpty()) {
                    // Remove a partitionKey from the ledger if the set is empty
                    levelDBStore.delete(SerializationUtils.serialize(partitionKey));
                }
            }
            else
                {
                    levelDBStore.delete(SerializationUtils.serialize(partitionKey));
                }
        }
        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    @Override
    public List<Object> execute(Object o) {
        return null;
    }

    public static void copyDirectory(String sourceDirectoryLocation, String destinationDirectoryLocation) throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        File sourceDirectory = new File(sourceDirectoryLocation);
        File destinationDirectory = new File(destinationDirectoryLocation);
        FileUtils.copyDirectory(sourceDirectory, destinationDirectory);

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    private void prepareSnapshot() throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        copyDirectory(String.format("%s/ledger_v4_%s_pd.ldb",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE")), String.format("%s/ledger_v4_%s_pd.ldb-snapshot",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE")));
        FileUtils.delete(new File(String.format("%s/ledger_v4_%s_pd.ldb-snapshot/LOCK",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE"))));

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    private void deleteSnapshot() throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        FileUtils.deleteDirectory(new File(String.format("%s/ledger_v4_%s_pd.ldb-snapshot",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE"))));

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());
    }

    @Override
    public List<Object> readPartitionsMetadata(Object o) throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        prepareSnapshot();
        ReadOptions ro = new ReadOptions();
        Options options = new Options();
        var levelDBStore1 = factory.open(new File(String.format("%s/ledger_v4_%s_pd.ldb-snapshot",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE"))), options);

        ro.snapshot(levelDBStore1.getSnapshot());

        var iterator = levelDBStore1.iterator();
        var finalResult = new ArrayList<>();
        iterator.seekToFirst();
        while (iterator.hasNext()){
            var key = SerializationUtils.deserialize(iterator.peekNext().getKey());
      if (key instanceof PrimaryKey) {
        finalResult.add(key);
            }
            iterator.next();
        }
        levelDBStore1.close();
        deleteSnapshot();

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return finalResult;
    }

    public Iterator<List<PrimaryKey>> readPaginatedPrimaryKeys()  {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        DBIterator iterator = levelDBStore.iterator();

        Iterator<List<PrimaryKey>> pagingIterator =
            new AbstractIterator<>() {
              private Object resumeKey;
              private boolean endOfData;

              @Override
              protected List<PrimaryKey> computeNext() {
                if (endOfData) {
                  return endOfData();
                }
                List<PrimaryKey> rows;
                  try {
                      rows = getData(resumeKey, PAGE_SIZE);
                  } catch (IOException e) {
                      throw new RuntimeException();
                  }

                  if (rows.isEmpty()) {
                  return endOfData();
                } else if (rows.size() < PAGE_SIZE) {
                  endOfData = true;
                } else {
                  resumeKey = rows.get(rows.size() - 1);
                }
                return rows;
              }

              private List<PrimaryKey> getData(Object startKey, int PAGE_SIZE) throws IOException {
                List<PrimaryKey> result = new ArrayList<>();
                if (startKey != null) {
                  if (startKey instanceof PartitionKey) {
                    iterator.seek(SerializationUtils.serialize((PartitionKey) startKey));
                  }
                } else {
                  iterator.seekToFirst();
                  if (iterator.hasNext()) {
                    var nextMap = iterator.peekNext();
                    if (SerializationUtils.deserialize(nextMap.getKey()) instanceof PartitionKey) {
                      var key = (PartitionKey) SerializationUtils.deserialize(nextMap.getKey());
                      Set<String> clusteringKeys = Utils.cborDecoderSet(
                              levelDBStore.get(nextMap.getKey())
                      );
                      for (var c : clusteringKeys) {
                        result.add(new PrimaryKey(key.getPartitionKey(), c));
                      }
                    }
                      iterator.next();
                  }
                }

                for (int i = 0; i < PAGE_SIZE/2; i++) {
                  if (iterator.hasNext()) {
                      var nextMap = iterator.peekNext();
                      if (SerializationUtils.deserialize(nextMap.getKey()) instanceof PartitionKey) {
                        var key = (PartitionKey) SerializationUtils.deserialize(nextMap.getKey());
                          Set<String> clusteringKeys = Utils.cborDecoderSet(
                                levelDBStore.get(nextMap.getKey())
                        );
                      for (var c : clusteringKeys) {
                        result.add(new PrimaryKey(key.getPartitionKey(), c));
                      }
                      }
                      iterator.next();
                    } else break;
                }
                return result;
              }
            };

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

    return pagingIterator;
}

    public Iterator<List<Object>> readPaginatedPartitionsMetadata() throws IOException {
        var methodName = new Throwable().getStackTrace()[0].getMethodName();
        var startTime = System.nanoTime();

        prepareSnapshot();
        ReadOptions ro = new ReadOptions();
        Options options = new Options();
        var levelDBStore1 = factory.open(new File(String.format("%s/ledger_v4_%s_pd.ldb-snapshot",
                properties.getProperty("LOCAL_STORAGE_PATH"),
                properties.getProperty("TILE"))), options);

        ro.snapshot(levelDBStore1.getSnapshot());
        DBIterator iterator = levelDBStore1.iterator();

        Iterator<List<Object>> pagingIterator = new AbstractIterator<>() {
            private Object resumeKey;
            private boolean endOfData;

            @Override
            protected List<Object> computeNext() {
                if (endOfData) {
                    return endOfData();
                }
                List<Object> rows = getData(resumeKey, PAGE_SIZE);
                if (rows.isEmpty()) {
                    return endOfData();
                } else if (rows.size() < PAGE_SIZE) {
                    endOfData = true;
                    try {
                        levelDBStore1.close();
                        deleteSnapshot();
                    } catch (IOException e) {
                        throw new RuntimeException();
                    }

                } else {
                    resumeKey = rows.get(rows.size() - 1);
                }
                return rows;
            }

            private List<Object> getData(Object startKey, int PAGE_SIZE) {
                List<Object> result = new ArrayList<>();
                if (startKey != null) {
                    iterator.seek(SerializationUtils.serialize((PrimaryKey) startKey));
                } else {
                    iterator.seekToFirst();
                    if (iterator.hasNext())
                    result.add(SerializationUtils.deserialize(iterator.peekNext().getKey()));
                }

                for (int i=0; i<PAGE_SIZE; i++) {
                    if (iterator.hasNext()) {
                        result.add(SerializationUtils.deserialize(iterator.peekNext().getKey()));
                        iterator.next();
                    } else break;
                }
                return result;
            }
        };

        var elapsedTime = System.nanoTime() - startTime;
        LOGGER.debug("Call {} : {} ms", methodName, Duration.ofNanos(elapsedTime).toMillis());

        return pagingIterator;

    }


}
