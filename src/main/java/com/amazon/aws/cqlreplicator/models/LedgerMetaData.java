// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazon.aws.cqlreplicator.models;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

/** Defines ledger's metadata */
public class LedgerMetaData {
  private final int tile;
  private final String partitionKeys;
  private final String clusteringColumns;
  private final String keyspaceName;
  private final String tableName;
  private final long lastRun;
  private final long lastWriteTime;

  public LedgerMetaData(
      final String partitionKeys,
      final String clusteringColumns,
      final String keyspaceName,
      final String tableName,
      final int tile,
      final long lastRun,
      final long lastWriteTime) {
    this.partitionKeys = partitionKeys;
    this.clusteringColumns = clusteringColumns;
    this.keyspaceName = keyspaceName;
    this.tableName = tableName;
    this.lastRun = lastRun;
    this.lastWriteTime = lastWriteTime;
    this.tile = tile;
  }

  public String getPartitionKeys() {
    return partitionKeys;
  }

  public String getClusteringColumns() {
    return clusteringColumns;
  }

  public String getKeyspaceName() {
    return keyspaceName;
  }

  public String getTableName() {
    return tableName;
  }

  public long getLastRun() {
    return lastRun;
  }

  public long getLastWriteTime() {
    return lastWriteTime;
  }

  public int getTile() {
    return tile;
  }

  @Override
  public String toString() {
    return ReflectionToStringBuilder.toString(this);
  }
}
