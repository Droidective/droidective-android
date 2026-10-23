# Reflection fixtures

What `grpcio-reflection` 1.84 (`grpc.reflection.v1alpha`) answered when asked about
`reflected.proto`, recorded off the wire by Droidective's own HTTP/2 client. Each `.grpc` file is
one answer's body as it crossed: gRPC's five-byte frame, then a `ServerReflectionResponse`.

- `list.grpc` — `list_services`: `droidective.reflected.Shop` and the reflection service.
- `symbol.grpc` — `file_containing_symbol: droidective.reflected.Shop`: `reflected.proto` and the
  two files it imports, all three in one answer.
- `symbol-alone.grpc` — the same answer with only `reflected.proto` kept, re-serialized, as a
  server that sends only the file asked for answers.
- `types.grpc`, `timestamp.grpc` — `file_by_filename` for each import.
- `missing.grpc` — `file_containing_symbol: droidective.reflected.Nope`: an `error_response`,
  NOT_FOUND, in a call whose own status is OK.

`reflected.protoset` is the same two files compiled by `protoc --include_imports`, to check the
schema reflection gives against the one protoc gives.

The server answered `grpc.reflection.v1` with UNIMPLEMENTED ("Method not found!"), which is why
v1alpha is asked next.
