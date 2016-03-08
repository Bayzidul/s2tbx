package org.esa.s2tbx.dataio.spot6;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import org.esa.s2tbx.dataio.VirtualDirEx;
import org.esa.s2tbx.dataio.readers.GMLReader;
import org.esa.s2tbx.dataio.spot6.dimap.ImageMetadata;
import org.esa.s2tbx.dataio.spot6.dimap.Spot6Constants;
import org.esa.s2tbx.dataio.spot6.dimap.VolumeMetadata;
import org.esa.s2tbx.dataio.spot6.internal.MosaicMultiLevelSource;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.TreeNode;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.awt.image.DataBuffer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reader for SPOT 6/7 products.
 *
 * @author Cosmin Cara
 */
public class Spot6ProductReader extends AbstractProductReader {

    private Spot6ProductReaderPlugin plugIn;
    private VirtualDirEx productDirectory;
    private VolumeMetadata metadata;
    private final static Map<Integer, Integer> typeMap = new HashMap<Integer, Integer>() {{
        put(ProductData.TYPE_UINT8, DataBuffer.TYPE_BYTE);
        put(ProductData.TYPE_INT8, DataBuffer.TYPE_BYTE);
        put(ProductData.TYPE_UINT16, DataBuffer.TYPE_USHORT);
        put(ProductData.TYPE_INT16, DataBuffer.TYPE_SHORT);
        put(ProductData.TYPE_UINT32, DataBuffer.TYPE_INT);
        put(ProductData.TYPE_INT32, DataBuffer.TYPE_INT);
        put(ProductData.TYPE_FLOAT32, DataBuffer.TYPE_FLOAT);
    }};
    private final Logger logger;

    protected Spot6ProductReader(Spot6ProductReaderPlugin readerPlugIn) {
        super(readerPlugIn);
        plugIn = readerPlugIn;
        logger = Logger.getLogger(Spot6ProductReader.class.getName());
    }

