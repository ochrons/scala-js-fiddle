package fiddle.compiler

import fiddle.compiler.cache.{AutoCompleteCache, CompilerCache, LinkerCache}
import fiddle.shared.ExtLib
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker.Linker
import org.scalajs.core.tools.linker.backend.{ModuleKind, OutputMode}
import org.scalajs.core.tools.logging._
import org.scalajs.core.tools.sem.Semantics
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.reflect.internal.util.Position
import scala.reflect.io
import scala.tools.nsc
import scala.tools.nsc.Settings
import scala.tools.nsc.backend.JavaPlatform
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.util.ClassPath.JavaContext
import scala.tools.nsc.util._
import scala.util.Try

/**
  * Handles the interaction between scala-js-fiddle and
  * scalac/scalajs-tools to compile and optimize code submitted by users.
  */
class Compiler(libManager: LibraryManager, code: String) {
  self =>
  val log = LoggerFactory.getLogger(getClass)
  val sjsLogger = new Log4jLogger()
  val blacklist = Set("<init>")
  val dependencyRE = """ *// \$FiddleDependency (.+)""".r
  private val codeLines = code.replaceAll("\r", "").split('\n')
  val extLibDefs = codeLines.collect {
    case dependencyRE(dep) => dep
  }.toSet

  lazy val extLibs = {
    val userLibs = extLibDefs.map(lib => ExtLib(lib)).collect {
      case lib if libManager.depLibs.contains(lib) => lib
      case lib => throw new IllegalArgumentException(s"Library $lib is not allowed")
    }.toList

    log.debug(s"Full dependencies: $userLibs")
    userLibs.toSet
  }

  /**
    * Converts a bunch of bytes into Scalac's weird VirtualFile class
    */
  def makeFile(src: Array[Byte]) = {
    val singleFile = new io.VirtualFile("ScalaFiddle.scala")
    val output = singleFile.output
    output.write(src)
    output.close()
    singleFile
  }

  def inMemClassloader = {
    new ClassLoader(this.getClass.getClassLoader) {
      val classCache = mutable.Map.empty[String, Option[Class[_]]]
      val libs = libManager.compilerLibraries(extLibs)

      override def findClass(name: String): Class[_] = {

        def findClassInLibs(): Option[AbstractFile] = {
          val parts = name.split('.')
          libs.map(dir => {
            Try {
              parts.dropRight(1)
                .foldLeft[AbstractFile](dir)((parent, next) => parent.lookupName(next, directory = true))
                .lookupName(parts.last + ".class", directory = false)
            } getOrElse null
          }).find(_ != null)
        }

        val res = classCache.getOrElseUpdate(
          name,
          findClassInLibs().map { f =>
            val data = f.toByteArray
            this.defineClass(name, data, 0, data.length)
          }
        )
        res match {
          case None =>
            log.error("Not Found Class " + name)
            throw new ClassNotFoundException()
          case Some(cls) =>
            cls
        }
      }
    }
  }

  /**
    * Mixed in to make a Scala compiler run entirely in-memory,
    * loading its classpath and running macros from pre-loaded
    * in-memory files
    */
  trait InMemoryGlobal {
    g: scala.tools.nsc.Global =>
    def ctx: JavaContext
    def dirs: Vector[DirectoryClassPath]
    override lazy val plugins = List[Plugin](
      new org.scalajs.core.compiler.ScalaJSPlugin(this),
      new org.scalamacros.paradise.Plugin(this)
    )
    override lazy val platform: ThisPlatform = new JavaPlatform {
      val global: g.type = g
      override def classPath = new JavaClassPath(dirs, ctx)
    }
  }

  /**
    * Code to initialize random bits and pieces that are needed
    * for the Scala compiler to function, common between the
    * normal and presentation compiler
    */
  def initGlobalBits(logger: String => Unit) = {
    val vd = new io.VirtualDirectory("(memory)", None)
    val jCtx = new JavaContext()
    val jDirs = libManager.compilerLibraries(extLibs).map(new DirectoryClassPath(_, jCtx)).toVector
    lazy val settings = new Settings

    settings.outputDirs.setSingleOutput(vd)
    val reporter = new StoreReporter
    (settings, reporter, vd, jCtx, jDirs)
  }

