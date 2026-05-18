package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.physicalOperator.SeqScanOperator;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

public class DBManager {
    private static DBManager instance;

    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;
    private final Map<String, BPlusTreeIndex> runtimeIndexes;

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
        this.runtimeIndexes = new HashMap<>();
        instance = this;
    }

    public static DBManager getInstance() {
        return instance;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    public void showTables() {
        // Task 2.1.1 Basic DDL - SHOW TABLES: display persisted table metadata.
        Logger.info("|---------------|");
        Logger.info("|    Tables     |");
        Logger.info("|---------------|");
        for (String tableName : metaManager.getTableNames()) {
            Logger.info("|{}|", StringUtils.center(tableName, 15, ' '));
        }
        Logger.info("|---------------|");
        // REVIEW(Task 2.1.1 Basic DDL - SHOW TABLES): showTables currently writes to Logger like other DDL helpers;
        // returning a result-set operator would make this easier to test.
    }

    public void descTable(String table_name) throws DBException {
        // Task 2.1.1 Basic DDL - DESCRIBE TABLE: display column names and types
        // from table metadata.
        TableMeta tableMeta = metaManager.getTable(table_name);
        Logger.info("|---------------|---------------|");
        Logger.info("|     Field     |     Type      |");
        Logger.info("|---------------|---------------|");
        for (ColumnMeta columnMeta : tableMeta.columns_list) {
            Logger.info("|{}|{}|",
                    StringUtils.center(columnMeta.name, 15, ' '),
                    StringUtils.center(columnMeta.type.toString(), 15, ' '));
        }
        Logger.info("|---------------|---------------|");
        // REVIEW(Task 2.1.1 Basic DDL - DESCRIBE TABLE): descTable prints physical ValueType names; SQL type aliases can be
        // added if metadata starts preserving original DDL type names.
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        // Task 2.0.1 Table Management: create metadata,
        // table directory, and the table data file.
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    public void createIndex(String indexName, String tableName, String columnName) throws DBException {
        // Task 3.1 Index Support - CREATE INDEX: validate metadata, persist the
        // index definition, and build an in-memory B+Tree from current table rows.
        TableMeta tableMeta = metaManager.getTable(tableName);
        tableMeta.addIndex(indexName, columnName, TableMeta.IndexType.BTREE);
        BPlusTreeIndex index = rebuildIndex(tableName, indexName);
        metaManager.saveToJson();
        Logger.info(index.printTree());
    }

    public void dropIndex(String indexName) throws DBException {
        // Task 3.1 Index Support - DROP INDEX: remove the index definition and
        // discard its runtime B+Tree.
        for (String tableName : metaManager.getTableNames()) {
            TableMeta tableMeta = metaManager.getTable(tableName);
            if (tableMeta.getIndexes().containsKey(indexName)) {
                tableMeta.dropIndex(indexName);
                runtimeIndexes.remove(indexKey(tableName, indexName));
                metaManager.saveToJson();
                return;
            }
        }
        throw new DBException(ExceptionTypes.InvalidSQL("DROP INDEX", "Index does not exist: " + indexName));
    }

    public void addColumn(String tableName, String columnName, String dataType) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        List<Value[]> oldRows = readAllRows(tableName);
        ValueType valueType = parseColumnType(dataType);
        int offset = 0;
        for (ColumnMeta columnMeta : tableMeta.columns_list) {
            offset += columnMeta.len;
        }
        ColumnMeta columnMeta = new ColumnMeta(tableName, columnName, valueType, valueLength(valueType), offset);
        tableMeta.addColumn(columnMeta);
        List<Value[]> rewrittenRows = new ArrayList<>();
        for (Value[] row : oldRows) {
            Value[] rewrittenRow = new Value[row.length + 1];
            System.arraycopy(row, 0, rewrittenRow, 0, row.length);
            rewrittenRow[row.length] = defaultValue(valueType);
            rewrittenRows.add(rewrittenRow);
        }
        rewriteTableData(tableName, tableMeta, rewrittenRows);
        metaManager.saveToJson();
    }

    public void dropColumn(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.columnCount() <= 1) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(tableName));
        }
        int droppedColumnIndex = indexedColumnPosition(tableMeta, columnName);
        List<Value[]> oldRows = readAllRows(tableName);
        tableMeta.dropColumn(columnName);
        List<Value[]> rewrittenRows = new ArrayList<>();
        for (Value[] row : oldRows) {
            Value[] rewrittenRow = new Value[row.length - 1];
            int targetIndex = 0;
            for (int sourceIndex = 0; sourceIndex < row.length; sourceIndex++) {
                if (sourceIndex != droppedColumnIndex) {
                    rewrittenRow[targetIndex++] = row[sourceIndex];
                }
            }
            rewrittenRows.add(rewrittenRow);
        }
        rewriteTableData(tableName, tableMeta, rewrittenRows);
        metaManager.saveToJson();
    }

    public void renameTable(String oldTableName, String newTableName) throws DBException {
        if (!isTableExists(oldTableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(oldTableName));
        }
        if (isTableExists(newTableName)) {
            throw new DBException(ExceptionTypes.TableAlreadyExist(newTableName));
        }
        bufferPool.FlushAllPages(String.format("%s/%s", oldTableName, "data"));
        File oldFolder = new File(String.format("%s/%s", diskManager.getCurrentDir(), oldTableName));
        File newFolder = new File(String.format("%s/%s", diskManager.getCurrentDir(), newTableName));
        if (oldFolder.exists() && !oldFolder.renameTo(newFolder)) {
            throw new DBException(ExceptionTypes.BadIOError(
                    String.format("Failed to rename table directory %s to %s", oldFolder, newFolder)));
        }
        String oldDataFile = String.format("%s/%s", oldTableName, "data");
        String newDataFile = String.format("%s/%s", newTableName, "data");
        Integer pageCount = diskManager.filePages.remove(oldDataFile);
        if (pageCount != null) {
            diskManager.filePages.put(newDataFile, pageCount);
        }
        runtimeIndexes.keySet().removeIf(key -> key.startsWith(oldTableName + "#"));
        metaManager.renameTable(oldTableName, newTableName);
    }

    public BPlusTreeIndex getIndex(String tableName, String indexName) throws DBException {
        BPlusTreeIndex index = runtimeIndexes.get(indexKey(tableName, indexName));
        if (index == null) {
            index = rebuildIndex(tableName, indexName);
        }
        return index;
    }

    public BPlusTreeIndex getIndexOnColumn(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        String indexName = tableMeta.findIndexOnColumn(columnName);
        return indexName == null ? null : getIndex(tableName, indexName);
    }

    public void insertIntoIndexes(String tableName, RID rid, Value[] rowValues) throws DBException {
        // Task 3.1 Index Support - Dynamic INSERT Maintenance: insert the new RID
        // into every runtime B+Tree defined on this table.
        TableMeta tableMeta = metaManager.getTable(tableName);
        for (String indexName : tableMeta.getIndexes().keySet()) {
            int columnIndex = indexedColumnPosition(tableMeta, tableMeta.getIndexColumn(indexName));
            getIndex(tableName, indexName).insert(rowValues[columnIndex], rid);
        }
    }

    public void updateIndexes(String tableName, RID rid, Value[] oldValues, Value[] newValues) throws DBException {
        // Task 3.1 Index Support - Dynamic UPDATE Maintenance: replace old indexed
        // key entries with the updated row values.
        TableMeta tableMeta = metaManager.getTable(tableName);
        for (String indexName : tableMeta.getIndexes().keySet()) {
            int columnIndex = indexedColumnPosition(tableMeta, tableMeta.getIndexColumn(indexName));
            BPlusTreeIndex index = getIndex(tableName, indexName);
            index.delete(oldValues[columnIndex], rid);
            index.insert(newValues[columnIndex], rid);
        }
    }

    private BPlusTreeIndex rebuildIndex(String tableName, String indexName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        String columnName = tableMeta.getIndexColumn(indexName);
        if (columnName == null) {
            throw new DBException(ExceptionTypes.InvalidSQL("INDEX", "Missing index column metadata: " + indexName));
        }
        int columnIndex = indexedColumnPosition(tableMeta, columnName);
        BPlusTreeIndex index = new BPlusTreeIndex(tableName, indexName, columnName);
        SeqScanOperator scanner = new SeqScanOperator(tableName, this);
        try {
            scanner.Begin();
            while (scanner.hasNext()) {
                scanner.Next();
                TableTuple tuple = (TableTuple) scanner.Current();
                if (tuple != null) {
                    index.insert(tuple.getValues()[columnIndex], tuple.getRID());
                }
            }
        } finally {
            scanner.Close();
        }
        runtimeIndexes.put(indexKey(tableName, indexName), index);
        return index;
    }

    private int indexedColumnPosition(TableMeta tableMeta, String columnName) throws DBException {
        for (int i = 0; i < tableMeta.columns_list.size(); i++) {
            if (tableMeta.columns_list.get(i).name.equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
    }

    private String indexKey(String tableName, String indexName) {
        return tableName + "#" + indexName;
    }

    private List<Value[]> readAllRows(String tableName) throws DBException {
        List<Value[]> rows = new ArrayList<>();
        SeqScanOperator scanner = new SeqScanOperator(tableName, this);
        try {
            scanner.Begin();
            while (scanner.hasNext()) {
                scanner.Next();
                TableTuple tuple = (TableTuple) scanner.Current();
                if (tuple != null) {
                    rows.add(tuple.getValues());
                }
            }
        } finally {
            scanner.Close();
        }
        return rows;
    }

    private void rewriteTableData(String tableName, TableMeta tableMeta, List<Value[]> rows) throws DBException {
        String dataFile = String.format("%s/%s", tableName, "data");
        bufferPool.FlushAllPages("");
        bufferPool.DiscardAllPages();
        recordManager.DeleteFile(dataFile);
        recordManager.CreateFile(dataFile, recordSize(tableMeta));
        RecordFileHandle handle = recordManager.OpenFile(tableName);
        try {
            for (Value[] row : rows) {
                ByteBuf buffer = Unpooled.buffer(recordSize(tableMeta));
                writeRow(buffer, row, tableMeta.columns_list);
                handle.InsertRecord(buffer);
            }
        } finally {
            recordManager.CloseFile(handle);
        }
        runtimeIndexes.keySet().removeIf(key -> key.startsWith(tableName + "#"));
    }

    private int recordSize(TableMeta tableMeta) {
        int recordSize = 0;
        for (ColumnMeta columnMeta : tableMeta.columns_list) {
            recordSize += columnMeta.len;
        }
        return recordSize;
    }

    private ValueType parseColumnType(String dataType) throws DBException {
        if (dataType == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE ADD COLUMN"));
        }
        if (dataType.equalsIgnoreCase("char") || dataType.equalsIgnoreCase("varchar")) {
            return ValueType.CHAR;
        }
        if (dataType.equalsIgnoreCase("int") || dataType.equalsIgnoreCase("integer")) {
            return ValueType.INTEGER;
        }
        if (dataType.equalsIgnoreCase("float") || dataType.equalsIgnoreCase("double")) {
            return ValueType.FLOAT;
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE ADD COLUMN " + dataType));
    }

    private int valueLength(ValueType valueType) throws DBException {
        return switch (valueType) {
            case CHAR -> Value.CHAR_SIZE;
            case INTEGER -> Value.INT_SIZE;
            case FLOAT -> Value.FLOAT_SIZE;
            default -> throw new DBException(ExceptionTypes.UnsupportedValueType(valueType));
        };
    }

    private Value defaultValue(ValueType valueType) throws DBException {
        return switch (valueType) {
            case CHAR -> new Value("");
            case INTEGER -> new Value(0L);
            case FLOAT -> new Value(0.0);
            default -> throw new DBException(ExceptionTypes.UnsupportedValueType(valueType));
        };
    }

    private void writeRow(ByteBuf buffer, Value[] row, List<ColumnMeta> columns) {
        for (int i = 0; i < columns.size(); i++) {
            writeValue(buffer, row[i], columns.get(i));
        }
    }

    private void writeValue(ByteBuf buffer, Value value, ColumnMeta columnMeta) {
        if (value.type == ValueType.CHAR) {
            byte[] bytes = ((String) value.value).getBytes();
            ByteBuffer fixedWidth = ByteBuffer.allocate(columnMeta.len);
            fixedWidth.put(bytes, 0, Math.min(bytes.length, columnMeta.len));
            buffer.writeBytes(fixedWidth.array());
            return;
        }
        buffer.writeBytes(value.ToByte());
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
    public void dropTable(String table_name) throws DBException {
        // Task 2.1.1 Basic DDL - DROP TABLE: remove both table files and metadata.
        if(!isTableExists(table_name)){
            throw new DBException(ExceptionTypes.BadIOError("Table does not exist"));
        }
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        deleteDirectory(new File(table_folder));
        runtimeIndexes.keySet().removeIf(key -> key.startsWith(table_name + "#"));
        metaManager.dropTable(table_name);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages(null);
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        // Task 4.1 Transaction API: expose BEGIN through DBManager for command planning.
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        // Task 4.1 Transaction API: expose COMMIT and persist runtime state.
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }
}