    @Override
    public TreeNode<File> getProductComponents() {
        if (productDirectory.isCompressed()) {
            return super.getProductComponents();
        } else {
            TreeNode<File> result = super.getProductComponents();
            //if the volume metadata file is present, but it is not in the list, add it!
            try {
                File volumeMetadataPhysicalFile = productDirectory.getFile(Spot6Constants.ROOT_METADATA);
                if (metadata != null) {
                    addProductComponentIfNotPresent(Spot6Constants.ROOT_METADATA, volumeMetadataPhysicalFile, result);
                    for (VolumeMetadata component : metadata.getVolumeMetadataList()) {
                        try {
                            addProductComponentIfNotPresent(component.getPath(), productDirectory.getFile(component.getPath()), result);
                        } catch (IOException ex) {
                            logger.warning(ex.getMessage());
                        }
                    }

                    for (ImageMetadata component : metadata.getImageMetadataList()) {
                        try {
                            //add thumb file of the component
                            addProductComponentIfNotPresent(component.getPath(), productDirectory.getFile(component.getPath()), result);
                        } catch (IOException ex) {
                            logger.warning(ex.getMessage());
                        }
                    }
                }
            } catch (IOException ex) {
                logger.warning(ex.getMessage());
            }

            return result;
        }
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        productDirectory = plugIn.getInput(getInput());
        metadata = VolumeMetadata.create(productDirectory.getFile(Spot6Constants.ROOT_METADATA).toPath());
        Product product = null;
        if (metadata != null) {
            List<ImageMetadata> imageMetadataList = metadata.getImageMetadataList();
            if (imageMetadataList.size() == 0) {
                throw new IOException("No raster found");
            }
            int width = metadata.getSceneWidth();
            int height = metadata.getSceneHeight();
            product = new Product(metadata.getInternalReference(),
                                  metadata.getProductType(),
                                  width, height);
            product.setFileLocation(new File(metadata.getPath()));
            ImageMetadata maxResImageMetadata = metadata.getMaxResolutionImage();
            product.setStartTime(maxResImageMetadata.getProductStartTime());
            product.setEndTime(maxResImageMetadata.getProductEndTime());
            product.setDescription(maxResImageMetadata.getProductDescription());
            product.setNumResolutionsMax(imageMetadataList.size());
            ImageMetadata.InsertionPoint origin = maxResImageMetadata.getInsertPoint();
            if (maxResImageMetadata.hasInsertPoint()) {
                String crsCode = maxResImageMetadata.getCRSCode();
                try {
                    GeoCoding geoCoding = new CrsGeoCoding(CRS.decode(crsCode),
                                                            width, height,
                                                            origin.x, origin.y,
                                                            origin.stepX, origin.stepY);
                    product.setSceneGeoCoding(geoCoding);
                } catch (Exception e) {
                    logger.warning(e.getMessage());
                }
            } else {
                initProductTiePointGeoCoding(maxResImageMetadata, product);
            }
            for (ImageMetadata imageMetadata : imageMetadataList) {
                product.getMetadataRoot().addElement(imageMetadata.getRootElement());
                int numBands = imageMetadata.getNumBands();
                ImageMetadata.BandInfo[] bandInfos = imageMetadata.getBandsInformation();

                int pixelDataType = imageMetadata.getPixelDataType();
                int tileRows = imageMetadata.getTileRowsCount();
                int tileCols = imageMetadata.getTileColsCount();
                int tileWidth = imageMetadata.getTileWidth();
                int tileHeight = imageMetadata.getTileHeight();
                int noDataValue = imageMetadata.getNoDataValue();
                int bandWidth = imageMetadata.getRasterWidth();
                int bandHeight = imageMetadata.getRasterHeight();
                float factorX = (float) width / bandWidth;
                float factorY = (float) height / bandHeight;

                Float[] solarIrradiances = imageMetadata.getSolarIrradiances();
                double[][] scalingAndOffsets = imageMetadata.getScalingAndOffsets();
                Map<String, int[]> tileInfo = imageMetadata.getRasterTileInfo();
                Product[][] tiles = new Product[tileCols][tileRows];
                for (String rasterFile : tileInfo.keySet()) {
                    int[] coords = tileInfo.get(rasterFile);
                    tiles[coords[0]][coords[1]] = ProductIO.readProduct(Paths.get(imageMetadata.getPath()).resolve(rasterFile).toFile());
                }
                int levels = tiles[0][0].getBandAt(0).getSourceImage().getModel().getLevelCount();
                final Stx[] statistics = imageMetadata.getBandsStatistics();
                for (int i = 0; i < numBands; i++) {
                    Band targetBand = new Band(bandInfos[i].getId(), pixelDataType,
                                                Math.round(width / factorX),
                                                Math.round(height / factorY));
                    targetBand.setSpectralBandIndex(i);
                    targetBand.setSpectralWavelength(bandInfos[i].getCentralWavelength());
                    targetBand.setSpectralBandwidth(bandInfos[i].getBandwidth());
                    targetBand.setSolarFlux(solarIrradiances[i]);
                    targetBand.setUnit(bandInfos[i].getUnit());
                    targetBand.setNoDataValue(noDataValue);
                    targetBand.setNoDataValueUsed(true);
                    targetBand.setScalingFactor(scalingAndOffsets[i][0] / bandInfos[i].getGain());
                    targetBand.setScalingOffset(scalingAndOffsets[i][1] * bandInfos[i].getBias());
                    initBandGeoCoding(imageMetadata, targetBand, width, height);
                    Band[][] srcBands = new Band[tileRows][tileCols];
                    for (int x = 0; x < tileRows; x++) {
                        for (int y = 0; y < tileCols; y++) {
                            srcBands[x][y] = tiles[x][y].getBandAt(i);
                        }
                    }

                    MosaicMultiLevelSource bandSource =
                            new MosaicMultiLevelSource(srcBands,
                                    bandWidth, bandHeight,
                                    tileWidth, tileHeight, tileRows, tileCols,
                                    levels, typeMap.get(pixelDataType),
                                    imageMetadata.isGeocoded() ?
                                            targetBand.getGeoCoding() != null ?
                                                    Product.findImageToModelTransform(targetBand.getGeoCoding()) :
                                                    Product.findImageToModelTransform(product.getSceneGeoCoding()) :
                                            targetBand.getImageToModelTransform());
                    targetBand.setSourceImage(new DefaultMultiLevelImage(bandSource));

                    product.addBand(targetBand);

                }
                addMasks(product, imageMetadata);
                addGMLMasks(product, imageMetadata);
            }
            product.setModified(false);
        }

        return product;
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY,
                                          int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY,
                                          Band destBand,
                                          int destOffsetX, int destOffsetY,
                                          int destWidth, int destHeight,
                                          ProductData destBuffer, ProgressMonitor pm) throws IOException {
    }

