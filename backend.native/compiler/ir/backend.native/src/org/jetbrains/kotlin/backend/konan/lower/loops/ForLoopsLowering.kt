/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower.loops

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.irasdescriptors.isSubtypeOf
import org.jetbrains.kotlin.backend.konan.irasdescriptors.typeWithStarProjections
import org.jetbrains.kotlin.ir.util.isSimpleTypeWithQuestionMark
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrVariableSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.util.OperatorNameConventions

/**  This lowering pass optimizes range-based for-loops.
 *
 *   Replace iteration over ranges (X.indices, a..b, etc.) and arrays with
 *   simple while loop over primitive induction variable.
 */
internal class ForLoopsLowering(val context: Context) : FileLoweringPass {

    private val progressionInfoBuilder = ProgressionInfoBuilder(context)

    // TODO: reduce scope to IrBlock(origin=FOR_LOOP)
    override fun lower(irFile: IrFile) {

        val transformer = RangeLoopTransformer(context, progressionInfoBuilder)
        // Lower loops
        irFile.transformChildrenVoid(transformer)

        // Update references in break/continue.
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitBreakContinue(jump: IrBreakContinue): IrExpression {
                transformer.oldLoopToNewLoop[jump.loop]?.let { jump.loop = it }
                return jump
            }
        })
    }
}

/** Contains information about variables used in the loop. */
sealed class ForLoopInfo(
    val progressionInfo: ProgressionInfo,
    val inductionVariable: IrVariable,
    val bound: IrVariable,
    val last: IrVariable,
    val step: IrVariable,
    var loopVariable: IrVariable?
)

internal class ProgressionLoopInfo(
        progressionInfo: ProgressionInfo,
        inductionVariable: IrVariable,
        bound: IrVariable,
        last: IrVariable,
        step: IrVariable,
        loopVariable: IrVariable? = null
) : ForLoopInfo(progressionInfo, inductionVariable, bound, last, step, loopVariable)

internal class ArrayLoopInfo(
        progressionInfo: ProgressionInfo,
        inductionVariable: IrVariable,
        bound: IrVariable,
        last: IrVariable,
        step: IrVariable,
        val collection: IrValueDeclaration
) : ForLoopInfo(progressionInfo, inductionVariable, bound, last, step, inductionVariable)

private fun ProgressionType.elementType(context: Context): IrType = when (this) {
    ProgressionType.INT_PROGRESSION -> context.irBuiltIns.intType
    ProgressionType.LONG_PROGRESSION -> context.irBuiltIns.longType
    ProgressionType.CHAR_PROGRESSION -> context.irBuiltIns.charType
}

private class RangeLoopTransformer(val context: Context, val progressionInfoBuilder: ProgressionInfoBuilder) : IrElementTransformerVoidWithContext() {

    private val symbols = context.ir.symbols
    private val iteratorToLoopInfo = mutableMapOf<IrVariableSymbol, ForLoopInfo>()
    internal val oldLoopToNewLoop = mutableMapOf<IrLoop, IrLoop>()

    private val progressionElementClasses = (symbols.integerClasses + symbols.char).toSet()

    val scopeOwnerSymbol
        get() = currentScope!!.scope.scopeOwnerSymbol

    private fun DeclarationIrBuilder.buildMinValueCondition(forLoopInfo: ForLoopInfo): IrExpression {
        // Condition for a corner case: for (i in a until Int.MIN_VALUE) {}.
        // Check if forLoopInfo.bound > MIN_VALUE.
        val progressionType = forLoopInfo.progressionInfo.progressionType
        val irBuiltIns = context.irBuiltIns
        val minConst = when (progressionType) {
            ProgressionType.INT_PROGRESSION -> IrConstImpl
                    .int(startOffset, endOffset, irBuiltIns.intType, Int.MIN_VALUE)
            ProgressionType.CHAR_PROGRESSION -> IrConstImpl
                    .char(startOffset, endOffset, irBuiltIns.charType, 0.toChar())
            ProgressionType.LONG_PROGRESSION -> IrConstImpl
                    .long(startOffset, endOffset, irBuiltIns.longType, Long.MIN_VALUE)
        }
        val compareTo = symbols.getBinaryOperator(OperatorNameConventions.COMPARE_TO,
                forLoopInfo.bound.type.toKotlinType(),
                minConst.type.toKotlinType())
        return irCall(irBuiltIns.greaterFunByOperandType[irBuiltIns.int]?.symbol!!).apply {
            val compareToCall = irCall(compareTo).apply {
                dispatchReceiver = irGet(forLoopInfo.bound)
                putValueArgument(0, minConst)
            }
            putValueArgument(0, compareToCall)
            putValueArgument(1, irInt(0))
        }
    }

