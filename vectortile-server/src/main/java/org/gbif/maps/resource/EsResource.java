package org.gbif.maps.resource;

import org.gbif.maps.common.bin.HexBin;
import org.gbif.maps.common.bin.SquareBin;
import org.gbif.maps.common.projection.Double2D;
import org.gbif.maps.common.projection.TileProjection;
import org.gbif.maps.common.projection.TileSchema;
import org.gbif.maps.common.projection.Tiles;
import org.gbif.occurrence.search.heatmap.OccurrenceHeatmapRequest;
import org.gbif.occurrence.search.heatmap.OccurrenceHeatmapRequestProvider;
import org.gbif.occurrence.search.heatmap.OccurrenceHeatmapService;
import org.gbif.occurrence.search.heatmap.es.EsOccurrenceHeatmapResponse;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import no.ecc.vectortile.VectorTileEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.maps.resource.Params.BIN_MODE_HEX;
import static org.gbif.maps.resource.Params.BIN_MODE_SQUARE;
import static org.gbif.maps.resource.Params.DEFAULT_HEX_PER_TILE;
import static org.gbif.maps.resource.Params.DEFAULT_SQUARE_SIZE;
import static org.gbif.maps.resource.Params.HEX_TILE_SIZE;
import static org.gbif.maps.resource.Params.SQUARE_TILE_SIZE;
import static org.gbif.maps.resource.Params.enableCORS;

/**
 * SOLR search as a vector tile service.
 * Note to developers: This class could benefit from some significant refactoring and cleanup.
 */
@Path("/occurrence/adhoc")
@Singleton
public final class EsResource {

  private static final Logger LOG = LoggerFactory.getLogger(EsResource.class);
  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  @VisibleForTesting
  static final double QUERY_BUFFER_PERCENTAGE = 0.125;  // 1/8th tile buffer all around, similar to the HBase maps

  private final int tileSize;
  private final int bufferSize;
  private final TileProjection projection;
  private final OccurrenceHeatmapService<EsOccurrenceHeatmapResponse> heatmapService;


  public EsResource(OccurrenceHeatmapService<EsOccurrenceHeatmapResponse> heatmapService, int tileSize, int bufferSize) throws IOException {
    this.tileSize = tileSize;
    this.bufferSize = bufferSize;
    this.heatmapService = heatmapService;
    projection = Tiles.fromEPSG("EPSG:4326", tileSize);
  }

