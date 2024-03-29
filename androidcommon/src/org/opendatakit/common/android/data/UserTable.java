/*
 * Copyright (C) 2012 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.common.android.data;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.opendatakit.aggregate.odktables.rest.ElementDataType;
import org.opendatakit.aggregate.odktables.rest.ElementType;
import org.opendatakit.common.android.provider.DataTableColumns;
import org.opendatakit.common.android.utilities.DataUtil;
import org.opendatakit.common.android.utilities.ODKDatabaseUtils;
import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.common.android.utilities.WebLogger;

import android.content.Context;
import android.database.Cursor;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * This class represents a table. This can be conceptualized as a list of rows.
 * Each row comprises the user-defined columns, or data, as well as the
 * ODKTables-specified metadata.
 * <p>
 * This should be considered an immutable class.
 *
 * @author unknown
 * @author sudar.sam@gmail.com
 *
 */
public class UserTable {

  private static final String TAG = UserTable.class.getSimpleName();

  private final String mAppName;
  private final String mTableId;
  private final ArrayList<ColumnDefinition> mColumnDefns;
  private final ArrayList<Row> mRows;
  /**
   * The {@link TableProperties} associated with this table. Included so that
   * more intelligent things can be done with regards to interpretation of type.
   */
//  private final TableProperties mTp;
  private final String mSqlWhereClause;
  private final String[] mSqlSelectionArgs;
  private final String[] mSqlGroupByArgs;
  private final String mSqlHavingClause;
  private final String mSqlOrderByElementKey;
  private final String mSqlOrderByDirection;

  private final Map<String,Integer> mElementKeyToIndex;
  private final String[] mElementKeyForIndex;

  private final DataUtil du;

  public UserTable(UserTable table, List<Integer> indexes) {
    this.mAppName = table.mAppName;
    this.mTableId = table.mTableId;
    this.mColumnDefns = table.mColumnDefns;
    du = table.du;
    mRows = new ArrayList<Row>(indexes.size());
    for (int i = 0 ; i < indexes.size(); ++i) {
      Row r = table.getRowAtIndex(indexes.get(i));
      mRows.add(r);
    }
    this.mSqlWhereClause = table.mSqlWhereClause;
    this.mSqlSelectionArgs = table.mSqlSelectionArgs;
    this.mSqlGroupByArgs = table.mSqlGroupByArgs;
    this.mSqlHavingClause = table.mSqlHavingClause;
    this.mSqlOrderByElementKey = table.mSqlOrderByElementKey;
    this.mSqlOrderByDirection = table.mSqlOrderByDirection;
    this.mElementKeyToIndex = table.mElementKeyToIndex;
    this.mElementKeyForIndex = table.mElementKeyForIndex;
  }

  public UserTable(Cursor c, String appName, String tableId,
      ArrayList<ColumnDefinition> columnDefns,
      String sqlWhereClause, String[] sqlSelectionArgs,
      String[] sqlGroupByArgs, String sqlHavingClause,
      String sqlOrderByElementKey, String sqlOrderByDirection) {
    this.mAppName = appName;
    this.mTableId = tableId;
    this.mColumnDefns = columnDefns;
    du = new DataUtil(Locale.ENGLISH, TimeZone.getDefault());
//    mTp = tableProperties;
    this.mSqlWhereClause = sqlWhereClause;
    this.mSqlSelectionArgs = sqlSelectionArgs;
    this.mSqlGroupByArgs = sqlGroupByArgs;
    this.mSqlHavingClause = sqlHavingClause;
    this.mSqlOrderByElementKey = sqlOrderByElementKey;
    this.mSqlOrderByDirection = sqlOrderByDirection;
    int rowIdIndex = c.getColumnIndexOrThrow(DataTableColumns.ID);
    // These maps will map the element key to the corresponding index in
    // either data or metadata. If the user has defined a column with the
    // element key _my_data, and this column is at index 5 in the data
    // array, dataKeyToIndex would then have a mapping of _my_data:5.
    // The sync_state column, if present at index 7, would have a mapping
    // in metadataKeyToIndex of sync_state:7.
    List<String> adminColumnOrder = ODKDatabaseUtils.get().getAdminColumns();
    mElementKeyToIndex = new HashMap<String, Integer>();
    List<String> userColumnOrder = ColumnDefinition.getRetentionColumnNames(mColumnDefns);
    mElementKeyForIndex = new String[userColumnOrder.size()+adminColumnOrder.size()];
    int[] cursorIndex = new int[userColumnOrder.size()+adminColumnOrder.size()];
    int i = 0;
    for (i = 0; i < userColumnOrder.size(); i++) {
      String elementKey = userColumnOrder.get(i);
      mElementKeyForIndex[i] = elementKey;
      mElementKeyToIndex.put(elementKey, i);
      cursorIndex[i] = c.getColumnIndex(elementKey);
    }

    for (int j = 0; j < adminColumnOrder.size(); j++) {
      // TODO: problem is here. unclear how to best get just the
      // metadata in here. hmm.
      String elementKey = adminColumnOrder.get(j);
      mElementKeyForIndex[i+j] = elementKey;
      mElementKeyToIndex.put(elementKey, i+j);
      cursorIndex[i+j] = c.getColumnIndex(elementKey);
    }

    c.moveToFirst();
    int rowCount = c.getCount();
    mRows = new ArrayList<Row>(rowCount);

    String[] rowData = new String[userColumnOrder.size()+ adminColumnOrder.size()];
    if (c.moveToFirst()) {
      do {
        if ( c.isNull(rowIdIndex)) {
          throw new IllegalStateException("Unexpected null value for rowId");
        }
        String rowId = ODKDatabaseUtils.get().getIndexAsString(c, rowIdIndex);
        // First get the user-defined data for this row.
        for (i = 0; i < cursorIndex.length; i++) {
          String value = ODKDatabaseUtils.get().getIndexAsString(c, cursorIndex[i]);
          rowData[i] = value;
        }
        Row nextRow = new Row(rowId, rowData.clone());
        mRows.add(nextRow);
      } while (c.moveToNext());
    }
    c.close();
  }