    // TODO: Eliminate the loop if we can prove that it will not be executed.
    private fun DeclarationIrBuilder.buildEmptinessCheck(loop: IrLoop, forLoopInfo: ForLoopInfo): IrExpression {
        val builtIns = context.irBuiltIns
        val increasing = forLoopInfo.progressionInfo.increasing
        val comparingBuiltIn = if (increasing) {
            // TODO: Consider behavior unification?
            when (forLoopInfo) {
                is ProgressionLoopInfo -> builtIns.lessOrEqualFunByOperandType[builtIns.int]?.symbol
                is ArrayLoopInfo -> builtIns.lessFunByOperandType[builtIns.int]?.symbol
            }
        }
        else builtIns.greaterOrEqualFunByOperandType[builtIns.int]?.symbol

        // Check if inductionVariable <= last.
        val compareTo = symbols.getBinaryOperator(OperatorNameConventions.COMPARE_TO,
                forLoopInfo.inductionVariable.type.toKotlinType(),
                forLoopInfo.last.type.toKotlinType())

        val check = irCall(comparingBuiltIn!!).apply {
            putValueArgument(0, irCallOp(compareTo.owner, irGet(forLoopInfo.inductionVariable), irGet(forLoopInfo.last)))
            putValueArgument(1, irInt(0))
        }

        // Process closed and open ranges in different manners.
        return if (forLoopInfo.progressionInfo.closed) {
            irIfThen(check, loop)   // if (inductionVariable <= last) { loop }
        } else {
            // Take into account a corner case: for (i in a until Int.MIN_VALUE) {}.
            // if (inductionVariable <= last && bound > MIN_VALUE) { loop }
            irIfThen(check, irIfThen(buildMinValueCondition(forLoopInfo), loop))
        }
    }

    private fun DeclarationIrBuilder.buildNewCondition(oldCondition: IrExpression): Pair<IrExpression, ForLoopInfo>? {
        if (oldCondition !is IrCall || oldCondition.origin != IrStatementOrigin.FOR_LOOP_HAS_NEXT) {
            return null
        }

        val irIteratorAccess = oldCondition.dispatchReceiver as? IrGetValue ?: throw AssertionError()
        // Return null if we didn't lower a corresponding header.
        val forLoopInfo = iteratorToLoopInfo[irIteratorAccess.symbol] ?: return null
        assert(forLoopInfo.loopVariable != null)

        val condition = irCall(context.irBuiltIns.booleanNotSymbol).apply {
            putValueArgument(0, irCall(context.irBuiltIns.eqeqSymbol).apply {
                putValueArgument(0, irGet(forLoopInfo.loopVariable!!))
                putValueArgument(1, irGet(forLoopInfo.last))
            })
        }
        return condition to forLoopInfo
    }

    /**
     * This loop
     *
     * for (i in first..last step foo) { ... }
     *
     * is represented in IR in such a manner:
     *
     * val it = (first..last step foo).iterator()
     * while (it.hasNext()) {
     *     val i = it.next()
     *     ...
     * }
     *
     * We transform it into the following loop:
     *
     * var it = first
     * if (it <= last) {  // (it >= last if the progression is decreasing)
     *     do {
     *         val i = it++
     *         ...
     *     } while (i != last)
     * }
     */
    // TODO:  Lower `for (i in a until b)` to loop with precondition: for (i = a; i < b; a++);
    override fun visitWhileLoop(loop: IrWhileLoop): IrExpression {
        if (loop.origin != IrStatementOrigin.FOR_LOOP_INNER_WHILE) {
            return super.visitWhileLoop(loop)
        }

        with(context.createIrBuilder(scopeOwnerSymbol, loop.startOffset, loop.endOffset)) {
            // Transform accesses to the old iterator (see visitVariable method). Store loopVariable in loopInfo.
            // Replace not transparent containers with transparent ones (IrComposite)
            val newBody = loop.body?.transform(this@RangeLoopTransformer, null)?.let {
                if (it is IrContainerExpression && !it.isTransparentScope) {
                    IrCompositeImpl(startOffset, endOffset, it.type, it.origin, it.statements)
                } else {
                    it
                }
            }
            val (newCondition, forLoopInfo) = buildNewCondition(loop.condition)
                    ?: return super.visitWhileLoop(loop)

            val newLoop = IrDoWhileLoopImpl(loop.startOffset, loop.endOffset, loop.type, loop.origin).apply {
                label = loop.label
                condition = newCondition
                body = newBody
            }
            oldLoopToNewLoop[loop] = newLoop
            // Build a check for an empty progression before the loop.
            return buildEmptinessCheck(newLoop, forLoopInfo)
        }
    }

