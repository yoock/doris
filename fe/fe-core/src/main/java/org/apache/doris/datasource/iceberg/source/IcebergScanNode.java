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

package org.apache.doris.datasource.iceberg.source;

import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.FunctionCallExpr;
import org.apache.doris.analysis.TableSnapshot;
import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.UserException;
import org.apache.doris.common.security.authentication.PreExecutionAuthenticator;
import org.apache.doris.common.util.LocationPath;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.datasource.ExternalTable;
import org.apache.doris.datasource.FileQueryScanNode;
import org.apache.doris.datasource.TableFormatType;
import org.apache.doris.datasource.hive.HMSExternalTable;
import org.apache.doris.datasource.hive.HiveMetaStoreClientHelper;
import org.apache.doris.datasource.iceberg.IcebergExternalCatalog;
import org.apache.doris.datasource.iceberg.IcebergExternalTable;
import org.apache.doris.datasource.iceberg.IcebergUtils;
import org.apache.doris.planner.PlanNodeId;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.spi.Split;
import org.apache.doris.statistics.StatisticalType;
import org.apache.doris.thrift.TExplainLevel;
import org.apache.doris.thrift.TFileFormatType;
import org.apache.doris.thrift.TFileRangeDesc;
import org.apache.doris.thrift.TIcebergDeleteFileDesc;
import org.apache.doris.thrift.TIcebergFileDesc;
import org.apache.doris.thrift.TPlanNode;
import org.apache.doris.thrift.TPushAggOp;
import org.apache.doris.thrift.TTableFormatFileDesc;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;
import org.apache.iceberg.util.SnapshotUtil;
import org.apache.iceberg.util.TableScanUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class IcebergScanNode extends FileQueryScanNode {

    public static final int MIN_DELETE_FILE_SUPPORT_VERSION = 2;

    private IcebergSource source;
    private Table icebergTable;
    private List<String> pushdownIcebergPredicates = Lists.newArrayList();
    private boolean pushDownCount = false;
    private static final long COUNT_WITH_PARALLEL_SPLITS = 10000;
    private long targetSplitSize;
    private ConcurrentHashMap.KeySetView<Object, Boolean> partitionPathSet;
    private boolean isPartitionedTable;
    private int formatVersion;
    private PreExecutionAuthenticator preExecutionAuthenticator;

    /**
     * External file scan node for Query iceberg table
     * needCheckColumnPriv: Some of ExternalFileScanNode do not need to check column priv
     * eg: s3 tvf
     * These scan nodes do not have corresponding catalog/database/table info, so no need to do priv check
     */
    public IcebergScanNode(PlanNodeId id, TupleDescriptor desc, boolean needCheckColumnPriv) {
        super(id, desc, "ICEBERG_SCAN_NODE", StatisticalType.ICEBERG_SCAN_NODE, needCheckColumnPriv);

        ExternalTable table = (ExternalTable) desc.getTable();
        if (table instanceof HMSExternalTable) {
            source = new IcebergHMSSource((HMSExternalTable) table, desc, columnNameToRange);
        } else if (table instanceof IcebergExternalTable) {
            String catalogType = ((IcebergExternalTable) table).getIcebergCatalogType();
            switch (catalogType) {
                case IcebergExternalCatalog.ICEBERG_HMS:
                case IcebergExternalCatalog.ICEBERG_REST:
                case IcebergExternalCatalog.ICEBERG_DLF:
                case IcebergExternalCatalog.ICEBERG_GLUE:
                case IcebergExternalCatalog.ICEBERG_HADOOP:
                    source = new IcebergApiSource((IcebergExternalTable) table, desc, columnNameToRange);
                    break;
                default:
                    Preconditions.checkState(false, "Unknown iceberg catalog type: " + catalogType);
                    break;
            }
        }
        Preconditions.checkNotNull(source);
    }

    @Override
    protected void doInitialize() throws UserException {
        icebergTable = source.getIcebergTable();
        targetSplitSize = getRealFileSplitSize(0);
        partitionPathSet = ConcurrentHashMap.newKeySet();
        isPartitionedTable = icebergTable.spec().isPartitioned();
        formatVersion = ((BaseTable) icebergTable).operations().current().formatVersion();
        preExecutionAuthenticator = source.getCatalog().getPreExecutionAuthenticator();
        super.doInitialize();
    }

    @Override
    protected void setScanParams(TFileRangeDesc rangeDesc, Split split) {
        if (split instanceof IcebergSplit) {
            setIcebergParams(rangeDesc, (IcebergSplit) split);
        }
    }

    private void setIcebergParams(TFileRangeDesc rangeDesc, IcebergSplit icebergSplit) {
        TTableFormatFileDesc tableFormatFileDesc = new TTableFormatFileDesc();
        tableFormatFileDesc.setTableFormatType(icebergSplit.getTableFormatType().value());
        TIcebergFileDesc fileDesc = new TIcebergFileDesc();
        fileDesc.setFormatVersion(formatVersion);
        fileDesc.setOriginalFilePath(icebergSplit.getOriginalPath());
        if (pushDownCount) {
            fileDesc.setRowCount(icebergSplit.getRowCount());
        }
        if (formatVersion < MIN_DELETE_FILE_SUPPORT_VERSION) {
            fileDesc.setContent(FileContent.DATA.id());
        } else {
            for (IcebergDeleteFileFilter filter : icebergSplit.getDeleteFileFilters()) {
                TIcebergDeleteFileDesc deleteFileDesc = new TIcebergDeleteFileDesc();
                String deleteFilePath = filter.getDeleteFilePath();
                LocationPath locationPath = new LocationPath(deleteFilePath, icebergSplit.getConfig());
                deleteFileDesc.setPath(locationPath.toStorageLocation().toString());
                if (filter instanceof IcebergDeleteFileFilter.PositionDelete) {
                    IcebergDeleteFileFilter.PositionDelete positionDelete =
                            (IcebergDeleteFileFilter.PositionDelete) filter;
                    OptionalLong lowerBound = positionDelete.getPositionLowerBound();
                    OptionalLong upperBound = positionDelete.getPositionUpperBound();
                    if (lowerBound.isPresent()) {
                        deleteFileDesc.setPositionLowerBound(lowerBound.getAsLong());
                    }
                    if (upperBound.isPresent()) {
                        deleteFileDesc.setPositionUpperBound(upperBound.getAsLong());
                    }
                    deleteFileDesc.setContent(FileContent.POSITION_DELETES.id());
                } else {
                    IcebergDeleteFileFilter.EqualityDelete equalityDelete =
                            (IcebergDeleteFileFilter.EqualityDelete) filter;
                    deleteFileDesc.setFieldIds(equalityDelete.getFieldIds());
                    deleteFileDesc.setContent(FileContent.EQUALITY_DELETES.id());
                }
                fileDesc.addToDeleteFiles(deleteFileDesc);
            }
        }
        tableFormatFileDesc.setIcebergParams(fileDesc);
        rangeDesc.setTableFormatParams(tableFormatFileDesc);
    }

    @Override

    public List<Split> getSplits(int numBackends) throws UserException {
        try {
            return preExecutionAuthenticator.execute(() -> doGetSplits(numBackends));
        } catch (Exception e) {
            throw new RuntimeException(ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    @Override
    public void startSplit(int numBackends) throws UserException {
        try {
            preExecutionAuthenticator.execute(() -> {
                doStartSplit();
                return null;
            });
        } catch (Exception e) {
            throw new UserException(e.getMessage(), e);
        }
    }

    public void doStartSplit() throws UserException {
        TableScan scan = createTableScan();
        CompletableFuture.runAsync(() -> {
            try {
                CloseableIterable<FileScanTask> fileScanTasks = planFileScanTask(scan);
                // 1. this task should stop when all splits are assigned
                // 2. if we want to stop this plan, we can close the fileScanTasks to stop
                splitAssignment.addCloseable(fileScanTasks);

                fileScanTasks.forEach(fileScanTask -> {
                    splitAssignment.addToQueue(Lists.newArrayList(createIcebergSplit(fileScanTask)));
                });

                splitAssignment.finishSchedule();
            } catch (Exception e) {
                splitAssignment.setException(new UserException(e.getMessage(), e));
            }
        });
    }

    private TableScan createTableScan() throws UserException {
        TableScan scan = icebergTable.newScan();

        // set snapshot
        Long snapshotId = getSpecifiedSnapshot();
        if (snapshotId != null) {
            scan = scan.useSnapshot(snapshotId);
        }

        // set filter
        List<Expression> expressions = new ArrayList<>();
        for (Expr conjunct : conjuncts) {
            Expression expression = IcebergUtils.convertToIcebergExpr(conjunct, icebergTable.schema());
            if (expression != null) {
                expressions.add(expression);
            }
        }
        for (Expression predicate : expressions) {
            scan = scan.filter(predicate);
            this.pushdownIcebergPredicates.add(predicate.toString());
        }

        scan = scan.planWith(source.getCatalog().getThreadPool());

        return scan;
    }

    public CloseableIterable<FileScanTask> planFileScanTask(TableScan scan) {
        long targetSplitSize = getRealFileSplitSize(0);
        CloseableIterable<FileScanTask> splitFiles;
        try {
            splitFiles = TableScanUtil.splitFiles(scan.planFiles(), targetSplitSize);
        } catch (NullPointerException e) {
            /*
        Caused by: java.lang.NullPointerException: Type cannot be null
            at org.apache.iceberg.relocated.com.google.common.base.Preconditions.checkNotNull
                (Preconditions.java:921) ~[iceberg-bundled-guava-1.4.3.jar:?]
            at org.apache.iceberg.types.Types$NestedField.<init>(Types.java:447) ~[iceberg-api-1.4.3.jar:?]
            at org.apache.iceberg.types.Types$NestedField.optional(Types.java:416) ~[iceberg-api-1.4.3.jar:?]
            at org.apache.iceberg.PartitionSpec.partitionType(PartitionSpec.java:132) ~[iceberg-api-1.4.3.jar:?]
            at org.apache.iceberg.DeleteFileIndex.lambda$new$0(DeleteFileIndex.java:97) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.relocated.com.google.common.collect.RegularImmutableMap.forEach
                (RegularImmutableMap.java:297) ~[iceberg-bundled-guava-1.4.3.jar:?]
            at org.apache.iceberg.DeleteFileIndex.<init>(DeleteFileIndex.java:97) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.DeleteFileIndex.<init>(DeleteFileIndex.java:71) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.DeleteFileIndex$Builder.build(DeleteFileIndex.java:578) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.ManifestGroup.plan(ManifestGroup.java:183) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.ManifestGroup.planFiles(ManifestGroup.java:170) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.DataTableScan.doPlanFiles(DataTableScan.java:89) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.iceberg.SnapshotScan.planFiles(SnapshotScan.java:139) ~[iceberg-core-1.4.3.jar:?]
            at org.apache.doris.datasource.iceberg.source.IcebergScanNode.doGetSplits
                (IcebergScanNode.java:209) ~[doris-fe.jar:1.2-SNAPSHOT]
        EXAMPLE:
             CREATE TABLE iceberg_tb(col1 INT,col2 STRING) USING ICEBERG PARTITIONED BY (bucket(10,col2));
             INSERT INTO iceberg_tb VALUES( ... );
             ALTER  TABLE iceberg_tb DROP PARTITION FIELD bucket(10,col2);
             ALTER TABLE iceberg_tb DROP COLUMNS col2 STRING;
        Link: https://github.com/apache/iceberg/pull/10755
        */
            LOG.warn("Iceberg TableScanUtil.splitFiles throw NullPointerException. Cause : ", e);
            throw new NotSupportedException("Unable to read Iceberg table with dropped old partition column.");
        }
        return splitFiles;
    }

    private Split createIcebergSplit(FileScanTask fileScanTask) {
        if (isPartitionedTable) {
            StructLike structLike = fileScanTask.file().partition();
            // Counts the number of partitions read
            partitionPathSet.add(structLike.toString());
        }
        String originalPath = fileScanTask.file().path().toString();
        LocationPath locationPath = new LocationPath(originalPath, source.getCatalog().getProperties());
        IcebergSplit split = new IcebergSplit(
                locationPath,
                fileScanTask.start(),
                fileScanTask.length(),
                fileScanTask.file().fileSizeInBytes(),
                new String[0],
                formatVersion,
                source.getCatalog().getProperties(),
                new ArrayList<>(),
                originalPath);
        if (!fileScanTask.deletes().isEmpty()) {
            split.setDeleteFileFilters(getDeleteFileFilters(fileScanTask));
        }
        split.setTableFormatType(TableFormatType.ICEBERG);
        split.setTargetSplitSize(targetSplitSize);
        return split;
    }

    private List<Split> doGetSplits(int numBackends) throws UserException {

        TableScan scan = createTableScan();
        List<Split> splits = new ArrayList<>();

        try (CloseableIterable<FileScanTask> fileScanTasks = planFileScanTask(scan)) {
            fileScanTasks.forEach(taskGrp ->  {
                Split split = createIcebergSplit(taskGrp);
                splits.add(split);
            });
        } catch (IOException e) {
            throw new UserException(e.getMessage(), e.getCause());
        }

        TPushAggOp aggOp = getPushDownAggNoGroupingOp();
        if (aggOp.equals(TPushAggOp.COUNT)) {
            // we can create a special empty split and skip the plan process
            if (splits.isEmpty()) {
                return splits;
            }
            long countFromSnapshot = getCountFromSnapshot();
            if (countFromSnapshot >= 0) {
                pushDownCount = true;
                List<Split> pushDownCountSplits;
                if (countFromSnapshot > COUNT_WITH_PARALLEL_SPLITS) {
                    int parallelNum = ConnectContext.get().getSessionVariable().getParallelExecInstanceNum();
                    pushDownCountSplits = splits.subList(0, Math.min(splits.size(), parallelNum));
                } else {
                    pushDownCountSplits = Collections.singletonList(splits.get(0));
                }
                assignCountToSplits(pushDownCountSplits, countFromSnapshot);
                return pushDownCountSplits;
            }
        }

        selectedPartitionNum = partitionPathSet.size();
        splits.forEach(s -> s.setTargetSplitSize(fileSplitSize));
        return splits;
    }

    @Override
    public boolean isBatchMode() {
        return sessionVariable.getNumPartitionsInBatchMode() > 0;
    }

    public Long getSpecifiedSnapshot() throws UserException {
        TableSnapshot tableSnapshot = getQueryTableSnapshot();
        if (tableSnapshot != null) {
            TableSnapshot.VersionType type = tableSnapshot.getType();
            try {
                if (type == TableSnapshot.VersionType.VERSION) {
                    return tableSnapshot.getVersion();
                } else {
                    long timestamp = TimeUtils.timeStringToLong(tableSnapshot.getTime(), TimeUtils.getTimeZone());
                    return SnapshotUtil.snapshotIdAsOfTime(icebergTable, timestamp);
                }
            } catch (IllegalArgumentException e) {
                throw new UserException(e);
            }
        }
        return null;
    }

    private List<IcebergDeleteFileFilter> getDeleteFileFilters(FileScanTask spitTask) {
        List<IcebergDeleteFileFilter> filters = new ArrayList<>();
        for (DeleteFile delete : spitTask.deletes()) {
            if (delete.content() == FileContent.POSITION_DELETES) {
                Optional<Long> positionLowerBound = Optional.ofNullable(delete.lowerBounds())
                        .map(m -> m.get(MetadataColumns.DELETE_FILE_POS.fieldId()))
                        .map(bytes -> Conversions.fromByteBuffer(MetadataColumns.DELETE_FILE_POS.type(), bytes));
                Optional<Long> positionUpperBound = Optional.ofNullable(delete.upperBounds())
                        .map(m -> m.get(MetadataColumns.DELETE_FILE_POS.fieldId()))
                        .map(bytes -> Conversions.fromByteBuffer(MetadataColumns.DELETE_FILE_POS.type(), bytes));
                filters.add(IcebergDeleteFileFilter.createPositionDelete(delete.path().toString(),
                        positionLowerBound.orElse(-1L), positionUpperBound.orElse(-1L),
                        delete.fileSizeInBytes()));
            } else if (delete.content() == FileContent.EQUALITY_DELETES) {
                filters.add(IcebergDeleteFileFilter.createEqualityDelete(
                        delete.path().toString(), delete.equalityFieldIds(), delete.fileSizeInBytes()));
            } else {
                throw new IllegalStateException("Unknown delete content: " + delete.content());
            }
        }
        return filters;
    }

    @Override
    public TFileFormatType getFileFormatType() throws UserException {
        TFileFormatType type;
        String icebergFormat = source.getFileFormat();
        if (icebergFormat.equalsIgnoreCase("parquet")) {
            type = TFileFormatType.FORMAT_PARQUET;
        } else if (icebergFormat.equalsIgnoreCase("orc")) {
            type = TFileFormatType.FORMAT_ORC;
        } else {
            throw new DdlException(String.format("Unsupported format name: %s for iceberg table.", icebergFormat));
        }
        return type;
    }

    @Override
    public List<String> getPathPartitionKeys() throws UserException {
        return icebergTable.spec().fields().stream().map(PartitionField::name).map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    @Override
    public TableIf getTargetTable() {
        return source.getTargetTable();
    }

    @Override
    public Map<String, String> getLocationProperties() throws UserException {
        return source.getCatalog().getCatalogProperty().getHadoopProperties();
    }

    @Override
    public boolean pushDownAggNoGrouping(FunctionCallExpr aggExpr) {
        String aggFunctionName = aggExpr.getFnName().getFunction().toUpperCase();
        return "COUNT".equals(aggFunctionName);
    }

    @Override
    public boolean pushDownAggNoGroupingCheckCol(FunctionCallExpr aggExpr, Column col) {
        return !col.isAllowNull();
    }

    private long getCountFromSnapshot() {
        Long specifiedSnapshot;
        try {
            specifiedSnapshot = getSpecifiedSnapshot();
        } catch (UserException e) {
            return -1;
        }

        Snapshot snapshot = specifiedSnapshot == null
                ? icebergTable.currentSnapshot() : icebergTable.snapshot(specifiedSnapshot);

        // empty table
        if (snapshot == null) {
            return 0;
        }

        Map<String, String> summary = snapshot.summary();
        if (summary.get(IcebergUtils.TOTAL_EQUALITY_DELETES).equals("0")) {
            return Long.parseLong(summary.get(IcebergUtils.TOTAL_RECORDS))
                - Long.parseLong(summary.get(IcebergUtils.TOTAL_POSITION_DELETES));
        } else {
            return -1;
        }
    }

    @Override
    protected void toThrift(TPlanNode planNode) {
        super.toThrift(planNode);
    }

    @Override
    public long getPushDownCount() {
        return getCountFromSnapshot();
    }

    @Override
    public String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        if (pushdownIcebergPredicates.isEmpty()) {
            return super.getNodeExplainString(prefix, detailLevel);
        }
        StringBuilder sb = new StringBuilder();
        for (String predicate : pushdownIcebergPredicates) {
            sb.append(prefix).append(prefix).append(predicate).append("\n");
        }
        return super.getNodeExplainString(prefix, detailLevel)
                + String.format("%sicebergPredicatePushdown=\n%s\n", prefix, sb);
    }

    private void assignCountToSplits(List<Split> splits, long totalCount) {
        int size = splits.size();
        long countPerSplit = totalCount / size;
        for (int i = 0; i < size - 1; i++) {
            ((IcebergSplit) splits.get(i)).setRowCount(countPerSplit);
        }
        ((IcebergSplit) splits.get(size - 1)).setRowCount(countPerSplit + totalCount % size);
    }

    @Override
    public int numApproximateSplits() {
        return NUM_SPLITS_PER_PARTITION * partitionPathSet.size() > 0 ? partitionPathSet.size() : 1;
    }
}
