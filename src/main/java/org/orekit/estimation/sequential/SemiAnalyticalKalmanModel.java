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
package org.orekit.estimation.sequential;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hipparchus.exception.MathRuntimeException;
import org.hipparchus.filtering.kalman.ProcessEstimate;
import org.hipparchus.filtering.kalman.extended.ExtendedKalmanFilter;
import org.hipparchus.filtering.kalman.extended.NonLinearEvolution;
import org.hipparchus.filtering.kalman.extended.NonLinearProcess;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.ArrayRealVector;
import org.hipparchus.linear.MatrixUtils;
import org.hipparchus.linear.QRDecomposition;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.hipparchus.util.FastMath;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitMessages;
import org.orekit.estimation.measurements.EstimatedMeasurement;
import org.orekit.estimation.measurements.EstimationModifier;
import org.orekit.estimation.measurements.ObservedMeasurement;
import org.orekit.estimation.measurements.modifiers.DynamicOutlierFilter;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.propagation.PropagationType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.conversion.DSSTPropagatorBuilder;
import org.orekit.propagation.semianalytical.dsst.DSSTJacobiansMapper;
import org.orekit.propagation.semianalytical.dsst.DSSTPartialDerivativesEquations;
import org.orekit.propagation.semianalytical.dsst.DSSTPropagator;
import org.orekit.propagation.semianalytical.dsst.forces.DSSTForceModel;
import org.orekit.propagation.semianalytical.dsst.forces.ShortPeriodTerms;
import org.orekit.propagation.semianalytical.dsst.utilities.AuxiliaryElements;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.ChronologicalComparator;
import org.orekit.utils.ParameterDriver;
import org.orekit.utils.ParameterDriversList;
import org.orekit.utils.ParameterDriversList.DelegatingDriver;

/** Process model to use with a {@link SemiAnalyticalKalmanEstimator}.
 *
 * @see "Folcik Z., Orbit Determination Using Modern Filters/Smoothers and Continuous Thrust Modeling,
 *       Master of Science Thesis, Department of Aeronautics and Astronautics, MIT, June, 2008."
 *
 * @see "Cazabonne B., Bayard J., Journot M., and Cefola P. J., A Semi-analytical Approach for Orbit
 *       Determination based on Extended Kalman Filter, AAS Paper 21-614, AAS/AIAA Astrodynamics
 *       Specialist Conference, Big Sky, August 2021."
 *
 * @author Julie Bayard
 * @author Bryan Cazabonne
 * @author Maxime Journot
 */
public  class SemiAnalyticalKalmanModel implements KalmanEstimation, NonLinearProcess<MeasurementDecorator> {

    /** Builders for DSST propagator. */
    private final DSSTPropagatorBuilder builder;

    /** Estimated orbital parameters. */
    private final ParameterDriversList estimatedOrbitalParameters;

    /** Per-builder estimated propagation drivers. */
    private final ParameterDriversList estimatedPropagationParameters;

    /** Estimated measurements parameters. */
    private final ParameterDriversList estimatedMeasurementsParameters;

    /** Map for propagation parameters columns. */
    private final Map<String, Integer> propagationParameterColumns;

    /** Map for measurements parameters columns. */
    private final Map<String, Integer> measurementParameterColumns;

    /** Scaling factors. */
    private final double[] scale;

    /** Provider for covariance matrix. */
    private final CovarianceMatrixProvider covarianceMatrixProvider;

    /** Process noise matrix provider for measurement parameters. */
    private final CovarianceMatrixProvider measurementProcessNoiseMatrix;

    /** Mappers for extracting Jacobians from integrated states. */
    private DSSTJacobiansMapper mapper;

    /** Propagators for the reference trajectories, up to current date. */
    private DSSTPropagator dsstPropagator;

    /** Observer to retrieve current estimation info. */
    private KalmanObserver observer;

    /** Current number of measurement. */
    private int currentMeasurementNumber;

    /** Current date. */
    private AbsoluteDate currentDate;

    /** Predicted mean element filter correction. */
    private RealVector predictedFilterCorrection;

    /** Corrected mean element filter correction. */
    private RealVector correctedFilterCorrection;

    /** Predicted measurement. */
    private EstimatedMeasurement<?> predictedMeasurement;

    /** Nominal mean spacecraft state. */
    private SpacecraftState nominalMeanSpacecraftState;

    /** Previous nominal mean spacecraft state. */
    private SpacecraftState previousNominalMeanSpacecraftState;

    /** Current corrected estimate. */
    private ProcessEstimate correctedEstimate;

    /** Inverse of the orbital part of the state transition matrix. */
    private RealMatrix phiS;

    /** Propagation parameters part of the state transition matrix. */
    private RealMatrix psiS;

