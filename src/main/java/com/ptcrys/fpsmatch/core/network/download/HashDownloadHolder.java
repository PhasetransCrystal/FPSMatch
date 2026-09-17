package com.ptcrys.fpsmatch.core.network.download;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.core.data.HashData;
import com.ptcrys.fpsmatch.util.hash.FileHashUtil;

import java.io.File;

public record HashDownloadHolder(String url, File file, HashData hashData) implements IDownloadAble {

    public static final Codec<HashDownloadHolder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(HashDownloadHolder::url),
            Codec.STRING.fieldOf("musicUrl").forGetter(HashDownloadHolder::getFileStr),
            HashData.CODEC.fieldOf("hashData").forGetter(HashDownloadHolder::hashData)).apply(instance, HashDownloadHolder::new));

    public HashDownloadHolder(String url, String file, HashData hashData) {
        this(url, new File(file), hashData);
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

    @Override
    public void onDownloadCompleted() {
        if (!checkHash()) {
            file.delete();
        }
    }

    public boolean checkHash() {
        if (file.exists()) {
            try {
                return FileHashUtil.calculateHash(file, hashData.getHashAlgorithm()).equals(hashData.hash());
            } catch (Exception e) {
                FPSMatch.LOGGER.error("Error checking hash", e);
                return false;
            }
        } else {
            return false;
        }
    }
}
