name := "instastorysaver"
version := "0.2.0"
organization := "com.github.alikemalocalan"
scalaVersion := "3.3.8"

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.github.instagram4j.instagram4j" % "web"             % "3.0",
  "com.typesafe"                       % "config"          % "1.4.9",
  "commons-io"                         % "commons-io"      % "2.22.0",
  "com.lihaoyi"                        %% "mainargs"       % "0.7.8",
  "ch.qos.logback"                     % "logback-classic" % "1.6.3"
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

enablePlugins(JavaAppPackaging)

assembly / assemblyJarName := s"${name.value}.jar"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", _*)             => MergeStrategy.first
  case PathList("META-INF", "okio.kotlin_module")       => MergeStrategy.first
  case PathList("META-INF", "MANIFEST.MF")              => MergeStrategy.discard
  case PathList("META-INF", "INDEX.LIST")               => MergeStrategy.discard
  case PathList("META-INF", "LICENSE" | "LICENSE.txt")  => MergeStrategy.discard
  case PathList("META-INF", "NOTICE" | "NOTICE.txt")    => MergeStrategy.discard
  case "module-info.class"                              => MergeStrategy.last
  case other                                            => MergeStrategy.defaultMergeStrategy(other)
}

assembly / mainClass := Some("com.github.alikemalocalan.instastorysaver.StorySaverCLI")
