package com.ptcrys.fpsmatch.core.shop;

import java.util.Objects;

public record ShopActionResult(Code code) {

    public ShopActionResult {
        Objects.requireNonNull(code, "code");
    }

    public static ShopActionResult success() {
        return new ShopActionResult(Code.SUCCESS);
    }

    public static ShopActionResult failure(Code code) {
        if (code == Code.SUCCESS) {
            throw new IllegalArgumentException("Failure result cannot use SUCCESS");
        }
        return new ShopActionResult(code);
    }

    public boolean accepted() {
        return code == Code.SUCCESS;
    }

    public enum Code {
        SUCCESS,
        INVALID_REQUEST,
        SHOP_UNAVAILABLE,
        NOT_ALLOWED,
        INSUFFICIENT_FUNDS,
        LOCKED_OR_MAX_COUNT,
        CANNOT_RETURN
    }
}
