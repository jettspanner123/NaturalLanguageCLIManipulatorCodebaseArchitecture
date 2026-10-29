name := "amy-core"
organization := "com.jettspanner"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.4"

Compile / scalaSource := baseDirectory.value / "Source"
Test / scalaSource := baseDirectory.value / "Test"
