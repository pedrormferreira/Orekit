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
package org.orekit.propagation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

import org.hipparchus.CalculusFieldElement;
import org.hipparchus.Field;
import org.orekit.attitudes.AttitudeProvider;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitMessages;
import org.orekit.frames.Frame;
import org.orekit.propagation.sampling.FieldStepHandlerMultiplexer;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.FieldAbsoluteDate;
import org.orekit.utils.TimeSpanMap;
import org.orekit.utils.TimeStampedFieldPVCoordinates;

/** Common handling of {@link Propagator} methods for analytical propagators.
 * <p>
 * This abstract class allows to provide easily the full set of {@link Propagator}
 * methods, including all propagation modes support and discrete events support for
 * any simple propagation method.
 * </p>
 * @param <T> the type of the field elements
 * @author Luc Maisonobe
 */
public abstract class FieldAbstractPropagator<T extends CalculusFieldElement<T>> implements FieldPropagator<T> {

    /** Multiplexer for step handlers. */
    private FieldStepHandlerMultiplexer<T> multiplexer;

    /** Start date. */
    private FieldAbsoluteDate<T> startDate;

    /** Attitude provider. */
    private AttitudeProvider attitudeProvider;

    /** Closed form generators.
     * @since 11.1
     */
    private final List<FieldStackableGenerator<T>> closedFormGenerators;

    /** States managed by no generators. */
    private final Map<String, TimeSpanMap<T[]>> unmanagedStates;

    /** Field used.*/
    private final Field<T> field;

    /** Initial state. */
    private FieldSpacecraftState<T> initialState;

    /** Build a new instance.
     * @param field setting the field
     */
    protected FieldAbstractPropagator(final Field<T> field) {
        this.field      = field;
        multiplexer     = new FieldStepHandlerMultiplexer<>();
        closedFormGenerators        = new ArrayList<>();
        unmanagedStates = new HashMap<>();
    }

    /** Set a start date.
     * @param startDate start date
     */
    protected void setStartDate(final FieldAbsoluteDate<T> startDate) {
        this.startDate = startDate;
    }

    /** Get the start date.
     * @return start date
     */
    protected FieldAbsoluteDate<T> getStartDate() {
        return startDate;
    }

    /**  {@inheritDoc} */
    public AttitudeProvider getAttitudeProvider() {
        return attitudeProvider;
    }

    /**  {@inheritDoc} */
    public void setAttitudeProvider(final AttitudeProvider attitudeProvider) {
        this.attitudeProvider = attitudeProvider;
    }

    /** Field getter.
     * @return field used*/
    public Field<T> getField() {
        return field;
    }

    /** {@inheritDoc} */
    public FieldSpacecraftState<T> getInitialState() {
        return initialState;
    }

    /** {@inheritDoc} */
    public Frame getFrame() {
        return initialState.getFrame();
    }

    /** {@inheritDoc} */
    public void resetInitialState(final FieldSpacecraftState<T> state) {
        initialState = state;
        setStartDate(state.getDate());
    }

    /** {@inheritDoc} */
    public FieldStepHandlerMultiplexer<T> getMultiplexer() {
        return multiplexer;
    }

