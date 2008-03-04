/* NEST (New Scala Test)
 * Copyright 2007-2008 LAMP/EPFL
 * @author Philipp Haller
 */

// $Id$

package scala.tools.partest.nest

import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.{Reporter, ConsoleReporter}

import java.io.{File, BufferedReader, PrintWriter, FileWriter, StringWriter}

class ExtConsoleReporter(override val settings: Settings, reader: BufferedReader, var writer: PrintWriter) extends ConsoleReporter(settings, reader, writer) {
  def this(settings: Settings) = {
    this(settings, Console.in, new PrintWriter(new FileWriter("/dev/null")))
  }
  def hasWarnings: Boolean = WARNING.count != 0
}

abstract class SimpleCompiler {
  def compile(file: File, kind: String): Boolean
  def compile(file: File, kind: String, log: File): Boolean
}

class DirectCompiler(val fileManager: FileManager) extends SimpleCompiler {
  def newGlobal(settings: Settings, reporter: Reporter): Global =
    new Global(settings, reporter)

  def newGlobal(settings: Settings, log: File): Global = {
    val rep = new ExtConsoleReporter(new Settings(x => ()),
                                     Console.in,
                                     new PrintWriter(new FileWriter(log)))
    rep.shortname = true
    newGlobal(settings, rep)
  }

  def newSettings = {
    val settings = new Settings(x => ())
    settings.deprecation.value = true
    settings.nowarnings.value = false
    settings.encoding.value = "iso-8859-1"
    settings
  }

  def newReporter(sett: Settings) = new ExtConsoleReporter(sett,
                                                           Console.in,
                                                           new PrintWriter(new StringWriter))

  def compile(file: File, kind: String, log: File): Boolean = {
    val testSettings = newSettings
    val global = newGlobal(testSettings, log)
    val testRep: ExtConsoleReporter = global.reporter.asInstanceOf[ExtConsoleReporter]

    val test: TestFile = kind match {
      case "pos"      => PosTestFile(file, fileManager)
      case "neg"      => NegTestFile(file, fileManager)
      case "run"      => RunTestFile(file, fileManager)
      case "jvm"      => JvmTestFile(file, fileManager)
      case "jvm5"     => Jvm5TestFile(file, fileManager)
      case "shootout" => ShootoutTestFile(file, fileManager)
    }
    test.defineSettings(testSettings)

    val toCompile = List(file.getPath)
    try {
      (new global.Run) compile toCompile
      testRep.printSummary
      testRep.writer.flush
      testRep.writer.close
    } catch {
      case e: Exception =>
        e.printStackTrace()
        false
    }
    !testRep.hasErrors
  }

  def compile(file: File, kind: String): Boolean = {
    val testSettings = newSettings
    val testRep = newReporter(testSettings)
    val global = newGlobal(testSettings, testRep)

    val test: TestFile = kind match {
      case "pos"      => PosTestFile(file, fileManager)
      case "neg"      => NegTestFile(file, fileManager)
      case "run"      => RunTestFile(file, fileManager)
      case "jvm"      => JvmTestFile(file, fileManager)
      case "jvm5"     => Jvm5TestFile(file, fileManager)
      case "shootout" => ShootoutTestFile(file, fileManager)
    }
    test.defineSettings(testSettings)

    val toCompile = List(file.getPath)
    try {
      (new global.Run) compile toCompile
      testRep.printSummary
      testRep.writer.flush
      testRep.writer.close
    } catch {
      case e: Exception =>
        e.printStackTrace()
        false
    }
    !testRep.hasErrors
  }
}

class ReflectiveCompiler(val fileManager: ConsoleFileManager) extends SimpleCompiler {
  import fileManager.{latestCompFile, latestPartestFile, latestFjbgFile}

  val sepUrls = Array(latestCompFile.toURL, latestPartestFile.toURL,
                      latestFjbgFile.toURL)
  //NestUI.verbose("constructing URLClassLoader from URLs "+latestCompFile+" and "+latestPartestFile)

  val sepLoader = new java.net.URLClassLoader(sepUrls, null)

  val sepCompilerClass =
    sepLoader.loadClass("scala.tools.partest.nest.DirectCompiler")
  val sepCompiler = sepCompilerClass.newInstance()

  // needed for reflective invocation
  val fileClass = Class.forName("java.io.File")
  val stringClass = Class.forName("java.lang.String")
  val sepCompileMethod =
    sepCompilerClass.getMethod("compile", Array(fileClass, stringClass))
  val sepCompileMethod2 =
    sepCompilerClass.getMethod("compile", Array(fileClass, stringClass, fileClass))

  /* This method throws java.lang.reflect.InvocationTargetException
   * if the compiler crashes.
   * This exception is handled in the shouldCompile and shouldFailCompile
   * methods of class CompileManager.
   */
  def compile(file: File, kind: String): Boolean = {
    val fileArgs: Array[AnyRef] = Array(file, kind)
    val res = sepCompileMethod.invoke(sepCompiler, fileArgs).asInstanceOf[java.lang.Boolean]
    res.booleanValue()
  }

  /* This method throws java.lang.reflect.InvocationTargetException
   * if the compiler crashes.
   * This exception is handled in the shouldCompile and shouldFailCompile
   * methods of class CompileManager.
   */
  def compile(file: File, kind: String, log: File): Boolean = {
    val fileArgs: Array[AnyRef] = Array(file, kind, log)
    val res = sepCompileMethod2.invoke(sepCompiler, fileArgs).asInstanceOf[java.lang.Boolean]
    res.booleanValue()
  }
}

class CompileManager(val fileManager: FileManager) {
  var compiler: SimpleCompiler = new /*ReflectiveCompiler*/ DirectCompiler(fileManager)

  var numSeparateCompilers = 1
  def createSeparateCompiler() = {
    numSeparateCompilers += 1
    compiler = new /*ReflectiveCompiler*/ DirectCompiler(fileManager)
  }

  /* This method returns true iff compilation succeeds.
   */
  def shouldCompile(file: File, kind: String): Boolean = {
    createSeparateCompiler()

    try {
      compiler.compile(file, kind)
    } catch {
      case t: Throwable =>
        NestUI.verbose("while invoking compiler ("+file+"):")
        NestUI.verbose("caught "+t)
        t.printStackTrace
        t.getCause.printStackTrace
        false
    }
  }

  /* This method returns true iff compilation fails
   * _and_ the compiler does _not_ crash.
   *
   * If the compiler crashes, this method returns false.
   */
  def shouldFailCompile(file: File, kind: String, log: File): Boolean = {
    // always create new separate compiler
    createSeparateCompiler()

    try {
      // simulating compiler crash
      /*if (file.getName().endsWith("bug752.scala")) {
        NestUI.verbose("simulating compiler crash")
        throw new java.lang.reflect.InvocationTargetException(new Throwable)
      }*/

      !compiler.compile(file, kind, log)
    } catch {
      case t: Throwable =>
        NestUI.verbose("while invoking compiler ("+file+"):")
        NestUI.verbose("caught "+t)
        t.printStackTrace
        t.getCause.printStackTrace
        false
    }
  }
}