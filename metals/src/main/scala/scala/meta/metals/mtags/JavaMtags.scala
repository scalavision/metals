package scala.meta.metals.mtags

import java.io.StringReader
import scala.meta.internal.semanticdb3.SymbolInformation.Property
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model._
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.internal.semanticdb3.Language

// TODO, emit correct method overload symbols https://github.com/scalameta/metals/issues/281
object JavaMtags {
  private implicit class XtensionJavaModel(val m: JavaModel) extends AnyVal {
    def lineNumber: Int = m.getLineNumber - 1
  }
  def index(input: Input.VirtualFile): MtagsIndexer = {
    val builder = new JavaProjectBuilder()
    new MtagsIndexer { self =>
      override def indexRoot(): Unit = {
        try {
          val source = builder.addSource(new StringReader(input.value))
          if (source.getPackage != null) {
            source.getPackageName.split("\\.").foreach { p =>
              term(
                p,
                toRangePosition(source.getPackage.lineNumber, p),
                Kind.PACKAGE,
                0
              )
            }
          }
          source.getClasses.forEach(visitClass)
        } catch {
          case _: NullPointerException => ()
          // Hitting on this fellow here indexing the JDK
          // Error indexing file:///Library/Java/JavaVirtualMachines/jdk1.8.0_102.jdk/Contents/Home/src.zip/java/time/temporal/IsoFields.java
          // java.lang.NullPointerException: null
          // at com.thoughtworks.qdox.builder.impl.ModelBuilder.createTypeVariable(ModelBuilder.java:503)
          // at com.thoughtworks.qdox.builder.impl.ModelBuilder.endMethod(ModelBuilder.java:470)
          // TODO(olafur) report bug to qdox.
        }
      }

      /** Computes the start/end offsets from a name in a line number.
       *
       * Applies a simple heuristic to find the name: the first occurence of
       * name in that line. If the name does not appear in the line then
       * 0 is returned. If the name appears for example in the return type
       * of a method then we get the position of the return type, not the
       * end of the world.
       */
      def toRangePosition(line: Int, name: String): Position = {
        val offset = input.toOffset(line, 0)
        val column = {
          val fromIndex = {
            // HACK(olafur) avoid hitting on substrings of "package".
            if (input.value.startsWith("package", offset)) "package".length
            else offset
          }
          val idx = input.value.indexOf(name, fromIndex)
          if (idx == -1) 0
          else idx - offset
        }
        val pos = input.toPosition(line, column, line, column + name.length)
        pos
      }

      def visitFields[T <: JavaMember](fields: java.util.List[T]): Unit =
        if (fields == null) ()
        else fields.forEach(visitMember)

      def visitClasses(classes: java.util.List[JavaClass]): Unit =
        if (classes == null) ()
        else classes.forEach(visitClass)

      def visitClass(cls: JavaClass): Unit =
        withOwner(owner) {
          val kind = if (cls.isInterface) Kind.INTERFACE else Kind.CLASS
          val pos = toRangePosition(cls.lineNumber, cls.getName)
          tpe(
            cls.getName,
            pos,
            kind,
            if (cls.isEnum) Property.ENUM.value else 0
          )
          visitClasses(cls.getNestedClasses)
          visitFields(cls.getMethods)
          visitFields(cls.getFields)
        }

      private def getDisambiguator(params: java.util.List[JavaParameter]): String = {

        def extractType(s: String)= {
          if(s.contains("int")) "Int"
          else if(s.contains("String")) "String"
          else if(s.contains("long")) "Long"
          else if(s.contains("char")) "Char"
          else if(s.contains("boolean")) "Boolean"
          else if(s.contains("double")) "Double"
          else if(s.contains("float")) "Float"
          else if(s.contains("short")) "Short"
          else s
        }

        if(params.isEmpty) "()"
        else {
          val sb = new StringBuilder()
          params.forEach { param =>
            val t = extractType(param.getType().getValue)
            if(sb.length > 1) {
              sb.append(",")
            }
            sb.append(t)
          }
          "(" + sb.append(")").mkString
        }

      }

      def visitMember[T <: JavaMember](m: T): Unit =
        withOwner(owner) {
          val name = m.getName
          val line = m match {
            case c: JavaMethod => c.lineNumber
            case c: JavaField => c.lineNumber
            // TODO(olafur) handle constructos
            case _ => 0
          }
          val methodParams = m match {
            case c: JavaMethod => c.getParameters
          }
          val pos = toRangePosition(line, name)
          val kind: Kind = m match {
            case _: JavaMethod => Kind.METHOD
            case _: JavaField => Kind.FIELD
            case c: JavaClass =>
              if (c.isInterface) Kind.INTERFACE
              else Kind.CLASS
            case _ => Kind.UNKNOWN_KIND
          }
          method(name, getDisambiguator(methodParams), pos, kind, 0)
        }
      override def language: Language = Language.JAVA
    }
  }

}
