# spring-boot-gRPC

[![Build](https://github.com/xinqilin/spring-boot-gRPC/actions/workflows/build.yml/badge.svg)](https://github.com/xinqilin/spring-boot-gRPC/actions/workflows/build.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) | 繁體中文

用 Spring Boot 4.1 內建的 Spring gRPC 寫的 **gRPC Server（接收端）** 練習專案：別人（gRPC Client）呼叫這個服務，這個服務負責接收請求並回應。除了四種 RPC 模式，也示範真實服務會用到的錯誤處理、interceptor 與 metadata、deadline 與 cancellation、flow control。這份文件提到的每個行為都有對應的測試。

第一次接觸 gRPC？先看圖解入門：[`docs/grpc-introduction.html`](docs/grpc-introduction.html)。

## 專案內容

| 主題 | 程式碼 | 對應測試 |
|---|---|---|
| 四種 RPC 模式 | `grpc/unary`、`grpc/serverstreaming`、`grpc/clientstreaming`、`grpc/bidistreaming` | `*ServiceImplTests` |
| 錯誤處理：`GrpcExceptionHandler` vs `onError(Status)` | `grpc/error/GlobalGrpcExceptionHandler`、`ClientStreamingServiceImpl` | `UnaryServiceImplTests`、`ClientStreamingServiceImplTests` |
| Interceptor、metadata 與 interceptor 順序 | `grpc/interceptor/LoggingServerInterceptor` | `LoggingServerInterceptorTests` |
| Deadline 與 cancellation | `ServerStreamingServiceImpl#subscribe` | `ServerStreamingServiceImplTests` |
| Flow control（背壓） | `ServerStreamingServiceImpl#subscribeWithFlowControl` | `ServerStreamingServiceImplTests` |
| Health 與 reflection | 自動設定 | `HealthServiceTests` |
| 測試：in-process vs 真實 Netty server | | `RealServerIntegrationTests` |

**技術棧：** Java 21 · Spring Boot 4.1.1（Spring gRPC 1.1.1）· grpc-java 1.83.1 · protobuf 4.35.1 · Gradle 9.8.1

## 快速開始

```bash
./gradlew build      # 從 .proto 產生程式碼、編譯、跑全部測試
./gradlew bootRun    # 啟動 gRPC server，localhost:9090
./gradlew test --tests 'com.bill.streaming.grpc.unary.UnaryServiceImplTests'   # 只跑單一測試類別
```

專案開了 reflection，不需要 `.proto` 檔，直接用 [grpcurl](https://github.com/fullstorydev/grpcurl) 就能打（macOS：`brew install grpcurl`）：

```bash
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.unary.UnaryGreeter/SayHello
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.serverstreaming.ServerStreamingGreeter/Subscribe
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

grpcurl 對 client-streaming / bidi-streaming 的支援方式是從 stdin 讀多筆 JSON（`-d @`，一行一筆），操作起來不如直接看對應的測試直觀。

## gRPC 是什麼、為什麼要學

- gRPC 是 Google 開發的 RPC 框架，底層走 HTTP/2，訊息格式用 **Protocol Buffers（protobuf）** 序列化，比 REST + JSON 更省頻寬，而且很多呼叫可以共用同一條連線。
- 你在 `.proto` 檔定義「服務有哪些方法、輸入輸出長什麼樣子」，`protoc` + gRPC plugin 會產生各種語言的 client stub 和 server 基底類別。你只需要實作介面，不用手刻序列化，也不用手刻 HTTP handler。

## 四種 RPC 模式

每一種模式都拆成**獨立的 proto service + 獨立的 package**，一個資料夾就是一個完整、可以獨立閱讀的範例。

| 模式 | Package | proto service / 方法 | 說明 |
|---|---|---|---|
| **Unary**（一問一答） | `grpc.unary` | `UnaryGreeter.SayHello` | 一個 request 換一個 response，行為很像一般的 REST API。 |
| **Server-streaming**（伺服器推播） | `grpc.serverstreaming` | `ServerStreamingGreeter.Subscribe` | Client 送一個 request，Server 陸續推送多筆 response，最後才 `onCompleted()`。適合「訂閱通知」「即時報價」這類場景。 |
| **Client-streaming**（客戶端推播） | `grpc.clientstreaming` | `ClientStreamingGreeter.Upload` | Client 陸續送多筆 request，Server 等 Client 呼叫 `onCompleted()` 後才彙總、回一筆 response。**這最貼近「接收端」的典型場景**：對方系統持續把資料推進來，這裡負責收集、彙整。 |
| **Bidirectional-streaming**（雙向） | `grpc.bidistreaming` | `BidiStreamingGreeter.Chat` | 雙方各自獨立、同時持續互推訊息，這裡的實作是收到一筆就回一筆（echo）。 |

四種模式的語法差異只在 `stream` 關鍵字要不要加、加在請求還是回應：

```proto
service UnaryGreeter {
  rpc SayHello(HelloRequest) returns (HelloReply) {}                 // Unary
}
service ServerStreamingGreeter {
  rpc Subscribe(HelloRequest) returns (stream HelloReply) {}         // stream 在「回應」那邊
}
service ClientStreamingGreeter {
  rpc Upload(stream HelloRequest) returns (UploadSummary) {}         // stream 在「請求」那邊
}
service BidiStreamingGreeter {
  rpc Chat(stream HelloRequest) returns (stream HelloReply) {}       // 兩邊都 stream
}
```

## Hello world 之後

### 錯誤處理

gRPC 用[狀態碼](https://grpc.io/docs/guides/status-codes/)加上描述回報錯誤。這個 repo 示範兩種產生錯誤的方式：

- **Spring 寫法（Unary）**：`UnaryServiceImpl` 收到空白 name 時丟 `IllegalArgumentException`。`GlobalGrpcExceptionHandler` 實作 `GrpcExceptionHandler`，把它轉成 `INVALID_ARGUMENT`，角色跟 Spring MVC 的 `@ControllerAdvice` 一樣。handler 遇到自己不處理的例外就回傳 `null`。沒人處理的例外最後會走 `Status.fromThrowable`，一般例外會變成 `UNKNOWN`，client 只知道出錯、不知道原因。
- **原生 gRPC 寫法（Client-streaming）**：`ClientStreamingServiceImpl` 直接呼叫 `responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(...).asRuntimeException())`。`onError` 之後這個 call 就結束了，不能再呼叫 `onNext` 或 `onCompleted`，所以 observer 用 `failed` flag 擋掉後續動作。

### Interceptor 與 metadata

`LoggingServerInterceptor` 在每個呼叫之前執行，角色類似 Servlet Filter：

1. 讀 request header `x-request-id`（gRPC 的 metadata）
2. 把同一個值寫回 response header
3. call 結束時記錄 method、status code 與耗時

`@GlobalServerInterceptor` 讓它套用到所有 service；只想套用到單一 service 時，改用 `@GrpcService(interceptors = ...)`。

**順序很重要。** global interceptor 依 `@Order` 排序，越前面越外層。Spring gRPC 的 `GrpcExceptionHandlerInterceptor` 標了 `@Order(Ordered.HIGHEST_PRECEDENCE)`，預設就在最外層。service 丟出例外時，exception handler 會自己 close 掉這個 call，排在它裡面的 interceptor 看不到這次 `close()`，失敗就漏記了。所以 `LoggingServerInterceptor` 也用 `HIGHEST_PRECEDENCE`：同序時，應用程式自己的 bean 比 auto-configuration 的 bean 先註冊，logging 因此落在 exception handler 外層。`LoggingServerInterceptorTests` 會在這個順序改變時失敗（例如拿掉 annotation）。

沒有任何回應訊息的錯誤是 trailers-only 回應，server 不會呼叫 `sendHeaders`，所以錯誤時不會回傳 request id。

### Deadline 與 cancellation

`Subscribe` 每 200ms 推一筆。client 設 300ms deadline 時，會收到 `#1`、`#2`，接著拿到 `DEADLINE_EXCEEDED`。server 端的 `ServerCallStreamObserver#isCancelled()` 變成 true，迴圈在送 `#3` 之前停下來。`setOnCancelHandler` 必須在第一次 `onNext` 之前註冊。

`Thread.sleep` 只是示範用：它會一直佔住處理這個 call 的 thread，正式環境應該改用排程推送。

### Flow control

在迴圈裡一直呼叫 `onNext`，不會管 client 讀得多快；transport 還送不出去的訊息會堆在 server 記憶體裡。`SubscribeWithFlowControl` 只在 `isReady()` 為 true 時送，transport 有空間時再從 `setOnReadyHandler` 接著送。

測試從 client 端控制節奏：用 `ClientResponseObserver` 呼叫 `disableAutoRequestWithInitial(1)`，每收到一筆再 `request(1)`。在這個測試裡，server 的 onReadyHandler 被呼叫了 1001 次，每次送一筆，1000 筆照順序送完。

### 測試：in-process vs 真實 server

大部分測試用 `@SpringBootTest` + `@AutoConfigureTestGrpcTransport`，改走 in-process transport：快，也不佔 port。產生出來的 stub 用 `@ImportGrpcClients` 注入。`RealServerIntegrationTests` 刻意不用那個 annotation：它真的在隨機 port 啟動 Netty server（`spring.grpc.server.port=0` 加 `@LocalGrpcServerPort`），再自己建 `ManagedChannel`，走真正的 TCP 與 HTTP/2。

### Health 與 reflection

兩個 service 都來自 `io.grpc:grpc-services`，`spring-boot-starter-grpc-server` 已經帶進來。reflection 讓 grpcurl 這類工具不需要 `.proto` 就能查到服務；health 提供標準的 `grpc.health.v1.Health`，service 名稱留空代表查詢整體狀態。

## Protobuf（`.proto`）怎麼寫、怎麼設計

### 基本骨架

以 `src/main/proto/common.proto` 為例：

```proto
syntax = "proto3";                                        // 目前幾乎都用 proto3，語法跟舊的 proto2 不同
package streaming.common;                                 // protobuf 自己的 namespace，避免不同 .proto 檔的型別互撞
option java_package = "com.bill.streaming.grpc.common";    // 產生的 Java 程式碼要放在哪個 package
option java_multiple_files = true;                         // 每個 message 各自產生一個獨立 .java 檔
option java_outer_classname = "CommonProto";               // java_multiple_files=true 時，這個名字只用在少數 static 方法上
```

`java_multiple_files` 建議一律開 `true`：關掉的話，所有 message 都會被包在 outer class 裡面，你得寫 `CommonProto.HelloRequest` 才能用；開了之後每個 message 都是獨立的 top-level class（`HelloRequest`、`HelloReply`…），比較符合一般 Java 的直覺。

### message：定義資料結構，大致等同一個 DTO

```proto
message HelloRequest {
  string name = 1;
}
```

每個欄位的寫法固定是 `<型別> <欄位名稱> = <field number>;`。

### 欄位後面那個數字是什麼？可以不寫嗎？

**不行，field number 是必填的**，而且它**不是欄位的順序編號**，而是這個欄位在**二進位 wire format** 裡的唯一身分證號碼。protobuf 序列化時完全不靠欄位名稱，只靠這個數字對應欄位。幾個實際規則：

- 同一個 `message` 裡，數字不能重複，但**不需要從 1 開始連續編號**（為了好讀，通常還是照順序給）。
- **1～15** 用 1 byte 就能編碼 tag，**16～2047** 要用 2 bytes，所以把常用欄位留給 1～15。
- 這個數字一旦上線用過（資料已經序列化存起來、或 API 已經對外發布），**就不能再更改或重複使用**，否則舊資料或舊版 client 讀出來的欄位會對應到錯的意思。
- 想拿掉某個欄位時，刪掉那行，並用 `reserved` 明確保留數字和名稱，避免以後不小心又用到：

  ```proto
  message HelloRequest {
    reserved 2, 3;
    reserved "old_field_name";
    string name = 1;
  }
  ```

- 新增欄位就用「目前最大數字 + 1」。舊版 client 讀到不認識的欄位號碼會直接忽略，這是 protobuf 能做到向前、向後相容的關鍵：新增欄位不用逼所有 client 同時升級。

可以把 field number 想成「資料庫欄位的 internal ID」：**欄位改名字完全沒問題**（只影響產生出來的 Java method 名稱），但這個 ID 一旦用了就不能亂動。

### 常用的欄位型別

| proto 型別 | 說明 | 對應 Java 型別 |
|---|---|---|
| `string` | UTF-8 文字 | `String` |
| `int32` / `int64` | 一般整數 | `int` / `long` |
| `sint32` / `sint64` | 對負數做過 zig-zag 編碼，欄位常是負數時比 `int32`/`int64` 省空間 | `int` / `long` |
| `bool` | 布林 | `boolean` |
| `double` / `float` | 浮點數 | `double` / `float` |
| `bytes` | 任意二進位資料 | `ByteString` |
| `repeated T` | 陣列 / List，例如 `repeated string tags = 4;` | `List<T>` |

### 用 `import` 共用 message

四個 service 共用的 `HelloRequest` / `HelloReply` / `UploadSummary` 抽到 `common.proto`，其他 `.proto` 用 `import "common.proto";` 引用，再用完整路徑 `streaming.common.HelloRequest` 取用（可以對照 `src/main/proto/unary.proto`）。好處是不用各自重複定義；壞處是多一個檔案、多一層 import。專案很小、訊息很少共用時，每個 `.proto` 各自定義也可以。

### Naming convention

- `message` / `service` 名稱：`PascalCase`，例如 `HelloRequest`、`UnaryGreeter`。
- 欄位名稱：`lower_snake_case`，例如 `user_id`。protoc 產生 Java 程式碼時會自動轉成 `getUserId()` / `setUserId()`。
- `rpc` 方法名稱：`PascalCase`，例如 `SayHello`。這是為了跟 Go、Python 等語言共用同一份 schema 時風格一致。

## 專案結構

```
src/main/proto/
  common.proto              四個 service 共用的訊息（HelloRequest / HelloReply / UploadSummary）
  unary.proto               UnaryGreeter
  server_streaming.proto    ServerStreamingGreeter（Subscribe、SubscribeWithFlowControl）
  client_streaming.proto    ClientStreamingGreeter
  bidi_streaming.proto      BidiStreamingGreeter

src/main/java/com/bill/streaming/grpc/
  unary/ serverstreaming/ clientstreaming/ bidistreaming/   每種模式一個 service 實作
  error/GlobalGrpcExceptionHandler.java                      例外 -> gRPC status
  interceptor/LoggingServerInterceptor.java                  request id、log、interceptor 順序

src/test/java/com/bill/streaming/grpc/                      每個行為一個測試類別

docs/grpc-introduction.html                                  圖解入門
```

產生的程式碼在 `build/generated/sources/proto/main/{java,grpc}`。每個 `.proto` 的 `java_package` 都設成對應實作類別的 package，所以 `*Grpc` 類別不用 import 就能直接用。

### Build 注意事項

- 套用 `com.google.protobuf` plugin 時，Spring Boot 的 Gradle plugin 會自動設定 `protoc` 和 `protoc-gen-grpc-java` 的版本，但 `build.gradle` 裡的 `protobuf { plugins { grpc {} } }` 仍然必要；少了它只會產生 message 類別，`*Grpc` stub 不會產生。
- 只要是 `BindableService` bean 就會被註冊成 gRPC service，標 `@Service` 就夠了。Spring gRPC 的 `@GrpcService` 是選用的，主要用在設定單一 service 的 interceptor。

## 下一步可以延伸的方向

- **TLS**：目前是 plaintext，正式環境要用 `spring.grpc.server.ssl.bundle` 設定 SSL bundle。
- **真的寫一個 client 專案**：另外開一個小專案，用 `spring-boot-starter-grpc-client` + `@ImportGrpcClients` 呼叫這個 server。
- **Observability**：加上 Micrometer metrics 與 tracing。

## 授權

[MIT](LICENSE)
