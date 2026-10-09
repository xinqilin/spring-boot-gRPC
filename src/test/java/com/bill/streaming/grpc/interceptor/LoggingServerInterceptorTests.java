package com.bill.streaming.grpc.interceptor;

import java.util.concurrent.atomic.AtomicReference;

import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.unary.UnaryGreeterGrpc;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.grpc.client.ImportGrpcClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ExtendWith(OutputCaptureExtension.class)
class LoggingServerInterceptorTests {

  @TestConfiguration
  @ImportGrpcClients(types = UnaryGreeterGrpc.UnaryGreeterBlockingStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private UnaryGreeterGrpc.UnaryGreeterBlockingStub blockingStub;

  @Test
  void interceptCall_echoesRequestIdInResponseHeaders() {
    Metadata requestHeaders = new Metadata();
    requestHeaders.put(LoggingServerInterceptor.REQUEST_ID, "req-123");
    AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
    AtomicReference<Metadata> trailers = new AtomicReference<>();

    // client 端也有 interceptor：一個負責帶 header 出去，一個負責把回來的 header / trailer 抓下來
    blockingStub
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(requestHeaders),
            MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, trailers))
        .sayHello(HelloRequest.newBuilder().setName("Bill").build());

    assertThat(responseHeaders.get().get(LoggingServerInterceptor.REQUEST_ID)).isEqualTo("req-123");
  }

  @Test
  void interceptCall_logsCallsFailedByExceptionHandler(CapturedOutput output) {
    HelloRequest blankName = HelloRequest.newBuilder().setName(" ").build();

    assertThatThrownBy(() -> blockingStub.sayHello(blankName)).isInstanceOf(StatusRuntimeException.class);

    // 若 logging 不在 GrpcExceptionHandlerInterceptor（@Order(HIGHEST_PRECEDENCE)）外層，這筆失敗的 call 不會被記錄
    assertThat(output).contains("[gRPC] streaming.unary.UnaryGreeter/SayHello requestId=null status=INVALID_ARGUMENT");
  }
}
