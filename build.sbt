import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.7"
ThisBuild / version := "0.2.0-SNAPSHOT"
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / publishTo := localStaging.value

// Shared library dependencies
lazy val cpsAsyncVersion = "1.2.0"
lazy val cpsAsyncOrg = "io.github.dotty-cps-async"
lazy val appContextVersion = "0.3.0"
lazy val jsoniterVersion = "2.30.15"

// Cross-platform core (Durable monad, DurableCacheBackend, etc.)
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    organization := "io.github.dotty-cps-async",
    name := "durable-monad-core",
    libraryDependencies ++= Seq(
      cpsAsyncOrg %%% "dotty-cps-async" % cpsAsyncVersion,
      "com.github.rssh" %%% "appcontext" % appContextVersion,
      "com.github.rssh" %%% "appcontext-tf" % appContextVersion,
      "org.scalameta" %%% "munit" % "1.0.0" % Test
    )
  )
  .jvmSettings(
    // JVM-specific settings
    Test / fork := true,
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion % Test,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % Test
    )
  )
  .jsSettings(
    // JS-specific settings
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      // Add jsoniter for cross-process test JSON serialization
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core" % jsoniterVersion % Test,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion % Test
    )
  )
  .nativeSettings(
    // Native-specific settings
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val coreNative = core.native

// Cats-effect integration module
lazy val catsEffectVersion = "3.5.7"

lazy val durableCe3 = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("durable-ce3"))
  .dependsOn(core, core % "test->test")
  .settings(
    organization := "io.github.dotty-cps-async",
    name := "durable-monad-ce3",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      cpsAsyncOrg %%% "cps-async-connect-cats-effect" % "1.0.0",
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0" % Test
    )
  )
  .jvmSettings(
    Test / fork := true
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )

lazy val durableCe3JVM = durableCe3.jvm
lazy val durableCe3JS = durableCe3.js

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, coreNative, durableCe3JVM, durableCe3JS)
  .settings(
    name := "durable-monad",
    publish / skip := true
  )
