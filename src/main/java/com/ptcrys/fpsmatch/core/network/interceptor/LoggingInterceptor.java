package com.ptcrys.fpsmatch.core.network.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP请求日志拦截器，用于记录Java HttpClient的请求和响应信息
 */
public class LoggingInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger("FPSMatch HTTP Logger");
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final String REQUEST_START_HEADER = "X-Request-Start-Time";

    @Override
    public HttpRequest.Builder requestIntercept(HttpRequest.Builder builder, Object body) {
        // 记录请求开始时间
        builder.setHeader(REQUEST_START_HEADER, String.valueOf(System.nanoTime()));

        // 记录请求信息
        HttpRequest request = builder.build();
        logRequest(request, body);

        return builder;
    }

    @Override
    public <T> HttpResponse<T> responseIntercept(HttpResponse<T> response) {
        // 获取请求开始时间
        long startNs = getRequestStartTime(response);
        long tookMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

        // 记录响应信息
        logResponse(response, tookMs);

        return response;
    }

    private <T> long getRequestStartTime(HttpResponse<T> response) {
        try {
            Optional<String> startTimeHeader = response.request().headers().firstValue(REQUEST_START_HEADER);
            if (startTimeHeader.isPresent()) {
                return Long.parseLong(startTimeHeader.get());
            }
        } catch (Exception e) {
            logger.warn("Could not parse request start time", e);
        }
        return System.nanoTime() - Duration.ofSeconds(1).toNanos();
    }

    private void logRequest(HttpRequest request, Object body) {
        StringBuilder requestLog = new StringBuilder();
        requestLog.append("\n");
        requestLog.append("--> ").append(request.method()).append(" ").append(request.uri()).append("\n");

        // 添加请求头
        HttpHeaders requestHeaders = request.headers();
        requestHeaders.map().forEach((name, values) -> {
            if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                values.forEach(value -> requestLog.append("| ").append(name).append(": ").append(value).append("\n"));
            }
        });

        // 添加请求体
        if (body != null) {
            try {
                byte[] bodyBytes = serializeBody(body);
                requestLog.append("| Request Body: ").append(new String(bodyBytes, UTF8)).append("\n");
            } catch (Exception e) {
                requestLog.append("| Could not log request body: ").append(e.getMessage()).append("\n");
            }
        }

        requestLog.append("--> END ").append(request.method());
        logger.info(requestLog.toString());
    }

    private <T> void logResponse(HttpResponse<T> response, long tookMs) {
        StringBuilder responseLog = new StringBuilder();
        responseLog.append("\n");
        responseLog.append("<-- ").append(response.statusCode()).append(" ")
                .append(response.uri()).append(" (").append(tookMs).append("ms)").append("\n");

        // 添加响应头
        HttpHeaders responseHeaders = response.headers();
        responseHeaders.map().forEach((name, values) -> {
            values.forEach(value -> responseLog.append("| ").append(name).append(": ").append(value).append("\n"));
        });

        // 添加响应体
        try {
            if (response.body() != null) {
                if (response.body() instanceof byte[] bodyBytes) {
                    if (bodyBytes.length > 0) {
                        responseLog.append("| Response Body: ").append(new String(bodyBytes, UTF8)).append("\n");
                    }
                } else {
                    responseLog.append("| Response Body: ").append(response.body().toString()).append("\n");
                }
            }
        } catch (Exception e) {
            responseLog.append("| Could not log response body: ").append(e.getMessage()).append("\n");
        }

        responseLog.append("<-- END HTTP");
        logger.info(responseLog.toString());
    }

    private byte[] serializeBody(Object body) {
        if (body instanceof byte[]) {
            return (byte[]) body;
        }
        return body.toString().getBytes(UTF8);
    }
}