    private void initProductTiePointGeoCoding(ImageMetadata imageMetadata, Product product) {
        float[][] cornerLonsLats = imageMetadata.getCornerLonsLats();
        int sceneWidth = product.getSceneRasterWidth();
        int sceneHeight = product.getSceneRasterHeight();
        TiePointGrid latGrid = createTiePointGrid("latitude", 2, 2, 0, 0, sceneWidth , sceneHeight, cornerLonsLats[1]);
        product.addTiePointGrid(latGrid);
        TiePointGrid lonGrid = createTiePointGrid("longitude", 2, 2, 0, 0, sceneWidth, sceneHeight, cornerLonsLats[0]);
        product.addTiePointGrid(lonGrid);
        product.setSceneGeoCoding(new TiePointGeoCoding(latGrid, lonGrid));
    }

    private void initBandGeoCoding(ImageMetadata imageMetadata, Band band, int sceneWidth, int sceneHeight) {
        int bandWidth = imageMetadata.getRasterWidth();
        int bandHeight = imageMetadata.getRasterHeight();
        GeoCoding geoCoding = null;
        ImageMetadata.InsertionPoint insertPoint = imageMetadata.getInsertPoint();
        String crsCode = imageMetadata.getCRSCode();
        try {
            CoordinateReferenceSystem crs = CRS.decode(crsCode);
            if (imageMetadata.hasInsertPoint()) {
                    geoCoding = new CrsGeoCoding(crs,
                            bandWidth, bandHeight,
                            insertPoint.x, insertPoint.y,
                            insertPoint.stepX, insertPoint.stepY, 0.0, 0.0);
            } else {
                if (sceneWidth != bandWidth) {
                    AffineTransform2D transform2D = new AffineTransform2D((float) sceneWidth / bandWidth, 0.0, 0.0, (float) sceneHeight / bandHeight, 0.0, 0.0);
                    band.setImageToModelTransform(transform2D);
                }
            }
        } catch (Exception e) {
            logger.warning(e.getMessage());
        }
        band.setGeoCoding(geoCoding);
    }

    private void addMasks(Product target, ImageMetadata metadata) {
        ProductNodeGroup<Mask> maskGroup = target.getMaskGroup();
        if (!maskGroup.contains(Spot6Constants.NODATA)) {
            int noDataValue = metadata.getNoDataValue();
            maskGroup.add(Mask.BandMathsType.create(Spot6Constants.NODATA, Spot6Constants.NODATA,
                                                    target.getSceneRasterWidth(), target.getSceneRasterHeight(),
                                                    String.valueOf(noDataValue), Color.BLACK, 0.5));
        }
        if (!maskGroup.contains(Spot6Constants.SATURATED)) {
            int saturatedValue = metadata.getSaturatedValue();
            maskGroup.add(Mask.BandMathsType.create(Spot6Constants.SATURATED, Spot6Constants.SATURATED,
                                                    target.getSceneRasterWidth(), target.getSceneRasterHeight(),
                                                    String.valueOf(saturatedValue), Color.ORANGE, 0.5));
        }
    }

    private void addGMLMasks(Product target, ImageMetadata metadata) {
        List<ImageMetadata.MaskInfo> gmlMasks = metadata.getMasks();
        final ProductNodeGroup<VectorDataNode> vectorDataGroup = target.getVectorDataGroup();
        gmlMasks.stream().filter(mask -> !vectorDataGroup.contains(mask.name)).forEach(mask -> {
            logger.info(String.format("Parsing mask %s of component %s", mask.name, metadata.getFileName()));
            VectorDataNode node = GMLReader.parse(mask.name, mask.path);
            if (node != null) {
                node.setDescription(mask.description);
                vectorDataGroup.add(node);
            }
        });
    }


    private void addProductComponentIfNotPresent(String componentId, File componentFile, TreeNode<File> currentComponents) {
        TreeNode<File> resultComponent = null;
        for (TreeNode node : currentComponents.getChildren()) {
            if (node.getId().toLowerCase().equals(componentId.toLowerCase())) {
                //noinspection unchecked
                resultComponent = node;
                break;
            }
        }
        if (resultComponent == null) {
            resultComponent = new TreeNode<File>(componentId, componentFile);
            currentComponents.addChild(resultComponent);
        }
    }

}
