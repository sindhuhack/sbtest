/* sbt -- Simple Build Tool
 * Copyright 2009, 2010  Mark Harrah
 */
package sbt
package compiler

	import xsbti.{AnalysisCallback, Logger => xLogger, Reporter}
	import java.io.File
	import java.net.{URL, URLClassLoader}

/** Interface to the Scala compiler that uses the dependency analysis plugin.  This class uses the Scala library and compiler
* provided by scalaInstance.  This class requires a ComponentManager in order to obtain the interface code to scalac and
* the analysis plugin.  Because these call Scala code for a different Scala version than the one used for this class, they must
* be compiled for the version of Scala being used.*/
class AnalyzingCompiler(val scalaInstance: xsbti.compile.ScalaInstance, val provider: CompilerInterfaceProvider, val cp: xsbti.compile.ClasspathOptions, log: Logger)
{
	def this(scalaInstance: ScalaInstance, provider: CompilerInterfaceProvider, log: Logger) = this(scalaInstance, provider, ClasspathOptions.auto, log)
	def apply(sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String], callback: AnalysisCallback, maximumErrors: Int, log: Logger)
	{
		val arguments = (new CompilerArguments(scalaInstance, cp))(sources, classpath, outputDirectory, options)
		compile(arguments, callback, maximumErrors, log)
	}

	def compile(arguments: Seq[String], callback: AnalysisCallback, maximumErrors: Int, log: Logger): Unit =
		compile(arguments, callback, log, new LoggerReporter(maximumErrors, log))
	def compile(arguments: Seq[String], callback: AnalysisCallback, log: Logger, reporter: Reporter)
	{
		call("xsbt.CompilerInterface", log)(
			classOf[Array[String]], classOf[AnalysisCallback], classOf[xLogger], classOf[Reporter] ) (
			arguments.toArray[String] : Array[String], callback, log, reporter )
	}

	def doc(sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String], maximumErrors: Int, log: Logger): Unit =
		doc(sources, classpath, outputDirectory, options, log, new LoggerReporter(maximumErrors, log))
	def doc(sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String], log: Logger, reporter: Reporter): Unit =
	{
		val arguments = (new CompilerArguments(scalaInstance, cp))(sources, classpath, outputDirectory, options)
		call("xsbt.ScaladocInterface", log) (classOf[Array[String]], classOf[xLogger], classOf[Reporter]) (
			arguments.toArray[String] : Array[String], log, reporter)
	}
	def console(classpath: Seq[File], options: Seq[String], initialCommands: String, cleanupCommands: String, log: Logger)(loader: Option[ClassLoader] = None, bindings: Seq[(String, Any)] = Nil): Unit =
	{
		val arguments = new CompilerArguments(scalaInstance, cp)
		val classpathString = CompilerArguments.absString(arguments.finishClasspath(classpath))
		val bootClasspath = if(cp.autoBoot) arguments.createBootClasspath else ""
		val (names, values) = bindings.unzip
		call("xsbt.ConsoleInterface", log)(
			classOf[Array[String]], classOf[String], classOf[String], classOf[String], classOf[String], classOf[ClassLoader], classOf[Array[String]], classOf[Array[Any]], classOf[xLogger])(
			options.toArray[String]: Array[String], bootClasspath, classpathString, initialCommands, cleanupCommands, loader.orNull, names.toArray[String], values.toArray[Any], log)
	}
	def force(log: Logger): Unit = provider(scalaInstance, log)
	private def call(interfaceClassName: String, log: Logger)(argTypes: Class[_]*)(args: AnyRef*)
	{
		val interfaceClass = getInterfaceClass(interfaceClassName, log)
		val interface = interfaceClass.newInstance.asInstanceOf[AnyRef]
		val method = interfaceClass.getMethod("run", argTypes : _*)
		try { method.invoke(interface, args: _*) }
		catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
	}
	private[this] def loader =
	{
		val interfaceJar = provider(scalaInstance, log)
		// this goes to scalaInstance.loader for scala classes and the loader of this class for xsbti classes
		val dual = createDualLoader(scalaInstance.loader, getClass.getClassLoader)
		new URLClassLoader(Array(interfaceJar.toURI.toURL), dual)
	}
	private def getInterfaceClass(name: String, log: Logger) = Class.forName(name, true, loader)
	protected def createDualLoader(scalaLoader: ClassLoader, sbtLoader: ClassLoader): ClassLoader =
	{
		val xsbtiFilter = (name: String) => name.startsWith("xsbti.")
		val notXsbtiFilter = (name: String) => !xsbtiFilter(name)
		new classpath.DualLoader(scalaLoader, notXsbtiFilter, x => true, sbtLoader, xsbtiFilter, x => false)
	}
	override def toString = "Analyzing compiler (Scala " + scalaInstance.actualVersion + ")"
}
object AnalyzingCompiler
{
	import sbt.IO.{copy, createDirectory, zip, jars, unzip, withTemporaryDirectory}

	/** Extract sources from source jars, compile them with the xsbti interfaces on the classpath, and package the compiled classes and
	* any resources from the source jars into a final jar.*/
	def compileSources(sourceJars: Iterable[File], targetJar: File, xsbtiJars: Iterable[File], id: String, compiler: RawCompiler, log: Logger)
	{
		val isSource = (f: File) => isSourceName(f.getName)
		def keepIfSource(files: Set[File]): Set[File] = if(files.exists(isSource)) files else Set()

		withTemporaryDirectory { dir =>
			val extractedSources = (Set[File]() /: sourceJars) { (extracted, sourceJar)=> extracted ++ keepIfSource(unzip(sourceJar, dir)) }
			val (sourceFiles, resources) = extractedSources.partition(isSource)
			withTemporaryDirectory { outputDirectory =>
				log.info("'" + id + "' not yet compiled for Scala " + compiler.scalaInstance.actualVersion + ". Compiling...")
				val start = System.currentTimeMillis
				try
				{
					compiler(sourceFiles.toSeq, xsbtiJars.toSeq ++ sourceJars, outputDirectory, "-nowarn" :: Nil)
					log.info("  Compilation completed in " + (System.currentTimeMillis - start) / 1000.0 + " s")
				}
				catch { case e: xsbti.CompileFailed => throw new CompileFailed(e.arguments, "Error compiling sbt component '" + id + "'") }
				import sbt.Path._
				copy(resources x rebase(dir, outputDirectory))
				zip((outputDirectory ***) x_! relativeTo(outputDirectory), targetJar)
			}
		}
	}
	private def isSourceName(name: String): Boolean = name.endsWith(".scala") || name.endsWith(".java")
}