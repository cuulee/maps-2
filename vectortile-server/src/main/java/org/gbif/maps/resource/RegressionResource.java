package org.gbif.maps.resource;

import org.gbif.api.model.occurrence.search.OccurrenceSearchParameter;
import org.gbif.api.model.occurrence.search.OccurrenceSearchRequest;
import org.gbif.api.vocabulary.BasisOfRecord;
import org.gbif.maps.common.projection.Long2D;
import org.gbif.occurrence.search.OccurrenceSearchRequestBuilder;
import org.gbif.occurrence.search.solr.OccurrenceSolrField;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;
import no.ecc.vectortile.VectorTileDecoder;
import no.ecc.vectortile.VectorTileEncoder;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.gbif.maps.resource.Params.enableCORS;
import static org.gbif.maps.resource.Params.mapKey;

/**
 * The resource for the linear regression services.
 * This is the service developed to support the species population trends application and as such is fairly tailored
 * to that need.  Should this become a more generic requirement in the future then this should be refactored.
 */
@Path("/occurrence/regression")
@Singleton
public final class RegressionResource {

  private static final Logger LOG = LoggerFactory.getLogger(RegressionResource.class);
  private static final int HEX_PER_TILE = 35;
  private static final int TILE_SIZE = 4096;
  private static final int TILE_BUFFER = TILE_SIZE / 4; // a generous buffer for hexagons
  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Explicitly name BORs of interest to exclude fossils and living specimens
  private static final List<String> SUITABLE_BASIS_OF_RECORDS = ImmutableList.of(
    "UNKNOWN", // assume unlikely to be fossils or living
    "PRESERVED_SPECIMEN",
    "OBSERVATION",
    "HUMAN_OBSERVATION",
    "MACHINE_OBSERVATION",
    "MATERIAL_SAMPLE",
    "LITERATURE"
  );
  private static final VectorTileDecoder decoder = new VectorTileDecoder();
  static {
    decoder.setAutoScale(false); // important to avoid auto scaling to 256 tiles
  }

  private final TileResource tiles;
  private final SolrClient solrClient;

  public RegressionResource(TileResource tiles, SolrClient solrClient) {
    this.tiles = tiles;
    this.solrClient = solrClient;
  }

  /**
   * Returns the vector tile for hte surface of information which is the result of applying the regression for the
   * given query against the higherTaxonKey.  E.g. It allows you to normalise Puma concolor against Puma or Felidae
   * depending on the higher taxonKey.
   *
   * @param z              The zoom
   * @param x              The tile x
   * @param y              The tile y
   * @param srs            The projection
   * @param higherTaxonKey The taxon key to normalize against.  This is expected to be a higher taxon of the target
   *                       species
   * @param response       The HTTP response
   * @param request        The HTTP request
   *
   * @return The vector tile showing the result of the linear regression
   */
  @GET
  @Path("/{z}/{x}/{y}.mvt")
  @Timed
  @Produces("application/x-protobuf")
  public byte[] hexagonSurface(
    @PathParam("z") int z,
    @PathParam("x") long x,
    @PathParam("y") long y,
    @DefaultValue("EPSG:3857") @QueryParam("srs") String srs,  // default as SphericalMercator
    @QueryParam("year") String year,
    @QueryParam("higherTaxonKey") String higherTaxonKey,
    @DefaultValue("2") @QueryParam("minYears") int minYears,  // 2 years are required for a regression
    @Context HttpServletResponse response,
    @Context HttpServletRequest request
  ) throws Exception {
    enableCORS(response);
    String mapKey = mapKey(request);

    byte[] speciesLayer = tiles.getTile(z, x, y, mapKey, srs, SUITABLE_BASIS_OF_RECORDS, year, true, "hex", HEX_PER_TILE, HEX_PER_TILE);
    mapKey = Params.MAP_TYPES.get("taxonKey") + ":" + higherTaxonKey;
    byte[] higherTaxaLayer = tiles.getTile(z, x, y, mapKey, srs,
                                           SUITABLE_BASIS_OF_RECORDS, year, true, "hex", HEX_PER_TILE, HEX_PER_TILE);

    // determine the global pixel origin address at the top left of the tile, used for uniquely identifying the hexagons
    Long2D originXY = new Long2D(x * TILE_SIZE, y * TILE_SIZE);

    response.setStatus(204);
    return regression(speciesLayer, higherTaxaLayer, minYears, originXY);
  }

