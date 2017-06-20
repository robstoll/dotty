package dotty.tools
package repl

import scala.annotation.tailrec

import dotc.reporting.MessageRendering
import dotc.reporting.diagnostic.MessageContainer
import dotc.ast.untpd
import dotc.ast.tpd
import dotc.core.Contexts.Context
import dotc.core.Flags._
import dotc.core.Denotations.Denotation
import dotc.core.NameKinds.SimpleNameKind
import dotc.core.Types.{ ExprType, ConstantType }
import dotc.config.CompilerCommand
import dotc.{ Compiler, Driver }
import dotc.printing.SyntaxHighlighting

import AmmoniteReader._
import results._

case class State(objectIndex: Int, valIndex: Int, history: History)

class Repl(settings: Array[String]) extends Driver {

  // FIXME: Change the Driver API to not require implementing this method
  override protected def newCompiler(implicit ctx: Context): Compiler =
    ???

  protected[this] def initializeCtx = {
    val rootCtx = initCtx.fresh
    val summary = CompilerCommand.distill(settings)(rootCtx)
    val ictx = rootCtx.setSettings(summary.sstate)
    ictx.base.initialize()(ictx)
    ictx
  }

  protected[this] var myCtx    = initializeCtx: Context
  protected[this] var compiler = new ReplCompiler(myCtx)

  private def readLine(history: History) =
    AmmoniteReader(history)(myCtx).prompt()

  @tailrec final def run(state: State = State(0, 0, Nil)): Unit =
    readLine(state.history) match {
      case (parsed: Parsed, history) =>
        val newState = compile(parsed, state)
        run(newState.copy(history = history))

      case (SyntaxErrors(errs, ctx), history) =>
        displayErrors(errs)(myCtx)
        run(state)

      case (Newline, history) =>
        run(state)

      case (cmd: Command, history) =>
        interpretCommand(cmd, state)
    }

  def compile(parsed: Parsed, state: State): State = {
    implicit val ctx = myCtx
    compiler
      .compile(parsed, state)
      .fold(
        errors => {
          displayErrors(errors)
          state
        },
        (unit, newState, ctx) => {
          displayDefinitions(unit.tpdTree)(ctx)
          newState
        }
      )
  }

  def displayDefinitions(tree: tpd.Tree)(implicit ctx: Context): Unit = {
    def display(tree: tpd.Tree) = if (tree.symbol.info.exists) {
      val info = tree.symbol.info
      val defn = ctx.definitions
      val defs =
        info.bounds.hi.finalResultType
        .membersBasedOnFlags(Method, Synthetic | Private)
        .filterNot { denot =>
          denot.symbol.owner == defn.AnyClass ||
          denot.symbol.owner == defn.ObjectClass ||
          denot.symbol.isConstructor
        }

      val vals =
        info.fields
        .filterNot(_.symbol.is(ParamAccessor | Private | Synthetic | Module))
        .filter(_.symbol.name.is(SimpleNameKind))

      (
        defs.map(Rendering.renderMethod) ++
        vals.map(Rendering.renderVal)
      ).foreach(println)
    }

    def displayTypeDef(tree: tpd.TypeDef) = {
      val sym = tree.symbol
      val name = tree.name.show.dropRight(if (sym.is(Module)) 1 else 0)

      val kind =
        if (sym.is(CaseClass)) "case class"
        else if (sym.is(Trait)) "trait"
        else if (sym.is(Module)) "object"
        else "class"


      println(SyntaxHighlighting(s"defined $kind $name"))
    }


    tree match {
      case tpd.PackageDef(_, xs) =>
        val allTypeDefs = xs.collect { case td: tpd.TypeDef => td }
        val (objs, tds) = allTypeDefs.partition(_.name.show.startsWith("ReplSession"))
        objs
          .foreach(display)
        tds
          .filterNot(t => t.symbol.is(Synthetic) || !t.name.is(SimpleNameKind))
          .foreach(displayTypeDef)
    }
  }

  def interpretCommand(cmd: Command, state: State): Unit = cmd match {
    case UnknownCommand(cmd) => {
      println(s"""Unknown command: "$cmd", run ":help" for a list of commands""")
      run(state)
    }

    case Help => {
      println(Help.text)
      run(state)
    }

    case Reset => {
      myCtx = initCtx.fresh
      run()
    }

    case Load(path) =>
      if ((new java.io.File(path)).exists) {
        val contents = scala.io.Source.fromFile(path).mkString
        run(state.copy(history = contents :: state.history))
      }
      else {
        println(s"""Couldn't find file "$path"""")
        run(state)
      }

    case Quit =>
      // end of the world!
  }

  private val messageRenderer = new MessageRendering {}
  private def renderMessage(cont: MessageContainer): Context => String =
    messageRenderer.messageAndPos(cont.contained(), cont.pos, messageRenderer.diagnosticLevel(cont))

  def displayErrors(errs: Seq[MessageContainer])(implicit ctx: Context): Unit =
    errs.map(renderMessage(_)(ctx)).foreach(println)
}
