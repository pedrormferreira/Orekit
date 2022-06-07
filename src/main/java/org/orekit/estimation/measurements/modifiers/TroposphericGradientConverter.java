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
package org.orekit.estimation.measurements.modifiers;

import org.orekit.attitudes.AttitudeProvider;
import org.orekit.propagation.SpacecraftState;

/**
 * Converter for states and parameters arrays.
 * @author Bryan Cazabonne
 * @since 10.2
 * @deprecated as of 11.2, replaced by {@link ModifierGradientConverter}
 */
@Deprecated
public class TroposphericGradientConverter extends ModifierGradientConverter {

    /** Simple constructor.
     * @param state regular state
     * @param freeStateParameters number of free parameters, either 3 (position) or 6 (position-velocity)
     * @param provider provider to use if attitude needs to be recomputed
     */
    public TroposphericGradientConverter(final SpacecraftState state, final int freeStateParameters,
                                         final AttitudeProvider provider) {
        super(state, freeStateParameters, provider);
    }

}
