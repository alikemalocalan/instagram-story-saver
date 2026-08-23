name := "instastorysaver"
version := "1.0.0"
organization := "com.github.alikemalocalan"
scalaVersion := "3.3.8"

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.github.instagram4j.instagram4j" % "web"          % "3.0",
  "com.typesafe"                       % "config"       % "1.4.9",
  "com.lihaoyi"                        %% "mainargs"    % "0.7.8",
  "org.slf4j"                          % "slf4j-simple" % "2.0.17"
)

Compile / mainClass := Some("com.github.alikemalocalan.instastorysaver.StorySaverScheduler")

scalacOptions := Seq(
  "-source:3.3",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding",
  "utf8"
)

val stage = taskKey[Unit]("Stage task")

val Stage = config("stage")

enablePlugins(JavaAppPackaging, GraalVMNativeImagePlugin)

graalVMNativeImageOptions ++= Seq(
  "-H:+UnlockExperimentalVMOptions",
  "--no-fallback",
  "-H:+StripDebugInfo",
  "-O3",
  "-R:MinHeapSize=16m",
  "-R:MaxHeapSize=64m",
  "--strict-image-heap",
  "--enable-https",
  "--enable-http",
  "--install-exit-handlers",
  "--initialize-at-build-time=org.slf4j,com.typesafe.config,android.org.json,okio"
)

assembly / assemblyJarName := s"${name.value}.jar"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*)                                                 => MergeStrategy.concat
  case PathList("META-INF", "versions", _*)                                                 => MergeStrategy.first
  case PathList("META-INF", "maven", _*)                                                    => MergeStrategy.discard
  case PathList("META-INF", "proguard", _*)                                                 => MergeStrategy.discard
  case PathList("META-INF", "MANIFEST.MF" | "INDEX.LIST" | "DEPENDENCIES")                  => MergeStrategy.discard
  case PathList("module-info.class")                                                        => MergeStrategy.discard
  case path if path.endsWith(".SF") || path.endsWith(".DSA") || path.endsWith(".RSA")      => MergeStrategy.discard
  case path if path.endsWith(".kotlin_module") || path.endsWith(".kotlin_metadata")        => MergeStrategy.discard
  case path if path.endsWith(".kotlin_builtins")                                           => MergeStrategy.first
  case path if path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".txt")  => MergeStrategy.discard
  case path if path.toLowerCase.contains("license") || path.toLowerCase.contains("notice") => MergeStrategy.discard
  case other                                                                                => MergeStrategy.defaultMergeStrategy(other)
}

assembly / mainClass := Some("com.github.alikemalocalan.instastorysaver.StorySaverCLI")
