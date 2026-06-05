package dev.gate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminController#execSql} の事前検証ロジック（純粋関数部分）の回帰テスト。
 * DB を必要としない package-private ヘルパのみを対象とする。
 */
class AdminControllerSqlTest {

    // --- #1: ファイルアクセス系フラグメントの遮断 ---

    @Test
    void blocksIntoOutfile() {
        String norm = AdminController.normalizeSql("SELECT * FROM t INTO OUTFILE '/tmp/x'");
        assertEquals("INTO OUTFILE", AdminController.matchedBlockedFragment(norm));
    }

    @Test
    void blocksLoadFileFunction() {
        String norm = AdminController.normalizeSql("SELECT LOAD_FILE('/etc/passwd')");
        assertEquals("LOAD_FILE", AdminController.matchedBlockedFragment(norm));
    }

    @Test
    void blocksIntoDumpfile() {
        String norm = AdminController.normalizeSql("select id from t into   dumpfile '/tmp/y'");
        assertEquals("INTO DUMPFILE", AdminController.matchedBlockedFragment(norm));
    }

    @Test
    void blocksDespiteCommentsAndCase() {
        // コメントで OUTFILE を割っても、コメント除去 → 正規化後に検出される
        String norm = AdminController.normalizeSql("SELECT 1 INTO /* c */ outfile '/tmp/z'");
        assertEquals("INTO OUTFILE", AdminController.matchedBlockedFragment(norm));
    }

    @Test
    void allowsPlainSelect() {
        assertNull(AdminController.matchedBlockedFragment(AdminController.normalizeSql("SELECT 1")));
        assertNull(AdminController.matchedBlockedFragment(
                AdminController.normalizeSql("SELECT id, name FROM users WHERE id = 1")));
    }

    // --- #4: 書き込みキーワード検出（Discord 通知要否） ---

    @Test
    void detectsWriteKeywords() {
        assertTrue(AdminController.isWriteKeyword(first("INSERT INTO t (a) VALUES (1)")));
        assertTrue(AdminController.isWriteKeyword(first("UPDATE t SET a = 1")));
        assertTrue(AdminController.isWriteKeyword(first("DELETE FROM t WHERE id = 1")));
        assertTrue(AdminController.isWriteKeyword(first("ALTER TABLE t ADD COLUMN x INT")));
    }

    @Test
    void readKeywordsAreNotWrites() {
        assertFalse(AdminController.isWriteKeyword(first("SELECT 1")));
        assertFalse(AdminController.isWriteKeyword(first("SHOW TABLES")));
        assertFalse(AdminController.isWriteKeyword(first("DESCRIBE t")));
        assertFalse(AdminController.isWriteKeyword(first("EXPLAIN SELECT 1")));
    }

    // --- splitStatements: クォート/エスケープ境界 ---

    @Test
    void splitsTopLevelSemicolons() {
        List<String> stmts = AdminController.splitStatements("SELECT 1; SELECT 2; SELECT 3");
        assertEquals(3, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("SELECT 3", stmts.get(2));
    }

    @Test
    void doesNotSplitSemicolonInsideStringLiteral() {
        List<String> stmts = AdminController.splitStatements("INSERT INTO t (a) VALUES ('x;y'); SELECT 1");
        assertEquals(2, stmts.size());
        assertEquals("INSERT INTO t (a) VALUES ('x;y')", stmts.get(0));
        assertEquals("SELECT 1", stmts.get(1));
    }

    @Test
    void handlesEscapedQuoteInsideLiteral() {
        List<String> stmts = AdminController.splitStatements("SELECT 'a\\'b;c'");
        assertEquals(1, stmts.size());
        assertEquals("SELECT 'a\\'b;c'", stmts.get(0));
    }

    @Test
    void trailingStatementWithoutSemicolonIsKept() {
        List<String> stmts = AdminController.splitStatements("SELECT 1");
        assertEquals(1, stmts.size());
        assertEquals("SELECT 1", stmts.get(0));
    }

    // --- stripSqlComments ---

    @Test
    void stripsLineAndBlockComments() {
        String stripped = AdminController.stripSqlComments("SELECT 1 -- comment\n, 2 /* block */ , 3");
        // コメントが除去され、本体トークンは残る
        assertFalse(stripped.contains("comment"));
        assertFalse(stripped.contains("block"));
        assertTrue(stripped.contains("SELECT 1"));
    }

    private static String first(String sql) {
        String norm = AdminController.normalizeSql(sql);
        return norm.isEmpty() ? "" : norm.split(" ", 2)[0];
    }
}
