package commandcenter

import commandcenter.command.*
import commandcenter.CCRuntime.Env
import zio.*
import zio.test.*

import java.time.Instant

object SearchSpec extends CommandBaseSpec {

  val defectCommand: Command[Unit] = new Command[Unit] {
    val commandType: CommandType = CommandType.ExitCommand

    def commandNames: List[String] = List("exit")

    def title: String = "Exit"

    def preview(searchInput: SearchInput): ZIO[Env, CommandError, PreviewResults[Unit]] =
      ZIO.dieMessage("This command is intentionally broken!")
  }

  def spec: Spec[TestEnvironment & Env, Any] =
    suite("SearchSpec")(
      test("defect in one command should not fail entire search") {
        val commands = Vector(defectCommand, EpochMillisCommand(List("epochmillis")))
        val results = Command.search(commands, Map.empty, "e", defaultCommandContext)
        val time = Instant.now()

        for {
          _        <- TestClock.setTime(time)
          previews <- results.map(_.previews)
        } yield assertTrue(previews.head.asInstanceOf[PreviewResult.Some[Any]].result == time.toEpochMilli.toString)
      }
    )
}
