// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.plugins.tigerreview.AlignmentAnalyzer.AlignmentResult;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataLoader;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataLoader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Side panel for TIGER review and surface suggestion results.
 *
 * Uses a tabbed layout to separate TIGER review results from surface
 * suggestions. Each tab uses an independent analyzer and provides
 * Fix / Fix All controls.
 */
public class TIGERReviewDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener, CommandQueuePreciseListener {

    private final JTabbedPane tabbedPane;

    // TIGER review tab
    private final DefaultMutableTreeNode tigerRoot;
    private final JTree tigerTree;

    // Surface suggestions tab
    private final DefaultMutableTreeNode surfaceRoot;
    private final JTree surfaceTree;

    // Speed limit tab
    private final DefaultMutableTreeNode speedLimitRoot;
    private final JTree speedLimitTree;

    // Alignment tab
    private final DefaultMutableTreeNode alignmentRoot;
    private final JTree alignmentTree;
    private final AlignmentTaggingPanel taggingPanel;

    private List<ReviewResult> tigerResults = new ArrayList<>();
    private List<SurfaceSuggestion> surfaceResults = new ArrayList<>();
    private List<SpeedLimitSuggestion> speedLimitResults = new ArrayList<>();
    private List<AlignmentResult> alignmentResults = new ArrayList<>();

    private final AbstractAction analyzeAction;
    private final AbstractAction fixAction;

    /** Guard to prevent selection feedback loop */
    private boolean updatingSelection;

    /** Track running analysis to avoid concurrent runs */
    private SwingWorker<Void, Void> currentWorker;

    /**
     * After a fix-then-reanalyze cycle, the leaf index to select in the active tab.
     * -1 means no pending selection (normal analyze). The index refers to a flat
     * enumeration of leaf nodes across all category groups in the active tree.
     */
    private int pendingLeafIndex = -1;

    /** Which tab index the pending selection targets (so we select in the right tree). */
    private int pendingTabIndex = -1;

    /** Sort mode for the Alignment tab tree. */
    enum AlignmentSortMode {
        NAME, LENGTH, NODE_COUNT
    }

    private AlignmentSortMode alignmentSortMode = AlignmentSortMode.NAME;
    private boolean alignmentSortReversed;

    public TIGERReviewDialog() {
        super(
            tr("TIGER ROAR"),
            "tiger-review",
            tr("Review TIGER-imported roads with corroborating evidence"),
            Shortcut.registerShortcut(
                "subwindow:tigerreview",
                tr("Windows: {0}", tr("TIGER ROAR")),
                KeyEvent.VK_T, Shortcut.ALT_SHIFT
            ),
            150
        );

        // --- TIGER review tree ---
        tigerRoot = new DefaultMutableTreeNode("Results");
        tigerTree = createResultTree(tigerRoot);

        // --- Surface suggestions tree ---
        surfaceRoot = new DefaultMutableTreeNode("Results");
        surfaceTree = createResultTree(surfaceRoot);

        // --- Speed limit tree ---
        speedLimitRoot = new DefaultMutableTreeNode("Results");
        speedLimitTree = createResultTree(speedLimitRoot);

        // --- Alignment tree + tagging panel ---
        alignmentRoot = new DefaultMutableTreeNode("Results");
        alignmentTree = createResultTree(alignmentRoot);
        alignmentTree.setComponentPopupMenu(createAlignmentSortMenu());
        taggingPanel = new AlignmentTaggingPanel();
        taggingPanel.setApplyCallback(tags -> applyAlignmentTagsAndFix(tags));

        JSplitPane alignmentSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(alignmentTree), taggingPanel);
        alignmentSplit.setResizeWeight(1.0); // tree gets extra space
        alignmentSplit.setDividerSize(4);

        // --- Tabbed pane ---
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(tr("TIGER Review"), new JScrollPane(tigerTree));
        tabbedPane.addTab(tr("Surface"), new JScrollPane(surfaceTree));
        tabbedPane.addTab(tr("Speed Limit"), new JScrollPane(speedLimitTree));
        tabbedPane.addTab(tr("Alignment"), alignmentSplit);
        tabbedPane.addChangeListener(e -> updateButtonState());

