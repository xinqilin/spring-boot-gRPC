# Spring Boot + gRPC Receiver 練習

這是一個 Spring Boot 4.1 + gRPC 的練習專案，角色定位是 **gRPC Server（接收端）**：
別人（gRPC Client）呼叫這個服務，這個服務負責接收請求並回應。

## gRPC 是什麼、為什麼要學

- gRPC 是 Google 開發的 RPC 框架，底層走 HTTP/2，訊息格式用 **Protocol Buffers（protobuf）** 序列化，
  比傳統 REST + JSON 更省頻寬、更快，且有明確的 schema（`.proto` 檔）可以跨語言產生 client/server 程式碼。
- 你在 `.proto` 檔定義「服務有哪些方法、輸入輸出長什麼樣子」，工具（`protoc` + gRPC plugin）會自動幫你產生
  Java 的介面與資料類別，你只需要「實作介面」，不用自己手刻序列化/反序列化、也不用手刻 HTTP handler。

## 四種 RPC 溝通模式

gRPC 有 4 種溝通模式，這個專案刻意把每一種都拆成**獨立的 proto service + 獨立的 package**，
一個資料夾就是一個完整、可以獨立閱讀的範例，不會四種擠在同一個 service/類別裡混在一起：

| 模式 | Package | proto service / 方法 | 說明 |
|---|---|---|---|
| **Unary**（一問一答） | `grpc.unary` | `UnaryGreeter.SayHello` | 最基本的型態：一個 request 換一個 response，行為上很像一般的 REST API。 |
| **Server-streaming**（伺服器推播） | `grpc.serverstreaming` | `ServerStreamingGreeter.Subscribe` | Client 送一個 request，Server 陸續推送多筆 response，最後才 `onCompleted()`。適合「訂閱通知」「即時報價」這類場景。 |
| **Client-streaming**（客戶端推播） | `grpc.clientstreaming` | `ClientStreamingGreeter.Upload` | Client 陸續送多筆 request，Server 收完（Client 呼叫 `onCompleted()`）後才彙總、回一筆 response。**這最貼近「接收端」的典型場景**：對方系統持續把資料推進來，這裡負責收集/彙整。 |
| **Bidirectional-streaming**（雙向） | `grpc.bidistreaming` | `BidiStreamingGreeter.Chat` | 雙方各自獨立、同時持續互推訊息，這裡的實作是收到一筆就馬上回一筆（echo）。 |

## 專案結構

```
src/main/proto/
  common.proto            四個 service 共用的訊息型別（HelloRequest / HelloReply / UploadSummary）
  unary.proto             UnaryGreeter service
  server_streaming.proto  ServerStreamingGreeter service
  client_streaming.proto  ClientStreamingGreeter service
  bidi_streaming.proto    BidiStreamingGreeter service

src/main/java/com/bill/streaming/grpc/
  common/                 （protoc 產生的共用訊息類別，不用手寫）
  unary/UnaryServiceImpl.java
  serverstreaming/ServerStreamingServiceImpl.java
  clientstreaming/ClientStreamingServiceImpl.java
  bidistreaming/BidiStreamingServiceImpl.java

src/test/java/com/bill/streaming/grpc/
  unary/UnaryServiceImplTests.java
  serverstreaming/ServerStreamingServiceImplTests.java
  clientstreaming/ClientStreamingServiceImplTests.java
  bidistreaming/BidiStreamingServiceImplTests.java
```

每個 proto 檔都用 `import "common.proto"` 引用共用的訊息型別，避免四個檔案各自重複定義一樣的
`HelloRequest`/`HelloReply`。每個 `*ServiceImpl` 只要標 `@Service` 就會被 Spring Boot 的 gRPC
autoconfiguration 自動掃描並註冊（不需要額外的 `@GrpcService` annotation，那是舊版社群
`grpc-spring-boot-starter` 的用法，Spring Boot 4.1 起 gRPC 支援已內建整併進 Boot 本體）。

## 如何 Build / Run

```bash
./gradlew build      # 會先跑 protoc 產生 Java stub，再編譯（Spring Boot 4.1 已自動管理 protoc / grpc-java 版本，不用手動設定）
./gradlew bootRun     # 啟動服務，預設監聽 9090 port（可用 spring.grpc.server.port 覆寫）
```

