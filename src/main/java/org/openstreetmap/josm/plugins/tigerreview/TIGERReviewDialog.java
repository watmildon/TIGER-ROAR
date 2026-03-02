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
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataLoader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Side panel for TIGER review results.
 *
 * Provides an "Analyze" button that runs the same checks as the validator
 * test, displaying results in a tree view with Fix and Fix All buttons.
 */
public class TIGERReviewDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener, CommandQueuePreciseListener {

    private final DefaultMutableTreeNode root;
    private final JTree tree;
    private List<ReviewResult> currentResults = new ArrayList<>();

    private final AbstractAction analyzeAction;
    private final AbstractAction fixAction;
    private final AbstractAction fixAllAction;

    /** Guard to prevent selection feedback loop */
    private boolean updatingSelection;

    /** Track running analysis to avoid concurrent runs */
    private SwingWorker<List<ReviewResult>, Void> currentWorker;

    public TIGERReviewDialog() {
        super(
            tr("TIGER Review"),
            "tiger-review",
            tr("Review TIGER-imported roads with corroborating evidence"),
            Shortcut.registerShortcut(
                "subwindow:tigerreview",
                tr("Windows: {0}", tr("TIGER Review")),
                KeyEvent.VK_T, Shortcut.ALT_SHIFT
            ),
            150
        );

        // --- Results tree ---
        root = new DefaultMutableTreeNode("Results");
        tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ReviewResultTreeRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);

