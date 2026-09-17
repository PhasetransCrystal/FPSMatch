package com.ptcrys.fpsmatch.core.data.music;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.core.data.HashData;
import com.ptcrys.fpsmatch.core.network.download.DownloadHolder;
import com.ptcrys.fpsmatch.core.network.download.Downloader;
import com.ptcrys.fpsmatch.core.network.download.HashDownloadHolder;
import com.ptcrys.fpsmatch.core.persistence.PersistenceUtils;
import com.ptcrys.fpsmatch.util.hash.FileHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public record OnlineMusic(String uuid, String musicUrl, String musicName, String coverUrl, String customMemo, HashData hashData) {

    public static final Codec<OnlineMusic> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(OnlineMusic::uuid),
            Codec.STRING.fieldOf("musicUrl").forGetter(OnlineMusic::musicUrl),
            Codec.STRING.fieldOf("musicName").forGetter(OnlineMusic::musicName),
            Codec.STRING.fieldOf("coverUrl").forGetter(OnlineMusic::coverUrl),
            Codec.STRING.fieldOf("customMemo").forGetter(OnlineMusic::customMemo),
            HashData.CODEC.fieldOf("hashData").forGetter(OnlineMusic::hashData)).apply(instance, OnlineMusic::new));

    private static final Logger log = LoggerFactory.getLogger(OnlineMusic.class);

    public File getMusicFile() {
        return PersistenceUtils.getLocalCachePath(musicName, "music").toFile();
    }

    public File getCoverFile() {
        return PersistenceUtils.getLocalCachePath(musicName, "cover").toFile();
    }

    public boolean musicExists() {
        return getMusicFile().exists();
    }

    public boolean coverExists() {
        return getCoverFile().exists();
    }

    public HashDownloadHolder musicHolder() {
        return new HashDownloadHolder(musicUrl, getMusicFile(), hashData);
    }

    public DownloadHolder coverHolder() {
        return new DownloadHolder(coverUrl, getCoverFile());
    }

    public void downloadMusic() {
        Downloader.Instance().download(musicHolder());
    }

    public void downloadCover() {
        Downloader.Instance().download(coverHolder());
    }

    public boolean checkHash() {
        if (musicExists()) {
            try {
                return FileHashUtil.calculateHash(getMusicFile(), hashData.getHashAlgorithm()).equals(hashData.hash());
            } catch (Exception e) {
                log.error("error: ", e);
                return false;
            }
        } else {
            return false;
        }
    }

    public InputStream stream() {
        if (!this.checkHash()) return null;

        try (FileInputStream fis = new FileInputStream(this.getMusicFile());
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) != -1) {
                byteArrayOutputStream.write(buf, 0, len);
            }
            return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
        } catch (Exception e) {
            log.error("error: ", e);
            return null;
        }
    }
}