  public String getAppName() {
    return mAppName;
  }
  
  public String getTableId() {
    return mTableId;
  }
  
  public ArrayList<ColumnDefinition> getColumnDefinitions() {
    return mColumnDefns;
  }
  
  public Row getRowAtIndex(int index) {
    return this.mRows.get(index);
  }

  public Integer getColumnIndexOfElementKey(String elementKey) {
    return this.mElementKeyToIndex.get(elementKey);
  }

  public String getElementKey(int colNum) {
    return this.mElementKeyForIndex[colNum];
  }

  public String getWhereClause() {
    return mSqlWhereClause;
  }

  public String[] getSelectionArgs() {
    return mSqlSelectionArgs.clone();
  }

  /**
   * True if the table has a group-by clause in its query
   *
   * @return
   */
  public boolean isGroupedBy() {
    return mSqlGroupByArgs != null && mSqlGroupByArgs.length != 0;
  }

  public String[] getGroupByArgs() {
    return mSqlGroupByArgs.clone();
  }

  public String getHavingClause() {
    return mSqlHavingClause;
  }

  public String getOrderByElementKey() {
    return mSqlOrderByElementKey;
  }

  public String getOrderByDirection() {
    return mSqlOrderByDirection;
  }

  public int getWidth() {
    return mElementKeyForIndex.length;
  }

  public int getNumberOfRows() {
    return this.mRows.size();
  }

  public boolean hasCheckpointRows() {
    for ( Row row : mRows ) {
      String type = row.getRawDataOrMetadataByElementKey(DataTableColumns.SAVEPOINT_TYPE);
      if ( type == null || type.length() == 0 ) {
        return true;
      }
    }
    return false;
  }