    /** Kalman process model constructor (package private).
     * @param propagatorBuilder propagators builders used to evaluate the orbits.
     * @param covarianceMatrixProvider provider for covariance matrix
     * @param estimatedMeasurementParameters measurement parameters to estimate
     * @param measurementProcessNoiseMatrix provider for measurement process noise matrix
     * @param observer observer for Kalman Filter estimations
     */
    protected SemiAnalyticalKalmanModel(final DSSTPropagatorBuilder propagatorBuilder,
    		                            final CovarianceMatrixProvider covarianceMatrixProvider,
                                        final ParameterDriversList estimatedMeasurementParameters,
                                        final CovarianceMatrixProvider measurementProcessNoiseMatrix) {

        this.builder                         = propagatorBuilder;
        this.estimatedMeasurementsParameters = estimatedMeasurementParameters;
        this.measurementParameterColumns     = new HashMap<>(estimatedMeasurementsParameters.getDrivers().size());
        this.observer                        = null;
        this.currentMeasurementNumber        = 0;
        this.currentDate                     = propagatorBuilder.getInitialOrbitDate();
        this.covarianceMatrixProvider        = covarianceMatrixProvider;
        this.measurementProcessNoiseMatrix   = measurementProcessNoiseMatrix;

        // Number of estimated parameters
        int columns = 0;

        // Set estimated orbital parameters
        estimatedOrbitalParameters = new ParameterDriversList();
        for (final ParameterDriver driver : builder.getOrbitalParametersDrivers().getDrivers()) {

        	// Verify if the driver reference date has been set
            if (driver.getReferenceDate() == null) {
                driver.setReferenceDate(currentDate);
            }

            // Verify if the driver is selected
            if (driver.isSelected()) {
            	estimatedOrbitalParameters.add(driver);
                columns++;
            }

        }

        // Set estimated propagation parameters
        estimatedPropagationParameters = new ParameterDriversList();
        final List<String> estimatedPropagationParametersNames = new ArrayList<>();
        for (final ParameterDriver driver : builder.getPropagationParametersDrivers().getDrivers()) {
        
            // Verify if the driver reference date has been set
            if (driver.getReferenceDate() == null) {
                driver.setReferenceDate(currentDate);
            }
        
            // Verify if the driver is selected
            if (driver.isSelected()) {
            	estimatedPropagationParameters.add(driver);
                final String driverName = driver.getName();
                // Add the driver name if it has not been added yet
                if (!estimatedPropagationParametersNames.contains(driverName)) {
                    estimatedPropagationParametersNames.add(driverName);
                }
                columns++;
            }
        
        }
        estimatedPropagationParametersNames.sort(Comparator.naturalOrder());
        
        // Populate the map of propagation drivers' columns and update the total number of columns
        propagationParameterColumns = new HashMap<>(estimatedPropagationParametersNames.size());
        for (final String driverName : estimatedPropagationParametersNames) {
            propagationParameterColumns.put(driverName, columns);
            ++columns;
        }

        // Set the estimated measurement parameters
        for (final ParameterDriver parameter : estimatedMeasurementsParameters.getDrivers()) {
            if (parameter.getReferenceDate() == null) {
                parameter.setReferenceDate(currentDate);
            }
            measurementParameterColumns.put(parameter.getName(), columns);
            ++columns;
        }
        
        // Compute the scale factors
        this.scale = new double[columns];
        int index = 0;
        for (final ParameterDriver driver : estimatedOrbitalParameters.getDrivers()) {
            scale[index++] = driver.getScale();
        }
        for (final ParameterDriver driver : estimatedPropagationParameters.getDrivers()) {
            scale[index++] = driver.getScale();
        }
        for (final ParameterDriver driver : estimatedMeasurementsParameters.getDrivers()) {
            scale[index++] = driver.getScale();
        }

        // Build the reference propagator and add its partial derivatives equations implementation
        updateReferenceTrajectory(getEstimatedPropagator());
        this.nominalMeanSpacecraftState = dsstPropagator.getInitialState();
        this.previousNominalMeanSpacecraftState = nominalMeanSpacecraftState;

        // Initialize "field" short periodic terms
        mapper.initializeFieldShortPeriodTerms(nominalMeanSpacecraftState);

        // Initialize the estimated normalized mean element filter correction (See Ref [1], Eq. 3.2a)
        this.predictedFilterCorrection = MatrixUtils.createRealVector(columns);
        this.correctedFilterCorrection = predictedFilterCorrection;

        // Initialize propagation parameters part of the state transition matrix (See Ref [1], Eq. 3.2c)
        this.psiS = null;
        if (estimatedPropagationParameters.getNbParams() != 0) {
        	this.psiS = MatrixUtils.createRealMatrix(getNumberSelectedOrbitalDrivers(),
        		                                     getNumberSelectedPropagationDrivers());
        }

        // Initialize inverse of the orbital part of the state transition matrix (See Ref [1], Eq. 3.2d)
        this.phiS = MatrixUtils.createRealIdentityMatrix(getNumberSelectedOrbitalDrivers());
        
        // Number of estimated measurement parameters
        final int nbMeas = getNumberSelectedMeasurementDrivers();

        // Number of estimated dynamic parameters (orbital + propagation)
        final int nbDyn  = getNumberSelectedOrbitalDrivers() + getNumberSelectedPropagationDrivers();

        // Covariance matrix
        final RealMatrix noiseK = MatrixUtils.createRealMatrix(nbDyn + nbMeas, nbDyn + nbMeas);
        final RealMatrix noiseP = covarianceMatrixProvider.getInitialCovarianceMatrix(nominalMeanSpacecraftState);
        noiseK.setSubMatrix(noiseP.getData(), 0, 0);
        if (measurementProcessNoiseMatrix != null) {
            final RealMatrix noiseM = measurementProcessNoiseMatrix.getInitialCovarianceMatrix(nominalMeanSpacecraftState);
            noiseK.setSubMatrix(noiseM.getData(), nbDyn, nbDyn);
        }

        // Verify dimension
        checkDimension(noiseK.getRowDimension(),
                       builder.getOrbitalParametersDrivers(),
                       builder.getPropagationParametersDrivers(),
                       estimatedMeasurementsParameters);

        final RealMatrix correctedCovariance = normalizeCovarianceMatrix(noiseK);

        // Initialize corrected estimate
        this.correctedEstimate = new ProcessEstimate(0.0, correctedFilterCorrection, correctedCovariance);

    }

    /** Get the observer for Kalman Filter estimations.
     * @return the observer for Kalman Filter estimations
     */
    public KalmanObserver getObserver() {
    	return observer;
    }

    /** Set the observer.
     * @param observer the observer
     */
    public void setObserver(final KalmanObserver observer) {
        this.observer = observer;
    }

    /** Get the current corrected estimate.
     * @return current corrected estimate
     */
    public ProcessEstimate getEstimate() {
        return correctedEstimate;
    }

