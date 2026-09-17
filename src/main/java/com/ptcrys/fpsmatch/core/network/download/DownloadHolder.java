package com.ptcrys.fpsmatch.core.network.download;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.io.File;

public record DownloadHolder(String url, File file) implements IDownloadAble {

    public static final Codec<DownloadHolder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(DownloadHolder::url),
            Codec.STRING.fieldOf("url").forGetter(DownloadHolder::getFileStr)).apply(instance, DownloadHolder::new));

    public DownloadHolder(String url, String file) {
        this(url, new File(file));
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public File getFile() {
        return file;
    }

    public String getFileStr() {
        return file.getAbsolutePath();
    }
}
