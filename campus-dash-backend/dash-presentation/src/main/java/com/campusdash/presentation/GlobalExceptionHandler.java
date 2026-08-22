package com.campusdash.presentation;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常返回 200 + 业务错误码，不用 HTTP 5xx——抢不到单不是服务器错误 */
    @ExceptionHandler(BizException.class)
    public Result<Void> onBiz(BizException e) {
        return Result.fail(e.code());
    }

    /** 唯一索引冲突：说明并发防线生效了，这是预期行为 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> onDuplicate(DuplicateKeyException e) {
        return Result.fail(ErrorCode.GRAB_CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> onOther(Exception e) {
        log.error("未预期异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
