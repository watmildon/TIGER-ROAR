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
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataLoader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Side panel for TIGER review results.
 *
 * Uses a tabbed layout: Bulk Review (multi-road analysis with name corroboration)
 * and Single Review (per-road alignment worklist). Each tab uses an independent
 * analyzer and provides Fix / Fix All controls.
 */
public class TIGERReviewDialog extends ToggleDialog
        implements ActiveLayerChangeListener, DataSelectionListener, CommandQueuePreciseListener {

    private final JTabbedPane tabbedPane;

    // Bulk Review tab
    private final DefaultMutableTreeNode tigerRoot;
    private final JTree tigerTree;

    // Single Review (alignment) tab
    private final DefaultMutableTreeNode alignmentRoot;
    private final JTree alignmentTree;
    private final AlignmentTaggingPanel taggingPanel;

    /** All TIGER results from the last analyze. Partitioned at render time by {@link #routesToSingleReview(int)}. */
    private List<ReviewResult> tigerResults = new ArrayList<>();
    private List<AlignmentResult> alignmentResults = new ArrayList<>();
    private List<MissingTagAnalyzer.MissingTagResult> missingTagResults = new ArrayList<>();

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

    /**
     * Session-wide overwrite decisions keyed by tag key. Set by the user via the
     * "remember choice" checkbox in the conflict confirmation dialog. true =
     * silently overwrite future conflicts on this tag, false = silently skip.
     * Absent key = ask again. Cleared on dialog recreation (i.e., per JOSM session).
     */
    private final Map<String, Boolean> rememberedConflictChoices = new java.util.HashMap<>();

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

        // --- Bulk Review tree ---
        tigerRoot = new DefaultMutableTreeNode("Results");
        tigerTree = createResultTree(tigerRoot);

        // --- Single Review (alignment) tree + tagging panel ---
        alignmentRoot = new DefaultMutableTreeNode("Results");
        alignmentTree = createResultTree(alignmentRoot);
        alignmentTree.setComponentPopupMenu(createAlignmentSortMenu());
        taggingPanel = new AlignmentTaggingPanel();
        taggingPanel.setApplyCallback(tags -> applyAlignmentTagsAndFix(tags));
        // Tagging panel is meaningful only when the selection contains at least one
        // alignment-review row. Disable it when the user has only TIGER name-suggestion
        // rows highlighted, so the surface/lanes buttons don't accidentally tag a road
        // the user just meant to rename.
        alignmentTree.addTreeSelectionListener(e -> updateTaggingPanelState());

        JSplitPane alignmentSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(alignmentTree), taggingPanel);
        alignmentSplit.setResizeWeight(1.0); // tree gets extra space
        alignmentSplit.setDividerSize(4);

        // --- Tabbed pane ---
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(tr("Bulk Review"), new JScrollPane(tigerTree));
        tabbedPane.addTab(tr("Single Review"), alignmentSplit);
        tabbedPane.addChangeListener(e -> updateButtonState());

        // --- Actions ---
        analyzeAction = new AbstractAction(tr("Analyze")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                analyze();
            }
        };
        analyzeAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                tr("Scan the current data layer and populate both review tabs"));
        new ImageProvider("dialogs", "refresh").getResource().attachImageIcon(analyzeAction, true);

        fixAction = new AbstractAction(tr("Fix")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                fixSelected();
            }
        };
        fixAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                tr("Apply each selected row''s fix (rename, set tiger:reviewed, remove tiger tags, etc.). "
                   + "On Single Review, also adds any surface/lanes selected in the tagging panel."));
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
        return tabbedPane.getSelectedIndex() == 1 ? alignmentTree : tigerTree;
    }

    private DefaultMutableTreeNode getActiveRoot() {
        return tabbedPane.getSelectedIndex() == 1 ? alignmentRoot : tigerRoot;
    }

    private List<? extends TreeDisplayable> getActiveResults() {
        if (tabbedPane.getSelectedIndex() == 1) {
            return buildSingleReviewResults();
        }
        return bulkReviewSlice();
    }

    private boolean hasAnyResults() {
        return !tigerResults.isEmpty() || !alignmentResults.isEmpty() || !missingTagResults.isEmpty();
    }

    /** Union of all rows that should appear in the Single Review tab. */
    private List<TreeDisplayable> buildSingleReviewResults() {
        List<TreeDisplayable> out = new ArrayList<>();
        out.addAll(alignmentResults);
        out.addAll(singleReviewSlice());
        out.addAll(missingTagResults);
        return out;
    }

    /**
     * Codes that belong in the Single Review tab. Other TIGER codes stay in Bulk Review.
     *
     * <p>Single Review hosts items that need eyes on a specific road: per-road
     * name suggestions and "name verified, alignment needs review" items. The
     * combined NAD+address evidence codes ({@code TIGER_COMBINED_*}) stay in
     * Bulk Review because they're high-confidence enough to apply en masse.
     */
    private static boolean routesToSingleReview(ReviewResult rr) {
        int code = rr.getCode();
        // Per-source name and directional suggestions
        if (code == TIGERReviewTest.TIGER_NAD_NAME_SUGGESTION
                || code == TIGERReviewTest.TIGER_ADDRESS_NAME_SUGGESTION
                || code == TIGERReviewTest.TIGER_COMBINED_NAME_SUGGESTION
                || code == TIGERReviewTest.TIGER_NAD_DIRECTIONAL_SUGGESTION
                || code == TIGERReviewTest.TIGER_ADDRESS_DIRECTIONAL_SUGGESTION
                || code == TIGERReviewTest.TIGER_COMBINED_DIRECTIONAL_SUGGESTION) {
            return true;
        }
        // "Name verified, alignment needs review" — the SET_NAME_REVIEWED variant of
        // the various TIGER_NAME_VERIFIED_* codes. Fully-verified (REMOVE_TAG) stays in Bulk.
        if (rr.getFixAction() == TIGERReviewAnalyzer.FixAction.SET_NAME_REVIEWED) {
            return true;
        }
        return false;
    }

    private List<ReviewResult> bulkReviewSlice() {
        List<ReviewResult> out = new ArrayList<>(tigerResults.size());
        for (ReviewResult rr : tigerResults) {
            if (!routesToSingleReview(rr)) out.add(rr);
        }
        return out;
    }

    private List<ReviewResult> singleReviewSlice() {
        List<ReviewResult> out = new ArrayList<>();
        for (ReviewResult rr : tigerResults) {
            if (routesToSingleReview(rr)) out.add(rr);
        }
        return out;
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
            private List<AlignmentResult> alignmentRes;
            private List<MissingTagAnalyzer.MissingTagResult> missingRes;
            private long analysisMs;

            @Override
            protected Void doInBackground() {
                // Load NAD data synchronously before analysis if needed
                if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false)
                        && !NadDataCache.getInstance().isReady()) {
                    NadDataLoader.getInstance().loadForDataSetSync(ds);
                }
                long startTime = System.nanoTime();
                TIGERReviewAnalyzer.AnalysisResult tigerAnalysis =
                        TIGERReviewAnalyzer.analyzeAllTimed(ds);
                tigerRes = tigerAnalysis.getResults();
                AlignmentAnalyzer.AlignmentAnalysisResult alignmentAnalysis =
                        AlignmentAnalyzer.analyzeAllTimed(ds);
                alignmentRes = alignmentAnalysis.getResults();
                MissingTagAnalyzer.MissingTagAnalysisResult missingAnalysis =
                        MissingTagAnalyzer.analyzeAllTimed(ds);
                missingRes = missingAnalysis.getResults();
                analysisMs = (System.nanoTime() - startTime) / 1_000_000;
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // Surface any doInBackground() exception
                    if (!isCancelled()) {
                        tigerResults = tigerRes;
                        alignmentResults = alignmentRes;
                        missingTagResults = missingRes;
                        rebuildTrees();
                        applyPendingSelection();
                        setTitle(buildTitle(
                                tigerResults.size() + alignmentResults.size() + missingTagResults.size(),
                                analysisMs));
                    }
                } catch (java.util.concurrent.CancellationException ex) {
                    // Worker was cancelled — no error to report
                    clearResults();
                } catch (Exception ex) {
                    Logging.error("TIGER ROAR analysis failed: " + ex.getMessage());
                    Logging.error(ex);
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
        alignmentResults = new ArrayList<>();
        missingTagResults = new ArrayList<>();
        rebuildTrees();
        setTitle(tr("TIGER ROAR"));
        updateButtonState();
    }

    // --- Tree building ---

    /**
     * Rebuild both trees and update tab titles with counts.
     */
    private void rebuildTrees() {
        rebuildSingleTree(tigerRoot, tigerTree, bulkReviewSlice(), null);
        rebuildSingleTree(alignmentRoot, alignmentTree, buildSingleReviewResults(),
                getAlignmentComparator(), TIGERReviewDialog::singleReviewTopBucket);
        updateTabTitles();
    }

    /**
     * Top-level bucket name for a Single Review category, or null to render flat
     * at the tree root.
     *
     * <p>TIGER-related categories nest under one "TIGER Review" parent.
     * Missing-tag worklists ({@code Missing surface}, {@code Missing lanes}) and
     * any future non-TIGER worklists render at the root.
     */
    private static String singleReviewTopBucket(String categoryGroup) {
        if (categoryGroup == null) return null;
        if (tr("Missing surface").equals(categoryGroup)
                || tr("Missing lanes").equals(categoryGroup)) {
            return null;
        }
        return tr("TIGER Review");
    }

    private void rebuildSingleTree(DefaultMutableTreeNode root, JTree tree,
            List<? extends TreeDisplayable> results,
            java.util.Comparator<TreeDisplayable> customSort) {
        rebuildSingleTree(root, tree, results, customSort, null);
    }

    /**
     * Rebuild a tree from a flat list of {@link TreeDisplayable} results.
     *
     * <p>Always groups results by {@link TreeDisplayable#getGroupMessage()} into
     * category nodes. When {@code topGrouper} is non-null, category nodes whose
     * group name maps to a non-null bucket are nested under a parent node for
     * that bucket; categories that map to null render flat at the root.
     *
     * @param topGrouper maps a category's group message to a top-level bucket
     *                   name (null means render the category at the root)
     */
    private void rebuildSingleTree(DefaultMutableTreeNode root, JTree tree,
            List<? extends TreeDisplayable> results,
            java.util.Comparator<TreeDisplayable> customSort,
            java.util.function.Function<String, String> topGrouper) {
        // Save expanded state of every non-leaf node by its label sans count suffix
        Set<String> collapsedGroups = new HashSet<>();
        captureCollapsedState(root, tree, collapsedGroups);
        boolean hadChildren = root.getChildCount() > 0;

        root.removeAllChildren();

        // Group results by category groupMessage
        Map<String, List<TreeDisplayable>> grouped = new LinkedHashMap<>();
        for (TreeDisplayable result : results) {
            grouped.computeIfAbsent(result.getGroupMessage(), k -> new ArrayList<>()).add(result);
        }

        // Sort categories by priority (most complete/actionable first). Use the
        // minimum priority across the group's items so a stray item with a fallback
        // priority can't drag the whole group to the bottom.
        List<Map.Entry<String, List<TreeDisplayable>>> sortedGroups = new ArrayList<>(grouped.entrySet());
        sortedGroups.sort((a, b) -> {
            int pa = a.getValue().stream().mapToInt(TIGERReviewDialog::getGroupPriority).min().orElse(99);
            int pb = b.getValue().stream().mapToInt(TIGERReviewDialog::getGroupPriority).min().orElse(99);
            return Integer.compare(pa, pb);
        });

        // Bucket categories by top-level group while preserving sorted order
        Map<String, DefaultMutableTreeNode> topBuckets = new LinkedHashMap<>();

        for (Map.Entry<String, List<TreeDisplayable>> entry : sortedGroups) {
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                    entry.getKey() + " (" + entry.getValue().size() + ")");
            List<TreeDisplayable> sorted = new ArrayList<>(entry.getValue());
            if (customSort != null) {
                sorted.sort(customSort);
            } else {
                sorted.sort(defaultNameComparator());
            }
            for (TreeDisplayable result : sorted) {
                categoryNode.add(new DefaultMutableTreeNode(result));
            }

            String topBucket = topGrouper == null ? null : topGrouper.apply(entry.getKey());
            if (topBucket == null) {
                root.add(categoryNode);
            } else {
                DefaultMutableTreeNode parent = topBuckets.computeIfAbsent(topBucket,
                        k -> new DefaultMutableTreeNode(k));
                parent.add(categoryNode);
                // Append parent to root the first time we see it (preserves first-seen order)
                if (parent.getParent() == null) {
                    root.add(parent);
                }
            }
        }

        // Replace top-bucket labels with their final counts
        for (Map.Entry<String, DefaultMutableTreeNode> entry : topBuckets.entrySet()) {
            int leafTotal = 0;
            for (int i = 0; i < entry.getValue().getChildCount(); i++) {
                leafTotal += entry.getValue().getChildAt(i).getChildCount();
            }
            entry.getValue().setUserObject(entry.getKey() + " (" + leafTotal + ")");
        }

        ((DefaultTreeModel) tree.getModel()).reload();
        restoreExpansionState(root, tree, collapsedGroups, hadChildren);
    }

    private static java.util.Comparator<TreeDisplayable> defaultNameComparator() {
        return (a, b) -> {
            String nameA = a.getWay().get("name");
            String nameB = b.getWay().get("name");
            if (nameA != null && nameB != null) {
                return nameA.compareToIgnoreCase(nameB);
            }
            if (nameA != null) return -1;
            if (nameB != null) return 1;
            return Long.compare(a.getWay().getId(), b.getWay().getId());
        };
    }

    /** Strip a " (N)" count suffix from a node label to get a stable key. */
    private static String groupKeyOf(DefaultMutableTreeNode node) {
        return node.getUserObject().toString().replaceAll(" \\(\\d+\\)$", "");
    }

    private static void captureCollapsedState(DefaultMutableTreeNode node, JTree tree, Set<String> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.isLeaf()) continue;
            TreePath path = new TreePath(child.getPath());
            if (tree.isCollapsed(path)) out.add(groupKeyOf(child));
            captureCollapsedState(child, tree, out);
        }
    }

    private static void restoreExpansionState(DefaultMutableTreeNode node, JTree tree,
            Set<String> collapsedGroups, boolean hadChildren) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.isLeaf()) continue;
            TreePath path = new TreePath(child.getPath());
            if (!hadChildren || !collapsedGroups.contains(groupKeyOf(child))) {
                tree.expandPath(path);
            }
            restoreExpansionState(child, tree, collapsedGroups, hadChildren);
        }
    }

    /** Apply {@code action} to every leaf under {@code node} in tree order. */
    private static void forEachLeaf(DefaultMutableTreeNode node,
            java.util.function.Consumer<DefaultMutableTreeNode> action) {
        if (node.isLeaf()) {
            action.accept(node);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            forEachLeaf((DefaultMutableTreeNode) node.getChildAt(i), action);
        }
    }

    private void updateTabTitles() {
        String arrow = alignmentSortReversed ? "\u25BC" : "\u25B2";
        String sortLabel = switch (alignmentSortMode) {
            case LENGTH -> tr("length {0}", arrow);
            case NODE_COUNT -> tr("nodes {0}", arrow);
            default -> tr("name {0}", arrow);
        };
        int bulkCount = bulkReviewSlice().size();
        int singleCount = buildSingleReviewResults().size();
        tabbedPane.setTitleAt(0, tr("Bulk Review ({0})", bulkCount));
        tabbedPane.setTitleAt(1, tr("Single Review ({0}, {1})", singleCount, sortLabel));
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
        rebuildSingleTree(alignmentRoot, alignmentTree, buildSingleReviewResults(),
                getAlignmentComparator(), TIGERReviewDialog::singleReviewTopBucket);
        updateTabTitles();
    }

    /**
     * Priority for sorting groups in the tree. Lower number = higher in the list.
     * Both tabs draw from the same priority numbering; {@link #routesToSingleReview}
     * decides which tab each item lands on, and the priorities then order the
     * resulting subset.
     *
     * Bulk Review tab (priorities used, in order):
     *   0  Residual TIGER tags (trivial cleanup)
     *   1  Unnamed road verified (trivial, no name to worry about)
     *   2  Fully verified (name + alignment, just remove tag)
     *   3  Name upgrade (was name-only, alignment now confirmed)
     *   5  Invalid tiger:reviewed value (needs manual attention)
     *  10  Alignment verified, name not corroborated (fix sets tiger:reviewed=aerial)
     *
     * Single Review tab (priorities used, in order):
     *   0  Name verified, check alignment (AlignmentAnalyzer worklist) [under TIGER Review]
     *   1  Unnamed roads (AlignmentAnalyzer worklist)                  [under TIGER Review]
     *   4  Name verified, alignment needs review (SET_NAME_REVIEWED)   [under TIGER Review]
     *   6  Combined directional upgrade                                 [under TIGER Review]
     *   7  Individual directional upgrades                              [under TIGER Review]
     *   8  Combined name suggestion                                     [under TIGER Review]
     *   9  Individual name suggestions                                  [under TIGER Review]
     *  20  Missing surface (any vehicular road)                         [root level]
     *  21  Missing lanes (any vehicular road)                           [root level]
     */
    private static int getGroupPriority(TreeDisplayable result) {
        int code = result.getCode();

        // --- AlignmentAnalyzer codes (Single Review only) ---

        if (code == AlignmentAnalyzer.ALIGNMENT_NAME_REVIEWED) return 0;
        if (code == AlignmentAnalyzer.ALIGNMENT_UNNAMED_UNREVIEWED) return 1;

        // --- TIGERReviewAnalyzer codes (routed to either tab) ---

        if (code == TIGERReviewTest.TIGER_RESIDUAL_TAGS) return 0;
        if (code == TIGERReviewTest.TIGER_UNNAMED_VERIFIED) return 1;

        // Fully verified and name-only share warning codes; distinguish by fix action
        if (code == TIGERReviewTest.TIGER_FULLY_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_NAD
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_USER_EDIT
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_DUAL_CARRIAGEWAY) {
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

        // --- General Review (non-TIGER worklists, Single Review only) ---

        if (code == MissingTagAnalyzer.MISSING_SURFACE) return 20;
        if (code == MissingTagAnalyzer.MISSING_LANES) return 21;

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

        // Single Review tab hosts three kinds of items:
        //   - AlignmentResult:  blind alignment review, apply panel tags + remove tiger tags
        //   - ReviewResult:     per-road name fixes; if the panel has tags selected,
        //                       combine them with the row's fix; otherwise use the row's
        //                       own fix supplier
        //   - MissingTagResult: only the panel tags apply (no tiger:* manipulation).
        //                       Without panel tags there's nothing to do, so skip.
        if (tabbedPane.getSelectedIndex() == 1) {
            Map<String, String> panelTags = taggingPanel.getSelectedTags();
            List<TreeDisplayable> taggedItems = new ArrayList<>();
            List<TreeDisplayable> reviewItems = new ArrayList<>();
            for (TreeDisplayable item : toFix) {
                if (item instanceof MissingTagAnalyzer.MissingTagResult) {
                    if (!panelTags.isEmpty()) taggedItems.add(item);
                    continue;
                }
                if (item instanceof AlignmentResult || !panelTags.isEmpty()) {
                    // AlignmentResult always uses the tagging path (even with empty tags
                    // it removes tiger tags). ReviewResult uses it only when the user
                    // has panel tags selected, so the rename + tags + tiger-strip happen
                    // together.
                    taggedItems.add(item);
                } else {
                    reviewItems.add(item);
                }
            }
            if (!taggedItems.isEmpty()) {
                applyAlignmentTagsAndFix(panelTags, taggedItems);
            }
            if (!reviewItems.isEmpty()) {
                applyFixes(reviewItems);
            }
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

        int[] leafIndex = {0};
        int[] maxFixedIndex = {-1};
        forEachLeaf(root, leaf -> {
            if (leaf.getUserObject() instanceof TreeDisplayable result
                    && fixedWays.contains(result.getWay())) {
                maxFixedIndex[0] = leafIndex[0];
            }
            leafIndex[0]++;
        });

        // The next item after the last fixed one. Since the fixed items will be
        // removed, the item that was at (maxFixedIndex + 1) will shift down by
        // the number of fixed items at or before that position. But we don't know
        // exactly how many will be removed (cascading, etc.), so we count how many
        // non-fixed items precede the target position instead.
        int targetOriginalIndex = maxFixedIndex[0] + 1;
        int[] nonFixedCount = {0};
        int[] currentLeaf = {0};
        int[] result = {-1};

        forEachLeaf(root, leaf -> {
            if (result[0] >= 0) return; // Already found
            boolean isFixed = leaf.getUserObject() instanceof TreeDisplayable td
                    && fixedWays.contains(td.getWay());
            if (!isFixed && currentLeaf[0] >= targetOriginalIndex) {
                result[0] = nonFixedCount[0];
                return;
            }
            if (!isFixed) nonFixedCount[0]++;
            currentLeaf[0]++;
        });

        // All items after the last fixed one were also fixed; wrap to top
        return result[0] >= 0 ? result[0] : 0;
    }

    /**
     * Apply panel tags (surface, lanes, etc.) to the current Single Review selection.
     * Called from the {@link AlignmentTaggingPanel} quick-tag buttons.
     *
     * @param tags map of tag key to value (empty map = remove tiger tags only)
     */
    private void applyAlignmentTagsAndFix(Map<String, String> tags) {
        applyAlignmentTagsAndFix(tags, getSelectedResultsFromTree(alignmentTree));
    }

    /**
     * Apply panel tags + the row-appropriate tiger-tag mutation to each item.
     *
     * <p>Behavior per item type:
     * <ul>
     *   <li>{@link AlignmentResult}: apply panel tags, remove all tiger:* tags.</li>
     *   <li>{@link ReviewResult} with SUGGEST_NAME: apply panel tags, rename to the
     *       suggested name, then remove all tiger:* tags (the user is reviewing the
     *       road completely by adding alignment-relevant tags).</li>
     *   <li>{@link ReviewResult} other actions: apply panel tags, then remove all
     *       tiger:* tags (the panel-tag click is itself an alignment-review action).</li>
     *   <li>{@link MissingTagAnalyzer.MissingTagResult}: apply panel tags only. Do
     *       not touch tiger:* tags &mdash; these are non-TIGER worklist rows.</li>
     * </ul>
     *
     * <p>If applying a panel tag would overwrite an incompatible existing value on
     * a way, the user is prompted with a "remember choice" checkbox. The decision
     * is recorded per tag key for the remainder of the session.
     */
    private void applyAlignmentTagsAndFix(Map<String, String> tags, List<TreeDisplayable> toFix) {
        if (toFix.isEmpty()) return;

        // Per-tag-key conflict resolution. If any way in the selection has an
        // incompatible existing value for a tag we're about to set, ask the user
        // (unless they already remembered a session-wide decision).
        Map<String, Boolean> applyTag = resolveConflicts(tags, toFix);

        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        List<Command> allCommands = new ArrayList<>();
        for (TreeDisplayable result : toFix) {
            Way way = result.getWay();
            boolean isMissingTag = result instanceof MissingTagAnalyzer.MissingTagResult;

            // 1. Apply the additional tags (surface, lanes, etc.). Skip a tag for a
            //    way when it conflicts and the resolved decision is "no, don't overwrite".
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                String key = entry.getKey();
                String newVal = entry.getValue();
                String existing = way.get(key);
                if (existing != null && !isCompatibleValue(key, existing, newVal)
                        && !Boolean.TRUE.equals(applyTag.get(key))) {
                    continue; // skip conflicting tag for this way
                }
                allCommands.add(new ChangePropertyCommand(way, key, newVal));
            }

            // 2. For SUGGEST_NAME rows, apply the rename. (The supplier's own
            //    tiger-tag handling is superseded by our unconditional removal below.)
            if (result instanceof ReviewResult rr
                    && rr.getFixAction() == TIGERReviewAnalyzer.FixAction.SUGGEST_NAME
                    && rr.getSuggestedName() != null) {
                allCommands.add(new ChangePropertyCommand(way, "name", rr.getSuggestedName()));
            }

            // 3. Remove tiger tags for TIGER rows. MissingTag rows leave tiger:* alone.
            if (!isMissingTag) {
                Command removeCmd = TIGERReviewAnalyzer.createRemoveTagCommand(way, stripTigerTags);
                if (removeCmd != null) {
                    allCommands.add(removeCmd);
                }
            }
        }

        if (allCommands.isEmpty()) return;

        // Set pending selection if not already set by fixSelected()
        if (pendingLeafIndex < 0) {
            pendingLeafIndex = computeNextLeafIndex(alignmentTree, alignmentRoot, toFix);
            pendingTabIndex = 1;
        }

        // Record in MRU
        taggingPanel.recordMru(tags);

        Command combined = SequenceCommand.wrapIfNeeded(
                tr("Single Review fix + tags ({0} roads)", toFix.size()), allCommands);
        UndoRedoHandler.getInstance().add(combined);

        analyze();
    }

    /**
     * For each tag in {@code tags}, determine whether to overwrite an incompatible
     * existing value when a conflict is found in {@code toFix}. Returns a map from
     * tag key to "true = overwrite, false = skip" decisions. Tags with no conflict
     * map to {@code true} (no question to ask). Tags with conflicts consult the
     * session-remembered choice or prompt the user.
     */
    private Map<String, Boolean> resolveConflicts(Map<String, String> tags,
            List<TreeDisplayable> toFix) {
        Map<String, Boolean> decisions = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey();
            String newVal = entry.getValue();

            // Find a sample conflicting (way, existing-value) — used to phrase the prompt.
            Way sampleWay = null;
            String sampleExisting = null;
            int conflictCount = 0;
            for (TreeDisplayable item : toFix) {
                Way way = item.getWay();
                String existing = way.get(key);
                if (existing != null && !isCompatibleValue(key, existing, newVal)) {
                    if (sampleWay == null) {
                        sampleWay = way;
                        sampleExisting = existing;
                    }
                    conflictCount++;
                }
            }
            if (conflictCount == 0) {
                decisions.put(key, true); // no conflict, free to apply
                continue;
            }

            // Session-remembered decision wins.
            Boolean remembered = rememberedConflictChoices.get(key);
            if (remembered != null) {
                decisions.put(key, remembered);
                continue;
            }

            // Prompt the user.
            boolean overwrite = promptForConflict(key, newVal, sampleExisting, conflictCount);
            decisions.put(key, overwrite);
        }
        return decisions;
    }

    /**
     * Show a confirmation dialog asking whether to overwrite an existing tag value.
     * The dialog includes a "remember choice for this session" checkbox; when
     * checked, the decision is stored in {@link #rememberedConflictChoices} and
     * applied silently to all subsequent conflicts on the same tag key.
     *
     * @return true if the user chose to overwrite, false to skip
     */
    private boolean promptForConflict(String key, String newVal, String existing, int conflictCount) {
        String message;
        if (conflictCount == 1) {
            message = tr("Setting {0}={1} would overwrite the existing value {0}={2} on 1 road. Overwrite?",
                    key, newVal, existing);
        } else {
            message = tr("Setting {0}={1} would overwrite incompatible existing values on {2} roads "
                       + "(sample existing: {0}={3}). Overwrite all?",
                    key, newVal, conflictCount, existing);
        }

        javax.swing.JCheckBox remember = new javax.swing.JCheckBox(
                tr("Remember choice for {0}= conflicts this session", key));
        Object[] panel = new Object[]{message, remember};

        int choice = javax.swing.JOptionPane.showConfirmDialog(
                MainApplication.getMainFrame(), panel,
                tr("Tag conflict"),
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        boolean overwrite = choice == javax.swing.JOptionPane.YES_OPTION;
        if (remember.isSelected()) {
            rememberedConflictChoices.put(key, overwrite);
        }
        return overwrite;
    }

    /**
     * Decide whether a new tag value is compatible with an existing one. Compatible
     * means "no conflict prompt needed":
     * <ul>
     *   <li>Same value (case-insensitive).</li>
     *   <li>For surface=: the new value is a specific refinement of the existing
     *       generic (paved &rarr; asphalt, unpaved &rarr; gravel).</li>
     * </ul>
     * Anything else is a conflict.
     */
    private static boolean isCompatibleValue(String key, String existing, String newVal) {
        if (existing.equalsIgnoreCase(newVal)) return true;
        if ("surface".equals(key)) {
            // Refinement of a generic value is compatible (paved -> asphalt).
            return org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck
                    .isSameCategory(existing, newVal);
        }
        return false;
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
                // Non-leaf (category or top-level group): collect all descendant leaves
                forEachLeaf(node, leaf -> {
                    if (leaf.getUserObject() instanceof TreeDisplayable td) {
                        results.add(td);
                    }
                });
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
        updateTaggingPanelState();
    }

    /**
     * Tagging panel is enabled whenever the Single Review tab is showing. Every
     * row type now supports tag application (MissingTag rows just add the panel
     * tag without touching tiger:*).
     */
    private void updateTaggingPanelState() {
        taggingPanel.setTaggingEnabled(true);
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
                alignmentTree.clearSelection();
                return;
            }

            // Sync both trees; only scroll the active one
            JTree active = getActiveTree();
            syncTreeSelection(tigerTree, tigerRoot, selectedWays, tigerTree == active);
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

        JTree tree = tabIdx == 1 ? alignmentTree : tigerTree;
        DefaultMutableTreeNode root = tabIdx == 1 ? alignmentRoot : tigerRoot;

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
        DefaultMutableTreeNode[] firstLeaf = {null};
        DefaultMutableTreeNode[] targetLeaf = {null};
        int[] current = {0};
        forEachLeaf(root, leaf -> {
            if (firstLeaf[0] == null) firstLeaf[0] = leaf;
            if (current[0] == leafIndex) targetLeaf[0] = leaf;
            current[0]++;
        });

        DefaultMutableTreeNode chosen = targetLeaf[0] != null ? targetLeaf[0] : firstLeaf[0];
        if (chosen == null) return null;
        TreePath path = new TreePath(chosen.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        return chosen.getUserObject() instanceof TreeDisplayable td ? td : null;
    }

    private void syncTreeSelection(JTree tree, DefaultMutableTreeNode root,
            Set<Way> selectedWays, boolean scrollVisible) {
        List<TreePath> matchingPaths = new ArrayList<>();
        forEachLeaf(root, leaf -> {
            if (leaf.getUserObject() instanceof TreeDisplayable result
                    && selectedWays.contains(result.getWay())) {
                matchingPaths.add(new TreePath(leaf.getPath()));
            }
        });

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