  def autocomplete(pos: Int): List[(String, String)] = {
    import scala.tools.nsc.interactive._

    val startTime = System.nanoTime()
    // global can be reused, just create new runs for new compiler invocations
    val (settings, reporter, _, jCtx, jDirs) = initGlobalBits(_ => ())
    settings.processArgumentString("-Ypresentation-any-thread")
    val compiler = AutoCompleteCache.getOrUpdate(extLibs, new nsc.interactive.Global(settings, reporter) with InMemoryGlobal {
      g =>
      def ctx = jCtx
      def dirs = jDirs
      override lazy val analyzer = new {
        val global: g.type = g
      } with InteractiveAnalyzer {
        val cl = inMemClassloader
        override def findMacroClassLoader() = cl
      }
    })

    compiler.reporter.reset()
    val startOffset = pos
    val source = code.take(startOffset) + "_CURSOR_ " + code.drop(startOffset)
    val run = new compiler.TyperRun
    val unit = compiler.newCompilationUnit(source, "ScalaFiddle.scala")
    val richUnit = new compiler.RichCompilationUnit(unit.source)
    //log.debug(s"Source: ${source.take(startOffset)}${scala.Console.RED}|${scala.Console.RESET}${source.drop(startOffset)}")
    compiler.unitOfFile(richUnit.source.file) = richUnit
    val results = compiler.completionsAt(richUnit.position(startOffset)).matchingResults()

    val endTime = System.nanoTime()
    log.debug(s"AutoCompletion time: ${(endTime - startTime) / 1000} us")
    log.debug(s"AutoCompletion results: ${results.take(20)}")

    results.map(r => (r.sym.signatureString, r.symNameDropLocal.decoded)).distinct
  }

  def compile(logger: String => Unit = _ => ()): (String, Option[Seq[VirtualScalaJSIRFile]]) = {

    log.debug("Compiling source:\n" + code)
    val singleFile = makeFile(code.getBytes("UTF-8"))

    val startTime = System.nanoTime()
    val (settings, reporter, vd, jCtx, jDirs) = initGlobalBits(logger)
    val compiler = CompilerCache.getOrUpdate(extLibs, new nsc.Global(settings, reporter) with InMemoryGlobal {
      g =>
      def ctx = jCtx
      def dirs = jDirs
      override lazy val analyzer = new {
        val global: g.type = g
      } with Analyzer {
        val cl = inMemClassloader
        override def findMacroClassLoader() = cl
      }
    })

    compiler.reporter.reset()
    compiler.settings.outputDirs.setSingleOutput(vd)
    try {
      val run = new compiler.Run()
      run.compileFiles(List(singleFile))

      val endTime = System.nanoTime()
      log.debug(s"Compilation: ${(endTime - startTime) / 1000} us")
      // print errors
      val errors = compiler.reporter.asInstanceOf[StoreReporter].infos.map { info =>
        val label = info.severity.toString match {
          case "ERROR"   => "error: "
          case "WARNING" => "warning: "
          case "INFO"    => ""
        }
        Position.formatMessage(info.pos, label + info.msg, false)
      }.mkString("\n")
      if (vd.iterator.isEmpty) {
        (errors, None)
      } else {
        val things = for {
          x <- vd.iterator.to[collection.immutable.Traversable]
          if x.name.endsWith(".sjsir")
        } yield {
          val f = new MemVirtualSerializedScalaJSIRFile(x.path)
          f.content = x.toByteArray
          f: VirtualScalaJSIRFile
        }
        (errors, Some(things.toSeq))
      }
    } catch {
      case e: Throwable =>
        CompilerCache.remove(extLibs)
        throw e
    }
  }

  def export(output: VirtualJSFile): String =
    output.content

  def fastOpt(userFiles: Seq[VirtualScalaJSIRFile]): VirtualJSFile =
    link(userFiles, fullOpt = false)

  def fullOpt(userFiles: Seq[VirtualScalaJSIRFile]): VirtualJSFile =
    link(userFiles, fullOpt = true)

  def link(userFiles: Seq[VirtualScalaJSIRFile], fullOpt: Boolean): VirtualJSFile = {
    val semantics =
      if (fullOpt) Semantics.Defaults.optimized
      else Semantics.Defaults

    // add parameters as fake libraries to make caching work correctly
    val libs = extLibs + ExtLib("semantics", "optimized", fullOpt.toString, false)

    val output = WritableMemVirtualJSFile("output.js")
    try {
      val linker = LinkerCache.getOrUpdate(libs, Linker(
        semantics = semantics,
        outputMode = OutputMode.Default,
        moduleKind = ModuleKind.NoModule,
        config = Linker.Config().withSourceMap(false).withClosureCompiler(fullOpt)
      ))
      linker.link(libManager.linkerLibraries(extLibs) ++ userFiles, output, sjsLogger)
    } catch {
      case e: Throwable =>
        LinkerCache.remove(libs)
        throw e
    }
    output
  }

  def getLog = sjsLogger.logLines

  class Log4jLogger(minLevel: Level = Level.Debug) extends Logger {
    var logLines = Vector.empty[String]

    def log(level: Level, message: => String): Unit = if (level >= minLevel) {
      if (level == Level.Warn || level == Level.Error) {
        logLines :+= message
        self.log.error(message)
      } else {
        self.log.debug(message)
      }
    }
    def success(message: => String): Unit = info(message)
    def trace(t: => Throwable): Unit = {
      self.log.error("Compiling error", t)
    }
  }

}