    /** Process a single measurement.
     * <p>
     * Update the filter with the new measurements.
     * </p>
     * @param observedMeasurements the list of measurements to process
     * @param filter Extended Kalman Filter
     * @return estimated propagator
     */
    public DSSTPropagator processMeasurements(final List<ObservedMeasurement<?>> observedMeasurements,
    	                                      final ExtendedKalmanFilter<MeasurementDecorator> filter) {
        try {

        	// Sort the measurement
        	observedMeasurements.sort(new ChronologicalComparator());

        	// Initialize step handler and set it to the propagator
            final EskfMeasurementHandler stepHandler = new EskfMeasurementHandler(this, filter, observedMeasurements, builder.getInitialOrbitDate());
            dsstPropagator.setMasterMode(stepHandler);
            dsstPropagator.propagate(observedMeasurements.get(0).getDate(), observedMeasurements.get(observedMeasurements.size() - 1).getDate());

            // Return the last estimated propagator
            return getEstimatedPropagator();

        } catch (MathRuntimeException mrte) {
            throw new OrekitException(mrte);
        }
    }

    /** Get the propagator estimated with the values set in the propagator builder.
     * @return propagator based on the current values in the builder
     */
    public DSSTPropagator getEstimatedPropagator() {
        // Return propagator built with current instantiation of the propagator builder
        return builder.buildPropagator(builder.getSelectedNormalizedParameters());
    }

    /** {@inheritDoc} */
	@Override
	public NonLinearEvolution getEvolution(final double previousTime, final RealVector previousState,
			                               final MeasurementDecorator measurement) {

        // Set a reference date for all measurements parameters that lack one (including the not estimated ones)
        final ObservedMeasurement<?> observedMeasurement = measurement.getObservedMeasurement();
        for (final ParameterDriver driver : observedMeasurement.getParametersDrivers()) {
            if (driver.getReferenceDate() == null) {
                driver.setReferenceDate(builder.getInitialOrbitDate());
            }
        }

        // Increment measurement number
        ++currentMeasurementNumber;

        // Update the current date
        currentDate = measurement.getObservedMeasurement().getDate();

        // Normalized state transition matrix
        final RealMatrix stm = getErrorStateTransitionMatrix();

        // Predict filter correction
        predictedFilterCorrection = predictFilterCorrection(stm);

        // Short period term derivatives
        analyticalDerivativeComputations(nominalMeanSpacecraftState);

        // Calculate the predicted osculating elements
        final double[] osculating = computeOsculatingElements();
        final Orbit osculatingOrbit = OrbitType.EQUINOCTIAL.mapArrayToOrbit(osculating, null, builder.getPositionAngle(),
                                                                            currentDate, nominalMeanSpacecraftState.getMu(),
                                                                            nominalMeanSpacecraftState.getFrame());

        // Compute the predicted measurements  (See Ref [1], Eq. 3.8)
        predictedMeasurement = observedMeasurement.estimate(currentMeasurementNumber,
        		                                            currentMeasurementNumber,
        		                                            new SpacecraftState[] {
        		                                            		new SpacecraftState(osculatingOrbit,
        		                                            				nominalMeanSpacecraftState.getAttitude(),
                		                                            		nominalMeanSpacecraftState.getMass(),
                		                                            		nominalMeanSpacecraftState.getAdditionalStates())
        		                                            });

        // Normalized measurement matrix
        final RealMatrix measurementMatrix = getMeasurementMatrix();

        // Number of estimated measurement parameters
        final int nbMeas = getNumberSelectedMeasurementDrivers();

        // Number of estimated dynamic parameters (orbital + propagation)
        final int nbDyn  = getNumberSelectedOrbitalDrivers() + getNumberSelectedPropagationDrivers();

        // Covariance matrix
        final RealMatrix noiseK = MatrixUtils.createRealMatrix(nbDyn + nbMeas, nbDyn + nbMeas);
        final RealMatrix noiseP = covarianceMatrixProvider.
                                  getProcessNoiseMatrix(previousNominalMeanSpacecraftState,
                                		                nominalMeanSpacecraftState);
        noiseK.setSubMatrix(noiseP.getData(), 0, 0);
        if (measurementProcessNoiseMatrix != null) {
            final RealMatrix noiseM = measurementProcessNoiseMatrix.getProcessNoiseMatrix(previousNominalMeanSpacecraftState,
            		                                                                      nominalMeanSpacecraftState);
            noiseK.setSubMatrix(noiseM.getData(), nbDyn, nbDyn);
        }

        // Verify dimension
        checkDimension(noiseK.getRowDimension(),
                       builder.getOrbitalParametersDrivers(),
                       builder.getPropagationParametersDrivers(),
                       estimatedMeasurementsParameters);

        final RealMatrix normalizedProcessNoise = normalizeCovarianceMatrix(noiseK);

        // Return
		return new NonLinearEvolution(measurement.getTime(), predictedFilterCorrection, stm,
				                      normalizedProcessNoise, measurementMatrix);
	}

	/** {@inheritDoc} */
	@Override
	public RealVector getInnovation(final MeasurementDecorator measurement, final NonLinearEvolution evolution,
			                        final RealMatrix innovationCovarianceMatrix) {

        // Apply the dynamic outlier filter, if it exists
        applyDynamicOutlierFilter(predictedMeasurement, innovationCovarianceMatrix);
        if (predictedMeasurement.getStatus() == EstimatedMeasurement.Status.REJECTED)  {
            // set innovation to null to notify filter measurement is rejected
            return null;
        } else {
            // Normalized innovation of the measurement (Nx1)
            final double[] observed  = predictedMeasurement.getObservedMeasurement().getObservedValue();
            final double[] estimated = predictedMeasurement.getEstimatedValue();
            final double[] sigma     = predictedMeasurement.getObservedMeasurement().getTheoreticalStandardDeviation();
            final double[] residuals = new double[observed.length];

            for (int i = 0; i < observed.length; i++) {
                residuals[i] = (observed[i] - estimated[i]) / sigma[i];
            }
            return MatrixUtils.createRealVector(residuals);
        }

	}

