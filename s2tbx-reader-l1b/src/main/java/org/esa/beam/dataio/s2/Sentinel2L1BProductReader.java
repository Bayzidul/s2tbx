package org.esa.beam.dataio.s2;

import com.bc.ceres.core.Assert;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;
import com.bc.ceres.glevel.support.DefaultMultiLevelSource;
import com.vividsolutions.jts.geom.Coordinate;
import jp2.TileLayout;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.apache.commons.math3.util.Pair;
import org.esa.beam.dataio.Utils;
import org.esa.beam.dataio.s2.filepatterns.S2L1bGranuleDirFilename;
import org.esa.beam.dataio.s2.filepatterns.S2L1bGranuleImageFilename;
import org.esa.beam.dataio.s2.filepatterns.S2L1bProductFilename;
import org.esa.beam.framework.dataio.AbstractProductReader;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.CrsGeoCoding;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.TiePointGeoCoding;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.jai.ImageManager;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.SystemUtils;
import org.esa.beam.util.io.FileUtils;
import org.esa.beam.util.logging.BeamLogManager;
import org.geotools.geometry.Envelope2D;
import org.jdom.JDOMException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.BorderDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.esa.beam.dataio.s2.CoordinateUtils.convertDoublesToFloats;
import static org.esa.beam.dataio.s2.CoordinateUtils.getLatitudes;
import static org.esa.beam.dataio.s2.CoordinateUtils.getLongitudes;
import static org.esa.beam.dataio.s2.L1bMetadata.ProductCharacteristics;
import static org.esa.beam.dataio.s2.L1bMetadata.SpectralInformation;
import static org.esa.beam.dataio.s2.L1bMetadata.Tile;
import static org.esa.beam.dataio.s2.L1bMetadata.parseHeader;
import static org.esa.beam.dataio.s2.S2L1bConfig.DEFAULT_JAI_TILE_SIZE;
import static org.esa.beam.dataio.s2.S2L1bConfig.FILL_CODE_MOSAIC_BG;
import static org.esa.beam.dataio.s2.S2L1bConfig.L1B_TILE_LAYOUTS;
import static org.esa.beam.dataio.s2.S2L1bConfig.SAMPLE_PRODUCT_DATA_TYPE;

// import org.github.jamm.MemoryMeter;

// todo - register reasonable RGB profile(s)
// todo - set a band's validMaskExpr or no-data value (read from GML)
// todo - set band's ImageInfo from min,max,histogram found in header (--> L1cMetadata.quicklookDescriptor)
// todo - viewing incidence tie-point grids contain NaN values - find out how to correctly treat them
// todo - configure BEAM module / SUHET installer so that OpenJPEG "opj_decompress" executable is accessible on all platforms

// todo - better collect problems during product opening and generate problem report (requires reader API change), see {@report "Problem detected..."} code marks

/**
 * <p>
 * This product reader can currently read single L1C tiles (also called L1C granules) and entire L1C scenes composed of
 * multiple L1C tiles.
 * </p>
 * <p>
 * To read single tiles, select any tile image file (IMG_*.jp2) within a product package. The reader will then
 * collect other band images for the selected tile and wiull also try to read the metadata file (MTD_*.xml).
 * </p>
 * <p>To read an entire scene, select the metadata file (MTD_*.xml) within a product package. The reader will then
 * collect other tile/band images and create a mosaic on the fly.
 * </p>
 *
 * @author Norman Fomferra
 */
public class Sentinel2L1BProductReader extends AbstractProductReader {

    private File cacheDir;
    protected final Logger logger;
    // private MemoryMeter meter;

    public static class TileBandInfo {
        final Map<String, File> tileIdToFileMap;
        final int bandIndex;
        final S2L1bWavebandInfo wavebandInfo;
        final TileLayout imageLayout;
        final String detectorId;

        TileBandInfo(Map<String, File> tileIdToFileMap, int bandIndex, String detector, S2L1bWavebandInfo wavebandInfo, TileLayout imageLayout) {
            this.tileIdToFileMap = Collections.unmodifiableMap(tileIdToFileMap);
            this.bandIndex = bandIndex;
            this.detectorId = detector == null ? "" : detector;
            this.wavebandInfo = wavebandInfo;
            this.imageLayout = imageLayout;
        }

        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }


