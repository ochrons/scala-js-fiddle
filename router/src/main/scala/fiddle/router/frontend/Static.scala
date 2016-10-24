package fiddle.router.frontend

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import akka.util.ByteString
import fiddle.router.Config
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Stream
import scala.util.Try
import scalatags.Text.all._
import scalatags.Text.svgAttrs.xLinkHref
import scalatags.Text.svgTags.{svg, use}
import scalatags.Text.tags2

object Static {
  val log = LoggerFactory.getLogger(getClass)

  val extJSFiles = Seq(
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/ace.js",
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/ext-language_tools.js",
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/ext-static_highlight.js",
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/mode-scala.js",
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/theme-eclipse.js",
    s"/META-INF/resources/webjars/ace/${Config.aceVersion}/src-min/theme-tomorrow_night.js",
    s"/web/gzip.js"
  )

  val cssFiles = Seq(
    "/META-INF/resources/webjars/normalize.css/2.1.3/normalize.css",
    "/common.css"
  )

  case class Button(id: String, value: String, title: String)

  val buttons = Seq(
    Button("run", "Run", "Ctrl/Cmd-Enter to run,\nShift-Ctrl/Cmd-Enter to run optimized"),
    Button("reset", "Reset", "Reset back to original source"),
    Button("font-increase", "+A", "Increase font size"),
    Button("font-decrease", "-A", "Decrease font size")
  )

  // store concatenated and hashed resource blobs
  val cache = TrieMap.empty[Seq[String], (String, Array[Byte])]

  final val layoutRE = """([vh])(\d\d)""".r

