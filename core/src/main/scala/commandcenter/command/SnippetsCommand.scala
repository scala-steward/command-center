package commandcenter.command

import com.typesafe.config.Config
import commandcenter.command.SnippetsCommand.Snippet
import commandcenter.scorers.LengthScorer
import commandcenter.tools.Tools
import commandcenter.view.Renderer
import commandcenter.CCRuntime.Env
import fansi.Color
import io.circe.Decoder
import zio.*

final case class SnippetsCommand(commandNames: List[String], snippets: List[Snippet]) extends Command[String] {
  val commandType: CommandType = CommandType.SnippetsCommand
  val title: String = "Snippets"

  def preview(searchInput: SearchInput): ZIO[Env, CommandError, PreviewResults[String]] =
    for {
      (isPrefixed, snippetSearch) <-
        ZIO.fromOption(searchInput.asPrefixed).mapBoth(_ => (false, searchInput.input), s => (true, s.rest)).merge

    } yield PreviewResults.fromIterable(snippets.map { snip =>
      val score =
        if (isPrefixed)
          Scores.veryHigh(searchInput.context)
        else {
          // TODO: Implement better scoring algorithm (like Sublime Text fuzzy search)
          val matchScore = LengthScorer.scoreDefault(snip.keyword, snippetSearch)
          Scores.veryHigh(searchInput.context) * matchScore
        }

      (snip, score)
    }.collect {
      case (snippet, score) if score > 0 =>
        Preview(snippet.value)
          .onRun(Tools.setClipboard(snippet.value))
          .score(score)
          .rendered(Renderer.renderDefault(title, Color.Magenta(snippet.keyword) ++ " " ++ snippet.value))
    })
}

object SnippetsCommand extends CommandPlugin[SnippetsCommand] {
  final case class Snippet(keyword: String, value: String)

  object Snippet {
    implicit val decoder: Decoder[Snippet] = Decoder.forProduct2("keyword", "value")(Snippet.apply)
  }

  def make(config: Config): IO[CommandPluginError, SnippetsCommand] =
    for {
      commandNames <- config.getZIO[Option[List[String]]]("commandNames")
      snippets     <- config.getZIO[Option[List[Snippet]]]("snippets")
    } yield SnippetsCommand(
      commandNames.getOrElse(List("snippets", "snippet", "snip")),
      snippets.getOrElse(Nil)
    )
}