    Sentinel2L1BProductReader(Sentinel2L1BProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
        logger = BeamLogManager.getSystemLogger();
        // meter = new MemoryMeter();
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) throws IOException {
        // Should never not come here, since we have an OpImage that reads data
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        logger.fine("readProductNodeImpl, " + getInput().toString());

        Product p = null;

        final File inputFile = new File(getInput().toString());
        if (!inputFile.exists()) {
            throw new FileNotFoundException(inputFile.getPath());
        }

        // critical do we have to read a standalone granule or jp2 file ?

        if (S2L1bProductFilename.isProductFilename(inputFile.getName())) {
            boolean isAGranule = S2L1bProductFilename.isGranuleFilename(inputFile.getName());
            if(isAGranule)
            {
                logger.fine("Reading a granule");
            }

            p = getL1bMosaicProduct(inputFile, isAGranule);

            if (p != null) {
                readMasks(p);
                p.setModified(false);
            }
        } else {
            throw new IOException("Unhandled file type.");
        }

        return p;
    }

    private TiePointGrid addTiePointGrid(int width, int height, String gridName, float[] tiePoints) {
        final TiePointGrid tiePointGrid = createTiePointGrid(gridName, 2, 2, 0, 0, width, height, tiePoints);
        return tiePointGrid;
    }

    private void readMasks(Product p) {
        Assert.notNull(p);
        // critical Implement this method
    }

    private Product getL1bMosaicProduct(File granuleMetadataFile, boolean isAGranule) throws IOException {
        // critical Fix this function
        Objects.requireNonNull(granuleMetadataFile);
        // first we need to recover parent metadata file...

        String filterTileId = null;
        File metadataFile = null;
        if(isAGranule)
        {
            try
            {
                Objects.requireNonNull(granuleMetadataFile.getParentFile());
                Objects.requireNonNull(granuleMetadataFile.getParentFile().getParentFile());
                Objects.requireNonNull(granuleMetadataFile.getParentFile().getParentFile().getParentFile());
            } catch (NullPointerException npe)
            {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", granuleMetadataFile.getName()));
            }

            File up2levels = granuleMetadataFile.getParentFile().getParentFile().getParentFile();
            File tileIdFilter = granuleMetadataFile.getParentFile();

            filterTileId = tileIdFilter.getName();

            File[] files = up2levels.listFiles();
            for(File f: files)
            {
                if(S2L1bProductFilename.isProductFilename(f.getName()) && S2L1bProductFilename.isMetadataFilename(f.getName()))
                {
                    metadataFile = f;
                    break;
                }
            }
            if(metadataFile == null)
            {
                throw new IOException(String.format("Unable to retrieve the product associated to granule metadata file [%s]", granuleMetadataFile.getName()));
            }
        }
        else
        {
            metadataFile = granuleMetadataFile;
        }

        final String aFilter = filterTileId;

        L1bMetadata metadataHeader = null;

        try {
            metadataHeader = parseHeader(metadataFile);
        } catch (JDOMException e) {
            BeamLogManager.getSystemLogger().severe(Utils.getStackTrace(e));
            throw new IOException("Failed to parse metadata in " + metadataFile.getName());
        }

        L1bSceneDescription sceneDescription = L1bSceneDescription.create(metadataHeader, Tile.idGeom.G10M);
        logger.fine("Scene Description: " + sceneDescription);

        File productDir = getProductDir(metadataFile);
        initCacheDir(productDir);

        ProductCharacteristics productCharacteristics = metadataHeader.getProductCharacteristics();

        // fixme what if bandInformations are optional ?
        Map<Integer, TileBandInfo> bandInfoMap = new HashMap<Integer, TileBandInfo>();
        List<L1bMetadata.Tile> tileList = metadataHeader.getTileList();

        if(isAGranule)
        {
            tileList = metadataHeader.getTileList().stream().filter(p -> p.id.equalsIgnoreCase(aFilter)).collect(Collectors.toList());
        }

        Map<String, Tile> tilesById = new HashMap<>(tileList.size());
        for (Tile aTile : tileList) {
            tilesById.put(aTile.id, aTile);
        }

        if (productCharacteristics.bandInformations != null) {
            for (SpectralInformation bandInformation : productCharacteristics.bandInformations) {
                int bandIndex = bandInformation.bandId;
                if (bandIndex >= 0 && bandIndex < productCharacteristics.bandInformations.length) {

                    HashMap<String, File> tileFileMap = new HashMap<String, File>();
                    for (Tile tile : tileList) {
                        S2L1bGranuleDirFilename gf = S2L1bGranuleDirFilename.create(tile.id);
                        Guardian.assertNotNull("Product files don't match regular expressions", gf);

                        S2L1bGranuleImageFilename granuleFileName = gf.getImageFilename(bandInformation.physicalBand);
                        String imgFilename = "GRANULE" + File.separator + tile.id + File.separator + "IMG_DATA" + File.separator + granuleFileName.name;
                        logger.finer("Adding file " + imgFilename + " to band: " + bandInformation.physicalBand);

                        File file = new File(productDir, imgFilename);
                        if (file.exists()) {
                            tileFileMap.put(tile.id, file);
                        } else {
                            logger.warning(String.format("Warning: missing file %s\n", file));
                        }
                    }

                    if (!tileFileMap.isEmpty()) {
                        TileBandInfo tileBandInfo = createBandInfoFromHeaderInfo(bandInformation, tileFileMap);
                        bandInfoMap.put(bandIndex, tileBandInfo);
                    } else {
                        logger.warning(String.format("Warning: no image files found for band %s\n", bandInformation.physicalBand));
                    }
                } else {
                    logger.warning(String.format("Warning: illegal band index detected for band %s\n", bandInformation.physicalBand));
                }
            }
        } else {
            logger.warning("There are no spectral information here !");
        }

        // fixme remove hardcoded indexes

        String[] detectors = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};

