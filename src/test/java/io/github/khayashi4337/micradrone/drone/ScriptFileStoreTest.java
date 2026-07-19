package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void folderNameEncodesGridPosition() {
        assertEquals("1_-2_3", ScriptFileStore.folderName(1, -2, 3));
    }

    @Test
    void ensureControllerFolderSeedsEverySampleScriptWhenMissing() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");

        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, 5, 64, -5);

        assertEquals(scriptsDir.resolve("5_64_-5"), folder);
        for (String name : SampleScripts.ALL.keySet()) {
            assertTrue(Files.exists(folder.resolve(name)), name + " should have been seeded");
        }
        assertEquals(SampleScripts.MAIN, ScriptFileStore.load(folder.resolve(ScriptFileStore.DEFAULT_SCRIPT_NAME)));
    }

    @Test
    void ensureControllerFolderNeverOverwritesAnExistingFile() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Path folder = scriptsDir.resolve(ScriptFileStore.folderName(0, 0, 0));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(ScriptFileStore.DEFAULT_SCRIPT_NAME), "move(\"east\")\n");

        ScriptFileStore.ensureControllerFolder(scriptsDir, 0, 0, 0);

        assertEquals("move(\"east\")\n", ScriptFileStore.load(folder.resolve(ScriptFileStore.DEFAULT_SCRIPT_NAME)));
    }

    @Test
    void ensureControllerFolderRestoresADeletedSampleAsAPermanentReferenceLibrary() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        ScriptFileStore.ensureControllerFolder(scriptsDir, 1, 2, 3);
        Path folder = scriptsDir.resolve(ScriptFileStore.folderName(1, 2, 3));
        Files.delete(folder.resolve("move_square.mdrone"));

        ScriptFileStore.ensureControllerFolder(scriptsDir, 1, 2, 3);

        assertTrue(Files.exists(folder.resolve("move_square.mdrone")));
        assertEquals(SampleScripts.MOVE_SQUARE, ScriptFileStore.load(folder.resolve("move_square.mdrone")));
    }

    @Test
    void ensureControllerFolderKeepsAnEditedSampleAsIs() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        ScriptFileStore.ensureControllerFolder(scriptsDir, 1, 2, 3);
        Path folder = scriptsDir.resolve(ScriptFileStore.folderName(1, 2, 3));
        Files.writeString(folder.resolve("move_square.mdrone"), "print(\"edited\")\n");

        ScriptFileStore.ensureControllerFolder(scriptsDir, 1, 2, 3);

        assertEquals("print(\"edited\")\n", ScriptFileStore.load(folder.resolve("move_square.mdrone")));
    }

    @Test
    void listScriptsReturnsOnlyMdroneFilesSorted() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, 0, 0, 0);
        Files.writeString(folder.resolve("notes.txt"), "not a script");

        List<String> names = ScriptFileStore.listScripts(folder);

        assertEquals(List.copyOf(SampleScripts.ALL.keySet()).stream().sorted().toList(), names);
    }

    @Test
    void listScriptsReturnsEmptyListForAMissingFolder() throws IOException {
        List<String> names = ScriptFileStore.listScripts(tempDir.resolve("does_not_exist"));
        assertEquals(List.of(), names);
    }

    @Test
    void describeScriptUsesTheFirstNonEmptyCommentLine() {
        String content = """
                # Tills and plants wheat across the whole plot.
                size = get_world_size()
                """;
        assertEquals("Tills and plants wheat across the whole plot.", ScriptFileStore.describeScript(content, "fallback.mdrone"));
    }

    @Test
    void describeScriptSkipsLeadingBlankLinesBeforeTheComment() {
        String content = "\n\n  # after some blank lines\nmove(\"east\")\n";
        assertEquals("after some blank lines", ScriptFileStore.describeScript(content, "fallback.mdrone"));
    }

    @Test
    void describeScriptFallsBackToTheFileNameWhenThereIsNoLeadingComment() {
        assertEquals("fallback.mdrone", ScriptFileStore.describeScript("move(\"east\")\n", "fallback.mdrone"));
    }

    @Test
    void describeScriptFallsBackWhenTheOnlyCommentLineIsEmpty() {
        assertEquals("fallback.mdrone", ScriptFileStore.describeScript("#\nmove(\"east\")\n", "fallback.mdrone"));
    }

    @Test
    void listScriptsWithDescriptionsPairsEveryFileWithItsDescription() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, 0, 0, 0);

        Map<String, String> described = ScriptFileStore.listScriptsWithDescriptions(folder);

        assertEquals(SampleScripts.ALL.size(), described.size());
        assertEquals(
                ScriptFileStore.describeScript(SampleScripts.MAIN, ScriptFileStore.DEFAULT_SCRIPT_NAME),
                described.get(ScriptFileStore.DEFAULT_SCRIPT_NAME));
    }
}
