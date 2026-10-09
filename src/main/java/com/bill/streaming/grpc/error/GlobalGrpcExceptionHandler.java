package com.bill.streaming.grpc.error;

import io.grpc.Status;
import io.grpc.StatusException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * 把 service 丟出的例外統一轉成 gRPC Status，概念上等同 Spring MVC 的 @ControllerAdvice。
 * 回傳 null 代表「這個例外我不處理」，最後會落到 Spring gRPC 的 fallback：Status.fromThrowable，
 * 一般例外會變成 UNKNOWN，client 只看得到一個沒有意義的錯誤碼。
 */
@Component
public class GlobalGrpcExceptionHandler implements GrpcExceptionHandler {

  @Override
  public StatusException handleException(Throwable exception) {
    if (exception instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asException();
    }
    return null;
  }
}
