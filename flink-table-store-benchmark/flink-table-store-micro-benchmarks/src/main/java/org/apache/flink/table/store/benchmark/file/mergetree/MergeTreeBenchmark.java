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

package org.apache.flink.table.store.benchmark.file.mergetree;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.benchmark.config.ConfigUtil;
import org.apache.flink.table.store.benchmark.config.FileBenchmarkOptions;
import org.apache.flink.table.store.file.KeyValue;
import org.apache.flink.table.store.file.compact.CompactResult;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.io.KeyValueFileReaderFactory;
import org.apache.flink.table.store.file.io.KeyValueFileWriterFactory;
import org.apache.flink.table.store.file.io.RollingFileWriter;
import org.apache.flink.table.store.file.memory.HeapMemorySegmentPool;
import org.apache.flink.table.store.file.mergetree.Levels;
import org.apache.flink.table.store.file.mergetree.MergeTreeReaders;
import org.apache.flink.table.store.file.mergetree.MergeTreeWriter;
import org.apache.flink.table.store.file.mergetree.SortedRun;
import org.apache.flink.table.store.file.mergetree.compact.AbstractCompactRewriter;
import org.apache.flink.table.store.file.mergetree.compact.CompactRewriter;
import org.apache.flink.table.store.file.mergetree.compact.CompactStrategy;
import org.apache.flink.table.store.file.mergetree.compact.DeduplicateMergeFunction;
import org.apache.flink.table.store.file.mergetree.compact.MergeTreeCompactManager;
import org.apache.flink.table.store.file.mergetree.compact.UniversalCompaction;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.utils.FileStorePathFactory;
import org.apache.flink.table.store.file.utils.RecordReaderIterator;
import org.apache.flink.table.store.file.utils.RecordWriter;
import org.apache.flink.table.store.format.FileFormat;
import org.apache.flink.table.store.utils.BinaryRowDataUtil;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;

import org.apache.commons.io.FileUtils;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static org.apache.flink.table.store.file.utils.FileStorePathFactory.PARTITION_DEFAULT_NAME;
import static org.apache.flink.util.Preconditions.checkState;

/** Base class for merge tree benchmark. */
@SuppressWarnings("MethodMayBeStatic")
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@Fork(3)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
public class MergeTreeBenchmark {
    @Param({"avro", "orc", "parquet"})
    protected String format;

    protected ExecutorService service;
    protected File file;
    protected Comparator<RowData> comparator;
    protected CoreOptions options;
    protected KeyValueFileReaderFactory readerFactory;
    private KeyValueFileReaderFactory compactReaderFactory;
    protected KeyValueFileWriterFactory writerFactory;
    private KeyValueFileWriterFactory compactWriterFactory;
    protected List<DataFileMeta> compactedFiles;

    protected RecordWriter<KeyValue> writer;
    protected int batchCount;
    protected int countPerBatch;
    protected KeyValue kv = new KeyValue();
    protected long sequenceNumber = 0;

    protected void createRecordWriter() {
        Configuration configuration = ConfigUtil.loadBenchMarkConf();
        batchCount = configuration.get(FileBenchmarkOptions.WRITER_BATCH_COUNT);
        countPerBatch = configuration.get(FileBenchmarkOptions.WRITER_RECORD_COUNT_PER_BATCH);
        service = Executors.newSingleThreadExecutor();
        file = new File(ConfigUtil.createFileDataDir(configuration));
        comparator = Comparator.comparingInt(o -> o.getInt(0));
        compactedFiles = new ArrayList<>();

        Path path = new org.apache.flink.core.fs.Path(file.toURI());
        FileStorePathFactory pathFactory =
                new FileStorePathFactory(
                        path, RowType.of(), configuration.get(PARTITION_DEFAULT_NAME), format);
        writer = recreateMergeTree(configuration, path, pathFactory);
    }

    private RecordWriter<KeyValue> recreateMergeTree(
            Configuration configuration, Path path, FileStorePathFactory pathFactory) {
        options = new CoreOptions(configuration);
        RowType keyType = new RowType(singletonList(new RowType.RowField("k", new IntType())));
        RowType valueType = new RowType(singletonList(new RowType.RowField("v", new IntType())));
        FileFormat flushingFormat = FileFormat.fromIdentifier(format, new Configuration());
        KeyValueFileReaderFactory.Builder readerBuilder =
                KeyValueFileReaderFactory.builder(
                        new SchemaManager(path),
                        0,
                        keyType,
                        valueType,
                        flushingFormat,
                        pathFactory);
        readerFactory = readerBuilder.build(BinaryRowDataUtil.EMPTY_ROW, 0);
        compactReaderFactory = readerBuilder.build(BinaryRowDataUtil.EMPTY_ROW, 0);
        KeyValueFileWriterFactory.Builder writerBuilder =
                KeyValueFileWriterFactory.builder(
                        0,
                        keyType,
                        valueType,
                        flushingFormat,
                        pathFactory,
                        options.targetFileSize());
        writerFactory = writerBuilder.build(BinaryRowDataUtil.EMPTY_ROW, 0);
        compactWriterFactory = writerBuilder.build(BinaryRowDataUtil.EMPTY_ROW, 0);
        return createMergeTreeWriter(Collections.emptyList());
    }

