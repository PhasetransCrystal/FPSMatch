package com.ptcrys.fpsmatch.core.network.download;

import com.ptcrys.fpsmatch.core.network.NetworkModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class Downloader {

    private static final Logger logger = LoggerFactory.getLogger("FPSMatch Downloader");
    private static final Downloader INSTANCE = new Downloader();
    private final Map<String, NetworkModule> modules = new HashMap<>();

    public static Downloader Instance() {
        return INSTANCE;
    }

    public void download(IDownloadAble downloadAble) {
        String url = downloadAble.getUrl();
        if (!modules.containsKey(url)) {
            NetworkModule module = NetworkModule.initializeNetworkModule(url);
            modules.put(url, module);

            module.newRequest()
                    .downloadRequest()
                    .saveTo(downloadAble.getFile().toPath())
                    .downloadAsyncAndClose()
                    .thenAccept(result -> {
                        logger.info("Download Success: {}", result.fileName());
                    })
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            logger.error("Download Fail: ", throwable);
                        } else {
                            downloadAble.onDownloadCompleted();
                        }
                        modules.remove(url);
                    });
        }
    }

    public void stop(IDownloadAble downloadAble) {
        String url = downloadAble.getUrl();
        if (modules.containsKey(url)) {
            NetworkModule module = modules.get(url);
            module.shutdown();
            modules.remove(url);
        }
    }
}