啟動成功會看到類似這樣的 log，代表四個 service 各自獨立註冊成功：

```
Registered gRPC service: streaming.unary.UnaryGreeter
Registered gRPC service: streaming.serverstreaming.ServerStreamingGreeter
Registered gRPC service: streaming.clientstreaming.ClientStreamingGreeter
Registered gRPC service: streaming.bidistreaming.BidiStreamingGreeter
Registered gRPC service: grpc.reflection.v1.ServerReflection
Registered gRPC service: grpc.health.v1.Health
gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090], port: 9090
```

（`grpc.reflection.v1.ServerReflection` 跟 `grpc.health.v1.Health` 是因為加了 `io.grpc:grpc-services`
依賴才會自動出現，用來讓外部工具可以查詢有哪些服務、以及做健康檢查。）

## 如何跑內建測試

```bash
./gradlew test
```

四個 package 各自有自己的測試類別，都用 `@AutoConfigureTestGrpcTransport` 啟動一個 in-process 的
gRPC server（不佔用真實 port），不需要另外寫一個 client 專案、也不需要手動啟動 server：

- `UnaryServiceImplTests` / `ServerStreamingServiceImplTests`：只需要 blocking stub。
- `ClientStreamingServiceImplTests` / `BidiStreamingServiceImplTests`：需要 async stub
  （因為要邊送多筆 request 邊收 response），所以用 `CountDownLatch` 等非同步的 `onCompleted()` 完成。

> 小提醒：`@ImportGrpcClients` 如果改用 `basePackageClasses` 做套件掃描，底層預設只認得 blocking stub，
> 抓不到 async stub。所以這裡改用 `types = XxxStub.class` 明確指定要建立哪一種 stub bean。

## 用 grpcurl 手動驗證（不用寫 client 程式）

因為專案加了 reflection 支援，不需要 `.proto` 檔，直接用 [grpcurl](https://github.com/fullstorydev/grpcurl) 就能打：

```bash
# macOS 安裝 grpcurl（沒裝的話才需要）
brew install grpcurl

# 先 ./gradlew bootRun 啟動服務，再開另一個 terminal：

# 列出所有服務
grpcurl -plaintext localhost:9090 list

# 呼叫 Unary：SayHello
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.unary.UnaryGreeter/SayHello

# 呼叫 Server-streaming：Subscribe（會連續印出 5 筆回應）
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.serverstreaming.ServerStreamingGreeter/Subscribe

# 健康檢查
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

`grpcurl` 對 client-streaming / bidi-streaming 的支援方式是從 stdin 讀多筆 JSON（用 `-d @` 搭配換行分隔的
JSON 訊息），操作起來不如 `./gradlew test` 直觀，這兩種模式建議直接看對應的 `*ServiceImplTests` 寫法。

## 已知限制 / 這次順手處理的事

- 專案原本就加了 `spring-boot-starter-data-jpa`，但沒有設定任何 DataSource，會導致 **整個 App 完全無法啟動**
  （跟 gRPC 沒有關係，是既有狀態）。這裡加了 `runtimeOnly 'com.h2database:h2'` 讓它能用一個
  in-memory H2 資料庫正常開機，之後你真的要接資料庫時，記得換成正式的 driver/連線設定。

## 下一步可以自己延伸的方向

- **TLS**：目前是 plaintext（`-plaintext` 參數），正式環境要開 TLS。
- **Interceptor**：可以寫 `ServerInterceptor` 做統一的 log、認證、trace context 傳遞。
- **錯誤處理**：目前範例沒有處理例外，真實情境要用 `Status`/`StatusRuntimeException`
  回傳明確的 gRPC 錯誤碼（例如 `INVALID_ARGUMENT`、`NOT_FOUND`），而不是讓例外直接變成 `UNKNOWN`。
- **真的寫一個 client 專案**：目前驗證都是同一個專案內的 test 或 grpcurl，真的要練習「兩個服務對打」
  可以另外開一個小專案，用 `spring-boot-starter-grpc-client` + `@ImportGrpcClients` 呼叫這個 server。
