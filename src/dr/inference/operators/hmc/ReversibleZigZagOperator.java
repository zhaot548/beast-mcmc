/*
 * NewHamiltonianMonteCarloOperator.java
 *
 * Copyright (c) 2002-2019 Alexei Drummond, Andrew Rambaut and Marc Suchard
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.inference.operators.hmc;

import dr.inference.hmc.GradientWrtParameterProvider;
import dr.inference.hmc.PrecisionColumnProvider;
import dr.inference.hmc.PrecisionMatrixVectorProductProvider;
import dr.inference.hmc.ReversibleHMCProvider;
import dr.inference.model.Parameter;
import dr.math.MathUtils;
import dr.math.matrixAlgebra.ReadableVector;
import dr.math.matrixAlgebra.WrappedVector;
import dr.xml.Reportable;

/**
 * @author Aki Nishimura
 * @author Zhenyu Zhang
 * @author Marc A. Suchard
 */

public class ReversibleZigZagOperator extends AbstractZigZagOperator implements Reportable, ReversibleHMCProvider {

    public ReversibleZigZagOperator(GradientWrtParameterProvider gradientProvider,
                                    PrecisionMatrixVectorProductProvider multiplicationProvider,
                                    PrecisionColumnProvider columnProvider,
                                    double weight, Options runtimeOptions, Parameter mask,
                                    int threadCount, int direction) {

        super(gradientProvider, multiplicationProvider, columnProvider, weight, runtimeOptions, mask, threadCount);
        super.direction = direction;
    }
    double integrateTrajectory(WrappedVector position, int direction) {

        super.direction = direction;
        String signString;
        if (DEBUG_SIGN) {
            signString = printSign(position);
            System.err.println(signString);
        }

        if (TIMING) {
            timer.startTimer("warmUp");
        }

        WrappedVector momentum = drawInitialMomentum();
        WrappedVector velocity = drawInitialVelocity(momentum);
        WrappedVector gradient = getInitialGradient();
        WrappedVector action = getPrecisionProduct(velocity);

        BounceState bounceState = new BounceState(drawTotalTravelTime());

        if (TIMING) {
            timer.stopTimer("warmUp");
        }

        int count = 0;

        double[] p, v, a, g, m;
        if (TEST_NATIVE_OPERATOR) {

            p = position.getBuffer().clone();
            v = velocity.getBuffer().clone();
            a = action.getBuffer().clone();
            g = gradient.getBuffer().clone();
            m = momentum.getBuffer().clone();

            nativeZigZag.operate(columnProvider, p, v, a, g, m,
                    bounceState.remainingTime);
        }

        if (TEST_CRITICAL_REGION) {
            nativeZigZag.enterCriticalRegion(position.getBuffer(), velocity.getBuffer(),
                    action.getBuffer(), gradient.getBuffer(), momentum.getBuffer());
        }

        if (TIMING) {
            timer.startTimer("integrateTrajectory");
        }

        while (bounceState.isTimeRemaining()) {

            if (DEBUG) {
                debugBefore(position, count);
            }

            final MinimumTravelInformation firstBounce;

            if (taskPool != null) {

                if (FUSE) {

                    if (TIMING) {
                        timer.startTimer("getNext");
                    }

                    firstBounce = getNextBounceParallel(position,
                            velocity, action, gradient, momentum);

                    if (TIMING) {
                        timer.stopTimer("getNext");
                    }

                    if (TEST_NATIVE_BOUNCE) {
                        testNative(firstBounce, position, velocity, action, gradient, momentum);
                    }

                } else {

                    MinimumTravelInformation boundaryBounce = getNextBoundaryBounce(
                            position, velocity);
                    MinimumTravelInformation gradientBounce = getNextGradientBounceParallel(
                            action, gradient, momentum);

                    firstBounce = (boundaryBounce.time < gradientBounce.time) ?
                            new MinimumTravelInformation(boundaryBounce.time, boundaryBounce.index, Type.BOUNDARY) :
                            new MinimumTravelInformation(gradientBounce.time, gradientBounce.index, Type.GRADIENT);
                }

            } else {

                if (FUSE) {

                    if (TIMING) {
                        timer.startTimer("getNext");
                    }

                    firstBounce = getNextBounce(position,
                            velocity, action, gradient, momentum);

                    if (TIMING) {
                        timer.stopTimer("getNext");
                    }

                    if (TEST_NATIVE_BOUNCE) {
                        testNative(firstBounce, position, velocity, action, gradient, momentum);
                    }

                } else {

                    if (TIMING) {
                        timer.startTimer("getNextBoundary");
                    }

                    MinimumTravelInformation boundaryBounce = getNextBoundaryBounce(
                            position, velocity);

                    if (TIMING) {
                        timer.stopTimer("getNextBoundary");
                        timer.startTimer("getNextGradient");
                    }
                    MinimumTravelInformation gradientBounce = getNextGradientBounce(action, gradient, momentum);

                    if (TIMING) {
                        timer.stopTimer("getNextGradient");
                    }

                    firstBounce = (boundaryBounce.time < gradientBounce.time) ?
                            new MinimumTravelInformation(boundaryBounce.time, boundaryBounce.index, Type.BOUNDARY) :
                            new MinimumTravelInformation(gradientBounce.time, gradientBounce.index, Type.GRADIENT);

                }
            }

            bounceState = doBounce(bounceState, firstBounce, position, velocity, action, gradient, momentum);

            if (DEBUG) {
                debugAfter(bounceState, position);
                String newSignString = printSign(position);
                System.err.println(newSignString);
                if (bounceState.type != Type.BOUNDARY && signString.compareTo(newSignString) != 0) {
                    System.err.println("Sign error");
                }
            }

            ++count;
        }

        if (TIMING) {
            timer.stopTimer("integrateTrajectory");
        }

        if (TEST_CRITICAL_REGION) {
            nativeZigZag.exitCriticalRegion();
        }

        if (TEST_NATIVE_OPERATOR) {

            if (!close(p, position.getBuffer())) {

                System.err.println("c: " + new WrappedVector.Raw(p, 0, 10));
                System.err.println("c: " + new WrappedVector.Raw(position.getBuffer(), 0, 10));
            } else {
                System.err.println("close");
            }
        }

        if (DEBUG_SIGN) {
            printSign(position);
        }

        return 0.0;
    }
    void integrateTrajectoryWithMomentum(WrappedVector position, WrappedVector momentum, int direction, double time ) {

        super.direction = direction;
        WrappedVector velocity = drawInitialVelocity(momentum);
        WrappedVector gradient = getInitialGradient();
        WrappedVector action = getPrecisionProduct(velocity);

        BounceState bounceState = new BounceState(time);

        while (bounceState.isTimeRemaining()) {

            final MinimumTravelInformation firstBounce;

            firstBounce = getNextBounce(position,
                    velocity, action, gradient, momentum);

            bounceState = doBounce(bounceState, firstBounce, position, velocity, action, gradient, momentum);
        }
    }

