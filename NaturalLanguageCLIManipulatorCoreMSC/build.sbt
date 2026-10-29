name := "amy-core"
organization := "com.jettspanner"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.4"

Compile / scalaSource := baseDirectory.value / "Source"
Test / scalaSource := baseDirectory.value / "Test"

// The IPC Shared Protocol lives in the sibling Shared folder, not inside
// Core - both processes generate their own typed code from the same
// .proto file. See NaturalLanguageCLIManipulatorSharedNMSC/IPCSharedProtocols/.
Compile / PB.protoSources := Seq(
  baseDirectory.value.getParentFile / "NaturalLanguageCLIManipulatorSharedNMSC" / "IPCSharedProtocols"
)

Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value
)

libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
