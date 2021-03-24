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

package org.orekit.files.ccsds.ndm.odm.ocm;

import java.util.List;

import org.orekit.files.ccsds.ndm.odm.UserDefined;
import org.orekit.files.ccsds.section.Data;

/** Data container for Orbit Comprehensive Messages.
 * @author LOuc Maisonobe
 * @since 11.0
 */
public class OcmData implements Data {

    /** Orbit state histories logical blocks. */
    private final List<OrbitStateHistory> orbitBlocks;

    /** Physical properties logical block. */
    private final PhysicalProperties physicBlock;

    /** Covariance logical blocks. */
    private final List<CovarianceHistory> covarianceBlocks;

    /** Maneuvers logical blocks. */
    private final List<ManeuverHistory> maneuverBlocks;

    /** Perturbations logical block. */
    private final Perturbations perturbationsBlock;

    /** Orbit determination logical block. */
    private final OrbitDetermination orbitDeterminationBlock;

    /** User defined parameters logical block. */
    private final UserDefined userDefinedBlock;

    /** Simple constructor.
     * @param orbitBlocks orbital state histories logical blocks (may be empty)
     * @param physicBlock physical properties logical block (may be null)
     * @param covarianceBlocks covariance logical blocks (may be empty)
     * @param maneuverBlocks maneuvers logical blocks (may be empty)
     * @param perturbationsBlock perturbations logical block (may be null)
     * @param orbitDeterminationBlock orbit determination logical block (may be null)
     * @param userDefinedBlock user defined parameters logical block (may be null)
     */
    public OcmData(final List<OrbitStateHistory> orbitBlocks,
                   final PhysicalProperties      physicBlock,
                   final List<CovarianceHistory> covarianceBlocks,
                   final List<ManeuverHistory>   maneuverBlocks,
                   final Perturbations           perturbationsBlock,
                   final OrbitDetermination      orbitDeterminationBlock,
                   final UserDefined          userDefinedBlock) {
        this.orbitBlocks              = orbitBlocks;
        this.physicBlock              = physicBlock;
        this.covarianceBlocks         = covarianceBlocks;
        this.maneuverBlocks           = maneuverBlocks;
        this.perturbationsBlock       = perturbationsBlock;
        this.orbitDeterminationBlock  = orbitDeterminationBlock;
        this.userDefinedBlock         = userDefinedBlock;
    }

    /** {@inheritDoc} */
    @Override
    public void checkMandatoryEntries() {
        if (orbitBlocks != null) {
            for (final OrbitStateHistory osh : orbitBlocks) {
                osh.getMetadata().checkMandatoryEntries();
            }
        }
        if (physicBlock != null) {
            physicBlock.checkMandatoryEntries();
        }
        if (covarianceBlocks != null) {
            for (final CovarianceHistory ch : covarianceBlocks) {
                ch.getMetadata().checkMandatoryEntries();
            }
        }
        if (maneuverBlocks != null) {
            for (final ManeuverHistory mh : maneuverBlocks) {
                mh.getMetadata().checkMandatoryEntries();
            }
        }
        if (perturbationsBlock != null) {
            perturbationsBlock.checkMandatoryEntries();
        }
        if (orbitDeterminationBlock != null) {
            orbitDeterminationBlock.checkMandatoryEntries();
        }
        if (userDefinedBlock != null) {
            userDefinedBlock.checkMandatoryEntries();
        }
    }

    /** Get orbit state histories logical blocks.
     * @return orbita state histories logical blocks (may be null)
     */
    public List<OrbitStateHistory> getOrbitBlocks() {
        return orbitBlocks;
    }

    /** Get physical properties logical block.
     * @return physical properties logical block (may be null)
     */
    public PhysicalProperties getPhysicBlock() {
        return physicBlock;
    }

    /** Get covariance logical blocks.
     * @return covariance logical blocks (may be null)
     */
    public List<CovarianceHistory> getCovarianceBlocks() {
        return covarianceBlocks;
    }

    /** Get maneuvers logical blocks.
     * @return maneuvers logical block (may be null)
     */
    public List<ManeuverHistory> getManeuverBlocks() {
        return maneuverBlocks;
    }

    /** Get perturbations logical block.
     * @return perturbations logical block (may be null)
     */
    public Perturbations getPerturbationsBlock() {
        return perturbationsBlock;
    }

    /** Get orbit determination logical block.
     * @return orbit determination logical block (may be null)
     */
    public OrbitDetermination getOrbitDeterminationBlock() {
        return orbitDeterminationBlock;
    }

    /** Get user defined parameters logical block.
     * @return user defined parameters logical block (may be null)
     */
    public UserDefined getUserDefinedBlock() {
        return userDefinedBlock;
    }

}
