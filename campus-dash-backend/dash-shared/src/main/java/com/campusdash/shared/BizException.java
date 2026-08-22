package com.campusdash.shared;

/**
 * 业务异常。继承 RuntimeException 是为了配合 Spring 事务的默认回滚规则，
 * 但项目里 @Transactional 仍显式声明 rollbackFor = Exception.class。
 */
public class BizException extends RuntimeException {

    private final ErrorCode code;

    public BizException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public BizException(ErrorCode code, String detail) {
        super(code.message() + ": " + detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
