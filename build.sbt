
name := "instastorysaver"
version := "0.2.0"
organization := "com.github.alikemalocalan"
scalaVersion := "2.13.5"

libraryDependencies ++= Seq(
  "com.github.instagram4j" % "instagram4j" % "2.0.4",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.1009",
  "com.typesafe" % "config" % "1.4.1",
  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "commons-io" % "commons-io" % "2.8.0",
  "org.rogach" %% "scallop" % "4.0.2"
)

mainClass in Compile := Some("com.github.alikemalocalan.instastorysaver.StorySaverScheduler")

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

val stage = taskKey[Unit]("Stage task")

val Stage = config("stage")

enablePlugins(JavaAppPackaging)

assemblyJarName in assembly := s"${name.value}.jar"

assemblyMergeStrategy in assembly := {
  case "module-info.class" => MergeStrategy.last
  case other => MergeStrategy.defaultMergeStrategy(other)
}

mainClass in assembly := Some("com.github.alikemalocalan.instastorysaver.StorySaverCLI")