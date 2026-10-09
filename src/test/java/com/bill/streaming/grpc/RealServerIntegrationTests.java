package com.bill.streaming.grpc;

import com.bill.streaming.grpc.common.HelloReply;
import com.bill.streaming.grpc.common.HelloRequest;
import com.bill.streaming.grpc.unary.UnaryGreeterGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 其他測試都用 @AutoConfigureTestGrpcTransport（in-process，不經過網路，快又不佔 port）。
 * 這裡刻意不用它：真的啟動 Netty server（port=0 代表隨機挑一個空的 port），
 * client 自己建 ManagedChannel，走真正的 TCP + HTTP/2，跟正式環境的呼叫路徑一樣。
 */
@SpringBootTest(properties = "spring.grpc.server.port=0")
class RealServerIntegrationTests {

  @LocalGrpcServerPort
  private int port;

  private ManagedChannel channel;

  @BeforeEach
  void openChannel() {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
  }

  @AfterEach
  void closeChannel() {
    channel.shutdownNow();
  }

  @Test
  void sayHello_overRealNettyServer() {
    UnaryGreeterGrpc.UnaryGreeterBlockingStub stub = UnaryGreeterGrpc.newBlockingStub(channel);

    HelloReply reply = stub.sayHello(HelloRequest.newBuilder().setName("Bill").build());

    assertThat(reply.getMessage()).isEqualTo("Hello, Bill");
  }
}
