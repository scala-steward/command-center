package commandcenter.command

import com.typesafe.config.Config
import commandcenter.CCRuntime.Env
import commandcenter.command.CommandError._
import commandcenter.util.ProcessUtil
import zio._

final case class OpenBrowserCommand() extends Command[Unit] {
  val commandType: CommandType = CommandType.OpenBrowserCommand

  val commandNames: List[String] = List.empty

  val title: String = "Open in Browser"

  def preview(searchInput: SearchInput): ZIO[Env, CommandError, PreviewResults[Unit]] = {
    val input      = searchInput.input
    val startsWith = input.startsWith("http://") || input.startsWith("https://")

    // TODO: also check endsWith TLD + URL.isValid

    if (startsWith)
      UIO(PreviewResults.one(Preview.unit.onRun(ProcessUtil.openBrowser(input))))
    else
      ZIO.fail(NotApplicable)
  }
}

object OpenBrowserCommand extends CommandPlugin[OpenBrowserCommand] {
  def make(config: Config): UManaged[OpenBrowserCommand] = ZManaged.succeed(OpenBrowserCommand())
}
