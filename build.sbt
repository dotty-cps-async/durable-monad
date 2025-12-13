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
  .aggregate(coreJVM, coreJS, coreNative, controllerJS, scriptsJS)
  .settings(
    name := "durable-monad",
    publish / skip := true
  )

// Controller for durable scripts (JS only - uses Node.js)
lazy val controllerJS = project.in(file("controller-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS)
  .settings(
    name := "durable-monad-scalajs-controller",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    Compile / mainClass := Some("example.ControllerMain")
  )

// Isolated scripts that can run in VM contexts (JS only)
lazy val scriptsJS = project.in(file("scripts-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS)
  .settings(
    name := "scalajs-scripts",
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.NoModule) },
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      cpsAsyncOrg %%% "dotty-cps-async" % cpsAsyncVersion
    )
  )
