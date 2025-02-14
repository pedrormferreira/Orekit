/* Copyright 2002-2012 Space Applications Services
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
package org.orekit.files.sp3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hipparchus.exception.LocalizedCoreFormats;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.orekit.annotation.DefaultDataContext;
import org.orekit.data.DataContext;
import org.orekit.data.DataSource;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitIllegalArgumentException;
import org.orekit.errors.OrekitMessages;
import org.orekit.files.general.EphemerisFileParser;
import org.orekit.files.sp3.SP3.SP3Coordinate;
import org.orekit.files.sp3.SP3.SP3FileType;
import org.orekit.frames.Frame;
import org.orekit.gnss.TimeSystem;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateComponents;
import org.orekit.time.DateTimeComponents;
import org.orekit.time.TimeComponents;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScales;
import org.orekit.utils.CartesianDerivativesFilter;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

/** A parser for the SP3 orbit file format. It supports all formats from sp3-a
 * to sp3-d.
 * <p>
 * <b>Note:</b> this parser is thread-safe, so calling {@link #parse} from
 * different threads is allowed.
 * </p>
 * @see <a href="ftp://igs.org/pub/data/format/sp3_docu.txt">SP3-a file format</a>
 * @see <a href="ftp://igs.org/pub/data/format/sp3c.txt">SP3-c file format</a>
 * @see <a href="ftp://igs.org/pub/data/format/sp3d.pdf">SP3-d file format</a>
 * @author Thomas Neidhart
 * @author Luc Maisonobe
 */
public class SP3Parser implements EphemerisFileParser<SP3> {

    /** Bad or absent clock values are to be set to 999999.999999. */
    public static final double DEFAULT_CLOCK_VALUE = 999999.999999;

    /** Spaces delimiters. */
    private static final String SPACES = "\\s+";

    /** One millimeter, in meters. */
    private static final double MILLIMETER = 1.0e-3;

    /** Standard gravitational parameter in m^3 / s^2. */
    private final double mu;
    /** Number of data points to use in interpolation. */
    private final int interpolationSamples;
    /** Mapping from frame identifier in the file to a {@link Frame}. */
    private final Function<? super String, ? extends Frame> frameBuilder;
    /** Set of time scales. */
    private final TimeScales timeScales;

    /**
     * Create an SP3 parser using default values.
     *
     * <p>This constructor uses the {@link DataContext#getDefault() default data context}.
     *
     * @see #SP3Parser(double, int, Function)
     */
    @DefaultDataContext
    public SP3Parser() {
        this(Constants.EIGEN5C_EARTH_MU, 7, SP3Parser::guessFrame);
    }

    /**
     * Create an SP3 parser and specify the extra information needed to create a {@link
     * org.orekit.propagation.Propagator Propagator} from the ephemeris data.
     *
     * <p>This constructor uses the {@link DataContext#getDefault() default data context}.
     *
     * @param mu                   is the standard gravitational parameter to use for
     *                             creating {@link org.orekit.orbits.Orbit Orbits} from
     *                             the ephemeris data. See {@link Constants}.
     * @param interpolationSamples is the number of samples to use when interpolating.
     * @param frameBuilder         is a function that can construct a frame from an SP3
     *                             coordinate system string. The coordinate system can be
     *                             any 5 character string e.g. ITR92, IGb08.
     * @see #SP3Parser(double, int, Function, TimeScales)
     */
    @DefaultDataContext
    public SP3Parser(final double mu,
                     final int interpolationSamples,
                     final Function<? super String, ? extends Frame> frameBuilder) {
        this(mu, interpolationSamples, frameBuilder,
                DataContext.getDefault().getTimeScales());
    }

