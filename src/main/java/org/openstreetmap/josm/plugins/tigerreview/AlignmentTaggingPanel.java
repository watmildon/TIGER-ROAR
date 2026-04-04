// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Tagging panel for the Alignment tab, inspired by TIGERKing.
 *
 * <p>Layout:
 * <ul>
 *   <li>Row 1: surface dropdown, lanes dropdown, custom button (reflects dropdowns)</li>
 *   <li>Row 2: fixed preset buttons (paved, unpaved, asphalt), then MRU buttons (last 3)</li>
 * </ul>
 */
class AlignmentTaggingPanel extends JPanel {

    /** Display label / actual tag value pairs for surface. First entry is "none" = no tag. */
    private static final String[][] SURFACE_OPTIONS = {
            {"none", null},
            {"asphalt", "asphalt"},
            {"concrete", "concrete"},
            {"paved", "paved"},
            {"unpaved", "unpaved"},
    };

    /** Display label / actual tag value pairs for lanes. First entry is "none" = no tag. */
    private static final String[][] LANES_OPTIONS = {
            {"none", null},
            {"2", "2"},
            {"3", "3"},
            {"4", "4"},
    };

    /** Fixed preset tag sets — always shown in this order before the MRU. */
    private static final Map<String, String> PRESET_PAVED = Map.of("surface", "paved");
    private static final Map<String, String> PRESET_UNPAVED = Map.of("surface", "unpaved");
    private static final Map<String, String> PRESET_ASPHALT = Map.of("surface", "asphalt");

    /** The fixed presets, used to filter them out of MRU. */
    private static final Set<Map<String, String>> FIXED_PRESETS = Set.of(
            PRESET_PAVED, PRESET_UNPAVED, PRESET_ASPHALT);

    private static final int MAX_MRU = 3;

    private final JComboBox<String> surfaceCombo;
    private final JComboBox<String> lanesCombo;
    private final JButton customButton;
    private final JPanel presetsPanel;

    /** MRU list — most recent first. Each entry is a tag map snapshot. */
    private final LinkedList<Map<String, String>> mruList = new LinkedList<>();

    /** Callback: receives a map of tag key -> value. */
    private Consumer<Map<String, String>> applyCallback;

    AlignmentTaggingPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // --- Row 1: dropdowns + custom button ---
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        topRow.setAlignmentX(LEFT_ALIGNMENT);

        topRow.add(makeLabel(tr("surface")));
        surfaceCombo = new JComboBox<>();
        for (String[] opt : SURFACE_OPTIONS) {
            surfaceCombo.addItem(opt[0]);
        }
        surfaceCombo.addActionListener(e -> updateCustomButton());
        topRow.add(surfaceCombo);

        topRow.add(makeLabel(tr("lanes")));
        lanesCombo = new JComboBox<>();
        for (String[] opt : LANES_OPTIONS) {
            lanesCombo.addItem(opt[0]);
        }
        lanesCombo.addActionListener(e -> updateCustomButton());
        topRow.add(lanesCombo);

        customButton = new JButton(formatTagLabel(new LinkedHashMap<>()));
        customButton.setMargin(new Insets(2, 8, 2, 8));
        customButton.setToolTipText(tr("Apply current dropdown selection and remove tiger tags"));
        customButton.addActionListener(e -> applyFromDropdowns());
        topRow.add(customButton);
        updateCustomButton();

        add(topRow);

        // --- Row 2: fixed presets + MRU ---
        presetsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        presetsPanel.setAlignmentX(LEFT_ALIGNMENT);

        // Fixed preset buttons
        presetsPanel.add(makePresetButton("paved", PRESET_PAVED));
        presetsPanel.add(makePresetButton("unpaved", PRESET_UNPAVED));
        presetsPanel.add(makePresetButton("asphalt", PRESET_ASPHALT));