    /** Finalize estimation.
     * @param observedMeasurement measurement that has just been processed
     * @param estimate corrected estimate
     */
    public void finalizeEstimation(final ObservedMeasurement<?> observedMeasurement,
                                   final ProcessEstimate estimate) {
        // Update the process estimate
        correctedEstimate = estimate;
        // Corrected filter correction
        correctedFilterCorrection = estimate.getState();
        // Update the previous nominal mean spacecraft state
        previousNominalMeanSpacecraftState = nominalMeanSpacecraftState;
    }

    /** Finalize estimation operations on the observation grid. */
    public void finalizeOperationsObservationGrid() {
    	// Update parameters
    	updateParameters();
    }

	/** {@inheritDoc} */
	@Override
	public ParameterDriversList getEstimatedOrbitalParameters() {
		return estimatedOrbitalParameters;
	}

	/** {@inheritDoc} */
	@Override
	public ParameterDriversList getEstimatedPropagationParameters() {
		return estimatedPropagationParameters;
	}

	/** {@inheritDoc} */
	@Override
	public ParameterDriversList getEstimatedMeasurementsParameters() {
		return estimatedMeasurementsParameters;
	}

	/** {@inheritDoc} */
	@Override
	public SpacecraftState[] getPredictedSpacecraftStates() {
		return new SpacecraftState[] {nominalMeanSpacecraftState};
	}

	/** {@inheritDoc} */
	@Override
	public SpacecraftState[] getCorrectedSpacecraftStates() {
		return new SpacecraftState[] {getEstimatedPropagator().getInitialState()};
	}

	/** {@inheritDoc} */
	@Override
	public RealVector getPhysicalEstimatedState() {
        // Method {@link ParameterDriver#getValue()} is used to get
        // the physical values of the state.
        // The scales'array is used to get the size of the state vector
        final RealVector physicalEstimatedState = new ArrayRealVector(scale.length);
        int i = 0;
        for (final DelegatingDriver driver : getEstimatedOrbitalParameters().getDrivers()) {
            physicalEstimatedState.setEntry(i++, driver.getValue());
        }
        for (final DelegatingDriver driver : getEstimatedPropagationParameters().getDrivers()) {
            physicalEstimatedState.setEntry(i++, driver.getValue());
        }
        for (final DelegatingDriver driver : getEstimatedMeasurementsParameters().getDrivers()) {
            physicalEstimatedState.setEntry(i++, driver.getValue());
        }

        return physicalEstimatedState;
	}

	/** {@inheritDoc} */
	@Override
	public RealMatrix getPhysicalEstimatedCovarianceMatrix() {
        // Un-normalize the estimated covariance matrix (P) from Hipparchus and return it.
        // The covariance P is an mxm matrix where m = nbOrb + nbPropag + nbMeas
        // For each element [i,j] of P the corresponding normalized value is:
        // Pn[i,j] = P[i,j] / (scale[i]*scale[j])
        // Consequently: P[i,j] = Pn[i,j] * scale[i] * scale[j]

        // Normalized covariance matrix
        final RealMatrix normalizedP = correctedEstimate.getCovariance();

        // Initialize physical covariance matrix
        final int nbParams = normalizedP.getRowDimension();
        final RealMatrix physicalP = MatrixUtils.createRealMatrix(nbParams, nbParams);

        // Un-normalize the covairance matrix
        for (int i = 0; i < nbParams; ++i) {
            for (int j = 0; j < nbParams; ++j) {
                physicalP.setEntry(i, j, normalizedP.getEntry(i, j) * scale[i] * scale[j]);
            }
        }
        return physicalP;
	}

	/** {@inheritDoc} */
	@Override
	public RealMatrix getPhysicalStateTransitionMatrix() {
        //  Un-normalize the state transition matrix (φ) from Hipparchus and return it.
        // φ is an mxm matrix where m = nbOrb + nbPropag + nbMeas
        // For each element [i,j] of normalized φ (φn), the corresponding physical value is:
        // φ[i,j] = φn[i,j] * scale[i] / scale[j]

        // Normalized matrix
        final RealMatrix normalizedSTM = correctedEstimate.getStateTransitionMatrix();

        if (normalizedSTM == null) {
            return null;
        } else {
            // Initialize physical matrix
            final int nbParams = normalizedSTM.getRowDimension();
            final RealMatrix physicalSTM = MatrixUtils.createRealMatrix(nbParams, nbParams);

            // Un-normalize the matrix
            for (int i = 0; i < nbParams; ++i) {
                for (int j = 0; j < nbParams; ++j) {
                    physicalSTM.setEntry(i, j,
                                         normalizedSTM.getEntry(i, j) * scale[i] / scale[j]);
                }
            }
            return physicalSTM;
        }
	}

	/** {@inheritDoc} */
	@Override
	public RealMatrix getPhysicalMeasurementJacobian() {
        // Un-normalize the measurement matrix (H) from Hipparchus and return it.
        // H is an nxm matrix where:
        //  - m = nbOrb + nbPropag + nbMeas is the number of estimated parameters
        //  - n is the size of the measurement being processed by the filter
        // For each element [i,j] of normalized H (Hn) the corresponding physical value is:
        // H[i,j] = Hn[i,j] * σ[i] / scale[j]

        // Normalized matrix
        final RealMatrix normalizedH = correctedEstimate.getMeasurementJacobian();

        if (normalizedH == null) {
            return null;
        } else {
            // Get current measurement sigmas
            final double[] sigmas = predictedMeasurement.getObservedMeasurement().getTheoreticalStandardDeviation();

            // Initialize physical matrix
            final int nbLine = normalizedH.getRowDimension();
            final int nbCol  = normalizedH.getColumnDimension();
            final RealMatrix physicalH = MatrixUtils.createRealMatrix(nbLine, nbCol);

            // Un-normalize the matrix
            for (int i = 0; i < nbLine; ++i) {
                for (int j = 0; j < nbCol; ++j) {
                    physicalH.setEntry(i, j, normalizedH.getEntry(i, j) * sigmas[i] / scale[j]);
                }
            }
            return physicalH;
        }
	}