    @Override
    public String getOperatorName() {
        return "Zig-zag particle operator";
    }

    @Override
    final WrappedVector drawInitialMomentum() {

        ReadableVector mass = preconditioning.mass;
        double[] momentum = new double[mass.getDim()];

        for (int i = 0, len = momentum.length; i < len; i++) {
            int sign = (MathUtils.nextDouble() > 0.5) ? 1 : -1;
            momentum[i] = sign * MathUtils.nextExponential(1) * Math.sqrt(mass.get(i));
        }

        if (mask != null) {
            applyMask(momentum);
        }

        return new WrappedVector.Raw(momentum);
    }

    @Override
    final WrappedVector drawInitialVelocity(WrappedVector momentum) {

        ReadableVector mass = preconditioning.mass;
        double[] velocity = new double[momentum.getDim()];

        for (int i = 0, len = momentum.getDim(); i < len; ++i) {
            velocity[i] = sign(momentum.get(i)) / Math.sqrt(mass.get(i));
        }

        return new WrappedVector.Raw(velocity);
    }

    @Override
    final BounceState doBounce(BounceState initialBounceState,
                               MinimumTravelInformation firstBounce,
                               WrappedVector position, WrappedVector velocity,
                               WrappedVector action, WrappedVector gradient, WrappedVector momentum) {

        if (TIMING) {
            timer.startTimer("doBounce");
        }

        double remainingTime = initialBounceState.remainingTime;
        double eventTime = firstBounce.time;

        final BounceState finalBounceState;

        final Type eventType = firstBounce.type;
        final int eventIndex = firstBounce.index;
        WrappedVector column = getPrecisionColumn(eventIndex);

        if (remainingTime < Math.abs(eventTime)) { // No event during remaining time

//            updatePosition(position, velocity, sign(eventTime) * remainingTime);
            updateDynamics(position.getBuffer(), velocity.getBuffer(),
                    action.getBuffer(), gradient.getBuffer(), momentum.getBuffer(),
                    column.getBuffer(), sign(eventTime) * remainingTime, eventIndex);
            finalBounceState = new BounceState(Type.NONE, -1, 0.0); //todo: make sure it is necessary to update & record momentum here!

        } else {

            if (TIMING) {
                timer.startTimer("notUpdateAction");
            }

            if (TEST_FUSED_DYNAMICS) {


                if (!TEST_NATIVE_INNER_BOUNCE) {

                    updateDynamics(position.getBuffer(), velocity.getBuffer(),
                            action.getBuffer(), gradient.getBuffer(), momentum.getBuffer(),
                            column.getBuffer(), eventTime, eventIndex);

                } else {

                    nativeZigZag.updateDynamics(position.getBuffer(), velocity.getBuffer(),
                            action.getBuffer(), gradient.getBuffer(), momentum.getBuffer(),
                            column.getBuffer(), eventTime, eventIndex, eventType.ordinal());
                }

                if (firstBounce.type == Type.BOUNDARY) { // Reflect against boundary

                    reflectMomentum(momentum, position, eventIndex);
                } else { // Bounce caused by the gradient

                    setZeroMomentum(momentum, eventIndex);

                }

                reflectVelocity(velocity, eventIndex);

            } else {

                if (!TEST_NATIVE_INNER_BOUNCE) {
                    updatePosition(position, velocity, eventTime);
                    updateMomentum(momentum, gradient, action, eventTime);

                    if (firstBounce.type == Type.BOUNDARY) { // Reflect against boundary

                        reflectMomentum(momentum, position, eventIndex);

                    } else { // Bounce caused by the gradient

                        setZeroMomentum(momentum, eventIndex);

                    }

                    reflectVelocity(velocity, eventIndex);
                    updateGradient(gradient, eventTime, action);

                } else {

                    if (TEST_CRITICAL_REGION) {
                        nativeZigZag.innerBounceCriticalRegion(eventIndex, eventIndex, eventType.ordinal());
                    } else {
                        nativeZigZag.innerBounce(position.getBuffer(), velocity.getBuffer(),
                                action.getBuffer(), gradient.getBuffer(), momentum.getBuffer(),
                                eventTime, eventIndex, eventType.ordinal());
                    }
                }

                if (TIMING) {
                    timer.stopTimer("notUpdateAction");
                }

                updateAction(action, velocity, eventIndex);
            }

            finalBounceState = new BounceState(eventType, eventIndex, remainingTime - Math.abs(eventTime));
        }

        if (TIMING) {
            timer.stopTimer("doBounce");
        }

        return finalBounceState;
    }

