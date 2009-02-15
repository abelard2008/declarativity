package bfs.test;

import org.junit.Test;

public class BFSData2Test extends DataCommon {
    private static final String TEST_FILE = "/usr/share/dict/words";

	@Test(timeout=24000)
    public void test1() {
        test(TEST_FILE, 3, 1, 9);
        checkFiles();
        cleanupAll();
    }

	public static void main(String[] args) throws Exception {
		BFSData2Test t = new BFSData2Test();
		t.test1();
	}
}
