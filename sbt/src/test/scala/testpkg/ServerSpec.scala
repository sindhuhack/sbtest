/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package testpkg

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ForkJoinPool

import org.scalatest._
import sbt.RunFromSourceMain
import sbt.io.IO
import sbt.io.syntax._
import sbt.protocol.ClientSocket
import testpkg.TestServer.withTestServer

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, _ }
import scala.concurrent.duration._

class ServerSpec extends AsyncFreeSpec with Matchers {
  "server" - {
    "should start" in withTestServer("handshake") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": "3", "method": "sbt/setting", "params": { "setting": "root/name" } }"""
      )
      assert(p.waitForString(10) { s =>
        s contains """"id":"3""""
      })
    }

    "return number id when number id is sent" in withTestServer("handshake") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": 3, "method": "sbt/setting", "params": { "setting": "root/name" } }"""
      )
      assert(p.waitForString(10) { s =>
        s contains """"id":3"""
      })
    }

    "report task failures in case of exceptions" in withTestServer("events") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": 11, "method": "sbt/exec", "params": { "commandLine": "hello" } }"""
      )
      assert(p.waitForString(10) { s =>
        (s contains """"id":11""") && (s contains """"error":""")
      })
    }

    "return error if cancelling non-matched task id" in withTestServer("events") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id":12, "method": "sbt/exec", "params": { "commandLine": "run" } }"""
      )
      p.writeLine(
        """{ "jsonrpc": "2.0", "id":13, "method": "sbt/cancelRequest", "params": { "id": "55" } }"""
      )

      assert(p.waitForString(20) { s =>
        (s contains """"error":{"code":-32800""")
      })
    }

    "cancel on-going task with numeric id" in withTestServer("events") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id":12, "method": "sbt/exec", "params": { "commandLine": "run" } }"""
      )

      Thread.sleep(1000)

      p.writeLine(
        """{ "jsonrpc": "2.0", "id":13, "method": "sbt/cancelRequest", "params": { "id": "12" } }"""
      )

      assert(p.waitForString(30) { s =>
        s contains """"result":{"status":"Task cancelled""""
      })
    }

    "cancel on-going task with string id" in withTestServer("events") { p =>
      p.writeLine(
        """{ "jsonrpc": "2.0", "id": "foo", "method": "sbt/exec", "params": { "commandLine": "run" } }"""
      )

      Thread.sleep(1000)

      p.writeLine(
        """{ "jsonrpc": "2.0", "id": "bar", "method": "sbt/cancelRequest", "params": { "id": "foo" } }"""
      )

      assert(p.waitForString(30) { s =>
        s contains """"result":{"status":"Task cancelled""""
      })
    }
  }
}

object TestServer {
  // The test server instance will be executed in a Thread pool separated from the tests
  implicit val ec = ExecutionContext.fromExecutor(new ForkJoinPool())

  private val serverTestBase: File = new File(".").getAbsoluteFile / "sbt" / "src" / "server-test"

  def withTestServer(testBuild: String)(f: TestServer => Future[Assertion]): Future[Assertion] = {
    IO.withTemporaryDirectory { temp =>
      IO.copyDirectory(serverTestBase / testBuild, temp / testBuild)
      withTestServer(temp / testBuild)(f)
    }
  }

  def withTestServer(baseDirectory: File)(f: TestServer => Future[Assertion]): Future[Assertion] = {
    val testServer = TestServer(baseDirectory)
    try {
      f(testServer)
    } finally {
      try { testServer.bye() } finally {}
    }
  }

  def hostLog(s: String): Unit = {
    println(s"""[${scala.Console.MAGENTA}build-1${scala.Console.RESET}] $s""")
  }
}

