import sbt._
import Keys._
import Settings.versions

val commonSettings = Seq(
  scalacOptions := Seq(
    "-Xlint",
    "-unchecked",
    "-deprecation",
    "-feature"
  ),
  scalaVersion := "2.11.8",
  version := versions.fiddle
)

lazy val root = project.in(file("."))
  .aggregate(page, compilerServer, runtime, client, router)

lazy val shared = project
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)

lazy val client = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % versions.dom,
      "com.lihaoyi" %%% "scalatags" % versions.scalatags,
      "com.lihaoyi" %%% "upickle" % versions.upickle,
      "com.github.marklister" %%% "base64" % "0.2.3",
      "org.scala-lang.modules" %% "scala-async" % versions.async % "provided",
      "org.scalatest" %%% "scalatest" % versions.scalatest % "test"
    ),
    // rename output always to -opt.js
    artifactPath in(Compile, fastOptJS) := ((crossTarget in(Compile, fastOptJS)).value /
      ((moduleName in fastOptJS).value + "-opt.js")),
    relativeSourceMaps := true
  )


lazy val page = project
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % versions.dom,
      "com.lihaoyi" %%% "scalatags" % versions.scalatags
    )
  )

lazy val runtime = project
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-library" % scalaJSVersion,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value
    )
  )

lazy val compilerServer = project.in(file("compiler-server"))
  .dependsOn(shared, page)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .settings(commonSettings)
  .settings(Revolver.settings: _*)
  .settings(
    name := "scalafiddle-core",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "com.typesafe.akka" %% "akka-actor" % versions.akka,
      "com.typesafe.akka" %% "akka-slf4j" % versions.akka,
      "com.typesafe.akka" %% "akka-http-core" % versions.akkaHttp,
      "com.typesafe.akka" %% "akka-http" % versions.akkaHttp,
      "ch.qos.logback" % "logback-classic" % "1.1.9",
      "org.scala-js" % "scalajs-compiler" % scalaJSVersion cross CrossVersion.full,
      "org.scala-js" %% "scalajs-tools" % scalaJSVersion,
      "org.scalamacros" %% "paradise" % versions.macroParadise cross CrossVersion.full,
      "org.scala-lang.modules" %% "scala-async" % versions.async % "provided",
      "com.lihaoyi" %% "scalatags" % versions.scalatags,
      "com.lihaoyi" %% "upickle" % versions.upickle,
      "io.get-coursier" %% "coursier" % versions.coursier,
      "io.get-coursier" %% "coursier-cache" % versions.coursier,
      "org.apache.maven" % "maven-artifact" % "3.3.9",
      "org.xerial.snappy" % "snappy-java" % "1.1.2.6",
      "org.xerial.larray" %% "larray" % "0.4.0",
      "org.scalatest" %% "scalatest" % versions.scalatest % "test"
    ),
    (resources in Compile) ++= {
      (managedClasspath in(runtime, Compile)).value.map(_.data) ++ Seq(
        (packageBin in(page, Compile)).value
      )
    },
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    javaOptions in Revolver.reStart ++= Seq("-Xmx3g", "-Xss4m"),
    javaOptions in Universal ++= Seq("-J-Xss4m"),
    resourceGenerators in Compile += Def.task {
      // store build version in a property file
      val file = (resourceManaged in Compile).value / "version.properties"
      val contents =
        s"""
           |version=${version.value}
           |scalaVersion=${scalaVersion.value}
           |scalaJSVersion=$scalaJSVersion
           |aceVersion=${versions.ace}
           |""".stripMargin
      IO.write(file, contents)
      Seq(file)
    }.taskValue,
    scriptClasspath := Seq("../config/") ++ scriptClasspath.value,
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from("java:8")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
        expose(8080)
      }
    },
    imageNames in docker := Seq(
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-core",
        tag = Some("latest")
      ),
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-core",
        tag = Some(version.value)
      )
    )
  )

lazy val router = (project in file("router"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(sbtdocker.DockerPlugin)
  .dependsOn(shared)
  .settings(Revolver.settings: _*)
  .settings(commonSettings)
  .settings(
    name := "scalafiddle-router",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % versions.akka,
      "com.typesafe.akka" %% "akka-slf4j" % versions.akka,
      "com.typesafe.akka" %% "akka-http-core" % versions.akkaHttp,
      "com.typesafe.akka" %% "akka-http" % versions.akkaHttp,
      "com.lihaoyi" %% "scalatags" % versions.scalatags,
      "org.webjars" % "ace" % versions.ace,
      "org.webjars" % "normalize.css" % "3.0.2",
      "com.lihaoyi" %% "upickle" % versions.upickle,
      "com.github.marklister" %% "base64" % "0.2.3",
      "ch.megard" %% "akka-http-cors" % "0.1.11",
      "org.scalatest" %% "scalatest" % versions.scalatest % "test",
      "ch.qos.logback" % "logback-classic" % "1.1.9"
    ),
    javaOptions in Revolver.reStart ++= Seq("-Xmx1g"),
    scriptClasspath := Seq("../config/") ++ scriptClasspath.value,
    resourceGenerators in Compile += Def.task {
      // store build version in a property file
      val file = (resourceManaged in Compile).value / "version.properties"
      val contents =
        s"""
           |version=${version.value}
           |scalaVersion=${scalaVersion.value}
           |scalaJSVersion=$scalaJSVersion
           |aceVersion=${versions.ace}
           |""".stripMargin
      IO.write(file, contents)
      Seq(file)
    }.taskValue,
    (resources in Compile) ++= {
      Seq((fullOptJS in(client, Compile)).value.data)
    },
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from("openjdk:8")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir)
        expose(8080)
      }
    },
    imageNames in docker := Seq(
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-router",
        tag = Some("latest")
      ),
      ImageName(
        namespace = Some("scalafiddle"),
        repository = "scalafiddle-router",
        tag = Some(version.value)
      )
    )
  )