  def renderPage(srcFiles: Seq[String], paramMap: Map[String, String]): Array[Byte] = {
    // apply layout parameters
    val responsiveWidth = Try(paramMap.getOrElse("responsiveWidth", "640").toInt).getOrElse(640)
    val customStyle = paramMap.getOrElse("style", "")
    val (themeCSS, logoSrc) = paramMap.get("theme") match {
      case Some("dark") => ("/styles-dark.css", Config.logoDark)
      case _ => ("/styles-light.css", Config.logoLight)
    }
    val fullOpt = paramMap.contains("fullOpt")
    val allJS = joinResources(extJSFiles ++ srcFiles, ".js", ";\n")
    val allCSS = joinResources(cssFiles :+ themeCSS, ".css", "\n")
    val jsURLs = s"cache/$allJS" +: Config.extJS
    val cssURLs = s"cache/$allCSS" +: Config.extCSS

    // convert baseEnv to JS string variable
    val baseEnv =
    s"""var baseEnv = ${Config.baseEnv.split('\n').map(l => s"""'$l\\n'""").mkString(" +\n")};"""

    // parse which buttons to hide
    val toHide = paramMap.get("hideButtons").map(_.split(',')).getOrElse(Array.empty)
    val visibleButtons: Seq[Modifier] = buttons.filterNot(b => toHide.contains(b.id)).map { case Button(bId, bValue, bTitle) =>
      div(title := bTitle, id := s"$bId-icon", cls := "icon")(
        svg(width := 21, height := 21)(use(xLinkHref := s"#sym_$bId")), span(cls := "button", bValue)
      )
    }
    val (direction, ratio) = paramMap.getOrElse("layout", "h50") match {
      case layoutRE(d, r) => (d, r.toInt min 85 max 15)
      case _ => ("h", 50)
    }
    val editorSize = ratio.toInt
    val outputSize = 100 - editorSize
    val commonLayout =
      s"""
         |#output{$customStyle}
         |.ace_editor{$customStyle}
        """.stripMargin
    val vertCSS =
      s"""
         |.main {
         |    flex-direction: column;
         |}
         |#editorWrap {
         |    flex: 0 0 $editorSize%;
         |}
         |#sandbox {
         |    flex: 0 0 $outputSize%;
         |    border-top-width: 1px;
         |}
         """.stripMargin
    val layout = direction match {
      case "h" =>
        s"""
           |#editorWrap {
           |    flex: 0 0 $editorSize%;
           |}
           |#sandbox {
           |    flex: 0 0 $outputSize%;
           |    border-left-width: 1px;
           |}
           |@media only screen and (max-width: ${responsiveWidth}px) {
           |$vertCSS
           |}
           """.stripMargin + commonLayout
      case "v" =>
        vertCSS + commonLayout
    }
    val pageHtml = "<!DOCTYPE html>" + html(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width, initial-scale=1"),
        meta(name := "author", content := "Otto Chrons and Li Haoyi"),
        tags2.title("ScalaFiddle"),
        for (jsURL <- jsURLs) yield script(`type` := "application/javascript", src := jsURL),
        for (cssURL <- cssURLs) yield link(rel := "stylesheet", href := cssURL),
        scalatags.Text.tags2.style(raw(layout))
      ),
      body(
        raw(
          """
            |<svg xmlns="http://www.w3.org/2000/svg">
            | <symbol id="sym_help" viewBox="0 0 24 24">
            |   <g>
            |     <circle cx=12 cy=12 r=12 fill="white" fill-opacity="0"/>
            |  		<path id="circle" style="fill-rule:evenodd;clip-rule:evenodd;" d="M12.001,2.085c-5.478,0-9.916,4.438-9.916,9.916
            |    c0,5.476,4.438,9.914,9.916,9.914c5.476,0,9.914-4.438,9.914-9.914C21.915,6.523,17.477,2.085,12.001,2.085z M12.002,20.085
            |    c-4.465,0-8.084-3.619-8.084-8.083c0-4.465,3.619-8.084,8.084-8.084c4.464,0,8.083,3.619,8.083,8.084
            |    C20.085,16.466,16.466,20.085,12.002,20.085z"/>
            |  		<g>
            |  			<path style="fill-rule:evenodd;clip-rule:evenodd;" d="M11.766,6.688c-2.5,0-3.219,2.188-3.219,2.188l1.411,0.854
            |     c0,0,0.298-0.791,0.901-1.229c0.516-0.375,1.625-0.625,2.219,0.125c0.701,0.885-0.17,1.587-1.078,2.719
            |     C11.047,12.531,11,15,11,15h1.969c0,0,0.135-2.318,1.041-3.381c0.603-0.707,1.443-1.338,1.443-2.494S14.266,6.688,11.766,6.688z"/>
            |  			<rect x="11" y="16" style="fill-rule:evenodd;clip-rule:evenodd;" width="2" height="2"/>
            |  		</g>
            |  	</g>
            | </symbol>
            | <symbol id="sym_run" viewBox="0 0 21 21">
            |   <polygon points="3,1 18,10 3,19"/>
            | </symbol>
            | <symbol id="sym_reset" viewBox="0 0 500 500">
            |   <circle cx=250 cy=250 r=250 fill="white" fill-opacity="0"/>
            |   <path d= "M492.1,213.8h-62.2C412.6,111.5,323.6,33.5,216.5,33.5C97,33.5,0.1,130.5,0.1,250S97,
            |            466.5,216.5,466.5 c54.5,0,104.3-20.2,142.3-53.4L314,356.2c-25.7,23.6-59.9,38.1-97.4,
            |            38.1c-79.6,0-144.3-64.7-144.3-144.3s64.7-144.3,144.3-144.3 c67.1,0,123.4,46,139.5,
            |            108.1h-63c-7.9,0-10.3,5.1-5.3,11.2l95.8,117.9c5,6.2,13.1,6.2,18.2,0L497.6,225 C502.4,
            |            218.9,500,213.8,492.1,213.8z"/>
            | </symbol>
            | <symbol id="sym_upload" viewBox="0 0 16 16">
            |  <path d="M7 9H5l3-3 3 3H9v5H7V9z m5-4c0-0.44-0.91-3-4.5-3-2.42 0-4.5 1.92-4.5 4C1.02 6 0 7.52 0 9
            |  c0 1.53 1 3 3 3 0.44 0 2.66 0 3 0v-1.3H3C1.38 10.7 1.3 9.28 1.3 9c0-0.17 0.05-1.7 1.7-1.7h1.3v-1.3
            |  c0-1.39 1.56-2.7 3.2-2.7 2.55 0 3.13 1.55 3.2 1.8v1.2h1.3c0.81 0 2.7 0.22 2.7 2.2 0 2.09-2.25 2.2-2.7 2.2
            |  H10v1.3c0.38 0 1.98 0 2 0 2.08 0 4-1.16 4-3.5 0-2.44-1.92-3.5-4-3.5z" />
            | </symbol>
            | <symbol id="sym_share" viewBox="0 0 64 64">
            |   <path d="M48,39.26c-2.377,0-4.515,1-6.033,2.596L24.23,33.172c0.061-0.408,0.103-0.821,0.103-1.246c0-0.414-0.04-0.818-0.098-1.215
            |     l17.711-8.589c1.519,1.609,3.667,2.619,6.054,2.619c4.602,0,8.333-3.731,8.333-8.333c0-4.603-3.731-8.333-8.333-8.333
            |     s-8.333,3.73-8.333,8.333c0,0.414,0.04,0.817,0.098,1.215l-17.711,8.589c-1.519-1.609-3.666-2.619-6.054-2.619
            |     c-4.603,0-8.333,3.731-8.333,8.333c0,4.603,3.73,8.333,8.333,8.333c2.377,0,4.515-1,6.033-2.596l17.737,8.684
            |     c-0.061,0.407-0.103,0.821-0.103,1.246c0,4.603,3.731,8.333,8.333,8.333s8.333-3.73,8.333-8.333C56.333,42.99,52.602,39.26,48,39.26  z"/>
            | </symbol>
            |</svg>
            |</svg>
          """.stripMargin),

        div(cls := "top")(
          header(
            div(cls := "left")(
              visibleButtons,
              div(id := "fiddleSelectorDiv", style := "display: none")(
                select(id := "fiddleSelector")
              )
            ),
            div(cls := "right")(
              div(cls := "logo")(
                a(href := "", id := "editLink", target := "_blank", img(src := logoSrc))
              )
            )
          ),
          div(cls := "main")(
            div(id := "editorWrap")(
              div(id := "editor")
            ),
            div(id := "sandbox")(
              iframe(
                id := "codeframe",
                width := "100%",
                height := "100%",
                attr("frameborder") := "0",
                attr("sandbox") := "allow-scripts",
                src := s"codeframe?theme=${paramMap.getOrElse("theme", "light")}")
            )
          )
        )
      ),
      script(`type` := "text/javascript", raw(
        if (Config.analyticsID.nonEmpty)
          s"""
             |(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
             |(i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
             |m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
             |})(window,document,'script','//www.google-analytics.com/analytics.js','ga');
             |ga('create', '${Config.analyticsID}', 'auto');
             |ga('send', 'pageview');
             |""".stripMargin
        else "")),
      script(`type` := "text/javascript", raw(baseEnv)),
      script(`type` := "text/javascript", raw(s"""Client().main($fullOpt, "${Config.scalaFiddleSourceUrl}", "${Config.scalaFiddleEditUrl}", baseEnv)"""))
    ).toString()
    pageHtml.getBytes(StandardCharsets.UTF_8)
  }

  def renderCodeFrame(paramMap: Map[String, String]): Array[Byte] = {
    val themeCSS = paramMap.get("theme") match {
      case Some("dark") => "/styles-dark.css"
      case _ => "/styles-light.css"
    }
    val allCSS = joinResources(cssFiles :+ themeCSS, ".css", "\n")
    val cssURLs = s"cache/$allCSS" +: Config.extCSS

    val pageHtml = "<!DOCTYPE html>" + html(
      head(
        meta(charset := "UTF-8"),
        meta(name := "robots", content := "noindex"),
        for (cssURL <- cssURLs) yield link(rel := "stylesheet", href := cssURL)
      ),
      body(
        div(id := "container", style := "height: 100%; width: 100%")(
          div(cls := "label")(span(id := "output-tag", "Output")),
          canvas(id := "canvas", style := "position: absolute"),
          div(id := "output"),
          script(`type` := "text/javascript", raw(
            """
              |var label = document.getElementById("output-tag");
              |var canvas = document.getElementById("canvas");
              |var panel = document.getElementById("output");
              |var container = document.getElementById("container");
              |window.addEventListener('message', function (e) {
              |  var mainWindow = e.source;
              |  var msg = e.data;
              |  switch(msg.cmd) {
              |    case "label":
              |      label.innerHTML = msg.data;
              |      break;
              |    case "clear":
              |      panel.innerHTML = "";
              |      canvas.height = container.clientHeight;
              |      canvas.width = container.clientWidth;
              |      canvas.getContext("2d").clearRect(0, 0, 10000, 10000);
              |      break;
              |    case "print":
              |      panel.innerHTML = msg.data;
              |      break;
              |    case "code":
              |      try {
              |        eval(msg.data);
              |        eval("var sf = ScalaFiddle();if(typeof sf.main === 'function') sf.main();");
              |      } catch(ex) {
              |        panel.insertAdjacentHTML('beforeend', '<pre class="error">ERROR: ' + ex.message + '\n' + ex.stack + '</pre>')
              |      } finally {
              |        e.source.postMessage("evalCompleted", "*");
              |      }
              |      break;
              |    case "fontIncrease":
              |      container.style.fontSize = msg.data;
              |      break;
              |    case "fontDecrease":
              |      container.style.fontSize = msg.data;
              |      break;
              |  }
              |});
            """.stripMargin)
          )
        )
      )
    ).toString()
    pageHtml.getBytes(StandardCharsets.UTF_8)
  }

  private def concatHash(resources: Seq[String], glueStr: String): (String, Array[Byte]) = {
    val hash = MessageDigest.getInstance("MD5")
    // files need a bit of glue between them to work properly in concatenated form
    val glue = glueStr.getBytes
    // read all resources and calculate both hash and concatenated string
    val data = resources.map { res =>
      log.debug(s"Loading reseource $res")
      val stream = getClass.getResourceAsStream(res)
      val data = Stream.continually(stream.read).takeWhile(_ != -1).map(_.toByte).toArray ++ glue
      hash.update(data)
      data
    }.reduceLeft(_ ++ _)
    (hash.digest().map("%02x".format(_)).mkString, data)
  }

  private def joinResources(resources: Seq[String], extension: String, glueStr: String): String = {
    cache.getOrElseUpdate(resources, concatHash(resources, glueStr))._1 + extension
  }

  def fetchResource(hash: String): Option[Array[Byte]] = {
    cache.values.find(_._1 == hash).map(_._2)
  }
}