        // Order bands by physicalBand
        Map<String, SpectralInformation> sin = new HashMap<String, SpectralInformation>();
        for (SpectralInformation bandInformation : productCharacteristics.bandInformations) {
            sin.put(bandInformation.physicalBand, bandInformation);
        }

        // critical here we must split by detector and band..., detector first
        Map<Pair<String, String>, Map<String, File>> detectorBandInfoMap = new HashMap<Pair<String, String>, Map<String, File>>();
        Map<String, TileBandInfo> bandInfoByKey = new HashMap<String, TileBandInfo>();
        if (productCharacteristics.bandInformations != null) {
            for (Tile tile : tileList) {
                S2L1bGranuleDirFilename gf = S2L1bGranuleDirFilename.create(tile.id);
                Guardian.assertNotNull("Product files don't match regular expressions", gf);

                for (SpectralInformation bandInformation : productCharacteristics.bandInformations) {
                    S2L1bGranuleImageFilename granuleFileName = gf.getImageFilename(bandInformation.physicalBand);
                    String imgFilename = "GRANULE" + File.separator + tile.id + File.separator + "IMG_DATA" + File.separator + granuleFileName.name;

                    logger.finer("Adding file " + imgFilename + " to band: " + bandInformation.physicalBand + ", and detector: " + gf.getDetectorId());

                    File file = new File(productDir, imgFilename);
                    if (file.exists()) {
                        Pair<String, String> key = new Pair<String, String>(bandInformation.physicalBand, gf.getDetectorId());
                        Map<String, File> fileMapper = detectorBandInfoMap.getOrDefault(key, new HashMap<String, File>());
                        fileMapper.put(tile.id, file);
                        if (!detectorBandInfoMap.containsKey(key)) {
                            detectorBandInfoMap.put(key, fileMapper);
                        }
                    } else {
                        logger.warning(String.format("Warning: missing file %s\n", file));
                    }
                }
            }

            if (!detectorBandInfoMap.isEmpty()) {
                for (Pair<String, String> key : detectorBandInfoMap.keySet()) {
                    // critical BandInfo needs its associated TiePointgrid
                    TileBandInfo tileBandInfo = createBandInfoFromHeaderInfo(key.getSecond(), sin.get(key.getFirst()), detectorBandInfoMap.get(key));

                    // composite band name : detector + band
                    String keyMix = key.getSecond() + key.getFirst();
                    bandInfoByKey.put(keyMix, tileBandInfo);
                }
            }
        } else {
            // fixme Look for optional info in schema
            logger.warning("There are no spectral information here !");
        }

        Product product = new Product(FileUtils.getFilenameWithoutExtension(metadataFile),
                                      "S2_MSI_" + productCharacteristics.processingLevel,
                                      sceneDescription.getSceneRectangle().width,
                                      sceneDescription.getSceneRectangle().height);

        product.setFileLocation(metadataFile.getParentFile());

        Map<String, GeoCoding> geoCodingsByDetector = new HashMap<>();

        // fixme Iterate over detectorBandInfoMap and add TiePointGrids to product
        if (!bandInfoByKey.isEmpty()) {
            for (TileBandInfo tbi : bandInfoByKey.values()) {
                if (!geoCodingsByDetector.containsKey(tbi.detectorId)) {
                    GeoCoding gc = getGeoCodingFromTileBandInfo(tbi, tilesById, product);
                    geoCodingsByDetector.put(tbi.detectorId, gc);
                }
            }
        }


