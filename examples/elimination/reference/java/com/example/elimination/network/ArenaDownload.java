package com.example.elimination.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.ptcrys.fpsmatch.core.network.NetworkModule;
import net.ptcrys.fpsmatch.core.network.download.DownloadException;
import net.ptcrys.fpsmatch.core.network.download.DownloadResult;

public final class ArenaDownload {
    public static DownloadResult download(String url, Path destination)
            throws IOException, DownloadException {
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        NetworkModule module = NetworkModule.initializeNetworkModule(url);
        try {
            return module.newRequest().downloadRequest()
                    .saveTo(target)
                    .download();
        } finally {
            module.shutdown();
        }
    }
}
