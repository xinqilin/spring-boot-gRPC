package com.bill.streaming.grpc.bidistreaming;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bidirectional-streaming：雙方各自獨立、同時持續互推訊息，這裡收到一筆就立刻回一筆（echo）。
 */
@Service
public class BidiStreamingServiceImpl extends BidiStreamingGreeterGrpc.BidiStreamingGreeterImplBase {

  private static final Logger log = LoggerFactory.getLogger(BidiStreamingServiceImpl.class);

  @Override
  public StreamObserver<HelloRequest> chat(StreamObserver<HelloReply> responseObserver) {
    return new StreamObserver<>() {

      @Override
      public void onNext(HelloRequest request) {
        log.info("[Chat] received name={}", request.getName());
        responseObserver.onNext(HelloReply.newBuilder()
            .setMessage("echo: " + request.getName())
            .build());
      }

      @Override
      public void onError(Throwable t) {
        log.warn("[Chat] client aborted the stream", t);
      }

      @Override
      public void onCompleted() {
        log.info("[Chat] client finished");
        responseObserver.onCompleted();
      }
    };
  }
}
