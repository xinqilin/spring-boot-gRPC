package com.bill.streaming.grpc.unary;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Unary：一個請求換一個回應，最基礎的 gRPC 溝通模式。
 */
@Service
public class UnaryServiceImpl extends UnaryGreeterGrpc.UnaryGreeterImplBase {

  private static final Logger log = LoggerFactory.getLogger(UnaryServiceImpl.class);

  @Override
  public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    log.info("[SayHello] received name={}", request.getName());
    responseObserver.onNext(HelloReply.newBuilder()
        .setMessage("Hello, " + request.getName())
        .build());
    responseObserver.onCompleted();
  }
}