case class TestServer(baseDirectory: File)(implicit ec: ExecutionContext) {
  import TestServer.hostLog

  val readBuffer = new Array[Byte](4096)
  var buffer: Vector[Byte] = Vector.empty
  var bytesRead = 0
  private val delimiter: Byte = '\n'.toByte
  private val RetByte = '\r'.toByte

  hostLog("fork to a new sbt instance")
  val process =
    Future {
      RunFromSourceMain.fork(baseDirectory)
    }

  lazy val portfile = baseDirectory / "project" / "target" / "active.json"

  val timeout = 3.minutes // Long timeout for CI builds
  hostLog(s"wait up to $timeout until the server is ready to respond")
  def waitForPortfile(): Unit =
    if (portfile.exists) ()
    else {
      val base = Files.createDirectories(portfile.toPath.getParent).toRealPath()
      val resolved = base.resolve(portfile.getName) // On mac we need to resolve /var to /private/var
      val repo = sbt.io.FileTreeRepository.default(_.getPath)
      repo.register(resolved, -1)
      val monitor = sbt.io.FileEventMonitor(repo, logger = _ => ())
      try {
        if (!portfile.exists &&
            !monitor
              .poll(timeout)
              .map(_.entry.typedPath)
              .exists(tp => tp.exists && tp.getPath == resolved))
          sys.error(s"Timeout. $portfile is not found.")
      } finally {
        println(s"Finished waiting for $resolved")
        Thread.sleep(1000) // Add sleep to ensure the server is really ready.
        monitor.close()
      }
    }
  waitForPortfile()

  // make connection to the socket described in the portfile
  val (sk, tkn) = ClientSocket.socket(portfile)
  val out = sk.getOutputStream
  val in = sk.getInputStream

  // initiate handshake
  sendJsonRpc(
    """{ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "initializationOptions": { } } }"""
  )

  def test(f: TestServer => Future[Assertion]): Future[Assertion] = {
    f(this)
  }

  def bye(): Unit = {
    hostLog("sending exit")
    sendJsonRpc(
      """{ "jsonrpc": "2.0", "id": 9, "method": "sbt/exec", "params": { "commandLine": "exit" } }"""
    )
    for {
      p <- process
    } {
      p.destroy()
    }
  }

  def sendJsonRpc(message: String): Unit = {
    writeLine(s"""Content-Length: ${message.size + 2}""")
    writeLine("")
    writeLine(message)
  }

  def writeLine(s: String): Unit = {
    def writeEndLine(): Unit = {
      val retByte: Byte = '\r'.toByte
      val delimiter: Byte = '\n'.toByte
      out.write(retByte.toInt)
      out.write(delimiter.toInt)
      out.flush
    }

    if (s != "") {
      out.write(s.getBytes("UTF-8"))
    }
    writeEndLine
  }

  def readFrame: Option[String] = {
    def getContentLength: Int = {
      readLine map { line =>
        line.drop(16).toInt
      } getOrElse (0)
    }

    val l = getContentLength
    readLine
    readLine
    readContentLength(l)
  }

  @tailrec
  final def waitForString(num: Int)(f: String => Boolean): Boolean = {
    if (num < 0) false
    else
      readFrame match {
        case Some(x) if f(x) => true
        case _ =>
          waitForString(num - 1)(f)
      }
  }

  def readLine: Option[String] = {
    if (buffer.isEmpty && (in.available > 0)) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    val delimPos = buffer.indexOf(delimiter)
    if (delimPos > 0) {
      val chunk0 = buffer.take(delimPos)
      buffer = buffer.drop(delimPos + 1)
      // remove \r at the end of line.
      val chunk1 = if (chunk0.lastOption contains RetByte) chunk0.dropRight(1) else chunk0
      Some(new String(chunk1.toArray, "utf-8"))
    } else None // no EOL yet, so skip this turn.
  }

  def readContentLength(length: Int): Option[String] = {
    if (buffer.isEmpty && (in.available > 0)) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    if (length <= buffer.size) {
      val chunk = buffer.take(length)
      buffer = buffer.drop(length)
      Some(new String(chunk.toArray, "utf-8"))
    } else None // have not read enough yet, so skip this turn.
  }

}
