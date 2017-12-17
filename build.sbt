import sbtbuildinfo.BuildInfoPlugin.autoImport._

inThisBuild(Seq(
  organization := "de.proteinevolution",
  scalaVersion := "2.12.4",
  version      := "0.1.0"
))

lazy val commonSettings = Seq(
  scalaJSProjects := Seq(client),
  pipelineStages in Assets := Seq(scalaJSPipeline),
  logLevel := Level.Warn,
  TwirlKeys.templateImports := Nil
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion,
    "playVersion" -> play.core.PlayVersion.current
  ),
  buildInfoPackage := "build"
)

lazy val coreSettings = commonSettings ++ Settings.compileSettings

lazy val disableDocs = Seq[Setting[_]](
  sources in (Compile, doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false
)

lazy val common = (project in file("modules/common"))
    .enablePlugins(PlayScala, JavaAppPackaging)
    .settings(
      name := "common",
      libraryDependencies ++= Dependencies.commonDeps,
      Settings.compileSettings,
      TwirlKeys.templateImports := Seq.empty,
      disableDocs
    )
    .disablePlugins(PlayLayoutPlugin)

lazy val root = (project in file("."))
    .enablePlugins(PlayScala, PlayAkkaHttp2Support, JavaAppPackaging, SbtWeb, BuildInfoPlugin)
    .dependsOn(client, common)
    .aggregate(client, common)
    .settings(
      coreSettings,
      name := "mpi-toolkit",
      libraryDependencies ++= (Dependencies.commonDeps ++ Dependencies.testDeps ++ Dependencies.frontendDeps),
      pipelineStages := Seq(digest, gzip),
      compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
      buildInfoSettings
    )

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }
resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

lazy val client = (project in file("client"))
    .enablePlugins(ScalaJSPlugin, ScalaJSWeb, BuildInfoPlugin)
    .settings(
      scalaJSUseMainModuleInitializer := true,
      scalaJSUseMainModuleInitializer in Test := false,
      buildInfoSettings,
      libraryDependencies ++= Seq(
        "org.scala-js"  %%% "scalajs-dom"     % "0.9.3",
        "com.tgf.pizza" %%% "scalajs-mithril" % "0.1.1",
        "be.doeraene"   %%% "scalajs-jquery"  % "0.9.2"
      )
    )

fork := true // required for "sbt run" to pick up javaOptions
javaOptions += "-Dplay.editor=http://localhost:63342/api/file/?file=%s&line=%s"
fork in Test := true
logLevel in Test := Level.Info

scalacOptions in Test ++= Seq("-Yrangepos")
testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD")
JsEngineKeys.engineType := JsEngineKeys.EngineType.Node