        // fixme wait until geocoding is fixed
        /*
        if (product.getGeoCoding() == null) {
            // use default geocoding
            setGeoCoding(product, sceneDescription.getSceneEnvelope());
        }
        */

        product.getMetadataRoot().addElement(metadataHeader.getMetadataElement());
        // setStartStopTime(product, mtdFilename.start, mtdFilename.stop);

        addDetectorBands(product, bandInfoByKey, new L1bSceneMultiLevelImageFactory(sceneDescription, ImageManager.getImageToModelTransform(product.getGeoCoding())));

        return product;
    }

    // critical add complete TiePointGrid
    private GeoCoding getSimpleGeoCodingFromTileBandInfo(TileBandInfo tileBandInfo, Map<String, Tile> tileList, Product product) {
        Objects.requireNonNull(tileBandInfo);
        Objects.requireNonNull(tileList);
        Objects.requireNonNull(product);

        Set<String> ourTileIds = tileBandInfo.tileIdToFileMap.keySet();
        List<Tile> aList = new ArrayList<Tile>(ourTileIds.size());
        List<Coordinate> coords = new ArrayList<>();
        for (String tileId : ourTileIds) {
            Tile currentTile = tileList.get(tileId);
            aList.add(currentTile);
        }

        // sort tiles by position
        Collections.sort(aList, (Tile u1, Tile u2) -> u1.tileGeometry10M.position.compareTo(u2.tileGeometry10M.position));

        for (Tile currentTile : aList) {
            coords.add(currentTile.corners.get(0));
            coords.add(currentTile.corners.get(3));
        }
        coords.add(aList.get(aList.size() - 1).corners.get(1));
        coords.add(aList.get(aList.size() - 1).corners.get(2));

        // critical, look at TiePointGrid construction, clockwise, counterclockwise ?
        float[] lats = convertDoublesToFloats(getLatitudes(coords));
        float[] lons = convertDoublesToFloats(getLongitudes(coords));

        // todo create Tiepointgrid add add to product
        // fixme this will fail, only one geocoding per product ?

        // fixme we should change of each band
        TiePointGrid latGrid = addTiePointGrid(aList.get(0).tileGeometry10M.numCols, aList.get(0).tileGeometry10M.numRowsDetector, tileBandInfo.wavebandInfo.bandName + ",latitude", lats);
        product.addTiePointGrid(latGrid);
        TiePointGrid lonGrid = addTiePointGrid(aList.get(0).tileGeometry10M.numCols, aList.get(0).tileGeometry10M.numRowsDetector, tileBandInfo.wavebandInfo.bandName + ",longitude", lons);
        product.addTiePointGrid(lonGrid);

        GeoCoding geoCoding = new TiePointGeoCoding(latGrid, lonGrid);
        return geoCoding;
    }

    /**
     * Uses the 4 lat-lon corners of a detector to create the geocoding
     *
     * @param tileBandInfo
     * @param tileList
     * @param product
     * @return
     */
    private GeoCoding getGeoCodingFromTileBandInfo(TileBandInfo tileBandInfo, Map<String, Tile> tileList, Product product) {
        Objects.requireNonNull(tileBandInfo);
        Objects.requireNonNull(tileList);
        Objects.requireNonNull(product);

        Set<String> ourTileIds = tileBandInfo.tileIdToFileMap.keySet();
        List<Tile> aList = new ArrayList<Tile>(ourTileIds.size());
        List<Coordinate> coords = new ArrayList<>();
        for (String tileId : ourTileIds) {
            Tile currentTile = tileList.get(tileId);
            aList.add(currentTile);
        }

        // sort tiles by position
        Collections.sort(aList, (Tile u1, Tile u2) -> u1.tileGeometry10M.position.compareTo(u2.tileGeometry10M.position));

        coords.add(aList.get(0).corners.get(0));
        coords.add(aList.get(0).corners.get(3));
        coords.add(aList.get(aList.size() - 1).corners.get(1));
        coords.add(aList.get(aList.size() - 1).corners.get(2));

        // critical, look at TiePointGrid construction, clockwise, counterclockwise ?
        float[] lats = convertDoublesToFloats(getLatitudes(coords));
        float[] lons = convertDoublesToFloats(getLongitudes(coords));

        TiePointGrid latGrid = addTiePointGrid(aList.get(0).tileGeometry10M.numCols, aList.get(0).tileGeometry10M.numRowsDetector, tileBandInfo.wavebandInfo.bandName + ",latitude", lats);
        product.addTiePointGrid(latGrid);
        TiePointGrid lonGrid = addTiePointGrid(aList.get(0).tileGeometry10M.numCols, aList.get(0).tileGeometry10M.numRowsDetector, tileBandInfo.wavebandInfo.bandName + ",longitude", lons);
        product.addTiePointGrid(lonGrid);

        // fixme this will fail, only one geocoding per product ?
        GeoCoding geoCoding = new TiePointGeoCoding(latGrid, lonGrid);
        return geoCoding;
    }

    private void addDetectorBands(Product product, Map<String, TileBandInfo> stringBandInfoMap, MultiLevelImageFactory mlif) throws IOException {
        product.setPreferredTileSize(DEFAULT_JAI_TILE_SIZE, DEFAULT_JAI_TILE_SIZE);
        product.setNumResolutionsMax(L1B_TILE_LAYOUTS[0].numResolutions);

        product.setAutoGrouping("D01:D02:D03:D04:D05:D06:D07:D08:D09:D10:D11:D12");

        ArrayList<String> bandIndexes = new ArrayList<String>(stringBandInfoMap.keySet());
        Collections.sort(bandIndexes);

        if (bandIndexes.isEmpty()) {
            throw new IOException("No valid bands found.");
        }

        for (String bandIndex : bandIndexes) {
            TileBandInfo tileBandInfo = stringBandInfoMap.get(bandIndex);
            Band band = addBand(product, tileBandInfo);
            band.setSourceImage(mlif.createSourceImage(tileBandInfo));
        }
    }

    private Band addBand(Product product, TileBandInfo tileBandInfo) {
        final Band band = product.addBand(tileBandInfo.wavebandInfo.bandName, SAMPLE_PRODUCT_DATA_TYPE);

        band.setSpectralBandIndex(tileBandInfo.bandIndex);
        band.setSpectralWavelength((float) tileBandInfo.wavebandInfo.wavelength);
        band.setSpectralBandwidth((float) tileBandInfo.wavebandInfo.bandwidth);


        //todo add masks from GML metadata files (gml branch)
        setValidPixelMask(band, tileBandInfo.wavebandInfo.bandName);

        // todo - We don't use the scaling factor because we want to stay with 16bit unsigned short samples due to the large
        // amounts of data when saving the images. We provide virtual reflectance bands for this reason. We can use the
        // scaling factor again, once we have product writer parameters, so that users can decide to write data as
        // 16bit samples.
        //
        //band.setScalingFactor(bandInfo.wavebandInfo.scalingFactor);

        return band;
    }

    private void setValidPixelMask(Band band, String bandName) {
        band.setNoDataValue(0);
        band.setValidPixelExpression(String.format("%s.raw > %s",
                                                   bandName, S2L1bConfig.RAW_NO_DATA_THRESHOLD));
    }

    private TiePointGrid createTiePointGrid(String name, int gridWidth, int gridHeight, float[] values) {
        final TiePointGrid tiePointGrid = new TiePointGrid(name, gridWidth, gridHeight, 0.0F, 0.0F, 500.0F, 500.0F, values);
        tiePointGrid.setNoDataValue(Double.NaN);
        tiePointGrid.setNoDataValueUsed(true);
        return tiePointGrid;
    }

    private static Map<String, File> createFileMap(String tileId, File imageFile) {
        Map<String, File> tileIdToFileMap = new HashMap<String, File>();
        tileIdToFileMap.put(tileId, imageFile);
        return tileIdToFileMap;
    }

    private void setStartStopTime(Product product, String start, String stop) {
        try {
            product.setStartTime(ProductData.UTC.parse(start, "yyyyMMddHHmmss"));
        } catch (ParseException e) {
            // {@report "illegal start date"}
        }

        try {
            product.setEndTime(ProductData.UTC.parse(stop, "yyyyMMddHHmmss"));
        } catch (ParseException e) {
            // {@report "illegal stop date"}
        }
    }

    private TileBandInfo createBandInfoFromDefaults(int bandIndex, S2L1bWavebandInfo wavebandInfo, String tileId, File imageFile) {
        // L1cTileLayout aLayout = CodeStreamUtils.getL1bTileLayout(imageFile.toURI().toString(), null);
        return new TileBandInfo(createFileMap(tileId, imageFile),
                                bandIndex, null,
                                wavebandInfo,
                                // aLayout);
                                //todo test this
                                L1B_TILE_LAYOUTS[wavebandInfo.resolution.id]);

    }

    private TileBandInfo createBandInfoFromHeaderInfo(SpectralInformation bandInformation, Map<String, File> tileFileMap) {
        S2L1bSpatialResolution spatialResolution = S2L1bSpatialResolution.valueOfResolution(bandInformation.resolution);
        return new TileBandInfo(tileFileMap,
                                bandInformation.bandId, null,
                                new S2L1bWavebandInfo(bandInformation.bandId,
                                                      bandInformation.physicalBand,
                                                      spatialResolution, bandInformation.wavelenghtCentral,
                                                      Math.abs(bandInformation.wavelenghtMax + bandInformation.wavelenghtMin)),
                                L1B_TILE_LAYOUTS[spatialResolution.id]);
    }

    private TileBandInfo createBandInfoFromHeaderInfo(String detector, SpectralInformation bandInformation, Map<String, File> tileFileMap) {
        S2L1bSpatialResolution spatialResolution = S2L1bSpatialResolution.valueOfResolution(bandInformation.resolution);
        return new TileBandInfo(tileFileMap,
                                bandInformation.bandId, detector,
                                new S2L1bWavebandInfo(bandInformation.bandId,
                                                      detector + bandInformation.physicalBand, // critical text shown to user in menu (detector, band) is evaluated as an expression !!
                                                      spatialResolution, bandInformation.wavelenghtCentral,
                                                      Math.abs(bandInformation.wavelenghtMax + bandInformation.wavelenghtMin)),
                                L1B_TILE_LAYOUTS[spatialResolution.id]);
    }

    private void setGeoCoding(Product product, Envelope2D envelope) {
        try {
            product.setGeoCoding(new CrsGeoCoding(envelope.getCoordinateReferenceSystem(),
                                                  product.getSceneRasterWidth(),
                                                  product.getSceneRasterHeight(),
                                                  envelope.getMinX(),
                                                  envelope.getMaxY(),
                                                  S2L1bSpatialResolution.R10M.resolution,
                                                  S2L1bSpatialResolution.R10M.resolution,
                                                  0.0, 0.0));
        } catch (FactoryException e) {
            logger.severe("Illegal CRS");
        } catch (TransformException e) {
            logger.severe("Illegal projection");
        }
    }

    static File getProductDir(File productFile) throws IOException {
        final File resolvedFile = productFile.getCanonicalFile();
        if (!resolvedFile.exists()) {
            throw new FileNotFoundException("File not found: " + productFile);
        }

        if (productFile.getParentFile() == null) {
            return new File(".").getCanonicalFile();
        }

        return productFile.getParentFile();
    }

    void initCacheDir(File productDir) throws IOException {
        cacheDir = new File(new File(SystemUtils.getApplicationDataDir(), "beam-sentinel2-reader/cache"),
                            productDir.getName());
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        if (!cacheDir.exists() || !cacheDir.isDirectory() || !cacheDir.canWrite()) {
            throw new IOException("Can't access package cache directory");
        }
    }


    private abstract class MultiLevelImageFactory {
        protected final AffineTransform imageToModelTransform;

        protected MultiLevelImageFactory(AffineTransform imageToModelTransform) {
            this.imageToModelTransform = imageToModelTransform;
        }

        public abstract MultiLevelImage createSourceImage(TileBandInfo tileBandInfo);
    }

    private class L1bTileMultiLevelImageFactory extends MultiLevelImageFactory {
        private L1bTileMultiLevelImageFactory(AffineTransform imageToModelTransform) {
            super(imageToModelTransform);
        }

        public MultiLevelImage createSourceImage(TileBandInfo tileBandInfo) {
            return new DefaultMultiLevelImage(new L1bTileMultiLevelSource(tileBandInfo, imageToModelTransform));
        }
    }

    private class L1bSceneMultiLevelImageFactory extends MultiLevelImageFactory {

        private final L1bSceneDescription sceneDescription;

        public L1bSceneMultiLevelImageFactory(L1bSceneDescription sceneDescription, AffineTransform imageToModelTransform) {
            super(imageToModelTransform);

            BeamLogManager.getSystemLogger().fine("Model factory: " + ToStringBuilder.reflectionToString(imageToModelTransform));

            this.sceneDescription = sceneDescription;
        }

        @Override
        public MultiLevelImage createSourceImage(TileBandInfo tileBandInfo) {
            BandL1bSceneMultiLevelSource bandScene = new BandL1bSceneMultiLevelSource(sceneDescription, tileBandInfo, imageToModelTransform);
            BeamLogManager.getSystemLogger().log(Level.parse(S2L1bConfig.LOG_SCENE), "BandScene: " + bandScene);
            return new DefaultMultiLevelImage(bandScene);
        }
    }

    /**
     * A MultiLevelSource for single L1C tiles.
     */
    private class L1bTileMultiLevelSource extends AbstractMultiLevelSource {
        final TileBandInfo tileBandInfo;

        public L1bTileMultiLevelSource(TileBandInfo tileBandInfo, AffineTransform imageToModelTransform) {
            super(new DefaultMultiLevelModel(tileBandInfo.imageLayout.numResolutions,
                                             imageToModelTransform,
                                             L1B_TILE_LAYOUTS[0].width, //fixme we must use data from jp2 files to update this
                                             L1B_TILE_LAYOUTS[0].height)); //fixme we must use data from jp2 files to update this
            this.tileBandInfo = tileBandInfo;
        }

        @Override
        protected RenderedImage createImage(int level) {
            File imageFile = tileBandInfo.tileIdToFileMap.values().iterator().next();
            return L1bTileOpImage.create(imageFile,
                                         cacheDir,
                                         null,
                                         tileBandInfo.imageLayout,
                                         getModel(),
                                         tileBandInfo.wavebandInfo.resolution,
                                         level);
        }

    }

    /**
     * A MultiLevelSource for a scene made of multiple L1C tiles.
     */
    private abstract class AbstractL1bSceneMultiLevelSource extends AbstractMultiLevelSource {
        protected final L1bSceneDescription sceneDescription;

        AbstractL1bSceneMultiLevelSource(L1bSceneDescription sceneDescription, AffineTransform imageToModelTransform, int numResolutions) {
            super(new DefaultMultiLevelModel(numResolutions,
                                             imageToModelTransform,
                                             sceneDescription.getSceneRectangle().width,
                                             sceneDescription.getSceneRectangle().height));
            this.sceneDescription = sceneDescription;
        }


        protected abstract PlanarImage createL1bTileImage(String tileId, int level);
    }

    /**
     * A MultiLevelSource used by bands for a scene made of multiple L1C tiles.
     */
    private final class BandL1bSceneMultiLevelSource extends AbstractL1bSceneMultiLevelSource {
        private final TileBandInfo tileBandInfo;

        // critical remove Memory profilers
        // private MemoryMeter meter;

        public BandL1bSceneMultiLevelSource(L1bSceneDescription sceneDescription, TileBandInfo tileBandInfo, AffineTransform imageToModelTransform) {
            super(sceneDescription, imageToModelTransform, tileBandInfo.imageLayout.numResolutions);
            this.tileBandInfo = tileBandInfo;
            // this.meter = new MemoryMeter();
        }

        @Override
        protected PlanarImage createL1bTileImage(String tileId, int level) {
            File imageFile = tileBandInfo.tileIdToFileMap.get(tileId);

            PlanarImage planarImage = L1bTileOpImage.create(imageFile,
                                                            cacheDir,
                                                            null, // tileRectangle.getLocation(),
                                                            tileBandInfo.imageLayout,
                                                            getModel(),
                                                            tileBandInfo.wavebandInfo.resolution,
                                                            level);

            logger.fine(String.format("Planar image model: %s", getModel().toString()));

            logger.fine(String.format("Planar image created: %s %s: minX=%d, minY=%d, width=%d, height=%d\n",
                                      tileBandInfo.wavebandInfo.bandName, tileId,
                                      planarImage.getMinX(), planarImage.getMinY(),
                                      planarImage.getWidth(), planarImage.getHeight()));

            return planarImage;
        }

        @Override
        protected RenderedImage createImage(int level) {
            ArrayList<RenderedImage> tileImages = new ArrayList<RenderedImage>();

            long preImage = 0;
            long postImage = 0;
            // long zeroImage = meter.measureDeep(tileImages);

            // fixme here comes the extra filter coming from extra field in Bandinfo

            List<String> tiles = sceneDescription.getTileIds().stream().filter(x -> x.contains(tileBandInfo.detectorId)).collect(Collectors.toList());

            for (String tileId : tiles) {
                int tileIndex = sceneDescription.getTileIndex(tileId);
                Rectangle tileRectangle = sceneDescription.getTileRectangle(tileIndex);

                // fixme look at tiles without associated filename, look at tileId list
                PlanarImage opImage = createL1bTileImage(tileId, level);
                // preImage = meter.measureDeep(opImage);

                {
                    double factorX = 1.0 / (Math.pow(2, level) * (this.tileBandInfo.wavebandInfo.resolution.resolution / S2L1bSpatialResolution.R10M.resolution));
                    double factorY = 1.0 / (Math.pow(2, level) * (this.tileBandInfo.wavebandInfo.resolution.resolution / S2L1bSpatialResolution.R10M.resolution));

                    opImage = TranslateDescriptor.create(opImage,
                                                         (float) Math.floor((tileRectangle.x * factorX)),
                                                         (float) Math.floor((tileRectangle.y * factorY)),
                                                         Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);

                    logger.log(Level.parse(S2L1bConfig.LOG_SCENE), String.format("Translate descriptor: %s", ToStringBuilder.reflectionToString(opImage)));

                    // postImage = meter.measureDeep(opImage);
                }

                logger.log(Level.parse(S2L1bConfig.LOG_SCENE), String.format("opImage added for level %d at (%d,%d) with size (%d,%d)%n", level, opImage.getMinX(), opImage.getMinY(), opImage.getWidth(), opImage.getHeight()));
                tileImages.add(opImage);
            }

            // long deadShot = meter.measureDeep(tileImages);

            if (tileImages.isEmpty()) {
                logger.warning("No tile images for mosaic");
                return null;
            }

            ImageLayout imageLayout = new ImageLayout();
            imageLayout.setMinX(0);
            imageLayout.setMinY(0);
            imageLayout.setTileWidth(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileHeight(DEFAULT_JAI_TILE_SIZE);
            imageLayout.setTileGridXOffset(0);
            imageLayout.setTileGridYOffset(0);

            RenderedOp mosaicOp = MosaicDescriptor.create(tileImages.toArray(new RenderedImage[tileImages.size()]),
                                                          MosaicDescriptor.MOSAIC_TYPE_OVERLAY,
                                                          null, null, new double[][]{{1.0}}, new double[]{FILL_CODE_MOSAIC_BG},
                                                          new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

            // todo add crop or extend here to ensure "right" size...
            Rectangle fitrect = new Rectangle(0, 0, (int) sceneDescription.getSceneEnvelope().getWidth() / tileBandInfo.wavebandInfo.resolution.resolution, (int) sceneDescription.getSceneEnvelope().getHeight() / tileBandInfo.wavebandInfo.resolution.resolution);
            final Rectangle destBounds = DefaultMultiLevelSource.getLevelImageBounds(fitrect, Math.pow(2.0, level));

            BorderExtender borderExtender = BorderExtender.createInstance(BorderExtender.BORDER_COPY);

            if (mosaicOp.getWidth() < destBounds.width || mosaicOp.getHeight() < destBounds.height) {
                int rightPad = destBounds.width - mosaicOp.getWidth();
                int bottomPad = destBounds.height - mosaicOp.getHeight();
                BeamLogManager.getSystemLogger().log(Level.parse(S2L1bConfig.LOG_SCENE), String.format("Border: (%d, %d), (%d, %d)", mosaicOp.getWidth(), destBounds.width, mosaicOp.getHeight(), destBounds.height));

                mosaicOp = BorderDescriptor.create(mosaicOp, 0, rightPad, 0, bottomPad, borderExtender, null);
            }

            if (this.tileBandInfo.wavebandInfo.resolution != S2L1bSpatialResolution.R10M) {
                PlanarImage scaled = L1bTileOpImage.createGenericScaledImage(mosaicOp, sceneDescription.getSceneEnvelope(), this.tileBandInfo.wavebandInfo.resolution, level);

                logger.log(Level.parse(S2L1bConfig.LOG_SCENE), String.format("mosaicOp created for level %d at (%d,%d) with size (%d, %d)%n", level, scaled.getMinX(), scaled.getMinY(), scaled.getWidth(), scaled.getHeight()));

                return scaled;
            }
            // todo add crop ?

            logger.log(Level.parse(S2L1bConfig.LOG_SCENE), String.format("mosaicOp created for level %d at (%d,%d) with size (%d, %d)%n", level, mosaicOp.getMinX(), mosaicOp.getMinY(), mosaicOp.getWidth(), mosaicOp.getHeight()));

            return mosaicOp;
        }

        @Override
        public String toString() {
            return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
        }
    }
}