    fun processHeader(variable: IrVariable): IrStatement? {

        assert(variable.origin == IrDeclarationOrigin.FOR_LOOP_ITERATOR)
        val symbol = variable.symbol
        val iteratorType = symbols.iterator.typeWithStarProjections

        if (!variable.type.isSubtypeOf(iteratorType)) {
            return null
        }
        assert(symbol !in iteratorToLoopInfo)

        val builder = context.createIrBuilder(scopeOwnerSymbol, variable.startOffset, variable.endOffset)
        // Collect loop info and form the loop header composite.
        val progressionInfo = variable.accept(progressionInfoBuilder, null)
                ?: return null

        with(builder) {
            kotlin.with(progressionInfo) {
                /**
                 * For this loop:
                 * `for (i in a() .. b() step c() step d())`
                 * We need to call functions in the following order: a, b, c, d.
                 * So we call b() before step calculations and then call last element calculation function (if required).
                 */

                val statements = mutableListOf<IrStatement>()

                progressionInfo.arrayDeclaration?.let { collection ->
                    if (isNewValueDeclaration) {
                        collection.parent = variable.parent
                        statements += collection
                    }
                }

                // Due to features of PSI2IR we can obtain nullable arguments here while actually
                // they are non-nullable (the frontend takes care about this). So we need to cast them to non-nullable.
                val inductionVariable = scope.createTemporaryVariable(first.castIfNecessary(progressionType),
                        nameHint = "inductionVariable",
                        isMutable = true,
                        origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE).also {
                    statements.add(it)
                }

                val boundValue = scope.createTemporaryVariable(ensureNotNullable(bound.castIfNecessary(progressionType)),
                        nameHint = "bound",
                        origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE)
                        .also { statements.add(it) }

                val stepExpression = if (step != null) {
                    if (increasing) step else step.unaryMinus()
                } else {
                    defaultStep(startOffset, endOffset)
                }

                val stepValue = scope.createTemporaryVariable(ensureNotNullable(stepExpression),
                        nameHint = "step",
                        origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE).also {
                    statements.add(it)
                }

                // Calculate the last element of the progression
                // The last element can be:
                //    boundValue, if step is 1 and the range is closed.
                //    boundValue - 1, if step is 1 and the range is open.
                //    getProgressionLast(inductionVariable, boundValue, step), if step != 1 and the range is closed.
                //    getProgressionLast(inductionVariable, boundValue - 1, step), if step != 1 and the range is open.
                var lastExpression: IrExpression = if (!closed) {
                    val decrementSymbol = symbols.getUnaryOperator(OperatorNameConventions.DEC, boundValue.type.toKotlinType())
                    irCall(decrementSymbol.owner).apply {
                        dispatchReceiver = irGet(boundValue)
                    }
                } else {
                    irGet(boundValue)
                }
                if (needLastCalculation) {
                    lastExpression = irGetProgressionLast(progressionType,
                            inductionVariable,
                            lastExpression,
                            stepValue)
                }

                val lastValue = scope.createTemporaryVariable(lastExpression,
                        nameHint = "last",
                        origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE).also {
                    statements.add(it)
                }

                iteratorToLoopInfo[symbol] = if (arrayDeclaration != null) {
                    ArrayLoopInfo(
                            progressionInfo,
                            inductionVariable,
                            boundValue,
                            lastValue,
                            stepValue,
                            collection = arrayDeclaration)
                } else {
                    ProgressionLoopInfo(
                            progressionInfo,
                            inductionVariable,
                            boundValue,
                            lastValue,
                            stepValue
                    )
                }


                return IrCompositeImpl(startOffset, endOffset, context.irBuiltIns.unitType, null, statements)
            }
        }
    }

    private fun IrExpression.castIfNecessary(progressionType: ProgressionType): IrExpression {
        assert(type.classifierOrNull in progressionElementClasses)
        return if (type.classifierOrNull == progressionType.elementType(context).classifierOrNull) {
            this
        } else {
            val function = symbols.getFunction(progressionType.numberCastFunctionName, type.toKotlinType())
            IrCallImpl(startOffset, endOffset, function.owner.returnType, function)
                    .apply { dispatchReceiver = this@castIfNecessary }
        }
    }