  @GET
  @Path("/{z}/{x}/{y}.mvt")
  @Timed
  @Produces("application/x-protobuf")
  public byte[] all(@PathParam("type") String type, @PathParam("key") String key,
                    @PathParam("z") int z, @PathParam("x") long x, @PathParam("y") long y,
                    @DefaultValue("EPSG:4326") @QueryParam("srs") String srs,
                    @QueryParam("bin") String bin,
                    @DefaultValue(DEFAULT_HEX_PER_TILE) @QueryParam("hexPerTile") int hexPerTile,
                    @DefaultValue(DEFAULT_SQUARE_SIZE) @QueryParam("squareSize") int squareSize,
                    @Context HttpServletResponse response, @Context HttpServletRequest request) throws Exception {
    enableCORS(response);
    Preconditions.checkArgument("EPSG:4326".equalsIgnoreCase(srs),
                                "Adhoc search maps are currently only available in EPSG:4326");
    OccurrenceHeatmapRequest heatmapRequest = OccurrenceHeatmapRequestProvider.buildOccurrenceHeatmapRequest(request);


    Preconditions.checkArgument(bin == null || BIN_MODE_HEX.equalsIgnoreCase(bin)
        || BIN_MODE_SQUARE.equalsIgnoreCase(bin), "Unsupported bin mode");


    heatmapRequest.setGeometry(searchGeom(z, x, y));
    LOG.info("Heatmap request:{}", heatmapRequest);

    EsOccurrenceHeatmapResponse heatmapResponse = heatmapService.searchHeatMap(heatmapRequest);
    VectorTileEncoder encoder = new VectorTileEncoder (tileSize, bufferSize, false);

    // iterate the data structure from SOLR painting cells
    for (EsOccurrenceHeatmapResponse.GeoGridBucket bucket : heatmapResponse.getBuckets()) {
        if (bucket.getDocCount()  > 0) {

          Rectangle2D.Double cell = asRectangle(bucket.getCell());
          // get the extent of the cell
          final Point2D cellSW = new Point2D.Double(cell.getMinX(), cell.getMinY());
          final Point2D cellNE = new Point2D.Double(cell.getMaxX(), cell.getMaxY());

          double minXAsNorm = cellSW.getX();
          double maxXAsNorm = cellNE.getX();
          double minYAsNorm = cellSW.getY();
          double maxYAsNorm = cellNE.getY();

          // convert the lat,lng into pixel coordinates
          Double2D swGlobalXY = projection.toGlobalPixelXY(maxYAsNorm, minXAsNorm, z);
          Double2D neGlobalXY = projection.toGlobalPixelXY(minYAsNorm, maxXAsNorm, z);
          Double2D swTileXY = Tiles.toTileLocalXY(swGlobalXY, TileSchema.WGS84_PLATE_CAREÉ, z, x, y, tileSize, bufferSize);
          Double2D neTileXY = Tiles.toTileLocalXY(neGlobalXY, TileSchema.WGS84_PLATE_CAREÉ, z, x, y, tileSize, bufferSize);

          int minX = (int) swTileXY.getX();
          int maxX = (int) neTileXY.getX();
          double centerX = minX + (((double) maxX - minX) / 2);

          int minY = (int) swTileXY.getY();
          int maxY = (int) neTileXY.getY();
          double centerY = minY + (((double) maxY - minY) / 2);

          Map<String, Object> meta = new HashMap<>();
          meta.put("total", bucket.getDocCount());

          // for binning, we add the cell center point, otherwise the geometry
          if (bin != null) {
            // hack: use just the center points for each cell
            Coordinate center = new Coordinate(centerX, centerY);
            encoder.addFeature("occurrence", meta, GEOMETRY_FACTORY.createPoint(center));

          } else {
            // default behaviour with polygon squares for the cells
            Coordinate[] coords = new Coordinate[] {
              new Coordinate(swTileXY.getX(), swTileXY.getY()),
              new Coordinate(neTileXY.getX(), swTileXY.getY()),
              new Coordinate(neTileXY.getX(), neTileXY.getY()),
              new Coordinate(swTileXY.getX(), neTileXY.getY()),
              new Coordinate(swTileXY.getX(), swTileXY.getY())
            };

            encoder.addFeature("occurrence", meta, GEOMETRY_FACTORY.createPolygon(coords));
          }
        }
    }

    byte[] encodedTile = encoder.encode();
    if (BIN_MODE_HEX.equalsIgnoreCase(bin) && heatmapResponse.getBuckets() != null && !heatmapResponse.getBuckets().isEmpty()) {
      // binning will throw IAE on no data, so code defensively
      HexBin binner = new HexBin(HEX_TILE_SIZE, hexPerTile);
      return binner.bin(encodedTile, z, x, y);

    } else if (BIN_MODE_SQUARE.equalsIgnoreCase(bin) && heatmapResponse.getBuckets() != null && !heatmapResponse.getBuckets().isEmpty()) {
      SquareBin binner = new SquareBin(SQUARE_TILE_SIZE, squareSize);
      return binner.bin(encodedTile, z, x, y);
    } else {
      return encodedTile;
    }
  }




  /**
   * Returns a BBox search string for the geometry in WGS84 CRS for the tile with a buffer.
   */
  private static String searchGeom(int z, long x, long y) {
    Double2D[] boundary = bufferedTileBoundary(z, x, y);
    return boundary[0].getX() + "," + boundary[0].getY() + "," + boundary[1].getX() + "," + boundary[1].getY();
  }

  /**
   * For the given tile, returns the envelope for the tile, with a buffer.
   * @param z zoom
   * @param x tile X address
   * @param y tile Y address
   * @return an envelope for the tile, with the appropriate buffer
   */
  @VisibleForTesting
  static Double2D[] bufferedTileBoundary(int z, long x, long y) {
    int tilesPerZoom = 1 << z;
    double degreesPerTile = 180d/tilesPerZoom;
    double bufferDegrees = QUERY_BUFFER_PERCENTAGE * degreesPerTile;

    // the edges of the tile after buffering
    double minLng = (degreesPerTile * x) - 180 - bufferDegrees;
    double maxLng = minLng + degreesPerTile + (bufferDegrees * 2);

    double maxLat = 90 - (degreesPerTile * y) + bufferDegrees;
    double minLat = maxLat - degreesPerTile - 2 * bufferDegrees;

    // handle the dateline wrapping (for all zooms above 0, which needs special attention)

    // clip the extent (SOLR barfs otherwise)
    maxLat = Math.min(maxLat, 90);
    minLat = Math.max(minLat, -90);

    return new Double2D[] {new Double2D(minLng, minLat), new Double2D(maxLng, maxLat)};
  }

  /**
   * Utility method to convert Cell into a Rectangle2D.
   */
  private static Rectangle2D.Double asRectangle(EsOccurrenceHeatmapResponse.Cell cell) {
    return new Rectangle2D.Double(cell.getBounds().getTopLeft().getLon(), cell.getBounds().getTopLeft().getLat(),
      cell.getBounds().getBottomRight().getLon() - cell.getBounds().getTopLeft().getLon(), //width
      cell.getBounds().getTopLeft().getLat() - cell.getBounds().getBottomRight().getLat()); //height
  }
}
