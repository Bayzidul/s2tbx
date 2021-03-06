package org.esa.s2tbx.radiometry;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;

import java.awt.*;
import java.util.Map;

@OperatorMetadata(
        alias = "Ndi45Op",
        version="1.0",
        category = "Optical/Thematic Land Processing/Vegetation Radiometric Indices",
        description = "Normalized Difference Index using bands 4 and 5",
        authors = "Dragos Mihailescu",
        copyright = "Copyright (C) 2016 by CS ROMANIA")
public class Ndi45Op extends BaseIndexOp{

    // constants
    public static final String BAND_NAME = "ndi45";

    @Parameter(label = "Red (B4) factor", defaultValue = "1.0F", description = "The value of the red source band (B4) is multiplied by this value.")
    private float redB4Factor;

    @Parameter(label = "Red (B5) factor", defaultValue = "1.0F", description = "The value of the red source band (B5) is multiplied by this value.")
    private float redB5Factor;

    @Parameter(label = "Red source band 4",
            description = "The red band (B4) for the NDI45 computation. If not provided, the " +
                    "operator will try to find the best fitting band.",
            rasterDataNodeType = Band.class)
    private String redSourceBand4;

    @Parameter(label = "Red source band 5",
            description = "The red band (B5) for the NDI45 computation. If not provided, the " +
                    "operator will try to find the best fitting band.",
            rasterDataNodeType = Band.class)
    private String redSourceBand5;

    @Override
    public String getBandName() {
        return BAND_NAME;
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {
        pm.beginTask("Computing NDI45", rectangle.height);
        try {
            Tile redB4Tile = getSourceTile(getSourceProduct().getBand(redSourceBand4), rectangle);
            Tile redB5Tile = getSourceTile(getSourceProduct().getBand(redSourceBand5), rectangle);

            Tile ndi45 = targetTiles.get(targetProduct.getBand(BAND_NAME));
            Tile ndi45Flags = targetTiles.get(targetProduct.getBand(FLAGS_BAND_NAME));

            float ndi45Value;
            int ndi45FlagsValue;

            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                    final float redB5 = redB5Factor * redB5Tile.getSampleFloat(x, y);
                    final float redB4 = redB4Factor * redB4Tile.getSampleFloat(x, y);

                    ndi45Value = (redB5 - redB4) / (redB5 + redB4);

                    ndi45FlagsValue = 0;
                    if (Float.isNaN(ndi45Value) || Float.isInfinite(ndi45Value)) {
                        ndi45FlagsValue |= ARITHMETIC_FLAG_VALUE;
                        ndi45Value = 0.0f;
                    }
                    if (ndi45Value < 0.0f) {
                        ndi45FlagsValue |= LOW_FLAG_VALUE;
                    }
                    if (ndi45Value > 1.0f) {
                        ndi45FlagsValue |= HIGH_FLAG_VALUE;
                    }
                    ndi45.setSample(x, y, ndi45Value);
                    ndi45Flags.setSample(x, y, ndi45FlagsValue);
                }
                checkForCancellation();
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }

    @Override
    protected void loadSourceBands(Product product) throws OperatorException {
        if (redSourceBand4 == null) {
            redSourceBand4 = findBand(650, 680, product);
            getLogger().info("Using band '" + redSourceBand4 + "' as red input band (B4).");
        }
        if (redSourceBand5 == null) {
            redSourceBand5 = findBand(698, 713, product);
            getLogger().info("Using band '" + redSourceBand5 + "' as red input band (B5).");
        }
        if (redSourceBand4 == null) {
            throw new OperatorException("Unable to find band that could be used as red input band (B4). Please specify band.");
        }
        if (redSourceBand4 == null) {
            throw new OperatorException("Unable to find band that could be used as red input band (B5). Please specify band.");
        }
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(Ndi45Op.class);
        }

    }

}
