/* Copyright 2002-2022 CS GROUP
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
package org.orekit.estimation.iod;

import org.hipparchus.analysis.solvers.LaguerreSolver;
import org.hipparchus.complex.Complex;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.LUDecomposition;
import org.hipparchus.util.FastMath;
import org.orekit.estimation.measurements.AngularRaDec;
import org.orekit.frames.Frame;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;

/**
 * Laplace angles-only initial orbit determination, assuming Keplerian motion.
 * An orbit is determined from three angular observations from the same site.
 *
 *
 * Reference:
 *    Bate, R., Mueller, D. D., &amp; White, J. E. (1971). Fundamentals of astrodynamics.
 *    New York: Dover Publications.
 *
 * @author Shiva Iyer
 * @since 10.1
 */
public class IodLaplace {

    /** Gravitational constant. */
    private final double mu;

    /** Constructor.
     *
     * @param mu  gravitational constant
     */
    public IodLaplace(final double mu) {
        this.mu = mu;
    }

    /** Estimate the orbit from three angular observations at the same location.
     *
     * @param frame inertial frame for observer coordinates and orbit estimate
     * @param obsPva Observer coordinates at time of raDec2
     * @param raDec1 first angular observation
     * @param raDec2 second angular observation
     * @param raDec3 third angular observation
     * @return estimate of the orbit at the central date obsDate2 or null if
     *         no estimate is possible with the given data
     * @since 11.0
     */
    public CartesianOrbit estimate(final Frame frame, final PVCoordinates obsPva,
                                   final AngularRaDec raDec1, final AngularRaDec raDec2,
                                   final AngularRaDec raDec3) {
        return estimate(frame, obsPva,
                        raDec1.getDate(), lineOfSight(raDec1),
                        raDec2.getDate(), lineOfSight(raDec2),
                        raDec3.getDate(), lineOfSight(raDec3));
    }

