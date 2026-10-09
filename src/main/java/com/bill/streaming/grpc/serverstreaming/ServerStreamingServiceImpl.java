package com.bill.streaming.grpc.serverstreaming;

import java.util.concurrent.TimeUnit;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Server-streaming：一個請求，Server 陸續推送多筆回應，最後才 onCompleted()。
 */
@Service
public class ServerStreamingServiceImpl extends ServerStreamingGreeterGrpc.ServerStreamingGreeterImplBase {

  private static final Logger log = LoggerFactory.getLogger(ServerStreamingServiceImpl.class);

  private static final int REPLY_COUNT = 5;
  private static final long INTERVAL_MS = 200;
  private static final int FLOW_CONTROL_REPLY_COUNT = 1000;

  @Override
  public void subscribe(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    log.info("[Subscribe] received name={}", request.getName());
    // 轉型成 ServerCallStreamObserver 才拿得到 cancellation 相關 API
    ServerCallStreamObserver<HelloReply> serverObserver = (ServerCallStreamObserver<HelloReply>) responseObserver;
    // 必須在第一次 onNext 之前註冊；client 主動取消或 deadline 到期都會觸發
    serverObserver.setOnCancelHandler(() -> log.info("[Subscribe] cancelled (client cancel or deadline exceeded)"));

    // 示範用：Thread.sleep 會一直佔住處理這個 call 的 thread，正式環境應改用排程或非同步推送
    try {
      for (int i = 1; i <= REPLY_COUNT; i++) {
        if (serverObserver.isCancelled()) {
          // client 已經不在了，繼續推只是浪費資源；call 已結束，也不能再 onCompleted
          log.info("[Subscribe] stop pushing at #{}", i);
          return;
        }
        serverObserver.onNext(HelloReply.newBuilder()
            .setMessage("Hello, " + request.getName() + " #" + i)
            .build());
        TimeUnit.MILLISECONDS.sleep(INTERVAL_MS);
      }
      serverObserver.onCompleted();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      serverObserver.onError(Status.CANCELLED.withDescription("server interrupted").asRuntimeException());
    }
  }

  /**
   * 有背壓（flow control）的推送：只在 transport 還有空間（isReady）時才送，滿了就停下來，
   * 等 client 讀走、空間又出來時 gRPC 會再呼叫 onReadyHandler，接著從上次的位置繼續送。
   * 對照 subscribe：不管 client 讀不讀得完都一直 onNext，送不出去的訊息會堆在 server 記憶體裡。
   */
  @Override
  public void subscribeWithFlowControl(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    log.info("[SubscribeWithFlowControl] received name={}", request.getName());
    ServerCallStreamObserver<HelloReply> serverObserver = (ServerCallStreamObserver<HelloReply>) responseObserver;

    // onReadyHandler 由 gRPC 依序呼叫、不會同時執行，所以內部狀態不需要加鎖
    serverObserver.setOnReadyHandler(new Runnable() {

      private int next = 1;
      private boolean completed = false;

      @Override
      public void run() {
        while (serverObserver.isReady() && next <= FLOW_CONTROL_REPLY_COUNT) {
          serverObserver.onNext(HelloReply.newBuilder()
              .setMessage("Hello, " + request.getName() + " #" + next++)
              .build());
        }
        // onReadyHandler 在送完之後仍可能再被呼叫，用 completed 避免重複 onCompleted
        if (next > FLOW_CONTROL_REPLY_COUNT && !completed) {
          completed = true;
          serverObserver.onCompleted();
        }
      }
    });
  }
}
