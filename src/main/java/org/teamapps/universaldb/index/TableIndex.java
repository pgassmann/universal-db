/*-
 * ========================LICENSE_START=================================
 * UniversalDB
 * ---
 * Copyright (C) 2014 - 2020 TeamApps.org
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.teamapps.universaldb.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teamapps.universaldb.TableConfig;
import org.teamapps.universaldb.index.bool.BooleanIndex;
import org.teamapps.universaldb.index.file.FileStore;
import org.teamapps.universaldb.index.numeric.LongIndex;
import org.teamapps.universaldb.index.reference.blockindex.ReferenceBlockChain;
import org.teamapps.universaldb.index.reference.blockindex.ReferenceBlockChainImpl;
import org.teamapps.universaldb.index.reference.single.SingleReferenceIndex;
import org.teamapps.universaldb.index.text.*;
import org.teamapps.universaldb.index.translation.TranslatableText;
import org.teamapps.universaldb.index.translation.TranslatableTextIndex;
import org.teamapps.universaldb.query.AndFilter;
import org.teamapps.universaldb.query.Filter;
import org.teamapps.universaldb.query.IndexFilter;
import org.teamapps.universaldb.query.OrFilter;
import org.teamapps.universaldb.schema.Column;
import org.teamapps.universaldb.schema.Table;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TableIndex implements MappedObject {
	private static final Logger log = LoggerFactory.getLogger(TableIndex.class);

	private final DatabaseIndex databaseIndex;
	private final String name;
	private final String parentFQN;
	private final File path;
	private final TableConfig tableConfig;
	private boolean keepDeletedRecords;
	private BooleanIndex records;
	private BooleanIndex deletedRecords;
	private LongIndex transactionIndex;

	private List<ColumnIndex> columnIndices;
	private Map<String, ColumnIndex> columnIndexByName;
	private CharIndex collectionCharIndex;
	private CollectionTextSearchIndex collectionTextSearchIndex;
	private ReferenceBlockChain referenceBlockChain;
	private List<String> fileFieldNames;
	private List<TextIndex> textFields;
	private List<TranslatableTextIndex> translatedTextFields;
	private int mappingId;

	public TableIndex(DatabaseIndex database, String name, TableConfig tableConfig) {
		this(database, database.getPath(), database.getFQN(), name, tableConfig);
	}

	public TableIndex(DatabaseIndex databaseIndex, File parentPath, String parentFQN, String name, TableConfig tableConfig) {
		this.databaseIndex = databaseIndex;
		this.name = name;
		this.parentFQN = parentFQN;
		this.path = new File(parentPath, name);
		path.mkdir();
		records = new BooleanIndex("coll-recs", this, ColumnType.BOOLEAN);
		this.tableConfig = tableConfig;

		columnIndices = new ArrayList<>();
		columnIndexByName = new HashMap<>();

		if (tableConfig.keepDeleted()) {
			keepDeletedRecords = true;
			deletedRecords = new BooleanIndex("coll-del-recs", this, ColumnType.BOOLEAN);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(this::close));
	}


	public ReferenceBlockChain getReferenceBlockChain() {
		if (referenceBlockChain == null) {
			referenceBlockChain = new ReferenceBlockChainImpl(path, "ref.graph");
		}
		return referenceBlockChain;
	}

	public CharIndex getCollectionCharIndex() {
		if (collectionCharIndex == null) {
			collectionCharIndex = new CharIndex(path, "coll-text");
		}
		return collectionCharIndex;
	}

	public CollectionTextSearchIndex getCollectionTextSearchIndex() {
		if (collectionTextSearchIndex == null) {
			collectionTextSearchIndex = new CollectionTextSearchIndex(path, "coll-text");
		}
		return collectionTextSearchIndex;
	}

	public void checkFullTextIndex() {
		if (collectionTextSearchIndex == null) {
			return;
		}
		if (!records.getValue(0) && getCount() > 0) {
			long time = System.currentTimeMillis();
			log.warn("RECREATING FULL TEXT INDEX FOR: " + getName() + " (RECORDS:" + getCount() + ", MAX-DOC:" + collectionTextSearchIndex.getMaxDoc() + ")");
			recreateFullTextIndex();
			log.warn("RECREATING FINISHED FOR: " + getName() + " (TIME:" + (System.currentTimeMillis() - time) + ")");
		}
		if (getCount() > collectionTextSearchIndex.getMaxDoc()) {
			//todo check if necessary - null values?
		}
		records.setValue(0, false);
	}

	public void forceFullTextIndexRecreation() {
		log.warn("FORCED RECREATING FULL TEXT INDEX FOR: " + getName() + " (RECORDS:" + getCount() + ", MAX-DOC:" + collectionTextSearchIndex.getMaxDoc() + ")");
		recreateFullTextIndex();

	}

	private void recreateFullTextIndex() {
		try {
			collectionTextSearchIndex.deleteAllDocuments();
			BitSet bitSet = records.getBitSet();
			for (int id = bitSet.nextSetBit(0); id >= 0; id = bitSet.nextSetBit(id + 1)) {
				List<FullTextIndexValue> values = new ArrayList<>();
				for (TextIndex textField : getTextFields()) {
					String value = textField.getValue(id);
					if (value != null) {
						values.add(new FullTextIndexValue(textField.getName(), value));
					}
				}
				for (TranslatableTextIndex translatableTextIndex : getTranslatedTextFields()) {
					TranslatableText value = translatableTextIndex.getValue(id);
					if (value != null) {
						values.add(new FullTextIndexValue(translatableTextIndex.getName(), value));
					}
				}
				if (!values.isEmpty()) {
					collectionTextSearchIndex.setRecordValues(id, values, false);
				}
			}
			collectionTextSearchIndex.commit(false);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


	public FileStore getFileStore() {
		return databaseIndex.getSchemaIndex().getFileStore();
	}

	public File getPath() {
		return path;
	}

	public TableConfig getTableConfig() {
		return tableConfig;
	}

	public BitSet getRecords() {
		return records.getBitSet();
	}

	public int getCount() {
		return records.getCount();
	}

	public BitSet getDeletedRecords() {
		if (!keepDeletedRecords) {
			return null;
		}
		return deletedRecords.getBitSet();
	}

	public void addIndex(ColumnType type, String name) {
		ColumnIndex column = ColumnIndex.createColumn(this, name, type);
		addIndex(column);
	}

	public void addIndex(ColumnIndex index) {
		columnIndices.add(index);
		columnIndexByName.put(index.getName(), index);
		fileFieldNames = null;
		textFields = null;
	}

	public Filter createFullTextFilter(String query, String... fieldNames) {
		AndFilter andFilter = new AndFilter();
		if (query == null || query.isBlank()) {
			return andFilter;
		}
		String[] terms = query.split(" ");
		for (String term : terms) {
			if (!term.isBlank()) {
				boolean isNegation = term.startsWith("!");
				TextFilter textFilter = parseTextFilter(term);
				Filter fullTextFilter = createFullTextFilter(textFilter, !isNegation, fieldNames);
				andFilter.and(fullTextFilter);
			}
		}
		return andFilter;
	}

	private TextFilter parseTextFilter(String term) {
		boolean negation = false;
		boolean similar = false;
		boolean startsWith = false;
		boolean equals = false;
		if (term.startsWith("!")) {
			negation = true;
			term = term.substring(1);
		}
		if (term.endsWith("+")) {
			similar = true;
			term = term.substring(0, term.length() - 1);
		}
		if (term.endsWith("*")) {
			startsWith = true;
			term = term.substring(0, term.length() - 1);
		}
		if (term.startsWith("\"") && term.endsWith("\"")) {
			term = term.substring(0, term.length() - 2);
		}
		if (equals) {
			return negation ? TextFilter.termEqualsFilter(term) : TextFilter.termNotEqualsFilter(term);
		} else if (similar) {
			return negation ? TextFilter.termNotSimilarFilter(term) : TextFilter.termSimilarFilter(term);
		} else if (startsWith) {
			return negation ? TextFilter.termStartsNotWithFilter(term) : TextFilter.termStartsWithFilter(term);
		} else {
			return negation ? TextFilter.termContainsNotFilter(term) : TextFilter.termContainsFilter(term);
		}
	}

	public Filter createFullTextFilter(TextFilter textFilter, String... fieldNames) {
		return createFullTextFilter(textFilter, true, fieldNames);
	}

	public Filter createFullTextFilter(TextFilter textFilter, boolean orQuery, String... fieldNames) {
		Filter filter = orQuery ? new OrFilter() : new AndFilter();
		if (fieldNames == null || fieldNames.length == 0) {
			columnIndices.stream().filter(columnIndex -> columnIndex.getType() == IndexType.TEXT).forEach(columnIndex -> {
				IndexFilter<TextIndex, TextFilter> indexFilter = new IndexFilter<TextIndex, TextFilter>(columnIndex, textFilter);
				if (orQuery) {
					filter.or(indexFilter);
				} else {
					filter.and(indexFilter);
				}
			});
		} else {
			for (String fieldName : fieldNames) {
				ColumnIndex columnIndex = columnIndexByName.get(fieldName);
				if (columnIndex != null && columnIndex.getType() == IndexType.TEXT) {
					IndexFilter<TextIndex, TextFilter> indexFilter = new IndexFilter<TextIndex, TextFilter>(columnIndex, textFilter);
					if (orQuery) {
						filter.or(indexFilter);
					} else {
						filter.and(indexFilter);
					}
				}
			}
		}
		return filter;
	}

	public List<SortEntry> sortRecords(String columnName, BitSet records, boolean ascending, SingleReferenceIndex... path) {
		ColumnIndex column = null;
		if (path != null && path.length > 0) {
			column = path[path.length - 1].getReferencedTable().getColumnIndex(columnName);
		} else {
			column = getColumnIndex(columnName);
		}
		if (column == null) {
			return null;
		}
		List<SortEntry> sortEntries = SortEntry.createSortEntries(records, path);

		return column.sortRecords(sortEntries, ascending, null);
	}

	public int createRecord(int recordId, int correlationId, boolean update) {
		int id = 0;
		if (recordId == 0) {
			if (keepDeletedRecords) {
				id = Math.max(records.getNextId(), deletedRecords.getNextId());
			} else {
				id = records.getNextId();
			}
		} else {
			id = recordId;
			if (keepDeletedRecords && deletedRecords.getValue(recordId)) {
				deletedRecords.setValue(recordId, false);
			}
		}
		records.setValue(id, true);
		return id;
	}

	public void updateFullTextIndex(int id, List<FullTextIndexValue> values, boolean update) {
		if (update) {
			Set<String> textFieldNames = values.stream().map(FullTextIndexValue::getFieldName).collect(Collectors.toSet());
			List<FullTextIndexValue> recordFullTextIndexValues = new ArrayList<>(values);
			for (TextIndex textField : getTextFields()) {
				if (!textFieldNames.contains(textField.getName())) {
					recordFullTextIndexValues.add(new FullTextIndexValue(textField.getName(), textField.getValue(id)));
				}
			}
			for (TranslatableTextIndex translatableTextIndex : getTranslatedTextFields()) {
				if (!textFieldNames.contains(translatableTextIndex.getName())) {
					recordFullTextIndexValues.add(new FullTextIndexValue(translatableTextIndex.getName(), translatableTextIndex.getValue(id)));
				}
			}
			collectionTextSearchIndex.setRecordValues(id, recordFullTextIndexValues, true);
		} else {
			collectionTextSearchIndex.setRecordValues(id, values, false);
		}
	}

	public boolean deleteRecord(int id) {
		records.setValue(id, false);
		if (keepDeletedRecords) {
			deletedRecords.setValue(id, true);
			return true;
		} else {
			for (ColumnIndex columnIndex : columnIndices) {
				columnIndex.removeValue(id);
			}
			collectionTextSearchIndex.delete(id, getFileFieldNames());
			return false;
		}
	}

	public void setTransactionId(int id, long transactionId) {
		if (!tableConfig.isCheckpoints()) {
			return;
		}
		if (transactionIndex == null) {
			transactionIndex = getTransactionIndex();
		}
		transactionIndex.setValue(id, transactionId);
	}

	public long getTransactionId(int id) {
		if (id == 0 || !tableConfig.isCheckpoints()) {
			return 0;
		}
		if (transactionIndex == null) {
			transactionIndex = getTransactionIndex();
		}
		return transactionIndex.getValue(id);
	}

	private LongIndex getTransactionIndex() {
		if (!tableConfig.isCheckpoints()) {
			return null;
		}
		return (LongIndex) getColumnIndex(Table.FIELD_CHECKPOINTS);
	}

	private List<String> getFileFieldNames() {
		if (fileFieldNames == null) {
			fileFieldNames = columnIndices.stream()
					.filter(index -> index.getType() == IndexType.FILE)
					.map(ColumnIndex::getName)
					.collect(Collectors.toList());
		}
		return fileFieldNames;
	}

	private List<TextIndex> getTextFields() {
		if (textFields == null) {
			textFields = columnIndices.stream()
					.filter(index -> index.getType() == IndexType.TEXT)
					.map(index -> (TextIndex) index)
					.collect(Collectors.toList());
		}
		return textFields;
	}

	private List<TranslatableTextIndex> getTranslatedTextFields() {
		if (translatedTextFields == null) {
			translatedTextFields = columnIndices.stream()
					.filter(index -> index.getType() == IndexType.TRANSLATABLE_TEXT)
					.map(index -> (TranslatableTextIndex) index)
					.collect(Collectors.toList());
		}
		return translatedTextFields;
	}

	public BitSet getRecordBitSet() {
		return records.getBitSet();
	}

	public BitSet getDeletedRecordsBitSet() {
		if (!keepDeletedRecords) {
			return null;
		}
		return deletedRecords.getBitSet();
	}

	public List<ColumnIndex> getColumnIndices() {
		return columnIndices;
	}

	public ColumnIndex getColumnIndex(String name) {
		return columnIndexByName.get(name);
	}

	public int getMappingId() {
		return mappingId;
	}

	public void setMappingId(int id) {
		if (mappingId > 0) {
			throw new RuntimeException("Cannot set new mapping id for index:" + name + " as it is already mapped");
		}
		this.mappingId = id;
	}

	public void merge(Table table) {
		for (Column column : table.getColumns()) {
			ColumnIndex localColumn = getColumnIndex(column.getName());
			if (localColumn == null) {
				localColumn = ColumnIndex.createColumn(this, column.getName(), column.getType());
				addIndex(localColumn);
			}
			if (localColumn.getMappingId() == 0) {
				localColumn.setMappingId(column.getMappingId());
			}
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("collection: ").append(name).append(", id:").append(mappingId).append("\n");
		for (ColumnIndex column : columnIndices) {
			sb.append("\t").append(column.toString()).append("\n");
		}
		return sb.toString();
	}

	public void close() {
		try {
			log.info("Shutdown on collection:" + name);
			if (collectionTextSearchIndex != null) {
				collectionTextSearchIndex.commit(true);
			}
			records.setValue(0, true);
			records.close();
			for (ColumnIndex column : columnIndices) {
				column.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void drop() {
		collectionTextSearchIndex.drop();
		for (ColumnIndex column : columnIndices) {
			column.drop();
		}
	}

	public String getFQN() {
		return parentFQN + "." + name;
	}

	public String getName() {
		return name;
	}

	public DatabaseIndex getDatabaseIndex() {
		return databaseIndex;
	}
}
