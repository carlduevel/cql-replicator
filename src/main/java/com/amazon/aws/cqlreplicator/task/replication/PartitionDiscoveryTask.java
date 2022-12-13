// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazon.aws.cqlreplicator.task.replication;

import com.amazon.aws.cqlreplicator.models.PartitionMetaData;
import com.amazon.aws.cqlreplicator.storage.*;
import com.amazon.aws.cqlreplicator.task.AbstractTask;
import com.amazon.aws.cqlreplicator.util.Utils;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.Row;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static com.amazon.aws.cqlreplicator.util.Utils.getDistributedRangesByTiles;

/**
 * The {@code PartitionDiscoveryTask} class provides partition key synchronization between Cassandra
 * cluster and Amazon Keyspaces by using <em>token range split</em>. This implementation makes ~
 * &scanAndCompare; scan Cassandra cluster and compare any token range of <em>n</em>.
 *
 * <p>The {@code PartitionDiscoveryTask} splits Cassandra token range in <em>m</em> tiles and each
 * instance of PartitionDiscoveryTask handles only one tile
 *
 * <p>
 */
public class PartitionDiscoveryTask extends AbstractTask {

  private static final Logger LOGGER = LoggerFactory.getLogger(PartitionDiscoveryTask.class);
  private static final Pattern REGEX_PIPE = Pattern.compile("\\|");
  private static int ADVANCED_CACHE_SIZE;
  private static SourceStorageOnCassandra sourceStorageOnCassandra;
  private static Map<String, LinkedHashMap<String, String>> metaData;
  private static LedgerStorageOnLevelDB ledgerStorageOnLevelDB;
  private final Properties config;

  /**
   * Constructor for PartitionDiscoveryTask.
   *
   * @param config the array to be sorted
   */
  public PartitionDiscoveryTask(Properties config) throws IOException {
    this.config = config;
    ADVANCED_CACHE_SIZE =
        Integer.parseInt(config.getProperty("EXTERNAL_MEMCACHED_PAGE_SIZE_PER_TILE"));
    sourceStorageOnCassandra = new SourceStorageOnCassandra(config);
    metaData = sourceStorageOnCassandra.getMetaData();
    ledgerStorageOnLevelDB = new LedgerStorageOnLevelDB(config);
  }

  private static List<Range<Long>> batches(Long start, Long end, Long batchSize){
    Long batchEnd = start;
    List<Range<Long>> batches = new ArrayList<>();
    for (var batchStart = start;batchStart <= end; batchStart=batchEnd+1) {
      batchEnd = Math.min(batchEnd + batchSize, end);
      Range<Long> range = Range.closed(batchStart, batchEnd);
      batches.add(range);
    }
    return batches;
  }

  /** Scan and compare partition keys. */
  private void scanAndCompare(
      List<ImmutablePair<String, String>> rangeList, CacheStorage pkCache, String[] pks)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {

    AdvancedCache<String> advancedCache = null;

    if (pkCache instanceof MemcachedCacheStorage) {
      advancedCache =
          new AdvancedCache<>(ADVANCED_CACHE_SIZE, pkCache) {
            @Override
            protected void flush(List<String> payload, CacheStorage cacheStorage)
                throws IOException, InterruptedException, ExecutionException, TimeoutException {
              var totalChunks = String.format("%s|%s", config.getProperty("TILE"), "totalChunks");
              var currentChunk = Integer.parseInt((String) cacheStorage.get(totalChunks));
              LOGGER.debug("{}:{}", totalChunks, currentChunk);
              var cborPayload = Utils.cborEncoder(payload);
              var compressedCborPayload = Utils.compress(cborPayload);
              var keyOfChunk =
                  String.format("%s|%s|%s", "pksChunk", config.getProperty("TILE"), currentChunk);
              pkCache.put(keyOfChunk, compressedCborPayload);
              ((MemcachedCacheStorage) pkCache).incrByOne(totalChunks);
            }
          };
    }

    boolean totalChunksExist =
        pkCache.containsKey(String.format("%s|%s", config.getProperty("TILE"), "totalChunks"));
    if (!totalChunksExist)
      pkCache.put(String.format("%s|%s", config.getProperty("TILE"), "totalChunks"), "0");

    Long batchSize = Long.valueOf(config.getProperty("READ_BATCH_SIZE"));

    var pksStr = String.join(",", pks);

    for (ImmutablePair<String, String> range : rangeList) {
      var rangeStart = Long.parseLong(range.left);
      var rangeEnd = Long.parseLong(range.right);
      List<Row> resultSetRange = new ArrayList<>();
      for (Range<Long> batch : batches(rangeStart,rangeEnd, batchSize)){
        resultSetRange.addAll(sourceStorageOnCassandra.findPartitionsByTokenRange(pksStr, batch.lowerEndpoint(),
                batch.upperEndpoint()));
      }
      LOGGER.trace("Processing a range: {} - {}", rangeStart, rangeEnd);
      for (Row eachResult : resultSetRange) {
        var i = 0;
        List<String> tmp = new ArrayList<>();

        for (String cl : pks) {
          var type = metaData.get("partition_key").get(cl);
          tmp.add(String.valueOf(eachResult.get(pks[i], Utils.getClassType(type.toUpperCase()))));
          i++;
        }

        var res = String.join("|", tmp);
        var flag = pkCache.containsKey(res);

        if (!flag) {
          pkCache.add(
              Integer.parseInt(config.getProperty("TILE")), res, Instant.now().toEpochMilli());

          var partitionMetaData =
              new PartitionMetaData(
                  Integer.parseInt(config.getProperty("TILE")),
                  config.getProperty("TARGET_KEYSPACE"),
                  config.getProperty("TARGET_TABLE"),
                  res);

          syncPartitionKeys(partitionMetaData);

          if (advancedCache != null) advancedCache.put(partitionMetaData.getPk());

          LOGGER.debug("Syncing a new partition key: {}", res);
        }
      }
    }

    if (advancedCache != null && advancedCache.getSize() > 0) {
      LOGGER.info("Flushing remainders: {}", advancedCache.getSize());
      advancedCache.doFlush();
    }

    LOGGER.info("Comparing stage is running");
  }

