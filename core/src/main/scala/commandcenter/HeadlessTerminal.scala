package commandcenter

import commandcenter.command.{Command, PreviewResult, SearchResults}
import commandcenter.locale.Language
import commandcenter.CCRuntime.Env
import zio.*

import java.awt.Dimension

final case class HeadlessTerminal(searchResultsRef: Ref[SearchResults[Any]]) extends CCTerminal {
  def terminalType: TerminalType = TerminalType.Test

  def opacity: RIO[Env, Float] = ZIO.succeed(1.0f)

  def setOpacity(opacity: Float): RIO[Env, Unit] = ZIO.unit

  def isOpacitySupported: URIO[Env, Boolean] = ZIO.succeed(false)

  def size: RIO[Env, Dimension] = ZIO.succeed(new Dimension(80, 40))

  def setSize(width: Int, height: Int): RIO[Env, Unit] = ZIO.unit

  def reload: RIO[Env, Unit] = ZIO.unit

  def reset: URIO[Env, Unit] = searchResultsRef.set(SearchResults.empty)

  def search(commands: Vector[Command[Any]], aliases: Map[String, List[String]])(
      searchTerm: String
  ): URIO[Env, SearchResults[Any]] = {
    val context = CommandContext(Language.detect(searchTerm), this, 1.0)

    Command
      .search(commands, aliases, searchTerm, context)
      .tap { r =>
        searchResultsRef.set(r)
      }
  }

  def run(cursorIndex: Int): URIO[Env, Option[PreviewResult[Any]]] =
    for {
      results <- searchResultsRef.get
      previewResult = results.previews.lift(cursorIndex)
      _ <- ZIO.foreachDiscard(previewResult) { preview =>
             preview.onRunSandboxedLogged.forkDaemon
           }
    } yield previewResult

  def showMore[A](
      moreResults: Chunk[PreviewResult[A]],
      previewSource: PreviewResult[A],
      pageSize: Int
  ): RIO[Env, Unit] =
    ZIO.unit
}

object HeadlessTerminal {

  def create: UIO[HeadlessTerminal] =
    for {
      searchResultsRef <- Ref.make(SearchResults.empty[Any])
    } yield HeadlessTerminal(searchResultsRef)
}
