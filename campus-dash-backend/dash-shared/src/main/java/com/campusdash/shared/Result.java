package com.campusdash.shared;

/** 统一返回结构，presentation 层用它包装响应 */
public record Result<T>(String code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.name(), ErrorCode.OK.message(), data);
    }

    public static <T> Result<T> fail(ErrorCode code) {
        return new Result<>(code.name(), code.message(), null);
    }

    public static <T> Result<T> fail(ErrorCode code, T data) {
        return new Result<>(code.name(), code.message(), data);
    }

    public boolean success() {
        return ErrorCode.OK.name().equals(code);
    }
}
