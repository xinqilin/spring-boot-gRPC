package com.bill.streaming.grpc.bidistreaming;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.stub.StreamObserver;
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
class BidiStreamingServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = BidiStreamingGreeterGrpc.BidiStreamingGreeterStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private BidiStreamingGreeterGrpc.BidiStreamingGreeterStub asyncStub;

  @Test
  void chat_echoesEachMessageBidirectionally() throws InterruptedException {
    CountDownLatch repliesReceived = new CountDownLatch(2);
    List<String> received = new CopyOnWriteArrayList<>();

    StreamObserver<HelloRequest> requestObserver = asyncStub.chat(new StreamObserver<>() {
      @Override
      public void onNext(HelloReply value) {
        received.add(value.getMessage());
        repliesReceived.countDown();
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onCompleted() {
      }
    });

    requestObserver.onNext(HelloRequest.newBuilder().setName("X").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("Y").build());
    requestObserver.onCompleted();

    assertThat(repliesReceived.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(received).containsExactlyInAnyOrder("echo: X", "echo: Y");
  }
}
