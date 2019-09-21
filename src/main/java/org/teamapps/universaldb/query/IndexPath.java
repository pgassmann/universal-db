/*-
 * ========================LICENSE_START=================================
 * UniversalDB
 * ---
 * Copyright (C) 2014 - 2019 TeamApps.org
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
package org.teamapps.universaldb.query;

import org.teamapps.universaldb.index.TableIndex;
import org.teamapps.universaldb.index.reference.multi.MultiReferenceIndex;
import org.teamapps.universaldb.index.reference.single.SingleReferenceIndex;

import java.util.BitSet;
import java.util.Objects;
import java.util.PrimitiveIterator;

public class IndexPath implements Comparable<IndexPath>{

	private boolean localPath;
	private boolean cyclicPath = true;
	private MultiReferenceIndex[] forwardMultiPath;
	private SingleReferenceIndex[] forwardSinglePath;

	private MultiReferenceIndex[] backwardMultiPath;
	private SingleReferenceIndex[] backwardSinglePath;

	private String pathId;

	public IndexPath() {
		localPath = true;
		createPathId();
	}

	public boolean isSamePath(IndexPath path) {
		return getPathId().equals(path.getPathId());
	}

	public void addPath(IndexPath path) {
		if (path.isLocalPath()) {
			return;
		}
		SingleReferenceIndex[] forwardSinglePath = path.forwardSinglePath;
		MultiReferenceIndex[] forwardMultiPath = path.forwardMultiPath;
		SingleReferenceIndex[] backwardSinglePath = path.backwardSinglePath;
		MultiReferenceIndex[] backwardMultiPath = path.backwardMultiPath;
		int len = forwardSinglePath.length;
		for (int i = 0; i < len; i++) {
			if (forwardSinglePath[i] != null) {
				if (backwardSinglePath[i] != null) {
					addPath(forwardSinglePath[i], backwardSinglePath[i]);
				} else {
					addPath(forwardSinglePath[i], backwardMultiPath[i]);
				}
			} else {
				if (backwardSinglePath[i] != null) {
					addPath(forwardMultiPath[i], backwardSinglePath[i]);
				} else {
					addPath(forwardMultiPath[i], backwardMultiPath[i]);
				}
			}
		}
	}

	public void addPath(MultiReferenceIndex forwardIndex) {
		increasePath();
		forwardMultiPath[forwardMultiPath.length - 1] = forwardIndex;
		cyclicPath = false;
	}

	public void addPath(SingleReferenceIndex forwardIndex) {
		increasePath();
		forwardSinglePath[forwardSinglePath.length - 1] = forwardIndex;
		cyclicPath = false;
	}

	public void addPath(MultiReferenceIndex forwardIndex, MultiReferenceIndex backwardIndex) {
		increasePath();
		forwardMultiPath[forwardMultiPath.length - 1] = forwardIndex;
		backwardMultiPath[0] = backwardIndex;
		createPathId();
	}

	public void addPath(MultiReferenceIndex forwardIndex, SingleReferenceIndex backwardIndex) {
		increasePath();
		forwardMultiPath[forwardMultiPath.length - 1] = forwardIndex;
		backwardSinglePath[0] = backwardIndex;
		createPathId();
	}

	public void addPath(SingleReferenceIndex forwardIndex, MultiReferenceIndex backwardIndex) {
		increasePath();
		forwardSinglePath[forwardSinglePath.length - 1] = forwardIndex;
		backwardMultiPath[0] = backwardIndex;
		createPathId();
	}

	public void addPath(SingleReferenceIndex forwardIndex, SingleReferenceIndex backwardIndex) {
		increasePath();
		forwardSinglePath[forwardSinglePath.length - 1] = forwardIndex;
		backwardSinglePath[0] = backwardIndex;
		createPathId();
	}

	public BitSet calculatePathBitSet(BitSet records) {
		if (isLocalPath()) {
			return records;
		}
		if (!cyclicPath && getExpense() > 12) {
			return getLeafTable().getRecordBitSet();
		}
		return calculatePath(records, false);
	}

	public BitSet calculateReversePath(BitSet records, BitSet originRecords) {
		if (isLocalPath()) {
			return records;
		}
		if (!cyclicPath || getReverseExpense() > 9) {
			return calculatePathMath(originRecords, records);
		} else {
			BitSet localRecords = calculatePath(records, true);
			localRecords.and(originRecords);
			return localRecords;
		}
	}

	public int getSinglePathLeafId(int id) {
		if(isLocalPath()) {
			return id;
		}
		int pathLength = forwardSinglePath.length;
		for (int i = 0; i < pathLength; i++) {
			SingleReferenceIndex singleRefIndex = forwardSinglePath[i];
			id = singleRefIndex.getValue(id);
			if (id <= 0) {
				return 0;
			}
		}
		return id;
	}

	public TableIndex getLeafTable() {
		int length = forwardSinglePath.length;
		if (forwardSinglePath[length - 1] != null) {
			return forwardSinglePath[length - 1].getReferencedTable();
		} else {
			return forwardMultiPath[length - 1].getReferencedTable();
		}
	}

	private BitSet calculatePath(BitSet records, boolean reversePath) {
		if(isLocalPath()) {
			return records;
		}
		MultiReferenceIndex[] multiPath = forwardMultiPath;
		SingleReferenceIndex[] singlePath = forwardSinglePath;
		if (reversePath) {
			multiPath = backwardMultiPath;
			singlePath = backwardSinglePath;
		}
		int pathLength = multiPath.length;
		BitSet result = new BitSet();
		BitSet bitSet = null;
		for (int i = 0; i < pathLength; i++) {
			if (bitSet == null) {
				bitSet = records;
			} else {
				bitSet = result;
				result = new BitSet();
			}
			MultiReferenceIndex multiReferenceIndex = multiPath[i];
			if (multiReferenceIndex != null) {
				for (int id = bitSet.nextSetBit(0); id >= 0; id = bitSet.nextSetBit(id + 1)) {
					BitSet references = multiReferenceIndex.getReferencesAsBitSet(id);
					result.or(references);
				}
			} else {
				SingleReferenceIndex singleRefIndex = singlePath[i];
				for (int id = bitSet.nextSetBit(0); id >= 0; id = bitSet.nextSetBit(id + 1)) {
					int value = singleRefIndex.getValue(id);
					if (value > 0) {
						result.set(value);
					}
				}
			}
		}
		return result;
	}

	private BitSet calculatePathMath(BitSet records, BitSet matchingLeafRecords) {
		BitSet result = new BitSet();
		int maxPos = forwardSinglePath.length - 1;
		for (int id = records.nextSetBit(0); id >= 0; id = records.nextSetBit(id + 1)) {
			if (isMatch(0, maxPos, id, matchingLeafRecords)) {
				result.set(id);
			}
		}
		return result;
	}

	private boolean isMatch(int pathPos, int maxPos, int id, BitSet matchingLeafRecords) {
		if (pathPos == maxPos) {
			if (forwardSinglePath[pathPos] != null) {
				int value = forwardSinglePath[pathPos].getValue(id);
				return matchingLeafRecords.get(value);
			} else {
				PrimitiveIterator.OfInt references = forwardMultiPath[pathPos].getReferences(id);
				if (references == null) {
					return false;
				} else {
					while (references.hasNext()) {
						if (matchingLeafRecords.get(references.nextInt())) {
							return true;
						}
					}
					return false;
				}
			}
		} else {
			if (forwardSinglePath[pathPos] != null) {
				int value = forwardSinglePath[pathPos].getValue(id);
				return isMatch(pathPos + 1, maxPos, value, matchingLeafRecords);
			} else {
				PrimitiveIterator.OfInt references = forwardMultiPath[pathPos].getReferences(id);
				if (references == null) {
					return false;
				} else {
					while (references.hasNext()) {
						if (isMatch(pathPos + 1, maxPos, references.nextInt(), matchingLeafRecords)) {
							return true;
						}
					}
					return false;
				}
			}
		}
	}

	public boolean isCyclicPath() {
		return !localPath && cyclicPath;
	}

	public boolean isLocalPath() {
		return localPath;
	}

	public String getPathId() {
		return pathId;
	}

	public int getExpense() {
		int expense = 0;
		if(isLocalPath()) {
			return expense;
		}
		for (MultiReferenceIndex multiReferenceIndex : forwardMultiPath) {
			if (multiReferenceIndex != null) {
				expense += 10;
			} else {
				expense += 1;
			}
		}
		return expense;
	}

	public int getReverseExpense() {
		int expense = 0;
		if(isLocalPath()) {
			return expense;
		}
		for (MultiReferenceIndex multiReferenceIndex : backwardMultiPath) {
			if (multiReferenceIndex != null) {
				expense += 10;
			} else {
				expense += 1;
			}
		}
		return expense;
	}

	private void createPathId() {
		if(isLocalPath()) {
			pathId = "empty";
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < forwardMultiPath.length; i++) {
			MultiReferenceIndex multiReferenceIndex = forwardMultiPath[i];
			if (multiReferenceIndex != null) {
				sb.append(multiReferenceIndex.getMappingId()).append(".");
			} else {
				SingleReferenceIndex singleRefIndex = forwardSinglePath[i];
				sb.append(singleRefIndex.getMappingId()).append(".");
			}
		}
		pathId = sb.toString();
	}

	private void increasePath() {
		localPath = false;
		this.forwardMultiPath = increasePath(this.forwardMultiPath, false);
		this.forwardSinglePath = increasePath(this.forwardSinglePath, false);
		this.backwardMultiPath = increasePath(this.backwardMultiPath, true);
		this.backwardSinglePath = increasePath(this.backwardSinglePath, true);
	}

	private MultiReferenceIndex[] increasePath(MultiReferenceIndex[] path, boolean reversePath) {
		if (path == null) {
			path = new MultiReferenceIndex[1];
			return path;
		}
		MultiReferenceIndex[] newPath = new MultiReferenceIndex[path.length + 1];
		if (reversePath) {
			System.arraycopy(path, 0, newPath, 1, path.length);
		} else {
			System.arraycopy(path, 0, newPath, 0, path.length);
		}
		return newPath;
	}

	private SingleReferenceIndex[] increasePath(SingleReferenceIndex[] path, boolean reversePath) {
		if (path == null) {
			path = new SingleReferenceIndex[1];
			return path;
		}
		SingleReferenceIndex[] newPath = new SingleReferenceIndex[path.length + 1];
		if (reversePath) {
			System.arraycopy(path, 0, newPath, 1, path.length);
		} else {
			System.arraycopy(path, 0, newPath, 0, path.length);
		}
		return newPath;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		IndexPath path = (IndexPath) o;
		return getPathId().equals(path.getPathId());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getPathId());
	}

	@Override
	public int compareTo(IndexPath o) {
		int expense = getExpense();
		int otherExpense = o.getExpense();
		return expense - otherExpense;
	}
}
