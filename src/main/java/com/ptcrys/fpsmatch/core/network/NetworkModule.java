package com.ptcrys.fpsmatch.core.network;

import com.mojang.serialization.Codec;
import com.ptcrys.fpsmatch.core.network.interceptor.Interceptor;
import com.ptcrys.fpsmatch.core.network.interceptor.LoggingInterceptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 支持拦截器的网络请求模块
 */
public class NetworkModule {

    private boolean closed = false;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final List<Interceptor> interceptors = new ArrayList<>();

    private NetworkModule(Builder builder) {
        this.baseUrl = builder.baseUrl;

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(builder.connectTimeout));

        if (builder.executor != null) {
            clientBuilder.executor(builder.executor);
        }

        if (builder.followRedirects) {
            clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
        }

        this.httpClient = clientBuilder.build();

        this.interceptors.addAll(builder.interceptors);
    }

    public <T> RequestBuilder<T> newRequest(Codec<T> codec) {
        return new RequestBuilder<>(this, codec);
    }

    public RequestBuilder<Void> newRequest() {
        return new RequestBuilder<>(this, null);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public NetworkModule addInterceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
        return this;
    }

    HttpRequest.Builder applyRequestInterceptors(HttpRequest.Builder builder, Object body) {
        HttpRequest.Builder result = builder;
        for (Interceptor interceptor : interceptors) {
            result = interceptor.requestIntercept(result, body);
        }
        return result;
    }

    <T> HttpResponse<T> applyResponseInterceptors(HttpResponse<T> response) {
        HttpResponse<T> result = response;
        for (Interceptor interceptor : interceptors) {
            result = interceptor.responseIntercept(result);
        }
        return result;
    }

    public void shutdown() {
        Executor executor = httpClient.executor().orElse(null);
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdown();
        }
        this.closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public static void shutdown(Executor executor) {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdown();
        }
    }

    public static class Builder {

        private String baseUrl = "";
        private long connectTimeout = 10_000;
        private Executor executor;
        private final List<Interceptor> interceptors = new ArrayList<>();
        private boolean followRedirects = true;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder connectTimeout(long timeout, TimeUnit unit) {
            this.connectTimeout = unit.toMillis(timeout);
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public NetworkModule build() {
            return new NetworkModule(this);
        }
    }

    public static NetworkModule initializeNetworkModule(String baseUrl) {
        return new Builder()
                .baseUrl(baseUrl)
                .connectTimeout(15, TimeUnit.SECONDS)
                .executor(Executors.newCachedThreadPool())
                .addInterceptor(new LoggingInterceptor())
                .build();
    }
}