  /**
   * Uses the parameters to perform a search on SOLR and returns the regression.  This should use the standard
   * GBIF occurrence API parameters with the addition of higherTaxonKey.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public String adHocRegression(@Context HttpServletRequest request, @Context HttpServletResponse response) throws IOException, SolrServerException {
    enableCORS(response);
    String higherTaxonKey = Preconditions.checkNotNull(request.getParameter("higherTaxonKey"),
                                                       "A higherTaxonKey must be provided to perform regression");
    TreeMap<String, Long> speciesCounts = yearFacetFromSolr(request);
    TreeMap<String, Long> groupCounts = yearFacetFromSolr(request, higherTaxonKey);
    Map<String, Object> meta = regressionToMeta(speciesCounts, groupCounts);
    return MAPPER.writeValueAsString(meta);
  }

  /**
   * Performs the regression for the species and groups generating the meta data.
   * @param speciesCounts
   * @param groupCounts
   * @return
   * @throws JsonProcessingException
   */
  private Map<String, Object> regressionToMeta(TreeMap<String, Long> speciesCounts, TreeMap<String, Long> groupCounts)
    throws JsonProcessingException {
    // normalise all species counts against the group, building the regression the normalized data
    SimpleRegression regression = new SimpleRegression();
    groupCounts.forEach((year, groupCount) -> {
      double speciesCount = speciesCounts.containsKey(year) ? speciesCounts.get(year) : 0.0;
      if (groupCount > 0) { // defensive coding
        regression.addData(Double.valueOf(year), speciesCount / groupCount);
      }
    });

    Map<String, Object> meta = Maps.newHashMap();
    regressionStatsToMeta(meta, regression);
    meta.put("groupCounts", MAPPER.writeValueAsString(groupCounts));
    meta.put("speciesCounts", MAPPER.writeValueAsString(speciesCounts));
    return meta;
  }

  /**
   * Using the HTTP request containing the typical GBIF API parameters creates a SOLR faceted query for the years.
   * If a taxonKey is provided explicitly then all taxon keys in the HTTP request are ignored and replaced with these.
   */
  private TreeMap<String, Long> yearFacetFromSolr(HttpServletRequest request, String... taxonKey)
    throws IOException, SolrServerException {
    OccurrenceSearchRequest searchRequest = new OccurrenceSearchRequest(0, 0);
    Params.setSearchParams(searchRequest, request);

    // force our basis of record to be those that are supported
    searchRequest.getParameters().removeAll(OccurrenceSearchParameter.BASIS_OF_RECORD);
    SUITABLE_BASIS_OF_RECORDS.forEach(bor -> searchRequest.addBasisOfRecordFilter(BasisOfRecord.valueOf(bor)));


    searchRequest.getFacets().clear(); // safeguard against dangerous queries
    searchRequest.addFacets(OccurrenceSearchParameter.YEAR);

    if (taxonKey != null && taxonKey.length > 0) {
      searchRequest.getParameters().get(OccurrenceSearchParameter.TAXON_KEY).clear();
      searchRequest.getParameters().get(OccurrenceSearchParameter.TAXON_KEY).addAll(Lists.newArrayList(taxonKey));
    }

    // Default search request handler, no sort order, 1 record (required) and facet support
    OccurrenceSearchRequestBuilder builder = new OccurrenceSearchRequestBuilder("/search", null, 1, 1, true);
    SolrQuery query = builder.build(searchRequest);

    query.setFacetLimit(300); // safeguard with 3 centuries of data (this is designed for modern day analysis)
    query.setRows(0);

    LOG.debug("SOLR query: {}", query);
    QueryResponse response = solrClient.query(query);
    FacetField years = response.getFacetField(OccurrenceSolrField.YEAR.getFieldName());
    TreeMap<String, Long> yearCounts = Maps.newTreeMap();
    years.getValues().forEach(c -> yearCounts.put(c.getName(), c.getCount()));

    yearCounts.forEach((y, c) -> LOG.debug("{}:{}", y, c));

    LOG.info("SOLR response: {}", yearCounts);
    return yearCounts;
  }

