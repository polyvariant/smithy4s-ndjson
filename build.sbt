ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v")),
)

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / tlFatalWarnings := false
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

ThisBuild / mergifyStewardConfig ~= (_.map(_.withMergeMinors(true)))

// Smithy sources are formatted too (`smithyFmtAll` to fix), checked alongside scalafmt in the
// same sbt invocation rather than as a job of its own — it's fast, and a formatting failure is
// worth reporting all at once.
ThisBuild / githubWorkflowBuild ~= {
  _.map {
    case step: WorkflowStep.Sbt if step.name.exists(_.contains("formatting")) =>
      step.withCommands(step.commands :+ "smithyFmtCheckAll")
    case other => other
  }
}

val smithy4sVersion = "0.19.11"
// Only the generated trait classes need this, and only against the stable parts of the model API
// (`AbstractTrait`, `ShapeId`, `Node`). A consumer whose build also pulls an older smithy-model —
// via alloy, say — resolves to the newer of the two, which is the direction Smithy tooling
// supports: a newer model library reads older models.
val smithyVersion = "1.73.0"
val http4sVersion = "0.23.36"
val fs2Version = "3.13.0"
val weaverVersion = "0.13.0"

val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-no-indent",
    "-Wunused:all",
  )
)

/** The protocol itself: the `org.polyvariant.ndjson#ndjsonRestJson` trait, and nothing else.
  *
  * Published as a plain Java artifact (no Scala suffix, no Scala library) because a protocol
  * definition is not Scala-specific: it is consumed by `smithy-build`, by Smithy CLI validators,
  * and by codegen for any language — none of which should have to pick a Scala version to depend on
  * it. Scala users get it transitively via `http4s` below.
  *
  * `SmithyTraitCodegenPlugin` generates the Java trait classes from the model and packages the
  * `.smithy` file under `META-INF/smithy`, so the trait resolves off the classpath.
  */
lazy val protocol = project
  .in(file("modules/protocol"))
  .enablePlugins(SmithyTraitCodegenPlugin)
  .settings(
    name := "smithy4s-ndjson-protocol",
    // No previous release to check against yet. Note this is `tlMimaPreviousVersions := Set.empty`
    // and *not* `disablePlugins(MimaPlugin)`: sbt-typelevel's `TypelevelPlugin` transitively
    // requires `MimaPlugin`, so disabling the latter also switches off `TypelevelSonatypePlugin` —
    // which is what sets `publishTo`. The module then fails to publish with "Repository for
    // publishing is not specified", but only on a release run, long after CI has gone green.
    tlMimaPreviousVersions := Set.empty,
    crossPaths := false,
    autoScalaLibrary := false,
    // The trait is built only from shapes in `smithy.api`, so no extra model dependencies are
    // needed to *generate* it (a protocol referencing alloy shapes would list `alloy-core` here).
    smithyTraitCodegenDependencies := Nil,
    // The generated Java trait classes extend Smithy's own `AbstractTrait`, so the model library
    // is needed to compile them — and by anything loading the trait through Smithy's ModelAssembler.
    libraryDependencies += "software.amazon.smithy" % "smithy-model" % smithyVersion,
    // javadoc rejects the lint flags sbt-typelevel passes to javac (`-Xlint:all` is not a javadoc
    // option), so the doc task gets a pared-down set. Only `-source` survives, to keep the
    // generated docs on the same language level as the compiled classes.
    Compile / doc / javacOptions := Seq("-source", tlJdkRelease.value.fold("11")(_.toString)),
    smithyTraitCodegenJavaPackage := "org.polyvariant.ndjson",
    smithyTraitCodegenNamespace := "org.polyvariant.ndjson",
  )

/** Makes `protocol`'s trait visible to smithy4s codegen in this build.
  *
  * `smithy4sInternalDependenciesAsJars` doesn't include jars from the "smithy4s" configuration, so
  * an ordinary `dependsOn` isn't enough to put the protocol trait on the codegen model path — this
  * is the in-build equivalent of a `% Smithy4s`-scoped dependency. Downstream users don't need any
  * of this: for them the trait arrives on the ordinary classpath, via `META-INF/smithy`.
  */
lazy val buildTimeProtocolDependency =
  Compile / smithy4sInternalDependenciesAsJars ++=
    (protocol / Compile / fullClasspathAsJars).value.map(_.data)

/** The http4s interpreter for the protocol: `NdjsonRestJsonBuilder`, the counterpart to smithy4s's
  * `SimpleRestJsonBuilder` for services that stream.
  */
lazy val http4s = project
  .in(file("modules/http4s"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .dependsOn(protocol, testFixtures % Test)
  .settings(
    name := "smithy4s-ndjson-http4s",
    commonSettings,
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-json" % smithy4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.typelevel" %% "weaver-cats" % weaverVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
    ),
    buildTimeProtocolDependency,
  )

/** A service exercising every shape the protocol admits (binary in, NDJSON out, plain JSON,
  * metadata bindings), so the interpreter is tested against real codegen output rather than a
  * hand-written stand-in.
  *
  * Its own module because the smithy4s sbt plugin only wires codegen into `Compile` — a
  * `Test / smithy4sInputDirs` in `http4s` is accepted but never runs. Keeping it separate also
  * keeps the fixtures out of the published jar.
  */
lazy val testFixtures = project
  .in(file("modules/testfixtures"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .disablePlugins(MimaPlugin)
  .dependsOn(protocol)
  .settings(
    name := "smithy4s-ndjson-testfixtures",
    commonSettings,
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion
    ),
    buildTimeProtocolDependency,
  )

lazy val root = tlCrossRootProject.aggregate(protocol, http4s, testFixtures)
