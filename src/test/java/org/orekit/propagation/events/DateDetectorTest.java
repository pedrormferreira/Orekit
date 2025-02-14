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
package org.orekit.propagation.events;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.ode.events.Action;
import org.hipparchus.ode.nonstiff.AdaptiveStepsizeIntegrator;
import org.hipparchus.ode.nonstiff.DormandPrince853Integrator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.orekit.Utils;
import org.orekit.errors.OrekitException;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.EquinoctialOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.propagation.events.handlers.EventHandler;
import org.orekit.propagation.integration.AdditionalDerivativesProvider;
import org.orekit.propagation.integration.CombinedDerivatives;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.propagation.sampling.OrekitStepInterpolator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

public class DateDetectorTest {

    private int evtno = 0;
    private double maxCheck;
    private double threshold;
    private double dt;
    private Orbit iniOrbit;
    private AbsoluteDate iniDate;
    private AbsoluteDate nodeDate;
    private DateDetector dateDetector;
    private NumericalPropagator propagator;

    @Test
    public void testSimpleTimer() {
        DateDetector dateDetector = new DateDetector(maxCheck, threshold, iniDate.shiftedBy(2.0*dt));
        Assert.assertEquals(2 * dt, dateDetector.getDate().durationFrom(iniDate), 1.0e-10);
        propagator.addAdditionalDerivativesProvider(new AdditionalDerivativesProvider() {
            public String   getName()                      { return "dummy"; }
            public int      getDimension()                 { return 1; }
            public double[] derivatives(final SpacecraftState state) { return null; }
            public CombinedDerivatives combinedDerivatives(SpacecraftState s) {
                return new CombinedDerivatives(new double[1], null);
            }
        });
        propagator.setInitialState(propagator.getInitialState().addAdditionalState("dummy", new double[1]));
        propagator.getMultiplexer().add(interpolator -> {
            SpacecraftState prev = interpolator.getPreviousState();
            SpacecraftState curr = interpolator.getCurrentState();
            double dt = curr.getDate().durationFrom(prev.getDate());
            OrekitStepInterpolator restricted =
                            interpolator.restrictStep(prev.shiftedBy(dt * +0.25),
                                                      curr.shiftedBy(dt * -0.25));
            SpacecraftState restrictedPrev = restricted.getPreviousState();
            SpacecraftState restrictedCurr = restricted.getCurrentState();
            double restrictedDt = restrictedCurr.getDate().durationFrom(restrictedPrev.getDate());
            Assert.assertEquals(dt * 0.5, restrictedDt, 1.0e-10);
        });
        propagator.setOrbitType(OrbitType.EQUINOCTIAL);
        propagator.addEventDetector(dateDetector);
        final SpacecraftState finalState = propagator.propagate(iniDate.shiftedBy(100.*dt));

        Assert.assertEquals(2.0*dt, finalState.getDate().durationFrom(iniDate), threshold);
    }

    @Test
    public void testEmbeddedTimer() {
        dateDetector = new DateDetector(maxCheck, threshold);
        Assert.assertNull(dateDetector.getDate());
        EventDetector nodeDetector = new NodeDetector(iniOrbit, iniOrbit.getFrame()).
                withHandler(new ContinueOnEvent<NodeDetector>() {
                    public Action eventOccurred(SpacecraftState s, NodeDetector nd, boolean increasing)
                        {
                        if (increasing) {
                            nodeDate = s.getDate();
                            dateDetector.addEventDate(nodeDate.shiftedBy(dt));
                        }
                        return Action.CONTINUE;
                    }
                });

        propagator.addEventDetector(nodeDetector);
        propagator.addEventDetector(dateDetector);
        final SpacecraftState finalState = propagator.propagate(iniDate.shiftedBy(100.*dt));

        Assert.assertEquals(dt, finalState.getDate().durationFrom(nodeDate), threshold);
    }