	/** {@inheritDoc} */
	@Override
	public RealMatrix getPhysicalInnovationCovarianceMatrix() {
        // Un-normalize the innovation covariance matrix (S) from Hipparchus and return it.
        // S is an nxn matrix where n is the size of the measurement being processed by the filter
        // For each element [i,j] of normalized S (Sn) the corresponding physical value is:
        // S[i,j] = Sn[i,j] * σ[i] * σ[j]

        // Normalized matrix
        final RealMatrix normalizedS = correctedEstimate.getInnovationCovariance();

        if (normalizedS == null) {
            return null;
        } else {
            // Get current measurement sigmas
            final double[] sigmas = predictedMeasurement.getObservedMeasurement().getTheoreticalStandardDeviation();

            // Initialize physical matrix
            final int nbMeas = sigmas.length;
            final RealMatrix physicalS = MatrixUtils.createRealMatrix(nbMeas, nbMeas);

            // Un-normalize the matrix
            for (int i = 0; i < nbMeas; ++i) {
                for (int j = 0; j < nbMeas; ++j) {
                    physicalS.setEntry(i, j, normalizedS.getEntry(i, j) * sigmas[i] *   sigmas[j]);
                }
            }
            return physicalS;
        }
	}

	/** {@inheritDoc} */
	@Override
	public RealMatrix getPhysicalKalmanGain() {
        // Un-normalize the Kalman gain (K) from Hipparchus and return it.
        // K is an mxn matrix where:
        //  - m = nbOrb + nbPropag + nbMeas is the number of estimated parameters
        //  - n is the size of the measurement being processed by the filter
        // For each element [i,j] of normalized K (Kn) the corresponding physical value is:
        // K[i,j] = Kn[i,j] * scale[i] / σ[j]

        // Normalized matrix
        final RealMatrix normalizedK = correctedEstimate.getKalmanGain();

        if (normalizedK == null) {
            return null;
        } else {
            // Get current measurement sigmas
            final double[] sigmas = predictedMeasurement.getObservedMeasurement().getTheoreticalStandardDeviation();

            // Initialize physical matrix
            final int nbLine = normalizedK.getRowDimension();
            final int nbCol  = normalizedK.getColumnDimension();
            final RealMatrix physicalK = MatrixUtils.createRealMatrix(nbLine, nbCol);

            // Un-normalize the matrix
            for (int i = 0; i < nbLine; ++i) {
                for (int j = 0; j < nbCol; ++j) {
                    physicalK.setEntry(i, j, normalizedK.getEntry(i, j) * scale[i] / sigmas[j]);
                }
            }
            return physicalK;
        }
	}

	/** {@inheritDoc} */
	@Override
	public int getCurrentMeasurementNumber() {
		return currentMeasurementNumber;
	}

	/** {@inheritDoc} */
	@Override
	public AbsoluteDate getCurrentDate() {
		return currentDate;
	}

	/** {@inheritDoc} */
	@Override
	public EstimatedMeasurement<?> getPredictedMeasurement() {
		return predictedMeasurement;
	}

	/** {@inheritDoc}
	 * In the Extended Semi-analytical Kalman Filter there is
	 * no corrected measurements. As a result, this method returns
	 * the predicted measurement.
	 */
	@Override
	public EstimatedMeasurement<?> getCorrectedMeasurement() {
		return predictedMeasurement;
	}

	/** Update the nominal spacecraft state.
	 * @param nominal nominal spacecraft state
	 */
	public void updateNominalSpacecraftState(final SpacecraftState nominal) {
		this.nominalMeanSpacecraftState = nominal;
		// Update the builder with the nominal mean elements orbit
		builder.resetOrbit(nominal.getOrbit(), PropagationType.MEAN);
	}

    /** Update the reference trajectories using the propagator as input.
     * @param propagator The new propagator to use
     */
    public void updateReferenceTrajectory(final DSSTPropagator propagator) {

    	dsstPropagator = propagator;

        // Link the partial derivatives to this new propagator
        final String equationName = SemiAnalyticalKalmanEstimator.class.getName() + "-derivatives-";
        final DSSTPartialDerivativesEquations pde = new DSSTPartialDerivativesEquations(equationName, dsstPropagator);

        // Mean state
        final SpacecraftState meanState = dsstPropagator.initialIsOsculating() ?
                       DSSTPropagator.computeMeanState(dsstPropagator.getInitialState(), dsstPropagator.getAttitudeProvider(), dsstPropagator.getAllForceModels()) :
                       dsstPropagator.getInitialState();

        // Reset the Jacobians
        final SpacecraftState rawState = meanState;
        final SpacecraftState stateWithDerivatives = pde.setInitialJacobians(rawState);
        dsstPropagator.setInitialState(stateWithDerivatives, PropagationType.MEAN);
        mapper = pde.getMapper();

    }

    /** Update the DSST short periodic terms.
     * @param state current mean state
     */
    public void updateShortPeriods(final SpacecraftState state) {
    	// Loop on DSST force models
    	for (final DSSTForceModel model : builder.getAllForceModels()) {
    		model.updateShortPeriodTerms(model.getParameters(), state);
    	}
    	mapper.updateFieldShortPeriodTerms(state);
    }

    /** Initialize the short periodic terms for the Kalman Filter.
     * @param meanState mean state for auxiliary elements
     */
    public void initializeShortPeriodicTerms(final SpacecraftState meanState) {
        final List<ShortPeriodTerms> shortPeriodTerms = new ArrayList<ShortPeriodTerms>();
        for (final DSSTForceModel force :  builder.getAllForceModels()) {
            shortPeriodTerms.addAll(force.initializeShortPeriodTerms(new AuxiliaryElements(meanState.getOrbit(), 1), PropagationType.OSCULATING, force.getParameters()));
        }
        dsstPropagator.setShortPeriodTerms(shortPeriodTerms);
    }

