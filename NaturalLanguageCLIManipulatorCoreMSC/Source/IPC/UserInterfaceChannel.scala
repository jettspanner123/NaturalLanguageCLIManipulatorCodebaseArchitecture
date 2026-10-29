package amy.ipc

import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.file.Paths

/** Owns the loopback TCP socket Core and the User Interface process talk
  * over, and the UI process itself. See ARCHITECTURE.md section 1 for why
  * this is a socket rather than stdio: the UI process needs its own real
  * stdin/stdout/stderr for terminal rendering and raw keystroke reading, so
  * IPC travels over this separate channel instead.
  */
final class UserInterfaceChannel private (uiProcess: Process, socket: Socket):
  private val reader = MessageFrameReader(socket.getInputStream)
  private val writer = MessageFrameWriter(socket.getOutputStream)

  def send(message: CoreToUserInterface): Unit =
    writer.writeFrame(message.toByteArray)

  def receive(): Option[UserInterfaceToCore] =
    reader.readFrame().map(UserInterfaceToCore.parseFrom)

  /** Closes the socket and waits for the User Interface process to exit on
    * its own. Closing the socket signals end-of-stream to the UI, which
    * finishes processing any already-sent frames before it exits - calling
    * Process.destroy() here instead would race ahead of that and could
    * kill the UI before it reads/renders the last message Core sent it.
    */
  def close(): Unit =
    socket.close()
    uiProcess.waitFor()

object UserInterfaceChannel:

  /** Starts the User Interface process and waits for it to connect back
    * over the loopback socket. The UI process inherits the real terminal's
    * stdin/stdout/stderr directly from Core.
    */
  def start(): UserInterfaceChannel =
    val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val port = serverSocket.getLocalPort

    val uiEntryPoint = Paths.get("..", "NaturalLanguageCLIManipulatorUserInterfaceMSC", "dist", "Main.js")

    val processBuilder = ProcessBuilder("node", uiEntryPoint.toString, port.toString)
    processBuilder.inheritIO()

    val uiProcess = processBuilder.start()
    val socket = serverSocket.accept()
    serverSocket.close()

    new UserInterfaceChannel(uiProcess, socket)
