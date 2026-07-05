package com.bill.streaming.grpc.serverstreaming;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
class ServerStreamingServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = ServerStreamingGreeterGrpc.ServerStreamingGreeterBlockingStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private ServerStreamingGreeterGrpc.ServerStreamingGreeterBlockingStub blockingStub;

  @Test
  void subscribe_streamsMultipleReplies() {
    Iterator<HelloReply> replies = blockingStub.subscribe(HelloRequest.newBuilder().setName("Bill").build());

    List<String> messages = new ArrayList<>();
    replies.forEachRemaining(reply -> messages.add(reply.getMessage()));

    assertThat(messages).hasSize(5).allMatch(message -> message.contains("Bill"));
  }
}