    /** Get the normalized state transition matrix (STM) from previous point to current point.
     * The STM contains the partial derivatives of current state with respect to previous state.
     * The  STM is an mxm matrix where m is the size of the state vector.
     * m = nbOrb + nbPropag + nbMeas
     * @return the normalized error state transition matrix
     */
    private RealMatrix getErrorStateTransitionMatrix() {

        /* The state transition matrix is obtained as follows, with:
         *  - Phi(k, k-1) : Transitional orbital matrix
         *  - Psi(k, k-1) : Transitional propagation parameters matrix
         *
         *       |             |             |   .    |
         *       | Phi(k, k-1) | Psi(k, k-1) | ..0..  |
         *       |             |             |   .    |
         *       |-------------|-------------|--------|
         *       |      .      |    1 0 0    |   .    |
         * STM = |    ..0..    |    0 1 0    | ..0..  |
         *       |      .      |    0 0 1    |   .    |
         *       |-------------|-------------|--------|
         *       |      .      |      .      | 1 0 0..|
         *       |    ..0..    |    ..0..    | 0 1 0..|
         *       |      .      |      .      | 0 0 1..|
         */

        // Initialize to the proper size identity matrix
        final RealMatrix stm = MatrixUtils.createRealIdentityMatrix(correctedEstimate.getState().getDimension());

        // Derivatives of the state vector with respect to initial state vector
        final int nbOrb = getNumberSelectedOrbitalDrivers();
        final double[][] aYY0 = new double[nbOrb][nbOrb];
        mapper.getB2(nominalMeanSpacecraftState, aYY0);
        final RealMatrix dYdY0 = new Array2DRowRealMatrix(aYY0, false);

        // Calculate transitional orbital matrix (See Ref [1], Eq. 3.4a)
        final RealMatrix phi = dYdY0.multiply(phiS);

        // Fill the state transition matrix with the orbital drivers
        final List<DelegatingDriver> drivers = builder.getOrbitalParametersDrivers().getDrivers();
        for (int i = 0; i < nbOrb; ++i) {
            if (drivers.get(i).isSelected()) {
                int jOrb = 0;
                for (int j = 0; j < nbOrb; ++j) {
                    if (drivers.get(j).isSelected()) {
                        stm.setEntry(i, jOrb++, phi.getEntry(i, j));
                    }
                }
            }
        }

        // Update PhiS
        phiS = new QRDecomposition(dYdY0).getSolver().getInverse();

        // Derivatives of the state vector with respect to propagation parameters
        if (psiS != null) {

        	final int nbProp = getNumberSelectedPropagationDrivers();
        	final double[][] aYPp = new double[nbOrb][nbProp];
            mapper.getB3(nominalMeanSpacecraftState, aYPp);
            final RealMatrix dYdPp = new Array2DRowRealMatrix(aYPp, false);

            // Calculate transitional parameters matrix (See Ref [1], Eq. 3.4b)
            final RealMatrix psi = dYdPp.subtract(phi.multiply(psiS));

            // Fill 1st row, 2nd column (dY/dPp)
            for (int i = 0; i < nbOrb; ++i) {
                for (int j = 0; j < nbProp; ++j) {
                    stm.setEntry(i, j + nbOrb, psi.getEntry(i, j));
                }
            }

            // Update PsiS
            psiS = dYdPp;

        }

        // Normalization of the STM
        // normalized(STM)ij = STMij*Sj/Si
        for (int i = 0; i < scale.length; i++) {
            for (int j = 0; j < scale.length; j++ ) {
                stm.setEntry(i, j, stm.getEntry(i, j) * scale[j] / scale[i]);
            }
        }

        // Return the error state transition matrix
        return stm;

    }

    /** Get the normalized measurement matrix H.
     * H contains the partial derivatives of the measurement with respect to the state.
     * H is an nxm matrix where n is the size of the measurement vector and m the size of the state vector.
     * @return the normalized measurement matrix H
     */
    private RealMatrix getMeasurementMatrix() {

        // Observed measurement characteristics
        final SpacecraftState        evaluationState     = predictedMeasurement.getStates()[0];
        final ObservedMeasurement<?> observedMeasurement = predictedMeasurement.getObservedMeasurement();
        final double[] sigma  = predictedMeasurement.getObservedMeasurement().getTheoreticalStandardDeviation();

        // Initialize measurement matrix H: nxm
        // n: Number of measurements in current measurement
        // m: State vector size
        final RealMatrix measurementMatrix = MatrixUtils.
                createRealMatrix(observedMeasurement.getDimension(),
                                 correctedEstimate.getState().getDimension());

        // Predicted orbit
        final Orbit predictedOrbit = evaluationState.getOrbit();

        // Measurement matrix's columns related to orbital and propagation parameters
        // ----------------------------------------------------------

        // Partial derivatives of the current Cartesian coordinates with respect to current orbital state
        final int nbOrb  = getNumberSelectedOrbitalDrivers();
        final int nbProp = getNumberSelectedPropagationDrivers();
        final double[][] aCY = new double[nbOrb][nbOrb];
        predictedOrbit.getJacobianWrtParameters(builder.getPositionAngle(), aCY);
        final RealMatrix dCdY = new Array2DRowRealMatrix(aCY, false);

        // Jacobian of the measurement with respect to current Cartesian coordinates
        final RealMatrix dMdC = new Array2DRowRealMatrix(predictedMeasurement.getStateDerivatives(0), false);

        // Jacobian of the measurement with respect to current orbital state
        RealMatrix dMdY = dMdC.multiply(dCdY);

        // Compute factor dShortPeriod_dMeanState = I+B1 | B4
        final RealMatrix IpB1B4 = MatrixUtils.createRealMatrix(nbOrb, nbOrb + nbProp);

    	// B1
        final double[][] aSptY = new double[nbOrb][nbOrb];
        mapper.getB1(aSptY);
        final RealMatrix B1 = new Array2DRowRealMatrix(aSptY, false);

        // I + B1
        final RealMatrix I = MatrixUtils.createRealIdentityMatrix(nbOrb);
        final RealMatrix IpB1 = I.add(B1);
        IpB1B4.setSubMatrix(IpB1.getData(), 0, 0);

        // If there are not propagation parameters, B4 is null
        if (psiS != null) {
            final double[][] aSptPp = new double[nbOrb][nbProp];
            mapper.getB4(aSptPp);
            final RealMatrix B4 = new Array2DRowRealMatrix(aSptPp, false);
            IpB1B4.setSubMatrix(B4.getData(), 0, nbOrb);

        }
        // Ref [1], Eq. 3.10
        dMdY = dMdY.multiply(IpB1B4);

        for (int i = 0; i < dMdY.getRowDimension(); i++) {
            for (int j = 0; j < nbOrb; j++) {
                final double driverScale = builder.getOrbitalParametersDrivers().getDrivers().get(j).getScale();
                measurementMatrix.setEntry(i, j, dMdY.getEntry(i, j) / sigma[i] * driverScale);
            }
            for (int j = 0; j < nbProp; j++) {
                final double driverScale = estimatedPropagationParameters.getDrivers().get(j).getScale();
                measurementMatrix.setEntry(i, j + nbOrb,
                                           dMdY.getEntry(i, j + nbOrb) / sigma[i] * driverScale);
            }
        }

        // Normalized measurement matrix's columns related to measurement parameters
        // --------------------------------------------------------------

        // Jacobian of the measurement with respect to measurement parameters
        // Gather the measurement parameters linked to current measurement
        for (final ParameterDriver driver : observedMeasurement.getParametersDrivers()) {
            if (driver.isSelected()) {
                // Derivatives of current measurement w/r to selected measurement parameter
                final double[] aMPm = predictedMeasurement.getParameterDerivatives(driver);

                // Check that the measurement parameter is managed by the filter
                if (measurementParameterColumns.get(driver.getName()) != null) {
                    // Column of the driver in the measurement matrix
                    final int driverColumn = measurementParameterColumns.get(driver.getName());

                    // Fill the corresponding indexes of the measurement matrix
                    for (int i = 0; i < aMPm.length; ++i) {
                        measurementMatrix.setEntry(i, driverColumn, aMPm[i] / sigma[i] * driver.getScale());
                    }
                }
            }
        }

        return measurementMatrix;
    }

