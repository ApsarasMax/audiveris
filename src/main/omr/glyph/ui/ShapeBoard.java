//----------------------------------------------------------------------------//
//                                                                            //
//                            S h a p e B o a r d                             //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright (C) Herve Bitteur 2000-2010. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.glyph.ui;

import omr.Main;

import omr.glyph.Glyphs;
import omr.glyph.Shape;
import omr.glyph.ShapeRange;
import omr.glyph.facets.Glyph;

import omr.lag.LagOrientation;

import omr.log.Logger;

import omr.score.common.PixelPoint;
import omr.score.common.ScorePoint;
import omr.score.ui.ScoreView;

import omr.script.InsertTask;

import omr.selection.MouseMovement;
import omr.selection.UserEvent;

import omr.sheet.Sheet;

import omr.ui.Board;
import omr.ui.dnd.AbstractGhostDropListener;
import omr.ui.dnd.GhostDropAdapter;
import omr.ui.dnd.GhostDropEvent;
import omr.ui.dnd.GhostDropListener;
import omr.ui.dnd.GhostGlassPane;
import omr.ui.dnd.GhostImageAdapter;
import omr.ui.dnd.GhostMotionAdapter;
import omr.ui.dnd.ScreenPoint;
import omr.ui.symbol.ShapeSymbol;
import omr.ui.util.Panel;
import omr.ui.view.RubberPanel;

import java.awt.*;
import java.awt.event.*;
import java.util.*;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;

/**
 * Class {@code ShapeBoard} hosts a palette of shapes for direct insertion by
 * means of drag and drop to the target score/sheet view
 *
 * @author Hervé Bitteur
 */
