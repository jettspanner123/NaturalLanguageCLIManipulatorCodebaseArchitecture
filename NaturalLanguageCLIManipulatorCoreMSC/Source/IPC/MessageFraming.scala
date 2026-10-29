package amy.ipc

import java.io.{DataInputStream, DataOutputStream, EOFException, InputStream, OutputStream}

/** Reads length-prefixed frames (4-byte big-endian length + payload) from
  * the loopback socket's input stream. See AmyIpc.proto for why framing is
  * needed on top of raw protobuf bytes.
  */
final class MessageFrameReader(in: InputStream):
  private val dataIn = DataInputStream(in)

  /** Blocks until a full frame is available. Returns None once the other
    * side has closed the connection.
    */
  def readFrame(): Option[Array[Byte]] =
    try
      val length = dataIn.readInt()
      val bytes = new Array[Byte](length)
      dataIn.readFully(bytes)
      Some(bytes)
    catch
      case _: EOFException => None

/** Writes length-prefixed frames (4-byte big-endian length + payload) to
  * the loopback socket's output stream.
  */
final class MessageFrameWriter(out: OutputStream):
  private val dataOut = DataOutputStream(out)

  def writeFrame(bytes: Array[Byte]): Unit =
    dataOut.writeInt(bytes.length)
    dataOut.write(bytes)
    dataOut.flush()