    /** Predict the filter correction for the new observation.
     * @param stm normalized state transition matrix
     * @return the predicted filter correction for the new observation
     */
    private RealVector predictFilterCorrection(final RealMatrix stm) {
    	// Ref [1], Eq. 3.5a and 3.5b
    	return stm.operate(correctedFilterCorrection);
    }

    /** Compute the predicted osculating elements.
     * @return the predicted osculating element
     */
    private double[] computeOsculatingElements() {

    	// Number of estimated orbital parameters
    	final int nbOrb = getNumberSelectedOrbitalDrivers();

    	// B1
        final double[][] aSptY = new double[nbOrb][nbOrb];
        mapper.getB1(aSptY);
        final RealMatrix B1 = new Array2DRowRealMatrix(aSptY, false);

        // Short periodic terms
        final double[] shortPeriodTerms = dsstPropagator.getShortPeriodTermsValue(nominalMeanSpacecraftState);

        // Physical filter correction
        final RealVector physicalFilterCorrection = MatrixUtils.createRealVector(nbOrb);
        for (int index = 0; index < nbOrb; index++) {
        	physicalFilterCorrection.addToEntry(index, predictedFilterCorrection.getEntry(index) * scale[index]);
        }

        // B1 * physicalCorrection
        final RealVector B1Correction = B1.operate(physicalFilterCorrection);

        // Nominal mean elements
        final double[] nominalMeanElements = new double[nbOrb];
        OrbitType.EQUINOCTIAL.mapOrbitToArray(nominalMeanSpacecraftState.getOrbit(), builder.getPositionAngle(), nominalMeanElements, null);

        // Ref [1] Eq. 3.6
        final double[] osculatingElements = new double[nbOrb];
        for (int i = 0; i < nbOrb; i++) {
        	osculatingElements[i] = nominalMeanElements[i] +
        			                physicalFilterCorrection.getEntry(i) +
        			                shortPeriodTerms[i] +
        			                B1Correction.getEntry(i);
        }

        // Return
        return osculatingElements;

    }

    /** Analytical computation of derivatives.
     * This method allow to compute analytical derivatives.
     * @param state mean state used to calculate short period perturbations
     */
    private void analyticalDerivativeComputations(final SpacecraftState state) {
        mapper.setShortPeriodJacobians(state);
    }

    /** Normalize a covariance matrix.
     * The covariance P is an mxm matrix where m = nbOrb + nbPropag + nbMeas
     * For each element [i,j] of P the corresponding normalized value is:
     * Pn[i,j] = P[i,j] / (scale[i]*scale[j])
     * @param physicalCovarianceMatrix The "physical" covariance matrix in input
     * @return the normalized covariance matrix
     */
    private RealMatrix normalizeCovarianceMatrix(final RealMatrix physicalCovarianceMatrix) {

        // Initialize output matrix
        final int nbParams = physicalCovarianceMatrix.getRowDimension();
        final RealMatrix normalizedCovarianceMatrix = MatrixUtils.createRealMatrix(nbParams, nbParams);

        // Normalize the state matrix
        for (int i = 0; i < nbParams; ++i) {
            for (int j = 0; j < nbParams; ++j) {
                normalizedCovarianceMatrix.setEntry(i, j,
                                                    physicalCovarianceMatrix.getEntry(i, j) /
                                                    (scale[i] * scale[j]));
            }
        }
        return normalizedCovarianceMatrix;
    }

