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
package org.orekit.files.ccsds.ndm.odm.opm;

import java.io.IOException;

import org.orekit.data.DataContext;
import org.orekit.files.ccsds.definitions.TimeSystem;
import org.orekit.files.ccsds.ndm.odm.CartesianCovarianceWriter;
import org.orekit.files.ccsds.ndm.odm.CommonMetadata;
import org.orekit.files.ccsds.ndm.odm.CommonMetadataWriter;
import org.orekit.files.ccsds.ndm.odm.KeplerianElementsWriter;
import org.orekit.files.ccsds.ndm.odm.SpacecraftParametersWriter;
import org.orekit.files.ccsds.ndm.odm.StateVectorWriter;
import org.orekit.files.ccsds.ndm.odm.UserDefinedWriter;
import org.orekit.files.ccsds.section.Header;
import org.orekit.files.ccsds.section.Segment;
import org.orekit.files.ccsds.section.XmlStructureKey;
import org.orekit.files.ccsds.utils.ContextBinding;
import org.orekit.files.ccsds.utils.FileFormat;
import org.orekit.files.ccsds.utils.generation.AbstractMessageWriter;
import org.orekit.files.ccsds.utils.generation.Generator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;


/**
 * Writer for CCSDS Orit Parameter Message.
 *
 * @author Luc Maisonobe
 * @since 11.0
 */
public class OpmWriter extends AbstractMessageWriter {

    /** Version number implemented. **/
    public static final double CCSDS_OPM_VERS = 3.0;

    /** Key width for aligning the '=' sign. */
    public static final int KEY_WIDTH = 18;

    /** Complete constructor.
     * @param conventions IERS Conventions
     * @param dataContext used to retrieve frames, time scales, etc.
     * @param missionReferenceDate reference date for Mission Elapsed Time or Mission Relative Time time systems
     * @param header file header (may be null)
     * @param fileName file name for error messages
     */
    public OpmWriter(final IERSConventions conventions, final DataContext dataContext,
                     final AbsoluteDate missionReferenceDate,
                     final Header header, final String fileName) {
        super(OpmFile.FORMAT_VERSION_KEY, CCSDS_OPM_VERS, header,
              new ContextBinding(
                  () -> conventions, () -> false, () -> dataContext,
                  () -> missionReferenceDate, () -> TimeSystem.UTC,
                  () -> 0.0, () -> 1.0),
              fileName);
    }

    /** Write one segment.
     * @param generator generator to use for producing output
     * @param segment segment to write
     * @throws IOException if any buffer writing operations fails
     */
    public void writeSegment(final Generator generator, final Segment<CommonMetadata, OpmData> segment)
        throws IOException {

        // write the metadata
        new CommonMetadataWriter(segment.getMetadata(), getTimeConverter()).
        write(generator);

        // start data block
        if (generator.getFormat() == FileFormat.XML) {
            generator.enterSection(XmlStructureKey.data.name());
        }

        // write mandatory state vector block
        new StateVectorWriter(XmlSubStructureKey.stateVector.name(), null,
                              segment.getData().getStateVectorBlock(), getTimeConverter()).
        write(generator);

        if (segment.getData().getKeplerianElementsBlock() != null) {
            // write optional Keplerian elements block
            new KeplerianElementsWriter(XmlSubStructureKey.keplerianElements.name(), null,
                                        segment.getData().getKeplerianElementsBlock()).
            write(generator);
        }

        if (segment.getData().getSpacecraftParametersBlock() != null) {
            // write optional spacecraft parameters block
            new SpacecraftParametersWriter(XmlSubStructureKey.spacecraftParameters.name(), null,
                                           segment.getData().getSpacecraftParametersBlock()).
            write(generator);
        }

        if (segment.getData().getCovarianceBlock() != null) {
            // write optional spacecraft parameters block
            new CartesianCovarianceWriter(XmlSubStructureKey.covarianceMatrix.name(), null,
                                          segment.getData().getCovarianceBlock()).
            write(generator);
        }

        if (!segment.getData().getManeuvers().isEmpty()) {
            for (final Maneuver maneuver : segment.getData().getManeuvers()) {
                // write optional maneuver block
                new ManeuverWriter(maneuver, getTimeConverter()).write(generator);
            }
        }

        if (segment.getData().getUserDefinedBlock() != null) {
            // write optional uder defined parameters block
            new UserDefinedWriter(XmlSubStructureKey.userDefinedParameters.name(), null,
                                  segment.getData().getUserDefinedBlock()).
            write(generator);
        }

        // stop data block
        if (generator.getFormat() == FileFormat.XML) {
            generator.exitSection();
        }

    }

}
