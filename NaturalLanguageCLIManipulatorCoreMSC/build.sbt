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

// `sbt run` forks CoreEntryPoint into its own JVM by default, which does
// NOT connect that forked process's stdin to sbt's own stdin unless this
// is set - without it, the User Interface process (spawned via
// ProcessBuilder.inheritIO() inside CoreEntryPoint) inherits a stdin that
// was never actually connected to the real terminal, so keystrokes never
// arrive no matter what's typed or piped in.
run / connectInput := true
