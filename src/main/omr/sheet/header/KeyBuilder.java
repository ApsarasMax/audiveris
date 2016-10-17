//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       K e y B u i l d e r                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Hervé Bitteur and others 2000-2016. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.header;

import omr.classifier.Classifier;
import omr.classifier.Evaluation;
import omr.classifier.GlyphClassifier;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.Glyph;
import omr.glyph.GlyphCluster;
import omr.glyph.GlyphFactory;
import omr.glyph.GlyphIndex;
import omr.glyph.Grades;
import omr.glyph.Shape;
import omr.glyph.Symbol.Group;

import omr.math.Clustering;
import omr.math.GeoUtil;
import omr.math.Population;
import omr.math.Projection;
import static omr.run.Orientation.VERTICAL;
import omr.run.RunTable;
import omr.run.RunTableFactory;

import omr.sheet.Picture;
import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.Staff;
import omr.sheet.SystemInfo;
import omr.sheet.header.HeaderBuilder.Plotter;
import omr.sheet.header.StaffHeader.Range;

import omr.sig.SIGraph;
import omr.sig.inter.BarlineInter;
import omr.sig.inter.ClefInter;
import omr.sig.inter.ClefInter.ClefKind;
import omr.sig.inter.Inter;
import omr.sig.inter.KeyAlterInter;
import omr.sig.inter.KeyInter;
import omr.sig.relation.ClefKeyRelation;
import omr.sig.relation.Exclusion;
import omr.sig.relation.KeyAltersRelation;
import omr.sig.relation.Relation;

import omr.util.Navigable;

import ij.process.Blitter;
import ij.process.ByteProcessor;

import org.jfree.data.xy.XYSeries;

import org.jgrapht.Graphs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import static java.awt.image.BufferedImage.TYPE_BYTE_GRAY;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Class {@code KeyBuilder} retrieves a staff key signature through the vertical
 * projection to x-axis of the foreground pixels in a given abscissa range of a staff.
 * <p>
 * An instance typically handles the initial key signature, perhaps void, at the beginning of a
 * staff.
 * Another instance may be used to process a key signature change located farther in the staff,
 * generally right after a double bar line.
 * <p>
 * A key signature is a sequence of consistent alterations (all sharps or all flats or none) in a
 * predefined order (FCGDAEB for sharps, BEADGCF for flats).
 * In the case of a key signature change, there may be some natural signs to explicitly cancel the
 * previous alterations, although this is not mandatory.
 * <p>
 * <img src="http://www.musicarrangers.com/star-theory/images/p14a.gif">
 * <p>
 * <img src="http://www.musicarrangers.com/star-theory/images/p14b.gif">
 * <p>
 * The relative positioning of alterations in a given signature is identical for all clefs (treble,
 * alto, tenor, bass) with the only exception of the sharp-based signatures in tenor clef.
 * <p>
 * <img src="http://www.musicarrangers.com/star-theory/images/p14c.gif">
 * <p>
 * The main tool is a vertical projection of the StaffHeader pixels onto the x-axis.
 * Vertically, the projection uses an envelope that can embrace any key signature (under any clef),
 * from two interline values above the staff to one interline value below the staff.
 * Horizontally, the goal is to split the projection into slices, one slice for each alteration item
 * to be extracted.
 * <p>
 * Peak detection allows to detect alteration "stems" (one for a flat, two for a sharp).
 * Typical x delta between two stems of a sharp is around 0.5+ interline.
 * Typical x delta between stems of 2 flats (or first stems of 2 sharps) is around 1+ interline.
 * Unfortunately, some flat-delta may be smaller than some sharp-delta...
 * <p>
 * Typical peak height (above the lines height) is around 2+ interline values.
 * All peaks have similar heights in the same key-sig, this may differentiate a key-sig from a
 * time-sig.
 * A space, if any, between two key-sig items is very narrow.
 * <p>
 * Strategy:<ol>
 * <li>Find first significant space right after clef, it's the space that separates the clef from
 * next item (key-sig or time-sig or first note/rest, etc).
 * This space may not be detected in the projection when the first key-sig item is very close to the
 * clef, because their projections on x-axis overlap.
 * If that space is really wide, consider there is no key-sig.
 * <li>The next really wide space, if any, will mark the end of key-sig.
 * <li>Look for peaks in the area, make sure each peak corresponds to some stem-like shape.
 * <li>Once all peaks have been retrieved, check delta abscissa between peaks, to differentiate
 * sharps vs flats sequence.
 * Additional help is brought by checking the left side of first peak (it is almost void for a flat
 * and not for a sharp).
 * <li>Determine the number of items.
 * <li>Determine precise horizontal slicing of the projection into items.
 * <li>Looking only at connected components within the key-sig area, try to retrieve one good
 * component for each slice, by trying each glyph compound via shape classifier for verification and
 * vertical positioning.
 * <li>For slices left empty, use hard slice segmentation and perform recognition within slice only.
 * <li>Create one KeyInter.
 * <li>Create one KeyAlterInter instance per item.
 * <li>Verify each item pitch in the staff (to be later matched against staff clef).
 * </ol>
 *
 * @author Hervé Bitteur
 */