    /** Check dimension.
     * @param dimension dimension to check
     * @param orbitalParameters orbital parameters
     * @param propagationParameters propagation parameters
     * @param measurementParameters measurements parameters
     */
    private void checkDimension(final int dimension,
                                final ParameterDriversList orbitalParameters,
                                final ParameterDriversList propagationParameters,
                                final ParameterDriversList measurementParameters) {

        // count parameters, taking care of counting all orbital parameters
        // regardless of them being estimated or not
        int requiredDimension = orbitalParameters.getNbParams();
        for (final ParameterDriver driver : propagationParameters.getDrivers()) {
            if (driver.isSelected()) {
                ++requiredDimension;
            }
        }
        for (final ParameterDriver driver : measurementParameters.getDrivers()) {
            if (driver.isSelected()) {
                ++requiredDimension;
            }
        }

        if (dimension != requiredDimension) {
            // there is a problem, set up an explicit error message
            final StringBuilder strBuilder = new StringBuilder();
            for (final ParameterDriver driver : orbitalParameters.getDrivers()) {
                if (strBuilder.length() > 0) {
                    strBuilder.append(", ");
                }
                strBuilder.append(driver.getName());
            }
            for (final ParameterDriver driver : propagationParameters.getDrivers()) {
                if (driver.isSelected()) {
                    strBuilder.append(driver.getName());
                }
            }
            for (final ParameterDriver driver : measurementParameters.getDrivers()) {
                if (driver.isSelected()) {
                    strBuilder.append(driver.getName());
                }
            }
            throw new OrekitException(OrekitMessages.DIMENSION_INCONSISTENT_WITH_PARAMETERS,
                                      dimension, strBuilder.toString());
        }

    }

    /** Set and apply a dynamic outlier filter on a measurement.<p>
     * Loop on the modifiers to see if a dynamic outlier filter needs to be applied.<p>
     * Compute the sigma array using the matrix in input and set the filter.<p>
     * Apply the filter by calling the modify method on the estimated measurement.<p>
     * Reset the filter.
     * @param measurement measurement to filter
     * @param innovationCovarianceMatrix So called innovation covariance matrix S, with:<p>
     *        S = H.Ppred.Ht + R<p>
     *        Where:<p>
     *         - H is the normalized measurement matrix (Ht its transpose)<p>
     *         - Ppred is the normalized predicted covariance matrix<p>
     *         - R is the normalized measurement noise matrix
     * @param <T> the type of measurement
     */
    private <T extends ObservedMeasurement<T>> void applyDynamicOutlierFilter(final EstimatedMeasurement<T> measurement,
                                                                              final RealMatrix innovationCovarianceMatrix) {

        // Observed measurement associated to the predicted measurement
        final ObservedMeasurement<T> observedMeasurement = measurement.getObservedMeasurement();

        // Check if a dynamic filter was added to the measurement
        // If so, update its sigma value and apply it
        for (EstimationModifier<T> modifier : observedMeasurement.getModifiers()) {
            if (modifier instanceof DynamicOutlierFilter<?>) {
                final DynamicOutlierFilter<T> dynamicOutlierFilter = (DynamicOutlierFilter<T>) modifier;

                // Initialize the values of the sigma array used in the dynamic filter
                final double[] sigmaDynamic     = new double[innovationCovarianceMatrix.getColumnDimension()];
                final double[] sigmaMeasurement = observedMeasurement.getTheoreticalStandardDeviation();

                // Set the sigma value for each element of the measurement
                // Here we do use the value suggested by David A. Vallado (see [1]§10.6):
                // sigmaDynamic[i] = sqrt(diag(S))*sigma[i]
                // With S = H.Ppred.Ht + R
                // Where:
                //  - S is the measurement error matrix in input
                //  - H is the normalized measurement matrix (Ht its transpose)
                //  - Ppred is the normalized predicted covariance matrix
                //  - R is the normalized measurement noise matrix
                //  - sigma[i] is the theoretical standard deviation of the ith component of the measurement.
                //    It is used here to un-normalize the value before it is filtered
                for (int i = 0; i < sigmaDynamic.length; i++) {
                    sigmaDynamic[i] = FastMath.sqrt(innovationCovarianceMatrix.getEntry(i, i)) * sigmaMeasurement[i];
                }
                dynamicOutlierFilter.setSigma(sigmaDynamic);

                // Apply the modifier on the estimated measurement
                modifier.modify(measurement);

                // Re-initialize the value of the filter for the next measurement of the same type
                dynamicOutlierFilter.setSigma(null);
            }
        }
    }

    /** Get the number of estimated orbital parameters.
     * @return the number of estimated orbital parameters
     */
    private int getNumberSelectedOrbitalDrivers() {
    	return estimatedOrbitalParameters.getNbParams();
    }

    /** Get the number of estimated propagation parameters.
     * @return the number of estimated propagation parameters
     */
    private int getNumberSelectedPropagationDrivers() {
    	return estimatedPropagationParameters.getNbParams();
    }

    /** Get the number of estimated measurement parameters.
     * @return the number of estimated measurement parameters
     */
    private int getNumberSelectedMeasurementDrivers() {
    	return estimatedMeasurementsParameters.getNbParams();
    }

    /** Update the estimated parameters after the correction phase of the filter.
     * The min/max allowed values are handled by the parameter themselves.
     */
    private void updateParameters() {
    	final RealVector correctedState = correctedEstimate.getState();
        int i = 0;
        for (final DelegatingDriver driver : getEstimatedOrbitalParameters().getDrivers()) {
            // let the parameter handle min/max clipping
            driver.setNormalizedValue(driver.getNormalizedValue() + correctedState.getEntry(i++));
        }
        for (final DelegatingDriver driver : getEstimatedPropagationParameters().getDrivers()) {
            // let the parameter handle min/max clipping
            driver.setNormalizedValue(driver.getNormalizedValue() + correctedState.getEntry(i++));
        }
        for (final DelegatingDriver driver : getEstimatedMeasurementsParameters().getDrivers()) {
            // let the parameter handle min/max clipping
            driver.setNormalizedValue(driver.getNormalizedValue() + correctedState.getEntry(i++));
        }
    }

}