public class ShapeBoard
    extends Board
{
    //~ Static fields/initializers ---------------------------------------------

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(ShapeBoard.class);

    /** To force the width of the various panels */
    private static final int BOARD_WIDTH = 280;

    /**
     * To force the height of the various shape panels (just a dirty hack)
     */
    private static final Map<ShapeRange, Integer> heights = new HashMap<ShapeRange, Integer>();

    static {
        heights.put(ShapeRange.Accidentals, 40);
        heights.put(ShapeRange.Articulations, 60);
        heights.put(ShapeRange.Barlines, 100);
        heights.put(ShapeRange.Beams, 60);
        heights.put(ShapeRange.Clefs, 140);
        heights.put(ShapeRange.Dynamics, 200);
        heights.put(ShapeRange.Flags, 130);
        heights.put(ShapeRange.HeadAndFlags, 140);
        heights.put(ShapeRange.NoteHeads, 40);
        heights.put(ShapeRange.Markers, 120);
        heights.put(ShapeRange.Notes, 40);
        heights.put(ShapeRange.Ornaments, 80);
        heights.put(ShapeRange.Rests, 120);
        heights.put(ShapeRange.Times, 130);
        heights.put(ShapeRange.Others, 80);
    }

    //~ Instance fields --------------------------------------------------------

    /** Related sheet */
    private final Sheet sheet;

    /** The controller in charge of symbol assignments */
    private final SymbolsController symbolsController;

    /**
     * Method called when a range is selected: the panel of ranges is replaced
     * by the panel of shapes that compose the selected range.
     */
    private ActionListener rangeListener = new ActionListener() {
        public void actionPerformed (ActionEvent e)
        {
            // Remove panel of ranges
            body.remove(rangesPanel);

            // Replace by proper panel of range shapes
            String     rangeName = ((JButton) e.getSource()).getName();
            ShapeRange range = ShapeRange.getRange(rangeName);
            shapesPanel = shapesPanels.get(range);

            if (shapesPanel == null) {
                // Lazily populate the map of shapesPanel instances
                shapesPanels.put(range, shapesPanel = defineShapesPanel(range));
            }

            body.add(shapesPanel);

            // Perhaps this is too much ... TODO
            JFrame frame = Main.getGui()
                               .getFrame();
            frame.invalidate();
            frame.validate();
            frame.repaint();
        }
    };

    /**
     * Method called when a panel of shapes is closed: the panel is replaced
     * by the panel of ranges to allow the selection of another range.
     */
    private ActionListener closeListener = new ActionListener() {
        public void actionPerformed (ActionEvent e)
        {
            // Remove current panel of shapes
            body.remove(shapesPanel);

            // Replace by panel of ranges
            body.add(rangesPanel);

            // Perhaps this is too much ... TODO
            JFrame frame = Main.getGui()
                               .getFrame();
            frame.invalidate();
            frame.validate();
            frame.repaint();
        }
    };

    /**
     * Method called when the button is clicked
     */
    private MouseListener mouseListener = new MouseInputAdapter() {
        @Override
        public void mouseClicked (MouseEvent e)
        {
            long newClick = System.currentTimeMillis();

            if ((newClick - lastClick) <= maxClickDelay) {
                Glyph glyph = sheet.getVerticalLag()
                                   .getSelectedGlyph();

                if (glyph != null) {
                    JButton button = (JButton) e.getSource();
                    Shape   shape = Shape.valueOf(button.getName());

                    // Actually assign the shape
                    symbolsController.asyncAssignGlyphs(
                        Glyphs.sortedSet(glyph),
                        shape,
                        false);
                }
            }

            lastClick = newClick;
        }
    };

    /** Panel of all ranges */
    private final Panel rangesPanel;

    /** Map of shape panels */
    private final Map<ShapeRange, Panel> shapesPanels = new HashMap<ShapeRange, Panel>();

    /** Current panel of shapes */
    private Panel shapesPanel;

    /** The main target: score view */
    private final ScoreView scoreView;

    /** The window on score */
    private final JViewport scoreViewport;

    /** Time of last click */
    private long lastClick;

    /** Maximum delay for a double-click */
    private long maxClickDelay = Main.getGui()
                                     .getMaxDoubleClickDelay();

    //~ Constructors -----------------------------------------------------------

    //-------------//
    // ShapeBoard //
    //-------------//
    /**
     * Create a new ShapeBoard object
     * @param symbolsController the UI controller for symbols
     * @param sheet the related sheet
     */
    public ShapeBoard (SymbolsController symbolsController,
                       Sheet             sheet)
    {
        super("Palette", "Shapes", null, null);
        this.symbolsController = symbolsController;
        this.sheet = sheet;

        scoreView = sheet.getScore()
                         .getFirstView();
        scoreViewport = scoreView.getViewport();
        body.add(rangesPanel = defineRangesPanel());
    }

    //~ Methods ----------------------------------------------------------------

    //---------//
    // onEvent //
    //---------//
    public void onEvent (UserEvent event)
    {
        // Empty
    }

    //-------------------//
    // defineRangesPanel //
    //-------------------//
    /**
     * Define the panel of ranges
     * @return the global panel of ranges
     */
    private Panel defineRangesPanel ()
    {
        Panel panel = new Panel();
        panel.setNoInsets();
        panel.setPreferredSize(new Dimension(BOARD_WIDTH, 120));

        FlowLayout layout = new FlowLayout();
        layout.setAlignment(FlowLayout.LEADING);
        panel.setLayout(layout);

        for (ShapeRange range : ShapeRange.getRanges()) {
            Shape rep = range.getRep();

            if (rep != null) {
                JButton button = new JButton();
                button.setIcon(rep.getSymbol());
                button.setName(range.getName());
                button.addActionListener(rangeListener);
                button.setToolTipText(range.getName());
                button.setBorderPainted(false);
                //        button.setFocusPainted(true);
                //        button.setContentAreaFilled(true);
                panel.add(button);
            }
        }

        return panel;
    }

    //-------------------//
    // defineShapesPanel //
    //-------------------//
    /**
     * Define the panel of shapes for a given range
     * @param range the given range of shapes
     * @return the panel of shapes for the provided range
     */
    private Panel defineShapesPanel (ShapeRange range)
    {
        Panel panel = new Panel();
        panel.setNoInsets();
        panel.setPreferredSize(new Dimension(BOARD_WIDTH, heights.get(range)));

        FlowLayout layout = new FlowLayout();
        layout.setAlignment(FlowLayout.LEADING);
        panel.setLayout(layout);

        // Button to close this shapes panel and return to ranges panel
        JButton close = new JButton(range.getName());
        close.addActionListener(closeListener);
        close.setToolTipText("Back to ranges");
        close.setBorderPainted(false);
        panel.add(close);

        // Listeners for DnD
        GhostGlassPane      glassPane = Main.getGui()
                                            .getGlassPane();
        GhostMotionAdapter  motionListener = new GhostMotionAdapter(glassPane);
        MouseMotionListener locationListener = new LocationListener();
        GhostDropListener   dropListener = new MyDropListener();

        // One button per shape
        for (Shape shape : range.getShapes()) {
            JButton button = new JButton();
            button.setIcon(shape.getSymbol());
            button.setName(shape.toString());
            button.setToolTipText(shape.toString());
            button.setBorderPainted(false);
            button.addMouseMotionListener(motionListener);
            button.addMouseMotionListener(locationListener);

            // Ability to use the button for direct assignment via double-click
            button.addMouseListener(mouseListener);

            // Directly use the shape icon image for DnD ghost
            ShapeSymbol             symbol = shape.getSymbol();
            GhostDropAdapter<Shape> imageAdapter = new GhostImageAdapter<Shape>(
                glassPane,
                shape,
                symbol.getIconImage());
            imageAdapter.addGhostDropListener(dropListener);
            button.addMouseListener(imageAdapter);

            panel.add(button);
        }

        return panel;
    }

    //~ Inner Classes ----------------------------------------------------------

    //------------------//
    // LocationListener //
    //------------------//
    /**
     * A listener in charge of forwarding the current mouse location in terms
     * of ScoreLocation (and transitively SheetLocation)
     */
    private class LocationListener
        extends MouseMotionAdapter
    {
        //~ Methods ------------------------------------------------------------

        @Override
        public void mouseDragged (MouseEvent e)
        {
            // Publish the sheet position
            ScreenPoint screenPoint = new ScreenPoint(
                e.getComponent(),
                e.getPoint());

            // The (zoomed) score view
            if (screenPoint.isInComponent(scoreViewport)) {
                RubberPanel view = (RubberPanel) scoreViewport.getView();
                Point       localPt = screenPoint.getLocalPoint(view);
                view.getZoom()
                    .unscale(localPt);
                view.pointSelected(localPt, MouseMovement.DRAGGING);
            }
        }
    }

    //----------------//
    // MyDropListener //
    //----------------//
    private class MyDropListener
        extends AbstractGhostDropListener<Shape>
    {
        //~ Constructors -------------------------------------------------------

        public MyDropListener ()
        {
            // Target is the score view
            super(scoreViewport);
        }

        //~ Methods ------------------------------------------------------------

        @Override
        public void ghostDropped (GhostDropEvent<Shape> e)
        {
            ScreenPoint screenPoint = e.getDropLocation();

            if (screenPoint.isInComponent(scoreViewport)) {
                RubberPanel view = (RubberPanel) scoreViewport.getView();
                Point       localPt = screenPoint.getLocalPoint(view);
                view.getZoom()
                    .unscale(localPt);

                ScorePoint scrPt = new ScorePoint(localPt.x, localPt.y);
                PixelPoint pixPt = scoreView.toPixelPoint(scrPt);

                // Asynchronously insert the desired shape at proper location
                new InsertTask(
                    e.getAction(),
                    Collections.singleton(pixPt),
                    LagOrientation.VERTICAL).launch(sheet);
            }
        }
    }
}