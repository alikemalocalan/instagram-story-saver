
name := "instastorysaver"
version := "0.1.0"
organization := "com.github.alikemalocalan"
scalaVersion := "2.13.1"

val scala213Version = "2.13.1"
val AkkaVersion = "2.6.10"
// To make the default compiler and REPL use Dotty
//val dottyVersion = "0.27.0-RC1"
//scalaVersion := dottyVersion

resolvers ++= Seq(
  "apache-snapshots" at "https://repository.apache.org/snapshots/",
  "Typesafe" at "https://repo.typesafe.com/typesafe/releases/",
  "Sbt plugins" at "https://dl.bintray.com/sbt/sbt-plugin-releases",
  "instagram4j" at "https://dl.bintray.com/instagram4j/maven"
)


libraryDependencies ++= Seq(
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "com.github.instagram4j" % "instagram4j" % "develop-370fad2",
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "io.spray" %% "spray-json" % "1.3.5",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.894",
  "com.github.pureconfig" %% "pureconfig" % "0.12.1",
  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "commons-io" % "commons-io" % "2.6"
)

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

// To cross compile with Dotty and Scala 2
//crossScalaVersions := Seq(dottyVersion, scala213Version)
