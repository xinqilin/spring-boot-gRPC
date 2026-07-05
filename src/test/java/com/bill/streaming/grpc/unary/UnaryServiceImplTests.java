package com.bill.streaming.grpc.unary;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.grpc.client.ImportGrpcClients;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
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
}