        // --- Actions ---
        analyzeAction = new AbstractAction(tr("Analyze")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                analyze();
            }
        };
        new ImageProvider("dialogs", "refresh").getResource().attachImageIcon(analyzeAction, true);

        fixAction = new AbstractAction(tr("Fix")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixSelected();
            }
        };
        new ImageProvider("dialogs", "fix").getResource().attachImageIcon(fixAction, true);

        createLayout(tabbedPane, false, Arrays.asList(
            new SideButton(analyzeAction),
            new SideButton(fixAction)
        ));

        updateButtonState();
    }

    /**
     * Create a JTree with shared renderer, selection sync, and double-click zoom.
     */
    private JTree createResultTree(DefaultMutableTreeNode root) {
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ResultTreeRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);

        // Selection: sync selected tree items to map selection
        tree.addTreeSelectionListener(e -> {
            if (updatingSelection) return;
            updatingSelection = true;
            try {
                DataSet ds = getDataSet();
                if (ds == null) return;
                List<TreeDisplayable> selected = getSelectedResultsFromTree(tree);
                if (selected.isEmpty()) return;
                Set<Way> ways = selected.stream()
                        .map(TreeDisplayable::getWay)
                        .collect(Collectors.toSet());
                ds.setSelected(ways);
            } finally {
                updatingSelection = false;
            }
        });

        // Double-click: zoom to way
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (userObj instanceof TreeDisplayable result) {
                        AutoScaleAction.zoomTo(Collections.singleton(result.getWay()));
                    }
                }
            }
        });

        return tree;
    }

    private DataSet getDataSet() {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        return editLayer != null ? editLayer.getDataSet() : null;
    }

    // --- Tab helpers ---

    private JTree getActiveTree() {
        return switch (tabbedPane.getSelectedIndex()) {
            case 1 -> surfaceTree;
            case 2 -> speedLimitTree;
            case 3 -> alignmentTree;
            default -> tigerTree;
        };
    }

    private DefaultMutableTreeNode getActiveRoot() {
        return switch (tabbedPane.getSelectedIndex()) {
            case 1 -> surfaceRoot;
            case 2 -> speedLimitRoot;
            case 3 -> alignmentRoot;
            default -> tigerRoot;
        };
    }

    private List<? extends TreeDisplayable> getActiveResults() {
        return switch (tabbedPane.getSelectedIndex()) {
            case 1 -> surfaceResults;
            case 2 -> speedLimitResults;
            case 3 -> alignmentResults;
            default -> tigerResults;
        };
    }

    private boolean hasAnyResults() {
        return !tigerResults.isEmpty() || !surfaceResults.isEmpty()
                || !speedLimitResults.isEmpty() || !alignmentResults.isEmpty();
    }

    // --- Analysis ---

    /**
     * Run analysis in a background thread.
     */
    private void analyze() {
        DataSet ds = getDataSet();
        if (ds == null) {
            clearResults();
            return;
        }

        // Cancel any running analysis
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }

        setTitle(tr("TIGER ROAR: analyzing..."));
        analyzeAction.setEnabled(false);

        currentWorker = new SwingWorker<Void, Void>() {
            private List<ReviewResult> tigerRes;
            private List<SurfaceSuggestion> surfaceRes;
            private List<SpeedLimitSuggestion> speedLimitRes;
            private List<AlignmentResult> alignmentRes;
            private long analysisMs;

            @Override
            protected Void doInBackground() {
                // Load NAD data synchronously before analysis if needed
                if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false)
                        && !NadDataCache.getInstance().isReady()) {
                    NadDataLoader.getInstance().loadForDataSetSync(ds);
                }
                // Load Mapillary data synchronously before analysis if needed
                if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)
                        && !MapillaryDataCache.getInstance().isReady()) {
                    MapillaryDataLoader.getInstance().loadForDataSetSync(ds);
                }
                long startTime = System.nanoTime();
                TIGERReviewAnalyzer.AnalysisResult tigerAnalysis =
                        TIGERReviewAnalyzer.analyzeAllTimed(ds);
                tigerRes = tigerAnalysis.getResults();
                SurfaceAnalyzer.SurfaceAnalysisResult surfaceAnalysis =
                        SurfaceAnalyzer.analyzeAllTimed(ds);
                surfaceRes = surfaceAnalysis.getResults();
                SpeedLimitAnalyzer.SpeedLimitAnalysisResult speedLimitAnalysis =
                        SpeedLimitAnalyzer.analyzeAllTimed(ds);
                speedLimitRes = speedLimitAnalysis.getResults();
                AlignmentAnalyzer.AlignmentAnalysisResult alignmentAnalysis =
                        AlignmentAnalyzer.analyzeAllTimed(ds);
                alignmentRes = alignmentAnalysis.getResults();
                analysisMs = (System.nanoTime() - startTime) / 1_000_000;
                return null;
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        tigerResults = tigerRes;
                        surfaceResults = surfaceRes;
                        speedLimitResults = speedLimitRes;
                        alignmentResults = alignmentRes;
                        rebuildTrees();
                        applyPendingSelection();
                        setTitle(buildTitle(
                                tigerResults.size() + surfaceResults.size()
                                        + speedLimitResults.size() + alignmentResults.size(),
                                analysisMs));
                    }
                } catch (Exception ex) {
                    clearResults();
                    setTitle(tr("TIGER ROAR: error"));
                } finally {
                    analyzeAction.setEnabled(true);
                    updateButtonState();
                }
            }
        };
        currentWorker.execute();
    }

    private void clearResults() {
        tigerResults = new ArrayList<>();
        surfaceResults = new ArrayList<>();
        speedLimitResults = new ArrayList<>();
        alignmentResults = new ArrayList<>();
        rebuildTrees();
        setTitle(tr("TIGER ROAR"));
        updateButtonState();
    }

    // --- Tree building ---

    /**
     * Rebuild both trees and update tab titles with counts.
     */
    private void rebuildTrees() {
        rebuildSingleTree(tigerRoot, tigerTree, tigerResults, null);
        rebuildSingleTree(surfaceRoot, surfaceTree, surfaceResults, null);
        rebuildSingleTree(speedLimitRoot, speedLimitTree, speedLimitResults, null);
        rebuildSingleTree(alignmentRoot, alignmentTree, alignmentResults, getAlignmentComparator());
        updateTabTitles();
    }

    private void rebuildSingleTree(DefaultMutableTreeNode root, JTree tree,
            List<? extends TreeDisplayable> results,
            java.util.Comparator<TreeDisplayable> customSort) {
        // Save expanded state of category nodes by group message
        Set<String> collapsedGroups = new HashSet<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode categoryNode = (DefaultMutableTreeNode) root.getChildAt(i);
            String label = categoryNode.getUserObject().toString();
            // Strip the count suffix " (N)" to get the group key
            String groupKey = label.replaceAll(" \\(\\d+\\)$", "");
            TreePath path = new TreePath(new Object[]{root, categoryNode});
            if (tree.isCollapsed(path)) {
                collapsedGroups.add(groupKey);
            }
        }
        boolean hadChildren = root.getChildCount() > 0;

        root.removeAllChildren();

        // Group results by groupMessage
        Map<String, List<TreeDisplayable>> grouped = new LinkedHashMap<>();
        for (TreeDisplayable result : results) {
            grouped.computeIfAbsent(result.getGroupMessage(), k -> new ArrayList<>()).add(result);
        }

        // Sort groups by priority (most complete/actionable first)
        List<Map.Entry<String, List<TreeDisplayable>>> sortedGroups = new ArrayList<>(grouped.entrySet());
        sortedGroups.sort((a, b) -> {
            int pa = getGroupPriority(a.getValue().get(0));
            int pb = getGroupPriority(b.getValue().get(0));
            return Integer.compare(pa, pb);
        });

        for (Map.Entry<String, List<TreeDisplayable>> entry : sortedGroups) {
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                    entry.getKey() + " (" + entry.getValue().size() + ")");
            List<TreeDisplayable> sorted = new ArrayList<>(entry.getValue());
            if (customSort != null) {
                sorted.sort(customSort);
            } else {
                sorted.sort((a, b) -> {
                    String nameA = a.getWay().get("name");
                    String nameB = b.getWay().get("name");
                    if (nameA != null && nameB != null) {
                        return nameA.compareToIgnoreCase(nameB);
                    }
                    if (nameA != null) return -1;
                    if (nameB != null) return 1;
                    return Long.compare(a.getWay().getId(), b.getWay().getId());
                });
            }
            for (TreeDisplayable result : sorted) {
                categoryNode.add(new DefaultMutableTreeNode(result));
            }
            root.add(categoryNode);
        }

        ((DefaultTreeModel) tree.getModel()).reload();

        // Restore expansion state: expand all by default on first build,
        // otherwise restore previous collapsed/expanded state
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode categoryNode = (DefaultMutableTreeNode) root.getChildAt(i);
            String label = categoryNode.getUserObject().toString();
            String groupKey = label.replaceAll(" \\(\\d+\\)$", "");
            TreePath path = new TreePath(new Object[]{root, categoryNode});
            if (!hadChildren || !collapsedGroups.contains(groupKey)) {
                tree.expandPath(path);
            }
        }
    }

    private void updateTabTitles() {
        String arrow = alignmentSortReversed ? "\u25BC" : "\u25B2";
        String sortLabel = switch (alignmentSortMode) {
            case LENGTH -> tr("length {0}", arrow);
            case NODE_COUNT -> tr("nodes {0}", arrow);
            default -> tr("name {0}", arrow);
        };
        tabbedPane.setTitleAt(0, tr("TIGER Review ({0})", tigerResults.size()));
        tabbedPane.setTitleAt(1, tr("Surface ({0})", surfaceResults.size()));
        tabbedPane.setTitleAt(2, tr("Speed Limit ({0})", speedLimitResults.size()));
        tabbedPane.setTitleAt(3, tr("Alignment ({0}, {1})", alignmentResults.size(), sortLabel));
    }

    /**
     * Build a comparator for the alignment tree based on the current sort mode and direction.
     */
    private java.util.Comparator<TreeDisplayable> getAlignmentComparator() {
        java.util.Comparator<TreeDisplayable> cmp = switch (alignmentSortMode) {
            case LENGTH -> (a, b) -> Double.compare(a.getWay().getLength(), b.getWay().getLength());
            case NODE_COUNT -> (a, b) -> Integer.compare(a.getWay().getNodesCount(), b.getWay().getNodesCount());
            default -> (a, b) -> {
                String nameA = a.getWay().get("name");
                String nameB = b.getWay().get("name");
                if (nameA != null && nameB != null) {
                    return nameA.compareToIgnoreCase(nameB);
                }
                if (nameA != null) return -1;
                if (nameB != null) return 1;
                return Long.compare(a.getWay().getId(), b.getWay().getId());
            };
        };
        return alignmentSortReversed ? cmp.reversed() : cmp;
    }

    /**
     * Create the right-click context menu for the alignment tree.
     * Rebuilds items on each show so labels reflect current state.
     */
    private JPopupMenu createAlignmentSortMenu() {
        JPopupMenu menu = new JPopupMenu() {
            @Override
            public void show(java.awt.Component invoker, int x, int y) {
                removeAll();
                addSortItem(this, tr("name"), AlignmentSortMode.NAME);
                addSortItem(this, tr("length"), AlignmentSortMode.LENGTH);
                addSortItem(this, tr("node count"), AlignmentSortMode.NODE_COUNT);
                super.show(invoker, x, y);
            }
        };
        return menu;
    }

    private void addSortItem(JPopupMenu menu, String label, AlignmentSortMode mode) {
        if (alignmentSortMode == mode) {
            // Currently sorted by this — offer to reverse
            String direction = alignmentSortReversed ? tr("ascending") : tr("descending");
            JMenuItem item = new JMenuItem(tr("Sort by {0} ({1})", label, direction));
            item.addActionListener(e -> {
                alignmentSortReversed = !alignmentSortReversed;
                rebuildAlignmentTree();
            });
            menu.add(item);
        } else {
            JMenuItem item = new JMenuItem(tr("Sort by {0}", label));
            item.addActionListener(e -> {
                alignmentSortMode = mode;
                alignmentSortReversed = false;
                rebuildAlignmentTree();
            });
            menu.add(item);
        }
    }

    private void rebuildAlignmentTree() {
        rebuildSingleTree(alignmentRoot, alignmentTree, alignmentResults, getAlignmentComparator());
        updateTabTitles();
    }

    /**
     * Priority for sorting groups in the tree.
     * Lower number = higher in the list.
     *
     * TIGER Review tab ordering:
     *   0  Residual TIGER tags (trivial cleanup)
     *   1  Unnamed road verified (trivial, no name to worry about)
     *   2  Fully verified (name + alignment, just remove tag)
     *   3  Name upgrade (was name-only, alignment now confirmed)
     *   4  Name verified, alignment needs review (fix sets tiger:reviewed=name)
     *   5  Invalid tiger:reviewed value (needs manual attention)
     *   6  Combined directional upgrade (NAD + addresses agree, high confidence)
     *   7  Individual directional upgrades (prefix/suffix addition)
     *   8  Combined name suggestion (NAD + addresses agree, informational)
     *   9  Individual name suggestions (informational)
     *  10  Alignment verified, name not corroborated (fix sets tiger:reviewed=aerial)
     *
     * Surface tab ordering:
     *   0-5  Surface suggestions by confidence (high to low)
     *   6    Surface conflict (informational)
     *
     * Speed Limit tab ordering:
     *   0-3  Speed suggestions by confidence (high to low)
     */
    private static int getGroupPriority(TreeDisplayable result) {
        int code = result.getCode();

        // --- TIGER Review tab ---

        if (code == TIGERReviewTest.TIGER_RESIDUAL_TAGS) return 0;
        if (code == TIGERReviewTest.TIGER_UNNAMED_VERIFIED) return 1;

        // Fully verified and name-only share warning codes; distinguish by fix action
        if (code == TIGERReviewTest.TIGER_FULLY_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_NAD
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_USER_EDIT) {
            if (result instanceof ReviewResult rr
                    && rr.getFixAction() == TIGERReviewAnalyzer.FixAction.REMOVE_TAG) {
                return 2; // Fully verified
            }
            return 4; // Name verified, alignment needs review
        }
        if (code == TIGERReviewTest.TIGER_NAME_UPGRADE) return 3;
        if (code == TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE) return 5;
        if (code == TIGERReviewTest.TIGER_COMBINED_DIRECTIONAL_SUGGESTION) return 6;
        if (code == TIGERReviewTest.TIGER_NAD_DIRECTIONAL_SUGGESTION
                || code == TIGERReviewTest.TIGER_ADDRESS_DIRECTIONAL_SUGGESTION) return 7;
        if (code == TIGERReviewTest.TIGER_COMBINED_NAME_SUGGESTION) return 8;
        if (code == TIGERReviewTest.TIGER_NAD_NAME_SUGGESTION
                || code == TIGERReviewTest.TIGER_ADDRESS_NAME_SUGGESTION) return 9;
        if (code == TIGERReviewTest.TIGER_NAME_NOT_CORROBORATED) return 10;

        // --- Surface tab ---

        if (code == SurfaceTest.SURFACE_CONNECTED_ROAD) return 0;
        if (code == SurfaceTest.SURFACE_CONNECTED_ROAD_UPGRADE) return 1;
        if (code == SurfaceTest.SURFACE_CROSSING) return 2;
        if (code == SurfaceTest.SURFACE_CROSSING_UPGRADE) return 3;
        if (code == SurfaceTest.SURFACE_PARKING_AREA) return 4;
        if (code == SurfaceTest.SURFACE_PARKING_AREA_UPGRADE) return 5;
        if (code == SurfaceTest.SURFACE_LANES_PAVED) return 6;
        if (code == SurfaceTest.SURFACE_LANES_CONFLICT) return 7;
        if (code == SurfaceTest.SURFACE_CONFLICT) return 8;

        // --- Speed Limit tab ---

        if (code == SpeedLimitTest.SPEED_MISSING_MULTI_SIGN) return 0;
        if (code == SpeedLimitTest.SPEED_MISSING) return 1;
        if (code == SpeedLimitTest.SPEED_CONFLICT_MULTI_SIGN) return 2;
        if (code == SpeedLimitTest.SPEED_CONFLICT) return 3;

        // --- Alignment tab ---

        if (code == AlignmentAnalyzer.ALIGNMENT_NAME_REVIEWED) return 0;
        if (code == AlignmentAnalyzer.ALIGNMENT_UNNAMED_UNREVIEWED) return 1;

        return 99;
    }

    // --- Fix actions ---

    /**
     * Fix selected items in the active tab. If a category node is selected, fix all its children.
     * After fixing and re-analyzing, selects the next item after the fixed ones.
     */
    private void fixSelected() {
        JTree activeTree = getActiveTree();
        List<TreeDisplayable> toFix = getSelectedResultsFromTree(activeTree);
        if (toFix.isEmpty()) return;

        // If selection spans multiple categories, confirm with the user
        Set<String> groups = new HashSet<>();
        for (TreeDisplayable result : toFix) {
            groups.add(result.getGroupMessage());
        }
        if (groups.size() > 1) {
            int choice = javax.swing.JOptionPane.showConfirmDialog(
                    MainApplication.getMainFrame(),
                    tr("Your selection includes {0} different fix categories. Apply all?", groups.size()),
                    tr("Confirm Fix"),
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.QUESTION_MESSAGE);
            if (choice != javax.swing.JOptionPane.OK_OPTION) {
                return;
            }
        }

        // Compute the next leaf index to select after fix + re-analyze
        pendingLeafIndex = computeNextLeafIndex(activeTree, getActiveRoot(), toFix);
        pendingTabIndex = tabbedPane.getSelectedIndex();

        // Alignment tab: also apply any selected surface/lanes tags
        if (tabbedPane.getSelectedIndex() == 3) {
            applyAlignmentTagsAndFix(taggingPanel.getSelectedTags());
            return;
        }

        applyFixes(toFix);
    }

    /**
     * Find the flat leaf index of the first leaf node that comes after all
     * selected/fixed items. If no such leaf exists, returns 0 (first item).
     */
    private int computeNextLeafIndex(JTree tree, DefaultMutableTreeNode root,
                                      List<TreeDisplayable> fixedItems) {
        Set<Way> fixedWays = new HashSet<>();
        for (TreeDisplayable item : fixedItems) {
            fixedWays.add(item.getWay());
        }

        int leafIndex = 0;
        int maxFixedIndex = -1;

        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode category = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < category.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                if (leaf.getUserObject() instanceof TreeDisplayable result
                        && fixedWays.contains(result.getWay())) {
                    maxFixedIndex = leafIndex;
                }
                leafIndex++;
            }
        }

        // The next item after the last fixed one. Since the fixed items will be
        // removed, the item that was at (maxFixedIndex + 1) will shift down by
        // the number of fixed items at or before that position. But we don't know
        // exactly how many will be removed (cascading, etc.), so we count how many
        // non-fixed items precede the target position instead.
        int targetOriginalIndex = maxFixedIndex + 1;
        int nonFixedCount = 0;
        leafIndex = 0;

        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode category = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < category.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                boolean isFixed = leaf.getUserObject() instanceof TreeDisplayable result
                        && fixedWays.contains(result.getWay());
                if (!isFixed && leafIndex >= targetOriginalIndex) {
                    // This is the first non-fixed leaf after the last fixed one
                    return nonFixedCount;
                }
                if (!isFixed) {
                    nonFixedCount++;
                }
                leafIndex++;
            }
        }

        // All items after the last fixed one were also fixed; wrap to top
        return 0;
    }

    /**
     * Apply additional tags (surface, lanes, etc.) to selected alignment items,
     * then remove tiger tags and advance to the next item.
     * Called from the Fix button (via {@link #fixSelected()}) or from
     * {@link AlignmentTaggingPanel} quick-tag buttons.
     *
     * @param tags map of tag key to value (empty map = fix only, no tag additions)
     */
    private void applyAlignmentTagsAndFix(Map<String, String> tags) {
        List<TreeDisplayable> toFix = getSelectedResultsFromTree(alignmentTree);
        if (toFix.isEmpty()) return;

        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        List<Command> allCommands = new ArrayList<>();
        for (TreeDisplayable result : toFix) {
            Way way = result.getWay();

            // Apply the additional tags (surface, lanes, etc.)
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                allCommands.add(new ChangePropertyCommand(way, entry.getKey(), entry.getValue()));
            }

            // Remove tiger tags (the alignment fix)
            Command removeCmd = TIGERReviewAnalyzer.createRemoveTagCommand(way, stripTigerTags);
            if (removeCmd != null) {
                allCommands.add(removeCmd);
            }
        }

        if (allCommands.isEmpty()) return;

        // Set pending selection if not already set by fixSelected()
        if (pendingLeafIndex < 0) {
            pendingLeafIndex = computeNextLeafIndex(alignmentTree, alignmentRoot, toFix);
            pendingTabIndex = 3;
        }

        // Record in MRU
        taggingPanel.recordMru(tags);

        Command combined = SequenceCommand.wrapIfNeeded(
                tr("Alignment fix + tags ({0} roads)", toFix.size()), allCommands);
        UndoRedoHandler.getInstance().add(combined);

        analyze();
    }

    /**
     * Gather results from a tree's current selection.
     */
    private List<TreeDisplayable> getSelectedResultsFromTree(JTree tree) {
        List<TreeDisplayable> results = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return results;

        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof TreeDisplayable result) {
                results.add(result);
            } else if (!node.isLeaf()) {
                // Category node: collect all children
                for (int i = 0; i < node.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (child.getUserObject() instanceof TreeDisplayable result) {
                        results.add(result);
                    }
                }
            }
        }
        return results;
    }

    /**
     * Detect when the same way has complementary fixes from different categories and
     * upgrade them. For example, SET_NAME_REVIEWED + SET_ALIGNMENT_REVIEWED on the same
     * way means the road is fully verified, so replace both with a single REMOVE_TAG.
     */
    private List<TreeDisplayable> mergeComplementaryFixes(List<? extends TreeDisplayable> toFix) {
        // Group ReviewResults by way, tracking which fix actions are present
        Map<Way, List<ReviewResult>> byWay = new LinkedHashMap<>();
        List<TreeDisplayable> nonReviewResults = new ArrayList<>();
        for (TreeDisplayable result : toFix) {
            if (result instanceof ReviewResult rr) {
                byWay.computeIfAbsent(rr.getWay(), k -> new ArrayList<>()).add(rr);
            } else {
                nonReviewResults.add(result);
            }
        }

        List<TreeDisplayable> merged = new ArrayList<>(nonReviewResults);
        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        for (Map.Entry<Way, List<ReviewResult>> entry : byWay.entrySet()) {
            List<ReviewResult> results = entry.getValue();
            if (results.size() <= 1) {
                merged.addAll(results);
                continue;
            }

            // Check if complementary fixes combine to fully verify the way
            Set<TIGERReviewAnalyzer.FixAction> actions = new HashSet<>();
            for (ReviewResult rr : results) {
                if (rr.getFixAction() != null) {
                    actions.add(rr.getFixAction());
                }
            }

            boolean hasName = actions.contains(TIGERReviewAnalyzer.FixAction.SET_NAME_REVIEWED)
                    || actions.contains(TIGERReviewAnalyzer.FixAction.SUGGEST_NAME);
            boolean hasAlignment = actions.contains(TIGERReviewAnalyzer.FixAction.SET_ALIGNMENT_REVIEWED);

            if (hasName && hasAlignment) {
                // Name + alignment = fully verified. Replace with a single REMOVE_TAG,
                // keeping any SUGGEST_NAME so the name change is also applied.
                Way way = entry.getKey();
                for (ReviewResult rr : results) {
                    if (rr.getFixAction() == TIGERReviewAnalyzer.FixAction.SUGGEST_NAME) {
                        // Keep the name suggestion — its supplier already checks alignment
                        // state at execution time and will do REMOVE_TAG
                        merged.add(rr);
                    }
                }
                // Add a single REMOVE_TAG for the fully verified way
                merged.add(new ReviewResult(way, TIGERReviewTest.TIGER_FULLY_VERIFIED,
                        tr("name + alignment verified (combined from multiple fixes)"),
                        tr("Fully verified"),
                        TIGERReviewAnalyzer.FixAction.REMOVE_TAG, stripTigerTags));
            } else {
                // No complementary merge possible — keep all results as-is
                merged.addAll(results);
            }
        }

        return merged;
    }

    /**
     * Apply fix commands, cascade fully-verified fixes to neighbors, and re-analyze.
     *
     * When a road is fully verified (REMOVE_TAG), adjacent unreviewed roads sharing the
     * same name may become fully verified too (they gain name corroboration from the
     * newly-fixed neighbor). This method iteratively cascades fixes until no more
     * neighbors qualify, then wraps everything into a single undo operation.
     */
    private void applyFixes(List<? extends TreeDisplayable> toFix) {
        // Detect complementary fixes on the same way and upgrade them.
        // e.g. SET_NAME_REVIEWED + SET_ALIGNMENT_REVIEWED on the same way = REMOVE_TAG
        toFix = mergeComplementaryFixes(toFix);

        List<Command> allCommands = new ArrayList<>();
        for (TreeDisplayable result : toFix) {
            Supplier<Command> supplier = result.getFixSupplier();
            if (supplier != null) {
                Command cmd = supplier.get();
                if (cmd != null) {
                    allCommands.add(cmd);
                }
            }
        }
        if (allCommands.isEmpty()) return;

        // Execute initial fixes so tags are updated in memory
        Command initial = SequenceCommand.wrapIfNeeded(tr("TIGER Review fixes"), allCommands);
        UndoRedoHandler.getInstance().add(initial);
        int undoCount = 1;

        // Collect REMOVE_TAG ways as the cascade frontier
        Set<Way> alreadyFixed = new HashSet<>();
        Set<Way> frontier = new HashSet<>();
        for (TreeDisplayable result : toFix) {
            if (result instanceof ReviewResult rr
                    && rr.getFixAction() == TIGERReviewAnalyzer.FixAction.REMOVE_TAG
                    && rr.getCode() != TIGERReviewTest.TIGER_RESIDUAL_TAGS) {
                alreadyFixed.add(rr.getWay());
                frontier.add(rr.getWay());
            }
        }

        // Cascade: find adjacent roads that are now fully verified
        int maxCascade = 1000;
        List<Command> cascadeCommands = new ArrayList<>();
        while (!frontier.isEmpty() && alreadyFixed.size() < maxCascade) {
            Set<Way> nextFrontier = new HashSet<>();
            for (Way fixedWay : frontier) {
                String name = fixedWay.get("name");
                if (name == null || name.isEmpty()) continue;

                List<Node> nodes = fixedWay.getNodes();
                if (nodes.isEmpty()) continue;
                Node[] endpoints = {nodes.get(0), nodes.get(nodes.size() - 1)};

                for (Node endpoint : endpoints) {
                    for (OsmPrimitive referrer : endpoint.getReferrers()) {
                        if (!(referrer instanceof Way neighbor) || alreadyFixed.contains(neighbor)) continue;
                        if (!"no".equals(neighbor.get("tiger:reviewed"))) continue;
                        if (!name.equals(neighbor.get("name"))) continue;

                        // Re-analyze this neighbor (tags are updated from prior fixes)
                        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeWayWithPreferences(neighbor);
                        for (ReviewResult rr : results) {
                            if (rr.getFixAction() == TIGERReviewAnalyzer.FixAction.REMOVE_TAG) {
                                Command cmd = rr.getFixSupplier().get();
                                if (cmd != null) {
                                    // Execute immediately so next iteration sees updated tags
                                    UndoRedoHandler.getInstance().add(cmd);
                                    undoCount++;
                                    cascadeCommands.add(cmd);
                                    alreadyFixed.add(neighbor);
                                    nextFrontier.add(neighbor);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            frontier = nextFrontier;
        }

        // Recombine into a single undo operation
        if (!cascadeCommands.isEmpty()) {
            // Undo all individual commands
            for (int i = 0; i < undoCount; i++) {
                UndoRedoHandler.getInstance().undo();
            }
            allCommands.addAll(cascadeCommands);
            int totalRoads = allCommands.size();
            Command combined = SequenceCommand.wrapIfNeeded(
                    tr("TIGER Review fixes ({0} roads)", totalRoads), allCommands);
            UndoRedoHandler.getInstance().add(combined);
        }

        analyze();
    }

    private void updateButtonState() {
        boolean hasResults = !getActiveResults().isEmpty();
        fixAction.setEnabled(hasResults);
    }

    /**
     * Build the title bar text, including timing and NAD cache status.
     */
    private String buildTitle(int resultCount, long analysisMs) {
        String title = tr("TIGER ROAR: {0} results ({1}ms)", resultCount, analysisMs);
        if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false)) {
            NadDataCache cache = NadDataCache.getInstance();
            if (cache.isReady()) {
                title += " (NAD: " + cache.getAddressCount() + " addresses)";
            } else if (cache.getErrorMessage() != null) {
                title += " (NAD: error)";
            } else {
                title += " (NAD: not loaded)";
            }
        }
        if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)) {
            MapillaryDataCache cache = MapillaryDataCache.getInstance();
            if (cache.isReady()) {
                title += " (Mapillary: " + cache.getDetectionCount() + " signs, "
                        + cache.getMarkingCount() + " markings)";
            } else if (cache.getErrorMessage() != null) {
                title += " (Mapillary: error)";
            } else {
                title += " (Mapillary: not loaded)";
            }
        }
        return title;
    }

    // --- Listener lifecycle ---

    @Override
    public void showNotify() {
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this);
        UndoRedoHandler.getInstance().addCommandQueuePreciseListener(this);
    }

    @Override
    public void hideNotify() {
        MainApplication.getLayerManager().removeActiveLayerChangeListener(this);
        SelectionEventManager.getInstance().removeSelectionListener(this);
        UndoRedoHandler.getInstance().removeCommandQueuePreciseListener(this);
    }

    @Override
    public void commandAdded(UndoRedoHandler.CommandAddedEvent e) {
        // no-op: fix methods call analyze() after executing commands
    }

    @Override
    public void commandUndone(UndoRedoHandler.CommandUndoneEvent e) {
        if (hasAnyResults()) {
            analyze();
        }
    }

    @Override
    public void commandRedone(UndoRedoHandler.CommandRedoneEvent e) {
        if (hasAnyResults()) {
            analyze();
        }
    }

    @Override
    public void cleaned(UndoRedoHandler.CommandQueueCleanedEvent e) {
        if (hasAnyResults()) {
            analyze();
        }
    }

    @Override
    public void preferenceChanged(PreferenceChangeEvent e) {
        super.preferenceChanged(e);
        if (e.getKey().startsWith("tigerreview.") && hasAnyResults()) {
            analyze();
        }
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        clearResults();
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        if (updatingSelection) return;
        updatingSelection = true;
        try {
            Collection<? extends OsmPrimitive> selected = event.getSelection();
            Set<Way> selectedWays = selected.stream()
                    .filter(Way.class::isInstance)
                    .map(Way.class::cast)
                    .collect(Collectors.toSet());

            if (selectedWays.isEmpty()) {
                tigerTree.clearSelection();
                surfaceTree.clearSelection();
                speedLimitTree.clearSelection();
                alignmentTree.clearSelection();
                return;
            }

            // Sync all trees; only scroll the active one
            JTree active = getActiveTree();
            syncTreeSelection(tigerTree, tigerRoot, selectedWays, tigerTree == active);
            syncTreeSelection(surfaceTree, surfaceRoot, selectedWays, surfaceTree == active);
            syncTreeSelection(speedLimitTree, speedLimitRoot, selectedWays, speedLimitTree == active);
            syncTreeSelection(alignmentTree, alignmentRoot, selectedWays, alignmentTree == active);
        } finally {
            updatingSelection = false;
        }
    }

    /**
     * If a fix operation set a pending selection, apply it now and clear the pending state.
     * This selects the next item in the tree, syncs the JOSM map selection, and zooms to it.
     */
    private void applyPendingSelection() {
        if (pendingLeafIndex < 0) return;
        int tabIdx = pendingTabIndex;
        int leafIdx = pendingLeafIndex;
        pendingLeafIndex = -1;
        pendingTabIndex = -1;

        JTree tree = switch (tabIdx) {
            case 1 -> surfaceTree;
            case 2 -> speedLimitTree;
            case 3 -> alignmentTree;
            default -> tigerTree;
        };
        DefaultMutableTreeNode root = switch (tabIdx) {
            case 1 -> surfaceRoot;
            case 2 -> speedLimitRoot;
            case 3 -> alignmentRoot;
            default -> tigerRoot;
        };

        // Select the leaf — this triggers the tree's selection listener which
        // syncs the JOSM map selection (ds.setSelected). Do NOT guard with
        // updatingSelection so the listener fires normally.
        TreeDisplayable selected = selectLeafByIndex(tree, root, leafIdx);

        // Zoom the map to the newly selected way
        if (selected != null) {
            AutoScaleAction.zoomTo(Collections.singleton(selected.getWay()));
        }
    }

    /**
     * Select the leaf node at the given flat index in a tree, and scroll to it.
     * If the index is out of range, selects the first leaf (index 0).
     *
     * @return the selected TreeDisplayable, or null if the tree is empty
     */
    private TreeDisplayable selectLeafByIndex(JTree tree, DefaultMutableTreeNode root, int leafIndex) {
        int current = 0;
        DefaultMutableTreeNode firstLeaf = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode category = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < category.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                if (firstLeaf == null) {
                    firstLeaf = leaf;
                }
                if (current == leafIndex) {
                    TreePath path = new TreePath(leaf.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    return leaf.getUserObject() instanceof TreeDisplayable td ? td : null;
                }
                current++;
            }
        }
        // Index out of range — select first leaf
        if (firstLeaf != null) {
            TreePath path = new TreePath(firstLeaf.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            return firstLeaf.getUserObject() instanceof TreeDisplayable td ? td : null;
        }
        return null;
    }

    private void syncTreeSelection(JTree tree, DefaultMutableTreeNode root,
            Set<Way> selectedWays, boolean scrollVisible) {
        List<TreePath> matchingPaths = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode category = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < category.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                if (leaf.getUserObject() instanceof TreeDisplayable result
                        && selectedWays.contains(result.getWay())) {
                    matchingPaths.add(new TreePath(leaf.getPath()));
                }
            }
        }

        if (!matchingPaths.isEmpty()) {
            tree.setSelectionPaths(matchingPaths.toArray(new TreePath[0]));
            if (scrollVisible) {
                tree.scrollPathToVisible(matchingPaths.get(0));
            }
        } else {
            tree.clearSelection();
        }
    }

    // --- Tree cell renderer ---

    private static class ResultTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (!(value instanceof DefaultMutableTreeNode node)) return this;
            Object userObj = node.getUserObject();

            if (userObj instanceof TreeDisplayable result) {
                // Leaf node: show way info + evidence with tag-aware icon
                Way way = result.getWay();
                String name = way.get("name");
                String wayLabel;
                if (name != null && !name.isEmpty()) {
                    wayLabel = tr("Way {0}: {1}", String.valueOf(way.getId()), name);
                } else {
                    String highway = way.get("highway");
                    wayLabel = tr("Way {0} ({1})", String.valueOf(way.getId()), highway != null ? highway : "?");
                }
                setText(wayLabel + " \u2014 " + result.getMessage());
                setToolTipText(result.getGroupMessage());
                setIcon(ImageProvider.getPadded(way, new Dimension(16, 16)));
            } else if (userObj instanceof String) {
                // Category node: already formatted with count
                setIcon(null);
                setToolTipText(null);
            }
            return this;
        }
    }
}
