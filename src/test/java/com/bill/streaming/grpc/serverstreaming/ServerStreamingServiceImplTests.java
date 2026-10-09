package com.bill.streaming.grpc.serverstreaming;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
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
class ServerStreamingServiceImplTests {

  @TestConfiguration
  @ImportGrpcClients(types = {
      ServerStreamingGreeterGrpc.ServerStreamingGreeterBlockingStub.class,
      ServerStreamingGreeterGrpc.ServerStreamingGreeterStub.class})
  static class GrpcClientsConfig {
  }

  @Autowired
  private ServerStreamingGreeterGrpc.ServerStreamingGreeterBlockingStub blockingStub;

  @Autowired
  private ServerStreamingGreeterGrpc.ServerStreamingGreeterStub asyncStub;

  @Test
  void subscribe_streamsMultipleReplies() {
    Iterator<HelloReply> replies = blockingStub.subscribe(HelloRequest.newBuilder().setName("Bill").build());

    List<String> messages = new ArrayList<>();
    replies.forEachRemaining(reply -> messages.add(reply.getMessage()));

    assertThat(messages).hasSize(5).allMatch(message -> message.contains("Bill"));
  }

  @Test
  void subscribe_deadlineExceeded_endsStreamEarly() {
    // 每 200ms 推一筆，全部推完約 1 秒；client 只願意等 300ms
    Iterator<HelloReply> replies = blockingStub.withDeadlineAfter(300, TimeUnit.MILLISECONDS)
        .subscribe(HelloRequest.newBuilder().setName("Bill").build());

    List<String> messages = new ArrayList<>();
    assertThatThrownBy(() -> replies.forEachRemaining(reply -> messages.add(reply.getMessage())))
        .isInstanceOfSatisfying(StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    // deadline 到期前已經收到的訊息仍然有效，只是 stream 提早結束
    assertThat(messages).isNotEmpty().hasSizeLessThan(5);
  }

  @Test
  void subscribeWithFlowControl_clientPullsOneAtATime() throws InterruptedException {
    CountDownLatch completed = new CountDownLatch(1);
    List<String> received = new CopyOnWriteArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    asyncStub.subscribeWithFlowControl(HelloRequest.newBuilder().setName("Bill").build(),
        new ClientResponseObserver<HelloRequest, HelloReply>() {

          private ClientCallStreamObserver<HelloRequest> requestStream;

          @Override
          public void beforeStart(ClientCallStreamObserver<HelloRequest> requestStream) {
            this.requestStream = requestStream;
            // 關掉自動 request，改由 client 自己控制節奏：一開始只要 1 筆
            requestStream.disableAutoRequestWithInitial(1);
          }

          @Override
          public void onNext(HelloReply value) {
            received.add(value.getMessage());
            // 處理完這筆才跟 server 要下一筆；server 端會因此在 isReady() == false 時停下來等
            requestStream.request(1);
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

    assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(error.get()).isNull();
    assertThat(received).containsExactlyElementsOf(
        IntStream.rangeClosed(1, 1000).mapToObj(i -> "Hello, Bill #" + i).toList());
  }
}
