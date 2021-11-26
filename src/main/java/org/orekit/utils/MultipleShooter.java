/* Copyright 2002-2021 CS GROUP
 * Licensed to CS GROUP (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.integration.IntegrableGenerator;
import org.orekit.propagation.numerical.DerivativesWrtThirdBodyEpoch;
import org.orekit.propagation.numerical.NumericalPropagator;

/**
 * Multiple shooting method applicable for trajectories, in an ephemeris model.
 * Not suited for closed orbits.
 * @see "TRAJECTORY DESIGN AND ORBIT MAINTENANCE STRATEGIES IN MULTI-BODY DYNAMICAL REGIMES by Thomas A. Pavlak, Purdue University"
 * @author William Desprats
 * @since 10.2
 */
public class MultipleShooter extends AbstractMultipleShooting {

    /** Simple Constructor.
     * <p> Standard constructor for multiple shooting which can be used with the CR3BP model.</p>
     * @param initialGuessList initial patch points to be corrected.
     * @param propagatorList list of propagators associated to each patch point.
     * @param additionalEquations list of additional equations linked to propagatorList.
     * @param arcDuration initial guess of the duration of each arc.
     * @param tolerance convergence tolerance on the constraint vector
     * @deprecated as of 11.1, replaced by {@link #MultipleShooter(List, List, double, double, List)}
     */
    @SuppressWarnings("deprecation")
    @Deprecated
    public MultipleShooter(final List<SpacecraftState> initialGuessList, final List<NumericalPropagator> propagatorList,
                           final List<org.orekit.propagation.integration.AdditionalEquations> additionalEquations,
                           final double arcDuration, final double tolerance) {
        this(initialGuessList, propagatorList, arcDuration, tolerance,
             additionalEquations.stream().map(ae -> new org.orekit.propagation.integration.AdditionalEquationAdapter(ae)).collect(Collectors.toList()));
    }

    /** Simple Constructor.
     * <p> Standard constructor for multiple shooting which can be used with the CR3BP model.</p>
     * @param initialGuessList initial patch points to be corrected.
     * @param propagatorList list of propagators associated to each patch point.
     * @param additionalEquations list of additional equations linked to propagatorList.
     * @param arcDuration initial guess of the duration of each arc.
     * @param tolerance convergence tolerance on the constraint vector
     * @since 11.1
     */
    public MultipleShooter(final List<SpacecraftState> initialGuessList, final List<NumericalPropagator> propagatorList,
                           final double arcDuration, final double tolerance,
                           final List<IntegrableGenerator> additionalEquations) {
        super(initialGuessList, propagatorList, arcDuration, tolerance, additionalEquations, "derivatives");
    }

    /** {@inheritDoc} */
    protected SpacecraftState getAugmentedInitialState(final SpacecraftState initialState,
                                                       final IntegrableGenerator additionalEquation) {
        return ((DerivativesWrtThirdBodyEpoch) additionalEquation).setInitialJacobians(initialState);
    }

    /** {@inheritDoc} */
    protected double[][] computeAdditionalJacobianMatrix(final List<SpacecraftState> propagatedSP) {
        final Map<Integer, Double> mapConstraints = getConstraintsMap();

        final int n = mapConstraints.size();
        final int ncolumns = getNumberOfFreeVariables() - 1;

        final double[][] M = new double[n][ncolumns];

        int k = 0;
        for (int index : mapConstraints.keySet()) {
            M[k][index] = 1;
            k++;
        }
        return M;
    }

    /** {@inheritDoc} */
    protected double[] computeAdditionalConstraints(final List<SpacecraftState> propagatedSP) {
        // The additional constraint vector has the following form :

        //           [ y1i - y1d ]---- other constraints (component of
        // Fadd(X) = [    ...    ]    | a patch point eaquals to a
        //           [vz2i - vz2d]----  desired value)

        // Number of additional constraints
        final int      n             = getConstraintsMap().size();
        final double[] fxAdditionnal = new double[n];

        // Update additional constraints
        updateAdditionalConstraints(0, fxAdditionnal);
        return fxAdditionnal;
    }

}
