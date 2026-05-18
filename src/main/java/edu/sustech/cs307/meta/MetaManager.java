package edu.sustech.cs307.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

public class MetaManager {
    private static final String META_FILE = "meta_data.json";
    private final String ROOT_DIR;
    private final Map<String, TableMeta> tables;
    private final ObjectMapper objectMapper;

    public MetaManager(String root_dir) throws DBException {
        this.objectMapper = new ObjectMapper();
        this.tables = new HashMap<>();
        this.ROOT_DIR = root_dir;
        loadFromJson();
    }

    public void createTable(TableMeta tableMeta) throws DBException {
        // Task 2.0.1 Table Management: persist table
        // metadata immediately after creation.
        String tableName = tableMeta.tableName;
        if (tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableAlreadyExist(tableName));
        }
        if (tableMeta.columnCount() == 0) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(tableName));
        }
        tables.put(tableName, tableMeta);
        saveToJson();
    }

    public void dropTable(String tableName) throws DBException {
        // Task 2.1.1 Basic DDL - DROP TABLE: remove table metadata and persist it.
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        tables.remove(tableName);
        saveToJson();
    }

    public void renameTable(String oldTableName, String newTableName) throws DBException {
        if (!tables.containsKey(oldTableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(oldTableName));
        }
        if (tables.containsKey(newTableName)) {
            throw new DBException(ExceptionTypes.TableAlreadyExist(newTableName));
        }
        TableMeta tableMeta = tables.remove(oldTableName);
        tableMeta.rename(newTableName);
        tables.put(newTableName, tableMeta);
        saveToJson();
    }

    public void addColumnInTable(String tableName, ColumnMeta column) throws DBException {
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        this.tables.get(tableName).addColumn(column);
        saveToJson();
    }

    public void dropColumnInTable(String tableName, String columnName) throws DBException {
        if (!tables.containsKey(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        this.tables.get(tableName).dropColumn((columnName));
        saveToJson();
    }

    public TableMeta getTable(String tableName) throws DBException {
        if (tables.containsKey(tableName)) {
            return tables.get(tableName);
        }
        throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        // return null;
    }

    public Set<String> getTableNames() {
        return this.tables.keySet();
    }

    public void saveToJson() throws DBException {
        // Task 2.0.1 Table Management: store metadata on
        // disk so tables survive DB restarts.
        // check the root directory exists
        if (!new File(ROOT_DIR).exists()) {
            // create it
            new File(ROOT_DIR).mkdirs();
        }

        try (Writer writer = new FileWriter(String.format("%s/%s", ROOT_DIR, META_FILE))) {
            objectMapper.writeValue(writer, tables);
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.UnableSaveMetadata(e.getMessage()));
        }
    }

    private void loadFromJson() throws DBException {
        File file = new File(ROOT_DIR + "/" + META_FILE);
        if (!file.exists())
            return;

        try (Reader reader = new FileReader(ROOT_DIR + "/" + META_FILE)) {
            TypeReference<Map<String, TableMeta>> typeRef = new TypeReference<>() {
            };
            Map<String, TableMeta> loadedTables = objectMapper.readValue(reader, typeRef);
            if (loadedTables != null) {
                tables.putAll(loadedTables);
            }
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.UnableLoadMetadata(e.getMessage()));
        }
    }
}
