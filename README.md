# spring-boot-gRPC

[![Build](https://github.com/xinqilin/spring-boot-gRPC/actions/workflows/build.yml/badge.svg)](https://github.com/xinqilin/spring-boot-gRPC/actions/workflows/build.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

English | [繁體中文](README.zh-TW.md)

A hands-on **gRPC server** built with Spring Boot 4.1 and its built-in Spring gRPC support. It covers all four RPC modes, then the parts a real service needs: error handling, interceptors and metadata, deadlines and cancellation, and flow control. Every behavior described here is pinned down by a test.

New to gRPC? Start with the [illustrated introduction](https://bill-lin.dev/spring-boot-gRPC/grpc-introduction.html) (Traditional Chinese).

## What's inside

| Topic | Code | Proven by |
|---|---|---|
| Four RPC modes | `grpc/unary`, `grpc/serverstreaming`, `grpc/clientstreaming`, `grpc/bidistreaming` | `*ServiceImplTests` |
| Error handling: `GrpcExceptionHandler` vs. `onError(Status)` | `grpc/error/GlobalGrpcExceptionHandler`, `ClientStreamingServiceImpl` | `UnaryServiceImplTests`, `ClientStreamingServiceImplTests` |
| Interceptors, metadata and interceptor ordering | `grpc/interceptor/LoggingServerInterceptor` | `LoggingServerInterceptorTests` |
| Deadlines and cancellation | `ServerStreamingServiceImpl#subscribe` | `ServerStreamingServiceImplTests` |
| Flow control (backpressure) | `ServerStreamingServiceImpl#subscribeWithFlowControl` | `ServerStreamingServiceImplTests` |
| Health and reflection services | auto-configured | `HealthServiceTests` |
| In-process vs. real Netty server in tests | | `RealServerIntegrationTests` |

**Stack:** Java 21 · Spring Boot 4.1.1 (Spring gRPC 1.1.1) · grpc-java 1.83.1 · protobuf 4.35.1 · Gradle 9.8.1

## Quick start

```bash
./gradlew build      # generate code from .proto, compile, run all tests
./gradlew bootRun    # start the gRPC server on localhost:9090
./gradlew test --tests 'com.bill.streaming.grpc.unary.UnaryServiceImplTests'   # a single test class
```

Server reflection is enabled, so [grpcurl](https://github.com/fullstorydev/grpcurl) works without the `.proto` files (`brew install grpcurl` on macOS):

```bash
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.unary.UnaryGreeter/SayHello
grpcurl -plaintext -d '{"name": "Bill"}' localhost:9090 streaming.serverstreaming.ServerStreamingGreeter/Subscribe
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

grpcurl reads client-streaming and bidi messages from stdin (`-d @`, one JSON message per line). For those two modes the tests are easier to follow.

## Why gRPC

- gRPC runs on HTTP/2 and serializes messages with **Protocol Buffers**. Payloads are smaller than JSON, and many calls share one connection.
- You describe the API once in a `.proto` file. `protoc` and the gRPC plugin generate client stubs and server base classes for many languages. You implement an interface instead of hand-writing serialization or HTTP handlers.

## The four RPC modes

Each mode lives in its own `.proto` service and its own Java package, so every folder is a complete example you can read on its own.

| Mode | Package | Service / method | Behavior |
|---|---|---|---|
| **Unary** | `grpc.unary` | `UnaryGreeter.SayHello` | One request, one response. Behaves like a REST call. |
| **Server-streaming** | `grpc.serverstreaming` | `ServerStreamingGreeter.Subscribe` | One request; the server pushes several responses, then `onCompleted()`. Think subscriptions or price feeds. |
| **Client-streaming** | `grpc.clientstreaming` | `ClientStreamingGreeter.Upload` | The client pushes many requests; the server replies once after the client calls `onCompleted()`. The typical "receiver" scenario: another system streams data in and this service aggregates it. |
| **Bidirectional** | `grpc.bidistreaming` | `BidiStreamingGreeter.Chat` | Both sides stream independently. This implementation echoes each message as it arrives. |

The only syntactic difference is where the `stream` keyword goes:

```proto
service UnaryGreeter {
  rpc SayHello(HelloRequest) returns (HelloReply) {}                 // Unary
}
service ServerStreamingGreeter {
  rpc Subscribe(HelloRequest) returns (stream HelloReply) {}         // stream on the response
}
service ClientStreamingGreeter {
  rpc Upload(stream HelloRequest) returns (UploadSummary) {}         // stream on the request
}
service BidiStreamingGreeter {
  rpc Chat(stream HelloRequest) returns (stream HelloReply) {}       // stream on both
}
```

## Beyond hello world

### Error handling

gRPC reports failures as a [status code](https://grpc.io/docs/guides/status-codes/) plus a description. This repo shows two ways to produce one:

- **Spring way (Unary).** `UnaryServiceImpl` throws `IllegalArgumentException` for a blank name. `GlobalGrpcExceptionHandler` implements `GrpcExceptionHandler` and maps it to `INVALID_ARGUMENT`, much like `@ControllerAdvice` in Spring MVC. A handler returns `null` for exceptions it does not handle. Anything nobody handles falls back to `Status.fromThrowable`, which turns an ordinary exception into `UNKNOWN`, so the client cannot tell what went wrong.
- **Plain gRPC way (Client-streaming).** `ClientStreamingServiceImpl` calls `responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(...).asRuntimeException())` directly. After `onError` the call is over: never call `onNext` or `onCompleted` again, which is why the observer tracks a `failed` flag.

### Interceptors and metadata

`LoggingServerInterceptor` runs before every call, like a servlet filter:

1. reads the `x-request-id` request header (gRPC metadata),
2. echoes it back in the response headers,
3. logs method, status code and latency when the call closes.

`@GlobalServerInterceptor` applies it to every service. To target a single service, use `@GrpcService(interceptors = ...)` instead.

**Ordering matters.** Global interceptors are sorted by `@Order`; the first one is the outermost. Spring gRPC's `GrpcExceptionHandlerInterceptor` is annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`, so by default it is the outermost layer. When a service throws, the exception handler closes the call itself, and any interceptor inside it never sees that `close()`: the failure goes unlogged. `LoggingServerInterceptor` therefore uses the same `HIGHEST_PRECEDENCE`. On equal order, application beans are registered before auto-configured ones, so the logging interceptor ends up outside the exception handler. `LoggingServerInterceptorTests` fails if that ever changes, for example when the annotation is removed.

Error responses that carry no message are "trailers-only": the server never calls `sendHeaders`, so the request id is not echoed on errors.

### Deadlines and cancellation

`Subscribe` pushes one reply every 200 ms. A client that sets a 300 ms deadline receives `#1` and `#2`, then gets `DEADLINE_EXCEEDED`. On the server, `ServerCallStreamObserver#isCancelled()` turns true and the loop stops before sending `#3`. `setOnCancelHandler` has to be registered before the first `onNext`.

The `Thread.sleep` is there for the demo only. It blocks the thread handling the call; a production service would schedule the pushes instead.

### Flow control

Calling `onNext` in a loop ignores how fast the client reads. Messages the transport cannot send yet pile up in server memory. `SubscribeWithFlowControl` sends only while `isReady()` is true and continues from `setOnReadyHandler` when the transport has room again.

The test controls the pace from the client side with a `ClientResponseObserver`: `disableAutoRequestWithInitial(1)`, then `request(1)` after each message. In that test the server's ready handler runs 1,001 times, sends one message each time, and all 1,000 messages arrive in order.

### Testing: in-process vs. real server

Most tests use `@SpringBootTest` with `@AutoConfigureTestGrpcTransport`, which swaps in an in-process transport: fast, no ports. Generated stubs are injected with `@ImportGrpcClients`. `RealServerIntegrationTests` deliberately skips that annotation: it starts the real Netty server on a random port (`spring.grpc.server.port=0` plus `@LocalGrpcServerPort`) and connects with a plain `ManagedChannel` over TCP and HTTP/2.

### Health and reflection

Both services come from `io.grpc:grpc-services`, which `spring-boot-starter-grpc-server` already includes. Reflection lets tools such as grpcurl discover services without `.proto` files. Health exposes the standard `grpc.health.v1.Health` service; an empty service name asks for the overall status.

## Writing `.proto` files

### File skeleton

From `src/main/proto/common.proto`:

```proto
syntax = "proto3";                                        // proto3 is the current syntax
package streaming.common;                                 // protobuf's own namespace, keeps type names from colliding
option java_package = "com.bill.streaming.grpc.common";    // Java package of the generated code
option java_multiple_files = true;                         // one top-level Java class per message
option java_outer_classname = "CommonProto";               // only used for a few static helpers when java_multiple_files is true
```

Keep `java_multiple_files = true`. Without it, every message is nested inside the outer class and you write `CommonProto.HelloRequest` instead of `HelloRequest`.

### Messages and field numbers

```proto
message HelloRequest {
  string name = 1;
}
```

Each field is `<type> <name> = <field number>;`. The number is **required**, and it is not a display order. It is the field's identity on the wire: protobuf never sends field names, only numbers.

- Numbers must be unique within a message. They do not have to be consecutive.
- 1 to 15 encode in one byte, 16 to 2047 in two. Give 1 to 15 to frequent fields.
- Once a number has been used (serialized data exists or the API is public), **never change or reuse it**. Old data or old clients would map the bytes to the wrong field.
- To remove a field, delete it and reserve its number and name:

  ```proto
  message HelloRequest {
    reserved 2, 3;
    reserved "old_field_name";
    string name = 1;
  }
  ```

- To add a field, use the next free number. Old clients skip numbers they do not know, which is what makes protobuf forward and backward compatible.

Renaming a field is safe on the wire (it only changes the generated Java method names). Changing its number is not.

### Common types

| proto type | Meaning | Java type |
|---|---|---|
| `string` | UTF-8 text | `String` |
| `int32` / `int64` | integers | `int` / `long` |
| `sint32` / `sint64` | zig-zag encoded, smaller when values are often negative | `int` / `long` |
| `bool` | boolean | `boolean` |
| `double` / `float` | floating point | `double` / `float` |
| `bytes` | arbitrary binary data | `ByteString` |
| `repeated T` | list, e.g. `repeated string tags = 4;` | `List<T>` |

### Sharing messages with `import`

The four services share `HelloRequest`, `HelloReply` and `UploadSummary` from `common.proto`. The other files `import "common.proto";` and refer to `streaming.common.HelloRequest` (see `src/main/proto/unary.proto`). This avoids duplicate definitions at the cost of one more file. In a small project with little sharing, defining messages per file is also fine.

### Naming conventions

- `message` and `service` names: `PascalCase` (`HelloRequest`, `UnaryGreeter`).
- Field names: `lower_snake_case` (`user_id`). protoc generates `getUserId()` / `setUserId()` for Java.
- `rpc` names: `PascalCase` (`SayHello`), so the schema reads the same from Go, Python and other languages.

## Project layout

```
src/main/proto/
  common.proto              shared messages (HelloRequest / HelloReply / UploadSummary)
  unary.proto               UnaryGreeter
  server_streaming.proto    ServerStreamingGreeter (Subscribe, SubscribeWithFlowControl)
  client_streaming.proto    ClientStreamingGreeter
  bidi_streaming.proto      BidiStreamingGreeter

src/main/java/com/bill/streaming/grpc/
  unary/ serverstreaming/ clientstreaming/ bidistreaming/   one service implementation per mode
  error/GlobalGrpcExceptionHandler.java                      exception -> gRPC status
  interceptor/LoggingServerInterceptor.java                  request id, logging, ordering

src/test/java/com/bill/streaming/grpc/                      one test class per behavior

docs/grpc-introduction.html                                  illustrated introduction (Traditional Chinese)
```

Generated code goes to `build/generated/sources/proto/main/{java,grpc}`. Each `.proto` sets `java_package` to the package of its implementation, so the generated `*Grpc` classes are used without imports.

### Build notes

- Spring Boot's Gradle plugin reacts to the `com.google.protobuf` plugin and configures the `protoc` and `protoc-gen-grpc-java` versions. The `protobuf { plugins { grpc {} } }` block in `build.gradle` is still required; without it only message classes are generated, and the `*Grpc` stubs are missing.
- A service is exposed as soon as it is a `BindableService` bean; `@Service` is enough. Spring gRPC's `@GrpcService` is optional and mainly useful for per-service interceptors.

## Next steps

- **TLS:** the server runs in plaintext. Configure an SSL bundle with `spring.grpc.server.ssl.bundle`.
- **A real client project:** call this server from a separate app using `spring-boot-starter-grpc-client` and `@ImportGrpcClients`.
- **Observability:** add Micrometer metrics and tracing for gRPC calls.

## License

[MIT](LICENSE)
