/*
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package uk.ac.manchester.tornado.drivers.ptx.graal.phases;

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;
import static uk.ac.manchester.tornado.runtime.TornadoCoreRuntime.getDebugContext;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Graph;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.common.DeadCodeEliminationPhase;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.exceptions.TornadoBailoutRuntimeException;
import uk.ac.manchester.tornado.drivers.ptx.graal.nodes.PTXKernelContextAccessNode;
import uk.ac.manchester.tornado.runtime.common.RuntimeUtilities;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.graal.nodes.ParallelRangeNode;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoLoopUnroller;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoValueTypeReplacement;

public class TornadoTaskSpecialisation extends BasePhase<TornadoHighTierContext> {

    private static final int MAX_ITERATIONS = 15;

    private final CanonicalizerPhase canonicalizer;
    private final TornadoValueTypeReplacement valueTypeReplacement;
    private final DeadCodeEliminationPhase deadCodeElimination;
    private final TornadoLoopUnroller loopUnroll;
    private long batchThreads;
    private int index;
    private boolean gridScheduling;
    private boolean printOnce = true;

    private static final String WARNING_GRID_SCHEDULER_DYNAMIC_LOOP_BOUNDS = "[TornadoVM] Warning: The loop bounds will be configured by the GridScheduler. Check the grid by using the flag --threadInfo.";

    public TornadoTaskSpecialisation(CanonicalizerPhase canonicalizer) {
        this.canonicalizer = canonicalizer;
        this.valueTypeReplacement = new TornadoValueTypeReplacement();
        this.deadCodeElimination = new DeadCodeEliminationPhase();
        this.loopUnroll = new TornadoLoopUnroller(canonicalizer);
    }

    private Field lookupField(Class<?> type, String field) {
        Field f = null;
        try {
            f = type.getDeclaredField(field);
            if (!f.isAccessible()) {
                f.setAccessible(true);
            }
        } catch (NoSuchFieldException | SecurityException e) {
            if (type.getSuperclass() != null) {
                f = lookupField(type.getSuperclass(), field);
            } else {
                e.printStackTrace();
            }
        }
        return f;
    }

    @FunctionalInterface
    private interface FunctionThatThrows<T, R> {
        R apply(T t) throws IllegalArgumentException, IllegalAccessException;
    }

    private <T> T lookup(Object object, FunctionThatThrows<Object, T> function) throws IllegalArgumentException, IllegalAccessException {
        return function.apply(object);
    }

    private Object lookupRefField(StructuredGraph graph, Node node, Object obj, String field) {
        final Class<?> type = obj.getClass();
        final Field f = lookupField(type, field);
        Object result;
        try {
            result = f.get(obj);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private ConstantNode lookupPrimField(StructuredGraph graph, Node node, Object obj, String field, JavaKind kind) {
        final Class<?> type = obj.getClass();
        final Field f = lookupField(type, field);
        ConstantNode constant = null;
        try {
            switch (kind) {
                case Boolean:
                    constant = ConstantNode.forBoolean(lookup(obj, f::getBoolean));
                    break;
                case Byte:
                    constant = ConstantNode.forByte(lookup(obj, f::getByte), graph);
                    break;
                case Char:
                    constant = ConstantNode.forChar(lookup(obj, f::getChar), graph);
                    break;
                case Double:
                    constant = ConstantNode.forDouble(lookup(obj, f::getDouble));
                    break;
                case Float:
                    constant = ConstantNode.forFloat(lookup(obj, f::getFloat));
                    break;
                case Int:
                    constant = ConstantNode.forInt(lookup(obj, f::getInt));
                    break;
                case Long:
                    constant = ConstantNode.forLong(lookup(obj, f::getLong));
                    break;
                case Short:
                    constant = ConstantNode.forShort(lookup(obj, f::getShort), graph);
                    break;
                case Object:
                    /*
                     * propagate all constants from connected final fields
                     */
                    if (Modifier.isFinal(f.getModifiers())) {
                        final Object value = lookup(obj, f::get);
                        node.usages().filter(LoadFieldNode.class).forEach(load -> evaluate(graph, load, value));
                        node.usages().filter(ArrayLengthNode.class).forEach(arrayLength -> evaluate(graph, arrayLength, value));
                    }
                    break;
                case Illegal:
                case Void:
                default:
                    break;
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return constant;
    }

    private void printWarningMessageForDynamicLoopBounds() {
        if (printOnce) {
            System.out.println(WARNING_GRID_SCHEDULER_DYNAMIC_LOOP_BOUNDS);
            printOnce = false;
        }
    }

    private void evaluate(final StructuredGraph graph, final Node node, final Object value) {
        if (node instanceof ArrayLengthNode) {
            ArrayLengthNode arrayLength = (ArrayLengthNode) node;
            int length = Array.getLength(value);

            /**
             * This condition covers the case that loop bounds should be taken based on the
             * grid size given by {@link GridScheduler}. This allows the loop bounds to be
             * dynamically configured, without requiring recompilation.
             */
            if (gridScheduling && isParameterInvolvedInParallelLoopBound(node)) {
                printWarningMessageForDynamicLoopBounds();
                ConstantNode constantValue = graph.addOrUnique(ConstantNode.forInt(index));
                PTXKernelContextAccessNode kernelContextAccessNode = graph.addOrUnique(new PTXKernelContextAccessNode(constantValue));
                node.replaceAtUsages(kernelContextAccessNode);
                index++;
            } else {
                final ConstantNode constant;
                if (batchThreads <= 0) {
                    constant = ConstantNode.forInt(length);
                } else {
                    constant = ConstantNode.forInt((int) batchThreads);
                }
                node.replaceAtUsages(graph.addOrUnique(constant));
            }
            arrayLength.clearInputs();
            GraphUtil.removeFixedWithUnusedInputs(arrayLength);
        } else if (node instanceof LoadFieldNode) {
            final LoadFieldNode loadField = (LoadFieldNode) node;
            final ResolvedJavaField field = loadField.field();
            if (field.getType().getJavaKind().isPrimitive()) {
                ConstantNode constant = lookupPrimField(graph, node, value, field.getName(), field.getJavaKind());
                constant = graph.addOrUnique(constant);
                node.replaceAtUsages(constant);
                loadField.clearInputs();
                graph.removeFixed(loadField);
            } else if (field.isFinal()) {
                Object object = lookupRefField(graph, node, value, field.getName());
                node.usages().forEach(n -> evaluate(graph, n, object));
            } else if (!field.isFinal()) {
                throw new TornadoBailoutRuntimeException("Non-final objects introduced via scope are not supported");
            }
        } else if (node instanceof IsNullNode) {
            final IsNullNode isNullNode = (IsNullNode) node;
            final boolean isNull = (value == null);
            if (isNull) {
                isNullNode.replaceAtUsages(LogicConstantNode.tautology(graph));
            } else {
                isNullNode.replaceAtUsages(LogicConstantNode.contradiction(graph));
            }
            isNullNode.safeDelete();
        } else if (node instanceof PiNode) {
            PiNode piNode = (PiNode) node;
            piNode.replaceAtUsages(piNode.getOriginalNode());
            piNode.safeDelete();
        }
    }

    private ConstantNode createConstantFromObject(Object obj) {
        ConstantNode result = null;
        if (obj instanceof Float) {
            result = ConstantNode.forFloat((float) obj);
        } else if (obj instanceof Integer) {
            result = ConstantNode.forInt((int) obj);
        } else if (obj instanceof Double) {
            result = ConstantNode.forDouble((double) obj);
        } else {
            unimplemented("createConstantFromObject: %s", obj);
        }
        return result;
    }

    private boolean isParameterInvolvedInParallelLoopBound(Node parameterNode) {
        AtomicBoolean parameterInLoopBound = new AtomicBoolean(false);
        parameterNode.usages().snapshot().forEach(node -> {
            if (node instanceof ParallelRangeNode) {
                parameterInLoopBound.set(true);
            }
        });
        return parameterInLoopBound.get();
    }

    private void propagateParameters(StructuredGraph graph, ParameterNode parameterNode, Object[] args) {
        if (args[parameterNode.index()] != null && RuntimeUtilities.isBoxedPrimitiveClass(args[parameterNode.index()].getClass())) {
            /**
             * This condition covers the case that loop bounds should be taken based on the
             * grid size given by {@link GridScheduler}. This allows the loop bounds to be
             * dynamically configured, without requiring recompilation.
             */
            if (gridScheduling && isParameterInvolvedInParallelLoopBound(parameterNode)) {
                printWarningMessageForDynamicLoopBounds();
                ConstantNode constantValue = graph.addOrUnique(ConstantNode.forInt(index));
                PTXKernelContextAccessNode kernelContextAccessNode = graph.addOrUnique(new PTXKernelContextAccessNode(constantValue));
                parameterNode.replaceAtUsages(kernelContextAccessNode);
                index++;
            } else {
                ConstantNode constant = createConstantFromObject(args[parameterNode.index()]);
                graph.addWithoutUnique(constant);
                parameterNode.replaceAtUsages(constant);
            }
        } else {
            parameterNode.usages().snapshot().forEach(n -> {
                evaluate(graph, n, args[parameterNode.index()]);
            });
        }
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        int iterations = 0;
        int lastNodeCount = graph.getNodeCount();
        boolean hasWork = true;
        this.batchThreads = context.getBatchThreads();
        this.gridScheduling = context.isGridSchedulerEnabled();

        while (hasWork) {
            final Graph.Mark mark = graph.getMark();
            if (context.hasArgs()) {
                getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "Before Phase Propagate Parameters");
                for (final ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                    propagateParameters(graph, param, context.getArgs());
                }
                getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "After Phase Propagate Parameters");
            }

            canonicalizer.apply(graph, context);

            graph.getNewNodes(mark).filter(PiNode.class).forEach(pi -> {
                if (pi.stamp(NodeView.DEFAULT) instanceof ObjectStamp && pi.object().stamp(NodeView.DEFAULT) instanceof ObjectStamp) {
                    pi.replaceAtUsages(pi.object());
                    pi.clearInputs();
                    pi.safeDelete();
                }
            });

            getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "After Phase Pi Node Removal");

            loopUnroll.execute(graph, context);

            valueTypeReplacement.execute(graph, context);

            canonicalizer.apply(graph, context);

            deadCodeElimination.run(graph);

            getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "After TaskSpecialisation iteration = " + iterations);

            hasWork = (lastNodeCount != graph.getNodeCount() || graph.getNewNodes(mark).isNotEmpty()) && (iterations < MAX_ITERATIONS);
            lastNodeCount = graph.getNodeCount();
            iterations++;
        }

        graph.getNodes().filter(ParallelRangeNode.class).forEach(range -> {
            if (range.value() instanceof PhiNode) {
                PhiNode phiNode = (PhiNode) range.value();
                NodeIterable<Node> usages = range.usages();
                for (Node usage : usages) {
                    if (usage instanceof IntegerLessThanNode) {
                        IntegerLessThanNode less = (IntegerLessThanNode) usage;
                        ConstantNode constant = null;
                        if (less.getX() instanceof ConstantNode) {
                            constant = (ConstantNode) less.getX();
                        } else if (less.getY() instanceof ConstantNode) {
                            constant = (ConstantNode) less.getY();
                        }

                        // we swap the values between the new Constant and the PhiNode
                        if (constant != null) {
                            getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "Before Swapping Constant-Phi");
                            ParallelRangeNode pr = new ParallelRangeNode(range.index(), constant, range.offset(), range.stride());
                            graph.addOrUnique(pr);
                            range.safeDelete();

                            IntegerLessThanNode intLess = new IntegerLessThanNode(phiNode, pr);
                            graph.addOrUnique(intLess);
                            less.usages().first().replaceAllInputs(less, intLess);
                            less.safeDelete();
                            getDebugContext().dump(DebugContext.INFO_LEVEL, graph, "After Swapping Constant-Phi");
                        }
                    }
                }
            }
        });

        if (iterations == MAX_ITERATIONS) {
            Tornado.warn("TaskSpecialisation unable to complete after %d iterations", iterations);
        }
        Tornado.debug("TaskSpecialisation ran %d iterations", iterations);
        Tornado.debug("valid graph? %s", graph.verify());
        index = 0;
    }
}
