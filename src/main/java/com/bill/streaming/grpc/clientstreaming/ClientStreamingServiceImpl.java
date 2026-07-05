package com.bill.streaming.grpc.clientstreaming;

import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.common.UploadSummary;
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

      private int count = 0;
      private final StringBuilder names = new StringBuilder();

      @Override
      public void onNext(HelloRequest request) {
        count++;
        names.append(request.getName()).append(',');
        log.info("[Upload] received #{} name={}", count, request.getName());
      }

      @Override
      public void onError(Throwable t) {
        log.warn("[Upload] client aborted the stream", t);
      }

      @Override
      public void onCompleted() {
        log.info("[Upload] client finished, total={}", count);
        responseObserver.onNext(UploadSummary.newBuilder()
            .setCount(count)
            .setMessage("received " + count + " messages: " + names)
            .build());
        responseObserver.onCompleted();
      }
    };
  }
}
