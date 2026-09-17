package com.ptcrys.fpsmatch.core.network;

import java.util.List;
import java.util.Map;

/**
 * API响应封装类，统一处理响应数据和错误信息
 */
public class ApiResponse<T> {

    private int code;
    private String message;
    private Map<String, List<String>> headers;
    private String rawBody;
    private T data;
    private ApiError error;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    public String getRawBody() {
        return rawBody;
    }

    public void setRawBody(String rawBody) {
        this.rawBody = rawBody;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public ApiError getError() {
        return error;
    }

    public void setError(ApiError error) {
        this.error = error;
    }

    /**
     * 判断请求是否成功
     */
    public boolean isSuccessful() {
        return code >= 200 && code < 300 && error == null;
    }
}
