package app.trailsafe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class BackupRunnerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void stageChildStaysInsideParent() throws Exception {
        File root = temporary.newFolder("staging");
        File parent = new File(root, "folder");

        assertEquals(new File(parent, "note.pdf").getCanonicalFile(),
                BackupRunner.safeStageChild(root, parent, "note.pdf"));
    }

    @Test
    public void stageChildRejectsTraversalName() throws Exception {
        File root = temporary.newFolder("staging");

        try {
            BackupRunner.safeStageChild(root, root, "..");
            fail("Expected traversal name to be rejected");
        } catch (SecurityException expected) {
            // Expected.
        }
    }

    @Test
    public void stageChildRejectsEscapedParent() throws Exception {
        File root = temporary.newFolder("staging");
        File outside = temporary.newFolder("outside");

        try {
            BackupRunner.safeStageChild(root, outside, "session.json");
            fail("Expected escaped parent to be rejected");
        } catch (SecurityException expected) {
            // Expected.
        }
    }

    @Test
    public void batchFlushesAtEitherLimit() {
        assertFalse(BackupRunner.shouldFlush(BackupRunner.BATCH_BYTES - 1, BackupRunner.BATCH_FILES - 1));
        assertTrue(BackupRunner.shouldFlush(BackupRunner.BATCH_BYTES, 1));
        assertTrue(BackupRunner.shouldFlush(1, BackupRunner.BATCH_FILES));
    }

    @Test
    public void readsRemoteSkipsFromTransferSummary() throws Exception {
        assertEquals(7, BackupRunner.remotelySkipped(
                "{\"transferredItems\":2,\"skippedItems\":7,\"failedItems\":0}", 9));
    }

    @Test
    public void fingerprintsContentNotMetadata() throws Exception {
        ByteArrayOutputStream firstCopy = new ByteArrayOutputStream();
        ByteArrayOutputStream secondCopy = new ByteArrayOutputStream();
        String first = BackupRunner.copyAndFingerprint(
                new ByteArrayInputStream("same size A".getBytes(StandardCharsets.UTF_8)), firstCopy, () -> false);
        String second = BackupRunner.copyAndFingerprint(
                new ByteArrayInputStream("same size B".getBytes(StandardCharsets.UTF_8)), secondCopy, () -> false);

        assertFalse(first.equals(second));
        assertEquals("same size A", firstCopy.toString(StandardCharsets.UTF_8.name()));
    }

    @Test
    public void rejectsFolderCyclesAndUnsafeDepth() {
        Set<String> ancestors = new HashSet<>();
        BackupRunner.enterFolder(ancestors, "root", 0);
        try {
            BackupRunner.enterFolder(ancestors, "root", 1);
            fail("Expected folder cycle to be rejected");
        } catch (SecurityException expected) {
            // Expected.
        }

        try {
            BackupRunner.enterFolder(new HashSet<>(), "deep", BackupRunner.MAX_DEPTH + 1);
            fail("Expected unsafe nesting to be rejected");
        } catch (SecurityException expected) {
            // Expected.
        }
    }
}