public class KeyBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(KeyBuilder.class);

    /** Shapes allowed in a key signature. */
    private static final Set<Shape> keyShapes = EnumSet.of(Shape.FLAT, Shape.SHARP);

    //~ Instance fields ----------------------------------------------------------------------------
    /** Dedicated staff to analyze. */
    private final Staff staff;

    /** Key range info. */
    private final StaffHeader.Range range;

    /** The containing system. */
    @Navigable(false)
    private final SystemInfo system;

    /** The related SIG. */
    private final SIGraph sig;

    /** The related sheet. */
    @Navigable(false)
    private final Sheet sheet;

    /** Related scale. */
    private final Scale scale;

    /** Scale-dependent parameters. */
    private final Parameters params;

    /** Header key-sig or key-sig change?. TODO: not yet used, but will be needed */
    private final boolean inHeader;

    /** Shape classifier to use. */
    private final Classifier classifier = GlyphClassifier.getInstance();

    /** Staff-free pixel source. */
    private final ByteProcessor staffFreeSource;

    /** Precise beginning abscissa of measure. */
    private final int measureStart;

    /** ROI for key search. */
    private final Roi roi;

    /** Projection of foreground pixels, indexed by abscissa. */
    private final Projection projection;

    /** Sequence of peaks found. */
    private final List<KeyEvent.Peak> peaks = new ArrayList<KeyEvent.Peak>();

    /** Sequence of spaces and peaks. (for debugging) */
    private final List<KeyEvent> events = new ArrayList<KeyEvent>();

    /** Shape used for key signature. */
    private Shape keyShape;

    /** Sequence of alteration slices. */
    private final List<Slice> slices = new ArrayList<Slice>();

    /** Resulting key inter, if any. */
    private KeyInter keyInter;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new KeyBuilder object.
     *
     * @param staff        the underlying staff
     * @param globalWidth  global plotting width
     * @param measureStart precise beginning abscissa of measure (generally right after bar line).
     * @param browseStart  estimated beginning abscissa for browsing.
     * @param inHeader     true for the key-sig in StaffHeader, false for a key-sig change
     */
    protected KeyBuilder (Staff staff,
                          int globalWidth,
                          int measureStart,
                          int browseStart,
                          boolean inHeader)
    {
        this.staff = staff;
        this.inHeader = inHeader;

        system = staff.getSystem();
        sig = system.getSig();
        sheet = system.getSheet();
        staffFreeSource = sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);

        scale = sheet.getScale();
        params = new Parameters(scale);

        final StaffHeader header = staff.getHeader();

        if (header.keyRange != null) {
            range = header.keyRange;
        } else {
            header.keyRange = (range = new StaffHeader.Range());
            range.browseStart = browseStart;
            range.browseStop = getBrowseStop(globalWidth, measureStart, browseStart);
        }

        this.measureStart = measureStart;

        Rectangle browseRect = getBrowseRect();
        roi = new Roi(browseRect.y, browseRect.height);
        projection = getProjection(browseRect);
    }

    //~ Methods ------------------------------------------------------------------------------------
    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        return "KeyBuilder#" + getId();
    }

    //---------//
    // addPlot //
    //---------//
    protected void addPlot (Plotter plotter)
    {
        final int xMin = projection.getStart();
        final int xMax = projection.getStop();

        {
            // Values
            XYSeries cumulSeries = new XYSeries("Key");

            for (int x = xMin; x <= xMax; x++) {
                cumulSeries.add(x, projection.getValue(x));
            }

            plotter.add(cumulSeries, Color.RED, false);
        }

        List<Integer> alterStarts = staff.getHeader().alterStarts;

        if (alterStarts != null) {
            for (int ia = 0; ia < alterStarts.size(); ia++) {
                // Items marks
                XYSeries sep = new XYSeries("A" + (ia + 1));
                double x = alterStarts.get(ia);
                sep.add(x, -Plotter.MARK);
                sep.add(x, staff.getHeight());
                plotter.add(sep, Color.CYAN, false);
            }
        }

        if (range.start != -1) {
            // Area limits
            XYSeries series = new XYSeries("KeyArea");
            int start = range.start;
            int stop = (range.stop != 0) ? range.stop : xMax;
            series.add(start, -Plotter.MARK);
            series.add(start, staff.getHeight());
            series.add(stop, staff.getHeight());
            series.add(stop, -Plotter.MARK);
            plotter.add(series, Color.ORANGE, false);
        }

        {
            // Browse start for peak threshold
            XYSeries series = new XYSeries("KeyBrowse");
            int x = range.browseStart;
            series.add(x, -Plotter.MARK);
            series.add(x, params.minPeakCumul);
            series.add((range.stop != 0) ? range.stop : xMax, params.minPeakCumul);
            plotter.add(series, Color.BLACK, false);
        }

        {
            // Space threshold
            XYSeries chunkSeries = new XYSeries("Space");
            int x = range.browseStart;
            chunkSeries.add(x, params.maxSpaceCumul);
            chunkSeries.add(xMax, params.maxSpaceCumul);
            plotter.add(chunkSeries, Color.YELLOW, false);
        }
    }

    //---------------//
    // adjustPitches //
    //---------------//
    protected void adjustPitches ()
    {
        if (slices.isEmpty()) {
            return;
        }

        // Collect pitches measured from the underlying glyphs of alteration items
        Double[] mPitches = new Double[slices.size()];

        for (int i = 0; i < slices.size(); i++) {
            KeyAlterInter alter = slices.get(i).getAlter();
            mPitches[i] = (alter != null) ? alter.getMeasuredPitch() : null;
        }

        // Guess clef kind from pattern of measured pitches
        Map<ClefKind, Double> results = new EnumMap<ClefKind, Double>(ClefKind.class);
        ClefKind guess = ClefInter.guessKind(keyShape, mPitches, results);

        // (Slightly) adjust pitches if needed
        int[] stdPitches = (keyShape == Shape.SHARP) ? ClefInter.sharpsMap.get(guess)
                : ClefInter.flatsMap.get(guess);

        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            KeyAlterInter alter = slice.getAlter();

            if (alter != null) {
                int std = stdPitches[i];

                if (alter.getIntegerPitch() != std) {
                    logger.info(
                            "Staff#{} slice#{} pitch adjusted from {} to {}",
                            getId(),
                            i + 1,
                            String.format("%.1f", alter.getMeasuredPitch()),
                            std);
                    alter.setPitch(std);
                }
            } else {
                logger.info("Staff#{} no alter for {}", getId(), slice);
            }
        }

        // Create key inter
        createKeyInter();

        // Compare clef(s) candidates and key signature for this staff
        checkWithClefs(guess, results);

        //TODO: Boost key components. This is a hack
        // Perhaps we could simply remove the key alters from the sig, and now play with the key
        // as an ensemble instead.
        for (Slice slice : slices) {
            KeyAlterInter alter = slice.getAlter();

            if (alter != null) {
                alter.increase(0.25); ///////////// !!!!!!!!!!!!!!!!!!
            }
        }

        // Record slices starts in StaffHeader structure
        if (!slices.isEmpty()) {
            List<Integer> starts = new ArrayList<Integer>();

            for (Slice slice : slices) {
                starts.add(slice.getRect().x);
            }

            staff.getHeader().alterStarts = starts;
        }

        // Adjust key-sig stop for this staff
        for (int i = slices.size() - 1; i >= 0; i--) {
            KeyBuilder.Slice slice = slices.get(i);
            KeyAlterInter inter = slice.getAlter();

            if (inter != null) {
                Rectangle bounds = inter.getBounds();
                int end = (bounds.x + bounds.width) - 1;
                staff.setKeyStop(end);

                break;
            }
        }
    }

    //----------------//
    // getBrowseStart //
    //----------------//
    /**
     * @return the browseStart
     */
    protected Integer getBrowseStart ()
    {
        return range.browseStart;
    }

    //-----------------//
    // getMeasureStart //
    //-----------------//
    /**
     * @return the measureStart
     */
    protected int getMeasureStart ()
    {
        return measureStart;
    }

    //-----------//
    // getSlices //
    //-----------//
    /**
     * @return the slices
     */
    protected List<Slice> getSlices ()
    {
        return slices;
    }

    //-------------//
    // insertSlice //
    //-------------//
    /**
     * Insert a slice at provided index.
     *
     * @param index             provided index
     * @param theoreticalOffset theoretical offset WRT measure start
     */
    protected void insertSlice (int index,
                                int theoreticalOffset)
    {
        Slice nextSlice = slices.get(index);
        Slice slice = createSlice(measureStart + theoreticalOffset, nextSlice.getRect().x - 1);
        slices.add(index, slice);

        // Reassign staff slice attachments
        for (int i = 0; i < slices.size(); i++) {
            staff.addAttachment("k" + (i + 1), slices.get(i).getRect());
        }

        logger.debug("Staff#{} trying to insert key left {}", getId(), slice);

        // Process this new slice and try to assign a valid alter.
        extractAlter(slice, Collections.singleton(keyShape), Grades.keyAlterMinGrade2);
    }

    //---------//
    // process //
    //---------//
    /**
     * Process the potential key signature of the assigned staff.
     */
    protected void process ()
    {
        logger.debug("Key processing for S#{} staff#{}", system.getId(), getId());

        // Retrieve & check peaks
        browseArea();

        // Infer signature from peaks
        int signature = retrieveSignature();

        if (signature != 0) {
            // Check/modify signature
            signature = checkSignature(signature);
        }

        if (signature != 0) {
            // Compute start for each sig item
            List<Integer> starts = computeStarts(signature);

            if (!starts.isEmpty()) {
                // Allocate empty slices
                allocateSlices(starts);

                // First, look for suitable items in key area, using connected components
                retrieveComponents();

                // If some slices are still empty, use hard slice extraction
                List<Slice> emptySlices = getEmptySlices();

                if (!emptySlices.isEmpty()) {
                    logger.debug("Staff#{} empty key slices: {}", getId(), emptySlices);
                    extractEmptySlices(emptySlices);
                }
            } else {
                signature = 0;
            }
        }
    }

    //-----------//
    // reprocess //
    //-----------//
    /**
     * Re-launch the processing, using an updated browsing abscissa.
     *
     * @param browseStart new browsing abscissa
     */
    protected void reprocess (int browseStart)
    {
        range.browseStart = browseStart;
        reset();
        process();
    }

    //-----------//
    // scanSlice //
    //-----------//
    /**
     * Inspect the provided range and try to define a slice there.
     * <p>
     * The slice is inserted only if significant pixels can be found, and an alter is assigned to
     * the slice if classification is successful.
     *
     * @param start start of range abscissa
     * @param stop  stop of range abscissa
     * @return the created slice if any, with its slice.alter if successful
     */
    protected Slice scanSlice (int start,
                               int stop)
    {
        if (isRangeVoid(start, stop)) {
            // Nothing interesting there
            return null;
        } else {
            Slice slice = createSlice(start, stop);
            slices.add(slice);

            // Append to staff slice attachments
            staff.addAttachment("k" + slice.getId(), slice.getRect());

            logger.debug("Staff#{} trying to append key right {}", getId(), slice);

            // Process this new slice
            Set<Shape> shapes = (keyShape != null) ? Collections.singleton(keyShape) : keyShapes;
            KeyAlterInter alter = extractAlter(slice, shapes, Grades.keyAlterMinGrade2);

            if (alter != null) {
                keyShape = alter.getShape();
            }

            return slice;
        }
    }

    //----------------//
    // allocateSlices //
    //----------------//
    /**
     * Using the starting mark found for each alteration item, defines all slices.
     *
     * @param starts
     */
    private void allocateSlices (List<Integer> starts)
    {
        final int count = starts.size();

        for (int i = 0; i < count; i++) {
            int start = starts.get(i);
            int stop = (i < (count - 1)) ? (starts.get(i + 1) - 1) : range.stop;
            Slice slice = createSlice(start, stop);
            slices.add(slice);
            staff.addAttachment("k" + (i + 1), slice.getRect());
        }
    }

    //------------//
    // browseArea //
    //------------//
    /**
     * Browse the histogram to detect the sequence of peaks (similar to stems) and
     * spaces (blanks).
     */
    private void browseArea ()
    {
        // Maximum abscissa range to be browsed
        final int xMin = range.browseStart;
        final int xMax = range.browseStop;

        // Space parameters
        int maxSpaceCumul = params.maxSpaceCumul;
        int spaceStart = -1; // Space start abscissa
        int spaceStop = -1; // Space stop abscissa
        boolean valleyHit = false;

        // Peak parameters
        int peakStart = -1; // Peak start abscissa
        int peakStop = -1; // Peak stop abscissa
        int peakHeight = 0; // Peak height

        for (int x = xMin; x <= xMax; x++) {
            int cumul = projection.getValue(x);

            // For peak
            if (cumul >= params.minPeakCumul) {
                if (!valleyHit) {
                    continue;
                }

                if (spaceStart != -1) {
                    // End of space
                    if (!createSpace(spaceStart, spaceStop)) {
                        return; // Too wide space encountered
                    }

                    spaceStart = -1;
                }

                if (peakStart == -1) {
                    peakStart = x; // Beginning of peak
                }

                peakStop = x;
                peakHeight = Math.max(peakHeight, cumul);
            } else if (!valleyHit) {
                valleyHit = true;
            } else {
                if (peakStart != -1) {
                    // End of peak
                    if (!createPeak(peakStart, peakStop, peakHeight)) {
                        return; // Invalid peak encountered
                    }

                    peakStart = -1;
                    peakHeight = 0;
                }

                // For space
                if (cumul <= maxSpaceCumul) {
                    // Below threshold, we are in a space
                    if (spaceStart == -1) {
                        spaceStart = x; // Start of space
                    }

                    spaceStop = x;
                } else if (spaceStart != -1) {
                    // End of space
                    if (!createSpace(spaceStart, spaceStop)) {
                        return; // Too wide space encountered
                    }

                    spaceStart = -1;
                }
            }
        }

        // Finish ongoing space if any
        if (spaceStart != -1) {
            createSpace(spaceStart, spaceStop);
        } else if (peakStart != -1) {
            // Finish ongoing peak if any (this is rather unlikely...)
            createPeak(peakStart, peakStop, peakHeight);
        }
    }

    //----------------//
    // checkSignature //
    //----------------//
    /**
     * Additional tests on key-sig, which may get adjusted.
     *
     * @return the signature value, perhaps modified
     */
    private int checkSignature (int signature)
    {
        // Case of final invalid peak
        KeyEvent.Peak lastPeak = peaks.get(peaks.size() - 1);

        if (lastPeak.isInvalid()) {
            // Where is the precise end of key-sig?
            // Check x delta between previous (good) peak and this one
            range.stop = lastPeak.start - 1;

            KeyEvent.Peak goodPeak = lastGoodPeak();
            int trail = range.stop - goodPeak.start + 1;

            // Check trailing length
            if (signature < 0) {
                if (trail < params.minFlatTrail) {
                    logger.debug("Removing too narrow flat");
                    signature += 1;
                }
            } else if (trail < params.minSharpTrail) {
                logger.debug("Removing too narrow sharp");
                signature -= 1;
            }
        }

        return signature;
    }

    //----------------//
    // checkWithClefs //
    //----------------//
    private void checkWithClefs (ClefKind guess,
                                 Map<ClefKind, Double> results)
    {
        // Look for clef on left side in staff (together with its competing clefs)
        List<Inter> staffClefs = sig.inters(staff, ClefInter.class);
        Collections.sort(staffClefs, Inter.byAbscissa);

        int res = Collections.binarySearch(staffClefs, keyInter, Inter.byAbscissa);
        int indexClef = -res - 2;

        if (indexClef >= 0) {
            ClefInter lastClef = (ClefInter) staffClefs.get(indexClef);
            Set<Relation> excs = sig.getExclusions(lastClef);
            List<ClefInter> clefs = new ArrayList<ClefInter>();

            for (Relation rel : excs) {
                Inter inter = Graphs.getOppositeVertex(sig, rel, lastClef);

                if (inter instanceof ClefInter) {
                    clefs.add((ClefInter) inter);
                }
            }

            clefs.add(lastClef);

            logger.debug(
                    "staffClefs: {} index:{} lastClef:{} set:{}",
                    staffClefs,
                    indexClef,
                    lastClef,
                    clefs);

            for (Iterator<ClefInter> it = clefs.iterator(); it.hasNext();) {
                ClefInter clef = it.next();

                if (clef.getKind() == guess) {
                    sig.addEdge(clef, keyInter, new ClefKeyRelation());
                } else {
                    sig.insertExclusion(clef, keyInter, Exclusion.Cause.INCOMPATIBLE);
                    ///clef.delete();
                    it.remove();
                }
            }
        }
    }

    //---------------//
    // computeStarts //
    //---------------//
    /**
     * Compute the theoretical starting abscissa for each key-sig item.
     */
    private List<Integer> computeStarts (int signature)
    {
        List<Integer> starts = new ArrayList<Integer>();

        if (signature > 0) {
            // Sharps
            starts.add(range.start);

            for (int i = 2; i < peaks.size(); i += 2) {
                KeyEvent.Peak peak = peaks.get(i);

                if (peak.isInvalid()) {
                    break;
                }

                starts.add((int) Math.ceil(0.5 * (peak.start + peaks.get(i - 1).stop)));
            }

            // End of area
            refineStop(lastGoodPeak(), params.sharpTrail, params.maxSharpTrail);
        } else if (signature < 0) {
            // Flats
            KeyEvent.Peak firstPeak = peaks.get(0);

            // Start of area, make sure there is nothing right before first peak
            int flatHeading = ((firstPeak.start + firstPeak.stop) / 2) - range.start;

            if (flatHeading > params.maxFlatHeading) {
                logger.debug("Too large heading before first flat peak");

                return starts;
            }

            starts.add(range.start);

            for (int i = 1; i < peaks.size(); i++) {
                KeyEvent.Peak peak = peaks.get(i);

                if (peak.isInvalid()) {
                    break;
                }

                starts.add(peak.start);
            }

            // End of area
            refineStop(lastGoodPeak(), params.flatTrail, params.maxFlatTrail);
        }

        return starts;
    }

    //----------------//
    // createKeyInter //
    //----------------//
    private void createKeyInter ()
    {
        List<KeyAlterInter> alters = new ArrayList<KeyAlterInter>();
        Rectangle box = null;

        for (Slice slice : slices) {
            KeyAlterInter alter = slice.getAlter();

            if (alter != null) {
                alters.add(alter);

                if (box == null) {
                    box = alter.getBounds();
                } else {
                    box.add(alter.getBounds());
                }
            }
        }

        // Grade: all alters in a key-sig support each other
        for (int i = 0; i < alters.size(); i++) {
            KeyAlterInter alter = alters.get(i);

            for (KeyAlterInter sibling : alters.subList(i + 1, alters.size())) {
                sig.addEdge(alter, sibling, new KeyAltersRelation());
            }
        }

        double grade = 0;

        for (KeyAlterInter alter : alters) {
            grade += sig.computeContextualGrade(alter, false);
        }

        grade /= alters.size();

        keyInter = new KeyInter(box, grade, getFifths(), alters);
        keyInter.setStaff(staff);
        sig.addVertex(keyInter);
        staff.getHeader().key = keyInter;
    }

    //------------//
    // createPeak //
    //------------//
    /**
     * (Try to) create a peak for a candidate alteration item.
     * The peak is checked for its height and its width.
     *
     * @param start  start abscissa
     * @param stop   stop abscissa
     * @param height peak height
     * @return whether key processing can keep on
     */
    private boolean createPeak (int start,
                                int stop,
                                int height)
    {
        boolean keepOn = true;
        KeyEvent.Peak peak = new KeyEvent.Peak(start, stop, height);

        // Check whether this peak could be part of sig, otherwise give up
        if ((height > params.maxPeakCumul) || (peak.getWidth() > params.maxPeakWidth)) {
            logger.debug("Invalid height or width for peak");
            peak.setInvalid();
            keepOn = false;
        } else {
            // Does this peak correspond to a stem-shaped item? if not, simply ignore it
            if (!isStemLike(peak)) {
                return true;
            }

            // We may have an interesting peak, check distance since previous peak
            KeyEvent.Peak prevPeak = peaks.isEmpty() ? null : peaks.get(peaks.size() - 1);

            if (prevPeak != null) {
                // Check delta abscissa
                double x = (start + stop) / 2.0;
                double dx = x - ((prevPeak.start + prevPeak.stop) / 2.0);

                if (dx > params.maxPeakDx) {
                    // A large dx indicates we are beyond end of key-sig
                    logger.debug("Too large delta since previous peak");
                    peak.setInvalid();
                    keepOn = false;
                }
            } else {
                // Very first peak, check offset from theoretical start
                // TODO: this is too strict, check emptyness in previous abscissae
                int offset = start - range.browseStart;

                if (offset > params.maxFirstPeakOffset) {
                    logger.debug("First peak arrives too late");
                    peak.setInvalid();
                    keepOn = false;
                } else if (range.start == 0) {
                    // Set range.start at beginning of browsing, since no space was found before peak
                    range.start = range.browseStart;
                }
            }
        }

        events.add(peak);
        peaks.add(peak);

        return keepOn;
    }

    //-------------//
    // createSlice //
    //-------------//
    private Slice createSlice (int start,
                               int stop)
    {
        Rectangle rect = new Rectangle(start, roi.y, stop - start + 1, roi.height);

        return new Slice(rect);
    }

    //-------------//
    // createSpace //
    //-------------//
    /**
     * (Try to) create a new space between items. (clef, alterations, time-sig, ...)
     *
     * @param spaceStart start space abscissa
     * @param spaceStop  stop space abscissa
     * @return true to keep browsing, false to stop immediately
     */
    private boolean createSpace (int spaceStart,
                                 int spaceStop)
    {
        boolean keepOn = true;
        KeyEvent.Space space = new KeyEvent.Space(spaceStart, spaceStop);

        if (range.start == 0) {
            // This is the very first space found
            if (space.getWidth() > params.maxFirstSpaceWidth) {
                // No key signature!
                logger.debug("Staff#{} no key signature.", getId());
                keepOn = false;
            } else {
                // Set range.start here, since first chunk may be later skipped if lacking peak
                range.start = space.stop + 1;
            }
        } else if (peaks.isEmpty()) {
            range.start = space.stop + 1;
        } else if (space.getWidth() > params.maxInnerSpace) {
            range.stop = space.start;
            keepOn = false;
        }

        events.add(space);

        return keepOn;
    }

    //--------------//
    // extractAlter //
    //--------------//
    /**
     * Using the provided abscissa range, extract the relevant foreground pixels
     * from the NO_STAFF image and evaluate possible glyph instances.
     *
     * @param slice        the slice to process
     * @param targetShapes the set of shapes to try
     * @param minGrade     minimum acceptable grade
     * @return the Inter created if any
     */
    private KeyAlterInter extractAlter (Slice slice,
                                        Set<Shape> targetShapes,
                                        double minGrade)
    {
        final GlyphIndex glyphIndex = sheet.getGlyphIndex();
        Rectangle sliceRect = slice.getRect();

        ByteProcessor sliceBuf = roi.getSlicePixels(staffFreeSource, slice, slices);
        RunTable runTable = new RunTableFactory(VERTICAL).createTable(sliceBuf);
        List<Glyph> parts = GlyphFactory.buildGlyphs(runTable, sliceRect.getLocation());

        purgeGlyphs(parts, (sliceRect.x + sliceRect.width) - 1);

        for (ListIterator<Glyph> li = parts.listIterator(); li.hasNext();) {
            Glyph glyph = li.next();
            glyph = glyphIndex.registerOriginal(glyph);
            glyph.addGroup(Group.ALTER_PART);
            system.addFreeGlyph(glyph);
            li.set(glyph);
        }

        SingleAdapter adapter = new SingleAdapter(slice, parts, targetShapes);
        new GlyphCluster(adapter, null).decompose();

        if (adapter.slice.eval != null) {
            double grade = Inter.intrinsicRatio * adapter.slice.eval.grade;

            if (grade >= minGrade) {
                logger.debug("Glyph#{} {}", adapter.slice.glyph.getId(), adapter.slice.eval);

                KeyAlterInter alterInter = KeyAlterInter.create(
                        adapter.slice.glyph,
                        adapter.slice.eval.shape,
                        grade,
                        staff);

                if (alterInter != null) {
                    sig.addVertex(alterInter);
                    slice.alter = alterInter;
                    logger.debug("{}", slice);

                    return alterInter;
                }
            }
        }

        return null;
    }

    //--------------------//
    // extractEmptySlices //
    //--------------------//
    /**
     * Using the starting mark found for each alteration item, extract each vertical
     * slice and build alteration inter out of each slice.
     *
     * @param emptySlices sequence of empty slices
     */
    private void extractEmptySlices (List<Slice> emptySlices)
    {
        for (Slice slice : emptySlices) {
            extractAlter(slice, Collections.singleton(keyShape), Grades.keyAlterMinGrade);
        }
    }

    //---------------//
    // getBrowseRect //
    //---------------//
    /**
     * Define the rectangular area to be browsed.
     * <p>
     * The lookup area must embrace all possible key signatures, whatever the staff clef, so it goes
     * from first line to last line of staff, augmented of 2 interline value above and 1 interline
     * value below.
     *
     * @return the rectangular area to be browsed
     */
    private Rectangle getBrowseRect ()
    {
        final int xMin = Math.max(0, measureStart - params.preStaffMargin);
        final int xMax = range.browseStop;

        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;

        for (int x = xMin; x <= xMax; x++) {
            yMin = Math.min(yMin, staff.getFirstLine().yAt(xMin) - (2 * scale.getInterline()));
            yMax = Math.max(yMax, staff.getLastLine().yAt(xMin) + (1 * scale.getInterline()));
        }

        return new Rectangle(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
    }

    //---------------//
    // getBrowseStop //
    //---------------//
    /**
     * Determine the abscissa where to stop projection analysis.
     * <p>
     * The analysis range is typically [browseStart .. measureStart+globalWidth] but may end
     * earlier if a (good) bar line is encountered.
     *
     * @param globalWidth  theoretical projection length
     * @param measureStart abscissa at measure start
     * @param browseStart  abscissa at browse start (just after clef)
     * @return the end abscissa
     */
    private int getBrowseStop (int globalWidth,
                               int measureStart,
                               int browseStart)
    {
        int end = measureStart + globalWidth;

        for (BarlineInter bar : staff.getBars()) {
            if (!bar.isGood()) {
                continue;
            }

            int barStart = bar.getBounds().x;

            if ((barStart > browseStart) && (barStart <= end)) {
                logger.debug("Staff#{} stopping key search before {}", getId(), bar);
                end = barStart - 1;

                break;
            }
        }

        return end;
    }

    //----------------//
    // getEmptySlices //
    //----------------//
    /**
     * Report the sequence of slices found empty.
     *
     * @return the empty slices
     */
    private List<Slice> getEmptySlices ()
    {
        List<Slice> emptySlices = null;

        for (Slice slice : slices) {
            if (slice.alter == null) {
                if (emptySlices == null) {
                    emptySlices = new ArrayList<Slice>();
                }

                emptySlices.add(slice);
            }
        }

        if (emptySlices == null) {
            return Collections.emptyList();
        }

        return emptySlices;
    }

    //-----------//
    // getFifths //
    //-----------//
    /**
     * Staff key signature is dynamically computed using the keyShape and the count of
     * alteration slices.
     *
     * @return the signature as an integer value
     */
    private int getFifths ()
    {
        if (slices.isEmpty()) {
            return 0;
        }

        if (keyShape == Shape.SHARP) {
            return slices.size();
        } else {
            return -slices.size();
        }
    }

    //-------//
    // getId //
    //-------//
    private int getId ()
    {
        return staff.getId();
    }

    //---------------//
    // getProjection //
    //---------------//
    /**
     * Cumulate the foreground pixels for each abscissa value in the lookup area.
     *
     * @return the populated cumulation table
     */
    private Projection getProjection (Rectangle browseRect)
    {
        final int xMin = browseRect.x;
        final int xMax = (browseRect.x + browseRect.width) - 1;
        final int yMin = browseRect.y;
        final int yMax = (browseRect.y + browseRect.height) - 1;
        final Projection table = new Projection.Short(xMin, xMax);

        for (int x = xMin; x <= xMax; x++) {
            short cumul = 0;

            for (int y = yMin; y <= yMax; y++) {
                if (staffFreeSource.get(x, y) == 0) {
                    cumul++;
                }
            }

            table.increment(x, cumul);
        }

        return table;
    }

    //---------//
    // hasStem //
    //---------//
    /**
     * Report whether the provided rectangular peak area contains a vertical portion
     * of 'coreLength' with a black ratio of at least 'minBlackRatio'.
     * <p>
     * A row is considered as black if it contains at least one black pixel.
     *
     * @param area          the vertical very narrow rectangle of interest
     * @param source        the pixel source
     * @param coreLength    minimum "stem" length
     * @param minBlackRatio minimum ratio of black rows in "stem" length
     * @return true if a "stem" is found
     */
    private boolean hasStem (Rectangle area,
                             ByteProcessor source,
                             int coreLength,
                             double minBlackRatio)
    {
        // Process all rows
        final boolean[] blacks = new boolean[area.height];
        Arrays.fill(blacks, false);

        for (int y = 0; y < area.height; y++) {
            for (int x = 0; x < area.width; x++) {
                if (source.get(area.x + x, area.y + y) == 0) {
                    blacks[y] = true;

                    break;
                }
            }
        }

        // Build a sliding window, of length coreLength
        final int quorum = (int) Math.rint(coreLength * minBlackRatio);
        int count = 0;

        for (int y = 0; y < coreLength; y++) {
            if (blacks[y]) {
                count++;
            }
        }

        if (count >= quorum) {
            return true;
        }

        // Move the window downward
        for (int y = 1, yMax = area.height - coreLength; y <= yMax; y++) {
            if (blacks[y - 1]) {
                count--;
            }

            if (blacks[y + (coreLength - 1)]) {
                count++;
            }

            if (count >= quorum) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // isRangeVoid //
    //-------------//
    /**
     * Check whether the provided abscissa range is void (no significant chunk is found
     * above lines cumulated pixels).
     *
     * @param start range start abscissa
     * @param stop  range stop abscissa
     * @return true if void, false if not void
     */
    private boolean isRangeVoid (int start,
                                 int stop)
    {
        int meanHeight = 0;
        int count = 0;

        for (int x = start; x <= stop; x++) {
            int cumul = projection.getValue(x);

            if (cumul > 0) {
                count++;
                meanHeight += cumul;
            }
        }

        if (count > 0) {
            meanHeight = (int) Math.rint(meanHeight / (double) count);
            logger.debug("isRangeVoid start:{} stop:{} meanHeight:{}", start, stop, meanHeight);
        }

        // TODO: use a specific constant?
        return meanHeight <= (params.maxSpaceCumul / 2);
    }

    //------------//
    // isStemLike //
    //------------//
    /**
     * Check whether the provided peak of cumulated pixels corresponds to a "stem".
     * <p>
     * We define a lookup rectangle using peak abscissa range.
     * The rectangle is searched for pixels that could make a "stem".
     *
     * @param peak the peak to check
     * @return true if OK
     */
    private boolean isStemLike (KeyEvent.Peak peak)
    {
        final Rectangle rect = new Rectangle(peak.start, roi.y, peak.getWidth(), roi.height);

        if (peak.getWidth() <= 2) {
            rect.grow(1, 0); // Slight margin on left & right of peak
        }

        boolean stem = hasStem(rect, staffFreeSource, params.coreStemLength, params.minBlackRatio);

        if (!stem) {
            logger.debug("Staff#{} {} no stem", getId(), peak);
        }

        return stem;
    }

    //--------------//
    // lastGoodPeak //
    //--------------//
    /**
     * Report the last valid peak found
     *
     * @return the last valid peak, if any
     */
    private KeyEvent.Peak lastGoodPeak ()
    {
        KeyEvent.Peak good = null;

        for (KeyEvent.Peak peak : peaks) {
            if (peak.isInvalid()) {
                break;
            }

            good = peak;
        }

        return good;
    }

    //-------------//
    // purgeGlyphs //
    //-------------//
    /**
     * Purge the population of glyph candidates as much as possible, since the cost
     * of their later combinations is very high.
     * <p>
     * Those of width 1 and stuck on right side of slice can be safely removed, since they
     * certainly belong to the stem of the next slice.
     * <p>
     * Those composed of just one (isolated) pixel are also removed, although this is more
     * questionable.
     *
     * @param glyphs the collection to purge
     * @param xMax   maximum abscissa in area
     */
    private void purgeGlyphs (List<Glyph> glyphs,
                              int xMax)
    {
        final int minWeight = 2;

        List<Glyph> toRemove = new ArrayList<Glyph>();

        for (Glyph glyph : glyphs) {
            if ((glyph.getWeight() < minWeight) || (glyph.getBounds().x == xMax)) {
                toRemove.add(glyph);
            }
        }

        if (!toRemove.isEmpty()) {
            glyphs.removeAll(toRemove);
        }
    }

    //------------//
    // refineStop //
    //------------//
    /**
     * Adjust the stop abscissa of key sig.
     *
     * @param lastGoodPeak last valid peak found
     * @param typicalTrail typical length after last peak (this depends on alter shape)
     * @param maxTrail     maximum length after last peak
     */
    private void refineStop (KeyEvent.Peak lastGoodPeak,
                             int typicalTrail,
                             int maxTrail)
    {
        final int xMin = (lastGoodPeak.start + typicalTrail) - 1;
        final int xMax = Math.min(projection.getStop(), lastGoodPeak.start + maxTrail);

        int minCount = Integer.MAX_VALUE;

        for (int x = xMin; x <= xMax; x++) {
            int count = projection.getValue(x);

            if (count < minCount) {
                range.stop = x - 1;
                minCount = count;
            }
        }
    }

    //-------//
    // reset //
    //-------//
    /**
     * Reset the class relevant parameters, so that a new browsing can be launched.
     */
    private void reset ()
    {
        for (Slice slice : slices) {
            KeyAlterInter alter = slice.getAlter();

            if (alter != null) {
                alter.delete();
            }
        }

        peaks.clear();
        events.clear();
        slices.clear();

        range.start = 0;
        range.stop = 0;
    }

    //--------------------//
    // retrieveComponents //
    //--------------------//
    /**
     * Look into sig area for key items, based on connected components.
     */
    private void retrieveComponents ()
    {
        logger.debug("Key for staff#{}", getId());

        // Key-sig area pixels
        ByteProcessor keyBuf = roi.getAreaPixels(staffFreeSource, range);
        RunTable runTable = new RunTableFactory(VERTICAL).createTable(keyBuf);
        List<Glyph> parts = GlyphFactory.buildGlyphs(runTable, new Point(range.start, roi.y));

        purgeGlyphs(parts, range.stop);

        final GlyphIndex glyphIndex = sheet.getGlyphIndex();

        for (ListIterator<Glyph> li = parts.listIterator(); li.hasNext();) {
            Glyph glyph = li.next();
            glyph = glyphIndex.registerOriginal(glyph);
            glyph.addGroup(Group.ALTER_PART);
            system.addFreeGlyph(glyph);
            li.set(glyph);
        }

        MultipleAdapter adapter = new MultipleAdapter(parts, Collections.singleton(keyShape));
        new GlyphCluster(adapter, null).decompose();

        for (Slice slice : slices) {
            if (slice.eval != null) {
                double grade = Inter.intrinsicRatio * slice.eval.grade;

                if (grade >= Grades.keyAlterMinGrade) {
                    KeyAlterInter alterInter = KeyAlterInter.create(
                            slice.glyph,
                            slice.eval.shape,
                            grade,
                            staff);

                    if (alterInter != null) {
                        sig.addVertex(alterInter);
                        slice.alter = alterInter;
                    }
                }
            }

            logger.debug("{}", slice);
        }

        // If one or several slices lack alter inter, process them by hard slice extraction
    }

    //-------------------//
    // retrieveSignature //
    //-------------------//
    /**
     * Retrieve the staff signature value.
     * This is based on the average value of peaks intervals, computed on the short ones (the
     * intervals shorter or equal to the mean value).
     *
     * @return -flats, 0 or +sharps
     */
    private int retrieveSignature ()
    {
        if (peaks.isEmpty()) {
            return 0;
        }

        int last = peaks.size() - 1;

        if (peaks.get(last).isInvalid()) {
            if (last > 0) {
                last--;
            } else {
                logger.debug("no valid peak");

                return 0;
            }
        }

        if (last > 0) {
            // Compute mean value of short intervals
            double meanDx = (peaks.get(last).getCenter() - peaks.get(0).getCenter()) / last;
            int shorts = 0;
            double sum = 0;

            for (int i = 1; i <= last; i++) {
                double interval = peaks.get(i).getCenter() - peaks.get(i - 1).getCenter();

                if (interval <= meanDx) {
                    shorts++;
                    sum += interval;
                }
            }

            double meanShort = sum / shorts;
            int offset = peaks.get(0).start - range.start;

            if (meanShort < params.minFlatDelta) {
                keyShape = Shape.SHARP;
            } else if (meanShort > params.maxSharpDelta) {
                keyShape = Shape.FLAT;
            } else {
                keyShape = (offset > params.offsetThreshold) ? Shape.SHARP : Shape.FLAT;
            }

            // For sharps, peaks count must be an even number
            if (keyShape == Shape.SHARP) {
                if (((last + 1) % 2) != 0) {
                    if (peaks.get(peaks.size() - 1).isInvalid()) {
                        peaks.remove(peaks.size() - 1);
                    }

                    peaks.get(peaks.size() - 1).setInvalid();
                    last--;
                }

                return (last + 1) / 2;
            } else {
                return -(last + 1);
            }
        } else {
            keyShape = Shape.FLAT;

            return -1;
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //--------//
    // Column //
    //--------//
    /**
     * Manages the system consistency for a column of staff-based KeyBuilder instances.
     */
    public static class Column
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final SystemInfo system;

        /** Map of key builders. (one per staff) */
        private final Map<Staff, KeyBuilder> builders = new TreeMap<Staff, KeyBuilder>(Staff.byId);

        /** Theoretical abscissa offset for each slice. */
        private List<Integer> globalOffsets;

        //~ Constructors ---------------------------------------------------------------------------
        public Column (SystemInfo system)
        {
            this.system = system;
        }

        //~ Methods --------------------------------------------------------------------------------
        //---------//
        // addPlot //
        //---------//
        public String addPlot (Plotter plotter,
                               Staff staff)
        {
            int measureStart = staff.getHeaderStart();
            KeyBuilder builder = new KeyBuilder(staff, 0, measureStart, 0, true);

            builder.addPlot(plotter);

            KeyInter key = staff.getHeader().key;

            return (key != null) ? ("key:" + key.getFifths()) : null;
        }

        //--------------//
        // retrieveKeys //
        //--------------//
        /**
         * Retrieve the column of staves keys.
         *
         * @param projectionWidth desired width for projection
         * @return the ending abscissa offset of keys column WRT measure start
         */
        public int retrieveKeys (int projectionWidth)
        {
            // Define each staff key-sig area
            for (Staff staff : system.getStaves()) {
                int measureStart = staff.getHeaderStart();
                int clefStop = staff.getClefStop();
                int browseStart = (clefStop != 0) ? (clefStop + 1) : staff.getHeaderStop();

                builders.put(
                        staff,
                        new KeyBuilder(staff, projectionWidth, measureStart, browseStart, true));
            }

            // Process each staff separately
            for (KeyBuilder builder : builders.values()) {
                builder.process();
            }

            // Make sure non-empty key areas do have keys
            //TODO
            //
            // Check keys alignment at system level, if applicable
            if (system.getStaves().size() > 1) {
                checkKeysAlignment();
            }

            // Adjust each individual alter pitch, according to best matching key-sig
            for (KeyBuilder builder : builders.values()) {
                builder.adjustPitches();
            }

            // Push StaffHeader
            int maxKeyOffset = 0;

            for (Staff staff : system.getStaves()) {
                int measureStart = staff.getHeaderStart();
                Integer keyStop = staff.getKeyStop();

                if (keyStop != null) {
                    maxKeyOffset = Math.max(maxKeyOffset, keyStop - measureStart);
                }
            }

            return maxKeyOffset;
        }

        //--------------------//
        // checkKeysAlignment //
        //--------------------//
        /**
         * Verify vertical alignment of keys within the same system.
         */
        private void checkKeysAlignment ()
        {
            // Get theoretical abscissa offset for each slice in the system
            final int meanSliceWidth = getGlobalOffsets();
            final int maxSliceDist = system.getSheet().getScale().toPixels(constants.maxSliceDist);

            // Missing initial slices?
            // Check that each initial inter is located at proper offset
            for (KeyBuilder builder : builders.values()) {
                for (int i = 0; i < builder.getSlices().size(); i++) {
                    int x = builder.getSlices().get(i).getRect().x;
                    int offset = x - builder.getMeasureStart();
                    Integer index = getBestSliceIndex(offset, maxSliceDist);

                    if (index != null) {
                        if (index > i) {
                            // Insert missing slice!
                            logger.debug("Staff#{} slice inserted at index:{}", builder.getId(), i);
                            builder.insertSlice(i, globalOffsets.get(i));
                        }
                    } else {
                        // Slice too far on left
                        logger.debug(
                                "Staff#{} misaligned slice index:{} x:{}",
                                builder.getId(),
                                i,
                                x);

                        int newStart = builder.getMeasureStart() + globalOffsets.get(0);
                        Integer browseStart = builder.getBrowseStart();

                        if (browseStart != null) {
                            newStart = (browseStart + newStart) / 2; // Safer
                        }

                        builder.reprocess(newStart);
                    }
                }
            }

            // Missing trailing slices?
            for (KeyBuilder builder : builders.values()) {
                List<KeyBuilder.Slice> slices = builder.getSlices();

                if (slices.size() < globalOffsets.size()) {
                    for (int i = slices.size(); i < globalOffsets.size(); i++) {
                        int x = (builder.getMeasureStart() + globalOffsets.get(i)) - 1;
                        logger.debug(
                                "Staff#{} Should investigate slice index:{} at x:{}",
                                builder.getId(),
                                i,
                                x);

                        Slice slice = builder.scanSlice(x, (x + meanSliceWidth) - 1);

                        if (slice == null) {
                            // Nothing found at all, so stop slice sequence here
                            break;
                        }
                    }
                }
            }

            // At this point in the system column, all relevant slices (slices with some pixels)
            // have been allocated, but some slices may have no recognized alter assigned.
            // Perhaps a phase #3 with relaxed params could be tried? TODO
            for (KeyBuilder builder : builders.values()) {
                List<KeyBuilder.Slice> slices = builder.getSlices();

                for (int i = 0; i < slices.size(); i++) {
                    Slice slice = slices.get(i);
                    Inter inter = slice.getAlter();

                    if (inter == null) {
                        logger.info("Staff#{} weird key {}", builder.getId(), slice);
                    }
                }
            }
        }

        //-------------------//
        // getBestSliceIndex //
        //-------------------//
        /**
         * Determine the corresponding global index for the provided abscissa offset.
         *
         * @param offset       slice offset
         * @param maxSliceDist maximum acceptable abscissa distance
         * @return the global index found or null
         */
        private Integer getBestSliceIndex (int offset,
                                           int maxSliceDist)
        {
            Integer bestIndex = null;
            double bestDist = Double.MAX_VALUE;

            for (int i = 0; i < globalOffsets.size(); i++) {
                int gOffset = globalOffsets.get(i);
                double dist = Math.abs(gOffset - offset);

                if (bestDist > dist) {
                    bestDist = dist;
                    bestIndex = i;
                }
            }

            if (bestDist <= maxSliceDist) {
                return bestIndex;
            } else {
                return null;
            }
        }

        //------------------//
        // getGlobalOffsets //
        //------------------//
        /**
         * Retrieve the theoretical offset abscissa for all slices in the system.
         *
         * @return the mean slice width
         */
        private int getGlobalOffsets ()
        {
            int sliceCount = 0;
            int meanSliceWidth = 0;

            // Check that key-sig items appear vertically aligned between staves
            List<Population> pops = new ArrayList<Population>();
            List<Double> vals = new ArrayList<Double>();

            for (KeyBuilder builder : builders.values()) {
                ///StringBuilder sb = new StringBuilder();
                ///sb.append("S#").append(builder.getId());
                for (int i = 0; i < builder.getSlices().size(); i++) {
                    KeyBuilder.Slice slice = builder.getSlices().get(i);
                    sliceCount++;
                    meanSliceWidth += slice.getRect().width;

                    int x = slice.getRect().x;
                    int offset = x - builder.getMeasureStart();

                    ///sb.append(" ").append(i).append(":").append(offset);
                    final Population pop;

                    if (i >= pops.size()) {
                        pops.add(new Population());
                    }

                    pop = pops.get(i);
                    pop.includeValue(offset);
                    vals.add((double) offset);
                }

                ///logger.debug(sb.toString());
            }

            int G = pops.size();
            Clustering.Gaussian[] laws = new Clustering.Gaussian[G];

            for (int i = 0; i < G; i++) {
                Population pop = pops.get(i);
                laws[i] = new Clustering.Gaussian(pop.getMeanValue(), 1.0); //pop.getStandardDeviation());
            }

            double[] table = new double[vals.size()];

            for (int i = 0; i < vals.size(); i++) {
                table[i] = vals.get(i);
            }

            double[] pi = Clustering.EM(table, laws);

            List<Integer> theoreticals = new ArrayList<Integer>();

            for (int k = 0; k < G; k++) {
                Clustering.Gaussian law = laws[k];
                theoreticals.add((int) Math.rint(law.getMean()));
            }

            globalOffsets = theoreticals;

            if (sliceCount > 0) {
                meanSliceWidth = (int) Math.rint(meanSliceWidth / (double) sliceCount);
            }

            logger.debug("globalOffsets:{} meanSliceWidth:{}", globalOffsets, meanSliceWidth);

            return meanSliceWidth;
        }
    }

    //-------//
    // Slice //
    //-------//
    /**
     * Represents a rectangular slice of a key-sig, likely to contain an alteration item.
     */
    protected class Slice
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Rectangular slice definition. */
        private final Rectangle rect;

        /** Best glyph, if any. */
        private Glyph glyph;

        /** Best evaluation, if any. */
        private Evaluation eval;

        /** Retrieved alter item, if any. */
        private KeyAlterInter alter;

        //~ Constructors ---------------------------------------------------------------------------
        public Slice (Rectangle rect)
        {
            this.rect = rect;
        }

        //~ Methods --------------------------------------------------------------------------------
        /**
         * @return the rectangle definition
         */
        public Rectangle getRect ()
        {
            return rect;
        }

        @Override
        public String toString ()
        {
            StringBuilder sb = new StringBuilder("Slice{");
            sb.append("#").append(getId());

            if (alter != null) {
                sb.append(" ").append(alter);
            }

            if (glyph != null) {
                sb.append(String.format(" glyph#%d %.3f", glyph.getId(), eval.grade));
            }

            sb.append("}");

            return sb.toString();
        }

        /**
         * @return the alter, if any
         */
        KeyAlterInter getAlter ()
        {
            return alter;
        }

        int getId ()
        {
            return 1 + slices.indexOf(this);
        }
    }

    //-----------------//
    // AbstractAdapter //
    //-----------------//
    /**
     * Abstract adapter for retrieving items.
     */
    private abstract class AbstractAdapter
            extends GlyphCluster.AbstractAdapter
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Relevant shapes. */
        protected final EnumSet<Shape> targetShapes = EnumSet.noneOf(Shape.class);

        //~ Constructors ---------------------------------------------------------------------------
        public AbstractAdapter (List<Glyph> parts,
                                Set<Shape> targetShapes)
        {
            super(parts, params.maxGlyphGap);
            this.targetShapes.addAll(targetShapes);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public boolean isSizeAcceptable (Rectangle box)
        {
            return (box.height <= params.maxGlyphHeight) && (box.width <= params.maxGlyphWidth);
        }

        @Override
        public boolean isWeightAcceptable (int weight)
        {
            return weight >= params.minGlyphWeight;
        }

        protected void evaluateSliceGlyph (Slice slice,
                                           Glyph glyph)
        {
            trials++;

            Evaluation[] evals = classifier.getNaturalEvaluations(glyph, sheet.getInterline());

            for (Shape shape : targetShapes) {
                Evaluation eval = evals[shape.ordinal()];

                if (glyph.getId() == 0) {
                    glyph = sheet.getGlyphIndex().registerOriginal(glyph);
                    system.addFreeGlyph(glyph);
                }

                logger.debug("glyph#{} width:{} eval:{}", glyph.getId(), glyph.getWidth(), eval);

                if ((slice.eval == null) || (slice.eval.grade < eval.grade)) {
                    slice.eval = eval;
                    slice.glyph = glyph;
                }
            }
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Scale.Fraction maxSliceDist = new Scale.Fraction(
                0.5,
                "Maximum x distance to theoretical slice");

        private final Scale.LineFraction maxSpaceCumul = new Scale.LineFraction(
                2.0,
                "Maximum cumul value in space (specified WRT staff line thickness)");

        private final Scale.Fraction coreStemLength = new Scale.Fraction(
                2.0,
                "Core length for alteration \"stem\" (flat or sharp)");

        private final Constant.Ratio minBlackRatio = new Constant.Ratio(
                0.75,
                "Minimum ratio of black rows in core length");

        private final Scale.Fraction typicalAlterationHeight = new Scale.Fraction(
                2.5,
                "Typical alteration height (flat or sharp)");

        private final Constant.Ratio peakHeightRatio = new Constant.Ratio(
                0.5,
                "Ratio of height to detect peaks");

        private final Scale.Fraction preStaffMargin = new Scale.Fraction(
                2.0,
                "Horizontal margin before staff left (for plot display)");

        private final Scale.Fraction maxFirstPeakOffset = new Scale.Fraction(
                2.0,
                "Maximum x offset of first peak (WRT browse start)");

        private final Scale.Fraction maxPeakCumul = new Scale.Fraction(
                4.0,
                "Maximum cumul value to accept peak (absolute value)");

        private final Scale.Fraction maxPeakWidth = new Scale.Fraction(
                0.4,
                "Maximum width to accept peak (measured at threshold height)");

        private final Scale.Fraction maxFlatHeading = new Scale.Fraction(
                0.4,
                "Maximum heading length before peak for a flat item");

        private final Scale.Fraction flatTrail = new Scale.Fraction(
                1.0,
                "Typical trailing length after peak for a flat item");

        private final Scale.Fraction minFlatTrail = new Scale.Fraction(
                0.8,
                "Minimum trailing length after peak for a flat item");

        private final Scale.Fraction maxFlatTrail = new Scale.Fraction(
                1.3,
                "Maximum trailing length after peak for a flat item");

        private final Scale.Fraction sharpTrail = new Scale.Fraction(
                0.3,
                "Typical trailing length after last peak for a sharp item");

        private final Scale.Fraction minSharpTrail = new Scale.Fraction(
                0.2,
                "Minimum trailing length after last peak for a sharp item");

        private final Scale.Fraction maxSharpTrail = new Scale.Fraction(
                0.5,
                "Maximum trailing length after last peak for a sharp item");

        private final Scale.Fraction maxPeakDx = new Scale.Fraction(
                1.4,
                "Maximum delta abscissa between peaks");

        private final Scale.Fraction maxSharpDelta = new Scale.Fraction(
                0.75,
                "Maximum short peak delta for sharps");

        private final Scale.Fraction minFlatDelta = new Scale.Fraction(
                0.5,
                "Minimum short peak delta for flats");

        private final Scale.Fraction offsetThreshold = new Scale.Fraction(
                0.1,
                "Threshold on first peak offset that differentiates flat & sharp");

        private final Scale.Fraction maxGlyphGap = new Scale.Fraction(
                1.5,
                "Maximum distance between two glyphs of a single alter symbol");

        private final Scale.Fraction maxGlyphWidth = new Scale.Fraction(
                2.0,
                "Maximum glyph width");

        private final Scale.Fraction maxGlyphHeight = new Scale.Fraction(
                3.5,
                "Maximum glyph height");

        private final Scale.AreaFraction minGlyphWeight = new Scale.AreaFraction(
                0.3,
                "Minimum glyph weight");

        // Beware: A too small value might miss the whole key-sig
        private final Scale.Fraction maxFirstSpaceWidth = new Scale.Fraction(
                1.75,
                "Maximum initial space before key signature");

        // Beware: A too small value might miss final key-sig items
        private final Scale.Fraction maxInnerSpace = new Scale.Fraction(
                0.7,
                "Maximum inner space within key signature");
    }

    //-----------------//
    // MultipleAdapter //
    //-----------------//
    /**
     * Adapter for retrieving all items of the key (in key area).
     */
    private class MultipleAdapter
            extends AbstractAdapter
    {
        //~ Constructors ---------------------------------------------------------------------------

        public MultipleAdapter (List<Glyph> parts,
                                Set<Shape> targetShapes)
        {
            super(parts, targetShapes);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void evaluateGlyph (Glyph glyph)
        {
            // Retrieve impacted slice
            final Slice slice = sliceOf(glyph);

            if (slice != null) {
                evaluateSliceGlyph(slice, glyph);
            }
        }

        private Slice sliceOf (Glyph glyph)
        {
            final Point centroid = glyph.getCentroid();

            for (Slice slice : slices) {
                if (GeoUtil.xEmbraces(slice.rect, centroid.x)) {
                    return slice;
                }
            }

            return null;
        }
    }

    //------------//
    // Parameters //
    //------------//
    private static class Parameters
    {
        //~ Instance fields ------------------------------------------------------------------------

        final int preStaffMargin;

        final int maxFirstPeakOffset;

        final int maxFirstSpaceWidth;

        final int maxInnerSpace;

        final int minPeakCumul;

        final int maxSpaceCumul;

        final int coreStemLength;

        final double minBlackRatio;

        final int maxPeakCumul;

        final int maxPeakWidth;

        final int maxFlatHeading;

        final int flatTrail;

        final int minFlatTrail;

        final int maxFlatTrail;

        final int sharpTrail;

        final int minSharpTrail;

        final int maxSharpTrail;

        final int maxPeakDx;

        final double maxSharpDelta;

        final double minFlatDelta;

        final double offsetThreshold;

        final double maxGlyphGap;

        final double maxGlyphWidth;

        final double maxGlyphHeight;

        final int minGlyphWeight;

        //~ Constructors ---------------------------------------------------------------------------
        public Parameters (Scale scale)
        {
            preStaffMargin = scale.toPixels(constants.preStaffMargin);
            maxFirstPeakOffset = scale.toPixels(constants.maxFirstPeakOffset);
            maxFirstSpaceWidth = scale.toPixels(constants.maxFirstSpaceWidth);
            maxInnerSpace = scale.toPixels(constants.maxInnerSpace);
            maxSpaceCumul = scale.toPixels(constants.maxSpaceCumul);
            coreStemLength = scale.toPixels(constants.coreStemLength);
            minBlackRatio = constants.minBlackRatio.getValue();
            maxPeakCumul = scale.toPixels(constants.maxPeakCumul);
            maxPeakWidth = scale.toPixels(constants.maxPeakWidth);
            maxFlatHeading = scale.toPixels(constants.maxFlatHeading);
            flatTrail = scale.toPixels(constants.flatTrail);
            minFlatTrail = scale.toPixels(constants.minFlatTrail);
            maxFlatTrail = scale.toPixels(constants.maxFlatTrail);
            sharpTrail = scale.toPixels(constants.sharpTrail);
            minSharpTrail = scale.toPixels(constants.minSharpTrail);
            maxSharpTrail = scale.toPixels(constants.maxSharpTrail);
            maxPeakDx = scale.toPixels(constants.maxPeakDx);
            maxSharpDelta = scale.toPixelsDouble(constants.maxSharpDelta);
            minFlatDelta = scale.toPixelsDouble(constants.minFlatDelta);
            offsetThreshold = scale.toPixelsDouble(constants.offsetThreshold);
            maxGlyphGap = scale.toPixelsDouble(constants.maxGlyphGap);
            maxGlyphWidth = scale.toPixelsDouble(constants.maxGlyphWidth);
            maxGlyphHeight = scale.toPixelsDouble(constants.maxGlyphHeight);
            minGlyphWeight = scale.toPixels(constants.minGlyphWeight);

            // Maximum alteration contribution (on top of staff lines)
            double maxAlterContrib = constants.typicalAlterationHeight.getValue() * (scale.getInterline()
                                                                                     - scale.getMainFore());
            minPeakCumul = (int) Math.rint(
                    (5 * scale.getMainFore())
                    + (constants.peakHeightRatio.getValue() * maxAlterContrib));
        }
    }

    //-----//
    // Roi //
    //-----//
    /**
     * Handles the region of interest for key retrieval.
     */
    private static class Roi
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Region top ordinate. */
        public final int y;

        /** Region height. */
        public final int height;

        //~ Constructors ---------------------------------------------------------------------------
        public Roi (int y,
                    int height)
        {
            this.y = y;
            this.height = height;
        }

        //~ Methods --------------------------------------------------------------------------------
        /**
         * Report the pixels buffer for the whole key area
         *
         * @param source pixel source (staff free)
         * @param range  start/stop values for key area
         * @return the buffer of area pixels
         */
        public ByteProcessor getAreaPixels (ByteProcessor source,
                                            Range range)
        {
            Rectangle keyRect = new Rectangle(
                    range.start,
                    y,
                    range.stop - range.start + 1,
                    height);

            ByteProcessor keyBuffer = new ByteProcessor(keyRect.width, height);
            keyBuffer.copyBits(source, -keyRect.x, -y, Blitter.COPY);

            return keyBuffer;
        }

        /**
         * Report the pixels buffer for just a slice
         *
         * @param source pixel source (staff free)
         * @param slice  the current slice
         * @param slices sequence of all slices
         * @return the buffer of slice pixels
         */
        public ByteProcessor getSlicePixels (ByteProcessor source,
                                             Slice slice,
                                             List<Slice> slices)
        {
            Rectangle sRect = slice.getRect();
            BufferedImage sImage = new BufferedImage(sRect.width, sRect.height, TYPE_BYTE_GRAY);
            ByteProcessor sBuffer = new ByteProcessor(sImage);
            sBuffer.copyBits(source, -sRect.x, -sRect.y, Blitter.COPY);

            // Erase good key items from adjacent slices, if any
            final int idx = slices.indexOf(slice);
            final Integer prevIdx = (idx > 0) ? (idx - 1) : null;
            final Integer nextIdx = (idx < (slices.size() - 1)) ? (idx + 1) : null;
            Graphics2D g = null;

            for (Integer i : new Integer[]{prevIdx, nextIdx}) {
                if (i != null) {
                    final Slice sl = slices.get(i);

                    if (sl.alter != null) {
                        final Glyph glyph = sl.alter.getGlyph();

                        if (glyph.getBounds().intersects(sRect)) {
                            if (g == null) {
                                g = sImage.createGraphics();
                                g.setColor(Color.white);
                            }

                            final Point offset = new Point(
                                    glyph.getLeft() - sRect.x,
                                    glyph.getTop() - sRect.y);
                            logger.debug("Erasing glyph#{} from {}", glyph.getId(), slice);
                            glyph.getRunTable().render(g, offset);
                        }
                    }
                }
            }

            if (g != null) {
                g.dispose();
            }

            return sBuffer;
        }
    }

    //---------------//
    // SingleAdapter //
    //---------------//
    /**
     * Adapter for retrieving one key item (in a slice).
     */
    private class SingleAdapter
            extends AbstractAdapter
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Related slice. */
        private final Slice slice;

        //~ Constructors ---------------------------------------------------------------------------
        public SingleAdapter (Slice slice,
                              List<Glyph> parts,
                              Set<Shape> targetShapes)
        {
            super(parts, targetShapes);
            this.slice = slice;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void evaluateGlyph (Glyph glyph)
        {
            evaluateSliceGlyph(slice, glyph);
        }
    }
}
