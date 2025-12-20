import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.7"

// Shared library dependencies
lazy val catsEffectVersion = "3.5.7"
lazy val cpsAsyncVersion = "1.2.0-SNAPSHOT"
lazy val cpsAsyncOrg = "io.github.dotty-cps-async"
lazy val appContextVersion = "0.3.0"
lazy val jsoniterVersion = "2.30.15"

// Cross-platform core (Durable monad, DurableCacheBackend, etc.)
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
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
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )
  .nativeSettings(
    // Native-specific settings
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val coreNative = core.native

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, coreNative)
  .settings(
    name := "durable-monad",
    publish / skip := true
  )
