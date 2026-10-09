package com.bill.streaming.grpc.clientstreaming;

import java.util.ArrayList;
import java.util.List;

import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.common.UploadSummary;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Client-streaming：對方陸續推送多筆請求，這端收完（onCompleted）後才彙總回一筆結果。
 * 這是最貼近「接收端」情境的模式：對方系統持續把資料推進來，這裡負責收集/彙整。
 */
@Service
public class ClientStreamingServiceImpl extends ClientStreamingGreeterGrpc.ClientStreamingGreeterImplBase {

  private static final Logger log = LoggerFactory.getLogger(ClientStreamingServiceImpl.class);

  @Override
  public StreamObserver<HelloRequest> upload(StreamObserver<UploadSummary> responseObserver) {
    return new StreamObserver<>() {

      private final List<String> names = new ArrayList<>();
      private boolean failed = false;

      @Override
      public void onNext(HelloRequest request) {
        if (failed) {
          return;
        }
        if (request.getName().isBlank()) {
          failed = true;
          // 原生 gRPC 寫法：自己呼叫 onError 回傳 Status。
          // onError 之後這個 call 就結束了，不能再呼叫 onNext / onCompleted，所以用 failed 擋掉後續動作。
          responseObserver.onError(Status.INVALID_ARGUMENT
              .withDescription("name must not be blank (message #" + (names.size() + 1) + ")")
              .asRuntimeException());
          return;
        }
        names.add(request.getName());
        log.info("[Upload] received #{} name={}", names.size(), request.getName());
      }

      @Override
      public void onError(Throwable t) {
        log.warn("[Upload] client aborted the stream", t);
      }

      @Override
      public void onCompleted() {
        if (failed) {
          return;
        }
        log.info("[Upload] client finished, total={}", names.size());
        responseObserver.onNext(UploadSummary.newBuilder()
            .setCount(names.size())
            .setMessage("received " + names.size() + " messages: " + String.join(",", names))
            .build());
        responseObserver.onCompleted();
      }
    };
  }
}
