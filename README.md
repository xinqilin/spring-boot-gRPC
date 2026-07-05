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

## Protobuf（`.proto`）怎麼寫、怎麼設計

### 基本骨架

以 `src/main/proto/common.proto` 為例：

```proto
syntax = "proto3";                                        // 目前幾乎都用 proto3，語法跟舊的 proto2 不同
package streaming.common;                                 // protobuf 自己的 namespace，避免不同 .proto 檔的型別互撞
option java_package = "com.bill.streaming.grpc.common";    // 產生的 Java 程式碼要放在哪個 package
option java_multiple_files = true;                         // 每個 message 各自產生一個獨立 .java 檔（見下方說明）
option java_outer_classname = "CommonProto";               // java_multiple_files=true 時，這個名字只用在少數 static 方法上
```

`java_multiple_files` 建議一律開 `true`：關掉的話，所有 message 都會被包在
`option java_outer_classname` 那個 outer class 裡面，你得寫 `CommonProto.HelloRequest` 才能用；
開了之後每個 message 都是自己獨立的 top-level class（`HelloRequest`、`HelloReply` ...），比較符合
一般 Java 的直覺。

### message：定義資料結構，大致等同一個 DTO / POJO

```proto
message HelloRequest {
  string name = 1;
}
```

每個欄位的寫法固定是：

```
<型別> <欄位名稱> = <field number>;
```

### 欄位後面那個數字（1、2、3...）是什麼？可以不寫嗎？

**不行，這個數字（field number）是必填的**，而且它的意思常被誤會——它**不是欄位的順序編號**，
而是這個欄位在**二進位 wire format** 裡的唯一身分證號碼。protobuf 序列化時完全不靠欄位名稱，
只靠這個數字去對應欄位。幾個實際規則：

- 同一個 `message` 裡，數字不能重複，但**不需要從 1 開始連續編號**（不過為了好讀，通常還是照順序給）。
- **1～15** 用 1 byte 就能編碼 tag，**16～2047** 要用 2 bytes——所以把常用/高頻欄位留給 1～15，
  低頻或之後才加的欄位可以用比較大的數字。
- 這個數字一旦上線用過（尤其資料已經序列化存起來、或 API 已經對外發布），**就不能再更改或重複使用**，
  否則舊資料或舊版 client 讀出來的欄位會對應到錯的意思，是實際會發生的相容性事故。
- 想拿掉某個欄位時，把那行刪掉、但**保留（不重用）那個數字**，或乾脆用 `reserved` 明確標記，
  避免以後不小心又用到同一個數字：

  ```proto
  message HelloRequest {
    reserved 2, 3;
    reserved "old_field_name";
    string name = 1;
  }
  ```

- 新增欄位就直接用「目前最大數字 + 1」。舊版 client 讀到自己不認識的欄位號碼會直接忽略、不會壞掉——
  這就是 protobuf 能做到「向前/向後相容」的關鍵設計，你可以新增欄位而不用同時逼所有 client 升級。

可以把 field number 想成「資料庫欄位的 internal ID」而不是「欄位在螢幕上排第幾個」：**欄位改名字
完全沒問題**（不影響 wire 相容性，只影響產生出來的 Java method 名稱），但這個 ID 一旦用了就不能亂動。

### 常用的欄位型別

| proto 型別 | 說明 | 對應 Java 型別 |
|---|---|---|
| `string` | UTF-8 文字 | `String` |
| `int32` / `int64` | 一般整數 | `int` / `long` |
| `sint32` / `sint64` | 對負數做過 zig-zag 編碼，欄位常是負數時比 `int32`/`int64` 省空間 | `int` / `long` |
| `bool` | 布林 | `boolean` |
| `double` / `float` | 浮點數 | `double` / `float` |
| `bytes` | 任意二進位資料 | `ByteString` |
| `repeated T` | 陣列/List，例如 `repeated string tags = 4;` | `List<T>` |

### message 可以互相 `import`，避免重複定義

這個 repo 把四個 service 共用的 `HelloRequest` / `HelloReply` / `UploadSummary` 抽到
`common.proto`，其他四個 `.proto` 檔用 `import "common.proto";` 引用，再用完整路徑
`streaming.common.HelloRequest` 取用（可以直接對照 `src/main/proto/unary.proto` 怎麼寫）。
好處是四個 service 不用各自重複定義一樣的訊息型別；壞處是要多開一個檔案、多一層 import，
如果訊息很少共用、專案很小，也可以每個 `.proto` 各自定義自己的訊息就好，不一定要抽共用檔。

### service / rpc：定義四種溝通模式的語法差異

```proto
service UnaryGreeter {
  rpc SayHello(HelloRequest) returns (HelloReply) {}                 // Unary
}
service ServerStreamingGreeter {
  rpc Subscribe(HelloRequest) returns (stream HelloReply) {}         // Server-streaming：stream 在「回應」那邊
}
service ClientStreamingGreeter {
  rpc Upload(stream HelloRequest) returns (UploadSummary) {}         // Client-streaming：stream 在「請求」那邊
}
service BidiStreamingGreeter {
  rpc Chat(stream HelloRequest) returns (stream HelloReply) {}       // Bidirectional：兩邊都 stream
}
```

四種模式的語法差異就只在 `stream` 關鍵字要不要加、加在請求還是回應，其他完全一樣——這也是為什麼
`streaming.proto`（如果沒拆開的話）很容易四種擠在一起看起來很像，但語意差很多。

### Naming Convention（風格慣例，非強制但建議照做）

- `message` / `service` 名稱：`PascalCase`，例如 `HelloRequest`、`UnaryGreeter`。
- 欄位名稱：`lower_snake_case`，例如 `user_id`——protoc 產生 Java 程式碼時會自動轉成
  `getUserId()` / `setUserId()` 這種 camelCase getter/setter，欄位本身不用手動轉。
- `rpc` 方法名稱：`PascalCase`，例如 `SayHello`——這是為了跟其他語言（Go、Python...）共用同一份
  schema 時風格一致，不是照 Java 的方法命名慣例。

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

每個 `*ServiceImpl` 只要標 `@Service` 就會被 Spring Boot 的 gRPC autoconfiguration 自動掃描並註冊
（不需要額外的 `@GrpcService` annotation，那是舊版社群 `grpc-spring-boot-starter` 的用法，
Spring Boot 4.1 起 gRPC 支援已內建整併進 Boot 本體）。

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

## 下一步可以自己延伸的方向

- **TLS**：目前是 plaintext（`-plaintext` 參數），正式環境要開 TLS。
- **Interceptor**：可以寫 `ServerInterceptor` 做統一的 log、認證、trace context 傳遞。
- **錯誤處理**：目前範例沒有處理例外，真實情境要用 `Status`/`StatusRuntimeException`
  回傳明確的 gRPC 錯誤碼（例如 `INVALID_ARGUMENT`、`NOT_FOUND`），而不是讓例外直接變成 `UNKNOWN`。
- **真的寫一個 client 專案**：目前驗證都是同一個專案內的 test 或 grpcurl，真的要練習「兩個服務對打」
  可以另外開一個小專案，用 `spring-boot-starter-grpc-client` + `@ImportGrpcClients` 呼叫這個 server。
