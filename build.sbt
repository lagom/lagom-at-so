
name := "lagom-at-so"
version := "1.0"

scalaVersion := "2.11.7"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws" % "2.5.3"
)

// Exclude commons-logging because it conflicts with the jcl-over-slf4j
libraryDependencies ~= { _ map {
  case m if m.organization == "com.typesafe.play" =>
    m.exclude("commons-logging", "commons-logging")
  case m => m
}}

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.first
  case other => (assemblyMergeStrategy in assembly).value(other)
}
