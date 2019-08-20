package polynote.kernel
package interpreter
package scal

import java.lang.reflect.Constructor

import scala.reflect.internal.util.NoPosition
import scala.tools.nsc.interactive.Global
import polynote.messages.CellID
import zio.blocking.Blocking
import zio.{Task, TaskR, ZIO}
import ScalaInterpreter.{addPositionUpdates, captureLastExpression}

class ScalaInterpreter private (
  val scalaCompiler: ScalaCompiler
) extends Interpreter {
  import scalaCompiler.{CellCode, global, Imports}
  import global.{Tree, ValDef, TermName, Modifiers, EmptyTree, TypeTree, Import, Name, Type, Quasiquote, typeOf, atPos, NoType}

  ///////////////////////////////////
  // Interpreter interface methods //
  ///////////////////////////////////

  override def run(code: String, state: State): TaskR[Blocking with PublishResults with CurrentTask with CurrentRuntime, State] = for {
    publishSync      <- ZIO.accessM[PublishResults](_.unsafePublishResultSync)
    publishAsync     <- ZIO.accessM[PublishResults](_.unsafePublishResultAsync)
    blockingExecutor <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
    collectedState   <- injectState(collectState(state))
    valDefs           = collectedState.values.mapValues(_._1).values.toList
    cellCode         <- scalaCompiler.cellCode(s"Cell${state.id.toString}", code, collectedState.prevCells, valDefs, collectedState.imports)
      .flatMap(_.transformCode(transformCode).pruneInputs())
    remainingValues   = cellCode.inputs.map(_.name.decodedName.toString)
    values            = remainingValues.map(collectedState.values).map(_._2)
    runBlocking       = new BlockingService(publishSync, publishAsync, scalaCompiler.classLoader, blockingExecutor)
    cls              <- scalaCompiler.compileCell(cellCode).provide(runBlocking)
    resultInstance   <- runClass(cls, cellCode, values, state).provide(runBlocking)
    resultValues     <- getResultValues(state.id, cellCode, resultInstance)
  } yield ScalaCellState(state.id, state.prev, resultValues, cellCode, resultInstance)

  override def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]] = for {
    collectedState   <- injectState(collectState(state)).provide(CurrentRuntime.NoCurrentRuntime)
    valDefs           = collectedState.values.mapValues(_._1).values.toList
    cellCode         <- scalaCompiler.cellCode(s"Cell${state.id.toString}", code, collectedState.prevCells, valDefs, collectedState.imports, strictParse = false)
      .map(_.transformCode(transformCode))
  } yield completer.completions(cellCode, pos)

  override def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]] = for {
    collectedState <- injectState(collectState(state)).provide(CurrentRuntime.NoCurrentRuntime)
    valDefs         = collectedState.values.mapValues(_._1).values.toList
    cellCode         <- scalaCompiler.cellCode(s"Cell${state.id.toString}", code, collectedState.prevCells, valDefs, collectedState.imports, strictParse = false)
      .map(_.transformCode(transformCode))
  } yield completer.paramHints(cellCode, pos)

  ///////////////////////////////////////
  // Overrideable scala-specific stuff //
  ///////////////////////////////////////

  /**
    * Overrideable method to inject some pre-defined state (values and imports) into every cell. The base implementation
    * injects the `kernel` value, making it available to the notebook. Override to inject more imports or
    *
    */
  protected def injectState(collectedState: CollectedState): TaskR[CurrentRuntime, CollectedState] =
    ZIO.access[CurrentRuntime](_.currentRuntime).map {
      kernelRuntime =>
        collectedState.copy(values = collectedState.values + (runtimeValDef.name.toString -> (runtimeValDef, kernelRuntime: Any)))
    }

  /**
    * Transforms the Scala statements, to inject additional things like updating the execution status and ensuring
    * the last expression is captured. Override to add additional transformations (don't forget to call super.transformCode!)
    */
  protected def transformCode(code: List[Tree]): List[Tree] = {
    addPositionUpdates(global)(captureLastExpression(global)(code))
  }


  ////////////////////////////////////////////////////
  // Protected structures for subclass implementors //
  ////////////////////////////////////////////////////

  /**
    * Container for information about available values, etc from previous cells or predefined things
    */
  protected case class CollectedState(
    values: Map[String, (ValDef, Any)] = Map.empty,
    imports: Imports = Imports(),
    prevCells: List[CellCode] = Nil)


  //////////////////////////////////
  // Private scala-specific stuff //
  //////////////////////////////////

  private val completer = ScalaCompleter(scalaCompiler)

  // create the parameter that's used to inject the `kernel` value into cell scope
  private def runtimeValDef = ValDef(Modifiers(), TermName("kernel"), tq"polynote.runtime.KernelRuntime", EmptyTree)

  /**
    * Goes backward through the state and collects all the output values and imports from previous cells. For Scala cells,
    * it also builds up a list of prior CellCode instances. We start by wrapping a cell's code in a class which has all
    * these available values and all available prior cells as constructor arguments, and then we prune it to only keep
    * the constructor arguments which the code requires.
    */
  private def collectState(state: State): CollectedState = state.prev.collect {
    case ScalaCellState(_, _, values, cellCode, _) =>
      val valuesMap = values.map(v => v.name -> v.value).toMap
      val inputs = cellCode.typedOutputs.map(_.duplicate.setPos(NoPosition)).map(v => v.name.encodedName.toString -> (v, valuesMap(v.name.decodedName.toString))).toMap
      (inputs, Option(cellCode))
    case state =>
      val inputs = state.values.map {
        v =>
          val name = TermName(v.name)
          name.encodedName.toString -> (ValDef(Modifiers(), name, TypeTree(v.scalaType.asInstanceOf[global.Type]), EmptyTree), v.value)
      }.toMap
      (inputs, None)
  }.foldRight(CollectedState()) {
    case ((nextInputs, cellCode), CollectedState(inputs, imports, priorCells)) =>
      val nextImports = cellCode.map(_.splitImports()).getOrElse(Imports(Nil, Nil))
      CollectedState(nextInputs ++ inputs, nextImports ++ imports, cellCode.map(_ :: priorCells).getOrElse(priorCells))
  }

  private def collectPrevInstances(code: CellCode, state: State): List[AnyRef] = {
    val allInstances = state.prev.collect {
      case ScalaCellState(_, _, _, cellCode, inst) => cellCode.cellClassSymbol -> inst
    }.toMap

    val usedInstances = code.priorCells.map {
      cell => allInstances(cell.cellClassSymbol)
    }

    usedInstances
  }

  private def partitionInputs(code: CellCode, inputValues: List[Any]) = {
    val (implicitInputs, nonImplicitInputs) = code.inputs.zip(inputValues).partition(_._1.mods.isImplicit)
    (nonImplicitInputs.map(_._2.asInstanceOf[AnyRef]), implicitInputs.map(_._2.asInstanceOf[AnyRef]))
  }

  private def createInstance(constructor: Constructor[_], prevInstances: List[AnyRef], inputs: List[Any]): AnyRef = {
    constructor.newInstance(prevInstances ++ inputs.map(_.asInstanceOf[AnyRef]): _*).asInstanceOf[AnyRef]
  }

  /**
    * Run the cell given the loaded compiled class and the input values carried from previous cells (not including
    * prior cell instances themselves). Collects any required prior cell instances (for dependent types and imports)
    * and constructs the cell class (running the code) in an interruptible task while capturing standard output.
    */
  private def runClass(cls: Class[_], code: CellCode, inputValues: List[Any], state: State) = for {
    constructor   <- ZIO(cls.getDeclaredConstructors()(0))
    prevInstances  = collectPrevInstances(code, state)
    (nonImplicitInputs, implicitInputs) = partitionInputs(code, inputValues)
    instance      <- zio.blocking.effectBlocking(createInstance(constructor, prevInstances, nonImplicitInputs ++ implicitInputs))
  } yield instance

  private def getResultValues(id: CellID, code: CellCode, result: AnyRef) = {
    val cls = result.getClass
    val typedOuts = code.typedOutputs
    scalaCompiler.formatTypes(typedOuts.map(_.tpt.tpe)).flatMap {
      typeNames => zio.blocking.effectBlocking {
        typedOuts.zip(typeNames).collect {
          case (v, typeName) if !(v.tpt.tpe =:= typeOf[Unit]) && !(v.tpt.tpe =:= typeOf[scala.runtime.BoxedUnit]) =>
            val value = cls.getDeclaredMethod(v.name.encodedName.toString).invoke(result)
            ResultValue(v.name.decodedName.toString, typeName, Nil, id, value, v.tpt.tpe, Some((v.pos.start, v.pos.end)))
        }
      }
    }
  }

  ///////////////////////////////////////////

  /**
    * A [[State]] implementation for Scala cells. It tracks the CellCode and the instance of the cell class, which
    * we'll need to pass into future cells if they use types, classes, etc from this cell.
    */
  case class ScalaCellState(id: CellID, prev: State, values: List[ResultValue], cellCode: CellCode, instance: AnyRef) extends State {
    def withPrev(prev: State): ScalaCellState = copy(prev = prev)
  }

}

