package com.bill.streaming.grpc;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.grpc.client.ImportGrpcClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 標準 grpc.health.v1.Health service：grpc-services 在 classpath 上（starter 已帶入）就會自動註冊。
 * service 名稱留空（""）代表查詢整體健康狀態。
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
class HealthServiceTests {

  @TestConfiguration
  @ImportGrpcClients(types = HealthGrpc.HealthBlockingStub.class)
  static class GrpcClientsConfig {
  }

  @Autowired
  private HealthGrpc.HealthBlockingStub healthStub;

  @Test
  void check_overallStatusIsServing() {
    HealthCheckResponse response = healthStub.check(HealthCheckRequest.newBuilder().setService("").build());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }
}
