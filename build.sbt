// ==============================================================================
// Instagram Story Saver - Modern Scala 3 Build Definition
// ==============================================================================

ThisBuild / organization := "com.github.alikemalocalan"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "1.0.0"
ThisBuild / resolvers += "jitpack" at "https://jitpack.io"

val instagram4jVersion = "3.0"
val jacksonVersion = "2.22.2"
val jacksonAnnotationsVersion = "2.22"
val okhttpVersion = "5.5.0"
val okioVersion = "3.18.1"
val kotlinVersion = "2.4.10"
val jsoupVersion = "1.23.1"
val configVersion = "1.4.9"
val mainargsVersion = "0.7.8"
val slf4jVersion = "2.0.18"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, GraalVMNativeImagePlugin)
  .settings(
    name := "instastorysaver",
    resolvers += "jitpack" at "https://jitpack.io",
    libraryDependencies ++= Seq(
      ("com.github.instagram4j.instagram4j" % "web" % instagram4jVersion).exclude("com.squareup.okhttp3", "okhttp"),
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonAnnotationsVersion,
      "com.squareup.okhttp3" % "okhttp-jvm" % okhttpVersion,
      "com.squareup.okio" % "okio" % okioVersion,
      "org.jetbrains.kotlin" % "kotlin-stdlib" % kotlinVersion,
      "org.jsoup" % "jsoup" % jsoupVersion,
      "com.typesafe" % "config" % configVersion,
      "com.lihaoyi" %% "mainargs" % mainargsVersion,
      "org.slf4j" % "slf4j-simple" % slf4jVersion
    ),
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonAnnotationsVersion,
      "com.squareup.okio" % "okio" % okioVersion,
      "com.squareup.okio" % "okio-jvm" % okioVersion,
      "org.jetbrains.kotlin" % "kotlin-stdlib" % kotlinVersion,
      "org.jsoup" % "jsoup" % jsoupVersion
    ),
    scalacOptions ++= Seq(
      "-source:3.3",
      "-encoding",
      "utf-8",
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    Compile / mainClass := Some("com.github.alikemalocalan.instastorysaver.StorySaverCLI"),

    // --------------------------------------------------------------------------
    // GraalVM Native Image Settings
    // --------------------------------------------------------------------------
    graalVMNativeImageOptions ++= Seq(
      "-H:+UnlockExperimentalVMOptions",
      "--no-fallback",
      "-H:+StripDebugInfo",
      "-O3",
      "-march=armv8-a",
      "-R:MinHeapSize=16m",
      "-R:MaxHeapSize=64m",
      "--strict-image-heap",
      "-H:NativeLinkerOption=-Wl,--gc-sections",
      "-H:NativeLinkerOption=-Wl,-z,relro,-z,now",
      "--enable-https",
      "--enable-http",
      "--install-exit-handlers",
      "--initialize-at-build-time=org.slf4j,com.typesafe.config,android.org.json,okio"
    ),

    // --------------------------------------------------------------------------
    // Assembly Fat JAR Merge Strategies
    // --------------------------------------------------------------------------
    assembly / assemblyJarName := s"${name.value}.jar",
    assembly / mainClass := Some("com.github.alikemalocalan.instastorysaver.StorySaverCLI"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)                                                => MergeStrategy.concat
      case PathList("META-INF", "versions", _*)                                                => MergeStrategy.first
      case PathList("META-INF", "maven", _*)                                                   => MergeStrategy.discard
      case PathList("META-INF", "proguard", _*)                                                => MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF" | "INDEX.LIST" | "DEPENDENCIES")                 => MergeStrategy.discard
      case PathList("module-info.class")                                                       => MergeStrategy.discard
      case PathList("okhttp3", _*)                                                             => MergeStrategy.first
      case path if path.endsWith(".SF") || path.endsWith(".DSA") || path.endsWith(".RSA")      => MergeStrategy.discard
      case path if path.endsWith(".kotlin_module") || path.endsWith(".kotlin_metadata")        => MergeStrategy.discard
      case path if path.endsWith(".kotlin_builtins")                                           => MergeStrategy.first
      case path if path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".txt") => MergeStrategy.discard
      case path if path.toLowerCase.contains("license") || path.toLowerCase.contains("notice") => MergeStrategy.discard
      case other => MergeStrategy.defaultMergeStrategy(other)
    }
  )
