package com.ptcrys.fpsmatch.core.network.download;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 下载进度记录类，提供下载进度跟踪和相关计算功能
 *
 * @param bytesDownloaded 已下载字节数（必须≥0）
 * @param totalBytes      总字节数（未知时为-1）
 * @param progress        下载进度（0.0-1.0，未知时为-1）
 */
public record DownloadProgress(long bytesDownloaded, long totalBytes, double progress) {

    // 线程安全的格式化器
    private static final ThreadLocal<DecimalFormat> PROGRESS_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("0.00%"));
    private static final ThreadLocal<DecimalFormat> SIZE_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));
    private static final ThreadLocal<DecimalFormat> SPEED_FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    // 紧凑型构造器参数验证
    public DownloadProgress {
        if (bytesDownloaded < 0) {
            throw new IllegalArgumentException("下载字节数不能为负数: " + bytesDownloaded);
        }
        if (totalBytes < -1) {
            throw new IllegalArgumentException("总字节数无效: " + totalBytes);
        }
        progress = calculateProgress(bytesDownloaded, totalBytes);
    }

    /**
     * 创建总大小未知的下载进度
     * 
     * @param bytesDownloaded 已下载字节数
     */
    public DownloadProgress(long bytesDownloaded) {
        this(bytesDownloaded, -1);
    }

    /**
     * 创建已知总大小的下载进度
     * 
     * @param bytesDownloaded 已下载字节数
     * @param totalBytes      总字节数
     */
    public DownloadProgress(long bytesDownloaded, long totalBytes) {
        this(bytesDownloaded, totalBytes, calculateProgress(bytesDownloaded, totalBytes));
    }

    private static double calculateProgress(long downloaded, long total) {
        return total > 0 ? Math.min(1.0, Math.max(0.0, (double) downloaded / total)) : -1;
    }

    /**
     * 获取格式化后的已下载大小
     */
    public String getFormattedDownloadedSize() {
        return formatSize(bytesDownloaded);
    }

    /**
     * 获取格式化后的总大小
     */
    public String getFormattedTotalSize() {
        return totalBytes >= 0 ? formatSize(totalBytes) : "未知";
    }

    /**
     * 获取格式化后的进度百分比
     */
    public String getFormattedProgress() {
        return progress >= 0 ? PROGRESS_FORMAT.get().format(progress).replace("%", "%%") : "-";
    }

    /**
     * 计算下载速度
     * 
     * @param previousProgress 前一个进度状态
     * @param elapsedNanos     经过的时间（纳秒）
     * @return 下载速度（字节/秒），无法计算时返回-1
     */
    public double calculateSpeed(@NotNull DownloadProgress previousProgress, long elapsedNanos) {
        Objects.requireNonNull(previousProgress, "前一个进度不能为null");
        if (elapsedNanos <= 0) return -1;

        long bytesDiff = this.bytesDownloaded - previousProgress.bytesDownloaded;
        double elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        return bytesDiff / Math.max(0.001, elapsedSeconds); // 避免除以0
    }

    /**
     * 获取格式化后的下载速度
     */
    public String getFormattedSpeed(double speedBytesPerSec) {
        if (speedBytesPerSec <= 0) return "-";
        return formatSpeed(speedBytesPerSec);
    }

    /**
     * 估算剩余时间
     * 
     * @param speedBytesPerSec 当前速度（字节/秒）
     * @return 剩余时间，无法计算时返回null
     */
    public @Nullable Duration getRemainingTime(double speedBytesPerSec) {
        if (speedBytesPerSec <= 0 || totalBytes < 0 || progress < 0) {
            return null;
        }
        long remainingBytes = totalBytes - bytesDownloaded;
        long seconds = (long) (remainingBytes / speedBytesPerSec);
        return Duration.ofSeconds(seconds);
    }

    /**
     * 获取格式化后的剩余时间
     */
    public String getFormattedRemainingTime(@Nullable Duration remaining) {
        if (remaining == null) return "-";

        long seconds = remaining.getSeconds();
        if (seconds < 60) {
            return seconds + "秒";
        }
        if (seconds < 3600) {
            return String.format("%d分%d秒",
                    seconds / 60,
                    seconds % 60);
        }
        return String.format("%d时%d分%d秒",
                seconds / 3600,
                (seconds % 3600) / 60,
                seconds % 60);
    }

    @Override
    public @NotNull String toString() {
        if (totalBytes < 0) {
            return String.format("Downloading: %s", getFormattedDownloadedSize());
        }
        return String.format("Downloading: %s / %s (%s)",
                getFormattedDownloadedSize(),
                getFormattedTotalSize(),
                getFormattedProgress());
    }

    // 内部格式化方法
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double kb = bytes / 1024.0;
        if (kb < 1024) return SIZE_FORMAT.get().format(kb) + " KB";

        double mb = kb / 1024.0;
        if (mb < 1024) return SIZE_FORMAT.get().format(mb) + " MB";

        double gb = mb / 1024.0;
        return SIZE_FORMAT.get().format(gb) + " GB";
    }

    private static String formatSpeed(double bytesPerSec) {
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) return SPEED_FORMAT.get().format(kb) + " KB/s";

        double mb = kb / 1024.0;
        return SPEED_FORMAT.get().format(mb) + " MB/s";
    }
}
