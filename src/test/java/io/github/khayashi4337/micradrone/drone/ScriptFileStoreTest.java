package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aliasFolderNameFallsBackToCoordinatesWhenBlank() {
        assertEquals("1_-2_3", ScriptFileStore.folderName("", 1, -2, 3));
        assertEquals("1_-2_3", ScriptFileStore.folderName("   ", 1, -2, 3));
    }

    @Test
    void aliasFolderNameIsTheAliasWithTheCoordinateSuffixAppended() {
        assertEquals("North Farm_1_-2_3", ScriptFileStore.folderName("North Farm", 1, -2, 3));
    }

    @Test
    void aliasFolderNameSanitizesFilesystemInvalidCharacters() {
        // < > : " / \ | ? * are all invalid in a Windows folder name.
        assertEquals("abcdefgh_1_2_3", ScriptFileStore.folderName("a<b>c:d\"e/f\\g|h?*", 1, 2, 3));
    }

    @Test
    void aliasFolderNameSanitizesUnicodeAliasesUntouched() {
        // Japanese text isn't filesystem-invalid, so it should survive as-is (aliases are player-typed).
        assertEquals("北の畑_1_2_3", ScriptFileStore.folderName("北の畑", 1, 2, 3));
    }

    @Test
    void aliasFolderNameStripsTrailingDotsAndSpaces() {
        // Windows folder names can't end in a dot or a space.
        assertEquals("North Farm_1_2_3", ScriptFileStore.folderName("North Farm. . ", 1, 2, 3));
    }

    @Test
    void aliasFolderNameEscapesReservedWindowsDeviceNames() {
        assertEquals("CON__1_2_3", ScriptFileStore.folderName("CON", 1, 2, 3));
        assertEquals("com1__1_2_3", ScriptFileStore.folderName("com1", 1, 2, 3));
    }

    @Test
    void aliasFolderNameFallsBackToCoordinatesWhenSanitizingLeavesNothing() {
        assertEquals("1_2_3", ScriptFileStore.folderName("///", 1, 2, 3));
    }

    @Test
    void ensureControllerFolderCreatesAnEmptyFolder() throws IOException {
        // Sample seeding was retired with the GUI-reduction rework (issue #7): samples now arrive
        // as scroll items in the auto-placed library container, not as files.
        Path scriptsDir = tempDir.resolve("scripts");

        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, 5, 64, -5);

        assertEquals(scriptsDir.resolve("5_64_-5"), folder);
        assertEquals(List.of(), ScriptFileStore.listScripts(folder));
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
    void ensureControllerFolderWithAliasCreatesTheAliasNamedFolder() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");

        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, "North Farm", 5, 64, -5);

        assertEquals(scriptsDir.resolve("North Farm_5_64_-5"), folder);
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void renameControllerFolderMovesExistingScriptsToTheNewAliasName() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Path oldFolder = ScriptFileStore.ensureControllerFolder(scriptsDir, "", 1, 2, 3);
        Files.writeString(oldFolder.resolve(ScriptFileStore.DEFAULT_SCRIPT_NAME), "print(\"mine\")\n");

        ScriptFileStore.renameControllerFolder(scriptsDir, "", "North Farm", 1, 2, 3);

        Path newFolder = scriptsDir.resolve("North Farm_1_2_3");
        assertTrue(Files.isDirectory(newFolder), "renamed folder should exist");
        assertEquals("print(\"mine\")\n", ScriptFileStore.load(newFolder.resolve(ScriptFileStore.DEFAULT_SCRIPT_NAME)));
        assertTrue(Files.notExists(oldFolder), "old folder should no longer exist after the rename");
    }

    @Test
    void renameControllerFolderIsANoOpWhenTheAliasDidNotActuallyChange() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        ScriptFileStore.ensureControllerFolder(scriptsDir, "North Farm", 1, 2, 3);

        ScriptFileStore.renameControllerFolder(scriptsDir, "North Farm", "North Farm", 1, 2, 3);

        assertTrue(Files.isDirectory(scriptsDir.resolve("North Farm_1_2_3")));
    }

    @Test
    void renameControllerFolderIsANoOpWhenThereIsNoOldFolderToMove() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");

        ScriptFileStore.renameControllerFolder(scriptsDir, "", "North Farm", 1, 2, 3);

        assertTrue(Files.notExists(scriptsDir.resolve("North Farm_1_2_3")),
                "nothing should have been created - the folder is created lazily on first real use");
    }

    @Test
    void listScriptsReturnsOnlyMdroneFilesSorted() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Path folder = ScriptFileStore.ensureControllerFolder(scriptsDir, 0, 0, 0);
        Files.writeString(folder.resolve("b.mdrone"), "till()\n");
        Files.writeString(folder.resolve("a.mdrone"), "till()\n");
        Files.writeString(folder.resolve("notes.txt"), "not a script");

        List<String> names = ScriptFileStore.listScripts(folder);

        assertEquals(List.of("a.mdrone", "b.mdrone"), names);
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
        Files.writeString(folder.resolve("greet.mdrone"), "# Says hello.\nprint(\"hi\")\n");

        Map<String, String> described = ScriptFileStore.listScriptsWithDescriptions(folder);

        assertEquals(1, described.size());
        assertEquals("Says hello.", described.get("greet.mdrone"));
    }

    @Test
    void isValidScriptNameAcceptsPlainMdroneNames() {
        assertTrue(ScriptFileStore.isValidScriptName("main.mdrone"));
        assertTrue(ScriptFileStore.isValidScriptName("my farm v2.mdrone"));
    }

    @Test
    void isValidScriptNameRejectsPathEscapes() {
        // These names arrive over the network from the client, so anything that could resolve
        // outside the controller's script folder must be rejected.
        assertFalse(ScriptFileStore.isValidScriptName("../main.mdrone"));
        assertFalse(ScriptFileStore.isValidScriptName("sub/main.mdrone"));
        assertFalse(ScriptFileStore.isValidScriptName("sub\\main.mdrone"));
        assertFalse(ScriptFileStore.isValidScriptName("C:evil.mdrone"));
    }

    @Test
    void isValidScriptNameRejectsNonMdroneAndEmptyNames() {
        assertFalse(ScriptFileStore.isValidScriptName(null));
        assertFalse(ScriptFileStore.isValidScriptName(""));
        assertFalse(ScriptFileStore.isValidScriptName(".mdrone"));
        assertFalse(ScriptFileStore.isValidScriptName("notes.txt"));
    }
}
