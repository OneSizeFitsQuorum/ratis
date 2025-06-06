/*
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
package org.apache.ratis.server.raftlog.memory;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.server.metrics.RaftLogMetricsBase;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.server.raftlog.LogProtoUtils;
import org.apache.ratis.server.raftlog.RaftLogBase;
import org.apache.ratis.server.raftlog.LogEntryHeader;
import org.apache.ratis.server.raftlog.RaftLogIOException;
import org.apache.ratis.server.storage.RaftStorageMetadata;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.util.AutoCloseableLock;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ReferenceCountedObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A simple RaftLog implementation in memory. Used only for testing.
 */
public class MemoryRaftLog extends RaftLogBase {
  static class EntryList {
    private final List<LogEntryProto> entries = new ArrayList<>();

    LogEntryProto get(int i) {
      return i >= 0 && i < entries.size() ? entries.get(i) : null;
    }

    TermIndex getTermIndex(int i) {
      return TermIndex.valueOf(get(i));
    }

    private LogEntryHeader getLogEntryHeader(int i) {
      return LogEntryHeader.valueOf(get(i));
    }

    int size() {
      return entries.size();
    }

    void truncate(int index) {
      if (entries.size() > index) {
        clear(index, entries.size());
      }
    }

    void purge(int index) {
      if (entries.size() > index) {
        clear(0, index);
      }
    }

    void clear(int from, int to) {
      entries.subList(from, to).clear();
    }

    void add(LogEntryProto entryRef) {
      entries.add(entryRef);
    }
  }

  private final EntryList entries = new EntryList();
  private final AtomicReference<RaftStorageMetadata> metadata = new AtomicReference<>(RaftStorageMetadata.getDefault());
  private final RaftLogMetricsBase metrics;

  public MemoryRaftLog(RaftGroupMemberId memberId,
                       LongSupplier commitIndexSupplier,
                       RaftProperties properties) {
    super(memberId, commitIndexSupplier, properties);
    this.metrics = new RaftLogMetricsBase(memberId);
  }

  @Override
  public void close() throws IOException {
    super.close();
    metrics.unregister();
  }

  @Override
  public RaftLogMetricsBase getRaftLogMetrics() {
    return metrics;
  }

  @Override
  public LogEntryProto get(long index) throws RaftLogIOException {
    final ReferenceCountedObject<LogEntryProto> ref = retainLog(index);
    try {
      return LogProtoUtils.copy(ref.get());
    } finally {
      ref.release();
    }
  }

  @Override
  public ReferenceCountedObject<LogEntryProto> retainLog(long index) {
    checkLogState();
    try (AutoCloseableLock readLock = readLock()) {
      final LogEntryProto entry = entries.get(Math.toIntExact(index));
      final ReferenceCountedObject<LogEntryProto> ref = ReferenceCountedObject.wrap(entry);
      ref.retain();
      return ref;
    }
  }

  @Override
  public EntryWithData getEntryWithData(long index) throws RaftLogIOException {
    throw new UnsupportedOperationException("Use retainEntryWithData(" + index + ") instead.");
  }

  @Override
  public ReferenceCountedObject<EntryWithData> retainEntryWithData(long index) {
    final ReferenceCountedObject<LogEntryProto> ref = retainLog(index);
    return newEntryWithData(ref);
  }