  /**
   * What follows is hastily prepared for the pilot implementation.
   * This should be refactored and unit tests added.
   */
  private byte[] regression(byte[] speciesTile, byte[] groupTile, int minYears, Long2D originXY) throws IOException {

    // build indexes of year counts per hexagon for the species and the group
    Map<String, TreeMap<String, Long>> groupCounts = yearCountsByGeometry(groupTile, originXY);
    Map<String, TreeMap<String, Long>> speciesCounts = yearCountsByGeometry(speciesTile, originXY);

    // An index of the geometries.  This requires 2 decodings of the species tile, so could be optimised
    Map<String, Geometry> speciesGeometries = Maps.newHashMap();
    decoder.decode(speciesTile).forEach(f -> {
      Polygon geom = (Polygon) f.getGeometry();
      String id = getGeometryId(geom, originXY);
      speciesGeometries.put(id, geom);
    });

    VectorTileEncoder encoder = new VectorTileEncoder(TILE_SIZE, TILE_BUFFER, false);
    speciesCounts.entrySet().stream().forEach(e -> {
      try {
        if (groupCounts.containsKey(e.getKey()) && e.getValue().size() >= minYears) {
          Map<String, Object> meta = regressionToMeta(e.getValue(), groupCounts.get(e.getKey()));
          meta.put("id", e.getKey());
          encoder.addFeature("regression", meta, speciesGeometries.get(e.getKey()));
        }

      } catch (JsonProcessingException e1) {
        // ignore
      }
    });
    return encoder.encode();
  }

  /**
   * Decodes a vector tile and extracts the year counts accumulated by the geometry.
   * @param vectorTile
   * @param offsetXY The offset to apply when creating the geometry ID
   * @return A map keys on geometry ID containing counts by year for each geometry
   * @throws IOException if the tile is corrupt
   */
  private static Map<String, TreeMap<String, Long>> yearCountsByGeometry(byte[] vectorTile, Long2D offsetXY) throws IOException {
    Map<String, TreeMap<String, Long>> counts = Maps.newHashMap();
    decoder.decode(vectorTile).forEach(f -> {
      String hexagonId = getGeometryId((Polygon) f.getGeometry(), offsetXY);
      TreeMap<String, Long> years = f.getAttributes().entrySet().stream()
                                 .filter(filterFeaturesToYears()) // discard attributes that aren't year counts
                                 .collect(Collectors.toMap(
                                   e -> e.getKey(),
                                   e -> (Long) e.getValue(),
                                   (c1, c2) -> c1 + c2,
                                   TreeMap::new
                                 ));

      // features can come from different layers in the tile, so we need to merge them in
      if (counts.containsKey(hexagonId)) {

        TreeMap<String, Long> merged =
          Stream.concat(counts.get(hexagonId).entrySet().stream(), years.entrySet().stream())
                .collect(Collectors.toMap(
                  e -> e.getKey(),
                  e -> e.getValue(),
                  (c1, c2) -> c1 + c2,
                  TreeMap::new
                ));

      } else {
        counts.put(hexagonId, years);
      }
    });
    return counts;
  }

  /**
   * Filters to only the entries that have a year:count structure.
   * This is of course making very strict assumptions on the schema of data.
   */
  static Predicate<Map.Entry<String, Object>> filterFeaturesToYears() {
    return new Predicate<Map.Entry<String, Object>>() {
      @Override
      public boolean test(Map.Entry<String, Object> e) {
        try {
          if (Integer.parseInt(e.getKey()) > 0 && e.getValue() instanceof Long) {
            return true;
          }
        } catch(NumberFormatException ex) {
          // expected
        }
        return false;
      }
    };
  }

  /**
   * We use the global pixel address of the first vertex of the hexagon as the unique identifier.
   *
   * @return A globally unique identifier for the hexagon, that will be the same across tile boundaries (i.e. handling
   * buffers).
   */
  private static String getGeometryId(Polygon geom, Long2D originXY) {
    Coordinate vertex = geom.getCoordinates()[0];
    String id = ((int) originXY.getX() + vertex.x) + ":" + ((int) originXY.getY() + vertex.y);
    return id;
  }

  /**
   * Sets the named values in the metadata from the result of the regression.
   */
  private void regressionStatsToMeta(Map<String, Object> meta, SimpleRegression regression) {
    meta.put("slope", regression.getSlope());
    meta.put("intercept", regression.getIntercept());
    meta.put("significance", regression.getSignificance());
    meta.put("SSE", regression.getSumSquaredErrors());
    meta.put("interceptStdErr", regression.getInterceptStdErr());
    meta.put("meanSquareError", regression.getMeanSquareError());
    meta.put("slopeStdErr", regression.getSlopeStdErr());
  }
}
