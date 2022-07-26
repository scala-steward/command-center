import sbt._
import sbt.Keys._
import sbtwelcome._

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    coreUI,
    cli,
    emulatorSwt,
    emulatorSwing
  )
  .settings(
    name := "command-center",
    addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll"),
    addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll"),
    logo :=
      s"""
         |,---.                           .   ,---.         .
         ||     ,-. ,-,-. ,-,-. ,-. ,-. ,-|   |     ,-. ,-. |- ,-. ,-.
         ||     | | | | | | | | ,-| | | | |   |     |-' | | |  |-' |
         |`---' `-' ' ' ' ' ' ' `-^ ' ' `-'   `---' `-' ' ' `' `-' '
         |
         |""".stripMargin,
    usefulTasks := Seq(
      UsefulTask("a", "~compile", "Compile all modules with file-watch enabled"),
      UsefulTask("b", "fmt", "Run scalafmt on the entire project"),
      UsefulTask("c", "cli/run", "Run the Command Center CLI client (interactive mode by default)"),
      UsefulTask("d", "cli/assembly", "Create an executable JAR for running command line utility"),
      UsefulTask(
        "e",
        "cli/graalvm-native-image:packageBin",
        s"Create a native executable of the CLI client ${scala.Console.RED}(Windows not yet supported)"
      ),
      UsefulTask("f", "emulator-swt/run", "Run the Command Center emulated terminal (SWT)"),
      UsefulTask("g", "emulator-swt/assembly", "Create an executable JAR for running in terminal emulator mode (SWT)"),
      UsefulTask("h", "emulator-swing/run", "Run the Command Center emulated terminal (Swing)"),
      UsefulTask(
        "i",
        "emulator-swing/assembly",
        "Create an executable JAR for running in terminal emulator mode (Swing)"
      )
    )
  )

lazy val core = module("core")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % V.zio,
      "dev.zio" %% "zio-streams" % V.zio,
      "dev.zio" %% "zio-logging" % V.zioLogging,
      "dev.zio" %% "zio-prelude" % V.zioPrelude,
      "dev.zio" %% "zio-process" % V.zioProcess,
      "dev.zio" %% "zio-cache" % V.zioCache,
      "org.scala-lang" % "scala-reflect" % V.scalaReflect,
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "io.circe" %% "circe-config" % V.circeConfig,
      "com.monovore" %% "decline" % V.decline,
      "com.lihaoyi" %% "fansi" % V.fansi,
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.beachape" %% "enumeratum-circe" % V.enumeratum,
      "com.softwaremill.sttp.client3" %% "core" % V.sttp,
      "com.softwaremill.sttp.client3" %% "circe" % V.sttp,
      "com.softwaremill.sttp.client3" %% "zio" % V.sttp,
      "com.lihaoyi" %% "fastparse" % V.fastparse,
      "org.typelevel" %% "spire" % V.spire,
      "net.java.dev.jna" % "jna" % V.jna,
      "net.java.dev.jna" % "jna-platform" % V.jna,
      "org.ocpsoft.prettytime" % "prettytime-nlp" % V.prettytime
    ),
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, sbtVersion),
    buildInfoPackage := "commandcenter"
  )
  .enablePlugins(BuildInfoPlugin)

lazy val coreUI = module("core-ui")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "com.googlecode.lanterna" % "lanterna" % V.lanterna
    )
  )

lazy val cli = module("cli")
  .dependsOn(coreUI)
  .settings(
    fork := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "svm-subs" % V.graal
    ),
    // Windows native terminal requires JNA.
    libraryDependencies ++= Seq("net.java.dev.jna" % "jna-platform" % V.jna).filter(_ => OS.os == OS.Windows),
    assembly / mainClass := Some("commandcenter.cli.Main"),
    assembly / assemblyJarName := "cc.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _ @_*) => MergeStrategy.discard
      case _                           => MergeStrategy.first
    },
    graalVMNativeImageGraalVersion := Build.imageGraal,
    graalVMNativeImageOptions ++= Seq(
      "-H:+ReportExceptionStackTraces",
      "-H:+TraceClassInitialization",
      "-H:IncludeResources=lipsum",
      "-H:IncludeResources=applescript/.*",
      "--rerun-class-initialization-at-runtime=sun.security.provider.NativePRNG", // TODO: This is deprecated and doesn't quite work. Will need a workaround.
      "--initialize-at-run-time=com.googlecode.lanterna.terminal.win32.WindowsTerminal",
      "--initialize-at-build-time",
      "--no-fallback",
      "--enable-https",
      // The following unsafe flags are not ideal, but are currently needed until a solution for WindowsTerminal breaking the build is found
      "--allow-incomplete-classpath",
      "--report-unsupported-elements-at-runtime"
    )
  )
  .enablePlugins(GraalVMNativeImagePlugin, JavaServerAppPackaging)

