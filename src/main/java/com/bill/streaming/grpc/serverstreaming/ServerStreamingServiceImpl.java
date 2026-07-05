package com.bill.streaming.grpc.serverstreaming;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
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

  @Override
  public void subscribe(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    log.info("[Subscribe] received name={}", request.getName());
    for (int i = 1; i <= 5; i++) {
      responseObserver.onNext(HelloReply.newBuilder()
          .setMessage("Hello, " + request.getName() + " #" + i)
          .build());
    }
    responseObserver.onCompleted();
  }
}
