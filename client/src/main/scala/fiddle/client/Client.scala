package fiddle.client

import java.net.URLDecoder
import java.util.UUID

import fiddle.shared.{CompilationResponse, CompilerMessage, CompilerResponse, CompletionResponse}
import fiddle.{client, _}
import org.scalajs.dom
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw._
import upickle.default._

import scala.async.Async.{async, await}
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSName}
import scala.scalajs.js.timers.{SetIntervalHandle, SetTimeoutHandle}
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.niocharset.StandardCharsets
import scala.util.Success

@JSName("Zlib.Gzip")
@js.native
class Gzip(data: js.Array[Byte]) extends js.Object {
  def compress(): Uint8Array = js.native
}

case class SourceFile(name: String, code: String, prefix: List[String] = Nil, postfix: List[String] = Nil, indent: Int = 0,
  fiddleId: Option[String] = None, id: String = UUID.randomUUID().toString)

class Client(editURL: String) {
  implicit val RedLogger = client.Client.RedLogger
  var sourceFiles = Seq(SourceFile("ScalaFiddle.scala", ""))
  var currentSource = sourceFiles.head.id
  var currentSourceName = sourceFiles.head.name

  def currentSourceFile = sourceFiles.find(_.id == currentSource).get

  val command = Channel[Future[CompilationResponse]]()

  val runIcon = dom.document.getElementById("run-icon").asInstanceOf[HTMLElement]
  val resetIcon = dom.document.getElementById("reset-icon").asInstanceOf[HTMLElement]
  def codeFrame = dom.document.getElementById("codeframe").asInstanceOf[HTMLIFrameElement]
  val fiddleSelectorDiv = dom.document.getElementById("fiddleSelectorDiv").asInstanceOf[HTMLElement]
  val fiddleSelector = dom.document.getElementById("fiddleSelector").asInstanceOf[HTMLSelectElement]
  val editLink = dom.document.getElementById("editLink").asInstanceOf[HTMLAnchorElement]
  val fontIncreaseIcon = dom.document.getElementById("font-increase-icon").asInstanceOf[HTMLElement]
  val fontDecreaseIcon = dom.document.getElementById("font-decrease-icon").asInstanceOf[HTMLElement]
  val aceEditor = dom.document.querySelector(".ace_editor").asInstanceOf[HTMLElement]

  val (minFontSize, maxFontSize, stepSize) = (0.5, 2.5, 0.1)
  var fontSize = 1.0

  def exec(s: String) = {
    Client.clear()

    showStatus("RUNNING")
    js.timers.setTimeout(20) {
      Client.sendFrameCmd("code", s)
    }
  }

  val compilationLoop = task * async {
    val future = await(command())
    await(compile(future)).foreach(exec)

    while (true) {
      val future = await(command())

      val compiled = await(compile(future))
      compiled.foreach(exec)
    }
  }

  val editor: Editor = new Editor(Seq(
    ("Compile", "Enter", () => fastOpt),
    ("FullOptimize", "Shift-Enter", () => fullOpt),
    ("Complete", "Space", () => editor.complete()),
    ("Parse", "Alt-Enter", parseFull _)
  ), complete, RedLogger)

  def beginCompilation(): Future[Unit] = {
    // fully clear the code iframe by reloading it
    val p = Promise[Unit]
    codeFrame.onload = (e: Event) => {
      runIcon.classList.add("active")
      showStatus("COMPILING")
      p.complete(Success(()))
    }
    codeFrame.src = codeFrame.src
    p.future
  }

  def endCompilation(): Unit = {
    runIcon.classList.remove("active")
    showStatus("RESULT")
    editor.focus()
  }

  def showError(errStr: String): Unit = {
    import scalatags.JsDom.all._
    showStatus("Errors")
    Client.clear()
    Client.printOutput(pre(cls := "error", errStr))
  }

  def reconstructSource(source: String, srcFile: SourceFile): String = {
    val indented = source.split("\n").map(l => (" " * srcFile.indent) + l).map(_ + "\n").mkString
    // use map instead of mkString to prevent an empty list from generating a single line
    srcFile.prefix.map(_ + "\n").mkString + indented + "\n" + srcFile.postfix.map(_ + "\n").mkString
  }

