package dotty.tools.dotc
package core

import Decorators._
import Periods._
import Names._
import Phases._
import Types._
import Symbols._
import TypeComparers._, Printers._
import collection.mutable
import collection.immutable.BitSet

object Contexts {

  val NoContext: Context = null

  abstract class Context extends Periods with Substituters with TypeOps {
    implicit val ctx: Context = this
    val underlying: Context
    val root: RootContext
    val period: Period
    def constraints: Constraints
    def typeComparer: TypeComparer
    def printer: Printer = ???
    def names: NameTable
    def enclClass: Context = ???
    def phase: Phase = ???
    def owner: Symbol = ???
    def erasedTypes: Boolean = ???
  }

  abstract class DiagnosticsContext(ctx: Context) extends SubContext(ctx) {
    var diagnostics: () => String
  }

  abstract class SubContext(val underlying: Context) extends Context {
    val root: RootContext = underlying.root
    val period: Period = underlying.period
    val constraints = underlying.constraints
    def names: NameTable = root.names
    lazy val typeComparer =
      if (constraints eq underlying.constraints) underlying.typeComparer
      else new TypeComparer(this)
  }

  class RootContext extends Context
                       with Transformers {

    val underlying: Context = throw new UnsupportedOperationException("RootContext.underlying")
    def typeComparer: TypeComparer = ???

    val root: RootContext = this
    val period = Nowhere
    val names: NameTable = new NameTable
    val variance = 1

    var lastPhaseId: Int = NoPhaseId
    lazy val definitions = new Definitions()(this)

    val constraints: Constraints = Map()

    // Symbols state
    /** A map from a superclass id to the class that has it */
    private[core] var classOfId = new Array[ClassSymbol](InitialSuperIdsSize)

    /** A map from a superclass to its superclass id */
    private[core] val superIdOfClass = new mutable.HashMap[ClassSymbol, Int]

    /** The last allocate superclass id */
    private[core] var lastSuperId = -1

    /** Allocate and return next free superclass id */
    private[core] def nextSuperId: Int = {
      lastSuperId += 1;
      if (lastSuperId >= classOfId.length) {
        val tmp = new Array[ClassSymbol](classOfId.length * 2)
        classOfId.copyToArray(tmp)
        classOfId = tmp
      }
      lastSuperId
    }

    // SymDenotations state
    private[core] val uniqueBits = new util.HashSet[BitSet]("superbits", 1024)

    // Types state
    private[core] val uniques = new util.HashSet[Type]("uniques", initialUniquesCapacity) {
      override def hash(x: Type): Int = x.hash
    }

    // TypeOps state
    private[core] var volatileRecursions: Int = 0
    private[core] val pendingVolatiles = new mutable.HashSet[Type]
  }

  /** Initial size of superId table */
  private final val InitialSuperIdsSize = 4096

  /** Initial capacity of uniques HashMap */
  private[core] final val initialUniquesCapacity = 50000

  /** How many recursive calls to isVolatile are performed before
   *  logging starts.
   */
  private[core] final val LogVolatileThreshold = 50
}
