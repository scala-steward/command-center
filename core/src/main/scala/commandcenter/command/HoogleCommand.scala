package commandcenter.command

import com.typesafe.config.Config
import commandcenter.CCRuntime.Env
import commandcenter.command.HoogleCommand.HoogleResult
import commandcenter.util.ProcessUtil
import io.circe.{ Decoder, Json }
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.httpclient.zio._
import zio.{ IO, Managed, ZIO }

final case class HoogleCommand(commandNames: List[String]) extends Command[Unit] {
  val commandType: CommandType = CommandType.HoogleCommand
  val title: String            = "Hoogle"

  def preview(searchInput: SearchInput): ZIO[Env, CommandError, PreviewResults[Unit]] =
    for {
      input    <- ZIO.fromOption(searchInput.asPrefixed.filter(_.rest.nonEmpty)).orElseFail(CommandError.NotApplicable)
      request   = basicRequest
                    .get(uri"https://hoogle.haskell.org?mode=json&format=text&hoogle=${input.rest}&start=1&count=5")
                    .response(asJson[Json])
      response <- send(request)
                    .map(_.body)
                    .absolve
                    .mapError(CommandError.UnexpectedException)
      results  <- IO.fromEither(
                    response.as[List[HoogleResult]]
                  ).mapError(CommandError.UnexpectedException)
    } yield PreviewResults.fromIterable(results.map { result =>
      Preview.unit
        .onRun(ProcessUtil.openBrowser(result.url))
        .score(Scores.high(input.context))
        .renderedAnsi(
          fansi.Color.Magenta(result.item) ++ " " ++ fansi.Color.Yellow(result.module.name) ++ " " ++ fansi.Color
            .Cyan(result.`package`.name) ++ "\n" ++ result.docs
        )
    })
}

object HoogleCommand extends CommandPlugin[HoogleCommand] {
  final case class HoogleResult(
    url: String,
    item: String,
    docs: String,
    module: HoogleReference,
    `package`: HoogleReference
  )

  object HoogleResult {
    implicit val decoder: Decoder[HoogleResult] = Decoder.instance { c =>
      for {
        url       <- c.get[String]("url")
        item      <- c.get[String]("item")
        docs      <- c.get[String]("docs").map(sanitizeDocs)
        module    <- c.get[HoogleReference]("module")
        `package` <- c.get[HoogleReference]("package")
      } yield HoogleResult(url, item, docs, module, `package`)
    }

    private def sanitizeDocs(docs: String): String =
      docs.trim.replaceAll("[\n]{3,}", "\n\n")
  }

  final case class HoogleReference(name: String, url: String)

  object HoogleReference {
    implicit val decoder: Decoder[HoogleReference] = Decoder.forProduct2("name", "url")(HoogleReference.apply)
  }

  def make(config: Config): Managed[CommandPluginError, HoogleCommand] =
    for {
      commandNames <- config.getManaged[Option[List[String]]]("commandNames")
    } yield HoogleCommand(commandNames.getOrElse(List("hoogle", "h")))
}
