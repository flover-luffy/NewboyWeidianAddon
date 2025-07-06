
package net.lawaxi.sbwa.util;

import java.io.File;
import java.util.Objects;

public final class Common {
    private static volatile Common instance;
    private final File parentFolder;
    private final File documentFolder;
    private final File picFolder;
    private final File dataFolder;
    private final File historyFolder;

    private Common(File parentFolder) {
        this.parentFolder = Objects.requireNonNull(parentFolder);
        this.documentFolder = initFolder(new File(parentFolder, "documents"));
        this.dataFolder = initFolder(new File(parentFolder, "data"));
        this.picFolder = this.dataFolder; // 保持原有设计
        this.historyFolder = initFolder(new File(parentFolder, "histories"));
    }

    public static synchronized Common initialize(File parentFolder) {
        if (instance == null) {
            instance = new Common(parentFolder);
        }
        return instance;
    }

    public static Common getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Common not initialized");
        }
        return instance;
    }

    private File initFolder(File folder) {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new RuntimeException("Failed to create directory: " + folder);
        }
        return folder;
    }

    // Getter methods
    public File getParentFolder() { return parentFolder; }
    public File getDocumentFolder() { return documentFolder; }
    public File getPicFolder() { return picFolder; }
    public File getDataFolder() { return dataFolder; }
    public File getHistoryFolder() { return historyFolder; }
}