    @Test
    public void testAutoEmbeddedTimer() {
        dateDetector = new DateDetector(maxCheck, threshold, iniDate.shiftedBy(-dt)).
                withHandler(new ContinueOnEvent<DateDetector>() {
                    public Action eventOccurred(SpacecraftState s, DateDetector dd,  boolean increasing)
                            {
                        AbsoluteDate nextDate = s.getDate().shiftedBy(-dt);
                        dd.addEventDate(nextDate);
                        ++evtno;
                        return Action.CONTINUE;
                    }
                });
        propagator.addEventDetector(dateDetector);
        propagator.propagate(iniDate.shiftedBy(-100.*dt));

        Assert.assertEquals(100, evtno);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testExceptionTimer() {
        dateDetector = new DateDetector(maxCheck, threshold, iniDate.shiftedBy(dt)).
                withHandler(new ContinueOnEvent<DateDetector>() {
                    public Action eventOccurred(SpacecraftState s, DateDetector dd, boolean increasing)
                        {
                        double step = (evtno % 2 == 0) ? 2.*maxCheck : maxCheck/2.;
                        AbsoluteDate nextDate = s.getDate().shiftedBy(step);
                        dd.addEventDate(nextDate);
                        ++evtno;
                        return Action.CONTINUE;
                    }
                });
        propagator.addEventDetector(dateDetector);
        propagator.propagate(iniDate.shiftedBy(100.*dt));
    }

    /**
     * Check that a generic event handler can be used with an event detector.
     */
    @Test
    public void testGenericHandler() {
        //setup
        dateDetector = new DateDetector(maxCheck, threshold, iniDate.shiftedBy(dt));
        // generic event handler that works with all detectors.
        EventHandler<EventDetector> handler = new EventHandler<EventDetector>() {
            @Override
            public Action eventOccurred(SpacecraftState s,
                                        EventDetector detector,
                                        boolean increasing)
                    {
                Assert.assertSame(dateDetector, detector);
                return Action.STOP;
            }

            @Override
            public SpacecraftState resetState(EventDetector detector,
                                              SpacecraftState oldState)
                    {
                throw new RuntimeException("Should not be called");
            }
        };

        //action
        dateDetector = dateDetector.withHandler(handler);
        propagator.addEventDetector(dateDetector);
        SpacecraftState finalState = propagator.propagate(iniDate.shiftedBy(100 * dt));

        //verify
        Assert.assertEquals(dt, finalState.getDate().durationFrom(iniDate), threshold);
    }

    @Before
    public void setUp() {
        try {
            Utils.setDataRoot("regular-data");
            final double mu = 3.9860047e14;
            final Vector3D position  = new Vector3D(-6142438.668, 3492467.560, -25767.25680);
            final Vector3D velocity  = new Vector3D(505.8479685, 942.7809215, 7435.922231);
            iniDate  = new AbsoluteDate(1969, 7, 28, 4, 0, 0.0, TimeScalesFactory.getTT());
            iniOrbit = new EquinoctialOrbit(new PVCoordinates(position, velocity),
                                            FramesFactory.getEME2000(), iniDate, mu);
            SpacecraftState initialState = new SpacecraftState(iniOrbit);
            double[] absTolerance = {
                0.001, 1.0e-9, 1.0e-9, 1.0e-6, 1.0e-6, 1.0e-6, 0.001
            };
            double[] relTolerance = {
                1.0e-7, 1.0e-4, 1.0e-4, 1.0e-7, 1.0e-7, 1.0e-7, 1.0e-7
            };
            AdaptiveStepsizeIntegrator integrator =
                new DormandPrince853Integrator(0.001, 1000, absTolerance, relTolerance);
            integrator.setInitialStepSize(60);
            propagator = new NumericalPropagator(integrator);
            propagator.setInitialState(initialState);
            dt = 60.;
            maxCheck  = 10.;
            threshold = 10.e-10;
            evtno = 0;
        } catch (OrekitException oe) {
            Assert.fail(oe.getLocalizedMessage());
        }
    }

    @After
    public void tearDown() {
        iniDate = null;
        propagator = null;
        dateDetector = null;
    }

}