object ScalaInterpreter {
  def apply(): TaskR[ScalaCompiler.Provider, ScalaInterpreter] = ZIO.access[ScalaCompiler.Provider](_.scalaCompiler).map {
    compiler => new ScalaInterpreter(compiler)
  }
  // capture the last statement in a value Out, if it's a free expression
  def captureLastExpression(global: Global)(trees: List[global.Tree]): List[global.Tree] = {
    import global._
    trees.reverse match {
      case Nil => Nil
      case l :: r => l match {
        case v: ValDef => (v :: r).reverse
        case expr if expr.isTerm => (atPos(expr.pos)(ValDef(Modifiers(), TermName("Out"), TypeTree(NoType), expr)) :: r).reverse
        case v => (v :: r).reverse
      }
    }
  }

  // Notify the `kernel` of progress and execution status during the cell execution
  def addPositionUpdates(global: Global)(trees: List[global.Tree]): List[global.Tree] = {
    import global._
    val numTrees = trees.size
    trees.zipWithIndex.flatMap {
      case (tree, index) =>
        val treeProgress = index.toDouble / numTrees
        val lineStr = s"Line ${tree.pos.line}"
        // code to notify kernel of progress in the cell
        def setProgress(detail: String) = q"""kernel.setProgress($treeProgress, $detail)"""
        def setPos(mark: Tree) =
          if(mark.pos.isRange)
            Some(q"""kernel.setExecutionStatus(${mark.pos.start}, ${mark.pos.end})""")
          else None

        def wrapWithProgress(name: String, tree: Tree): List[Tree] =
          setPos(tree).toList ++ List(setProgress(name), tree)

        tree match {
          case tree: global.ValDef => wrapWithProgress(tree.name.decodedName.toString, tree)
          case tree: global.MemberDef => List(tree)
          case tree: global.Import => List(tree)
          case tree => wrapWithProgress(lineStr, tree)
        }
    }
  }

  class Factory extends Interpreter.Factory {
    val languageName = "Scala"
    override def apply(): TaskR[ScalaCompiler.Provider, Interpreter] = ScalaInterpreter()
  }
}