addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.8.7")
addSbtPlugin("org.typelevel" % "sbt-typelevel-mergify" % "0.8.7")
// Newer than the one sbt-typelevel brings: older JGit reads a git worktree as a bare repository,
// so the build fails to load from one.
addSbtPlugin("com.github.sbt" % "sbt-git" % "3.0.0")
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.19.13")
addSbtPlugin("org.polyvariant" % "smithy-scala-tools-sbt" % "0.3.2")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