  def encodeSource(source: String): String = {
    import com.github.marklister.base64.Base64._

    import js.JSConverters._
    implicit def scheme: B64Scheme = base64Url
    val fullSource = source.getBytes(StandardCharsets.UTF_8)
    val compressedBuffer = new Gzip(fullSource.toJSArray).compress()
    val compressedSource = new Array[Byte](compressedBuffer.length)
    var i = 0
    while (i < compressedBuffer.length) {
      compressedSource(i) = compressedBuffer.get(i).toByte
      i += 1
    }
    Encoder(compressedSource).toBase64
  }

  def compileServer(code: String, opt: String): Future[CompilationResponse] = {
    val tag = s"${if (Client.initializing) "initial-" else ""}$opt"
    val startTime = System.nanoTime()
    Ajax.get(
      url = s"compile?opt=$opt&source=${encodeSource(reconstructSource(code, currentSourceFile))}"
    ).map { res =>
      val compileTime = (System.nanoTime() - startTime) / 1000000
      EventTracker.sendEvent("compile", tag, currentSourceName, compileTime)
      read[CompilationResponse](res.responseText)
    } recover {
      case e: dom.ext.AjaxException =>
        showError(s"Error: ${e.xhr.responseText}")
        throw e
      case e: Throwable =>
        showError(e.toString)
        throw e
    }
  }

  def fullOpt = {
    beginCompilation().map(_ => command.update(compileServer(editor.code, "full")))
  }

  def fastOpt = {
    beginCompilation().map(_ => command.update(compileServer(editor.code, "fast")))
  }

  def parseFull() = {
    import scalatags.JsDom.all._
    Client.clear()
    Client.printOutput(h2("Full source code"), pre(reconstructSource(editor.code, currentSourceFile)))
  }

  // attach handlers to icons
  if (runIcon != null) {
    runIcon.onclick = (e: MouseEvent) => {
      if (e.shiftKey)
        fullOpt
      else
        fastOpt
    }
  }

  if (resetIcon != null) {
    resetIcon.onclick = (e: MouseEvent) => {
      EventTracker.sendEvent("reset", "reset", currentSourceName)
      selectSource(currentSource)
      editor.focus()
    }
  }


  if (fontIncreaseIcon != null) {
    fontIncreaseIcon.onclick = (e: MouseEvent) => {
      fontSize = Math.min(fontSize + stepSize, maxFontSize)
      val style = s"${fontSize}em"
      aceEditor.style.fontSize = style
      Client.sendFrameCmd("fontIncrease", style)
    }
  }

  if (fontDecreaseIcon != null) {
    fontDecreaseIcon.onclick = (e: MouseEvent) => {
      fontSize = Math.max(fontSize - stepSize, minFontSize)
      val style = s"${fontSize}em"
      aceEditor.style.fontSize = style
      Client.sendFrameCmd("fontDecrease", style)
    }
  }

  fiddleSelector.onchange = (e: Event) => {
    val sel = fiddleSelector.options(fiddleSelector.selectedIndex).value
    dom.console.log(s"Fiddle selected $sel")
    updateSource(editor.code)
    selectSource(sel)
  }

  def showStatus(status: String) = {
    Client.sendFrameCmd("label", status)
  }

  val fiddleStart = """\s*// \$FiddleStart\s*$""".r
  val fiddleEnd = """\s*// \$FiddleEnd\s*$""".r

  // separate source code into pre,main,post blocks
  def extractCode(src: SourceFile): SourceFile = {
    val lines = src.code.split('\n')
    val (pre, main, post) = lines.foldLeft((List.empty[String], List.empty[String], List.empty[String])) {
      case ((preList, mainList, postList), line) => line match {
        case fiddleStart() =>
          (line :: mainList ::: preList, Nil, Nil)
        case fiddleEnd() =>
          (preList, mainList, line :: postList)
        case l if postList.nonEmpty =>
          (preList, mainList, line :: postList)
        case _ =>
          (preList, line :: mainList, postList)
      }
    }
    // remove indentation from main part
    val indent = main.filter(_.nonEmpty).map(_.takeWhile(_ == ' ').length) match {
      case l if l.nonEmpty => l.min
      case _ => 0
    }
    src.copy(code = main.reverse.map(_.drop(indent)).mkString("", "\n", "\n"), prefix = pre.reverse, postfix = post.reverse, indent = indent)
  }

