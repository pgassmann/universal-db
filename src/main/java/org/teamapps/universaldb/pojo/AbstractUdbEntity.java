/*-
 * ========================LICENSE_START=================================
 * UniversalDB
 * ---
 * Copyright (C) 2014 - 2021 TeamApps.org
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
package org.teamapps.universaldb.pojo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.teamapps.universaldb.context.UserContext;
import org.teamapps.universaldb.index.ColumnIndex;
import org.teamapps.universaldb.index.SortEntry;
import org.teamapps.universaldb.index.TableIndex;
import org.teamapps.universaldb.index.bool.BooleanIndex;
import org.teamapps.universaldb.index.numeric.*;
import org.teamapps.universaldb.index.reference.multi.MultiReferenceIndex;
import org.teamapps.universaldb.index.reference.single.SingleReferenceIndex;
import org.teamapps.universaldb.index.reference.value.*;
import org.teamapps.universaldb.index.text.TextIndex;
import org.teamapps.universaldb.index.translation.TranslatableText;
import org.teamapps.universaldb.index.translation.TranslatableTextIndex;
import org.teamapps.universaldb.record.EntityBuilder;
import org.teamapps.universaldb.schema.Table;
import org.teamapps.universaldb.transaction.Transaction;
import org.teamapps.universaldb.transaction.TransactionRecord;
import org.teamapps.universaldb.transaction.TransactionRecordValue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public abstract class AbstractUdbEntity<ENTITY extends Entity> implements Entity<ENTITY>, EntityBuilder<ENTITY> {

	private static final Logger log = LoggerFactory.getLogger(AbstractUdbEntity.class);
	private static final AtomicInteger correlationIdGenerator = new AtomicInteger();
	private static final int MAX_CORRELATION_ID = 2_000_000_000;

	private final TableIndex tableIndex;
	private int id;
	private boolean createEntity;
	private int correlationId;
	private EntityChangeSet entityChangeSet;
	private Transaction transaction;

	public static <ENTITY> List<ENTITY> createEntityList(EntityBuilder<ENTITY> entityBuilder, List<Integer> recordIds){
		List<ENTITY> list = new ArrayList<>();
		for (Integer recordId : recordIds) {
			list.add(entityBuilder.build(recordId));
		}
		return list;
	}

	public static <ENTITY extends Entity> List<ENTITY> sort(TableIndex table, List<ENTITY> list, String sortFieldName, boolean ascending, String ... path) {
		return sort(table, list, sortFieldName, ascending, UserContext.create(), path);
	}

	public static <ENTITY extends Entity> List<ENTITY> sort(TableIndex table, List<ENTITY> list, String sortFieldName, boolean ascending, UserContext userContext, String ... path) {
		SingleReferenceIndex[] referencePath = getReferenceIndices(table, path);
		ColumnIndex column = getSortColumn(table, sortFieldName, referencePath);
		List<SortEntry<ENTITY>> sortEntries = SortEntry.createSortEntries(list, referencePath);
		sortEntries = column.sortRecords(sortEntries, ascending, userContext);
		return sortEntries.stream().map(SortEntry::getEntity).collect(Collectors.toList());
	}

	public static <ENTITY extends Entity> List<ENTITY> sort(TableIndex table, EntityBuilder<ENTITY> builder, BitSet recordIds, String sortFieldName, boolean ascending, String ... path) {
		return sort(table, builder, recordIds, sortFieldName, ascending, null, path);
	}

	public static <ENTITY extends Entity> List<ENTITY> sort(TableIndex table, EntityBuilder<ENTITY> builder, BitSet recordIds, String sortFieldName, boolean ascending, UserContext userContext, String ... path) {
		SingleReferenceIndex[] referencePath = getReferenceIndices(table, path);
		ColumnIndex column = getSortColumn(table, sortFieldName, referencePath);
		List<SortEntry> sortEntries = SortEntry.createSortEntries(recordIds, referencePath);
		sortEntries = column.sortRecords(sortEntries, ascending, userContext);
		List<ENTITY> list = new ArrayList<>();
		for (SortEntry entry : sortEntries) {
			list.add(builder.build(entry.getId()));
		}
		return list;
	}

	private static SingleReferenceIndex[] getReferenceIndices(TableIndex table, String[] path) {
		SingleReferenceIndex[] referencePath = null;
		if (path != null && path.length > 0) {
			referencePath = new SingleReferenceIndex[path.length];
			TableIndex pathTable = table;
			for (int i = 0; i < path.length; i++) {
				referencePath[i] = (SingleReferenceIndex) pathTable.getColumnIndex(path[i]);
				pathTable = referencePath[i].getReferencedTable();
			}
		}
		return referencePath;
	}

	private static ColumnIndex getSortColumn(TableIndex table, String sortFieldName, SingleReferenceIndex[] referencePath) {
		ColumnIndex column;
		if (referencePath != null && referencePath.length > 0) {
			column = referencePath[referencePath.length - 1].getReferencedTable().getColumnIndex(sortFieldName);
		} else {
			column = table.getColumnIndex(sortFieldName);
		}
		return column;
	}

	public AbstractUdbEntity(TableIndex tableIndex) {
		this.tableIndex = tableIndex;
		createEntity = true;
		createCorrelationId();
	}

	public AbstractUdbEntity(TableIndex tableIndex, int id, boolean createEntity) {
		this.tableIndex = tableIndex;
		this.id = id;
		this.createEntity = createEntity;
		if (createEntity) {
			createCorrelationId();
		}
	}

	private void createCorrelationId() {
		if (correlationId > 0) {
			return;
		}
		correlationId = correlationIdGenerator.incrementAndGet();
		if (correlationId == MAX_CORRELATION_ID) {
			correlationIdGenerator.set(0);
		}
	}

	@Override
	public int getId() {
		if (id == 0 && transaction != null) {
			id = transaction.getResolvedRecordIdByCorrelationId(correlationId);
		}
		return id;
	}

	protected int getCorrelationId() {
		return correlationId;
	}

	public Object getEntityValue(String fieldName) {
		ColumnIndex index = tableIndex.getColumnIndex(fieldName);
		if (index != null) {
			return index.getGenericValue(getId());
		} else {
			return null;
		}
	}

	public void setEntityValue(String fieldName, Object value) {
		ColumnIndex index = tableIndex.getColumnIndex(fieldName);
		if (index != null) {
			setChangeValue(index, value, null);
		}
	}

	protected void setChangeValue(ColumnIndex index, Object value, TableIndex tableIndex) {
		checkChangeSet();
		entityChangeSet.addChangeValue(index, value);
	}

	protected void setSingleReferenceValue(ColumnIndex index, Entity reference, TableIndex tableIndex) {
		AbstractUdbEntity entity = (AbstractUdbEntity) reference;
		RecordReference recordReference = null;
		if (entity != null) {
			recordReference = new RecordReference(entity.getId(), entity.getCorrelationId());
		}
		checkChangeSet();
		entityChangeSet.addChangeValue(index, recordReference);
		entityChangeSet.setReferenceChange(index, entity);
	}

	protected <OTHER_ENTITY extends Entity> List<OTHER_ENTITY> createEntityList(ColumnIndex index, EntityBuilder<OTHER_ENTITY> entityBuilder) {
		TransactionRecordValue changeValue = getChangeValue(index);
		MultiReferenceEditValue editValue = (MultiReferenceEditValue) changeValue.getValue();
		MultiReferenceIndex multiReferenceIndex = (MultiReferenceIndex) index;
		return createEntityList(editValue, multiReferenceIndex.getReferencesAsList(id), entityBuilder);
	}

	protected <OTHER_ENTITY extends Entity> List<OTHER_ENTITY> createEntityList(MultiReferenceEditValue editValue, List<Integer> referencedRecords, EntityBuilder<OTHER_ENTITY> entityBuilder) {
		Map<RecordReference, OTHER_ENTITY> entityByReference = (Map<RecordReference, OTHER_ENTITY>) entityChangeSet.getEntityByReference();
		List<OTHER_ENTITY> list = new ArrayList<>();
		if (!editValue.getSetReferences().isEmpty() || editValue.isRemoveAll()) {
			List<RecordReference> references = editValue.isRemoveAll() ? editValue.getAddReferences() : editValue.getSetReferences();
			references.forEach(reference -> {
				OTHER_ENTITY entity = entityByReference.get(reference);
				if (entity == null && reference.getRecordId() > 0) {
					entity = entityBuilder.build(reference.getRecordId());
				}
				if (entity != null) {
					list.add(entity);
				} else {
					log.error("Cannot add reference to list: no record id and no matching correlation id!");
				}
			});
			return list;
		} else {
			Set<Integer> removeSet = new HashSet<>();
			editValue.getRemoveReferences().forEach(reference -> {
				if (reference.getRecordId() > 0) {
					removeSet.add(reference.getRecordId());
				} else {
					OTHER_ENTITY entity = entityByReference.get(reference);
					if (entity != null) {
						removeSet.add(entity.getId());
					}
				}
			});
			List<OTHER_ENTITY> addEntities = new ArrayList<>();
			editValue.getAddReferences().forEach(reference -> {
				OTHER_ENTITY entity = entityByReference.get(reference);
				if (entity == null && reference.getRecordId() > 0) {
					entity = entityBuilder.build(reference.getRecordId());
				}
				if (entity != null) {
					addEntities.add(entity);
				}
			});
			Set<Integer> addEntitySet = new HashSet<>();
			addEntities.forEach(entity -> addEntitySet.add(entity.getId()));
			for (Integer recordId : referencedRecords) {
				if (!removeSet.contains(recordId) && !addEntitySet.contains(recordId)) {
					list.add(entityBuilder.build(recordId));
				}
			}
			list.addAll(addEntities);
			return list;
		}
	}

	protected TransactionRecordValue getChangeValue(ColumnIndex index) {
		if (entityChangeSet == null) {
			return null;
		} else {
			return entityChangeSet.getChangeValue(index);
		}
	}

	protected Object getChangedValue(ColumnIndex index) {
		if (entityChangeSet == null) {
			return null;
		} else {
			return entityChangeSet.getChangeValue(index).getValue();
		}
	}

	protected AbstractUdbEntity getReferenceChangeValue(ColumnIndex index) {
		if (entityChangeSet == null) {
			return null;
		} else {
			return entityChangeSet.getReferenceChange(index);
		}
	}

	protected void addMultiReferenceValue(List<? extends Entity> entities, MultiReferenceIndex multiReferenceIndex) {
		if (entities == null || entities.isEmpty()) {
			return;
		}
		MultiReferenceEditValue editValue = getOrCreateMultiReferenceEditValue(multiReferenceIndex);
		List<RecordReference> references = createRecordReferences(entities);

		editValue.addReferences(references);
	}

	protected void removeMultiReferenceValue(List<? extends Entity> entities, MultiReferenceIndex multiReferenceIndex) {
		if (entities == null || entities.isEmpty()) {
			return;
		}
		MultiReferenceEditValue editValue = getOrCreateMultiReferenceEditValue(multiReferenceIndex);
		List<RecordReference> references = createRecordReferences(entities);
		editValue.removeReferences(references);
	}

	protected void setMultiReferenceValue(List<? extends Entity> entities, MultiReferenceIndex multiReferenceIndex) {
		if (entities == null || entities.isEmpty()) {
			removeAllMultiReferenceValue(multiReferenceIndex);
		} else {
			MultiReferenceEditValue editValue = getOrCreateMultiReferenceEditValue(multiReferenceIndex);
			List<RecordReference> references = createRecordReferences(entities);
			editValue.setReferences(references);
		}
	}

	protected void removeAllMultiReferenceValue(MultiReferenceIndex multiReferenceIndex) {
		MultiReferenceEditValue editValue = getOrCreateMultiReferenceEditValue(multiReferenceIndex);
		editValue.setRemoveAll();
	}

	private List<RecordReference> createRecordReferences(List<? extends Entity> entities) {
		List<RecordReference> references = new ArrayList<>();
		for (Entity entity : entities) {
			AbstractUdbEntity udbEntity = (AbstractUdbEntity) entity;
			RecordReference recordReference = new RecordReference(udbEntity.getId(), udbEntity.getCorrelationId());
			references.add(recordReference);
			entityChangeSet.addRecordReference(recordReference, entity);
		}
		return references;
	}

	private MultiReferenceEditValue getOrCreateMultiReferenceEditValue(MultiReferenceIndex multiReferenceIndex) {
		MultiReferenceEditValue editValue;
		TransactionRecordValue changeValue = getChangeValue(multiReferenceIndex);
		if (changeValue != null) {
			editValue = (MultiReferenceEditValue) changeValue.getValue();
		} else {
			editValue = new MultiReferenceEditValue();
			setChangeValue(multiReferenceIndex, editValue, tableIndex);
		}
		return editValue;
	}

	public boolean getBooleanValue(BooleanIndex index) {
		if (isChanged(index)) {
			return (boolean) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setBooleanValue(boolean value, BooleanIndex index) {
		if (getBooleanValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public short getShortValue(ShortIndex index) {
		if (isChanged(index)) {
			return (short) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setShortValue(short value, ShortIndex index) {
		if (getShortValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public int getIntValue(IntegerIndex index) {
		if (isChanged(index)) {
			return (int) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setIntValue(int value, IntegerIndex index) {
		if (getIntValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public long getLongValue(LongIndex index) {
		if (isChanged(index)) {
			return (long) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setLongValue(long value, LongIndex index) {
		if (getLongValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public float getFloatValue(FloatIndex index) {
		if (isChanged(index)) {
			return (float) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setFloatValue(float value, FloatIndex index) {
		if (getFloatValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public double getDoubleValue(DoubleIndex index) {
		if (isChanged(index)) {
			return (double) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setDoubleValue(double value, DoubleIndex index) {
		if (getDoubleValue(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public String getTextValue(TextIndex index) {
		if (isChanged(index)) {
			return (String) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setTextValue(String value, TextIndex index) {
		if (!Objects.equals(getTextValue(index), value)) {
 			setChangeValue(index, value, tableIndex);
		}
	}

	public TranslatableText getTranslatableTextValue(TranslatableTextIndex index) {
		if (isChanged(index)) {
			return (TranslatableText) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setTranslatableTextValue(TranslatableText value, TranslatableTextIndex index) {
		setChangeValue(index, value, tableIndex);
	}

	public Instant getTimestampValue(IntegerIndex index) {
		int value;
		if (isChanged(index)) {
			value = (int) getChangedValue(index);
		} else {
			value = index.getValue(getId());
		}
		return value == 0 ? null : Instant.ofEpochSecond(value);
	}

	public int getTimestampAsEpochSecond(IntegerIndex index) {
		if (isChanged(index)) {
			return (int) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public long getTimestampAsEpochMilli(IntegerIndex index) {
		if (isChanged(index)) {
			return ((Integer) getChangedValue(index)).longValue() * 1000L;
		} else {
			return index.getValue(getId()) * 1000L;
		}
	}

	public void setTimestampValue(Instant value, IntegerIndex index) {
		if (!Objects.equals(getTimestampValue(index), value)) {
			Integer intValue = value != null ? (int) value.getEpochSecond() : 0;
			setChangeValue(index, intValue, tableIndex);
		}
	}

	public void setTimestampAsEpochSecond(int value, IntegerIndex index) {
		if (getTimestampAsEpochSecond(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public void setTimestampAsEpochMilli(long value, IntegerIndex index) {
		if (getTimestampAsEpochMilli(index) != value) {
			int intValue = (int) (value / 1000);
			setChangeValue(index, intValue, tableIndex);
		}
	}

	public Instant getTimeValue(IntegerIndex index) {
		int value;
		if (isChanged(index)) {
			value = (int) getChangedValue(index);
		} else {
			value = index.getValue(getId());
		}
		return value == 0 ? null: Instant.ofEpochSecond(value);
	}

	public void setTimeValue(Instant value, IntegerIndex index) {
		if (!Objects.equals(getTimeValue(index), value)) {
			Integer intValue = value != null ? (int) value.getEpochSecond() : 0;
			setChangeValue(index, intValue, tableIndex);
		}
	}

	public Instant getDateValue(LongIndex index) {
		long value;
		if (isChanged(index)) {
			value = (long) getChangedValue(index);
		} else {
			value = index.getValue(getId());
		}
		return value == 0 ? null : Instant.ofEpochMilli(value);
	}

	public long getDateAsEpochMilli(LongIndex index) {
		if (isChanged(index)) {
			return (long) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setDateValue(Instant value, LongIndex index) {
		if (!Objects.equals(getDateValue(index), value)) {
			Long longValue = value != null ? value.toEpochMilli() : 0;
			setChangeValue(index, longValue, tableIndex);
		}
	}

	public void setDateAsEpochMilli(long value, LongIndex index) {
		if (getDateAsEpochMilli(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public Instant getDateTimeValue(LongIndex index) {
		long value;
		if (isChanged(index)) {
			value = (long) getChangedValue(index);
		} else {
			value = index.getValue(getId());
		}
		return value == 0 ? null : Instant.ofEpochMilli(value);
	}

	public long getDateTimeAsEpochMilli(LongIndex index) {
		if (isChanged(index)) {
			return (long) getChangedValue(index);
		} else {
			return index.getValue(getId());
		}
	}

	public void setDateTimeValue(Instant value, LongIndex index) {
		if (!Objects.equals(getDateTimeValue(index), value)) {
			Long longValue = value != null ? (long) value.toEpochMilli() : 0;
			setChangeValue(index, longValue, tableIndex);
		}
	}

	public void setDateTimeAsEpochMilli(long value, LongIndex index) {
		if (getDateTimeAsEpochMilli(index) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public LocalDate getLocalDateValue(LongIndex index) {
		long value;
		if (isChanged(index)) {
			value = (long) getChangedValue(index);
		} else {
			value = index.getValue(getId());
		}
		return value == 0 ? null : LocalDate.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC);
	}

	public void setLocalDateValue(LocalDate value, LongIndex index) {
		long longValue = value != null ? value.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000L : 0;
		LocalDate currentValue = getLocalDateValue(index);
		if (!Objects.equals(currentValue, value) && (currentValue != null || longValue != 0)) {
			setChangeValue(index, longValue, tableIndex);
		}
	}

	public void setLocalDateAsEpochMilli(long value, LongIndex index) {
		if (index.getValue(getId()) != value) {
			setChangeValue(index, value, tableIndex);
		}
	}

	public <ENUM extends Enum<ENUM>> ENUM getEnumValue(ShortIndex index, ENUM[] values) {
		short shortValue;
		if (isChanged(index)) {
			shortValue = (short) getChangedValue(index);
		} else {
			shortValue = index.getValue(getId());
		}
		return shortValue == 0 ? null : values[shortValue - 1];
	}

	public <ENUM extends Enum<ENUM>> void setEnumValue(ShortIndex index, ENUM value) {
		short shortValue = (short) (value != null ? value.ordinal() + 1 : 0);
		if (getShortValue(index) != shortValue) {
			setChangeValue(index, shortValue, tableIndex);
		}
	}

	public <OTHER_ENTITY extends Entity> List<OTHER_ENTITY> getMultiReferenceValue(MultiReferenceIndex index, EntityBuilder<OTHER_ENTITY> entityBuilder) {
		if (isChanged(index)) {
			return createEntityList(index, entityBuilder);
		} else {
			if (!index.isEmpty(getId())) {
				return createEntityList(entityBuilder, index.getReferencesAsList(getId()));
			} else {
				return Collections.emptyList();
			}
		}
	}

	public <OTHER_ENTITY extends Entity> int getMultiReferenceValueCount(MultiReferenceIndex index, EntityBuilder<OTHER_ENTITY> entityBuilder) {
		if (isChanged(index)) {
			return createEntityList(index, entityBuilder).size();
		} else {
			return index.getReferencesCount(getId());
		}
	}

	public <OTHER_ENTITY extends Entity> BitSet getMultiReferenceValueAsBitSet(MultiReferenceIndex index, EntityBuilder<OTHER_ENTITY> entityBuilder) {
		if (isChanged(index)) {
			BitSet bitSet = new BitSet();
			createEntityList(index, entityBuilder).stream().map(Entity::getId).forEach(bitSet::set);
			return bitSet;
		} else {
			return index.getReferencesAsBitSet(getId());
		}
	}

	public boolean isChanged(ColumnIndex index) {
		return entityChangeSet != null && entityChangeSet.isChanged(index);
	}

	protected int getEntityId(Entity entity) {
		if (entity == null) {
			return 0;
		} else {
			return entity.getId();
		}
	}

	protected Transaction getTransaction() {
		return transaction;
	}

	@Override
	public void clearChanges() {
		entityChangeSet = null;
	}

	@Override
	public boolean isModified() {
		return entityChangeSet != null;
	}

	private void checkChangeSet() {
		if (entityChangeSet == null) {
			entityChangeSet = new EntityChangeSet();
			createCorrelationId();
		}
	}

	public void save(Transaction transaction, TableIndex tableIndex, boolean strictChangeVerification) {
		if (entityChangeSet != null) {
			this.transaction = transaction;
			boolean update = !createEntity;
			TransactionRecord transactionRecord = new TransactionRecord(tableIndex, id, correlationId, transaction.getUserId(), update, false, strictChangeVerification);
			entityChangeSet.setTransactionRecordValues(transaction, transactionRecord, strictChangeVerification);
			transaction.addTransactionRecord(transactionRecord);
			clearChanges();
			createEntity = false;
		}
	}

	public void save(TableIndex tableIndex) {
		save(tableIndex, false);
	}

	public CompletableFuture<Boolean> saveAsynchronously(TableIndex tableIndex) {
		save(tableIndex, true);
		return new CompletableFuture<>();
	}

	public void save(TableIndex tableIndex, boolean asynchronous) {
		if (entityChangeSet != null) {
			transaction = Transaction.create();
			save(transaction, tableIndex, false);
			transaction.execute(asynchronous);
			if (id == 0) {
				id = transaction.getResolvedRecordIdByCorrelationId(correlationId);
			}
		}
	}

	public TableIndex getTableIndex() {
		return tableIndex;
	}

	public String getQualifiedName() {
		return tableIndex.getFQN();
	}

	public void delete(Transaction transaction, TableIndex tableIndex) {
		TransactionRecord transactionRecord = new TransactionRecord(tableIndex, id, 0, transaction.getUserId(), true);
		transaction.addTransactionRecord(transactionRecord);
		clearChanges();
	}

	public void delete(TableIndex tableIndex) {
		Transaction transaction = Transaction.create();
		delete(transaction, tableIndex);
		transaction.execute();
	}

	@Override
	public boolean isStored() {
		if (id > 0 && tableIndex.isStored(id)) {
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean isDeleted() {
		return tableIndex.isDeleted(id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		AbstractUdbEntity<?> that = (AbstractUdbEntity<?>) o;
		if (getId() > 0 && getId() == that.getId()) {
			return true;
		}
		return getCorrelationId() > 0 && getCorrelationId() == that.getCorrelationId();
	}

	@Override
	public int hashCode() {
		if (getId() > 0) {
			return getId();
		} else {
			return getCorrelationId();
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(tableIndex.getName()).append(": ").append(getId()).append("\n");
		List<ColumnIndex> sortedFields = new ArrayList<>();
		sortedFields.addAll(tableIndex.getColumnIndices().stream().filter(column -> !Table.isReservedMetaName(column.getName())).collect(Collectors.toList()));
		sortedFields.addAll(tableIndex.getColumnIndices().stream().filter(column -> Table.isReservedMetaName(column.getName())).collect(Collectors.toList()));
		for (ColumnIndex column : sortedFields) {
			sb.append("\t").append(column.getName()).append(": ").append(column.getStringValue(getId()));
			if (isChanged(column)) {
				TransactionRecordValue changeValue = getChangeValue(column);
				sb.append(" -> ").append(changeValue.getValue());
			}
			sb.append("\n");
		}
		return sb.toString();
	}
}
