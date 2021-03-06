package commandcenter.emulator.swing.ui

import commandcenter.CCRuntime.Env
import commandcenter._
import commandcenter.command._
import commandcenter.emulator.swing.event.KeyboardShortcutUtil
import commandcenter.emulator.util.Lists
import commandcenter.locale.Language
import commandcenter.tools.Tools
import commandcenter.ui.CCTheme
import commandcenter.util.{ Debouncer, OS }
import commandcenter.view.{ Rendered, Style }
import zio._
import zio.blocking.Blocking
import zio.stream.ZSink

import java.awt._
import java.awt.event.KeyEvent
import javax.swing._
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.{ DefaultStyledDocument, SimpleAttributeSet, StyleConstants, StyleContext }

final case class SwingTerminal(
  commandCursorRef: Ref[Int],
  searchResultsRef: Ref[SearchResults[Any]],
  searchDebouncer: Debouncer[Env, Nothing, Unit],
  closePromise: Promise[Nothing, Unit]
)(implicit runtime: Runtime[Env])
    extends GuiTerminal {
  val terminalType: TerminalType = TerminalType.Swing

  val theme         = CCTheme.default
  val document      = new DefaultStyledDocument
  val context       = new StyleContext
  val frame         = new JFrame("Command Center")
  val preferredFont = runtime.unsafeRun(getPreferredFont)

  frame.setBackground(theme.background)
  frame.setFocusable(false)
  frame.setUndecorated(true)
  frame.getContentPane.setLayout(new BorderLayout())
  frame.getRootPane.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, CCTheme.default.darkGray))

  val inputTextField = new ZTextField
  inputTextField.setFont(preferredFont)
  inputTextField.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10))
  inputTextField.setBackground(theme.background)
  inputTextField.setForeground(theme.foreground)
  inputTextField.setCaretColor(theme.foreground)
  // Enable ability to detect Tab key presses
  inputTextField.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, java.util.Collections.emptySet())
  frame.getContentPane.add(inputTextField, BorderLayout.NORTH)

  val outputTextPane = new JTextPane(document)
  outputTextPane.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 10))

  outputTextPane.setBackground(theme.background)
  outputTextPane.setForeground(theme.foreground)
