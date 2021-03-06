package org.allenai.plugins

import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtScalariform._
import org.scalastyle.sbt.{
  ScalastylePlugin, Tasks => ScalastyleTasks

}
import scalariform.formatter.ScalaFormatter
import scalariform.formatter.preferences._
import scalariform.parser.ScalaParserException

import java.io.File

/** Plugin wrapping the scalastyle SBT plugin. This uses the configuration resource in this package
  *  to configure scalastyle, and sets up test and compile to depend on it. */
object StylePlugin extends AutoPlugin {
  lazy val formattingPreferences = {
    FormattingPreferences().
      setPreference(DoubleIndentClassDeclaration, true).
      setPreference(MultilineScaladocCommentsStartOnFirstLine, true).
      setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
  }

  object StyleKeys {
    val styleCheck = TaskKey[Unit]("styleCheck", "Check scala file style using scalastyle")
    val format = TaskKey[Seq[File]]("format", "Format scala sources using scalariform")
    val formatCheck = TaskKey[Seq[File]]("formatCheck", "Check scala sources using scalariform")
    val formatCheckStrict = TaskKey[Unit](
      "formatCheckStrict",
      "Check scala sources using scalariform, failing if an unformatted file is found"
    )
  }

  override def requires: Plugins = plugins.JvmPlugin

  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Def.Setting[_]] =
    // Add default scalariform + scalastyle settings.
    defaultScalariformSettings ++
    ScalastylePlugin.projectSettings ++
    // Add our default settings to test & compile.
    inConfig(Compile)(configSettings) ++
    inConfig(Test)(configSettings) ++
    // Check format & style on compile.
    // Putting format second means it gets evaluated first in SBT. Same with the Test checks.
    Seq(
      compileInputs in (Test, compile) <<=
        (compileInputs in (Test, compile)) dependsOn (StyleKeys.styleCheck in Test),
      compileInputs in (Test, compile) <<=
        (compileInputs in (Test, compile)) dependsOn (StyleKeys.formatCheck in Test),
      compileInputs in (Compile, compile) <<=
        (compileInputs in (Compile, compile)) dependsOn (StyleKeys.styleCheck in Compile),
      compileInputs in (Compile, compile) <<=
        (compileInputs in (Compile, compile)) dependsOn (StyleKeys.formatCheck in Compile),
      // scalariform settings
      ScalariformKeys.preferences := formattingPreferences
    )

  // Settings used for a particular configuration (such as Compile).
  def configSettings: Seq[Setting[_]] = Seq(
    StyleKeys.format := ScalariformKeys.format.value,
    StyleKeys.formatCheck := checkFormatting(
      ScalariformKeys.preferences.value,
      (sourceDirectories in ScalariformKeys.format).value.toList,
      (includeFilter in ScalariformKeys.format).value,
      (excludeFilter in ScalariformKeys.format).value,
      thisProjectRef.value,
      configuration.value,
      streams.value,
      scalaVersion.value
    ),
    StyleKeys.formatCheckStrict <<= (StyleKeys.formatCheck) map { files: Seq[File] =>
      if (files.size > 0) {
        throw new IllegalArgumentException("Unformatted files.")

      }
    },
    StyleKeys.styleCheck := {
      // "q" for "quiet".
      val args = Seq("q")
      val configXml = configFile(target.value)
      // If set to true, the SBT task will treat style warnings as style errors.
      val warnIsError = false
      val sourceDir = (scalaSource in StyleKeys.styleCheck).value
      val outputXml = new File(scalastyleTarget(target.value), "scalastyle-results.xml")
      val localStreams = streams.value
      ScalastyleTasks.doScalastyle(
        args,
        configXml,
        None, // Config URL; overrides configXml.
        warnIsError,
        sourceDir,
        outputXml,
        localStreams,
        0, // How frequently, in hours, to refresh config from URL. Ignored if URL is None.
        target.value,
        "/dev/null" // URL cache file. Ignored if URL is None.

      )

    }
  )

  /** @return a `scalastyle` directory in `targetDir`, creating it if needed */
  def scalastyleTarget(targetDir: File): File = {
    val dir = new File(targetDir, "scalastyle")
    IO.createDirectory(dir)
    dir

  }

  /** Copies the resource config file to the local filesystem (in `target`), and returns the new
    *  location.
    *  @param targetDir the value of the `target` setting key
    */
  def configFile(targetDir: File): File = {
    val destinationFile = new File(scalastyleTarget(targetDir), "scalastyle-config.xml")

    if (!destinationFile.exists) {
      val resourceName = "allenai-style-config.xml"
      Option(getClass.getClassLoader.getResourceAsStream(resourceName)) match {
        case None => throw new NullPointerException(s"Failed to find $resourceName in resources")
        case Some(sourceStream) => IO.write(destinationFile, IO.readStream(sourceStream))

      }

    }
    destinationFile

  }

  def checkFormatting(
    preferences: IFormattingPreferences,
    sourceDirectories: Seq[File],
    includeFilter: FileFilter,
    excludeFilter: FileFilter,
    ref: ProjectRef,
    configuration: Configuration,
    streams: TaskStreams,
    scalaVersion: String
  ): Seq[File] = {

    def unformattedFiles(files: Set[File]): Set[File] =
      for {
        file <- files if file.exists
        contents = IO.read(file)
        formatted = try {
          ScalaFormatter.format(
            contents,
            preferences,
            scalaVersion = pureScalaVersion(scalaVersion)
          )
        } catch {
          case e: ScalaParserException =>
            streams.log.error("Scalariform parser error for %s: %s".format(file, e.getMessage))
            contents
        }
        if formatted != contents
      } yield (file)

    streams.log("Checking scala formatting...")
    val files = sourceDirectories.descendantsExcept(includeFilter, excludeFilter).get.toSet
    val unformatted = unformattedFiles(files).toSeq sortBy (_.getName)
    for (file <- unformatted) {
      streams.log.error(f"misformatted: ${file.getName}")
    }

    unformatted
  }

  def pureScalaVersion(scalaVersion: String): String =
    scalaVersion.split("-").head
}
