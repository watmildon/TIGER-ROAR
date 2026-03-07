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
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
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

    private List<ReviewResult> tigerResults = new ArrayList<>();
    private List<SurfaceSuggestion> surfaceResults = new ArrayList<>();
    private List<SpeedLimitSuggestion> speedLimitResults = new ArrayList<>();

    private final AbstractAction analyzeAction;
    private final AbstractAction fixAction;
    private final AbstractAction fixAllAction;

    /** Guard to prevent selection feedback loop */
    private boolean updatingSelection;

    /** Track running analysis to avoid concurrent runs */
    private SwingWorker<Void, Void> currentWorker;

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

        // --- Tabbed pane ---
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(tr("TIGER Review"), new JScrollPane(tigerTree));
        tabbedPane.addTab(tr("Surface"), new JScrollPane(surfaceTree));
        tabbedPane.addTab(tr("Speed Limit"), new JScrollPane(speedLimitTree));
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

        fixAllAction = new AbstractAction(tr("Fix All")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixAll();
            }
        };
        new ImageProvider("dialogs", "fix").getResource().attachImageIcon(fixAllAction, true);

        createLayout(tabbedPane, false, Arrays.asList(
            new SideButton(analyzeAction),
            new SideButton(fixAction),
            new SideButton(fixAllAction)
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
            default -> tigerTree;
        };
    }

    private DefaultMutableTreeNode getActiveRoot() {
        return switch (tabbedPane.getSelectedIndex()) {
            case 1 -> surfaceRoot;
            case 2 -> speedLimitRoot;
            default -> tigerRoot;
        };
    }

    private List<? extends TreeDisplayable> getActiveResults() {
        return switch (tabbedPane.getSelectedIndex()) {
            case 1 -> surfaceResults;
            case 2 -> speedLimitResults;
            default -> tigerResults;
        };
    }

    private boolean hasAnyResults() {
        return !tigerResults.isEmpty() || !surfaceResults.isEmpty() || !speedLimitResults.isEmpty();
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
                tigerRes = TIGERReviewAnalyzer.analyzeAll(ds);
                surfaceRes = SurfaceAnalyzer.analyzeAll(ds);
                speedLimitRes = SpeedLimitAnalyzer.analyzeAll(ds);
                return null;
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        tigerResults = tigerRes;
                        surfaceResults = surfaceRes;
                        speedLimitResults = speedLimitRes;
                        rebuildTrees();
                        setTitle(buildTitle(
                                tigerResults.size() + surfaceResults.size() + speedLimitResults.size()));
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
        rebuildTrees();
        setTitle(tr("TIGER ROAR"));
        updateButtonState();
    }

    // --- Tree building ---

    /**
     * Rebuild both trees and update tab titles with counts.
     */
    private void rebuildTrees() {
        rebuildSingleTree(tigerRoot, tigerTree, tigerResults);
        rebuildSingleTree(surfaceRoot, surfaceTree, surfaceResults);
        rebuildSingleTree(speedLimitRoot, speedLimitTree, speedLimitResults);
        updateTabTitles();
    }

    private void rebuildSingleTree(DefaultMutableTreeNode root, JTree tree,
            List<? extends TreeDisplayable> results) {
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
            for (TreeDisplayable result : entry.getValue()) {
                categoryNode.add(new DefaultMutableTreeNode(result));
            }
            root.add(categoryNode);
        }

        ((DefaultTreeModel) tree.getModel()).reload();

        // Expand all category nodes
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void updateTabTitles() {
        tabbedPane.setTitleAt(0, tr("TIGER Review ({0})", tigerResults.size()));
        tabbedPane.setTitleAt(1, tr("Surface ({0})", surfaceResults.size()));
        tabbedPane.setTitleAt(2, tr("Speed Limit ({0})", speedLimitResults.size()));
    }

    /**
     * Priority for sorting groups in the tree.
     * Lower number = higher in the list (most actionable/complete first).
     */
    private static int getGroupPriority(TreeDisplayable result) {
        int code = result.getCode();
        // Fully verified (all name verification codes when paired with alignment)
        if (code == TIGERReviewTest.TIGER_FULLY_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_NAD) {
            // Fully verified and name-only share codes; distinguish by fix action
            if (result instanceof ReviewResult rr
                    && rr.getFixAction() == TIGERReviewAnalyzer.FixAction.REMOVE_TAG) {
                return 0; // Fully verified
            }
            return 3; // Name verified, alignment needs review
        }
        if (code == TIGERReviewTest.TIGER_NAME_UPGRADE) return 1;
        if (code == TIGERReviewTest.TIGER_UNNAMED_VERIFIED) return 2;
        if (code == TIGERReviewTest.TIGER_NAME_NOT_CORROBORATED) return 4;
        if (code == TIGERReviewTest.TIGER_RESIDUAL_TAGS) return 5;
        if (code == SurfaceTest.SURFACE_SUGGESTED_BOTH_ENDS) return 6;
        if (code == SurfaceTest.SURFACE_SUGGESTED_BOTH_ENDS_MIXED) return 7;
        if (code == SurfaceTest.SURFACE_SUGGESTED_ONE_END) return 8;
        if (code == SurfaceTest.SURFACE_UPGRADE_BOTH_ENDS) return 9;
        if (code == SurfaceTest.SURFACE_UPGRADE_BOTH_ENDS_MIXED) return 10;
        if (code == SurfaceTest.SURFACE_UPGRADE_ONE_END) return 11;
        if (code == SurfaceTest.SURFACE_CONFLICT) return 12;
        if (code == TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE) return 13;
        if (code == SpeedLimitTest.SPEED_MISSING_MULTI_SIGN) return 14;
        if (code == SpeedLimitTest.SPEED_MISSING) return 15;
        if (code == SpeedLimitTest.SPEED_CONFLICT_MULTI_SIGN) return 16;
        if (code == SpeedLimitTest.SPEED_CONFLICT) return 17;
        return 18;
    }

    // --- Fix actions ---

    /**
     * Fix selected items in the active tab. If a category node is selected, fix all its children.
     */
    private void fixSelected() {
        List<TreeDisplayable> toFix = getSelectedResultsFromTree(getActiveTree());
        if (toFix.isEmpty()) return;
        applyFixes(toFix);
    }

    private void fixAll() {
        List<? extends TreeDisplayable> results = getActiveResults();
        if (results.isEmpty()) return;
        applyFixes(new ArrayList<>(results));
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
     * Apply fix commands and re-analyze.
     */
    private void applyFixes(List<? extends TreeDisplayable> toFix) {
        List<Command> commands = new ArrayList<>();
        for (TreeDisplayable result : toFix) {
            Supplier<Command> supplier = result.getFixSupplier();
            if (supplier != null) {
                Command cmd = supplier.get();
                if (cmd != null) {
                    commands.add(cmd);
                }
            }
        }
        if (commands.isEmpty()) return;

        Command combined = SequenceCommand.wrapIfNeeded(tr("TIGER Review fixes"), commands);
        UndoRedoHandler.getInstance().add(combined);
        analyze();
    }

    private void updateButtonState() {
        boolean hasResults = !getActiveResults().isEmpty();
        fixAction.setEnabled(hasResults);
        fixAllAction.setEnabled(hasResults);
    }

    /**
     * Build the title bar text, including NAD cache status if NAD check is enabled.
     */
    private String buildTitle(int resultCount) {
        String title = tr("TIGER ROAR: {0} results", resultCount);
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
                title += " (Mapillary: " + cache.getDetectionCount() + " signs)";
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
                return;
            }

            // Sync all trees; only scroll the active one
            JTree active = getActiveTree();
            syncTreeSelection(tigerTree, tigerRoot, selectedWays, tigerTree == active);
            syncTreeSelection(surfaceTree, surfaceRoot, selectedWays, surfaceTree == active);
            syncTreeSelection(speedLimitTree, speedLimitRoot, selectedWays, speedLimitTree == active);
        } finally {
            updatingSelection = false;
        }
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
