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

package org.apache.flink.table.store.connector.source;

import org.apache.flink.api.connector.source.SourceEvent;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.table.source.DataSplit;
import org.apache.flink.table.store.table.source.DataTableScan;
import org.apache.flink.table.store.table.source.SnapshotEnumerator;
import org.apache.flink.table.store.table.source.SnapshotEnumerator.EnumeratorResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.apache.flink.table.store.connector.source.PendingSplitsCheckpoint.INVALID_SNAPSHOT;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/** A continuously monitoring enumerator. */
public class ContinuousFileSplitEnumerator
        implements SplitEnumerator<FileStoreSourceSplit, PendingSplitsCheckpoint> {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuousFileSplitEnumerator.class);

    private final SplitEnumeratorContext<FileStoreSourceSplit> context;

    private final Map<Integer, Queue<FileStoreSourceSplit>> bucketSplits;

    private final long discoveryInterval;

    private final Set<Integer> readersAwaitingSplit;

    private final FileStoreSourceSplitGenerator splitGenerator;

    private final SnapshotEnumerator snapshotEnumerator;

    private Long currentSnapshotId;

    public ContinuousFileSplitEnumerator(
            SplitEnumeratorContext<FileStoreSourceSplit> context,
            Path location,
            DataTableScan scan,
            CoreOptions.ChangelogProducer changelogProducer,
            Collection<FileStoreSourceSplit> remainSplits,
            long currentSnapshotId,
            long discoveryInterval) {
        checkArgument(discoveryInterval > 0L);
        this.context = checkNotNull(context);
        this.bucketSplits = new HashMap<>();
        addSplits(remainSplits);
        this.currentSnapshotId = currentSnapshotId;
        this.discoveryInterval = discoveryInterval;
        this.readersAwaitingSplit = new HashSet<>();
        this.splitGenerator = new FileStoreSourceSplitGenerator();
        this.snapshotEnumerator =
                new SnapshotEnumerator(location, scan, changelogProducer, currentSnapshotId);
    }

    private void addSplits(Collection<FileStoreSourceSplit> splits) {
        splits.forEach(this::addSplit);
    }

    private void addSplit(FileStoreSourceSplit split) {
        bucketSplits
                .computeIfAbsent(((DataSplit) split.split()).bucket(), i -> new LinkedList<>())
                .add(split);
    }

    @Override
    public void start() {
        context.callAsync(
                snapshotEnumerator,
                this::processDiscoveredSplits,
                discoveryInterval,
                discoveryInterval);
    }

    @Override
    public void close() throws IOException {
        // no resources to close
    }

    @Override
    public void addReader(int subtaskId) {
        // this source is purely lazy-pull-based, nothing to do upon registration
    }

    @Override
    public void handleSplitRequest(int subtaskId, @Nullable String requesterHostname) {
        readersAwaitingSplit.add(subtaskId);
        assignSplits();
    }

    @Override
    public void handleSourceEvent(int subtaskId, SourceEvent sourceEvent) {
        LOG.error("Received unrecognized event: {}", sourceEvent);
    }

    @Override
    public void addSplitsBack(List<FileStoreSourceSplit> splits, int subtaskId) {
        LOG.debug("File Source Enumerator adds splits back: {}", splits);
        addSplits(splits);
    }

    @Override
    public PendingSplitsCheckpoint snapshotState(long checkpointId) {
        List<FileStoreSourceSplit> splits = new ArrayList<>();
        bucketSplits.values().forEach(splits::addAll);
        final PendingSplitsCheckpoint checkpoint =
                new PendingSplitsCheckpoint(
                        splits, currentSnapshotId == null ? INVALID_SNAPSHOT : currentSnapshotId);

        LOG.debug("Source Checkpoint is {}", checkpoint);
        return checkpoint;
    }

    // ------------------------------------------------------------------------

    private void processDiscoveredSplits(@Nullable EnumeratorResult result, Throwable error) {
        if (error != null) {
            LOG.error("Failed to enumerate files", error);
            return;
        }

        if (result == null) {
            return;
        }

        currentSnapshotId = result.snapshotId;
        addSplits(splitGenerator.createSplits(result.plan));
        assignSplits();
    }

    private void assignSplits() {
        bucketSplits.forEach(
                (bucket, splits) -> {
                    if (splits.size() > 0) {
                        // To ensure the order of consumption, the data of the same bucket is given
                        // to a task to be consumed.
                        int task = bucket % context.currentParallelism();
                        if (readersAwaitingSplit.remove(task)) {
                            // if the reader that requested another split has failed in the
                            // meantime, remove
                            // it from the list of waiting readers
                            if (!context.registeredReaders().containsKey(task)) {
                                return;
                            }
                            context.assignSplit(splits.poll(), task);
                        }
                    }
                });
    }
}
