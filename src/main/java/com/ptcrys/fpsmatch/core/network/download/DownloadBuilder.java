package com.ptcrys.fpsmatch.core.network.download;

import com.ptcrys.fpsmatch.core.network.NetworkModule;
import com.ptcrys.fpsmatch.core.network.RequestBuilder;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 下载构建器内部类
 */
public class DownloadBuilder<T> {

    private final RequestBuilder<T> parent;
    private Path downloadPath;
    private Consumer<DownloadProgress> progressCallback;
    private boolean resumeDownload = false;
    private long existingFileSize = 0;

    @ApiStatus.Internal
    public DownloadBuilder(RequestBuilder<T> parent) {
        this.parent = parent;
    }

    /**
     * 设置下载保存路径
     */
    public DownloadBuilder<T> saveTo(Path downloadPath) {
        this.downloadPath = downloadPath;
        return this;
    }

    /**
     * 设置进度回调
     */
    public DownloadBuilder<T> callback(Consumer<DownloadProgress> progressCallback) {
        this.progressCallback = progressCallback;
        return this;
    }

    /**
     * 启用断点续传
     */
    public DownloadBuilder<T> enableResume() {
        this.resumeDownload = true;
        return this;
    }

    /**
     * 同步执行下载
     */
    public DownloadResult download() throws DownloadException {
        validateParameters();

        try {
            HttpRequest request = parent.buildRequest();

            // 检查文件是否存在以支持断点续传
            if (resumeDownload && Files.exists(downloadPath)) {
                existingFileSize = Files.size(downloadPath);
                request = HttpRequest.newBuilder(request.uri())
                        .header("Range", "bytes=" + existingFileSize + "-")
                        .build();
            }

            // 获取文件信息
            HttpResponse<Void> headResponse = parent.getClient().send(
                    HttpRequest.newBuilder(request.uri()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            if (!isSuccessful(headResponse.statusCode())) {
                throw new DownloadException("Failed to get file info: HTTP " + headResponse.statusCode());
            }

            // 获取文件总大小
            long totalSize = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (resumeDownload && existingFileSize > 0) {
                totalSize = existingFileSize + headResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);
            }

            // 创建临时文件
            Path tempFile = Files.createTempFile(downloadPath.getParent(), "download_", ".tmp");
            try {
                // 执行下载
                HttpResponse<InputStream> response = parent.getClient().send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (!isSuccessful(response.statusCode())) {
                    throw new DownloadException("Download failed: HTTP " + response.statusCode());
                }

                // 下载并保存文件
                try (InputStream inputStream = response.body();
                        OutputStream outputStream = Files.newOutputStream(tempFile,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    long downloadedBytes = existingFileSize;
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while (!parent.getModule().isClosed() && (bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        // 触发进度回调
                        if (progressCallback != null) {
                            progressCallback.accept(new DownloadProgress(
                                    downloadedBytes,
                                    totalSize,
                                    (double) downloadedBytes / (totalSize > 0 ? totalSize : downloadedBytes)));
                        }
                    }
                }

                if (parent.getModule().isClosed()) {
                    Files.deleteIfExists(tempFile);
                    return DownloadResult.empty();
                }

                // 移动临时文件到目标位置
                Files.move(tempFile, downloadPath, StandardCopyOption.REPLACE_EXISTING);

                // 返回下载结果
                return new DownloadResult(
                        downloadPath,
                        totalSize,
                        getFileNameFromResponse(headResponse),
                        headResponse.headers().map());
            } finally {
                // 确保临时文件被删除
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException | InterruptedException e) {
            throw new DownloadException("Download failed", e);
        }
    }

    /**
     * 异步执行下载
     */
    public CompletableFuture<DownloadResult> downloadAsync() {
        return this.downloadAsync(parent.getClient().executor().get());
    }

    public CompletableFuture<DownloadResult> downloadAsyncAndClose() {
        return this.downloadAsync()
                .whenComplete((result, error) -> {
                    parent.getModule().shutdown();
                });
    }

    public CompletableFuture<DownloadResult> downloadAsync(Executor executor) {
        validateParameters();

        return CompletableFuture.supplyAsync(() -> {
            try {
                return download();
            } catch (DownloadException e) {
                throw new CompletionException(e);
            }
        }, executor).whenComplete(
                (result, error) -> {
                    NetworkModule.shutdown(executor);
                });
    }

    public CompletableFuture<DownloadResult> downloadAsyncAndClose(Executor executor) {
        return this.downloadAsync(executor)
                .whenComplete((result, error) -> {
                    NetworkModule.shutdown(executor);
                });
    }

    private void validateParameters() {
        if (downloadPath == null) {
            throw new IllegalStateException("Download path not set");
        }
    }

    private boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private String getFileNameFromResponse(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Disposition")
                .map(header -> {
                    String[] parts = header.split(";");
                    for (String part : parts) {
                        if (part.trim().startsWith("filename=")) {
                            return part.substring(part.indexOf('=') + 1)
                                    .replace("\"", "")
                                    .trim();
                        }
                    }
                    return null;
                })
                .orElseGet(() -> {
                    String path = response.uri().getPath();
                    return path.substring(path.lastIndexOf('/') + 1);
                });
    }
}
