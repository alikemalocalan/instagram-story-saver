
name := "instastorysaver"
version := "0.1.3"
organization := "com.github.alikemalocalan"
scalaVersion := "2.12.12"

resolvers in Global ++= Seq(
  "Typesafe" at "https://repo.typesafe.com/typesafe/releases/",
  "Sbt plugins" at "https://dl.bintray.com/sbt/sbt-plugin-releases",
  "instagram4j" at "https://dl.bintray.com/instagram4j/maven"
)

libraryDependencies ++= Seq(
  "com.github.instagram4j" % "instagram4j" % "2.0.0",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.894",
  "com.typesafe" % "config" % "1.4.1",
  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "commons-io" % "commons-io" % "2.8.0",
  "org.rogach" %% "scallop" % "3.5.1"
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