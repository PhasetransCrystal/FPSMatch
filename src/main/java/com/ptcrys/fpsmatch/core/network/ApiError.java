package com.ptcrys.fpsmatch.core.network;

/**
 * API错误信息封装类
 */
public class ApiError {

    private String message;
    private Throwable cause;

    public ApiError(String message) {
        this.message = message;
    }

    public ApiError(String message, Throwable cause) {
        this.message = message;
        this.cause = cause;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Throwable getCause() {
        return cause;
    }

    public void setCause(Throwable cause) {
        this.cause = cause;
    }
}