  def setSources(sources: Seq[SourceFile]): Unit = {
    import scalatags.JsDom.all._

    sourceFiles = sources.map(extractCode)
    fiddleSelector.innerHTML = ""
    sourceFiles.foreach { source =>
      fiddleSelector.add(option(value := source.id)(source.name).render)
    }
    if (sourceFiles.size > 1) {
      fiddleSelectorDiv.style.display = "inherit"
    } else {
      fiddleSelectorDiv.style.display = "none"
    }
    selectSource(sourceFiles.head.id)
  }

  def selectSource(id: String): Unit = {
    sourceFiles.find(_.id == id).foreach { src =>
      currentSource = src.id
      currentSourceName = src.name
      editor.sess.setValue(src.code)
      editLink.href = src.fiddleId match {
        case Some(fiddleId) => editURL + "sf/" + fiddleId
        case None => editURL
      }
    }
  }

  def updateSource(code: String): Unit = {
    sourceFiles = sourceFiles.collect {
      case sf: SourceFile if sf.id == currentSource => sf.copy(code = code)
      case sf => sf
    }
  }

  def compile(res: Future[CompilationResponse]): Future[Option[String]] = {
    res.map { response =>
      endCompilation()
      val prefixLines = currentSourceFile.prefix.size
      editor.setAnnotations(response.annotations.map(a => a.copy(row = a.row - prefixLines)))
      if (response.jsCode.isEmpty) {
        // show compiler errors in output
        val allErrors = response.annotations.map { ann =>
          s"ScalaFiddle.scala:${ann.row + 1 - prefixLines}: ${ann.tpe}: ${ann.text.mkString("\n")}"
        }.mkString("\n")
        showError(allErrors)
      }
      response.jsCode
    }.recover { case e: Exception =>
      endCompilation()
      Client.logError(e.toString)
      None
    }
  }

  def complete(): Future[CompletionResponse] = async {
    val code = reconstructSource(editor.code, currentSourceFile)
    val row = editor.row + currentSourceFile.prefix.size
    val col = editor.column + currentSourceFile.indent
    val intOffset = col + code.split("\n")
      .take(row)
      .map(_.length + 1)
      .sum

    val startTime = System.nanoTime()

    val f = Ajax.get(
      url = s"complete?offset=$intOffset&source=${encodeSource(code)}"
    ).map { res =>
      val completeTime = (System.nanoTime() - startTime) / 1000000
      EventTracker.sendEvent("complete", "complete", currentSourceName, completeTime)
      read[CompletionResponse](res.responseText)
    } recover {
      case e: dom.ext.AjaxException =>
        showError(s"Error: ${e.xhr.responseText}")
        throw e
      case e: Throwable =>
        showError(e.toString)
        throw e
    }

    val res = await(f)
    res
  }
}

@JSExport("Client")
object Client {
  implicit val RedLogger = new Logger(logError)

  var intervalHandles = List.empty[SetIntervalHandle]
  var timeoutHandles = List.empty[SetTimeoutHandle]

  dom.window.onerror = { (event: dom.Event, source: String, fileno: Int, columnNumber: Int) =>
    dom.console.log("dom.onerror")
    Client.logError(event.toString)
  }

  // listen to messages from the iframe
  dom.window.addEventListener("message", (e: MessageEvent) => {
    sendFrameCmd("label", "RESULT")
  })

  def parseUriParameters(search: String): Map[String, String] = {
    search.drop(1).split('&').filter(_.nonEmpty).map { part =>
      val pair = part.split("=")
      val key = URLDecoder.decode(pair(0), "UTF-8")
      val value = if (pair.length > 1) URLDecoder.decode(pair(1), "UTF-8") else ""
      key -> value
    }.toMap
  }