    private void updateDynamics(double[] p,
                                double[] v,
                                double[] a,
                                double[] g,
                                double[] m,
                                double[] c,
                                double time,
                                int index) {

        final double halfTimeSquared = time * time / 2;
        final double twoV = 2 * v[index];

        for (int i = 0, len = p.length; i < len; ++i) {
            final double gi = g[i]; final double ai = a[i];

            p[i] = p[i] + time * v[i];
            m[i] = m[i] + time * gi - halfTimeSquared * ai;
            g[i] = gi - time * ai;
            a[i] = ai - twoV * c[i];
        }

    }

    @Override
    public void updatePositionAfterMap(WrappedVector position, WrappedVector momentum, int direction, double time) {
        integrateTrajectoryWithMomentum(position, momentum, direction, time);
    }

    @Override
    public WrappedVector drawMomentum() {
        return drawInitialMomentum();
    }
//    private static void reflectMomentum(WrappedVector momentum,
//                                        WrappedVector position,
//                                        int eventIndex) {
//
//        momentum.set(eventIndex, -momentum.get(eventIndex));
//        position.set(eventIndex, 0.0); // Exactly on boundary to avoid potential round-off error
//    }
//
//    private static void setZeroMomentum(WrappedVector momentum,
//                                        int gradientEventIndex) {
//
//        momentum.set(gradientEventIndex, 0.0); // Exactly zero on gradient event to avoid potential round-off error
//    }
//
//    private static void reflectVelocity(WrappedVector velocity,
//                                        int eventIndex) {
//
//        velocity.set(eventIndex, -velocity.get(eventIndex));
//    }
//
//    private void updateMomentum(WrappedVector momentum,
//                                WrappedVector gradient,
//                                WrappedVector action,
//                                double eventTime) {
//
//        final double[] m = momentum.getBuffer();
//        final double[] g = gradient.getBuffer();
//        final double[] a = action.getBuffer();
//
//        final double halfEventTimeSquared = eventTime * eventTime / 2;
//
//        for (int i = 0, len = m.length; i < len; ++i) {
//            m[i] += eventTime * g[i] - halfEventTimeSquared * a[i];
//        }
//
//        if (mask != null) {
//            applyMask(m);
//        }
//    }
}