    private fun DeclarationIrBuilder.ensureNotNullable(expression: IrExpression): IrExpression {
        return if (expression.type.isSimpleTypeWithQuestionMark) {
            irImplicitCast(expression, expression.type.makeNotNull())
        } else {
            expression
        }
    }

    private fun IrExpression.unaryMinus(): IrExpression {
        val unaryOperator = symbols.getUnaryOperator(OperatorNameConventions.UNARY_MINUS, type.toKotlinType())
        return IrCallImpl(startOffset, endOffset, unaryOperator.owner.returnType, unaryOperator).apply {
            dispatchReceiver = this@unaryMinus
        }
    }

    private fun ProgressionInfo.defaultStep(startOffset: Int, endOffset: Int): IrExpression {
        val type = progressionType.elementType(context)
        val step = if (increasing) 1 else -1
        return when {
            type.isInt() || type.isChar() ->
                IrConstImpl.int(startOffset, endOffset, context.irBuiltIns.intType, step)
            type.isLong() ->
                IrConstImpl.long(startOffset, endOffset, context.irBuiltIns.longType, step.toLong())
            else -> throw IllegalArgumentException()
        }
    }

    private fun irGetProgressionLast(progressionType: ProgressionType,
                                     first: IrVariable,
                                     lastExpression: IrExpression,
                                     step: IrVariable): IrExpression {
        val symbol = symbols.getProgressionLast[progressionType.elementType(context).toKotlinType()]
                ?: throw IllegalArgumentException("No `getProgressionLast` for type ${step.type} ${lastExpression.type}")
        val startOffset = lastExpression.startOffset
        val endOffset = lastExpression.endOffset
        return IrCallImpl(startOffset, lastExpression.endOffset, symbol.owner.returnType, symbol).apply {
            putValueArgument(0, IrGetValueImpl(startOffset, endOffset, first.type, first.symbol))
            putValueArgument(1, lastExpression.castIfNecessary(progressionType))
            putValueArgument(2, IrGetValueImpl(startOffset, endOffset, step.type, step.symbol))
        }
    }

    private fun getIterator(initializer: IrCall): IrGetValue =
            if (initializer.symbol in symbols.arrayGet.values) {
                (initializer.getValueArgument(0) as? IrCall)?.dispatchReceiver
            } else {
                initializer.dispatchReceiver
            } as? IrGetValue ?: throw AssertionError()

    // Lower getting a next induction variable value.
    fun processNext(variable: IrVariable): IrExpression? {
        val initializer = variable.initializer as IrCall

        val iterator = getIterator(initializer)
        val forLoopInfo = iteratorToLoopInfo[iterator.symbol]
                ?: return null  // If we didn't lower a corresponding header.

        val plusOperator = symbols.getBinaryOperator(
                OperatorNameConventions.PLUS,
                forLoopInfo.inductionVariable.type.toKotlinType(),
                forLoopInfo.step.type.toKotlinType()
        )

        if (forLoopInfo is ProgressionLoopInfo) {
            forLoopInfo.loopVariable = variable
        }

        with(context.createIrBuilder(scopeOwnerSymbol, initializer.startOffset, initializer.endOffset)) {
            variable.initializer = when (forLoopInfo) {
                is ArrayLoopInfo -> {
                    val collectionDeclaration = forLoopInfo.collection
                    val callee = symbols.arrayGet[collectionDeclaration.type.classifierOrFail]!!
                    irCall(callee).apply {
                        dispatchReceiver = irGet(collectionDeclaration)
                        putValueArgument(0, irGet(forLoopInfo.inductionVariable))
                    }
                }
                is ProgressionLoopInfo -> irGet(forLoopInfo.inductionVariable)
            }
            val increment = irSetVar(forLoopInfo.inductionVariable, irCallOp(plusOperator.owner,
                    irGet(forLoopInfo.inductionVariable),
                    irGet(forLoopInfo.step)))
            return IrCompositeImpl(variable.startOffset,
                    variable.endOffset,
                    context.irBuiltIns.unitType,
                    IrStatementOrigin.FOR_LOOP_NEXT,
                    listOf(variable, increment))
        }
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val initializer = declaration.initializer
        if (initializer == null || initializer !is IrCall) {
            return super.visitVariable(declaration)
        }
        return when (initializer.origin) {
            IrStatementOrigin.FOR_LOOP_ITERATOR ->
                processHeader(declaration)
            IrStatementOrigin.FOR_LOOP_NEXT ->
                processNext(declaration)
            else -> null
        } ?: super.visitVariable(declaration)
    }
}