    /**
     * Create an SP3 parser and specify the extra information needed to create a {@link
     * org.orekit.propagation.Propagator Propagator} from the ephemeris data.
     *
     * @param mu                   is the standard gravitational parameter to use for
     *                             creating {@link org.orekit.orbits.Orbit Orbits} from
     *                             the ephemeris data. See {@link Constants}.
     * @param interpolationSamples is the number of samples to use when interpolating.
     * @param frameBuilder         is a function that can construct a frame from an SP3
     *                             coordinate system string. The coordinate system can be
     * @param timeScales           the set of time scales used for parsing dates.
     * @since 10.1
     */
    public SP3Parser(final double mu,
                     final int interpolationSamples,
                     final Function<? super String, ? extends Frame> frameBuilder,
                     final TimeScales timeScales) {
        this.mu = mu;
        this.interpolationSamples = interpolationSamples;
        this.frameBuilder = frameBuilder;
        this.timeScales = timeScales;
    }

    /**
     * Default string to {@link Frame} conversion for {@link #SP3Parser()}.
     *
     * <p>This method uses the {@link DataContext#getDefault() default data context}.
     *
     * @param name of the frame.
     * @return ITRF based on 2010 conventions,
     * with tidal effects considered during EOP interpolation.
     */
    @DefaultDataContext
    private static Frame guessFrame(final String name) {
        return DataContext.getDefault().getFrames()
                .getITRF(IERSConventions.IERS_2010, false);
    }

    @Override
    public SP3 parse(final DataSource source) {

        try (Reader reader = source.getOpener().openReaderOnce();
             BufferedReader br = (reader == null) ? null : new BufferedReader(reader)) {

            if (br == null) {
                throw new OrekitException(OrekitMessages.UNABLE_TO_FIND_FILE, source.getName());
            }

            // initialize internal data structures
            final ParseInfo pi = new ParseInfo();

            int lineNumber = 0;
            Stream<LineParser> candidateParsers = Stream.of(LineParser.HEADER_VERSION);
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                ++lineNumber;
                final String l = line;
                final Optional<LineParser> selected = candidateParsers.filter(p -> p.canHandle(l)).findFirst();
                if (selected.isPresent()) {
                    try {
                        selected.get().parse(line, pi);
                    } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
                        throw new OrekitException(e,
                                                  OrekitMessages.UNABLE_TO_PARSE_LINE_IN_FILE,
                                                  lineNumber, source.getName(), line);
                    }
                    candidateParsers = selected.get().allowedNext();
                } else {
                    throw new OrekitException(OrekitMessages.UNABLE_TO_PARSE_LINE_IN_FILE,
                                              lineNumber, source.getName(), line);
                }
                if (pi.done) {
                    if (pi.nbEpochs != pi.file.getNumberOfEpochs()) {
                        throw new OrekitException(OrekitMessages.SP3_NUMBER_OF_EPOCH_MISMATCH,
                                                  pi.nbEpochs, source.getName(), pi.file.getNumberOfEpochs());
                    }
                    return pi.file;
                }
            }

            // Sometimes, the "EOF" key is not available in the file
            // If the expected number of entries has been read
            // we can suppose that the file has been read properly
            if (pi.nbEpochs == pi.file.getNumberOfEpochs()) {
                return pi.file;
            }