  @Override
  public TermIndex getTermIndex(long index) {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      return entries.getTermIndex(Math.toIntExact(index));
    }
  }

  @Override
  public LogEntryHeader[] getEntries(long startIndex, long endIndex) {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      if (startIndex >= entries.size()) {
        return null;
      }
      final int from = Math.toIntExact(startIndex);
      final int to = Math.toIntExact(Math.min(entries.size(), endIndex));
      final LogEntryHeader[] headers = new LogEntryHeader[to - from];
      for (int i = 0; i < headers.length; i++) {
        headers[i] = entries.getLogEntryHeader(i);
      }
      return headers;
    }
  }

  @Override
  protected CompletableFuture<Long> truncateImpl(long index) {
    checkLogState();
    try(AutoCloseableLock writeLock = writeLock()) {
      Preconditions.assertTrue(index >= 0);
      entries.truncate(Math.toIntExact(index));
    }
    return CompletableFuture.completedFuture(index);
  }


  @Override
  protected CompletableFuture<Long> purgeImpl(long index) {
    try (AutoCloseableLock writeLock = writeLock()) {
      Preconditions.assertTrue(index >= 0);
      entries.purge(Math.toIntExact(index));
    }
    return CompletableFuture.completedFuture(index);
  }

  @Override
  public TermIndex getLastEntryTermIndex() {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      return entries.getTermIndex(entries.size() - 1);
    }
  }

  @Override
  protected CompletableFuture<Long> appendEntryImpl(ReferenceCountedObject<LogEntryProto> entryRef,
      TransactionContext context) {
    checkLogState();
    LogEntryProto entry = entryRef.retain();
    try (AutoCloseableLock writeLock = writeLock()) {
      validateLogEntry(entry);
      entries.add(entry);
    } finally {
      entryRef.release();
    }
    return CompletableFuture.completedFuture(entry.getIndex());
  }

  @Override
  public long getStartIndex() {
    return entries.size() == 0? INVALID_LOG_INDEX: entries.getTermIndex(0).getIndex();
  }

  @Override
  public List<CompletableFuture<Long>> appendImpl(ReferenceCountedObject<List<LogEntryProto>> entriesRef) {
    checkLogState();
    final List<LogEntryProto> logEntryProtos = entriesRef.retain();
    if (logEntryProtos == null || logEntryProtos.isEmpty()) {
      entriesRef.release();
      return Collections.emptyList();
    }
    try (AutoCloseableLock writeLock = writeLock()) {
      // Before truncating the entries, we first need to check if some
      // entries are duplicated. If the leader sends entry 6, entry 7, then
      // entry 6 again, without this check the follower may truncate entry 7
      // when receiving entry 6 again. Then before the leader detects this
      // truncation in the next appendEntries RPC, leader may think entry 7 has
      // been committed but in the system the entry has not been committed to
      // the quorum of peers' disks.
      boolean toTruncate = false;
      int truncateIndex = (int) logEntryProtos.get(0).getIndex();
      int index = 0;
      for (; truncateIndex < getNextIndex() && index < logEntryProtos.size();
           index++, truncateIndex++) {
        if (this.entries.get(truncateIndex).getTerm() !=
            logEntryProtos.get(index).getTerm()) {
          toTruncate = true;
          break;
        }
      }
      final List<CompletableFuture<Long>> futures;
      if (toTruncate) {
        futures = new ArrayList<>(logEntryProtos.size() - index + 1);
        futures.add(truncate(truncateIndex));
      } else {
        futures = new ArrayList<>(logEntryProtos.size() - index);
      }
      for (int i = index; i < logEntryProtos.size(); i++) {
        LogEntryProto logEntryProto = logEntryProtos.get(i);
        entries.add(LogProtoUtils.copy(logEntryProto));
        futures.add(CompletableFuture.completedFuture(logEntryProto.getIndex()));
      }
      return futures;
    } finally {
      entriesRef.release();
    }
  }

  public String getEntryString() {
    return "entries=" + entries;
  }

  @Override
  public long getFlushIndex() {
    return getNextIndex() - 1;
  }

  @Override
  public void persistMetadata(RaftStorageMetadata newMetadata) {
    metadata.set(newMetadata);
  }

  @Override
  public RaftStorageMetadata loadMetadata() {
    return metadata.get();
  }

  @Override
  public CompletableFuture<Long> onSnapshotInstalled(long lastSnapshotIndex) {
    return CompletableFuture.completedFuture(lastSnapshotIndex);
    // do nothing
  }
}
