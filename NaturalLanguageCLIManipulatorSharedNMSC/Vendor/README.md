# Vendor

`scalapb/scalapb.proto` is vendored here (extracted from the `scalapb-runtime` jar, version 0.11.17) so that the plain `protoc` CLI used by the TypeScript side's `npm run generate:ipc` can resolve `AmyIpc.proto`'s `import "scalapb/scalapb.proto"`. The Scala side doesn't need this copy - sbt-protoc resolves that import directly from the `scalapb-runtime` dependency on its own classpath.

This lives one level above `IPCSharedProtocols/`, not inside it, on purpose: Core's `PB.protoSources` points at `IPCSharedProtocols/` and treats every `.proto` file found there as a compile target, not just as an importable path. Putting this vendored file inside that folder made sbt-protoc try to compile it as its own target too, which collided with ScalaPB's own bundled copy of `scalapb.proto` on the classpath ("already defined" errors). Keeping it as a sibling folder means it's only ever resolved as an import, never treated as a target.

If `AmyIpc.proto` ever bumps the ScalaPB option version it depends on, re-extract this file from the matching `scalapb-runtime_3` jar version.
