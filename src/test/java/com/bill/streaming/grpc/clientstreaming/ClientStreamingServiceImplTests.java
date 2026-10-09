package com.bill.streaming.grpc.clientstreaming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.common.UploadSummary;
import io.grpc.Status;
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
class ClientStreamingServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = ClientStreamingGreeterGrpc.ClientStreamingGreeterStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private ClientStreamingGreeterGrpc.ClientStreamingGreeterStub asyncStub;

  private final CountDownLatch completed = new CountDownLatch(1);
  private final AtomicReference<UploadSummary> summary = new AtomicReference<>();
  private final AtomicReference<Throwable> error = new AtomicReference<>();

  @Test
  void upload_aggregatesClientStream() throws InterruptedException {
    StreamObserver<HelloRequest> requestObserver = startUpload();

    requestObserver.onNext(HelloRequest.newBuilder().setName("A").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("B").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("C").build());
    requestObserver.onCompleted();

    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(error.get()).isNull();
    assertThat(summary.get().getCount()).isEqualTo(3);
    assertThat(summary.get().getMessage()).isEqualTo("received 3 messages: A,B,C");
  }

  @Test
  void upload_blankName_returnsInvalidArgument() throws InterruptedException {
    StreamObserver<HelloRequest> requestObserver = startUpload();

    requestObserver.onNext(HelloRequest.newBuilder().setName("A").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName(" ").build());
    requestObserver.onNext(HelloRequest.newBuilder().setName("C").build());
    requestObserver.onCompleted();

    assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(summary.get()).isNull();
    Status status = Status.fromThrowable(error.get());
    assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status.getDescription()).isEqualTo("name must not be blank (message #2)");
  }

  private StreamObserver<HelloRequest> startUpload() {
    return asyncStub.upload(new StreamObserver<>() {
      @Override
      public void onNext(UploadSummary value) {
        summary.set(value);
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
  }
}
