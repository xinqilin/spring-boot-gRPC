package com.bill.streaming.grpc.clientstreaming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.common.UploadSummary;
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
class ClientStreamingServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = ClientStreamingGreeterGrpc.ClientStreamingGreeterStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private ClientStreamingGreeterGrpc.ClientStreamingGreeterStub asyncStub;

  @Test
  void upload_aggregatesClientStream() throws InterruptedException {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<UploadSummary> summary = new AtomicReference<>();

    StreamObserver<HelloRequest> requestObserver = asyncStub.upload(new StreamObserver<>() {
      @Override
      public void onNext(UploadSummary value) {
        summary.set(value);
      }

      @Override
      public void onError(Throwable t) {
        completed.countDown();
      }

      @Override
      public void onCompleted() {
        completed.countDown();
      }
    });

    requestObserver.onNext(HelloRequest.newBuilder().setName("A").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("B").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("C").build());
    requestObserver.onCompleted();

    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(summary.get().getCount()).isEqualTo(3);
  }
}
