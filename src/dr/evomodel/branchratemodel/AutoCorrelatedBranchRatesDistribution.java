/*
 * AutoCorrelatedBranchRatesDistribution.java
 *
 * Copyright (c) 2002-2015 Alexei Drummond, Andrew Rambaut and Marc Suchard
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

package dr.evomodel.branchratemodel;

import dr.evolution.tree.NodeRef;
import dr.evolution.tree.Tree;
import dr.evomodel.tree.TreeModel;
import dr.inference.distribution.ParametricMultivariateDistributionModel;
import dr.inference.hmc.GradientWrtParameterProvider;
import dr.inference.model.*;
import dr.math.MultivariateFunction;
import dr.math.NumericalDerivative;
import dr.util.Author;
import dr.util.Citable;
import dr.util.Citation;
import dr.xml.Reportable;

import java.util.Collections;
import java.util.List;

/**
 * @author Marc A. Suchard
 * @author Xiang Ji
 * @author Philippe Lemey
 */
public class AutoCorrelatedBranchRatesDistribution extends AbstractModelLikelihood
        implements GradientWrtParameterProvider, Citable, Reportable {

    private final ArbitraryBranchRates branchRateModel;
    private final ParametricMultivariateDistributionModel distribution;
    private final BranchVarianceScaling scaling;
    private final boolean log;

    private final Tree tree;
    private final Parameter rateParameter;

    private boolean incrementsKnown = false;
    private boolean savedIncrementsKnown;

    private boolean likelihoodKnown = false;
    private boolean savedLikelihoodKnown;

    private double logLikelihood;
    private double savedLogLikelihood;

    private final int dim;
    private double[] increments;
    private double[] savedIncrements;

    public AutoCorrelatedBranchRatesDistribution(String name,
                                                 ArbitraryBranchRates branchRateModel,
                                                 ParametricMultivariateDistributionModel distribution,
                                                 BranchVarianceScaling scaling,
                                                 boolean log) {
        super(name);
        this.branchRateModel = branchRateModel;
        this.distribution = distribution;
        this.scaling = scaling;
        this.log = log;

        this.tree = branchRateModel.getTree();
        this.rateParameter = branchRateModel.getRateParameter();

        addModel(branchRateModel);
        addModel(distribution);

        if (tree instanceof TreeModel) {
            addModel((TreeModel) tree);
        }

        this.dim = branchRateModel.getRateParameter().getDimension();
        this.increments = new double[dim];
        this.savedIncrements = new double[dim];

        if (dim != distribution.getMean().length) {
            throw new RuntimeException("Dimension mismatch in AutoCorrelatedRatesDistribution. " +
                    dim + " != " + distribution.getMean().length);
        }
    }

    @Override
    public Likelihood getLikelihood() {
        return this;
    }

    @Override
    public Parameter getParameter() {
        return rateParameter;
    }

    @Override
    public int getDimension() {
        return rateParameter.getDimension();
    }

    @Override
    public double[] getGradientLogDensity() {

        if (!(distribution instanceof GradientProvider)) {
            throw new RuntimeException("Not yet implemented");
        }

        GradientProvider incrementGradientProvider = (GradientProvider) distribution;
        checkIncrements();
        double[] gradientWrtIncrement = incrementGradientProvider.getGradientLogDensity(increments);

        for (int i = 0; i < dim; i++) {
            NodeRef node = tree.getNode(i);
            if (!tree.isRoot(node)) {
                gradientWrtIncrement[i] = scaling.rescaleIncrement(gradientWrtIncrement[i], tree.getBranchLength(node));
            }
        }

        double[] gradientWrtBranch = new double[dim];
        recurseGradientPreOrder(tree.getRoot(), gradientWrtBranch, gradientWrtIncrement);
        return gradientWrtBranch;
    }

    @Override
    protected void handleModelChangedEvent(Model model, Object object, int index) {
        incrementsKnown = false;
        likelihoodKnown = false;
    }

    @Override
    protected void handleVariableChangedEvent(Variable variable, int index, Parameter.ChangeType type) {
        likelihoodKnown = false;
    }

    @Override
    protected void storeState() {
        savedIncrementsKnown = incrementsKnown;
        System.arraycopy(increments, 0, savedIncrements, 0, dim);

        savedLikelihoodKnown = likelihoodKnown;
        savedLogLikelihood = logLikelihood;
    }

    @Override
    protected void restoreState() {
        incrementsKnown = savedIncrementsKnown;
        double[] tmp = savedIncrements;
        savedIncrements = increments;
        increments = tmp;

        likelihoodKnown = savedLikelihoodKnown;
        logLikelihood = savedLogLikelihood;
    }

    @Override
    protected void acceptState() {

    }

    public double getIncrement(int index) {
        checkIncrements();
        return increments[index];
    }

    @Override
    public Model getModel() {
        return this;
    }

    @Override
    public double getLogLikelihood() {
        if (!likelihoodKnown) {
            logLikelihood = calculateLogLikelihood();
        }
        return logLikelihood;
    }

    @Override
    public void makeDirty() {
        likelihoodKnown = false;
    }

    @Override
    public Citation.Category getCategory() {
        return null;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public List<Citation> getCitations() {
        return Collections.singletonList(CITATION);
    }

    public static Citation CITATION = new Citation(
            new Author[]{
            },
            Citation.Status.IN_PREPARATION
    );

    private void checkIncrements() {
        if (!incrementsKnown) {
            recursePreOrder(tree.getRoot(), 0.0);
            incrementsKnown = true;
        }
    }

    private double calculateLogLikelihood() {
        checkIncrements();
        return distribution.logPdf(increments);
    }

    private void recursePreOrder(NodeRef node, double untransformedParentRate) {

        if (!tree.isRoot(node)) {
            final double untransformedRate = transform(branchRateModel.getUntransformedBranchRate(tree, node));
            final double branchLength = tree.getBranchLength(node);
            final double rateIncrement = scaling.rescaleIncrement(
                    untransformedRate - untransformedParentRate, branchLength);

            increments[branchRateModel.getParameterIndexFromNode(node)] = rateIncrement;

            untransformedParentRate = untransformedRate;
        }

        if (!tree.isExternal(node)) {
            recursePreOrder(tree.getChild(node, 0), untransformedParentRate);
            recursePreOrder(tree.getChild(node, 1), untransformedParentRate);
        }
    }

    private void recurseGradientPreOrder(NodeRef node,
                                         double[] gradientWrtBranch,
                                         double[] gradientWrtIncrement) {

        int index = branchRateModel.getParameterIndexFromNode(node);

        if (!tree.isRoot(node)) {
            gradientWrtBranch[index] += gradientWrtIncrement[index];
        }

        if (!tree.isExternal(node)) {

            NodeRef child0 = tree.getChild(node, 0);
            NodeRef child1 = tree.getChild(node, 1);

            if (!tree.isRoot(node)) {
                gradientWrtBranch[index] -= gradientWrtIncrement[branchRateModel.getParameterIndexFromNode(child0)];
                gradientWrtBranch[index] -= gradientWrtIncrement[branchRateModel.getParameterIndexFromNode(child1)];
            }

            recurseGradientPreOrder(child0, gradientWrtBranch, gradientWrtIncrement);
            recurseGradientPreOrder(child1, gradientWrtBranch, gradientWrtIncrement);
        }
    }

    private double transform(double x) {
        return log ? Math.log(x) : x;
    }

    private MultivariateFunction numeric1 = new MultivariateFunction() {
        @Override
        public double evaluate(double[] argument) {

            for (int i = 0; i < argument.length; ++i) {
                rateParameter.setParameterValue(i, argument[i]);
            }

            makeDirty();
            return getLogLikelihood();
        }

        @Override
        public int getNumArguments() {
            return rateParameter.getDimension();
        }

        @Override
        public double getLowerBound(int n) {
            return 0;
        }

        @Override
        public double getUpperBound(int n) {
            return Double.POSITIVE_INFINITY;
        }
    };

    public double[] getNumericalGradient() {
        double[] savedValues = rateParameter.getParameterValues();
        double[] testGradient = NumericalDerivative.gradient(numeric1, rateParameter.getParameterValues());
        for (int i = 0; i < savedValues.length; ++i) {
            rateParameter.setParameterValue(i, savedValues[i]);
        }

        return testGradient;
    }

    @Override
    public String getReport() {

        double[] testGradient = getNumericalGradient();

        StringBuilder sb = new StringBuilder();
        sb.append("Peeling: ").append(new dr.math.matrixAlgebra.Vector(getGradientLogDensity()));
        sb.append("\n");
        sb.append("numeric: ").append(new dr.math.matrixAlgebra.Vector(testGradient));
        sb.append("\n");

        return sb.toString();
    }


    public enum BranchVarianceScaling {

        NONE("none") {
            @Override
            double rescaleIncrement(double increment, double branchLength) {
                return increment;
            }
        },

        BY_TIME("byTime") {
            @Override
            double rescaleIncrement(double increment, double branchLength) {
                return increment / Math.sqrt(branchLength);
            }
        };

        BranchVarianceScaling(String name) {
            this.name = name;
        }

        private final String name;

        abstract double rescaleIncrement(double increment, double branchLength);

        public String getName() {
            return name;
        }

        public static BranchVarianceScaling parse(String name) {
            for (BranchVarianceScaling scaling : BranchVarianceScaling.values()) {
                if (scaling.getName().equalsIgnoreCase(name)) {
                    return scaling;
                }
            }
            return null;
        }
    }
}