    /** Estimate orbit from three line of sight angles from the same location.
     *
     * @param frame inertial frame for observer coordinates and orbit estimate
     * @param obsPva Observer coordinates at time obsDate2
     * @param obsDate1 date of observation 1
     * @param los1 line of sight unit vector 1
     * @param obsDate2 date of observation 2
     * @param los2 line of sight unit vector 2
     * @param obsDate3 date of observation 3
     * @param los3 line of sight unit vector 3
     * @return estimate of the orbit at the central date obsDate2 or null if
     *         no estimate is possible with the given data
     */
    public CartesianOrbit estimate(final Frame frame, final PVCoordinates obsPva,
                                   final AbsoluteDate obsDate1, final Vector3D los1,
                                   final AbsoluteDate obsDate2, final Vector3D los2,
                                   final AbsoluteDate obsDate3, final Vector3D los3) {
        // The first observation is taken as t1 = 0
        final double t2 = obsDate2.durationFrom(obsDate1);
        final double t3 = obsDate3.durationFrom(obsDate1);

        // Calculate the first and second derivatives of the Line Of Sight vector at t2
        final Vector3D Ldot = los1.scalarMultiply((t2 - t3) / (t2 * t3)).
                        add(los2.scalarMultiply((2.0 * t2 - t3) / (t2 * (t2 - t3)))).
                        add(los3.scalarMultiply(t2 / (t3 * (t3 - t2))));
        final Vector3D Ldotdot = los1.scalarMultiply(2.0 / (t2 * t3)).
                        add(los2.scalarMultiply(2.0 / (t2 * (t2 - t3)))).
                        add(los3.scalarMultiply(2.0 / (t3 * (t3 - t2))));

        // The determinant will vanish if the observer lies in the plane of the orbit at t2
        final double D = 2.0 * getDeterminant(los2, Ldot, Ldotdot);
        if (FastMath.abs(D) < 1.0E-14) {
            return null;
        }

        final double Dsq = D * D;
        final double R = obsPva.getPosition().getNorm();
        final double RdotL = obsPva.getPosition().dotProduct(los2);

        final double D1 = getDeterminant(los2, Ldot, obsPva.getAcceleration());
        final double D2 = getDeterminant(los2, Ldot, obsPva.getPosition());

        // Coefficients of the 8th order polynomial we need to solve to determine "r"
        final double[] coeff = new double[] {-4.0 * mu * mu * D2 * D2 / Dsq,
                                             0.0,
                                             0.0,
                                             4.0 * mu * D2 * (RdotL / D - 2.0 * D1 / Dsq),
                                             0.0,
                                             0.0,
                                             4.0 * D1 * RdotL / D - 4.0 * D1 * D1 / Dsq - R * R, 0.0,
                                             1.0};

        // Use the Laguerre polynomial solver and take the initial guess to be
        // 5 times the observer's position magnitude
        final LaguerreSolver solver = new LaguerreSolver(1E-10, 1E-10, 1E-10);
        final Complex[] roots = solver.solveAllComplex(coeff, 5.0 * R);

        // We consider "r" to be the positive real root with the largest magnitude
        double rMag = 0.0;
        for (int i = 0; i < roots.length; i++) {
            if (roots[i].getReal() > rMag &&
                            FastMath.abs(roots[i].getImaginary()) < solver.getAbsoluteAccuracy()) {
                rMag = roots[i].getReal();
            }
        }
        if (rMag == 0.0) {
            return null;
        }

        // Calculate rho, the slant range from the observer to the satellite at t2.
        // This yields the "r" vector, which is the satellite's position vector at t2.
        final double rCubed = rMag * rMag * rMag;
        final double rho = -2.0 * D1 / D - 2.0 * mu * D2 / (D * rCubed);
        final Vector3D posVec = los2.scalarMultiply(rho).add(obsPva.getPosition());

        // Calculate rho_dot at t2, which will yield the satellite's velocity vector at t2
        final double D3 = getDeterminant(los2, obsPva.getAcceleration(), Ldotdot);
        final double D4 = getDeterminant(los2, obsPva.getPosition(), Ldotdot);
        final double rhoDot = -D3 / D - mu * D4 / (D * rCubed);
        final Vector3D velVec = los2.scalarMultiply(rhoDot).
                        add(Ldot.scalarMultiply(rho)).
                        add(obsPva.getVelocity());

        // Return the estimated orbit
        return new CartesianOrbit(new PVCoordinates(posVec, velVec), frame, obsDate2, mu);
    }

    /**
     * Calculates the line of sight vector.
     * @param alpha right ascension angle, in radians
     * @param delta declination angle, in radians
     * @return the line of sight vector
     * @since 11.0
     */
    public static Vector3D lineOfSight(final double alpha, final double delta) {
        return new Vector3D(FastMath.cos(delta) * FastMath.cos(alpha),
                            FastMath.cos(delta) * FastMath.sin(alpha),
                            FastMath.sin(delta));
    }

    /**
     * Calculate the line of sight vector from an AngularRaDec measurement.
     * @param raDec measurement
     * @return the line of sight vector
     * @since 11.0
     */
    public static Vector3D lineOfSight(final AngularRaDec raDec) {

        // Observed values
        final double[] observed = raDec.getObservedValue();

        // Return
        return lineOfSight(observed[0], observed[1]);

    }

    /** Calculate the determinant of the matrix with given column vectors.
     *
     * @param col0 Matrix column 0
     * @param col1 Matrix column 1
     * @param col2 Matrix column 2
     * @return matrix determinant
     *
     */
    private double getDeterminant(final Vector3D col0, final Vector3D col1, final Vector3D col2) {
        final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(3, 3);
        mat.setColumn(0, col0.toArray());
        mat.setColumn(1, col1.toArray());
        mat.setColumn(2, col2.toArray());
        return new LUDecomposition(mat).getDeterminant();
    }

}
