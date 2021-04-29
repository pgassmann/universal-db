package org.teamapps.universaldb.index.buffer;

import com.google.common.io.Files;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

public class BlockChainAtomicStoreTest {

	private static BlockChainAtomicStore store;

	@BeforeClass
	public static void setup() {
		File tempDir = Files.createTempDir();
		store = new BlockChainAtomicStore(tempDir, "blockChainAtomicStoreTest");
	}

	@AfterClass
	public static void tearDown() {
		store.drop();
	}

	@Test
	public void getEntryCount() {
		List<Integer> list = createList(1);
		store.setEntries(1, list);
		assertEquals(list.size(), store.getEntryCount(1));
		list = createList(1, 10_000);
		store.setEntries(1, list);
		assertEquals(list.size(), store.getEntryCount(1));
	}

	@Test
	public void removeEntries() {
		List<Integer> list = createList(1, 2, 3, 4, 5);
		store.setEntries(1, list);
		check(list, store.getEntries(1));
		list.remove(0);
		store.removeEntries(1, Collections.singletonList(1));
		check(list, store.getEntries(1));
		list = createList(100, 100_000);
		store.setEntries(1, list);
		check(list, store.getEntries(1));
		List<Integer> removeList = createList(101, 99_999);
		list.removeAll(new HashSet<>(removeList));
		store.removeEntries(1, removeList);
		check(list, store.getEntries(1));
		assertEquals(2, store.getEntryCount(1));
	}

	@Test
	public void addEntries() {
		for (int id = 100; id < 110; id++) {
			for (int i = 1; i < 10_000; i++) {
				store.addEntries(id, Collections.singletonList(i));
				assertEquals(i, store.getEntryCount(id));
			}
		}
		List<Integer> list = createList(1, 10_000);
		for (int id = 100; id < 110; id++) {
			check(list, store.getEntries(id));
		}
	}

	@Test
	public void setEntries() throws InterruptedException {
		List<Integer> list = createList(1);
		store.setEntries(1, list);
		check(list, store.getEntries(1));
		list = createList(100, 10_000);
		store.setEntries(1, list);
		check(list, store.getEntries(1));
	}

	private static List<Integer> createList(Integer ... values) {
		return new ArrayList<>(Arrays.asList(values));
	}

	private static List<Integer> createList(int start, int end) {
		List<Integer> list = new ArrayList<>();
		for (int i = start; i < end; i++) {
			list.add(i);
		}
		return list;
	}

	private static void check(List<Integer> expected, List<Integer> actual) {
		check(new HashSet<>(expected), new HashSet<>(actual));
	}

	private static void check(Set<Integer> expected, Set<Integer> actual) {
		assertEquals(expected, actual);
		if (expected.size() != actual.size()) {
			System.err.println("Wrong size expected:" + expected.size() + ", actual:" + actual.size());
		}
		for (Integer value : expected) {
			if (!actual.contains(value)) {
				System.err.println("Missing value:" + value);
			}
		}
	}


}