package protonbackup.ui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogFolderPickerTest {

    @TempDir Path root;

    @Test
    void listsFoldersOnlyAndNeverHiddenOnes() throws Exception {
        Files.createDirectories(root.resolve(".git/objects"));
        Files.createDirectory(root.resolve(".config"));
        Files.createDirectory(root.resolve("Zebra"));
        Files.createDirectory(root.resolve("apple"));
        Files.createDirectory(root.resolve("Mango"));
        Files.writeString(root.resolve("file.txt"), "x");
        Files.writeString(root.resolve(".hidden-file"), "x");
        Files.createSymbolicLink(root.resolve("link-to-apple"), root.resolve("apple"));

        var names = DialogFolderPicker.visibleSubfolders(root).stream().map(p -> p.getFileName().toString()).toList();

        assertEquals(List.of("apple", "link-to-apple", "Mango", "Zebra"), names);
    }

    @Test
    void anUnreadableOrMissingFolderIsEmptyNotAnError() {
        assertEquals(List.of(), DialogFolderPicker.visibleSubfolders(root.resolve("nope")));
    }
}
