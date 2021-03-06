package commandcenter.command

import com.typesafe.config.Config
import commandcenter.CCRuntime.Env
import commandcenter.view.Renderer
import zio.{ Managed, ZIO }

// TODO: Work in progress
final case class TerminalCommand(commandNames: List[String]) extends Command[Unit] {
  val commandType: CommandType = CommandType.TerminalCommand
  val title: String            = "Terminal"

  def preview(searchInput: SearchInput): ZIO[Env, CommandError, PreviewResults[Unit]] =
    for {
      input <- ZIO.fromOption(searchInput.asPrefixed).orElseFail(CommandError.NotApplicable)
    } yield PreviewResults.one(
      Preview.unit.rendered(Renderer.renderDefault("Terminal", input.rest)).score(Scores.high(input.context))
    )
}

object TerminalCommand extends CommandPlugin[TerminalCommand] {
  def make(config: Config): Managed[CommandPluginError, TerminalCommand] =
    for {
      commandNames <- config.getManaged[Option[List[String]]]("commandNames")
    } yield TerminalCommand(commandNames.getOrElse(List("$", ">")))
}