        // MRU buttons will be appended by rebuildMruButtons()
        rebuildMruButtons();
        add(presetsPanel);
    }

    /**
     * Set the callback invoked when tags should be applied.
     */
    void setApplyCallback(Consumer<Map<String, String>> callback) {
        this.applyCallback = callback;
    }

    /**
     * Get the currently selected tags from the dropdowns.
     */
    Map<String, String> getSelectedTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        String surface = SURFACE_OPTIONS[surfaceCombo.getSelectedIndex()][1];
        if (surface != null) {
            tags.put("surface", surface);
        }
        String lanes = LANES_OPTIONS[lanesCombo.getSelectedIndex()][1];
        if (lanes != null) {
            tags.put("lanes", lanes);
        }
        return tags;
    }

    /**
     * Clear the dropdown selections back to "none".
     */
    void clearSelection() {
        surfaceCombo.setSelectedIndex(0);
        lanesCombo.setSelectedIndex(0);
    }

    /**
     * Record a tag set in the MRU list (called after a fix is applied).
     */
    void recordMru(Map<String, String> tags) {
        addToMru(tags);
        rebuildMruButtons();
    }

    private void applyFromDropdowns() {
        if (applyCallback == null) return;
        Map<String, String> tags = getSelectedTags();
        addToMru(tags);
        rebuildMruButtons();
        applyCallback.accept(tags);
    }

    private void applyPreset(Map<String, String> tags) {
        if (applyCallback == null) return;
        addToMru(tags);
        rebuildMruButtons();
        applyCallback.accept(tags);
    }

    private void addToMru(Map<String, String> tags) {
        // Don't add fixed presets to MRU
        if (FIXED_PRESETS.contains(tags)) return;
        // Remove duplicates
        mruList.removeIf(existing -> existing.equals(tags));
        // Add to front
        mruList.addFirst(new LinkedHashMap<>(tags));
        // Trim
        while (mruList.size() > MAX_MRU) {
            mruList.removeLast();
        }
    }

    private void updateCustomButton() {
        Map<String, String> tags = getSelectedTags();
        customButton.setText(formatTagLabel(tags));
        customButton.setToolTipText(formatTagTooltip(tags));
    }

    /**
     * Rebuild only the MRU portion of the presets panel (after the 3 fixed buttons).
     */
    private void rebuildMruButtons() {
        // Remove old MRU buttons (everything after the 3 fixed presets)
        while (presetsPanel.getComponentCount() > 3) {
            presetsPanel.remove(presetsPanel.getComponentCount() - 1);
        }

        // Add MRU buttons
        for (Map<String, String> tags : mruList) {
            JButton btn = new JButton(formatTagLabel(tags));
            btn.setMargin(new Insets(2, 8, 2, 8));
            btn.setToolTipText(formatTagTooltip(tags));
            final Map<String, String> tagsCopy = new LinkedHashMap<>(tags);
            btn.addActionListener(e -> applyPreset(tagsCopy));
            presetsPanel.add(btn);
        }

        presetsPanel.revalidate();
        presetsPanel.repaint();
    }

    private JButton makePresetButton(String label, Map<String, String> tags) {
        JButton btn = new JButton(label);
        btn.setMargin(new Insets(2, 8, 2, 8));
        btn.setToolTipText(formatTagTooltip(tags));
        btn.addActionListener(e -> applyPreset(tags));
        return btn;
    }

    /**
     * Format a tag map into a compact button label.
     */
    static String formatTagLabel(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return tr("no tags");
        }
        StringBuilder sb = new StringBuilder();
        String surface = tags.get("surface");
        String lanes = tags.get("lanes");
        if (surface != null) {
            sb.append(surface);
        }
        if (lanes != null) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(lanes).append(" lanes");
        }
        return sb.toString();
    }

    private static String formatTagTooltip(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return tr("Remove tiger tags only (no additional tags)");
        }
        StringBuilder sb = new StringBuilder(tr("Apply "));
        boolean first = true;
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append(tr(" and remove tiger tags"));
        return sb.toString();
    }

    private static JLabel makeLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        return lbl;
    }
}
