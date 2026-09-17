package com.ptcrys.fpsmatch.core.network.download;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record DownloadResult(Path filePath, long fileSize, String fileName, Map<String, List<String>> headers) {

    public static DownloadResult empty() {
        return new DownloadResult(Path.of(""), 0, "", new HashMap<>());
    }
}
