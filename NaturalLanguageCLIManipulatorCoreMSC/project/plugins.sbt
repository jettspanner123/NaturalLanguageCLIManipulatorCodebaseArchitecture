// GraalVM native-image packaging (see ARCHITECTURE.md section 2 - Distribution).
// Verify this is still the latest release before the first real build.
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.3.4")

// Protocol Buffers codegen for the IPC Shared Protocol
// (see NaturalLanguageCLIManipulatorSharedNMSC/IPCSharedProtocols/AmyIpc.proto).
// Verify these are still the latest releases before the first real build.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17"