        // Selection: sync all selected tree items to map selection
        tree.addTreeSelectionListener(e -> {
            if (updatingSelection) return;
            updatingSelection = true;
            try {
                DataSet ds = getDataSet();
                if (ds == null) return;
                List<ReviewResult> selected = getSelectedResults();
                if (selected.isEmpty()) return;
                Set<Way> ways = selected.stream()
                        .map(ReviewResult::getWay)
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
                    if (userObj instanceof ReviewResult result) {
                        AutoScaleAction.zoomTo(Collections.singleton(result.getWay()));
                    }
                }
            }
        });

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

        createLayout(new JScrollPane(tree), false, Arrays.asList(
            new SideButton(analyzeAction),
            new SideButton(fixAction),
            new SideButton(fixAllAction)
        ));

        updateButtonState();
    }

    private DataSet getDataSet() {
        OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
        return editLayer != null ? editLayer.getDataSet() : null;
    }

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

        setTitle(tr("TIGER Review: analyzing..."));
        analyzeAction.setEnabled(false);

        currentWorker = new SwingWorker<List<ReviewResult>, Void>() {
            @Override
            protected List<ReviewResult> doInBackground() {
                // Load NAD data synchronously before analysis if needed
                if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false)
                        && !NadDataCache.getInstance().isReady()) {
                    NadDataLoader.getInstance().loadForDataSetSync(ds);
                }
                return TIGERReviewAnalyzer.analyzeAll(ds);
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        currentResults = get();
                        rebuildTree();
                        setTitle(buildTitle(currentResults.size()));
                    }
                } catch (Exception ex) {
                    clearResults();
                    setTitle(tr("TIGER Review: error"));
                } finally {
                    analyzeAction.setEnabled(true);
                    updateButtonState();
                }
            }
        };
        currentWorker.execute();
    }

    private void clearResults() {
        currentResults = new ArrayList<>();
        rebuildTree();
        setTitle(tr("TIGER Review"));
        updateButtonState();
    }

    /**
     * Rebuild the tree from currentResults, grouped by groupMessage
     * and sorted by completeness (fully verified first).
     */
    private void rebuildTree() {
        root.removeAllChildren();

        // Group results by groupMessage
        Map<String, List<ReviewResult>> grouped = new LinkedHashMap<>();
        for (ReviewResult result : currentResults) {
            grouped.computeIfAbsent(result.getGroupMessage(), k -> new ArrayList<>()).add(result);
        }

        // Sort groups by priority (most complete first)
        List<Map.Entry<String, List<ReviewResult>>> sortedGroups = new ArrayList<>(grouped.entrySet());
        sortedGroups.sort((a, b) -> {
            int pa = getGroupPriority(a.getValue().get(0));
            int pb = getGroupPriority(b.getValue().get(0));
            return Integer.compare(pa, pb);
        });

        for (Map.Entry<String, List<ReviewResult>> entry : sortedGroups) {
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                    entry.getKey() + " (" + entry.getValue().size() + ")");
            for (ReviewResult result : entry.getValue()) {
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

    /**
     * Priority for sorting groups in the tree.
     * Lower number = higher in the list (most actionable/complete first).
     */
    private static int getGroupPriority(ReviewResult result) {
        int code = result.getCode();
        // Fully verified (all name verification codes when paired with alignment)
        if (code == TIGERReviewTest.TIGER_FULLY_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_NAD) {
            // Fully verified and name-only share codes; distinguish by fix action
            if (result.getFixAction() == TIGERReviewAnalyzer.FixAction.REMOVE_TAG) {
                return 0; // Fully verified
            }
            return 3; // Name verified, alignment needs review
        }
        if (code == TIGERReviewTest.TIGER_NAME_UPGRADE) return 1;
        if (code == TIGERReviewTest.TIGER_UNNAMED_VERIFIED) return 2;
        if (code == TIGERReviewTest.TIGER_NAME_NOT_CORROBORATED) return 4;
        if (code == TIGERReviewTest.TIGER_RESIDUAL_TAGS) return 5;
        if (code == TIGERReviewTest.TIGER_SURFACE_SUGGESTED_BOTH_ENDS) return 6;
        if (code == TIGERReviewTest.TIGER_SURFACE_SUGGESTED_ONE_END) return 7;
        if (code == TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE) return 8;
        return 9;
    }

    /**
     * Fix selected items. If a category node is selected, fix all its children.
     */
    private void fixSelected() {
        List<ReviewResult> toFix = getSelectedResults();
        if (toFix.isEmpty()) return;
        applyFixes(toFix);
    }

    private void fixAll() {
        if (currentResults.isEmpty()) return;
        applyFixes(new ArrayList<>(currentResults));
    }

    /**
     * Gather ReviewResults from the current tree selection.
     */
    private List<ReviewResult> getSelectedResults() {
        List<ReviewResult> results = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return results;

        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof ReviewResult result) {
                results.add(result);
            } else if (!node.isLeaf()) {
                // Category node: collect all children
                for (int i = 0; i < node.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (child.getUserObject() instanceof ReviewResult result) {
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
    private void applyFixes(List<ReviewResult> toFix) {
        List<Command> commands = new ArrayList<>();
        for (ReviewResult result : toFix) {
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
        boolean hasResults = !currentResults.isEmpty();
        fixAction.setEnabled(hasResults);
        fixAllAction.setEnabled(hasResults);
    }

    /**
     * Build the title bar text, including NAD cache status if NAD check is enabled.
     */
    private String buildTitle(int resultCount) {
        String title = tr("TIGER Review: {0} results", resultCount);
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
        if (!currentResults.isEmpty()) {
            analyze();
        }
    }

    @Override
    public void commandRedone(UndoRedoHandler.CommandRedoneEvent e) {
        if (!currentResults.isEmpty()) {
            analyze();
        }
    }

    @Override
    public void cleaned(UndoRedoHandler.CommandQueueCleanedEvent e) {
        if (!currentResults.isEmpty()) {
            analyze();
        }
    }

    @Override
    public void preferenceChanged(PreferenceChangeEvent e) {
        super.preferenceChanged(e);
        if (e.getKey().startsWith("tigerreview.") && !currentResults.isEmpty()) {
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
                tree.clearSelection();
                return;
            }

            // Find matching tree nodes and select them
            List<TreePath> matchingPaths = new ArrayList<>();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode category = (DefaultMutableTreeNode) root.getChildAt(i);
                for (int j = 0; j < category.getChildCount(); j++) {
                    DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) category.getChildAt(j);
                    if (leaf.getUserObject() instanceof ReviewResult result
                            && selectedWays.contains(result.getWay())) {
                        matchingPaths.add(new TreePath(leaf.getPath()));
                    }
                }
            }

            if (!matchingPaths.isEmpty()) {
                tree.setSelectionPaths(matchingPaths.toArray(new TreePath[0]));
                tree.scrollPathToVisible(matchingPaths.get(0));
            } else {
                tree.clearSelection();
            }
        } finally {
            updatingSelection = false;
        }
    }

    // --- Tree cell renderer ---

    private static class ReviewResultTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (!(value instanceof DefaultMutableTreeNode node)) return this;
            Object userObj = node.getUserObject();

            if (userObj instanceof ReviewResult result) {
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
