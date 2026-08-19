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

/** Puts `protocol`'s trait on the codegen model path, using the in-build artifact rather than a
  * resolved one.
  *
  * `smithy4sInternalDependenciesAsJars` doesn't include jars from the "smithy4s" configuration, so
  * an ordinary `dependsOn` isn't enough — this is the in-build equivalent of a `% Smithy4s`-scoped
  * dependency, and it is what lets the modules below build against an unreleased protocol change in
  * the same commit — including the very first one, before the protocol has ever been published.
  *
  * Downstream users need none of this: the trait reaches them on the ordinary classpath via
  * `META-INF/smithy`, pulled in by `core`'s `smithy4sDependencies` manifest entry.
  */
lazy val buildTimeProtocolDependency =
  Compile / smithy4sInternalDependenciesAsJars ++=
    (protocol / Compile / fullClasspathAsJars).value.map(_.data)

/** Keeps the protocol namespace from being generated a second time.
  *
  * The trait has to be on the model path of every module that runs codegen (an annotated service
  * can't be read without it), but only `core` should emit Scala for it — otherwise each consumer
  * gets its own copy of `org.polyvariant.ndjson.NdjsonRestJson` and downstream classpaths end up
  * with duplicates of one fully-qualified class.
  *
  * This uses the deprecated sbt key rather than its suggested replacement, the `smithy4sCodegen`
  * Smithy metadata, on purpose: metadata applies to the assembled model as a whole, so declaring
  * the exclusion in the protocol's own `.smithy` would also suppress generation in `core`, which is
  * the one module that must generate it. The two sources are unioned, not overridden, so there is
  * no per-module way to express this in metadata. Revisit if the key is actually removed.
  */
lazy val protocolGeneratedByCore =
  Compile / smithy4sExcludedNamespaces := List("org.polyvariant.ndjson")

/** Records the protocol as a smithy-level dependency of `core` in its jar manifest, so a downstream
  * build pulls the trait onto its own codegen model path automatically.
  *
  * The smithy4s plugin does this for you, but only for dependencies declared `% Smithy4s` in
  * `libraryDependencies` — which an in-build module can't be, since nothing has published the
  * current snapshot yet and the `% Smithy4s` entry would fail to resolve. So the entry is written
  * directly; see https://github.com/disneystreaming/smithy4s/issues/1749 for the sbt setting that
  * would make this unnecessary.
  *
  * The value format is the plugin's own (`moduleIdEncode`): comma-separated `org:name:revision`,
  * with no Scala suffix because `protocol` sets `crossPaths := false`. `packageOptions` is appended
  * to rather than assigned, so the plugin's own entry for `core`'s external `% Smithy4s`
  * dependencies (currently none) would survive alongside this one.
  */
lazy val protocolManifestEntry =
  Compile / packageBin / packageOptions += {
    val manifest = new java.util.jar.Manifest()
    manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest
      .getMainAttributes()
      .putValue(
        "smithy4sDependencies",
        s"org.polyvariant:smithy4s-ndjson-protocol:${version.value}",
      )
    Package.JarManifest(manifest)
  }

/** The generated Scala view of the protocol trait: `org.polyvariant.ndjson.NdjsonRestJson`.
  *
  * Its own module because that class must be generated exactly once in the build. Generating it
  * from both `http4s` and `testFixtures` — which is what happened while each ran codegen over the
  * protocol namespace itself — puts two copies of the same fully-qualified class on a downstream
  * classpath.
  *
  * Note this deliberately does NOT `.dependsOn(protocol)`. `protocol` is a Java-only artifact
  * (`crossPaths := false`, `autoScalaLibrary := false`), so a project dependency would pin this
  * module — and everything downstream of it — to the JVM, foreclosing a JS/Native cross-build, and
  * would put a suffix-less artifact in the published POM. The trait is a build-time input only: it
  * reaches codegen via `buildTimeProtocolDependency`, and reaches downstream builds as the
  * `smithy4sDependencies` manifest entry written by `protocolManifestEntry`.
  */
lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "smithy4s-ndjson-core",
    commonSettings,
    tlMimaPreviousVersions := Set.empty,
    libraryDependencies += "com.disneystreaming.smithy4s" %% "smithy4s-core" % smithy4sVersion,
    buildTimeProtocolDependency,
    protocolManifestEntry,
  )

/** The http4s interpreter for the protocol: `NdjsonRestJsonBuilder`, the counterpart to smithy4s's
  * `SimpleRestJsonBuilder` for services that stream.
  */
lazy val http4s = project
  .in(file("modules/http4s"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .dependsOn(core, testFixtures % Test)
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
    protocolGeneratedByCore,
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
  .dependsOn(core)
  .settings(
    name := "smithy4s-ndjson-testfixtures",
    commonSettings,
    publish / skip := true,
    buildTimeProtocolDependency,
    protocolGeneratedByCore,
  )

lazy val root = tlCrossRootProject.aggregate(protocol, core, http4s, testFixtures)
