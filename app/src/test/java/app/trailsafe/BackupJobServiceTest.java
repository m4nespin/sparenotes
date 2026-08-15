package app.trailsafe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public final class BackupJobServiceTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void stageChildStaysInsideParent() throws Exception {
        File root = temporary.newFolder("staging");
        File parent = new File(root, "folder");

        assertEquals(new File(parent, "note.pdf").getCanonicalFile(),
                BackupJobService.safeStageChild(root, parent, "note.pdf"));
    }

    @Test
    public void stageChildRejectsTraversalName() throws Exception {
        File root = temporary.newFolder("staging");

        try {
            BackupJobService.safeStageChild(root, root, "..");
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
            BackupJobService.safeStageChild(root, outside, "session.json");
            fail("Expected escaped parent to be rejected");
        } catch (SecurityException expected) {
            // Expected.
        }
    }
}
