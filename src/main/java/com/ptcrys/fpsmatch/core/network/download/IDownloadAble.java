package com.ptcrys.fpsmatch.core.network.download;

import java.io.File;

public interface IDownloadAble {

    String getUrl();

    File getFile();

    default void onDownloadCompleted() {}
}
