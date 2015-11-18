package org.esa.s2tbx.dataio.s2.masks;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * S2-MSI Masks model
 *
 * @author J. Malik
 */
public enum MaskInfo {

    MSK_DETFOO(
            "MSK_DETFOO",
            "DETECTOR_FOOTPRINT",
            "Detector footprint mask",
            null,
            "detector_footprint",
            true,
            MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_NODATA_NODATA(
            "MSK_NODATA",
            "QT_NODATA_PIXELS",
            "Radiometric quality mask",
            "No–data pixels",
            "nodata",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_NODATA_CROSSTALK(
            "MSK_NODATA",
            "QT_PARTIALLY_CORRECTED_PIXELS",
            "Radiometric quality mask",
            "Pixels partially corrected during cross-talk processing",
            "partially_corrected_crosstalk",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_SATURA_L1A(
            "MSK_SATURA",
            "QT_SATURATED_PIXELS_L1A",
            "Radiometric quality mask",
            "Saturated pixels before on-ground radiometric processing",
            "saturated_l1a",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_SATURA_L1B(
            "MSK_SATURA",
            "QT_SATURATED_PIXELS_L1B",
            "Radiometric quality mask",
            "Saturated pixels after on-ground radiometric processing",
            "saturated_l1b",
            true,
            MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_DEFECT(
            "MSK_DEFECT",
            "QT_DEFECTIVE_PIXELS",
            "Radiometric quality mask",
            "Defective pixels (matching defective columns)",
            "defective",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_TECQUA_ANC_LOST(
            "MSK_TECQUA",
            "ANC_LOST",
            "Technical quality mask",
            "Ancillary lost data",
            "ancillary_lost",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            Color.ORANGE,
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_TECQUA_ANC_DEG(
            "MSK_TECQUA",
            "ANC_DEG",
            "Technical quality mask",
            "Ancillary degraded data",
            "ancillary_degraded",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_TECQUA_MSI_LOST(
            "MSK_TECQUA",
            "MSI_LOST",
            "Technical quality mask",
            "MSI lost data",
            "msi_lost",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_TECQUA_MSI_DEG(
            "MSK_TECQUA",
            "MSI_DEG",
            "Technical quality mask",
            "MSI degraded data",
            "msi_degraded",
            true,
            MaskInfo.L1A | MaskInfo.L1B | MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_CLOLOW(
            "MSK_CLOLOW",
            "CLOUD_INV",
            "Coarse cloud mask",
            null,
            "coarse_cloud",
            true,
            MaskInfo.L1A | MaskInfo.L1B,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_CLOUDS_OPAQUE(
            "MSK_CLOUDS",
            "OPAQUE",
            "Finer cloud mask",
            "Opaque clouds",
            "opaque_clouds",
            false,
            MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY),
    MSK_CLOUDS_CIRRUS(
            "MSK_CLOUDS",
            "CIRRUS",
            "Finer cloud mask",
            "Cirrus clouds",
            "cirrus_clouds",
            false,
            MaskInfo.L1C | MaskInfo.L2A,
            ColorIterator.next(),
            MaskInfo.DEFAULT_TRANSPARENCY);

    private final String mainType;
    private final String subType;
    private final String mainDescription;
    private final String subDescription;
    private final String snapName;
    private final boolean perBand;
    private final int levels;
    private final Color color;
    private final double transparency;

    public static final int L1A = (1 << 0);
    public static final int L1B = (1 << 1);
    public static final int L1C = (1 << 2);
    public static final int L2A = (1 << 3);

    private static final double DEFAULT_TRANSPARENCY = 0.8;

    MaskInfo(String mainType, String subType, String mainDescription, String subDescription, String snapName, boolean perBand, int levels, Color color, double transparency) {
        this.mainType = mainType;
        this.subType = subType;
        this.mainDescription = mainDescription;
        this.subDescription = subDescription;
        this.snapName = snapName;
        this.perBand = perBand;
        this.levels = levels;
        this.color = color;
        this.transparency = transparency;
    }

    public String getMainType() {
        return mainType;
    }

    public String getSubType() {
        return subType;
    }

    public String getSnapName() {
        return snapName;
    }

    public String getSnapNameForBand(String bandName) {
        return String.format("%s_%s", snapName, bandName);
    }

    public String getDescription() {
        String description;
        if (subDescription == null) {
            description = mainDescription;
        }
        else {
            description = String.format("%s - %s", mainDescription, subDescription);
        }
        return description;
    }

    public String getDescriptionForBand(String bandName) {
        return String.format("%s - %s", getDescription(), bandName);
    }

    public Color getColor() {
        return color;
    }

    public double getTransparency() {
        return transparency;
    }

    public boolean isPresentAtLevel(int level) {
        return (levels & level) != 0;
    }

    public boolean isPerBand() {
        return this.perBand;
    }

    private static class ColorIterator {

        static ArrayList<Color> colors;
        static Iterator<Color> colorIterator;

        static {
            colors = new ArrayList<>();
            colors.add(Color.red);
            colors.add(Color.red.darker());
            colors.add(Color.red.darker().darker());
            colors.add(Color.blue);
            colors.add(Color.blue.darker());
            colors.add(Color.blue.darker().darker());
            colors.add(Color.green);
            colors.add(Color.green.darker());
            colors.add(Color.green.darker().darker());
            colors.add(Color.yellow);
            colors.add(Color.yellow.darker());
            colors.add(Color.yellow.darker().darker());
            colors.add(Color.magenta);
            colors.add(Color.magenta.darker());
            colors.add(Color.magenta.darker().darker());
            colors.add(Color.pink);
            colors.add(Color.pink.darker());
            colors.add(Color.pink.darker().darker());
            colorIterator = colors.iterator();
        }

        static Color next() {
            if (!colorIterator.hasNext()) {
                colorIterator = colors.iterator();
            }
            return colorIterator.next();
        }
    }

}
