credentials += Credentials(Path.userHome / ".sbt" / "central_sonatype_credentials")

ThisBuild / organization := "io.github.dotty-cps-async"
ThisBuild / organizationName := "dotty-cps-async"
ThisBuild / organizationHomepage := Some(url("https://github.com/dotty-cps-async"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/rssh/durable-monad"),
    "scm:git@github.com:rssh/durable-monad.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id    = "rssh",
    name  = "Ruslan Shevchenko",
    email = "ruslan@shevchenko.kiev.ua",
    url   = url("https://github.com/rssh")
  )
)

ThisBuild / description := "Durable monad for Scala - workflow engine with persistent state"
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/rssh/durable-monad"))

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true