  private void syncPartitionKeys(PartitionMetaData partitionMetaData) {
    ledgerStorageOnLevelDB.writePartitionMetadata(partitionMetaData);
  }

  private void deletePartitions(String[] pks, CacheStorage pkCache, int chunk)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {

    var keyOfChunkFirst = String.format("%s|%s|%s", "pksChunk", config.getProperty("TILE"), chunk);
    var compressedPayloadFirst = (byte[]) pkCache.get(keyOfChunkFirst);
    var cborPayloadFirst = Utils.decompress(compressedPayloadFirst);
    var collection = Utils.cborDecoder(cborPayloadFirst);

    var finalClonedCollection = new ArrayList<>(collection);
    collection.forEach(
        key -> {
          BoundStatementBuilder boundStatementCassandraBuilder =
              sourceStorageOnCassandra.getCassandraPreparedStatement().boundStatementBuilder();

          if (pkCache instanceof MemcachedCacheStorage)
            LOGGER.debug("Processing partition key: {}", key);
          var pk = REGEX_PIPE.split((String) key);

          var i = 0;

          for (String cl : pks) {
            var type = metaData.get("partition_key").get(cl);
            try {
              boundStatementCassandraBuilder =
                  Utils.aggregateBuilder(type, cl, pk[i], boundStatementCassandraBuilder);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            i++;
          }

          List<Row> cassandraResult =
              sourceStorageOnCassandra.extract(boundStatementCassandraBuilder);

          // Found deleted partition key
          if (cassandraResult.size() == 0) {
            LOGGER.debug("Found deleted partition key {}", key);

            // Remove partition key from the cache
            if (pkCache instanceof MemcachedCacheStorage) {
              try {
                pkCache.remove(Integer.parseInt(config.getProperty("TILE")), (key));
              } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
              }
              finalClonedCollection.remove(key);
            }

            // Delete partition from Ledger
            ledgerStorageOnLevelDB.deletePartitionMetadata(
                new PartitionMetaData(
                    Integer.parseInt(config.getProperty("TILE")),
                    config.getProperty("TARGET_KEYSPACE"),
                    config.getProperty("TARGET_TABLE"),
                    (String) key));
          }
        });

    if (finalClonedCollection.size() < collection.size()) {
      var cborPayload = Utils.cborEncoder(finalClonedCollection);
      var compressedPayload = Utils.compress(cborPayload);
      var keyOfChunk = String.format("%s|%s|%s", "pksChunk", config.getProperty("TILE"), chunk);
      pkCache.put(keyOfChunk, compressedPayload);
    }

    if (finalClonedCollection.size() == 0) {
      var keyOfChunk = String.format("%s|%s", config.getProperty("TILE"), "totalChunks");
      ((MemcachedCacheStorage) pkCache).decrByOne(keyOfChunk);
    }
  }

  /**
   * Scan and remove deleted partition keys.
   *
   * @params rangeList, pkCache, pks the array to be sorted
   */
  private void scanAndRemove(CacheStorage pkCache, String[] pks, Utils.CassandraTaskTypes taskName)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {

    if (taskName.equals(Utils.CassandraTaskTypes.SYNC_DELETED_PARTITION_KEYS)) {
      LOGGER.info("Syncing deleted partition keys between C* and Amazon Keyspaces");
      if (pkCache instanceof MemcachedCacheStorage) {
        var totalChunks = String.format("%s|%s", config.getProperty("TILE"), "totalChunks");
        var chunks = Integer.parseInt(((String) pkCache.get(totalChunks)).trim());
        // remove each chunk of partition keys
        for (int chunk = 0; chunk < chunks; chunk++) {
          deletePartitions(pks, pkCache, chunk);
        }
      }
    }
  }

  /**
   * Perform partition key task.
   *
   * @param pkCache the array to be sorted
   */
  @Override
  protected void doPerformTask(CacheStorage pkCache, Utils.CassandraTaskTypes taskName)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {

    var pks = metaData.get("partition_key").keySet().toArray(new String[0]);

    List<ImmutablePair<String, String>> ranges = sourceStorageOnCassandra.getTokenRanges();
    var totalRanges = ranges.size();
    List<List<ImmutablePair<String, String>>> tiles =
        getDistributedRangesByTiles(ranges, Integer.parseInt(config.getProperty("TILES")));
    var currentTile = Integer.parseInt(config.getProperty("TILE"));
    List<ImmutablePair<String, String>> rangeList = tiles.get(currentTile);

    // if tiles = 0 we need to scan one range from one pkScanner, if tiles>0 we need to scan all
    // ranges from the pkScanner
    LOGGER.info("The number of ranges in the cassandra: {}", totalRanges);
    LOGGER.info("The number of ranges for the tile: {}", rangeList.size());
    LOGGER.info("The number of tiles: {}", tiles.size());
    LOGGER.info("The current tile: {}", currentTile);

    scanAndCompare(rangeList, (CacheStorage<String, Long>) pkCache, pks);
    if (config.getProperty("REPLICATE_DELETES").equals("true")) {
      scanAndRemove((CacheStorage<String, Long>) pkCache, pks, taskName);
    }

    LOGGER.info("Caching and comparing stage is completed");
    LOGGER.info(
        "The number of pre-loaded elements in the cache is {} ",
        pkCache.getSize(Integer.parseInt(config.getProperty("TILE"))));
  }
}
