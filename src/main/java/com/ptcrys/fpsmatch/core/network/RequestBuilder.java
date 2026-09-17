package com.ptcrys.fpsmatch.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ptcrys.fpsmatch.core.network.download.DownloadBuilder;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 基于Java HttpClient的请求构建器
 */
public class RequestBuilder<T> {

    private final NetworkModule module;
    private final Codec<T> codec;
    private String baseUrl;
    private String path = "";
    private RequestMethod method = RequestMethod.GET;
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private HttpRequest.BodyPublisher bodyPublisher;
    private String contentType = "application/json";
    private String rawBody;

    public RequestBuilder(NetworkModule client, Codec<T> codec) {
        this.module = client;
        this.codec = codec;
        this.baseUrl = client.getBaseUrl();
    }

    /**
     * 开始构建下载请求
     */
    public DownloadBuilder<T> downloadRequest() {
        return new DownloadBuilder<>(this);
    }

    public HttpClient getClient() {
        return module.getHttpClient();
    }

    public NetworkModule getModule() {
        return module;
    }

    public RequestBuilder<T> setRequestMethod(RequestMethod method) {
        this.method = method;
        return this;
    }

    public RequestBuilder<T> setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public RequestBuilder<T> addPath(String path) {
        if (path == null || path.isEmpty()) {
            return this;
        }

        if (!this.path.endsWith("/") && !path.startsWith("/")) {
            this.path += "/";
        } else if (this.path.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        }

        this.path += path;
        return this;
    }

    public RequestBuilder<T> addHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public RequestBuilder<T> addQueryParam(String key, String value) {
        queryParams.put(key, value);
        return this;
    }

    public RequestBuilder<T> setJsonBody(T body) {
        try {
            JsonElement jsonElement = codec.encodeStart(JsonOps.INSTANCE, body)
                    .getOrThrow(false, e -> {
                        throw new RuntimeException("编码失败: " + e);
                    });
            String jsonString = jsonElement.toString();
            this.rawBody = jsonString;
            this.bodyPublisher = HttpRequest.BodyPublishers.ofString(jsonString);
            this.contentType = "application/json";
        } catch (Exception e) {
            throw new RuntimeException("设置JSON请求体失败: " + e.getMessage(), e);
        }
        return this;
    }

    public RequestBuilder<T> setFormBody(Map<String, String> formData) {
        StringBuilder formBody = new StringBuilder();
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            if (!formBody.isEmpty()) {
                formBody.append("&");
            }
            formBody.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        this.rawBody = formBody.toString();
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(formBody.toString());
        this.contentType = "application/x-www-form-urlencoded";
        return this;
    }

    public ApiResponse<T> execute() {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        HttpRequest request = buildRequest();
        try {
            HttpResponse<String> response = getClient().send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (IOException | InterruptedException e) {
            apiResponse.setError(new ApiError(e.getMessage(), e));
            apiResponse.setRawBody(rawBody);
            return apiResponse;
        }
    }

    public CompletableFuture<ApiResponse<T>> executeAsync() {
        HttpRequest request = buildRequest();
        CompletableFuture<ApiResponse<T>> result = new CompletableFuture<>();

        getClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse)
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        result.complete(response);
                    }
                });
        return result;
    }

    @ApiStatus.Internal
    public HttpRequest buildRequest() {
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl is empty");
        }

        String fullUrl = buildFullUrl();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl));

        // 添加请求头
        headers.forEach(requestBuilder::header);
        if (!headers.containsKey("Content-Type") && bodyPublisher != null) {
            requestBuilder.header("Content-Type", contentType);
        }

        // 设置请求方法和请求体
        switch (method) {
            case GET:
                requestBuilder.GET();
                break;
            case POST:
                requestBuilder.POST(bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody());
                break;
            case PUT:
                requestBuilder.PUT(bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody());
                break;
            case DELETE:
                requestBuilder.method("DELETE", bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody());
                break;
            case HEAD:
                requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                break;
            case OPTIONS:
                requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                break;
            case PATCH:
                requestBuilder.method("PATCH", bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody());
                break;
        }

        // 应用请求拦截器
        return module.applyRequestInterceptors(requestBuilder, rawBody).build();
    }

    private ApiResponse<T> parseResponse(HttpResponse<String> response) {
        // 先应用响应拦截器
        HttpResponse<String> interceptedResponse = module.applyResponseInterceptors(response);

        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.setCode(interceptedResponse.statusCode());
        apiResponse.setMessage("");
        apiResponse.setHeaders(interceptedResponse.headers().map());
        apiResponse.setRawBody(interceptedResponse.body());

        // 解析响应体
        if (codec != null && interceptedResponse.statusCode() >= 200 && interceptedResponse.statusCode() < 300) {
            try {
                JsonElement jsonElement = JsonParser.parseString(interceptedResponse.body());
                T data = codec.decode(JsonOps.INSTANCE, jsonElement)
                        .getOrThrow(false, e -> {
                            throw new RuntimeException(e);
                        }).getFirst();
                apiResponse.setData(data);
            } catch (Exception e) {
                apiResponse.setError(new ApiError("CODEC解码失败: " + e.getMessage(), e));
            }
        } else if (interceptedResponse.statusCode() >= 400) {
            apiResponse.setError(new ApiError("请求失败: " + interceptedResponse.statusCode()));
        }
        return apiResponse;
    }

    private String buildFullUrl() {
        StringBuilder fullUrl = new StringBuilder(baseUrl);

        // 处理路径
        if (!path.isEmpty()) {
            if (!fullUrl.toString().endsWith("/") && !path.startsWith("/")) {
                fullUrl.append("/");
            } else if (fullUrl.toString().endsWith("/") && path.startsWith("/")) {
                path = path.substring(1);
            }
            fullUrl.append(path);
        }

        // 添加查询参数
        if (!queryParams.isEmpty()) {
            fullUrl.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) {
                    fullUrl.append("&");
                }
                fullUrl.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }

        return fullUrl.toString();
    }
}