            // we never reached the EOF marker or number of epochs doesn't correspond to the expected number
            throw new OrekitException(OrekitMessages.SP3_UNEXPECTED_END_OF_FILE, lineNumber);

        } catch (IOException ioe) {
            throw new OrekitException(ioe, LocalizedCoreFormats.SIMPLE_MESSAGE, ioe.getLocalizedMessage());
        }

    }

    /** Returns the {@link SP3FileType} that corresponds to a given string in a SP3 file.
     * @param fileType file type as string
     * @return file type as enum
     */
    private static SP3FileType getFileType(final String fileType) {
        SP3FileType type = SP3FileType.UNDEFINED;
        if ("G".equalsIgnoreCase(fileType)) {
            type = SP3FileType.GPS;
        } else if ("M".equalsIgnoreCase(fileType)) {
            type = SP3FileType.MIXED;
        } else if ("R".equalsIgnoreCase(fileType)) {
            type = SP3FileType.GLONASS;
        } else if ("L".equalsIgnoreCase(fileType)) {
            type = SP3FileType.LEO;
        } else if ("S".equalsIgnoreCase(fileType)) {
            type = SP3FileType.SBAS;
        } else if ("I".equalsIgnoreCase(fileType)) {
            type = SP3FileType.IRNSS;
        } else if ("E".equalsIgnoreCase(fileType)) {
            type = SP3FileType.GALILEO;
        } else if ("C".equalsIgnoreCase(fileType)) {
            type = SP3FileType.COMPASS;
        } else if ("J".equalsIgnoreCase(fileType)) {
            type = SP3FileType.QZSS;
        }
        return type;
    }

    /** Transient data used for parsing a sp3 file. The data is kept in a
     * separate data structure to make the parser thread-safe.
     * <p><b>Note</b>: The class intentionally does not provide accessor
     * methods, as it is only used internally for parsing a SP3 file.</p>
     */
    private class ParseInfo {

        /** Set of time scales for parsing dates. */
        private final TimeScales timeScales;

        /** The corresponding SP3File object. */
        private SP3 file;

        /** The latest epoch as read from the SP3 file. */
        private AbsoluteDate latestEpoch;

        /** The latest position as read from the SP3 file. */
        private Vector3D latestPosition;

        /** The latest clock value as read from the SP3 file. */
        private double latestClock;

        /** Indicates if the SP3 file has velocity entries. */
        private boolean hasVelocityEntries;

        /** The timescale used in the SP3 file. */
        private TimeScale timeScale;

        /** Date and time of the file. */
        private DateTimeComponents epoch;

        /** The number of satellites as contained in the SP3 file. */
        private int maxSatellites;

        /** The number of satellites accuracies already seen. */
        private int nbAccuracies;

        /** The number of epochs already seen. */
        private int nbEpochs;

        /** End Of File reached indicator. */
        private boolean done;

        /** The base for pos/vel. */
        //private double posVelBase;

        /** The base for clock/rate. */
        //private double clockBase;

        /** Create a new {@link ParseInfo} object. */
        protected ParseInfo() {
            this.timeScales = SP3Parser.this.timeScales;
            file               = new SP3(mu, interpolationSamples, frameBuilder);
            latestEpoch        = null;
            latestPosition     = null;
            latestClock        = 0.0;
            hasVelocityEntries = false;
            epoch              = DateTimeComponents.JULIAN_EPOCH;
            timeScale          = timeScales.getGPS();
            maxSatellites      = 0;
            nbAccuracies       = 0;
            nbEpochs           = 0;
            done               = false;
            //posVelBase = 2d;
            //clockBase = 2d;
        }
    }

    /** Parsers for specific lines. */
    private enum LineParser {

        /** Parser for version, epoch, data used and agency information. */
        HEADER_VERSION("^#[a-z].*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                try (Scanner s1      = new Scanner(line);
                     Scanner s2      = s1.useDelimiter(SPACES);
                     Scanner scanner = s2.useLocale(Locale.US)) {
                    scanner.skip("#");
                    final String v = scanner.next();

                    final char version = v.substring(0, 1).toLowerCase().charAt(0);
                    if (version != 'a' && version != 'b' && version != 'c' && version != 'd') {
                        throw new OrekitException(OrekitMessages.SP3_UNSUPPORTED_VERSION, version);
                    }

                    pi.hasVelocityEntries = "V".equals(v.substring(1, 2));
                    pi.file.setFilter(pi.hasVelocityEntries ?
                                      CartesianDerivativesFilter.USE_PV :
                                      CartesianDerivativesFilter.USE_P);

                    final int    year   = Integer.parseInt(v.substring(2));
                    final int    month  = scanner.nextInt();
                    final int    day    = scanner.nextInt();
                    final int    hour   = scanner.nextInt();
                    final int    minute = scanner.nextInt();
                    final double second = scanner.nextDouble();

                    pi.epoch = new DateTimeComponents(year, month, day,
                                                      hour, minute, second);

                    final int numEpochs = scanner.nextInt();
                    pi.file.setNumberOfEpochs(numEpochs);

                    // data used indicator
                    pi.file.setDataUsed(scanner.next());

                    pi.file.setCoordinateSystem(scanner.next());
                    pi.file.setOrbitTypeKey(scanner.next());
                    pi.file.setAgency(scanner.next());
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_DATE_TIME_REFERENCE);
            }

        },

        /** Parser for additional date/time references in gps/julian day notation. */
        HEADER_DATE_TIME_REFERENCE("^##.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                try (Scanner s1      = new Scanner(line);
                     Scanner s2      = s1.useDelimiter(SPACES);
                     Scanner scanner = s2.useLocale(Locale.US)) {
                    scanner.skip("##");

                    // gps week
                    pi.file.setGpsWeek(scanner.nextInt());
                    // seconds of week
                    pi.file.setSecondsOfWeek(scanner.nextDouble());
                    // epoch interval
                    pi.file.setEpochInterval(scanner.nextDouble());
                    // julian day
                    pi.file.setJulianDay(scanner.nextInt());
                    // day fraction
                    pi.file.setDayFraction(scanner.nextDouble());
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_SAT_IDS);
            }

        },

        /** Parser for satellites identifiers. */
        HEADER_SAT_IDS("^\\+ .*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {

                if (pi.maxSatellites == 0) {
                    // this is the first ids line, it also contains the number of satellites
                    pi.maxSatellites = Integer.parseInt(line.substring(3, 6).trim());
                }

                final int lineLength = line.length();
                int count = pi.file.getSatelliteCount();
                int startIdx = 9;
                while (count++ < pi.maxSatellites && (startIdx + 3) <= lineLength) {
                    final String satId = line.substring(startIdx, startIdx + 3).trim();
                    if (satId.length() > 0) {
                        pi.file.addSatellite(satId);
                    }
                    startIdx += 3;
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_SAT_IDS, HEADER_ACCURACY);
            }

        },

        /** Parser for general accuracy information for each satellite. */
        HEADER_ACCURACY("^\\+\\+.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                final int lineLength = line.length();
                int startIdx = 9;
                while (pi.nbAccuracies < pi.maxSatellites && (startIdx + 3) <= lineLength) {
                    final String sub = line.substring(startIdx, startIdx + 3).trim();
                    if (sub.length() > 0) {
                        final int exponent = Integer.parseInt(sub);
                        // the accuracy is calculated as 2**exp (in mm)
                        pi.file.setAccuracy(pi.nbAccuracies++, (2 << exponent) * MILLIMETER);
                    }
                    startIdx += 3;
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_ACCURACY, HEADER_TIME_SYSTEM);
            }

        },

        /** Parser for time system. */
        HEADER_TIME_SYSTEM("^%c.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {

                if (pi.file.getType() == null) {
                    // this the first custom fields line, the only one really used
                    pi.file.setType(getFileType(line.substring(3, 5).trim()));

                    // now identify the time system in use
                    final String tsStr = line.substring(9, 12).trim();
                    final TimeSystem ts;
                    if (tsStr.equalsIgnoreCase("ccc")) {
                        ts = TimeSystem.GPS;
                    } else {
                        ts = TimeSystem.valueOf(tsStr);
                    }
                    pi.file.setTimeSystem(ts);
                    pi.timeScale = ts.getTimeScale(pi.timeScales);

                    // now we know the time scale used, we can set the file epoch
                    pi.file.setEpoch(new AbsoluteDate(pi.epoch, pi.timeScale));
                }

            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_TIME_SYSTEM, HEADER_STANDARD_DEVIATIONS);
            }

        },

        /** Parser for standard deviations of position/velocity/clock components. */
        HEADER_STANDARD_DEVIATIONS("^%f.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                // String base = line.substring(3, 13).trim();
                // if (!base.equals("0.0000000")) {
                //    // (mm or 10**-4 mm/sec)
                //    pi.posVelBase = Double.valueOf(base);
                // }

                // base = line.substring(14, 26).trim();
                // if (!base.equals("0.000000000")) {
                //    // (psec or 10**-4 psec/sec)
                //    pi.clockBase = Double.valueOf(base);
                // }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_STANDARD_DEVIATIONS, HEADER_CUSTOM_PARAMETERS);
            }

        },

        /** Parser for custom parameters. */
        HEADER_CUSTOM_PARAMETERS("^%i.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                // ignore additional custom parameters
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_CUSTOM_PARAMETERS, HEADER_COMMENTS);
            }

        },

        /** Parser for comments. */
        HEADER_COMMENTS("^[%]?/\\*.*|") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                // ignore comments
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(HEADER_COMMENTS, DATA_EPOCH);
            }

        },

        /** Parser for epoch. */
        DATA_EPOCH("^\\* .*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                final int    year   = Integer.parseInt(line.substring(3, 7).trim());
                final int    month  = Integer.parseInt(line.substring(8, 10).trim());
                final int    day    = Integer.parseInt(line.substring(11, 13).trim());
                final int    hour   = Integer.parseInt(line.substring(14, 16).trim());
                final int    minute = Integer.parseInt(line.substring(17, 19).trim());
                final double second = Double.parseDouble(line.substring(20).trim());

                // some SP3 files have weird epochs as in the following two examples, where
                // the middle dates are wrong
                //
                // *  2016  7  6 16 58  0.00000000
                // PL51  11872.234459   3316.551981    101.400098 999999.999999
                // VL51   8054.606014 -27076.640110 -53372.762255 999999.999999
                // *  2016  7  6 16 60  0.00000000
                // PL51  11948.228978   2986.113872   -538.901114 999999.999999
                // VL51   4605.419303 -27972.588048 -53316.820671 999999.999999
                // *  2016  7  6 17  2  0.00000000
                // PL51  11982.652569   2645.786926  -1177.549463 999999.999999
                // VL51   1128.248622 -28724.293303 -53097.358387 999999.999999
                //
                // *  2016  7  6 23 58  0.00000000
                // PL51   3215.382310  -7958.586164   8812.395707
                // VL51 -18058.659942 -45834.335707 -34496.540437
                // *  2016  7  7 24  0  0.00000000
                // PL51   2989.229334  -8494.421415   8385.068555
                // VL51 -19617.027447 -43444.824985 -36706.159070
                // *  2016  7  7  0  2  0.00000000
                // PL51   2744.983592  -9000.639164   7931.904779
                // VL51 -21072.925764 -40899.633288 -38801.567078
                //
                // In the first case, the date should really be 2016  7  6 17  0  0.00000000,
                // i.e as the minutes field overflows, the hours field should be incremented
                // In the second case, the date should really be 2016  7  7  0  0  0.00000000,
                // i.e. as the hours field overflows, the day field should be kept as is
                // we cannot be sure how carry was managed when these bogus files were written
                // so we try different options, incrementing or not previous field, and selecting
                // the closest one to expected date
                DateComponents dc = new DateComponents(year, month, day);
                final List<AbsoluteDate> candidates = new ArrayList<>();
                int h = hour;
                int m = minute;
                double s = second;
                if (s >= 60.0) {
                    s -= 60;
                    addCandidate(candidates, dc, h, m, s, pi.timeScale);
                    m++;
                }
                if (m > 59) {
                    m = 0;
                    addCandidate(candidates, dc, h, m, s, pi.timeScale);
                    h++;
                }
                if (h > 23) {
                    h = 0;
                    addCandidate(candidates, dc, h, m, s, pi.timeScale);
                    dc = new DateComponents(dc, 1);
                }
                addCandidate(candidates, dc, h, m, s, pi.timeScale);
                final AbsoluteDate expected = pi.latestEpoch == null ?
                                              pi.file.getEpoch() :
                                                  pi.latestEpoch.shiftedBy(pi.file.getEpochInterval());
                pi.latestEpoch = null;
                for (final AbsoluteDate candidate : candidates) {
                    if (FastMath.abs(candidate.durationFrom(expected)) < 0.01 * pi.file.getEpochInterval()) {
                        pi.latestEpoch = candidate;
                    }
                }
                if (pi.latestEpoch == null) {
                    // no date recognized, just parse again the initial fields
                    // in order to generate again an exception
                    pi.latestEpoch = new AbsoluteDate(year, month, day, hour, minute, second, pi.timeScale);
                }
                pi.nbEpochs++;
            }

            /** Add an epoch candidate to a list.
             * @param candidates list of candidates
             * @param dc date components
             * @param hour hour number from 0 to 23
             * @param minute minute number from 0 to 59
             * @param second second number from 0.0 to 60.0 (excluded)
             * @param timeScale time scale
             * @since 11.1.1
             */
            private void addCandidate(final List<AbsoluteDate> candidates, final DateComponents dc,
                                      final int hour, final int minute, final double second,
                                      final TimeScale timeScale) {
                try {
                    candidates.add(new AbsoluteDate(dc, new TimeComponents(hour, minute, second), timeScale));
                } catch (OrekitIllegalArgumentException oiae) {
                    // ignored
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(DATA_POSITION);
            }

        },

        /** Parser for position. */
        DATA_POSITION("^P.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                final String satelliteId = line.substring(1, 4).trim();

                if (!pi.file.containsSatellite(satelliteId)) {
                    pi.latestPosition = null;
                } else {
                    final double x = Double.parseDouble(line.substring(4, 18).trim());
                    final double y = Double.parseDouble(line.substring(18, 32).trim());
                    final double z = Double.parseDouble(line.substring(32, 46).trim());

                    // the position values are in km and have to be converted to m
                    pi.latestPosition = new Vector3D(x * 1000, y * 1000, z * 1000);

                    // clock (microsec)
                    pi.latestClock = line.trim().length() <= 46 ?
                                                          DEFAULT_CLOCK_VALUE :
                                                              Double.parseDouble(line.substring(46, 60).trim()) * 1e-6;

                    // the additional items are optional and not read yet

                    // if (line.length() >= 73) {
                    // // x-sdev (b**n mm)
                    // int xStdDevExp = Integer.valueOf(line.substring(61,
                    // 63).trim());
                    // // y-sdev (b**n mm)
                    // int yStdDevExp = Integer.valueOf(line.substring(64,
                    // 66).trim());
                    // // z-sdev (b**n mm)
                    // int zStdDevExp = Integer.valueOf(line.substring(67,
                    // 69).trim());
                    // // c-sdev (b**n psec)
                    // int cStdDevExp = Integer.valueOf(line.substring(70,
                    // 73).trim());
                    //
                    // pi.posStdDevRecord =
                    // new PositionStdDevRecord(FastMath.pow(pi.posVelBase, xStdDevExp),
                    // FastMath.pow(pi.posVelBase,
                    // yStdDevExp), FastMath.pow(pi.posVelBase, zStdDevExp),
                    // FastMath.pow(pi.clockBase, cStdDevExp));
                    //
                    // String clockEventFlag = line.substring(74, 75);
                    // String clockPredFlag = line.substring(75, 76);
                    // String maneuverFlag = line.substring(78, 79);
                    // String orbitPredFlag = line.substring(79, 80);
                    // }

                    if (!pi.hasVelocityEntries) {
                        final SP3Coordinate coord =
                                new SP3Coordinate(pi.latestEpoch,
                                                  pi.latestPosition,
                                                  pi.latestClock);
                        pi.file.addSatelliteCoordinate(satelliteId, coord);
                    }
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(DATA_EPOCH, DATA_POSITION, DATA_POSITION_CORRELATION, DATA_VELOCITY, EOF);
            }

        },

        /** Parser for position correlation. */
        DATA_POSITION_CORRELATION("^EP.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                // ignored for now
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(DATA_EPOCH, DATA_POSITION, DATA_VELOCITY, EOF);
            }

        },

        /** Parser for velocity. */
        DATA_VELOCITY("^V.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                final String satelliteId = line.substring(1, 4).trim();

                if (pi.file.containsSatellite(satelliteId)) {
                    final double xv = Double.parseDouble(line.substring(4, 18).trim());
                    final double yv = Double.parseDouble(line.substring(18, 32).trim());
                    final double zv = Double.parseDouble(line.substring(32, 46).trim());

                    // the velocity values are in dm/s and have to be converted to m/s
                    final Vector3D velocity = new Vector3D(xv / 10d, yv / 10d, zv / 10d);

                    // clock rate in file is 1e-4 us / s
                    final double clockRateChange = line.trim().length() <= 46 ?
                                                                        DEFAULT_CLOCK_VALUE :
                                                                            Double.parseDouble(line.substring(46, 60).trim()) * 1e-4;

                    // the additional items are optional and not read yet

                    // if (line.length() >= 73) {
                    // // xvel-sdev (b**n 10**-4 mm/sec)
                    // int xVstdDevExp = Integer.valueOf(line.substring(61,
                    // 63).trim());
                    // // yvel-sdev (b**n 10**-4 mm/sec)
                    // int yVstdDevExp = Integer.valueOf(line.substring(64,
                    // 66).trim());
                    // // zvel-sdev (b**n 10**-4 mm/sec)
                    // int zVstdDevExp = Integer.valueOf(line.substring(67,
                    // 69).trim());
                    // // clkrate-sdev (b**n 10**-4 psec/sec)
                    // int clkStdDevExp = Integer.valueOf(line.substring(70,
                    // 73).trim());
                    // }

                    final SP3Coordinate coord =
                            new SP3Coordinate(pi.latestEpoch,
                                              pi.latestPosition,
                                              velocity,
                                              pi.latestClock,
                                              clockRateChange);
                    pi.file.addSatelliteCoordinate(satelliteId, coord);
                }
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(DATA_EPOCH, DATA_POSITION, DATA_VELOCITY_CORRELATION, EOF);
            }

        },

        /** Parser for velocity correlation. */
        DATA_VELOCITY_CORRELATION("^EV.*") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                // ignored for now
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(DATA_EPOCH, DATA_POSITION, EOF);
            }

        },

        /** Parser for End Of File marker. */
        EOF("^[eE][oO][fF]\\s*$") {

            /** {@inheritDoc} */
            @Override
            public void parse(final String line, final ParseInfo pi) {
                pi.done = true;
            }

            /** {@inheritDoc} */
            @Override
            public Stream<LineParser> allowedNext() {
                return Stream.of(EOF);
            }

        };

        /** Pattern for identifying line. */
        private final Pattern pattern;

        /** Simple constructor.
         * @param lineRegexp regular expression for identifying line
         */
        LineParser(final String lineRegexp) {
            pattern = Pattern.compile(lineRegexp);
        }

        /** Parse a line.
         * @param line line to parse
         * @param pi holder for transient data
         */
        public abstract void parse(String line, ParseInfo pi);

        /** Get the allowed parsers for next line.
         * @return allowed parsers for next line
         */
        public abstract Stream<LineParser> allowedNext();

        /** Check if parser can handle line.
         * @param line line to parse
         * @return true if parser can handle the specified line
         */
        public boolean canHandle(final String line) {
            return pattern.matcher(line).matches();
        }

    }

}
