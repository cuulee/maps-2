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

import java.util.Collections;
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
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
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
 * Elasticsearch as a vector tile service.
 */
@Path("/map/occurrence/adhoc")
@Singleton
public final class EsResource {

  private static final Logger LOG = LoggerFactory.getLogger(EsResource.class);

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  private static final String ESPG_4326 = "EPSG:4326";

  private static final String LAYER_NAME = "occurrence";

  @VisibleForTesting
  static final double QUERY_BUFFER_PERCENTAGE = 0.125;  // 1/8th tile buffer all around, similar to the HBase maps

  private final int tileSize;
  private final int bufferSize;
  private final TileProjection projection;
  private final OccurrenceHeatmapService<EsOccurrenceHeatmapResponse> heatmapService;


  public EsResource(OccurrenceHeatmapService<EsOccurrenceHeatmapResponse> heatmapService, int tileSize, int bufferSize) {
    this.tileSize = tileSize;
    this.bufferSize = bufferSize;
    this.heatmapService = heatmapService;
    projection = Tiles.fromEPSG(ESPG_4326, tileSize);
  }

  @GET
  @Path("/{z}/{x}/{y}.mvt")
  @Timed
  @Produces("application/x-protobuf")
  public byte[] all(@PathParam("type") String type, @PathParam("key") String key,
                    @PathParam("z") int z, @PathParam("x") long x, @PathParam("y") long y,
                    @DefaultValue(ESPG_4326) @QueryParam("srs") String srs,
                    @QueryParam("bin") String bin,
                    @DefaultValue(DEFAULT_HEX_PER_TILE) @QueryParam("hexPerTile") int hexPerTile,
                    @DefaultValue(DEFAULT_SQUARE_SIZE) @QueryParam("squareSize") int squareSize,
                    @Context HttpServletResponse response, @Context HttpServletRequest request) throws Exception {
    enableCORS(response);
    Preconditions.checkArgument(ESPG_4326.equalsIgnoreCase(srs),
                                "Adhoc search maps are currently only available in EPSG:4326");
    OccurrenceHeatmapRequest heatmapRequest = OccurrenceHeatmapRequestProvider.buildOccurrenceHeatmapRequest(request);


    Preconditions.checkArgument(bin == null || BIN_MODE_HEX.equalsIgnoreCase(bin)
        || BIN_MODE_SQUARE.equalsIgnoreCase(bin), "Unsupported bin mode");


    heatmapRequest.setGeometry(searchGeom(z, x, y));
    LOG.debug("Heatmap request:{}", heatmapRequest);

    EsOccurrenceHeatmapResponse heatmapResponse = heatmapService.searchHeatMap(heatmapRequest);
    VectorTileEncoder encoder = new VectorTileEncoder(tileSize, bufferSize, false);

    // iterate the data structure to render tiles
    for (EsOccurrenceHeatmapResponse.GeoGridBucket bucket : heatmapResponse.getBuckets()) {
        if (bucket.getDocCount()  > 0) {
          // convert the lat,lng into pixel coordinates
          Double2D swTileXY = getTopLeftTile(bucket.getCell().getBounds(),z,x,y);
          Double2D neTileXY = getBottomRightTile(bucket.getCell().getBounds(),z,x,y);
          // for binning, we add the cell center point, otherwise the geometry
          encoder.addFeature(LAYER_NAME, Collections.singletonMap("total", bucket.getDocCount()),
            bin != null? getCentroid(swTileXY,neTileXY) : getPolygon(swTileXY,neTileXY));
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
   * Translates the top-left coordinate of a bound into pixel based tile.
   */
  private Double2D getTopLeftTile(EsOccurrenceHeatmapResponse.Bounds bounds, int z, long x, long y) {
    Double2D swGlobalXY = projection.toGlobalPixelXY(bounds.getTopLeft().getLat(), bounds.getTopLeft().getLon(), z);
    return Tiles.toTileLocalXY(swGlobalXY, TileSchema.WGS84_PLATE_CAREÉ, z, x, y, tileSize, bufferSize);
  }

  /**
   * Translates the bottom-right coordinate of a bound into pixel based tile.
   */

  private Double2D getBottomRightTile(EsOccurrenceHeatmapResponse.Bounds bounds, int z, long x, long y) {
    Double2D neGlobalXY = projection.toGlobalPixelXY(bounds.getBottomRight().getLat(), bounds.getBottomRight().getLon(), z);
    return Tiles.toTileLocalXY(neGlobalXY, TileSchema.WGS84_PLATE_CAREÉ, z, x, y, tileSize, bufferSize);
  }

  /**
   * Extracts the centroid of tile described by the top-left and bottom-right points.
   */
  private static Point getCentroid(Double2D swTileXY, Double2D neTileXY) {
    int minX = (int) swTileXY.getX();
    int maxX = (int) neTileXY.getX();
    int minY = (int) swTileXY.getY();
    int maxY = (int) neTileXY.getY();
    double centerY = minY + (((double) maxY - minY) / 2);
    double centerX = minX + (((double) maxX - minX) / 2);
    // hack: use just the center points for each cell
    return GEOMETRY_FACTORY.createPoint(new Coordinate(centerX, centerY));
  }

  /**
   * Creates a WKT polygon based on top-left and bottom-right points.
   */
  private static Polygon getPolygon(Double2D swTileXY, Double2D neTileXY) {
    Coordinate[] coordinates = new Coordinate[] {
      new Coordinate(swTileXY.getX(), swTileXY.getY()),
      new Coordinate(neTileXY.getX(), swTileXY.getY()),
      new Coordinate(neTileXY.getX(), neTileXY.getY()),
      new Coordinate(swTileXY.getX(), neTileXY.getY()),
      new Coordinate(swTileXY.getX(), swTileXY.getY())
    };
    return GEOMETRY_FACTORY.createPolygon(coordinates);
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
    double minLng = to180Degrees((degreesPerTile * x) - 180 - bufferDegrees);
    double maxLng = to180Degrees(minLng + degreesPerTile + (bufferDegrees * 2));

    // clip the extent (ES barfs otherwise)
    double maxLat = Math.min(90 - (degreesPerTile * y) + bufferDegrees, 90);
    double minLat = Math.max(maxLat - degreesPerTile - 2 * bufferDegrees, -90);

    return new Double2D[] {new Double2D(minLng, minLat), new Double2D(maxLng, maxLat)};
  }

  /**
   * if the longitude is expressed from 0..360 it is converted to -180..180.
   */
  private static double to180Degrees(double longitude) {
    if(longitude > 180) {
      return longitude - 360;
    } else if (longitude < -180){
      return longitude + 360;
    }
    return longitude;
  }

}