  var initializing = true
  val queryParams = parseUriParameters(dom.window.location.search)
  val previewMode = queryParams.contains("preview")
  val envId = queryParams.getOrElse("env", "default")
  val codeFrame = dom.document.getElementById("codeframe").asInstanceOf[HTMLIFrameElement]

  dom.console.log(s"queryParams: $queryParams")

  def logError(s: String): Unit = {
    dom.console.error(s)
  }

  def sendFrameCmd(cmd: String, data: String = "") = {
    val msg = js.Dynamic.literal(cmd = cmd, data = data)
    codeFrame.contentWindow.postMessage(msg, "*")
  }

  def clear() = {
    dom.console.log(s"Clearing ${timeoutHandles.size} timeouts and ${intervalHandles.size} intervals")
    // clear all timers
    timeoutHandles.foreach(js.timers.clearTimeout)
    timeoutHandles = Nil
    intervalHandles.foreach(js.timers.clearInterval)
    intervalHandles = Nil
    sendFrameCmd("clear")
  }

  val subFiddleRE = """// \$SubFiddle (\w.+)""".r
  def parseFiddles(srcCode: String): Seq[SourceFile] = {
    val lines = srcCode.split('\n').toSeq.map(_.replaceAll("[\\n\\r]", ""))
    val (prevSources, lastSource) = lines.foldLeft((List.empty[SourceFile], SourceFile("ScalaFiddle.scala", ""))) { case ((acc, src), line) =>
      line match {
        case subFiddleRE(name) =>
          val newSrc = SourceFile(name.trim, "")
          (src :: acc, newSrc)
        case _ =>
          (acc, src.copy(code = src.code + line + "\n"))
      }
    }
    (lastSource :: prevSources).filter(_.code.replaceAll("\\s", "").nonEmpty).reverse
  }

  def defaultCode(id: String) =
    s"""println("There was an error loading fiddle with identifier '$id'")
        |println("Loading an empty application so you can get started")
    """.stripMargin

  @JSExport
  def main(useFull: Boolean, scalaFiddleSourceUrl: String, scalaFiddleEditUrl: String, baseEnv: String): Unit = task * async {
    clear()
    Editor.initEditor
    val client = new Client(scalaFiddleEditUrl)
    if (queryParams.contains("sfid")) {
      val ids = queryParams("sfid").split(",").toList
      val sources = await(Future.sequence(ids.map { id =>
        loadSource(scalaFiddleSourceUrl, id) recover {
          case e => SourceFile("ScalaFiddle.scala", baseEnv).copy(code = defaultCode(id))
        }
      }))
      client.setSources(sources)
      if (useFull)
        client.fullOpt
      else
        client.fastOpt
    } else if (queryParams.contains("source")) {
      val srcCode = queryParams("source")
      client.showStatus("Loading")
      // check for sub-fiddles
      val sources = await(Future(parseFiddles(srcCode)))
      client.setSources(sources)
      if (useFull)
        client.fullOpt
      else
        client.fastOpt
    } else {
      client.setSources(Seq(SourceFile("ScalaFiddle.scala", baseEnv)))
    }
    initializing = false
  }

  val fiddleName = """\s*// \$FiddleName\s+(.+)$""".r

  def parseName(source: String): Option[String] = {
    source.split("\n").collectFirst {
      case fiddleName(name) => name
    }
  }

  def loadSource(url: String, id: String): Future[SourceFile] = {
    Ajax.get(url + id).map { res =>
      val source = res.responseText
      val name = parseName(source).getOrElse("ScalaFiddle")
      SourceFile(name, source, fiddleId = Some(id))
    }
  }

  def printOutput(ss: String) = {
    import scalatags.JsDom.all._
    val html = div(ss).toString
    Client.sendFrameCmd("print", html)
  }

  def printOutput(ss: scalatags.JsDom.all.Modifier*) = {
    import scalatags.JsDom.all._
    val html = div(ss).toString
    Client.sendFrameCmd("print", html)
  }
}