lazy val enabledPlugins: Set[String] =
  Build.compilerOption("command-center-plugins").map(_.split(',').map(_.trim).toSet).getOrElse(Set.empty)

def optionalPlugin(project: Project): Option[ClasspathDependency] = {
  val cp =
    if (enabledPlugins.contains(project.id) || enabledPlugins.contains("*")) Some(project: ClasspathDependency)
    else None
  println(s"${scala.Console.CYAN}${project.id} enabled?${scala.Console.RESET} ${cp.isDefined}")
  cp
}

lazy val emulatorCore = module("emulator-core")
  .dependsOn(coreUI)
  .dependsOn(
    (optionalPlugin(strokeOrderPlugin) ++ optionalPlugin(jectPlugin)).toSeq *
  )
  .settings(
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "com.github.tulskiy" % "jkeymaster" % V.jkeymaster,
      "org.slf4j" % "slf4j-nop" % V.slf4j // Seems to be required for jkeymaster on Linux
    )
  )

lazy val emulatorSwt = module("emulator-swt")
  .dependsOn(emulatorCore)
  .dependsOn(
    (optionalPlugin(strokeOrderPlugin) ++ optionalPlugin(jectPlugin)).toSeq *
  )
  .settings(
    publishMavenStyle := false,
    run / baseDirectory := file("."),
    assembly / mainClass := Some("commandcenter.emulator.swt.Main"),
    javaOptions ++= Seq("-XstartOnFirstThread").filter(_ => OS.os == OS.MacOS),
    assembly / assemblyJarName := "cc-swt.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _ @_*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", _ @_*)             => MergeStrategy.discard
      case _                                       => MergeStrategy.first
    },
    libraryDependencies ++= swtDependencies
  )

lazy val emulatorSwing = module("emulator-swing")
  .dependsOn(emulatorCore)
  .dependsOn(
    (optionalPlugin(strokeOrderPlugin) ++ optionalPlugin(jectPlugin)).toSeq: _*
  )
  .settings(
    run / baseDirectory := file("."),
    assembly / mainClass := Some("commandcenter.emulator.swing.Main"),
    assembly / assemblyJarName := "cc-swing.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _ @_*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", _ @_*)             => MergeStrategy.discard
      case _                                       => MergeStrategy.first
    }
  )

lazy val strokeOrderPlugin = module("stroke-order-plugin", Some("extras/stroke-order"))
  .dependsOn(core)

lazy val jectPlugin = module("ject-plugin", Some("extras/ject"))
  .dependsOn(core)
  .settings(libraryDependencies ++= Seq("com.github.reibitto" %% "ject-ja" % V.ject))

lazy val extras = project
  .in(file("extras"))
  .aggregate(strokeOrderPlugin, jectPlugin)

def swtDependencies: Seq[ModuleID] =
  OS.os match {
    case OS.Windows =>
      Seq("org.eclipse.platform" % "org.eclipse.swt.win32.win32.x86_64" % V.swt intransitive ())
    case OS.MacOS =>
      Seq("org.eclipse.platform" % "org.eclipse.swt.cocoa.macosx.x86_64" % V.swt intransitive ())
    case OS.Linux =>
      Seq("org.eclipse.platform" % "org.eclipse.swt.gtk.linux.x86_64" % V.swt intransitive ())
    case OS.Other(name) =>
      println(s"SWT does not support OS '$name'")
      Seq.empty
  }

def module(projectId: String, moduleFile: Option[String] = None): Project =
  Project(id = projectId, base = file(moduleFile.getOrElse(projectId)))
    .settings(Build.defaultSettings(projectId))
