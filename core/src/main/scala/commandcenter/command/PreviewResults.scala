package commandcenter.command

import commandcenter.CCRuntime.Env
import zio.*
import zio.stream.ZStream

sealed trait PreviewResults[+A]

object PreviewResults {

  def one[A](result: PreviewResult[A]): PreviewResults[A] =
    PreviewResults.Single(result)

  def multiple[A](result: PreviewResult[A], resultsRest: PreviewResult[A]*): PreviewResults[A] =
    PreviewResults.Multiple(NonEmptyChunk(result, resultsRest*))

  def fromIterable[A](results: Iterable[PreviewResult[A]]): PreviewResults[A] =
    PreviewResults.Multiple(Chunk.fromIterable(results))

  def paginated[A](
      stream: ZStream[Env, CommandError, PreviewResult[A]],
      initialPageSize: Int,
      morePageSize: Int,
      totalRemaining: Option[Long] = None
  ): PreviewResults.Paginated[A] =
    PreviewResults.Paginated(stream, initialPageSize, morePageSize, totalRemaining)

  final case class Single[A](result: PreviewResult[A]) extends PreviewResults[A]

  final case class Multiple[A](results: Chunk[PreviewResult[A]]) extends PreviewResults[A]

  final case class Paginated[A](
      results: ZStream[Env, CommandError, PreviewResult[A]],
      initialPageSize: Int,
      morePageSize: Int,
      totalRemaining: Option[Long]
  ) extends PreviewResults[A] {

    def moreMessage: String =
      totalRemaining match {
        case Some(remaining) => s"Load $morePageSize of $remaining more..."
        case None            => "More..."
      }

  }

  object Paginated {

    def fromIterable[A](
        results: Iterable[PreviewResult[A]],
        initialPageSize: Int,
        morePageSize: Int,
        totalRemaining: Option[Long] = None
    ): Paginated[A] =
      PreviewResults.Paginated(ZStream.fromIterable(results), initialPageSize, morePageSize, totalRemaining)
  }
}
