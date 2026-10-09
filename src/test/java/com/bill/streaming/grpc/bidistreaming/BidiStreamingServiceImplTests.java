package com.bill.streaming.grpc.bidistreaming;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

@SpringBootTest
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
    CountDownLatch completed = new CountDownLatch(1);
    List<String> received = new CopyOnWriteArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    StreamObserver<HelloRequest> requestObserver = asyncStub.chat(new StreamObserver<>() {
      @Override
      public void onNext(HelloReply value) {
        received.add(value.getMessage());
      }

      @Override
      public void onError(Throwable t) {
        error.set(t);
        completed.countDown();
      }

      @Override
      public void onCompleted() {
        completed.countDown();
      }
    });

    requestObserver.onNext(HelloRequest.newBuilder().setName("X").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("Y").build());
    requestObserver.onCompleted();

    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(error.get()).isNull();
    // 同一條 stream 內的訊息保證依序送達，所以可以斷言順序
    assertThat(received).containsExactly("echo: X", "echo: Y");
  }
}