    /** {@inheritDoc} */
    @Deprecated
    @Override
    public void addAdditionalStateProvider(final FieldAdditionalStateProvider<T> additionalStateProvider) {
        addClosedFormGenerator(new FieldAdditionalStateProviderAdapter<>(additionalStateProvider));
    }

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Deprecated
    @Override
    public List<FieldAdditionalStateProvider<T>> getAdditionalStateProviders() {
        return getClosedFormGenerators().
                        stream().
                        map(u -> new FieldClosedFormAdapter<>(u)).
                        collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    public void addClosedFormGenerator(final FieldStackableGenerator<T> generator) {

        // check if the name is already used
        if (isAdditionalStateManaged(generator.getName())) {
            // this additional state is already registered, complain
            throw new OrekitException(OrekitMessages.ADDITIONAL_STATE_NAME_ALREADY_IN_USE,
                                      generator.getName());
        }

        // this is really a new name, add it
        closedFormGenerators.add(generator);

    }

    /** {@inheritDoc} */
    public List<FieldStackableGenerator<T>> getClosedFormGenerators() {
        return Collections.unmodifiableList(closedFormGenerators);
    }

    /** Update state by adding unmanaged states.
     * @param original original state
     * @return updated state, with unmanaged states included
     * @see #updateAdditionalStates(FieldSpacecraftState)
     */
    protected FieldSpacecraftState<T> updateUnmanagedStates(final FieldSpacecraftState<T> original) {

        // start with original state,
        // which may already contain additional states, for example in interpolated ephemerides
        FieldSpacecraftState<T> updated = original;

        // update the states not managed by providers
        for (final Map.Entry<String, TimeSpanMap<T[]>> entry : unmanagedStates.entrySet()) {
            updated = updated.addAdditionalState(entry.getKey(),
                                                 entry.getValue().get(original.getDate().toAbsoluteDate()));
        }

        return updated;

    }

    /** Get all generators.
     * @return all generators
     * @since 11.1
     */
    protected Collection<FieldStackableGenerator<T>> getAllGenerators() {
        return closedFormGenerators;
    }

    /** Update state by adding all additional states.
     * @param original original state
     * @return updated state, with all additional states included
     * (including {@link #updateUnmanagedStates(FieldSpacecraftState) unmanaged} states)
     * @see #addClosedFormGenerator(FieldStackableGenerator)
     * @see #updateUnmanagedStates(FieldSpacecraftState)
     */
    protected FieldSpacecraftState<T> updateAdditionalStates(final FieldSpacecraftState<T> original) {

        // start with original state and unmanaged states
        FieldSpacecraftState<T> updated = updateUnmanagedStates(original);

        // set up queue for updaters
        final Queue<FieldStackableGenerator<T>> pending = new LinkedList<>(getAllGenerators());

        // update the additional states managed by updaters, taking care of dependencies
        int yieldCount = 0;
        while (!pending.isEmpty()) {
            final FieldStackableGenerator<T> updater = pending.remove();
            if (updater.yield(updated)) {
                // this updater has to wait for another one,
                // we put it again in the pending queue
                pending.add(updater);
                if (++yieldCount >= pending.size()) {
                    // all pending updaters yielded!, they probably need data not yet initialized
                    // we let the propagation proceed, if these data are really needed right now
                    // an appropriate exception will be triggered when caller tries to access them
                    break;
                }
            } else {
                // we can use this updater right now
                if (updater.isClosedForm()) {
                    updated = updated.addAdditionalState(updater.getName(), updater.generate(updated));
                } else {
                    updated = updated.addAdditionalStateDerivative(updater.getName(), updater.generate(updated));
                }
                yieldCount = 0;
            }
        }

        return updated;

    }

    /** {@inheritDoc} */
    public boolean isAdditionalStateManaged(final String name) {
        for (final FieldStackableGenerator<T> provider : closedFormGenerators) {
            if (provider.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    public String[] getManagedAdditionalStates() {
        final String[] managed = new String[closedFormGenerators.size()];
        for (int i = 0; i < managed.length; ++i) {
            managed[i] = closedFormGenerators.get(i).getName();
        }
        return managed;
    }

    /** {@inheritDoc} */
    public FieldSpacecraftState<T> propagate(final FieldAbsoluteDate<T> target) {
        if (startDate == null) {
            startDate = getInitialState().getDate();
        }
        return propagate(startDate, target);
    }

    /** {@inheritDoc} */
    public TimeStampedFieldPVCoordinates<T> getPVCoordinates(final FieldAbsoluteDate<T> date, final Frame frame) {
        return propagate(date).getPVCoordinates(frame);
    }

    /** Initialize propagation.
     * @since 10.1
     */
    protected void initializePropagation() {

        unmanagedStates.clear();

        if (initialState != null) {
            // there is an initial state
            // (null initial states occur for example in interpolated ephemerides)
            // copy the additional states present in initialState but otherwise not managed
            for (final Map.Entry<String, T[]> initial : initialState.getAdditionalStates().entrySet()) {
                if (!isAdditionalStateManaged(initial.getKey())) {
                    // this additional state is in the initial state, but is unknown to the propagator
                    // we store it in a way event handlers may change it
                    unmanagedStates.put(initial.getKey(), new TimeSpanMap<>(initial.getValue()));
                }
            }
        }
    }

    /** Notify about a state change.
     * @param state new state
     */
    protected void stateChanged(final FieldSpacecraftState<T> state) {
        final AbsoluteDate date    = state.getDate().toAbsoluteDate();
        final boolean      forward = date.durationFrom(getStartDate().toAbsoluteDate()) >= 0.0;
        for (final Map.Entry<String, T[]> changed : state.getAdditionalStates().entrySet()) {
            final TimeSpanMap<T[]> tsm = unmanagedStates.get(changed.getKey());
            if (tsm != null) {
                // this is an unmanaged state
                if (forward) {
                    tsm.addValidAfter(changed.getValue(), date);
                } else {
                    tsm.addValidBefore(changed.getValue(), date);
                }
            }
        }
    }

}