    private MergeTreeWriter createMergeTreeWriter(List<DataFileMeta> files) {
        long maxSequenceNumber =
                files.stream().map(DataFileMeta::maxSequenceNumber).max(Long::compare).orElse(-1L);
        MergeTreeWriter writer =
                new MergeTreeWriter(
                        false,
                        128,
                        null,
                        createCompactManager(service, files),
                        maxSequenceNumber,
                        comparator,
                        new DeduplicateMergeFunction(),
                        writerFactory,
                        options.commitForceCompact(),
                        CoreOptions.ChangelogProducer.NONE);
        writer.setMemoryPool(
                new HeapMemorySegmentPool(options.writeBufferSize(), options.pageSize()));
        return writer;
    }

    private MergeTreeCompactManager createCompactManager(
            ExecutorService compactExecutor, List<DataFileMeta> files) {
        CompactStrategy strategy =
                new UniversalCompaction(
                        options.maxSizeAmplificationPercent(),
                        options.sortedRunSizeRatio(),
                        options.numSortedRunCompactionTrigger(),
                        options.maxSortedRunNum());
        CompactRewriter rewriter = new TestCompactRewriter();
        return new MergeTreeCompactManager(
                compactExecutor,
                new Levels(comparator, files, options.numLevels()),
                strategy,
                comparator,
                options.targetFileSize(),
                options.numSortedRunStopTrigger(),
                rewriter);
    }

    protected void mergeCompacted(
            Set<String> newFileNames,
            List<DataFileMeta> compactedFiles,
            RecordWriter.CommitIncrement increment) {
        increment.newFilesIncrement().newFiles().stream()
                .map(DataFileMeta::fileName)
                .forEach(newFileNames::add);
        compactedFiles.addAll(increment.newFilesIncrement().newFiles());
        Set<String> afterFiles =
                increment.compactIncrement().compactAfter().stream()
                        .map(DataFileMeta::fileName)
                        .collect(Collectors.toSet());
        for (DataFileMeta file : increment.compactIncrement().compactBefore()) {
            checkState(compactedFiles.remove(file));
            // See MergeTreeWriter.updateCompactResult
            if (!newFileNames.contains(file.fileName()) && !afterFiles.contains(file.fileName())) {
                compactWriterFactory.deleteFile(file.fileName());
            }
        }
        compactedFiles.addAll(increment.compactIncrement().compactAfter());
    }

    protected void cleanUp() throws Exception {
        if (file != null) {
            FileUtils.forceDeleteOnExit(file);
            file = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
        compactedFiles.clear();
    }

    /** Key value data to be written in {@link MergeTreeWriterBenchmark}. */
    @State(Scope.Thread)
    public static class KeyValueData {
        GenericRowData key;
        RowKind kind;
        GenericRowData value;

        @Setup(Level.Invocation)
        public void kvSetup() {
            key = new GenericRowData(1);
            key.setField(0, ThreadLocalRandom.current().nextInt());

            kind = RowKind.INSERT;

            value = new GenericRowData(1);
            value.setField(0, ThreadLocalRandom.current().nextInt());
        }

        @TearDown(Level.Invocation)
        public void kvTearDown() {
            key = null;
            kind = null;
            value = null;
        }
    }

    private class TestCompactRewriter extends AbstractCompactRewriter {

        @Override
        public CompactResult rewrite(
                int outputLevel, boolean dropDelete, List<List<SortedRun>> sections)
                throws Exception {
            RollingFileWriter<KeyValue, DataFileMeta> writer =
                    compactWriterFactory.createRollingMergeTreeFileWriter(outputLevel);
            writer.write(
                    new RecordReaderIterator<>(
                            MergeTreeReaders.readerForMergeTree(
                                    sections,
                                    dropDelete,
                                    compactReaderFactory,
                                    comparator,
                                    new DeduplicateMergeFunction())));
            writer.close();
            return new CompactResult(extractFilesFromSections(sections), writer.result());
        }
    }
}