//  outputTextPane.setCaretColor(Color.RED) // TODO: Make caret color configurable
  outputTextPane.setEditable(false)

  val outputScrollPane = new JScrollPane(
    outputTextPane,
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  ) {
    override def getPreferredSize: Dimension =
      runtime.unsafeRun {
        for {
          config              <- Conf.config
          preferredFrameWidth <- getPreferredFrameWidth
          searchResults       <- searchResultsRef.get
          height               = if (searchResults.previews.isEmpty) 0
                                 else
                                   outputTextPane.getPreferredSize.height min config.display.maxHeight
        } yield new Dimension(preferredFrameWidth, height)
      }
  }
  outputScrollPane.setBorder(BorderFactory.createEmptyBorder())

  outputScrollPane.getVerticalScrollBar.setUI(new BasicScrollBarUI() {
    val emptyButton: JButton = {
      val button = new JButton()
      button.setPreferredSize(new Dimension(0, 0))
      button.setMinimumSize(new Dimension(0, 0))
      button.setMaximumSize(new Dimension(0, 0))
      button
    }

    override def createDecreaseButton(orientation: Int): JButton = emptyButton
    override def createIncreaseButton(orientation: Int): JButton = emptyButton

    override protected def configureScrollBarColors(): Unit = {
      thumbColor = new Color(50, 50, 50)
      thumbDarkShadowColor = new Color(30, 30, 30)
      thumbHighlightColor = new Color(90, 90, 90)
      thumbLightShadowColor = new Color(70, 70, 70)
      trackColor = Color.BLACK
      trackHighlightColor = Color.LIGHT_GRAY
    }
  })

  frame.getContentPane.add(outputScrollPane, BorderLayout.CENTER)

  inputTextField.addOnChangeListener { e =>
    val searchTerm = inputTextField.getText
    val context    = CommandContext(Language.detect(searchTerm), SwingTerminal.this, 1.0)

    for {
      config <- Conf.config
      _      <- searchDebouncer(
                  Command
                    .search(config.commands, config.aliases, searchTerm, context)
                    .tap(r => commandCursorRef.set(0) *> searchResultsRef.set(r) *> render(r))
                    .unit
                ).flatMap(_.join)
    } yield ()
  }

  def init: RIO[Env, Unit] =
    for {
      opacity <- Conf.get(_.display.opacity)
      _       <- setOpacity(opacity)
    } yield ()

  private def render(searchResults: SearchResults[Any]): UIO[Unit] =
    for {
      commandCursor <- commandCursorRef.get
    } yield
      if (frame.isVisible) { // If the frame isn't visible, trying to insert into the document will throw an exception
        SwingUtilities.invokeLater { () =>
          def colorMask(width: Int): Long = ~0L >>> (64 - width)

          document.remove(0, document.getLength)

          var scrollToPosition: Int = 0

          def renderBar(rowIndex: Int): Unit = {
            val barStyle = new SimpleAttributeSet()

            if (rowIndex == commandCursor)
              StyleConstants.setBackground(barStyle, CCTheme.default.green)
            else
              StyleConstants.setBackground(barStyle, CCTheme.default.darkGray)

            document.insertString(document.getLength, " ", barStyle)
            document.insertString(document.getLength, " ", null)
          }

          searchResults.rendered.zipWithIndex.foreach { case (r, row) =>
            r match {
              case Rendered.Styled(segments) =>
                renderBar(row)

                segments.foreach { styledText =>
                  val style = new SimpleAttributeSet()

                  styledText.styles.foreach {
                    case Style.Bold                   => StyleConstants.setBold(style, true)
                    case Style.Underline              => StyleConstants.setUnderline(style, true)
                    case Style.Italic                 => StyleConstants.setItalic(style, true)
                    case Style.ForegroundColor(color) => StyleConstants.setForeground(style, color)
                    case Style.BackgroundColor(color) => StyleConstants.setForeground(style, color)
                    case Style.FontFamily(fontFamily) => StyleConstants.setFontFamily(style, fontFamily)
                    case Style.FontSize(fontSize)     => StyleConstants.setFontSize(style, fontSize)
                  }

                  if (row < commandCursor)
                    scrollToPosition += styledText.text.length + 3

                  document.insertString(document.getLength, styledText.text, style)
                }

                if (row < searchResults.rendered.length - 1)
                  document.insertString(document.getLength, "\n", null)

              case ar: Rendered.Ansi =>
                renderBar(row)

                if (row < commandCursor)
                  scrollToPosition += ar.ansiStr.length + 3

                val renderStr = if (row < searchResults.rendered.length - 1) ar.ansiStr ++ "\n" else ar.ansiStr

                var i: Int = 0
                Lists.groupConsecutive(renderStr.getColors.toList).foreach { c =>
                  val s = renderStr.plainText.substring(i, i + c.length)

                  i += c.length

                  val ansiForeground = (c.head >>> fansi.Color.offset) & colorMask(fansi.Color.width)
                  val ansiBackground = (c.head >>> fansi.Back.offset) & colorMask(fansi.Back.width)

                  val awtForegroundOpt = CCTheme.default.fromFansiColorCode(ansiForeground.toInt)
                  val awtBackgroundOpt = CCTheme.default.fromFansiColorCode(ansiBackground.toInt)

                  val style = (awtForegroundOpt, awtBackgroundOpt) match {
                    case (None, None) =>
                      // Don't bother wastefully creating a StyleRange object
                      null

                    case _ =>
                      val style = new SimpleAttributeSet()

                      awtForegroundOpt.foreach(StyleConstants.setForeground(style, _))
                      awtBackgroundOpt.foreach(StyleConstants.setBackground(style, _))

                      style
                  }

                  document.insertString(document.getLength, s, style)
                }

            }
          }

          outputTextPane.setCaretPosition(scrollToPosition)

          frame.pack()
        }
      } else {
        SwingUtilities.invokeLater { () =>
          document.remove(0, document.getLength)
          frame.pack()
        }
      }

  def reset: UIO[Unit] =
    for {
      _ <- commandCursorRef.set(0)
      _ <- UIO {
             inputTextField.setText("")
             document.remove(0, document.getLength)
           }
      _ <- searchResultsRef.set(SearchResults.empty)
    } yield ()

  def runSelected(results: SearchResults[Any], cursorIndex: Int): RIO[Env, Option[PreviewResult[Any]]] =
    results.previews.lift(cursorIndex) match {
      case None =>
        for {
          _ <- hide
          _ <- deactivate.ignore
        } yield None

      case previewOpt @ Some(preview) =>
        for {
          _ <- (hide *> deactivate.ignore).when(preview.runOption != RunOption.RemainOpen)
          _ <- preview.moreResults match {
                 case MoreResults.Remaining(p @ PreviewResults.Paginated(rs, pageSize, totalRemaining))
                     if totalRemaining.forall(_ > 0) =>
                   for {
                     _                     <- preview.onRun.absorb.forkDaemon
                     (results, restStream) <- rs.peel(ZSink.take(pageSize)).useNow.mapError(_.toThrowable)
                     _                     <- showMore(
                                                results,
                                                preview.moreResults(
                                                  MoreResults.Remaining(
                                                    p.copy(
                                                      results = restStream,
                                                      totalRemaining = p.totalRemaining.map(_ - pageSize)
                                                    )
                                                  )
                                                ),
                                                pageSize
                                              )
                   } yield ()

                 case _ =>
                   // TODO: Log defects
                   preview.onRun.absorb.forkDaemon *> reset
               }
        } yield previewOpt
    }

  def showMore[A](
    moreResults: Chunk[PreviewResult[A]],
    previewSource: PreviewResult[A],
    pageSize: Int
  ): RIO[Env, Unit] =
    for {
      cursorIndex   <- commandCursorRef.get
      searchResults <- searchResultsRef.updateAndGet { results =>
                         val (front, back) = results.previews.splitAt(cursorIndex)

                         val previews = if (moreResults.length < pageSize) {
                           front ++ moreResults ++ back.tail
                         } else {
                           front ++ moreResults ++ Chunk.single(previewSource) ++ back.tail
                         }

                         results.copy(previews = previews)
                       }
      _             <- render(searchResults)
    } yield ()

  inputTextField.addZKeyListener(new ZKeyAdapter {
    override def keyPressed(e: KeyEvent): URIO[Env, Unit] =
      e.getKeyCode match {
        case KeyEvent.VK_ENTER =>
          for {
            _               <- searchDebouncer.triggerNowAwait
            previousResults <- searchResultsRef.get
            cursorIndex     <- commandCursorRef.get
            resultOpt       <- runSelected(previousResults, cursorIndex).catchAll(_ => UIO.none)
            _               <- ZIO.whenCase(resultOpt.map(_.runOption)) { case Some(RunOption.Exit) =>
                                 closePromise.succeed(())
                               }
          } yield ()

        case KeyEvent.VK_ESCAPE =>
          for {
            _ <- hide
            _ <- deactivate.ignore
            _ <- reset
          } yield ()

        case KeyEvent.VK_DOWN =>
          e.consume() // Not ideal to have it outside the for-comprehension, but wrapping this in UIO will not work

          for {
            previousResults <- searchResultsRef.get
            previousCursor  <-
              commandCursorRef.getAndUpdate(cursor => (cursor + 1) min (previousResults.previews.length - 1))
            // TODO: Add `renderSelectionCursor` optimization here too (refer to SwtTerminal)
            _               <- render(previousResults).when(previousCursor < previousResults.previews.length - 1)
          } yield ()

        case KeyEvent.VK_UP =>
          e.consume() // Not ideal to have it outside the for-comprehension, but wrapping this in UIO will not work

          for {
            previousResults <- searchResultsRef.get
            previousCursor  <- commandCursorRef.getAndUpdate(cursor => (cursor - 1) max 0)
            // TODO: Add `renderSelectionCursor` optimization here too (refer to SwtTerminal)
            _               <- render(previousResults).when(previousCursor > 0)
          } yield ()

        case _ =>
          for {
            previousResults <- searchResultsRef.get
            shortcutPressed  = KeyboardShortcutUtil.fromKeyEvent(e)
            eligibleResults  = previousResults.previews.filter { p =>
                                 p.shortcuts.contains(shortcutPressed)
                               }
            bestMatch        = eligibleResults.maxByOption(_.score)
            _               <- ZIO.foreach_(bestMatch) { preview =>
                                 for {
                                   _ <- hide
                                   _ <- preview.onRun.absorb.forkDaemon // TODO: Log defects
                                   _ <- reset
                                 } yield ()
                               }
          } yield ()
      }
  })

  frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
  frame.setMinimumSize(new Dimension(runtime.unsafeRun(getPreferredFrameWidth), 20))
  frame.pack()

  def clearScreen: UIO[Unit] =
    UIO {
      document.remove(0, document.getLength)
    }

  def open: Task[Unit] =
    Task {
      val bounds =
        GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice.getDefaultConfiguration.getBounds

      val x = (bounds.width - frame.getWidth) / 2

      frame.setLocation(x, 0)
      frame.setVisible(true)

    }

  def hide: UIO[Unit] = UIO {
    frame.setVisible(false)
  }

  def activate: RIO[Has[Tools] with Blocking, Unit] =
    OS.os match {
      case OS.MacOS => Tools.activate
      case _        =>
        UIO {
          frame.toFront()
          frame.requestFocus()
          inputTextField.requestFocusInWindow()
        }
    }

  def deactivate: RIO[Has[Tools] with Blocking, Unit] =
    OS.os match {
      case OS.MacOS => Tools.hide
      case _        => UIO.unit
    }

  def opacity: RIO[Env, Float] = UIO(frame.getOpacity)

  def setOpacity(opacity: Float): RIO[Env, Unit] = Task(frame.setOpacity(opacity)).whenM(isOpacitySupported)

  def isOpacitySupported: URIO[Env, Boolean] =
    Task(
      GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice
        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)
    ).orElseSucceed(false)

  def size: RIO[Env, Dimension] = UIO(frame.getSize)

  def setSize(width: Int, maxHeight: Int): RIO[Env, Unit] = ZIO.unit

  def reload: RIO[Env, Unit] =
    for {
      config <- Conf.reload
      _      <- setOpacity(config.display.opacity)
      _      <- Task {
                  inputTextField.setFont(preferredFont)
                  outputTextPane.setFont(preferredFont)
                }
    } yield ()

  def getPreferredFont: URIO[Has[Conf], Font] = {
    def fallbackFont = new Font("Monospaced", Font.PLAIN, 18)

    (for {
      fonts              <- Conf.get(_.display.fonts)
      installedFontNames <- Task(GraphicsEnvironment.getLocalGraphicsEnvironment.getAvailableFontFamilyNames.toSet)
    } yield fonts.find(f => installedFontNames.contains(f.getName)).getOrElse(fallbackFont)).orElse(UIO(fallbackFont))
  }

  def getPreferredFrameWidth: URIO[Has[Conf], Int] =
    for {
      width       <- Conf.get(_.display.width)
      screenWidth <-
        Task(
          GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice.getDefaultConfiguration.getBounds.width
        ).orElse(UIO(width))
    } yield width min screenWidth
}

object SwingTerminal {
  def create(runtime: CCRuntime): RManaged[Env, SwingTerminal] =
    for {
      debounceDelay    <- Conf.get(_.general.debounceDelay).toManaged_
      searchDebouncer  <- Debouncer.make[Env, Nothing, Unit](debounceDelay).toManaged_
      commandCursorRef <- Ref.makeManaged(0)
      searchResultsRef <- Ref.makeManaged(SearchResults.empty[Any])
      closePromise     <- Promise.makeManaged[Nothing, Unit]
      swingTerminal    <-
        ZManaged.make(
          UIO(new SwingTerminal(commandCursorRef, searchResultsRef, searchDebouncer, closePromise)(runtime))
        )(t => UIO(t.frame.dispose()))
      _                <- swingTerminal.init.toManaged_
    } yield swingTerminal
}
