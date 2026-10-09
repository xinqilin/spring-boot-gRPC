package com.bill.streaming.grpc.unary;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.grpc.client.ImportGrpcClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureTestGrpcTransport
class UnaryServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = UnaryGreeterGrpc.UnaryGreeterBlockingStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private UnaryGreeterGrpc.UnaryGreeterBlockingStub blockingStub;

  @Test
  void sayHello_returnsGreeting() {
    HelloReply reply = blockingStub.sayHello(HelloRequest.newBuilder().setName("Bill").build());

    assertThat(reply.getMessage()).isEqualTo("Hello, Bill");
  }

  @Test
  void sayHello_blankName_returnsInvalidArgument() {
    HelloRequest request = HelloRequest.newBuilder().setName(" ").build();

    assertThatThrownBy(() -> blockingStub.sayHello(request))
        .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
          assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
          assertThat(e.getStatus().getDescription()).isEqualTo("name must not be blank");
        });
  }
}
