package com.bill.streaming.grpc.interceptor;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * 每個 RPC 進來都會先經過這裡，角色類似 Servlet Filter：
 * 1. 從 request metadata（等同 HTTP/2 header）讀出 x-request-id
 * 2. 把同一個 x-request-id 寫回 response header，讓 client 可以對帳
 * 3. call 結束（close）時記錄 method、status code 與耗時
 *
 * @GlobalServerInterceptor 讓它套用到所有 service；只想套用到單一 service 時，改用 @GrpcService(interceptors = ...)。
 *
 * 順序很重要：global interceptor 依 @Order 排序，排越前面越外層。Spring gRPC 的 GrpcExceptionHandlerInterceptor
 * 標了 @Order(HIGHEST_PRECEDENCE)，預設就在最外層；比它內層的 interceptor，遇到 service 丟例外時，
 * call 會被它直接 close 掉，不會經過下面的 close()，log 就漏記那些失敗的 call。
 * 所以這裡也用 HIGHEST_PRECEDENCE 跟它同序：同序時應用程式自己的 bean 先註冊、排在 auto-configuration 的 bean 前面，
 * logging 因此落在它外層。LoggingServerInterceptorTests 會檢查這個順序。
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoggingServerInterceptor implements ServerInterceptor {

  static final Metadata.Key<String> REQUEST_ID = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

  private static final Logger log = LoggerFactory.getLogger(LoggingServerInterceptor.class);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String requestId = headers.get(REQUEST_ID);
    String method = call.getMethodDescriptor().getFullMethodName();
    long startNanos = System.nanoTime();

    ServerCall<ReqT, RespT> loggingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {

      // 只有在送出第一筆回應前才會呼叫；直接回錯誤（沒有任何回應訊息）時是 trailers-only，不會經過這裡
      @Override
      public void sendHeaders(Metadata responseHeaders) {
        if (requestId != null) {
          responseHeaders.put(REQUEST_ID, requestId);
        }
        super.sendHeaders(responseHeaders);
      }

      @Override
      public void close(Status status, Metadata trailers) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("[gRPC] {} requestId={} status={} elapsed={}ms", method, requestId, status.getCode(), elapsedMs);
        super.close(status, trailers);
      }
    };
    return next.startCall(loggingCall, headers);
  }
}
