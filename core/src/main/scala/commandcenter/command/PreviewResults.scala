package commandcenter.command

import commandcenter.CCRuntime.Env
import zio.Chunk
import zio.stream.ZStream

sealed trait PreviewResults[+A]

object PreviewResults {
  def one[A](result: PreviewResult[A]): PreviewResults[A] =
    PreviewResults.Single(result)

  def fromIterable[A](results: Iterable[PreviewResult[A]]): PreviewResults[A] =
    PreviewResults.Multiple(Chunk.fromIterable(results))

  def paginated[A](
    stream: ZStream[Env, CommandError, PreviewResult[A]],
    pageSize: Int,
    totalRemaining: Option[Long] = None
  ): PreviewResults[A] =
    PreviewResults.Paginated(stream, pageSize, totalRemaining)

  final case class Single[A](result: PreviewResult[A]) extends PreviewResults[A]

  final case class Multiple[A](results: Chunk[PreviewResult[A]]) extends PreviewResults[A]

  final case class Paginated[A](
    results: ZStream[Env, CommandError, PreviewResult[A]],
    pageSize: Int,
    totalRemaining: Option[Long]
  ) extends PreviewResults[A]
}
