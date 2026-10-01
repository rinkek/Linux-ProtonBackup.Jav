package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The JSON below is real {@code filesystem list --json} output captured during the original development. */
class RemoteNodeTest {

    private static final String FILE_JSON = """
            [
            {"uid":"xxxx~kvCc","parentUid":"xxxx~f30T","name":{"ok":true,"value":"same.txt"},
             "type":"file","mediaType":"text/plain;charset=utf-8","isShared":false,
             "creationTime":"2026-09-28T17:07:42.000Z","totalStorageSize":87,
             "activeRevision":{"uid":"xxxx~DL9Q","state":"active","storageSize":87,
               "claimedSize":9,"claimedModificationTime":"2026-09-28T17:07:39.989Z",
               "claimedDigests":{"sha1":"6ec44483dd1f0b0a3da37891d83d0cc3f4a98918"}},
             "treeEventScopeId":"xxxx"}
            ]
            """;

    private static final String FOLDER_JSON = """
            [{"uid":"xxxx~4yHG","type":"folder","name":{"ok":true,"value":"sub"},
              "isShared":false,"treeEventScopeId":"xxxx"}]
            """;

    private static final String UNDECRYPTABLE_NAME_JSON =
            "[{\"uid\":\"xxxx\",\"type\":\"file\",\"name\":{\"ok\":false,\"value\":null}}]";

    @Test
    void parsesARealFileEntryWithActiveRevision() throws Exception {
        var node = RemoteNode.parseList(FILE_JSON).get(0);

        assertEquals("xxxx~kvCc", node.uid());
        assertEquals("file", node.type());
        assertFalse(node.isFolder());
        assertEquals("same.txt", node.fileName());
        assertNotNull(node.activeRevision());
        assertEquals(9L, node.activeRevision().claimedSize());
        assertEquals(
                OffsetDateTime.parse("2026-09-28T17:07:39.989+00:00"),
                node.activeRevision().claimedModificationInstant());
    }

    @Test
    void parsesAFolderEntryWithoutActiveRevision() throws Exception {
        var node = RemoteNode.parseList(FOLDER_JSON).get(0);

        assertTrue(node.isFolder());
        assertEquals("sub", node.fileName());
        assertNull(node.activeRevision());
    }

    @Test
    void fileNameIsNullWhenDecryptionFailed() throws Exception {
        var node = RemoteNode.parseList(UNDECRYPTABLE_NAME_JSON).get(0);

        assertNotNull(node.name());
        assertFalse(node.name().ok());
        assertNull(node.fileName());
    }

    @Test
    void emptyOrBlankInputReturnsNoNodes() throws Exception {
        assertEquals(List.of(), RemoteNode.parseList(""));
        assertEquals(List.of(), RemoteNode.parseList("   "));
        assertEquals(List.of(), RemoteNode.parseList("[\n\n]"));
        assertEquals(List.of(), RemoteNode.parseList(null));
    }

    @Test
    void propertyNamesAreMatchedCaseInsensitively() throws Exception {
        var node = RemoteNode.parseList("[{\"UID\":\"u\",\"Type\":\"folder\"}]").get(0);

        assertEquals("u", node.uid());
        assertTrue(node.isFolder());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(JsonProcessingException.class, () -> RemoteNode.parseList("not json"));
    }
}
