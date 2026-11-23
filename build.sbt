// ==============================================================================
// Instagram Story Saver - Modern Scala 3 Build Definition
// ==============================================================================

ThisBuild / organization := "com.github.alikemalocalan"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version := "1.0.0"

val upickleVersion = "4.4.3"
val configVersion = "1.4.10"
val mainargsVersion = "0.7.8"
val slf4jVersion = "2.0.20"
val munitVersion = "1.3.6"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, GraalVMNativeImagePlugin)
  .settings(
    name := "instastorysaver",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleVersion,
      "com.typesafe" % "config" % configVersion,
      "com.lihaoyi" %% "mainargs" % mainargsVersion,
      "org.slf4j" % "slf4j-simple" % slf4jVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
    // sbt 2's `test` behaves like testQuick; always run the full fixture suite.
    Test / test := (Test / testOnly).toTask(" *").value,
    scalacOptions ++= Seq(
      "-source:3.9",
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
    graalVMNativeImageOptions ++= {
      val isMac = System.getProperty("os.name", "").toLowerCase.contains("mac")
      val baseOptions = Seq(
        "-H:+UnlockExperimentalVMOptions",
        "--no-fallback",
        "-Os",
        "-march=armv8-a",
        "-R:MinHeapSize=16m",
        "-R:MaxHeapSize=64m",
        "-H:-IncludeStackTraces",
        "--enable-https",
        "--enable-http",
        "--install-exit-handlers",
        "--initialize-at-build-time=org.slf4j,com.typesafe.config"
      )
      if (isMac) {
        baseOptions ++ Seq(
          "-H:NativeLinkerOption=-Wl,-dead_strip"
        )
      } else {
        baseOptions ++ Seq(
          "-H:+StripDebugInfo",
          "-H:NativeLinkerOption=-Wl,--gc-sections",
          "-H:NativeLinkerOption=-Wl,-z,relro,-z,now"
        )
      }
    },

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
      case PathList("META-INF", "MANIFEST.MF" | "INDEX.LIST" | "DEPENDENCIES" | "NOTICE" | "NOTICE.txt" | "LICENSE" | "LICENSE.txt") => MergeStrategy.discard
      case PathList("META-INF", p @ _*) if p.exists(_.toLowerCase.contains("notice")) || p.exists(_.toLowerCase.contains("license")) => MergeStrategy.discard
      case PathList("module-info.class")                                                       => MergeStrategy.discard
      case path if path.endsWith(".SF") || path.endsWith(".DSA") || path.endsWith(".RSA")      => MergeStrategy.discard
      case path if path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".txt") => MergeStrategy.discard
      case path if path.toLowerCase.contains("license") || path.toLowerCase.contains("notice") => MergeStrategy.discard
      // Scala 3 TASTy metadata is compile-time only; not needed at runtime or in native-image.
      case path if path.endsWith(".tasty")                                                     => MergeStrategy.discard
      case other => MergeStrategy.defaultMergeStrategy(other)
    }
  )