  public boolean hasConflictRows() {
    for ( Row row : mRows ) {
      String conflictType = row.getRawDataOrMetadataByElementKey(DataTableColumns.CONFLICT_TYPE);
      if ( conflictType != null && conflictType.length() != 0 ) {
        return true;
      }
    }
    return false;
  }
  /**
   * Scan the rowIds to get the row number. As the rowIds are not sorted, this
   * is a potentially expensive operation, scanning the entire array, as well as
   * the cost of checking String equality. Should be used only when necessary.
   * <p>
   * Return -1 if the row Id is not found.
   *
   * @param rowId
   * @return
   */
  public int getRowNumFromId(String rowId) {
    for (int i = 0; i < this.mRows.size(); i++) {
      if (this.mRows.get(i).mRowId.equals(rowId)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * This represents a single row of data in a table.
   *
   * @author sudar.sam@gmail.com
   *
   */
  /*
   * This class is final to try and reduce overhead. As final there is no
   * extended-class pointer. Not positive this is really a thing, need to
   * investigate. Nothing harmed by finalizing, though.
   */
  public final class Row {

    /**
     * The id of the row.
     */
    private final String mRowId;

    /**
     * Holds a mix of user-data and meta-data of the row
     */
    private final String[] mRowData;

    /**
     * Construct the row.
     *
     * @param rowId
     * @param rowData
     *          the combined set of data and metadata in the row.
     */
    public Row(String rowId, String[] rowData) {
      this.mRowId = rowId;
      this.mRowData = rowData;
    }

    /**
     * Return the id of this row.
     * @return
     */
    public String getRowId() {
      return this.mRowId;
    }

    /**
     * Return the String representing the contents of the column represented by
     * the passed in elementKey. This can be either the element key of a
     * user-defined column or a ODKTabes-specified metadata column.
     * <p>
     * Null values are returned as nulls.
     *
     * @param elementKey
     *          elementKey of data or metadata column
     * @return String representation of contents of column. Null values are
     *         returned as null.
     */
    public String getRawDataOrMetadataByElementKey(String elementKey) {
      String result;
      Integer cell = mElementKeyToIndex.get(elementKey);
      if ( cell == null ) {
        WebLogger.getLogger(mAppName).e(TAG, "elementKey [" + elementKey + "] was not found in table");
        return null;
      }
      result = this.mRowData[cell];
      if (result == null) {
        return null;
      }
      return result;
    }

    /**
     * Return the data stored in the cursor at the given index and given position
     * (ie the given row which the cursor is currently on) as null OR whatever
     * data type it is.
     * <p>This does not actually convert data types from one type
     * to the other. Instead, it safely preserves null values and returns boxed
     * data values. If you specify ArrayList or HashMap, it JSON deserializes
     * the value into one of those.
     *
     * @param c
     * @param clazz
     * @param i
     * @return
     */
    @SuppressWarnings("unchecked")
    public final <T> T getRawDataType(String elementKey, Class<T> clazz) {
      // If you add additional return types here be sure to modify the javadoc.
      try {
        String value = getRawDataOrMetadataByElementKey(elementKey);
        if (value == null) {
          return null;
        }
        if ( clazz == Long.class ) {
          Long l = Long.parseLong(value);
          return (T) l;
        } else if ( clazz == Integer.class ) {
          Integer l = Integer.parseInt(value);
          return (T) l;
        } else if ( clazz == Double.class ) {
          Double d = Double.parseDouble(value);
          return (T) d;
        } else if ( clazz == String.class ) {
          return (T) value;
        } else if ( clazz == Boolean.class ) {
          return (T) Boolean.valueOf(value);
        } else if ( clazz == ArrayList.class ) {
          // json deserialization of an array
          return (T) ODKFileUtils.mapper.readValue(value, ArrayList.class);
        } else if ( clazz == HashMap.class ) {
          // json deserialization of an object
          return (T) ODKFileUtils.mapper.readValue(value, HashMap.class);
        } else {
          throw new IllegalStateException("Unexpected data type in SQLite table");
        }
      } catch (ClassCastException e) {
        e.printStackTrace();
        throw new IllegalStateException("Unexpected data type conversion failure " + e.toString() + " in SQLite table ");
      } catch (JsonParseException e) {
        e.printStackTrace();
        throw new IllegalStateException("Unexpected data type conversion failure " + e.toString() + " on SQLite table");
      } catch (JsonMappingException e) {
        e.printStackTrace();
        throw new IllegalStateException("Unexpected data type conversion failure " + e.toString() + " on SQLite table");
      } catch (IOException e) {
        e.printStackTrace();
        throw new IllegalStateException("Unexpected data type conversion failure " + e.toString() + " on SQLite table");
      }
    }

    public String getDisplayTextOfData(Context context, ElementType type, String elementKey, boolean showErrorText) {
      // TODO: share processing with CollectUtil.writeRowDataToBeEdited(...)
      String raw = getRawDataOrMetadataByElementKey(elementKey);
      if ( raw == null ) {
        return null;
      }
      if ( raw.length() == 0 ) {
        throw new IllegalArgumentException("unexpected zero-length string in database! " + elementKey);
      }
      
      if ( type == null ) {
        return raw;
      }
      if ( type.getDataType() == ElementDataType.rowpath ) {
        File f = ODKFileUtils.getAsFile(getAppName(), raw);
        return f.getName();
      } else if ( type.getDataType() == ElementDataType.configpath ) {
        return raw;
      } else if ( type.getDataType() == ElementDataType.number &&
                  raw.indexOf('.') != -1 ) {
        // trim trailing zeros on numbers (leaving the last one)
        int lnz = raw.length()-1;
        while ( lnz > 0 && raw.charAt(lnz) == '0' ) {
          lnz--;
        }
        if ( lnz >= raw.length()-2 ) {
          // ended in non-zero or x0
          return raw;
        } else {
          // ended in 0...0
          return raw.substring(0, lnz+2);
        }
      } else {
        return raw;
      }
    }

    @Override
    public int hashCode() {
      final int PRIME = 31;
      int result = 1;
      result = result * PRIME + this.mRowId.hashCode();
      result = result * PRIME + this.mRowData.hashCode();
      return result;
    }

  }
}
