name := "Hermes"

organization := "org.red"

version := "1.0"

scalaVersion := "2.12.2"

assemblyJarName in assembly := "hermes.jar"
mainClass in assembly := Some("org.red.hermes.ApplicationMain")

val meta = """.*(RSA|DSA)$""".r

// Hax to get .jar to execute
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) =>
    xs.map(_.toLowerCase) match {
      case ("manifest.mf" :: Nil) |
           ("index.list" :: Nil) |
           ("dependencies" :: Nil) |
           ("bckey.dsa" :: Nil) => MergeStrategy.discard
      case _ => MergeStrategy.first
    }
  case PathList("reference.conf") | PathList("application.conf") => MergeStrategy.concat
  case PathList(_*) => MergeStrategy.first
}

scalacOptions ++= Seq("-deprecation", "-feature")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

resolvers ++=
  Seq(
    "Artifactory Realm" at "http://maven.red.greg2010.me/artifactory/sbt-local/",
    "jcenter" at "http://jcenter.bintray.com"
  )


val circeVersion = "0.8.0"
libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-async" % "0.9.6",
  "org.typelevel" %% "cats" % "0.9.0",
  "com.typesafe" % "config" % "1.3.1",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "com.github.pukkaone" % "logback-gelf" % "1.1.10",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "org.quartz-scheduler" % "quartz" % "2.3.0",
  "net.joelinn" % "quartz-redis-jobstore" % "1.1.8",
  "org.red" %% "reddb" % "1.0.11-SNAPSHOT",
  "org.red" %% "iris" % "0.0.19-SNAPSHOT",
  "io.monix" %% "monix" % "2.3.0",
  "com.github.theholywaffle" % "teamspeak3-api" % "1.0.14",
  "net.dv8tion" % "JDA" % "3.2.0_226",
  "com.gilt" %% "gfc-concurrent" % "0.3.5",
  "net.databinder.dispatch" %% "dispatch-core"   % "0.13.1",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "io.circe" %% "circe-yaml" % "0.5.0").map(_.exclude("org.slf4j", "slf4j-simple"))
